# Origin-aware canonical source qualification

The implementation lives in the managed kbb SCI engine:
https://github.com/kotoba-lang/org-babashka-nbb/pull/1
Merged revision: `90fc125965995b8e8205ac723bf6d432afabe0ed` (nbb 1.5.212).

Use the established `bin/kbb --backend sci --classpath <source-paths> <entry.cljk>`
entrypoint. Set `KBB_ENGINE` to that checkout's `cli.js` when isolating a release.
Set `NBB_CLJK_ROOTS` to a JSON array of absolute repository roots with origin
manifests, or use `:cljk-roots` in an explicit source-only config. All dependencies
must be immutable merged revisions and explicitly present on the classpath.

This mode validates canonical entrypoints and transitive require/reload, selects
declared .cljs before .cljc, and rejects unknown/ambiguous/incompatible sources.
It preserves source paths and bytes. The engine's default behavior is retained.
There is no parallel standalone 1.4.208 engine, copied suffix tree or minified patch.
The earlier experimental patch remains in this PR's Git history, not a deployment.

Validation: loader 7 tests / 23 assertions; upstream 50 / 84; original node 33 / 106;
archive 7 / 39; seven-process ref race passes; run health/start/stop passes.
The engine uses its existing build compatibility toolchain; this is not a native
Kotoba migration claim. Generated JavaScript hashes are not byte-reproducible.

Production preflight found every existing witness snapshot missing :voted-view.
The new node correctly refuses those snapshots. Release files can be staged but
activation must preserve the existing chain's voting history; do not fabricate a
watermark, erase snapshots or reuse keys on a new chain without a migration plan.

The owner subsequently authorized a separate new chain.
`inga-reservation-20260911-v1` is active on judah/benjamin/simeon/joseph,
ports 19601–19604, with independent keys/data and system LaunchDaemons running
as each ordinary user. The existing chains are unchanged. Full peer mesh, a
single durable winner from two smoke writers, membership-proof verification
and w4 restart/state recovery passed. Automatic archive capture and the optional
finalized-prefix service remain unwired; witness activation does not prove live
Kotobase archive persistence or payment execution.
