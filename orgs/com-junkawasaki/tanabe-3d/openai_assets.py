"""Regenerate OpenAI assets for cyber-drill v20 (12-scene drill flow):

  1. Photoreal Japanese chemical-plant scene photos via gpt-image-1
     (corporate-PR documentation style, workers in PPE, ultra photorealistic)
  2. Narration MP3s via OpenAI TTS (gpt-4o-mini-tts, voice=nova)

Marble world generation is handled separately once credits are added.

Usage:
  source ~/.gftd/openai.env && export OPENAI_API_KEY
  python3 openai_assets.py [--photos | --tts | --all]
"""
from __future__ import annotations

import argparse
import base64
import json
import os
import sys
import urllib.request
from pathlib import Path

OPENAI_API = "https://api.openai.com"

OUT_ROOT = Path.home() / "tanabe-3d/output"
PLANT_DIR = OUT_ROOT / "openai-plants"
DEPLOY_DIR = Path.home() / "tanabe-3d/cyber-drill-marble-v20/public"
VOICE_DIR = DEPLOY_DIR / "voice"
PLANT_DIR.mkdir(parents=True, exist_ok=True)
VOICE_DIR.mkdir(parents=True, exist_ok=True)

# ----------------------------------------------------------------------------
# Corporate brand-photography anchor — Japanese chemical / industrial plant
# documentation aesthetic, like Mitsui Chemicals Operation Service or Yokogawa
# Centum case-study webpage photos.
# ----------------------------------------------------------------------------
_PHOTO_ANCHOR = (
    "ultra photorealistic, hyperrealistic photograph, candid documentary photo, "
    "shot on Canon EOS R5 with RF 35mm f/1.8 IS Macro STM at f/4 1/100 ISO 400, "
    "natural ambient overhead fluorescent lighting only, no studio strobes, no HDR, "
    "absolutely NOT a 3D render, NOT CGI, NOT an AI illustration, NOT a video game screenshot, "
    "slightly imperfect framing, mild handheld micro-shake, real lens vignetting, subtle film grain, "
    "looks like an internal photo posted to a Japanese plant operator company brochure or website, "
    "no readable text or logos but blurry placeholder labels and signage may exist"
)

