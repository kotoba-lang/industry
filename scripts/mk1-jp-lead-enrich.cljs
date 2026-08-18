(ns mk1-jp-lead-enrich
  "MK-1 日本リードの肉付け。90-docs/business/mk1-jp-prospect-pool.datoms.edn の
   法人番号 1,014 件に、gBizINFO と各社サイトから属性と連絡先を付ける。

   段は 3 つ。**それぞれ独立に走らせ、途中結果を必ずファイルに残す**（1,014 件を
   1 回で通すと途中で落ちたときに全部やり直しになる）。

     enrich   法人番号 → gBizINFO API → company_url / capital_stock /
              employee_number / business_items / representative_name
              （**メールは gBizINFO に無い。**実測 2026-08-18、swagger の HojinInfo 全 30 項目を確認）
     qualify  資本金・従業員数 → 中小企業者等の該当性（経営強化税制の入口）
     contact  company_url → 問い合わせページ → メールアドレス / フォーム URL
              **併記の『送信を拒否する』表示を必ず拾う**（特定電子メール法の例外が外れる）

   ■ 到達手段の法的な床（2026-08-18 確認）
   特定電子メール法はオプトイン規制だが、**企業が公表しているメールアドレス宛の
   広告宣伝メールは例外**。ただし **アドレスと併記で受信拒否の表示がある場合は例外に
   ならない**。したがって contact 段は住所を拾うだけでなく **拒否表示の有無を必ず記録**し、
   拒否表示があるものは `:contact/opt-out-declared true` にして送信対象から外す。

   ■ 数えられなかったものを 0 件にしない（CLAUDE.md の 5 問）
   各段は scanned / hit / miss / error を必ず出し、**scanned=0 を成功として返さない**。
   トークン未設定・入力不在は exit 2（0 でも 1 でもない = 『答えられなかった』）。"
  (:require [clojure.string :as str]
            [cljs.reader :as reader]
            ["fs" :as fs]))

(def pool-path "90-docs/business/mk1-jp-prospect-pool.datoms.edn")
(def out-dir   "90-docs/business/")
(def gbiz-base "https://api.info.gbiz.go.jp/hojin/v1/hojin/")

(defn die2 [msg] (println (str "CANNOT ANSWER — " msg)) (js/process.exit 2))
(defn slurp-edn [p]
  (when-not (fs/existsSync p) (die2 (str p " が無い")))
  (reader/read-string (str (fs/readFileSync p))))
(defn spit-edn [p x] (fs/writeFileSync p (with-out-str (pr x))))
(defn sleep [ms] (js/Promise. (fn [res] (js/setTimeout res ms))))
;; NOTE: この nbb では `js/await` が動かない（Function.prototype.apply called on undefined）。
;; 全段を **promise チェーン**で書く。逐次実行は `serial` が担う。
(defn serial
  "xs を 1 件ずつ f に通し、結果のベクタに解決する promise を返す。並列にしない
   （相手のサーバに同時接続しないため）。"
  [f xs]
  (reduce (fn [acc x] (.then acc (fn [v] (.then (f x) (fn [r] (conj v r))))))
          (.resolve js/Promise []) xs))

