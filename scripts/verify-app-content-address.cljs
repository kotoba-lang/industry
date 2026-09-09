#!/usr/bin/env nbb
;; Which application manifests identify their document by content, and which
;; only by location?
;;
;;   nbb --classpath ".:orgs/kotoba-lang/content-address/src" \
;;       scripts/verify-app-content-address.cljs [<root>] [--list]
;;
;; ADR-2608157000. The judge is `content-address.core/addressed?` — this
;; script does not carry a second copy of that decision, because a mirror
;; that only one side fixes is how the workspace has lost time before.
;;
;; Exit codes are three-valued: 0 = every manifest carries a content address,
;; 1 = some identify by location only, 2 = the question could not be answered
;; (nothing scanned, the library is not checked out, or an EDN would not
;; read). A check that could not run must never look like a pass
;; (ADR-2608136000).
;;
;; ⚠ This is deliberately NOT a fleet gate. Its input lives under `orgs/`,
;; and fleet ships a single repo's tree — a gate here would be permanently
;; red for want of input, exactly like `root-permit-index`.

(ns verify-app-content-address
  (:require ["node:child_process" :as cp]
            ["node:fs" :as fs]
            ["node:path" :as path]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [content-address.core :as ca]))

(def ^:private manifest-name "kotoba.app.edn")

(defn- script-args []
  (if (seq *command-line-args*)
    (vec *command-line-args*)
    (let [argv (vec (js->clj (.-argv js/process)))
          i (first (keep-indexed
                    (fn [i v] (when (str/ends-with? v "verify-app-content-address.cljs") i))
                    argv))]
      (if (some? i) (subvec argv (inc i)) []))))

