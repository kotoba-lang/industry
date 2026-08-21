(ns gftd.fabric-actor-core
  "fabric actor（`*-organism.aozora.app`）が外へ出す文字列と終了状態の
   **参照実装**。正本は `scripts/kotoba/fabric_actor_core.kotoba`（ADR-2608155000）。

   ## 誰がこれを読むか

   - `scripts/register_fabric_actors.clj`（JVM、登録）
   - `scripts/publish_fabric_actor_status.clj`（JVM、週次 pulse。launchd
     `com.junkawasaki.fabric-actor-cadence`）
   - `scripts/fleet-ci/gates/fabric-actor-core-check.cljs`（nbb、パリティ gate）

   JVM と nbb の両方から読まれるので `.cljc`。両 script はこれを require し、
   **自分では文字列を組み立てない** —— 2026-08-15 以前は 2 本が product 一覧・
   handle・Keychain service 名・exit 規則をそれぞれ独立に持っており、片方だけ
   直る状態だった。

   ## kernel と食い違ったら

   **kernel が正しい。ここを直す。** `manifest/org-id.kotoba` と
   `scripts/org_id_derivation.cljs` の分担と同じで、gate
   `root-fabric-actor-core` が両者の完全一致を毎回見る。

   ## 欠測の綴り

   `nil`（host がその欄を読めなかった）は文字列欄で `\"unknown\"`、件数欄でも
   `\"unknown\"` になる。`0` は `\"0\"` のまま —— 測って 0 だった、である。
   これは移行時に直した実バグで、以前は `(str nil)` が空文字になり
   `runtime=/` と出ていた（測れなかったのか 0 なのか読み手に判別できない）。"
  (:require [clojure.string :as str]))

;; fabric actor を持つ product。**この 1 箇所が正**（両 script が別々に
;; 持っていた列を畳んだもの）。
(def products
  ["itonami" "manimani" "isekai" "shinshi" "yukkuri" "dougaka" "animeka" "mangaka" "babiniku"])

(def pds "https://pds.aozora.app")

;; host が「その欄を読めなかった」ことを kernel へ渡す番兵。件数は負値、
;; 文字列は空文字。件数に負は無いので実在の値と衝突しない。
(def absent-count -1)

(defn ^:private count-sentinel
  "JSON から読んだ件数を kernel が受け取れる i64 にする。数でないもの
   （nil / 文字列 / 小数）は欠測として扱う —— 勝手に丸めると『測れなかった』が
   『測って N だった』に化ける。"
  [v]
  (if (and (number? v) (== v (long v)) (>= v 0)) (long v) absent-count))

(defn ^:private text-sentinel [v]
  (if (or (nil? v) (str/blank? (str v))) "" (str v)))

;; --- kernel と 1 対 1 に対応する関数群 --------------------------------------
;; 名前も引数の順序も `fabric_actor_core.kotoba` と同じにしてある。gate は
;; 名前で突き合わせるので、片方だけ改名すると落ちる。

(defn handle [product] (str product "-organism.aozora.app"))

(defn seed-service [h] (str "aozora.app/actor-seed/" h))

(defn display-name [product] (str product " organism"))

(defn description [product]
  (str "Autonomous " product
       " actor. Kotoba-policy governed, capability-bound and auditable."))

(defn registration-text [product]
  (str product
       " organism registered. Kotoba → Kototama WASM → Murakumo"
       " → Kotobase evidence. Autonomous activity remains bounded and auditable."))

(defn text-or-unknown [s] (if (= "" s) "unknown" s))

(defn count-text [n] (if (neg? n) "unknown" (str n)))

(defn runtime-text [healthy total]
  (str (count-text healthy) "/" (count-text total)))

(defn pulse-text [product status runtime ci dirty]
  (str product " organism weekly pulse — fabric=" (text-or-unknown status)
       ", runtime=" runtime
       ", CI=" (text-or-unknown ci)
       ", open checkout WIP=" (count-text dirty)
       ". Evidence: https://murakumo.cloud/health/fabric.json"))

(defn exit-code
  "0 = 全件成功 / 1 = 1 件以上失敗 / **2 = 1 件も試していない**。

   0 件処理を 0（成功）と綴らないのは ADR-2608136000 の evidence floor を
   終了状態に当てたもの: product 一覧が壊れて空になった日に緑が出る。"
  [attempted failed]
  (cond (< attempted 1) 2
        (pos? failed) 1
        :else 0))

;; --- host 側の橋渡し（kernel には無い。JSON の形を知っているのは host） -----

(defn pulse-for
  "murakumo `/health/fabric.json` の 1 レスポンスから、その product の pulse 文を作る。
   欄が欠けていても綴りが崩れないよう、番兵に落としてから kernel の規則に通す。"
  [product health]
  (let [activity (:activity health)
        summary  (:summary health)]
    (pulse-text product
                (text-sentinel (:status health))
                (runtime-text (count-sentinel (get-in activity [:runtime :healthy]))
                              (count-sentinel (get-in activity [:runtime :total])))
                (text-sentinel (get-in activity [:ci :status]))
                (count-sentinel (:dirty summary)))))
