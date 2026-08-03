;; scripts/organism-readjudicate.cljs — ADR-2607289700 D7/D9 の organism 再判定。
;;
;; なぜ別スクリプトか: repo-taxonomy.cljs の :repo/kind "organism" は
;; **README.md の本文 4096 byte に "artificial organism" 等の語があるか**だけで
;; 決まる (ADR-2607289600 既知の限界 5)。D9 は「heartbeat 実装を持ちながら宣言して
;; いない repo を取りこぼしている」ことを理由に、大量移送の前に再判定を要求している。
;;
;; ここでは D1/D7 が定義する organism の 2 条件を、宣言ではなく **repo 内の証拠**から
;; 別々に測る:
;;
;;   (a) 自分で起きる   — heartbeat / resident loop / schedule
;;   (b) 自分の did で対外発話する — did 宣言 + publisher/CACAO 実装
;;
;; 判定を自動確定しない。**信号行列と候補を出すだけ**で、kind の確定は
;; repo-taxonomy.cljs 側と owner 裁定に委ねる。閾値を先に決めて数字を後から
;; 合わせるのを避けるため。
;;
;; Usage:
;;   nbb scripts/organism-readjudicate.cljs --workspace <superproject-root> \
;;       [--out 90-docs/audits/organism-readjudication.datoms.edn]

