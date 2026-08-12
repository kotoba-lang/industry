#!/usr/bin/env nbb
;; yorishiro-provider-vault-check.cljs — kotoba-lang/app-yorishiro の provider/ suite を
;; ノード上で実際に回す gate。
;;
;; **なぜ要るか。** ADR-2608122600 が記録した最悪の site はこの repo だった:
;; `provider/{nuro,japanpost-enaiyo}/runner.ts` が `VAULT_URL ?? "http://vault:8200"` を
;; 既定に持ち、`X-Vault-Token: $VAULT_TOKEN` を **cleartext で誰も所有していない
;; single-label ホスト**へ送り、返ってきたものを無検査で信じて **銀行口座**にし、
;; flow.ts がそれをキャッシュバック請求フォームに submit していた。偽の `vault` は
;; token を受け取るだけでなく、金の行き先を選べた。
;;
;; merge 3d161b144ae7 がこれを直し、`provider/vault-client.test.ts` に 34 本の
;; テストを足した。そのうち **10 本は source guard** で、runner の中に
;; 既定エンドポイントも手組みの Vault request も無いことを直接 assert する
;; （= 再発を止めているのはこの 10 本）。**が、それを走らせるものが 1 つも
;; 無かった** —— gates.edn にこの repo の entry は無く、GitHub Actions も無い。
;; guard は書かれていたが、誰も読んでいなかった。
;;
;; **何を検査するか。** repo 自身の vitest suite をそのまま回す。検査ロジックを
;; ここに複製しない（fleet 側と repo 側で別実装を持つと、片方だけ通る状態が
;; 黙って生まれる。net-kotobase-hermetic-check.cljs と同じ規律）。この gate が
;; 持つのは「実際に走ったか」「本数が減っていないか」「guard block がまだ在るか」
;; の 3 つの床だけ。
;;
;; **なぜ npm を引けるのか。** ノードは外向き HTTPS を持つ。2026-08-05 に到達可能な
;; 10 ノード全部で registry.npmjs.org / repo1.maven.org / github.com を実測して
;; すべて 200 だった。README と tick.cljs の一部コメントに残る「ノードは tailnet
;; だけ」は 2026-07-26 に zebulun 1 台で測った値の一般化で、誤り
;; （この誤った前提で workflow 167 本が不当に拒否されていた）。
;;
;; **kotoba/ を含めない理由。** kotoba/package.json の依存は
;; `git+https://github.com/etzhayyim/com-etzhayyim-sdk*.git` で、ノードに credential を
;; 置かない不変条件と衝突しうる。ここで検査したいのは credential 経路の guard なので、
;; 対象は provider/ に閉じる。kotoba/ を足すなら別 gate として、依存が anonymous に
;; 引けることを実測してから。
;;
;; ノード側で `npx nbb yorishiro-provider-vault-check.cljs <dir> [--min N]`
;; として実行（**<dir> は引数の先頭**。多くの gate と同じく、最初の非 --flag を
;; tree として取る）。
(ns fleet-ci.gates.yorishiro-provider-vault-check
  (:require ["node:child_process" :as cp]
            ["node:fs" :as fs]
            ["node:path" :as path]
            [clojure.string :as str]))

(def args (vec *command-line-args*))

