(ns ongakuka.catalog
  (:require [clojure.edn :as edn]
            #?(:clj [clojure.java.io :as io])))

(def default-resource "catalog.edn")

(defn load-catalog
  ([] (load-catalog default-resource))
  ([resource-name]
   #?(:cljs
      (throw (ex-info "load-catalog needs host-provided EDN on cljs"
                      {:resource resource-name}))
      :clj
   (if-let [res (io/resource resource-name)]
     (edn/read-string (slurp res))
     (throw (ex-info "catalog resource not found" {:resource resource-name}))))))

(defn assets
  ([] (assets (load-catalog)))
  ([catalog]
   (:ongakuka.catalog/assets catalog)))

(defn by-id
  ([asset-id] (by-id (load-catalog) asset-id))
  ([catalog asset-id]
   (first (filter #(= asset-id (:asset/id %)) (assets catalog)))))

(defn source-assets
  ([source] (source-assets (load-catalog) source))
  ([catalog source]
   (filter #(= source (:asset/source %)) (assets catalog))))
