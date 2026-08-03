#!/usr/bin/env nbb
;; kotoba-compiler-jdk-free-check.cljs — kotoba-lang/compiler の hermetic gate。
;;
;; **なぜ fleet に置くか。** 2 つの理由が重なっている。
;;
;; 1. ADR-2607300900 / ADR-2608036600 の決定（CI は GitHub Actions ではなく
;;    murakumo fleet）。kotoba-lang org の Actions は今日は動いているが、
;;    com-junkawasaki / gftdcojp / network-awai は org 単位で止まった実績があり、
;;    「動いている org」も同じ理由で止まりうる。
;; 2. **`.github/workflows/` は書けない。** このワークスペースの token は
;;    `gist, read:org, repo` しか持たず `workflow` scope が無いので、Actions 側に
;;    step を足す経路が塞がっている（実測 2026-08-03）。fleet なら repo に
;;    ファイルを置くだけで登録できる。
;;
;; **何を検査するか。** compiler ADR-0202 の JDK-free 経路の不変条件。検査ロジックは
;; ここに複製せず、**repo 自身の verifier（`test/nbb/classpath.cljs`）を呼ぶ**
;; （fleet 側と repo 側で別実装を持つと、片方だけ通る状態が黙って生まれる）。
;;
;;   A. `deps-lock.edn` が `deps.edn` と一致する（= JDK-free 経路が生きている）
;;   B. resolver の fail-closed 一式（`--hermetic-only`）
;;   C. **JVM を PATH から消した状態**で B が通ること（= 主張そのものの回帰テスト）
;;
;; C が本題である。A/B は JVM があっても通るので、それだけでは
;; 「JDK 無しで動く」を守れない。この gate は `clojure` と `java` を exit 127 の
;; stub で隠し、**stub が 1 度でも呼ばれたら fail する**（呼ばれた＝JVM 経路に
;; 落ちた＝主張が失われた、を「たまたま通った」と区別する）。
;;
;; **持ってこられないもの**（黙って落とさず、理由を書く）:
;;   - `test/nbb/classpath.cljs` の「checked-in lock resolves」1 群。実依存 9 本を
;;     `~/.gitlibs` に要求する。ノードは tailnet だけで github.com に route が無く、
;;     `ship-git-deps!` が置く展開物には `.git` が無いので resolver の
;;     `rev-parse HEAD` 照合（= content address の確認そのもの）が成立しない。
;;     **その照合を CI の都合で緩めない。** `--hermetic-only` が明示的に除外し、
;;     除外した事実を出力に残す。
;;   - `clojure -M:test` / `npm ci` / browser matrix / wasm-tools / cargo を要する
;;     compiler CI の大半。ノードに registry egress が無い。
;;   - `bin/kotoba` の dispatch shell 自体。`<root>/node_modules/nbb/cli.js` を
;;     spawn するが tarball は `git archive` なので node_modules が無い。
;;     dispatch の下にある解決経路（本命）は B/C が直接叩く。
;;
;; **したがって green の意味は「JDK-free 解決の不変条件が保たれている」であって
;; 「compiler の CI が通った」ではない。**
;;
;; ノード側で `npx nbb kotoba-compiler-jdk-free-check.cljs <dir>` として実行。

(ns fleet-ci.gates.kotoba-compiler-jdk-free-check
  (:require ["node:child_process" :as cp]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            [clojure.string :as str]))

(def args (vec *command-line-args*))

