#!/usr/bin/env nbb
;; ledger-land.cljs — append-only ledger の 1 行を origin/main へ着地させる。
;;
;;   nbb scripts/ledger-land.cljs <repo-slug> <ledger-path> <line-file> [--dry-run]
;;
;; ## なぜ要るか
;;
;; 常駐 loop（kaiyu-kaizen ほか）は判断の記録を working tree の台帳に **append する
;; だけ**で、commit しない。共有 checkout に書いて誰かが拾うのを待つ設計になっている
;; ので、拾われなければ 1 回の `git checkout` で消える。
;;
;; 実測 2026-08-19: kaiyu-kaizen が 08-18 に書いた `:status :woke` 2 行
;; （kotobase.net 17:27Z / itonami.cloud 23:34Z）は、main 同期のために stash へ
;; 退避されたまま 1 日以上どのブランチにも remote にも存在しなかった。issue-id で
;; 突き合わせると main の台帳には同じ window の woke が babiniku.net の分しか無く、
;; **計測結果が 2 件失われかけていた**。cleanup で拾って PR #2375 として着地させたが、
;; 拾ったのは人間（の依頼で動いた agent）であって loop ではない。
;;
;; ## 経路
;;
;; ローカル checkout を経由しない。GitHub API で origin/main の blob を読み、行を
;; 足し、blob → tree → commit → ref を作る。CLAUDE.md が manifest 更新に定めている
;; server-side single commit と同じ形で、理由も同じ:
;;
;;   - 共有 checkout が古い branch に居ても、その状態に影響されない
;;   - 台帳は追記のみなので、他者の追記と競合しても「両方載せる」が常に正しい
;;   - force-push も rebase も履歴書き換えも要らない
;;
;; ## 競合
;;
;; loop は複数 profile が同時に走る。ref 更新は base commit を指定した CAS なので、
;; 競合したら **main を読み直して自分の行を足し直す**（マージではなく再適用）。
;; 追記専用なのでこれで収束する。上限 5 回、それでも駄目なら **黙って諦めない** —
;; exit 3（0 でも 1 でもない = 「着地できなかった」）で終わり、行は working tree に
;; 残るので次の周が拾える。
;;
;; ## 冪等性
;;
;; 同じ行が既に main に在れば何もしない。loop が再実行されても二重に積まない。

(require '[clojure.string :as str])
(def cp (js/require "node:child_process"))
(def fs (js/require "node:fs"))

(def argv (vec *command-line-args*))
(def dry-run? (boolean (some #{"--dry-run"} argv)))
(def pos (vec (remove #(str/starts-with? % "--") argv)))
(def slug (nth pos 0 nil))
(def ledger-path (nth pos 1 nil))
(def line-file (nth pos 2 nil))

(defn- gh [& args]
  (let [r (.spawnSync cp "gh" (clj->js (vec args))
                      #js {:encoding "utf8" :timeout 60000
                           :stdio #js ["ignore" "pipe" "pipe"]})]
    {:status (or (.-status r) 1)
     :out (str/trim (str (or (.-stdout r) "")))
     :err (str (or (.-stderr r) ""))}))

(defn- gh-out [& args]
  (let [{:keys [status out err]} (apply gh args)]
    (when-not (zero? status)
      (throw (js/Error. (str "gh failed: " (str/join " " args) " :: " (subs err 0 (min 200 (count err)))))))
    out))

(defn- b64-decode [s] (.toString (.from js/Buffer s "base64") "utf8"))
(defn- b64-encode [s] (.toString (.from js/Buffer s "utf8") "base64"))

(defn- land-once
  "1 回試す。-> :landed / :already / :conflict"
  [line]
  (let [base (gh-out "api" (str "repos/" slug "/git/ref/heads/main") "--jq" ".object.sha")
        cur  (-> (gh-out "api" (str "repos/" slug "/contents/" ledger-path "?ref=" base) "--jq" ".content")
                 (str/replace #"\s" "")
                 b64-decode)]
    (if (str/includes? cur line)
      :already
      (let [next-txt (str (str/replace cur #"\n+$" "") "\n" line "\n")
            blob (gh-out "api" (str "repos/" slug "/git/blobs")
                         "-f" (str "content=" (b64-encode next-txt))
                         "-f" "encoding=base64" "--jq" ".sha")
            tree (gh-out "api" (str "repos/" slug "/git/trees")
                         "-f" (str "base_tree=" base)
                         "-f" (str "tree[][path]=" ledger-path)
                         "-f" "tree[][mode]=100644" "-f" "tree[][type]=blob"
                         "-f" (str "tree[][sha]=" blob) "--jq" ".sha")
            commit (gh-out "api" (str "repos/" slug "/git/commits")
                           "-f" (str "message=kaizen: land one ledger line from the loop that wrote it")
                           "-f" (str "tree=" tree)
                           "-f" (str "parents[]=" base) "--jq" ".sha")
            ;; CAS: base を指定した ref 更新。競合したら失敗する（force はしない）。
            upd (gh "api" "-X" "PATCH" (str "repos/" slug "/git/refs/heads/main")
                    "-f" (str "sha=" commit) "-F" "force=false")]
        (if (zero? (:status upd)) :landed :conflict)))))

(defn- main []
  (when (or (nil? slug) (nil? ledger-path) (nil? line-file))
    (println "usage: ledger-land.cljs <repo-slug> <ledger-path> <line-file> [--dry-run]")
    (js/process.exit 2))
  (let [line (str/trim (.readFileSync fs line-file "utf8"))]
    (when (str/blank? line)
      (println "REFUSING: line file is empty — nothing to land")
      (js/process.exit 2))
    (if dry-run?
      (do (println "would land 1 line into" (str slug ":" ledger-path))
          (js/process.exit 0))
      (loop [attempt 1]
        (let [r (try (land-once line)
                     (catch :default e (println "attempt" attempt "error:" (.-message e)) :conflict))]
          (case r
            :already (do (println "ALREADY" ledger-path "— line is on main; nothing to do") (js/process.exit 0))
            :landed  (do (println "LANDED" ledger-path "attempt" attempt) (js/process.exit 0))
            (if (< attempt 5)
              (recur (inc attempt))
              ;; 「着地できなかった」を成功と区別する。0 でも 1 でもない。
              (do (println "UNLANDED after 5 attempts — the line stays in the working tree for the next tick")
                  (js/process.exit 3)))))))))

(main)
