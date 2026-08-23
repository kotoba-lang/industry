#!/usr/bin/env nbb
;; lp-copy-score.cljs — LP の**コピー**を決定論的に採点する。
;;
;; `scripts/threed-maturity-audit.cljs` が「その機能は動くか」を測るのに対し、
;; ここは「その面は売る形をしているか」を測る。両方とも **LLM の主観採点を
;; 一次情報にしない**（ADR-2607132300 の実測: 3 体の judge が 4 ライブラリを
;; 全軸 4.0–5.0/5 と採点した裏で、4 つの具体的欠落を 1 つも指摘しなかった）。
;;
;; ## 3 つのレンズ
;;
;;   :register  感情レジスタ。語彙は **Hume の 48 ラベル**
;;              （`orgs/gftdcojp/hume/clj/src/hume/oka.cljc` の `emotion-labels`）
;;   :sales     売る文章の構造（先頭に何を置くか / 否定の密度 / 具体性）
;;   :lp        面としての機能（単一の行動 / 主張の隣に証拠 / 反論処理）
;;
;; ## ⚠ :register は Hume のモデル出力ではない
;;
;; **これは Hume AI の expression measurement を呼んでいない。** 呼べる credential も
;; 経路もこのスクリプトには無い。ここでやっているのは、**同じ 48 ラベルの語彙**に
;; 対して日本語の語彙的手がかりを数える**代理指標**である。
;;
;; したがって:
;;   - 48 のうち**手がかりを定義できたラベルだけ**を報告する。残りは
;;     `:unaddressed` として件数で申告する —— 0 と報告しない。
;;     **測っていないことと、測って 0 だったことは違う。**
;;   - `hume/oka.cljc` 自身の注意書きをそのまま引き継ぐ:
;;     *"Expression scores estimate perceived expression, not internal emotional state."*
;;     ここではさらに弱く、**読み手の状態ではなく、文章が招く register の代理**である。
;;   - 実物の Hume を当てたいなら音声/表情/言語モデルの API 経路が要る。
;;     **この数字を Hume のスコアとして引用しない。**
;;
;; ## 使い方
;;
;;   nbb scripts/lp-copy-score.cljs <url|file> [<url|file> ...]
;;   nbb scripts/lp-copy-score.cljs --json <url>       # 機械可読
;;
;; **単独のスコアは読めない。比較して読む** —— 同じ計器で 2 面以上を測り、
;; 差を見る。絶対値の閾値はこのファイルの外に無い。

(ns lp-copy-score
  (:require ["node:child_process" :as cp]
            ["node:fs" :as fs]
            [clojure.string :as str]))

