#!/usr/bin/env nbb
;; sukashi-datalake-tick.cljs — 広告サプライチェーンの観測グラフを
;; R2 Data Catalog に追従させる。hyakka / hirameki の tick と同型。
;;
;;     nbb scripts/sukashi-datalake-tick.cljs [--force] [--repo <path>] [--out-dir <dir>]
;;
;; ## 1. 変わっていなければ何もしない
;;
;; `datalake-sync.py` は表を全置換する。グラフが動いていない時間に 8 表を消して
;; 書き直すと、答えは同じまま Iceberg の snapshot と metadata だけが増える。
;;
;; 署名は**ログ全体の sha256**。ここは 1 ファイル 13.5 MB なので数十ミリ秒で、
;; サイズや mtime より正確 —— observatory が同じ内容を書き直した周に、
;; 無駄な snapshot を作らない。
;;
;; ## 2. 測れなかったことを clean と書かない
;;
;;   0  載せた、または載せる必要がなかった
;;   1  export か load が失敗した
;;   2  REFUSED — ログが無い / 行が出所の印を持たない等、そもそも問えなかった
;;
;; **exit 2 が特に効くのがこの射影**である。observatory の出力は各 repo の
;; `.gitignore` の中にあり、走らせたマシンにしか無い。ログが無いのは
;; 「広告グラフが空」ではなく「この機械では observatory がまだ走っていない」で、
;; その 2 つを同じ緑にすると、空の表を実測として公開することになる。

(ns sukashi-datalake-tick
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

;; `--root` exists so this tick can be exercised from a worktree before the
;; scripts it calls have landed in the shared checkout.
(def root (or (flag "--root") "/Users/junkawasaki/github/com-junkawasaki"))
(def repo (or (flag "--repo") (str root "/orgs/cloud-itonami/sukashi")))
(def out-dir (or (flag "--out-dir") (str (.homedir os) "/.itonami/sukashi-lake")))
(def state-path (path/join out-dir "state.json"))
(def log-path (path/join repo "data" "sukashi.datoms.kotoba.edn"))

(defn die! [code msg] (js/console.error msg) (.exit js/process code))

(defn log-signature []
  (when-not (fs/existsSync log-path)
    (die! 2 (str "REFUSED: no datom log at " log-path
                 "\nThe observatory writes to a gitignored path, so an absent log means it has"
                 "\nnot run here — not that the ad supply chain is empty.")))
  {:bytes (.-size (fs/statSync log-path))
   :sig (.digest (.update (crypto/createHash "sha256") (fs/readFileSync log-path)) "hex")})

(defn read-state []
  (try (js->clj (js/JSON.parse (fs/readFileSync state-path "utf8")) :keywordize-keys true)
       (catch :default _ nil)))

(defn sh [args opts]
  (let [r (cp/spawnSync (first args) (clj->js (rest args))
                        (clj->js (merge {:encoding "utf8" :timeout 1800000
                                         :maxBuffer (* 64 1024 1024)} opts)))]
    {:exit (if (nil? (.-status r)) 1 (.-status r))
     :out (str (or (.-stdout r) "")) :err (str (or (.-stderr r) ""))}))

(defn -main []
  (let [{:keys [bytes sig]} (log-signature)
        prev (read-state)]
    (println (str "LOG\t" bytes " bytes\tsig=" (subs sig 0 12)))
    (if (and (not force?) (= sig (:sig prev)))
      (do (println (str "current\tlake matches the log (last synced " (:synced-at prev) ")"))
          (.exit js/process 0))
      (let [_ (fs/mkdirSync out-dir #js {:recursive true})
            ex (sh ["nbb" (str root "/scripts/sukashi-datalake-export.cljs")
                    "--repo" repo "--out-dir" out-dir] {:cwd root})]
        (print (:out ex))
        (when (pos? (:exit ex))
          (die! (if (= 2 (:exit ex)) 2 1)
                (str "export failed (" (:exit ex) "):\n" (:err ex))))
        (let [ld (sh ["python3" (str root "/scripts/datalake-sync.py")
                      "--spec" (path/join out-dir "sukashi.spec.json")
                      "--in-dir" out-dir] {:cwd root})]
          (print (:out ld))
          (when (pos? (:exit ld))
            (die! (if (= 2 (:exit ld)) 2 1)
                  (str "iceberg load failed (" (:exit ld) "):\n" (:err ld))))
          ;; Written only after the load reported success, so a crashed load
          ;; leaves the signature stale and the next tick retries rather than
          ;; recording a sync that did not happen.
          (fs/writeFileSync state-path
                            (js/JSON.stringify
                             (clj->js {"sig" sig "bytes" bytes
                                       "synced-at" (.toISOString (js/Date.))})))
          (println (str "SYNCED\tsig=" (subs sig 0 12)
                        " -> cloud_itonami.sukashi_* (provenance: sukashi_manifest)")))))))

(-main)
