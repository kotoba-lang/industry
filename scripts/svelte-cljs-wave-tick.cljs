#!/usr/bin/env nbb
;; scripts/svelte-cljs-wave-tick.cljs — Svelte → cljs 移行の候補を**測る**
;; （ADR-2608260900）。決定論。モデルを起こさない。
;;
;;   nbb --classpath ".:scripts/nbb_compat" scripts/svelte-cljs-wave-tick.cljs [--limit 4]
;;
;; 姉妹 tick（industry-stack-wave / itonami-os-connect）と同型で、loop は
;; 「候補があるときだけ」claude を起こす。手順の正本は skill `svelte-cljs-wave`
;; であって、ここではない（2 箇所に書くと必ず片方が古くなる）。
;;
;; ## 候補の選び方
;;
;; **1 repo あたりの .svelte 行数が少ない順。** 完走確率が高い順に消す。
;; 大物（app-aozora-svelte 34k 行、svelte-design-system 5k 行 55 component）は
;; この loop の対象にしない —— design system の移植は jp-go-dds への上流拡張
;; という別の判断を要する仕事で、機械的な波で流すものではない。
;;
;; ## 除外は宣言する（黙って除くと移行が進んだように見える）
;;
;;   node_modules / .git / dist / build / out / target / .shadow-cljs / .claude
;;   .claude/worktrees   使い捨て。同じファイルが複数 worktree に複製される
;;   contracts/lib/      vendored な第三者サイト。我々が書いていない
;;   m365-archive/       OneDrive 履歴の DataLad dataset。live なコードではない
;;   :max-files 超       1 repo に多数ある repo はこの波の対象外（上記の理由）
;;
;; ## exit code
;;
;;   0  測れた（候補 0 本でも 0。これは gate ではなく測定器）
;;   2  **測れなかった** —— orgs/ が無い、find が失敗した、走査 0 件。
;;      0 と区別されることが要点（ADR-2608136000）。

(ns svelte-cljs-wave-tick
  (:require [clojure.string :as str]))

(def fs (js/require "fs"))
(def cp (js/require "child_process"))
(def os (js/require "os"))
(def path (js/require "path"))

(def home (.homedir os))
(def root (or (aget (.-env js/process) "COM_JUNKAWASAKI_ROOT")
              (str home "/github/com-junkawasaki")))
(def ledger-file (str home "/.gftd/svelte-cljs-wave-tick.ledger.edn"))

