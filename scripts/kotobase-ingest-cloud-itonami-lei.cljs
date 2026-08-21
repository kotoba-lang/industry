#!/usr/bin/env nbb
;; kotobase-ingest-cloud-itonami-lei.cljs — pull-based batch fold job that
;; closes ADR-2607072300's open "journal -> kotobase-peer fold job" gap for
;; the cloud-itonami-lei-* actor family (ADR-2607110300). Enumerates every
;; public `cloud-itonami-lei-<LEI>` repo, fetches its `blueprint.edn` +
;; `80-data/public/tos.journal.edn` (the git-authoritative source), and
;; transacts them into kotobase.net as one shared, queryable graph.
;;
;; Real ClojureScript (this repo's CLAUDE.md runtime priority ranks cljs
;; above nbb, but a full Datalog engine has no cljs-native hosting runtime
;; yet per kotobase-cf-wasm/DEPLOY.md, so nbb running real .cljs source is
;; the correct current choice here, matching the "Node 側の検証/テスト
;; ハーネス...も新規に書く場合は nbb で書く" rule for this class of script)
;; — reuses `kotoba-lang/kotobase-client`'s proven CACAO/transact/q client
;; verbatim rather than reimplementing auth or the wire protocol.
;;
;; Run (from the superproject root, after `west update kotobase-client`):
;;   cd orgs/kotoba-lang/kotobase-client && npm install   # once
;;   NODE_PATH="$(pwd)/node_modules" npx nbb --classpath "src" \
;;     ../../../scripts/kotobase-ingest-cloud-itonami-lei.cljs
;;
;; Identity: a fresh Ed25519 seed is generated on first run and persisted to
;; scripts/.kotobase-ingest-cloud-itonami-lei-identity.hex (gitignored, NEVER
;; commit this file — same discipline as every other actor's
;; `.<actor>/identity.edn`). The graph is `kotobase/db/<that-did>/
;; cloud-itonami-lei-catalog`, self-sovereign per ADR-2607072300/root
;; CLAUDE.md's CACAO actor pattern — re-running with the SAME identity file
;; re-asserts the same [entity attr] facts (cardinality-one upsert), which is
;; idempotent by construction: it does not duplicate data. Losing the
;; identity file means a re-run mints a NEW identity and starts a NEW, empty
;; graph instead of continuing this one — back it up before treating it as
;; disposable.
;;
;; tx_edn wire shape (kotoba-lang/kotobase-server handler.cljc
;; `tx-edn->quads`): a vector of ENTITY MAPS `[{:db/id "e" :ns/attr v ...}
;; ...]` — NOT `[:db/add e a v]` triples (that shape is kotobase-peer's own
;; in-process API, not what this XRPC layer's tx_edn parser accepts; the
;; only accepted vector forms are `[:db/retract e a v]` / `[:db/retractEntity
;; e]`, dispatched before the entity-map case).

(ns kotobase-ingest-cloud-itonami-lei
  (:require ["node:crypto" :as node-crypto]
            ["node:fs" :as fs]
            ["node:path" :as path]
            ["node:child_process" :refer [execSync]]
            [cljs.reader :as edn]
            [clojure.string :as str]
            [nbb.core :refer [*file*]]
            [kotobase.client :as client]))

;; BUG FIX (2026-07-25): this used to be `(path/dirname (aget js/process.argv 2))`,
;; which assumed argv[2] is this script's own path — true for plain
;; `node script.js`, but NOT true for how this script is actually invoked
;; (`nbb --classpath <cp> <script.cljs> ...`). Under nbb, process.argv is
;; [node, nbb-bin, "--classpath", <cp>, <script.cljs>, ...script-args], so
;; argv[2] is literally the string "--classpath" — `path/dirname` on that
;; returns "." (no path separator), silently resolving script-dir to
;; whatever the *current working directory* happened to be at invocation
;; time instead of scripts/. That misplaced the identity file and made a
;; real run look like a "first run", minting a throwaway identity instead
;; of reusing the persisted one. `nbb.core/*file*` is nbb's own supported
;; way to get the absolute path of the file currently being executed
;; (see node_modules/nbb/README.md "Current file") — it is invocation-shape
;; agnostic (no dependence on argv position, flag count, or cwd), so this
;; can't recur regardless of what flags precede the script path.
(def script-dir
  (path/dirname *file*))

(def identity-path
  (path/join script-dir ".kotobase-ingest-cloud-itonami-lei-identity.hex"))

(defn load-or-create-identity! []
  (if (fs/existsSync identity-path)
    (js/Uint8Array.from (js/Buffer.from (str/trim (fs/readFileSync identity-path "utf8")) "hex"))
    (let [sk (js/Uint8Array. (.randomBytes node-crypto 32))]
      (fs/writeFileSync identity-path (.toString (js/Buffer.from sk) "hex"))
      (println "Minted a NEW ingestion identity at" identity-path
                "— back this file up, losing it orphans the graph.")
      sk)))

(def sk (load-or-create-identity!))
(def c (client/make-client {:endpoint "https://kotobase.net"
                             :operator-did "did:web:kotobase.net"
                             :secret-key sk}))
(def db-name "cloud-itonami-lei-catalog")

(defn fetch-text [url]
  (-> (js/fetch url)
      (.then (fn [^js r]
               (if (.-ok r)
                 (.text r)
                 (throw (js/Error. (str "HTTP " (.-status r) " " url))))))))

(defn list-repos
  "REST (`gh api .../repos`), not `gh repo list` (GraphQL) — the GraphQL rate
  limit is shared/exhaustible session-wide (hit empty mid-development here),
  REST has its own separate, much larger budget."
  []
  (let [out (execSync "gh api \"orgs/cloud-itonami/repos?per_page=100\" --paginate --jq '.[].name'"
                       #js {:encoding "utf8" :maxBuffer (* 20 1024 1024)})]
    (->> (str/split-lines out)
         (filter #(str/starts-with? % "cloud-itonami-lei-"))
         sort vec)))

(defn build-tx-data
  "One entity map for the company (blueprint.edn fields + :company/repo), and
  one entity map per distinct :tos/* document id in the journal (its own
  attrs plus a :tos/company back-reference) — the entity-map shape
  handler.cljc's tx-edn->quads requires."
  [repo bp journal]
  (let [lei (:company/lei bp)
        ent (str "lei:" lei)
        company-map (-> bp
                         (assoc :db/id ent)
                         (assoc :company/repo (str "https://github.com/cloud-itonami/" repo)))
        by-doc (group-by first journal)
        tos-maps (for [[doc-id entries] by-doc]
                   (into {:db/id (str ent "/" doc-id) :tos/company ent}
                         (keep (fn [[_ a v _tx op]] (when (= op :add) [a v])))
                         entries))]
    (vec (cons company-map tos-maps))))

(defn ingest-one! [repo]
  (let [base (str "https://raw.githubusercontent.com/cloud-itonami/" repo "/main/")]
    (-> (js/Promise.all #js [(fetch-text (str base "blueprint.edn"))
                              (fetch-text (str base "80-data/public/tos.journal.edn"))])
        (.then (fn [[bp-text journal-text]]
                 (let [bp (edn/read-string bp-text)
                       journal (edn/read-string journal-text)
                       tx-data (build-tx-data repo bp journal)
                       tx-edn (pr-str tx-data)]
                   (-> (client/transact c db-name tx-edn {:retry? true})
                       (.then (fn [res]
                                (println "OK  " repo " lei=" (:company/lei bp)
                                         " entities=" (count tx-data)
                                         " datom_count=" (.-datom_count res))
                                {:repo repo :ok true :lei (:company/lei bp)}))))))
        (.catch (fn [e]
                  (println "FAIL" repo (.-message e))
                  {:repo repo :ok false :error (.-message e)})))))

(def pace-ms
  "Conservative delay between successive company ingests (on top of
  kotobase-client's own per-request retry/backoff). Added after a 2026-07-25
  incident where running all 159 repos back-to-back with no inter-request
  pause drove a rising rate of real Cloudflare Worker 503 'exceeded resource
  limits' failures against backend.kotobase.net (observed climbing from
  ~6/16 to 27/40 as the unpaced run progressed) — a backend capacity/pacing
  issue, not a client-side bug, so the fix here is to slow down, not retry
  harder. Override via INGEST_PACE_MS env var if a future run needs to tune
  it (e.g. faster once backend headroom is confirmed)."
  (let [v (aget js/process.env "INGEST_PACE_MS")]
    (if (and v (not (js/isNaN (js/parseInt v 10))))
      (js/parseInt v 10)
      2000)))

(defn sleep [ms]
  (js/Promise. (fn [resolve] (js/setTimeout resolve ms))))

(defn run-sequential [repos]
  (reduce (fn [chain-p repo]
            (.then chain-p (fn [acc]
                             (-> (ingest-one! repo)
                                 (.then (fn [r] (.concat acc #js [r])))
                                 (.then (fn [acc2] (.then (sleep pace-ms) (fn [_] acc2))))))))
          (js/Promise.resolve #js [])
          repos))

(defn select-repos
  "Optionally restrict a repair run to a comma-separated list supplied in
  INGEST_REPOS.  The normal no-env invocation still folds the complete
  catalog.  Reject unknown names so a typo cannot silently produce a
  misleading successful partial run."
  [repos]
  (if-let [raw (not-empty (aget js/process.env "INGEST_REPOS"))]
    (let [requested (->> (str/split raw #",")
                         (map str/trim)
                         (remove empty?)
                         distinct
                         vec)
          known (set repos)
          unknown (remove known requested)]
      (when (seq unknown)
        (throw (js/Error. (str "INGEST_REPOS contains unknown repositories: "
                               (str/join "," unknown)))))
      requested)
    repos))

(defn -main []
  (println "ingest identity did:" (:did c))
  (let [all-repos (list-repos)
        repos (select-repos all-repos)]
    (println "repo count:" (count repos) "catalog total:" (count all-repos))
    (-> (run-sequential repos)
        (.then (fn [results]
                 (let [results (js->clj results :keywordize-keys true)
                       ok (filter :ok results)
                       failed (remove :ok results)]
                   (println "=== SUMMARY ===")
                   (println "total:" (count results) "ok:" (count ok) "failed:" (count failed))
                   (when (seq failed)
                     (println "FAILED REPOS:")
                     (doseq [f failed] (println " -" (:repo f) (:error f)))))))
        (.catch (fn [e] (println "FATAL:" (.-message e)) (println (.-stack e)))))))

(-main)
