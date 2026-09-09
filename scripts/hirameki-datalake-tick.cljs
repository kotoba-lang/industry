#!/usr/bin/env nbb
;; hirameki-datalake-tick.cljs — 公開特許 corpus を R2 Data Catalog に追従させる。
;;
;;     nbb scripts/hirameki-datalake-tick.cljs [--force] [--repo <path>] [--out-dir <dir>]
;;
;; export（EDN -> JSON）と load（JSON -> Iceberg）を順に呼ぶだけで、判断は
;; 2 つしか持たない。hyakka-datalake-tick.cljs と同型。
;;
;; ## 1. 変わっていなければ何もしない
;;
;; `datalake-sync.py` は表を全置換する。corpus が 1 バイトも動いていない時間に
;; 2,990 行と 10,921 本の引用エッジを消して書き直すと、答えは同じまま Iceberg の
;; snapshot と metadata だけが増える。だから corpus の**署名**（shard 名とサイズ）を
;; 取って前回と比べ、同じなら export すら走らせない。
;;
;; 署名に git commit を使わないのは、**harvest が corpus を書いてから commit する
;; までに窓がある**ため（`com.gftd.hirameki-harvest` は 20 tick 回してから 1 度
;; commit する）。commit だけを見る tick は、その窓の間ずっと「変化なし」と
;; 報告する。ファイルのサイズは commit を待たずに動く。
;;
;; ## 2. 測れなかったことを clean と書かない
;;
;;   0  載せた、または載せる必要がなかった
;;   1  export か load が失敗した
;;   2  REFUSED — corpus が読めない等、そもそも問えなかった
;;
;; 1 と 2 を畳まない。「lake が古い」と「lake が古いかどうか分からない」は
;; 違う答えで、後者だけが人を呼ぶ。

(ns hirameki-datalake-tick
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
;; scripts it calls have landed in the shared checkout. Without it the only way
;; to test a change is to land it first, which is the wrong order.
(def root (or (flag "--root") "/Users/junkawasaki/github/com-junkawasaki"))
(def repo (or (flag "--repo") (str root "/orgs/cloud-itonami/hirameki-patents")))
(def out-dir (or (flag "--out-dir") (str (.homedir os) "/.itonami/hirameki-lake")))
(def state-path (path/join out-dir "state.json"))
(def corpus-dir (path/join repo "corpus"))

(defn die! [code msg] (js/console.error msg) (.exit js/process code))

(defn corpus-signature
  "Name + size of every corpus shard, hashed. Cheap, and it moves as soon as the
  harvester writes — which a git commit does not."
  []
  (when-not (fs/existsSync corpus-dir)
    (die! 2 (str "REFUSED: no corpus at " corpus-dir)))
  (let [files (->> (fs/readdirSync corpus-dir)
                   (filter #(str/ends-with? % ".kotoba.edn"))
                   sort
                   (mapv #(path/join corpus-dir %)))
        h (crypto/createHash "sha256")]
    (when (empty? files)
      (die! 2 (str "REFUSED: " corpus-dir " holds no .kotoba.edn — an unread tree is not an empty corpus.")))
    (doseq [f files] (.update h (str f ":" (.-size (fs/statSync f)) "\n")))
    {:files (count files) :sig (.digest h "hex")}))

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
  (let [{:keys [files sig]} (corpus-signature)
        prev (read-state)]
    (println (str "CORPUS\t" files " shards\tsig=" (subs sig 0 12)))
    (if (and (not force?) (= sig (:sig prev)))
      (do (println (str "current\tlake matches the corpus (last synced "
                        (:synced-at prev) ")"))
          (.exit js/process 0))
      (let [_ (fs/mkdirSync out-dir #js {:recursive true})
            ex (sh ["nbb" (str root "/scripts/hirameki-datalake-export.cljs")
                    "--repo" repo "--out-dir" out-dir] {:cwd root})]
        (print (:out ex))
        (when (pos? (:exit ex))
          (die! (if (= 2 (:exit ex)) 2 1)
                (str "export failed (" (:exit ex) "):\n" (:err ex))))
        (let [ld (sh ["python3" (str root "/scripts/datalake-sync.py")
                      "--spec" (path/join out-dir "hirameki.spec.json")
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
                             (clj->js {"sig" sig "shards" files
                                       "synced-at" (.toISOString (js/Date.))})))
          ;; No row count here on purpose — the signature is taken before the
          ;; export and the export counts again at its own moment.
          ;; `hirameki_manifest` carries the counts that were actually exported.
          (println (str "SYNCED\tsig=" (subs sig 0 12)
                        " -> cloud_itonami.hirameki_* (counts: hirameki_manifest)")))))))

(-main)
