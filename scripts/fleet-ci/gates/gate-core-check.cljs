#!/usr/bin/env nbb
;; gate-core-check.cljs — BMC gate evaluator の **判定を 2 実装が同じに下すか**を
;; 見る（ADR-2608159300）。
;;
;; ## 何を突き合わせるか
;;
;;   正本    70-tools/bmc/kotoba/gate_core.kotoba   （kotoba/pure、capability 0）
;;   参照    70-tools/bmc/src/gftd/gate.cljc        （daily routine が実際に読む）
;;
;; ノードに kotoba ツールチェーンは無い（fleet が配るのは **この repo の tree だけ**で、
;; `orgs/` の amu は入らない）。だから fabric-actor-core gate と同じく、**コンパイル済みの
;; `.mjs` を配って import する**。gate 自身はコンパイルしない。
;;
;; ## 5 つの検査
;;
;;   1. artifact <-> source —— `.mjs` の `sourceDigest` が `.kotoba` の sha256 と一致。
;;      `.kotoba` を直して再コンパイルを忘れると、**古い artifact に対して緑が出る**。
;;   2. purity —— `requiredCapabilities` が空。欄が **読めなかったとき空と読まない**。
;;   3. parity —— 下の case 表の全件で kernel == cljc。ε は無い（全部 i64）。
;;   4. evidence floor —— 実際に走った case 数が `--min` 未満なら落とす。表が空に
;;      なった gate は exit 0 で何も主張しない（ADR-2608136000）。
;;   5. **branch floor** —— 演算子 5 種・節コード 4 種・判定 3 種が全部現れたか。
;;      「幸せな経路だけの表」は、この workspace が繰り返し踏んでいる失敗そのもの。
;;      境界（lhs = rhs で `:>=` と `:>` が割れる点）も名指しで踏む。
;;
;; ## 参照が kernel を「使っている」ことも見る
;;
;; scalar 同士の一致だけだと、`evaluate-hyp` が kernel の規則を捨てて素の `>=` に
;; 戻っても緑になる。そこで fixture ごとに、**参照自身の `->bp` / `op-code` が出した
;; scalar** を kernel に通し、その判定が参照の `:status` と一致することを見る。
;; スケール規則は参照の 1 箇所に在り続ける（gate は写さない）。
;;
;; ## 使い方
;;
;;   nbb gate-core-check.cljs <repo-dir> [--min N] [--nbb CMD]
;;
;; `<dir>` は **引数の先頭**に置く（fleet はそう渡す。CLAUDE.md の実測済みの罠）。

(ns gate-core-check
  (:require [clojure.string :as str]
            [clojure.edn :as edn]
            ["node:child_process" :as cp]
            ["node:crypto" :as crypto]
            ["node:fs" :as fs]
            ["node:path" :as path]))

(def args (vec *command-line-args*))

