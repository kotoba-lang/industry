# Maturity

**Level: R2 live adapter**

Implemented:
- Subject, evidence reference, and attestation models.
- Validation for known subject and evidence kinds, required IDs, attestation fields, and non-adjudicating records.
- Datom emitters for subjects, evidence references, and attestations.
- Ledger adapter boundary for validated subject/evidence/attestation datom transactions.
- Durable EDN ledger implementation.
- Datomic/Kotoba ledger backend adapter with transaction option propagation.
- DID resolver adapter boundary and DID document to subject binding.
- Verifiable Credential verifier boundary with evidence and attestation binding.
- Cross-source subject merge policy with aliases and conflict markers.
- Contract tests for subject shape, invalid records, attestation datoms, DID resolution, VC binding, merge policy, ledger payloads, and durable transaction reload.

Not yet R2:
- None.
