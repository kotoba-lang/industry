#!/usr/bin/env nbb
(ns verify-shadow-warnings-as-errors
  "shadow-cljs builds that cannot fail on a broken var reference.

  `shadow-cljs release` treats an undeclared or renamed var as a WARNING and
  exits 0, having written a bundle that throws `Cannot read properties of
  undefined` on its first request. So `build exit=0` does not distinguish a
  working bundle from a broken one, and every place that treats a green build
  as a gate is measuring nothing.

  Measured 2026-08-18 in cloud-itonami/app-cowork during the SvelteKit ->
  ClojureScript migration: renaming a referenced var produced a shipped bundle
  and rc=0. With `:warnings-as-errors true` under `:compiler-options` the same
  mutation gives rc=1 AND leaves dist/ byte-unchanged -- a failing build stops
  shipping.

  **The fix has a trap.** `:warnings-as-errors` under `:build-options` is
  silently ignored; shadow reads it at [:compiler-options :warnings-as-errors].
  The misplaced key looks like the fix and is itself a check that cannot fail.
  This detector therefore reports WHERE the key is, not merely whether the
  string appears.

  Fleet measurement the day this was written: 183 repos ship a shadow-cljs.edn
  and 0 of them set it anywhere.

  What this does NOT do: build anything, or judge whether a given repo's
  bundle is currently broken. It reports which builds are incapable of saying
  so. Fixing a repo means adding the key AND showing, in that repo, that a
  broken var now fails -- adding the key without that demonstration repeats
  the mistake this exists to catch.

  **It reads CHECKOUTS under orgs/, which are not the repos main branches.**
  A repo fixed on main still reads as unfixed here until its checkout moves,
  and a repo whose checkout predates its shadow-cljs.edn is not listed at all
  -- it looks like a repo with no shadow build. Measured while writing this:
  cloud-itonami/app-cowork had landed the key on main (df7bdb4) while its
  checkout sat at caef100, before the file existed, so it appeared in NO
  category. Do not read a repos absence from this output as evidence about
  its main. Check with:

    gh api repos/<org>/<repo>/contents/shadow-cljs.edn --jq .content | base64 -d

  usage:
    nbb scripts/verify-shadow-warnings-as-errors.cljs [<root>] [--all]

  <root> defaults to the superproject. Exit 0 clean / 1 findings /
  2 COULD NOT ANSWER."
  (:require ["fs" :as fs]
            [clojure.string :as str]))

(def argv (vec *command-line-args*))
(def root (or (first (remove #(str/starts-with? % "--") argv))
              "/Users/junkawasaki/github/com-junkawasaki"))
(def all? (some #{"--all"} argv))

(defn- slurp* [p] (try (.readFileSync fs p "utf8") (catch :default _ nil)))
(defn- dirs [p] (try (->> (.readdirSync fs p #js {:withFileTypes true})
                          (filter #(.isDirectory %)) (map #(.-name %)) vec)
                     (catch :default _ [])))

(defn- configs
  "orgs/<org>/<repo>/shadow-cljs.edn の一覧。深く潜らない —— shadow の設定は
   repo 直下に置く規約で、潜ると node_modules の中の他人の設定を拾う。"
  []
  (let [orgs-dir (str root "/orgs")]
    (vec (for [org (dirs orgs-dir)
               repo (dirs (str orgs-dir "/" org))
               :let [p (str orgs-dir "/" org "/" repo "/shadow-cljs.edn")]
               :when (try (.existsSync fs p) (catch :default _ false))]
           {:repo (str "orgs/" org "/" repo) :path p}))))

(defn- classify
  "text -> :ok | :misplaced | :absent | :unreadable

   :misplaced は `:build-options` の中に置かれている場合。文字列があることを
   もって合格にしない —— そこに書いても shadow は読まない。"
  [text]
  (cond
    (nil? text) :unreadable
    (re-find #":compiler-options[^}]*:warnings-as-errors\s+true" text) :ok
    (re-find #":build-options[^}]*:warnings-as-errors" text) :misplaced
    (re-find #":warnings-as-errors" text) :misplaced
    :else :absent))

(let [cs (configs)]
  (println (str "SCANNED\t" (count cs)))
  (when (zero? (count cs))
    (println "UNDETERMINED\tno shadow-cljs.edn found under orgs/ -- is <root> a superproject checkout?")
    (js/process.exit 2))
  (let [rs (map (fn [{:keys [repo path]}] (assoc {} :repo repo :verdict (classify (slurp* path)))) cs)
        by (group-by :verdict rs)
        unreadable (:unreadable by)
        absent (:absent by)
        misplaced (:misplaced by)
        ok (:ok by)]
    (println (str "ok=" (count ok)
                  " absent=" (count absent)
                  " misplaced=" (count misplaced)
                  " unreadable=" (count unreadable)))
    (println (str "NOTE\tthis reads checkouts under orgs/, not the repos main branches; "
                  "a repo fixed on main but not checked out here is invisible to it"))
    (when (seq misplaced)
      (println "\nMISPLACED — the key is present but shadow does not read it there:")
      (doseq [r misplaced] (println (str "  " (:repo r)))))
    (when (seq absent)
      (println "\nABSENT — a broken var reference cannot fail these builds:")
      (doseq [r (if all? absent (take 20 absent))] (println (str "  " (:repo r))))
      (when (and (not all?) (> (count absent) 20))
        (println (str "  … 他 " (- (count absent) 20) " 件（--all で全件）"))))
    (when (seq unreadable)
      (println "\nUNDETERMINED — could not read:")
      (doseq [r unreadable] (println (str "  " (:repo r))))
      (println "Refusing to report a pass: some configs were not read.")
      (js/process.exit 2))
    (if (or (seq absent) (seq misplaced))
      (js/process.exit 1)
      (do (println "\nOK\tevery shadow-cljs build can fail on a broken var") (js/process.exit 0)))))
