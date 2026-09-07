#!/usr/bin/env nbb
;; verify-catch-swallow.cljs — `(catch T _ <empty-literal>)` outside a teardown,
;; and Python `except Exception: pass`.
;;
;;   nbb --classpath ".:scripts/nbb_compat" scripts/verify-catch-swallow.cljs [<dir>] [--findings]
;;
;; ## なぜ要るか
;;
;; ADR-2608136000 の形 —— **測れなかった検査が、測って問題が無かった検査と同じ値を
;; 返す** —— を、ソースの中で最も安く作る 1 行がこれである:
;;
;;     (try (read-the-evidence p) (catch :default _ nil))
;;
;; 読めなかったことと、読んだら空だったことが、同じ `nil` になる。呼び出し側は
;; その nil を「無かった」と読み、下流の検査は 0 件を clean として数える。
;; 例外の本文（ENOENT / EACCES / parse error）はここで捨てられ、CLAUDE.md の
;; 3 問目（受け取ったエラー本文を捨てていないか）にも同時に当たる。
;;
;; ## 何を検出するか
;;
;;   A. clojure  — `(catch <T> _ <lit>)` で <lit> ∈ #{nil false 0 -1 [] {} #{}}、
;;                 束縛名が `_`（または `_` 始まり）で本文が literal 1 つ。
;;                 **ただし** 囲んでいる `try` 本文が teardown なら除外する:
;;                 `.close` `.destroy` `.kill` `unlinkSync` `rmSync`。
;;                 片付けで失敗を握るのは正当で、そこを報告すると規則を守った側を罰する。
;;   B. python   — `except:` / `except Exception:` / `except BaseException:` の直後が
;;                 `pass` だけ。特定の例外型（`except OSError: pass`）は意図的な絞り込み
;;                 なので報告しない。teardown（`.close(` `.kill(` `os.unlink` `os.remove`
;;                 `shutil.rmtree` が try 本文に在る）も同じく除外。
;;
;; **これは「例外を握るのが常に誤り」という主張ではない。** 報告するのは、握った
;; 結果が「正常に空だった」と同じ値になる形であって、握ること自体ではない。
;; `(catch :default e (log! e) nil)` は本文が literal 1 つでないので当たらない。
;;
;; ## exit code は三値
;;
;;   0  走査して、該当が無かった
;;   1  該当があった
;;   2  **答えられなかった** —— self-check に失敗した、走査対象が 0 件、対象が無い、
;;      読めないファイルがあった。

