# kura — operational state

## `shiropico-receipts.json`

**The only thing that makes the objects stored on the kura fleet verifiable.**
Each entry carries the source key, byte size, stripe geometry and — the part that
matters — the SHA-256 digest `kura.node.object/put-object!>` returned. A read
without that digest is a read without knowing: erasure decoding with a wrong
shard produces wrong bytes and no error, so the digest is the only check.

An object stored without a receipt is stranded: it exists, it costs storage, and
nothing can ever confirm what it is.

### Why it is here, and where it belongs instead

It lived in `/tmp` for the whole of the first real ingest, which was a mistake
worth naming: 92 objects and 127 MB of production assets were one reboot away
from being unverifiable. It is committed here so that stops being true.

**This is interim.** The right home is `kura.bugyo.plane` — the coordinator's
event-sourced metadata plane, which already replays node registrations and is the
thing a second operator would have to be able to read. A JSON file in a git repo
is a single-writer store that only one machine can see, which is exactly what the
metadata plane exists not to be.

### Regenerating and using it

```bash
# resume the ingest (skips keys already present)
nbb --classpath "src:script:../kura/src:../erasure/src:../merkle-sum/src:../sigv4/src" \
    script/ingest_shiropico.cljs

# verify + repair every stored object against its receipt
nbb --classpath "src:script:..." script/repair_shiropico.cljs
```

Both read and write `/tmp/shiropico-receipts.json`, so copy this file there first.
That path is hard-coded and should move to an argument — noted, not done.

### State at the close of the 2026-07-30 session

- 92 of 215 selected objects stored (`episode/` excluded — 82 objects, 10 GB)
- 127.4 MB logical, launch layout n=32, multiplier 2.0
- The first 61 were taken to full redundancy by two repair passes (505 shards
  restored, every digest matching). **Objects 62–92 have not been repaired yet**
  and were written degraded.
