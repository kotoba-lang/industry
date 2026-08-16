#!/usr/bin/env nbb
;; hayari の無人ループが止まったら、人に見えるところで 1 回だけ鳴らす。
;;
;; なぜ tick 側に置かないか: **死んだループは自分の死を報告できない。** 警報は
;; ループとは別のプロセスから出る必要がある。したがってこれは operator 側
;; （人が座っているマシン）に常駐し、ループが走るノードには置かない。
;;
;; 判定ロジックは複製しない —— gates/hayari-tick-alive.cljs を **そのまま呼んで
;; exit code を見る**。閾値が 2 箇所にあると、片方だけ直された時に静かに食い違う。
;;
;; 遷移の扱い（1 回だけ鳴らす・初回の未知を健全と読まない・警報自身は常に 0）は
;; `transition-alarm` にある（2026-08-16 抽出）。
;;
;;   nbb --classpath scripts/fleet-ci scripts/fleet-ci/hayari-alarm.cljs \
;;       [--root <superproject>] [--notify false] [--state <f>]

(ns hayari-alarm
  (:require ["node:child_process" :as cp]
            ["node:fs" :as fs]
            ["node:path" :as path]
            [clojure.string :as str]
            [transition-alarm :as alarm]))

(def args (vec *command-line-args*))
(defn- flag [n d] (let [i (.indexOf args n)] (if (neg? i) d (nth args (inc i)))))

(def root    (flag "--root" (path/join (or js/process.env.HOME "/tmp") "github" "com-junkawasaki")))
(def notify? (not= "false" (flag "--notify" "true")))
(def state-f (flag "--state" (path/join (or js/process.env.HOME "/tmp") ".gftd" "hayari-alarm-state.edn")))

(def gate   (path/join root "scripts" "fleet-ci" "gates" "hayari-tick-alive.cljs"))
(def target (path/join root "orgs" "cloud-itonami" "hayari"))

(defn check []
  (let [r (cp/spawnSync "nbb" (clj->js [gate target])
                        #js {:encoding "utf8" :timeout 120000})
        code (or (.-status r) 1)
        out (str/trim (str (or (.-stdout r) "") (or (.-stderr r) "")))]
    (println out)
    {:healthy? (zero? code)
     :why (first (str/split-lines out))
     :persist {:gate-exit code}}))

(defn -main [& _]
  (when-not (fs/existsSync gate)
    (println (str "hayari-alarm: gate が無い — " gate)) (js/process.exit 1))
  (alarm/run! {:label "hayari-alarm"
               :check check
               :state-file state-f
               :notify? notify?
               :messages {:broke ["hayari: ループが止まりました" (fn [{:keys [why]}] why)]
                          :recovered ["hayari: ループが回復しました"
                                      (fn [_] "backfill が再開しています")]}})
  ;; 警報自身は常に 0 で終わる。落ちるのは gate の役目であって、
  ;; 通知役が赤いと「通知が壊れている」と「監視対象が壊れている」が混ざる。
  (js/process.exit 0))

(apply -main args)
