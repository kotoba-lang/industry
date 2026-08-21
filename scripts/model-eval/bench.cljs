;; bench.cljs — laguna-xs-2.1 (IQ2_XS) vs qwen3.6-35b-a3b (IQ2_XXS) on one
;; murakumo node (judah, Mac mini M4 / 16 GB).
;;
;; 出力は 1 行 1 EDN の追記ログ。途中で落ちても、そこまでの実測は残る。
;;
;;   nbb bench.cljs speed  <model>
;;   nbb bench.cljs tasks  <model>
;;   nbb bench.cljs needle <model>
;;
;; 「答えられなかった」を「合格」と同じ形で出さないこと (CLAUDE.md, ADR-2608136000)。
;; だから結果は :pass / :fail / :error の 3 値で、:error は集計で分けて数える。

(ns bench
  (:require ["fs" :as fs]
            ["child_process" :as cp]
            ["os" :as os]
            [clojure.string :as str]
            [promesa.core :as p]
            [tasks :as t]))

;; ノードの ollama は localhost にしか bind していないので ssh トンネル越しに叩く:
;;   ssh -N -L 11435:127.0.0.1:11434 <node>
(def endpoint (or (.-BENCH_ENDPOINT js/process.env) "http://127.0.0.1:11435"))
(def out-dir (or (.-BENCH_OUT js/process.env) "./bench-results"))

(defn append! [file m]
  (fs/mkdirSync out-dir #js {:recursive true})
  (fs/appendFileSync (str out-dir "/" file) (str (pr-str m) "\n")))

(defn chat [model prompt opts]
  (p/let [body (js/JSON.stringify
                (clj->js (merge {:model model
                                 :messages [{:role "user" :content prompt}]
                                 :stream false
                                 ;; THINK=on で reasoning を有効にする。laguna は
                                 ;; reasoning model なので両モードとも測る —— 片方
                                 ;; だけ測ると「予算切れ」を「不正解」と読み違える。
                                 :think (= "on" (.-THINK js/process.env))
                                 :keep_alive "40m"
                                 :options (merge {:temperature 0 :seed 42 :num_ctx 8192}
                                                 (:options opts))}
                                (dissoc opts :options))))
          res (js/fetch (str endpoint "/api/chat")
                        #js {:method "POST"
                             :headers #js {"Content-Type" "application/json"}
                             :body body})
          txt (.text res)]
    (try (js->clj (js/JSON.parse txt) :keywordize-keys true)
         (catch :default _ {:error (subs txt 0 400)}))))

