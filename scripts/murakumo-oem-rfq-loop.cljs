#!/usr/bin/env nbb
;; scripts/murakumo-oem-rfq-loop.cljs — 受信 tick のあとに、未処理の業者返信が
;; あるときだけ claude を起こす。候補が無ければモデルは呼ばない。
;;
;; usage:
;;   nbb scripts/murakumo-oem-rfq-loop.cljs
;;
;; launchd: scripts/cloud.itonami.bot.murakumo-oem-rfq.plist (4h)

(require '[clojure.edn :as edn]
         '[clojure.string :as str])

(def fs (js/require "node:fs"))
(def path (js/require "node:path"))
(def os (js/require "node:os"))
(def cp (js/require "node:child_process"))
(def process (js/require "node:process"))

(def home (.homedir os))
(def ledger-dir (or (.-OEM_RFQ_HOME js/process.env)
                    (.join path home ".itonami" "murakumo-oem-rfq")))
(def root (or (.-COM_JUNKAWASAKI_ROOT js/process.env)
              (.cwd process)))
(def this-dir
  (.dirname path (or (second (js->clj (.-argv process)))
                     (.cwd process))))
(def tick-path
  (let [sib (.join path this-dir "murakumo-oem-rfq-tick.cljs")
        local (.join path ledger-dir "tick.cljs")
        repo (.join path root "scripts" "murakumo-oem-rfq-tick.cljs")]
    (cond
      (.existsSync fs local) local
      (.existsSync fs sib) sib
      :else repo)))
(def ledger-path (.join path ledger-dir "loop.ledger.edn"))
(def nbb-bin (or (.-NBB_BIN js/process.env) "nbb"))
(def claude-bin (or (.-CLAUDE_BIN js/process.env) "claude"))

(defn- now-iso [] (.toISOString (js/Date.)))

(defn- read-ledger []
  (if-not (.existsSync fs ledger-path)
    []
    (->> (str/split (.readFileSync fs ledger-path "utf8") #"\n")
         (remove str/blank?)
         (keep (fn [line]
                 (try (edn/read-string line) (catch :default _ nil))))
         vec)))

(defn- woken-ids [rows]
  (into #{} (keep :id (filter #(= :woke (:kind %)) rows))))

(defn- append! [m]
  (.mkdirSync fs (.dirname path ledger-path) #js {:recursive true})
  (.appendFileSync fs ledger-path (str (pr-str m) "\n") "utf8"))

(defn- run-tick []
  (let [out (.execFileSync cp nbb-bin
                           #js [tick-path "--json"]
                           #js {:encoding "utf8"
                                :cwd root
                                :env js/process.env
                                :timeout 60000})
        lines (->> (str/split (str out) #"\n")
                   (remove str/blank?))
        json-line (last lines)]
    (js->clj (js/JSON.parse json-line) :keywordize-keys true)))

(defn- wake! [cand]
  (let [prompt (str "/murakumo-oem-rfq\n\n"
                    "Handle exactly one inbound vendor reply. Do not PO. "
                    "Do not send money. Do not claim Node 24 or MK-1 is on sale. "
                    "Extract white-label / MOQ / lead days / who buys GPU. "
                    "Escalate contract/NDA/phone-if-unknown to owner.\n\n"
                    "Candidate:\n"
                    (js/JSON.stringify (clj->js cand) nil 2))
        started (js/Date.now)
        r (.spawnSync cp claude-bin
                      #js ["-p" prompt]
                      #js {:encoding "utf8"
                           :cwd root
                           :env js/process.env
                           :timeout 900000})
        code (or (.-status r) 1)]
    (append! {:kind :woke
              :id (:id cand)
              :from (:from cand)
              :subject (:subject cand)
              :as-of (now-iso)
              :exit code
              :ms (- (js/Date.now) started)})
    code))

(try
  (let [tick (run-tick)
        unanswered (boolean (:unanswered tick))
        cands (vec (:candidates tick))
        seen (woken-ids (read-ledger))
        fresh (vec (remove #(contains? seen (:id %)) cands))]
    (cond
      unanswered
      (do (append! {:kind :skip :why :unanswered :reason (:reason tick)
                    :as-of (now-iso)})
          (println "SKIP unanswered" (:reason tick))
          (.exit process 0))

      (empty? cands)
      (do (append! {:kind :skip :why :no-candidates
                    :scanned (:scanned tick) :as-of (now-iso)})
          (println "SKIP no vendor replies scanned=" (:scanned tick))
          (.exit process 0))

      (empty? fresh)
      (do (append! {:kind :skip :why :already-woke
                    :ids (mapv :id cands) :as-of (now-iso)})
          (println "SKIP already woke" (pr-str (mapv :id cands)))
          (.exit process 0))

      :else
      (let [one (first fresh)]
        (println "WAKE" (:id one) (:from one))
        (.exit process (wake! one)))))
  (catch :default e
    (append! {:kind :failed :error (str e) :as-of (now-iso)})
    (println "FAIL" (str e))
    (.exit process 1)))
