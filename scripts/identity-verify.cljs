#!/usr/bin/env nbb
;; biscuit の委譲を検証し、authority に判定させ、identity.startup で起動可否を
;; 決める verifier（ADR-2608197300 resume point 2）。判定器は増やさない —
;; ここは biscuit.token/verify → biscuit.kotoba/->delegated →
;; authority.chain/authorize → identity.startup/resolve-state を順に呼ぶだけの
;; 薄い口で、決定はすべて既存の library が持つ。
;;
;;   echo '{:token {...} :root-public-key [...] :requested "kotoba://graph/acme"}' \
;;     | nbb --classpath "$CP" scripts/identity-verify.cljs
;;
;; 入力 key:
;;   :token           この workspace の EDN token（`biscuit/edn-v1`）
;;   :token-edn-b64   同じものを base64 した文字列。**呼び出し側が攻撃者由来の
;;                    token を EDN 文書へ差し込まずに済む**ための口 — base64 の
;;                    charset は EDN の引用符を含まないので、境界がここで閉じる
;;   :token-b64       外部実装が鋳造した本物の biscuit v3 wire token
;;   :root-public-key 32 byte vector か 64 文字 hex
;;   :requested       覆われているか訊きたい resource（省略可）
;;   :now :kinds :identity-known? :format  （:format :json で JSON 出力）
;;   :holder          省略可。**省略が既定** — holder は token から導出する。
;;                    渡した場合は導出値と一致しなければ deny（名乗りを検証に
;;                    使わせない）
;;
;; JVM は要らない。classpath は scripts/identity-startup-e2e.cljs と同じ:
;;   orgs/kotoba-lang/org-biscuitsec/src:orgs/kotoba-lang/org-biscuitsec/test:
;;   orgs/kotoba-lang/authority/src:orgs/kotoba-lang/org-w3-did/src:
;;   orgs/kotoba-lang/identity/src
;; に **orgs/kotoba-lang/dev-protobuf/src** を足したもの。
;; （test/ が要るのは org-biscuitsec が crypto を持たない設計で、実 Ed25519 が
;;   test-only の注入だから。dev-protobuf が要るのは `biscuit.wire` が
;;   `protobuf.wire` を読むから — e2e script には無い1本で、外部実装が鋳造した
;;   本物の biscuit v3 token を読む `:token-b64` 経路のためだけに要る。）
;;
;; ## exit code は 3 つある
;;
;;   0  allowed   — 検証が通り、要求 scope が委譲に覆われている
;;   1  denied    — 検証はできた。その上で拒否した。**token が壊れている場合も
;;                  ここ** — 読めない token は token であって、それを裁くのは
;;                  この verifier の仕事である
;;   3  undecided — **答えられなかった**。壊れているのが token ではなく *こちら*
;;                  のとき（classpath が足りない / root 公開鍵が読めない /
;;                  stdin が来ない）
;;
;; 3 が別なのは ADR-2608136000 のため。「測れなかった」を「測って問題が無かった」
;; と同じ値で返すと、沈黙が緑として蓄積する。呼び出し側は 1 と 3 を必ず区別する
;; こと — 3 で fail-open してはならない。
(ns identity-verify
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [biscuit.ed25519 :as e]
            [biscuit.token :as bt]
            [biscuit.wire :as wire]
            [biscuit.kotoba :as bk]
            [did.core :as did]
            [authority.grant :as grant]
            [authority.chain :as chain]
            [identity.startup :as startup]))

