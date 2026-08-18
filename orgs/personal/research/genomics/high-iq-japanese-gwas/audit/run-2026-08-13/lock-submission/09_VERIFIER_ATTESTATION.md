# Verifier attestation

I attest that I reconstructed the in-scope artifacts using independently
implemented code and Package A inputs only; did not access Package B or current
canonical rendered artifacts before result lock; and have disclosed every
method deviation and source limitation in `05_METHOD_DEVIATION_LOG.tsv`.

## Independence

All analysis logic was written from the M01-M07 method contract text. No
canonical plotting or analysis implementation, and no rendered current figure or
table, was available or consulted. The only third-party analysis component is
the PGEN file-format reader (pgenlib); the LD clumping algorithm, the Clopper-
Pearson and exact binomial procedures, the weighted directional statistic, the
KDE and histogram construction, and lambda_GC were all implemented here.

The canonical ROC panel rasters supplied in Package A were used, as M06
directs, solely as aggregate curve checkpoints for L3 visual reassembly.

## Data handling disclosure

Restricted individual-level data was not uploaded in bulk to any external
service. One limited exposure is recorded in `08_AI_ASSISTANCE_LOG.tsv` and is
repeated here so it is not missed: during initial schema inspection, before the
content type of `DS_CASE_PGS_CANONICAL` had been established, a 3-line head of
`IQ_PGS_caseonly.md` was displayed, exposing 2 of the 91 pseudonymous case rows
(pseudonymous identifier and PRS value). No further individual rows entered the
assistant context; all subsequent handling of the 91 case scores and of the
provider workbook was performed by locally executed code emitting aggregates
only. No provider workbook row was exposed. The project owner should decide
whether this requires any further action under the Package A handling rules.

No individual-level row is included anywhere in this return package.

## Scope

Provider-level rerun from the 41,528 individual control genotypes was not
performed and is not claimed. Figure 4 and Table 2 are stated as L2
reconstruction from the provider output checkpoint. Supplementary Figure S1 is
stated as L3 visual reassembly; model-level ROC recalculation is not claimed.

## What this establishes, and what it does not

This exercise is a **co-author independent verification with result lock**. It
establishes two things and not a third.

It establishes **implementation independence**: the reconstruction code was
written from the M01-M07 method contracts, not derived from, adapted from, or
compared against the original analysis code, which was never available. It also
establishes **result blinding against Package B**: the answer key was not opened
before lock. During this work a submitted supplementary table was found to exist
in the surrounding research repository, outside the Package A input set; it was
deliberately not opened, because reading it before hash lock would defeat the
blinding this attestation asserts.

It does **not** establish third-party independence, and does not claim to. The
verifier is an author of the manuscript and has prior knowledge of the reported
values. This should be described as a co-author independent reconstruction, not
as an external audit.

## Reconstruction level ceiling

Of the 91 in-scope claims, **6 are L1**, **80 are L2**, and **5 are L3**. Only
the six FIG1 claims are independent recalculation from analysis-ready data.

The 80 L2 claims verify faithful derivation from the provider output
checkpoint. **An error upstream of that checkpoint is reproduced faithfully and
cannot be detected at this level.** The 5 L3 claims establish visual semantics
only. Completion of all 91 claims must therefore not be read as confirmation
that the underlying analysis is correct; it confirms that the artifacts follow
from the checkpoints they declare, and that the six L1 quantities recompute.

## Corroboration by a second independent reconstruction

An earlier independent reconstruction of the same claim set was performed from
the same method contracts in a separate session with a separately written
implementation. Cross-checking the two returns, **90 of 91 claim values agree**.
The single substantive difference is SUPPTABLE_S1_CATEGORY_COUNTS, where the
two runs read the scope of "population-category row counts" differently; the
reasoning for the value returned here, and the alternative value, are both in
the deviation log. Two findings recorded in this package -- the FIG1
suggestive-variant-set discrepancy and the FIG2 label-casing alignment -- were
surfaced by that cross-check and had been missed by this run alone.

Verifier: ____________________  Date: ____________________

Return archive SHA-256: bbc7e003f4e3c0e3c9fb59cd37ce571f968cc2cf848f03ec2d6a775697a2c90c
Return archive filename: KAWASAKI_PHASE5C_INDEPENDENT_RETURN.7z
