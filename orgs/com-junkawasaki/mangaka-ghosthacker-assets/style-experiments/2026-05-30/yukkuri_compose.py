#!/usr/bin/env python3
"""Compose yukkuri scenes: bg + 2 char sprites + voice + SFX + BGM.

Pipeline per scene:
  1. PIL: bg → resize 1920×1080 → paste Reimu(L, ~38% width) + Marisa(R)
     with luminance-threshold mask for white-bg sprite alpha
     → /tmp/yukkuri-gallery/img/yukkuri-{scene_id}-base.png
  2. ffmpeg: -loop 1 -t duration base.png → scene_silent.mp4
     + drawtext overlay per voice line, fade enable
  3. numpy audio mix:
     - voice clips at abs_offset (extracted from edge-tts mp3s)
     - numpy synth BGM (calm/educational mood)
     - (later) MMAudio SFX via separate ComfyUI pass per scene
  4. ffmpeg mux video+audio → yukkuri-{scene_id}.mp4
  5. ffmpeg concat all scenes → yukkuri-scene-32s.mp4
"""
from __future__ import annotations
import asyncio, json, subprocess, sys, wave, os
from pathlib import Path
import numpy as np
from PIL import Image, ImageOps
import edge_tts

SR = 44100
FPS = 24
W, H = 1920, 1080
GALLERY = Path("/private/tmp/yukkuri-gallery")
IMG = GALLERY / "img"
VOICE = GALLERY / "voice"
TMP = Path("/tmp/yukkuri-scene-build")
TMP.mkdir(exist_ok=True)
OUT_FINAL = IMG / "yukkuri-scene-32s.mp4"

SCRIPT = json.loads(Path("/tmp/yukkuri_scene_script.json").read_text())

# ─── PIL composite (bg + 2 sprites) ─────────────────────────────────────────
def alpha_from_white(img: Image.Image, thresh: int = 240) -> Image.Image:
    """Generate alpha mask by treating near-white pixels as background."""
    img = img.convert("RGBA")
    px = img.load()
    w, h = img.size
    for y in range(h):
        for x in range(w):
            r, g, b, a = px[x, y]
            if r >= thresh and g >= thresh and b >= thresh:
                px[x, y] = (r, g, b, 0)
    return img

def build_base_composite(scene: dict) -> Path:
    """bg + 2 sprites composited. Returns base.png path."""
    bg_path = IMG / f"yukkuri-{scene['background'].replace('_','-').replace('.png','')}.png"
    if not bg_path.exists():
        print(f"  ⚠ bg not found: {bg_path}")
        return None
    bg = Image.open(bg_path).convert("RGB")
    bg = bg.resize((W, H), Image.LANCZOS)

    reimu_path = IMG / "yukkuri-reimu-chibi.png"
    marisa_path = IMG / "yukkuri-marisa-chibi.png"
    if not reimu_path.exists() or not marisa_path.exists():
        print(f"  ⚠ char sprites missing"); return None

    # Reimu on left
    reimu = Image.open(reimu_path)
    reimu = alpha_from_white(reimu)
    target_h = int(H * 0.75)
    target_w = int(reimu.width * target_h / reimu.height)
    reimu = reimu.resize((target_w, target_h), Image.LANCZOS)
    reimu_x = int(W * 0.05)
    reimu_y = H - target_h - 20

    # Marisa on right (mirror so faces center)
    marisa = Image.open(marisa_path)
    marisa = alpha_from_white(marisa)
    marisa = ImageOps.mirror(marisa)
    target_w_m = int(marisa.width * target_h / marisa.height)
    marisa = marisa.resize((target_w_m, target_h), Image.LANCZOS)
    marisa_x = W - target_w_m - int(W * 0.05)
    marisa_y = H - target_h - 20

    bg = bg.convert("RGBA")
    bg.alpha_composite(reimu, dest=(reimu_x, reimu_y))
    bg.alpha_composite(marisa, dest=(marisa_x, marisa_y))

    base = TMP / f"base_{scene['id']}.png"
    bg.convert("RGB").save(base, "PNG")
    return base


