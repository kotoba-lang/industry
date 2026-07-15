---
id: adr-2607061613-kotoba-lang-ipfs-svg-rename
title: "ADR-2607061613: ipfs → io-ipfs, svg → org-w3-svg"
status: accepted
doc_type: adr
topic: naming
authoritative: true
last_verified: 2026-07-06
authoritative_for:
  - kotoba-lang/ipfs を io-<domain> 命名(io-libp2p/io-ipld/io-multiformatsと
    同じ ipfs.tech 系列)へ、kotoba-lang/svg を org-w3-svg(W3C Recommendation)
    へ改称する判断。ADR-2607061603(W3C spec substrate batch)の follow-up
related:
  - orgs/kotoba-lang/io-ipfs
  - orgs/kotoba-lang/org-w3-svg
  - 90-docs/adr/2607061603-kotoba-lang-w3-spec-substrate-batch-rename.md
supersedes: []
superseded_by: []
---

# ADR-2607061613: ipfs / svg rename

## 背景

ADR-2607061603 の follow-up。`ipfs`/`svg` は README で実体を確認済み
(`gh api` だけでなく README も読んで、KAMI wgsl 置き場のような偽陽性が
ないことを確認):

- `ipfs`: `kotoba.lang.ipfs` — Kubo (IPFS) HTTP API pin/fetch/node-info
  ヘルパー。`io-libp2p`/`io-ipld`/`io-multiformats` と同じ ipfs.tech/
  Protocol Labs 系列。
- `svg`: "EDN-first Clojure/ClojureScript substrate for SVG documents" —
  W3C SVG の EDN 表現。`com-junkawasaki/svgraph`(別物、DrawingML/PPTX/
  ブラウザエディタ)とは明示的にスコープが分離されている。

依存確認(grep):
- `ipfs`: `kotoba-lang/checkpointer` の deps.edn のみ。
- `svg`: `kotoba-lang/d3` の deps.edn にコメントでの言及のみ
  (`:deps {}` — 実際のコード依存はゼロ、wire-compatibility の説明コメント
  だけなので更新不要)。

## 決定

- `kotoba-lang/ipfs` → `kotoba-lang/io-ipfs`
- `kotoba-lang/svg` → `kotoba-lang/org-w3-svg`

手順は ADR-2607061603/2607052300 と同一(gh repo rename → ローカル
checkout移動+remote retarget → 依存先座標更新 → repos.edn/west.yml更新)。
`checkpointer` の deps.edn を worktree + サーバサイドマージで更新
(`io.github.kotoba-lang/ipfs` → `io.github.kotoba-lang/io-ipfs`)。

## 検証

```
gh api repos/kotoba-lang/io-ipfs --jq '.full_name'   # kotoba-lang/io-ipfs
cd checkpointer && clojure -Spath                     # io-ipfs 座標解決OK
nbb scripts/gen-west-manifest.cljs --entry io-ipfs,org-w3-svg
```

## Consequences

- (+) ipfs.tech 系列(io-libp2p/io-ipld/io-multiformats/io-ipfs)と W3C
  Recommendation 系列(org-w3-*)がそれぞれ一貫した命名になった。
- 残る follow-up: `ed25519`/`dag-cbor`/`cacao`(依存グラフが大きい、
  互いに依存関係あり)は別ADRで扱う。
