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
    ;; 訂正 2026-08-31: YATA の掲示価格は決まった（800 credits/TB月、ADR-2608313700）。
    ;; 開いているのは価格ではなく **計器** —— GB月 を actor 単位で数える経路が無い。
    ;; 古い理由を残すと、解けた問題を解けていないことにしてしまう。
    {:value nil :reason "no storage meter: YATA is posted at 800 credits/TB-month (ADR-2608313700) but nothing counts GB-months per actor"}))

(defn settled-revenue [repo]
  (get-in receipts [repo :revenue]))


;; --- the offer ladder ------------------------------------------------------
;;
;; `no-sku` was one bucket doing two jobs. An actor that has written a price
;; book and is holding it back because its own gates are unmet is not in the
;; same state as an actor that has never named what it sells, and collapsing
;; them prints the same 31 either way -- the shape this script's own docstring
;; is about.
;;
;; The shape is not invented here. `cloud-itonami-isic-6311-identity/pricing.edn`
;; already carries it (ADR-2608110200): the price book is DATA in one file,
;; `:pricing/gates` are what must be true before charging anyone, and
;; `:plan/status` says whether a plan is offered or merely argued with. That
;; repo is 1 of 1,965. Reading it here is what makes the other 1,964 a funnel
;; with a place to put the answer rather than a single flat number.

(defn read-pricing-book
  "`pricing.edn` at the repo root, or nil. Unreadable is NOT nil -- a file that
   exists and will not parse is a finding, not an absence."
  [dir]
  (let [f (p/join dir "pricing.edn")]
    (when (fs/existsSync f)
      (try (edn/read-string (slurp* f))
           (catch :default e {:pricing/unreadable (.-message e)})))))

