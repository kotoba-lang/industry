;; kotobase 暗号化強度トレードオフ — XMILE (OASIS XMILE 1.0) system-dynamics model
;;
;; 実行:
;;   nbb --classpath "orgs/kotoba-lang/org-oasis-open-xmile/src:orgs/kotoba-lang/dsl-core/src" \
;;       <this-file>
;;
;; パラメータの出典（すべて実測、捏造なし）:
;;   [B] kotoba-lang/kotobase/capability-bench/results/storage.edn  (2026-08-06)
;;       - AEAD overhead 28 B/block, throughput 56.23 MB/s
;;       - index block mean 12225.47 B
;;       - opaque index amplification: request-factor 2.8, row-factor 8.06
;;       - convergent equality leak 35.12%
;;       - S3 profile GETs/op: point-read 2.04, find-by-value 3.6, range-scan 16
;;   [L] .../results/latest.edn (2026-08-06) — kotobase-prolly stored
;;       2983 blocks / 44,293,549 B  → mean block 14,848 B
;;   [P] kotoba-lang/pqh src (code constants)
;;       X25519 pub 32 B, ML-KEM-768 pub 1184 B / ct 1088 B, ML-DSA-65 pub 1952 B
;;       XChaCha20-Poly1305 nonce 24 + tag 16 = 40 B/block
;;   [D] net-kotobase 本番 /_migration (2026-08-07 実測) — sweep copied 8601 objects
;;
;; 派生値（実測の組み合わせであることを明示する）:
;;   C0 = 8601 objects [D] × 14,848 B/block [L] = 127,706,048 B = 121.8 MiB
;;        ← 本番のオブジェクト「数」は実測、1件あたりバイト数は bench 由来の平均。
;;           本番の実バイト数は未計測なので、これは推定であって計測値ではない。

;; 注: xmile.validate は kotoba.dsl.problem (dsl-core) に依存し、dsl-core は
;; 既に .kotoba へ移行済みなので nbb からは読めない（2026-08-07 実測）。
;; model/execute は dsl-core 非依存なので、構造検証だけ JVM 側に回すか、
;; ここでは execute が投げる例外を検証の代わりにする。
(ns crypto-tradeoff-xmile
  (:require [xmile.model :as m]
            [xmile.execute :as execute]))

;; ── 実測パラメータ ────────────────────────────────────────────────────────
(def MEAN-BLOCK-BYTES 14848.0)          ; [L] 44293549 / 2983
(def C0-MB (/ (* 8601 MEAN-BLOCK-BYTES) 1048576.0))  ; [D]×[L] ≈ 127.7 MB
(def AEAD-THROUGHPUT-MBPS 56.23)        ; [B]
(def SECONDS-PER-MONTH 2592000.0)

;; AEAD envelope 1 block あたりのバイト増（block sealing のみ）
(def AESGCM-OVERHEAD-B 28.0)            ; [B] 実測
(def XCHACHA-OVERHEAD-B 40.0)           ; [P] nonce 24 + tag 16

;; opaque index（サーバが index を読めない）ときの増幅 [B]
(def OPAQUE-REQ-FACTOR 2.8)
(def OPAQUE-ROW-FACTOR 8.06)

;; S3 profile の GET/op [B]
(def GET-POINT 2.04)
(def GET-FIND 3.6)
(def GET-RANGE 16.0)

;; ── モデル構築 ────────────────────────────────────────────────────────────
;; Stocks:
;;   Classical_Corpus  (MB)      — classical-only 暗号で保管されている量
;;   PQ_Corpus         (MB)      — pqh-v1 hybrid で保管されている量
;;   Exposure          (MB·month)— classical corpus の露出積分
;;                                 ★harvest レートは実測が無いので掛けない。
;;                                   実 harvest バイト = Exposure × (任意レート)
;;   Cum_GET           (M req)   — 累積 GET（コスト代理）
;;
;; Flows:
;;   Ingest_Classical / Ingest_PQ / Migrate / Accrue_Exposure / GET_Rate

