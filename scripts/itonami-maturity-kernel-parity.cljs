#!/usr/bin/env nbb
;; scripts/itonami-maturity-kernel-parity.cljs — Kotoba カーネルと cljs 参照実装の
;; **完全一致**ゲート。ADR-2608052000。
;;
;; compiler の **依存閉包ごと** classpath に載せる。compiler/src だけでは
;; `Could not find namespace: kotoba.artifact.core` で起動しない（実測 2026-08-08）。
;; 閉包は **そのつど compiler/deps.edn から引く。列を貼らない** —— 貼った列は
;; compiler が名前空間を別 repo へ出すたびに腐り、gate は落ちるのではなく
;; *走らなくなる*（実測 2026-08-09: `kotoba.compiler.frontend` が #545 で
;; compiler → kotoba-sema へ移り、貼ってあった 12 repo の列がそれを含まず
;; `Could not find namespace: kotoba.compiler.frontend` で起動しなかった。
;; ここには「deps.edn から引く」と書いてあったのに、その下に引いた結果の
;; スナップショットが貼ってあったため、読む側は貼られた列の方を使った）:
;;
;;   CP=".:scripts/nbb_compat:orgs/kotoba-lang/compiler/src:orgs/kotoba-lang/compiler/resources"
;;   for r in $(grep -oE 'io\.github\.kotoba-lang/[a-z0-9-]+' orgs/kotoba-lang/compiler/deps.edn \
;;              | sed 's|.*/||' | sort -u); do
;;     [ -d "orgs/kotoba-lang/$r/src" ] || echo "MISSING checkout: $r"   # west update で取る
;;     CP="$CP:orgs/kotoba-lang/$r/src"
;;     [ -d "orgs/kotoba-lang/$r/resources" ] && CP="$CP:orgs/kotoba-lang/$r/resources"
;;   done
;;   nbb --classpath "$CP" scripts/itonami-maturity-kernel-parity.cljs \
;;     [--evidence manifest/itonami-maturity-evidence.edn] \
;;     [--datoms 90-docs/system-dynamics/itonami-maturity.datoms.edn] \
;;     [--kernel 90-docs/system-dynamics/kotoba/itonami_maturity_kernel.kotoba] \
;;     [--batch 300] [--limit N]
;;
;; ## 何を検査するか
;;
;; 生成済み datom 面に載っている **全 repo** の `:maturity/own-bp` が、同じ観測値を
;; Kotoba カーネルに通した結果と **1 の位まで一致する**こと。ε 付き比較ではない —
;; スコアを整数 basis point にしてあるので完全一致で書ける。浮動小数だと
;; 「どこまでズレてよいか」を誰かが決めることになり、その判断が検査から漏れる。
;;
;; ## どう検査するか（css.kotoba-parity-test と同じ形）
;;
;; repo 1 件につき **引数ゼロの `.kotoba` 関数を 1 個生成**し、その本体に
;; その repo の観測値を literal で埋め込んだスコア式まるごとを置く。カーネル本体と
;; 一緒にコンパイルして KIR インタプリタで実行する。つまり軸の計算も 7 軸の
;; 加重畳み込みも **全部 Kotoba の中**で起きる（host は literal を書くだけ）。
;;
;; 引数渡しで済ませないのは、`pair` ハンドルが 1 回の `ir/execute` の中でしか
;; 有効でないため — 畳み込みを host からの複数呼び出しに分けると
;; `invalid-pair-handle` で trap する（実測）。これは kernel の制約ではなく
;; 「畳み込みは 1 実行に収める」という KIR のアリーナ寿命の要請。
;;
;; ## k 定数・重み
;;
;; **生成済み datom 面の fleet-summary から読む。** ここで再計算すると
;; 「検査対象の答えを検査側が仮定する」ことになる。

;; ## KIR は compiler ではなく kotoba-kir にある（2026-08-08 修正）
;;
;; この gate は当初 `kotoba.compiler.ir` を require していたが、
;; **ADR-2607266000 Phase B がその名前空間を compiler から撤去した**
;; （「compiler は frontend -> KIR だけを所有し、残りは consume する」）。
;; 以来この gate は `Could not find namespace` で **一度も起動できていなかった** ——
;; 落ちたのではなく走らなかったので、「スコア算術はカーネルと一致している」が
;; 誰にも検査されないまま計測が landed し続けていた。
;; `lower` / `execute` は名前も引数も同じまま移っただけなので require の差し替えで足りる。
(ns itonami-maturity-kernel-parity
  (:require [clojure.string :as str]
            [clojure.edn :as edn]
            [kotoba.compiler.frontend :as frontend]
            [kotoba.kir :as ir]
            ["fs" :as fs]
            ["path" :as npath]))

