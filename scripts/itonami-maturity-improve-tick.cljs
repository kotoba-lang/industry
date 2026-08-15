#!/usr/bin/env nbb
;; scripts/itonami-maturity-improve-tick.cljs — 成熟度を**上げる** loop の入力を測る
;; （superproject ADR-2608080000）。observe-only。
;;
;; ## 姉妹との住み分け
;;
;;   com.gftd.itonami-maturity-tick   決定論的欠陥クラス（Tier 1）を無人で直す。
;;                                    モデルは居ない。**2026-08-05 時点で枯れており**
;;                                    1,398 repo を 6h ごとに走査して findings 0。
;;   com.gftd.itonami-os-connect      まだ繋がっていない産業を OS に 1 本繋ぐ（横）。
;;   com.gftd.itonami-maturity-improve  ← これ。**軸そのものを上げる**（縦）。
;;
;; ADR-2608052000 は「どこに工数を積むと fleet 合計が最大に伸びるか」を
;; 計算したが、**それを定期的に実行するものが無かった**。この tick はその
;; 計算結果を毎周読み直し、次の 1 手の対象と軸を名指しする。
;;
;; ## 何を出すか
;;
;;   1. lane —— substrate か breadth か（ADR-2608052000 決定 2 の 2〜5% 帯を
;;      **ledger の実績から**維持する。固定スケジュールにしない）
;;   2. 対象 repo と、その**一番弱い軸**（何をすればよいかまで降ろす）
;;   3. 計測値の鮮度（古ければ、まず測り直すのが次の 1 手）
;;
;; ## 不変条件
;;
;;   - **何も書かない・deploy しない・git を触らない。** 測って言うだけ。
;;   - 捏造ゼロ。読めなければ :unknown。0 に丸めない。
;;   - 順位は毎周読み直す。ADR-2608052000 決定 1 が明示的に
;;     「この順位は作業のたびに変わる。固定リストとして扱わない」と書いている。
;;
;; 使い方:
;;   nbb --classpath ".:scripts/nbb_compat" scripts/itonami-maturity-improve-tick.cljs
;;
;; exit 0 常に（監視であって gate ではない）。

(ns itonami-maturity-improve-tick
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [scripts.itonami-maturity-freshness :as fresh]))

(def fs (js/require "fs"))
(def os (js/require "os"))
(def cp (js/require "child_process"))

(def home (.homedir os))
(def root (or (aget (.-env js/process) "COM_JUNKAWASAKI_ROOT")
              (str home "/github/com-junkawasaki")))
(def datoms-file (str root "/90-docs/system-dynamics/itonami-maturity.datoms.edn"))
(def archived-file (str root "/manifest/archived-repos.edn"))
(def ledger-file (str home "/.gftd/itonami-maturity-improve.ledger.edn"))

;; 計測値がこれより古ければ、次の 1 手は「作業」ではなく「測り直し」。
;; 古い順位に従って働くのは、測っていないものを測ったことにするのと同じ。
;;
;; **これは床であって、唯一の判定ではない。** 日数だけを見ていた版は、同じ日に
;; 着地した仕事を構造的に見落とした（`scripts/itonami_maturity_freshness.cljs`
;; の ns docstring に実測を書いた）。本当の問いは「計測はどれだけ古いか」では
;; なく「計測は **この loop 自身が直前に着地させた仕事** を見ているか」で、
;; それは時計ではなく ledger にしか書いていない。
(def stale-after-days 7)

;; ADR-2608052000 決定 2。専用フェーズを切らず、この帯を常時維持する。
;; ゼロにすると t=24 で 13.8% 失い、10% を超えると過投資（17 repo は飽和済み）。
(def substrate-share-floor 0.02)

(defn log! [& xs] (println (str/join " " (map str xs))))
(defn- slurp* [p] (try (str (.readFileSync fs p "utf8")) (catch :default _ nil)))

