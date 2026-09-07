#!/usr/bin/env nbb
;; 稼働面の索引を **生成** する（手書きしない）。
;;
;; ## なぜ要るのか
;;
;; 2026-08-03、murakumo に `/signup` を手書きした。`authn.kotobase.net/sign-in`
;; が既に稼働していることを知らなかったからで、同じ日に email OTP と hosted UI
;; も作りかけた。いずれも **既に実装され動いていた**。
;;
;; CLAUDE.md には既に『作る前に、同種の重なる仕組みが既にないか確認する』と
;; 書いてある。それを読んだうえで重複させた —— **意思ではなく、見る場所が
;; 無かった**のが原因。`west.yml` は 4,050 個の repo 名を持つが、
;; 「どのホストがどのパスを提供しているか」は持たない。
;;
;; ## 手書きしない
;;
;; 索引を手で書くと必ず腐る。`wrangler.jsonc` の `routes` と、worker ソースの
;; パス literal から生成する。**生成できない部分は『生成できなかった』と記録**
;; して、カバレッジを偽らない。
;;
;; 出力は `90-docs/surface/surface.datoms.edn`（`manifest/edn-query.cljs` で
;; 引ける datom 形）。`:surface/repo` が `repo-taxonomy` の `:repo/path` と
;; 同じ形なので join できる。
;;
;;   nbb --classpath "orgs/kotoba-lang/org-toml/src" \\
;;     scripts/gen-surface-index.cljs            # 生成
;;   nbb --classpath "orgs/kotoba-lang/org-toml/src" \\
;;     scripts/gen-surface-index.cljs --check    # 差分があれば非ゼロ終了

(ns gen-surface-index
  (:require ["fs" :as fs]
            ["path" :as path]
            [clojure.string :as str]
            ;; TOML is read by kotoba-lang/org-toml (portable .cljc, TOML v1.0.0).
            ;; Requires --classpath orgs/kotoba-lang/org-toml/src. If that
            ;; checkout is absent this file fails to load, LOUDLY, which is the
            ;; point: an index that silently drops a whole config format is the
            ;; failure this generator's own docstring warns about.
            [toml.reader :as toml]))

(def root (.cwd js/process))
(def out-file (path/join root "90-docs" "surface" "surface.datoms.edn"))

(defn- read-safe [f]
  (try (fs/readFileSync f "utf8") (catch :default _ nil)))