(def root
  (or (first (remove #(str/starts-with? % "--") args)) "."))

(defn- die! [code & msg]
  (println (str "FLEET-CI: " (str/join " " msg)))
  (js/process.exit code))

(defn- run
  "ノード内の子プロセスを 1 回 -> {:rc n :out s}。stdout と stderr を必ず両方
  拾う（net-kotobase gate が python の unittest summary を stderr で取りこぼし、
  通っているテストを fail と報告した実績があるため）。"
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
;; 展開が正しいことを先に確かめる（tarball の中身が想定と違うまま「合格」しない）

(def required-paths
  ["deps.edn"
   "deps-lock.edn"
   "src/kotoba/compiler/nbb/classpath.cljs"
   "test/nbb/classpath.cljs"])

(doseq [p required-paths]
  (when-not (fs/existsSync (path/join root p))
    (die! 90 "missing after extract:" p "— refusing to report a pass")))

;; ノードに道具が無いのは「検証できなかった」であって「合格」ではない。
(doseq [bin ["node" "npx" "git"]]
  (when-not (have? bin)
    (die! 91 bin "is not installed on this node — refusing to report a pass"
          "(provision it with scripts/fleet-ci/provision.cljs)")))

(def failures (atom []))
(defn- fail! [step & msg]
  (swap! failures conj step)
  (println (str "  FAIL " step ": " (str/join " " msg))))

;; ---------------------------------------------------------------------------
;; JVM を隠す。
;;
;; `clojure` と `java` を exit 127 の stub で覆い、**呼ばれたら記録する**。
;; 「JVM が無くても通った」だけでは弱い — 通ったうえで JVM 経路に一度も
;; 落ちていないことまで見る。stale な lock は `bin/kotoba` を
;; `clojure -Spath` に落とすので、その退行がまさにこの marker に出る。

(def shadow-dir (fs/mkdtempSync (path/join (os/tmpdir) "fleet-nojvm-")))
(def marker (path/join shadow-dir "invoked.log"))

(doseq [bin ["clojure" "java" "clj"]]
  (let [p (path/join shadow-dir bin)]
    (fs/writeFileSync p (str "#!/bin/sh\n"
                             "echo \"" bin " $*\" >> " marker "\n"
                             "echo \"" bin ": hidden by fleet-ci jdk-free gate\" >&2\n"
                             "exit 127\n"))
    ;; 8 進を明示する。`0755` は reader によって 493 にも 755 にも読まれうる。
    (fs/chmodSync p (js/parseInt "755" 8))))

(def no-jvm-env
  (js/Object.assign #js {} js/process.env
                    #js {"PATH" (str shadow-dir ":" (.-PATH js/process.env))
                         ;; tools.deps を経路から確実に外す。JAVA_HOME が残って
                         ;; いると stub を迂回して実体を掴む道具がある。
                         "JAVA_HOME" (path/join shadow-dir "no-java-home")}))

;; ---------------------------------------------------------------------------
;; A. lock が deps.edn と一致する（JDK-free 経路が生きているかどうかの本体）
;;
;; ここが落ちているとき `bin/kotoba` は黙って `clojure -Spath` に落ちる
;; （= JDK 依存が復活する）。実測 2026-08-03: #500 が merge された時点で
;; 既に stale だった（open 中に deps.edn の pin が 2 本進んだ）。
;; **pin が動く頻度からして、これは edge ではなく通常の腐り方である。**

(let [{:keys [rc out]} (run "npx" ["--yes" "nbb" "--classpath" "src"
                                   "-e" (str "(require '[kotoba.compiler.nbb.classpath :as c])"
                                             "(println (if (= (c/deps-digest \".\")"
                                             " (:lock/deps-digest (c/read-lock \".\")))"
                                             " \"LOCK-FRESH\" \"LOCK-STALE\"))")]
                            {:cwd root :env no-jvm-env})]
  (println "A lock-freshness: rc=" rc)
  (cond
    (str/includes? out "LOCK-FRESH") (println "  ok — deps-lock.edn matches deps.edn")
    (str/includes? out "LOCK-STALE")
    (fail! "lock-freshness"
           "deps-lock.edn does not match deps.edn — the JDK-free path is dead"
           "(regenerate: nbb scripts/lock-classpath.cljs)")
    :else (fail! "lock-freshness" "no verdict from the resolver:" (tail out 8))))

;; ---------------------------------------------------------------------------
;; B/C. repo 自身の verifier を、JVM を隠した状態で回す。
;;
;; `--hermetic-only` は実依存の closure を要る 1 群を明示的に除外する
;; （ノードは github.com に届かない）。除外は出力に残るので、
;; 「全部走った」と「一部を飛ばした」が受領書の上で区別できる。

(let [{:keys [rc out]} (run "npx" ["--yes" "nbb" "--classpath" "src"
                                   "test/nbb/classpath.cljs" "--hermetic-only"]
                            {:cwd root :env no-jvm-env})
      passes (count (re-seq #"(?m)^PASS " out))
      skips (count (re-seq #"(?m)^SKIP " out))]
  (println "B resolver-fail-closed:" (str "rc=" rc ", " passes " pass, " skips " skip"))
  (doseq [l (str/split-lines (str/trim out))
          :when (re-find #"^(SKIP|FAIL)" l)]
    (println "   " l))
  (cond
    (not (str/includes? out "jvm-free-classpath"))
    (fail! "resolver" "no verdict line — refusing to report a pass:" (tail out 10))

    (str/includes? out "FAIL jvm-free-classpath")
    (fail! "resolver" "the repo's own verifier failed:" (tail out 12))

    ;; 床。除外や壊れた引数で検査が空になったのを「合格」にしない。
    (< passes 8)
    (fail! "resolver" "only" passes "checks ran — expected at least 8")

    (not (zero? rc))
    (fail! "resolver" "verifier exited" rc)

    :else (println "  ok —" passes "fail-closed checks passed with no JVM on PATH")))

;; ---------------------------------------------------------------------------
;; C の本体: JVM stub が 1 度でも呼ばれていないこと。

(if (fs/existsSync marker)
  (fail! "jdk-free" "the JVM was reached during this run:"
         (tail (fs/readFileSync marker "utf8") 5)
         "— a pass that needed a JDK is not the property this gate exists for")
  (println "C jdk-free: ok — clojure/java/clj were never invoked"))

;; ---------------------------------------------------------------------------

(if (seq @failures)
  (die! 1 (count @failures) "of 3 checks failed:" (str/join ", " (distinct @failures)))
  (println "OK — deps-lock fresh + resolver fail-closed + no JVM reached"))