(defn- find-manifests [root]
  (let [out (try
              (.toString (cp/execSync (str "rg --files --hidden --no-messages -g '"
                                           manifest-name "' " root)
                                      #js {:maxBuffer (* 64 1024 1024)}))
              (catch :default _ ""))]
    (->> (str/split-lines out)
         (remove str/blank?)
         ;; A worktree copy is the same manifest twice; counting it twice
         ;; would move the coverage number without moving the coverage.
         (remove #(re-find #"/(node_modules|\.git)/" %))
         (remove #(re-find #"/orgs/[^/]+/[._]" %))
         sort
         vec)))

(def ^:private exempt-kinds
  "Kinds that are outside content addressing, and why.

  A manifest that declares one of these is not unfinished work — it has no
  single document to address. Before this key existed the three states
  `not yet published`, `never going to be a document` and `nobody looked`
  produced the same LOCATED line, so ADR-2608157000's three open items
  (44 pipelines / 89 dynamic workers / 154 placement manifests) could not be
  told apart by the detector that was supposed to be draining them."
  {:service   "a dynamic worker — it answers requests, it does not serve one document"
   :placement "an actor placement manifest — it declares where code runs, not what is served"})

(def ^:private legacy-kind
  "Spellings that predate the keyword value space (ADR-2609092600).

  Measured 2026-09-09: all 435 addressed manifests carry
  `:kotoba.app/kind \"appview\"` — a string — and all 82 unaddressed ones
  carry no kind at all. An appview repo ships one `docs/index.html`, so
  \"appview\" says exactly what `:document` says.

  They are normalised here rather than rewritten in 435 files, because
  rewriting them would move no coverage (CLAUDE.md: 一括改名はしない). It
  matters for the failing direction: without this, an appview that lost its
  address would fall through to LOCATED — the weaker finding — instead of
  UNPUBLISHED, which is what it would actually be."
  {"appview" :document})

(defn- declared-kind
  "The `:kotoba.app/kind` this file declares, or nil. Read from whichever
  entity carries it, because a manifest may be a map or a vector of maps."
  [parsed]
  (let [raw (some :kotoba.app/kind (ca/entities parsed))]
    (if (string? raw) (get legacy-kind raw) raw)))

(defn- classify [p]
  (let [m (try (edn/read-string (.readFileSync fs p "utf8"))
               (catch :default e {::unreadable (str e)}))
        kind (when-not (::unreadable m) (declared-kind m))]
    (cond
      (::unreadable m)
      {:path p :state :unreadable :detail (::unreadable m)}

      (empty? (ca/entities m))
      {:path p :state :unreadable :detail "no application entity in this EDN"}

      ;; An address answers the question whatever the kind says, so this
      ;; comes first: a service that also publishes a document is addressed,
      ;; not exempt.
      (ca/file-addressed? m)
      {:path p :state :addressed :kind kind
       :address (ca/file-address-of m)
       :problems (mapcat ca/problems (filter ca/addressed? (ca/entities m)))}

      (contains? exempt-kinds kind)
      {:path p :state :exempt :kind kind :why (get exempt-kinds kind)}

      ;; `:document` is a claim that there IS one document here. Unaddressed,
      ;; that is a stronger finding than never having said what this is, and
      ;; it gets its own reason literal so the two cannot be folded together.
      (= :document kind)
      {:path p :state :document-not-addressed :kind kind
       :problems (ca/problems (first (ca/entities m)))}

      :else
      {:path p :state :located
       :problems (ca/problems (first (ca/entities m)))})))

(defn -main [& args]
  (let [root (or (first (remove #(str/starts-with? % "--") args)) "orgs")
        list? (some #{"--list"} args)
        lib "orgs/kotoba-lang/content-address/src"]
    (when-not (.existsSync fs lib)
      (println "UNANSWERED — kotoba-lang/content-address is not checked out at" lib)
      (println "  west update --fetch smart content-address")
      ;; Exit, do not throw. `set! exitCode 2` followed by a throw is overridden
      ;; by nbb's own error handler, which exits 1 -- so the refusal ("could not
      ;; answer") arrived at the caller wearing the code for "found violations".
      ;; Measured 2026-08-19 by running this from a tree with no orgs/: it
      ;; printed UNANSWERED and exited 1.
      (js/process.exit 2))
    (let [rows (mapv classify (find-manifests root))
          by-state (group-by :state rows)
          scanned (count rows)
          addressed (count (:addressed by-state))
          exempt (count (:exempt by-state))
          mislabelled (count (:document-not-addressed by-state))
          located (count (:located by-state))
          unreadable (count (:unreadable by-state))]
      (when list?
        (doseq [r rows]
          (println (case (:state r)
                     :addressed "ADDRESSED "
                     :exempt "EXEMPT    "
                     :document-not-addressed "UNPUBLISHED"
                     :located "LOCATED   "
                     "UNREADABLE")
                   (:path r)
                   (if (seq (:problems r))
                     (str/join "," (map (comp name :problem) (:problems r)))
                     ""))))
      (doseq [r (:addressed by-state)]
        (println "ADDRESSED " (:path r)
                 (get-in r [:address :bundle-cid] (get-in r [:address :graph-cid]))))
      (doseq [r (:unreadable by-state)]
        (println "UNREADABLE" (:path r) (:detail r)))
      ;; The located-only manifests, in the one format the detector registry
      ;; parses (FINDING<TAB>severity<TAB>key<TAB>detail). Without these lines
      ;; this script reports 80 violations to a human reading the log and zero
      ;; findings to anything that files the result -- SCANNED matches, no
      ;; FINDING is seen, and the run is recorded as clean. Everything else here
      ;; was already careful (three-valued exit, no second copy of the judge, a
      ;; refusal when the library is absent); the gap was only that its output
      ;; was addressed to a reader.
      ;; A manifest that says `:document` and carries no address is a
      ;; different failure from one that never said what it is, so it gets
      ;; its own key. Folding them would make a repo look finished the
      ;; moment someone typed the kind.
      (doseq [r (:document-not-addressed by-state)]
        (println (str "FINDING\twarn\tdocument-not-addressed:" (:path r) "\t"
                      "declares :kotoba.app/kind :document and carries no content address"
                      (when (seq (:problems r))
                        (str " — " (str/join "," (map (comp name :problem) (:problems r))))))))
      (doseq [r (:located by-state)]
        (println (str "FINDING\twarn\tlocated-only:" (:path r) "\t"
                      "identifies its document by location, not by content"
                      (when (seq (:problems r))
                        (str " — " (str/join "," (map (comp name :problem) (:problems r))))))))
      (println (str "ROOT\t" (path/resolve root)))
      (println (str "SCANNED\t" scanned))
      (println (str "ADDRESSED\t" addressed))
      (println (str "EXEMPT\t" exempt))
      (println (str "UNPUBLISHED\t" mislabelled))
      (println (str "LOCATED\t" located))
      (println (str "UNREADABLE\t" unreadable))
      (cond
        (zero? scanned)
        (do (println "UNANSWERED — no" manifest-name "was found under" root)
            (set! (.-exitCode js/process) 2))

        (pos? unreadable)
        (do (println "UNANSWERED — an application manifest would not read")
            (set! (.-exitCode js/process) 2))

        (or (pos? located) (pos? mislabelled))
        (set! (.-exitCode js/process) 1)))))

(apply -main (script-args))
