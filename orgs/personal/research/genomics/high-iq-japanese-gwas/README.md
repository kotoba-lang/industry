# High-IQ Japanese GWAS — submission set + independent reconstruction package

Working directory for the manuscript **"Ancestry-enriched and shared polygenic
signals associated with high IQ in a Japanese extreme-phenotype cohort."**

| | |
|---|---|
| Authors | Jun Kawasaki<sup>1,2</sup>, Shun Nogawa<sup>3</sup>, Kazuki Tainaka<sup>1,2</sup> |
| Affiliations | <sup>1</sup> GFTD AI Research Lab / Gftd DAO, Tokyo · <sup>2</sup> Dept. of System Pathology for Neurological Disorders, Brain Research Institute, Niigata University · <sup>3</sup> Genequest Inc., Tokyo |
| Corresponding | Kazuki Tainaka (kztainaka@bri.niigata-u.ac.jp) · Jun Kawasaki (jun@gftd.ai) |
| Submitted to | Scientific Reports (Nature Portfolio), Article |
| Cover letter date | 31 July 2026 |
| Ethics | NPO National Clinical Research Council, Ref. No. 521 (12 April 2024) |
| Public repository | https://doi.org/10.5281/zenodo.21711574 |

**Design.** Case-control GWAS of 91 Japanese individuals meeting a prespecified
high-range cognitive criterion (CAMS ≥ 140) against 41,528 unphenotyped Japanese
commercial genetic-testing controls; 41,619 individuals and 362,797 directly
genotyped autosomal variants after QC. Genotyping on Illumina Infinium Global
Screening Array-24 v3.0. Cross-population comparison against the Coleman et al.
European intelligence GWAS; polygenic scores via PRSice2 on the Savage et al.
base study.

---

## Layout

```
high-iq-japanese-gwas/
├── README.md                        ← this index (plain git)
├── .gitattributes                   ← data-handling policy (plain git)
├── submission/                      ← what was submitted, 31 Jul 2026 [annexed]
│   ├── MS.docx                      manuscript
│   ├── SI.docx                      supplementary information
│   ├── Cover.docx                   cover letter + suggested reviewers
│   ├── Supplementary_Table_S1.csv   26 variants × 23 annotation fields
│   └── ja/                          unofficial Japanese reference translations
│       ├── MS-ja.md
│       ├── SI-ja.md
│       └── Cover-ja.md
└── reconstruction-package-a-v1.1/   ← Package A v1.1, as received
    ├── 00_README_FIRST.md
    ├── 01_SCOPE_AND_AUTHORIZATION/  authorization, scope, result-lock protocol
    ├── 02_ARTIFACT_INVENTORY/       artifact → level → dataset maps
    ├── 03_METHOD_CONTRACTS/         M01–M07 + metric definitions
    ├── 04_DATA_PAYLOAD/             26 datasets [annexed, restricted]
    ├── 05_REQUIRED_OUTPUT_SCHEMAS/  return templates
    ├── 06_RETURN_PACKAGE_SPECIFICATION/
    ├── PACKAGE_A_MANIFEST.tsv
    └── SHA256SUMS.txt
```

## Japanese reference translations

`submission/ja/` holds unofficial Japanese translations of the three documents
Tainaka-sensei assembled (MS, SI, Cover), for internal reference only. **The
submitted English `.docx` files are authoritative**; where wording differs, the
English wins. Numbers, statistics, gene symbols, and rsIDs are carried across
verbatim. The manuscript's inline equations are Word equation objects that do
not survive text extraction, so those spots are marked 〔数式は原文参照〕 rather
than reconstructed from guesswork. Being under `submission/**`, these files are
annexed like the rest of the unpublished manuscript set.

## Data handling — read before touching `04_DATA_PAYLOAD/`

The payload contains **pseudonymous individual case PGS**
(`IQ_PGS_caseonly.md`, 91 case rows) and an **internal Genequest provider
workbook** (`Nogawa_analysis_results_20260526.xlsx`). Per the package's
`01_SCOPE_AND_AUTHORIZATION/DATA_SECURITY_AND_AI_RULES.md`, these must not reach
an external AI tool, public paste service, public repository, or external/public
cloud service. AI assistance may use only schemas, field definitions, dummy data,
and aggregate error summaries.

How that is enforced here:

- This lives in `com-junkawasaki/root`, a **private** repository.
- `orgs/personal/` is a **git-annex** subtree: data files are committed as
  symlinks to annex keys, never as plaintext git blobs. Annex content is
  encrypted (`encryption=hybrid`) before it reaches the `b2` or `ipfs` special
  remotes.
- The parent `orgs/personal/.gitattributes` exempts `**/*.md` from annexing so
  human docs stay browsable. **That exemption is unsafe here** — the canonical
  case-PGS dataset ships as a `.md`. The local `.gitattributes` overrides it and
  forces the whole of `04_DATA_PAYLOAD/**` to annex regardless of extension.

Verify the routing before adding new payload files:

```bash
git check-attr annex.largefiles -- <path>   # restricted → "anything"
```

Method contracts, scope docs, and this index stay in plain git — they describe
method and structure only, which the package rules explicitly permit.

Fetch / release payload content locally:

```bash
git annex get  orgs/personal/research/genomics/high-iq-japanese-gwas
git annex drop orgs/personal/research/genomics/high-iq-japanese-gwas
```

## Reconstruction scope

