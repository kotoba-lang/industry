#!/usr/bin/env nbb
;; kouhou-ingest.cljs — 官公庁・公益 press の日次 ingest を回し、その日の
;; briefing / ledger / corpus receipt を git に、取得した feed 本体を annex の
;; 2 つの off-machine remote に載せる（ADR-2608110200）。
;; launchd の com.gftd.kouhou-ingest から呼ばれる。
;;
;; ## なぜこれが要るか
;;
;; ADR-2608110200 は ingest + store を landing させたが、**定期実行に配線しな
;; かった**。`manifest/observatories.edn` はそれを正直に書いている（「ここを毎時に
;; 足すかは別判断なので、まだ足していない」）。結果、2026-08-11 の 1 パス以降
;; 7 日間 1 件も取り込まれていない。取得側は本物で、動かす仕組みだけが無かった。
;;
;; ## 不変条件
;;
;; 1. **「測れなかった」を「問題なし」と同じ値で返さない**（ADR-2608136000）。
;;    checkout が無い / branch が違う / 他人の未コミット変更がある — どれも
;;    exit 2 で終わる。0 でも 1 でもないのは「答えを出せなかった」の意。
;; 2. **custody は location log ではなく exit code で確かめる**（ADR-2608131100）。
;;    `whereis` は主張であって測定ではない。`checkpresentkey` は remote に訊く。
;; 3. **1 件も archive できなかった run は成功ではない。** 取り込み 0 件は
;;    「今日は press が無かった」ではなく、ほぼ確実にこちらの故障である。
;; 4. **他人の作業を壊さない。** data/ と raw/ の外に未コミット変更があれば、
;;    その checkout では走らない（共有 west checkout なので）。
;; 5. **publish はしない。** KOUHOU_PUBLISH=0 の phase 0（observe）で回す。
;;    外向きの発信を無人の定期実行に混ぜない。

