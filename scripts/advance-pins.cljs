;; manifest/west.yml の pin を、指定した repo 群の現在の default branch HEAD へ
;; 前進させる。**当該 entry の revision 行だけ**を書き換える(wholesale 再生成は
;; しない — CLAUDE.md の pin 前進規約)。書き換え後は必ず
;; scripts/verify-west-pins.cljs を通すこと(この script は検証をしない)。
;;
;; usage:
;;   nbb scripts/advance-pins.cljs <org> <list-file> [--execute]
;; --execute が無ければ dry-run(何件動くかだけ表示)。
(require '[clojure.string :as str]
         '["fs" :as fs]
         '["child_process" :as cp])

(defn- head-sha [org repo]
  (try
    (-> (cp/execSync (str "gh api repos/" org "/" repo "/commits/HEAD --jq .sha")
                     #js {:encoding "utf8" :stdio #js ["pipe" "pipe" "pipe"]})
        str/trim not-empty)
    (catch :default _ nil)))

(let [[org list-file & flags] *command-line-args*
      execute? (some #{"--execute"} flags)
      repos (->> (fs/readFileSync list-file "utf8") str/split-lines
                 (map str/trim) (remove str/blank?) set)
      path "manifest/west.yml"
      lines (str/split-lines (fs/readFileSync path "utf8"))
      ;; name 行を見つけ、その直後数行にある revision 行だけを差し替える
      out (atom (vec lines))
      tally (atom {:advanced 0 :already 0 :no-sha 0 :not-in-manifest 0})
      seen (atom #{})]
  (doseq [[i l] (map-indexed vector lines)]
    (when-let [nm (second (re-find #"^\s*- name: (\S+)\s*$" l))]
      (when (contains? repos nm)
        (swap! seen conj nm)
        (if-let [sha (head-sha org nm)]
          (loop [j (inc i)]
            (when (< j (min (+ i 6) (count lines)))
              (if-let [m (re-find #"^(\s*revision: )([0-9a-f]{40})\s*$" (nth lines j))]
                (if (= (nth m 2) sha)
                  (swap! tally update :already inc)
                  (do (swap! out assoc j (str (nth m 1) sha))
                      (swap! tally update :advanced inc)))
                (recur (inc j)))))
          (swap! tally update :no-sha inc)))))
  (let [missing (remove @seen repos)]
    (swap! tally assoc :not-in-manifest (count missing))
    (doseq [m missing] (println (str "  manifest 未登録: " m))))
  (when execute?
    ;; 末尾の改行を保つ — str/join だけだと最終行の改行が消え、
    ;; pin と無関係な 1 行の diff が出る(実測でこれを出した)。
    (fs/writeFileSync path (str (str/join "\n" @out) "\n")))
  (println (str (if execute? "EXECUTE" "DRY-RUN") " " (pr-str @tally))))
