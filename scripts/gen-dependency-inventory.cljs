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
  "deps.edn の :deps と各 alias の :extra-deps / :replace-deps を 1 本に。

   **`:deps` は production、alias 側は development** として印を付けて返す。
   両者を同じ列に混ぜると、test runner の脆弱性が本番の脆弱性と同じ緊急度で
   並ぶ（2026-08-23 に実際にそう報告し、triage を誤った）。"
  [m]
  (concat (map (fn [e] [e false]) (:deps m))
          (mapcat (fn [[_ a]] (map (fn [e] [e true])
                                   (concat (:extra-deps a) (:replace-deps a))))
                  (:aliases m))))

(defn- from-deps-edn [file rel]
  (when-let [m (try (edn/read-string (read-safe file)) (catch :default _ nil))]
    (keep (fn [[[coord spec] dev?]]
            (cond
              (:mvn/version spec)
              {:purl (maven-purl coord (:mvn/version spec))
               :ecosystem :maven :name (str coord) :version (:mvn/version spec)
               :exact? true :dev? dev? :file rel}

              (:git/sha spec)
              (when-let [p (github-purl (:git/url spec) (:git/sha spec))]
                {:purl p :ecosystem :github :name (str coord) :version (:git/sha spec)
                 :exact? true :dev? dev? :file rel})

              ;; :local/root は同じ tree の中。第三者依存ではないので
              ;; 脆弱性照会の対象にしないが、**数からは落とさない**。
              (:local/root spec)
              {:purl nil :ecosystem :internal :name (str coord) :version nil
               :exact? true :dev? dev? :file rel}

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
                ;; npm が既に計算した `dev` をそのまま運ぶ。ここを落とすと
                ;; miniflare 経由の dev 依存が本番依存と区別できなくなる。
                {:purl (npm-purl nm ver) :ecosystem :npm :name nm :version ver
                 :exact? true :dev? (boolean (or (get v "dev") (get v "devOptional")))
                 :file rel})))
          (get m "packages"))))

(def ^:private source-exts
  #{".ts" ".tsx" ".js" ".jsx" ".mjs" ".cjs" ".svelte" ".vue"
    ".cljs" ".cljc" ".clj" ".kotoba"})

