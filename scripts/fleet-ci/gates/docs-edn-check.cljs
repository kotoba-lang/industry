#!/usr/bin/env nbb
;; docs-edn-check.cljs — EDN-only ドキュメント repo の gate。
;;
;; 展開済みの repo tree を受け取り、tree 内の全 *.edn が
;; `(edn/read-string (slurp f))` 可能であることを検証する（90-docs の EDN-only
;; 方針 ADR-2607171600 と同じ不変条件を、CI として repo 側にも課す）。
;;
;; ノード側で `npx nbb docs-edn-check.cljs <dir>` として実行される
;; （tick.cljs が heredoc でノードに配って呼ぶ。JVM を要求しない gate）。
;;
;; exit codes（3 つとも 2026-09-10 に実測、`/tmp` の使い捨て repo で再現できる）:
;;
;;   0  木が完全で、壊れた文書は無い            edn files: 3/3
;;   2  読めなかった文書が在る（壊れてはいない）  edn files: 2/3  CANNOT ANSWER
;;   1  実際に壊れている文書が在る               edn files: 3/4  FAIL broken.edn
;;
;; 1 が 2 に優先する —— 読めた文書は本当に読めているので、読めなかったものが
;; 何であれ parse 失敗は実在する（manifest/docs-edn-only.cljs と同じ優先順位）。
;;
;; false-pass 対策: 検出ファイル数が --min 未満なら FAIL する。tarball の
;; 展開ミス（ADR-2607178000 addendum の --strip-components 事故と同型）で
;; 空ディレクトリを検査して「0 件 = 全部有効」と報告する事故を防ぐ。
(ns fleet-ci.gates.docs-edn-check
  (:require ["node:child_process" :as child-process]
            ["node:fs" :as fs]
            ["node:path" :as path]
            [cljs.reader :as reader]
            [clojure.string :as str]))

(def args (vec *command-line-args*))

(defn- flag [name default]
  (let [i (.indexOf args name)]
    (if (neg? i) default (nth args (inc i)))))

;; tick.cljs は常に「展開ディレクトリ」を第1引数で渡すので、検査範囲を狭めたい
;; ときはここで受ける。superproject のように .edn が repo 全体に散っていて、
;; 不変条件（EDN-only / parse 可能）が課されているのは一部の plane だけ、という
;; 対象のための口。範囲外に古い壊れた EDN があっても、規約が効いている plane の
;; 回帰を検出できる。
(def sub (flag "--sub" nil))
(def root (let [d (or (first (remove #(str/starts-with? % "--")
                                     (remove (set [(flag "--min" nil) (or sub "")]) args)))
                      ".")]
            (if sub (path/join d sub) d)))
(def min-files (js/parseInt (str (flag "--min" 50)) 10))

