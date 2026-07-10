# ADR-2607105000: `kotoba-lang/standard-model` — extending independent-review discipline to the original 6 namespaces finds and fixes a real hypercharge-normalization bug

## Status

Accepted and landed.

**Landed 2026-07-09/10:**
[`kotoba-lang/standard-model#15`](https://github.com/kotoba-lang/standard-model/pull/15)
merged as `519e5da8daff98692a720538e50506ea8848c50f` — 80 tests / 2918
assertions, CI green (JDK 17+21). Superproject `manifest/west.yml` pin
advanced to this commit via the sanctioned GitHub API single-entry commit
(`df6500d3629421e57c5f2741d48def5af1d52b65`).

## Context

The GTG work this session (ADR-2607101700 through ADR-2607103600) repeatedly
found real bugs via independent adversarial review — PR #6 (structure-constant
slot order), PR #11 (`curvature-scalar` double-$h$ application) — each
invisible to "tests pass" until someone re-derived the physics from scratch
before reading the code. That discipline had never been applied to the
**original six namespaces** (`kotoba.sm.complex`, `tensor`, `vector-field`,
`spinor`, `gauge`, `standard-model`) landed by ADR-2607051500, which predate
and are independent of the GTG work.

After pausing the (still-unresolved, see ADR-2607103600) GTG time-derivative
investigation, this same review discipline was pointed at the original six
namespaces instead — genuinely different work, not a repeat of the paused
bug chase.

**A fresh reviewer independently re-derived, before reading any code**: the
fermion generation table and Gell-Mann–Nishijima relation, electroweak mixing
(Weinberg angle, $W$/$Z$/photon, $M_W=M_Z\cos\theta_W$), the Higgs
potential/vev/Yukawa masses, CKM unitarity, Dirac algebra, and Minkowski
tensor conventions — from standard QFT-textbook and PDG knowledge — then
diffed against the implementation.

**Result: everything checked out correct except one real, reproducible bug.**
`covariant-derivative-sm` built the U(1)$_Y$ gauge generator from the raw
fermion hypercharge `:Y`, not `:Y/2`. This namespace's own
Gell-Mann–Nishijima convention (already correctly checked elsewhere by
`gell-mann-nishijima-ok?`) is $Q=T_3+Y/2$. After electroweak mixing — using
this namespace's own `mixing-matrix`/`photon-Z`, inverted — the photon
coupling coefficient works out to $e(T_3+Y_{\text{eff}})$ (since
$e=g\sin\theta_W=g'\cos\theta_W$, already independently satisfied by this
namespace's own mass/mixing functions), which equals the required
$eQ=e(T_3+Y/2)$ only when $Y_{\text{eff}}=Y/2$. Using raw $Y$ instead gives a
coupling wrong by a $T_3$-dependent factor for every charged fermion with
$T_3\neq0$ — concretely, 50% too large in magnitude for $e_L$ specifically.

**Why this survived the original landing**: the only test of
`covariant-derivative-sm` (`covariant-derivative-sm-reduces-to-partial-when-
fields-off`) exercises exclusively the fields-off case, where the U(1)
generator's normalization cannot possibly matter. No test anywhere
decomposed the combined SU(2)/U(1) output into the physical photon direction
and checked it against $eQ$ — the classic "each piece verified in isolation,
never verified in combination" gap this review discipline exists to find.

**The requester independently re-verified before merging**, in the now-usual
pattern: re-derived the same $e(T_3+Y_{\text{eff}})=eQ$ algebra from scratch,
and in the process of building an even stronger regression test than the
reviewer's own, caught and fixed a coupling-constant argument-order mistake
in that test's *own* first draft (passing `gw=1.0` instead of the intended
`g`) before it could land a subtly-wrong "passing" test.

## Decision

Fix: `(gauge/u1-generators (:Y fermion) dim)` → `(gauge/u1-generators
(/ (:Y fermion) 2.0) dim)`.

Add `covariant-derivative-sm-photon-coupling-matches-e-times-Q`: constructs
a pure-photon field configuration (chosen so the mixed $Z$-component is
exactly zero) and checks the resulting coupling for $e_L$ against $-ieQ\psi$
exactly, plus an explicit assertion that the pre-fix (raw $Y$) value would
have been $1.5\times$ too large — a regression strong enough to have caught
this at the original landing.

**No other bugs found** across the rest of the original six namespaces:
`complex.cljc`/`tensor.cljc`'s core algebra, `spinor.cljc`'s gamma matrices
and Dirac equation, `gauge.cljc`'s SU(2)/SU(3) generators and structure
constants (including the self-interaction slot-order pattern already fixed
for the noncompact GTG case, independently reconfirmed correct for the
original compact case too), the CKM matrix (checked via an independent
3-rotation matrix product, matching to floating-point noise), and the Higgs
sector all matched independent derivation. Some coverage gaps were noted but
not fixed (see Consequences) since they are not bugs, only thin tests around
already-verified-correct code.

## Consequences

**In scope, landed:** the hypercharge fix and its regression test.

**Coverage gaps noted, not fixed in this pass** (flagged for whoever revisits
`kotoba.sm.standard-model` next, not urgent since the underlying formulas are
independently confirmed correct, just thinly tested):
- `gell-mann-nishijima-ok?` is tautological against its own `:Q` field for
  18 of 21 fermion-table entries (only 3 — up, electron, electron-neutrino —
  are independently spot-checked against known charges).
- `photon-Z`/`mixing-matrix` is only tested for norm preservation, which any
  orthogonal matrix satisfies — would not catch a sign flip or an $A/Z$
  swap. (This review independently confirmed the actual convention is
  correct; only the test's discriminating power is weak.)
- `M_W=M_Z\cos\theta_W` and CKM unitarity are each tested at exactly one
  fixed set of coupling/angle values, though both are algebraic identities
  that hold generically by construction.
- `covariant-derivative-sm` expects `fermion` maps with `:color`/`:weak`
  keys, while `fermion-content`'s entries use `:multiplet` instead — the two
  parts of the module were never directly wired together, which is part of
  why the hypercharge bug went unexercised against the real fermion table.
- `yang-mills-density`'s test mislabels its `n=0` degenerate branch as
  "U(1)" — a real U(1) always has exactly one generator (`n=1`), never zero.
  The formula itself is correct at `n=1`; only the test label is misleading.

**Methodological consequence**: this is the third real bug this session's
independent-review discipline has found in `kotoba-lang/standard-model`
(after PR #6 and PR #11), and the first found by applying that discipline
to code that had *not* just been freshly written and was presumed
comparatively low-risk for being older and more "settled." That presumption
was wrong. The review discipline established for GTG — re-derive from
physics literature before reading code, decompose combined systems and check
the combination (not just each piece in isolation), and independently
re-verify any proposed regression test before trusting it — generalizes, and
should be considered for the rest of this repo's surface area, not treated
as GTG-specific tooling.
