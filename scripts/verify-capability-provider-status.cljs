#!/usr/bin/env nbb
;; verify-capability-provider-status.cljs — Kotoba capability パッケージが宣言する
;; `:capability/provider-status` と、実際にホストが提供しているものとの食い違いを測る。
;;
;;   nbb --classpath ".:scripts/nbb_compat" \
;;     scripts/verify-capability-provider-status.cljs [<superproject-root>] [--findings]
;;
;; ## なぜ要るか
;;
;; **宣言された status は主張であって測定ではない。** 2026-09-06、1 つの
;; capability について 5 つの repo が別々のことを言っている状態が実測された:
;;
;;   - `capability-llm-infer/capability.edn` は `:contract-only` と宣言している。
;;   - `murakumo/murakumo.app.edn` はその status を根拠に、itonami agent の
;;     LLM ターンを全部 mesh の外（JVM 常駐）に置いている。
;;   - `kototama/src/kototama/tender.clj` には quota 計測付きの実 JVM host 実装が在る。
;;   - `kototama/src/kototama/browser.cljc` は browser も `:yes` と言う。
;;   - `kotoba-lang/lang/host-parity.edn` は同じ browser を `:no` と言う。
;;   - `kotoba/src/kotoba/runtime.clj` の `op->kind` にその op が無いので、
;;     kotoba 側の host は本当に serve できない。
;;
;; どれか 1 つが「間違っている」のではない。**どれが古くてどれが今も真なのかを
;; 木の中の何も答えられなかった**というのが欠陥である。この検出器はその形だけを
;; 報告する ——「この repo が悪い」ではなく「この 2 つの出所が食い違っている、
;; ここに在る」。
;;
;; ## status の語義（kotoba-core-contracts が正本）
;;
;; `kotoba.core.capability-repository/allowed-provider-statuses` の docstring:
;;
;;   :contract-only          discovery + definition CID only
;;   :reference-implemented  published pure/reference provider with content digest
;;
;; つまり status が語っているのは **その package 自身が provider artifact を
;; 同梱しているか**であって、システム全体に provider が在るかではない。
;; ところが consumer は後者として読む（murakumo が実際にそう読んだ）。
;; この検出器が測るのはその 2 つの間の距離であり、status の定義を変えろとは
;; 言わない。
;;
;; ## 何を測るか（ホスト表が正本、status ではない）
;;
;;   :contract   kotoba-core-contracts resources/kotoba/runtime/capability_contract.edn
;;               — capability id と、その id に属する op（宣言された wire shape）
;;   :op-kind    kotoba       src/kotoba/runtime.clj          `op->kind`
;;   :real-ops   kotoba       src/kotoba/wasm_exec.clj        `real-op-effects`
;;   :effects    kotoba-lang  src/kotoba/lang/capability_values.cljc `effect-for-kind`
;;   :tender     kototama     src/kototama/tender.clj         `fn-by-id`
;;   :browser    kototama     src/kototama/browser.cljc       `host-impl`
;;   :parity     kotoba-lang  lang/host-parity.edn            `:imports`
;;   :artifact   その capability package 自身の `:capability/artifact`
;;               （宣言された path が実在し sha256 が一致するか）
;;
;; ホストは 5 つに分ける。**別ホストの行を突き合わせない**（kotoba の Chicory host と
;; kototama.tender はどちらも JVM だが別の実装なので、混ぜると偽の不一致が出る）:
;;
;;   :jvm-kotoba      kotoba 自身の host 面（op->kind → effect-for-kind → real-op-effects）
;;   :jvm-tender      kototama.tender（Chicory）
;;   :browser         wasm-webcomponent actor-host
;;   :node            同 JS module を Node で
;;   :reference-wasm  capability package 同梱の reference provider
;;
;; ## 3 つの finding kind
;;
;;   understated   `:contract-only` と宣言しているのに、ホスト表の少なくとも 1 つが
;;                 実 provider を示している
;;   overstated    provider 有りの status を宣言しているのに、どのホスト表も実装せず、
;;                 同梱 artifact も検証できない
;;   inconsistent  同じ (capability, host) について 2 つのホスト表が食い違っている
;;
;; ## exit code は三値（ADR-2608136000）
;;
;;   0  走査して、該当が無かった
;;   1  該当があった
;;   2  **答えられなかった** —— 自己検証に失敗した / ホスト表のどれかが読めなかった /
;;      走査対象が 0 件だった / capability package のどれかが読めなかった。
;;      **読めなかった入力が、読めて問題が無かった入力と同じ値を返してはならない。**
;;      west は checkout されていない project を fs に映さないので、
;;      「disk に無い」は「provider が無い」ではない。
;;
;; ## ここに測定値を書かない
;;
;; 件数・repo 名・「今日は N 件」は一切書かない。この workspace は日付付きの実測値が
;; 日付を落として引用され続ける事故を繰り返している。ここに残すのは *測り方* だけで、
;; *測った結果* は実行時の出力と `manifest/orgs-detectors.edn` の tick state が持つ。