(defn prospects [] (filter #(= :prospect (:pool/kind %)) (slurp-edn pool-path)))


;; ── qualify ─────────────────────────────────────────────────────────────────
;; 経営強化税制の『中小企業者等』は業種で閾値が違う。ここは**粗いふるい**であって
;; 判定ではない。最終判定は顧客の税理士（税理士法52条の床）。
(defn qualify [{:keys [company/capital company/employees] :as p}]
  (let [cap (when capital (js/Number capital))
        emp (when employees (js/Number employees))]
    (assoc p :qual/sme
           (cond (and cap (> cap 100000000)) :out-capital-over-100m
                 (and emp (> emp 1000))      :out-employees-over-1000
                 (or cap emp)                :likely-in
                 :else                       :unknown))))

;; ── enrich ──────────────────────────────────────────────────────────────────
(defn enrich-one [tok p]
  (-> (js/fetch (str gbiz-base (:company/houjin-bangou p))
                #js {:headers #js {"X-hojinInfo-api-token" tok "Accept" "application/json"}})
      (.then (fn [r] (if (.-ok r) (.json r) nil)))
      (.catch (fn [_] nil))
      (.then (fn [r]
               (let [h (some-> r (aget "hojin-infos") (aget 0))]
                 (.then (sleep 350)
                        (fn [_]
                          (cond-> p
                            ;; 実測 2026-08-18（30件）: postal_code は全件、capital_stock は 0 件。
                            ;; gBizINFO が厚いのは政府の仕組みに登場した法人だけ。資本金での
                            ;; 資格審査はできない。**郵便番号が埋まることが郵送DMには効く。**
                            h (assoc :company/postal (aget h "postal_code")
                                     :company/location (aget h "location")
                                     :company/status (aget h "status")
                                     :company/url (aget h "company_url")
                                     :company/capital (aget h "capital_stock")
                                     :company/employees (aget h "employee_number")
                                     :company/items (aget h "business_items")
                                     :company/rep (aget h "representative_name")
                                     :company/rep-pos (aget h "representative_position")
                                     :company/gov-qualified (aget h "qualification_grade")
                                     :company/established (aget h "date_of_establishment"))))))))))

(defn enrich! [limit]
  (let [tok (or (.-GBIZ_TOKEN js/process.env)
                (die2 "GBIZ_TOKEN が未設定。https://info.gbiz.go.jp/hojin/DownloadTop の『アクセストークンを取得する』から申請する"))
        xs (vec (cond->> (prospects) limit (take limit)))]
    (when (zero? (count xs)) (die2 "prospect が 0 件"))
    (.then (serial (partial enrich-one tok) xs)
           (fn [acc]
             (spit-edn (str out-dir "mk1-jp-leads-enriched.edn") (mapv qualify acc))
             (println (str "ENRICH\tscanned=" (count acc)
                           "\tpostal=" (count (filter :company/postal acc))
                           "\trep=" (count (filter :company/rep acc))
                           "\temployees=" (count (filter :company/employees acc))
                           "\tcapital=" (count (filter :company/capital acc))
                           "\turl=" (count (filter :company/url acc))
                           "\tgov-qual=" (count (filter :company/gov-qualified acc))
                           "\tapi-miss=" (count (remove :company/postal acc))))
             (when (zero? (count acc)) (die2 "1 件も走査していない"))))))

;; ── contact ─────────────────────────────────────────────────────────────────
(def email-re #"[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}")
(def refuse-re #"(?i)(送信を拒否|受信を拒否|営業.{0,4}(メール|お問い合わせ).{0,6}(ご遠慮|お断り)|勧誘.{0,6}お断り|no soliciting|営業メールお断り)")

(defn extract-contact [html]
  (let [mails (->> (re-seq email-re html)
                   (remove #(re-find #"(?i)\.(png|jpe?g|gif|svg|webp|css|js)$" %))
                   ;; 実測 2026-08-18（68 サイト）: 素朴な抽出は 11 件を拾ったが、うち 5 件は
                   ;; **Wix / Sentry の埋め込みテレメトリ**と no-reply で、連絡先ではなかった。
                   ;; **検査が過大に報告していた。**実数は 6 件。
                   (remove #(re-find #"(?i)@(sentry|[^@]*wixpress|sentry-next)" %))
                   (remove #(re-find #"(?i)^(no-?reply|donotreply|noreply)@" %))
                   (remove #(str/includes? % "example."))
                   distinct vec)
        forms (->> (re-seq #"(?i)href=\"([^\"]*(?:contact|inquiry|toiawase|form)[^\"]*)\"" html)
                   (map second) distinct (take 3) vec)]
    {:contact/emails mails
     :contact/form-paths forms
     ;; ⚠ **トップページしか見ていない。**営業お断りの表示は問い合わせページ側に出ることが
     ;; 多く、その場合ここは false になる ——「表示が無い」と「見ていない」が同じ値になる
     ;; （CLAUDE.md の 5 問の 1 番目）。**フォーム経路まで辿るまで、この値を『拒否表示は
     ;; 無い』の根拠に使わない。**
     :contact/opt-out-scanned :top-page-only
     :contact/opt-out-declared (boolean (re-find refuse-re html))
     :contact/scanned-bytes (count html)}))

(defn fetch-text [url]
  (-> (js/fetch url #js {:headers #js {"User-Agent" "Mozilla/5.0 (compatible; murakumo-lead-research/1.0)"}
                         :redirect "follow"})
      (.then (fn [r] (if (.-ok r) (.text r) nil)))
      (.catch (fn [_] nil))))

(defn contact-one [p]
  (-> (fetch-text (:company/url p))
      (.then (fn [html]
               (.then (sleep 1200)
                      (fn [_] (merge p (if html (extract-contact html)
                                           {:contact/error :unreachable}))))))))

(defn contact! [limit]
  (let [xs (vec (->> (slurp-edn (str out-dir "mk1-jp-leads-enriched.edn"))
                     (filter :company/url) (#(cond->> % limit (take limit)))))]
    (when (zero? (count xs)) (die2 "company_url を持つ行が 0 件。enrich を先に走らせる"))
    (.then (serial contact-one xs)
           (fn [acc]
             (spit-edn (str out-dir "mk1-jp-leads-contacts.edn") acc)
             (println (str "CONTACT\tscanned=" (count acc)
                           "\temail=" (count (filter #(seq (:contact/emails %)) acc))
                           "\topt-out-declared=" (count (filter :contact/opt-out-declared acc))
                           "\tunreachable=" (count (filter :contact/error acc))))
             (when (zero? (count acc)) (die2 "1 件も走査していない"))))))

;; ── probe: 抽出ロジックの実証（任意 URL）────────────────────────────────────
(defn probe-one [u]
  (-> (fetch-text u)
      (.then (fn [html]
               (.then (sleep 1200)
                      (fn [_]
                        (if html
                          (let [c (extract-contact html)]
                            (println (str "  " u
                                          "\n     bytes=" (:contact/scanned-bytes c)
                                          "  email=" (pr-str (vec (take 2 (:contact/emails c))))
                                          "  form=" (pr-str (vec (take 1 (:contact/form-paths c))))
                                          "  拒否表示=" (:contact/opt-out-declared c)))
                            :ok)
                          (do (println (str "  " u "  → 到達不能")) :unreachable))))))))

(defn probe! [urls]
  (println (str "PROBE — 抽出ロジックの実証。対象 " (count urls) " 件"))
  (.then (serial probe-one (vec urls))
         (fn [rs]
           (println (str "PROBE\tscanned=" (count rs)
                         "\tok=" (count (filter #(= :ok %) rs))
                         "\tunreachable=" (count (filter #(= :unreachable %) rs))))
           (when (zero? (count rs)) (die2 "1 件も走査していない")))))

(let [[cmd & args] *command-line-args*
      lim (some-> (first (filter #(re-matches #"\d+" (or % "")) args)) js/Number)]
  (case cmd
    "enrich"  (enrich! lim)
    "contact" (contact! lim)
    "probe"   (probe! (remove #(re-matches #"\d+" %) args))
    (do (println "usage: nbb scripts/mk1-jp-lead-enrich.cljs <enrich|contact|probe> [limit|urls...]")
        (println "  enrich  は GBIZ_TOKEN が要る（gBizINFO の申請フォームから取得）")
        (println (str "  pool の prospect: " (count (prospects)) " 件")))))
