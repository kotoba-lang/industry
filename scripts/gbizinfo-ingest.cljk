#!/usr/bin/env nbb
;; gbizinfo-ingest.cljs — gBizINFO 全件を日次で取り込み、corpus を annex の
;; 2 remote に預け、projection を commit する（ADR-2608190100）。
;; launchd の com.gftd.gbizinfo-ingest から呼ばれる。
;;
;; ## 日次でよいのか
;;
;; 全件データの更新は月次だが、**publish 日を事前に知る方法が無い**（配布ファイル名は
;; ダウンロードした日付で、publish 日ではない）。だから毎日引いて **content の
;; sha256 が前回と同じなら projection を書き換えない**。5 ファイル約 28 MB/日の
;; ダウンロードは払う。払わないと「いつ変わったか」を後から言えない。
;;
;; ## 不変条件（kouhou-ingest と同じ規律）
;;
;; 1. **「測れなかった」を「問題なし」と同じ値で返さない** —— token が無い /
;;    checkout が無い / 乖離しているは exit 2、収集の失敗は exit 1。
;; 2. **custody は location log ではなく exit code**（ADR-2608131100）。
;; 3. **corpus が 1 remote にも載らなかった run は成功ではない。**

(require '[clojure.string :as str])

(def cp (js/require "node:child_process"))
(def fs (js/require "node:fs"))
(def path (js/require "node:path"))

(def root "/Users/junkawasaki/github/com-junkawasaki")
(def ds (str root "/orgs/com-junkawasaki/jp-go-gbiz-info"))
(def property (str root "/orgs/kotoba-lang/property"))
(def remotes ["kotobase" "b2"])

(defn- run [args {:keys [dir env]}]
  (let [r (.spawnSync cp (first args) (clj->js (rest args))
                      (clj->js (cond-> {:encoding "utf8" :maxBuffer (* 256 1024 1024)}
                                 dir (assoc :cwd dir) env (assoc :env env))))]
    {:exit (or (.-status r) 1) :out (or (.-stdout r) "") :err (or (.-stderr r) "")}))

(defn- git [& args] (run (into ["git"] args) {:dir ds}))
(defn- lines [s] (remove str/blank? (str/split-lines (or s ""))))
(defn- die [m] (println (str "UNABLE " m)) (js/process.exit 2))
(defn- fail [m] (println (str "FAIL " m)) (js/process.exit 1))

(println (str "gbizinfo-ingest " (.toISOString (js/Date.))))

(doseq [d [ds property]]
  (when-not (.existsSync fs (str d "/.git"))
    (die (str "checkout が無い: " d " — west update してから"))))

;; ── 門: 他人の作業を壊さない ─────────────────────────────────────────────
(let [dirty (->> (lines (:out (git "status" "--porcelain")))
                 (map #(subs % 3))
                 (remove #(or (str/starts-with? % "data/") (str/starts-with? % "raw/"))))]
  (when (seq dirty)
    (die (str "data/ と raw/ の外に未コミット変更がある: " (str/join " " (take 5 dirty))))))

(def remote (or (first (remove #{"kotobase" "b2"} (lines (:out (git "remote"))))) "origin"))
(git "fetch" "--quiet" remote)
(let [main-ref (str remote "/main")
      behind? (zero? (:exit (git "merge-base" "--is-ancestor" "HEAD" main-ref)))
      ahead? (zero? (:exit (git "merge-base" "--is-ancestor" main-ref "HEAD")))]
  (when-not (or behind? ahead?) (die (str "HEAD と " main-ref " が乖離している")))
  (when (and behind? (not ahead?)) (git "merge" "--ff-only" main-ref)))
(run ["git" "annex" "merge"] {:dir ds})

;; ── 面が持つ法人番号（これが projection の対象） ─────────────────────────
(def numbers-file "/tmp/gbizinfo-plane-numbers.txt")
(def gov-file "/tmp/gbizinfo-government-numbers.txt")

(let [dirs [(str root "/orgs/com-junkawasaki/jp-go-nta-houjin-bangou/data")
            (str root "/90-docs/business")]
      all (atom #{}) gov (atom #{})]
  (doseq [d dirs :when (.existsSync fs d)
          f (js->clj (.readdirSync fs d)) :when (str/ends-with? f ".edn")]
    (let [gov? (str/includes? f "government")]
      (doseq [l (lines (.readFileSync fs (.join path d f) "utf8"))
              m (re-seq #"\"(\d{13})\"" l)]
        (swap! all conj (second m))
        (when gov? (swap! gov conj (second m))))))
  (when (empty? @all) (die "面に法人番号が 1 件も無い"))
  (.writeFileSync fs numbers-file (str/join "\n" (sort @all)))
  (.writeFileSync fs gov-file (str/join "\n" (sort @gov)))
  (println (str "  plane: " (count @all) " 法人番号 (うち government " (count @gov) ")")))

;; ── 取り込み ────────────────────────────────────────────────────────────
(def raw-dir (str ds "/raw/" (subs (.toISOString (js/Date.)) 0 10)))
(def before-sha
  (let [f (str ds "/data/gbizinfo-zenken-joined.datoms.edn")]
    (when (.existsSync fs f)
      (set (map second (re-seq #":source/content-sha256 \"([0-9a-f]{64})\""
                               (.readFileSync fs f "utf8")))))))

(let [{:keys [exit out err]} (run ["nbb" "-cp" "src" "scripts/collect_gbizinfo_zenken.cljs"
                                   "--numbers" numbers-file
                                   "--summarise" gov-file
                                   "--archive-to" raw-dir
                                   "--out" (str ds "/data/gbizinfo-zenken-joined.datoms.edn")
                                   "--summary-out" (str ds "/data/gbizinfo-zenken-government-summary.datoms.edn")]
                                  {:dir property})]
  (doseq [l (filter #(or (str/includes? % "rows ->") (str/includes? % "wrote")) (lines out))]
    (println (str "  " l)))
  (when (pos? exit)
    (println (str "  " (str/trim (or (last (lines out)) ""))))
    (println (str "  stderr: " (str/trim (or (last (lines err)) ""))))
    ;; collector の exit 3 は「トークンが無い」= 測れなかった。
    (if (= 3 exit) (die "gBizINFO トークンが解決できない") (fail "collect に失敗した"))))

;; ── publish が変わったか ────────────────────────────────────────────────
(def after-sha
  (set (map second (re-seq #":source/content-sha256 \"([0-9a-f]{64})\""
                           (.readFileSync fs (str ds "/data/gbizinfo-zenken-joined.datoms.edn") "utf8")))))
(def unchanged? (= before-sha after-sha))
(println (str "  publish: " (if unchanged? "前回と同じ内容" "変わった")
              " (" (count after-sha) " ファイルの sha256)"))

;; ── commit（raw/** は .gitattributes により annex 行き） ─────────────────
(let [{:keys [exit err]} (run ["datalad" "save" "-m"
                               (str "gbizinfo " (subs (.toISOString (js/Date.)) 0 10)
                                    (when unchanged? " (publish 変化なし)"))]
                              {:dir ds})]
  (when (pos? exit) (println (str "  save stderr: " (str/trim err))) (fail "datalad save に失敗")))
(println "  save ok")

;; ── corpus を 2 remote へ。annex copy（datalad push ではない） ───────────
(doseq [r remotes]
  (let [{:keys [exit err]} (run ["git" "annex" "copy" "--to" r "--jobs" "1" "raw"] {:dir ds})]
    (when (pos? exit)
      (println (str "  copy --to " r ": " (str/trim (or (last (lines err)) "")))))))

;; ── custody は exit code で。今日の分は全部、過去は標本 ─────────────────
(defn- keys-under [p] (lines (:out (run ["git" "annex" "find" "--format=${key}\n" p] {:dir ds}))))
(def todays (keys-under (str "raw/" (subs (.toISOString (js/Date.)) 0 10))))
(def older (remove (set todays) (keys-under "raw")))
(def checked (concat todays (take 5 (shuffle older))))
(def custody
  (into {} (for [r remotes]
             [r (count (filter #(zero? (:exit (run ["git" "annex" "checkpresentkey" % r] {:dir ds})))
                               checked))])))
(println (str "  custody (VERIFIED by exit code): "
              (str/join ", " (for [r remotes] (str r "=" (get custody r) "/" (count checked))))
              "  [today " (count todays) " + sample " (- (count checked) (count todays))
              " of " (count older) " older]"))

;; ── push ────────────────────────────────────────────────────────────────
(doseq [[src dst] [["HEAD" "main"] ["git-annex" "git-annex"]]]
  (let [{:keys [exit err]} (git "push" remote (str src ":" dst))]
    (println (str "  push " dst (if (zero? exit) " ok"
                                    (str " FAILED: " (str/trim (or (last (lines err)) ""))))))))

(when (pos? (:exit (git "merge-base" "--is-ancestor" "HEAD" (str remote "/main"))))
  (fail "commit が remote に載っていない — 手元だけの記録になっている"))

(when (and (seq checked) (zero? (apply max (vals custody))))
  (fail "corpus がどの remote にも 1 件も載っていない"))

(println "gbizinfo-ingest done")
