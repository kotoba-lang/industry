#!/usr/bin/env nbb
;; 認証スコープ内の repo の **実依存** を purl として棚卸しする。
;;
;; ## なぜ要るのか
;;
;; SOC 2 CC7.1（新たな脆弱性の検知）と ISO/IEC 27001:2022 A.8.8（技術的脆弱性の
;; 管理）で最初に問われるのは「何を使っているか」である。
;;
;; ⚠ **この workspace には既に SBOM / 脆弱性照合のモデルが在る** ——
;; `kotoba-lang/app-sbom`（component/purl・OSV からの CVE 取り込み・VulnMatch・
;; severity 別 SLA・blast radius、1,823 行 + テスト）。**新しく作らない。**
;; 欠けているのは (1) `sbom.etzhayyim.com` が未デプロイ、(2) 自分たちの依存を
;; component として投入するものが無い、の 2 つで、この script は (2) を埋める。
;;
;; ## 正確でない version を正確なふりで出さない
;;
;; `package.json` の `^1.2.3` は範囲であって version ではない。OSV に範囲を投げると
;; 「該当なし」が返り、それは「脆弱性が無い」と同じ顔をする。したがって:
;;
;;   - `package-lock.json` / `pnpm-lock.yaml` が在ればそちらを正とする（exact）
;;   - 無ければ範囲を記録し `:dependency/exact? false` を立てる
;;   - `--osv` は **exact なものだけ**を照会し、除外した数を必ず印字する
;;
;;   nbb scripts/gen-dependency-inventory.cljs           # 棚卸し（scope 内のみ）
;;   nbb scripts/gen-dependency-inventory.cljs --all     # 全 repo
;;   nbb scripts/gen-dependency-inventory.cljs --osv     # OSV.dev に照会（外部送信）

