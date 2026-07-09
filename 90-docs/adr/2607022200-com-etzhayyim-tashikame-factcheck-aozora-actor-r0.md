---
id: adr-2607022200-com-etzhayyim-tashikame-factcheck-aozora-actor-r0
title: "ADR-2607022200: com-etzhayyim-tashikame — fact-check verdict publisher → app-aozora (R0 scaffold)"
status: accepted
doc_type: adr
topic: actor-registration
authoritative: true
last_verified: 2026-07-02
implemented: 2026-07-02
implementation:
  repo: etzhayyim/com-etzhayyim-tashikame
  submodule: orgs/etzhayyim/com-etzhayyim-tashikame
  pinned: 7d66ce7
  landed_via: "child repo created + pushed (HEAD 7d66ce7ffa2bb4d4785e95a77179c169c3415c60, R0 scaffold, clj-kondo 0 errors / 10 tests 30 assertions 0 fail). west manifest: repos.edn に entry 追加 + west.yml は tashikame の1 entry のみ single-entry 追加（全面再生成は既存の drift で pin 退行する罠を避けるため意図的に取らない — ADR-2606290000 sng precedent と同じ）。RAD identity journal は orgs/etzhayyim/root/80-data/kotoba-rad/tashikame.identity.journal.edn に積む（別 repo、別 commit）。"
authoritative_for:
  - "news/social の fact-check を行い、その評定（supported/refuted/misleading/unverifiable + 引用）を app-aozora に自律投稿する actor を、独立 FactGovernor + append-only 評定台帳で新設する判断"
  - "publication は ADR-2606281500（種をまく）により autonomous by default — per-post の operator/Council 事前制限なし。FactGovernor の HARD violation（uncited-conclusive / malformed-citation / catastrophe-veto / person-targeting / no-actuation）のみが publish を withheld する。低信頼度は publish を block せず :low-confidence tag で透過"
  - "tashikame は assess のみ（:effect :assessment）。actuation（資金移動・権限付与・物理駆動等）は絶対に行わない（publication ≠ actuation）"
related:
  - orgs/etzhayyim/com-etzhayyim-tashikame
  - orgs/etzhayyim/com-etzhayyim-tashikame/docs/adr/0001-architecture.md   # child-level 設計正本
  - 90-docs/adr/2606290000-sng-standalone-actor-r0.md                        # same single-entry-add precedent
  - orgs/etzhayyim/com-etzhayyim-kawaraban                                    # claim source (news mirror, verdict 不可)
  - orgs/etzhayyim/com-etzhayyim-danjo                                         # cross-actor: discrepancy oversight
  - orgs/etzhayyim/com-etzhayyim-yomi                                          # cross-actor: intel assessments (own ledger)
---

# ADR-2607022200: com-etzhayyim-tashikame — fact-check verdict publisher → app-aozora (R0 scaffold)

**Status**: accepted (R0 scaffold landed 2026-07-02)
**Date**: 2026-07-02
**Deciders**: Jun Kawasaki

## Context

オーナー指示（2026-07-02）：**social post / news の fact-check を行い app-aozora に投稿する** actor を設計・統合せよ。この目的を一本で担う actor は存在しなかった。隣接 actor はそれぞれ範囲が違う:

- **kawaraban** — news を mirror する MEDIUM。charter G1 で verdict 不可。
- **danjo** — 政府データの discrepancy 観測。verdict を出さず、feed 投稿もしない。
- **ake** — KG/profile の community-edit。claim verdict でなく、live publish は Council-gated。
- **yomi** — intel assessment を**自身の台帳**に公開（aozora feed ではない）。

## Decision

tashikame（確かめ）を **workspace actor pattern** の fact-check instance として新設（robotaxi / gftd-talent / itonami / kyoninka / sng と同型）。設計の正本は子リポ `docs/adr/0001-architecture.md`。骨子:

1. **封じ込め + 独立 FactGovernor + 不変台帳。** 知能ノード `factllm` は1ノードに封じ込め *proposal（評定 + 引用 + confidence）のみ* を返す（`:effect :assessment`）。独立系統の **FactGovernor** が検閲し、commit / hold に振る。commit/hold は append-only の評定台帳に積む。不変条件: *tashikame は FactGovernor が拒否した評定を決して投稿しない*。

2. **langgraph-clj StateGraph, 1 run = 1 claim check。** 内部無限ループなし。`interrupt-before` なし — publication は自律(下記)。

3. **自律投稿（ADR-2606281500 種をまく）。** publication は actor 自身の SPEECH で **autonomous by default**。per-post の operator/Council 事前制限なし。off-switch は publish 毎の revocable member CACAO leash（`:leash`）。**publication ≠ actuation**: tashikame は assess のみ。FactGovernor の HARD violation のみが publish を withheld する。

4. **FactGovernor gates。**
   - HARD → HOLD（記録、非投稿）: `:no-actuation` / `:uncited-conclusive`（conclusive rating に引用 0）/ `:malformed-citation` / `:catastrophe-veto`（Rider §2 scan）/ `:person-targeting`（私的個人への doxing。public-figure の claim-check は可）。
   - SOFT → tag 付き投稿（block しない）: `:low-confidence`。

5. **注入境界。** Store（`MemStore` ‖ `DatomicStore` ‖ kotoba-server）/ Advisor（mock ‖ `langchain.model` on Murakumo + read-only web gather）/ Publisher（Mock ‖ app-aozora createRecord）/ Phase（0 observe → 1 autonomous-publish）。コアは不変。

6. **Store は `:db-api` 駆動。** `MemStore ≡ DatomicStore` contract test で保証。

7. **自己主権 identity。** `tashikame.cacao`（`tsumugu.cacao` から port）+ `tashikame.aozora`（app-aozora createRecord）。秘密鍵は `.tashikame/identity.edn`（gitignore）。

8. **`.cljc` portable**（JVM/SCI/cljs/WASM）。`.clj` は JVM-only I/O（cacao, aozora）のみ。

## Registration（superproject 反映）

- **子リポ**: `etzhayyim/com-etzhayyim-tashikame` を作成し R0 scaffold を push（HEAD `7d66ce7`、lint/test green）。
- **west manifest**: `manifest/repos.edn` に entry 追加 + `manifest/west.yml` は **tashikame の1 entry のみ single-entry 追加**。全面再生成は取らない（`west.yml` は他子リポで既に STALE/drift しており、再生成は local working HEAD で pin し直すため pin 退行の罠 — ADR-2606290000 sng precedent と同じ）。
- **RAD identity**: `orgs/etzhayyim/root/80-data/kotoba-rad/tashikame.identity.journal.edn` に actor identity を積む（別 repo `etzhayyim/root` への別 commit）。

## Consequences

- (+) claim を fact-check し、governor-clean・引用 grounded・append-only 監査付きで per-post 人間 gating なしに評定投稿できるループができた。
- (+) `factllm` は swap 可能（mock → Murakumo LLM）で、投稿保証（governor + 台帳）は不変。
- (−) R0 `mock-advisor` は heuristic。実評価は deploy で LLM + read-only web gather を wire する必要。catastrophe-veto denylist は illustrative（canonical `etzhayyim_organism.sensors.charter_rider.scan` の wire は follow-up）。`:person-targeting` も R0 heuristic。

## Notes

- 本 ADR は superproject レベルの登録・位置づけが責務。設計の正本は子リポ `docs/adr/0001-architecture.md`。
- actor pattern の同型性は `CLAUDE.md`「Actors」節に拠る。
