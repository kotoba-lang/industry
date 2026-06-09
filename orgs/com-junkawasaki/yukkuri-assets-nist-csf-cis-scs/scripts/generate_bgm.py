#!/usr/bin/env python3
"""Generate a yukkuri-friendly ambient BGM placeholder via NumPy synthesis.

Ongakuka cross-project XRPC currently returns 404 from outside and the
in-cluster path is RW-blocked (same B2 SlowDown). Until ongakuka recovers
we paint a calm pad + slow pentatonic arpeggio that loops cleanly under a
4-minute yukkuri lecture without distracting from the dialogue.

Output: bgm/nist-csf-cis-scs.wav (44.1kHz 16-bit mono)
"""
from __future__ import annotations

import argparse
import json
import math
import struct
import subprocess
import sys
from pathlib import Path

import numpy as np


def midi_to_hz(midi: float) -> float:
    return 440.0 * (2.0 ** ((midi - 69) / 12.0))


def sine(t: np.ndarray, hz: float, phase: float = 0.0) -> np.ndarray:
    return np.sin(2 * np.pi * hz * t + phase)


def ipfs_add(path: Path) -> str:
    r = subprocess.run(
        ["ipfs", "add", "--cid-version", "1", "--quieter", str(path)],
        check=True, capture_output=True, text=True,
    )
    return r.stdout.strip()


def synth(duration_sec: float, sr: int = 44100) -> np.ndarray:
    n = int(sr * duration_sec)
    t = np.arange(n) / sr

    # 3-voice pad on C major (C3, E3, G3). Slight detune for chorus warmth.
    pad_midis = [(48, 0.6), (52, 0.5), (55, 0.5)]
    pad = np.zeros(n, dtype=np.float64)
    for midi, gain in pad_midis:
        f = midi_to_hz(midi)
        v = sine(t, f) * 0.6 + sine(t, f * 1.005) * 0.3 + sine(t, f * 2.0) * 0.1
        pad += v * gain

    # slow LFO breathing 0.08 Hz (12.5s period)
    lfo = 0.6 + 0.4 * (0.5 + 0.5 * sine(t, 0.08))
    pad *= lfo

    # pentatonic arpeggio (C minor pentatonic: C4 D#4 F4 G4 A#4) — gentle
    arp_midis = [60, 63, 65, 67, 70]
    arp = np.zeros(n, dtype=np.float64)
    step_sec = 0.5
    note_dur = 1.2
    note_samples = int(sr * note_dur)
    decay = np.exp(-np.arange(note_samples) / (sr * 0.40))
    chime_env = decay * (1 - np.exp(-np.arange(note_samples) / (sr * 0.01)))

    step_idx = 0
    pos = 0
    while pos + note_samples < n:
        midi = arp_midis[step_idx % len(arp_midis)]
        f = midi_to_hz(midi)
        local_t = np.arange(note_samples) / sr
        tone = (sine(local_t, f) * 0.6
                + sine(local_t, f * 2.0) * 0.25
                + sine(local_t, f * 3.0) * 0.10)
        arp[pos:pos + note_samples] += tone * chime_env * 0.20
        pos += int(sr * step_sec * 2)  # every 1 second
        step_idx += 1
        # rest every 5 notes for breathing room
        if step_idx % 5 == 0:
            pos += int(sr * step_sec * 4)

    out = pad * 0.35 + arp * 0.50

    # global fade in/out 4s each
    fade_n = int(sr * 4.0)
    fade_in = np.linspace(0, 1, fade_n)
    fade_out = np.linspace(1, 0, fade_n)
    out[:fade_n] *= fade_in
    out[-fade_n:] *= fade_out

    # normalize peak to -3 dBFS so it sits under voice
    peak = np.max(np.abs(out))
    if peak > 0:
        out = out / peak * 0.71

    return out.astype(np.float32)


def save_wav_pcm16(path: Path, samples: np.ndarray, sr: int = 44100) -> None:
    pcm = np.clip(samples * 32767, -32768, 32767).astype("<i2")
    data = pcm.tobytes()
    with path.open("wb") as f:
        f.write(b"RIFF")
        f.write(struct.pack("<I", 36 + len(data)))
        f.write(b"WAVE")
        f.write(b"fmt ")
        f.write(struct.pack("<I", 16))
        f.write(struct.pack("<H", 1))      # PCM
        f.write(struct.pack("<H", 1))      # mono
        f.write(struct.pack("<I", sr))
        f.write(struct.pack("<I", sr * 2))
        f.write(struct.pack("<H", 2))
        f.write(struct.pack("<H", 16))
        f.write(b"data")
        f.write(struct.pack("<I", len(data)))
        f.write(data)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--out-dir", type=Path, default=Path("bgm"))
    ap.add_argument("--manifest", type=Path, default=Path("manifest.json"))
    ap.add_argument("--video-id", default="nist-csf-cis-scs")
    ap.add_argument("--duration", type=float, default=260.0)  # 4m20s ≥ wav total + tails
    ap.add_argument("--sr", type=int, default=44100)
    args = ap.parse_args()

    args.out_dir.mkdir(parents=True, exist_ok=True)
    samples = synth(args.duration, sr=args.sr)
    wav_path = args.out_dir / f"{args.video_id}.wav"
    save_wav_pcm16(wav_path, samples, sr=args.sr)
    cid = ipfs_add(wav_path)
    size = wav_path.stat().st_size

    manifest = json.loads(args.manifest.read_text(encoding="utf-8")) if args.manifest.exists() else {}
    manifest["bgm"] = {
        "video_id": args.video_id,
        "path": str(wav_path),
        "bytes": size,
        "ipfs_cid": cid,
        "duration_sec": args.duration,
        "sample_rate": args.sr,
        "channels": 1,
        "bit_depth": 16,
        "image_kind": "placeholder",
        "generator": "numpy ambient pad + pentatonic arpeggio (ongakuka unreachable)",
        "key": "C major pad + C minor pentatonic arpeggio",
        "peak_dbfs": -3.0,
    }
    args.manifest.write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"bgm: {wav_path.name} {size/1024:.1f}KB {args.duration:.0f}s cid={cid}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