(defn- source-files
  "repo 配下の source を集める。node_modules と生成物は除く。"
  [repo-root]
  (letfn [(walk [d depth]
            (when (<= depth 5)
              (let [ents (try (fs/readdirSync d #js {:withFileTypes true}) (catch :default _ []))]
                (mapcat (fn [e]
                          (let [n (.-name e) q (path/join d n)]
                            (cond
                              (and (.isFile e) (some #(str/ends-with? n %) source-exts)) [q]
                              (and (.isDirectory e)
                                   (not (str/starts-with? n "."))
                                   (not (#{"node_modules" "dist" "target" "build" "out" "public"} n)))
                              (walk q (inc depth))
                              :else nil)))
                        ents))))]
    (vec (walk repo-root 0))))

(defn- imported-anywhere?
  "この repo の source が `nm` を **直接** import しているか。

   ## これが答えない問い

   **推移依存の到達性には答えない。** 直接 import されていない推移依存でも、
   親が使っていれば実行時には到達する。ここが答えるのは
   『package.json が直接宣言しているのに、どのソースからも参照されていない』
   という 1 つの形だけで、それ以外は `nil`（判定していない）を返す。

   実測 2026-08-23 (`cloud-itonami/media`): `kysely` は `dependencies` に在り
   HIGH 3 件を持つが、import しているファイルは **0 件**、脆弱 API の使用も 0 件、
   さらに `media.itonami.cloud` は DNS が解決しない。到達性を測らずに
   『HIGH×3・本番』だけを見れば 0.27→0.28 の major bump をかけるところだった。
   正しい是正は使っていない依存を外すことだった。

   照合は字句的で近似である —— 動的 `require(name)` や re-export 経由は拾えない。
   したがって **false は『使っていない』の証明ではなく『直接参照が見当たらない』**
   であり、finding にはそう書く。"
  [repo-root nm]
  (let [pat (re-pattern (str "(?:require\\(|from\\s+|import\\s+|\\[\")[\"']"
                             (str/replace nm #"[.*+?^${}()|\[\]\\]" "\\$&")
                             "(?:/[^\"']*)?[\"']"))]
    (boolean (some (fn [f]
                     (when-let [t (read-safe f)] (re-find pat t)))
                   (source-files repo-root)))))

(defn- declared-directly?
  "この repo のどれかの package.json が `nm` を直接宣言しているか。

   宣言されていない = 推移依存なので、`imported-anywhere?` の答えは意味を持たない
   （親経由で到達しうる）。両者を分けるためにここで判定する。"
  [repo-root nm]
  (letfn [(walk [d depth]
            (when (<= depth 3)
              (let [ents (try (fs/readdirSync d #js {:withFileTypes true}) (catch :default _ []))]
                (mapcat (fn [e]
                          (let [n (.-name e) q (path/join d n)]
                            (cond
                              (and (.isFile e) (= n "package.json")) [q]
                              (and (.isDirectory e) (not (str/starts-with? n "."))
                                   (not (#{"node_modules" "dist" "target" "build"} n)))
                              (walk q (inc depth))
                              :else nil)))
                        ents))))]
    (boolean (some (fn [f]
                     (when-let [m (read-json f)]
                       (some #(contains? (get m %) nm)
                             ["dependencies" "devDependencies" "optionalDependencies"])))
                   (walk repo-root 0)))))

(defn- reachability
  "-> :unused-direct | :direct-and-imported | :transitive | :unknown

   `:transitive` は **『到達しない』ではない** —— 親が使っていれば実行時に到達する。
   判定できないことを、判定した結果と同じ顔にしない（ADR-2608136000）。"
  [repo nm]
  (let [root (path/join root repo)]
    (if-not (fs/existsSync root)
      :unknown
      (if (declared-directly? root nm)
        (if (imported-anywhere? root nm) :direct-and-imported :unused-direct)
        :transitive))))

(defn- from-pnpm-lock
  "pnpm-lock.yaml (v9) の `packages:` から確定版を取る。

   なぜ足したか: このスキャナは `package-lock.json` しか読まず、**pnpm を使う
   65 manifest を『version が範囲のまま = 未測定』として数えていた**（実測
   2026-08-23）。lockfile が在るのに無いものとして数えるのは、
   ADR-2608136000 が禁じている『測れなかったものを測った結果と同じ顔にする』
   の裏返しで、こちらは *測れるものを測らずに未測定に入れて* いた。

   形式は v9 で統一されている（実測: 30 ファイルすべて `lockfileVersion: '9.0'`）:

     packages:
       '@adobe/css-tools@4.4.4':
         resolution: {...}

   **name は最後の `@` で切る。** `@adobe/css-tools@4.4.4` の先頭 `@` で切ると
   name が空になる —— OSV 照会で同じ間違いをして 1000 件の batch を落とした。

   dev/production は v9 の `packages:` からは判らない（`importers:` を辿る必要が
   ある）ので、**判らないものを production と主張しない**: `:dev? nil` を返し、
   coverage 側で『scope 不明』として数える。"
  [file rel]
  (when-let [raw (read-safe file)]
    (let [lines (str/split-lines raw)
          start (first (keep-indexed (fn [i l] (when (= "packages:" (str/trim l)) i)) lines))]
      (when start
        (->> (drop (inc start) lines)
             (take-while #(or (str/blank? %) (str/starts-with? % "  ")))
             (keep (fn [l]
                     (when-let [m (re-find #"^  '?([^':]+?)'?:\s*$" l)]
                       (let [k (nth m 1)
                             k (if-let [i (str/index-of k "(")] (subs k 0 i) k)
                             i (.lastIndexOf k "@")]
                         (when (pos? i)
                           (let [nm (subs k 0 i) ver (subs k (inc i))]
                             (when (and (seq nm) (re-matches #"[0-9][^\s]*" ver))
                               {:purl (npm-purl nm ver) :ecosystem :npm
                                :name nm :version ver :exact? true
                                :dev? nil :file rel})))))))
             vec)))))

(defn- from-package-json [file rel]
  (when-let [m (read-json file)]
    (mapcat (fn [k]
              (keep (fn [[nm spec]]
                      (when (string? spec)
                        (let [exact? (boolean (re-matches #"\d+\.\d+\.\d+.*" spec))]
                          {:purl (when exact? (npm-purl nm spec))
                           :ecosystem :npm :name nm :version spec
                           :exact? exact? :dev? (= k "devDependencies") :file rel})))
                    (get m k)))
            ["dependencies" "devDependencies" "optionalDependencies"])))

;; ── 走査 ───────────────────────────────────────────────────────────────────

(def ^:private manifest-names
  #{"deps.edn" "package.json" "package-lock.json" "pnpm-lock.yaml"})

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
  (let [stats (atom {:repos 0 :manifests 0 :unreadable 0 :lock-preferred 0 :pnpm-read 0})
        rows (atom [])]
    (doseq [repo repos]
      (swap! stats update :repos inc)
      (let [ms (manifests (path/join root repo))
            ;; 同じディレクトリに lock が在れば package.json は読まない。
            ;; 範囲と確定版が二重に入ると、同じ依存が 2 件に見える。
            ;; lockfile を持つディレクトリでは package.json（範囲）を読まない。
            ;; pnpm も同じ扱いにする —— 以前は package-lock.json だけを見ており、
            ;; pnpm の repo は lockfile が在るのに未測定に数えられていた。
            lock-dirs (set (map path/dirname
                                (filter #(or (str/ends-with? % "package-lock.json")
                                             (str/ends-with? % "pnpm-lock.yaml")) ms)))]
        (doseq [f ms
                :let [rel (str/replace f (str root "/") "")
                      base (path/basename f)
                      dir (path/dirname f)]]
          (swap! stats update :manifests inc)
          (let [es (cond
                     (= base "deps.edn") (from-deps-edn f rel)
                     (= base "package-lock.json") (do (swap! stats update :lock-preferred inc)
                                                      (from-package-lock f rel))
                     (= base "pnpm-lock.yaml") (do (swap! stats update :lock-preferred inc)
                                                   (swap! stats update :pnpm-read inc)
                                                   (from-pnpm-lock f rel))
                     (and (= base "package.json") (not (lock-dirs dir))) (from-package-json f rel)
                     :else [])]
            (when (and (nil? es) (not= base "package.json"))
              (swap! stats update :unreadable inc))
            (doseq [e (or es [])]
              (swap! rows conj (assoc e :repo repo)))))))
    {:rows @rows :stats @stats}))

(defn ->datoms [{:keys [rows stats]} scoped?]
  (let [uniq (->> rows
                  ;; 同じ package が production と development の両方に現れることが
                  ;; ある。dev? を鍵に含めないと、片方が消えて triage が狂う。
                  (group-by (juxt :repo :ecosystem :name :version :dev?))
                  (map (fn [[_ g]] (first g))))
        ds (map-indexed
            (fn [i r]
              (cond-> {:db/id (- (inc i))
                       :dependency/repo (:repo r)
                       :dependency/ecosystem (:ecosystem r)
                       :dependency/name (:name r)
                       :dependency/exact? (:exact? r)
                       ;; **本番に載るか、開発時だけか。** これが無いと、
                       ;; miniflare の中の undici が本番の依存と同じ緊急度で並ぶ。
                       :dependency/dev? (:dev? r)
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
             :dependency/pnpm-lockfiles-read (:pnpm-read stats)
             :dependency/rows (count uniq)
             ;; **OSV に投げられるのは exact なものだけ。** 範囲のまま照会すると
             ;; 「該当なし」が返り、それは「脆弱性が無い」と同じ顔をする。
             ;; `:dev? nil` は「判らなかった」であって production ではない。
             ;; 3 つに分けて数える —— 2 値に畳むと pnpm 由来が本番として並ぶ。
             :dependency/production (count (filter #(false? (:dev? %)) uniq))
             :dependency/development (count (filter #(true? (:dev? %)) uniq))
             :dependency/scope-unknown (count (filter #(nil? (:dev? %)) uniq))
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

;; `--selftest-reachability` — 一時ディレクトリに小さな repo を建てて 4 状態を実演する。
;; 片方向しか出さない検査は、その判定が働いていることを示さない（CLAUDE.md
;; 「gate は落ちることを確かめてから landed とする」の対偶）。
(defn- selftest-reachability! []
  (let [dir (path/join (.tmpdir (js/require "node:os")) "dep-reach-selftest")
        w! (fn [rel content]
             (let [f (path/join dir rel)]
               (fs/mkdirSync (path/dirname f) #js {:recursive true})
               (fs/writeFileSync f content)))]
    (try (fs/rmSync dir #js {:recursive true :force true}) (catch :default _ nil))
    (w! "package.json" (js/JSON.stringify
                        (clj->js {"dependencies" {"used-pkg" "^1.0.0" "unused-pkg" "^2.0.0"}})))
    (w! "src/app.ts" "import { thing } from \"used-pkg\";\nexport const x = thing;\n")
    ;; 直接宣言されていない = 推移依存。import されていても :transitive のまま。
    (w! "src/other.ts" "const y = require(\"transitive-pkg\");\n")
    (let [rel (str/replace dir (str root "/") "")
          ;; reachability は root 相対を取るので、絶対パスの場合はそのまま使う
          probe (fn [nm]
                  (if (declared-directly? dir nm)
                    (if (imported-anywhere? dir nm) :direct-and-imported :unused-direct)
                    :transitive))
          r1 (probe "used-pkg") r2 (probe "unused-pkg") r3 (probe "transitive-pkg")
          missing (reachability "orgs/does-not-exist-xyz" "anything")
          ok (and (= :direct-and-imported r1) (= :unused-direct r2)
                  (= :transitive r3) (= :unknown missing))]
      (println "reachability selftest")
      (println (str "  (1) 宣言あり + import あり -> " (name r1)))
      (println (str "  (2) 宣言あり + import なし -> " (name r2) "  ← 是正は bump ではなく削除"))
      (println (str "  (3) 宣言なし（推移依存）   -> " (name r3) "  ← 『到達しない』とは言わない"))
      (println (str "  (4) checkout が無い         -> " (name missing) "  ← 測れていないと申告"))
      (println (if ok "  SELFTEST PASS" "  SELFTEST FAIL"))
      (try (fs/rmSync dir #js {:recursive true :force true}) (catch :default _ nil))
      ok)))

(defn -main [& args]
  (when (some #{"--selftest-reachability"} args)
    (js/process.exit (if (selftest-reachability!) 0 1)))
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
                                                (let [rows (by-purl purl)
                                                      ;; **`(remove :dependency/dev? …)` と書かない。**
                                                      ;; `:dev? nil` は「pnpm lockfile からは判らなかった」で
                                                      ;; あって production ではない。not で畳むと判らなかった
                                                      ;; ものが本番として並ぶ（実測 2026-08-23: pnpm 対応を
                                                      ;; 入れた直後に undici が誤って PRODUCTION と出た）。
                                                      prod (filter #(false? (:dependency/dev? %)) rows)
                                                      unk (filter #(nil? (:dependency/dev? %)) rows)]
                                                  {:name nm :version ver :ids (:ids v)
                                                   :production? (boolean (seq prod))
                                                   :scope-unknown? (boolean (and (empty? prod) (seq unk)))
                                                   :reach (let [rs (sort (distinct (map :dependency/repo rows)))]
                                                            (into {} (map (fn [r] [r (reachability r nm)]) rs)))
                                                   :prod-repos (sort (distinct (map :dependency/repo prod)))
                                                   :unk-repos (sort (distinct (map :dependency/repo unk)))
                                                   :repos (sort (distinct (map :dependency/repo rows)))}))))
                                       (sort-by :name))]
                         ;; **本番に載るものを先に出す。** dev 依存と同じ順に並べると
                         ;; 読む側が緊急度を取り違える（2026-08-23 に実際に取り違えた）。
                         (doseq [h (sort-by (juxt (complement :production?) :name) hits)]
                           (println (str "  " (cond (:production? h) "PRODUCTION"
                                                   (:scope-unknown? h) "scope不明  "
                                                   :else "dev-only  ")
                                         "  " (:name h) "@" (:version h)
                                         "  " (str/join " " (:ids h))))
                           (println (str "      使用 " (count (:repos h)) " repo"
                                         (when (:production? h)
                                           (str " / うち本番 " (count (:prod-repos h)) ": "
                                                (str/join ", " (:prod-repos h))))
                                         (when (:scope-unknown? h)
                                           (str " / scope 不明 " (count (:unk-repos h)) " repo"
                                                "（pnpm lockfile は dev/prod を言わない）"))))
                           (doseq [[r k] (sort-by key (:reach h))]
                             (println (str "        到達性 " (name k) "  " r
                                           (case k
                                             :unused-direct "  ← 直接宣言だが import 0。是正は bump ではなく削除"
                                             :transitive    "  ← 推移依存。親経由で到達しうる（未判定）"
                                             :unknown       "  ← checkout が無く測れていない"
                                             ""))))
                           ;; orgs-detector プロトコル。key は purl なので、版が上がれば
                           ;; finding は resolve し、別の版で再発すれば別の finding になる。
                           (when (some #{"--findings"} args)
                             (println (str "FINDING\t" (if (:production? h) "error" "warn")
                                           "\tvuln:pkg/" (:name h) "@" (:version h)
                                           "\t" (str/join " " (:ids h))
                                           " — " (cond
                                                   (:production? h)
                                                   (str "本番 " (count (:prod-repos h)) " repo: "
                                                        (str/join ", " (:prod-repos h)))
                                                   (:scope-unknown? h)
                                                   (str "scope 不明（pnpm）" (count (:unk-repos h)) " repo")
                                                   :else
                                                   (str "開発時のみ、" (count (:repos h)) " repo"))
                                           " / 到達性 "
                                           (str/join "," (distinct (map (comp name val) (:reach h))))))))
                         ;; 照会できなかった分も finding にする。沈黙した除外は
                         ;; 「脆弱性が無い」と同じ顔をする。
                         (when (and (some #{"--findings"} args)
                                    (pos? (:dependency/not-queryable cov)))
                           (println (str "FINDING\twarn\tvuln-unqueryable:no-purl"
                                         "\t" (:dependency/not-queryable cov)
                                         " 件に purl が無く照会できない（version が範囲、または :local/root の同一 tree 依存）")))
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
                            ";; 本番=" (:dependency/production cov)
                            " 開発時のみ=" (:dependency/development cov)
                            " scope不明=" (:dependency/scope-unknown cov)
                            " ← この 3 つを混ぜると triage を誤る（不明を本番に数えない）\n"
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
