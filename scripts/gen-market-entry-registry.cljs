#!/usr/bin/env nbb
;; scripts/gen-market-entry-registry.cljs — cloud-itonami-iso3166-* 衛星 223 か国
;; (+ 省庁 agency repo 群) の blueprint.edn / organization.edn を集約し、
;; gftdcojp/cloud-itonami の market-entry API 用静的データ namespace
;; (src/cloud_itonami/market_entry.cljc) を生成する。
;;
;; ADR-2607122400。ADR-2607121000 P1「iso3166×223 を market-entry API 化」の
;; データ供給側。open-business と同じく「静的・公開・portable な .cljc データを
;; edge bundle にコンパイルする」方式 (KV 不要・認証不要)。
;;
;; 使い方:
;;   nbb scripts/gen-market-entry-registry.cljs <output-repo-root> [github-repo-list.txt]
;;   例: gh api "orgs/cloud-itonami/repos?per_page=100" --paginate --jq '.[].name' > /tmp/gh.txt
;;       nbb scripts/gen-market-entry-registry.cljs orgs/gftdcojp/cloud-itonami-wt-market-entry /tmp/gh.txt
;;   github-repo-list を渡すと、GitHub に実在する衛星だけに :repo リンクを載せる
;;   (honest-default: 未 push のローカル scaffold への dead link を公開 API に出さない)。

