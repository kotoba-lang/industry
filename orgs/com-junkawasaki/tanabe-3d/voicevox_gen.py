"""Generate VOICEVOX narration WAVs for cyber-drill v20 scenes via Modal.

Spins up VOICEVOX engine (CPU image) in a Modal function, hits its HTTP API
to synthesise 5 scene narrations as WAV, downloads them locally so they can
be deployed to the cyber-drill-marble-v20 worker as static assets.

Output: ~/tanabe-3d/cyber-drill-marble-v20/public/voice/<scene>.wav
"""
import json
import os
import time
from pathlib import Path

import modal

OUT_DIR = Path.home() / "tanabe-3d/cyber-drill-marble-v20/public/voice"
OUT_DIR.mkdir(parents=True, exist_ok=True)

SCENARIO_PATH = Path.home() / "tanabe-3d/cyber-drill-marble-v20/public/scenario.json"

app = modal.App("voicevox-narration")

# VOICEVOX engine CPU image — official docker image.
# Modal adds Python 3.11; the engine's own deps (uvicorn/fastapi/numpy/onnx etc)
# must be re-installed for that interpreter. We point PYTHONPATH at the bundled
# engine source so `import voicevox_engine` resolves.
image = (
    modal.Image.from_registry(
        "voicevox/voicevox_engine:cpu-ubuntu20.04-latest",
        add_python="3.11",
    )
    .pip_install(
        # Full runtime dep list from VOICEVOX engine pyproject.toml v0.24
        "fastapi-slim>=0.115.5",
        "jinja2>=3.1.3",
        "kanalizer>=0.1.1",
        "numpy>=2.2.3",
        "onnxruntime",
        "platformdirs>=4.2.0",
        "psutil>=7.1.1",
        "pydantic>=2.7.3",
        "pyopenjtalk-prebuilt",   # fallback; engine prefers voicevox fork but prebuilt works
        "python-multipart>=0.0.20",
        "pyworld-prebuilt",       # CPython wheel; engine prefers pyworld but prebuilt works
        "pyyaml>=6.0.1",
        "semver>=3.0.0",
        "setuptools<82",
        "soundfile>=0.13.1",
        "soxr>=0.5.0",
        "starlette>=0.45.3",
        "uvicorn[standard]>=0.34.0",
        "requests",
    )
    .env({"PYTHONPATH": "/opt/voicevox_engine"})
)

@app.function(image=image, timeout=15 * 60)
def synth_all(scenario: dict) -> dict[str, bytes]:
    """Run VOICEVOX engine internally on this container, return {scene: wav_bytes}."""
    import subprocess
    import threading
    import urllib.request
    import urllib.parse
    import json as _json

    # Locate the engine entry script — try common paths
    import os as _os
    base = "/opt/voicevox_engine"
    candidate_dirs = [base, "/voicevox_engine", "/app"]
    engine_dir = None
    for d in candidate_dirs:
        if _os.path.isdir(d):
            for entry in ("run.py", "main.py", "run"):
                if _os.path.exists(_os.path.join(d, entry)):
                    engine_dir = d
                    entry_file = entry
                    break
            if engine_dir:
                break
    if not engine_dir:
        # Fallback: search filesystem
        for root, _dirs, files in _os.walk("/"):
            if "run.py" in files and "voicevox" in root.lower():
                engine_dir = root
                entry_file = "run.py"
                break
            if root.count("/") > 4:  # don't go too deep
                _dirs.clear()
    if not engine_dir:
        raise RuntimeError("voicevox engine dir not found")

    print(f"engine dir: {engine_dir} entry: {entry_file}", flush=True)
    cmd = (["python", entry_file] if entry_file.endswith(".py")
           else [f"./{entry_file}"]) + ["--host", "127.0.0.1", "--port", "50021"]

    # Start the engine in background
    proc = subprocess.Popen(
        cmd,
        cwd=engine_dir,
        stdout=subprocess.PIPE, stderr=subprocess.PIPE,
    )

    # Wait until /version responds (ONNX models load slowly on CPU, ~30-90s)
    for i in range(180):
        # If subprocess died, surface stderr to surface the real cause
        if proc.poll() is not None:
            stdout, stderr = proc.communicate(timeout=2)
            raise RuntimeError(
                f"voicevox subprocess exited rc={proc.returncode}\n"
                f"stdout:\n{stdout.decode()[-1500:]}\n"
                f"stderr:\n{stderr.decode()[-1500:]}"
            )
        try:
            with urllib.request.urlopen("http://127.0.0.1:50021/version", timeout=2) as r:
                if r.status == 200:
                    print(f"voicevox up after {i}s — version {r.read().decode()}", flush=True)
                    break
        except Exception:
            time.sleep(1)
    else:
        raise RuntimeError("voicevox engine did not start within 180s")

    speaker = scenario.get("speaker", 3)  # default ずんだもん ノーマル
    out: dict[str, bytes] = {}

    for scene_name, scene in scenario["scenes"].items():
        text = scene["narration"]
        print(f"  [{scene_name}] querying speaker={speaker}: {text[:40]}…", flush=True)

        # Step 1: audio_query (returns synthesis params JSON)
        q_url = f"http://127.0.0.1:50021/audio_query?text={urllib.parse.quote(text)}&speaker={speaker}"
        req = urllib.request.Request(q_url, method="POST")
        with urllib.request.urlopen(req, timeout=30) as r:
            query = _json.loads(r.read())

        # Tune for clearer narration
        query["speedScale"] = 1.0
        query["pitchScale"] = 0.0
        query["intonationScale"] = 1.2
        query["volumeScale"] = 1.0
        query["prePhonemeLength"] = 0.1
        query["postPhonemeLength"] = 0.2

        # Step 2: synthesis (returns WAV bytes)
        s_url = f"http://127.0.0.1:50021/synthesis?speaker={speaker}"
        body = _json.dumps(query).encode("utf-8")
        req = urllib.request.Request(s_url, method="POST", data=body, headers={"Content-Type": "application/json"})
        with urllib.request.urlopen(req, timeout=120) as r:
            wav = r.read()
        out[scene_name] = wav
        print(f"  [{scene_name}] {len(wav)//1024} KB WAV", flush=True)

    proc.terminate()
    return out


@app.local_entrypoint()
def main():
    scenario = json.loads(SCENARIO_PATH.read_text())
    print(f"Synthesising {len(scenario['scenes'])} narrations on Modal VOICEVOX…")
    print(f"  speaker={scenario.get('speaker',3)} ({scenario.get('speaker_label','?')})")
    print(f"  output dir: {OUT_DIR}")
    wavs = synth_all.remote(scenario)
    for name, wav in wavs.items():
        dst = OUT_DIR / f"{name}.wav"
        dst.write_bytes(wav)
        print(f"  ✓ {dst} ({len(wav)//1024} KB)")
    print(f"\nNext: cd ~/tanabe-3d/cyber-drill-marble-v20 && wrangler deploy")