;; `--min 10 .` のように書かれても `"10"` を tree のパスと誤読しないよう、
;; 値を取るフラグを飛ばして探す（CLAUDE.md 実測済みの罠）。
(def ^:private value-flags #{"--min" "--nbb"})

(def root
  (loop [[a & more] args prev nil]
    (cond (nil? a) "."
          (str/starts-with? a "--") (recur more a)
          (contains? value-flags prev) (recur more nil)
          :else a)))

(defn- opt [flag default]
  (let [i (.indexOf args flag)]
    (if (neg? i) default (get args (inc i)))))

(def min-cases (js/parseInt (opt "--min" "300")))

;; `--nbb CMD` は **手でこの gate を検証するためだけ**にある。`npx --yes <pkg> <args>`
;; は作者の laptop（npm 11.12.1）でスクリプトパスをパッケージ名と誤読して落ちるが、
;; fleet のノード（10.9.8 / 11.17.0）では正しく走る。override が無いと、この gate は
;; **落ちることを一度も見せられないまま landing する**。
(def nbb-cmd (opt "--nbb" nil))

(def failures (atom []))

(defn- fail! [& parts]
  (let [m (str/join " " (map str parts))]
    (swap! failures conj m)
    (println "FAIL" m)))

(defn- sha256 [buf] (-> (crypto/createHash "sha256") (.update buf) (.digest "hex")))

;; ---------------------------------------------------------------------------
;; case 表。**1 行足すことは「両実装がこの入力を同じに扱う」と主張すること。**

;; 演算子コード。有効な 5 つと、**無効な 2 つ**（0 = host が知らない `:op` に
;; 割り当てるコード、7 = 単に存在しないコード）。無効な op が
;; `code-unanswerable` になることは、この gate の主張の一部。
(def op-codes [1 2 3 4 5 0 7])

;; 比較する値の組。**境界（lhs = rhs）が要**——そこでだけ `:>=` と `:>` が割れる。
;; 番兵と範囲端の外側も踏む（欠測が 0 に化けていないこと）。
(def absent -9007199254740991)
(def max-bp 9000000000000000)

(def pairs
  [[0 0]                         ;; 境界（0）
   [10000 10000]                 ;; 境界（1.0）
   [9999 10000]                  ;; 1 bp 下
   [10001 10000]                 ;; 1 bp 上
   [200 200]                     ;; 閾値 0.02 の境界
   [3000 3000]                   ;; 閾値 0.3 の境界
   [2999 3000]
   [-10000 10000]                ;; 負 vs 正
   [-10000 -10000]               ;; 両方負・境界
   [-10001 -10000]
   [-9999 -10000]
   [max-bp max-bp]               ;; admit 範囲の端
   [(- 0 max-bp) (- 0 max-bp)]
   [(- 0 max-bp) max-bp]
   [(inc max-bp) 0]              ;; 端の 1 つ外（左）
   [0 (inc max-bp)]              ;; 端の 1 つ外（右）
   [(dec (- 0 max-bp)) 0]
   [0 (dec (- 0 max-bp))]
   [absent 0]                    ;; 欠測（左）
   [0 absent]                    ;; 欠測（右）
   [absent absent]               ;; 両方欠測
   [absent 10000]
   [1 0]
   [0 1]])

;; `code-verdict` に渡すコード。定義域の 4 つと、**定義域の外の 2 つ**
;; （既定で blocked にしていないことの確認）。
(def clause-codes [-2 -1 0 1 42 -42])

;; 連言の畳み込みに食わせる列。**空の列**を含む——0 本の `:all` を validated と
;; 綴らないことがこの kernel の主張の 1 つ。順序が効くこと（absent が後から来ても
;; 前から来ても blocked）も踏む。
(def folds
  [[] [1] [0] [-1] [-2]
   [1 1] [1 0] [0 1] [1 -1] [-1 1] [0 -1] [-1 0]
   [1 1 1] [1 0 1] [1 -1 0] [0 0] [-1 -1]
   [1 -2] [-2 1] [0 -2] [-1 -2] [-2 -1]
   [1 1 1 1 0] [1 42]])

;; `all-verdict` の (acc, n)。n = 0 の行が床。
(def verdict-accs [2 1 0 -2])
(def verdict-ns [0 1 2 5])

;; 再提案規則。3×3 の全組み合わせ + 順位の外側。
(def status-pairs
  (vec (for [c [0 1 2] p [0 1 2]] [c p])))

;; `value-admitted` に渡す値。
(def admit-values
  [0 1 -1 10000 -10000 max-bp (- 0 max-bp) (inc max-bp) (dec (- 0 max-bp))
   absent (- 0 absent) 9007199254740991])

;; 定数。**host の def と kernel の 0 引数関数が同じ数を言うか。**
;; ここがずれると、以降の全 case が「同じ入力」を突き合わせていない。
(def constant-names
  ["op-ge" "op-gt" "op-le" "op-lt" "op-eq"
   "code-unanswerable" "code-absent" "code-unmet" "code-met"
   "verdict-blocked" "verdict-measuring" "verdict-validated"
   "status-untested" "status-measuring" "status-validated"
   "absent-bp" "max-abs-bp" "all-init"])

;; spec 単位の fixture。`:from` は実在の gate-spec、`:spec` は合成。
;; **3 つの判定が全部現れる**ように選んである（branch floor が見る）。
(def fixtures
  [{:label "isekai viral 1.5 >= 1.0"      :metrics {:fork {:viral-coefficient 1.5}} :from :hyp/isekai-fork-viral}
   {:label "isekai viral 1.0 境界"        :metrics {:fork {:viral-coefficient 1.0}} :from :hyp/isekai-fork-viral}
   {:label "isekai viral 0.5"             :metrics {:fork {:viral-coefficient 0.5}} :from :hyp/isekai-fork-viral}
   {:label "isekai viral 欠測"            :metrics {}                               :from :hyp/isekai-fork-viral}
   {:label "isekai viral 非数値"          :metrics {:fork {:viral-coefficient "n/a"}} :from :hyp/isekai-fork-viral}
   {:label "isekai viral 文字列の数"      :metrics {:fork {:viral-coefficient "1.5"}} :from :hyp/isekai-fork-viral}
   {:label "apex 転換率 0.02 境界"        :metrics {:conversion {:pct 0.02}}         :from :hyp/apex-privacy-premium}
   {:label "apex 転換率 0.019"            :metrics {:conversion {:pct 0.019}}        :from :hyp/apex-privacy-premium}
   {:label "aozora 比率 0.3 境界"         :metrics {:engagement {:organism-engagement-ratio 0.3}} :from :hyp/aozora-organism-content}
   ;; **`:> 0` の gate に欠測を渡す**——0 として渡していたら validated に化ける経路。
   {:label "nexus agent-hint 欠測"        :metrics {}                               :from :hyp/nexus-x402-agent-demand}
   {:label "nexus agent-hint 0"           :metrics {:catalog {:settlements {:agent-hint {:agent 0}}}} :from :hyp/nexus-x402-agent-demand}
   {:label "nexus agent-hint 1"           :metrics {:catalog {:settlements {:agent-hint {:agent 1}}}} :from :hyp/nexus-x402-agent-demand}
   {:label "yukkuri YPP 両方到達"         :metrics {:channel {:subscribers 1000 :watch-hours 4000}} :from :hyp/yukkuri-ypp-then-rpm}
   {:label "yukkuri YPP 片方未達"         :metrics {:channel {:subscribers 1000 :watch-hours 10}}   :from :hyp/yukkuri-ypp-then-rpm}
   {:label "yukkuri YPP 片方欠測"         :metrics {:channel {:subscribers 1000}}    :from :hyp/yukkuri-ypp-then-rpm}
   {:label "shinshi GMV > ad"             :metrics {:revenue {:creator-gmv-jpy 500000 :ad-revenue-jpy 100000}} :from :hyp/club-shinshi-creator-take}
   {:label "shinshi GMV = ad（境界）"     :metrics {:revenue {:creator-gmv-jpy 100000 :ad-revenue-jpy 100000}} :from :hyp/club-shinshi-creator-take}
   {:label "shinshi ad 欠測"              :metrics {:revenue {:creator-gmv-jpy 500000}} :from :hyp/club-shinshi-creator-take}
   {:label "合成 :<= 到達"                :metrics {:a 1}   :spec {:metric [:a] :op :<= :threshold 2 :evidence-label "L"}}
   {:label "合成 :<= 未達"                :metrics {:a 3}   :spec {:metric [:a] :op :<= :threshold 2 :evidence-label "L"}}
   {:label "合成 :< 境界"                 :metrics {:a 2}   :spec {:metric [:a] :op :< :threshold 2 :evidence-label "L"}}
   {:label "合成 := 到達"                 :metrics {:a 2}   :spec {:metric [:a] :op := :threshold 2 :evidence-label "L"}}
   {:label "合成 := 未達"                 :metrics {:a 3}   :spec {:metric [:a] :op := :threshold 2 :evidence-label "L"}}])

;; ---------------------------------------------------------------------------
;; cljc 側: 参照実装を子プロセスで起動して答えだけ受け取る。**規則を写さない。**
;;
;; classpath は root の `nbb.edn` が既定で持つものと同じ 3 つ（`70-tools/bmc/src`
;; が daily routine の読み方、`.` と `scripts/nbb_compat` は `gftd.canvas` が
;; `scripts.nbb-compat` を require するため）。

(defn- ref-answers []
  (let [expr (str "(require '[gftd.gate :as g])"
                  "(prn {:constants (into {} (for [n " (pr-str constant-names)
                  "] [n (case n \"op-ge\" g/op-ge \"op-gt\" g/op-gt \"op-le\" g/op-le"
                  "     \"op-lt\" g/op-lt \"op-eq\" g/op-eq"
                  "     \"code-unanswerable\" g/code-unanswerable \"code-absent\" g/code-absent"
                  "     \"code-unmet\" g/code-unmet \"code-met\" g/code-met"
                  "     \"verdict-blocked\" g/verdict-blocked \"verdict-measuring\" g/verdict-measuring"
                  "     \"verdict-validated\" g/verdict-validated"
                  "     \"status-untested\" g/status-untested \"status-measuring\" g/status-measuring"
                  "     \"status-validated\" g/status-validated"
                  "     \"absent-bp\" g/absent-bp \"max-abs-bp\" g/max-abs-bp (g/all-init))]))"
                  " :value-admitted (into {} (for [v " (pr-str admit-values)
                  "] [v (g/value-admitted v)]))"
                  " :clause-code (into {} (for [[l r] " (pr-str pairs)
                  " o " (pr-str op-codes) "] [[l o r] (g/clause-code l o r)]))"
                  " :clause-verdict (into {} (for [[l r] " (pr-str pairs)
                  " o " (pr-str op-codes) "] [[l o r] (g/clause-verdict l o r)]))"
                  " :code-verdict (into {} (for [c " (pr-str clause-codes)
                  "] [c (g/code-verdict c)]))"
                  " :all-step (into {} (for [a " (pr-str verdict-accs)
                  " c " (pr-str clause-codes) "] [[a c] (g/all-step a c)]))"
                  " :all-verdict (into {} (for [a " (pr-str verdict-accs)
                  " n " (pr-str verdict-ns) "] [[a n] (g/all-verdict a n)]))"
                  " :fold (into {} (for [f " (pr-str folds)
                  "] [f (g/all-verdict (reduce g/all-step (g/all-init) f) (count f))]))"
                  " :should-propose (into {} (for [[c p] " (pr-str status-pairs)
                  "] [[c p] (g/should-propose c p)]))"
                  " :status-rank (into {} (for [s [:untested :measuring :validated :invalidated nil]"
                  "] [s (g/status-rank s)]))"
                  ;; fixture ごとに、参照が実際に返した status と、参照自身の
                  ;; `->bp` / `op-code` が出した scalar を返す。
                  " :fixtures (into [] (for [fx " (pr-str fixtures)
                  "] (let [spec (or (:spec fx) (get g/gate-specs (:from fx)))"
                  "        m (:metrics fx)"
                  "        cl (cond (:all spec) (vec (for [c (:all spec)]"
                  "                                    [(g/->bp (get-in m (:metric c))) (g/op-code (:op c)) (g/->bp (:threshold c))]))"
                  "                 (:compare spec) (let [cmp (:compare spec)]"
                  "                                   [[(g/->bp (get-in m (:lhs cmp))) (g/op-code (:op cmp)) (g/->bp (get-in m (:rhs cmp)))]])"
                  "                 (:metric spec) [[(g/->bp (get-in m (:metric spec))) (g/op-code (:op spec)) (g/->bp (:threshold spec))]]"
                  "                 :else nil)]"
                  "    {:label (:label fx) :clauses cl :status (:status (g/evaluate-hyp m spec))})))"
                  " :spec-count (count g/gate-specs)})")
        [prog pre] (if nbb-cmd
                     (do (println "NOTE: using --nbb" nbb-cmd "instead of the pinned npx nbb")
                         [nbb-cmd []])
                     ["npx" ["--yes" "nbb"]])
        r (cp/spawnSync prog (clj->js (concat pre ["--classpath" "70-tools/bmc/src:scripts/nbb_compat:."
                                                   "-e" expr]))
                        #js {:cwd root :encoding "utf8" :maxBuffer (* 64 1024 1024)})
        out (str (aget r "stdout"))
        err (str (aget r "stderr"))]
    (if (or (not= 0 (aget r "status")) (str/blank? out))
      (do (fail! "could not run 70-tools/bmc/src/gftd/gate.cljc —"
                 (str/join " " (take-last 4 (str/split-lines (str err out)))))
          nil)
      (try (edn/read-string out)
           (catch :default e
             (fail! "unreadable output from the cljc reference:" (.-message e))
             nil)))))

;; ---------------------------------------------------------------------------
;; kotoba 側。**trap を失敗として捕まえる** —— 外へ投げると gate ごと落ちて、
;; 診断が「artifact を import できない」になり原因を取り違える。
;;
;; **`:i64` の seam は BigInt。** 数をそのまま渡すと `invalid-i64` で trap する。
;; 戻りの BigInt も Number へ戻す —— 戻り側は throw すらしないので、放置すると
;; 「一致しない」ではなく「静かに別の値」になる（ADR-2608122000）。
;; fuel は instance ごとに尽きるので 1 呼び出し 1 instance。

(defn- ->guest [v] (js/BigInt v))
(defn- <-guest [v] (js/Number v))

(defn- kernel-caller [m]
  (let [instantiate (aget m "instantiateKotoba")]
    (fn [fname argv]
      (try {:ok (<-guest (apply (aget (instantiate #js {}) fname) (map ->guest argv)))}
           (catch :default e {:trap (.-message e) :fn fname :args argv})))))

(def ran (atom 0))
(def seen-ops (atom #{}))
(def seen-codes (atom #{}))
(def seen-verdicts (atom #{}))

(defn- expect! [k fname argv want]
  (swap! ran inc)
  (let [got (k fname argv)]
    (cond
      (:trap got)
      (do (fail! fname (pr-str argv) "TRAPPED —" (:trap got)) nil)
      (not= (:ok got) want)
      (do (fail! fname (pr-str argv) "— kernel says" (pr-str (:ok got))
                 "but cljc says" (pr-str want))
          nil)
      :else (:ok got))))

(defn- check-parity! [m ref]
  (let [k (kernel-caller m)]
    ;; 定数（0 引数関数 vs host の def）
    (doseq [[n want] (:constants ref)]
      (expect! k n [] want))
    ;; 値の admit
    (doseq [[v want] (:value-admitted ref)] (expect! k "value-admitted" [v] want))
    ;; 1 節（演算子 × 値の組）
    (doseq [[[l o r] want] (:clause-code ref)]
      (swap! seen-ops conj o)
      (when-let [got (expect! k "clause-code" [l o r] want)] (swap! seen-codes conj got)))
    (doseq [[[l o r] want] (:clause-verdict ref)] (expect! k "clause-verdict" [l o r] want))
    (doseq [[c want] (:code-verdict ref)] (expect! k "code-verdict" [c] want))
    ;; 連言
    (doseq [[[a c] want] (:all-step ref)] (expect! k "all-step" [a c] want))
    (doseq [[[a n] want] (:all-verdict ref)] (expect! k "all-verdict" [a n] want))
    ;; 畳み込み全体（host が回す順序も含めて）
    (doseq [[f want] (:fold ref)]
      (swap! ran inc)
      (let [acc (reduce (fn [a c] (:ok (k "all-step" [a c]))) (:ok (k "all-init" [])) f)
            got (:ok (k "all-verdict" [acc (count f)]))]
        (when (not= got want)
          (fail! "fold" (pr-str f) "— kernel says" (pr-str got) "but cljc says" (pr-str want)))))
    ;; 再提案規則
    (doseq [[[c p] want] (:should-propose ref)] (expect! k "should-propose" [c p] want))
    ;; spec 単位: 参照の scalar を kernel に通した判定 == 参照の :status
    (let [verdict->kw {(:ok (k "verdict-blocked" [])) :blocked
                       (:ok (k "verdict-measuring" [])) :measuring
                       (:ok (k "verdict-validated" [])) :validated}]
      (doseq [{:keys [label clauses status]} (:fixtures ref)]
        (swap! seen-verdicts conj status)
        (if (nil? clauses)
          (fail! "fixture" (pr-str label) "produced no clauses — the case table asserts nothing about it")
          (do
            (swap! ran inc)
            (let [acc (reduce (fn [a [l o r]]
                                (:ok (k "all-step" [a (:ok (k "clause-code" [l o r]))])))
                              (:ok (k "all-init" []))
                              clauses)
                  code (:ok (k "all-verdict" [acc (count clauses)]))
                  got (get verdict->kw code)]
              (cond
                (nil? got)
                (fail! "fixture" (pr-str label) "— kernel returned verdict code" (pr-str code)
                       "which is not one of blocked/measuring/validated")
                (not= got status)
                (fail! "fixture" (pr-str label) "— kernel decides" (pr-str got)
                       "but gate.cljc/evaluate-hyp returned" (pr-str status))))))))))

;; ---------------------------------------------------------------------------

(defn- branch-floor! [ref]
  ;; 幸せな経路だけの表を落とす。**`when (seq …)` の中に入れない。**
  (let [missing (fn [want seen] (vec (remove seen want)))
        want-ops [0 1 2 3 4 5 7]
        want-codes [-2 -1 0 1]
        want-verdicts [:blocked :measuring :validated]]
    (when (seq (missing want-ops @seen-ops))
      (fail! "not every operator code was exercised — missing"
             (pr-str (missing want-ops @seen-ops))))
    (when (seq (missing want-codes @seen-codes))
      (fail! "not every clause code was produced — missing"
             (pr-str (missing want-codes @seen-codes))
             "— a table that never produces -1 (absent) or -2 (unanswerable) asserts nothing"
             "about the branch this migration exists to fix"))
    (when (seq (missing want-verdicts @seen-verdicts))
      (fail! "not every verdict was reached by a fixture — missing"
             (pr-str (missing want-verdicts @seen-verdicts))))
    ;; 境界そのもの: lhs = rhs で `:>=` と `:>` が割れていること。
    (let [ge (get (:clause-code ref) [10000 1 10000])
          gt (get (:clause-code ref) [10000 2 10000])]
      (when-not (and (= 1 ge) (= 0 gt))
        (fail! "the boundary case is not discriminating: at lhs = rhs the reference says"
               ":>= ->" (pr-str ge) "and :> ->" (pr-str gt)
               "— they must differ, or the table cannot tell the two operators apart")))))

(defn- main! []
  (let [src      (path/join root "70-tools" "bmc" "kotoba" "gate_core.kotoba")
        artifact (path/join root "70-tools" "bmc" "kotoba" "gate_core.mjs")]
    (cond
      (not (fs/existsSync src))      (do (fail! "missing" src) (set! (.-exitCode js/process) 1))
      (not (fs/existsSync artifact)) (do (fail! "missing" artifact) (set! (.-exitCode js/process) 1))
      :else
      (let [want (sha256 (fs/readFileSync src))
            js   (fs/readFileSync artifact "utf8")
            got  (second (re-find #"sourceDigest:\"([0-9a-f]+)\"" js))
            caps (second (re-find #"requiredCapabilities:Object\.freeze\(\[([^\]]*)\]\)" js))]
        (when-not (= want got)
          (fail! "artifact does not belong to the committed source:"
                 "gate_core.kotoba sha256" want
                 "but gate_core.mjs carries" (str got)
                 "— recompile with `kotoba -M compile … --target js`"))
        (cond
          (nil? caps)
          (fail! "could not read requiredCapabilities from the artifact"
                 "— the purity check cannot run, and absence is not a pass")
          (not= "" (str/trim caps))
          (fail! "the gate decision core is no longer pure — requiredCapabilities:" caps))
        (println "artifact<->source:" (if (= want got) "bound" "BROKEN")
                 "· capabilities:" (cond (nil? caps) "UNREADABLE"
                                         (= "" (str/trim caps)) "none"
                                         :else caps))
        (if-let [ref (ref-answers)]
          (do
            ;; gate-specs が静かに縮むと、fixture は縮まないのに **実在しない
            ;; 仮説について両実装が一致している**ことを確かめ続ける。
            (when (< (:spec-count ref) 14)
              (fail! "gftd.gate/gate-specs lists only" (:spec-count ref)
                     "hypotheses — the fixtures would go on checking gates nobody ships"))
            ;; `status-rank` は既定を持つ側なので、その既定が移行前の `not=` の
            ;; 意味と同じであることを名指しで見る。
            (let [sr (:status-rank ref)]
              (when-not (= [0 1 2 0 0] (mapv sr [:untested :measuring :validated :invalidated nil]))
                (fail! "gftd.gate/status-rank no longer means what the pre-migration"
                       "`(not= :validated s)` meant:" (pr-str sr))))
            (-> (js/import (str "file://" (path/resolve artifact)))
                (.catch (fn [e] (fail! "could not import the compiled artifact:" (.-message e)) nil))
                (.then (fn [m]
                         (when m (check-parity! m ref))
                         (branch-floor! ref)
                         (println (str "ran " @ran " cases, " (count @failures) " failing"))
                         ;; 絞り込みが壊れて 0 件を「合格」にしない床。上限ではない。
                         ;; **`when (seq …)` の中に入れない** —— 0 件こそが床の存在理由。
                         (when (< @ran min-cases)
                           (println "FAIL only" @ran "cases ran, floor is" min-cases
                                    "— a pass here would assert nothing")
                           (swap! failures conj "evidence floor"))
                         (when (seq @failures)
                           (println "")
                           (println "ADR-2608159300: gate 判定の正本は")
                           (println "70-tools/bmc/kotoba/gate_core.kotoba。参照は")
                           (println "70-tools/bmc/src/gftd/gate.cljc の 1 箇所だけで、")
                           (println "daily の BMC routine はそこを読む。食い違ったら")
                           (println "**kernel が正しい**。")
                           (set! (.-exitCode js/process) 1))))))
          (set! (.-exitCode js/process) 1))))))

(main!)
