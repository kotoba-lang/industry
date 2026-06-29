(ns minimax-m2-modal.kotoba
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(defn spec []
  (edn/read-string (slurp (io/resource "kotoba.edn"))))

(defn model-spec [id]
  (let [models (:models (spec))
        k (keyword id)]
    (or (get models k)
        (throw (ex-info "Unknown model" {:model id :available (keys models)})))))

(defn vllm-args [id]
  (let [{:keys [defaults]} (spec)
        m (model-spec id)
        max-len (or (:vllm/max-model-len m) (:max-model-len defaults))]
    (concat ["vllm" "serve" (:model/id m)
             "--served-model-name" (:served-name m)
             "--trust-remote-code"
             "--tensor-parallel-size" (str (:vllm/tensor-parallel-size m))
             "--max-model-len" (str max-len)
             "--gpu-memory-utilization" (str (:gpu-memory-utilization defaults))
             "--tool-call-parser" (:vllm/tool-call-parser m)
             "--reasoning-parser" (:vllm/reasoning-parser m)
             "--api-key" (str "$" (:api-key-env m))
             "--host" (:host defaults)
             "--port" (str (:vllm-port defaults))]
            (:vllm/extra-args m))))

(defn shell-quote [s]
  (if (re-find #"[^\w@%+=:,./-]" s)
    (str "'" (str/replace s #"'" "'\\''") "'")
    s))

(defn print-command [id]
  (println (str/join " " (map shell-quote (vllm-args id)))))

(defn print-models []
  (doseq [[id m] (:models (spec))]
    (println (format "%-18s %s %s %s"
                     (name id)
                     (:model/id m)
                     (:modal/gpu m)
                     (:modal/volume m)))))

(defn -main [& [command model-id]]
  (case (or command "models")
    "models" (print-models)
    "vllm" (print-command (or model-id "minimax-m27"))
    (do
      (println "usage: clj -M:kotoba [models|vllm <model-id>]")
      (System/exit 2))))
