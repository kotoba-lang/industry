#!/usr/bin/env nbb
;; scripts/repo-taxonomy.cljs
;;
;; ADR-2607289600 D5 の生成器。west 登録 repo と workspace 内の実体から
;; 「identity 面 / runtime 面 / role リンク」の 3 面分類を **証拠ベースで** 導出し、
;; DataScript/Datomic にそのまま transact! できる EAVT entity-map のベクタとして
;; manifest/repo-taxonomy.edn に書き出す。
;;
;; 設計上の不変条件（ADR-2607289600）:
;;
;;   1. **名前 glob で層/種別を決めない。** manifest/layers.edn は
;;      `com-etzhayyim-*` を :layer-actor と定義しているが、実測
;;      (90-docs/audits/etzhayyim-reorg-audit.datoms.edn) では 612 中 actor は 145、
;;      library が 466 だった。判定はリポ内の証拠 (governor.cljc / blueprint.edn /
;;      wrangler.toml / organization.edn / README 宣言) から行い、名前は
;;      証拠が無いときの弱いフォールバックとしてのみ使い、その旨を
;;      :role/derived-from に必ず残す。
;;
;;   2. **捏造ゼロ。** 判定できない repo は :repo/kind を "unclassified" にし、
;;      理由を :repo/kind-evidence に書く。デフォルト値で埋めない
;;      (scripts/repo-maturity.cljs と同じ規律)。
;;
;;   3. **握り潰さない。** blueprint.edn のパース失敗・checkout 欠落は件数を
;;      集計して stderr に WARNING で報告する (CLAUDE.md docs/EDN 面の warn-skipped! 規約)。
;;
;;   4. **kind は排他、traits は非排他。** actor でありかつ service にデプロイされて
;;      いる repo は珍しくない。優先順位の取り合いをせず、:repo/kind に主種別、
;;      :repo/traits に検出した全シグナルを残す。
;;
;; 結合キー:
;;   :repo/path    runtime 面の主キー (manifest/repo-maturity.edn と同じ)
;;   :company/lei  identity 面の法人キー (market-intel / cloud-itonami-lei と同じ。
;;                 CLAUDE.md「企業データと fleet 状態も同じ面」の結合キー)
;;   :entity/did   agent / organism のキー
;;   :source/dataset "repo-taxonomy"  出自
;;
;; 使い方:
;;   nbb scripts/repo-taxonomy.cljs                      ; 生成して manifest/repo-taxonomy.edn を書く
;;   nbb scripts/repo-taxonomy.cljs --summary            ; 生成せず内訳だけ表示
;;   nbb scripts/repo-taxonomy.cljs --check              ; 既存ファイルと一致するか (CI 用、差分あれば exit 1)
;;   nbb scripts/repo-taxonomy.cljs --workspace <dir>    ; orgs/ を持つ workspace (既定: cwd)
;;   nbb scripts/repo-taxonomy.cljs --limit 50           ; 先頭 N repo だけ (動作確認用)
(require '[scripts.nbb-compat :as io :refer [slurp spit]]
         '[clojure.string :as str]
         '[clojure.edn :as edn])

(def fs (js/require "node:fs"))
(def npath (js/require "node:path"))

;; ---- CLI -------------------------------------------------------------------

(defn- parse-args [args]
  (loop [args args opts {:workspace "." :out "manifest/repo-taxonomy.edn"}]
    (if-let [[k & more] (seq args)]
      (case k
        "--workspace" (recur (rest more) (assoc opts :workspace (first more)))
        "--out"       (recur (rest more) (assoc opts :out (first more)))
        "--limit"     (recur (rest more) (assoc opts :limit (js/parseInt (first more))))
        "--check"     (recur more (assoc opts :check true))
        "--summary"   (recur more (assoc opts :summary true))
        (recur more opts))
      opts)))

(def opts (parse-args *command-line-args*))
(def ws (:workspace opts))

(def warnings (atom {}))
(defn- warn! [k] (swap! warnings update k (fnil inc 0)))

(defn- exists? [p] (.existsSync fs p))

;; `--workspace` は **orgs/ の checkout がどこにあるか** だけを指す。
;; レジストリ (west.yml / fleet-agents.edn / organism registry) と出力は cwd 相対 —
;; agent worktree から superproject の checkout をスキャンして、worktree 側の
;; manifest/ に書く運用のため。既定 (--workspace .) では両者は一致する。
(defn- ws-path [& parts] (apply (.-join npath) ws parts))

;; ---- manifest/west.yml parse (生成物なので行指向で十分) ----------------------

(defn- parse-west-projects [text]
  (loop [lines (str/split-lines text) in-projects? false cur nil acc []]
    (if-let [[line & more] (seq lines)]
      (let [t (str/trim line)]
        (cond
          (str/starts-with? line "  projects:") (recur more true nil acc)
          (and in-projects? (str/starts-with? line "  ") (not (str/starts-with? line "    ")))
          (recur more false nil (cond-> acc cur (conj cur)))

          (and in-projects? (str/starts-with? t "- name:"))
          (recur more true {:name (str/trim (subs t 7))} (cond-> acc cur (conj cur)))

          (and in-projects? cur (str/starts-with? t "path:"))
          (recur more true (assoc cur :path (str/trim (subs t 5))) acc)

          (and in-projects? cur (str/starts-with? t "groups:"))
          (recur more true
                 (assoc cur :groups (-> (subs t 7) str/trim
                                        (str/replace #"^\[|\]$" "")
                                        (str/split #",\s*")
                                        vec))
                 acc)
          :else (recur more in-projects? cur acc)))
      (cond-> acc cur (conj cur)))))

;; ---- 証拠収集 ---------------------------------------------------------------

(def skip-dirs #{".git" "node_modules" "target" ".shadow-cljs" ".cpcache"
                 "dist" "build" ".datalad" ".next" "vendor" ".venv"})

(defn- walk-files
  "repo 直下から最大 max-depth / max-entries で file の repo 相対パスを集める。
   etzhayyim/root のような 1.7GB repo で暴走しないよう必ず上限で打ち切る。"
  [root max-depth max-entries]
  (let [out (atom [])
        truncated? (atom false)]
    (letfn [(go [dir depth rel]
              (when (and (<= depth max-depth) (not @truncated?))
                (let [entries (try (.readdirSync fs dir #js {:withFileTypes true})
                                   (catch :default _ #js []))]
                  (doseq [e entries]
                    (when-not @truncated?
                      (let [nm (.-name e)
                            child (str dir "/" nm)
                            crel (if (= rel "") nm (str rel "/" nm))]
                        (cond
                          (.isDirectory e) (when-not (contains? skip-dirs nm)
                                             (go child (inc depth) crel))
                          :else (do (swap! out conj crel)
                                    (when (>= (count @out) max-entries)
                                      (reset! truncated? true))))))))))]
      (go root 0 ""))
    {:files @out :truncated? @truncated?}))

(defn- read-head [p n]
  (try (let [buf (.readFileSync fs p)] (subs (.toString buf "utf8") 0 (min n (.-length buf))))
       (catch :default _ nil)))

(defn- read-blueprint
  "blueprint.edn は map か [map] の両形がある。壊れていたら nil + WARN。"
  [p]
  (when (exists? p)
    (try (let [v (edn/read-string (slurp p))]
           (cond (map? v) v
                 (and (vector? v) (map? (first v))) (first v)
                 :else (do (warn! :blueprint-shape) nil)))
         (catch :default _ (do (warn! :blueprint-parse) nil)))))

(def organism-re
  #"(?i)artificial[-\s]organism|organism autonomy|organism loop|organism 循環|人工生命|artificial organism")

(defn project-roots
  "\"\" plus every `<dir>/` whose deps.edn sits one level down. The nested
  layout this workspace actually uses (python `lg/` beside clojure `clj/`)."
  [files]
  (into [""] (keep #(when-let [[_ d] (re-matches #"([^/]+)/deps\.edn" %)] (str d "/")) files)))

(defn under-project-root?
  "Predicate: is `f` under `<root><sub>` for some project root? `sub` is
  \"src/\" / \"test/\" / \"tests/\"."
  [files sub]
  (let [prefixes (map #(str % sub) (project-roots files))]
    (fn [f] (boolean (some #(str/starts-with? f %) prefixes)))))

(defn- collect-evidence [rel-path]
  (let [root (ws-path rel-path)]
    (if-not (exists? root)
      {:present? false}
      (let [{:keys [files truncated?]} (walk-files root 5 4000)
            base (fn [f] (last (str/split f #"/")))
            has? (fn [pred] (boolean (some pred files)))
            root-file? (fn [nm] (has? #(= % nm)))
            bp (read-blueprint (str root "/blueprint.edn"))
            ;; README.md だけを 4096 byte 読む実装だと、この workspace の EDN-only 規約で
            ;; README.edn / README.md.edn しか持たない repo (実測 2026-08-03 で 150 件) の
            ;; 宣言が構造的に見えない。manifest 系も宣言の在り処なので併せて読む。
            ;; CLAUDE.md / MATURITY.md も含める。実測 2026-08-03: yukkuri の
            ;; did:web:yukkuri.gftd.ai は CLAUDE.md にしか書かれておらず、README 系だけ
            ;; 見ると「実装はあるのに did 宣言が無い」と誤判定する。
            docs (->> ["README.md" "readme.md" "README.edn" "README.md.edn"
                       "manifest.edn" "manifest.jsonld" "actor-manifest.jsonld"
                       "CLAUDE.md" "MATURITY.md"]
                      (keep #(read-head (str root "/" %) 16384))
                      (str/join "\n"))
            readme (when (seq docs) docs)]
        {:present?     true
         :truncated?   truncated?
         :file-count   (count files)
         :governor?    (has? #(re-matches #"governor\.clj[cs]?" (base %)))
         :advisor?     (has? #(re-matches #"advisor\.clj[cs]?" (base %)))
         :actor-edn?   (root-file? "actor.edn")
         :identity-edn? (root-file? "identity.edn")
         :blueprint    bp
         :org-record?  (or (root-file? "organization.edn")
                           (boolean (:company/lei bp)))
         :service?     (has? #(re-matches #"wrangler\.(toml|json|jsonc)" (base %)))
         :corpus?      (has? #(str/ends-with? % ".datoms.edn"))
         ;; Source roots are the repo root AND every first-level directory that
         ;; carries its own deps.edn (`clj/src`, `lg-clj/src` …). Measured
         ;; 2026-08-23: cloud-itonami/ai-gftd-dougaka keeps its whole engine
         ;; under clj/ and was classified "docs — no src" with 43 KB of source
         ;; and 7 test files in plain sight; yukkuri / app-yukkuri have the
         ;; same lg/ + clj/ layout. The nested root must declare itself with a
         ;; deps.edn — a stray src/ under docs/ is not a project.
         :src?         (has? (under-project-root? files "src/"))
         :tests?       (has? (fn [f] (or ((under-project-root? files "test/") f)
                                         ((under-project-root? files "tests/") f))))
         :deps?        (root-file? "deps.edn")
         :data?        (has? #(and (str/starts-with? % "data/")
                                   (or (str/ends-with? % ".edn") (str/ends-with? % ".json")
                                       (str/ends-with? % ".csv"))))
         :docs?        (has? #(or (str/starts-with? % "docs/") (str/starts-with? % "90-docs/")))
         ;; ADR-2607171000 / 2607171100 の MOVED tombstone 型と、
         ;; NOT-MIGRATED-*（vendor/charter 判定で意図的に移送しなかった印）。
         :tombstone?   (has? #(let [b (base %)]
                                (or (str/starts-with? b "NOT-MIGRATED")
                                    (re-find #"MOVED\.md" b))))
         :ui?          (has? #(or (= (base %) "index.html") (= (base %) "shadow-cljs.edn")))
         :heartbeat?   (has? #(str/includes? (base %) "heartbeat"))
         ;; ADR-2607289700 D1 の organism 2 条件を、宣言ではなく実装の証拠として測る。
         ;; (a) 自分で起きる: heartbeat または resident loop
         ;; (b) 自分の did で対外発話する: did 宣言 + 実際に外へ出す実装
         :resident-loop? (has? #(re-find #"(?i)(^|/)(loop|tick|scheduler|cron|daemon)\.(clj[cs]?|kotoba)$" %))
         :did-declared?  (boolean (and readme (re-find #"did:(web|key):" readme)))
         :publish-impl?  (has? #(re-find #"(?i)(^|/)(publisher|aozora|cacao|pds)\.(clj[cs]?|kotoba)$" %))
         :organism-doc? (boolean (and readme (re-find organism-re readme)))}))))

;; ---- runtime 面: kind + traits ---------------------------------------------

(defn- traits-of [ev]
  (cond-> #{}
    (:governor? ev)    (conj :governor)
    (:advisor? ev)     (conj :advisor)
    (:actor-edn? ev)   (conj :actor-declared)
    (:blueprint ev)    (conj :blueprint)
    (:org-record? ev)  (conj :organisation-record)
    (:service? ev)     (conj :deploy-target)
    (:corpus? ev)      (conj :datoms)
    (:data? ev)        (conj :data)
    (:docs? ev)        (conj :docs)
    (:src? ev)         (conj :source)
    (:tests? ev)       (conj :tests)
    (:ui? ev)          (conj :ui)
    (:heartbeat? ev)   (conj :heartbeat)
    (:organism-doc? ev)(conj :organism-declared)
    (:resident-loop? ev)(conj :resident-loop)
    (:publish-impl? ev)(conj :publish-impl)
    (:tombstone? ev)   (conj :tombstone)
    (:identity-edn? ev)(conj :identity-record)))

(defn- classify
  "kind は排他。証拠が無ければ unclassified (デフォルト値で埋めない)。
   戻り値 [kind evidence-string]。"
  [ev]
  (cond
    (not (:present? ev))
    ["absent" "checkout not present in workspace"]

    (and (:tombstone? ev) (not (:src? ev)))
    ["tombstone" "NOT-MIGRATED / MOVED marker without src"]

    ;; organism は **宣言ではなく実装**で決める (ADR-2608032100)。
    ;;
    ;; 旧実装は `README に organism 語 AND (heartbeat OR governor)` だった。
    ;; governor は actor なら普通に持つので、実質「README にそう書いてあれば organism」
    ;; と同じで、**取りこぼすより過大報告した** — 実測 2026-08-03 で yomi / kyoninka /
    ;; sng は heartbeat も resident loop も持たないのに organism と報告され、逆に
    ;; cron + aozora publisher + did:web を実装する yukkuri は README.md が無いため
    ;; 落ちていた。
    ;;
    ;; D1 の 2 条件を両方満たすことを要求する。宣言は任意 (あれば evidence に出すだけ)。
    (and (or (:heartbeat? ev) (:resident-loop? ev))
         (:did-declared? ev)
         (:publish-impl? ev))
    ["organism" (str (if (:heartbeat? ev) "heartbeat" "resident loop")
                     " + did + publisher impl"
                     (when (:organism-doc? ev) " (declared)"))]

    (or (:governor? ev) (:actor-edn? ev))
    ["actor" (if (:governor? ev) "governor.clj* present" "actor.edn present")]

    ;; assoc / LEI は organization.edn や :company/lei を持つ「実在法人の記録」。
    ;; 補助的な src が同居していても actor ではない (governor が無いことは上で確定済み)。
    (:org-record? ev)
    ["organisation-record" "organization.edn / :company/lei without governor"]

    (:service? ev)
    ["service" "wrangler deploy config present"]

    (and (:src? ev) (:ui? ev))
    ["app" "src + index.html/shadow-cljs.edn"]

    (:src? ev)
    ["lib" "src without governor/deploy/ui markers"]

    (or (:corpus? ev) (:data? ev))
    ["corpus" (if (:corpus? ev) "*.datoms.edn without src" "data/ payload without src")]

    (:blueprint ev)
    ["blueprint-only" "blueprint.edn without src"]

    (:docs? ev)
    ["docs" "docs/ only — no src, data or deploy target"]

    :else
    ["unclassified" (str "no classifying marker (" (:file-count ev) " files scanned)")]))

;; ---- role リンク: この repo はどの identity kind の役割か --------------------

(defn- role-of
  "ISCO は person の職業、ISIC/LEI/assoc/ISO3166/COFOG は organisation。
   blueprint のキーを最優先し、無ければ repo 名から (derived-from に必ず残す)。"
  [nm bp]
  (let [b (fn [k] (get bp k))]
    (cond
      (b :itonami.blueprint/isco-08)
      {:for "person" :standard (str "isco-08:" (b :itonami.blueprint/isco-08)) :from "blueprint"}
      (b :itonami.blueprint/isic-rev5)
      {:for "organisation" :standard (str "isic-rev5:" (b :itonami.blueprint/isic-rev5)) :from "blueprint"}
      (:company/lei bp)
      {:for "organisation" :standard (str "lei:" (:company/lei bp)) :from "blueprint"}

      (re-find #"^cloud-itonami-isco-(\S+)$" nm)
      {:for "person" :standard (str "isco-08:" (second (re-find #"^cloud-itonami-isco-(\S+)$" nm))) :from "name"}
      (re-find #"^cloud-itonami-isic-(\S+)$" nm)
      {:for "organisation" :standard (str "isic-rev5:" (second (re-find #"^cloud-itonami-isic-(\S+)$" nm))) :from "name"}
      (re-find #"^cloud-itonami-lei-(\S+)$" nm)
      {:for "organisation" :standard (str "lei:" (str/upper-case (second (re-find #"^cloud-itonami-lei-(\S+)$" nm)))) :from "name"}
      (re-find #"^cloud-itonami-assoc-(\S+)$" nm)
      {:for "organisation" :standard (str "assoc:" (second (re-find #"^cloud-itonami-assoc-(\S+)$" nm))) :from "name"}
      (re-find #"^cloud-itonami-iso3166-(\S+)$" nm)
      {:for "organisation" :standard (str "iso3166:" (second (re-find #"^cloud-itonami-iso3166-(\S+)$" nm))) :from "name"}
      (re-find #"^cloud-itonami-cofog-(\S+)$" nm)
      {:for "organisation" :standard (str "cofog:" (second (re-find #"^cloud-itonami-cofog-(\S+)$" nm))) :from "name"}
      :else nil)))

;; ---- identity 面 ------------------------------------------------------------

(defn- agent-entities []
  (let [p "manifest/fleet-agents.edn"]
    (if-not (exists? p)
      (do (warn! :fleet-agents-missing) [])
      (->> (str/split-lines (slurp p))
           (remove str/blank?)
           (keep (fn [line]
                   (try (let [m (edn/read-string line)]
                          {:entity/kind    "agent"
                           :entity/id      (:did m)
                           :entity/did     (:did m)
                           :entity/name    (:agent m)
                           :entity/signer  (:signer m)
                           :entity/grants  (pr-str (:grants m))
                           :entity/enrolled (:enrolled m)
                           :source/dataset "repo-taxonomy"})
                        (catch :default _ (do (warn! :fleet-agent-parse) nil)))))
           vec))))

(defn- organism-entities []
  (let [p "80-data/system/artificial-organism-actors.json"]
    (if-not (exists? p)
      (do (warn! :organism-registry-missing) [])
      (try (let [j (js->clj (js/JSON.parse (slurp p)) :keywordize-keys true)]
             (->> (:results j)
                  (map (fn [r]
                         {:entity/kind    "organism"
                          :entity/id      (:did r)
                          :entity/did     (:did r)
                          :entity/handle  (:handle r)
                          :entity/product (:product r)
                          :entity/state   (:state r)
                          :source/dataset "repo-taxonomy"}))
                  vec))
           (catch :default _ (do (warn! :organism-registry-parse) []))))))

;; ---- 実行 -------------------------------------------------------------------

(def projects
  (let [all (->> (parse-west-projects (slurp "manifest/west.yml"))
                 (filter :path)
                 (remove #(contains? (set (:groups %)) "datalad"))
                 (sort-by :path))]
    (if-let [n (:limit opts)] (vec (take n all)) (vec all))))

(println (str "repo-taxonomy: " (count projects) " west projects (datalad 除く), workspace=" ws))

(def repo-rows
  (vec (for [{:keys [name path]} projects]
         (let [ev (collect-evidence path)
               [kind why] (classify ev)
               bp (:blueprint ev)
               role (role-of name bp)]
           {:name name :path path :ev ev :kind kind :why why :bp bp :role role}))))

(defn- org-of [path]
  (let [seg (str/split path #"/")]
    (if (and (= (first seg) "orgs") (second seg)) (second seg) "superproject")))

(def repo-entities
  (vec (for [r repo-rows]
         (let [ev (:ev r) bp (:bp r)]
           (cond-> {:repo/path       (:path r)
                    :repo/name       (:name r)
                    :repo/org        (org-of (:path r))
                    :repo/kind       (:kind r)
                    :repo/kind-evidence (:why r)
                    :repo/traits     (pr-str (traits-of ev))
                    :repo/present    (boolean (:present? ev))
                    :source/dataset  "repo-taxonomy"}
             (:truncated? ev)                    (assoc :repo/scan-truncated true)
             (:itonami.blueprint/maturity bp)    (assoc :blueprint/maturity (str (:itonami.blueprint/maturity bp)))
             (:itonami.blueprint/governor bp)    (assoc :blueprint/governor (str (:itonami.blueprint/governor bp)))
             (:itonami.blueprint/status bp)      (assoc :blueprint/status (str (:itonami.blueprint/status bp))))))))

(def role-entities
  (vec (for [r repo-rows :when (:role r)]
         {:role/repo         (:path r)
          :role/for-kind     (:for (:role r))
          :role/standard     (:standard (:role r))
          :role/derived-from (:from (:role r))
          :source/dataset    "repo-taxonomy"})))

(def organisation-entities
  (vec (for [r repo-rows
             :let [bp (:bp r)]
             :when (:company/lei bp)]
         {:entity/kind        "organisation"
          :entity/id          (str "lei:" (:company/lei bp))
          :company/lei        (:company/lei bp)
          :company/legal-name (:company/legal-name bp)
          :company/jurisdiction (:company/jurisdiction bp)
          :entity/repo        (:path r)
          :source/dataset     "repo-taxonomy"})))

(def identity-entities
  (vec (concat organisation-entities (agent-entities) (organism-entities))))

;; person 面のギャップは埋めずに明示する (ADR-2607289600 D6)。
(def gap-entities
  (let [persons (count (filter #(= "person" (:entity/kind %)) identity-entities))
        person-roles (count (filter #(= "person" (:role/for-kind %)) role-entities))]
    (when (zero? persons)
      [{:taxonomy/gap  "person-plane"
        :taxonomy/note (str person-roles " 件の repo が person の職業役割 (ISCO) を実装しているが、"
                            "identity 面に person entity が 0 件。approval gate / bounded-spend の"
                            "帰着先が person である以上、ここは埋めるべき穴 (捏造せず gap として記録)。")
        :source/dataset "repo-taxonomy"}])))

(def all-entities
  (let [rows (concat repo-entities role-entities identity-entities gap-entities)]
    (vec (map-indexed (fn [i m] (assoc m :db/id (- (inc i)))) rows))))

;; ---- 出力 -------------------------------------------------------------------

;; cljs の map は 8 キーを超えると HashMap になり印字順が入れ替わる。
;; --check の byte 比較と diff の可読性のため、キー順を固定して自前で印字する。
(def key-order
  [:db/id
   :repo/path :repo/name :repo/org :repo/kind :repo/kind-evidence :repo/traits
   :repo/present :repo/scan-truncated
   :blueprint/maturity :blueprint/governor :blueprint/status
   :role/repo :role/for-kind :role/standard :role/derived-from
   :entity/kind :entity/id :entity/did :entity/name :entity/handle :entity/product
   :entity/state :entity/signer :entity/grants :entity/enrolled :entity/repo
   :company/lei :company/legal-name :company/jurisdiction
   :taxonomy/gap :taxonomy/note
   :source/dataset])

(defn- render-entity [m]
  (let [known (set key-order)
        ks (concat (filter #(contains? m %) key-order)
                   (sort (remove known (keys m))))]
    (str "{" (str/join ", " (map #(str (pr-str %) " " (pr-str (get m %))) ks)) "}")))

(defn- render [entities]
  (str ";; manifest/repo-taxonomy.edn — generated by scripts/repo-taxonomy.cljs. DO NOT EDIT BY HAND.\n"
       ";; Regenerate: nbb scripts/repo-taxonomy.cljs\n"
       ";; Verify:     nbb scripts/repo-taxonomy.cljs --check\n"
       ";; ADR-2607289600 (identity / runtime / role の 3 面分類)。\n"
       ";;\n"
       ";; 3 面:\n"
       ";;   identity 面 :entity/kind = person | organisation | agent | organism  (did / lei を持つ)\n"
       ";;   runtime  面 :repo/kind   = actor | organism | service | app | lib | corpus |\n"
       ";;                              organisation-record | blueprint-only | unclassified | absent\n"
       ";;   role リンク :role/for-kind = person | organisation  (ISCO→person, ISIC/LEI/assoc→organisation)\n"
       ";;\n"
       ";; 判定は repo 内の証拠から。名前 glob は証拠が無いときのみ、:role/derived-from \"name\" として。\n"
       ";; 判定できないものは \"unclassified\"。デフォルト値で埋めない。\n"
       "[" (str/join "\n " (map render-entity entities)) "]\n"))

(def rendered (render all-entities))

(defn- summary []
  (let [tally (fn [ks] (->> ks frequencies (sort-by (comp - val))))]
    (println "\n--- runtime 面 :repo/kind ---")
    (doseq [[k n] (tally (map :repo/kind repo-entities))] (println (str "  " k ": " n)))
    (println "--- role リンク :role/for-kind ---")
    (doseq [[k n] (tally (map :role/for-kind role-entities))] (println (str "  " k ": " n)))
    (println "--- role 導出元 ---")
    (doseq [[k n] (tally (map :role/derived-from role-entities))] (println (str "  " k ": " n)))
    (println "--- identity 面 :entity/kind ---")
    (doseq [[k n] (tally (map :entity/kind identity-entities))] (println (str "  " k ": " n)))
    (println (str "--- 合計 entity: " (count all-entities) " ---"))
    (when (seq @warnings)
      (binding [*print-fn* *print-err-fn*]
        (println "WARNING repo-taxonomy: 以下を握り潰さず報告する:")
        (doseq [[k n] @warnings] (println (str "  " (name k) ": " n)))))))

(cond
  (:summary opts)
  (summary)

  ;; --out は cwd 相対 (書き込む repo)、--workspace は orgs/ と入力レジストリの在り処。
  ;; agent worktree から superproject の checkout をスキャンする運用のため両者は別。
  (:check opts)
  (let [p (:out opts)]
    (if (and (exists? p) (= (slurp p) rendered))
      (do (println "repo-taxonomy: OK (canonical)") (summary))
      (do (println "repo-taxonomy: STALE — nbb scripts/repo-taxonomy.cljs で再生成してください")
          (io/exit 1))))

  :else
  (do (spit (:out opts) rendered)
      (println (str "repo-taxonomy: wrote " (:out opts) " (" (count all-entities) " entities)"))
      (summary)))
