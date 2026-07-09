#!/usr/bin/env python3
"""Queue yukkuri keyframes on local ComfyUI.

4 assets:
  1. anime_room.png      — chibi-friendly Japanese room background
  2. cyberspace_nodes.png — IPFS DHT network visual
  3. reimu_chibi.png      — Reimu-like shrine maiden chibi (transparent-ish bg)
  4. marisa_chibi.png     — Marisa-like witch chibi
"""
import json, time, urllib.request

BASE = "http://localhost:8188"
NEG_BASE = ("lowres, worst quality, low quality, bad anatomy, bad hands, "
            "missing fingers, extra digit, cropped, text, signature, "
            "watermark, blurry, jpeg artifacts, ugly, mutated, deformed")

JOBS = [
    ("yukkuri-anime-room",
     "masterpiece, best quality, very aesthetic, absurdres, "
     "anime style background, traditional Japanese tatami room, "
     "warm afternoon light through shoji screen, no people, "
     "cozy atmosphere, simple composition, slice of life background, "
     "soft pastel colors, ghibli inspired",
     NEG_BASE + ", people, characters, person, human, multiple panels"),

    ("yukkuri-cyberspace-nodes",
     "masterpiece, best quality, very aesthetic, absurdres, "
     "anime style background, dark cyberspace with glowing network nodes, "
     "distributed system visualization, hexagonal grid pattern, "
     "cyan and purple data streams flowing between nodes, "
     "wireframe globe, futuristic abstract, no people, no text, "
     "synthwave aesthetic",
     NEG_BASE + ", people, characters, person, human, text, multiple panels"),

    ("yukkuri-reimu-chibi",
     "masterpiece, best quality, very aesthetic, absurdres, "
     "1girl, chibi shrine maiden, dark hair with red ribbon, "
     "red and white miko outfit, detached sleeves, "
     "cheerful expression, simple flat colors, "
     "anime chibi style, full body, standing pose facing viewer slightly tilted, "
     "white background, no shadow under feet",
     NEG_BASE + ", complex background, realistic, multiple characters"),

    ("yukkuri-marisa-chibi",
     "masterpiece, best quality, very aesthetic, absurdres, "
     "1girl, chibi witch, blonde long hair with braided side, "
     "black witch hat with white ribbon, black dress with white apron, "
     "confident grin, simple flat colors, "
     "anime chibi style, full body, standing pose facing viewer slightly tilted, "
     "white background, no shadow under feet",
     NEG_BASE + ", complex background, realistic, multiple characters"),
]

def post(payload):
    body = json.dumps(payload).encode()
    req = urllib.request.Request(f"{BASE}/prompt", data=body,
                                 headers={"content-type":"application/json"})
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.loads(r.read())

for prefix, pos, neg in JOBS:
    seed = (int(time.time() * 1000) + hash(prefix) & 0xFFFFFFFF) & 0xFFFFFFFF
    wf = {
        "1": {"class_type":"CLIPSetLastLayer","inputs":{"clip":["4",1],"stop_at_clip_layer":-2}},
        "3": {"class_type":"KSampler","inputs":{
                "seed":seed,"steps":28,"cfg":7.0,
                "sampler_name":"dpmpp_2m","scheduler":"karras","denoise":1.0,
                "model":["4",0],"positive":["6",0],"negative":["7",0],
                "latent_image":["5",0]}},
        "4": {"class_type":"CheckpointLoaderSimple","inputs":{"ckpt_name":"animagine-xl-4.0.safetensors"}},
        "5": {"class_type":"EmptyLatentImage","inputs":{"width":1024,"height":1024,"batch_size":1}},
        "6": {"class_type":"CLIPTextEncode","inputs":{"text":pos,"clip":["1",0]}},
        "7": {"class_type":"CLIPTextEncode","inputs":{"text":neg,"clip":["1",0]}},
        "8": {"class_type":"VAEDecode","inputs":{"samples":["3",0],"vae":["4",2]}},
        "9": {"class_type":"SaveImage","inputs":{"images":["8",0],"filename_prefix":prefix}},
    }
    resp = post({"prompt": wf, "client_id": f"yukkuri-{prefix}"})
    print(f"queued {prefix:<26s} prompt_id={resp.get('prompt_id')[:8]} queue_no={resp.get('number')}", flush=True)
    time.sleep(0.3)
print(f"\n{len(JOBS)} yukkuri assets queued — ETA ~{len(JOBS)*510/60:.0f}min")
