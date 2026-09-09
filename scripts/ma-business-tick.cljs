#!/usr/bin/env nbb
;; scripts/ma-business-tick.cljs — M&A マッチング事業の現在地を測る。observe-only。
;;
;; ## この tick が要る理由
;;
;; itonami bots の作業単位は repo 1 本である（`repo-bots` は west の repo ごとに
;; bot を 1 体、`itonami-os-maturity-tick` は候補を 1 本ずつ出す）。M&A マッチングは
;; 6 本にまたがるので、**どの bot の視野にも「事業」として入っていない。**
;; この tick は `manifest/ma-business.edn` の構成表を入力に、事業を 1 つの対象として
;; 測り、**次の 1 手をちょうど 1 つ**名指しする。着地は loop の agent 側がやる。
;;
;; ## 不変条件（姉妹 tick と同一 — itonami-os-maturity-tick / repo-bots/tick）
;;
;;   - **何も書かない・deploy しない・git を書き換えない。** 測って言うだけ。
;;     書くのは `~/.itonami/ma-business-tick.ledger.edn` だけ（追記のみ）。
;;   - 捏造ゼロ。読めなかったら :unmeasured。:ok にも :broken にも丸めない。
;;   - **測れなかったことを「問題なし」と同じ形で返さない**（ADR-2608136000）。
;;     構成 repo を 1 本も測れなかった周は exit 2 で「答えられなかった」と言う。
;;   - 宣言（os.edn）は origin/main から読む。working tree は共有 checkout なので
;;     他セッションの WIP で汚れている（itonami-os-maturity-tick が 2 周連続で
;;     この罠を踏んだ実測がある）。
;;
;; ## 標準形の判定は姉妹 tick の写しである
;;
;; `standard-form` は `scripts/itonami-os-maturity-tick.cljs` の `conformance` と
;; **同じ規則**（単一 ns / phase.cljc に read-ops+write-ops+default-phase /
;; operation.cljc に build と langgraph.graph / store.cljc に seed-db / governor.cljc /
;; render_html.clj 以外の .clj を持たない）。ここで :ok と言えることは、OS の候補
;; プールがその repo を受理することと同じ意味でなければ、この tick は嘘になる。
;; **姉妹を書き換えたらここも書き換える。**
;;
;; 使い方:
;;   nbb --classpath ".:scripts/nbb_compat" scripts/ma-business-tick.cljs
;;   nbb ... scripts/ma-business-tick.cljs --no-ledger    ; 測るが ledger に書かない
;;   COM_JUNKAWASAKI_ROOT=/tmp/fixture nbb ... scripts/ma-business-tick.cljs
;;
;; ## fleet gate にしない
;;
;; この tick は `orgs/cloud-itonami/*` を読む。fleet が配るのはその repo の tree だけで
;; `orgs/` 配下の子リポは入らないので、**gate にすると入力が無い場所で回ることになる**
;; （`root-permit-index` が 300 周それで赤かったのと同じ形）。exit も 0/2 しか返さない
;; ——「割れた床が在る」は fail ではなく観測である。
;;
;; exit 0 = 測れた（finding が在っても 0。これは gate ではなく監視）
;; exit 2 = 答えられなかった（構成表が読めない / 構成 repo を 1 本も測れなかった）

(ns ma-business-tick
  (:require [clojure.edn :as edn]
            [clojure.string :as str]))

(def fs (js/require "fs"))
(def os (js/require "os"))
(def cp (js/require "child_process"))
(def path (js/require "path"))

(def home (.homedir os))
(def root (or (aget (.-env js/process) "COM_JUNKAWASAKI_ROOT")
              (str home "/github/com-junkawasaki")))
