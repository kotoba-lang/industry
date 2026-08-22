#!/usr/bin/env nbb
;; run.cljs — 拒否が今も落ちるかを確かめる local loop。
;;
;; `mutations.edn` の各 mutation を当てて、**赤くなることを確認する**。
;; 赤くならなかったものは「そのテストは、その不変条件を守っていない」という
;; 具体的な TODO として報告する。
;;
;; ## テストではなく、テストのテストである
;;
;; 通常の CI は「テストが緑か」を見る。これは「テストが**赤くなれるか**」を見る。
;; 静かに噛まなくなったテストは緑のままなので、緑を見ているかぎり永久に気づけない
;; ——それが 2026-08-05 に 2 回起きた（`mutations.edn` の冒頭を参照）。
;;
;; ## 共有 checkout を絶対に壊さない
;;
;; ソースを書き換える道具なので、事故ると他人の作業が消える。3 重に守る:
;;
;;   1. **使い捨て worktree で作業する。** 共有 checkout は読まない・書かない。
;;      このマシンは多数の agent セッションが並行しており、共有 tree を触る
;;      定期ジョブは他人の WIP を壊す。
;;   2. **worktree は west の pin から切る。** 「今たまたま checkout されている
;;      もの」ではなく、manifest が指しているものを検査する。
;;   3. mutation の適用と復元は同じ関数の中で完結し、復元は例外でも走る。
;;      worktree ごと捨てるので、復元が失敗しても共有側には何も残らない。
;;
;; 重い build は `scripts/resource-guard.mjs` の build lock を通す
;; （このワークスペースの mandatory な資源制御）。
;;
;; 使い方:
;;   nbb scripts/maturity-loop/run.cljs                 全 suite
;;   nbb scripts/maturity-loop/run.cljs --only inga     repo 名で絞る
;;   nbb scripts/maturity-loop/run.cljs --keep-worktree 失敗時に worktree を残す
(ns maturity-loop.run
  (:require ["node:child_process" :as cp]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            [cljs.reader :as reader]
            [clojure.string :as str]))

(def args (vec *command-line-args*))

(defn- progress!
  "進捗は stderr へ。Node の stdout はパイプ相手だとブロックバッファされるので、
  ファイルに落とすと最後まで 1 行も見えない —— 実測 2026-08-24、67 分回して
  出力ファイルは 0 行だった。stderr は同期に書かれる。"
  [& xs]
  (binding [*print-fn* *print-err-fn*] (println (apply str xs))))

(defn- flag [name default]
  (let [i (.indexOf args name)]
    (if (neg? i) default (nth args (inc i) default))))

(defn- flag? [name] (not (neg? (.indexOf args name))))

(def root
  "superproject のルート。この script の位置から 2 つ上。"
  (path/resolve (path/join (path/dirname (or js/__filename "scripts/maturity-loop/run.cljs"))
                           ".." "..")))

(defn- sh
  "同期実行。`{:out :code}`。stderr は out に混ぜる —— 失敗の理由は大抵そちらに出る。"
  [cmd cwd]
  (let [r (cp/spawnSync (first cmd) (clj->js (rest cmd))
                        #js {:cwd cwd :encoding "utf8" :shell false
                             :maxBuffer (* 64 1024 1024)})]
    {:out (str (.-stdout r) (.-stderr r))
     :code (or (.-status r) 1)}))

(defn- guarded
  "build lock を通して実行する。lock が他セッションに握られていたら待つ ——
  奪わない。奪える仕組みにすると、この loop が他人の build を壊す側になる。"
  [cmd cwd]
  (loop [tries 0]
    (let [r (sh (into ["node" (path/join root "scripts/resource-guard.mjs") "run" "build" "--"] cmd) cwd)]
      (if (and (str/includes? (:out r) "already running") (< tries 60))
        (do (sh ["sleep" "20"] cwd) (recur (inc tries)))
        r))))

