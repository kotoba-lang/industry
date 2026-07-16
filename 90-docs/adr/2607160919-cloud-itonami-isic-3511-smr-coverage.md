# ADR-2607160919: `cloud-itonami-isic-3511` (Small Modular Reactor generation-operator compliance actor) — Top-10 item #6 extension, third ISIC-35 vertical

- Status: Accepted (2026-07-16)
- Related: `cloud-itonami-isic-3510` (grid distribution, ADR-2607142400,
  this fleet's first always-un-overridable HARD check precedent),
  `cloud-itonami-isic-3512` (community renewable generation + storage,
  `energy.registry`/`energy.facts` module-shape template),
  `cloud-itonami-isic-0520` (Lignite Mining Operations Coordination,
  ADR-2607152300, closed-allowlist/scope-exclusion module-shape
  template, most recent verified-redo quality-bar precedent),
  `cloud-itonami-isic-0510` (Hard Coal Mining Operations Coordination,
  ADR-2607152100, string-vs-keyword site-directory bug-class
  precedent), ADR-2607121000 (ISIC/ISCO global reverse-toposort wave
  plan, this build's own explicit Top-10 value-ranking item #6)

## Context

`orgs/kotoba-lang/industry` holds a 646+-entry ISIC registry with a
formal value-ranked rollout plan (ADR-2607121000); item #6 of its
Top-10 value ranking is "3510/3512 電力" (electric power). ISIC Rev.5
group 351 (electric power) actually splits into 3511 (non-renewable
generation) / 3512 (renewable generation) / 3513 (transmission) / 3514
(distribution) / 3515 (trade) / 3516 (storage), per the Swiss KUBB
NOGA-2025/ISIC-Rev.5 coding tool
(`https://www.kubb-tool.bfs.admin.ch/en/noga/2025/351`).
`cloud-itonami-isic-3510` (already built, scoped to grid distribution)
and `cloud-itonami-isic-3512` (already built, scoped to community
renewable generation + storage) exist; **3511 did not exist yet**.

The owner explicitly asked, after being shown this roadmap, to add a
Small Modular Reactor (SMR) nuclear-generation-operator blueprint under
3511 as a **formal roadmap extension of Top-10 item #6** — this build
implements that request and records it here as a proper ADR, matching
this fleet's own convention of one ADR per actor addition.

**Quality-bar context**: `90-docs/adr/2607152300-cloud-itonami-isic-0520-lignite-mining-coverage.md`
documents that earlier the same day, an 18-agent haiku batch produced
a 61% defect rate on this exact kind of task (empty implementations,
missing modules, false "all tests green" self-reports). This build
follows that incident's post-mortem guardrails: small batch (one
actor), verified-redo discipline, and do not trust self-report — every
test/lint number in this ADR's Verification Notes was actually run in
this session and its literal raw output pasted below, not paraphrased.
`kotoba-lang/industry`'s registry is being concurrently promoted by
multiple other sessions today; the maturity count below was
live-recomputed via `(kotoba.industry/maturity-summary)` immediately
before each write, per that repo's own test-suite discipline.

## Decision

### Decision 1: roadmap-priority extension, not a rogue insertion

This build extends ADR-2607121000's own explicit Top-10 value-ranking
item #6 ("3510/3512 電力") to cover all three of 3510/3511/3512.
Rationale for SMR specifically: Small Modular Reactors are a fast-
growing generation segment with a heavy licensing/compliance burden
relative to plant size — multiple jurisdictions (Japan/NRA, USA/NRC,
UK/ONR — see `smrops.facts`) actively run SMR-specific licensing or
design-assessment programs. This is a natural fit for this fleet's
market-entry-compliance moat thesis (the same iso3166×223-country
compliance-layer pattern ADR-2607121000 names for item #4, 法人設立×
compliance).

### Decision 2: governance/compliance software actor, NOT reactor engineering

Like every sibling in this fleet, `cloud-itonami-isic-3511` is an
independent-Governor-gated LLM advisor + append-only audit ledger for
an SMR generation OPERATOR business — it is NOT reactor physics/
engineering, NOT a control system, and NEVER performs or authorizes an
actual reactor-safety-critical action. ISIC 3511 as a class also
covers coal/gas/oil generation; this repo's honest R0 scope is the
nuclear/SMR operator niche only, documented in README `Business-
process coverage` exactly as `cloud-itonami-isic-3510`'s own ADR did
for its narrower-than-the-class customer/meter-level distribution
framing.

### Decision 3: closed five-op proposal allowlist, all `:effect :propose`

Mirroring `cloud-itonami-isic-0520`'s (ligniteops) verified
coordination-only shape rather than `cloud-itonami-isic-3512`'s
(energy) dual-actuation shape, because this actor has NO real-world
actuation event at all:

- `:log-safety-inspection-record` — routine safety/maintenance
  inspection record logging
- `:draft-licensing-submission` — siting/licensing/regulatory-filing
  draft (NRC/NRA-style submission drafting — DRAFT ONLY, operator/
  counsel signs and files)
- `:log-fuel-custody-record` — fresh/spent fuel or waste chain-of-
  custody record logging
- `:draft-community-benefit-report` — community-benefit/public-
  disclosure report drafting
- `:flag-safety-concern` — surface an observed safety/security
  concern — ALWAYS escalates, regardless of confidence

### Decision 4: five HARD governor checks, the fifth stronger than the rest

1. **Facility unverified** — target SMR-facility record must exist AND
   be independently `:registered?`/`:verified?` in the store.
2. **Effect not `:propose`** — any other value is a claim to directly
   actuate outside governance.
3. **Spec-basis fabrication** — a `:draft-licensing-submission` with no
   citation to an official regulator source (`smrops.facts`, seeded
   JPN/NRA · USA/NRC · GBR/ONR) is rejected, mirroring
   `cloud-itonami-isic-3512`'s `energy.governor/spec-basis-violations`.
4. **Scope exclusion** (permanent, un-overridable, substring-scanned,
   English + Japanese term list, mirroring `ligniteops.governor`'s
   qualified-phrase approach so it never collides with legitimate
   safety-concern flagging): control-rod operation / reactor trip or
   scram decision / criticality safety determination / radiological
   release or dose authorization / containment integrity override /
   emergency evacuation order / fuel loading or refueling sequencing /
   security-force response decision (制御棒操作 / 原子炉停止・スクラム
   判断 / 臨界安全性判定 / 放射性物質放出・被ばく許可 / 格納容器健全性
   オーバーライド / 避難命令 / 燃料装荷・取替順序 / 警備隊対応決定).
   `legitimate-containment-monitoring-flag-is-not-scope-excluded`
   proves a legitimate `:flag-safety-concern` about an observed
   containment-monitoring anomaly is NOT scope-excluded.
5. **Absolute live-actuation request** — a NARROWER, SEPARATE,
   STRONGER check (see Decision 5).

### Decision 5: why the absolute check needs a SEPARATE, stronger enforcement than the scope-exclusion

`absolute-actuation-request-violations` fires only when a proposal's
own content is ITSELF an imperative request to authorize an actual
radiological-release/exposure decision or reactor-trip/scram decision
RIGHT NOW ("authorize immediate release", "grant scram authorization")
— not a record, draft or discussion of that territory (which check 4
already blocks). This is stronger because check 4's failure mode is
about a proposal's CURRENT quality/framing (a different, better-worded
resubmission could avoid it), while the absolute check's failure mode
is categorical: no resubmission can cure a proposal that is itself a
live actuation-authorization request, because the actor's charter
permanently excludes performing or authorizing that act regardless of
phrasing.

