"""Install a pure-PyTorch `flash_attn` shim into the phase0 venv only.

`modeling_sdar.py` imports `flash_attn.ops.triton.layer_norm.rms_norm_fn`
unconditionally and uses it as SDARRMSNorm.forward.  It is a *kernel*, not
semantics: the same file carries the reference implementation, commented out,
directly below the call.  This shim provides that reference implementation under
the name the vendor file imports, so the vendor file stays byte-identical to what
was downloaded (auditable) and no attention path changes.

Only `rms_norm_fn` is provided.  `flash_attn_func` and `bert_padding` are
deliberately absent -- modeling_sdar.py imports those inside a try/except and
falls back, and this run uses eager attention, so a silent wrong-kernel path is
impossible: if anything asks for them, it gets ImportError, not a wrong answer.
"""

import os
import site
import subprocess
import sys

VENV_PY = "/home/gad/dllm-phase0/venv/bin/python"

RMS = '''"""Pure-PyTorch stand-in for flash_attn.ops.triton.layer_norm.

Mathematically the RMSNorm that modeling_sdar.py carries as its own commented-out
reference implementation.  Installed by install_flash_attn_shim.py.
"""

import torch


def rms_norm_fn(x, weight=None, bias=None, eps=1e-6, residual=None,
                prenorm=False, residual_in_fp32=False, dropout_p=0.0, **kw):
    if residual is not None or prenorm or dropout_p:
        raise NotImplementedError(
            "shim implements plain RMSNorm only; a caller wanted "
            "residual/prenorm/dropout, which would change semantics silently"
        )
    dtype = x.dtype
    xf = x.float()
    xf = xf * torch.rsqrt(xf.pow(2).mean(-1, keepdim=True) + eps)
    out = xf.to(dtype)
    if weight is not None:
        out = out * weight
    if bias is not None:
        out = out + bias
    return out


def layer_norm_fn(*a, **k):
    raise NotImplementedError("shim provides rms_norm_fn only")
'''


def main():
    sp = subprocess.check_output(
        [VENV_PY, "-c", "import site;print(site.getsitepackages()[0])"], text=True
    ).strip()
    root = os.path.join(sp, "flash_attn")
    for d in (root, f"{root}/ops", f"{root}/ops/triton"):
        os.makedirs(d, exist_ok=True)
    open(f"{root}/__init__.py", "w").write(
        '"""Shim: rms_norm_fn only. See install_flash_attn_shim.py."""\n'
        '__version__ = "0.0.0-shim"\n'
    )
    open(f"{root}/ops/__init__.py", "w").write("")
    open(f"{root}/ops/triton/__init__.py", "w").write("")
    open(f"{root}/ops/triton/layer_norm.py", "w").write(RMS)
    print("installed shim at", root)

    # prove it: shim RMSNorm must match an independent reference to ~bf16 noise
    check = (
        "import torch;"
        "from flash_attn.ops.triton.layer_norm import rms_norm_fn;"
        "x=torch.randn(4,7,64,dtype=torch.bfloat16);w=torch.randn(64,dtype=torch.bfloat16);"
        "a=rms_norm_fn(x,weight=w,bias=None,eps=1e-6);"
        "xf=x.float();r=xf*torch.rsqrt(xf.pow(2).mean(-1,keepdim=True)+1e-6);"
        "b=(w*r.to(x.dtype));"
        "print('max abs diff vs reference:', (a.float()-b.float()).abs().max().item())"
    )
    subprocess.run([VENV_PY, "-c", check], check=True)


if __name__ == "__main__":
    sys.exit(main())
