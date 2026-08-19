#!/usr/bin/env nbb
;; retry-unreachable.cljs — 「機械に届かなかった」だけを理由に赤で固定された
;; 台帳エントリを剥がし、次の tick に判定し直させる。
;;
;; ADR-2608198500 は *これから* の unreachable を台帳に書かないようにしたが、
;; **既に書かれたものは残る**。`work-changed?` は「記録済み sha ≠ 現 tip」でしか
;; 再実行しないので、残った赤はその repo に次の commit が来るまで剥がれない。
;; 実測 2026-08-19: 603 の :fail のうち 41 が unreachable 由来で、うち main が
;; 7 件（最古は 4 日前）。
;;
;; 何を剥がすか（3 条件すべて）:
;;   1. 台帳の outcome が :fail
;;   2. その gate+sha に extract sentinel を含む行が log にある
;;   3. その gate+sha に **sentinel を含まない判定行が 1 本も無い**
;;      —— 本物の失敗を一度でも出しているなら触らない。赤を消す道具は、
;;      消しすぎないことの方が難しい。
;;
;; 既定は dry-run。--execute で書く。
;;
;;   nbb scripts/fleet-ci/retry-unreachable.cljs
;;   nbb scripts/fleet-ci/retry-unreachable.cljs --execute
;;   nbb scripts/fleet-ci/retry-unreachable.cljs --self-test

(ns retry-unreachable
  (:require [cljs.reader :as reader]
            [clojure.string :as str]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]))

(def gftd (path/join (os/homedir) ".gftd"))
(def state-file (path/join gftd "fleet-ci-state.edn"))
(def log-file (path/join gftd "fleet-ci-tick.log"))
(def lock-file (path/join gftd "fleet-ci-tick.lock"))

;; tick.cljs と同じ綴り。ここが drift すると本 script は 0 件を報告し、
;; **何も詰まっていないのと見分けがつかない**ので self-test で固定する。
(def extract-fail-sentinel "FLEET-CI: extract failed on")

(defn- pid-alive? [pid]
  (try (js/process.kill pid 0) true (catch :default _ false)))

(defn- tick-running?
  "tick が走っている間に state を書き換えると、その tick の save-state! が
  こちらの書き込みを丸ごと巻き戻す（read-modify-write が競合する）。
  tick と同じ lock を見て、生きているなら何もしない。"
  []
  (and (fs/existsSync lock-file)
       (let [{:keys [pid]} (try (reader/read-string (str (fs/readFileSync lock-file "utf8")))
                                (catch :default _ {}))]
         (boolean (and pid (pid-alive? pid))))))

(defn verdict-lines
  "`log` の中の、この gate+sha についての判定行だけ。"
  [log id sha7]
  (let [needle (str "test-" id "-" sha7 "-")]
    (filterv #(str/includes? % needle) (str/split-lines log))))

(defn unreachable-only?
  "この gate+sha について記録された判定が、extract failure だけか。
  1 本でも sentinel の無い判定行があれば false —— 本物の失敗を見ている。"
  [lines]
  (and (seq lines)
       (every? #(str/includes? % extract-fail-sentinel) lines)))

(defn stuck-entries
  "剥がしてよいエントリ -> [[id entry] ...]"
  [state log]
  (vec (for [[k v] (:repos state)
             :let [id (name k)
                   sha7 (subs (str (:sha v)) 0 7)]
             :when (= :fail (:outcome v))
             :when (unreachable-only? (verdict-lines log id sha7))]
         [k v])))

;; ---------------------------------------------------------------------------

(defn- self-test! []
  (let [fails (atom 0)
        check (fn [ok? label]
                (when-not ok? (swap! fails inc) (println "FAIL" label)))
        log (str/join "\n"
                      ["2026-08-19T14:09:28Z   |    fail test-kagami-f95e627-murakumo-issachar exit 90 — ssh: connect to host 100.89.204.30 port 22: Operation timed out | FLEET-CI: extract failed on issachar"
                       "2026-08-19T13:49:25Z   |    pass test-amu-native-fuzz-bf82a4d-murakumo-simeon exit 0 — native-fuzz: 20000 passed"
                       "2026-08-19T12:54:27Z   |    fail test-amu-native-fuzz-ac3a117-murakumo-simeon exit 1 — SUMMARY: UndefinedBehaviorSanitizer"
                       "2026-08-19T11:00:00Z   |    fail test-amu-native-fuzz-ac3a117-murakumo-levi exit 90 — ssh timed out | FLEET-CI: extract failed on levi"])
        state {:repos {:kagami {:sha "f95e6274c292aace5fa130221547626a047f2f00" :outcome :fail}
                       :amu-native-fuzz {:sha "ac3a1178f2bc54bc99c8c0b2d89de043d4c7dbf4" :outcome :fail}
                       :amu-native-conformance {:sha "bf82a4d0595611201ace502279f16ce722205a86" :outcome :pass}}}
        stuck (set (map (comp name first) (stuck-entries state log)))]
    (check (contains? stuck "kagami")
           "an entry whose only verdict was an extract failure is drained")
    (check (not (contains? stuck "amu-native-fuzz"))
           "an entry that ALSO has a real failure at the same sha is left alone — a
            sanitizer report and a network blip both landed on ac3a117, and the
            sanitizer report is the one that means something")
    (check (not (contains? stuck "amu-native-conformance"))
           "a passing entry is never touched")
    (check (unreachable-only? [(str "x " extract-fail-sentinel " levi")])
           "the sentinel this script reads is the one tick emits")
    (check (not (unreachable-only? []))
           "no verdict line at all is not 'unreachable only' — absence of evidence
            is not evidence, and draining on it would re-run everything forever")
    (if (zero? @fails)
      (println "retry-unreachable: self-test OK (5 cases)")
      (do (println "retry-unreachable: self-test FAILED" @fails) (js/process.exit 1)))))

(defn -main [& args]
  (let [args (set args)]
    (when (contains? args "--self-test") (self-test!) (js/process.exit 0))
    (when-not (fs/existsSync state-file)
      (println "retry-unreachable: no state file at" state-file) (js/process.exit 2))
    (let [execute? (contains? args "--execute")
          state (reader/read-string (str (fs/readFileSync state-file "utf8")))
          log (str (fs/readFileSync log-file "utf8"))
          stuck (stuck-entries state log)
          total-fail (count (filter #(= :fail (:outcome (second %))) (:repos state)))]
      (println (str "retry-unreachable: " (count (:repos state)) " entries, "
                    total-fail " red, " (count stuck)
                    " red only because a node was unreachable"))
      (doseq [[k v] (sort-by (comp :at second) stuck)]
        (println "  " (:at v) (name k) (subs (str (:sha v)) 0 7)))
      (cond
        (empty? stuck) (println "retry-unreachable: nothing to drain")
        (not execute?) (println "retry-unreachable: dry-run — pass --execute to drain")
        (tick-running?)
        (do (println "retry-unreachable: a tick holds the lock — refusing to write"
                     "(its save-state! would roll this back). Try again between ticks.")
            (js/process.exit 3))
        :else
        (let [next-state (update state :repos #(apply dissoc % (map first stuck)))]
          (fs/writeFileSync state-file (str (pr-str next-state) "\n"))
          (println "retry-unreachable: drained" (count stuck)
                   "— the next tick judges these shas again"))))))

(apply -main *command-line-args*)
