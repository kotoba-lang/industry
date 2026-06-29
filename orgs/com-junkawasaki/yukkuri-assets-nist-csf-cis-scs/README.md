# yukkuri-assets · NIST CSF v2 / CIS Controls / SCS制度

ゆっくり実況動画 1本分の音声アセット。RW write path が B2 SlowDown で塞がっていた
ため、RisingWave を経由せず以下のルートで生成した。

```
hand-authored script JSON
  → VOICEVOX engine (in-cluster, via kubectl port-forward)
    → 35 wav files
      → ipfs add (CIDv1, local daemon auto-pin)
        → manifest.json (line_id ↔ CID ↔ style_id ↔ text)
          → datalad save (git + git-annex)
```

これは etzhayyim/root substrate (RW-free, AT MST + IPFS + Base L2) の
canonical persistence path と一致する (CLAUDE.md §Operating Entity Boundary)。

## Source

| Item | Origin |
|---|---|
| Script JSON | `60-apps/ai-gftd-project-yukkuri/examples/scripts/nist-csf-cis-scs.json` (`src/`) |
| Topic + outline | `60-apps/ai-gftd-project-yukkuri/examples/scripts/nist-csf-cis-scs.topic.md` (`src/`) |
| Synthesizer | VOICEVOX engine 0.25.2 (in-cluster `voicevox-engine.mitama-udf.svc:50021`) |
| Speakers | 四国めたん (LEFT, base style_id=2) + ずんだもん (RIGHT, base style_id=3) |
| Speed / Pitch | speed=0.95 (ゆっくり間延び) / pitch=0.0 |
| Generated | 2026-05-28 |

## Counts

- scenes: 6
- voice lines: 35 (35/35 ok)
- voice synth time: 342.0 s
- voice wav bytes: 13,204.5 KB (~12.9 MB)
- backgrounds: 6 (1024×576 placeholders)
- character refs: 2 (512×768 placeholders, L=ゆきり / R=まりり)
- BGM: 1 legacy placeholder (260s, 44.1kHz 16-bit mono, ~22 MB)
- BGM library: 30 MP3 tracks (20 DOVA-SYNDROME + 10 Incompetech) in `bgm/dova/` and `bgm/incompetech/`

## IPFS CIDs (CIDv1 base32)

| Asset | CID |
|---|---|
| `wavs/` (35 voice wavs) | `bafybeict2e6mtv645yjza2yqjnaefsdddkfm3qxmlqc6mh7wzqji734mpi` |
| `images/` (6 BG + 2 char) | `bafybeidjogfk2gzsp4ntgv3piik33zuusmra3zdixguozzntlkwq4j5pvi` |
| `bgm/nist-csf-cis-scs.wav` | `bafybeieevm7rsgsk2nhyit3ydqlfvgwmzjxidgjcvwpnwgmjsdjrbf4nea` |
| `src/nist-csf-cis-scs.json` (script source) | `bafkreicayjz5o34tgfpgxhoautt7oj3abvz2j52to6nke6w4hadux3fshm` |
| `manifest.json` (post-image+bgm merge) | (see `manifest.json` itself) |

per-asset CIDs:
- voice lines: `manifest.json[lines][].ipfs_cid` (35件)
- images: `manifest.json[images][].ipfs_cid` (8件)
- BGM: `manifest.json[bgm].ipfs_cid`

## Asset provenance

| Kind | Status | Generator |
|---|---|---|
| Script JSON | hand-authored | reference example (CLAUDE.md §yukkuri scriptwriter format) |
| Voice (35 lines) | real | VOICEVOX engine 0.25.2 (in-cluster via kubectl port-forward, 四国めたん + ずんだもん, speed=0.95) |
| Backgrounds (6) | **placeholder** | PIL gradient + Hiragino label (disk-full prevented diffusers SD-turbo DL, 2.4GB free) |
| Characters L/R (2) | **placeholder** | PIL silhouette + Hiragino label (same disk constraint) |
| Legacy BGM (1) | **placeholder / retired** | NumPy ambient pad + C-minor pentatonic arpeggio (ongakuka XRPC was unreachable, RW-blocked) |
| BGM library (30) | licensed external material | DOVA-SYNDROME + Incompetech tracks, render-only use via `ongakuka.gftd.ai` catalog |

