#!/usr/bin/env nbb
;; ~/.itonami/ のスケジュール実行スクリプトが、この repo の accepted な規約に
;; 違反していないかを報告する（読み取り専用。修正はしない）。
;;
;; なぜ必要か — ADR-2608019100
;;
;;   これらのスクリプトは repo の外にあり、workspace の repo を書き換える。
;;   hook も lint も grep も repo の外は見ないので、規約は物理的に届いていない。
;;   実測: `~/.itonami/` で git を fetch するスクリプトは2件あり、**2件とも**
;;   ADR-2607211600（shallow 禁止）に違反していた。母数2で違反2 である。
;;   片方は superproject を6時間ごとに shallow へ戻し、もう片方は west 管理下の
;;   子リポを到達可能 commit 1 件まで潰していた。どちらも例外を出さず、
;;   `unrelated histories` や pin 退行という別の顔をして現れた。
;;
;; 安全のための制限（意図的で、緩めない）
;;
;;   1. 読むのは `.cljs` と `.sh` だけ。`~/.itonami/` には `.pem`（秘密鍵）や
;;      `.json` / `.edn` の資格情報が同居しているので、拡張子の allowlist で
;;      弾く。「全部読んで grep する」を絶対にしない。
;;   2. **一致した行の中身を出力しない。** 報告するのは path・行番号・規則 id
;;      だけ。スニペットを出すと、秘密が1行に同居していた場合にそれを
;;      ログへ書き出すことになる。
;;   3. 修正は一切しない。何を直すかは人間が決める。
;;
;; 使い方:
;;   nbb scripts/audit-gftd-scripts.cljs
;;   → 違反があれば exit 1（CI/hook に繋ぐ余地を残すが、本 script 自身は繋がない）

(ns audit-gftd-scripts
  (:require ["fs" :as fs]
            ["os" :as os]
            ["path" :as path]
            [clojure.string :as str]))

(def gftd-dir (path/join (os/homedir) ".itonami"))
(def launch-agents (path/join (os/homedir) "Library" "LaunchAgents"))