Following the same reasoning `cloud-itonami-isic-3510`'s own ADR
(90-docs/adr/2607142400) gives for why `protected-recipient-
violations` needed its own absolute, un-overridable-by-human-approval
treatment distinct from its other ordinary HARD checks: in this
fleet's existing StateGraph shape, EVERY HARD verdict already routes
straight to `:hold` and structurally never reaches `:request-
approval`, so literal "human override" isn't the differentiator any
HARD check can uniquely claim. The genuinely new, stronger guarantee
this build adds is a SECOND, INDEPENDENT enforcement point:
`smrops.operation`'s `:commit` node — the one node that actually
writes the SSoT — independently re-derives `governor/absolute-
actuation-request?` directly from the proposal and refuses to write if
it fires, regardless of what `:record`/`:disposition` an
(hypothetically buggy) upstream `:decide` computed. This is belt-and-
suspenders specifically for the one failure mode this actor's charter
can never tolerate under any circumstance — a latent wiring bug, a
future refactor adding a new path into `:commit`, or a compromised
advisor racing a legitimate approval.
`commit-node-independently-re-blocks-absolute-actuation-content`
(`test/smrops/governor_contract_test.clj`) exercises this directly by
calling `smrops.operation/commit-node` with a `:record` that would
otherwise commit, proving the redundant check is real, not decorative.

### Decision 6: other HARD checks, module shape, and the string-key bug class avoided