(defn unmet-gates
  "Gates the book itself declares are not met. `:gate/met` must be literally
   true -- a missing flag is unmet, because a gate nobody answered is not a
   gate that passed."
  [book]
  (remove #(true? (:gate/met %)) (:pricing/gates book)))

(defn offered-plans
  "Plans whose status is something other than :proposed. A book of proposals is
   a book nobody may be billed from, which is what that repo says about itself."
  [book]
  (remove #(= :proposed (:plan/status %)) (:pricing/plans book)))

(defn offer-stage
  "Where this actor stands between existing and being payable.

     :in-catalog  a facilitator lists a SKU for it -- it can actually be paid
     :sellable    price book present, no unmet gate, at least one plan offered
     :proposed    price book present, but gated or all plans still proposals
     :unreadable  a pricing.edn that will not parse
     :no-offer    nothing names what this actor sells

   The order matters: being in the catalog is the only stage where revenue can
   be non-zero, and the three below it are degrees of not-yet, not degrees of
   failure."
  [{:keys [skus book]}]
  (cond
    (seq skus)                        :in-catalog
    (:pricing/unreadable book)        :unreadable
    (nil? book)                       :no-offer
    (and (empty? (unmet-gates book))
         (seq (offered-plans book)))  :sellable
    :else                             :proposed))

;; --- run --------------------------------------------------------------------

(defn -main []
  (let [orgs (p/join root "orgs" "cloud-itonami")
        ev-path (p/join root "manifest" "itonami-maturity-evidence.edn")]
    (when-not (fs/existsSync orgs) (refuse! (str "no orgs tree at " orgs)))
    (when-not (fs/existsSync ev-path) (refuse! (str "no evidence file at " ev-path)))
    (let [evidence (try (edn/read-string (slurp* ev-path))
                        (catch :default e (refuse! (str "evidence unreadable: " (.-message e)))))
          flagged (filter #(and (:repo/path %) (:repo/has-actor-edn? %)) evidence)
          ;; **母集団は tree ではなく、一度スキャンして保存した file から来る。**
          ;; 昨日 scan した evidence に対して今日 actor を足すと、この検出器は
          ;; その actor を `no-offer` に数えるのではなく **一度も見ない** ——
          ;; そして出力にはそれが書かれていなかった。
          ;;
          ;; 実測 2026-08-31: cloud-itonami/actor-hanmoto を作った直後、
          ;; metering detector（tree を直接歩く）は 1 件増え、この script は
          ;; 31 のまま動かなかった。同じ問いに 2 つの数が出て、片方は
          ;; 「無い」ではなく「見えない」だった。
          ev-mtime (try (.-mtime (fs/statSync ev-path)) (catch :default _ nil))
          ;; **数の差ではなく集合の差で取る。** evidence の `:repo/has-actor-edn?` は
          ;; tree 内のどこに actor.edn があっても真になる（この script 自身が
          ;; contamination として報告している性質）ので、repo 直下だけを数えた値と
          ;; は比較できない。最初この 2 つを引き算して 32 - 41 = 0 を出し、
          ;; **見えていない actor が居るのに UNSEEN 0 と報告しかけた。**
          on-disk (let [base (p/join root "orgs" "cloud-itonami")]
                    (if-not (fs/existsSync base) []
                      (vec (filter #(fs/existsSync (p/join base % "actor.edn"))
                                   (fs/readdirSync base)))))
          known (set (map #(last (str/split (str (:repo/path %)) #"/")) flagged))
          unseen-names (vec (remove known on-disk))
          unseen (count unseen-names)]
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
                           :skus (get skus nm) :book (read-pricing-book dir)})
                   actors (filter #(= :actor (:kind %)) rows)
                   schemas (filter #(= :schema (:kind %)) rows)
                   broken (filter #(#{:unparseable :absent} (:kind %)) rows)]
               (when (zero? (count actors)) (refuse! "no admissible actor manifest found"))
               ;; evidence floor, before any verdict
               ;; **何を読んだかを先に言う。** 母集団が cache から来ることと、
               ;; その cache がいつのものかを、数の前に置く。
               (println (str "EVIDENCE\t" ev-path "\t"
                             (if ev-mtime (.toISOString ev-mtime) "mtime unknown")))
               (println (str "POPULATION\tfrom evidence, not from the tree"
                             "\ton-disk actor.edn dirs=" (count on-disk)
                             "\tin evidence=" (count flagged)
                             (when (pos? unseen) (str "\tUNSEEN=" unseen))))
               (println (str "SCANNED\t" (count rows)))
               (println (str "ADMITTED\t" (count actors)))
               (println (str "SCHEMA-NOT-ACTOR\t" (count schemas)))
               (println (str "UNPARSEABLE\t" (count broken)))
               (println (str "CATALOG\t" (if offline? "skipped (--no-catalog)"
                                             (str (count (:items catalog)) " SKUs, "
                                                  (count (distinct (map :seller (:items catalog)))) " sellers"))))
               (println)
               (let [priced (filter #(seq (:skus %)) actors)
                     unpriced (remove #(seq (:skus %)) actors)
                     stage (group-by offer-stage actors)
                     n #(count (get stage % []))]
                 (println (str "actors with a SKU:      " (count priced)))
                 (println (str "actors with no SKU:     " (count unpriced)))
                 (println)
                 ;; The ladder, not the flat number. Each rung has a different
                 ;; next action, which is the whole reason for splitting them.
                 (println "OFFER LADDER (ADR-2608110200 の price book を読んで分類)")
                 (println (str "  in-catalog   " (n :in-catalog)
                               "\t払われる経路が在る（収入が 0 でない唯一の段）"))
                 (println (str "  sellable     " (n :sellable)
                               "\t価格表が在り gate も通っている。catalog へ /apply するだけ"))
                 (println (str "  proposed     " (n :proposed)
                               "\t価格表は在るが gate 未達 or 全 plan が :proposed"))
                 (println (str "  unreadable   " (n :unreadable)
                               "\tpricing.edn が在るが読めない"))
                 (println (str "  no-offer     " (n :no-offer)
                               "\t何を売るのか、どこにも書かれていない"))
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
                 ;; 2 つの finding に割る。`no-offer` の次の一手は「何を売るか決める」で、
                 ;; `proposed` / `sellable` の次の一手は「gate を通す」「/apply する」。
                 ;; 1 つの finding にまとめると、次の一手が違うものが同じ行になる。
                 (when (seq (get stage :no-offer))
                   (finding! "warn" "actors-without-an-offer"
                             (str (n :no-offer) " of " (count actors)
                                  " admitted actors declare no price book at all"
                                  " (no pricing.edn). Revenue is measured at 0 because"
                                  " nothing names what they sell. The shape to copy is"
                                  " cloud-itonami-isic-6311-identity/pricing.edn"
                                  " (ADR-2608110200): price as data in one file,"
                                  " :pricing/gates for what must be true before charging.")))
                 (when (seq (concat (get stage :proposed) (get stage :sellable)))
                   (finding! "warn" "offers-declared-but-not-payable"
                             (str (+ (n :proposed) (n :sellable)) " actor(s) have a price book"
                                  " but no catalog entry: " (n :sellable) " have met their own"
                                  " gates and only need /apply at " catalog-url ", "
                                  (n :proposed) " are still gated or all-proposed."
                                  " These are NOT the same state as having no offer.")))
                 (when (seq (get stage :unreadable))
                   (finding! "fail" "pricing-book-unreadable"
                             (str (n :unreadable) " pricing.edn present but unparseable: "
                                  (str/join ", " (map :path (take 3 (get stage :unreadable)))))))
                 (when (seq open-cost)
                   (finding! "fail" "cost-side-open"
                             (str "margin is uncomputable for " (count open-cost) " of "
                                  (count actors) " admitted actors. "
                                  (:reason (kumo-burn nil))
                                  " ADR-2608291009 step 4 cannot run until a receipt"
                                  " store exists; pass --receipts <edn> once one does.")))
                 ;; 見えていない actor を、`no-offer` の中に黙って混ぜない ——
                 ;; 混ぜたら『値札が無い』と『測っていない』が同じ行になる。
                 (when (pos? unseen)
                   (finding! "warn" "evidence-behind-the-tree"
                             (str unseen " repo(s) hold an actor.edn on disk that this"
                                  " evidence file does not know about, so they are NOT"
                                  " in any count above -- not even in no-offer: "
                                  (str/join ", " unseen-names)
                                  ". Re-run scripts/itonami-maturity-scan.cljs. Evidence: "
                                  ev-path)))
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
