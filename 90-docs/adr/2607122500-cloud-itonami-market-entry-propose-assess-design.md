# ADR-2607122500: market-entry 手続き実行経路の設計 — propose-assess を既存 effects lifecycle に載せる（design-only）

**Status**: accepted（設計のみ。実装は procurement lane の静穏化にゲート — 下記 6）
**Date**: 2026-07-12
**Deciders**: Jun Kawasaki
**Scope**: `orgs/gftdcojp/cloud-itonami`（設計対象。本 ADR ではコード変更なし）

## Context

ADR-2607122400 + addendum で `GET /api/market-entry` は「report する gateway」に
なった（6910 incorporation 29 法域 / 8291 compliance 5 法域 / actors リンク）。
次の切片 =「手続きの実行」への入口。edge から actor をどう起動するかの設計が必要。

前例監査（2026-07-12）: cloud-itonami には既に**手続き実行の確立された lifecycle が
ある** — `POST …/procurement/propose-match|propose-share` が effect を tenant の
approval queue（KV 上の conn、`workspace-store`）に enqueue し、
`POST /api/{org}/{repo}/effects/{id}/approve|reject` で人間が裁く。approve は
`cloud-itonami.approval/merge!` を即実行（handlers = effect-kind dispatch table）。
認可は `tenant-auth/guarded-post`（admin bar、CACAO）。全て portable `.cljc` を
edge bundle にコンパイル（ADR-0016 cljs-first）。**この lifecycle を再発明しては
ならない。** 同時に、この系のファイル（approval / workspace handlers /
effects-endpoints）は product-party procurement lane（内部 ADR-0020、
pp-next5/6 worktree）が現役で編集中 — 今実装すると衝突する。

## Decision（設計）

1. **新パターンを作らない。** market-entry の手続きは既存 propose→approve→merge
   lifecycle の 1 effect-kind として実装する:
   `POST /api/{org}/{repo}/market-entry/propose-assess` `{country: "JPN"}`。
   グローバル `/api/market-entry` への POST は作らない（状態は tenant の conn に
   属する。手続きは必ず tenant 文脈で行う）。
2. **認可**: `tenant-auth/guarded-post`（hr/billing/effects/procurement と同じ
   admin bar）。細粒度 role は hr-endpoints の同種 note と同じ follow-up 扱い。
3. **Effect**: `:market-entry/assess`、`:itonami.effect/risk :read-only`。
   payload には propose 時点で `cloud-itonami.market-entry`（生成データ）から
   当該国の incorporation spec-basis を埋め込む（merge 時に外部 fetch しない）。
   merge handler は assessment 文書（spec-basis 引用 + required-docs チェック
   リスト + compliance sources）を conn に append するだけ — 外部送信なし。
4. **Governor 規律**: 6910 RegistrarGovernor の第一 HARD check（spec-basis 捏造
   禁止）を propose 時に edge で先行適用する — catalog 非対象国（R0: 188 か国中
   159）への propose-assess は effect を作らず **422 で正直に拒否**
   （"no spec-basis: 6910 R0 covers 29 jurisdictions" + coverage リスト参照）。
   HARD violation は人間の approve でも越えられない、という fleet 不変条件を
   API 面でも保存する。
5. **実 filing は永久にこの経路のスコープ外。** 実際の法人設立申請
   （`:external-send` / actuation 相当）は 6910 actor 本体の operation
   （StateGraph + RegistrarGovernor + interrupt-before human sign-off）の仕事
   であり、edge からは行わない。edge の役割は assess の enqueue と人間の裁可
   までに固定する（LLM→actuator 直結禁止の物理版規律 ADR-2607011000 と同型）。
6. **実装タイミング**: procurement lane（pp-next5/6、内部 ADR-0020）が main に
   着地して静穏化してから、この設計で実装 PR を起こす。理由: 同一ファイル群
   （approval.cljc / workspace handlers / effects-endpoints 隣接）への並行編集は
   textual conflict がほぼ確実。設計を先に固定するのが本 ADR の役割。

## Consequences

- (+) 手続き実行が既存の人間裁可 queue・監査経路・認可バーに自動的に乗る
  （新しい攻撃面・新しい状態層を作らない）。
- (+) spec-basis 捏造の HARD 拒否が propose 時点で API 契約になる。
- (−) 実装は未着手（意図的）。gateway は当面 report-only のまま。
- (−) coverage 拡大（29→more 法域）は 6910 formation.facts への追記が正
  （citable source 必須、作り話でカバレッジを膨らませない）— 生成器再実行で
  API に自動反映される。

## References

- ADR-2607122400 + addendum（gateway join — 本設計の前提）
- cloud-itonami 内部: effects-endpoints / procurement-endpoints /
  tenant-auth の各 ns docstring、ADR-0013（自己登録）、ADR-0016（cljs-first）、
  ADR-0020（procurement lifecycle — 衝突回避の対象）
- ADR-2607031500（m6910）/ ADR-2607110400（8291）/ ADR-2607011000（governor 不変条件）
- 本 ADR とペアの `.edn`
