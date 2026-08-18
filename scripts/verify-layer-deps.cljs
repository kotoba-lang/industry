#!/usr/bin/env nbb
;; verify-layer-deps.cljs — ADR-2607171100 Wave 3.
;; manifest/layers.edn の layer taxonomy に対して 3 点を lint する:
;;   (a) deps.edn の :local/root エッジが layer DAG(依存方向)に違反していないか
;;   (b) repo 内の番号 dir (00-〜70-) 新設 — repo-internal-allowed 外かつ
;;       grandfathered/frozen 以外は violation
;;   (c) :local/root が workspace(orgs/)の外を指していないか
;;
;; 使い方:  nbb scripts/verify-layer-deps.cljs            ; 全 org スキャン
;;          nbb scripts/verify-layer-deps.cljs --report   ; violation でも exit 0(レポートのみ)
;;          nbb scripts/verify-layer-deps.cljs --findings ; 機械可読な finding も出す
;;
;; layer 解決は layers.edn の :naming glob。どの glob にも合わない repo は
;; :layer-unknown として DAG 検査対象外(件数のみ報告)。
;;
;; ## fleet gate にはできない
;;
;; この検査は `orgs/` 配下の全 repo を歩く。murakumo fleet gate が配るのは対象
;; repo 自身の tree だけなので、ノード上では空の workspace を歩いて 0 件 =
;; 合格になる。したがって `manifest/orgs-detectors.edn` に登録し、`orgs/` を
;; 実際に持つこのマシンの tick で回す。
;;
;; ## --findings が出すもの
;;
;;   FINDING<TAB>severity<TAB>key<TAB>detail   violation 1 件につき 1 行
;;   SCANNED<TAB>n<TAB>unit                    証拠行(n=0 は :inconclusive 扱い)
;;
;; key は **構造的な識別子**(kind + repo + 対象)であって、件数などの動く数を
;; 含めない。含めると同じ violation が毎回 resolved かつ new として報告される。
;; --findings を付けなければ出力は従来とバイト同一。
(require '[clojure.edn :as edn]
         '[clojure.string :as str]
         '["fs" :as fs]
         '["path" :as node-path]
         '["child_process" :as cp])

(def argv (into [] (.slice js/process.argv 2)))
(def report-only? (some #{"--report"} argv))
(def findings? (some #{"--findings"} argv))

(def root (str/trim (str (.execSync cp "git rev-parse --show-toplevel"))))
(def layers-file (or (.. js/process -env -LAYERS_EDN)
                     (node-path/join root "manifest" "layers.edn")))
(def cfg (edn/read-string (fs/readFileSync layers-file "utf8")))

(def layers (:manifest.layers/layers cfg))
(def dag (set (map vec (:manifest.layers/dependency-dag cfg))))
(def no-inbound (set (:manifest.layers/no-inbound-edges cfg)))
(def internal-allowed (set (:manifest.layers/repo-internal-allowed cfg)))
(def frozen (set (:manifest.layers/frozen-legacy-roots cfg)))
(def grandfathered (set (:manifest.layers/grandfathered-internal-dirs cfg)))

(defn glob->re [g]
  (re-pattern (str "^" (-> g
                           (str/replace #"[.+^${}()|\[\]\\]" "\\$&")
                           (str/replace "*" ".*"))
                   "$")))

(def naming-rules
  (for [{:keys [layer naming]} layers, g naming
        :when (not (str/includes? g "<"))]   ; "<protocol-name>" 等の記述的 glob は除外
    [(glob->re g) layer]))

(defn repo-layer [repo-name]
  (or (some (fn [[re l]] (when (re-find re repo-name) l)) naming-rules)
      :layer-unknown))

(defn list-dirs [p]
  (if (fs/existsSync p)
    (->> (fs/readdirSync p #js {:withFileTypes true})
         (filter #(.isDirectory %))
         (map #(.-name %)))
    []))

;; deps.edn の任意の深さから :local/root 値を集める
(defn collect-local-roots [form]
  (let [acc (atom [])]
    (clojure.walk/postwalk
     (fn [x]
       (when (and (map? x) (contains? x :local/root))
         (swap! acc conj (:local/root x)))
       x)
     form)
    @acc))

(require '[clojure.walk])

(def orgs-dir (node-path/join root "orgs"))
(def repos
  (for [org (list-dirs orgs-dir)
        repo (list-dirs (node-path/join orgs-dir org))]
    {:org org :repo repo
     :west-path (str "orgs/" org "/" repo)
     :abs (node-path/join orgs-dir org repo)}))

(def violations (atom []))
(def stats (atom {:repos 0 :deps-parsed 0 :deps-skipped 0 :edges 0 :unknown-layer 0}))

(doseq [{:keys [org repo west-path abs]} repos]
  (swap! stats update :repos inc)
  (let [layer (repo-layer repo)]
    (when (= layer :layer-unknown) (swap! stats update :unknown-layer inc))
    ;; (b) 番号 dir 検査
    (when-not (or (frozen west-path) (grandfathered west-path))
      (doseq [d (list-dirs abs)
              :when (and (re-find #"^\d\d-" d) (not (internal-allowed d)))]
        (swap! violations conj
               {:kind :internal-numbered-dir :repo west-path :dir d})))
    ;; (a)(c) deps.edn エッジ検査
    (let [deps-file (node-path/join abs "deps.edn")]
      (when (fs/existsSync deps-file)
        (if-let [form (try (edn/read-string {:default (fn [_ v] v)}
                                            (fs/readFileSync deps-file "utf8"))
                           (catch :default _
                             (swap! stats update :deps-skipped inc) nil))]
          (do (swap! stats update :deps-parsed inc)
              (doseq [lr (collect-local-roots form)
                      :when (string? lr)]
                (swap! stats update :edges inc)
                (let [target (node-path/resolve abs lr)
                      rel (node-path/relative root target)]
                  (if-not (str/starts-with? rel "orgs/")
                    (when-not (str/starts-with? rel "..")  ; repo 内 subdir 参照は許容
                      (swap! violations conj
                             {:kind :local-root-outside-orgs :repo west-path :target rel}))
                    (let [[_ _torg trepo] (str/split rel #"/")
                          tlayer (repo-layer (or trepo ""))]
                      (cond
                        (no-inbound tlayer)
                        (swap! violations conj
                               {:kind :dag-violation :repo west-path :layer layer
                                :target rel :target-layer tlayer
                                :why "no-inbound layer"})
                        (and (not= layer :layer-unknown) (not= tlayer :layer-unknown)
                             (not= layer tlayer) (not (dag [layer tlayer])))
                        (swap! violations conj
                               {:kind :dag-violation :repo west-path :layer layer
                                :target rel :target-layer tlayer
                                :why "edge not in dependency-dag"})))))))
          nil)))))

(println "verify-layer-deps: " (pr-str @stats))
(if (empty? @violations)
  (println "OK: no layer violations")
  (do (println (count @violations) "violation(s):")
      (doseq [v @violations] (println " " (pr-str v)))))

(defn- finding-key
  "violation の安定な識別子。kind と repo と『どこが』だけで作り、件数や
   layer 名のような後から変わりうる注釈を混ぜない。"
  [{:keys [kind repo dir target]}]
  (str (name kind) ":" repo ":" (or dir target)))

(when findings?
  ;; 証拠行を先に出す。何も歩かなかった run を clean として記録させないための床。
  ;; 数えるのは「実際に走査した repo ディレクトリ」で、orgs/ が無い場所で走れば
  ;; 0 になり、tick 側が :inconclusive として扱う。
  (println (str "SCANNED\t" (:repos @stats) "\trepo director(ies) under orgs/"))
  (doseq [v @violations]
    (println (str "FINDING\tfail\t" (finding-key v) "\t" (pr-str v)))))

(when (and (seq @violations) (not report-only?))
  (.exit js/process 1))
