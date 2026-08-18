(ns mk1-jp-channel-cost-model
  "MK-1 を日本で売るときのマーケティング手法の比較。

   問い（オーナー、2026-08-18）: DM を送るコスト、電柱・屋外広告などのコストも含めて
   marketing 手法を広く調査する。

   ■ この比較の軸は『インプレッション単価』ではない
   我々は **名指しの 1,007 社**を持っている（中小企業庁の経営力向上計画 認定企業一覧
   から抽出、郵便番号つき）。したがってチャネルは 2 種類に割れる:

     targeted   名簿の 1 社を狙って当てられる（DM・FAXDM・テレアポ・訪問・フォーム）
     broadcast  面に当てる。**名簿を狙えない**（電柱・交通・屋外・折込・リスティング・
                業界誌・展示会）

   broadcast の『1 社あたり費用』は定義できない。**定義できないことを 0 や ∞ で
   埋めず、`:not-targetable` と書く。**代わりに『名簿の 1,007 社に当てようとしたら
   いくらか』を計算して、構造的に外れていることを数で示す。

   ■ 一次情報（2026-08-18 に調査。すべて相場であって見積ではない）
   - 郵便: 定形25g以内 110円 / はがき 85円（2024-10-01 改定後）
   - DM 発送代行: 印刷+作業+送料の 3 要素。初期費用 数千〜数万円
   - FAXDM: 送信 3-10円/枚。フォロー架電 100-500円/件
   - テレアポ代行: 100-300円/コール（BtoB 高難度 200-300）。リスト課金 300円〜/件
   - 展示会: 出展料 1小間 10-50万円。総額（施工・人件・集客込み）110-250万円
   - 業界誌広告: 30-100万円/本。記事広告 50-500万円/本
   - リスティング広告: CPC 300-2,000円
   - **電柱広告: 製作 12,000-22,000円/個 + 月額 2,400円〜/箇所**
   - 交通広告: 中吊り 50万-2,200万円 / 駅ポスター 1,600-84,000円/週 /
               メトロ ポスター 600万円〜/週"
  (:require [clojure.string :as str]))

(defn r1 [x] (/ (js/Math.round (* 10 x)) 10))
(defn yen [x] (str "¥" (.toLocaleString (js/Math.round x) "en-US")))
(defn pad [s n] (.padEnd (str s) n))
(defn lp [s n] (.padStart (str s) n))

(def N 1007)          ; 送付可能な名簿件数（実測）
(def N-B 373)         ; B 製造業
(def resp-lo 0.005)   ; ASSUMPTION: BtoB DM の返信率 0.5%
(def resp-hi 0.02)    ; ASSUMPTION: 同 2%

;; ── targeted: 名簿の 1 社に当てる単価 ────────────────────────────────────
(def targeted
  [{:ch "郵送DM（封書・返信はがき同封）"
    :unit-lo (+ 110 40 0) :unit-hi (+ 110 60 0)
    :note "定形110円 + 印刷/封入/宛名 40-60円。返信はがきは料金受取人払にすれば**返ってきた分だけ** 85+21円"
    :setup-lo 10000 :setup-hi 50000 :reach 1.0
    :msg "A4 1枚。制約（性能未実測・税務助言不可）を全部書ける"}
   {:ch "FAXDM"
    :unit-lo 3 :unit-hi 10
    :note "送信のみ。**FAX番号を我々は 1 件も持っていない**（名簿にもgBizINFOにも無い）"
    :setup-lo 0 :setup-hi 20000 :reach 0.0
    :msg "1枚。ただし到達率と心証が悪く、製造業でも近年は嫌われる"}
   {:ch "テレアポ代行"
    :unit-lo 100 :unit-hi 300
    :note "BtoB高難度は200-300円/コール。**電話番号も我々は持っていない**（gBizINFO の tel は空）"
    :setup-lo 50000 :setup-hi 200000 :reach 0.0
    :msg "口頭。性能を聞かれて答えられない状態では不利"}
   {:ch "問い合わせフォーム送信"
    :unit-lo 0 :unit-hi 0
    :note "**URL が判明したのは 70 社（6.9%）だけ**。うちフォーム痕跡 60 社。CAPTCHA は越えない"
    :setup-lo 0 :setup-hi 0 :reach (/ 60 1007)
    :msg "フォームの文字数制限内。営業お断り表示の確認が未完（下記の欠陥参照）"}
   {:ch "メール"
    :unit-lo 0 :unit-hi 0
    :note "**実測 6 社**（68サイト走査、素朴な抽出11件からWix/Sentryのテレメトリ5件を除外）"
    :setup-lo 0 :setup-hi 0 :reach (/ 6 1007)
    :msg "自由。ただし 6/1007 = 0.6%"}])

