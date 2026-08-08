#!/usr/bin/env nbb
;; itonami-maturity-freshness-check.cljs — 成熟度向上 loop の鮮度判定の gate。
;;
;; **検証ロジックはここに複製しない。** 正本は repo 内の
;; `scripts/itonami-maturity-freshness-test.cljs` で、この gate は展開済み tree に
;; 対してそれを呼ぶだけ（west-pin-policy-check.cljs と同じ形）。fleet 側と手回し側で
;; 別実装を持つと、片方だけ通る状態が黙って生まれる。
;;
;; ## なぜこれが要るか
;;
;; 判定を壊しても **loop は普通に動き続ける**。tick は毎周 exit 0 で順位を出し、
;; ranked も lane も正しく見える —— 壊れるのは「その順位が今の tree を見ているか」
;; だけで、それは出力を読んでも分からない。実測 2026-08-09: 鮮度が日単位だったため
;; 78 分前に着地した仕事が `0 日 = 新鮮` と読まれ、tick は既に README も quickstart も
;; ADR も在る repo を「axis-docs 0bp」と名指しし、**もう一度同じ軸を上げろ**と言った。
;; 直近 3 周が同じ穴に落ちており、そのたびに人が手で気付いて別の repo を選び直していた。
;;
;; つまりこれは「気づかれないまま死ぬ」種類の検査なので、tip ごとに回す価値がある。
;;
;; ## exit 0 を信用しない
;;
;; policy test が classpath 破壊等で「何も検証していないのに exit 0」になる経路が
;; あるので、summary 行（`N cases OK`）の存在と N>0 を assert する。
;;
;; ノード側で `npx nbb itonami-maturity-freshness-check.cljs <dir>` として実行。
(ns fleet-ci.gates.itonami-maturity-freshness-check
  (:require ["node:child_process" :as cp]
            ["node:fs" :as fs]
            ["node:path" :as path]
            [clojure.string :as str]))

(def args (vec *command-line-args*))
(def root (or (first (remove #(str/starts-with? % "--") args)) "."))

(defn- die! [code & msg]
  (println (str "FLEET-CI: " (str/join " " msg)))
  (js/process.exit code))

(def policy-test (path/join root "scripts" "itonami-maturity-freshness-test.cljs"))
(def policy-ns (path/join root "scripts" "itonami_maturity_freshness.cljs"))
(def compat-dir (path/join root "scripts" "nbb_compat"))

;; 展開失敗による false-pass を構造的に防ぐ（不変条件 2）。90 は
;; 「tree が期待どおり届いていない」を表す tick.cljs 側の慣習。
(when-not (fs/existsSync policy-test)
  (die! 90 "scripts/itonami-maturity-freshness-test.cljs missing after extract"))
(when-not (fs/existsSync policy-ns)
  (die! 90 "scripts/itonami_maturity_freshness.cljs missing after extract"))
(when-not (fs/existsSync compat-dir)
  (die! 90 "scripts/nbb_compat missing after extract — classpath would be broken"))

(def result
  (try
    (cp/execSync "npx --yes nbb --classpath \".:scripts/nbb_compat\" scripts/itonami-maturity-freshness-test.cljs"
                 #js {:cwd root :encoding "utf8" :stdio "pipe" :timeout 600000})
    (catch :default e
      ;; 落ちても出力は見せる（原因を receipt に残す）
      (let [out (str (or (.-stdout e) "") (or (.-stderr e) ""))]
        (println (str/trim out))
        (die! 1 "itonami-maturity-freshness-test failed")))))

(println (str/trim (str result)))

(let [summary (re-find #"(\d+)\s+cases?\s+OK" (str result))]
  (cond
    (nil? summary)
    (die! 93 "no 'N cases OK' summary in output — refusing to report a pass")

    (zero? (js/parseInt (second summary) 10))
    (die! 94 "zero policy cases ran")

    :else
    (println (str "FLEET-CI: itonami-maturity freshness OK ("
                  (second summary) " cases)"))))
