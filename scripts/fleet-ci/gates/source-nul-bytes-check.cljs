#!/usr/bin/env nbb
;; source-nul-bytes-check.cljs — NUL バイトの gate。
;;
;; **検証ロジックはここに複製しない。** 正本は
;; `scripts/verify-source-nul-bytes.cljs` で、この gate は展開済み tree に
;; 対してそれを呼ぶだけ（adr-identity-check.cljs と同じ形）。fleet 側と
;; 手回し側で別実装を持つと、片方だけ通る状態が黙って生まれる。
;;
;; ## 何を守るか
;;
;; ソースに紛れた NUL バイト 1 個で `file(1)` はそのファイルを `data` と
;; 判定し、grep はバイナリを黙って飛ばす。`grep -c foo <file>` は出力ゼロで
;; exit 1 —— 「その語が無いファイル」と完全に同じ見え方になる。
;;
;; 実測 2026-08-18、同じ原因が 1 日に 2 回、別経路で出た（tick.cljs と
;; net-isekai-gen/cid.clj）。後者では「JVM interop 無し」という走査結果が
;; 嘘になり、同じファイルが 3 repo に複製されていた。
;;
;; ## この gate が覆う範囲と、覆わない範囲
;;
;; fleet が配るのは **その repo 自身の tree だけ**で、west 管理の `orgs/` は
;; 入らない（`git ls-files orgs/cloud-itonami` は 0 件）。したがってこの gate
;; が守るのは superproject の tree であって、子リポではない。子リポ側は
;; それぞれの repo で同じ検査を回す必要があり、**そこはまだ空いている**。
;; 覆っていない範囲をここに書いておくのは、緑を「全部見た」と読ませないため。
;;
;; ## exit 0 を信用しない
;;
;; 正本側が三値で答える —— 0 は走査して違反なし、1 は違反、2 は
;; **答えられなかった**（自己検証失敗 / 0 件走査 / ディレクトリ不在）。
;; 2 が 0 と別であることが要点で、この gate はそれをそのまま伝播させる。

(require '["node:child_process" :as cp]
         '["node:fs" :as fs])

(def verifier "scripts/verify-source-nul-bytes.cljs")

(let [dir (or (first (remove #(.startsWith % "--") *command-line-args*)) ".")
      script (str dir "/" verifier)]
  (if-not (fs/existsSync script)
    (do (println "SCANNED\t0")
        (println (str "Refusing to report a verdict: " script " is not in the"
                      " shipped tree. Check the gate's :include-ext — a gate"
                      " whose verifier was filtered out would otherwise pass."))
        (js/process.exit 2))
    (let [r (cp/spawnSync "nbb" #js [verifier dir]
                          #js {:cwd dir :encoding "utf8"})]
      (print (or (.-stdout r) ""))
      (print (or (.-stderr r) ""))
      (js/process.exit (or (.-status r) 2)))))
