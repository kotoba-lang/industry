#!/usr/bin/env nbb
;; scripts/audit-manifest-lexicon-drift.cljs のテスト。
;;
;;   nbb scripts/test-audit-manifest-lexicon-drift.cljs
;;
;; `scripts/test_audit_manifest_lexicon_drift.py` の 5 本を移し、Kotoba kernel
;; についての 2 本を足した。監査本体は subprocess として動かす -- そうすると
;; 実際に走る入口をそのまま検査でき、ライブラリ用に切り出し直さずに済む。

(ns test-audit-manifest-lexicon-drift
  (:require [clojure.string :as str]
            ["node:child_process" :as cp]
            ["node:crypto" :as crypto]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
))

(def ^:private root (.cwd js/process))
(def ^:private failures (atom 0))
(def ^:private ran (atom 0))

(defn- check! [name ok? detail]
  (swap! ran inc)
  (if ok?
    (println (str "PASS " name))
    (do (swap! failures inc)
        (println (str "FAIL " name (when detail (str " -- " detail)))))))

(defn- tmpdir [] (fs/mkdtempSync (path/join (os/tmpdir) "lexicon-drift-")))
(defn- spit! [p text]
  (fs/mkdirSync (path/dirname p) #js {:recursive true})
  (fs/writeFileSync p text))

(defn- run-audit
  "監査を subprocess で走らせ、`{:out :status}` を返す。"
  [super-root west-root & flags]
  (let [r (cp/spawnSync "nbb"
                        (clj->js (into ["scripts/audit-manifest-lexicon-drift.cljs"] flags))
                        #js {:cwd root :encoding "utf8"
                             :env (js/Object.assign
                                   #js {} (.-env js/process)
                                   #js {"COM_JUNKAWASAKI_SUPER_ROOT" super-root
                                        "COM_JUNKAWASAKI_WEST_ROOT" west-root})})]
    {:out (str (.-stdout r) (.-stderr r)) :status (.-status r)}))

