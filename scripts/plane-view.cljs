#!/usr/bin/env nbb
;; plane-view.cljs — 事前計算した view を読むだけの最小スクリプト。
;;
;; `manifest/edn-query.cljs view <name>` と答えは同じ。違うのは**何をコンパイル
;; するか**で、実測 2026-08-25:
;;
;;   nbb の起動そのもの                    288 ms
;;   edn-query.cljs 経由の view            589 ms
;;   このスクリプト経由の view             (下の測定を参照)
;;
;; 差は edn-query.cljs（約 3,000 行、datascript と 40 近い loader を含む）を
;; nbb が毎回コンパイルする費用である。**29 KB の EDN を読むのに面の実装は要らない。**
;;
;; view を作るのは refresh の仕事（`manifest/edn-query.cljs refresh`）。ここは読むだけ。
;;
;; Run:
;;   nbb scripts/plane-view.cljs <name>
;;   nbb scripts/plane-view.cljs --list

(ns plane-view
  (:require ["fs" :as fs]
            ["child_process" :as cp]
            [clojure.string :as str]
            [cljs.reader :as edn]))

(def args (vec *command-line-args*))
(def root (str/trim (.toString (.execSync cp "git rev-parse --show-toplevel"))))
(def views-path (str root "/.projection-cache/edn-query-views.edn"))

(defn -main []
  (if-not (.existsSync fs views-path)
    (do (js/console.error
         (str "plane-view: no materialised views at " views-path "\n"
              ;; classpath は 4 項目。`.` と `scripts/nbb_compat` だけを渡すと
              ;; `Could not find namespace: datalog.core` で落ちる —— datalog
              ;; backend は west 管理の orgs/kotoba-lang/datalog に在り、それが
              ;; datom.source を require する。正本は edn-query.cljs の
              ;; `classpath` var（実測 2026-09-06、ここは 2 項目を印字していた）。
              "  build them: nbb --classpath \".:scripts/nbb_compat"
              ":orgs/kotoba-lang/datalog/src:orgs/kotoba-lang/datom-source/src\" "
              "manifest/edn-query.cljs refresh"))
        (.exit js/process 4))
    (let [{:keys [views built-at]} (edn/read-string (.readFileSync fs views-path "utf8"))
          nm (first args)]
      (cond
        (or (nil? nm) (= "--list" nm))
        (do (js/console.error (str "plane-view: built " built-at))
            (doseq [[k v] (sort-by (fn [[k _]] (str k)) views)]
              (println (str (if (keyword? k) (name k) (str k))
                            "\t" (count (:rows v)) " rows"))))

        :else
        (if-let [v (or (get views (keyword nm)) (get views nm))]
          (do (js/console.error (str "plane-view: " nm " built " built-at))
              (println (pr-str (:rows v))))
          (do (js/console.error
               (str "plane-view: no such view: " nm " (have: "
                    (str/join ", " (map (fn [k] (if (keyword? k) (name k) (str k)))
                                        (keys views)))
                    ")"))
              (.exit js/process 4)))))))

(-main)
