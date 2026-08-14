#!/usr/bin/env python3
"""One-shot Wan2.2 ti2v 5B on local ComfyUI (gad:8188). Not a standing service.

usage: python3 shinshi-wan-i2v-once.py <ref.png> <out.mp4> [prompt]
"""
import hashlib, json, time, uuid, urllib.request, urllib.error, os, sys

COMFY = os.environ.get("COMFY", "http://127.0.0.1:8188")
REF = sys.argv[1] if len(sys.argv) > 1 else "/tmp/akari-ref.png"
OUT = sys.argv[2] if len(sys.argv) > 2 else "/tmp/akari-hoshino-cafe-0.mp4"
PROMPT = (
    sys.argv[3]
    if len(sys.argv) > 3
    else (
        "adult woman, cafe corner, subtle breathing, looking at viewer, "
        "cinematic lighting, gentle motion"
    )
)
NEG = "child, loli, shota, underage, lowres, blurry, extra fingers"
W, H, FRAMES = 512, 768, 49
SEED = int(hashlib.sha256(os.path.basename(REF).encode()).hexdigest()[:8], 16) % (2**31)
PREFIX = "shinshi-" + os.path.splitext(os.path.basename(OUT))[0][:40]


def req(method, path, data=None, headers=None):
    r = urllib.request.Request(
        COMFY + path, data=data, method=method, headers=headers or {}
    )
    with urllib.request.urlopen(r, timeout=120) as resp:
        return resp.status, resp.read()


def upload(path):
    boundary = "----wan" + uuid.uuid4().hex
    filename = os.path.basename(path)
    with open(path, "rb") as f:
        body = f.read()
    parts = (
        ("--" + boundary + "\r\n").encode()
        + f'Content-Disposition: form-data; name="image"; filename="{filename}"\r\n'.encode()
        + b"Content-Type: image/png\r\n\r\n"
        + body
        + b"\r\n--"
        + boundary.encode()
        + b"--\r\n"
    )
    st, raw = req(
        "POST",
        "/upload/image",
        data=parts,
        headers={"Content-Type": f"multipart/form-data; boundary={boundary}"},
    )
    j = json.loads(raw)
    name = j.get("name")
    if not name:
        raise SystemExit("upload failed: " + raw[:200].decode("utf-8", "replace"))
    print("uploaded", name, flush=True)
    return name


def graph(ref_name):
    return {
        "37": {
            "class_type": "UNETLoader",
            "inputs": {"unet_name": "wan2.2_ti2v_5B_fp16.safetensors", "weight_dtype": "default"},
        },
        "38": {
            "class_type": "CLIPLoader",
            "inputs": {"clip_name": "umt5_xxl_fp8_e4m3fn_scaled.safetensors", "type": "wan", "device": "default"},
        },
        "39": {"class_type": "VAELoader", "inputs": {"vae_name": "wan2.2_vae.safetensors"}},
        "6": {"class_type": "CLIPTextEncode", "inputs": {"text": PROMPT, "clip": ["38", 0]}},
        "7": {"class_type": "CLIPTextEncode", "inputs": {"text": NEG, "clip": ["38", 0]}},
        "48": {"class_type": "ModelSamplingSD3", "inputs": {"model": ["37", 0], "shift": 8.0}},
        "52": {"class_type": "LoadImage", "inputs": {"image": ref_name}},
        "55": {
            "class_type": "Wan22ImageToVideoLatent",
            "inputs": {
                "vae": ["39", 0],
                "width": W,
                "height": H,
                "length": FRAMES,
                "batch_size": 1,
                "start_image": ["52", 0],
            },
        },
        "3": {
            "class_type": "KSampler",
            "inputs": {
                "seed": SEED,
                "steps": 20,
                "cfg": 5.0,
                "sampler_name": "uni_pc",
                "scheduler": "simple",
                "denoise": 1.0,
                "model": ["48", 0],
                "positive": ["6", 0],
                "negative": ["7", 0],
                "latent_image": ["55", 0],
            },
        },
        "8": {"class_type": "VAEDecode", "inputs": {"samples": ["3", 0], "vae": ["39", 0]}},
        "57": {"class_type": "CreateVideo", "inputs": {"images": ["8", 0], "fps": 24.0}},
        "58": {
            "class_type": "SaveVideo",
            "inputs": {
                "video": ["57", 0],
                "filename_prefix": PREFIX,
                "format": "auto",
                "codec": "auto",
            },
        },
    }


def main():
    name = upload(REF)
    client_id = uuid.uuid4().hex
    payload = json.dumps({"prompt": graph(name), "client_id": client_id}).encode()
    st, raw = req(
        "POST",
        "/prompt",
        data=payload,
        headers={"Content-Type": "application/json"},
    )
    j = json.loads(raw)
    pid = j.get("prompt_id")
    if not pid:
        raise SystemExit("no prompt_id: " + raw[:400].decode("utf-8", "replace"))
    print("prompt_id", pid, "seed", SEED, flush=True)
    t0 = time.time()
    while time.time() - t0 < 900:
        time.sleep(5)
        try:
            st, raw = req("GET", "/history/" + pid)
        except urllib.error.HTTPError as e:
            print("history", e.code, flush=True)
            continue
        hist = json.loads(raw)
        entry = hist.get(pid) or (next(iter(hist.values())) if hist else None)
        if not entry:
            print("waiting", int(time.time() - t0), "s", flush=True)
            continue
        outs = entry.get("outputs") or {}
        videos = []
        for node, o in outs.items():
            for v in o.get("videos") or o.get("gifs") or []:
                videos.append(v)
            for v in o.get("images") or []:
                if str(v.get("filename", "")).endswith(".mp4"):
                    videos.append(v)
        if videos:
            v = videos[0]
            fn, sub, typ = v.get("filename"), v.get("subfolder") or "", v.get("type") or "output"
            q = f"/view?filename={fn}&subfolder={sub}&type={typ}"
            st, blob = req("GET", q)
            open(OUT, "wb").write(blob)
            print("wrote", OUT, "bytes", len(blob), "filename", fn, flush=True)
            return
        print("running", int(time.time() - t0), "s nodes", list(outs), flush=True)
    raise SystemExit("timeout")


if __name__ == "__main__":
    main()
