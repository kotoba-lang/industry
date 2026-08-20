#!/usr/bin/env nbb
;; verify-wasm-toolchain-pin.cljs — amu が固定した Wasm toolchain の version と、
;; fleet のノードが実際に持っている version を突き合わせる。
;;
;; usage:
;;   nbb scripts/verify-wasm-toolchain-pin.cljs [--findings] [--root <dir>]
;;   nbb scripts/verify-wasm-toolchain-pin.cljs --self-test
;;
;; **なぜ要るか。** amu は `component-model-v1.edn` の
;; `[:spec-baseline :wasi :toolchain]` で toolchain を pin し、他の version を
;; 実行前に拒否する。fleet 側は `~/.gftd/wasm-pin/bin` に置いた binary で
;; その pin を満たしており、`amu-jvm-test` gate はそれに依存する。
;;
;; amu が pin を上げると、prefix の binary は古いまま残る。そのとき gate は
;; 赤くなるが、出るメッセージは `wasm-tools version is not pinned` で、
;; **repo の失敗のように読める**。実際には「ノードの工具が古い」であって、
;; ADR-2608198600 で直したのと同じ、ノードについての判定が repo の判定として
;; 記録される形である。この detector はそれを、gate が誤った顔で赤くなる前に、
;; 正しい名前で言う。
;;
;; **fleet gate にはできない。** gate は 1 repo の tree しか配らず、
;; amu は west 管理で `orgs/` に在る。突き合わせるのは
;; `orgs/kotoba-lang/amu` の資源と `scripts/fleet-ci/nodes.edn` の 2 つで、
;; どちらも 1 つの tree には揃わない。

(ns verify-wasm-toolchain-pin
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            ["node:fs" :as fs]
            ["node:path" :as path]))

(def argv (vec *command-line-args*))
(def flags (set argv))
(def findings? (contains? flags "--findings"))

(defn- flag [nm default]
  (let [i (.indexOf argv nm)] (if (neg? i) default (nth argv (inc i) default))))

(def root (.resolve path (flag "--root" ".")))
;; **正本は amu ではなく `kotoba-lang/kotoba-component`。**
;; amu の test は `read-resource "kotoba/lang/component-model-v1.edn"` で読むが
;; それは classpath 資源で、実体は依存側に在る。最初この detector は amu の下を
;; 見に行き、**そこに無かった** —— そして refusal 経路が働いて「clean」ではなく
;; 「読めないので答えない」と言った。もし不在を 0 件と数えていたら、間違った
;; repo を指したまま「drift 無し」を報告し続けていた。
(def authority-path
  (.join path root "orgs" "kotoba-lang" "kotoba-component"
         "resources" "kotoba" "lang" "component-model-v1.edn"))
(def nodes-path (.join path root "scripts" "fleet-ci" "nodes.edn"))

(defn- read-edn [p]
  (try (edn/read-string (str (fs/readFileSync p "utf8"))) (catch :default _ nil)))

(defn finding! [sev k detail]
  (when findings? (println (str "FINDING\t" sev "\t" k "\t" detail))))

;; ---------------------------------------------------------------------------
;; pure core

(defn pinned-toolchain
  "amu の契約が要求する {:wasm-tools \"…\" :wac-cli \"…\"}。無ければ nil。"
  [contract]
  (get-in contract [:spec-baseline :wasi :toolchain]))

