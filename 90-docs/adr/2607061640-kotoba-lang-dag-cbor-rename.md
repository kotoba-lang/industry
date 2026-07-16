---
id: adr-2607061640-kotoba-lang-dag-cbor-rename
title: "ADR-2607061640: dag-cbor → org-ietf-cbor"
status: accepted
doc_type: adr
topic: naming
authoritative: true
last_verified: 2026-07-06
authoritative_for:
  - kotoba-lang/dag-cbor を org-ietf-cbor へ改称する判断
    (RFC 8949 実装、org-ietf-turn/ical/imap/smtp/ed25519 と同じ precedent)
related:
  - orgs/kotoba-lang/org-ietf-cbor
  - 90-docs/adr/2607061620-kotoba-lang-ed25519-rename.md
supersedes: []
superseded_by: []
---

# ADR-2607061640: dag-cbor rename

## 背景

README で実体確認: "Definite-length CBOR (RFC 8949) encode/decode — with
both a canonical (dag-cbor key-sorted) encoder and an order-preserving
one for signing payloads" — 名前は "dag-cbor" だが、実際のスコープは
**RFC 8949(CBOR)の一般的な実装**で、IPLD の dag-cbor 正規化はその一
モードに過ぎない(CACAO の署名ペイロードには canonical でない
order-preserving モードを使う、という記述がその根拠)。よって
`io-ipld-dag-cbor` ではなく `org-ietf-cbor`(RFC 8949 = IETF文書)を選択。
namespace は元から `cbor.core` であり、リポジトリ名を変えても
`cbor.core` の呼び出し側コードは無変更。

## 依存グラフ調査

`ed25519` と同時に依存するリポジトリが多い(cacao 経由の推移含む)。実
依存4件:

| repo | ファイル |
|---|---|
| `kotoba-lang/io-ipld`(旧ipld) | deps.edn |
| `kotoba-lang/pqh` | deps.edn(ed25519 rename時に続けて更新) |
| `gftdcojp/net-kotobase` | cli/deps.edn, cli/nbb.edn |
| `kotoba-lang/cacao` | deps.edn, nbb.edn(rename後 21 tests/115 assertions green) |

`etzhayyim/root` は ed25519 と同じ理由でスキップ。

## 決定

`kotoba-lang/dag-cbor` → `kotoba-lang/org-ietf-cbor`。手順は
ADR-2607061620 と同一。

## 検証

```
gh api repos/kotoba-lang/org-ietf-cbor --jq '.full_name'
cd io-ipld && clojure -M:test   # 7 tests / 21 assertions green
cd cacao   && clojure -M:test   # 21 tests / 115 assertions green
nbb scripts/gen-west-manifest.cljs --entry org-ietf-cbor
```

## Consequences

- (+) 4つの実依存先の座標が canonical に。namespace(`cbor.core`)は無変更
  なのでコード側の変更ゼロ。
- 残る follow-up: `cacao` 自身の rename(ed25519/dag-cbor 両方の rename が
  完了したので次に着手可能)。
