#!/usr/bin/env nbb
;; Which repositories carry a `.itonami/profile.edn`, and how many could not be
;; looked at. ADR-2609061200 D6.
;;
;;   nbb scripts/repo-profiles/gen-roster.cljs           write manifest/repo-profiles.edn
;;   nbb scripts/repo-profiles/gen-roster.cljs --check   fail if the file is stale
;;   nbb scripts/repo-profiles/gen-roster.cljs --report   print, write nothing
;;
;; exit 0  answered, and every listed repository was looked at
;; exit 1  --check and the committed file is stale
;; exit 2  COULD NOT ANSWER — the pin source was unreadable, or some listed
;;         repositories have no checkout here and this roster is therefore
;;         incomplete
;;
;; ## Why the third exit code exists
;;
;; A repository's profile lives in its TREE. west lists 4,000+ projects and this
;; machine does not hold every one of them, so a local scan is structurally
;; partial: a repository can carry a profile on `main` and be invisible here.
;;
;; Printing a roster of "the ones I found" with no denominator is the shape this
;; workspace keeps finding — `manifest/docs-edn-only.cljs` printed
;; `parse-errors=0` over 2,340 of 2,505 files because the other 165 were outside
;; the sparse cone, and read-nothing counted as found-nothing. So this prints
;; `profiles=<scanned>/<listed>` always, and refuses when they differ.
;;
;; ## What being in this file does NOT mean
;;
;; That a bot exists, or that anything is running. It means one file was on disk
;; and parsed. `manifest/repo-bots.edn` opens with the same warning for the same
;; reason: `manifest/observatories.edn` recorded 22 actors of which 8 had never
;; been run, while three documents said they worked.

(ns gen-roster
  (:require [clojure.edn :as edn]
            [clojure.string :as str]))

(def fs (js/require "node:fs"))
;; `node-path`, not `path`: `look` destructures a project's `:path`, and a
;; module bound to the same name is shadowed exactly there — where it is used.
;; The first version of this file did that and died with
;; "Cannot read properties of null (reading \'join\')".
(def node-path (js/require "node:path"))
(def cp (js/require "node:child_process"))