(require '[clojure.string :as str])

(def cp (js/require "node:child_process"))
(def fs (js/require "node:fs"))

(def root "/Users/junkawasaki/github/com-junkawasaki")
(def ds (str root "/orgs/cloud-itonami/kouhou"))
(def remotes ["kotobase" "b2"])

(defn- run [args {:keys [dir env]}]
  (let [r (.spawnSync cp (first args) (clj->js (rest args))
                      (clj->js (cond-> {:encoding "utf8" :maxBuffer (* 64 1024 1024)}
                                 dir (assoc :cwd dir)
                                 env (assoc :env env))))]
    {:exit (or (.-status r) 1)
     :out (or (.-stdout r) "")
     :err (or (.-stderr r) "")}))

(defn- git [& args] (run (into ["git"] args) {:dir ds}))

(defn- die
  "答えを出せなかった。exit 2 — 成功(0) とも「壊れている」(1) とも違う値にする。"
  [msg] (println (str "UNABLE " msg)) (js/process.exit 2))

(defn- fail [msg] (println (str "FAIL " msg)) (js/process.exit 1))

(defn- lines [s] (remove str/blank? (str/split-lines (or s ""))))

(println (str "kouhou-ingest " (.toISOString (js/Date.))))

(when-not (.existsSync fs (str ds "/.git"))
  (die (str "checkout が無い: " ds " — west update してから（黙って clone しない）")))

;; ── 0) 共有 checkout を壊さないための門 ──────────────────────────────────
;; west の checkout は **detached HEAD が正常**（pin の commit を直接見ている）。
;; branch 名で門を作ると、この job は一度も走らない — 実測 2026-08-18、最初の
;; 実装がまさにそれで `UNABLE branch が main ではない (HEAD)` を返した。
;; 見るべきは「誰かの枝の上に居るか」であって branch 名ではない。
(let [dirty (->> (lines (:out (git "status" "--porcelain")))
                 (map #(subs % 3))
                 (remove #(or (str/starts-with? % "data/") (str/starts-with? % "raw/"))))]
  (when (seq dirty)
    (die (str "data/ と raw/ の外に未コミット変更がある: " (str/join " " (take 5 dirty))))))

(git "fetch" "--quiet" "cloud-itonami")
(def main-ref "cloud-itonami/main")

;; 乖離しているなら触らない。ancestor 関係のどちらかであることだけを要求する
;; （後ろに居れば FF し、前に居れば未 push の commit があるので push で回収する）。
(let [behind? (zero? (:exit (git "merge-base" "--is-ancestor" "HEAD" main-ref)))
      ahead? (zero? (:exit (git "merge-base" "--is-ancestor" main-ref "HEAD")))]
  (when-not (or behind? ahead?)
    (die (str "HEAD と " main-ref " が乖離している — 先に解消する")))
  (when (and behind? (not ahead?))
    (let [{:keys [exit err]} (git "merge" "--ff-only" main-ref)]
      (when (pos? exit)
        (die (str main-ref " に FF できない: " (str/trim err)))))))
(run ["git" "annex" "merge"] {:dir ds})

(defn- shard-count [plane]
  (let [d (str ds "/data/" plane)]
    (if (.existsSync fs d) (count (.readdirSync fs d)) 0)))

(def before {:briefings (shard-count "briefings")
             :ledger (shard-count "ledger")
             :corpus (shard-count "corpus")})

;; ── 1) 実 fetch。publish はしない ────────────────────────────────────────
(def env (doto (js/Object.assign #js {} js/process.env)
           (aset "KOUHOU_ALLOW_LIVE_INGEST" "1")
           (aset "KOUHOU_PUBLISH" "0")))

;; ── 同じ UTC 日の 2 回目を通す ──────────────────────────────────────────
;;
;; annex は commit した raw を**読み取り専用の symlink**にする。だから同じ UTC 日に
;; 2 回目を走らせると、その日の全 source が
;; `raw/<日>/<id>.xml (Permission denied)` で落ちる —— **53/53 error、persisted 0**。
;; 実測 2026-08-19、無人の 2 回が「51 with errors」を出していた正体がこれ。
;; 日次 run は UTC 日をまたぐので普段は踏まないが、手で回した瞬間・retry した
;; 瞬間に全滅する。
;;
;; unlock は「その日の分」だけ。全部 unlock すると corpus 全体が実ファイルに戻り、
;; 次の save が履歴全体の diff になる。
(let [today (subs (.toISOString (js/Date.)) 0 10)
      dir (str "raw/" today)]
  (when (.existsSync fs (str ds "/" dir))
    (let [{:keys [exit]} (run ["datalad" "unlock" dir] {:dir ds})]
      (println (str "  unlock " dir (if (zero? exit) " ok" " (skipped)"))))))

(def run-out (atom ""))

(let [{:keys [exit out err]} (run ["clojure" "-M:dev:live-ingest"] {:dir ds :env env})
      summary (filter #(or (str/starts-with? % "=== ")
                           (str/includes? % "sources,")
                           (str/starts-with? % "persisted:"))
                      (lines out))]
  (reset! run-out out)
  (doseq [l summary] (println (str "  " l)))
  (when (pos? exit)
    (println (str "  stderr: " (str/trim (or (last (lines err)) ""))))
    (fail "live-ingest が失敗した")))

;; ── error の中身を残す。数だけでは次に同じことが起きても分からない ──────
;;
;; 実測 2026-08-19: 無人の 2 回とも「53 sources, 2 committed, 51 with errors」を
;; 出したが、直後に手で回すと fetch は 48/53 通った。**理由を捨てていたので
;; 「51」以上のことが言えなかった。** 残した途端に 1 パスで分かった（上の unlock）。
;;
;; 数字だけ潰して本文は切らない —— `(` で切ると
;; `raw/…/x.xml (Permission denied)` が `raw/…/x.xml` になり、
;; 「パスが理由」に見えて何が起きたか分からなくなる。
(def error-reasons
  (->> (lines @run-out)
       (keep #(second (re-find #":(?:fetch-)?error \"([^\"]{0,80})" %)))
       (map #(-> % (str/replace #"\d{2,}" "N") str/trim (subs 0 (min 70 (count %)))))
       frequencies
       (sort-by val >)))

(def counts
  (let [line (first (filter #(str/includes? % "sources,") (lines @run-out)))
        ns- (map #(js/parseInt % 10) (re-seq #"[0-9]+" (or line "")))]
    (zipmap [:sources :committed :held :published :errors] ns-)))

(when (seq error-reasons)
  (println "  error の内訳:")
  (doseq [[reason n] (take 6 error-reasons)]
    (println (str "    " n "\t" reason))))

;; ── 2) 何が増えたかを、意図ではなくディスクから数える ────────────────────
(def after {:briefings (shard-count "briefings")
            :ledger (shard-count "ledger")
            :corpus (shard-count "corpus")})
(println (str "  shards: briefings " (:briefings before) "->" (:briefings after)
              ", ledger " (:ledger before) "->" (:ledger after)
              ", corpus " (:corpus before) "->" (:corpus after)))

(def archived
  ;; `--untracked-files=all` は必須。既定の `normal` は**新しいディレクトリを 1 行に
  ;; 畳む**ので、38 本の feed を新規 `raw/<日>/` に書いた run が「1」と報告される
  ;; （実測 2026-08-18、この script の初回実行がまさにそれ）。床としては通るが、
  ;; 出てくる数が嘘になる — 検査は落ちないが、読む人が誤る。
  (count (lines (:out (run ["git" "status" "--porcelain" "--untracked-files=all" "raw"]
                           {:dir ds})))))

;; 不変条件 3。0 件は「今日は press が無かった」ではない。
(when (zero? archived)
  (fail "raw/ に 1 件も archive されなかった — 取り込みが実際には走っていない"))
(println (str "  archived: " archived " feed"))

;; ── 3) commit（raw/** は .gitattributes により annex 行き）────────────────
(let [{:keys [exit err]} (run ["datalad" "save" "-m"
                               (str "ingest " (subs (.toISOString (js/Date.)) 0 10)
                                    " (" archived " feed)")]
                              {:dir ds})]
  (when (pos? exit)
    (println (str "  save stderr: " (str/trim err)))
    (fail "datalad save に失敗")))
(println "  save ok")

;; ── 4) 2 つの off-machine remote へ。`datalad push` ではなく annex copy ──
;;
;; `datalad push --to <special remote>` は git を押そうとして失敗する。
;; newsfeed 側はこれで「WARN」を出し続け、台帳のコピーが手元 1 本のまま
;; 数週間気づかれなかった（2026-08-18 実測）。運ぶのは annex copy。
(doseq [r remotes]
  (let [{:keys [exit err]} (run ["git" "annex" "copy" "--to" r "--jobs" "1" "raw"] {:dir ds})]
    (when (pos? exit)
      (println (str "  copy --to " r " stderr: " (str/trim (or (last (lines err)) "")))))))

;; ── 5) custody は exit code で確かめる（location log を数えない）─────────
(def today (subs (.toISOString (js/Date.)) 0 10))

(defn- keys-under [path]
  (lines (:out (run ["git" "annex" "find" "--format=${key}\n" path] {:dir ds}))))

(def all-keys (keys-under "raw"))

;; **全 key を毎日問い合わせない。** corpus は毎日 40 本ずつ増えるので、
;; O(key × remote) の検証は 1 件 ~2 秒 × 2 remote で伸び続け、1 年で数時間の job に
;; なる。今日書いたものは全部、過去のものは標本だけ確かめる —— 系統的な破損
;; （remote が死んだ、鍵が失効した）は標本で出るし、今日の分は今日しか確かめられない。
(def todays-keys (keys-under (str "raw/" today)))
(def older (remove (set todays-keys) all-keys))
(def sample-size 10)
(def sampled (take sample-size (shuffle older)))
(def checked (concat todays-keys sampled))

(def custody
  (into {}
        (for [r remotes]
          [r (count (filter (fn [k]
                              (zero? (:exit (run ["git" "annex" "checkpresentkey" k r] {:dir ds}))))
                            checked))])))
;; 分母を必ず一緒に出す。「93 present」だけでは、何本問い合わせたか読めない
;; （検査していないものを合格として数える形は ADR-2608131100 でやった）。
(println (str "  custody (VERIFIED by exit code): "
              (str/join ", " (for [r remotes]
                               (str r "=" (get custody r) "/" (count checked))))
              "  [today " (count todays-keys) " + sample " (count sampled)
              " of " (count older) " older; corpus " (count all-keys) "]"))

(when (< (apply max (vals custody)) (count checked))
  (println (str "WARN 問い合わせた " (count checked) " 本のうち、"
                "どの remote でも揃っていないものがある")))

;; ── 6) push。git-annex branch も一緒に（custody の地図はこれ）────────────
;; **`HEAD:main` で押す。** west の checkout は detached なので `main:main` は
;; 「そんな ref は無い」で失敗し、commit は手元に残り続ける。newsfeed 側は
;; まさにこれで、日次 ingest の 4 commit 分が数週間 push されないままだった
;; （2026-08-18 実測。annex の中身も手元 1 本だったので、台帳は完全に単一コピー）。
(doseq [[src dst] [["HEAD" "main"] ["git-annex" "git-annex"]]]
  (let [{:keys [exit err]} (git "push" "cloud-itonami" (str src ":" dst))]
    (if (pos? exit)
      (println (str "  push " dst " FAILED: " (str/trim (or (last (lines err)) ""))))
      (println (str "  push " dst " ok")))))

;; git を押せていなければ、この run の記録はこの機械にしか無い。
(let [{:keys [exit]} (git "merge-base" "--is-ancestor" "HEAD" main-ref)]
  (when (pos? exit)
    (fail "commit が remote に載っていない — 手元だけの記録になっている")))

;; off-machine のコピーが 1 本も取れていない run は、成功として終わらせない。
(when (zero? (apply max (vals custody)))
  (fail "どの remote にも 1 件も載っていない — 手元 1 本の状態で終わっている"))


;; 半分以上が落ちた run を「done」で終わらせない。しきい値は当て推量だが、
;; **静かに終わることの方が悪い** —— 実測 2 回とも 51/53 が落ちて、ログには
;; 「done」とだけ書いてあった。
(let [{:keys [sources errors]} counts]
  (when (and (number? sources) (number? errors) (pos? sources)
             (> errors (quot sources 2)))
    (fail (str sources " 中 " errors " が error —— 半分を超えた run は成功ではない。"
               "上の内訳が理由を言う"))))

(println "kouhou-ingest done")
