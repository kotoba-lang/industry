;; hayari の無人ループが生きているか。
;;
;; なぜこの gate が要るか: **死んだループは自分の死を報告できない。** tick は
;; 2026-08-10 から自分の健全性を commit 済み要約に書いているが、それは
;; 「動いているが失敗している」しか報告できない —— 起動しなくなれば
;; `:hayari.tick/last-run-at` はそのまま残り、他の数字も全部そのまま健全に見える。
;; 古い心拍を「古い」と判定する主体が別に要る。それがこの gate である。
;;
;; 設置直後に実際に起きた: `west update` が失敗し、pin だけ進んで checkout が
;; 進まず、query 面が 4 日遅れたまま座っていた（12 可視 / 16 保持）。痕跡は
;; /tmp のログ 1 行だけで、誰も読んでいなかった。
;;
;; ネットワーク: 不要（shipped tree 内の .edn を読むだけ）。したがって
;; **ノードに credential を置かない不変条件 3 を保ったまま**警報が置ける
;; —— hayari は public repo なので、この gate は認証を一切要さない。
;;
;; ノード側で `npx nbb hayari-tick-alive.cljs <dir> [--max-age-hours N]
;; [--max-failures N]` として実行。

(ns fleet-ci.gates.hayari-tick-alive
  (:require ["node:fs" :as fs]
            ["node:path" :as path]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(def args (vec *command-line-args*))

(defn- flag [name default]
  (let [i (.indexOf args name)]
    (if (neg? i) default (nth args (inc i)))))

(def root (or (first (remove #(str/starts-with? % "--")
                             (remove #(re-matches #"^\d+$" %) args)))
              "."))
(def max-age-hours (js/parseInt (flag "--max-age-hours" "36") 10))
(def max-failures  (js/parseInt (flag "--max-failures" "1") 10))

(defn- fail! [& msg]
  (println (str "FAIL hayari-tick-alive: " (str/join " " msg)))
  (js/process.exit 1))

(def summary-file (path/join root "data" "hayari-summary.edn"))

(when-not (fs/existsSync summary-file)
  ;; 「健全」ではなく「観測できていない」。ファイルが無いことを pass にすると、
  ;; この gate 自身が沈黙する種類の故障を持つことになる。
  (fail! "data/hayari-summary.edn が無い —"
         "ループが健全なのではなく、健全性を観測できていない。root=" root))

(def entities
  (try (edn/read-string (fs/readFileSync summary-file "utf8"))
       (catch :default e
         (fail! "要約が読めない:" (str e)))))

(def health (first (filter :hayari.tick/last-run-at entities)))

(when-not health
  (fail! "health entity が無い — tick は健全性を書いていない"
         "(bin/tick.cljs が古いか、要約が別経路で生成された)"))

;; tick は `(subs (.toISOString (js/Date.)) 0 19)` を書くので UTC だが 'Z' が無い。
;; JS の Date はサフィックス無しの `YYYY-MM-DDTHH:MM:SS` を **ローカル時刻**として
;; 解釈するので、そのまま渡すと JST 環境で 9 時間ぶんずれて「未来の心拍」になる。
;; 明示的に Z を付ける。
(def last-run-ms
  (let [s (:hayari.tick/last-run-at health)
        s (if (str/ends-with? s "Z") s (str s "Z"))
        t (.getTime (js/Date. s))]
    (if (js/isNaN t) (fail! "last-run-at が解釈できない:" (pr-str s)) t)))

(def age-hours (/ (- (js/Date.now) last-run-ms) 3600000.0))
(def failures (or (:hayari.tick/consecutive-failures health) 0))
(def days-held (or (:hayari.tick/days-held health) 0))
(def outcome (:hayari.tick/last-outcome health))

(println (str "hayari-tick-alive: last-run " (:hayari.tick/last-run-at health) "Z"
              " (" (.toFixed age-hours 1) "h 前)"
              " · outcome " outcome
              " · consecutive-failures " failures
              " · days-held " days-held))

(when (> age-hours max-age-hours)
  ;; :last-run-at はニュースのある run でのみ進む。backfill 中は毎 tick、完了後は
  ;; 日次。したがって閾値を超えるのは「暇だった」ではなく「止まった」である。
  (fail! (str "心拍が " (.toFixed age-hours 1) "h 前 — 上限 " max-age-hours "h。")
         "ループが止まっている(暇だったのではない:"
         "last-run-at はニュースのある run でのみ進む)"))

(when (> failures max-failures)
  (fail! (str "連続失敗 " failures " 回 — 上限 " max-failures "。")
         "1 回は一過性として通すが、続くのは通さない。last-outcome=" outcome))

(println (str "OK hayari-tick-alive: 生きている"
              (when (pos? failures)
                (str " (直近 1 回失敗しているが一過性として通す: " outcome ")"))
              (when-let [r (:hayari.tick/recovered-from-failures health)]
                (str " (直前に " r " 回連続で失敗し回復済み)"))))
