#!/usr/bin/env nbb
;; murakumo-codegen.cljs — murakumo fleet の LLM に**並列に**下書きを書かせる。
;;
;; ADR-2607173100 に従い、**concrete な model id を焼かない**。解決順は
;;   ① env override（`MURAKUMO_ENDPOINT` / `MURAKUMO_MODEL`）
;;   ② `murakumo-main` alias（`GET https://api.murakumo.cloud/infer/models/murakumo-main`）
;;   ③ fallback は **endpoint だけ**を焼く（endpoint 先の serving モデルに従う）
;; モデル切替は KV の alias 1 entry の PUT で済み、この script は次回実行から追従する。
;;
;; ## 使い方
;;
;;   nbb scripts/murakumo-codegen.cljs --jobs-file jobs.edn [--jobs N] [--max-tokens N]
;;   nbb scripts/murakumo-codegen.cljs --prompt-file p.txt --out draft.kotoba
;;   nbb scripts/murakumo-codegen.cljs --probe          # 疎通と実効 tok/s だけ測る
;;
;; jobs.edn は job の vector:
;;   [{:id "score-core" :out "/tmp/score_core.kotoba"
;;     :system "..." :prompt "..." :max-tokens 3000}]
;;
;; ## 並列度は **alias が申告する値**を既定にする（定数で持たない）
;;
;; 2026-08-15 実測: alias は `:parallel 2` を申告し、実効 11.7 tok/s だった。
;; **この数をここに焼かない** —— ring のノード数は日によって変わる。既定は
;; alias の申告値、`--jobs` で上書き。申告が無ければ 1（並べないほうが、
;; 並べて 429 を食うより速い）。
;;
;; ## reasoning モデルであることの扱い
;;
;; 現行 main（`qwen-agentworld-35b-a3b`）は reasoning モデルで、既定では
;; `content` が空のまま `reasoning_content` にトークンを吐き、`max_tokens` を
;; 思考だけで使い切る（実測: 120 token の予算が全部思考に消えて content 空）。
;; **`chat_template_kwargs.enable_thinking=false` を既定にする** —— コード生成では
;; 思考の可視化に価値が無く、予算を食うだけ。`--thinking` で戻せる。
;;
;; ## 空の出力を成功と綴らない
;;
;; content が空/空白だけの job は **書き込まず失敗として数える**（ADR-2608136000）。
;; 「モデルが答えなかった」と「モデルが空ファイルを正解として返した」を
;; 出力で区別できないと、下流は空ファイルをレビューしてしまう。
;; exit code は 0=全部成功 / 1=1 件以上失敗 / **2=1 件も走らせていない**。

(ns murakumo-codegen
  (:require [clojure.string :as str]
            [clojure.edn :as edn]
            ["node:fs" :as fs]
            ["node:path" :as path]))

(def args (vec *command-line-args*))

(defn- opt [flag default]
  (let [i (.indexOf args flag)]
    (if (neg? i) default (get args (inc i)))))

(defn- flag? [f] (not (neg? (.indexOf args f))))

(def alias-url
  (or (aget js/process.env "MURAKUMO_ALIAS_URL")
      "https://api.murakumo.cloud/infer/models/murakumo-main"))

;; ③ の fallback。**endpoint だけ**。model 名は焼かない。
(def fallback-endpoint "https://infer.murakumo.cloud/v1/chat/completions")

(defn- resolve-target! []
  (let [env-ep (aget js/process.env "MURAKUMO_ENDPOINT")
        env-model (aget js/process.env "MURAKUMO_MODEL")]
    (if env-ep
      (js/Promise.resolve {:endpoint env-ep
                           :model (or env-model "murakumo-main")
                           :parallel 1
                           :source :env})
      (-> (js/fetch alias-url)
          (.then #(.json %))
          (.then (fn [j]
                   (let [m (js->clj j :keywordize-keys true)]
                     {:endpoint (or (:endpoint m) fallback-endpoint)
                      ;; alias 名を送る。worker 側が KV で解決するので、ここで
                      ;; `alias-for` の concrete id を送ると切替に追従しなくなる。
                      :model (or env-model "murakumo-main")
                      :alias-for (:alias-for m)
                      :status (:status m)
                      :parallel (or (:parallel m) 1)
                      :source :alias})))
          (.catch (fn [e]
                    (println "WARN alias unreachable (" (.-message e) ") — falling back to the endpoint only")
                    {:endpoint fallback-endpoint
                     :model (or env-model "murakumo-main")
                     :parallel 1
                     :source :fallback}))))))

(defn- complete!
  "1 job を投げて {:content :usage :tps} か {:error} を返す。"
  [target {:keys [system prompt max-tokens temperature thinking?]}]
  (let [body (cond-> {:model (:model target)
                      :max_tokens (or max-tokens 3000)
                      :temperature (or temperature 0)
                      :messages (cond-> []
                                  system (conj {:role "system" :content system})
                                  true (conj {:role "user" :content prompt}))}
               (not thinking?) (assoc :chat_template_kwargs {:enable_thinking false}))]
    (-> (js/fetch (:endpoint target)
                  #js {:method "POST"
                       :headers #js {"content-type" "application/json"}
                       :body (js/JSON.stringify (clj->js body))})
        (.then (fn [r]
                 (if-not (.-ok r)
                   (-> (.text r)
                       (.then (fn [t]
                                ;; **本文を捨てない** —— status だけ記録する経路は、
                                ;; 原因が応答の中に書いてあっても読まない
                                ;; （ADR-2608136000 の 3 番目）。
                                {:error (str "HTTP " (.-status r) " — "
                                             (subs t 0 (min 400 (count t))))})))
                   (.json r))))
        (.then (fn [j]
                 (if (:error j)
                   j
                   (let [m (js->clj j :keywordize-keys true)
                         msg (get-in m [:choices 0 :message])
                         content (str (:content msg))
                         reasoning (str (:reasoning-content msg) (:reasoning_content msg))]
                     (if (str/blank? content)
                       {:error (str "the model returned no content"
                                    (when-not (str/blank? reasoning)
                                      (str " (it spent the budget on reasoning; finish_reason="
                                           (get-in m [:choices 0 :finish_reason]) ")")))}
                       {:content content
                        :finish (get-in m [:choices 0 :finish_reason])
                        :usage (:usage m)
                        :tps (get-in m [:timings :predicted_per_second])})))))
        (.catch (fn [e] {:error (str "request failed: " (.-message e))})))))

