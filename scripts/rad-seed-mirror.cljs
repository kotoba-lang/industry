#!/usr/bin/env nbb
;; rad-seed-mirror.cljs — 常時稼働の seed に repo を複製（seed + fetch）して可用性を確保する。
;;
;; 背景（ADR-2607259000 / ledger seq 91-99）: 3,622 repo は端末 A（25mbair、alias
;; junkawasaki）に登録されている。**その端末しか実体を持っていないと、スリープ中は
;; ネットワークから引けない**（実測: 無作為 9 件のうち 3 件が 150s でも fetch 不能。
;; 端末が起きたあと再確認したら取得できた）。delegate を増やしても取得性は改善しないので、
;; 常時稼働 seed（gad）に複製を置く。RID は変わらず、二重登録も起こらない。
;;
;; 使い方:
;;   nbb scripts/rad-seed-mirror.cljs --host gad --as-user gad \
;;     --peer z6MkpPKisDoVCDsunNZTtX1eEErH8tcdeNpXrVVfDRkhsWEk@100.86.235.122:8776 \
;;     --limit 20                        # パイロット
;;   （--limit なしで manifest の全 RID。--rids a,b で個別指定。--dry-run で下見）
;;
;; RID の出所は **manifest/repos.edn の :manifest.repos/rad-rids（正本）**。
;; storage 不在を根拠に何かを消すことは絶対にしない（seq 91 の教訓）— この script は
;; 追加専用（seed + fetch）で、削除は一切行わない。
(ns rad-seed-mirror
  (:require ["node:child_process" :as cp]
            [cljs.reader :as reader]
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
                                                           :timeout 3600000} o))))}
       (catch :default e {:exit (or (.-status e) 1)
                          :out (str (some-> (.-stdout e) str) (some-> (.-stderr e) str) e)})))

(defn run-bash [script]
  (let [inner (if (:as-user opts) ["sudo" "-u" (:as-user opts) "-H" "bash" "-s"] ["bash" "-s"])]
    (if (:host opts)
      (sh "ssh" (concat ["-o" "BatchMode=yes" "-o" "ConnectTimeout=15" (:host opts)] inner) {:input script})
      (sh (first inner) (rest inner) {:input script}))))

(defn gh! [& a]
  (let [{:keys [exit out]} (sh "gh" a)]
    (when-not (zero? exit) (log "gh failed:" (str/trim out)) (js/process.exit 1))
    (str/trim out)))

(defn manifest-rids []
  (let [raw (gh! "api" "-H" "Accept: application/vnd.github.raw"
                 "repos/com-junkawasaki/root/contents/manifest/repos.edn?ref=main")
        e (let [m (reader/read-string raw)] (if (vector? m) (first m) m))
        m (some-> (:manifest.repos/rad-rids e) reader/read-string)]
    (vec (sort (distinct (vals m))))))

(def storage-dir (or (:storage opts) "$HOME/.radicle/storage"))

(defn chunk-script [rids peer]
  (str/join
   "\n"
   (concat
    ["set -u"
     "export PATH=$HOME/.radicle/bin:/opt/homebrew/bin:$PATH"
     ;; ⚠ `--seed` は **NID のみ**（`nid@addr:port` は 1.9.1 で parse エラー）。
     ;; アドレス指定は事前の `rad node connect <nid>@<addr>` で行う。
     (str "PEER=" (pr-str (first (str/split (or peer "") #"@"))))
     "rids=("]
    [(str/join "\n" (map #(str "  " %) rids))]
    [")"
     "for rid in \"${rids[@]}\"; do"
     (str "  id=${rid#rad:}")
     (str "  if [ -d " storage-dir "/$id ]; then echo \"RESULT $rid have\"; continue; fi")
     "  rad seed \"$rid\" --scope all >/dev/null 2>&1 || true"
     "  if [ -n \"$PEER\" ]; then"
     "    out=$(rad sync --fetch --seed \"$PEER\" \"$rid\" 2>&1)"
     "  else"
     "    out=$(rad sync --fetch \"$rid\" 2>&1)"
     "  fi"
     (str "  if [ -d " storage-dir "/$id ]; then echo \"RESULT $rid fetched\"; "
          "else echo \"RESULT $rid miss ${out##*$'\\n'}\"; fi")
     "done"])))

(defn du []
  (let [{:keys [out]} (run-bash (str "du -sk " storage-dir " 2>/dev/null | cut -f1"))]
    (js/parseInt (str/trim (str out)) 10)))

(let [peer (:peer opts)
      all (if (:rids opts)
            (->> (str/split (str (:rids opts)) #",") (map str/trim) (remove str/blank?) vec)
            (manifest-rids))
      rids (if (:limit opts) (vec (take (js/parseInt (:limit opts)) all)) all)
      chunk-size (js/parseInt (or (:chunk opts) "50"))]
  (log "mirror target:" (count rids) "RIDs ->" (or (:host opts) "local")
       (if peer (str "via peer " (subs peer 0 20) "…") "(discovery)"))
  (if (:dry-run opts)
    (log "dry run — first 5:" (pr-str (take 5 rids)))
    (let [kb0 (du)
          t0 (js/Date.now)
          tally (atom {})]
      (log "storage before:" (js/Math.round (/ kb0 1024)) "MB")
      (doseq [[i chunk] (map-indexed vector (partition-all chunk-size rids))]
        (let [{:keys [out]} (run-bash (chunk-script (vec chunk) peer))
              lines (re-seq #"RESULT (rad:z[A-Za-z0-9]+) (\S+)(.*)" (str out))]
          (doseq [[_ rid status detail] lines]
            (swap! tally update (keyword status) (fnil inc 0))
            (when (= status "miss") (log "  miss" rid (str/trim (str detail)))))
          (log "chunk" (inc i) "->" (pr-str @tally)
               (str "(" (js/Math.round (/ (- (js/Date.now) t0) 1000)) "s, "
                    (js/Math.round (/ (du) 1024)) "MB)"))))
      (let [kb1 (du) secs (/ (- (js/Date.now) t0) 1000)]
        (log "done:" (pr-str @tally))
        (log "storage:" (js/Math.round (/ kb0 1024)) "MB ->" (js/Math.round (/ kb1 1024)) "MB"
             (str "(+" (js/Math.round (/ (- kb1 kb0) 1024)) "MB in "
                  (js/Math.round secs) "s = "
                  (if (pos? (count rids)) (.toFixed (/ secs (count rids)) 1) "?") "s/repo)"))))))