;; ── broadcast: 名簿を狙えない ────────────────────────────────────────────
(def broadcast
  [{:ch "電柱広告" :cost "製作 12,000-22,000円/個 + 月額 2,400円〜/箇所"
    :aim :not-targetable
    :hypo "名簿 1,007 社の前に 1 本ずつ立てたら 初期 1,813万円 + 月 242万円（各社の前に電柱が在ると仮定して）"
    :msg-fit "**5秒の一瞥で伝わらない。**¥798,000 の設備 × 未実測の性能 × 税制の条件付き —— 景表法の床を守ると書けることが残らない"}
   {:ch "屋外看板・野立て" :cost "立地により月数万〜数十万円"
    :aim :not-targetable :hypo "工業団地の入口なら製造業の経営者が通るが、名指しはできない"
    :msg-fit "同上"}
   {:ch "交通広告（中吊り）" :cost "50万-2,200万円/期間"
    :aim :not-targetable :hypo "山手線1週間 398万円〜。BtoC の到達で、決裁者に当たるのは偶然"
    :msg-fit "同上"}
   {:ch "駅ポスター" :cost "1,600-84,000円/週（駅による）"
    :aim :not-targetable :hypo "安い駅なら試せるが、名簿とは無関係"
    :msg-fit "同上"}
   {:ch "リスティング広告" :cost "CPC 300-2,000円"
    :aim :intent-targetable
    :hypo "**検索している人にだけ当たる。**『ローカル LLM 導入』等の検索は在るが、日本の中小企業のAI導入率は5.1%（ADR-2608180400）で母数が薄い"
    :msg-fit "LP で全部書ける。**ただし性能を書けないLPの直帰率は高い**"}
   {:ch "業界誌広告" :cost "30-100万円/本（記事広告 50-500万円）"
    :aim :segment-targetable
    :hypo "製造業/建設業の業界誌なら**セグメントには当たる**。1,007社の名指しではないが母集団は重なる"
    :msg-fit "記事広告なら制約も含めて書ける。**broadcast の中では唯一 message が載る**"}
   {:ch "展示会出展" :cost "出展料 1小間 10-50万円 / 総額 110-250万円"
    :aim :segment-targetable
    :hypo "来場者は自ら足を運んだ決裁者。**実機を触らせられる**"
    :msg-fit "**性能未実測の状態では出られない**（実機がブースで動かないと成立しない）"}])

(println "=== MK-1 日本市場：マーケティング手法の比較 ===\n")
(println (str "名簿: **" N " 社**（郵便番号つき、実測）。うち B 製造業 " N-B " 社。"))
(println "比較の軸は『名簿の 1 社に当てる費用』。面で当てる手法は狙えないので別扱いにする。\n")

(println "--- 1. targeted（名簿を狙える）---")
(println (str (pad "手法" 30) (lp "単価" 14) (lp "到達可能" 10) (lp "全件費用" 14) (lp "B のみ" 12)))
(doseq [{:keys [ch unit-lo unit-hi setup-lo setup-hi reach]} targeted]
  (let [n (* N reach) nb (* N-B reach)
        lo (+ setup-lo (* n unit-lo)) hi (+ setup-hi (* n unit-hi))
        blo (+ setup-lo (* nb unit-lo)) bhi (+ setup-hi (* nb unit-hi))]
    (println (str (pad ch 30)
                  (lp (if (= unit-lo unit-hi) (yen unit-lo) (str (yen unit-lo) "-" (yen unit-hi))) 14)
                  (lp (str (js/Math.round n) " 社") 10)
                  (lp (if (zero? n) "—" (str (yen lo) "-" (yen hi))) 14)
                  (lp (if (zero? nb) "—" (yen bhi)) 12)))))
(doseq [{:keys [ch note]} targeted] (println (str "   " (pad ch 26) note)))

