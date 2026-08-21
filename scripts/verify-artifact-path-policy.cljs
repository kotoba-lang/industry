#!/usr/bin/env nbb
;; scripts/verify-artifact-path-policy.cljs — enforce a DataLad dataset's own
;; `index/policy.edn` allowlist of path shapes.
;;
;; Why this exists (ADR-2800003200 Phase 4): the artifact plane's README says
;; "do not encode a customer, a part number or a programme name in a path",
;; and prose is not a control — nothing reads it at the moment somebody adds a
;; file. The leak it guards against survives encryption: git-annex hides file
;; CONTENT, never path, size, update time or author. A drawing at
;; `raw/cad/<customer>-<part>.step` updated the week before a launch leaks a
;; schedule to anyone who can read the tree, encrypted or not.
;;
;; So a dataset declares the path shapes it accepts, and this fails on anything
;; else. Datasets without an `index/policy.edn` are reported as :skipped —
;; declaring a policy is opt-in, breaking one is not.
;;
;; Usage:
;;   nbb --classpath ".:scripts/nbb_compat" scripts/verify-artifact-path-policy.cljs
;;   nbb ... scripts/verify-artifact-path-policy.cljs --names tsukuru-manufacturing-artifacts
;;
;; Exit 1 if any annexed file in a policied dataset matches no allowed shape.
;;
;; Sibling of scripts/annex-custody-verify.cljs, which asks a different question:
;; that one asks whether the bytes still EXIST somewhere off this machine; this
;; one asks whether they should be here at all.

(require '[scripts.nbb-compat :refer [sh exit]]
         '[clojure.edn :as edn]
         '[clojure.string :as str])

(def fs (js/require "node:fs"))
(def root (str/trim (:out (sh "git" "rev-parse" "--show-toplevel"))))

(defn- projects
  "DataLad projects from west.yml — name + path, for entries carrying
  `datalad: true`. Parsed positionally rather than with a YAML library, the
  same way annex-custody-verify does: west.yml is generated, so its shape is
  stable, and adding a dependency to read one flag is not a trade worth making."
  []
  (let [lines (str/split-lines (fs.readFileSync (str root "/manifest/west.yml") "utf8"))]
    (loop [[l & more] lines, cur nil, out []]
      (if (nil? l)
        out
        (cond
          (str/starts-with? l "    - name: ")
          (recur more {:name (str/trim (subs l 12))} out)

          (and cur (str/starts-with? l "      path: "))
          (recur more (assoc cur :path (str/trim (subs l 12))) out)

          (and cur (str/includes? l "datalad: true"))
          (recur more nil (conj out cur))

          :else (recur more cur out))))))

(defn- annexed-files
  "Every file git-annex tracks in a dataset, whether or not its content is
  present locally. `git annex find --include '*'` lists by working-tree path
  and does not require the bytes — which matters, because the normal state of
  this plane is content dropped."
  [dir]
  (let [{:keys [exit out]} (sh "git" "-C" dir "annex" "find" "--include" "*")]
    (if (zero? (or exit 0))
      (remove str/blank? (str/split-lines (str out)))
      [])))

(defn- policy [dir]
  (let [p (str dir "/index/policy.edn")]
    (when (fs.existsSync p)
      (edn/read-string (fs.readFileSync p "utf8")))))

(defn- violations [pol files]
  (let [shapes (:policy/allowed-paths pol)
        res (mapv (fn [s] [(js/RegExp. (:regex s)) s]) shapes)]
    (vec (for [f files
               :when (not (some (fn [[re _]] (.test re f)) res))]
           f))))

(let [args (vec *command-line-args*)
      wanted (when-let [i (.indexOf args "--names")]
               (when (nat-int? i) (set (str/split (get args (inc i) "") #","))))
      projs (cond->> (projects)
              (seq wanted) (filter #(contains? wanted (:name %))))
      results
      (for [{:keys [name path]} projs
            :let [dir (str root "/" path)]]
        (cond
          (not (fs.existsSync dir)) {:name name :state :skipped :why "not checked out"}
          (nil? (policy dir)) {:name name :state :skipped :why "no index/policy.edn"}
          :else
          (let [pol (policy dir)
                files (annexed-files dir)
                bad (violations pol files)]
            {:name name :files (count files) :bad bad
             :state (if (seq bad) :fail :ok)
             ;; Surfaced on every run, not only on failure: a dataset that
             ;; stores plaintext should say so every time somebody checks it,
             ;; rather than only when something else breaks.
             :encryption (get-in pol [:policy/storage-facts :encryption])
             :confidential-permitted?
             (get-in pol [:policy/confidential-material :permitted?])})))
      results (vec results)
      failed (filterv #(= :fail (:state %)) results)]
  (doseq [{:keys [name state files bad why encryption confidential-permitted?]} results]
    (case state
      :skipped (println (str "  - " name ": skipped (" why ")"))
      :ok (println (str "  - " name ": OK — " files " annexed file(s), all within the declared shapes"
                        " [encryption=" (clj->js encryption)
                        ", confidential-material=" (if confidential-permitted? "permitted" "NOT permitted") "]"))
      :fail (do (println (str "  - " name ": FAIL — " (count bad) " path(s) outside the allowlist:"))
                (doseq [b (take 10 bad)] (println (str "      " b))))))
  (println (str "verify-artifact-path-policy: checked=" (count results)
                " ok=" (count (filter #(= :ok (:state %)) results))
                " fail=" (count failed)
                " skipped=" (count (filter #(= :skipped (:state %)) results))))
  (exit (if (seq failed) 1 0)))
