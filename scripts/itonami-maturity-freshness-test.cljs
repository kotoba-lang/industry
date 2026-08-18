#!/usr/bin/env nbb
;; scripts/itonami-maturity-freshness-test.cljs
;;
;; 成熟度向上 loop の鮮度判定の gate。
;;
;;   nbb --classpath ".:scripts/nbb_compat" scripts/itonami-maturity-freshness-test.cljs
;;
;; exit 0 iff 全件 OK。**落ちない gate は劇場**なので、この suite の各 case は
;; 「判定を 1 つ壊すと必ず赤くなる」ように選んである。特に:
;;
;;   · remeasure-does-not-self-trap  —— `axis-raise?` から remeasure の除外を
;;     外すと赤くなる。これが無いと loop は測り直しから出られない。
;;   · equal-timestamp-is-not-after  —— `>` を `>=` にすると赤くなる。
;;   · unknown-generation            —— fail-open/fail-closed どちらに倒しても赤くなる。

(require '[scripts.itonami-maturity-freshness :as f]
         '[scripts.nbb-compat :as compat])

(def failures (atom 0))
(def ran (atom 0))

(defn check! [name expected actual]
  (swap! ran inc)
  (if (= expected actual)
    (println (str "OK " name))
    (do (swap! failures inc)
        (println (str "FAIL " name))
        (println (str "  expected=" (pr-str expected)))
        (println (str "  actual  =" (pr-str actual))))))

(defn- ms [iso] (f/parse-instant iso))

;; ── 実測した回帰（2026-08-09）─────────────────────────────────────────────────
;;
;; datoms commit 2026-08-09 00:56:09 +0900 = 2026-08-08T15:56:09Z
;; marine-insurance の axis-docs 着地 2026-08-08T17:14:19Z（merged 676c81f8）
;; 差は 78 分 —— 日数に丸めると 0 日で、旧判定は『新鮮』と答えていた。

(def measured-at (ms "2026-08-08T15:56:09Z"))
(def now (ms "2026-08-09T02:00:00Z"))

(def real-ledger
  [{:at "2026-08-08T14:05:00Z" :outcome :landed :lane :none-remeasure
    :axis :none-remeasure :target "90-docs/system-dynamics/itonami-maturity.datoms.edn"
    :merged "0943b5936bf6dce7571e6c99802cbce10a755ef3"}
   {:at "2026-08-08T16:01:01Z" :outcome :landed :lane :none
    :axis :none-remeasure :target "90-docs/system-dynamics/itonami-maturity.datoms.edn"
    :merged "e9288be9556e9a0d5fc4bd4eeff92a00dd4fd5e3"}
   {:at "2026-08-08T17:14:19.000Z" :outcome :landed :lane :breadth
    :axis :axis-docs :target "orgs/cloud-itonami/marine-insurance"
    :merged "676c81f855a019becc0e0db63499b71694e94b4b"}
   {:at "2026-08-08T17:31:27.866Z" :outcome :measured}])

(let [r (f/freshness {:generated-at measured-at :now now
                      :entries real-ledger :stale-after-days 7})]
  (check! "real regression: 78 分前の着地を STALE と言う"
          {:stale? true :reason :blind-to-own-work}
          (select-keys r [:stale? :reason]))
  (check! "real regression: 見落とした着地を名指しする"
          ["orgs/cloud-itonami/marine-insurance"]
          (mapv :target (:unseen r)))
  (check! "real regression: 日数に丸めると 0 日（旧判定が黙った理由）"
          0
          (js/Math.round (:age-days r))))

;; ── 測り直しの周で自分を罠にかけない ─────────────────────────────────────────
;;
;; ledger は datoms を commit した *後* に書くので、測り直しの行は必ず計測値より
;; 新しい。これを「見落とした仕事」と数えると、測る → stale と言われる → また
;; 測る、で永久に抜けられない。

(let [r (f/freshness {:generated-at (ms "2026-08-08T15:56:09Z")
                      :now now
                      :entries [{:at "2026-08-08T16:01:01Z" :outcome :landed
                                 :axis :none-remeasure :lane :none
                                 :merged "e9288be9"}]
                      :stale-after-days 7})]
  (check! "remeasure-does-not-self-trap: 測り直し直後は fresh"
          {:stale? false :reason :fresh}
          (select-keys r [:stale? :reason])))

;; ── 計測が最新のときは黙る（誤検知しない）───────────────────────────────────

(let [r (f/freshness {:generated-at (ms "2026-08-08T18:00:00Z")
                      :now now :entries real-ledger :stale-after-days 7})]
  (check! "計測が全着地より新しければ fresh"
          {:stale? false :reason :fresh}
          (select-keys r [:stale? :reason])))

;; ── 日数の床は残っている ─────────────────────────────────────────────────────

(let [r (f/freshness {:generated-at (ms "2026-07-01T00:00:00Z")
                      :now (ms "2026-08-09T00:00:00Z")
                      :entries [] :stale-after-days 7})]
  (check! "着地が 1 件も無くても 7 日を超えれば STALE"
          {:stale? true :reason :too-old}
          (select-keys r [:stale? :reason])))

(let [r (f/freshness {:generated-at (ms "2026-08-05T00:00:00Z")
                      :now (ms "2026-08-09T00:00:00Z")
                      :entries [] :stale-after-days 7})]
  (check! "床の内側で着地も無ければ fresh"
          {:stale? false :reason :fresh}
          (select-keys r [:stale? :reason])))

;; 両方当てはまるときは、より具体的な方を言う（次の 1 手が強くなる）。
(let [r (f/freshness {:generated-at (ms "2026-07-01T00:00:00Z")
                      :now (ms "2026-08-09T00:00:00Z")
                      :entries real-ledger :stale-after-days 7})]
  (check! "古い かつ 見落としあり → :blind-to-own-work が勝つ"
          :blind-to-own-work (:reason r)))

;; ── 何を『見落とした仕事』と数えるか ─────────────────────────────────────────

(check! "measured の行（:merged 無し）は数えない"
        []
        (f/unseen-landings [{:at "2026-08-08T17:31:27.866Z" :outcome :measured}]
                           measured-at))

(check! ":ran の行（着地していない）は数えない"
        []
        (f/unseen-landings [{:at "2026-08-08T17:31:00Z" :outcome :ran
                             :axis :axis-docs :target "x"}]
                           measured-at))

(check! ":partial でも main に載っていれば数える（自己申告ではなく merged で見る）"
        ["orgs/cloud-itonami/oil-refining"]
        (mapv :target
              (f/unseen-landings [{:at "2026-08-08T17:31:00Z" :outcome :partial
                                   :axis :axis-test :merged "deadbeef"
                                   :target "orgs/cloud-itonami/oil-refining"}]
                                 measured-at)))

(check! "計測より前の着地は数えない"
        []
        (f/unseen-landings [{:at "2026-08-08T09:55:07.764Z" :outcome :landed
                             :axis :axis-docs :merged "08d66326" :target "x"}]
                           measured-at))

;; 境界: ちょうど同時刻は『後』ではない。commit した瞬間に書かれた行を
;; 見落とし扱いにしない。
(check! "equal-timestamp-is-not-after"
        []
        (f/unseen-landings [{:at "2026-08-08T15:56:09Z" :outcome :landed
                             :axis :axis-docs :merged "abc" :target "x"}]
                           measured-at))

(check! "見落としは古い順に並ぶ"
        ["b" "a"]
        (mapv :target
              (f/unseen-landings
               [{:at "2026-08-08T19:00:00Z" :axis :axis-test :merged "1" :target "a"}
                {:at "2026-08-08T17:00:00Z" :axis :axis-docs :merged "2" :target "b"}]
               measured-at)))

;; ── ledger の `:at` より merge commit の実時刻を優先する ─────────────────────
;;
;; 実測（2026-08-09、この case が生まれた周）: m365-ingest の行は
;; `:at "2026-08-09T00:30:00Z"` と書いてあったが、merge commit c70a7114 の実時刻は
;; 2026-08-08T15:14:18Z —— JST の壁時計に Z を付けた 9 時間先の値だった。測り直しを
;; main に着地させた**直後**の tick がまだ STALE と答え、loop は測り直しから
;; 出られなかった（測る → stale → また測る）。
;;
;; `landing-instant` の `:landed-at-ms` 優先を外すと、この case は赤くなる。

(def m365-row
  {:at "2026-08-09T00:30:00Z"                 ; 周が自分で書いた値（JST に Z）
   :landed-at-ms (ms "2026-08-08T15:14:18Z")  ; git が持つ事実
   :outcome :landed :lane :breadth :axis :axis-docs
   :target "orgs/cloud-itonami/m365-ingest"
   :merged "c70a7114c4cb618191a3cf17b071d73dcfef3cb7"})

(let [r (f/freshness {:generated-at (ms "2026-08-08T18:18:32Z")  ; 測り直しの着地時刻
                      :now (ms "2026-08-08T18:30:00Z")
                      :entries [m365-row] :stale-after-days 7})]
  (check! "commit-time-beats-at: 測り直した後は fresh（:at は 9 時間先を指していた）"
          {:stale? false :reason :fresh}
          (select-keys r [:stale? :reason])))

(check! "commit-time-beats-at: 出所が :commit だと分かる"
        {:ms (ms "2026-08-08T15:14:18Z") :source :commit}
        (f/landing-instant m365-row))

(check! "landing-instant: commit を解決できなければ :at に落ちる"
        {:ms (ms "2026-08-08T17:14:19.000Z") :source :at}
        (f/landing-instant {:at "2026-08-08T17:14:19.000Z" :merged "676c81f8"}))

;; 優先順位を入れただけで検出そのものを弱めていないこと。
(let [r (f/freshness {:generated-at (ms "2026-08-08T15:00:00Z")
                      :now (ms "2026-08-08T18:30:00Z")
                      :entries [m365-row] :stale-after-days 7})]
  (check! "commit-time-beats-at: 実時刻が計測より後なら今までどおり STALE"
          {:stale? true :reason :blind-to-own-work}
          (select-keys r [:stale? :reason])))

;; ── 未来の時刻は見落としの証拠にならない ─────────────────────────────────────
;;
;; commit を解決できなかった行が壊れた `:at`（未来）を持っていると、それだけで
;; loop は永久に測り直しへ送り返される。未来判定を外すと 2 件とも赤くなる。

(def future-row
  {:at "2026-08-09T00:30:00Z" :outcome :landed :lane :breadth :axis :axis-docs
   :target "orgs/cloud-itonami/m365-ingest" :merged "deadbeef"})  ; 解決できない SHA

(let [r (f/freshness {:generated-at (ms "2026-08-08T18:18:32Z")
                      :now (ms "2026-08-08T18:30:00Z")
                      :entries [future-row] :stale-after-days 7})]
  (check! "future-at-is-not-evidence: 未来の :at では STALE にしない"
          {:stale? false :reason :fresh}
          (select-keys r [:stale? :reason]))
  (check! "future-at-is-not-evidence: 黙って捨てず :suspect で報告する"
          ["orgs/cloud-itonami/m365-ingest"]
          (mapv :target (:suspect r))))

;; 未来判定は `now` を渡したときだけ。判定材料が無いのに『未来ではない』と
;; 決めつけない。
(check! "future 判定は now が無ければ掛けない"
        ["orgs/cloud-itonami/m365-ingest"]
        (mapv :target (f/unseen-landings [future-row] (ms "2026-08-08T18:18:32Z"))))

;; ── 読めない値を捏造しない ───────────────────────────────────────────────────

(check! "parse-instant: 読めない文字列は nil（0 に丸めない）"
        [nil nil nil]
        [(f/parse-instant "not-a-date") (f/parse-instant nil) (f/parse-instant 12345)])

(check! "at が読めない行は数えない（落ちもしない）"
        []
        (f/unseen-landings [{:at "yesterday-ish" :axis :axis-docs :merged "abc" :target "x"}]
                           measured-at))

(let [r (f/freshness {:generated-at :unknown :now now
                      :entries real-ledger :stale-after-days 7})]
  (check! "generated-at が読めなければ stale とも fresh とも言わない"
          {:stale? false :reason :unknown-generation :age-days nil}
          (select-keys r [:stale? :reason :age-days])))

;; ── 読んでいるコピー自体が古い場合 ───────────────────────────────────────────
;;
;; `:root-reads-behind-remote` は他の理由と違って **測り直しでは直らない**。
;; tick が読む root は cwd ではないので、共有 checkout が別 branch に居ると
;; 「測り直せ」→ main に着地 → 同じ古い値を読む、が閉ループになる（実測
;; 2026-08-15、2 周消費）。だから他のどの理由よりも先に立つ必要がある。

(let [r (f/freshness {:generated-at measured-at :now now
                      :entries real-ledger :stale-after-days 7
                      :root-reads-behind-remote? true})]
  (check! "root が remote の tip を読んでいないなら、それを最初に言う"
          {:stale? true :reason :root-reads-behind-remote}
          (select-keys r [:stale? :reason])))

;; 順番が効いていること。unseen があっても :blind-to-own-work に落ちてはいけない
;; —— 両方が真のとき正しい手は測り直しではなく root を直すことなので。
(let [unseen-entries [{:at "2026-08-15T10:00:00Z" :outcome :landed
                       :axis :axis-docs :target "orgs/x/y"
                       :landing-ms (+ measured-at 60000)}]
      r (f/freshness {:generated-at measured-at :now now
                      :entries unseen-entries :stale-after-days 7
                      :root-reads-behind-remote? true})]
  (check! "unseen があっても root の問題を優先する（測り直しは空振りするから）"
          :root-reads-behind-remote
          (:reason r)))

;; 日数の床より先でもある
(let [r (f/freshness {:generated-at (- now (* 40 86400000)) :now now
                      :entries [] :stale-after-days 7
                      :root-reads-behind-remote? true})]
  (check! "40 日古くても、先に root の問題を言う"
          :root-reads-behind-remote
          (:reason r)))

;; **判定できなかったときは判定しない。** git が答えられない（remote が無い /
;; detached / offline）とき tick は nil を渡す。nil を true と同じに扱えば、
;; 見ていないものを見たことにする——このファイル全体が扱っている失敗形そのもの。
(doseq [[label v] [["nil（git が答えられなかった）" nil]
                   ["false（tip と一致している）" false]]]
  (let [r (f/freshness {:generated-at measured-at :now now
                        :entries real-ledger :stale-after-days 7
                        :root-reads-behind-remote? v})]
    (check! (str "root の判定が " label " なら、この理由は立てない")
            false
            (= :root-reads-behind-remote (:reason r)))))

;; 引数を渡さない既存の呼び出しは、今までどおり
(let [r (f/freshness {:generated-at measured-at :now now
                      :entries real-ledger :stale-after-days 7})]
  (check! "引数を省略した既存の呼び出しは挙動が変わらない"
          false
          (= :root-reads-behind-remote (:reason r))))

;; ── 表示 ─────────────────────────────────────────────────────────────────────

(check! "explain: root の問題は『測り直しでは直らない』と言う"
        true
        (boolean (re-find #"測り直しでは直らない"
                          (f/explain (f/freshness {:generated-at measured-at :now now
                                                   :entries real-ledger :stale-after-days 7
                                                   :root-reads-behind-remote? true})))))


(let [r (f/freshness {:generated-at measured-at :now now
                      :entries real-ledger :stale-after-days 7})]
  (check! "explain: STALE の理由が読める"
          true
          (boolean (re-find #"STALE" (f/explain r)))))

(check! "explain: 鮮度不明を『0 日』と読ませない"
        true
        (boolean (re-find #"不明"
                          (f/explain (f/freshness {:generated-at :unknown :now now
                                                   :entries [] :stale-after-days 7})))))

;; ── classify-movement（2026-08-15、実測した回帰）─────────────────────────────
;;
;; 直前の周が cloud-itonami/redelivery の axis-docs を上げて main へ merge し
;; （2da6173、14:06:31Z）、**ledger 行を書かずに終わった**。ledger だけを読む
;; `classify-landings` にとって、その周は『何もしなかった周』と同じ形をしている
;; —— `unseen` は空になり、tick は redelivery を 1 位に置いて axis-docs を
;; 名指しした。operator-quickstart は 23 分前から main に在った。
;;
;; **基準時刻の選び方まで固定する。** datoms の commit は 14:09:52Z で、それを
;; 基準にすると見落とす（14:06:31 < 14:09:52）。`:scan/at` は 14:03:30Z で、
;; こちらを基準にすると捕まる。実際その datoms の redelivery 行は
;; `axis-docs 0` のままだった —— 計測は本当に見ていない。

(def scan-at (ms "2026-08-15T14:03:30.385Z"))          ; datoms の :scan/at
(def datoms-committed-at (ms "2026-08-15T14:09:52Z"))  ; datoms の commit 時刻
(def redelivery-head (ms "2026-08-15T14:06:31Z"))      ; 2da6173

(def candidates
  [{:repo "orgs/cloud-itonami/redelivery" :head-ms redelivery-head}
   {:repo "orgs/cloud-itonami/robot" :head-ms (ms "2026-07-20T00:00:00Z")}
   {:repo "orgs/cloud-itonami/sanctions" :head-ms nil}])

(let [r (f/classify-movement candidates scan-at)]
  (check! "movement: ledger に行が無くても、動いた repo を名指しできる"
          ["orgs/cloud-itonami/redelivery"]
          (mapv :repo (:moved r)))
  (check! "movement: 動いた repo は候補に残さない"
          false
          (boolean (some #(= "orgs/cloud-itonami/redelivery" (:repo %)) (:kept r))))
  (check! "movement: 動いていない repo は候補に残る"
          true
          (boolean (some #(= "orgs/cloud-itonami/robot" (:repo %)) (:kept r))))
  ;; **『確かめられなかった』を『動いていない』と混ぜない。** git が答えな
  ;; かった repo は :unknown に出しつつ候補にも残す —— 落とすと確かめられ
  ;; なかったことだけを理由に候補が静かに減り、黙って残すと沈黙が pass になる。
  (check! "movement: git が答えない repo は :unknown に出す"
          ["orgs/cloud-itonami/sanctions"]
          (mapv :repo (:unknown r)))
  (check! "movement: :unknown な repo は候補にも残す"
          true
          (boolean (some #(= "orgs/cloud-itonami/sanctions" (:repo %)) (:kept r))))
  ;; evidence floor —— `:moved []` だけでは「動いた repo が無かった」と
  ;; 「1 本も確かめられなかった」が同じ行になる。件数を必ず返す。
  (check! "movement: 確認した本数を返す（:moved [] を『確かめた』と読ませない）"
          3 (:checked r)))

;; **基準時刻の回帰そのもの。** commit 時刻を基準にすると、この検査が捕まえる
;; はずだった当の着地を見落とす。tick の基準を commit 時刻へ戻すと赤くなる。
(check! "movement: commit 時刻を基準にすると redelivery を見落とす（だから :scan/at）"
        []
        (mapv :repo (:moved (f/classify-movement candidates datoms-committed-at))))

;; `>` を `>=` にすると赤くなる。計測と同時刻の commit は計測に含まれている。
(check! "movement: 計測と同時刻の commit は『動いた』ではない"
        []
        (mapv :repo (:moved (f/classify-movement
                             [{:repo "same" :head-ms scan-at}] scan-at))))

;; 基準が無いとき『誰も動いていない』と**主張しない**。:checked 0 で区別する。
(let [r (f/classify-movement candidates :unknown)]
  (check! "movement: 基準が無ければ動いたと主張しない" [] (mapv :repo (:moved r)))
  (check! "movement: 基準が無ければ候補を落とさない" 3 (count (:kept r)))
  (check! "movement: 基準が無いことを :checked 0 で言う" 0 (:checked r)))

(println)

;; ── landings-all-excluded? ───────────────────────────────────────────────────
;; **両方向を出す。** 覆えているときに true を返せなければ意味が無く、覆えて
;; いないときに true を返せば stale な 1 位を黙って渡す。
(let [blind {:reason :blind-to-own-work
             :unseen [{:target "orgs/o/a" :axis :axis-docs}
                      {:target "orgs/o/b" :axis :axis-docs}]}
      probe (fn [names checked] {:moved (mapv (fn [n] {:repo n}) names) :checked checked})]

  (check! "excluded?: 名指しされた着地を全て除外していれば true"
          true (f/landings-all-excluded? blind (probe ["orgs/o/a" "orgs/o/b"] 24)))

  (check! "excluded?: 1 つでも欠けていれば false（probe 深度の外は :moved に入らない）"
          false (f/landings-all-excluded? blind (probe ["orgs/o/a"] 24)))

  (check! "excluded?: 件数が合っても名前が違えば false"
          false (f/landings-all-excluded? blind (probe ["orgs/o/a" "orgs/o/zzz"] 24)))

  ;; orgs/ が populate されていない状態。probe は 24/24 答えられず :moved が空。
  (check! "excluded?: probe が 1 件も答えていなければ false"
          false (f/landings-all-excluded? blind (probe [] 0)))

  (check! "excluded?: :checked が無ければ false"
          false (f/landings-all-excluded? blind {:moved [{:repo "orgs/o/a"} {:repo "orgs/o/b"}]}))

  (check! "excluded?: :target を持たない着地は照合できないので false"
          false (f/landings-all-excluded?
                 {:reason :blind-to-own-work :unseen [{:axis :axis-docs}]}
                 (probe ["orgs/o/a"] 24)))

  ;; ここは緩めない。測り直しでは直らない理由と、日数の床。
  (check! "excluded?: :root-reads-behind-remote は緩めない"
          false (f/landings-all-excluded?
                 (assoc blind :reason :root-reads-behind-remote)
                 (probe ["orgs/o/a" "orgs/o/b"] 24)))

  (check! "excluded?: :too-old は緩めない"
          false (f/landings-all-excluded?
                 (assoc blind :reason :too-old)
                 (probe ["orgs/o/a" "orgs/o/b"] 24)))

  (check! "excluded?: 着地が無ければ（:fresh 等）false"
          false (f/landings-all-excluded?
                 {:reason :blind-to-own-work :unseen []} (probe [] 24))))

(println)
;; 件数つきの summary。**gate 側はこの行が無ければ pass と報告しない** ——
;; classpath が壊れて 1 件も走らないまま exit 0 になる経路を塞ぐ
;; （west-pin-policy-check.cljs と同じ床）。
(if (pos? @failures)
  (do (println (str "FAILED " @failures " of " @ran))
      (compat/exit 1))
  (do (println (str @ran " cases OK"))
      (compat/exit 0)))
