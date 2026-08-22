#!/usr/bin/env nbb
;; threed-maturity-audit — 3D/CAD/CAM/render スタックの決定論的 fitness function。
;;
;; 判定するのは 2 段だけで、どちらも tree から測る（LLM も、行数も、人の点数も使わない）:
;;
;;   1. marker  — その軸が名指しする top-level `(def…` 名が実在するか
;;   2. behavior— 90-docs/maturity/probes/<id>.cljs を実際に走らせて不変条件を検査
;;
;; **marker だけ通った軸は :declared で止まり、:working には繰り上がらない。**
;; 測っていないものを緑にしないため（CLAUDE.md 2608136000 の 5 問、第 4 問
;; 「『飛ばした』と『合格した』が出力で区別できるか」）。
;; behavior が落ちた軸は :hollow —— 名前だけ在って動かない。これが最悪の状態で、
;; grep でも行数でも LLM 採点でも緑に見える。実測 2026-08-20 に 2 件見つかった。
;;
;;   nbb --classpath ".:scripts/nbb_compat" scripts/threed-maturity-audit.cljs
;;   nbb --classpath ".:scripts/nbb_compat" scripts/threed-maturity-audit.cljs --check
;;   … --axis :kernel/fillet-chamfer     # 1 軸だけ測る
;;   … --no-behavior                     # marker だけ（速い。ただし :declared 止まり）
;;
;; ## 実測: 全 64 軸で 290 秒（load 4.6、2026-08-24）
;;
;;     :cae/cfd  93.4s   :dcc/cloth-hair 45.4s   :cam/collision-check 40.0s
;;     :render/offline-pathtracer 31.3s   :render/denoise 20.3s   :kernel/fillet 12.8s
;;     残り 58 軸で 47 秒
;;
;; **この audit は元から高くなかった。高かったのはマシンである。** 同じ probe が
;; load 5 で 13 秒、load 100 で 91 秒（fillet）。全体でも load 70〜150 の測定では
;; 524〜1357 秒だったものが、load 5 では 290 秒に収まる。
;;
;; 3 tick にわたって「高い probe」を順位付けして削ったが、**その順位は競合の
;; 産物だった** —— 実際に削った 2 件（collision-check の板を半分、cloth-hair の
;; 振り子と compliance）はどちらも切り分けを保ったまま安くなっているので誤りでは
;; ないが、動機は水増しされていた。負荷を秒数の隣に書くようにして初めて見えた。
;; **これ以上は削らない。** 最大の `:cae/cfd` は docstring が「刻みを減らすと
;; L2 が 4.8% → 10.9%」と書いており、安くする道が主張を弱める道と一致している。
;;
;; ⚠ **これは fleet gate にできない。** 判定は `orgs/` 配下の checkout を読むが、
;; fleet が配るのはその repo の tree だけで `git ls-files orgs/` は 0 件である
;; （CLAUDE.md「gate が要求する入力が repo に無いことがある」）。gate 化すると
;; 入力不在で毎回 exit 3 になる。これは operator 側で回す道具として置く。
;;
;; exit 0 = 書けた / --check が一致  1 = --check が不一致
;;      3 = **答えられなかった**（catalog が読めない、checkout が 1 つも無い、
;;          走査ファイル 0 件）。0 でも 1 でもない値にするのは、沈黙が緑として
;;          蓄積するのを止めるため。

(ns threed-maturity-audit
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            ["fs" :as fs]
            ["os" :as os]
            ["path" :as path]
            ["child_process" :as cp]))

