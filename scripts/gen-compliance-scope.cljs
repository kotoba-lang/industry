#!/usr/bin/env nbb
;; 認証スコープ（SOC 2 / ISO 27001 / ISMAP）の資産棚卸しを **生成** する。
;;
;; ## なぜ要るのか
;;
;; SOC 2 は法人（service organization）と system boundary に対して出る。ISO 27001
;; は ISMS の適用範囲に対して出る。どちらも最初に要求されるのは **資産台帳**
;; （SOC 2 CC3.2 / CC6.1、ISO/IEC 27001:2022 A.5.9）で、これが無いと監査法人も
;; 認証機関も見積りすら出せない。
;;
;; 手で書くと必ず腐る。`90-docs/surface/surface.datoms.edn` は「どのホストが
;; どのパスを出しているか」を既に持っているが、**そのワーカがどのデータストアに
;; 触るか（binding）は持たない** —— 監査で問われるのはそこなので、ここで足す。
;;
;; ## 測れなかったことを「無い」と書かない
;;
;; CLAUDE.md「検査を書く前・緑を信じる前の 6 問」に従う:
;;
;; - 入力が 0 件なら pass せず **exit 2**（0 でも 1 でもない = 答えられなかった）
;; - 読めた数と並べられた数を **両方** 出す（`files-parsed` / `files-listed`）
;; - 形式ごとに分けて数える。TOML は下記のとおり限定パーサなので、
;;   **JSONC と同じ信頼度で数えない**
;;
;; ## TOML パーサの限界（先に申告する）
;;
;; `wrangler.toml` は行指向の限定読みしかしない —— `[[table]]` / `[table]` /
;; `key = "scalar"` と、ファイル全体からの `pattern = "..."` 抽出だけ。
;; 複数行のインラインテーブル配列や入れ子は読めない。読めなかった binding は
;; `:scope/coverage` の `:toml-partial?` で申告する。JSONC は完全に読む。
;;
;;   nbb scripts/gen-compliance-scope.cljs            # 生成
;;   nbb scripts/gen-compliance-scope.cljs --check    # 差分があれば非ゼロ終了

(ns gen-compliance-scope
  (:require ["fs" :as fs]
            ["path" :as path]
            [clojure.string :as str]))

(def root (.cwd js/process))
(def out-file (path/join root "90-docs" "compliance" "scope.datoms.edn"))

