#!/usr/bin/env nbb
;; verify-doc-reality.cljs — agent 指示（CLAUDE.md / AGENTS.md）が ADR と tree の
;; 実態と食い違っていないかを **決定論で** 測る。ADR-2609072600。
;;
;; docs-audit bot の床。あの bot はこれまでモデルの目視だけで文書鮮度を見ており、
;; 「0 findings」が「測って問題が無かった」なのか「見落とした」なのか出力から
;; 区別できなかった —— CLAUDE.md が repo-wide の失敗クラスとして名指ししている形。
;;
;;   nbb --classpath ".:scripts/nbb_compat" scripts/verify-doc-reality.cljs [<dir>] [--findings]
;;
;; exit code:
;;   0  走査して findings 0
;;   1  findings あり
;;   2  REFUSED — 走査できなかった（入力が無い / ADR が 1 件も読めない）。
;;      0 でも 1 でもない値にするのは、「測れなかった」を「問題なし」と
;;      同じ顔で返さないため。
;;
;; 測るもの（3 つとも機械で答えが出るものだけ。中身の正しさは測らない）:
;;
;;   1. cited-superseded / cited-missing
;;      agent 指示が引く `ADR-<stamp>` が superseded な ADR を指していないか。
;;      ⚠ 経緯として旧 ADR を引くのは正当なので、**その行の周辺に demurrer**
;;      （旧 / 当時 / superseded / 反転 / 撤去 / reverse …）が在れば finding に
;;      しない。demurrer 語彙は rule-kaizen と同じ考え方で、ここに直書きする
;;      （corpus から導かない）。
;;
;;   2. dead-path
;;      superproject 起点の path 参照（`scripts/…` `manifest/…` `90-docs/…`
;;      `.claude/…` `70-tools/…`）が git にも disk にも無い。
;;      **glob・placeholder・子リポ相対は測らない**（答えられないものを
;;      「無い」と報告しないため）。
;;
;;   3. agents-md-stale
;;      AGENTS.md が CLAUDE.md からの生成物として最新か（ADR-2609062600）。
;;      正本の `gen-agents-md.cljs --check` に委譲し、2 は 2 のまま伝播する。

