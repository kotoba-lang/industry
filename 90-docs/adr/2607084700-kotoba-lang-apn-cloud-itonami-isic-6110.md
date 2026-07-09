---
id: adr-2607084700-kotoba-lang-apn-cloud-itonami-isic-6110
title: "ADR-2607084700: kotoba-lang/apn (All-Photonics Network topology + RWA) + cloud-itonami-isic-6110 (Network Advisor ⊣ Network Provisioning Governor)"
status: accepted
doc_type: adr
topic: apn-optical-network-wired-telecom-operator
authoritative: true
last_verified: 2026-07-08
authoritative_for:
  - kotoba-lang/apn の新設と設計根拠
  - cloud-itonami-isic-6110 の :spec -> :implemented 昇格根拠
  - registry.edn の ISIC-Rev.4 J-prefix プレースホルダから ISIC-Rev.5 命名への訂正根拠
related:
  - orgs/etzhayyim/root/90-docs/adr/2606051600-noroshi-photonic-electronic-convergence-comms-chip-isac.md
  - orgs/cloud-itonami/cloud-itonami-isic-6190
  - orgs/kotoba-lang/dcs
supersedes: []
superseded_by: []
---

# ADR-2607084700: kotoba-lang/apn (All-Photonics Network topology + RWA) + cloud-itonami-isic-6110 (Network Advisor ⊣ Network Provisioning Governor)

**Status**: accepted
**Date**: 2026-07-08
**Deciders**: Jun Kawasaki

## Problem

The question put to the substrate was: *「cloud-itonami, kotoba-lang で iown apn での 光通信network の ライブラリ、設計, データセンター設計, modeling などは設計されている?」*

A repo-wide search returned **no**: neither `cloud-itonami` (the ISIC
business-actor fleet) nor `kotoba-lang` (the shared domain-kernel org)
had any library, design or modeling for an IOWN-style All-Photonics
Network (APN) — no wavelength-routed optical topology model, no
Routing-and-Wavelength-Assignment (RWA), no data-center-network
design. The closest existing work was `etzhayyim/root/20-actors/noroshi`
(ADR-2606051600), which explicitly names "the IOWN shape" but operates
one layer down — a photonics-electronics-convergence *comms chip* +
ISAC sim + packaging robotics, not a network-topology/provisioning
model. `etzhayyim/com-etzhayyim-data-center-ops` covers *operations*
(facility/rack/power/SLA), not optical topology or wavelength
provisioning either.

The user asked to close this gap: design+implement the library in
`kotoba-lang`, and its consuming implementation in `cloud-itonami`.

## Decision

### Decision 1: scope — topology + RWA, explicitly not physical-layer or vendor control-plane

`kotoba-lang/apn` models photonic nodes (ROADM sites), DWDM fibre
links, and lightpaths (single-wavelength end-to-end optical circuits)
as plain EDN, plus a Dijkstra + single-link-deviation alternate-routing
RWA solver and a pure provisioning lifecycle. It deliberately excludes
physical-layer engineering (optical power budget, OSNR — that stays
`noroshi`'s domain, avoiding duplication with the tapeout-facing
photonic-chip actor) and any vendor control-plane wire protocol
(GMPLS/PCEP/NETCONF — host-injected, out of scope, the same seam
`dcs.ports` uses to keep fieldbus I/O out of `dcs`). "APN" here is the
generic industry term for a wavelength-routed all-optical transport
network; the library is built from first principles and open
literature, not any vendor-proprietary or NDA-derived specification.

### Decision 2: wavelength continuity is a structural invariant, not a validator rule

A lightpath (`apn.model`) carries exactly ONE `:apn/wavelength` field
for its entire `:apn/path` (no per-hop wavelength field) — a
wavelength-converting circuit, which would require O-E-O regeneration
at a hop, is simply inexpressible in the data model. This is the same
"unrepresentable, not merely policed" discipline `noroshi`'s
`active_alignment.PERMITTED_USES` and `iwakura`/`nusa`'s `:class`
fields use for their own domain invariants.

