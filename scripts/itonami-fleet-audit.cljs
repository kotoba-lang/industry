#!/usr/bin/env nbb
;; scripts/itonami-fleet-audit.cljs — cloud-itonami actor fleet の健全性/活動性を
;; 一括評価する CLI。
;;
;; orgs/cloud-itonami/cloud-itonami-* の各リポジトリについて、blueprint.edn の
;; 宣言的メタデータ(maturity/domain/governor)と、実測シグナル(直近コミット日時・
;; dirty 状態・未push コミット数・src/test ファイル数・facts.cljc の法域カバレッジ数・
;; governed-actor 構成要素の有無)を集め、そこから2つの派生判定を計算する:
;;   :itonami.fleet-audit/prod-ready?            status が :active(実装済+直近
;;                                                 メンテ) か :archive(アーカイブ
;;                                                 として実コンテンツを保持)である
;;                                                 場合のみ true。:stub/:no-commits/
;;                                                 :stale/:dormant はまだ prod に
;;                                                 達していない。
;;   :itonami.fleet-audit/real-world-ingest-signal どの実世界データ経路で実測した
;;                                                 か — :facts-citations
;;                                                 (facts.cljc 内の実 URL 引用数。
;;                                                 isic/isco の国別カタログだけで
;;                                                 なく municipality の条例別
;;                                                 カタログ等、キー形が違っても
;;                                                 拾える汎化版) / :archive-content
;;                                                 (80-data/ 配下の実ファイル) /
;;                                                 :actor-blueprint-structure
;;                                                 (isco/gtin family 限定。
;;                                                 governor.cljc+store.cljc の
;;                                                 governed-actor blueprint 構成要素が
;;                                                 あるが、これは facts.cljc を持つ
;;                                                 設計ではない ADR-2607012000 の別種の
;;                                                 repo なので :none-measured に落とさ
;;                                                 ない。2026-07-23: cloud-itonami-gtin-
;;                                                 issuance/-verification が isco と
;;                                                 basename-for-basename 一致する構造の
;;                                                 clone だと確認して追加、同時に
;;                                                 cofog/unspsc/partners/hygiene-access
;;                                                 は governor+store は共通でも
;;                                                 registry/phase/sim/operation を伴う
;;                                                 別形状〈isic 型〉で、unspsc-27 が同じ
;;                                                 形状のまま実 facts.cljc(OSHA 引用6件)
;;                                                 を持つことが「いずれ facts.cljc を
;;                                                 持ちうる」ことの証拠なので対象外に
;;                                                 据え置いた) /
;;                                                 :internal-reference-facts
;;                                                 (isic family 限定。facts.cljc は
;;                                                 あるが :cost-threshold 形の内部
;;                                                 参照テーブル — Governor/Advisor が
;;                                                 コスト閾値を発明しないための内部
;;                                                 参照専用で、そもそも外部citationを
;;                                                 持つ設計ではない。2026-07-23 実測:
;;                                                 71 件の isic zero-citation repo の
;;                                                 うち 24 件がこの shape。isco の
;;                                                 :actor-blueprint-structure と同型の
;;                                                 「別種repoなのでgapに落とさない」
;;                                                 救済) /
;;                                                 :plain-library-structure
;;                                                 (regulatory family 限定。
;;                                                 cloud-itonami-regulatory-tracker が
;;                                                 advisor/governor/store を一切持たない
;;                                                 plain な .cljc 共有ライブラリだと自ら
;;                                                 明記〈'not a governed actor'〉している
;;                                                 ため、2026-07-23 追加) /
;;                                                 :declared-internal-only(repo 自身が
;;                                                 blueprint.edn で
;;                                                 :itonami.blueprint/citation-policy
;;                                                 :internal-vocabulary-only と宣言し、
;;                                                 根拠文書を :citation-policy-basis で
;;                                                 明示している場合。設計上 citation を
;;                                                 持てない actor — 埋めること自体が
;;                                                 ADR 違反になる gap を報告しないため、
;;                                                 2026-07-25 追加。basis 無しの宣言は
;;                                                 honour しない) /
;;                                                 :none-measured(どの経路も
;;                                                 存在しない)。なお isic family の
;;                                                 facts.cljc で URL citation が0件の
;;                                                 場合、:spec-basis "..." 形の実在する
;;                                                 法令/規制citation(URLでなく条文名で
;;                                                 引用)があれば :facts-citations の
;;                                                 count に合算する(2026-07-23 実測:
;;                                                 71件中7件がこの shape。detector が
;;                                                 https:// のみ数えていたための過小
;;                                                 count で、カテゴリ誤判定ではない)。
;;   :itonami.fleet-audit/real-world-ingest-gap?  対応する count が 0、または
;;                                                 経路が :none-measured なら true
;;                                                 (未測定は「対象外」ではなく
;;                                                 「未達」— ADR-2607203000 の
;;                                                 「今日数値を持たない entity は
;;                                                 対象外でなくカバレッジ未達」原則
;;                                                 に合わせる)。
;; 集めた結果を:
;;   1. 人間可読なサマリ+「要対応」表を標準出力に表示
;;   2. manifest/edn-datomize.cljs の tx-entities モード経由で、そのまま
;;      Datomic/Datascript に transact できる tx-data を生成し、
;;      manifest/schema.edn に :itonami.fleet-audit/* 属性を登録
;;   3. manifest/itonami-fleet-audit.edn へ(:db/id 無しの flat map ベクタ、
;;      manifest/repo-maturity.edn と同じ規約で)毎回上書き保存
;; の3つを行う。実際のアクターリポジトリは読むだけで一切書き換えない
;; (blueprint.edn 自体を tx-data 化したい場合は
;; `nbb manifest/edn-datomize.cljs wrap-map-glob orgs/cloud-itonami blueprint.edn
;; itonami.blueprint` を別途、意図して実行すること — 380個の別 git repo を書き換える
;; 操作なので、このスクリプトからは絶対に自動実行しない)。
;;
;; 使い方:
;;   nbb scripts/itonami-fleet-audit.cljs             — 人間可読なサマリ+「要対応」表
;;   nbb scripts/itonami-fleet-audit.cljs --all        — 全リポジトリの表を表示
;;   nbb scripts/itonami-fleet-audit.cljs --not-prod    — prod-ready? false の repo だけ表示
;;   nbb scripts/itonami-fleet-audit.cljs --ingest-gap  — real-world-ingest-gap? true の repo だけ表示
;;   nbb scripts/itonami-fleet-audit.cljs --edn         — tx-data 全件を EDN で標準出力へ
;;                                                        (リダイレクトで保存: ... > report.edn)
;;   毎回の実行で manifest/itonami-fleet-audit.edn を上書き生成する(下記 DataScript 例参照)。

(require '[scripts.nbb-compat :refer [slurp spit file-seq format relative-path]]
         '[clojure.edn :as edn]
         '[clojure.java.io :as io]
         '[clojure.java.shell :refer [sh]]
         '[clojure.set :as set]
         '[clojure.string :as str])

(def os (js/require "node:os"))
(def node-fs (js/require "node:fs"))

(def root (-> (sh "git" "rev-parse" "--show-toplevel") :out str/trim))
(def fleet-dir (io/file root "orgs" "cloud-itonami"))

(defn actor-dirs []
  (->> (.listFiles fleet-dir)
       (filter #(.isDirectory %))
       (filter #(str/starts-with? (.getName %) "cloud-itonami-"))
       (sort-by #(.getName %))))

(defn read-blueprint
  "blueprint.edn を読む。生の map / wrap-map! 済み tx-data([{...}])のどちらでも読める。"
  [dir]
  (let [f (io/file dir "blueprint.edn")]
    (when (.exists f)
      (try
        (let [content (edn/read-string (slurp f))]
          (cond
            (and (vector? content) (map? (first content))) (dissoc (first content) :db/id)
            (map? content) content
            :else nil))
        (catch :default _ nil)))))

(defn- read-industry-maturity-registry
  "kotoba-lang/industry's own registry.edn -- a plain (not pr-str-blob-
   encoded) top-level map, :industries a real EDN vector of {:id
   :maturity ...} per ISIC code, authoritative per ADR-2607121000.
   2026-07-21 finding: EVERY sampled maturity-unset isic repo (62/62) has a
   real entry here -- the gap this fn closes is a cross-reference gap, not
   a missing-data gap."
  []
  (let [f (io/file root "orgs" "kotoba-lang" "industry" "resources" "kotoba" "industry" "registry.edn")]
    (if (.exists f)
      (try
        (into {} (map (juxt :id :maturity)) (:industries (edn/read-string (slurp f))))
        (catch :default _ {}))
      {})))

(defn- read-occupation-maturity-registry
  "kotoba-lang/occupation's own registry.edn -- unlike industry's, the
   top-level is a vector-of-one-map (tx-data shape) whose
   :kotoba.occupation/occupations value is itself a pr-str-encoded EDN
   STRING (double-parse required; same nested-string-blob convention
   documented in this workspace's own docs/ADR edn tooling). 2026-07-21
   finding: 216/216 sampled maturity-unset isco repos have a real entry
   here too."
  []
  (let [f (io/file root "orgs" "kotoba-lang" "occupation" "resources" "kotoba" "occupation" "registry.edn")]
    (if (.exists f)
      (try
        (let [outer (edn/read-string (slurp f))
              occs-str (:kotoba.occupation/occupations (first outer))
              occs (edn/read-string occs-str)]
          (into {} (map (juxt :id :maturity)) occs))
        (catch :default _ {}))
      {})))

(def industry-maturity-registry (read-industry-maturity-registry))
(def occupation-maturity-registry (read-occupation-maturity-registry))

(defn canonical-maturity
  "Cross-references the canonical kotoba-lang/industry or
   kotoba-lang/occupation registry by numeric code, parsed from the repo's
   own real name -- nil (not a guess) when the repo isn't an isic/isco
   code or the code isn't in either registry."
  [repo-name]
  (or (when-let [code (second (re-find #"^cloud-itonami-isic-(\d+)" repo-name))]
        (get industry-maturity-registry code))
      (when-let [code (second (re-find #"^cloud-itonami-isco-(\d+)" repo-name))]
        (get occupation-maturity-registry code))))

(defn git-out [dir & args]
  (let [{:keys [exit out]} (apply sh "git" "-C" (.getPath dir) args)]
    (when (zero? exit) (not-empty (str/trim out)))))

(defn last-commit-at [dir] (git-out dir "log" "-1" "--format=%cI"))
(defn dirty? [dir] (boolean (git-out dir "status" "--porcelain")))
(defn repo-url [dir] (git-out dir "remote" "get-url" "origin"))
(defn unpushed-count [dir]
  (when-let [s (git-out dir "rev-list" "--count" "@{u}..HEAD")]
    (let [n (js/parseInt s 10)]
      (when-not (js/isNaN n) n))))

(defn days-since [iso-date-str]
  (when iso-date-str
    (let [then (.getTime (js/Date. iso-date-str))
          now (js/Date.now)]
      (.round js/Math (/ (- now then) 86400000)))))

(defn count-files [dir sub]
  (let [d (io/file dir sub)]
    (if (.exists d)
      (count (filter #(.isFile %) (file-seq d)))
      0)))

(defn find-facts-file [dir]
  (let [src (io/file dir "src")]
    (when (.exists src)
      (first (filter #(and (.isFile %) (= "facts.cljc" (.getName %))) (file-seq src))))))

(def jurisdiction-re #"\"[A-Z]{3}\"\s*\{")

(defn jurisdiction-coverage [dir]
  (when-let [f (find-facts-file dir)]
    (count (re-seq jurisdiction-re (slurp f)))))

(def url-re #"https?://")

(defn facts-citation-count
  "Real-world source citations inside facts.cljc specifically(not the whole
   src/ tree). Generalizes jurisdiction-coverage beyond the isic/isco
   per-country `\"XXX\" {` catalog shape -- e.g. cloud-itonami-municipality-*
   keys its catalog by municipality slug, not country code, so
   jurisdiction-coverage reads 0 there even though every ordinance entry
   cites a real :ordinance/url (verified 2026-07-21 against
   cloud-itonami-municipality-jpn-tokyo, which has 2 official
   reiki.metro.tokyo.lg.jp citations and 0 jurisdiction-re matches).
   Deliberately scoped to facts.cljc, NOT all of src/ -- src/registry.cljc
   commonly embeds a `https://www.w3.org/ns/credentials/v2` JSON-LD
   `@context` boilerplate (verifiable-credential issuance scaffolding, not
   domain data), which would contaminate a whole-src/ URL count into a false
   non-gap for repos with zero real facts."
  [dir]
  (when-let [f (find-facts-file dir)]
    (count (re-seq url-re (slurp f)))))

(def cost-threshold-re #":cost-threshold\b")

(defn internal-reference-facts-count
  "Count of `:cost-threshold` entries inside facts.cljc -- the isic-family
   internal governor/advisor procurement-reference-table shape (2026-07-23
   sampling: 24/71 isic zero-citation repos, e.g. cloud-itonami-isic-0111's
   `cerealops.facts`/cloud-itonami-isic-0112's `riceops.facts`: 'supply
   category cost policy ... the Governor and Advisor consult these instead
   of inventing thresholds', ADR-2607152500-confirmed intent). These
   repos were never designed to hold external citations at all -- a zero
   `facts-citation-count` there is not an unpopulated citation catalog, it
   is the CORRECT, complete state of a namespace whose only job is
   internal cost-threshold/classification lookup. Deliberately scoped to
   the literal `:cost-threshold` key (not a docstring-phrase match, which
   would be far less reliable across this fleet's varied wording) --
   nonzero exactly when this shape is present, so callers can use
   `(pos? ...)` as both detector and a meaningful strength count."
  [dir]
  (when-let [f (find-facts-file dir)]
    (count (re-seq cost-threshold-re (slurp f)))))

(defn declared-internal-only?
  "True when the repo's own blueprint.edn DECLARES that it holds no external
   regulatory citations by design, and names the document justifying it.

   Distinct from `internal-reference-facts-count`'s `:cost-threshold` shape:
   that one is INFERRED from a recognizable table, whereas this is an explicit
   self-declaration for actors whose design brief FORBIDS holding regulatory
   content at all. First case: cloud-itonami-isic-4912, whose ADR-0002 removed
   a fabricated jurisdiction catalog (real regulators' names, 49 C.F.R. /
   鉄道事業法 / ROGS 2006 / AEG citations, government URLs) because it is an
   internal operations-coordination actor -- `:spec-basis`/`:legal-basis` are
   operator-supplied per request. Reporting such a repo as a real-world-ingest
   gap points at a gap that must never be closed: closing it means
   re-committing the exact fabrication the ADR removed.

   Deliberately NOT a docstring-phrase match (see
   `internal-reference-facts-count`'s note on why that is unreliable across
   this fleet's wording) and deliberately NOT honoured without a basis: the
   `:itonami.blueprint/citation-policy-basis` key must be a non-blank string
   naming the decision record, so this cannot become a silent opt-out for a
   repo that simply has not done its ingest work yet."
  [bp]
  (and (= :internal-vocabulary-only (:itonami.blueprint/citation-policy bp))
       (let [basis (:itonami.blueprint/citation-policy-basis bp)]
         (and (string? basis) (not (str/blank? basis))))))

(def spec-basis-citation-re #":spec-basis\s+\"")

(defn isic-spec-basis-citation-count
  "Count of populated `:spec-basis \"...\"` entries inside facts.cljc -- real,
   named, citable regulatory/statute references (e.g.
   cloud-itonami-isic-1410's `apparel.facts`: 'Vietnam Labor Code 2019
   Article 93-94, ILO Conventions 98, 100, 111', cloud-itonami-isic-1512's
   `luggage.facts`: 'FTC Guides for Select Leather and Imitation Leather
   Products, 16 CFR Part 24', ADR-2612500000-confirmed real citations)
   that `facts-citation-count` misses because they cite a statute/CFR
   section/regulation name rather than an `https://` URL. 2026-07-23
   sampling: 7/71 isic zero-citation repos share this exact per-
   jurisdiction `catalog` + `:spec-basis` + honest-`coverage` template
   (cloud-itonami-isic-1410/1420/1430/1512/1910/3520/3530) -- this is a
   detector under-count, not a category mismatch: these repos already
   ARE genuinely-cited real-world-ingest, just formatted as prose law
   citations instead of a URL. Requires the key to be immediately
   followed by a quoted string (`:spec-basis \"...\"`), not merely the
   bare word `:spec-basis` appearing in a docstring's prose (e.g.
   cloud-itonami-isic-4912's `railfreight.facts` docstring mentions
   `:spec-basis` twice while explaining why it deliberately holds NO
   citation data at all -- this regex correctly reads 0 there since
   neither mention is followed by a quoted value)."
  [dir]
  (when-let [f (find-facts-file dir)]
    (count (re-seq spec-basis-citation-re (slurp f)))))

(def component-patterns
  {:governor #"governor\.cljc$"
   :store #"store\.cljc$"
   :phase #"phase\.cljc$"
   :sim #"sim\.cljc$"
   :registry #"registry\.cljc$"
   :operation #"operation\.cljc$"
   :facts #"facts\.cljc$"
   :advisor #"(llm|advisor)\.cljc$"})

(defn components [dir]
  (let [src (io/file dir "src")]
    (if (.exists src)
      (let [names (map #(.getName %) (filter #(.isFile %) (file-seq src)))]
        (into #{}
              (keep (fn [[k re]] (when (some #(re-find re %) names) k)))
              component-patterns))
      #{})))

(defn archive-file-count
  "Real, non-empty file count under `80-data/` -- the read-only-archive repo
   pattern (cloud-itonami-lei-*, ADR-2607110300/2607199960: 'archives
   publicly published legal/policy documents ... does not act, propose, or
   execute anything ... not a governed Advisor/Governor actor'). These repos
   are BY DESIGN not shaped like the src/-based actor repos `components`/
   `find-facts-file` above check for -- a src-file-count of 0 there does not
   mean empty, it means 'this is a different, real, non-actor repo kind.'
   2026-07-21 correction: 143/143 cloud-itonami-lei-* repos were previously
   flagged :stub by this script; a sample (cloud-itonami-lei-
   ilul7b6z54mrycf6h308) has a real 248-line 80-data/public/tos.journal.edn.
   Checks real file size via node:fs `statSync` (bound to `node-fs` below),
   NOT `.length` -- `scripts.nbb-compat`'s `io/file` shim object has no
   `.length` method (only `isFile`/`isDirectory`/`getPath`/`getName`/etc,
   see scripts/nbb_compat.cljs's own `file` fn), unlike real java.io.File.
   Excludes zero-byte placeholder files from counting as real content."
  [dir]
  (let [d (io/file dir "80-data")]
    (if (.exists d)
      (count (filter #(and (.isFile %) (pos? (.-size (.statSync node-fs (.getPath %)))))
                     (file-seq d)))
      0)))

(def actor-blueprint-file-names
  "The governed-actor blueprint component vocabulary cloud-itonami-isco-*
   repos are scaffolded with (ADR-2607012000: 'a sole-proprietor operator
   simulation -- an autonomous advisor proposing operations, gated by a
   governor, backed by a Store protocol'). Deliberately a separate,
   narrower vocabulary from `component-patterns` above (not folded into
   it) -- scoped only to the :actor-blueprint-structure real-world-ingest
   signal below (see `real-world-ingest`), so this addition can't perturb
   the generic :itonami.fleet-audit/components / :component-count fields
   used across every other family. 2026-07-21 finding across all 216
   cloud-itonami-isco-* repos: governor.cljc + store.cljc are universal
   (216/216, always substantive content -- min observed sizes 47/86 lines,
   never a stub) and always nested one level under src/ (e.g.
   src/officer_admin/governor.cljc, not directly in src/ -- basename match
   below handles this via file-seq regardless of depth). advisor.cljc /
   actor.cljc are real too but only 191/216 -- present together or absent
   together in every repo checked, so they count toward this signal's
   strength but are not required for the floor.

   2026-07-23 finding: cloud-itonami-gtin-issuance and
   cloud-itonami-gtin-verification (2 of the 3 cloud-itonami-gtin-* repos;
   the third, cloud-itonami-gtin-catalog, is an unbuilt stub with none of
   these files, so it is unaffected either way) match this exact 4-file
   vocabulary basename-for-basename (governor.cljc + store.cljc +
   advisor.cljc + actor.cljc, no registry.cljc, no facts.cljc) -- and their
   own `actor.cljc`/`governor.cljc` ns docstrings say so explicitly
   ('Modeled on cloud-itonami-isco-1324's supplydist.actor' /
   '...supplydist.governor'). This is not a coincidental naming overlap;
   the repos were built as instances of the same isco governed-actor
   blueprint pattern, and the one externally-verifiable rule their
   governor enforces (the GS1 GTIN Modulo-10 check-digit algorithm) is
   embedded as code in `governor.cljc` itself, not as a per-source URL
   citation catalog -- structurally the same 'not a facts.cljc-shaped
   repo' kind as isco, not an unmeasured gap. See `real-world-ingest` for
   why this stays gated to isco+gtin specifically and is not applied
   family-agnostically (cofog/unspsc/partners/hygiene-access all also
   have governor.cljc+store.cljc but are a DIFFERENT, mixed
   catalog+actor shape -- registry.cljc + phase.cljc + sim.cljc +
   operation.cljc alongside governor/store/advisor -- and are real,
   not-yet-matured gaps: cloud-itonami-unspsc-27/formation ships a real
   facts.cljc with 6 genuine https://www.osha.gov citations on that exact
   file shape, proving the mixed-pattern genre does eventually grow real
   citations, unlike isco/gtin's 4-file shape which never has)."
  #{"governor.cljc" "store.cljc" "advisor.cljc" "actor.cljc"})

(defn actor-blueprint-components
  "Which of the 4 actor-blueprint component files (see
   `actor-blueprint-file-names`) exist anywhere under src/, by basename,
   at any nesting depth."
  [dir]
  (let [src (io/file dir "src")]
    (if (.exists src)
      (let [names (map #(.getName %) (filter #(.isFile %) (file-seq src)))]
        (into #{} (filter actor-blueprint-file-names) names))
      #{})))

(defn actor-blueprint-structure?
  "true when both governor.cljc and store.cljc are present -- the minimal
   floor empirically true for 216/216 cloud-itonami-isco-* repos (see
   `actor-blueprint-file-names` docstring; advisor.cljc/actor.cljc are
   real but only 191/216 so aren't required for the floor). Also the
   floor for cloud-itonami-gtin-issuance/-verification (2026-07-23, see
   `actor-blueprint-file-names`)."
  [blueprint-components]
  (and (contains? blueprint-components "governor.cljc")
       (contains? blueprint-components "store.cljc")))

(defn plain-library-structure?
  "true when the repo has real (non-stub) src/ content but NONE of the
   governed-actor pattern's own component files (`component-patterns`'s
   :governor / :advisor / :store) appear anywhere under src/ -- i.e.
   structurally a plain, domain-agnostic .cljc library, not a governed
   actor at all (no advisor, no governor, no StateGraph, no audit
   ledger). 2026-07-23 finding: cloud-itonami-regulatory-tracker (the
   sole cloud-itonami-regulatory-* repo) is exactly this shape --
   src/cloud_itonami/regulatory_tracker/core.cljc is a single pure
   ordered-stage/exit-stage transition-validation namespace built on
   `kotoba.crm.pipeline`, whose own ns docstring and README say in so
   many words: 'This is a PLAIN FUNCTION LIBRARY with NO independent
   decision authority of its own -- not a governor, not an advisor, not
   a StateGraph, no ledger' / 'This is a plain library, not a governed
   actor'. It deliberately keeps its two domain fields
   (:subject-id/:regulatory-track) opaque and caller-defined -- it never
   inspects, validates, or cites real per-jurisdiction regulatory content
   itself (that citation-bearing content lives in each CALLING actor's
   own domain, e.g. cloud-itonami-hygiene-access's docs/regulatory/*.md
   dossiers). A shared, domain-agnostic technical-commons library by
   design has no real-world citation content of its own to ingest -- a
   category-mismatch akin to isco/gtin's, not an unmeasured gap. See
   `real-world-ingest` for the family gate."
  [comps src-count]
  (and (pos? src-count)
       (empty? (set/intersection comps #{:governor :advisor :store}))))

(defn actor-status [{:keys [src-file-count archive-file-count last-commit-at days-since-commit]}]
  (cond
    (and (zero? src-file-count) (pos? archive-file-count)) :archive
    (zero? src-file-count) :stub
    (nil? last-commit-at) :no-commits
    (> days-since-commit 180) :stale
    (> days-since-commit 30) :dormant
    :else :active))

(def known-families
  "cloud-itonami-<family>-<rest> の <family> 語彙(実測: isic 429 / isco 216 /
   lei 143 / assoc 78 / municipality 49 / unspsc 5 / cofog 5 / gtin 3 /
   regulatory 1, 2026-07-21 時点)。未知語は fallback で repo 名の残り全体を
   family として使う(cloud-itonami-partners のような単発 repo 用)。"
  #{"isic" "isco" "lei" "assoc" "municipality" "iso3166" "unspsc" "cofog" "gtin" "regulatory"})

(defn family-of [repo-name]
  (let [rest-name (subs repo-name (count "cloud-itonami-"))
        seg (first (str/split rest-name #"-"))]
    (keyword (if (contains? known-families seg) seg rest-name))))

(defn prod-ready? [status]
  (contains? #{:active :archive} status))

(def actor-blueprint-families
  "Families whose governed-actor blueprint component-structure
   (governor.cljc + store.cljc, see `actor-blueprint-structure?`) counts
   as the :actor-blueprint-structure real-world-ingest signal rather than
   an unmeasured gap. isco (ADR-2607012000) is the original, verified
   member; gtin was added 2026-07-23 after confirming
   cloud-itonami-gtin-issuance/-verification are literal structural
   clones of the isco pattern (4-file vocabulary, own code docstrings say
   'Modeled on cloud-itonami-isco-1324'). See `actor-blueprint-file-names`
   and `real-world-ingest` for the full evidence and for why this set is
   NOT widened to cofog/unspsc/partners/hygiene-access, which share
   governor.cljc+store.cljc but are a different, mixed catalog+actor
   shape with real (if not-yet-matured) facts.cljc potential."
  #{:isco :gtin})

(defn real-world-ingest
  "Precedence: real facts.cljc citations first (strongest evidence, wins
   even for isco/gtin family repos that happen to have one); then, ONLY
   for isic-family facts.cljc files with zero URL citations, two narrower
   isic-specific reclassifications (spec-basis prose citations counted as
   real citations, then the internal-reference-table shape recognized as
   by-design non-citation-bearing); then archive content; then (isco/gtin
   families only) the governed-actor blueprint component-structure
   signal; then (regulatory family only) the plain domain-agnostic
   library-structure signal; else none-measured.

   The :actor-blueprint-structure branch is deliberately gated on
   `actor-blueprint-families` (isco + gtin) and NOT applied
   family-agnostically: 2026-07-21 measurement shows 164/429
   cloud-itonami-isic-* repos also have both governor.cljc + store.cljc
   under src/ without a facts.cljc (isic's governor/store naming is not
   evidence of the same isco governed-actor blueprint pattern per
   ADR-2607012000 -- it would be a scope-widening false fix, not the same
   correction, to let those flip signal here). 2026-07-23: cofog/unspsc/
   partners/hygiene-access were independently investigated and also NOT
   added here -- they have the SAME governor.cljc+store.cljc floor, but
   additionally carry registry.cljc/phase.cljc/sim.cljc/operation.cljc (a
   different, mixed catalog+actor shape, structurally parallel to isic,
   not to isco/gtin's narrower 4-file shape) and cloud-itonami-unspsc-27
   (identical mixed shape, sibling segment in the same unspsc family)
   ships a real facts.cljc with 6 genuine https://www.osha.gov citations
   -- proof the mixed-pattern genre does reach real citations once
   matured, so the other mixed-shape repos lacking one yet are a real,
   not-yet-measured gap, not a category mismatch. Ungating
   :actor-blueprint-structure beyond isco+gtin, or applying it to any of
   these four families, would silently change their real-world-ingest-
   signal distribution, which this fix must not do -- least of all
   isic's, whose own OWN internal-reference/spec-basis carve-outs are the
   two isic-only branches immediately below.

   The :plain-library-structure branch is gated on `(= family
   :regulatory)`: cloud-itonami-regulatory-tracker (the sole
   cloud-itonami-regulatory-* repo) is a plain, non-actor .cljc shared
   library by its own explicit self-description (see
   `plain-library-structure` docstring) -- a category mismatch, not an
   unmeasured gap.

   The two isic-only branches below are similarly narrowly gated (both on
   `(= family :isic)` AND on the existing url `citation-count` already
   being zero, so they can never override or double-count a repo that
   already has real `https://` citations):

   - `isic-spec-basis-count` (see `isic-spec-basis-citation-count`)
     recognizes real, populated `:spec-basis \"...\"` law/regulation
     citations that `facts-citation-count`'s URL-only regex misses --
     genuinely NOT a gap, just under-counted. Folded into the SAME
     `:facts-citations` signal (not a new signal name) because
     semantically it is exactly that: a real-world citation found inside
     facts.cljc, just not URL-shaped.
   - `isic-internal-reference?` (see `internal-reference-facts-count`)
     recognizes the isic-family internal governor/advisor cost-threshold
     reference-table shape, which was never designed to hold external
     citations at all (the isic-family analogue of isco's
     :actor-blueprint-structure carve-out, ADR-2607152500-confirmed for
     at least one sampled repo) -- surfaced as its own
     `:internal-reference-facts` signal so it stays visibly distinct from
     genuine citation coverage in `:by-ingest-signal` summaries.

   2026-07-23 sampling (dozens of isic zero-citation repos read directly,
   across 01xx/07xx/10xx-14xx/18xx/19xx/33xx/35xx/49xx/58xx/62xx/79x/80xx/
   82xx code ranges): of the 71 isic repos with a facts.cljc but zero URL
   citations, 24 match the internal-reference shape and 7 match the
   spec-basis shape -- the remaining 40 (plus all 181 isic repos with no
   facts.cljc at all) are a genuinely heterogeneous mix (jurisdiction-
   keyed operational thresholds with no citable source at all, e.g.
   cloud-itonami-isic-1010's `meatprocessing.facts`/cloud-itonami-
   isic-1101's `distilling.facts`; bare record-verification predicates,
   e.g. cloud-itonami-isic-791's `travelagencyops.facts`; internal
   governance-tier catalogs that reuse citation-catalog docstring
   phrasing without citing an external source, e.g. cloud-itonami-
   isic-5820's `crm.facts`) with no single clean, defensible, content-
   based signal found to separate a real backlog from a category error --
   deliberately left flagged as a gap rather than forcing a broader
   reclassification the sampled content doesn't support."
  [{:keys [has-facts-file? citation-count archive-count family blueprint-components
           isic-spec-basis-count isic-internal-reference-count comps src-count
           declared-internal-only?]}]
  (cond
    ;; A real citation count still wins: a repo that declares the policy but
    ;; nevertheless carries citations is reported on its citations, so the
    ;; declaration can never mask real content.
    (and has-facts-file? (pos? citation-count)) {:signal :facts-citations :count citation-count}

    ;; Explicit, justified self-declaration that citations are forbidden here.
    ;; `count` is `src-count` (real on-disk content), NOT 1 and NOT a component
    ;; count -- same reasoning as `:plain-library-structure` below: a zero count
    ;; would let `real-world-ingest-gap?`'s `(zero? ingest-count)` clause
    ;; re-flag the repo despite the signal no longer being :none-measured.
    declared-internal-only?
    {:signal :declared-internal-only :count (max src-count 1)}

    (and has-facts-file? (= family :isic) (zero? citation-count) (pos? isic-spec-basis-count))
    {:signal :facts-citations :count isic-spec-basis-count}

    (and has-facts-file? (= family :isic) (zero? citation-count) (pos? isic-internal-reference-count))
    {:signal :internal-reference-facts :count isic-internal-reference-count}

    has-facts-file? {:signal :facts-citations :count citation-count}
    (pos? archive-count) {:signal :archive-content :count archive-count}
    (and (contains? actor-blueprint-families family) (actor-blueprint-structure? blueprint-components))
    {:signal :actor-blueprint-structure :count (count blueprint-components)}
    (and (= family :regulatory) (plain-library-structure? comps src-count))
    ;; count is deliberately `src-count`, NOT `(count comps)` -- `comps`
    ;; is EMPTY by construction for this signal (`plain-library-structure?`
    ;; requires no governor/advisor/store present), so counting it would
    ;; make `:itonami.fleet-audit/real-world-ingest-gap?`'s `(zero?
    ;; ingest-count)` clause re-flag this as a gap despite the signal no
    ;; longer being :none-measured. `src-count` is the real, non-zero
    ;; measure of on-disk content this signal is actually attesting to.
    {:signal :plain-library-structure :count src-count}
    :else {:signal :none-measured :count 0}))

(defn actor-facts [dir]
  (let [name (.getName dir)
        family (family-of name)
        bp (read-blueprint dir)
        commit-at (last-commit-at dir)
        days (or (days-since commit-at) -1)
        src-count (count-files dir "src")
        archive-count (archive-file-count dir)
        comps (components dir)
        blueprint-comps (actor-blueprint-components dir)
        has-facts? (boolean (find-facts-file dir))
        juri-count (or (jurisdiction-coverage dir) 0)
        citation-count (or (facts-citation-count dir) 0)
        isic-spec-basis-count (if (= family :isic) (or (isic-spec-basis-citation-count dir) 0) 0)
        isic-internal-reference-count (if (= family :isic) (or (internal-reference-facts-count dir) 0) 0)
        own-maturity (:itonami.blueprint/maturity bp)
        registry-maturity (when-not own-maturity (canonical-maturity name))
        maturity (cond
                   own-maturity own-maturity
                   registry-maturity registry-maturity
                   (nil? bp) :no-blueprint
                   :else :maturity-unset)
        maturity-source (cond
                          own-maturity :own-blueprint
                          registry-maturity :canonical-registry
                          :else :unknown)
        status (actor-status {:src-file-count src-count
                              :archive-file-count archive-count
                              :last-commit-at commit-at
                              :days-since-commit days})
        ingest (real-world-ingest {:has-facts-file? has-facts?
                                   :citation-count citation-count
                                   :archive-count archive-count
                                   :family family
                                   :blueprint-components blueprint-comps
                                   :isic-spec-basis-count isic-spec-basis-count
                                   :isic-internal-reference-count isic-internal-reference-count
                                   :comps comps
                                   :src-count src-count
                                   :declared-internal-only? (declared-internal-only? bp)})
        ingest-signal (:signal ingest)
        ingest-count (:count ingest)]
    {:itonami.fleet-audit/repo name
     :itonami.fleet-audit/family family
     :itonami.fleet-audit/isic (or (:itonami.blueprint/isic-rev5 bp) "")
     :itonami.fleet-audit/domain (or (:itonami.blueprint/domain bp) :unknown)
     :itonami.fleet-audit/governor (or (:itonami.blueprint/governor bp) :none)
     :itonami.fleet-audit/maturity maturity
     :itonami.fleet-audit/maturity-source maturity-source
     :itonami.fleet-audit/repo-url (or (repo-url dir) "")
     :itonami.fleet-audit/last-commit-at (or commit-at "")
     :itonami.fleet-audit/days-since-commit days
     :itonami.fleet-audit/dirty? (dirty? dir)
     :itonami.fleet-audit/unpushed-commit-count (or (unpushed-count dir) 0)
     :itonami.fleet-audit/src-file-count src-count
     :itonami.fleet-audit/archive-file-count archive-count
     :itonami.fleet-audit/test-file-count (count-files dir "test")
     :itonami.fleet-audit/jurisdiction-coverage-count juri-count
     :itonami.fleet-audit/component-count (count comps)
     :itonami.fleet-audit/components (vec (sort comps))
     :itonami.fleet-audit/status status
     :itonami.fleet-audit/prod-ready? (prod-ready? status)
     :itonami.fleet-audit/real-world-ingest-signal ingest-signal
     :itonami.fleet-audit/real-world-ingest-count ingest-count
     :itonami.fleet-audit/real-world-ingest-gap? (or (= ingest-signal :none-measured) (zero? ingest-count))}))

(defn build-entities []
  (mapv actor-facts (actor-dirs)))

(defn to-tx-data!
  "entities を manifest/edn-datomize.cljs の tx-entities モード経由で Datomic/
   Datascript tx-data に変換し、manifest/schema.edn へスキーマを登録する
   (OS tmpdir を経由し、一時ファイルは処理後に削除する)。"
  [entities]
  (let [tmp-in (io/file (.tmpdir os) "itonami-fleet-audit-staging.edn")
        tmp-out (io/file (.tmpdir os) "itonami-fleet-audit-txdata.edn")
        _ (spit tmp-in (pr-str entities))
        {:keys [exit err]} (sh "nbb" (str root "/manifest/edn-datomize.cljs") "tx-entities"
                                (.getPath tmp-in) (.getPath tmp-out))]
    (when-not (zero? exit) (println "WARN: tx-entities failed:" err))
    (let [tx (when (.exists tmp-out) (edn/read-string (slurp tmp-out)))]
      (try (.unlinkSync node-fs (.getPath tmp-in)) (catch :default _ nil))
      (try (.unlinkSync node-fs (.getPath tmp-out)) (catch :default _ nil))
      tx)))

(defn summary [entities]
  {:total (count entities)
   :by-family (into (sorted-map) (frequencies (map :itonami.fleet-audit/family entities)))
   :by-maturity (into (sorted-map) (frequencies (map :itonami.fleet-audit/maturity entities)))
   :by-maturity-source (into (sorted-map) (frequencies (map :itonami.fleet-audit/maturity-source entities)))
   :by-status (into (sorted-map) (frequencies (map :itonami.fleet-audit/status entities)))
   :dirty (count (filter :itonami.fleet-audit/dirty? entities))
   :with-unpushed-commits (count (filter #(pos? (:itonami.fleet-audit/unpushed-commit-count %)) entities))
   :with-jurisdiction-coverage (count (filter #(pos? (:itonami.fleet-audit/jurisdiction-coverage-count %)) entities))
   :total-jurisdiction-coverage (reduce + (map :itonami.fleet-audit/jurisdiction-coverage-count entities))
   :not-prod-ready (count (remove :itonami.fleet-audit/prod-ready? entities))
   :real-world-ingest-gap (count (filter :itonami.fleet-audit/real-world-ingest-gap? entities))
   ;; Surfaced as its own headline number, not only inside :by-ingest-signal:
   ;; repos excluded from the gap count by their OWN declaration must stay
   ;; conspicuous, so a growing figure here is visible as something to audit
   ;; rather than quietly shrinking :real-world-ingest-gap.
   :declared-internal-only (count (filter #(= :declared-internal-only
                                             (:itonami.fleet-audit/real-world-ingest-signal %))
                                          entities))
   :by-ingest-signal (into (sorted-map) (frequencies (map :itonami.fleet-audit/real-world-ingest-signal entities)))})

(defn needs-attention [entities]
  (filter (fn [e] (or (= :stale (:itonami.fleet-audit/status e))
                      (:itonami.fleet-audit/dirty? e)
                      (pos? (:itonami.fleet-audit/unpushed-commit-count e))))
          entities))

(defn not-prod-ready [entities]
  (remove :itonami.fleet-audit/prod-ready? entities))

(defn ingest-gap [entities]
  (filter :itonami.fleet-audit/real-world-ingest-gap? entities))

(defn print-row [{:itonami.fleet-audit/keys [repo family maturity status days-since-commit
                                              src-file-count test-file-count
                                              jurisdiction-coverage-count
                                              prod-ready? real-world-ingest-gap?
                                              dirty? unpushed-commit-count]}]
  (println (format "%-38s %-13s %-12s %-11s %5s %4s %4s %4s %-5s %-5s %-5s %s"
                    repo (name family) (name maturity) (name status)
                    (if (neg? days-since-commit) "n/a" (str days-since-commit "d"))
                    src-file-count test-file-count jurisdiction-coverage-count
                    (if prod-ready? "-" "NOT-PROD")
                    (if real-world-ingest-gap? "GAP" "-")
                    (if dirty? "dirty" "-")
                    (if (pos? unpushed-commit-count) (str unpushed-commit-count " unpushed") ""))))

(defn print-header []
  (println (format "%-38s %-13s %-12s %-11s %5s %4s %4s %4s %-5s %-5s %-5s %s"
                    "repo" "family" "maturity" "status" "days" "src" "test" "juri"
                    "prod" "ingest" "" ""))
  (println (str/join (repeat 135 "-"))))

(def edn-header
  (str ";; manifest/itonami-fleet-audit.edn — generated by scripts/itonami-fleet-audit.cljs.\n"
       ";; DO NOT EDIT BY HAND. Regenerate: nbb scripts/itonami-fleet-audit.cljs\n"
       ";;\n"
       ";; A vector of DataScript/Datomic-transactable entity-maps, one per\n"
       ";; orgs/cloud-itonami/cloud-itonami-* repo. Each map is directly usable as a\n"
       ";; tx-data entry (no :db/id — DataScript assigns a tempid per map on transact):\n"
       ";;\n"
       ";;   (require '[datascript.core :as d])\n"
       ";;   (def conn (d/create-conn {}))\n"
       ";;   (d/transact! conn (clojure.edn/read-string (slurp \"manifest/itonami-fleet-audit.edn\")))\n"
       ";;\n"
       ";;   ;; repos that have not reached prod maturity yet:\n"
       ";;   (d/q '[:find ?repo ?status :where\n"
       ";;          [?e :itonami.fleet-audit/repo ?repo]\n"
       ";;          [?e :itonami.fleet-audit/status ?status]\n"
       ";;          [?e :itonami.fleet-audit/prod-ready? false]] @conn)\n"
       ";;\n"
       ";;   ;; repos where real-world information ingestion is incomplete:\n"
       ";;   (d/q '[:find ?repo ?signal ?count :where\n"
       ";;          [?e :itonami.fleet-audit/repo ?repo]\n"
       ";;          [?e :itonami.fleet-audit/real-world-ingest-signal ?signal]\n"
       ";;          [?e :itonami.fleet-audit/real-world-ingest-count ?count]\n"
       ";;          [?e :itonami.fleet-audit/real-world-ingest-gap? true]] @conn)\n"
       ";;\n"
       ";; Fields:\n"
       ";;   :itonami.fleet-audit/family              parsed from repo name (isic/isco/lei/\n"
       ";;                                             assoc/municipality/iso3166/unspsc/cofog/\n"
       ";;                                             gtin/regulatory/other).\n"
       ";;   :itonami.fleet-audit/status               :stub / :no-commits / :stale (>180d) /\n"
       ";;                                             :dormant (30-180d) / :active / :archive\n"
       ";;                                             (read-only 80-data/ archive, e.g. lei ToS).\n"
       ";;   :itonami.fleet-audit/prod-ready?          true only for :active / :archive status —\n"
       ";;                                             the fleet's only two \"doing its job\" states.\n"
       ";;   :itonami.fleet-audit/real-world-ingest-signal  which real-world data channel was\n"
       ";;                                             measured: :facts-citations (real https:// URL\n"
       ";;                                             citations inside facts.cljc — generalizes past\n"
       ";;                                             the isic/isco per-country catalog shape to\n"
       ";;                                             municipality's per-ordinance shape etc.; for\n"
       ";;                                             isic family specifically also folds in real\n"
       ";;                                             populated `:spec-basis \"...\"` law/regulation\n"
       ";;                                             citations that cite a statute/CFR section rather\n"
       ";;                                             than a URL, see `isic-spec-basis-citation-count`) /\n"
       ";;                                             :archive-content (80-data/ real files) /\n"
       ";;                                             :actor-blueprint-structure (isco/gtin families\n"
       ";;                                             only: governor.cljc + store.cljc governed-actor\n"
       ";;                                             blueprint components per ADR-2607012000 — these\n"
       ";;                                             repos are a structurally different, by-design\n"
       ";;                                             non-facts.cljc repo kind, not an unmeasured gap;\n"
       ";;                                             see `real-world-ingest` for why this is gated to\n"
       ";;                                             isco+gtin and not applied to cofog/unspsc/\n"
       ";;                                             partners/hygiene-access, a different mixed\n"
       ";;                                             catalog+actor shape with real, not-yet-matured\n"
       ";;                                             facts.cljc potential — see cloud-itonami-unspsc-27) /\n"
       ";;                                             :internal-reference-facts (isic family only:\n"
       ";;                                             facts.cljc exists but is a `:cost-threshold`\n"
       ";;                                             internal governor/advisor reference table, never\n"
       ";;                                             designed to hold external citations — see\n"
       ";;                                             `internal-reference-facts-count`) /\n"
       ";;                                             :plain-library-structure (regulatory family only:\n"
       ";;                                             cloud-itonami-regulatory-tracker, a plain\n"
       ";;                                             domain-agnostic .cljc library with no advisor/\n"
       ";;                                             governor/store of its own — a category mismatch,\n"
       ";;                                             not an unmeasured gap) /\n"
       ";;                                             :declared-internal-only (the repo itself\n"
       ";;                                             declares :itonami.blueprint/citation-policy\n"
       ";;                                             :internal-vocabulary-only with a mandatory\n"
       ";;                                             :citation-policy-basis — an actor whose design\n"
       ";;                                             forbids holding citations, so the gap must\n"
       ";;                                             never be closed) /\n"
       ";;                                             :none-measured (no channel present at all).\n"
       ";;   :itonami.fleet-audit/real-world-ingest-gap?  true when the measured count is 0 OR the\n"
       ";;                                             signal is :none-measured — an entity with no\n"
       ";;                                             measurement is a coverage gap, not \"n/a\"\n"
       ";;                                             (ADR-2607203000 principle).\n"
       ";;\n"
       ";; Generated: " (.toISOString (js/Date.)) "\n\n"))

(defn write-edn! [entities out-path]
  (spit (io/file root out-path)
        (str edn-header "[\n" (str/join "\n" (map #(str " " (pr-str %)) entities)) "\n]\n")))

(let [entities (build-entities)
      args (set *command-line-args*)
      edn? (contains? args "--edn")
      all? (contains? args "--all")
      not-prod? (contains? args "--not-prod")
      ingest-gap? (contains? args "--ingest-gap")]
  ;; schema 登録 + manifest/itonami-fleet-audit.edn への保存は毎回の副作用として
  ;; 常に行う(--edn の有無に関わらず。kotoba-boundary-audit.cljs 同様「標準出力に
  ;; 出すかは呼び出し側が選ぶ」流儀を保ちつつ、永続化とschema蓄積だけは常時行う)。
  (to-tx-data! entities)
  (write-edn! entities "manifest/itonami-fleet-audit.edn")
  (if edn?
    (prn entities)
    (do
      (println "cloud-itonami fleet audit --" (count entities) "repos under" (relative-path root fleet-dir))
      (println "summary:" (pr-str (summary entities)))
      (println "wrote manifest/itonami-fleet-audit.edn")
      (println)
      (cond
        all? (do (print-header) (doseq [e entities] (print-row e)))

        not-prod? (let [rows (vec (not-prod-ready entities))]
                    (println (count rows) "repos not yet prod-ready:")
                    (println)
                    (print-header)
                    (doseq [e rows] (print-row e)))

        ingest-gap? (let [rows (vec (ingest-gap entities))]
                      (println (count rows) "repos with a real-world-ingest gap:")
                      (println)
                      (print-header)
                      (doseq [e rows] (print-row e)))

        :else
        (let [attn (vec (needs-attention entities))]
          (println (count attn) "repos need attention (stale / dirty / unpushed commits):")
          (println)
          (print-header)
          (doseq [e (take 200 attn)] (print-row e))
          (when (> (count attn) 200)
            (println (format "... %d more. Use --all for the full fleet, --edn for full tx-data."
                              (- (count attn) 200))))
          (when (zero? (count attn))
            (println "(none — use --all to see the full fleet table.)")))))))
