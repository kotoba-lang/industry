# split-namespace

Turn one Clojure namespace into one repository per definition, plus a namespace
that assembles them. The unit of a library is the definition
(ADR-2609091200); a cycle of mutually recursive definitions is one unit,
because a cycle has no acyclic split.

Twenty-three namespaces went through this on 2026-09-09, producing 471
definition repositories under `kotoba-lang`.

## The pipeline

```bash
S=scripts/split-namespace
SRC=orgs/kotoba-lang/<fam>/src/kotoba/lang/<fam>.cljc

nbb $S/extract.cljs  "$SRC"                        > /tmp/k<fam>.graph
nbb $S/scc.cljs      /tmp/k<fam>.graph             > /tmp/k<fam>.scc
cat /tmp/k<fam>.graph /tmp/k<fam>.scc              > /tmp/k<fam>.combined

nbb $S/generate.cljs "$SRC" /tmp/k<fam>.combined /tmp/k<fam> \
    kotoba.<fam> <fam>- <fam> kotoba.lang.<fam>
```

`generate.cljs` writes `<out>/REPOS.tsv` — the definition-name to repo-name
mapping. **Read it; do not reimplement it.** Every shell copy of that mapping
has diverged from the generator's at least once (one did not kebab-case
CamelCase and looked for `fs-IAsyncFilesystem`; another did not drop the leading
`I` and looked for `fs-iasync-filesystem`), and both failed as "no such
directory" — which is exactly what a definition that was deliberately not
generated also looks like.

If the namespace declares a protocol, `unfork.cljs` rewrites the source to name
the split's protocol instead of declaring a second one. See "One protocol"
below.

## Verifying a split

Run the family's own suite twice against identical assertions — once resolving
through the source namespace, once through the assembled facade — and require
the same numbers. **Then break the facade and watch that run go red.** A control
that quietly fails to break anything returns exactly what a passing control
returns: two controls in this work stayed green because the substitution never
applied (an unescaped `?` in the pattern) or because the suite never reaches the
name that was broken.

Break a name the suite actually calls, counted:

```bash
grep -ohE '<alias>/[a-z0-9<>*!?-]+' <test-files> | sed 's|<alias>/||' \
  | sort | uniq -c | sort -rn | head -1
```

## What the facade does not carry, and why

Each of these was measured, not reasoned about, and the generated facade names
its own omissions in its docstring.

| Not re-exported | Because |
| --- | --- |
| **value vars** | `(def x other/x)` copies. Harmless for a function; for a value it makes `with-redefs` through the facade a SILENT no-op. On `kotoba.lang.edn` three assertions passed against nothing at all. |
| **protocols** | A protocol's identity is what `extend-type` and `reify` dispatch on. A copy makes an implementation silently extend nothing. |
| **record types** | A type is not a var: `(:require [ns :refer [RNG]])` fails at load with "RNG does not exist". The constructors `->RNG` / `map->RNG` are functions and cross fine. |
| **macros** | A macro var cannot be copied at all: `(def are other/are)` is a compile error, "Can't take value of a macro". A forwarding `defmacro` needs `:require-macros` gymnastics in ClojureScript. |

## One protocol, not two

A protocol split into its own repo is a **different protocol** from the one the
source namespace declares (ADR-2609091900). That was a live defect, not a
theoretical one: `kotoba.lang.fs-host/host-filesystem`, a real JVM filesystem in
the same repository, answered through `kotoba.lang.fs` and failed through
`kotoba.fs` with "No implementation of method: :exists?".

`unfork.cljs` makes the source namespace name the split's protocol. The methods
must be **interned**, not `:refer`red — a referred var is a mapping, and a
qualified reference like `fs/read` to a merely referred var does not compile.

**Known limit, measured:** a copied protocol var works for `reify` and NOT for
`extend-type`. Every implementation of these protocols in this workspace uses
`reify` — 46,357 files scanned, 69 `reify`, zero `extend-type`,
`extend-protocol` or `extend` — which is why this is a repair rather than a
trade. A consumer needing `extend-type` extends the protocol in its own repo.

## A name is not bent to fit a host

Every protocol drops its leading `I` when it moves into its own repo. There is
no exception for names `java.lang` happens to occupy. On the JVM a namespace is
handed 96 `java.lang` names before it says anything, `Process` is one, and
`(defprotocol Process ...)` is then "Expecting var, but Process is mapped to
class java.lang.Process".

Keeping the `I` would put an accident of one host — the host this workspace
ranks last — into the identity of a definition. So the name stays uniform and
the generator emits, in the one repo that needs it:

```clojure
#?(:clj (do (ns-unmap *ns* 'Process)))
```

which is the principle the library is built on: a component touches only what it
was granted. `java.lang.Process` stays reachable by its full name — measured.
The 96 names came from `(ns-imports (create-ns (gensym)))` on Clojure 1.12.0,
not from memory; `java.io.Reader` and `java.io.Writer` are not among them, which
is why `kotoba.io`'s `Reader` and `Writer` never needed this.

`generate.cljs` refuses if a protocol name was kept as-is to dodge a collision.

## Every refusal, and what taught it

The tools stop rather than produce something that looks right. Each of these
came from a failure that was silent at generation, commit and push, and showed
up only at load — or never.

**extract.cljs**

- an unknown definition form → `UNSEEN`. Pointed at `kotoba.lang.fs` the first
  version silently dropped two `defprotocol` forms: 16 in the file, 14 in the
  output, no complaint.
- a definition name the reference scan cannot PRODUCE → `BLIND`. The scan only
  started a token at a letter, so `->ymd` was invisible, `->iso8601` came out
  with no edge to it, and its repo did not compile.

**generate.cljs**

- a graph file with no `DEF` lines. Passing it the scc output alone made it
  write 19 repos with zero dependencies each and report success.
- two definition names that slug to one repo.
- a repo name the host will not take. `pos<` and `pos>=` were reported as
  `create-failed` and the family went out ten repos of twelve, leaving the
  facade naming two repos that do not exist.
- a repo that requires a namespace its own `deps.edn` does not provide.
  `kotoba-lang/fs-split` could not be loaded from its own main.
- a definition the graph names but whose body it cannot read. The form list
  lives in two files and they drifted; the generator died on a null.
- a source repository that does not pin something the split needs. Resolving to
  a local checkout's tip instead silently upgraded `fs` and `io` under
  `kotoba.lang.store` and cost its suite ten assertions.
- a record type named directly — `(RNG. x)`, `(instance? RNG x)` — which needs
  `:import` and has no portable cljc form.
- a protocol name kept as-is to dodge a host collision.

## What is not here

The publish / update / land drivers are shell, and this workspace does not take
new `.sh` (owner instruction 2026-07-14). Their steps are ordinary and stated
above; the parts that were easy to get wrong are the mapping (`REPOS.tsv`), the
pin source (the source repository's own `deps.edn`), and never force-pushing —
a definition repo that already exists takes a commit on top of its history.
