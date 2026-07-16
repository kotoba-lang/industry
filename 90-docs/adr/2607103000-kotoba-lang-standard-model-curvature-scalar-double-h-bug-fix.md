# ADR-2607103000: `kotoba-lang/standard-model` — `curvature-scalar` double-applied $h$; fixed by the independent review the previous landing ADR called for

## Status

Accepted and landed. Follow-up to ADR-2607102700 (Phase 1 landing), acting on
the review that ADR's own methodological note anticipated needing.

**Landed 2026-07-09:**
[`kotoba-lang/standard-model#11`](https://github.com/kotoba-lang/standard-model/pull/11)
merged as `6296794a5eb5bec816e76994336685f453fc9012` — 77 tests / 2898
assertions, CI green (JDK 17+21, after one transient infrastructure retry —
see Consequences). Superproject `manifest/west.yml` pin advanced to this
commit via the sanctioned GitHub API single-entry commit
(`89edb0d773d9d40d089d2df2efcd6754b38c2e6b`).

## Context

ADR-2607102700 closed with an explicit methodological note: Phase 1's first
implementation attempt had already failed silently once (using the wrong
covariant derivative, producing identically-zero curvature by construction),
and was caught only because the verification standard was an independent
numeric match against a known closed-form result, not "does the code run."
That note said this was "not license to relax this discipline for whatever
comes next" — so, per this repo's now-established practice
(ADR-2607101700/1900's own precedent: land, then independently review), an
adversarial review of the just-merged Phase 1 code was commissioned
immediately following that landing.

**The review found a real, independently-confirmed bug in `curvature-scalar`
itself** — the headline result of Phase 1. `curvature-scalar` computed
$\gamma^a\cdot(\gamma^b\cdot R(h_b\wedge h_a))$, contracting the
**already-covariant** `riemann-basis-pair` (eq 4.48, which internally uses
$L_a=a\cdot\bar h(\nabla)$ — i.e. is already built *from* $h$) against a
bivector built from **position-gauge-field rows** $h_b\wedge h_a$, instead of
the fixed frame basis vectors $e_a\wedge e_b$ that LDG eq (4.11)/(4.12)
actually specify. Since `riemann-basis-pair` already *is* the covariant
$\mathcal{R}$ (LDG eq 4.9: $\mathcal{R}(B)=R(h(B))$ for a flat $R$ and basis
bivector $B$ — the $h$-dependence is baked into *how* the covariant map
computes its answer, not into *which* bivector it is fed), wedging
$h_b\wedge h_a$ on top applied $h$ a **second** time.

**Why the merged, CI-green, Schwarzschild-verified test suite didn't catch
this**: the existing regression test checked only the **scalar** (trace). In
LDG's own Schwarzschild solution, $h(e_1)=e_1$, $h(e_2)=e_2$, $h(e_3)=e_3$
exactly (fixed points of $h$), so the erroneous double application of $h$ is a
no-op on purely-spatial index pairs and only affects terms whose index is 0.
The resulting spurious, genuinely-asymmetric Ricci-**tensor** components
(magnitude $\sim10^{-4}$, roughly 2000× above this pipeline's documented
finite-difference noise floor of $\sim10^{-10}$) happened to cancel exactly in
this particular solution's *trace* — the only quantity the existing test
checked. **Independently reconfirmed by the requester** (not merely by
re-reading the reviewer's report): computing the full Ricci tensor both ways
in a fresh REPL session showed the buggy formula gives e.g.
$R_{10}\approx1.9\times10^{-4}$ alongside $R_{01}\approx9\times10^{-11}$ for
the same adjacent pair — a genuine, physically-impossible asymmetry (the Ricci
tensor must be symmetric) — while the fixed formula keeps every component at
the $\sim10^{-10}$ noise floor.

This is the **third** time this specific failure mode — a seemingly-thorough
single pass that is confidently, silently wrong, invisible to "tests pass" —
has occurred in this repo's GTG work, after PR #6 (structure-constant slot
order) and Phase 1's own first implementation attempt (wrong covariant
derivative). All three were caught only by independent numeric verification
against a ground truth stronger than "the code runs" or even "the scalar
matches."

## Decision

Fix `curvature-scalar` to call `riemann-basis-pair(b, a)` directly — no
`wedge-vectors`/general `riemann-map` call, since both indices are always
fixed basis vectors here, never position-gauge-field rows.

**The `(b, a)` argument order is disclosed as only algebraically, not
independently numerically, justified.** `riemann-basis-pair` is antisymmetric
($R(a\wedge b)=-R(b\wedge a)$), so swapping this argument order flips the sign
of the returned scalar — and the only available verification (Schwarzschild)
has $R=0$ identically, which cannot distinguish a correct sign from its
negation. The **magnitude** fix (eliminating the genuine
$\sim10^{-4}$-magnitude spurious asymmetric Ricci-tensor component) is
independently, empirically confirmed by two people computing the full tensor
from scratch; the specific sign/argument-order convention is not. This
caveat is recorded in `curvature-scalar`'s own docstring rather than
presented with unwarranted confidence, pending either a literature worked
example with a nonzero known curvature-scalar value, or independent expert
review of the eq (4.11)/(4.12) argument-order derivation.

**A strictly stronger regression test was added**:
`curvature-scalar-full-ricci-tensor-vanishes-not-merely-its-trace` checks
every component of the Ricci tensor (not merely its trace) at both existing
Schwarzschild test points — the exact class of check that would have caught
this bug at Phase 1's original landing, and that no `curvature-scalar` test
had exercised before.

## Consequences

**In scope, implemented and merged:** exactly the fix and the strengthened
tensor-level regression test above. `frame-adjoint`, `H-field`, `omega-from-h`,
`L-a-omega`, `riemann-basis-pair`, and `riemann-map`/`riemann-map-matrix` were
all independently re-confirmed correct by the same review (via several
methods, including a from-scratch verification path using the existing Dirac
gamma-matrix representation, `kotoba.sm.spinor/gammas`, entirely independent
of the finite-difference-based `riemann-map` code path) — the bug was
isolated to `curvature-scalar`'s final assembly step only.

**A minor operational note, unrelated to the bug**: this PR's CI initially
showed a JDK 21 job failure that turned out to be a transient GitHub Actions
infrastructure issue (a `curl: (22) 504` gateway timeout downloading the
Clojure CLI tooling, unrelated to any code change) — re-running the failed job
alone produced a clean pass. Recorded here only so a future reader checking
this PR's CI history isn't confused by the retry; it has no bearing on the
correctness of the fix itself, which was independently verified locally
before any push.

**Methodological consequence, extending ADR-2607102700's note**: this is now
the third instance of this specific failure mode in this repo's GTG work.
Future GTG phases should continue to treat "independent adversarial review
immediately following any non-trivial landing" as standard operating
procedure, not an optional extra — and continue to prefer regression tests
that check the strongest available invariant (here, the full tensor) over the
weakest one that happens to be convenient to compute (here, the trace) when
the two are not equivalent under the available test data (they coincide only
because $R=0$ makes both the tensor and its trace vanish for a vacuum
solution — a coincidence of the vacuum case, not a general equivalence).

**Explicitly out of scope, unchanged**: everything ADR-2607102300/2607102700
already declared out of scope (matter coupling, Einstein multivector,
GR-equivalence proof beyond Schwarzschild, quantization/RGE/scattering/
lattice, dark-sector/de-Sitter extension) remains out of scope. This ADR
fixes a bug in already-in-scope code; it does not widen scope.
