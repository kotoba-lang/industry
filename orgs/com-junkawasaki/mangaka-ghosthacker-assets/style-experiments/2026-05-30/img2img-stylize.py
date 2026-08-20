"""Hybrid post-processing: Blender Freestyle panel → ComfyUI img2img
SDXL + manga LoRA → stylized manga panel.

Usage:
    python img2img-stylize.py <blender_panel.png> <focal_char> <scene_slug> <shot> <out.png>
"""
import json, os, sys, time, urllib.parse, urllib.request, base64
from pathlib import Path

COMFY = "http://192.168.1.22:8188"

CHAR_LORA = {"Yuto":"yuto_persona.safetensors","Ren":"ren_persona.safetensors","Nei":"nei_persona.safetensors"}

STYLE_PROMPT_PREFIX = (
    # Death Note as primary reference (2026-05-24 recalibration)
    "(Death Note manga page:1.5), (Takeshi Obata art style:1.4), "
    "(small Hideyuki Furuhashi-level character anatomy:1.2), "
    "(hyper-detailed eyes:1.3), (sharp facial features:1.3), "
    "(individual hair strands rendered:1.1), "
    # Death Note specific traits
    "(photo-realistic backgrounds:1.3), (detailed environment objects:1.2), "
    "(stark chiaroscuro:1.2), (deep blacks against bright whites:1.2), "
    "professional manga art, monochrome inked manga, "
    "(sharp black ink lines:1.2), (subtle hatching:0.9), "
    # Brightness — user feedback: 画面が全体的に暗すぎる
    "(bright manga page:1.3), (high key lighting:1.2), "
    "white paper background, daylight scene, "
    # Atmosphere / interaction — user feedback
    "(characters talking to each other:1.2), (lively interaction:1.1), "
    "(animated expressions:1.2), (eye contact between characters:1.1), "
    "gesturing hands, casual atmosphere, "
    "detailed background, panel borders visible, 2D manga not 3D render"
)
# Animagine XL 4.0 公式推奨 negative quality terms (Cagliostro Lab docs):
NEG = ("worst quality, low quality, lowres, displeasing, very displeasing, "
       "bad anatomy, bad hands, missing fingers, extra digits, "
       "blurry, deformed, jpeg artifacts, signature, watermark, "
       "(color:1.5), (blue tint:1.4), (purple tint:1.4), "
       "watercolor, sepia, cyan tint, color leak, photograph, 3d render, "
       # Darkness + emptiness NEG — user feedback: 画面が全体的に暗すぎる
       "(dark page:1.4), (heavy shadow:1.3), (pitch black:1.4), "
       "(empty room:1.3), (no characters:1.3), (static:1.1), "
       # Composition NEG
       "different location, different time of day, "
       "repeated faces, school hallway, street, outdoor")

# Per-scene time-of-day + atmosphere for continuity across panels
SCENE_TIME = {
    "yuto-bedroom":  "late night, room lit only by smartphone screen and desk lamp, "
                      "warm dim lighting, casual home clothing, indoor bedroom interior",
    "classroom":     "afternoon school hours, daylight through windows, "
                      "school uniform, indoor classroom interior",
    "rens-room":     "late evening, monitor wall glow, neon ambiance, "
                      "casual indoor wear, indoor hacker den interior",
    "city-street":   "early evening, street lamps on, urban perspective, "
                      "outdoor casual wear, japanese urban street",
}

CHAR_PROMPTS = {
    # LoRA-free descriptive prompts — Animagine XL 4.0 handles these
    # cleanly without character LoRAs (user feedback: lora が良くない感じ).
    # Detailed enough to give SDXL identity anchor via text alone.
    "Yuto": "1boy, 17 year old Japanese teen male, dark messy black hair, "
             "anxious expression, slim build, white short-sleeve shirt, navy slacks, "
             "school uniform of 3-B class",
    "Ren":  "1boy, 17 year old Japanese teen male, dark hair half-covered with hoodie, "
             "sleepy droopy eyes, casual oversized black hoodie or school uniform",
    "Nei":  "1girl, 16 year old Japanese teen girl, long platinum blonde hair, "
             "calm gentle expression, navy sailor blouse uniform with red ribbon tie, "
             "pleated navy skirt knee-length, white knee socks",
    "Akira":"1boy, 17 year old Japanese teen male, short brown hair, energetic expression, "
             "fashionable street style or school uniform, designer sneakers",
    "Saki": "1girl, 16 year old Japanese teen girl, shoulder-length brown hair, "
             "skeptical raised eyebrow expression, navy sailor uniform with red ribbon",
}

