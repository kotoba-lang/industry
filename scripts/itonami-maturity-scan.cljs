#!/usr/bin/env nbb
;; scripts/itonami-maturity-scan.cljs — cloud-itonami 成熟度・依存関係の一次証拠スキャナ。
;;
;; ADR-2608052000。出力は manifest/itonami-maturity-evidence.edn（生成物・手編集禁止）。
;;
;;   nbb scripts/itonami-maturity-scan.cljs [--data-root <path>] [--out <path>] [--limit N]
;;
;; --data-root は orgs/ が実際に populate されている superproject 本体 checkout。
;; west 管理の orgs/ は gitignore されており fresh worktree には存在しないので、
;; worktree からスクリプトを走らせるときは必ず本体を指す
;; （90-docs/business/scripts/flagship-checklist-scan.cljs と同じ約束）。
;;
;; このスクリプトは「観測」だけを行い、スコアを一切計算しない。スコア化と
;; system dynamics は scripts/itonami-maturity-dynamics.cljs が担う。観測と評価を
;; 同じファイルに混ぜると、後から「この数字は測ったのか決めたのか」が読めなくなる。
;;
;; 測っていない項目は false/0 でなく nil を書く。nil は「未測定」であって「無い」ではない
;; （ADR-2607203000 の原則: 測っていないものを 0 と書かない）。

(ns itonami-maturity-scan
  (:require [clojure.string :as str]
            [clojure.edn :as edn]
            ["fs" :as fs]
            ["path" :as npath]
            ["child_process" :as cp]))

;; ---------------------------------------------------------------- args

(defn- parse-args [args]
  (loop [a args m {:data-root (.cwd js/process)
                   :out "manifest/itonami-maturity-evidence.edn"
                   :limit nil
                   :orgs ["cloud-itonami"]}]
    (if (empty? a)
      m
      (let [[k v & more] a]
        (case k
          "--data-root" (recur more (assoc m :data-root v))
          "--out"       (recur more (assoc m :out v))
          "--limit"     (recur more (assoc m :limit (js/parseInt v 10)))
          (recur (rest a) m))))))

(def opts (parse-args (vec (.slice (.-argv js/process) 2))))
(def data-root (:data-root opts))

(defn- exists? [p] (.existsSync fs p))
(defn- dr [& parts] (apply (.-join npath) data-root parts))

(defn- slurp* [p]
  (try (.toString (.readFileSync fs p) "utf8") (catch :default _ nil)))

;; ---------------------------------------------------------------- west.yml

