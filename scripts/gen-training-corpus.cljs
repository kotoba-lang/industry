#!/usr/bin/env nbb
;; gen-training-corpus.cljs — この workspace の**文書を学習コーパスとして採点し、
;; datom として query できる面に載せる**生成器（ADR-2608056000）。
;;
;; ## 何を作るか
;;
;;   90-docs/corpus/corpus.datoms.edn   1 文書 = 1 entity（score / tier / hazard /
;;                                      sha256 / annex key）+ coverage entity +
;;                                      shard entity。**生成物、手編集禁止。**
;;   .corpus-shards/<tier>-v1.edn       学習に食わせる bytes（--shard 時のみ書く）。
;;                                      **git には入れない** —— DataLad/git-annex 行き。
;;
;; 手書きは `manifest/corpus-policy.edn` だけ（軸・重み・閾値・hazard・consent）。
;;
;; ## 使い方
;;
;;   nbb scripts/gen-training-corpus.cljs                # 生成
;;   nbb scripts/gen-training-corpus.cljs --check        # 差分があれば exit 1（gate 用）
;;   nbb scripts/gen-training-corpus.cljs --shard        # 生成 + shard bytes を書き出す
;;
;; ## なぜ `--check` を gate にしてよいか（concept 索引は「するな」なのに）
;;
;; `gen-concept-index.cljs` の出力は **今この checkout に何が展開されているか**に
;; 依存する（並行セッションの `west update` で走査対象が増減する）。ここは
;; superproject 自身の追跡済みファイルだけを見るので、同じ commit なら誰が何回
;; 走らせても同じ bytes になる。だから sha256 gate として意味を持つ。
;;
;; ## 採点は決定論。LLM judge を使わない
;;
;; 採点が**学習データの選別**を決める以上、再現しない採点器は汚染源になる。
;; 根拠は policy 冒頭のコメント（ADR-2607132300 の 3-judge panel 実測）。

