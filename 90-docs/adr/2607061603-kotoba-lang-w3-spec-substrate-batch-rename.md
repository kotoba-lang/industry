---
id: adr-2607061603-kotoba-lang-w3-spec-substrate-batch-rename
title: "ADR-2607061603: did / vc / activitypub / activitystreams / rdf / turtle を org-w3-* へ改称(W3C spec substrate batch rename)"
status: accepted
doc_type: adr
topic: naming
authoritative: true
last_verified: 2026-07-06
authoritative_for:
  - kotoba-lang の EDN-first W3C spec substrate 群(did/vc/activitypub/
    activitystreams/rdf/turtle)を org-<body>-<spec> 命名規約(org-ietf-turn/
    org-ietf-ical/org-w3-webauthn/org-w3-aria 等の前例)に揃える判断
  - 依存が0〜1件だった軽量な6件をこのバッチで実施し、依存グラフの大きい
    cacao/ed25519/dag-cbor/ipfs/svg は別途(依存先の同時更新が必要かつ
    一部が並行セッションの作業対象のため)判断する方針
related:
  - orgs/kotoba-lang/org-w3-did
  - orgs/kotoba-lang/org-w3-vc
  - orgs/kotoba-lang/org-w3-activitypub
  - orgs/kotoba-lang/org-w3-activitystreams
  - orgs/kotoba-lang/org-w3-rdf
  - orgs/kotoba-lang/org-w3-turtle
  - 90-docs/adr/2607052300-kotoba-lang-turn-rt-net-signal-reverse-domain-rename.md  # rename手順の正本
  - 90-docs/adr/2607061800-ipns-head-signed-records-registry-resolved.md # 直近の同型rename前例(ipns→tech-ipfs-specs-ipns)
supersedes: []
superseded_by: []
---

# ADR-2607061603: W3C spec substrate batch rename

- Status: accepted(2026-07-06)

## 背景

オーナーからの依頼: 今回 `org-ietf-imap`/`org-ietf-smtp` を作った流れで、
kotoba-lang 内に同様のパターン(標準規格を実装しているのに `org-<body>-
<spec>` 命名規約に揃っていない bare 名の repo)が他にもないか調査し、
該当するものは改称・分離してほしいとのオーナー指示。

調査の結果(`gh api repos/kotoba-lang/<name>` で実体を確認、名前だけで
判断しない):

- `did`/`vc`/`activitypub`/`activitystreams`/`rdf`/`turtle` は
  いずれも "EDN-first X substrate for kotoba" という実際の説明文を持ち、
  W3C の正式な仕様(DID Core / Verifiable Credentials / ActivityPub /
  ActivityStreams 2.0 / RDF 1.1 / RDF 1.1 Turtle)を指している、実在する
  軽量 scaffold(bare名のまま)。
- 依存グラフを事前に調査(`grep` で全 checkout の deps.edn/nbb.edn を検索):
  `did` のみ `kotoba-lang/kotoba` の deps.edn(2箇所: git座標 + `:local/root`)
  から実際に参照されていた。他5件は依存ゼロ。
- 一方で `atom`/`webgl`/`mathml`/`geojson`/`step`/`spirv`/`otio` は
  名前が規格と一致するが、実体は無関係な KAMI clj-wgsl 移行(ADR-2607010930)
  の Phase 4 置き場 scaffold だった(**改称すると実態と乖離した誤情報になる
  ため対象外**)。
- `ipfs`/`svg`/`cacao`/`ed25519`/`dag-cbor` は実体はあるが依存グラフが
  大きい(cacao 6+、ed25519 8+、dag-cbor 5+ の依存 repo。一部は
  `etzhayyim/root` の別セッション worktree 等、並行作業の形跡あり)ため、
  本バッチには含めず別途判断する。

## 決定

`did`/`vc`/`activitypub`/`activitystreams`/`rdf`/`turtle` を
`org-w3-<spec>` へ改称する(依存が0〜1件で低リスクなため即時実施)。

実行手順(ADR-2607052300 / 直近の ipns rename と同一手順):

1. `gh repo rename <old> org-w3-<old> --repo kotoba-lang/<old>`
   (GitHub が旧名からの redirect を保持)
2. ローカル checkout の移動 + remote retarget
   (`orgs/kotoba-lang/<old>` → `orgs/kotoba-lang/org-w3-<old>`)
3. 依存先の座標更新: `kotoba-lang/kotoba` の deps.edn を
   `io.github.kotoba-lang/did` → `io.github.kotoba-lang/org-w3-did`
   へ(pin は同一 SHA、座標のみ)。worktree + サーバサイドマージで実施
   (`kotoba` の shared checkout は detached HEAD の west 管理 pin だった
   ため直接編集しない)。
4. `manifest/repos.edn`: `:path-overrides` に6エントリ追加、
   `:extra-projects` の該当 path を新名へ書き換え。
5. `nbb scripts/gen-west-manifest.cljs --entry org-w3-did,org-w3-vc,
   org-w3-activitypub,org-w3-activitystreams,org-w3-rdf,org-w3-turtle`
   で新規6エントリを追加(pin検証 OK)。`--entry` は旧エントリを自動削除
   しない(ipns rename と同じ既知の挙動)ため、旧 bare 名の6エントリを
   手動で west.yml から削除。

## 却下案

- **`ipfs`/`svg`/`cacao`/`ed25519`/`dag-cbor` も同時に改称する**: 依存
  グラフが大きく(cacao/ed25519/dag-cbor は合計20超の依存箇所に跨る)、
  一部は他セッションが同時に触っている形跡があるため、本バッチのスコープ
  外とし、個別に安全性を見極めてから着手する。
- **`atom`/`webgl`/`mathml`/`geojson`/`step`/`spirv`/`otio` を名前だけで
  改称する**: 実体が無関係な KAMI wgsl 移行 scaffold であることが
  `gh api` での実体確認で判明したため、改称すると誤情報になる。対象外。

## 検証

```
gh api repos/kotoba-lang/org-w3-did --jq '.full_name'         # kotoba-lang/org-w3-did
cd kotoba && clojure -Spath   # io.github.kotoba-lang/org-w3-did の座標解決を確認
nbb scripts/gen-west-manifest.cljs --entry org-w3-did,org-w3-vc,org-w3-activitypub,org-w3-activitystreams,org-w3-rdf,org-w3-turtle
```

## Consequences

- (+) 6リポジトリが `org-<body>-<spec>` 命名規約に揃い、将来この規約を
  前提にした tooling/検索がしやすくなる。
- (+) 唯一の実依存(`kotoba`)の座標も更新済みで、canonical な状態を維持。
- (−) `ipfs`/`svg`/`cacao`/`ed25519`/`dag-cbor` は依然 bare 名のまま
  (follow-up)。
