#!/usr/bin/env nbb
;; training-corpus-check.cljs — com-junkawasaki/root の学習コーパス面の gate
;; （ADR-2608056000）。ノード側で `npx nbb training-corpus-check.cljs <repo-dir>`
;; として走る（tick.cljs が heredoc で配って呼ぶ）。**JVM を要求しない。**
;;
;; 検査するもの:
;;   0. 展開の健全性（空ツリーを「違反 0 件 = 合格」と報告しない床）
;;   1. **索引に載っている行が、その文書を再採点した結果と完全に一致する**
;;      （生成器を実際に走らせる `--verify-index`）
;;   2. coverage が申告する policy の sha256 が実ファイルと一致する
;;   3. coverage の tier 別件数が実 entity 数と一致する（申告と実体の乖離）
;;   4. hazard を持つ文書は必ず :excluded、:excluded は必ず hazard を持つ
;;   5. shard は :gold/:silver だけを覆い、:shard/documents が実件数と一致する
;;      （= **hazard で除外した文書が bytes plane に漏れない**）
;;   6. annex key の形が SHA256-s<bytes>--<sha256> で、sha256/bytes と整合する
;;
;; (1) が主眼。committed 済みの値を読むだけの検査では、**内部整合を保ったまま
;; 手編集された生成物**を検出できない。(3)〜(6) はそれとは独立な不変条件で、
;; 生成器自体が壊れたケース（再採点は通るが中身が矛盾する）を捕まえる。
;;
;; ⚠ **全再生成の bytes 一致（`--check`）を gate にしてはいけない。** この repo は
;; 並行セッションが毎日 ADR を足すので、新しい文書が 1 件 landed した瞬間に赤になり、
;; 以後ずっと赤のままになる（「常に赤い gate は無視され、無視される gate は存在しないのと
;; 同じ」）。`--verify-index` は **載っている行の正しさ**だけを不変条件にし、
;; **載っていない文書は件数を報告して通す** —— 索引が古いことと壊れていることを分ける。

(ns fleet-ci.gates.training-corpus-check
  (:require ["node:fs" :as fs]
            ["node:path" :as path]
            ["node:crypto" :as crypto]
            ["node:child_process" :as cp]
            [cljs.reader :as reader]
            [clojure.string :as str]))