### Decision 3: RWA is an honestly-scoped heuristic, not full Yen's k-shortest-paths

`apn.rwa/alternate-paths` finds the shortest path via Dijkstra, then
generates additional candidates by excluding one link of that path at
a time (a single-link-deviation heuristic) — explicitly documented as
NOT a full k-shortest-loopless-paths algorithm. This follows this
workspace's sourcing-honesty discipline (`noroshi`'s G10; every
sibling `*.facts` namespace's honest-coverage-reporting convention):
claim exactly what the algorithm does, not more.

### Decision 4: `cloud-itonami-isic-6110` mirrors `cloud-itonami-isic-6190` op-for-op, with two deliberate domain-specific deviations

Five ops (`:demand/intake`, `:license/verify`, `:route/screen`,
`:actuation/provision-lightpath`, `:actuation/teardown-lightpath`),
six governor checks (spec-basis, evidence-incomplete,
route-endpoints-invalid, capacity-blocked, two double-actuation
guards), Phase 0→3 rollout with actuation permanently excluded from
every phase's `:auto` set — the same shape `telecom.governor`/
`telecom.phase` establish. Two checks were deliberately NOT copied
verbatim (see the child repo's own `docs/adr/0001-architecture.md`
Decisions 2-3 for the full reasoning):

- `route-endpoints-missing?` needs one topology lookup (unlike
  `e164-invalid-format?`'s zero-lookup pure field check), because
  "does this node exist" is a property of the live network, not of the
  demand record alone.
- `capacity-blocked-violations` is scoped to the PROVISIONING side
  (`:route/screen`/`:actuation/provision-lightpath`), not the teardown
  side — the inverse of `telecom.governor`'s scoping (billing-dispute
  gates SUPPRESSION) — because releasing an active lightpath never
  needs a capacity check, only activating a new one does.

### Decision 5: `MemStore` only for R0 — an honestly-recorded coverage gap

Unlike `telecom.store` (which ships `MemStore` + a `DatomicStore`
proven to satisfy the same contract), `netops.store` ships `MemStore`
only. The `Store` protocol is written so a `DatomicStore` is additive
later, but shipping and proving a second backend was deliberately
deferred rather than shipped unverified — recorded explicitly in the
namespace's own docstring, not silently.

### Decision 6: registry.edn's pre-existing "6110" entry is corrected in place, not duplicated

`kotoba-lang/industry`'s `registry.edn` already had an `:id "6110"`
entry, but under a stale ISIC-Rev.4 J-prefix scheme
(`gftdcojp/cloud-itonami-J6110`, `:maturity :spec` — a registry
placeholder whose repo was never built; `kotoba.industry/by-id` builds
its lookup via `(into {} (map (juxt :id identity) ...))`, so a second
`{:id "6110" ...}` map would silently shadow one entry depending on
vector order). Since the placeholder repo never existed, this ADR
edits that entry in place — `:repo`/`:business-id` corrected to the
ISIC-Rev.5 `cloud-itonami/cloud-itonami-isic-6110` naming every other
implemented actor in this fleet already uses, `:maturity :spec ->
:implemented`, `:required-technologies`/`:optional-technologies`
aligned to the actor's own `blueprint.edn` — the same in-place
promotion mechanic `cloud-itonami-isic-6190`'s own ADR-0001 Decision
10 used for its `blueprint.edn` field-sync fixes.

## Verification

- `kotoba-lang/apn`: `clojure -M:test` — 29 tests / 72 assertions, 0
  failures. `clojure -M:lint` — 0 errors/warnings. Pushed to
  `github.com/kotoba-lang/apn` (public).
