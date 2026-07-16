---
id: adr-2607072330-kotoba-lang-denrei-posting-actor
title: "ADR-2607072330: kotoba-lang/denrei — 投稿 actor（post-LLM ⊣ MembershipGovernor）"
status: accepted
doc_type: adr
topic: kotoba-lang-denrei-posting-actor
authoritative: true
last_verified: 2026-07-07
authoritative_for:
  - kaisha communication space への AI 投稿経路が kotoba-lang/denrei（governed actor）のみであること
  - denrei の設計（post-LLM ⊣ MembershipGovernor、二流路 StateGraph、Phase 0→3、post は常に人間承認）
  - MembershipGovernor の HARD 不変条件（no-actuation / target-mismatch / not-a-member / model-invalid / consent-blocked mention / tenant-isolation）
  - mention 規律（governor と delivery record の両側が kaisha.model/mentions を共用）
related:
  - 90-docs/adr/2607072310-kotoba-lang-kaisha-communication-space.md
  - 90-docs/adr/2607062010-kotoba-lang-koyomi-schedule-actor.md
  - 90-docs/adr/2607061500-kotoba-lang-tayori-correspondence-actor.md
  - manifest/repos.edn
supersedes: []
superseded_by: []
---

# ADR-2607072330: kotoba-lang/denrei — 投稿 actor（post-LLM ⊣ MembershipGovernor）

**Status**: accepted
**Date**: 2026-07-07
**Deciders**: Jun Kawasaki

## Context

ADR-2607072310（kaisha）は「AI のチャンネル投稿は必ず governed actor（post-LLM
⊣ Governor、send は人間承認）経由とし、model へ直書きさせない」を follow-up と
して明記した。kaisha.model は pure な space/channel/message EDN で、投稿・
consent・governor の概念を持たない（実測確認済み）。チャンネル投稿は
membership 境界（private channel）と mention による対人到達を構造的に持つため、
tayori / koyomi と同種の独立 governor を持つ actor が必要。

## Decision

新規 repo `kotoba-lang/denrei`（伝令）を起こし、post-LLM ⊣ MembershipGovernor
型の投稿 actor として実装する（koyomi と同型の二流路 StateGraph）。

- **ChannelTarget port**（fetch-message / propose-revision! / post!。content は
  {:tenant :space :channel :message <kaisha.model message EDN>}、delivery
  record 生成は denrei 側が所有）。post! は人間承認後の commit 步でのみ呼ばれる。
- **二流路 StateGraph**: ingest（:space/register — kaisha space を ground fact
  として機械的に記録、LLM 無し）、assess（:message/draft → govern → decide →
  commit|escalate|hold、:message/post は常に人間承認）。TOCTOU-safe: 承認時に
  checkpoint 済み content をそのまま post!（koyomi の同修正を移植）。
- **MembershipGovernor の HARD 不変条件**: missing-activity / no-actuation
  （effect は :draft のみ）/ target-mismatch（request の space/channel からの
  すり替え禁止）/ missing-channel / **not-a-member（actor 自身の member
  identity "denrei" が channel member でなければ構造的に投稿不能 — private
  #ops はレビューでなく型で防ぐ）** / **model-invalid（kaisha.validate の
  unknown-author / orphan-reply / nested-thread が governor の床）** /
  consent-blocked mention（post 時。mention 解析は kaisha.model/mentions を
  governor と delivery record で共用 — 密輸不能）/ tenant-isolation。
- **SOFT**: confidence floor / duplicate-body（同一 body の既存 message →
  escalate）/ first-contact mention は draft 時でも high-stakes。
- Store（MemStore ≡ DatomicStore、langchain.db :db-api、contract test）、
  CACAO 自己発行（.denrei/identity.edn、gitignore）、append-only 台帳、
  Phase 0→3（post は phase 非依存で常に人間）。

## Consequences

kaisha への AI 投稿が governed actor の型に集約され、private channel への
投稿不能・blocked member への mention 不能・投稿は常に人間承認が構造的に
強制される。実行状況（2026-07-07）: repo scaffold 完了（src 9 ns + test 5 ns、
31 tests / 129 assertions 全緑、clj-kondo errors/warnings 0、sim デモ完動）、
GitHub 作成（public）+ push（`99a85ba`）、west 登録、本 ADR。

Addendum（2026-07-07 同日）: cloud-itonami 配線も完了 — `cloud_itonami.workspace`
の投影層に teian/koyomi と同型で `:chat/draft-message`（→ denrei
:message/draft、:read-only）/ `:chat/post-message`（→ denrei :message/post、
:external-send、denrei 自身の :request-approval interrupt を real resume、
HARD hold は override しない）を追加（cloud-itonami `1f27ceb`、350 tests /
2719 assertions 全緑）。

Addendum 2（2026-07-07 同日）: live ChannelTarget も完了 — `denrei.pod`
（`db-channelport` = langchain.db :db-api 契約のみで喋る ChannelTarget、
`fleet-channelport` = murakumo fleet node の実測方言 pre-wire、
`messages-since`/`follow!` = kotobase / kotoba-peer と共通の datom 面
cursor tail）。murakumo fleet 実ノード（asher）で post!/fetch-message/
channel-messages の e2e 実測成功（denrei `69a3f5d`、38 tests / 149
assertions 全緑）。deploy 方針と fleet 方言の詳細・KSE 不採用の理由は
ADR-2607072400（+ addendum 1/2）が正本。

## References

`orgs/kotoba-lang/denrei`、`orgs/kotoba-lang/kaisha`、`orgs/kotoba-lang/koyomi`
（scaffold 手本）、ADR-2607072310(kaisha)、ADR-2607062010(koyomi)、
ADR-2607061500(tayori)。
