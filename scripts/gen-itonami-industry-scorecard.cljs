#!/usr/bin/env nbb
;; gen-itonami-industry-scorecard.cljs — 産業（ISIC）単位に畳んだ成熟度・
;; coverage の生成物。ADR-2608052000 が repo 単位で測ったものを、公開面が
;; 出せる粒度（ISIC section → division → class）に折り畳む。
;;
;; ## なぜ要るか
;;
;; 2026-08-07 の実測: 1,808 repo × 7 軸のスコアは
;; `90-docs/system-dynamics/itonami-maturity.datoms.edn` に在るのに、
;; **consumer が scripts/ だけで UI が 1 つも無い**。itonami.cloud の公開面
;; （トップの集計と /marketplace/）は「実装が在るか」しか出しておらず、
;; 「どの産業がどれだけ出来ているか」は誰も見られなかった。欠けていたのは
;; 計測ではなく、産業軸への畳み込みと表示。
;;
;; ## 入力（すべて既存の生成物 / 正本。ここでは何も測り直さない）
;;
;;   90-docs/system-dynamics/itonami-maturity.datoms.edn
;;     repo 単位の 7 軸スコア + leverage（ADR-2608052000）。算術の正本は
;;     90-docs/system-dynamics/kotoba/itonami_maturity_kernel.kotoba。
;;   orgs/kotoba-lang/industry/resources/kotoba/industry/registry.edn
;;     どの ISIC コードに事業が登録されているか（651 件）。/marketplace/ と
;;     同じ SSoT を読む —— 公開面ごとに別の数を出さないため。
;;   orgs/cloud-itonami/org-un-isic/data/classes/*.json
;;     UN ISIC **Rev.4** の 428 class。coverage の分母はここだけから採る。
;;   orgs/etzhayyim/com-etzhayyim-app-open-kyber/industry-packs/isic-packs.kotoba.edn
;;     section A–U の名称（`:isic.pack/scope :section` の 21 件）。
;;
;; ## 捏造しないために決めたこと
;;
;;   - **division → section の対応は自分で書かない。** org-un-isic の
;;     `kotoba/src/types.ts` の `sectionForDivision`（ISIC Rev.4 の境界を
;;     テストで固定してある）をそのまま移した。数値は下の `section-of-division`
;;     に、出典は `:summary/section-authority` に記録する。
;;   - **registry は自分の改訂番号を宣言していない**（実測: registry.edn に
;;     Rev.4 とも Rev.5 とも書いていない。「Rev.5」は generate-marketplace.cljs
;;     の :label とサイト本文にしか無い）。だから coverage の分母は Rev.4 と
;;     **明示して**出し、両者が一致する保証は主張しない。一致しなかった
;;     id は `:summary/off-classification` として件数と実例を残す。
;;   - **分母に入らないものを分子に入れない。** registry には 3 桁の group
;;     entry（218 件）と接尾辞付き（3 件）が混ざっている。official coverage の
;;     分子は「公式 class 表に実在する 4 桁 id」だけに絞り、それ以外は
;;     `:section/registered-other` に分けて数える。
;;   - **測っていないものは 0 でなく nil。** maturity datom を持たない登録は
;;     平均の分母から外し、`:section/unmeasured` に件数を出す（ADR-2607203000）。
;;
;; 使い方:
;;   nbb scripts/gen-itonami-industry-scorecard.cljs           ; 生成して書く
;;   nbb scripts/gen-itonami-industry-scorecard.cljs --check   ; 差分があれば exit 1
;;   nbb scripts/gen-itonami-industry-scorecard.cljs --out PATH

(ns gen-itonami-industry-scorecard
  (:require [clojure.edn :as edn]
            [clojure.string :as str]))

(def fs (js/require "fs"))
(def nodepath (js/require "path"))

(def argv (vec (drop 2 (js->clj js/process.argv))))
(def check? (some #{"--check"} argv))
(def out-path
  (or (second (drop-while #(not= "--out" %) argv))
      "90-docs/system-dynamics/itonami-industry-scorecard.edn"))

(def maturity-path "90-docs/system-dynamics/itonami-maturity.datoms.edn")
(def registry-path "orgs/kotoba-lang/industry/resources/kotoba/industry/registry.edn")
(def classes-dir "orgs/cloud-itonami/org-un-isic/data/classes")
(def packs-path "orgs/etzhayyim/com-etzhayyim-app-open-kyber/industry-packs/isic-packs.kotoba.edn")
(def section-authority
  "orgs/cloud-itonami/org-un-isic/kotoba/src/types.ts sectionForDivision (ISIC Rev.4)")

(defn- slurp* [p]
  (when-not (fs.existsSync p)
    (throw (ex-info (str "入力が見つかりません: " p
                         " — checkout されていない可能性があります"
                         " (west update --fetch smart で取得する)")
                    {:path p})))
  (fs.readFileSync p "utf8"))

;; ---------------------------------------------------------------------------
;; division → section。org-un-isic の types.ts と同じ境界（Rev.4、A–U）。
;; 自分の記憶から書かない — 向こうはテストで境界を固定してある。
;; ---------------------------------------------------------------------------

(defn section-of-division
  "2 桁 division → section 1 文字。ISIC に存在しない division（34/40/44/48/
  54/57/67/76/83/89 等）は nil を返す —— types.ts は `<=` の連鎖なので隣の
  section に吸わせてしまうが、そこは『存在しない』が正しい。"
  [division]
  (let [d (js/parseInt division 10)]
    (cond
      (or (js/isNaN d) (< d 1) (> d 99)) nil
      (<= d 3) "A"
      (<= d 9) "B"
      (<= d 33) "C"
      (= d 35) "D"
      (and (>= d 36) (<= d 39)) "E"
      (and (>= d 41) (<= d 43)) "F"
      (and (>= d 45) (<= d 47)) "G"
      (and (>= d 49) (<= d 53)) "H"
      (and (>= d 55) (<= d 56)) "I"
      (and (>= d 58) (<= d 63)) "J"
      (and (>= d 64) (<= d 66)) "K"
      (= d 68) "L"
      (and (>= d 69) (<= d 75)) "M"
      (and (>= d 77) (<= d 82)) "N"
      (= d 84) "O"
      (= d 85) "P"
      (and (>= d 86) (<= d 88)) "Q"
      (and (>= d 90) (<= d 93)) "R"
      (and (>= d 94) (<= d 96)) "S"
      (and (>= d 97) (<= d 98)) "T"
      (= d 99) "U"
      :else nil)))

(def section-order ["A" "B" "C" "D" "E" "F" "G" "H" "I" "J" "K"
                    "L" "M" "N" "O" "P" "Q" "R" "S" "T" "U"])

(def section-divisions
  "表示用の division 範囲。上の分岐と同じ値をここでもう一度書いているので、
  食い違わないよう `verify-ranges!` が起動時に突き合わせる。"
  {"A" "01-03" "B" "05-09" "C" "10-33" "D" "35" "E" "36-39"
   "F" "41-43" "G" "45-47" "H" "49-53" "I" "55-56" "J" "58-63"
   "K" "64-66" "L" "68" "M" "69-75" "N" "77-82" "O" "84"
   "P" "85" "Q" "86-88" "R" "90-93" "S" "94-96" "T" "97-98" "U" "99"})

(defn- verify-ranges!
  "`section-divisions` の各範囲が本当に `section-of-division` と一致するか。
  表示用の文字列が分岐からずれると、UI だけが静かに嘘をつく。"
  []
  (doseq [[sec range-str] section-divisions]
    (let [[lo hi] (str/split range-str #"-")
          lo (js/parseInt lo 10)
          hi (js/parseInt (or hi (str lo)) 10)]
      (doseq [d (range lo (inc hi))]
        (let [dd (if (< d 10) (str "0" d) (str d))
              got (section-of-division dd)]
          (when (and got (not= got sec))
            (throw (ex-info (str "section-divisions が section-of-division と矛盾: "
                                 dd " -> " got " but declared " sec)
                            {:division dd}))))))))

;; ---------------------------------------------------------------------------
;; 入力
;; ---------------------------------------------------------------------------

(defn- read-maturity
  "repo 名 -> maturity entity。ADR-2608052000 の生成物をそのまま読む。"
  []
  (let [xs (edn/read-string (slurp* maturity-path))
        repos (filter :repo/name xs)
        summary (first (filter #(= "fleet-summary" (:summary/kind %)) xs))]
    {:by-repo (into {} (map (juxt :repo/name identity)) repos)
     :scan-at (:scan/at summary)
     :fleet-mean-own (:summary/mean-own summary)
     :fleet-mean-effective (:summary/mean-effective summary)
     :scored-repos (:summary/scored-repos summary)}))

(defn- entry? [e] (and (map? e) (:id e) (:maturity e)))

(defn- read-registry
  "registry.edn の :industries。generate-marketplace.cljs と同じ形の入力だが、
  こちらは **:implemented で絞らない** —— 登録されているのに実装が無い
  （`:spec`）ことこそ gap なので、絞ると gap が消える。"
  []
  (let [reg (edn/read-string (slurp* registry-path))
        xs (:industries reg)]
    (when-not (seq xs)
      (throw (ex-info "registry.edn に :industries が無い" {})))
    {:entries (vec (filter entry? xs))
     :updated (:kotoba.registry/updated reg)}))

(defn- read-official-classes
  "UN ISIC Rev.4 の class。coverage の分母はここだけ。"
  []
  (when-not (fs.existsSync classes-dir)
    (throw (ex-info (str "class ディレクトリが無い: " classes-dir) {})))
  (into {}
        (keep (fn [f]
                (when (str/ends-with? f ".json")
                  (let [j (js->clj (js/JSON.parse
                                    (slurp* (nodepath.join classes-dir f)))
                                   :keywordize-keys true)]
                    (when (:code j) [(:code j) (:nameEn j)])))))
        (js->clj (fs.readdirSync classes-dir))))

(defn- read-section-names
  "section A–U の名称。`:isic.pack/scope :section` の 21 件だけを採る
  （division pack にも同じ `:pack/name` キーが付いているので、scope を見ずに
  拾うと『Construction of buildings』が section F の名前になる）。"
  []
  (let [packs (:packs (edn/read-string (slurp* packs-path)))]
    (into {}
          (keep (fn [p]
                  (when (= :section (:isic.pack/scope p))
                    [(:isic.pack/section p) (:pack/name p)])))
          packs)))

;; ---------------------------------------------------------------------------
;; 畳み込み
;; ---------------------------------------------------------------------------

(def axes
  [[:maturity/axis-substrate :substrate]
   [:maturity/axis-test :test]
   [:maturity/axis-governed :governed]
   [:maturity/axis-ingest :ingest]
   [:maturity/axis-docs :docs]
   [:maturity/axis-surface :surface]
   [:maturity/axis-fresh :fresh]])

(defn- round4 [x] (when x (/ (js/Math.round (* x 10000)) 10000)))

(defn- mean
  "測れた値だけの平均。1 件も無ければ nil（0 ではない）。"
  [xs]
  (let [vs (remove nil? xs)]
    (when (seq vs) (/ (reduce + vs) (count vs)))))

(defn- mean-bp [xs]
  (when-let [m (mean xs)] (js/Math.round m)))

(defn- digits-prefix [id]
  (second (re-find #"^(\d+)" (str id))))

(defn- industry-row
  [{:keys [by-repo]} official {:keys [id] :as e}]
  (let [digits (digits-prefix id)
        division (when (and digits (>= (count digits) 2)) (subs digits 0 2))
        section (some-> division section-of-division)
        repo-name (or (:business-id e) (str "cloud-itonami-isic-" id))
        m (get by-repo repo-name)
        official-name (get official id)]
    (cond-> {:scorecard/kind "industry"
             :source/dataset "itonami-industry-scorecard"
             :industry/id (str id)
             :industry/name (:name e)
             :industry/registry-maturity (:maturity e)
             :industry/repo-name repo-name
             :industry/official-class? (some? official-name)
             :industry/class-level? (and (some? digits) (= 4 (count digits)))
             :industry/measured? (some? m)}
      section (assoc :industry/section section)
      division (assoc :industry/division division)
      official-name (assoc :industry/official-name official-name)
      (:repo e) (assoc :industry/repo (:repo e))
      (:demo e) (assoc :industry/demo (:demo e))
      m (merge (select-keys m (into [:maturity/own :maturity/effective
                                     :leverage/band :repo/kind :maturity/layer]
                                    (map first) axes))))))

(defn- section-entity
  [section {:keys [section-names official-by-section]} rows]
  (let [measured (filter :industry/measured? rows)
        class-level (filter #(and (:industry/class-level? %)
                                  (:industry/official-class? %)) rows)
        impl-class (filter #(= :implemented (:industry/registry-maturity %)) class-level)
        official-n (get official-by-section section 0)]
    (cond->
     {:source/dataset "itonami-industry-scorecard"
      :scorecard/kind "section"
      :section/code section
      :section/name (get section-names section)
      :section/divisions (get section-divisions section)
      ;; registry 側の数
      :section/registered (count rows)
      :section/implemented (count (filter #(= :implemented (:industry/registry-maturity %)) rows))
      :section/spec (count (filter #(= :spec (:industry/registry-maturity %)) rows))
      ;; official class 表と突き合わせた数（coverage はここからしか出さない）
      :section/official-classes official-n
      :section/registered-class-level (count class-level)
      :section/registered-other (- (count rows) (count class-level))
      :section/implemented-class-level (count impl-class)
      ;; 測れた数と測れなかった数
      :section/scored (count measured)
      :section/unmeasured (- (count rows) (count measured))}
      (pos? official-n)
      (assoc :section/coverage-official
             (round4 (/ (count impl-class) official-n)))
      (seq measured)
      (merge
       {:section/mean-own (round4 (mean (map :maturity/own measured)))
        :section/mean-effective (round4 (mean (map :maturity/effective measured)))
        :section/min-own (round4 (apply min (keep :maturity/own measured)))
        :section/max-own (round4 (apply max (keep :maturity/own measured)))}
       (into {}
             (map (fn [[k label]]
                    [(keyword "section" (str "axis-" (name label)))
                     (mean-bp (map k measured))]))
             axes)))))

;; ---------------------------------------------------------------------------
;; 実行
;; ---------------------------------------------------------------------------

(verify-ranges!)

(def maturity (read-maturity))
(def registry (read-registry))
(def official (read-official-classes))
(def section-names (read-section-names))

(def official-by-section
  (reduce (fn [m code]
            (if-let [s (section-of-division (subs code 0 2))]
              (update m s (fnil inc 0))
              m))
          {} (keys official)))

(def rows (mapv (partial industry-row maturity official) (:entries registry)))
(def by-section (group-by :industry/section rows))

(def sections
  (vec (keep (fn [s]
               (when-let [rs (seq (get by-section s))]
                 (section-entity s {:section-names section-names
                                    :official-by-section official-by-section}
                                 rs)))
             section-order)))

(def unsectioned (get by-section nil))
(def off-classification
  (remove #(and (:industry/class-level? %) (:industry/official-class? %)) rows))

(def summary
  {:source/dataset "itonami-industry-scorecard"
   :scorecard/kind "summary"
   ;; 出典。どれか 1 つでも動いたら数が変わるので、全部名前で残す。
   :summary/sources
   [{:role "repo 単位の 7 軸スコア" :path maturity-path :adr "ADR-2608052000"}
    {:role "ISIC 事業 registry" :path registry-path
     :updated (:updated registry)}
    {:role "UN ISIC Rev.4 class（coverage の分母）" :path classes-dir}
    {:role "section A–U の名称" :path packs-path}]
   :summary/section-authority section-authority
   :summary/maturity-scan-at (:scan-at maturity)
   :summary/fleet-scored-repos (:scored-repos maturity)
   :summary/fleet-mean-own (:fleet-mean-own maturity)
   :summary/fleet-mean-effective (:fleet-mean-effective maturity)
   :summary/registered (count rows)
   :summary/implemented (count (filter #(= :implemented (:industry/registry-maturity %)) rows))
   :summary/spec (count (filter #(= :spec (:industry/registry-maturity %)) rows))
   :summary/scored (count (filter :industry/measured? rows))
   :summary/unmeasured (count (remove :industry/measured? rows))
   :summary/official-classes (count official)
   :summary/sections-with-entries (count sections)
   ;; registry の id が公式 class 表に無いもの。3 桁 group / 接尾辞付き /
   ;; 改訂差の合計。**Rev の食い違いはここに出る** ので黙って畳まない。
   :summary/off-classification (count off-classification)
   :summary/off-classification-sample
   (vec (take 8 (map :industry/id off-classification)))
   :summary/unsectioned (count unsectioned)
   :summary/caveat
   (str "coverage の分母は UN ISIC Rev.4 の " (count official) " class。"
        "registry.edn 自身は改訂番号を宣言していないので、両者が同じ改訂で"
        "ある保証は無い。突き合わない id は :summary/off-classification に"
        "件数で出してあり、分子からも除いてある。"
        "スコアは " (:scan-at maturity) " 時点の repo 実測（ADR-2608052000）で、"
        "測れなかった登録は 0 ではなく :section/unmeasured に分けてある。")})

(def payload
  "`:db/id` は連番の負数。DataScript/Datomic にそのまま transact できる形に
  しておく（`manifest/edn-query.cljs` の datom 面に載せるため）。"
  (vec (map-indexed (fn [i e] (assoc e :db/id (- (inc i))))
                    (concat [summary] sections rows))))

(defn- render [xs]
  (str ";; GENERATED by scripts/gen-itonami-industry-scorecard.cljs — 手で編集しない。\n"
       ";; 産業（ISIC）単位に畳んだ成熟度・coverage。出典と caveat は\n"
       ";; :scorecard/kind \"summary\" の entity に入っている。\n"
       "[\n"
       (str/join "\n" (map pr-str xs))
       "\n]\n"))

(def text (render payload))

(if check?
  (let [current (when (fs.existsSync out-path) (slurp* out-path))]
    (if (= current text)
      (println (str "OK " out-path " は canonical（" (count payload) " entity）"))
      (do (println (str "STALE " out-path
                        " — nbb scripts/gen-itonami-industry-scorecard.cljs で再生成する"))
          (js/process.exit 1))))
  (do (fs.mkdirSync (nodepath.dirname out-path) #js {:recursive true})
      (fs.writeFileSync out-path text)
      (println (str "wrote " out-path " — "
                    (count sections) " section / " (count rows) " industry / "
                    (:summary/implemented summary) " implemented / "
                    (:summary/scored summary) " scored / "
                    (:summary/unmeasured summary) " unmeasured"))))
