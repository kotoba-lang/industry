"""Content-addressing for RSi artifacts (ipfs-parity CIDv1 raw/sha2-256).

Reuses the same pattern as rasen/ibuki/shionome — byte-identical to `ipfs add --raw-leaves`.
"""
from __future__ import annotations
import hashlib, struct


def _varint(n: int) -> bytes:
    out = []
    while True:
        b = n & 0x7F
        n >>= 7
        if n:
            out.append(b | 0x80)
        else:
            out.append(b)
            break
    return bytes(out)


def cid_of(data: bytes) -> str:
    """Return CIDv1 base32-lower for raw bytes (codec 0x55, sha2-256)."""
    digest = hashlib.sha256(data).digest()
    # multihash: varint(0x12) + varint(32) + digest
    mh = bytes([0x12, 0x20]) + digest
    # CIDv1: version=1 + codec=raw(0x55) + multihash
    raw_cid = bytes([0x01, 0x55]) + mh

    # base32 lower (RFC 4648, no padding), multibase prefix 'b'
    import base64
    b32 = base64.b32encode(raw_cid).decode().lower().rstrip("=")
    return "b" + b32


def cid_of_str(text: str) -> str:
    return cid_of(text.encode())


def cid_of_jsonl(lines: list[str]) -> str:
    return cid_of("\n".join(lines).encode())
