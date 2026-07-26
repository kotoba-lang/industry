(ns scripts.west-pin-guard-policy)

(defn requires-verification?
  "Only a superproject push that changes manifest/west.yml needs pin
  verification. Child repositories have no blobs; unrelated pushes have equal
  blobs. This predicate is deliberately tiny so G-O cannot regress unnoticed."
  [head-blob main-blob]
  (and (not (clojure.string/blank? head-blob))
       (not (clojure.string/blank? main-blob))
       (not= head-blob main-blob)))
