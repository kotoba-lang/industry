#!/usr/bin/env nbb
;; kototama-hermetic-test.cljs — kototama の、この repo だけで検査できる部分の gate。
;;
;; **なぜ `:jvm-test` ではないのか。** kototama の `:test` alias は
;; `:local/root "../kotoba"` などを解決する。west 配置の workstation では正しく、
;; fleet ノードでは**不可能**である —— fleet が配るのはその repo の tree だけで、
;; sibling は入らない。だから `clojure -M:test` を回す gate は、コードではなく
;; classpath について落ち続ける。
;;
;; 代わりに repo 側が `:test-hermetic` を宣言し（namespace を明示列挙 —— runner の
;; 既定スキャンは、将来 sibling を要する namespace が増えたときにそれも読み込む）、
;; この gate はそれを回す。**検査できない部分は gate に含めない**。含めたふりを
;; すると、赤の理由が「依存が無い」なのか「コードが壊れた」なのか誰も区別できなく
;; なる。
;;
;; **この列挙は 2 namespace だった。実測していなかったからで、残りが sibling を
;; 要するからではない。**（2026-08-20 実測）29 の test namespace は全部、この repo
;; だけで組んだ classpath で **load する**。sibling が塞いでいたものは無かった。
;; 足りていなかったのは**ファイル**で、それは repo ではなく gates.edn の
;; `:include-ext` の問題だった:
;;
;;   .edn .clj .cljc .cljs だけ配る（旧）   207 tests, 12 failures + 1 error
;;   .wasm .kotoba .mjs 等も配る（現）      205 tests,  0 failures
;;
;; 落ちていたのはコードではなくファイルである —— `tender-test` の emit fixture は
;; `.wasm`/`.kotoba`、`host-parity-live-test` が駆動する host は `.mjs`、TCB
;; inventory が hash する `workerd/kototama-core-host.mjs` も `.mjs`。
;; **再現するのは repo ではなく、絞り込んだ後の tree である**
;; （CLAUDE.md「赤い gate を直す前に 3 つ確かめる」4 と同型。あちらは唯一の
;; production source が落ちた例、こちらは fixture が落ちた例）。
;;
;; 何が覆われるか（205 tests / 1,270 assertions）: linear-journal の at-most-once
;; 消費台帳と content-addressed chain、execution 値と memo 可否（root
;; ADR-2608160200）、delivery semantics、tender と Chicory の 74 tests、component
;; authority / grant / platform / provider、guest・browser・contract の parity、
;; TCB inventory の digest drift 検出。
;;
;; 何が覆われないか: `kototama.packaging-test` の 2 tests だけ。
;; `deploy/validate-packaging.sh` が `deploy/bin/kototama-authority-daemon`
;; ——**拡張子の無い** wrapper——を要求し、拡張子の allowlist では原理的に選べない。
;; 黙って落とすのではなく、ここで名指しする。
;;
;; ## exit 0 を信用しない
;;
;;   1. `:min-files`（gates.edn）が空 tree を gate 起動前に弾く。
;;   2. この gate が deps.edn / src / test の存在を extract 後に確認し、無ければ
;;      exit 90（tick.cljs の「tree が期待どおり届いていない」慣習）。
;;   3. この gate が `Ran N tests` の存在と N >= --min を assert する。
;;      **0 件を走らせて exit 0 は、通ったのと同じ顔をする。**
;;
;; ネットワーク: 要る（test-runner と io-multiformats を解決する）。
;;
;; ノード側で `npx nbb kototama-hermetic-test.cljs <dir> --min 20` として実行。
;; ⚠ `<dir>` は引数の**先頭**に置く（CLAUDE.md「赤い gate を直す前に 3 つ確かめる」2）。
(ns fleet-ci.gates.kototama-hermetic-test
  (:require ["node:child_process" :as cp]
            ["node:fs" :as fs]
            ["node:path" :as path]
            [clojure.string :as str]))

(def args (vec *command-line-args*))
(def root (or (first (remove #(str/starts-with? % "--") args)) "."))
(def min-tests (or (second (drop-while #(not= % "--min") args)) "20"))

(defn- die! [code & msg]
  (println (str "FLEET-CI: " (str/join " " msg)))
  (js/process.exit code))

(doseq [[p why] [["deps.edn" ":include-ext に .edn が要る（無いと alias が読めない）"]
                 ["src" ":include-ext に .clj/.cljc が要る"]
                 ["test" "テストの無い tree で「0 件 = 合格」を報告させない"]]]
  (when-not (fs/existsSync (path/join root p))
    (die! 90 p "missing after extract —" why)))

(def result
  (try
    {:code 0
     :out (cp/execFileSync "clojure" (clj->js ["-M:test-hermetic"])
                           #js {:encoding "utf8" :cwd root :maxBuffer 33554432})}
    (catch :default e
      {:code (or (.-status e) 1)
       :out (str (or (some-> (.-stdout e) str) "")
                 (or (some-> (.-stderr e) str) ""))})))

(print (:out result))

;; runner が実際にテストを走らせたことを、呼び出し側でも測る。alias が壊れて
;; いても、namespace 名が変わっていても、runner は 0 件で exit 0 を返しうる。
(let [m (re-find #"Ran (\d+) tests" (str (:out result)))]
  (when-not m
    (die! 93 "no `Ran N tests` summary in output"
          "— refusing to report a pass for a run that may not have tested anything"))
  (when (< (js/parseInt (second m)) (js/parseInt min-tests))
    (die! 93 "ran only" (second m) "tests (floor" (str min-tests ")")
          "— refusing to report a pass on a shrunken suite")))

(js/process.exit (:code result))
