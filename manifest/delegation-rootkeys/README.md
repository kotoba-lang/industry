# delegation root keys

The published rotation logs for biscuit delegation roots
(root ADR-2608180200, `kotoba-lang/org-biscuitsec`'s `biscuit.rootkey`).

## What an edge pins, and what it fetches

An edge pins **one value**: the **genesis key digest**. Not the current key —
that is the whole point, because a current key baked into a config cannot
rotate, and a current key fetched from a mutable place can be swapped by
whoever controls that place.

Everything else is fetched and checked:

    tip.edn            -> the newest record's digest (unsigned; a hint)
    sha256-<digest>.edn -> one record, immutable, addressed by its content

`biscuit.rootkey/resolve-log` walks back from the tip to genesis and
`verify-log` checks forward: each record must be signed by the key its
predecessor **committed to**, must name its predecessor's digest, and must
not repeat a sequence number.

## Why these files may live in a git repo

Because the location is not the authority. A record's authority comes from
the chain, so publishing here, in an object store, or over `did:webvh` are
transport choices — they change availability, not trust. Git is the cheapest
one that is already public, versioned and content-addressed, so it is where
the first log lives.

## Logs

| subject | genesis key digest (pin this) | seq |
|---|---|---|
| `kotobase.net/delegation/root` | `sha256:7718c4bc4047c1e158ce5dbd12540244be476ec03a7e6404755a94d8a728795b` | 2 |

**The pin above did not change when the key rotated.** That is the property
this whole shape exists for, and it has now been exercised rather than
argued: `seq 2` is signed by the key `seq 1` committed to, and a reader
holding only the genesis digest follows it. During the overlap both keys are
current, because tokens minted under the first are still live.

Private keys are in `kagi` (compartment `personal`):
`biscuit-root-kotobase-delegation-genesis`, `-next-1` (now the second
current key) and the pre-rotation `-next-2`, with 0600 copies under `~/.itonami/`
for non-interactive use. **No secret is in this directory.**

## Rotating

Sign the next record with the key the current one committed to, commit to a
NEW next key's digest, name the current record's digest as `prev`, increment
`seq`, write the object, then move `tip.edn`. A reader that trusts record *n*
verifies *n+1* without trusting this repository.
