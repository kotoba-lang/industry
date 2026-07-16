---
id: adr-2607084500-nameserver-org-ietf-dns-rename
title: "ADR-2607084500: kotoba-lang/nameserver を org-ietf-dns へ rename — RFC 1035 (DNS) は IETF RFC、既存 org-ietf-turn/org-ietf-ical 等と同じ reverse-domain 命名precedent"
status: accepted
doc_type: adr
topic: kotoba-lang-dns-nameserver-domain-hosting
authoritative: true
last_verified: 2026-07-08
authoritative_for:
  - nameserver → org-ietf-dns の rename 決定と根拠
  - この repo が既存の org-ietf-* batch (turn/ical/cbor/opus/ed25519 等) と
    同じ扱いを受ける理由
related:
  - 90-docs/adr/2607083100-kotoba-lang-nameserver-domain-hosting.md
  - orgs/kotoba-lang/org-ietf-dns
supersedes: []
superseded_by: []
---

# ADR-2607084500: nameserver → org-ietf-dns rename

- Status: accepted (2026-07-08)
- Deciders: Jun Kawasaki

## Decision

`kotoba-lang/nameserver`（ADR-2607083100 で新設した RFC 1035 権威DNS
ネームサーバー + IPNS alt-root ブリッジ）を `kotoba-lang/org-ietf-dns` へ
GitHub rename する。

DNS のワイヤメッセージ形式（`nameserver.wire`）が実装しているのは
**RFC 1035**（"DOMAIN NAMES - IMPLEMENTATION AND SPECIFICATION"）そのもの
— IETF が発行する RFC であり、既存の org-ietf-* batch と同じ判定基準
（実際にその規格のワイヤ形式/データ形式を読み書きしている）を満たす:

- `org-ietf-turn`（RFC 8656）・`org-ietf-ical`（RFC 5545）・
  `org-ietf-cbor`（RFC 8949）・`org-ietf-opus`（RFC 6716）・
  `org-ietf-ed25519`（RFC 8032）と同型 — IANA ではなく IETF を reverse-domain
  の起点にするのは、RFC の発行元が IETF であり、IANA は DNS のパラメータ
  registry / root zone の**運用**を担うだけで規格の発行元ではないため。

## 実施内容

- GitHub repo rename: `kotoba-lang/nameserver` → `kotoba-lang/org-ietf-dns`
  （GitHub は旧名への参照を自動リダイレクトする — `io.github.kotoba-lang/nameserver`
  という Clojure deps.edn 座標も動作し続けるが、新規に書く座標は
  `io.github.kotoba-lang/org-ietf-dns` を使う）。
- ローカル checkout も `orgs/kotoba-lang/nameserver` → `orgs/kotoba-lang/org-ietf-dns`
  へ改名し、git remote を更新（`orgs/kotoba-lang/turn` 等、過去の rename では
  ローカル checkout が旧パスのまま放置されている例もあるが、今回は checkout
  作成直後だったため揃えた）。
- リポジトリ内部の Clojure namespace（`nameserver.wire`/`nameserver.store`/
  `nameserver.resolver`/`nameserver.custom-tld`/`nameserver.delegate`/
  `nameserver.server`）は**変更しない** — `kotoba-lang/ipns` が
  `tech-ipfs-specs-ipns` へ rename された後も内部 namespace `ipns.core` を
  変更していない前例と同じ（repo の GitHub 上の識別子と Clojure namespace
  は別軸）。
- README の CI バッジ URL を新パスへ更新。内部 ADR
  (`docs/adr/0001-architecture.md`) の superproject 記録リンクも
  ADR-2607083100(rename 前に付番衝突回避で 2607083000→2607083100 に
  改名済み)を指すよう修正。
- `manifest/repos.edn`: `:path-overrides` に
  `"orgs/kotoba-lang/nameserver" "orgs/kotoba-lang/org-ietf-dns"` を追加、
  `:extra-projects` 内の該当 entry も新パスへ更新。
- `manifest/west.yml`: `--entry org-ietf-dns` で新 entry を追加。旧
  `name: nameserver` entry は `--entry` の splice(名前一致置換)では
  自動削除されない(rename元→rename先の対応関係を splice は追跡しない)ため、
  手動で該当5行を削除 — 結果は `git diff` で新規追加+旧entry削除のみの
  最小diffであることを確認済み（wholesale 再生成はしていない）。

## Verification

改名後の checkout で `clojure -M:dev:test` 再実行、27 tests / 54
assertions 変わらず green。`manifest/repos.edn` の EDN 構文を
`clojure.edn/read-string` で確認。`manifest/west.yml` の diff が
`org-ietf-dns` 新規ブロック追加 + `nameserver` 旧ブロック削除のみで
他 entry に触れていないことを `git diff` で確認。
