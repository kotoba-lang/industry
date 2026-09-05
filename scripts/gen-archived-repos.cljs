#!/usr/bin/env nbb
;; scripts/gen-archived-repos.cljs — GitHub で archived（read-only）な repo を
;; 掃き出して `manifest/archived-repos.edn` に書く。生成物（手編集禁止）。
;;
;; ## なぜ要るか
;;
;; `itonami-maturity-improve` loop は「own が低い = fleet-gain が高い」順に
;; 対象を選ぶ。**archived な repo は定義上そこの常連になる** —— archived =
;; 開発が止まっている = 全軸が低い = 「伸びしろが最大」と読まれる。しかし
;; archived な repo には push できないので、その周は必ず空振りする。
;;
;; 実測 2026-08-11: fleet 最下位 3 本（com-etzhayyim-gov_municipality /
;; com-etzhayyim-infra_utility_connect / ai-gftd-kaisya）が**全部 archived**で、
;; loop は 3 周連続で先頭 3 手を捨てていた（ledger に 2 回報告されている）。
;; さらに 445bp の帯に archived が 6 本控えており、writable な候補を上げるほど
;; **archived が上へ繰り上がってくる**。放置すると悪化する。
;;
;; ## 何を変えないか
;;
;; **スコアは 1bp も動かさない。** archived な repo は従来どおり測られ、
;; datoms にも fleet 平均にも入り続ける。この掃き出しが変えるのは
;; 「次の 1 手として誰を指名するか」だけ —— 測り方ではなく行き先。
;; （スコア意味論を変える案は 2 周続けて見送られている。ここはその論点に
;;   触れない形で routing だけを直す。）
;;
;; ## 不変条件
;;
;;   - **掃き出せた org と件数を必ず記録する。** 「archived 0 件」と
;;     「そもそも掃き出せていない」は別物で、記録が無ければ区別できない
;;     （記録が無い = UNVERIFIED であって CONFORMANT ではない）。
;;   - org が 1 つでも失敗したら**書かずに exit 1**。部分的な結果を
;;     完全な結果の顔で置くと、消えた org の archived が黙って候補に戻る。
;;
;; 使い方:
;;   nbb --classpath ".:scripts/nbb_compat" scripts/gen-archived-repos.cljs
;;   nbb ... scripts/gen-archived-repos.cljs --orgs cloud-itonami,kotoba-lang
;;   nbb ... scripts/gen-archived-repos.cljs --check     # 差分があれば exit 1
;;
;; exit 0 = 書けた（--check なら最新）/ exit 1 = 掃き出し失敗 or STALE。

