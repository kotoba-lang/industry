# ADR-0024: kotoba stack accountability boundaries

- **Status**: Accepted
- **Date**: 2026-06-29
- **Deciders**: 河崎純真
- **Context tags**: accountability, authority, kotoba, kotoba-lang, kototama, aiueos, kotodama, murakumo, kami-engine, app-aozora, clj-libraries
- **Related**: ADR-0020 three-org taxonomy, ADR-0021 Rust/Clojure/WASM layering, ADR-0023 organism actor first

## Context

`kotoba` historically accumulated many responsibilities: language, database,
compiler, runtime, graph, auth, server, mesh, and agent-facing surfaces. That was
useful while proving the stack, but it makes the important question ambiguous:
who is accountable for granting authority, spending resources, persisting data,
or running an organism?

Recent splits move in the right direction:

- `kotoba-lang` separates the language profile and conformance contract.
- `kototama` separates the common host facade for CLJ/Kotoba -> Wasm execution.
- portable `*-clj` libraries separate reusable Clojure authoring assets from
  product and deployment responsibility.
- The GitHub repositories `kotoba`, `kototama`, `aiueos`, and `kotodama` now
  live under the `kotoba-lang` organization. Local checkout paths for the
  migrated language/runtime/library repos are `orgs/kotoba-lang/<repo>`.

This ADR fixes the accountability boundary so future work does not put generic
or authority-bearing behavior back into `kotoba` by default.

## Decision

Accountability is assigned by **authority held**, not by implementation language
or by where the code historically lived. Each layer may depend on lower layers,
but it must not silently acquire their authority.

| Layer / repo | Owns | May use | Must not own |
|---|---|---|---|
| `kotoba` | semantic substrate, datom/store invariants, query/history semantics, content addressing, auth primitives, compiler substrate | `kotoba-lang`, `kotoba-clj`, `kototama`, `aiueos`, app repos | product workflows, OS supervision, organism mission, deployment authority, app UX |
| `kotoba-lang` | source profile, reader target rules, language versioning, conformance fixtures | `kotoba-clj`, docs, tests | runtime policy, host execution, product semantics |
| `kotoba-clj` | CLJ subset compiler, safe analysis, Wasm/component emission | `kotoba-lang`, `kototama`, `aiueos` | granting host authority, supervising systems, owning app state |
| `kototama` | common host facade, `HostCaps`, `RuntimeLimits`, import surface validation, browser/local/server execution contract | `kotoba-clj`, `kami-engine-clj`, low-level hosts | deciding whether a deployment is allowed, owning organism lifecycle, app business rules |
| `aiueos` | component OS, broker, capability graph, grant/deny decisions, policy, audit, system supervision | `kotoba`, `kotoba-lang`, `kototama` | language semantics, app product policy, organism mission |
| `kotodama` | organism/actor lifecycle, dialogue/invoke loop, memory and skill routing patterns | `kotoba`, `kototama`, `aiueos`, `*-clj` libraries | host authority grants, substrate invariants, product deployment accountability |
| `murakumo` / `cloud-murakumo` | compute placement, GPU/serverless scheduling, financial-effect gates, resource audit | `kotoba`, `kototama`, `aiueos` where useful | language profile, generic organism runtime, app-specific UX |
| `kami-engine` | engine core, rendering/physics/input loop, engine SDK contracts | `kotoba-lang`, `kototama` for authoring surfaces | app business logic, generic host authority, OS broker |
| `app-aozora` | product/account service, yoro UX, app contracts, product data policy, operational accountability | `kotoba`, `kototama`, `kotodama`, `*-clj` libraries | generic language/runtime substrate |
| `*-clj` shared libraries | portable protocols, pure/domain-agnostic APIs, authoring utilities | all higher layers | secrets, tenancy, deployment grants, financial/resource authority |

The authority chain is:

```text
kotoba-lang  -> describes valid source
kotoba-clj   -> compiles/analyzes source
kototama     -> exposes host-facing execution contract
aiueos       -> grants/denies component authority and audits it
kotodama     -> runs organism patterns under granted authority
app/murakumo -> owns product or compute-resource outcomes
```

`kotoba` remains a substrate monorepo, not the accountable actor. It can provide
auth and storage primitives, but it does not decide that a component may run, a
GPU may be rented, a user account may be touched, or an organism may act.

## Admission rule for new code

New generic code must not be added to `kotoba` unless it protects or exposes a
substrate invariant. Use this routing rule:

| New code kind | Destination |
|---|---|
| language grammar/profile/conformance | `kotoba-lang` |
| CLJ subset compilation or safe analysis | `kotoba-clj` |
| host capability facade / Wasm import contract | `kototama` |
| capability graph, broker, grant/deny, audit | `aiueos` |
| organism lifecycle / actor loop / skill routing | `kotodama` |
| compute scheduling / GPU spend / serverless placement | `murakumo` or `cloud-murakumo` |
| engine loop/render/physics/input | `kami-engine` |
| product workflow / UX / tenant data policy | product repo such as `app-aozora` |
| portable Clojure API with no deployment authority | appropriate `*-clj` library |

If the answer is "it could live in `kotoba` because everything uses it", that is
not enough. The change must name the invariant `kotoba` is accountable for.

## Review gate

Any change that introduces or widens authority must update the owning layer's
documentation before merge:

- new host import or capability surface: `kototama` docs and tests
- new grant/deny rule: `aiueos` policy/audit docs and tests
- new source construct: `kotoba-lang` profile/conformance and `kotoba-clj` tests
- new organism action pattern: `kotodama` docs
- new resource spend or placement path: `murakumo` approval/audit docs
- new product workflow or data policy: product repo ADR

## Consequences

- `kotoba` can stay broad internally, but no longer receives new accountability
  by default.
- Authority-bearing behavior has a visible owner and audit surface.
- The `kotoba-lang` and `kototama` splits are promoted from implementation
  cleanup to architectural boundaries.
- `aiueos` is the accountable broker for component authority; `kototama` is the
  host contract; `kotoba` is the substrate.

## Non-goals

- This ADR does not move repositories or rewrite existing historical code.
- This ADR does not require splitting the `kotoba` monorepo immediately.
- This ADR does not decide product governance for a specific deployment; product
  repos remain accountable for their own app-level policies.