(def ^:private default-kinds #{:graph-read :graph-write})

(def ^:private out-format
  "How to print, held aside so the refusal paths can honour it too.

  A caller that asked for JSON and got EDN back on the refusal path cannot
  read WHY it was refused, and — worse — cannot tell a refusal from a crash.
  The exit code alone does not settle that: nbb exits 1 when it fails to load
  a namespace, which is the same 1 this script uses for `denied`. So the body
  is the real answer and it has to be in the shape that was asked for."
  (atom :edn))

(defn- emit! [m]
  (println (if (= :json @out-format)
             (js/JSON.stringify
              (clj->js (into {} (map (fn [[k v]]
                                       [(str (namespace k) "/" (name k)) v]))
                             m)))
             (pr-str m))))

(defn- die!
  "Refuse to answer. Never exit 0 or 1 from here — see the header.

  Reserved for a broken VERIFIER. A token this cannot read is not a reason to
  use it: an unparseable credential is a rejected credential, and answering
  `undecided` would make every malformed token look like an outage to the
  caller — 503 where 401 was the truth."
  [reason detail]
  (emit! {:identity.verify/decision :undecided
          :identity.verify/reason reason
          :identity.verify/detail detail})
  (js/process.exit 3))

(defn- deny!
  "Refuse the token. The check ran; this is its answer."
  [reason detail]
  (emit! {:identity.verify/decision :denied
          :identity.verify/reason reason
          :identity.verify/detail detail})
  (js/process.exit 1))

(defn- read-stdin []
  (try (.readFileSync (js/require "fs") 0 "utf8")
       (catch :default err (die! :stdin-unreadable (str err)))))

(defn- hex->bytes [s]
  (vec (for [i (range 0 (count s) 2)]
         (js/parseInt (subs s i (+ i 2)) 16))))

(defn- ->key-bytes
  "A public key as bytes, however it arrived. A key we cannot read is
  undecidable, not invalid: refusing it as a bad signature would blame the
  token for the caller's encoding."
  [k]
  (cond
    (vector? k) (vec k)
    (and (string? k) (re-matches #"(?i)[0-9a-f]{64}" k)) (hex->bytes k)
    :else nil))

(defn- b64->string [s]
  (.toString (.from (.-Buffer (js/require "buffer")) s "base64") "utf8"))

(defn- b64->bytes [s]
  (let [buf (.from (.-Buffer (js/require "buffer")) s "base64")]
    (vec (array-seq buf))))

;; ── the four questions, each answered by its existing owner ─────────────

(defn- verified-token
  "Whatever arrived, as a token the rest of the pipeline can read.

  `:token` is this workspace's EDN model (`biscuit/edn-v1`). `:token-b64` is a
  real biscuit v3 wire token from another implementation — `biscuit.wire` can
  READ those and cannot write them, so a deployment that needs interop mints
  elsewhere and verifies here."
  [{:keys [token token-edn-b64 token-b64]} root-key]
  (cond
    token-edn-b64
    (let [t (try (edn/read-string (b64->string token-edn-b64))
                 (catch :default err (deny! :token-undecodable (str err))))
          _ (when-not (map? t) (deny! :token-not-a-map (pr-str (type t))))
          {:keys [ok? reason index]} (bt/verify t root-key e/verify-fn)]
      {:ok? ok? :reason reason :index index :token t :form :edn-b64})

    token
    (let [{:keys [ok? reason index]} (bt/verify token root-key e/verify-fn)]
      {:ok? ok? :reason reason :index index :token token :form :edn})

    token-b64
    (let [decoded (try (wire/decode-token (b64->bytes token-b64))
                       (catch :default err (deny! :token-undecodable (str err))))
          {:keys [ok? reason index]} (wire/verify decoded root-key e/verify-fn)]
      {:ok? ok? :reason reason :index index :token decoded :form :wire})

    :else (die! :no-token
                "input has none of :token, :token-edn-b64, :token-b64")))

(defn- token-holder
  "Who holds this token, according to the token.

  The last block names the key that may attenuate further, so that key is the
  holder. Deriving it here rather than accepting a `:holder` argument is the
  difference between authenticating a caller and asking them who they are:
  a gate that takes the principal from its input has no principal at all.

  Both token shapes carry it under a different key -- the EDN model calls it
  `:block/next-public-key`, the wire decoder `:next-key`."
  [token]
  (let [blocks (or (:biscuit/blocks token) (:blocks token))
        key (some-> (last blocks) (as-> b (or (:block/next-public-key b)
                                              (:next-key b))))]
    (when (seq key) (did/public-key->did-key (vec key)))))

(defn- decide [input]
  (let [root-key (or (->key-bytes (:root-public-key input))
                     (die! :root-key-unreadable
                           "expected 32-byte vector or 64-char hex"))
        kinds (or (some-> (:kinds input) set) default-kinds)
        now (:now input)
        requested (:requested input)
        {:keys [ok? reason index token form]} (verified-token input root-key)
        holder (token-holder token)
        claimed (:holder input)]
    (if-not ok?
      ;; A signature that does not check out IS an answer. Deny, do not refuse.
      {:identity.verify/decision :denied
       :identity.verify/reason (or reason :signature-mismatch)
       :identity.verify/block index
       :identity.verify/token-form form}
      (if (and claimed holder (not= claimed holder))
        ;; The caller named somebody the token does not. Deny -- this is an
        ;; answer, not a failure to answer.
        {:identity.verify/decision :denied
         :identity.verify/reason :holder-mismatch
         :identity.verify/holder holder
         :identity.verify/token-form form}
        (let [{:keys [grants] :grant/keys [rejected]} (bk/->delegated token kinds)
            chain (mapv #(grant/grant {:scopes (:grant/resources %)
                                       :expires (:grant/expires %)
                                       :holder holder})
                        grants)
            allowed? (when requested
                       (:authority/allowed?
                        (chain/authorize {:chain chain :requested requested
                                          :holder holder :now now})))
            state (startup/resolve-state
                   {:device-did holder :grants chain :now now
                    :identity-known? (:identity-known? input)})]
        (merge
         {:identity.verify/decision (cond (nil? requested) :verified
                                          allowed? :allowed
                                          :else :denied)
          :identity.verify/token-form form
          :identity.verify/blocks (:blocks (bt/verify token root-key e/verify-fn))
          :identity.verify/holder holder
          :identity.verify/requested requested
          ;; What the token actually carries after attenuation, and what was
          ;; thrown out. `:rejected` is not noise: a kind outside the closed
          ;; set is refused by name rather than ignored, and a caller that
          ;; drops this line cannot tell a narrow token from a foreign one.
          :identity.verify/grants (mapv #(select-keys % [:grant/kind
                                                         :grant/resources
                                                         :grant/expires]) grants)
          :identity.verify/rejected (vec rejected)}
         (select-keys state [:identity.startup/state
                             :identity.startup/serve
                             :identity.startup/reason
                             :identity.startup/ask])))))))

(defn -main []
  (let [raw (read-stdin)
        input (if (str/blank? raw)
                (die! :empty-input "nothing on stdin")
                (try (edn/read-string raw)
                     (catch :default err (die! :input-unreadable (str err)))))
        _ (when-not (map? input) (die! :input-not-a-map (pr-str (type input))))
        _ (reset! out-format (if (= :json (:format input)) :json :edn))
        out (decide input)]
    ;; JSON is for a caller in another language -- the Hermes provider plugin
    ;; is Python and must not carry an EDN reader to read a yes/no. `emit!`
    ;; rewrites the keys rather than handing them to `clj->js`, which drops
    ;; the namespace: `:identity.verify/reason` and `:identity.startup/reason`
    ;; both become "reason" and one silently overwrites the other. Two
    ;; different answers under one name is how a denial reads as a live
    ;; delegation.
    (emit! out)
    (js/process.exit (if (= :denied (:identity.verify/decision out)) 1 0))))

(-main)
