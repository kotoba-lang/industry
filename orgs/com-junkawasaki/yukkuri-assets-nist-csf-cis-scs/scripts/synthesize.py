#!/usr/bin/env python3
"""Synthesize yukkuri VOICEVOX wavs from a hand-authored script JSON, pin to IPFS.

Pipeline:
  1. Read input script JSON (scriptwriter format: scenes[].lines[]).
  2. For each line resolve (style_id) from (speaker LEFT/RIGHT + emotion).
  3. POST /audio_query + /synthesis to VOICEVOX (default localhost:50021).
  4. Write wav under wavs/scene-{i}-line-{j}.wav.
  5. `ipfs add` each wav (auto-pins locally) and record CID + size.
  6. Write manifest.json mapping line_id -> {cid, style_id, speaker, emotion, ...}.

Usage:
  python3 scripts/synthesize.py \
    --script ../ai-gftd-apps-gftdcojp/60-apps/ai-gftd-project-yukkuri/examples/scripts/nist-csf-cis-scs.json \
    --manifest manifest.json \
    --wavs-dir wavs \
    --speed 0.95
"""
from __future__ import annotations

import argparse
import hashlib
import json
import subprocess
import sys
import time
from pathlib import Path
from urllib import request

# Mirror lg_yukkuri/voicevox_client.py emotion → style_id mapping.
_EMOTION_STYLE: dict[int, dict[str, int]] = {
    2: {  # 四国めたん (LEFT default)
        "normal": 2,
        "happy": 0,
        "surprised": 6,
        "sad": 36,
        "angry": 6,
        "whisper": 37,
    },
    3: {  # ずんだもん (RIGHT default)
        "normal": 3,
        "happy": 1,
        "surprised": 7,
        "sad": 76,
        "angry": 7,
        "tired": 75,
    },
}

_BASE_STYLE = {"left": 2, "right": 3}


def resolve_style_id(speaker: str, emotion: str) -> int:
    base = _BASE_STYLE.get(speaker, 2)
    table = _EMOTION_STYLE.get(base, {})
    return table.get(emotion, base)


def synthesize(voicevox_url: str, text: str, style_id: int, speed: float, pitch: float) -> bytes:
    q_url = f"{voicevox_url}/audio_query?speaker={style_id}&text={request.quote(text)}"
    req = request.Request(q_url, method="POST")
    with request.urlopen(req, timeout=60) as resp:
        query = json.loads(resp.read())
    if speed != 1.0:
        query["speedScale"] = speed
    if pitch != 0.0:
        query["pitchScale"] = pitch
    body = json.dumps(query).encode("utf-8")
    s_url = f"{voicevox_url}/synthesis?speaker={style_id}"
    req = request.Request(
        s_url,
        method="POST",
        data=body,
        headers={"Content-Type": "application/json", "Accept": "audio/wav"},
    )
    with request.urlopen(req, timeout=120) as resp:
        return resp.read()


