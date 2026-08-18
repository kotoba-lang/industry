# Phase 5C — code-independent reconstruction, second run (2026-08-13)

A second reconstruction of the same 91-claim set from the same Package A v1.1
method contracts, written independently of the run in `../return/`. The point of
having two is stated in the collaborator's request of 2026-08-03: an answer-key
audit cannot exclude AI-specific errors, so the analysis policy alone was given
and the code was generated from zero.

**Result: the two runs agree on 90 of 91 claim values.**

## What is here

| path | contents |
|---|---|
| `KAWASAKI_PHASE5C_VERIFICATION_REPORT_2026-08-13.pdf` | Japanese verification report — background, procedure, results, findings, current state (7pp) |
| `01_INDEPENDENT_ARTIFACT_RESULTS.tsv` | all 91 claim values + 1 additional |
| `04_INPUT_HASH_AUDIT.tsv` | 57/57 input SHA-256 verification |
| `05_METHOD_DEVIATION_LOG.tsv` | 22 entries (14 declared / 5 no-deviation / 2 open / 1 upstream) |
| `08_AI_ASSISTANCE_LOG.tsv` | AI assistance and data-handling disclosure |
| `lock-submission/` | the three items the result-lock protocol sends to the custodian |

## What is deliberately not here

- **The encrypted return archive** (`KAWASAKI_PHASE5C_INDEPENDENT_RETURN.7z`,
  25 MB) and its plaintext tree (71 files, 26 MB). Large binaries do not go into
  git history in this workspace; both are held in the local controlled
  environment. The archive's SHA-256 is recorded in `lock-submission/`.
- **Its passphrase**, which is in the OS keychain and is never written to code,
  logs, filenames, reports, or command history.
- **Any individual-level row.** The staged files were scanned against all 91
  pseudonymous case identifiers before commit: zero hits.

## Reading the numbers correctly

Of the 91 claims, **6 are L1**, 80 are L2, 5 are L3. Only the six Figure 1
claims are independent recalculation from analysis-ready data; the L2 claims
verify derivation from the provider checkpoint, so an error upstream of that
checkpoint is reproduced faithfully and is not detectable here. Completing all
91 claims is not confirmation that the underlying analysis is correct.

This is a **co-author independent verification with result lock**, not an
external audit. The verifier is an author of the manuscript.

## State

The reconstruction is complete and the archive hash is fixed. The custodian has
**not** yet recorded the hash, so Package B has not been opened and **the
comparison against the paper has not been performed.**

## The one disagreement between the two runs

`SUPPTABLE_S1_CATEGORY_COUNTS` — this run counts all 26 rows (8/12/6); the run
in `../return/` counts the 17 lead rows (5/7/5). The reasoning for the value
returned here is in `05_METHOD_DEVIATION_LOG.tsv`, and the lead-only value is
returned alongside as a separate claim so it can be substituted without
recomputation.

Two findings recorded here — the Figure 1 suggestive-variant-set discrepancy
(27 vs 26, `rs376128944`) and the Figure 2 label-casing alignment — were
surfaced only by cross-checking the two runs, and had been missed by this run
alone.