(require '[scripts.nbb-compat :refer [slurp spit file-seq exit]]
         '[clojure.edn :as edn]
         '[clojure.string :as str]
         '[clojure.java.shell :as shell])

(def root (str/trim (:out (shell/sh "git" "rev-parse" "--show-toplevel"))))
(def fs (js/require "node:fs"))

(def prefix "cloud-itonami-iso3166-")

(defn slurp-edn [path]
  (try (edn/read-string {:default (fn [_tag v] v)} (slurp path))
       (catch :default _ nil)))

;; ---- gateway join (ADR-2607122400 addendum): 6910 incorporation ×
;; 8291 compliance-source カタログを per-国エントリへ bake する。
;; 両 facts.cljc は「純データの def」なので、ファイル全体を edn として読み
;; (def catalog <literal>) の literal だけ抜く。コードが混じる他の def
;; (coverage 等) はデータとして読めるが使わない。

(defn extract-top-form
  "src 内の marker から始まる、括弧バランスの取れたトップレベルフォーム文字列を
  返す。文字列リテラル(エスケープ含む)と ; 行コメントの中の括弧は数えない。
  ファイル全体を edn として読むと後方の def に混じる #() 等で落ちるため、
  対象フォームだけを切り出してから読む。"
  [src marker]
  (let [start (str/index-of src marker)]
    (assert (some? start) (str "marker not found: " (pr-str marker)))
    (loop [i start depth 0 in-str? false esc? false in-comment? false]
      (let [ch (.charAt src i)]
        (assert (seq ch) "unbalanced form (EOF reached)")
        (cond
          in-comment? (recur (inc i) depth false false (not= ch "\n"))
          in-str? (cond esc? (recur (inc i) depth true false false)
                        (= ch "\\") (recur (inc i) depth true true false)
                        (= ch "\"") (recur (inc i) depth false false false)
                        :else (recur (inc i) depth true false false))
          (= ch "\"") (recur (inc i) depth true false false)
          (= ch ";") (recur (inc i) depth false false true)
          (= ch "(") (recur (inc i) (inc depth) false false false)
          (= ch ")") (if (= depth 1)
                       (subs src start (inc i))
                       (recur (inc i) (dec depth) false false false))
          :else (recur (inc i) depth false false false))))))

(defn read-def-literal
  "path の .cljc から (def name ... <literal>) の literal(最終要素)を返す。"
  [path def-name]
  (let [form (edn/read-string {:default (fn [_tag v] v)}
                              (extract-top-form (slurp path)
                                                (str "(def " def-name "\n")))]
    (last form)))

(def incorporation-catalog-path
  "orgs/cloud-itonami/cloud-itonami-isic-6910/src/formation/facts.cljc")
(def compliance-catalog-path
  "orgs/cloud-itonami/cloud-itonami-isic-8291/src/dossier/facts.cljc")

(defn incorporation-by-iso3
  "6910 formation.facts/catalog: \"JPN\"/\"USA-DE\" キー → iso3 でグループ化。
  \"USA-DE\" のような法域 variant は :key にそのまま残す(federalism note 等の
  ニュアンスを潰さない)。"
  []
  (let [catalog (read-def-literal (str root "/" incorporation-catalog-path) 'catalog)]
    (assert (map? catalog) "6910 catalog literal not found/not a map")
    (group-by #(first (str/split (:key %) #"-"))
              (map (fn [[k v]] (assoc v :key k)) catalog))))

(defn compliance-by-iso3
  "8291 dossier.facts/catalog: :jurisdiction (:jpn/:eu 等) → ISO3 大文字で
  グループ化。:eu のような非国コードはどの国にも attach しない(EU 加盟国
  マッピング表を持たない = 正直なカバレッジ)。"
  []
  (let [catalog (read-def-literal (str root "/" compliance-catalog-path) 'catalog)]
    (assert (vector? catalog) "8291 catalog literal not found/not a vector")
    (group-by #(str/upper-case (name (:jurisdiction %))) catalog)))

(defn satellite-dirs []
  (->> (.readdirSync fs (str root "/orgs/cloud-itonami"))
       (filter #(str/starts-with? % prefix))
       sort))

(defn read-satellite [on-github? dir]
  (let [base (str root "/orgs/cloud-itonami/" dir)
        slug (subs dir (count prefix))
        bp (slurp-edn (str base "/blueprint.edn"))
        org (slurp-edn (str base "/organization.edn"))]
    {:slug slug
     :iso3 (str/upper-case (first (str/split slug #"-")))
     :agency? (str/includes? slug "-")
     :repo (when (on-github? dir) (str "https://github.com/cloud-itonami/" dir))
     :blueprint-name (:itonami.blueprint/name bp)
     :governor (:itonami.blueprint/governor bp)
     :required-technologies (vec (:itonami.blueprint/required-technologies bp))
     :name-en (:name-en org)
     :name-local (:name-local org)
     :wikidata (:wikidata org)
     :official-url (or (:official-url org)
                       (get-in bp [:itonami.blueprint/organization :official-url]))
     :contact-page (or (:contact-page org)
                       (get-in bp [:itonami.blueprint/organization :contact-page]))
     :ooyake-id (or (:ooyake-id org)
                    (get-in bp [:itonami.blueprint/organization :ooyake-id]))
     :head-role (:head-role org)}))

(defn ->agency [{:keys [slug name-en name-local official-url ooyake-id repo]}]
  (cond-> {:slug slug}
    repo (assoc :repo repo)
    name-en (assoc :name-en name-en)
    name-local (assoc :name-local name-local)
    official-url (assoc :official-url official-url)
    ooyake-id (assoc :ooyake-id ooyake-id)))

(defn ->incorporation [{:keys [key name owner-authority legal-basis national-spec
                               provenance required-docs notes]}]
  (cond-> {:key key}
    name (assoc :name name)
    owner-authority (assoc :owner-authority owner-authority)
    legal-basis (assoc :legal-basis legal-basis)
    national-spec (assoc :national-spec national-spec)
    provenance (assoc :provenance provenance)
    (seq required-docs) (assoc :required-docs (vec required-docs))
    notes (assoc :notes notes)))

(defn ->compliance-source [{:keys [id name jurisdiction class covers access url live-capable?]}]
  (cond-> {:id (clojure.core/name id)
           :name name
           :class (clojure.core/name class)
           :access (clojure.core/name access)
           :url url}
    jurisdiction (assoc :jurisdiction (clojure.core/name jurisdiction))
    (seq covers) (assoc :covers (vec (sort (map clojure.core/name covers))))
    (some? live-capable?) (assoc :live-capable? live-capable?)))

(defn ->country [{:keys [iso3 slug repo blueprint-name governor required-technologies
                         name-en name-local wikidata official-url contact-page
                         ooyake-id head-role]}
                 agencies incorporation compliance-sources]
  (cond-> {:iso3 iso3 :slug slug}
    repo (assoc :repo repo)
    name-en (assoc :name-en name-en)
    name-local (assoc :name-local name-local)
    wikidata (assoc :wikidata wikidata)
    official-url (assoc :official-url official-url)
    contact-page (assoc :contact-page contact-page)
    ooyake-id (assoc :ooyake-id ooyake-id)
    head-role (assoc :head-role head-role)
    blueprint-name (assoc :blueprint-name blueprint-name)
    governor (assoc :governor governor)
    (seq required-technologies) (assoc :required-technologies required-technologies)
    (seq agencies) (assoc :agencies (vec (sort-by :slug (map ->agency agencies))))
    (seq incorporation)
    (assoc :incorporation (vec (sort-by :key (map ->incorporation incorporation))))
    (seq compliance-sources)
    (assoc :compliance-sources (vec (sort-by :id (map ->compliance-source compliance-sources))))))

(defn build [on-github?]
  (let [sats (map #(read-satellite on-github? %) (satellite-dirs))
        {agencies true countries false} (group-by :agency? sats)
        by-parent (group-by :iso3 agencies)
        inc-idx (incorporation-by-iso3)
        comp-idx (compliance-by-iso3)]
    (->> countries
         (map (fn [c] (->country c
                                 (get by-parent (:iso3 c))
                                 (get inc-idx (:iso3 c))
                                 (get comp-idx (:iso3 c)))))
         (sort-by :iso3)
         vec)))

(defn emit-cljc [countries]
  (str
   "(ns cloud-itonami.market-entry\n"
   "  \"GENERATED — do not edit by hand. iso3166×" (count countries) " market-entry registry\n"
   "  (ADR-2607121000 P1 / ADR-2607122400), aggregated from the cloud-itonami org's\n"
   "  cloud-itonami-iso3166-* satellite repos' blueprint.edn + organization.edn.\n"
   "  Static, public, portable .cljc data compiled into the :edge-api bundle —\n"
   "  same discipline as cloud-itonami.open-business/blueprints (no KV, no auth).\n"
   "  Regenerate from the superproject root:\n"
   "    nbb scripts/gen-market-entry-registry.cljs <this-repo-root>\")\n"
   "\n"
   "(def countries\n"
   "  [" (str/join "\n   " (map pr-str countries)) "])\n"
   "\n"
   "(def by-iso3\n"
   "  \"ISO 3166-1 alpha-3 (upper-case) -> country entry.\"\n"
   "  (into {} (map (juxt :iso3 identity)) countries))\n"))

(defn -main [& args]
  (let [[out-root gh-list] args]
    (when-not out-root
      (println "usage: nbb scripts/gen-market-entry-registry.cljs <output-repo-root> [github-repo-list.txt]")
      (exit 1))
    (let [on-github? (if gh-list
                       (set (str/split-lines (slurp gh-list)))
                       (constantly true))
          countries (build on-github?)
          n-agencies (reduce + (map (comp count :agencies) countries))
          n-linked (count (filter :repo countries))
          n-inc (count (filter :incorporation countries))
          n-comp (count (filter :compliance-sources countries))
          out (str out-root "/src/cloud_itonami/market_entry.cljc")]
      (assert (<= 150 (count countries)) (str "suspiciously few countries: " (count countries)))
      (assert (pos? n-inc) "gateway join produced zero incorporation sections")
      (spit out (emit-cljc countries))
      (println "wrote" out "countries=" (count countries)
               "agencies=" n-agencies "with-repo-link=" n-linked
               "incorporation=" n-inc "compliance=" n-comp))))

(apply -main *command-line-args*)
