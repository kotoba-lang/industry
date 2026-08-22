#!/usr/bin/env nbb
;; github-workflows-absent-check.cljs — 「CI は murakumo、GitHub Actions は使わない」
;; を fleet 側で保つ gate。
;;
;; **検証ロジックはここに複製しない。** 正本は
;; `scripts/verify-no-github-workflows.cljs`（source-nul-bytes-check.cljs と同じ形）。
;; fleet 側と手回し側で別実装を持つと、片方だけ通る状態が黙って生まれる。
;;
;; ## なぜ tree の gate なのか
;;
;; ADR-2607300900 で Actions は repo 単位で無効化したが、**無効化は GitHub 側の
;; 設定であって tree の性質ではない**。設定は誰かが UI で戻せるし、この workspace
;; の token では読めない経路もある。一方 `.github/workflows/*.yml` が repo root に
;; 現れることは tree の性質で、credential 無しのノードでも見える —— そして
;; それが **GitHub が自分の判断で runner を起こす唯一の入口**でもある。
;;
;; 見ていない範囲（`actions/permissions` や Dependabot の設定）は verifier が
;; 出力に明記する。緑を「Actions が無効である」と読ませないため。
;;
;; ## exit 0 を信用しない
;;
;; 正本が三値で答える —— 0 走査して違反なし / 1 違反 / 2 **答えられなかった**
;; （tree が届いていない、錨 `manifest/west.yml` が無い）。2 をそのまま伝播する。

(require '["node:child_process" :as cp]
         '["node:fs" :as fs])

(def verifier "scripts/verify-no-github-workflows.cljs")

(let [argv (vec *command-line-args*)
      dir (or (first (remove #(.startsWith % "--") argv)) ".")
      passthrough (vec (drop-while #(not (.startsWith % "--")) argv))
      script (str dir "/" verifier)]
  (if-not (fs/existsSync script)
    (do (println "SCANNED\t0")
        (println (str "Refusing to report a verdict: " script " is not in the"
                      " shipped tree. Check the gate's :include-ext — a gate"
                      " whose verifier was filtered out would otherwise pass."))
        (js/process.exit 2))
    (let [r (cp/spawnSync "nbb" (clj->js (into [verifier dir] passthrough))
                          #js {:cwd dir :encoding "utf8"})]
      (print (or (.-stdout r) ""))
      (print (or (.-stderr r) ""))
      (js/process.exit (or (.-status r) 2)))))
