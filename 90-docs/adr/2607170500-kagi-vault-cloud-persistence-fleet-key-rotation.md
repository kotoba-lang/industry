# ADR-2607170500: kagi vault の repo外home化 + kotobase.net クラウド永続化 + fleet 署名鍵ローテーション

**Status**: accepted (実装・ライブ検証済み)
**Date**: 2026-07-17
**Deciders**: Jun Kawasaki（「1, 2 を押して」= 鍵ローテーション + vault配置見直し / 「kagi, kagitaba は apple keychain cloud, 1password のように cloud で永続化, kotobase.net で永続できるようにしたい」）
**Related**:
- 90-docs/adr/2607160005-kotoba-fleet-agent-vcs-west-successor.md（fleet native CI / kagi 鍵運用の元）
- 90-docs/adr/2606272330-*.md（kagi = kotoba-native secrets vault）
- 90-docs/adr/2607022300-actor-deploy-3-substrate-topology.md（murakumo=compute の CI 実行会場）

## Context

fleet native CI（`fleet ci-verify`）の murakumo 常駐化を検討する中で、2026-07-16 に
kagi へ格納したはずの **fleet 署名鍵 5 本（fleet-owner-key/-owner-root/-gov1/-gov2/
-agent1）が消失**していた。調査の結果:

- kagi の vault は実行ディレクトリ直下の `./.kagi/`（gitignore）に置かれる設計だった。
- fleet セッションはそれを **共有 west checkout `orgs/kotoba-lang/kagi/.kagi/`**（または
  per-agent worktree）に作った。並行セッションが `kagi init` を別 checkout で走らせると
  **新しい identity（別 did/graph）で vault を作り直し、旧 vault の item を orphan 化**する。
  実際に現存する vault は aozora セッションの 4 item だけを持ち、fleet の 5 鍵は
  private material ごと復元不能だった（ディスク全域 / scratchpad / APFS snapshot /
  Keychain を探索して不在確認）。

一方オーナーは、kagi/kagitaba を **iCloud Keychain / 1Password のように cloud で永続化**
（デバイス紛失や checkout 破壊に耐える）し、その cloud を **kotobase.net** にしたいと要望した。

## Decision

### 1. vault を repo外の安定 home へ + init ガード（消失の根本原因を断つ）

- vault path 解決順を `$KAGI_HOME` → **`~/.kagi`（既定）** → 旧 `./.kagi`（読取り fallback、
  初回に home へ copy 自動移行）に変更。**repo checkout / worktree の掃除・再clone・
  並行セッションの clobber から独立**する。
- `kagi init` は既存 vault があれば拒否（re-init は新 identity を作り item を orphan 化する
  = まさに今回の消失モード）。

### 2. kotobase.net クラウド永続化（`kagi push` / `pull` / `sync`）

- vault snapshot は**ディスク上で既に E2E 暗号化済み**（`kagi.persist/->edn` は暗号文 +
  wrap 済み VMK + 台帳のみ、平文・素 VMK を出さない）。よって暗号文 blob をそのまま
  untrusted server に置いて安全 = **iCloud Keychain と同じ信頼モデル**（server は sync relay、
  trust root ではない。master passphrase / OS-Keychain VMK unlock はデバイスから出ない）。
- snapshot を actor 自身の tenant graph `kotobase/db/<did>/kagi-vault` に単一 datom として
  upsert（`:kagi.vault/seq` 単調増加）。depth-1 self-mint CACAO で認可（graph = 自 DID +
  db-name の hash なので owner 権限は構造的）。
- **ライブ往復検証済み**: push 56KB → ローカルを改竄 → pull が byte 一致で復元 → vault は
  なお unlock 可能。

### 3. fleet 署名鍵ローテーション

- fresh な ed25519 5 本を生成し、durable な `~/.kagi` へ格納 → cloud（kotobase.net）へ push
  = **ローカル home + リモート cloud の二重 durable**。
- `manifest/fleet-keys.edn` の trust anchor（`:keys` の ed25519:hex、`:roots`/`:canonical` の
  did:key）を新鍵に差し替え。旧 material は untrusted。
- **検証済み**: `kagi get fleet-owner-key` が PEM を返し、その pubkey `ed25519:36e96151…` が
  sign/verify し、fleet-keys.edn の `:keys` エントリと一致（= fleet は再び pin 署名可能）。

## kotobase.net CACAO wire — ライブで確定した要件

net-kotobase の edge（`clj-edge/edge_cacao.cljc`）+ 実運用実績のある
`cloud-murakumo/queue_kotoba.clj` の minter を照合し、**ライブ往復で確定**:

- **単一 resource `kotoba://can/kotobase:pin`**（`required-capability` の hardcoded 完全一致。
  op/graph でパラメタ化されない）。domain `kotobase.net`、aud **`did:web:kotobase.net`**、
  wire に `"h"` フィールドを持たない。
- CBOR は **canonical dag-cbor**（map key を length→bytewise でソート）。厳格
  `@ipld/dag-cbor` decode が非正準 map を弾くため。署名は SIWE 文字列上なのでキー順は
  署名に影響しない。
- iat/exp は **ISO-8601 秒精度**（edge の `parse-utc-seconds` は `…THH:MM:SSZ` 厳格、
  小数秒を弾く）。
- 書き込みは `db_name`（edge が tenant graph を導出）、読み取りは canonical **`:graph`** CID。
- サーバは query 行を **`:rows`** で返す（langchain.kotoba-db の `:q` は stale な `:rows_edn`
  を読むため常に空 = 使わず直接 XRPC）。tx は **`:db/add` ベクタ datom**、query は
  **MAP 形**（vector query は triple-pattern エンジンに落ち無言でゼロ行）。

これらは kagi.cacao の `mint-kotobase` / `canonical-graph` と kagi.sync に実装し、
44 tests green + ライブ往復で確認した。

## Consequences

- kagi/kagitaba secrets が checkout やデバイスの喪失に耐える（home + cloud 二重）。
  新デバイスは `kagi pull` で vault を復元できる（+ master passphrase / device unlock）。
- fleet の署名能力が復旧。以後の kagi 鍵運用は必ず `~/.kagi`（repo外）を使い、init は
  既存 vault を上書きしない。**この class の鍵消失は再発しない。**
- follow-up: (a) kagitaba item も同経路で cloud 化（現状 kagi vault 単位で push、item 粒度
  ではない）。(b) push は last-writer-wins（`:kagi.vault/seq`）。多デバイス同時編集の
  マージは非対応（1Password 同様、実用上は稀）。(c) fleet native CI の murakumo 常駐化
  （ADR-2607160005 の残タスク）は本 ADR で鍵が復旧したので再開可能。
