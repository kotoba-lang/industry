
# Required encrypted return tree

```text
KAWASAKI_PHASE5C_INDEPENDENT_RETURN/
├─ 00_COMPLETION_BLOCK.txt
├─ 01_INDEPENDENT_ARTIFACT_RESULTS.tsv
├─ 02_ARTIFACT_FILES/
├─ 03_SOURCE_TABLES/
├─ 04_INPUT_HASH_AUDIT.tsv
├─ 05_METHOD_DEVIATION_LOG.tsv
├─ 06_SOFTWARE_ENVIRONMENT.tsv
├─ 07_VISUAL_SEMANTIC_QA.tsv
├─ 08_AI_ASSISTANCE_LOG.tsv
├─ 09_VERIFIER_ATTESTATION.md
├─ RETURN_MANIFEST.tsv
└─ SHA256SUMS.txt
```

Encrypt with AES-256 and header encryption. Send the archive SHA-256 separately
before any answer-key access.
