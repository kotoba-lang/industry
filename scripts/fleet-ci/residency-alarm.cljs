#!/usr/bin/env nbb
;; residency-alarm.cljs — 常駐の drift を遷移時に 1 回だけ知らせる。
;;
;; `scripts/residency-collect.cljs --check` を呼んで exit code を見るだけ。
;; **判定をここに複製しない** —— 閾値と drift の定義は collector 側にあり、
;; 2 箇所にあって片方だけ直る、を起こさない（hayari-alarm と同じ方針）。
;;
;; **なぜ fleet gate ではないのか。** drift の片側は `launchctl list`、つまり
;; *この機械で実際に何が動いているか*である。fleet gate はノードへ tree を配って
;; 走らせるので、operator の launchd を見られない。したがって operator 側の常駐。
;;
;; **重複について。** 遷移検出（前回の健全性を state に持ち、変化した時だけ鳴らす）は
;; `hayari-alarm.cljs` と同型である。2 本目なので共通化しない —— **3 本目を書くときに
;; `transition-alarm` として抽出する。** ここに書いておかないと、3 本目の人が
;; 同じ判断を最初からやり直す。
;;
;;   nbb scripts/fleet-ci/residency-alarm.cljs [--root <superproject>] [--notify false]

(ns fleet-ci.residency-alarm
  (:require ["node:child_process" :as cp]
            ["node:fs" :as fs]
            ["node:path" :as path]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(def args (vec *command-line-args*))
(defn- flag [n d] (let [i (.indexOf args n)] (if (neg? i) d (nth args (inc i)))))

(def root (flag "--root" (path/join (or js/process.env.HOME "/tmp") "github" "com-junkawasaki")))
(def notify? (not= "false" (flag "--notify" "true")))
(def state-f (flag "--state" (path/join (or js/process.env.HOME "/tmp") ".gftd" "residency-alarm-state.edn")))

(defn- run-check []
  (let [r (cp/spawnSync "nbb" #js["scripts/residency-collect.cljs" root "--check"]
                        #js {:cwd root :encoding "utf8" :maxBuffer (* 8 1024 1024)})]
    {:rc (aget r "status") :out (str (aget r "stdout") (aget r "stderr"))}))

(defn- notify! [title body]
  ;; このマシンに実在する唯一の人間向けチャネル（workspace に slack/mail 連携は無い）。
  ;; 判定は state と exit code に残るので、通知の失敗は握り潰してよい。
  (when notify?
    (try
      (cp/spawnSync "osascript"
                    #js["-e" (str "display notification " (pr-str body)
                                  " with title " (pr-str title))]
                    #js {:encoding "utf8"})
      (catch :default _ nil))))

(defn -main [& _]
  (let [{:keys [rc out]} (run-check)
        healthy? (zero? rc)
        ;; FAIL 行が「何が drift したか」を1行で持っている。
        why (or (first (filter #(str/starts-with? % "FAIL") (str/split-lines out)))
                "drift あり")
        prior (when (fs/existsSync state-f)
                (try (edn/read-string (fs/readFileSync state-f "utf8")) (catch :default _ nil)))
        was-healthy? (:healthy? prior)]
    (println out)
    (cond
      ;; 初回は前回を知らない。**不健全なら鳴らす** —— 「知らない」を「健全」と
      ;; 読み替えない（hayari-alarm と同じ理由）。
      (nil? prior)
      (when-not healthy? (notify! "常駐 drift" why))

      (and was-healthy? (not healthy?))
      (notify! "常駐 drift が出ました" why)

      (and (not was-healthy?) healthy?)
      (notify! "常駐 drift が解消しました" "宣言と実機が一致しています"))

    (fs/mkdirSync (path/dirname state-f) #js {:recursive true})
    (fs/writeFileSync state-f (pr-str {:healthy? healthy? :rc rc :why (when-not healthy? why)}))
    (set! (.-exitCode js/process) (if healthy? 0 1))))

(apply -main *command-line-args*)
