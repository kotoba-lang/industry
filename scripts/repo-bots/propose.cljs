#!/usr/bin/env nbb
;; scripts/repo-bots/propose.cljs — 床を割っている bot 1 体について、murakumo に
;; 提案文を書かせ、**決定論的な gate が受理/却下を決める**。
;;
;;   nbb scripts/repo-bots/propose.cljs              ; --next の 1 件
;;   nbb scripts/repo-bots/propose.cljs --bot kotoba-lang/amu
;;   nbb scripts/repo-bots/propose.cljs --batch 20   ; 波で回す（常駐用）
;;   nbb scripts/repo-bots/propose.cljs --dry-run    ; 模型を呼ばず証拠だけ出す
;;
;; ## 模型が触れるのは提案文だけ
;;
;; 測定に模型を入れない（ADR-2608271500 決定 3）。ここに来る時点で「どの repo の
;; どの床が割れているか」は tick が決めてあり、模型がするのは**その 1 件に対する
;; 文章の起草**だけ。受理するかは下の gate が決める。ADR-2608271450 が
;; wiki.kotobase.net で採ったのと同じ切り方:
;;
;;     tick（決定論） → 証拠（決定論） → 模型が起草 → gate（決定論） → 提案
;;
;; ## なぜ gate が要るか
;;
;; 「この repo に何が足りないか」と訊かれた模型は、**何も無くても流暢に答える**。
;; 実在しそうなファイル名と実在しそうな URL を書き、どちらも diff では見分けが
;; つかない。だから模型には証拠しか渡さず、出てきた文は次で機械的に検査する:
;;
;;   1. 床を実際に越えるか（README なら 200 byte 以上）
;;   2. 挙げたパスが**実在するか**（実在しないものを 1 つでも挙げたら却下）
;;   3. 挙げた URL のホストが証拠に在るか（github.com/<org>/<name> は許す）
;;   4. 雛形の痕跡（TODO / FIXME / <placeholder> / lorem）が無いか
;;   5. repo 名を名乗っているか
;;
;; ## モデル名を焼かない
;;
;; alias `murakumo-main` だけを送る（ADR-2607173100）。具体の model id を焼くと
;; fleet が動いた日に 401/502 になる。receipt には**呼んだ時点の alias-for** を
;; 記録する —— 後から「どの実体が書いたか」を辿れるようにするため。ただし次回も
;; 同じ実体だとは仮定しない。
;;
;; exit: 0 受理（提案を書いた） / 1 gate が却下 / 2 走れなかった

(ns repo-bots-propose
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [cljs.pprint :as pp]))

(def fs (js/require "node:fs"))
(def os (js/require "node:os"))
(def path (js/require "node:path"))

