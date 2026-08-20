#!/usr/bin/env nbb
;; threed-coscientist — Generate → Reflect → Rank(Elo) → Evolve → Meta を
;; 3D/CAD/CAM/render スタックの **実測値**に対して回す。
;;
;; 原典は `90-docs/adr/2606141500-keiei-arbor-coscientist-engine.edn`、直近の先例は
;; `90-docs/design-quality/coscientist.cljc`（isekai.ux ADR-0007 由来）。
;;
;; **judge は LLM ではない。** 入力は `scripts/threed-maturity-audit.cljs` が tree から
;; 測った `threed-parity.datoms.edn` で、勝敗は測定値だけで決まる（同じ入力なら同じ
;; 順位が出る）。ADR-2607132300 の実測 —— 3 体の LLM judge が全軸 4.0–5.0/5 と採点した
;; 裏で 4 つの具体的欠落を 1 つも指摘しなかった —— を繰り返さないため。
;;
;;   nbb scripts/threed-coscientist.cljs            # 次の反復番号で書く
;;   nbb scripts/threed-coscientist.cljs --n 2      # 反復番号を明示
;;
;; exit 3 = 測定が無い / 空（**答えられなかった**。0 でも 1 でもない）

(ns threed-coscientist
  (:require [clojure.edn :as edn] [clojure.string :as str] ["fs" :as fs] ["path" :as path]))

(def root (or (aget js/process.env "THREED_ROOT") (js/process.cwd)))
(def argv (vec (drop 2 (js->clj js/process.argv))))
(def parity-file (path/join root "90-docs" "maturity" "threed-parity.datoms.edn"))
(def out-dir (path/join root "90-docs" "maturity" "coscientist"))

(defn refuse! [msg]
  (binding [*print-fn* *print-err-fn*] (println (str "REFUSED\t" msg)))
  (js/process.exit 3))

(defn- pad [s n] (let [s (str s)] (str s (apply str (repeat (max 0 (- n (count s))) " ")))))

;; ── Generate ──────────────────────────────────────────────────────────────
;; 1 つの測定 → 1 つ以上の仮説。hollow は 2 つ出す（実装する / 宣言を撤回する）——
;; 「名前だけ在る」状態の解消は、実装だけが解ではない。撤回も真実の回復である。

(def effort-rank {:S 0 :M 1 :L 2 :XL 3})

(defn- hypotheses-for [i m]
  (let [{:parity/keys [axis label weight status segment behavior-probe evidence markers-missing]} m
        w weight
        base {:h/axis axis :h/label label :h/segment segment :h/weight w}]
    (case (keyword (subs status 1))
      :hollow
      [(assoc base :h/id (str "h" i "a") :h/kind :implement :h/effort :L
              :h/gain (* 1.0 w)
              :h/change (str "宣言された能力を実装する。" evidence)
              :h/why "hollow は最悪の状態 —— grep でも行数でも LLM 採点でも緑に見え、下流が在ると信じて設計する")
       (assoc base :h/id (str "h" i "b") :h/kind :retract :h/effort :S
              :h/gain (* 0.7 w)
              :h/change (str "実装しないなら**宣言を撤回する**（構築子・戦略集合・post 一覧を消すか、"
                             "呼ばれたら明示的に [:error \"not implemented\"] を返す）。"
                             "真実の回復は実装だけが手段ではない")
              :h/why "撤回は :S で、下流が誤解する期間を今日終わらせられる")]

      :declared
      [(assoc base :h/id (str "h" i) :h/kind :probe :h/effort :S
              :h/gain (* 0.6 w)
              :h/change (str "behavior probe `90-docs/maturity/probes/" (str/replace (str/replace axis ":" "") "/" "-")
                             ".cljs` を書いて :working か :hollow かを確定させる")
              :h/why "marker だけ通った軸は未検証。能力は増えないが、**未知が既知になる** —— 未知のまま売る方が高くつく")]

      :thin
      [(assoc base :h/id (str "h" i) :h/kind :complete :h/effort :M
              :h/gain (* 0.5 w)
              :h/change (str "欠けている marker を実装する: " markers-missing)
              :h/why "部分実装は、欠けている名前が分かっている分だけ着手しやすい")]

      :absent
      [(assoc base :h/id (str "h" i) :h/kind :build :h/effort (if (>= w 5) :XL :L)
              :h/gain (* 0.9 w)
              :h/change (str "走査範囲に無い。作るか、**この軸を対象外と明示する**: " markers-missing)
              :h/why "absent は嘘をつかない —— 無いと言っている。危険なのは hollow の方")]

      :unmeasurable
      [(assoc base :h/id (str "h" i) :h/kind :measure :h/effort :S
              :h/gain (* 0.4 w)
              :h/change (str "測れるようにする（checkout / paths の解決）: " evidence)
              :h/why "測れない軸は pass にも fail にも数えていない —— 分母から落ちている")]

      [])))

