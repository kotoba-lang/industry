# Verifier attestation

**DRAFT — not yet signed. Do not sign until all 91 claims are complete and the
return archive is ready for hash lock.**

I attest that I reconstructed the in-scope artifacts using independently
implemented code and Package A inputs only; did not access Package B or current
canonical rendered artifacts before result lock; and have disclosed every method
deviation and source limitation.

## Qualification to the AI/cloud clause

The template clause reads "did not upload raw or individual data to an external
AI/cloud service." **That clause cannot be signed unqualified.** The accurate
statement is:

> No provider workbook rows, sample tables, or restricted data were uploaded to
> an external AI or cloud service, **except** that during a structure check of
> `DS_CASE_PGS_CANONICAL` (`IQ_PGS_caseonly.md`) a `head -6` placed the header
> and 5 of 91 pseudonymous individual case rows (ID and PRS value) into the
> context of an external AI service (Claude Opus 5, Anthropic). No further
> individual rows were exposed, and the published summary artifact was scanned
> and confirmed to contain none of them.

Full detail is in `08_AI_ASSISTANCE_LOG.tsv`. The disclosure is made here rather
than omitted because the attestation is the document a reader relies on.

## Independence — what this does and does not establish

This exercise establishes **implementation independence** (the reconstruction
code was written from the method contracts, not derived from the original
analysis code) and **result blinding against Package B** (the answer key was not
accessed before lock).

It does **not** establish third-party independence. The verifier is an author of
the manuscript. `00_README_FIRST.md` also states the exercise is not fully
result-blinded, since analysis-ready summaries and provider checkpoints contain
numeric fields, and the verifier has prior knowledge of the manuscript's reported
values. This work should be described as a **code-independent internal
reconstruction with result lock**, not as an independent audit.

## Reconstruction level ceiling

Only FIG1 is L1 (independent recalculation from analysis-ready data). FIG2,
FIG3, FIG4, TABLE1, TABLE2 and SuppTable S1 are L2 and verify derivation from
the provider output checkpoint only — an error upstream of the checkpoint is
reproduced faithfully and cannot be detected. SuppFig S1 is L3 and establishes
visual semantics only. Completion of all 91 claims must not be described as
verification of all reported results.

---

Verifier: ____________________  Date: ____________________

Return archive SHA-256: _________________________________________________
