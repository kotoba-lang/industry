#!/usr/bin/env bb
;; seed.bb — gftd 経営シムの初期状態と datomic シードを実 facts から構築 (Clojure)。
;;
;; 実 facts(m365-archive/facts) を読み、(1) ゲーム初期 World 状態 と
;; (2) datomic 取り込み用 datom ベクタ を 1 つの EDN マップで stdout に出力する。
;; Rust 側はこの EDN を解析して AppState を満たし、:datoms を transact するだけ。
;; facts→状態の導出ロジックは全てここ(Clojure)に集約する。
;;
;;   bb analysis/seed.bb <facts-dir>
;;   => {:world {...} :datoms [ ... ]}

(require '[clojure.edn :as edn]
         '[clojure.string :as str]
         '[clojure.java.io :as io])

(def facts-dir (or (first *command-line-args*) "../m365-archive/facts"))

(defn read-objs [name]
  (let [path (str facts-dir "/" name)]
    (if (.exists (io/file path))
      (->> (str/split-lines (slurp path))
           (map str/trim)
           (filter #(str/starts-with? % "{"))
           (keep #(try (edn/read-string %) (catch Exception _ nil))))
      [])))

(defn gftd? [s] (let [l (str/lower-case (str s))] (or (str/includes? l "gftd") (str/includes? (str s) "ギフテ"))))

;; ---- 社内人員: :person/role :internal ----
(defn headcount []
  (max 1 (count (filter #(= :internal (:person/role %)) (read-objs "people.edn")))))

;; ---- 商談パイプライン: 実契約(party_b+amount, 外部, 重複排除) → 不足は crm 補完 ----
(defn pipeline []
  (let [deals (->> (read-objs "contract-terms.edn")
                   (keep (fn [c] (let [p (:party_b c) a (:amount_jpy c)]
                                   (when (and (string? p) (seq p) (number? a) (pos? a)
                                              (not (gftd? p)) (not (str/includes? p "ギフテ")))
                                     [p a]))))
                   (reduce (fn [m [p a]] (if (m p) m (assoc m p a))) {})
                   (sort-by (comp - val))
                   (take 12)
                   (mapv (fn [[p a]] [p a])))]
    (if (>= (count deals) 8)
      deals
      (let [have (set (map first deals))
            fill (->> (read-objs "crm.edn")
                      (sort-by #(- (or (:message_count %) 0)))
                      (keep (fn [o] (let [d (:org_domain o)]
                                      (when (and d (not (have d)))
                                        [d (max 500000 (min 30000000 (* 8000 (or (:message_count o) 0))))]))))
                      (take (- 12 (count deals))))]
        (into deals fill)))))

;; ---- プロジェクト top10 ----
(defn projects []
  (->> (read-objs "projects.edn")
       (keep (fn [p] (when-let [n (:gftd.project/name p)]
                       [n (name (or (:gftd.project/status p) :unknown))])))
       (take 10)
       vec))

;; ---- 請求書: gftd 発行(売上) / 受領(コスト) の累計 ----
(defn invoice-totals []
  (reduce (fn [acc v]
            (let [a (:amount_jpy v)]
              (if (and (number? a) (pos? a))
                (cond (gftd? (:issuer v))    (update acc :issued + a)
                      (gftd? (:billed_to v)) (update acc :received + a)
                      :else acc)
                acc)))
          {:issued 0 :received 0} (read-objs "invoice-terms.edn")))

;; ---- 直近(2026)の実活動: 会議 + inbound ----
(defn recent-activity []
  (let [evs (->> (read-objs "events.edn")
                 (filter #(and (string? (:start %)) (str/starts-with? (:start %) "2026")
                               (not= "日本の休日" (:calendar %))))
                 reverse
                 (keep :subject)
                 (map #(str "📅 会議: " %))
                 distinct (take 8))
        tri (->> (read-objs "triage-log.edn")
                 (filter #(and (string? (:received %)) (str/starts-with? (:received %) "2026")))
                 (keep :subject)
                 (remove #(or (str/includes? % "scrum") (str/includes? % "スクラム") (str/starts-with? % "キャンセル")))
                 (map #(str "📨 受信: " %))
                 distinct (take 8))]
    (vec (concat evs tri))))

;; ---- datom ベクタ ----
(defn edn-str [s] (pr-str (str s)))
(defn datoms [hc cash burn pl pj inv]
  (vec (concat
        [{:db/id "company" :sim.company/name "gftdcojp" :sim.company/headcount hc
          :sim.company/cash-jpy cash :sim.company/burn-jpy burn
          :gftd.fin/issued-total-jpy (:issued inv) :gftd.fin/received-total-jpy (:received inv)}]
        (map-indexed (fn [i [p a]] {:db/id (str "pl" i) :gftd.contract/party p
                                    :sim.pipeline/value-jpy a :sim.pipeline/stage :open}) pl)
        (map-indexed (fn [i [n s]] {:db/id (str "pj" i) :gftd.project/name n
                                    :gftd.project/status (keyword s)}) pj)
        (map-indexed (fn [i d] {:db/id (str "hist" i)
                                :gftd.decision/id (str (:id d))
                                :gftd.decision/policy (or (:policy d) :reply)
                                :gftd.decision/at (str (:decided_at d))
                                :gftd.decision/note (str (:note d))
                                :sim.decision/turn 0 :sim.decision/approved true})
                     (read-objs "decisions.edn")))))

(let [hc   (headcount)
      burn (* hc 700000)
      cash (* burn 10)
      pl   (pipeline)
      pj   (projects)
      inv  (invoice-totals)
      pjpy (reduce + 0 (map second pl))]
  (println (pr-str
            {:world {:headcount hc :cash cash :burn burn :pipeline-jpy pjpy
                     :revenue (:issued inv) :issued (:issued inv) :received (:received inv)
                     :pipeline (mapv (fn [[p a]] [p a]) pl)
                     :projects pj
                     :recent-activity (recent-activity)}
             :datoms (datoms hc cash burn pl pj inv)})))
