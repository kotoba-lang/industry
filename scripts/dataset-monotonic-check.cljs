#!/usr/bin/env nbb
;; commit 済みデータが縮んでいないかを検査する。
;;
;;   nbb scripts/dataset-monotonic-check.cljs [--root .] [--against <ref>]
;;
;; 宣言は manifest/monotonic-datasets.edn。そこに書いた軸の distinct 値が
;; baseline より減っていたら落ちる。
;;
;; ## なぜ「件数」ではなく「軸の distinct 値」を数えるか
;;
;; 行数は正当に減りうる（重複の除去、meta 行の棄却）。**観測した日が無かったことに
;; なる**のは正当に起きない。だから数えるのは行ではなく軸である。
;;
;; ## なぜ検査が要るか（2026-08-11 の実測）
;;
;; hayari の要約が raw から作り直され、5 日 122 country-day が 1 日 61 に
;; 置き換わって push された。テストは通り、gate は緑で、ファイルは妥当な EDN の
;; まま。**「壊れた」ようには見えず「その日はそれだけだった」ように見えた。**
;;
;; ## この検査自身が一度沈黙した（同日、2 回）
;;
;; 1. superproject で `git show origin/main:<path>` していた。宣言したパスは west
;;    管理の**子リポ**にあり superproject の git には無いので、常に空が返った。
;; 2. 子リポの中で回すよう直しても、west の checkout に **`origin/main` は無い**
;;    （remote は manifest の remote 名、ref は `manifest-rev` だけ）。
;;
;; どちらも「baseline が読めない」を「新規データセット」と読んで**必ず合格**した。
;; したがってこの版では **baseline を解決できないこと自体を FAIL とする** ——
;; 検査できなかったことを、検査に通ったことと混同しない。

(ns dataset-monotonic-check
  (:require ["node:child_process" :as cp]
            ["node:fs" :as fs]
            ["node:path" :as path]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(def args (vec *command-line-args*))
(defn- flag [n d] (let [i (.indexOf args n)] (if (neg? i) d (nth args (inc i)))))

(def root      (flag "--root" "."))
(def against-in (flag "--against" nil))

(defn- git-in [dir & a]
  (let [r (cp/spawnSync "git" (clj->js (vec a))
                        #js {:cwd dir :encoding "utf8" :maxBuffer 134217728})]
    {:ok? (zero? (or (.-status r) 0)) :out (or (.-stdout r) "")}))

(defn- resolves? [dir ref] (:ok? (git-in dir "rev-parse" "--verify" "--quiet" (str ref "^{commit}"))))

(defn- baseline-ref
  "この checkout で比較に使える ref。west の子リポは remote を manifest の remote 名で
  作り `origin/main` を持たないので、決め打ちにせず探す。**見つからなければ nil を
  返し、呼び出し側が FAIL にする**（「比較できなかった」を合格にしない）。

  HEAD を最後の候補に置くのは、それが「最後に commit した状態」であり、
  実際の事故（作り直した要約を commit → push）を **commit の時点で**捕まえる
  baseline だからである。"
  [dir]
  (or (when against-in (when (resolves? dir against-in) against-in))
      (when-not against-in
        (first (for [r (->> (str/split-lines (:out (git-in dir "remote")))
                            (remove str/blank?)
                            (cons "origin"))
                     b ["HEAD" "main" "master"]
                     :let [ref (str r "/" b)]
                     :when (resolves? dir ref)]
                 ref)))
      (when (and (nil? against-in) (resolves? dir "HEAD")) "HEAD")))

(defn- axis-values
  "宣言された軸の distinct 値。読めなければ nil（0 ではない —— 読めないことを
  『空だった』と数えると、この検査自身が沈黙する故障を持つ）。"
  [text axis]
  (try
    (let [d (edn/read-string {:default (fn [_ v] v)} text)
          ms (filter map? (if (sequential? d) d [d]))]
      (set (keep #(get % axis) ms)))
    (catch :default _ nil)))

;; 宣言の場所を root と独立に指定できるようにしてある。worktree で編集した宣言を
;; 本体の checkout に対して試す、という運用が実際に要る。
(def decl-file (flag "--decl" (path/join root "manifest" "monotonic-datasets.edn")))

(when-not (fs/existsSync decl-file)
  (println (str "FAIL monotonic: 宣言が無い — " decl-file))
  (js/process.exit 1))

(def datasets (:datasets (edn/read-string (fs/readFileSync decl-file "utf8"))))

(when (empty? datasets)
  (println "FAIL monotonic: 宣言が空 — 検査対象ゼロを合格にしない")
  (js/process.exit 1))

(def results
  (vec
    (for [{:keys [repo path axis label]} datasets]
      (let [dir     (path/join root repo)
            f       (path/join dir path)
            now-txt (when (fs/existsSync f) (fs/readFileSync f "utf8"))
            git?    (fs/existsSync (path/join dir ".git"))
            ref     (when git? (baseline-ref dir))
            ;; baseline に「そのパスが在るか」を先に問う。無いなら新規、
            ;; 在るのに読めないなら故障 —— この 2 つを同じ nil に潰さない。
            in-base? (when ref (:ok? (git-in dir "cat-file" "-e" (str ref ":" path))))
            base-r   (when in-base? (git-in dir "show" (str ref ":" path)))
            now      (when now-txt (axis-values now-txt axis))
            base     (when (:ok? base-r) (axis-values (:out base-r) axis))
            issue
            (cond
              (not git?)  {:kind :no-repo
                           :msg (str "checkout が無い — " dir
                                     "（west update 未実行。検査できないことを合格にしない）")}
              (nil? ref)  {:kind :no-baseline
                           :msg (str "比較できる ref が無い"
                                     (when against-in (str " — 指定された " against-in " が解決しない")))}
              (nil? now-txt)  {:kind :missing    :msg "working tree に無い"}
              (nil? now)      {:kind :unreadable :msg "working tree 側が読めない"}
              (not in-base?)  nil                       ; 新規データセット。縮みようがない
              (nil? base)     {:kind :unreadable :msg (str ref " 側が読めない")}
              :else
              (let [lost (sort (remove now base))]
                (when (seq lost)
                  {:kind :shrank
                   :msg (str (count base) " → " (count now)
                             "、消えた軸 " (count lost) " 件: "
                             (str/join ", " (take 5 (map str lost)))
                             (when (< 5 (count lost)) " …"))})))]
        {:label label :path path :ref ref :n (some-> now count)
         :new? (and ref (not in-base?)) :issue issue}))))

(def findings (vec (keep #(when (:issue %) (merge (:issue %) (select-keys % [:label :path]))) results)))

(println (str "monotonic: " (count datasets) " 件の宣言を検査"))

(if (empty? findings)
  (do (doseq [{:keys [label n ref new?]} results]
        (println (str "  OK " label ": " (or n "?") " 件"
                      (if new? (str "（" ref " に無い = 新規）") (str "（vs " ref "）")))))
      (println "OK monotonic: 縮んだデータは無い"))
  (do (doseq [{:keys [label path kind msg]} findings]
        (println (str "  " (name kind) " " label " (" path "): " msg)))
      (println (str "FAIL monotonic: " (count findings)
                    " 件。観測した軸が消えるのは正当に起きない —— "
                    "行が減るのと、観測した日が無かったことになるのは別"))
      (js/process.exit 1)))
