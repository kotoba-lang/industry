#!/usr/bin/env nbb
;; itonami-regenerate.cljs — cloud-itonami actor の demo を **実 actor で再生成し、
;; committed 済みの生成物と一致するか**を見る fleet gate。
;;
;; オーナー指示（2026-08-10）「workflow は github は使わずに, murakumo を使うように
;; して」。したがって **この gate は `.github/workflows/` を一切読まない。**
;; 必要な情報はすべて repo 自身の `deps.edn` から導く。
;;
;; ## なぜ workflow を読む方式をやめたか（実測 2026-08-10）
;;
;; 直前まで `gates/github_workflow_run.cljs` が regenerate.yml を翻訳して走らせて
;; いた。それで **25 本中 8 本が落ちた** —— 落ちた理由は fleet ではなく workflow 側で、
;; `regenerate.yml` が `deps.edn` の `:local/root` を**全部 checkout していなかった**:
;;
;;   6910←inkan / 6311←org-ethereum-jsonrpc / 6312←isic-6311 / 7310←langchain-store /
;;   6420←kototama,langchain-store / 2910←isic-0710,isic-2410,isic-2930 /
;;   6612←kototama / 6622←kototama
;;
;; 6910 は `inkan` 依存が入った 2026-08-09 から壊れており、Actions は 08-04 に
;; 止まっていたので**誰にも報告されなかった**。つまり「checkout 一覧を人が
;; workflow に書き写す」という工程そのものが故障点だった。
;;
;; **依存の正本は deps.edn であって workflow ではない。** ここから導けば書き忘れは
;; 構造的に起きない —— 上記 8 本は、workflow ファイルを 1 行も触らずにこの gate で
;; 通るようになる（このワークスペースの token は `workflow` OAuth scope を持たない
;; ので、そもそも workflow は直せない）。
;;
;; ## やること
;;
;;   1. 使い捨て workspace に `orgs/<org>/<repo>` の形で tree を置く
;;      （`:local/root` が `../../kotoba-lang/x` と `../x` の 2 形なので深さ 2 が要る）
;;   2. deps.edn の `:local/root` を**推移的に**辿り、足りない依存を clone する
;;   3. 再生成コマンド（既定 `clojure -M:dev:render-html`）を repo 直下で走らせる
;;   4. 実行前後の sha256 を突き合わせ、**committed 済みの生成物が動いたら落とす**
;;
;; 4 が要点。committed 済みの値を読むだけの経路では、**内部整合を保ったまま
;; 手編集された生成物**を検出できない（CLAUDE.md「生成物を検査する gate は
;; sha256 を見る」）。
;;
;; ## 使い方
;;
;;   npx nbb itonami-regenerate.cljs <repo-dir> --org <org> [--cmd "clojure -M:dev:render-html"]
;;
;; gate 登録は :script-args ["--org" "cloud-itonami"]。

(ns itonami-regenerate
  (:require [clojure.string :as str]))

(def fs (js/require "node:fs"))
(def path (js/require "node:path"))
(def os (js/require "node:os"))
(def cp (js/require "node:child_process"))
(def crypto (js/require "node:crypto"))

(def args (vec *command-line-args*))

(defn- flag [nm default]
  (let [i (.indexOf args nm)]
    (if (neg? i) default (nth args (inc i) default))))

