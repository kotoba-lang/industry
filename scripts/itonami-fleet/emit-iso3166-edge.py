#!/usr/bin/env python3
"""Emit the Worker edge for an iso3166-* market-entry actor.

Only defensible because it was measured, not assumed. Across all 186
iso3166 actors:

  operation.cljc   byte-identical
  Store protocol   the same 13 methods
  MemStore body    ONE code shape once strings and comments are stripped
  store.cljc       184 distinct shapes  <- the per-country part
  governor.cljc    184 distinct shapes  <- the per-country part

So the persistence adapter is genuinely the same for all of them, and the
country-specific logic sits in `governor`/`registry`, which this never
touches.

What is NOT generated: the routes. `marketentry/edge/worker.cljs` is copied
from the AGO actor with only the service name substituted, because the
request shape comes from the shared `operation.cljc`. If that ever diverges
for a country, this copy is wrong and the build says so rather than the
runtime.
"""
import io
import os
import re
import sys

STORE_ADDITION = '''

;; ----------------------------- KotobaseStore (the Worker's) -----------------------------

(defrecord KotobaseStore [st seed]
  Store
  (engagement [_ id]
    (when id (persist/get-doc (persist/ctx st :engagement :engagement/id) id)))
  (all-engagements [_]
    (sort-by :id (persist/all-docs (persist/ctx st :engagement :engagement/id))))
  (assessment-of [_ engagement-id]
    (persist/get-doc (persist/ctx st :assessment :assessment/engagement-id) engagement-id))

  (ledger [_] (persist/read-events (persist/stream-ctx st :ledger)))
  (draft-history [_] (persist/read-events (persist/stream-ctx st :draft)))
  (submit-history [_] (persist/read-events (persist/stream-ctx st :submit)))

  ;; MemStore keeps a per-jurisdiction counter; here the count of the stream
  ;; IS that counter. This actor covers exactly one jurisdiction, and every
  ;; draft appends exactly one record, so the two agree - and a length is a
  ;; read where a counter document would be a read-modify-write that two
  ;; concurrent drafts would collide on.
  (next-draft-sequence [s _jurisdiction] (count (draft-history s)))
  (next-submit-sequence [s _jurisdiction] (count (submit-history s)))

  (engagement-already-drafted? [s engagement-id]
    (boolean (:drafted? (engagement s engagement-id))))
  (engagement-already-submitted? [s engagement-id]
    (boolean (:submitted? (engagement s engagement-id))))

  (commit-record! [s {:keys [effect path value payload]}]
    (case effect
      :engagement/upsert
      (let [c (persist/ctx st :engagement :engagement/id)
            existing (persist/get-doc c (:id value))]
        (persist/put-doc! c (merge existing value)))

      :assessment/set
      (persist/put-doc! (persist/ctx st :assessment :assessment/engagement-id)
                        (assoc payload :assessment/engagement-id (first path)))

      :engagement/mark-drafted
      (let [engagement-id (first path)
            {:keys [result engagement-patch]} (draft-filing! s engagement-id)
            c (persist/ctx st :engagement :engagement/id)]
        (persist/append-event! (persist/stream-ctx st :draft) seed result)
        (persist/put-doc! c (merge (persist/get-doc c engagement-id) engagement-patch))
        result)

      :engagement/mark-submitted
      (let [engagement-id (first path)
            {:keys [result engagement-patch]} (submit-filing! s engagement-id)
            c (persist/ctx st :engagement :engagement/id)]
        (persist/append-event! (persist/stream-ctx st :submit) seed result)
        (persist/put-doc! c (merge (persist/get-doc c engagement-id) engagement-patch))
        result)
      nil)
    s)

  (append-ledger! [_ fact]
    (persist/append-event! (persist/stream-ctx st :ledger) seed fact)
    fact)

  (with-engagements [s engagements]
    (let [c (persist/ctx st :engagement :engagement/id)]
      (doseq [[_ e] engagements] (persist/put-doc! c e)))
    s))

(defn kotobase-store
  "The durable Store over a HOST-INJECTED database api.

  `marketplace.persist/store` throws when `db-api` is missing or partial, so
  this actor cannot come up looking durable while writing to nothing."
  [{:keys [db-api seq-fn]}]
  (->KotobaseStore (persist/store {:db-api db-api :actor "marketentry-%(cc)s"})
                   (or seq-fn (let [n (atom 0)] #(swap! n inc)))))
'''

SHADOW = '''\
;; The %(CC)s market-entry actor's Worker. :esm, the same shape as every other
;; actor on the shared host.
{:deps {:aliases [:cljs]}
 :builds
 {:worker
  {:target :esm
   :output-dir "dist"
   :modules {:worker {:exports {default marketentry.edge.worker/app}}}
   :compiler-options {:output-feature-set :es2020 :infer-externs :auto}}}}
'''

WRANGLER = '''\
{
  "name": "%(repo)s",
  "main": "dist/worker.js",
  "compatibility_date": "2026-07-30",
  "compatibility_flags": ["nodejs_compat"],
  "observability": { "enabled": true }
  // Uploaded into the ai-gftd-repository-dispatch namespace with
  // --dispatch-namespace and --secrets-file.
  //
  // `wrangler secret put` cannot reach a namespaced Worker - the flag does
  // not exist - and .dev.vars alone is NOT picked up either. --secrets-file
  // is the path that works, and it echoes the binding back; if that line is
  // missing, the secret is not there.
  //
  // Reachable at /%(repo)s/* through itonami-fleet-dispatch.
  //
  // Secrets, never placed here:
  //   KOTOBASE_SECRET_KEY  the fleet seed (kagi: itonami-fleet-kotobase-seed)
}
'''

