#!/usr/bin/env nbb
;; verify-checker-floors.cljs — run every `scripts/check-*.cljs` / `scripts/verify-*.cljs`
;; in a tree against an EMPTY input and require that it refuses (exit 2).
;;
;;   nbb --classpath ".:scripts/nbb_compat" scripts/verify-checker-floors.cljs [<dir>] [--findings] [--strict] [--child-timeout-ms N]
;;
;; ## なぜ要るか
;;
;; ADR-2608136000 の 1 問目 —— **入力が無いとき何を返すか。pass ならそれが欠陥。**
;; 検査器は「N 件を見て違反 0」と「0 件を見て違反 0」を同じ PASS で印字する。
;; 配られた tree に対象が入っていなかった日（fleet の `:include-ext` が source を
;; 落とした実測 2026-08-13）、この検査器は緑を返し、誰も気づかない。
;;
;; 手で読んで見つけられる欠陥ではない —— PASS の println の手前に床が在るかどうかは
;; 全部のコードを読まないと分からず、しかも読んだ確信は測定ではない。だから**走らせる**:
;; 空の作業ディレクトリを渡し、exit code を見る。
;;
;; ## 何をするか
;;
;; 1. 対象 tree の下で `scripts/` ディレクトリを探し、`check-*.cljs` / `verify-*.cljs` を集める。
;; 2. repo ごとに一時ディレクトリ T を作り、`scripts/` を T/scripts に**複製**する
;;    （`*file*` から repo root を導く検査器が、空の root を見るように）。
;;    `node_modules` が在れば T へ symlink（読むだけ）。T/work は空で、これが cwd かつ
;;    positional / `--root` 引数になる。
;; 3. PATH の先頭に `clojure` `clj` `java` `gh` の stub を置く。呼ばれたら
;;    T/stub.log に追記して exit 64 —— 「禁じたい経路は『無い』ではなく拒否して記録する」
;;    （CLAUDE.md）。JVM を起こす検査器がこの検出器のせいで load を積むことはない。
;; 4. 1 本ずつ time-box して実行し、exit code で分類する:
;;
;;      exit 0   pass-on-empty   **finding（warn）** —— 0 件の証拠で PASS を印字した
;;      exit 2   refuses         正しい —— 答えられないと言った
;;      exit 1   fails           報告はする（NOTE）が finding ではない。
;;                               「入力が無い」と「違反があった」を同じ値で返しているので
;;                               `--strict` では info finding になる
;;      exit 3+  fails           同上
;;      timeout  unmeasured      info finding。測れなかったものを clean にも fail にも数えない
;;
;; ## これが言わないこと
;;
;; pass-on-empty は「その検査器は壊れている」ではなく「その検査器は床を持たない」
;; である。床の無い検査器は、対象が在る限り正しく働く。問題は対象が消えた日に
;; 何も言わないことで、それは対象が在る日には測れない。
;;
;; exit 1 で終わる検査器（ファイルが無くて crash する等）は pass ではないので finding に
;; しない。全部を finding にすると STANDING が積もり、それは registry のヘッダが
;; 「沈黙と区別できない」と呼ぶ状態である。
;;
;; ## exit code は三値
;;
;;   0  走らせて、pass-on-empty が無かった
;;   1  pass-on-empty があった（`--strict` なら fails / unmeasured も）
;;   2  **答えられなかった** —— self-check 失敗、検査器が 0 本、1 本も測れなかった、対象が無い

