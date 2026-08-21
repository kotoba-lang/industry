#!/usr/bin/env nbb
;; score-video.cljs — deterministic per-model video-output quality scorer
;; (ADR-2607180000). Runs ffprobe/ffmpeg over one produced .mp4, computes the
;; container + pixel axes declared in gen-quality.datoms.edn, normalizes each to
;; [0,1], and PRINTS append-only ledger rows (one EDN map per line) to stdout —
;; the caller redirects/appends into gen-quality-ledger.edn. No LLM, no browser,
;; no network: same class of decision-function as 90-docs/design-quality/audit.
;;
;;   nbb 90-docs/gen-quality/score-video.cljs \
;;       --model <model-id> --run-id <id> --file <path.mp4> [--target-dur <s>]
;;
;; Exit 2 if the file is missing/unreadable (fail-closed — never emit a fabricated
;; score for an artifact that does not exist).
(ns score-video
  (:require [clojure.string :as str]
            ["node:child_process" :as cp]
            ["node:fs" :as fs]))

(defn- sh [cmd args]
  (let [r (cp/spawnSync cmd (clj->js args) #js {:encoding "utf8" :maxBuffer 67108864})]
    {:code (.-status r) :out (or (.-stdout r) "") :err (or (.-stderr r) "")}))

(defn- args->map [argv]
  (loop [xs (seq argv) m {}]
    (if (and xs (next xs))
      (recur (nnext xs) (assoc m (keyword (str/replace (first xs) #"^--" "")) (second xs)))
      m)))

(defn- clamp01 [x] (max 0.0 (min 1.0 (double x))))
(defn- round3 [x] (/ (js/Math.round (* x 1000)) 1000.0))

(defn probe
  "ffprobe → {:w :h :fps :nb-frames :vbitrate :duration :has-audio}."
  [file]
  (let [j (-> (sh "ffprobe" ["-v" "error" "-select_streams" "v:0"
                             "-show_entries" "stream=width,height,r_frame_rate,nb_frames,bit_rate,duration"
                             "-show_entries" "format=size,duration"
                             "-of" "json" file])
              :out js/JSON.parse)
        s (aget (.-streams j) 0)
        [n d] (str/split (or (.-r_frame_rate s) "0/1") #"/")
        fps (if (and d (not= "0" d)) (/ (js/parseFloat n) (js/parseFloat d)) 0)
        audio (-> (sh "ffprobe" ["-v" "error" "-select_streams" "a"
                                 "-show_entries" "stream=codec_name" "-of" "csv=p=0" file])
                  :out str/trim seq boolean)]
    {:w (js/parseInt (.-width s)) :h (js/parseInt (.-height s))
     :fps fps
     :nb-frames (js/parseInt (or (.-nb_frames s) "0"))
     :vbitrate (js/parseFloat (or (.-bit_rate s) "0"))
     :duration (js/parseFloat (or (.-duration s) (.. j -format -duration) "0"))
     :size (js/parseInt (or (.. j -format -size) "0"))
     :has-audio audio}))

(defn mean-ydif
  "Mean frame-to-frame luma delta over the first `secs` seconds — a frozen
  slideshow reads ~0 (placeholder video), real generated motion reads > 0."
  [file secs]
  (let [{:keys [err]} (sh "ffmpeg" ["-hide_banner" "-i" file
                                    "-vf" "signalstats,metadata=print:key=lavfi.signalstats.YDIF"
                                    "-t" (str secs) "-f" "null" "-"])
        vals (->> (re-seq #"YDIF=([0-9.]+)" err) (map (comp js/parseFloat second)))]
    (if (seq vals) (/ (reduce + vals) (count vals)) 0.0)))

;; axis normalizers → [0,1]. Constants mirror gen-quality.datoms.edn :axis/* :norm.
(defn- axis-scores [{:keys [w h fps nb-frames vbitrate duration has-audio]} target-dur ydif]
  (let [area (* w h)
        bpp-s (if (and vbitrate (pos? area) (pos? fps)) (/ vbitrate area fps) 0)]
    (cond-> {:axis/resolution     (clamp01 (/ area (* 720 1280)))
             :axis/fps            (clamp01 (/ fps 24))
             :axis/native-audio   (if has-audio 1.0 0.0)
             :axis/bitrate-density (clamp01 (/ bpp-s 0.04))
             :axis/temporal-motion (clamp01 (/ ydif 8.0))}
      target-dur (assoc :axis/duration-fidelity
                        (clamp01 (- 1.0 (/ (js/Math.abs (- duration target-dur))
                                           (max 1.0 target-dur))))))))

(def axis-weight
  {:axis/resolution 0.15 :axis/fps 0.15 :axis/native-audio 0.10
   :axis/bitrate-density 0.20 :axis/temporal-motion 0.30 :axis/duration-fidelity 0.10})

(defn -main []
  (let [{:keys [model run-id file target-dur]} (args->map *command-line-args*)]
    (when-not (and model run-id file)
      (println "usage: nbb score-video.cljs --model <id> --run-id <id> --file <mp4> [--target-dur <s>]")
      (js/process.exit 2))
    (when-not (fs/existsSync file)
      (binding [*out* *err*] (println "fail-closed: file not found:" file))
      (js/process.exit 2))
    (let [p (probe file)
          ydif (mean-ydif file 6)
          tdur (some-> target-dur js/parseFloat)
          scores (axis-scores p tdur ydif)
          composite (/ (reduce + (map (fn [[a s]] (* s (axis-weight a 0))) scores))
                       (reduce + (map #(axis-weight % 0) (keys scores))))
          now (-> (js/Date.) .toISOString)
          note (str "w=" (:w p) " h=" (:h p) " fps=" (round3 (:fps p))
                    " frames=" (:nb-frames p) " dur=" (round3 (:duration p))
                    " vbitrate=" (:vbitrate p) " audio=" (:has-audio p)
                    " mean_ydif=" (round3 ydif))]
      (doseq [[a s] (sort scores)]
        (println (pr-str {:eval/model (keyword model) :eval/axis a :eval/layer :metric
                          :eval/score (round3 s) :eval/judge "ffprobe+ffmpeg-signalstats"
                          :eval/run-id run-id :eval/at now :eval/file file :eval/note note})))
      (println (pr-str {:eval/model (keyword model) :eval/axis :axis/composite :eval/layer :metric
                        :eval/score (round3 composite) :eval/judge "weighted-mean"
                        :eval/run-id run-id :eval/at now :eval/file file
                        :eval/note (str "weights " (pr-str axis-weight))})))))

(-main)
