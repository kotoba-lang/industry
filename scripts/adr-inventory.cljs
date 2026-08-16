#!/usr/bin/env nbb
;; adr-inventory — 90-docs/adr の **棚卸し候補**を決定論で出す。
;; ここにモデルは居ない。直す判断は skill `adr-inventory`。
;;
;; 正本: ADR-2608161700。姉妹は diagnose-unreadable-edn（読めない文書）で、
;; あちらは parse。こちらは「読めるのに、いまの正本として嘘をついている」。
;;
;; 検出する kind（安全な順。tick もこの順で候補を出す）:
;;
;;   :successor-unmarked     後続 ADR がこの文書を supersedes しているのに、
;;                           本人の status が superseded でない
;;   :superseded-without-pointer  status は superseded なのに pointer が空で、
;;                           後続がグラフから一意に決まる
;;   :superseded-ambiguous   同上だが後続が 2 件以上（推測して埋めてはいけない）
;;   :orphan-successor       superseded-by が指す先を解決できない
;;   :unresolved-supersede-ref  後続の :adr/supersedes が stamp 衝突で解決不能
;;   :id-collision           **同じ :adr/id 文字列**を 2 ファイルが名乗っている
;;   :status-body-mismatch   属性と本文 **Status** が見出しとして食い違う
;;   :ledger-ops-instruction 本文が adr-ledger append を現行手順として残している
;;   :cited-superseded       CLAUDE.md / AGENTS.md が superseded な stamp を
;;                           引用している（snippet 付き。現行方針か経緯かはモデル）
;;
;; 検出しないもの（意図的）:
;;   - reader を通らないファイル（docs-edn-repair の仕事）
;;   - 決定の中身が「今も正しいか」（それはモデルで、しかも 1 件ずつ）
;;   - accepted の :adr/superseded-by ""（空文字プレースホルダ。スキーマ癖）
;;   - 同じ 10 分窓の stamp を複数 ADR が使うこと（番号体系の性質。衝突は
;;     参照解決を stamp に落とさないことで扱う。ADR-2607257000 の教訓）
;;   - :adr/status が keyword なこと（query 面の schema 癖。棚卸しではない。
;;     `--hygiene` で出す）
;;
;; usage:
;;   nbb scripts/adr-inventory.cljs
;;   nbb scripts/adr-inventory.cljs --edn          ; slim（件数 + next 1 件）
;;   nbb scripts/adr-inventory.cljs --hygiene      ; keyword-status も出す
;;   nbb scripts/adr-inventory.cljs --min-listed 1500

