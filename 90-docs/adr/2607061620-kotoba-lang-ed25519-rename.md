---
id: adr-2607061620-kotoba-lang-ed25519-rename
title: "ADR-2607061620: ed25519 → org-ietf-ed25519"
status: accepted
doc_type: adr
topic: naming
authoritative: true
last_verified: 2026-07-06
authoritative_for:
  - kotoba-lang/ed25519 を org-ietf-ed25519 へ改称する判断
    (RFC 8032 実装、org-ietf-turn/ical/imap/smtp と同じ precedent)
  - 8件の実依存先(pqh/witness-quorum/kagi/tayori/kekkai/org-signal/
    gftdcojp-net-kotobase/kotoba-lang-cacao)の座標を同一パスで更新する判断。
    etzhayyim/root は意図的にスキップ(理由は本文参照)
related:
  - orgs/kotoba-lang/org-ietf-ed25519
  - 90-docs/adr/2607061603-kotoba-lang-w3-spec-substrate-batch-rename.md
  - 90-docs/adr/2607061613-kotoba-lang-ipfs-svg-rename.md
supersedes: []
superseded_by: []
---

# ADR-2607061620: ed25519 rename

## 背景

`kotoba-lang/ed25519` README で実体確認: "Recover an Ed25519 public key
(and its did:key) from a raw 32-byte seed... per RFC 8032 §5.1.5" — RFC
8032(edwards25519)を実装する、依存ゼロ・babashka対応の本物のライブラリ。
`org-ietf-turn`/`org-ietf-ical`/`org-ietf-imap`/`org-ietf-smtp` と同じ
`org-ietf-*` precedent に揃える。

## 依存グラフ調査

`grep` で全 checkout の deps.edn/nbb.edn を検索し、実依存9件を特定:

| repo | ファイル | 備考 |
|---|---|---|
| `kotoba-lang/pqh` | deps.edn | dag-cbor にも依存(follow-up で再度触る) |
| `kotoba-lang/witness-quorum` | deps.edn | |
| `kotoba-lang/kagi` | deps.edn | `:sha`(`:git/sha`でなく)キーを使用、既存のまま維持 |
| `kotoba-lang/tayori` | deps.edn | |
| `kotoba-lang/kekkai` | deps.edn | |
| `kotoba-lang/org-signal`(旧signal) | deps.edn | 直前に他セッションが `com-junkawasaki/ed25519-clj` → `kotoba-lang/ed25519` の座標修正コミットを landing 済み |
| `gftdcojp/net-kotobase` | cli/deps.edn, cli/nbb.edn | cacao/dag-cborにも依存(それぞれ2箇所ずつ) |
| `kotoba-lang/cacao` | deps.edn, nbb.edn | ed25519 rename 後も21 tests/115 assertions green を確認 |

**`etzhayyim/root`(nbb.edn)は意図的にスキップ**: 座標が既に別種の stale
(`com-junkawasaki/ed25519-clj` というグループID、`:git/url` 自体は
`kotoba-lang/ed25519` を指しているため GitHub redirect で動作継続)であり、
かつ `.claude/worktrees/did-web-migration` という他セッションの
作業中 worktree が存在する。redirect により機能的には壊れないため、
このリポジトリへの侵襲を避けた。

## 決定

`kotoba-lang/ed25519` → `kotoba-lang/org-ietf-ed25519`。手順は
ADR-2607061603/2607061613 と同一(gh repo rename → ローカル移動+remote
retarget → 各依存先を worktree + サーバサイドマージで更新 → repos.edn/
west.yml)。

## 検証

```
gh api repos/kotoba-lang/org-ietf-ed25519 --jq '.full_name'
cd cacao && clojure -M:test   # 21 tests / 115 assertions green(ed25519 rename後)
nbb scripts/gen-west-manifest.cljs --entry org-ietf-ed25519
```

## Consequences

- (+) 8つの実依存先の座標が canonical に。
- (−) `etzhayyim/root` は座標未更新のまま(redirect で機能は保つが
  canonical ではない)。follow-up。
- 残る follow-up: `dag-cbor`(pqh/net-kotobase/cacaoでの座標更新が必要)、
  `cacao` 自身のrename(ed25519/dag-cbor rename後に実施)。
