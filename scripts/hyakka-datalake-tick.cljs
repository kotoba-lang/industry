#!/usr/bin/env nbb
;; hyakka-datalake-tick.cljs — hyakka の台帳を R2 Data Catalog に追従させる。
;;
;;     nbb scripts/hyakka-datalake-tick.cljs [--force] [--out-dir <dir>]
;;
;; export（EDN -> JSON）と load（JSON -> Iceberg）を順に呼ぶだけで、判断は
;; 2 つしか持たない。
;;
;; ## 1. 変わっていなければ何もしない
;;
;; `datalake-sync.py` は表を全置換する。台帳が 1 バイトも動いていない時間に
;; 7,912 行を消して書き直すと、答えは同じまま Iceberg の snapshot と metadata
;; だけが増える。だから台帳の**署名**（ファイル名と各サイズ）を取って前回と
;; 比べ、同じなら export すら走らせない。
;;
;; 署名に git commit を使わないのは、resident が台帳を **untracked のまま**
;; 置く時間帯があるため（実測 2026-08-28、commit されていない ledger が
;; worktree に居た）。commit だけを見る tick は、その間ずっと「変化なし」と
;; 報告して新しい claim を落とす。
;;
;; ## 2. 測れなかったことを clean と書かない
;;
;;   0  載せた、または載せる必要がなかった
;;   1  export か load が失敗した
;;   2  REFUSED — 台帳が読めない等、そもそも問えなかった
;;
;; 1 と 2 を畳まない。「lake が古い」と「lake が古いかどうか分からない」は
;; 違う答えで、後者だけが人を呼ぶ。

(ns hyakka-datalake-tick
  (:require [clojure.string :as str]
            ["node:fs" :as fs]
            ["node:path" :as path]
            ["node:os" :as os]
            ["node:crypto" :as crypto]
            ["node:child_process" :as cp]))

(def argv (vec (drop 2 (js->clj (.-argv js/process)))))
(defn- flag [n] (let [i (.indexOf (clj->js argv) n)]
                  (when (and (>= i 0) (< (inc i) (count argv))) (nth argv (inc i)))))
(def force? (some? (some #{"--force"} argv)))

(def root "/Users/junkawasaki/github/com-junkawasaki")
(def worktree (or (flag "--worktree")
                  (str (.homedir os) "/.itonami/worktrees/app-hyakka-resident")))
(def out-dir (or (flag "--out-dir") (str (.homedir os) "/.itonami/hyakka-lake")))
(def state-path (path/join out-dir "state.json"))
(def ledger-dir (path/join worktree "knowledge" "ledger"))

(defn die! [code msg] (js/console.error msg) (.exit js/process code))

(defn ledger-signature
  "Name + size of every ledger file, hashed. Cheap, and it moves when an
  untracked file appears — which a git commit does not."
  []
  (when-not (fs/existsSync ledger-dir)
    (die! 2 (str "REFUSED: no ledger at " ledger-dir)))
  (let [files (sort (mapcat (fn walk [p]
                              (let [st (fs/statSync p)]
                                (if (.isDirectory st)
                                  (mapcat walk (sort (map #(path/join p %) (fs/readdirSync p))))
                                  (when (str/ends-with? p ".datoms.edn") [p]))))
                            [ledger-dir]))
        h (crypto/createHash "sha256")]
    (when (empty? files)
      (die! 2 (str "REFUSED: " ledger-dir " holds no .datoms.edn — an unread tree is not an empty wiki.")))
    (doseq [f files] (.update h (str f ":" (.-size (fs/statSync f)) "\n")))
    {:files (count files) :sig (.digest h "hex")}))

(defn read-state []
  (try (js->clj (js/JSON.parse (fs/readFileSync state-path "utf8")) :keywordize-keys true)
       (catch :default _ nil)))

(defn sh [args opts]
  (let [r (cp/spawnSync (first args) (clj->js (rest args))
                        (clj->js (merge {:encoding "utf8" :timeout 1800000
                                         :maxBuffer (* 64 1024 1024)} opts)))
        status (.-status r)
        signal (.-signal r)
        spawn-error (.-error r)]
    ;; A null status means the child never exited on its own: it was killed by
    ;; a signal (the :timeout above sends SIGTERM) or never started. Mapping
    ;; that to 1 makes "we cut it off at 30 minutes" and "it failed" the same
    ;; line in the log -- with empty stderr in both cases, because a killed
    ;; process writes none. Found live 2026-08-29: hyakka-datalake logged
    ;; `export failed (1):` with nothing after it, and the cause was this
    ;; timeout firing under machine load, not the exporter.
    {:exit (if (nil? status) 1 status)
     :killed-by (when (nil? status) (or signal (some-> spawn-error .-message) "unknown"))
     :out (str (or (.-stdout r) "")) :err (str (or (.-stderr r) ""))}))

(defn- failure-detail
  "Why the child stopped, said in a way that distinguishes the two cases."
  [{:keys [exit killed-by err]}]
  (if killed-by
    (str "killed by " killed-by " (not an exit code -- the timeout in `sh`, "
         "or a failure to start). A killed process writes no stderr, so its "
         "absence below is expected and is NOT evidence the child was silent "
         "about a real error.\n" err)
    (str "exit " exit "\n" err)))

(defn -main []
  (let [{:keys [files sig]} (ledger-signature)
        prev (read-state)]
    (println (str "LEDGER\t" files " files\tsig=" (subs sig 0 12)))
    (if (and (not force?) (= sig (:sig prev)))
      (do (println (str "current\tlake matches the ledger (last synced "
                        (:synced-at prev) ")"))
          (.exit js/process 0))
      (let [_ (fs/mkdirSync out-dir #js {:recursive true})
            ex (sh ["nbb" (str root "/scripts/hyakka-datalake-export.cljs")
                    "--worktree" worktree "--out-dir" out-dir] {:cwd root})]
        (print (:out ex))
        (when (pos? (:exit ex))
          (die! (if (= 2 (:exit ex)) 2 1)
                (str "export failed: " (failure-detail ex))))
        (let [ld (sh ["python3" (str root "/scripts/datalake-sync.py")
                      "--spec" (path/join out-dir "hyakka.spec.json")
                      "--in-dir" out-dir] {:cwd root})]
          (print (:out ld))
          (when (pos? (:exit ld))
            (die! (if (= 2 (:exit ld)) 2 1)
                  (str "load failed: " (failure-detail ld))))
          ;; Written only after the load reported success, so a crashed load
          ;; leaves the signature stale and the next tick retries rather than
          ;; recording a sync that did not happen.
          (fs/writeFileSync state-path
                            (js/JSON.stringify
                             (clj->js {"sig" sig "files" files
                                       "synced-at" (.toISOString (js/Date.))})))
          ;; No file count here on purpose. The signature is taken before the
          ;; export and the export counts again at its own moment, so the two
          ;; can differ by whatever the resident wrote in between — printing the
          ;; earlier number next to the loader's rows invites the reader to
          ;; treat them as the same measurement. `hyakka_manifest` carries the
          ;; counts that were actually exported; that is the joinable answer.
          (println (str "SYNCED\tsig=" (subs sig 0 12)
                        " -> cloud_itonami.hyakka_* (counts: hyakka_manifest)")))))))

(-main)
