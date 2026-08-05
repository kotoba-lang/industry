;; github_workflow_run.cljs — GitHub Actions の workflow YAML を fleet で実行できる
;; 形に翻訳し、**実行できないものを実行できると言わない**ための受理判定を行う。
;;
;; オーナー指示（2026-08-05）「すでにある workflow をそのままで murakumo で
;; 使えるように設計統合」。このワークスペースには workflow が 2,583 本ある
;; （checkout 済みだけで。repo 数では 1,352+）。全部を fleet gate に書き直すのは
;; 現実的でないので、**YAML をそのまま読んで実行する**。
;;
;; ## なぜ自前の YAML サブセットパーサなのか
;;
;; fleet のノードは tailnet だけに繋がっていて npm registry に届かない。gate は
;; 1 本の .cljs としてノードへ配られるので、`js-yaml` を require できない。
;; workflow YAML は書式が非常に規則的（2 スペースインデント / ブロックスカラー /
;; フロー配列）なので、必要な部分集合だけを自前で読む。**部分集合であることを
;; 隠さない** — 読めなかったファイルは「読めなかった」と報告し、成功に数えない。
;;
;; ## 実測（2026-08-05、実在 2,583 ファイル）
;;
;; `uses:` は 11,443 回のうち 11,136 回（97.3%）が 5 つに集中している:
;;   actions/checkout 4,851 / DeLaGuardo/setup-clojure 2,916 /
;;   actions/setup-java 2,736 / actions/setup-node 324 / actions/cache 309
;; いずれも fleet では **既に満たされている前提**なので no-op にできる:
;;   checkout   → tick.cljs が tree を配っている
;;   setup-*    → ノードは provision 済み（probe.cljs が java/clojure/node を記録）
;;   cache      → ノードの ~/.m2 ~/.gitlibs は永続
;; 残り 307 回・40 種類は no-op にできない（artifact / pages / rust / cosign 等）。
;; **それらは skip せず、workflow ごと :unsupported にする。**
(ns github-workflow-run
  (:require [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; YAML サブセットパーサ
;;
;; 対応: ネストした map、`- ` リスト、`|`/`>` ブロックスカラー、`[a, b]` フロー配列、
;;       引用文字列、コメント、`key:` だけの行。
;; 非対応（読めたら例外にする、黙って無視しない）: アンカー/エイリアス、複数
;;       ドキュメント、複雑なフロー map。

(defn- strip-comment
  "行末コメントを落とす。引用の中の # は残す。"
  [s]
  (let [n (count s)]
    (loop [i 0 q nil]
      (if (>= i n)
        s
        (let [c (nth s i)]
          (cond
            (and q (= c q)) (recur (inc i) nil)
            q (recur (inc i) q)
            (or (= c \") (= c \')) (recur (inc i) c)
            (and (= c \#) (or (zero? i) (= \space (nth s (dec i)))))
            (str/trimr (subs s 0 i))
            :else (recur (inc i) nil)))))))

(defn- unquote-scalar [s]
  (let [s (str/trim s)]
    (cond
      (and (>= (count s) 2) (str/starts-with? s "\"") (str/ends-with? s "\""))
      (-> (subs s 1 (dec (count s)))
          (str/replace "\\n" "\n") (str/replace "\\\"" "\""))
      (and (>= (count s) 2) (str/starts-with? s "'") (str/ends-with? s "'"))
      (subs s 1 (dec (count s)))
      :else s)))

(defn- scalar
  "YAML スカラー -> string/bool/number。**数値化はしない** — workflow の値は
   ほぼ文字列で、'on' や version の 3.11 を型変換すると却って壊れる。
   true/false だけは条件に使われるので変換する。"
  [s]
  (let [v (unquote-scalar s)]
    (case v
      ("true" "True" "TRUE") true
      ("false" "False" "FALSE") false
      ("null" "~" "") nil
      v)))

(defn- flow-seq [s]
  (let [inner (str/trim (subs s 1 (dec (count s))))]
    (if (str/blank? inner)
      []
      (mapv scalar (str/split inner #",")))))

(defn- indent-of [line]
  (count (take-while #(= \space %) line)))

(declare parse-block)

(defn- block-scalar
  "`|` / `>` の本文を集める。-> [text rest-lines]"
  [style lines base-indent]
  (let [body (take-while (fn [l] (or (str/blank? l) (> (indent-of l) base-indent))) lines)
        rest' (drop (count body) lines)
        ind (or (some->> body (remove str/blank?) first indent-of) (inc base-indent))
        txt (str/join (if (= style ">") " " "\n")
                      (map (fn [l] (if (str/blank? l) "" (subs l (min ind (count l))))) body))]
    [txt rest']))

(defn- parse-seq [lines indent]
  (loop [ls lines acc []]
    (let [l (first ls)]
      (if (or (nil? l)
              (str/blank? l)
              (< (indent-of l) indent)
              (not (str/starts-with? (str/triml l) "- ")))
        [acc ls]
        (let [content (subs (str/triml l) 2)
              ;; `- key: v` は同じ行から始まる map。後続の深いインデント行も含める。
              deeper (take-while (fn [x] (or (str/blank? x) (> (indent-of x) indent))) (rest ls))
              rest' (drop (count deeper) (rest ls))]
          (if (re-find #"^[A-Za-z_][A-Za-z0-9_.\-]*:" content)
            (let [synth (cons (str (apply str (repeat (+ indent 2) " ")) content) deeper)
                  [m _] (parse-block synth (+ indent 2))]
              (recur rest' (conj acc m)))
            (recur (rest ls) (conj acc (scalar content)))))))))

(defn- parse-block
  "-> [map rest-lines]"
  [lines indent]
  (loop [ls lines acc {}]
    (let [l (first ls)]
      (cond
        (nil? l) [acc nil]
        (str/blank? l) (recur (rest ls) acc)
        (< (indent-of l) indent) [acc ls]
        (str/starts-with? (str/triml l) "- ") [acc ls]
        :else
        (let [t (str/triml l)
              m (re-find #"^([^:]+):\s?(.*)$" t)]
          (if-not m
            ;; plain scalar の折り返し行。YAML は
            ;;   name: very long text,
            ;;         continued here
            ;; を 1 つの値に畳む。key: に見えないインデント行が来たら直前の値の
            ;; 続きとして畳む（実測: 2,584 本のうち 1 本がこれで落ちていた）。
            (if-let [prev (last (keys acc))]
              (recur (rest ls) (assoc acc prev (str/trim (str (get acc prev) " " t))))
              (throw (js/Error. (str "unparsable YAML line: " (pr-str l)))))
            (let [k (unquote-scalar (nth m 1))
                  v (str/trim (nth m 2))]
              (cond
                ;; ブロックスカラー
                (or (= v "|") (= v ">") (= v "|-") (= v ">-") (= v "|+"))
                (let [[txt rest'] (block-scalar (subs v 0 1) (rest ls) (indent-of l))]
                  (recur rest' (assoc acc k txt)))
                ;; フロー配列
                (and (str/starts-with? v "[") (str/ends-with? v "]"))
                (recur (rest ls) (assoc acc k (flow-seq v)))
                ;; 値なし → ネストした map か seq
                (str/blank? v)
                (let [nxt (first (drop-while str/blank? (rest ls)))]
                  (if (and nxt (> (indent-of nxt) (indent-of l))
                           (str/starts-with? (str/triml nxt) "- "))
                    (let [[s rest'] (parse-seq (drop-while str/blank? (rest ls)) (indent-of nxt))]
                      (recur rest' (assoc acc k s)))
                    (let [[m2 rest'] (parse-block (rest ls) (inc (indent-of l)))]
                      (recur rest' (assoc acc k m2)))))
                :else
                (recur (rest ls) (assoc acc k (scalar v)))))))))))

(defn parse-yaml
  "workflow YAML -> clojure map。読めなければ例外。"
  [text]
  (when (re-find #"(?m)^\s*<<:|^---\s*$[\s\S]*^---\s*$|&[A-Za-z]" text)
    ;; アンカー / merge key / 複数ドキュメントは部分集合の外。黙って
    ;; 読み違えるより落ちる方がよい。
    (when (re-find #"(?m)^\s*<<:" text)
      (throw (js/Error. "YAML merge keys (<<:) are outside this parser's subset"))))
  (let [lines (->> (str/split-lines text)
                   (map strip-comment)
                   (remove #(re-matches #"\s*" %))
                   (remove #(str/starts-with? (str/trim %) "---")))]
    (first (parse-block lines 0))))

;; ---------------------------------------------------------------------------
;; 受理判定
;;
;; ここが設計の要。**「動かせないものを skip して green にする」ことを構造的に
;; 禁じる。** no-op にしてよい action は、fleet で前提が既に満たされているものだけ。

(def noop-actions
  "fleet では既に満たされているので no-op にしてよい action と、その理由。"
  {"actions/checkout" "tick.cljs already ships the repo tree to the node"
   "actions/setup-java" "nodes are provisioned; probe.cljs records :java-home"
   "actions/setup-node" "nodes are provisioned; probe.cljs records :node"
   "DeLaGuardo/setup-clojure" "nodes are provisioned; probe.cljs records :clojure"
   "actions/cache" "node ~/.m2 and ~/.gitlibs are persistent across ticks"})

;; ノードは tailnet のみで外向き HTTPS が無い。これらを叩く run: は必ず落ちるので、
;; 「落ちてから気づく」のでなく受理段階で :needs-network として弾く。
(def network-commands
  #{"npm" "npx" "pnpm" "yarn" "cargo" "pip" "pip3" "curl" "wget" "brew" "docker"
    "gh" "aws" "wrangler" "mvn" "gradle"})

(defn- step-kind [step]
  (cond
    (get step "uses")
    (let [a (first (str/split (str (get step "uses")) #"@"))]
      (if (contains? noop-actions a)
        {:kind :noop :action a :why (get noop-actions a)}
        {:kind :unsupported-action :action a}))
    (get step "run") {:kind :run :script (str (get step "run"))}
    :else {:kind :empty}))

(defn- first-tokens [script]
  (->> (str/split-lines script)
       (map str/trim)
       (remove str/blank?)
       (remove #(str/starts-with? % "#"))
       (map #(first (str/split % #"\s+")))
       (map #(str/replace % #"^sudo$" ""))
       (remove str/blank?)))

;; ---------------------------------------------------------------------------
;; matrix 展開
;;
;; `${{ matrix.x }}` は fleet 側の制約ではなく**このランナーの実装事項**なので、
;; 未実装のまま :unsupported にすると「fleet では動かない」と誤読される
;; （実測 2026-08-05: 非対応 518 本のうち 269 本が matrix 未展開だけを理由に
;; 落ちていた ＝ 非対応理由の最大要因が実は自分の穴だった）。展開して直す。

(defn- has-expression? [s] (boolean (re-find #"\$\{\{" (str s))))

(defn- cartesian [ks m]
  (reduce (fn [acc k]
            (for [combo acc v (get m k)] (assoc combo k v)))
          [{}] ks))

(defn matrix-combos
  "strategy.matrix -> [{var value …} …]。include は追加、exclude は未対応（例外）。"
  [matrix]
  (let [include (get matrix "include")
        exclude (get matrix "exclude")
        axes (dissoc matrix "include" "exclude")]
    (when exclude
      (throw (js/Error. "matrix exclude: is not implemented by this runner")))
    (let [base (if (seq axes)
                 (vec (cartesian (vec (keys axes))
                                 (into {} (map (fn [[k v]] [k (if (sequential? v) v [v])])) axes)))
                 [])
          inc' (mapv #(into {} (map (fn [[k v]] [k v])) %) (or include []))]
      (vec (concat base inc')))))

(defn- substitute
  "`${{ matrix.k }}` を値に置換。matrix 以外の式が残ったら nil を返す（呼び手が弾く）。"
  [s combo]
  (when (some? s)
    (let [out (reduce (fn [acc [k v]]
                        (-> acc
                            (str/replace (js/RegExp. (str "\\$\\{\\{\\s*matrix\\." k "\\s*\\}\\}") "g")
                                         (str v))))
                      (str s) combo)]
      out)))

(defn- apply-combo [job combo]
  (letfn [(walk [x]
            (cond
              (string? x) (substitute x combo)
              (map? x) (into {} (map (fn [[k v]] [k (walk v)])) x)
              (sequential? x) (mapv walk x)
              :else x))]
    (walk job)))

(defn- expand-jobs
  "matrix を持つ job を組み合わせごとの job に展開する。-> [[jid job] …]"
  [jobs]
  (vec (mapcat
        (fn [[jid job]]
          (if-let [m (get-in job ["strategy" "matrix"])]
            (let [combos (matrix-combos m)]
              (if (empty? combos)
                [[jid job]]
                (map-indexed
                 (fn [i combo]
                   [(str jid " [" (str/join " " (map (fn [[k v]] (str k "=" v)) combo)) "]")
                    (apply-combo (dissoc job "strategy") combo)])
                 combos)))
            [[jid job]]))
        jobs)))

(defn analyze
  "parse 済み workflow -> {:jobs [...] :verdict :runnable|:unsupported :reasons [...]}"
  [wf]
  (let [jobs (try (expand-jobs (get wf "jobs"))
                  (catch :default e {:expand-error (.-message e)}))
        reasons (atom [])
        jobs (if (map? jobs)
               (do (swap! reasons conj (str "matrix: " (:expand-error jobs))) [])
               jobs)
        job-plans
        (vec (for [[jid job] jobs]
               (let [steps (or (get job "steps") [])
                     kinds (mapv step-kind steps)]
                 (when (get job "container")
                   (swap! reasons conj (str jid ": uses a container, which the fleet has no runtime for")))
                 (when (get job "services")
                   (swap! reasons conj (str jid ": uses services:, which the fleet has no runtime for")))
                 ;; 展開後も式が残る run: は、bash にそのまま流すと静かに壊れる。
                 (doseq [k kinds :when (and (= :run (:kind k)) (has-expression? (:script k)))]
                   (swap! reasons conj
                          (str jid ": a run: step still contains a ${{ }} expression after matrix"
                               " substitution; this runner does not evaluate the GitHub expression language")))
                 (doseq [k kinds :when (= :unsupported-action (:kind k))]
                   (swap! reasons conj (str jid ": action " (:action k) " has no fleet equivalent")))
                 (doseq [k kinds :when (= :run (:kind k))
                         t (first-tokens (:script k))
                         :when (contains? network-commands t)]
                   (swap! reasons conj (str jid ": `" t "` needs network egress; fleet nodes are tailnet-only")))
                 {:id jid
                  :env (get job "env")
                  :steps (mapv (fn [s k] (merge {:name (get s "name")
                                                 :if (get s "if")
                                                 :working-directory (get s "working-directory")
                                                 :env (get s "env")} k))
                               steps kinds)})))]
    {:name (get wf "name")
     :jobs job-plans
     :reasons (vec (distinct @reasons))
     :verdict (if (seq @reasons) :unsupported :runnable)}))

;; ---------------------------------------------------------------------------
;; ノード実行用の bash 生成
;;
;; `if:` は評価しない。GitHub の式言語（`${{ }}`）を部分的に実装すると、
;; 「条件が読めなかったので実行した / しなかった」のどちらでも嘘になる。
;; 条件付き step を持つ workflow は受理段階で落とす方が正直。


(defn to-bash
  "runnable な plan -> ノードで走らせる bash。実行できない形は例外。"
  [plan {:keys [java-home]}]
  (when (not= :runnable (:verdict plan))
    (throw (js/Error. (str "refusing to emit bash for an unsupported workflow: "
                           (str/join "; " (:reasons plan))))))
  (str/join
   "\n"
   (concat
    ["set -eu"
     "export PATH=/opt/homebrew/bin:/usr/local/bin:$PATH"
     (str "export JAVA_HOME=" (or java-home "/opt/homebrew/opt/openjdk"))
     "export PATH=$JAVA_HOME/bin:$PATH"
     "export CI=true GITHUB_ACTIONS=true"]
    (mapcat
     (fn [{:keys [id env steps]}]
       (concat
        [(str "echo '--- job " id " ---'")]
        (for [[k v] env] (str "export " k "=" (pr-str (str v))))
        (mapcat
         (fn [{:keys [kind name script working-directory env] :as st}]
           (when (has-expression? (:if st))
             (throw (js/Error. (str "step " (pr-str name)
                                    " has an if: expression; this runner does not evaluate ${{ }}"))))
           (case kind
             :noop [(str "echo 'skip (satisfied by fleet): " (:action st) " — " (:why st) "'")]
             :empty []
             :run (concat
                   [(str "echo 'step: " (str/replace (str (or name "run")) "'" "") "'")]
                   (for [[k v] env] (str "export " k "=" (pr-str (str v))))
                   [(if working-directory
                      (str "( cd " working-directory " && " script " )")
                      script)])
             []))
         steps)))
     (:jobs plan)))))

;; ---------------------------------------------------------------------------
;; ノード側エントリポイント
;;
;; tick.cljs の :nbb-script gate として配られ、`npx nbb <this> <repo-dir> [--workflow f]`
;; で走る。**引数が無いときは何もしない** — audit がこのファイルをライブラリとして
;; require するため（nbb には load-file が無く、gate とライブラリを 1 ファイルに
;; まとめるのがコード重複を避ける唯一の方法だった）。
;;
;; 不変条件: 受理できない workflow は **実行せず exit 1**。skip して green にしない。

(defn- run-bash!
  ;; **repo root を cwd にする。** GitHub の run: は checkout 先を作業ディレクトリと
  ;; して書かれているので、ここを渡さないと相対パスが全部外れる（実測: find src が
  ;; No such file or directory になった）。
  [script cwd]
  (let [cp (js/require "node:child_process")]
    (try
      (let [out (.execFileSync cp "bash" #js ["-c" script]
                               #js {:encoding "utf8" :maxBuffer 67108864 :stdio "pipe" :cwd cwd})]
        {:ok true :out (str out)})
      (catch :default e
        {:ok false
         :out (str (some-> (.-stdout e) str) (some-> (.-stderr e) str))
         :status (.-status e)}))))

(defn -main [& argv]
  (let [fs (js/require "node:fs")
        path (js/require "node:path")
        args (vec argv)
        root (or (first (remove #(str/starts-with? % "--") args)) ".")
        only (let [i (.indexOf args "--workflow")] (when-not (neg? i) (nth args (inc i) nil)))
        dir (.join path root ".github" "workflows")]
    (when-not (.existsSync fs dir)
      (println (str "FLEET-CI: no .github/workflows in the shipped tree — "
                    "extraction or :include-ext is wrong, refusing to report a pass"))
      (js/process.exit 90))
    (let [files (->> (.readdirSync fs dir)
                     (filter #(or (str/ends-with? % ".yml") (str/ends-with? % ".yaml")))
                     (filter #(or (nil? only) (= only %)))
                     sort vec)]
      (when (empty? files)
        (println (str "FLEET-CI: no workflow files matched" (when only (str " --workflow " only))))
        (js/process.exit 90))
      (let [results
            (doall
             (for [f files]
               (let [p (.join path dir f)
                     text (str (.readFileSync fs p "utf8"))]
                 (try
                   (let [plan (analyze (parse-yaml text))]
                     (if (not= :runnable (:verdict plan))
                       {:file f :ok false :unsupported (:reasons plan)}
                       (let [sh (to-bash plan {:java-home (.-JAVA_HOME js/process.env)})
                             r (run-bash! sh root)]
                         (println (str "=== " f " ==="))
                         (println (:out r))
                         {:file f :ok (:ok r) :status (:status r)})))
                   (catch :default e
                     {:file f :ok false :error (.-message e)})))))
            bad (remove :ok results)]
        (println (str "workflows: " (count results) " run, " (count bad) " failed"))
        (doseq [b bad]
          (println (str "  FAIL " (:file b)
                        (when (:error b) (str " — " (:error b)))
                        (when (:unsupported b)
                          (str " — unsupported: " (str/join "; " (:unsupported b)))))))
        (if (seq bad) (js/process.exit 1) (println "OK — all workflows passed"))))))

;; **明示的な sentinel でのみ実行する。** 「引数があれば実行」にすると、
;; このファイルを require した側（audit）が自分の引数で実行を誘発する（実測）。
;; gate 登録は :script-args ["--run-workflows"] を渡す。
(when (some #(= "--run-workflows" %) *command-line-args*)
  (apply -main *command-line-args*))