Facility/site unverified and spec-basis fabrication above match this
fleet's "ground truth, not self-report" and "no fabrication"
disciplines exactly. Module shape: `smrops.facts` (official-source
citation registry, mirroring `energy.facts`'s honesty discipline),
`smrops.registry` (licensing-submission-draft + fuel-custody-record
construction, unsigned certificates, jurisdiction-scoped reference
numbers, mirroring `energy.registry`'s dual-record shape but adapted
from actuation records to DRAFT records), `smrops.store` (MemStore,
**string-keyed** SMR-facility directory — `ADR-2607152100` documents a
site-directory string-vs-keyword bug class explicitly avoided here
from the start, matching `ligniteops.store`'s own fix), `smrops.
advisor` (SmrOpsAdvisor: mock + a real-LLM seam via `langchain.model`,
plus TWO distinct test hooks — `:out-of-scope?` for the broad scope-
exclusion check and `:absolute-actuation-test?` for the narrower
absolute check, deliberately separate so each failure mode is
exercised end-to-end independently), `smrops.governor` (all checks
above, priority-ordered), `smrops.phase` (0→3 rollout table;
`:flag-safety-concern` and the excluded/absolute territory are never
in any phase's `:auto` set), `smrops.operation` (a `langgraph-clj`
StateGraph: intake → advise → govern → decide → commit | hold |
request-approval, with the Decision 5 redundant check at `:commit`),
`smrops.sim` (demo driver, `clojure -M:run`, walks a clean auto-
committing case, the always-escalating safety-concern flag, and all
HARD-hold scenarios including the absolute radiological-release/
scram-authorization block).

### Decision 7: `kotoba-lang/industry` registry + manifest registration

`"3511"` added to `resources/kotoba/industry/registry.edn`
(`:repo cloud-itonami/cloud-itonami-isic-3511`, `:business-id
"cloud-itonami-isic-3511"` matching the corrected non-stale convention
`cloud-itonami-isic-3512` already uses — NOT `3510`'s own stale
`"cloud-itonami-3510"` pattern its ADR separately documents —
`:maturity :implemented`). `test/kotoba/industry_test.clj` /
`industry_wave_test.clj` updated the same way the most recent prior
addition did (live-recomputed count via `(industry/maturity-summary)`
immediately before each edit, not a hardcoded assumed number — other
sessions were concurrently promoting entries throughout this build).
Landed via the fleet's established GitHub-API server-side pattern:
registry.edn via a sha-checked Contents-API single-file PUT (this
file's own established single-line-file workflow, avoiding a textual
3-way merge on a single massive line), test files via branch + `gh api
repos/.../merges` server-side merge (no local rebase, no force-push).
`manifest/repos.edn`'s `:manifest.repos/extra-projects` set gained
`"orgs/cloud-itonami/cloud-itonami-isic-3511"` (single-element
addition, diff-verified via a full old/new EDN structural compare
before the PUT), and `manifest/west.yml` gained the corresponding
project entry via `nbb scripts/gen-west-manifest.cljs --entry
cloud-itonami-isic-3511` (server-side pin verification passed: "新規
entry, pin c5259d891e2c は main から到達可能"), confirmed canonical via
`--check`.

## Alternatives considered

| Option | Verdict | Reason |
|---|---|---|
| Fold the absolute live-actuation-request block into the same `scope-exclusion-violations` rule/enforcement point | ❌ | Would lose both the conceptual distinction (discussing/drafting vs. imperatively requesting) and the redundant `:commit`-node enforcement point this ADR's Decision 5 requires; the task's own framing requires this check to be demonstrably stronger, which a single shared enforcement point cannot show in code, only in a docstring |
| Give this actor a dual-actuation shape like `cloud-itonami-isic-3512` (energy), with `:actuation/*` ops that always escalate | ❌ | This actor genuinely has no real-world actuation event — every op is a draft or a log entry. Inventing an actuation op to match the sibling shape would misrepresent the actor's actual capability surface, contradicting this fleet's `smrops.facts`-style honesty discipline |
| Re-derive the absolute check only at `:govern`, relying on `:decide`'s phase-gate wiring to keep it from ever reaching `:commit` | ❌ | Exactly the single-point-of-failure shape every OTHER HARD check in this actor already has; provides no code-level distinction from an ordinary HARD check |
| Single `:out-of-scope?` advisor test hook covering both the broad scope-exclusion and the absolute live-actuation-request failure modes | ❌ | The two checks are structurally distinct; a single hook could not prove each is independently reachable and testable, and risks one phrase list shadowing coverage of the other |
| Cover conventional/fossil (coal/gas/oil) generation under ISIC 3511 in this same repo, matching the class's full scope | ❌ | Out of scope for this R0 — this build's mandate is specifically the SMR/nuclear operator niche; honestly scoped per README `Business-process coverage`, matching `cloud-itonami-isic-3510`'s own narrower-than-the-class precedent |
| `smrops.registry` producing SIGNED certificates for licensing-submission drafts | ❌ | Every record this actor produces is a DRAFT; signing is the operator's/counsel's own act (see child-repo README `Actuation`). A signed certificate would misrepresent an unfiled draft as an authoritative filing |

## Consequences

- (+) Extends ADR-2607121000's Top-10 value-ranking item #6 to cover
  all three of ISIC 3510/3511/3512 in this fleet.
- (+) Introduces this fleet's first TWO-TIER excluded-territory design
  in one governor: a broad, permanent scope-exclusion check plus a
  narrower, separately-and-redundantly-enforced absolute-actuation-
  request check — a template other domains with an analogous
  "discussing X is merely excluded, but REQUESTING X live is
  categorically worse" concept may reuse.
- (+) Confirms `energy.registry`'s dual-record/unsigned-certificate
  shape and `ligniteops.governor`'s closed-allowlist/scope-exclusion
  shape compose cleanly into a THIRD ISIC-35 vertical with a genuinely
  different actuation profile (zero real actuations).
- (-) This R0 governs the SMR/nuclear compliance-recordkeeping slice
  only — no conventional/fossil generation, no reactor-control-system
  integration, no real regulator filing integration; see child-repo
  README `Business-process coverage` for the full honest-scope
  accounting.
- Fleet-wide `kotoba-lang/industry` `:implemented` count: 377 → 378
  (live-recomputed via `(kotoba.industry/maturity-summary)`
  immediately before the registry PUT; a fresh post-merge clone
  re-verified 378 `:implemented` with 649 total entries).

## References

- `cloud-itonami-isic-3511/docs/adr/0001-architecture.md` (the
  authoritative child-repo architecture record, full Decision/
  Alternatives detail)
- `cloud-itonami-isic-3512/docs/adr/0001-architecture.md`
  (`energy.registry`/`energy.facts` module-shape template)
- `cloud-itonami-isic-0520/docs/adr/` (ADR-2607152300, closed-
  allowlist/scope-exclusion module-shape template, most recent
  verified-redo precedent)
- `cloud-itonami-isic-0510/docs/adr/` (ADR-2607152100, string-vs-
  keyword site-directory bug-class precedent avoided here from the
  start)
- `90-docs/adr/2607142400-cloud-itonami-isic-3510-electric-power-coverage.md`
  (`protected-recipient-violations`, this fleet's first
  always-un-overridable HARD check — the closest structural precedent
  for this repo's own absolute check)
- ADR-2607121000 (ISIC/ISCO global reverse-toposort wave plan, Top-10
  item #6)
- `kotoba-lang/industry` `resources/kotoba/industry/registry.edn`
  `"3511"` entry
- Swiss KUBB NOGA-2025/ISIC-Rev.5 coding tool,
  `https://www.kubb-tool.bfs.admin.ch/en/noga/2025/351` (confirms the
  351 group split into 3511-3516)

## Verification Notes

- `cloud-itonami-isic-3511`: pushed to `main`, commit
  `c5259d891e2c2257ec250d56221c8763aa619236`. `clojure -M:test`
  (verified both at push time and again from a fresh clone):
  **`Ran 76 tests containing 247 assertions. 0 failures, 0 errors.`**
  `clojure -M:lint` (fresh clone): **`linting took 649ms, errors: 0,
  warnings: 0`**. `clojure -M:run` (`smrops.sim` demo) walked all
  scenarios (clean auto-commit at phase 3, phase-1 approval
  escalation, the always-escalating safety-concern flag, and all
  HARD-hold cases — unregistered facility, unverified facility,
  non-`:propose` effect, no-spec-basis licensing submission, scope-
  excluded content, and the absolute live-actuation-request block at
  BOTH `:govern` and the redundant `:commit`-node re-check) without
  error.
- `kotoba-lang/industry`: registry.edn PUT commit
  `62eeb4eabeadbbd87c29fdc15dd5a77488a7b94c`; test-file corroboration
  merge commit `5463069e11749e06ef01bec84657d57b627e9938` (2-parent
  GitHub API server-side merge, branch `chore/register-isic-3511-smr`
  deleted after merge). `clojure -M:test` (fresh post-merge clone):
  **`Ran 15 tests containing 1043 assertions. 0 failures, 0 errors.`**
  `clojure -M:lint` (fresh post-merge clone): **`linting took 471ms,
  errors: 0, warnings: 0`**.
- Superproject `manifest/repos.edn` PUT commit
  `49dfde506d2e71b3863a4ee0110f2b543e6b2bee` (single-element
  `:extra-projects` addition, diff-verified). `nbb scripts/gen-west-
  manifest.cljs --entry cloud-itonami-isic-3511`: server-side pin
  verification passed (`OK cloud-itonami-isic-3511
  (cloud-itonami/cloud-itonami-isic-3511): 新規 entry, pin
  c5259d891e2c は main から到達可能`), wrote a 5-line single-entry
  diff to `manifest/west.yml`. `nbb scripts/gen-west-manifest.cljs
  --check`: **`west.yml is up to date.`**
