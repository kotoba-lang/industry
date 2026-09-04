#!/usr/bin/env nbb
;; scripts/repo-bots/tick.cljs — 名簿の bot を、最後に測った順から有界な波で
;; 起こして 1 周させる。
;;
;;   nbb scripts/repo-bots/tick.cljs                 ; 既定の波（200 体）
;;   nbb scripts/repo-bots/tick.cljs --wave 500
;;   nbb scripts/repo-bots/tick.cljs --only kotoba-lang/amu
;;   nbb scripts/repo-bots/tick.cljs --report        ; 測らず、いまの state を読む
;;   nbb scripts/repo-bots/tick.cljs --state /tmp/x.edn --ledger /tmp/x.ledger.edn
;;
;; ## 何を測るか（全部ローカル。network を引かない）
;;
;;   :checkout     checkout が在って、git がそれを**自分の** repo として受理する
;;   :pinned       local HEAD が west pin と一致する
;;   :landed       未 commit の変更が無く、upstream より前に出た commit も無い
;;   :readme       README.md が在って 200 byte 以上ある
;;   :test-signal  コードが在るなら test の信号が在る（コードが無ければ :n/a）
;;
;; ## 4 値であることが設計の中心
;;
;; floor は `:ok` / `:broken` / `:n/a` / `:unmeasured` を返す。ADR-2608136000 が
;; 「測れなかった検査が、測って問題が無かった検査と同じ値を返す」を 1 日で 14 箇所
;; 見つけた形そのものなので、**測れなかったことを ok に畳まない**:
;;
;;   - checkout が無い bot は「違反 0 件」ではなく **:unmeasured**
;;   - `.git` は在るが git が受理しない checkout も **:unmeasured**。ここを ok に
;;     畳むと git は親を辿り、後続の床が **superproject の状態**をその bot の
;;     測定値として記録する（実測 2026-08-30。詳細は `floor-checkout`）
;;   - upstream を解決できない branch の :landed も **:unmeasured**（ok ではない）
;;   - 上流 default branch との遅れ（pin 鮮度）は network が要るのでここでは測らない。
;;     測っていないものを、測ったように見せない
;;
;; ## 出力の class
;;
;; orgs-detector-tick.cljs と同じ 4 分類。理由も同じで、**標準的に赤いものは沈黙と
;; 区別が付かない**（ADR-2608124800 が 867 / 282 / 269 / 268 連続失敗を数えた）。
;;
;;   NEW        この波で初めて割れた床。名指しで出す
;;   RESOLVED   前は割れていて、いま塞がった床。名指しで 1 度だけ
;;   STANDING   それ以外。件数と最古の齢だけ。**列挙しない**
;;   UNMEASURED 測れなかったもの。件数と理由の内訳
;;
;; その bot の初回は BASELINE として数える（初日は全部 new なので、叫べば
;; 同じ嘘を反対向きにやることになる）。
;;
;; ## 共有 checkout を書かない
;;
;; 書くのは `~/.gftd/repo-bots/` の下だけ。4,000 本の checkout は**読むだけ**で、
;; git の書き込みコマンドは 1 つも呼ばない。並行 agent が走っているマシンなので、
;; ここを緩めるとこの tick が他人の working tree を壊す側になる。
;;
;; exit: 0 = 波を測った（findings が在っても 0。これは gate ではなく監視）
;;       2 = 測れなかった（名簿が無い / 波が空 / lock 競合）。0 でも 1 でもない値。

(ns repo-bots-tick
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [cljs.pprint :as pp]))

(def fs (js/require "node:fs"))
(def os (js/require "node:os"))
(def path (js/require "node:path"))
(def cp (js/require "node:child_process"))

