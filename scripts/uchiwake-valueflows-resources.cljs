#!/usr/bin/env nbb
;; scripts/uchiwake-valueflows-resources.cljs — the first REAL economic content on
;; the Valueflows plane.
;;
;;   nbb --classpath ".:scripts/nbb_compat" scripts/uchiwake-valueflows-resources.cljs \
;;     --data-root $HOME/github/com-junkawasaki
;;   ... --check
;;
;; ADR-2608153000. Until now every cloud-itonami business on the Valueflows plane
;; had a CLASSIFICATION and no economic content: blueprints declare governance and
;; technology, never a resource. cloud-itonami/uchiwake does carry resources —
;; products with real GS1 GTINs, their parts, and the raw materials — so they
;; project into `vf:ResourceSpecification`, which upstream defines as specifying
;; "the kind of economic or environmental resource, EVEN IF the resource is not
;; instantiated as an EconomicResource". That last clause is why this is a
;; faithful use rather than a stretch: nothing here claims any of it moved.
;;
;; ## Sourcing is carried forward, not dropped
;;
;; uchiwake labels every node `:authoritative` or `:representative` — and it is
;; overwhelmingly the latter: measured 2026-08-15, 2 of 165. The two authoritative
;; ones are products whose GTINs are publicly documented; everything else is
;; inferred from teardowns and ingredient labels. That label travels onto every
;; projected entity and is counted separately in coverage. A plane that showed
;; 165 resource specifications without the 2/163 split would be presenting
;; inference as measurement, which is the one thing this projection exists to
;; avoid.
;;
;; ## This is NOT a bill of materials
;;
;; uchiwake's README describes a product -> part -> material BOM with quantities.
;; The committed data has neither: measured 2026-08-15, the only attributes
;; present are identity and classification — no edges, no quantities, in either
;; products.merged.kotoba.edn or seed-products.kotoba.edn. So
;; `dependent-demand` and `value-rollup` still cannot run on it, and coverage
;; says so rather than letting 165 specifications read as a recipe.
;;
;; Exit codes: 0 written/identical · 1 STALE · 2 COULD NOT ANSWER.

(ns uchiwake-valueflows-resources
  (:require ["fs" :as fs]
            ["path" :as path]
            [clojure.string :as str]
            [clojure.pprint :as pprint]
            [clojure.edn :as edn]))

(def out-path "90-docs/valueflows/uchiwake-resources-vf.datoms.edn")
(def dataset "uchiwake-valueflows")
(def source-rel "orgs/cloud-itonami/uchiwake/data/products.merged.kotoba.edn")

;; GS1's own canonical URI form for a trade item (GS1 Digital Link). Recorded as
;; an identifier, not a promise of a page: measured 2026-08-15,
;; https://id.gs1.org/01/05449000000996 returns 404, which says the GS1 resolver
;; does not know that GTIN and nothing about whether the identifier is correct.
;; Same distinction as vf:omUnitIdentifier and the om-2 IRIs.
(def gs1-prefix "https://id.gs1.org/01/")

;; Measured against the workspace on 2026-08-15. A run that reads materially
;; fewer has lost an input and must not write a smaller file that looks whole.
(def floor {:products 20 :parts 15 :materials 100})

(defn- die [code msg] (println msg) (js/process.exit code))
(defn- slurp* [p] (str (fs/readFileSync p "utf8")))
(defn- exists? [p] (fs/existsSync p))