(ns gen-dependency-inventory
  (:require ["fs" :as fs]
            ["path" :as path]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(def root (.cwd js/process))
(def scope-file (path/join root "90-docs" "compliance" "scope.datoms.edn"))
(def out-file (path/join root "90-docs" "compliance" "dependencies.datoms.edn"))

(def named-properties
  #{"kotobalabs.com" "kotoba-lang.org" "kotobase.net" "murakumo.cloud" "itonami.cloud"})

(defn- read-safe [f]
  (try (fs/readFileSync f "utf8") (catch :default _ nil)))

(defn- read-json [f]
  (when-let [raw (read-safe f)]
    (try (js->clj (js/JSON.parse raw)) (catch :default _ nil))))

(defn in-scope-repos
  "compliance scope 索引から、名指しされた 5 面に資産を持つ repo を取る。
   索引が無ければ **推測せずに nil** を返す（呼び出し側が拒否する）。"
  []
  (when-let [raw (read-safe scope-file)]
    (let [ds (try (edn/read-string raw) (catch :default _ nil))]
      (when ds
        (->> ds
             (remove :scope/coverage)
             (filter #(named-properties (:scope/property %)))
             (keep :scope/repo)
             distinct sort vec)))))

;; ── purl ───────────────────────────────────────────────────────────────────

(defn- maven-purl [coord version]
  (let [c (str coord)
        [g a] (if (str/includes? c "/") (str/split c #"/" 2) [c c])]
    (str "pkg:maven/" g "/" a "@" version)))

(defn- github-purl [url sha]
  (let [m (re-find #"github\.com[:/]+([^/]+)/([^/.]+)" (or url ""))]
    (when m (str "pkg:github/" (nth m 1) "/" (nth m 2) "@" sha))))

(defn- npm-purl [nm version] (str "pkg:npm/" nm "@" version))

;; ── deps.edn ───────────────────────────────────────────────────────────────

(defn- deps-entries
  "deps.edn の :deps と各 alias の :extra-deps / :replace-deps を 1 本に。"
  [m]
  (concat (:deps m)
          (mapcat (fn [[_ a]] (concat (:extra-deps a) (:replace-deps a)))
                  (:aliases m))))

(defn- from-deps-edn [file rel]
  (when-let [m (try (edn/read-string (read-safe file)) (catch :default _ nil))]
    (keep (fn [[coord spec]]
            (cond
              (:mvn/version spec)
              {:purl (maven-purl coord (:mvn/version spec))
               :ecosystem :maven :name (str coord) :version (:mvn/version spec)
               :exact? true :file rel}

              (:git/sha spec)
              (when-let [p (github-purl (:git/url spec) (:git/sha spec))]
                {:purl p :ecosystem :github :name (str coord) :version (:git/sha spec)
                 :exact? true :file rel})

              ;; :local/root は同じ tree の中。第三者依存ではないので
              ;; 脆弱性照会の対象にしないが、**数からは落とさない**。
              (:local/root spec)
              {:purl nil :ecosystem :internal :name (str coord) :version nil
               :exact? true :file rel}

              :else nil))
          (deps-entries m))))

;; ── npm ────────────────────────────────────────────────────────────────────

(defn- from-package-lock
  "package-lock.json v2/v3 の `packages` は **確定版** を持つ。ここが最優先。"
  [file rel]
  (when-let [m (read-json file)]
    (keep (fn [[k v]]
            (let [nm (or (get v "name")
                         (when (str/includes? k "node_modules/")
                           (last (str/split k #"node_modules/"))))
                  ver (get v "version")]
              (when (and nm ver (seq nm) (not (get v "link")))
                {:purl (npm-purl nm ver) :ecosystem :npm :name nm :version ver
                 :exact? true :file rel})))
          (get m "packages"))))

(defn- from-package-json [file rel]
  (when-let [m (read-json file)]
    (mapcat (fn [k]
              (keep (fn [[nm spec]]
                      (when (string? spec)
                        (let [exact? (boolean (re-matches #"\d+\.\d+\.\d+.*" spec))]
                          {:purl (when exact? (npm-purl nm spec))
                           :ecosystem :npm :name nm :version spec
                           :exact? exact? :file rel})))
                    (get m k)))
            ["dependencies" "devDependencies" "optionalDependencies"])))

;; ── 走査 ───────────────────────────────────────────────────────────────────

(def ^:private manifest-names
  #{"deps.edn" "package.json" "package-lock.json"})

(defn- manifests [repo-root]
  (letfn [(walk [d depth]
            (when (<= depth 3)
              (let [ents (try (fs/readdirSync d #js {:withFileTypes true}) (catch :default _ []))]
                (mapcat (fn [e]
                          (let [n (.-name e) p (path/join d n)]
                            (cond
                              (and (.isFile e) (manifest-names n)) [p]
                              (and (.isDirectory e)
                                   (not (str/starts-with? n "."))
                                   (not (#{"node_modules" "dist" "target" "build" "out"} n)))
                              (walk p (inc depth))
                              :else nil)))
                        ents))))]
    (vec (walk repo-root 0))))

(defn scan [repos]
  (let [stats (atom {:repos 0 :manifests 0 :unreadable 0 :lock-preferred 0})
        rows (atom [])]
    (doseq [repo repos]
      (swap! stats update :repos inc)
      (let [ms (manifests (path/join root repo))
            ;; 同じディレクトリに lock が在れば package.json は読まない。
            ;; 範囲と確定版が二重に入ると、同じ依存が 2 件に見える。
            lock-dirs (set (map path/dirname (filter #(str/ends-with? % "package-lock.json") ms)))]
        (doseq [f ms
                :let [rel (str/replace f (str root "/") "")
                      base (path/basename f)
                      dir (path/dirname f)]]
          (swap! stats update :manifests inc)
          (let [es (cond
                     (= base "deps.edn") (from-deps-edn f rel)
                     (= base "package-lock.json") (do (swap! stats update :lock-preferred inc)
                                                      (from-package-lock f rel))
                     (and (= base "package.json") (not (lock-dirs dir))) (from-package-json f rel)
                     :else [])]
            (when (and (nil? es) (not= base "package.json"))
              (swap! stats update :unreadable inc))
            (doseq [e (or es [])]
              (swap! rows conj (assoc e :repo repo)))))))
    {:rows @rows :stats @stats}))

(defn ->datoms [{:keys [rows stats]} scoped?]
  (let [uniq (->> rows
                  (group-by (juxt :repo :ecosystem :name :version))
                  (map (fn [[_ g]] (first g))))
        ds (map-indexed
            (fn [i r]
              (cond-> {:db/id (- (inc i))
                       :dependency/repo (:repo r)
                       :dependency/ecosystem (:ecosystem r)
                       :dependency/name (:name r)
                       :dependency/exact? (:exact? r)
                       :source/dataset "compliance-dependencies"
                       :source/file (:file r)}
                (:version r) (assoc :dependency/version (:version r))
                (:purl r)    (assoc :dependency/purl (:purl r))))
            uniq)
        exact (count (filter #(and (:exact? %) (:purl %)) uniq))
        cov {:db/id (- (inc (count uniq)))
             :dependency/coverage true
             :source/dataset "compliance-dependencies"
             :dependency/scope (if scoped? :named-properties :all-repos)
             :dependency/repos-scanned (:repos stats)
             :dependency/manifests-read (:manifests stats)
             :dependency/manifests-unreadable (:unreadable stats)
             :dependency/lockfiles-preferred (:lock-preferred stats)
             :dependency/rows (count uniq)
             ;; **OSV に投げられるのは exact なものだけ。** 範囲のまま照会すると
             ;; 「該当なし」が返り、それは「脆弱性が無い」と同じ顔をする。
             :dependency/queryable exact
             :dependency/not-queryable (- (count uniq) exact)}]
    (vec (concat ds [cov]))))

;; ── OSV 照会（外部送信。既定では走らない） ─────────────────────────────────

(defn- osv-skipped
  "OSV の batch に載せられない purl を ecosystem ごとに数える。

   **除外は沈黙してはならない。** `pkg:github/...` は OSV の npm/Maven 面には
   載らないので照会されないが、初版はこれを数に出さず、`除外 55` とだけ
   印字していた —— 実際には 199 件が照会されていなかった。
   『飛ばした』と『合格した』が出力で区別できること（CLAUDE.md の 4 問目）。"
  [purls]
  (->> purls
       (remove #(or (str/starts-with? % "pkg:npm/") (str/starts-with? % "pkg:maven/")))
       (map #(first (str/split % #"/")))
       frequencies))

(defn osv-query! [purls]
  (let [qs (->> purls
                (keep (fn [p]
                        ;; **最後の `@` で切る。** 最初の `@` で切ると、scope 付き npm
                        ;; パッケージ（`pkg:npm/@types/node@20.1.0`）の先頭の `@` に当たり、
                        ;; name が空・version が "types/node@20.1.0" になる。OSV はこれを
                        ;; `invalid query` として **バッチ全体ごと** 400 で返すので、
                        ;; 1 件の綴り違いが 1000 件の照会を黙って落とす。
                        (let [i (.lastIndexOf p "@")
                              base (when (pos? i) (subs p 0 i))
                              ver (when (pos? i) (subs p (inc i)))]
                          (when (and base ver (seq ver))
                            (cond
                              (str/starts-with? base "pkg:npm/")
                              {"package" {"ecosystem" "npm" "name" (subs base 8)} "version" ver}
                              (str/starts-with? base "pkg:maven/")
                              {"package" {"ecosystem" "Maven"
                                          "name" (str/replace (subs base 10) "/" ":")}
                               "version" ver}
                              :else nil)))))
                vec)]
    (if (empty? qs)
      (js/Promise.resolve {:queried 0 :vulns [] :skipped (osv-skipped purls)})
      ;; OSV の batch は 1000 件/回。分割して順に投げる。
      (let [chunks (partition-all 1000 qs)]
        (-> (js/Promise.all
             (clj->js
              (map (fn [c]
                     (-> (js/fetch "https://api.osv.dev/v1/querybatch"
                                   #js {:method "POST"
                                        :headers #js {"content-type" "application/json"}
                                        :body (js/JSON.stringify (clj->js {"queries" (vec c)}))})
                         (.then (fn [r]
                                  ;; **応答本文を捨てない。** status だけ記録する経路は、
                                  ;; 原因が本文に書いてあっても読まない（CLAUDE.md の 3 問目）。
                                  (if (.-ok r)
                                    (.json r)
                                    (-> (.text r)
                                        (.then (fn [t]
                                                 (throw (js/Error.
                                                         (str "OSV HTTP " (.-status r) " — "
                                                              (subs t 0 (min 600 (count t))))))))))))
                         (.then (fn [j]
                                  (map (fn [q res]
                                         {:query q
                                          :ids (map #(get % "id")
                                                    (or (get (js->clj res) "vulns") []))})
                                       c (or (get (js->clj j) "results") []))))))
                   chunks)))
            (.then (fn [rs]
                     {:queried (count qs)
                      :skipped (osv-skipped purls)
                      :vulns (->> (apply concat (js->clj rs :keywordize-keys false))
                                  (filter #(seq (:ids %)))
                                  vec)})))))))

(defn -main [& args]
  (let [all? (some #{"--all"} args)
        osv? (some #{"--osv"} args)
        repos (if all?
                (let [orgs (path/join root "orgs")]
                  (vec (for [org (try (fs/readdirSync orgs) (catch :default _ []))
                             repo (try (fs/readdirSync (path/join orgs org)) (catch :default _ []))]
                         (str "orgs/" org "/" repo))))
                (in-scope-repos))]
    (when (empty? repos)
      (js/console.error
       (str "Refusing to report an inventory: 対象 repo が 0 件。"
            (if all? " orgs/ が読めない。" (str " " scope-file " が無いか空 —— "
                                                "先に nbb scripts/gen-compliance-scope.cljs を回す。"))))
      (js/process.exit 2))
    (let [{:keys [rows stats] :as res} (scan repos)]
      (when (zero? (count rows))
        (js/console.error (str "Refusing to report an inventory: "
                               (:repos stats) " repo / " (:manifests stats)
                               " manifest を見たが依存を 1 件も読めなかった"))
        (js/process.exit 2))
      (let [datoms (->datoms res (not all?))
            cov (last datoms)]
        (if osv?
          (let [ds (butlast datoms)
                by-purl (group-by :dependency/purl ds)]
            (-> (osv-query! (distinct (keep :dependency/purl ds)))
              (.then (fn [{:keys [queried vulns skipped]}]
                       (println (str "OSV: 照会 " queried " 件"))
                       (println (str "  照会できなかった: version が範囲のまま "
                                     (:dependency/not-queryable cov) " 件"
                                     (when (seq skipped)
                                       (str " / OSV の対象 ecosystem 外 "
                                            (reduce + (vals skipped)) " 件 "
                                            (pr-str skipped)))))
                       (let [hits (->> vulns
                                       (map (fn [v]
                                              (let [nm (get-in v [:query "package" "name"])
                                                    ver (get-in v [:query "version"])
                                                    eco (get-in v [:query "package" "ecosystem"])
                                                    purl (if (= "npm" eco)
                                                           (str "pkg:npm/" nm "@" ver)
                                                           (str "pkg:maven/" (str/replace nm ":" "/") "@" ver))]
                                                {:name nm :version ver :ids (:ids v)
                                                 :repos (sort (distinct (map :dependency/repo (by-purl purl))))})))
                                       (sort-by :name))]
                         (doseq [h hits]
                           (println (str "  " (:name h) "@" (:version h)
                                         "  " (str/join " " (:ids h))))
                           (println (str "      使用: " (str/join ", " (:repos h))))
                           ;; orgs-detector プロトコル。key は purl なので、版が上がれば
                           ;; finding は resolve し、別の版で再発すれば別の finding になる。
                           (when (some #{"--findings"} args)
                             (println (str "FINDING\terror\tvuln:pkg/" (:name h) "@" (:version h)
                                           "\t" (str/join " " (:ids h))
                                           " — " (count (:repos h)) " repo: "
                                           (str/join ", " (:repos h))))))
                         ;; 照会できなかった分も finding にする。沈黙した除外は
                         ;; 「脆弱性が無い」と同じ顔をする。
                         (when (and (some #{"--findings"} args)
                                    (pos? (:dependency/not-queryable cov)))
                           (println (str "FINDING\twarn\tvuln-unqueryable:range-versions"
                                         "\t" (:dependency/not-queryable cov)
                                         " 件が version 範囲のままで照会できない — lockfile が無い manifest がある")))
                         (when (and (some #{"--findings"} args) (seq skipped))
                           (println (str "FINDING\tinfo\tvuln-unqueryable:ecosystem"
                                         "\t" (reduce + (vals skipped))
                                         " 件が OSV の対象 ecosystem 外 " (pr-str skipped)
                                         " — git 依存の脆弱性は別経路で見る必要がある")))
                         (println (str "VULNERABLE\t" (count hits)))
                         (println (str "SCANNED\t" queried)))))
              (.catch (fn [e]
                        ;; 応答本文を捨てない。status だけ記録する経路は原因を読まない。
                        (js/console.error (str "OSV 照会に失敗: " (.-message e)))
                        (js/process.exit 2)))))
          (let [header (str ";; 認証スコープ内の依存棚卸し —— **生成物。手で編集しない**\n"
                            ";; 再生成: nbb scripts/gen-dependency-inventory.cljs\n;;\n"
                            ";; SOC 2 CC7.1 / ISO/IEC 27001:2022 A.8.8 の入力。\n"
                            ";; 脆弱性照合のモデルは kotoba-lang/app-sbom に既に在る（未デプロイ）。\n"
                            ";; ここが埋めるのは『自分たちの依存を一度も測っていない』側だけ。\n;;\n"
                            ";; scope=" (name (:dependency/scope cov))
                            " repos=" (:dependency/repos-scanned cov)
                            " manifests=" (:dependency/manifests-read cov)
                            " rows=" (:dependency/rows cov) "\n"
                            ";; OSV に照会できる（version 確定）=" (:dependency/queryable cov)
                            " / できない（範囲のまま）=" (:dependency/not-queryable cov) "\n\n")
                body (str header (pr-str datoms) "\n")]
            (fs/mkdirSync (path/dirname out-file) #js {:recursive true})
            (fs/writeFileSync out-file body)
            (println (str "wrote " out-file))
            (println (str "  repos=" (:dependency/repos-scanned cov)
                          " manifests=" (:dependency/manifests-read cov)
                          " rows=" (:dependency/rows cov)))
            (println (str "  queryable=" (:dependency/queryable cov)
                          " not-queryable=" (:dependency/not-queryable cov)))
            (println (str "SCANNED\t" (:dependency/rows cov)))))))))

(apply -main *command-line-args*)
