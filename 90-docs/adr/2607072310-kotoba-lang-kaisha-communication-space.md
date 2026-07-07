---
id: adr-2607072310-kotoba-lang-kaisha-communication-space
title: "ADR-2607072310: kotoba-lang/kaisha — communication space (Slack/Teams 相当) の EDN workspace surface"
status: accepted
doc_type: adr
topic: kotoba-lang-kaisha-communication-space
authoritative: true
last_verified: 2026-07-07
authoritative_for:
  - communication space (Slack/Teams 相当) の surface repo が kotoba-lang/kaisha であること
  - kaisha の v1 スコープ（pure EDN model のみ。transport/persistence/AI 参加は follow-up）
  - AI がチャンネルへ投稿する経路は governed actor（post-LLM ⊣ Governor、人間承認）経由に限る方針
  - 名前の由来と gftdcojp/ai-gftd-kaisya との無関係性
related:
  - 90-docs/adr/2607062000-kotoba-lang-teian-briefing-actor.md
  - 90-docs/adr/2607062010-kotoba-lang-koyomi-schedule-actor.md
  - 90-docs/adr/2607061500-kotoba-lang-tayori-correspondence-actor.md
  - 90-docs/adr/2606302300-org-taxonomy-4-orgs.md
  - manifest/repos.edn
supersedes: []
superseded_by: []
---

# ADR-2607072310: kotoba-lang/kaisha — communication space (Slack/Teams 相当) の EDN workspace surface

**Status**: accepted
**Date**: 2026-07-07
**Deciders**: Jun Kawasaki

## Context

GFTD workspace surface には slides / docs / drive / sheets / calendar / mail /
contacts / forms が揃っている一方、Slack/Teams 相当の**自前 communication
space は未設計**だった（2026-07-07 実測: 該当 repo / ADR ともに無し）。既存は
外部サービス接続面のみ — tayori（Email/Slack/WhatsApp 返信ドラフト、
ADR-2607061500）、gijiroku（Zoom/Meet/Teams 議事録取り込み、ADR-2607031100）。
オーナー指示（2026-07-07）で自前の communication space を起こすことになり、
候補10案から **kaisha（会社）** が選定された。

命名規約: surface ライブラリは英語名（slides/docs/sheets/calendar）が既存規約
だが、kaisha は「会社そのものが集まる場」という製品コンセプトを名前にした
オーナー選定名であり、例外として日本語名を採る。**gftdcojp/ai-gftd-kaisya
（kaisya 綴り・別 org・別製品）とは無関係**。

## Decision

新規 public repo **`kotoba-lang/kaisha`** を起こし、communication space の
EDN-native surface model として実装する。

- **Model**（`kaisha.model`、pure CLJC・依存ゼロ）: space（会社ごとに1つ、
  members/channels/read markers を保持）、channel（:public/:private、
  members、messages）、message（author/body/at/thread/reactions）。
  操作は post / reply / react / join / leave / mark-read、投影は
  messages-in-order / thread / mentions / mentioned? / visible-channels /
  unread。Slack 準拠のセマンティクス: thread は1段（親は top-level 必須）、
  private channel は member のみ可視、read marker は member×channel。
- **Validate**（`kaisha.validate`）: unknown-author / orphan-reply /
  nested-thread / private-without-members 等を `{:kaisha/severity :kaisha/code}`
  で返す。calendar.validate と同型。
- **Identity**: member に `:kaisha/did` slot（kotoba CACAO did:key 前提の置き場。
  model は強制しない）。
- **v1 スコープ外（follow-up）**: transport（kotoba-server XRPC lexicon、KSE
  realtime fan-out）、persistence（datom 投影、`kotoba-lang/crdt` による
  message body の共同編集）、UI。**AI のチャンネル投稿は必ず governed actor
  （post-LLM ⊣ Governor、send は人間承認）経由**とし、tayori/teian と同型の
  独立 actor として別 ADR で起こす（model へ直書きさせない）。

## Consequences

Slack/Teams 相当の社内空間が workspace suite の他 surface と同じ「pure EDN +
pure functions」の形で手に入り、Datomic/kotoba への永続・XRPC 公開・CRDT 化を
ホスト側の選択にできる。tayori（外部チャンネル返信）/ gijiroku（会議記録）とは
役割が直交: kaisha は自前の常設空間そのもの。

実行状況（2026-07-07）: repo scaffold（model/validate/test、5 tests 16
assertions 全緑）、GitHub 作成（public）+ push（`21766b9`）、west 登録、本 ADR。
transport / persistence / actor / UI は未着手の follow-up。

## References

`orgs/kotoba-lang/kaisha`、`orgs/kotoba-lang/calendar`（scaffold 手本）、
ADR-2607062000(teian)、ADR-2607062010(koyomi)、ADR-2607061500(tayori)、
ADR-2607031100(gijiroku)、ADR-2606302300(org taxonomy)。
