#!/usr/bin/env nbb
(require '[cljs.reader :as reader]
         '[clojure.set :as set]
         '[clojure.string :as str])

(def fs (js/require "node:fs"))
(def root (.cwd js/process))
(defn text [relative] (.readFileSync fs (str root "/" relative) "utf8"))
(defn fail! [message data]
  (println "HOST PARITY FAIL:" message (pr-str data))
  (.exit js/process 1))

(let [catalog (reader/read-string
               (text "orgs/kotoba-lang/kotoba-lang/lang/host-parity.edn"))
      required (:required-imports catalog)
      contract-source (text "orgs/kotoba-lang/kototama/src/kototama/contract.cljc")
      browser-source (text "orgs/kotoba-lang/wasm-webcomponent/src/actor-host.js")
      contract-ids (set (map (comp keyword second)
                             (re-seq #":import/id\s+:([a-z0-9-]+)" contract-source)))
      browser-ids (set (map (comp keyword second)
                            (re-seq #"\{\s*id:\s*'([a-z0-9-]+)'\s*,\s*category:" browser-source)))
      override-ids (set (keys (:imports catalog)))
      profile (:browser-profile catalog)
      categories (map #(get profile % #{})
                      [:required :intentional-native-boundary
                       :deferred-provider-components :deferred-host-injection])
      classified (apply set/union #{} categories)
      category-total (reduce + (map count categories))
      statuses (get-in catalog [:acceptance :browser-linkable-statuses])
      default-row (:unlisted-import-default catalog)
      browser-linkable (set (filter (fn [id]
                                      (contains? statuses
                                                 (:browser (merge default-row
                                                                  (get-in catalog [:imports id])))))
                                    required))]
  (when-not (= required contract-ids browser-ids)
    (fail! "declared import sets drifted"
           {:language-only (set/difference required contract-ids browser-ids)
            :contract-only (set/difference contract-ids required)
            :browser-only (set/difference browser-ids required)}))
  (when-not (set/subset? override-ids required)
    (fail! "host-parity overrides contain unknown imports"
           (set/difference override-ids required)))
  (when-not (and (= required classified) (= (count required) category-total))
    (fail! "browser profile is not a complete disjoint partition"
           {:required-count (count required)
            :classified-count (count classified)
            :category-total category-total
            :unclassified (set/difference required classified)}))
  (let [implemented-fields (set (map second (re-seq #"fns\.([a-z0-9_]+)\s*=" browser-source)))
        implemented-ids (set (map #(keyword (str/replace % "_" "-")) implemented-fields))
        node-only #{:transport-connect :tls-open :tls-server-end-point
                    :transport-write :transport-read :transport-close
                    :scram-sha256 :pg-cancel-register :pg-cancel :kagi-sign}
        browser-callable (apply disj implemented-ids :llm-infer node-only)]
    (when-not (= browser-linkable browser-callable)
      (fail! "browser matrix disagrees with implemented host functions"
             {:matrix-only (set/difference browser-linkable browser-callable)
              :implementation-only (set/difference browser-callable browser-linkable)})))
  (when-not (= 10 (count browser-linkable))
    (fail! "browser linkability evidence changed without qualification"
           {:count (count browser-linkable) :imports browser-linkable}))
  (println "KOTOBA HOST PARITY PASS:"
           (count required) "declared imports;"
           (count browser-linkable) "browser-linkable; contract and JS sets agree"))