(defn- args []
  (let [a (vec (drop 2 (js->clj js/process.argv)))]
    {:data-root (or (second (drop-while #(not= "--data-root" %) a)) (.cwd js/process))
     :check? (boolean (some #{"--check"} a))}))

(defn- read-nodes [root]
  (let [f (path/join root source-rel)]
    (when-not (exists? f)
      (die 2 (str "CANNOT ANSWER: " f " is absent. west checkouts are gitignored,"
                  " so a worktree must be given --data-root.")))
    (let [v (edn/read-string (slurp* f))]
      (when-not (vector? v)
        (die 2 (str "CANNOT ANSWER: " f " did not read as a vector of nodes.")))
      v)))

(defn- kind-of [n]
  (cond (:product/id n) :product
        (:part/id n) :part
        (:material/id n) :material
        :else nil))

(defn- classifications
  "One or more taxonomy identifiers, which is what vf:classifiedAs takes. Only
   codes the node actually carries — nothing is derived from a name."
  [n k]
  (let [g #(get n (keyword (name k) %))]
    (cond-> []
      (g "gtin") (conj (str gs1-prefix (g "gtin")))
      (g "unspsc") (conj (str "unspsc:" (g "unspsc")))
      (g "hs-code") (conj (str "hs:" (g "hs-code"))))))

(defn- node->entity [i n]
  (let [k (kind-of n)
        g #(get n (keyword (name k) %))
        cls (classifications n k)]
    (cond-> {:db/id (- (inc i))
             :source/dataset dataset
             :vf.resource-spec/id (g "id")
             :vf.resource-spec/name (g "name")
             :vf.resource-spec/kind (name k)
             ;; uchiwake's own label, verbatim. 2 of 165 are :authoritative.
             :vf.resource-spec/sourcing (some-> (g "sourcing") name)
             :vf.resource-spec/classified-as cls
             :vf.resource-spec/classification-count (count cls)}
      (g "gtin") (assoc :vf.resource-spec/gtin (g "gtin"))
      (g "unspsc") (assoc :vf.resource-spec/unspsc (g "unspsc"))
      (g "hs-code") (assoc :vf.resource-spec/hs-code (g "hs-code"))
      (g "brand") (assoc :vf.resource-spec/brand (g "brand"))
      (g "brand-owner") (assoc :vf.resource-spec/brand-owner (g "brand-owner"))
      (g "kind") (assoc :vf.resource-spec/material-kind (some-> (g "kind") name))
      (g "sector") (assoc :vf.resource-spec/sector (some-> (g "sector") name))
      ;; a vf:Measure: value plus unit. The unit is recorded VERBATIM; resolving
      ;; it belongs to valueflows.unit in the consumer, not to a second copy of
      ;; the registry here.
      (g "net-content")
      (assoc :vf.resource-spec/net-content-value (g "net-content")
             :vf.resource-spec/net-content-unit (g "net-content-unit")))))

(defn- coverage [i entities]
  (let [by-kind (frequencies (map :vf.resource-spec/kind entities))
        by-sourcing (frequencies (map :vf.resource-spec/sourcing entities))
        units (into (sorted-set) (keep :vf.resource-spec/net-content-unit entities))]
    {:db/id (- (inc i))
     :source/dataset dataset
     :vf.coverage/resource-specs (count entities)
     :vf.coverage/by-kind by-kind
     :vf.coverage/by-sourcing by-sourcing
     :vf.coverage/authoritative (get by-sourcing "authoritative" 0)
     :vf.coverage/representative (get by-sourcing "representative" 0)
     :vf.coverage/with-gtin (count (filter :vf.resource-spec/gtin entities))
     :vf.coverage/with-net-content (count (filter :vf.resource-spec/net-content-value entities))
     :vf.coverage/net-content-units (vec units)
     ;; the two things a reader must not conclude from a count here
     :vf.coverage/is-a-bill-of-materials? false
     :vf.coverage/has-quantities-between-resources? false
     :vf.coverage/complete? false
     :vf.coverage/note
     (str "The first real economic content on this plane, and two things it is"
          " NOT. (1) It is not a bill of materials: uchiwake's README describes"
          " a product -> part -> material decomposition with quantities, and the"
          " committed data has no edges and no quantities at all — only identity"
          " and classification. dependent-demand and value-rollup therefore"
          " still cannot run. (2) It is overwhelmingly INFERRED: "
          (get by-sourcing "authoritative" 0) " of " (count entities)
          " nodes are :authoritative (products with publicly documented GTINs);"
          " the rest are :representative, inferred from teardowns and ingredient"
          " labels by uchiwake itself. That label is carried onto every entity"
          " here and must be read before citing any of these as measured.")}))

(defn- render [entities cov]
  (str ";; GENERATED by scripts/uchiwake-valueflows-resources.cljs. DO NOT EDIT BY HAND.\n"
       ";; ADR-2608153000. Regenerate:\n"
       ";;   nbb --classpath \".:scripts/nbb_compat\" \\\n"
       ";;     scripts/uchiwake-valueflows-resources.cljs --data-root $HOME/github/com-junkawasaki\n"
       ";;\n"
       ";; Source: " source-rel "\n"
       ";; Every entity is :source/dataset \"" dataset "\". The LAST entity is coverage —\n"
       ";; read it before citing any count, and read :vf.resource-spec/sourcing before\n"
       ";; citing any individual specification as measured.\n"
       (with-out-str (pprint/pprint (conj (vec entities) cov)))))

(let [{:keys [data-root check?]} (args)
      nodes (read-nodes data-root)
      typed (filterv kind-of nodes)
      entities (vec (map-indexed node->entity typed))
      by-kind (frequencies (map :vf.resource-spec/kind entities))]
  (doseq [[k n] [["products" (get by-kind "product" 0)]
                 ["parts" (get by-kind "part" 0)]
                 ["materials" (get by-kind "material" 0)]]]
    (let [f (get floor (keyword k))]
      (when (and f (< n f))
        (die 2 (str "CANNOT ANSWER: read " n " " k ", floor " f
                    ". An input is missing; a smaller file would look complete.")))))
  (let [cov (coverage (count entities) entities)
        content (render entities cov)]
    (println (str "projected " (count entities) " resource specifications"
                  " · " (pr-str by-kind)
                  " · authoritative " (:vf.coverage/authoritative cov)
                  " · representative " (:vf.coverage/representative cov)
                  " · units " (pr-str (:vf.coverage/net-content-units cov))))
    (if check?
      (let [have (when (exists? out-path) (slurp* out-path))]
        (cond
          (nil? have) (die 1 (str "STALE: " out-path " is absent"))
          (not= have content) (die 1 (str "STALE: " out-path " disagrees with the workspace"))
          :else (println (str "OK: " out-path " matches"))))
      (do (fs/mkdirSync (path/dirname out-path) #js {:recursive true})
          (fs/writeFileSync out-path content)
          (println (str "wrote " out-path " (" (count content) " bytes)"))))))
