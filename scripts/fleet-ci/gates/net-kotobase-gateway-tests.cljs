#!/usr/bin/env nbb
;; net-kotobase-gateway-tests.cljs — API gateway の cljs テストスイートを実際に回す。
;;
;; **なぜ要るか。** 2026-08-11、`client_api.cljc` の閉じ括弧 1 個で `/api/*` の
;; 全 8 経路が 502 を返していた。**そのソースのテストは 1 本も落ちなかった** ——
;; 当時 `client_api_test` は「どのパスがこの surface に属するか」を 2 件
;; 主張するだけで、**その surface を一度も呼んでいなかった**から。
;; hermetic gate は syntax と tenant-isolation は見るがテストスイートは回さない。
;; つまり fleet 側に、この種の欠陥を merge 前に止めるものが無かった。
;; （障害の実体は net-kotobase docs/adr/2608110600、live 側の目は ADR-2608110700）
;;
;; **なぜ今までできなかったか、そしてなぜ今できるか。** hermetic gate の除外リストは
;; `npm ci` 系を「ノードは tailnet だけで npm registry に届かない」として外していた。
;; 2026-08-11 実測: **judah / levi / simeon の 3 台とも registry.npmjs.org = 200、
;; repo1.maven.org = 200**、java は PATH には無いが `/opt/homebrew/opt/openjdk` に
;; 26.0.1 が在る。実際に judah で `npm ci && npm test` を通し 342 tests / 0 failures を
;; 確認してからこの gate を書いた（できると仮定して書いていない）。
;;
;; **合格の条件は「0 failures」だけではない。** 実行された test 数が `--min-tests`
;; を下回ったら落とす。classpath が壊れて 3 件だけ走り「0 failures」と報告する状態を
;; 合格にしないための床で、`:min-files` と同じ役割。
;;
;; usage: nbb net-kotobase-gateway-tests.cljs <dir> [--min-tests N]
;;   <dir> は**第 1 引数**（fleet がそう渡す）。`--flag` の後ろに置かないこと。
(ns fleet-ci.gates.net-kotobase-gateway-tests
  (:require ["node:child_process" :as cp]
            ["node:fs" :as fs]
            ["node:path" :as path]
            [clojure.string :as str]))

(def argv (vec *command-line-args*))
(def tree (or (first (remove #(str/starts-with? % "--") argv)) "."))
(defn- flag [name default]
  (let [i (.indexOf argv name)]
    (if (neg? i) default (js/parseInt (get argv (inc i) (str default)) 10))))
(def min-tests (flag "--min-tests" 340))

(def gateway (path/join tree "kotobase-api-gateway-cljs"))

(defn- die! [code msg]
  (println msg)
  (println (str "FLEET-CI-EXIT: " code))
  (js/process.exit code))

;; ── java: recorded in nodes.edn as :java-home, but NOT on a non-interactive
;; ssh PATH (measured 2026-08-11: `java -version` fails on all three nodes while
;; /opt/homebrew/opt/openjdk/bin/java reports 26.0.1). Resolve it here rather
;; than depending on the shell, and treat "no java" as 91, not as a pass.
(def ^:private java-homes
  ["/opt/homebrew/opt/openjdk" "/usr/local/opt/openjdk" "/Library/Java/JavaVirtualMachines/current"])

(defn- java-home []
  (or (some-> (aget js/process.env "JAVA_HOME") not-empty)
      (first (filter #(fs/existsSync (path/join % "bin" "java")) java-homes))))

(defn- run
  "→ {:code :out}. Never throws; the caller decides what a non-zero means."
  [cmd cwd env-extra]
  (try
    (let [out (cp/execSync cmd
                           #js {:cwd cwd :encoding "utf8" :maxBuffer (* 64 1024 1024)
                                :stdio #js ["pipe" "pipe" "pipe"]
                                :env (js/Object.assign #js {} js/process.env (clj->js env-extra))})]
      {:code 0 :out out})
    (catch :default e
      {:code (or (.-status e) 1)
       :out (str (some-> (.-stdout e) str) (some-> (.-stderr e) str))})))

(defn -main []
  (println (str "net-kotobase-gateway-tests :: " gateway))
  (when-not (fs/existsSync (path/join gateway "package.json"))
    (die! 90 (str "FLEET-CI: missing after extract: " gateway "/package.json — refusing to report a pass")))
  ;; The suite resolves `net-kotobase/object-store-b2-s3` as a :local/root sibling
  ;; INSIDE this repo, so a tree shipped without it compiles nothing. Checked
  ;; explicitly because the failure otherwise reads as a classpath error deep in
  ;; shadow-cljs output.
  (when-not (fs/existsSync (path/join tree "kotobase-object-store-b2-s3" "deps.edn"))
    (die! 90 "FLEET-CI: kotobase-object-store-b2-s3 missing from the shipped tree (:local/root dep)"))
  (let [jh (java-home)]
    (when-not jh
      (die! 91 "FLEET-CI: no JVM found (shadow-cljs needs one) — a missing tool is not a pass"))
    (println (str "java-home: " jh))
    (let [env {"JAVA_HOME" jh
               "PATH" (str (path/join jh "bin") ":" (aget js/process.env "PATH"))}
          install (run "npm ci --no-audit --no-fund" gateway env)]
      (when-not (zero? (:code install))
        (die! 1 (str "FLEET-CI: npm ci failed\n" (str/join "\n" (take-last 20 (str/split-lines (:out install)))))))
      (let [{:keys [code out]} (run "npm test" gateway env)
            lines (str/split-lines out)
            ;; Name the failures. The summary sits at the very end but each
            ;; `FAIL in (…)` block is far above it, so a plain tail shows the
            ;; count and not one test — which makes a red gate unactionable.
            failed (->> lines
                        (keep-indexed (fn [i l]
                                        (when (re-find #"^(FAIL|ERROR) in " l)
                                          (str/join " | " (map str/trim (subvec lines i (min (count lines) (+ i 3))))))))
                        (take 12))
            tail (str/join "\n"
                           (concat (when (seq failed) (cons "-- failing tests --" failed))
                                   ["-- tail --"]
                                   (take-last 12 lines)))
            ran (some-> (re-find #"Ran (\d+) tests containing (\d+) assertions" out))
            n (some-> ran second js/parseInt)
            asserts (some-> ran (nth 2) js/parseInt)
            clean? (re-find #"0 failures, 0 errors" out)]
        (cond
          (nil? ran)
          (die! 1 (str "FLEET-CI: the suite produced no test summary — it did not run\n" tail))

          (not clean?)
          (die! 1 (str "FLEET-CI: " n " tests ran with failures\n" tail))

          (< n min-tests)
          ;; 0 failures over a suite that barely ran is the shape of a broken
          ;; classpath, not of a healthy repo.
          (die! 1 (str "FLEET-CI: only " n " tests ran (floor " min-tests
                       ") — a truncated suite is not a pass\n" tail))

          (not (zero? code))
          (die! 1 (str "FLEET-CI: npm test exited " code " despite a clean summary\n" tail))

          :else
          (do (println (str "OK — " n " tests / " asserts " assertions, 0 failures, 0 errors"
                            " (floor " min-tests ")"))
              (println "FLEET-CI-EXIT: 0")))))))

(-main)
