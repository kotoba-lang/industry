#!/usr/bin/env nbb
;; SessionStart フック: **何かが実際に読んでいる checkout** が、自分の remote の
;; default branch からどれだけ遅れているかを、毎セッション冒頭で報告する。
;;
;; なぜ要るか (ADR-2608136800):
;;   2026-08-13 の 1 日で、stale な checkout を正本として読む誤りが 4 回起きた。
;;   うち 1 件(ADR-2608135200 初版)は 127 commit 遅れた `orgs/kotoba-lang/kotoba` の
;;   deps.edn を読んで「依存が 988 commit 遅れている」と書き、その数字が 2 つの
;;   agent 指示に転記された。実際の差は 51 commit で、repo は既に直っていた。
;;   CLAUDE.md は「checkout・west pin・repo の main は別物」と何度も書いており、
;;   その日だけで 3 回言い直された。**それでも守られなかったので、測って出す。**
;;
;; 出力の設計方針 —— **短さが機能である。**
;;   4,415 checkout のうち 532 が behind だが、532 行を出す hook は誰も読まない。
;;   読まれない出力は、出さないのと同じ失敗である。だから
;;   **「何かがそれを解決先として読んでいる」checkout だけ**を、しかも
;;   reader の多い順に上位だけ測って、worst 数件を出す。
;;   遅れていること自体は害ではない —— 誰かが読んでいて初めて害になる。
;;
;; **fetch はしない。** hook は速く offline でなければならない。ここが報告するのは
;; 「最後の fetch が見た値」であって「今 GitHub にある値」ではなく、その旨と
;; 最終 fetch からの経過時間を必ず併記する。**測定器自身が stale であることを
;; 隠さない** —— それを隠したら、この hook はまさに直そうとしている誤りを犯す。
;;
;; stdout に書く理由: `.claude/settings.json` の SessionStart 登録は全部
;; `2>/dev/null` を付けている。**stderr に書くと黙って消える。**
;; (nbb では `binding [*out* *err*]` は何もしない —— 束縛しても stdout に出る。
;;  同じ日にこの 2 つを取り違えて stderr 修正が無言で無効化された事例がある。)
;;
;; 入力: `manifest/checkout-consequence.edn`(生成物)。
;;   生成は `nbb scripts/checkout-staleness.cljs --write-consequence`。
;;   このファイルが無ければ **何も出さずに exit 0**(fail-open)。
;;
;; 手動実行: `nbb .claude/hooks/session-start-checkout-staleness.cljs [<dir>]`