(def readable-extensions
  "拡張子の allowlist。ここに無いものは開かない —— `.pem` / `.json` / `.edn` /
   `.log` が同じディレクトリに同居しているため、denylist ではなく allowlist に
   する（新しい秘密の拡張子が増えても既定で読まれない）。"
  #{".cljs" ".sh"})

(def rules
  "各規則は accepted な ADR を1つ指す。`:match?` は1行を受け取り真偽を返す純関数。"
  [{:id :shallow-fetch
    :adr "ADR-2607211600 / ADR-2608019100"
    :why "shallow は fetch のたびに graft を積み、ancestry を壊す。`unrelated histories` や pin 退行として現れる"
    ;; git と同じ行にあることを要求する。`innen record tick --depth N` のような
    ;; 非 git の --depth を拾わないため（実際に ~/.itonami/ に存在する）。
    :match? (fn [line]
              (and (str/includes? line "git")
                   (or (str/includes? line "--depth")
                       (str/includes? line "--shallow"))))}

   {:id :bb-script-host
    :adr "ADR-2607173000"
    :why "bb は script host として退役済み。運用スクリプトは nbb で書く"
    :match? (fn [line]
              (or (str/includes? line "#!/usr/bin/env bb")
                  (re-find #"(?:^|[\s\"(])bb\s+[\w./-]" line)))}])

(defn- sh-file-finding
  "`.sh` の存在を **inventory として** 報告する。行単位ではないので別扱い。

   **これは自動的な「違反」ではない。** CLAUDE.md が禁じているのは shell script を
   **新しく作る**ことであって、既存のものが全て移行対象という意味ではない。当初
   この規則は『既存も nbb へ移す対象』と書いていたが、それは実物を読む前の
   決めつけだった —— 実際に読んだところ `run-itonami-5820-serve.sh` は kagi から
   トークンを読んで環境へ渡し `exec` で置き換わる live サービス起動器で、nbb 化は
   **資格情報の受け渡し経路を増やし、launchd のプロセス監視が依存する `exec` 意味論を
   崩す**。得られるのは形式的な適合だけである。移行しない、が正しい判断だった。

   **過剰に強い報告は、読み手に報告全体を無視させる。** ここは事実（`.sh` が在る）
   だけを述べ、移行するかは1件ずつ人が判断する。判断済みのものは
   `90-docs/adr/2608019200`（セッション記録）に理由付きで残っている。"
  [file]
  (when (str/ends-with? file ".sh")
    {:file file :line nil :rule :shell-script
     :adr "CLAUDE.md（新規 .sh は作らない。既存は個別判断）"
     :why "inventory: .sh が在る。新規作成なら違反。既存なら移行するかを1件ずつ判断する"}))

(defn comment-line?
  "コメント行か。**規則は「使用」を検出するものなので、コメントは除外する。**

   実測 2026-08-01: これを入れる前、`run-innen-tick.cljs:20` の
   `;; ... which found bb but` が bb-script-host に誤検出された —— そのコメントは
   まさに『launchd の PATH が bb を拾ってしまった』経緯を説明している行であり、
   規約違反の記述ではなく規約遵守の記録だった。同種の誤検出は shallow 規則でも
   起きうる（禁止事項を説明するコメントは、その禁止語を必ず含む）。

   shebang（`#!`）はコメントではなく実行指定なので除外しない。"
  [line]
  (let [t (str/trim line)]
    (or (str/starts-with? t ";;")
        (and (str/starts-with? t "#") (not (str/starts-with? t "#!"))))))

(defn findings-in-lines
  "行の並び -> findings。純関数（自己テスト用に file I/O から分離してある）。"
  [file lines]
  (->> lines
       (map-indexed
        (fn [i line]
          (when-not (comment-line? line)
            (keep (fn [{:keys [id match? adr why]}]
                    (when (match? line)
                      {:file file :line (inc i) :rule id :adr adr :why why}))
                  rules))))
       (apply concat)
       (remove nil?)
       vec))

(defn- scan-file [file]
  (findings-in-lines file (str/split-lines (fs/readFileSync file "utf8"))))

(def self-tests
  "**規則が実際に捕まえることを、実際に踏んだ行で確かめる。**
   ADR-2608019000 の規律: 検査を landing する前に、それが本物の違反で発火し
   本物の非違反で発火しないことを確認する。全て実在した行から取っている。"
  [{:label "superproject を6時間ごとに潰していた行（ADR-2608019100 instance 1）"
    :line "      (sh \"git fetch --depth 1 origin main\" {:cwd root})"
    :expect #{:shallow-fetch}}
   {:label "west 子リポを commit 1件まで潰していた行（同 instance 2）"
    :line "      (sh \"git fetch origin --depth 1\" {:cwd sip-checkout})"
    :expect #{:shallow-fetch}}
   {:label "修正後の行は発火しない"
    :line "      (sh \"git fetch origin main\" {:cwd root})"
    :expect #{}}
   {:label "git でない --depth は拾わない（innen CLI の実在する引数）"
    :line "                             \"--depth\" depth \"--as-of\" as-of]"
    :expect #{}}
   {:label "bb を説明するコメントは違反ではない（実際に誤検出した行）"
    :line ";; that form produced PATH=\"/opt/homebrew/bin:\" under launchd, which found bb but"
    :expect #{}}
   {:label "bb の実起動は違反"
    :line "  (sh \"bb scripts/emit-kernel-wasm.bb\" {:cwd root})"
    :expect #{:bb-script-host}}
   {:label "bb の shebang は違反"
    :line "#!/usr/bin/env bb"
    :expect #{:bb-script-host}}])

(defn- run-self-tests!
  "失敗したら監査を実行せずに落とす —— 壊れた検査が『違反なし』と報告するのが
   最悪の結果だから。"
  []
  (let [failures (keep (fn [{:keys [label line expect]}]
                         (let [got (set (map :rule (findings-in-lines "<self-test>" [line])))]
                           (when-not (= expect got)
                             (str "  期待 " (pr-str expect) " / 実際 " (pr-str got) " — " label))))
                       self-tests)]
    (when (seq failures)
      (println "自己テスト失敗。検査自体が壊れているので監査を中止する:")
      (doseq [f failures] (println f))
      (js/process.exit 2))))

(defn- scheduled-by
  "この script を参照している launchd plist の basename、無ければ nil。
   plist の中身は出力しない —— 参照の有無だけを見る。"
  [file plists]
  (let [base (path/basename file)]
    (some (fn [p]
            (when (str/includes? (fs/readFileSync p "utf8") base)
              (path/basename p)))
          plists)))

(def sensitive-extensions
  "**開いたら事故になる拡張子**（`~/.itonami/` に実在する）。allowlist に入っていない
   ことを起動時に毎回確かめるためだけに存在する —— 検査の allowlist が将来
   広げられたとき、これが無ければ誰も気づかない。"
  #{".pem" ".json" ".edn" ".log" ".tgz" ".plist" ".key" ".p12"})

(defn- assert-allowlist-excludes-secrets!
  "**この script の安全性は allowlist 1つに乗っている。** 実際のディレクトリに
   対して、走査対象に危険な拡張子が1つも入らないことを毎回確かめる。

   定数どうしの比較ではなく実ディレクトリで確かめるのは、`~/.itonami/` に新しい
   種類の秘密が置かれた場合も捕まえるため。"
  [entries scanned]
  (let [bad (filter #(contains? sensitive-extensions (path/extname %)) scanned)]
    (when (seq bad)
      (println "安全確認に失敗: 走査対象に開いてはならない拡張子が含まれている。")
      ;; ファイル名自体を出さない —— 秘密の存在と名前を漏らさないため、件数と
      ;; 拡張子だけ報告する。
      (println (str "  " (count bad) " 件 / 拡張子: "
                    (pr-str (sort (distinct (map path/extname bad))))))
      (js/process.exit 2))
    ;; 逆向きも確かめる: 実際に秘密が「在る」のに除外できていた、という状態を
    ;; 確認しておかないと、単に対象が空でも通ってしまう。
    (let [present (set (filter #(contains? sensitive-extensions (path/extname %)) entries))]
      {:excluded (count present) :scanned (count scanned)})))

(defn -main []
  (run-self-tests!)
  (if-not (fs/existsSync gftd-dir)
    (do (println (str "~/.itonami/ が無い。監査対象なし: " gftd-dir))
        (js/process.exit 0))
    (let [entries (fs/readdirSync gftd-dir)
          files (->> entries
                     (filter #(contains? readable-extensions (path/extname %)))
                     ;; .bak-* 等の退避コピーは現行の運用対象ではないので除く。
                     (remove #(str/includes? % ".bak"))
                     (map #(path/join gftd-dir %))
                     sort)
          plists (if (fs/existsSync launch-agents)
                   (->> (fs/readdirSync launch-agents)
                        (filter #(str/ends-with? % ".plist"))
                        (map #(path/join launch-agents %)))
                   [])
          safety (assert-allowlist-excludes-secrets! entries files)
          findings (->> files
                        (mapcat (fn [f]
                                  (concat (remove nil? [(sh-file-finding f)])
                                          (scan-file f)))))]
      (println (str "監査対象: " (count files) " ファイル（.cljs/.sh のみ）"
                    " / launchd plist " (count plists) " 件"))
      (println (str "安全確認: 秘密になりうる拡張子 " (:excluded safety)
                    " 件を走査対象から除外（開いていない）"))
      (println (apply str (repeat 78 "-")))
      (if (empty? findings)
        (println "違反なし。")
        (doseq [{:keys [file line rule adr why]} findings]
          (println (str (path/basename file)
                        (when line (str ":" line))
                        "  [" (name rule) "]  " adr))
          (println (str "    " why))
          (when-let [job (scheduled-by file plists)]
            (println (str "    ※ launchd から定期実行されている: " job)))))
      (println)
      (println (str "違反 " (count findings) " 件"))
      (when (seq findings)
        (println "※ 一致行の中身は意図的に出力していない（同じ行に秘密が同居しうるため）。")
        (js/process.exit 1)))))

(-main)