(def ^:private value-flags #{"--min"})

(defn- flag [nm default]
  (let [i (.indexOf args nm)]
    (if (neg? i) default (get args (inc i) default))))

(def root
  (loop [[a & more] args prev nil]
    (cond (nil? a) "."
          (str/starts-with? a "--") (recur more a)
          (contains? value-flags prev) (recur more nil)
          :else a)))

;; false-pass の床。suite が壊れて 0 件になったとき「0 件検証して合格」を返さない。
;; 34 は fix 時点の実測値（10 source/README guard + 24 behavioural）。
(def min-tests (js/parseInt (str (flag "--min" 34)) 10))

(defn- die! [code & msg]
  (println (str "FLEET-CI: " (str/join " " msg)))
  (js/process.exit code))

(defn- run
  "ノード内の子プロセスを 1 回。-> {:rc n :out s}。stdout と stderr を必ず両方拾う
  （vitest は summary を stdout に、install 失敗を stderr に書く）。"
  [cmd argv opts]
  (let [r (cp/spawnSync cmd (clj->js (vec argv))
                        (clj->js (merge {:encoding "utf8" :maxBuffer 33554432} opts)))]
    {:rc (if (nil? (.-status r)) 1 (.-status r))
     :out (str (or (some-> (.-stdout r) str) "")
               (or (some-> (.-stderr r) str) "")
               (when (.-error r) (str "\n" (.. r -error -message))))}))

(defn- have? [bin]
  (zero? (:rc (run "bash" ["-c" (str "command -v " bin)] {}))))

(defn- tail [s n]
  (str/join "\n" (take-last n (str/split-lines (str/trim (str s))))))

;; ---------------------------------------------------------------------------
;; 1. 展開が正しいことを先に確かめる。
;;
;; :include-ext の絞り込みが壊れて provider/ が届かないと、vitest は
;; 「テストファイルが無い」で終わる。それを合格と読ませない。
;; runner.ts と README.md まで名指しするのは、source guard と README guard が
;; **その 2 種のファイルを読む**から —— 届いていなければ guard は問いを立てられない。

(def provider-dir (path/join root "provider"))

(def required
  ["provider/package.json"
   "provider/vitest.config.ts"
   "provider/vault-client.ts"
   "provider/vault-client.test.ts"
   "provider/nuro/runner.ts"
   "provider/nuro/README.md"
   "provider/japanpost-enaiyo/runner.ts"
   "provider/japanpost-enaiyo/README.md"])

(doseq [rel required]
  (let [p (path/join root rel)]
    (when-not (fs/existsSync p)
      (die! 90 "missing after extract:" rel
            "— the provider suite cannot inspect what was not shipped;"
            "refusing to report a pass"))))

;; ノードに道具が無いのは「検証できなかった」であって「合格」ではない。
(doseq [bin ["node" "npm"]]
  (when-not (have? bin)
    (die! 91 bin "is not installed on this node — refusing to report a pass"
          "(provision it with scripts/fleet-ci/provision.cljs)")))

;; ---------------------------------------------------------------------------
;; 2. guard block がまだ在ることを確かめる。
;;
;; 本数の床（--min）だけだと、guard describe を消して別のテストを 4 本足せば
;; 34 のまま緑になる。**guard が消えたことは、guard が落ちたことより静か**なので、
;; 名指しで存在を要求する。ここで assert しているのは guard の中身ではなく
;; 「guard が在ること」— 検査ロジックの複製ではない。

(let [src (str (fs/readFileSync (path/join root "provider" "vault-client.test.ts") "utf8"))]
  (doseq [blk ["runner source guards"
               "deployment checklist documents the required configuration"]]
    (when-not (str/includes? src (str "describe(\"" blk "\""))
      (die! 92 "the test file no longer contains a `describe(\"" blk "\")` block"
            "— the regression guard was removed, not merely broken"))))

;; ---------------------------------------------------------------------------
;; 3. 依存を入れて suite を回す。

(println "installing provider devDependencies (vitest) …")
(let [{:keys [rc out]} (run "npm" ["install" "--no-audit" "--no-fund" "--loglevel=error"]
                            {:cwd provider-dir})]
  (when-not (zero? rc)
    (die! 93 "npm install failed in provider/ —"
          "cannot run the suite, so this is `not verified`, not `pass`:\n"
          (tail out 15))))

(def vitest-bin (path/join provider-dir "node_modules" ".bin" "vitest"))
(when-not (fs/existsSync vitest-bin)
  (die! 93 "node_modules/.bin/vitest absent after npm install — refusing to report a pass"))

(def result (run vitest-bin ["run"] {:cwd provider-dir}))
(println (tail (:out result) 25))

;; ---------------------------------------------------------------------------
;; 4. 判定。
;;
;; vitest の summary 行は緑なら `Tests  34 passed (34)`、赤なら
;; `Tests  4 failed | 30 passed (34)`。**括弧内の総数**を読む（passed だけを
;; 読むと、落ちた分だけ総数が減ったように見えて床をすり抜ける）。

(def total
  (some-> (re-find #"Tests\s+.*?\((\d+)\)" (str (:out result))) second js/parseInt))

(cond
  (nil? total)
  (die! 94 "no `Tests N` summary in vitest output — the suite did not run;"
        "refusing to report a pass")

  (< total min-tests)
  (die! 95 "only" total "tests in the provider suite (expected >=" min-tests
        ") — tests were deleted, or the run was truncated")

  (not (zero? (:rc result)))
  (die! 1 "provider suite FAILED —" total "tests, vitest exited" (:rc result))

  :else
  (println (str "FLEET-CI: provider suite OK — " total " tests, "
                (count required) " required paths present, guard blocks intact")))
