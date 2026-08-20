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

(defn- flag [name default]
  (let [i (.indexOf args name)]
    (if (neg? i) default (nth args (inc i)))))

;; tick.cljs は常に「展開ディレクトリ」を第1引数で渡すので、検査範囲を狭めたい
;; ときはここで受ける。superproject のように .edn が repo 全体に散っていて、
;; 不変条件（EDN-only / parse 可能）が課されているのは一部の plane だけ、という
;; 対象のための口。範囲外に古い壊れた EDN があっても、規約が効いている plane の
;; 回帰を検出できる。
(def sub (flag "--sub" nil))
(def root (let [d (or (first (remove #(str/starts-with? % "--")
                                     (remove (set [(flag "--min" nil) (or sub "")]) args)))
                      ".")]
            (if sub (path/join d sub) d)))
(def min-files (js/parseInt (str (flag "--min" 50)) 10))

;; --strict-keys — 「読めた」を「無傷」と読まないための追加検査。opt-in。
;;
;; `read-string` が throw しないことは、文書が壊れていないことを意味しない。
;; 実測 2026-08-20: ADR の本文中に引用符を 1 つ入れると、そこで文字列が終わり、
;; 続く語が **キーとして** 読まれ、次の引用符から新しい文字列が始まる。引用符の
;; 個数の偶奇が合えば map も vector も閉じるので、**reader は何事もなく値を返す**。
;; その値の keys は `(:adr/status :adr/id nothing :adr/body :adr/date no-op :db/id "\\")`
;; で、`:adr/body` は数千字あるはずが 523 字に切り詰められていた。
;;
;; 壊れた文書と無傷の文書が同じ exit code を返す —— この repo が 1 日で 6 箇所
;; 見つけた形そのもの。keys が全部 keyword かどうかは、それを 1 行で分ける。
;;
;; 既定は off。この gate は 3 つの repo が既に使っており、そちらの現在値を
;; 測らずに厳しくすると、直すべきものが無いのに赤くなる。
(def strict-keys (some? (some #{"--strict-keys"} args)))

(defn- bad-keys
  "SYMBOL keys anywhere in a parsed document. Empty is clean.

  Symbols only, and that narrowing was measured rather than assumed. The first
  version flagged every non-keyword key and reddened 8 of the superproject's
  2,347 ADRs — of which 7 were legitimate: `\"p50\"`/`\"p95\"`, `\".clj\"`/`\".cljs\"`,
  `\"stripe.com\"`, `0 1 2 3`. EDN maps take string and integer keys and this
  corpus uses them on purpose; a check that calls those corrupt is a check
  nobody will keep.

  A bare SYMBOL in key position is different: it is what prose becomes when a
  quote ends its string early and the reader carries on parsing the sentence as
  structure. Measured across the same 2,347 documents, exactly one has symbol
  keys — `commit-dag` and `|quad-store`, a table cell that bled into the
  document — so the rule costs one true positive and no false ones."
  [v]
  (cond
    (map? v) (concat (filter symbol? (keys v)) (mapcat bad-keys (vals v)))
    (sequential? v) (mapcat bad-keys v)
    :else nil))

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

(when-not (fs/existsSync root)
  (println "FLEET-CI: root does not exist:" root
           "— extraction or --sub is wrong, refusing to report pass")
  (js/process.exit 90))

(let [files (edn-files root)
      bad (atom [])]
  (doseq [f files]
    (try
      (let [s (str (fs/readFileSync f "utf8"))
            ;; 空ファイル・コメントのみは read-string が nil を返すのが正常。
            v (reader/read-string (str "[" s "]"))]
        (when strict-keys
          (let [odd (distinct (bad-keys v))]
            (when (seq odd)
              (swap! bad conj
                     [f (str "parses, but " (count odd) " symbol key(s) — "
                             (str/join ", " (map pr-str (take 4 odd)))
                             " — a quote inside a string ended it early and what "
                             "followed was read as structure")])))))
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
    ;; One string, not two arguments to println. `(when strict-keys ...)` is nil
    ;; when the flag is off, and println prints that nil: the three repos that
    ;; do not pass --strict-keys have been recording
    ;; "OK — all 2347 edn files parse nil" in their fleet receipts since this
    ;; flag landed. Harmless to the verdict and confusing in the one line a
    ;; reader actually sees.
    :else (println (str "OK — all " (count files) " edn files parse"
                        (when strict-keys
                          ", and no symbol appears in key position")))))
