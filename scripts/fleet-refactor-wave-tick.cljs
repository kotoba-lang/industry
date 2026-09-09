#!/usr/bin/env nbb
;; scripts/fleet-refactor-wave-tick.cljs — orgs/cloud-itonami/ 向け refactor 波の
;; 候補を**測る**（ADR-2608290100）。決定論。モデルを起こさない。
;;
;;   nbb --classpath ".:scripts/nbb_compat" scripts/fleet-refactor-wave-tick.cljs \
;;     [--limit 4] [--mission a|b|both]
;;
;; 姉妹 tick（svelte-cljs-wave-tick.cljs）と同型。手順の正本は skill
;; `fleet-refactor-wave` であって、ここではない（2 箇所に書くと必ず片方が古くなる）。
;;
;; ## 2 mission
;;
;; Mission A（D1 premise 除去、ADR-2608039000）と Mission B（clj/cljc → .kotoba、
;; ADR-2608261100）を同じ tick で扱う。判定基準そのものはここで再定義しない ——
;; `orgs/cloud-itonami/loop-fleet-refactor-wave` の `src/loop_fleet_refactor_wave/
;; mission_{a,b}.cljc` が正本の実装で、このファイルの判定関数はその
;; **verbatim な写し**。判定ロジックを変えるときは両方を同じ commit で変える。
;;
;; ## なぜ classpath require ではなく複製か
;;
;; 最初は companion repo を classpath に足して require する設計だった。実測
;; 2026-08-29: 新しく west 登録した project は、登録した直後の他マシン/他
;; worktree では **checked out されていない**（`west update` を別途要る）。
;; その状態で require が失敗すると nbb は `-main` に達する前に例外で落ち、
;; 生成される exit code は 1（「測れなかった」の専用 exit 2 ではない）。この
;; tick は svelte-cljs-wave-tick.cljs と同じく自己完結にし、companion repo が
;; 未 checkout でも exit 2 を正しく返せるようにする。
;;
;; ## なぜ 1 回の find で全 repo を走査するか（repo ごとの find/grep にしない）
;;
;; 実測 2026-08-29: `orgs/cloud-itonami/` は 1,857 repo が実在する（west 管理、
;; 共有 checkout）。repo ごとに `find`/`grep` を subprocess spawn すると
;; 1,857 × 数回 = 万単位の spawn になり、この機械（並行 agent が多数走る負荷の
;; 高いワークステーション）では非現実的に遅い。svelte-cljs-wave-tick.cljs の
;; `scan!`（1 回の find で全ファイルを tmp file に落として読む）と同じ形にする。
;;
;; ## exit code
;;
;;   0  測れた（候補 0 本でも 0。プールが在って候補が無いのは legitimate な
;;      状態 — 例えば Mission A は 2026-08-29 実測で orgs/cloud-itonami/ 配下
;;      candidate 0 件だった。これは gate ではなく測定器）。
;;   2  **測れなかった** —— orgs/cloud-itonami/ が無い、find が失敗した、
;;      pool（repo 数）が 0。0 と 2 を区別する意味がそこにある（ADR-2608136000）。

(ns fleet-refactor-wave-tick
  (:require [clojure.string :as str]
            [clojure.edn :as edn]))

;; ─────────────── Mission A 判定（…mission_a.cljc の写し） ──────────────────

