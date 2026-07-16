---
id: adr-2606290000-sng-standalone-actor-r0
title: "ADR-2606290000: com-etzhayyim-sng — e-methane Sabatier SNG 製造アクターを standalone Tier-B actor として新設（R0 scaffold）"
status: accepted
doc_type: adr
topic: actor-registration
authoritative: true
last_verified: 2026-06-29
implemented: 2026-06-29
implementation:
  repo: etzhayyim/com-etzhayyim-sng
  submodule: orgs/etzhayyim/com-etzhayyim-sng
  pinned: 03b3b6c
  landed_via: "child repo created + pushed (HEAD 03b3b6c, R0 scaffold). west manifest: repos.edn に sng entry を追加し、west.yml は sng の1 entry のみ single-entry 追加（gen-west-manifest で sng 行が canonical 一致を確認）。main 反映は repos.edn :manifest-workflow の GitHub-API single-entry 正経路で確定予定。※ west.yml の全面再生成は意図的に取らない（失敗/WIP で local HEAD が pin から drift した子 repo 群の pin 退行を避けるため）。RAD identity journal は orgs/etzhayyim/root/80-data/kotoba-rad/sng.identity.journal.edn に積む follow-up。"
authoritative_for:
  - Sabatier methanation（CO₂ + 4 H₂ → CH₄ + 2 H₂O）による e-methane 製造を、hikari energy actor の cell ではなく standalone Tier-B actor として新設する判断
  - sng を ワークスペース actor pattern の第5 instance（robotaxi / gftd-talent / itonami / kyoninka の同型）とする位置づけ
  - synth-LLM を1ノードに封じ込め *proposal のみ* 返させ、独立 CarbonGovernor が9つの硬い炭素不変条件を検閲する設計
  - 炭素ルールブックをコードでなく EAVT ground datom / governor 定数（データ）として持ち、.cljc portable（JVM/SCI/cljs/WASM）かつ整数のみで扱う方針
related:
  - orgs/etzhayyim/com-etzhayyim-sng                    # the domain organism (R0 scaffold)
  - orgs/etzhayyim/com-etzhayyim-sng/docs/adr/0001-architecture.md  # child-level architecture ADR（正本）
  - orgs/com-junkawasaki/langgraph-clj                  # StateGraph / checkpoint / interrupt-before
  - orgs/com-junkawasaki/langchain-clj                  # :db-api store backend + langchain.model advisor
  - 90-docs/adr/2606130900-maxwell-rsi-ecosystem.md     # energy-substrate ecosystem context
---

# ADR-2606290000: com-etzhayyim-sng — e-methane Sabatier SNG 製造アクターを standalone Tier-B actor として新設（R0 scaffold）

**Status**: accepted
**Date**: 2026-06-29
**Deciders**: Jun Kawasaki

## Context

ADR-2605265900 は Sabatier methanation（CO₂ + 4 H₂ → CH₄ + 2 H₂O, 250–400 °C over
Ni/γ-Al₂O₃, ΔH = −165 kJ/mol）を closed-loop synfuel pathway として評価し
**CONDITIONALLY PERMITTED**（Council ratify 待ち）とした。条件は、combined
biomethane+SNG ≤ 200 Nm³/day cap、open-catalyst 義務、green-H₂ + DAC-CO₂ only の
feedstock chain、Council Lv6+≥3 による pathway-selection review。元 ADR は実装を
`hikari` energy actor の cell（`20-actors/hikari/cells/sng_sabatier/`, lexicon
`com.etzhayyim.hikari.*`）として path-reserve したが、その cell は未 scaffold だった。

メタン化の運用は、単一 energy cell の範囲を超える——多数の batch・facility・
Council seat にまたがる carbon-discipline の決定（liability-bearing）である。
`kamado` actor は既に D-gate sub-ADR pathway を standalone Tier-B actor に昇格する
前例を作っている（kamado の G1 gate は親 2605263500 D3 を直接引用）。SNG もこれに
倣い、cell でなく standalone actor にする。

## Decision

sng を **standalone Tier-B actor** として新設する——ワークスペース actor pattern
の**第5 instance**（robotaxi-actor / gftd-talent-actor / ai-gftd-itonami /
com-etzhayyim-kyoninka の同型）。詳細な設計は子リポの
`orgs/etzhayyim/com-etzhayyim-sng/docs/adr/0001-architecture.md`（正本）に置き、
本 ADR は superproject レベルの登録・位置づけを固定する。骨子:

1. **封じ込め + 独立 governor + 不変台帳。** synthesis advisor（**synth-LLM**）は
   1ノードに封じ込め *proposal のみ* を返す（`:recommendation` + rationale + cited
   facts, `:effect :assessment`）。独立系統の **CarbonGovernor** が EAVT ground
   datom 上で全 proposal を硬い炭素不変条件で検閲し commit / hold / human-approval
   に振る。不変条件: *actor は governor が拒否する batch attestation を決して記録
   せず、attestation 付与や reactor 起動も決して行わない*。commit/hold/record は
   append-only の batch genealogy 台帳に積む。

2. **langgraph-clj StateGraph, 1 run = 1 operation。** 内部無限ループなし。
   `interrupt-before #{:request-approval}` が human-in-the-loop seam——pathway
   selection は governor が完全 clean でも常に Council Lv6+≥3 signoff で一時停止
   （high-stakes）。

3. **三つの注入境界。** Store（`MemStore` ‖ `DatomicStore`）/ Advisor
   （`mock-advisor` ‖ `llm-advisor` on `langchain.model`）/ Phase（R0→R3）。コアは
   三つとも不変。

