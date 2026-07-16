# ADR-2607101900: `kotoba-lang/standard-model` — GTG position-gauge sector (Phase 0b), curvature quadratic invariant (Phase 0c), and a field-strength antisymmetry fix found by an independent review loop

## Status

Accepted and landed. Direct continuation of ADR-2607101700 (Phase 0a rotation-gauge
sector + the non-compact structure-constants generalization). This ADR covers four
more merged PRs produced by a self-paced generate → verify → adversarially-review →
fix loop, run over `kotoba-lang/standard-model` on 2026-07-09.

**Landed 2026-07-09**, in order:

- [`kotoba-lang/standard-model#3`](https://github.com/kotoba-lang/standard-model/pull/3)
  merged as `9689fb877a961611a44d171b6614e45ca2f9c163` — Phase 0b, the position gauge
  field $h_\mu$ and derived metric.
- [`kotoba-lang/standard-model#4`](https://github.com/kotoba-lang/standard-model/pull/4)
  merged as `cc10dcb6a571451f20a5f328b7ca9d7aff0d8d62` — edge-case test coverage
  (test-only), which surfaced a real correctness gap in
  `kotoba.sm.gauge/field-strength`.
- [`kotoba-lang/standard-model#5`](https://github.com/kotoba-lang/standard-model/pull/5)
  merged as `9e94650961ef1792a46c77a3fb8bc307b29f64db` — Phase 0c, a curvature
  quadratic invariant (deliberately not the true Lasenby-Doran-Gull curvature
  scalar; see Decision).
- [`kotoba-lang/standard-model#6`](https://github.com/kotoba-lang/standard-model/pull/6)
  merged as `6ed2ca0d9a881d8a8126328001dabb2bc394ff2a` — the production-code fix for
  #4's finding, root-caused by a dedicated independent adversarial review pass (not
  itself a PR) before being implemented.

Superproject `manifest/west.yml` pin advanced through each of these in turn via the
sanctioned GitHub API single-entry commit path (final state:
`6ed2ca0d9a881d8a8126328001dabb2bc394ff2a`, superproject commit
`b4300522aa098f8d0d963e1542c201e114441483`).

## Context

This work was produced by a `/loop`-driven, self-paced session running against
`kotoba-lang/standard-model`, continuing directly from ADR-2607101700's Phase 0a.
Each iteration followed the same discipline established there: implement in an
isolated scratch clone, independently re-run tests before trusting an
implementing-agent's self-report, push a branch, open a PR with CI required to be
green, and only then merge and advance the manifest pin. Four things happened in
this pass worth recording as a single coherent story rather than four disconnected
line items:

1. **Phase 0b (position gauge)** was a comparatively low-risk, well-scoped addition:
   a position gauge field $h_\mu(x)$ (a 4×4 real matrix, row $\mu$ = the physical
   four-vector $h_\mu$) and a derived metric $g_{\mu\nu}=h_\mu\cdot h_\nu$, reusing
   `kotoba.sm.tensor/dot` unmodified. No matrix inverse was implemented — deliberately
   deferred (see Consequences).

2. **Hardening test coverage (PR #4)**, which was *supposed* to be a routine,
   low-risk addition of edge-case tests (antisymmetry, linearity, guard conditions)
   around already-shipped code, **found a real bug**: `kotoba.sm.gauge/field-strength`'s
   self-interaction term $g f^{abc}A_\mu^bA_\nu^c$ is only antisymmetric under
   $\mu\leftrightarrow\nu$ when the excited gauge-field components share the same
   trace-Gram sign — true automatically for the compact SU(2)/SU(3) case (uniform
   $K^{DD}=\tfrac12$) but false for $so(1,3)$'s mixed boost/rotation-type components
   (ADR-2607101700's $K^{DD}=\mp1$ finding). This is exactly the kind of thing
   coverage-hardening passes are *for* — it was recorded honestly as a passing test
   documenting the current (broken) behavior, per this repo's established practice,
   rather than silently patched or ignored, and a dedicated follow-up was filed
   instead of rushing a fix inline.

3. **Phase 0c (curvature quadratic invariant)** was deliberately scoped *narrower*
   than "the GTG curvature scalar." Lasenby-Doran-Gull's true curvature scalar
   $R=h^\mu\wedge h^\nu\cdot R(\bar h_\nu,\bar h_\mu)$ is *linear* in the curvature
   bivector $R_{\mu\nu}$ (already implemented, Phase 0a); what's implemented here,
   $I=\sum_k K^{kk}(R_{\mu\nu}^kR^{k\,\mu\nu})$, is instead *quadratic* — the same
   relationship the Kretschmann scalar $R_{abcd}R^{abcd}$ has to the Riemann tensor
   in ordinary GR. The implementing agent explicitly declined to guess at the true
   linear contraction's index-pairing convention without an independent literature
   value to check it against, and filed that as a named follow-up (task tracked,
   ADR Consequences below) rather than risk a plausible-looking but silently wrong
   result. This is the same honesty discipline as ADR-2607101700's structure-constant
   finding, applied prospectively this time instead of after the fact.

4. **An independent adversarial review** (a dedicated task, not a PR — no code
   changed) was run specifically to stress-test PR #1/#2's claims before building
   further on top of them: a fresh pass re-derived the $so(1,3)$ commutation
   relations, the Gram-matrix signs, and the generalized structure-constant formula
   from the physics literature (Peskin & Schroeder) *before* reading the
   implementation, then checked for divergence. It found the existing algebra
   correct, but went further than PR #4's finding: it identified the **exact root
   cause** of the antisymmetry bug — `self-interaction-term` indexed the
   structure-constants array as `f-abc[a][b][c]` (output index first) where
   `structure-constants`' own definition, $[T_A,T_B]=if^{ABD}T_D$, puts the output/
   target index **third** — and verified the fix (swap to `f-abc[b][c][a]`) three
   independent ways (re-indexed reimplementation, direct matrix ground truth via
   $-ig[A_0,A_1]$, and bit-identical SU(2) regression) before any production code
   was touched. PR #6 then implemented exactly that fix.

## Decision

Continue `kotoba.sm.gtg` (the sole namespace all of this lands in, alongside
`kotoba.sm.gauge`) with:

- **Phase 0b**: `position-gauge-identity`, `derived-metric`, `derived-metric-field`,
  `derived-metric-matches-flat?`. Flat-limit check is exact integer equality (not
  epsilon-close) against `kotoba.sm.tensor/metric`. Consistency with the pre-existing
  global $SO(3,1)^+$ representation (`kotoba.sm.vector-field`'s boosts/rotations) is
  proven algebraically ($\Lambda^T\eta\Lambda=\eta \Rightarrow \Lambda\eta\Lambda^T=\eta$)
  and checked numerically against 7 concrete transformations.

- **Phase 0c**: `curvature-quadratic-invariant`, `curvature-bivector-component`,
  scoped to $h=$ `position-gauge-identity` only (the one case needing no matrix
  inverse). Explicitly documented, in both the function docstring and the namespace
  docstring, as *not* the LDG curvature scalar — a quadratic invariant of the same
  family as the Kretschmann scalar, not the linear Ricci-scalar analogue. Not
  guaranteed non-negative (the Gram matrix $K$ is indefinite).

- **The `kotoba.sm.gauge/field-strength` fix**: `self-interaction-term`'s structure-
  constant lookup corrected from `f-abc[a][b][c]` to `f-abc[b][c][a]`. Proven
  behavior-preserving for every compact generator set in this repo (SU(2)/SU(3)/U(1))
  via an independent-oracle regression test reimplementing the old slot order and
  comparing bit-for-bit; proven to restore $so(1,3)$ antisymmetry via PR #4's own
  counterexample (values flip from equal, `+0.35`/`+0.35`, to negated,
  `-0.35`/`+0.35`) plus 3 additional mixed boost/rotation excitation patterns.

Test suite grew from 22 tests / 85 assertions (ADR-2607051500's original landing)
through 30/428 (Phase 0a, ADR-2607101700) to **47 tests / 2065 assertions** at the
end of this pass, CI green (JDK 17+21) at every merged step, zero regressions.

## Consequences

**In scope, implemented and merged:** exactly the items above.

**Explicitly out of scope, deferred (extends, does not replace, ADR-2607101700's
boundary):**

- A general (non-identity) position gauge field: the reciprocal frame $\bar h$
  requires a general $4\times4$ matrix inverse, not implemented anywhere in this
  codebase. Without it, `derived-metric` only has a literature-faithful
  interpretation at $h=$ identity or a constant Lorentz transformation.
- The true, linear Lasenby-Doran-Gull curvature scalar $R$ — needs the same matrix
  inverse plus a literature-verified index-pairing convention that this pass
  deliberately declined to guess at.
- The Einstein multivector, the GTG action principle and field equations, any proof
  of equivalence to General Relativity, and any dark-matter/dark-energy/de-Sitter
  extension — unchanged from ADR-2607101700.
- The combined $h_\mu+\Omega_\mu$ GTG covariant derivative (Phase 0a's spin
  connection and Phase 0b's derived metric remain developed independently of each
  other in this codebase).
- Path-integral quantization, renormalization/RGE, scattering amplitudes, lattice
  simulation — unchanged scope boundary from ADR-2607051500.

Do not read this ADR, any more than ADR-2607101700, as claiming gravity or
unification is implemented. It records: a position-gauge field and derived metric
faithful to GTG in the identity/global-Lorentz special case; a curvature invariant
honestly labeled as *not* the literature's curvature scalar; and a concrete
correctness bug plus its verified fix, surfaced by deliberately investing in
adversarial test coverage and independent review rather than only forward
implementation. The review-finds-a-real-bug-in-already-merged-code outcome here is
the intended product of that discipline, not a failure of it.