(def root (.resolve path (or (first (remove #(str/starts-with? % "--") args)) ".")))
(def org (flag "--org" nil))
(def regen-cmd (flag "--cmd" "clojure -M:dev:render-html"))

(defn- die! [code & msg]
  (println (str "FLEET-CI: " (str/join " " msg)))
  (js/process.exit code))

(defn- slurp* [p] (try (.toString (.readFileSync fs p) "utf8") (catch :default _ nil)))

;; `regen-cmd` の実体がこのノードに無いなら、この gate は repo について何も
;; 言えない。**赤ではなく棄権する。**
;;
;; 実測 2026-08-19: この gate 群 31 本は `:cap` を宣言しておらず、placement が
;; どこにでも置いていた。`clojure` を持たない 3 ノード（dan / issachar /
;; naphtali）で 13 fail / 0 pass、持つ 6 ノードで 51 pass / 2 fail。落ちた側の
;; 中身は `bash: line 4: clojure: command not found` で、repo の欠陥ではない。
;;
;; ADR-2608198600 が `:cap :jvm` を宣言して**誤配置を止めた**。ここは、それでも
;; 誤配置が起きたときに**赤として記録されないようにする**方。`:cap` を書き忘れた
;; 次の gate 群にも効く（同じ欠陥は既に 3 回起きている）。
(defn- executable-exists? [bin]
  (zero? (.-status (.spawnSync cp "sh" #js ["-c" (str "command -v " bin)]
                               #js {:stdio "ignore"}))))

(defn- refuse-if-toolchain-absent! []
  (let [bin (first (str/split (str/trim regen-cmd) #"\s+"))]
    (when-not (executable-exists? bin)
      ;; tick.cljs の `cannot-answer-sentinel` と同じ綴り。tick はこれを見た run を
      ;; 台帳に書かず、次の tick が別のノードで試す。
      (println (str "FLEET-CI: cannot answer on " (or (.-HOSTNAME js/process.env)
                                                      (.hostname os))
                    " — the regeneration command needs `" bin
                    "`, which is not on this node. This says nothing about "
                    (or org "the repo") "; the gate needs :cap :jvm placement."))
      (js/process.exit 1))))

;; ---------------------------------------------------------------------------
;; deps.edn の :local/root
;;
;; **EDN パーサを積まない。** gate は 1 ファイルとしてノードに配られるので依存を
;; 増やせない（nbb に load-file は無い）。必要なのは `:local/root "…"` の値だけ
;; なので正規表現で足りる —— **足りない場合は落とす**（下の org 推定を参照）。

(defn- local-roots
  "deps.edn の本文 -> 相対パスの集合。alias 内のものも拾う（:dev の
   :override-deps に居ることがある）。"
  [text]
  (->> (re-seq #":local/root\s+\"([^\"]+)\"" (str text))
       (map second)
       distinct
       vec))

(defn- resolve-dep
  "ある deps.edn の置き場所 dir から見た相対パス -> {:abs :org :name}。

   実測した形（2026-08-10、25 repo + 推移依存）:
     ../../<org>/<name>   73 件
     ../<name>            12 件
     ../../cloud-itonami/<name>  1 件

   **org は解決後の絶対パスから採る**（`<ws>/orgs/<org>/<name>` 配置なので
   `basename(dirname(abs))`）。相対形から場合分けしない。

   ⚠ ここは一度間違えた。`../<name>` を「**検査対象 repo と同じ org**」と決め打ちして
   いたが、`../<name>` が現れるのは**推移依存の中**でもある —— 実測 2026-08-10:
   isic-6420 → kototama（kotoba-lang）→ `../kotoba`。kototama から見た `../kotoba` は
   `kotoba-lang/kotoba` なのに、検査対象の org を使って `cloud-itonami/kotoba` を
   clone しようとし `Repository not found` で落ちた。**相対パスは、それが書かれて
   いるファイルの位置から解決する。**

   ⚠ **lib 座標（`io.github.<org>/<name>`）からも org を導かないこと。** isic-6910 は
   `io.github.com-junkawasaki/langchain-clj` が `../../kotoba-lang/langchain` を指す ——
   改名前の座標が残っており座標と実 repo が一致しない。**パスの方が正しい。**"
  [dir rel]
  (let [abs (.resolve path dir rel)
        parent (.dirname path abs)
        nm (.basename path abs)
        org* (.basename path parent)]
    (when (and (not (str/blank? nm)) (not (str/blank? org*))
               (not= "/" parent) (not= "." nm))
      {:abs abs :org org* :name nm})))

;; ---------------------------------------------------------------------------
;; 生成物の drift

(def skip-dirs #{".git" "node_modules" ".cpcache" ".clj-kondo" ".lsp" "target" ".gitlibs" ".m2"})

(defn- snapshot [dir]
  (let [out (atom {})]
    (letfn [(walk [abs rel depth]
              (when (< depth 12)
                (doseq [e (try (.readdirSync fs abs #js {:withFileTypes true})
                               (catch :default _ #js []))]
                  (let [nm (.-name e)
                        a (.join path abs nm)
                        r (if (str/blank? rel) nm (str rel "/" nm))]
                    (cond
                      (.isDirectory e) (when-not (contains? skip-dirs nm) (walk a r (inc depth)))
                      (.isFile e) (let [h (.createHash crypto "sha256")]
                                    (.update h (.readFileSync fs a))
                                    (swap! out assoc r (.digest h "hex"))))))))]
      (walk dir "" 0))
    @out))

;; ---------------------------------------------------------------------------

;; ノードの非対話 ssh には JDK が見えない。**明示的に環境を敷く。**
;;
;; ⚠ 実測 2026-08-10、実ノード asher: `clojure` は PATH に在る（/opt/homebrew/bin）が
;; `JAVA_HOME` は未設定で、`/usr/bin/java` は JDK 未導入時の stub なので
;; `clojure -M:...` が **The operation couldn't be completed. Unable to locate a
;; Java Runtime.** で即死する。手元の macOS では JDK が既定で引けるので**この差は
;; ローカル検証では出ない** —— gate をローカルで 25/25 通してから実ノードで落ちた。
;; `gates/github_workflow_run.cljs` は同じ理由で最初からこれを export していた。
(def env-prelude
  (str "export PATH=/opt/homebrew/bin:/usr/local/bin:$PATH\n"
       "export JAVA_HOME=\"${JAVA_HOME:-/opt/homebrew/opt/openjdk}\"\n"
       "export PATH=\"$JAVA_HOME/bin:$PATH\"\n"))

(defn- sh [cmd cwd]
  (try {:ok true :out (str (.execFileSync cp "bash" #js ["-c" (str env-prelude cmd)]
                                          #js {:encoding "utf8" :maxBuffer 67108864
                                               :stdio "pipe" :cwd cwd}))}
       (catch :default e
         {:ok false :out (str (some-> (.-stdout e) str) (some-> (.-stderr e) str))})))

(defn -main []
  ;; **最初に見る。** `regen-cmd` は argv の flag なので、tree を読む前から
  ;; 分かっている。後ろに置くと、答えられないと分かっているノードで workspace を
  ;; 作り、依存を 2 本 clone してから棄権することになる。
  (refuse-if-toolchain-absent!)
  (when (str/blank? org)
    (die! 90 "--org is required (the GitHub org of the repo under test);"
          "sibling deps written as ../<name> cannot be resolved without it"))
  (let [deps-text (slurp* (.join path root "deps.edn"))]
    (when-not deps-text
      (die! 90 "no deps.edn in the shipped tree — extraction or :include-ext is wrong,"
            "refusing to report a pass"))

    ;; ws/orgs/<org>/<repo> —— :local/root が 2 段上まで遡るので深さ 2 が要る。
    (let [ws (.mkdtempSync fs (.join path (.tmpdir os) "itonami-regen-"))
          repo-name (.basename path root)
          repo-dir (.join path ws "orgs" org repo-name)]
      (.mkdirSync fs (.dirname path repo-dir) #js {:recursive true})
      (.execFileSync cp "cp" #js ["-a" (str root "/.") repo-dir] #js {:stdio "ignore"})
      (println (str "workspace: " ws " (tree at orgs/" org "/" repo-name ")"))

      ;; 依存を推移的に解決する。**workflow の checkout 一覧は見ない。**
      (loop [queue [[repo-dir deps-text]] seen #{} cloned 0]
        (if (empty? queue)
          (println (str "resolved " cloned " local dep(s) from deps.edn"))
          (let [[dir text] (first queue)
                rels (local-roots text)
                nexts (atom [])
                n (atom cloned)]
            (doseq [rel rels]
              (if-let [{:keys [abs org name]} (resolve-dep dir rel)]
                (when-not (contains? seen abs)
                  (if (.existsSync fs abs)
                    (swap! nexts conj [abs (slurp* (.join path abs "deps.edn"))])
                    (let [url (str "https://github.com/" org "/" name ".git")
                          r (sh (str "git clone --quiet --depth 1 " url " " abs) ws)]
                      (if-not (:ok r)
                        (die! 1 (str "could not clone local dep " org "/" name
                                     " (declared in deps.edn as " rel "): " (str/trim (:out r))))
                        (do (println (str "  dep " org "/" name " -> " rel))
                            (swap! n inc)
                            (swap! nexts conj [abs (slurp* (.join path abs "deps.edn"))]))))))
                (die! 90 (str "unrecognised :local/root shape " (pr-str rel)
                              " — this gate resolves ../<name> and ../../<org>/<name> only;"
                              " refusing to guess where it goes"))))
            (recur (into (vec (rest queue)) (remove #(nil? (second %)) @nexts))
                   (into seen (map first @nexts))
                   @n))))

      (let [before (snapshot repo-dir)
            _ (println (str "regenerating: " regen-cmd))
            r (sh regen-cmd repo-dir)]
        (println (:out r))
        (when-not (:ok r)
          (println (str "workspace kept for inspection: " ws))
          (die! 1 "the regeneration command failed — the actor cannot rebuild its own demo"))
        (let [after (snapshot repo-dir)
              changed (vec (sort (for [[p h] before :let [h2 (get after p)]
                                       :when (and h2 (not= h h2))] p)))
              removed (vec (sort (for [[p _] before :when (nil? (get after p))] p)))
              drift (into changed removed)]
          (if (seq drift)
            (do (println (str "FLEET-CI: regeneration changed " (count drift)
                              " committed file(s) — the published demo is stale"))
                (doseq [p (take 40 drift)] (println (str "  drift " p)))
                (when (> (count drift) 40)
                  (println (str "  … and " (- (count drift) 40) " more")))
                (println (str "workspace kept for inspection: " ws))
                (js/process.exit 1))
            (do (println (str "FLEET-CI: no drift — " (count before)
                              " committed file(s) match a fresh regeneration"))
                (.rmSync fs ws #js {:recursive true :force true}))))))))

(-main)
