#!/usr/bin/env nbb
;; lei-discover-candidates.cljs — 取得候補そのものを権威ソースから発見する。
;;
;; ## なぜ要るのか
;;
;; `scripts/d1/lei-candidates.edn` は **手書きの 88 件**で、そこがカタログの
;; 上限になっていた（実測 2026-07-27: 取得済 155 社 / 25 法域、target は 58 カ国）。
;; `lei-acquire.cljs` は既に GLEIF で検証しているが、**何を取るか**は人が
;; 書き足すまで増えない。ここはその発見側を埋める。
;;
;; ## 2 つの source を、それぞれ得意なことに使う
;;
;;   Wikidata  **どの企業か**を選ぶ。LEI(P1278) と売上(P2139) を持つので
;;             売上順に並べられる。
;;   GLEIF     **その LEI が実在するか**を裏取りする。LEI の発行元なので、
;;             ここに無い LEI は存在しない。
;;
;; ## なぜ GLEIF だけでは足りなかったか（実測 2026-07-27）
;;
;; GLEIF は 250 万件を**順不同**で返す。国で絞って先頭から取ると
;; 「Doctor Abenet 産婦人科クリニック」「MONARCH TRAVEL」のような小規模
;; 事業者が並ぶ —— 網羅ではなくノイズが増える。
;;
;; 重要度で絞れないか試したが、いずれも空振りだった:
;;
;;   filter[owns]=true      どの国でも 0 件（受理されるが機能しない）
;;   direct-children 数     ケニア 20 社すべて 0（シグナルが存在しない）
;;
;; GLEIF が持っているのは法人名と住所だけで、規模の情報が無い。だから
;; **選別は Wikidata に任せ、GLEIF は裏取りに使う**。
;;
;; ## 捏造しないための線引き
;;
;; - **`:site` は Wikidata の P856 をそのまま通すだけ**（推測しない）。
;;   `lei-acquire` が実際に GET して「本当にその会社のサイトか」を検証する。
;;   P856 が無ければ付けない —— そのときは site-issue として正直に止まる。
;; - **BRANCH を除外する。** GLEIF は支店レコードが親とほぼ同名で返るので、
;;   支店を会社として登録しない（`lei-acquire` と同じ判断をここでも行う）。
;; - 既に候補にある名前・既に取得済みの名前は出さない（重複を積まない）。
;;
;; ## 使い方（superproject root から）
;;
;;   nbb scripts/lei-discover-candidates.cljs [--per-country N] [--countries A,B]
;;                                            [--apply]
;;
;; 既定は dry-run。`--apply` で `scripts/d1/lei-candidates.edn` に追記する
;; （既存行は触らない — 手書きのコメントと並び順を壊さないため）。

(ns lei-discover-candidates
  (:require ["fs" :as fs]
            ["path" :as path]
            [clojure.string :as str]
            [cljs.reader :as edn]
            [promesa.core :as p]))

(def ^:private ua
  "cloud-itonami-lei-catalog/1.0 (+https://github.com/cloud-itonami; discovery; GET only)")

(def ^:private candidates-path "scripts/d1/lei-candidates.edn")
(def ^:private targets-path
  "orgs/kotoba-lang/loop-lei-catalog/resources/target-countries.edn")

;; ───────────────────────── 既存の状態 ─────────────────────────

(defn- read-edn [p]
  (edn/read-string (fs/readFileSync p "utf8")))

