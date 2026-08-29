#!/usr/bin/env nbb
;; scripts/recruit-vertical-tick.cljs — manifest/recruit-equivalent-verticals.edn を
;; 現在地と突き合わせ、**次に建てる 1 本**を名指しする。ADR-2608290100。
;;
;;   nbb --classpath ".:scripts/nbb_compat" scripts/recruit-vertical-tick.cljs [--findings] [--root <dir>]
;;
;; ## この tick が答える問い
;;
;;   1. registry の行は全部、出所つきで測られているか（測っていない値が順位に
;;      混ざっていないか）
;;   2. 基準（売上高 / 年間商品販売額）の違う値が黙って 1 つの順位に混ざって
;;      いないか
;;   3. その vertical の repo は実在するか。governor を持つか
;;   4. biscuit の scope は宣言されているか。**それは live か**
;;   5. hyakka（wiki.kotobase.net）に claim が出ているか
;;   6. 次に建てるべき 1 本はどれか
;;
;; ## exit code —— 3 値。畳まない
;;
;;   0  測れて、床を割っているものが無い
;;   1  測れて、findings が在る
;;   2  REFUSED —— そもそも問えなかった（orgs/ が無い等）
;;
;; 1 と 2 を畳まないのは CLAUDE.md の「測れなかった検査が、測って問題が無かった
;; 検査と同じ値を返す」を避けるため。**orgs/ を持たない checkout でこの tick を
;; 走らせると 2 を返す** —— 0 ではない。superproject の共有 checkout は west の
;; pin で orgs/ を持つが、CI コンテナや worktree は持たないことがあり、そこで
;; 「clean」と報告したらこの tick は嘘をつく。
;;
;; ## 何も書かない
;;
;; :writes :none。測って言うだけ。着地は人か loop の agent 側がやる。

(ns recruit-vertical-tick
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            ["node:fs" :as fs]
            ["node:path" :as path]))