(def argv (vec (drop 2 js/process.argv)))
(defn- flag [n] (some #{n} argv))
(defn- opt [n] (let [i (.indexOf argv n)] (when (and (>= i 0) (< (inc i) (count argv)))
                                            (nth argv (inc i)))))

(def top (or (.-CLAUDE_PROJECT_DIR (.-env js/process)) (.cwd js/process)))
(def home (.homedir os))
(def state-dir (str home "/.itonami/repo-bots"))
(def state-file (or (opt "--state") (str state-dir "/state.edn")))
(def proposal-dir (or (opt "--out") (str state-dir "/proposals")))
(def receipt-ledger (str state-dir "/proposals.ledger.edn"))
(def registry-file (.join path top "manifest" "repo-bots.edn"))

;; endpoint だけを焼き、model は alias。fallback も endpoint のみ（ADR-2607173100）。
(def api-base (or (.-MURAKUMO_API_BASE (.-env js/process)) "https://api.murakumo.cloud"))
(def model-alias "murakumo-main")

(defn now-iso [] (.toISOString (js/Date.)))
(defn- read-edn [f] (try (edn/read-string (.readFileSync fs f "utf8")) (catch :default _ nil)))
(defn- exists? [p] (try (.existsSync fs p) (catch :default _ false)))
(defn- slurp* [p n]
  (try (let [s (str (.readFileSync fs p "utf8"))] (subs s 0 (min n (count s)))) (catch :default _ nil)))

;; ---------------------------------------------------------------- 証拠（決定論）

(def ^:private evidence-file-budget 6000)

(defn- ls* [dir]
  (try (vec (sort (js->clj (.readdirSync fs dir)))) (catch :default _ [])))

(defn- walk-src
  "src 以下を有界に歩いてパスを集める。深さ 3・件数 120 で切る。**切ったことを
  呼び手に返す** —— 切った一覧を『全部』として模型に渡すと、無いものを無いと
  言わせることになる。"
  [root]
  (loop [q [[root 0]] out [] truncated? false]
    (cond
      (>= (count out) 120) [out true]
      (empty? q) [out truncated?]
      :else
      (let [[[d depth] & rest] q]
        (if (> depth 3)
          (recur (vec rest) out true)
          (let [entries (ls* d)
                dirs (filter #(try (.isDirectory (.statSync fs (str d "/" %))) (catch :default _ false)) entries)
                files (remove (set dirs) entries)]
            (recur (into (vec rest) (map #(vector (str d "/" %) (inc depth)) dirs))
                   (into out (map #(str/replace (str d "/" %) (str root "/") "") files))
                   truncated?)))))))

(defn- gather [bot]
  (let [abs (.join path top (:bot/repo bot))
        [src-files src-truncated?] (if (exists? (str abs "/src")) (walk-src (str abs "/src")) [[] false])]
    {:repo (:bot/repo bot)
     :name (:bot/name bot)
     :org (:bot/org bot)
     :class (:bot/class bot)
     :top (ls* abs)
     :src (mapv #(str "src/" %) src-files)
     :src-truncated? src-truncated?
     :readme (slurp* (str abs "/README.md") 1200)
     :deps (slurp* (str abs "/deps.edn") 1500)
     :package (slurp* (str abs "/package.json") 1200)
     :docs (when (exists? (str abs "/docs")) (take 20 (ls* (str abs "/docs"))))
     :first-src (let [f (first src-files)]
                  (when f (slurp* (str abs "/src/" f) 2500)))}))

(defn- evidence-text [e]
  (str/join
   "\n"
   (remove nil?
           [(str "repository: " (:org e) "/" (:name e))
            (str "path: " (:repo e))
            (str "top-level entries: " (str/join ", " (:top e)))
            (when (seq (:src e))
              (str "source files" (when (:src-truncated? e) " (TRUNCATED — this is not the full list)")
                   ": " (str/join ", " (:src e))))
            (when (:docs e) (str "docs/: " (str/join ", " (:docs e))))
            (when (:deps e) (str "deps.edn:\n" (:deps e)))
            (when (:package e) (str "package.json:\n" (:package e)))
            (when (:readme e) (str "existing README.md (may be a stub):\n" (:readme e)))
            (when (:first-src e) (str "first source file body:\n" (:first-src e)))])))

;; ---------------------------------------------------------------- 模型（提案文だけ）

(defn- prompt-for [floor e]
  (case floor
    :readme
    (str "You are writing the README.md for one repository in a large multi-repo workspace.\n\n"
         "RULES — these are checked mechanically after you answer, and your answer is\n"
         "rejected outright if you break them:\n"
         "1. Use ONLY the evidence below. If the evidence does not say what something\n"
         "   does, do not guess — say less instead.\n"
         "2. Never name a file or path that is not listed in the evidence.\n"
         "3. Never invent a URL. The only link you may use is\n"
         "   https://github.com/" (:org e) "/" (:name e) "\n"
         "4. No TODO, FIXME, placeholder brackets, or lorem text.\n"
         "5. Open by naming the repository, because its name may not say what it does.\n"
         "6. 400-1400 bytes. Markdown. Match the language of the existing evidence\n"
         "   (Japanese if the evidence is mostly Japanese, otherwise English).\n"
         "7. Output the README body only — no preamble, no code fence around the whole thing.\n\n"
         "EVIDENCE\n========\n" (evidence-text e))
    (str "Describe, in at most 6 lines, what is missing in this repository regarding "
         (name floor) ". Use only the evidence. Never name a path that is not listed.\n\n"
         "EVIDENCE\n========\n" (evidence-text e))))

(defn- call-murakumo [prompt]
  (-> (js/fetch (str api-base "/v1/chat/completions")
                #js {:method "POST"
                     :headers #js {"content-type" "application/json"}
                     :body (js/JSON.stringify
                            (clj->js {:model model-alias
                                      :max_tokens 900
                                      :temperature 0.2
                                      :messages [{:role "user" :content prompt}]}))})
      (.then (fn [r]
               (if-not (.-ok r)
                 (.then (.text r) (fn [t] {:ok? false :err (str "HTTP " (.-status r) " " (subs (str t) 0 200))}))
                 (.then (.json r)
                        (fn [j]
                          (let [j (js->clj j :keywordize-keys true)]
                            {:ok? true
                             :text (get-in j [:choices 0 :message :content])
                             :usage (:usage j)}))))))
      (.catch (fn [e] {:ok? false :err (str e)}))))

(defn- alias-for []
  (-> (js/fetch (str api-base "/infer/models/" model-alias))
      (.then #(.json %))
      (.then #(get (js->clj % :keywordize-keys true) :alias-for))
      (.catch (fn [_] nil))))

;; ---------------------------------------------------------------- gate（決定論）

(def ^:private placeholder-re #"(?i)\bTODO\b|\bFIXME\b|\blorem ipsum\b|<placeholder|＜.*?を書く＞|XXX")

(defn- claimed-paths
  "文中で挙げられた『ファイルらしきもの』。namespace（str/join）や見出しを拾わない
  ように、拡張子を持つものだけを見る。"
  [text]
  (->> (re-seq #"`([^`\n]{2,80})`" text)
       (map second)
       (filter #(re-find #"\.(cljc?|cljs|edn|json|md|yml|yaml|js|mjs|ts|tsx|toml|kotoba|wat|wasm|clj)$" %))
       ;; glob は「この 1 本が在る」という主張ではないので、実在検査の対象にしない。
       ;; 実測 2026-08-27: `*.edn` と書いた草稿を「実在しないパス」として却下していた。
       ;; **本物でない理由で落とす gate は、本物の理由で落としたときも信用されなくなる。**
       (remove #(re-find #"[*?\[\]{}]" %))
       (map #(str/replace % #"^\./" ""))
       distinct))

(defn- claimed-hosts [text]
  (->> (re-seq #"https?://([^/\s)\"]+)" text) (map second) distinct))

(defn- gate
  "受理/却下を決める。返り値 {:ok? bool :reasons [..]}。

  **却下の理由は literal で pin する** —— 別の理由で落ちたものを『捏造を捕まえた』と
  数えないため（ADR-2608136000 の 6 問目）。"
  [floor e text]
  (let [text (str/trim (str text))
        abs (.join path top (:repo e))
        ev (evidence-text e)
        allowed-host (str "github.com")
        bad-paths (remove #(or (exists? (str abs "/" %))
                               (str/includes? ev %))
                          (claimed-paths text))
        bad-hosts (remove #(or (= % allowed-host) (str/includes? ev %)) (claimed-hosts text))
        reasons
        (cond-> []
          (str/blank? text)                       (conj :empty-output)
          (and (= floor :readme) (< (count text) 200)) (conj :below-the-floor-it-should-clear)
          (> (count text) 4000)                   (conj :too-long)
          (seq bad-paths)                         (conj [:names-a-path-that-does-not-exist (vec (take 3 bad-paths))])
          (seq bad-hosts)                         (conj [:names-a-host-not-in-the-evidence (vec (take 3 bad-hosts))])
          (re-find placeholder-re text)           (conj :placeholder-text)
          (and (= floor :readme)
               (not (str/includes? (str/lower-case text) (str/lower-case (:name e)))))
          (conj :does-not-name-the-repository))]
    {:ok? (empty? reasons) :reasons reasons}))

;; ---------------------------------------------------------------- 選択

(def floor-priority {:readme 0 :test-signal 1})

(defn- candidates [registry state]
  (let [by-id (into {} (map (juxt :bot/id identity) registry))]
    (->> (for [[id row] (:bots state)
               [f {:keys [since detail]}] (:broken row)
               :when (contains? floor-priority f)
               :when (by-id id)]
           {:bot id :floor f :since since :detail detail :entry (by-id id)})
         (sort-by (fn [c] [(get floor-priority (:floor c) 99) (str (:bot c))])))))

;; ---------------------------------------------------------------- 実行

(defn- append-receipt! [m]
  (try (.mkdirSync fs state-dir #js {:recursive true})
       (.appendFileSync fs receipt-ledger (str (pr-str m) "\n"))
       (catch :default _ nil)))

;; 滞留の上限。**書く側より着地側が遅いので、放っておくと草稿の墓場ができる。**
;; 411 件の :readme に対して着地は 1 反復 1 件なので、上限が無ければ数百本の
;; 未読の草稿が積み上がり、「提案は出ている」という見た目だけが残る。
;; 上限に当たったら書く側を止める —— そのとき詰まっているのは書く側ではない。
(def ^:private pending-cap
  (js/parseInt (or (.-REPO_BOT_PENDING_CAP (.-env js/process)) "40") 10))

(defn- pending-count []
  (try (count (filter #(str/ends-with? % ".md")
                      (remove #(str/ends-with? % ".rejected.md")
                              (js->clj (.readdirSync fs proposal-dir)))))
       (catch :default _ 0)))

(defn- marker [id ext] (str proposal-dir "/" (str/replace id "/" "__") ext))

(defn- already-proposed? [id]
  ;; 「草稿が在る」だけでなく「証拠が薄いと分かっている」も skip する。印を残さないと
  ;; **毎時おなじ空 repo を測り直して 1 歩も進まない**（実測 2026-08-27: launchd の
  ;; 最初の 2 周が同じ 7 体を再走査していた）。
  (or (exists? (marker id ".md"))
      (exists? (marker id ".insufficient.edn"))))

;; 証拠がこれより薄い repo は、模型に訊いても**書けるのは捏造だけ**になる。
;; gate で落とせば済む話ではない —— 落とすと分かっている呼び出しに fleet の
;; 時間を使い、receipt に「却下」が積み上がって本物の却下が埋もれる。
;; 空の repo に README が無いのは README の欠落ではなく、その repo が空だという
;; 別の事実である（skill /repo-bot-drain も同じことを言っている）。
(def ^:private evidence-floor-bytes 400)

(defn- propose-one! [c alias-now]
  (let [e (gather (:entry c))
        ev (evidence-text e)
        prompt (prompt-for (:floor c) e)]
    (if (< (count ev) evidence-floor-bytes)
      (do (println (str "INSUFFICIENT-EVIDENCE\t" (:bot c) "\t" (count ev)
                        " byte —— 模型を呼ばない。これは README の欠落ではなく、repo が空であること"))
          (.mkdirSync fs proposal-dir #js {:recursive true})
          (.writeFileSync fs (marker (:bot c) ".insufficient.edn")
                          (with-out-str (pp/pprint {:bot (:bot c) :floor (:floor c) :at (now-iso)
                                                    :evidence-bytes (count ev)
                                                    :note "repo が空。README を書くのではなく、中身を作るか退役させる"})))
          (append-receipt! {:at (now-iso) :bot (:bot c) :floor (:floor c)
                            :outcome :insufficient-evidence :evidence-bytes (count ev)})
          (js/Promise.resolve :insufficient-evidence))
    (-> (call-murakumo prompt)
        (.then
         (fn [r]
           (if-not (:ok? r)
             (do (println (str "ERROR\t" (:bot c) "\t" (:err r)))
                 (append-receipt! {:at (now-iso) :bot (:bot c) :floor (:floor c)
                                   :outcome :call-failed :err (:err r)})
                 :error)
             (let [text (str/trim (str (:text r)))
                   g (gate (:floor c) e text)
                   base (str/replace (:bot c) "/" "__")]
               (.mkdirSync fs proposal-dir #js {:recursive true})
               (if (:ok? g)
                 (do (.writeFileSync fs (str proposal-dir "/" base ".md") (str text "\n"))
                     (.writeFileSync fs (str proposal-dir "/" base ".receipt.edn")
                                     (with-out-str (pp/pprint
                                                    {:bot (:bot c) :floor (:floor c) :at (now-iso)
                                                     :model model-alias :alias-for alias-now
                                                     :usage (:usage r) :accepted true
                                                     :evidence-bytes (count (evidence-text e))})))
                     (println (str "ACCEPTED\t" (:bot c) "\t" (name (:floor c)) "\t"
                                   (count text) " byte\ttokens "
                                   (get-in r [:usage :total_tokens])))
                     (append-receipt! {:at (now-iso) :bot (:bot c) :floor (:floor c)
                                       :outcome :accepted :bytes (count text)
                                       :usage (:usage r) :alias-for alias-now})
                     :accepted)
                 (do (println (str "REJECTED\t" (:bot c) "\t" (name (:floor c)) "\t" (pr-str (:reasons g))))
                     ;; 却下した草稿も残す。何を却下したか読めないと、gate が
                     ;; 効いているのか単に呼べていないのか区別できない。
                     (.writeFileSync fs (str proposal-dir "/" base ".rejected.md") (str text "\n"))
                     (append-receipt! {:at (now-iso) :bot (:bot c) :floor (:floor c)
                                       :outcome :rejected :reasons (:reasons g)
                                       :usage (:usage r) :alias-for alias-now})
                     :rejected))))))))))

(defn -main []
  (let [registry (read-edn registry-file)
        state (read-edn state-file)]
    (cond
      (not (seq registry))
      (do (println "REFUSED\t名簿が読めない:" registry-file) (js/process.exit 2))

      (nil? state)
      ;; 測っていないことを「候補 0 件」と読まない。
      (do (println "REFUSED\tまだ誰も測っていない。先に tick を回す") (js/process.exit 2))

      :else
      (let [only (opt "--bot")
            batch (js/parseInt (or (opt "--batch") "1") 10)
            cands (cond->> (candidates registry state)
                    only (filter #(= only (:bot %)))
                    ;; --bot で名指しされたときは「提案済み」で弾かない。名指しは
                    ;; 「もう一度やれ」であって、波の重複回避とは別の意図。
                    (nil? only) (remove #(already-proposed? (:bot %)))
                    ;; 証拠の多い順に回す。名簿は id 順なので、そのまま取ると
                    ;; cloud-itonami/app-* の空 scaffold が固まって先頭に並び、
                    ;; 波が丸ごと INSUFFICIENT-EVIDENCE で溶ける（実測: 8 件中 7 件）。
                    ;; 並べ替えの走査は先頭 60 件に限る（局所 FS だけとはいえ有界にする）。
                    (nil? only) (#(let [head (vec (take 60 %))]
                                    (sort-by (fn [c] (- (count (evidence-text (gather (:entry c)))))) head)))
                    true (take (if only 1 (max 1 batch))))]
        (cond
          (and (nil? only) (>= (pending-count) pending-cap))
          (do (println (str "SATURATED\t未着地の草稿が " (pending-count) " 本（上限 " pending-cap
                            "）。**詰まっているのは書く側ではない** —— 着地させてから増やす"))
              (js/process.exit 0))

          (empty? cands)
          (do (println "NO-CANDIDATES\t提案すべき床割れが無い（または全て提案済み）")
              (js/process.exit 0))

          ;; 草稿を模型抜きで gate にかける。gate が効いているかを確かめる口であり、
          ;; skill /repo-bot-drain が手直しした草稿を通す口でもある。
          (opt "--check-draft")
          (let [c (first cands)
                e (gather (:entry c))
                text (str (.readFileSync fs (opt "--check-draft") "utf8"))
                g (gate (:floor c) e text)]
            (println (if (:ok? g) "ACCEPTED" (str "REJECTED\t" (pr-str (:reasons g)))))
            (js/process.exit (if (:ok? g) 0 1)))

          (flag "--dry-run")
          (do (doseq [c cands]
                (let [e (gather (:entry c))]
                  (println (str "DRY\t" (:bot c) "\t" (name (:floor c))
                                "\tevidence " (count (evidence-text e)) " byte"))))
              (js/process.exit 0))

          :else
          (-> (alias-for)
              (.then (fn [a]
                       (println (str "MODEL\t" model-alias " -> " (or a "?") "\t" api-base))
                       (reduce (fn [p c] (.then p (fn [acc] (.then (propose-one! c a) #(conj acc %)))))
                               (js/Promise.resolve []) cands)))
              (.then (fn [outcomes]
                       (let [freq (frequencies outcomes)]
                         (println (str "SCANNED\t" (count outcomes) "\tproposals\t" (pr-str freq)))
                         (js/process.exit (if (pos? (get freq :accepted 0)) 0 1)))))))))))

(-main)
