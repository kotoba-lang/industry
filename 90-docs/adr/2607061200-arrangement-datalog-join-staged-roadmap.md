# ADR-2607061200: `arrangement.datalog` — conjunctive join now, full Datalog as a staged roadmap

## Status

Accepted (stage 1 implemented)

## Context

`kotoba-lang/kotobase`'s completion review found that `arrangement.query`/
`kotobase-peer.core/q` (the query layer over the 4-index Arrangement,
formerly `kqe`+`quad-store`) is **single triple-pattern only** — one
`[s p o]` pattern per call, no variable binding across clauses, no
join, no negation, no aggregation, no recursive rules. Both repos'
READMEs already flagged this honestly ("Full Datalog fixpoint / SPARQL
BGP evaluation" as explicitly out of scope), but no scaffolding
(clause-vector input shape, join algorithm, test fixtures) existed for
closing the gap.

The owner's stated target is **full Datalog**: recursive rules,
negation, and aggregation, matching Datomic's `:find`/`:where`/`:rules`
surface. That is a multi-week effort spanning fixpoint computation and
stratified-negation safety analysis — too large to land correctly in
one sitting without its own design review. Rather than either (a)
silently shipping only a fraction of "full Datalog" without saying so,
or (b) blocking all progress on the full design, this ADR stages the
work and lands the foundation stage now.

## Decision

**Stage 1 (this ADR, implemented): conjunctive multi-clause join.**
New `arrangement.datalog/q`:

```clojure
(dl/q db {:find '[?name] :where '[[?s "role" "admin"] [?s "name" ?name]]} visible?)
```

- Clause = `[e a v]`; each position is a bound value, a logic variable
  (symbol starting with `?`), or the wildcard `_`.
- Algorithm: nested-loop join. Starting from `#{{}}` (one empty
  binding), each `:where` clause is resolved against `db` with its
  still-unbound variables substituted as wildcards (`nil`) and its
  bound variables substituted as concrete values; each matching row
  extends the binding, unifying newly-bound variables and dropping
  binding candidates that conflict with an already-bound variable.
- Reuses `arrangement.query/query`'s existing 4-index routing
  unchanged — this is a thin layer on top, not a rewrite.
  `visible?` is required and threaded per-clause, same convention as
  `arrangement.query` itself (ADR-2607050500).
- `kotobase-peer.core/query` wraps this for the peer library; the
  existing single-pattern `kotobase-peer.core/q` is untouched (no
  breaking change for existing callers).

Verified: `arrangement` 23 tests/53 assertions green (8 new join
tests: shared-variable join, 3-clause chain join, cartesian product
with no shared variable, conflicting-binding empty result, self-join
via a repeated variable within one clause, `visible?`-required,
`visible?` applied per-clause).

**Stages 2-4 (roadmap, NOT implemented by this ADR):**

2. **Negation** — `[not [e a v]]` clauses: anti-join, drop bindings
   for which the negated clause has any match. Composes on top of
   stage 1's binding-set representation without changing it.
3. **Aggregation** — `:with` + aggregate functions (`count`, `sum`,
   `max`, ...) applied during `:find` projection, after the join
   completes.
4. **Recursive rules** — `:rules` (Datomic-style rule definitions) +
   naive or semi-naive fixpoint evaluation. The most complex stage:
   if combined with stage 2's negation, requires a stratification
   safety check (a rule can't recurse through its own negation) before
   evaluation, to guarantee the fixpoint is well-defined.

Each stage is additive over the previous one's output shape (a set of
variable bindings) — no stage requires re-deriving stage 1's join.

## Consequences

- (+) `kotoba-lang/kotobase`'s "does it support Datomic-style query?"
  gap now has a real, tested answer for the common case (conjunctive
  multi-clause lookup) instead of only single-pattern routing.
- (+) The staged plan is written down before stage 1 ships, so "full
  Datalog" isn't silently descoped — stages 2-4 are a tracked
  follow-up, not a surprise gap discovered later.
- (−) Stages 2-4 are unscheduled; a query mixing negation or
  aggregation today gets no error — it simply has no syntax for those
  yet (`:where` clauses are always positive triple patterns).

## Follow-up

Implement stage 2 (negation) next, since stage 4 (recursion) needs
stage 2's stratification analysis as a prerequisite regardless of
which stage 3 (aggregation) lands before.

## One-line summary

**`arrangement.datalog/q` adds Datomic-shaped conjunctive multi-clause
join (`:find`/`:where`, nested-loop join, variable unification) over
`arrangement.query`'s existing single-pattern router; negation,
aggregation, and recursive rules are staged as tracked follow-ups, not
implemented here.**
