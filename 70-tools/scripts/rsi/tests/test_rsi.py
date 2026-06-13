"""RSi ecosystem tests — no network, no GPU, no EVO required."""
from __future__ import annotations
import json, tempfile
from pathlib import Path
import sys, os

# Make rsi importable from this test
sys.path.insert(0, str(Path(__file__).parents[3]))


# ---------------------------------------------------------------------------
# cid.py
# ---------------------------------------------------------------------------

def test_cid_deterministic():
    from rsi.cid import cid_of_str
    a = cid_of_str("hello")
    b = cid_of_str("hello")
    assert a == b, "CID must be deterministic"


def test_cid_distinct():
    from rsi.cid import cid_of_str
    assert cid_of_str("a") != cid_of_str("b")


def test_cid_prefix():
    from rsi.cid import cid_of_str
    assert cid_of_str("test").startswith("b"), "CIDv1 base32 multibase prefix = b"


def test_cid_bytes_vs_str():
    from rsi.cid import cid_of, cid_of_str
    assert cid_of(b"hello") == cid_of_str("hello")


# ---------------------------------------------------------------------------
# corpus.py
# ---------------------------------------------------------------------------

def _make_pair(id_: str, clj: str = "(defn foo [] :ok)", scan: str = "ok") -> dict:
    return {
        "id": id_,
        "messages": [
            {"role": "user", "content": "Convert foo"},
            {"role": "model", "content": clj},
        ],
        "meta": {"fn": id_.split("/")[-1], "scan": scan},
    }


def test_corpus_ingest_dedup(tmp_path, monkeypatch):
    from rsi import corpus as c_mod
    # Redirect corpus file to tmp
    corpus_file = tmp_path / "corpus.jsonl"
    monkeypatch.setattr(c_mod, "SFT_CORPUS", corpus_file)

    # Mock kotoba bridge (no network)
    monkeypatch.setattr(c_mod, "write_corpus_pair", lambda *a, **k: True)
    # Mock charter scan + clj gate to pass
    monkeypatch.setattr(c_mod, "_charter_scan", lambda t: "skip")
    monkeypatch.setattr(c_mod, "_clj_gate",     lambda t: "skip")

    source = tmp_path / "source.jsonl"
    pairs = [_make_pair("actor/foo"), _make_pair("actor/bar")]
    source.write_text("\n".join(json.dumps(p) for p in pairs))

    n = c_mod.ingest_jsonl(source)
    assert n == 2

    # Second ingest: same pairs → dedup → 0 new
    n2 = c_mod.ingest_jsonl(source)
    assert n2 == 0


def test_corpus_size(tmp_path, monkeypatch):
    from rsi import corpus as c_mod
    corpus_file = tmp_path / "corpus.jsonl"
    monkeypatch.setattr(c_mod, "SFT_CORPUS", corpus_file)

    corpus_file.write_text(
        json.dumps(_make_pair("a/b")) + "\n" +
        json.dumps(_make_pair("c/d")) + "\n"
    )
    assert c_mod.corpus_size() == 2


def test_corpus_size_empty(tmp_path, monkeypatch):
    from rsi import corpus as c_mod
    monkeypatch.setattr(c_mod, "SFT_CORPUS", tmp_path / "no.jsonl")
    assert c_mod.corpus_size() == 0


def test_charter_fail_skips(tmp_path, monkeypatch):
    from rsi import corpus as c_mod
    corpus_file = tmp_path / "corpus.jsonl"
    monkeypatch.setattr(c_mod, "SFT_CORPUS", corpus_file)
    monkeypatch.setattr(c_mod, "write_corpus_pair", lambda *a, **k: True)
    monkeypatch.setattr(c_mod, "_charter_scan", lambda t: "fail:weapons")
    monkeypatch.setattr(c_mod, "_clj_gate",     lambda t: "ok")

    source = tmp_path / "source.jsonl"
    source.write_text(json.dumps(_make_pair("actor/bad")))
    n = c_mod.ingest_jsonl(source)
    assert n == 0, "Charter fail must be rejected"


def test_clj_gate_fail_skips(tmp_path, monkeypatch):
    from rsi import corpus as c_mod
    corpus_file = tmp_path / "corpus.jsonl"
    monkeypatch.setattr(c_mod, "SFT_CORPUS", corpus_file)
    monkeypatch.setattr(c_mod, "write_corpus_pair", lambda *a, **k: True)
    monkeypatch.setattr(c_mod, "_charter_scan", lambda t: "skip")
    monkeypatch.setattr(c_mod, "_clj_gate",     lambda t: "fail")

    source = tmp_path / "source.jsonl"
    source.write_text(json.dumps(_make_pair("actor/bad_clj", "(not clojure!!)")))
    n = c_mod.ingest_jsonl(source)
    assert n == 0, "Clj gate fail must be rejected"


# ---------------------------------------------------------------------------
# eval.py — scoring (local, no GPU)
# ---------------------------------------------------------------------------

def test_score_pass():
    from rsi.eval import _score_clj
    assert _score_clj("(defn foo [x] (* x 2))")


def test_score_fail_unbalanced():
    from rsi.eval import _score_clj
    assert not _score_clj("(defn foo [x] (* x 2)")


