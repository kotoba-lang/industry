#!/usr/bin/env nbb
;; murakumo-nodes.cljs — フリート各機の**推論プロセスとメモリを 1 箇所から見る／止める／載せる**。
;;
;;   nbb scripts/murakumo-nodes.cljs audit [--json]
;;   nbb scripts/murakumo-nodes.cljs reap [--orphans] [--apply]
;;   nbb scripts/murakumo-nodes.cljs serve <node> <model.gguf> [--mmproj F] [--ctx N] [--port N]
;;   nbb scripts/murakumo-nodes.cljs stop <node> [--port N]
;;
;; ## なぜ要るのか（2026-08-15 に実際に起きたこと）
;;
;; 一晩の計測で、私は以下を**全部**やった:
;;
;;   * laptop に 9.7GB の llama-server を放置したまま次の計測をした
;;   * gad に同じ Qwen3.8 を **2 重ロード**した（:8090 と :11434）
;;   * 7 台の mini に 50052 と 50053 の rpc-server を**二重起動**して確保を抱えさせた
;;   * judah を **空き 63MB** まで埋め、その状態で測った 3.84 tok/s を
;;     「context を伸ばすと遅い」と読みかけた（実際はスラッシング）
;;   * dan を context 上限探索で落とし、tailnet ごと offline にした
;;
;; **どれも「載せる前に空きを見なかった」ことに帰着する。** この CLI は
;; 載せる前に必ず測り、載せた後は本物の生成で確かめ、失敗したら自分で片付ける。
;;
;; ## 3 つの不変条件
;;
;; 1. **`server is listening` をロード成功と読まない。** llama.cpp は遅延確保なので、
;;    listening を出したあと最初のリクエストで `Compute error` で死ぬ（judah で実測）。
;;    `serve` は**本物の生成が返るまで成功と綴らない**。返らなければ自分で止める。
;; 2. **自分が起動したものだけを既定で殺す。** `~/.murakumo/managed/` に記録した
;;    ものが対象。記録に無いプロセス（本番・他人の実験）は `--orphans` を付けても
;;    既定は**報告だけ**で、`--apply` を足して初めて止める。
;; 3. **空きメモリの床を守る。** `serve` は model+mmproj+余裕が空きに収まらなければ
;;    **載せずに断る**。載せてから落ちるより、載せないほうが安い。

(ns murakumo-nodes
  (:require [clojure.string :as str]
            ["node:child_process" :as cp]))

