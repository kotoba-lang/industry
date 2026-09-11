#!/usr/bin/env nbb
;; manifest-reality-check.cljs — manifest 実態乖離の gate。
;;
;; 展開済みの superproject tree を受け取り、`manifest/west.yml` と
;; `manifest/repos.edn` を `kotoba-lang/manifest-reality` の検査に掛ける。
;;
;; **検査ロジックはここに複製しない。** 正本は kotoba-lang/manifest-reality で、
;; この gate は入力を用意して呼ぶだけ。GitHub Actions 側と fleet 側で別実装を
;; 持つと、片方だけ通る状態が黙って生まれる（repository-roles-check.cljs と
;; 同じ理由・同じ形）。
;;
;; ライブラリは **git dep ではなくノード上へ clone して classpath に足す**。
;; ノードに clojure/maven を前提させないため（gate は nbb だけで走る）。
;; 取得は public repo なので token 不要 — ノードに credential を置かない
;; 不変条件を崩さない。
;;
;; **cadence を分ける。** ここが走らせるのはファイルだけで完結する検査
;; （構造 / org 整合 / stale key）に限る。GitHub 突合（unregistered /
;; orphan / duplicate / credential-shaped）は API を約 4,000 コール使うので
;; 5 分 tick には載せず、--with-reality を付けた日次 run でのみ有効にする。
;;
;; ノード側で:
;;   npx nbb manifest-reality-check.cljs <superproject-dir> [--with-reality]
(ns fleet-ci.gates.manifest-reality-check
  (:require ["node:child_process" :as cp]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            [cljs.reader :as reader]
            [clojure.string :as str]))

(def args (vec *command-line-args*))
(def root (or (first (remove #(str/starts-with? % "--") args)) "."))
(def with-reality? (boolean (some #{"--with-reality"} args)))

(def lib-repo "https://github.com/kotoba-lang/manifest-reality.git")
;; 正本の pin。前進させるときは gate と一緒にレビューする（黙って tip を追わない）。
(def lib-sha "0773406")

(defn- die [msg] (println msg) (js/process.exit 1))

(defn- lib-usable?
  "キャッシュが**実際に使えるか**を見る。ディレクトリの存在で判定してはいけない。

  実測 2026-08-05: levi の `/tmp/manifest-reality-0773406` はディレクトリだけ
  残って中身が消えていた（`src` 配下のファイル数 0、`.git` は
  `fatal: not a git repository`）。macOS の /tmp 定期パージが中身だけ消し、
  `existsSync` は true を返し続けるので **二度と再 clone されない**。
  この gate はそれで 254 回連続して落ちていた —— 永久に赤い gate は
  signal を運ばない（ADR-2607300400）。

  判定は「この gate が実際に require する名前空間のファイルが在るか」。
  clone の成否でも sha の一致でもなく、使う物そのものを見る。"
  [dir]
  (let [entry (path/join dir "src" "manifest_reality" "checks.cljc")
        alt (path/join dir "src" "manifest_reality" "checks.cljs")]
    (or (fs/existsSync entry) (fs/existsSync alt))))

(defn- fetch-lib! []
  (let [dir (path/join (os/tmpdir) (str "manifest-reality-" lib-sha))]
    (when-not (lib-usable? dir)
      ;; 壊れたキャッシュは黙って使わず捨てる。
      (when (fs/existsSync dir)
        (println (str "cache at " dir " is unusable — removing and re-cloning"))
        (fs/rmSync dir #js {:recursive true :force true}))
      (cp/execSync (str "git clone --quiet " lib-repo " " dir))
      (cp/execSync (str "git -C " dir " checkout --quiet " lib-sha))
      (when-not (lib-usable? dir)
        (die (str "FLEET-CI: cloned " lib-repo " but " dir
                  "/src/manifest_reality/checks.* is still missing"))))
    (path/join dir "src")))

(defn- slurp* [p]
  (when (fs/existsSync p) (fs/readFileSync p "utf8")))

(defn -main []
  (let [west-p (path/join root "manifest" "west.yml")
        repos-p (path/join root "manifest" "repos.edn")
        west (or (slurp* west-p) (die (str "missing " west-p)))
        repos-txt (or (slurp* repos-p) (die (str "missing " repos-p)))
        m (first (reader/read-string repos-txt))
        km {:rad-rids (reader/read-string (:manifest.repos/rad-rids m))
            :datalad (reader/read-string (:manifest.repos/datalad m))
            :archived (reader/read-string (:manifest.repos/archived m))}
        libsrc (fetch-lib!)
        ;; ライブラリは別プロセスの nbb で呼ぶ（この gate の classpath を汚さない）
        payload (path/join (os/tmpdir) (str "mr-in-" (.getTime (js/Date.)) ".edn"))
        runner (path/join (os/tmpdir) (str "mr-run-" (.getTime (js/Date.)) ".cljs"))]
    (fs/writeFileSync payload (pr-str {:west west :keyed-maps km}))
    (fs/writeFileSync runner
      (str "(ns mr-run (:require [\"fs\" :as fs] [cljs.reader :as r]"
           " [manifest-reality.checks :as c]))\n"
           "(let [in (r/read-string (fs/readFileSync \"" payload "\" \"utf8\"))\n"
           "      f (c/audit in)]\n"
           "  (println (pr-str {:findings f :errors (count (c/errors f))"
           " :summary (c/summary f)})))\n"))
    (let [out (str (cp/execSync (str "npx nbb --classpath " libsrc " " runner)))
          {:keys [findings errors summary]} (reader/read-string (str/trim out))]
      (println "manifest-reality:" (count findings) "findings," errors "errors")
      (doseq [[k n] (sort-by (comp str key) summary)] (println "  " (pr-str k) n))
      (doseq [f (filter #(= :error (:severity %)) findings)]
        (println "  ERROR" (:check f) (:subject f) (pr-str (:detail f))))
      (when-not with-reality?
        (println "  (GitHub 突合は --with-reality 指定時のみ — API 約 4,000 コール)"))
      (js/process.exit (if (pos? errors) 1 0)))))

(-main)
