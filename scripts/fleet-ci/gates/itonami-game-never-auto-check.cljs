#!/usr/bin/env nbb
;; itonami-game-never-auto-check.cljs — 「クリーニング営み」の主張を機械検査する。
;;
;; 対象: `60-apps/network-isekai/public/games/itonami/isic-9601/`
;;
;; ## なぜこの gate が要るか
;;
;; このゲームの主張は 1 行に尽きる: **cloud-itonami の governed actor はどれも、
;; どの phase の `:auto` 集合にも入れない操作を最低 1 つ永久に持っている。**
;; 放置ゲーは「全部自動化する」ゲームなので、その 1 つが自動化されないことが
;; そのまま遊びになっている。
;;
;; **これは prose では守れない。** 数字は 8 つの別 repo
;; (cloud-itonami-isic-{9601,4520,9609,8121,8129,3700,3811,3900}) の
;; `phase.cljc` / `governor.cljc` から 2026-08-08 に読んで `world.cljc` に
;; 転写したもので、転写元と転写先は別 repo・別 owner。バランス調整のつもりで
;; `:auto-at-3` に 1 語足す、`:never-auto` を空にする、`phase-table` の
;; `:auto` に `:clean` を入れる —— どれも**ゲームは普通に動き続ける**。
;; 壊れるのは主張だけで、動作は壊れないので、遊んでも気づけない。
;;
;; repo 側のテスト (`test/logic_test.cljs` / `test/world3d_test.clj`) は同じ
;; 不変条件を見ているが、**誰も回していない** —— GitHub Actions は撤去済み
;; (ADR-2607300900) で、fleet gate に載って初めて毎 tip で走る。
;;
;; ## 検査する不変条件
;;
;;   1. `district.cljc` の phase 3 の `:auto` は `:auto-at-3` から導出されており、
;;      never-auto の op はそこに入らない（`:hard-human?` は `:never-auto` から導出）
;;   2. `district.cljc` が `:auto` を推測していない —— `:auto-at-3` 以外から作らない
;;   3. `world.cljc` の各 district の `:never-auto` が空でない
;;   4. 各 district の `:never-auto` の op が、その district 自身の `:auto-at-3` に無い
;;   5. district 数と、街全体の never-auto op 総数が申告どおり
;;   6. 各 district が実在の repo 名 (`cloud-itonami-isic-<code>`) を名乗る
;;
;; 2026-08-08 の kaizen で board が district 駆動になり、phase table は
;; `logic.cljc` のリテラルから `district.cljc` の導出へ移った。**gate は不変条件を
;; 追いかける** —— 元の場所を見続ければ「見つからない」で落ちるか、もっと悪いことに
;; 「見つかったものが空だから合格」になる。ここは前者で落ちた（expected 4 phases,
;; found 0）ので気づけた。
;;
;; ## 落ちることの確認（2026-08-08、移設後に取り直し）
;;
;; 無改変の tree で exit 0、以下 7 通りでいずれも exit 1:
;;
;;   A `:auto auto-keys` → `:auto (set keys')`      phase 3 が writes 集合をそのまま自動化
;;   B `:hard-human?` を `false` 固定               人手必須の工程が消える
;;   C `auto3` を `:auto-at-3` でなく `:ops` から   自動化範囲をゲームが発明する
;;   D `never` を `#{}` 固定                        never-auto の導出そのものを切る
;;   E ある district の `:never-auto` を空に         (isic-3900)
;;   F never-auto の op を同じ district の `:auto-at-3` にも書く  (isic-4520)
;;   G district が実在しない repo 名を名乗る        (isic-3811)
;;
;; **この確認をするときは、壊す置換が実際に当たったことを先に見ること。**
;; A と F は最初 exit 0 で「gate に穴がある」ように見えたが、実際は sed/perl が
;; 一致していなかっただけで、ファイルは無改変のまま合格していた。**当たらなかった
;; break と、素通しの gate は、出力が完全に同じ。** 置換後の grep で差分を確認して
;; から gate を回す。
;;
;; ネットワーク: 不要。tree だけを読む。
;;
;; 使い方: nbb itonami-game-never-auto-check.cljs <repo-tree>

(ns itonami-game-never-auto-check
  (:require ["node:fs" :as fs]
            ["node:path" :as path]
            [clojure.string :as str]))

(def root (or (first *command-line-args*) "."))
(def game-dir "60-apps/network-isekai/public/games/itonami/isic-9601/src/itonami/isic_9601")

(def failures (atom []))
(defn fail! [& xs] (swap! failures conj (str/join " " (map str xs))))

(defn- slurp* [rel]
  (let [p (path/join root game-dir rel)]
    (when-not (fs/existsSync p)
      (fail! "missing" (str game-dir "/" rel))
      nil)
    (when (fs/existsSync p) (fs/readFileSync p "utf8"))))