;; オーナーが認証取得の対象として名指しした 5 面（2026-08-23）。
;; ここに無いホストも棚卸しの対象に **する** —— 同じ Cloudflare account と
;; 同じ deploy credential を共有している面を境界の外に置くことはできない。
(def named-properties
  #{"kotobalabs.com" "kotoba-lang.org" "kotobase.net" "murakumo.cloud" "itonami.cloud"})

(defn- read-safe [f]
  (try (fs/readFileSync f "utf8") (catch :default _ nil)))

(defn- dirs [p]
  (try (->> (fs/readdirSync p #js {:withFileTypes true})
            (filter #(.isDirectory %))
            (map #(.-name %))
            (remove #(str/starts-with? % ".")))
       (catch :default _ [])))

(defn- strip-jsonc
  "JSONC のコメントを落とす。**文字列内の `//` を消さない** —— route pattern が
   `https://…` を含むので、素朴な行削除だと壊れる（gen-surface-index と同型）。"
  [s]
  (let [n (count s)]
    (loop [i 0 out [] in-str? false esc? false]
      (if (>= i n)
        (apply str out)
        (let [c (nth s i)]
          (cond
            esc? (recur (inc i) (conj out c) in-str? false)
            (and in-str? (= c \\)) (recur (inc i) (conj out c) true true)
            (= c \") (recur (inc i) (conj out c) (not in-str?) false)
            (and (not in-str?) (= c \/) (< (inc i) n) (= (nth s (inc i)) \/))
            (let [nl (or (str/index-of s "\n" i) n)] (recur nl out false false))
            ;; ブロックコメント。`//` だけ落とす版は `/* */` を JSON に残す。
            (and (not in-str?) (= c \/) (< (inc i) n) (= (nth s (inc i)) \*))
            (let [e (or (str/index-of s "*/" (+ i 2)) n)] (recur (+ e 2) out false false))
            :else (recur (inc i) (conj out c) in-str? false)))))))

(defn- drop-trailing-commas
  "`,` の直後が `}` / `]` なら落とす。JSONC では合法、`JSON.parse` では不正。

   初版はこれをやっておらず、**`net-kotobase/engine`（kotobase.net の本番
   worker）を静かに落としていた** —— 落ちた 2 件は数としては記録されていたが、
   どのファイルかを言っていなかったので誰も気付けなかった。"
  [s]
  (str/replace s #",(\s*[}\]])" "$1"))

;; ── binding 種別 → subprocessor ────────────────────────────────────────────
;; 監査で問われるのは「どのデータがどの第三者の手に渡るか」なので、binding の
;; 種別ではなく **預け先** を列に持つ。

(def binding-kinds
  "wrangler の binding セクション名 → {:kind, :vendor, :resource-key}。
   `:resource-key` は『どの実体か』を指す非秘密フィールド名。**値そのものは
   秘密ではない**（committed 済みの wrangler に既に載っている）。"
  {"d1_databases"              {:kind :d1        :vendor "cloudflare" :resource-key "database_name"}
   "kv_namespaces"             {:kind :kv        :vendor "cloudflare" :resource-key "id"}
   "r2_buckets"                {:kind :r2        :vendor "cloudflare" :resource-key "bucket_name"}
   "durable_objects"           {:kind :durable-object :vendor "cloudflare" :resource-key "class_name"}
   "queues"                    {:kind :queue     :vendor "cloudflare" :resource-key "queue"}
   "vectorize"                 {:kind :vectorize :vendor "cloudflare" :resource-key "index_name"}
   "hyperdrive"                {:kind :hyperdrive :vendor "cloudflare" :resource-key "id"}
   "analytics_engine_datasets" {:kind :analytics :vendor "cloudflare" :resource-key "dataset"}
   "ai"                        {:kind :workers-ai :vendor "cloudflare" :resource-key nil}
   "browser"                   {:kind :browser   :vendor "cloudflare" :resource-key nil}
   "images"                    {:kind :images    :vendor "cloudflare" :resource-key nil}
   "workflows"                 {:kind :workflow  :vendor "cloudflare" :resource-key "class_name"}
   "pipelines"                 {:kind :pipeline  :vendor "cloudflare" :resource-key "pipeline"}
   "mtls_certificates"         {:kind :mtls      :vendor "cloudflare" :resource-key "certificate_id"}
   "secrets_store_secrets"     {:kind :secret-ref :vendor "cloudflare" :resource-key "secret_name"}
   "dispatch_namespaces"       {:kind :dispatch  :vendor "cloudflare" :resource-key "namespace"}
   "send_email"                {:kind :email-send :vendor "cloudflare" :resource-key "destination_address"}
   "services"                  {:kind :service   :vendor "self"       :resource-key "service"}
   "assets"                    {:kind :static-assets :vendor "cloudflare" :resource-key "directory"}
   "version_metadata"          {:kind :version-metadata :vendor "cloudflare" :resource-key nil}})

(def vendor-domains
  "`vars`（committed・非秘密）の値に現れたら subprocessor として数える外部ドメイン。
   **allowlist 照合しかしない** —— 値そのものは 1 バイトも出力しない。誤って
   token が vars に入っていた場合に索引へ写さないため。"
  {"backblazeb2.com" "backblaze-b2"
   "b2-api.com"      "backblaze-b2"
   "api.stripe.com"  "stripe"
   "stripe.com"      "stripe"
   "api.resend.com"  "resend"
   "api.anthropic.com" "anthropic"
   "api.openai.com"  "openai"
   "api.github.com"  "github"
   "githubusercontent.com" "github"
   "murakumo.cloud"  "murakumo-fleet"
   "api.telnyx.com"  "telnyx"
   "hubapi.com"      "hubspot"
   "googleapis.com"  "google"
   "supabase.co"     "supabase"})

(defn- registrable
  "host → 登録可能ドメイン。**2 ラベル固定の素朴な実装** —— この workspace の
   対象ドメインはすべて 2 ラベル（kotobase.net 等）。`.co.jp` 型は誤る。"
  [h]
  (let [ls (str/split (or h "") #"\.")]
    (if (>= (count ls) 2) (str/join "." (take-last 2 ls)) h)))

;; ── JSONC 経路 ─────────────────────────────────────────────────────────────

(defn- jsonc-hosts [cfg]
  (->> (concat (map #(get % "pattern") (get cfg "routes" []))
               (keep identity [(get cfg "route")]))
       (keep (fn [p] (when (string? p)
                       (-> p (str/replace #"^https?://" "") (str/split #"/") first))))
       (remove str/blank?)
       distinct))

(defn- jsonc-bindings
  "cfg → binding 行。`:resource` は非秘密の識別フィールドのみ。"
  [cfg]
  (mapcat
   (fn [[section {:keys [kind vendor resource-key]}]]
     (let [v (get cfg section)
           entries (cond (map? v) [v]
                         (vector? v) v
                         (sequential? v) (vec v)
                         (some? v) [{}]
                         :else nil)]
       (for [e entries
             :when (map? e)]
         {:kind kind :vendor vendor
          :binding (or (get e "binding") (get e "name") (name kind))
          :resource (when resource-key (get e resource-key))})))
   binding-kinds))

(defn- vars-vendors
  "`vars` の値を allowlist に照合して subprocessor を拾う。値は出力しない。"
  [cfg]
  (let [vs (get cfg "vars")
        blob (when (map? vs) (str/join " " (filter string? (vals vs))))]
    (when (and blob (seq blob))
      (distinct (keep (fn [[d vendor]] (when (str/includes? blob d) vendor)) vendor-domains)))))

(defn- outbound-hosts
  "`vars`（committed・非秘密）の値に書かれた **http(s) URL のホスト名だけ** を拾う。

   境界の判定に要るのは『この面がどこへ出て行くか』であって、URL 全体ではない。
   path / query / userinfo は 1 バイトも出力しない —— 誤って token を含む var が
   あったときに索引へ写さないため。userinfo（`@`）を含む URL は丸ごと捨てる。"
  [cfg]
  (let [vs (get cfg "vars")]
    (when (map? vs)
      (->> (filter string? (vals vs))
           (mapcat #(re-seq #"https?://([A-Za-z0-9._-]+)(?:[:/?#]|$)" %))
           (map second)
           (remove #(or (str/blank? %) (str/includes? % "@")))
           (remove #(str/starts-with? % "localhost"))
           (remove #(re-matches #"[0-9.]+" %))
           distinct))))

;; ── TOML 経路（限定） ──────────────────────────────────────────────────────

(defn- toml-scalar [line]
  (when-let [[_ k v] (re-find #"^\s*([A-Za-z0-9_]+)\s*=\s*\"([^\"]*)\"" line)]
    [k v]))

(defn- parse-toml-limited
  "wrangler.toml の限定読み。返すのは {:name, :hosts, :bindings, :partial? }。
   `:partial?` は「読めなかった構文がこのファイルに在った」の申告であって、
   binding が無いことの意味ではない。"
  [raw]
  (let [lines (str/split-lines raw)
        hosts (->> (re-seq #"pattern\s*=\s*\"([^\"]+)\"" raw)
                   (map second)
                   (map #(-> % (str/replace #"^https?://" "") (str/split #"/") first))
                   (remove str/blank?)
                   distinct)
        ;; 読めない構文の申告: 複数行インラインテーブル / 入れ子配列
        partial? (boolean (re-find #"=\s*\[\s*$|=\s*\{[^}]*$" raw))]
    (loop [[l & more] lines, section nil, cur nil, out [], nm nil]
      (if (nil? l)
        {:name nm :hosts hosts
         :bindings (vec (remove nil? (conj out cur)))
         :partial? partial?}
        (cond
          ;; [[array-of-tables]] / [table]
          (re-find #"^\s*\[\[?([A-Za-z0-9_.]+)\]\]?" l)
          (let [s (second (re-find #"^\s*\[\[?([A-Za-z0-9_.]+)\]\]?" l))
                spec (get binding-kinds s)]
            (recur more s
                   (when spec {:kind (:kind spec) :vendor (:vendor spec)
                               :binding nil :resource nil :section s})
                   (if cur (conj out cur) out) nm))

          :else
          (let [[k v] (toml-scalar l)]
            (cond
              (and k (nil? section) (= k "name")) (recur more section cur out v)
              (and k cur)
              (let [spec (get binding-kinds (:section cur))
                    cur' (cond-> cur
                           (= k "binding") (assoc :binding v)
                           (and (:resource-key spec) (= k (:resource-key spec))) (assoc :resource v))]
                (recur more section cur' out nm))
              :else (recur more section cur out nm))))))))

;; ── 走査 ───────────────────────────────────────────────────────────────────

(defn- wrangler-files [repo-root]
  (letfn [(walk [d depth]
            (when (<= depth 3)
              (let [ents (try (fs/readdirSync d #js {:withFileTypes true}) (catch :default _ []))]
                (mapcat (fn [e]
                          (let [n (.-name e) p (path/join d n)]
                            (cond
                              (and (.isFile e) (#{"wrangler.jsonc" "wrangler.json" "wrangler.toml"} n)) [p]
                              (and (.isDirectory e)
                                   (not (str/starts-with? n "."))
                                   (not (#{"node_modules" "dist" "target" "public" "test"} n)))
                              (walk p (inc depth))
                              :else nil)))
                        ents))))]
    (vec (walk repo-root 0))))

(defn scan []
  (let [orgs-dir (path/join root "orgs")
        stats (atom {:listed 0 :jsonc-listed 0 :jsonc-parsed 0 :jsonc-failed 0
                     :toml-listed 0 :toml-parsed 0 :toml-partial 0 :unreadable 0
                     :failed-files []})
        rows (atom [])]
    (doseq [org (dirs orgs-dir)
            repo (dirs (path/join orgs-dir org))
            cfg-file (wrangler-files (path/join orgs-dir org repo))]
      (swap! stats update :listed inc)
      (let [toml? (str/ends-with? cfg-file ".toml")
            raw (read-safe cfg-file)
            rel (str/replace cfg-file (str root "/") "")
            repo-path (str "orgs/" org "/" repo)]
        (swap! stats update (if toml? :toml-listed :jsonc-listed) inc)
        (if (nil? raw)
          (swap! stats update :unreadable inc)
          (let [parsed (if toml?
                         (try (parse-toml-limited raw) (catch :default _ nil))
                         (when-let [cfg (try (js->clj (js/JSON.parse (drop-trailing-commas (strip-jsonc raw))))
                                             (catch :default _ nil))]
                           ;; `env.<name>` の routes / bindings も本体と同じ重みで数える。
                           ;; 初版はここを歩いておらず、`kotobase-cf-wasm-testnet.aozora.app`
                           ;; （engine の env.testnet）を落としていた —— 隔離環境は
                           ;; 監査スコープの外ではなく、**別スコープの資産**である。
                           (let [envs (get cfg "env")
                                 sub (when (map? envs) (filter map? (vals envs)))]
                             {:name (get cfg "name")
                              :hosts (distinct (mapcat jsonc-hosts (cons cfg sub)))
                              :bindings (mapcat jsonc-bindings (cons cfg sub))
                              :vendors (distinct (mapcat vars-vendors (cons cfg sub)))
                              :outbound (distinct (mapcat outbound-hosts (cons cfg sub)))
                              :partial? false})))]
            (if (nil? parsed)
              (do (swap! stats update :failed-files conj rel)
                  (swap! stats update (if toml? :toml-listed :jsonc-failed) (if toml? identity inc)))
              (do
                (swap! stats update (if toml? :toml-parsed :jsonc-parsed) inc)
                (when (:partial? parsed) (swap! stats update :toml-partial inc))
                (let [hosts (seq (:hosts parsed))
                      props (distinct (map registrable hosts))
                      bs (:bindings parsed)]
                  ;; binding 1 件 = 1 行。binding が無いワーカも 1 行残す
                  ;; （「binding が無い」と「読めなかった」を出力で区別するため）。
                  (doseq [b (or (seq bs) [{:kind :none :vendor "cloudflare" :binding nil :resource nil}])]
                    (swap! rows conj
                           {:worker (:name parsed)
                            :repo repo-path
                            :hosts (vec hosts)
                            :properties (vec props)
                            :named? (boolean (some named-properties props))
                            :kind (:kind b) :vendor (:vendor b)
                            :binding (:binding b) :resource (:resource b)
                            :format (if toml? :toml :jsonc)
                            :file rel}))
                  (doseq [oh (:outbound parsed)
                          :let [op (registrable oh)]
                          ;; 自分自身の面への URL は外向きではない
                          :when (not (contains? (set props) op))]
                    (swap! rows conj
                           {:worker (:name parsed) :repo repo-path
                            :hosts (vec hosts) :properties (vec props)
                            :named? (boolean (some named-properties props))
                            :kind :outbound-url
                            :vendor (or (get vendor-domains op) op)
                            :binding nil :resource oh
                            :format (if toml? :toml :jsonc) :file rel}))
                  (doseq [v (:vendors parsed)]
                    (swap! rows conj
                           {:worker (:name parsed) :repo repo-path
                            :hosts (vec hosts) :properties (vec props)
                            :named? (boolean (some named-properties props))
                            :kind :external-endpoint :vendor v
                            :binding nil :resource nil
                            :format (if toml? :toml :jsonc) :file rel})))))))))
    {:rows @rows :stats @stats}))

(defn ->datoms [{:keys [rows stats]}]
  (let [ds (map-indexed
            (fn [i r]
              (cond-> {:db/id (- (inc i))
                       :scope/worker (:worker r)
                       :scope/repo (:repo r)
                       :scope/binding-kind (:kind r)
                       :scope/subprocessor (:vendor r)
                       :scope/named-property? (:named? r)
                       :scope/config-format (:format r)
                       :source/dataset "compliance-scope"
                       :source/file (:file r)}
                (:binding r)  (assoc :scope/binding-name (:binding r))
                (:resource r) (assoc :scope/resource (:resource r))
                (seq (:hosts r))      (assoc :scope/host (first (:hosts r)))
                (seq (:properties r)) (assoc :scope/property (first (:properties r))
                                             :scope/property-count (count (:properties r)))
                ;; 2 面以上に応答する 1 ワーカは、境界をそこで切れないことの証拠。
                ;; SOC 2 の system boundary / ISO 27001 の適用範囲は、ドメイン単位では
                ;; なく **共有された制御環境** の単位でしか切れない。
                (> (count (:properties r)) 1)
                (assoc :scope/co-hosted (str/join "," (sort (:properties r))))))
            rows)
        cov {:db/id (- (inc (count rows)))
             :scope/coverage true
             :source/dataset "compliance-scope"
             :scope/files-listed (:listed stats)
             :scope/jsonc-listed (:jsonc-listed stats)
             :scope/jsonc-parsed (:jsonc-parsed stats)
             :scope/jsonc-failed (:jsonc-failed stats)
             :scope/toml-listed (:toml-listed stats)
             :scope/toml-parsed (:toml-parsed stats)
             :scope/toml-partial (:toml-partial stats)
             :scope/files-unreadable (:unreadable stats)
             ;; TOML は限定パーサ。JSONC と同じ信頼度で数えないための印。
             ;; **どのファイルが落ちたかを名前で残す。** 数だけの記録は
             ;; 「2 件落ちた」が読まれずに終わる —— 実際、落ちていた 1 件は
             ;; kotobase.net の本番 worker だった。
             :scope/failed-files (vec (sort (:failed-files stats)))
             :scope/toml-parser :line-oriented-subset}]
    (vec (concat ds [cov]))))

(defn- finding!
  "FINDING<TAB>severity<TAB>key<TAB>detail — orgs-detector プロトコル。

   key は安定した識別子にする（worker 名 / path / ドメイン）。文言を変えても
   同じ finding が NEW として数え直されないため。"
  [severity key detail]
  (println (str "FINDING\t" severity "\t" key "\t" detail)))

(defn- report-findings!
  "書き込みを 1 バイトもせずに、境界に関わる 3 種を報告する。

   1. 名指しされた面を含む cross-boundary worker —— 境界をそこで切れない証拠
   2. parse できなかった config —— 数ではなく名前で出す
   3. 資産が 1 件も観測されない名指し面 —— 『測ったが安全』ではなく『まだ無い』"
  [{:keys [rows stats]}]
  (doseq [r (->> rows
                 (filter #(> (count (:properties %)) 1))
                 (filter #(some named-properties (:properties %)))
                 (map #(vector (:worker %) (str/join "," (sort (:properties %))) (:file %)))
                 distinct
                 (sort-by first))]
    (finding! "warn" (str "cross-boundary:" (first r))
              (str (second r) " を 1 ワーカが跨ぐ (" (nth r 2) ")"
                   " — system boundary はドメイン単位では切れない")))
  (doseq [f (sort (:failed-files stats))]
    (finding! "error" (str "unparsable-config:" f)
              "wrangler 設定が parse できない — この worker は資産台帳と surface 索引の両方から消えている"))
  (let [seen (set (mapcat :properties rows))]
    (doseq [d (sort (remove seen named-properties))]
      (finding! "info" (str "named-property-absent:" d)
                "認証対象として名指しされたが、稼働資産が 1 件も観測されない — UNVERIFIED であって clean ではない"))))

(defn -main [& args]
  (let [{:keys [rows stats] :as res} (scan)
        parsed (+ (:jsonc-parsed stats) (:toml-parsed stats))]
    ;; 入力が無いときに pass しない。0 でも 1 でもない値で終わる。
    (when (zero? parsed)
      (js/console.error
       (str "Refusing to report a scope: parsed 0 of " (:listed stats) " wrangler config(s). "
            "cone 外 / 未 checkout の可能性がある —— `git ls-files` で確かめること。"))
      ;; `set! exitCode` + throw では nbb が 1 で上書きする（実測 2026-08-23）。
      ;; 「答えられなかった」は 0 でも 1 でもない値でなければ意味が無いので、
      ;; ここは exit を直に呼ぶ。
      (js/process.exit 2))
    (when (some #{"--findings"} args)
      (report-findings! res)
      (println (str "SCANNED\t" parsed))
      (js/process.exit 0))
    (let [datoms (->datoms res)
          props (->> rows (mapcat :properties) distinct sort)
          named (->> props (filter named-properties) sort)
          cross (->> rows (filter #(> (count (:properties %)) 1))
                     (map #(str/join "+" (sort (:properties %)))) distinct sort)
          header (str ";; 認証スコープの資産棚卸し —— **生成物。手で編集しない**\n"
                      ";; 再生成: nbb scripts/gen-compliance-scope.cljs\n;;\n"
                      ";; SOC 2 CC3.2/CC6.1 と ISO/IEC 27001:2022 A.5.9 が要求する資産台帳の\n"
                      ";; 機械可読な下地。`:scope/repo` は repo-taxonomy の `:repo/path` と、\n"
                      ";; `:scope/host` は surface 索引の `:surface/host` と join できる。\n;;\n"
                      ";; ⚠ TOML は限定パーサ（行指向 subset）。JSONC と同じ信頼度で数えない。\n"
                      ";;   読めた数と並べた数は `:scope/coverage` entity に両方入っている。\n;;\n"
                      ";; worker=" (count (distinct (keep :worker rows)))
                      " repo=" (count (distinct (map :repo rows)))
                      " binding=" (count rows)
                      " property=" (count props) "\n"
                      ";; named-property=" (str/join "," named)
                      " (kotobalabs.com: 観測されず)\n"
                      ";; cross-boundary=" (count cross)
                      (when (seq cross) (str " -> " (str/join " / " cross))) "\n"
                      ";; parsed=" parsed "/" (:listed stats)
                      " (jsonc " (:jsonc-parsed stats) "/" (:jsonc-listed stats)
                      ", toml " (:toml-parsed stats) "/" (:toml-listed stats) ")\n\n")
          body (str header (pr-str datoms) "\n")]
      (if (some #{"--check"} args)
        (let [cur (read-safe out-file)]
          (if (= cur body)
            (println (str "compliance scope: 最新\nSCANNED\t" parsed))
            (do (js/console.error "compliance scope が古い。再生成せよ: nbb scripts/gen-compliance-scope.cljs")
                (set! (.-exitCode js/process) 1))))
        (do (fs/mkdirSync (path/dirname out-file) #js {:recursive true})
            (fs/writeFileSync out-file body)
            (println (str "wrote " out-file))
            (println (str "  worker=" (count (distinct (keep :worker rows)))
                          " repo=" (count (distinct (map :repo rows)))
                          " binding=" (count rows)))
            (println (str "  parsed=" parsed "/" (:listed stats)
                          " (jsonc " (:jsonc-parsed stats) "/" (:jsonc-listed stats)
                          ", toml " (:toml-parsed stats) "/" (:toml-listed stats)
                          ", unreadable " (:unreadable stats) ")"))
            (println (str "SCANNED\t" parsed))))))) 

(apply -main *command-line-args*)