;; ── west pin ────────────────────────────────────────────────────────────────

(defn- pinned-sha
  "west.yml が `repo-path` に対して指している revision。**現在の checkout の
  HEAD ではない** —— 検査するのは manifest が指すものである。

  照合は **`path:`** で行う。`- name:` ではない —— west の project 名は path の
  basename と一致するとは限らず、実測 2026-08-19 で **4,216 entry 中 60 件**が
  食い違っている（`cloud-itonami-mangaka-data` が `orgs/cloud-itonami/mangaka-data`、
  `kotoba-lang-bim` が `orgs/kotoba-lang/bim` など）。名前で引いていた前の版は、
  その 60 件に対して nil を返し、呼び出し側は **『west.yml に pin が無い』と
  報告して SKIP** していた —— manifest が壊れているように読めるが、実際には
  引く鍵を間違えていただけである。`mutations.edn` にそれらの repo を足しても、
  suite は走らずに skip されるので、**検査を足したのに何も検査されない**。

  entry の境界は次の `- name:` までとする。`revision:` が `name:` の直後 6 行に
  あるという仮定は置かない —— `userdata:` を持つ entry では実際にもっと下に来る。"
  [repo-path]
  (let [yml (fs/readFileSync (path/join root "manifest/west.yml") "utf8")
        lines (vec (str/split-lines yml))
        name? #(str/starts-with? (str/trim %) "- name: ")
        field (fn [ls k]
                (some #(let [t (str/trim %)]
                         (when (str/starts-with? t (str k ": "))
                           (str/trim (subs t (+ 2 (count k))))))
                      ls))
        starts (keep-indexed (fn [i l] (when (name? l) i)) lines)]
    (some (fn [[a b]]
            (let [block (subvec lines a (or b (count lines)))]
              (when (= (field block "path") repo-path)
                (field block "revision"))))
          (partition 2 1 (concat starts [nil])))))

;; ── mutation ────────────────────────────────────────────────────────────────

(defn- occurrences
  "`needle` が `s` に何回現れるか。正規表現を使わない —— アンカーは Clojure の
  ソース断片で、括弧も `?` も `[` も普通に含む。それを regex に変換する処理は
  それ自体がバグの温床で、実際に最初の版はそこで落ちた。"
  [s needle]
  (loop [from 0, n 0]
    (let [i (str/index-of s needle from)]
      (if i (recur (+ i (count needle)) (inc n)) n))))

(defn- apply-mutation!
  "1 箇所だけ壊す。`:find` が無い／2 箇所以上あるのは **loop 自身のバグ** で、
  そのまま走らせると「壊せていないのに緑」を「噛まなかった」と誤報告する。"
  [dir {:keys [file find replace id]}]
  (let [p (path/join dir file)
        s (fs/readFileSync p "utf8")
        n (occurrences s find)]
    (cond
      (zero? n)
      {:ok? false :reason (str "anchor not found for " id " in " file)}

      (> n 1)
      {:ok? false :reason (str "anchor is not unique for " id " (" n " occurrences)")}

      :else
      (do (fs/writeFileSync p (str/replace-first s find replace))
          {:ok? true :original s :path p}))))

(defn- run-suite [dir {:keys [cmd npm-install]}]
  (when npm-install
    (guarded ["npm" "install" "--silent"] dir))
  (guarded (vec cmd) dir))

(defn- green?*
  "suite が緑か。**終了コードが第一の根拠**で、マーカーはその裏取り。

   マーカーの文字列一致だけで判定すると、**失敗メッセージが README や quickstart を
   丸ごと吐き、その本文が緑マーカーを引用している**場合に、赤い実行を緑と読む。
   実測（2026-08-09、marine-insurance）: 20 mutation のうち 3 つがこれで
   『噛まない』と誤報された —— どれも `(is (str/includes? readme ...))` 形の
   assertion で、README が手順として `... actor: all green` を引用していた。

   **『不変条件を誰も守っていない』という最も行動を促す報告が偽陽性になる**ので、
   ここは出力の見た目ではなくプロセスの終了コードを信じる。"
  [out code green-marker]
  (and (zero? code) (str/includes? out green-marker)))

