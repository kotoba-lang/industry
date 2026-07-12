(ns babashka.curl (:require [scripts.nbb-compat :as compat]))

(defn- auth-args [{:keys [basic-auth]}]
  (when basic-auth (let [[user pass] basic-auth] ["-u" (str user ":" pass)])))

(defn- parse-response
  "curl の stdout は body の直後に改行区切りで %{http_code} を付けている
   (下の -w \"\\n%{http_code}\" 参照)。接続自体が失敗すると -w も出力されない
   ため、その場合は curl のプロセス終了コードにフォールバックする。"
  [r]
  (let [out (or (:out r) "")
        nl (.lastIndexOf out "\n")]
    (if (neg? nl)
      {:status (:exit r) :body out :headers {}}
      {:status (js/parseInt (subs out (inc nl)) 10) :body (subs out 0 nl) :headers {}})))

(defn get [url & {:as opts}]
  (let [args (cond-> ["curl" "-sS" "-L" "-w" "\n%{http_code}" url]
               (:headers opts) (into (mapcat (fn [[k v]] ["-H" (str k ": " v)]) (:headers opts)))
               (:basic-auth opts) (into (auth-args opts))
               (:raw-args opts) (into (:raw-args opts)))
        r (apply compat/sh args)]
    (parse-response r)))

(defn post [url & {:as opts}]
  (let [args (cond-> ["curl" "-sS" "-L" "-X" "POST" "-w" "\n%{http_code}" url]
               (:headers opts) (into (mapcat (fn [[k v]] ["-H" (str k ": " v)]) (:headers opts)))
               (:body opts) (into ["--data" (:body opts)])
               (:basic-auth opts) (into (auth-args opts))
               (:raw-args opts) (into (:raw-args opts)))
        r (apply compat/sh args)]
    (parse-response r)))
