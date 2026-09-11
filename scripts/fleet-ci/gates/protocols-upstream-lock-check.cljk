#!/usr/bin/env nbb
;; protocols-upstream-lock-check.cljs — net-kotobase/control-plane の
;; `protocols-worker` が、自分の upstream lock 契約をまだ満たしているか。
;;
;; usage: nbb protocols-upstream-lock-check.cljs <dir> [--min-repos N]
;;        <dir> を **先頭**に置く。多くの gate が tree を
;;        `(first (remove #(str/starts-with? % "--") argv))` で決めるので、
;;        `gate.cljs --min-repos 20 .` と書くと **"20" が tree のパスになる**
;;        （root CLAUDE.md が 3 つの gate を誤診した記録）。fleet は <dir> を
;;        先に渡すので、手元で回すときも同じ順にする。
;;
;; ---------------------------------------------------------------------------
;; この gate が答え **ない** 問い —— 先に書く（黙って落とさない）
;; ---------------------------------------------------------------------------
;;
;; **`protocols-worker` の 120 tests は、この gate も含めて fleet では回せない。**
;; 2026-08-17 実測（ADR-2608170200）:
;;
;;   suite       `shadow-cljs compile test && node out/node-tests.js`
;;   source-path `shadow-cljs.edn` が `../../../kotoba-lang/<repo>/src` を **26 本**
;;               参照する。これは west の**兄弟 project** で、repo の外に在る。
;;   実測        `git ls-files | grep -c kotoba-lang/` = **0**。
;;               tip の tarball を展開しても `kotoba-lang` に一致する path は 0 件。
;;
;; fleet がノードへ配るのは **対象 repo の tree だけ**なので、26 本の source-path は
;; どれ 1 つ解決しない。運ぶ機構も無い —— `ship-git-deps!` が運ぶのは `deps.edn` の
;; `:git/sha` 依存であって shadow-cljs の相対 source-path ではなく、
;; `protocols-worker` に `deps.edn` は無い（したがって `:jvm-test` も構造的に使えない）。
;; これは `root-permit-index` が 298 receipt 全部赤だったのと同じ形で、**入力が無い
;; gate は落ちているのではなく問いを立てられていない**。だから suite の gate は landing
;; しない。
;;
;; さらに、この repo で shadow-cljs を回す gate は**一度作られて撤去されている** ——
;; `net-kotobase-bundle-freshness` の初版が `npm ci && npm test` を回しており、
;; オーナー指示（2026-08-11「java に依存しないようにしてほしい」）で sha256 比較に
;; 置き換えられた。shadow-cljs = JVM なので、仮に 26 本を運べても同じ指示に当たる。
;;
;; ---------------------------------------------------------------------------
;; そこで問いを変える —— lock 契約は自己完結していて、ノードで**実際に**答えられる
;; ---------------------------------------------------------------------------
;;
;; `upstream-lock.json` は、shadow-cljs が引く 26 本の repo それぞれの 40-hex revision を
;; 固定する。repo 自身の checker（`scripts/upstream-lock.mjs`）が持つ不変条件は:
;;
;;   - lock の repo 集合 == `shadow-cljs.edn` の source-path 集合（過不足なく、sorted）
;;   - revision は例外なく小文字 40-hex
;;   - top-level は `schema` / `organization` / `repositories` ちょうど 3 つ、この順
;;
;; **これは実在した壊れ方である。** `shadow-cljs.edn` のコメント自身が 2 度記録して
;; いる: `kotobase-block-codec` を足し忘れてビルドが
;; "kotobase.blockcodec.core is not available" で落ち、`datalog` を足し忘れて
;; "datalog.index is not available" で落ちた（2026-08-03、"this shell could not be
;; rebuilt at all"）。source-path が増えて lock が追随しない、が実際の failure mode。
;;
;; **なぜノードで動くか（実測 2026-08-17、配られる tarball を展開しただけの tree で）**:
;; checker は node builtin だけを使い、`upstream-lock.json` と `shadow-cljs.edn` を
;; 自分の隣から読む。`npm ci` 不要・JVM 不要・egress 不要・兄弟 checkout 不要。
;; `node_modules` が無い tree で `--self-test` と `--check` が両方 exit 0 だった。
;;
;; **`--verify-checkouts` は呼ばない。** あれは兄弟 checkout を実際に見に行くので、
;; 上と同じ理由でノードでは答えられない。呼べば毎回赤になる。
;;
;; ---------------------------------------------------------------------------
;; なぜ `--self-test` も回すか —— checker が黙って無効化されるのを止める
;; ---------------------------------------------------------------------------
;;
;; `--check` だけだと、`lockFailures` が `return []` に潰された瞬間に **緑になる**。
;; `--self-test` は checker 自身が持つ positive 1 件 + negative 4 件の fixture を回す
;; ので、判定器が discriminate しなくなったこと自体を落とせる。**両方向の証拠を
;; checker が自分で持っている**ので、gate 側に fixture を複製しない。
;;
;; ---------------------------------------------------------------------------
;; exit code —— 「答えられなかった」に専用の値を与える（root CLAUDE.md の 5 問）
;; ---------------------------------------------------------------------------
;;
;;   0   契約を満たしている
;;   1   契約が破れている / checker が discriminate しなくなった（本物の finding）
;;   90  **答えられなかった**: 入力が配られていない、または evidence floor 未達
;;   91  node が使えない（道具が無いことは合格ではない）
;;
;; evidence floor は checker 自身が出す数を読む（`... OK (26 exact revisions)`）。
;; **gate 側で数え直さない** —— 数え直せばそれは 2 つ目の実装で、片方だけ直る。
;; 空の `shadow-cljs.edn` と空の lock は `lockFailures` を通ってしまう（集合が
;; 一致するので）が、その時 checker は `OK (0 exact revisions)` と言うので、
;; floor がそれを 90 で止める。**検査対象 0 件を clean にしない。**
;;
;; ---------------------------------------------------------------------------
;; `FLEET-CI-EXIT:` を自分では印字しない
;; ---------------------------------------------------------------------------
;;
;; verdict は tick.cljs の wrapper が `echo "FLEET-CI-EXIT: $code"` で出し、operator は
;; out-file 全体を `grep -q '^FLEET-CI-EXIT: 0$'` する。gate が自分でこの行を出すと、
;; **exit 1 の途中で `FLEET-CI-EXIT: 0` を印字した瞬間に false pass になる**
;; （grep は行を探すだけで、どちらが verdict かを知らない）。出さなければ構造的に起きない。
;;
;; なお wrapper は `echo "$out" | tail -25` しか出力に残さないので、**結論は最後に置く**。

