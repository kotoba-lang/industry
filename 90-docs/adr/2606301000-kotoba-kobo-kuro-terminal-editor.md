---
id: adr-2606301000-kotoba-kobo-kuro-terminal-editor
title: "ADR-2606301000: kotoba-lang/kobo と kuro を terminal/code editor の統合 workbench として設計する"
status: proposed
doc_type: adr
topic: kotoba-aiueos-dev-workbench
authoritative: true
last_verified: 2026-06-30
authoritative_for:
  - kotoba-lang と aiueos を使った terminal / code editor / agent workbench の repo 名と境界
  - kotoba が意味・証跡・言語層、aiueos が能力隔離された実行 surface を担う責務分担
  - 初期 MVP から durable agent workbench へ育てる実装ロードマップ
related:
  - 90-docs/adr/2606290740-aiueos-computer-surface-isolated-computer-use.md
  - 90-docs/adr/2606290930-kotoba-aiueos-capability-bridge.md
  - 90-docs/adr/2606280001-kotoba-code-durable-agent-loop.md
  - 90-docs/adr/2606280300-kotoba-rad-git-sovereign-repo.md
  - orgs/kotoba-lang/aiueos
  - orgs/kotoba-lang/kotoba-v2025
supersedes: []
superseded_by: []
---

# ADR-2606301000: kotoba-lang/kobo + kuro terminal/editor workbench

**Status**: proposed
**Date**: 2026-06-30
**Deciders**: Jun Kawasaki

## Decision

新しい repo 名は **`kotoba-lang/kobo`** とする。terminal の中核 repo/package は
**`kotoba-lang/kuro`** とする。

`kobo` は、kotoba-lang と aiueos を使って terminal、code editor、agent
execution、repo 証跡を 1 つの workbench にまとめる開発環境である。VS Code clone ではなく、
「コード・コマンド・実行・監査・権限」をすべて kotoba の datom/CID モデルに落とし、実行は
aiueos の capability surface で隔離する。

一言で:

```text
kobo = editor + terminal + agent loop + audit log
     = kotoba meaning/provenance × aiueos isolated execution

kuro = terminal session + command intent + effective grant + receipt
```

## Naming

推奨 repo 名:

| rank | name | use |
|---|---|---|
| 1 | `kotoba-lang/kobo` | 正式名。工房の短縮形で repo 名が軽い |
| 2 | `kotoba-lang/kuro` | terminal 中核。黒い terminal surface を明示 |
| 3 | `kotoba-koubou` | 公開説明名/alias。repo 名としてはやや長い |
| 4 | `kotoba-workbench` | 英語圏向け alias。説明性は高いが既視感が強い |
| 5 | `kotoba-shokunin` | agent/persona 色が強い。tool 全体名より worker 名向き |
| 6 | `kotoba-terminal` | scope が狭い。editor と durable agent を含めにくい |
| 7 | `kotoba-atelier` | 良いが kotoba/kami 系の和語 naming から少し外れる |

実装 namespace は `kobo.*` と `kuro.*` で切る:

- `kobo.workbench`: panes, buffers, terminal sessions, receipts
- `kobo.editor`: editor model + diagnostics + deterministic patching
- `kobo.grant`: capability intersection and denial explanation
- `kuro.terminal`: terminal session, command, receipt model
- future `kobo.agent`: durable agent supervisor
- future `kobo.repo`: kotoba-rad/git bridge

## Product Shape

最初の画面は landing page ではなく workbench:

```text
┌─────────────────────────────────────────────────────────────┐
│ repo / branch / active grant / agent budget / audit status  │
├───────────────┬───────────────────────────────┬─────────────┤
│ file tree     │ editor tabs                    │ graph/audit │
│ symbols       │ kotoba/clj/edn/code            │ facts       │
│ changes       │ inline diagnostics             │ grants      │
├───────────────┴───────────────────────────────┴─────────────┤
│ terminal tabs: shell / aiueos run / tests / agent transcript │
└─────────────────────────────────────────────────────────────┘
```

Primary workflows:

1. open repo, inspect status, search files
2. edit code with kotoba-aware diagnostics and LSP
3. run commands in capability-scoped terminals
4. admit generated code through aiueos before execution
5. run tests/builds with receipts
6. let a durable agent make bounded ticks, stop at policy/human gates
7. persist every meaningful action as kotoba facts and CID-addressed receipts

## Responsibility Split

| layer | responsibility |
|---|---|
| UI | panes, editor, terminal tabs, diff, graph/audit views |
| `kotoba` | source as data, EDN/datom facts, CID receipts, repo provenance, semantic diagnostics |
| `kotoba-rad/git` | Git object bridge, repo identity, signed refs, private object metadata |
| `aiueos` | verify/admit/run components, capability intersection, surface providers, audit |
| `kototama` | host-facing execution contract for compiled kotoba/clj components |
| durable loop | bounded agent ticks, checkpoint, lease, budget, recovery |

