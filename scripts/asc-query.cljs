;; App Store Connect API 読み取り専用クエリ。
;; 既存の ~/.appstoreconnect/private_keys/AuthKey_<KEY_ID>.p8 で ES256 JWT を作る。
;; 使い方: nbb asc-query.cljs <path>   例: /v1/apps?limit=200
(ns asc-query
  (:require [clojure.string :as str]
            ["crypto" :as crypto]
            ["fs" :as fs]
            ["os" :as os]
            ["path" :as path]))

(def key-id "62BW4Q57AB")
(def issuer-id "69a6de81-326a-47e3-e053-5b8c7c11a4d1")
(def key-path (path/join (os/homedir) ".appstoreconnect" "private_keys"
                         (str "AuthKey_" key-id ".p8")))

(defn b64url [s]
  (-> s (str/replace #"\+" "-") (str/replace #"/" "_") (str/replace #"=+$" "")))

(defn b64url-json [m]
  (b64url (.toString (js/Buffer.from (js/JSON.stringify (clj->js m))) "base64")))

(defn mint-jwt []
  (let [now (js/Math.floor (/ (js/Date.now) 1000))
        header {:alg "ES256" :kid key-id :typ "JWT"}
        claims {:iss issuer-id :iat now :exp (+ now 600) :aud "appstoreconnect-v1"}
        signing-input (str (b64url-json header) "." (b64url-json claims))
        signer (doto (crypto/createSign "SHA256") (.update signing-input))
        ;; JOSE は DER ではなく r||s の固定長。dsaEncoding を明示しないと検証に落ちる。
        sig (.sign signer #js {:key (fs/readFileSync key-path "utf8")
                               :dsaEncoding "ieee-p1363"}
                   "base64")]
    (str signing-input "." (b64url sig))))

(defn -main [& args]
  (let [p (or (first args) "/v1/apps?limit=200")
        url (str "https://api.appstoreconnect.apple.com" p)]
    (-> (js/fetch url #js {:headers #js {"Authorization" (str "Bearer " (mint-jwt))}})
        (.then (fn [r] (.then (.text r) (fn [t] [(.-status r) t]))))
        (.then (fn [[status t]]
                 (println "HTTP" status)
                 (println t)))
        (.catch (fn [e] (println "ERROR" (.-message e)))))))

(apply -main *command-line-args*)
