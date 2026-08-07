#!/usr/bin/env nbb
;; verify-require-graph — ある repo が別の repo の namespace を消し、参照側を
;; 更新しないまま置き去りにした状態を検出する。
;;
;; ## なぜ要るか（実測した事故）
;;
;; 2026-07-21、`kotoba-lang/dsl-core` が `src/kotoba/dsl/problem.cljc` を削除して
;; `problem.kotoba` に置き換えた。消費側は 1 つも更新されなかった。結果
;; `kotoba.dsl.problem` は JVM でも nbb でも ClojureScript でも load できなくなり、
;; **checkout 済みだけで 12 repo が 17 日間壊れたまま**、誰にも気付かれなかった
;; （ADR-2608071000）。壊れていたのは test だけでなく `src/` の実行経路で、
;; `loop-system-dynamics` の XMILE モデル 6 本が動かなくなっていた。
;;
;; 気付かれなかったのは、**どの CI も自 repo しか見なかったから**である。dsl-core
;; 自身の CI は緑（`.kotoba` を JVM ホストで駆動するテストは通る）。消費側には CI が
;; 無い（GitHub Actions は ADR-2607300900 で撤去、fleet に未 port）。
;;
;; ## 検出規則（意図的に狭い）
;;
;; 「require した namespace が provided に無い」だけを条件にすると、**未 checkout の
;; west project や maven/npm 由来の namespace を全部『無い』と報告する** —— 4,132
;; project の大半は手元に無いので、それは誤検出の山になる。`verify-parity-probes` が
;; 戒めているのと同じ形の誤り（探索範囲が主張を支えていない）を、検出器自身がやる
;; ことになる。
;;
;; そこで **移行 orphan の署名**だけを見る:
;;
;;   :kotoba-orphan   require された ns の `.clj/.cljc/.cljs` は無いが、同じ path に
;;                    **`.kotoba` がある**。= その ns は .kotoba へ移行され、参照側が
;;                    追従していない。dsl-core の事故がちょうどこれ。**これだけで
;;                    fail する。**
;;   :sibling-gap     require された ns のファイルは無いが、その親ディレクトリは
;;                    checkout 済み repo の source root に実在する。**報告のみで
;;                    fail しない。**
;;
;; ## なぜ :sibling-gap で fail しないか
;;
;; 親ディレクトリが在ることは、その ns を**その repo が提供すべき**ことを意味しない。
;; 実測（2026-08-07）: `network-awai/cloud-murakumo` の `engi.consensus` は
;; `kotoba-lang/engi-node` の `engi/` ディレクトリと衝突して sibling-gap に見えるが、
;; 提供元は未 checkout の別 repo かもしれない。west は 4,132 project を持ち大半は
;; 手元に無いので、**これを fail にすると「未 checkout」を「壊れている」と言うことに
;; なる。** `verify-parity-probes` が戒めているのと同じ誤りを検出器自身がやる形。
;;
;; 一方 `:kotoba-orphan` は未 checkout で説明できない —— **その `.kotoba` は手元に
;; 在り、同じ path の `.clj/.cljc/.cljs` だけが無い**。提供元 repo は checkout されて
;; おり、そこに在るはずのものが無いと言い切れる。
;;
;; どちらも「手元に無い＝存在しない」とは言っていない。
;;
;; ## この検査を fleet gate にしていない理由
;;
;; fleet の `:nbb-script` gate は **1 repo の tree を配ってノードで回す**。この検査は
;; 本質的に repo 横断（消費側と提供側が別 repo）なので、その形に載らない。superproject
;; の tree には `orgs/` 配下の west project が入らないため、root の gate にしても
;; 走査対象がゼロになる。したがって workspace script として置き、agent と手作業から
;; 呼ぶ。**「gate に載っている」と誤解させないため、ここに明記しておく。**
;;
;; usage: nbb scripts/verify-require-graph.cljs [--root DIR] [--min-repos N]

