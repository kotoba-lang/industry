#!/usr/bin/env nbb
;; adr-body.cljs — ADR の :adr/body を読む/書く唯一の安全な口。
;;
;;   nbb --classpath ".:scripts/nbb_compat" scripts/adr-body.cljs get    <adr.edn>
;;   nbb --classpath ".:scripts/nbb_compat" scripts/adr-body.cljs append <adr.edn> <text-file>
;;   nbb --classpath ".:scripts/nbb_compat" scripts/adr-body.cljs set    <adr.edn> <text-file>
;;
;; ## なぜ要るか
;;
;; 2026-09-01、ADR-2608311750 の本文が 1061 行から 39 行に潰れた。潰したのは
;; **手書きの EDN unescaper** で、`\X -> X` を全部の X に適用したため `\n` が
;; 文字 n になった。EDN としては妥当なままなので parse 検査は緑を返し、
;; 破損は 1 日誰にも見えなかった。
;;
;; 検出器（`verify-flattened-edn-prose.cljs`）は**後から**見つける。これは
;; **書く前に**なくす —— この口を通れば、worker は escape を一度も書かない。
;; reader が読み、`pr-str` が書き、書いた直後に読み直して照合する。
;;
;; ## 書いた後に必ず読み直す
;;
;; 「書けた」と「書いたものが入っている」は別の主張である。この口は
;; 書き戻した直後にファイルを読み直し、本文が意図した文字列と**完全一致**
;; することを確かめてから 0 で終わる。一致しなければ元のバイトに戻して 1。
;;
;; ## exit code
;;
;;   0  読めた / 書けて、読み直しで一致した
;;   1  書いたが読み直しで一致しなかった（元に戻した）
;;   2  **答えられなかった** —— 引数不足、entity が 1 つでない、:adr/body が無い

(ns adr-body
  (:require [clojure.edn :as edn]
            ["fs" :as fs]))

(defn- die! [code msg] (.error js/console msg) (js/process.exit code))

(defn- read-entity [file]
  (let [v (try (edn/read-string (.readFileSync fs file "utf8"))
               (catch :default e (die! 2 (str "EDN として読めない: " file " — " (.-message e)))))]
    (when-not (and (vector? v) (= 1 (count v)) (map? (first v)))
      (die! 2 (str "1 entity の vector ではない: " file)))
    (when-not (string? (:adr/body (first v)))
      (die! 2 (str ":adr/body が文字列として無い: " file)))
    (first v)))

(defn- write-body! [file entity body]
  (let [before (.readFileSync fs file "utf8")]
    (.writeFileSync fs file (str (pr-str [(assoc entity :adr/body body)]) "\n"))
    ;; 書いた直後に読み直して照合する。ここが要点で、これが無ければこの口も
    ;; 「書けたつもり」を報告できてしまう。
    (let [back (:adr/body (read-entity file))]
      (if (= back body)
        (do (.error js/console
                    (str "OK — " (count body) " 字 / "
                         (count (re-seq #"\n" body)) " 改行。読み直して一致。"))
            (js/process.exit 0))
        (do (.writeFileSync fs file before)
            (die! 1 (str "書き戻しが一致しない（元のバイトに復元した）: "
                         "意図 " (count body) " 字 / " (count (re-seq #"\n" body)) " 改行、"
                         "実際 " (count back) " 字 / " (count (re-seq #"\n" back)) " 改行")))))))

(defn -main [& args]
  (let [[op file text-file] args]
    (case op
      ;; process.exit の直後は stdout の書き込みが**切れる** —— pipe 相手だと
      ;; バッファが flush される前にプロセスが落ちる。実測 2026-09-01: 51,390 字の
      ;; 本文が 44,394 字で切れ、切れたことは出力からは分からなかった。
      ;; ここでは exit を呼ばず、自然終了に任せる（既定の終了コードは 0）。
      "get" (do (when-not file (die! 2 "usage: get <adr.edn>"))
                (.write js/process.stdout (:adr/body (read-entity file))))
      "append" (do (when-not (and file text-file) (die! 2 "usage: append <adr.edn> <text-file>"))
                   (let [e (read-entity file)
                         add (.readFileSync fs text-file "utf8")]
                     (write-body! file e (str (:adr/body e) add))))
      "set" (do (when-not (and file text-file) (die! 2 "usage: set <adr.edn> <text-file>"))
                (let [e (read-entity file)]
                  (write-body! file e (.readFileSync fs text-file "utf8"))))
      (die! 2 "usage: adr-body.cljs get|append|set <adr.edn> [<text-file>]"))))

(apply -main *command-line-args*)
