(ns spotwork.governor
  "単発シフト提案の **独立 governor**。提案を作る側（`spotwork.match`）とは別系統で、
  `spotwork.facts` の条文カタログだけを根拠に 可決 / 保留 / 拒否 を返す。

  ## 不変条件

  1. **judge の 3 値を混ぜない。** 各規則は `:pass` / `:violated` / `:not-measured`
     のいずれかを返す。「測れなかった」は「測って問題が無かった」と**別の値**で、
     verdict でも決して `:pass` に畳まれない（ADR-2608136000）。
  2. **拒否は理由の literal で表す。** `[:error :rule/id …]` の形で返し、真偽値で
     返さない —— truthy 判定は「空でない拒否」を成功として通す
     （CLAUDE.md が記録した `tls.cert` の実事故と同じ形）。
  3. **マッチ確定と就業確定はどの phase でも自動化しない。** `phase-table` の
     `:auto` 集合に `:actuation/confirm-match` / `:actuation/place-worker` は
     どの行にも入らない。人間のオペレータだけが確定する。
     兄弟 actor（`cloud-itonami-isic-7810` / `-7820`）と同じ形。"
  (:require [spotwork.facts :as facts]
            [spotwork.time :as t]
            [clojure.string :as str]))

;; ---------------------------------------------------------------- actuation

