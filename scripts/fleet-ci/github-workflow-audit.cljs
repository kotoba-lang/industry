#!/usr/bin/env nbb
;; github-workflow-audit.cljs — workspace 中の .github/workflows/*.yml を全部
;; パースして受理判定にかけ、**fleet でそのまま実行できるものが何本あるか**を
;; 実測する。
;;
;; 設計判断のための計器であって gate ではない。「既存 workflow をそのまま
;; murakumo で使えるか」に対して、感想ではなく数で答えるために置く。
;;
;;   nbb --classpath scripts/fleet-ci scripts/fleet-ci/github-workflow-audit.cljs
;;   ... --root orgs/kotoba-lang        # 範囲を絞る
;;   ... --show-unsupported 40          # 落ちた理由の内訳を出す
(ns fleet-ci.github-workflow-audit
  (:require ["node:fs" :as fs]
            ["node:path" :as path]
            [clojure.string :as str]
            [github-workflow-run :as gw]))

(def args (vec *command-line-args*))
(defn opt [f d] (let [i (.indexOf args f)] (if (neg? i) d (nth args (inc i) d))))

(def root (opt "--root" "orgs"))
(def show-n (js/parseInt (str (opt "--show-unsupported" "25")) 10))

(defn workflow-files [dir]
  (let [out (atom [])]
    ((fn walk [d depth]
       (when (< depth 6)
         (doseq [e (try (fs/readdirSync d #js {:withFileTypes true}) (catch :default _ []))]
           (let [n (.-name e) p (path/join d n)]
             (cond
               (and (.isDirectory e) (not= n "node_modules") (not= n ".git"))
               (walk p (inc depth))
               (and (.isFile e)
                    (str/includes? p "/.github/workflows/")
                    (or (str/ends-with? n ".yml") (str/ends-with? n ".yaml")))
               (swap! out conj p))))))
     dir 0)
    @out))

(defn -main []
  (let [files (workflow-files root)
        results
        (doall
         (for [f files]
           (let [text (str (fs/readFileSync f "utf8"))]
             (try
               (let [wf (gw/parse-yaml text)]
                 (if-not (get wf "jobs")
                   {:file f :outcome :no-jobs}
                   (let [plan (gw/analyze wf)]
                     (if (= :runnable (:verdict plan))
                       ;; runnable と言うからには bash まで出せることを確かめる。
                       ;; 出せないなら runnable ではない。
                       (try
                         (let [sh (gw/to-bash plan {})]
                           {:file f :outcome :runnable :bytes (count sh)
                            :jobs (count (:jobs plan))})
                         (catch :default e
                           {:file f :outcome :unsupported
                            :reasons [(str "emit: " (.-message e))]}))
                       {:file f :outcome :unsupported :reasons (:reasons plan)}))))
               (catch :default e
                 {:file f :outcome :unparsable :error (.-message e)})))))
        by (group-by :outcome results)
        n (count results)
        pct #(if (zero? n) 0 (js/Math.round (* 100 (/ % n))))]
    (println (str "workflow files under " root ": " n))
    (doseq [k [:runnable :unsupported :unparsable :no-jobs]]
      (println (str "  " (name k) ": " (count (get by k)) " (" (pct (count (get by k))) "%)")))
    (println)
    (println "why unsupported (top reasons):")
    (doseq [[reason c] (->> (mapcat :reasons (get by :unsupported))
                            ;; job 名は理由の本体ではないので落として集計する
                            (map #(str/replace % #"^[^:]+: " ""))
                            frequencies
                            (sort-by (comp - val))
                            (take show-n))]
      (println (str "  " c "  " reason)))
    (when (seq (get by :unparsable))
      (println)
      (println "parser gaps (these are NOT counted as supported):")
      (doseq [[e c] (->> (map :error (get by :unparsable)) frequencies
                         (sort-by (comp - val)) (take 10))]
        (println (str "  " c "  " e))))
    (println)
    (println "sample runnable:")
    (doseq [r (take 8 (get by :runnable))]
      (println (str "  " (:file r) "  (" (:jobs r) " job(s), " (:bytes r) "B bash)")))))

(-main)
