#!/usr/bin/env python3
"""Synth all 8 yukkuri dialogue lines via edge-tts.

Reimu  = Nanami +30% rate +6Hz pitch (high robotic-ish narration)
Marisa = Keita  +25% rate +3Hz pitch (brash narration)
"""
import asyncio, json, subprocess, wave
from pathlib import Path
import numpy as np
import edge_tts

SR = 44100
OUT = Path("/tmp/yukkuri-gallery/voice")
OUT.mkdir(parents=True, exist_ok=True)

script = json.load(open("/tmp/yukkuri_scene_script.json"))

async def tts(text, voice, rate, pitch, mp3):
    c = edge_tts.Communicate(text, voice, rate=rate, pitch=pitch)
    await c.save(str(mp3))

def decode(mp3):
    r = subprocess.run(["ffmpeg","-v","error","-i",str(mp3),"-ac","1","-ar",str(SR),"-f","s16le","-"],
                       capture_output=True)
    if r.returncode!=0: return np.zeros(0,dtype=np.float32)
    return np.frombuffer(r.stdout,dtype=np.int16).astype(np.float32)/32768.0

async def main():
    manifest = []
    for s_idx, scene in enumerate(script["scenes"]):
        for l_idx, line in enumerate(scene["lines"]):
            fname = f"{scene['id']}_l{l_idx}_{line['speaker']}.mp3"
            mp3 = OUT / fname
            await tts(line["text"], line["voice"], line["rate"], line["pitch"], mp3)
            pcm = decode(mp3)
            manifest.append({
                "scene": scene["id"], "line": l_idx,
                "speaker": line["speaker"], "t": line["t"],
                "text": line["text"], "voice": line["voice"],
                "rate": line["rate"], "pitch": line["pitch"],
                "file": fname, "duration_s": len(pcm)/SR
            })
            print(f"  ✓ {scene['id']} L{l_idx} [{line['speaker']:>6s}] {len(pcm)/SR:.2f}s «{line['text']}»")
    (OUT.parent / "voice_manifest.json").write_text(json.dumps(manifest, indent=2, ensure_ascii=False))
    print(f"\n  manifest: {OUT.parent / 'voice_manifest.json'}  ({len(manifest)} lines)")

asyncio.run(main())