(defn- normalize-name
  "比較用。GLEIF と手書きで表記ゆれ（記号・大小・空白）があるので揃える。"
  [s]
  (-> (str s) str/upper-case
      (str/replace #"[.,()]" "")
      (str/replace #"\s+" " ")
      str/trim))

(defn- existing-names
  "候補リスト + 取得済み repo の両方から、既知の法人名を集める。

  **取得済みも見る。** 候補リストにしか無いと、既に repo になった会社を
  もう一度候補として積んでしまう。"
  []
  (let [cands (try (read-edn candidates-path) (catch :default _ []))
        from-cands (map #(normalize-name (:name %)) cands)
        repo-dir "orgs/cloud-itonami"
        from-repos
        (if-not (fs/existsSync repo-dir)
          []
          (->> (fs/readdirSync repo-dir)
               (filter #(str/starts-with? % "cloud-itonami-lei-"))
               (keep (fn [d]
                       (let [f (path/join repo-dir d "blueprint.edn")]
                         (when (fs/existsSync f)
                           (some-> (re-find #":company/legal-name\s+\"([^\"]+)\""
                                            (fs/readFileSync f "utf8"))
                                   second normalize-name)))))))]
    (set (concat from-cands from-repos))))

(defn- country-counts
  "取得済みの国別件数。**どこが薄いか**を決めるのに使う。"
  []
  (let [repo-dir "orgs/cloud-itonami"]
    (if-not (fs/existsSync repo-dir)
      {}
      (->> (fs/readdirSync repo-dir)
           (filter #(str/starts-with? % "cloud-itonami-lei-"))
           (keep (fn [d]
                   (let [f (path/join repo-dir d "blueprint.edn")]
                     (when (fs/existsSync f)
                       (some-> (re-find #":company/jurisdiction\s+\"([^\"]+)\""
                                        (fs/readFileSync f "utf8"))
                               second (str/split #"-") first)))))
           frequencies))))

;; ───────────────────────── Wikidata（選別）─────────────────────────

(def ^:private country-qid
  "ISO2 -> Wikidata QID。**載っていない国はスキップする**（推測しない）。
  QID を当て推量で作ると別の国の企業を混ぜることになる。"
  {"AE" "Q878" "AR" "Q414" "AT" "Q40" "AU" "Q408" "BE" "Q31" "BR" "Q155"
   "CA" "Q16" "CH" "Q39" "CL" "Q298" "CN" "Q148" "CO" "Q739" "CZ" "Q213"
   "DE" "Q183" "DK" "Q35" "EG" "Q79" "ES" "Q29" "ET" "Q115" "FI" "Q33"
   "FR" "Q142" "GB" "Q145" "GH" "Q117" "GR" "Q41" "HK" "Q8646" "HU" "Q28"
   "ID" "Q252" "IE" "Q27" "IL" "Q801" "IN" "Q668" "IT" "Q38" "JP" "Q17"
   "KE" "Q114" "KR" "Q884" "KW" "Q817" "LU" "Q32" "MA" "Q1028" "MX" "Q96"
   "MY" "Q833" "NG" "Q1033" "NL" "Q55" "NO" "Q20" "NZ" "Q664" "PE" "Q419"
   "PH" "Q928" "PK" "Q843" "PL" "Q36" "PT" "Q45" "QA" "Q846" "RO" "Q218"
   "SA" "Q851" "SE" "Q34" "SG" "Q334" "TH" "Q869" "TR" "Q43" "TW" "Q865"
   "UA" "Q212" "US" "Q30" "VN" "Q881" "ZA" "Q258"})

(defn- sparql
  "Wikidata SPARQL。売上のあるものを上に、無いものも拾う（売上が未記入でも
  著名な企業はあるため）。"
  [qid limit]
  (str "SELECT ?lei ?name ?rev ?site WHERE { "
       "?c wdt:P1278 ?lei ; wdt:P17 wd:" qid " . "
       "OPTIONAL { ?c wdt:P2139 ?rev } "
       ;; P856 = 公式サイト。**これが無いとパイプラインが完結しない** ——
       ;; lei-acquire は公式サイトを実際に GET して検証してから repo を作る
       ;; ので、:site 無しの候補は site-issue で止まり 1 件も repo にならない。
       ;; 推測 URL ではなく Wikidata の申告値をそのまま通し、検証は
       ;; lei-acquire に任せる（線引きは守ったまま穴だけ塞ぐ）。
       "OPTIONAL { ?c wdt:P856 ?site } "
       "?c rdfs:label ?name . FILTER(LANG(?name)=\"en\") "
       "} ORDER BY DESC(?rev) LIMIT " limit))

(defn- fetch-wikidata
  "1 カ国ぶんの候補 -> Promise<[{:lei :name :country :revenue}] | nil>。

  nil は「取得できなかった」で、空 vector の「0 件だった」とは違う。"
  [country limit]
  (if-let [qid (country-qid country)]
    (-> (js/fetch (str "https://query.wikidata.org/sparql?query="
                       (js/encodeURIComponent (sparql qid limit)))
                  #js {:headers #js {"accept" "application/sparql-results+json"
                                     "user-agent" ua}})
        (.then (fn [^js r]
                 (if-not (.-ok r)
                   (throw (js/Error. (str "Wikidata HTTP " (.-status r) " for " country)))
                   (.json r))))
        (.then (fn [j]
                 (->> (aget (aget j "results") "bindings")
                      array-seq
                      (map (fn [b]
                             {:lei (some-> (aget b "lei") (aget "value"))
                              :name (some-> (aget b "name") (aget "value"))
                              :revenue (some-> (aget b "rev") (aget "value"))
                              :site (some-> (aget b "site") (aget "value"))
                              :country country}))
                      (filter :lei)
                      vec)))
        (.catch (fn [e] (println "  !" (ex-message e)) nil)))
    (p/resolved [])))

;; ───────────────────────── GLEIF（裏取り）─────────────────────────

(defn- verify-lei
  "その LEI が GLEIF に実在し ACTIVE か -> Promise<{:ok? :legal-name :country :category}>。

  **Wikidata の LEI をそのまま信じない。** 誰でも編集できるので、
  発行元で裏を取ってから候補にする。取れなければ候補にしない
  （推測で埋めるより、落とすほうがいい）。"
  [lei]
  (-> (js/fetch (str "https://api.gleif.org/api/v1/lei-records/" lei)
                #js {:headers #js {"accept" "application/vnd.api+json"
                                   "user-agent" ua}})
      (.then (fn [^js r] (if (.-ok r) (.json r) nil)))
      (.then (fn [j]
               (if-not j
                 {:ok? false}
                 (let [a (aget (aget j "data") "attributes")
                       e (aget a "entity")]
                   {:ok? (= "ACTIVE" (aget e "status"))
                    :legal-name (some-> (aget e "legalName") (aget "name"))
                    :country (some-> (aget e "legalAddress") (aget "country"))
                    :category (aget e "category")}))))
      (.catch (fn [_] {:ok? false}))))

(defn- branch? [{:keys [category]}]
  ;; GLEIF の BRANCH は親とほぼ同名で返る。支店を会社として登録しない。
  (= "BRANCH" (str category)))

;; ───────────────────────── 実行 ─────────────────────────

(defn- arg-val [args flag default]
  (let [i (.indexOf (clj->js args) flag)]
    (if (neg? i) default (nth args (inc i) default))))

(defn -main [& args]
  (let [apply? (boolean (some #{"--apply"} args))
        per-country (js/parseInt (arg-val args "--per-country" "5") 10)
        only (when-let [s (arg-val args "--countries" nil)]
               (set (str/split s #",")))
        targets (read-edn targets-path)
        have (country-counts)
        known (existing-names)
        ;; **薄い国から埋める。** 155 件中 88 件が US に偏っているので、
        ;; 件数の少ない順に回すことで広がる方向に働く。
        queue (->> targets
                   (filter #(or (nil? only) (contains? only %)))
                   (sort-by #(get have % 0))
                   vec)]
    (println "target 国数 :" (count targets))
    (println "取得済み法域:" (count have) "| 既知の法人名:" (count known))
    (println "対象         :" (count queue) "カ国 ×" per-country "件/国")
    (println "source       : Wikidata（選別）→ GLEIF（裏取り）")
    (println)
    (-> (p/loop [remaining queue acc []]
          (if (empty? remaining)
            acc
            (p/let [c (first remaining)
                    picked (fetch-wikidata c per-country)
                    ;; 未知のものだけを GLEIF に問い合わせる（無駄打ちしない）
                    fresh (when picked
                            (->> picked
                                 (remove #(contains? known (normalize-name (:name %))))
                                 vec))
                    checked (if (seq fresh)
                              (p/all (map (fn [f]
                                            (p/let [v (verify-lei (:lei f))]
                                              (assoc f :verify v)))
                                          fresh))
                              [])]
              (let [seen-leis (set (map :lei acc))
                    ok (->> checked
                            (filter #(get-in % [:verify :ok?]))
                            (remove #(branch? (:verify %)))
                            ;; **登記国が違うものは、その国の候補にしない。**
                            ;; Wikidata の P17 は事業展開国も含むので、
                            ;; ケニアを埋めるつもりで Jumia（ドイツ法人）が
                            ;; 入る。法人カタログなので登記国が一致するもの
                            ;; だけを数える（実測 2026-07-27）。
                            (filter #(= c (get-in % [:verify :country])))
                            ;; 同じ LEI が複数国のクエリで返る（Jumia は
                            ;; ET/GH/KE/MA すべてで返った）。run 内で重複排除。
                            (remove #(contains? seen-leis (:lei %)))
                            ;; **GLEIF の法人名を正とする。** Wikidata の
                            ;; 表示名（"Honda"）ではなく登記名を候補に書く。
                            (map (fn [f]
                                   {:name (get-in f [:verify :legal-name])
                                    :country (get-in f [:verify :country])
                                    :lei (:lei f)
                                    ;; Wikidata の申告値。検証は lei-acquire。
                                    ;; 無ければ付けない（推測しない）。
                                    :site (:site f)}))
                            (filter :name)
                            (distinct)
                            vec)]
                (println (str "  " c
                              " 取得済 " (get have c 0)
                              " → Wikidata " (if picked (count picked) "取得失敗")
                              " / 未知 " (count (or fresh []))
                              " / 採用 " (count ok)))
                (p/recur (rest remaining) (into acc ok))))))
        (p/then
         (fn [found]
           (println)
           (println "新規候補:" (count found) "件（Wikidata で選び、GLEIF で裏取り済み）")
           (doseq [f (take 12 found)]
             (println "   " (:country f) (:name f) (str "(" (:lei f) ")")))
           (when (> (count found) 12)
             (println "    …他" (- (count found) 12) "件"))
           (if-not apply?
             (println "\ndry-run。--apply で" candidates-path "に追記します。")
             (let [txt (fs/readFileSync candidates-path "utf8")
                   idx (.lastIndexOf txt "]")
                   ;; :site は付けない（どちらの source も website を持たない。
                   ;; 推測 URL は検証済みの値と区別がつかなくなる）。
                   lines (str/join "\n"
                                   (map (fn [f]
                                          (str " {:name " (pr-str (:name f))
                                               " :country " (pr-str (:country f))
                                               " :lei " (pr-str (:lei f))
                                               (when (:site f)
                                                 (str " :site " (pr-str (:site f))))
                                               "}"))
                                        found))]
               (if (neg? idx)
                 (println "STOP:" candidates-path "の閉じ括弧が見つかりません。")
                 (do (fs/writeFileSync
                      candidates-path
                      (str (subs txt 0 idx)
                           "\n ;; ── discovery で追加（scripts/lei-discover-candidates.cljs）──\n"
                           " ;; Wikidata で売上順に選び、GLEIF で LEI と登記名を裏取り済み。\n"
                           " ;; :site は付けていない — lei-acquire が公式サイトを検証して埋める。\n"
                           lines "\n"
                           (subs txt idx)))
                     (println "追記しました:" (count found) "件 →" candidates-path)))))))
        (p/catch (fn [e] (println "ERROR:" (str e)) (js/process.exit 1))))))

(apply -main *command-line-args*)