(def phase-table
  "phase → 自動実行してよい操作。**どの phase にも確定系は入らない。**"
  {:phase/shadow   {:auto #{}}
   :phase/advisory {:auto #{:actuation/draft-proposal}}
   :phase/assisted {:auto #{:actuation/draft-proposal :actuation/rank-cohorts}}
   :phase/operating {:auto #{:actuation/draft-proposal :actuation/rank-cohorts
                               :actuation/notify-operator}}})

(def never-auto
  "phase 表とは独立の第 2 の層。表を書き換えても、ここが確定系を止める。"
  #{:actuation/confirm-match :actuation/place-worker})

(defn auto-allowed?
  "その phase でその操作を人手なしに実行してよいか。"
  [phase actuation]
  (and (not (contains? never-auto actuation))
       (contains? (get-in phase-table [phase :auto] #{}) actuation)))

;; ---------------------------------------------------------------- PII 走査

(def ^:private pii-key-re
  #"(?i)(name|email|mail|phone|tel|address|birth|dob|mynumber|my-number|passport|ssn|account|resident)")

(def ^:private email-re #"[^@\s]+@[^@\s]+\.[^@\s]+")

(defn pii-hits
  "任意の入れ子構造から個人識別子らしいものを拾う。key 名と、値の形（メール）を
  見る。**見つからないことは「PII が無い」の証拠にはなるが、走査対象が空でも
  同じ答えになる**ので、呼び手は走査した節点数も一緒に受け取る。"
  [x]
  (let [hits (atom [])
        n (atom 0)]
    (letfn [(walk [node path]
              (swap! n inc)
              (cond
                (map? node)
                (doseq [[k v] node]
                  (let [kn (if (keyword? k) (str (namespace k) "/" (name k)) (str k))]
                    (when (re-find pii-key-re kn)
                      (swap! hits conj {:path (conj path k) :why :key-name :key kn}))
                    (walk v (conj path k))))

                (coll? node)
                (doseq [[i v] (map-indexed vector node)] (walk v (conj path i)))

                (string? node)
                (when (re-find email-re node)
                  (swap! hits conj {:path path :why :email-shaped}))

                :else nil))]
      (walk x []))
    {:hits @hits :scanned @n}))

;; ---------------------------------------------------------------- 個々の判定

(defn- judge [status detail] {:status status :detail detail})

(def ^:private pass (judge :pass nil))
(defn- unmeasured [why] (judge :not-measured {:why why}))
(defn- violated [detail] (judge :violated detail))

(defn- night-overlap-min
  "深夜（22:00–05:00）とシフトの重なり。読めなければ nil。"
  [offer]
  (when-let [segs (t/segments (:offer/start offer) (:offer/end offer))]
    (t/overlap-minutes segs [[1320 t/day-minutes] [0 300]])))

(defn- check-minimum-wage [offer operator]
  (let [wage (:offer/hourly-wage offer)
        region (:offer/region offer)
        floor (get-in operator [:minimum-wage region])]
    (cond
      (not (number? wage)) (unmeasured :offer/hourly-wage-missing)
      (nil? region) (unmeasured :offer/region-missing)
      ;; 額は条文に無い。渡されていないなら測れていない（:pass にしない）
      (not (number? floor)) (unmeasured :operator/minimum-wage-not-supplied)
      (< wage floor) (violated {:wage wage :floor floor :region region})
      :else pass)))

(defn- working-or-why
  "労働時間、または**測れなかった理由**。時刻が読めないのと休憩が未申告なのは
  別の欠落なので、同じ理由名に畳まない（畳むと差し戻す先を間違える）。"
  [offer]
  (let [span (t/span-minutes (:offer/start offer) (:offer/end offer))]
    (cond
      (nil? span) [nil :offer/hours-unreadable]
      (not (number? (:offer/break-minutes offer))) [nil :offer/break-undeclared]
      :else [(t/working-minutes (:offer/start offer) (:offer/end offer)
                                (:offer/break-minutes offer))
             (when (nil? (t/working-minutes (:offer/start offer) (:offer/end offer)
                                            (:offer/break-minutes offer)))
               :offer/break-exceeds-span)])))

(defn- check-daily-cap [offer]
  (let [[work why] (working-or-why offer)]
    (cond
      (nil? work) (unmeasured why)
      (<= work 480) pass
      (nil? (:offer/agreement-36? offer)) (unmeasured :offer/agreement-36-undeclared)
      (false? (:offer/agreement-36? offer)) (violated {:working-minutes work :cap 480})
      :else pass)))

(defn- check-overtime-premium [offer]
  (let [[work why] (working-or-why offer)
        rate (:offer/overtime-premium-rate offer)]
    (cond
      (nil? work) (unmeasured why)
      (<= work 480) pass
      (not (number? rate)) (unmeasured :offer/overtime-premium-undeclared)
      (< rate 1.25) (violated {:rate rate :required 1.25 :working-minutes work})
      :else pass)))

(defn- check-night-premium [offer]
  (let [ov (night-overlap-min offer)
        rate (:offer/night-premium-rate offer)]
    (cond
      (nil? ov) (unmeasured :offer/hours-unreadable)
      (zero? ov) pass
      (not (number? rate)) (unmeasured :offer/night-premium-undeclared)
      (< rate 1.25) (violated {:rate rate :required 1.25 :night-minutes ov})
      :else pass)))

(defn- check-break [offer]
  (let [span (t/span-minutes (:offer/start offer) (:offer/end offer))
        brk (:offer/break-minutes offer)]
    (cond
      (nil? span) (unmeasured :offer/hours-unreadable)
      (not (number? brk)) (unmeasured :offer/break-undeclared)
      ;; 休憩が拘束時間を超えている申告は矛盾していて、労働時間が負になる。
      ;; **これを pass にしない** —— 負の労働時間は required 0 を満たすので、
      ;; 何も足さないと「休憩は足りている」として通る（この系が防ごうとして
      ;; いる形そのもの）。
      (> brk span) (unmeasured :offer/break-exceeds-span)
      :else
      (let [work (- span brk)
            required (cond (> work 480) 60 (> work 360) 45 :else 0)]
        (if (< brk required)
          (violated {:break brk :required required :working-minutes work})
          pass)))))

(defn- check-minor-night [offer]
  (let [ov (night-overlap-min offer)
        min-age (:offer/min-age offer)]
    (cond
      (nil? ov) (unmeasured :offer/hours-unreadable)
      (zero? ov) pass
      (not (number? min-age)) (unmeasured :offer/min-age-undeclared)
      (< min-age 18) (violated {:min-age min-age :night-minutes ov})
      :else pass)))

(defn- check-minimum-age [offer]
  (let [min-age (:offer/min-age offer)]
    (cond
      (not (number? min-age)) (unmeasured :offer/min-age-undeclared)
      (< min-age 15) (violated {:min-age min-age :required 15})
      :else pass)))

(defn- check-wage-deduction [offer]
  (let [d (:offer/wage-deductions offer)]
    (cond
      (nil? d) (unmeasured :offer/wage-deductions-undeclared)
      (seq d) (violated {:deductions (vec d)})
      :else pass)))

(defn- check-penalty [offer]
  (let [p (:offer/penalty-predetermined? offer)]
    (cond
      (nil? p) (unmeasured :offer/penalty-undeclared)
      (true? p) (violated {:penalty true})
      :else pass)))

(defn- check-fee-payer [offer]
  (let [payer (get-in offer [:offer/fee :fee/payer])]
    (cond
      (nil? payer) (unmeasured :offer/fee-payer-undeclared)
      (= :jobseeker payer) (violated {:payer payer})
      :else pass)))

(defn- check-authorization [offer cohort]
  (let [required (:offer/work-authorization-required? offer)]
    (cond
      (nil? required) (unmeasured :offer/work-authorization-requirement-undeclared)
      (false? required) pass
      (nil? cohort) (unmeasured :cohort/absent)
      (nil? (:cohort/work-authorization-verified? cohort))
      (unmeasured :cohort/work-authorization-unknown)
      (false? (:cohort/work-authorization-verified? cohort))
      (violated {:cohort (:cohort/id cohort)})
      :else pass)))

(defn- check-pii [proposal]
  (let [{:keys [hits scanned]} (pii-hits proposal)]
    (cond
      (zero? scanned) (unmeasured :proposal/empty)
      (seq hits) (violated {:hits (vec (take 8 hits)) :scanned scanned})
      :else pass)))

(defn- check-k-anonymity [cohort jurisdiction]
  (let [k (or (facts/threshold jurisdiction :cohort/below-k-anonymity :k) 5)]
    (cond
      (nil? cohort) (unmeasured :cohort/absent)
      (not (number? (:cohort/size cohort))) (unmeasured :cohort/size-unknown)
      (< (:cohort/size cohort) k) (violated {:size (:cohort/size cohort) :k k})
      :else pass)))

(defn- check-supply [offer cohort]
  (let [need (:offer/headcount offer)
        have (:cohort/size cohort)]
    (cond
      (not (number? need)) (unmeasured :offer/headcount-undeclared)
      (not (number? have)) (unmeasured :cohort/size-unknown)
      (< have need) (violated {:size have :headcount need})
      :else pass)))

;; ---------------------------------------------------------------- 集約

(defn- checks
  "規則 id → judge。`facts` に載っている規則を全部埋める（**片方だけ増えると
  規則が黙って無検査になる**ので、下の `assert-coverage` が突き合わせる）。"
  [{:keys [offer cohort proposal operator]}]
  {:wage/below-minimum (check-minimum-wage offer operator)
   :hours/over-daily-cap-without-36 (check-daily-cap offer)
   :hours/overtime-premium-missing (check-overtime-premium offer)
   :hours/night-premium-missing (check-night-premium offer)
   :break/insufficient (check-break offer)
   :minor/night-work (check-minor-night offer)
   :minor/under-minimum-age (check-minimum-age offer)
   :wage/deduction-from-pay (check-wage-deduction offer)
   :wage/penalty-predetermined (check-penalty offer)
   :fee/charged-to-jobseeker (check-fee-payer offer)
   :authorization/unverified (check-authorization offer cohort)
   :privacy/pii-in-proposal (check-pii (or proposal {:offer offer :cohort cohort}))
   :cohort/below-k-anonymity (check-k-anonymity cohort (:offer/jurisdiction offer))
   :supply/insufficient-cohort (check-supply offer cohort)})

(defn assert-coverage
  "`facts` の規則と、この governor が実際に走らせる検査が 1 対 1 か。
  `[:ok n]` / `[:error :coverage/mismatch {…}]`。

  これが無いと、カタログに規則を足しただけで「規則は在るが誰も見ていない」
  状態が緑のまま成立する。"
  [jurisdiction]
  (let [declared (facts/rule-ids jurisdiction)
        ;; 空の offer でも checks は全 key を返す（judge は :not-measured になる）
        implemented (set (keys (checks {:offer {:offer/jurisdiction jurisdiction}})))]
    (if (= declared implemented)
      [:ok (count declared)]
      [:error :coverage/mismatch
       {:declared-only (vec (sort (remove implemented declared)))
        :implemented-only (vec (sort (remove declared implemented)))}])))

(defn review
  "提案を審査する。

  入力 `{:offer … :cohort … :proposal … :operator …}`。
  返り値:

  ```clojure
  {:verdict :block|:hold|:pass
   :findings [{:rule/id … :status … :detail … :severity … :basis […]}]
   :counts {:violated n :not-measured n :pass n}
   :jurisdiction :jp}
  ```

  verdict の決め方（この順に、最初に当たったもの）:

  1. 法域が未収載 → `:block`（知らない法域の提案を通さない）
  2. `:block` 規則に違反 → `:block`
  3. `:not-measured` が 1 つでも → `:hold`。**`:pass` に畳まない**
  4. `:hold` 規則に違反 → `:hold`
  5. それ以外 → `:pass`"
  [{:keys [offer] :as input}]
  (let [j (:offer/jurisdiction offer)]
    (if-not (facts/known-jurisdiction? j)
      {:verdict :block
       :jurisdiction j
       :counts {:violated 1 :not-measured 0 :pass 0}
       :findings [{:rule/id :jurisdiction/unknown
                   :status :violated
                   :severity :block
                   :detail {:jurisdiction j :known (vec (sort (map name (facts/jurisdictions))))}
                   :basis []}]}
      (let [rs (facts/rules j)
            judged (checks input)
            findings (vec (for [[rid {:keys [status detail]}] (sort-by (comp str key) judged)
                                :let [r (get rs rid)]]
                            {:rule/id rid
                             :status status
                             :severity (:rule/severity r)
                             :label (:rule/label r)
                             :detail detail
                             :basis (mapv #(select-keys % [:basis/law :basis/article
                                                           :basis/url :basis/url-status])
                                          (:rule/basis r))}))
            by (group-by :status findings)
            blocked (some #(and (= :violated (:status %)) (= :block (:severity %))) findings)
            unmeasured? (seq (get by :not-measured))
            held (some #(and (= :violated (:status %)) (= :hold (:severity %))) findings)]
        {:verdict (cond blocked :block
                        unmeasured? :hold
                        held :hold
                        :else :pass)
         :jurisdiction j
         :counts {:violated (count (get by :violated []))
                  :not-measured (count (get by :not-measured []))
                  :pass (count (get by :pass []))}
         :findings findings}))))

(defn blocking-findings [review-result]
  (filterv #(and (= :violated (:status %)) (= :block (:severity %))) (:findings review-result)))

(defn why-not-pass
  "`:pass` でない理由を人が読める行にする。`:pass` なら nil。"
  [review-result]
  (when (not= :pass (:verdict review-result))
    (->> (:findings review-result)
         (remove #(= :pass (:status %)))
         (map (fn [f]
                ;; 名前空間を落とさない —— `:wage/below-minimum` と
                ;; `:cohort/below-k-anonymity` は name だけだと紛らわしい
                (str (subs (str (:rule/id f)) 1) "=" (name (:status f))
                     (when-let [w (get-in f [:detail :why])]
                       (str "(" (subs (str w) 1) ")")))))
         (str/join " "))))
