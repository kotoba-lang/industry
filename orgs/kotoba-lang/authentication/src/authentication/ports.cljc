(ns authentication.ports)

(defprotocol IFactorVerifier
  (verify-factor! [port factor-request response]))

(defn verifier-map
  "Build a factor-type -> IFactorVerifier map."
  [& kvs]
  (apply hash-map kvs))