NPMRC = '''\
# shadow-cljs resolves npm requires against the real node_modules tree, and
# @ipld/dag-cbor's own dependencies (cborg, multiformats) are not hoisted by
# pnpm's default isolated layout. Declaring another package's transitive deps
# as our own is how they drift; a hoisted tree is what npm would give.
node-linker=hoisted
'''

PKG = '''\
{
  "name": "%(repo)s",
  "private": true,
  "comment": "shadow-cljs compiles src/marketentry/edge/worker.cljs -> dist/worker.js, the kotobase host for the %(CC)s market-entry actor.",
  "scripts": { "build": "shadow-cljs release worker" },
  "devDependencies": {
    "@noble/hashes": "^2.2.0",
    "shadow-cljs": "^2.28.20",
    "wrangler": "^4.0.0",
    "@noble/curves": "^1.6.0",
    "@ipld/dag-cbor": "^9.2.1"
  }
}
'''

GITIGNORE = '''
# Worker build + secrets. node_modules pulls a ~109 MB workerd binary.
node_modules/
dist/
.shadow-cljs/
.cpcache/
.dev.vars
'''

DEPS_ADD = '''
        ;; The HOST half, shared with the marketplace family rather than
        ;; reimplemented.
        io.github.kotoba-lang/marketplace
        {:local/root "../../kotoba-lang/marketplace"}
        io.github.kotoba-lang/kotobase-client
        {:local/root "../../kotoba-lang/kotobase-client"}}'''


def emit(repo_dir, worker_src):
    repo = os.path.basename(repo_dir.rstrip('/'))
    cc = repo.rsplit('-', 1)[-1]
    sub = {"repo": repo, "cc": cc, "CC": cc.upper()}
    changed = []

    p = os.path.join(repo_dir, 'src/marketentry/store.cljc')
    if not os.path.exists(p):
        return None, f"{repo}: no marketentry/store.cljc"
    s = io.open(p, encoding='utf-8').read()
    if 'KotobaseStore' not in s:
        # Anchor on the END of the :require form, not on a particular library.
        # The family has two shapes — 123 actors end with langchain-store.core,
        # 62 with langchain.db behind a reader conditional — and keying on
        # either one silently skipped the other third of the fleet.
        m = re.search(r'\(:require\b.*?\)\)', s, re.S)
        if not m:
            return None, f"{repo}: no :require form found"
        block = m.group(0)
        s2 = s[:m.start()] + block[:-2] + '\n            [marketplace.persist :as persist]))' + s[m.end():]
        io.open(p, 'w', encoding='utf-8').write(s2.rstrip() + (STORE_ADDITION % sub))
        changed.append('store.cljc')

    wp = os.path.join(repo_dir, 'src/marketentry/edge/worker.cljs')
    if not os.path.exists(wp):
        os.makedirs(os.path.dirname(wp), exist_ok=True)
        w = io.open(worker_src, encoding='utf-8').read()
        w = w.replace('cloud-itonami-iso3166-ago', repo)
        w = w.replace('AGO market-entry', sub["CC"] + ' market-entry')
        io.open(wp, 'w', encoding='utf-8').write(w)
        changed.append('worker.cljs')

    for name, body in (('shadow-cljs.edn', SHADOW), ('wrangler.jsonc', WRANGLER),
                       ('.npmrc', NPMRC), ('package.json', PKG)):
        fp = os.path.join(repo_dir, name)
        if not os.path.exists(fp):
            io.open(fp, 'w', encoding='utf-8').write(body % sub)
            changed.append(name)

    gp = os.path.join(repo_dir, '.gitignore')
    g = io.open(gp, encoding='utf-8').read() if os.path.exists(gp) else ""
    if 'node_modules/' not in g:
        io.open(gp, 'w', encoding='utf-8').write(g.rstrip() + GITIGNORE)
        changed.append('.gitignore')

    dp = os.path.join(repo_dir, 'deps.edn')
    d = io.open(dp, encoding='utf-8').read()
    if 'kotoba-lang/marketplace' not in d:
        # Anchor on the close of the :deps map, not on a particular dependency.
        # Some of the family carry langchain-store and some do not; keying on it
        # skipped the ones that do not, which is the same mistake the :require
        # anchor made one step earlier.
        m = re.search(r':deps\s*\{.*?\}\}', d, re.S)
        if not m:
            return None, f"{repo}: no :deps map found"
        d2 = d[:m.end() - 1] + DEPS_ADD + d[m.end():]
        if ':cljs {' not in d2:
            d2 = d2.replace(" :aliases\n {",
                            " :aliases\n {;; the Worker build's classpath\n"
                            "  :cljs {:extra-deps {thheller/shadow-cljs {:mvn/version \"2.28.20\"}}}\n  ", 1)
        io.open(dp, 'w', encoding='utf-8').write(d2)
        changed.append('deps.edn')

    return changed, None


if __name__ == '__main__':
    worker_src = sys.argv[1]
    ok = fail = 0
    for d in sys.argv[2:]:
        ch, err = emit(d, worker_src)
        if err:
            print(f"  FAIL {err}")
            fail += 1
        else:
            ok += 1
            print(f"  {os.path.basename(d)}: {', '.join(ch) if ch else 'already done'}")
    print(f"\n{ok} emitted, {fail} failed")
