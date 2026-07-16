---
id: adr-2607010900-com-etzhayyim-fleet-coordinator-actor
title: "ADR-2607010900: com-etzhayyim-fleet — FleetCoordinatorActor（kotoba-fleet の封じ込め実体）"
status: accepted
doc_type: adr
topic: fleet-coordination-actor
authoritative: true
last_verified: 2026-07-01
related:
  - 90-docs/adr/2606302000-kotoba-fleet-agent-coordination.md
  - orgs/kotoba-lang/kotoba-fleet/README.md
  - orgs/etzhayyim/com-etzhayyim-fleet/README.md
  - orgs/com-junkawasaki/robotaxi-actor/README.md
  - orgs/etzhayyim/root/80-data/kotoba-rad/fleet.identity.journal.edn
supersedes: []
superseded_by: []
---

# ADR-2607010900: com-etzhayyim-fleet — FleetCoordinatorActor

**Status**: accepted
**Date**: 2026-07-01
**Deciders**: Jun Kawasaki

## Context

ADR-2606302000 が **kotoba-fleet**（20-agent 並列開発の調整 substrate = lease +
governor-drain + fleet-view を append-only datom 上で）を定義し、`kotoba-lang/kotoba-fleet`
に純 `.cljc` ライブラリとして実装した。ライブラリは「再利用可能な primitive」だが、
それ自体は鍵も graph も持たない ⇒ RAD identity を持つ **actor 実体**ではない。

CLAUDE.md「Actors」節は、ドメインを actor 化するとき **封じ込め + 独立 governor +
不変台帳**の同型（robotaxi AR1 ⊣ SafetyGovernor / ai-gftd-itonami ops-LLM ⊣
CertGovernor）に揃え、`etzhayyim/com-etzhayyim-{name}` として west + RAD identity まで
登録することを完了条件とする。

## Decision

**kotoba-fleet を封じ込めた actor 実体 `etzhayyim/com-etzhayyim-fleet` を起こす。**

- **Coordinator**（`fleet.coordinator`）= 封じ込め知能ノード。次に materialize すべき
  pending write を **proposal のみ**返す（今は決定的 mock、`:advise` で LLM に差替可）。
- **FleetGovernor**（`fleet.governor`）= 独立検閲。単一不変条件を強制:
  **「提案 agent が work の lease を保持し、gate を通過したときだけ materialize。
  protected path（`manifest/`, `90-docs/adr/`）は人間 sign-off を要求。」**
  それ以外は append-only に hold（黙って落とさない・上書きしない）。
- **StateGraph**（`fleet.actor`）= `observe → coordinate → govern → decide →
  materialize | hold | (human-signoff)`。1 run = 1 coordination tick（無限内部ループ無し）。
  `interrupt-before #{:human-signoff}` を human-in-the-loop に転用。`:audit` が不変台帳。
  materialize/hold は kotoba-fleet の `record!` で receipt を積み、proposal は一度だけ drain。
- 注入境界: Store(`kotoba-fleet` MemStore ‖ 実 kotoba-db) / Advisor(mock ‖ LLM) /
  materialize hook(単一 git writer) を差替、コアは不変。

不変条件は contract test で固定（6 tests / 16 assertions）: 非 lease-holder・gate 却下・
期限切れ lease は materialize に到達せず、protected path は sign-off を経てのみ materialize。

## kotoba-fleet lib 変更（同時反映）

actor 統合で判明した gap を lib 側で解消: `kotoba.fleet.governor/record!` を public 化し、
drain! だけでなく **actor graph からも 1 提案の決定を receipt できる**ようにした
（receipt が無いと proposal が pending のままで毎 tick 再選択される）。pin 前進済み。

## Consequences

- kotoba-fleet（lib）と com-etzhayyim-fleet（actor 実体）の 2 層に分離: 前者は全 org が
  消費する platform substrate、後者は封じ込め・governor・RAD identity を持つ運用実体。
- RAD identity は `etzhayyim/root:80-data/kotoba-rad/fleet.identity.journal.edn` に登録
  （did:web:etzhayyim.github.io:com-etzhayyim-fleet、repo、content CID）。
  **Ed25519 CACAO 自己署名（actor 鍵）は鍵 provision 後の follow-up**（journal の
  `:rad/attestation :pending-self-sign` で明示）。
- west 登録は light（clone-depth 無し、full）で ADR-2606302100 のハイブリッド方針と整合。

## Addendum (2026-07-04, ai-gftd-itonami deprecation cleanup)

本文が同型パターンの一例として挙げる `ai-gftd-itonami` は repo として実体化しないまま
終わった（GitHub 上に実在せず、ローカルの空 placeholder checkout も削除済み）。
ops-LLM⊣CertGovernor の実装は `orgs/gftdcojp/cloud-itonami` 本体に統合されている。
本文は起票時点の記述として保持し書き換えない。