def ipfs_add(path: Path) -> str:
    # `ipfs add` auto-pins by default. Use --cid-version 1 for base32 CIDv1 (more portable).
    result = subprocess.run(
        ["ipfs", "add", "--cid-version", "1", "--quieter", str(path)],
        check=True, capture_output=True, text=True,
    )
    return result.stdout.strip()


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--script", required=True, type=Path)
    ap.add_argument("--manifest", default=Path("manifest.json"), type=Path)
    ap.add_argument("--wavs-dir", default=Path("wavs"), type=Path)
    ap.add_argument("--voicevox-url", default="http://localhost:50021")
    ap.add_argument("--speed", type=float, default=0.95)
    ap.add_argument("--pitch", type=float, default=0.0)
    ap.add_argument("--video-id", default="nist-csf-cis-scs")
    args = ap.parse_args()

    script = json.loads(args.script.read_text(encoding="utf-8"))
    args.wavs_dir.mkdir(parents=True, exist_ok=True)

    manifest: dict = {
        "video_id": args.video_id,
        "source_script": str(args.script),
        "voicevox_url": args.voicevox_url,
        "voicevox_version": None,
        "speed": args.speed,
        "pitch": args.pitch,
        "speakers": {
            "left": {"voicevox": "四国めたん", "base_style_id": 2},
            "right": {"voicevox": "ずんだもん", "base_style_id": 3},
        },
        "credit": "VOICEVOX:四国めたん, VOICEVOX:ずんだもん",
        "lines": [],
        "_meta": script.get("_meta", {}),
    }

    try:
        with request.urlopen(f"{args.voicevox_url}/version", timeout=5) as resp:
            manifest["voicevox_version"] = json.loads(resp.read())
    except Exception as exc:
        print(f"warn: could not get voicevox version: {exc}", file=sys.stderr)

    total_lines = sum(len(s.get("lines") or []) for s in script.get("scenes", []))
    print(f"input: {total_lines} lines across {len(script.get('scenes', []))} scenes")
    print(f"voicevox: {args.voicevox_url} version={manifest['voicevox_version']}")
    print(f"wavs_dir: {args.wavs_dir.resolve()}")
    print(f"speed={args.speed} pitch={args.pitch}")
    print()

    t0 = time.monotonic()
    idx = 0
    for i, scene in enumerate(script.get("scenes", [])):
        for j, line in enumerate(scene.get("lines") or []):
            idx += 1
            speaker = line.get("speaker", "left")
            emotion = line.get("emotion", "normal")
            text = (line.get("text") or "").strip()
            if not text:
                continue
            style_id = resolve_style_id(speaker, emotion)
            line_id = f"line-{args.video_id}-{i}-{j}"
            wav_path = args.wavs_dir / f"scene-{i:02d}-line-{j:02d}.wav"
            t_line = time.monotonic()
            try:
                wav_bytes = synthesize(
                    args.voicevox_url, text, style_id, args.speed, args.pitch
                )
            except Exception as exc:
                print(f"[{idx}/{total_lines}] FAIL {line_id} style={style_id}: {exc}")
                manifest["lines"].append({
                    "line_id": line_id, "scene_index": i, "line_index": j,
                    "speaker": speaker, "emotion": emotion, "style_id": style_id,
                    "text": text, "error": str(exc),
                })
                continue
            wav_path.write_bytes(wav_bytes)
            cid = ipfs_add(wav_path)
            sha = hashlib.sha256(wav_bytes).hexdigest()
            dt = time.monotonic() - t_line
            print(
                f"[{idx:>2}/{total_lines}] {speaker:>5} sty={style_id:>2} "
                f"emo={emotion:<9} {len(wav_bytes)/1024:>6.1f}KB {dt:>5.1f}s "
                f"cid={cid} {text[:36]}"
            )
            manifest["lines"].append({
                "line_id": line_id,
                "scene_index": i,
                "line_index": j,
                "speaker": speaker,
                "emotion": emotion,
                "style_id": style_id,
                "voice_preset": f"voicevox:{style_id}",
                "text": text,
                "wav_path": str(wav_path),
                "wav_bytes": len(wav_bytes),
                "wav_sha256": sha,
                "ipfs_cid": cid,
                "duration_synth_sec": round(dt, 2),
            })

    elapsed = time.monotonic() - t0
    manifest["total_lines"] = total_lines
    manifest["succeeded"] = sum(1 for ln in manifest["lines"] if "ipfs_cid" in ln)
    manifest["failed"] = sum(1 for ln in manifest["lines"] if "error" in ln)
    manifest["total_synth_sec"] = round(elapsed, 2)
    manifest["total_wav_bytes"] = sum(ln.get("wav_bytes", 0) for ln in manifest["lines"])

    args.manifest.write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
    print()
    print(f"== done: {manifest['succeeded']}/{total_lines} ok, "
          f"{manifest['failed']} failed, {elapsed:.1f}s total, "
          f"{manifest['total_wav_bytes']/1024:.1f}KB wav ==")
    print(f"manifest: {args.manifest.resolve()}")
    return 0 if manifest["failed"] == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
