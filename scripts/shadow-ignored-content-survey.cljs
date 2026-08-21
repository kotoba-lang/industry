#!/usr/bin/env nbb
;; Survey of GITIGNORED content across the 249 shadow checkouts, and a revised
;; three-way ranking.
;;
;;   nbb scripts/shadow-ignored-content-survey.cljs
;;   nbb scripts/shadow-ignored-content-survey.cljs --out FILE.edn
;;   nbb scripts/shadow-ignored-content-survey.cljs --limit 20        ; sample
;;   nbb scripts/shadow-ignored-content-survey.cljs --root /path/to/superproject
;;
;; READ-ONLY. Every git call is `status`; nothing is fetched, written, moved or
;; deleted. No `--depth` fetch is issued anywhere -- a worktree shares
;; `.git/shallow` with its parent and this workspace has an observed case of one
;; repository's shallow fetch writing a graft into another's object store
;; (ADR-2608124400).
;;
;; ## Why this exists
;;
;; `scripts/shadow-checkout-disposition.cljs` ranked 223 of 249 shadow checkouts
;; :safe-to-retire on the evidence of `git status`: nothing dirty, nothing
;; untracked, no stash, no linked worktree, every tip published.
;;
;; **`git status` does not see gitignored state.** Its whole job is to hide it.
;; So "holds nothing unique" was never measured over the ignored half of the
;; tree -- it was assumed. Measured 2026-08-13: `etzhayyim/com-etzhayyim-minidrama`
;; was ranked :safe-to-retire while its gitignored `.minidrama/` held the actor's
;; Ed25519 private key and 36 rendered episodes, present in no commit and in no
;; other checkout. And `load-or-create-identity!` opens that directory RELATIVE
;; TO THE CURRENT WORKING DIRECTORY, so merely changing cwd mints a new keypair
;; and a new DID without saying so.
;;
;; 223 was therefore an upper bound and 25 a lower bound. This script measures
;; the real numbers.
;;
;; ## What it asks, per checkout
;;
;;   1. `git status --porcelain -z --ignored=matching` -> the ignored entries.
;;      Untracked-files defaults to `normal`, so a wholly-ignored directory
;;      collapses to one line (`!! node_modules/`) instead of 40,000. One
;;      subprocess per checkout, ~110 ms warm.
;;   2. Classify each entry. **Not all ignored content is precious.** A checkout
;;      with 40,000 ignored files that are all `node_modules` is still safe; one
;;      with a single 32-byte key file is not. Three classes:
;;        :rebuildable-hard  never walked (node_modules, target, .cpcache, ...)
;;        :rebuildable-soft  walked under a cap, but not counted as unique
;;                           (dist, out, build, .next, coverage, tmp, logs, ...)
;;        :candidate         everything else -- walked, hashed, compared
;;   3. Walk candidates (and soft ones, for secret shapes only) with `fs`, never
;;      following symlinks, capped at `max-files` per entry.
;;   4. **Compare against the declared twin, not by name alone.** Presence of the
;;      same relative path in the twin is necessary but not sufficient -- two
;;      `.minidrama/` directories can hold different episodes. Where the tree is
;;      within budget the comparison is a CONTENT digest (sha256 per file, folded
;;      over the sorted relative paths); above budget it degrades to a METADATA
;;      digest (relative path + size) and the row says which was used.
;;   5. Secret and identity material is located by filename shape and reported as
;;      PATH, SIZE and CLASS only. **Contents are never read for reporting, never
;;      printed and never copied.** Files inside a candidate tree are hashed as
;;      part of the tree digest; an individual secret file's digest is never
;;      emitted.
;;
;; ## Stated misses
;;
;;   - :rebuildable-hard trees are not walked at all, so a credential hidden
;;     inside `node_modules/` or `target/` would not be found. That is the price
;;     of not walking ~40k files per checkout, and it is stated rather than
;;     hidden.
;;   - Filename shape is a heuristic. A key stored as `blob.dat` is invisible to
;;     it. The digest comparison still catches it as unique content; only the
;;     :secret CLASSIFICATION would miss.
;;   - Absence from the twin is not proof of absence from everywhere. Only the
;;     declared twin is compared, which is the conservative direction: it can
;;     over-report uniqueness, not under-report it.
;;
;; ## Output
;;
;; A datoms dataset, `:source/dataset "shadow-ignored"`, keyed by `:shadow/dir`
;; so it joins the `shadow-checkouts` dataset of
;; `90-docs/adr/2608135600-shadow-checkout-disposition.datoms.edn`. The revised
;; verdict lives HERE, in `:shadow/verdict-revised`; the git-status-only verdict
;; is carried alongside as `:shadow/verdict-git-status` so the join is legible
;; from one side. Exit code 0 clean, 1 findings (some checkout moved), 2 a
;; checkout could not be assessed.

