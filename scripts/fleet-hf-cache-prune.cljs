#!/usr/bin/env nbb
;; scripts/fleet-hf-cache-prune.cljs — keep every fleet node's HuggingFace cache
;; inside the retention policy of ADR-2608139600: **drop what has not been read
;; in N days (default 30), then cap the cache at G GB (default 50)**.
;;
;; **なぜ script なのか。** 2026-08-13 にこの掃除を手で 1 回やって 352 GB 戻した。
;; 手でやった 1 回は policy ではない —— キャッシュは翌日から再び太る。CLAUDE.md が
;; 繰り返し書いているとおり、prose の規則は守られず、機構だけが守られる。
;;
;; **なぜ atime なのか。** mtime は *取得した日* であって使った日ではない。手作業の
;; 初回はここを間違え、「30 日以内に使用」と報告したものが実際には「30 日以内に
;; ダウンロード」だった。fleet の volume は atime を実際に更新する（実測 2026-08-13、
;; 10/10 ノードで probe 済み）ので、読み出しを直接測れる。**atime が死んでいる
;; ノードでは、この script は答えを出さずに exit 2 する**（下記）。
;;
;; 測れなかったことを「違反 0」と報告しない（ADR-2608136000 の 5 問）:
;;   - ノードごとに SCANNED を必ず出す。0 件走査の clean と、本当に clean を区別する
;;   - 測定に失敗したノードは NO-ANSWER として数え、**exit 2**（0 でも 1 でもない）
;;   - manifest は削除の *後* に書く。手作業版は前に書いていたので、rm が
;;     Permission denied で落ちた 6 件を「削除済み」と記録していた
;;
;; 実測済みの罠:
;;   - キャッシュには **root 所有のファイル**が混じる（過去に root が取得した分）。
;;     ユーザ権限の rm は Directory not empty / Permission denied で落ちるので、
;;     失敗したものだけ sudo -n で再試行する
;;   - モデルは `hub/models--*` だけでなく `~/.cache/huggingface/` 直下にも置かれる
;;     （実測: wai-real-cn-v10 が 26.5 GB）。両方を見る
;;   - remote は `sh -s` に stdin で渡す。ログインシェルが zsh のノードで
;;     `hub/models--*` が 1 件も無いと `no matches found` で **エラー終了する**
;;     （手作業版はこれで 3 ノードを取りこぼした）
;;
;; usage:
;;   nbb scripts/fleet-hf-cache-prune.cljs --check          ;; 報告のみ（既定）
;;   nbb scripts/fleet-hf-cache-prune.cljs --apply          ;; 実際に削除する
;;   nbb scripts/fleet-hf-cache-prune.cljs --days 30 --cap-gb 50
;;   nbb scripts/fleet-hf-cache-prune.cljs --hosts a,b      ;; 対象を絞る
;;
;; exit: 0 = 全ノード policy 内 / 1 = 違反あり（--check 時）/ 2 = 測れなかった

(ns fleet-hf-cache-prune
  (:require [clojure.string :as str]
            [promesa.core :as p]
            ["node:child_process" :as cp]
            ["node:fs" :as fs]
            ["node:path" :as path]))

(def argv (vec *command-line-args*))

