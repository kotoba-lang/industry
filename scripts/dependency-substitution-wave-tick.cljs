#!/usr/bin/env nbb
;; scripts/dependency-substitution-wave-tick.cljs — kotoba-lang 外部依存の
;; substitution-available 候補を**測る**（ADR-2608260200）。決定論。モデルを起こさない。
;;
;;   nbb --classpath ".:scripts/nbb_compat" scripts/dependency-substitution-wave-tick.cljs [--limit 4]
;;
;; 正本は skill `dependency-substitution-wave` と manifest/dependency-substitution.edn。
;; 1 波 = **1 coordinate** に揃えた repo 最大 `--limit` 本（既定 4）。
;;
;; exit 0 = 測れた（候補 0 でも 0）。exit 2 = 測れなかった（ADR-2608136000）。

(ns dependency-substitution-wave-tick
  (:require [clojure.string :as str]))

(def fs (js/require "fs"))
(def cp (js/require "child_process"))
(def os (js/require "os"))
(def path (js/require "path"))

(def home (.homedir os))
(def root (or (aget (.-env js/process) "COM_JUNKAWASAKI_ROOT")
              (str home "/github/com-junkawasaki")))
(def ledger-file (str home "/.itonami/dependency-substitution-wave-tick.ledger.edn"))
(def agent-branch "agent/dependency-substitution")

