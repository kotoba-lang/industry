# ADR-2607173000: Fleet-wide Babashka → nbb (bb binary retirement)

**Status**: accepted  
**Date**: 2026-07-17  
**Deciders**: Jun Kawasaki (owner request「Babashka を全て nbb に」)  
**Related**: ADR-2607100100 (runtime priority; superproject tooling nbb port addendum),
PR #336 (`chore(tooling): migrate root babashka/shell tooling to nbb`),
ADR-2607124500 (BMC routine bb→nbb command fix)

## Context

Superproject operational tooling (`scripts/`, `manifest/`, `70-tools/bmc/`,
`.claude/hooks/`) already runs on **nbb** (PR #336 / ADR-2607100100 addendum).
`scripts/nbb_compat` provides Node stand-ins for historical `babashka.*` /
`clojure.java.*` APIs so ported scripts keep compiling.

That port left a **fleet gap**:

| Surface | ~Count (2026-07-17) |
|---------|---------------------|
| Child `bb.edn` | ~134 |
| Child `*.bb` scripts | ~86 (edn-datomize / publish / gen-shadow families) |
| Child `nbb.edn` | 2 only |
| Superproject residual `bb` invocations | scripts/hooks/skills/workflows |

ADR-2607100100 Decision §3 deferred full ops tooling migration to a separate
ADR. This ADR **closes that deferral** for the entire west-managed fleet:
**no workflow, script, CI job, or agent instruction should require the `bb`
binary.**

nbb is not SCI-identical (no raw Java interop, no babashka pods, explicit
classpath). JVM remains a last-resort **app** runtime for work that truly
needs it (`clojure -M:…`, Playwright Java, etc.). This program retires **bb
as the task/script host**, not JVM application code.

## Decision

1. **Canonical scripting / task host = nbb only** across the superproject and
   every west project under `orgs/`.
2. **`bb.edn` and `#!/usr/bin/env bb` are forbidden** in new work and must be
   removed in waves (below). Scaffolds emit `nbb.edn` + package.json / nbb
   test entry — never `bb.edn`.
3. **`scripts/nbb_compat` may keep `babashka.*` namespace names** as temporary
   shims so existing nbb scripts need not rename requires in the same PR.
   Shim namespaces are not a license to invoke the `bb` binary.
4. **Task registry**: babashka `:tasks` has no nbb equivalent. Prefer:
   - `nbb.edn` for `:paths`
   - `nbb <script>.cljs` / `nbb -m <ns>` / shared `scripts/nbb-run-tests.cljs`
   - `package.json` scripts for multi-command surfaces
   - `clojure -M:…` / `npx …` only when the work itself requires JVM or Node
     tools — orchestrated without bb
5. **Wave plan** (land separately; do not force-push):

| Wave | Scope |
|------|--------|
| 0 | Superproject residual + ADR + `nbb-run-tests` + `verify-no-babashka` + CLAUDE.md |
| 1 | Shared script families: publish, edn-datomize fan-out, gen-shadow-cljs-edn |
| 2 | Mechanical scaffold `bb.edn` (~88–100 repos): sci-test + shell-only patterns |
| 3 | Large custom `bb.edn` (cloud-itonami, network-isekai, local-manimani, murakumo, etzhayyim/root, …) |
| 4 | Enforcement sweep; new-project-scaffold skill; final inventory → 0 |

6. **Historical ADR prose** is not mass-rewritten (lesson from closed PR #442).
   Live call sites (README, CLAUDE, skills, CI, scripts) are updated.

## Consequences

- Agents and humans use `nbb …` exclusively for repo scripts.
- Child repos gain `nbb.edn` where classpath is needed; delete `bb.edn` after
  the replacement test path is verified.
- Pin advances land per-repo (or small batches) via existing west/fleet rules.
- Optional CI/agent gate: `nbb scripts/verify-no-babashka.cljs` fails on new
  `bb.edn` / `#!/usr/bin/env bb` under tracked paths.

## Exit criteria

- [ ] `fd -t f 'bb.edn' orgs` → 0
- [ ] `fd -t f '\.bb$' orgs` → 0
- [ ] Superproject live paths: no `bb` command invocation
- [ ] Scaffold skill emits nbb-only tooling
- [ ] This ADR addendum records final inventory

## Wave 0 landing (this commit series)

- Policy ADR (this document + edn pair)
- CLAUDE.md: ops tooling is nbb-only (closes “bb が正本” wording)
- Residual: `migrate-etzhayyim-compat`, kami-webgpu audit test discovery,
  design-quality workflow/skill call sites
- Shared helpers: `scripts/nbb-run-tests.cljs`, `scripts/verify-no-babashka.cljs`,
  `scripts/bb_to_nbb_scaffold.cljs` (classifier + emitter for Wave 2),
  `scripts/gen-shadow-cljs-edn.cljs` (Wave 1 shared template)

## Progress (2026-07-17)

| Wave | Status |
|------|--------|
| 0 Superproject residual + helpers | **done** (merged to main) |
| 1 Shared `.bb` families | partial — `gen-shadow-cljs-edn.cljs` in superproject; publish/edn-datomize fan-out still open |
| 2 Scaffold `bb.edn` | **~45** `kotoba-lang/com-*` sci-test repos landed (emit + `npm test` green + server merge). More sci-test / shell-only remaining outside that set |
| 3 Large custom `bb.edn` | open (cloud-itonami, network-isekai, local-manimani, murakumo, etzhayyim/root, …) |
| 4 Enforcement | open |

**Follow-up required:** west pin advance for every landed child repo (child `main`
has the nbb files; superproject pins still point at pre-migration SHAs until
`gen-west-manifest.cljs --entry <name>` / fleet pin-advance).

## Progress addendum (2026-07-17 continued)

- Wave 2 pin advance: ~45 `kotoba-lang/com-*` pins verified and written to west.yml.
- Wave 3: `cloud-itonami` bb.edn → `scripts/tasks.edn` + `run-task.cljs` (164 shellable tasks) landed on main.
- Wave 3 batch: ~25 additional project-root bb.edn converted+merged; remaining project-root bb.edn ≈ worktree/fork/etzhayyim-root only.
- Helper: `scripts/bb_edn_to_nbb_tasks.cljs` for large bb.edn conversion.
