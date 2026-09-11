#!/usr/bin/env nbb
;; snapshot-freshness-check.cljs — committed なデータ snapshot が古くなったら赤くする gate。
;;
;; 対象は `*.manifest.edn`（`:manifest/fetched-at` を epoch ms で持つもの）。
;; tree 内の manifest を全部読み、`--max-age-days` を超えたものが 1 つでもあれば FAIL。
;;
;;   npx nbb snapshot-freshness-check.cljs <dir> --sub <subdir> --max-age-days 21 --min 3
;;
;; ## なぜ gate なのか
;;
;; watchlist-screen の snapshot は 2026-07-19 から 2026-08-26 まで 38 日古いまま
;; だった。**screening は毎回正しく `:watchlist/stale? true` を返していた** ——
;; 誰もそのフィールドを読んでいなかっただけである。値が正しく計算されていることと、
;; 誰かがそれに気づくことは別で、後者には赤くなる場所が要る。
;;
;; ## 閾値をライブラリの staleness より緩くしてある理由
;;
;; `watchlist.model/default-stale-after-days` は 14 で、この gate の既定は 21。
;; **測っているものが違う**: ライブラリの 14 は「この答えを信用してよいか」、
;; gate の 21 は「更新の仕組みが動いているか」。同じ数にすると、1 回走り遅れた
;; だけで赤くなり、赤が「仕組みが壊れた」を意味しなくなる。
;;
;; ## 測れなかったことを clean と書かない
;;
;; - manifest が `--min` 件未満なら FAIL。tarball の展開ミスや `:include-ext`
;;   の絞り込みで空 tree を検査し「0 件 = 全部新鮮」と報告する事故を防ぐ床。
;; - `:manifest/fetched-at` が無い / 数値でない manifest は **stale と同じ扱い**
;;   で FAIL する。読めなかった manifest を「新鮮」に丸めない。
;; - 何件走査したかを必ず `SCANNED<TAB>n` で印字する。

(ns fleet-ci.gates.snapshot-freshness-check
  (:require ["node:fs" :as fs]
            ["node:path" :as path]
            [cljs.reader :as reader]
            [clojure.string :as str]))

(def args (vec *command-line-args*))

(defn- flag [n default]
  (let [i (.indexOf args n)]
    (if (neg? i) default (nth args (inc i)))))

(def sub (flag "--sub" nil))
;; `<dir>` は先頭の非フラグ引数。fleet は必ず先頭で渡すが、手元で
;; `gate.cljs --min 3 .` と書くと "3" を tree のパスにしてしまうので、
;; フラグの値も候補から除いてから拾う（CLAUDE.md の実測済みの罠）。
(def flag-values (set (remove nil? [sub (flag "--max-age-days" nil) (flag "--min" nil)])))
(def root (let [d (or (first (remove #(or (str/starts-with? % "--") (flag-values %)) args)) ".")]
            (if sub (path/join d sub) d)))
(def max-age-days (js/parseFloat (str (flag "--max-age-days" 21))))
(def min-files (js/parseInt (str (flag "--min" 1)) 10))

(defn- walk [dir]
  (if-not (fs/existsSync dir)
    []
    (mapcat (fn [e]
              (let [p (path/join dir e)]
                (if (.isDirectory (fs/statSync p)) (walk p) [p])))
            (fs/readdirSync dir))))

(def manifests (vec (sort (filter #(str/ends-with? % ".manifest.edn") (walk root)))))

(defn- age-days [ms now] (/ (- now ms) 86400000.0))

(defn -main []
  (let [now (js/Date.now)
        rows (mapv (fn [p]
                     (let [m (try (reader/read-string (fs/readFileSync p "utf8"))
                                  (catch :default e {:read-error (.-message e)}))
                           fetched (:manifest/fetched-at m)]
                       (cond
                         (:read-error m) {:path p :bad (str "unreadable: " (:read-error m))}
                         (not (number? fetched)) {:path p :bad "no numeric :manifest/fetched-at"}
                         :else {:path p :age (age-days fetched now)
                                :source (:manifest/source m)
                                :count (:manifest/entity-count m)})))
                   manifests)]
    (println (str "SCANNED\t" (count rows) "\tmanifests under " root))
    (doseq [{:keys [path age bad source count]} rows]
      (println (str "  " (if bad "BAD  " (if (> age max-age-days) "STALE" "fresh"))
                    "\t" (if bad bad (str (.toFixed age 1) "d  n=" count "  " source))
                    "\t" path)))
    (let [stale (filter #(or (:bad %) (> (:age %) max-age-days)) rows)]
      (cond
        (< (count rows) min-files)
        (do (println (str "FAIL: found " (count rows) " manifests, floor is " min-files
                          " -- refusing to report freshness for a tree this small"))
            (js/process.exit 1))

        (seq stale)
        (do (println (str "FAIL: " (count stale) " of " (count rows)
                          " manifests are stale or unreadable (max " max-age-days " days)"))
            (js/process.exit 1))

        :else
        (println (str "OK: " (count rows) " manifests, oldest "
                      (.toFixed (apply max (map :age rows)) 1) "d, limit " max-age-days "d"))))))

(-main)
