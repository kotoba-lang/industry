#!/usr/bin/env nbb
;; atproto-engagement-collect.cljs — この艦隊が atproto に書いた record への
;; **反応**（like / repost / reply）を測り、datom 面に落とす。
;;
;; 入力は `manifest/atproto-surfaces.edn`（どの面に書いているか）。
;; 出力は `90-docs/observation/atproto-engagement.datoms.edn`
;; （`:source/dataset "atproto-engagement"`、`manifest/edn-query.cljs` から引ける）。
;;
;; ## この collector が答えを分ける 3 つの状態
;;
;; 素朴に書くと、次の 3 つが**全部 0 として同じ顔で出てくる**。それが
;; ADR-2608136000 が名指しした型で、この workspace が 1 日で 14 箇所見つけたもの。
;;
;;   A  面に到達できなかった            → 測れていない。**exit 2**（0 でも 1 でもない）
;;   B  到達したが、こちらの record が 0 → 観測対象が無い。**exit 3**
;;   C  record を見て、反応が 0 だった   → これが本物の「反応ゼロ」。**exit 0**
;;
;; **C だけが「反応が無い」という主張である。** A と B は主張ですらない。
;; 2026-08-19 現在この collector が返すのは **B** で、それは正しい —— 艦隊は
;; まだ 1 件も公開していない。B を C として報告する collector は、公開が
;; 止まっていることを「不人気」として報告し続ける。
;;
;; ## evidence floor
;;
;; 毎回 `SCANNED<TAB><repos>/<records>/<engagement>` を stdout に出す。
;; 0 件を clean として通さないための床（fleet-ci 全体の規律）。
;;
;; ## :self-hosted の構造的な穴（隠さずに記録する）
;;
;; appview を持たない単独 PDS では、反応は**同じ PDS の中にある分しか見えない**。
;; 外部から来た like は構造的に観測不能で、それは「反応が無い」ことではない。
;; datom には `:engagement/scope :within-pds` を付けて、後から読む者が
;; 全数だと誤読しないようにする。
;;
;; usage:
;;   nbb scripts/atproto-engagement-collect.cljs [--out <path>] [--dry-run]

