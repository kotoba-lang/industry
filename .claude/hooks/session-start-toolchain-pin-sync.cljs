#!/usr/bin/env nbb
;; SessionStart フック: **セッションが読み"通す" checkout を、west pin に合わせる。**
;;
;; なぜ要るか (2026-09-09, ADR-2609092500):
;;   pure S-expression core が `kotoba` CLI に数日間拒否され続けた。言語は正しく、
;;   **west pin も正しかった** —— 共有 `amu` checkout が、その pin より
;;   **200 commit 遅れていた**。checkout が pin する kotoba-sema は main より 71 遅れ。
;;   CLAUDE.md が繰り返し書いている「checkout・west pin・repo の main は 3 つの
;;   別物」の、3 番目が腐っていた形である。
;;
;;   既存の `session-start-checkout-staleness` はこれを見ない —— あれは
;;   **checkout と「自分の remote の default branch」**を、**読み手の多い順**で
;;   比べる。toolchain repo は誰も `:local/root` しないので順位に入らず、
;;   200 commit 遅れたコンパイラは 1 行も出なかった。
;;
;; **警告ではなく同期する。** CLAUDE.md 自身が「警告を読むことと同期することは
;; 別の動作で、前者は後者を保証しない」と書いており、実際この日それが起きた。
;; だから clean な checkout は**黙って合わせる**（west がやることと同じ、detached
;; で pin に置く）。触らないのは次の 3 つだけで、いずれも理由を出す:
;;
;;   1. tracked な変更がある            —— 他人の WIP を捨てない (CLAUDE.md)
;;   2. branch 上に未 push の commit がある —— 落ちない。報告して止まる
;;   3. pin の commit が手元に無く、fetch も予算内に終わらなかった
;;
;;   untracked ファイルは checkout を妨げないので無視する（amu には 588 個ある）。
;;
;; **pin 自体の鮮度も出す。** pin が「最後に fetch した origin/main」より遅れて
;; いれば行数を出して 1 コマンドを示す。fetch は原則しない —— hook は速く offline
;; でなければならず、ここが報告するのは「最後の fetch が見た値」である。
;;
;; 入力: `manifest/session-sync.edn`（repo 名の一覧）と、**origin/main の**
;;   `manifest/west.yml`。**working tree の west.yml を既定にしない** ——
;;   遅れた local manifest を正本にすると、まさにこの hook が直そうとしている
;;   ズレを固定する。origin/main が引けないときはそう言って local に落ちる。
;;
;; stdout に書く: `.claude/settings.json` の SessionStart 登録は `2>/dev/null`
;;   を付けるので、stderr に書くと黙って消える。
;;
;; 手動実行: `nbb .claude/hooks/session-start-toolchain-pin-sync.cljs [<root>]`
;;           `--dry-run` を渡すと測るだけで checkout しない。