(def argv (vec (js->clj (.-argv js/process))))
(def args (vec *command-line-args*))
(def root (or (first (remove #(str/starts-with? % "--") args)) "."))

(def failures (atom []))
(defn fail! [& xs] (swap! failures conj (str/join " " (map str xs))))
(defn slurp* [p] (str (fs/readFileSync p "utf8")))
(defn sha256-file [p]
  (-> (.createHash crypto "sha256") (.update (fs/readFileSync p)) (.digest "hex")))

(defn- die-early [msg code]
  (println (str "FLEET-CI: " msg))
  (js/process.exit code))

;; --------------------------------------------------------------------------
;; 0. 展開の健全性

(def corpus-path (path/join root "90-docs" "corpus" "corpus.datoms.edn"))
(def policy-path (path/join root "manifest" "corpus-policy.edn"))
(def generator-path (path/join root "scripts" "gen-training-corpus.cljs"))

(doseq [p [corpus-path policy-path generator-path]]
  (when-not (fs/existsSync p)
    (die-early (str "missing " (path/relative root p)
                    " — tree did not extract, refusing to report a vacuous pass") 2)))

(def entities
  (let [x (reader/read-string (slurp* corpus-path))]
    (if (vector? x) x (die-early "corpus.datoms.edn is not a tx-data vector" 2))))

(def docs (filterv :corpus/id entities))
(def shards (filterv :shard/id entities))
(def coverage (first (filterv :corpus/coverage entities)))

(when (< (count docs) 500)
  (die-early (str "only " (count docs) " scored documents — corpus looks truncated") 2))
(when-not coverage (die-early "corpus.datoms.edn has no :corpus/coverage entity" 2))

;; --------------------------------------------------------------------------
;; 1. 再生成 —— この gate の主眼

(let [r (.spawnSync cp (first argv)
                    (clj->js [(second argv) generator-path "--verify-index"])
                    #js {:cwd root :encoding "utf8" :timeout 900000})
      status (.-status r)]
  ;; 通っても出力は出す（何件が未索引かは合否ではないが、読む価値のある事実）。
  (println (str/trim (str (.-stdout r))))
  (when-not (zero? (or status 1))
    (fail! "index verification failed (an indexed row does not match a fresh scoring):"
           (str/trim (str (.-stdout r) (.-stderr r))))))

;; --------------------------------------------------------------------------
;; 2. policy の sha256

(let [declared (:coverage/policy-sha256 coverage)
      actual (sha256-file policy-path)]
  (when-not (= declared actual)
    (fail! "coverage declares policy sha256" declared "but manifest/corpus-policy.edn is" actual)))

;; --------------------------------------------------------------------------
;; 3. coverage の申告 vs 実体

(let [by-tier (frequencies (map :corpus/tier docs))]
  (doseq [[tier k] [[:gold :coverage/gold] [:silver :coverage/silver]
                    [:bronze :coverage/bronze] [:excluded :coverage/excluded]]]
    (let [declared (get coverage k)
          actual (get by-tier tier 0)]
      (when-not (= declared actual)
        (fail! "coverage" k "declares" declared "but" (count docs) "entities contain" actual))))
  (when-not (= (:coverage/scored coverage) (count docs))
    (fail! "coverage/scored" (:coverage/scored coverage) "≠ document entities" (count docs))))

;; --------------------------------------------------------------------------
;; 4. hazard ⇔ :excluded

(doseq [d docs]
  (let [hz (seq (:corpus/hazard d))
        excluded? (= :excluded (:corpus/tier d))]
    (when (and hz (not excluded?))
      (fail! "document has hazard but is not excluded:" (:corpus/id d) (vec (:corpus/hazard d))))
    (when (and excluded? (not hz))
      (fail! "document is excluded without a recorded hazard:" (:corpus/id d)))))

;; --------------------------------------------------------------------------
;; 5. shard の被覆 —— hazard 除外が bytes plane に漏れないこと

(let [by-tier (frequencies (map :corpus/tier docs))]
  (when (empty? shards) (fail! "no shard entities"))
  (doseq [s shards]
    (let [tier (:shard/tier s)]
      (when (= :excluded tier)
        (fail! "a shard covers the :excluded tier — hazardous documents would reach the bytes plane"))
      (when-not (= (:shard/documents s) (get by-tier tier 0))
        (fail! "shard" (:shard/id s) "declares" (:shard/documents s)
               "documents but tier" tier "has" (get by-tier tier 0)))
      (when (str/blank? (:shard/dataset s))
        (fail! "shard" (:shard/id s) "has no :shard/dataset (bytes plane destination)")))))

;; --------------------------------------------------------------------------
;; 6. annex key の形と整合

(defn check-annex-key [label key bytes sha]
  (let [m (re-matches #"SHA256-s(\d+)--([0-9a-f]{64})" (str key))]
    (cond
      (nil? m) (fail! label "has a malformed annex key:" key)
      (not= (js/parseInt (nth m 1) 10) bytes)
      (fail! label "annex key size" (nth m 1) "≠ :bytes" bytes)
      (not= (nth m 2) sha)
      (fail! label "annex key digest ≠ recorded sha256"))))

(doseq [d docs]
  (check-annex-key (:corpus/id d) (:corpus/annex-key d) (:corpus/bytes d) (:corpus/sha256 d)))
(doseq [s shards]
  (check-annex-key (:shard/id s) (:shard/annex-key s) (:shard/bytes s) (:shard/sha256 s)))

;; --------------------------------------------------------------------------

(if (seq @failures)
  (do (println (str "FLEET-CI: training-corpus FAIL — " (count @failures) " violation(s)"))
      (doseq [f @failures] (println (str "  - " f)))
      (js/process.exit 1))
  (println (str "FLEET-CI: training-corpus OK — " (count docs) " documents, "
                (count shards) " shards, coverage declares "
                (:coverage/gold coverage) " gold / "
                (:coverage/silver coverage) " silver / "
                (:coverage/bronze coverage) " bronze / "
                (:coverage/excluded coverage) " excluded")))