(def registry-file (str root "/manifest/ma-business.edn"))
(def itonami-dir (str root "/orgs/cloud-itonami"))
(def os-app (str root "/orgs/network-awai/cloud-itonami"))
(def ledger-file (str home "/.itonami/ma-business-tick.ledger.edn"))
(def argv (vec *command-line-args*))
(def no-ledger? (boolean (some #{"--no-ledger"} argv)))

(defn log! [& xs] (println (str/join " " (map str xs))))

(defn- pad
  "cljs の core に `format` は無い。桁を揃えるのはここだけなので自前で足す。"
  [s n]
  (let [s (str s)] (if (< (count s) n) (str s (apply str (repeat (- n (count s)) " "))) s)))

(defn- exists? [p] (try (.existsSync fs p) (catch :default _ false)))
(defn- slurp* [p] (try (str (.readFileSync fs p "utf8")) (catch :default _ nil)))
(defn- ls* [p] (try (vec (.readdirSync fs p)) (catch :default _ nil)))

(defn- sh
  "走らせる。**投げない** —— probe が落ちても tick 全体を道連れにせず
  :unmeasured を書けるようにする。"
  [cmd args]
  (try
    (let [r (.spawnSync cp cmd (clj->js args) #js {:encoding "utf8" :timeout 60000})]
      {:code (aget r "status") :out (str (aget r "stdout"))})
    (catch :default e {:code nil :out (str e)})))

;; ── 標準形の判定（姉妹 tick の写し。上の docstring 参照） ────────────────────

(defn- op-set?
  "phase.cljc に `(def read-ops` / `(def write-ops` が両方在るか。
  姉妹は集合の中身まで読むが、ここが要るのは在否だけ。"
  [phase]
  (and phase
       (str/includes? phase "(def read-ops")
       (str/includes? phase "(def write-ops")))

(defn- standard-form
  "repo が OS 標準形かを実測する。返すのは判定と、外れた理由。
  **判定不能（src が読めない）と不適合（読めたが形が違う）を分ける。**"
  [dir]
  (let [src (str dir "/src")]
    (if-not (exists? src)
      {:verdict :unmeasured :why :no-src}
      (let [nss (try (->> (.readdirSync fs src #js {:withFileTypes true})
                          (filter #(.isDirectory %))
                          (mapv #(.-name %)))
                     (catch :default _ nil))]
        (cond
          (nil? nss) {:verdict :unmeasured :why :src-unreadable}
          (not= 1 (count nss)) {:verdict :broken :why :not-a-single-namespace}
          :else
          (let [ns- (first nss)
                base (str src "/" ns- "/")
                phase (slurp* (str base "phase.cljc"))
                oper (slurp* (str base "operation.cljc"))
                store (slurp* (str base "store.cljc"))
                gov? (exists? (str base "governor.cljc"))
                core-clj (->> (or (ls* base) [])
                              (filter #(and (str/ends-with? % ".clj")
                                            (not= "render_html.clj" %)))
                              vec)
                why (cond
                      (not (op-set? phase)) :no-phase-op-sets
                      (not (and phase (str/includes? phase "(def default-phase"))) :no-default-phase
                      (not (and oper (str/includes? oper "(defn build"))) :no-operation-build
                      (not (and oper (str/includes? oper "langgraph.graph"))) :not-a-langgraph-actor
                      (not (and store (str/includes? store "(defn seed-db"))) :no-seed-db
                      (not gov?) :no-governor
                      (seq core-clj) :jvm-only-core
                      :else nil)]
            (cond-> {:verdict (if why :broken :ok) :ns ns-}
              why (assoc :why why)
              (seq core-clj) (assoc :jvm-only-core core-clj))))))))

;; ── os.edn の宣言（origin/main から。working tree は読まない） ───────────────

(defn- os-declared-repos
  "os.edn が宣言している repo 名の集合。**読めなければ nil**（空集合ではない）。
  空集合を返すと『1 本も宣言されていない』と区別が付かず、private repo や
  未 checkout が『全部未宣言』として報告される。"
  []
  (if-not (exists? (str os-app "/.git"))
    {:repos nil :why :no-checkout}
    (let [{:keys [code out]} (sh "git" ["-C" os-app "show" "origin/main:os.edn"])]
      (if (not= 0 code)
        {:repos nil :why :origin-main-unreadable}
        ;; 宣言の形は os.edn 側の都合なので構造を仮定しない。**repo 名の出現**だけを
        ;; 見る（`cloud-itonami-<family>-<code>` と、構成表に載る素の名前）。
        ;; **repo を指す位置に在る名前だけを拾う。** 素の引用符トークンを全部
        ;; 拾うと、os.edn のどこかに偶然同じ文字列が在るだけで宣言済みになる
        ;; ——2 文字の `ma` が特に危ない。宣言の構造は os.edn 側の都合なので
        ;; 固定しないが、`orgs/cloud-itonami/<name>` というパス形か、名前に
        ;; `repo` / `path` を含む key の直後の値、のどちらかであることは要求する。
        ;; 外した場合に出るのは **false red**（見えて直せる）であって
        ;; false green ではない。
        {:repos (into (set (map second (re-seq #"\"orgs/cloud-itonami/([a-z0-9][a-z0-9.-]*)\"" out)))
                      (map second (re-seq #"[:/a-z0-9-]*(?:repo|path)[a-z0-9-]*\s+\"(?:orgs/cloud-itonami/)?([a-z0-9][a-z0-9.-]*)\"" out)))
         :raw-bytes (count out)}))))

;; ── 構成 repo 1 本を測る ────────────────────────────────────────────────────

(defn- measure-repo
  [c declared]
  (let [name- (:repo/name c)
        dir (str itonami-dir "/" name-)
        git? (exists? (str dir "/.git"))
        checkout (cond
                   (not (exists? dir)) {:verdict :unmeasured :why :no-checkout}
                   (not git?) {:verdict :unmeasured :why :not-a-git-repo}
                   :else {:verdict :ok})
        sf (if (= :unmeasured (:verdict checkout))
             {:verdict :unmeasured :why (:why checkout)}
             (standard-form dir))
        ;; **`ma` は候補プールに構造的に入らない**（candidate-family-re に当たらない）。
        ;; それは違反ではないので :n/a とし、代わりに os.edn の宣言を必須にする。
        auto (if (false? (:repo/auto-candidate? c)) :n/a :ok)
        decl (cond
               (nil? declared) {:verdict :unmeasured :why :os-edn-unreadable}
               (contains? declared name-) {:verdict :ok}
               :else {:verdict :broken :why :not-declared-in-os-edn})
        tests (cond
                (= :unmeasured (:verdict checkout)) {:verdict :unmeasured :why (:why checkout)}
                (or (exists? (str dir "/test"))
                    (str/includes? (or (slurp* (str dir "/deps.edn")) "") ":test"))
                {:verdict :ok}
                :else {:verdict :broken :why :no-test-signal})]
    {:repo name- :role (:repo/role c)
     :checkout checkout :standard-form sf :os-declared decl :test-signal tests
     :auto-candidate auto}))

;; ── 事業レベルの床 ──────────────────────────────────────────────────────────

(defn- stage-owner-floor
  "9 stage すべてに owner が居るか。**居ない stage を名指しする。**

  さらに **owner が構成 repo として測定対象に入っているか**も見る。ここを見ないと、
  stage に repo 名を書いた瞬間に床が緑になり、その repo は standard-form も
  os-declared も一度も測られない —— 『埋めたので進んだ』という見た目だけが残る。
  この床が測るのは **所有であって被覆ではない**（その stage の仕事をその repo が
  どこまで実際にやるかは、ここでは測っていない）。"
  [reg measured]
  ;; **測定対象（`measured`）の名前で照合する。** 構成表の全 entry で照合すると、
  ;; `:repo/role :none` の entry（誤配置の記録として載せてあるだけの repo）に
  ;; stage を割り当てても床が緑になり、その repo は一度も測られない。
  (let [names (set (map :repo measured))
        unowned (->> (:business/stages reg) (filter #(nil? (:stage/owner %))) (mapv :stage/id))
        dangling (->> (:business/stages reg)
                      (keep :stage/owner)
                      (remove names)
                      distinct vec)]
    (cond
      (seq unowned) {:verdict :broken :why :stages-without-owner :stages unowned}
      (seq dangling) {:verdict :broken :why :owner-not-a-constituent :repos dangling}
      :else {:verdict :ok})))

(defn- matching-runtime-floor
  "Matching stage の owner に runtime（src/）が在るか。
  **この事業を M&A クラウド型たらしめている 1 stage** なので、他と分けて測る。"
  [reg measured]
  (let [owner (some #(when (= :matching (:stage/id %)) (:stage/owner %)) (:business/stages reg))]
    (cond
      (nil? owner) {:verdict :broken :why :matching-has-no-owner}
      :else
      (let [m (some #(when (= owner (:repo %)) %) measured)]
        (cond
          (nil? m) {:verdict :unmeasured :why :owner-not-in-constituents :owner owner}
          (= :unmeasured (:verdict (:checkout m))) {:verdict :unmeasured
                                                    :why (:why (:checkout m)) :owner owner}
          (= :ok (:verdict (:standard-form m))) {:verdict :ok :owner owner}
          :else {:verdict :broken :owner owner :why (:why (:standard-form m))})))))

;; ── 次の 1 手 ───────────────────────────────────────────────────────────────

(defn- next-move
  "床の順（構成表の :business/floors が優先順位）で、最初に割れたものを 1 つ。
  **:unmeasured は「次の 1 手」にしない** —— 測れていないものを直しに行くと、
  直したつもりで別のものを壊す。測れないこと自体が報告対象。"
  [floors]
  (or (some (fn [[id v]] (when (= :broken (:verdict v)) {:floor id :detail v}))
            floors)
      nil))

;; ── 走る ────────────────────────────────────────────────────────────────────

(defn -main []
  (let [reg-raw (slurp* registry-file)
        reg (when reg-raw (try (edn/read-string reg-raw) (catch :default _ nil)))]
    (when (nil? reg)
      (log! "REFUSED  構成表を読めなかった:" registry-file)
      (log! "         測れなかったことを『問題なし』として報告しない。")
      (.exit js/process 2))
    (let [{declared :repos decl-why :why} (os-declared-repos)
          cs (->> (:business/constituents reg) (remove #(= :none (:repo/role %))))
          measured (mapv #(measure-repo % declared) cs)
          scanned (count measured)
          measurable (count (remove #(= :unmeasured (:verdict (:checkout %))) measured))
          ;; **床は per-repo の判定を畳んで作る。畳み方は 1 つだけ**（roll-up）:
          ;; 1 本でも :broken なら :broken、無くて 1 本でも :unmeasured なら
          ;; **:unmeasured**、全部 :ok なら :ok。
          ;;
          ;; 真ん中の段が要る。旧版は「:broken が無ければ :ok」と畳んでいたので、
          ;; **7 本中 6 本が測れず 1 本だけ通った周が :ok になっていた** ——
          ;; ADR-2608136000 の「測れなかった検査が、測って問題が無かった検査と
          ;; 同じ値を返す」そのもので、自分の PR の中に作っていた。
          roll-up (fn [k detail-fn]
                    (let [bad (filterv #(= :broken (:verdict (k %))) measured)
                          unk (filterv #(= :unmeasured (:verdict (k %))) measured)]
                      (cond
                        (seq bad) {:verdict :broken :repos (mapv detail-fn bad)}
                        (seq unk) {:verdict :unmeasured
                                   :repos (mapv (juxt :repo #(:why (k %))) unk)}
                        (empty? measured) {:verdict :unmeasured :why :no-constituents}
                        :else {:verdict :ok})))
          compute (fn [id]
                    (case id
                      :stage-owner (stage-owner-floor reg measured)
                      :checkout (roll-up :checkout (juxt :repo #(:why (:checkout %))))
                      :matching-runtime (matching-runtime-floor reg measured)
                      :standard-form (roll-up :standard-form
                                              (juxt :repo #(:why (:standard-form %))))
                      :os-declared (if (nil? declared)
                                     {:verdict :unmeasured :why decl-why}
                                     (roll-up :os-declared
                                              (juxt :repo #(:why (:os-declared %)))))
                      ;; **構成表が名前を挙げた床を、実装が無いからと黙って落とさない。**
                      ;; 落とすと『名簿には 5 つ、出力には 4 つ』が誰にも気付かれない。
                      {:verdict :unmeasured :why :floor-not-implemented}))
          ;; **順序は構成表が決める。** ここに literal で書くと、構成表の
          ;; 「順序が優先順位である」という宣言が嘘になる。
          floor-ids (mapv :floor/id (:business/floors reg))
          floors (mapv (fn [id] [id (compute id)]) floor-ids)
          nxt (next-move floors)]

      (log! "== M&A マッチング事業 tick ==" (str "(" (:business/id reg) ")"))
      (log! "root         " root)
      (log! "SCANNED      " scanned "constituents /" measurable "measurable")
      (log! "")
      (doseq [m measured]
        (log! (str "  " (pad (:repo m) 26) " " (pad (str (:role m)) 11)
                   " checkout=" (name (:verdict (:checkout m)))
                   " standard-form=" (name (:verdict (:standard-form m)))
                   (when-let [w (:why (:standard-form m))] (str "(" (name w) ")"))
                   " os-declared=" (name (:verdict (:os-declared m)))
                   " test=" (name (:verdict (:test-signal m))))))
      (log! "")
      (doseq [[id v] floors]
        (log! (str "  FLOOR " (pad (name id) 18) " " (pad (name (:verdict v)) 11) " "
                   (pr-str (dissoc v :verdict)))))
      (log! "")

      ;; **evidence floor.** 構成 repo を 1 本も測れなかった周に『次の 1 手』を
      ;; 出すと、それは測っていない現在地に基づく指示になる。答えを拒否する。
      (if (zero? measurable)
        (do (log! "REFUSED  構成 repo を 1 本も測れなかった（checkout 0 本）。")
            (log! "         west update で orgs/cloud-itonami を取得してから測り直す。")
            (log! "         SCANNED" scanned "MEASURABLE 0 → 『問題なし』ではなく『答えられなかった』。")
            (when-not no-ledger?
              (try (.mkdirSync fs (.dirname path ledger-file) #js {:recursive true})
                   (.appendFileSync fs ledger-file
                                    (str (pr-str {:at (.toISOString (js/Date.))
                                                  :root root :scanned scanned :measurable 0
                                                  :verdict :could-not-measure}) "\n"))
                   (catch :default _ nil)))
            (.exit js/process 2))
        (do
          (if nxt
            (do (log! "NEXT     床" (name (:floor nxt)) "が割れている。1 反復 = ここを 1 つ塞ぐ。")
                (log! "         " (pr-str (:detail nxt))))
            (log! "NEXT     割れている床は無い（:unmeasured は残りうる。上の FLOOR 行を読む）。"))
          (when-not no-ledger?
            (try
              (.mkdirSync fs (.dirname path ledger-file) #js {:recursive true})
              (.appendFileSync fs ledger-file
                               (str (pr-str {:at (.toISOString (js/Date.))
                                             :root root :scanned scanned :measurable measurable
                                             :floors (into {} (map (fn [[k v]] [k (:verdict v)]) floors))
                                             :next (:floor nxt)}) "\n"))
              (catch :default e (log! "WARN     ledger に書けなかった:" (str e)))))
          (.exit js/process 0))))))

(-main)
