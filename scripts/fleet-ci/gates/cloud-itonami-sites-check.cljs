#!/usr/bin/env nbb
;; cloud-itonami-sites-check.cljs — network-awai/cloud-itonami の公開サイト面 gate。
;;
;; **なぜ fleet に置くか。** ADR-2607301300 が入れた STRICT job `sites` は
;; `.github/workflows/ci.yml` にあるが、**network-awai org の Actions は
;; 2026-08-03T01:10Z 以降 1 本も job を起動していない**（課金停止。net-kotobase の
;; gate コメントに実測が残っている）。落ちるのではなく走らないので、**repo は
;; green に見えたまま何も検査されていなかった。** ADR-2607300900 の決定
;; （CI は Actions ではなく fleet）をこの surface にも適用する。
;;
;; **何を検査するか。** repo 自身の `test-sites`（= `sites-registry-check` +
;; `sites-bundle-verify`）をそのまま呼ぶ。検査ロジックを gate 側に複製しない ——
;; 複製すると fleet 側と repo 側で別実装になり、片方だけ通る状態が黙って生まれる。
;;
;;   A. generate-sites-registry.cljs --check
;;      レジストリが `sites.edn` + 実ファイルの canonical な projection であること。
;;      共通クロームの有無・org 面の存在・org 宣言の網羅もここで強制される
;;      （宣言だけして実体が無い / クローム無しのページは登録できない）。
;;
;;   B. verify-sites-bundle.cljs
;;      **ビルド済みの `functions/edge/sites-core.js` を実際に import して呼ぶ。**
;;      exit code や `node --check` では artifact の健全性は分からない。
;;      path モードと host モードの両方（未登録 404 / 宣言外ファイル 404 /
;;      パストラバーサルがサイト外に出ない / org ホストが cockpit を映さない /
;;      `.well-known` は通す / apex の 301）。
;;
;; **どちらも standalone で動く**（west sibling も source repo checkout も要らない）。
;; 逆に `score-sites` と `build-sites --check` は west レイアウトを要求するので
;; **ここには入れない** —— continue-on-error の飾りにしない、という ADR の方針を継ぐ。
;;
;; **数を見る。** nbb の runner は失敗しても exit code を立てないことがあるので、
;; rc だけでは緑になりうる。出力から件数を読み、床を下回ったら落とす ——
;; 「検査が 0 件走って合格」を防ぐ床であって、上限ではない。
;;
;; ノード側で `npx nbb cloud-itonami-sites-check.cljs <dir> [--min-checks N]
;; [--min-reserved N]` として実行。
(ns fleet-ci.gates.cloud-itonami-sites-check
  (:require ["node:child_process" :as cp]
            [clojure.string :as str]))

;; **`process.argv` を直に読まない。** nbb では argv[2] がスクリプト自身のパスに
;; なるので `(drop 2 …)` だと root がスクリプトの `.cljs` になり、spawnSync の cwd
;; がそこを指して **status=nil（プロセスが起動しない）** になる。実測 2026-08-05:
;; それで gate が「2/2 失敗」と報告したが、原因は検査対象ではなく引数解析だった。
;; `*command-line-args*` はユーザー引数だけを持つ（net-kotobase gate と同じ）。
(def args (vec *command-line-args*))