(ns gen-training-corpus
  (:require ["fs" :as fs]
            ["path" :as path]
            ["crypto" :as crypto]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(def root (.cwd js/process))
(def args (set (vec *command-line-args*)))
(def check? (contains? args "--check"))
(def shard? (contains? args "--shard"))
(def verify-index? (contains? args "--verify-index"))

(def policy-path (path/join root "manifest" "corpus-policy.edn"))
(def out-path (path/join root "90-docs" "corpus" "corpus.datoms.edn"))

;; ---------------------------------------------------------------------------
;; 素材

(defn- read-buffer [p] (try (fs/readFileSync p) (catch :default _ nil)))
(defn- sha256 [buf] (-> (.createHash crypto "sha256") (.update buf) (.digest "hex")))
(defn- utf8-bytes [^string s] (.byteLength js/Buffer s "utf8"))

(defn- read-edn [p]
  (edn/read-string {:default (fn [_tag v] v)} (str (fs/readFileSync p "utf8"))))

(def policy (read-edn policy-path))
(def policy-sha (sha256 (fs/readFileSync policy-path)))

(defn- walk
  "root 直下を再帰的に辿り、ext のいずれかで終わるファイルの絶対パスを返す。
   `.git` と隠しディレクトリには入らない（生成物の混入と無限走査を防ぐ）。"
  [dir exts]
  (if-not (fs/existsSync dir)
    []
    (->> (fs/readdirSync dir #js {:withFileTypes true})
         (mapcat (fn [e]
                   (let [n (.-name e)
                         p (path/join dir n)]
                     (cond
                       (str/starts-with? n ".") []
                       (.isDirectory e) (walk p exts)
                       (some #(str/ends-with? n %) exts) [p]
                       :else []))))
         vec)))

;; posix セパレータ前提（このワークスペースは darwin/linux のみ。Windows で
;; 走らせるなら path/sep を "/" に畳む必要がある）。
(defn- rel [p] (path/relative root p))

;; ---------------------------------------------------------------------------
;; 正規表現。JS の RegExp を使うので `(?m)`/`(?i)` は書けない —— フラグは
;; `:re-flags` / `:hazard/re-flags` に分けて持ち、行頭は `(^|\n)` で表す。

(defn- rx [pattern flags] (js/RegExp. pattern (str (or flags "") "g")))

(defn- match-count [text pattern flags]
  (let [m (.match text (rx pattern flags))]
    (if m (.-length m) 0)))

(defn- matches? [text pattern flags] (pos? (match-count text pattern flags)))

;; ---------------------------------------------------------------------------
;; 軸

(defn- axis-evidence [text]
  (let [{:keys [regexes saturation]} (:evidence-markers policy)
        hit (count (filter #(matches? text (:re %) (:re-flags %)) regexes))]
    (min 1.0 (/ hit (double saturation)))))

(defn- axis-specificity [text n-bytes]
  (let [{:keys [regexes saturation-per-kib]} (:specificity policy)
        hits (reduce + (map #(match-count text (:re %) (:re-flags %)) regexes))
        kib (max 1.0 (/ n-bytes 1024.0))]
    (min 1.0 (/ (/ hits kib) saturation-per-kib))))

(defn- axis-structure [kind entity text]
  (let [{:keys [adr-required-attrs md-heading-re md-section-re md-sections-saturation]}
        (:structure policy)]
    (if (and (= :adr kind) (map? entity))
      (/ (count (filter #(contains? entity %) adr-required-attrs))
         (double (count adr-required-attrs)))
      (let [head (if (matches? text md-heading-re nil) 0.5 0.0)
            secs (min 1.0 (/ (match-count text md-section-re nil)
                             (double md-sections-saturation)))]
        (+ head (* 0.5 secs))))))

(defn- axis-provenance [kind entity text]
  (let [{:keys [adr-attrs md-date-re]} (:provenance policy)]
    (if (and (= :adr kind) (map? entity))
      (/ (count (filter #(contains? entity %) adr-attrs)) (double (count adr-attrs)))
      (if (matches? text md-date-re nil) 0.5 0.2))))

(defn- axis-currency [entity]
  (let [{:keys [status-scores match-order unknown]} (:currency policy)
        s (some-> (:adr/status entity) str str/lower-case)]
    (if (str/blank? s)
      unknown
      (or (some (fn [k] (when (str/includes? s k) (get status-scores k))) match-order)
          unknown))))

(defn- axis-self-containment [n]
  (let [{:keys [min-bytes ideal-bytes decay-bytes]} (:self-containment policy)]
    (cond
      (< n min-bytes)   (* 0.8 (/ n (double min-bytes)))
      (< n ideal-bytes) (+ 0.8 (* 0.2 (/ (- n min-bytes)
                                         (double (- ideal-bytes min-bytes)))))
      (<= n decay-bytes) 1.0
      :else (max 0.5 (/ decay-bytes (double n))))))

(defn- round* [x]
  (let [f (Math/pow 10 (:round-digits policy))]
    (/ (Math/round (* x f)) f)))

(defn- composite
  "重み付き**幾何平均**。弱い次元を他の次元で埋めさせない（tamaki ADR-0002 の
   lineage vitality と同じ理由）。"
  [scores]
  (let [axes (:axes policy)
        eps (:epsilon policy)
        total (reduce + (map :axis/weight axes))
        acc (reduce (fn [a {:axis/keys [id weight]}]
                      (+ a (* weight (Math/log (max eps (get scores id 0.0))))))
                    0.0 axes)]
    (Math/exp (/ acc total))))

;; ---------------------------------------------------------------------------
;; hazard。**秘密が無いことの証明ではない**（形が既知のものだけを弾く構造検査）。

(defn- hazards-of [text]
  (let [structural (->> (:hazards policy)
                        (filter #(matches? text (:hazard/re %) (:hazard/re-flags %)))
                        (map :hazard/id))
        {:keys [email-re allow max-distinct]} (:pii policy)
        emails (-> (or (.match text (rx email-re nil)) #js []) js->clj set)
        foreign (remove (set allow) emails)]
    (vec (sort (cond-> (vec structural)
                 (> (count foreign) max-distinct) (conj :pii-email-cluster))))))

(defn- tier-of [score hazards]
  (if (seq hazards)
    :excluded
    (or (some (fn [{:tier/keys [id min-score]}] (when (>= score min-score) id))
              (:tiers policy))
        :bronze)))

(defn- consent-of [id]
  (let [{:keys [default rules]} (:consent policy)]
    (or (some (fn [{:keys [path-prefix consent]}]
                (when (str/starts-with? id path-prefix) consent))
              rules)
        default)))

;; ---------------------------------------------------------------------------
;; 1 文書

(defn- entity-of [kind p]
  (let [id (rel p)
        buf (read-buffer p)]
    (cond
      (nil? buf) {:skip :unreadable :corpus/id id}
      (> (.-length buf) (get-in policy [:scan :max-bytes])) {:skip :oversize :corpus/id id}
      :else
      (let [text (.toString buf "utf8")
            n (.-length buf)
            ;; EDN として読むのは .edn だけ。markdown を edn/read-string に通すと
            ;; 「先頭フォームがたまたま読めた/読めなかった」という無意味な値が出る
            ;; （実測: 76 件の .md のうち 5 件だけが parse 失敗と記録され、残り 71 件は
            ;; 偶然読めていた）。**測っていないものを測ったことにしない。**
            parsed (when (str/ends-with? id ".edn")
                     (try (let [x (edn/read-string {:default (fn [_t v] v)} text)]
                            (cond (vector? x) (first x) (map? x) x :else nil))
                          (catch :default _ ::parse-failed)))
            failed? (= parsed ::parse-failed)
            entity (when (map? parsed) parsed)
            scores {:evidence (axis-evidence text)
                    :specificity (axis-specificity text n)
                    :structure (axis-structure kind entity text)
                    :provenance (axis-provenance kind entity text)
                    :currency (axis-currency entity)
                    :self-containment (axis-self-containment n)}
            score (composite scores)
            hz (hazards-of text)]
        (cond-> {;; 3 種類の entity（文書 / shard / coverage）に共通の identity。
                 ;; projection contract の `:projection/identity-attrs` は
                 ;; **全 entity が持つ属性**でなければならない（manifest/project-edn.cljs）。
                 :corpus/entity-id id
                 :corpus/id id
                 :corpus/kind kind
                 :corpus/bytes n
                 :corpus/sha256 (sha256 buf)
                 :corpus/annex-key (str "SHA256-s" n "--" (sha256 buf))
                 :corpus/repo "com-junkawasaki/root"
                 :corpus/consent (consent-of id)
                 :corpus/score (round* score)
                 :corpus/score-evidence (round* (:evidence scores))
                 :corpus/score-specificity (round* (:specificity scores))
                 :corpus/score-structure (round* (:structure scores))
                 :corpus/score-provenance (round* (:provenance scores))
                 :corpus/score-currency (round* (:currency scores))
                 :corpus/score-self-containment (round* (:self-containment scores))
                 :corpus/tier (tier-of score hz)
                 :source/dataset (get-in policy [:query :source/dataset])}
          (seq hz) (assoc :corpus/hazard hz)
          failed? (assoc :corpus/parse :failed)
          (:adr/id entity) (assoc :corpus/adr-id (str (:adr/id entity)))
          (:adr/status entity) (assoc :corpus/status (str (:adr/status entity)))
          ;; text は datom 面に載せない。bytes は annex 行き（ADR-2608039700）。
          true (with-meta {:text text}))))))

;; ---------------------------------------------------------------------------
;; 正規化した印字（同じ入力 → 同じ bytes）

(defn- canon [m] (into (sorted-map) m))
(defn- print-vec [entities]
  (str "[" (str/join "\n " (map (comp pr-str canon) entities)) "]\n"))

;; ---------------------------------------------------------------------------
;; shard（bytes plane）

(defn- shard-body
  "tier の文書本文を canonical EDN として畳む。**この bytes が annex key の実体**。"
  [docs]
  (print-vec (map (fn [d] {:doc/id (:corpus/id d)
                           :doc/kind (:corpus/kind d)
                           :doc/sha256 (:corpus/sha256 d)
                           :doc/text (:text (meta d))})
                  docs)))

(defn- shard-entity [tier docs]
  (let [body (shard-body docs)
        n (utf8-bytes body)
        h (sha256 (js/Buffer.from body "utf8"))
        bp (:bytes-plane policy)]
    {:corpus/entity-id (str "shard/" (name tier) "-v1")
     :shard/id (str (name tier) "-v1")
     :shard/tier tier
     :shard/documents (count docs)
     :shard/bytes n
     :shard/sha256 h
     :shard/annex-key (str "SHA256-s" n "--" h)
     :shard/dataset (:dataset/id bp)
     :shard/path (str (:dataset/subdir bp) "/" (name tier) "-v1.edn")
     ;; annex に入って special remote へ複製されるまで custody は未確認。
     ;; 「宣言した」ことを「保管している」と読ませない。
     :shard/custody :unverified
     :source/dataset (get-in policy [:query :source/dataset])}))

;; ---------------------------------------------------------------------------

(defn- build []
  (let [excluded (set (get-in policy [:scan :exclude-paths]))
        excluded-prefixes (vec (get-in policy [:scan :exclude-prefixes]))
        excluded? (fn [id] (or (contains? excluded id)
                               (some #(str/starts-with? id %) excluded-prefixes)))
        files (->> (get-in policy [:scan :roots])
                   ;; `path` を分配束縛しない —— nbb では namespace alias `path/join` と
                   ;; 局所束縛 `path` が同じ記号に見えて解決が壊れる。
                   (mapcat (fn [r]
                             (let [k (:root/kind r)]
                               (map (fn [p] [k p])
                                    (walk (path/join root (:root/path r)) (:root/ext r))))))
                   (remove (fn [[_ p]] (excluded? (rel p))))
                   ;; 90-docs の .md 走査と 90-docs/adr の .edn 走査は重ならないが、
                   ;; root を足したときの取りこぼし/二重計上を構造的に防ぐ。
                   (reduce (fn [acc [k p]] (if (contains? acc p) acc (assoc acc p k))) {})
                   (map (fn [[p k]] [k p]))
                   (sort-by second))
        results (map (fn [[k p]] (entity-of k p)) files)
        skipped (filter :skip results)
        docs (->> (remove :skip results) (sort-by :corpus/id) vec)
        by-tier (frequencies (map :corpus/tier docs))
        shard-tiers (get-in policy [:bytes-plane :shard/tiers])
        shards (map (fn [t] (shard-entity t (filter #(= t (:corpus/tier %)) docs)))
                    shard-tiers)
        coverage {:corpus/entity-id "coverage"
                  :corpus/coverage true
                  :coverage/policy-id (:policy/id policy)
                  :coverage/policy-sha256 policy-sha
                  :coverage/scanned (count files)
                  :coverage/scored (count docs)
                  :coverage/skipped (count skipped)
                  :coverage/skipped-oversize (count (filter #(= :oversize (:skip %)) skipped))
                  :coverage/skipped-unreadable (count (filter #(= :unreadable (:skip %)) skipped))
                  :coverage/parse-failed (count (filter #(= :failed (:corpus/parse %)) docs))
                  :coverage/gold (get by-tier :gold 0)
                  :coverage/silver (get by-tier :silver 0)
                  :coverage/bronze (get by-tier :bronze 0)
                  :coverage/excluded (get by-tier :excluded 0)
                  :source/dataset (get-in policy [:query :source/dataset])}
        entities (concat docs shards [coverage])
        numbered (map-indexed (fn [i e] (assoc e :db/id (- (inc i)))) entities)]
    {:docs docs :shards shards :coverage coverage
     :text (str ";; 90-docs/corpus/corpus.datoms.edn — **生成物**。手編集禁止。\n"
                ";; 再生成: nbb scripts/gen-training-corpus.cljs\n"
                ";; 正本の policy: manifest/corpus-policy.edn (" (:policy/id policy) ")\n"
                ";; 設計: ADR-2608056000。query: :source/dataset \"training-corpus\"\n"
                ";;\n"
                ";; :corpus/score は 6 軸の重み付き**幾何平均**。:corpus/tier が :excluded の\n"
                ";; 文書は hazard 検出により score に関係なく学習から外れる。\n"
                ";; hazard 検査は構造的（既知の形だけ）で、秘密の不在を証明しない。\n"
                (print-vec numbered))}))

;; ---------------------------------------------------------------------------
;; --verify-index: **索引に載っている行だけ**を再計算して照合する。
;;
;; なぜ `--check`（全再生成の bytes 一致）を gate にしないか: この repo は
;; 並行セッションが毎日 ADR を足す。全再生成一致を gate にすると、**新しい文書が
;; 1 つ landed した瞬間に赤**になり、以後ずっと赤のままになる。CLAUDE.md が
;; gen-concept-index について書いているとおり「常に赤い gate は無視され、
;; 無視される gate は存在しないのと同じ」。
;;
;; **索引はスナップショットで、tree は動き続ける。** だから「今の tree で採点し直した
;; 結果と一致するか」は、どう切り取っても安定な不変条件にならない。実際この設計は
;; 3 回作り直している（3 回目は closing の監査で見つけた）:
;;
;;   1. 全再生成の bytes 一致 → **文書が 1 件 landed した瞬間に赤**
;;   2. 索引にある行だけ再採点 → **既存の文書が 1 件編集された瞬間に赤**。この repo は
;;      「文書は最新状態のみを表す。履歴は git に任せる」（ADR-2607257000）なので
;;      ADR の本文は日常的に書き換わる —— 実測、merge の 23 commit 後に 5 件が該当した
;;   3. **記録された sha256 と現在の bytes が一致する行だけ**再採点（この実装）
;;
;; 3 の理屈: 行が主張しているのは「*この bytes* を採点したらこの点になった」であって
;; 「この path は今もこの点だ」ではない。だから:
;;
;;   file が消えた / sha256 が変わった → **その行は検証できない。stale として報告し通す**
;;   sha256 が記録どおり            → **完全一致でなければならない**（改竄検出はここ）
;;
;; これは tree が動いても壊れない。かつ改竄は依然として捕まる —— スコアだけ手で
;; 書き換えても file の bytes は変わらないので sha256 は一致し、再採点で必ず食い違う。
(defn- verify-index! []
  (when-not (fs/existsSync out-path)
    (println "MISSING: 90-docs/corpus/corpus.datoms.edn") (js/process.exit 1))
  (let [indexed (->> (edn/read-string {:default (fn [_t v] v)}
                                      (str (fs/readFileSync out-path "utf8")))
                     (filter :corpus/id))
        by-id (into {} (map (juxt :corpus/id identity)) indexed)
        excluded (set (get-in policy [:scan :exclude-paths]))
        prefixes (vec (get-in policy [:scan :exclude-prefixes]))
        on-disk (->> (get-in policy [:scan :roots])
                     (mapcat (fn [r]
                               (let [k (:root/kind r)]
                                 (map (fn [p] [k (rel p)])
                                      (walk (path/join root (:root/path r)) (:root/ext r))))))
                     (remove (fn [[_ id]] (or (contains? excluded id)
                                              (some #(str/starts-with? id %) prefixes))))
                     (reduce (fn [acc [k id]] (if (contains? acc id) acc (assoc acc id k))) {}))
        unindexed (remove #(contains? by-id %) (keys on-disk))
        classified
        (->> indexed
             (map (fn [e]
                    (let [id (:corpus/id e)
                          kind (get on-disk id)
                          buf (when kind (read-buffer (path/join root id)))]
                      (cond
                        (nil? kind) [:gone id]
                        (nil? buf) [:gone id]
                        ;; bytes が変わっていれば、この行が主張している対象は
                        ;; もう存在しない。検証不能であって不正ではない。
                        (not= (sha256 buf) (:corpus/sha256 e)) [:changed id]
                        :else
                        (let [fresh (dissoc (entity-of kind (path/join root id)) :db/id)
                              stored (dissoc e :db/id)]
                          (if (= (into (sorted-map) fresh) (into (sorted-map) stored))
                            [:verified id]
                            [:mismatch id]))))))
             (group-by first))
        n (fn [k] (count (get classified k)))
        mismatched (mapv second (get classified :mismatch))]
    (println (str "indexed " (count indexed) " row(s): "
                  (n :verified) " verified against unchanged bytes, "
                  (n :changed) " stale (document edited since indexing), "
                  (n :gone) " stale (document removed); "
                  (count unindexed) " document(s) on disk not yet indexed"))
    (if (seq mismatched)
      (do (println (str "MISMATCH: " (count mismatched)
                        " row(s) whose file is byte-identical to what was indexed"
                        " do not reproduce — the index was edited by hand:"))
          (doseq [id (take 10 mismatched)] (println (str "  - " id)))
          (println "  run: nbb scripts/gen-training-corpus.cljs")
          (js/process.exit 1))
      (println (str "OK: all " (n :verified)
                    " verifiable row(s) reproduce exactly"
                    " (stale rows are reported, not failed — the index is a snapshot"
                    " and the tree moves)")))))

(defn- main-generate! []
  (let [{:keys [text docs shards coverage]} (build)]
    (if check?
      (let [current (when (fs/existsSync out-path) (str (fs/readFileSync out-path "utf8")))]
        (if (= current text)
          (println "OK: 90-docs/corpus/corpus.datoms.edn is canonical"
                   (str "(" (count docs) " docs)"))
          (do (println "STALE: 90-docs/corpus/corpus.datoms.edn differs from generator output")
              (println "  run: nbb scripts/gen-training-corpus.cljs")
              (js/process.exit 1))))
      (do
        (fs/mkdirSync (path/dirname out-path) #js {:recursive true})
        (fs/writeFileSync out-path text)
        (println (str "wrote " (rel out-path)
                      " — " (count docs) " docs"
                      " (gold " (:coverage/gold coverage)
                      " / silver " (:coverage/silver coverage)
                      " / bronze " (:coverage/bronze coverage)
                      " / excluded " (:coverage/excluded coverage) ")"
                      ", skipped " (:coverage/skipped coverage)))
        (when shard?
          (let [dir (path/join root (get-in policy [:bytes-plane :build/out-dir]))]
            (fs/mkdirSync dir #js {:recursive true})
            (doseq [s shards]
              (let [tier (:shard/tier s)
                    body (shard-body (filter #(= tier (:corpus/tier %)) docs))
                    p (path/join dir (str (name tier) "-v1.edn"))]
                (fs/writeFileSync p body)
                (println (str "wrote " (rel p) " — " (:shard/bytes s) " bytes, key "
                              (:shard/annex-key s)))))))))))

;; Explicit AWAI seed export uses this existing corpus host. It never scans or
;; exports the workspace document corpus, and never promotes research to serving.
(defn- awai-errors [seed]
  (let [models (:models seed)
        rows (mapcat (fn [[model spec]]
                       (mapcat (fn [split]
                                 (map #(assoc % :model model :split split)
                                      (get spec split []))) [:train :eval])) models)
        duplicates (fn [f] (->> rows (map f) frequencies (filter #(> (val %) 1)) seq))
        norm #(str/replace (str/lower-case (str/trim (or % ""))) #"\s+" " ")]
    (vec
     (concat
      (when-not (= #{:basho :hokusai} (set (keys models))) [:model-set])
      (when-not (= :research-only (:training-purpose seed)) [:research-purpose])
      (when-not (= :new-synthetic (get-in seed [:authorship :kind])) [:source-kind])
      (when (str/blank? (get-in seed [:authorship :source])) [:source-missing])
      (when-not (seq (get-in models [:basho :train])) [:empty-training])
      (when (some #(empty? (:eval (val %))) models) [:empty-evaluation])
      (when (duplicates :id) [:duplicate-id])
      (when (duplicates #(norm (:prompt %))) [:duplicate-prompt])
      (when (some (fn [[_ rs]] (> (count (set (map :split rs))) 1))
                  (group-by (juxt :model :family) rows)) [:family-split-leakage])
      (when (some #(or (str/blank? (:id %)) (str/blank? (:family %))
                      (str/blank? (:prompt %))) rows) [:invalid-row])
      (when (some #(and (= :train (:split %)) (str/blank? (:answer %))) rows)
        [:missing-answer])
      (when (some #(and (= :eval (:split %))
                       (or (not (seq (:rubric %))) (some str/blank? (:rubric %)))) rows)
        [:missing-rubric])
      (when (seq (get-in models [:hokusai :train])) [:video-text-not-training-clips])))))

(defn- awai-self-test! []
  (let [seed (read-edn (path/join root "manifest/awai-model-seed.edn"))
        cases [[seed []]
               [(assoc-in seed [:models :basho :train] []) [:empty-training]]
               [(assoc seed :training-purpose :commercial) [:research-purpose]]
               [(assoc-in seed [:authorship :source] "") [:source-missing]]
               [(assoc-in seed [:models :basho :train 0 :answer] "") [:missing-answer]]
               [(assoc-in seed [:models :basho :eval 0 :family] "delivery-register")
                [:family-split-leakage]]
               [(assoc-in seed [:models :basho :eval 0 :prompt]
                          (str "  " (get-in seed [:models :basho :train 0 :prompt]) "  "))
                [:duplicate-prompt]]
               [(assoc-in seed [:models :hokusai :eval] []) [:empty-evaluation]]]]
    (doseq [[input expected] cases]
      (let [actual (awai-errors input)]
        (when-not (= expected actual)
          (throw (ex-info "AWAI control failed" {:expected expected :actual actual})))))
    (println "AWAI seed: 8 controls passed, including exact rejection reasons")))

(defn- awai-export! []
  (let [argv (vec *command-line-args*)
        idx (.indexOf argv "--awai-research-export")
        dest (get argv (inc idx))
        input (path/join root "manifest/awai-model-seed.edn")
        seed (read-edn input)
        errors (awai-errors seed)]
    (when (seq errors) (throw (ex-info "AWAI seed rejected" {:reasons errors})))
    (when (or (str/blank? dest) (str/starts-with? dest "--"))
      (throw (ex-info "Provide a new output directory" {})))
    ;; Refuse overwrite: evaluated/trained artifacts must remain tied to bytes.
    (fs/mkdirSync dest)
    (let [files
          (mapv
           (fn [[file rows]]
             (let [body (str (str/join "\n" (map #(js/JSON.stringify (clj->js %)) rows)) "\n")]
               (fs/writeFileSync (path/join dest file) body #js {:flag "wx"})
               {:file file :rows (count rows) :sha256 (sha256 body)}))
           [["basho-train.jsonl"
             (mapv (fn [r] {:messages [{:role "user" :content (:prompt r)}
                                       {:role "assistant" :content (:answer r)}]})
                   (get-in seed [:models :basho :train]))]
            ["basho-eval.jsonl" (get-in seed [:models :basho :eval])]
            ["hokusai-eval.jsonl" (get-in seed [:models :hokusai :eval])]])
          receipt {:purpose :research-only :commercial-ready false :trained-model false
                   :source "manifest/awai-model-seed.edn"
                   :source-sha256 (sha256 (fs/readFileSync input))
                   :authorship (:authorship seed) :files files
                   :limits ["Seed only, not a production corpus or independent benchmark"
                            "Exact prompt and family checks do not detect semantic leakage"
                            "No video training clips or trained artifacts exported"
                            "Training and evaluation quality have not been measured"]}]
      (fs/writeFileSync (path/join dest "receipt.edn") (str (pr-str receipt) "\n")
                        #js {:flag "wx"})
      (println (pr-str receipt)))))

(defn -main []
  (cond
    (contains? args "--awai-self-test") (awai-self-test!)
    (contains? args "--awai-research-export") (awai-export!)
    verify-index? (verify-index!)
    :else (main-generate!)))

(-main)