4. **Store は `:db-api` 駆動。** backend とは langchain.db の
   `{:q :transact! :db :pull :entid}` マップ越しにのみ喋る。
   `langchain.db/api`（in-process）と `langchain.kotoba-db/kotoba-api`
   （kotoba-server XRPC）が同マップを実装するので、同一 record が in-mem / 実
   Datomic / kotoba pod を選ばず動く。`MemStore ≡ DatomicStore` contract test で保証。

5. **炭素ルールブックはデータ。** feedstock class・catalyst formula・
   council-approval-level・leak/storage cap・aggregate cap は EAVT ground datom /
   governor 定数（ADR 由来）。batch や feedstock CID の追加は
   `:batch/register` / `:feedstock/record` transaction で、catalysis-chemist
   Council seat が review する——コード変更不要。`commercial-co2` は schema enum
   に表れない閉ループ class なので、化石炭素 feedstock は構造的に排除される。

6. **整数のみ**（charter）: Nm³・圧力・op-temp・council-level・mass-balance-pct・
   leak-rate-pct は整数、日付は `yyyymmdd` 整数で注入された `:today` と比較。
   `.cljc` portable（JVM / SCI / cljs / WASM）、date/decimal library 非依存。

7. **Lexicon 再 namespace。** ADR-2605265900 §6 が `com.etzhayyim.hikari.*` で
   pre-declare した4つの SNG lexicon は `com.etzhayyim.sng.*` に移す
   （`sngBatchAttestation` / `sngStorageInventory` / `sngPathwaySelectionRecord` /
   `silenSngReview`）。kamado pattern（`com.etzhayyim.kamado`）に準拠。
   ADR-2605265900 §6 table はこれに併せて修正。

### CarbonGovernor の9つの不変条件

facility recognized · closed-loop carbon only · open catalyst only ·
aggregate cap ≤ 200 Nm³/day · leak ≤ 1 % with quarterly OGI ·
storage ≤ 500 Nm³/parcel and ≤ 2,000 aggregate · mass-balance ≥ 95 % ·
high-temp (> 350 °C) needs council-level ≥ 6 · no-actuation
（`:effect` は `:assessment`）。加えて soft rule: confidence floor → escalate、
pathway selection は常に high-stakes → Council Lv6+≥3。

`:batch/attest` は9つ全てを検査; `:pathway/select` は facility + no-actuation
のみ（割り当て自体は Council の判断）。

## Registration（superproject 反映）

- **子リポ**: `etzhayyim/com-etzhayyim-sng` を作成し R0 scaffold を push 済み
  （HEAD `03b3b6c`）。`deps.edn` + `.cljc` 正本（`src/sng/{phase,sim,governor,
  synthesis,synthllm,store}.cljc`）+ `docs/adr/0001-architecture.md` + contract
  test（`governor_contract_test.clj` / `store_contract_test.clj`）。
- **west manifest**: `manifest/repos.edn` に sng entry を追加し、west.yml は
  **sng の1 entry のみ single-entry 追加**（`gen-west-manifest` で当該行が canonical
  一致を確認）。docs branch で stage 済み。main 反映は `repos.edn :manifest-workflow`
  の GitHub-API single-entry 正経路で確定（pin == repo HEAD `03b3b6c` を検証）。
  **全面再生成は意図的に取らない** — `west update` に失敗した子 repo 群
  （drawingml-svg / freeboard / manimani / root / app-aozora / org-spirit-in-physics
  等）は local HEAD が pin から drift しており、再生成は local working HEAD で pin
  し直すため **pin 退行**を起こす（CLAUDE.md「再生成はローカル working HEAD で pin
  するので…黙ってロールバックする＝pin 退行の罠」）。
- **RAD identity**: `orgs/etzhayyim/root/80-data/kotoba-rad/sng.identity.journal.edn`
  に actor identity を積む follow-up（`:rad/repo` / `:rad/did-web` / 署名参照）。
- **ADR-2605265900 §5/§6 修正**: cell path-reserve 取消と lexicon ns
  `com.etzhayyim.sng.*` への変更を反映する follow-up。

## Consequences

- (+) 「この batch は e-methane か？」という liability-bearing な炭素規律の決定を、
  モデルが「attested」と言う前に独立 governor と不変台帳で束ねられる。
- (+) synth-LLM は upgrade/実モデル差し替え可能だが、炭素保証は governor とデータ
  に住むため影響しない。
- (+) feedstock/catalyst rulebook はデータなので、catalysis-chemist Council seat
  との curation は reviewed transaction（リファクタでない）。
- (−) R0 scaffold 時点では rulebook は illustrative。Council seat との curation が
 必要。整数 only / `commercial-co2` 非表現 などの制約は意図的。
- (−) manifest の main 反映と RAD identity 積みは follow-up（本 ADR の accepted は
  設計と R0 scaffold の時点）。landed_via は main 取り込み後に更新する。

## Notes

- 本 ADR は superproject レベルの登録・位置づけが責務。設計の正本は子リポの
  `docs/adr/0001-architecture.md`。
- actor pattern の同型性は CLAUDE.md「Actors」節および
  `.cursor/rules/always/actor-pattern-rule.mdc` に拠る。
- 本 ADR を supersede する場合は、pathway の Council ratify 状態・rulebook の
  curation進捗・kotoba-server sovereign ledger 立ち上げ有無を併せて更新すること。

## Addendum (2026-07-04, ai-gftd-itonami deprecation cleanup)

本文が同型4例の1つとして挙げる `ai-gftd-itonami` は repo として実体化しないまま終わった
（GitHub 上に実在せず、ローカルの空 placeholder checkout も削除済み）。
ops-LLM⊣CertGovernor の実装は `orgs/gftdcojp/cloud-itonami` 本体に統合されている。
本文は起票時点の記述として保持し書き換えない。
