import importlib.util
import pathlib
import unittest


SCRIPT = pathlib.Path(__file__).parents[1] / "scripts" / "ses-mail-datalake-sync.py"
SPEC = importlib.util.spec_from_file_location("ses_mail_datalake_sync", SCRIPT)
MOD = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MOD)


class SesMailDatalakeSyncTest(unittest.TestCase):
    def test_normalize_rejects_unknown_fields(self):
        with self.assertRaisesRegex(ValueError, "unknown fields"):
            MOD.normalize_rows(
                [{"record_id": "x", "source": "outlook", "message_id": "m", "raw_body": "no"}],
                MOD.SCHEMAS["ses_mail"],
                "2026-08-26T00:00:00Z",
            )

    def test_classification_projection_does_not_define_raw_body_columns(self):
        names = set(MOD.SCHEMAS["ses_mail"].names)
        self.assertNotIn("body", names)
        self.assertIn("body_sha256", names)

    def test_raw_projection_keeps_body_payload_and_rfc822(self):
        names = set(MOD.SCHEMAS["ses_mail_raw"].names)
        self.assertIn("body_content", names)
        self.assertIn("connector_payload_json", names)
        self.assertIn("raw_rfc822", names)

    def test_binary_fields_decode_base64(self):
        rows = MOD.normalize_rows(
            [{"record_id": "a", "source": "gmail", "message_id": "m", "content": "aGVsbG8="}],
            MOD.SCHEMAS["ses_mail_attachment"],
            "2026-08-26T00:00:00Z",
        )
        self.assertEqual(b"hello", rows[0]["content"])
        MOD.add_content_hashes("ses_mail_attachment", rows)
        self.assertEqual(64, len(rows[0]["content_sha256"]))

    def test_dedupe_is_last_write_wins(self):
        rows = [
            {"record_id": "m1", "summary": "old"},
            {"record_id": "m1", "summary": "new"},
            {"record_id": "m2", "summary": "other"},
        ]
        got = MOD.dedupe_rows(rows)
        self.assertEqual(["m1", "m2"], [row["record_id"] for row in got])
        self.assertEqual("new", got[0]["summary"])

    def test_merge_preserves_existing_and_replaces_same_record(self):
        schema = MOD.SCHEMAS["ses_candidate"]
        old = MOD.normalize_rows(
            [{"record_id": "m1#1", "source": "outlook", "message_id": "m1", "provider": "old"}],
            schema, "2026-08-25T00:00:00Z",
        )
        new = MOD.normalize_rows(
            [{"record_id": "m1#1", "source": "outlook", "message_id": "m1", "provider": "new"},
             {"record_id": "m2#1", "source": "outlook", "message_id": "m2", "provider": "other"}],
            schema, "2026-08-26T00:00:00Z",
        )
        merged = MOD.merge_table(MOD.arrow(old, schema), new, schema).to_pylist()
        self.assertEqual(2, len(merged))
        self.assertEqual("new", merged[0]["provider"])


if __name__ == "__main__":
    unittest.main()