(def args (vec *command-line-args*))
(defn- arg [flag default]
  (if-let [i (first (keep-indexed #(when (= %2 flag) %1) args))]
    (js/parseInt (nth args (inc i)) 10)
    default))

(def limit (arg "--limit" 4))

;; 1 波は 1 coordinate。crypto / protocol 系を UI 波より先に。
(def coordinate-priority
  ["@noble/ciphers"
   "@noble/hashes"
   "hpke-js"
   "@ipld/dag-cbor"
   "datascript/datascript"
   "http-kit/http-kit"
   "org.clojure/data.json"
   "metosin/jsonista"
   "@atproto/api"
   "@atproto/common-web"
   "com.digitalpetri.modbus/modbus-tcp"
   "org.bouncycastle/bcpkix-jdk18on"
   "yaml"
   "three"
   "@pixiv/three-vrm"
   "reagent/reagent"
   "re-frame/re-frame"
   "ink"
   "zod"
   "@apollo/client"
   "@digital-go-jp/design-tokens"])

;; app-scap の radix / next 一括は別波。ここでは最後尾に回す（priority に無い coordinate は repo 数昇順）。
(def defer-patterns
  ["@radix-ui/"
   "next"
   "@hookform/"
   "react-day-picker"
   "react-resizable-panels"
   "vaul"
   "cmdk"])

;; GCM-SIV has no first-party equivalent — do not substitute aes.gcm (ADR-2608301100).
(def coordinate-deferred-repos
  {"@noble/ciphers" #{"kotobase-server"}})

;; Ladder step 3 landed: reference provider + parity test on disk (still imports npm).
(def reference-complete
  {"@noble/ciphers"
   {"noise" ["src/noise/provider/reference.cljs"
             "test/noise/provider/reference_aead_test.cljs"]
    "kagi" ["src/kagi/crypto/reference.cljs"
            "test/kagi/crypto/reference_aead_test.cljs"]}
   "@noble/hashes"
   {"noise" ["src/noise/hash/reference.cljs"
             "test/noise/provider/reference_hash_test.cljs"]
    "kagi" ["src/kagi/digest/reference.cljs"
            "test/kagi/digest/reference_test.cljs"]}})

(defn- deferred? [coord]
  (some #(str/starts-with? coord %) defer-patterns))

(defn- coordinate-deferred? [{:keys [coordinate name]}]
  (contains? (get coordinate-deferred-repos coordinate #{}) name))

(defn- reference-complete? [{:keys [coordinate name repo]}]
  (when-let [paths (get-in reference-complete [coordinate name])]
    (every? #(.existsSync fs (path.join root repo %)) paths)))

(defn- run-findings!
  "verify-dependency-substitution --findings を subprocess で走らせ、stdout を返す。
   nil = 測れなかった。"
  []
  (try
    (let [r (.spawnSync cp "nbb"
                        (clj->js ["--classpath" ".:scripts/nbb_compat"
                                   "scripts/verify-dependency-substitution.cljs"
                                   "--findings"])
                        #js {:cwd root :encoding "utf8" :timeout 600000})]
      (when (not= 0 (aget r "status")) nil)
      (str (aget r "stdout")))
    (catch :default _ nil)))

(defn- parse-findings [out]
  (letfn [(parse-line [line pattern mode]
            (when-let [m (re-find pattern line)]
              (let [[_ coord target repos-s] m
                    repos (->> (str/split repos-s #",\s*")
                               (map str/trim)
                               (remove str/blank?)
                               (remove #(str/starts-with? % "+"))
                               vec)]
                {:coordinate coord
                 :target (str/trim target)
                 :repos repos
                 :mode mode})))]
    (->> (str/split-lines (or out ""))
         (keep (fn [line]
                 (or (parse-line line
                                 #"FINDING\tinfo\tsubstitution-via-host:([^\t]+)\t-> ([^;]+) via host-provider \+ kotoba oracle; still imported by (.+)"
                                 :via-host)
                     (parse-line line
                                 #"FINDING\tinfo\tsubstitution-available:([^\t]+)\t-> ([^;]+); still imported by (.+)"
                                 :rewire))))
         (reduce (fn [m {:keys [coordinate target repos mode]}]
                   (update m coordinate
                           (fnil (fn [old]
                                   (merge old {:target target :mode mode
                                               :repos (vec (distinct (concat (:repos old) repos)))}))
                                 {:target target :repos repos :mode mode})))
                 {}))))

(defn- repo-path [name]
  (str "orgs/kotoba-lang/" name))

(defn- on-disk? [repo]
  (.existsSync fs (path.join root repo "deps.edn")))

(defn- has-linked-worktree? [{:keys [repo]}]
  (try
    (let [r (.spawnSync cp "git"
                        (clj->js ["-C" (path.join root repo) "worktree" "list" "--porcelain"])
                        #js {:encoding "utf8" :timeout 30000})]
      (if (not= 0 (aget r "status"))
        true
        (> (count (re-seq #"(?m)^worktree " (str (aget r "stdout")))) 1)))
    (catch :default _ true)))

(defn- remote-name [{:keys [repo]}]
  (try
    (let [org (second (str/split repo #"/"))
          r (.spawnSync cp "git" (clj->js ["-C" (path.join root repo) "remote"])
                        #js {:encoding "utf8" :timeout 30000})
          names (->> (str/split-lines (str (aget r "stdout"))) (remove str/blank?) set)]
      (cond (contains? names org) org
            (contains? names "origin") "origin"
            :else (first (sort names))))
    (catch :default _ nil)))

(defn- in-flight [c]
  (if (has-linked-worktree? c)
    :worktree
    (if-let [rem (remote-name c)]
      (try
        (let [r (.spawnSync cp "git"
                            (clj->js ["-C" (path.join root (:repo c))
                                      "ls-remote" "--heads" rem agent-branch])
                            #js {:encoding "utf8" :timeout 30000})]
          (cond (not= 0 (aget r "status")) :unmeasured
                (str/blank? (str (aget r "stdout"))) nil
                :else :branch))
        (catch :default _ :unmeasured))
      :unmeasured)))

(defn- coordinate-order [coord->info]
  (let [prio-idx (into {} (map-indexed (fn [i c] [c i]) coordinate-priority))
        ranked (sort-by (fn [[coord info]]
                          [(if (deferred? coord) 1 0)
                           (if (= :via-host (:mode info)) 0 1)
                           (get prio-idx coord 999)
                           (count (:repos info))
                           coord])
                        coord->info)]
    (map first ranked)))

(defn- wave-coordinates [coord->info]
  (vec (concat (filter #(contains? coord->info %) coordinate-priority)
               (coordinate-order (apply dissoc coord->info coordinate-priority)))))

(defn- pick-wave [coord->info]
  (some (fn [coord]
          (let [{:keys [target repos]} (get coord->info coord)
                open (->> repos
                          (map (fn [name]
                                 {:repo (repo-path name)
                                  :org "kotoba-lang"
                                  :name name
                                  :coordinate coord
                                  :target target}))
                          (filter #(on-disk? (:repo %)))
                          vec)]
            (when (seq open)
              {:coordinate coord :target target :open open})))
        (wave-coordinates coord->info)))

(defn -main []
  (let [out (run-findings!)]
    (when (nil? out)
      (println "REFUSING\tverify-dependency-substitution failed or non-zero exit")
      (println "SCANNED\t0")
      (js/process.exit 2))
    (when (str/includes? out "REFUSING")
      (println (first (filter #(str/starts-with? % "REFUSING") (str/split-lines out))))
      (println "SCANNED\t0")
      (js/process.exit 2))

    (let [coord->info (parse-findings out)
          scanned-m (re-find #"SCANNED\t(\d+)" out)
          scanned (if scanned-m (js/parseInt (nth scanned-m 1) 10) 0)
          pool (count coord->info)
          wave (pick-wave coord->info)
          coordinate (:coordinate wave)
          target (:target wave)
          ranked (if wave
                   (->> (:open wave) (sort-by :name) vec)
                   [])
          skipped-missing (when wave
                            (->> (:repos (get coord->info coordinate))
                                 (remove #(on-disk? (repo-path %)))
                                 vec))
          skipped (atom [])
          ref-complete (atom [])
          deferred (atom [])
          unmeasured (atom [])
          picked (->> ranked
                      (remove (fn [c]
                                (cond
                                  (coordinate-deferred? c)
                                  (do (swap! deferred conj (:repo c)) true)

                                  (reference-complete? c)
                                  (do (swap! ref-complete conj (:repo c)) true)

                                  :else
                                  (case (in-flight c)
                                    nil false
                                    :unmeasured (do (swap! unmeasured conj (:repo c)) true)
                                    (do (swap! skipped conj (:repo c)) true)))))
                      (take limit)
                      vec)
          rec {:at (.toISOString (js/Date.))
               :pool pool
               :scanned scanned
               :coordinate coordinate
               :target target
               :limit limit
               :missing-checkout (or skipped-missing [])
               :deferred-skipped @deferred
               :reference-complete-skipped @ref-complete
               :in-flight-skipped @skipped
               :unmeasured-skipped @unmeasured
               :candidates picked}]

      (println (str "SCANNED\t" scanned "\trepos"))
      (println (str "POOL\t" pool "\tsubstitution coordinates (via-host + available)"))
      (when coordinate
        (println (str "WAVE-COORDINATE\t" coordinate "\t->\t" target)))
      (println (str "MISSING-CHECKOUT\t" (count (or skipped-missing []))
                    (when (seq skipped-missing)
                      (str "\t" (str/join " " skipped-missing)))))
      (println (str "DEFERRED-SKIPPED\t" (count @deferred)
                    (when (seq @deferred) (str "\t" (str/join " " @deferred)))))
      (println (str "REFERENCE-COMPLETE-SKIPPED\t" (count @ref-complete)
                    (when (seq @ref-complete) (str "\t" (str/join " " @ref-complete)))))
      (println (str "IN-FLIGHT-SKIPPED\t" (count @skipped)
                    (when (seq @skipped) (str "\t" (str/join " " @skipped)))))
      (println (str "UNMEASURED-SKIPPED\t" (count @unmeasured)
                    (when (seq @unmeasured) (str "\t" (str/join " " @unmeasured)))))
      (println (str "CANDIDATES\t" (count picked)))
      (doseq [c picked]
        (println (str "  " (str/join "\t" [(:repo c) (:coordinate c) (:target c)]))))
      (try (.appendFileSync fs ledger-file (str (pr-str rec) "\n"))
           (catch :default e (println "ledger 追記失敗:" (str e))))
      (js/process.exit 0))))

(-main)