(defn- decentralization-claim? [text]
  (boolean (re-find #"(?i)(decentrali[sz]|blockchain|分散)" (or text ""))))

(defn- d1-binding? [wrangler-text]
  (boolean (re-find #"d1_databases" (or wrangler-text ""))))

(defn- cas-arbiter-signal? [source-text]
  (boolean
   (re-find #"(?i)(WHERE\s+sequence|onlyIf\.etagMatches|If-Match|head[_-]?db|conditional[_-]?write|cas[_-]?arbiter|ref[_-]?plane)"
            (or source-text ""))))

(defn- mission-a-candidate? [{:keys [readme-text wrangler-text source-text]}]
  (and (decentralization-claim? readme-text)
       (d1-binding? wrangler-text)
       (cas-arbiter-signal? source-text)))

;; ─────────────── Mission B 判定（…mission_b.cljc の写し） ─────────────────

(defn- custody-gated? [script-text]
  (boolean (re-find #"(?i)(migration\.edn|svelte/)" (or script-text ""))))

(defn- kotoba-twin-exists? [clj-relative-path kotoba-relative-paths]
  (let [stem (some-> clj-relative-path (str/replace #"\.cljc?$" ""))]
    (boolean (some #(str/includes? % stem) kotoba-relative-paths))))

(defn- host-mechanism-signal? [source-text]
  (boolean
   (re-find #"(?i)(clj-http|hato\.client|babashka\.http-client|babashka\.process|babashka\.fs|java\.net\.Socket|fs/readFileSync|fs/writeFileSync|process/exec|System/getenv|System/exit|clojure\.java\.io|\.execFileSync|\.spawnSync|\bslurp\b|\bspit\b)"
            (or source-text ""))))

(defn- operational-script-signal?
  "Real candidates found 2026-08-29 (production run against orgs/cloud-itonami/,
   1,857 repos): 4-line run_tests.clj files (require + run-tests + System/exit,
   zero product decisions) ranked as the smallest -- therefore highest-priority
   -- candidates. line-count<=400 alone does not separate 'small and pure' from
   'small and empty of decisions'; requiring defn-count>=1 (below) and rejecting
   runner basenames here both do."
  [path source-text]
  (or (boolean (re-find #"(?i)(run[_-]?tests?|test[_-]?runner|runner)\.(clj|cljc)$" (or path "")))
      (boolean (re-find #"clojure\.test/run-tests|cljs\.test/run-tests" (or source-text "")))))

(defn- host-boundary-path?
  "path が host/ ディレクトリ・ns セグメントに在るか。ADR-2607279200 の 4 分類で
   `kotoba/host` は ambient authority を**保つ**側なので、repo 自身が host/ に
   置いたファイルは「これは mechanism だ」と宣言している。

   実測 2026-08-29: `tadori/src/tadori/host/http.clj`（7 行・defn 1 個・docstring
   に \"Network authority terminates here.\"）を host-mechanism-signal? が
   取り逃した —— `babashka.http-client` を alias `http` で使っており source 側の
   列挙に無かったため。**client library の列挙は原理的に完成しない**ので、path を
   見る方が堅い。"
  [path]
  (boolean (re-find #"(?:^|/)host/|\.host\." (or path ""))))

(defn- unactivated-scaffold?
  "全ての `defn` の body の**先頭**が無条件 `throw` か = 未 activate の R0 scaffold。
   移す product semantics が無い —— 移植すると定数 `[:result _ E]` を返す
   `.kotoba` になり、**挙動ゼロの diff が「移行済み」として数えられる**。

   実測 2026-08-29: `hikari/cells/*/state_machine.cljc` 3 本
   （`(defn solve [_state] (throw (ex-info \"R0 scaffold ... not activated\" ...)))`）
   が候補 4 枠のうち 3 つを占めた。この形は fleet 全体で tick の line-count 範囲に
   111 件あり、**正当に着地できないので pool から消えず、毎周同じものが再提案される**
   （loop が進まず空回りする）。

   operational-script-signal? が記録しているのと同じ欠陥クラス（'small and empty of
   decisions'）に別経路で到達したもの。判定は意図的に狭く、**throw が body の
   先頭形でなければならない** —— `when-not`/`cond` の中の防御的 throw は match
   しない。2026-08-29 に実ファイルで**両方向**確認済み: 上記 3 本は match し、
   防御的 throw を計 3 個持つ実装済み decision core である `grid_edge` と
   `solar_pv_install` は match しない。"
  [source-text]
  (let [src (or source-text "")
        defns (count (re-seq #"\(defn-?\s" src))
        throw-first (count (re-seq #"\(defn-?\s+[^\s\[\]]+\s+(?:\^\S+\s+)?(?:\"(?:[^\"\\]|\\.)*\"\s+)?\[[^\]]*\]\s*\(throw[\s(]" src))]
    (and (pos? defns) (= defns throw-first))))

(defn- strip-ns-form [source-text]
  (str/replace (or source-text "") #"(?s)^.*?\(ns\s.*?\n\n" ""))

(defn- decision-free-passthrough?
  "Every defn is a pass-through into another namespace and the file decides
   nothing of its own. Its product semantics live in the delegate, not here.

   Found 2026-08-30 by production run: the wave surfaced exactly one candidate,
   `mio/src/mio/methods/social.cljc` (13 lines, two defns, both delegating into
   `etzhayyim.social.publication`, which has no .kotoba twin in the fleet).
   Porting it cannot produce a migration -- either the delegate is still .cljc,
   which the workspace rule forbids migrating against, or the port reproduces
   the same pass-through: a zero-behaviour diff counted as a landed slice.

   Measured 2026-08-30 across orgs/cloud-itonami: 658 files survive every other
   predicate here and still match this one, and ALL TWENTY of the smallest
   survivors are of this shape. `mission-b-candidates` sorts by ascending line
   count, so the class occupies the entire front of the queue -- every wave
   would draw its full slate from files that cannot land, and redraw them
   forever.

   Third instance of the class `operational-script-signal?` names ('small and
   empty of decisions'), after `unactivated-scaffold?`. Verified both
   directions against real files: matches `mio/methods/social.cljc`, the
   13-line social_post adapters in amime / kaname / kenchi / kuni-umi /
   actor-hoshimori, infra-utility-connect's four 12-line cells, and
   cloud-itonami-app's `health.cljc` (whose own docstring reads \"decide
   nothing\"); does not match hikari's `grid_edge` / `solar_pv_install`, nor
   mio's own `reward.cljc` / `analyze.cljc`, which delegate but also decide."
  [source-text]
  (let [src (strip-ns-form source-text)
        defns (count (re-seq #"\(defn-?\s" src))
        ns-calls (count (re-seq #"\([a-zA-Z][a-zA-Z0-9_.\-]*/[a-zA-Z0-9_.\-!?*<>=+]+[\s)]" src))
        decisions (count (re-seq #"\((?:if|if-not|if-let|when|when-not|when-let|cond|condp|case|and|or|not|=|not=|<|>|<=|>=|\+|-|\*|/|min|max|count|filter|remove|reduce|some|every\?)[\s)]" src))]
    (and (pos? defns) (pos? ns-calls) (zero? decisions))))

(defn- defn-count [source-text]
  (count (re-seq #"\(defn-?\s" (or source-text ""))))

(defn- candidate-slice? [{:keys [line-count custody-gated? has-kotoba-twin? host-mechanism?
                                  operational-script? host-boundary? unactivated-scaffold?
                                  decision-free-passthrough? defn-count]}]
  (and (not custody-gated?)
       (not has-kotoba-twin?)
       (not host-mechanism?)
       (not operational-script?)
       (not host-boundary?)
       (not unactivated-scaffold?)
       (not decision-free-passthrough?)
       (pos? (or defn-count 0))
       (pos? line-count)
       (<= line-count 400)))

;; ───────────────────────────── I/O ──────────────────────────────

(def fs (js/require "fs"))
(def cp (js/require "child_process"))
(def os (js/require "os"))
(def path (js/require "path"))

(def home (.homedir os))
(def root (or (aget (.-env js/process) "COM_JUNKAWASAKI_ROOT")
              (str home "/github/com-junkawasaki")))
(def ledger-file (str home "/.itonami/fleet-refactor-wave-tick.ledger.edn"))
(def fleet-dir "orgs/cloud-itonami")

(def args (vec *command-line-args*))
(defn- arg [flag default]
  (if-let [i (first (keep-indexed #(when (= %2 flag) %1) args))]
    (nth args (inc i))
    default))
(def limit (js/parseInt (arg "--limit" "4") 10))
(def mission-filter (arg "--mission" "both")) ; "a" | "b" | "both"

(defn- read-file [rel]
  (try (str (.readFileSync fs (path.join root rel) "utf8")) (catch :default _ nil)))

(defn- repo-of
  "orgs/cloud-itonami/<repo>/... の <repo> を取り出す。"
  [rel]
  (nth (str/split rel #"/") 2 nil))

(defn- worktree-artifact? [rel]
  (or (str/includes? (str "/" rel) "/.claude/worktrees/")
      ;; stale linked worktree（repo 名が "." で始まる）: svelte-cljs-wave-tick.cljs
      ;; の実測知見と同じ罠 — 掃き出し先を repo として二重に数えない。
      (str/starts-with? (str (repo-of rel)) ".")))

(defn- scan!
  "orgs/cloud-itonami/ を 1 パス走査して、対象拡張子の rel-path を返す。
   失敗時 nil。find の stdout を execFileSync でパイプ受けすると、負荷の高い
   機械では遅くなる（svelte-cljs-wave-tick.cljs の実測知見）ので、ファイルに
   落として読む。"
  []
  (let [tmp (path.join (or (aget (.-env js/process) "TMPDIR") "/tmp")
                       (str "frw-" (.getTime (js/Date.)) ".txt"))
        find-args ["orgs/cloud-itonami"
                   "(" "-name" "node_modules" "-o" "-name" ".git" "-o" "-name" "dist"
                   "-o" "-name" "build" "-o" "-name" "out" "-o" "-name" "target"
                   "-o" "-name" "test" "-o" "-name" ".shadow-cljs" "-o" "-path" "*/.claude/worktrees/*"
                   ")" "-prune" "-o"
                   "(" "-name" "*.clj" "-o" "-name" "*.cljc" "-o" "-name" "*.kotoba"
                   "-o" "-name" "*.cljs" "-o" "-name" "wrangler.jsonc" "-o" "-name" "wrangler.toml"
                   "-o" "-name" "wrangler.json" "-o" "-name" "README.md" "-o" "-name" "README.edn"
                   ")" "-type" "f" "-print"]]
    (try
      (let [fd (.openSync fs tmp "w")]
        (try (.execFileSync cp "find" (clj->js find-args)
                            #js {:cwd root :stdio #js ["ignore" fd "ignore"] :timeout 900000})
             (finally (.closeSync fs fd)))
        (let [rels (->> (str/split-lines (str (.readFileSync fs tmp "utf8")))
                        (remove str/blank?)
                        (remove worktree-artifact?)
                        vec)]
          (.unlinkSync fs tmp)
          rels))
      (catch :default _ nil))))

;; ───────────────────────── in-flight ─────────────────────────

(defn- sh [cmd cmd-args opts]
  (try
    (let [r (.spawnSync cp cmd (clj->js cmd-args)
                        (clj->js (merge {:encoding "utf8" :cwd root :timeout 30000} opts)))]
      {:code (aget r "status") :out (str (aget r "stdout")) :err (str (aget r "stderr"))})
    (catch :default e {:code nil :out "" :err (str e)})))

(defn- remote-name [repo-name]
  (let [{:keys [out]} (sh "git" ["-C" (path.join fleet-dir repo-name) "remote"] {})
        names (->> (str/split-lines out) (remove str/blank?) set)]
    (cond (contains? names "cloud-itonami") "cloud-itonami"
          (contains? names "origin") "origin"
          :else (first (sort names)))))

(defn- has-linked-worktree? [repo-name]
  (let [{:keys [code out]} (sh "git" ["-C" (path.join fleet-dir repo-name) "worktree" "list" "--porcelain"] {})]
    (if (not= 0 (or code -1))
      true
      (> (count (re-seq #"(?m)^worktree " out)) 1))))

(defn- in-flight [repo-name branch-name]
  (if (has-linked-worktree? repo-name)
    :worktree
    (if-let [rem (remote-name repo-name)]
      (let [{:keys [code out]} (sh "git" ["-C" (path.join fleet-dir repo-name)
                                          "ls-remote" "--heads" rem branch-name] {})]
        (cond (not= 0 (or code -1)) :unmeasured
              (str/blank? out) nil
              :else :branch))
      :unmeasured)))

;; ───────────────── Q9 authority（Mission B の入場判定）─────────────────
;;
;; Mission B が提案してよいかは、この tick が決めることではない。機械正本は
;; `kotoba-lang/kotoba-lang` の `lang/q9-migration.edn`（CLAUDE.md「Q9 の移行単位」
;; 節が名指しで authority と呼んでいるファイル）。
;;
;; **なぜ後から足したか。** 2026-08-30 12:02/12:16 に authority が v3 になり
;; (a) `:scope :decision-only-extraction-forbidden true` (b) `:current-decision
;; :authorized-waves #{:wave-0 :wave-1}`（cloud-itonami は wave-4）が入った。
;; この tick の Mission B ヒューリスティクスは ADR-2608290100 Decision §3 が
;; 書いているとおり「小さい自己完結 decision core の発見」で、(a) が名指しで
;; 禁じている形そのもの。同日 06:30/06:32 に 2 本の decision core が landed
;; しており（isic-3812 / kotoba-erp）、それらは authority の
;; `:legacy-decision-cores {:status :historical-evidence-only :expansion false}`
;; に当たる。
;;
;; gate が無ければこの tick は**毎時 forbidden な仕事を 4 本提案し続ける**。
;; SKILL.md は候補をそのまま fresh agent に配れと書いてあるので、提案は実行に
;; なる。これは ADR-2608136000 の 2 問目（「そもそも実行できないとき何を返すか。
;; pass と同じ値なら欠陥」）の Mission B 版 —— 「許可されているか測っていない」が
;; 「測って許可されていた」と同じ出力（候補 4 本）になっていた。
;;
;; authority が**読めなかったときは候補を出さない**（exit 2）。読めない = 許可
;; されているかを測っていない、であって許可ではない。

(def ^:private q9-authority-rel "lang/q9-migration.edn")
(def ^:private q9-authority-repo "orgs/kotoba-lang/kotoba-lang")
(def ^:private fleet-org "cloud-itonami")

(defn- q9-authority
  "kotoba-lang の **origin/main** から authority を読む。checkout は pin の分だけ
   遅れるので checkout のファイルを読まない（CLAUDE.md「結論を出す前に origin/main
   を読む」。実測 2026-08-30: checkout は 4 commit 遅れており、そこには v3 の
   禁止条項が 1 つも無かった）。

   返り値 {:ok <edn>} / {:unmeasured <reason>}。"
  []
  (let [{:keys [code out err]}
        (sh "git" ["-C" q9-authority-repo "show" (str "origin/main:" q9-authority-rel)] {})]
    (cond
      (not= 0 (or code -1))
      {:unmeasured (str q9-authority-repo " の origin/main から " q9-authority-rel
                        " を読めない: " (str/trim (str err)))}
      (str/blank? out)
      {:unmeasured (str q9-authority-rel " が空")}
      :else
      (try
        (let [m (edn/read-string out)]
          (if (map? m)
            {:ok m}
            {:unmeasured (str q9-authority-rel " が map ではない")}))
        (catch :default e
          {:unmeasured (str q9-authority-rel " を読めない (EDN): " (str e))})))))

(defn- mission-b-block-reasons
  "authority が Mission B を禁じている理由を**全部**返す（空 = 許可）。
   cond で最初の 1 つだけを返さないのは、片方が解除されたときに残りが残ることを
   出力から読めるようにするため。"
  [auth]
  (let [scope (:scope auth)
        waves (:waves auth)
        org-wave (some (fn [[k v]]
                         (when (str/includes? (str (:name v)) fleet-org) k))
                       waves)
        authorized (or (get-in auth [:current-decision :authorized-waves]) #{})]
    (cond-> []
      (:decision-only-extraction-forbidden scope)
      (conj (str ":scope :decision-only-extraction-forbidden true — この tick の "
                 "Mission B は decision core を探す（ADR-2608290100 Decision §3）"))

      (nil? org-wave)
      (conj (str "authority の :waves に " fleet-org " を名指す wave が無い"))

      (and (some? org-wave) (not (contains? authorized org-wave)))
      (conj (str org-wave " (" (:name (get waves org-wave)) ") が :authorized-waves "
                 (pr-str authorized) " に無い — :status "
                 (pr-str (:status (get waves org-wave))))))))

;; ───────────────────────── candidate assembly ─────────────────────────

(defn- mission-a-candidates
  "readme-by-repo / wrangler-text-by-repo / source-text-by-repo（同じ repo の
   .ts/.js/.clj/.cljc/.cljs を連結したもの、arbiter grep 代わりに全文検索）
   から Mission A 候補を作る。source は README+wrangler の 2 条件が揃った repo
   だけに絞ってから合成する（コストの高い連結を全 repo に対して行わない）。"
  [by-repo]
  (->> by-repo
       (keep (fn [[repo-name files]]
               (let [readme (some #(when (re-find #"README\.(md|edn)$" %) (read-file %)) files)
                     wrangler-files (filter #(re-find #"wrangler\.(jsonc|toml|json)$" %) files)
                     wrangler-text (apply str (keep read-file wrangler-files))]
                 (when (and (decentralization-claim? readme) (d1-binding? wrangler-text))
                   (let [src-files (filter #(re-find #"\.(clj|cljc|cljs)$" %) files)
                         source-text (apply str (keep read-file src-files))]
                     (when (mission-a-candidate? {:readme-text readme
                                                  :wrangler-text wrangler-text
                                                  :source-text source-text})
                       {:repo (str fleet-dir "/" repo-name)
                        :org "cloud-itonami"
                        :name repo-name
                        :mission :a}))))))
       vec))

(defn- mission-b-candidates
  [by-repo]
  (->> by-repo
       (mapcat (fn [[repo-name files]]
                 (let [scripts (filter #(and (re-find #"\.cljs$" %) (re-find #"/(docs|scripts)/" %)) files)
                       custody? (some #(custody-gated? (read-file %)) scripts)]
                   (when-not custody?
                     (let [clj-files (filter #(re-find #"\.(clj|cljc)$" %) files)
                           kotoba-files (filter #(re-find #"\.kotoba$" %) files)]
                       (->> clj-files
                            (keep (fn [f]
                                    (let [text (read-file f)
                                          line-count (if text (count (str/split-lines text)) 0)
                                          has-twin? (kotoba-twin-exists? f kotoba-files)
                                          host-sig? (host-mechanism-signal? text)
                                          op-sig? (operational-script-signal? f text)
                                          host-path? (host-boundary-path? f)
                                          scaffold? (unactivated-scaffold? text)
                                          passthrough? (decision-free-passthrough? text)
                                          n-defn (defn-count text)]
                                      (when (candidate-slice? {:line-count line-count
                                                               :custody-gated? false
                                                               :has-kotoba-twin? has-twin?
                                                               :host-mechanism? host-sig?
                                                               :operational-script? op-sig?
                                                               :host-boundary? host-path?
                                                               :unactivated-scaffold? scaffold?
                                                               :decision-free-passthrough? passthrough?
                                                               :defn-count n-defn})
                                        {:repo (str fleet-dir "/" repo-name)
                                         :org "cloud-itonami"
                                         :name repo-name
                                         :mission :b
                                         :file f
                                         :lines line-count})))))))))
                 )
       (sort-by :lines)
       vec))

;; ───────────────────────── main ─────────────────────────

(defn -main []
  (let [rels (scan!)]
    (when (nil? rels)
      (println "REFUSING\tfind(1) failed — the scan did not run")
      (println "SCANNED\t0")
      (js/process.exit 2))

    (let [by-repo (reduce (fn [m r] (update m (repo-of r) (fnil conj []) r)) {} rels)
          pool (count by-repo)]
      (when (zero? pool)
        (println (str "REFUSING\t" fleet-dir " に repo が 0 件 — walk が壊れている"
                      "（cloud-itonami fleet は 100+ repo を持つはず）"))
        (println "SCANNED\t0")
        (js/process.exit 2))

      (let [want-a (contains? #{"a" "both"} mission-filter)
            want-b-asked (contains? #{"b" "both"} mission-filter)
            ;; Mission B は authority に訊いてから測る。禁じられている形を
            ;; 毎周測り直しても行動できる出力にはならないので、走査ごと省く。
            auth (when want-b-asked (q9-authority))
            b-blocked (when (:ok auth) (mission-b-block-reasons (:ok auth)))]
        (when (:unmeasured auth)
          (println (str "REFUSING\tQ9 authority を読めない —— Mission B が許可されて"
                        "いるかを測っていない。候補は出さない"))
          (println (str "REASON\t" (:unmeasured auth)))
          (println (str "SCANNED\t" (count rels) "\tfiles under " fleet-dir))
          (println (str "POOL\t" pool "\trepos"))
          (js/process.exit 2))

        (let [want-b (and want-b-asked (empty? b-blocked))
            a-cands (if want-a (mission-a-candidates by-repo) [])
            b-cands (if want-b (mission-b-candidates by-repo) [])
            all-raw (->> (concat a-cands b-cands)
                         (sort-by (juxt :mission (fnil :lines 0) :repo))
                         vec)
            ranked (take (* 4 limit) all-raw)
            skipped (atom [])
            unmeasured (atom [])
            picked (->> ranked
                        (remove (fn [c]
                                  (let [branch (if (= :a (:mission c))
                                                 "agent/d1-premise-fix"
                                                 "agent/kotoba-migration")]
                                    (case (in-flight (:name c) branch)
                                      nil false
                                      :unmeasured (do (swap! unmeasured conj (:repo c)) true)
                                      (do (swap! skipped conj (:repo c)) true)))))
                        (take limit)
                        vec)
            rec {:at (.toISOString (js/Date.))
                 :pool pool
                 :files (count rels)
                 :mission-a-raw (count a-cands)
                 :mission-b-raw (if want-b (count b-cands) :not-measured)
                 :mission-b-blocked (vec (or b-blocked []))
                 :limit limit
                 :in-flight-skipped @skipped
                 :unmeasured-skipped @unmeasured
                 :candidates picked}]

        (println (str "SCANNED\t" (count rels) "\tfiles under " fleet-dir))
        (println (str "POOL\t" pool "\trepos"))
        (println (str "MISSION-A-RAW\t" (count a-cands)))
        (if want-b
          (println (str "MISSION-B-RAW\t" (count b-cands)))
          (println (str "MISSION-B-RAW\tnot-measured"
                        (when-not want-b-asked "\t(--mission a)"))))
        (doseq [r (or b-blocked [])]
          (println (str "MISSION-B-BLOCKED\t" r)))
        (println (str "IN-FLIGHT-SKIPPED\t" (count @skipped)
                      (when (seq @skipped) (str "\t" (str/join " " @skipped)))))
        (println (str "UNMEASURED-SKIPPED\t" (count @unmeasured)
                      (when (seq @unmeasured) (str "\t" (str/join " " @unmeasured)))))
        (println (str "CANDIDATES\t" (count picked)))
        (doseq [c picked]
          (println (str "  " (name (:mission c)) "\t" (:repo c)
                        (when (:file c) (str "\t" (:file c) "\t" (:lines c) "L")))))
        (try (.appendFileSync fs ledger-file (str (pr-str rec) "\n"))
             (catch :default e (println "ledger 追記失敗:" (str e))))
        (js/process.exit 0))))))

(-main)