def http(m, p, b=None, h=None):
    r = urllib.request.Request(f"{COMFY}{p}", data=b, method=m, headers=h or {})
    with urllib.request.urlopen(r, timeout=120) as q:
        return q.status, q.read()

def upload_image(local_path: Path) -> str:
    """Upload PNG to ComfyUI input dir; returns the name reference."""
    import urllib.request, mimetypes, uuid
    boundary = "----formboundary" + uuid.uuid4().hex
    fname = f"v5-blender-{local_path.stem}-{uuid.uuid4().hex[:8]}.png"
    body = (
        f"--{boundary}\r\n"
        f'Content-Disposition: form-data; name="image"; filename="{fname}"\r\n'
        f"Content-Type: image/png\r\n\r\n"
    ).encode() + local_path.read_bytes() + f"\r\n--{boundary}--\r\n".encode()
    req = urllib.request.Request(f"{COMFY}/upload/image", data=body,
                                  headers={"Content-Type": f"multipart/form-data; boundary={boundary}"},
                                  method="POST")
    with urllib.request.urlopen(req, timeout=60) as r:
        result = json.loads(r.read())
    return result.get("name") or fname

def submit(wf):
    s, b = http("POST", "/prompt", json.dumps({"prompt": wf}).encode(),
                 {"content-type": "application/json"})
    j = json.loads(b)
    if j.get("node_errors"):
        print(f"  err: {json.dumps(j['node_errors'])[:600]}")
    return j.get("prompt_id")

def wait_for(pid, timeout=300):
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        _, b = http("GET", f"/history/{pid}")
        e = (json.loads(b) or {}).get(pid)
        if e and (e.get("outputs") or e.get("status", {}).get("status_str")
                   in ("error", "success")):
            return e
        time.sleep(3)
    return {}

def fetch_view(filename, subfolder=""):
    q = urllib.parse.urlencode({"filename": filename, "subfolder": subfolder, "type": "output"})
    _, b = http("GET", f"/view?{q}")
    return b

