#!/usr/bin/env nbb
;; scripts/isekai-game-dev-tick.cljs — isekai.network のゲーム制作 bot の測定段。
;; **決定論。モデルは居ない。** 姉妹 `scripts/repo-bots/tick.cljs` /
;; `scripts/fleet-refactor-wave-tick.cljs` と同じ 2 段構えの 1 段目で、
;; `scripts/isekai-game-dev-loop.cljs` が毎周これを先に回す。
;;
;; ## 何を測るか（順に）
;;
;;   (a) `orgs/network-awai/network-isekai` が checkout されていて、
;;       HEAD が west pin と一致するか。pin が GitHub default branch tip から
;;       遅れていないかは `gh api .../compare/<pin>...HEAD` で測る。
;;       **API が答えなかったら :not-measured** —— 測れなかった鮮度を
;;       :fresh に畳まない（ADR-2608136000 の形）。
;;   (b) 候補。優先順:
;;       1. registration-gap — public/games/<ns>/<g>/ に居るのに
;;          {game.edn, content-ratings.edn の entry, thumbnail.svg} のどれかが無い。
;;          ただし deliberately-unlisted（オーナー判断で catalog 外のもの）は
;;          候補にせず :excluded として出力に見せる
;;       2. mobile-input-gap — catalog game なのに touch 入力が logic に届かない
;;          （logic.cljc が key-pressed? を読み、(axis …) を一度も読まない）。
;;          出荷済みの欠陥（実例: gftd/jintori が phone で操作不能、オーナー報告
;;          2026-08-29）なので planned より先
;;       3. new-game — public/benchmarks/catalog.edn の :samples で
;;          :status が :planned / :next のもの
;;       4. どれも無ければ :no-candidates
;;
;; ## 出力と exit
;;
;;   stdout 最終行に EDN を 1 行。
;;   exit 0 = :candidate または :no-candidates（EDN の :outcome で区別する）
;;   exit 2 = :not-measured（checkout が無い / catalog が読めない）。
;;            0 でも 1 でもない値 —— 「実行できなかった検査が、実行して問題が
;;            無かった検査と同じ値を返す」を作らない。
;;
;; 読むだけ。git の書き込みコマンドは 1 つも呼ばない。
;;
;; usage:
;;   nbb scripts/isekai-game-dev-tick.cljs

(ns isekai-game-dev-tick
  (:require [clojure.edn :as edn]
            [clojure.string :as str]))

(def fs (js/require "node:fs"))
(def os (js/require "node:os"))
(def cp (js/require "node:child_process"))

(def home (.homedir os))
(def root (or (aget (.-env js/process) "COM_JUNKAWASAKI_ROOT")
              (str home "/github/com-junkawasaki")))
(def repo-path "orgs/network-awai/network-isekai")
(def repo-abs (str root "/" repo-path))
(def gh-repo "network-awai/network-isekai")

(defn- sh [cmd args opts]
  (try
    (let [r (.spawnSync cp cmd (clj->js args)
                        (clj->js (merge {:encoding "utf8" :timeout 30000} opts)))]
      {:code (aget r "status") :out (str (aget r "stdout")) :err (str (aget r "stderr"))})
    (catch :default e {:code nil :out "" :err (str e)})))

(defn- exists? [p] (try (.existsSync fs p) (catch :default _ false)))

(defn- read-file [p] (try (.readFileSync fs p "utf8") (catch :default _ nil)))

(defn- read-edn-file [p]
  (try (edn/read-string (read-file p)) (catch :default _ nil)))

;; ---------------------------------------------------------------- (a) freshness