(require '[scripts.nbb-compat :as io :refer [slurp spit]]
         '[clojure.string :as str])

(def fs (js/require "node:fs"))
(def npath (js/require "node:path"))

(defn- parse-args [args]
  (loop [[k & more :as all] (vec args)
         opts {:workspace "." :out nil}]
    (if k
      (case k
        "--workspace" (recur (rest more) (assoc opts :workspace (first more)))
        "--out"       (recur (rest more) (assoc opts :out (first more)))
        (recur more opts))
      opts)))

(def opts (parse-args *command-line-args*))
(def ws (:workspace opts))
(defn- ws-path [& parts] (apply (.-join npath) ws parts))
(defn- exists? [p] (.existsSync fs p))

(def warnings (atom {}))
(defn- warn! [k] (swap! warnings update k (fnil inc 0)))

;; ---- west.yml（生成物なので行指向で十分。repo-taxonomy.cljs と同型）----------

(defn- west-projects []
  (let [text (slurp (ws-path "manifest/west.yml"))]
    (loop [lines (str/split-lines text) in? false cur nil acc []]
      (if-let [[line & more] (seq lines)]
        (let [t (str/trim line)]
          (cond
            (str/starts-with? line "  projects:") (recur more true nil acc)
            (and in? (str/starts-with? line "  ") (not (str/starts-with? line "    ")))
            (recur more false nil (cond-> acc cur (conj cur)))
            (and in? (str/starts-with? t "- name:"))
            (recur more true {:name (str/trim (subs t 7))} (cond-> acc cur (conj cur)))
            (and in? cur (str/starts-with? t "path:"))
            (recur more true (assoc cur :path (str/trim (subs t 5))) acc)
            :else (recur more in? cur acc)))
        (cond-> acc cur (conj cur))))))

;; ---- 走査 -------------------------------------------------------------------

(defn- walk-files
  "root 配下の相対パスを最大 max-depth / max-files まで。.git と node_modules は除外。"
  [root max-depth max-files]
  (let [out (atom []) truncated? (atom false)]
    (letfn [(go [dir depth prefix]
              (when (and (< depth max-depth) (not @truncated?))
                (doseq [e (try (.readdirSync fs dir #js {:withFileTypes true})
                               (catch :default _ (do (warn! :readdir-failed) [])))]
                  (when-not @truncated?
                    (let [nm (.-name e) rel (if (= prefix "") nm (str prefix "/" nm))]
                      (when-not (or (= nm ".git") (= nm "node_modules") (= nm ".shadow-cljs"))
                        (if (.isDirectory e)
                          (go (.join npath dir nm) (inc depth) rel)
                          (do (swap! out conj rel)
                              (when (> (count @out) max-files) (reset! truncated? true))))))))))]
      (go root 0 ""))
    {:files @out :truncated? @truncated?}))

(defn- read-head [p n]
  (try (let [buf (.readFileSync fs p)] (subs (.toString buf "utf8") 0 (min n (.-length buf))))
       (catch :default _ nil)))

;; 盲点 1: repo-taxonomy.cljs は README.md / readme.md しか読まない。
;; 実測 2026-08-03: README.md を持たず README.edn / README.md.edn だけを持つ repo が
;; 150 件ある（本 workspace の EDN-only 規約の帰結）。それらの organism 宣言は
;; 構造的に見えていなかった。
;; 盲点 2: 4096 byte 打ち切り。宣言が後方にある README を取りこぼす。
(def ^:private readme-names ["README.md" "readme.md" "README.edn" "README.md.edn"])
(def ^:private manifest-names ["manifest.edn" "manifest.jsonld" "actor-manifest.jsonld"
                               "MATURITY.md" "CLAUDE.md"])

(defn- concat-docs [root names limit]
  (->> names
       (keep (fn [nm] (read-head (str root "/" nm) limit)))
       (str/join "\n")))

(def organism-re
  #"(?i)artificial[-\s]organism|organism autonomy|organism loop|organism 循環|人工生命")

;; D1 の「自分で起きる」— heartbeat 語だけでなく、resident loop の実体も見る。
(def heartbeat-file-re #"(?i)heartbeat")
;; ディレクトリを src/ に限定しない。実測: network-isekai は backend/、
;; club-shinshi は 20-actors/ 配下に実装を置く monorepo 形で、src/ 限定だと
;; 構造的に false negative になる。
(def loop-file-re #"(?i)(^|/)(loop|tick|scheduler|cron|daemon)\.(clj[cs]?|kotoba)$")

;; D1 の「自分の did で対外発話する」— did 宣言と、実際に外へ出す実装の両方。
(def did-re #"did:(web|key):")
(def publish-file-re #"(?i)(^|/)(publisher|aozora|cacao|pds)\.(clj[cs]?|kotoba)$")

;; ADR-2606281500 = 種をまく doctrine（publication is AUTONOMOUS BY DEFAULT）。
;; 「organism」という語を使わずに自律性を宣言している repo を拾う。
(def autonomy-doctrine-re
  #"(?i)2606281500|autonomous by default|autonomously by default|種をまく|自律的に(公開|発話|投稿)")

(defn- probe [rel-path]
  (let [root (ws-path rel-path)]
    (when (exists? root)
      (let [{:keys [files truncated?]} (walk-files root 4 3000)
            base (fn [f] (last (str/split f #"/")))
            docs (str (concat-docs root readme-names 16384) "\n"
                      (concat-docs root manifest-names 16384))
            has? (fn [pred] (boolean (some pred files)))]
        {:path rel-path
         :truncated? truncated?
         :file-count (count files)
         ;; (a) 自分で起きる
         :heartbeat-file? (has? #(re-find heartbeat-file-re (base %)))
         :resident-loop?  (has? #(re-find loop-file-re %))
         ;; (b) 自分の did で対外発話する
         :did-declared?   (boolean (re-find did-re docs))
         :publish-impl?   (has? #(re-find publish-file-re %))
         ;; 宣言面
         :organism-phrase? (boolean (re-find organism-re docs))
         :autonomy-doctrine? (boolean (re-find autonomy-doctrine-re docs))
         ;; 参考: 既存分類器が使う証拠
         :governor? (has? #(re-matches #"governor\.clj[cs]?" (base %)))
         :readme-md? (exists? (str root "/README.md"))}))))

;; ---- 判定（自動確定しない。3 段階のラベルだけ付ける）------------------------

(defn- self-starting? [p] (or (:heartbeat-file? p) (:resident-loop? p)))
(defn- own-voice? [p] (and (:did-declared? p) (:publish-impl? p)))
(defn- declared? [p] (or (:organism-phrase? p) (:autonomy-doctrine? p)))

(defn- verdict [p]
  (cond
    (and (self-starting? p) (own-voice? p) (declared? p)) "organism"
    (and (self-starting? p) (own-voice? p))               "organism-undeclared"
    (and (declared? p) (or (self-starting? p) (own-voice? p))) "organism-partial"
    :else                                                  "not-organism"))

;; ---- 9 identity との突き合わせ（D7 が要求）---------------------------------

(defn- registered-identities []
  (let [p (ws-path "80-data/system/artificial-organism-actors.json")]
    (if-not (exists? p)
      (do (warn! :organism-registry-missing) [])
      (try (->> (.-results (js/JSON.parse (slurp p)))
                (map (fn [r] {:product (.-product r) :handle (.-handle r)
                              :did (.-did r) :state (.-state r)}))
                vec)
           (catch :default _ (do (warn! :organism-registry-parse) []))))))

;; ---- 実行 -------------------------------------------------------------------

(def projects (->> (west-projects) (filter :path) (remove #(str/includes? (:path %) "m365-archive"))))
(println (str "organism-readjudicate: " (count projects) " west projects, workspace=" ws))

(def probes (->> projects (keep #(probe (:path %))) vec))
(println (str "organism-readjudicate: " (count probes) " present checkouts scanned"))

(def judged (mapv #(assoc % :verdict (verdict %)) probes))
(def by-verdict (group-by :verdict judged))

(doseq [v ["organism" "organism-undeclared" "organism-partial"]]
  (println (str "\n=== " v " (" (count (get by-verdict v)) ") ==="))
  (doseq [p (sort-by :path (get by-verdict v))]
    (println (str "  " (:path p)
                  "  self-start=" (cond (:heartbeat-file? p) "heartbeat"
                                        (:resident-loop? p) "loop" :else "-")
                  " did=" (if (:did-declared? p) "y" "-")
                  " publish=" (if (:publish-impl? p) "y" "-")
                  " declared=" (cond (:organism-phrase? p) "phrase"
                                     (:autonomy-doctrine? p) "doctrine" :else "-")
                  (when-not (:readme-md? p) "  [README.md 無し — 旧検出器の盲点]")))))

(println (str "\nnot-organism: " (count (get by-verdict "not-organism"))))

(println "\n=== 9 registered identities との突き合わせ ===")
(let [ids (registered-identities)
      paths (set (map :path judged))
      ;; 完全一致だけだと isekai→network-isekai / shinshi→club-shinshi /
      ;; babiniku→net-babiniku を「repo 無し」と誤報告する。部分一致も拾い、
      ;; どちらで当たったかを残す。
      find-repo (fn [product]
                  (let [exact (->> paths (filter #(= product (last (str/split % #"/")))) sort vec)]
                    (if (seq exact)
                      exact
                      (->> paths
                           (filter #(str/includes? (last (str/split % #"/")) product))
                           sort vec))))]
  (doseq [{:keys [product handle state]} ids]
    (let [repos (find-repo product)
          vs (->> judged (filter #(some #{(:path %)} repos)) (map :verdict) distinct vec)]
      (println (str "  " product " (" state ", " handle ")  repo=" (if (seq repos) (str/join "," repos) "— 見つからない")
                    "  verdict=" (if (seq vs) (str/join "," vs) "-"))))))

(when-let [out (:out opts)]
  (let [rows (map-indexed
              (fn [i p]
                {:db/id (- (inc i))
                 :organism/repo (:path p)
                 :organism/verdict (:verdict p)
                 :organism/self-starting (boolean (self-starting? p))
                 :organism/own-voice (boolean (own-voice? p))
                 :organism/declared (boolean (declared? p))
                 :organism/evidence (pr-str (select-keys p [:heartbeat-file? :resident-loop?
                                                            :did-declared? :publish-impl?
                                                            :organism-phrase? :autonomy-doctrine?
                                                            :governor? :readme-md?]))
                 :source/dataset "organism-readjudication"})
              (remove #(= "not-organism" (:verdict %)) judged))]
    (.mkdirSync fs (.dirname npath out) #js {:recursive true})
    (spit out (str ";; 90-docs/audits/organism-readjudication.datoms.edn\n"
                   ";; generated by scripts/organism-readjudicate.cljs — DO NOT EDIT BY HAND.\n"
                   ";; ADR-2607289700 D7/D9 の organism 再判定。not-organism は出力しない。\n"
                   "[" (str/join "\n " (map pr-str rows)) "]\n"))
    (println (str "\norganism-readjudicate: wrote " out " (" (count rows) " entities)"))))

(when (seq @warnings)
  (binding [*print-fn* *print-err-fn*]
    (println "WARNING organism-readjudicate: 握り潰さず報告する:")
    (doseq [[k n] @warnings] (println (str "  " (name k) ": " n)))))
