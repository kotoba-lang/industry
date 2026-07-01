(ns identify.adapters.edn-resolver
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [identify.adapters.resolver :as resolver]))

(defn- read-index [file]
  (if (.exists (io/file file))
    (edn/read-string (slurp file))
    {}))

(defn- write-index! [file index]
  (let [f (io/file file)]
    (when-let [parent (.getParentFile f)]
      (.mkdirs parent))
    (spit f (pr-str index))
    index))

(defn identifier-key [identifier]
  [(:type identifier) (:value identifier)])

(defn put-candidates! [file identifier candidates]
  (write-index! file (assoc (read-index file) (identifier-key identifier) (vec candidates))))

(defn edn-resolver [file]
  (reify resolver/IResolverClient
    (resolve-identifier! [_ payload _opts]
      (get (read-index file) (identifier-key payload) []))))
