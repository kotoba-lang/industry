#!/usr/bin/env nbb
;; observatory-run.cljs — 領域別 observatory を実際に走らせ、何が起きたかを
;; query 面に載る EDN として記録する。ADR-2608081200。
;;
;; ## なぜ superproject 側にこれがあるか
;;
;; 各 actor の正本（charter / gate / schema）はその repo 自身。ここが持つのは
;; **運用** —— どの runtime で叩くか、workspace が materialize されているか、
;; 実際に出力が伸びたか、伸びなかったのは正常か異常か。
;; `scripts/newsfeed-ingest.cljs`（ADR-2608031900）が置いた境界と同じ。
;;
;; ## 不変条件（newsfeed-ingest から引き継ぐ）
;;
;; 1. **exit 0 を成功の証拠にしない。** 子プロセスの exit code に加えて、
;;    :produces が実際に伸びたかを測る。**特にパイプ越しの `$?` を見ない** ——
;;    実測 2026-08-08、`java … | tail` の `$?` は tail の exit で、落ちた actor が
;;    exit=0 に見えていた。spawnSync で子の status を直接取る。
;; 2. **checkout が無ければ黙って clone しない。** west 管理下の path を勝手に
;;    作ると他セッションと衝突する。:absent として報告する。
;; 3. **下振れしたら exit 1。** registry の :expect より悪い結果は失敗。
;;    :known-broken が動き出した場合も報告する（登録簿を直させるため）。
;; 4. **出力の全文を query 面に載せない。** actor の datom log は各 repo の
;;    ローカル台帳（多くは .gitignore 済み）。載せるのは観測サマリだけ。
;;
;; ## 使い方
;;
;;   nbb --classpath ".:scripts/nbb_compat" scripts/observatory-run.cljs
;;   nbb --classpath ".:scripts/nbb_compat" scripts/observatory-run.cljs --only watari,sukashi
;;   nbb --classpath ".:scripts/nbb_compat" scripts/observatory-run.cljs --check
;;   nbb --classpath ".:scripts/nbb_compat" scripts/observatory-run.cljs --live kouhou
;;
;;   --check  走らせない。登録簿の整合（west.yml に在るか / checkout が在るか）だけ見る
;;   --live   :live-alias を持つ actor を実 fetch/publish モードで走らせる（明示 opt-in）