(require '[clojure.string :as str])

(def fs   (js/require "node:fs"))
(def path (js/require "node:path"))
(def cp   (js/require "node:child_process"))

(def argv (vec (.slice js/process.argv 2)))
(def dry-run? (some #(= % "--dry-run") argv))

;; nbb's argv carries the SCRIPT path too, so an argument scan that only skips
;; flags picks the script as the root. Measured twice in one day on two other
;; scripts; the failure surfaces two steps later as "not readable".
(def root
  (or (first (remove (fn [a] (or (str/starts-with? a "--") (str/ends-with? a ".cljs"))) argv))
      (.-CLAUDE_PROJECT_DIR js/process.env)
      (.cwd js/process)))

(defn sh [dir cmd]
  (try {:out (str (.execSync cp cmd #js {:cwd dir :encoding "utf8"
                                         :stdio #js ["pipe" "pipe" "pipe"]
                                         :maxBuffer 33554432}))
        :exit 0}
       (catch :default e {:out (str (or (.-stdout e) "")) :exit 1})))

(defn ok? [r] (zero? (:exit r)))
(defn trimmed [r] (str/trim (:out r)))

(def config-path (.join path root "manifest" "session-sync.edn"))

(defn read-config []
  (try
    (let [text (.readFileSync fs config-path "utf8")]
      (cljs.reader/read-string text))
    (catch :default _ nil)))

;; --- west.yml -------------------------------------------------------------

(defn manifest-text
  "The pins as origin/main has them, with the local copy as a named fallback."
  []
  (let [from-origin (sh root "git show origin/main:manifest/west.yml")]
    (if (ok? from-origin)
      {:text (:out from-origin) :source :origin/main}
      (let [local (try (.readFileSync fs (.join path root "manifest" "west.yml") "utf8")
                       (catch :default _ nil))]
        (when local {:text local :source :working-tree})))))

(defn parse-pins
  "name -> {:revision :path}. A flat scan is enough: west.yml is generated and
   every entry has the same four lines in the same order."
  [text]
  (loop [lines (str/split-lines text) current nil acc {}]
    (if (empty? lines)
      acc
      (let [line (str/trim (first lines))
            rest* (rest lines)]
        (cond
          (str/starts-with? line "- name: ")
          (recur rest* {:name (str/trim (subs line 8))} acc)

          (and current (str/starts-with? line "revision: "))
          (recur rest* (assoc current :revision (str/trim (subs line 10))) acc)

          (and current (str/starts-with? line "path: "))
          (let [entry (assoc current :path (str/trim (subs line 6)))]
            (recur rest* nil (assoc acc (:name entry) entry)))

          :else (recur rest* current acc))))))

;; --- one repository -------------------------------------------------------

(defn head-of [dir] (trimmed (sh dir "git rev-parse HEAD")))

(defn tracked-dirty? [dir]
  (seq (trimmed (sh dir "git status --porcelain --untracked-files=no"))))

(defn branch-of [dir]
  (let [b (trimmed (sh dir "git rev-parse --abbrev-ref HEAD"))]
    (when-not (= b "HEAD") b)))

(defn unpushed?
  "Commits on the current branch that the recorded pin does not contain. A
   checkout carrying work nobody has seen is not one to move."
  [dir pin]
  (let [r (sh dir (str "git rev-list --count " pin "..HEAD"))]
    (and (ok? r) (not= "0" (trimmed r)))))

(defn have-commit? [dir sha]
  (ok? (sh dir (str "git cat-file -e " sha "^{commit}"))))

(defn behind-pin
  "How far the last-fetched origin/main is ahead of the pin, or nil."
  [dir pin]
  (let [r (sh dir (str "git rev-list --count " pin "..origin/main"))]
    (when (ok? r)
      (let [n (js/parseInt (trimmed r))]
        (when (and (not (js/isNaN n)) (pos? n)) n)))))

(defn sync-one [root name entry deadline]
  (let [dir (.join path root (:path entry))
        pin (:revision entry)]
    (cond
      (not (try (.existsSync fs (.join path dir ".git")) (catch :default _ false)))
      {:state :absent :name name}

      (= (head-of dir) pin)
      {:state :at-pin :name name :stale-pin (behind-pin dir pin)}

      (tracked-dirty? dir)
      {:state :dirty :name name}

      (unpushed? dir pin)
      {:state :unpushed :name name :branch (branch-of dir)}

      :else
      (let [have? (or (have-commit? dir pin)
                      (and (< (js/Date.now) deadline)
                           (do (sh dir "git fetch origin --quiet") (have-commit? dir pin))))]
        (cond
          (not have?) {:state :missing-commit :name name}
          dry-run? {:state :would-sync :name name}
          (ok? (sh dir (str "git checkout --quiet --detach " pin)))
          {:state :synced :name name :stale-pin (behind-pin dir pin)}
          :else {:state :checkout-failed :name name})))))

;; --- report ---------------------------------------------------------------

(defn -main []
  (let [config (read-config)]
    (if-not config
      ;; Loud fail-open: a silent exit here is indistinguishable from a healthy
      ;; session, which is the defect this workspace has counted 14 times.
      (println (str "toolchain pin sync: REFUSED — " config-path " is not readable,"
                    " so nothing was measured"))
      (let [m (manifest-text)]
        (if-not m
          (println "toolchain pin sync: REFUSED — no manifest/west.yml from origin/main or the working tree")
          (let [pins (parse-pins (:text m))
                names (take (or (:max-repos config) 12) (:repos config))
                deadline (+ (js/Date.now) (or (:budget-ms config) 12000))
                results (doall (for [n names
                                     :when (< (js/Date.now) deadline)
                                     :let [entry (get pins n)]]
                                 (if entry
                                   (sync-one root n entry deadline)
                                   {:state :unregistered :name n})))
                by (group-by :state results)
                measured (count results)
                synced (:synced by)
                blocked (concat (:dirty by) (:unpushed by) (:missing-commit by) (:checkout-failed by))
                stale-pins (filter :stale-pin (concat (:at-pin by) (:synced by)))]
            (when (seq synced)
              (println (str "toolchain pin sync: synced " (count synced) " checkout(s) to their west pin — "
                            (str/join ", " (map :name synced)))))
            (doseq [r blocked]
              (println (str "  ⚠ " (:name r) ": "
                            (case (:state r)
                              :dirty "tracked changes — left alone (CLAUDE.md: 破棄しない)"
                              :unpushed (str "commits on " (or (:branch r) "HEAD") " that the pin does not contain — left alone")
                              :missing-commit "the pinned commit is not here and the fetch did not finish in budget"
                              "checkout failed")
                            "; sync it yourself when you know what it holds")))
            (doseq [r (:would-sync by)]
              (println (str "  (dry-run) " (:name r) " would move to its pin")))
            (doseq [r stale-pins]
              (println (str "  · " (:name r) ": the PIN is " (:stale-pin r)
                            " commit(s) behind the last-fetched origin/main"
                            " — advance it with `nbb scripts/west-pin-put.cljs " (:name r) " HEAD`")))
            (when (and (empty? synced) (empty? blocked) (empty? stale-pins))
              (println (str "toolchain pin sync: " measured " checkout(s) already at their west pin"
                            (when (= :working-tree (:source m))
                              " (pins read from the WORKING TREE — origin/main was not readable)"))))
            (when (< measured (count names))
              (println (str "  measured " measured " of " (count names)
                            " — the budget ran out, so this is partial")))))))))

(-main)
