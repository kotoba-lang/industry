#!/usr/bin/env nbb
;; Publish one app document to BOTH byte planes, and prove both of them serve it.
;;
;;   nbb --classpath ".:orgs/kotoba-lang/content-address/src" \
;;       scripts/publish-document.cljs <file> --manifest <path> [--id <id>] [--dry-run]
;;
;; There are two stores and they are not interchangeable (ADR-2609092600):
;;
;;   archive  PUT https://kotobase.net/ipfs/{cid}   -> B2
;;            read back at {cid}.ipfs.kotobase.net, which is also the retrieval
;;            address every IPNI advertisement names. Dropping it would break a
;;            promise already made to the indexer.
;;   origin   R2 `ipld/{cid}` in kotobase-graph-database-production
;;            read by the web plane -- {cid}.ipfs.itonami.cloud and every
;;            {name}.itonami.app. This is the one an app is SERVED from.
;;
;; `content-address publish` writes only the archive. A document published that
;; way answers 200 on the bytes plane and 502 on the web plane, because the R2
;; miss falls through to the public gateway, which does not have it either.
;; That cost a wrong diagnosis once ("the host is broken" — the host was fine).
;; This script is the decision recorded on 2026-09-09 as「今のところは R2」:
;; publishing writes both, and R2 is the one whose absence is a bug.
;;
;; ⚠ The archive step is the existing CLI, invoked, not reimplemented. Its
;; PUT -> GET -> byte-compare is the proven path and a second copy of it would
;; be a second thing to keep right.
;;
;; Fail-closed. Exit 0 both planes serve the exact bytes / 1 a plane does not /
;; 2 could not answer. The web-plane read-back at the end is not decoration:
;; it is the only step that shows the decision actually took effect.
(ns publish-document
  (:require ["node:child_process" :as cp]
            ["node:fs" :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [content-address.core :as ca]))

(def ^:private origin-bucket "kotobase-graph-database-production")
(def ^:private web-plane-host "ipfs.itonami.cloud")

(defn- script-args []
  (if (seq *command-line-args*)
    (vec *command-line-args*)
    (let [argv (vec (js->clj (.-argv js/process)))
          i (first (keep-indexed (fn [i v] (when (str/ends-with? v "publish-document.cljs") i)) argv))]
      (if (some? i) (subvec argv (inc i)) []))))

(def args (script-args))
(defn- flag [n] (second (drop-while #(not= % n) args)))
(defn- switch? [n] (boolean (some #{n} args)))

(defn- die [code msg] (println msg) (js/process.exit code))

(defn- sh
  "Run a command, returning {:exit :out}. Never throws: a tool that is absent
  and a tool that failed are both answers this has to distinguish."
  [cmd opts]
  (try
    {:exit 0 :out (.toString (cp/execSync cmd (clj->js (merge {:stdio "pipe" :maxBuffer (* 64 1024 1024)} opts))))}
    (catch :default e
      {:exit (or (.-status e) 1)
       :out (str (some-> (.-stdout e) .toString) (some-> (.-stderr e) .toString))})))

(defn- read-manifest [p]
  (try (edn/read-string (.readFileSync fs p "utf8"))
       (catch :default e (die 2 (str "UNANSWERED — manifest will not read: " p " — " e)))))

(let [file (first (remove #(or (str/starts-with? % "--")
                              (some #{%} (remove nil? [(flag "--manifest") (flag "--id") (flag "--wrangler-dir")])))
                          args))
      manifest-path (flag "--manifest")
      id (flag "--id")
      wrangler-dir (or (flag "--wrangler-dir") "orgs/net-kotobase/ipfs")
      dry? (switch? "--dry-run")]
  (when-not (and file manifest-path)
    (die 2 "usage: publish-document.cljs <file> --manifest <path> [--id <id>] [--wrangler-dir <dir>] [--dry-run]"))
  (when-not (.existsSync fs file)
    (die 2 (str "UNANSWERED — no such document: " file)))
  (when-not (.existsSync fs wrangler-dir)
    (die 2 (str "UNANSWERED — no directory to run wrangler from: " wrangler-dir)))

  ;; ---- 1. archive plane (B2), through the existing CLI -------------------
  (let [cli "orgs/kotoba-lang/content-address/bin/content_address.cljs"
        _ (when-not (.existsSync fs cli)
            (die 2 (str "UNANSWERED — kotoba-lang/content-address is not checked out at " cli)))
        cmd (str "nbb --classpath .:orgs/kotoba-lang/content-address/src " cli
                 " publish " file " --manifest " manifest-path
                 (when id (str " --id " id))
                 (when dry? " --dry-run"))
        r (sh cmd {})]
    (println (str/trim (:out r)))
    (when-not (zero? (:exit r))
      (die 1 "archive publish failed; the origin plane was not touched"))

    (if dry?
      (println "dry-run — the origin-plane write and its read-back were skipped")
      ;; ---- 2. origin plane (R2) -------------------------------------------
      (let [m (read-manifest manifest-path)
            cid (or (:bundle-cid (ca/file-address-of m)) (die 2 "UNANSWERED — the manifest carries no bundle-cid after publishing"))
            put (sh (str "npx wrangler r2 object put " origin-bucket "/ipld/" cid
                         " --file " file " --content-type application/vnd.ipld.raw --remote")
                    {:cwd wrangler-dir})]
        (when-not (zero? (:exit put))
          (println (str/trim (:out put)))
          (die 1 (str "origin-plane write failed for " cid
                      "; the document is archived but the web plane cannot serve it")))
        (println (str "origin " origin-bucket "/ipld/" cid))

        ;; ---- 3. the read-back that shows the decision took effect ---------
        (let [url (str "https://" cid "." web-plane-host "/")
              tmp (str "/tmp/publish-document-" cid ".bin")
              get (sh (str "curl -sS -m 120 -o " tmp " -w '%{http_code}' " url) {})
              code (str/trim (:out get))
              same (sh (str "cmp -s " file " " tmp) {})]
          (println (str "web    " url " -> " code))
          (cond
            (not= "200" code)
            (die 1 (str "the web plane answered " code " for a document it should now hold"))

            (not (zero? (:exit same)))
            (die 1 "the web plane returned bytes that are not the document that was sent")

            :else
            (println "verified both planes serve the exact bytes")))))))