(defn- run-pool!
  "bounded concurrency。Promise.all で全部同時に投げない —— ring の申告並列度を
   超えると queue に積まれるだけで、速くならず失敗の診断だけ難しくなる。"
  [target jobs n]
  (let [remaining (atom (vec jobs))
        results (atom [])
        worker (fn worker []
                 (let [job (first @remaining)]
                   (if (nil? job)
                     (js/Promise.resolve nil)
                     (do (swap! remaining subvec 1)
                         (let [t0 (js/Date.now)]
                           (-> (complete! target job)
                               (.then (fn [r]
                                        (let [secs (/ (- (js/Date.now) t0) 1000.0)
                                              r (assoc r :id (:id job) :out (:out job) :secs secs)]
                                          (if (:error r)
                                            (println "FAIL" (:id job) "—" (:error r))
                                            (do (when (:out job)
                                                  (fs/mkdirSync (path/dirname (:out job)) #js {:recursive true})
                                                  (fs/writeFileSync (:out job) (:content r)))
                                                (println (str "ok   " (:id job)
                                                              "  " (get-in r [:usage :completion_tokens]) " tok"
                                                              "  " (.toFixed secs 1) "s"
                                                              (when-let [t (:tps r)] (str "  " (.toFixed t 1) " tok/s"))
                                                              (when (= "length" (:finish r)) "  ⚠ TRUNCATED (max_tokens)")
                                                              (when (:out job) (str "  -> " (:out job)))))))
                                          (swap! results conj r))
                                        (worker)))))))))]
    (-> (js/Promise.all (clj->js (map (fn [_] (worker)) (range (max 1 n)))))
        (.then (fn [_] @results)))))

(defn- probe! [target]
  (-> (complete! target {:id "probe"
                         :prompt "Output only this line and nothing else: MURAKUMO-CODEGEN-OK"
                         :max-tokens 64})
      (.then (fn [r]
               (if (:error r)
                 (do (println "FAIL probe —" (:error r)) (set! (.-exitCode js/process) 1))
                 (println "probe:" (str/trim (:content r))
                          "· tok/s" (some-> (:tps r) (.toFixed 1))))))))

(defn- main! []
  (-> (resolve-target!)
      (.then
       (fn [target]
         (println (str "target " (:endpoint target)
                       " · model " (:model target)
                       (when (:alias-for target) (str " (alias-for " (:alias-for target) ")"))
                       (when (:status target) (str " · " (:status target)))
                       " · resolved via " (name (:source target))
                       " · declared parallel " (:parallel target)))
         (cond
           (flag? "--probe") (probe! target)

           :else
           (let [jobs (cond
                        (opt "--jobs-file" nil)
                        (edn/read-string (str (fs/readFileSync (opt "--jobs-file" nil) "utf8")))

                        (opt "--prompt-file" nil)
                        [{:id "single"
                          :out (opt "--out" nil)
                          :prompt (str (fs/readFileSync (opt "--prompt-file" nil) "utf8"))}]

                        :else nil)
                 mt (some-> (opt "--max-tokens" nil) js/parseInt)
                 jobs (mapv #(cond-> (assoc % :thinking? (flag? "--thinking"))
                               mt (assoc :max-tokens mt))
                            (or jobs []))
                 n (js/parseInt (opt "--jobs" (str (:parallel target))))]
             (if (empty? jobs)
               (do (println "FAIL no jobs — pass --jobs-file, --prompt-file, or --probe")
                   ;; 2 = 「答えられなかった」。0 でも 1 でもない
                   ;; （何も走らせていないことを成功とも失敗とも綴らない）。
                   (set! (.-exitCode js/process) 2))
               (do
                 (println (str "running " (count jobs) " job(s) at concurrency " n))
                 (-> (run-pool! target jobs n)
                     (.then (fn [rs]
                              (let [bad (filter :error rs)]
                                (println (str "ran " (count rs) " job(s), " (count bad) " failing"))
                                (when (not= (count rs) (count jobs))
                                  (println "FAIL only" (count rs) "of" (count jobs) "jobs reported back")
                                  (set! (.-exitCode js/process) 1))
                                (when (seq bad)
                                  (set! (.-exitCode js/process) 1))))))))))))))

(main!)