(defn generate [rows]
  (vec (mapcat (fn [[i m]] (hypotheses-for i m))
               (map-indexed vector (remove #(= ":working" (:parity/status %)) rows)))))

;; ── Reflect ───────────────────────────────────────────────────────────────

(defn reflect [hyps]
  (mapv (fn [h]
          (assoc h :h/risk
                 (case (:h/kind h)
                   :probe    :low        ; 対象コードを触らない。読むだけ
                   :retract  :medium     ; 公開 API が減る。下流が壊れうる
                   :complete :medium
                   :implement :high      ; 幾何カーネルの新規実装
                   :build    :high
                   :measure  :low)))
        hyps))

;; ── Rank: Elo ─────────────────────────────────────────────────────────────
;; 勝敗規則（決定論的）:
;;   1. gain が大きい方が勝つ
;;   2. 同点なら effort が小さい方
;;   3. なお同点なら「嘘を消す」方（retract/implement）が「機能を足す」方に勝つ
;;   4. なお同点なら id 順（安定性のため）

(defn- expected [ra rb] (/ 1.0 (+ 1.0 (Math/pow 10.0 (/ (- rb ra) 400.0)))))

(defn- truth-repair? [h] (contains? #{:retract :implement :probe} (:h/kind h)))

(defn- bout [a b]
  (cond
    (> (:h/gain a) (:h/gain b)) :a
    (< (:h/gain a) (:h/gain b)) :b
    (< (effort-rank (:h/effort a)) (effort-rank (:h/effort b))) :a
    (> (effort-rank (:h/effort a)) (effort-rank (:h/effort b))) :b
    (and (truth-repair? a) (not (truth-repair? b))) :a
    (and (truth-repair? b) (not (truth-repair? a))) :b
    (neg? (compare (:h/id a) (:h/id b))) :a
    :else :b))

(defn rank [hyps]
  (let [ids (mapv :h/id hyps)
        by-id (into {} (map (juxt :h/id identity) hyps))
        init (zipmap ids (repeat 1200.0))
        ratings (reduce (fn [rt [i j]]
                          (let [a (by-id i) b (by-id j) ra (rt i) rb (rt j)
                                sa (if (= :a (bout a b)) 1.0 0.0)]
                            (-> rt (update i + (* 32 (- sa (expected ra rb))))
                                (update j + (* 32 (- (- 1.0 sa) (expected rb ra)))))))
                        init
                        (for [i ids j ids :when (neg? (compare i j))] [i j]))]
    (->> hyps
         (map #(assoc % :h/elo (Math/round (ratings (:h/id %)))))
         (sort-by (juxt (comp - :h/elo) (comp - :h/gain) :h/id))
         vec)))

;; ── Evolve ────────────────────────────────────────────────────────────────
;; 今回出荷する束 = 低リスクだけ。高リスク（幾何カーネルの新規実装）を同じ反復に
;; 混ぜない —— 混ぜると「反復が終わらない」か「混ぜた分が全部未着地で終わる」。

(defn evolve [ranked]
  {:batch/id "threed-kaizen-1"
   :batch/members (mapv :h/id (filter #(= :low (:h/risk %)) ranked))
   :batch/rationale "低リスク（対象コードを触らない probe と測定修復）だけを 1 反復で出荷する。
hollow の実装と absent の新規構築は高リスクで、同じ反復に混ぜると全部が未着地で終わる。"})

;; ── Meta ──────────────────────────────────────────────────────────────────

(defn -main []
  (when-not (fs/existsSync parity-file)
    (refuse! (str "測定が無い: " parity-file " —— 先に scripts/threed-maturity-audit.cljs を回す")))
  (let [all (edn/read-string (fs/readFileSync parity-file "utf8"))
        rows (filterv :parity/axis all)
        cov (first (filter :coverage/kind all))
        _ (when (empty? rows) (refuse! "測定 0 件"))
        total-w (reduce + (map :parity/weight rows))
        working-w (reduce + (map :parity/weight (filter #(= ":working" (:parity/status %)) rows)))
        hollow-w (reduce + (map :parity/weight (filter #(= ":hollow" (:parity/status %)) rows)))
        unverified-w (reduce + (map :parity/weight (filter #(= ":declared" (:parity/status %)) rows)))
        score (* 100.0 (/ working-w total-w))
        hyps (-> rows generate reflect rank)
        batch (evolve hyps)
        n (let [i (.indexOf argv "--n")]
            (if (neg? i) (inc (count (filter #(re-find #"^iteration-\d+\.edn$" %)
                                             (if (fs/existsSync out-dir) (fs/readdirSync out-dir) []))))
                (js/parseInt (nth argv (inc i)))))
        projected (+ score (* 100.0 (/ (reduce + (map :h/gain (filter #(some #{(:h/id %)} (:batch/members batch)) hyps))) total-w)))
        doc {:iteration/n n
             :iteration/dataset "threed-parity"
             :iteration/judge "scripts/threed-maturity-audit.cljs（決定論的。LLM を使わない）"
             :iteration/axes (count rows)
             :iteration/weight-total total-w
             :iteration/score-working-pct score
             :iteration/weight-hollow hollow-w
             :iteration/weight-unverified unverified-w
             :iteration/coverage (select-keys cov [:coverage/axes-unmeasurable
                                                   :coverage/axes-with-behavior-probe
                                                   :coverage/scanned-files :coverage/resolved-repos])
             :iteration/note (str "score は「behavior probe が通った軸の重み / 全重み」。"
                                  "**:declared（未検証）を分子に入れていない** —— 入れると "
                                  (Math/round (* 100.0 (/ (+ working-w unverified-w) total-w)))
                                  "% になるが、それは測っていないものを緑にした数字である。")
             :iteration/roadmap (mapv #(select-keys % [:h/id :h/axis :h/kind :h/effort :h/risk
                                                       :h/elo :h/gain :h/change :h/why :h/weight])
                                      hyps)
             :iteration/batch batch
             :iteration/projected-score projected}]
    (fs/mkdirSync out-dir #js {:recursive true})
    (fs/writeFileSync (path/join out-dir (str "iteration-" (if (< n 10) (str "0" n) n) ".edn"))
                      (str ";; 生成物。手で編集しない。再生成: nbb scripts/threed-coscientist.cljs\n"
                           ";; 入力は 90-docs/maturity/threed-parity.datoms.edn（tree からの実測）。\n"
                           ";; judge は LLM ではない —— 同じ入力なら同じ順位が出る。\n\n"
                           (pr-str doc) "\n"))
    (println (str "ITERATION\t" n "\taxes=" (count rows) "\tweight=" total-w))
    (println (str "SCORE\t" (.toFixed score 1) "%\t(working 重み " working-w "/" total-w
                  ")\thollow=" hollow-w "\tunverified=" unverified-w))
    (println (str "HYPOTHESES\t" (count hyps) "\tbatch=" (count (:batch/members batch)) "（低リスクのみ）"))
    (println)
    (println "順位  id     Elo   gain 労力 risk    軸")
    (doseq [h (take 18 hyps)]
      (println (str "  " (pad (:h/id h) 7) (pad (:h/elo h) 6) (pad (.toFixed (:h/gain h) 1) 5)
                    (pad (name (:h/effort h)) 5) (pad (name (:h/risk h)) 8)
                    (:h/axis h) "  [" (name (:h/kind h)) "]")))))

(-main)
