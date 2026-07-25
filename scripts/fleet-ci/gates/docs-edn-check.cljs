#!/usr/bin/env nbb
;; docs-edn-check.cljs — EDN-only ドキュメント repo の gate。
;;
;; 展開済みの repo tree を受け取り、tree 内の全 *.edn が
;; `(edn/read-string (slurp f))` 可能であることを検証する（90-docs の EDN-only
;; 方針 ADR-2607171600 と同じ不変条件を、CI として repo 側にも課す）。
;;
;; ノード側で `npx nbb docs-edn-check.cljs <dir>` として実行される
;; （tick.cljs が heredoc でノードに配って呼ぶ。JVM を要求しない gate）。
;;
;; false-pass 対策: 検出ファイル数が --min 未満なら FAIL する。tarball の
;; 展開ミス（ADR-2607178000 addendum の --strip-components 事故と同型）で
;; 空ディレクトリを検査して「0 件 = 全部有効」と報告する事故を防ぐ。
(ns fleet-ci.gates.docs-edn-check
  (:require ["node:fs" :as fs]
            ["node:path" :as path]
            [cljs.reader :as reader]
            [clojure.string :as str]))

(def args (vec *command-line-args*))
(def root (or (first (remove #(str/starts-with? % "--") args)) "."))
(def min-files
  (let [i (.indexOf args "--min")]
    (if (neg? i) 50 (js/parseInt (nth args (inc i)) 10))))

(def skip-dirs #{"node_modules" ".git" "archive" "dist" "target" ".shadow-cljs"})

(defn edn-files [dir]
  (let [out (atom [])]
    ((fn walk [d]
       (doseq [e (fs/readdirSync d #js {:withFileTypes true})]
         (let [n (.-name e) p (path/join d n)]
           (cond
             (.isDirectory e) (when-not (contains? skip-dirs n) (walk p))
             (str/ends-with? n ".edn") (swap! out conj p)))))
     dir)
    @out))

(let [files (edn-files root)
      bad (atom [])]
  (doseq [f files]
    (try
      (let [s (str (fs/readFileSync f "utf8"))]
        ;; 空ファイル・コメントのみは read-string が nil を返すのが正常。
        (reader/read-string (str "[" s "]")))
      (catch :default e
        (swap! bad conj [f (ex-message e)]))))
  (println "edn files:" (count files) "unparsable:" (count @bad))
  (doseq [[f msg] (take 20 @bad)]
    (println "  FAIL" (path/relative root f) "—" msg))
  (cond
    (< (count files) min-files)
    (do (println "FLEET-CI: only" (count files) "edn files found (<" min-files
                 ") — extraction or path is wrong, refusing to report pass")
        (js/process.exit 90))
    (seq @bad) (js/process.exit 1)
    :else (println "OK — all" (count files) "edn files parse")))