(require '[clojure.string :as str]
         '[clojure.edn :as edn])

(def fs   (js/require "node:fs"))
(def path (js/require "node:path"))
(def cp   (js/require "node:child_process"))

;; 実測する上限。reader の多い順。**全件を毎回測らない。**
(def max-measured 30)
;; 報告に並べる上限。
(def max-listed 5)
;; 予算。超えたら測れた分だけを「部分的」と申告して出す。
(def budget-ms 9000)

(def deadline (+ (js/Date.now) budget-ms))
(defn out-of-time? [] (> (js/Date.now) deadline))

(defn slurp* [p] (try (.readFileSync fs p "utf8") (catch :default _ nil)))
(defn exists? [p] (try (.existsSync fs p) (catch :default _ false)))
(defn mtime-ms [p] (try (.-mtimeMs (.statSync fs p)) (catch :default _ nil)))

(defn resolve-gitdir [c]
  (let [g (.join path c ".git")]
    (try
      (let [st (.lstatSync fs g)]
        (cond
          (.isDirectory st) g
          (.isFile st) (when-let [m (re-find #"(?m)^gitdir:\s*(.+)$" (or (slurp* g) ""))]
                         (.resolve path (.dirname path g) (str/trim (second m))))
          :else nil))
      (catch :default _ nil))))

(defn common-dir [gitdir]
  (if-let [t (slurp* (.join path gitdir "commondir"))]
    (.resolve path gitdir (str/trim t))
    gitdir))

(defn packed-refs [cdir]
  (if-let [txt (slurp* (.join path cdir "packed-refs"))]
    (into {} (keep (fn [line]
                     (when-not (or (str/blank? line)
                                   (str/starts-with? line "#")
                                   (str/starts-with? line "^"))
                       (let [[sha ref] (str/split (str/trim line) #"\s+" 2)]
                         (when (and sha ref) [ref sha]))))
                   (str/split-lines txt)))
    {}))

(defn resolve-ref [cdir packed ref]
  (let [loose (slurp* (.join path cdir ref))]
    (cond
      (and loose (str/starts-with? (str/trim loose) "ref: "))
      (let [t (str/trim (subs (str/trim loose) 5))]
        (or (some-> (slurp* (.join path cdir t)) str/trim not-empty) (get packed t)))
      (and loose (not (str/blank? loose))) (str/trim loose)
      :else (get packed ref))))

(defn head-sha [gitdir cdir packed]
  (let [h (some-> (slurp* (.join path gitdir "HEAD")) str/trim)]
    (cond
      (nil? h) nil
      (str/starts-with? h "ref: ") (resolve-ref cdir packed (str/trim (subs h 5)))
      :else h)))

(defn remote-candidates
  "remote の default branch について、**手元にある観測を全部**返す。
   west は名前付き remote ではなく URL 直指定で fetch するので
   `refs/remotes/*` はしばしば取り残され、FETCH_HEAD だけが進む。
   片方だけを見ると、進んだ checkout が『ahead』に見える(実測 2026-08-13、
   com-* 系 ~680 repo でこの誤りが出た)。"
  [cdir packed]
  (let [tracking (for [r ["origin" "main"]  ; 稀に refs/remotes/<r>/HEAD が在る
                       :let [t (some-> (slurp* (.join path cdir "refs/remotes" r "HEAD")) str/trim)]
                       :when (and t (str/starts-with? t "ref: "))]
                   (resolve-ref cdir packed (str/trim (subs t 5))))
        fh (->> (str/split-lines (or (slurp* (.join path cdir "FETCH_HEAD")) ""))
                (keep (fn [line]
                        (let [[sha _ desc] (str/split line #"\t" 3)]
                          (when (and sha desc (re-find #"^[0-9a-f]{40}$" sha)
                                     (re-find #"^branch\s+'(main|master)'\s+of\s" (str/trim desc)))
                            sha))))
                first)]
    (distinct (remove nil? (concat tracking [fh])))))

(defn git-lr
  "`rev-list --left-right --count HEAD...<sha>` → {:ahead n :behind n} か nil。"
  [dir head sha]
  (try
    (let [out (.execFileSync cp "git"
                             (clj->js ["-C" dir "rev-list" "--left-right" "--count"
                                       (str head "..." sha)])
                             #js {:encoding "utf8" :timeout 6000 :stdio #js ["ignore" "pipe" "ignore"]})
          [a b] (str/split (str/trim (or out "")) #"\s+")]
      (when (and a b) {:ahead (js/parseInt a) :behind (js/parseInt b)}))
    (catch :default _ nil)))

(defn grafts [cdir]
  (let [f (.join path cdir "shallow")]
    (if-not (exists? f) 0
        (count (remove str/blank? (str/split-lines (or (slurp* f) "")))))))

(defn measure
  "1 checkout を測る。shallow なら **距離を出さない** —— shallow clone は
   ancestry に誤った答えを、正しい答えと同じ顔で返す(ADR-2608124400)。"
  [root {:keys [path* readers]}]
  (let [c (.join path root path*)]
    (when-let [gitdir (resolve-gitdir c)]
      (let [cdir   (common-dir gitdir)
            packed (packed-refs cdir)
            h      (head-sha gitdir cdir packed)
            g      (grafts cdir)
            fh-ms  (mtime-ms (.join path cdir "FETCH_HEAD"))]
        (cond
          (pos? g) {:label path* :readers readers :shallow? true :fetch-head-ms fh-ms}
          (nil? h) nil
          :else
          (let [cands (remote-candidates cdir packed)]
            (when (seq cands)
              (if (every? #(= h %) cands)
                {:label path* :readers readers :behind 0 :ahead 0 :fetch-head-ms fh-ms}
                ;; 候補ごとに測って **いちばん進んだ観測**を採る(behind 最大)
                (let [rs (keep #(git-lr c h %) cands)]
                  (when (seq rs)
                    (let [best (first (sort-by (juxt (comp - :behind) :ahead) rs))]
                      (merge {:label path* :readers readers :fetch-head-ms fh-ms} best))))))))))))

(defn ago [ms]
  (if (nil? ms) "never fetched"
      (let [d (/ (- (js/Date.now) ms) 86400000.0)]
        (cond (< d 1) (str (js/Math.round (* d 24)) "h") (< d 400) (str (js/Math.round d) "d")
              :else (str (.toFixed (/ d 365) 1) "y")))))

(defn done!
  ([] (js/process.exit 0))
  ([msg]
   ;; **stdout に書く。** SessionStart 登録は 2>/dev/null なので stderr は消える。
   (println (js/JSON.stringify
              (clj->js {:systemMessage msg
                        :hookSpecificOutput {:hookEventName "SessionStart"
                                             :additionalContext msg}})))
   (js/process.exit 0)))

(try
  (let [root (or (first *command-line-args*)
                 (not-empty (str (or js/process.env.CLAUDE_PROJECT_DIR "")))
                 ".")
        f    (.join path root "manifest" "checkout-consequence.edn")
        data (when (exists? f)
               (try (edn/read-string (slurp* f)) (catch :default _ nil)))]
    (when-not (map? data) (done!))

    (let [entries (->> (:entries data)
                       (map (fn [e] {:path* (:path e) :readers (count (:readers e))}))
                       (sort-by (comp - :readers))
                       (take max-measured))
          measured (loop [es (seq entries), acc []]
                     (if (or (nil? es) (out-of-time?))
                       {:rows acc :complete? (nil? es) :scanned (count acc)}
                       (recur (next es) (if-let [m (measure root (first es))] (conj acc m) acc))))
          rows  (:rows measured)
          ;; **reader の数は gate であって重みではない。**
          ;; 害の大きさは「そこを正本として読んだときに何 commit 間違えるか」
          ;; = behind の値そのもの。reader 数を掛けると、多数に読まれている
          ;; -18 の repo が -127 の repo を押し下げる —— 実際に一度そう書いて、
          ;; **ADR-2608135200 の事故そのもの(`kotoba` が -127)が上位 5 件から
          ;; 消えた。** 捕まえたい事例を捕まえない順位付けは、順位付けではない。
          bad   (->> rows (filter #(pos? (or (:behind %) 0)))
                     (sort-by (fn [r] [(- (:behind r)) (- (:readers r))])))
          ;; ただし「最も広く読まれている behind」は別枠で必ず出す ——
          ;; 遅れは小さくても波及は最大なので、behind 順だけだと落ちる。
          most-read (first (sort-by (comp - :readers)
                                    (filter #(pos? (or (:behind %) 0)) rows)))
          shal  (filter :shallow? rows)
          s     (:summary data)]

      (when (and (empty? bad) (empty? shal)) (done!))

      (let [shown (take max-listed bad)
            extra (- (count bad) (count shown))
            oldest (->> rows (keep :fetch-head-ms) sort first)]
        (done!
          (str/join
            "\n"
            (concat
              [(str "⚠ checkout staleness: " (count bad) " of the " (:scanned measured)
                    " most-read checkouts are behind their **last-fetched** default branch"
                    (when-not (:complete? measured) "  (⚠ 時間予算で打ち切り — 部分的)"))
               "  no fetch was performed. これは「最後の fetch が見た値」であって"
               (str "  「今 GitHub にある値」ではない(この中で最も古い fetch は "
                    (ago oldest) " 前)。")
               ""]
              (map (fn [r] (str "  - " (:label r) "  -" (:behind r)
                                (when (pos? (or (:ahead r) 0)) (str " +" (:ahead r)))
                                "   ← " (:readers r) " reader(s), fetched " (ago (:fetch-head-ms r)) " ago"))
                   shown)
              (when (pos? extra) [(str "  … 他 " extra " 件")])
              (when (and most-read (not (some #(= (:label %) (:label most-read)) shown)))
                [(str "  最も広く読まれている behind: " (:label most-read) "  -" (:behind most-read)
                      "   ← " (:readers most-read) " reader(s)")])
              (when (seq shal)
                [(str "  - shallow のため距離不明: "
                      (str/join ", " (map :label (take 3 shal))))])
              [""
               "checkout・west pin・repo の main は 3 つの別物。**結論を出す前に origin/main を読む**"
               "(ADR-2608136800。127 commit 遅れた checkout を正本として読んだ実事故が ADR-2608135200)。"]
              (when s
                [(str "  前回の全件測定: " (count (:entries data)) " 個が読まれており、"
                      (:total s) " checkout 中 behind " (:behind s)
                      " / dirty " (:dirty s) " / 既定 branch 上で ahead " (:ahead-on-default-branch s)
                      " / shallow " (:shallow s) "。")])
              ["  全件を測り直す: nbb scripts/checkout-staleness.cljs --write-consequence"
               "  直す: dirty/ahead は git-cleanup-conflict へ。clean で誰も読んでいないものだけ"
               "        `west update --fetch smart <name>`(拒否はしても破棄はしない)。"]))))))
  (catch :default _ nil))
(js/process.exit 0)