(def args (vec *command-line-args*))
(defn- flag? [n] (some #(= n %) args))
(def targets (vec (remove #(str/starts-with? % "--") args)))

;; ---------------------------------------------------------------------------
;; 取得と本文抽出
;; ---------------------------------------------------------------------------

(defn fetch [t]
  (if (str/starts-with? t "http")
    (str (cp/execSync (str "curl -sS -L --max-time 30 " (pr-str t)) #js {:encoding "utf8" :maxBuffer 8000000}))
    (str (fs/readFileSync t "utf8"))))

(defn- strip [h]
  (-> h
      (str/replace #"(?s)<script.*?</script>" " ")
      (str/replace #"(?s)<style.*?</style>" " ")
      (str/replace #"(?s)<nav.*?</nav>" " ")
      (str/replace #"(?s)<head.*?</head>" " ")))

(defn- text-of [h]
  (-> (strip h)
      (str/replace #"<[^>]+>" " ")
      (str/replace #"&nbsp;" " ")
      (str/replace #"&amp;" "&")
      (str/replace #"&quot;" "\"")
      (str/replace #"&#39;" "'")
      (str/replace #"&lt;" "<")
      (str/replace #"&gt;" ">")
      (str/replace #"\s+" " ")
      str/trim))

(defn- sentences [t]
  (->> (str/split t #"[。！？\.\!\?]")
       (map str/trim)
       (remove #(< (count %) 4))
       vec))

(defn- hero-text
  "最初の見出しから最初の節までを『冒頭』とする。**折り返し上に何を置いたか**は
   LP で最も強い決定なので、本文全体の平均に溶かさない。"
  [h]
  (let [s (strip h)
        i (or (str/index-of s "<h1") 0)
        j (or (str/index-of s "<section" i) (min (count s) (+ i 2600)))]
    (text-of (subs s i (max i j)))))

;; ---------------------------------------------------------------------------
;; 語彙。**すべて日本語の表層手がかり**で、意味解析はしていない。
;; ---------------------------------------------------------------------------

(def lexicon
  "**言語ごとに別の語彙。** 日本語の語彙を英語ページに当てると 0 が返り、
   それは『否定が無い』と見分けが付かない —— 実測 2026-08-23、`/go`（英語）が
   否定 0 / 留保 0 / CTA 0 を返し、**日本語で書かれた否定の無いページと同じ形**
   だった。`detect-lang` で選び、選べなければ軸ごと `:unmeasurable` にする。"
  {:ja {:negation ["ではありません" "ではない" "ではなく" "書きません" "置きません"
                   "出しません" "ありません" "しません" "できません" "保証しません"
                   "限りません" "要りません" "使いません" "なりません"]
        :hedge ["一般的" "対象可否" "確認してください" "場合" "など" "可能性" "前提"
                "とは限" "かもしれ" "程度" "見込み" "推奨"]
        :cta ["購入する" "購入" "申し込" "問い合わせ" "相談" "試す"]}
   :en {:negation ["is not" "are not" "does not" "do not" "cannot" "will not"
                   "not a " "no " "never" "neither" "without "]
        :hedge ["generally" "usually" "may " "might" "depends" "confirm with"
                "commonly" "we do not warrant" "whether"]
        :cta ["buy" "purchase" "contact" "try" "get started" "see the"]}})

(def concrete-marker
  "具体数。**日本語の桁語（万・千・億）とカンマ区切りを取る。**
   実測 2026-08-23、旧版は `[0-9]+\\s*(単位)` だったので、見出しの `税込 99,900` も
   `10万円未満` も拾えず **具体数 0** と報告していた —— 値段が見出しのページに対して。"
  #"[0-9][0-9,]*(\.[0-9]+)?\s*(万|千|億|円|%|パーセント|バイト|点|件|倍|GB|MB|KB|mm|cm|時間|分|日|年|台|本|人|JPY|USD)|[0-9]{1,3}(,[0-9]{3})+")

(defn detect-lang
  "本文が日本語か英語か。**判定できなければ nil を返し、呼び出し側が
   `:unmeasurable` にする** —— 推測して片方の語彙を当てない。"
  [t]
  (let [n (count t)
        jp (count (re-seq #"[\u3040-\u30ff\u4e00-\u9faf]" t))
        ratio (if (zero? n) 0 (/ jp n))]
    (cond (> ratio 0.15) :ja
          (< ratio 0.02) :en
          ;; **混合は拒否しない。優勢な方で採点し、混合であることを併記する。**
          ;; 実測 2026-08-23、拒否していたとき /models・/gpu・/blog の 3 面が
          ;; UNMEASURABLE になった —— どれも「日本語の固有名詞が混じった英語面」で、
          ;; **測れないのではなく、測り方を選べばよいだけだった。**
          :else (if (> ratio 0.08) :ja :en))))

(defn mixed? [t]
  (let [n (count t)
        jp (count (re-seq #"[\u3040-\u30ff\u4e00-\u9faf]" t))
        r (if (zero? n) 0 (/ jp n))]
    (and (>= r 0.02) (<= r 0.15))))

(defn js-dependent
  "server-render された本文に対して、**JS を実行しないと読めない面か**。

   `#app` / `#root` の mount point が在り、かつ server 本文が薄ければ、
   クローラと初回表示が見るのはその薄い分だけである。**これは体裁ではなく
   到達性の問題**なので、コピーの点数と並べて出す。"
  [html text]
  (let [mount (boolean (re-find #"id=\"(app|root)\"" html))
        scripts (count (re-seq #"<script" html))]
    {:mount-point? mount
     :script-tags scripts
     :server-text-chars (count text)
     :js-dependent? (and mount (< (count text) 600))}))

(def register-cues
  "**Hume の 48 ラベルのうち、日本語の表層手がかりを定義できたものだけ。**
   ここに無い 36 ラベルは `:unaddressed` として件数で申告する ——
   **0 と報告しない。**

   `:invites` は「その register を招く手がかり」、`:blocks` は「削ぐ手がかり」。"
  {"Calmness"      {:invites ["落ち着" "静か" "そのまま" "淡々"] :blocks ["今すぐ" "急" "限定"]}
   "Doubt"         {:invites ["かもしれ" "とは限" "可能性" "場合による" "対象可否"] :blocks []}
   "Confusion"     {:invites ["ただし" "なお" "別物" "とは別" "混同"] :blocks []}
   "Determination" {:invites ["する" "決め" "揃え" "通す" "回す"] :blocks []}
   "Interest"      {:invites ["なぜ" "どこ" "実測" "測った" "検査" "根拠"] :blocks []}
   "Excitement"    {:invites ["新し" "初めて" "ついに" "無償" "無料"] :blocks []}
   "Anxiety"       {:invites ["禁じ" "違反" "漏れ" "リスク" "危険" "失敗"] :blocks []}
   "Boredom"       {:invites ["について" "に関して" "となります" "であります"] :blocks []}
   "Realization"   {:invites ["つまり" "ということ" "だから" "そこで"] :blocks []}
   "Pride"         {:invites ["自社" "独自" "唯一" "世界初"] :blocks []}
   "Contentment"   {:invites ["安心" "十分" "足り"] :blocks []}
   "Distress"      {:invites ["困" "止ま" "壊れ" "落ち"] :blocks []}})

(def hume-label-count
  "`orgs/gftdcojp/hume/clj/src/hume/oka.cljc` の `emotion-labels` の件数。
   **手で写した定数なので、上流が動いたらここも動かす。**"
  48)

;; ---------------------------------------------------------------------------
;; 採点
;; ---------------------------------------------------------------------------

(defn- occurrences [t ws] (reduce + (map (fn [w] (count (re-seq (re-pattern (str/replace w #"([.*+?^${}()|\[\]\\])" "\\$1")) t))) ws)))

(defn- pct [a b] (if (zero? b) 0 (/ (* 100.0 a) b)))
(defn- r1 [x] (js/Number (.toFixed x 1)))

(def min-chars
  "これ未満は『測れなかった』。**0 と報告しない。**" 300)

(defn score-one [label html]
  (let [t (text-of html)
        hero (hero-text html)
        sents (sentences t)
        n (count sents)
        lang (detect-lang t)
        lx (get lexicon lang)
        negation (:negation lx)
        hedge (:hedge lx)
        cta-words (:cta lx)
        lc (fn [s] (if (= lang :en) (str/lower-case s) s))
        hit? (fn [s ws] (some #(str/includes? (lc s) %) ws))
        neg-sents (when lx (filter #(hit? % negation) sents))
        hero-sents (sentences hero)
        hero-neg (when lx (filter #(hit? % negation) hero-sents))
        first-neg-idx (when lx (first (keep-indexed (fn [i s] (when (hit? s negation) i)) hero-sents)))
        cta-n (when lx (occurrences (lc t) cta-words))
        buy-links (count (re-seq #"buy\.stripe\.com" html))
        h2 (count (re-seq #"<h2" html))
        concrete (count (re-seq concrete-marker t))
        avg-len (if (zero? n) 0 (/ (reduce + (map count sents)) n))
        reg (into {}
                  (keep (fn [[k {:keys [invites blocks]}]]
                          (let [v (- (occurrences t invites) (occurrences t blocks))]
                            (when (pos? v) [k v])))
                        register-cues))]
    (if (or (nil? lx) (< (count t) min-chars))
      {:target label :chars (count t) :sentences n :lang lang
       :render (js-dependent html t)
       :unmeasurable (cond (and (< (count t) min-chars)
                                (:js-dependent? (js-dependent html t)))
                           (str "server 本文が " (count t) " 字（床 " min-chars "）で mount point あり"
                                " —— **取得の失敗ではなく、内容が client 描画**。"
                                "この面のコピーを測るには描画後の DOM が要る")
                           (< (count t) min-chars)
                           (str "本文が " (count t) " 字（床 " min-chars "）—— 取得か抽出に失敗している")
                           :else
                           "言語を判定できない（日本語 15% 超でも英語 2% 未満でもない）")}
      {:target label
     :chars (count t)
     :sentences n
     :lang lang
     :mixed? (mixed? t)
     :render (js-dependent html t)
     :sales {:negation-sentences (count neg-sents)
             :negation-ratio-pct (r1 (pct (count neg-sents) n))
             :negation-hits (occurrences t negation)
             :hedge-hits (occurrences t hedge)
             :hero-sentences (count hero-sents)
             :hero-negation-sentences (count hero-neg)
             :hero-first-negation-at first-neg-idx
             :concrete-numbers concrete
             :avg-sentence-chars (r1 avg-len)}
     :lp {:cta-words cta-n
          :buy-links buy-links
          :sections h2
          :cta-per-section (r1 (pct cta-n (max 1 h2)))}
     :register (if (= lang :ja)
                 {:addressed (into (sorted-map) reg)
                  :labels-with-cues (count register-cues)
                  :labels-total hume-label-count
                  :unaddressed (- hume-label-count (count register-cues))}
                 {:unmeasurable "register の手がかりは日本語のみ定義してある"})})))

;; ---------------------------------------------------------------------------
;; 出力
;; ---------------------------------------------------------------------------

(defn- row [k v] (println (str "  " (.padEnd (str k) 30) v)))

(defn report [{:keys [target chars sentences sales lp register lang unmeasurable] :as m}]
  (println (str "\n── " target))
  (row "本文字数 / 文数 / 言語" (str chars " / " sentences " / " (or (some-> lang name) "?")
                                    (when (:mixed? m) "（混合。優勢な方で採点）")))
  (when (:js-dependent? (:render m))
    (row "⚠ JS 依存" (str "server 本文 " (:server-text-chars (:render m)) " 字 + mount point あり"
                           " —— クローラと初回表示が見るのはこれだけ")))
  (if unmeasurable
    (row "UNMEASURABLE" (str "**" unmeasurable "**"))
    (do
  (println "  ── sales")
  (row "否定文の割合" (str (:negation-ratio-pct sales) "%  ("
                           (:negation-sentences sales) "/" sentences " 文)"))
  (row "否定表現の総数" (:negation-hits sales))
  (row "留保表現の総数" (:hedge-hits sales))
  (row "冒頭の否定文" (str (:hero-negation-sentences sales) "/" (:hero-sentences sales)
                            (if-let [i (:hero-first-negation-at sales)]
                              (str "  最初の否定は " (inc i) " 文目")
                              "  （冒頭に否定なし）")))
  (row "具体数の出現" (:concrete-numbers sales))
  (row "平均文長" (str (:avg-sentence-chars sales) " 字"))
  (println "  ── lp")
  (row "CTA 語 / 購入リンク" (str (:cta-words lp) " / " (:buy-links lp)))
  (row "節数 / 節あたり CTA" (str (:sections lp) " / " (:cta-per-section lp) "%"))
  (println "  ── register（Hume 48 ラベルの語彙に対する**代理指標**）")
  (doseq [[k v] (or (:addressed register) {})]
    (row k v))
  (if-let [u (:unmeasurable register)]
    (row "UNMEASURABLE" (str "**" u "**"))
    (row "⚠ 手がかり未定義" (str (:unaddressed register) "/" (:labels-total register)
                                 " ラベル —— **0 ではなく未測定**"))))))

(defn -main []
  (if (empty? targets)
    (do (println "usage: nbb scripts/lp-copy-score.cljs <url|file> [...]") (js/process.exit 2))
    (let [rs (mapv (fn [t] (score-one t (fetch t))) targets)]
      (if (flag? "--json")
        (println (js/JSON.stringify (clj->js rs) nil 2))
        (do (println "lp-copy-score —— 決定論的。LLM の採点は使っていない。")
            (println "⚠ :register は **Hume のモデル出力ではない**（同じ 48 ラベルの語彙に対する語彙的代理）。")
            (doseq [r rs] (report r))
            (println "\n**単独の値は読めない。同じ計器で測った別の面と比べること。**")))))
  nil)

(-main)
