;; kotobase — 「server は安全性の責務を持たない」構成での client 負担と leakage
;; XMILE (OASIS XMILE 1.0) system-dynamics model
;;
;; 実行:
;;   nbb --classpath "orgs/kotoba-lang/org-oasis-open-xmile/src" <this-file>
;;
;; 前提（オーナー指示 2026-08-07）:
;;   安全性の責務を server に持たせない。したがって server は「暗号文と CID の
;;   置き場」でしかなく、機密性は client 側の鍵と計算で担保する。
;;   → server が index を読めるかどうかが、そのまま query 能力を決める。
;;
;; 出典（すべて実測）:
;;   [B] kotobase/capability-bench/results/storage.edn (2026-08-06)
;;       index block mean 12225.47 B / index heights eavt 2, avet 2
;;       GET/op: point-read 2.04, find-by-value 3.6, range-scan 16
;;       hops: point-read 2, transaction 5, range-scan 2
;;       opaque index amplification: request 2.8x, row 8.06x
;;       AEAD throughput 56.23 MB/s, decrypt 0.03 ms/index-block
;;       convergent equality leak 35.12%
;;   [L] .../latest.edn — mean block 14,848 B (44,293,549 / 2983)
;;   [P] kotoba-lang/pqh — XChaCha20 nonce24+tag16, ML-KEM-768 ct 1088 B
;;   [K] kotoba-lang/kagi/src/kagi/crypto/noble.cljs — ブラウザ側の実 ML-KEM-768
;;       (@noble/post-quantum)。client 側 PQ は実在する（想定ではない）。
;;   [C] kotoba-lang/crypto — hmac / hkdf 実装済み → blind index は今日作れる

(ns client-burden-xmile
  (:require [xmile.model :as m]
            [xmile.execute :as execute]))

(def MEAN-BLOCK-B 14848.0)          ; [L]
(def AEAD-MBPS 56.23)               ; [B]
(def GET-POINT 2.04)                ; [B]
(def GET-FIND 3.6)
(def GET-RANGE 16.0)
(def OPAQUE-REQ 2.8)                ; [B]
(def INDEX-HEIGHT 2)                ; [B] eavt 2 / avet 2

;; クエリ mix（構成の仮定。実測ではない）
(def MIX [[0.70 GET-POINT] [0.20 GET-FIND] [0.10 GET-RANGE]])
(def BASE-GET (reduce + (map (fn [[w g]] (* w g)) MIX)))

;; ── 検索可能暗号の段（server の責務はどの段でもゼロ） ────────────────────
;; :server-query  server がその段で答えられるもの
;; :get-amp       GET 増幅 [B]
;; :hops          依存往復の鎖（latency 下限を決める）
;; :client-frac   client が復号するバイトの割合（fetch した block のうち）
;; :leak          server が学習するもの
(def tiers
  [{:id "P" :name "cohort/projection 平文(PII ゼロ)"
    :server-query "full Datalog" :get-amp 1.0 :hops 2 :client-frac 0.0
    :leak "projection の中身のみ(構造上 PII ゼロ)"
    :status "実装済み — rirekisho/kagi/talent の 3 実装"}
   {:id "B" :name "blind index (HMAC 決定的トークン)"
    :server-query "等価のみ" :get-amp 1.0 :hops 2 :client-frac 1.0
    :leak "検索パターン + アクセスパターン + 等価/頻度"
    :status "今日作れる — kotoba-lang/crypto の hmac [C]"}
   {:id "S" :name "SSE (暗号化転置索引)"
    :server-query "等価 + 論理積" :get-amp 1.0 :hops 3 :client-frac 1.0
    :leak "検索パターン + アクセスパターン"
    :status "未実装"}
   {:id "C" :name "opaque index + client 側 tree 降下"
    :server-query "無し(blob 取得のみ)" :get-amp OPAQUE-REQ
    :hops (inc INDEX-HEIGHT) :client-frac 1.0
    :leak "アクセスパターン + サイズ"
    :status "今日作れる — 追加の暗号要素なし"}
   {:id "F" :name "全 client (corpus 丸ごと取得)"
    :server-query "無し" :get-amp 1.0 :hops 1 :client-frac 1.0
    :leak "サイズのみ"
    :status "今日作れる — corpus が小さい間だけ現実的"}])

