
# Result lock and unblinding protocol

1. Complete every in-scope artifact and the required return templates.
2. Create an encrypted return archive and a SHA-256 manifest.
3. Send the archive hash, completion block, and signed attestation to the
   custodian.
4. The custodian records the return hash before opening Package B.
5. Only after hash lock may the custodian compare with the answer key.
6. Any discrepancy investigation is Round 2 with a new archive and hash; never
   silently replace the locked independent result.