(def args (vec *command-line-args*))
(def cmd (first (remove #(str/starts-with? % "--") args)))
(defn- pos-arg [n] (nth (vec (remove #(str/starts-with? % "--") args)) n nil))
(defn- opt [f d] (let [i (.indexOf args f)] (if (neg? i) d (get args (inc i)))))
(defn- flag? [f] (not (neg? (.indexOf args f))))

;; mac mini（Metal / RPC worker）と gad（Linux / Vulkan head）は別種。混ぜない。
(def minis ["asher" "benjamin" "dan" "issachar" "joseph" "judah" "levi" "naphtali" "simeon" "zebulun"])
(def head "gad")

;; 載せる前に残す余裕（MiB）。macOS 自身と KV の伸びしろ。
;; **この数字は judah を空き 63MB まで埋めた実測から来ている** —— 余裕ゼロでも
;; listening までは進み、最初の生成で Metal の command buffer が落ちた。
(def headroom-mb 1536)

(defn- sh! [host script & [timeout-s]]
  (let [r (cp/spawnSync "ssh" (clj->js ["-o" "BatchMode=yes" "-o" (str "ConnectTimeout=" (or timeout-s 8))
                                        host script])
                        #js {:encoding "utf8" :maxBuffer (* 16 1024 1024)})]
    {:exit (or (aget r "status") 1) :out (str/trim (str (aget r "stdout"))) :err (str (aget r "stderr"))}))

;; --- 観測 -------------------------------------------------------------------
;; **macOS と Linux で書き分ける。** `pgrep -c` は macOS に無く（実測で usage が
;; 返った）、`vm_stat` は Linux に無い。片方の書き方で両方を測ろうとすると、
;; 測れなかった側が「0 件」として通る。

(def mini-probe
  (str "l=$(pgrep -f 'llama-server -m' | wc -l | tr -d ' ');"
       "rss=$(ps -eo rss,args | grep 'llama-server -m' | grep -v grep | awk '{s+=$1} END{printf \"%.1f\", s/1048576}');"
       "free=$(vm_stat | awk '/Pages free/{gsub(/\\./,\"\");printf \"%d\", $3*16384/1048576}');"
       "wl=$(sysctl -n iogpu.wired_limit_mb 2>/dev/null);"
       "tot=$(sysctl -n hw.memsize | awk '{printf \"%d\", $1/1048576}');"
       "rpc=$(for p in 50052 50053; do nc -z -w1 127.0.0.1 $p >/dev/null 2>&1 && printf '%s ' $p; done);"
       "mg=$(ls ~/.murakumo/managed 2>/dev/null | tr '\\n' ' ');"
       "echo \"$l|${rss:-0}|$free|$wl|$tot|$rpc|$mg\""))

(def head-probe
  (str "l=$(pgrep -f llama-server | wc -l);"
       "rss=$(ps -eo rss,args | grep llama-server | grep -v grep | awk '{s+=$1} END{printf \"%.1f\", s/1048576}');"
       "read _ tot used free _ <<< $(free -m | sed -n 2p);"
       "ports=$(ss -ltn 2>/dev/null | grep -oE ':(8090|8095|8096|11434)' | tr -d ':' | sort -u | tr '\\n' ' ');"
       "echo \"$l|${rss:-0}|$free|-|$tot|$ports|\""))

(defn- pad [s n] (let [s (str s)] (str s (apply str (repeat (max 0 (- n (count s))) " ")))))
(defn- lpad [s n] (let [s (str s)] (str (apply str (repeat (max 0 (- n (count s))) " ")) s)))

(defn- parse-probe [s]
  (let [[l rss free wl tot ports managed] (str/split (str s) #"\|")]
    {:procs (js/parseInt (or l "0")) :rss-gb (js/parseFloat (or rss "0"))
     :free-mb (js/parseInt (or free "0")) :wired-mb (js/parseInt (or wl "0"))
     :total-mb (js/parseInt (or tot "0"))
     :ports (remove str/blank? (str/split (or ports "") #"\s+"))
     :managed (remove str/blank? (str/split (or managed "") #"\s+"))}))

(defn- probe [host]
  (let [{:keys [exit out]} (sh! host (if (= host head) head-probe mini-probe) 6)]
    (if (or (not= 0 exit) (str/blank? out))
      {:unreachable true}
      (parse-probe out))))

(defn- cmd-audit! []
  (println (str (str/join "  " ["node      " "procs" "rss"  "freeMB" "wiredMB" "totalMB" "ports/managed"])))
  (let [rows (for [h (cons head minis)] [h (probe h)])]
    (doseq [[h p] rows]
      (if (:unreachable p)
        (println (str (pad h 10) "  UNREACHABLE"))
        (println (str (pad h 10) "  " (lpad (:procs p) 5) " "
                      (lpad (.toFixed (:rss-gb p) 1) 5) "G " (lpad (:free-mb p) 7) " "
                      (lpad (if (pos? (:wired-mb p)) (:wired-mb p) "-") 8) " "
                      (lpad (:total-mb p) 8) "  "
                      (str/join "," (:ports p))
                      (when (seq (:managed p)) (str " managed:" (str/join "," (:managed p))))))))
    ;; **低空きを黙って通さない。** 測っただけで終わると、次の人が同じ穴に落ちる。
    (let [tight (filter (fn [[_ p]] (and (not (:unreachable p)) (< (:free-mb p) headroom-mb))) rows)
          unreach (filter (fn [[_ p]] (:unreachable p)) rows)]
      (println "")
      (when (seq tight)
        (println (str "⚠ " (count tight) " node(s) below the " headroom-mb " MiB floor — a load there will"
                      " reach 'listening' and then die on the first request:"))
        (doseq [[h p] tight] (println (str "    " h " free " (:free-mb p) " MiB"))))
      (when (seq unreach)
        (println (str "⚠ " (count unreach) " unreachable: " (str/join ", " (map first unreach))
                      " — unreachable is NOT idle; do not count it as capacity")))
      (when (and (empty? tight) (empty? unreach)) (println "all reachable nodes above the floor")))))

;; --- 片付け -----------------------------------------------------------------

(defn- cmd-reap! []
  (let [apply? (flag? "--apply")
        orphans? (flag? "--orphans")]
    (println (if apply? "REAPING" "DRY RUN (add --apply to act)"))
    (doseq [h (cons head minis)]
      (let [p (probe h)]
        (when-not (:unreachable p)
          ;; 1) 自分が記録したもの
          (let [managed (:managed p)]
            (when (seq managed)
              (println (str "  " h " managed: " (str/join "," managed)))
              (when apply?
                (doseq [m managed]
                  (sh! h (str "pid=$(cat ~/.murakumo/managed/" m " 2>/dev/null); "
                              "[ -n \"$pid\" ] && kill $pid 2>/dev/null; rm -f ~/.murakumo/managed/" m))))))
          ;; 2) 記録に無い推論プロセス —— **既定では報告だけ**
          (when (and orphans? (pos? (:procs p)) (empty? (:managed p)))
            ;; **head は既定で殺さない。** gad の :8090 は systemd の
            ;; murakumo-ring.service = `murakumo-main` の実体で、止めると
            ;; フリート全体の推論が落ちる。モデルの入れ替えは
            ;; `murakumo-default-model.cljs` の仕事（退避と自動 revert がある）。
            (let [protected? (= h head)]
              (println (str "  " h " UNTRACKED inference process(es), rss " (:rss-gb p) "G"
                            (cond protected? " — PROTECTED (head; use murakumo-default-model.cljs)"
                                  apply? " -> stopping"
                                  :else " (report only; --apply to stop)")))
              (when (and apply? (not protected?))
                (sh! h "pkill -f 'llama-server -m'")))))))
    (when-not apply? (println "nothing was stopped"))))

;; --- 載せる -----------------------------------------------------------------

(defn- verify-generation!
  "**本物の生成でだけ ready と綴る。** listening は遅延確保なので当てにならない。"
  [host port tries]
  (loop [i 0]
    (if (>= i tries)
      {:error "no completion within the wait window"}
      (let [{:keys [out]} (sh! host (str "curl -sS --max-time 90 http://127.0.0.1:" port
                                         "/v1/chat/completions -H 'content-type: application/json' "
                                         "-d '{\"model\":\"x\",\"max_tokens\":32,\"temperature\":0,"
                                         "\"chat_template_kwargs\":{\"enable_thinking\":false},"
                                         "\"messages\":[{\"role\":\"user\",\"content\":\"Count to 10.\"}]}'")
                            120)]
        (cond
          (str/includes? out "\"choices\"")
          {:ok true :tps (some-> (re-find #"\"predicted_per_second\":([0-9.]+)" out) second js/parseFloat)}
          (str/includes? out "Compute error")
          ;; これは「まだロード中」ではない。**メモリが足りずに死んだ**。待っても直らない。
          {:error "Compute error — the device ran out of memory after the server started listening"}
          :else (do (sh! host "sleep 10" 20) (recur (inc i))))))))

(defn- cmd-serve! [node model]
  (let [port (opt "--port" "8096")
        ctx (opt "--ctx" "16384")
        mmproj (opt "--mmproj" nil)
        p (probe node)]
    (cond
      (:unreachable p) (do (println "FAIL" node "unreachable") (set! (.-exitCode js/process) 1))
      :else
      (let [{:keys [out]} (sh! node (str "stat -f%z " model " 2>/dev/null || stat -c%s " model " 2>/dev/null"))
            model-mb (quot (js/parseInt (or (not-empty out) "0")) 1048576)
            mm-mb (if mmproj
                    (quot (js/parseInt (or (not-empty (:out (sh! node (str "stat -f%z " mmproj " 2>/dev/null")))) "0")) 1048576)
                    0)
            need (+ model-mb mm-mb headroom-mb)]
        (println (str node ": free " (:free-mb p) " MiB · model " model-mb
                      " · mmproj " mm-mb " · headroom " headroom-mb " -> need " need))
        (cond
          (zero? model-mb)
          (do (println "FAIL model file not found on" node) (set! (.-exitCode js/process) 1))

          ;; **載せる前に断る。** 載せてから Compute error で落ちるより安い。
          (< (:free-mb p) need)
          (do (println (str "REFUSING to load: " (:free-mb p) " MiB free but " need " MiB needed."
                            " Free memory first (`reap`) or raise iogpu.wired_limit_mb."))
              (set! (.-exitCode js/process) 1))

          :else
          (do
            (sh! node (str "pkill -f 'llama-server -m' 2>/dev/null; mkdir -p ~/.murakumo/managed; sleep 2"))
            (sh! node (str "cd ~/.murakumo/bin9334 && nohup ./llama-server -m " model
                           (when mmproj (str " --mmproj " mmproj))
                           " -ngl 999 -c " ctx " --parallel 1 --host 0.0.0.0 --port " port
                           " --jinja --spec-type ngram-cache,ngram-simple"
                           " > /tmp/murakumo-" port ".log 2>&1 & echo $! > ~/.murakumo/managed/" port))
            (let [r (verify-generation! node port 24)]
              (if (:error r)
                (do (println "FAIL" (:error r))
                    ;; **自分で片付ける。** 壊れたサーバを置き去りにしない。
                    (sh! node (str "pid=$(cat ~/.murakumo/managed/" port "); kill $pid 2>/dev/null;"
                                   " rm -f ~/.murakumo/managed/" port))
                    (println "stopped the failed server and cleared its record")
                    (set! (.-exitCode js/process) 1))
                (println (str "OK " node ":" port " ctx=" ctx " · "
                              (.toFixed (or (:tps r) 0) 2) " tok/s · tracked in ~/.murakumo/managed/" port))))))))))

(defn- cmd-stop! [node]
  (let [port (opt "--port" "8096")
        {:keys [out]} (sh! node (str "pid=$(cat ~/.murakumo/managed/" port " 2>/dev/null);"
                                     " if [ -n \"$pid\" ]; then kill $pid 2>/dev/null; rm -f ~/.murakumo/managed/" port
                                     "; echo stopped; else echo 'no managed server on that port'; fi"))]
    (println node ":" out)))

(case cmd
  "audit" (cmd-audit!)
  "reap"  (cmd-reap!)
  "serve" (if-let [m (pos-arg 2)] (cmd-serve! (pos-arg 1) m)
            (do (println "usage: serve <node> <model.gguf> [--mmproj F] [--ctx N] [--port N]")
                (set! (.-exitCode js/process) 2)))
  "stop"  (if-let [n (pos-arg 1)] (cmd-stop! n)
            (do (println "usage: stop <node> [--port N]") (set! (.-exitCode js/process) 2)))
  (do (println "usage: murakumo-nodes.cljs audit | reap [--orphans] [--apply] | serve <node> <model> | stop <node>")
      ;; 2 = 何も実行していない
      (set! (.-exitCode js/process) 2)))
