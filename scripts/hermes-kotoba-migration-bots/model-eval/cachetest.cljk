#!/usr/bin/env nbb
(ns cachetest (:require [clojure.string :as str] ["node:child_process" :as cp]))
(def big (str/join "\n" (repeat 40 "Kotoba is a typed s-expression language; strings use string-length, string-substring, string=?, string-concat, string-from-i64.")))
(defn call [suffix]
  (let [body (js/JSON.stringify (clj->js {:model "murakumo-main" :max_tokens 8 :temperature 0
                                          :messages [{:role "user" :content (str big "\n\nReply with exactly: " suffix)}]}))
        t0 (js/Date.now)
        r (cp/spawnSync "curl" #js ["-sS" "--max-time" "300" "-X" "POST"
                                    "https://api.murakumo.cloud/v1/chat/completions"
                                    "-H" "Content-Type: application/json" "-d" body]
                        #js {:encoding "utf8" :maxBuffer 33554432})
        ms (- (js/Date.now) t0)
        j (js/JSON.parse (.-stdout r))]
    (println (str "suffix=" suffix
                  "  prompt=" (.. j -usage -prompt_tokens)
                  "  cached=" (or (some-> j .-usage .-prompt_tokens_details .-cached_tokens) "n/a")
                  "  completion=" (.. j -usage -completion_tokens)
                  "  ms=" ms))))
(call "A") (call "B") (call "C")
