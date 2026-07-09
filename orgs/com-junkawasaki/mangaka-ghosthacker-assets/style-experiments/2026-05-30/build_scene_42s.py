#!/usr/bin/env python3
"""Build 42s 6-cut anime scene from MMAudio-conditioned per-cut mp4s.

Narrative arc:
  1. cut05 (8s)  shrine ELS TB        — establishing morning, place
  2. cut01 (8s)  bedroom MS ZOOM_IN   — protagonist, anxious-determined
  3. cut03 (6s)  bedroom CU TU phone  — inciting incident (message)
  4. cut02 (6s)  classroom LS PAN_R   — transition to school
  5. cut06 (7s)  rooftop ECU PAN_L    — emotional climax (tear)
  6. cut04 (7s)  classroom TWO TILT_UP — resolution (friendship)
Total: 42s

Each cut already has MMAudio SFX baked in. Add:
  • Continuous BGM (numpy synth, 'emotional' mood, ducked under voice)
  • Japanese dialogue (edge-tts) at narrative moments
  • Master mix: ffmpeg merges MMAudio SFX + BGM + voice → final mp4
"""
from __future__ import annotations
import asyncio, math, subprocess, sys, wave
from pathlib import Path
import numpy as np
import edge_tts

SR = 44100
GALLERY = Path("/private/tmp/mangaka-gallery/img")
TMP = Path("/tmp/scene42s")
TMP.mkdir(exist_ok=True)
OUT = GALLERY / "animeka-scene-42s.mp4"

# (cut_file, dur_s, dialogue_lines[(t_offset, voice, rate, pitch, text)])
CUTS = [
    ("animeka-cut05-mma.mp4", 8.0, []),  # establishing — no dialogue, just SFX + BGM
    ("animeka-cut01-mma.mp4", 8.0, [
        (1.5, "ja-JP-NanamiNeural", "-15%", "-3Hz", "もう…決めた"),
        (4.5, "ja-JP-NanamiNeural", "-10%", "-2Hz", "行くしかない"),
    ]),
    ("animeka-cut03-mma.mp4", 6.0, [
        (1.8, "ja-JP-NanamiNeural", "+0%",  "-6Hz", "来た…"),
        (3.5, "ja-JP-NanamiNeural", "+5%",  "-4Hz", "どうしよう"),
    ]),
    ("animeka-cut02-mma.mp4", 6.0, [
        (2.0, "ja-JP-NanamiNeural", "+0%",  "+0Hz", "あれ、誰だろう"),
    ]),
    ("animeka-cut06-mma.mp4", 7.0, [
        (2.5, "ja-JP-NanamiNeural", "+0%",  "-8Hz", "ごめん…"),
    ]),
    ("animeka-cut04-mma.mp4", 7.0, [
        (1.8, "ja-JP-NanamiNeural", "+15%", "+3Hz", "ねえ!"),
        (3.2, "ja-JP-KeitaNeural",  "+10%", "+0Hz", "楽しいね"),
        (4.8, "ja-JP-NanamiNeural", "+10%", "+2Hz", "ほんと、最高"),
    ]),
]
TOTAL = sum(c[1] for c in CUTS)
print(f"[scene] total={TOTAL}s ({len(CUTS)} cuts × avg {TOTAL/len(CUTS):.1f}s)")


# ─── concat 6 cut mp4s into a single video+audio track ─────────────────────
def concat_cuts_with_mma():
    """Concat 6 MMAudio-conditioned mp4s. Each already has its synced SFX."""
    # Need to re-encode to ensure stream compatibility (different fps/timebase otherwise)
    list_file = TMP / "concat.txt"
    list_file.write_text("\n".join(f"file '{GALLERY / cf}'" for cf, _, _ in CUTS) + "\n")
    out_av = TMP / "scene42_mma_only.mp4"
    cmd = ["ffmpeg","-y","-f","concat","-safe","0","-i",str(list_file),
           "-c:v","libx264","-preset","medium","-crf","18","-pix_fmt","yuv420p",
           "-c:a","aac","-b:a","160k","-ar","44100","-ac","1",
           "-movflags","+faststart", str(out_av)]
    r = subprocess.run(cmd, capture_output=True, text=True)
    if r.returncode != 0:
        print("concat fail:", r.stderr[-400:]); sys.exit(1)
    print(f"  ✓ scene42_mma_only.mp4  ({out_av.stat().st_size//1024//1024}MB)")
    return out_av


