#!/usr/bin/env nbb
;; net-kotobase-bundle-freshness.cljs — commit 済みの Worker バンドルが、
;; **その commit の source から作られたものか**。JDK を要求しない。
;;
;; **なぜ要るか。** `kotobase-api-gateway/js/kotobase-worker.js` は wrangler の
;; `main` が指すデプロイ成果物であり、かつ commit 済みである。したがって
;; 「source が緑」と「production が動かしているものが同じ」は独立した事実で、
;; **実際に食い違った実績がある** —— 2026-08-10 に source 変更が main に入り、
;; バンドルの再ビルドは翌 02:58 UTC の別 PR（#398）だった。その間、main の
;; source とデプロイ可能な artifact は別物だった。
;;
;; **なぜ JDK を要求しないか（2026-08-11、オーナー指示「java に依存しないように」）。**
;; 初版はここでテストスイートを回していた（`npm ci && npm test`）が、それは
;; shadow-cljs = JVM を要求する。JDK-free 化を実測で試した結果:
;;   - repo 側の Closure 依存は `transit.cljc` の `goog.typeOf` 1 箇所だけで、
;;     これは除去した（`object?` に置換、368 tests 無回帰）。
;;   - `shadow.resource/inline` は nbb 用の runtime shim で回避できる。
;;   - **それでも nbb では動かない。** Worker の経路で `nbb_core.js` 内部が
;;     `Cannot read properties of undefined (reading 'length')` で落ち、
;;     スタックに**アプリのフレームが 1 つも無い** —— 直せる行が存在しない、
;;     nbb/SCI 側の限界。
;;   - この workspace に JDK-free な ClojureScript コンパイラは無い
;;     （repo-search / concept-lookup とも該当なし）。
;; つまり **shadow-cljs の代替は今日は無い**。仮に nbb で通したとしても、それは
;; **出荷されるのとは別の runtime** を検査したことになり、このセッションが潰して
;; きた「緑だが出荷物を見ていない」を作り直すだけになる。
;;
;; **なのでここは問いを変える。** バンドルを*作る*には JVM が要るが、
;; **作られたものが古いかどうかを見るのに JVM は要らない** —— sha256 で足りる。
;; 検査ロジックは repo 自身の `scripts/bundle-manifest.cljs` をそのまま呼ぶ
;; （gate 側に複製しない）。
;;
;; **この gate が答えない問い**（黙って落とさず書く）: コードが正しいか。
;; テストスイートは operator 側の `npm test` のまま。merge 前に落とす目は
;; 現在 fleet に無い。production 側は `net-kotobase-live-planes` が見る。
;;
;; usage: nbb net-kotobase-bundle-freshness.cljs <dir>
(ns fleet-ci.gates.net-kotobase-bundle-freshness
  (:require ["node:child_process" :as cp]
            ["node:fs" :as fs]
            ["node:path" :as path]
            [clojure.string :as str]))

(def argv (vec *command-line-args*))
(def tree (or (first (remove #(str/starts-with? % "--") argv)) "."))

(defn- die! [code msg]
  (println msg)
  (println (str "FLEET-CI-EXIT: " code))
  (js/process.exit code))

(defn -main []
  (println (str "net-kotobase-bundle-freshness :: " tree))
  (let [script (path/join tree "scripts" "bundle-manifest.cljs")
        bundle (path/join tree "kotobase-api-gateway" "js" "kotobase-worker.js")]
    ;; The verifier and the artifact both have to be in the shipped tree. A gate
    ;; whose input is absent has not asked its question — it must not pass.
    (when-not (fs/existsSync script)
      (die! 90 (str "FLEET-CI: missing after extract: scripts/bundle-manifest.cljs"
                    " — refusing to report a pass")))
    (when-not (fs/existsSync bundle)
      (die! 90 "FLEET-CI: missing after extract: kotobase-api-gateway/js/kotobase-worker.js"))
    ;; A JVM must not be needed. If one is ever reintroduced here we want to
    ;; hear about it, so say plainly that this path does not use it.
    (println "jdk: not required by this gate (sha256 only)")
    (let [;; Prefer an nbb already on PATH. `npx --yes nbb <script> <args>` is
          ;; the fleet's own convention but it misparses trailing arguments on
          ;; some npm versions — it treated the script path as a package name
          ;; here (npm 11.12.1, measured 2026-08-11) — so only fall back to it.
          runner (if (zero? (:code (try {:code 0 :out (cp/execSync "command -v nbb"
                                                                   #js {:encoding "utf8"
                                                                        :stdio #js ["pipe" "pipe" "pipe"]})}
                                        (catch :default _ {:code 1 :out ""}))))
                   "nbb"
                   "npx --yes nbb@1.4.208")
          q (fn [s] (str "'" (str/replace s "'" "'\\''") "'"))
          {:keys [code out]}
          (try
            {:code 0 :out (cp/execSync (str runner " " (q script) " " (q tree) " check")
                                       #js {:encoding "utf8" :maxBuffer (* 16 1024 1024)
                                            :stdio #js ["pipe" "pipe" "pipe"]})}
            (catch :default e
              {:code (or (.-status e) 1)
               :out (str (some-> (.-stdout e) str) (some-> (.-stderr e) str))}))]
      ;; The verifier prints its own FLEET-CI-EXIT line; echo its output and
      ;; adopt its verdict rather than emitting a second, competing one.
      (println (str/trim (str/replace out #"(?m)^FLEET-CI-EXIT: \d+$" "")))
      (if (zero? code)
        (println "FLEET-CI-EXIT: 0")
        (die! 1 "FLEET-CI: the committed bundle does not match this tree's sources")))))

(-main)
