;; Is `:query/digest` expensive, or was the interpreter expensive?
;;
;; ADR-2609109800 measured the digest under nbb and stated the caveat that nbb
;; is not workerd. That caveat was hiding TWO differences inside one word: nbb
;; is a different runtime, and it is also not a compiler -- it is SCI, an
;; interpreter. A number produced by interpreting ClojureScript says very
;; little about the same code compiled by Closure and run in an isolate.
;;
;; This separates them. The same three query values are measured in three
;; places, all over HTTP so the transport cost is in every number and cancels
;; in the slope:
;;
;;   interpreted   nbb / SCI, this process
;;   compiled      the shadow-cljs release bundle, this process (Node)
;;   workerd       the same bundle, under `wrangler dev`
;;
;; ## Why the slope rather than a stopwatch
;;
;; A Worker's clock does not advance the way a process's does, so an in-worker
;; `performance.now()` is not a measurement one can compare with Node's. The
;; client therefore asks each endpoint to do the work N times and regresses
;; latency on N: the slope is the per-digest cost and the intercept is
;; everything that is not the digest. That works identically in all three
;; places and needs no clock inside any of them.
;;
;; ## Setup (the bundle is a build artifact and is not committed)
;;
;;   cd 90-docs/query-plane/digest-runtime
;;   ln -sfn ../../../orgs/net-kotobase/ipfs/node_modules node_modules
;;   node ../../../scripts/resource-guard.mjs run build -- \
;;     ./node_modules/.bin/shadow-cljs release esm
;;
;; Then, from the superproject root:
;;
;;   nbb --classpath "orgs/kotoba-lang/org-w3-owl2/src:orgs/kotoba-lang/io-ipld/src:orgs/kotoba-lang/org-ietf-cbor/src:orgs/kotoba-lang/io-multiformats/src:orgs/kotoba-lang/org-nist-sha2/src:orgs/kotoba-lang/text/src" 90-docs/query-plane/bench_digest_runtimes.cljs
;;
;; The bench starts its own server, writes a three-line default-export shim to
;; a temporary directory and spawns `wrangler dev` over it, then shuts both
;; down. If wrangler cannot start, the workerd row says so rather than being
;; quietly omitted.

(ns bench-digest-runtimes
  (:require [owl.rules :as rules]
            [kotoba.value.codec :as vc]
            ["os" :as os]
            ["fs" :as fs]
            ["path" :as path]
            [clojure.string :as str]
            ["http" :as http]
            ["child_process" :as cp]))

(def reps 5)
(def counts [0 25 50 100 200])
(def node-port 8789)
(def workerd-port 8788)
(def bundle (path/resolve "90-docs/query-plane/digest-runtime/out/bench.js"))

(def queries
  {"plain" {:find '[?c] :where '[["Felix" :rdf/type ?c]]}
   "hierarchy" {:find '[?c] :where '[(owl-type "Felix" ?c)]
                :rules (rules/hierarchy-rules)}
   "triple" {:find '[?o] :where '[(owl-triple "Felix" :knows ?o)]
             :rules (rules/triple-rules)}})

(defn- interpreted-n [name n]
  (let [q (get queries name)]
    (loop [i 0 last nil]
      (if (< i n) (recur (inc i) (vc/value-cid q)) (str last)))))

;; ── the local server: interpreted here, compiled from the bundle ─────────────

