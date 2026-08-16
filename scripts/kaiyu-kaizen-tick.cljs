#!/usr/bin/env nbb
;; scripts/kaiyu-kaizen-tick.cljs — 回遊の計測を読んで「今いちばん答えるべき問い」を
;; 1 件だけ出す。**決定論。モデルを起こさない。**
;;
;; 姉妹 loop（docs-edn-repair / itonami-maturity-improve）と同じ 2 段構え:
;;
;;   1. ここが測る（各サイトの read face を叩き、kaiyu.diagnose に通す）
;;   2. 候補があるときだけ scripts/kaiyu-kaizen-loop.cljs がモデルを起こす
;;
;; 逆順にすると、毎周モデルに 3 サイト分の JSON を読ませ、周ごとに違う基準で選ぶ
;; ことになる。**探索と判定は機械、文脈の確認と提案はモデル。**
;;
;; ## サイトごとに read face の形が違う（正規化はここの仕事）
;;
;; | site | endpoint | 認証 |
;; |---|---|---|
;; | shinshi.club | `/_metrics/audience` | `x-internal-trust` |
;; | babiniku.net | `/api/telemetry-report` | `Bearer` |
;; | kotobase.net | `/api/kaiyu` | `Bearer` |
;;
;; 形を揃えるのは kaiyu.core/section の 3 値（:measured/:partial/:not-measured）に
;; 落とすところまで。**足りない情報を推測で埋めない** —— `collected-since` が無い
;; セクションは `:not-measured` として渡し、diagnose 側がそれを最優先で報告する。
;;
;; ## 収集が「始まった日」だけでなく「止まった日」も訊く（2 回目の request）
;;
;; `collected-since` は span の**手前の端**しか答えない。2 日書いて死んだ beacon は、
;; 今も書いている beacon と同じ `:partial` を返す。`kaiyu.core/recent` と
;; `kaiyu.diagnose/staleness-findings` はその差を訊くために在るが、**再問い合わせ
;; できる caller にしか答えられない —— それはここだけである。**
;;
;; だから同じ read face に `window_days=<probe-days>` でもう一度だけ訊き、直近の
;; 行数を section に添える。store は既に window で絞っているので、**新しい query を
;; 覚える必要があるホストは 1 つも無い**（4 site すべてが短い window を honour し、
;; 応答に解決後の from/to を載せることを 2026-08-16 に実測）。
;;
;; **probe が答えなかったときは nil を渡す。0 ではない。** 0 行は
;; 「収集が止まっている」の証拠そのものなので、失敗した probe が 0 として入ると
;; 証拠を捏造することになる（ADR-2608136000）。同じ理由で、**訊いた span と違う
;; span を答えた応答も失敗として扱う** —— `window_days` を無視して window 全体を
;; 返すホストがあれば、その行は「直近の行」に化け、止まった計器が健全に見える。
;;
;; ## 不変条件
;;
;;   - **secret が無いサイトは skip、`:no-secret` として記録する。** 無認証で叩かない
;;     （gated endpoint への無認証は 403 で、「データが無い」と見分けが付かなくなる）
;;   - **HTTP が 200 でないサイトは skip、`:unreachable`。** 0 件と区別する
;;   - **計測が疑わしいサイトは、そのサイトの site 所見を出さない**（diagnose の
;;     short-circuit）。tick はそれをそのまま通す
;;   - 捏造ゼロ。読めなかったものを 0 と書かない
;;   - **直近 span の probe が失敗したサイトは `:recent` を持たない**（0 行ではなく
;;     不在）。staleness の所見はそのサイトでは一切出ず、出力に probe の可否が出る
;;   - exit 0 常に（監視であって gate ではない）
;;
;; 使い方:
;;   nbb --classpath ".:scripts/nbb_compat:orgs/kotoba-lang/kaiyu/src" \
;;       scripts/kaiyu-kaizen-tick.cljs [--window-days 7] [--json]