# ─── extract baked MMAudio SFX to numpy ────────────────────────────────────
def extract_sfx_to_array(mp4: Path) -> np.ndarray:
    """Decode mp4's audio track to mono float32 @ SR."""
    cmd = ["ffmpeg","-v","error","-i",str(mp4),"-ac","1","-ar",str(SR),"-f","s16le","-"]
    r = subprocess.run(cmd, capture_output=True)
    if r.returncode != 0:
        print(f"  extract fail: {r.stderr[-300:].decode()}"); return np.zeros(int(TOTAL*SR), dtype=np.float32)
    pcm = np.frombuffer(r.stdout, dtype=np.int16).astype(np.float32) / 32768.0
    return pcm


# ─── BGM synth (port) ─────────────────────────────────────────────────────
_MOOD = dict(
    bpm=72, key_root=261.63, scale=[0,2,4,5,7,9,11],
    melody_oct=1, bass_oct=-2,
    chord_voicing=[(0,7,12),(5,9,14),(4,7,11),(7,11,14)],
    melody_staccato=0.9,
    bgm_vol=0.12, melody_vol=0.10, bass_vol=0.06,  # lower so MMAudio SFX shines
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
    return w*_adsr(n,0.12,0.1,0.6,0.3)
def synth_note(f,dur,stacc):
    n=int(dur*SR); ns=int(max(0.05,stacc)*n)
    if ns<=0: return np.zeros(n)
    w=_sine(f,ns)+0.3*_sine(f*2,ns)+0.1*_sine(f*3,ns)
    env=_adsr(ns,0.03,0.05,0.7,0.08)
    out=np.zeros(n); out[:ns]=w*env
    return out
def synth_bgm(dur_s):
    bpm=_MOOD["bpm"]; beat=60.0/bpm
    n=int(dur_s*SR); bgm=np.zeros(n)
    chord_dur=beat*2; nc=int(chord_dur*SR)
    cf=[[_semi(_MOOD["key_root"],s) for s in v] for v in _MOOD["chord_voicing"]]
    pos=0; ci=0
    while pos<n:
        w=synth_chord(cf[ci%len(cf)],chord_dur)*_MOOD["bgm_vol"]
        end=min(pos+nc,n); bgm[pos:end]+=w[:end-pos]; pos=end; ci+=1
    nd=beat; nn=int(nd*SR)
    scale=_MOOD["scale"]; sh=12*_MOOD["melody_oct"]
    mpat=[0,2,4,2,4,5,4,2]; pos=0; mi=0
    while pos<n:
        deg=scale[mpat[mi%len(mpat)]%len(scale)]
        f=_semi(_MOOD["key_root"],deg+sh)
        w=synth_note(f,nd,_MOOD["melody_staccato"])*_MOOD["melody_vol"]
        end=min(pos+nn,n); bgm[pos:end]+=w[:end-pos]; pos=end; mi+=1
    bd=beat*2; nb=int(bd*SR)
    bpat=[0,5,4,7]; pos=0; bi=0
    while pos<n:
        deg=scale[bpat[bi%len(bpat)]%len(scale)]
        f=_semi(_MOOD["key_root"],deg+12*_MOOD["bass_oct"])
        w=synth_note(f,bd,0.8)*_MOOD["bass_vol"]
        end=min(pos+nb,n); bgm[pos:end]+=w[:end-pos]; pos=end; bi+=1
    return bgm


# ─── TTS ────────────────────────────────────────────────────────────────
async def tts(text, voice, rate, pitch, mp3):
    comm = edge_tts.Communicate(text, voice, rate=rate, pitch=pitch)
    await comm.save(str(mp3))

def decode_mp3(mp3):
    r=subprocess.run(["ffmpeg","-v","error","-i",str(mp3),"-ac","1","-ar",str(SR),"-f","s16le","-"], capture_output=True)
    if r.returncode!=0: return np.zeros(0,dtype=np.float32)
    return np.frombuffer(r.stdout,dtype=np.int16).astype(np.float32)/32768.0

async def synth_all_dialogue():
    print("[1/4] dialogue (edge-tts)")
    clips=[]; cur=0.0
    for cut_file, dur, dialogue in CUTS:
        for (off, voice, rate, pitch, text) in dialogue:
            mp3 = TMP / f"v_{cut_file.replace('.mp4','')}_{int(off*100):03d}.mp3"
            await tts(text, voice, rate, pitch, mp3)
            pcm = decode_mp3(mp3)
            abs_t = cur + off
            clips.append((abs_t, pcm, text, voice))
            print(f"  ✓ t={abs_t:5.2f}s [{voice.split('-')[-1]:>15s}] {text!r:20s} → {len(pcm)/SR:.2f}s")
        cur += dur
    return clips


# ─── main ──────────────────────────────────────────────────────────────
def main():
    print("[2/4] concat 6 mma cuts (video + baked MMAudio SFX)")
    scene_mma = concat_cuts_with_mma()

    clips = asyncio.run(synth_all_dialogue())

    print("[3/4] extract baked SFX + synth BGM + duck under voice")
    mma_sfx = extract_sfx_to_array(scene_mma)
    # Truncate / pad to exactly TOTAL*SR
    n_total = int(TOTAL * SR)
    if len(mma_sfx) > n_total: mma_sfx = mma_sfx[:n_total]
    if len(mma_sfx) < n_total: mma_sfx = np.pad(mma_sfx, (0, n_total - len(mma_sfx)))
    bgm = synth_bgm(TOTAL)

    # Voice + duck
    duck = np.ones(n_total)
    voice_track = np.zeros(n_total)
    for abs_t, pcm, _, _ in clips:
        sn = int(abs_t * SR)
        en = min(sn + len(pcm), n_total)
        seg = pcm[:en-sn]
        voice_track[sn:en] += seg * 1.5
        env = np.ones(en-sn) * 0.45
        fade = int(0.08 * SR)
        if fade*2 < len(env):
            env[:fade] = np.linspace(1.0, 0.45, fade)
            env[-fade:] = np.linspace(0.45, 1.0, fade)
        duck[sn:en] *= env

    # Mix: MMAudio SFX (already in cuts) + BGM (ducked under voice) + voice
    mix = mma_sfx + (bgm * duck) + voice_track
    peak = np.max(np.abs(mix)) or 1.0
    mix = mix / peak * 0.94

    wav = TMP / "scene42.wav"
    with wave.open(str(wav),"wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR)
        w.writeframes((mix*32767).astype(np.int16).tobytes())
    print(f"  ✓ scene42.wav ({wav.stat().st_size//1024}KB)")

    print("[4/4] mux video + mixed audio")
    r = subprocess.run(["ffmpeg","-y","-i",str(scene_mma),"-i",str(wav),
        "-map","0:v","-map","1:a","-c:v","copy",
        "-c:a","aac","-b:a","192k","-ar","44100","-ac","1",
        "-shortest","-movflags","+faststart", str(OUT)],
        capture_output=True, text=True)
    if r.returncode != 0:
        print("mux fail:", r.stderr[-400:]); sys.exit(1)
    print(f"  ✓ {OUT} ({OUT.stat().st_size//1024//1024}MB)")

    deploy = Path("/Users/junkawasaki/gftdcojp/ai-gftd-apps-gftdcojp/60-apps/ai-gftd-project-animeka/appview/ai-gftd-wasm-animeka-an1m3k4x/svelte/build/img/animeka-scene-42s.mp4")
    deploy.write_bytes(OUT.read_bytes())

    out = subprocess.check_output(["ffprobe","-v","error",
        "-show_entries","format=duration,size",
        "-of","default=noprint_wrappers=1",str(OUT)], text=True)
    print(f"\n--- final ---\n{out}")
    print(f"http://localhost:8765/img/animeka-scene-42s.mp4\n")
    print("Narrative + audio layers:")
    cur=0.0
    for cf, dur, dlg in CUTS:
        cut = cf.replace("animeka-","").replace("-mma.mp4","")
        print(f"  {cur:5.1f}–{cur+dur:5.1f}s  {cut:<6s}  MMAudio SFX baked")
        for off, voice, rate, pitch, text in dlg:
            print(f"          t+{off:.1f}s  [{voice.split('-')[-1]}] 「{text}」")
        cur += dur


if __name__ == "__main__":
    main()