(defn- parse-args [args]
  (loop [a args m {:evidence "manifest/itonami-maturity-evidence.edn"
                   :datoms "90-docs/system-dynamics/itonami-maturity.datoms.edn"
                   :kernel "90-docs/system-dynamics/kotoba/itonami_maturity_kernel.kotoba"
                   :batch 300 :limit nil}]
    (if (empty? a) m
        (let [[k v & more] a]
          (case k
            "--evidence" (recur more (assoc m :evidence v))
            "--datoms"   (recur more (assoc m :datoms v))
            "--kernel"   (recur more (assoc m :kernel v))
            "--batch"    (recur more (assoc m :batch (js/parseInt v 10)))
            "--limit"    (recur more (assoc m :limit (js/parseInt v 10)))
            (recur (rest a) m))))))

(def opts (parse-args (vec (.slice (.-argv js/process) 2))))
(defn- slurp* [p] (.toString (.readFileSync fs p) "utf8"))
(defn- resolve* [p] ((.-resolve npath) (.cwd js/process) p))

;; ---------------------------------------------------------------- load

(def evidence (edn/read-string (slurp* (resolve* (:evidence opts)))))
(def datoms   (edn/read-string (slurp* (resolve* (:datoms opts)))))
(def by-path  (into {} (map (juxt :repo/path identity) evidence)))

