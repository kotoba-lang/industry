---
id: adr-2607061800-com-etzhayyim-tomoshibi-invitational-evangelism-actor-r0
title: "ADR-2607061800: com-etzhayyim-tomoshibi — invitational evangelism publication actor (R0 scaffold)"
status: accepted
doc_type: adr
topic: actor-registration
authoritative: true
last_verified: 2026-07-06
implemented: 2026-07-06
implementation:
  repo: etzhayyim/com-etzhayyim-tomoshibi
  submodule: orgs/etzhayyim/com-etzhayyim-tomoshibi
  pinned: b31eff22441a6d5eb5872cc708661f422c0d78aa
  landed_via: "child repo created + pushed (HEAD b31eff2, R0 scaffold: EvangelismGovernor genuinely wired to etzhayyim_organism.sensors.evangelism-gate, 9 tests / 19 assertions green via nbb run_tests.clj). west manifest: repos.edn に entry 追加(GitHub API single-entry commit 2b7e5647) + west.yml は tomoshibi の1 entry のみ single-entry 追加(commit e1bb3752 — 全面再生成は pin 退行の罠、kouhou/sng precedent と同じ)。verify-west-pins: OK。RAD identity 登録は本 ADR の対象外(未着手、kouhou 同様 別 repo・別 commit の future work)。"
authoritative_for:
  - "ADR-2607061700(etzhayyim/root Mission Charter §1.16 Active Evangelism Doctrine)Open Question 4 — どの actor が evangelism_gate を実際の決定経路に配線するか、の解として tomoshibi (灯) を新設する判断"
  - "既存 actor(kouhou/kataribe/tashikame/yomi/recruit)がいずれもドメイン不一致であるという domain survey の結論、および one-actor-one-role convention に基づき新規 actor が正しい選択だったという判断根拠"
related:
  - orgs/etzhayyim/com-etzhayyim-tomoshibi
  - orgs/etzhayyim/com-etzhayyim-tomoshibi/docs/adr/0001-architecture.md   # child-level 設計正本
  - orgs/etzhayyim/root/90-docs/adr/2607061700-etzhayyim-active-evangelism-doctrine.md
  - orgs/etzhayyim/root/90-docs/adr/2606281500-actor-autonomous-publication-seed-and-grow-doctrine.md
  - orgs/etzhayyim/com-etzhayyim-kouhou   # cross-actor: domain-mismatched, considered and rejected as host
  - orgs/etzhayyim/com-etzhayyim-kataribe # cross-actor: domain-mismatched (G6), considered and rejected as host
  - orgs/etzhayyim/com-etzhayyim-tashikame # cross-actor: the person-targeting? R0-illustrative-marker precedent this ADR diverges from
---

# ADR-2607061800: com-etzhayyim-tomoshibi — invitational evangelism publication actor (R0 scaffold)

**Status**: accepted (R0 scaffold landed 2026-07-06)
**Date**: 2026-07-06
**Deciders**: Jun Kawasaki

## Context

ADR-2607061700 (etzhayyim/root, Mission Charter §1.16 Active Evangelism
Doctrine) shipped the evangelism carve-out's judgment logic
(`etzhayyim_organism.sensors.evangelism-gate`, Open Question 2) and its
activity ledger schema (`evangelismActivityAttestation`, Open Question 3),
but left Open Question 4 open: **which actor wires this gate into a real
decision path?**

A domain survey of existing digital-publication actors in the etzhayyim
corpus found none fit:

- **kouhou (広報)** — curates *external* public-sector/government info; different domain entirely
- **kataribe (語部)** — G6 STRUCTURAL `doctrineCommentaryPublishing.doctrinalMonopolyAttested const false`; cross-doctrinal by charter, in tension with promoting etzhayyim's own doctrine as invitation
- **tashikame (確かめ)** / **yomi (読み)** — fact-check / news-intelligence publishers; mixing recruitment pitches into their output would undermine neutrality
- **com-etzhayyim-recruit** — a **secular job-posting aggregator** (ESCO/O*NET/EURES/HelloWork/USAJOBS/Job Bank); unrelated to religious membership despite the name