(ns shadow-ignored-content-survey
  (:require ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            ["node:crypto" :as crypto]
            ["node:child_process" :as child]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(def argv (vec (drop 2 js/process.argv)))
(defn- flag? [f] (some #{f} argv))
(defn- opt [f d] (let [i (.indexOf argv f)] (if (neg? i) d (get argv (inc i) d))))

(def root (opt "--root" (.cwd js/process)))
(def datoms-in (opt "--datoms" "90-docs/adr/2608135600-shadow-checkout-disposition.datoms.edn"))
(def out-file (opt "--out" nil))
(def limit (when-let [s (opt "--limit" nil)] (js/parseInt s 10)))
(def max-files (js/parseInt (opt "--max-files" "20000") 10))
;; content-hash a tree only when it is small enough to be cheap; above this the
;; comparison degrades to metadata and the row says so.
(def hash-max-bytes (* 512 1024 1024))
(def hash-max-files 8000)

(defn- abs [rel] (.join path root rel))
(defn- load1 [] (aget (.loadavg os) 0))
(defn- fmt-load [] (.toFixed (load1) 0))

;; ---------------------------------------------------------------- classification

(def rebuildable-hard
  #{"node_modules" "target" ".cpcache" ".shadow-cljs" ".gradle" ".cargo" "Pods"
    ".venv" "venv" ".m2" ".stack-work" ".dart_tool" ".bundle" "vendor"
    ".terraform" ".pnpm-store" ".yarn"})

(def rebuildable-soft
  #{"dist" "out" "build" "target-cljs" ".next" ".nuxt" ".svelte-kit" ".astro"
    ".turbo" ".parcel-cache" ".vercel" ".wrangler" "coverage" ".nyc_output"
    "__pycache__" ".pytest_cache" ".mypy_cache" ".ruff_cache" ".clj-kondo"
    "elm-stuff" ".DS_Store" "tmp" ".tmp" "temp" "logs" "log" ".cache"
    ".eslintcache" ".ipynb_checkpoints" "node_repl_history" ".lsp" ".calva"
    ".portal" "cljs-test-runner-out" ".cljs_node_repl" ".idea" ".vscode"})

(def rebuildable-suffix
  [".log" ".class" ".pyc" ".o" ".d" ".map" ".tsbuildinfo"])

(defn- basename [p] (.basename path p))

(defn- classify-entry
  "An ignored entry is rebuildable, or it is a candidate. Directory entries
   arrive with a trailing slash from git.

   **Every path segment is tested, not just the basename.** git reports an
   ignored entry at whatever depth the ignore pattern matched, so a rebuildable
   directory can surface as its individual leaves: measured 2026-08-13,
   `kotoba_kawase/.pytest_cache/README.md` arrived as four separate entries and
   a basename-only test called every one of them a candidate. The rebuildable
   marker is a segment anywhere in the path."
  [rel]
  (let [clean (str/replace rel #"/$" "")
        segs (str/split clean #"/")
        b (basename clean)]
    (cond
      (some rebuildable-hard segs) :rebuildable-hard
      (some rebuildable-soft segs) :rebuildable-soft
      (some #(str/ends-with? b %) rebuildable-suffix) :rebuildable-soft
      :else :candidate)))

;; Filename shapes for secret / identity / datastore material. Reported as path
;; + size + class only. Never as content.
(def secret-shapes
  [[:credential #"^\.env($|\.)"]
   [:credential #"(?i)\.(pem|key|p12|pfx|jks|keystore|ppk|kdbx)$"]
   [:credential #"(?i)^id_(rsa|dsa|ecdsa|ed25519)$"]
   [:credential #"(?i)^credentials?($|[._-])"]
   [:credential #"(?i)(^|[._-])(secret|secrets|password|passwd|apikey)([._-]|$)"]
   [:credential #"(?i)(^|[._-])(api[_-]key|access[_-]token|auth[_-]token)([._-]|$)"]
   [:credential #"^\.(npmrc|netrc|pgpass|htpasswd)$"]
   [:credential #"(?i)(^|[._-])token([._-]|$)"]
   [:identity   #"(?i)(^|[._-])(identity|keypair|privkey|private[_-]key|seed)([._-]|$)"]
   [:identity   #"(?i)\.(jwk|did)$"]
   [:identity   #"(?i)^id_(rsa|dsa|ecdsa|ed25519)\.pub$"]
   [:datastore  #"(?i)\.(sqlite3?|db|ldb|mdb|leveldb|duckdb)$"]
   [:datastore  #"(?i)(^|[._-])(ledger|journal)([._-]|$)"]])

(defn- secret-class [b]
  (some (fn [[k re]] (when (re-find re b) k)) secret-shapes))

;; ---------------------------------------------------------------- walking

(defn- walk!
  "Collect [rel size] for regular files under `dir`, never following symlinks.
   Returns {:files [[rel size]...] :truncated bool}. `dir` is absolute."
  [dir cap]
  (let [acc (volatile! [])
        truncated (volatile! false)]
    (letfn [(go [d prefix]
              (when-not @truncated
                (let [es (try (.readdirSync fs d #js{:withFileTypes true}) (catch :default _ #js[]))]
                  (doseq [e (array-seq es)]
                    (when-not @truncated
                      (let [n (.-name e)
                            full (.join path d n)
                            rel (if (= prefix "") n (str prefix "/" n))]
                        (cond
                          (.isSymbolicLink e) (vswap! acc conj [rel -1])
                          (.isDirectory e) (go full rel)
                          (.isFile e)
                          (let [sz (try (.-size (.statSync fs full)) (catch :default _ 0))]
                            (vswap! acc conj [rel sz])
                            (when (>= (count @acc) cap) (vreset! truncated true)))
                          :else nil)))))))]
      (go dir ""))
    {:files @acc :truncated @truncated}))

(defn- sha256-file [p]
  (try
    (let [h (.createHash crypto "sha256")]
      (.update h (.readFileSync fs p))
      (.digest h "hex"))
    (catch :default _ "unreadable")))

(defn- tree-digest
  "Fold a stable digest over a walked tree. :content reads every file; :metadata
   reads none (path + size only). Returns [digest method]."
  [base files method]
  (let [h (.createHash crypto "sha256")]
    (doseq [[rel sz] (sort-by first files)]
      (.update h (str rel "\u0000"
                      (if (= method :content) (sha256-file (.join path base rel)) sz)
                      "\n")))
    [(.digest h "hex") method]))

(defn- digest-method [files bytes]
  (if (and (<= (count files) hash-max-files) (<= bytes hash-max-bytes)) :content :metadata))

(defn- collect
  "File list for one ignored entry, expressed RELATIVE TO THE CHECKOUT ROOT so
   the shadow side and the twin side are directly comparable. `base` is the
   checkout root (absolute), `rel` the entry path within it, `dir?` whether the
   entry is a directory."
  [base rel dir?]
  (if dir?
    (let [{:keys [files truncated]} (walk! (.join path base rel) max-files)]
      {:files (mapv (fn [[r sz]] [(str rel "/" r) sz]) files) :truncated truncated})
    {:files [[rel (try (.-size (.statSync fs (.join path base rel))) (catch :default _ 0))]]
     :truncated false}))

(defn- survey-entry
  "Walk one ignored entry of a checkout and summarise it."
  [shadow-dir rel]
  (let [clean (str/replace rel #"/$" "")
        dir? (str/ends-with? rel "/")
        cls (classify-entry rel)]
    (if (= cls :rebuildable-hard)
      {:rel clean :class cls :dir? dir? :files 0 :bytes 0 :walked false
       :truncated false :secrets [] :file-list []}
      (let [{:keys [files truncated]} (collect shadow-dir clean dir?)
            bytes (reduce + 0 (map #(max 0 (second %)) files))
            secrets (keep (fn [[r sz]]
                            (when-let [k (secret-class (basename r))]
                              {:path r :size sz :class k}))
                          files)]
        {:rel clean :class cls :dir? dir? :files (count files) :bytes bytes
         :walked true :truncated truncated :secrets (vec secrets)
         :file-list files}))))

;; ---------------------------------------------------------------- git

(defn- ignored-entries
  "One subprocess. Returns [entries err]. NUL-separated so paths are never quoted."
  [dir]
  (try
    (let [out (.execFileSync child "git"
                             #js["-C" dir "status" "--porcelain" "-z" "--ignored=matching"]
                             #js{:encoding "utf8" :maxBuffer (* 256 1024 1024) :timeout 180000
                                 :stdio #js["ignore" "pipe" "pipe"]})]
      [(->> (str/split out #"\x00")
            (remove str/blank?)
            (filter #(str/starts-with? % "!! "))
            (map #(subs % 3))
            vec)
       nil])
    (catch :default e
      (let [m (str/replace (str (.-message e)) #"\s+" " ")]
        [nil (subs m 0 (min 200 (count m)))]))))

;; ---------------------------------------------------------------- per checkout

(defn- survey-checkout [row]
  (let [dir (get row :shadow/dir)
        twin (get row :shadow/twin-path)
        adir (abs dir) atwin (abs twin)
        t0 (js/Date.now)
        [entries err] (ignored-entries adir)]
    (if err
      {:shadow/dir dir :error err :verdict :cannot-assess :ms (- (js/Date.now) t0)}
      (let [surveys (mapv #(survey-entry adir %) entries)
            candidates (filterv #(= :candidate (:class %)) surveys)
            ;; twin comparison, per candidate entry
            compared
            (mapv (fn [c]
                    (let [tfull (.join path atwin (:rel c))
                          present (.existsSync fs tfull)]
                      (if-not present
                        (assoc c :twin :absent :method "-")
                        (let [tw (if (:dir? c) (walk! tfull max-files)
                                     {:files [[(basename (:rel c))
                                               (try (.-size (.statSync fs tfull)) (catch :default _ 0))]]
                                      :truncated false})
                              tfiles (:files tw)
                              tbytes (reduce + 0 (map #(max 0 (second %)) tfiles))
                              m (let [ms (digest-method (:file-list c) (:bytes c))
                                      mt (digest-method tfiles tbytes)]
                                  (if (and (= ms :content) (= mt :content)) :content :metadata))
                              sbase (if (:dir? c) (.join path adir (:rel c)) adir)
                              tbase (if (:dir? c) tfull atwin)
                              [sd _] (tree-digest sbase (:file-list c) m)
                              [td _] (tree-digest tbase tfiles m)]
                          (assoc c :twin (if (= sd td) :same :differs)
                                 :method (name m)
                                 :twin-files (count tfiles) :twin-bytes tbytes)))))
                  candidates)
            unique (filterv #(#{:absent :differs} (:twin %)) compared)
            all-secrets (vec (mapcat :secrets surveys))
            unique-secrets (vec (mapcat :secrets unique))]
        {:shadow/dir dir
         :entries entries
         :surveys surveys
         :compared compared
         :unique unique
         :secrets all-secrets
         :unique-secrets unique-secrets
         :verdict (if (seq unique) :needs-draining :safe-to-retire)
         :ms (- (js/Date.now) t0)}))))

;; ---------------------------------------------------------------- main

(def rows
  ;; `--datoms` is read relative to the CWD (it is a file of THIS repo);
  ;; `--root` only ever prefixes the `orgs/` paths being surveyed.
  (let [src (.readFileSync fs datoms-in "utf8")
        all (edn/read-string src)
        all (vec all)]
    (if limit (vec (take limit all)) all)))

(println (str "shadow-ignored-content-survey  root=" root
              "  checkouts=" (count rows) "  load1=" (fmt-load)))

(def t-start (js/Date.now))
(def results
  (vec (map-indexed
        (fn [i row]
          (when (zero? (mod i 25))
            (println (str "  .. " i "/" (count rows) "  load1=" (fmt-load)
                          "  elapsed=" (js/Math.round (/ (- (js/Date.now) t-start) 1000)) "s")))
          (survey-checkout row))
        rows)))
(def elapsed-s (/ (- (js/Date.now) t-start) 1000))

(def by-dir (into {} (map (juxt #(get % :shadow/dir) identity) rows)))

(def moved
  (vec (for [r results
             :let [orig (get (by-dir (:shadow/dir r)) :shadow/verdict)]
             :when (and (= orig "safe-to-retire") (not= :safe-to-retire (:verdict r)))]
         r)))

(println (str "\n== elapsed " (.toFixed elapsed-s 1) " s  ("
              (.toFixed (/ (* 1000 elapsed-s) (max 1 (count rows))) 0) " ms/checkout)"
              "  load1=" (fmt-load)))

(println (str "\n== ignored content, by class"))
(let [tally (frequencies (mapcat (fn [r] (map :class (:surveys r))) results))]
  (doseq [[k v] (sort-by (comp - val) tally)] (println (str "  " (name k) "  " v " entries"))))

(println (str "\n== checkouts moving safe-to-retire -> needs-draining: " (count moved)))
(doseq [r (sort-by #(- (reduce + 0 (map :bytes (:unique %)))) moved)]
  (println (str "  " (:shadow/dir r)))
  (doseq [u (:unique r)]
    (println (str "      " (:rel u) (when (:dir? u) "/")
                  "  files=" (:files u) "  bytes=" (:bytes u)
                  "  twin=" (name (:twin u)) "  cmp=" (:method u)
                  (when (:truncated u) "  TRUNCATED")))))

(println (str "\n== secret / identity shaped files (paths and sizes only; contents never read for reporting)"))
(doseq [r results
        :when (seq (:secrets r))]
  (println (str "  " (:shadow/dir r)))
  (doseq [s (:secrets r)]
    (println (str "      [" (name (:class s)) "] " (:path s) "  " (:size s) " B"))))

(def errs (filterv :error results))
(when (seq errs)
  (println (str "\n== could not assess: " (count errs)))
  (doseq [e errs] (println (str "  " (:shadow/dir e) "  " (:error e)))))

;; disk arithmetic ------------------------------------------------------------
(defn- kb [d] (or (get (by-dir d) :shadow/disk-kb) 0))
(def revised
  (into {} (for [r results]
             [(:shadow/dir r)
              (let [orig (get (by-dir (:shadow/dir r)) :shadow/verdict)]
                (cond
                  (= orig "cannot-assess") "cannot-assess"
                  (:error r) "cannot-assess"
                  (= orig "needs-draining") "needs-draining"
                  (= :needs-draining (:verdict r)) "needs-draining"
                  :else "safe-to-retire"))])))
(def gib (fn [k] (.toFixed (/ k 1048576.0) 2)))
(println "\n== revised ranking")
(doseq [v ["safe-to-retire" "needs-draining" "cannot-assess"]]
  (let [ds (filterv #(= v (revised %)) (keys revised))]
    (println (str "  " v "  " (count ds) "  " (gib (reduce + 0 (map kb ds))) " GiB"))))

;; emit -----------------------------------------------------------------------
(when out-file
  (let [ent (map-indexed
             (fn [i r]
               (let [d (:shadow/dir r) src (by-dir d)]
                 {:db/id (- (inc i))
                  :source/dataset "shadow-ignored"
                  :shadow/dir d
                  :shadow/twin-path (get src :shadow/twin-path)
                  :shadow/org (get src :shadow/org)
                  :shadow/disk-kb (get src :shadow/disk-kb)
                  :shadow/verdict-git-status (get src :shadow/verdict)
                  :shadow/verdict-revised (revised d)
                  :shadow/ignored-entry-count (count (:entries r))
                  :shadow/ignored-entries (str/join " | " (:entries r))
                  :shadow/ignored-rebuildable-entries
                  (str/join " | " (map :rel (filter #(not= :candidate (:class %)) (:surveys r))))
                  :shadow/ignored-candidate-entries
                  (str/join " | " (map :rel (filter #(= :candidate (:class %)) (:surveys r))))
                  :shadow/ignored-candidate-files (reduce + 0 (map :files (:compared r)))
                  :shadow/ignored-candidate-bytes (reduce + 0 (map :bytes (:compared r)))
                  :shadow/ignored-unique-entries
                  (str/join " | " (map #(str (:rel %) "(" (name (:twin %)) "," (:method %) ")") (:unique r)))
                  :shadow/ignored-unique-bytes (reduce + 0 (map :bytes (:unique r)))
                  :shadow/ignored-truncated (boolean (some :truncated (:surveys r)))
                  :shadow/secret-file-count (count (:secrets r))
                  :shadow/secret-files
                  (str/join " | " (map #(str (name (:class %)) ":" (:path %) ":" (:size %) "B") (:secrets r)))
                  :shadow/secret-unique-count (count (:unique-secrets r))
                  :shadow/survey-error (or (:error r) "")
                  :shadow/survey-ms (:ms r)}))
             results)]
    ;; `--out` is relative to the CWD, not to `--root`. `--root` names the tree
    ;; being surveyed, which is the shared superproject checkout; writing the
    ;; report there would litter a tree this script is only allowed to read.
    (.writeFileSync fs out-file
                    (str "[" (str/join "\n " (map pr-str ent)) "]\n"))
    (println (str "\nwrote " out-file "  (" (count ent) " entities)"))))

(js/process.exit (cond (seq errs) 2 (seq moved) 1 :else 0))