(require '[scripts.nbb-compat :as compat :refer [slurp spit sh]]
         '[clojure.edn :as edn]
         '[clojure.string :as str])

(def fs (js/require "node:fs"))
(def path-mod (js/require "node:path"))
(def cp (js/require "node:child_process"))

(def root (str/trim (:out (sh "git" "rev-parse" "--show-toplevel"))))

(defn- abs [& parts] (apply (.-join path-mod) (clj->js (cons root parts))))
(defn- exists? [p] (.existsSync fs p))

;; nbb の process.argv には `--classpath` とその値、script 自身のパスも入る
;; （実測: drop 2 で ["--classpath" ".:scripts/nbb_compat" "…/observatory-run.cljs" …]）。
;; 手で drop すると位置引数を誤読するので、gate 側と同じく *command-line-args* を使う。
(def argv (vec *command-line-args*))
(defn- flag? [f] (some #{f} argv))
(defn- opt [f] (let [i (.indexOf (clj->js argv) f)] (when (>= i 0) (nth argv (inc i) nil))))

(def check-only? (flag? "--check"))
(def only (when-let [v (opt "--only")] (set (str/split v #","))))
(def live (when-let [v (opt "--live")] (set (str/split v #","))))

;; --registry は gate の自己検査用（『この gate は落ちるのか』を別の登録簿で
;; 確かめるため）。運用では既定の manifest/observatories.edn を使う。
(def registry
  (edn/read-string (slurp (or (opt "--registry") (abs "manifest" "observatories.edn")))))
(def obs (:observatories registry))

;; ── west.yml との整合（登録簿が実在しない repo を指していないか）─────────────
;; west.yml は 4,000 project の生成物なので全体を parse せず、`name:` 行だけ拾う。
(def west-names
  (into #{} (map #(str/trim (second %)))
        (re-seq #"(?m)^    - name: (\S+)$" (slurp (abs "manifest" "west.yml")))))

;; ── 実行 ────────────────────────────────────────────────────────────────
(defn- repo-dir [o] (abs "orgs" (:org o) (:name o)))

(defn- expand
  "${REPO} を checkout の絶対パスに展開する。相対パスを禁じるための唯一の経路。"
  [s dir]
  (str/replace s "${REPO}" dir))

(defn- measure
  ":produces の大きさと**最終更新時刻**を測る。ファイルなら bytes と行数、
   ディレクトリならファイル数と合計 bytes。無ければ nil ではなく 0 を返す
   （『測ったが 0』と『測っていない』を混同しない — 後者は :produces が nil のとき）。

   :mtime を測るのは、**古い出力が残っているだけの actor を『動いた』と
   数えないため**。実測 2026-08-08: shionome / mitooshi は out/ を上書きするので
   bytes が変わらず、bytes 差分だけ見ていると前回の遺物で合格してしまった。"
  [dir produces]
  (when produces
    (let [p ((.-join path-mod) dir produces)]
      (cond
        (not (exists? p)) {:bytes 0 :units 0 :kind :missing :mtime 0}
        (.isDirectory (.statSync fs p))
        (let [fs* (vec (.readdirSync fs p))
              stats (for [f fs*
                          :let [q ((.-join path-mod) p f)]
                          :when (.isFile (.statSync fs q))]
                      (.statSync fs q))]
          {:kind :dir :units (count fs*)
           :bytes (reduce + 0 (map #(.-size %) stats))
           :mtime (reduce max 0 (map #(.getTime (.-mtime %)) stats))})
        :else
        (let [st (.statSync fs p)]
          {:kind :file
           :bytes (.-size st)
           :mtime (.getTime (.-mtime st))
           :units (count (remove str/blank? (str/split-lines (slurp p))))})))))

(defn- run-one
  "1 actor を起動して {:exit :out :err} を返す。**パイプを挟まない** ——
   子の status を直接読む（パイプ越しの $? を見ないという不変条件 1）。"
  [o dir live?]
  (let [tmo (:timeout-ms registry 600000)
        aliases (:aliases o)
        live-alias (when live? (:live-alias o))
        [cmd args]
        (case (:runtime o)
          :clojure
          (let [a (cond
                    ;; live 実行は通常の alias に :live-alias を足して起動する
                    live-alias [(str "-M:" (str/join ":" (concat aliases [live-alias])))]
                    (seq aliases) [(str "-M:" (str/join ":" aliases))]
                    :else ["-M" "-m" (:main o)])]
            ["clojure" (into a (map #(expand % dir) (:args o)))])
          :nbb ["nbb" (into [(:main o)] (map #(expand % dir) (:args o)))]
          ;; :node（yabai / collector）は未実装。**exit 127 は「その actor が
          ;; 落ちた」ではなく「こちらが起動方法を持っていない」という意味**なので、
          ;; 登録簿の :blocked-by でその差を明示すること。実装するのは repo 側の
          ;; ブロッカーが解けてから —— 起動できないものに runner を先回りで足しても
          ;; 検証できない。
          [nil nil])]
    (if (nil? cmd)
      {:exit 127 :out ""
       :err (str "unknown runtime " (:runtime o)
                 " — observatory-run が起動方法を持っていない（actor 側の失敗ではない）")}
      ;; process.env はプレーンな JS object ではないので js->clj では変換できない
      ;; （nbb-compat の getenv-all がそのために在る）。
      (let [env (merge (compat/getenv-all)
                       ;; JAVA_TOOL_OPTIONS の proxy banner が stdout を汚すので黙らせる。
                       {"JAVA_TOOL_OPTIONS" ""}
                       (when live? (:live-env o)))
            r (.spawnSync cp cmd (clj->js args)
                          (clj->js {:cwd dir :encoding "utf8" :timeout tmo
                                    :maxBuffer (* 64 1024 1024) :env env}))]
        {:exit (if (nil? (.-status r)) 124 (.-status r)) ; null = timeout/signal
         :out (or (.-stdout r) "")
         :err (or (.-stderr r) "")}))))

(defn- chain-ok?
  "actor が chain 検証を印字していれば拾う。印字しない actor では nil
   （『検証していない』を『壊れている』と混同しない）。

   ⚠ **偽を先に見る。** actor によって印字が `chain OK` だったり
   `chain={:ok true, :length 1, :broken-at -1}` だったりする（実測 2026-08-08:
   inochi / rasen / busshi は後者）。真のパターンを先に当てると
   `{:ok false, :broken-at 3}` の `:broken-at` に引っかかる余地を残すので、
   **壊れている側を先に判定する**。"
  [out]
  (cond
    (re-find #"BROKEN|ok=false|:ok false" out) false
    (re-find #"chain OK|chain ok=true|ok=true|:ok true" out) true
    :else nil))

(defn- classify
  "実測を :expect の語彙に落とす。

   `t0` は起動直前の時刻。**この run で書かれた出力だけを産出として数える** ——
   古い out/ が残っているだけの actor を『動いた』にしない。append-only の
   datom log は bytes が伸びること、上書き型の出力は mtime が新しいことを見る。"
  [{:keys [exit]} before after produces t0]
  ;; mtime の粒度が 1 秒のファイルシステムがあるので 2 秒の余裕を持たせる。
  ;; **偽の FAIL の方が偽の PASS より悪い** —— flaky な gate は無視されるようになり、
  ;; そうなれば gate が無いのと同じ。実運用で actor は数秒かかるので、この余裕で
  ;; 前回の出力を「新しい」と誤認することはない。
  (let [fresh? (>= (:mtime after 0) (- t0 2000))]
    (cond
      (not (zero? exit)) :known-broken
      (nil? produces) :runs-ok
      ;; append-only 台帳: 伸びたか
      (> (:bytes after 0) (:bytes before 0))
      (if (= :file (:kind after)) :produces-datoms :produces-files)
      ;; 上書き型: この run で書き直されたか
      (and fresh? (pos? (:bytes after 0))) :produces-files
      ;; 冪等な append-only 台帳: seed から導いた datom 集合が前回と同じなら
      ;; **何も足さないのが正常**（actor 自身が appended=false (no-change) と印字する）。
      ;; 実測 2026-08-08: inochi / rasen / busshi は 2 周目で伸びない。これを
      ;; :runs-empty（＝ 0 件・設定不足）と同じ扱いにすると、正常な 2 周目が毎回
      ;; 下振れ扱いになり、gate が常時赤 = 無視される gate になる。
      ;; run のあとに台帳が実在して中身があることは要求する。ただし**台帳を消しても
      ;; seed から作り直す**ので、それだけでは落ちない（実測: 消すと Δbytes>0 で
      ;; :produces-datoms になり合格）。落ちるのは actor が実際に失敗した時
      ;; （実測: seed を壊すと exit 1 → :known-broken → 下振れで FAIL）。
      (and (= :file (:kind after)) (pos? (:bytes after 0)))
      :produces-datoms-idempotent
      :else :runs-empty)))

;; 下位互換の梯子。observed の rank が expect の rank 以上なら合格。
;; :produces-datoms-idempotent を :produces-datoms の**下**に置くのが要点 ——
;; 毎周伸びるはずの watari が伸びなくなったら、それは下振れとして落ちる。
(def rank {:known-broken 0 :runs-empty 1 :runs-ok 2 :produces-files 3
           :produces-datoms-idempotent 4 :produces-datoms 5})

;; ── main ────────────────────────────────────────────────────────────────
(println (str "observatory-run " (.toISOString (js/Date.))
              (when check-only? "  [--check: 走らせない]")))

(def results
  (doall
   (for [o obs
         :when (or (nil? only) (only (:name o)))]
     (let [dir (repo-dir o)
           in-west? (contains? west-names (:name o))
           present? (exists? ((.-join path-mod) dir ".git"))
           live? (boolean (and live (live (:name o)) (:live-alias o)))
           base {:name (:name o) :org (:org o) :domain (:domain o)
                 :expect (:expect o) :in-west in-west? :present present?
                 :blocked-by (:blocked-by o) :live live?}]
       (cond
         (not in-west?)
         (do (println (str "  ✗ " (:name o) " — west.yml に登録が無い"))
             (assoc base :observed :unregistered))

         (not present?)
         (do (println (str "  · " (:name o) " — checkout 無し（clone しない）"))
             (assoc base :observed :absent))

         check-only?
         (do (println (str "  · " (:name o) " — 登録 OK / checkout 有り"))
             (assoc base :observed :not-run))

         :else
         (let [before (measure dir (:produces o))
               t0 (.now js/Date)
               r (run-one o dir live?)
               ms (- (.now js/Date) t0)
               after (measure dir (:produces o))
               observed (classify r before after (:produces o) t0)
               ok? (>= (rank observed 0) (rank (:expect o) 0))
               grew (- (:bytes after 0) (:bytes before 0))]
           (println (str (if ok? "  ✓ " "  ✗ ") (:name o)
                         " — " (name observed)
                         " (expect " (name (:expect o)) ")"
                         " exit=" (:exit r)
                         (when (:produces o) (str " Δbytes=" grew " units=" (:units after 0)))
                         " " (Math/round (/ ms 1000)) "s"))
           (when-not ok?
             (println (str "      " (str/trim (or (last (take 3 (reverse (remove str/blank? (str/split-lines (str (:err r) "\n" (:out r))))))) "")))))
           (assoc base
                  :observed observed :ok ok? :exit (:exit r) :ms ms
                  :bytes-before (:bytes before 0) :bytes-after (:bytes after 0)
                  :units (:units after 0)
                  :chain-ok (chain-ok? (str (:out r) (:err r)))
                  :output-gitignored (boolean (:output-gitignored o))
                  :headline (or (first (remove str/blank? (str/split-lines (:out r)))) ""))))))))

;; ── 台帳を書く ──────────────────────────────────────────────────────────
(def now (.toISOString (js/Date.)))

(defn- datom [i r]
  (cond-> {:db/id (- (inc i))
           :observatory/name (:name r)
           :observatory/org (:org r)
           :observatory/repo (str "orgs/" (:org r) "/" (:name r))
           :observatory/domain (:domain r)
           :observatory/expect (:expect r)
           :observatory/observed (:observed r)
           :observatory/registered-in-west (:in-west r)
           :observatory/checkout-present (:present r)
           :observatory/as-of now
           :source/dataset "observatory"}
    (contains? r :ok) (assoc :observatory/meets-expectation (:ok r))
    (contains? r :exit) (assoc :observatory/exit (:exit r))
    (contains? r :ms) (assoc :observatory/duration-ms (:ms r))
    (contains? r :bytes-after) (assoc :observatory/output-bytes (:bytes-after r)
                                      :observatory/output-delta-bytes
                                      (- (:bytes-after r) (:bytes-before r 0))
                                      :observatory/output-units (:units r))
    (some? (:chain-ok r)) (assoc :observatory/chain-ok (:chain-ok r))
    (:output-gitignored r) (assoc :observatory/output-gitignored true)
    (:blocked-by r) (assoc :observatory/blocked-by (str/replace (:blocked-by r) #"\s+" " "))
    (seq (:headline r)) (assoc :observatory/headline (:headline r))))

(def ran (remove #(#{:not-run :absent :unregistered} (:observed %)) results))
(def failing (remove :ok ran))

(def coverage
  {:db/id -9999
   :observatory/coverage true
   :coverage/registered (count obs)
   :coverage/evaluated (count results)
   :coverage/ran (count ran)
   :coverage/meeting-expectation (count (filter :ok ran))
   :coverage/below-expectation (count failing)
   :coverage/known-broken (count (filter #(= :known-broken (:expect %)) obs))
   :coverage/unmeasured (count (:unmeasured registry))
   ;; **数えていないものを申告する。** 索引に無いことを不在の証拠に使わせない
   ;; （concept 索引の :concept/coverage と同じ立場）。
   :coverage/note (str "unmeasured は『対象外』ではなく『まだ測っていない』。"
                       "登録簿 manifest/observatories.edn の :unmeasured を見ること。")
   :coverage/as-of now
   :source/dataset "observatory"})

(def ledger-path (abs (:ledger registry)))

;; --only / --registry の run は台帳を書かない。**部分実行の結果で全体の台帳を
;; 上書きしない** —— 1 件だけ走らせた結果が「fleet 全体の観測」に化けると、
;; 載っていない actor が消えたように読める（実測 2026-08-08、この保護を入れる前に
;; gate の失敗テストが 13 行の台帳を 1 行に潰した）。
(def partial-run? (or (some? only) (some? (opt "--registry"))))

(when (and partial-run? (not check-only?))
  (println "\n  （--only / --registry の部分実行なので台帳は書かない）"))

(when (and (not check-only?) (not partial-run?))
  (spit ledger-path
        (str ";; 90-docs/observatory/observatory.datoms.edn — **生成物**。手編集禁止。\n"
             ";; 再生成: nbb --classpath \".:scripts/nbb_compat\" scripts/observatory-run.cljs\n"
             ";; 登録簿（正本・手書き）: manifest/observatories.edn   設計: ADR-2608081200\n"
             ";;\n"
             ";; 領域別 observatory を**実際に起動した**結果。README や MATURITY.md の\n"
             ";; 主張ではなく、その時刻に走らせて観測した値だけが入る。\n"
             ";;\n"
             ";; query: :source/dataset \"observatory\"\n"
             ";; join:  :observatory/repo → repo-taxonomy / itonami-maturity の :repo/path\n"
             ";;\n"
             ";; ⚠ :observatory/output-gitignored true の actor は、出力が各 repo の\n"
             ";;   .gitignore に入っている（charter 上のローカル台帳）。**走らせた者の\n"
             ";;   マシンにしか存在しない。** 面に載っているのはここの観測サマリだけ。\n"
             ";;\n"
             ";; ⚠ :observatory/observed :runs-empty は「正常終了したが 0 件」。\n"
             ";;   exit 0 だが成功ではない。:observatory/blocked-by を読むこと。\n\n"
             "[" (str/join "\n " (map pr-str (concat (map-indexed datom results) [coverage]))) "]\n"))
  (println (str "\n  → " (:ledger registry))))

;; ── まとめ ──────────────────────────────────────────────────────────────
(println (str "\n  登録 " (count obs)
              " / 評価 " (count results)
              " / 実行 " (count ran)
              " / 期待どおり " (count (filter :ok ran))
              " / 下振れ " (count failing)
              " / 未測定 " (count (:unmeasured registry))))

;; :known-broken が動き出したら、それも報告する（登録簿を直させるため）。
(doseq [r ran
        :when (and (= :known-broken (:expect r)) (not= :known-broken (:observed r)))]
  (println (str "  ! " (:name r) " は :known-broken だが実際には "
                (name (:observed r)) " した — manifest/observatories.edn を直すこと")))

(when (seq failing)
  (println (str "\nFAIL 下振れ: " (str/join ", " (map :name failing))))
  (compat/exit 1))