(defn- line-value [out label]
  (some->> (str/split-lines out)
           (some #(when (str/starts-with? % (str label ": ")) %))
           (#(subs % (+ 2 (count label))))))

;; ---------------------------------------------------------------------------
;; Kotoba kernel -- 判定の正本
;; ---------------------------------------------------------------------------

(def ^:private kernel-source (path/join root "scripts" "kotoba" "lexicon_nsid_core.kotoba"))
(def ^:private kernel-artifact (path/join root "scripts" "kotoba" "lexicon_nsid_core.mjs"))

(defn- sha256-file [p]
  (-> (crypto/createHash "sha256") (.update (fs/readFileSync p)) (.digest "hex")))

;; 20 件。Python の正規表現
;;   ^[a-z][a-zA-Z0-9-]*(?:\.[a-zA-Z][a-zA-Z0-9-]*){2,}$
;; が 2026-08-25 に返した答えを pin してある。kernel を書き換えたときに、
;; 「動く」ではなく「同じ判定をする」で落ちるようにするため。
(def ^:private nsid-corpus
  [["com.etzhayyim.yamabiko.trainsetManufactureRecord" true]
   ["com.etzhayyim.apps.etzhayyim.priorityConformanceAttestation" true]
   ["app.bsky.feed.post" true]
   ["a.b.c" true]
   ["a.b" false]
   ["com.Example.thing" true]
   ["Com.example.thing" false]
   ["1com.example.thing" false]
   ["com..example.thing" false]
   ["com.example.thing." false]
   [".com.example.thing" false]
   ["com.example.-thing" false]
   ["com.example.thing-" true]
   ["com.example.th1ng" true]
   ["com.example.9thing" false]
   ["com.example.thing.sub" true]
   ["com-x.example.thing" true]
   ["" false]
   ["com.example" false]])

(defn- kernel-tests [m]
  (check! "artifact-was-compiled-from-the-checked-in-source"
          (= (sha256-file kernel-source) (.-sourceDigest (.-kotobaArtifact m)))
          (str "source " (subs (sha256-file kernel-source) 0 12)
               " vs artifact " (subs (str (.-sourceDigest (.-kotobaArtifact m))) 0 12)
               " -- recompile with `amu compile ... --target js`"))
  (check! "kernel-needs-no-capabilities"
          (zero? (count (or (.-requiredCapabilities (.-kotobaArtifact m)) #js [])))
          "the NSID judgement is kotoba/pure and must stay so")
  (let [bad (keep (fn [[s want]]
                    ;; instance ごとの fuel なので 1 呼び出し 1 instance。
                    (let [inst ((.-instantiateKotoba m) #js {})
                          got (= 1 (js/Number ((aget inst "nsid?") s)))]
                      (when (not= got want) [s want got])))
                  nsid-corpus)]
    (check! "kernel-agrees-with-the-regex-it-replaced"
            (empty? bad)
            (pr-str (vec bad)))))

;; ---------------------------------------------------------------------------
;; 監査本体 -- Python 版から移した 5 本
;; ---------------------------------------------------------------------------

(defn- test-exact-west-paths []
  (let [d (tmpdir)]
    (spit! (path/join d "manifest" "west.yml")
           (str "projects:\n"
                "  - name: x\n    path: orgs/etzhayyim/com-etzhayyim-yamabiko\n"
                "  - name: bad\n    path: orgs/etzhayyim/root/20-actors/yamabiko\n"))
    ;; 入れ子の actor パスは拾わない。west が生成する flat path だけが対象。
    (let [{:keys [out]} (run-audit d d)]
      (check! "exact-west-paths-only"
              (= "0" (line-value out "West actor manifests scanned"))
              (str "manifest が無い木なので 0 のはず: " (pr-str (line-value out "West actor manifests scanned")))))))

(defn- yamabiko-tree []
  (let [d (tmpdir)
        owner (path/join d "orgs" "etzhayyim" "com-etzhayyim-yamabiko")]
    (spit! (path/join owner "manifest.edn")
           "{:actor/lexicons [\"com.etzhayyim.yamabiko.trainsetManufactureRecord\"]}")
    (spit! (path/join owner "wire" "contracts" "lexicons" "trainsetManufactureRecord.json")
           (js/JSON.stringify #js {"lexicon" 1
                                   "id" "com.etzhayyim.yamabiko.trainsetManufactureRecord"
                                   "defs" #js {}}))
    (spit! (path/join d "manifest" "west.yml")
           (str "projects:\n  - name: com-etzhayyim-yamabiko\n"
                "    path: orgs/etzhayyim/com-etzhayyim-yamabiko\n"))
    d))

(defn- test-canonical-edn-contract-is-not-an-orphan []
  (let [d (yamabiko-tree)
        {:keys [out]} (run-audit d d)]
    (check! "canonical-edn-contract-is-not-an-orphan"
            (and (= "1" (line-value out "West actor manifests scanned"))
                 (= "1" (line-value out "Lexicons declared"))
                 (= "0" (line-value out "Undeclared orphan lexicons"))
                 (= "0" (line-value out "Manifest declarations missing wire JSON")))
            out)))

(defn- test-eavt-vector-id-is-authoritative []
  (let [d (yamabiko-tree)
        owner (path/join d "orgs" "etzhayyim" "com-etzhayyim-yamabiko")]
    ;; identity を EAVT ベクタで持つ 2 本目を隣に置く。宣言されていないので
    ;; orphan として出るが、名前は stem ではなく `*/id` の値でなければならない。
    (spit! (path/join owner "wire" "contracts" "lexicons" "classification.json")
           (js/JSON.stringify
            #js [#js {"classification/id" "com.etzhayyim.yamabiko.classificationRenamed"}]))
    (let [{:keys [out]} (run-audit d d)]
      (check! "eavt-vector-id-is-authoritative"
              (str/includes? out "ORPHAN com.etzhayyim.yamabiko.classificationRenamed")
              out))))

(defn- test-ambiguous-eavt-falls-back []
  (let [d (yamabiko-tree)
        owner (path/join d "orgs" "etzhayyim" "com-etzhayyim-yamabiko")]
    ;; id が 2 つあるベクタは曖昧なので、stem から作った名前に落ちる。
    (spit! (path/join owner "wire" "contracts" "lexicons" "mixed.json")
           (js/JSON.stringify #js [#js {"a/id" "com.etzhayyim.a.one"}
                                   #js {"b/id" "com.etzhayyim.b.two"}]))
    (let [{:keys [out]} (run-audit d d)]
      (check! "ambiguous-eavt-falls-back-to-stem"
              (str/includes? out "ORPHAN com.etzhayyim.yamabiko.mixed")
              out))))

(defn- test-root-owned-registry []
  (let [{:keys [out]} (run-audit root root)]
    (check! "root-owned-registry-is-read"
            (not= "0" (line-value out "Root-owned contracts"))
            (str "実際の木でルート登録簿が読めていない: " (pr-str out)))))

(defn- test-strict-exit []
  (let [d (yamabiko-tree)
        owner (path/join d "orgs" "etzhayyim" "com-etzhayyim-yamabiko")]
    (spit! (path/join owner "manifest.edn")
           "{:actor/lexicons [\"com.etzhayyim.yamabiko.trainsetManufactureRecord\"\n
                              \"com.etzhayyim.yamabiko.thereIsNoWireJsonForThis\"]}")
    (let [loose (run-audit d d)
          strict (run-audit d d "--strict")]
      ;; --strict が無いときは報告して 0、あるときは 1。飛ばしたと合格したが
      ;; exit code で区別できること。
      (check! "strict-turns-a-finding-into-a-nonzero-exit"
              (and (= 0 (:status loose)) (= 1 (:status strict)))
              (str "loose=" (:status loose) " strict=" (:status strict))))))

;; ---------------------------------------------------------------------------

(-> (js/import (str "file://" kernel-artifact))
    (.then (fn [m]
             (kernel-tests m)
             (test-exact-west-paths)
             (test-canonical-edn-contract-is-not-an-orphan)
             (test-eavt-vector-id-is-authoritative)
             (test-ambiguous-eavt-falls-back)
             (test-root-owned-registry)
             (test-strict-exit)
             (println)
             (println (str "ran " @ran " checks, " @failures " failed"))
             ;; 0 件走ったのに緑、を出さない。
             (when (zero? @ran)
               (println "REFUSING: no check ran. That is not the same as nothing failing.")
               (set! (.-exitCode js/process) 2))
             (when (pos? @failures) (set! (.-exitCode js/process) 1))))
    (.catch (fn [e]
              (println "REFUSING: kernel artifact could not be loaded --" (.-message e))
              (set! (.-exitCode js/process) 2))))