def stylize(panel_png: Path, focal_char: str | None, scene_slug: str,
             shot: str, out_path: Path, denoise: float = 0.32,
             page_seed: int | None = None, panel_idx: int = 0,
             scene_graph: dict | None = None) -> Path | None:
    """ControlNet-Canny + txt2img — Blender output is composition guide only.

    For temporal/spatial continuity within a page:
      - page_seed: same base seed across all panels of a page so the
        character look stays consistent (LoRA + same seed = same face)
      - SCENE_TIME: explicit time-of-day + interior/exterior lock so
        SDXL doesn't drift to hallway / classroom / street between
        panels of the same bedroom scene.
    """
    img_name = upload_image(panel_png)
    char_prompt = CHAR_PROMPTS.get(focal_char or "", "")
    # Multi-char explicit placement — when scene_graph has multiple
    # actors, prepend a sentence telling SDXL where each character is.
    # User feedback: characters invisible because SDXL doesn't know to
    # render multiple figures from multi-mannequin silhouettes alone.
    if scene_graph and len(scene_graph.get("actors", [])) > 1:
        chars = [a["id"] for a in scene_graph["actors"]]
        if focal_char in chars:
            # Focal first, then ambient
            ordered = [focal_char] + [c for c in chars if c != focal_char]
        else:
            ordered = chars
        char_descs = [CHAR_PROMPTS.get(c, c) for c in ordered if c in CHAR_PROMPTS]
        if char_descs:
            # Force SDXL: explicitly list all characters present
            multi_anchor = (
                f"multi-character scene with {len(char_descs)} students visible: "
                + " ; ".join(char_descs)
                + " ; all characters fully drawn with face and body, "
                  "interacting in classroom together, "
                  "(detailed character anatomy:1.3)"
            )
            char_prompt = multi_anchor
    # Character LoRA disabled by default (2026-05-23): user feedback
    # "lora が良くない感じ" — the trained persona LoRAs interfere with
    # SDXL output (abstract output / unstable identity). Falling back
    # to Animagine XL 4.0 base + descriptive text prompts which the
    # base model handles cleanly.
    # Re-enable with V5_USE_LORA=1 env var if needed.
    scene_chars = [focal_char] if (focal_char and
                     os.environ.get("V5_USE_LORA", "0") == "1") else []

    scene_prompt = ""
    weather_season = ""
    if scene_graph:
        scene_prompt = scene_graph.get("setting_lock", "") or SCENE_TIME.get(scene_slug, "")
        atm = scene_graph.get("atmosphere", {})
        weather_season = f"{atm.get('weather','clear')} weather, {atm.get('season','early autumn')}"
    else:
        scene_prompt = SCENE_TIME.get(scene_slug, "")
    shot_prompt = {
        "ECU": "extreme close up face, dramatic emotion",
        "CU":  "close up portrait, detailed face",
        "MS":  "medium shot, character visible waist-up",
        "LS":  "long shot, full body figure in environment",
        "Wide":"wide establishing shot, full environment",
    }.get(shot, "medium shot")
    # Path D: per-scene background prop list (Gemma feedback: backgrounds flat)
    SCENE_PROPS = {
        "yuto-bedroom": "detailed background: study desk with smartphone, "
                        "single bed with crumpled blanket, small window with curtain, "
                        "warm desk lamp, scattered notebooks, anime posters, wooden floor",
        "classroom":    "detailed background: rows of wooden student desks with chairs, "
                        "blackboard with chalk dust, west window blinds with sunbeams, "
                        "ceiling fluorescent lights, wooden floor, teacher's desk at front",
        "school-hierarchy": "detailed background: rows of wooden desks, blackboard, "
                              "west window with afternoon sunbeams and dust particles, "
                              "ceiling lights, wooden floor",
        "rens-room":    "detailed background: multi-monitor wall glowing, research pinboard "
                         "with papers, gaming chair, wall shelves with hardware, closed blinds",
        "neis-room":    "detailed background: vanity mirror with jewelry, open wardrobe, "
                         "posters, single bed with floral blanket, large window with curtains",
        "city-street":  "detailed background: concrete sidewalk with cracks, low residential wall, "
                         "parked bicycle, vending machine, telephone poles with overhead cables",
    }
    bg_detail = SCENE_PROPS.get(scene_slug, "")
    pos_text = ", ".join(filter(None, [
        STYLE_PROMPT_PREFIX, char_prompt, scene_prompt, weather_season, shot_prompt,
        "high contrast black and white, sharp manga ink, screentone shading, hatching",
        bg_detail,
        # Style-consistency anchor (added 2026-05-23): same artist style
        # across all panels of this page — identical ink line weight,
        # same screentone density, same hatching direction.
        "consistent artist style across panels, identical line weight, "
        "uniform screentone density 60lpi, consistent hatching direction, "
        "consistent location lighting and time of day matching adjacent panels",
    ]))

    wf = {
        "ckpt":   {"class_type": "CheckpointLoaderSimple",
                   "inputs": {"ckpt_name": "animagine-xl-4.0.safetensors"}},
        "loadimg":{"class_type": "LoadImage", "inputs": {"image": img_name}},
        "canny":  {"class_type": "Canny",
                   "inputs": {"image": ["loadimg", 0],
                               "low_threshold": 0.1, "high_threshold": 0.3}},
        "cnet":   {"class_type": "ControlNetLoader",
                   "inputs": {"control_net_name": "controlnet-union-sdxl-promax.safetensors"}},
        # CLIP skip 2 — Animagine XL 4.0 公式推奨 for anime/manga style
        "clipskip":{"class_type": "CLIPSetLastLayer",
                    "inputs": {"clip": ["ckpt", 1], "stop_at_clip_layer": -2}},
        "pos":    {"class_type": "CLIPTextEncode",
                   "inputs": {"text": pos_text, "clip": ["clipskip", 0]}},
        "neg":    {"class_type": "CLIPTextEncode",
                   "inputs": {"text": NEG, "clip": ["clipskip", 0]}},
        "cn1":    {"class_type": "ControlNetApply",
                   "inputs": {"conditioning": ["pos", 0], "control_net": ["cnet", 0],
                               "image": ["canny", 0], "strength": 0.55}},
        "lat":    {"class_type": "EmptyLatentImage",
                   "inputs": {"width": 1216, "height": 832, "batch_size": 1}},
        # Seed style-lock (2026-05-23): all panels of same page use the
        # SAME seed — previously seed = page_seed + panel_idx, but the
        # +panel_idx offset caused per-panel style drift visible to
        # users. Now all panels share page_seed so SDXL outputs
        # stylistically consistent panels across the page.
        # Animagine XL 4.0 公式推奨 sampler: dpmpp_2m (non-SDE) + karras
        # The SDE variant injects per-step noise → panel-to-panel
        # variation. Non-SDE is deterministic → style consistency.
        "ks":     {"class_type": "KSampler",
                   "inputs": {"seed": (page_seed or 42),
                               "steps": 28, "cfg": 6.0,
                               "sampler_name": "dpmpp_2m", "scheduler": "karras",
                               "denoise": 1.0,
                               "model": ["ckpt", 0],
                               "positive": ["cn1", 0], "negative": ["neg", 0],
                               "latent_image": ["lat", 0]}},
        "dec":    {"class_type": "VAEDecode",
                   "inputs": {"samples": ["ks", 0], "vae": ["ckpt", 2]}},
        "save":   {"class_type": "SaveImage",
                   "inputs": {"filename_prefix": f"v5-stylized-{panel_png.stem}",
                               "images": ["dec", 0]}},
    }
    # Multi-LoRA chaining (kept opt-in via V5_USE_LORA=1).
    chars_with_lora = [c for c in (scene_chars or [focal_char])
                         if c and c in CHAR_LORA]
    if chars_with_lora and os.environ.get("V5_USE_LORA", "0") == "1":
        prev_model = ["ckpt", 0]
        for i, c in enumerate(chars_with_lora):
            key = f"lora{i}"
            is_focal = (scene_graph and any(a["id"] == c and a.get("is_focal")
                                              for a in scene_graph["actors"])) \
                       if scene_graph else True
            strength = 0.85 if is_focal else 0.50
            wf[key] = {"class_type": "LoraLoaderModelOnly",
                       "inputs": {"model": prev_model,
                                   "lora_name": CHAR_LORA[c],
                                   "strength_model": strength}}
            prev_model = [key, 0]
        wf["ks"]["inputs"]["model"] = prev_model

    # Regional prompting — Path C (2026-05-24): split panel into character zones.
    # For multi-character panels (Wide/LS shots with ambient cast), split
    # the latent canvas into zones with per-character prompts.
    # Each character zone gets focal character_prompt; background zone
    # gets the scene + background detail.
    # Activates when V5_USE_REGIONAL=1 AND len(scene_chars) > 1.
    if (os.environ.get("V5_USE_REGIONAL", "0") == "1"
        and scene_graph and len(scene_graph.get("actors", [])) > 1):
        actors = scene_graph["actors"]
        n = len(actors)
        # Width per zone = full latent / n
        zone_w = 1216 // n
        base_cond = ["cn1", 0]   # ControlNet-applied positive cond
        # Per-actor zone masks
        regional_conds = []
        for i, actor in enumerate(actors):
            char = actor["id"]
            char_prompt_solo = CHAR_PROMPTS.get(char, "")
            if not char_prompt_solo:
                continue
            zone_x = i * zone_w
            zone_x2 = (i + 1) * zone_w if i < n - 1 else 1216
            # Per-zone prompt: focus on this character
            zone_prompt = (
                f"{STYLE_PROMPT_PREFIX}, {char_prompt_solo}, "
                f"single character {char}, manga ink line art"
            )
            wf[f"pos_z{i}"] = {"class_type": "CLIPTextEncode",
                                "inputs": {"text": zone_prompt,
                                            "clip": ["clipskip", 0]}}
            wf[f"mask_z{i}"] = {"class_type": "SolidMask",
                                 "inputs": {"value": 1.0,
                                             "width":  zone_x2 - zone_x,
                                             "height": 832}}
            # Pad mask to full size (panel-position)
            wf[f"cond_z{i}"] = {"class_type": "ConditioningSetMask",
                                 "inputs": {
                                     "conditioning": [f"pos_z{i}", 0],
                                     "mask":         [f"mask_z{i}", 0],
                                     "strength":     0.9,
                                     "set_cond_area":"default",
                                 }}
            regional_conds.append([f"cond_z{i}", 0])
        # Combine regional conds with base ControlNet cond
        if regional_conds:
            # Cascade ConditioningCombine across all regional
            prev_cond = base_cond
            for i, rc in enumerate(regional_conds):
                key = f"comb_{i}"
                wf[key] = {"class_type": "ConditioningCombine",
                           "inputs": {"conditioning_1": prev_cond,
                                       "conditioning_2": rc}}
                prev_cond = [key, 0]
            wf["ks"]["inputs"]["positive"] = prev_cond
            print(f"  [regional] {len(regional_conds)} character zones wired")

    # IPAdapter FaceID — Path B wired via direct loaders (2026-05-25).
    # ComfyUI host verified to have:
    #   models/ipadapter/ip-adapter-faceid-plusv2_sdxl.bin    (1.4 GB)
    #   models/clip_vision/CLIP-ViT-H-14-laion2B-s32B-b79K.safetensors
    #   models/insightface/models/antelopev2 + buffalo_l
    # PLUS FACE preset unavailable (missing ip-adapter-plus-face variant);
    # use IPAdapterModelLoader direct path instead.
    if (os.environ.get("V5_USE_IPADAPTER", "0") == "1" and focal_char):
        face_ref = Path(f"/tmp/character-faces/{focal_char.lower()}-face.png")
        if face_ref.exists():
            face_name = upload_image(face_ref)
            wf["ipa_model_load"] = {
                "class_type": "IPAdapterModelLoader",
                "inputs": {"ipadapter_file": "ip-adapter-faceid-plusv2_sdxl.bin"}}
            wf["clip_vision"] = {
                "class_type": "CLIPVisionLoader",
                "inputs": {"clip_name": "CLIP-ViT-H-14-laion2B-s32B-b79K.safetensors"}}
            wf["insight"] = {
                "class_type": "IPAdapterInsightFaceLoader",
                "inputs": {"provider": "CPU", "model_name": "buffalo_l"}}
            wf["ipa_img"] = {"class_type": "LoadImage",
                              "inputs": {"image": face_name}}
            wf["ipa_face"] = {"class_type": "IPAdapterFaceID",
                               "inputs": {
                                   "model":           wf["ks"]["inputs"]["model"],
                                   "ipadapter":       ["ipa_model_load", 0],
                                   "image":           ["ipa_img", 0],
                                   "weight":          0.75,
                                   "weight_faceidv2": 1.0,
                                   "weight_type":     "linear",
                                   "combine_embeds":  "concat",
                                   "start_at":        0.0,
                                   "end_at":          1.0,
                                   "embeds_scaling":  "V only",
                                   "insightface":     ["insight", 0],
                                   "clip_vision":     ["clip_vision", 0],
                               }}
            wf["ks"]["inputs"]["model"] = ["ipa_face", 0]
            print(f"  [ipadapter] {focal_char} face ref loaded via direct path")

    pid = submit(wf)
    if not pid:
        return None
    e = wait_for(pid, timeout=300)
    outs = e.get("outputs") or {}
    fname = None
    for nid, o in outs.items():
        for im in o.get("images", []):
            fname = im["filename"]; break
        if fname: break
    if not fname:
        return None
    data = fetch_view(fname, "")
    out_path.write_bytes(data)
    return out_path

if __name__ == "__main__":
    panel = Path(sys.argv[1])
    focal = sys.argv[2] if sys.argv[2] != "-" else None
    scene = sys.argv[3]
    shot = sys.argv[4]
    out = Path(sys.argv[5])
    denoise = float(sys.argv[6]) if len(sys.argv) > 6 else 0.32
    r = stylize(panel, focal, scene, shot, out, denoise=denoise)
    print(f"OK: {r}" if r else "FAIL")
