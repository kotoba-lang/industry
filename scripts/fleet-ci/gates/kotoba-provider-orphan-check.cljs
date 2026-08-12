#!/usr/bin/env nbb
(ns kotoba-provider-orphan-check
  "provider 側から `.kotoba` 移行の置き去りを検出する。`verify-require-graph` の対偶。

  ## 規則

      この repo 自身の git 履歴で `src/` 配下の .clj/.cljc/.cljs が削除されており、
      同じ path に `.kotoba` が今あり、兄弟の .clj/.cljc/.cljs が残っていないなら FAIL。

  条件は 3 つとも**この repo の中だけ**で確かめられる（消費側を見ない）。だから
  消費側がまだ存在しない時点でも鳴る —— そこが `scripts/verify-require-graph.cljs`
  との差である。あちらは消費側が既に壊れてから初めて鳴る事後検出器で、しかも
  本質的に repo 横断なので gate になれない（当該ファイル冒頭に明記されている）。

  ## なぜ履歴が要るか —— 実測で決着している

  「兄弟のない `.kotoba` は全部 FAIL」にしてはならない。2026-08-13 に checkout 済み
  4,415 project を全数走査した実測値:

      tracked な .kotoba          1,936
        src/ 配下                    227   うち兄弟なし 206（97 repo）
        src/ 以外                  1,709   うち兄弟なし 1,705

  **`src/` に限っても兄弟なしが 206 件ある。** その大半は最初から `.kotoba` として
  書かれた正常なもの（`bounded_*.kotoba` / `*_golden.kotoba` /
  cloud-itonami の `association_facts.kotoba` 46 件など）。つまり「`src/` の外に
  置くのが `.kotoba` の慣習だから、`src/` に居るものは移行の残骸だ」という
  tree だけで済ませる仮説は、**測ったら成り立たなかった**。区別できるのは履歴だけ
  —— 置き換えたのか、新しく書いたのか。

  ## この gate が今 fleet に載っていない理由（重要・実測）

  **fleet は `.git` を配らない。** `scripts/fleet-ci/tick.cljs` は対象 tip を
  mirror からの `git archive --format=tar.gz --prefix=repo/ <sha>` で固め、それを
  ssh stdin でノードへ流す（`full-tarball!` / `filtered-tarball!`）。`git archive`
  の出力に `.git` は入らない —— 入れる option も無い。したがってノード上の展開
  ディレクトリには履歴が無く、**この gate の入力そのものが tree に存在しない。**

  それを承知で `gates.edn` に載せると `root-permit-index` と同じ形になる ——
  「入力が無い gate は、落ちているのではなく問いを立てられていない」。だから
  載せていない。詳細は ADR-2608132400。

  **直し方の機構は既にある。** ADR-2608132000（同日）は、`ship-git-deps!` が
  ノードに置く**依存**が `git archive` の展開物で `.git` を持たないために
  `amu-native-conformance` が 28 run 連続で赤だったのを、**`git bundle` を送って
  本物の checkout を作る**ことで直した。gate 対象 repo 自身の tree に同じことを
  すれば（entry の `:ship-self-bundle true` 相当）、この gate は載る。

  そのため **`.git` が無い tree では緑を返さず exit 90 で落ちる**（下の床①）。
  もし将来これを gates.edn に載せた人が居ても、静かな false-green にはならず
  「履歴がここに無い」と名指しで赤くなる。

  ## 床（何も測っていないのに PASSED と言わないため）

  ① `.git` が無い              → exit 90  履歴が無いので判定不能。緑にしない
  ② scan した .kotoba が --min 未満 → exit 91  絞り込みが壊れて 0 件 → trivially pass を防ぐ
  ③ `git ls-files` が失敗       → exit 92  index が読めないのに 0 件と report しない

  ## 使い方

      nbb kotoba-provider-orphan-check.cljs <dir> [--min N] [--root src] [--allow PATH]

  ⚠ `<dir>` は引数の**先頭**（CLAUDE.md「赤い gate を直す前に 3 つ確かめる」2）。
  `--root` は複数回渡せる（既定 `src`）。`--allow` は path 単位の opt-out で、
  「この ns に Clojure 消費側は存在しない」と宣言する場合に使う。乱用すると gate が
  劇場になるので、使うなら理由を gates.edn の script-args の隣に書くこと。

  ## コスト

  履歴問い合わせは **兄弟のない `.kotoba` の path についてだけ**行う（`git log
  --diff-filter=D -- <3 path>`）。repo 全体に対する `git log --diff-filter=D` は
  大きな repo で数分かかるが、pathspec を先に絞れば 1 path あたりミリ秒で返る。
  実測は ADR-2608132400。"
  (:require [clojure.string :as str]
            ["node:fs" :as fs]
            ["node:path" :as path]
            ["node:child_process" :as cp]))

(def argv (vec *command-line-args*))

(defn- flag-values
  "`--k v --k v` を全部拾う。"
  [k]
  (->> (map vector argv (rest argv))
       (filter (fn [[a _]] (= a k)))
       (mapv second)))

(defn- flag-value [k default]
  (or (first (flag-values k)) default))

