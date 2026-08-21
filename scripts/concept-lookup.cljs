#!/usr/bin/env nbb
;; 「この workspace に X はあるか」を**有界に**引く。
;;
;;   nbb scripts/concept-lookup.cljs terminal
;;   nbb scripts/concept-lookup.cljs 端末
;;   nbb scripts/concept-lookup.cljs console --all
;;
;; grep との違いは 1 点だけ: **答えが有界で、順位が付いている**。4,050 repo に
;; grep をかけると出力が数百行になり、切られ、切られたことに気付かない
;; （2026-08-03 に実際そうなった。詳細は scripts/gen-concept-index.cljs）。
;;
;; 索引は `90-docs/concept/concept.datoms.edn`（生成物）、語彙は
;; `manifest/concept-vocabulary.edn`（手書き）。

(ns concept-lookup
  (:require ["fs" :as fs]
            ["path" :as path]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(def root (.cwd js/process))

(defn- read-edn [p]
  (try (edn/read-string {:default (fn [_ v] v)} (fs/readFileSync p "utf8"))
       (catch :default _ nil)))

(def index (or (read-edn (path/join root "90-docs" "concept" "concept.datoms.edn")) []))
(def vocab (or (read-edn (path/join root "manifest" "concept-vocabulary.edn")) {}))

(defn matching-concepts
  "問い合わせ語 → 概念。綴りとの部分一致を両方向で見る（`term` で
   `terminal-emulator` に、`terminal emulator` で `terminal` に届く）。

  `:query-spellings` も見る。**問いとして良い語と、索引の鍵として良い語は
  別**である —— `proposal` はまさに人が訊く語だが、README に書かれた
  `proposal` を全部拾うと 94 repo に当たって答えが埋まる（実測 2026-08-16、
  `:kip` に `proposal` を綴りとして入れた直後に 99 件返した）。索引するのは
  `:spellings` だけ、問いを受けるのは両方、という非対称をここで作る。"
  [q]
  (let [l (str/lower-case q)]
    (keep (fn [[c {:keys [spellings query-spellings]}]]
            (when (or (str/includes? (name c) l)
                      (some #(let [s (str/lower-case %)]
                               (or (str/includes? s l) (str/includes? l s)))
                            (concat spellings query-spellings)))
              c))
          vocab)))

(defn read-edn-str [s]
  (or (try (edn/read-string (str s)) (catch :default _ nil)) []))

(defn score
  "順位。**綴りをいくつ引き当てたか**が主。`kuro` は terminal / tty / pty /
   shell session / console の 5 つに当たり、`console` 1 つだけの operator
   console repo より上に来る —— これが名前で辿れない repo を見つける経路。"
  [row]
  (let [spellings (count (read-edn-str (:concept/spellings row)))]
    (+ (* 10 spellings)
       (if (:concept/registered? row) 3 0)
       ;; repo 名自体が概念を名乗っているなら、それは強い自己申告
       (if (some #(str/includes? (str/lower-case (:concept/name row)) (str/lower-case %))
                 (read-edn-str (:concept/spellings row)))
         5 0))))

(defn coverage []
  (first (filter :concept/coverage index)))

(defn- print-rows [rows limit]
  (doseq [r (take limit rows)]
    (println (str "  " (:concept/name r)
                  (when-not (:concept/registered? r) "  ⚠west未登録")
                  "\n    " (:concept/repo r)
                  "\n    " (:concept/gloss r))))
  (when (> (count rows) limit)
    (println (str "  … 他 " (- (count rows) limit) " 件（--all で全件）"))))

(defn -main [& args]
  (let [args (vec args)
        all? (some #{"--all"} args)
        q (first (remove #(str/starts-with? % "--") args))]
    (cond
      (empty? index)
      (println "索引が空です。nbb scripts/gen-concept-index.cljs で生成してください。")

      (nil? q)
      (do (println "使い方: nbb scripts/concept-lookup.cljs <語> [--all]\n")
          (println "語彙（manifest/concept-vocabulary.edn）:")
          (doseq [[c {:keys [label]}] (sort-by key vocab)]
            (println (str "  " (name c) "  —  " label))))

      :else
      (let [concepts (matching-concepts q)]
        (if (empty? concepts)
          (do (println (str "『" q "』は語彙に無い概念です。"))
              (println "  索引の gloss を素通しで検索します（順位無し）:")
              (let [l (str/lower-case q)
                    hits (filter #(str/includes? (str/lower-case (str (:concept/gloss %))) l)
                                 index)]
                (print-rows (distinct hits) (if all? 10000 10))
                (println))
              (println "  この語で今後も引くなら manifest/concept-vocabulary.edn に足してください。"))
          (doseq [c concepts]
            (let [{:keys [label note]} (get vocab c)
                  rows (->> index
                            (filter #(= (name c) (:concept/term %)))
                            (sort-by score >))]
              (println (str "\n■ " (name c) " — " label
                            "  (" (count rows) " repo)"))
              (when note (println (str "  » " note)))
              (print-rows rows (if all? 10000 8)))))
        (when-let [cv (coverage)]
          (println (str "\n⚠ 索引の対象は README のある repo だけ（"
                        (:concept/repos-indexable cv) "/" (:concept/repos-total cv)
                        "、README 無し " (:concept/repos-without-readme cv)
                        " 件は未索引）。"
                        "\n  **索引に無いことは存在しないことの証拠になりません。**")))))))

(apply -main *command-line-args*)