(def summary (or (first (filter #(= "fleet-summary" (:summary/kind %)) datoms))
                 (throw (ex-info "fleet-summary entity not found in datoms" {}))))

(def k-consts {:src (:model/k-src summary) :test (:model/k-test summary)
               :cite (:model/k-cite summary) :readme (:model/k-readme summary)
               :adr (:model/k-adr summary)})
(def weights (edn/read-string (:model/weights summary)))
(def stale-days (:model/stale-days summary))

;; freshness は評価時刻からの経過日数。ここで現在時刻を取り直すと日跨ぎで
;; ゲートが落ちる（スコアのバグではなく検査のバグ）。datom 面が記録している
;; 評価時刻をそのまま使う。
(def eval-now-ms
  (let [t (.parse js/Date (:scan/at summary))]
    (if (js/isNaN t)
      (throw (ex-info ":scan/at missing or unparseable in fleet-summary" {:at (:scan/at summary)}))
      t)))
(def component-total 7)

(def rows (vec (filter :maturity/own-bp datoms)))

(def kernel-src (slurp* (resolve* (:kernel opts))))

;; ---------------------------------------------------------------- codegen
;;
;; 生成する式は cljs 参照実装 (`itonami-maturity-dynamics.cljs` の axes-of /
;; weighted-mean-bp) と 1 対 1 対応する。対応が崩れたらこのゲートが落ちる。

(defn- i [n] (str (js/Math.trunc n)))

(defn- axis-exprs
  "repo 1 件の 7 軸を `.kotoba` 式にする。未測定の軸は nil を返し、畳み込みから外す
   （0 として混ぜない — 『測っていない』が『悪い』に化ける）。"
  [e]
  (let [days (when-let [iso (:git/last-commit e)]
               (let [t (.parse js/Date iso)]
                 (when-not (js/isNaN t)
                   (js/Math.floor (/ (- eval-now-ms t) 86400000.0)))))
        b (fn [x] (if x "10000" "0"))]
    {:m/substrate (str "(sat-bp " (i (:src/bytes e 0)) " " (i (:src k-consts)) ")")
     :m/test      (str "(sat-bp " (i (:test/bytes e 0)) " " (i (:test k-consts)) ")")
     :m/governed  (str "(ratio-bp " (i (:component/count e 0)) " " component-total ")")
     :m/ingest    (str "(sat-bp " (i (:ingest/citation-count e 0)) " " (i (:cite k-consts)) ")")
     :m/docs      (str "(axis3-bp (sat-bp " (i (:doc/readme-bytes e 0)) " " (i (:readme k-consts)) ")"
                       " (ratio-bp " (i (:doc/adr-count e 0)) " " (i (:adr k-consts)) ")"
                       " " (b (:doc/has-operator-quickstart? e)) ")")
     :m/surface   (str "(axis4-bp " (b (pos? (:surface/demo-file-count e 0)))
                       " " (b (:surface/has-cron-workflow? e))
                       " " (b (:doc/has-business-model? e))
                       " " (b (:doc/has-pricing? e)) ")")
     :m/fresh     (when days (str "(decay-bp " (i stale-days) " " (i days) ")"))}))

(defn- fold-expr
  "(値,重み) の列を acc-init/acc-step2/acc-step/acc-mean の入れ子式へ。
   arity 上限 5 いっぱいの acc-step2 で 2 軸ずつ畳む。"
  [pairs]
  (str "(acc-mean "
       (reduce (fn [acc chunk]
                 (if (= 2 (count chunk))
                   (let [[[v1 w1] [v2 w2]] chunk]
                     (str "(acc-step2 " acc " " v1 " " w1 " " v2 " " w2 ")"))
                   (let [[[v1 w1]] chunk]
                     (str "(acc-step " acc " " v1 " " w1 ")"))))
               "(acc-init)"
               (partition-all 2 pairs))
       ")"))

(defn- case-defn [nm e kind]
  (let [w (get weights kind (:default weights))
        ax (axis-exprs e)
        pairs (vec (keep (fn [[k wt]] (when-let [v (get ax k)] [v (i wt)])) w))]
    (when (seq pairs)
      (str "(defn " nm " [] :i64 " (fold-expr pairs) ")"))))

(def base-exports
  ["clamp-bp" "sat-bp" "decay-bp" "ratio-bp" "bool-bp" "axis3-bp" "axis4-bp"
   "acc-init" "acc-step" "acc-step2" "acc-mean"])

(defn- batch-source
  "カーネル本体 + 生成した case 群。:export 行はカーネルのものを置き換える
   （entryless library は非空の export 列を要求する）。"
  [case-names case-defns]
  (str (str/replace kernel-src
                    #"\(:export \[[^\]]*\]\)"
                    (str "(:export [" (str/join " " (concat base-exports case-names)) "])"))
       "\n\n;; --- generated parity cases ---\n"
       (str/join "\n" case-defns) "\n"))

;; ---------------------------------------------------------------- main

(defn -main []
  (let [targets (cond-> rows (:limit opts) (->> (take (:limit opts)) vec))
        t0 (.now js/Date)
        batches (partition-all (:batch opts) (map-indexed vector targets))
        _ (println (str "parity: " (count targets) " repos in "
                        (count batches) " batches of <=" (:batch opts)))
        results
        (vec (mapcat
              (fn [bi batch]
                (let [prepared (keep (fn [[gi r]]
                                       (let [e (by-path (:repo/path r))
                                             nm (str "case" gi)
                                             d (when e (case-defn nm e (:repo/kind r)))]
                                         (when d {:name nm :defn d :row r})))
                                     batch)
                      src (batch-source (map :name prepared) (map :defn prepared))
                      kir (ir/lower (frontend/analyze src))
                      out (mapv (fn [{:keys [name row]}]
                                  {:path (:repo/path row)
                                   :expected (:maturity/own-bp row)
                                   :got (js/Number (ir/execute kir (symbol name) []))})
                                prepared)]
                  (println (str "  batch " (inc bi) "/" (count batches)
                                " — " (count out) " cases (" (- (.now js/Date) t0) "ms)"))
                  out))
              (range) batches))
        missing (- (count targets) (count results))
        mismatches (vec (remove #(= (:expected %) (:got %)) results))
        elapsed (- (.now js/Date) t0)]
    (println (str "\nkernel: 90-docs/system-dynamics/kotoba/itonami_maturity_kernel.kotoba"))
    (println (str "checked " (count results) " repos in " elapsed "ms"
                  (when (pos? missing) (str "; " missing " skipped (no evidence row)"))))
    (if (and (empty? mismatches) (zero? missing))
      (do (println (str "  PASS — every :maturity/own-bp is reproduced exactly by the Kotoba kernel"))
          (.exit js/process 0))
      (do (when (pos? missing)
            (println (str "  FAIL — " missing " scored repos had no evidence row")))
          (when (seq mismatches)
            (println (str "  FAIL — " (count mismatches) " mismatches:"))
            (doseq [m (take 20 mismatches)]
              (println (str "    " (:path m) "  cljs=" (:expected m) "  kotoba=" (:got m)))))
          (.exit js/process 1)))))

(-main)