(defn node-rows
  "cap や version を持ちうるノードだけ。到達不能・operator は対象外。"
  [nodes]
  (filterv #(and (:reachable? %) (not= :operator (:role %))) (:nodes nodes)))

(defn drift
  "pin と 1 ノードの記録を突き合わせる -> {:kind … } または nil。

  **cap を持つノードだけを見るのでは足りない。** cap を失ったノード
  （= 工具が消えた／version が変わって wac が外れた等）は、まさに
  報告したい状態でありながら cap の集合から消える —— 『壊れたものが
  観測対象から外れる』のは、この session が繰り返し直してきた形。"
  [pin node]
  (let [{:keys [wasm-tools-version wac-version caps host]} node
        capped? (contains? (set caps) :wasm-tools)
        want-wt (:wasm-tools pin)
        want-wac (:wac-cli pin)]
    (cond
      ;; 工具をひとつも記録していないノードは対象外（そもそも入れていない）。
      (and (nil? wasm-tools-version) (nil? wac-version)) nil

      (and wasm-tools-version want-wt (not= wasm-tools-version want-wt))
      {:kind :wasm-tools-version :host host :have wasm-tools-version :want want-wt
       :capped? capped?}

      (and wac-version want-wac (not= wac-version want-wac))
      {:kind :wac-version :host host :have wac-version :want want-wac
       :capped? capped?}

      :else nil)))

;; ---------------------------------------------------------------------------

(defn- self-test! []
  (let [fails (atom 0)
        check (fn [ok? label] (when-not ok? (swap! fails inc) (println "FAIL" label)))
        pin {:wasm-tools "1.243.0" :wac-cli "0.10.1"}
        node (fn [m] (merge {:host "n" :reachable? true} m))]
    (check (= pin (pinned-toolchain
                   {:spec-baseline {:wasi {:toolchain pin}}}))
           "the pin is read from the contract path amu actually uses")
    (check (nil? (pinned-toolchain {:spec-baseline {:wasi {}}}))
           "a contract without a toolchain yields nil, which the caller turns
            into a refusal rather than into a clean report")
    (check (nil? (drift pin (node {:wasm-tools-version "1.243.0"
                                   :wac-version "0.10.1"
                                   :caps #{:wasm-tools}})))
           "a node on the pin is not a finding")
    (check (= :wasm-tools-version
              (:kind (drift pin (node {:wasm-tools-version "1.257.1"
                                       :wac-version "0.10.1"
                                       :caps #{:wasm-tools}}))))
           "a newer wasm-tools is drift — brew's copy is exactly this")
    (check (= :wac-version
              (:kind (drift pin (node {:wasm-tools-version "1.243.0"
                                       :wac-version "0.9.0"
                                       :caps #{:wasm-tools}}))))
           "and so is a wac that moved")
    (check (some? (drift pin (node {:wasm-tools-version "1.257.1" :caps #{}})))
           "a node WITHOUT the cap is still reported: losing the cap is the
            symptom, and looking only at capped nodes would hide the very
            machines that broke")
    (check (nil? (drift pin (node {:caps #{:jvm}})))
           "a node with no wasm tooling recorded is not drift — it never had any")
    (if (zero? @fails)
      (println "verify-wasm-toolchain-pin: self-test OK (7 cases)")
      (do (println "verify-wasm-toolchain-pin: self-test FAILED" @fails)
          (js/process.exit 1)))))

(defn -main []
  (when (contains? flags "--self-test") (self-test!) (js/process.exit 0))
  (let [contract (read-edn authority-path)
        nodes (read-edn nodes-path)]
    ;; **答えられないことを clean と区別する。** amu が checkout されていない
    ;; 環境（west は 4,000 repo を管理しており、手元に無いのが普通）で
    ;; 「drift 0 件」と言えば、それは測って問題が無かったのと同じ顔になる。
    (when-not contract
      (println (str "could not read the pin authority at " authority-path
                    " — refusing to report clean (is orgs/kotoba-lang/kotoba-component checked out?)"))
      (js/process.exit 2))
    (when-not nodes
      (println (str "could not read " nodes-path " — refusing to report clean"))
      (js/process.exit 2))
    (let [pin (pinned-toolchain contract)]
      (when-not pin
        (println "the contract has no [:spec-baseline :wasi :toolchain] — refusing to report clean")
        (js/process.exit 2))
      (let [rows (node-rows nodes)
            drifts (keep #(drift pin %) rows)]
        (doseq [d drifts]
          (finding! (if (:capped? d) "high" "medium")
                    (str (name (:kind d)) ":" (:host d))
                    (str "has " (:have d) ", amu pins " (:want d)
                         (if (:capped? d)
                           " — this node carries :wasm-tools, so amu-jvm-test will be placed here and go red about the NODE"
                           " — this node no longer carries :wasm-tools"))))
        (println (str "SCANNED\t" (count rows) "\treachable node(s) against pin "
                      (pr-str pin)))
        (println (str "drift: " (count drifts)))
        (js/process.exit (if (seq drifts) 1 0))))))

(-main)
