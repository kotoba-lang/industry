# ADR-2607111000: cybercrime/crypto-theft victim-support — where it lives (etzhayyim actor cluster vs. cloud-itonami venture front)

**Status**: accepted
**Date**: 2026-07-10
**Deciders**: Jun Kawasaki (+ Claude, オーナー承認のうえ実行)

## Context

The owner asked whether an actor exists to handle cybercrime/crypto-asset-
theft victim response — requesting exchange freeze, filing with law
enforcement across jurisdictions, registering into a "yabai" registry,
flagging blockchain addresses — and where it should be designed:
`etzhayyim`, `cloud-itonami` (gftdcojp), or `kotoba-lang`.

A cross-repo survey found the capability already split across a
cross-linked cluster of `etzhayyim/root` actors, none of them
`etzhayyim-yabai` (that repo is an AML/sanctions risk-scoring feed, not a
victim-case registry):

| Actor | Covers |
|---|---|
| 助 tasuke (`20-actors/tasuke`, ADR-2606060900) | JP-domestic victim filing drafts: police report, bank/platform freeze & chargeback request. Free, self-submit only. |
| 辿 tadori (`20-actors/tadori`) | On-chain trace + address/cluster flagging (mixer/CEX/bridge/scam/sanctioned), OFAC SDN ingestion. Evidence-only — never files or freezes itself. |
| malak (`60-apps`) | Cross-jurisdiction law-enforcement referral: `CyberCrimeCase`/`InterpolNotice`/`AgencyReferral`, 16+ agencies (FBI IC3, NCA, BKA, AFP, RCMP, 警察庁, INTERPOL/Europol/FATF). |
| crypto-asset-freeze (`60-apps`, T2) | Coordinates the actual exchange freeze request (`requestFreeze`/`createIncident`/`traceWallet`), caller-DID-gated to law enforcement. |

`kotoba-lang` has only low-level substrate (`wallet`, `btc-crypto`,
`eth-crypto`, `chain`, `internet-intel`) — no domain/actor logic, correctly
so per its `:language-substrate :platform` org scope.

The owner then asked whether `cloud-itonami` (gftdcojp, `:human-centric
:business` per `manifest/repos.edn`) could also host this, since it
already runs government/administrative-procedure workflows (the
`:procedure` lane, `company.cljc`'s gftdcojp/gftdjapan operating set). On
inspection that lane is **B2G for gftdcojp's own corporate entity**, not a
third-party victim-facing service, and its approval model is an internal
employee approval-inbox — a structural mismatch with the LE-DID-gated
governance the etzhayyim actor cluster's governed actions need. The owner
then clarified the actual ask: `cloud-itonami` hosting **non-profit
activity** specifically, which is a different, better-fitting question —
`cloud-itonami`'s ADR-0010 venture-portfolio model (Business Model Canvas
+ Lean cycle + a structurally non-equity contribution/distribution funding
ledger) is exactly the shape a donation-funded victim-support front needs.

## Decision

Split the responsibility along the same "advisor proposes, independent
governor decides" line this superproject already uses everywhere:

1. **The governed actions stay in `etzhayyim`, unchanged.** Exchange
   freeze coordination, cross-jurisdiction agency filing, and on-chain
   trace/flagging are not duplicated anywhere else. `etzhayyim`'s
   `:agent-centric :public-interest` org scope is the correct home for
   LE-authorized, human(law-enforcement)-gated actions.
2. **A new non-profit business/ops/funding front, `gftdcojp/cloud-mamori`,
   is registered as `cloud-itonami`'s 11th portfolio venture** (ADR-0010 —
   `itonami-cli/ventures`, an ordinary `:itonami.repo/*`, ADR-0002 tenant
   isolation, no new concept). Its own repo owns victim intake and
   referral-drafting only (`cloud-mamori.case`: `intake!`/`refer!`/
   `mark-submitted!`), producing ready-to-submit referral drafts toward one
   of the four etzhayyim actors above — it cannot invoke any of them
   itself, only name which one a human takes the case to next. Funding
   (donations/grants) rides `cloud-itonami`'s existing non-equity ledger;
   no new funding mechanism was written.
3. **No new actor pattern was invented.** `cloud-mamori` needs no governor
   of its own because it never executes a governed action — the only
   state transition it allows (`:pending-approval` → `:submitted`) is a
   human bookkeeping step, not an autonomous effect.

## Consequences

- (+) Zero duplication of etzhayyim's actor/governor logic; the
  containment+governor+ledger pattern stays exactly where the LE/cross-
  jurisdiction authorization actually lives.
- (+) Non-profit funding gets ADR-0010's already-legally-reviewed
  non-equity ledger for free.
- (+) Clear placement rule for future similar questions: host the
  *governed action* wherever its governance shape (containment + the
  right independent authority) already fits; host the *business/ops/
  funding front* in `cloud-itonami`'s venture portfolio if it's gftdcojp
  non-profit/for-profit activity, regardless of what the underlying
  governed action's domain is.
- (−) No live integration with etzhayyim's actor endpoints exists yet
  (none were confirmed to have a public API in this session's research) —
  referrals are hand-carried by a human today. Wiring a real API call is
  gated on those endpoints actually existing.
- (−) This ADR does not certify legal compliance for operating a
  victim-support charity/NGO in any jurisdiction — a business/legal
  decision outside its scope, same caveat ADR-0010 makes for the funding
  ledger generally.
- Manifest registration: `orgs/gftdcojp/cloud-mamori` added to
  `manifest/repos.edn`'s `:manifest.repos/extra-projects` and to
  `manifest/west.yml` (single-entry GitHub API commits, per
  `repos.edn` `:manifest-workflow`). `cloud-itonami`'s own west.yml pin
  was advanced in the same pass (clean fast-forward, `compare` API
  verified `ahead_by=6 behind_by=0`) since this task's venture-
  registration change had just moved its real `main` tip.

## References

- `gftdcojp/cloud-mamori` — new repo, `docs/adr/0001-architecture.md`.
- `gftdcojp/cloud-itonami` ADR-0019 (`docs/adr/0019-cloud-mamori-victim-support-venture.md`)
  — 11th portfolio venture registration.
- `gftdcojp/cloud-itonami` ADR-0010 (venture-as-repo model, non-equity
  funding ledger) and ADR-0002 (tenant isolation).
- `etzhayyim/root` — 助 tasuke, 辿 tadori, malak, crypto-asset-freeze (the
  actor cluster this venture refers into, unchanged).
- `manifest/repos.edn` `:manifest.repos/orgs` (etzhayyim
  `:agent-centric :public-interest`; gftdcojp `:human-centric :business`)
  and `:manifest-workflow` (single-entry API commit path this ADR's
  manifest changes followed).
