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
;;   nbb scripts/gen-surface-index.cljs            # 生成
;;   nbb scripts/gen-surface-index.cljs --check    # 差分があれば非ゼロ終了

(ns gen-surface-index
  (:require ["fs" :as fs]
            ["path" :as path]
            [clojure.string :as str]))

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
            :else (recur (inc i) (conj out c) in-str? false)))))))

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
                              (and (.isFile e) (#{"wrangler.jsonc" "wrangler.json"} n)) [p]
                              (and (.isDirectory e)
                                   (not (str/starts-with? n "."))
                                   (not (#{"node_modules" "dist" "target" "public" "test"} n)))
                              (walk p (inc depth))
                              :else nil)))
                        ents))))]
    (vec (walk repo-root 0))))

(defn scan []
  (let [orgs-dir (path/join root "orgs")]
    (for [org (dirs orgs-dir)
          repo (dirs (path/join orgs-dir org))
          :let [rp (path/join orgs-dir org repo)]
          cfg-file (wrangler-files rp)
          :let [raw (read-safe cfg-file)]
          :when raw
          :let [cfg (try (js->clj (js/JSON.parse (strip-jsonc raw)))
                         (catch :default _ nil))]
          :when cfg
          :let [hs (hosts-of cfg)
                ps (paths-of (path/join (path/dirname cfg-file) "src"))]
          h hs
          p (or (seq ps) [{:path "/" :file cfg-file}])]
      {:host h
       :path (:path p)
       :repo (str "orgs/" org "/" repo)
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
           :surface/worker (:worker r)
           :surface/kind (:kind r)
           :source/dataset "surface"
           :source/file (:file r)})
        rows)))

(defn -main [& args]
  (let [rows (vec (scan))
        datoms (->datoms rows)
        header (str ";; 稼働面の索引 —— **生成物。手で編集しない**\n"
                    ";; 再生成: nbb scripts/gen-surface-index.cljs\n;;\n"
                    ";; 2026-08-03 の重複（murakumo に /signup を手書きしたが\n"
                    ";; authn.kotobase.net/sign-in が既に稼働していた）を防ぐために作った。\n"
                    ";; CLAUDE.md の『作る前に確認する』が効かなかったのは意思ではなく\n"
                    ";; **見る場所が無かった**から。\n;;\n"
                    ";; ⚠ カバレッジは完全ではない。segment ベクタ形のルート\n"
                    ";;   （(= seg [\"v1\" \"gen\"])）は拾えない。拾えた分だけを記録する。\n;;\n"
                    ";; host=" (count (distinct (map :host rows)))
                    " repo=" (count (distinct (map :repo rows)))
                    " surface=" (count rows) "\n\n")
        body (str header (pr-str datoms) "\n")]
    (if (some #{"--check"} args)
      (let [cur (read-safe out-file)]
        (if (= cur body)
          (println "surface index: 最新")
          (do (js/console.error "surface index が古い。再生成せよ: nbb scripts/gen-surface-index.cljs")
              (set! (.-exitCode js/process) 1))))
      (do (fs/mkdirSync (path/dirname out-file) #js {:recursive true})
          (fs/writeFileSync out-file body)
          (println (str "wrote " out-file))
          (println (str "  host=" (count (distinct (map :host rows)))
                        " repo=" (count (distinct (map :repo rows)))
                        " surface=" (count rows)))))))

(apply -main *command-line-args*)