(defn- west-projects
  "manifest/west.yml から {name path} を拾う。west.yml は生成物なので
   YAML パーサを積まず、`- name:` / `path:` の行対で読む（gen-west-manifest.cljs が
   出す canonical 形に依存する）。"
  [text]
  (let [lines (str/split-lines text)]
    (loop [ls lines cur nil acc []]
      (if (empty? ls)
        (cond-> acc cur (conj cur))
        (let [l (first ls)]
          (cond
            (re-find #"^\s+- name:\s*(\S+)" l)
            (recur (rest ls) {:name (second (re-find #"^\s+- name:\s*(\S+)" l))}
                   (cond-> acc cur (conj cur)))

            (and cur (re-find #"^\s+path:\s*(\S+)" l))
            (recur (rest ls) (assoc cur :path (second (re-find #"^\s+path:\s*(\S+)" l))) acc)

            (and cur (re-find #"^\s+revision:\s*(\S+)" l))
            (recur (rest ls) (assoc cur :revision (second (re-find #"^\s+revision:\s*(\S+)" l))) acc)

            :else (recur (rest ls) cur acc)))))))

;; ---------------------------------------------------------------- fs walk

(def skip-dirs #{".git" "node_modules" "target" ".shadow-cljs" ".cpcache"
                 "dist" "build" ".datalad" ".next" "vendor" ".venv" ".clj-kondo"})

(defn- virtualenv?
  "Python の仮想環境か。名前ではなく `pyvenv.cfg` の実在で判定する。

   名前で弾くと取りこぼす: skip-dirs は完全一致なので `.venv` は消えるが
   `.venv-tts` は残る。実測 2026-08-11、newscaster の `.venv-tts` は 27,560 files
   あり、walk が src/ に着く前に 6000 の予算を使い切って **src=0 / test=0** と
   測った（実体は src 109KB / test 33KB）。名前の変種を足し続けるより、
   virtualenv が必ず持つ標準ファイルを見る方が漏れない。"
  [dir]
  (try (.existsSync fs (str dir "/pyvenv.cfg")) (catch :default _ false)))

(defn- walk-files
  "repo 相対 path の列。max-depth / max-entries で必ず打ち切る（暴走防止）。

   打ち切りは黙って起きてはならない —— truncated? な repo は src/test が 0 に
   潰れ、『実装が無い repo』と見分けが付かなくなる。呼び出し側は必ず
   :truncated? を報告すること。"
  [root max-depth max-entries]
  (let [out (atom [])
        truncated? (atom false)]
    (letfn [(go [dir depth rel]
              (when (and (<= depth max-depth) (not @truncated?))
                (doseq [e (try (.readdirSync fs dir #js {:withFileTypes true})
                               (catch :default _ #js []))]
                  (when-not @truncated?
                    (let [nm (.-name e)
                          child (str dir "/" nm)
                          crel (if (= rel "") nm (str rel "/" nm))]
                      (if (.isDirectory e)
                        (when-not (or (contains? skip-dirs nm) (virtualenv? child))
                          (go child (inc depth) crel))
                        (do (swap! out conj crel)
                            (when (>= (count @out) max-entries) (reset! truncated? true)))))))))]
      (go root 0 ""))
    {:files @out :truncated? @truncated?}))

(defn- file-size [p] (try (.-size (.statSync fs p)) (catch :default _ 0)))

;; ---------------------------------------------------------------- deps.edn

(def local-root-re #":local/root\s+\"([^\"]+)\"")

(defn- normalize-dep
  "deps.edn の :local/root は repo からの相対 path（\"../../kotoba-lang/langchain\"）。
   orgs/<org>/<repo> 形へ正規化する。解決できないものは nil を返す（捏造しない）。"
  [repo-path rel]
  (let [abs (.normalize npath (.join npath (dr repo-path) rel))
        prefix (str (dr "orgs") "/")]
    (when (str/starts-with? abs prefix)
      (let [tail (subs abs (count prefix))
            segs (str/split tail #"/")]
        (when (= 2 (count segs)) (str "orgs/" tail))))))

(defn- parse-deps [repo-path deps-text]
  (when deps-text
    (let [locals (->> (re-seq local-root-re deps-text)
                      (map second)
                      distinct
                      (keep #(normalize-dep repo-path %))
                      vec)
          unresolved (->> (re-seq local-root-re deps-text)
                          (map second)
                          distinct
                          (remove #(normalize-dep repo-path %))
                          vec)
          mvn (count (re-seq #":mvn/version" deps-text))
          git (count (re-seq #":git/(?:sha|tag|url)" deps-text))]
      {:local locals :unresolved unresolved :mvn-count mvn :git-count git})))

;; ---------------------------------------------------------------- git

(defn- git-last-commit-iso [repo-abs]
  (try
    (-> (.execSync cp "git log -1 --format=%cI"
                   #js {:cwd repo-abs :encoding "utf8" :stdio #js ["ignore" "pipe" "ignore"]
                        :timeout 15000})
        str/trim
        (#(when-not (str/blank? %) %)))
    (catch :default _ nil)))

(defn- git-commit-count [repo-abs]
  (try
    (-> (.execSync cp "git rev-list --count HEAD"
                   #js {:cwd repo-abs :encoding "utf8" :stdio #js ["ignore" "pipe" "ignore"]
                        :timeout 15000})
        str/trim js/parseInt)
    (catch :default _ nil)))

;; ---------------------------------------------------------------- evidence

(def src-ext #{"cljc" "cljs" "clj" "kotoba"})

(defn- ext-of [f] (let [i (.lastIndexOf f ".")] (when (pos? i) (subs f (inc i)))))
(defn- base-of [f] (last (str/split f #"/")))

;; actor discipline の構成要素。cloud-itonami の actor は langgraph StateGraph +
;; 独立 Governor + append-only ledger のパターン（skill build-actor）で、
;; そのどの部品が実在するかを file 名から測る。名前は itonami-fleet-audit.cljs の
;; :components と同じ語彙に合わせてある。
(def component-patterns
  {:operation  #"(?i)(^|/)operation[s]?\.(cljc|cljs|clj)$"
   :governor   #"(?i)(^|/)(governor|policy)\.(cljc|cljs|clj)$"
   :store      #"(?i)(^|/)store\.(cljc|cljs|clj)$"
   :phase      #"(?i)(^|/)phase\.(cljc|cljs|clj)$"
   :sim        #"(?i)(^|/)sim\.(cljc|cljs|clj)$"
   :facts      #"(?i)(^|/)facts\.(cljc|cljs|clj)$"
   :ledger     #"(?i)(^|/)ledger\.(cljc|cljs|clj)$"})

(defn- collect [org repo]
  (let [rel  (str "orgs/" org "/" repo)
        root (dr rel)]
    (if-not (exists? root)
      {:repo/path rel :repo/org org :repo/name repo :repo/present? false}
      (let [{:keys [files truncated?]} (walk-files root 6 6000)
            src-files   (filterv #(and (str/starts-with? % "src/") (src-ext (ext-of %))) files)
            test-files  (filterv #(and (str/starts-with? % "test/") (src-ext (ext-of %))) files)
            kotoba-files (filterv #(= "kotoba" (ext-of %)) files)
            src-bytes   (reduce + 0 (map #(file-size (str root "/" %)) src-files))
            test-bytes  (reduce + 0 (map #(file-size (str root "/" %)) test-files))
            has-file?   (fn [nm] (boolean (some #(= (base-of %) nm) files)))
            has-path?   (fn [re] (boolean (some #(re-find re %) files)))
            components  (into #{} (keep (fn [[k re]] (when (some #(re-find re %) files) k))
                                        component-patterns))
            readme      (slurp* (str root "/README.md"))
            deps-text   (slurp* (str root "/deps.edn"))
            deps        (parse-deps rel deps-text)
            ;; facts.cljc / data/ 内の実 URL 引用数。「実データを引いているか」の直接証拠。
            fact-files  (filterv #(re-find #"(?i)(^|/)(facts|catalog|jurisdictions?)\.(cljc|cljs|clj|edn)$" %) files)
            data-files  (filterv #(str/starts-with? % "data/") files)
            citation-n  (reduce + 0
                                (map (fn [f]
                                       (let [t (slurp* (str root "/" f))]
                                         (if t (count (re-seq #"https?://" t)) 0)))
                                     (take 40 (concat fact-files (take 20 data-files)))))
            wf-files    (filterv #(re-find #"^\.github/workflows/" %) files)
            demo-files  (filterv #(re-find #"(?i)^(docs|samples|public|demo)/.*\.html$" %) files)
            adr-files   (filterv #(re-find #"(?i)^(docs/adr|90-docs/adr)/" %) files)]
        {:repo/path rel
         :repo/org org
         :repo/name repo
         :repo/present? true
         :repo/files-truncated? truncated?
         :repo/file-count (count files)
         ;; --- substrate
         :src/file-count (count src-files)
         :src/bytes src-bytes
         :test/file-count (count test-files)
         :test/bytes test-bytes
         :kotoba/file-count (count kotoba-files)
         ;; --- actor discipline
         :component/present (vec (sort components))
         :component/count (count components)
         ;; --- real-world ingest
         :ingest/citation-count citation-n
         :ingest/fact-file-count (count fact-files)
         :ingest/data-file-count (count data-files)
         ;; --- docs
         :doc/readme-bytes (if readme (count readme) 0)
         :doc/adr-count (count adr-files)
         :doc/has-operator-quickstart? (has-path? #"(?i)operator-quickstart")
         :doc/has-business-model? (has-path? #"(?i)business-model")
         :doc/has-pricing? (has-path? #"(?i)pricing")
         ;; --- product surface
         :surface/demo-file-count (count demo-files)
         :surface/workflow-count (count wf-files)
         :surface/has-cron-workflow?
         (boolean (some (fn [f] (when-let [t (slurp* (str root "/" f))]
                                  (re-find #"(?m)^\s*schedule:" t)))
                        wf-files))
         ;; --- deps
         :deps/has-deps-edn? (some? deps-text)
         :deps/local (vec (:local deps))
         :deps/local-count (count (:local deps))
         :deps/unresolved (vec (:unresolved deps))
         :deps/mvn-count (or (:mvn-count deps) 0)
         :deps/git-count (or (:git-count deps) 0)
         ;; --- markers
         :repo/tombstone? (or (has-file? "NOT-MIGRATED") (has-file? "MOVED"))
         :repo/has-actor-edn? (has-file? "actor.edn")
         :repo/has-blueprint? (has-file? "blueprint.edn")
         ;; --- vcs
         :git/last-commit (git-last-commit-iso root)
         :git/commit-count (git-commit-count root)}))))

;; ---------------------------------------------------------------- render

(defn- render-val [v]
  (cond
    (nil? v) "nil"
    (string? v) (pr-str v)
    (boolean? v) (str v)
    (number? v) (str v)
    (keyword? v) (str v)
    (vector? v) (str "[" (str/join " " (map render-val v)) "]")
    :else (pr-str v)))

(defn- render-entity [m]
  (str "{" (str/join ", " (map (fn [[k v]] (str k " " (render-val v))) m)) "}"))

;; ---------------------------------------------------------------- main

(defn -main []
  (let [west-text (or (slurp* (dr "manifest" "west.yml"))
                      (throw (ex-info "west.yml not found" {:at (dr "manifest" "west.yml")})))
        projects (west-projects west-text)
        target-orgs (set (:orgs opts))
        primary (->> projects
                     (keep :path)
                     (filter #(some (fn [o] (str/starts-with? % (str "orgs/" o "/"))) target-orgs))
                     sort vec)
        primary (if (:limit opts) (vec (take (:limit opts) primary)) primary)
        _ (println (str "scan: " (count primary) " primary repos under " (pr-str (vec target-orgs))
                        " (west.yml has " (count projects) " projects)"))
        t0 (.now js/Date)
        prim-ev (vec (map-indexed
                      (fn [i p]
                        (when (zero? (mod i 200))
                          (println (str "  .. " i "/" (count primary) " " p)))
                        (let [[_ org repo] (str/split p #"/")]
                          (collect org repo)))
                      primary))
        ;; 依存先（多くは kotoba-lang の substrate library）も node として測る。
        ;; leverage の計算は substrate の成熟度が要るので、ここを落とすと
        ;; 「hub が何であるか」を測らずに hub を語ることになる。
        dep-targets (->> prim-ev
                         (mapcat :deps/local)
                         distinct
                         (remove (fn [p] (some (fn [o] (str/starts-with? p (str "orgs/" o "/"))) target-orgs)))
                         sort vec)
        _ (println (str "scan: " (count dep-targets) " cross-org dependency targets"))
        dep-ev (vec (map-indexed
                     (fn [i p]
                       (when (zero? (mod i 100)) (println (str "  dep .. " i "/" (count dep-targets))))
                       (let [[_ org repo] (str/split p #"/")]
                         (assoc (collect org repo) :repo/substrate-only? true)))
                     dep-targets))
        all (into prim-ev dep-ev)
        elapsed (- (.now js/Date) t0)
        out-path ((.-resolve npath) (.cwd js/process) (:out opts))
        header (str ";; manifest/itonami-maturity-evidence.edn — GENERATED, DO NOT HAND-EDIT.\n"
                    ";; Regenerate:\n"
                    ";;   nbb scripts/itonami-maturity-scan.cljs --data-root <superproject main checkout>\n"
                    ";;\n"
                    ";; ADR-2608052000。cloud-itonami 全 repo（+ その依存先 substrate repo）の\n"
                    ";; 成熟度・依存関係の一次証拠。**観測のみ**でスコアを含まない — スコア化と\n"
                    ";; system dynamics は scripts/itonami-maturity-dynamics.cljs が別途行う。\n"
                    ";;\n"
                    ";; 各 map は DataScript/Datomic に直接 transact できる（:db/id 無し = tempid）。\n"
                    ";; :repo/path で manifest/repo-taxonomy.edn（:source/dataset \"repo-taxonomy\"）と\n"
                    ";; join できる。\n"
                    ";;\n"
                    ";; nil は「未測定」であって「値が無い/0」ではない。\n"
                    ";;\n"
                    ";; Scanned: " (.toISOString (js/Date.)) "\n"
                    ";; data-root: " data-root "\n"
                    ";; primary=" (count prim-ev) " substrate=" (count dep-ev)
                    " elapsed-ms=" elapsed "\n\n")]
    (.writeFileSync fs out-path
                    (str header "[\n " (str/join "\n " (map render-entity all)) "\n]\n"))
    (println (str "wrote " out-path " (" (count all) " entities, " elapsed "ms)"))
    (println (str "  present=" (count (filter :repo/present? all))
                  " absent=" (count (remove :repo/present? all))
                  " with-src=" (count (filter #(pos? (:src/file-count % 0)) all))
                  " with-tests=" (count (filter #(pos? (:test/file-count % 0)) all))
                  " with-kotoba=" (count (filter #(pos? (:kotoba/file-count % 0)) all))))
    ;; 打ち切った repo は src/test が 0 に潰れており、『実装が無い repo』と
    ;; 区別が付かない。黙って通すと、その 0 がそのまま成熟度スコアになる。
    (let [tr (filter :repo/files-truncated? all)]
      (when (seq tr)
        (binding [*print-fn* *print-err-fn*]
          (println (str "WARNING: " (count tr)
                        " repo で file walk を打ち切った —— これらの src/test は"
                        " 0 に潰れており、実測値ではない:"))
          (doseq [e tr]
            (println (str "  " (:repo/path e) " (files>=" (:repo/file-count e) ")"))))))))

(-main)
