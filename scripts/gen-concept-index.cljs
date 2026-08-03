#!/usr/bin/env nbb
;; 「この workspace に X はあるか」に**有界な答え**を返す索引を生成する。
;;
;; ## なぜ要るのか
;;
;; 2026-08-03、`kotoba-lang に terminal, console は設計実装されている?` に
;; 「無い」と答えた。実際には 2026-06-30 から `kotoba-lang/kuro`（terminal
;; model）・`kotoba-lang/kobo`（workbench）・ADR-2606301000 が在った。
;;
;; 原因は 2 つあり、どちらも grep では直らない:
;;
;;   1. **出力を切った。** `grep -rn terminal --include=README.md` は
;;      `kuro/README.md:3` に当たっていた。4,050 repo 分の出力を `head -40`
;;      で切ったので、当の行を見ていない。grep は無界で、無界な出力は切られる。
;;   2. **名前が機能を示さない。** `kuro`（黒）も `kobo`（工房）も terminal と
;;      一文字も共有しない。名前からの推測経路が存在しない。
;;
;; `90-docs/surface/surface.datoms.edn` が同じ日に同じ理由（見る場所が無い）で
;; 作られた。あれは **HTTP の面**を索引する。これは **概念**を索引する。
;;
;; ## 手書きしない（語彙だけ手書き）
;;
;; 索引本体は README から生成する（手書きの索引は必ず腐る）。「端末 と terminal
;; は同じものを指す」だけは repo の中身から導出できないので
;; `manifest/concept-vocabulary.edn` に手で持つ。
;;
;; ## カバレッジを偽らない
;;
;; README が無い repo は索引できない。**その数を数えて記録する** —— 索引を
;; 引いて出なかったことが「存在しない」の証拠に使われるので、どれだけ見て
;; いないかを言えないと索引は嘘をつく。
;;
;;   nbb scripts/gen-concept-index.cljs                      # 生成
;;   nbb scripts/gen-concept-index.cljs --check              # 差分があれば非ゼロ終了
;;   nbb scripts/gen-concept-index.cljs --scan-root <path>   # orgs/ を別 checkout から読む

