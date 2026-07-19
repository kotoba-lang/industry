#!/usr/bin/env nbb
;; west-orphan-audit.cljs — local orgs/*/* vs west.yml / :local/root 依存の orphan 検出。
;;
;; 背景 (2026-07-17 実測):
;;   - kotoba-lang/crm は GitHub に push 済みなのに west 未登録・local 未 checkout。
;;     cloud-itonami-isic-5820/6201/6202 の deps.edn が
;;     {:local/root "../../kotoba-lang/crm"} を指すため fresh checkout で壊れる。
;;   - orgs/ 直下には path-override 残骸・worktree・personal データ・真の未登録 git
;;     が混在する。「local にある = west に無い」をそのまま登録対象にすると誤爆する。
;;
;; 分類:
;;   :path-override-leftover  — repos.edn :path-overrides の旧 path（新 path が west にある）
;;   :personal                — orgs/personal/*（west 対象外）
;;   :worktree-scratch        — _* / *-current / *-boundary / _wt-* 等
;;   :true-orphan-git         — local git があり west path に無い（登録 or 退役候補）
;;   :true-orphan-nongit      — local dir のみ（scaffold 残骸等）
;;   :local-root-broken       — deps.edn の :local/root が指す project が
;;                              未存在 or west 未登録（fresh checkout 破壊）
;;
;; 使い方:
;;   nbb scripts/west-orphan-audit.cljs              ; 人間可読サマリ
;;   nbb scripts/west-orphan-audit.cljs --all        ; 全件列挙
;;   nbb scripts/west-orphan-audit.cljs --blocking   ; :local-root-broken のみ
;;   nbb scripts/west-orphan-audit.cljs --edn        ; 機械可読 EDN
;;
;; exit 0: blocking なし
;; exit 1: :local-root-broken が1件以上（登録漏れが consumer を壊している）

(require '[scripts.nbb-compat :refer [slurp sh]]
         '[clojure.string :as str]
         '[clojure.edn :as edn])

(def node-fs (js/require "node:fs"))
(def node-path (js/require "node:path"))

(def root
  (-> (sh "git" "rev-parse" "--show-toplevel") :out str/trim))

(defn exists? [p]
  (try (.existsSync node-fs p) (catch :default _ false)))

(defn is-dir? [p]
  (try (.isDirectory (.statSync node-fs p)) (catch :default _ false)))

(defn is-file? [p]
  (try (.isFile (.statSync node-fs p)) (catch :default _ false)))

(defn list-dirs [p]
  (when (is-dir? p)
    (->> (.readdirSync node-fs p)
         (map str)
         (remove #(str/starts-with? % "."))
         (map #(node-path.join p %))
         (filter is-dir?)
         sort
         vec)))

(defn read-text [p]
  (when (exists? p)
    (try (slurp p) (catch :default _ nil))))

(defn west-paths
  "west.yml の path: 行を set で返す。"
  []
  (let [text (or (read-text (node-path.join root "manifest/west.yml")) "")]
    (->> (re-seq #"(?m)^\s+path:\s+(\S+)" text)
         (map second)
         set)))

(defn parse-string-map-blob
  "repos.edn 内の string 化された map リテラルから \"k\" \"v\" ペアを抜く。"
  [blob]
  (when blob
    (into {}
          (for [[_ k v] (re-seq #"\"([^\"]+)\"\s+\"([^\"]+)\"" blob)]
            [k v]))))

(defn path-overrides
  "repos.edn :path-overrides の old→new map。"
  []
  (let [text (or (read-text (node-path.join root "manifest/repos.edn")) "")
        m (re-find #":manifest\.repos/path-overrides\s+\"(.*?)\",\s*:manifest"
                   text)]
    (if m
      (parse-string-map-blob (str/replace (second m) #"\\\"" "\""))
      {})))

(defn local-org-projects
  "orgs/<org>/<repo> の相対 path 一覧。"
  []
  (let [orgs-root (node-path.join root "orgs")]
    (vec
     (for [org (list-dirs orgs-root)
           repo (list-dirs org)
           :let [rel (str "orgs/" (node-path.basename org) "/" (node-path.basename repo))]]
       rel))))

(defn git? [rel]
  (let [g (node-path.join root rel ".git")]
    (or (exists? g) (is-file? g))))

(defn git-remote [rel]
  (let [{:keys [exit out]} (sh "git" "-C" (node-path.join root rel)
                               "remote" "get-url" "origin")]
    (when (zero? exit) (not-empty (str/trim out)))))

(defn worktree-scratch?
  [rel]
  (let [name (node-path.basename rel)]
    (or (str/starts-with? name "_")
        (str/starts-with? name "wt-")
        (str/includes? name "worktree")
        (str/ends-with? name "-current")
        (str/ends-with? name "-boundary")
        (boolean (re-matches #".*-pr\d+" name))
        (= name "__pycache__"))))

(defn classify-unregistered
  "local にあって west path に無いものを分類。"
  [local west overrides]
  (let [unreg (filter #(not (contains? west %)) local)]
    (reduce
     (fn [acc rel]
       (cond
         (contains? overrides rel)
         (update acc :path-override-leftover conj
                 {:path rel :maps-to (get overrides rel)
                  :target-in-west? (contains? west (get overrides rel))})

         (str/starts-with? rel "orgs/personal/")
         (update acc :personal conj {:path rel})

         (worktree-scratch? rel)
         (update acc :worktree-scratch conj
                 {:path rel :git? (git? rel)
                  :origin (when (git? rel) (git-remote rel))})

         (git? rel)
         (update acc :true-orphan-git conj
                 {:path rel :origin (or (git-remote rel) "")})

         :else
         (update acc :true-orphan-nongit conj {:path rel})))
     {:path-override-leftover []
      :personal []
      :worktree-scratch []
      :true-orphan-git []
      :true-orphan-nongit []}
     unreg)))

(defn project-of-abs
  "絶対 path を orgs/org/repo に畳む。失敗時は relative string。
  deps.edn の ../../../ が workspace 外へ抜ける場合は path 末尾の
  <org>/<repo> を orgs/ 付きで推定する。"
  [abs]
  (try
    (let [rel (node-path.relative root abs)
          parts (vec (str/split rel #"[\\/]"))]
      (cond
        (and (>= (count parts) 3) (= (first parts) "orgs"))
        (str/join "/" (take 3 parts))

        ;; abs 自体が .../orgs/org/repo を含む
        :else
        (let [abs-parts (vec (str/split abs #"[\\/]"))
              idx (.indexOf abs-parts "orgs")]
          (if (and (>= idx 0) (>= (count abs-parts) (+ idx 3)))
            (str/join "/" (subvec abs-parts idx (+ idx 3)))
            rel))))
    (catch :default _
      abs)))

(defn scan-local-root-deps
  "orgs/*/*/deps.edn の :local/root を走査し、未存在 or west 未登録を列挙。"
  [west]
  (let [orgs-root (node-path.join root "orgs")
        hits (atom [])]
    (doseq [org (list-dirs orgs-root)
            repo (list-dirs org)
            :let [deps (node-path.join repo "deps.edn")]
            :when (exists? deps)
            :let [text (or (read-text deps) "")]
            :when (str/includes? text ":local/root")]
      (doseq [[_ target-rel] (re-seq #":local/root\s+\"([^\"]+)\"" text)]
        (let [abs (node-path.resolve repo target-rel)
              proj (project-of-abs abs)
              dir-exists? (exists? abs)
              in-west? (contains? west proj)]
          (when (or (not dir-exists?) (not in-west?))
            (swap! hits conj
                   {:consumer (str "orgs/" (node-path.basename org) "/"
                                   (node-path.basename repo) "/deps.edn")
                    :local-root target-rel
                    :project proj
                    :dir-exists? dir-exists?
                    :in-west? in-west?})))))
    ;; unique by project, keep first consumer as example
    (->> @hits
         (group-by :project)
         (map (fn [[proj rows]]
                (assoc (first rows)
                       :consumers (vec (distinct (map :consumer rows)))
                       :consumer-count (count (distinct (map :consumer rows))))))
         (sort-by :project)
         vec)))

(defn parse-args [argv]
  (let [s (set argv)]
    {:all? (contains? s "--all")
     :blocking? (contains? s "--blocking")
     :edn? (contains? s "--edn")}))

(defn print-human [report opts]
  (let [{:keys [unregistered local-root-broken counts]} report]
    (println "=== west-orphan-audit ===")
    (println (str "local orgs projects: " (:local counts)
                  "  west paths: " (:west counts)
                  "  unregistered total: " (:unregistered counts)))
    (println (str "  path-override leftovers: " (count (:path-override-leftover unregistered))))
    (println (str "  personal/*: " (count (:personal unregistered))))
    (println (str "  worktree/scratch: " (count (:worktree-scratch unregistered))))
    (println (str "  true-orphan-git: " (count (:true-orphan-git unregistered))))
    (println (str "  true-orphan-nongit: " (count (:true-orphan-nongit unregistered))))
    (println (str "  local-root-broken (blocking): " (count local-root-broken)))
    (when (seq local-root-broken)
      (println)
      (println "## BLOCKING: :local/root → missing or not-in-west")
      (doseq [row local-root-broken]
        (println (str "  " (:project row)
                      "  dir=" (:dir-exists? row)
                      " west=" (:in-west? row)
                      " consumers=" (:consumer-count row)))
        (when (:all? opts)
          (doseq [c (:consumers row)]
            (println (str "    - " c))))))
    (when (and (not (:blocking? opts)) (or (:all? opts) true))
      (println)
      (println "## true-orphan-git (register or retire)")
      (let [rows (:true-orphan-git unregistered)
            show (if (:all? opts) rows (take 25 rows))]
        (doseq [row show]
          (println (str "  " (:path row)
                        (if (str/blank? (:origin row))
                          "  origin=(none)"
                          (str "  origin=" (:origin row))))))
        (when (and (not (:all? opts)) (> (count rows) 25))
          (println (str "  ... +" (- (count rows) 25)
                        " more (pass --all)")))))
    (when (:all? opts)
      (println)
      (println "## path-override leftovers (do NOT re-register old path)")
      (doseq [row (:path-override-leftover unregistered)]
        (println (str "  " (:path row) " → " (:maps-to row)
                      " (new-in-west=" (:target-in-west? row) ")")))
      (println)
      (println "## worktree/scratch")
      (doseq [row (:worktree-scratch unregistered)]
        (println (str "  " (:path row) " git=" (:git? row))))
      (println)
      (println "## true-orphan-nongit")
      (doseq [row (:true-orphan-nongit unregistered)]
        (println (str "  " (:path row)))))))

(defn -main [& args]
  (let [opts (parse-args (or args #js []))
        west (west-paths)
        overrides (path-overrides)
        local (local-org-projects)
        unreg (classify-unregistered local west overrides)
        broken (scan-local-root-deps west)
        report {:counts {:local (count local)
                         :west (count west)
                         :unregistered (+ (count (:path-override-leftover unreg))
                                          (count (:personal unreg))
                                          (count (:worktree-scratch unreg))
                                          (count (:true-orphan-git unreg))
                                          (count (:true-orphan-nongit unreg)))}
                :unregistered unreg
                :local-root-broken broken
                :blocking-count (count broken)}]
    (if (:edn? opts)
      (println (pr-str report))
      (print-human report opts))
    ;; process.exit を先に呼ばないと nbb が常に 0 で落ちることがある
    (.exit js/process (if (pos? (count broken)) 1 0))))

(apply -main *command-line-args*)
