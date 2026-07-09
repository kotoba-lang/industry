(ns faceid.adapters.edn-local-auth
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [faceid.adapters.local-auth :as local-auth]))

(defn- read-devices [file]
  (if (.exists (io/file file))
    (edn/read-string (slurp file))
    {}))

(defn- write-devices! [file devices]
  (let [f (io/file file)]
    (when-let [parent (.getParentFile f)]
      (.mkdirs parent))
    (spit f (pr-str devices))
    devices))

(defn put-device! [file subject device]
  (write-devices! file (assoc (read-devices file) subject device)))

(defn edn-local-auth [file]
  (reify local-auth/ILocalAuthentication
    (evaluate-policy! [_ payload opts]
      (let [device (get (read-devices file) (:subject payload))]
        (cond
          (nil? device) {:error :device-not-enrolled}
          (and (:challenge device)
               (not= (:challenge device) (:challenge payload))) {:error :challenge-mismatch}
          :else {:device-id (:device-id device)
                 :credential-id (:credential-id device)
                 :provider :edn-local-auth/faceid
                 :evidence-ref (or (:evidence-ref device)
                                   (str "edn://faceid/" (:id payload)))
                 :attested-at (:attested-at opts)})))))