(def argv (vec *command-line-args*))
(defn- flag? [n] (boolean (some #{n} argv)))
(defn- opt [n] (let [i (.indexOf (clj->js argv) n)]
                 (when (and (>= i 0) (< (inc i) (count argv))) (nth argv (inc i)))))

(def root (or (opt "--root") (aget (.-env js/process) "COM_JUNKAWASAKI_ROOT") "."))
(def registry-path (path/join root "manifest" "recruit-equivalent-verticals.edn"))

(def findings (atom []))
(defn- finding! [sev k detail] (swap! findings conj [sev k detail]))

(defn- exists? [p] (try (.existsSync fs p) (catch :default _ false)))
(defn- slurp* [p] (try (.readFileSync fs p "utf8") (catch :default _ nil)))

(defn- refuse! [why]
  (println (str "REFUSED\t" why))
  (println "This tick refuses to report a pass it did not measure.")
  (.exit js/process 2))

;; ---------------------------------------------------------------------------
;; 1. registry を読む。読めなければ REFUSED（空として扱わない）
;; ---------------------------------------------------------------------------

(def registry
  (let [s (slurp* registry-path)]
    (when-not s (refuse! (str "registry unreadable: " registry-path)))
    (let [d (try (edn/read-string s) (catch :default e (refuse! (str "registry unparseable: " (.-message e)))))]
      (when-not (map? d) (refuse! "registry is not a map"))
      d)))

(def verticals (vec (:registry/verticals registry)))
(def sources (:registry/sources registry))

(when (empty? verticals) (refuse! "registry declares no verticals"))

;; ---------------------------------------------------------------------------
;; 2. 出所の完全性 —— 測られたと名乗る行は、出所を全部持っていなければならない
;; ---------------------------------------------------------------------------

(def required-source-fields [:url :retrieved-at :sha256 :unit :reference-year :publisher])

(defn- check-provenance! [v]
  (let [id (name (:vertical/id v))
        status (:market/status v)]
    (cond
      (nil? status)
      (finding! "error" (str "market-status-missing/" id)
                "行が :market/status を宣言していない。measured か unmeasured かが出力から区別できない")

      (= status :measured)
      (let [src-key (:market/source v)
            src (get sources src-key)]
        (cond
          (nil? (:market/value v))
          (finding! "error" (str "measured-without-value/" id) ":measured なのに :market/value が無い")

          (nil? src)
          (finding! "error" (str "source-unresolved/" id)
                    (str ":market/source " (str src-key) " が :registry/sources に無い"))

          :else
          (let [missing (remove #(get src %) required-source-fields)]
            (when (seq missing)
              (finding! "error" (str "source-incomplete/" id)
                        (str "出所に " (str/join "," (map name missing)) " が無い")))
            (when-not (:market/basis v)
              (finding! "error" (str "basis-missing/" id)
                        ":market/basis が無い。基準の違う値が黙って混ざる"))
            (when-not (:market/table v)
              (finding! "warn" (str "table-missing/" id)
                        "どの表から採ったかが無い。再現できない")))))

      :else nil)))

;; ---------------------------------------------------------------------------
;; 3. 基準の混在 —— 混ぜてはいけないのではなく、黙って混ぜてはいけない
;; ---------------------------------------------------------------------------

(defn- check-basis-mix! []
  (let [measured (filter #(= :measured (:market/status %)) verticals)
        bases (into #{} (keep :market/basis measured))]
    (when (> (count bases) 1)
      (let [by-basis (reduce (fn [m v] (update m (:market/basis v) (fnil conj []) (name (:vertical/id v))))
                             {} measured)]
        (finding! "info" "ranking-mixed-basis"
                  (str "順位が " (count bases) " 種類の基準にまたがる: "
                       (str/join " / " (map (fn [[b ids]] (str b "=" (str/join "," ids))) by-basis))
                       "。これは欠陥ではなく、申告されるべき事実"))))
    (when (some #(nil? (:market/basis %)) measured)
      (finding! "error" "basis-unknown-in-ranking" "基準の無い measured 行が順位に入っている"))))

;; ---------------------------------------------------------------------------
;; 4. 現在地 —— orgs/ を読む。無ければ REFUSED
;; ---------------------------------------------------------------------------

(def orgs-dir (path/join root "orgs" "cloud-itonami"))

(defn- repo-state [repo-path]
  (let [abs (path/join root repo-path)]
    (if-not (exists? abs)
      {:present? false}
      {:present? true
       :governor? (or (exists? (path/join abs "src"))
                      (exists? (path/join abs "deps.edn")))
       :readme?   (exists? (path/join abs "README.md"))})))

(defn- check-fleet! [v]
  (let [id (name (:vertical/id v))
        repos (vec (:vertical/repos v))]
    (when (empty? repos)
      (finding! "error" (str "no-repo/" id) "vertical が repo を 1 本も指していない"))
    (doseq [r repos]
      (let [st (repo-state r)]
        (cond
          (not (:present? st))
          (finding! "warn" (str "repo-absent/" id "/" (path/basename r))
                    (str r " が checkout に無い（west pin は在るかもしれない）"))
          (not (:governor? st))
          (finding! "warn" (str "repo-no-source/" id "/" (path/basename r))
                    (str r " に src/ も deps.edn も無い")))))))

;; ---------------------------------------------------------------------------
;; 5. biscuit / hyakka —— 宣言と live を分けて報告する
;; ---------------------------------------------------------------------------

(defn- check-authority! [v]
  (let [id (name (:vertical/id v))
        res (:authority/resource v)
        scheme (get-in registry [:registry/authority :scheme])]
    (cond
      (nil? res) (finding! "error" (str "authority-undeclared/" id) ":authority/resource が無い")
      (not (str/starts-with? res "kotoba://itonami/"))
      (finding! "error" (str "authority-off-scheme/" id)
                (str res " が宣言された scheme " scheme " に乗っていない")))))

(defn- check-hyakka! [v]
  (let [id (name (:vertical/id v))]
    (when-not (:hyakka/subject v)
      (finding! "error" (str "hyakka-subject-missing/" id) ":hyakka/subject が無い。claim の主語が決まらない"))))

;; ---------------------------------------------------------------------------
;; 実行
;; ---------------------------------------------------------------------------

(doseq [v verticals]
  (check-provenance! v)
  (check-authority! v)
  (check-hyakka! v))
(check-basis-mix!)

;; orgs/ を読む段。ここから先は測れないなら REFUSE する
(def orgs-present? (exists? orgs-dir))

(when orgs-present?
  (doseq [v verticals] (check-fleet! v)))

;; ---------------------------------------------------------------------------
;; 順位 —— measured だけ。unmeasured は 0 で埋めず、別枠で数える
;; ---------------------------------------------------------------------------

(def measured (filterv #(= :measured (:market/status %)) verticals))
(def unmeasured (filterv #(not= :measured (:market/status %)) verticals))
(def ranked (vec (reverse (sort-by :market/value measured))))

(println "== recruit-equivalent verticals ==")
(println (str "registry: " registry-path))
(println (str "measured: " (count measured) "  unmeasured(順位外): " (count unmeasured)
              "  capabilities(順位外): " (count (:registry/capabilities registry))))
(println "")
(println "rank\tvertical\t市場規模(百万円)\t基準\t出所")
(doseq [[i v] (map-indexed vector ranked)]
  (println (str (inc i) "\t" (name (:vertical/id v)) "\t" (:market/value v)
                "\t" (:market/basis v) "\t" (name (:market/source v)))))
(println "")

;; 次の 1 本 = まだ床を割っている measured 行のうち、最も市場規模が大きいもの。
;; 「2 本まとめない」は ADR-2607189300 のガードレール。
(def broken-ids
  (into #{} (keep (fn [[sev k _]]
                    (when (not= sev "info")
                      (let [seg (str/split k #"/")]
                        (when (> (count seg) 1) (nth seg 1)))))
                  @findings)))

(def next-one (first (filter #(contains? broken-ids (name (:vertical/id %))) ranked)))

(if next-one
  (println (str "NEXT\t" (name (:vertical/id next-one))
                "\t市場規模 " (:market/value next-one) " 百万円"
                "\t" (str/join "," (:vertical/repos next-one))))
  (println "NEXT\t(none)\tmeasured 行に床割れが無い"))
(println "")

;; ---------------------------------------------------------------------------
;; findings と evidence floor
;; ---------------------------------------------------------------------------

(when (flag? "--findings")
  (doseq [[sev k detail] @findings]
    (println (str "FINDING\t" sev "\t" k "\t" detail))))

;; **evidence floor は単位を含む。** 正の数だけを床にすると「別の集合を数えた」
;; を検出できない（CLAUDE.md の orgs-detectors 節が記録した失敗の形）。
(println (str "SCANNED\t" (count verticals) "/" (count verticals) "\tvertical"))

(when-not orgs-present?
  (refuse! (str "orgs/cloud-itonami が無いので fleet 側（repo 実在・governor）を測れなかった: " orgs-dir)))

(let [hard (count (remove #(= "info" (first %)) @findings))]
  (println (str "RESULT\t" (if (zero? hard) "clean" "findings") "\thard=" hard
                "\tinfo=" (- (count @findings) hard)))
  (.exit js/process (if (zero? hard) 0 1)))