(require '["node:fs" :as fs]
         '["node:path" :as path]
         '["node:crypto" :as crypto]
         '[clojure.edn :as edn]
         '[clojure.string :as str])

;; ---------------------------------------------------------------------------
;; source text scanning — Clojure ソースから 1 つの map form だけを取り出す
;; ---------------------------------------------------------------------------

(defn strip-comments
  "`;` から行末までを落とす。文字列リテラルと文字リテラル(`\\;` `\\\"`)の中は
   落とさない —— 素朴な行単位の正規表現はそこで壊れる。"
  [s]
  (let [n (count s)]
    (loop [i 0 out (transient []) in-str? false]
      (if (>= i n)
        (str/join (persistent! out))
        (let [c (nth s i)]
          (cond
            in-str?
            (cond
              (= c \\) (recur (+ i 2) (-> out (conj! c) (conj! (if (< (inc i) n) (nth s (inc i)) ""))) true)
              (= c \") (recur (inc i) (conj! out c) false)
              :else    (recur (inc i) (conj! out c) true))
            (= c \") (recur (inc i) (conj! out c) true)
            ;; 文字リテラル: `\;` `\"` `\{` を式の一部として飲み込む
            (= c \\) (recur (+ i 2) (-> out (conj! c) (conj! (if (< (inc i) n) (nth s (inc i)) ""))) false)
            (= c \;) (recur (or (str/index-of s "\n" i) n) out false)
            :else    (recur (inc i) (conj! out c) false)))))))

(defn balanced-from
  "index I の開き括弧から対応する閉じ括弧までの部分文字列。文字列・文字リテラルを
   跨がない。comment は事前に落としてあること。"
  [s i]
  (let [n (count s)]
    (loop [j i depth 0 in-str? false]
      (if (>= j n)
        nil
        (let [c (nth s j)]
          (cond
            in-str? (cond (= c \\) (recur (+ j 2) depth true)
                          (= c \") (recur (inc j) depth false)
                          :else    (recur (inc j) depth true))
            (= c \") (recur (inc j) depth true)
            (= c \\) (recur (+ j 2) depth false)
            (contains? #{\( \[ \{} c) (recur (inc j) (inc depth) false)
            (contains? #{\) \] \}} c) (if (= depth 1)
                                        (subs s i (inc j))
                                        (recur (inc j) (dec depth) false))
            :else (recur (inc j) depth false)))))))

(defn index-of-outside-string
  "NEEDLE の最初の出現位置。文字列リテラルの中は数えない。"
  [s needle from]
  (let [n (count s) k (count needle)]
    (loop [j from in-str? false]
      (cond
        (> (+ j k) n) nil
        in-str? (let [c (nth s j)]
                  (cond (= c \\) (recur (+ j 2) true)
                        (= c \") (recur (inc j) false)
                        :else    (recur (inc j) true)))
        :else (let [c (nth s j)]
                (cond
                  (= c \") (recur (inc j) true)
                  (= c \\) (recur (+ j 2) false)
                  (= needle (subs s j (+ j k))) j
                  :else (recur (inc j) false)))))))

(defn form-map-text
  "PATH のソースから、MARKER の後の ANCHOR（`{` で始まる短い文字列）で始まる
   balanced map の本文。見つからなければ nil。"
  [path marker anchor]
  (try
    (let [s (strip-comments (fs/readFileSync path "utf8"))]
      (when-let [i (str/index-of s marker)]
        (when-let [mi (index-of-outside-string s anchor i)]
          (balanced-from s mi))))
    (catch :default _ nil)))

(defn op-name
  "op->kind のキーは `'sym`。reader によって `(quote sym)` にも `'sym` という
   シンボルにもなりうるので両方から名前を取り出す。"
  [k]
  (cond
    (seq? k)    (some-> (second k) str)
    (symbol? k) (str/replace (str k) #"^'" "")
    :else       (str k)))

;; ---------------------------------------------------------------------------
;; sources — 全部 {:ok bool :data ... :why "..."} で返す。読めなかったことを
;; 「読めて空だった」と同じ形で返さない。
;; ---------------------------------------------------------------------------

(defn- ok [data] {:ok true :data data})
(defn- nope [why] {:ok false :why why})

(defn read-edn-file [p]
  (try
    (let [v (edn/read-string (fs/readFileSync p "utf8"))]
      (if (map? v) (ok v) (nope (str "not a map: " p))))
    (catch :default e (nope (str "unreadable: " p " (" (.-message e) ")")))))

(defn src-contract [root]
  (let [p (path/join root "orgs/kotoba-lang/kotoba-core-contracts"
                     "resources/kotoba/runtime/capability_contract.edn")
        r (read-edn-file p)]
    (if-not (:ok r)
      r
      (let [c (:data r)
            hi (:host-imports c)
            ids (:capability-ids c)]
        (if-not (and (map? hi) (map? ids) (pos? (count hi)))
          (nope (str "no :host-imports / :capability-ids in " p))
          (ok {:path p
               :cap-ids (set (keys ids))
               :cap->ops (reduce (fn [m [op d]]
                                   (if-let [cap (:capability d)]
                                     (update m cap (fnil conj #{}) (op-name op))
                                     m))
                                 {} hi)}))))))

(defn src-op-kind [root]
  (let [p (path/join root "orgs/kotoba-lang/kotoba/src/kotoba/runtime.clj")
        t (form-map-text p "(def op->kind" "{'")]
    (if-not t
      (nope (str "could not locate op->kind map in " p))
      (try
        (let [m (edn/read-string t)]
          (if-not (and (map? m) (pos? (count m)))
            (nope (str "op->kind parsed empty in " p))
            (ok {:path p
                 :data (into {} (map (fn [[k v]] [(op-name k) v])) m)})))
        (catch :default e (nope (str "op->kind unparseable in " p " (" (.-message e) ")")))))))

(defn src-real-ops [root]
  (let [p (path/join root "orgs/kotoba-lang/kotoba/src/kotoba/wasm_exec.clj")
        t (form-map-text p "(defn real-op-effects" "{'")]
    (if-not t
      (nope (str "could not locate real-op-effects map in " p))
      ;; `real-op-effects` の値は `(fn ...)` 本体で `#(...)` や `^bytes` を含むので
      ;; EDN では読めない。キーの形 `'op (fn` だけを取る。
      (let [ops (set (map second (re-seq #"'([A-Za-z0-9?!*<>=+_.$/-]+)\s+\(fn\b" t)))]
        (if (empty? ops)
          (nope (str "real-op-effects yielded no ops in " p " -- the map shape changed"))
          (ok {:path p :data ops}))))))

(defn src-effects [root]
  (let [p (path/join root "orgs/kotoba-lang/kotoba-lang/src/kotoba/lang/capability_values.cljc")
        t (form-map-text p "(def effect-for-kind" "{")]
    (if-not t
      (nope (str "could not locate effect-for-kind map in " p))
      (try
        (let [m (edn/read-string t)]
          (if-not (and (map? m) (pos? (count m)))
            (nope (str "effect-for-kind parsed empty in " p))
            (ok {:path p :data (set (keys m))})))
        (catch :default e (nope (str "effect-for-kind unparseable in " p " (" (.-message e) ")")))))))

(defn src-tender [root]
  (let [p (path/join root "orgs/kotoba-lang/kototama/src/kototama/tender.clj")
        t (form-map-text p "fn-by-id" "{")]
    (if-not t
      (nope (str "could not locate fn-by-id map in " p))
      ;; 値は `#(host-fn ...)` なので EDN では読めない。キーだけ取る。
      (let [ops (set (map second (re-seq #":([A-Za-z0-9?!*<>=+_.-]+)\s+#\(" t)))]
        (if (empty? ops)
          (nope (str "fn-by-id yielded no host fns in " p " -- the map shape changed"))
          (ok {:path p :data ops}))))))

(defn- host-matrix [t p what]
  (try
    (let [m (edn/read-string t)]
      (if-not (and (map? m) (pos? (count m)))
        (nope (str what " parsed empty in " p))
        (ok {:path p :data (into {} (map (fn [[k v]] [(name k) v])) m)})))
    (catch :default e (nope (str what " unparseable in " p " (" (.-message e) ")")))))

(defn src-browser [root]
  (let [p (path/join root "orgs/kotoba-lang/kototama/src/kototama/browser.cljc")
        t (form-map-text p "(def host-impl" "{")]
    (if-not t
      (nope (str "could not locate host-impl map in " p))
      (host-matrix t p "host-impl"))))

(defn src-parity [root]
  (let [p (path/join root "orgs/kotoba-lang/kotoba-lang/lang/host-parity.edn")
        r (read-edn-file p)]
    (if-not (:ok r)
      r
      (let [imports (:imports (:data r))]
        (if-not (and (map? imports) (pos? (count imports)))
          (nope (str "no :imports map in " p))
          (ok {:path p :data (into {} (map (fn [[k v]] [(name k) v])) imports)}))))))

(def source-readers
  {:contract src-contract
   :op-kind  src-op-kind
   :real-ops src-real-ops
   :effects  src-effects
   :tender   src-tender
   :browser  src-browser
   :parity   src-parity})

;; ---------------------------------------------------------------------------
;; classification — 純関数。srcs も cap も plain data なので自己検証で駆動できる。
;; ---------------------------------------------------------------------------

(def matrix-status
  "ホスト表の値 -> 判定。表に無い値は :unknown（推測しない）。"
  {:yes :provides
   :inject :conditional
   :coop-or-inject :conditional
   :component-link :conditional
   :no :absent})

(defn- obs [host source status detail]
  {:host host :source source :status status :detail detail})

(defn observations-for-op
  "srcs = {:op-kind {} :real-ops #{} :effects #{} :tender #{} :browser {} :parity {}}
   （全部読めている前提。読めない source は呼ぶ前に REFUSE している。）"
  [srcs op]
  (let [kind (get (:op-kind srcs) op)
        b (get (:browser srcs) op)
        pa (get (:parity srcs) op)
        mstat (fn [m k] (if (contains? m k) (get matrix-status (get m k) :unknown) :unknown))]
    (concat
     ;; --- :jvm-kotoba ------------------------------------------------------
     [(if kind
        (obs :jvm-kotoba :op-kind :unknown (str "op->kind " op " -> " kind))
        (obs :jvm-kotoba :op-kind :absent
             (str "kotoba.runtime/op->kind has no '" op ": guard-call cannot build a request for it")))
      (cond
        (nil? kind)
        (obs :jvm-kotoba :effects :unknown "no kind to check")
        (contains? (:effects srcs) kind)
        (obs :jvm-kotoba :effects :unknown (str "effect-for-kind has " kind))
        :else
        (obs :jvm-kotoba :effects :absent
             (str "op->kind maps '" op " -> " kind
                  " but effect-for-kind has no such kind: denied at RUN time with :unsupported-kind")))
      (if (contains? (:real-ops srcs) op)
        (obs :jvm-kotoba :real-ops :provides (str "wasm_exec/real-op-effects implements '" op))
        (obs :jvm-kotoba :real-ops :unknown "not in real-op-effects (other kotoba host surfaces exist)"))]
     ;; --- :jvm-tender ------------------------------------------------------
     [(if (contains? (:tender srcs) op)
        (obs :jvm-tender :tender :provides (str "tender/fn-by-id implements :" op))
        ;; 不在は不在の証明にならない —— tender は inject 経路も持つ
        (obs :jvm-tender :tender :unknown "not in tender/fn-by-id (injection path exists)"))
      (obs :jvm-tender :browser (mstat b :jvm) (str "kototama.browser/host-impl :jvm " (pr-str (:jvm b))))
      (obs :jvm-tender :parity (mstat pa :jvm) (str "host-parity.edn :jvm " (pr-str (:jvm pa))))]
     ;; --- :browser / :node -------------------------------------------------
     [(obs :browser :browser (mstat b :browser) (str "kototama.browser/host-impl :browser " (pr-str (:browser b))))
      (obs :browser :parity (mstat pa :browser) (str "host-parity.edn :browser " (pr-str (:browser pa))))
      (obs :node :browser (mstat b :node) (str "kototama.browser/host-impl :node " (pr-str (:node b))))
      (obs :node :parity (mstat pa :node) (str "host-parity.edn :node " (pr-str (:node pa))))])))

(defn observations
  "cap = {:id :status :ops #{op} :artifact {:status :provides|:absent|:unknown :detail}}"
  [srcs cap]
  (concat
   (mapcat #(map (fn [o] (assoc o :op %)) (observations-for-op srcs %)) (sort (:ops cap)))
   (when-let [a (:artifact cap)]
     [(assoc (obs :reference-wasm :artifact (:status a) (:detail a)) :op "-")])))

(defn inconsistencies
  "同じ (host, op) について、:unknown でない判定が 2 種類以上ある組。"
  [obs-list]
  (->> obs-list
       (remove #(= :unknown (:status %)))
       (group-by (juxt :host :op))
       (keep (fn [[[host op] group]]
               (let [statuses (set (map :status group))]
                 (when (> (count statuses) 1)
                   {:host host :op op
                    :sources (->> group
                                  (sort-by (comp name :source))
                                  (map #(str (name (:source %)) "=" (name (:status %))
                                             " [" (:detail %) "]"))
                                  vec)}))))
       (sort-by (juxt (comp name :host) :op))
       vec))

(defn classify
  "returns {:findings [{:kind :id :host :detail}] :observations [...]}"
  [srcs cap]
  (let [obs-list (vec (observations srcs cap))
        provides (filter #(= :provides (:status %)) obs-list)
        supporting (filter #(contains? #{:provides :conditional} (:status %)) obs-list)
        incs (inconsistencies obs-list)
        status (:status cap)
        base {:id (:id cap) :repo (:repo cap)}
        f-understated
        (when (and (= :contract-only status) (seq provides))
          [(assoc base
                  :kind :understated
                  :host nil
                  :detail
                  (str "declares :contract-only (package ships no provider artifact) "
                       "but host table(s) show a real provider: "
                       (str/join "; " (map #(str (name (:source %)) " @ " (name (:host %))
                                                 " op=" (:op %) " -- " (:detail %))
                                           (sort-by (juxt (comp name :source) :op) provides)))))])
        f-overstated
        (when (and (not= :contract-only status)
                   (contains? #{:reference-implemented} status)
                   (empty? supporting))
          [(assoc base
                  :kind :overstated
                  :host nil
                  :detail
                  (str "declares " status " but no host table implements it and its own artifact "
                       "could not be verified: "
                       (str/join "; " (map #(str (name (:source %)) " @ " (name (:host %))
                                                 " op=" (:op %) " -- " (:detail %))
                                           (filter #(= :absent (:status %)) obs-list)))))])
        f-inconsistent
        (map (fn [{:keys [host op sources]}]
               (assoc base
                      :kind :inconsistent
                      :host host
                      :detail (str "sources disagree about host " (name host) " for op " op ": "
                                   (str/join " vs " sources))))
             incs)]
    {:observations obs-list
     :findings (vec (concat f-understated f-overstated f-inconsistent))}))

;; ---------------------------------------------------------------------------
;; self-check — 「壊した入力で答えが変わること」を毎回確かめる。
;; 変えていないのに答えが変わらない検査は、緑を出す資格が無い。
;; ---------------------------------------------------------------------------

(def ^:private fixture-srcs-empty
  {:op-kind {} :real-ops #{} :effects #{} :tender #{} :browser {} :parity {}})

(defn- kinds-of [srcs cap] (set (map :kind (:findings (classify srcs cap)))))

(defn self-check!
  "returns nil when every arm behaves, otherwise a vector of failure strings."
  []
  (let [cap-co {:id "fx/one" :repo "fx" :status :contract-only :ops #{"fx-op"}}
        cap-ri {:id "fx/one" :repo "fx" :status :reference-implemented :ops #{"fx-op"}}
        ;; A. host table が実 provider を示す -> understated
        a (kinds-of (assoc fixture-srcs-empty :tender #{"fx-op"}) cap-co)
        ;; B. provider 有りを宣言しているのにどこにも無い -> overstated
        b (kinds-of (assoc fixture-srcs-empty :parity {"fx-op" {:jvm :no :browser :no :node :no}})
                    (assoc cap-ri :artifact {:status :absent :detail "fixture: artifact missing"}))
        ;; C. 2 表が同じ (cap, host) について食い違う -> inconsistent
        c (kinds-of (assoc fixture-srcs-empty
                           :browser {"fx-op" {:jvm :no :browser :yes :node :no}}
                           :parity  {"fx-op" {:jvm :no :browser :no  :node :no}})
                    cap-co)
        ;; D. 何も無い（op も宣言されていない）-> 何も出ない
        d (kinds-of fixture-srcs-empty cap-co)
        ;; E. reference-implemented で artifact が実在 -> overstated にしない
        e (kinds-of fixture-srcs-empty (assoc cap-ri :artifact {:status :provides :detail "fixture: artifact ok"}))
        fails
        (cond-> []
          (not (contains? a :understated))
          (conj (str "A: expected :understated, got " (pr-str a)))
          (not (contains? b :overstated))
          (conj (str "B: expected :overstated, got " (pr-str b)))
          (not (contains? c :inconsistent))
          (conj (str "C: expected :inconsistent, got " (pr-str c)))
          (seq d)
          (conj (str "D: expected NO findings for an unimplemented contract-only capability, got " (pr-str d)))
          (contains? e :overstated)
          (conj (str "E: expected NO :overstated when the package's own artifact verifies, got " (pr-str e))))]
    (when (seq fails) (vec fails))))

;; ---------------------------------------------------------------------------
;; disk side
;; ---------------------------------------------------------------------------

(defn- dir? [p] (try (.isDirectory (fs/statSync p)) (catch :default _ false)))
(defn- file? [p] (try (.isFile (fs/statSync p)) (catch :default _ false)))

(defn listed-capability-projects
  "manifest/west.yml が名指ししている capability-* project 名。
   west は 4,000 超の project を管理していて、その大半は checkout されていない ——
   disk に無いことは provider が無いことではないので、名簿の側も数える。"
  [root]
  (let [p (path/join root "manifest/west.yml")]
    (if-not (file? p)
      nil
      (try
        (->> (str/split-lines (fs/readFileSync p "utf8"))
             (keep #(second (re-find #"^\s*-\s+name:\s+(capability-\S+)" %)))
             set)
        (catch :default _ nil)))))

(defn checked-out-capability-dirs [root]
  (let [orgs (path/join root "orgs")]
    (when (dir? orgs)
      (->> (sort (fs/readdirSync orgs))
           (mapcat (fn [org]
                     (let [od (path/join orgs org)]
                       (if-not (dir? od)
                         []
                         (->> (sort (try (fs/readdirSync od) (catch :default _ [])))
                              (filter #(str/starts-with? % "capability-"))
                              (map (fn [r] {:org org :repo r :dir (path/join od r)})))))))
           vec))))

(defn sha256-of [p]
  (try
    (-> (crypto/createHash "sha256")
        (.update (fs/readFileSync p))
        (.digest "hex"))
    (catch :default _ nil)))

(defn artifact-observation
  "package 同梱の reference provider を、宣言された sha256 まで含めて確かめる。
   宣言だけを見て :provides にしない（それは status をもう一度読んでいるだけ）。"
  [dir manifest]
  (let [a (:capability/artifact manifest)
        rel (:path a)
        want (:sha256 a)]
    (cond
      (nil? a) nil
      (nil? rel) {:status :unknown :detail "artifact declared with no :path"}
      :else
      (let [full (path/join dir rel)]
        (cond
          (not (file? full)) {:status :absent :detail (str "declared artifact " rel " is not on disk")}
          (nil? want) {:status :unknown :detail (str rel " exists but declares no :sha256 to check it against")}
          :else (let [got (sha256-of full)]
                  (cond
                    (nil? got) {:status :unknown :detail (str "could not hash " rel)}
                    (= got want) {:status :provides :detail (str rel " present, sha256 matches the declared digest")}
                    :else {:status :absent
                           :detail (str rel " present but sha256 " (subs got 0 12)
                                        " != declared " (subs want 0 12))})))))))

(defn load-capability
  "-> {:ok true :cap {...}} | {:ok false :why ...}"
  [contract {:keys [org repo dir]}]
  (let [p (path/join dir "capability.edn")]
    (if-not (file? p)
      (nope (str repo ": no capability.edn"))
      (let [m (try (edn/read-string (fs/readFileSync p "utf8")) (catch :default e {::err (.-message e)}))]
        (cond
          (::err m) (nope (str repo ": capability.edn unparseable (" (::err m) ")"))
          (not (map? m)) (nope (str repo ": capability.edn is not a map"))
          :else
          (let [id (:capability/id m)
                status (:capability/provider-status m)
                declared (set (map name (or (:capability/imports m) #{})))
                from-contract (get (:cap->ops contract) id #{})
                ops (into declared from-contract)
                known-id? (contains? (:cap-ids contract) id)]
            (cond
              (not (string? id)) (nope (str repo ": no :capability/id"))
              (not (contains? #{:contract-only :reference-implemented} status))
              (nope (str repo ": unknown :capability/provider-status " (pr-str status)
                         " -- not in kotoba-core-contracts/allowed-provider-statuses"))
              (and (empty? ops) (not known-id?))
              (nope (str repo ": id " id " declares no imports and is absent from the contract's"
                         " :capability-ids -- nothing to measure against"))
              :else
              (ok {:id id :repo (str org "/" repo) :status status :ops ops
                   :op-source (cond (seq from-contract) :contract
                                    (seq declared) :capability-imports-only
                                    :else :none)
                   :artifact (artifact-observation dir m)}))))))))

;; ---------------------------------------------------------------------------
;; output
;; ---------------------------------------------------------------------------

(defn- finding! [severity key detail]
  (println (str "FINDING\t" severity "\t" key "\t" detail)))

(def severity-of {:understated "warn" :overstated "warn" :inconsistent "warn" :unverified "warn"})

(defn- finding-key [{:keys [kind id host repo]}]
  (str (name kind) ":" (or id repo) (when host (str ":" (name host)))))

(defn -main [& argv]
  (let [args (remove #(str/starts-with? % "--") argv)
        raw-root (or (first args) ".")
        ;; `{{root}}/orgs` を渡されても superproject root として扱う
        root (if (= "orgs" (path/basename (path/resolve raw-root)))
               (path/dirname (path/resolve raw-root))
               (path/resolve raw-root))
        findings? (boolean (some #{"--findings"} argv))
        bail! (fn [why]
                (println "SCANNED\t0/0")
                (println (str "REFUSED\t" why))
                (js/process.exit 2))]

    ;; 1. 自己検証が先。判別できない検査器は、走査する資格が無い。
    (when-let [fails (self-check!)]
      (println "SCANNED\t0/0")
      (println "REFUSED\tself-check failed: the classifier no longer discriminates")
      (doseq [f fails] (println (str "  " f)))
      (js/process.exit 2))
    (println "SELFCHECK\tok\t5 arms (understated/overstated/inconsistent/clean/artifact-verified)")

    (when-not (dir? root) (bail! (str "no such directory: " root)))
    (when-not (dir? (path/join root "orgs"))
      (bail! (str "no orgs/ under " root
                  " -- this detector reads west checkouts and cannot run from a bare repo tree")))

    ;; 2. ホスト表。1 つでも読めなければ全 capability の答えが変わるので REFUSE。
    (let [srcs-raw (into {} (map (fn [[k f]] [k (f root)])) source-readers)
          bad (into {} (remove (comp :ok val)) srcs-raw)]
      (when (seq bad)
        (println "SCANNED\t0/0")
        (println (str "SOURCES\t" (- (count srcs-raw) (count bad)) "/" (count srcs-raw)))
        (println "REFUSED\tone or more host tables could not be read; every verdict depends on all of them")
        (doseq [[k v] (sort-by (comp name key) bad)]
          (println (str "  " (name k) "\t" (:why v))))
        (js/process.exit 2))
      (println (str "SOURCES\t" (count srcs-raw) "/" (count source-readers)))
      (doseq [[k v] (sort-by (comp name key) srcs-raw)]
        (println (str "SOURCE\t" (name k) "\t" (get-in v [:data :path] (get-in v [:data :path] "")))))

      (let [contract (:data (:contract srcs-raw))
            srcs {:op-kind  (get-in srcs-raw [:op-kind :data :data])
                  :real-ops (get-in srcs-raw [:real-ops :data :data])
                  :effects  (get-in srcs-raw [:effects :data :data])
                  :tender   (get-in srcs-raw [:tender :data :data])
                  :browser  (get-in srcs-raw [:browser :data :data])
                  :parity   (get-in srcs-raw [:parity :data :data])}
            listed (listed-capability-projects root)
            dirs (checked-out-capability-dirs root)]

        (when (nil? listed)
          (bail! "manifest/west.yml is missing or unreadable: cannot say how much of the roster is off disk"))
        (when (empty? dirs)
          (println (str "SCANNED\t0/" (count listed)))
          (println "REFUSED\tno capability-* checkout found under orgs/; a scan of nothing is not a clean result")
          (js/process.exit 2))

        (let [loaded (map #(assoc (load-capability contract %) :src %) dirs)
              good (filter :ok loaded)
              bad-caps (remove :ok loaded)
              ;; `load-capability` returns {:ok true :data cap} -- reading it as
              ;; (:cap %) handed `classify` nil for every package, and a nil cap has
              ;; no :ops, so every one of them produced zero findings and the run
              ;; printed CLEAN. Measured 2026-09-07: 61/61 scanned, 0 findings,
              ;; while a direct call to `classify` on capability-llm-infer
              ;; returned 2. The detector had the exact defect it exists to find.
              results (map #(assoc (classify srcs (:data %)) :cap (:data %)) good)
              findings (vec (mapcat :findings results))
              unverified (mapv (fn [b] {:kind :unverified
                                        :repo (str (:org (:src b)) "/" (:repo (:src b)))
                                        :id nil :host nil
                                        :detail (:why b)})
                               bad-caps)
              all (vec (concat findings unverified))
              by-kind (frequencies (map :kind all))]

          ;; ---- evidence floor ----------------------------------------------
          (println (str "SCANNED\t" (count good) "/" (count listed)))
          (println (str "CHECKED-OUT\t" (count dirs)))
          (println (str "UNVERIFIED\t" (count unverified)))
          (println "NOTE\tSCANNED counts capability packages READ, over the capability-* projects west NAMES.")
          (println "NOTE\torgs/ is west-managed: an unchecked-out project is invisible to the filesystem, and its absence is not evidence that no provider exists.")
          (println "NOTE\t:capability/provider-status is a claim about the PACKAGE's own artifact (kotoba-core-contracts/allowed-provider-statuses). A finding reports that two sources disagree, not that a repo is wrong.")

          (doseq [[k n] (sort-by (comp name key) by-kind)]
            (println (str "KIND\t" (name k) "\t" n)))

          (if findings?
            (doseq [f (sort-by finding-key all)]
              (finding! (get severity-of (:kind f) "warn") (finding-key f) (:detail f)))
            (doseq [f (sort-by finding-key all)]
              (println (str (name (:kind f)) "\t" (or (:id f) (:repo f))
                            (when (:host f) (str "\t" (name (:host f))))
                            "\t" (:detail f)))))

          (println (str "FINDINGS\t" (count all)))

          (cond
            (seq unverified)
            (do (println (str "REFUSED\t" (count unverified) " of " (count dirs)
                              " checked-out capability packages could not be measured;"
                              " the findings above are a lower bound, not a complete answer"))
                (js/process.exit 2))

            (seq findings) (js/process.exit 1)

            :else (do (println "CLEAN\t0") (js/process.exit 0))))))))

(apply -main (vec *command-line-args*))