(require '["node:fs" :as fs]
         '["node:path" :as path]
         '["node:os" :as os]
         '["node:child_process" :as cp]
         '[clojure.string :as str])

(def checker-re #"^(check|verify)-.*\.cljs$")

(def stubbed-bins
  "呼ばれたら記録して exit 64 する実行ファイル名。JVM 起動と GitHub API を検査器の
   下で起こさない。"
  ["clojure" "clj" "java" "gh"])

;; ── 分類（純関数。self-check がこれを直接叩く） ────────────────────────────

(defn classify
  "{:exit n|nil :timeout? bool} -> :pass-on-empty | :refuses | :fails | :unmeasured"
  [{:keys [exit timeout?]}]
  (cond timeout?  :unmeasured
        (= 0 exit) :pass-on-empty
        (= 2 exit) :refuses
        :else      :fails))

;; Second rule (2026-09-07). The first rule fed a MISSING input and the known
;; floorless checkers crashed (exit 1) rather than print PASS, so it never went
;; red on a real tree -- a detector written to the hypothesis, not to a measured
;; red. The hazard silence-h9 names is a verdict with no evidence count: run the
;; checker against its OWN repo and refuse a PASS/OK/CLEAN line that carries no
;; number -- `PASS` alone cannot distinguish 40 components from 0.
(def verdict-re #"\b(PASS|OK|CLEAN|SUCCESS)\b|✅")   ; anywhere -- `Q9 JVM-FREE PASS:` carries it mid-line
(def count-re   #"(?i)(SCANNED\t\d+|\b\d+\s*(files?|components?|documents?|checks?|entries|verified|scanned|checked|items?|repos?|modules?)\b|\b(files?|components?|documents?|checks?|verified|scanned|checked)\D{0,12}\d+)")
(defn pass-without-count?
  "exit 0 + a verdict word + no evidence count anywhere in the output."
  [{:keys [exit out]}]
  (and (= 0 exit) (re-find verdict-re (str out)) (not (re-find count-re (str out)))))

;; ── 実行 ─────────────────────────────────────────────────────────────────

(defn- mkdtemp [prefix] (fs/mkdtempSync (path/join (os/tmpdir) prefix)))

(defn- write-stubs!
  "T/stubs/<bin> を作り、PATH に前置する文字列を返す。"
  [t]
  (let [dir (path/join t "stubs")
        log (path/join t "stub.log")]
    (fs/mkdirSync dir #js {:recursive true})
    (doseq [b stubbed-bins]
      (let [p (path/join dir b)]
        (fs/writeFileSync p (str "#!/bin/sh\nprintf '%s %s\\n' \"$0\" \"$*\" >> '" log "'\nexit 64\n"))
        (fs/chmodSync p 0755)))
    {:dir dir :log log}))

(defn- last-line
  "detail 用の 1 行。stack trace なら `Error` を含む最初の行（末尾は `Node.js v..` で
   何も言わない）、そうでなければ最後の非空行。"
  [s]
  (let [ls (remove str/blank? (str/split-lines (or s "")))
        pick (or (first (filter #(re-find #"Error|error:|ENOENT|EACCES" %) ls)) (last ls))]
    (some-> pick str/trim (as-> l (subs l 0 (min 160 (count l)))))))

(defn run-checker
  "1 本を空入力で実行。{:exit :timeout? :out :stubbed? :ms}。"
  [{:keys [script cwd classpath path-prefix stub-log timeout-ms]}]
  (let [t0 (js/Date.now)
        env (js/Object.assign #js {} js/process.env
                              #js {"PATH" (str path-prefix ":" (.-PATH js/process.env))
                                   "GIT_ALLOW_PROTOCOL" "none"
                                   "GIT_TERMINAL_PROMPT" "0"
                                   "NO_COLOR" "1"})
        r (cp/spawnSync "nbb" (clj->js (concat ["--classpath" classpath script cwd "--root" cwd]))
                        #js {:cwd cwd :encoding "utf8" :timeout timeout-ms
                             :stdio #js ["ignore" "pipe" "pipe"] :maxBuffer (* 16 1024 1024) :env env})
        timeout? (= "ETIMEDOUT" (some-> (.-error r) .-code))
        stub-hits (try (count (remove str/blank? (str/split-lines (fs/readFileSync stub-log "utf8"))))
                       (catch :default _ 0))]
    {:exit (when-not timeout? (.-status r))
     :timeout? (boolean timeout?)
     :spawn-error (when-let [e (.-error r)] (when-not timeout? (.-message e)))
     :out (str (.-stdout r) (when (str/blank? (str (.-stdout r))) (.-stderr r)))
     :stubbed stub-hits
     :ms (- (js/Date.now) t0)}))

(defn measure-scripts-dir
  "repo の scripts/ を複製し、各検査器を空入力で走らせる。結果の列。"
  [scripts-dir names timeout-ms]
  (let [repo (path/dirname scripts-dir)
        t (mkdtemp "checker-floors-")
        work (path/join t "work")
        _ (fs/mkdirSync work)
        _ (fs/cpSync scripts-dir (path/join t "scripts") #js {:recursive true :dereference false})
        _ (when (fs/existsSync (path/join repo "node_modules"))
            (fs/symlinkSync (path/join repo "node_modules") (path/join t "node_modules")))
        {:keys [dir log]} (write-stubs! t)
        cps (filter fs/existsSync [t (path/join t "scripts") (path/join repo "src")
                                   (path/join t "scripts" "nbb_compat")])
        classpath (str/join ":" cps)]
    (try
      (vec (for [n names]
             (let [_ (try (fs/unlinkSync log) (catch :default _ nil))
                   r (run-checker {:script (path/join t "scripts" n) :cwd work :classpath classpath
                                   :path-prefix dir :stub-log log :timeout-ms timeout-ms})
                   c (classify r)
                   ;; rule 2 only where rule 1 did not already fire: the checker on
                   ;; its own tree, same stubs (JVM/gh still refused there).
                   _ (try (fs/unlinkSync log) (catch :default _ nil))
                   ;; the ORIGINAL script, not the copy: checkers locate their
                   ;; inputs relative to their own file, and the copy in t/ has no
                   ;; ../qualification or ../lang beside it (that crash read as
                   ;; `fails` and hid every real PASS-without-count).
                   real (when-not (= c :pass-on-empty)
                          (run-checker {:script (path/join scripts-dir n) :cwd repo
                                        :classpath (str/join ":" (filter fs/existsSync [repo (path/join repo "scripts") (path/join repo "src") (path/join repo "scripts" "nbb_compat")]))
                                        :path-prefix dir :stub-log log :timeout-ms timeout-ms}))
                   c2 (if (and real (pass-without-count? real)) :pass-without-count c)]
               (assoc r :script (path/join scripts-dir n) :class c2
                      :real-last (when real (last-line (:out real)))))))
      (finally
        (fs/rmSync t #js {:recursive true :force true})))))

;; ── self-check: 3 種の fixture 検査器を実際に走らせて分類が合うことを示す ────
;;
;; 個数で返す。fixture は一時ディレクトリに書く（repo には何も書かない）。

(def fixture-scripts
  {"check-pass.cljs"   "(println \"PASS 0 files checked\") (js/process.exit 0)"
   "verify-floor.cljs" "(println \"SCANNED\\t0\") (println \"REFUSED\\tnothing to scan\") (js/process.exit 2)"
   "check-fail.cljs"   "(throw (js/Error. \"ENOENT: no such file\"))"
   ;; 境界: JVM に落ちる検査器は stub に当たり exit 64 -> fails、かつ stub.log が空でない
   "check-jvm.cljs"    "(require '[\"node:child_process\" :as cp]) (let [r (cp/spawnSync \"clojure\" #js [\"-M:test\"] #js {:stdio \"ignore\"})] (js/process.exit (if (= 0 (.-status r)) 0 1)))"})

(defn self-check! [timeout-ms]
  (let [t (mkdtemp "checker-floors-selfcheck-")
        sd (path/join t "scripts")]
    (try
      (fs/mkdirSync sd)
      (doseq [[n src] fixture-scripts] (fs/writeFileSync (path/join sd n) src))
      (let [rs (measure-scripts-dir sd (sort (keys fixture-scripts)) timeout-ms)
            by (into {} (map (juxt #(path/basename (:script %)) identity) rs))
            want {"check-pass.cljs" :pass-on-empty "verify-floor.cljs" :refuses
                  "check-fail.cljs" :fails "check-jvm.cljs" :fails}
            ok (count (filter (fn [[n c]] (= c (:class (by n)))) want))
            jvm-traced? (pos? (or (:stubbed (by "check-jvm.cljs")) 0))]
        {:ok ok :want (count want) :jvm-traced? jvm-traced?
         :got (into {} (map (fn [[n r]] [n [(:class r) (:exit r) (:stubbed r)]]) by))})
      (finally (fs/rmSync t #js {:recursive true :force true})))))

;; ── 走査 ──────────────────────────────────────────────────────────────────

(defn- find-scripts-dirs [root]
  (let [skip #{"node_modules" ".git" ".shadow-cljs" "out" "dist" "target"
               ".cpcache" ".gitlibs" "public" "build" ".venv" "__pycache__" ".claude"}]
    (letfn [(go [d depth]
              (let [ents (try (fs/readdirSync d #js {:withFileTypes true})
                              (catch :default e (println (str "NOTE\tunreadable-dir\t" d "\t" (.-message e))) #js []))
                    here (when (= "scripts" (path/basename d))
                           (let [names (->> (array-seq ents)
                                            (filter #(.isFile %))
                                            (map #(.-name %))
                                            (filter #(re-find checker-re %))
                                            sort vec)]
                             (when (seq names) [[d names]])))]
                (concat here
                        (when (< depth 8)
                          (mapcat (fn [e]
                                    (let [n (.-name e)]
                                      (if (and (.isDirectory e) (not (skip n)))
                                        (go (path/join d n) (inc depth))
                                        [])))
                                  (array-seq ents))))))]
      (go root 0))))

(defn- opt [argv flag default]
  (let [i (.indexOf (clj->js argv) flag)]
    (if (neg? i) default (nth argv (inc i) default))))

(defn- finding! [severity key detail]
  (println (str "FINDING\t" severity "\t" key "\t" detail)))

(defn -main [& argv]
  (let [timeout-val (opt argv "--child-timeout-ms" nil)
        args (->> argv
                  (remove #(str/starts-with? % "--"))
                  (remove #(and timeout-val (= % timeout-val))))
        root (or (first args) ".")
        findings? (boolean (some #{"--findings"} argv))
        strict? (boolean (some #{"--strict"} argv))
        timeout-ms (js/parseInt (opt argv "--child-timeout-ms" "60000") 10)]
    (when-not (try (.isDirectory (fs/statSync root)) (catch :default e
                                                        (println (str "NOTE\tstat failed: " (.-message e)))
                                                        false))
      (println "SCANNED\t0")
      (println (str "REFUSED\tno such directory: " root))
      (js/process.exit 2))
    (let [sc (self-check! timeout-ms)]
      (when-not (and (= (:ok sc) (:want sc)) (:jvm-traced? sc))
        (println "SCANNED\t0")
        (println "REFUSED\tself-check failed: the runner cannot tell a checker that passes on empty input from one that refuses or fails")
        (println (str "  classified correctly " (:ok sc) "/" (:want sc) ", jvm stub traced: " (:jvm-traced? sc)))
        (println (str "  got " (pr-str (:got sc))))
        (js/process.exit 2))
      (println (str "SELF-CHECK\t" (:ok sc) "/" (:want sc) "\tstub-traced=" (:jvm-traced? sc))))
    (let [dirs (vec (find-scripts-dirs root))
          total (reduce + (map (comp count second) dirs))
          results (vec (mapcat (fn [[d names]] (measure-scripts-dir d names timeout-ms)) dirs))
          measured (remove #(= :unmeasured (:class %)) results)
          by-class (group-by :class results)
          n (fn [k] (count (by-class k)))]
      (println (str "SCANNED\t" (count measured)))
      (when (str/includes? root "orgs")
        (println "NOTE\tcheckouts only; unchecked-out west projects are not scanned"))
      (println (str "CHECKERS\t" total "\tpass-on-empty=" (n :pass-on-empty) "\trefuses=" (n :refuses)
                    "\tpass-without-count=" (n :pass-without-count) "\tfails=" (n :fails) "\tunmeasured=" (n :unmeasured)))
      (when (pos? (n :unmeasured)) (println (str "UNMEASURED\t" (n :unmeasured))))
      (let [stubbed (filter #(pos? (:stubbed %)) results)]
        (when (seq stubbed) (println (str "STUBBED\t" (count stubbed) "\tcheckers reached clojure/clj/java/gh and were refused there"))))
      (cond
        (zero? total)
        (do (println "REFUSED\tno scripts/check-*.cljs or scripts/verify-*.cljs found; a run of nothing is not a clean result")
            (js/process.exit 2))

        (zero? (count measured))
        (do (println "REFUSED\tevery checker timed out; nothing was measured")
            (js/process.exit 2))

        :else
        (let [bad (concat (by-class :pass-on-empty)
                          (by-class :pass-without-count)
                          (by-class :unmeasured)
                          (when strict? (by-class :fails)))]
          (doseq [{:keys [script class exit out stubbed ms spawn-error]} (sort-by :script results)]
            (println (str (name class) "\t" script "\texit=" (or exit "timeout")
                          (when (pos? stubbed) "\tstubbed") "\t" ms "ms\t" (or spawn-error (last-line out)))))
          (if (seq bad)
            (do (when findings?
                  (doseq [{:keys [script class exit out]} (sort-by :script bad)]
                    (case class
                      :pass-on-empty
                      (finding! "warn" (str "pass-on-empty:" script)
                                (str script " exits 0 against an empty input dir and prints `" (last-line out)
                                     "` -- zero evidence and a clean scan return the same value; add a floor (SCANNED n, n=0 -> exit 2)"))
                      :pass-without-count
                      (finding! "warn" (str "pass-without-count:" script)
                                (str script " exits 0 on its own tree and prints `" (or (:real-last (first (filter #(= script (:script %)) results))) (last-line out))
                                     "` with no evidence count -- a verdict that cannot tell N from 0; print SCANNED n and exit 2 when n=0"))
                      :unmeasured
                      (finding! "info" (str "unmeasured:" script)
                                (str script " did not finish within " timeout-ms " ms against an empty input; not clean, not failing, unmeasured"))
                      :fails
                      (finding! "info" (str "fails-not-refuses:" script)
                                (str script " exits " exit " against an empty input -- the same value as `violations found`; exit 2 would say `cannot answer`")))))
                (println (str "FINDINGS\t" (count bad)))
                (js/process.exit 1))
            (do (println "CLEAN\t0") (js/process.exit 0))))))))

(apply -main (vec *command-line-args*))
