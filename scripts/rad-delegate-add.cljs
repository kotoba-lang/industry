#!/usr/bin/env nbb
;; rad-delegate-add.cljs — 既存の Radicle repo に **別端末の identity を delegate として追加**する
;; （端末ごとに identity を持ち、repo の delegates に両方を入れて threshold は 1 のまま）。
;;
;; 背景（ADR-2607259000 / ledger seq 91-92）: 3,621 repo は端末 A の identity
;; `did:key:z6MkpPKisDoVCDsunNZTtX1eEErH8tcdeNpXrVVfDRkhsWEk` (alias junkawasaki) で
;; 登録されている。Radicle は端末間で秘密鍵を共有しない設計なので、別端末から
;; canonical branch を更新するには **その端末の DID を delegate に追加**するのが正しい。
;; 秘密鍵を 1Password 等でコピーする方式は「同一 DID の複製」になり、端末紛失時に
;; その端末だけ失効させられないので採らない。
;;
;; ## 実行できる場所
;; **現在 delegate になっている鍵を持つ端末で実行する**（identity 更新は既存 delegate の
;; 署名が必要）。この repo の中からでなくてよい: `rad id --repo <RID> update` は storage を
;; 直接操作するので **working copy 不要**（rad 1.9.1 で確認済み）。
;;
;; ## 使い方
;;   # 端末 A（現 delegate）で: 端末 B の DID を全 repo に追加
;;   nbb scripts/rad-delegate-add.cljs --did did:key:z6Mk...B
;;   # 対象を絞る / 件数を刻む / 下見
;;   nbb scripts/rad-delegate-add.cljs --did <DID> --limit 50
;;   nbb scripts/rad-delegate-add.cljs --did <DID> --rids rad:zAAA,rad:zBBB
;;   nbb scripts/rad-delegate-add.cljs --did <DID> --dry-run
;;   # 別ホスト（ssh 到達可能で、そのホストが delegate 鍵を持つ場合）
;;   nbb scripts/rad-delegate-add.cljs --did <DID> --host 25mbair
;;
;; 冪等: 既に delegate に入っている repo は skip する。
;; 追加後に delegates を読み直して**実際に入ったことを確認**してから成功と数える。
(ns rad-delegate-add
  (:require ["node:child_process" :as cp]
            ["node:fs" :as fs]
            [clojure.string :as str]))

(def opts
  (loop [o {} [a & more] (vec *command-line-args*)]
    (cond (nil? a) o
          (str/starts-with? a "--")
          (let [k (keyword (subs a 2))]
            (if (or (nil? (first more)) (str/starts-with? (first more) "--"))
              (recur (assoc o k true) more)
              (recur (assoc o k (first more)) (rest more))))
          :else (recur o more))))

(defn log [& xs] (println (str (.toISOString (js/Date.)) " " (str/join " " (map str xs)))))

(defn sh [cmd args & [o]]
  (try {:exit 0 :out (str (cp/execFileSync cmd (clj->js (vec args))
                                          (clj->js (merge {:encoding "utf8" :maxBuffer 268435456
                                                           :timeout 1800000} o))))}
       (catch :default e {:exit (or (.-status e) 1)
                          :out (str (some-> (.-stdout e) str) (some-> (.-stderr e) str) e)})))

(defn run-bash
  "bash script を stdin 経由で実行する（--host があれば ssh 越し、--as-user があれば
  そのユーザーで）。secret を argv に載せないため常に stdin 経由。
  identity 更新は **鍵を持つユーザー自身**で走らせる必要がある（例: seed gad は
  root で ssh するが鍵は gad ユーザーのもの → --as-user gad）。"
  [script]
  (let [inner (if (:as-user opts)
                ["sudo" "-u" (:as-user opts) "-H" "bash" "-s"]
                ["bash" "-s"])]
    (if (:host opts)
      (sh "ssh" (concat ["-o" "BatchMode=yes" "-o" "ConnectTimeout=15" (:host opts)] inner)
          {:input script})
      (if (:as-user opts)
        (sh (first inner) (rest inner) {:input script})
        (sh "bash" ["-s"] {:input script})))))