(defn metrics [r]
  (let [ns->s #(/ (or % 0) 1e9)]
    {:in-tok (:prompt_eval_count r)
     :out-tok (:eval_count r)
     :prefill-tps (when (and (:prompt_eval_count r) (pos? (or (:prompt_eval_duration r) 0)))
                    (/ (:prompt_eval_count r) (ns->s (:prompt_eval_duration r))))
     :decode-tps (when (and (:eval_count r) (pos? (or (:eval_duration r) 0)))
                   (/ (:eval_count r) (ns->s (:eval_duration r))))
     :load-s (ns->s (:load_duration r))
     :total-s (ns->s (:total_duration r))}))

(defn r2 [x] (when x (/ (js/Math.round (* 100 x)) 100)))

;; cljs に clojure.core/format は無い。
(defn pad [s n] (let [s (str s)] (str s (apply str (repeat (max 0 (- n (count s))) " ")))))

;; ---------------------------------------------------------------- extraction

(defn code-block
  "```python ... ``` を取り出す。fence が無ければ本文全体を返す（fence を出さない
   のもモデルの振る舞いなので、抽出できなかったことを合格にも不合格にもしない —
   実際に走らせて判定する）。"
  [s]
  (let [m (re-find #"(?s)```(?:python|py)?\s*\n(.*?)```" (or s ""))]
    (if m (second m) (or s ""))))

(defn json-block
  "本文から最初のバランスした JSON object を取り出す。"
  [s]
  (let [s (str/replace (or s "") #"(?s)```(?:json)?\s*\n?" "")
        i (str/index-of s "{")]
    (when i
      (loop [j i depth 0 instr false esc false]
        (if (>= j (count s))
          nil
          (let [c (nth s j)]
            (cond
              esc (recur (inc j) depth instr false)
              (= c \\) (recur (inc j) depth instr true)
              (= c \") (recur (inc j) depth (not instr) false)
              instr (recur (inc j) depth instr false)
              (= c \{) (recur (inc j) (inc depth) instr false)
              (= c \}) (if (= depth 1)
                         (subs s i (inc j))
                         (recur (inc j) (dec depth) instr false))
              :else (recur (inc j) depth instr false))))))))

;; ------------------------------------------------------------------ checking

(defn run-python [src stdin-str]
  (let [f (str (os/tmpdir) "/bench-" (js/Math.floor (* 1e9 (js/Math.random))) ".py")]
    (fs/writeFileSync f src)
    (try
      (let [r (cp/spawnSync "python3" #js [f]
                            #js {:timeout 20000 :encoding "utf8"
                                 :input (or stdin-str "")
                                 :env #js {"PATH" (.-PATH js/process.env)
                                           "HOME" (os/tmpdir)}})]
        (fs/unlinkSync f)
        {:code (.-status r)
         :out (or (.-stdout r) "")
         :err (str/trim (subs (or (.-stderr r) "") 0 300))})
      (catch :default e
        {:code -1 :out "" :err (str e)}))))

(defn check [task content]
  (case (:kind task)
    :python (let [src (str (code-block content) "\n\n" (:harness task))
                  r (run-python src nil)]
              (assoc r :verdict (if (and (= 0 (:code r)) (str/includes? (:out r) "PASS"))
                                  :pass :fail)))
    :json (let [j (json-block content)]
            (if-not j
              {:verdict :fail :err "no JSON object in output" :code nil :out ""}
              (let [r (run-python (:harness task) j)]
                (assoc r :verdict (if (and (= 0 (:code r)) (str/includes? (:out r) "PASS"))
                                    :pass :fail)))))))

;; ------------------------------------------------------------------- runners

(defn run-tasks [model]
  (p/loop [ts (if-let [only (.-ONLY js/process.env)]
                (let [want (set (map keyword (str/split only #",")))]
                  (filter #(want (:id %)) t/tasks))
                t/tasks)
           acc []]
    (if (empty? ts)
      (let [n (count acc)
            f #(count (filter (fn [r] (= % (:verdict r))) acc))]
        (println (str "\n== " model "  PASS " (f :pass) "/" n
                      "   fail " (f :fail) "   truncated " (f :truncated))))
      (let [task (first ts)
            ;; 予算切れで途中で切れた出力を「不正解」と同じ形で数えない
            ;; (ADR-2608136000: 答えられなかったことを pass/fail に潰さない)。
            ;; thinking を有効にすると thinking token も num_predict を食う。
            ;; 実測: laguna は lru-cache 1 問に 12,295 字 (= 予算 3,000 tok 全部) を
            ;; 費やして本文を 1 字も出さなかった。cap はそこから決めている。
            cap (if (= "on" (.-THINK js/process.env))
                  16000
                  (max 2000 (* 2 (:budget task))))]
        (p/let [t0 (js/Date.now)
                r (chat model (:prompt task)
                        {:options {:num_predict cap :num_ctx 8192}})
                content (get-in r [:message :content] "")
                thinking (get-in r [:message :thinking])
                verdict (check task content)
                m (metrics r)
                trunc? (>= (or (:out-tok m) 0) cap)
                verdict (if (and trunc? (not= :pass (:verdict verdict)))
                          (assoc verdict :verdict :truncated) verdict)
                row (merge {:model model :task (:id task)
                            :verdict (:verdict verdict)
                            :cap cap
                            :err (:err verdict)
                            :wall-s (r2 (/ (- (js/Date.now) t0) 1000))
                            :thinking-chars (count (or thinking ""))
                            :content-chars (count content)}
                           (-> m (update :prefill-tps r2) (update :decode-tps r2)
                               (update :load-s r2) (update :total-s r2)))]
          (append! "tasks.edn" (assoc row :content content))
          (println (str (name (:verdict verdict)) "  " (name (:id task))
                        "  out=" (:out-tok m) "tok"
                        " decode=" (r2 (:decode-tps m)) "t/s"
                        " wall=" (:wall-s row) "s"
                        (when (:err verdict) (str "  ! " (first (str/split-lines (:err verdict)))))))
          (p/recur (rest ts) (conj acc row)))))))

(defn filler [n-words]
  (str/join " " (map #(str "line" % " the quick brown fox jumps over the lazy dog")
                     (range n-words))))

(defn run-needle [model]
  ;; filler の 1 entry ≒ 13 tok。目標 4K / 16K / 32K tok。
  (p/loop [ns [310 1250 2500] acc []]
    (if (empty? ns)
      (println "needle done")
      (let [w (first ns)
            secret "The murakumo fleet passphrase is ORANGE-HARBOR-49."
            body (str (filler (quot w 2)) "\n\n" secret "\n\n" (filler (quot w 2)))
            prompt (str "Read the document below and answer one question.\n\n<doc>\n"
                        body "\n</doc>\n\nWhat is the murakumo fleet passphrase? Answer with the passphrase only.")]
        (p/let [t0 (js/Date.now)
                r (chat model prompt {:options {:num_predict 60 :num_ctx 40960}})
                content (get-in r [:message :content] "")
                m (metrics r)
                ok (str/includes? (str/upper-case content) "ORANGE-HARBOR-49")
                row {:model model :needle-words w :in-tok (:in-tok m)
                     ;; 走らなかったことを「不正解」と同じ形にしない
                     :verdict (cond (:error r) :error
                                    (nil? (:in-tok m)) :error
                                    ok :pass :else :fail)
                     :error (or (:error r) (:error_ r))
                     :prefill-tps (r2 (:prefill-tps m))
                     :decode-tps (r2 (:decode-tps m))
                     :wall-s (r2 (/ (- (js/Date.now) t0) 1000))
                     :answer (str/trim (subs content 0 120))}]
          (append! "needle.edn" row)
          (println (str (name (:verdict row)) "  ctx=" (:in-tok m) "tok"
                        " prefill=" (:prefill-tps row) "t/s"
                        " wall=" (:wall-s row) "s  -> " (:answer row)))
          (p/recur (rest ns) (conj acc row)))))))

(defn run-speed [model]
  (p/loop [reps [1 2 3] acc []]
    (if (empty? reps)
      (let [ds (keep :decode-tps acc)]
        (println (str "== " model " median decode "
                      (r2 (nth (sort ds) (quot (count ds) 2))) " tok/s")))
      (p/let [t0 (js/Date.now)
              r (chat model "Write a haiku about a mac mini. Then count from 1 to 40, one number per line."
                      {:options {:num_predict 300 :num_ctx 4096}})
              m (metrics r)
              row (merge {:model model :rep (first reps)
                          :wall-s (r2 (/ (- (js/Date.now) t0) 1000))}
                         (-> m (update :prefill-tps r2) (update :decode-tps r2)
                             (update :load-s r2) (update :total-s r2)))]
        (append! "speed.edn" row)
        (println (str "rep " (first reps) "  out=" (:out-tok m)
                      " decode=" (:decode-tps row) "t/s load=" (:load-s row) "s"))
        (p/recur (rest reps) (conj acc row))))))

(defn ps []
  (p/let [res (js/fetch (str endpoint "/api/ps"))
          j (.json res)]
    (first (js->clj (.-models j) :keywordize-keys true))))

(defn run-ctx
  "num_ctx を上げながら『GPU に載り切っているか』と『実 decode』を測る。
   GPU 常駐率だけでは何も決められない —— 溢れた結果どれだけ遅くなるかが
   判断材料なので、両方を同じ行に出す。"
  [model]
  (p/loop [ns [8192 32768 65536 131072 262144] acc []]
    (if (empty? ns)
      (println "ctx done")
      (let [n (first ns)]
        (p/let [_ (chat model "hi" {:options {:num_ctx n :num_predict 4}})
                r (chat model "Count from 1 to 40, one number per line."
                        {:options {:num_ctx n :num_predict 150}})
                m (metrics r)
                g (ps)
                gpu% (when (and g (pos? (:size g)))
                       (* 100.0 (/ (:size_vram g 0) (:size g))))
                row {:model model :num-ctx n
                     :decode-tps (r2 (:decode-tps m))
                     :total-gb (when g (r2 (/ (:size g) 1e9)))
                     :gpu-gb (when g (r2 (/ (:size_vram g 0) 1e9)))
                     :gpu-pct (r2 gpu%)
                     ;; 載らなかったのか、載って遅いのかを混ぜない
                     :verdict (cond (nil? (:decode-tps m)) :error
                                    (and gpu% (< gpu% 99)) :spilled
                                    :else :resident)}]
          (append! "ctx.edn" row)
          (println (str (pad (name (:verdict row)) 10)
                        " num_ctx=" n
                        "  decode=" (:decode-tps row) "t/s"
                        "  gpu=" (:gpu-gb row) "/" (:total-gb row) "GB"
                        " (" (:gpu-pct row) "%)"))
          (p/recur (rest ns) (conj acc row)))))))

(let [[mode model] *command-line-args*]
  (case mode
    "speed" (run-speed model)
    "tasks" (run-tasks model)
    "needle" (run-needle model)
    "ctx" (run-ctx model)
    (println "usage: bench.cljs speed|tasks|needle|ctx <model>")))