# 12-scene cyber-drill flow:
PLANT_PROMPTS = [
    ("gate-guard",
     "wide medium-shot of the main gate area of a Japanese petrochemical plant complex, "
     "a small one-story beige security guard booth with white sliding window, behind it a metal swing gate, "
     "a uniformed security guard in a navy jacket and white shirt sitting inside the booth with a clipboard, viewed from a slight angle, his face partially turned away, "
     "in the foreground: a visitor-sign-in board with magnetic name tags, a small grey waste bin, "
     "behind the gate: a paved internal road leading toward a tall stainless flare stack and pipe rack silhouettes against an overcast sky, "
     "a small Japanese-style stop-line painted on the asphalt, a faded yellow chevron pattern, "
     "in the style of an internal corporate documentation photograph for a Japanese chemical company gate access policy webpage, " + _PHOTO_ANCHOR),

    ("central-control-room",
     "wide medium-shot interior of a real Japanese petrochemical plant central operations control room around 2010-2015, "
     "a male plant operator in a grey work uniform shirt with a small ID badge clipped to the chest pocket, sitting on a blue swivel task chair, viewed from a three-quarter angle behind his right shoulder so the screens are visible, "
     "in front of him a long continuous beige melamine desk top with four 24-inch flat-panel monitors displaying colorful P&ID process schematics with primary-color pipe lines, tank-level bar gauges and trend graphs, "
     "papers and A4 binders stacked at the right of the desk, a half-empty mineral water bottle, a desk telephone, "
     "behind the operator an open doorway showing fluorescent-lit corridor with safety posters on the wall, "
     "ceiling: regular grid of fluorescent panel lights, slightly cool ambient illumination, "
     "floor: brown-grey vinyl tile, "
     "in the style of an internal photograph from Mitsui Chemicals Operation Service company webpage, " + _PHOTO_ANCHOR),

    ("engineering-ws",
     "medium-shot interior of an engineering workstation room at a Japanese chemical plant, "
     "a male instrumentation engineer in a light blue button-up work shirt, glasses, sitting at a single workstation with two 27-inch LCDs showing SCADA configuration software with ladder-logic diagrams and function-block schematics in primary colors, "
     "his hands on a keyboard, head tilted slightly to read the screen, "
     "to his right a metal pegboard with cable testers, a multimeter, a few hand tools, "
     "on the desk: stack of printed loop diagrams, a coffee mug, a paper logbook, "
     "ceiling: white acoustic tiles with embedded fluorescent lights, "
     "floor: light grey carpet, "
     "in the style of an instrumentation engineering case-study photograph for a Japanese plant maintenance company, " + _PHOTO_ANCHOR),

    ("soc-monitoring",
     "wide interior of a small dedicated cybersecurity monitoring room at a Japanese chemical plant, "
     "two SOC analysts at adjacent workstations: one male in a polo shirt facing a wall of three 32-inch LCD monitors showing a SIEM dashboard with attack-pattern timeline, network topology graph, and event list, the other a female in a similar uniform standing behind her colleague, "
     "wall behind the workstations: a large mounted projector display showing a Japan map with little dots and warning icons, "
     "ceiling: fluorescent recessed panels, slightly cool color temperature, "
     "floor: industrial dark grey carpet tile, "
     "in the style of an internal security operations center photograph for a Japanese cybersecurity service provider webpage, " + _PHOTO_ANCHOR),

    ("server-room",
     "interior of an industrial control server room inside a Japanese chemical plant, "
     "two parallel rows of beige and dark-grey 42U server rack cabinets receding away from the camera, glass-and-metal front doors, small green and amber status LEDs along the front of each rack, "
     "a single male IT technician in a navy blue jumpsuit with a hi-vis armband, standing in the aisle holding a clipboard, looking at one of the racks, his back partially to the camera, "
     "raised access floor in dark grey perforated tiles, one tile lifted near him exposing cable bundles below, "
     "ceiling cable trays with neat coiled grey CAT5e and fiber bundles, "
     "fluorescent overhead lighting, slightly cool color temperature, no obvious brand logos visible, "
     "in the style of an internal facility audit photograph for a Japanese plant maintenance service, " + _PHOTO_ANCHOR),

    ("plc-panel-room",
     "medium interior of a safety PLC and relay panel room at a Japanese chemical plant, "
     "rows of tall beige steel control cabinets with hinged doors partially open showing rows of terminal blocks, color-coded wiring, small fuse holders, and labelled push-in spring terminals, "
     "a male electrician in a navy work overall and a white hard hat, kneeling in front of one open cabinet and probing a terminal with a multimeter, "
     "ceiling: bare fluorescent fixtures, some painted ductwork visible, "
     "floor: bare grey concrete with a few cable tray entries, "
     "walls: pale beige industrial paint with a few mounted earthquake-bracket angle steel, "
     "in the style of an internal photograph for a Japanese safety instrumentation system case-study, " + _PHOTO_ANCHOR),

    ("field-junction",
     "close medium-shot of an outdoor field junction box on a Japanese chemical plant catwalk, "
     "a square steel weatherproof junction box mounted on a steel column, hinged door open to reveal rows of cable glands and labelled terminal strips with wire end ferrules, "
     "a male field instrument technician in an orange safety vest, blue hard hat, and a face mask, leaning in to check a wire connection with a small flashlight, "
     "background: blurred plant pipe rack with thick insulated steel pipes, valve handles, and a section of grating walkway, overcast sky in the far background, "
     "shot at slight downward angle on the catwalk grating floor, "
     "in the style of an outdoor instrumentation inspection photograph for a Japanese petrochemical plant safety report, " + _PHOTO_ANCHOR),

    ("reactor-floor",
     "wide medium-shot interior of an active Japanese chemical process production floor, "
     "multiple tall stainless-steel reactor and storage tanks with longitudinal weld seams, each tank with a small white identifier placard on a steel frame (placards show generic alphanumeric markings as blurry placeholders), "
     "thick insulated steel pipe networks running between tanks and overhead, yellow-painted valve handles, "
     "structural steel framework supporting an upper walkway grating, "
     "floor: smooth pale-green epoxy resin coating, slightly glossy, with faint reflections of the overhead fluorescent fixtures, "
     "a single technician in a white powder-free coverall, white head cap, blue rubber boots and a white surgical mask, mid-ground, viewed from the side holding a small clipboard tablet, "
     "bright fluorescent overhead lighting in a regular grid, soft shadows on the green floor, "
     "in the style of a Japanese plant engineering company case-study photograph (Kanadevia or Toshiba Plant Systems style), " + _PHOTO_ANCHOR),

    ("tank-yard",
     "wide outdoor view of a Japanese petrochemical tank yard (Kawasaki rinkai-style kombinato), "
     "three to five tall white-painted vertical pressure tanks with longitudinal weld seams visible, weathered rust streaks below flange bolts, "
     "thick insulated steel pipe racks crossing the mid-ground with chipped yellow paint on valve handles, "
     "a single male field inspector in a navy work uniform, white hard hat, orange safety vest, holding a small radio, walking along the lower walkway, viewed from the side, "
     "in the background: a single tall flare stack against an overcast grey sky, low cloud bank, "
     "concrete ground darkened by old oil staining, gravel margin along a chain-link fence, "
     "yellow-and-black hazard chevrons painted on concrete bollards near the foreground, "
     "in the style of a Japanese petrochemical company tank-farm safety report photograph, " + _PHOTO_ANCHOR),

    ("esd-station",
     "close interior medium-shot of an emergency shutdown (ESD) station in a Japanese chemical plant control corridor, "
     "a small wall-mounted beige steel panel with two large red mushroom-head emergency stop pushbuttons under transparent plastic flip covers, labelled with paper inserts behind plastic windows, "
     "below the buttons: a strip of small toggle switches and indicator lamps, a horn-test pushbutton, "
     "a male senior operator in a grey work uniform standing in front of the panel, one hand reaching toward (but not pressing) the larger pushbutton, his face partially in profile, "
     "wall: pale beige industrial paint with a small framed laminated procedure card, "
     "ceiling: low fluorescent corridor lighting, "
     "in the style of an internal procedural training photograph for a Japanese plant safety management webpage, " + _PHOTO_ANCHOR),

    ("cleanroom",
     "interior of a Japanese pharmaceutical or semiconductor cleanroom in active operation, "
     "FFU (fan filter unit) ceiling grid with white fluorescent diffusers at every other cell, "
     "a single male technician in a full white powder-free cleanroom suit covering head and ears, white surgical mask, blue nitrile gloves, white rubber boots, "
     "standing close to a stainless-steel process equipment cabinet at the right, holding a flat tablet in his left hand and adjusting a small dial with his right, looking down at the equipment, "
     "behind him: row of stainless-steel pharma process vessels with sanitary tri-clamp fittings, sight glasses, and a tangle of thin polymer tubing, "
     "floor: pale grey perforated raised cleanroom tile, "
     "walls: white epoxy panels, "
     "bright but slightly cool fluorescent illumination, faint reflection of the lights on the polished stainless steel, "
     "in the style of a Rockwell Automation or Daikin Industries case-study photograph, " + _PHOTO_ANCHOR),

    ("incident-room",
     "wide medium-shot interior of a cyber-incident response coordination room at a Japanese chemical plant, "
     "a long oval meeting table with four people seated around it: a senior plant manager in a navy suit jacket, a security officer in a grey blazer, a younger operator in a grey work uniform, and a female emergency-response coordinator in a polo shirt, all looking toward a large wall-mounted display, "
     "the wall display shows a simplified incident timeline with colored bars, a network topology diagram, and a P&ID overview, "
     "on the table: open A4 binders, several paper-cup coffees, a desk telephone, a few laptops, "
     "ceiling: white acoustic panels with recessed fluorescent fixtures, "
     "walls: pale grey, one wall covered in a printed plant overview map, "
     "floor: light grey carpet tile, "
     "in the style of an internal incident-response training photograph for a Japanese cybersecurity case-study webpage, " + _PHOTO_ANCHOR),
]


