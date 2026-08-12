#!/usr/bin/env nbb
(ns fleet-ci.gates.amu-native-conformance-check
  (:require [clojure.string :as str]
            ["node:child_process" :as cp]
            ["node:fs" :as fs]
            ["node:path" :as path]))

(def args (vec *command-line-args*))
(def root (or (first (remove #(str/starts-with? % "--") args)) "."))

(defn- die! [code & message]
  (println (str "FLEET-CI: " (str/join " " message)))
  (js/process.exit code))

(doseq [relative ["bin/kotoba" "scripts/jdk-free-native-conformance.cljs"
                  "scripts/windows-profile-conformance.cljs"
                  "examples/structured.kotoba" "examples/nested-record.kotoba"
                  "examples/held-operations.kotoba"
                  "tools/kexe_loader.c"]]
  (when-not (fs/existsSync (path/join root relative))
    (die! 90 "missing after extract:" relative "— refusing to report pass")))

;; ---------------------------------------------------------------------------
;; `bin/amu` は自分の nbb CLI を `<root>/node_modules/nbb/cli.js` から spawn する。
;; tarball は `git archive` なので **node_modules が無い**。すると:
;;
;;   resolveWithLock → spawn(node, <存在しない cli.js>) → 非 0 → null
;;   → 「deps-lock.edn is missing or stale」→ `clojure -Spath` へ fallback
;;   → conformance script が PATH に仕込んだ exit 127 の stub に当たる
;;   → `error: cannot resolve Amu's dependency closure.`
;;
;; この最後の 1 行だけを見ると「依存が無い」と読めるが、**lock も依存も正しく、
;; 読む道具が無かった**。実測 2026-08-13、simeon で 28 run 連続赤の正体はこれと
;; `~/.gitlibs` の archive 形（tick.cljs 側で解消）の 2 つ。
;;
;; 姉妹 gate `kotoba-compiler-jdk-free-check.cljs` は同じ事実をヘッダに書いた上で
;; **dispatch shell を検査対象から外している**。この gate は逆に `bin/amu` を端から
;; 端まで動かすのが目的なので、外さずに道具を置く。
;;
;; `npm ci` は package-lock.json の pin をそのまま使う（ambient な npx 解決に
;; しない — CLAUDE.md の実測どおり、pin しない interpreter は「言語側の失敗を
;; コードの失敗として報告する」）。`--ignore-scripts` は @playwright/test の
;; postinstall（ブラウザ一式のダウンロード）を止めるため。実測 4 秒 / 9 package。
;;
;; **失敗したら黙って進まない。** 進むと上記の誤解を招く 1 行が再び出る。
(let [pkg-lock (fs/existsSync (path/join root "package-lock.json"))
      pkg (fs/existsSync (path/join root "package.json"))]
  (when-not pkg
    (die! 90 "no package.json — bin/amu resolves its nbb CLI from node_modules and cannot run"))
  (when-not (fs/existsSync (path/join root "node_modules" "nbb" "cli.js"))
    (println (str "npm " (if pkg-lock "ci" "install") " --ignore-scripts (bin/amu needs node_modules/nbb/cli.js)"))
    (let [r (cp/spawnSync "npm" (clj->js (if pkg-lock
                                           ["ci" "--ignore-scripts"]
                                           ["install" "--ignore-scripts"]))
                          #js {:cwd root :encoding "utf8" :maxBuffer 33554432})
          code (if (nil? (.-status r)) 1 (.-status r))]
      (when-not (zero? code)
        (die! 1 "npm install failed — bin/amu cannot resolve its nbb CLI:"
              (str/trim (str (or (.-stdout r) "") (or (.-stderr r) "")))))
      (when-not (fs/existsSync (path/join root "node_modules" "nbb" "cli.js"))
        (die! 1 "npm install succeeded but node_modules/nbb/cli.js is still absent —"
              "bin/amu would fall back to `clojure -Spath` and report a dependency error")))))

(defn- run-script! [script label]
  (let [result (cp/spawnSync "npx" #js ["--yes" "nbb" script]
                             #js {:cwd root :encoding "utf8" :maxBuffer 33554432
                                  :env js/process.env})
        code (if (nil? (.-status result)) 1 (.-status result))
        output (str (or (.-stdout result) "") (or (.-stderr result) ""))]
    (println (str/join "\n" (take-last 20 (str/split-lines (str/trim output)))))
    (when (.-error result)
      (die! 91 label "could not start:" (.. result -error -message)))
    (when-not (zero? code)
      (die! 1 label "exited" code))
    output))

(let [native-output (run-script! "scripts/jdk-free-native-conformance.cljs"
                                 "native conformance")]
  (when-not (re-find #"jdk-free-native: sealed (aarch64|x86_64) scalar, aggregate-variant, callable, bounded-apply artifacts independently extracted and executed under W\^X loader"
                     native-output)
    (die! 1 "native conformance emitted no held-operation runtime verdict")))

(let [windows-output (run-script! "scripts/windows-profile-conformance.cljs"
                                  "Windows profile conformance")]
  (when-not (re-find #"windows-profile: recursive-record (aarch64|x86_64) Windows KEXE verified"
                     windows-output)
    (die! 1 "Windows profile emitted no recursive-record KEXE verdict"))
  (when-not (re-find #"windows-profile: aggregate-variant callable bounded-apply (aarch64|x86_64) Windows KEXE verified"
                     windows-output)
    (die! 1 "Windows profile emitted no held-operation KEXE verdict"))
  (when-not (re-find #"windows-profile: entryless (aarch64|x86_64) Windows library verified"
                     windows-output)
    (die! 1 "Windows profile emitted no entryless-library verdict")))

(println "OK — JDK-free aggregate/callable execution + Windows KEXE verification passed")