(require '[clojure.string :as str]
         '[clojure.edn :as edn])

(def fs (js/require "node:fs"))
(def path (js/require "node:path"))

(def argv (vec (drop 2 (.-argv js/process))))
(defn flag [n] (some #(= n %) argv))
(defn opt [n d] (let [i (.indexOf argv n)] (if (neg? i) d (get argv (inc i) d))))

(def root (or (.-CLOUD_ITONAMI_WORKSPACE_ROOT (.-env js/process)) (.cwd js/process)))
(def out-path (opt "--out" (path.join root "90-docs/observation/atproto-engagement.datoms.edn")))
(def max-repos (let [v (opt "--max-repos" nil)] (when v (js/parseInt v 10))))
;; A bounded run is a SAMPLE. It must never enter the durable plane looking like
;; a complete observation, so --max-repos forces dry-run. Stated, not silent.
(def dry? (or (flag "--dry-run") (some? max-repos)))

(defn die [code & msg]
  (binding [*print-fn* *print-err-fn*] (apply println msg))
  (.exit js/process code))

;; ---------------------------------------------------------------------------
;; HTTP — keep the error BODY, not just the status.
;;
;; A collector that records only the status cannot explain itself later: the
;; reason is usually in the body (ADR-2608136000, the HTTP-400-twenty-times
;; case). So every failure carries :status AND :body.
;; ---------------------------------------------------------------------------

(defn fetch-json [url]
  (-> (js/fetch url #js {:headers #js {"User-Agent" "itonami-engagement-collect/1"}})
      (.then (fn [r]
               (-> (.text r)
                   (.then (fn [t]
                            (if (.-ok r)
                              (try {:ok (js->clj (js/JSON.parse t) :keywordize-keys true)}
                                   (catch :default _ {:err {:status (.-status r) :body (subs t 0 400)
                                                            :why "response was not JSON"}}))
                              {:err {:status (.-status r) :body (subs t 0 400)}}))))))
      (.catch (fn [e] (js/Promise.resolve {:err {:status 0 :body (str e) :why "unreachable"}})))))

;; ---------------------------------------------------------------------------
;; :self-hosted — one PDS, no appview
;; ---------------------------------------------------------------------------

(defn list-repos [pds]
  ;; limit=100 returned 403 on aozora; 20 works. Measured 2026-08-19.
  (letfn [(page [cursor acc]
            (let [u (str pds "/xrpc/com.atproto.sync.listRepos?limit=20"
                         (when cursor (str "&cursor=" cursor)))]
              (-> (fetch-json u)
                  (.then (fn [{:keys [ok err]}]
                           (cond
                             err {:err err}
                             :else (let [rs (:repos ok) acc (into acc rs) c (:cursor ok)]
                                     (if (and c (seq rs)
                                              (< (count acc) (or max-repos 2000)))
                                       (page c acc)
                                       {:ok acc :truncated? (boolean (and c (seq rs)))}))))))))]
    (page nil [])))

(defn list-records [pds did collection]
  (let [u (str pds "/xrpc/com.atproto.repo.listRecords?repo=" (js/encodeURIComponent did)
               "&collection=" collection "&limit=100")]
    (-> (fetch-json u)
        (.then (fn [{:keys [ok err]}] (if err {:err err} {:ok (or (:records ok) [])}))))))

(defn collect-self-hosted [surface]
  (let [pds (:pds surface)
        colls (:engagement-collections surface)]
    (-> (list-repos pds)
        (.then
         (fn [{:keys [ok err truncated?]}]
           (if err
             (js/Promise.resolve {:unreachable {:surface (:id surface) :at "listRepos" :err err}})
             ;; listRepos reports deactivated/taken-down repos too, and
             ;; listRecords answers those with HTTP 400 "Could not find repo".
             ;; Filter on the flag listRepos already gives us rather than
             ;; swallowing the 400 -- and say how many were dropped, because a
             ;; bound nobody is told about reads as full coverage.
             (let [all (vec (take (or max-repos 2000) ok))
                   live (filterv #(not (false? (:active %))) all)
                   dids (mapv :did live)
                   inactive (- (count all) (count live))
                   truncated? truncated?]
               (-> (js/Promise.all
                    (clj->js
                     (for [did dids c colls]
                       (-> (list-records pds did c)
                           (.then (fn [r] (assoc r :did did :collection c)))))))
                   (.then
                    (fn [results]
                      (let [rs (js->clj results)
                            failed (filterv :err rs)
                            good (filterv :ok rs)
                            records (vec (mapcat (fn [g]
                                                   (map #(assoc % :_did (:did g) :_collection (:collection g))
                                                        (:ok g)))
                                                 good))
                            ;; engagement = a record whose subject points at one of OUR uris
                            ours (into #{} (map :uri) records)
                            engagement (filterv (fn [r]
                                                  (let [s (get-in r [:value :subject])
                                                        u (if (map? s) (:uri s) s)]
                                                    (and u (contains? ours u))))
                                                records)]
                        {:surface (:id surface)
                         :truncated? truncated?
                         :inactive inactive
                         :repos (count dids)
                         :records (count records)
                         :engagement (count engagement)
                         ;; A partial read is NOT a clean read.
                         :unread (count failed)
                         :unread-detail
                         ;; Keep the BODY. An earlier version of this line did
                         ;; (select-keys .. [:status :why]) and threw the body
                         ;; away -- the exact defect this file's own docstring
                         ;; names. Caught 2026-08-19 by the first run against a
                         ;; surface that actually had records: three HTTP 400s
                         ;; whose reason was unreadable because of this line.
                         (mapv (fn [f]
                                 (let [e (:err f)]
                                   {:collection (:collection f)
                                    :status (:status e)
                                    :why (:why e)
                                    :body (some-> (:body e) (subs 0 (min 200 (count (:body e)))))}))
                               (take 3 failed))
                         :items engagement}))))))))))) 

;; ---------------------------------------------------------------------------
;; :appview — aggregated counts
;; ---------------------------------------------------------------------------

(defn collect-appview [surface]
  (let [owns (vec (:owns surface))]
    (if (empty? owns)
      ;; NOT an error, and NOT zero engagement: nothing is claimed on this surface.
      (js/Promise.resolve {:surface (:id surface) :repos 0 :records 0 :engagement 0 :unread 0 :items []})
      (js/Promise.resolve {:surface (:id surface) :repos (count owns) :records 0 :engagement 0
                           :unread (count owns)
                           :unread-detail [{:why "appview enumeration not implemented; add it when the fleet actually publishes here"}]
                           :items []}))))

;; ---------------------------------------------------------------------------

(defn datoms [now results]
  (vec
   (concat
    (for [r results]
      {:db/id (str "surface-" (name (:surface r)) "-" now)
       "source/dataset" "atproto-engagement"
       "engagement/surface" (name (:surface r))
       "engagement/observed-at" now
       "engagement/scope" "within-pds"
       "engagement/repos" (:repos r)
       "engagement/records" (:records r)
       "engagement/reactions" (:engagement r)
       "engagement/unread" (:unread r)})
    (for [r results i (:items r)]
      {:db/id (str "reaction-" (:uri i) "-" now)
       "source/dataset" "atproto-engagement"
       "engagement/surface" (name (:surface r))
       "engagement/observed-at" now
       "reaction/uri" (:uri i)
       "reaction/collection" (:_collection i)
       "reaction/actor" (:_did i)
       "reaction/subject" (let [s (get-in i [:value :subject])]
                            (if (map? s) (:uri s) (str s)))}))))

(defn -main []
  (let [cfg-path (path.join root "manifest/atproto-surfaces.edn")]
    (when-not (fs.existsSync cfg-path)
      (die 2 "CANNOT ANSWER — manifest/atproto-surfaces.edn is missing. Not reporting zero."))
    (let [cfg (edn/read-string (str (fs.readFileSync cfg-path "utf8")))
          colls (:engagement-collections cfg)
          surfaces (mapv #(assoc % :engagement-collections colls) (:surfaces cfg))]
      (when (empty? surfaces)
        (die 2 "CANNOT ANSWER — the registry declares no surfaces."))
      (-> (js/Promise.all
           (clj->js (for [s surfaces]
                      (case (:read-method s)
                        :self-hosted (collect-self-hosted s)
                        :appview (collect-appview s)
                        (js/Promise.resolve {:unreachable {:surface (:id s) :at "dispatch"
                                                           :err {:why (str "unknown :read-method " (:read-method s))}}})))))
          (.then
           (fn [raw]
             (let [rs (js->clj raw :keywordize-keys true)
                   unreachable (filterv :unreachable rs)
                   ok (filterv (complement :unreachable) rs)
                   repos (reduce + 0 (map :repos ok))
                   records (reduce + 0 (map :records ok))
                   reactions (reduce + 0 (map :engagement ok))
                   unread (reduce + 0 (map :unread ok))
                   now (.toISOString (js/Date.))]

               ;; evidence floor — always, before any verdict
               (println (str "SCANNED\t" repos "/" records "/" reactions))
               (when (some :truncated? ok)
                 (println (str "  BOUNDED — --max-repos " max-repos
                               " stopped enumeration early; this is a SAMPLE and no datoms are written.")))
               (doseq [r ok]
                 (println (str "  " (name (:surface r))
                               "  repos=" (:repos r) " records=" (:records r)
                               " reactions=" (:engagement r) " unread=" (:unread r)
                               (when (pos? (or (:inactive r) 0))
                                 (str " skipped-inactive=" (:inactive r)))))
                 (doseq [d (:unread-detail r)] (println (str "      unread: " (pr-str d)))))
               (doseq [u unreachable] (println (str "  UNREACHABLE " (pr-str (:unreachable u)))))

               ;; APPEND the entities, never overwrite the series. "Did
               ;; reactions grow after we changed something" is the whole
               ;; question, and a snapshot cannot answer it (CLAUDE.md --
               ;; 測定・イベント列は append-only).
               ;;
               ;; But the file stays ONE EDN vector rather than one form per
               ;; line. `edn-query.cljs` reads these with `slurp-edn`, which
               ;; takes a single form: line-delimited EDN would be silently
               ;; truncated to the first line and reported as a complete load.
               ;; The two existing line-delimited ledgers each have a dedicated
               ;; loader for exactly this reason; this file earns none by
               ;; staying readable as it is.
               ;;
               ;; Single writer assumed (one cadence, one host). Two concurrent
               ;; runs would lose one of them -- if this ever runs in parallel,
               ;; it needs a real append format and a loader to match.
               (when-not dry?
                 (let [dir (path.dirname out-path)
                       prior (if (fs.existsSync out-path)
                               (or (try (edn/read-string (str (fs.readFileSync out-path "utf8")))
                                        (catch :default _
                                          (die 2 "CANNOT ANSWER — the existing series is unreadable; refusing to overwrite it.")))
                                   [])
                               [])]
                   (when-not (fs.existsSync dir) (fs.mkdirSync dir #js {:recursive true}))
                   (fs.writeFileSync out-path
                                     (str (pr-str (into (vec prior) (datoms now ok))) "\n"))))

               (cond
                 ;; A — could not measure. Never the same value as a clean run.
                 (seq unreachable)
                 (die 2 (str "CANNOT ANSWER — " (count unreachable)
                             " surface(s) unreachable. Refusing to report zero reactions."))

                 (pos? unread)
                 (die 2 (str "CANNOT ANSWER — " unread
                             " read(s) failed. A partial read is not a clean read."))

                 ;; B — reachable, but this fleet has published nothing.
                 (zero? records)
                 (die 3 (str "NOTHING TO OBSERVE — " repos
                             " repo(s) reachable, 0 records published by this fleet."
                             " This is not 'no engagement'; it is 'nothing was posted'."))

                 ;; C — the only state that is a claim about engagement.
                 :else
                 (println (str "OK — " records " record(s), " reactions
                               " reaction(s) within-pds"
                               (if dry?
                                 " (dry-run: nothing written)"
                                 (str ", written to " out-path)))))))))))) 

(-main)
