#!/usr/bin/env nbb
;; murakumo-default-model.cljs — フリートの既定 LLM を 1 コマンドで切り替える。
;;
;; ADR-2607173100 の「モデル切替 = 1 entry の変更」を、**実際に serve している
;; プロセスまで含めて**成立させる。alias だけ書き換えても、その先が別のモデルを
;; 配っていれば嘘になる。
;;
;;   nbb scripts/murakumo-default-model.cljs status
;;   nbb scripts/murakumo-default-model.cljs list
;;   nbb scripts/murakumo-default-model.cljs bench [--tokens 120]
;;   nbb scripts/murakumo-default-model.cljs set <model-dir> [--distributed] [--parallel N] [--ctx N]
;;   nbb scripts/murakumo-default-model.cljs revert
;;
;; `<model-dir>` は gad の `/home/gad/models/` 直下のディレクトリ名。
;;
;; ## 2026-08-15 に手で踏んだ罠。**全部ここに埋めてある**
;;
;; 1. **`/health` は嘘をつく。** ロード中の llama-server は 503 を返し、`curl` は
;;    `-f` 無しだと 503 で exit 0 する。「healthy after 5s」と読めてしまうが、
;;    実際には重みを 1 バイトも読んでいなかった。**readiness は本物の生成が
;;    返ったことで判定する**（`ready?`）。
;; 2. **バイナリで 2 倍違う。** RPC ビルド（`build-rpc-glm`）と Vulkan ビルド
;;    （`llamacpp-vk`）は同じ引数でも 6.4 vs 13.0 tok/s だった。ローカル実行では
;;    **必ず Vulkan ビルド**（`LD_LIBRARY_PATH` も要る）。
;; 3. **`--parallel 2` はこの APU で per-request をほぼ半減させる**（13.0 -> 6.7）。
;;    aggregate は増えないので、既定は 1。MoE（active params が小さい）なら
;;    2 でも割に合ったが、dense では合わない。**モデルの形で決まるので測ること。**
;; 4. **1 台に載る dense モデルを ring に分散させると遅くなる。** ring は
;;    「1 台に載らないモデルのために 9 台の 16GB を束ねる」ためのもので、
;;    載るモデルでは層ごとの往復を足すだけ（11.7 -> 4.7 tok/s の実測）。
;;    `--distributed` は**明示したときだけ**使う。
;; 5. **reasoning モデルは黙って予算を食う。** thinking が既定 on だと
;;    `content` が空のまま `max_tokens` を使い切る。計測は必ず
;;    `chat_template_kwargs.enable_thinking=false` で行う。
;; 6. **registry は別の場所にあり、別の資格情報で守られている。** `set` は
;;    serve を切り替えるが、`api.murakumo.cloud` の alias メタデータ
;;    （`parallel` / `context` / `vision` / `fleet`）は `MURAKUMO_SERVICE_TOKEN`
;;    が無いと書けない。**書けなかったことを黙らない** —— 食い違いを一覧で出す。
;;
;; ## 安全側の設計
;;
;; * `set` は切替**前**に現 drop-in をタイムスタンプ付きで退避し、readiness が
;;   取れなければ **自動で戻す**。「落ちたまま放置」を構造的に作らない。
;; * 切替前後の tok/s を必ず測って両方出す。**測らずに成功と言わない。**
;; * exit 0=切替成功 / 1=失敗（自動 revert 済み）/ 2=**判定できなかった**。

(ns murakumo-default-model
  (:require [clojure.string :as str]
            ["node:child_process" :as cp]))