;; `<dir>` は先頭の非フラグ引数。フラグの値を tree と誤認しないよう、
;; 直前が `--` で始まる語だったものは除く。
(def here
  (or (->> (map vector (cons nil argv) argv)
           (remove (fn [[prev a]]
                     (or (str/starts-with? a "--")
                         (and prev (str/starts-with? prev "--")))))
           (map second)
           first)
      "."))

(def min-files (js/parseInt (flag-value "--min" "1") 10))
(def roots (let [r (flag-values "--root")] (if (seq r) r ["src"])))
(def allowed (set (flag-values "--allow")))
(def source-exts [".clj" ".cljc" ".cljs"])

(defn- fail! [code msg]
  (println (str "FLEET-CI: " msg))
  (js/process.exit code))

(defn- git [args]
  (try
    {:ok true
     :out (str (cp/execFileSync "git" (clj->js (into ["-C" here] args))
                                #js {:encoding "utf8" :maxBuffer (* 64 1024 1024)
                                     :stdio #js ["ignore" "pipe" "pipe"]}))}
    (catch :default e {:ok false :out (str (.-message e))})))

;; ---------------------------------------------------------------- 床①: 履歴

(when-not (fs/existsSync (path/join here ".git"))
  (fail! 90 (str "no .git in " here
                 " — this gate reads the repo's own history and a `git archive`"
                 " tarball does not carry it. Refusing to report a pass.")))

;; ------------------------------------------------------- tree: .kotoba を数える

(def ls (git ["ls-files" "--" "*.kotoba"]))
(when-not (:ok ls)
  (fail! 92 (str "git ls-files failed in " here " — refusing to report 0 files as a pass: "
                 (str/trim (:out ls)))))

(def all-kotoba
  (->> (str/split-lines (:out ls)) (remove str/blank?) vec))

(def under-root?
  (fn [p] (some (fn [r] (str/starts-with? p (str r "/"))) roots)))

(def scoped (filterv under-root? all-kotoba))

;; ---------------------------------------------------------------- 床②: 下限

(when (< (count all-kotoba) min-files)
  (fail! 91 (str "only " (count all-kotoba) " .kotoba file(s) in " here
                 ", expected at least " min-files
                 " — the tree filter is probably wrong (does :include-ext admit \".kotoba\"?)."
                 " Refusing to report a pass on a tree this gate did not really read.")))

;; ------------------------------------------------- 兄弟のない .kotoba を選び出す

(defn- siblings [kotoba-path]
  (let [base (str/replace kotoba-path #"\.kotoba$" "")]
    (mapv (fn [e] (str base e)) source-exts)))

(def sibling-less
  (filterv (fn [p]
             (not (some (fn [s] (fs/existsSync (path/join here s))) (siblings p))))
           scoped))

;; ------------------------------------------------------- 履歴: 削除されたか

(defn- deleting-commit
  "その path 群を削除した最新の commit。無ければ nil。"
  [paths]
  (let [{:keys [ok out]} (git (into ["log" "--diff-filter=D" "--max-count=1"
                                     "--format=%H%x09%ad%x09%s" "--date=short" "--"]
                                    paths))]
    (when (and ok (seq (str/trim out)))
      (let [[sha date subject] (str/split (str/trim (first (str/split-lines out))) #"\t")]
        {:sha sha :date date :subject subject}))))

(def findings
  (->> sibling-less
       (remove allowed)
       (keep (fn [p]
               (when-let [c (deleting-commit (siblings p))]
                 (assoc c :kotoba p :deleted (siblings p)))))
       vec))

;; ---------------------------------------------------------------- 判定

(println (str "scanned " (count all-kotoba) " .kotoba file(s); "
              (count scoped) " under " (pr-str roots) "; "
              (count sibling-less) " without a .clj/.cljc/.cljs sibling"
              (when (seq allowed) (str "; " (count allowed) " allow-listed"))))

(if (seq findings)
  (do
    (println)
    (println (str "FAIL kotoba-provider-orphan — " (count findings)
                  " namespace(s) migrated to .kotoba with no Clojure load path left"))
    (println)
    (doseq [{:keys [kotoba sha date subject]} findings]
      (println (str "  " kotoba))
      (println (str "    a Clojure source at this path was deleted in " (subs sha 0 12)
                    " (" date ") — " subject))
      (println (str "    and no sibling .clj/.cljc/.cljs remains, so this namespace"
                    " cannot be loaded by any Clojure runtime.")))
    (println)
    (println (str "  Fix (ADR-2608130900, the `css` shape): restore the .cljc beside the"
                  " .kotoba and add a parity test asserting the two agree. Keep the .kotoba"
                  " as the authority; the .cljc is what consumers load."))
    (println "FLEET-CI: kotoba-provider-orphan gate FAILED")
    (js/process.exit 1))
  (do
    (println (str "FLEET-CI: kotoba-provider-orphan gate OK ("
                  (count all-kotoba) " .kotoba scanned, "
                  (count sibling-less) " sibling-less, 0 with a deleted Clojure sibling)"))
    (js/process.exit 0)))
