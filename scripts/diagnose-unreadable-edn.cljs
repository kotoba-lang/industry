#!/usr/bin/env nbb
;; diagnose-unreadable-edn — reader を通らない 90-docs の文書について、
;; **何がどう壊れているか**を特定する。閉じ括弧の欠落だけが原因なら直す。
;;
;; ## 何が壊れているか
;;
;; `90-docs/` の一部の文書は `[{ :key ... }]` の tx-data 形だが、値として置かれた
;; サブマップが閉じられていない。実測（2026-08-07、`90-docs/deployment/
;; MONTH-3-GRACEFUL-RUST-DRAIN.edn`）: 外側キーごとの括弧深さが
;; 3 → 4 → 5 → 6 → 7 → 8 → 8 → 9 と単調に増えており、`:drain/*` の各サブマップが
;; 1 つも閉じられていない。最後の `]` が外側 map を閉じようとして
;; `Unmatched delimiter ].` になる。
;;
;; **この文書は reader を通らないので、datom 面から完全に不可視**である。存在する
;; のに、どの query にも出てこない。
;;
;; ## 修復の方針と、その検証
;;
;; インデント 2 の外側キー（`^  :`）は tx-data entity の直下、すなわち深さ 2
;; （`[` と `{`）に居るはずである。そこより深ければ、その分だけサブマップが
;; 閉じられていない。**外側キーの直前に不足分の `}` を入れる**。
;;
;; 括弧の数合わせは、間違えると内容を静かに別の入れ子へ移す。だから修復後に
;; **元ファイルの外側キー集合と、修復後に実際に読めた entity のキー集合が一致する
;; ことを検査する**。一致しなければ書かない。「parse が通った」は「正しく直った」
;; ではない。
;;
;; **2026-08-08 実測: 現在の 7 件はどれも --write の条件を満たさない。** どれも
;; 閉じ括弧の欠落に加えて、生成器が「map の並び」を vector で包まずに出した第 2 の
;; バグを持っており、その再構成は判断を要する。この道具が答えるのは「直せるか」
;; ではなく「**なぜ直せないか**」で、それが分かることに意味がある —— docs-edn-only.cljs
;; の known-parse-errors 注記によれば、以前にも同じ 2 つの手当てが試みられて同じ所で止まっている。
;;
;; usage: nbb scripts/diagnose-unreadable-edn.cljs <file>... [--write]
;;        --write が無ければ dry-run（差分の要約だけ出す）

