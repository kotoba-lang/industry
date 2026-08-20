#!/usr/bin/env nbb
;; MoneyForward パリティ検査 —— 分母のある被覆率を出す。
;;
;;   nbb --classpath ".:scripts/nbb_compat" scripts/verify-moneyforward-parity.cljs
;;
;; ## 何を測るか
;;
;; `90-docs/accounting/moneyforward-parity.datoms.edn` が記録した MF の
;; 財務諸表区分（実測 46 対）を分母に、`kotoba.shohyo.jp` が**実際に定義して
;; いる** section を分子にする。
;;
;; ## 数字を 2 箇所に持たない
;;
;; dataset は「MF 側に何があるか」と「どの section へ写すつもりか」だけを持ち、
;; **cloud-itonami 側にその section が在るかどうかは毎回ソースから数え直す。**
;; 到達度を dataset に書けば、実装を消しても数字は残る。
;;
;; ## 答えられなかったときは 0 でも 1 でもない
;;
;; ADR-2608136000: 「測れなかった検査が、測って問題が無かった検査と同じ値を
;; 返す」。ここで測れない条件は 3 つ —— dataset が読めない / shohyo の
;; checkout が無い / jp.cljc から section が 1 つも抽出できない。いずれも
;; **exit 3**（clean=0, gap=1 のどちらでもない）で、被覆率を印字しない。
(ns verify-moneyforward-parity
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            ["fs" :as fs]
            ["path" :as path]))

(def root (or (some-> js/process.env .-FLEET_ROOT) (.cwd js/process)))
(def dataset (path/join root "90-docs" "accounting" "moneyforward-parity.datoms.edn"))
(def shohyo-src (path/join root "orgs" "kotoba-lang" "shohyo" "src" "kotoba" "shohyo"))
(def jp-src (path/join shohyo-src "jp.cljc"))
(def genka-src (path/join shohyo-src "genka.cljc"))

(defn refuse! [why]
  (println (str "REFUSING to report a coverage figure: " why))
  (println "exit 3 — could not answer. This is not a pass and not a gap.")
  (.exit js/process 3))

(defn slurp' [f]
  (when (fs/existsSync f) (fs/readFileSync f "utf8")))