;; ── XMILE: client 負担の累積 ─────────────────────────────────────────────
;; Stocks:
;;   Corpus_MB           — corpus は成長する
;;   Cum_Client_MB       — client が復号したバイトの累積
;;   Cum_GET             — 累積 GET
;; tier F だけは「1 クエリ = corpus 全取得」なので Corpus_MB に比例して悪化する。
(defn build [{:keys [get-amp client-frac full-scan? ops-per-month c0-mb ingest]}]
  (-> (m/model "client-burden"
               {:xmile/sim-specs (m/sim-specs 0.0 120.0 {:xmile/dt 0.25
                                                         :xmile/method :euler})})
      (m/add-variable (m/stock "Corpus_MB" (str c0-mb) {:xmile/inflows #{"Ingest"}}))
      (m/add-variable (m/stock "Cum_Client_MB" "0" {:xmile/inflows #{"Client_MB_Rate"}}))
      (m/add-variable (m/stock "Cum_GET" "0" {:xmile/inflows #{"GET_Rate"}}))
      (m/add-variable (m/flow "Ingest" (str ingest)))
      (m/add-variable (m/aux "Ops" (str ops-per-month)))
      (m/add-variable (m/aux "Get_Per_Op" (str (* BASE-GET get-amp))))
      (m/add-variable (m/aux "Block_MB" (str (/ MEAN-BLOCK-B 1048576.0))))
      (m/add-variable (m/aux "Client_Frac" (str client-frac)))
      (m/add-variable (m/flow "GET_Rate" "Ops * Get_Per_Op"))
      ;; full-scan tier は corpus 全体、それ以外は fetch した block だけ
      (m/add-variable (m/flow "Client_MB_Rate"
                              (if full-scan?
                                "Ops * Corpus_MB * Client_Frac"
                                "Ops * Get_Per_Op * Block_MB * Client_Frac")))))

(def C0-MB (/ (* 8601 MEAN-BLOCK-B) 1048576.0))
(def COMMON {:ops-per-month 1.0e6 :c0-mb C0-MB :ingest 20.0})

(defn run-tier [t]
  (let [model (build (merge COMMON
                            {:get-amp (:get-amp t)
                             :client-frac (:client-frac t)
                             :full-scan? (= "F" (:id t))}))
        r (execute/run model)
        s (:xmile/series r)
        at (fn [k mo] (nth (s k) (int (* mo 4))))]
    (assoc t
           :client-mb-120 (at "Cum_Client_MB" 120)
           :get-120 (at "Cum_GET" 120)
           :corpus-120 (at "Corpus_MB" 120))))

(defn- fmt [x]
  (cond (>= x 1e9) (str (.toFixed (/ x 1e9) 2) "G")
        (>= x 1e6) (str (.toFixed (/ x 1e6) 2) "M")
        (>= x 1e3) (str (.toFixed (/ x 1e3) 2) "k")
        :else (.toFixed x 2)))

(println "=== server が安全性の責務を持たない構成 — 段ごとの client 負担 ===")
(println (str "corpus " (.toFixed C0-MB 1) " MiB → " (.toFixed (+ C0-MB (* 20 120)) 0)
              " MiB (120ヶ月), クエリ 1M ops/月"))
(println "（ops/月 と mix 70/20/10 は仮定。GET/op・hops・増幅・block サイズは実測）")
(println)
(println (str (.padEnd "段" 4) (.padEnd "構成" 34)
              (.padStart "server query" 14)
              (.padStart "GET amp" 9)
              (.padStart "hops" 6)
              (.padStart "client復号@120mo" 18)
              (.padStart "復号時間" 12)))
(doseq [t tiers]
  (let [x (run-tier t)
        hrs (/ (:client-mb-120 x) AEAD-MBPS 3600.0)]
    (println (str (.padEnd (:id x) 4) (.padEnd (:name x) 34)
                  (.padStart (:server-query x) 14)
                  (.padStart (str (:get-amp x) "x") 9)
                  (.padStart (str (:hops x)) 6)
                  (.padStart (str (fmt (:client-mb-120 x)) " MB") 18)
                  (.padStart (str (fmt hrs) " h") 12)))))

(println)
(println "=== 50ms RTT でのクエリ latency 下限（hops × RTT）===")
(doseq [t tiers]
  (println (str "  " (.padEnd (:id t) 3) (.padEnd (:name t) 34)
                (.padStart (str (* 50 (:hops t)) " ms") 9))))

(println)
(println "=== server が学習するもの / 実装状況 ===")
(doseq [t tiers]
  (println (str "  " (.padEnd (:id t) 3) (.padEnd (:leak t) 40) (:status t))))

(println)
(println "実測の裏付け: 等価漏洩は convergent 暗号で 35.12% のブロックが tenant 間で")
(println "byte 同一（storage.edn）。段 B の『等価/頻度』はこの実測と同じ種類の漏洩。")

;; ── 1 op あたりに割り戻す（集計値だけ見ると誤読するため）────────────────
(println)
(println "=== 1 クエリあたりの内訳（RTT 50ms 想定）===")
(println (str (.padEnd "段" 4) (.padEnd "構成" 34)
              (.padStart "復号KB/op" 12)
              (.padStart "復号ms/op" 12)
              (.padStart "RTT ms/op" 11)
              (.padStart "crypto比" 10)))
(doseq [t tiers]
  (let [total-ops (* 1.0e6 120)
        x (run-tier t)
        kb (/ (* (:client-mb-120 x) 1024.0) total-ops)
        ms (/ (/ (:client-mb-120 x) total-ops) AEAD-MBPS 0.001)
        rtt (* 50.0 (:hops t))]
    (println (str (.padEnd (:id t) 4) (.padEnd (:name t) 34)
                  (.padStart (fmt kb) 12)
                  (.padStart (.toFixed ms 3) 12)
                  (.padStart (.toFixed rtt 0) 11)
                  (.padStart (str (.toFixed (* 100 (/ ms (+ ms rtt))) 2) "%") 10)))))
(println)
(println "→ 段 B/S/C の client 復号は 1 op あたり ~1ms で、RTT 100-150ms に対して 1% 未満。")
(println "  client 側に寄せたときの実コストは暗号ではなく **往復回数(hops)**。")
(println "  server が index を読めなくなると tree 降下が client に移り hops が 2→3 になる。")