def _openai_key() -> str:
    k = os.environ.get("OPENAI_API_KEY", "").strip()
    if not k:
        sys.exit("OPENAI_API_KEY not set — `source ~/.gftd/openai.env && export OPENAI_API_KEY`")
    return k


def gen_image(name: str, prompt: str, *, force: bool = False) -> Path:
    dst = PLANT_DIR / f"{name}.png"
    if dst.exists() and dst.stat().st_size > 50_000 and not force:
        print(f"[{name}] image cached ({dst.stat().st_size//1024} KB) — skip")
        return dst
    body = json.dumps({
        "model": "gpt-image-1",
        "prompt": prompt,
        "n": 1,
        "size": "1024x1024",
        "quality": "high",
    }).encode()
    req = urllib.request.Request(
        f"{OPENAI_API}/v1/images/generations",
        method="POST", data=body,
        headers={"Authorization": f"Bearer {_openai_key()}", "Content-Type": "application/json"},
    )
    with urllib.request.urlopen(req, timeout=240) as r:
        resp = json.loads(r.read())
    raw = base64.b64decode(resp["data"][0]["b64_json"])
    dst.write_bytes(raw)
    print(f"[{name}] image regenerated ({len(raw)//1024} KB)")
    return dst


def gen_tts(name: str, text: str, *, voice: str = "nova", model: str = "gpt-4o-mini-tts") -> Path:
    """OpenAI TTS → MP3."""
    dst = VOICE_DIR / f"{name}.mp3"
    body = json.dumps({
        "model": model,
        "input": text,
        "voice": voice,
        "response_format": "mp3",
        "instructions": "落ち着いた女性アナウンサー風で、緊張感を持って淡々と読み上げる。industrial cyber-drill training simulation context.",
    }).encode()
    req = urllib.request.Request(
        f"{OPENAI_API}/v1/audio/speech",
        method="POST", data=body,
        headers={"Authorization": f"Bearer {_openai_key()}", "Content-Type": "application/json"},
    )
    with urllib.request.urlopen(req, timeout=120) as r:
        audio = r.read()
    dst.write_bytes(audio)
    print(f"[{name}] tts: {len(audio)//1024} KB → {dst.name}")
    return dst


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--photos", action="store_true")
    ap.add_argument("--tts", action="store_true")
    ap.add_argument("--all", action="store_true")
    ap.add_argument("--force", action="store_true", help="re-generate even if cached")
    ap.add_argument("--voice", default="nova", help="OpenAI TTS voice (alloy/echo/fable/onyx/nova/shimmer)")
    ap.add_argument("--only", default=None, help="comma-separated scene names to limit")
    args = ap.parse_args()

    if not (args.photos or args.tts or args.all):
        args.all = True

    _openai_key()
    scenario = json.loads((DEPLOY_DIR / "scenario.json").read_text())
    only = set(args.only.split(",")) if args.only else None

    if args.photos or args.all:
        print("\n=== Photoreal Japanese plant scenes (gpt-image-1 high) ===")
        for name, p in PLANT_PROMPTS:
            if only and name not in only:
                continue
            try:
                gen_image(name, p, force=args.force)
            except Exception as e:
                print(f"[{name}] PHOTO FAIL: {type(e).__name__}: {e}")

    if args.tts or args.all:
        print("\n=== Narration TTS (gpt-4o-mini-tts) ===")
        for name, scene in scenario["scenes"].items():
            if only and name not in only:
                continue
            try:
                gen_tts(name, scene["narration"], voice=args.voice)
            except Exception as e:
                print(f"[{name}] TTS FAIL: {type(e).__name__}: {e}")

    print(f"\nDone. Photos: {PLANT_DIR} | Voice: {VOICE_DIR}")
    print("Deploy: cd ~/tanabe-3d/cyber-drill-marble-v20 && wrangler deploy")


if __name__ == "__main__":
    main()