(def ^:private value-flags #{"--min-checks" "--min-reserved"})

(def root
  (loop [[a & more] args prev nil]
    (cond (nil? a) "."
          (str/starts-with? a "--") (recur more a)
          (contains? value-flags prev) (recur more nil)
          :else a)))

(defn- opt [flag default]
  (let [i (.indexOf args flag)]
    (if (neg? i) default (js/parseInt (get args (inc i))))))

(def min-checks (opt "--min-checks" 20))
(def min-reserved (opt "--min-reserved" 20))

(def failures (atom []))

(defn- fail! [& parts]
  (let [m (str/join " " (map str parts))]
    (swap! failures conj m)
    (println "FAIL" m)))

(defn- die! [code & parts]
  (println (str/join " " (map str parts)))
  (set! (.-exitCode js/process) code))

(defn- tail [s n]
  (str/join "\n" (take-last n (str/split-lines (str s)))))

(defn- run [cmd args]
  (let [r (cp/spawnSync cmd (clj->js args)
                        #js {:cwd root :encoding "utf8" :maxBuffer (* 32 1024 1024)})]
    {:rc (aget r "status")
     :out (str (aget r "stdout") (aget r "stderr"))}))

;; ---------------------------------------------------------------------------
;; A. レジストリが canonical か
;;
;; `--check` は「どこで生成しても同じ内容になる」ことが成立条件（環境依存の値を
;; 混ぜて gate を永久に赤くした実測 2026-07-31 の教訓）。ノードで走らせること
;; 自体が、その不変条件の検査になっている。

(let [{:keys [rc out]} (run "npx" ["nbb" "scripts/generate-sites-registry.cljs" "--check"])
      m (re-find #"OK: sites registry is canonical \((\d+) site\(s\), (\d+) reserved" (str out))]
  (cond
    (str/includes? (str out) "STALE")
    (do (println (tail out 5))
        (fail! "registry" "STALE — sites.edn / 実ファイルと生成物がずれている"))

    (nil? m)
    (do (println (tail out 15))
        (fail! "registry" "canonical サマリを読めない — 合格と報告しない"))

    (not (zero? rc))
    (do (println (tail out 10)) (fail! "registry" "nbb exited" rc))

    :else
    (let [sites (js/parseInt (nth m 1))
          reserved (js/parseInt (nth m 2))]
      (println "A registry:" sites "site(s)," reserved "reserved prefixes")
      (cond
        (zero? sites)
        (fail! "registry" "0 sites — 公開面が空。宣言が消えていないか")
        ;; 予約語は `public/` と `functions/` の実在エントリから生成される。
        ;; 極端に少ない = ship された tree が痩せていて、検査が実体を見ていない。
        (< reserved min-reserved)
        (fail! "registry" reserved "reserved prefixes <" min-reserved
               "— ship された tree が痩せている(検査が実体を見ていない)")))))

;; ---------------------------------------------------------------------------
;; B. ビルド済みバンドルを実際に import して呼ぶ

(let [{:keys [rc out]} (run "npx" ["nbb" "scripts/verify-sites-bundle.cljs"])
      ok-count (count (re-seq #"(?m)^\s+ok\s" (str out)))
      failed (re-find #"FAILED \((\d+)\)" (str out))]
  (println "B bundle:" ok-count "checks ok, rc=" rc)
  (cond
    (str/includes? (str out) "FAILED to import the bundle")
    (do (println (tail out 10))
        (fail! "bundle" "import できない — artifact が壊れている"))

    failed
    (do (println (tail out 20)) (fail! "bundle" (nth failed 1) "checks failed"))

    (not (str/includes? (str out) "all sites-core checks passed"))
    (do (println (tail out 20))
        (fail! "bundle" "完了行が無い — 途中で死んでいる可能性"))

    (< ok-count min-checks)
    (do (println (tail out 10))
        (fail! "bundle" ok-count "checks <" min-checks
               "— 検査が減っている(host モードが実行されていない?)"))

    (not (zero? rc))
    (do (println (tail out 10)) (fail! "bundle" "nbb exited" rc))))

;; ---------------------------------------------------------------------------
;; C. `Domain=` 付き cookie を入れさせない
;;
;; **サブドメインは origin を分けるが cookie jar は分けない。** `itonami.cloud` は
;; Public Suffix List に無いので、`Domain=itonami.cloud` の cookie は apex と
;; 全 org ホストで共有される —— 公開サイトが cockpit のセッションを読めることに
;; なる（ADR-2608057000 決定 6、KRP §4.1 rule 5）。
;;
;; **PSL に itonami.cloud を載せるのは解決策ではない。** それをすると apex 自身も
;; cookie を置けなくなる。GitHub が github.com を PSL に載せず Pages を別ドメインへ
;; 移したのはこのため。今日の正しい対策は「`Domain=` を付けない」の一点。
;;
;; 実測 2026-08-05 時点で cookie を置くコードは 1 行も無く、live 応答にも
;; `Set-Cookie` は無い。**つまりこれは「今は無い」を守るための回帰ガード**であって、
;; 既存の違反を数えるものではない。入り込んだ瞬間にここで落ちる。

(let [{:keys [rc out]} (run "grep" ["-rniE" "set-?cookie" "--include=*.cljc" "--include=*.cljs"
                                    "--include=*.js" "src" "functions"])
      ;; grep は不一致で rc=1。それは「違反ゼロ」であって失敗ではない。
      lines (if (= 1 rc) [] (remove str/blank? (str/split-lines (str out))))
      ;; ビルド成果物（`functions/edge/*-core.js`）は生成物なので source として数えない。
      src-lines (remove #(re-find #"functions/edge/[^:]*-core\.js" %) lines)
      domain-scoped (filter #(re-find #"(?i)domain\s*=" %) src-lines)]
  (println "C cookies:" (count src-lines) "cookie site(s) in source,"
           (count domain-scoped) "domain-scoped")
  (when (seq domain-scoped)
    (doseq [l (take 5 domain-scoped)] (println "   " l))
    (fail! "cookies" (count domain-scoped)
           "`Domain=` 付き cookie —— 公開サイトと cockpit で jar を共有してしまう"
           "(host-only にするか、別ドメインへ移す)")))

;; ---------------------------------------------------------------------------
;; D. `.well-known` が実体なしで 200 HTML を返さない（ADR-0044 / ADR-2608111721）
;;
;; **同じ surface の同じ規則の、別の入口。** B が見ているのは `/{org}/{repo}` の
;; 未知 404 で、こちらは `/.well-known/*` の未知 404。実測 2026-08-11、B が緑の
;; まま `/.well-known/did.json` と `/.well-known/zzz-nope.json` が **200 text/html**
;; （`_redirects` の `/* /index.html 200` が拾った SPA）を返していた —— KRP §4 rule 8
;; 違反が、B の管轄の隣に残っていた。
;;
;; **repo 側の検査をそのまま呼ぶ**（B と同じ理由。検査ロジックを gate に複製すると
;; 片方だけ通る状態が黙って生まれる）。repo 側は `functions/_middleware.js` を実際に
;; import し、`context.next()` を差し替えて上流の content-type を変える。
;;
;; 床が 8 なのは、この検査が守っているものが 8 分岐あるから —— **うち 3 つは回帰**
;; （apex は HTML のまま / `/api/*` の JSON は無傷 / `/api/*` の html-leak は 502 のまま）
;; で、guard を足したことで別の surface を壊していないことを見ている。減ったら落とす。

(let [{:keys [rc out]} (run "npx" ["nbb" "test/well_known_guard_test.cljs"])
      ok-count (count (re-seq #"(?m)^ok\s" (str out)))
      failed (re-find #"(\d+) FAILED" (str out))]
  (println "D well-known:" ok-count "checks ok, rc=" rc)
  (cond
    (str/includes? (str out) "harness error")
    (do (println (tail out 10))
        (fail! "well-known" "ハーネスが _middleware.js を import できない"))

    failed
    (do (println (tail out 20)) (fail! "well-known" (nth failed 1) "checks failed"))

    (not (str/includes? (str out) "all pass"))
    (do (println (tail out 20))
        (fail! "well-known" "完了行が無い — 途中で死んでいる可能性"))

    (< ok-count 8)
    (do (println (tail out 12))
        (fail! "well-known" ok-count "checks < 8 — 分岐が減っている"))

    (not (zero? rc))
    (do (println (tail out 10)) (fail! "well-known" "nbb exited" rc))))

;; ---------------------------------------------------------------------------

(if (seq @failures)
  (die! 1 (count @failures) "of 4 sites checks failed:" (str/join ", " @failures))
  (println "OK — sites registry is canonical, the built bundle enforces both the"
           "path-mode and org-host rules, .well-known does not fall through to the"
           "SPA, and no Domain-scoped cookie exists"))