Invariant:

```text
editor intent -> kotoba fact/patch -> aiueos grant check -> isolated effect -> receipt
```

The UI must not call host filesystem, shell, network, secrets, or desktop automation as ambient authority.
Those are explicit capabilities resolved through aiueos providers.

## Terminal Design

The terminal is not a raw host shell by default. It is an aiueos surface.

Terminal modes:

| mode | backing | default grants | use |
|---|---|---|---|
| `terminal-safe` | sandboxed process/container | repo read, tmp write, no secrets, limited net | normal commands, tests |
| `terminal-build` | sandboxed build surface | repo read/write, cache, toolchain, bounded net | builds and package installs |
| `terminal-host` | host shell | explicit signed opt-in | emergency escape hatch |
| `terminal-agent` | durable agent loop | same as safe/build but tick-scoped | AI-driven changes |

Every terminal session has:

- `session-id`
- repo root CID / Git commit
- command argv and cwd
- effective grant
- environment manifest, not raw inherited env
- stdout/stderr stream hashes
- exit code
- produced file patch CIDs

## Editor Design

The editor treats files as projections of repo facts, not merely buffers.

Core editor capabilities:

- syntax support for `.edn`, `.clj`, `.cljc`, `.kotoba`, manifests, policies
- kotoba-aware diagnostics: malformed EDN, unknown `:aiueos/*`, unresolved capability, unsafe form
- inline grant view: why this file/component can or cannot run
- diff as first-class patch facts
- command palette backed by declared commands, not arbitrary JS callbacks
- LSP bridge for existing languages, wrapped as a capability-scoped service

LLM editing rule:

```text
LLM output = proposed patch
deterministic validator = applies or rejects
aiueos = gates any execution caused by the patch
```

The model never mutates raw repo state directly.

## Agent Design

`kobo.agent` follows ADR-2606280001:

```text
lease/tick -> load checkpoint -> run bounded graph segment
           -> persist events/checkpoint/budget -> governor decision
           -> continue | sleep | interrupt | stop
```

Agent tools are workbench primitives:

- `read-file`
- `rg`
- `apply-patch`
- `format`
- `test`
- `aiueos-verify`
- `aiueos-run`
- `git-status`
- `git-diff`
- `commit-proposal`

Privileged tools require a governor decision and a grant intersection:

```text
effective = kotoba_grant ∩ aiueos_policy ∩ manifest.imports ∩ surface.offered ∩ budget
```

## Repo Layout

Recommended initial layout:

```text
kobo/
  README.md
  docs/
    adr/
    design/
  src/kobo/
    workbench.cljc      # panes, buffers, terminal sessions, receipts
    editor.cljc         # buffer model, diagnostics, patch contracts
    grant.cljc          # grant intersection and denial explanation
  test/kobo/

kuro/
  README.md
  src/kuro/
    terminal.cljc       # terminal modes, command intent, receipt facts
  test/kuro/
```

Use CLJC for the model and deterministic validation. Host-specific PTY/container/microVM
plumbing remains outside the portable core and is injected through aiueos capabilities.

## MVP

M0 should be intentionally small:

1. open a local Git repo
2. show file tree, editor, terminal, audit pane
3. run `rg`, `cargo test`, or project commands through `terminal-safe`
4. record command receipt as EDN
5. verify an aiueos manifest and show loud denials in the editor
6. apply a deterministic patch and show diff

M1:

1. add `terminal-build`
2. add kotoba/aiueos grant visualization
3. add LSP bridge
4. add `aiueos admit` for generated code
5. add agent tick loop with checkpoint and budget

M2:

1. signed repo identity via kotoba-rad
2. CID-addressed run receipts
3. remote isolated `computer` surface for browser/UI verification
4. private repo object grants
5. shareable audit bundle for PR/review

## Non-goals

- Do not build a general VS Code extension host first.
- Do not expose the user's host shell as the default terminal.
- Do not let generated code inherit host env/secrets.
- Do not make aiueos a hidden implementation detail; grants and denials are product UI.
- Do not store agent state only in chat transcripts or process memory.

## Consequences

- The repo has a clear product identity (`kobo`) without narrowing itself to terminal-only.
- Existing kotoba and aiueos ADRs stay intact: kotoba owns meaning/provenance, aiueos owns authority.
- The workbench becomes a practical entry point for kotoba-lang because users see terminal/editor behavior
  before they need to understand the whole semantic stack.
- Security UX becomes visible: a denial is not an error modal but an explainable part of the editor.
