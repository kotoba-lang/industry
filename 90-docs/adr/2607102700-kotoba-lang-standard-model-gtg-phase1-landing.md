# ADR-2607102700: `kotoba-lang/standard-model` — Phase 1 landing: the vacuum $\Omega(h)$ field equation, covariant Riemann map, and the true LDG Ricci scalar

## Status

Accepted and landed. This is the landing-record ADR ADR-2607102300 (the scope-opening
ADR) said should follow once something merged, mirroring the
ADR-2607101700 → ADR-2607101900 pattern.

**Landed 2026-07-09:**
[`kotoba-lang/standard-model#9`](https://github.com/kotoba-lang/standard-model/pull/9)
merged as `3710b427ed90b0a7afce3a08b00212bda8b7bcd8` — 68 tests / 2740
assertions (up from 58/2465 at ADR-2607101900's close), CI green (JDK 17+21).
Superproject `manifest/west.yml` pin advanced to this commit via the sanctioned
GitHub API single-entry commit (`d8bb964afa3555a7ba691790ebdd08049d56d7a1`).

## Context

ADR-2607102300 opened "Phase 1" — a deliberate, narrowly-scoped exception to this
repo's long-standing "action principle and field equations out of scope" boundary
— specifically to pursue the $\Omega(h)$ relation that Phase 0d and 0e each
independently found missing while chasing the true Lasenby-Doran-Gull (LDG)
curvature scalar. That ADR set an explicit verification bar: at least two
independent literature sources for the exact field equation, plus a genuine
independent numeric check, with an honest non-implementation writeup as the
correct fallback if the bar wasn't cleared.

**What happened, in three passes:**

1. **Literature research** found and cross-verified (LDG 1998 primary source,
   `gr-qc/0405033`, plus a truly independent author, Francis & Kosowsky,
   `gr-qc/0311007`) the vacuum gravitational action and the $\Omega$-field
   equation of motion (eq 4.37/4.40), with its closed-form solution (eq 4.49–4.53)
   and a worked example — LDG's own Schwarzschild solution in Painlevé-Gullstrand/
   Newtonian gauge (eq 6.79, closed-form curvature eq 6.73).

2. **A first standalone numeric verification attempt failed**, and — critically —
   *diagnosed why* rather than papering over it: `pdftotext` extraction of the key
   equations (4.28, 4.49–4.53) was demonstrably garbled (overdots and scope
   markers detached from their symbols across line wraps), and the attempt had
   used the wrong covariant-derivative operator — Phase 0a's flat, rotation-gauge-
   only-covariant $D_\mu=\partial_\mu+\Omega_\mu\times$ instead of the *calligraphic*
   $\mathcal{D}$ (Table 2) that eq (4.37)/(4.40) actually use, which uses
   $\bar h(\nabla)$ and $\omega(a)=\Omega(h(a))$. Reusing the wrong operator
   constructs a teleparallel connection whose curvature is identically zero by
   construction for any $h$ — a structurally wrong equation, not a sign slip.

3. **A second pass, reading the actual PDF pages as images** (not text
   extraction) resolved the ambiguity and correctly identified the calligraphic
   $\mathcal{D}$. A standalone Python/numpy re-implementation then reproduced
   LDG's own closed-form Schwarzschild curvature (eq 6.73) to ~$10^{-13}$
   (within finite-difference truncation error, confirmed second-order
   convergence), with the Ricci contraction vanishing to $10^{-17}$–$10^{-19}$ at
   three independent points — clearing ADR-2607102300's verification bar
   decisively. This was independently re-run and confirmed by the requester
   before any Clojure implementation began.

The Clojure port (PR #9) then translated this verified pipeline into
`kotoba.sm.gtg`, reusing existing `kotoba.sm.tensor` machinery (no from-scratch
geometric-algebra engine) and adding only the minimal new primitives the field
equation actually needs. Before this PR was merged, the requester independently
re-derived the Schwarzschild regression numbers in a live Clojure REPL session —
catching and correcting a mistake in that independent re-derivation itself
(forgetting the $\bar h\to h$ `frame-adjoint` conversion on the first attempt,
which produced spuriously near-zero results until corrected) — landing on values
matching the committed tests to the same tolerance.

## Decision

Land, in `kotoba.sm.gtg`, exactly:

- `frame-adjoint` (eq 2.46) — the self-inverse GTG adjoint relating $h$ and
  $\bar h$, needed as a standalone operation (not only embedded inside a
  reciprocal-frame formula as in Phase 0d/0e).
- Minimal new GA primitives on top of existing `kotoba.sm.tensor`:
  `bivector<->matrix`, `wedge-vectors`, `vector-dot-bivector`,
  `bivector-commutator`, `outermorphism` — each independently cross-checked
  against a Python reference engine before being trusted.
- `H-field` (eq 4.49) and `omega-from-h` (eq 4.53) — the closed-form vacuum,
  spin-free solution of the rotation-gauge field equation: given a position-gauge
  field, produces $\Omega_\mu(x)$ in exactly the shape Phase 0a's
  `rotation-field-strength` already consumes.
- `L-a-omega`, `riemann-basis-pair`, `riemann-map-matrix`, `riemann-map` (eq
  4.48) — the **covariant** Riemann map, using the position-gauge-covariant
  directional derivative $L_a$, not Phase 0a's flat coordinate derivative.
- `curvature-scalar` — the true LDG Ricci scalar,
  $R=\sum_{ab}\gamma^a\cdot(\gamma^b\cdot R(h_b\wedge h_a))$, using the *fixed
  background frame's own* trivial reciprocal (confirmed by two independent
  literature sources back in Phase 0d — not a reciprocal frame of $h$, a
  distinction `reciprocal-frame`'s own docstring already records). Reachable
  only because `riemann-map` supplies the bilinear extension to general
  (non-basis-pair) bivectors that Phase 0d/0e each found missing.

Verification landed with the code, not asserted separately: Schwarzschild
regression at two independent points/mass parameters (error
$\sim10^{-9}$–$10^{-10}$, within finite-difference tolerance), `curvature-scalar`
$\approx 0$ at both (the expected vacuum result) and **exactly** `0.0` at the flat
limit, where every derivative in the pipeline vanishes identically rather than
merely numerically.

## Consequences

**In scope, implemented and merged:** exactly the items above — vacuum,
spin-free only.

**This is a qualitative change from every prior ADR's framing.** ADR-2607051500
through ADR-2607101900 all described this repo as "classical field algebra
only... not an action/field-equation engine." That framing no longer fully
applies: this repo now contains a genuine field *equation* (the vacuum
$\Omega(h)$ relation) and its physical consequence (a curvature scalar verified
against an exact solution), not merely field-content algebra. What has **not**
changed: this remains bounded to the vacuum, spin-free case, verified against
exactly one exact solution (Schwarzschild) rather than a general
proof-of-equivalence to GR, and every boundary ADR-2607102300 declared still out
of scope (matter coupling, Einstein multivector/stress-energy sourcing,
GR-equivalence proof, quantization/RGE/scattering/lattice, dark-sector/de-Sitter
extension) remains out of scope here too — none of it was implicitly widened by
reaching the curvature scalar.

**Explicitly out of scope, deferred (unchanged from ADR-2607102300):** matter
coupling to gravity (the full three-factor-gauge-plus-gravity covariant
derivative), the Einstein multivector and stress-energy sourcing (i.e. the
non-vacuum field equation $G(a)=\kappa T(a)$), any proof of GR-equivalence beyond
the single Schwarzschild check, spin/torsion coupling (eq 4.28's general form,
only the spin-free reduction eq 4.40 was implemented), quantization/RGE/
scattering/lattice, and any dark-matter/dark-energy/de-Sitter extension.

**A methodological note worth keeping**, since it is the second time in this
repo's history (after the `self-interaction-term` slot-order bug, PR #6) that a
seemingly-thorough single pass produced a confidently-wrong result: the first
Schwarzschild verification attempt used equations that *looked* plausible from a
degraded text extraction and a superficially-reasonable choice of covariant
derivative, and failed silently (identically-zero curvature, not an error) rather
than loudly. It was caught only because the task's own success criterion was an
independent numeric match against a known closed-form result, not merely "does
the code run" or "does the formula resemble the paper." Every phase in this
repo's GTG work (0a through 1) has now been saved from at least one instance of
this failure mode by insisting on that kind of check; this ADR is not the first
and should not be read as license to relax it for whatever comes next.