(require '[clojure.string :as str])

(def fs (js/require "node:fs"))

(defn- scan-line
  "1 行を走査して [depth in-string? escaped?] を進める。"
  [line [d0 s0 e0]]
  (reduce (fn [[d s e] c]
            (cond
              e [d s false]
              (and s (= c "\\")) [d s true]
              (= c "\"") [d (not s) false]
              s [d s false]
              (contains? #{"[" "{" "("} c) [(inc d) s false]
              (contains? #{"]" "}" ")"} c) [(dec d) s false]
              :else [d s false]))
          [d0 s0 e0] (seq line)))

(def ^:private outer-key-re #"^  (:[A-Za-z][A-Za-z0-9._/-]*)")

(defn- outer-keys
  "元ファイルが意図している外側キーの並び（文字列内の擬似キーは拾わない）。"
  [lines]
  (loop [ls lines st [0 false false] ks []]
    (if (empty? ls)
      ks
      (let [line (first ls)
            in-str? (second st)
            m (when-not in-str? (re-find outer-key-re line))]
        (recur (rest ls) (scan-line line st) (if m (conj ks (second m)) ks))))))

(defn- repair-lines
  "外側キー行の直前で深さを 2 に戻す `}` を挿入する。行末に足すのは、
   キー行そのものを触らずに済むため（diff が読みやすい）。"
  [lines]
  (loop [ls lines st [0 false false] out []]
    (if (empty? ls)
      ;; EOF: 残った深さを閉じる。top-level の `[` `{` は元から閉じられているので
      ;; ここで足すのはサブマップ分だけ。
      out
      (let [line (first ls)
            [d in-str? _] st
            m (when-not in-str? (re-find outer-key-re line))
            need (when m (- d 2))
            out' (if (and need (pos? need))
                   ;; 直前の非空行の末尾に `}` を足す
                   (let [i (last (keep-indexed (fn [i l] (when-not (str/blank? l) i)) out))]
                     (if i
                       (assoc out i (str (nth out i) (apply str (repeat need "}"))))
                       out))
                   out)
            ;; 挿入した `}` の分だけ深さを補正してから、この行を走査する
            st' (scan-line line [(if (and need (pos? need)) 2 d) in-str? false])]
        (recur (rest ls) st' (conj out' line))))))

(defn- close-tail
  "最終行の `}]` より前で閉じ切れていない分を、最終行の直前に足す。"
  [lines]
  (let [depth-before-last (first (reduce (fn [st l] (scan-line l st))
                                          [0 false false] (butlast lines)))
        ;; 最終行は `…]}]` のような形。閉じ切るのに必要な `}` を、その直前の
        ;; 非空行の末尾に足す。
        need (- depth-before-last 2)]
    (if (pos? need)
      (let [i (last (keep-indexed (fn [i l] (when-not (str/blank? l) i))
                                  (vec (butlast lines))))]
        (conj (assoc (vec (butlast lines)) i
                     (str (nth (vec (butlast lines)) i) (apply str (repeat need "}"))))
              (last lines)))
      lines)))

(defn- entity-keys [text]
  (try
    (let [tx (cljs.reader/read-string text)]
      (when (and (vector? tx) (map? (first tx)))
        (set (map str (keys (first tx))))))
    (catch :default _ nil)))

(defn repair-file! [path write?]
  (let [raw (.readFileSync fs path "utf8")
        lines (vec (str/split-lines raw))
        before (entity-keys raw)]
    (if before
      (println (str "  SKIP " path " — 既に読める（" (count before) " キー）"))
      (let [intended (set (outer-keys lines))
            fixed (str/join "\n" (close-tail (repair-lines lines)))
            fixed (if (str/ends-with? raw "\n") (str fixed "\n") fixed)
            after (entity-keys fixed)]
        (cond
          (nil? after)
          (println (str "  FAIL " path "\n        括弧は閉じたが、まだ読めない: "
                        (try (do (cljs.reader/read-string fixed) "?")
                             (catch :default e (ex-message e)))
                        "\n        → 閉じ括弧の欠落とは別のバグ。**内容の再構成は判断を要するので"
                        "ここでは直さない。**"))

          (not= intended after)
          (println (str "  FAIL " path " — キー集合が変わった。書かない。\n"
                        "        意図 " (count intended) " / 修復後 " (count after)
                        "\n        欠落: " (pr-str (sort (remove after intended)))
                        "\n        余分: " (pr-str (sort (remove intended after)))))

          :else
          (do
            (when write? (.writeFileSync fs path fixed))
            (println (str "  " (if write? "FIXED" "OK(dry-run)") " " path
                          " — " (count after) " キーを保って読めるようになった"))))))))

(defn classify-file
  "1 ファイルの状態を **機械可読** で返す。`docs-edn-repair-tick` が消費する。
   人間向けの印字と同じ判定を 2 箇所に書かないため、判定はここだけに置く。

   :already-readable      reader を通る（修復不要）
   :closers-only          閉じ括弧を足すだけで読め、キー集合も保たれる
   :needs-reconstruction  括弧を閉じてもまだ読めない（第 2 のバグがある）
   :key-set-changed       読めるようになったがキー集合が変わった（危険。書かない）"
  [path]
  (let [raw (.readFileSync fs path "utf8")
        lines (vec (str/split-lines raw))]
    (if (entity-keys raw)
      {:path path :status :already-readable}
      (let [intended (set (outer-keys lines))
            fixed (str/join "\n" (close-tail (repair-lines lines)))
            after (entity-keys fixed)]
        (cond
          (nil? after)
          {:path path :status :needs-reconstruction
           :reason (try (do (cljs.reader/read-string fixed) "?")
                        (catch :default e (ex-message e)))
           :intended-keys (count intended)}

          (not= intended after)
          {:path path :status :key-set-changed
           :missing (vec (sort (remove after intended)))
           :extra (vec (sort (remove intended after)))}

          :else
          {:path path :status :closers-only :keys (count after)})))))

(defn -main [& args]
  (let [write? (boolean (some #{"--write"} args))
        edn-out? (boolean (some #{"--edn"} args))
        paths (remove #(str/starts-with? % "--") args)]
    (when edn-out?
      (println (pr-str (mapv classify-file paths)))
      (js/process.exit 0))
    (when (empty? paths)
      (println "usage: nbb scripts/diagnose-unreadable-edn.cljs <file>... [--write]")
      (js/process.exit 2))
    (println (str "diagnose-unreadable-edn (" (if write? "WRITE" "dry-run") "): "
                  (count paths) " ファイル"))
    (doseq [p paths] (repair-file! p write?))))

(apply -main (drop 3 (js->clj js/process.argv)))
