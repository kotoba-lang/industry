#!/usr/bin/env nbb
;; Vast.ai 調達計測ハーネス
;;
;; ## なぜこれが要るのか
;;
;; ADR-2608026300 の XMILE は、最適価格が 2〜10 credits/秒 に散らばる原因を
;; 「H100 の wall time が未計測」という **たった 1 つの数値** に絞り込んだ。
;; 掲示価格・plan の窓（`murakumo.infer.windows`）・寄与率の下限、いずれも
;; この数値の上に載っているのに、今日まで誰も測っていない。
;;
;; 加えて 2026-08-03 の調達調査で、serverless（scale-to-zero）と成人向けを
;; 禁止しない規約の両方を満たすのは Vast.ai だけ、という交差が判明した
;; （Modal は §1.4(c) "indecent or obscene"、RunPod は明示禁止 + lifetime ban、
;; TensorDock は規約は通るが serverless が無い）。つまり Vast.ai の実測は
;; 価格の話であると同時に、調達先が実在するかの話でもある。
;;
;; ## 一番大事な性質: インスタンスを必ず破棄する
;;
;; 計測は $0.20 で終わるが、**破棄に失敗したインスタンスは月 $260 を課金し
;; 続ける**。3 桁違う失敗なので、この harness の最重要機能は計測ではなく
;; `destroy!` の到達保証である:
;;
;;   - 計測本体を `with-destroy!` で包み、成功でも例外でも破棄する
;;   - 破棄の成否を戻り値ではなく **API に問い合わせて確認**する
;;   - 確認できなければ **非ゼロ終了 + instance id を stderr に叫ぶ**
;;     （黙って成功と報告するくらいなら失敗として叫ぶ）
;;   - `orphans` でいつでも取り残しを一覧・破棄できる
;;
;; ## 使い方
;;
;;   export VAST_API_KEY=...          # 鍵はリポジトリに置かない
;;   nbb 90-docs/procurement/vast-measure.cljs offers "RTX 4090"
;;   nbb 90-docs/procurement/vast-measure.cljs orphans [--destroy]
;;   nbb 90-docs/procurement/vast-measure.cljs measure "RTX 4090"

(ns vast-measure
  (:require [clojure.string :as str]
            [clojure.pprint :as pp]
            ["process" :as process]))

(def base "https://console.vast.ai/api/v0")

(defn- fail!
  "終了コードを立てる。

   ⚠ `(set! (.-exitCode process) n)` は **nbb では落ちる** ——
   ns の `:require` で取り込んだ process は ES module の名前空間オブジェクトで、
   プロパティが読み取り専用だから（TypeError: Cannot assign to property
   exitCode of [object Module]）。グローバルの `js/process` を使う。

   ⚠⚠ この docstring 自体が 2 度目の事故を起こした。最初の版は
   module 指定子を生の二重引用符で囲んで書いており、**docstring が
   そこで終わって残りが引数リストとして読まれた**（`process is not
   ISeqable`）。同じセッションで ADR の EDN でも 3 回踏んだ欠陥クラスで、
   docstring に生の二重引用符を置くと、壊れ方が「その行が読めない」ではなく
   「別の構文として読める」になるので気付きにくい。

   実測 2026-08-03: この 1 行のせいで、計測失敗時のエラーハンドラ自身が
   例外を投げ、**破棄の成否を報告する経路が壊れていた**。破棄そのものは
   動いていたが（実測でインスタンス 0 / 消費 $0.0036）、動いたことを
   確認できない安全網は安全網ではない。"
  [n]
  (set! (.-exitCode js/process) n))

(defn api-key []
  (or (some-> (.-VAST_API_KEY (.-env js/process)) str/trim not-empty)
      (throw (ex-info "VAST_API_KEY が未設定。鍵をリポジトリに書かないこと" {}))))

(defn- headers []
  #js {"Authorization" (str "Bearer " (api-key))
       "content-type" "application/json"})

