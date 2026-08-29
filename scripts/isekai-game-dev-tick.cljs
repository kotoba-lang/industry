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
;;          {game.edn, content-ratings.edn の entry, thumbnail.svg} のどれかが無い
;;       2. new-game — public/benchmarks/catalog.edn の :samples で
;;          :status が :planned / :next のもの
;;       3. どちらも無ければ :no-candidates
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

(defn- registration-gaps
  "public/games/<ns>/<g>/ に居るのに登録が欠けているゲーム。
  content-ratings が読めなかったら nil（= 測れなかった。0 件ではない）。"
  []
  (let [games-dir (str repo-abs "/public/games")
        ratings (read-edn-file (str repo-abs "/resources/content-ratings.edn"))
        rated (some-> ratings :entries keys set)]
    (if (nil? rated)
      nil
      (when-let [nss (list-dirs games-dir)]
        (vec
         (for [ns- nss
               g (or (list-dirs (str games-dir "/" ns-)) [])
               :let [base (str games-dir "/" ns- "/" g)
                     missing (cond-> []
                               (not (exists? (str base "/game.edn"))) (conj :game.edn)
                               (not (contains? rated (str "/" ns- "/" g))) (conj :content-rating)
                               (not (exists? (str base "/thumbnail.svg"))) (conj :thumbnail.svg))]
               :when (seq missing)]
           {:kind :registration-gap :game (str ns- "/" g) :missing missing}))))))

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
          gaps (registration-gaps)
          planned (delay (planned-samples))]
      (cond
        ;; ratings が読めない = registration を測れない。planned だけで
        ;; 「候補あり/なし」を言うと、測れなかった面が clean の顔をする。
        (nil? gaps)
        (do (prn {:outcome :not-measured :why :content-ratings-unreadable
                  :freshness fr})
            (js/process.exit 2))

        (seq gaps)
        (do (prn {:outcome :candidate :kind :registration-gap
                  :candidate (first gaps) :all-gaps gaps
                  :freshness fr})
            (js/process.exit 0))

        (nil? @planned)
        (do (prn {:outcome :not-measured :why :benchmarks-catalog-unreadable
                  :freshness fr})
            (js/process.exit 2))

        (seq @planned)
        (do (prn {:outcome :candidate :kind :new-game
                  :candidate (first @planned) :all-planned @planned
                  :freshness fr})
            (js/process.exit 0))

        :else
        (do (prn {:outcome :no-candidates :freshness fr
                  :note "registration gap 0 件、planned sample 0 件"})
            (js/process.exit 0))))))

(-main)
