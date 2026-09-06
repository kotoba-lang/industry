#!/usr/bin/env nbb
;; scripts/west-entry-remove.cljs — unregister west project entries.
;;
;; The counterpart to west-entry-rename.cljs, and the more dangerous one: a
;; rename that is wrong still points at a repository, and a removal that is
;; wrong points at nothing. Two cases need it — a repository that is being
;; retired, and a duplicate entry for a repository another entry already
;; registers — and both are easy to get backwards.
;;
;; So it refuses unless it can see what is being given up:
;;
;;   1. the entry exists and is a PROJECT (has a `path:`). A remote and a
;;      project can share a name.
;;   2. a local checkout at that path, if there is one, is CLEAN: no
;;      uncommitted changes and no commits its remote does not have. An
;;      unregistered path stops being updated and stops being noticed, so
;;      work left in one is work that disappears quietly.
;;   3. REASON is set and non-empty. Every removal is a decision; a removal
;;      with no reason recorded is one nobody can audit later.
;;
;; It does NOT check whether the upstream repository still exists: removing
;; the entry BEFORE deleting the repository is the correct order, and a
;; check for absence would force the dangerous one.
;;
;;   ENTRIES=names.txt REASON="..." [DRY=1] \
;;     nbb --classpath ".:scripts/nbb_compat" scripts/west-entry-remove.cljs
(require '[clojure.string :as str])

(def fs (js/require "node:fs"))
(def cp (js/require "node:child_process"))
(def entries-file (.-ENTRIES js/process.env))
(def reason (.-REASON js/process.env))
(def dry? (= "1" (.-DRY js/process.env)))
(def repo "com-junkawasaki/root")
(def path "manifest/west.yml")

(defn sh [cmd]
  (try {:out (str (.execSync cp cmd #js {:encoding "utf8" :maxBuffer 64000000
                                         :stdio #js ["pipe" "pipe" "pipe"]})) :exit 0}
       (catch :default e
         {:out (str (or (.-stdout e) "") (or (.-stderr e) "")) :exit (or (.-status e) 1)})))

(when-not (and (string? reason) (seq (str/trim (or reason ""))))
  (println "REFUSING: REASON is empty. A removal with no reason recorded is one nobody can audit later.")
  (js/process.exit 2))

(def wanted
  (->> (str/split-lines (.readFileSync fs entries-file "utf8"))
       (map str/trim) (remove str/blank?) vec))

(defn tip []
  {:blob (str/trim (:out (sh (str "gh api repos/" repo "/contents/manifest --jq '.[] | select(.name==\"west.yml\") | .sha'"))))
   :text (:out (sh (str "gh api repos/" repo "/contents/" path " -H 'Accept: application/vnd.github.raw'")))})

(defn entry-span [lines entry]
  (let [n (count lines)]
    (loop [i 0]
      (when (< i n)
        (if (= (str/trim (nth lines i)) (str "- name: " entry))
          (let [end (loop [j (inc i)]
                      (if (or (>= j n) (str/starts-with? (str/trim (nth lines j)) "- name: ")) j (recur (inc j))))]
            (if (some #(str/starts-with? (str/trim %) "path: ") (subvec lines i end))
              [i end]
              (recur end)))
          (recur (inc i)))))))

(defn checkout-objection
  "nil when the working tree at PATH may be given up."
  [p]
  (when (zero? (:exit (sh (str "test -e " p "/.git"))))
    (let [dirty (str/trim (:out (sh (str "git -C " p " status --porcelain 2>/dev/null | head -5"))))
          ahead (str/trim (:out (sh (str "git -C " p " log --branches --not --remotes --oneline 2>/dev/null | head -3"))))]
      (cond
        (seq dirty) (str "checkout at " p " has uncommitted changes")
        (seq ahead) (str "checkout at " p " has commits no remote has: " (first (str/split-lines ahead)))
        :else nil))))

(let [{:keys [blob text]} (tip)
      lines (vec (str/split-lines text))
      result
      (reduce
       (fn [{:keys [lines removed]} name]
         (if-let [[s e] (entry-span lines name)]
           (let [block (subvec lines s e)
                 p (some #(let [t (str/trim %)] (when (str/starts-with? t "path: ") (subs t 6))) block)]
             (if-let [err (checkout-objection p)]
               (do (println "  keep" name ":" err) {:lines lines :removed removed})
               (do (println "  remove" name " (" p ")")
                   {:lines (vec (concat (subvec lines 0 s) (subvec lines e)))
                    :removed (conj removed name)})))
           (do (println "  keep" name ": no project entry with that name")
               {:lines lines :removed removed})))
       {:lines lines :removed []} wanted)]
  (println (str "  " (count (:removed result)) " of " (count wanted) " entr(ies) removable"))
  (cond
    (empty? (:removed result)) (println "nothing to write")
    dry? (println "DRY=1 — not writing")
    :else
    (let [out (str (str/join "\n" (:lines result)) "\n")
          payload {:message (str "west: unregister " (count (:removed result))
                                 " entr(ies) — " (str/trim reason))
                   :content (.toString (.from js/Buffer out "utf8") "base64")
                   :sha blob :branch "main"}
          pf "/tmp/west-entry-remove-payload.json"
          _ (.writeFileSync fs pf (js/JSON.stringify (clj->js payload)) "utf8")
          r (sh (str "gh api -X PUT repos/" repo "/contents/" path " --input " pf " --jq .commit.sha"))]
      (if (re-matches #"[0-9a-f]{40}" (str/trim (:out r)))
        (println (str "committed " (str/trim (:out r)) " (" (count (:removed result)) " entries)"))
        (println "PUT failed:" (str/trim (:out r)))))))