(require '["node:fs" :as fs]
         '["node:path" :as path]
         '[clojure.string :as str])

(def clj-ext #{".clj" ".cljc" ".cljs"})
(def py-ext #{".py"})

(def teardown-tokens
  "囲む try 本文にこれが在れば、その catch は片付けの握りとみなす。"
  [".close" ".destroy" ".kill" "unlinkSync" "rmSync"])

(def py-teardown-tokens
  [".close(" ".kill(" "os.unlink" "os.remove" "shutil.rmtree" ".terminate("])

(def swallow-re
  ;; (catch :default _ nil)  (catch Exception _ false)  (catch js/Error _e {})
  #"\(catch\s+[^\s()]+\s+_[\w\-]*\s+(nil|false|0|-1|\[\]|\{\}|#\{\})\s*\)")

;; ── 形の判定（純関数。self-check がこれを直接叩く） ────────────────────────

(defn- line-of [src idx]
  (inc (count (re-seq #"\n" (subs src 0 idx)))))

(defn- comment-line?
  "その位置を含む行が `;` で始まる（前置空白を除く）か。"
  [src idx]
  (let [bol (inc (or (str/last-index-of src "\n" idx) -1))
        line (subs src bol idx)]
    (str/starts-with? (str/triml line) ";")))

(defn- enclosing-open
  "`idx` にある `(` の、直近の外側の `(` の位置。括弧の対応だけを数える粗い探索で、
   文字列中の括弧は数えない（粗さは teardown 判定を広げる方向 = 報告を減らす方向に
   しか効かない）。見つからなければ nil。"
  [src idx]
  (loop [i (dec idx) depth 0]
    (cond
      (neg? i) nil
      :else (let [c (nth src i)]
              (cond
                (= c \)) (recur (dec i) (inc depth))
                (= c \() (if (zero? depth) i (recur (dec i) (dec depth)))
                :else (recur (dec i) depth))))))

(defn- enclosing-try-body
  "catch が `idx` に在るとき、それを囲む `(try ...` の先頭から catch までの本文。
   囲みが `try` でなければ（マクロの中など）nil —— その場合は teardown 判定が
   できないので**報告する側**に倒す。"
  [src idx]
  (when-let [open (enclosing-open src idx)]
    (let [head (subs src open (min (count src) (+ open 8)))]
      (when (re-find #"^\(try[\s\(]" head)
        (subs src open idx)))))

(defn- teardown? [body tokens]
  (boolean (and body (some #(str/includes? body %) tokens))))

(defn clj-findings
  "1 ファイル分。`{:rule :catch-swallow :line n :lit s}` の列。"
  [src]
  (let [re (js/RegExp. (.-source swallow-re) "g")]
    (loop [out []]
      (if-let [m (.exec re src)]
        (let [idx (.-index m)
              lit (aget m 1)]
          (recur (if (or (comment-line? src idx)
                         (teardown? (enclosing-try-body src idx) teardown-tokens))
                   out
                   (conj out {:rule :catch-swallow :line (line-of src idx) :lit lit}))))
        out))))

(defn py-findings
  "1 ファイル分。`except:` / `except Exception:` / `except BaseException:` の次の
   非空行が `pass` だけなら該当。try 本文は、同じインデントの直近 `try:` からとる。"
  [src]
  (let [lines (vec (str/split src #"\n" -1))
        indent (fn [s] (count (re-find #"^\s*" s)))
        broad? (fn [s] (re-find #"^\s*except(\s*|\s+(Exception|BaseException)\s*(as\s+\w+)?\s*):\s*(#.*)?$" s))]
    (loop [i 0 out []]
      (if (>= i (count lines))
        out
        (let [l (nth lines i)]
          (if-not (broad? l)
            (recur (inc i) out)
            (let [next-i (loop [j (inc i)] (if (and (< j (count lines)) (str/blank? (nth lines j))) (recur (inc j)) j))
                  nxt (when (< next-i (count lines)) (nth lines next-i))
                  pass? (and nxt (re-find #"^\s*pass\s*(#.*)?$" nxt) (> (indent nxt) (indent l)))
                  ;; try 本文: 上へ遡って同じインデントの `try:` を探す
                  try-i (loop [j (dec i)]
                          (cond (neg? j) nil
                                (and (= (indent (nth lines j)) (indent l))
                                     (re-find #"^\s*try\s*:\s*(#.*)?$" (nth lines j))) j
                                :else (recur (dec j))))
                  body (when try-i (str/join "\n" (subvec lines try-i i)))]
              (recur (inc i)
                     (if (and pass? (not (teardown? body py-teardown-tokens)))
                       (conj out {:rule :except-pass :line (inc i) :lit "pass"})
                       out)))))))))

(defn findings-in [ext src]
  (cond (clj-ext ext) (clj-findings src)
        (py-ext ext) (py-findings src)
        :else []))

;; ── self-check: 既知の不良と既知の正常に、毎回正しく答えられることを示す ────
;;
;; 個数を返す（boolean は 1 件の退行と壊れた検出器を区別できない）。

(def known-bad-clj
  "読めなかったことと空だったことが同じ nil になる形。3 件。"
  "(defn read-policy [p]
     (try (edn/read-string (.readFileSync fs p \"utf8\")) (catch :default _ nil)))
   (defn count-evidence [dir]
     (try (count (list-files dir)) (catch Exception _ 0)))
   (defn admitted? [x] (try (check x) (catch js/Error _e false)))")

(def known-good-clj
  "teardown の握り 3 件 + 本文が literal 1 つでない 1 件 + コメント 1 件。0 件であるべき。"
  "(defn stop! [s] (try (.destroy s) (catch :default _ nil)))
   (defn cleanup! [p] (finally (try (fs/unlinkSync p) (catch :default _ nil))))
   (defn kill! [c] (try (.kill c \"SIGKILL\") (catch :default _ nil)))
   (defn read-policy [p] (try (slurp p) (catch :default e (log! :unreadable p (.-message e)) nil)))
   ;; (try (x) (catch :default _ nil))")

(def known-bad-py
  "try:\n    data = json.load(open(p))\nexcept Exception:\n    pass\n\ntry:\n    n = count(dir)\nexcept:\n    pass\n")

(def known-good-py
  "try:\n    sock.close()\nexcept Exception:\n    pass\n\ntry:\n    os.unlink(p)\nexcept:\n    pass\n\ntry:\n    v = int(s)\nexcept ValueError:\n    pass\n\ntry:\n    f()\nexcept Exception:\n    log.warning('x')\n")

(defn self-check!
  "discriminate できることの証明。できなければ nil。"
  []
  (let [bc (clj-findings known-bad-clj)
        gc (clj-findings known-good-clj)
        bp (py-findings known-bad-py)
        gp (py-findings known-good-py)]
    (when (and (= 3 (count bc)) (= 0 (count gc))
               (= 2 (count bp)) (= 0 (count gp)))
      {:bad-clj (count bc) :good-clj (count gc) :bad-py (count bp) :good-py (count gp)})))

;; ── 走査 ──────────────────────────────────────────────────────────────────

(defn- walk [dir]
  (let [skip #{"node_modules" ".git" ".shadow-cljs" "out" "dist" "target"
               ".cpcache" ".gitlibs" "public" ".venv" "venv" "__pycache__"}]
    (letfn [(go [d]
              (let [ents (try (fs/readdirSync d #js {:withFileTypes true}) (catch :default _ #js []))]
                (mapcat (fn [e]
                          (let [n (.-name e) p (path/join d n)]
                            (cond (.isDirectory e) (if (skip n) [] (go p))
                                  (or (clj-ext (path/extname n)) (py-ext (path/extname n))) [p]
                                  :else [])))
                        (array-seq ents))))]
      (go dir))))

(defn- self-file?
  "この検出器自身。known-bad fixture を文字列で持つので、走査すると自分を 3 件報告する。
   除外はこの 1 ファイル名だけ（他の検出器の fixture は本文が literal でないか、
   teardown を含む形にしてある）。"
  [p]
  (= "verify-catch-swallow.cljs" (path/basename p)))

(defn- finding! [severity key detail]
  (println (str "FINDING\t" severity "\t" key "\t" detail)))

(defn -main [& argv]
  (let [args (remove #(str/starts-with? % "--") argv)
        root (or (first args) ".")
        findings? (boolean (some #{"--findings"} argv))]
    (when-not (try (.isDirectory (fs/statSync root)) (catch :default e
                                                        (println (str "NOTE\tstat failed: " (.-message e)))
                                                        false))
      (println "SCANNED\t0")
      (println (str "REFUSED\tno such directory: " root))
      (js/process.exit 2))
    (if-not (self-check!)
      (do (println "SCANNED\t0")
          (println "REFUSED\tself-check failed: the detector cannot tell the known-bad shapes from the known-good ones")
          (println (str "  known-bad-clj  -> " (pr-str (clj-findings known-bad-clj)) " (want 3)"))
          (println (str "  known-good-clj -> " (pr-str (clj-findings known-good-clj)) " (want 0)"))
          (println (str "  known-bad-py   -> " (pr-str (py-findings known-bad-py)) " (want 2)"))
          (println (str "  known-good-py  -> " (pr-str (py-findings known-good-py)) " (want 0)"))
          (js/process.exit 2))
      (let [files (vec (remove self-file? (walk root)))
            hits (->> files
                      (mapcat (fn [p]
                                (let [src (try (fs/readFileSync p "utf8")
                                               (catch :default e
                                                 ;; 読めなかった理由を捨てない（3 問目）。nil は UNREADABLE として数える。
                                                 (println (str "NOTE\tunreadable\t" p "\t" (.-message e))) nil))]
                                  (if (nil? src)
                                    [{:file p :rule :unreadable :line 0}]
                                    (map #(assoc % :file p) (findings-in (path/extname p) src))))))
                      vec)
            unreadable (count (filter #(= :unreadable (:rule %)) hits))
            real (remove #(= :unreadable (:rule %)) hits)]
        (println (str "SCANNED\t" (count files)))
        (when (str/includes? root "orgs")
          (println "NOTE\tcheckouts only; unchecked-out west projects are not scanned"))
        (when (pos? unreadable) (println (str "UNREADABLE\t" unreadable)))
        (cond
          (zero? (count files))
          (do (println "REFUSED\tno .clj/.cljc/.cljs/.py source found; a scan of nothing is not a clean result")
              (js/process.exit 2))

          (pos? unreadable)
          (do (println "REFUSED\tsome candidates could not be read; unread is not clean")
              (js/process.exit 2))

          (seq real)
          (do (if findings?
                (doseq [[[rule file] group] (sort-by key (group-by (juxt :rule :file) real))]
                  (finding! "warn" (str (name rule) ":" file)
                            (str (name rule) " at " file " line(s) "
                                 (str/join "," (sort (map :line group)))
                                 " -- a failure to read and an empty read return the same value here; keep the error or refuse")))
                (doseq [{:keys [file rule line lit]} (sort-by (juxt :file :line) real)]
                  (println (str (name rule) "\t" file ":" line "\t" lit))))
              (println (str "FINDINGS\t" (count real)))
              (js/process.exit 1))

          :else
          (do (println "CLEAN\t0") (js/process.exit 0)))))))

(apply -main (vec *command-line-args*))
