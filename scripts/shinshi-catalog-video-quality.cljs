#!/usr/bin/env nbb
;; scripts/shinshi-catalog-video-quality.cljs — catalog i2v clip の決定論的品質。
;; モデルは居ない。測れなければ pass と書かない。
;;
;; 実測床（2026-08-08 / 2026-08-13）:
;;   全黒 clip = 49-frame mp4, 50,939 bytes, sampled luma 0.0
;;   公開した 2 本 = ~184–194 KiB, luma 129 / 135, 512×768, 49 frames, ~2.04s
;;
;; usage:
;;   nbb scripts/shinshi-catalog-video-quality.cljs <file.mp4> [--json]
;;   nbb scripts/shinshi-catalog-video-quality.cljs --self-test

(require '[clojure.string :as str])

(def fs (js/require "node:fs"))
(def path (js/require "node:path"))
(def os (js/require "node:os"))
(def cp (js/require "node:child_process"))
(def process (js/require "node:process"))

;; loop.cljs は deploy 先を quality.cljs という短名で呼ぶ。長名だけを見ると
;; 自分の .cljs を file-arg に取り、ffprobe が「Invalid data」で unanswered を吐く。
;; 名前ではなく「最後の .cljs = 自分」で切る。
(defn- parse-args [all]
  (let [self? (fn [s]
                (let [b (.basename path (str s))]
                  (or (contains? #{"shinshi-catalog-video-quality.cljs" "quality.cljs"} b)
                      (str/ends-with? b ".cljs"))))
        i (last (keep-indexed (fn [idx s] (when (self? s) idx)) all))]
    (vec (drop (inc (or i 1)) all))))

(def argv (parse-args (vec (js->clj (.-argv process)))))
(def json? (boolean (some #{"--json"} argv)))
(def self-test? (boolean (some #{"--self-test"} argv)))
(def file-arg (first (remove #(str/starts-with? % "--") argv)))

(def luma-black 8.0)
(def luma-dark 20.0)
(def min-bytes 80000)
(def expect-w 512)
(def expect-h 768)
(def expect-frames 49)
(def min-sec 1.5)
(def max-sec 3.5)
(def motion-mae 1.0)

(defn- ffmpeg []
  (or (let [p "/opt/homebrew/bin/ffmpeg"]
        (when (.existsSync fs p) p))
      "ffmpeg"))

(defn- ffprobe []
  (or (let [p "/opt/homebrew/bin/ffprobe"]
        (when (.existsSync fs p) p))
      "ffprobe"))

(defn- run [bin args]
  (let [r (.spawnSync cp bin (clj->js args)
                      #js {:encoding "utf8"
                           :stdio #js ["ignore" "pipe" "pipe"]
                           :timeout 30000})]
    {:status (or (.-status r) 1)
     :out (str (or (.-stdout r) ""))
     :err (str (or (.-stderr r) ""))}))

(defn- probe [file]
  (let [r (run (ffprobe)
               ["-v" "error"
                "-show_entries" "format=duration,size:stream=width,height,nb_frames,codec_name"
                "-of" "json" file])]
    (if (not= 0 (:status r))
      {:ok false :error (str "ffprobe-exit-" (:status r) " " (subs (:err r) 0 (min 200 (count (:err r)))))}
      (try
        (let [j (js->clj (js/JSON.parse (:out r)) :keywordize-keys true)
              st (first (:streams j))
              fmt (:format j)]
          {:ok true
           :width (js/parseInt (str (or (:width st) 0)) 10)
           :height (js/parseInt (str (or (:height st) 0)) 10)
           :frames (js/parseInt (str (or (:nb_frames st) 0)) 10)
           :codec (str (:codec_name st))
           :duration (js/parseFloat (str (or (:duration fmt) 0)))
           :bytes (js/parseInt (str (or (:size fmt) 0)) 10)})
        (catch :default e {:ok false :error (str "ffprobe-parse:" e)})))))

(defn- gray-frame [file ss]
  (let [r (.spawnSync cp (ffmpeg)
                      #js ["-hide_banner" "-loglevel" "error"
                           "-ss" (str ss) "-i" file
                           "-frames:v" "1"
                           "-vf" "format=gray,scale=16:16"
                           "-f" "rawvideo" "pipe:1"]
                      #js {:encoding nil :maxBuffer (* 4 1024 1024)
                           :stdio #js ["ignore" "pipe" "pipe"]
                           :timeout 20000})]
    (when (zero? (or (.-status r) 1))
      (.-stdout r))))

(defn- mean-gray [buf]
  (when buf
    (let [n (.-length buf)]
      (when (pos? n)
        (loop [i 0 s 0]
          (if (< i n)
            (recur (inc i) (+ s (aget buf i)))
            (/ (double s) n)))))))

(defn- mae [a b]
  (when (and a b (pos? (.-length a)) (= (.-length a) (.-length b)))
    (loop [i 0 s 0]
      (if (< i (.-length a))
        (recur (inc i) (+ s (js/Math.abs (- (aget a i) (aget b i)))))
        (/ (double s) (.-length a))))))

(defn- score-file [file]
  (if-not (and file (.existsSync fs file))
    {:pass false :unanswered true :reason "file-missing" :file file}
    (let [pr (probe file)]
      (if-not (:ok pr)
        {:pass false :unanswered true :reason (:error pr) :file file}
        (let [f0 (gray-frame file 0.2)
              f1 (gray-frame file 1.0)
              f2 (gray-frame file 1.6)
              lumas (vec (remove nil? [(mean-gray f0) (mean-gray f1) (mean-gray f2)]))
              luma (when (seq lumas) (/ (reduce + lumas) (count lumas)))
              motion (or (mae f0 f2) (mae f0 f1) (mae f1 f2))
              black? (or (nil? luma) (< luma luma-black))
              tiny? (< (:bytes pr) min-bytes)
              geom? (and (= expect-w (:width pr)) (= expect-h (:height pr)))
              frames? (= expect-frames (:frames pr))
              dur? (and (>= (:duration pr) min-sec) (<= (:duration pr) max-sec))
              static? (and (number? motion) (< motion motion-mae))
              dark? (and (number? luma) (not black?) (< luma luma-dark))
              fail (or black? tiny? (not geom?) (not frames?) (not dur?))
              yellow (and (not fail) (or static? dark?))]
          {:pass (not fail)
           :unanswered false
           :verdict (cond fail :fail yellow :yellow :else :pass)
           :file file
           :luma luma
           :luma-samples lumas
           :motion motion
           :width (:width pr)
           :height (:height pr)
           :frames (:frames pr)
           :duration (:duration pr)
           :bytes (:bytes pr)
           :codec (:codec pr)
           :checks {:black black? :tiny tiny? :geom (not geom?)
                    :frames (not frames?) :duration (not dur?)
                    :static static? :dark dark?}})))))

(defn- emit [m]
  (when-not json?
    (println (str "VERDICT\t" (name (or (:verdict m) :unanswered))))
    (println (str "PASS\t" (boolean (:pass m))))
    (println (str "UNANSWERED\t" (boolean (:unanswered m))))
    (when (:luma m) (println (str "LUMA\t" (.toFixed (:luma m) 2))))
    (when (:motion m) (println (str "MOTION\t" (.toFixed (:motion m) 2))))
    (when (:bytes m) (println (str "BYTES\t" (:bytes m))))
    (when (:reason m) (println (str "REASON\t" (:reason m)))))
  (println (js/JSON.stringify (clj->js m))))

(defn- argv-test! []
  ;; 実測した壊れ方: deploy 名 quality.cljs で file-arg が script 自身になった。
  (doseq [script ["/Users/x/.itonami/shinshi-catalog-video/quality.cljs"
                  "/repo/scripts/shinshi-catalog-video-quality.cljs"]]
    (let [got (parse-args ["node" "/opt/homebrew/bin/nbb" script "/tmp/a.mp4" "--json"])]
      (when-not (= ["/tmp/a.mp4" "--json"] got)
        (println (str "SELF-TEST FAIL: argv " script " -> " (pr-str got)))
        (.exit process 1))
      (when (str/ends-with? (str (first (remove #(str/starts-with? % "--") got))) ".cljs")
        (println "SELF-TEST FAIL: file-arg resolved to the script itself")
        (.exit process 1)))))

(defn- self-test! []
  (argv-test!)
  (let [empty-p (.join path (.tmpdir os) "shinshi-quality-empty.mp4")]
    (.writeFileSync fs empty-p (js/Buffer.from #js []))
    (let [bad (score-file empty-p)
          good-p "/tmp/aoi-amano-cafe-0.mp4"
          good (when (.existsSync fs good-p) (score-file good-p))
          bad-ok (false? (:pass bad))]
      (.unlinkSync fs empty-p)
      (when-not bad-ok
        (println "SELF-TEST FAIL: empty mp4 must not pass")
        (.exit process 1))
      (when (and good (not (:pass good)))
        (println "SELF-TEST FAIL: known good clip must pass")
        (.exit process 1))
      (println (str "SELF-TEST OK argv=ok empty-fail=" bad-ok
                    (when good (str " good-verdict=" (name (:verdict good))))))
      (.exit process 0))))

(cond
  self-test? (self-test!)
  (nil? file-arg)
  (do (println "usage: nbb scripts/shinshi-catalog-video-quality.cljs <file.mp4> [--json]")
      (.exit process 2))
  :else
  (let [m (score-file file-arg)]
    (emit m)
    (.exit process (cond (:unanswered m) 2 (:pass m) 0 :else 1))))
