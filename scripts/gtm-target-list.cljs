#!/usr/bin/env nbb
;; gtm-target-list.cljs — 90-docs/business/gtm-icp.datoms.edn の ICP を
;; cloud-itonami-lei catalog（D1、live）に当てて、実際に出る対象企業を返す。
;;
;; ## この出力が「何であって、何でないか」
;;
;; 返るのは **ICP 適合企業ではない。** ICP のうち今日のデータ面で評価できた述語
;; （国 + 連絡経路）にだけ適合した企業である。業種・規模・技術スタック・公表
;; コンプライアンス姿勢は catalog に属性が無く（実測 2026-08-04: isic_rev5 は
;; 185 社中 6 社、sector は 1 社）、評価されていない。
;;
;; だから出力 EDN は `:target-list/unevaluated` を必ず持ち、評価できなかった
;; 述語を名指しで運ぶ。ここを落とすと、リストを受け取った側には
;; 「ICP に適合した N 社」に見えてしまう —— 実際には「国と連絡先だけ合っていた
;; N 社」であるのに。両者を同じ言葉で呼ばないための機構であって、飾りではない。
;;
;; ## 生成しないもの
;;
;; outbound の下書きを作らない。送らない。ここは対象の抽出だけで、送信は
;; cloud-itonami.marketing/propose-outreach!（:external-send・承認キュー）が
;; 担う。status が active でない ICP（watch-only / excluded /
;; pending-owner-review）は理由を出して**生成そのものを拒否する** —— 保留中の
;; 判断を、リストが出てしまったという既成事実で追い越さないため。
;;
;; 実行（superproject root から）:
;;   nbb scripts/gtm-target-list.cljs --icp murakumo-eu [--out <file.edn>]
;;   nbb scripts/gtm-target-list.cljs --list

(ns gtm-target-list
  (:require ["fs" :as fs]
            ["child_process" :refer [execSync]]
            [clojure.string :as str]
            [cljs.reader :as edn]))

(def argv (vec (drop 2 (js->clj (.-argv js/process)))))
(defn- flag [n] (let [i (.indexOf (clj->js argv) n)]
                  (when (and (>= i 0) (< (inc i) (count argv))) (nth argv (inc i)))))

(def icp-file "90-docs/business/gtm-icp.datoms.edn")
(def db-name "cloud-itonami-lei-catalog")

(defn- load-icps []
  (->> (edn/read-string (fs/readFileSync icp-file "utf8"))
       (filter :icp/id)))

;; ── D1 ──────────────────────────────────────────────────────────────────────

