(ns babashka.curl (:require [scripts.nbb-compat :as compat]))
(defn get [url & {:as opts}]
  (let [args (cond-> ["curl" "-sS" "-L" url]
               (:headers opts) (into (mapcat (fn [[k v]] ["-H" (str k ": " v)]) (:headers opts))))
        r (apply compat/sh args)]
    {:status (:exit r) :body (:out r) :headers {}}))

(defn post [url & {:as opts}]
  (let [args (cond-> ["curl" "-sS" "-L" "-X" "POST" url]
               (:headers opts) (into (mapcat (fn [[k v]] ["-H" (str k ": " v)]) (:headers opts)))
               (:body opts) (into ["--data" (:body opts)]))
        r (apply compat/sh args)]
    {:status (:exit r) :body (:out r) :headers {}}))