(defn- balanced-map
  "The literal map starting at the first `{` at or after `from`, as text.

  Brace counting that knows about strings — `:jp \"流動資産\"` carries no
  braces today, but a docstring or a quoted 条文 could, and a counter that
  did not skip strings would close the map early and read a prefix as if it
  were the whole thing."
  [src from]
  (when-let [start (str/index-of src "{" from)]
    (loop [i start depth 0 in-str? false esc? false]
      (if (>= i (count src))
        nil
        (let [c (nth src i)]
          (cond
            esc?             (recur (inc i) depth in-str? false)
            (= c \\)         (recur (inc i) depth in-str? in-str?)
            (= c \")         (recur (inc i) depth (not in-str?) false)
            in-str?          (recur (inc i) depth true false)
            (= c \{)         (recur (inc i) (inc depth) false false)
            (= c \})         (if (= 1 depth)
                               (subs src start (inc i))
                               (recur (inc i) (dec depth) false false))
            :else            (recur (inc i) depth false false)))))))

(defn- section-keys
  "The keys of `(def <name> ...)`'s map, by READING it rather than by matching
  its indentation.

  The indentation-matching version of this got the answer wrong in both
  directions on real files: it missed `:current-assets` because that entry
  shares a line with the opening brace, and it would have captured `:jp` from
  an entry whose value starts on the next line. The map is literal EDN, so
  there is no reason to guess at it."
  [src name]
  (when-let [i (str/index-of src (str "(def " name))]
    (some-> (balanced-map src i) edn/read-string keys set)))

(defn sections-defined
  "The section keywords `kotoba.shohyo.jp` actually defines, read from the
  source rather than from a list kept alongside it.

  Read textually because this script runs from the superproject and the
  shohyo checkout is a west project whose deps are not on this classpath."
  [jp genka]
  {:bs    (or (section-keys jp "bs-sections") #{})
   :pl    (or (section-keys jp "pl-sections") #{})
   :cogs  (or (section-keys genka "cogs-items") #{})
   :genka (or (section-keys genka "cost-report-sections") #{})})

(defn -main []
  (let [raw (or (slurp' dataset) (refuse! (str "dataset unreadable: " dataset)))
        entities (try (edn/read-string raw)
                      (catch :default e (refuse! (str "dataset will not parse: " (.-message e)))))
        cats (filterv :mf.category/id entities)
        snap (first (filter :mf.snapshot/id entities))
        src  (or (slurp' jp-src)
                 (refuse! (str "shohyo checkout absent: " jp-src
                               " — run `west update --fetch smart shohyo`")))
        gsrc (or (slurp' genka-src)
                 (refuse! (str "shohyo checkout is behind: " genka-src
                               " is absent. The pin may not have been advanced"
                               " — run `west update --fetch smart shohyo`")))
        {:keys [bs pl cogs genka]} (sections-defined src gsrc)
        defined (reduce into #{} [bs pl cogs genka])]

    (when (empty? cats)
      (refuse! "dataset carries no :mf.category/id entities"))
    ;; Floors on the textual read. A count floor asks whether ENOUGH was
    ;; parsed; an identity floor asks whether the RIGHT thing was — and only
    ;; the second catches a regex that drifted onto the wrong lines.
    (doseq [[k n] [[:bs (count bs)] [:pl (count pl)]
                   [:cogs (count cogs)] [:genka (count genka)]]]
      (when (< n 3)
        (refuse! (str "only " n " entries parsed out of " (name k)
                      "-sections — the reader is broken, not the library"))))
    ;; Identity anchors, and ONLY where one is honest. Each of these three is
    ;; named by an article quoted in the library, so it cannot legitimately
    ;; disappear — if it is missing, the reader is reading the wrong file.
    ;; `cost-report-sections` deliberately has no anchor: every member of it
    ;; is `:source :observed` (原価計算基準 is not a 法令 and was not read), so
    ;; any of them MAY legitimately be renamed, and pinning one here would be
    ;; false precision that turns a real coverage gap into a refusal.
    (doseq [[k s'] [[:current-assets bs] [:net-sales pl] [:ending-inventory cogs]]]
      (when-not (contains? s' k)
        (refuse! (str k " is not in the parsed set. It is named by an article "
                      "this library quotes, so it cannot legitimately have been "
                      "removed — the reader is reading the wrong file"))))
    (when-not (= (count cats) (:mf.snapshot/categories snap))
      (refuse! (str "snapshot says " (:mf.snapshot/categories snap)
                    " categories, file carries " (count cats)
                    " — one of the two was edited without the other")))

    (let [judged (mapv (fn [{:mf.category/keys [statement id shohyo-section accounts]}]
                         {:statement statement :id id :accounts accounts
                          :verdict (cond
                                     (nil? shohyo-section) :no-section-mapped
                                     (contains? defined shohyo-section) :covered
                                     :else :section-named-but-absent)
                          :section shohyo-section})
                       cats)
          g (group-by :verdict judged)
          covered (count (:covered g))
          total (count judged)
          gaps (concat (:no-section-mapped g) (:section-named-but-absent g))]
      (println (str "SCANNED\t" total " MF categories against "
                    (count defined) " sections defined across kotoba.shohyo.jp"
                    " and kotoba.shohyo.genka"))
      (println (str "SNAPSHOT\t" (:mf.snapshot/office-name snap)
                    " measured " (:mf.snapshot/measured-at snap)
                    " — " (:mf.snapshot/accounts snap) " accounts, "
                    (:mf.snapshot/sub-accounts snap) " sub-accounts"))
      (println (str "COVERED\t" covered "/" total
                    " categories, " (reduce + 0 (map :accounts (:covered g))) "/"
                    (reduce + 0 (map :accounts judged)) " accounts"))
      (doseq [[verdict rows] (sort-by key (dissoc g :covered))]
        (println (str "\n" (name verdict) " (" (count rows) "):"))
        (doseq [{:keys [statement id accounts section]} (sort-by (juxt :statement :id) rows)]
          (println (str "  " statement "\t" id "\t" accounts " accounts"
                        (when section (str "\t-> " section " NOT DEFINED"))))))
      (when (seq (:section-named-but-absent g))
        (println "\n⚠ a section the dataset names is gone from the library — the mapping rotted, or the library regressed"))
      (if (seq gaps)
        (do (println (str "\n" (count gaps) (if (= 1 (count gaps)) " category has" " categories have") " nowhere to go."
                          " An account in one of them reports :unknown-section,"
                          " and `statements` returns :incomplete."))
            (.exit js/process 1))
        (println "\nEvery measured MF category has a section.")))))

(-main)
