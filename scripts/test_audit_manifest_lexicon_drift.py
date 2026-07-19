from __future__ import annotations

import importlib.util
import json
from pathlib import Path

SCRIPT = Path(__file__).with_name("audit-manifest-lexicon-drift.py")
SPEC = importlib.util.spec_from_file_location("manifest_drift", SCRIPT)
assert SPEC and SPEC.loader
audit = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(audit)


def test_exact_west_paths(tmp_path):
    west = tmp_path / "west.yml"
    west.write_text("projects:\n  - name: x\n    path: orgs/etzhayyim/com-etzhayyim-yamabiko\n  - name: bad\n    path: orgs/etzhayyim/root/20-actors/yamabiko\n")
    assert audit.west_etzhayyim_paths(west) == [Path("orgs/etzhayyim/com-etzhayyim-yamabiko")]


def test_eavt_vector_id_is_authoritative(tmp_path):
    wire = tmp_path / "classification.json"
    wire.write_text(json.dumps([{"classification/id": "com.etzhayyim.organizer.classification"}]))
    assert audit.lexicon_file_nsid(wire, "com.etzhayyim.organizer") == "com.etzhayyim.organizer.classification"


def test_ambiguous_eavt_falls_back(tmp_path):
    wire = tmp_path / "mixed.json"
    wire.write_text(json.dumps([{"a/id": "com.etzhayyim.a.one"}, {"b/id": "com.etzhayyim.b.two"}]))
    assert audit.lexicon_file_nsid(wire, "com.etzhayyim.demo") == "com.etzhayyim.demo.mixed"


def test_root_owned_priority_conformance_attestation():
    assert "com.etzhayyim.apps.etzhayyim.priorityConformanceAttestation" in audit.root_owned_nsids()


def test_yamabiko_canonical_edn_contract_is_not_an_orphan(tmp_path, monkeypatch):
    super_root = tmp_path
    owner = super_root / "orgs/etzhayyim/com-etzhayyim-yamabiko"
    wire = owner / "wire/contracts/lexicons"
    wire.mkdir(parents=True)
    (owner / "manifest.edn").write_text(
        '{:actor/lexicons ["com.etzhayyim.yamabiko.trainsetManufactureRecord"]}'
    )
    (wire / "trainsetManufactureRecord.json").write_text(
        json.dumps({"lexicon": 1, "id": "com.etzhayyim.yamabiko.trainsetManufactureRecord", "defs": {}})
    )
    west = super_root / "manifest/west.yml"
    west.parent.mkdir()
    west.write_text(
        "projects:\n  - name: com-etzhayyim-yamabiko\n"
        "    path: orgs/etzhayyim/com-etzhayyim-yamabiko\n"
    )
    monkeypatch.setattr(audit, "WEST_ROOT", super_root)
    monkeypatch.setattr(audit, "WEST_MANIFEST", west)
    monkeypatch.setattr(audit, "ROOT_COMPAT_LEXICONS", super_root / "root-compat")
    result = audit.audit()
    assert result["missing"] == []
    assert result["orphans"] == set()