(defn- dirs [p]
  (try (->> (fs/readdirSync p #js {:withFileTypes true})
            (filter #(.isDirectory %))
            (map #(.-name %))
            (remove #(str/starts-with? % ".")))
       (catch :default _ [])))

(defn- strip-jsonc
  "JSONC のコメントを落とす。**文字列内の `//` を消さない** —— URL が
   `https://…` を含むので、素朴な行削除だと route pattern が壊れる。"
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

   これが無いあいだ、この索引は **`net-kotobase/engine`（backend.kotobase.net の
   本番 worker）を静かに落としていた**。gen-compliance-scope は同じ穴を塞いだが、
   同型と書かれていたこちらには移されないまま残っていた —— しかもこの索引は
   parsed/listed を数えないので、落ちたことが出力のどこにも出なかった。"
  [s]
  (str/replace s #",(\s*[}\]])" "$1"))

(defn- hosts-of
  "wrangler 設定 → このワーカが応答するホスト名。"
  [cfg]
  (let [pats (concat (map #(get % "pattern") (get cfg "routes" []))
                     (keep identity [(get cfg "route")]))]
    (->> pats
         (keep (fn [p] (when (string? p)
                         (-> p (str/replace #"^https?://" "") (str/split #"/") first))))
         (remove str/blank?)
         distinct)))

(def ^:private path-re #"\"(/[a-zA-Z0-9._/-]{0,60})\"")

(defn- paths-of
  "ソースからパス literal を拾う。

   **完全ではない** —— segment ベクタ（`(= seg [\"v1\" \"gen\"])`）のような
   書き方は拾えない。拾えた分だけを記録し、カバレッジを偽らない。"
  [src-dir]
  (letfn [(walk [d]
            (let [ents (try (fs/readdirSync d #js {:withFileTypes true}) (catch :default _ []))]
              (mapcat (fn [e]
                        (let [p (path/join d (.-name e))]
                          (cond
                            (.isDirectory e) (when-not (str/starts-with? (.-name e) ".") (walk p))
                            (re-find #"\.(cljs|cljc|clj|ts|js)$" (.-name e))
                            (let [s (or (read-safe p) "")]
                              (map (fn [m] {:path (second m) :file p}) (re-seq path-re s)))
                            :else nil)))
                      ents)))]
    (->> (walk src-dir)
         (remove #(re-find #"\.(js|css|png|svg|map|json|md|edn|wasm)$" (:path %)))
         (remove #(< (count (:path %)) 2))
         distinct)))

(defn- west-paths
  "west.yml が pin している path の集合。

   ## なぜ索引が『正本かどうか』を持つ必要があるか

   実測 2026-08-03: 初版の索引は `authn.kotobase.net` の面を
   `net-kotobase-enterprise-wave2/3` `net-kotobase-obsidian-story` に帰属させた。
   **3 つとも west 未登録の作業コピー**で、正本は `net-kotobase`（同じ remote の
   別チェックアウト）だった。

   『どこにあるか』だけでは足りない —— **そこを編集してよいか**が分からないと、
   未登録のコピーを直して『やった』と思い込む。今朝の merkle-lsm（本番の
   コードがコミットに辿れない）と同じ欠陥クラスで、認証基盤で起きればより痛い。"
  []
  (let [raw (or (read-safe (path/join root "manifest" "west.yml")) "")]
    (into #{} (map second) (re-seq #"(?m)^\s+path:\s+(\S+)\s*$" raw))))

(defn- page-like? [p]
  (boolean (re-find #"(?i)(sign|login|logout|signup|register|account|auth|profile|settings|billing|checkout|pricing|store)" p)))

(defn- wrangler-files
  "repo 配下の wrangler 設定を **サブディレクトリまで**探す。

   ⚠ 最初の版は `orgs/<org>/<repo>/wrangler.jsonc` の 2 階層しか見ておらず、
   **`net-kotobase-enterprise-wave2/authn/` を見落とした** —— つまりこの索引を
   作る動機になった当の面（`authn.kotobase.net/sign-in`）を拾えていなかった。
   実測で気付いた（生成後に `sign` を検索したら出てこなかった）。

   **目的そのものを外す索引は、無いより悪い** —— 『検索したが無かった』が
   『存在しない』の証拠として使われるので。深さ 3 まで降りる。"
  [repo-root]
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

;; Configs that could not be parsed are COUNTED, not silently dropped. A
;; generator that skips what it cannot read reports the same empty result for
;; "this repo serves nothing" and "this repo's config defeated the parser", and
;; the second is then quoted as the first.
(defonce unparsed (atom []))

(defn- parse-config
  "wrangler config -> map, dispatched on extension.

   ⚠ Until 2026-09-07 this only read `.jsonc`/`.json`, so EVERY Worker
   configured in TOML was invisible to the index — measured that day: 43
   wrangler.toml under orgs/, 22 of them declaring routes, including
   `agent.itonami.cloud` and `mcp.itonami.cloud`, two live public hosts. The
   index that exists to answer \"which host serves what\" answered nothing for
   them, and nothing reads the same as does-not-exist."
  [file raw]
  (let [r (if (str/ends-with? file ".toml")
            (let [{:keys [status value]} (toml/read raw)]
              (when (= :ok status) value))
            (try (js->clj (js/JSON.parse (drop-trailing-commas (strip-jsonc raw))))
                 (catch :default _ nil)))]
    (when-not r (swap! unparsed conj file))
    r))

(defn scan []
  (let [orgs-dir (path/join root "orgs")
        registered (west-paths)]
    (for [org (dirs orgs-dir)
          repo (dirs (path/join orgs-dir org))
          :let [rp (path/join orgs-dir org repo)]
          cfg-file (wrangler-files rp)
          :let [raw (read-safe cfg-file)]
          :when raw
          :let [cfg (parse-config cfg-file raw)]
          :when cfg
          :let [hs (hosts-of cfg)
                ps (paths-of (path/join (path/dirname cfg-file) "src"))]
          h hs
          p (or (seq ps) [{:path "/" :file cfg-file}])]
      {:host h
       :path (:path p)
       :repo (str "orgs/" org "/" repo)
       :registered? (contains? registered (str "orgs/" org "/" repo))
       :worker (get cfg "name")
       :kind (if (page-like? (:path p)) :page :api)
       :file (str/replace (:file p) (str root "/") "")})))

(defn ->datoms [rows]
  (vec (map-indexed
        (fn [i r]
          {:db/id (- (inc i))
           :surface/host (:host r)
           :surface/path (:path r)
           :surface/repo (:repo r)
           ;; west 未登録 = **正本とは限らない作業コピー**。編集先を誤らせない
           ;; ための印（2026-08-03、authn を未登録コピーに帰属させた実測から）。
           :surface/registered? (:registered? r)
           :surface/worker (:worker r)
           :surface/kind (:kind r)
           :source/dataset "surface"
           :source/file (:file r)})
        rows)))

(defn -main [& args]
  (let [rows (vec (scan))
        datoms (->datoms rows)
        header (str ";; 稼働面の索引 —— **生成物。手で編集しない**\n"
                    ";; 再生成: nbb --classpath \"orgs/kotoba-lang/org-toml/src\" scripts/gen-surface-index.cljs\n;;\n"
                    ";; 2026-08-03 の重複（murakumo に /signup を手書きしたが\n"
                    ";; authn.kotobase.net/sign-in が既に稼働していた）を防ぐために作った。\n"
                    ";; CLAUDE.md の『作る前に確認する』が効かなかったのは意思ではなく\n"
                    ";; **見る場所が無かった**から。\n;;\n"
                    ";; ⚠ カバレッジは完全ではない。segment ベクタ形のルート\n"
                    ";;   （(= seg [\"v1\" \"gen\"])）は拾えない。拾えた分だけを記録する。\n;;\n"
                    ";; host=" (count (distinct (map :host rows)))
                    " repo=" (count (distinct (map :repo rows)))
                    " surface=" (count rows)
                    ;; Evidence floor: configs the parser could not read are
                    ;; counted here rather than dropped in silence, so a future
                    ;; reader can tell "serves nothing" from "was not read".
                    " unparsed-configs=" (count @unparsed) "\n\n")
        body (str header (pr-str datoms) "\n")]
    (if (some #{"--check"} args)
      (let [cur (read-safe out-file)]
        (if (= cur body)
          (println "surface index: 最新")
          (do (js/console.error (str "surface index が古い。再生成せよ: "
                                 "nbb --classpath \"orgs/kotoba-lang/org-toml/src\" "
                                 "scripts/gen-surface-index.cljs"))
              (set! (.-exitCode js/process) 1))))
      (do (fs/mkdirSync (path/dirname out-file) #js {:recursive true})
          (fs/writeFileSync out-file body)
          (println (str "wrote " out-file))
          (println (str "  host=" (count (distinct (map :host rows)))
                        " repo=" (count (distinct (map :repo rows)))
                        " surface=" (count rows)))))))

(apply -main *command-line-args*)
