#!/usr/bin/env nbb
;; agents-md-generated-check.cljs — AGENTS.md が CLAUDE.md からの生成物であり続けて
;; いることを fleet 側で保つ gate。ADR-2609062600。
;;
;; **検証ロジックはここに複製しない。** 正本は `scripts/gen-agents-md.cljs --check`
;; （github-workflows-absent-check.cljs と同じ形）。fleet 側と手回し側で別実装を
;; 持つと、片方だけ通る状態が黙って生まれる。
;;
;; ## なぜ tree の gate なのか
;;
;; 2 つの agent 指示ファイルは 2026-09-06 まで手で二重管理されており、30 日で
;; 片側 52 commit / 60 日で逆側 9 commit が相手に渡っていなかった。結果 AGENTS.md
;; は ADR-2809040800 が実測で反転させた規則を repo-wide mandatory の見出しとして
;; 保持し続け、逆に AGENTS.md だけが持つ 4 規則（うち 2 つは fleet gate で強制
;; されている）が CLAUDE.md に無かった。
;;
;; **乖離は音を立てない** —— どちらのファイルも単体では完全に読め、矛盾は
;; 2 つを並べて初めて見える。だから prose の規律ではなく tree の gate にする。
;;
;; ## exit 0 を信用しない
;;
;; 正本が三値で答える —— 0 一致 / 1 STALE（再生成が要る）/ 2 **答えられなかった**
;; （置換表が source と食い違った、tree が届いていない）。2 をそのまま伝播する。

(require '["node:child_process" :as cp]
         '["node:fs" :as fs])

(def generator "scripts/gen-agents-md.cljs")
(def required ["CLAUDE.md" "AGENTS.md" "scripts/gen-agents-md.cljs"])

(let [argv (vec *command-line-args*)
      dir (or (first (remove #(.startsWith % "--") argv)) ".")
      missing (remove #(fs/existsSync (str dir "/" %)) required)]
  (if (seq missing)
    (do (println "SCANNED\t0")
        (println (str "Refusing to report a verdict: " (clojure.string/join ", " missing)
                      " not in the shipped tree. Check the gate's :include-ext — a gate"
                      " whose inputs were filtered out would otherwise pass."))
        (js/process.exit 2))
    (let [r (cp/spawnSync "nbb" (clj->js [generator "--check"])
                          #js {:cwd dir :encoding "utf8"})]
      (println (str "SCANNED\t" (count required)))
      (print (or (.-stdout r) ""))
      (print (or (.-stderr r) ""))
      (js/process.exit (or (.-status r) 2)))))
