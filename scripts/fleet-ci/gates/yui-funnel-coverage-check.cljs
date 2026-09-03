#!/usr/bin/env nbb
;; yui-funnel-coverage-check.cljs — per-door machine-readable funnel surface
;; coverage. Evidence for ADR-2609022000's yui-h4 (publish-funnel-per-domain).
;;
;; The lesson this encodes (endpoint-health.edn spirit): a 200 is NOT health.
;; A door can return 200 text/html — the SPA fallback, not a funnel. This check
;; reads the CONTENT TYPE (and for json, the body's first char), not the code.
;;
;; HERMETIC: node global fetch + clojure.string only. No scripts.nbb-compat —
;; the fleet ships this gate file alone and the node's nbb invocation does not
;; carry the superproject's scripts/ dir (measured 2026-09-03 on levi:
;; `Could not find namespace: scripts.nbb-compat` was the gate's own dependency
;; failure, not a funnel regression).
;;
;; Run (fleet): npx --yes nbb gate-yui-funnel.cljs <tree> [--min N]
;;         (operator): nbb scripts/fleet-ci/gates/yui-funnel-coverage-check.cljs --min N
;;
;; Exit: 0 = machine-readable funnel doors >= --min, 1 = below / probe failure,
;;       2 = probe infrastructure failure.

(ns fleet-ci.gates.yui-funnel-coverage-check
  (:require [clojure.string :as str]))

(def argv (vec *command-line-args*))
(def min-doors
  (let [i (.indexOf argv "--min")]
    (js/parseInt (if (and (>= i 0) (< (inc i) (count argv)))
                   (nth argv (inc i) "2")
                   "2") 10)))

(def doors
  [{:domain "kotobase.net"   :url "https://kotobase.net/api/funnel"}
   {:domain "murakumo.cloud" :url "https://murakumo.cloud/api/funnel"}
   {:domain "isekai.network" :url "https://isekai.network/feed/fork-stats.edn"}
   {:domain "kotoba.cloud"   :url "https://kotoba.cloud/api/funnel"}
   {:domain "itonami.cloud"  :url "https://itonami.cloud/api/funnel"}])

(defn- classify [status content-type body]
  (cond
    (nil? status) :probe-failed
    (= 404 status) :no-endpoint
    (str/includes? (str/lower-case (str content-type)) "html") :spa-fallback
    (and (= 200 status)
         (or (str/includes? (str/lower-case (str content-type)) "json")
             ;; machine-readable by BODY, not by content type (types lie):
             ;; isekai's EDN feed ships as application/octet-stream and starts
             ;; with ;; comments. Accept: json object, EDN map, or an EDN map
             ;; after ;; comment lines.
             (str/starts-with? (str/trim (str body)) "{")
             (some #(str/starts-with? % "{:")
                   (->> (str/split-lines (str body))
                        (remove #(str/starts-with? % ";"))
                        (remove #(str/blank? %))
                        (take 3)))))
    :machine-readable
    (= 200 status) :other-200
    :else (keyword (str "http-" status))))

(defn- probe-one [{:keys [domain url]}]
  (-> (js/fetch url #js {:signal (js/AbortSignal.timeout 20000)
                         :headers #js {"User-Agent" "yui/1 (funnel-coverage gate)"}})
      (.then (fn [resp]
               (-> (.text resp)
                   (.then (fn [body]
                            {:domain domain :url url
                             :kind (classify (.-status resp)
                                             (.. resp -headers (get "content-type"))
                                             body)
                             :detail (str (.-status resp) " "
                                          (.. resp -headers (get "content-type")))})))))
      (.catch (fn [e]
                {:domain domain :url url :kind :probe-failed
                 :detail (.-message e)}))))

(defn- report! [results]
  (doseq [r results]
    (println (str (:domain r) "  " (name (:kind r))
                  (when (:detail r) (str "  (" (:detail r) ")"))
                  "  " (:url r))))
  (let [readable (count (filter #(= :machine-readable (:kind %)) results))
        spa (count (filter #(= :spa-fallback (:kind %)) results))
        none (count (filter #(= :no-endpoint (:kind %)) results))
        failed (count (filter #(= :probe-failed (:kind %)) results))]
    (println "")
    (println (str "machine-readable funnel doors: " readable "/5"
                  " (spa-fallback 200-but-not-data: " spa
                  ", no-endpoint 404: " none
                  ", probe-failed: " failed ")"))
    (println (str "yui-h4 work list (doors needing a real funnel surface): "
                  (str/join ", "
                            (map :domain (filter #(contains? #{:spa-fallback :no-endpoint :probe-failed}
                                                             (:kind %))
                                                 results)))))
    (if (>= readable min-doors)
      (do (println (str "yui-funnel-coverage: OK — " readable " >= --min " min-doors))
          (js/process.exit 0))
      (do (println (str "yui-funnel-coverage: FAIL — " readable " < --min " min-doors))
          (js/process.exit 1)))))

(-> (js/Promise.all (into-array (map probe-one doors)))
    (.then #(report! (js->clj % :keywordize-keys true)))
    (.catch (fn [e]
              (println (str "yui-funnel-coverage: probe infrastructure failed: " (.-message e)))
              (js/process.exit 2))))
