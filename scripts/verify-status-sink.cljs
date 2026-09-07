#!/usr/bin/env nbb
;; verify-status-sink.cljs — `(* 0 <call>)`: a status multiplied away to force
;; evaluation order, classified by what it sinks.
;;
;;   nbb --classpath ".:scripts/nbb_compat" scripts/verify-status-sink.cljs [<dir>] [--findings]
;;
;; ## なぜ要るか
;;
;; Kotoba のカーネル / ドライバ source は、store を順序付けるためにこう書く:
;;
;;     (let [stored (kernel-store-u64-4k tcb 4096 0 state)
;;           status (kernel-store-u64-4k tcb 4096 112 (tcb-status state))]
;;       (if (= (+ stored (* 0 status)) state) ...))
;;
;; `(* 0 status)` は status を評価させ、値を捨てる。store の戻りを捨てるのは
;; 順序付けの代償として引き受けられているが、**同じ形が load の結果や poll の
;; 結果を捨てているとき、それは読み戻し検査が無いことと同じ値になる**
;; （ADR-2608136000 の形: 測れなかった検査が、測って問題が無かった検査と同じ
;; 値を返す）。ソースの中で `(* 0 x)` は全部同じ顔をしていて、何を捨てたかは
;; x の産地まで辿らないと分からない。この検出器はその辿りを機械でやる。
;;
;; ## これは欠陥一覧ではない
;;
;; aiueos E8 は tautological な比較を外したあと、この idiom で store を意図的に
;; 順序付けている（k16-stream branch、commit "129 -> 0"）。そこでの件数は
;; **日付付きの受け入れ済み baseline** であって直すべき欠陥の数ではない
;; （registry の `:accepted` がそう言う）。この検出器の仕事は、全部の sink を
;; **見える・数えられる**状態にし、**何を捨てたか**（class）を site ごとに
;; 名指しすることである。class が `load-result` / `poll-result` の site は
;; severity warn、`store-return` / `other` は info。
;;
;; ## 何を検出するか
;;
;;   `(* 0 <operand>...)` を全部拾い、operand ごとに:
;;     - 数値 literal                       → literal（sink ではない。`(* 0 0)`）
;;     - call `(head ...)`                  → head を産地とする
;;     - 結合子 `+ - * quot bit-* if when do let cond` の call
;;                                          → その中の operand を再帰的に辿る
;;     - 素の local                         → 同じ top-level form の中で、行頭に
;;                                            在る `let`/`loop` 束縛 `sym (init)` を
;;                                            後ろから探し、init を辿る（深さ 6）
;;     - 束縛が見つからない local（多くは parameter）→ unresolved-local（sink ではない）
;;
;;   産地 head の class は token（`-` `/` 区切り）で決める。heuristic であって
;;   真理ではない —— 報告するのは「この site はこの class の値を捨てた」という
;;   読みで、そこを測れと言っている:
;;     load-result   load in read readback limb peek get cpuid rdtsc xgetbv rdmsr
;;     poll-result   wait poll drain spin until pause
;;     store-return  store put out write fill zero copy flush log mark xsetbv wrmsr
;;     other         それ以外
;;   site の class は産地全部の class の集合。severity は最悪 class で決まる。
;;
;;   コメント（`;` 以降）と文字列の中身は走査しない。
;;
;; ## exit code は三値
;;
;;   0  走査して、sink が無かった
;;   1  sink があった（FINDING は checkout ごと 1 本、SINK 行は site ごと）
;;   2  **答えられなかった** —— self-check 失敗、`.kotoba` が 0 件、対象が無い、
;;      読めないファイルがあった