;; rad が passphrase を要求する環境でも動くように、走っている radicle-node の環境から
;; RAD_PASSPHRASE を取り出す（macOS は ps eww、Linux は /proc/<pid>/environ。どちらも
;; **自分が所有するプロセス**しか読めないので、同一ユーザーで実行する前提）。
(def passphrase-preamble
  (str/join
   "\n"
   ["if [ -z \"${RAD_PASSPHRASE:-}\" ]; then"
    "  npid=$(pgrep -u \"$(id -u)\" -f 'radicle-node' | head -1)"
    "  if [ -n \"$npid\" ]; then"
    "    if [ -r /proc/$npid/environ ]; then"
    "      P=$(tr '\\0' '\\n' < /proc/$npid/environ | sed -n 's/^RAD_PASSPHRASE=//p')"
    "    else"
    "      P=$(ps eww -p $npid 2>/dev/null | tr ' ' '\\n' | sed -n 's/^RAD_PASSPHRASE=//p' | head -1)"
    "    fi"
    "    [ -n \"${P:-}\" ] && export RAD_PASSPHRASE=\"$P\""
    "  fi"
    "fi"]))

(defn rad-bin [] (or (:rad opts) "rad"))

(defn list-rids []
  (if (:rids opts)
    (->> (str/split (str (:rids opts)) #",") (map str/trim) (remove str/blank?) vec)
    (let [{:keys [exit out]} (run-bash (str "export PATH=$HOME/.radicle/bin:/opt/homebrew/bin:$PATH\n"
                                            (rad-bin) " ls --all 2>/dev/null | grep -o 'rad:z[A-Za-z0-9]*'"))]
      (when-not (zero? exit) (log "could not list RIDs:" (str/trim out)) (js/process.exit 1))
      (vec (distinct (re-seq #"rad:z[A-Za-z0-9]+" (str out)))))))

(defn batch-script
  "1 回の bash 実行で複数 RID を処理する（RID ごとに ssh を張らない）。
  各 RID につき 1 行 `RESULT <rid> <skip|added|failed|verify-failed>` を出す。"
  [rids did]
  (str/join
   "\n"
   (concat
    ["set -u"
     "export PATH=$HOME/.radicle/bin:/opt/homebrew/bin:$PATH"
     passphrase-preamble
     (str "DID=" (pr-str did))
     ;; RID は配列に入れて回す。while-read + heredoc にすると rad が stdin を食う。
     "rids=("]
    [(str/join "\n" (map #(str "  " %) rids))]
    [")"
     "for rid in \"${rids[@]}\"; do"
     "  cur=$(rad inspect \"$rid\" --delegates 2>/dev/null || true)"
     "  case \"$cur\" in"
     "    *\"$DID\"*) echo \"RESULT $rid skip\"; continue ;;"
     "  esac"
     (str "  out=$(rad id --repo \"$rid\" update --title "
          (pr-str "Add second device as delegate")
          " --description " (pr-str "per-device identity; threshold unchanged")
          " --delegate \"$DID\" --no-confirm -q 2>&1) || { echo \"RESULT $rid failed ${out##*$'\\n'}\"; continue; }")
     "  now=$(rad inspect \"$rid\" --delegates 2>/dev/null || true)"
     "  case \"$now\" in"
     "    *\"$DID\"*) echo \"RESULT $rid added\" ;;"
     "    *) echo \"RESULT $rid verify-failed\" ;;"
     "  esac"
     "done"])))

(let [did (or (:did opts)
              (do (log "usage: --did did:key:... [--rids a,b] [--limit N] [--host H] [--dry-run]")
                  (js/process.exit 2)))
      all (list-rids)
      rids (if (:limit opts) (vec (take (js/parseInt (:limit opts)) all)) all)]
  (log "delegate to add:" did)
  (log "target repos:" (count rids) (if (:host opts) (str "on " (:host opts)) "locally"))
  (if (:dry-run opts)
    (do (doseq [r (take 10 rids)] (log "  would update" r))
        (when (> (count rids) 10) (log "  … and" (- (count rids) 10) "more")))
    ;; 200 件ずつに割る（1 回の script が巨大になりすぎないように）
    (let [chunks (partition-all 200 rids)
          tally (atom {})]
      (doseq [[i chunk] (map-indexed vector chunks)]
        (let [{:keys [out]} (run-bash (batch-script (vec chunk) did))
              lines (re-seq #"RESULT (rad:z[A-Za-z0-9]+) (\S+)(.*)" (str out))]
          (doseq [[_ rid status detail] lines]
            (swap! tally update (keyword status) (fnil inc 0))
            (when (contains? #{"failed" "verify-failed"} status)
              (log "  " status rid (str/trim (str detail)))))
          (log "chunk" (inc i) "/" (count chunks) "->" (pr-str @tally))))
      (log "done:" (pr-str @tally))
      (fs/writeFileSync "/tmp/rad-delegate-add-result.edn" (pr-str {:did did :tally @tally})))))