(def argv (vec (drop 2 js/process.argv)))
(defn- flag [n] (some #{n} argv))
(defn- opt [n] (let [i (.indexOf argv n)] (when (and (>= i 0) (< (inc i) (count argv)))
                                            (nth argv (inc i)))))

(def top (or (.-CLAUDE_PROJECT_DIR (.-env js/process)) (.cwd js/process)))
(def home (.homedir os))
(def state-dir (str home "/.gftd/repo-bots"))
(def state-file (or (opt "--state") (str state-dir "/state.edn")))
(def ledger-file (or (opt "--ledger") (str state-dir "/observations.ledger.edn")))
(def log-file (str state-dir "/tick.log"))
(def lock-file (str state-file ".lock"))
(def registry-file (.join path top "manifest" "repo-bots.edn"))

(def wave-size (max 1 (js/parseInt (or (opt "--wave") "200") 10)))
(def day-ms (* 24 60 60 1000))

(defn now-iso [] (.toISOString (js/Date.)))
(defn- ms [iso] (let [t (.parse js/Date (str iso))] (if (js/Number.isFinite t) t 0)))
(defn- age-days [iso] (js/Math.floor (/ (- (.now js/Date) (ms iso)) day-ms)))

(defn- read-edn [f]
  (try (edn/read-string (.readFileSync fs f "utf8")) (catch :default _ nil)))

(defn- write-edn! [f data]
  ;; tmp に書いてから rename。半分書けた state は「一度も走っていない」と同じ顔で
  ;; 読まれるので、途中の姿を本番の名前で晒さない。
  (let [tmp (str f ".tmp")]
    (.writeFileSync fs tmp (with-out-str (pp/pprint data)))
    (.renameSync fs tmp f)))

(defn- append-ledger! [events]
  (when (seq events)
    (.mkdirSync fs state-dir #js {:recursive true})
    (.appendFileSync fs ledger-file
                     (str/join "" (map #(str (pr-str %) "\n") events)))))

(defn- read-ledger []
  (->> (try (str/split-lines (.readFileSync fs ledger-file "utf8")) (catch :default _ []))
       (remove str/blank?)
       (keep #(try (edn/read-string %) (catch :default _ nil)))))

;; ---------------------------------------------------------------- git (read-only)

(defn- git
  "git を読み取りだけで叩く。返すのは {:ok? :out :err}。

  **stderr を捨てない**（CLAUDE.md の 6 問の 3 番目）。捨てると、原因が応答の中に
  書いてあっても読まないまま『測れなかった』とだけ言うことになる。4,000 本のうち
  1 本の壊れた checkout で波を落とさないので例外にはしないが、理由は持ち帰る。"
  [repo & args]
  (try
    {:ok? true
     :out (str/trim (str (.execFileSync cp "git" (clj->js (into ["-C" repo] args))
                                        #js {:encoding "utf8"
                                             :stdio #js ["ignore" "pipe" "pipe"]
                                             :timeout 20000 :maxBuffer 4194304})))
     :err nil}
    (catch :default e
      (let [raw (or (some-> (.-stderr e) str) (str e))
            one (-> raw str/split-lines first str str/trim)]
        {:ok? false :out nil :err (subs one 0 (min 120 (count one)))}))))

(defn- exists? [p] (try (.existsSync fs p) (catch :default _ false)))
(defn- size-of [p] (try (.-size (.statSync fs p)) (catch :default _ 0)))

(defn- real-path
  "realpath。解決できなければ与えられた値をそのまま返す（比較を落とさない）。"
  [p]
  (try (str (.realpathSync fs p)) (catch :default _ (str p))))

;; ---------------------------------------------------------------- floors

(defn- floor-checkout
  "checkout が在って、**git がそれを自分の repo として受理する**こと。

  `.git` が在ることは、git がそれを repo として受理することを意味しない。受理
  しなければ git は黙って**親を辿り superproject を答える** — その答えは成功と
  同じ形（exit 0 + それらしい出力）で返るので、後続の床が superproject の状態を
  この bot の測定値として記録する。

  実測 2026-08-30、`com-junkawasaki/org-spirit-in-physics-comics`: DataLad
  dataset の `.git` が `objects/` と `config` を失っていた。パスとしての `.git`
  は在るので旧実装はここを `:ok` で通し、`--next` は

    {:floor :landed :detail \"未 commit 1 ファイル\"}

  を出した。その 1 ファイルは superproject の
  `90-docs/observatory/observatory.datoms.edn` で、この repo のものではない。
  `floor-pinned` も同じ経路で superproject の HEAD を読んでいた。**壊れた
  checkout が、測れなかったのではなく別の repo の値を答えていた。**

  ADR-2608136000 の 2 問目（そもそも実行できないとき何を返すか）そのものだが、
  返っていたのは pass ではなく**他人の測定値**なので、出力から嘘だと分からない。"
  [abs]
  (cond
    (not (exists? abs))               [:unmeasured "checkout が無い"]
    (not (exists? (str abs "/.git")))  [:unmeasured "git repo ではない"]
    :else
    (let [{:keys [ok? out err]} (git abs "rev-parse" "--show-toplevel")]
      (cond
        (not ok?)
        [:unmeasured (str "git が checkout を開けない: " err)]
        ;; git が答えた toplevel がこの checkout でないなら、以降の git は全部
        ;; 別の repo について答える。ok に畳まず :unmeasured で止める。
        (not= (real-path out) (real-path abs))
        [:unmeasured (str ".git が壊れている — git は親の repo を答える: " out)]
        :else [:ok nil]))))

(defn- floor-pinned [abs pin]
  (let [{:keys [ok? out err]} (git abs "rev-parse" "HEAD")]
    (cond
      (not ok?)               [:unmeasured (str "HEAD を解決できない: " err)]
      (str/blank? (str pin))  [:unmeasured "名簿に pin が無い"]
      (= out (str pin))       [:ok nil]
      :else                   [:broken (str "HEAD " (subs out 0 7) " ≠ pin " (subs (str pin) 0 7))])))

(defn- floor-landed [abs]
  (let [dirty (git abs "status" "--porcelain" "--untracked-files=no")
        ahead (git abs "rev-list" "--count" "@{u}..HEAD")]
    (cond
      (not (:ok? dirty))               [:unmeasured (str "status を取れない: " (:err dirty))]
      (not (str/blank? (:out dirty)))  [:broken (str "未 commit " (count (str/split-lines (:out dirty))) " ファイル")]
      ;; upstream が無い branch は「進んでいない」ではなく「測れない」。ここを ok に
      ;; 畳むと、push されていない branch が静かに緑になる。
      (not (:ok? ahead))               [:unmeasured (str "upstream を解決できない: " (:err ahead))]
      (= (:out ahead) "0")             [:ok nil]
      :else                            [:broken (str "未 push " (:out ahead) " commit")])))

(defn- floor-readme [abs]
  (let [p (str abs "/README.md")]
    (cond
      (not (exists? p))    [:broken "README.md が無い"]
      (< (size-of p) 200)  [:broken (str "README.md が " (size-of p) " byte")]
      :else                [:ok nil])))

(def ^:private code-markers ["deps.edn" "package.json" "src" "bb.edn" "shadow-cljs.edn" "Cargo.toml"])

(defn- floor-test-signal [abs]
  (if-not (some #(exists? (str abs "/" %)) code-markers)
    ;; コードが無い repo に test を要求しない。ただしこれは**測った上での** :n/a で
    ;; あって、宣言で検査対象から外したものではない。
    [:n/a "コードが無い"]
    (let [deps (when (exists? (str abs "/deps.edn"))
                 (try (str (.readFileSync fs (str abs "/deps.edn") "utf8")) (catch :default _ nil)))
          pkg  (when (exists? (str abs "/package.json"))
                 (try (str (.readFileSync fs (str abs "/package.json") "utf8")) (catch :default _ nil)))]
      (if (or (exists? (str abs "/test")) (exists? (str abs "/tests"))
              (and deps (str/includes? deps ":test"))
              (and pkg (re-find #"\"test\"\s*:" pkg)))
        [:ok nil]
        [:broken "test dir も test alias も無い"]))))

(defn- measure [bot]
  (let [abs (.join path top (:bot/repo bot))
        [c cd] (floor-checkout abs)]
    (if (not= c :ok)
      ;; checkout が無い bot は「床を守れている」でも「割っている」でもない。
      {:status :unmeasured :reason cd
       :floors {:checkout [c cd]}}
      {:status :measured
       :floors {:checkout    [:ok nil]
                :pinned      (floor-pinned abs (:bot/pin bot))
                :landed      (floor-landed abs)
                :readme      (floor-readme abs)
                :test-signal (floor-test-signal abs)}})))

;; ---------------------------------------------------------------- fold

(defn- by-verdict [m v]
  (into {} (for [[k [val d]] (:floors m) :when (= val v)] [k d])))

(defn- fold-one
  "1 体分の測定を前の行に畳み込み、[next-row events] を返す。"
  [id prev m at]
  (let [first? (nil? prev)
        prev-broken (:broken prev {})
        now-broken (by-verdict m :broken)
        now-unmeasured (by-verdict m :unmeasured)
        now-na (set (keys (by-verdict m :n/a)))
        newly (remove (set (keys prev-broken)) (keys now-broken))
        cleared (remove (set (keys now-broken)) (keys prev-broken))
        row {:last-tick at
             :ticks (inc (:ticks prev 0))
             :status (:status m)
             :reason (:reason m)
             :broken (into {} (for [[f d] now-broken]
                                [f {:since (get-in prev-broken [f :since] at) :detail d}]))
             :unmeasured now-unmeasured
             :na now-na}
        events (cond
                 first? [{:at at :bot id :event :first-observation
                          :status (:status m)
                          :broken (vec (sort (keys now-broken)))
                          :unmeasured (vec (sort (keys now-unmeasured)))}]
                 :else
                 (concat
                  (for [f newly]
                    {:at at :bot id :event :broke :floor f :detail (get now-broken f)})
                  (for [f cleared]
                    {:at at :bot id :event :cleared :floor f
                     :since (get-in prev-broken [f :since])
                     :age-days (age-days (get-in prev-broken [f :since] at))})))]
    [row (vec events)]))

;; ---------------------------------------------------------------- report

(defn- cap [n xs]
  (let [v (vec xs)]
    (if (<= (count v) n) [v 0] [(subvec v 0 n) (- (count v) n)])))

(defn- print-events! [label evs detail-fn]
  (when (seq evs)
    (println)
    (println (str label " (" (count evs) ")"))
    (let [[shown more] (cap 15 evs)]
      (doseq [e shown] (println (str "  " (detail-fn e))))
      (when (pos? more) (println (str "  … 他 " more " 件"))))))

(defn- standing-summary [bots]
  ;; 列挙しない。床ごとに件数と最古の齢だけ —— 既に言ったことだから。
  (let [pairs (for [[_ row] bots [f {:keys [since]}] (:broken row)] [f since])]
    (->> (group-by first pairs)
         (map (fn [[f xs]]
                [f (count xs) (apply max (map #(age-days (second %)) xs))]))
         (sort-by (comp - second)))))

(defn- growth-window [events days]
  (let [cut (- (.now js/Date) (* days day-ms))
        recent (filter #(> (ms (:at %)) cut) events)]
    {:cleared (count (filter #(= :cleared (:event %)) recent))
     :broke (count (filter #(= :broke (:event %)) recent))}))

(defn- roster-drift
  "名簿 (manifest/repo-bots.edn) が west.yml からどれだけ遅れているかを 2 つ数える。

  `:missing` — west.yml に在って名簿に無い repo。名簿は生成物なので west.yml が
  進むと黙って古くなり、**新しく登録された repo には bot が居ないまま**になる。
  それは『床を割っていない』と同じ顔をする。

  `:stale-pin` — 名簿の `:bot/pin` が west.yml の revision と違う repo。
  `floor-pinned` は HEAD をこの `:bot/pin` と比べるので、**名簿が遅れた分だけ
  誰も使っていない revision について答える**。向きは 2 つとも壊れる:
  checkout が west.yml の pin ちょうどに在っても `:broken` と報告し（実測
  2026-09-03、`kotoba-lang/num` —— HEAD = west pin = upstream tip の 3 点が一致
  していたのに 7 日間 `pinned` として立ち続けた）、逆に HEAD がたまたま古い pin と
  一致していれば west.yml が動いた後も `:ok` を返す。**後者は出力から見えない。**

  ここを黙らせず tick 自身が言うのは、名簿の再生成が誰かの記憶に依存しているため。
  ROSTER-DRIFT が membership しか見ていなかった間、pin の遅れは無症状だった。

  archived / datalad は名簿から意図的に外してあるので、ここでも外す —— さもないと
  常に 61 件のずれを報告し続け、**本物のずれが平常値に埋もれる**。

  読めなければ nil（0 ではない）—— 測れなかったことを『ずれ無し』に畳まない。"
  [registry]
  (try
    (let [lines (str/split-lines (.readFileSync fs (.join path top "manifest" "west.yml") "utf8"))
          eligible?  (fn [c] (and c (:path c)
                                   (not (some #{"archived" "datalad"} (:groups c)))))
          ;; path -> revision。revision が無い entry は nil のまま入れる（key の
          ;; 有無が『west に在る』の答えなので、値が無いことで落とさない）。
          west
          (loop [ls lines cur nil out {}]
            (if-let [line (first ls)]
              (let [flush (fn [o] (if (eligible? cur) (assoc o (:path cur) (:revision cur)) o))]
                (cond
                  (re-find #"^    - name: \S+$" line) (recur (rest ls) {} (flush out))
                  (and cur (re-find #"^      path: (\S+)$" line))
                  (recur (rest ls) (assoc cur :path (second (re-find #"^      path: (\S+)$" line))) out)
                  (and cur (re-find #"^      revision: (\S+)$" line))
                  (recur (rest ls) (assoc cur :revision (second (re-find #"^      revision: (\S+)$" line))) out)
                  (and cur (re-find #"^      groups: \[(.*)\]$" line))
                  (recur (rest ls)
                         (assoc cur :groups (map str/trim (str/split (second (re-find #"^      groups: \[(.*)\]$" line)) #",")))
                         out)
                  :else (recur (rest ls) cur out)))
              (if (eligible? cur) (assoc out (:path cur) (:revision cur)) out)))
          known (set (map :bot/repo registry))]
      {:missing (count (remove known (keys west)))
       ;; west 側に revision が無い entry は「pin が違う」ではなく測れない —— 数に
       ;; 混ぜず落とす。名簿にしか無い repo も同様（それは :missing の裏で、
       ;; ORPHAN として別に報告される）。
       :stale-pin (count (for [b registry
                               :let [w (get west (:bot/repo b))]
                               :when (and w (:bot/pin b) (not= w (:bot/pin b)))]
                           b))})
    (catch :default _ nil)))

(defn- report! [registry state]
  (let [bots (:bots state)
        ids (set (map :bot/id registry))
        roster (count ids)
        ;; 名簿に在るもののうち測ったものだけを数える。state には**名簿から消えた
        ;; repo の行**が残る（west の entry rename で実際に起きた: kotobase-query →
        ;; ayatori）。これを引き算に混ぜると `never -1` という、有り得ない数が出る。
        ;; 有り得ない数を 1 つでも印字する報告は、他の数も信用されなくなる。
        ticked (count (filter #(contains? ids (key %)) bots))
        orphans (- (count bots) ticked)
        never (- roster ticked)
        ;; measured / unmeasured も名簿の中だけで数える。orphan を混ぜると
        ;; measured + unmeasured ≠ ticked になり、どの数が本当か分からなくなる。
        in-roster (filter #(contains? ids (key %)) bots)
        measured (count (filter #(= :measured (:status (val %))) in-roster))
        unmeasured (- ticked measured)
        led (read-ledger)]
    (println (str "ROSTER\t" roster "\tbots"))
    (let [d (roster-drift registry)
          parts (when d
                  (cond-> []
                    (pos? (:missing d))   (conj (str (:missing d) " 件が west.yml に在って名簿に無い"))
                    (pos? (:stale-pin d)) (conj (str (:stale-pin d) " 件の :bot/pin が west.yml と違う"
                                                     " —— その分 :pinned 床は誰も使っていない revision について答える"))))]
      (cond
        (nil? d)   (println "ROSTER-DRIFT\tUNMEASURED —— west.yml が読めない")
        (seq parts) (println (str "ROSTER-DRIFT\t" (str/join " / " parts)
                                  "\t再生成: nbb scripts/repo-bots/gen-registry.cljs"))))
    (println (str "TICKED\t" ticked "\t(never " never ")"
                  (when (pos? orphans) (str "\tORPHAN " orphans " —— 名簿から消えた repo の state 行"))))
    (println (str "MEASURED\t" measured "\tUNMEASURED\t" unmeasured))
    (when (seq bots)
      (println)
      (println "STANDING（床ごと。列挙しない）")
      (doseq [[f n oldest] (standing-summary bots)]
        (println (str "  " (name f) "\t" n " 体\t最古 " oldest "d"))))
    ;; README が無く、コードも無い repo は「文書の欠落」ではなく **repo が空**。
;;    直し方が別物（README を書くのではなく、中身を作るか退役させる）なので分けて数える。
;;    実測 2026-08-27: 提案の最初の波 8 件のうち 6 件がこれだった。
    (let [empty-repos (count (for [[_ r] bots
                                   :when (get (:broken r) :readme)
                                   :when (contains? (set (:na r)) :test-signal)]
                               1))]
      (when (pos? empty-repos)
        (println (str "EMPTY-REPO\t" empty-repos
                      " 体は README もコードも無い —— README の欠落ではなく repo が空"))))
    (when (pos? unmeasured)
      (println)
      (println "UNMEASURED の理由")
      (doseq [[r n] (sort-by (comp - val)
                             (frequencies (keep #(:reason (val %)) in-roster)))]
        (println (str "  " r "\t" n))))
    (let [g7 (growth-window led 7) g1 (growth-window led 1)]
      (println)
      (println (str "GROWTH\t7d cleared " (:cleared g7) " / broke " (:broke g7)
                    "\t24h cleared " (:cleared g1) " / broke " (:broke g1)))
      (println (str "LEDGER\t" (count led) "\tevents\t" ledger-file)))))


;; ---------------------------------------------------------------- next

;; 床の優先順。上ほど先に直す。
;;   :landed  未着地の作業は**失われうる**。他の床は放置しても情報が減らない
;;   :pinned  checkout と manifest の食い違いは、次に触る誰かを誤らせる
;;   :readme  名前が機能を示さない repo の入口（ADR-2608039980 の「無い」と
;;            言う前に索引を引く、の索引側）
;;   :test-signal  本物の仕事。最後
(def floor-priority {:landed 0 :pinned 1 :readme 2 :test-signal 3})

;; 無人の loop に渡してはいけない床。
;;
;; :landed は**他人の未 commit の作業**である。優先順が一番上なのは正しい（失われ
;; うるのはこれだけ）が、それは**人が見ている**ときの話で、無人の周回が真っ先に
;; 手を付けてよい対象ではない。このマシンは並行 agent が走っており、CLAUDE.md が
;; 共有 checkout の直接編集を禁じているのはまさにこの形の事故のため。
;;
;; --next は従来どおり :landed を先頭に出す（人が /repo-bot-drain を打つときの答え）。
;; --next-unattended はそれを外す。**外したことを黙らない** —— 何件を外したかを
;; 一緒に返す。
(def ^:private unattended-excluded #{:landed})

;; 名簿から消えた repo の state 行を候補にしない。
;;
;; `report!` は orphan を注意深く全部の数から外しているのに、**候補を配る側は
;; 外していなかった**。名簿に無い bot は `select-wave` が registry から選ぶので
;; 二度と測られず、`--only` は「波が空」で REFUSED になる —— つまりその finding は
;; **どう直しても RESOLVED にならない**。それが「直せば塞がる finding」と同じ形で
;; 出てくるので、loop は毎周それを渡され、そこで止まる。
;;
;; 実測 2026-09-04: orphan 11 行のうち 3 行が broken を 4 件持ち、4 件とも state 中
;; 最古の :since だったため :pinned と :readme の**先頭に居座っていた**。
;; local-murakumo が head に着いた時点で無人 loop は進めなくなっていた。
;;
;; ADR-2608136000 の 2 問目（そもそも実行できないとき何を返すか）。ここが返して
;; いたのは pass ではなく**実行できない仕事**で、出力からはそれと分からない。
;; 黙って捨てず、何件外したかを一緒に返す（:landed の held-for-a-human と同じ作法）。
(defn- next-finding [state & {:keys [unattended? roster]}]
  (let [cands (for [[id row] (:bots state)
                    [f {:keys [since detail]}] (:broken row)
                    :when (or (nil? roster) (contains? roster id))
                    :when (not (and unattended? (unattended-excluded f)))]
                {:bot id :floor f :since since :detail detail})]
    (->> cands
         ;; ⚠ 最初の版は `(.indexOf (clj->js floor-priority) (:floor c))` と書いて
         ;; いた。JS 配列に cljs keyword を indexOf すると同一性比較になって**常に
         ;; -1** を返すので、優先順は一度も効かず :since だけで並んでいた。
         ;; 実測 2026-08-27: :landed が 2 件在るのに :readme を先頭に出していた。
         (sort-by (fn [c] [(get floor-priority (:floor c) 99) (ms (:since c))]))
         first)))

;; ---------------------------------------------------------------- wave

(defn- select-wave [registry prev-bots]
  (let [only (opt "--only")
        pool (if only (filter #(str/includes? (:bot/id %) only) registry) registry)]
    (->> pool
         (sort-by (fn [b] [(ms (:last-tick (get prev-bots (:bot/id b)) "1970-01-01T00:00:00.000Z"))
                           (:bot/id b)]))
         (take (if only (count pool) wave-size))
         vec)))

(def checkpoint-every 250)

(defn- run-wave!
  "波を 1 つ回し、exit code を返す。exit はここで呼ばない —— js/process.exit は
  finally を飛ばすので、ここで呼んだ版は lock を握ったまま抜けていた。"
  [registry prev]
  (let [at (now-iso)
        prev-bots (:bots prev {})
        wave (select-wave registry prev-bots)]
    (if (empty? wave)
      ;; 波が空なのは「全部きれい」ではなく「測れなかった」。
      (do (println "REFUSED\t波が空。名簿か --only を確かめる") 2)
      (loop [bs (seq wave) acc prev-bots pending [] all [] since 0]
        (if-let [b (first bs)]
          (let [id (:bot/id b)
                [row evs] (fold-one id (get prev-bots id) (measure b) at)
                acc' (assoc acc id row)
                pending' (into pending evs)
                all' (into all evs)]
            ;; 長い波の途中で落ちても、測った分は残す。**14 分走って何も記録しない
            ;; tick は、走らなかった tick と外から見て同じ**。
            (if (>= (inc since) checkpoint-every)
              (do (append-ledger! pending')
                  (write-edn! state-file {:schema 1 :updated at :bots acc' :partial? true})
                  (recur (next bs) acc' [] all' 0))
              (recur (next bs) acc' pending' all' (inc since))))
          (let [scanned (count wave)
                baseline (filter #(= :first-observation (:event %)) all)
                broke (filter #(= :broke (:event %)) all)
                cleared (filter #(= :cleared (:event %)) all)]
            (if (zero? scanned)
              (do (println "REFUSED\tSCANNED 0 —— 測っていないものを clean と報告しない") 2)
              (do
                (append-ledger! pending)
                (write-edn! state-file {:schema 1 :updated at :bots acc})
                (println (str "SCANNED\t" scanned "\tbots\t(roster " (count registry) ")"))
                (println (str "WAVE\t" (:bot/id (first wave)) " … " (:bot/id (last wave))))
                (when (seq baseline)
                  (println (str "BASELINE\t" (count baseline) " 体を初めて測った（new とは数えない）")))
                (print-events! "NEW（この波で初めて割れた床）" broke
                               #(str (:bot %) "\t" (name (:floor %)) "\t" (:detail %)))
                (print-events! "RESOLVED（塞がった床）" cleared
                               #(str (:bot %) "\t" (name (:floor %)) "\t" (:age-days %) "d 経過"))
                (println)
                (report! registry {:bots acc})
                0))))))))

;; ---------------------------------------------------------------- lock / main

(defn- lock-age-ms []
  (try (- (.now js/Date) (.getTime (.-mtime (.statSync fs lock-file))))
       (catch :default _ 0)))

(defn- try-lock! []
  (try
    (.mkdirSync fs state-dir #js {:recursive true})
    (.writeFileSync fs lock-file (str (.-pid js/process) " " (now-iso)) #js {:flag "wx"})
    true
    (catch :default _ false)))

(defn- acquire-lock!
  "1 度だけ取りに行き、取れなければ 30 分より古い lock（死んだ tick のもの。波は
  数分で終わる）だけを 1 回回収して再試行する。奪える仕組みにしない —— この
  tick が他人の実行を殺す側になる。"
  []
  (or (try-lock!)
      (when (> (lock-age-ms) (* 30 60 1000))
        (try (.unlinkSync fs lock-file) (catch :default _ nil))
        (try-lock!))))

(defn- release-lock! [] (try (.unlinkSync fs lock-file) (catch :default _ nil)))

(let [registry (read-edn registry-file)]
  (if-not (seq registry)
    (do (println "REFUSED\t名簿が読めない:" registry-file)
        (println "  生成: nbb scripts/repo-bots/gen-registry.cljs")
        (js/process.exit 2))
    (let [prev (or (read-edn state-file) {:schema 1 :bots {}})]
      (cond
        (or (flag "--next") (flag "--next-unattended"))
        (let [unattended? (boolean (flag "--next-unattended"))
              roster (set (map :bot/id registry))
              n (next-finding prev :unattended? unattended? :roster roster)
              ;; held も orphan も**名簿の中だけ**で数える。名簿から消えた bot の
              ;; :landed は「人のために取っておいた」のではなく、そもそも配れない。
              held (when unattended?
                     (count (for [[id row] (:bots prev)
                                  [f _] (:broken row)
                                  :when (contains? roster id)
                                  :when (unattended-excluded f)] 1)))
              orphaned (count (for [[id row] (:bots prev)
                                    [_ _] (:broken row)
                                    :when (not (contains? roster id))] 1))]
          ;; 候補が無いことと、測っていないことを区別する。state が空なら
          ;; 「finding 0 件」ではなく「まだ誰も測っていない」。
          (println (pr-str (cond
                             (empty? (:bots prev)) {:outcome :not-measured}
                             (nil? n) (cond-> {:outcome :no-candidates
                                                :ticked (count (:bots prev))}
                                        (and held (pos? held))
                                        (assoc :held-for-a-human held
                                               :note "無人の周回では :landed を渡さない（他人の未 commit の作業）")
                                        (pos? orphaned)
                                        (assoc :orphan-findings-skipped orphaned))
                             :else (cond-> (merge {:outcome :candidate} n)
                                     (and held (pos? held))
                                     (assoc :held-for-a-human held)
                                     (pos? orphaned)
                                     (assoc :orphan-findings-skipped orphaned)))))
          (when (pos? orphaned)
            ;; 数だけ返して黙らない。放っておくと state に溜まり続けるので、
            ;; 掃除の入口を stderr に出す（stdout は EDN 1 行のままにする）。
            (binding [*print-fn* *print-err-fn*]
              (println (str "NOTE\t名簿に無い bot の finding を " orphaned
                            " 件外した —— どう直しても RESOLVED にならない。"
                            " 一覧: nbb scripts/repo-bots/tick.cljs --report"))))
          (js/process.exit 0))

        (flag "--report")
        (do (report! registry prev) (js/process.exit 0))
        :else
        (if-not (acquire-lock!)
          (do (println "REFUSED\t別の tick が走っている:" lock-file)
              (js/process.exit 2))
          ;; exit は lock を外した後にだけ呼ぶ。js/process.exit は finally を
          ;; 飛ばすので、run-wave! の中で exit していた版は lock を握ったまま
          ;; 抜けていた（実測: 次の tick が 30 分 REFUSED になる）。
          (let [code (try (run-wave! registry prev)
                          (catch :default e
                            (println "REFUSED\t波の途中で落ちた:" (str e)) 2)
                          (finally (release-lock!)))]
            (js/process.exit code)))))))
