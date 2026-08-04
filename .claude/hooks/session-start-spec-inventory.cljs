#!/usr/bin/env nbb
;; SessionStart フック: 「外部仕様の実装がこの workspace にあるか」を、
;; agent が推測する前に見せる。
;;
;; 背景 (2026-08-04): 1 セッションで agent が「この workspace には X が無い」と
;; 3 回結論し、3 回とも間違っていた ——
;;
;;   * semantic-code は kotoba repo から kotoba-lang/codebase へ切り出し済みだった
;;   * 「DHT announce には libp2p ノードが要るが無い」→ io-libp2p-specs-kad-dht に
;;     multi-router quorum 付き delegated routing、tech-ipfs-specs-ipns に実 IPNS
;;     record があり、実ネットワークに publish できた
;;   * 「transport が無い」→ multistream/Yamux/Noise/multiaddr/protobuf が全部あった
;;
;; 誤りの構造はいつも同じ: **4,000 repo の workspace で、agent は「見えている
;; もの」から「無い」を推論する。** checkout されていない repo は `ls` にも
;; `find` にも映らないので、手元に無いことが存在しないことに見える。そして
;; 「無い」と結論した後は、探し直すのではなく作り始めてしまう。
;;
;; prose の指示では守られなかった(CLAUDE.md には「既存を確認せよ」が何度も
;; 書いてあり、それでも 3 回起きた)。なので毎セッション、推測の前に事実を出す。
;;
;; ここで出すのは **外部仕様ミラー系の repo 名だけ**(io-/org-/tech-/dev-/
;; capability- 接頭辞、約 200 件・4 KB)。この接頭辞群は命名規則上「どの外部
;; 仕様が実装済みか」の答えそのもので、上の 3 件のうち 2 件はこの一覧を見れば
;; 即座に防げた。残り 1 件(接頭辞なしの `noise` / `codebase`)は
;; `scripts/repo-search.cljs` が拾うので、そちらを併記する。
;;
;; fail-open: 何が失敗してもセッション開始をブロックしない。

(require '[cheshire.core :as json]
         '[clojure.string :as str]
         '[scripts.nbb-compat :as compat])

(def node-fs (js/require "node:fs"))
(def node-path (js/require "node:path"))

(def prefixes #"^(io|org|tech|dev|capability)-")

(defn- top []
  (or (.-CLAUDE_PROJECT_DIR (.-env js/process)) (.cwd js/process)))

(try
  (let [manifest (.join node-path (top) "manifest" "west.yml")]
    (when-not (.existsSync node-fs manifest)
      (compat/exit 0))
    (let [names (->> (str/split-lines (.readFileSync node-fs manifest "utf8"))
                     (keep #(second (re-find #"^    - name: (\S+)$" %)))
                     distinct)
          specs (->> names (filter #(re-find prefixes %)) sort)
          total (count names)]
      (when (seq specs)
        (let [message
              (str "外部仕様ミラー repo（" (count specs) " 件 / west.yml 全 " total " 件）。\n"
                   "**「この workspace には X が無い」と結論する前に、まずここを見て、"
                   "次に `nbb scripts/repo-search.cljs <語> [語...]` を引くこと。**\n"
                   "checkout されていない repo は ls にも find にも映らない —— "
                   "手元に無いことは存在しないことではない。\n\n"
                   (str/join ", " specs))]
          (println (json/generate-string
                    {:hookSpecificOutput {:hookEventName "SessionStart"
                                          :additionalContext message}}))))))
  (catch :default _ nil))

(compat/exit 0)
