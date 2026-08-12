#!/usr/bin/env nbb
;; rename-residue-check.cljs — cleanup-land の「改名残骸 gate」が生きているかを tip ごとに測る。
;;
;; **検証ロジックはここに複製しない。** 正本は repo 内の
;; `scripts/rename-residue-test.cljs` で、この gate は展開済み tree に対してそれを
;; 呼ぶだけ（itonami-maturity-freshness-check.cljs と同じ形）。fleet 側と手回し側で
;; 別実装を持つと、片方だけ通る状態が黙って生まれる。
;;
;; ## なぜこれが要るか
;;
;; これは **壊れても誰も気づかない**種類の検査である。分類器が沈黙して全部を
;; :wip に倒しても、cleanup-land は今までどおり exit 0 で `plan :additive N files`
;; と印字し、PR は開き、merge も通る。壊れていることが観測できるのは、
;; **次にディレクトリ改名が起きた後**に main へ古いファイルが戻ってきた時だけで、
;; 実測ではそれに 6 日かかり（改名 08-03/08-04 → 発覚 08-12）、その間に
;; 2 回の cleanup が 17 ファイルを戻した（net-kotobase/control-plane、
;; manifest/cleanup-workflow.edn の :residue-gate）。
;;
;; つまり fail は静かで、遅れて、別の症状として現れる。tip ごとに回す価値がある。
;;
;; ## exit 0 を信用しない
;;
;; classpath 破壊等で「何も検証していないのに exit 0」になる経路があるので、
;; summary 行（`N cases OK`）の存在と N が期待本数以上あることを assert する。
;; 単に N>0 にすると、fixture repo を建てられない環境（git 不在）で pure case
;; だけが走り、実 git に対する判定が 1 本も動いていないのに緑になる。
;;
;; ノード側で `npx nbb rename-residue-check.cljs <dir>` として実行。
;; ⚠ `<dir>` は引数の**先頭**に置く（CLAUDE.md「赤い gate を直す前に 3 つ確かめる」2）。
;;
;; ## 赤くなることの実測（2026-08-12、ADR-2608124800 項目 7）
;;
;; 「落ちない gate は劇場」（CLAUDE.md）。fixture 21 本に negative が含まれることは
;; **分類器**が判別する証拠であって、**gate が赤くなる**証拠ではない。別の主張なので
;; 別に測った。以下は全部、実際に壊して実行した結果である（再現手順つき）。
;;
;; 実行形は fleet と同じ `<dir>` 先頭:
;;   npx --yes nbb scripts/fleet-ci/gates/rename-residue-check.cljs <tree>
;;
;; | 壊し方 | exit | 出力の最終行 |
;; |---|---|---|
;; | 無改変（`:include-ext [".cljs"]` で絞った 355 ファイルの tree） | **0** | `FLEET-CI: rename-residue gate OK (21 cases)` |
;; | `classify` の cond 冒頭に `true (out :wip :new-path {})` | **1** | `FLEET-CI: rename-residue-test failed`（`FAIL — 6 cases OK, 15 failures`） |
;; | 同上を `:residue` 側に倒す | **1** | 同上（`FAIL — 7 cases OK, 14 failures`） |
;; | test の git fixture ブロック（165–184 行）を削除 | **94** | `FLEET-CI: only 13 cases ran, expected at least 21 — the git fixture half probably did not run` |
;; | test 末尾の summary println を削除（exit は残す） | **93** | `FLEET-CI: no 'N cases OK' summary in output — refusing to report a pass` |
;; | `scripts/rename-residue-test.cljs` を除く | **90** | `… rename-residue-test.cljs missing after extract` |
;; | `scripts/rename_residue.cljs` を除く | **90** | `… rename_residue.cljs missing after extract` |
;; | `scripts/nbb_compat/` を除く | **90** | `… nbb_compat missing after extract — classpath would be broken` |
;; | `<dir>` を先頭に置かず `--min 10 .` と渡す | **90** | `… rename-residue-test.cljs missing after extract` |
;;
;; 読み取れること:
;;
;; - **床は互いに区別できる。** 90 / 1 / 93 / 94 は別の失敗を別の文言で言う。90 だけは
;;   3 つの入口で共有だが、文言がどのファイルかを名指すので現場で迷わない。
;; - **各床が別の空振り経路を閉じている。** 特に 94 の実測では、下の test 自身は
;;   **exit 0 で `PASS — 13 cases OK, 0 failures` を印字していた** —— 床が無ければ
;;   これがそのまま緑になる。床は理屈ではなく実際にそこで止めた。
;; - **argv の順序ミスは fail-closed。** flag を先に渡すと `"10"` が tree のパスになるが、
;;   結果は偽陽性の緑ではなく 90 の赤である。
;; - **fleet が配る tree で緑になる。** `git archive` で `.cljs` だけ 355 ファイル
;;   （`:min-files 300` の床すれすれ）に絞った tree でも 21 cases 通る。つまりこの gate は
;;   `root-permit-index` のような「入力が tree に無いので構造的に緑にならない」型ではない。
;;   ただし床までの余裕は 55 ファイルしかない。
;;
;; ## 閉じていない穴（正直に）
;;
;; **summary 行を偽れば緑になる。** 実測: `rename-residue-test.cljs` の中身を
;; `(println "PASS — 21 cases OK, 0 failures")` の 1 行だけに置き換えると、この gate は
;; **exit 0 で `rename-residue gate OK (21 cases)` と報告する。** N は下限であって
;; 実測値との突き合わせではないので、`999 cases OK` でも同じく通る。
;;
;; これは設計上の欠陥ではなく**床の担当範囲の外**である。ここの床が閉じているのは
;; *環境*由来の空振り（tree 欠損・classpath 破壊・fixture 半分の不実行・summary 消失）で、
;; **test 本体の書き換え**は閉じていない。それに対する防御は gate ではなく diff の
;; レビューである。床を足して塞ごうとすると（例: `fixture at <path>` 行も要求する）
;; 偽装側も 1 行増やすだけなので、防御力は増えず床だけ増える。
;;
;; ## この記録をここに置いた理由
;;
;; 床の**隣**に置くため。`min-cases` を下げる・床を 1 つ消す変更をした人の diff に、
;; その床の exit code を名指した行が必ず一緒に現れる。ADR は日付つきの棚卸しなので
;; 「いつ測ったか」は残せるが、床を触る人の目には入らない。両方に書いてある
;; （ADR-2608124800 は項目 7 を outstanding から done へ書き換え済み）。
;;
;; fixture として符号化しなかったのは、fixture が測るのは**分類器**であって、
;; まさにこの区別（分類器の証拠 ≠ gate の証拠）が本件の主題だからである。gate 自身を
;; 自分の中から spawn して測ることもできるが、その自己 spawn が壊れた時の失敗は
;; また新しい静かな経路になる。
;;
;; ## この実測が fleet について言えないこと
;;
;; 上は**この laptop**（npm 11.12.1）での実測である。CLAUDE.md が記録しているとおり
;; ここでは `npx --yes nbb --classpath X file.cljs` が壊れており（実測: 引数が
;; `github:scripts/…` として package 解決され `npm ERR! code 128`）、上の実行は
;; `--yes` を落として `nbb` を直接呼ぶ PATH shim を噛ませている。したがって
;; **gate の判定ロジックが赤くなること**は示せたが、**fleet ノード上で npx 経路が
;; 通ること**はここでは示していない（ノード側は npm 11.17.0 / 10.9.8 で健全という
;; 別の実測がある）。逆向きも同じ —— ここで緑でもノードで緑とは限らない。
;; ノード上の証拠は receipt（`test-root-rename-residue-<sha7>-murakumo-<node>`）が持つ。
(ns fleet-ci.gates.rename-residue-check
  (:require ["node:child_process" :as cp]
            ["node:fs" :as fs]
            ["node:path" :as path]
            [clojure.string :as str]))