(ns gen-concept-index
  (:require ["fs" :as fs]
            ["path" :as path]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(def root (.cwd js/process))
(def out-file (path/join root "90-docs" "concept" "concept.datoms.edn"))

(defn- read-safe [f]
  (try (fs/readFileSync f "utf8") (catch :default _ nil)))

(defn- dirs [p]
  (try (->> (fs/readdirSync p #js {:withFileTypes true})
            (filter #(.isDirectory %))
            (map #(.-name %))
            (remove #(str/starts-with? % ".")))
       (catch :default _ [])))

(defn vocabulary []
  (-> (read-safe (path/join root "manifest" "concept-vocabulary.edn"))
      (or "{}")
      edn/read-string))

(defn west-paths
  "west.yml が pin している path の集合。

   未登録 = 正本とは限らない作業コピー。`surface` 索引が 2026-08-03 に学んだ
   のと同じ理由で持つ —— 『どこにあるか』だけでは足りず、**そこを編集して
   よいか**が要る。"
  []
  (let [raw (or (read-safe (path/join root "manifest" "west.yml")) "")]
    (into #{} (map second) (re-seq #"(?m)^\s+path:\s+(\S+)\s*$" raw))))

(defn readme-file [repo-dir]
  (first (keep #(let [p (path/join repo-dir %)]
                  (when (read-safe p) p))
               ["README.md" "readme.md" "Readme.md" "README.markdown"])))

(defn lede
  "README の『これは何か』の部分 —— H1 と、最初の散文段落 2 つまで。

   全文を見ない: 依存の羅列・例・変更履歴に概念語が現れると、その repo が
   その概念の実装であるかのように索引されてしまう（`dtn` の README は
   『Terminal 1 — long-running node』と書くが端末の実装ではない）。"
  [src]
  (let [lines (str/split-lines src)
        prose (->> lines
                   (remove #(str/starts-with? (str/trim %) "```"))
                   (remove #(re-find #"^\s*[|>-]" %)))]
    (->> prose
         (drop-while str/blank?)
         (take 14)
         (str/join " "))))

(defn headings
  "`## ...` 見出し。lede に無い機能はここに出る —— operator console 規約の
   23 repo は本文冒頭ではなく `## Operator console (UI/UX)` で名乗る。"
  [src]
  (->> (str/split-lines src)
       (keep #(second (re-find #"^#{2,3}\s+(.+?)\s*$" %)))
       (str/join " · ")))

(defn- escape-re [s]
  (str/replace s #"[.*+?^${}()|\[\]\\]" "\\\\$&"))

(defn spelling-match?
  "綴りが**語として**現れるか。

   最初の版は `str/includes?` だった。`ide` が `provide` に、`repl` が
   `replication` に、`auth` が `authoritative` に当たり、1 件目の entry が
   『aburi は IDE』だった（実測）。**当たりすぎる索引は当たらない索引と同じく
   使われない** —— 雑音は無視され、無視される索引は存在しないのと同じ。

   ASCII の綴りは英数字に挟まれていないことを要求する。日本語は語境界が無いので
   部分一致のまま（`端末` が別語の一部になることは実際上ない）。"
  [haystack spelling]
  (let [s (str/lower-case spelling)]
    (if (re-find #"[^\x00-\x7F]" s)
      (str/includes? haystack s)
      (boolean (re-find (js/RegExp. (str "(?<![a-z0-9])" (escape-re s) "(?![a-z0-9])") "i")
                        haystack)))))

(defn hits
  "この文字列が触れている概念 → その綴り。"
  [vocab s]
  (let [l (str/lower-case (or s ""))]
    (keep (fn [[concept {:keys [spellings]}]]
            (when-let [m (seq (filter #(spelling-match? l %) spellings))]
              [concept (vec m)]))
          vocab)))

(defn- gloss
  "索引に載せる 1 行。読んだ人が repo を開かずに当たりかどうか判断できる長さ。"
  [s]
  (let [t (-> (str/replace s #"\s+" " ") str/trim)]
    (if (> (count t) 160) (str (subs t 0 160) "…") t)))

(defn scan [scan-root]
  (let [vocab (vocabulary)
        registered (west-paths)
        orgs-dir (path/join scan-root "orgs")
        repos (for [org (dirs orgs-dir)
                    repo (dirs (path/join orgs-dir org))]
                {:org org :repo repo
                 :path (str "orgs/" org "/" repo)
                 :dir (path/join orgs-dir org repo)})
        with-readme (keep (fn [r]
                            (when-let [f (readme-file (:dir r))]
                              (assoc r :readme f :src (read-safe f))))
                          repos)]
    {:repos (count repos)
     :indexable (count with-readme)
     :rows
     (vec (for [{:keys [org repo path src readme]} with-readme
                :let [ld (lede src)
                      hd (headings src)
                      ;; repo 名そのものも見る。`terminal` を名乗る repo は
                      ;; 名前が機能を示す数少ない側なので拾わない理由が無い。
                      subject (str repo " " ld " " hd)]
                [concept spellings] (hits vocab subject)]
            {:concept (name concept)
             :repo path
             :name repo
             :org org
             :spellings spellings
             :gloss (gloss (if (str/blank? ld) hd ld))
             :registered? (contains? registered path)
             :file (str/replace readme (str scan-root "/") "")}))}))

(defn ->datoms [{:keys [rows repos indexable]}]
  (conj
   (vec (map-indexed
         (fn [i r]
           {:db/id (- (inc i))
            :concept/term (:concept r)
            :concept/repo (:repo r)
            :concept/name (:name r)
            :concept/org (:org r)
            :concept/spellings (pr-str (:spellings r))
            :concept/gloss (:gloss r)
            ;; west 未登録 = 正本とは限らない作業コピー
            :concept/registered? (:registered? r)
            :source/dataset "concept"
            :source/file (:file r)})
         rows))
   ;; カバレッジは entity として持つ。header コメントだけだと query した人に
   ;; 届かず、『索引に無い＝存在しない』を無検証で信じさせてしまう。
   {:db/id (- (inc (count rows)))
    :concept/coverage "concept-index"
    :concept/repos-total repos
    :concept/repos-indexable indexable
    :concept/repos-without-readme (- repos indexable)
    :source/dataset "concept"}))

(defn render [scanned datoms]
  (str ";; 概念の索引 —— **生成物。手で編集しない**\n"
       ";; 再生成: nbb scripts/gen-concept-index.cljs\n"
       ";; 語彙:   manifest/concept-vocabulary.edn（こちらは手書き）\n"
       ";; 検索:   nbb scripts/concept-lookup.cljs <語>\n;;\n"
       ";; 2026-08-03、『kotoba-lang に terminal, console はあるか』に「無い」と\n"
       ";; 答えた。kuro / kobo / ADR-2606301000 は 2026-06-30 から在った。grep は\n"
       ";; README に当たっていたが、4,050 repo 分の出力を head -40 で切っていた。\n"
       ";; **無界な検索は切られる。** 索引は有界な答えを返すために在る。\n;;\n"
       ";; ⚠ カバレッジ: README のある repo だけを見る。README の無い "
       (- (:repos scanned) (:indexable scanned)) " repo は\n"
       ";;   索引できない（:concept/coverage entity に記録）。**索引に無いことは\n"
       ";;   存在しないことの証拠にならない。**\n;;\n"
       ";; repo=" (:repos scanned) " indexable=" (:indexable scanned)
       " entries=" (count (:rows scanned)) "\n\n"
       (pr-str datoms) "\n"))

(defn -main [& args]
  (let [args (vec args)
        scan-root (or (second (drop-while #(not= "--scan-root" %) args)) root)
        scanned (scan scan-root)
        out (render scanned (->datoms scanned))]
    (if (some #{"--check"} args)
      (if (= out (read-safe out-file))
        (println "concept index: up to date")
        (do (println "concept index: STALE — rerun nbb scripts/gen-concept-index.cljs")
            (set! (.-exitCode js/process) 1)))
      (do (fs/mkdirSync (path/dirname out-file) #js {:recursive true})
          (fs/writeFileSync out-file out)
          (println (str "concept index: " (count (:rows scanned)) " entries over "
                        (:indexable scanned) "/" (:repos scanned) " repos → "
                        (str/replace out-file (str root "/") "")))))))

(apply -main *command-line-args*)