(require '[clojure.string :as str])

(def fs (js/require "node:fs"))
(def path (js/require "node:path"))
(def os (js/require "node:os"))
(def cp (js/require "node:child_process"))

(def root (or (aget (.-env js/process) "COM_JUNKAWASAKI_ROOT") (.cwd js/process)))
(def edn-out? (boolean (some #{"--edn"} *command-line-args*)))
(def hygiene? (boolean (some #{"--hygiene"} *command-line-args*)))

(defn- arg [flag default]
  (let [a (vec *command-line-args*)
        i (.indexOf a flag)]
    (if (neg? i) default (nth a (inc i)))))

(defn- sh [cmd args]
  (try
    (let [r (.spawnSync cp cmd (clj->js args)
                        #js {:encoding "utf8" :cwd root
                             :maxBuffer (* 64 1024 1024)})]
      {:code (aget r "status") :out (str (aget r "stdout")) :err (str (aget r "stderr"))})
    (catch :default e {:code nil :out "" :err (str e)})))

(defn- listed-adr-files
  "git ls-files。find でも ls でもない —— cone-mode sparse は disk に無いファイル
  を『無い』と答える（ADR-2608137400）。index が正。"
  []
  (let [{:keys [code out err]}
        (sh "git" ["ls-files" "--cached" "--others" "--exclude-standard"
                   "--" "90-docs/adr"])]
    (if (not= 0 code)
      {:ok? false :err (str/trim err) :paths []}
      {:ok? true
       :paths (->> (str/split-lines out)
                   (map str/trim)
                   (remove str/blank?)
                   (filter #(str/ends-with? % ".edn"))
                   (remove #(str/includes? % ".datoms.edn"))
                   vec)})))

(defn- on-disk? [rel]
  (try (.existsSync fs (.join path root rel))
       (catch :default _ false)))

(defn- stamp [s]
  (when s
    (let [s (str s)]
      (when-let [m (re-find #"(?:adr-|ADR-)?(\d{10})" s)]
        (second m)))))

(defn- as-refs [v]
  (cond
    (nil? v) []
    (false? v) []
    (= v "") []
    (and (string? v) (str/blank? v)) []
    (string? v) [v]
    (keyword? v) [(name v)]
    (sequential? v) (vec (mapcat as-refs v))
    :else [(str v)]))

(defn- slug [s]
  (when s
    (-> (str s)
        (str/replace #"^.*90-docs/adr/" "")
        (str/replace #"\.edn$" "")
        (str/replace #"^adr-" "")
        str/lower-case)))

(defn- status-name [s]
  (when (some? s)
    (let [raw (str/lower-case (if (keyword? s) (name s) (str s)))]
      (cond
        (str/includes? raw "superseded") "superseded"
        (str/includes? raw "retracted") "superseded"
        (str/includes? raw "rejected") "rejected"
        (str/includes? raw "deprecated") "deprecated"
        (str/includes? raw "proposed") "proposed"
        (str/includes? raw "draft") "draft"
        (str/includes? raw "accepted") "accepted"
        (str/includes? raw "implemented") "accepted"
        (str/includes? raw "active") "accepted"
        :else raw))))

(defn- in-force? [st]
  (contains? #{"accepted" "proposed" "draft"} st))

(defn- retired? [st]
  (contains? #{"superseded" "rejected" "deprecated"} st))

(defn- body-status [body]
  (when (string? body)
    (or (some-> (re-find #"(?m)^\*\*Status\*\*\s*:\s*([^\n]+)" body) second status-name)
        (some-> (re-find #"(?m)^Status\s*:\s*([^\n]+)" body) second status-name)
        (some-> (re-find #"(?m)^## Status\n+([^\n]+)" body) second status-name))))

(defn- load-entity [rel]
  (let [p (.join path root rel)]
    (try
      (let [tx (cljs.reader/read-string (.readFileSync fs p "utf8"))
            e (cond
                (and (vector? tx) (map? (first tx))) (first tx)
                (map? tx) tx
                :else nil)]
        (if (and (map? e) (:adr/id e))
          {:ok? true :entity e}
          {:ok? true :skip? true}))
      (catch :default e
        {:ok? false :error (str e)}))))

(defn- record [rel entity]
  (let [id (str (:adr/id entity))
        st-raw (:adr/status entity)
        st (status-name st-raw)
        suc-by (as-refs (or (:adr/superseded-by entity)
                            (:adr/superseded_by entity)))
        suc (as-refs (or (:adr/supersedes entity)
                         (:adr/supersedes_by entity)))]
    {:path rel
     :adr-id id
     :stamp (or (stamp id) (stamp rel))
     :status st
     :status-raw st-raw
     :keyword-status? (keyword? st-raw)
     :supersede-refs suc
     :superseded-by-refs suc-by
     :body (:adr/body entity)}))

(defn- ledger-ops? [body]
  (and (string? body)
       (let [hits ["scripts/adr-ledger-append"
                   "90-docs/adr-ledger/adr-ledger.edn"
                   "adr-ledger 経由"
                   "以後の更新は adr-ledger"
                   "ledger に append"]]
         (boolean
          (some (fn [h]
                  (when-let [i (str/index-of body h)]
                    (let [window (subs body i (min (count body) (+ i 120)))]
                      (not (re-find #"削除|撤去|retire|廃止済み" window)))))
                hits)))))

(defn- cite-stamps [text]
  (when (string? text)
    (vec (distinct (map second (re-seq #"ADR-(\d{10})" text))))))

(defn- snippet [text stamp]
  (when (and (string? text) stamp)
    (let [needle (str "ADR-" stamp)
          i (str/index-of text needle)]
      (when i
        (-> (subs text (max 0 (- i 80)) (min (count text) (+ i 80)))
            (str/replace #"\s+" " ")
            str/trim)))))

(def kind-order
  [:successor-unmarked
   :superseded-without-pointer
   :superseded-ambiguous
   :orphan-successor
   :unresolved-supersede-ref
   :id-collision
   :status-body-mismatch
   :ledger-ops-instruction
   :cited-superseded
   :keyword-status])

(defn- resolve-ref
  "参照を 1 文書に落とす。順序は exact id → path/slug → **一意な** stamp。
   stamp が衝突していたら推測しない（ADR-2607257000: 曖昧なスタンプは未解決）。"
  [ref {:keys [by-id by-slug by-stamp]}]
  (let [s (str ref)
        id-key (str/lower-case s)
        sl (slug s)
        st (stamp s)]
    (cond
      (get by-id id-key) {:ok (get by-id id-key)}
      (and sl (get by-slug sl)) {:ok (get by-slug sl)}
      (and st (= 1 (count (get by-stamp st)))) {:ok (first (get by-stamp st))}
      (and st (> (count (get by-stamp st)) 1))
      {:ambiguous st :candidates (mapv :path (get by-stamp st))}
      st {:missing st}
      :else {:unparsed s})))

(defn classify
  "records: seq of {:path :adr-id :stamp :status ...}.
   cites: {:claude <text> :agents <text>}"
  [records cites]
  (let [by-stamp (group-by :stamp (filter :stamp records))
        by-id (into {} (keep (fn [r]
                               (when (:adr-id r)
                                 [(str/lower-case (:adr-id r)) r]))
                             records))
        by-slug (into {} (keep (fn [r]
                                 (when-let [s (slug (:path r))]
                                   [s r]))
                               records))
        idx {:by-id by-id :by-slug by-slug :by-stamp by-stamp}
        successors-of (volatile! {})
        findings (volatile! [])
        emit! (fn [f] (vswap! findings conj f))]

    (doseq [r records]
      (doseq [ref (:supersede-refs r)]
        (let [res (resolve-ref ref idx)]
          (cond
            (:ok res)
            (vswap! successors-of update (:path (:ok res)) (fnil conj []) r)
            (:ambiguous res)
            (emit! {:kind :unresolved-supersede-ref
                    :path (:path r)
                    :adr-id (:adr-id r)
                    :stamp (:stamp r)
                    :status (:status r)
                    :ref ref
                    :ambiguous-stamp (:ambiguous res)
                    :candidates (:candidates res)
                    :note "supersedes の stamp が衝突している。推測して結び付けない"})
            (:missing res)
            (emit! {:kind :unresolved-supersede-ref
                    :path (:path r)
                    :adr-id (:adr-id r)
                    :stamp (:stamp r)
                    :status (:status r)
                    :ref ref
                    :missing-stamp (:missing res)
                    :note (str "supersedes が " (:missing res) " を指すが、その ADR が無い")})))))

    (let [succ-map @successors-of]
      (doseq [[id rs] (group-by #(str/lower-case (str (:adr-id %))) records)]
        (when (and id (not (str/blank? id)) (> (count rs) 1))
          (emit! {:kind :id-collision
                  :path (:path (first rs))
                  :paths (mapv :path rs)
                  :ids (mapv :adr-id rs)
                  :note (str "同じ :adr/id を " (count rs) " ファイルが名乗っている")})))

      (doseq [r records]
        (let [succs (get succ-map (:path r) [])]
          (when (and (in-force? (:status r)) (seq succs))
            (emit! {:kind :successor-unmarked
                    :path (:path r)
                    :adr-id (:adr-id r)
                    :stamp (:stamp r)
                    :status (:status r)
                    :successors (mapv #(select-keys % [:path :adr-id :stamp :status]) succs)
                    :note (if (> (count succs) 1)
                            "後続が複数。全置換か部分無効か、どれが最終の後継かはモデルが本文を読む"
                            "後続が supersedes に書いている。全置換か部分無効かはモデルが本文を読む")}))

          (when (and (retired? (:status r)) (empty? (:superseded-by-refs r)))
            (let [n (count succs)]
              (cond
                (= n 1)
                (emit! {:kind :superseded-without-pointer
                        :path (:path r)
                        :adr-id (:adr-id r)
                        :stamp (:stamp r)
                        :status (:status r)
                        :successors (mapv #(select-keys % [:path :adr-id :stamp :status]) succs)
                        :note "pointer が空で、後続はグラフから 1 件に決まる"})
                (> n 1)
                (emit! {:kind :superseded-ambiguous
                        :path (:path r)
                        :adr-id (:adr-id r)
                        :stamp (:stamp r)
                        :status (:status r)
                        :successors (mapv #(select-keys % [:path :adr-id :stamp :status]) succs)
                        :note "後続が複数。推測して埋めてはいけない"}))))

          (doseq [ref (:superseded-by-refs r)]
            (let [res (resolve-ref ref idx)]
              (when-not (:ok res)
                (emit! {:kind :orphan-successor
                        :path (:path r)
                        :adr-id (:adr-id r)
                        :stamp (:stamp r)
                        :status (:status r)
                        :ref ref
                        :resolve res
                        :note "superseded-by が解決できない"}))))

          (when (and hygiene? (:keyword-status? r))
            (emit! {:kind :keyword-status
                    :path (:path r)
                    :adr-id (:adr-id r)
                    :stamp (:stamp r)
                    :status (:status r)
                    :note ":adr/status が keyword。edn-query の面は文字列"}))

          (let [bs (body-status (:body r))]
            (when (and bs (:status r) (not= bs (:status r)))
              (emit! {:kind :status-body-mismatch
                      :path (:path r)
                      :adr-id (:adr-id r)
                      :stamp (:stamp r)
                      :status (:status r)
                      :body-status bs
                      :note (str "属性 " (:status r) " / 本文 " bs)})))

          (when (and (in-force? (:status r))
                     (not= (:stamp r) "2607257000")
                     (ledger-ops? (:body r)))
            (emit! {:kind :ledger-ops-instruction
                    :path (:path r)
                    :adr-id (:adr-id r)
                    :stamp (:stamp r)
                    :status (:status r)
                    :note "本文が adr-ledger append を現行手順として残している"}))))

      (let [retired-unique
            (into {} (keep (fn [[st rs]]
                             (when (and (= 1 (count rs))
                                        (retired? (:status (first rs))))
                               [st (first rs)]))
                           by-stamp))]
        (doseq [[src text] cites
                stamp (cite-stamps text)]
          (when-let [r (get retired-unique stamp)]
            (emit! {:kind :cited-superseded
                    :path (name src)
                    :stamp stamp
                    :adr-id (:adr-id r)
                    :status (:status r)
                    :snippet (snippet text stamp)
                    :note "方針文書が superseded な stamp を引用している。現行か経緯かはモデル"})))))

    (->> @findings
         (sort-by (juxt #(.indexOf kind-order (:kind %)) :stamp :path))
         vec)))

(defn inventory
  ([] (inventory {:min-listed (js/parseInt (str (arg "--min-listed" "1500")))}))
  ([{:keys [min-listed]}]
   (let [listed (listed-adr-files)]
     (cond
       (not (:ok? listed))
       {:outcome :insufficient-scan
        :reason :git-ls-files-failed
        :err (:err listed)
        :listed 0 :readable 0 :candidates []}

       (< (count (:paths listed)) min-listed)
       {:outcome :insufficient-scan
        :reason :listed-below-floor
        :listed (count (:paths listed))
        :min-listed min-listed
        :note (str "90-docs/adr の .edn が index に " (count (:paths listed))
                   " 件しか無い。sparse / 別 worktree の部分ビューからの「候補 0」は嘘になる")
        :candidates []}

       :else
       (let [paths (:paths listed)
             missing (vec (remove on-disk? paths))
             present (vec (filter on-disk? paths))]
         (if (< (count present) (* 0.95 (count paths)))
           {:outcome :insufficient-scan
            :reason :sparse-cone
            :listed (count paths)
            :on-disk (count present)
            :missing-count (count missing)
            :note (str "index " (count paths) " 件のうち disk に " (count present)
                       " 件しか無い（skip-worktree / sparse）。部分ビューから棚卸しない")
            :candidates []}
           (let [loaded (map (fn [p] [p (load-entity p)]) present)
                 unreadable (mapv first (filter #(not (:ok? (second %))) loaded))
                 records (->> loaded
                              (keep (fn [[p res]]
                                      (when (and (:ok? res) (not (:skip? res)))
                                        (record p (:entity res)))))
                              vec)
                 claude (try (.readFileSync fs (.join path root "CLAUDE.md") "utf8")
                             (catch :default _ nil))
                 agents (try (.readFileSync fs (.join path root "AGENTS.md") "utf8")
                             (catch :default _ nil))
                 findings (classify records {:claude claude :agents agents})
                 by-kind (into {} (for [[k v] (group-by :kind findings)]
                                    [k (count v)]))]
             {:outcome (if (seq findings) :candidates :clean)
              :listed (count paths)
              :on-disk (count present)
              :readable (count records)
              :unreadable (count unreadable)
              :skipped (- (count present) (count records) (count unreadable))
              :finding-count (count findings)
              :by-kind by-kind
              :unreadable-paths unreadable
              :candidates findings})))))))

(defn slim
  "tick / ledger 用。全 candidate を stdout に載せない（spawn の parse が
   途中で切れると :insufficient-scan になり、床を割っていないのにモデルが
   起きなくなる）。next 1 件と件数だけ。successors は path/id/status だけ残す。"
  [result]
  (let [cands (:candidates result)
        next (first cands)]
    (-> result
        (dissoc :candidates :unreadable-paths)
        (assoc :next (when next
                       (-> next
                           (dissoc :body :snippet)
                           (update :successors
                                   (fn [xs]
                                     (mapv #(select-keys % [:path :adr-id :stamp :status])
                                           (or xs []))))))
               :remaining (count cands)))))

(defn -main []
  (let [result (inventory)]
    (if edn-out?
      (println (pr-str (slim result)))
      (do
        (println (str "adr-inventory: listed " (:listed result)
                      " / readable " (or (:readable result) 0)
                      " / findings " (or (:finding-count result) 0)
                      " / outcome " (name (:outcome result))))
        (when-let [n (:note result)] (println (str "  " n)))
        (when-let [bk (:by-kind result)]
          (doseq [k kind-order]
            (when-let [c (get bk k)]
              (println (str "  " (name k) "  " c)))))
        (doseq [c (take 30 (:candidates result))]
          (println (str "  " (name (:kind c)) "  " (:path c)
                        (when-let [n (:note c)] (str "  — " n)))))
        (when (> (count (:candidates result)) 30)
          (println (str "  … +" (- (count (:candidates result)) 30) " more")))))
    (js/process.exit 0)))

(-main)