(defn- start-server! [compiled-digest-n]
  (js/Promise.
   (fn [resolve _]
     (let [srv (http/createServer
                (fn [req res]
                  (let [u (js/URL. (.-url req) "http://localhost")
                        p (.-searchParams u)
                        q (or (.get p "q") "plain")
                        n (js/parseInt (or (.get p "n") "0") 10)
                        compiled? (= "/compiled" (.-pathname u))
                        last (if compiled? (compiled-digest-n q n) (interpreted-n q n))]
                    (.writeHead res 200 (js-obj "content-type" "application/json"))
                    (.end res (js/JSON.stringify (js-obj "q" q "n" n "last" last))))))]
       (.listen srv node-port #(resolve srv))))))

;; ── workerd ─────────────────────────────────────────────────────────────────

(defn- spawn-workerd! []
  (let [dir (fs/mkdtempSync (path/join (os/tmpdir) "digest-runtime-"))]
    ;; Generated, not committed: this workspace does not author raw .mjs, and a
    ;; default-export shim is a build detail of wrangler's entry contract.
    (fs/writeFileSync (path/join dir "worker.mjs")
                      (str "import { worker } from " (js/JSON.stringify bundle) ";\n"
                           "export default worker;\n"))
    (fs/writeFileSync (path/join dir "wrangler.toml")
                      (str "name = \"digest-runtime\"\n"
                           "main = \"worker.mjs\"\n"
                           "compatibility_date = \"2026-01-01\"\n"))
    (cp/spawn "wrangler"
              (clj->js ["dev" "--port" (str workerd-port) "--ip" "127.0.0.1"])
              (js-obj "cwd" dir "stdio" "ignore"
                      "env" (js/Object.assign (js-obj) (.-env js/process)
                                              (js-obj "WRANGLER_SEND_METRICS" "false"))))))

;; ── client ──────────────────────────────────────────────────────────────────

(defn- get-json [url]
  (let [t0 (js/performance.now)]
    (-> (js/fetch url)
        (.then (fn [r] (-> (.json r)
                           (.then (fn [j] {:ms (- (js/performance.now) t0)
                                           :last (.-last j)}))))))))

(defn- sample [base q n acc k]
  (if (zero? k)
    (js/Promise.resolve acc)
    (-> (get-json (str base "?q=" q "&n=" n))
        (.then (fn [r] (sample base q n (conj acc r) (dec k)))))))

(defn- median [xs] (let [v (vec (sort xs))] (nth v (quot (count v) 2))))

(defn- regress [points]
  (let [n (count points)
        sx (reduce + (map first points)) sy (reduce + (map second points))
        sxx (reduce + (map #(* (first %) (first %)) points))
        sxy (reduce + (map #(* (first %) (second %)) points))
        slope (/ (- (* n sxy) (* sx sy)) (- (* n sxx) (* sx sx)))
        intercept (/ (- sy (* slope sx)) n)
        mean (/ sy n)
        ss-tot (reduce + (map #(let [d (- (second %) mean)] (* d d)) points))
        ss-res (reduce + (map #(let [d (- (second %) (+ intercept (* slope (first %))))]
                                 (* d d)) points))]
    {:intercept intercept :slope slope :r2 (- 1 (/ ss-res ss-tot))}))

(defn- measure [label base q]
  (letfn [(step [ns acc addr]
            (if (empty? ns)
              (js/Promise.resolve {:points acc :addr addr})
              (-> (sample base q (first ns) [] reps)
                  (.then (fn [rs]
                           (step (rest ns)
                                 (conj acc [(first ns) (median (map :ms rs))])
                                 ;; NOT `(or addr (:last (first rs)))`: the first
                                 ;; run is n=0, which computes nothing and returns
                                 ;; the empty string -- and an empty string is
                                 ;; truthy, so the control would have reported
                                 ;; perfect agreement between nine rows that all
                                 ;; carried no address at all. Measured 2026-09-10,
                                 ;; on the first run of this bench.
                                 (or addr (when (pos? (first ns)) (:last (first rs))))))))))]
    (-> (step counts [] nil)
        (.then (fn [{:keys [points addr]}]
                 (assoc (regress points) :label label :q q :addr addr))))))

(defn- fmt [x] (.toFixed x 4))

(defn- print-row [r]
  (println (str (.padEnd (:label r) 14)
                (.padEnd (:q r) 12)
                (.padStart (fmt (:slope r)) 12)
                (.padStart (.toFixed (:intercept r) 2) 12)
                (.padStart (.toFixed (:r2 r) 4) 9)
                "  " (subs (str (:addr r)) 0 20) "...")))

(defn -main []
  (println (str "load " (pr-str (mapv #(.toFixed % 2) (os/loadavg)))
                "  cpus " (count (os/cpus))
                "  reps " reps "  n " (pr-str counts)))
  (when-not (fs/existsSync bundle)
    (println "MISSING BUNDLE" bundle "-- see the setup block at the top")
    (.exit js/process 2))
  (let [child (atom nil) server (atom nil)]
    (-> (js/import bundle)
        (.then (fn [m] (start-server! (.-digestN m))))
        (.then (fn [srv]
                 (reset! server srv)
                 (reset! child (spawn-workerd!))
                 ;; wrangler needs a moment; a fixed wait is honest here
                 ;; because the readiness check below is what decides.
                 (js/Promise. (fn [res _] (js/setTimeout #(res nil) 20000)))))
        (.then (fn [_]
                 (-> (js/fetch (str "http://127.0.0.1:" workerd-port "/?q=plain&n=0"))
                     (.then (fn [_] true))
                     (.catch (fn [_] false)))))
        (.then (fn [workerd-up?]
                 (println)
                 (println (str "runtime      query        ms/digest    intercept       r2  address"))
                 (let [node-base (str "http://127.0.0.1:" node-port)
                       wd-base (str "http://127.0.0.1:" workerd-port "/")]
                   (letfn [(runs [specs acc]
                             (if (empty? specs)
                               (js/Promise.resolve acc)
                               (let [[label base q] (first specs)]
                                 (-> (measure label base q)
                                     (.then (fn [r] (print-row r)
                                              (runs (rest specs) (conj acc r))))))))]
                     (runs (concat
                            (for [q ["plain" "hierarchy" "triple"]]
                              ["interpreted" (str node-base "/interpreted") q])
                            (for [q ["plain" "hierarchy" "triple"]]
                              ["compiled" (str node-base "/compiled") q])
                            (when workerd-up?
                              (for [q ["plain" "hierarchy" "triple"]]
                                ["workerd" wd-base q])))
                           [])))))
        (.then (fn [rows]
                 (println)
                 (let [by (group-by :label rows)
                       addrs (set (map :addr rows))]
                   (println (str "addresses computed: " (count (remove str/blank? (map :addr rows)))
                                 " of " (count rows)
                                 "   distinct: " (count (remove str/blank? addrs))
                                 "  (3 queries, so 3 distinct and 0 blank is agreement)"))
                   (doseq [q ["plain" "hierarchy" "triple"]]
                     (let [as (set (map :addr (filter #(= q (:q %)) rows)))]
                       (println (str "  " (.padEnd q 12) (count as) " address(es): "
                                     (pr-str (mapv #(subs (str %) 0 16) (vec as)))))))
                   (when (contains? by "workerd")
                     (doseq [q ["plain" "hierarchy" "triple"]]
                       (let [g (fn [l] (:slope (first (filter #(and (= l (:label %)) (= q (:q %))) rows))))]
                         (println (str "  " (.padEnd q 12)
                                       " interpreted/compiled " (fmt (/ (g "interpreted") (g "compiled")))
                                       "x   compiled/workerd " (fmt (/ (g "compiled") (g "workerd")))
                                       "x")))))
                   (when-not (contains? by "workerd")
                     (println "workerd DID NOT START -- rows omitted rather than substituted")))
                 (when-let [c @child] (.kill c))
                 (when-let [s @server] (.close s))
                 (js/setTimeout #(.exit js/process 0) 500)))
        (.catch (fn [e]
                  (println "BENCH FAILED" (str e))
                  (when-let [c @child] (.kill c))
                  (when-let [s @server] (.close s))
                  (.exit js/process 1))))))

(-main)
