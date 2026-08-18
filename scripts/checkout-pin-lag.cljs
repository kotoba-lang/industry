#!/usr/bin/env nbb
;; scripts/checkout-pin-lag.cljs — is each checkout AT ITS WEST PIN?
;;
;;   nbb --classpath ".:scripts/nbb_compat" scripts/checkout-pin-lag.cljs \
;;     --west <path to a west.yml> [--root <orgs-bearing checkout>] [--names]
;;
;; ## The third leg
;;
;; CLAUDE.md and ADR-2608136800 say it repeatedly: the checkout, the west pin and
;; the repository's main are THREE different things. `scripts/checkout-staleness.cljs`
;; measures one pair — checkout against its remote's default branch, deliberately
;; without a network round trip. Nothing measured the other pair, and the maturity
;; loop's skill names it as a precondition: a checkout behind its pin measures work
;; that has already landed as absent. This answers that one leg and nothing else.
;;
;; ## Four answers, because there are four states
;;
;;   at-pin        HEAD is the pin.
;;   ahead         the pin is an ancestor of HEAD. NOT A PROBLEM -- an actor that
;;                 commits on its own is legitimately in front of its pin, and HEAD
;;                 is the truth there (the maturity skill says not to "fix" these).
;;   behind        HEAD does not contain the pin. THIS is what under-measures.
;;   cannot-tell   the pinned commit is not in the local clone, so git cannot
;;                 answer. Reported separately and never folded into the others:
;;                 measured 2026-08-15, this was 989 of 4,157 checkouts, and only
;;                 31 of those were shallow -- the rest had simply never fetched
;;                 that commit. Folding them into `ahead` hides a quarter of the
;;                 fleet.
;;
;; ## Why it decides on EXIT STATUS, in one sentence
;;
;; `git merge-base --is-ancestor A B` writes NOTHING to stdout; it answers 0 or 1.
;; The ad-hoc version of this check read stdout, mapped failure to nil, and let
;; `(or nil "")` collapse both branches to the empty string -- so it returned
;; "ancestor" either way and was structurally incapable of reporting `behind`. It
;; reported `0 behind` twice on 2026-08-15 while 308 checkouts were behind. That is
;; the whole reason this file exists as a file rather than a shell one-liner.
;;
;; ## What it does NOT do
;;
;; It does not fetch and it does not move anything: no `west update`, no checkout,
;; no write. Fixing is the caller's decision, and it is expensive -- measured, a
;; batch of 120 `cannot-tell` checkouts did not finish fetching in ten minutes,
;; because those need real object transfers.
;;
;; The numbers are a SNAPSHOT, not a state. The fleet advances pins continuously, so
;; comparing against a west.yml read once will drift while the run proceeds: during
;; the 2026-08-15 sweep `ahead` moved from 3 to 94 for that reason alone.
;;
;; Exit codes: 0 nothing behind · 1 something behind · 2 COULD NOT ANSWER.

(ns checkout-pin-lag
  (:require ["fs" :as fs]
            ["path" :as path]
            ["child_process" :as cp]
            [clojure.string :as str]))