;; --------------------------------------------------------------------------
;; The sources are read as TEXT, not loaded.
;;
;; Loading them would need the game's own classpath inside a gate that only gets a file
;; tree, and — more to the point — a check that evaluates the thing it checks can be
;; satisfied by a file that computes the right answer at runtime. The invariant here is
;; about what is WRITTEN DOWN, so the gate reads what is written down.
;; --------------------------------------------------------------------------

(defn- section
  "The text between `start` and the first `end` after it, or nil."
  [s start end]
  (when-let [i (str/index-of s start)]
    (let [rest' (subs s i)
          j (str/index-of rest' end)]
      (when j (subs rest' 0 j)))))

(defn- auto-sets
  "Every `:auto #{...}` in the phase table, as a vector of sets of op names."
  [phase-text]
  (mapv (fn [m] (set (map (fn [w] (subs w 1))
                          (re-seq #":[a-z0-9-]+" (nth m 1)))))
        (re-seq #":auto\s+#\{([^}]*)\}" phase-text)))

(when-let [d (slurp* "district.cljc")]
  ;; The board's phase table is built in `spec`. Two things must hold in that source:
  ;; `:auto` comes from `auto-keys`, and `auto-keys` comes from `:auto-at-3`. Anything
  ;; else — a literal set, a union, the writes set — would widen what runs unattended.
  (let [spec-text (or (section d "(defn spec" "(def playable") d)]
    (when-not (str/includes? spec-text ":auto auto-keys")
      (fail! "district.cljc: phase 3 ':auto' is not `auto-keys` — the one set that decides"
             "what runs with nobody watching must not be built any other way"))
    (when-not (str/includes? spec-text "auto3 (set (map (fn [o] (str o)) (:auto-at-3 d)))")
      (fail! "district.cljc: `auto3` is no longer read from :auto-at-3 — the phase-3 auto"
             "set would then be the game's invention rather than the repo's"))
    (when-not (str/includes? spec-text ":hard-human? (contains? never (str op))")
      (fail! "district.cljc: ':hard-human?' is no longer derived from :never-auto — a"
             "station could then be automatable while the repo says it is not"))
    (when-not (str/includes? spec-text "never (set (map (fn [o] (str o)) (:never-auto d)))")
      (fail! "district.cljc: `never` is no longer read from :never-auto"))))

;; --------------------------------------------------------------------------

(defn- districts
  "Each district entry as `{:id :repo :never-auto #{} :auto3 #{}}`, parsed from the text."
  [world]
  (let [chunks (rest (str/split world #"\{:id \""))]
    (mapv (fn [c]
            (let [id (first (str/split c #"\""))
                  grab (fn [k]
                         (when-let [m (re-find (re-pattern (str k "\\s+\\[([^\\]]*)\\]")) c)]
                           (set (map (fn [w] (subs w 1)) (re-seq #":[a-z0-9/-]+" (nth m 1))))))]
              {:id id
               :repo (second (re-find #":repo \"([^\"]+)\"" c))
               :never-auto (grab ":never-auto")
               :auto3 (grab ":auto-at-3")
               :why (second (re-find #":never-auto-why \"([^\"]*)\"" c))}))
          chunks)))

(when-let [world (slurp* "world.cljc")]
  (let [ds (districts world)]
    (if (empty? ds)
      (fail! "world.cljc: no districts parsed — the gate cannot see the invariant")
      (do
        (when (not= 8 (count ds))
          (fail! "world.cljc: expected 8 districts, found" (count ds)))
        (doseq [d ds]
          (when-not (and (:repo d) (re-matches #"cloud-itonami-isic-\d+" (:repo d)))
            (fail! (:id d) "does not name a cloud-itonami-isic-* repo:" (pr-str (:repo d))))
          (when (empty? (:never-auto d))
            (fail! (:id d) "has no :never-auto op — every actor in this fleet keeps at"
                   "least one operation permanently un-automatable; a district that"
                   "claims otherwise is a district whose repo was misread"))
          (when (str/blank? (str (:why d)))
            (fail! (:id d) "does not say WHY its op never automates"))
          (doseq [op (:never-auto d)]
            (when (contains? (:auto3 d) op)
              (fail! (:id d) "lists" (str ":" op)
                     "as never-auto AND in its own phase-3 :auto set — one of the two"
                     "was edited without the other"))))
        (let [total (reduce + 0 (map (fn [d] (count (:never-auto d))) ds))]
          (println (str "  districts " (count ds)
                        "  never-auto ops " total
                        "  (9601 contributes 2, the rest 1 each)"))
          (when (not= 9 total)
            (fail! "the street declares" total "never-auto ops; 9 were read from the"
                   "eight repos on 2026-08-08 — a change here means a repo was re-read"
                   "(update world/district-evidence) or a transcription was lost")))))))

;; --------------------------------------------------------------------------

(if (seq @failures)
  (do (println "FAIL itonami-game-never-auto")
      (doseq [f @failures] (println "  -" f))
      (js/process.exit 1))
  (println "OK itonami-game-never-auto"))