Package A supports **code-independent reconstruction with canonical rendered
outputs and the answer key withheld**. Package B (the answer key) is
custodian-only and is *not* released until the independent return archive and
its SHA-256 are hash-locked — see `01_SCOPE_AND_AUTHORIZATION/RESULT_LOCK_PROTOCOL.md`.
Implement reconstruction code independently; do not request Package B first.

| Artifact | Manuscript reference | Level |
|---|---|---|
| `FIG1` | Main Figure 1 — Manhattan + QQ | **L1** independent recalculation from analysis-ready data |
| `FIG2` | Main Figure 2 — population frequency and LD loci | L2 from provider checkpoint |
| `FIG3` | Main Figure 3 — cross-population | L2 from provider checkpoint |
| `FIG4` | Main Figure 4 — PGS validation | L2 from provider checkpoint |
| `TABLE1` | Main Table 1 | L2 from provider checkpoint |
| `TABLE2` | Main Table 2A / 2B | L2 from provider checkpoint |
| `SUPPTABLE_S1` | Supplementary Table S1 | L2 from provider checkpoint |
| `SUPPFIG_S1` | Supplementary Figure S1 — ROC | **L3** visual reassembly from aggregate raster |

L2/L3 artifacts must never be described as L1. Out of scope: provider-level
rerun from the 41,528 individual control genotypes, recovery of full-cohort
case/control mapping or PC1–PC10, reproduction of Genequest-internal commands,
and reintroduction of removed artifacts (PCA figure, signed-Z scatter,
downsampling figure, obsolete Figure 2A, older 18-locus set). A missing
out-of-scope provider source is **not** a missing-input failure when the current
checkpoint is present — see `04_DATA_PAYLOAD/00_INDEX/SOURCE_LIMITATIONS.tsv`.

Returns follow `05_REQUIRED_OUTPUT_SCHEMAS/` (results, file manifest, input-hash
audit, method-deviation log, software environment, visual-semantic QA, AI
assistance log) and `06_RETURN_PACKAGE_SPECIFICATION/`.

## Dataset index

25 datasets; full table in `04_DATA_PAYLOAD/00_INDEX/DATASET_MASTER_INDEX.tsv`,
field definitions in `DATA_DICTIONARY.tsv`, dependencies in
`ARTIFACT_DEPENDENCY_GRAPH.tsv`.

| Group | Datasets |
|---|---|
| `01_JAPANESE_GWAS_SUMMARY` | `DS_GWAS_CURRENT` (privacy-filtered summary stats) |
| `02_TABLE1_MAC_AF_SOURCES` | `DS_TABLE1_CHECKPOINT` |
| `03_LD_LOCI_AND_POPULATION_FREQUENCIES` | `DS_FIG2_FREQUENCY_CHECKPOINT`, `DS_LD_PRIMARY`, `DS_LD_SENSITIVITY`, `DS_EAS_LD_{PGEN,PVAR,PSAM}` (1000G EAS phase 3 GRCh37) |
| `04_CROSS_POPULATION_INPUTS` | `DS_CROSSPOP_MERGED_GENOMEWIDE`, `DS_CROSSPOP_PRIMARY`, `DS_CROSSPOP_SENSITIVITY`, `DS_FIG3_PANEL_{A,B}` |
| `05_PGS_PROVIDER_CHECKPOINTS` | `DS_NOGAWA_WORKBOOK` ⚠, `DS_PRSICE_THRESHOLD_SCAN` |
| `06_CASE_PGS_AND_CONTROL_AGGREGATES` | `DS_CASE_PGS_CANONICAL` ⚠, `DS_FIG4_AGGREGATE_SOURCE`, `DS_FIG4_DENSITY`, `DS_FIG4_SUMMARY`, `DS_FIG4_PANEL_B`, `DS_TABLE2_CHECKPOINT` |
| `07_ROC_INPUTS_OR_RASTER_CHECKPOINTS` | `DS_ROC_PANEL_{A,B}_RASTER`, `DS_ROC_AUC_SOURCE` |
| `08_SUPPLEMENTARY_TABLE_SOURCES` | `DS_TABLE_S1_CURRENT` |

⚠ = restricted individual-level or provider-internal rows.

## Provenance

Package A v1.1 received 2026-08-01; filed here unmodified. All 57 files listed in
`SHA256SUMS.txt` verified OK at filing time:

```bash
cd reconstruction-package-a-v1.1 && shasum -a 256 -c SHA256SUMS.txt
```

⚠ **Version conflict worth resolving.** `DS_CROSSPOP_MERGED_GENOMEWIDE`
(`IQ_EuropeanGWAS_merged_all.tsv`) shares a filename with a file already sitting
at `orgs/personal/research/genomics/IQ_EuropeanGWAS_merged_all.tsv`, but the two
are **not** the same file:

| | size (bytes) | MD5 |
|---|---|---|
| Package A v1.1 (this dir, canonical) | 37,517,901 | `1e8a217f309bed2e0125f5dc1835921f` |
| pre-existing loose copy | 33,981,631 | `d0e44fc49543087153cbaaf710e14b0e` |

The Package A copy is the current checkpoint and is the one to reconstruct
against. The loose copy — and its `… (1).tsv` sibling, which is the same annex
key, i.e. a true duplicate of the *older* file — predate this package. They were
left untouched here, along with the BBJ height sumstats and the Neo4j backup in
that directory; deciding whether to retire them is a separate cleanup.