(def args (vec *command-line-args*))
(def cmd (first (remove #(str/starts-with? % "--") args)))

(defn- opt [flag default]
  (let [i (.indexOf args flag)] (if (neg? i) default (get args (inc i)))))
(defn- flag? [f] (not (neg? (.indexOf args f))))

;; --- 固定値。ここだけがホスト依存 ------------------------------------------
(def host      (opt "--host" "gad"))
(def model-root "/home/gad/models")
(def vk-bin    "/home/gad/llamacpp-vk/llama-server")
(def vk-lib    "/home/gad/llamacpp-vk")
(def rpc-bin   "/home/gad/murakumo/llama.cpp/build-rpc-glm/bin/llama-server")
(def rpc-nodes (str "192.168.1.25:50052,192.168.1.24:50052,192.168.1.21:50052,"
                    "192.168.1.26:50052,192.168.1.17:50052,192.168.1.23:50052,"
                    "192.168.1.18:50052,192.168.1.20:50052,192.168.1.22:40052"))
(def rpc-split "3,3,3,3,4,3,3,3,3,12")
(def unit      "murakumo-ring.service")
(def dropin    (str "/etc/systemd/system/" unit ".d/qwen38.conf"))
(def port      8090)
(def public-ep "https://infer.murakumo.cloud/v1/chat/completions")
(def alias-url "https://api.murakumo.cloud/infer/models/murakumo-main")

(defn- ssh! [script]
  (let [r (cp/spawnSync "ssh" (clj->js ["-o" "BatchMode=yes" "-o" "ConnectTimeout=10" host script])
                        #js {:encoding "utf8" :maxBuffer (* 32 1024 1024)})]
    {:exit (or (aget r "status") 1)
     :out (str (aget r "stdout")) :err (str (aget r "stderr"))}))

;; --- 計測 -------------------------------------------------------------------
;; **本物の生成でしか readiness を判定しない**（罠 1）。thinking は必ず切る（罠 5）。

(defn- complete! [tokens]
  (-> (js/fetch public-ep
                #js {:method "POST"
                     :headers #js {"content-type" "application/json"}
                     :body (js/JSON.stringify
                            (clj->js {:model "murakumo-main" :max_tokens tokens :temperature 0
                                      :chat_template_kwargs {:enable_thinking false}
                                      :messages [{:role "user"
                                                  :content "Count from 1 to 40, comma separated, nothing else."}]}))})
      (.then #(.json %))
      (.then (fn [j] (let [m (js->clj j :keywordize-keys true)]
                       (if-let [name (:model m)]
                         {:model name
                          :tok (get-in m [:usage :completion_tokens])
                          :tps (get-in m [:timings :predicted_per_second])}
                         {:error (or (:error m) "no model field in the response")}))))
      (.catch (fn [e] {:error (.-message e)}))))

(defn- wait-ready!
  "本物の生成が返るまで待つ。-> {:model :tps} か {:error}。**503 を ready と読まない。**"
  [max-s]
  (let [deadline (+ (js/Date.now) (* 1000 max-s))]
    (letfn [(step []
              (-> (complete! 120)
                  (.then (fn [r]
                           (cond
                             (:model r) r
                             (> (js/Date.now) deadline) {:error (str "not ready within " max-s "s — last: " (:error r))}
                             :else (js/Promise. (fn [res] (js/setTimeout #(res (step)) 10000))))))))]
      (step))))

;; --- 状態 -------------------------------------------------------------------

(defn- running-args []
  (let [r (ssh! (str "ps -eo args | grep 'port " port "' | grep -v grep | head -1"))]
    (str/trim (:out r))))

(defn- registry []
  (-> (js/fetch alias-url) (.then #(.json %))
      (.then #(js->clj % :keywordize-keys true))
      (.catch (fn [e] {:error (.-message e)}))))

(defn- model-of [argv]
  (when-let [m (re-find #"-m\s+(\S+)" argv)] (second m)))

(defn- drift-report [argv reg]
  "serve している実態と registry が言っていることの食い違い。**黙らない**（罠 6）。"
  (let [running-model (some-> (model-of argv) (str/split #"/") last)
        parallel (some-> (re-find #"--parallel\s+(\d+)" argv) second)
        ctx (some-> (re-find #"-c\s+(\d+)" argv) second)
        vision? (str/includes? argv "--mmproj")
        distributed? (str/includes? argv "--rpc")
        rows (cond-> []
               ;; **前方一致や「qwen が入っていれば OK」で判定しない。** 最初の版は
               ;; alias-for を `-` で切った先頭語（"qwen"）が動作中のファイル名に
               ;; 含まれるかを見ていたので、`qwen-agentworld-35b-a3b` と
               ;; `Qwen3.8-27B-Q4_K_M.gguf` を **一致と判定して黙った**（2026-08-15、
               ;; まさにこの gate が検出すべきだった食い違いを見逃した）。
               ;; 区切り文字を落として全体を突き合わせる。
               (and running-model (:alias-for reg)
                    (not (str/includes? (str/replace (str/lower-case running-model) #"[^a-z0-9]" "")
                                        (str/replace (str/lower-case (:alias-for reg)) #"[^a-z0-9]" ""))))
               (conj [":alias-for" (:alias-for reg) running-model])
               (and parallel (:parallel reg) (not= (js/parseInt parallel) (:parallel reg)))
               (conj [":parallel" (:parallel reg) parallel])
               (and ctx (:context reg) (not= (js/parseInt ctx) (:context reg)))
               (conj [":context" (:context reg) ctx])
               (and (some? (:vision reg)) (not= (:vision reg) vision?))
               (conj [":vision" (:vision reg) vision?])
               (and (:fleet reg) (not distributed?))
               (conj [":fleet" (str/join "," (:fleet reg)) (str host " only (not distributed)")]))]
    rows))

(defn- cmd-status! []
  (let [argv (running-args)]
    (println "serving :" (or (model-of argv) "(nothing on port " port ")"))
    (println "binary  :" (or (first (str/split argv #"\s")) "?")
             (if (str/includes? argv "llamacpp-vk") "(Vulkan — the fast one)" "(⚠ not the Vulkan build; measured 2x slower)"))
    (println "mode    :" (if (str/includes? argv "--rpc") "distributed over the RPC ring" (str "local on " host))
             "· parallel" (or (some-> (re-find #"--parallel\s+(\d+)" argv) second) "?")
             "· ctx" (or (some-> (re-find #"-c\s+(\d+)" argv) second) "?"))
    (-> (registry)
        (.then (fn [reg]
                 (let [rows (drift-report argv reg)]
                   (if (empty? rows)
                     (println "registry: agrees with what is running")
                     (do (println "")
                         (println "⚠ registry METADATA IS STALE — api.murakumo.cloud advertises:")
                         (doseq [[k said actual] rows]
                           (println (str "    " k " says " (pr-str said) " · actually " (pr-str actual))))
                         (println "  Fixing it needs MURAKUMO_SERVICE_TOKEN (absent from kagi as of 2026-08-15;")
                         (println "  see .claude/skills/secrets-location-map/references/murakumo.md).")))
                   (complete! 120))))
        (.then (fn [r]
                 (if (:error r)
                   (do (println "measure : FAILED —" (:error r))
                       (set! (.-exitCode js/process) 2))
                   (println "measure :" (.toFixed (:tps r) 2) "tok/s decode")))))))

(defn- cmd-list! []
  (println (:out (ssh! (str "ls -d " model-root "/*/ | sed 's|.*/models/||;s|/$||'"))))
  (println "use:  set <name> [--distributed] [--parallel N] [--ctx N]"))

(defn- exec-line [dir {:keys [distributed? parallel ctx gguf mmproj]}]
  (str/join " "
    (remove nil?
      [vk-bin  ;; Vulkan ビルドは --rpc も持つ（実測 2026-08-15）。RPC 専用ビルドは使わない
       "-m" (str model-root "/" dir "/" gguf)
       (when mmproj (str "--mmproj " model-root "/" dir "/" mmproj))
       (when distributed? (str "--rpc " rpc-nodes))
       (when distributed? "--split-mode layer")
       (when distributed? (str "--tensor-split " rpc-split))
       "-ngl 999" (str "-c " ctx) (str "--parallel " parallel)
       "--host 0.0.0.0" (str "--port " port) "--jinja"])))

(defn- cmd-set! [dir]
  (let [distributed? (flag? "--distributed")
        parallel (opt "--parallel" "1")
        ctx (opt "--ctx" "32768")
        ls (:out (ssh! (str "ls -S " model-root "/" dir "/*.gguf 2>/dev/null | xargs -n1 basename")))
        files (remove str/blank? (str/split-lines (str/trim ls)))
        gguf (first (remove #(str/starts-with? % "mmproj") files))
        mmproj (first (filter #(str/starts-with? % "mmproj") files))]
    (cond
      (empty? files)
      (do (println "FAIL no .gguf under" (str model-root "/" dir)) (set! (.-exitCode js/process) 1))

      (nil? gguf)
      (do (println "FAIL only an mmproj found under" dir "— no weights") (set! (.-exitCode js/process) 1))

      :else
      (let [before (running-args)
            stamp (.replace (.toISOString (js/Date.)) #"[:.]" "-")
            backup (str "/tmp/murakumo-dropin-" stamp ".bak")
            line (exec-line dir {:distributed? distributed? :parallel parallel :ctx ctx
                                 :gguf gguf :mmproj mmproj})]
        (println "current :" (or (model-of before) "(none)"))
        (println "target  :" gguf (if mmproj (str "+ " mmproj) "") )
        (println "mode    :" (if distributed? "distributed (RPC ring)" (str "local on " host))
                 "· parallel" parallel "· ctx" ctx)
        (when (and distributed? (not (flag? "--i-measured-this")))
          (println "")
          (println "NOTE: distributing a model that fits one host measured 2.5x SLOWER")
          (println "      (11.7 -> 4.7 tok/s on 2026-08-15). Only use --distributed for a")
          (println "      model too large for" host "— and measure it."))
        (-> (complete! 60)
            (.then (fn [b] (println "before  :" (if (:tps b) (str (.toFixed (:tps b) 2) " tok/s") "(not serving)")) b))
            (.then
             (fn [before-m]
               ;; 退避 -> 書き込み -> restart。退避を先にやる（戻せない状態を作らない）。
               (let [w (ssh! (str "sudo cp " dropin " " backup " 2>/dev/null; "
                                  "sudo mkdir -p $(dirname " dropin ") && "
                                  "printf '%s\\n' "
                                  "'# written by scripts/murakumo-default-model.cljs at " stamp "' "
                                  "'# revert: sudo cp " backup " " dropin " (or rm it for the base unit)' "
                                  "'[Service]' "
                                  (str "'Environment=\"LD_LIBRARY_PATH=" vk-lib "\"' ")
                                  "'ExecStartPre=' 'ExecStart=' "
                                  "'ExecStart=" line "' "
                                  "| sudo tee " dropin " >/dev/null && "
                                  "sudo systemctl daemon-reload && sudo systemctl restart " unit
                                  " && echo SWITCHED"))]
                 (if-not (str/includes? (:out w) "SWITCHED")
                   (do (println "FAIL could not apply —" (str/trim (str (:err w) (:out w))))
                       (set! (.-exitCode js/process) 1)
                       nil)
                   (-> (wait-ready! 420)
                       (.then
                        (fn [r]
                          (if (:error r)
                            ;; **自動 revert。** 落ちたまま放置しない。
                            (let [rv (ssh! (str "sudo cp " backup " " dropin " 2>/dev/null || sudo rm -f " dropin "; "
                                                "sudo systemctl daemon-reload && sudo systemctl restart " unit
                                                " && echo REVERTED"))]
                              (println "FAIL" (:error r))
                              (println "auto-revert:" (if (str/includes? (:out rv) "REVERTED") "done" "FAILED — fix by hand"))
                              (set! (.-exitCode js/process) 1))
                            (do
                              (println "after   :" (.toFixed (:tps r) 2) "tok/s decode ·" (last (str/split (:model r) #"/")))
                              (when (and (:tps before-m) (< (:tps r) (* 0.8 (:tps before-m))))
                                (println "")
                                (println "⚠ this is" (.toFixed (* 100 (- 1 (/ (:tps r) (:tps before-m)))) 0)
                                         "% SLOWER than what it replaced. Revert with:")
                                (println "    nbb scripts/murakumo-default-model.cljs revert"))
                              (println "")
                              (println "registry metadata was NOT updated (needs MURAKUMO_SERVICE_TOKEN).")
                              (println "run `status` to see exactly which fields now disagree.")))))))))))))))

(defn- cmd-revert! []
  (let [r (ssh! (str "ls -t /tmp/murakumo-dropin-*.bak 2>/dev/null | head -1"))
        b (str/trim (:out r))]
    (if (str/blank? b)
      (do (println "no backup found — removing the drop-in returns to the base unit")
          (let [x (ssh! (str "sudo rm -f " dropin " && sudo systemctl daemon-reload && sudo systemctl restart " unit " && echo OK"))]
            (println (if (str/includes? (:out x) "OK") "reverted to the base unit" "FAILED"))))
      (let [x (ssh! (str "sudo cp " b " " dropin " && sudo systemctl daemon-reload && sudo systemctl restart " unit " && echo OK"))]
        (println (if (str/includes? (:out x) "OK") (str "restored " b) "FAILED"))))
    (-> (wait-ready! 420)
        (.then (fn [r] (if (:error r)
                         (do (println "FAIL still not serving —" (:error r)) (set! (.-exitCode js/process) 1))
                         (println "serving:" (last (str/split (:model r) #"/")) "·" (.toFixed (:tps r) 2) "tok/s")))))))

(case cmd
  "status" (cmd-status!)
  "list"   (cmd-list!)
  "bench"  (-> (complete! (js/parseInt (opt "--tokens" "120")))
               (.then (fn [r] (if (:error r)
                                (do (println "FAIL" (:error r)) (set! (.-exitCode js/process) 2))
                                (println (last (str/split (:model r) #"/")) "·" (.toFixed (:tps r) 2) "tok/s")))))
  "set"    (if-let [d (second (remove #(str/starts-with? % "--") args))]
             (cmd-set! d)
             (do (println "FAIL: set needs a model directory name (see `list`)")
                 (set! (.-exitCode js/process) 2)))
  "revert" (cmd-revert!)
  (do (println "usage: murakumo-default-model.cljs status|list|bench|set <dir>|revert")
      ;; 2 = 何も実行していない（成功とも失敗とも綴らない）
      (set! (.-exitCode js/process) 2)))
