#!/usr/bin/env nbb
;; verify-source-nul-bytes.cljs — ソースに紛れた NUL バイトの検査。
;;
;;   nbb scripts/verify-source-nul-bytes.cljs [<dir>]
;;
;; ## なぜ要るか
;;
;; **NUL バイトが 1 個入ったソースファイルに対して、grep は黙る。**
;; `file(1)` はそれを `data` と判定し、macOS の grep はバイナリを飛ばすので、
;; `grep -c MessageDigest <file>` は **出力ゼロで exit 1** ——「その語が無い
;; ファイル」と完全に同じ見え方になる。
;;
;; 実測 2026-08-18、同じ根本原因が 1 日に 2 回、別々の経路で出た:
;;
;;   1. `scripts/fleet-ci/tick.cljs` の文字列リテラルに生 NUL。3 回の検索が
;;      空で返り、それを「コードが無い」と読んだ。
;;   2. `network-awai/net-isekai-gen/src/isekai_gen/cid.clj` に生 NUL。
;;      「JVM interop 無し」と走査が報告したが、そのファイルは
;;      `java.security.MessageDigest` を import していた。同じファイルが
;;      **3 repo に複製**されていた。
;;
;; つまりこれは 1 ファイルの汚れではなく、**そのファイルについての検索に
;; 基づく推論を全部無効にする**性質の欠陥である。
;;
;; ## この検査自身が壊れていた
;;
;; 上を掃こうとして最初に書いた検出器は、シェルの引用で `\000` が NUL として
;; 渡らず、**既知の不良ファイルを「clean」、既知の正常ファイルを「NUL 有り」と
;; 報告した**。それに気づかなければ「65,961 ファイル中 0 件」を発見として
;; 報告していた。だからここでは **自己検証を通らなければ起動しない**:
;; 検出器は既知の不良と既知の正常の両方に正しく答えられることを毎回示す。
;;
;; ## exit code は三値
;;
;;   0  走査して、許可されていない NUL は無かった
;;   1  許可されていない NUL があった
;;   2  **答えられなかった** —— 自己検証に失敗した、走査対象が 0 件だった、
;;      または対象ディレクトリが無い。これが 0 と区別されることが要点で、
;;      検査できなかった実行が「異常なし」と同じ値を返してはならない。

(require '["node:fs" :as fs]
         '["node:path" :as path]
         '[clojure.string :as str])

(def source-ext #{".clj" ".cljc" ".cljs" ".kotoba" ".edn" ".md" ".yml" ".yaml" ".json"})

(def allowed
  "NUL を含んでよいと判断済みのパス接尾辞と、その理由。
   **接尾辞一致であって glob ではない** —— `elf64.clj` を許すのであって
   `elf64.clj.bak` を許さない、という粒度を意図している。"
  {"src/kotoba/native/elf64.clj"
   "ELF64 emitter: NUL は生成するバイナリヘッダそのもの"
   "src/kotoba/compiler/packaging/elf64.clj"
   "同上（compiler 側の ELF64 packaging）"})

(defn- has-nul?
  "`p` のバイト列に NUL が含まれるか。読めなければ nil。
   nil は「無い」ではなく「読めなかった」であり、呼び出し側が数える。"
  [p]
  (try
    (let [b (fs/readFileSync p)]
      (not= -1 (.indexOf b 0)))
    (catch :default _ nil)))

(defn- self-test!
  "検出器が **両方向に** 答えられることを示してから走る。
   示せなければ exit 2 —— 答えられない検査は合格を報告してはならない。"
  []
  (let [dir (fs/mkdtempSync (path/join (or (aget js/process.env "TMPDIR") "/tmp") "nulscan-"))
        bad (path/join dir "bad.clj")
        good (path/join dir "good.clj")]
    (fs/writeFileSync bad (js/Buffer.from #js [40 100 101 102 110 0 41]))  ; "(defn\0)"
    (fs/writeFileSync good "(defn f [] :ok)")
    (let [b (has-nul? bad) g (has-nul? good)]
      (fs/rmSync dir #js {:recursive true :force true})
      (when-not (and (true? b) (false? g))
        (println "SCANNED\t0")
        (println (str "SELFTEST FAILED — known-bad answered " (pr-str b)
                      ", known-clean answered " (pr-str g) "."))
        (println "Refusing to report a verdict: a detector that cannot answer")
        (println "both ways cannot distinguish `no NUL bytes` from `did not look`.")
        (js/process.exit 2))
      (println "SELFTEST\tok"))))

(defn- walk [dir]
  (let [out (atom [])]
    ((fn go [d]
       (doseq [e (fs/readdirSync d #js {:withFileTypes true})]
         (let [n (.-name e) full (path/join d n)]
           (cond
             (and (.isDirectory e) (not (#{".git" "node_modules" ".cpcache"} n))) (go full)
             (and (.isFile e) (source-ext (path/extname n))) (swap! out conj full)))))
     dir)
    @out))

(defn- allowed? [p]
  (some (fn [[suffix _]] (str/ends-with? p suffix)) allowed))

(let [dir (or (first *command-line-args*) ".")]
  (self-test!)
  (when-not (fs/existsSync dir)
    (println "SCANNED\t0")
    (println (str "Refusing to report a verdict: no such directory " dir))
    (js/process.exit 2))
  (let [files (walk dir)
        results (map (fn [p] [p (has-nul? p)]) files)
        unreadable (filterv (fn [[_ r]] (nil? r)) results)
        scanned (- (count results) (count unreadable))
        hits (filterv (fn [[_ r]] (true? r)) results)
        {ok true bad false} (group-by (comp boolean allowed? first) hits)]
    (println (str "SCANNED\t" scanned))
    (when (seq unreadable)
      (println (str "UNREADABLE\t" (count unreadable))))
    (doseq [[p _] (sort-by first (or ok []))]
      (println (str "  allowed  " p)))
    (doseq [[p _] (sort-by first (or bad []))]
      (println (str "  NUL      " p)))
    (cond
      ;; evidence floor: 0 件走査は 0 件違反ではない。
      (zero? scanned)
      (do (println "Refusing to report a pass: scanned 0 files.") (js/process.exit 2))

      (seq bad)
      (do (println)
          (println (str (count bad) " source file(s) contain a NUL byte."))
          (println "grep is SILENT on these — `grep -c foo <file>` prints nothing")
          (println "and exits 1, which is indistinguishable from the word being absent.")
          (println "Fix: replace the literal byte with a \\u0000 escape (same byte at")
          (println "runtime, file readable by every tool), or add the path to `allowed`")
          (println "in this script WITH the reason.")
          (js/process.exit 1))

      :else
      (println (str "OK\t" scanned " source files, "
                    (count (or ok [])) " allowed NUL file(s), 0 unexplained.")))))
