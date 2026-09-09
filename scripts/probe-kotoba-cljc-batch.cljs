#!/usr/bin/env nbb
;; probe-kotoba-cljc-batch.cljs — canonical compile probes を **amu CLI** で回す。
;;
;; 2026-08-30: JVM 版 (`probe-kotoba-cljc-batch.clj`, kotoba.compiler.core 直呼び +
;; java.security.MessageDigest) を amu CLI (`orgs/kotoba-lang/amu/bin/amu`)
;; spawn 形へ移植。コンパイラ自体は同一（amu は kotoba compiler の正規入口）で、
;; 判定は amu の JSON 出力 (`:ok true/false`) に寄せる。JVM/Clojure 依存が消える。
;;
;; 入力: tasks JSON（旧形式と同一 — [{:source "…" :transformed "…" :target "web"|"wasm"} …]）
;; 出力: results JSON（旧形式と同一 — 各 task に :direct / :explicit_export_remediation）
;;
;; target → amu target の対応: "web" → wasm32-browser, "wasm" → wasm32
;; （旧版の :js-kotoba-v1 / :wasm32-browser-kotoba-v1 プロファイル相当。
;;  amu が wasm artifact を出すので web/wasm の差は backend 指定だけ）。
;;
;; Run:
;;   nbb --classpath "scripts" scripts/probe-kotoba-cljc-batch.cljs <tasks.json> <out.json>
;;   （AMU_BIN で amu の場所を上書きできる。既定 orgs/kotoba-lang/amu/bin/amu）
(ns probe-kotoba-cljc-batch
  (:require [clojure.string :as str]
            ["node:child_process" :as cp]
            ["node:crypto" :as crypto]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]))

(def root (or (aget (.-env js/process) "COM_JUNKAWASAKI_ROOT")
              ;; legacy; `gftd` is retired (manifest/gftd-retirement.edn)
              (aget (.-env js/process) "GFTD_ROOT") "."))
(def amu-bin (or (aget (.-env js/process) "AMU_BIN")
                 (path/join root "orgs/kotoba-lang/amu/bin/amu")))

(defn- sha256 [s]
  (-> (crypto/createHash "sha256") (.update s) (.digest "hex")))

(defn- amu-compile!
  "source を amu に渡して compile。 {:status :exit_status :code :phase :message
   :artifact_sha256 :provenance_manifest} を返す。旧版の diagnostic-context は
   amu JSON diagnostic (:span) をそのまま運ぶ。"
  [source target]
  (let [tmpdir (fs/mkdtempSync (path/join (os/tmpdir) "amu-probe-"))
        src-path (path/join tmpdir "probe.cljc")
        out-path (path/join tmpdir "probe.wasm")
        amu-target (if (= target "web") "wasm32-browser" "wasm32")
        _ (fs/writeFileSync src-path source)
        r (cp/spawnSync amu-bin
                        (clj->js ["compile" src-path "--target" amu-target "--output" out-path])
                        #js {:encoding "utf8" :timeout 60000})
        stdout (or (.-stdout r) "")
        parsed (try (js->clj (js/JSON.parse stdout) :keywordize-keys true)
                    (catch :default _ nil))]
    ;; 先に成果物を読んでから掃除する（cleanup 後は wasm が無い）。
    (let [wasm (try (fs/readFileSync out-path) (catch :default _ nil))
          provenance? (boolean (try (fs/statSync (str out-path ".provenance.edn"))
                                    (catch :default _ nil)))]
      (doseq [f (fs/readdirSync tmpdir)] (fs/unlinkSync (path/join tmpdir f)))
      (fs/rmdirSync tmpdir)
      ;; amu の成功/失敗出力は EDN map ({:ok true ...} / {:format :kotoba.cli-error/v1 ...})。
      ;; JSON ではないので、成功は exit code 0 で判定し、失敗の diagnostic は
      ;; 文字列から :code :xxx / :message "..." を抜く（kotoba.diagnostic/v1 の形）。
      (if (zero? (.-status r))
        {:status "accepted" :exit_status 0 :code "ok" :phase nil
         :message "compiled"
         :artifact_sha256 (when wasm (sha256 wasm))
         :provenance_manifest provenance?}
        (let [s (str stdout " " (or (.-stderr r) ""))
              code (or (second (re-find #":error\s+([a-z0-9-]+)" s))
                       (second (re-find #":code\s+:kotoba\.error\/([a-z0-9-]+)" s))
                       "subset-reject")
              msg (or (second (re-find #":message \"([^\"]*)\"" s))
                      (str/trim s))]
          {:status "rejected" :exit_status 1
           :code code
           :phase "subset"
           :message msg
           :artifact_sha256 nil
           :provenance_manifest false})))))

(let [[input-path output-path] *command-line-args*]
  (when (or (nil? input-path) (nil? output-path))
    (binding [*out* *err*]
      (println "usage: probe-kotoba-cljc-batch.cljs <tasks.json> <out.json>"))
    (.exit js/process 2))
  (let [tasks (js->clj (js/JSON.parse (fs/readFileSync input-path "utf8")) :keywordize-keys true)
        results (mapv (fn [{:keys [source transformed target] :as task}]
                        (let [direct (amu-compile! source target)
                              remediation (when (and (= "rejected" (:status direct)) transformed)
                                            (amu-compile! transformed target))]
                          (-> task
                              (dissoc :source :transformed)
                              (assoc :direct direct :explicit_export_remediation remediation))))
                      tasks)]
    (fs/writeFileSync output-path (js/JSON.stringify (clj->js results)))))