(println "\n--- 2. 郵送DM の期待コスト（唯一 1,007 社に届く手法）---")
(let [u-lo 150 u-hi 170]
  (doseq [[lbl n] [["B 製造業のみ" N-B] ["A+B" (+ 217 373)] ["全件" N]]]
    (let [lo (+ 10000 (* n u-lo)) hi (+ 50000 (* n u-hi))
          r-lo (* n resp-lo) r-hi (* n resp-hi)
          reply-cost (* r-hi 106)]   ; 料金受取人払 85+21円、返ってきた分だけ
      (println (str "  " (pad lbl 14) (lp (str n " 通") 9)
                    "  発送 " (lp (str (yen lo) "-" (yen hi)) 20)
                    "  返信見込 " (lp (str (r1 r-lo) "-" (r1 r-hi) " 件") 16)
                    "  1件あたり " (yen (/ hi (max 1 r-lo))) "-" (yen (/ lo (max 1 r-hi))))))))
(println (str "  ※ 返信率 " (* 100 resp-lo) "-" (* 100 resp-hi) "% は **ASSUMPTION**（BtoB DM の一般値）。"
              "murakumo の実績は paid 0 で、返信率の実測は無い。"))
(println "  ※ 返信はがきは **料金受取人払**（85+21円）にすれば、返ってきた分しか払わない。")

(println "\n--- 3. broadcast（名簿を狙えない）---")
(doseq [{:keys [ch cost aim hypo msg-fit]} broadcast]
  (println (str "  " (pad ch 18) (pad (name aim) 20) cost))
  (println (str "      到達: " hypo))
  (println (str "      伝達: " msg-fit)))

(println "\n--- 4. いま構造的に選べない手法（持っていないもの）---")
(println "  FAXDM     : FAX番号を 0 件しか持っていない（名簿にも gBizINFO にも無い）")
(println "  テレアポ  : 電話番号を 0 件しか持っていない（gBizINFO の tel は空）")
(println "  メール    : 6/1007 = 0.6%")
(println "  フォーム  : 60/1007 = 6.0%（URL が判明した 70 社のうち）")
(println "  展示会    : 実機が無い。性能 gate 未実測（ADR-2607267000: 実測まで有償販売しない）")
(println "  → **持っているのは住所だけ。だから郵送が唯一 1,007 社に届く。**")


;; ── digital: プラットフォーム広告 ───────────────────────────────────────────
;; 一次確認（2026-08-18）:
;;   Google Customer Match の住所マッチは **First Name / Last Name / Country / Zip が必須**。
;;   我々が持つ代表者名は **55/1,014**（gBizINFO 実測）で、Google の最低マッチ数（約1,000）に
;;   遠く届かない。Meta / LINE / X の custom audience は email / phone ベースで、
;;   我々は email 6 件・phone 0 件。**したがって 5 媒体とも名簿を狙えない。**
;;   会社リストで狙えるのは LinkedIn だけだが、日本の中小製造・建設の経営者の在籍が薄い。
;;
;;   国内 MAU（2025-08 時点の公表値）: LINE 7,080万 / X 6,800万(DAU 4,000万) /
;;   YouTube 6,540万 / Instagram 4,230万 / TikTok 4,200万 / Facebook 2,600万
;;   60歳以上の LINE 利用率 91.1%（2024、2014 の 11.3% から）。YouTube は全年代 81.5%。
(def digital
  [{:ch "Google 検索広告" :mau "—（検索母数）" :aim :intent
    :fit "**BtoB・高額商材・40代以上は Google が有利**というのが定説。買う気のある人にだけ当たる"
    :risk "日本の中小企業の AI 導入率は 5.1%（ADR-2608180400）。**そもそも検索している人が薄い。**『ローカル LLM』『社内 AI サーバ』の検索量を先に測る必要がある"
    :msg "LP で全部書ける。**唯一メッセージの制約が無い媒体**"}
   {:ch "YouTube 広告" :mau "6,540万（全年代 81.5%）" :aim :demographic
    :fit "到達も年代も足りる。**動く実機を見せられる**のがこの商材に効く"
    :risk "**その実機が無い。**性能 gate 未実測で、動画で見せられるものが今は無い"
    :msg "動画。ただし景表法の床で性能を言えないので『何ができるか』を示せない"}
   {:ch "Facebook / Instagram 広告" :mau "FB 2,600万 / IG 4,230万" :aim :demographic
    :fit "**FB は国内 MAU が最小だが年代の適合が最も高い**（若年層が離れた結果、40-60代の比率が高い）。勤務先・役職・興味での詳細ターゲティングがある"
    :risk "役職ターゲティングは自己申告ベース。日本の町工場の経営者が FB に職歴を書いている率は低い"
    :msg "画像+文。LP へ送れる"}
   {:ch "LINE 広告" :mau "7,080万（登録1億超）。60歳以上の利用率 91.1%" :aim :demographic
    :fit "**国内で最も到達する。**年代も届く"
    :risk "**文脈が消費者。**¥798,000 の設備投資の検討を LINE で始める人は考えにくい。B2B の実績が薄い媒体"
    :msg "画像+文"}
   {:ch "X 広告" :mau "6,800万（DAU 4,000万）" :aim :demographic
    :fit "日本は X の世界第2位の市場"
    :risk "**この層が最も薄い。**IT・若年に偏り、中小製造・建設の経営者はほぼ居ない"
    :msg "文+画像"}
   {:ch "LinkedIn 広告" :mau "国内は小さい" :aim :company-list
    :fit "**5 媒体で唯一、会社リストで狙える**（Matched Audiences の company list）"
    :risk "日本の中小製造・建設の経営者の在籍率が低い。**狙えるが、そこに居ない**"
    :msg "文+画像"}])