(def args (vec (drop 2 (js->clj js/process.argv))))
(defn- flag [n] (boolean (some #{n} args)))
(defn- opt [n] (second (drop-while #(not= n %) args)))

(def root (or (opt "--root") (.cwd js/process)))
(def west (or (opt "--west") (path/join root "manifest" "west.yml")))
(def names? (flag "--names"))

;; Measured 2026-08-15: west.yml holds 4,175 projects and 4,157 are populated here.
;; A run that parses far fewer is reading the wrong file, and reporting "nothing
;; behind" from nothing parsed is the failure this script was written about.
(def floor-entries 3000)

(defn- die [code msg] (println msg) (js/process.exit code))

(defn- git-status [dir a]
  (.-status (.spawnSync cp "git" (clj->js (concat ["-C" dir] a)) #js {:encoding "utf8"})))

(defn- git-out [dir a]
  (let [r (.spawnSync cp "git" (clj->js (concat ["-C" dir] a)) #js {:encoding "utf8"})]
    (when (zero? (.-status r)) (str/trim (str (.-stdout r))))))

(defn- entries
  "name / revision / path triples out of west.yml. Parsed by indentation-free
   prefix rather than with a YAML library, which is what the other manifest
   scripts in this repository do."
  [text]
  (loop [ls (str/split-lines text) cur {} acc []]
    (if (empty? ls)
      acc
      (let [t (str/trim (first ls))]
        (cond
          (str/starts-with? t "- name:")
          (recur (rest ls) {:name (str/trim (subs t 7))} acc)

          (str/starts-with? t "revision:")
          (recur (rest ls) (assoc cur :rev (str/trim (subs t 9))) acc)

          (str/starts-with? t "path:")
          (let [e (assoc cur :path (str/trim (subs t 5)))]
            (recur (rest ls) {} (if (and (:name e) (:rev e) (:path e)) (conj acc e) acc)))

          :else (recur (rest ls) cur acc))))))

(defn- classify [e]
  (let [d (path/join root (:path e))
        head (git-out d ["rev-parse" "HEAD"])
        pin (:rev e)
        ;; EXIT STATUS. See the header.
        st (when (and head pin) (git-status d ["merge-base" "--is-ancestor" pin "HEAD"]))]
    (assoc e
           :head head
           :state (cond
                    (nil? head) :cannot-tell
                    (= head pin) :at-pin
                    (= 0 st) :ahead
                    (= 1 st) :behind
                    :else :cannot-tell)
           :shallow (= "true" (git-out d ["rev-parse" "--is-shallow-repository"])))))

(defn- dirty?
  "Only ever asked about the behind set. `git status --porcelain` walks the whole
  worktree, so asking it for all 4,158 checkouts took this script from seconds to
  over two minutes (measured 2026-08-16, on the first version of this change).
  The question is only meaningful where the answer changes what to do."
  [e]
  (not (str/blank? (or (git-out (path/join root (:path e)) ["status" "--porcelain"]) ""))))

(let [text (when (fs/existsSync west) (str (fs/readFileSync west "utf8")))]
  (when-not text
    (die 2 (str "CANNOT ANSWER: " west " is absent. Pass --west, or run from a"
                " checkout that has manifest/west.yml.")))
  (let [es (entries text)]
    (when (< (count es) floor-entries)
      (die 2 (str "CANNOT ANSWER: parsed " (count es) " projects from " west
                  ", floor " floor-entries ". Reporting nothing behind from"
                  " nothing parsed is the exact failure this script exists to"
                  " prevent.")))
    (let [present (filterv #(fs/existsSync (path/join root (:path %) ".git")) es)
          rows (mapv classify present)
          by0 (group-by :state rows)
          ;; dirty? is asked here and only here -- see its docstring.
          by (assoc by0 :behind (mapv #(assoc % :dirty (dirty? %)) (get by0 :behind [])))
          n (fn [k] (count (get by k [])))]
      (when (zero? (count present))
        (die 2 (str "CANNOT ANSWER: none of the " (count es) " projects is populated"
                    " under --root " root ".")))
      (println (str "SCANNED\t" (count present) "\tcheckout-pin-lag"))
      (println (str "projects in " west ": " (count es)
                    " · populated here: " (count present)))
      (println (str "  at-pin       " (n :at-pin)))
      (println (str "  ahead        " (n :ahead) "   (left alone -- HEAD is the truth)"))
      (let [bs (get by :behind [])
            bd (count (filterv :dirty bs))]
        (println (str "  behind       " (count bs) "   (these measure landed work as absent)"))
        (when (pos? (count bs))
          (println (str "    of those, dirty  " bd "   (uncommitted work -- `west update`"
                        " refuses these, correctly. Not yours to sync)"))
          (println (str "                clean " (- (count bs) bd)
                        "   (the actionable ones)"))))
      (println (str "  cannot-tell  " (n :cannot-tell)
                    "   (pin not in the local clone; "
                    (count (filter :shallow (get by :cannot-tell []))) " shallow)"))
      (when names?
        (doseq [k [:behind :cannot-tell]]
          (println (str "\n-- " (name k) " --"))
          (doseq [r (get by k [])]
            (println (str (:name r) (when (:dirty r) "\tdirty"))))))
      (println (str "\nThese are a snapshot, not a state: the fleet advances pins"
                    " while this runs. Fixing is not done here -- `west update"
                    " --fetch smart <names>` with xargs, on the CLEAN ones only,"
                    " and budget for it, since a batch of 120 cannot-tell checkouts"
                    " did not finish fetching in ten minutes on 2026-08-15."
                    " The exit code stays 1 while anything is behind, dirty or not:"
                    " the measurement is degraded either way. What changes is whose"
                    " problem it is."))
      (js/process.exit (if (pos? (n :behind)) 1 0)))))
