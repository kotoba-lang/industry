# ADR-2607103600: `kotoba-lang/standard-model` — a confirmed, currently-unresolved sign bug in Phase 1's field-equation pipeline for time-dependent position-gauge fields

## Status

Accepted (as an honest record of an open, unresolved defect — not a fix).
Documented prominently in code (PR #14) rather than left as a latent trap.
No landed fix. This ADR exists specifically to make the defect discoverable
and to record why two genuine attempts at a fix were not applied, so a future
attempt does not have to re-derive the same ground from scratch.

**Landed 2026-07-09:**
[`kotoba-lang/standard-model#14`](https://github.com/kotoba-lang/standard-model/pull/14)
(docstring-only, no behavior change) — pending merge at the time of this
ADR's authoring; will be updated with the merge commit once landed.

## Context

Following ADR-2607103000's fix (the `curvature-scalar` double-$h$ bug) and
PR #12/#13's work confirming `curvature-scalar`'s sign convention against a
non-trivial FRW-cosmology worked example (pure de Sitter, $\dot H=0$), a
follow-up task attempted to extend that cosmology regression test to a
genuine nonzero-density case (matter-dominated Einstein–de Sitter dust,
$H(t)=2/(3t)$).

**This surfaced a new, distinct, and more serious problem.** `curvature-scalar`
returns exactly **7×** the correct value for this solution. This was
independently reproduced by two people (the investigating agent, and the
requester running the exact same reproduction case in a fresh REPL session
afterward) at multiple time values ($t\in\{1.5,2.0,3.0,5.0\}$), with the
ratio stable at $7.00000000$ (to 7–8 significant figures) across a $20\times$
range of finite-difference step sizes ($5\times10^{-5}$ to $10^{-3}$) — a
clean algebraic factor, not truncation noise.

**Independently derived, GTG-free confirmation of the correct answer**: a
second investigation computed the Ricci scalar for the metric this
$\bar h$-field represents via ordinary Christoffel symbols (no geometric
algebra, no GTG formalism at all) and got $R=-12H^2-6\dot H$, matching a
standard textbook FRW result. This pipeline instead computes
$-12H^2+6\dot H$ — **the $\dot H$ (time-derivative) term alone has the wrong
sign**; the $H^2$ (non-derivative) term is exactly correct.

**Why this survived every prior review**: both regression solutions in this
codebase — Schwarzschild (static, no time dependence at all) and de Sitter
(constant Hubble parameter, $\dot H=0$ identically) — structurally cannot
exercise the time-derivative channel where this bug lives. This is the
**fourth** instance of a silently-wrong result in this repo's GTG work
(after PR #6's structure-constant slot order, Phase 1's first implementation
attempt using the wrong covariant derivative, and PR #11's
`curvature-scalar` double-$h$ bug) — and the first that two independent,
multi-hour investigation attempts could not resolve to a confident root
cause.

### What was investigated

Both investigations verified every individual function in the pipeline
against its own defining LDG equation, in isolation, and found each one
individually correct:

- `H-field` (eq 4.49): checked against a general symbolic $H(t)$ (not
  assuming any specific Friedmann-equation form) via exact symbolic
  differentiation — structurally has zero $\dot H$-dependence by
  construction (a mathematical necessity of the formula, not a bug).
- `omega-from-h` (eq 4.53, via eq 4.50): checked directly against eq (4.50)
  ($\partial_b\wedge(\omega(b)\cdot a)=H(a)$) using the code's own outputs
  for all 4 directions — exact match. Independently re-verified against a
  standalone Python geometric-algebra reference engine (the same one
  validated against Schwarzschild's eq 6.73) — exact match.
- `L-a-omega` (eq 4.42, $L_a:=a\cdot\bar h(\nabla)$): the identity
  $a\cdot\bar h(\nabla)=h(a)\cdot\nabla$ was re-derived three independent
  ways and confirmed on a **plain scalar test function**
  ($F(x)=t$, $a=e_0$) directly, with no bivector/GA machinery involved at
  all — exact match. The commutator identity (eq 4.44,
  $[L_a,L_b]=(L_ah(b)-L_bh(a))\cdot\nabla$) was also checked directly,
  bypassing $\omega$ entirely — matched to $10^{-13}$.
- `riemann-basis-pair` (eq 4.48): the derivation chain eq
  (4.44)→(4.46)→(4.47)→(4.48) was re-derived by hand and checked line-by-line
  against the implementation — exact structural match.

**Yet the end-to-end composition is measurably wrong**, specifically and only
in the time-derivative contribution. Symbolic computation (general $H(t)$,
not assuming a specific Friedmann form) isolated the discrepancy precisely:
the code computes $R(e_0\wedge e_1)=-H(t)^2+H'(t)$ where the correct
tetrad-frame value is $-H(t)^2-H'(t)$.

### The declined patch

A candidate one-line fix — negating `L-a-omega`'s $\rho=0$ (time-direction)
contribution only, leaving all other $\rho$ untouched — numerically
eliminates the dust-case discrepancy, and provably does not affect the
Schwarzschild or de Sitter regressions (both have a structurally zero
$\rho=0$ contribution, so cannot detect this class of change either way).

**This patch was deliberately not applied.** It directly contradicts the
simpler, independent scalar-function identity check described above
($a\cdot\bar h(\nabla)F=h(a)\cdot\nabla F$ on $F(x)=t$): applying the patch
would make that more fundamental check fail. A fix that repairs one
regression test by breaking a more basic, independently-verified identity is
not a fix — it is symptom suppression, and would reintroduce exactly the
"tests pass, code is wrong" failure mode this whole investigative lineage
exists to prevent.

## Decision

**Do not land any fix without a confident, independently-verified root
cause.** Document the defect prominently instead (PR #14): a namespace-level
"CONFIRMED, UNRESOLVED BUG" notice in `kotoba.sm.gtg`'s PHASE 1 SCOPE NOTE,
and a matching caveat on `curvature-scalar`'s own docstring, both stating
plainly that Schwarzschild (static) and de Sitter ($\dot H=0$) remain
verified and safe, and that any $h$-field with $\dot H\neq0$ is not safe to
trust through `omega-from-h`/`riemann-map`/`riemann-basis-pair`/
`curvature-scalar` until this is resolved.

No new regression test was added for the dust case (doing so without a
verified fix would either fail outright, misleadingly signal "resolved" if
someone later force-fits a passing assertion, or require asserting a wrong
number as "expected"). No existing test or code was modified — Schwarzschild
and de Sitter tests, and every other Phase 0/1 test, are unaffected and
continue to pass (79 tests, 2911 assertions, unchanged from before this ADR).

## Consequences

**In scope, landed:** honest documentation of a real, open defect, prominent
enough that no future reader of `curvature-scalar` (or anything built on top
of it) can miss the limitation.

**Explicitly deferred, not resolved by this ADR:**
- The actual root cause of the $\dot H$ sign error.
- Any fix.
- A regression test for the time-dependent (dust or general FRW) case.

**Recommended next steps for whoever picks this up**, per both
investigations' own suggestions:
- Consult GTG/geometric-algebra domain expertise beyond what iterative
  independent numeric/symbolic re-derivation from the primary paper alone
  can resolve — two thorough attempts, each multiple hours, each checking
  every individual formula against LDG's own equations and against
  independent GA/GR cross-checks, were not enough.
- Cross-check `omega-from-h`/`L-a-omega` against a **third**, independent
  exact time-dependent solution from the literature (not derived by this
  investigation itself), if one can be found via the same PDF-page-image
  discipline this repo now applies throughout.
- Consider whether the bug is not in any single named function but in an
  interaction between the INNER finite-difference level (`H-field`'s curl,
  differentiating $\bar h^{-1}$) and the OUTER finite-difference level
  (`L-a-omega`'s directional derivative, differentiating `omega-from-h`
  itself) — a "finite difference of a finite difference" structure unique to
  this pipeline that neither investigation fully ruled out as a source of a
  *structural* (not merely truncation-error) sign issue specific to
  double-differentiating along the same coordinate axis ($t$) at both levels.

**Methodological consequence, extending ADR-2607103000's note**: this is now
the fourth instance of the "silently wrong, invisible to existing tests"
failure mode in this repo's GTG work, and the first one that resisted two
genuine, careful, independent resolution attempts. The response to that is
not to force a fix, guess-and-check against the one failing test, or lower
the verification bar — it is exactly what happened here: document honestly,
decline the unverified patch, and leave the defect visible rather than
papered over. Future GTG work should continue to test against solutions that
are *maximally different in kind* from what's already covered (a genuinely
time-varying rate, not just "a solution with a different name" — the
lesson de Sitter's own limitation as a regression case, despite being
literally time-dependent in its metric, teaches: $\dot H=0$ made it
insufficient to catch this).

## Addendum (2026-07-09, same day): two further investigations narrow the search space, still unresolved

Following the user's explicit direction to continue narrowing the search (one
scoped diagnostic, then one final term-level attempt), two more investigations
ran after this ADR's initial acceptance:

**Investigation 3 (diagnostic, not a fix attempt): the nested-finite-difference
hypothesis is refuted.** `H-field`'s inner finite difference (the curl of
$\bar h^{-1}(a)$) was replaced with an exact, closed-form analytic expression
for the dust case — zero finite-difference error at that level, leaving only
`L-a-omega`'s outer finite difference in the pipeline. The bug persisted
bit-for-bit identical (same factor of 7, same wrong sign on the
$\dot H$-dependent component, agreement between the fully-finite-difference
and inner-analytic pipelines to ~$10^{-8}$, i.e. to the residual outer-FD
truncation only). This clears `H-field` completely — not merely "verified
correct in isolation" as before, but proven that even a zero-error version of
it does not change the outcome — and rules out any interaction between the
inner and outer finite-difference levels as the cause.

**Investigation 4 (term-by-term isolation): `L-a-omega` and `riemann-basis-pair`
are also cleared, by the strongest method used yet.** A from-scratch,
independent reimplementation (sympy, no calls into the Clojure code)
of eq (4.42)'s directional derivative and eq (4.48)'s combination formula
$R(a\wedge b)=L_a\omega(b)-L_b\omega(a)+\omega(a)\times\omega(b)-\omega(c(a,b))$
was built directly from the docstrings' stated formulas, then compared
term-by-term against the actual Clojure functions — for **all 6 basis-bivector
pairs**, not just the one pair examined in earlier passes. Every individual
term matched to finite-difference tolerance, and hand-reconstructing
`curvature-scalar`'s full double sum from these independently-verified
per-pair values reproduces the code's actual (wrong) output bit-for-bit. This
is a materially stronger clearance than "the formula looks right when
inspected": an *independently authored, differently-implemented* version of
the identical documented math reproduces the identical wrong answer, which
rules out coding-level bugs (index/loop/sign-transcription errors) in these
two functions specifically, not merely their high-level derivation.

**Where this leaves the search, after four investigations**: `H-field`,
`L-a-omega`, and `riemann-basis-pair` are now cleared with strong,
independent, non-code-reading evidence (exact analytic substitution for the
first; from-scratch independent reimplementation matching the wrong answer
for the other two). The only remaining candidates are (a) `omega-from-h`'s
closed-form derivation of eq (4.53) itself — i.e. a possible error in how
the codebase's own re-derivation of the closed form (`omega-from-h`'s own
docstring shows the a·(u∧B)=(a·u)B-u∧(a·B) identity-based rewrite) was
carried out, distinct from `omega-from-h` merely being "self-consistent with
eq (4.50)" as verified in earlier passes — or (b) a conceptual gap in how
eq (4.48) needs to be applied to genuinely time-evolving connections that
is not visible from re-deriving the formula alone, i.e. a physics/math-level
question rather than a code-level one. The fourth investigation's own
speculative side-lead (a candidate nonzero correction to $\omega(e_0)$,
suggested by comparing against an independently-built ordinary-GR tetrad
dictionary) was tested and explicitly discarded — it broke the already-
verified de Sitter case, so was not applied and is not treated as a finding.

