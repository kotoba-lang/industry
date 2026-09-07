#!/usr/bin/env nbb
;; verify-grammar-authority-floor.cljs — a checker that reads `lang/guest-grammar.edn`
;; to decide what is forbidden, and still passes when the grammar is missing or
;; forbids nothing.
;;
;;   nbb --classpath ".:scripts/nbb_compat" scripts/verify-grammar-authority-floor.cljs [<dir>] [--findings] [--child-timeout-ms N]
;;
;; ## なぜ要るか
;;
;; `lang/guest-grammar.edn`（kotoba-lang/kotoba-lang）は source-surface の唯一の
;; authority で、`:forbidden-heads` が「書いてはいけない head」を持つ。それを読む
;; 検査器はこう書きがちである:
;;
;;     (into #{} (:forbidden-heads grammar #{}))        ; キーが無ければ空集合
;;     (if-not (exists? grammar-path) [] (check ...))   ; ファイルが無ければ誤り 0 件
;;
;; どちらも **読めなかった authority を「何も禁じない authority」として扱う**。
;; 禁止集合が空なら「全 forbidden head が分類済み」は空虚に真で、検査器は PASS を
;; 印字する —— 26 個を検査した日と 0 個を検査した日が同じ行になる
;; （ADR-2608136000、silence-h10「guest-grammar unreadable == forbids nothing」）。
;;
;; コードを読んで見つけるものではない。走らせる: grammar を消した copy と、
;; `:forbidden-heads #{}` にした copy に対して同じ検査器を実行し、exit を見る。
;;
;; ## 何をするか
;;
;; 1. root 配下の checkout（`scripts/` を持つディレクトリ、深さ 4 まで）から、
;;    `scripts/*.cljs` で **comment 以外の本文**に `guest-grammar` を含むものを集める
;;    （prose で authority を名指しするだけの script は読んでいない —— 実測
;;    2026-09-07、check-vendored-copies-fleet は comment 1 行だけで、digest は GitHub
;;    から curl する。それを候補にすると「消しても pass」が偽の赤になる）。`.bb` は
;;    走らせられない（bb は退役、CLAUDE.md）ので unrunnable として数えるだけ。
;;    checkout に `guest-grammar.edn` が 1 つも無ければ変異が baseline と区別
;;    できないので unmeasured。
;; 2. checkout を一時ディレクトリへ 3 回複製する（`.git` / `node_modules` /
;;    build 出力を除く。`node_modules` は symlink）:
;;      baseline      無改変
;;      missing       すべての `guest-grammar.edn` を削除
;;      empty-forbid  すべての `guest-grammar.edn` の `:forbidden-heads` を `#{}` に
;;                    書き換え（テキスト置換。キーが無ければ先頭 `{` の直後に挿入）
;; 3. PATH の先頭に `clojure` `clj` `java` `gh` の stub を置き（呼ばれたら記録して
;;    exit 64）、各 copy を cwd にして `nbb scripts/<checker>` を time-box で実行。
;; 4. 判定:
;;      missing      で exit 0  → **finding（warn）** pass-on-missing-authority
;;                                 読めない authority は空の authority ではない。
;;                                 印字した count は別の何かの count である。
;;      empty-forbid で exit 0  → **finding（warn）** pass-on-empty-forbid-set
;;                                 本文に `forbidden` を含む検査器（禁止を決める側）
;;                                 だけに当てる。version だけ読む検査器には n/a。
;;      exit 2                  → refuses（正しい）
;;      exit 1 / 64 / crash     → fails（pass ではないので finding にしない。
;;                                 crash は「読めない」を「壊れた」と言っている）
;;      timeout                 → unmeasured
;;    baseline が exit 0 でない検査器は、その理由が grammar と無関係なので
;;    `baseline-red` として表に出す（finding の判定自体は変異 copy の exit で行う）。
;;
;; ## exit code は三値
;;
;;   0  測って、変異 copy で pass した検査器が無かった
;;   1  変異 copy で pass した検査器があった
;;   2  **答えられなかった** —— self-check 失敗、grammar を読む検査器が 0 本、
;;      1 本も測れなかった、対象が無い

(require '["node:fs" :as fs]
         '["node:path" :as path]
         '["node:os" :as os]
         '["node:child_process" :as cp]
         '[clojure.string :as str])

(def grammar-basename "guest-grammar.edn")
(def stubbed-bins ["clojure" "clj" "java" "gh"])
(def skip-dirs #{"node_modules" ".git" ".shadow-cljs" "out" "dist" "target"
                 ".cpcache" ".gitlibs" "public" "build" ".venv" "__pycache__" ".claude"})

;; ── 純関数（self-check が直接叩く） ──────────────────────────────────────

(defn classify
  "{:exit n|nil :timeout? bool} -> :passes | :refuses | :fails | :unmeasured"
  [{:keys [exit timeout?]}]
  (cond timeout? :unmeasured
        (= 0 exit) :passes
        (= 2 exit) :refuses
        :else :fails))

(defn empty-forbid-text
  "grammar 本文の `:forbidden-heads #{...}` を `#{}` に。キーが無ければ最初の
   `{` の直後に挿入する。"
  [text]
  (if (re-find #":forbidden-heads\s+#\{" text)
    (str/replace text #":forbidden-heads\s+#\{[^}]*\}" ":forbidden-heads #{}")
    (str/replace-first text #"\{" "{:forbidden-heads #{} ")))

(defn code-lines
  "`;` で始まる行を除いた本文。comment の中で authority を名指しするだけの
   script（check-vendored-copies-fleet は prose で `guest-grammar.edn` と言い、
   digest は GitHub から curl する）は grammar を読んでいない。"
  [src]
  (str/join "\n" (remove #(str/starts-with? (str/triml %) ";") (str/split-lines src))))

(defn code-reads-grammar? [checker-src]
  (str/includes? (code-lines checker-src) "guest-grammar"))

(defn forbid-decider? [checker-src]
  (boolean (re-find #"forbidden" (code-lines checker-src))))

(defn verdicts
  "1 検査器の 3 run から finding の列。"
  [{:keys [missing empty-forbid decider?]}]
  (cond-> []
    (= :passes (classify missing)) (conj :pass-on-missing-authority)
    (and decider? (= :passes (classify empty-forbid))) (conj :pass-on-empty-forbid-set)))

;; ── 実行 ─────────────────────────────────────────────────────────────────

(defn- mkdtemp [prefix] (fs/mkdtempSync (path/join (os/tmpdir) prefix)))

(defn- write-stubs! [t]
  (let [dir (path/join t "stubs") log (path/join t "stub.log")]
    (fs/mkdirSync dir #js {:recursive true})
    (doseq [b stubbed-bins]
      (let [p (path/join dir b)]
        (fs/writeFileSync p (str "#!/bin/sh\nprintf '%s %s\\n' \"$0\" \"$*\" >> '" log "'\nexit 64\n"))
        (fs/chmodSync p 0755)))
    {:dir dir :log log}))

(defn- last-line [s]
  (let [ls (remove str/blank? (str/split-lines (or s "")))
        pick (or (first (filter #(re-find #"Error|error:|ENOENT|EACCES|REFUSED|FAIL" %) ls)) (last ls))]
    (some-> pick str/trim (as-> l (subs l 0 (min 160 (count l)))))))

(defn- walk-files [dir pred]
  (letfn [(go [d]
            (let [ents (try (fs/readdirSync d #js {:withFileTypes true}) (catch :default _ #js []))]
              (mapcat (fn [e]
                        (let [n (.-name e) p (path/join d n)]
                          (cond (.isDirectory e) (if (skip-dirs n) [] (go p))
                                (pred n) [p]
                                :else [])))
                      (array-seq ents))))]
    (vec (go dir))))

(defn grammar-files [dir] (walk-files dir #(= grammar-basename %)))

(defn copy-checkout!
  "checkout を dst へ複製（skip-dirs を除く）。node_modules は symlink。"
  [repo dst]
  (fs/cpSync repo dst #js {:recursive true :dereference false
                           :filter (fn [src _] (not (contains? skip-dirs (path/basename src))))})
  (let [nm (path/join repo "node_modules")]
    (when (and (fs/existsSync nm) (not (fs/existsSync (path/join dst "node_modules"))))
      (fs/symlinkSync nm (path/join dst "node_modules")))))

(defn mutate!
  "copy に variant を適用。変異した grammar ファイル数を返す。"
  [dst variant]
  (let [gs (grammar-files dst)]
    (doseq [g gs]
      (case variant
        :baseline nil
        :missing (fs/unlinkSync g)
        :empty-forbid (fs/writeFileSync g (empty-forbid-text (fs/readFileSync g "utf8")))))
    (count gs)))

(defn run-checker [{:keys [cwd script path-prefix stub-log timeout-ms]}]
  (let [t0 (js/Date.now)
        env (js/Object.assign #js {} js/process.env
                              #js {"PATH" (str path-prefix ":" (.-PATH js/process.env))
                                   "GIT_ALLOW_PROTOCOL" "none" "GIT_TERMINAL_PROMPT" "0" "NO_COLOR" "1"})
        cps (filter fs/existsSync [cwd (path/join cwd "scripts") (path/join cwd "src") (path/join cwd "scripts" "nbb_compat")])
        _ (try (fs/unlinkSync stub-log) (catch :default _ nil))
        r (cp/spawnSync "nbb" (clj->js ["--classpath" (str/join ":" cps) script])
                        #js {:cwd cwd :encoding "utf8" :timeout timeout-ms
                             :stdio #js ["ignore" "pipe" "pipe"] :maxBuffer (* 16 1024 1024) :env env})
        timeout? (= "ETIMEDOUT" (some-> (.-error r) .-code))
        stubbed (try (count (remove str/blank? (str/split-lines (fs/readFileSync stub-log "utf8"))))
                     (catch :default _ 0))]
    {:exit (when-not timeout? (.-status r))
     :timeout? (boolean timeout?)
     :out (str (.-stdout r) (when (str/blank? (str (.-stdout r))) (.-stderr r)))
     :stubbed stubbed
     :ms (- (js/Date.now) t0)}))

(defn measure-checkout
  "1 checkout の全 checker × 3 variant。結果の列。"
  [repo checkers timeout-ms]
  (let [t (mkdtemp "grammar-floor-")
        {:keys [dir log]} (write-stubs! t)
        copies (into {} (for [v [:baseline :missing :empty-forbid]]
                          (let [d (path/join t (name v))]
                            (copy-checkout! repo d)
                            [v {:dir d :mutated (mutate! d v)}])))]
    (try
      (vec (for [{:keys [name decider?]} checkers]
             (let [runs (into {} (for [v [:baseline :missing :empty-forbid]]
                                   [v (run-checker {:cwd (get-in copies [v :dir])
                                                    :script (path/join "scripts" name)
                                                    :path-prefix dir :stub-log log :timeout-ms timeout-ms})]))
                   vs (verdicts (assoc runs :decider? decider?))]
               {:script (path/join repo "scripts" name)
                :decider? decider?
                :grammar-files (get-in copies [:baseline :mutated])
                :runs runs
                :verdicts vs
                :measured? (not (and (:timeout? (:missing runs)) (:timeout? (:empty-forbid runs))))})))
      (finally (fs/rmSync t #js {:recursive true :force true})))))

;; ── self-check: 3 fixture checkers against a fixture grammar ─────────────

(def fixture-grammar
  "{:kotoba.lang.guest-grammar/authority \"fixture\"\n :kotoba.lang.guest-grammar/profile-version 1\n :forbidden-heads #{eval load}}\n")

(def fixture-checkers
  {;; forbids-nothing-on-unreadable: missing -> {} ; empty set -> vacuous PASS
   "check-floorless.cljs"
   "(require '[\"node:fs\" :as fs] '[clojure.edn :as edn])
    (def g (try (edn/read-string (fs/readFileSync \"lang/guest-grammar.edn\" \"utf8\")) (catch :default _ {})))
    (def forbidden (set (:forbidden-heads g #{})))
    (println \"stats\" (pr-str {:forbidden (count forbidden)}))
    (println \"W0 PASS: guest-grammar authority\")
    (js/process.exit 0)"
   ;; floored: refuses on a missing file and on an empty forbid set
   "check-floored.cljs"
   "(require '[\"node:fs\" :as fs] '[clojure.edn :as edn])
    (when-not (fs/existsSync \"lang/guest-grammar.edn\")
      (println \"REFUSED\\tguest-grammar.edn missing\") (js/process.exit 2))
    (def forbidden (set (:forbidden-heads (edn/read-string (fs/readFileSync \"lang/guest-grammar.edn\" \"utf8\")) #{})))
    (when (zero? (count forbidden))
      (println \"REFUSED\\tforbidden-heads is empty; an authority that forbids nothing is not evidence\") (js/process.exit 2))
    (println (str \"PASS forbidden \" (count forbidden)))
    (js/process.exit 0)"
   ;; reads the grammar for a version only; a missing file is 'no drift'
   "check-version-only.cljs"
   "(require '[\"node:fs\" :as fs] '[clojure.edn :as edn])
    (if-not (fs/existsSync \"lang/guest-grammar.edn\")
      (do (println \"docs OK\") (js/process.exit 0))
      (do (println \"docs OK\" (:kotoba.lang.guest-grammar/profile-version (edn/read-string (fs/readFileSync \"lang/guest-grammar.edn\" \"utf8\")))) (js/process.exit 0)))"})

(defn self-check! [timeout-ms]
  (let [t (mkdtemp "grammar-floor-selfcheck-")
        repo (path/join t "repo")]
    (try
      (fs/mkdirSync (path/join repo "lang") #js {:recursive true})
      (fs/mkdirSync (path/join repo "scripts"))
      (fs/writeFileSync (path/join repo "lang" grammar-basename) fixture-grammar)
      (doseq [[n src] fixture-checkers] (fs/writeFileSync (path/join repo "scripts" n) src))
      (let [checkers (for [n (sort (keys fixture-checkers))]
                       {:name n :decider? (forbid-decider? (fixture-checkers n))})
            rs (measure-checkout repo checkers timeout-ms)
            by (into {} (map (juxt #(path/basename (:script %)) :verdicts) rs))
            want {"check-floorless.cljs" [:pass-on-missing-authority :pass-on-empty-forbid-set]
                  "check-floored.cljs" []
                  "check-version-only.cljs" [:pass-on-missing-authority]}
            ok (count (filter (fn [[n v]] (= v (by n))) want))
            ;; the fixture grammar text must actually mutate, or empty-forbid is baseline
            text-ok? (= 0 (count (re-seq #"eval" (empty-forbid-text fixture-grammar))))
            baseline-ok? (every? #(= :passes (classify (:baseline (:runs %)))) rs)
            ;; a comment-only mention is not a read; a call is
            filter-ok? (and (not (code-reads-grammar? ";; all seven copies of `lang/guest-grammar.edn`\n(println 1)"))
                            (code-reads-grammar? "(def p \"lang/guest-grammar.edn\")"))]
        {:ok (+ ok (if text-ok? 1 0) (if baseline-ok? 1 0) (if filter-ok? 1 0)) :want (+ (count want) 3)
         :got by :text-ok? text-ok? :baseline-ok? baseline-ok? :filter-ok? filter-ok?})
      (finally (fs/rmSync t #js {:recursive true :force true})))))

;; ── 走査 ──────────────────────────────────────────────────────────────────

(defn find-checkouts
  "root 配下、深さ 4 までで `scripts/` を持つディレクトリ。[{:repo :checkers :unrunnable}]。"
  [root]
  (letfn [(scripts-in [repo]
            (let [sd (path/join repo "scripts")]
              (when (try (.isDirectory (fs/statSync sd)) (catch :default _ false))
                (let [names (->> (array-seq (fs/readdirSync sd #js {:withFileTypes true}))
                                 (filter #(.isFile %)) (map #(.-name %)) sort)
                      hits (for [n names
                                 :when (re-find #"\.(cljs|bb|clj)$" n)
                                 :let [src (try (fs/readFileSync (path/join sd n) "utf8") (catch :default _ ""))]
                                 :when (code-reads-grammar? src)]
                             {:name n :decider? (forbid-decider? src) :runnable? (str/ends-with? n ".cljs")})]
                  (when (seq hits)
                    {:repo repo
                     :checkers (vec (filter :runnable? hits))
                     :unrunnable (vec (map :name (remove :runnable? hits)))})))))
          (go [d depth]
            (let [here (scripts-in d)
                  ents (if (< depth 4)
                         (try (fs/readdirSync d #js {:withFileTypes true}) (catch :default _ #js []))
                         #js [])]
              (concat (when here [here])
                      (mapcat (fn [e]
                                (let [n (.-name e)]
                                  (if (and (.isDirectory e) (not (skip-dirs n)) (not= "scripts" n))
                                    (go (path/join d n) (inc depth))
                                    [])))
                              (array-seq ents)))))]
    (vec (go root 0))))

(defn- opt [argv flag default]
  (let [i (.indexOf (clj->js argv) flag)]
    (if (neg? i) default (nth argv (inc i) default))))

(defn- rel [root p]
  (let [r (path/relative root p)] (if (str/blank? r) "." r)))

(defn- finding! [severity key detail]
  (println (str "FINDING\t" severity "\t" key "\t" detail)))

(defn -main [& argv]
  (let [timeout-val (opt argv "--child-timeout-ms" nil)
        args (->> argv (remove #(str/starts-with? % "--")) (remove #(and timeout-val (= % timeout-val))))
        root (path/resolve (or (first args) "."))
        findings? (boolean (some #{"--findings"} argv))
        timeout-ms (js/parseInt (opt argv "--child-timeout-ms" "120000") 10)]
    (when-not (try (.isDirectory (fs/statSync root)) (catch :default e
                                                        (println (str "NOTE\tstat failed: " (.-message e)))
                                                        false))
      (println "SCANNED\t0")
      (println (str "REFUSED\tno such directory: " root))
      (js/process.exit 2))
    (let [sc (self-check! timeout-ms)]
      (when-not (= (:ok sc) (:want sc))
        (println "SCANNED\t0")
        (println "REFUSED\tself-check failed: the runner cannot tell a checker that passes on a missing/empty grammar from one that refuses")
        (println (str "  got " (pr-str (:got sc)) " text-mutates=" (:text-ok? sc) " baseline-green=" (:baseline-ok? sc)))
        (js/process.exit 2))
      (println (str "SELF-CHECK\t" (:ok sc) "/" (:want sc))))
    (let [checkouts (find-checkouts root)
          unrunnable (mapcat :unrunnable checkouts)
          with-grammar (filter #(seq (grammar-files (:repo %))) checkouts)
          without-grammar (remove #(seq (grammar-files (:repo %))) checkouts)
          results (vec (mapcat (fn [{:keys [repo checkers]}] (measure-checkout repo checkers timeout-ms)) with-grammar))
          measured (filter :measured? results)
          bad (filter #(seq (:verdicts %)) measured)]
      (println (str "SCANNED\t" (count measured)))
      (when (str/includes? root "orgs")
        (println "NOTE\tcheckouts only; unchecked-out west projects are not scanned"))
      (println (str "CHECKOUTS\t" (count checkouts) "\tcheckers=" (reduce + (map (comp count :checkers) checkouts))
                    "\tunrunnable=" (count unrunnable) "\tno-grammar-file=" (count without-grammar)))
      (doseq [{:keys [repo unrunnable]} checkouts, n unrunnable]
        (println (str "NOTE\tunrunnable\t" (rel root repo) "/scripts/" n "\tbb script; not executed (bb is retired), counted but not measured")))
      (doseq [{:keys [repo checkers]} without-grammar]
        (println (str "NOTE\tno-grammar-file\t" (rel root repo) "\t" (str/join "," (map :name checkers))
                      "\tcheckout has no " grammar-basename "; a missing-grammar copy is the baseline, not a mutation")))
      (doseq [{:keys [script runs decider? verdicts measured?]} (sort-by :script results)]
        (let [cell (fn [v] (let [r (runs v)] (str (or (:exit r) "timeout") ":" (name (classify r)))))]
          (println (str (cond (not measured?) "unmeasured"
                              (seq verdicts) "floorless"
                              (not= :passes (classify (:baseline runs))) "baseline-red"
                              :else "floored")
                        "\t" (rel root script)
                        "\tbaseline=" (cell :baseline)
                        "\tmissing=" (cell :missing)
                        "\tempty-forbid=" (if decider? (cell :empty-forbid) "n/a")
                        (when (some #(pos? (:stubbed (runs %))) [:baseline :missing :empty-forbid]) "\tstubbed")
                        "\t" (last-line (:out (:missing runs)))))))
      (cond
        (zero? (reduce + (map (comp count :checkers) with-grammar)))
        (do (println (str "REFUSED\tno runnable scripts/*.cljs reading guest-grammar.edn found beneath " root
                          " (in a checkout that has the file); nothing to measure"))
            (js/process.exit 2))

        (zero? (count measured))
        (do (println "REFUSED\tevery grammar-reading checker timed out; nothing was measured")
            (js/process.exit 2))

        (seq bad)
        (do (when findings?
              (doseq [{:keys [script runs verdicts]} (sort-by :script bad)
                      v verdicts]
                (let [variant (if (= v :pass-on-missing-authority) :missing :empty-forbid)
                      r (runs variant)]
                  (finding! "warn" (str "grammar-floor:" (name variant) ":" (rel root script))
                            (str (rel root script) " exits 0 when every " grammar-basename " in the checkout is "
                                 (if (= variant :missing) "DELETED" "present with `:forbidden-heads #{}`")
                                 " and prints `" (last-line (:out r))
                                 ;; the count line, when there is one: `{:forbidden 0 ...}` beside a PASS
                                 ;; is the whole finding in one breath
                                 (when-let [c (first (filter #(re-find #"forbidden" %) (str/split-lines (str (:out r)))))]
                                   (str "` after `" (str/trim (subs c 0 (min 120 (count c))))))
                                 "` -- " (if (= variant :missing)
                                           "an authority that cannot be read is not an empty authority; refuse (exit 2) when the file is absent"
                                           "a forbid set of 0 makes every `forbidden head is classified` check vacuously true; treat 0 forbidden heads as a floor (exit 2), not a PASS"))))))
            (println (str "FINDINGS\t" (reduce + (map (comp count :verdicts) bad))))
            (js/process.exit 1))

        :else
        (do (println "CLEAN\t0") (js/process.exit 0))))))

(apply -main (vec *command-line-args*))
