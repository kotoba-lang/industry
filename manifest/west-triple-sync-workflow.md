# West triple-plane sync (GitHub · local · west)

Machine-readable: [`manifest/west-triple-sync-workflow.edn`](west-triple-sync-workflow.edn)  
ADR: `90-docs/adr/2607173200-github-local-west-triple-sync.edn`  
CLI: `nbb scripts/west-triple-sync.cljs`

## Goal

For a **managed** project set, keep three planes consistent and current:

| Plane | Role |
|---|---|
| **GitHub** | Content upstream (default-branch commits) |
| **west** | `path` + `revision` pin (`repos.edn` + `gen-west-manifest --entry`) |
| **local** | Working tree at `orgs/<org>/<repo>` |

Completion for the scope:

1. `nbb scripts/west-orphan-audit.cljs --blocking` exits 0 (for targets in scope)
2. Pins for touched entries pass `verify-west-pins` (reachable from default branch; no unpushed HEAD)
3. Every `:local/root` consumer of a managed project can resolve the directory after checkout

## Scopes (blast radius)

| Scope | What is managed |
|---|---|
| **`blocking`** (default) | Projects from `:local-root-broken` (fresh-checkout breakers) |
| **`managed`** | West projects that **already** have a local checkout (pin/local freshness only) |
| **`names`** | Explicit `--names crm,hil` (basename or `orgs/org/name`) |

**Not** managed by default: all 1000+ west paths without local checkout (sparse is normal), `personal/*`, path-override leftovers, worktree scratch.

## Commands

```bash
# Plan only (safe default)
nbb scripts/west-triple-sync.cljs plan --scope blocking
nbb scripts/west-triple-sync.cljs plan --names crm

# Apply (clone / register / ff-only pull / --entry pin)
nbb scripts/west-triple-sync.cljs apply --scope blocking
nbb scripts/west-triple-sync.cljs apply --names crm
nbb scripts/west-triple-sync.cljs apply --scope managed --no-pin-advance

# Verify gates
nbb scripts/west-triple-sync.cljs verify --scope blocking
```

## Phase order

1. **discover** — `west-orphan-audit --edn` + local∩west inventory  
2. **ensure-github** — remote must exist (`gh`); do not `gh repo create` here  
3. **ensure-local** — `git clone --depth 1` if missing (skip if dirty would be overwritten — N/A for missing dir)  
4. **ensure-west** — surgical `:extra-projects` insert + `gen-west-manifest.cljs --entry`  
5. **align-latest** — `fetch` + `merge --ff-only` when clean; pin advance only if HEAD is on origin  
6. **verify** — orphan blocking + pin verify  

## Hard rules

- No wholesale `gen-west-manifest` commit (ADR-2607022900).
- No re-register of path-override **old** paths.
- No force-push / rebase to “fix” divergence.
- No pin of unpushed local HEAD.
- Dirty local trees: skip + report (preserve WIP).
- Dep retargets (`langchain-clj` → `langchain`) are **reported**, not auto-edited.

## Relation to other runbooks

| Tool | Role |
|---|---|
| `new-project-scaffold` | Create + first registration |
| `west-triple-sync` | Ongoing / repair three-plane alignment |
| `git-cleanup-conflict` | Stash/branch/PR cleanup; inventory includes orphan audit |
| `west-orphan-audit` | Classification only |
| fleet CI reconcile | Absorbs west.yml into fleet-db (do not hand-edit fleet-db) |