**Status unchanged: this remains a confirmed, unresolved, documented bug.**
No code was changed by either investigation (`git status` clean at each
pass' conclusion); the "CONFIRMED, UNRESOLVED BUG" docstring warnings (PR #14)
remain accurate and in place. Four independent investigations, each
progressively narrower and more rigorous, have not found a code-level fix —
the remaining candidates point toward a physics/derivation-level question
(possibly requiring the same kind of external GTG expertise the original
ADR's "recommended next steps" already suggested) rather than something a
fifth code-level probing pass is likely to resolve differently from the
first four. Further attempts should change *method* (e.g. genuine external
consultation, or a fifth independent literature solution with $\dot H\neq0$)
rather than repeat the term-isolation approach that has now been applied at
increasing granularity three times (whole-function, then all-terms-one-pair,
then all-terms-all-pairs) without success.

## Second addendum (2026-07-09, same day): a fifth investigation, changing method, still finds no fix — pausing here

Per this ADR's own guidance ("further attempts should change method... rather than repeat term-isolation"), a fifth investigation used a genuinely different primary source: David Hestenes, "Gauge Theory Gravity with Geometric Calculus" (*Foundations of Physics*, 2005) — an independent author's exposition of the same GTG theory, not merely a different reading of the same LDG 1998 paper already exhausted by investigations 1–4.

**Result: `omega-from-h`'s closed-form derivation (eq 4.49/4.53) is independently corroborated, not contradicted.** Hestenes' own derivation (his Appendix A, eqs 242/245/246) uses an intermediate quantity with an opposite sign convention from LDG's, but substituting through algebraically shows the two collapse to the *identical* final closed form LDG's eq (4.53) states and `omega-from-h` implements — a real difference in exposition, not an error. A second candidate lead (Hestenes' curvature-bivector formula using the plain vector derivative $\nabla$ rather than LDG's $L_a$) was chased and also resolved as a self-consistent alternative frame choice (Hestenes' coordinate frame $g_\mu=\bar h^{-1}(e_\mu)$ vs. this codebase's fixed orthonormal frame $e_\mu$), not a sign discrepancy. Hestenes' paper contains **no cosmological or other time-dependent worked example** to cross-check the $\dot H$-dependent term against.

**Cumulative state after five investigations**: `H-field` (investigation 3, exact analytic substitution), `L-a-omega` and `riemann-basis-pair` (investigation 4, independent from-scratch reimplementation matching the wrong answer for all 6 basis pairs), and now `omega-from-h`'s closed-form derivation itself (investigation 5, independent third-source corroboration) have all been cleared by methods stronger than "the formula looks right on inspection." No coding-level bug has been found anywhere in the pipeline, by any of five independent methods, across roughly 15+ combined investigator-hours.

**Decision: pause this investigation here.** Every readily-available method — term isolation at increasing granularity (investigations 1, 2, 4), an FD-structure diagnostic (investigation 3), and cross-referencing an independent secondary source (investigation 5) — has been exhausted without finding a fix. The remaining hypothesis (a conceptual gap in applying the closed-form solution to genuinely time-evolving connections, not visible from re-deriving any single formula in isolation) is not the kind of question further solo re-derivation is likely to resolve; it most plausibly needs either domain-expert consultation this repository's usual working method cannot substitute for, or a fundamentally different worked example from the literature that neither LDG 1998 nor Hestenes 2005 happens to provide.

**Status, unchanged and now with higher confidence it will not casually resolve**: confirmed, unresolved, prominently documented (PR #14's docstring warnings). Schwarzschild (static) and de Sitter ($\dot H=0$) remain verified and safe. Any $h$-field with $\dot H\neq0$ remains untrustworthy through this pipeline. No further investigation of this specific bug is scheduled; it is left as an open, well-documented item for whenever genuine new information (a different literature source, or outside expertise) becomes available, rather than continuing to spend investigator effort re-covering the same five angles.
