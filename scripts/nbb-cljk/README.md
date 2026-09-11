# Opt-in canonical `.cljk` host

This is a source patch for nbb 1.4.208 at upstream commit
`4fff82ea9bb74ae0f49d5c8fed5723f2eee71445`. The managed root retains its existing
`nbb` command. `npm run nbb:cljk -- ...` explicitly selects a locally rebuilt
host under `.cache/nbb-cljk`; it is not enabled for the fleet or deployed.

`loader.patch` modifies the upstream namespace resolver and source entrypoint
validation and adds conformance tests. Source files are read at their original
`.cljk` paths. There is no suffix-copy step or minified-JavaScript patch.
The source revision, patch, npm lock and built JavaScript hashes are recorded in
`build-manifest.json`. The upstream EPL-1.0 license remains applicable.

## Rebuild

Prerequisites: Node 26.0.0, Java 21, Clojure CLI. The pinned upstream deps.edn
selects shadow-cljs 3.3.5 and the SCI revision. This invokes the existing upstream
ClojureScript build directly; it does not introduce a new operational script
host or require the upstream bb task runner.

Run from the managed root worktree, using an empty task-owned destination:

```text
git clone https://github.com/babashka/nbb.git .cache/nbb-cljk
git -C .cache/nbb-cljk checkout --detach 4fff82ea9bb74ae0f49d5c8fed5723f2eee71445
git -C .cache/nbb-cljk apply --unidiff-zero ../../scripts/nbb-cljk/loader.patch
cp scripts/nbb-cljk/package-lock.json .cache/nbb-cljk/package-lock.json
cd .cache/nbb-cljk
npm ci --ignore-scripts
clojure -M:test -m shadow.cljs.devtools.cli --force-spawn release modules --config-merge shadow-release.edn --config-merge shadow-tests.edn
node cli.js test/cljk_loader_test.cljs
node lib/nbb_tests.js
```

Do not publish the upstream package name/version from this local build. Do not
replace the global installation or point a dependency pin at the local branch.
Build output stays in the task-owned cache; root manages the patch and lock.

## Select source repositories

Set `NBB_CLJK_ROOTS` to a JSON array of absolute repository directories, each
containing `cljk-origin.edn`. This environment setting propagates to existing
child-process commands such as the inga ref race. Alternatively, use
`:cljk-roots ["/absolute/repo"]` in an explicitly selected nbb config. Config
wins over the environment. Explicit configuration is required: unset means the
upstream legacy resolver remains in use.

Pass source directories through the existing `--classpath` argument. The
registry does not add directories to the classpath. Existing dependencies
without renamed sources, such as text and the block client, remain ordinary
classpath entries and do not require a fabricated origin manifest.

Within classpath order, declared `.cljs` wins over `.cljc`; `.clj` alone is
rejected for this Node host. Collision filenames are mapped with the manifest.
Unknown origins, same-rank duplicate candidates, conflicting legacy candidates,
malformed/duplicate manifest keys, escaping paths and escaping symlinks reject.
An invalid first root cannot silently fall through to a later root. Manifests
are re-read for resolution and reload; no source or manifest cache obscures a
change. Diagnostics keep canonical `.cljk` paths. A `.cljk` entrypoint itself must
be registered and compatible.

## Qualification boundary

The conformance suite exercises the actual built host in child processes:
transitive require, reload of source and manifest, platform and classpath order,
unknown/ambiguous rejection, duplicate manifest keys, symlink escape rejection,
legacy behavior and canonical error paths. Upstream compiled unit tests are run
with the option unset.

The reservation acceptance uses original inga and inga-node worktrees, explicit
source dependencies, disposable generated test identities and local endpoints:
`script/test.cljk`, `script/test-archive.cljk`, `script/ref_race.cljk`, and
`script/run.cljk` with an observed health response followed by process stop.
A successful smoke is not a production rollout or live Kotobase durability
claim. The archive and inga PRs remain Draft; dependency integration must use a
merged inga revision.

The build process was repeated from a clean checkout with the saved patch and
lock. Advanced compiler output is not claimed to be byte-for-byte reproducible:
separate builds produced different generated symbol names. Both artifact hash
sets and their qualification are recorded. The source revision and patch remain
the replay boundary; do not substitute one artifact's hash for another.

For source-only qualification, use an explicit config containing the registered
roots and pass all dependency source directories in --classpath. Some existing
repository nbb.edn files declare :deps; the upstream dependency downloader uses
bb, which is outside this host qualification. The new loader does not replace
that downloader. The recorded reservation test uses explicit source-only config.
See qualification.json for exact tested source/dependency commits and counts.
