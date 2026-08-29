#!/usr/bin/env nbb
;; scripts/spotwork-match-tick.cljs — スキマバイト突合 bot の測定段。
;; **決定論。モデルは居ない。読むだけ（git の書き込みを 1 つも呼ばない）。**
;; 姉妹: scripts/isekai-game-dev-tick.cljs / scripts/repo-bots/tick.cljs。
;;
;; ## 何を測るか（順に）
;;
;;   (a) 床 — manifest / 求人 / cohort / 提案台帳が**読めるか**。1 つでも
;;       読めなければ :not-measured（exit 2）。読めなかったものを 0 件として
;;       数えない（ADR-2608136000）。
;;   (b) オペレータ維持データ（地域別最低賃金）の有無。**これは候補ではなく床**
;;       —— bot には直せない（今年度の告示額を運用者が転記するしかない）ので、
;;       報告はするがモデルは起こさない。
;;   (c) 候補。優先順:
;;       1. catalog-defect — 規則カタログが壊れている / 宣言と検査が 1 対 1 でない
;;       2. unreviewed-offer — 台帳に 1 行も無い求人
;;       3. stale-proposal — 求人の内容指紋が最後の提案と違う（賃金や時間が
;;          書き換わっている）
;;       4. 無ければ :no-candidates
;;
;; ## 出力と exit
;;
;;   stdout 最終行に EDN を 1 行。
;;   exit 0 = :candidate または :no-candidates（EDN の :outcome で区別する）
;;   exit 2 = :not-measured。0 でも 1 でもない値。
;;
;; ## --emit <offer-id>
;;
;; その求人の提案 1 行を**決定論的に生成して印字する**（台帳には書かない）。
;; skill はこれを使う —— 提案を手で書かせない。exit 0 / 2。
;;
;;   nbb scripts/spotwork-match-tick.cljs
;;   nbb scripts/spotwork-match-tick.cljs --emit of-fixture-0001-hall-day

(ns spotwork-match-tick
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [spotwork.facts :as facts]
            [spotwork.fingerprint :as fp]
            [spotwork.governor :as gov]
            [spotwork.proposal :as prop]))

(def fs (js/require "node:fs"))
(def os (js/require "node:os"))
(def path (js/require "node:path"))

(def home (.homedir os))
(def root (or (aget (.-env js/process) "COM_JUNKAWASAKI_ROOT")
              (str home "/github/com-junkawasaki")))