(println "\n--- 6. プラットフォーム広告（Google / LINE / Meta / X / YouTube）---")
(println "  ■ まず技術的な事実: **5 媒体とも我々の名簿を狙えない。**")
(println "    Google Customer Match の住所マッチは First/Last Name + Country + Zip が必須。")
(println "    我々が持つ代表者名は **55/1,014**、Google の最低マッチ数は約 1,000。")
(println "    Meta / LINE / X は email・phone ベースで、我々は email 6 件・phone 0 件。")
(println "    会社リストで狙えるのは LinkedIn だけだが、日本のこの層は在籍が薄い。")
(println "  → **デジタル広告の役割は『名簿に当てる』ではなく『名簿に無い母集団から連絡先を作る』。**")
(println "     郵送DM と競合しない。**補完である。**\n")
(doseq [{:keys [ch mau aim fit risk msg]} digital]
  (println (str "  " (pad ch 26) (pad (name aim) 14) mau))
  (println (str "      向く : " fit))
  (println (str "      不向き: " risk))
  (println (str "      伝達 : " msg)))

(println "\n--- 7. CPL で見たときに成立するか ---")
(let [gm 403016      ; MK-1 Solo の粗利（ADR-2608153400）
      dm-lo 8840 dm-hi 60810]   ; 郵送DM の返信1件あたり（B 373通、上の実測レンジ）
  (println (str "  MK-1 Solo の粗利: " (yen gm)))
  (println (str "  郵送DM の『返信1件』単価: " (yen dm-lo) "-" (yen dm-hi) "（B 373通）"))
  (println "  BtoB のリード獲得単価(CPL)は 一般に 1万〜3万円。**同じ桁である。**")
  (doseq [[cpl close] [[10000 0.10] [10000 0.05] [30000 0.10] [30000 0.05]]]
    (let [cpa (/ cpl close)]
      (println (str "   CPL " (yen cpl) " × 商談化・受注 " (* 100 close) "% → 受注1件 " (lp (yen cpa) 12)
                    "  粗利比 " (r1 (* 100 (/ cpa gm))) "%"
                    (if (> cpa gm) "  ✗ 粗利を超える" "  ○")))))
  (println "  → **CPL 3万円 × 受注率 5% だと受注1件 60万円で、粗利 40万円を超えて赤字。**")
  (println "     デジタル広告が成立するかは CPL ではなく **受注率**で決まる。それは未計測。"))


