# ADR-2607131200: `com-junkawasaki/physics-model` — relational resonance closing summary (loop concluded)

**Status**: accepted
**Date**: 2026-07-13
**Deciders**: Jun Kawasaki

## Context

This ADR closes the current `com-junkawasaki/physics-model` work wave.
The repository was assembled from the local `inc`, `time`, and `inc-rqm`
projects and then extended into a checked Lean formalization of the
relational resonance physics model.

The owner asked to close the loop once the checked layers and the
many-body / interacting scattering bridge were in place and the repo
build remained green.

## What this wave built

The repo now contains checked Lean layers for:

- finite families of general `n → m` scattering processes with conserved
  total four-momentum
- frame-invariant Born measurements for finite families of processes
- channel-wise phase twists that preserve family Born normalization and
  probabilities
- phase-twisted process Born measurements with pointwise probability
  invariance
- Lorentz-invariant phase-twisted process Born measurements
- Lorentz-invariant phase-twisted interacting scattering distributions
- interacting scattering families with phase-twisted Born measurements
- relabeling invariance and disjoint-union Born decomposition for
  interacting families
- `2 → 2` interacting families lifted into general `n → m` process
  families
- two-channel interference scattering with exact probability conservation

The checked state was validated with `lake build`, and the current
physics-model repo HEAD at close is:

- `com-junkawasaki/physics-model@e4ec3d28037648340ff4fa2010ace754ee5866e7`

## Consequences

- (+) The repo now has a coherent checked core for relational,
  resonance-oriented scattering: family conservation, phase twists,
  interference, relabeling, disjoint unions, and a bridge from `2 → 2`
  interacting families to general `n → m` processes.
- (+) The README and the Lean code now agree on the same checked
  boundary: what is proved, and what remains open.
- (+) The repo remains build-green at close.
- (−) The physically harder goals remain open: general relativity,
  Standard Model derivations, and a complete physical derivation of the
  Born rule are still not completed as a single theory.

## Future work

1. Extend the `n → m` bridge beyond `2 → 2` special cases if a direct
   many-body interacting abstraction becomes useful.
2. Add stronger composition laws for interacting families if the next
   wave needs categorical or monoidal structure.
3. Continue the physical derivation layer from the existing checked core
   toward the open obligations listed in the repo README.

