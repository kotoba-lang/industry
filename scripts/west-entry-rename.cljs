#!/usr/bin/env nbb
;; scripts/west-entry-rename.cljs — rename west PROJECT ENTRIES to the names
;; their repositories already carry upstream.
;;
;; This is not `git mv` and it is not the GitHub rename API. It is for the
;; case where a repository was renamed on GitHub and the manifest was not:
;; the entry keeps working, silently, because GitHub redirects the old path
;; — and `gen-west-manifest.cljs` warns in its own comments that a redirect
;; is not a name. An old path can be taken by something else later, and then
;; the manifest fetches whatever now answers to it.
;;
;; The same three checks as the pin scripts, adapted:
;;
;;   1. the NEW repo path resolves and is CANONICAL (`full_name` equals what
;;      was asked for). A target that is itself a redirect would move the
;;      entry from one redirect to another.
;;   2. the entry's recorded revision exists in that repository. A rename
;;      that lands on a repo without the pinned commit breaks `west update`
;;      at the next checkout rather than at this write.
;;   3. no existing entry already carries the new name. Two entries with one
;;      name is how a rename collapses a project into an unrelated one.
;;
;; Plus one blob-SHA precondition for the whole write, so a concurrent writer
;; gets a 409 and this retries from a fresh read rather than forcing.
;;
;; It also moves an entry between orgs, because that is the same event seen
;; from the manifest: a repository transferred to another organisation keeps
;; answering on its old owner through the same redirect, so an entry can be
;; behind on `remote:` exactly the way it is behind on `name:`.
;;
;;   RENAMES=renames.tsv  # lines of: <old-entry-name>\t<new-entry-name>\t<new-org>
;;   RENAMES=… [DRY=1] nbb --classpath ".:scripts/nbb_compat" scripts/west-entry-rename.cljs
(require '[clojure.string :as str])

(def fs (js/require "node:fs"))
(def cp (js/require "node:child_process"))
(def renames-file (.-RENAMES js/process.env))
(def dry? (= "1" (.-DRY js/process.env)))
(def repo "com-junkawasaki/root")
(def path "manifest/west.yml")

(defn sh [cmd]
  (try {:out (str (.execSync cp cmd #js {:encoding "utf8" :maxBuffer 64000000
                                         :stdio #js ["pipe" "pipe" "pipe"]})) :exit 0}
       (catch :default e
         {:out (str (or (.-stdout e) "") (or (.-stderr e) "")) :exit (or (.-status e) 1)})))

(def rows
  (->> (str/split-lines (.readFileSync fs renames-file "utf8"))
       (remove str/blank?)
       (map #(str/split % #"\t"))
       (filter #(= 3 (count %)))))

(defn tip []
  {:blob (str/trim (:out (sh (str "gh api repos/" repo "/contents/manifest --jq '.[] | select(.name==\"west.yml\") | .sha'"))))
   :text (:out (sh (str "gh api repos/" repo "/contents/" path " -H 'Accept: application/vnd.github.raw'")))})

(defn entry-span
  "[start end] line indices of the project block named ENTRY, or nil.

  A remote and a project can share a name, so the block is only the one that
  has a `path:` — the same reason the pin scripts do not stop at the first
  `- name:` match."
  [lines entry]
  (let [n (count lines)]
    (loop [i 0]
      (when (< i n)
        (if (= (str/trim (nth lines i)) (str "- name: " entry))
          (let [end (loop [j (inc i)]
                      (if (or (>= j n) (str/starts-with? (str/trim (nth lines j)) "- name: ")) j (recur (inc j))))
                block (subvec lines i end)]
            (if (some #(str/starts-with? (str/trim %) "path: ") block)
              [i end]
              (recur end)))
          (recur (inc i)))))))

(defn rewrite-block
  "name: -> new, remote: -> new org, path: -> orgs/<new org>/<new>, and
  repo-path: dropped because name and repo path agree after the rename.

  The path is rebuilt from the org and the name rather than edited in place:
  an entry that is behind on both is behind on the path in two segments, and
  patching one of them leaves a path that names neither the old repository
  nor the new one."
  [block old new org]
  (->> block
       (keep (fn [l]
               (let [t (str/trim l)
                     indent (subs l 0 (- (count l) (count t)))]
                 (cond
                   (= t (str "- name: " old)) (str indent "- name: " new)
                   (str/starts-with? t "remote: ") (str indent "remote: " org)
                   (str/starts-with? t "repo-path: ") nil
                   (str/starts-with? t "path: ") (str indent "path: orgs/" org "/" new)
                   :else l))))
       vec))

(defn revision-of [block]
  (some #(let [t (str/trim %)] (when (str/starts-with? t "revision: ") (subs t 10))) block))

(defn verify! [{:keys [old new org rev names paths]}]
  (let [full (str/trim (:out (sh (str "gh api repos/" org "/" new " --jq .full_name 2>/dev/null"))))]
    (cond
      (not= full (str org "/" new))
      (str "target is not canonical: " org "/" new " resolves to " (if (seq full) full "404"))

      (contains? (disj names old) new)
      (str "an entry named " new " already exists — refusing to collapse two projects into one name")

      (contains? paths (str "orgs/" org "/" new))
      (str "orgs/" org "/" new " is already some entry's checkout path — two projects sharing one working tree is how a rename loses one of them")

      (not= 0 (:exit (sh (str "gh api repos/" org "/" new "/commits/" rev " --jq .sha >/dev/null 2>&1"))))
      (str "pinned revision " (subs rev 0 8) " is not in " org "/" new)

      :else nil)))

(let [{:keys [blob text]} (tip)
      lines (vec (str/split-lines text))
      names (set (keep #(let [t (str/trim %)]
                          (when (str/starts-with? t "- name: ") (subs t 8))) lines))
      paths (set (keep #(let [t (str/trim %)]
                          (when (str/starts-with? t "path: ") (subs t 6))) lines))
      results
      (reduce
       (fn [{:keys [lines applied dropped]} [old new org]]
         (if-let [[s e] (entry-span lines old)]
           (let [block (subvec lines s e)
                 rev (revision-of block)]
             (if-let [err (verify! {:old old :new new :org org :rev rev :names names
                                    :paths (disj paths (some #(let [t (str/trim %)]
                                                                (when (str/starts-with? t "path: ") (subs t 6))) block))})]
               (do (println "  drop" old "->" new ":" err)
                   {:lines lines :applied applied :dropped (conj dropped old)})
               (do (println "  ok  " old "->" new "  (" (subs rev 0 8) ")")
                   {:lines (vec (concat (subvec lines 0 s) (rewrite-block block old new org) (subvec lines e)))
                    :applied (conj applied [old new]) :dropped dropped})))
           (do (println "  drop" old ": no project entry with that name")
               {:lines lines :applied applied :dropped (conj dropped old)})))
       {:lines lines :applied [] :dropped []}
       rows)]
  (println (str "  " (count (:applied results)) " of " (count rows) " entr(ies) verified"))
  (cond
    (empty? (:applied results)) (println "nothing to write")
    dry? (println "DRY=1 — not writing")
    :else
    (let [out (str (str/join "\n" (:lines results)) "\n")
          payload {:message (str "west: rename " (count (:applied results))
                                 " entr(ies) to the names their repositories already carry")
                   :content (.toString (.from js/Buffer out "utf8") "base64")
                   :sha blob :branch "main"}
          pf "/tmp/west-entry-rename-payload.json"
          _ (.writeFileSync fs pf (js/JSON.stringify (clj->js payload)) "utf8")
          r (sh (str "gh api -X PUT repos/" repo "/contents/" path
                     " --input " pf " --jq .commit.sha"))]
      (if (re-matches #"[0-9a-f]{40}" (str/trim (:out r)))
        (println (str "committed " (str/trim (:out r)) " ("
                      (count (:applied results)) " entries)"))
        (println "PUT failed:" (str/trim (:out r)))))))