(require '[clojure.string :as str])

(def fs (js/require "node:fs"))
(def path (js/require "node:path"))
(def cp (js/require "node:child_process"))

(def argv (vec *command-line-args*))
(def root (or (first (remove #(str/starts-with? % "--") argv)) "."))
(def show-findings? (boolean (some #{"--findings"} argv)))
(def docs ["CLAUDE.md" "AGENTS.md"])

(defn- rd [p] (str (.readFileSync fs (.join path root p) "utf8")))
(defn- exists? [p] (.existsSync fs (.join path root p)))

(defn- refuse! [msg]
  (println "SCANNED\t0")
  (println (str "Refusing to report a verdict: " msg))
  (js/process.exit 2))

;; ---------- inputs ----------
(when-let [missing (seq (remove exists? docs))]
  (refuse! (str (str/join ", " missing) " が tree に無い")))

(def adr-dir "90-docs/adr")
(when-not (exists? adr-dir) (refuse! (str adr-dir " が tree に無い")))

;; ADR の status は EDN を読まずに拾う（reader を通らない文書 1 件で
;; 面全体を落とさないため。読めなかった件数は必ず申告する）。
(def adr-files
  (vec (filter #(str/ends-with? % ".edn")
               (js->clj (.readdirSync fs (.join path root adr-dir))))))
(when (< (count adr-files) 100)
  (refuse! (str adr-dir " に ADR が " (count adr-files) " 件しか無い —— tree が届いていない")))

(def stamp->status
  (reduce
    (fn [m f]
      (let [stamp (second (re-find #"^(\d{6,12})" f))]
        (if-not stamp m
          (let [txt (try (rd (str adr-dir "/" f)) (catch :default _ nil))
                st  (when txt
                      (or (second (re-find #":adr/status\s+\"([a-z-]+)\"" txt))
                          (second (re-find #":adr/status\s+:([a-z-]+)" txt))))]
            (update m stamp (fnil conj []) {:file f :status st})))))
    {} adr-files))

;; demurrer = 「その決定はもう現行ではない」と文書自身が言っている語。
;; **弱い語を入れない。** `経緯` `歴史` `だった` は現行の ADR を指すときにも
;; 普通に使われるので、入れると本物の drift まで黙らせる（実測 2026-09-07:
;; `詳細・調査経緯は ADR-… を参照` が注入した finding を 2 件とも抑止した）。
;; 抑止しすぎる検査は、抑止しない検査より悪い —— 緑が意味を持たなくなる。
(def demurrers
  ["旧" "当時" "superseded" "Superseded" "supersede" "supersedes"
   "反転" "撤去" "削除した" "reverse" "reversed" "誤り" "廃止" "retire"])

(defn- near [lines i n]
  (str/join "\n" (subvec lines (max 0 (- i n)) (min (count lines) (+ i n 1)))))

;; ---------- 1. citations ----------
(def citation-findings
  (vec (for [doc docs
             :let [lines (vec (str/split-lines (rd doc)))]
             [i line] (map-indexed vector lines)
             m (re-seq #"ADR-(\d{6,12})" line)
             :let [stamp (second m)
                   cands (get stamp->status stamp)
                   sup (filter #(= "superseded" (:status %)) cands)
                   ctx (near lines i 2)
                   ack? (some #(str/includes? ctx %) demurrers)]
             :when (and (not ack?) (or (nil? cands) (seq sup)))]
         {:kind (if (nil? cands) :cited-missing :cited-superseded)
          :doc doc :line (inc i) :stamp stamp
          :note (if (nil? cands)
                  "この stamp の ADR が 90-docs/adr に無い"
                  (str "superseded: " (:file (first sup))))})))

;; ---------- 2. dead paths ----------
(def tracked
  (let [r (.spawnSync cp "git" (clj->js ["-C" root "ls-files"])
                      #js {:encoding "utf8" :maxBuffer 268435456})]
    (if (zero? (or (.-status r) 1))
      (set (str/split-lines (str (.-stdout r))))
      (refuse! "git ls-files が失敗 —— tree の実在を判定できない"))))

;; **superproject が一意に所有する root だけを測る。** 子リポも `scripts/` や
;; `90-docs/` や `docs/` を持つので、`scripts/vendor.cljs`（jp-go-dds のもの）や
;; `90-docs/coscientist/`（network-isekai のもの）は superproject には無いが
;; 死んでもいない —— 同じ文字列が別の repo の path を指している。
;; 区別する手段が文書側に無いので、**答えられない範囲は測らない**。
;; 何を測らなかったかは出力の `path-scope` 行が申告する。
(def path-roots ["manifest/" "scripts/fleet-ci/" ".claude/hooks/" "90-docs/adr/"])
(defn- measurable-path? [s]
  (and (some #(str/starts-with? s %) path-roots)
       (not (str/includes? s "*"))
       (not (str/includes? s "<"))
       (not (str/includes? s "…"))
       (not (str/includes? s " "))))

(defn- placeholder? [p]
  ;; `iteration-NN.edn` `shard-XX/` のような雛形。2 文字以上の連続大文字を
  ;; placeholder とみなす（この文書群の慣習）。
  (boolean (re-find #"[A-Z]{2,}" p)))

(defn- resolves? [p]
  (let [bare (str/replace p #"/$" "")]
    (or (contains? tracked p)
        (contains? tracked bare)
        (exists? p)
        ;; directory 参照と、拡張子を省いた ADR stamp 参照
        (some #(str/starts-with? % (str bare "/")) tracked)
        (some #(str/starts-with? % bare) tracked))))

(def path-refs
  (vec (for [doc docs
             :let [lines (vec (str/split-lines (rd doc)))]
             [i line] (map-indexed vector lines)
             m (re-seq #"`([^`]+)`" line)
             :let [p (second m)]
             :when (and (str/includes? p "/") (not (str/starts-with? p "http")))]
         {:doc doc :line (inc i) :path p :ctx (near lines i 2)})))

(def path-findings
  (vec (for [{:keys [doc line path ctx]} path-refs
             :when (and (measurable-path? path)
                        (not (placeholder? path))
                        (not (resolves? path))
                        ;; 文書自身が「撤去済み」と言っているものは finding にしない
                        (not (some #(str/includes? ctx %) demurrers)))]
         {:kind :dead-path :doc doc :line line :path path
          :note "git にも disk にも無い"})))

;; ---------- 3. AGENTS.md generation invariant ----------
(def gen "scripts/gen-agents-md.cljs")
(def gen-status
  (if-not (exists? gen)
    :absent
    (let [r (.spawnSync cp "nbb" (clj->js [gen "--check"])
                        #js {:cwd root :encoding "utf8"})]
      (case (or (.-status r) 2) 0 :ok 1 :stale :refused))))

(when (= gen-status :refused)
  (refuse! (str gen " --check が答えられなかった（exit 2）")))

(def gen-findings
  (case gen-status
    :stale [{:kind :agents-md-stale :doc "AGENTS.md" :line 1
             :note "CLAUDE.md からの再生成が要る（nbb scripts/gen-agents-md.cljs）"}]
    :absent [{:kind :generator-absent :doc "AGENTS.md" :line 1
              :note (str gen " が tree に無い —— ADR-2609062600 の生成規律が外れている")}]
    []))

;; ---------- report ----------
(def findings (vec (concat citation-findings path-findings gen-findings)))
(def unreadable (count (remove :status (mapcat val stamp->status))))

(println (str "SCANNED\t" (+ (count docs) (count adr-files))))
(println (str "docs=" (count docs)
              " adr=" (count adr-files)
              " adr-status-unreadable=" unreadable
              " agents-md=" (name gen-status)))
(println (str "path-scope=" (str/join "," path-roots)
              " path-refs-seen=" (count path-refs)
              " path-refs-measured=" (count (filter #(measurable-path? (:path %)) path-refs))
              "  (範囲外の path 参照は測っていない — 子リポ相対と区別できないため)"))
(println (str "findings=" (count findings)))
(doseq [[k v] (sort-by key (frequencies (map :kind findings)))]
  (println (str "  " (name k) "\t" v)))
(when show-findings?
  (doseq [f findings]
    (println (str "  " (name (:kind f)) "  " (:doc f) ":" (:line f)
                  "  " (or (:stamp f) (:path f)) "  — " (:note f)))))
(js/process.exit (if (seq findings) 1 0))