(ns fleet-ci.gates.protocols-upstream-lock-check
  (:require ["node:child_process" :as cp]
            ["node:fs" :as fs]
            ["node:path" :as path]
            [clojure.string :as str]))

(def argv (vec *command-line-args*))

;; <dir> は先頭の非フラグ引数。フラグの **値** を tree と読まないための形。
(def tree (or (first (remove #(str/starts-with? % "--") argv)) "."))

(defn- flag [nm default]
  (let [i (.indexOf argv nm)]
    (if (neg? i) default (nth argv (inc i) default))))

(def min-repos (js/parseInt (str (flag "--min-repos" 20)) 10))

(def worker-dir (path/join tree "protocols-worker"))
(def checker (path/join worker-dir "scripts" "upstream-lock.mjs"))

(defn- die! [code msg]
  (println msg)
  (js/process.exit code))

(defn- run
  "-> {:code n :out s}. node の exit code をそのまま返す（ssh を挟まないので信用できる）。"
  [args]
  (try
    {:code 0 :out (cp/execSync (str "node " (str/join " " args))
                               #js {:encoding "utf8"
                                    :maxBuffer (* 8 1024 1024)
                                    :stdio #js ["pipe" "pipe" "pipe"]})}
    (catch :default e
      {:code (or (.-status e) 1)
       :out (str (some-> (.-stdout e) str) (some-> (.-stderr e) str))})))

(defn- q [s] (str "'" (str/replace s "'" "'\\''") "'"))

(defn -main []
  (println (str "protocols-upstream-lock :: " tree))

  ;; ---- ① 道具。無いことは合格ではない。
  (let [{:keys [code out]} (run ["--version"])]
    (when-not (zero? code)
      (die! 91 (str "FLEET-CI: node is not usable on this host — refusing to report a pass\n"
                    (str/trim out))))
    (println (str "node " (str/trim out))))

  ;; ---- ② 入力。配られていない tree で検査して合格しない。
  ;;
  ;; 3 つとも `git ls-files` に在るファイルなので、欠けているなら配送の失敗である。
  ;; gates.edn の entry は `:include-ext` を **付けない**（= full tarball）ので、
  ;; ここが 90 になるのは絞り込みではなく repo/sha を間違えたときだけ。
  (doseq [[label p] [["scripts/upstream-lock.mjs" checker]
                     ["upstream-lock.json"        (path/join worker-dir "upstream-lock.json")]
                     ["shadow-cljs.edn"           (path/join worker-dir "shadow-cljs.edn")]]]
    (when-not (fs/existsSync p)
      (die! 90 (str "FLEET-CI: missing after extract: protocols-worker/" label
                    " — this gate could not ask its question (this is not a pass)"))))

  ;; ---- ③ checker の syntax。repo 自身の `upstream:check` の 1 段目と同じ。
  (let [{:keys [code out]} (run ["--check" (q checker)])]
    (when-not (zero? code)
      (die! 1 (str "FLEET-CI: scripts/upstream-lock.mjs does not parse\n" (str/trim out)))))

  ;; ---- ④ checker がまだ discriminate するか（positive 1 + negative 4 の自前 fixture）。
  (let [{:keys [code out]} (run [(q checker) "--self-test"])]
    (println (str/trim out))
    (when-not (zero? code)
      (die! 1 (str "FLEET-CI: the upstream-lock checker no longer discriminates — "
                   "its own fixtures failed. A checker that cannot fail cannot pass."))))

  ;; ---- ⑤ 本番の判定 + evidence floor。
  (let [{:keys [code out]} (run [(q checker) "--check"])
        out (str/trim out)]
    (when-not (zero? code)
      (die! 1 (str "FLEET-CI: protocols-worker upstream lock does not satisfy its own contract\n"
                   out)))
    (let [n (some-> (re-find #"\((\d+) exact revisions\)" out) second js/parseInt)]
      (when (nil? n)
        (die! 90 (str "FLEET-CI: could not read the revision count out of the checker's report — "
                      "refusing to report a pass on an unread contract\n" out)))
      ;; 結論は最後の 25 行に必ず残る位置に置く。
      (println (str "SCANNED\t" n))
      (when (< n min-repos)
        (die! 90 (str "FLEET-CI: only " n " locked upstream revisions (floor " min-repos ") — "
                      "an empty or truncated contract satisfies its own checker trivially, so "
                      "this is refused rather than reported as a pass")))
      (println (str "protocols-worker upstream lock OK — " n " pinned kotoba-lang revisions, "
                    "checker self-test green"))
      (println "NOT ANSWERED BY THIS GATE: whether the 120-test suite passes "
               "(shadow-cljs needs 26 sibling repos a node never receives)"))))

(-main)
