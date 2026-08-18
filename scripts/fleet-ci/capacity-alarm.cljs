#!/usr/bin/env nbb
;; capacity-alarm.cljs — この機械のディスクが尽きる前に、1 回だけ鳴らす。
;;
;; ADR-2608160300 の再発防止。2026-08-16、`/System/Volumes/Data` が 460Gi 中
;; 空き 122Mi になり、`git worktree add` が `No space left on device` で落ち、
;; 続いて agent harness 自身の task 出力すら書けなくなった。**症状は全部
;; 無関係な場所に出た** —— checkout 失敗、テスト失敗、receipt が書けない。
;; 容量そのものを見ていたものが何も無かったので、原因に辿り着くまでが遠かった。
;;
;; **なぜ fleet gate ではないのか。** 測る対象が *この機械* だからである。gate は
;; ノードへ tree を配って走らせるので、operator の空き容量を見られない
;; （residency-alarm が launchd について同じ理由で operator 側に居るのと同型）。
;; ノード側の空きは probe.cljs が `:free-gb` として既に測っている。
;;
;; **なぜ inode も見るのか。** 実測中、bytes がまだ 1.5Gi あった時点で
;; `ifree` が 1.3M まで落ちていた。片方だけ見ていると「まだ空いている」と
;; 読んで、ファイル作成が失敗する理由が分からなくなる。
;;
;;   nbb --classpath scripts/fleet-ci scripts/fleet-ci/capacity-alarm.cljs \
;;       [--floor-gb 20] [--floor-inodes 2000000] [--notify false] [--state <f>]

(ns capacity-alarm
  (:require ["node:child_process" :as cp]
            ["node:path" :as path]
            [clojure.string :as str]
            [transition-alarm :as alarm]))

(def args (vec *command-line-args*))
(defn- flag [n d] (let [i (.indexOf args n)] (if (neg? i) d (nth args (inc i)))))

;; 20 GiB: superproject の worktree 1 本 + build キャッシュが数 GB を要求するので、
;; 「気づいてから片付ける」余地がこのくらい要る。0 に近づいてから鳴らす警報は、
;; 鳴った時にはもう何もできない。
(defn- num-flag
  "A threshold that did not parse is not a threshold.

  `parseFloat` answers NaN for a bad value, and every comparison against NaN
  is false — so a typo in the floor would make this alarm report healthy
  forever, which is the exact shape of failure it exists to catch. Refuse
  instead."
  [name default parse]
  (let [raw (flag name default)
        v (parse raw)]
    (when (js/isNaN v)
      (println (str "capacity-alarm: " name " が数値ではありません: " (pr-str raw)))
      (js/process.exit 2))
    v))

(def floor-gb (num-flag "--floor-gb" "20" js/parseFloat))
(def floor-inodes (num-flag "--floor-inodes" "2000000" js/parseInt))
(def volume (flag "--volume" "/System/Volumes/Data"))
(def notify? (not= "false" (flag "--notify" "true")))
(def state-f (flag "--state" (path/join (or js/process.env.HOME "/tmp")
                                        ".gftd" "capacity-alarm-state.edn")))

(defn measure
  "`df -k` の 1 行を読む。**取れなかったときは nil を返す** —— 測れなかったことを
  『空きは十分』と同じ値にしない（ADR-2608136000 の 1 問目）。"
  [vol]
  (let [r (cp/spawnSync "df" (clj->js ["-k" "-i" vol])
                        #js {:encoding "utf8" :timeout 20000})]
    (when (zero? (or (.-status r) 1))
      (let [line (second (str/split-lines (str/trim (str (.-stdout r)))))
            cols (str/split (str/trim (or line "")) #"\s+")]
        ;; Filesystem 1024-blocks Used Available Capacity iused ifree %iused ...
        (when (>= (count cols) 7)
          {:free-gb (/ (js/parseFloat (nth cols 3)) 1048576.0)
           :free-inodes (js/parseInt (nth cols 6))
           :capacity (nth cols 4)})))))

(defn check []
  (if-let [{:keys [free-gb free-inodes capacity]} (measure volume)]
    (let [low-bytes? (< free-gb floor-gb)
          low-inodes? (< free-inodes floor-inodes)]
      {:healthy? (not (or low-bytes? low-inodes?))
       :free-gb free-gb :free-inodes free-inodes :capacity capacity
       :why (str/join " / "
                      (cond-> []
                        low-bytes? (conj (str "空き " (.toFixed free-gb 1)
                                              "GiB < 床 " floor-gb "GiB"))
                        low-inodes? (conj (str "inode 空き " free-inodes
                                               " < 床 " floor-inodes))))})
    ;; df が答えなかった。これは「空いている」ではない。
    {:healthy? false :why (str "df が " volume " を測れなかった")}))

(defn -main [& _]
  (let [result (alarm/run!
                {:label "capacity-alarm"
                 :check check
                 :state-file state-f
                 :notify? notify?
                 :messages
                 {:broke ["ディスクが尽きかけています"
                          (fn [{:keys [why]}]
                            (str why "。brew cleanup -s / npm cache clean が安全。"
                                 "CoreSimulator は消さない（ADR-2608160300）"))]
                  :recovered ["ディスクの空きが戻りました"
                              (fn [{:keys [free-gb capacity]}]
                                (str "空き " (when free-gb (.toFixed free-gb 1))
                                     "GiB（使用 " capacity "）"))]}})]
    (when-let [g (:free-gb result)]
      (println (str "capacity-alarm: 空き " (.toFixed g 1) "GiB / inode "
                    (:free-inodes result) " / 使用 " (:capacity result))))
    ;; 警報自身は常に 0。落ちるのは監視対象の役目であって、通知役が赤いと
    ;; 「通知が壊れている」と「ディスクが埋まった」が混ざる。
    (js/process.exit 0)))

(apply -main args)