(ns gen-archived-repos
  (:require ["node:child_process" :as cp]
            ["node:fs" :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(def argv (vec (drop 2 js/process.argv)))

(defn- flag-val [f]
  (let [i (.indexOf argv f)]
    (when (and (>= i 0) (< (inc i) (count argv))) (nth argv (inc i)))))

(def check? (some #{"--check"} argv))
(def out-path (or (flag-val "--out") "manifest/archived-repos.edn"))

;; 既定の org は datoms に実在する 4 つ（実測 2026-08-11: cloud-itonami 1,725 /
;; kotoba-lang 96 / etzhayyim 1 / network-awai 1）。scan の対象が増えたらここも足す。
(def default-orgs ["cloud-itonami" "etzhayyim" "kotoba-lang" "network-awai"])

(def orgs (if-let [s (flag-val "--orgs")]
            (vec (remove str/blank? (str/split s #",")))
            default-orgs))

(defn- sh [cmd args]
  (try {:exit 0 :out (str (cp/execFileSync cmd (clj->js (vec args))
                                           #js {:encoding "utf8"
                                                :maxBuffer 268435456
                                                :timeout 600000}))}
       (catch :default e
         {:exit (or (.-status e) 1)
          :out (str (some-> (.-stdout e) str))
          :err (str (some-> (.-stderr e) str) e)})))

(defn- lines [s]
  (vec (remove str/blank? (map str/trim (str/split-lines (or s ""))))))

(defn- sweep-org
  "org の repo を 1 回だけ舐めて {:total n :archived [names]} を返す。
   失敗は nil ではなく :error を持つ map で返す —— 空と失敗を混ぜない。"
  [org]
  (let [{:keys [exit out err]}
        (sh "gh" ["api" (str "orgs/" org "/repos?per_page=100&type=all")
                  "--paginate" "--jq" ".[] | [.name, (.archived|tostring)] | @tsv"])]
    (if-not (zero? exit)
      {:org org :error (str/trim (or err out))}
      (let [rows (keep (fn [l] (let [[n a] (str/split l #"\t")]
                                 (when (seq n) [n (= a "true")])))
                       (lines out))]
        {:org org
         :total (count rows)
         :names (set (map first rows))
         :archived (vec (sort (map first (filter second rows))))}))))

(defn- west-registered
  "west.yml の project を org ごとに {registered-github-name -> west-path} で返す。

  **この掃き出しの消費者は west のパスで候補を照合する。** GitHub が返すのは
  *現在の* repo 名で、west が持つのは *登録時の* 名前なので、repo が改名された
  瞬間に両者は食い違う。構成した `orgs/<org>/<github-name>` を書くと、その 1 本は
  除外表に載っていながらどの候補とも一致しない —— 実測 2026-09-05、掃き出しを
  最新化した直後に `ai-gftd-kaisya`(GitHub 上は `ai-kaisya`、archived)が除外
  17 本から 16 本に落ちて loop の 1 位に浮上した。`--check` は緑になり、それが
  守っているものは悪くなった。"
  []
  (let [text (try (fs/readFileSync "manifest/west.yml" "utf8") (catch :default _ nil))]
    (when-not text
      (println "manifest/west.yml が読めないので**書かない**。")
      (println "west のパスに写せないまま書くと、改名された repo が候補に戻る。")
      (js/process.exit 1))
    (->> (str/split text #"(?m)^    - name: ")
         rest
         (keep (fn [b]
                 (let [nm (first (str/split b #"\n"))
                       rp (second (re-find #"(?m)^      repo-path: (\S+)" b))
                       pa (second (re-find #"(?m)^      path: (orgs/\S+)" b))]
                   (when pa
                     (let [org (second (str/split pa #"/"))]
                       [[org (or rp nm)] pa])))))
         (into {}))))

(def west-paths (west-registered))

(defn- resolve-renamed
  "登録名が org の一覧に無い west entry を 1 件ずつ GitHub に訊く（リダイレクトが
   現在の repo を返す）。archived なら west のパスを返す。

   **応答が読めなかったものは nil ではなく :error にする** —— 読めなかった repo を
   『archived ではない』と数えると、候補に戻ったことに誰も気づかない。"
  [org registered]
  (let [{:keys [exit out err]}
        (sh "gh" ["api" (str "repos/" org "/" registered)
                  "--jq" "[.full_name, (.archived|tostring)] | @tsv"])]
    (if-not (zero? exit)
      {:error (str registered ": " (str/trim (or err out)))}
      (let [[full a] (str/split (str/trim out) #"\t")]
        {:registered registered :full full :archived? (= a "true")}))))

(println "archived sweep:" (count orgs) "orgs —" (str/join ", " orgs))

(def results (mapv (fn [o]
                     (let [r (sweep-org o)]
                       (if (:error r)
                         (println "  " o "— FAILED:" (subs (:error r) 0 (min 200 (count (:error r)))))
                         (println "  " o "— total" (:total r) "/ archived" (count (:archived r))))
                       r))
                   orgs))

(def failed (filterv :error results))

(when (seq failed)
  (println)
  (println "掃き出せなかった org があるので**書かない**:"
           (str/join ", " (map :org failed)))
  (println "部分的な結果を置くと、消えた org の archived が黙って候補に戻る。")
  (js/process.exit 1))

;; total 0 は「org が空」か「jq が黙って何も出さなかった」かの区別が付かない。
;; west が 4,000 project を持つこの workspace で total 0 の org は異常なので止める。
(when-let [empties (seq (filter #(zero? (:total %)) results))]
  (println)
  (println "total=0 の org があるので**書かない**:" (str/join ", " (map :org empties)))
  (println "org が空なのか掃き出しが黙って失敗したのか、この結果からは区別できない。")
  (js/process.exit 1))

(def archived-paths
  "GitHub の archived 名を west のパスへ写す。west に無い名前は従来どおり構成した
   パスで出す —— west に登録されていない repo はそもそも候補にならないので、
   ここで落としても routing は変わらない。"
  (mapcat (fn [{:keys [org archived]}]
            (map #(or (get west-paths [org %]) (str "orgs/" org "/" %)) archived))
          results))

(def orphans
  "登録名が org の一覧に無い west entry。改名か削除のどちらかで、前者は archived を
   隠している可能性がある。"
  (for [{:keys [org names]} results
        [[o registered] path] west-paths
        :when (and (= o org) (not (contains? names registered)))]
    {:org org :registered registered :path path}))

(println)
(println "west に登録されているが org の一覧に無い entry:" (count orphans)
         "— 改名かどうかを 1 件ずつ GitHub に訊く")

(def resolved (mapv (fn [{:keys [org registered path]}]
                      (assoc (resolve-renamed org registered) :path path :org org))
                    orphans))

(when-let [bad (seq (filter :error resolved))]
  (println)
  (println "解決できなかった west entry があるので**書かない**:" (count bad) "件")
  (doseq [b (take 5 bad)] (println "  " (:error b)))
  (println "読めなかった repo を『archived ではない』と数えると、候補に戻ったことに")
  (println "誰も気づかない。GitHub 側が答えられる状態で回し直すこと。")
  (js/process.exit 1))

(def renamed-paths
  (->> resolved (filter :archived?) (map :path) vec))

(doseq [r (filter :archived? resolved)]
  (println "   改名された archived:" (:registered r) "→" (:full r)
           "— west のパス" (:path r) "で除外する"))

(def payload
  {:generated-at (.toISOString (js/Date.))
   :source "gh api orgs/<org>/repos?type=all --paginate"
   :note (str "生成物（手編集禁止）。archived な repo は測定からは外さない —— "
              "スコアと fleet 平均には従来どおり入る。これが縛るのは "
              "itonami-maturity-improve loop の候補選択だけ。")
   :orgs (vec orgs)
   :swept (into (sorted-map) (map (juxt :org #(select-keys % [:total])) results))
   :archived-count (reduce + (map #(count (:archived %)) results))
   :archived (vec (sort (distinct (concat archived-paths renamed-paths))))})

(defn- render
  "1 path 1 行で書く。集合が増減したときの diff が読めることを優先する
   （pprint に任せると 1 行が折り返して、1 本増えただけで全体が動く）。"
  [m]
  (str ";; manifest/archived-repos.edn — 生成物。手で編集しない。\n"
       ";; 再生成: nbb --classpath \".:scripts/nbb_compat\" scripts/gen-archived-repos.cljs\n"
       "{:generated-at " (pr-str (:generated-at m)) "\n"
       " :source " (pr-str (:source m)) "\n"
       " :note " (pr-str (:note m)) "\n"
       " :orgs " (pr-str (:orgs m)) "\n"
       " :swept {" (str/join "\n         "
                             (map (fn [[o v]] (str (pr-str o) " " (pr-str v)))
                                  (:swept m))) "}\n"
       " :archived-count " (:archived-count m) "\n"
       " :archived [" (str/join "\n            " (map pr-str (:archived m))) "]}\n"))

(def text (render payload))

(if check?
  (let [prev (when (.existsSync fs out-path)
               (edn/read-string (str (.readFileSync fs out-path "utf8"))))
        same? (and prev (= (set (:archived prev)) (set (:archived payload))))]
    (if same?
      (do (println)
          (println "FRESH —" (:archived-count payload) "archived / "
                   (reduce + (map :total results)) "repos")
          (js/process.exit 0))
      (do (println)
          (println "STALE — archived の集合が" out-path "と違う")
          (when prev
            (let [was (set (:archived prev)) now (set (:archived payload))]
              (doseq [p (sort (remove was now))] (println "  + " p))
              (doseq [p (sort (remove now was))] (println "  - " p))))
          (js/process.exit 1))))
  (do (.writeFileSync fs out-path text)
      (println)
      (println "wrote" out-path "—" (:archived-count payload) "archived /"
               (reduce + (map :total results)) "repos swept")))
