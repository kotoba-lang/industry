#!/usr/bin/env nbb
;; scripts/kami-lib-update-tick.cljs — kami family + core render/game lib の
;; **pin 鮮度**を測る段。決定論。モデルは居ない。
;; `scripts/kami-lib-update-loop.cljs` が毎周これを先に回す。
;;
;; ## 何を測るか
;;
;; west.yml の entry のうち、名前が `^kami` のもの + core lib
;; {webgpu webgl host scene2d sprite2d render game dance bonsai}（kotoba-lang org）
;; について、west pin と upstream default branch tip を
;; `gh api repos/<org>/<repo>/compare/<pin>...HEAD` で比べる。
;; ahead_by > 0（pin から見て tip が先）= pin が遅れている = 候補。
;;
;; CLAUDE.md 2026-08-20 のオーナー規則「pin の既定状態は upstream default branch の
;; tip」—— 遅れた pin は平常ではなく是正対象。
;;
;; ## 有界であること
;;
;; 1 tick で見るのは最大 30 repo。どこまで見たかは
;; `~/.itonami/kami-lib-update/cursor.edn` の round-robin cursor が持ち、
;; 次の tick は続きから見る（全 100+ repo を毎周歩かない）。
;;
;; ## :not-measured を :fresh に畳まない
;;
;; API error / rate limit は**その repo について測れなかった**のであって
;; 「遅れていない」ではない。2026-08-12 の advance-pins の実測（rate-limited な
;; lookup が『することが無い』に潰れた）を繰り返さない:
;;   - 各 repo の失敗は :not-measured 列に理由つきで出す
;;   - 1 repo も測れなかった tick は exit 2（0 でも 1 でもない値）
;;   - 連続 5 回失敗したら（rate limit の顔）そこで止め、残りは未測定として
;;     cursor を進めない
;;
;; ## 出力と exit
;;
;;   stdout 最終行に EDN を 1 行。
;;   exit 0 = :candidate（:lagging が 1 件以上）または :no-candidates
;;   exit 2 = :not-measured（1 repo も測れなかった）
;;
;; 読むだけ + cursor 書き込みのみ。git / west.yml への書き込みは無い。
;;
;; usage:
;;   nbb scripts/kami-lib-update-tick.cljs [--limit N]

(ns kami-lib-update-tick
  (:require [clojure.edn :as edn]
            [clojure.string :as str]))

(def fs (js/require "node:fs"))
(def os (js/require "node:os"))
(def cp (js/require "node:child_process"))

(def argv (vec *command-line-args*))
(defn- opt [n] (let [i (.indexOf argv n)]
                 (when (and (>= i 0) (< (inc i) (count argv))) (nth argv (inc i)))))

(def home (.homedir os))
(def root (or (aget (.-env js/process) "COM_JUNKAWASAKI_ROOT")
              (str home "/github/com-junkawasaki")))
(def state-dir (str home "/.itonami/kami-lib-update"))
(def cursor-file (str state-dir "/cursor.edn"))
(def batch-limit (max 1 (js/parseInt (or (opt "--limit") "30") 10)))