`image_kind="placeholder"` / `generator` フィールドで識別可能。real 出力に差し替える際は同じ
key (`scene-00-sunset-shrine.png` 等) で上書き → `ipfs add` → manifest の該当 CID 更新 →
`datalad save` で provenance を維持したまま swap できる。

## BGM library (2026-06-29)

`ongakuka.gftd.ai` is now the cljc BGM selector/composer. The imported BGM
tracks are internal production materials only. Current pack:

- Japan/common yukkuri style: 20 DOVA-SYNDROME tracks under `bgm/dova/`
- US/global/region-tagged style: 10 Incompetech/Kevin MacLeod tracks under `bgm/incompetech/`

The exact track list, license IDs, attribution text, moods, regions, SHA-256,
and local paths are maintained in `orgs/gftdcojp/ongakuka/resources/catalog.edn`.

Policy: use only as background music muxed into produced videos; do not expose
raw files through public app static paths/CDNs; do not use for AI training; do
not register in Content ID or equivalent fingerprinting systems. Runtime
selection and credit strings live in
`orgs/gftdcojp/ongakuka/resources/catalog.edn`.

Annex/B2 operation notes are recorded in:
`orgs/gftdcojp/ongakuka/docs/b2-annex-runbook.md`.

Local daemon (`12D3KooWBhvfp6ZEhSiNDTQ3RmhkWdNsPZWmNmPdPDkqpkrN4kYU`) に
auto-pin 済み。他ノードからは:

```
ipfs cat bafybeict2e6mtv645yjza2yqjnaefsdddkfm3qxmlqc6mh7wzqji734mpi/scene-00-line-00.wav | aplay
ipfs get bafybeict2e6mtv645yjza2yqjnaefsdddkfm3qxmlqc6mh7wzqji734mpi -o ./wavs
```

## DataLad

```bash
# Clone (text + manifest, wav は annex lazy)
datalad clone <this dir or sibling> yukkuri-nist
cd yukkuri-nist

# Fetch all wav binaries
datalad get .

# Or fetch just one scene
datalad get wavs/scene-00-line-00.wav
```

git-annex backend: SHA256E (default)。`manifest.json` の `wav_sha256` と一致。

## License / Credit

VOICEVOX 公式キャラの商用利用はクレジット表記必須。動画 publish 時の description
には以下を付与する (yukkuri `upload_youtube` graph と整合):

```
VOICEVOX:四国めたん, VOICEVOX:ずんだもん
```

## Regenerate

```bash
# 1. port-forward VOICEVOX (in-cluster, mitama-udf)
KUBECONFIG=~/.kube/config-sjc kubectl port-forward -n mitama-udf svc/voicevox-engine 50021:50021 &

# 2. synthesize + ipfs add
python3 scripts/synthesize.py \
  --script src/nist-csf-cis-scs.json \
  --manifest manifest.json \
  --wavs-dir wavs \
  --speed 0.95 \
  --video-id nist-csf-cis-scs

# 3. commit
datalad save -m "regenerate"
```

## Downstream (TODO)

- Mac render pool が立ち上がったら `wavs/` + 立ち絵 + BG + telop を `kami-engine`
  に投入して mp4 mux
- RW write path 復旧後、`manifest.json` の `(line_id, ipfs_cid, wav_sha256, style_id)`
  を `vertex_yukkuri_line` に backfill (新規 INSERT ではなく UPDATE)、
  `vertex_yukkuri_asset` に kind=`voice` で blob 登録
- 動画完成後は `wavs/` dir CID を AT Repo (`ai.gftd.apps.yukkuri.asset`) record の
  `blob_key` (CID 形式) として参照可能。yukkuri.gftd.ai 側の B2 blob と
  decentralized IPFS pin の dual-locator にする