(defn- sh [cmd args]
  (try (let [r (.spawnSync cp cmd (clj->js args)
                           #js {:encoding "utf8" :cwd root :timeout 60000})]
         {:code (aget r "status") :out (str (aget r "stdout"))})
       (catch :default e {:code nil :out (str e)})))

;; ── 計測値 ───────────────────────────────────────────────────────────────────

(def datoms
  (when-let [s (slurp* datoms-file)]
    (try (edn/read-string s) (catch :default _ nil))))

(def ^:private scan-at-ms
  "計測が repo を読んだ時刻（datoms の `:scan/at`）。無ければ nil。

  **datoms の commit 時刻ではこの問いに答えられない。** commit は scan の後に
  起きるので、その窓に着地した仕事を『計測は見ている』と読んでしまう ——
  まさにこの検査が捕まえるはずの形である。

  実測 2026-08-15、この関数が生まれた周: redelivery の operator-quickstart は
  14:06:31Z に着地し、datoms は 14:09:52Z に commit された。commit 時刻を基準に
  すると `head-ms < generated-at` となり **見落とす**。`:scan/at`（14:03:30Z）を
  基準にすると捕まる —— そしてその datoms の redelivery 行は実際に
  `axis-docs 0` のままだった（つまり計測は本当に見ていない）。

  `:scan/at` は dynamics が走った時刻で、scan が repo を読んだ時刻よりわずかに
  後である。したがってこの基準も過少報告する側に倒れる —— 安全な向きは
  こちらなので、それでよい。"
  (when-let [s (some :scan/at datoms)]
    (fresh/parse-instant s)))

(defn- root-reads-behind-remote?
  "Is the datoms file this tick just read the one at the remote tip?

   Asked of git directly rather than inferred from commit distance, because the
   question that matters is not `how far behind is this checkout` but `is the FILE
   I read the landed one`. A checkout can be 40 commits behind and still hold the
   identical datoms; it can be 1 commit behind and hold a stale one.

   Why this exists: `root` is COM_JUNKAWASAKI_ROOT or $HOME/github/com-junkawasaki
   and is NOT cwd, so a shared checkout parked on another branch makes this tick
   read an old measurement, report :blind-to-own-work, and advise a remeasure that
   lands on main and changes nothing it can see. Measured 2026-08-15: two rounds
   spent in that loop.

   Returns nil, not false, when git cannot answer -- no remote, detached, offline.
   nil keeps the old behaviour, because claiming the root is fine when we could not
   look is the failure this whole file is about."
  []
  (let [head (sh "git" ["symbolic-ref" "--quiet" "--short" "HEAD"])
        remote-head (sh "git" ["symbolic-ref" "--quiet" "--short" "refs/remotes/origin/HEAD"])
        default (or (when (zero? (:code remote-head))
                      (last (str/split (str/trim (:out remote-head)) #"/")))
                    "main")
        rel (str "90-docs/system-dynamics/itonami-maturity.datoms.edn")
        ;; does the working tree's copy differ from the remote tip's copy?
        d (sh "git" ["diff" "--quiet" (str "origin/" default) "--" rel])]
    (cond
      ;; git could not answer -- say so by returning nil rather than a verdict
      (nil? (:code d)) nil
      (not (zero? (:code head))) nil
      (= 0 (:code d)) false
      (= 1 (:code d)) true
      :else nil)))

(defn- generated-at
  "datoms が最後に commit された時刻（epoch ms）。**ファイルの mtime では
  ない** —— checkout し直しただけで新しく見えてしまう。

  読めなければ `:unknown` を返す。`freshness` はそれを stale とも fresh とも
  言わずに上げてくる。"
  []
  (let [{:keys [code out]} (sh "git" ["log" "-1" "--format=%ct" "--"
                                      "90-docs/system-dynamics/itonami-maturity.datoms.edn"])]
    (if (and (= 0 code) (seq (str/trim out)))
      (* 1000 (js/parseInt (str/trim out) 10))
      :unknown)))

;; ── archived な repo は候補から外す（測定からは外さない）────────────────────
;;
;; **archived な repo はこの順位の常連になる。** archived = 開発が止まっている
;; = 全軸が低い = 「伸びしろが最大」と読まれる。しかし GitHub 側が read-only
;; なので push できず、その周はまるごと空振りする。
;;
;; 実測 2026-08-11: fleet 最下位 3 本（com-etzhayyim-gov_municipality /
;; com-etzhayyim-infra_utility_connect / ai-gftd-kaisya）が**全部 archived**で、
;; loop は 3 周連続で先頭 3 手を捨てた（ledger に 2 回 :not-done として報告
;; されている）。さらに 445bp の帯に archived が 6 本控えており、**writable な
;; 候補を上げるほど archived が上へ繰り上がる** —— 放置すると悪化する。
;;
;; **スコアは 1bp も動かさない。** archived な repo は従来どおり datoms にも
;; fleet 平均にも入る。ここが変えるのは行き先だけ。fleet 平均から除く案は
;; スコア意味論の変更なので、この修正には含めない（別の判断）。
(def archived
  (let [s (slurp* archived-file)
        m (when s (try (edn/read-string s) (catch :default _ nil)))]
    (when (seq (:archived m)) m)))

(def archived-paths (set (:archived archived)))

(def axes [:maturity/axis-substrate :maturity/axis-test :maturity/axis-governed
           :maturity/axis-ingest :maturity/axis-docs :maturity/axis-surface
           :maturity/axis-fresh])

;; ── 軸の重みは kind ごとに違う。**0 の軸は欠陥ではなく category mismatch** ──
;;
;; ADR-2608052000 が明示している: 「library に governor が無いのは欠陥ではなく
;; category mismatch」。実際 `lib` の重みは governed=0 / surface=0 である。
;;
;; **素の値が小さい軸を『弱い軸』として出すと、この loop は library に governor を
;; 生やしに行く。** 実測 2026-08-06: 重み補正前の版は langgraph（kind=lib）の
;; 次の 1 手として axis-governed（値 0.000、重み 0）を名指しした —— スコアは
;; 1 bp も動かず、ライブラリには不要なものが増える。**害のある助言**だった。
;;
;; だから並べ替えは値ではなく **重み × 伸びしろ**（= その軸を満点にしたときに
;; 実際に増える basis point）で行う。重み 0 の軸は候補にすら入らない。

;; ── 目標にしてよい軸 / いけない軸 ────────────────────────────────────────────
;;
;; **これがこの tick で一番重要な表。** 軸はすべて観測量（バイト数・ファイルの
;; 有無・経過日数）なので、「この軸を上げろ」とモデルに言うと、素直に
;; **観測量を直接動かしに行く** —— src にバイトを足し、README を膨らませ、
;; 意味の無い commit で freshness を戻す。スコアは上がり、fleet は悪くなる。
;;
;; だから軸を 2 つに分ける:
;;
;;   目標にしてよい = **その軸が上がったことを、軸とは独立に検証する手段がある**
;;   目標にしてはいけない = 検証手段が無く、直接動かすと必ず水増しになる
;;
;; 水増し不可の軸は「上げるな」ではない。**良い仕事の副産物として上がる**もので
;; あって、狙う対象ではない、という区別である。

(def axis-policy
  {:axis-test      {:targetable? true
                    :gate "nbb scripts/maturity-loop/run.cljs --only <repo>"
                    :why "テストを足すだけならバイト数は増える。**壊して赤くなることを確かめる**のが gate（『落ちない gate は劇場』）。"}
   :axis-ingest    {:targetable? true
                    :gate "引用した URL を実際に取得して 2xx を確認する"
                    :why "引用は捏造できるが、取得できるかは独立に検査できる。"}
   :axis-governed  {:targetable? true
                    :gate "拒否を実演する（生成器が拒否 0 件なら exit 1）"
                    :why "7 部品を置くだけならファイルは増える。門が本当に閉まることは実演でしか示せない。"}
   :axis-surface   {:targetable? true
                    :gate "demo 生成器を実際に回し、生成物が実 actor 由来であること"
                    :why "手書きのモックアップを置けば軸は上がる（ADR-2607122300 §1 が名指しで禁じた形）。"}
   :axis-docs      {:targetable? true
                    :gate "operator-quickstart の手順を実際に踏んで動くこと"
                    :why "README を膨らませれば軸は上がる。踏めるかどうかは独立に検査できる。"}
   :axis-substrate {:targetable? false
                    :why "`src/**` の実バイト数。**狙うと『コードを増やす』になる。** 実装が育った結果として上がる軸であって、目標にする軸ではない。"}
   :axis-fresh     {:targetable? false
                    :why "最終 commit からの経過日数。**狙うと『無意味な commit を打つ』になる。** 仕事をした結果として上がる。"}})

(defn- targetable? [axis] (get-in axis-policy [axis :targetable?] false))

(def ^:private weights
  "summary entity の `:model/weights`（pr-str された blob）。**自分で重みを
  持たない** —— 持った瞬間に、スコアを計算する側と助言する側で別の重みになる。"
  (delay
    (let [summary (first (filter :model/weights datoms))]
      (when-let [s (:model/weights summary)]
        (try (edn/read-string s) (catch :default _ nil))))))

(defn- weights-for [kind]
  (let [w @weights]
    (or (get w kind) (get w :default) (get w "default"))))

(defn- weakest-axes
  "重み × 伸びしろが大きい順に 3 つ。

  **測っていない軸（nil）は候補から外す** —— 0 ではない（ADR-2607203000）。
  **重み 0 の軸も外す** —— その kind にとって意味の無い軸なので。"
  [e]
  (let [w (weights-for (:repo/kind e))]
    (->> axes
         (keep (fn [a]
                 (let [v (get e a)
                       ;; blob は `#:m{...}` で書かれているので鍵は `:m/substrate`。
                       ;; `:substrate` で引くと全部 0 になり、weakest が空になる
                       ;; （実測 2026-08-06: それで出力が落ちた）。
                       wt (get w (keyword "m" (str/replace (name a) "axis-" "")) 0)]
                   (when (and (some? v) (pos? wt))
                     {:axis (keyword (name a))
                      :targetable? (targetable? (keyword (name a)))
                      :value v
                      :weight wt
                      ;; **軸も重みも basis point（10000 = 1.0）**。ADR-2608052000 が
                      ;; 整数 bp にしたのは、丸めが実行環境依存にならないようにする
                      ;; ため。[0,1] と混ぜると桁がずれる（実測 2026-08-06: (- 1 v)
                      ;; と書いて headroom が -12,515,000bp になった）。
                      ;; 満点にしたら repo の own が実際に何 bp 増えるか:
                      :headroom-bp (js/Math.round (/ (* wt (- 10000 v)) 10000))}))))
         (sort-by #(- (:headroom-bp %)))
         (take 4)
         vec)))

(defn- taken-by-someone-else
  "Is another session already working on this repository?

   Measured 2026-08-16: of seven consecutive rounds, FIVE had the tick's first
   choice already taken -- supplychain (two worktrees), sanctions, vin, toshi-kozan
   twice over. Every collision was caught by checking before starting, and nothing
   in the tick said so, which makes the check both mandatory and easy to skip. Two
   sessions writing the same docs/operator-quickstart.md is the outcome.

   Asks git about the repository rather than guessing from /tmp path names: a
   scratch file called /private/tmp/isic-vintage.cljs once matched a name-glob for
   `vin` and read as contention that was not there. `worktree list` is
   authoritative and finds linked worktrees wherever they live.

   Returns nil when it looks free, or a reason string."
  [path]
  (let [d (str root "/" path)
        wt (sh "git" ["-C" d "worktree" "list" "--porcelain"])
        br (sh "git" ["-C" d "branch" "--list" "agent/*"])
        n-wt (if (zero? (:code wt))
               (count (filter #(str/starts-with? % "worktree ")
                              (str/split-lines (:out wt))))
               0)
        branches (if (zero? (:code br))
                   (remove str/blank? (map str/trim (str/split-lines (:out br))))
                   [])]
    (cond
      ;; more than the checkout itself
      (> n-wt 1)
      (str "別セッションが worktree を " (dec n-wt) " 個持っている（触らない）")

      (seq branches)
      (str "agent branch が在る: " (str/join ", " (take 2 branches)) "（触らない）")

      :else nil)))

(defn- unseen-content
  "Content this repository has that the axis it is about to be sent to cannot see.

   `scan` emits :uncounted/* as report-only fields and no :maturity/* axis reads
   them (ADR-2608052000). They matter HERE because a 0bp axis has two causes that
   look identical in the ranking -- the repository has nothing, or the instrument
   is looking in the wrong place -- and sending an agent to the second is asking it
   to add what is already there.

   Returns a seq of one-line strings, or nil when there is nothing to say."
  [e]
  (seq
   (keep identity
         [(when (and (zero? (or (:maturity/axis-test e) 0))
                     (pos? (or (:uncounted/test-file-count e) 0)))
            (str "axis-test は 0bp だが test が " (:uncounted/test-file-count e)
                 " ファイル・" (:uncounted/test-bytes e 0) " バイト在る"
                 "（トップレベル test/ の外なので数えられていない）"))
          (when (and (zero? (or (:maturity/axis-substrate e) 0))
                     (pos? (or (:uncounted/src-file-count e) 0)))
            (str "axis-substrate は 0bp だが src が " (:uncounted/src-file-count e)
                 " ファイル・" (:uncounted/src-bytes e 0) " バイト在る"
                 "（入れ子の src/ なので数えられていない）"))
          (when (and (zero? (or (:maturity/axis-ingest e) 0))
                     (>= (or (:uncounted/url-count e) 0) 5))
            (str "axis-ingest は 0bp だが計数外のファイルに URL が "
                 (:uncounted/url-count e) " 件在る"))
          (when (pos? (or (:uncounted/readme-file-count e) 0))
            (str "README が .md ではないので docs の README 成分は 0"
                 "（README.edn 等が " (:uncounted/readme-file-count e) " 件）"))])))

(defn- row [e]
  {:repo (:repo/path e)
   :kind (:repo/kind e)
   :layer (:maturity/layer e)
   :own (:maturity/own e)
   :effective (:maturity/effective e)
   :fleet-gain (:leverage/fleet-gain e)
   :band (:leverage/band e)
   :unseen (unseen-content e)
   :taken (taken-by-someone-else (:repo/path e))
   :weakest (weakest-axes e)})

;; ── lane（substrate か breadth か）───────────────────────────────────────────

(defn- ledger-lines []
  (if-let [s (slurp* ledger-file)]
    (->> (str/split-lines s)
         (remove str/blank?)
         (keep #(try (edn/read-string %) (catch :default _ nil)))
         vec)
    []))

(defn- commit-time-ms
  "ledger 行の `:merged` が指す commit の実時刻（epoch ms）。解決できなければ nil。

  **ledger の `:at` を信用しないため。** `:at` は周が自分で書く文字列で、実測
  （2026-08-09）では JST の壁時計に `Z` を付けた行があった —— `m365-ingest` の
  `\"2026-08-09T00:30:00Z\"` に対し merge commit `c70a7114` の実時刻は
  `2026-08-08T15:14:18Z` で、9 時間先を指していた。commit の時刻は git が持つ
  事実なので、そちらを聞く。

  nil を返すことに意味がある（0 や now に丸めない）—— 解決できなかった行は
  `freshness` 側で `:at` に落ち、それが未来なら `:suspect` として表に出る。"
  [{:keys [merged target]}]
  (when (and (string? merged) (string? target) (str/starts-with? target "orgs/"))
    (let [{:keys [code out]} (sh "git" ["-C" target "log" "-1" "--format=%ct" merged])]
      (when (and (= 0 code) (seq (str/trim out)))
        (let [n (js/parseInt (str/trim out) 10)]
          (when-not (js/isNaN n) (* 1000 n)))))))

(defn- root-can-resolve-landings?
  "Can this root resolve a child repository's :merged sha at all?

   `commit-time-ms` asks `git -C <target>` where target is `orgs/<org>/<repo>`, so
   it needs a root whose `orgs/` is POPULATED -- not merely present. A plain
   worktree of the root repository has the fresh manifest and datoms and an empty
   or partial `orgs/`, and there every resolution fails, every axis-raise row falls
   back to its `:at` string, and a row written after the remeasure commit is
   reported as :blind-to-own-work. The measurement was fine; the root could not
   read the ledger's evidence.

   Measured 2026-08-15: this cost a round. The :root-reads-behind-remote message
   added earlier the same day advises passing COM_JUNKAWASAKI_ROOT, and following
   that advice with a worktree produced a FALSE stale -- public-malak's axis-docs
   was already 3333 on main and the tick said the ranking had not seen it. Prose in
   the message was not enough, because the prose is what got misread.

   Returns false only when we looked and found nothing resolvable; nil when there
   was nothing to check."
  [entries]
  (let [raises (filter fresh/axis-raise? entries)]
    (when (seq raises)
      (boolean (some (fn [{:keys [target]}]
                       (and (string? target)
                            (str/starts-with? target "orgs/")
                            (.existsSync fs (str root "/" target "/.git"))))
                     raises)))))

(defn- with-landing-times
  "軸上げの行に、merge commit から測った実時刻を添える。

  問い合わせるのは `axis-raise?` の行だけ（ledger 全体を git に聞かない）。"
  [entries]
  (mapv (fn [e]
          (if (fresh/axis-raise? e)
            (if-let [ms (commit-time-ms e)] (assoc e :landed-at-ms ms) e)
            e))
        entries))

;; ── 計測より後に動いた repo は候補から外す ──────────────────────────────────
;;
;; **The ledger is a self-report, and a round that lands and then dies writes no
;; self-report at all.** `freshness` learns "the measurement has not seen my last
;; landing" from ledger rows only, so a round that merged to main and exited
;; before appending its line is indistinguishable, here, from a round that did
;; nothing: both produce an empty `unseen`. The next tick names the same
;; repository and the same axis, and doing that work a second time is padding by
;; construction.
;;
;; Measured 2026-08-15: the preceding round raised axis-docs on
;; cloud-itonami/redelivery and merged it (2da6173, 14:06:31Z) without writing a
;; ledger row. This tick then ranked redelivery first and named axis-docs, whose
;; operator-quickstart had been on main for 23 minutes. The measurement was
;; committed at 14:43:04Z -- git could have said so; nothing asked it.
;;
;; So ask git. The fact the ledger was supposed to carry is already there.

(def ^:private movement-probe-depth
  "How far down the gain-sorted candidates to ask git. Not the whole lane -- that
  is 1,782 repositories every tick. Deep enough that dropping the moved ones
  still leaves five to rank."
  24)

(defn- head-commit-ms
  "Epoch ms of `path`'s current HEAD commit, or nil when git cannot answer.

  nil is neither 0 nor `now`. A repository that is not checked out, or whose
  `.git` this root cannot read, has not been shown to be unmoved -- and keeping
  `could not look` apart from `looked and it is current` is the entire point."
  [path]
  (let [{:keys [code out]} (sh "git" ["-C" path "log" "-1" "--format=%ct"])]
    (when (and (= 0 code) (seq (str/trim out)))
      (let [n (js/parseInt (str/trim out) 10)]
        (when-not (js/isNaN n) (* 1000 n))))))

(defn- with-head-times
  "候補に `:head-ms`（その repo の現 HEAD の commit 時刻）を添える。

  判定そのものは `fresh/classify-movement` が持つ —— この関数は git に聞く役だけ。
  `with-landing-times` が `:landed-at-ms` を添えるのと同じ分け方で、純粋な判定は
  test の効く ns に置く。"
  [candidates]
  (mapv #(assoc % :head-ms (head-commit-ms (:repo %))) candidates))

(defn- lane
  "ADR-2608052000 決定 2 を**実績から**維持する。固定スケジュール
  （『5 周に 1 回 substrate』等）にしないのは、周が飛んだり skip されたりすると
  帯からずれるため —— 実際に積んだ比率を見て、床を下回ったら substrate。"
  [entries]
  (let [worked (filterv #(contains? #{:landed :ran} (:outcome %)) entries)
        n (count worked)
        subs (count (filter #(= :substrate (:lane %)) worked))
        share (if (zero? n) 0.0 (/ subs n))]
    {:lane (if (< share substrate-share-floor) :substrate :breadth)
     :observed-substrate-share share
     :iterations n}))

;; ── 走る ─────────────────────────────────────────────────────────────────────

(defn -main []
  ;; **「計測値が無い」と「root を間違えている」を区別する。**
  ;; 実測 2026-08-09: 別マシン（superproject が ~/github/com-junkawasaki に無い）で
  ;; 走らせたところ、tick は「測り直し」を指示した。datoms は健在で、間違って
  ;; いたのは root だけ。測り直しは 1,700 repo を歩く重い反復なので、環境の
  ;; 設定ミスでそこへ送り込むと、その周がまるごと無駄になる。
  ;; **root が存在しないなら、それは計測の問題ではないと言う。**
  (when-not datoms
    (if (.existsSync fs root)
      (log! "計測値が読めない（" datoms-file "）— 次の 1 手は測り直し")
      (log! "root が存在しない（" root "）— 計測の問題ではない。"
            "superproject の場所を COM_JUNKAWASAKI_ROOT で指すこと"))
    (js/process.exit 0))

  (let [rows (->> datoms (filter :repo/path) (mapv row))
        entries (ledger-lines)
        behind? (root-reads-behind-remote?)
        ;; plain entries: this only reads :target paths, no git
        can-resolve? (root-can-resolve-landings? entries)
        gen-ms (generated-at)
        freshness (fresh/freshness {:generated-at gen-ms
                                    :now (.now js/Date)
                                    ;; `:at` ではなく merge commit の実時刻で測る
                                    :entries (with-landing-times entries)
                                    :stale-after-days stale-after-days
                                    :root-reads-behind-remote? behind?})
        {:keys [stale? unseen suspect]} freshness
        age (:age-days freshness)
        {:keys [lane observed-substrate-share iterations]} (lane entries)
        ;; **lane 名と layer 値は別の語彙。** `:substrate` はたまたま両方に
        ;; 存在するが、`:breadth` という layer は無い —— 実データの
        ;; `:maturity/layer` は `:cohort` 1,782 / `:flagship` 9 /
        ;; `:substrate` 17 だけ（実測 2026-08-08）。
        ;;
        ;; 素朴に `(= lane (:layer %))` と書くと breadth 周は必ず 0 件になり、
        ;; loop は `:no-targetable-axis` で毎回 skip する。しかも substrate の
        ;; 実績比率が床（2%）を超えた時点で lane は breadth に固定されるので、
        ;; **一度 substrate を 1 周でも回すと二度と何も選ばなくなる**。実際
        ;; 2026-08-06 に substrate を 1 周回した翌日から、この loop は
        ;; `ranked []` を出し続けて停止していた。
        ;;
        ;; breadth は層の名前ではなく『substrate 以外すべて』の意味なので、
        ;; そう書く。
        in-lane* (filterv (if (= :substrate lane)
                            #(= :substrate (:layer %))
                            #(not= :substrate (:layer %)))
                          rows)
        ;; archived を候補から落とす。**落としたことを黙らない** —— 掃き出しが
        ;; 無い/古いときに、この tick が黙って旧挙動へ戻ると、誰も気付かない。
        dropped (filterv #(archived-paths (:repo %)) in-lane*)
        in-lane (filterv #(not (archived-paths (:repo %))) in-lane*)
        ;; substrate 層は 17 本しかなく leverage に 10〜20 倍の段差がある。
        ;; cohort は 1,700 本超で ratio ≈ 1.0 の平坦地 —— **同じ順位付けでも
        ;; 意味の強さが違う**ので、それを出力に明記する。
        ;; **順位に載せる前に、git へ『この行はまだこの repo を describe して
        ;; いるか』を聞く。** ledger だけでは、着地して ledger を書かずに落ちた
        ;; 周を『何もしなかった周』と区別できない（上の classify-movement）。
        by-gain (vec (sort-by #(- (or (:fleet-gain %) 0)) in-lane))
        ;; **基準は `:scan/at`（計測が repo を読んだ時刻）であって datoms の
        ;; commit 時刻ではない。** 両者の差はこの検査が捕まえるべき窓そのもの
        ;; （上の scan-at-ms を見よ）。`:scan/at` が無い古い datoms のときだけ
        ;; commit 時刻へ落ちる。
        measured-at (or scan-at-ms gen-ms)
        movement (fresh/classify-movement
                  (with-head-times (vec (take movement-probe-depth by-gain)))
                  measured-at)
        moved (:moved movement)
        ranked (vec (take 5 (:kept movement)))
        flat? (and (= :breadth lane)
                   (let [gs (keep :fleet-gain ranked)]
                     (and (seq gs) (< (- (apply max gs) (apply min gs)) 0.5))))
        summary (first (filter :summary/layer-flagship datoms))

        entry {:at (.toISOString (js/Date.))
               :datoms-age-days (if (number? age) (js/Math.round age) :unknown)
               :datoms-stale? stale?
               :datoms-stale-reason (:reason freshness)
               ;; 見落とした着地を **名指しで** 残す。次周が ledger を読んだとき
               ;; 「なぜ測り直しになったか」を自分で再構成できるようにする。
               :datoms-unseen-landings (mapv #(select-keys % [:at :target :axis :merged])
                                             unseen)
               :lane lane
               :observed-substrate-share observed-substrate-share
               :iterations iterations
               :fleet {:mean-own (:summary/mean-own summary)
                       :mean-effective (:summary/mean-effective summary)
                       :substrate-drag (:summary/substrate-drag summary)}
               :ranked (mapv #(select-keys % [:repo :kind :layer :own :effective
                                              :fleet-gain :band :weakest])
                             ranked)
               ;; 誰を候補から外したかを残す。次周が「なぜこの repo が
               ;; 出てこないのか」を ledger だけで再構成できるようにする。
               ;; 掃き出しが無い周は :archived-sweep :missing と書く ——
               ;; 「除外 0 件」と「除外していない」を混ぜない。
               :archived-sweep (if archived
                                 {:generated-at (:generated-at archived)
                                  :total (:archived-count archived)}
                                 :missing)
               :archived-excluded (mapv :repo dropped)
               ;; **確認した本数を必ず残す。** `:moved []` だけでは「動いた repo が
               ;; 無かった」と「1 本も確かめられなかった」が同じ行になる。
               :movement {:checked (:checked movement)
                          :moved (mapv :repo moved)
                          :unknown (mapv :repo (:unknown movement))}
               :ranking-is-flat? flat?}]

    (log! "── 成熟度向上 tick ──")
    (log! (fresh/explain freshness))
    (doseq [u unseen]
      (log! (str "    ↳ 計測が見ていない着地: " (:at u) " " (:target u)
                 " " (name (or (:axis u) :?))
                 " (" (subs (str (:merged u)) 0 (min 8 (count (str (:merged u))))) ")")))
    ;; 時刻が壊れた行は判定から外したが、**外したことを黙らない**。
    (doseq [s suspect]
      (log! (str "    ⚠ ledger の時刻が未来（判定に使わない）: " (:at s) " " (:target s)
                 " — merge commit が解決できず :at に落ちた。"
                 "JST を Z と書いていないか確かめる")))
    (log! "fleet: 平均 M_own=" (:mean-own (:fleet entry))
          " M_eff=" (:mean-effective (:fleet entry))
          " substrate drag=" (:substrate-drag (:fleet entry)))
    (log! "lane:" (name lane)
          "（これまでの substrate 比率" (.toFixed (* 100 observed-substrate-share) 1)
          "% / 床" (* 100 substrate-share-floor) "% / 実績" iterations "周）")
    ;; 掃き出しが読めないときは**旧挙動に黙って戻らない**。archived が候補に
    ;; 混ざるのはこの loop が 3 周連続で空振りした原因そのものなので、
    ;; 「フィルタが効いていない」ことは順位より先に言う。
    (if archived
      (when (seq dropped)
        (log! (str "archived を候補から除外: " (count dropped) " 本"
                   "（掃き出し " (subs (str (:generated-at archived)) 0 10)
                   " / 全 " (:archived-count archived) " 本）"))
        (doseq [d (take 5 dropped)]
          (log! (str "    ↳ " (:repo d) " own=" (some-> (:own d) (.toFixed 3))
                     " — GitHub で read-only。push できないので候補から外した"))))
      (log! (str "⚠ archived の掃き出しが読めない（" archived-file "）。"
                 "**archived な repo が候補に混ざる** —— 指名されても push できず"
                 "その周は空振りする。`nbb --classpath \".:scripts/nbb_compat\" "
                 "scripts/gen-archived-repos.cljs` で作り直す")))
    ;; 計測より後に動いた repo。**archived と同じく、落としたことを黙らない。**
    ;; これを黙ると、順位から repo が消えた理由が ledger だけでは再構成できない。
    (when (seq moved)
      (log! (str "計測より後に動いたので候補から除外: " (count moved) " 本"
                 "（上位 " (:checked movement) " 本を git に確認）"))
      (doseq [m (take 5 moved)]
        (log! (str "    ↳ " (:repo m) " — HEAD が "
                   (subs (.toISOString (js/Date. (:head-ms m))) 0 19) "Z"
                   "、計測は " (subs (.toISOString (js/Date. measured-at)) 0 19) "Z"
                   (when-not scan-at-ms "（:scan/at が無いので commit 時刻）")
                   "。この行の軸値は現状を表していない（従うと水増しになる）"))))
    ;; **「確かめられなかった」と「動いていない」を混ぜない。**
    (when (seq (:unknown movement))
      (log! (str "⚠ " (count (:unknown movement)) "/" (:checked movement)
                 " 本は git が答えず、動いたかを確かめられなかった（候補には残した）: "
                 (str/join ", " (map :repo (take 3 (:unknown movement)))))))
    ;; evidence floor —— 1 本も答えを得られていないなら、『除外 0 本』は
    ;; 『動いた repo が無い』ではなく『確かめていない』である。
    (when (and (pos? (:checked movement))
               (= (count (:unknown movement)) (:checked movement)))
      (log! (str "⚠ **この確認は 1 本も答えを得ていない。** root（" root "）の "
                 "`orgs/` が populate されていない可能性が高い。この状態の"
                 "『除外 0 本』を『計測は現状を表している』と読まないこと")))
    (doseq [r ranked]
      (log! (str "  · " (:repo r) " [" (:kind r) "]"
                 "  own=" (some-> (:own r) (.toFixed 3))
                 " eff=" (some-> (:effective r) (.toFixed 3))
                 " gain=" (some-> (:fleet-gain r) (.toFixed 2))
                 " band=" (:band r)))
      (log! (str "      伸びしろ: "
                 (str/join " / " (map #(str (name (:axis %)) "="
                                            (js/Math.round (:value %)) "bp"
                                            " → +" (:headroom-bp %) "bp"
                                            (when-not (:targetable? %) "（狙わない）"))
                                      (:weakest r)))))
      ;; **0bp には 2 つの原因があり、順位表では同じ顔をしている。**
      ;; 「何も無い」と「計器が別の場所を見ている」を区別しないと、既に在るものを
      ;; 足しに行く周になる。
      (doseq [u (:unseen r)]
        (log! (str "      ⚠ " u)))
      (when-let [t (:taken r)]
        (log! (str "      ⛔ " t))))
    (when flat?
      (log! "⚠ この lane の leverage は平坦（上位 5 本の差 < 0.5）。順位は弱い信号なので、"
            "順位より『弱い軸を 1 つ確実に埋める』を優先する"))

    (try (.appendFileSync fs ledger-file (str (pr-str (assoc entry :outcome :measured)) "\n"))
         (log! "ledger:" ledger-file)
         (catch :default e (log! "ledger 追記に失敗（測定自体は有効）:" (str e))))

    (log! "")
    (log! "次の 1 手:"
          (cond
            stale?
            (str (case (:reason freshness)
                   :root-reads-behind-remote
                   (str "**測り直しても直らない。** 読んでいる datoms（" datoms-file
                        "）は remote の tip のものではないので、scan → dynamics を"
                        "回して main に着地させても、この root を読む限り同じ値が"
                        "返る。順位も『既に上げた軸が 0bp』のまま出る。直す:"
                        " (a) この root の checkout を既定 branch に同期する"
                        "（他セッションの WIP があるなら触らない）"
                        " (b) COM_JUNKAWASAKI_ROOT に、既定 branch を含む"
                        " checkout / worktree を渡して tick を回し直す。"
                        " どちらかを済ませてから順位を読む。")
                   :blind-to-own-work
                   (str (when (false? can-resolve?)
                          (str "⚠ **この STALE はこの root の性質かもしれない。** "
                               "着地時刻は `git -C orgs/<org>/<repo>` で測るので、"
                               "root の `orgs/` が populate されていないと解決に全部失敗し、"
                               "各行は `:at` 文字列に落ちる —— 測り直しの commit より後に "
                               "ledger を書いた行は、それだけで『計測が見ていない』と読まれる。"
                               "この root（" root "）には軸上げ行の子 checkout が 1 つも無い。"
                               "先に `orgs/` を持つ checkout で読み直すこと。\n\n     "))
                        "**上の順位を信用しない。** 計測(" (:datoms-age-days entry)
                        " 日前)より後に、この loop 自身が " (count unseen)
                        " 周ぶん着地させている（" (str/join ", " (map :target unseen))
                        "）。順位はその仕事を見ていないので、既に上げた軸を"
                        "『0bp』と読んで同じ場所へ送り返す —— 従うと水増しになる。")
                   :too-old (str "計測値が " (:datoms-age-days entry) " 日前。")
                   "")
                 ;; The remeasure instruction belongs ONLY to the reasons a
                 ;; remeasure answers. Appending it unconditionally made the
                 ;; :root-reads-behind-remote message contradict itself in the
                 ;; same sentence -- "remeasuring will not fix this" followed by
                 ;; "so remeasure" -- which is how an agent ends up doing the
                 ;; futile thing anyway.
                 (if (= :root-reads-behind-remote (:reason freshness))
                   ""
                   (str " まず itonami-maturity-scan → dynamics を回し直して着地させる"
                        "（skill の §5。この周は lane を消費しない）")))
            ;; **候補が消えた理由を取り違えさせない。** 全部が「計測より後に
            ;; 動いた」で落ちたのなら、疑うべきは lane でも計測の中身でもなく
            ;; 計測の**時点**で、次の 1 手は測り直しである。
            (and (empty? ranked) (seq moved))
            (str "上位 " (:checked movement) " 本すべてが計測より後に動いており、"
                 "順位に載せられる行が残らなかった。lane の判定ではなく計測の時点を疑う"
                 " —— itonami-maturity-scan → dynamics を回し直して着地させる"
                 "（skill の §5。この周は lane を消費しない）")
            (empty? ranked) (str lane " lane に対象が無い。lane の判定か計測値を疑う")
            ;; 重みのある軸が 1 つも無い = その kind にとって上げる意味のある軸が
            ;; 無い。**作らない。** 重みテーブルか kind 分類を疑う。
            (empty? (filter :targetable? (:weakest (first ranked))))
            (str (:repo (first ranked)) "（kind=" (:kind (first ranked)) "）は"
                 "上位 3 軸がすべて『狙わない軸』。**水増しに行かせない** —— "
                 "この repo は飛ばして次を採るか、軸の外側の仕事（実装そのもの）を選ぶ")

            ;; **1 位が誰かの作業中なら、次の 1 手はそれを言うこと。** 順位表に
            ;; ⛔ を出すだけでは足りない —— agent が従うのはこの行で、5/7 周で
            ;; 衝突していたのはここが黙っていたから。
            (:taken (first ranked))
            (let [r (first ranked)
                  free (first (remove :taken (rest ranked)))]
              (str "**1 位 " (:repo r) " は触らない** —— " (:taken r) "。"
                   (if free
                     (str "clean な次の候補は " (:repo free) "（own="
                          (some-> (:own free) (.toFixed 3)) "）。順位が平坦なので、"
                          "そちらを採って理由を ledger に書く")
                     (str "上位に clean な候補が無い。順位を下へ辿るか、"
                          "この周は測り直し/計器の仕事に充てる"))))

            :else (let [r (first ranked) a (first (filter :targetable? (:weakest r)))]
                    (str (:repo r) "（kind=" (:kind r) "）の "
                         (name (:axis a)) " 軸（現在 " (js/Math.round (:value a))
                         "bp、満点で +" (:headroom-bp a) "bp）を上げる"))))
    (js/process.exit 0)))

(-main)