(defn- now [] (.getTime (js/Date.)))
(defn- sleep [ms] (js/Promise. (fn [res] (js/setTimeout res ms))))

(def ^:private max-429-retries 6)

(defn- req!
  "Vast.ai API 呼び出し。non-2xx は例外にする —— 沈黙で nil を返すと、
   破棄の失敗が『成功』に化ける。

   ## 429 は例外にせず待って再試行する

   Vast.ai のレート上限は **3 リクエスト**（実測: `limit 3.0`、
   `retry_after 10`）。5 秒間隔のポーリングで即座に踏んだ。

   429 を単なるエラーにすると、**`destroy!` の確認経路が 429 を引いた瞬間に
   『破棄されていない』と誤報する** —— 実際には破棄されているのに、運用者は
   コンソールを見に行かされる。逆に確認を省けば、本物の取り残しを見逃す。
   どちらも避けるには、確認そのものが 429 に耐える必要がある。

   応答の `retry_after` に従う（自前の推測値を使わない）。上限回数で打ち切り、
   打ち切りは例外にする —— 無限再試行は課金の垂れ流しと同義。"
  ([method path opts] (req! method path opts 0))
  ([method path {:keys [body query] :as opts} attempt]
   (let [url (str base path
                  (when query
                    (str "?q=" (js/encodeURIComponent (js/JSON.stringify (clj->js query))))))
         init #js {:method (str/upper-case (name method)) :headers (headers)}]
     (when body (set! (.-body init) (js/JSON.stringify (clj->js body))))
     (-> (js/fetch url init)
         (.then (fn [^js r]
                  (cond
                    (.-ok r) (.json r)

                    (and (= 429 (.-status r)) (< attempt max-429-retries))
                    (.then (.text r)
                           (fn [t]
                             (let [after (or (some-> (re-find #"\"retry_after\":\s*(\d+)" t)
                                                     second
                                                     js/parseInt)
                                             10)]
                               (js/console.error (str ";; 429 — " after "s 待って再試行 ("
                                                      (inc attempt) "/" max-429-retries ") " path))
                               (.then (sleep (* 1000 (inc after)))
                                      (fn [_] (req! method path opts (inc attempt)))))))

                    :else
                    (.then (.text r)
                           (fn [t]
                             ;; 原因は **メッセージ本体**に載せる —— ex-data は
                             ;; sci のエラーラッパを通ると失われ、実測で
                             ;; 「vast api error」しか出ずに原因が消えた。
                             (throw (ex-info (str "vast api " (name method) " " path
                                                  " -> HTTP " (.-status r) " : "
                                                  (subs t 0 (min 300 (count t))))
                                             {:status (.-status r) :path path})))))))
         (.then (fn [j] (if (map? j) j (js->clj j :keywordize-keys true))))))))

;; ── オファー探索 ─────────────────────────────────────────────────────────────

(defn offers!
  "指定 GPU の on-demand オファーを安い順に。

   `reliability2` と `inet_down` で絞るのは価格のためではなく **計測の妥当性**
   のため —— 信頼性の低いホストを引くと、測っているのが GPU の速度なのか
   ホストの不調なのか区別できなくなる。"
  [gpu]
  (req! :get "/bundles/"
        {:query {:rentable {:eq true}
                 :num_gpus {:eq 1}
                 :gpu_name {:eq gpu}
                 :disk_space {:gte 60}
                 :reliability2 {:gte 0.97}
                 :inet_down {:gte 300}
                 :order [["dph_total" "asc"]]
                 :limit 100}}))

(defn summarize [gpu offers]
  (let [p (vec (sort (map :dph_total offers)))]
    {:gpu gpu
     :count (count p)
     :min-usd-per-hour (first p)
     :median-usd-per-hour (get p (quot (count p) 2))
     :gpu-ram-gb (some-> (:gpu_ram (first offers)) (/ 1024) js/Math.round)}))

;; ── 破棄（この harness の中核）───────────────────────────────────────────────

(defn instances! [] (req! :get "/instances/" {}))

(defn destroy!
  "インスタンスを破棄し、**API に問い合わせて消えたことを確認する**。

   DELETE が 200 を返したことと、課金が止まったことは別の主張である。
   確認できなければ例外にする —— id を必ず添えるので、握り潰されても
   `orphans` が後から拾える。"
  [id]
  (-> (req! :delete (str "/instances/" id "/") {})
      (.catch (fn [e]
                (js/console.error ";; destroy 呼び出しが失敗（確認は続行）:" (ex-message e))
                nil))
      (.then (fn [_] (sleep 3000)))
      (.then (fn [_] (instances!)))
      (.then (fn [{:keys [instances]}]
               (if (some (fn [i] (= id (:id i))) instances)
                 (throw (ex-info "インスタンスが破棄されていない — 課金が続いている"
                                 {:instance-id id
                                  :action "即座に手動で破棄せよ: https://cloud.vast.ai/instances/"}))
                 {:destroyed id})))))

(defn with-destroy!
  "`f` を走らせ、**成功でも例外でも** id を破棄する。破棄に失敗したら
   exitCode 2 と stderr で叫ぶ（黙って成功と報告しない）。"
  [id f]
  (-> (js/Promise.resolve (f))
      (.then (fn [res] (.then (destroy! id) (fn [_] res))))
      (.catch (fn [e]
                (-> (destroy! id)
                    (.catch (fn [de]
                              (js/console.error "!! 破棄失敗 !! 手動で破棄せよ: instance" id)
                              (js/console.error "!!" (ex-message de))
                              (fail! 2)
                              nil))
                    (.then (fn [_] (throw e))))))))

(defn orphans!
  "取り残されたインスタンスを一覧（`--destroy` で破棄）。
   計測が異常終了した後、**最初に叩くべきコマンド**。"
  [destroy?]
  (-> (instances!)
      (.then (fn [{:keys [instances]}]
               (if (empty? instances)
                 (do (println ";; 取り残しなし。") nil)
                 (do (pp/pprint (mapv (fn [i]
                                        (select-keys i [:id :gpu_name :dph_total :actual_status]))
                                      instances))
                     (when destroy?
                       (js/Promise.all
                        (into-array (map (fn [i] (destroy! (:id i))) instances))))))))))

;; ── 計測 ─────────────────────────────────────────────────────────────────────

(def comfy-image
  "ComfyUI + SDXL の測定用イメージ。**cold start の大半はこのイメージの取得**
   なので、何を測っているかを固定するために image を明示的に固定する。"
  "vastai/comfy:latest")

(def ^:private poll-interval-ms
  "起動待ちのポーリング間隔。**20 秒**。

   5 秒にしたら Vast.ai のレート上限（3 リクエスト）を即座に踏んだ（実測
   2026-08-03）。cold start は分単位なので、20 秒の粒度で困らない —— 細かく
   見たいという理由だけで API を叩く回数を増やすと、測りたかったものより先に
   レート上限を測ることになる。"
  20000)

(defn- wait-running
  "running になるまで待ち、**状態遷移を時刻付きで記録して返す**。

   最初の版は running かどうかだけを見て、上限超過で例外にしていた。実測
   （2026-08-03、`vastai/comfy:latest`）で 15 分待っても `loading` のままで、
   得られた情報は『15 分では終わらなかった』だけだった —— **どこで時間を
   使ったのかが分からない**ので、イメージが大きいのか、そのホストが遅いのか、
   マーケットのプロビジョニング自体が遅いのかを区別できない。

   区別できないと打ち手が決まらない: イメージが原因なら `cold_workers` で
   償却できるが、プロビジョニングが原因なら serverless の前提が崩れる。
   状態遷移を残せば、失敗しても次の判断材料になる。

   上限超過は引き続き例外（無限待ちは課金の垂れ流し）だが、**遷移履歴を
   例外データに載せる**ので、タイムアウトしても測定値は残る。"
  [id deadline-ms]
  (let [t0 (now)
        seen (atom [])]
    (letfn [(note! [st]
              (when (not= st (:status (peek @seen)))
                (swap! seen conj {:status st :at-ms (- (now) t0)})
                (js/console.error (str ";; " (js/Math.round (/ (- (now) t0) 1000)) "s  " st))))
            (poll []
              (-> (instances!)
                  (.then (fn [{:keys [instances]}]
                           (let [i (first (filter (fn [x] (= id (:id x))) instances))
                                 st (or (:actual_status i) "gone")]
                             (note! st)
                             (cond
                               (= "running" st)
                               {:ready-ms (- (now) t0) :status st :transitions @seen}

                               (> (- (now) t0) deadline-ms)
                               (throw (ex-info "起動が上限を超えた"
                                               {:instance-id id :status st
                                                :waited-ms (- (now) t0)
                                                :transitions @seen}))

                               :else
                               (.then (sleep poll-interval-ms) poll)))))))]
      (poll))))

(defn- rent!
  "オファーを借りて instance id を返す。"
  [o image]
  (-> (req! :put (str "/asks/" (:id o) "/")
            {:body {:client_id "me" :image image :disk 60 :runtype "ssh"}})
      (.then (fn [r]
               (or (:new_contract r) (:id r)
                   (throw (ex-info "instance id が返らなかった" {:response r})))))))

(defn measure!
  "1 オファーを借りて cold start を測り、**必ず破棄する**。

   測るのは 2 つだけ:
     :provision-ms … create → running（イメージ取得を含む = 真の cold start）
     :offer        … どのホストでいくらだったか（再現のため）

   生成そのものの秒数は ComfyUI への HTTP が要るので本 harness の対象外。
   **測れていないものを測ったと言わない。**"
  [gpu image deadline-ms]
  (-> (offers! gpu)
      (.then (fn [{:keys [offers]}]
               (when (empty? offers)
                 (throw (ex-info "該当オファーなし" {:gpu gpu})))
               (let [o (first offers)]
                 (println (str ";; 借用: " (:gpu_name o) " $" (:dph_total o) "/h"
                               " host=" (:host_id o) " geo=" (:geolocation o)))
                 (-> (rent! o image)
                     (.then (fn [id]
                              (println (str ";; instance " id
                                            " — 失敗しても `orphans --destroy` で破棄できる"))
                              (let [t0 (now)]
                                (with-destroy!
                                  id
                                  (fn []
                                    (-> (wait-running id deadline-ms)
                                        (.then (fn [w]
                                                 (let [ms (- (now) t0)]
                                                   {:gpu gpu
                                                    :image image
                                                    :offer (select-keys o [:id :host_id :dph_total
                                                                           :geolocation :inet_down
                                                                           :reliability2 :gpu_ram])
                                                    :provision-ms ms
                                                    :status (:status w)
                                                    :transitions (:transitions w)
                                                    :usd-spent (* (:dph_total o)
                                                                  (/ ms 3600000.0))})))))))))))))))

;; ── CLI ──────────────────────────────────────────────────────────────────────

(defn -main [& args]
  (let [[cmd a b c] args
        gpu (or a "RTX 4090")
        image (or b comfy-image)
        deadline (* 60 1000 (js/parseInt (or c "25") 10))]
    (-> (case cmd
          "offers" (.then (offers! gpu)
                          (fn [{:keys [offers]}] (pp/pprint (summarize gpu offers))))
          "orphans" (orphans! (= a "--destroy"))
          "measure" (.then (measure! gpu image deadline) pp/pprint)
          (js/Promise.resolve
           (println "usage: offers <gpu> | orphans [--destroy] | measure <gpu> [image] [minutes]")))
        (.catch (fn [e]
                  (js/console.error "ERROR:" (ex-message e) (pr-str (ex-data e)))
                  (js/console.error "取り残し確認: nbb 90-docs/procurement/vast-measure.cljs orphans")
                  (fail! 1))))))

(apply -main *command-line-args*)