- `cloud-itonami-isic-6110`: `clojure -M:dev:test` — 33 tests / 95
  assertions, 0 failures. `clojure -M:dev:run` demo verified end-to-end
  (the RWA solver picked the cheaper 450km tokyo-nagoya-osaka path over
  the 515km direct link unprompted; all four HARD-hold cases fired as
  designed: `:no-spec-basis`, `:route-endpoints-invalid`,
  `:capacity-blocked`, `:already-provisioned`/`:already-torn-down`).
  `clojure -M:dev:lint` — 0 errors/warnings. Pushed to
  `github.com/cloud-itonami/cloud-itonami-isic-6110` (public).
- `manifest/repos.edn`/`manifest/west.yml`: `apn` registered via
  `--entry apn` minimal diff, pin-verified (`verify-west-pins: 1 件の
  pin 変更をすべて検証 OK`), landed via GitHub API server-side merge
  (`repos.edn`'s `:manifest-workflow` canonical path).
- `kotoba-lang/industry`'s `registry.edn`: `clojure -M:test` — 7 tests
  / 118 assertions, 0 failures (`maturity-summary`'s hardcoded
  `:implemented` count updated 70→71 alongside the promotion). Landed
  via GitHub API server-side merge.

## Consequences

- Closes the gap the user's question identified: `kotoba-lang` now has
  a genuine APN topology/RWA library, and `cloud-itonami` has a
  network-operator actor consuming it — both real, tested code, not
  blueprint-only placeholders.
- Confirms the "wrap a pure domain-computation library, don't
  reimplement its algorithm inline" pattern: the Network Advisor and
  Network Provisioning Governor both call into `kotoba-lang/apn`
  rather than reimplementing routing/wavelength logic themselves —
  the same capability-layer split `telecom`/`kotoba-lang/phone`
  established, now demonstrated for a genuinely algorithmic (not just
  structural-validation) capability.
- A real editing-workflow lesson, not just a code lesson: the first
  attempt to register `kotoba-lang/apn` in `manifest/repos.edn` was
  made by editing the shared superproject checkout directly, and the
  uncommitted edit was silently lost to a concurrent session's
  `git merge --ff-only origin/main` in that same directory — the exact
  failure mode root CLAUDE.md's "並行エージェント運用" section warns
  about. Both manifest changes (`repos.edn`/`west.yml` and
  `registry.edn`) were redone via an external `git worktree` + feature
  branch + GitHub API server-side merge, and two more shallow-clone
  `unrelated histories` false positives were hit and correctly
  resolved via `git fetch --deepen` per the documented procedure,
  rather than assumed to be real force-pushes.
- `netops.store`'s `MemStore`-only scope (Decision 5) is a known,
  documented gap; a `DatomicStore` follow-up is additive when
  undertaken.

## Alternatives considered

- **Building the physical-layer link-budget math (OSNR, amplifier
  placement) into `apn` itself.** Rejected: that is `noroshi`'s
  explicit domain (photonics-electronics-convergence comms chip),
  and duplicating it in a network-topology library would blur the
  "chip vs. network" layer boundary the two libraries' READMEs both
  now document explicitly.
- **A full Yen's k-shortest-paths implementation in `apn.rwa`.**
  Rejected for R0 in favor of an honestly-scoped, tested
  single-link-deviation heuristic — see Decision 3.
- **Appending a second `{:id "6110" ...}` map to `registry.edn`
  instead of editing the existing entry.** Rejected: `kotoba.
  industry/by-id`'s `into {}` construction would silently shadow one
  of the two same-id entries depending on vector order — a structural
  bug, not a cosmetic duplicate.

## References

- `orgs/kotoba-lang/apn/README.md`
- `orgs/cloud-itonami/cloud-itonami-isic-6110/README.md`
- `orgs/cloud-itonami/cloud-itonami-isic-6110/docs/adr/0001-architecture.md`
- `orgs/etzhayyim/root/90-docs/adr/2606051600-noroshi-photonic-electronic-convergence-comms-chip-isac.md`
