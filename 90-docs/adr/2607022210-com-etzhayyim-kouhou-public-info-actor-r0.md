---
id: adr-2607022210-com-etzhayyim-kouhou-public-info-actor-r0
title: "ADR-2607022210: com-etzhayyim-kouhou — public-interest / public-sector info curator → app-aozora (R0 scaffold)"
status: accepted
doc_type: adr
topic: actor-registration
authoritative: true
last_verified: 2026-07-02
implemented: 2026-07-02
implementation:
  repo: etzhayyim/com-etzhayyim-kouhou
  submodule: orgs/etzhayyim/com-etzhayyim-kouhou
  pinned: ecab66e
  landed_via: "child repo created + pushed (HEAD ecab66e646f037bfd0ddf747b69fdc7df6669030, R0 scaffold, clj-kondo 0 errors / 11 tests 31 assertions 0 fail). west manifest: repos.edn に entry 追加 + west.yml は kouhou の1 entry のみ single-entry 追加（全面再生成は pin 退行の罠 — sng precedent と同じ）。RAD identity journal は orgs/etzhayyim/root/80-data/kotoba-rad/kouhou.identity.journal.edn に積む（別 repo、別 commit）。"
authoritative_for:
  - "公益・国営団体（政府広報・独立行政法人・公益法人・官報等）の news/情報を整理（dedupe/tag/要約）して app-aozora に social post する actor を、独立 PublicInfoGovernor + append-only briefing 台帳で新設する判断"
  - "出典は registry ホワイトリスト（公的機関・公益法人）に登録された host のみ。registry 外ソース・出典なし・広告入り・catastrophe-veto は HARD HOLD（記録・非投稿）。read-only public fetch は ADR-2606072802 により自律許可（operator gate 不要）"
  - "publication は ADR-2606281500（種をまく）で autonomous by default。aggregate-first（1 run = 1 briefing、洪水投稿しない）。低信頼度は :low-confidence tag で透過（block しない）"
related:
  - orgs/etzhayyim/com-etzhayyim-kouhou
  - orgs/etzhayyim/com-etzhayyim-kouhou/docs/adr/0001-architecture.md   # child-level 設計正本
  - orgs/etzhayyim/com-etzhayyim-kouhou/registry/sources.seed.json        # canonical source whitelist (R0 illustrative)
  - 90-docs/adr/2606290000-sng-standalone-actor-r0.md                      # same single-entry-add precedent
  - orgs/etzhayyim/com-etzhayyim-kawaraban                                  # cross-actor: general news mirror
  - orgs/etzhayyim/com-etzhayyim-kataribe                                   # cross-actor: religious press
  - orgs/etzhayyim/com-etzhayyim-danjo                                      # cross-actor: gov-data discrepancy
  - orgs/etzhayyim/com-etzhayyim-tashikame                                  # cross-actor: briefing → fact-check input
---

# ADR-2607022210: com-etzhayyim-kouhou — public-interest / public-sector info curator → app-aozora (R0 scaffold)

**Status**: accepted (R0 scaffold landed 2026-07-02)
**Date**: 2026-07-02
**Deciders**: Jun Kawasaki

## Context

オーナー指示（2026-07-02）：**公益・国営団体などの news/情報を整理して social post する** actor を設計せよ（tashikame と併せて）。この目的を担う actor はなかった:

- **kawaraban** — news を mirror するが curate/post はしない（G1 mirror-not-adjudicator）。
- **kataribe** — 宗教法人 press。ドメインが違う。
- **danjo** — 政府データの discrepancy 観測。curated briefing の feed 投稿はしない。

## Decision

kouhou（広報）を **workspace actor pattern** の public-info curation instance として新設。正本は子リポ `docs/adr/0001-architecture.md`。骨子:

1. **封じ込め + 独立 PublicInfoGovernor + 不変台帳。** 知能ノード `organizer` は *proposal（faithful summary + provenance URL + domain/tags）のみ*（`:effect :assessment`）。独立 **PublicInfoGovernor** が検閲。commit/hold は append-only briefing 台帳に積む。不変条件: *kouhou は PublicInfoGovernor が拒否した briefing を決して投稿しない*。

2. **langgraph-clj StateGraph, 1 run = 1 source digest。** 内部無限ループなし。`interrupt-before` なし。

3. **自律投稿（ADR-2606281500 種をまく）。** aggregate-first（1 run = 1 briefing）。off-switch は publish 毎の revocable member CACAO leash。publication ≠ actuation。

4. **PublicInfoGovernor gates。**
   - HARD → HOLD: `:no-actuation` / `:no-provenance`（source-url 空）/ `:source-not-in-registry`（host が whitelist 外）/ `:commercial-content`（ad/sponsored marker）/ `:catastrophe-veto`（Rider §2 scan）。
   - SOFT → tag 付き投稿: `:low-confidence`。

5. **出典ホワイトリスト（負荷規則）。** 正本は `registry/sources.seed.json`（host/kind/url/read-only）。`kouhou.governor/default-registry` が offline test 用に host set を mirror。read-only public fetch は ADR-2606072802 で自律許可（operator gate 不要）。

6. **注入境界・`:db-api`・自己主権 cacao・`.cljc` portable** — tashikame と同様。

## Registration（superproject 反映）

- **子リポ**: `etzhayyim/com-etzhayyim-kouhou` を作成し R0 scaffold を push（HEAD `ecab66e`、lint/test green）。
- **west manifest**: `repos.edn` entry + `west.yml` single-entry 追加（全面再生成は pin 退行の罠で取らない — sng precedent）。
- **RAD identity**: `orgs/etzhayyim/root/80-data/kotoba-rad/kouhou.identity.journal.edn` に積む（別 repo・別 commit）。

## Consequences

- (+) 登録済み公的機関ソースの info を、governor-clean・出典付き・広告なし・append-only 監査で per-post 人間 gating なしに social post できる wire ができた。
- (+) `organizer` は swap 可能（mock → Murakumo LLM）。
- (−) R0 registry は illustrative。deploy で実在する公式 feed の curate が必要。R0 summarizer は first-N-chars heuristic。catastrophe-veto denylist も illustrative。

## Notes

- 本 ADR は superproject レベルの登録・位置づけが責務。設計の正本は子リポ `docs/adr/0001-architecture.md`。
- fact-check（tashikame）と curation（kouhou）は、one-actor-one-role charter で分離（adjudicative speech vs faithful summarization は governor も分ける）。