(require '[clojure.string :as str]
         '[kaiyu.core :as kaiyu]
         '[kaiyu.diagnose :as dx])

(def cp (js/require "node:child_process"))

(def argv (vec (drop 2 (js->clj js/process.argv))))
(defn- arg [flag default]
  (let [i (.indexOf argv flag)] (if (neg? i) default (nth argv (inc i) default))))
(def window-days (js/parseInt (arg "--window-days" "7") 10))
(def json-out? (some? (some #{"--json"} argv)))

(def probe-days
  "How many trailing days the second request asks about.

  Long enough that a site with a quiet day or two is not called stopped, short
  enough that a beacon that dies is noticed while the window still holds the
  rows it wrote. `kaiyu.core/trailing-window` clamps it into the window, so a
  `--window-days` smaller than this stays coherent."
  3)

(def sites
  "The four sites with a 回遊 read face. itonami.cloud joined 2026-08-08 —
  until then the loop could read every site except the one it files into,
  which is the shape of a tool that stops being true first."
  [{:site "shinshi.club"
    :tenant {:org "network-awai" :repo "club-shinshi"}
    ;; A function of days, not a fixed string: the trailing probe asks the same
    ;; face about a shorter span, and building that from the site entry keeps
    ;; the two requests provably the same endpoint.
    :url #(str "https://shinshi.club/_metrics/audience?window_days=" %)
    :keychain "club-shinshi BMC_COLLECTOR_READONLY_SECRET"
    :header "x-internal-trust"
    :live-since "2026-06-11"
    :shape :shinshi}
   {:site "babiniku.net"
    :tenant {:org "network-awai" :repo "net-babiniku"}
    :url #(str "https://babiniku.net/api/telemetry-report?window_days=" %)
    :keychain "net-babiniku TELEMETRY_REPORT_SECRET"
    :header "authorization-bearer"
    :live-since "2026-08-06"
    :shape :babiniku}
   {:site "kotobase.net"
    :tenant {:org "network-awai" :repo "net-kotobase"}
    :url #(str "https://kotobase.net/api/kaiyu?window_days=" %)
    :keychain "net-kotobase KAIYU_REPORT_SECRET"
    :header "authorization-bearer"
    :live-since "2026-08-07"
    ;; Declared, not guessed: `kotobase.kaiyu-store` returns
    ;; `:dwell (kaiyu/section win [] nil)` as a literal constant, and its own ns
    ;; docstring says why — 「Dwell is not measured here.」 The site is generated
    ;; build-time from the kotoba-ui authoring namespaces and serves no client
    ;; script, so there is no beacon that could be broken. Without this the
    ;; diagnosis asks a three-way question whose three answers are all wrong,
    ;; nobody can close it, and the `:blocked` short-circuit hides every site
    ;; rule for this site — from the window ending 2026-08-13, the first one
    ;; whose `from` reached `:live-since`, the acquisition finding that had been
    ;; top in all five recorded rounds before it stopped being evaluated. A
    ;; declaration the read face contradicts is itself `:blocked`, so this
    ;; cannot quietly outlive what it describes.
    :uninstrumented #{:dwell}
    :shape :kotobase}
   {:site "itonami.cloud"
    :tenant {:org "network-awai" :repo "cloud-itonami"}
    :url #(str "https://itonami.cloud/api/kaiyu?window_days=" %)
    :keychain "cloud-itonami KAIYU_REPORT_SECRET"
    :header "authorization-bearer"
    ;; 2026-08-08, the day its own measurement landed. Earlier windows report
    ;; :not-measured rather than :blocked, which is the honest reading.
    :live-since "2026-08-08"
    ;; Declared for the same reason as kotobase.net above, and read off the same
    ;; evidence: `cloud-itonami.edge.self-kaiyu/report` returns
    ;; `:dwell (kaiyu/section win [] nil)` as a literal constant, and its ns
    ;; docstring names the absence — 「**Dwell is not measured**, and
    ;; `kaiyu.core/section` reports it `:not-measured` rather than as an empty
    ;; distribution」. It is a multi-page site measured server-side from the
    ;; `Referer`, deliberately with 「no client script and no cookie」, so there
    ;; is no beacon that could be broken.
    ;;
    ;; Without this the window ending 2026-08-14 — the first whose `from`
    ;; reached `:live-since` — asked a three-way question (beacon dead? read
    ;; broken? nobody came?) whose three answers are all wrong, so nobody could
    ;; close it, while the `:blocked` short-circuit hid every site rule for the
    ;; site the loop files INTO. The acquisition finding it masked was top in
    ;; all four recorded rounds before it stopped being evaluated. This is the
    ;; kotobase.net case of 2026-08-13 recurring one site later; the comment
    ;; above predicted it and the declaration was simply never carried across.
    :uninstrumented #{:dwell}
    :shape :kotobase}])

(defn- keychain
  "ONE targeted Keychain read by exact service name. Never an enumeration —
  CLAUDE.md safety floor ⑦."
  [service]
  (try
    (let [out (.execFileSync cp "security"
                             #js ["find-generic-password" "-s" service "-w"]
                             #js {:encoding "utf8" :stdio #js ["ignore" "pipe" "ignore"]})]
      (not-empty (str/trim (str out))))
    (catch :default _ nil)))

(defn- fetch-json
  "→ {:ok body} | {:error kw}. curl rather than fetch: this runs under nbb on a
  laptop where the proxy environment is the shell's, not node's."
  [url header secret]
  (try
    (let [args (into ["-sS" "--max-time" "25" "-w" "\n%{http_code}"]
                     (if (= header "authorization-bearer")
                       ["-H" (str "authorization: Bearer " secret) url]
                       ["-H" (str header ": " secret) url]))
          out (str (.execFileSync cp "curl" (clj->js args)
                                  #js {:encoding "utf8" :stdio #js ["ignore" "pipe" "ignore"]}))
          [body code] (let [i (.lastIndexOf out "\n")]
                        [(subs out 0 i) (str/trim (subs out (inc i)))])]
      (if (= "200" code)
        {:ok (js->clj (js/JSON.parse body) :keywordize-keys true)}
        {:error (keyword (str "http-" code))}))
    (catch :default _ {:error :unreachable})))

;; ── shape normalization ───────────────────────────────────────────────
;;
;; Each site answers in its own shape. What has to survive the translation is
;; the pair (rows, collected-since) per section — everything diagnose does
;; hangs off being able to tell "no rows" from "not measured".

(defn- section [win rows since] (kaiyu/section win (or rows []) since))

(defmulti normalize (fn [site _body _win] (:shape site)))

(defmethod normalize :shinshi [_ body win]
  (let [a (:audience body)]
    {:visits (section win (map (fn [r] {:source (:source r) :count (:count r)})
                               (get-in a [:visits :by-source]))
                      (get-in a [:visits :collected-since]))
     :dwell (section win (map (fn [r] {:route (:route r) :bucket (:bucket r) :count (:count r)})
                              (get-in a [:dwell :by-route-bucket]))
                     (get-in a [:dwell :collected-since]))
     :transitions (section win (map (fn [r] {:from (:from_route r) :to (:to_route r) :count (:count r)})
                                    (get-in a [:transitions :top-edges]))
                           (get-in a [:transitions :collected-since]))}))

(defmethod normalize :babiniku [_ body win]
  {:visits (section win (map (fn [r] {:source (:source r) :count (:count r)})
                             (get-in body [:visits :bySource]))
                    (get-in body [:visits :collectedSince]))
   :dwell (section win (map (fn [r] {:route (:route r) :bucket (:bucket r) :count (:count r)})
                            (get-in body [:dwell :byRouteBucket]))
                   (get-in body [:dwell :collectedSince]))
   :transitions (section win (map (fn [r] {:from (:from_route r) :to (:to_route r) :count (:count r)})
                                  (get-in body [:transitions :topEdges]))
                         (get-in body [:transitions :collectedSince]))})

(defmethod normalize :kotobase [_ body win]
  {:visits (section win (map (fn [r] {:source (:key r) :count (:count r)})
                             (get-in body [:visits :rows]))
                    (get-in body [:visits :collected-since]))
   ;; kotobase has no client beacon; its dwell section reports :not-measured by
   ;; construction (cloud-itonami/kotobase.kaiyu-store's own docstring), and
   ;; that is exactly what should reach diagnose.
   :dwell (section win (get-in body [:dwell :rows]) (get-in body [:dwell :collected-since]))
   :transitions (section win (map (fn [r] {:from (:from r) :to (:to r) :count (:count r)})
                                  (get-in body [:transitions :rows]))
                         (get-in body [:transitions :collected-since]))})

(defmulti window-of
  "The span the response SAYS it is about.

  Checked against the span that was asked for. A host that ignored
  `window_days` and answered about the whole window would otherwise hand back
  rows that get counted as recent, and a collector that stopped days ago would
  read as fresh — a check that could not run returning the value of a check
  that ran and found nothing (ADR-2608136000)."
  (fn [site _body] (:shape site)))

(defmethod window-of :shinshi [_ body] (select-keys (:audience body) [:from :to]))
(defmethod window-of :babiniku [_ body] (select-keys body [:from :to]))
(defmethod window-of :kotobase [_ body] (select-keys (:window body) [:from :to]))

(defn- trailing-probe
  "Ask the same read face about the window's own trailing span.

  → `{:trailing win :counts {section row-count}}`, or **nil** when the probe
  did not answer about that span — the request failed, or the response
  described a different one. nil rather than zeros, because zero rows is the
  entire evidence for 「収集が止まっている」 and a failed probe entering as zero
  would manufacture it. A section without `:recent` produces no staleness
  finding at all, which is the honest reading of 「訊けなかった」."
  [site win secret]
  (let [trailing (kaiyu/trailing-window win probe-days)
        {:keys [ok]} (fetch-json ((:url site) (:days trailing)) (:header site) secret)
        answered (when ok (window-of site ok))]
    (when (and (some? ok)
               (= (:from answered) (:from trailing))
               (= (:to answered) (:to trailing)))
      {:trailing trailing
       :counts (reduce-kv (fn [m k v] (assoc m k (count (:rows v))))
                          {} (normalize site ok trailing))})))

(defn- with-recent
  "Attach each section's trailing-span row count.

  Rebuilt through the public 4-arity rather than assoc'd in, so `:state` stays
  whatever `kaiyu.core` computes it to be. A nil probe leaves every section
  exactly as it was before this existed."
  [win sections {:keys [trailing counts]}]
  (if (nil? counts)
    sections
    (reduce-kv (fn [m k sec]
                 (assoc m k (if-let [n (get counts k)]
                              (kaiyu/section win (:rows sec) (:collected-since sec)
                                             (kaiyu/recent trailing n))
                              sec)))
               {} sections)))

(defn- vocabulary-of
  "Routes seen in this window. The site's full vocabulary lives in the site's
  own repo; sending a guessed one here would produce `unreached` findings for
  pages that do not exist."
  [sections]
  (into #{} (concat (map :to (get-in sections [:transitions :rows]))
                    (map :from (get-in sections [:transitions :rows]))
                    (map :route (get-in sections [:dwell :rows])))))

(defn- tick-site [site win]
  (if-let [secret (keychain (:keychain site))]
    (let [{:keys [ok error]} (fetch-json ((:url site) window-days) (:header site) secret)]
      (if error
        {:site (:site site) :status error}
        (let [probe (trailing-probe site win secret)
              sections (with-recent win (normalize site ok win) probe)
              diagnosis (dx/diagnose {:site (:site site)
                                      :window win
                                      :vocabulary (vocabulary-of sections)
                                      :site-live-since (:live-since site)
                                      :uninstrumented (:uninstrumented site)
                                      :sections sections})
              top (dx/top-finding diagnosis)]
          ;; `:uninstrumented` rides along even when empty. A section this loop
          ;; has agreed not to ask about must be visible in the same breath as
          ;; the finding count, or a reader counts 1 finding and reads silence
          ;; on the rest as health.
          ;; The probe rides along answered or not. A site whose trailing span
          ;; was never read produces no staleness finding, and that silence has
          ;; to be visible next to the finding count or it reads as health.
          (cond-> {:site (:site site)
                   :tenant (:tenant site)
                   :status (if (:blocked? diagnosis) :measurement-blocked :ok)
                   :uninstrumented (vec (sort (map name (:uninstrumented diagnosis))))
                   :recent-probe (if probe
                                   (let [{:keys [days from to]} (:trailing probe)]
                                     (str days "d " from "〜" to))
                                   :no-answer)
                   :finding-count (count (:findings diagnosis))}
            top (assoc :top (select-keys top [:id :severity :title :question :evidence])
                       :issue (dx/->issue diagnosis top))))))
    {:site (:site site) :status :no-secret}))

(let [win (kaiyu/window (subs (.toISOString (js/Date.)) 0 10) {:days window-days})
      results (mapv #(tick-site % win) sites)
      candidates (filterv :issue results)]
  (if json-out?
    (println (js/JSON.stringify (clj->js {:window win :results results :candidates candidates}) nil 2))
    (do
      (println (str "kaiyu-kaizen-tick: window " (:from win) "〜" (:to win)))
      (doseq [{:keys [site status finding-count top uninstrumented recent-probe]} results]
        (println (str "  " site "  " (name status)
                      (when finding-count (str "  findings=" finding-count))
                      (when (seq uninstrumented)
                        (str "  未計測(宣言)=" (str/join "," uninstrumented)))
                      (when recent-probe
                        (str "  直近probe=" (if (keyword? recent-probe)
                                              "答えなし"
                                              recent-probe)))
                      (when top (str "\n      → [" (name (:severity top)) "] " (:title top))))))
      (println (str "candidates: " (count candidates)))))
  (js/process.exit 0))