(def argv (vec *command-line-args*))
(def emit-id (second (drop-while #(not= "--emit" %) argv)))

(defn- abs [p] (if (str/starts-with? p "/") p (str root "/" p)))
(defn- exists? [p] (try (.existsSync fs p) (catch :default _ false)))
(defn- slurp* [p] (try (.readFileSync fs p "utf8") (catch :default _ nil)))

(defn- read-edn
  "ファイル 1 本 → `{:ok v}` / `{:unreadable p :why …}` / `{:absent p}`。
  **3 値。** 「無い」と「読めない」を同じ値にしない —— 前者は初期状態、
  後者は壊れている。"
  [p]
  (cond
    (not (exists? p)) {:absent p}
    :else (let [txt (slurp* p)]
            (if (nil? txt)
              {:unreadable p :why :unreadable-file}
              (try {:ok (edn/read-string txt)}
                   (catch :default e {:unreadable p :why :edn-parse-error
                                      :message (str e)}))))))

;; ---------------------------------------------------------------- 入力

(defn- load-manifest [] (read-edn (abs "manifest/spotwork.edn")))

(defn- offer-files [dir]
  (if-not (exists? dir)
    nil
    (->> (.readdirSync fs dir)
         (map str)
         (filter #(str/ends-with? % ".edn"))
         sort
         (mapv #(.join path dir %)))))

(defn- load-offers [dir]
  (let [files (offer-files dir)]
    (if (nil? files)
      {:absent dir}
      (let [read (mapv (fn [f] [f (read-edn f)]) files)
            bad (vec (for [[f r] read :when (:unreadable r)] {:file f :why (:why r)}))
            ok (vec (for [[_ r] read :when (:ok r)] (:ok r)))]
        {:listed (count files) :scanned (count ok) :offers ok :unreadable bad}))))

(defn- ledger-lines
  "提案台帳。`;;` と空行を飛ばす。**読めなかった行は数える**（落とすと台帳が
  実際より綺麗に見える）。"
  [p]
  (if-not (exists? p)
    {:absent p :entries [] :unreadable 0 :lines 0}
    (let [txt (or (slurp* p) "")
          lines (->> (str/split-lines txt)
                     (remove str/blank?)
                     (remove #(str/starts-with? (str/triml %) ";;"))
                     vec)
          parsed (mapv (fn [l] (try (edn/read-string l) (catch :default _ ::bad))) lines)]
      {:lines (count lines)
       :entries (vec (remove #(= ::bad %) parsed))
       :unreadable (count (filter #(= ::bad %) parsed))})))

;; ---------------------------------------------------------------- 候補

(defn- catalog-defect []
  (let [c (facts/assert-catalog)
        cov (gov/assert-coverage :jp)]
    (cond
      (= :error (first c)) {:kind :catalog-defect :detail {:assert-catalog c}}
      (= :error (first cov)) {:kind :catalog-defect :detail {:assert-coverage cov}}
      :else nil)))

(defn- latest-by-offer [entries]
  (reduce (fn [acc e]
            (let [k (:proposal/offer-id e)]
              (if (or (nil? (get acc k))
                      (pos? (compare (str (:proposal/at e))
                                     (str (:proposal/at (get acc k))))))
                (assoc acc k e)
                acc)))
          {} entries))

(defn- offer-candidates [offers latest]
  (let [unreviewed (vec (for [o offers
                              :when (nil? (get latest (:offer/id o)))]
                          {:offer-id (:offer/id o) :reason :never-reviewed}))
        stale (vec (for [o offers
                         :let [p (get latest (:offer/id o))]
                         :when (and p (not= (fp/of-offer o)
                                            (:proposal/offer-fingerprint p)))]
                     {:offer-id (:offer/id o)
                      :reason :content-changed
                      :recorded (:proposal/offer-fingerprint p)
                      :current (fp/of-offer o)}))]
    {:unreviewed unreviewed :stale stale}))

(defn- projected [offer cohorts operator]
  (let [e (prop/build offer cohorts {:at "projection" :operator operator})]
    {:verdict (:proposal/verdict e)
     :cohort (:proposal/cohort-id e)
     :why (:proposal/why e)}))

;; ---------------------------------------------------------------- 本体

(defn- gather []
  (let [man (load-manifest)]
    (if-not (:ok man)
      {:not-measured {:input :manifest :detail man}}
      (let [m (:ok man)
            offers-dir (abs (get-in m [:spotwork/source :offers :dir]))
            cohorts-file (abs (get-in m [:spotwork/source :cohorts :file]))
            operator-file (abs (get-in m [:spotwork/source :operator :file]))
            ledger-file (abs (get-in m [:spotwork/ledger :file]))
            offers (load-offers offers-dir)
            cohorts (read-edn cohorts-file)
            operator (read-edn operator-file)
            led (ledger-lines ledger-file)]
        (cond
          (:absent offers) {:not-measured {:input :offers-dir :detail offers}}
          (seq (:unreadable offers)) {:not-measured {:input :offers :detail (:unreadable offers)}}
          (:unreadable cohorts) {:not-measured {:input :cohorts :detail cohorts}}
          (:absent cohorts) {:not-measured {:input :cohorts :detail cohorts}}
          (:unreadable operator) {:not-measured {:input :operator :detail operator}}
          (pos? (:unreadable led)) {:not-measured {:input :ledger
                                                   :detail {:unreadable-lines (:unreadable led)}}}
          :else
          {:manifest m
           :offers (:offers offers)
           :offers-scanned [(:scanned offers) (:listed offers)]
           :cohorts (:ok cohorts)
           :operator (:ok operator)          ; 無ければ nil（床として報告する）
           :operator-absent? (boolean (:absent operator))
           :ledger led})))))

(defn- floors [{:keys [manifest operator-absent? ledger offers cohorts]}]
  (cond-> []
    operator-absent?
    (conj {:floor :operator-data-absent
           :fixable-by :operator
           :effect "地域別最低賃金が測れないので、どの提案も :pass にならない（:hold 止まり）"
           :how (str "cp " (get-in manifest [:spotwork/source :operator :example])
                     " " (get-in manifest [:spotwork/source :operator :file])
                     " して今年度の告示額を一次資料から転記する")})

    (= :fixtures-only (get-in manifest [:spotwork/source :offers :status]))
    (conj {:floor :demand-is-fixtures-only
           :fixable-by :operator
           :effect "ここに並んでいるのは実需要ではない。件数を実績として引用しない"})

    (zero? (:lines ledger))
    (conj {:floor :ledger-empty :fixable-by :bot})

    (empty? offers) (conj {:floor :no-offers :fixable-by :operator})
    (empty? cohorts) (conj {:floor :no-cohorts :fixable-by :operator})))

(defn -main []
  (let [g (gather)]
    (if-let [nm (:not-measured g)]
      (do (println (pr-str (merge {:outcome :not-measured} nm)))
          (js/process.exit 2))
      (let [{:keys [offers cohorts operator ledger]} g
            latest (latest-by-offer (:entries ledger))]
        (if emit-id
          ;; --emit: 提案 1 行を決定論的に生成して印字する（書き込まない）
          (if-let [offer (first (filter #(= emit-id (:offer/id %)) offers))]
            (do (println (prop/line (prop/build offer cohorts
                                                {:operator (or operator {})
                                                 :phase (get-in g [:manifest :spotwork/phase])
                                                 :weights (get-in g [:manifest :spotwork/weights])})))
                (js/process.exit 0))
            (do (println (pr-str {:outcome :not-measured :why :unknown-offer-id
                                  :offer-id emit-id
                                  :known (mapv :offer/id offers)}))
                (js/process.exit 2)))

          (let [defect (catalog-defect)
                {:keys [unreviewed stale]} (offer-candidates offers latest)
                base {:measured-at (.toISOString (js/Date.))
                      :offers-scanned (:offers-scanned g)
                      :cohorts (count cohorts)
                      :ledger {:lines (:lines ledger) :proposals (count (:entries ledger))}
                      :verdicts (prop/counts-by-verdict (:entries ledger))
                      :rules (facts/summary-line :jp)
                      :floors (floors g)}
                out (cond
                      defect (merge base {:outcome :candidate} defect)

                      (seq unreviewed)
                      (merge base {:outcome :candidate
                                   :kind :unreviewed-offer
                                   :candidate (first unreviewed)
                                   :projected (projected
                                               (first (filter #(= (:offer-id (first unreviewed))
                                                                  (:offer/id %)) offers))
                                               cohorts (or operator {}))
                                   :all-unreviewed (mapv :offer-id unreviewed)})

                      (seq stale)
                      (merge base {:outcome :candidate
                                   :kind :stale-proposal
                                   :candidate (first stale)
                                   :all-stale (mapv :offer-id stale)})

                      :else (merge base {:outcome :no-candidates}))]
            (println (pr-str out))
            (js/process.exit 0)))))))

(-main)
