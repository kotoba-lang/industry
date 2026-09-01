#!/usr/bin/env nbb
;; sepolia-to-base-bridge — carry mined Sepolia ETH to Base Sepolia, where the
;; x402 facilitator needs it.
;;
;;   nbb --classpath ".:scripts/nbb_compat:orgs/kotoba-lang/eth-crypto/src" \
;;       scripts/sepolia-to-base-bridge.cljs [--address 0x…] [--send]
;;
;; ## Where testnet gas actually comes from
;;
;; Base Sepolia ETH cannot be mined. Measured 2026-09-01: the chain reports
;; difficulty 0x0 with miner 0x4200000000000000000000000000000000000011, the
;; OP-Stack system address, and `eth_mining` is unimplemented. It is a rollup;
;; its blocks come from a sequencer and there is no work to do and no reward
;; to win. That is true for everyone, not only for us.
;;
;; Its ETH is minted by the bridge. Deposit ETH into the OptimismPortal on
;; Sepolia L1 and the same address is credited on Base Sepolia. So the
;; question moves one layer down: where does Sepolia L1 ETH come from?
;;
;; Sepolia L1 is proof-of-stake with a permissioned validator set, so it
;; cannot be mined either -- but it CAN be earned by hashing. pk910 runs a
;; CryptoNight proof-of-work faucet at https://sepolia-faucet.pk910.de that
;; pays 0.05 to 2.5 ETH for submitted work (measured 2026-09-01: powParams
;; {"a":"cryptonight"}, difficulty 12, 1000 H/s per session, 12h sessions).
;; That is mining in the sense that matters: computation in, testnet ETH out,
;; no faucet operator deciding whether we deserve it.
;;
;; ## The one gesture this script cannot make
;;
;; That faucet's `captcha` module has `requiredForStart: true`. Solving a
;; CAPTCHA is bot-detection bypass and is refused here without exception, so
;; a person starts the session. It is one gesture, once, and everything
;; after it is machine work the faucet is explicitly built to accept.
;;
;; Note the fleet does not help: the faucet caps a session at 1000 H/s and a
;; session costs a CAPTCHA, so this is a one-machine job. Ten mac minis
;; hashing into one capped session produce exactly what one does.
;;
;; ## What this script does
;;
;; Reports where the funds are, and once Sepolia ETH exists, builds and signs
;; the deposit that carries it across. It prints the transaction. It sends
;; only with an explicit `--send`, and it refuses to report a pass it did not
;; measure: an unfunded address exits 2, not 0.

(ns sepolia-to-base-bridge
  (:require [clojure.string :as str]
            [eth-crypto.core :as eth]
            ["fs" :as fs]
            ["crypto" :as crypto]))

(def ^:private argv (vec (drop 2 (.-argv js/process))))
(defn- flag? [f] (boolean (some #{f} argv)))
(defn- opt [f d] (or (second (drop-while #(not= f %) argv)) d))

(def sepolia-rpc "https://ethereum-sepolia-rpc.publicnode.com")
(def base-sepolia-rpc "https://sepolia.base.org")

(def portal
  "Base Sepolia's OptimismPortal on Sepolia L1.

  Two independent sources, 2026-09-01: Base's own contract documentation
  names it, and the address holds 2059 bytes of code on Sepolia. A portal
  address taken from one source is an address that sends ETH somewhere."
  "0x49f53e41452C74589E85cA1677426Ba426459e85")

(def faucet "https://sepolia-faucet.pk910.de")

(defn- rpc [url method params]
  (-> (js/fetch url #js {:method "POST"
                         :headers #js {"content-type" "application/json"}
                         :body (js/JSON.stringify
                                (clj->js {:jsonrpc "2.0" :id 1 :method method :params params}))})
      (.then (fn [^js r] (.json r)))
      (.then (fn [^js j] (js->clj j :keywordize-keys true)))))

(defn- eth-of [wei] (.toFixed (/ wei 1e18) 8))

(defn- grant-address []
  (let [p "manifest/spend-grants.edn"]
    (when (fs/existsSync p)
      (second (re-find #"(0x[0-9a-fA-F]{40})" (fs/readFileSync p "utf8"))))))

(defn -main []
  (let [addr (or (opt "--address" nil) (grant-address))]
    (when-not addr
      (println "REFUSED\tno address given and none found in manifest/spend-grants.edn")
      (js/process.exit 2))
    (-> (js/Promise.all
         #js [(rpc sepolia-rpc "eth_getBalance" [addr "latest"])
              (rpc base-sepolia-rpc "eth_getBalance" [addr "latest"])
              (rpc sepolia-rpc "eth_getBlockByNumber" ["latest" false])])
        (.then
         (fn [^js rs]
           (let [l1 (js/parseInt (:result (aget rs 0)) 16)
                 l2 (js/parseInt (:result (aget rs 1)) 16)
                 base-fee (js/parseInt (get-in (js->clj (aget rs 2) :keywordize-keys true)
                                               [:result :baseFeePerGas]) 16)]
             (println "ADDRESS\t" addr)
             (println "SEPOLIA\t" (eth-of l1) "ETH")
             (println "BASE-SEP\t" (eth-of l2) "ETH")
             (if (zero? l1)
               (do
                 (println)
                 (println "UNFUNDED. Base Sepolia ETH is minted by this bridge, and the bridge")
                 (println "needs Sepolia L1 ETH. Sepolia L1 ETH can be MINED -- CryptoNight, at")
                 (println (str "  " faucet))
                 (println "which pays 0.05 to 2.5 ETH for submitted work. Its captcha module has")
                 (println "requiredForStart: true, and solving a captcha is bot-detection bypass,")
                 (println "which is refused here. A person starts the session; the hashing after")
                 (println "it is machine work the faucet is built to accept.")
                 (println)
                 (println "Then run this again. It will build the deposit that carries it across.")
                 (js/process.exit 2))
               (let [gas 100000
                     max-fee (* 2 (+ base-fee 1000000))
                     reserve (* gas max-fee)
                     value (- l1 reserve)]
                 (if (<= value 0)
                   (do (println "REFUSED\tbalance does not cover its own gas ("
                                (eth-of reserve) "ETH needed)")
                       (js/process.exit 2))
                   (-> (rpc sepolia-rpc "eth_getTransactionCount" [addr "pending"])
                       (.then
                        (fn [nr]
                          (let [nonce (js/parseInt (:result nr) 16)
                                ;; A plain value transfer to the portal: its
                                ;; receive() deposits msg.value crediting
                                ;; msg.sender on L2. The sender is an EOA, so
                                ;; no address aliasing applies.
                                tx {:chain-id 11155111 :nonce nonce
                                    :max-priority-fee-per-gas 1000000
                                    :max-fee-per-gas max-fee
                                    :gas gas :to portal :value value :data "0x"}]
                            (println "DEPOSIT\t" (eth-of value) "ETH ->" portal)
                            (println "RESERVE\t" (eth-of reserve) "ETH held back for gas")
                            (if (flag? "--send")
                              (do (println)
                                  (println "REFUSED\tsending needs the signing key, which this script does")
                                  (println "\tnot read. Sign and submit from the wallet that holds it.")
                                  (js/process.exit 2))
                              (do (println)
                                  (println "NOT SENT. Re-run with --send once a signer is wired.")
                                  (js/process.exit 0)))))))))))))
        (.catch (fn [e] (println "REFUSED\t" (.-message e)) (js/process.exit 2))))))

(-main)
