# ADR-2607122400: GET /api/market-entry — iso3166 market-entry registry の API 化（P1 first slice）

**Status**: accepted
**Date**: 2026-07-12
**Deciders**: Jun Kawasaki
**Scope**: `orgs/gftdcojp/cloud-itonami`（edge API）, `scripts/gen-market-entry-registry.cljs`（superproject 生成器）, `manifest/west.yml`（cloud-itonami pin）

## Context

ADR-2607121000 P1 =「iso3166×223 を market-entry API 化」。ADR-2607122100 の
Track A 再導出でも market-entry-api（score 0.637）が本セッションの非衝突アイテム
だった（kernel 工場は fleet 進行中のため不介入 — ADR-2607122300）。

実態調査で判明した事実:
- 「223」は**か国数ではなく iso3166 衛星 repo 総数**（実体 = 188 か国 + 35 省庁
  agency repo。jpn-mof / usa-sec 等の国別下位機関が 35 本）。
- うち 7 repo（kgz/lao/mmr/tjk/tkm/uzb + ind-clean-air）は**ローカルに scaffold
  ディレクトリがあるだけで git repo ですらない**（`.git` 無し・GitHub 未存在 —
  どこかのセッションの中断 batch）。
- itonami.cloud の API 実体は shadow-cljs `:edge-api`（`.cljc` → ESM、
  `functions/api/*.js` は routing shim のみ）— repo 自体が cljs-first。

## Decision

1. **データ生成**: superproject `scripts/gen-market-entry-registry.cljs`（nbb）が
   `orgs/cloud-itonami/cloud-itonami-iso3166-*` の blueprint.edn + organization.edn
   を集約し、`src/cloud_itonami/market_entry.cljc`（GENERATED、静的・公開・
   portable data）を出力する。open-business と同じ no-KV / no-auth 方式。
   GitHub repo 実在リスト（`gh api orgs/cloud-itonami/repos`）を渡すと実在衛星
   のみ `:repo` リンクを載せる — **honest-default: 未 push scaffold への dead link
   を公開 API に出さない**（188 か国中 182 にリンク、6 か国はリンク無しで掲載）。
2. **API**: `cloud-itonami.edge.market-entry/on-request-get` +
   `functions/api/market-entry.js` shim + `:edge-api` export
   `marketEntryOnRequestGet`。
   - `GET /api/market-entry` → `{ok, count: 188, agencyCount: 35, countries: [summary…]}`
   - `GET /api/market-entry?country=JPN` → full entry（agencies 19 本、governor、
     requiredTechnologies、headRole = 役職名のみ・個人名なし）
   - 未知の country → **404 JSON**（alpha-3 hint 付き。500 にしない）
3. **検証**: node ESM smoke（list 200/188、JPN 19 agencies、ZZZ 404、KGZ は
   repo リンク無しで 200）→ main `3cfdb934` サーバ側マージ → Pages 自動デプロイ
   → ライブ確認。pin 前進 `25df3e05 → 3cfdb934`（API single-entry `24240ad3`）。
4. **スコープ外**: 6910 法人設立 actor / 8291 compliance actor との join
   （market-entry **gateway**化）、L2 protocol fee 連動、7 未 push scaffold の
   repo 化（中断 batch の持ち主セッションが再開する可能性があるため不介入）。

## Consequences

- (+) 223 衛星資産が初めて単一の公開 API surface になった（P1 の第一切片）。
  L1 自己登録済みの外部運用者が国別 compliance 起点情報に API でアクセスできる。
- (+) カバレッジが機械可読に正直（facts namespace の discipline を API に踏襲）。
- (−) 現状は registry の静的射影であって、per-国の手続き実行（6910×8291 join）
  ではない。gateway 化は次の切片。
- (−) 衛星データの更新は生成器の再実行 + rebuild が必要（自動同期なし。
  regenerate コマンドは生成ファイルの docstring に記載）。

## Artifacts

- `gftdcojp/cloud-itonami` main `3cfdb934`（branch 削除・worktree 撤去済み）
- `scripts/gen-market-entry-registry.cljs`（superproject、新規）
- superproject west.yml `24240ad3`（cloud-itonami pin 前進）
- https://itonami.cloud/api/market-entry （本番）
- 本 ADR とペアの `.edn`

## References

- ADR-2607121000（P1 の定義）/ ADR-2607122100(Track A) / ADR-2607122300（前ターン）
- ADR-2607106200/6300/6400（iso3166 衛星 batch 28-30 — 「223」の由来）
- `cloud-itonami` ADR-0013（自己登録）— 本 API の消費者となる外部テナント経路

## Addendum (2026-07-12, gateway join 第一切片 — main `c6846bab`)

本文 Decision 4 で「次の切片」とした **6910×8291 join を同日実装**した:

- `GET /api/market-entry?country=X` の詳細に **`incorporation`**
  （`cloud-itonami-isic-6910` `formation.facts` の per-法域カタログ:
  owner authority / legal basis / national spec / provenance / required
  docs。honest R0 = 29 法域。`USA-DE` の exemplar variant key と
  federalism note はそのまま保持）と **`complianceSources`**
  （`cloud-itonami-isic-8291` `dossier.facts` の closed source-basis
  カタログ: R0 = 5 法域。Companies House live client の `liveCapable`
  flag 含む）と **`actors`**（実行 actor repo へのリンク — 本 API は
  report、propose→commit は actor の governor 経由）を追加。一覧
  summary にも `incorporation`(bool) / `complianceSources`(count)。
- データは生成器が両 actor の facts カタログから bake（ファイル全体の
  edn 読みは後方 def の `#()` で落ちるため、文字列・コメント対応の
  括弧バランサで `(def catalog …)` フォームだけ抽出する方式に変更）。
- 検証: node ESM smoke（JPN 法務局+houjin-bangou / USA USA-DE+sec-edgar /
  AGO 両セクション不在 / 一覧 29/5）→ 本番同一確認。
- pin 前進 `3cfdb934 → c6846bab`（API single-entry `a4847be5`）。
- 依然スコープ外: POST 系の手続き実行（actor の StateGraph/governor を
  edge から起動する経路 — 状態と認可の設計が要るため別 ADR）。EU 圏
  ソース（:eu）の加盟国 attach（マッピング表を持たない = 正直な非対応）。