(defn flag? [f] (some #{f} argv))
(defn opt [f d]
  (let [i (.indexOf argv f)]
    (if (neg? i) d (nth argv (inc i) d))))

(def apply?  (flag? "--apply"))
(def days    (js/parseInt (opt "--days" "30")))
(def cap-mb  (* 1024 (js/parseInt (opt "--cap-gb" "50"))))
(def jobs    (js/parseInt (opt "--jobs" "10")))

(def repo-root
  ;; the superproject root, so this runs from anywhere
  (-> (cp/execSync "git rev-parse --show-toplevel" #js {:encoding "utf8"}) str str/trim))

(defn hosts-from-nodes-edn []
  (let [f (path/join repo-root "scripts" "fleet-ci" "nodes.edn")]
    (when (fs/existsSync f)
      (->> (str (fs/readFileSync f "utf8"))
           (re-seq #":host\s+\"([^\"]+)\"")
           (map second)
           vec))))

(def hosts
  (let [explicit (opt "--hosts" nil)]
    (if explicit
      (vec (remove str/blank? (str/split explicit #",")))
      (or (hosts-from-nodes-edn) []))))

;; ---------------------------------------------------------------- remote probe
;; Emits TSV so the driver never has to guess. Every line is tagged, and the
;; ATIME / SCANNED lines exist so "no violations" cannot be confused with
;; "nothing was looked at".
(def probe-sh "
set -u
C=$HOME/.cache/huggingface
if [ ! -d \"$C\" ]; then echo 'NOCACHE'; exit 0; fi

# Does this volume record read times at all? If not, the whole policy is
# unmeasurable here and the driver must refuse to answer.
T=$C/.atime_probe.$$
echo x > \"$T\" 2>/dev/null || { echo 'ATIME\tunwritable'; exit 0; }
A1=$(stat -f %a \"$T\" 2>/dev/null)
sleep 1
cat \"$T\" > /dev/null 2>&1
A2=$(stat -f %a \"$T\" 2>/dev/null)
rm -f \"$T\"
if [ \"$A1\" = \"$A2\" ]; then echo 'ATIME\tfrozen'; else echo 'ATIME\tlive'; fi

NOW=$(date +%s)
N=0
for d in \"$C\"/hub/models--* \"$C\"/*; do
  [ -d \"$d\" ] || continue
  b=$(basename \"$d\")
  case \"$b\" in hub|xet|datasets|.locks) continue;; esac
  m=$(du -sm \"$d\" 2>/dev/null | awk '{print $1}')
  [ -n \"$m\" ] || continue
  [ \"$m\" -lt 50 ] && continue
  la=$(find \"$d\" -type f -size +10M 2>/dev/null | head -25 |
       while read -r f; do stat -f %a \"$f\" 2>/dev/null; done | sort -rn | head -1)
  [ -n \"$la\" ] || la=$(find \"$d\" -type f 2>/dev/null | head -25 |
       while read -r f; do stat -f %a \"$f\" 2>/dev/null; done | sort -rn | head -1)
  [ -n \"$la\" ] || continue
  N=$((N+1))
  printf 'ENTRY\t%s\t%s\t%s\n' \"$m\" \"$(( (NOW-la)/86400 ))\" \"$b\"
done
printf 'SCANNED\t%s\n' \"$N\"
printf 'CACHE\t%s\n' \"$(du -sm \"$C\" 2>/dev/null | awk '{print $1}')\"
printf 'FREE\t%s\n' \"$(df -g / | awk 'NR==2{print $4}')\"
")

;; ---------------------------------------------------------------- remote delete
;; Takes the exact entry names on stdin (one per line). Records the manifest only
;; AFTER the removal is confirmed gone -- a manifest written first is a lie the
;; moment rm fails, which is exactly what happened on 2026-08-13.
(def delete-sh "
set -u
C=$HOME/.cache/huggingface
MAN=$HOME/.itonami/hf-cache-pruned.tsv
mkdir -p \"$HOME/.gftd\"
while IFS='\t' read -r m age b; do
  [ -n \"${b:-}\" ] || continue
  for p in \"$C/hub/$b\" \"$C/$b\"; do
    [ -d \"$p\" ] || continue
    rm -rf \"$p\" 2>/dev/null
    # root-owned blobs survive a user-level rm; retry those only.
    [ -d \"$p\" ] && sudo -n rm -rf \"$p\" 2>/dev/null
    if [ -d \"$p\" ]; then
      printf 'STUCK\t%s\n' \"$b\"
    else
      printf '%s\t%s\t%s\t%s\n' \"$(date -u +%FT%TZ)\" \"$m\" \"$age\" \"$b\" >> \"$MAN\"
      printf 'GONE\t%s\t%s\n' \"$m\" \"$b\"
    fi
  done
done
printf 'CACHE\t%s\n' \"$(du -sm \"$C\" 2>/dev/null | awk '{print $1}')\"
printf 'FREE\t%s\n' \"$(df -g / | awk 'NR==2{print $4}')\"
")

(defn ssh-sh
  "Run `script` on `host` through `sh -s`, feeding it `input` on stdin.
   Returns {:ok? bool :out str}. Never throws -- an unreachable node is data."
  [host script input]
  (p/create
   (fn [resolve _]
     (let [child (cp/spawn "ssh"
                           #js ["-o" "BatchMode=yes" "-o" "ConnectTimeout=30" host "sh -s"]
                           #js {:stdio #js ["pipe" "pipe" "pipe"]})
           out (atom "") err (atom "")]
       (.on (.-stdout child) "data" #(swap! out str %))
       (.on (.-stderr child) "data" #(swap! err str %))
       (.on child "error" (fn [e] (resolve {:ok? false :out (str e)})))
       (.on child "close"
            (fn [code] (resolve {:ok? (zero? code) :out @out :err @err})))
       (.write (.-stdin child) (str script "\n" input))
       (.end (.-stdin child))))))

(defn parse-probe [out]
  (reduce
   (fn [acc line]
     (let [[tag a b c] (str/split line #"\t")]
       (case tag
         "NOCACHE" (assoc acc :no-cache? true)
         "ATIME"   (assoc acc :atime a)
         "SCANNED" (assoc acc :scanned (js/parseInt a))
         "CACHE"   (assoc acc :cache-mb (js/parseInt a))
         "FREE"    (assoc acc :free-gb (js/parseInt a))
         "ENTRY"   (update acc :entries conj
                           {:mb (js/parseInt a) :age (js/parseInt b) :name c})
         acc)))
   {:entries [] :scanned nil :atime nil}
   (str/split-lines (str/trim (str out)))))

(defn plan
  "Decide what to drop. Stage 1 is the age rule; stage 2 only runs if what is
   left still exceeds the cap, and then it takes the least-recently-read first."
  [{:keys [entries cache-mb]}]
  (let [stale (filterv #(> (:age %) days) entries)
        kept  (filterv #(<= (:age %) days) entries)
        ;; non-model cache (xet, datasets, partials) still counts toward the cap
        other (max 0 (- (or cache-mb 0) (reduce + 0 (map :mb entries))))
        after (+ other (reduce + 0 (map :mb kept)))
        extra (loop [remaining after
                     [e & more] (vec (sort-by :age > kept))
                     acc []]
                (if (or (nil? e) (<= remaining cap-mb))
                  acc
                  (recur (- remaining (:mb e)) more (conj acc e))))]
    {:stale stale :extra extra :drop (into stale extra)
     :after-mb (- after (reduce + 0 (map :mb extra)))}))

(defn fmt-gb [mb] (str (.toFixed (/ (or mb 0) 1024.0) 1) "GB"))

(defn run-host [host]
  (p/let [r (ssh-sh host probe-sh "")]
    (if-not (:ok? r)
      {:host host :answer? false :why (str/trim (str (:err r) (:out r)))}
      (let [m (parse-probe (:out r))]
        (cond
          (:no-cache? m)          {:host host :answer? true :none? true}
          (not= "live" (:atime m)) {:host host :answer? false
                                    :why (str "atime " (:atime m)
                                              " — read times are not recorded, policy unmeasurable")}
          (nil? (:scanned m))     {:host host :answer? false :why "probe emitted no SCANNED line"}
          :else
          (let [pl (plan m)]
            (p/let [d (if (and apply? (seq (:drop pl)))
                        (ssh-sh host delete-sh
                                (str/join "\n" (for [e (:drop pl)]
                                                 (str (:mb e) "\t" (:age e) "\t" (:name e)))))
                        {:ok? true :out ""})]
              (let [gone  (count (re-seq #"(?m)^GONE\t" (str (:out d))))
                    stuck (vec (map second (re-seq #"(?m)^STUCK\t(.+)$" (str (:out d)))))
                    post  (parse-probe (:out d))]
                (merge {:host host :answer? true :scanned (:scanned m)
                        :cache-mb (:cache-mb m) :free-gb (:free-gb m)
                        :plan pl :gone gone :stuck stuck}
                       (when apply?
                         {:cache-after (:cache-mb post) :free-after (:free-gb post)}))))))))))

(defn chunked [n coll] (partition-all n coll))

(p/let [results (p/loop [batches (chunked jobs hosts) acc []]
                  (if-let [b (first batches)]
                    (p/let [rs (p/all (map run-host b))]
                      (p/recur (rest batches) (into acc rs)))
                    acc))]
  (println (str "policy: unread>" days "d dropped, cache capped at " (fmt-gb cap-mb)
                "  mode=" (if apply? "APPLY" "check") "  hosts=" (count hosts)))
  (doseq [r (sort-by :host results)]
    (cond
      (not (:answer? r))
      (println (str "  " (:host r) "  NO-ANSWER — " (:why r)))

      (:none? r)
      (println (str "  " (:host r) "  no cache"))

      :else
      (let [{:keys [plan scanned cache-mb free-gb gone stuck cache-after free-after]} r
            n (count (:drop plan))]
        (println
         (str "  " (:host r)
              "  scanned=" scanned
              " cache=" (fmt-gb cache-mb)
              " free=" free-gb "GB"
              (if apply?
                (str "  removed=" gone "/" n
                     " -> cache=" (fmt-gb cache-after) " free=" free-after "GB"
                     (when (seq stuck) (str "  STUCK=" (str/join "," stuck))))
                (str "  would-drop=" n
                     " (" (fmt-gb (reduce + 0 (map :mb (:drop plan)))) ")"
                     " -> " (fmt-gb (:after-mb plan))))))
        (when (and (not apply?) (seq (:drop plan)))
          (doseq [e (take 5 (sort-by :mb > (:drop plan)))]
            (println (str "        " (fmt-gb (:mb e)) "  " (:age e) "d  " (:name e))))))))

  (let [unmeasured (filterv #(not (:answer? %)) results)
        violating  (filterv #(and (:answer? %) (not (:none? %))
                                  (seq (get-in % [:plan :drop])))
                            results)
        stuck      (filterv #(seq (:stuck %)) results)]
    (println (str "\nanswered=" (- (count results) (count unmeasured)) "/" (count results)
                  "  violating=" (count violating)
                  (when apply? (str "  stuck=" (count stuck)))))
    (cond
      ;; "could not measure" must never look like "measured and clean".
      (seq unmeasured)
      (do (println (str "REFUSING to report a pass: "
                        (str/join ", " (map :host unmeasured)) " did not answer"))
          (js/process.exit 2))

      (and apply? (seq stuck))
      (do (println "some entries survived removal") (js/process.exit 1))

      (and (not apply?) (seq violating))
      (js/process.exit 1)

      :else
      (js/process.exit 0))))
