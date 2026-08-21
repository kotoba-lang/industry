#!/usr/bin/env nbb
(ns make-consumable
  "Make ONE kotoba-lang library consumable: name its :local/root siblings by git
  coordinate, VERIFY, then commit deps.edn on main.

  usage:
    nbb scripts/make-consumable.cljs <repo>              ; dry-run (verify only)
    nbb scripts/make-consumable.cljs <repo> --execute    ; verify, then commit

  VERIFICATION IS THE POINT, and it is done the way the defect is defined rather than by
  reading the diff. `clojure -Spath` needs nothing but deps.edn, so the rewritten file is
  dropped into an EMPTY directory and resolved there -- which is exactly the situation a
  forker is in, and exactly the situation the workspace never reproduces. Nothing is
  committed unless that resolves.

  ORDER MATTERS. A library pinned by coordinate is only usable if the sibling it pins is
  itself consumable, so this must run bottom-up. Of the 85 libraries the audit found, 63
  had every sibling already clean and 22 were blocked behind another broken one. Running
  it on a blocked repo simply fails the standalone check -- it does not produce a
  plausible-looking commit.

  Companion to scripts/consumability-audit.cljs, which measures the population."
  (:require ["fs" :as fs] ["child_process" :as cp] [clojure.edn :as edn] [clojure.string :as str]))

(defn- sh [cmd] (try {:ok true :out (str (cp/execSync cmd #js {:maxBuffer 20000000
                                                               :stdio #js ["ignore" "pipe" "pipe"]}))}
                     (catch :default e {:ok false :out (str e)})))
(defn- gh [path & [extra]]
  (let [{:keys [ok out]} (sh (str "gh api " path " " (or extra "") " 2>/dev/null"))]
    (when ok (str/trim out))))

(def repo (nth *command-line-args* 0))
(def execute? (= "--execute" (nth *command-line-args* 1 nil)))

(def blob-sha (gh (str "repos/kotoba-lang/" repo "/contents/deps.edn") "--jq .sha"))
(def text (gh (str "repos/kotoba-lang/" repo "/contents/deps.edn")
              "-H 'Accept: application/vnd.github.raw'"))
(def parsed (edn/read-string text))
(defn- org+repo
  "Resolve a :local/root path to <org>/<repo>.

  The org comes from the PATH, not from an assumption. \"../css\" is a sibling in the
  same org; \"../../cloud-itonami/cloud-itonami-isic-6311\" is not. Assuming
  kotoba-lang for every last path segment would have pinned a DIFFERENT repository
  that happened to share a name -- kotoba-lang/securities was the case that found
  this, and it only refused because no kotoba-lang repo of that name exists. A repo
  that did exist would have been pinned silently and wrongly."
  [root default-org]
  (let [segs (remove #{"" "." ".."} (str/split root #"/"))]
    (if (>= (count segs) 2)
      {:org (nth segs (- (count segs) 2)) :repo (last segs)}
      {:org default-org :repo (last segs)})))

(def locals (for [[k v] (:deps parsed) :when (and (map? v) (:local/root v))]
              (let [{:keys [org repo]} (org+repo (:local/root v) "kotoba-lang")]
                {:coord k :root (:local/root v) :org org :sibling repo})))

(when (empty? locals)
  (println repo ": nothing to do (no :local/root in :deps)")
  (js/process.exit 0))

;; Resolve each sibling's current default-branch HEAD.
(def resolved
  (doall (for [{:keys [org sibling] :as l} locals]
           (assoc l :sha (gh (str "repos/" org "/" sibling "/commits/HEAD") "--jq .sha")))))

(when (some (comp nil? :sha) resolved)
  (println repo ": REFUSED — could not resolve a sibling's HEAD:"
           (pr-str (map :sibling (filter (comp nil? :sha) resolved))))
  (js/process.exit 3))

(defn- rewrite [t]
  (reduce (fn [acc {:keys [root org sibling sha]}]
            (let [url (str "https://github.com/" org "/" sibling ".git")
                  needle (str "{:local/root \"" root "\"}")
                  repl (str "{:git/url \"" url "\"\n"
                            "                                     :git/sha \"" sha "\"}")]
              (if (str/includes? acc needle)
                (str/replace acc needle repl)
                ;; multi-line form: replace just the :local/root pair inside it
                (str/replace acc (str ":local/root \"" root "\"")
                             (str ":git/url \"" url "\"\n"
                                  "         :git/sha \"" sha "\"")))))
          t resolved))

(def next-text (rewrite text))

(when (str/includes? next-text ":local/root")
  ;; only the :deps ones must go; an alias may legitimately keep one
  (let [after (edn/read-string next-text)]
    (when (some #(and (map? %) (:local/root %)) (vals (:deps after)))
      (println repo ": REFUSED — a :local/root survived in :deps after rewriting")
      (js/process.exit 4))))

;; Verify in an empty directory: nothing but this deps.edn.
(def probe (str "/tmp/consumable-probe/" repo))
(sh (str "rm -rf " probe " && mkdir -p " probe))
(fs/writeFileSync (str probe "/deps.edn") next-text)
(def spath (sh (str "cd " probe " && timeout 300 clojure -Spath")))

(if-not (:ok spath)
  (do (println repo ": REFUSED — classpath does not resolve standalone")
      (println (str/join "\n" (take 4 (str/split-lines (:out spath)))))
      (js/process.exit 5))
  (do
    (println repo ": resolves standalone ✓  ("
             (str/join ", " (map #(str (:org %) "/" (:sibling %) "@" (subs (:sha %) 0 8)) resolved)) ")")
    (if-not execute?
      (println "  dry-run: not committed")
      (let [msg (str "deps: name " (str/join " / " (map :sibling resolved))
                     " by git coordinate\n\n"
                     "A library whose :deps use :local/root cannot build a classpath outside a\n"
                     "monorepo checkout: tools.deps resolves \"../sibling\" relative to the CONSUMER's\n"
                     "gitlibs directory, where nothing of the sort exists. This repository is published\n"
                     "to be forked, and a forker's first command failed.\n\n"
                     "Verified the way the defect is defined: deps.edn alone in an empty directory,\n"
                     "where `clojure -Spath` now resolves. The workspace never reproduces that\n"
                     "situation, which is why nothing here failed before.\n\n"
                     "Siblings pinned at their current default-branch HEAD.\n\n"
                     "Part of the sweep measured by scripts/consumability-audit.cljs (85 libraries).")
            payload (js/JSON.stringify
                     (clj->js {:branch "main" :sha blob-sha :message msg
                               :content (.toString (js/Buffer.from next-text "utf8") "base64")}))
            tmp (str "/tmp/deps-put-" repo ".json")]
        (fs/writeFileSync tmp payload)
        (let [{:keys [ok out]} (sh (str "gh api -X PUT repos/kotoba-lang/" repo
                                        "/contents/deps.edn --input " tmp " --jq .commit.sha"))]
          (if ok
            (println "  committed" (str/trim out))
            (do (println "  PUT FAILED:" out) (js/process.exit 6))))))))