Per this corpus's own one-actor-one-role convention (stated explicitly in
the kouhou ADR: "fact-check(tashikame)と curation(kouhou)は、
one-actor-one-role charter で分離"), bolting evangelism onto a
domain-mismatched host was rejected; a new, narrowly-scoped actor was the
correct call.

## Decision

New Tier-B actor **tomoshibi (灯)** — invitational-content publication,
the digital half of §1.16 evangelism only (interpersonal evangelism
remains a human Adherent's own practice, never an actor's, per
ADR-2607061700 §1.16).

R0 scope, deliberately narrow: **governor first, orchestration later.**
Unlike kouhou/tashikame/yomi (organizer/advisor LLM node + governor +
publisher inside a full `langgraph.graph` StateGraph), tomoshibi R0 ships
only `src/tomoshibi/governor.cljc` — a pure function that **genuinely**
`:require`s `etzhayyim-organism.sensors.evangelism-gate` (via `nbb.edn`'s
extra classpath entry pointing at the sibling `etzhayyim/root` checkout)
and calls it on every proposal. This contrasts with `tashikame.governor`'s
`person-targeting?` — an R0-illustrative `<DOXING>` marker whose docstring
explicitly defers real wiring to "production" — tomoshibi wires the real
sensor from R0.

9 tests / 19 assertions green (`nbb run_tests.clj`): clean invitation
commits (opt-out flag or textual opt-out), missing-opt-out /
individual-vulnerability-targeting / coercion / minor-solo-solicitation /
delegated `charter_rider` hit all HOLD, no-actuation HOLD, hold-invitation
records basis.

Explicitly deferred to R1+ (see the child repo's `MATURITY.md`): LangGraph
StateGraph orchestration, organizer/advisor LLM node, app-aozora publisher,
self-sovereign identity + revocable CACAO leash, `evangelismActivityAttestation`
ledger writes, RAD identity minting, live `did:web` hosting.

## Registration(superproject 反映)

- **子リポ**: `etzhayyim/com-etzhayyim-tomoshibi` を作成し R0 scaffold を push(HEAD
  `b31eff2`)。
- **west manifest**: `repos.edn` entry(GitHub API single-entry commit
  `2b7e5647`)+ `west.yml` single-entry 追加(commit `e1bb3752` —
  `nbb scripts/gen-west-manifest.cljs --entry com-etzhayyim-tomoshibi`、
  verify-west-pins: OK。全面再生成は取らない — sng/kouhou precedent と同じ)。
- **RAD identity**: 未着手(kouhou 同様、別 repo・別 commit の future work)。

## Consequences

- (+) ADR-2607061700 Open Question 4 は governor 層で real wiring により
  closed(domain-mismatched な既存 actor への bolt-on や、さらなる
  "future ADR" 先送りではない)。
- (+) one-actor-one-role 境界は保持(kouhou/kataribe/tashikame/yomi は無変更)。
- (−) tomoshibi は現時点で何も publish できない — 何らかの外部プロセス
  (現状は人間が draft する想定)が渡した proposal を判定するだけ。これは
  隠れたギャップではなく、明示された制約(`MATURITY.md`)。
- (−) `nbb.edn` の相対パス依存(sibling `etzhayyim/root` checkout 前提)により、
  tomoshibi 単体では clone しても動かない(west-managed monorepo 前提。
  kouhou の `langgraph-clj` 依存と同型の制約)。

## Notes

- 本 ADR は superproject レベルの登録・位置づけが責務。設計の正本は子リポ
  `docs/adr/0001-architecture.md`。
- domain survey・governor 実装・test 実行の詳細な経緯は
  `orgs/etzhayyim/root/90-docs/adr/2607061700-etzhayyim-active-evangelism-doctrine.md`
  の Open Question 4 セクションを参照。