# ─── per-scene mp4 with drawtext for dialogue ───────────────────────────────
def build_scene_video(scene: dict, base: Path) -> Path:
    """Compose video with drawtext overlay per voice line."""
    out = TMP / f"scene_{scene['id']}_silent.mp4"
    # Find a font that handles Japanese
    font_candidates = [
        "/System/Library/Fonts/Hiragino Sans GB.ttc",
        "/System/Library/Fonts/Hiragino Sans W3.ttc",
        "/System/Library/Fonts/PingFang.ttc",
        "/System/Library/Fonts/ヒラギノ角ゴシック W5.ttc",
    ]
    font_path = next((f for f in font_candidates if Path(f).exists()), None)
    if not font_path:
        # Fallback to any system .ttc
        try:
            font_path = subprocess.check_output(
                ["find", "/System/Library/Fonts", "-name", "*.ttc"],
                text=True, stderr=subprocess.DEVNULL).splitlines()[0]
        except Exception:
            font_path = "/System/Library/Fonts/Helvetica.ttc"
    print(f"  font: {font_path}")

    # Build drawtext filters — one per line, fade by enable=
    drawtext_filters = []
    voice_manifest = json.loads((GALLERY / "voice_manifest.json").read_text())
    for line_idx, line in enumerate(scene["lines"]):
        t = line["t"]
        # Voice duration from manifest
        vm = next((v for v in voice_manifest
                   if v["scene"] == scene["id"] and v["line"] == line_idx), None)
        dur = vm["duration_s"] if vm else 2.0
        end_t = t + dur + 0.3
        is_left = line["speaker"] == "reimu"
        # Bubble at top, alternating L/R alignment
        x_expr = "w*0.05" if is_left else "w-tw-w*0.05"
        y_expr = "h*0.06"
        color = "0xFFE0E0" if is_left else "0xE0E0FF"   # subtle tint
        # Escape Japanese text for ffmpeg drawtext (single quotes around text)
        # Use textfile via temp txt to handle UTF-8 reliably
        txt_path = TMP / f"txt_{scene['id']}_{line_idx}.txt"
        txt_path.write_text(line["text"], encoding="utf-8")
        drawtext = (
            f"drawtext=fontfile='{font_path}':"
            f"textfile='{txt_path}':"
            f"fontsize=42:fontcolor=black:"
            f"box=1:boxcolor={color}@0.92:boxborderw=20:"
            f"x={x_expr}:y={y_expr}:"
            f"enable='between(t,{t:.3f},{end_t:.3f})'"
        )
        drawtext_filters.append(drawtext)

    vf_chain = ",".join(drawtext_filters) if drawtext_filters else "null"
    cmd = ["ffmpeg", "-y",
           "-loop", "1", "-framerate", str(FPS), "-t", str(scene["duration_s"]),
           "-i", str(base),
           "-vf", vf_chain + ",format=yuv420p",
           "-c:v", "libx264", "-preset", "medium", "-crf", "20",
           "-pix_fmt", "yuv420p",
           str(out)]
    r = subprocess.run(cmd, capture_output=True, text=True)
    if r.returncode != 0:
        print(f"  ✗ scene video build failed:\n{r.stderr[-600:]}")
        return None
    actual = float(subprocess.check_output(
        ["ffprobe","-v","error","-show_entries","format=duration","-of","csv=p=0",str(out)]).strip())
    print(f"  ✓ scene_{scene['id']}_silent.mp4  {scene['duration_s']}s → actual {actual:.2f}s")
    return out


# ─── audio: voice + BGM ─────────────────────────────────────────────────────
def decode_voice(file_name: str) -> np.ndarray:
    mp3 = VOICE / file_name
    r = subprocess.run(
        ["ffmpeg","-v","error","-i",str(mp3),"-ac","1","-ar",str(SR),"-f","s16le","-"],
        capture_output=True)
    if r.returncode != 0: return np.zeros(0, dtype=np.float32)
    return np.frombuffer(r.stdout, dtype=np.int16).astype(np.float32) / 32768.0

# Calm/educational BGM — C major slow piano-like
_MOOD = dict(
    bpm=60, key_root=261.63, scale=[0,2,4,5,7,9,11],
    melody_oct=1, bass_oct=-1,
    chord_voicing=[(0,4,7,12),(5,9,12,16),(4,7,11,14),(7,11,14,19)],
    bgm_vol=0.10, melody_vol=0.08, bass_vol=0.05,
)
def _sine(f,n,ph=0.0):
    t = np.arange(n)/SR; return np.sin(2*np.pi*f*t+ph)
def _adsr(n,a,d,s,r):
    env=np.zeros(n); aN=min(int(a*SR),n); dN=min(int(d*SR),n-aN)
    rN=int(r*SR); sN=max(0,n-aN-dN-rN); rN=min(rN,n-aN-dN-sN)
    if aN: env[:aN]=np.linspace(0,1,aN)
    if dN: env[aN:aN+dN]=np.linspace(1,s,dN)
    if sN: env[aN+dN:aN+dN+sN]=s
    if rN: env[aN+dN+sN:aN+dN+sN+rN]=np.linspace(s,0,rN)
    return env
def _semi(b,st): return b*(2**(st/12.0))
def synth_chord(freqs, dur):
    n=int(dur*SR); w=np.zeros(n)
    for f in freqs: w += _sine(f,n) + 0.2*_sine(f*2,n)
    w/=max(1,len(freqs))
    return w*_adsr(n, 0.2, 0.15, 0.6, 0.4)
def synth_note(f, dur, stacc=0.85):
    n=int(dur*SR); ns=int(stacc*n)
    if ns<=0: return np.zeros(n)
    w=_sine(f,ns)+0.3*_sine(f*2,ns)+0.1*_sine(f*3,ns)
    env=_adsr(ns, 0.04, 0.06, 0.6, 0.1)
    out=np.zeros(n); out[:ns]=w*env
    return out