(def root (or (aget js/process.env "THREED_ROOT") (js/process.cwd)))
(def argv (vec (drop 2 (js->clj js/process.argv))))
(def check? (some #{"--check"} argv))
(def no-behavior? (some #{"--no-behavior"} argv))
(def only-axis (second (drop-while #(not= "--axis" %) argv)))

;; orgs/ は west 管理で、worktree からは元 checkout を指す必要がある。
;; THREED_ORGS で明示できる（既定は <root>/orgs）。
(def orgs-root (or (aget js/process.env "THREED_ORGS") (path/join root "orgs")))
(def catalog-file (path/join root "90-docs" "maturity" "threed-capability-axes.edn"))
(def out-file (path/join root "90-docs" "maturity" "threed-parity.datoms.edn"))
(def probe-dir (path/join root "90-docs" "maturity" "probes"))

(defn- pad [s n] (let [s (str s)] (str s (apply str (repeat (max 0 (- n (count s))) " ")))))

(defn refuse! [code msg]
  (binding [*print-fn* *print-err-fn*] (println (str "REFUSED\t" msg)))
  (js/process.exit code))

;; ── tree の読み取り ────────────────────────────────────────────────────────

(defn- exists? [p] (try (fs/existsSync p) (catch :default _ false)))

(defn- clj-files
  "path が dir なら配下の .clj/.cljc/.cljs を再帰収集、file ならそれ 1 件。
   test/ は除く（marker は production source に在ることを要求する）。"
  [p]
  (cond
    (not (exists? p)) []
    (.isDirectory (fs/statSync p))
    (->> (fs/readdirSync p)
         (mapcat (fn [e]
                   (let [c (path/join p e)]
                     (cond (= e ".git") []
                           (= e "test") []
                           (.isDirectory (fs/statSync c)) (clj-files c)
                           (re-find #"\.clj[cs]?$" e) [c]
                           :else []))))
         vec)
    (re-find #"\.clj[cs]?$" p) [p]
    :else []))

(def ^:private def-re #"(?m)^\(def[a-z]*\s+([A-Za-z0-9*!?<>+=_.'/-]+)")
;; multimethod で API を出す repo（cae-solver）向け。`solve` の dispatch 値を marker
;; として扱う —— そこでは `(defn fem-elastoplastic …)` は存在せず、`(defmethod
;; solver/solve :fem-elastoplastic …)` が能力の在処である。marker の綴りを間違えて
;; :absent と報告するのが、この audit で一番起きやすい嘘なので kind を分けて持つ。
(defn- dispatch-re
  "Dispatch values registered on one multimethod. `:axis/dispatch-of` names it
   (default `solve`, for cae-solver). The registry IS the capability claim in
   both places this is used —— `cae.solver/solve` and `brep.feature/apply-feature`
   —— so measuring the registered set measures the claim, not a restatement of it."
  [multi]
  (re-pattern (str "(?m)^\\(defmethod\\s+[A-Za-z0-9._/-]*" (or multi "solve")
                   "\\s+:([A-Za-z0-9*!?<>+-]+)")))

(defn- top-level-defs
  "ファイル群の marker 名の集合。行頭に固定するので、docstring やコメントの中の
   同名語は拾わない（grep との差はここ）。"
  [files kind multi]
  (let [re (if (= kind :dispatch) (dispatch-re multi) def-re)]
    (reduce (fn [acc f]
              (let [src (try (fs/readFileSync f "utf8") (catch :default _ ""))]
                (into acc (map second (re-seq re src)))))
            #{} files)))

(defn- loc [files]
  (reduce (fn [n f] (+ n (count (str/split-lines (try (fs/readFileSync f "utf8") (catch :default _ "")))))) 0 files))

(defn- test-file-count [repo-dir]
  (let [t (path/join repo-dir "test")]
    (if (exists? t)
      (count (filter #(re-find #"\.clj[cs]?$" %)
                     (loop [dirs [t] acc []]
                       (if (empty? dirs) acc
                           (let [d (first dirs)
                                 es (map #(path/join d %) (fs/readdirSync d))
                                 {ds true fsx false} (group-by #(.isDirectory (fs/statSync %)) es)]
                             (recur (into (rest dirs) ds) (into acc fsx)))))))
      0)))

;; ── behavior probe ────────────────────────────────────────────────────────

(defn- run-probe
  "probes/<id>.cljs を nbb で走らせる。契約は stdout 1 行:
     PROBE <id> PASS|FAIL|UNMEASURABLE <detail>
   契約に合わない出力・非ゼロの load エラーは **FAIL ではなく UNMEASURABLE**
   として扱い、stderr 本文を detail に残す（受け取ったエラー本文を捨てない）。"
  [id cp-dirs]
  (let [f (path/join probe-dir (str (name id) ".cljs"))]
    (if-not (exists? f)
      {:probe/status :no-probe :probe/detail "probe file not written yet"}
      (let [r (cp/spawnSync "nbb" (clj->js (concat ["--classpath" (str/join ":" cp-dirs)] [f]))
                            #js {:encoding "utf8" :cwd root :timeout 600000})
            out (str (.-stdout r)) err (str (.-stderr r))
            line (first (filter #(str/starts-with? % "PROBE ") (str/split-lines out)))]
        (cond
          ;; spawnSync kills a probe that exceeds :timeout and leaves stdout and
          ;; stderr EMPTY. Without this branch that arrives as "produced no PROBE
          ;; line" with nothing after `stderr=`, which reads like a broken probe
          ;; rather than a machine that was too busy to finish one. Measured
          ;; 2026-08-21: the CFD probe (4000 LBM steps) times out at load ~100 and
          ;; its axis silently left the working column.
          (some? (.-signal r))
          {:probe/status :unmeasurable
           :probe/detail (str "probe was killed by signal " (.-signal r)
                              " (timeout, or the machine ran out of room) — "
                              "this is NOT a failed invariant")}
          (nil? line)
          {:probe/status :unmeasurable
           :probe/detail (str "probe produced no PROBE line; stderr="
                              (str/trim (subs err 0 (min 400 (count err)))))}
          (str/includes? line " PASS ") {:probe/status :pass :probe/detail (str/trim line)}
          (str/includes? line " FAIL ") {:probe/status :fail :probe/detail (str/trim line)}
          :else {:probe/status :unmeasurable :probe/detail (str/trim line)})))))

;; ── 1 軸の測定 ────────────────────────────────────────────────────────────

(defn measure [axis]
  (let [repo (:axis/repo axis)
        repo-dir (path/join orgs-root repo)
        paths (map #(path/join repo-dir %) (:axis/paths axis))
        files (vec (distinct (mapcat clj-files paths)))
        ;; Some repos (webgpu has ~20 sibling git deps) cannot be put on a probe
        ;; classpath by naming directories — chasing the transitive set by hand
        ;; ends in a probe that reports UNMEASURABLE for a reason that has
        ;; nothing to do with the capability. `:axis/probe-classpath-from-deps`
        ;; asks the repo's own deps.edn instead. It costs one JVM start, which
        ;; is why it is opt-in rather than the default.
        resolved (when (:axis/probe-classpath-from-deps axis)
                   (try (let [raw (str/trim (str (cp/execSync "clojure -Spath"
                                                               #js {:cwd repo-dir :encoding "utf8"
                                                                    :stdio #js ["ignore" "pipe" "ignore"]})))]
                          ;; `-Spath` emits the repo's OWN paths relative ("src",
                          ;; "resources"). nbb resolves those against ITS cwd, which
                          ;; is the superproject — so they silently miss and the probe
                          ;; fails to find the namespace it is about to test.
                          (str/join ":" (map #(if (str/starts-with? % "/")
                                                % (path/join repo-dir %))
                                             (str/split raw #":"))))
                        (catch :default _ nil)))
        cp-dirs (concat [(path/join repo-dir "src")]
                        (map #(path/join orgs-root %) (:axis/probe-extra-cp axis))
                        (when resolved [resolved]))]
    (cond
      (not (exists? repo-dir))
      (merge axis {:axis/status :unmeasurable :axis/found [] :axis/missing (:axis/markers axis)
                   :axis/files 0 :axis/loc 0 :axis/tests 0
                   :axis/why "checkout が無い（west 未取得）。absent ではない —— 測れていない"})

      (empty? files)
      (merge axis {:axis/status :unmeasurable :axis/found [] :axis/missing (:axis/markers axis)
                   :axis/files 0 :axis/loc 0 :axis/tests (test-file-count repo-dir)
                   :axis/why (str "宣言された :axis/paths が 1 ファイルも解決しない: "
                                  (str/join ", " (:axis/paths axis)))})

      :else
      (let [defs (top-level-defs files (:axis/marker-kind axis :def) (:axis/dispatch-of axis))
            markers (:axis/markers axis)
            found (vec (filter defs markers))
            missing (vec (remove defs markers))
            probe (if (or no-behavior? (nil? (:axis/behavior axis)))
                    {:probe/status :no-probe
                     :probe/detail (if no-behavior? "--no-behavior" "この軸に behavior probe を書いていない")}
                    (run-probe (:axis/behavior axis) cp-dirs))
            status (cond
                     (empty? found) :absent
                     (seq missing)  :thin
                     (= :pass (:probe/status probe)) :working
                     (= :fail (:probe/status probe)) :hollow
                     ;; A probe that could not run is NOT the same as an axis with
                     ;; no probe. Collapsing them into :declared is this tool doing
                     ;; the very thing it exists to catch — reporting "could not
                     ;; measure" as something else. Found in it 2026-08-21.
                     (= :unmeasurable (:probe/status probe)) :unmeasurable
                     :else :declared)]
        (merge axis
               {:axis/status status :axis/found found :axis/missing missing
                :axis/files (count files) :axis/loc (loc files)
                :axis/tests (test-file-count repo-dir)}
               probe)))))

;; ── datoms への射影 ───────────────────────────────────────────────────────

(defn ->datom [i m]
  {:db/id (- (inc i))
   :source/dataset "threed-parity"
   :parity/subject (:axis/repo m)
   :parity/target (:axis/target m)
   :parity/segment (name (:axis/segment m))
   :parity/axis (str (:axis/id m))
   :parity/label (:axis/label m)
   :parity/weight (:axis/weight m)
   :parity/status (str (:axis/status m))
   :parity/markers-found (count (:axis/found m))
   :parity/markers-required (count (:axis/markers m))
   :parity/markers-missing (pr-str (:axis/missing m))
   :parity/behavior-probe (if (:axis/behavior m) (name (:axis/behavior m)) "none")
   :parity/behavior-status (str (:probe/status m))
   :parity/evidence (str (:probe/detail m) (when (:axis/why m) (str " | " (:axis/why m))))
   :parity/loc (:axis/loc m)
   :parity/files (:axis/files m)
   :parity/test-files (:axis/tests m)
   ;; 測るのにかかった秒数。**絶対値として引用しない** —— このマシンは並行
   ;; agent で load が 70〜300 の間を動く。高い probe を見つけるための相対値で
   ;; あって、ベンチマークではない。
   :parity/seconds (:axis/probe-seconds m)})

(defn -main []
  (when-not (exists? catalog-file)
    (refuse! 3 (str "catalog が無い: " catalog-file)))
  (let [catalog (try (edn/read-string (fs/readFileSync catalog-file "utf8"))
                     (catch :default e (refuse! 3 (str "catalog が読めない: " (.-message e)))))
        axes (cond->> (:catalog/axes catalog)
               only-axis (filter #(= only-axis (str (:axis/id %)))))
        _ (when (empty? axes) (refuse! 3 "測る軸が 0 件（--axis の指定が catalog に無い可能性）"))
        ;; 1 軸ずつ、**測り終えるたびに** stderr へ出す。以前は全部終わるまで
        ;; 何も出さなかったので、実測 28 分無音のとき「遅い」のか「止まった」のかを
        ;; 区別できなかった —— 沈黙が進行中と同じ顔をする。所要秒数も一緒に出す
        ;; ので、どの probe が高いかが測らずに分かる。
        load-before (first (.loadavg os))
        measured (vec (map-indexed
                       (fn [i axis]
                         (let [t0 (.now js/Date)
                               m (measure axis)
                               dt (/ (- (.now js/Date) t0) 1000.0)]
                           (binding [*print-fn* *print-err-fn*]
                             (println (str "  [" (inc i) "/" (count axes) "] "
                                           (pad (:axis/id axis) 34)
                                           (pad (:axis/status m) 14)
                                           (.toFixed dt 1) "s")))
                           (assoc m :axis/probe-seconds (js/parseFloat (.toFixed dt 2)))))
                       axes))
        by-status (frequencies (map :axis/status measured))
        scanned-files (reduce + (map :axis/files measured))
        resolved-repos (count (distinct (map :axis/repo (remove #(= :unmeasurable (:axis/status %)) measured))))]

    ;; evidence floor —— 走査 0 件を「違反 0 件 = 合格」にしない
    (when (zero? scanned-files)
      (refuse! 3 (str "走査ファイル 0 件。orgs/ が解決していない（THREED_ORGS=" orgs-root "）")))
    (when (zero? resolved-repos)
      ;; 「解決しなかった」の他に「全部 :unmeasurable だった」でもここに来る ——
      ;; `--axis` で 1 軸だけ測り、その probe が UNMEASURABLE を返した場合が
      ;; それ（実測 2026-08-23、realtime-gpu の break check）。どちらであれ
      ;; **報告できる測定が 0 件**なので拒否するのは正しいが、理由を 1 つに
      ;; 決め打ちすると読み手が checkout を疑って時間を使う。
      (refuse! 3 (str "報告できる測定が 0 件（" (count measured)
                      " 軸すべてが :unmeasurable か、checkout が 1 つも解決しなかった）")))

    (let [datoms (vec (map-indexed ->datom measured))
          coverage {:db/id -9999 :source/dataset "threed-parity"
                    :coverage/kind "threed-parity-coverage"
                    :coverage/axes-total (count measured)
                    :coverage/axes-unmeasurable (get by-status :unmeasurable 0)
                    :coverage/axes-with-behavior-probe (count (filter :axis/behavior measured))
                    :coverage/scanned-files scanned-files
                    :coverage/resolved-repos resolved-repos
                    :coverage/scope (:catalog/measured-scope catalog)
                    :coverage/note (str "status が :declared の軸は **未検証**。実装済みとして数えない。"
                                        ":absent は走査した checkout の中に無いという意味であって、"
                                        "west 4,000+ project の中に無いという意味ではない。")}
          body (str ";; threed-parity.datoms.edn — **生成物。手で編集しない。**\n"
                    ";; 再生成: nbb --classpath \".:scripts/nbb_compat\" scripts/threed-maturity-audit.cljs\n"
                    ";; 検査:   … --check\n"
                    ";; 語彙（判断が入る唯一の場所）: 90-docs/maturity/threed-capability-axes.edn\n"
                    ";; 設計: ADR-2608200100\n;;\n"
                    ";; :parity/status —\n"
                    ";;   working      marker あり + behavior probe PASS\n"
                    ";;   hollow       marker あり + behavior probe FAIL（名前だけ在って動かない）\n"
                    ";;   declared     marker あり + probe 無し = **未検証**。実装済みと数えない\n"
                    ";;   thin         marker が一部だけ（:parity/markers-missing に名前）\n"
                    ";;   absent       走査した checkout に marker ゼロ\n"
                    ";;   unmeasurable 測れなかった。pass にも fail にも数えない\n\n"
                    "[" (str/join "\n " (map pr-str (conj datoms coverage))) "]\n")]
      ;; --axis は部分測定なので datoms を **書かない** —— 1 軸だけの結果で
      ;; 55 軸分のデータセットを上書きすると、静かに切り詰めた面を「現在値」として残す。
      (if (and only-axis (not check?))
        (println "PARTIAL\t--axis 指定のため threed-parity.datoms.edn は書き換えない")
      (if check?
        ;; **秒数を除いて比べる。** `:parity/seconds` は実行ごとに揺れるので、
        ;; 素朴な文字列比較だと `--check` が**常に** STALE を出す —— 常に赤い
        ;; 検査は、常に緑の検査と同じだけ無内容である（CLAUDE.md 2608102000）。
        ;; 比べたいのは測定された状態（status / evidence / marker）であって、
        ;; そのとき何秒かかったかではない。
        (let [strip #(str/replace (or % "") #",? ?:parity/seconds [0-9.]+" "")
              cur (when (exists? out-file) (str (fs/readFileSync out-file "utf8")))]
          (if (= (strip cur) (strip body))
            (do (println "FRESH\tthreed-parity.datoms.edn は tree と一致（秒数の差は無視）")
                (js/process.exit 0))
            (do (println "STALE\tthreed-parity.datoms.edn が tree と一致しない。再生成が要る")
                (js/process.exit 1))))
        (fs/writeFileSync out-file body)))

      ;; ── 報告 ──
      ;; 負荷を秒数の隣に置く。**このマシンは並行 agent で load が 17〜300 の間を
    ;; 動く**ので、秒数だけを見て「速くなった/遅くなった」と読ませない。実測
    ;; 2026-08-24: 同じ probe が load 17 で 200 秒、load 100 で 541 秒。
    ;; ⚠ `scripts/maturity-loop/run.cljs`（mutation harness）と**同時に走らせない**
    ;; こと —— あちらは repo の全 suite を繰り返し回すので、両方回すと双方の
    ;; 秒数が意味を失う。あちらは resource-guard の build lock を通るが、
    ;; この audit は通らない（64 本の probe を lock 越しに直列化すると、
    ;; 長い mutation 実行中は永久に測れなくなる）。
    (binding [*print-fn* *print-err-fn*]
      (println (str "  load1 " (.toFixed load-before 1) " → "
                    (.toFixed (first (.loadavg os)) 1)
                    "（秒数はこの負荷での値。絶対値として引用しない）")))
    (println (str "SCANNED\taxes=" (count measured) "\trepos=" resolved-repos
                    "\tfiles=" scanned-files))
      (println (str "STATUS\tworking=" (get by-status :working 0)
                    "\thollow=" (get by-status :hollow 0)
                    "\tdeclared=" (get by-status :declared 0)
                    "\tthin=" (get by-status :thin 0)
                    "\tabsent=" (get by-status :absent 0)
                    "\tunmeasurable=" (get by-status :unmeasurable 0)))
      (println (str "PROBES\twritten=" (count (filter :axis/behavior measured))
                    "/" (count measured)
                    "\t(probe の無い軸は :declared 止まり —— 実装済みと数えない)"))
      (println)
      (doseq [[seg group] (sort-by key (group-by :axis/segment measured))]
        (println (str "── " (name seg) " ── " (get-in catalog [:catalog/segments seg])))
        (doseq [m (sort-by (juxt (comp - :axis/weight) (comp str :axis/id)) group)]
          (println (str "  " (pad (name (:axis/status m)) 14)
                        (pad (str "w" (:axis/weight m)) 4)
                        (str (:axis/id m))
                        "  [" (count (:axis/found m)) "/" (count (:axis/markers m)) " markers]"
                        (when (seq (:axis/missing m))
                          (str " missing=" (str/join "," (:axis/missing m))))
                        (when (= :fail (:probe/status m)) (str "  ⚠ " (:probe/detail m))))))
        (println)))))

(-main)
