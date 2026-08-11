#!/usr/bin/env nbb
;; hayari の無人ループが止まったら、人に見えるところで 1 回だけ鳴らす。
;;
;; なぜ tick 側に置かないか: **死んだループは自分の死を報告できない。** 警報は
;; ループとは別のプロセスから出る必要がある。したがってこれは operator 側
;; （人が座っているマシン）に常駐し、ループが走るノードには置かない。
;;
;; なぜ遷移のときだけ鳴らすか: 毎回鳴らすと無視されるようになる。
;; 「常時赤い gate は gate が無いのと同じ」という登録簿の既存の教訓は、
;; 通知にもそのまま当てはまる。healthy→unhealthy で 1 回、回復で 1 回。
;;
;; 判定ロジックは複製しない —— gates/hayari-tick-alive.cljs を **そのまま呼んで
;; exit code を見る**。閾値が 2 箇所にあると、片方だけ直された時に静かに食い違う。
;;
;;   nbb scripts/fleet-ci/hayari-alarm.cljs [--root <superproject>] [--notify false]

(ns fleet-ci.hayari-alarm
  (:require ["node:child_process" :as cp]
            ["node:fs" :as fs]
            ["node:path" :as path]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(def args (vec *command-line-args*))
(defn- flag [n d] (let [i (.indexOf args n)] (if (neg? i) d (nth args (inc i)))))

(def root      (flag "--root" (path/join (or js/process.env.HOME "/tmp") "github" "com-junkawasaki")))
(def notify?   (not= "false" (flag "--notify" "true")))
(def state-f   (flag "--state" (path/join (or js/process.env.HOME "/tmp") ".gftd" "hayari-alarm-state.edn")))

(def gate   (path/join root "scripts" "fleet-ci" "gates" "hayari-tick-alive.cljs"))
(def target (path/join root "orgs" "cloud-itonami" "hayari"))

(defn- run-gate []
  (let [r (cp/spawnSync "nbb" (clj->js [gate target])
                        #js {:encoding "utf8" :timeout 120000})]
    {:code (or (.-status r) 1)
     :out  (str/trim (str (or (.-stdout r) "") (or (.-stderr r) "")))}))

(defn- notify! [title body]
  ;; macOS の通知センターがこのマシンに実在する唯一の人間向けチャネル
  ;; （実測: workspace に slack / mail 連携は無い）。届かなくても警報の
  ;; 判定自体は state ファイルと exit code に残るので、失敗は握り潰してよい。
  (when notify?
    (try
      (cp/spawnSync "osascript"
                    (clj->js ["-e" (str "display notification " (pr-str body)
                                        " with title " (pr-str title))])
                    #js {:timeout 15000})
      (catch :default _ nil))))

(defn -main [& _]
  (when-not (fs/existsSync gate)
    (println (str "hayari-alarm: gate が無い — " gate)) (js/process.exit 1))
  (let [{:keys [code out]} (run-gate)
        healthy? (zero? code)
        prior    (when (fs/existsSync state-f)
                   (try (edn/read-string (fs/readFileSync state-f "utf8")) (catch :default _ nil)))
        was      (:healthy? prior)]
    (println out)
    (cond
      ;; 初回は state が無い。**不健全なら鳴らす** —— 「前回を知らない」を
      ;; 「変化していない」と扱うと、止まった状態で導入した時に永久に黙る。
      (nil? was)
      (when-not healthy?
        (notify! "hayari: ループが止まっています" (first (str/split-lines out))))

      (and was (not healthy?))
      (notify! "hayari: ループが止まりました" (first (str/split-lines out)))

      (and (not was) healthy?)
      (notify! "hayari: ループが回復しました" "backfill が再開しています"))

    (fs/mkdirSync (path/dirname state-f) #js {:recursive true})
    (fs/writeFileSync state-f
                      (pr-str {:healthy? healthy?
                               :at (subs (.toISOString (js/Date.)) 0 19)
                               :gate-exit code}))
    (println (str "hayari-alarm: healthy?=" healthy?
                  (cond (nil? was) " (初回)"
                        (= was healthy?) " (変化なし — 鳴らさない)"
                        :else " (遷移 — 鳴らした)")))
    ;; 警報自身は常に 0 で終わる。落ちるのは gate の役目であって、
    ;; 通知役が赤いと「通知が壊れている」と「監視対象が壊れている」が混ざる。
    (js/process.exit 0)))

(apply -main args)