(defn- d1 [sql]
  (let [out (execSync (str "npx wrangler d1 execute " db-name " --remote -y --json --command "
                           (pr-str sql))
                      #js {:encoding "utf8" :maxBuffer (* 40 1024 1024) :stdio "pipe"})
        i (.indexOf out "[")]
    (when (neg? i) (throw (js/Error. (str "no JSON in wrangler output: " (subs out 0 200)))))
    (get-in (first (js->clj (js/JSON.parse (subs out i)) :keywordize-keys true)) [:results])))

(defn- sql-in [xs] (str/join "," (map #(str "'" % "'") xs)))

;; ── selection ───────────────────────────────────────────────────────────────

(defn- checkable [icp] (edn/read-string (or (:icp/checkable-edn icp) "{}")))

(defn select-targets
  "Applies ONLY the checkable predicates. Country comes from the ICP's own
  `:country` set rather than `:icp/countries` so that what ran and what was
  declared cannot drift apart -- the same reason the loop shells out to the
  scripts instead of reimplementing them."
  [icp]
  (let [{:keys [country contact-route]} (checkable icp)]
    (if (empty? country)
      []
      (d1 (str "SELECT lei, country, legal_name, website, contact_email, inquiry_form_url, repo "
               "FROM company WHERE country IN (" (sql-in (sort country)) ")"
               (when (= :required contact-route)
                 " AND (contact_email IS NOT NULL OR inquiry_form_url IS NOT NULL)")
               " ORDER BY country, legal_name;")))))

(defn- ir-only?
  "The disqualifier the catalog CAN evaluate: a contact route that is visibly an
  investor-relations / privacy / press / ticketing desk does not reach a buyer.

  The role word is matched as a PREFIX of ANY dot-separated segment of the
  local part, because two real addresses in this catalog defeat the narrower
  readings: `fahrkartenservice@bahn.de` is a rail ticketing desk that an
  exact-token rule let through, and `ratpdev.communication@ratpdev.com` is a
  press desk that a first-segment-only rule let through. Both were being
  proposed as sales targets.

  Only applied to `contact_email`. A generic `/contact` FORM is a real
  front-door and is not disqualified."
  [{:keys [contact_email]}]
  (let [local (first (str/split (str contact_email) #"@"))
        desk? #(re-find #"(?i)^(ir|investors?|privacy|dpo|dataprotection|gdpr|fahrkarten|ticket|press|media|communications?)[a-z]*$" %)]
    (boolean (and (seq local) (some desk? (str/split local #"\."))))))

;; ── report ──────────────────────────────────────────────────────────────────

(defn- print-list []
  (println "ICP:")
  (doseq [i (sort-by (juxt :icp/product :icp/priority) (load-icps))]
    (println (str "  " (:icp/id i)
                  "  [" (:icp/status i) "]"
                  "  " (:icp/product i)
                  "  countries=" (pr-str (:icp/countries i))))))

(defn- blocked-reason [icp]
  (case (:icp/status icp)
    "active" nil
    "watch-only" (str "status=watch-only。この ICP は outbound を生成しない。"
                      "解除条件: " (:icp/unblock-condition icp))
    "excluded" (str "status=excluded。非ターゲットとして明示的に登録されている。根拠: "
                    (:icp/exclusion-evidence icp))
    "pending-owner-review" (str "status=pending-owner-review。国スコープが owner 決定ではなく導出。"
                                (:icp/countries-basis icp))
    (str "status=" (:icp/status icp) " は active ではない")))

(defn -main []
  (cond
    (some #{"--list"} argv) (print-list)

    :else
    (let [id (flag "--icp")
          icp (first (filter #(= id (:icp/id %)) (load-icps)))]
      (cond
        (nil? id) (do (println "usage: nbb scripts/gtm-target-list.cljs --icp <id> [--out <file>]")
                      (println) (print-list) (.exit js/process 2))
        (nil? icp) (do (println "no such ICP:" id) (print-list) (.exit js/process 2))
        :else
        (if-let [why (blocked-reason icp)]
          (do (println (str "REFUSED  " id))
              (println (str "  " why))
              (println "  ICP の status を変えるのは owner の判断。ここでは生成しない。")
              (.exit js/process 3))
          (let [rows (select-targets icp)
                {kept false disq true} (group-by ir-only? rows)
                unevaluated (edn/read-string (or (:icp/unevaluable-edn icp) "[]"))
                out {:target-list/icp id
                     :target-list/product (:icp/product icp)
                     :target-list/as-of (first (str/split (.toISOString (js/Date.)) #"T"))
                     :target-list/source "cloud-itonami-lei-catalog (D1, live)"
                     :target-list/checked (checkable icp)
                     :target-list/unevaluated unevaluated
                     :target-list/matched-on-checkable-only (count rows)
                     :target-list/disqualified-ir-only (count disq)
                     :target-list/targets (vec (map #(select-keys % [:lei :country :legal_name :website
                                                                     :contact_email :inquiry_form_url :repo])
                                                    kept))}]
            (println (str "ICP " id "  (" (:icp/product icp) ")"))
            (println (str "  checkable  : " (pr-str (checkable icp))))
            (println (str "  matched    : " (count rows) " 社（評価できた述語にのみ適合）"))
            (println (str "  disqualified: " (count disq) " 社（IR/privacy/発券窓口しか到達経路が無い）"))
            (println (str "  → targets  : " (count kept) " 社"))
            (doseq [r (sort-by (juxt :country :legal_name) kept)]
              (println (str "     " (:country r) "  " (:legal_name r)
                            "  " (or (:contact_email r) (:inquiry_form_url r)))))
            (println)
            (println (str "  評価できなかった述語（" (count unevaluated) " 件） —— この一覧を落とさないこと:"))
            (doseq [u unevaluated]
              (println (str "     " (:predicate u) " : want " (pr-str (:want u)) " — " (:why u))))
            (when-let [o (flag "--out")]
              (fs/writeFileSync o (str (pr-str out) "\n"))
              (println) (println "  wrote" o))))))))

(-main)
