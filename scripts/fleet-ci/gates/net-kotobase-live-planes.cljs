#!/usr/bin/env nbb
;; net-kotobase-live-planes.cljs — kotobase.net の**本番の面**が実際に答えるか。
;;
;; **なぜ要るか。** 2026-08-11、`/api/*` の全 8 経路と `/pins` が 502 を返し、
;; `graph.sparql` が 404 を返していた。その間 `GET /health` は `ok: true` を返し、
;; **502 を返しているその 8 経路を `client_api_paths` として並べていた**。
;; hermetic gate は tree を検査するので production を見ておらず、
;; `/health` を見ている監視は緑のままで、誰も気づかなかった。
;; 詳細は net-kotobase の docs/adr/2608110600。
;;
;; **なぜ hermetic gate では足りないか。** デプロイ成果物は commit 済みの
;; バンドル（kotobase-api-gateway/js/kotobase-worker.js）なので、
;; **ソースが緑でも production がそれを動かしているとは限らない**。
;; tree の検査と「今 production が答えるか」は別の問い。
;;
;; **egress について。** 初版の hermetic gate と ADR-2608036600 は
;; 「kotobase.net への egress がノードに無い」を理由に live smoke を除外していた。
;; これは 2026-07-26 に zebulun 1 台で測った値の一般化で、**2026-08-11 に
;; judah / levi / simeon で測り直したところ 3 台とも kotobase.net = 200 /
;; sparql.kotobase.net = 200**。CLAUDE.md の「ノードの外向き HTTPS は実測して使う。
;; 定数で持たない」に従い、この gate は起動時に自分で到達性を測り、
;; **届かなければ合格ではなく exit 92（skip）で正直に降りる**。
;;
;; **credential を持たない。** fleet-ci 不変条件 3（鍵はノードに配らない）を
;; 守るため、この gate は**その場で捨て鍵を生成して CACAO を自己署名する**。
;; kotobase は self-sovereign auth なので、新しい did:key はそれだけで p2p
;; テナントとして成立する（= 権限昇格にならない / 既存テナントに触れない）。
;; npm 依存も持たない: ed25519 は node:crypto、CBOR は下の最小実装。
;;
;; usage: nbb net-kotobase-live-planes.cljs [<dir>] [--base https://kotobase.net]
;;   <dir> は使わない（fleet が第1引数に tree を渡す規約に合わせて受けるだけ）。
(ns fleet-ci.gates.net-kotobase-live-planes
  (:require ["node:crypto" :as crypto]
            [clojure.string :as str]))

(def argv (vec *command-line-args*))
(defn- flag [name default]
  (let [i (.indexOf argv name)]
    (if (neg? i) default (get argv (inc i) default))))
(def base (str/replace (flag "--base" "https://kotobase.net") #"/+$" ""))

;; ── bytes ────────────────────────────────────────────────────────────────────
(defn- buf [& xs] (js/Buffer.concat (clj->js (mapv #(js/Buffer.from (clj->js %)) xs))))
(defn- utf8 [s] (js/Buffer.from s "utf8"))

;; ── ed25519 via node:crypto (no npm) ─────────────────────────────────────────
;; A raw 32-byte seed becomes a usable key by wrapping it in the fixed PKCS8
;; prefix for Ed25519; the public key comes back inside the fixed SPKI prefix.
(def ^:private pkcs8-prefix (js/Buffer.from "302e020100300506032b657004220420" "hex"))
(def ^:private spki-prefix-len 12)

(defn- private-key [seed]
  (crypto/createPrivateKey #js {:key (buf pkcs8-prefix seed) :format "der" :type "pkcs8"}))

(defn- public-bytes [priv]
  (let [der (.export (crypto/createPublicKey priv) #js {:format "der" :type "spki"})]
    (.subarray der spki-prefix-len)))

(defn- sign [priv message] (crypto/sign nil (utf8 message) priv))

;; ── base58btc (did:key) ──────────────────────────────────────────────────────
(def ^:private b58 "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz")
(defn- base58btc [bytes]
  (let [n (.-length bytes)]
    (loop [digits [0] i 0]
      (if (< i n)
        (let [carried (reduce (fn [{:keys [out carry]} d]
                                (let [v (+ (* d 256) carry)]
                                  {:out (conj out (mod v 58)) :carry (quot v 58)}))
                              {:out [] :carry (aget bytes i)} digits)
              ds (loop [out (:out carried) c (:carry carried)]
                   (if (pos? c) (recur (conj out (mod c 58)) (quot c 58)) out))]
          (recur ds (inc i)))
        (let [leading (count (take-while zero? (array-seq bytes)))]
          (str "z" (apply str (repeat leading "1"))
               (apply str (map #(nth b58 %) (reverse digits)))))))))

(defn- did-key [pub]
  ;; 0xed 0x01 = the Ed25519 multicodec prefix a did:key carries.
  (str "did:key:" (base58btc (buf (js/Buffer.from #js [0xed 0x01]) pub))))

;; ── minimal deterministic CBOR (dag-cbor subset: text strings, maps, arrays) ─
;; dag-cbor orders map keys by length first, then bytewise, and the decoder on
;; the other side is strict about it. Only the shapes a CACAO uses are handled.
(defn- cbor-head [major n]
  (let [m (bit-shift-left major 5)]
    (cond
      (< n 24) (js/Buffer.from #js [(bit-or m n)])
      (< n 0x100) (js/Buffer.from #js [(bit-or m 24) n])
      (< n 0x10000) (js/Buffer.from #js [(bit-or m 25) (bit-shift-right n 8) (bit-and n 0xff)])
      :else (throw (js/Error. "cbor: value too large for this gate")))))

(declare cbor)

(defn- cbor-text [s]
  (let [b (utf8 s)] (buf (cbor-head 3 (.-length b)) b)))

(defn- key-order [ks]
  (sort (fn [a b] (let [la (.-length (utf8 a)) lb (.-length (utf8 b))]
                    (if (not= la lb) (- la lb) (compare a b))))
        ks))

(defn- cbor [v]
  (cond
    (string? v) (cbor-text v)
    (vector? v) (apply buf (cbor-head 4 (count v)) (map cbor v))
    (map? v) (let [ks (key-order (map name (keys v)))]
               (apply buf (cbor-head 5 (count ks))
                      (mapcat (fn [k] [(cbor-text k) (cbor (get v (keyword k)))]) ks)))
    :else (throw (js/Error. (str "cbor: unsupported value " (pr-str v))))))

;; ── CACAO ────────────────────────────────────────────────────────────────────
(defn- iso-second [ms]
  (str/replace (.toISOString (js/Date. ms)) #"\.\d{3}Z$" "Z"))

(defn- siwe [p]
  (str/join "\n"
            (concat [(str (:domain p) " wants you to sign in with your Ethereum account:")
                     (last (str/split (:iss p) #":")) ""
                     (str "URI: " (:aud p))
                     (str "Version: " (:version p))
                     "Chain ID: 1"
                     (str "Nonce: " (:nonce p))
                     (str "Issued At: " (:iat p))
                     (str "Expiration Time: " (:exp p))
                     "Resources:"]
                    (map #(str "- " %) (:resources p)))))

(defn- mint
  "→ #js{authorization, x-kotoba-did}. `opts` may override :domain/:aud/:ttl-ms."
  [{:keys [seed did resources domain aud ttl-ms]}]
  (let [now (js/Date.now)
        p {:iss did
           :aud (or aud "did:web:kotobase.net")
           :iat (iso-second now)
           :exp (iso-second (+ now (or ttl-ms 300000)))
           :nonce (crypto/randomUUID)
           :domain (or domain "kotobase.net")
           :version "1"
           :resources (vec resources)}
        sig (sign (private-key seed) (siwe p))
        cacao {:h {:t "caip122"}
               :p p
               :s {:t "EdDSA" :s (.toString sig "base64url")}}]
    #js {"authorization" (str "CACAO " (.toString (cbor cacao) "base64"))
         "x-kotoba-did" did}))

;; ── checks ───────────────────────────────────────────────────────────────────
(defonce failures (atom []))
(defonce checked (atom 0))

(defn- fail! [label detail]
  (swap! failures conj (str label " — " detail))
  (println (str "  FAIL " label " :: " detail)))

(defn- pass! [label detail]
  (swap! checked inc)
  (println (str "  ok   " label (when detail (str " :: " detail)))))

(defn- fetch-text [url opts]
  (-> (js/fetch url (clj->js (merge {:signal (js/AbortSignal.timeout 20000)} opts)))
      (.then (fn [r] (-> (.text r) (.then (fn [t] {:status (.-status r) :body t})))))
      (.catch (fn [e] {:status 0 :body (or (.-message e) "network error")}))))

(defn- expect!
  "One check: `pred` over {:status :body}."
  [label url opts want pred]
  (-> (fetch-text url opts)
      (.then (fn [{:keys [status body] :as res}]
               (let [snippet (subs body 0 (min 160 (count body)))]
                 (cond
                   ;; The exact shape of the 2026-08-11 outage: an unhandled JS
                   ;; TypeError reaching the client. Called out separately so the
                   ;; failure names itself instead of just "wrong status".
                   (str/includes? body "is not a function")
                   (fail! label (str "raw JS TypeError leaked: " snippet))
                   (pred res) (pass! label (str "HTTP " status))
                   :else (fail! label (str "expected " want ", got HTTP " status " " snippet)))
                 res)))))

(defn- status= [n] (fn [{:keys [status]}] (= status n)))

(defn -main []
  (println (str "net-kotobase-live-planes :: " base))
  (let [seed (crypto/randomBytes 32)
        priv (private-key seed)
        did (did-key (public-bytes priv))
        pin-res ["kotoba://can/kotobase:pin" (str "kotoba://graph/" did)]
        auth (fn [& {:as opts}]
               (mint (merge {:seed seed :did did :resources pin-res} opts)))
        json-post (fn [headers body]
                    {:method "POST"
                     :headers (js/Object.assign #js {"content-type" "application/json"} headers)
                     :body (js/JSON.stringify (clj->js body))})]
    (println (str "throwaway tenant: " did))
    (-> (fetch-text (str base "/health") nil)
        (.then
         (fn [{:keys [status]}]
           (if (not= status 200)
             (do (println (str "SKIP: " base " unreachable from this node (HTTP " status
                               ") — reporting skip, not pass"))
                 (js/process.exit 92))
             (js/Promise.resolve))))
        ;; 1. health tells the truth about its own scope
        (.then #(expect! "health/scope-is-stated" (str base "/health") nil "200 + scope"
                         (fn [{:keys [status body]}]
                           (and (= status 200) (str/includes? body "\"scope\":\"edge\"")))))
        (.then #(expect! "health/deep-probes-dependency" (str base "/health?deep=1") nil
                         "200 + graph_database ok"
                         (fn [{:keys [status body]}]
                           (and (= status 200) (str/includes? body "graph_database")))))
        ;; 2. the Client API answers — the plane that was wholly 502
        (.then #(expect! "client-api/db" (str base "/api/db")
                         (json-post (doto (auth) (aset "x-datomic-db-name" "fleet-probe")) {})
                         "200" (status= 200)))
        (.then #(expect! "client-api/q" (str base "/api/q")
                         (json-post (doto (auth) (aset "x-datomic-db-name" "fleet-probe"))
                                    {:query "[:find ?e :where [?e ?a ?v]]" :args []})
                         "200" (status= 200)))
        (.then #(expect! "client-api/datoms" (str base "/api/datoms")
                         (json-post (doto (auth) (aset "x-datomic-db-name" "fleet-probe"))
                                    {:index "eavt" :limit 1})
                         "200" (status= 200)))
        ;; 3. PSA answers — must not be a 502 for a tenant holding nothing
        (.then #(expect! "psa/list" (str base "/pins")
                         {:method "GET" :headers (auth)} "200" (status= 200)))
        ;; 4. account plane
        (.then #(expect! "account/status"
                         (str base "/xrpc/ai.gftd.apps.kotobase.accountStatus")
                         (json-post (auth) {}) "200" (status= 200)))
        ;; 5. auth boundaries stay closed
        (.then #(expect! "auth/foreign-domain-refused" (str base "/pins")
                         {:method "GET" :headers (auth :domain "fleet-probe.invalid"
                                                       :aud "https://fleet-probe.invalid")}
                         "401" (status= 401)))
        (.then #(expect! "auth/expired-refused" (str base "/pins")
                         {:method "GET" :headers (auth :ttl-ms -60000)}
                         "401" (status= 401)))
        (.then #(expect! "auth/unauthenticated-refused" (str base "/pins")
                         {:method "GET"} "401" (status= 401)))
        ;; 6. the read-only protocol surfaces
        (.then (fn [_]
                 (js/Promise.all
                  (clj->js (for [h ["sparql" "cypher" "gremlin" "graphql" "graphdb"]]
                             (expect! (str "surface/" h)
                                      (str "https://" h ".kotobase.net/health") nil
                                      "200" (status= 200)))))))
        (.then
         (fn [_]
           (let [fs @failures]
             (println)
             (if (seq fs)
               (do (println (str "FLEET-CI: " (count fs) " of " (+ (count fs) @checked)
                                 " live checks failed"))
                   (doseq [f fs] (println (str "  - " f)))
                   (println "FLEET-CI-EXIT: 1")
                   (js/process.exit 1))
               (do (println (str "OK — " @checked " live checks passed against " base))
                   (println "FLEET-CI-EXIT: 0"))))))
        (.catch (fn [e]
                  (println (str "FLEET-CI: gate itself failed: " (or (.-stack e) (.-message e))))
                  (println "FLEET-CI-EXIT: 91")
                  (js/process.exit 91))))))

(-main)