(defn- bites?
  "mutation が本当に噛んだか。緑でなくなったことに加え、`:must-fail` に
  挙げたテスト名が実際に出力に現れることまで見る —— 別の理由で赤くなったのを
  「噛んだ」と読むのが、この種の道具の一番ありがちな嘘だから。"
  [out code green-marker must-fail]
  (let [green? (green?* out code green-marker)
        named (remove #(str/includes? out %) must-fail)]
    {:bit? (and (not green?) (empty? named))
     :still-green? green?
     :missing-names (vec named)}))

;; ── suite ───────────────────────────────────────────────────────────────────

(defn link-siblings!
  "worktree の deps.edn が `:local/root \"../X\"` で指す兄弟 repo を、sandbox の
  中に symlink で用意する。

  **これが無いと、その repo は base すら緑にならない** —— 落ちているのは実装では
  なく、隣が居ないことである。両者は `clojure -M:test` の出力では見分けが付かない
  ので、**張れなかった名前は必ず報告する**（黙って進むと「変異が咬まなかった」
  ではなく「suite が測れなかった」を、同じ顔で緑にする）。"
  [root dir sandbox]
  (let [f (path/join dir "deps.edn")]
    (when (fs/existsSync f)
      (doseq [m (re-seq #":local/root\s+\"\.\./([^\"]+)\"" (str (fs/readFileSync f "utf8")))]
        (let [name (second m)
              dst (path/join sandbox name)]
          (when-not (fs/existsSync dst)
            (if-let [srcdir (first (for [org (try (vec (fs/readdirSync (path/join root "orgs")))
                                                  (catch :default _ []))
                                         :let [p (path/join root "orgs" org name)]
                                         :when (fs/existsSync p)]
                                     p))]
              (fs/symlinkSync srcdir dst "dir")
              (progress! (str "   ⚠ 兄弟 `../" name "` が orgs/ に見つからない"
                              " —— この suite の base は隣が居ないせいで落ちる")))))))))

(defn- check-suite [{:keys [repo label mutations green-marker] :as suite} keep?]
  (let [sha (pinned-sha repo)
        src (path/join root repo)
        ;; worktree を **sandbox の中**に置く。裸で tmpdir に置くと、deps.edn の
        ;; `:local/root "../yaml"` のような**兄弟参照が解決できない** —— 共有
        ;; checkout では `orgs/kotoba-lang/yaml` が隣に在るが、tmpdir の隣には
        ;; 無いので、その repo は base すら緑にならず suite ごと測れない。
        ;; sandbox を 1 段挟めば、隣に symlink を張るだけで `../` が効く。
        sandbox (path/join (os/tmpdir) (str "maturity-" (path/basename repo) "-"
                                            (subs (or sha "nopin") 0 8)))
        dir (path/join sandbox (path/basename repo))]
    (progress! (str "\n── " label "  [" repo "]"))
    (if-not sha
      (do (progress! (str "   SKIP: west.yml に `path: " repo "` の entry が無い"
                        " —— この repo は west 管理下に無いか、path が変わっている"))
          {:skipped 1})
      (do
        (sh ["rm" "-rf" sandbox] root)
        (fs/mkdirSync sandbox #js {:recursive true})
        ;; pin は manifest のもので、ローカル checkout がそれを持っているとは
        ;; 限らない —— サーバ側マージ（`gh api .../merges`）で main が進んだ直後は
        ;; 特にそうで、実際にこの loop の初回実行がそれで 2 suite 落ちた。
        ;; remote 名は west の慣習で `origin` とは限らないので --all で引く。
        (sh ["git" "fetch" "--all" "--quiet"] src)
        ;; 消えた worktree の登録が残っていると `add` は
        ;; 「missing but already registered」で落ちる。**その状態は普通に起きる**
        ;; —— 前の実行が落ちた、別のセッションが /tmp を掃除した、`--keep-worktree`
        ;; の残骸を手で消した。実測 2026-08-24、180 mutation の通し実行のうち
        ;; 1 suite がこれだけの理由でエラーになった。prune は登録簿から
        ;; **実体の無い entry を消すだけ**で、生きている worktree には触らない。
        (sh ["git" "worktree" "prune"] src)
        (let [wt (sh ["git" "worktree" "add" "--detach" dir sha] src)]
          (if-not (zero? (:code wt))
            (do (progress! (str "   FAIL: worktree を作れない — " (str/trim (:out wt))))
                (progress! "         pin が upstream に無いか、checkout が壊れている")
                {:errors 1})
            (try
              (link-siblings! root dir sandbox)
              (let [base (run-suite dir suite)]
                (if-not (green?* (:out base) (:code base) green-marker)
                  (do (progress! (str "   FAIL: pin " (subs sha 0 8) " で suite が緑にならない"))
                      (progress! (str "         " (last (remove str/blank? (str/split-lines (:out base))))))
                      {:errors 1})
                  (do
                    (progress! (str "   base " (subs sha 0 8) ": 緑"))
                    (reduce
                     (fn [acc {:keys [id must-fail why] :as m}]
                       (let [applied (apply-mutation! dir m)]
                         (if-not (:ok? applied)
                           (do (progress! (str "   BUG  " id " — " (:reason applied)))
                               (update acc :errors inc))
                           (let [r (run-suite dir suite)
                                 v (bites? (:out r) (:code r) green-marker must-fail)]
                             (fs/writeFileSync (:path applied) (:original applied))
                             (if (:bit? v)
                               (do (progress! (str "   噛む " id))
                                   (update acc :bit inc))
                               (do (progress! (str "   噛まない " id " — " why))
                                   (when (:still-green? v)
                                     (progress! "         suite は緑のまま（この不変条件は誰も守っていない）"))
                                   (when (seq (:missing-names v))
                                     (progress! (str "         赤くなるはずのテストが出ていない: "
                                                   (str/join ", " (:missing-names v)))))
                                   (update acc :blind inc)))))))
                     {:bit 0 :blind 0 :errors 0}
                     mutations))))
              (finally
                ;; worktree ごと捨てる。mutation の復元が失敗していても、共有
                ;; checkout には何も残らない —— それがこの隔離の意味。
                (if keep?
                  (println (str "   (worktree を残した: " dir ")"))
                  (do (sh ["git" "worktree" "remove" "--force" dir] src)
                      (sh ["rm" "-rf" sandbox] root)))))))))))

;; ── main ────────────────────────────────────────────────────────────────────

(let [spec (reader/read-string (fs/readFileSync (path/join root "scripts/maturity-loop/mutations.edn") "utf8"))
      only (flag "--only" nil)
      keep? (flag? "--keep-worktree")
      suites (cond->> (:suites spec)
               only (filter #(str/includes? (:repo %) only)))
      _ (println (str "maturity-loop: " (count suites) " suite / policy " (:policy spec)))
      totals (reduce (fn [acc s]
                       (merge-with + acc (merge {:bit 0 :blind 0 :errors 0 :skipped 0}
                                                (check-suite s keep?))))
                     {:bit 0 :blind 0 :errors 0 :skipped 0}
                     suites)]
  (println (str "\nmaturity-loop: 噛む=" (:bit totals)
                " 噛まない=" (:blind totals)
                " エラー=" (:errors totals)
                " skip=" (:skipped totals)))
  (when (pos? (:blind totals))
    (println "噛まない mutation は「その不変条件を守っているテストが無い」という意味。テストを足すか、mutation が古いなら mutations.edn から外す。"))
  (js/process.exit (if (zero? (+ (:blind totals) (:errors totals))) 0 1)))
