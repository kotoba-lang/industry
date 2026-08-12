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

(defn- lookup
  "`{:sha \"...\"}` or `{:error \"...\"}` — never a bare nil.

  The distinction is the whole point. `gh` exits non-zero for a repository that
  does not exist, for a name that is not the repository's path, AND for a rate
  limit, and the first version collapsed all three into one `:no-sha` count and
  still exited 0. Measured 2026-08-12: with the shared hourly budget exhausted,
  210 lookups failed, the script reported `:no-sha 210`, wrote nothing, and
  succeeded — a run that touched no pin at all, indistinguishable from a run
  where those repositories were simply gone.

  Note also that `gh api rate_limit` does NOT report a secondary rate limit: it
  will say thousands of requests remain while every `repos/*` GET returns 403.
  So the error text is kept and printed rather than being classified here."
  [org repo]
  (try
    (let [out (-> (cp/execSync (str "gh api repos/" org "/" repo "/commits/HEAD --jq .sha")
                               #js {:encoding "utf8" :stdio #js ["pipe" "pipe" "pipe"]})
                  str/trim)]
      (if (re-matches #"[0-9a-f]{40}" out)
        {:sha out}
        ;; A 40-hex string is the only acceptable answer. Anything else — an
        ;; error body, an empty response — must not reach the manifest. A 403
        ;; JSON body was very nearly written into west.yml as a revision today
        ;; by a caller that did not check this.
        {:error (str "not a sha: " (subs out 0 (min 120 (count out))))}))
    (catch :default e
      {:error (or (some-> (.-stderr e) str str/trim str/split-lines first)
                  (str/trim (str (.-message e))))})))

(defn- head-sha
  "The repo's HEAD, trying the west `name` and then the last segment of its
  `path`. Those differ for 9 entries (`kotoba-lang-bim` at `orgs/kotoba-lang/
  bim`, and similar), and looking up only the name 404s on every one — so the
  script could never advance them and said so only as an untyped `:no-sha`."
  [org nm path-repo]
  (let [a (lookup org nm)]
    (if (:sha a)
      a
      (if (and path-repo (not= path-repo nm))
        (let [b (lookup org path-repo)]
          (if (:sha b) (assoc b :via path-repo) a))
        a))))

(let [[org list-file & flags] *command-line-args*
      execute? (some #{"--execute"} flags)
      repos (->> (fs/readFileSync list-file "utf8") str/split-lines
                 (map str/trim) (remove str/blank?) set)
      path "manifest/west.yml"
      lines (str/split-lines (fs/readFileSync path "utf8"))
      ;; name 行を見つけ、その直後数行にある revision 行だけを差し替える
      out (atom (vec lines))
      tally (atom {:advanced 0 :already 0 :lookup-failed 0 :not-in-manifest 0})
      failures (atom [])
      seen (atom #{})]
  (doseq [[i l] (map-indexed vector lines)]
    (when-let [nm (second (re-find #"^\s*- name: (\S+)\s*$" l))]
      (when (contains? repos nm)
        (swap! seen conj nm)
        ;; The entry's `path:` sits within the same few lines as its `name:`.
        (let [path-repo (some (fn [j]
                                (when (< j (min (+ i 6) (count lines)))
                                  (some-> (re-find #"^\s*path: \S+/(\S+)\s*$" (nth lines j))
                                          second)))
                              (range (inc i) (min (+ i 6) (count lines))))
              {:keys [sha error via]} (head-sha org nm path-repo)]
          (if sha
            (do
              (when via (println (str "  " nm ": resolved via path segment " via)))
              (loop [j (inc i)]
                (when (< j (min (+ i 6) (count lines)))
                  (if-let [m (re-find #"^(\s*revision: )([0-9a-f]{40})\s*$" (nth lines j))]
                    (if (= (nth m 2) sha)
                      (swap! tally update :already inc)
                      (do (swap! out assoc j (str (nth m 1) sha))
                          (swap! tally update :advanced inc)))
                    (recur (inc j))))))
            (do (swap! tally update :lookup-failed inc)
                (swap! failures conj [nm error])))))))
  (let [missing (remove @seen repos)]
    (swap! tally assoc :not-in-manifest (count missing))
    (doseq [m missing] (println (str "  manifest 未登録: " m))))
  (doseq [[nm err] @failures]
    (println (str "  LOOKUP FAILED " nm ": " err)))
  (when execute?
    ;; 末尾の改行を保つ — str/join だけだと最終行の改行が消え、
    ;; pin と無関係な 1 行の diff が出る(実測でこれを出した)。
    (fs/writeFileSync path (str (str/join "\n" @out) "\n")))
  (println (str (if execute? "EXECUTE" "DRY-RUN") " " (pr-str @tally)))
  ;; A lookup that could not be performed is not a pin that is up to date.
  ;; Exiting 0 here let a whole batch evaporate into a count nobody read.
  (when (pos? (:lookup-failed @tally))
    (println (str "advance-pins: " (:lookup-failed @tally)
                  " 件の HEAD 取得に失敗した。これは「pin が最新」ではない —— "
                  "`gh auth status` と secondary rate limit を確認すること"
                  "(`gh api rate_limit` はこの制限を報告しない)。"))
    (set! (.-exitCode js/process) 1)))
