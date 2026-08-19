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

(defn- walk
  "`{:files [...] :links [...]}` -- regular source files, and separately the
  symlinks that look like source.

  A git-annex pointer is a symlink, and `(.isFile e)` is FALSE for one, so
  the first version of this walker dropped them in silence: not scanned, not
  counted, not named -- and therefore indistinguishable in the output from a
  file that was read and found clean. Measured 2026-08-19 on the
  superproject tree: 118 symlinks carry a source extension, all annex
  pointers under `orgs/personal`.

  Their content is not in the tree at all -- a fleet node has no annex
  objects -- so this cannot scan them. What it can do is say how many it did
  not scan, which is the difference between *skipped* and *passed*."
  [dir]
  (let [out (atom []) links (atom [])]
    ((fn go [d]
       (doseq [e (fs/readdirSync d #js {:withFileTypes true})]
         (let [n (.-name e) full (path/join d n)]
           (cond
             (and (.isDirectory e) (not (#{".git" "node_modules" ".cpcache"} n))) (go full)
             (and (.isFile e) (source-ext (path/extname n))) (swap! out conj full)
             (and (.isSymbolicLink e) (source-ext (path/extname n))) (swap! links conj full)))))
     dir)
    {:files @out :links @links}))

(defn- allowed? [p]
  (some (fn [[suffix _]] (str/ends-with? p suffix)) allowed))

(defn- finding!
  "FINDING<TAB>severity<TAB>key<TAB>detail — the orgs-detector protocol.

  Emitted alongside the human-readable lines, not instead of them: this
  script is BOTH a fleet gate over the superproject tree and an
  orgs-detector over the child checkouts, and the two callers read different
  things. The key is the path, so a finding is stable across reruns and a
  file that gets fixed resolves rather than churning."
  [severity key detail]
  (println (str "FINDING\t" severity "\t" key "\t" detail)))

(let [args (vec *command-line-args*)
      findings? (some #{"--findings"} args)
      dir (or (first (remove #(str/starts-with? % "--") args)) ".")]
  (self-test!)
  (when-not (fs/existsSync dir)
    (println "SCANNED\t0")
    (println (str "Refusing to report a verdict: no such directory " dir))
    (js/process.exit 2))
  (let [{:keys [files links]} (walk dir)
        results (map (fn [p] [p (has-nul? p)]) files)
        unreadable (filterv (fn [[_ r]] (nil? r)) results)
        scanned (- (count results) (count unreadable))
        hits (filterv (fn [[_ r]] (true? r)) results)
        {ok true bad false} (group-by (comp boolean allowed? first) hits)]
    (println (str "SCANNED\t" scanned))
    (when (seq unreadable)
      (println (str "UNREADABLE\t" (count unreadable))))
    ;; Not a failure: an annex pointer is legitimately unreadable here, and
    ;; failing on it would leave this gate permanently red. It is printed so
    ;; that "did not look" never reads as "looked and found nothing".
    (when (seq links)
      (println (str "UNSCANNED-SYMLINK\t" (count links))))
    (doseq [[p _] (sort-by first (or ok []))]
      (println (str "  allowed  " p)))
    (doseq [[p _] (sort-by first (or bad []))]
      (println (str "  NUL      " p))
      (when findings?
        (finding! "warn" p
                  (str "raw control byte in source; grep is silent on this file "
                       "-- `grep -c <name> " p "` prints nothing and exits 1, "
                       "which is what a file NOT containing that name does"))))
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
