;; tos-fetch-j.cljs — ISIC J（情報通信）の公開 ToS を取得する。ADR-2608082100。
;;
;; ⚠ 取得した本文は commit しない（第三者の著作物）。commit するのは
;;   対象一覧（URL つき）・取得コード・派生した検出結果だけ。本文が要るなら
;;   これを実行して再取得する。
;;
;; usage: nbb 90-docs/system-dynamics/tos-fetch-j.cljs <出力ディレクトリ>

(require '[clojure.string :as str])
(def fs (js/require "fs"))
(def cp (js/require "child_process"))
(def out (or (second (drop 2 (js->clj (.-argv js/process)))) "/tmp/tos-j"))
(def list-file (str (.dirname (js/require "path") (nth (js->clj (.-argv js/process)) 1)) "/tos-j-targets.tsv"))

(defn strip-html [s]
  (-> s
      (str/replace #"(?is)<(script|style|noscript)[^>]*>.*?</\1>" " ")
      (str/replace #"(?s)<[^>]+>" " ")
      (str/replace #"&amp;" "&") (str/replace #"&lt;" "<") (str/replace #"&gt;" ">")
      (str/replace #"&(nbsp|#160);" " ") (str/replace #"&quot;" "\"") (str/replace #"&#39;" "'")
      (str/replace #"\s+" " ")))

(.mkdirSync fs out #js{:recursive true})
(def rows (->> (str/split-lines (.readFileSync fs list-file "utf8"))
               (remove str/blank?) (remove #(str/starts-with? % "#"))
               (map #(str/split % #"\t"))))
(println "対象:" (count rows) "社")
(doseq [[nm isic url] rows]
  (let [f (str out "/" (str/replace nm " " "_") ".txt")
        r (try (.execSync cp (str "curl -sL --max-time 25 -A 'Mozilla/5.0 (research; contract-terms study)' -w '\\n%{http_code}' " (pr-str url))
                          #js{:encoding "utf8" :maxBuffer 20000000})
               (catch :default _ nil))]
    (if r
      (let [i (str/last-index-of r "\n")
            code (str/trim (subs r (inc i)))
            body (strip-html (subs r 0 i))]
        (if (and (= code "200") (> (count body) 3000))
          (do (.writeFileSync fs f body) (println "OK  " nm isic (count body) "bytes"))
          (println "SKIP" nm isic "http=" code "bytes=" (count body))))
      (println "FAIL" nm isic))))