(def args (vec *command-line-args*))
(defn- arg [flag default]
  (if-let [i (first (keep-indexed #(when (= %2 flag) %1) args))]
    (js/parseInt (nth args (inc i)) 10)
    default))

(def limit (arg "--limit" 4))
;; 1 repo あたりこの本数を超える Svelte は、この機械的な波の対象にしない。
(def max-files (arg "--max-files" 2))

(def vendored-prefixes
  ["orgs/etzhayyim/root/50-infra/vultr/geth-private/contracts/lib/"
   "orgs/gftdcojp/apps-gftdcojp/50-infra/vultr/geth-private/contracts/lib/"
   "orgs/gftdcojp/m365-archive/"])

(defn- vendored? [rel] (some #(str/starts-with? rel %) vendored-prefixes))
(defn- worktree? [rel] (str/includes? (str "/" rel) "/.claude/worktrees/"))

(defn- scan!
  "orgs/ を 1 パス走査して .svelte の rel-path を返す。失敗時 nil。
   find の stdout を execFileSync でパイプ受けすると、この機械では 11.5s の
   走査が 69s になる（実測 2026-08-26、load 100 超）。ファイルに落として読む。"
  []
  (let [tmp (path.join (or (aget (.-env js/process) "TMPDIR") "/tmp")
                       (str "scw-" (.getTime (js/Date.)) ".txt"))
        args ["orgs"
              "(" "-name" "node_modules" "-o" "-name" ".git" "-o" "-name" "dist"
              "-o" "-name" "build" "-o" "-name" "out" "-o" "-name" "target"
              "-o" "-name" ".shadow-cljs" "-o" "-name" ".claude" ")" "-prune" "-o"
              "-name" "*.svelte" "-type" "f" "-print"]]
    (try
      (let [fd (.openSync fs tmp "w")]
        (try (.execFileSync cp "find" (clj->js args)
                            #js {:cwd root :stdio #js ["ignore" fd "ignore"]})
             (finally (.closeSync fs fd)))
        (let [rels (->> (str/split-lines (str (.readFileSync fs tmp "utf8")))
                        (remove str/blank?)
                        (remove worktree?)
                        (remove vendored?)
                        vec)]
          (.unlinkSync fs tmp)
          rels))
      (catch :default _ nil))))

(defn- repo-of [rel] (str/join "/" (take 3 (str/split rel #"/"))))

(defn- lines-of [rel]
  (try (count (str/split-lines (str (.readFileSync fs (path.join root rel) "utf8"))))
       (catch :default _ 0)))

(defn- svelte-dir
  "その repo の svelte ディレクトリ（appview/<name>/svelte 等）。無ければ nil。"
  [rels]
  (some (fn [r]
          (let [i (str/index-of r "/svelte/")]
            (when i (subs r 0 (+ i 7)))))
        rels))

(defn- custody-gated?
  "その repo が **machine-enforced な custody 契約**を持っていれば true。触らない。

   実測 2026-08-26: `cloud-itonami/app-global` に投げた agent が正しく拒否した ——
   あの repo の `docs/verify-custody.cljs` は「保管ファイル 24 / 87,245 バイト /
   出所 tree hash」を検査して**今日 PASS する**。`svelte/` を消すと再構成 hash が
   記録と恒久的に食い違い、直しようのない FAIL になる。文書の陳腐化ではなく
   **契約違反**なので、移行するなら migration.edn と検査器を書き換える統治判断が
   要る —— 移行バッチの agent が単独で決めることではない。

   同じ形の repo が 6 件ある（app-global / app-maps / app-roukisho / app-saiban /
   app-shomeisyashin / app-sre）。除外しないと loop が毎周これを選び、agent が
   毎周正しく拒否して、token だけが減る。"
  [{:keys [repo]}]
  (try (.existsSync fs (path.join root repo "docs" "verify-custody.cljs"))
       (catch :default _ false)))

(defn- in-flight?
  "その repo に agent/cljs-migration branch が remote に在れば true（= 別の波が
   まだ持っている、または前の波が landing に失敗して残した）。触らない。
   remote 名は west の慣習で org 名（`origin` ではない）。"
  [{:keys [repo org]}]
  (try
    (let [r (.spawnSync cp "git"
                        (clj->js ["-C" (path.join root repo)
                                  "ls-remote" "--heads" org "agent/cljs-migration"])
                        #js {:encoding "utf8" :timeout 30000})]
      ;; 問い合わせに失敗したら「在るかもしれない」側に倒す（触らない）。
      (if (not= 0 (aget r "status"))
        true
        (not (str/blank? (str (aget r "stdout"))))))
    (catch :default _ true)))

(defn -main []
  (let [rels (scan!)]
    (when (nil? rels)
      (println "REFUSING\tfind(1) failed — the scan did not run")
      (println "SCANNED\t0")
      (js/process.exit 2))
    (when (zero? (count rels))
      (println "REFUSING\tscanned 0 .svelte files — the walk is broken, not the tree")
      (println "SCANNED\t0")
      (js/process.exit 2))

    (let [by-repo (reduce (fn [m r] (update m (repo-of r) (fnil conj []) r)) {} rels)
          pool    (count by-repo)
          cands   (->> by-repo
                       (keep (fn [[repo fl]]
                               (when (<= (count fl) max-files)
                                 (let [n (reduce + (map lines-of fl))]
                                   {:repo repo
                                    :files (count fl)
                                    :lines n
                                    :svelte-dir (svelte-dir fl)
                                    :org (second (str/split repo #"/"))
                                    :name (nth (str/split repo #"/") 2)}))))
                       (filter :svelte-dir)
                       (sort-by (juxt :lines :repo))
                       vec)
          ;; in-flight 判定は 1 repo 1 network round trip なので、順位上位だけに当てる。
          ;; custody 判定はローカル（安い）ので全候補に当てる。
          custody (atom [])
          open    (->> cands
                       (remove (fn [c]
                                 (when (custody-gated? c)
                                   (swap! custody conj (:repo c)) true)))
                       vec)
          ;; in-flight 判定は 1 repo 1 network round trip なので、順位上位だけに当てる。
          ranked  (take (* 4 limit) open)
          skipped (atom [])
          picked  (->> ranked
                       (remove (fn [c]
                                 (when (in-flight? c)
                                   (swap! skipped conj (:repo c)) true)))
                       (take limit)
                       vec)
          rec {:at (.toISOString (js/Date.))
               :pool pool
               :files (count rels)
               :eligible (count (filter #(<= (count (second %)) max-files) by-repo))
               :limit limit
               :custody-skipped @custody
               :in-flight-skipped @skipped
               :candidates picked}
          cands picked]

      (println (str "SCANNED\t" (count rels)))
      (println (str "POOL\t" pool "\trepos still carrying .svelte"))
      (println (str "ELIGIBLE\t" (:eligible rec) "\t(<=" max-files " files/repo)"))
      (println (str "CUSTODY-SKIPPED\t" (count @custody)
                    (when (seq @custody) (str "\t" (str/join " " @custody)))))
      (println (str "IN-FLIGHT-SKIPPED\t" (count @skipped)
                    (when (seq @skipped) (str "\t" (str/join " " @skipped)))))
      (println (str "CANDIDATES\t" (count cands)))
      (doseq [c cands]
        (println (str "  " (str/join "\t" [(:repo c) (str (:files c) "f")
                                           (str (:lines c) "L") (:svelte-dir c)]))))
      (try (.appendFileSync fs ledger-file (str (pr-str rec) "\n"))
           (catch :default e (println "ledger 追記失敗:" (str e))))
      (js/process.exit 0))))

(-main)