(defn build
  "scenario: {:pq-share-eqn  新規書込のうち PQ で書く割合（XMILE 式・文字列）
              :migrate-duty  legacy 再暗号化に割く wall-time 比（0..1）
              :opaque-index? サーバが index を読めないか
              :ingest-mb-per-month 新規書込 MB/月
              :ops-per-month-M     クエリ数（百万/月）}"
  [{:keys [pq-share-eqn migrate-duty opaque-index?
           ingest-mb-per-month ops-per-month-M]}]
  (let [;; 再暗号化スループット上限 (MB/月)。crypto 律速の上界。
        migrate-cap (* AEAD-THROUGHPUT-MBPS SECONDS-PER-MONTH migrate-duty)
        ;; クエリ mix: point 70% / find 20% / range 10%（構成の仮定。実測ではない）
        base-get (+ (* 0.70 GET-POINT) (* 0.20 GET-FIND) (* 0.10 GET-RANGE))
        amp (if opaque-index? OPAQUE-REQ-FACTOR 1.0)]
    (-> (m/model "kotobase-crypto-tradeoff"
                 {:xmile/sim-specs (m/sim-specs 0.0 120.0 {:xmile/dt 0.25
                                                           :xmile/method :euler})})
        ;; --- stocks ---
        (m/add-variable (m/stock "Classical_Corpus" (str C0-MB)
                                 {:xmile/inflows #{"Ingest_Classical"}
                                  :xmile/outflows #{"Migrate"}}))
        (m/add-variable (m/stock "PQ_Corpus" "0"
                                 {:xmile/inflows #{"Ingest_PQ" "Migrate"}}))
        (m/add-variable (m/stock "Exposure" "0"
                                 {:xmile/inflows #{"Accrue_Exposure"}}))
        (m/add-variable (m/stock "Cum_GET" "0"
                                 {:xmile/inflows #{"GET_Rate"}}))
        ;; --- policy auxiliaries ---
        (m/add-variable (m/aux "Ingest_Total" (str ingest-mb-per-month)))
        (m/add-variable (m/aux "PQ_Share" pq-share-eqn))
        (m/add-variable (m/aux "Migration_Capacity" (str migrate-cap)))
        (m/add-variable (m/aux "Ops_Per_Month" (str ops-per-month-M)))
        (m/add-variable (m/aux "GET_Amplification" (str amp)))
        (m/add-variable (m/aux "Base_GET_Per_Op" (str base-get)))
        ;; --- flows ---
        (m/add-variable (m/flow "Ingest_Classical"
                                "Ingest_Total * (1 - PQ_Share)"))
        (m/add-variable (m/flow "Ingest_PQ"
                                "Ingest_Total * PQ_Share"))
        ;; 再暗号化は corpus を超えて進まない。PQ_Share が 0 の間は移行もしない
        ;; （新規を classical で書きながら legacy を移行するのは整合しないため）
        (m/add-variable (m/flow "Migrate"
                                "MIN(Classical_Corpus / DT, Migration_Capacity * PQ_Share)"))
        ;; 露出積分: classical のまま置かれている量を時間で積む
        (m/add-variable (m/flow "Accrue_Exposure" "Classical_Corpus"))
        (m/add-variable (m/flow "GET_Rate"
                                "Ops_Per_Month * Base_GET_Per_Op * GET_Amplification")))))

(def scenarios
  [{:label "S1 何もしない（classical のまま）"
    :pq-share-eqn "0"        :migrate-duty 0.0    :opaque-index? false}
   {:label "S2 新規のみ PQ、legacy 放置"
    :pq-share-eqn "1"        :migrate-duty 0.0    :opaque-index? false}
   {:label "S3 新規 PQ + legacy 再暗号化"
    :pq-share-eqn "1"        :migrate-duty 1e-6   :opaque-index? false}
   {:label "S4 24ヶ月後に着手（S3 と同内容）"
    :pq-share-eqn "STEP(1, 24)" :migrate-duty 1e-6 :opaque-index? false}
   {:label "S5 S3 + opaque index（index も秘匿）"
    :pq-share-eqn "1"        :migrate-duty 1e-6   :opaque-index? true}])

(def common {:ingest-mb-per-month 20.0 :ops-per-month-M 1.0})

(defn- at [series t-idx] (nth series t-idx))

(defn run-one [sc]
  (let [model (build (merge common sc))
        r (execute/run model)
        times (:xmile/times r)
        series (:xmile/series r)
        ;; dt=0.25 → index = month*4
        idx (fn [month] (int (* month 4)))]
    {:label (:label sc)
     :classical-60 (at (series "Classical_Corpus") (idx 60))
     :pq-60        (at (series "PQ_Corpus") (idx 60))
     :exposure-60  (at (series "Exposure") (idx 60))
     :exposure-120 (at (series "Exposure") (idx 120))
     :cum-get-120  (at (series "Cum_GET") (idx 120))
     :n-steps (count times)}))

(defn- fmt [x]
  (cond (>= (abs x) 1e6) (str (.toFixed (/ x 1e6) 2) "M")
        (>= (abs x) 1e3) (str (.toFixed (/ x 1e3) 2) "k")
        :else (.toFixed x 2)))

(println "=== kotobase 暗号化強度トレードオフ (XMILE 1.0, Euler dt=0.25, 120ヶ月) ===")
(println (str "初期 corpus C0 = " (.toFixed C0-MB 1) " MB"
              "  (本番 8601 objects[実測] × bench 平均 14848 B/block → 推定)"))
(println (str "新規書込 " (:ingest-mb-per-month common) " MB/月, クエリ "
              (:ops-per-month-M common) "M ops/月"))
(println)
(println (str (.padEnd "シナリオ" 34)
              (.padStart "Classical@60mo" 16)
              (.padStart "PQ@60mo" 12)
              (.padStart "露出@60mo" 14)
              (.padStart "露出@120mo" 14)
              (.padStart "累積GET@120mo" 15)))
(doseq [sc scenarios]
  (let [x (run-one sc)]
    (println (str (.padEnd (:label x) 34)
                  (.padStart (str (fmt (:classical-60 x)) " MB") 16)
                  (.padStart (str (fmt (:pq-60 x)) " MB") 12)
                  (.padStart (str (fmt (:exposure-60 x)) " MB·mo") 14)
                  (.padStart (str (fmt (:exposure-120 x)) " MB·mo") 14)
                  (.padStart (str (fmt (:cum-get-120 x)) " req") 15)))))

(println)
(println "露出 = classical のまま保持された量の時間積分 (MB·月)。")
(println "実 harvest バイト = 露出 × (敵対者の harvest レート /月)。")
(println "★harvest レートはこのワークスペースに実測が無いため掛けていない（捏造しない）。")

;; ── 相対比較 ──────────────────────────────────────────────────────────────
(println)
(println "=== S1 を 1.00 とした相対露出 ===")
(let [base (:exposure-120 (run-one (first scenarios)))]
  (doseq [sc scenarios]
    (let [x (run-one sc)]
      (println (str (.padEnd (:label x) 34)
                    (.padStart (.toFixed (/ (:exposure-120 x) base) 4) 10))))))

;; ── 強度ラダー: block 1件あたりの実バイトコスト ──────────────────────────
;; padding bucket [P] は「暗号文長からの平文長推定」を潰すための metadata 秘匿。
;; AEAD tag と違い、これは block サイズに対して非線形（bucket 境界）。
(def PAD-BUCKETS [1024 4096 16384 65536])
(defn pick-bucket [plaintext-len]
  (let [need (+ plaintext-len 1 16)]
    (first (filter #(<= need %) PAD-BUCKETS))))

(println)
(println "=== block 1件あたりの実コスト（実測平均から算出） ===")
(doseq [[nm mean] [["index block" 12225.47] ["definition block" 1638.28]]]
  (let [b (pick-bucket mean)]
    (println (str (.padEnd nm 20)
                  " 平均 " (.padStart (.toFixed mean 0) 7) " B"
                  " | AEAD tag +28 B = " (.toFixed (* 100 (/ 28.0 mean)) 2) "%"
                  " | pad→" b " B = +" (.toFixed (* 100 (/ (- b mean) mean)) 1) "%"))))
(println "→ metadata 秘匿(padding) は AEAD(暗号そのもの) より 2〜3 桁高い。")
(println "   小さい block ほど不利。block を大きく揃えるほど padding 比は下がる。")

;; KEM ciphertext は受信者ごとに付く（共有時のコスト）
(println)
(println "=== 共有（受信者 N 人）の鍵材コスト [P] ===")
(doseq [n [1 5 25]]
  (println (str "  受信者 " (.padStart (str n) 3) " 人: X25519 ct 32 B + ML-KEM-768 ct 1088 B ずつ = "
                (.padStart (str (* n (+ 32 1088))) 6) " B/block"
                "  (index block 平均比 " (.toFixed (* 100 (/ (* n 1120.0) 12225.47)) 1) "%)")))