(def argv (vec (drop 2 js/process.argv)))
(defn- flag [n] (some #{n} argv))

(def top (or (.-CLAUDE_PROJECT_DIR (.-env js/process)) (.cwd js/process)))
(def out-file (.join node-path top "manifest" "repo-profiles.edn"))

(def profile-relative ".itonami/profile.edn")
(def profiles-relative
  "The directory form, for a repository where several people keep their own.

  Both shapes are read, because the CLI reads both. A roster that looked at
  only one of them would report `carried 0` for a repository that carries two
  — measured 2026-09-06, when this file was written before the directory form
  existed and kept saying zero after three repositories had profiles on main."
  ".itonami/profiles")
(def schema "cloud.itonami.app.repo-profile.v1")

(defn- refuse! [& lines]
  (doseq [l lines] (println l))
  (.exit js/process 2))

(defn- west-yaml
  "west.yml from **origin/main**, never the working copy.

  The same refusal `scripts/repo-bots/gen-registry.cljs` makes, for the reason
  it records: on 2026-09-01 a superproject 401 commits behind produced a roster
  from a stale copy in which 55 pins disagreed, and the report that followed
  instructed a checkout to move BACKWARDS."
  []
  (let [r (.spawnSync cp "git" #js ["-C" top "show" "origin/main:manifest/west.yml"]
                      #js {:encoding "utf8" :maxBuffer (* 64 1024 1024)})]
    (if (and (zero? (.-status r)) (not (str/blank? (.-stdout r))))
      (.-stdout r)
      (refuse! (str "REFUSED\torigin/main:manifest/west.yml を読めなかった — "
                    (str/trim (str (.-stderr r))))
               "  roster は pin の正本から生成する。手元の west.yml へは落とさない"
               "  直す: git -C <superproject> fetch origin"))))

(defn- projects
  "`{:name :path :groups}` for every west project. No YAML parser, for the
  reason `repo-search.cljs` gives: the shape is entirely regular and being
  toolchain-free is worth more here than generality."
  []
  (loop [ls (str/split-lines (west-yaml)) cur nil out []]
    (if-let [line (first ls)]
      (let [push (fn [o] (cond-> o cur (conj cur)))]
        (cond
          (re-find #"^    - name: (\S+)$" line)
          (recur (rest ls) {:name (second (re-find #"^    - name: (\S+)$" line))} (push out))

          (and cur (re-find #"^      path: (\S+)$" line))
          (recur (rest ls) (assoc cur :path (second (re-find #"^      path: (\S+)$" line))) out)

          (and cur (re-find #"^      groups: \[(.*)\]$" line))
          (recur (rest ls)
                 (assoc cur :groups (->> (str/split (second (re-find #"^      groups: \[(.*)\]$" line)) #",")
                                         (map str/trim) (remove str/blank?) vec))
                 out)

          :else (recur (rest ls) cur out)))
      (cond-> out cur (conj cur)))))

(defn- look
  "What this machine can say about one project.

  `:unscanned` is NOT `:none`. No checkout means the question was not asked;
  an empty `.itonami/` means it was asked and answered."
  [{:keys [name path]}]
  (let [dir (.join node-path top (str path))]
    (cond
      (not (.existsSync fs dir)) {:name name :path path :state :unscanned :why :no-checkout}

      :else
      (let [single (.join node-path dir profile-relative)
            pdir (.join node-path dir profiles-relative)
            many (when (.existsSync fs pdir)
                   (->> (js->clj (.readdirSync fs pdir))
                        (filter #(str/ends-with? % ".edn"))
                        sort
                        (map #(.join node-path pdir %))))
            files (cond-> (vec many) (.existsSync fs single) (conj single))]
        (if (empty? files)
          {:name name :path path :state :none}
          ;; One entry per project, summarising its files: a repository is
          ;; `:carried` when at least one profile parses, and `:malformed` is
          ;; reported alongside rather than instead — a repository with one
          ;; good profile and one typo has both facts, and collapsing them
          ;; would lose whichever the reader needed.
          (let [seen (mapv (fn [f]
                             (let [text (try (.readFileSync fs f "utf8") (catch :default _ nil))
                                   v (try (edn/read-string {:readers {} :default (fn [_ _] ::tagged)} (str text))
                                          (catch :default _ ::unreadable))
                                   rel (.relative node-path dir f)]
                               (cond
                                 (nil? text) {:file rel :state :malformed}
                                 (= ::unreadable v) {:file rel :state :malformed}
                                 (not (map? v)) {:file rel :state :malformed}
                                 (not= schema (:profile/schema v)) {:file rel :state :malformed}
                                 :else {:file rel :state :carried
                                        :profile/id (str (:profile/id v))
                                        :profile/for (some-> (:profile/for v) str)})))
                           files)
                good (filterv #(= :carried (:state %)) seen)]
            {:name name :path path
             :state (if (seq good) :carried :malformed)
             :profiles (mapv #(select-keys % [:file :profile/id :profile/for]) good)
             :malformed (mapv :file (remove #(= :carried (:state %)) seen))}))))))

(defn- roster []
  (->> (projects)
       (remove #(some #{"archived"} (:groups %)))
       (remove #(some #{"datalad"} (:groups %)))
       (map look)
       (sort-by :name)
       vec))

(defn- coverage [rs]
  {:listed (count rs)
   :scanned (count (remove #(= :unscanned (:state %)) rs))
   :carried (count (filter #(= :carried (:state %)) rs))
   ;; profiles, not projects: a repository with four is four here, and one
   ;; with one good file and one typo contributes to both counts.
   :profiles (reduce + 0 (map #(count (:profiles %)) rs))
   :malformed (reduce + 0 (map #(count (:malformed %)) rs))})

(defn- render [rs cov]
  (str ";; manifest/repo-profiles.edn — generated by scripts/repo-profiles/gen-roster.cljs.\n"
       ";; DO NOT EDIT BY HAND.\n"
       ";;\n"
       ";; Which west projects carry `.itonami/profile.edn` (ADR-2609061200).\n"
       ";;\n"
       ";; **Being listed here does not mean a bot exists, and does not mean\n"
       ";; anything is running.** It means one file was on disk and parsed.\n"
       ";; manifest/repo-bots.edn opens the same way, because\n"
       ";; manifest/observatories.edn recorded 22 actors of which 8 had never\n"
       ";; been run while three documents said they worked.\n"
       ";;\n"
       ";; `:coverage` is not decoration. A profile lives in a repository's tree\n"
       ";; and this machine does not hold every west project, so a roster with no\n"
       ";; denominator would report `carried 3` and mean nothing by it.\n"
       ";;\n"
       ";; scanned=" (:scanned cov) "/" (:listed cov) "\n"
       "\n"
       (pr-str {:schema "manifest.repo-profiles.v1"
                :coverage cov
                :repos (filterv #(not= :none (:state %)) rs)})
       "\n"))

(defn -main []
  (let [rs (roster)
        cov (coverage rs)
        text (render rs cov)
        complete? (= (:scanned cov) (:listed cov))]
    (println (str "scanned=" (:scanned cov) "/" (:listed cov)
                  "  repos-carrying=" (:carried cov)
                  "  profiles=" (:profiles cov)
                  "  malformed=" (:malformed cov)))
    (when-not complete?
      (let [un (filterv #(= :unscanned (:state %)) rs)]
        (println (str "  " (count un) " 件は checkout が無く、見ていない。"
                      "見ていないものを「profile 無し」として数えない"))
        ;; NAMED, and bounded. "some were not looked at" is not actionable;
        ;; a list of 4,000 is not readable. The first few plus a count is both.
        (doseq [{:keys [name path why]} (take 5 un)]
          (println (str "    " name "\t" path "\t" (clojure.core/name (or why :unknown)))))
        (when (> (count un) 5)
          (println (str "    … 他 " (- (count un) 5) " 件")))))
    (cond
      (flag "--report") nil

      (flag "--check")
      (let [current (try (.readFileSync fs out-file "utf8") (catch :default _ nil))]
        (if (= current text)
          (println "OK\tmanifest/repo-profiles.edn は生成器と一致")
          (do (println "STALE\tmanifest/repo-profiles.edn を再生成してください")
              (.exit js/process 1))))

      :else
      (do (.mkdirSync fs (.dirname node-path out-file) #js {:recursive true})
          (.writeFileSync fs out-file text)
          (println (str "WROTE\t" out-file))))
    ;; The coverage exit comes LAST so the roster is still written and the
    ;; numbers still printed. Refusing before writing would leave an operator
    ;; with an exit code and no way to see what it was about.
    (when-not complete? (.exit js/process 2))))

(-main)
