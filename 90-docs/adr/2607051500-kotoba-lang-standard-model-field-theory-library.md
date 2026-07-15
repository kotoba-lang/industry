# ADR-2607051500: `kotoba-lang/standard-model` — gauge/tensor/vector/spinor field library computing classical Standard Model field content in `.cljc`

## Status
Accepted and landed (Phase 1 — classical field algebra — implemented and shipped this
pass; Phase 2 — quantization/RGE/scattering — explicitly deferred, see Consequences).

**Landed 2026-07-05:** repo created and pushed at
[`github.com/kotoba-lang/standard-model`](https://github.com/kotoba-lang/standard-model)
(public), all six namespaces implemented with `deftest` coverage — 22 tests / 85
assertions, 0 failures — CI green on GitHub Actions (JDK 17 + 21). Registered in
`manifest/repos.edn`/`manifest/west.yml` via `gen-west-manifest.cljs --entry
standard-model` (minimal diff, server-side pin verification OK).

## Context

Owner asked for a `kotoba-lang` `.cljc` library that computes, "from the Standard
Model," the four field types that make up its Lagrangian: **gauge fields**
(the SU(3)×SU(2)×U(1) connection $A_\mu^a$), **tensor fields** (the field-strength
$F_{\mu\nu}^a$ and the Minkowski-metric tensor algebra everything else is built on),
**vector fields** (four-vectors: momenta, gauge potentials as Lorentz vectors), and
**spinor fields** (Dirac fermions — quarks and leptons).

Existing `kotoba-lang` repos named `physics`/`physics-2d`/`vphysics` are a different
domain entirely — 2D/3D **rigid-body game-engine physics** (colliders, impulse
resolution) and **vehicle physics** (road load, aero drag). None contain any
particle-physics/QFT content, so this is new ground, not a duplicate. `kotoba-lang/num`
is a separate abstraction layer (N-D array/WGSL compute engine, ADR-2607051400) aimed
at ML tensor workloads; the small, fixed-size complex matrices this library needs
(4×4 gamma matrices, 3×3/8-generator gauge algebras) are simpler served by a
self-contained implementation than by coupling to `num`'s GPU-array API.

**Scope boundary (must be explicit — "compute from the Standard Model" is an
enormous phrase):** this ADR covers the **classical field-theoretic structure** of
the Standard Model — the algebraic objects and their defining relations/equations of
motion, evaluated numerically. It explicitly does **not** cover: path-integral
quantization, renormalization (loop diagrams, running couplings/RGE beyond a fixed
reference scale), scattering-amplitude/cross-section computation, or lattice
simulation. Those are legitimate, much larger follow-up efforts and are out of scope
for this pass.

## Decision

New repo `kotoba-lang/standard-model` (public, per `repos.edn` `:orgs` role
`:language-substrate`/`:scope :platform` visibility policy), zero-dep portable
`.cljc`, six namespaces under `kotoba.sm.*`:

1. **`kotoba.sm.complex`** — foundation layer every other namespace depends on:
   complex scalars as `[re im]` pairs (add/sub/mul/conj/scale/modulus), and complex
   *matrices* as vectors-of-row-vectors (add/scale/matmul/dagger/identity/trace/
   commutator `[A,B] = AB-BA`). Gamma matrices, Pauli matrices, and Gell-Mann
   matrices are all small complex matrices, so one shared algebra layer avoids
   reimplementing matmul three times.

2. **`kotoba.sm.tensor`** — Minkowski tensor algebra: metric $\eta = \mathrm{diag}(1,-1,-1,-1)$
   (mostly-minus / Bjorken–Drell convention, documented explicitly since the other
   common convention (mostly-plus) flips several signs), index raise/lower via the
   metric, tensor contraction over a shared index, the rank-4 Levi-Civita symbol
   $\varepsilon^{\mu\nu\rho\sigma}$, and generic rank-N tensor product/contraction
   over nested-vector tensors so rank-2 objects like $F_{\mu\nu}$ and the
   stress-energy tensor $T^{\mu\nu}$ are instances of the same representation.

3. **`kotoba.sm.vector-field`** — four-vectors ($p^\mu$, $A^\mu$) as a thin, physically-
   named layer over rank-1 tensors: Lorentz boosts and rotations (the defining
   representation of the restricted Lorentz group $SO(3,1)^+$), invariant
   inner product $p\cdot p = \eta_{\mu\nu}p^\mu p^\nu$, and a finite-difference
   four-gradient/four-divergence over field functions $\mathbb{R}^4 \to V$ (fields are
   represented as plain Clojure functions of a spacetime point; there is no symbolic
   differentiation layer — derivatives needed by later namespaces are either supplied
   analytically by the caller or taken via this numeric helper).

4. **`kotoba.sm.spinor`** — Pauli matrices $\sigma^i$ (Clifford $\{\sigma_i,\sigma_j\}=2\delta_{ij}I$),
   Dirac gamma matrices $\gamma^\mu$ in the Dirac (standard) representation built from
   them (Clifford $\{\gamma^\mu,\gamma^\nu\}=2\eta^{\mu\nu}I$, verified numerically in
   tests, not asserted), chirality projectors $P_{L,R}=\tfrac12(I\mp\gamma^5)$, Dirac
   adjoint $\bar\psi=\psi^\dagger\gamma^0$, bilinear covariants ($\bar\psi\psi$,
   $\bar\psi\gamma^\mu\psi$, ...), the free-particle plane-wave solution $u(p)e^{-ip\cdot x}$,
   and a Dirac-equation residual check $(i\gamma^\mu\partial_\mu - m)\psi$ evaluated
   via the Phase-3 finite-difference gradient — this is the concrete correctness test
   that a plane wave with the right dispersion relation actually solves the equation.

5. **`kotoba.sm.gauge`** — generic compact-Lie-algebra layer: generator sets for
   $U(1)$ (trivial abelian charge), $SU(2)$ (Pauli/2), $SU(3)$ (Gell-Mann/2, standard
   fundamental-rep generators satisfying $\mathrm{Tr}(T^aT^b)=\tfrac12\delta^{ab}$);
   structure constants $f^{abc}$ derived generically for *any* generator set from
   $f^{abc} = -2i\,\mathrm{Tr}([T^a,T^b]T^c)$ (checked against the textbook values
   $f^{123}=1$ for $SU(3)$, $\varepsilon^{abc}$ for $SU(2)$, not hard-coded); the
   gauge covariant derivative $D_\mu\psi = \partial_\mu\psi - ig\,A_\mu^a T^a\psi$;
   and the non-abelian field-strength tensor
   $F_{\mu\nu}^a = \partial_\mu A_\nu^a - \partial_\nu A_\mu^a + g f^{abc}A_\mu^bA_\nu^c$.

6. **`kotoba.sm.standard-model`** — composition layer:
   - the fermion generation table (6 quarks, 3 charged leptons, 3 neutrinos) with
     each multiplet's color rep, weak isospin $T_3$, hypercharge $Y$, and electric
     charge $Q$, checked against the Gell-Mann–Nishijima relation $Q=T_3+Y/2$ for
     every entry (a real correctness constraint, not a tautology, because $Y$ is
     recorded independently per the PDG convention and $Q$/$T_3$ are looked up from
     the standard assignment);
   - electroweak symmetry breaking: the $SU(2)_L\times U(1)_Y \to U(1)_{em}$ mixing
     rotation by the Weinberg angle $\theta_W=\arctan(g'/g)$ giving the photon/$Z$ from
     $B_\mu$/$W^3_\mu$, and $W^\pm_\mu=(W^1_\mu\mp iW^2_\mu)/\sqrt2$; the tree-level
     mass relation $M_W = M_Z\cos\theta_W$ derived from (not assumed by) the Higgs
     vev and gauge couplings;
   - the Higgs potential $V(\phi)=\mu^2\phi^\dagger\phi+\lambda(\phi^\dagger\phi)^2$,
     its symmetry-breaking vev $v=\sqrt{-\mu^2/\lambda}$, and Yukawa mass generation
     $m_f = y_f v/\sqrt2$;
   - the CKM quark-mixing matrix (Wolfenstein parameterization) with a unitarity
     check $V^\dagger V = I$;
   - the full three-factor covariant derivative for an arbitrary SM fermion multiplet
     (composing three `kotoba.sm.gauge` covariant derivatives, one per factor group);
   - Lagrangian-density term assembly: Yang-Mills ($-\tfrac14\sum_a F_{\mu\nu}^aF^{a\mu\nu}$
     per factor group), Dirac kinetic ($\bar\psi(i\gamma^\mu D_\mu-m)\psi$), Higgs
     kinetic+potential, and Yukawa — each a composable function of a field
     configuration at a spacetime point, plus a `total-lagrangian-density` that sums
     them.

Every namespace ships a `deftest` suite with concrete numeric physics checks (not
just "doesn't throw"): Clifford algebras, structure-constant textbook values,
Gell-Mann–Nishijima per fermion, electroweak mixing-matrix orthogonality and mass
ratio, CKM unitarity, Dirac-equation residual on a plane wave.

## Consequences

**In scope, implemented this pass:** the classical field algebra above — every
namespace listed, with tests.

**Explicitly out of scope, deferred to a future ADR if pursued:**
- Path-integral quantization, canonical quantization, Feynman rules/diagrams.
- Renormalization, running couplings (RGE), loop-level anything.
- Scattering amplitudes / cross-sections / decay rates.
- Lattice QCD or any discretized-spacetime simulation.
- Neutrino oscillation (PMNS matrix) — same shape as CKM, natural follow-up but not
  included here to keep this pass bounded.
- Symbolic (CAS-style) differentiation — derivatives are numeric (finite-difference)
  or analytically supplied by the caller; there is no expression-tree/symbolic layer.

Do not read this ADR as claiming a full QFT computation engine exists — it is the
field-content and classical-equations-of-motion layer only, which is what was asked
for and what "gauge/tensor/vector/spinor fields computed from the Standard Model"
concretely denotes at the classical level.

Follows the standing "new project" authorization (CLAUDE.md, 2026-06-28): ADR → repo
scaffold (`.cljc` + `deps.edn` + README + tests) → `git init` + initial commit →
`gh repo create kotoba-lang/standard-model --public` + push → manifest registration
(`repos.edn` + `gen-west-manifest.cljs --entry standard-model`) → this ADR + manifest
reflected in the superproject.