def test_score_gens(tmp_path):
    from rsi.eval import score_gens, score_base_gens
    gen_file = tmp_path / "gen.jsonl"
    gen_file.write_text(
        json.dumps({"name": "a", "base_out": "(defn a [])", "new_out": "(defn b [])"}) + "\n" +
        json.dumps({"name": "b", "base_out": "(broken",     "new_out": "(broken"    }) + "\n"
    )
    assert score_gens(gen_file)      == 50.0
    assert score_base_gens(gen_file) == 50.0


# ---------------------------------------------------------------------------
# loop.py — state helpers (no train/deploy)
# ---------------------------------------------------------------------------

def test_next_run_number_empty(tmp_path, monkeypatch):
    from rsi import loop as l_mod, config as cfg
    monkeypatch.setattr(cfg, "MODELS_LOG", str(tmp_path / "models.jsonl"))
    monkeypatch.setattr(l_mod, "MODELS_LOG", tmp_path / "models.jsonl")
    assert l_mod._next_run_number() == 1


def test_next_run_number_with_runs(tmp_path, monkeypatch):
    from rsi import loop as l_mod
    log = tmp_path / "models.jsonl"
    log.write_text(
        json.dumps({"run_id": "maxwell-0001", "corpus_size": 100}) + "\n" +
        json.dumps({"run_id": "maxwell-0002", "corpus_size": 200}) + "\n"
    )
    monkeypatch.setattr(l_mod, "MODELS_LOG", log)
    assert l_mod._next_run_number() == 3


def test_last_corpus_size_empty(tmp_path, monkeypatch):
    from rsi import loop as l_mod
    monkeypatch.setattr(l_mod, "MODELS_LOG", tmp_path / "no.jsonl")
    assert l_mod._last_corpus_size_at_train() == 0


def test_last_corpus_size(tmp_path, monkeypatch):
    from rsi import loop as l_mod
    log = tmp_path / "models.jsonl"
    log.write_text(
        json.dumps({"run_id": "maxwell-0001", "corpus_size": 137}) + "\n"
    )
    monkeypatch.setattr(l_mod, "MODELS_LOG", log)
    assert l_mod._last_corpus_size_at_train() == 137


# ---------------------------------------------------------------------------
# kotoba_bridge.py — no-network unit (mock urllib)
# ---------------------------------------------------------------------------

def test_bridge_write_noop_on_failure(monkeypatch):
    """write_* functions must return False but not raise on network failure."""
    import urllib.request
    def _fail(*a, **kw):
        raise OSError("no network")
    monkeypatch.setattr(urllib.request, "urlopen", _fail)

    from rsi import kotoba_bridge as kb
    assert kb.write_corpus_pair("cid1", "id1", "actor", "fn", "skip", "skip") is False
    assert kb.write_run("r1","c1","m","e",1,10,"p","c",1.0,1.0) is False
    assert kb.write_eval("e1","r1",20.0,21.0,1.0,54,"deploy") is False
    assert kb.write_checkpoint("cp1","r1","m1","hf",20.0,21.0) is False


# ---------------------------------------------------------------------------
# deploy.py — verdict gate (no fleet)
# ---------------------------------------------------------------------------

def test_deploy_dry_run(monkeypatch):
    from rsi import deploy as d_mod

    calls = []
    monkeypatch.setattr(d_mod, "_ssh", lambda *a, **k: type("R",(),{"returncode":0,"stdout":"merged → x"})())
    monkeypatch.setattr(d_mod, "_ollama_create", lambda *a, **k: True)
    monkeypatch.setattr(d_mod, "write_checkpoint", lambda **k: True)

    run_rec  = {"run_id": "maxwell-0001",
                "checkpoint_path": f"gad:~/rsi/maxwell-0001/adapter"}
    eval_rec = {"base_pp": 20.0, "new_pp": 22.0, "delta_pp": 2.0, "eval_id": "abc123"}

    result = d_mod.deploy(run_rec, eval_rec, 1, dry_run=True)
    assert result is True


# ---------------------------------------------------------------------------
# harvest.py — discover unharvested (no fleet call)
# ---------------------------------------------------------------------------

def test_discover_unharvested(tmp_path, monkeypatch):
    from rsi import harvest as h_mod, corpus as c_mod

    # Set up fake actor methods directory
    actor_dir = tmp_path / "20-actors" / "foo" / "methods"
    actor_dir.mkdir(parents=True)
    (actor_dir / "bar.py").write_text("def bar(): pass")
    (actor_dir / "baz.py").write_text("def baz(): pass")

    # Empty corpus
    corpus_file = tmp_path / "corpus.jsonl"
    monkeypatch.setattr(c_mod, "SFT_CORPUS", corpus_file)

    found = h_mod.discover_unharvested_py([actor_dir])
    assert len(found) == 2


def test_discover_skips_seen(tmp_path, monkeypatch):
    from rsi import harvest as h_mod, corpus as c_mod

    actor_dir = tmp_path / "20-actors" / "foo" / "methods"
    actor_dir.mkdir(parents=True)
    py = actor_dir / "bar.py"
    py.write_text("def bar(): pass")

    corpus_file = tmp_path / "corpus.jsonl"
    pair = _make_pair("foo/bar")
    pair["meta"]["src_py"] = str(py)
    corpus_file.write_text(json.dumps(pair))
    monkeypatch.setattr(c_mod, "SFT_CORPUS", corpus_file)

    found = h_mod.discover_unharvested_py([actor_dir])
    assert len(found) == 0
