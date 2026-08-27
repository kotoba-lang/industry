#!/usr/bin/env nbb
(ns tender-debug
  (:require ["node:fs" :as fs]))

(defn wasm-numeric-result [value]
  (if (= "bigint" (js/typeof value)) (js/Number value) (js/Number value)))

(defn node-sample []
  (let [wasm (fs/readFileSync "orgs/kotoba-lang/kototama/test/kototama/fixtures/kotoba-compiled-fact.wasm")]
    (-> (.instantiate js/WebAssembly wasm (clj->js {}))
        (.then (fn [result]
                 (let [guest-main (aget (.-exports (.-instance result)) "main")]
                   (println "v" (wasm-numeric-result (guest-main))))))
        (.catch (fn [e] (println "err" (.-message e)))))))

(node-sample)