(require '["node:fs" :as fs]
         '["node:path" :as path]
         '[clojure.edn :as edn]
         '[clojure.string :as str])

(def kotoba-ext #{".kotoba"})

(def combiners
  "値を結合するだけの head。`(* 0 (+ a b))` は a と b を捨てているので、産地は
   この下に在る。"
  '#{+ - * quot rem bit-or bit-and bit-xor bit-shift-left bit-shift-right
     if when do let cond and or not not= = < > <= >= inc dec})

(def binding-heads "let loop if-let when-let")

(def class-tokens
  "産地 head の token -> class。先勝ち（load, poll, store の順）。"
  [[:load-result  #{"load" "in" "read" "readback" "limb" "peek" "get" "cpuid" "rdtsc" "xgetbv" "rdmsr"}]
   [:poll-result  #{"wait" "poll" "drain" "spin" "until" "pause"}]
   [:store-return #{"store" "put" "out" "write" "fill" "zero" "copy" "flush" "log" "mark" "xsetbv" "wrmsr"}]])

(def class-rank {:load-result 0 :poll-result 1 :store-return 2 :other 3})

(defn head-class [head]
  ;; trailing digits are widths, not words: `store32` -> store, `u8` -> u
  (let [toks (set (remove str/blank? (map #(str/replace % #"\d+$" "") (str/split (str head) #"[-/.]"))))]
    (or (some (fn [[c ts]] (when (seq (filter ts toks)) c)) class-tokens)
        :other)))

;; ── source preparation ────────────────────────────────────────────────────

(defn blank-comments-and-strings
  "同じ長さの文字列を返す。`;` 以降と文字列の中身は空白になる（位置と行番号を
   保つため）。"
  [src]
  (let [n (count src)
        out (js/Array. n)]
    (loop [i 0 mode :code]
      (when (< i n)
        (let [c (nth src i)]
          (case mode
            :code (cond (= c ";") (do (aset out i " ") (recur (inc i) :comment))
                        (= c "\"") (do (aset out i c) (recur (inc i) :string))
                        (= c "\\") (do (aset out i " ")
                                       (when (< (inc i) n) (aset out (inc i) " "))
                                       (recur (+ i 2) :code))
                        :else (do (aset out i c) (recur (inc i) :code)))
            :comment (if (= c "\n")
                       (do (aset out i c) (recur (inc i) :code))
                       (do (aset out i " ") (recur (inc i) :comment)))
            :string (cond (= c "\\") (do (aset out i " ")
                                         (when (< (inc i) n) (aset out (inc i) " "))
                                         (recur (+ i 2) :string))
                          (= c "\"") (do (aset out i c) (recur (inc i) :code))
                          (= c "\n") (do (aset out i c) (recur (inc i) :string))
                          :else (do (aset out i " ") (recur (inc i) :string)))))))
    (.join out "")))

(defn- line-of [src idx]
  (inc (count (re-seq #"\n" (subs src 0 idx)))))

(defn balanced-end
  "`idx` の開き括弧に対応する閉じ括弧の次の位置。閉じなければ nil。"
  [b idx]
  (let [n (count b)]
    (loop [i idx depth 0]
      (if (>= i n)
        nil
        (let [c (nth b i)]
          (cond (or (= c "(") (= c "[") (= c "{")) (recur (inc i) (inc depth))
                (or (= c ")") (= c "]") (= c "}")) (if (= depth 1) (inc i) (recur (inc i) (dec depth)))
                :else (recur (inc i) depth)))))))

(defn- read-form
  "blanked 上で括弧を数え、原文からその範囲を EDN として読む。読めなければ nil。"
  [src b idx]
  (when-let [end (balanced-end b idx)]
    (try (edn/read-string (subs src idx end))
         (catch :default _ nil))))

(defn- top-level-start
  "`idx` を含む top-level form の先頭（行頭の `(`）。"
  [b idx]
  (let [i (str/last-index-of b "\n(" idx)]
    (if (nil? i) 0 (inc i))))

(defn- re-escape [s]
  (str/replace s #"[.*+?^${}()|\[\]\\/]" "\\$&"))

(defn- number-token? [s] (boolean (re-matches #"-?\d+(\.\d+)?" s)))

(declare producer-heads)

(defn- resolve-local
  "`sym` の束縛を、[top, idx) の中で行頭パターンで後ろから探す。産地 head の列
   （見つからなければ [:unresolved]）。"
  [src b top idx sym depth visited]
  (if (or (> depth 6) (visited sym))
    [:unresolved]
    (let [seg (subs b top idx)
          re (js/RegExp. (str "(?:^|\\n)[^\\S\\n]*(?:\\((?:" (str/replace binding-heads " " "|")
                              ")\\s+\\[|\\[)?" (re-escape (str sym)) "[^\\S\\n]+(\\S)") "g")
          last-m (loop [m (.exec re seg) acc nil]
                   (if m (recur (.exec re seg) m) acc))]
      (if-not last-m
        [:unresolved]
        (let [pos (+ top (dec (+ (.-index last-m) (count (aget last-m 0)))))
              c (nth b pos)]
          (cond
            (= c "(") (if-let [form (read-form src b pos)]
                        (producer-heads src b top pos form (inc depth) (conj visited sym))
                        [:unparsed])
            :else (let [tok (re-find #"^[^\s\]\)]+" (subs b pos))]
                    (cond (nil? tok) [:unresolved]
                          (number-token? tok) []
                          (str/starts-with? tok ":") []
                          :else (resolve-local src b top pos (symbol tok) (inc depth) (conj visited sym))))))))))

(defn producer-heads
  "form が捨てる値の産地 head の列。結合子は透過し、local は束縛へ辿る。
   辿れない local は :unresolved、読めない form は :unparsed として列に残る。"
  [src b top idx form depth visited]
  (cond
    (seq? form) (let [h (first form)]
                  (cond (nil? h) []
                        (and (symbol? h) (combiners h))
                        (vec (mapcat #(producer-heads src b top idx % depth visited) (rest form)))
                        (symbol? h) [(str h)]
                        :else [(str "?" (pr-str h))]))
    (vector? form) (vec (mapcat #(producer-heads src b top idx % depth visited) form))
    (symbol? form) (resolve-local src b top idx form depth visited)
    :else []))

(def sink-re #"\(\*\s+0\s")

(defn sites-in
  "1 ファイル分。`{:line :status :classes :heads :text}` の列。status は
   :sink | :literal | :unresolved-local | :unparsed。"
  [src]
  (let [b (blank-comments-and-strings src)
        re (js/RegExp. (.-source sink-re) "g")]
    (loop [out []]
      (if-let [m (.exec re b)]
        (let [idx (.-index m)
              form (read-form src b idx)
              text (let [end (or (balanced-end b idx) (min (count src) (+ idx 60)))
                         t (str/replace (subs src idx end) #"\s+" " ")]
                     (if (> (count t) 96) (str (subs t 0 93) "...") t))
              site (if-not (seq? form)
                     {:status :unparsed :heads [] :classes #{}}
                     (let [operands (drop 2 form)
                           top (top-level-start b idx)
                           heads (vec (mapcat #(producer-heads src b top idx % 0 #{}) operands))
                           real (remove keyword? heads)
                           classes (set (map head-class real))]
                       (cond (seq real) {:status :sink :heads (vec real) :classes classes}
                             (some #{:unparsed} heads) {:status :unparsed :heads [] :classes #{}}
                             (some #{:unresolved} heads) {:status :unresolved-local :heads [] :classes #{}}
                             :else {:status :literal :heads [] :classes #{}})))]
          (recur (conj out (assoc site :line (line-of src idx) :text text))))
        out))))

(defn- worst-class [classes]
  (first (sort-by class-rank classes)))

(defn- class-label [classes]
  (str/join "+" (map name (sort-by class-rank classes))))

;; ── self-check: 5 class-bearing sinks, 1 literal, 1 parameter, comment, string ─

(def known-bad
  "(defn pci-read [bus offset]
  (let [addressed (kernel-out-u32 3320 (pci-address bus offset))]
    (+ (kernel-in-u32 3324) (* 0 addressed))))
(defn readback-check [bus]
  (let [command (pci-read bus 4)
        written (pci-write bus 4 command)
        readback (pci-read bus 4)]
    (+ 1 (* 0 (+ readback written)))))
(defn wait-loop [mmio remaining]
  (+ (wait-fifo-empty mmio (- remaining 1)) (* 0 (kernel-pause))))
(defn seq-put [ws i]
  (+ i 1 (* 0 (e-put ws i 0))))
(defn other-call [x]
  (+ 1 (* 0 (send-diagnostic x))))
(defn param-only [address]
  (+ 1 (* 0 address)))
(defn literal-only [] (+ 247 (* 0 0)))
;; (+ 1 (* 0 (kernel-load-u8 0)))
(defn in-string [] (string-length \"(* 0 (kernel-load-u8 0))\"))
")

(def known-good
  "(defn scale [x] (* 2 x))
(defn half [x] (* 0.5 x))
(defn zero-first [x] (* x 0))
")

(defn self-check! []
  (let [bad (sites-in known-bad)
        good (sites-in known-good)
        sinks (filter #(= :sink (:status %)) bad)
        n-class (fn [c] (count (filter #(contains? (:classes %) c) sinks)))
        got {:sinks (count sinks)
             :literal (count (filter #(= :literal (:status %)) bad))
             :unresolved-local (count (filter #(= :unresolved-local (:status %)) bad))
             :unparsed (count (filter #(= :unparsed (:status %)) bad))
             :load-result (n-class :load-result)
             :poll-result (n-class :poll-result)
             :store-return (n-class :store-return)
             :other (n-class :other)
             :good-forms (count good)}
        want {:sinks 5 :literal 1 :unresolved-local 1 :unparsed 0
              :load-result 1 :poll-result 1 :store-return 3 :other 1 :good-forms 0}]
    {:ok (count (filter (fn [[k v]] (= v (got k))) want)) :want (count want) :got got :expect want}))

;; ── walk ──────────────────────────────────────────────────────────────────

(defn- walk [dir]
  (let [skip #{"node_modules" ".git" ".shadow-cljs" "out" "dist" "target"
               ".cpcache" ".gitlibs" "public" "build" ".venv" "__pycache__"}]
    (letfn [(go [d]
              (let [ents (try (fs/readdirSync d #js {:withFileTypes true})
                              (catch :default e (println (str "NOTE\tunreadable-dir\t" d "\t" (.-message e))) #js []))]
                (mapcat (fn [e]
                          (let [n (.-name e) p (path/join d n)]
                            (cond (.isDirectory e) (if (skip n) [] (go p))
                                  (kotoba-ext (path/extname n)) [p]
                                  :else [])))
                        (array-seq ents))))]
      (go dir))))

(defn- repo-of
  "ファイルを含む checkout（`.git` が在る最も近い祖先）。root の外へは出ない。
   見つからなければ root。"
  [root file]
  (loop [d (path/dirname file)]
    (cond (fs/existsSync (path/join d ".git")) d
          (or (= d root) (= d (path/dirname d))) root
          (not (str/starts-with? d root)) root
          :else (recur (path/dirname d)))))

(defn- rel [root p]
  (let [r (path/relative root p)] (if (str/blank? r) "." r)))

(defn- finding! [severity key detail]
  (println (str "FINDING\t" severity "\t" key "\t" detail)))

(defn -main [& argv]
  (let [args (remove #(str/starts-with? % "--") argv)
        root (path/resolve (or (first args) "."))
        findings? (boolean (some #{"--findings"} argv))]
    (when-not (try (.isDirectory (fs/statSync root)) (catch :default e
                                                        (println (str "NOTE\tstat failed: " (.-message e)))
                                                        false))
      (println "SCANNED\t0")
      (println (str "REFUSED\tno such directory: " root))
      (js/process.exit 2))
    (let [sc (self-check!)]
      (when-not (= (:ok sc) (:want sc))
        (println "SCANNED\t0")
        (println "REFUSED\tself-check failed: the detector cannot classify the known sink shapes")
        (println (str "  got    " (pr-str (:got sc))))
        (println (str "  expect " (pr-str (:expect sc))))
        (js/process.exit 2))
      (println (str "SELF-CHECK\t" (:ok sc) "/" (:want sc))))
    (let [files (vec (walk root))
          per-file (vec (for [p files]
                          (let [src (try (fs/readFileSync p "utf8")
                                         (catch :default e
                                           (println (str "NOTE\tunreadable\t" p "\t" (.-message e))) nil))]
                            (if (nil? src)
                              {:file p :unreadable? true :sites []}
                              {:file p :sites (sites-in src)}))))
          unreadable (count (filter :unreadable? per-file))
          all-sites (vec (mapcat (fn [{:keys [file sites]}] (map #(assoc % :file file) sites)) per-file))
          by-status (group-by :status all-sites)
          sinks (vec (by-status :sink))
          n-class (fn [c] (count (filter #(contains? (:classes %) c) sinks)))]
      (println (str "SCANNED\t" (count files)))
      (when (str/includes? root "orgs")
        (println "NOTE\tcheckouts only; unchecked-out west projects are not scanned"))
      (when (pos? unreadable) (println (str "UNREADABLE\t" unreadable)))
      (cond
        (zero? (count files))
        (do (println "REFUSED\tno .kotoba source found; a scan of nothing is not a clean result")
            (js/process.exit 2))

        (pos? unreadable)
        (do (println "REFUSED\tsome .kotoba files could not be read; unread is not clean")
            (js/process.exit 2))

        :else
        (do
          (println (str "FORMS\t" (count all-sites)
                        "\tliteral=" (count (by-status :literal))
                        "\tunresolved-local=" (count (by-status :unresolved-local))
                        "\tunparsed=" (count (by-status :unparsed))))
          (doseq [{:keys [file line classes heads text]} (sort-by (juxt :file :line) sinks)]
            (println (str "SINK\t" (rel root file) ":" line "\t" (class-label classes)
                          "\t<- " (str/join " " (distinct heads)) "\t" text)))
          (println (str "BY-CLASS\tload-result=" (n-class :load-result)
                        "\tpoll-result=" (n-class :poll-result)
                        "\tstore-return=" (n-class :store-return)
                        "\tother=" (n-class :other)))
          (if (seq sinks)
            (do
              (when findings?
                (doseq [[repo group] (sort-by key (group-by #(repo-of root (:file %)) sinks))]
                  (let [files (distinct (map :file group))
                        nc (fn [c] (count (filter #(contains? (:classes %) c) group)))
                        worst (worst-class (set (mapcat :classes group)))
                        sev (if (#{:load-result :poll-result} worst) "warn" "info")]
                    (finding! sev (str "status-sink:" (rel root repo))
                              (str (rel root repo) ": " (count group) " `(* 0 <call>)` sink(s) in " (count files)
                                   " file(s) -- load-result " (nc :load-result)
                                   ", poll-result " (nc :poll-result)
                                   ", store-return " (nc :store-return)
                                   ", other " (nc :other)
                                   "; worst class " (name worst)
                                   " -- a sunk load/poll result is a read-back nobody checks; a sunk store return is sequencing; see SINK lines for file:line and class")))))
              (println (str "FINDINGS\t" (count sinks)))
              (js/process.exit 1))
            (do (println "CLEAN\t0") (js/process.exit 0))))))))

(apply -main (vec *command-line-args*))
