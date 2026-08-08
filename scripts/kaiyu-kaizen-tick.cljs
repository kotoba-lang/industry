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
;; ## 不変条件
;;
;;   - **secret が無いサイトは skip、`:no-secret` として記録する。** 無認証で叩かない
;;     （gated endpoint への無認証は 403 で、「データが無い」と見分けが付かなくなる）
;;   - **HTTP が 200 でないサイトは skip、`:unreachable`。** 0 件と区別する
;;   - **計測が疑わしいサイトは、そのサイトの site 所見を出さない**（diagnose の
;;     short-circuit）。tick はそれをそのまま通す
;;   - 捏造ゼロ。読めなかったものを 0 と書かない
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

(def sites
  "The four sites with a 回遊 read face. itonami.cloud joined 2026-08-08 —
  until then the loop could read every site except the one it files into,
  which is the shape of a tool that stops being true first."
  [{:site "shinshi.club"
    :tenant {:org "network-awai" :repo "club-shinshi"}
    :url (str "https://shinshi.club/_metrics/audience?window_days=" window-days)
    :keychain "club-shinshi BMC_COLLECTOR_READONLY_SECRET"
    :header "x-internal-trust"
    :live-since "2026-06-11"
    :shape :shinshi}
   {:site "babiniku.net"
    :tenant {:org "network-awai" :repo "net-babiniku"}
    :url (str "https://babiniku.net/api/telemetry-report?window_days=" window-days)
    :keychain "net-babiniku TELEMETRY_REPORT_SECRET"
    :header "authorization-bearer"
    :live-since "2026-08-06"
    :shape :babiniku}
   {:site "kotobase.net"
    :tenant {:org "network-awai" :repo "net-kotobase"}
    :url (str "https://kotobase.net/api/kaiyu?window_days=" window-days)
    :keychain "net-kotobase KAIYU_REPORT_SECRET"
    :header "authorization-bearer"
    :live-since "2026-08-07"
    :shape :kotobase}
   {:site "itonami.cloud"
    :tenant {:org "network-awai" :repo "cloud-itonami"}
    :url (str "https://itonami.cloud/api/kaiyu?window_days=" window-days)
    :keychain "cloud-itonami KAIYU_REPORT_SECRET"
    :header "authorization-bearer"
    ;; 2026-08-08, the day its own measurement landed. Earlier windows report
    ;; :not-measured rather than :blocked, which is the honest reading.
    :live-since "2026-08-08"
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
    (let [{:keys [ok error]} (fetch-json (:url site) (:header site) secret)]
      (if error
        {:site (:site site) :status error}
        (let [sections (normalize site ok win)
              diagnosis (dx/diagnose {:site (:site site)
                                      :window win
                                      :vocabulary (vocabulary-of sections)
                                      :site-live-since (:live-since site)
                                      :sections sections})
              top (dx/top-finding diagnosis)]
          (cond-> {:site (:site site)
                   :tenant (:tenant site)
                   :status (if (:blocked? diagnosis) :measurement-blocked :ok)
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
      (doseq [{:keys [site status finding-count top]} results]
        (println (str "  " site "  " (name status)
                      (when finding-count (str "  findings=" finding-count))
                      (when top (str "\n      → [" (name (:severity top)) "] " (:title top))))))
      (println (str "candidates: " (count candidates)))))
  (js/process.exit 0))