(defn- west-pin
  "manifest/west.yml から network-isekai の revision を読む。読めなければ nil。"
  []
  (when-let [yml (read-file (str root "/manifest/west.yml"))]
    (let [lines (str/split-lines yml)]
      (loop [ls lines in? false]
        (when-let [line (first ls)]
          (cond
            (re-find #"^    - name: network-isekai$" line) (recur (rest ls) true)
            (and in? (re-find #"^    - name: " line)) nil
            (and in? (re-find #"^      revision: (\S+)" line))
            (second (re-find #"^      revision: (\S+)" line))
            :else (recur (rest ls) in?)))))))

(defn- git-head []
  (let [{:keys [code out]} (sh "git" ["-C" repo-abs "rev-parse" "HEAD"] {})]
    (when (= 0 code) (str/trim out))))

(defn- pin-vs-tip
  "pin と upstream default branch tip の比較。gh が答えなければ :not-measured。
  :fresh と :not-measured は別の事実 —— 混ぜない。"
  [pin]
  (let [{:keys [code out err]}
        (sh "gh" ["api" (str "repos/" gh-repo "/compare/" pin "...HEAD")
                  "--jq" "{ahead_by: .ahead_by, behind_by: .behind_by, status: .status}"]
            {:timeout 20000})]
    (if (not= 0 code)
      {:verdict :not-measured
       :why (subs (str/trim (str err)) 0 (min 160 (count (str/trim (str err)))))}
      (let [m (try (js->clj (js/JSON.parse out) :keywordize-keys true)
                   (catch :default _ nil))]
        (cond
          (nil? m) {:verdict :not-measured :why "compare の応答が読めない"}
          (pos? (or (:ahead_by m) 0)) {:verdict :pin-lagging :ahead-by (:ahead_by m)
                                       :behind-by (:behind_by m) :status (:status m)}
          :else {:verdict :fresh :status (:status m)})))))

(defn- freshness []
  (let [pin (west-pin)
        head (git-head)]
    (cond
      (nil? head) {:verdict :not-measured :why "HEAD を解決できない"}
      (nil? pin)  {:verdict :not-measured :why "west.yml から pin が読めない"}
      :else
      (merge {:pin (subs pin 0 7) :head (subs head 0 7)
              :head=pin? (= pin head)}
             (pin-vs-tip pin)))))

;; ---------------------------------------------------------------- (b) candidates

(defn- list-dirs [p]
  (try
    (->> (.readdirSync fs p #js {:withFileTypes true})
         (filter #(.isDirectory %))
         (map #(.-name %))
         sort vec)
    (catch :default _ nil)))

(def deliberately-unlisted
  "public/games/ に居るが、**オーナー判断で catalog に載せていない**もの。
  登録漏れ（bot が塞ぐ対象）ではないので registration-gap から除外する。
  黙って消さない —— tick の出力に :excluded として毎回出す。

  - gftd/castlevania — Canvas 2D の standalone prototype。listed games が共有する
    3D kami.webgpu render-IR pipeline を通らないため、意図して game discovery の
    外に置かれている（根拠: network-isekai の shadow-cljs.edn の :castlevania
    build 冒頭コメント、~L48「NOT part of :app, NOT listed in game discovery」）
  - gftd/sekaiju — 2D 横スクロール MMO client（network-isekai ADR-0071）。
    castlevania と同じ形（独自 module / Canvas 2D / no WebGPU）で、意図して
    catalog の外（同じく shadow-cljs.edn の :sekaiju build コメントが明記）

  ここに足すときは、除外の根拠が**上流 repo に書かれている**ことを確かめてから。"
  #{"gftd/castlevania" "gftd/sekaiju"})

(defn- registration-gaps
  "public/games/<ns>/<g>/ に居るのに登録が欠けているゲーム。
  content-ratings が読めなかったら nil（= 測れなかった。0 件ではない）。
  deliberately-unlisted は候補にしないが、:excluded として持ち帰る。"
  []
  (let [games-dir (str repo-abs "/public/games")
        ratings (read-edn-file (str repo-abs "/resources/content-ratings.edn"))
        rated (some-> ratings :entries keys set)]
    (if (nil? rated)
      nil
      (when-let [nss (list-dirs games-dir)]
        (let [rows (for [ns- nss
                         g (or (list-dirs (str games-dir "/" ns-)) [])
                         :let [base (str games-dir "/" ns- "/" g)
                               id (str ns- "/" g)
                               missing (cond-> []
                                         (not (exists? (str base "/game.edn"))) (conj :game.edn)
                                         (not (contains? rated (str "/" id))) (conj :content-rating)
                                         (not (exists? (str base "/thumbnail.svg"))) (conj :thumbnail.svg))]
                         :when (seq missing)]
                     {:kind :registration-gap :game id :missing missing})]
          {:gaps (vec (remove #(deliberately-unlisted (:game %)) rows))
           ;; 除外は沈黙させない: 除外したものと、それが何を欠いているかを見せる。
           ;; 除外集合に居るのに欠けが 0 のもの（= いつか登録された）はここに
           ;; 出なくなるので、集合の陳腐化も出力から見える。
           :excluded (vec (filter #(deliberately-unlisted (:game %)) rows))})))))

(def owner-reported-defects
  "オーナーが名指しで報告した出荷済み欠陥。候補の並びで先頭に来る（検出は
  下の走査と同じ —— ここに書いても走査に当たらなければ候補にならない。
  直って走査から消えたら、この集合の entry は不活性になるので消してよい）。

  - gftd/jintori — 2026-08-29 オーナー報告「phone で操作不能」"
  #{"gftd/jintori"})

(defn- mobile-input-gaps
  "touch 入力が logic に届かない catalog game。

  判定は決定論の 2 条件: logic.cljc が `key-pressed?` を読み、かつ
  `(axis ` を一度も読まない。kami.input（orgs/kotoba-lang/host の
  src/kami/input.cljc）の pointer 経路は **axes しか出さず、actions は
  keyboard event からしか発火しない**ので、axis を読まない key-pressed?
  だけの logic には touch 操作が構造的に届かない。

  ⚠ 「scene に :sticks が無い」を条件にしない。kami.input は scene の
  :axes に MoveX と MoveY が居れば **全面 1 本の default stick** を与える
  （input.cljc の `sticks` / `default-stick`）ので、:sticks 無しでも
  `(axis \"MoveX\")` を読む logic（実測: gftd/palisade, gftd/petit-forro）は
  phone で動いている。そこを候補にすると、動いている game を『直し』に
  モデルを起こすことになる。scene の :sticks 有無は fixer の参考として
  行に載せるだけ。

  content-ratings に依存しないので registration-gaps とは独立に測れる。
  games dir が読めなければ nil（= 測れなかった。0 件ではない）。"
  []
  (let [games-dir (str repo-abs "/public/games")]
    (when-let [nss (list-dirs games-dir)]
      (->> (for [ns- nss
                 g (or (list-dirs (str games-dir "/" ns-)) [])
                 :let [id (str ns- "/" g)
                       base (str games-dir "/" ns- "/" g)
                       logic (read-file (str base "/logic.cljc"))
                       scene (read-file (str base "/scene.edn"))]
                 :when (and (exists? (str base "/game.edn"))
                            (not (deliberately-unlisted id))
                            logic
                            (str/includes? logic "key-pressed?")
                            (not (str/includes? logic "(axis ")))]
             {:kind :mobile-input-gap :game id
              :sticks-declared? (boolean (and scene (str/includes? scene ":sticks")))
              :owner-reported? (contains? owner-reported-defects id)})
           ;; オーナー報告の欠陥が先頭、あとはアルファベット順。
           (sort-by (fn [m] [(if (:owner-reported? m) 0 1) (:game m)]))
           vec))))

(defn- planned-samples
  "catalog.edn の :samples で :status :planned / :next。読めなければ nil。"
  []
  (when-let [cat (read-edn-file (str repo-abs "/public/benchmarks/catalog.edn"))]
    (->> (:samples cat)
         (filter #(#{:planned :next} (:status %)))
         ;; :next は「次はこれ」という catalog 自身の宣言なので先頭に。
         (sort-by #(if (= :next (:status %)) 0 1))
         (mapv #(select-keys % [:id :dimension :genre :status :template :limit])))))

;; ---------------------------------------------------------------- main

(defn -main []
  (cond
    (not (exists? repo-abs))
    (do (prn {:outcome :not-measured :why :checkout-missing :repo repo-path})
        (js/process.exit 2))

    (not (exists? (str repo-abs "/.git")))
    (do (prn {:outcome :not-measured :why :not-a-git-repo :repo repo-path})
        (js/process.exit 2))

    :else
    (let [fr (freshness)
          reg (registration-gaps)
          gaps (:gaps reg)
          excluded (:excluded reg)
          migs (mobile-input-gaps)
          planned (delay (planned-samples))]
      (cond
        ;; ratings が読めない = registration を測れない。planned だけで
        ;; 「候補あり/なし」を言うと、測れなかった面が clean の顔をする。
        (nil? reg)
        (do (prn {:outcome :not-measured :why :content-ratings-unreadable
                  :freshness fr})
            (js/process.exit 2))

        (seq gaps)
        (do (prn (cond-> {:outcome :candidate :kind :registration-gap
                          :candidate (first gaps) :all-gaps gaps
                          :freshness fr}
                   (seq excluded) (assoc :excluded excluded)))
            (js/process.exit 0))

        (seq migs)
        (do (prn (cond-> {:outcome :candidate :kind :mobile-input-gap
                          :candidate (first migs) :all-mobile-input-gaps migs
                          :freshness fr}
                   (seq excluded) (assoc :excluded excluded)))
            (js/process.exit 0))

        (nil? @planned)
        (do (prn (cond-> {:outcome :not-measured :why :benchmarks-catalog-unreadable
                          :freshness fr}
                   (seq excluded) (assoc :excluded excluded)))
            (js/process.exit 2))

        (seq @planned)
        (do (prn (cond-> {:outcome :candidate :kind :new-game
                          :candidate (first @planned) :all-planned @planned
                          :freshness fr}
                   (seq excluded) (assoc :excluded excluded)))
            (js/process.exit 0))

        :else
        (do (prn (cond-> {:outcome :no-candidates :freshness fr
                          :note "registration gap 0 件、planned sample 0 件"}
                   (seq excluded) (assoc :excluded excluded)))
            (js/process.exit 0))))))

(-main)
