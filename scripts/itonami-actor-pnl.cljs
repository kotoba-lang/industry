(ns itonami-actor-pnl
  "The P&L input for cloud-itonami actors, and the three ways it is not
  computable today.

  ADR-2608291009 (BOT / KUMO / YATA) defines what `a bot that works and earns`
  means, per window:

      revenue = what the outside actually settled for that actor's output
      cost    = burn_KUMO x posted KUMO price + burn_YATA x posted YATA price
      margin  = revenue - cost

  and names step 4 of its implementation order: run that over the governed
  actors -- `not 1,958, but the 41 that hold an actor.edn, because that is the
  only population where receipts can exist`.

  Run as written, that step returns 41 zeros. Both sides are structurally zero
  today: KUMO is never called from production (ADR-2608026100 records the
  engine as complete and unfired), so there is no receipt to fold, and only one
  cloud-itonami repo carries an x402 SKU. A zero margin for `earned nothing and
  burned nothing` prints identically to a zero margin for `broke even`, and
  CLAUDE.md names that shape as the one defect class this workspace keeps
  rebuilding: the check that could not measure returning the value of the check
  that measured and found nothing wrong.

  So margin is three-valued here. `:unmeasured` is not zero, and it carries the
  reason it could not be closed.

  It also reports the population, because the population is wrong. The maturity
  scan sets `:repo/has-actor-edn?` with a basename match anywhere in the tree
  (`has-file?` in itonami-maturity-scan.cljs), and ten of the forty-one hits are
  `schema/actor.edn` -- auto-generated Datomic attribute vectors that have
  nothing to do with actor identity. An actor manifest is a map that names a
  subject; a schema is a vector of #:db{}. This distinguishes them and reports
  the difference rather than correcting the scan, because changing how the scan
  counts would move several hundred maturity scores in one step, and the scan
  says so about itself.

  Findings:

    no-sku                an actor with no way to be paid. This is the
                          1,958-against-2 ratio made per-actor: revenue is
                          measured, and measured at zero, because no catalog
                          entry names it.
    sku-without-receipts  an actor that can be paid and whose cost side cannot
                          be closed. This is the one that blocks step 4.
    schema-not-actor      population contamination, reported against the
                          detector rather than the repo.

  Refusals. `I could not measure` exits 2, never 0: no orgs tree, no evidence
  file, zero actors admitted, or a catalog that would not load. The catalog is
  the revenue side in full -- if it does not load, every actor would read as
  `no-sku`, and a fetch failure would print as the finding it is supposed to
  detect. `SCANNED<TAB>0` is not clean."
  (:require ["fs" :as fs] ["path" :as p] [clojure.string :as str] [clojure.edn :as edn]))