;; ── broadcast の再評価（2026-08-18、オーナー指摘を受けた訂正）───────────────
;; **前版は broadcast を一括で『外れ』と切った。それは粗すぎた。**
;; broadcast は独立した 2 軸で分かれる:
;;   純度   その媒体のオーディエンスが決裁者にどれだけ偏っているか
;;   搭載量 5 秒の一瞥か、読み物か
;; 電柱・交通・屋外は 純度低 × 搭載量低 なので外れる。**ゴルフ場と雑誌は違う。**
(def broadcast-2
  [{:ch "ゴルフカート広告（カートビジョン）"
    :cost "1週間 5万円〜 / 1ヶ月 20万円〜。**最低出稿 30万円**。関東が高い"
    :purity "**高い。ゴルファーの 40% が部長職以上、26.4% が年収800万以上、19.5% が世帯金融資産3,000万以上。75.5% が月1回以上プレー**"
    :payload "低い。プレー中のカート画面。**認知は作れるが説明はできない**"
    :verdict "**電柱と決定的に違うのは純度**。ただし搭載量が低いので、単独では効かない。『名前を見たことがある』を作る役"}
   {:ch "日経トップリーダー（中堅・中小企業の経営者向け月刊誌）"
    :cost "4色1P **77万円** / 表四 99.8万円 / 表二 88.5万円"
    :purity "**高い。誌名どおり中小企業の経営者が読者**"
    :payload "**高い。読み物なので『まだ出荷していない、実測が終わるまで売らない』まで書ける**"
    :verdict "**純度と搭載量の両方を満たす数少ない媒体。**ただし 77万円は郵送DM 全件(22万円)の 3.5 倍"}
   {:ch "東商新聞（東京商工会議所の機関紙）"
    :cost "要見積（商工会議所系は総じて安い）"
    :purity "**8万社超の経営者層が購読。**しかも東京商工会議所は**認定経営革新等支援機関**で、経営力向上計画の支援をしている —— **我々の母集団 1,014 社と直接重なる**"
    :payload "高い（紙面）"
    :verdict "**この調査で見つかった中で最も筋が良い。**8万社の経営者に、我々の名簿と同じ性質の母集団で届く"}
   {:ch "各地商工会議所の会報誌"
    :cost "低コスト（福岡は約20,000社配布、さいたまは年12回で20%割引）"
    :purity "高い（会員企業の経営者）"
    :payload "高い"
    :verdict "地域を絞れる。**首都圏の会議所から始められる**"}
   {:ch "HBR / 一橋ビジネスレビュー"
    :cost "100万円 / 52万円"
    :purity "中（大企業・アカデミア寄り）"
    :payload "高い"
    :verdict "読者層が我々の中小製造・建設とずれる"}])

(println "\n--- 8. broadcast の再評価: 純度 × 搭載量 ---")
(println "  **前版は broadcast を一括で外れと切った。それは粗すぎた。**")
(println "  電柱・交通・屋外が外れるのは broadcast だからではなく、")
(println "  **純度（決裁者への偏り）と 搭載量（伝えられる量）の両方が低い**から。")
(println "  ゴルフ場と雑誌は、少なくとも片方が高い。\n")
(doseq [{:keys [ch cost purity payload verdict]} broadcast-2]
  (println (str "  ■ " ch))
  (println (str "      費用  : " cost))
  (println (str "      純度  : " purity))
  (println (str "      搭載量: " payload))
  (println (str "      判定  : " verdict)))

(println "\n--- 9. 1 決裁者あたりで並べる（分かる範囲で）---")
(let [dm-all 221190 dm-b 113410]
  (doseq [[ch cost reach note]
          [["郵送DM 全件" dm-all 1007 "名指し。実測"]
           ["郵送DM B のみ" dm-b 373 "名指し。実測"]
           ["日経トップリーダー 1P" 770000 nil "**発行部数を未確認**。5万部なら 1部15円"]
           ["東商新聞" nil 80000 "**掲載料を未確認**。20万円なら 1社2.5円、50万円なら 6.3円"]
           ["ゴルフカート 1ヶ月" 200000 nil "**掲出ゴルフ場のラウンド数を未確認**"]]]
    (println (str "  " (pad ch 26)
                  (lp (if cost (yen cost) "要見積") 12)
                  (lp (if reach (str reach " 者") "要確認") 12)
                  (lp (if (and cost reach) (str (yen (/ cost reach)) "/者") "—") 14)
                  "  " note))))
(println "  ⚠ **未確認の 3 つ（発行部数・掲載料・ラウンド数）が埋まるまで、この表で順位を付けない。**")

(println "\n--- 5. この模型の欠陥（自分の検査の話）---")
(println "  ① 受信拒否表示の検出はトップページしか見ていない。0/68 は『表示が無い』ではなく")
(println "     **『見ていない』**。営業お断りは問い合わせページ側に出る（修正済み、再走査は未）")
(println "  ② メール抽出は当初 11 件を報告したが、5 件は Wix/Sentry のテレメトリだった。")
(println "     **検査が過大に報告していた。**除外規則を足して実数 6 件（修正済み）")
(println "  ③ 返信率 0.5-2% は業界一般値。**この製品・この母集団での実測ではない**")