(require '[clojure.string :as str])

(def fs (js/require "node:fs"))
(def path (js/require "node:path"))
(def cp (js/require "node:child_process"))

(def source-exts #{".clj" ".cljc" ".cljs"})
(def source-roots ["src" "test"])

;; require 先が外部（maven / npm / 言語組み込み）だと分かっている接頭辞。ここに
;; 載せるのは「この workspace が提供しないと確信できるもの」だけにする。
(def external-prefixes
  ["clojure." "cljs." "goog." "java." "javax." "js." "node." "promesa."
   "datascript." "cognitect." "malli." "reitit." "hiccup." "shadow."
   "applied-science." "borkdude." "babashka." "sci." "taoensso." "medley."])

(defn- external? [ns-str]
  (or (some #(str/starts-with? ns-str %) external-prefixes)
      ;; 単一セグメント（`user` 等）は判定材料が無いので触らない
      (not (str/includes? ns-str "."))))

(defn- ns->rel
  "namespace symbol → source root からの相対 path（拡張子なし）。
   Clojure の規則どおり `-` → `_`。"
  [ns-str]
  (-> ns-str (str/replace "-" "_") (str/replace "." "/")))

(defn- sh [cmd args opts]
  (let [r (.spawnSync cp cmd (clj->js args)
                      (clj->js (merge {:encoding "utf8" :maxBuffer (* 512 1024 1024)} opts)))]
    {:exit (or (.-status r) 1) :out (or (.-stdout r) "") :err (or (.-stderr r) "")}))

;; 走査は **プロセス 2 回**で終える。repo ごとに `find`/`grep` を起動する素朴な形は
;; 数千 spawn になり 2 分で終わらなかった（実測）。orgs 配下を 1 回舐めて nbb 側で
;; repo に振り分ける。

(defn- split-source-path
  "orgs/<org>/<repo>/(src|test)/<rel> → {:org :repo :root :rel}。
   その形でなければ nil（ドキュメント・ビルド生成物・深い階層の除外を兼ねる）。

   `.` で始まる repo 名は除く —— agent の一時 worktree（`.wt-run`、
   `.cloud-itonami-app-adopt` 等）で、同じ内容を二重に数える上に、消えた瞬間に
   検出結果が変わる。"
  [rel-from-orgs]
  (let [seg (str/split rel-from-orgs #"/")]
    (when (and (>= (count seg) 4)
               (not (str/starts-with? (nth seg 1) "."))
               (contains? (set source-roots) (nth seg 2)))
      {:org (nth seg 0) :repo (nth seg 1) :root (nth seg 2)
       :rel (str/join "/" (drop 3 seg))})))

(defn- scan-files
  "orgs 配下の src/test にある全ファイルを 1 回の find で列挙し、repo ごとに
   {:provided #{ns} :kotoba #{ns} :dirs #{prefix}} を畳む。ファイルの中身は
   読まない —— path 規則は Clojure が実際に使う解決規則そのものなので、ns 宣言より
   path の方が『load できるか』の直接の証拠になる。"
  [root]
  (let [orgs (.join path root "orgs")
        {:keys [out]} (sh "find" [orgs
                                  "-name" "node_modules" "-prune" "-o"
                                  "-name" ".git" "-prune" "-o"
                                  "-name" "target" "-prune" "-o"
                                  "-name" ".shadow-cljs" "-prune" "-o"
                                  "-type" "f" "-print"] {})]
    (reduce
     (fn [acc abs]
       (if-let [{:keys [org repo rel]} (split-source-path (.relative path orgs abs))]
         (let [ext (.extname path abs)
               base (subs rel 0 (- (count rel) (count ext)))
               nsish (-> base (str/replace "/" ".") (str/replace "_" "-"))
               parent (.dirname path rel)
               k [org repo]]
           (cond-> acc
             (contains? source-exts ext) (update-in [k :provided] (fnil conj #{}) nsish)
             (= ".kotoba" ext) (update-in [k :kotoba] (fnil conj #{}) nsish)
             (not= parent ".") (update-in [k :dirs] (fnil conj #{})
                                          (-> parent (str/replace "/" ".")
                                              (str/replace "_" "-")))))
         acc))
     {} (remove str/blank? (str/split-lines out)))))

(defn- scan-requires
  "orgs 配下の .clj/.cljc/.cljs から require されている ns を 1 回の grep で集め、
   repo ごとに畳む。

   ns 形を正しくパースせず grep で拾うのは意図的 —— reader conditional・`#?@`・
   macro 展開を含む 40,000 ファイルを完全にパースするより安い。

   ただし **`[a.b.c` を素朴に拾うと散文まで拾う**（実測: `[elecequipmfg.governor's`
   のような docstring 中の語、`[apparel.*` のようなワイルドカード表記が 104 件中に
   混じった）。そこで alias 節（`:as` / `:refer` / `:as-alias` / `:include-macros`）が
   続くことを要求する。alias 無しの `[foo.bar]` 形は取りこぼすが、**取りこぼしより
   誤検出の方が gate にとって致命的**である —— 狼少年になった gate は読まれなくなる。"
  [root]
  (let [orgs (.join path root "orgs")
        {:keys [out]} (sh "grep" ["-roE" "--include=*.clj" "--include=*.cljc" "--include=*.cljs"
                                  "--exclude-dir=node_modules" "--exclude-dir=.git"
                                  "--exclude-dir=target"
                                  (str "\\[[a-z][a-zA-Z0-9.*+!_'?<>=-]*\\.[a-zA-Z0-9.*+!_'?<>=-]+"
                                       "[[:space:]]+:(as|refer|as-alias|include-macros)[[:space:]]")
                                  orgs]
                          {:stdio ["ignore" "pipe" "ignore"]})]
    (reduce
     (fn [acc line]
       (let [i (str/index-of line ":[")]
         (if-not i
           acc
           (let [abs (subs line 0 i)
                 ;; `[ns :as alias` まで拾っているので ns だけ切り出す
                 req (first (str/split (subs line (+ i 2)) #"[[:space:]]" 2))
                 req (str/trim (str (first (str/split req #"\s"))))]
             (if (or (external? req) (str/blank? req))
               acc
               (if-let [{:keys [org repo]} (split-source-path (.relative path orgs abs))]
                 (update acc [org repo] (fnil conj #{}) req)
                 acc))))))
     {} (remove str/blank? (str/split-lines out)))))

(defn -main [& args]
  (let [flags (apply hash-map (map str args))
        root (or (get flags "--root") (.cwd js/process))
        min-repos (js/parseInt (or (get flags "--min-repos") "50"))
        files (scan-files root)
        repos (keys files)]
    (when (< (count repos) min-repos)
      (println (str "FAIL source root を持つ repo が " (count repos) " 件しか見つからない"
                    " (--min-repos " min-repos ")。"
                    "\n  west checkout が空か --root が違う。**走査対象ゼロを合格にしない。**"))
      (js/process.exit 1))
    (let [requires (scan-requires root)
          provided (into #{} (mapcat :provided (vals files)))
          kotoba-only (into #{} (mapcat :kotoba (vals files)))
          dirs (into #{} (mapcat :dirs (vals files)))
          findings
          (for [[[org repo] reqs] requires
                req reqs
                :when (not (contains? provided req))
                :let [parent (str/join "." (butlast (str/split req #"\.")))
                      kotoba? (contains? kotoba-only req)
                      sibling? (and (seq parent) (contains? dirs parent))]
                :when (or kotoba? sibling?)]
            {:kind (if kotoba? :kotoba-orphan :sibling-gap)
             :ns req
             :by (str org "/" repo)})
          {orphans :kotoba-orphan siblings :sibling-gap} (group-by :kind findings)
          render (fn [fs*]
                   (doseq [[ns g] (sort-by key (group-by :ns fs*))
                           :let [by (sort (distinct (map :by g)))]]
                     (println (str "\n  " ns))
                     (println (str "      参照している repo (" (count by) "): "
                                   (str/join ", " by)))))]
      (println (str "verify-require-graph: repo " (count repos)
                    " / provided ns " (count provided)
                    " / .kotoba のみ " (count kotoba-only)))
      (when (seq siblings)
        (println (str "\n報告のみ（fail させない）— 親ディレクトリは手元にあるが ns が無い: "
                      (count (distinct (map :ns siblings))) " 件"))
        (println "  提供元 repo が未 checkout でも同じ形になるので、これだけでは壊れている証拠にならない。")
        (render siblings))
      (if (empty? orphans)
        (println (str "\nOK — .kotoba へ移行された namespace を、"
                      ".clj/.cljc/.cljs のまま require している repo は無い"))
        (do
          (println (str "\nFAIL " (count (distinct (map :ns orphans)))
                        " namespace が .kotoba へ移行され、参照側が追従していない（参照 "
                        (count orphans) " 件）:"))
          (render orphans)
          (println (str "\n提供元には同じ path に .kotoba があり、.clj/.cljc/.cljs が無い。"
                        "**未 checkout では説明できない** —— その repo は手元にある。\n"
                        "直し方: 移行する側が『消費側が load できる経路を持ってから src/ の "
                        ".cljc を外す』（ADR-2608071000 決定 1）。既に外してしまっている場合は、"
                        "参照側を先に移すか、移行を巻き戻す。"))
          (js/process.exit 1))))))

(apply -main (drop 3 (js->clj js/process.argv)))