(def argv (vec (drop 2 (.-argv js/process))))
(defn flag [n] (some #{n} argv))
(defn opt [n d] (let [i (.indexOf argv n)] (if (neg? i) d (get argv (inc i) d))))
(def root (opt "--root" "."))
(def findings? (flag "--findings"))
(def catalog-url (opt "--catalog" "https://x402.nexus/catalog"))
(def offline? (flag "--no-catalog"))
;; The cost side, when it exists. Shape: {"repo-name" {:kumo n :yata n :revenue n}}
;; in posted-price credits, folded from signed receipts. Nothing writes this file
;; today; the flag is the seam that makes step 4 runnable the day something does,
;; and the reason this detector has a reachable green.
(def receipts-path (opt "--receipts" nil))

(defn slurp* [f] (try (.readFileSync fs f "utf8") (catch :default _ nil)))

(defn finding! [sev k detail]
  (when findings? (println (str "FINDING\t" sev "\t" k "\t" detail))))

(defn refuse! [msg]
  (println (str "REFUSED\t" msg))
  (println "Refusing to report a pass: the measurement did not run.")
  (.exit js/process 2))

;; --- population -------------------------------------------------------------
;;
;; An actor manifest names a subject. Every real one in the fleet carries at
;; least one of these; a Datomic schema carries none of them because it is a
;; vector, not a map.
(def identity-keys #{:did :handle :actor/id :actor :ipns-key})

;; The same manifest appears in two shapes. Phase-3 edn-datomize wrapped several
;; of them as tx-data -- a vector holding one map whose keys are ns-prefixed
;; (:actor/did, :actor/handle). That is still an actor manifest and must not be
;; counted as contamination; the first version of this detector called all
;; twelve vectors schemas and was wrong about at least aburi, caught by reading
;; the body rather than the header comment.
;;
;; A Datomic schema is also a vector, and is told apart by what its entities
;; say: attribute definitions carry :db/valueType and :db/cardinality and
;; describe attributes, never a subject.
(def ns-identity-keys #{:actor/did :actor/handle :actor/id :actor/ipns-key})

(defn- schema-entity? [m]
  (and (map? m) (or (contains? m :db/valueType) (contains? m :db/cardinality))))

(defn- actor-entity? [m]
  (and (map? m) (some ns-identity-keys (keys m))))

(defn classify-manifest
  "→ [:actor m] | [:schema nil] | [:unparseable reason] | [:absent nil]"
  [path]
  (if-not (fs/existsSync path)
    [:absent nil]
    (let [t (slurp* path)]
      (if-not t
        [:unparseable "unreadable"]
        (let [v (try (edn/read-string t) (catch :default e [::err (.-message e)]))]
          (cond
            (and (vector? v) (= ::err (first v))) [:unparseable (second v)]
            (map? v) (if (some identity-keys (keys v))
                       [:actor v]
                       [:unparseable "map with no identity key"])
            (vector? v)
            (cond
              (some actor-entity? v) [:actor (first (filter actor-entity? v))]
              (some schema-entity? v) [:schema nil]
              :else [:unparseable "vector that is neither tx-data for an actor nor attribute definitions"])
            :else [:unparseable (str "not a map or vector: " (type v))]))))))

(defn find-actor-edn
  "Mirror the maturity scan's basename rule so the two populations are
   comparable, then judge the file by its content rather than its name."
  [dir]
  (let [seen (atom nil)]
    (letfn [(walk [d depth]
              (when (and (nil? @seen) (< depth 4))
                (doseq [e (try (vec (.readdirSync fs d)) (catch :default _ []))
                        :while (nil? @seen)]
                  (let [f (p/join d e)]
                    (cond
                      (= e "actor.edn") (reset! seen f)
                      (and (not (str/starts-with? e "."))
                           (not (#{"node_modules" "target" "dist" "build"} e))
                           (try (.isDirectory (.statSync fs f)) (catch :default _ false)))
                      (walk f (inc depth)))))))]
      (walk dir 0)
      @seen)))

;; --- revenue side -----------------------------------------------------------

(defn fetch-catalog []
  (-> (js/fetch catalog-url)
      (.then (fn [r] (if (.-ok r) (.json r)
                         (throw (js/Error. (str "HTTP " (.-status r)))))))
      (.then (fn [j] (js->clj j :keywordize-keys true)))))

(defn sku-index
  "seller name → the SKUs it sells. Sellers are named by the facilitator, not by
   repo path, so the join is by name and is reported as such."
  [catalog]
  (group-by :seller (:items catalog)))

;; --- cost side --------------------------------------------------------------
;;
;; KUMO burn is folded from signed receipts (murakumo.infer.credits/receipt).
;; There is no receipt store on this host and none is reachable from a repo
;; scan, so the cost side reports why it is open rather than reporting zero.

(def receipts
  (when receipts-path
    (if-not (fs/existsSync receipts-path)
      (refuse! (str "receipts file not found: " receipts-path))
      (try (edn/read-string (slurp* receipts-path))
           (catch :default e (refuse! (str "receipts unreadable: " (.-message e))))))))

(defn kumo-burn [repo]
  (if-let [v (get-in receipts [repo :kumo])]
    {:value v :reason nil}
    {:value nil :reason "no receipt store: murakumo.infer.credits is not called from production (ADR-2608026100)"}))

(defn yata-burn [repo]
  (if-let [v (get-in receipts [repo :yata])]
    {:value v :reason nil}
    {:value nil :reason "YATA has no posted price: cost inputs unmeasured (ADR-2608291009 D6)"}))

(defn settled-revenue [repo]
  (get-in receipts [repo :revenue]))

;; --- run --------------------------------------------------------------------

(defn -main []
  (let [orgs (p/join root "orgs" "cloud-itonami")
        ev-path (p/join root "manifest" "itonami-maturity-evidence.edn")]
    (when-not (fs/existsSync orgs) (refuse! (str "no orgs tree at " orgs)))
    (when-not (fs/existsSync ev-path) (refuse! (str "no evidence file at " ev-path)))
    (let [evidence (try (edn/read-string (slurp* ev-path))
                        (catch :default e (refuse! (str "evidence unreadable: " (.-message e)))))
          flagged (filter #(and (:repo/path %) (:repo/has-actor-edn? %)) evidence)]
      (when (zero? (count flagged)) (refuse! "evidence flagged zero actor repos"))
      (-> (if offline? (js/Promise.resolve nil) (fetch-catalog))
          (.then
           (fn [catalog]
             ;; A 200 that carries no items is not an empty catalog, it is a
             ;; page that is not the catalog -- x402.nexus answers 200 with a
             ;; service index for any unknown path. Admitting it would make
             ;; every actor read `no-sku`, which is the finding this detector
             ;; is for. Measured 2026-08-29.
             (when-not offline?
               (when (nil? catalog) (refuse! "catalog returned nothing"))
               (when-not (seq (:items catalog))
                 (refuse! (str "catalog at " catalog-url
                               " carried no :items -- responded, but is not a catalog"))))
             (let [skus (when catalog (sku-index catalog))
                   rows (for [e flagged
                              :let [dir (p/join root (:repo/path e))
                                    f (find-actor-edn dir)
                                    [kind m] (if f (classify-manifest f) [:absent nil])
                                    nm (:repo/name e)]]
                          {:repo nm :path (:repo/path e) :file f :kind kind :manifest m
                           :skus (get skus nm)})
                   actors (filter #(= :actor (:kind %)) rows)
                   schemas (filter #(= :schema (:kind %)) rows)
                   broken (filter #(#{:unparseable :absent} (:kind %)) rows)]
               (when (zero? (count actors)) (refuse! "no admissible actor manifest found"))
               ;; evidence floor, before any verdict
               (println (str "SCANNED\t" (count rows)))
               (println (str "ADMITTED\t" (count actors)))
               (println (str "SCHEMA-NOT-ACTOR\t" (count schemas)))
               (println (str "UNPARSEABLE\t" (count broken)))
               (println (str "CATALOG\t" (if offline? "skipped (--no-catalog)"
                                             (str (count (:items catalog)) " SKUs, "
                                                  (count (distinct (map :seller (:items catalog)))) " sellers"))))
               (println)
               (let [priced (filter #(seq (:skus %)) actors)
                     unpriced (remove #(seq (:skus %)) actors)]
                 (println (str "actors with a SKU:      " (count priced)))
                 (println (str "actors with no SKU:     " (count unpriced)))
                 (let [open-cost (remove #(and (:value (kumo-burn (:repo %)))
                                               (:value (yata-burn (:repo %)))
                                               (settled-revenue (:repo %)))
                                         actors)]
                 (println (str "margin computable:      " (- (count actors) (count open-cost))
                               (when (seq open-cost)
                                 (str "  (open for " (count open-cost) ": "
                                      (:reason (kumo-burn nil)) ")"))))
                 (println)
                 ;; Three findings, not forty-one rows. Each is aggregate because
                 ;; each has one fix; a per-repo row here would report the same
                 ;; structural fact 29 times an hour and stop being read.
                 (when (seq schemas)
                   (finding! "warn" "actor-population-contaminated"
                             (str (count schemas) " of " (count rows)
                                  " actor.edn hits are Datomic schemas, not actor manifests"
                                  " (itonami-maturity-scan's has-file? matches a basename anywhere"
                                  " in the tree). :repo/has-actor-edn? overstates the governed"
                                  " population by " (count schemas) ". Examples: "
                                  (str/join ", " (map :path (take 3 schemas))))))
                 (when (seq unpriced)
                   (finding! "warn" "actors-without-sku"
                             (str (count unpriced) " of " (count actors)
                                  " admitted actors have no catalog entry, so revenue is"
                                  " measured at 0: they have no way to be paid."
                                  " Catalog: " catalog-url)))
                 (when (seq open-cost)
                   (finding! "fail" "cost-side-open"
                             (str "margin is uncomputable for " (count open-cost) " of "
                                  (count actors) " admitted actors. "
                                  (:reason (kumo-burn nil))
                                  " ADR-2608291009 step 4 cannot run until a receipt"
                                  " store exists; pass --receipts <edn> once one does.")))
                 (when (seq broken)
                   (finding! "warn" "actor-manifest-unparseable"
                             (str (count broken) " actor.edn could not be read or parsed: "
                                  (str/join ", " (map :path (take 3 broken))))))
                 ;; Three outcomes, three codes. A margin nobody can compute is
                 ;; not a pass, and must not share an exit code with one that is.
                 (println)
                 (let [margins (for [r actors]
                                 (let [k (kumo-burn (:repo r)) y (yata-burn (:repo r))]
                                   (if-let [rev (and (:value k) (:value y) (settled-revenue (:repo r)))]
                                     {:repo (:repo r) :margin (- rev (:value k) (:value y))}
                                     {:repo (:repo r) :margin nil})))
                       open (filter #(nil? (:margin %)) margins)
                       negative (filter #(some-> (:margin %) neg?) margins)]
                   (cond
                     (seq open)
                     (do (println (str "VERDICT\tunmeasured for " (count open) " of "
                                       (count actors) " admitted actors"))
                         (.exit js/process 2))
                     (seq negative)
                     (do (println (str "VERDICT\t" (count negative) " actor(s) below margin 0"))
                         (.exit js/process 1))
                     :else
                     (do (println (str "VERDICT\tall " (count actors)
                                       " admitted actors closed a window at or above margin 0"))
                         (.exit js/process 0)))))))))
          (.catch (fn [e] (refuse! (str "catalog fetch failed: " (.-message e)))))))))

(-main)