(def args (vec *command-line-args*))
(def root (or (first (remove #(str/starts-with? % "--") args)) "."))

(defn- die! [code & msg]
  (println (str "FLEET-CI: " (str/join " " msg)))
  (js/process.exit code))

;; pure case 13 + fixture case 8 = 21。fixture が丸ごと落ちても pure だけで
;; 緑にならないよう、床を fixture を含む本数に置く。
(def min-cases 21)

(def test-entry (path/join root "scripts" "rename-residue-test.cljs"))
(def gate-ns (path/join root "scripts" "rename_residue.cljs"))
(def compat-dir (path/join root "scripts" "nbb_compat"))

;; 展開失敗による false-pass を構造的に防ぐ。90 は「tree が期待どおり届いていない」
;; を表す tick.cljs 側の慣習。
(when-not (fs/existsSync test-entry)
  (die! 90 "scripts/rename-residue-test.cljs missing after extract"))
(when-not (fs/existsSync gate-ns)
  (die! 90 "scripts/rename_residue.cljs missing after extract"))
(when-not (fs/existsSync compat-dir)
  (die! 90 "scripts/nbb_compat missing after extract — classpath would be broken"))

(def result
  (try
    (cp/execSync "npx --yes nbb --classpath \".:scripts/nbb_compat\" scripts/rename-residue-test.cljs"
                 #js {:cwd root :encoding "utf8" :stdio "pipe" :timeout 600000})
    (catch :default e
      (let [out (str (or (.-stdout e) "") (or (.-stderr e) ""))]
        (println (str/trim out))
        (die! 1 "rename-residue-test failed")))))

(println (str/trim (str result)))

(let [summary (re-find #"(\d+)\s+cases?\s+OK" (str result))
      n (some-> summary second (js/parseInt 10))]
  (cond
    (nil? summary)
    (die! 93 "no 'N cases OK' summary in output — refusing to report a pass")

    (< n min-cases)
    (die! 94 (str "only " n " cases ran, expected at least " min-cases
                  " — the git fixture half probably did not run"))

    :else
    (println (str "FLEET-CI: rename-residue gate OK (" n " cases)"))))
