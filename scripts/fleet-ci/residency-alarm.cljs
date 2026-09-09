#!/usr/bin/env nbb
;; residency-alarm.cljs — 常駐の drift を遷移時に 1 回だけ知らせる。
;;
;; `scripts/residency-collect.cljs --check` を呼んで exit code を見るだけ。
;; **判定をここに複製しない** —— 閾値と drift の定義は collector 側にあり、
;; 2 箇所にあって片方だけ直る、を起こさない。
;;
;; **なぜ fleet gate ではないのか。** drift の片側は `launchctl list`、つまり
;; *この機械で実際に何が動いているか*である。fleet gate はノードへ tree を配って
;; 走らせるので、operator の launchd を見られない。したがって operator 側の常駐。
;;
;; **遷移の扱いは `transition-alarm` にある**（2026-08-16 に抽出）。この file が
;; 「3 本目を書くときに抽出する」と書き残していた、その 3 本目が capacity-alarm
;; だった。ここに残るのは *何を検査するか* だけ。
;;
;; **exit code はこの alarm 固有**: 不健全なら 1 で終わる。hayari-alarm は常に 0 で
;; 終わり、その理由（通知役が赤いと「通知が壊れている」と「対象が壊れている」が
;; 混ざる）の方が筋は良いが、ここを黙って変えると gate や plist が exit を見ていた
;; 場合に挙動が変わる。変えるなら別の変更として、見てから変える。
;;
;;   nbb --classpath scripts/fleet-ci scripts/fleet-ci/residency-alarm.cljs \
;;       [--root <superproject>] [--notify false] [--state <f>]

(ns residency-alarm
  (:require ["node:child_process" :as cp]
            ["node:path" :as path]
            [clojure.string :as str]
            [transition-alarm :as alarm]))

(def args (vec *command-line-args*))
(defn- flag [n d] (let [i (.indexOf args n)] (if (neg? i) d (nth args (inc i)))))

(def root (flag "--root" (path/join (or js/process.env.HOME "/tmp") "github" "com-junkawasaki")))
(def notify? (not= "false" (flag "--notify" "true")))
(def state-f (flag "--state" (path/join (or js/process.env.HOME "/tmp") ".itonami" "residency-alarm-state.edn")))

(defn check []
  (let [r (cp/spawnSync "nbb" #js["scripts/residency-collect.cljs" root "--check"]
                        #js {:cwd root :encoding "utf8" :maxBuffer (* 8 1024 1024)})
        rc (aget r "status")
        out (str (aget r "stdout") (aget r "stderr"))]
    (println out)
    {:healthy? (zero? rc)
     ;; FAIL 行が「何が drift したか」を1行で持っている。
     :why (or (first (filter #(str/starts-with? % "FAIL") (str/split-lines out)))
              "drift あり")
     :persist {:rc rc}}))

(defn -main [& _]
  (let [{:keys [healthy?]}
        (alarm/run! {:label "residency-alarm"
                     :check check
                     :state-file state-f
                     :notify? notify?
                     :messages {:broke ["常駐 drift" (fn [{:keys [why]}] why)]
                                :recovered ["常駐 drift が解消しました"
                                            (fn [_] "宣言と実機が一致しています")]}})]
    (set! (.-exitCode js/process) (if healthy? 0 1))))

(apply -main args)