;; --strict-keys — 「読めた」を「無傷」と読まないための追加検査。opt-in。
;;
;; `read-string` が throw しないことは、文書が壊れていないことを意味しない。
;; 実測 2026-08-20: ADR の本文中に引用符を 1 つ入れると、そこで文字列が終わり、
;; 続く語が **キーとして** 読まれ、次の引用符から新しい文字列が始まる。引用符の
;; 個数の偶奇が合えば map も vector も閉じるので、**reader は何事もなく値を返す**。
;; その値の keys は `(:adr/status :adr/id nothing :adr/body :adr/date no-op :db/id "\\")`
;; で、`:adr/body` は数千字あるはずが 523 字に切り詰められていた。
;;
;; 壊れた文書と無傷の文書が同じ exit code を返す —— この repo が 1 日で 6 箇所
;; 見つけた形そのもの。keys が全部 keyword かどうかは、それを 1 行で分ける。
;;
;; 既定は off。この gate は 3 つの repo が既に使っており、そちらの現在値を
;; 測らずに厳しくすると、直すべきものが無いのに赤くなる。
(def strict-keys (some? (some #{"--strict-keys"} args)))

(defn- bad-keys
  "SYMBOL keys anywhere in a parsed document. Empty is clean.

  Symbols only, and that narrowing was measured rather than assumed. The first
  version flagged every non-keyword key and reddened 8 of the superproject's
  2,347 ADRs — of which 7 were legitimate: `\"p50\"`/`\"p95\"`, `\".clj\"`/`\".cljs\"`,
  `\"stripe.com\"`, `0 1 2 3`. EDN maps take string and integer keys and this
  corpus uses them on purpose; a check that calls those corrupt is a check
  nobody will keep.

  A bare SYMBOL in key position is different: it is what prose becomes when a
  quote ends its string early and the reader carries on parsing the sentence as
  structure. Measured across the same 2,347 documents, exactly one has symbol
  keys — `commit-dag` and `|quad-store`, a table cell that bled into the
  document — so the rule costs one true positive and no false ones.

  ⚠ IT DOES NOT CATCH AN INTEGER KEY, and one got past it on 2026-09-07. An ADR
  body contained a backticked example whose content was a quoted number; the
  quote ended the docstring, the number after it was read as a KEY, and the rest
  of the sentence closed the map. The document parsed, the entity had a key of
  `10`, and this check — symbols only — said nothing. `adr-bad-keys` below is the
  narrow answer: integer keys are legitimate in this corpus generally, and not in
  an ADR."
  [v]
  (cond
    (map? v) (concat (filter symbol? (keys v)) (mapcat bad-keys (vals v)))
    (sequential? v) (mapcat bad-keys v)
    :else nil))

(defn- adr-bad-keys
  "Non-keyword TOP-LEVEL keys on an ADR entity. Empty is clean.

  Narrower than `bad-keys` in what it looks at and wider in what it rejects, and
  both narrowings were measured. An ADR is `[{:db/id … :adr/id … :adr/body …}]`,
  so every top-level key is a keyword by construction — checked across all 2,769
  ADRs in `90-docs/adr` on 2026-09-07: not one has a top-level key that is not a
  keyword. So rejecting anything else there costs no false positives, while the
  corpus-wide rule cannot reject integer or string keys because seven documents
  use them on purpose.

  It looks only at the top level for the same reason: an ADR's nested structure
  is a `pr-str` blob inside a string, so anything deeper is not the ADR's own
  shape."
  [v]
  (->> (if (sequential? v) v [v])
       (filter map?)
       (mapcat (fn [e] (remove keyword? (keys e))))))

(def skip-dirs #{"node_modules" ".git" "archive" "dist" "target" ".shadow-cljs"
                 ;; Session scratch: git worktrees other agents cut under the
                 ;; superproject. They are UNTRACKED, so the fleet is never
                 ;; sent them -- walking them makes a local run red for files
                 ;; the gate will never see on a node, which is the same
                 ;; measurement error as the sparse-cone one in CLAUDE.md with
                 ;; its sign flipped. Measured 2026-08-29: every one of this
                 ;; gate's local failures came from one such worktree.
                 ".worktrees"})

;; Ask git what is in the tree, and only fall back to walking the filesystem
;; when git cannot answer (an extracted tree with no .git, which is exactly
;; what the fleet ships). The two disagree in both directions: git alone
;; misses the extracted tree, the walk alone picks up untracked scratch.
(defn tracked-edn-files [dir]
  (try
    (let [out (.execFileSync child-process "git"
                             #js ["-C" dir "ls-files" "--cached" "--others"
                                  "--exclude-standard" "-z" "--" "*.edn"]
                             #js {:encoding "utf8" :maxBuffer (* 64 1024 1024)})]
      (->> (str/split out #"\u0000")
           (remove str/blank?)
           (remove (fn [f] (some #(str/starts-with? f (str % "/")) skip-dirs)))
           (mapv #(path/join dir %))
           seq))
    (catch :default _ nil)))

(defn walked-edn-files [dir]
  (let [out (atom [])]
    ((fn walk [d]
       (doseq [e (fs/readdirSync d #js {:withFileTypes true})]
         (let [n (.-name e) p (path/join d n)]
           (cond
             (.isDirectory e) (when-not (contains? skip-dirs n) (walk p))
             (str/ends-with? n ".edn") (swap! out conj p)))))
     dir)
    @out))

(defn edn-files [dir]
  (or (tracked-edn-files dir) (walked-edn-files dir)))

(defn split-readable
  "-> [on-disk unreadable]

  `tracked-edn-files` asks git, and git answers about the INDEX. In a cone-mode
  sparse checkout the index names files this working tree does not contain, so
  `readFileSync` throws ENOENT on them. Those went into the same `bad` list as a
  genuinely corrupt document and the gate exited 1 -- reporting a file it could
  not read as a file that does not parse.

  `manifest/docs-edn-only.cljs` already splits these (`edn=<scanned>/<listed>`,
  exit 2) and this gate reads the same git listing; it just never made the same
  distinction. Measured 2026-09-10 on a `--profile policy` worktree: 19 of the
  FAIL lines were ENOENT for 60-apps/ and 80-data/ paths outside the cone, which
  in the output are indistinguishable from the one real finding."
  [files]
  [(filterv fs/existsSync files) (vec (remove fs/existsSync files))])

(when-not (fs/existsSync root)
  (println "FLEET-CI: root does not exist:" root
           "— extraction or --sub is wrong, refusing to report pass")
  (js/process.exit 90))

(let [listed (edn-files root)
      [files unreadable] (split-readable listed)
      bad (atom [])]
  (doseq [f files]
    (try
      (let [s (str (fs/readFileSync f "utf8"))
            ;; 空ファイル・コメントのみは read-string が nil を返すのが正常。
            ;;
            ;; ⚠ 閉じ括弧の前に改行を入れる。`;;` 行コメントは行末まで伸びるので、
            ;; 末尾に改行が無いファイルでは `"]"` が**最後のコメントに飲み込まれ**、
            ;; その文書が壊れているという報告になる。実測 2026-09-10、
            ;; 90-docs/business/revenue-agent-loop/runs/0037-… は全行が `;;` の
            ;; 健全なファイルだが最後のバイトが `。` で、gate は
            ;; `Unexpected EOF while reading item 0 of vector` と報告していた ——
            ;; **道具の側の欠陥を、文書の側の欠陥として名指ししていた。**
            ;; 現在の repo で当たるのは 1 件だが、条件は「コメントで終わり改行が無い」
            ;; だけなので、いつでも増えうる。
            v (reader/read-string (str "[\n" s "\n]"))]
        (when strict-keys
          (let [odd (distinct (bad-keys v))]
            (when (seq odd)
              (swap! bad conj
                     [f (str "parses, but " (count odd) " symbol key(s) — "
                             (str/join ", " (map pr-str (take 4 odd)))
                             " — a quote inside a string ended it early and what "
                             "followed was read as structure")])))
          ;; the ADR-only rule, which catches what the symbol test cannot
          (when (str/includes? f "90-docs/adr/")
            (let [odd (distinct (adr-bad-keys (first v)))]
              (when (seq odd)
                (swap! bad conj
                       [f (str "parses, but the ADR entity has " (count odd)
                               " top-level key(s) that are not keywords — "
                               (str/join ", " (map pr-str (take 4 odd)))
                               " — a quote inside the body ended it early and what "
                               "followed was read as structure")]))))))
      (catch :default e
        (swap! bad conj [f (ex-message e)]))))
  (println (str "edn files: " (count files) "/" (count listed)
                " unparsable: " (count @bad)))
  (doseq [[f msg] (take 20 @bad)]
    (println "  FAIL" (path/relative root f) "—" msg))
  (when (seq unreadable)
    (println (str "=== " (count unreadable)
                  " .edn ARE IN GIT BUT NOT IN THIS WORKING TREE ==="))
    (println "  This checkout does not contain them, so their content was not read.")
    (println "  Fix: run from a full checkout, or sparse-checkout add their directories.")
    (doseq [f (take 10 unreadable)] (println " " (path/relative root f)))
    (when (< 10 (count unreadable))
      (println (str "  … and " (- (count unreadable) 10) " more"))))
  (cond
    (< (count listed) min-files)
    (do (println "FLEET-CI: only" (count listed) "edn files found (<" min-files
                 ") — extraction or path is wrong, refusing to report pass")
        (js/process.exit 90))

    ;; A definite defect outranks an incomplete corpus: every document that WAS
    ;; read was really read, so a parse failure in it is real no matter what was
    ;; missing. Same precedence as manifest/docs-edn-only.cljs.
    (seq @bad) (js/process.exit 1)

    ;; Read nothing that is broken, but did not read everything. "All edn files
    ;; parse" is not something this run is entitled to say, and 0 must not be the
    ;; answer (ADR-2608136000: a check that could not perform its measurement
    ;; must not return the value of one that performed it and found nothing).
    (seq unreadable)
    (do (println (str "CANNOT ANSWER — " (count unreadable) " of " (count listed)
                      " edn files are not in this working tree; the "
                      (count files) " that are all parse"))
        (js/process.exit 2))
    ;; One string, not two arguments to println. `(when strict-keys ...)` is nil
    ;; when the flag is off, and println prints that nil: the three repos that
    ;; do not pass --strict-keys have been recording
    ;; "OK — all 2347 edn files parse nil" in their fleet receipts since this
    ;; flag landed. Harmless to the verdict and confusing in the one line a
    ;; reader actually sees.
    :else (println (str "OK — all " (count files) " of " (count listed)
                        " edn files parse"
                        (when strict-keys
                          ", and no symbol appears in key position")))))
