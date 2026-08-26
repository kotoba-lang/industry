import importlib.util
import pathlib
import unittest


SCRIPT = pathlib.Path(__file__).parents[1] / "scripts" / "m365-datalake-sync.py"
SPEC = importlib.util.spec_from_file_location("m365_datalake_sync", SCRIPT)
MOD = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MOD)


class M365DatalakeSyncTest(unittest.TestCase):
    def test_parse_annex_key(self):
        self.assertEqual(
            ("MD5E", 12, "0123456789abcdef0123456789abcdef"),
            MOD.parse_annex_key("MD5E-s12--0123456789abcdef0123456789abcdef.pdf"),
        )

    def test_object_key_is_content_addressed(self):
        key = "MD5E-s12--0123456789abcdef0123456789abcdef.pdf"
        self.assertEqual(MOD.object_key(key), MOD.object_key(key))
        self.assertNotIn(".pdf", MOD.object_key(key))

    def test_source_fields_mailbox_and_timestamp(self):
        system, mailbox, captured = MOD.source_fields(
            "mail/keiri/受信トレイ/20260820T074813Z_x.eml",
            "j.kawasaki@gftd.co.jp", {"keiri": "keiri@gftd.co.jp"},
        )
        self.assertEqual("m365-mail", system)
        self.assertEqual("keiri@gftd.co.jp", mailbox)
        self.assertEqual("20260820T074813Z", captured)

    def test_source_fields_signed_in_mailbox(self):
        _, mailbox, _ = MOD.source_fields(
            "mail/受信トレイ/20260820T074813Z_x.eml",
            "j.kawasaki@gftd.co.jp", {"keiri": "keiri@gftd.co.jp"},
        )
        self.assertEqual("j.kawasaki@gftd.co.jp", mailbox)


if __name__ == "__main__":
    unittest.main()