(def core-libs #{"webgpu" "webgl" "host" "scene2d" "sprite2d" "render" "game" "dance" "bonsai"})

(defn- sh [cmd args opts]
  (try
    (let [r (.spawnSync cp cmd (clj->js args)
                        (clj->js (merge {:encoding "utf8" :timeout 20000} opts)))]
      {:code (aget r "status") :out (str (aget r "stdout")) :err (str (aget r "stderr"))})
    (catch :default e {:code nil :out "" :err (str e)})))

;; ---------------------------------------------------------------- west.yml scope

(defn- west-entries
  "west.yml の project entry を {:name :remote :revision :groups} の列で。
  読めなければ nil（空とは別）。"
  []
  (try
    (let [lines (str/split-lines (.readFileSync fs (str root "/manifest/west.yml") "utf8"))
          flush (fn [out cur] (if (and cur (:name cur)) (conj out cur) out))]
      (loop [ls lines cur nil out []]
        (if-let [line (first ls)]
          (cond
            (re-find #"^    - name: (\S+)$" line)
            (recur (rest ls) {:name (second (re-find #"^    - name: (\S+)$" line))} (flush out cur))
            (and cur (re-find #"^      remote: (\S+)" line))
            (recur (rest ls) (assoc cur :remote (second (re-find #"^      remote: (\S+)" line))) out)
            (and cur (re-find #"^      revision: (\S+)" line))
            (recur (rest ls) (assoc cur :revision (second (re-find #"^      revision: (\S+)" line))) out)
            (and cur (re-find #"^      groups: \[(.*)\]" line))
            (recur (rest ls)
                   (assoc cur :groups (mapv str/trim (str/split (second (re-find #"^      groups: \[(.*)\]" line)) #",")))
                   out)
            :else (recur (rest ls) cur out))
          (flush out cur))))
    (catch :default _ nil)))

(defn- in-scope? [{:keys [name remote groups]}]
  (and (not (some #{"archived" "datalad"} (or groups [])))
       (or (str/starts-with? (str name) "kami")
           (and (contains? core-libs (str name)) (= remote "kotoba-lang")))))

;; ---------------------------------------------------------------- cursor

(defn- read-cursor []
  (or (try (edn/read-string (.readFileSync fs cursor-file "utf8")) (catch :default _ nil))
      {:index 0}))

(defn- write-cursor! [m]
  (try
    (.mkdirSync fs state-dir #js {:recursive true})
    (let [tmp (str cursor-file ".tmp")]
      (.writeFileSync fs tmp (str (pr-str m) "\n"))
      (.renameSync fs tmp cursor-file))
    (catch :default _ nil)))

;; ---------------------------------------------------------------- compare

(defn- compare-pin
  "pin vs upstream default branch tip。gh が答えなければ {:verdict :not-measured}。"
  [{:keys [name remote revision]}]
  (if (str/blank? (str revision))
    {:entry name :org remote :verdict :not-measured :why "west.yml に revision が無い"}
    (let [{:keys [code out err]}
          (sh "gh" ["api" (str "repos/" remote "/" name "/compare/" revision "...HEAD")
                    "--jq" "{ahead_by: .ahead_by, behind_by: .behind_by, status: .status}"]
              {})]
      (if (not= 0 code)
        {:entry name :org remote :verdict :not-measured
         :why (let [e (str/trim (str err))] (subs e 0 (min 160 (count e))))}
        (let [m (try (js->clj (js/JSON.parse out) :keywordize-keys true)
                     (catch :default _ nil))]
          (cond
            (nil? m)
            {:entry name :org remote :verdict :not-measured :why "compare の応答が読めない"}
            (pos? (or (:ahead_by m) 0))
            {:entry name :org remote :verdict :lagging :pin (subs (str revision) 0 7)
             :ahead-by (:ahead_by m) :behind-by (:behind_by m) :status (:status m)}
            :else
            {:entry name :org remote :verdict :fresh}))))))

;; ---------------------------------------------------------------- main

(defn -main []
  (let [entries (west-entries)]
    (if (nil? entries)
      (do (prn {:outcome :not-measured :why :west-yml-unreadable})
          (js/process.exit 2))
      (let [scope (->> entries (filter in-scope?) (sort-by :name) vec)
            n (count scope)]
        (if (zero? n)
          ;; scope が空なのは「全部 fresh」ではなく「測る対象を特定できなかった」。
          (do (prn {:outcome :not-measured :why :scope-empty})
              (js/process.exit 2))
          (let [start (mod (:index (read-cursor) 0) n)
                take-n (min batch-limit n)
                batch (->> (concat (subvec scope start) (subvec scope 0 start))
                           (take take-n))
                ;; 連続失敗 5 回（rate limit の顔）で打ち切る。打ち切った分は
                ;; 「見ていない」のであって「fresh」ではない —— cursor も進めない。
                results (loop [bs (seq batch) acc [] streak 0]
                          (if (and bs (< streak 5))
                            (let [r (compare-pin (first bs))]
                              (recur (next bs) (conj acc r)
                                     (if (= :not-measured (:verdict r)) (inc streak) 0)))
                            acc))
                attempted (count results)
                aborted? (< attempted (count batch))
                lagging (filterv #(= :lagging (:verdict %)) results)
                not-measured (filterv #(= :not-measured (:verdict %)) results)
                fresh-n (count (filter #(= :fresh (:verdict %)) results))
                measured-n (+ fresh-n (count lagging))]
            (write-cursor! {:index (mod (+ start attempted) n)
                            :at (.toISOString (js/Date.))
                            :scope-total n})
            (let [base {:checked attempted
                        :scope-total n
                        :cursor {:from start :to (mod (+ start attempted) n)}
                        :fresh fresh-n
                        :lagging lagging
                        :not-measured not-measured
                        :aborted-early? aborted?}]
              (cond
                (zero? measured-n)
                ;; 1 repo も測れなかった。「遅れ 0 件」の顔をさせない。
                (do (prn (assoc base :outcome :not-measured))
                    (js/process.exit 2))

                (seq lagging)
                (do (prn (assoc base :outcome :candidate))
                    (js/process.exit 0))

                :else
                (do (prn (cond-> (assoc base :outcome :no-candidates)
                           (seq not-measured)
                           (assoc :note (str (count not-measured)
                                             " repo は測れなかった（fresh とは数えていない）"))))
                    (js/process.exit 0))))))))))

(-main)