def synth_bgm(dur_s):
    bpm=_MOOD["bpm"]; beat=60.0/bpm
    n=int(dur_s*SR); bgm=np.zeros(n)
    chord_dur=beat*4; nc=int(chord_dur*SR)
    cf=[[_semi(_MOOD["key_root"],s) for s in v] for v in _MOOD["chord_voicing"]]
    pos=0; ci=0
    while pos<n:
        w=synth_chord(cf[ci%len(cf)], chord_dur)*_MOOD["bgm_vol"]
        end=min(pos+nc,n); bgm[pos:end]+=w[:end-pos]; pos=end; ci+=1
    return bgm


# ─── per-scene full mp4 with audio ──────────────────────────────────────────
def build_scene_with_audio(scene: dict, silent_mp4: Path) -> Path:
    """Add voice + BGM as mixed audio to silent scene mp4."""
    out = TMP / f"scene_{scene['id']}.mp4"
    n_total = int(scene["duration_s"] * SR)
    voice_track = np.zeros(n_total)
    duck = np.ones(n_total)
    voice_manifest = json.loads((GALLERY / "voice_manifest.json").read_text())
    for line_idx, line in enumerate(scene["lines"]):
        vm = next((v for v in voice_manifest
                   if v["scene"] == scene["id"] and v["line"] == line_idx), None)
        if not vm: continue
        pcm = decode_voice(vm["file"])
        sn = int(line["t"] * SR)
        en = min(sn + len(pcm), n_total)
        voice_track[sn:en] += pcm[:en-sn] * 1.5
        # duck BGM
        env = np.ones(en-sn) * 0.4
        fade = int(0.1 * SR)
        if fade*2 < len(env):
            env[:fade] = np.linspace(1.0, 0.4, fade)
            env[-fade:] = np.linspace(0.4, 1.0, fade)
        duck[sn:en] *= env
    bgm = synth_bgm(scene["duration_s"])
    mix = (bgm * duck) + voice_track
    peak = np.max(np.abs(mix)) or 1.0
    mix = mix / peak * 0.94
    wav = TMP / f"scene_{scene['id']}.wav"
    with wave.open(str(wav),"wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR)
        w.writeframes((mix*32767).astype(np.int16).tobytes())
    r = subprocess.run(["ffmpeg","-y","-i",str(silent_mp4),"-i",str(wav),
        "-map","0:v","-map","1:a","-c:v","copy",
        "-c:a","aac","-b:a","160k","-ar","44100","-ac","1",
        "-shortest","-movflags","+faststart",str(out)],
        capture_output=True, text=True)
    if r.returncode != 0:
        print(f"  ✗ mux failed: {r.stderr[-400:]}"); return None
    print(f"  ✓ scene_{scene['id']}.mp4  ({out.stat().st_size//1024}KB)")
    return out


# ─── main ──────────────────────────────────────────────────────────────────
def main():
    print("[1/4] PIL base composites (bg + 2 sprites)")
    bases = []
    for s in SCRIPT["scenes"]:
        b = build_base_composite(s)
        if not b: print(f"  ✗ {s['id']} base failed"); sys.exit(1)
        bases.append(b)

    print("[2/4] per-scene mp4 with drawtext")
    silents = []
    for s, base in zip(SCRIPT["scenes"], bases):
        m = build_scene_video(s, base)
        if not m: print(f"  ✗ {s['id']} video failed"); sys.exit(1)
        silents.append(m)

    print("[3/4] per-scene with audio mix (voice + BGM)")
    finals = []
    for s, silent in zip(SCRIPT["scenes"], silents):
        f = build_scene_with_audio(s, silent)
        if not f: print(f"  ✗ {s['id']} audio failed"); sys.exit(1)
        # Copy per-scene mp4 to gallery
        scene_out = IMG / f"yukkuri-{s['id']}.mp4"
        scene_out.write_bytes(f.read_bytes())
        finals.append(f)

    print("[4/4] concat 3 scenes → final 32s mp4")
    concat_list = TMP / "concat.txt"
    concat_list.write_text("\n".join(f"file '{p}'" for p in finals) + "\n")
    r = subprocess.run(["ffmpeg","-y","-f","concat","-safe","0","-i",str(concat_list),
        "-c:v","libx264","-preset","medium","-crf","20","-pix_fmt","yuv420p",
        "-c:a","aac","-b:a","160k","-ar","44100","-ac","1",
        "-movflags","+faststart", str(OUT_FINAL)],
        capture_output=True, text=True)
    if r.returncode != 0:
        print("  ✗ concat failed:", r.stderr[-400:]); sys.exit(1)

    out_info = subprocess.check_output(
        ["ffprobe","-v","error","-show_entries","format=duration,size",
         "-of","default=noprint_wrappers=1",str(OUT_FINAL)], text=True)
    print(f"\n--- final ---\n{out_info}")
    print(f"http://localhost:8765/yukkuri/img/yukkuri-scene-32s.mp4\n")

if __name__ == "__main__":
    main()
