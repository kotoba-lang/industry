#!/usr/bin/env nbb
;; verify-printed-value-identity.cljs — 「印字した値」を durable な identity に
;; している形の検出。
;;
;;   nbb scripts/verify-printed-value-identity.cljs [<dir>] [--findings]
;;
;; ## なぜ要るか
;;
;; ADR-2609107000 決定 1 は「面ごとの envelope は各自のもの、値は 1 つの value
;; model を通す」と決めたが、**それを確かめるものが無い**（同 ADR の
;; "Nothing here is a gate" が自分でそう書いている）。この検出器はその半分 ——
;; 最も安く壊れる方の半分 —— を機械で見る。
;;
;; 壊れ方は実在の記録がある。ADR-2609076000 が payload v1 について書いている:
;;
;;   payload v1 hashed `(pr-str canonical-edn)` while labelling the CID
;;   dag-cbor, which was wrong on both halves.
;;
;; 印字を identity にすると、identity が **printer の振る舞い**に依存する:
;;
;;   * map の並び順。8 entry を超えると Clojure の map は array-map から
;;     hash-map に変わり、`pr-str` の並びが変わる（この workspace は既に
;;     `css.core/declarations` で実測している）。
;;   * float の印字。`1.0` / `1` / `1.0E0` は runtime と桁で変わる。
;;   * i64。JS の Number は 2^53 を超えると印字が変わる。
;;   * keyword と string。`:a` と `"a"` は印字を経由すると復元できない
;;     （`ipld.value` の docstring がこの 1 点のために存在する）。
;;
;; **どれも例外を投げない。** 値は印字でき、digest は計算でき、CID は返る。
;; 違うのは「同じ値が別のアドレスを持つ」ことだけで、それは次に読むときまで
;; 音を立てない。
;;
;; ## 何を検出するか
;;
;;   A. printed-then-addressed — digest / address を名乗る呼び出しが、
;;      `pr-str` / `prn-str` / `print-str` / `with-out-str` を直接包んでいる。
;;   B. threaded-print-to-address — 1 つの `->` / `->>` の中で、印字が先に
;;      現れ、そのあとに digest / address を名乗る語が現れる。
;;
;; ## これは verdict ではない
;;
;; **印字を hash すること自体は誤りではない。** 印字したテキストそのものが
;; 成果物なら（ソースファイル、ログ行、人が読む書類）、その digest は正しい。
;; 誤りになるのは、**値**が成果物なのに印字を経由したときだけ。したがって
;; finding は「ここを測れ —— 印字が成果物か、値が成果物か」であって
;; 「ここが壊れている」ではない。`verify-codec-seq-expansion` と同じ約束。
;;
;; ## exit code は三値
;;
;;   0  走査して、該当が無かった
;;   1  該当があった
;;   2  **答えられなかった** —— self-check に失敗した、走査対象が 0 件だった、
;;      読めないファイルがあった、または対象ディレクトリが無い。
;;      検査できなかった実行が「異常なし」と同じ値を返してはならない
;;      （ADR-2608136000）。

(require '["node:fs" :as fs]
         '["node:path" :as path]
         '[clojure.string :as str])

(def source-ext #{".clj" ".cljc" ".cljs"})

(def printers
  "値をテキストにする呼び出し。`str` は入れない —— 文字列連結が大半で、
   広げた瞬間に findings が「珍しいものの一覧」に変わる。"
  ["pr-str" "prn-str" "print-str" "with-out-str"])

(def address-words
  "呼び出し名がこれを含むなら、その結果は identity として使われている。"
  ["sha256" "sha-256" "sha512" "blake" "digest" "checksum" "fingerprint"
   "multihash" "content-address" "content-cid" "value-cid" "cid" "etag"])

;; ── 形の判定（純関数。self-check がこれを直接叩く） ────────────────────────

(defn top-level-forms
  "`src` を top-level form 単位に割る。括弧の対応だけを見る粗い分割で、文字列と
   行コメントの中の括弧は数えない。パースではないので壊れたソースでも止まらない。"
  [src]
  (loop [i 0 depth 0 start 0 in-str? false in-cmt? false esc? false out []]
    (if (>= i (count src))
      (if (< start (count src)) (conj out (subs src start)) out)
      (let [c (nth src i)]
        (cond
          esc?      (recur (inc i) depth start in-str? in-cmt? false out)
          in-str?   (cond (= c \\) (recur (inc i) depth start true in-cmt? true out)
                          (= c \") (recur (inc i) depth start false in-cmt? false out)
                          :else    (recur (inc i) depth start true in-cmt? false out))
          in-cmt?   (recur (inc i) depth start false (not= c \newline) false out)
          (= c \\)  (recur (+ i 2) depth start false false false out)
          (= c \;)  (recur (inc i) depth start false true false out)
          (= c \")  (recur (inc i) depth start true false false out)
          (= c \()  (recur (inc i) (inc depth) (if (zero? depth) i start) false false false out)
          (= c \))  (let [d (dec depth)]
                      (if (zero? d)
                        (recur (inc i) 0 (inc i) false false false (conj out (subs src start (inc i))))
                        (recur (inc i) d start false false false out)))
          :else     (recur (inc i) depth start false false false out))))))

(defn- address-name?
  "その語が identity を名乗っているか。`sym` は namespace 修飾を含んでよい。"
  [sym]
  (let [s (str/lower-case sym)]
    (boolean (some #(str/includes? s %) address-words))))

(defn- head-at
  "`form` の位置 `i` にある `(` の直後の語（呼び出し名）。無ければ nil。"
  [form i]
  (let [n (count form)]
    (loop [j (inc i) out []]
      (if (or (>= j n)
              (contains? #{\space \newline \tab \( \) \[ \] \{ \}} (nth form j)))
        (when (seq out) (apply str out))
        (recur (inc j) (conj out (nth form j)))))))

(defn printed-then-addressed
  "A: identity を名乗る呼び出しが printer を直接包んでいる位置の列。
   `(sha256 (pr-str v))` は該当、`(sha256 (canonical-bytes v))` は非該当。"
  [form]
  (let [n (count form)]
    (loop [i 0 out []]
      (if-let [i (str/index-of form "(" i)]
        (let [head (head-at form i)
              out (if (and head (address-name? head))
                    ;; 直後の引数が printer 呼び出しか。空白を跨いで 1 つだけ見る。
                    (let [after (subs form (+ i 1 (count head)) (min n (+ i 1 (count head) 40)))
                          trimmed (str/triml after)]
                      (if (some #(str/starts-with? trimmed (str "(" %)) printers)
                        (conj out i)
                        (if (some #(str/starts-with? trimmed (str "(" "clojure.core/" %)) printers)
                          (conj out i)
                          out)))
                    out)]
          (recur (inc i) out))
        out))))

(defn thread-steps
  "`->` / `->>` form の、head を除いた**段**の列。各段は bare symbol か 1 つの
   括弧付き step。文字列は既に空白化されているので、括弧の対応だけを見る。"
  [form]
  (let [n (count form)
        head (or (head-at form 0) "")
        start (+ 1 (count head))]
    (loop [i start depth 0 cur [] out []]
      (if (>= i n)
        (let [out (if (seq cur) (conj out (apply str cur)) out)]
          ;; 最後の `)` は form 自身の閉じなので落とす
          (vec (remove str/blank? out)))
        (let [c (nth form i)]
          (cond
            (contains? #{\( \[ \{} c) (recur (inc i) (inc depth) (conj cur c) out)
            (contains? #{\) \] \}} c) (if (zero? depth)
                                        (let [out (if (seq cur) (conj out (apply str cur)) out)]
                                          (vec (remove str/blank? out)))
                                        (recur (inc i) (dec depth) (conj cur c) out))
            (and (zero? depth) (contains? #{\space \newline \tab \,} c))
            (recur (inc i) depth [] (if (seq cur) (conj out (apply str cur)) out))
            :else (recur (inc i) depth (conj cur c) out)))))))

(defn- step-name
  "その段が呼ぶもの。bare symbol ならそれ自身、括弧付きなら head。"
  [step]
  (if (str/starts-with? step "(") (or (head-at step 0) "") step))

(defn threaded-print-to-address
  "B: 1 つの `->` / `->>` の中で、**印字する段**より後に **identity を名乗る段**が
   ある位置の列。`(-> v pr-str sha256)` は該当。

   段で見るのは、窓で語を拾うと巻き込むから —— 実測 2026-09-10、
   `(-> (js/Promise.all [... (pr-str payload) ...]) (.then ...))` の 400 字窓に
   別件の `plaintext-digest` が入って偽陽性になった。そこでは印字したテキスト
   自体が成果物で、digest は無関係な近所の変数だった。"
  [form]
  (let [n (count form)]
    (loop [i 0 out []]
      (if-let [i (str/index-of form "(" i)]
        (let [head (head-at form i)
              out (if (contains? #{"->" "->>" "some->" "some->>"} head)
                    (let [;; この `(` から始まる 1 form を取り出す
                          sub (first (top-level-forms (subs form i)))
                          steps (map step-name (thread-steps (or sub "")))
                          idx (first (keep-indexed (fn [k s] (when (some #(= s %) printers) k)) steps))]
                      (if (and idx (some address-name? (drop (inc idx) steps)))
                        (conj out i)
                        out))
                    out)]
          (recur (inc i) out))
        out))))

(defn blank-strings
  "文字列リテラルと行コメントの中身を、長さと改行を保ったまま空白に置き換える。

   これが無いと、**検出器は自分の docstring と自分の fixture を検出する** ——
   実測 2026-09-10、このファイル自身で 4 件（初回実行）。同じことが、例を
   docstring に書いている全ての正しいコードで起きる。位置を保つのは、行番号を
   ずらさないため。"
  [src]
  (let [n (count src)]
    (loop [i 0 in-str? false in-cmt? false esc? false out (vec src)]
      (if (>= i n)
        (apply str out)
        (let [c (nth src i)
              blank (fn [v] (if (= c \newline) v (assoc v i \space)))]
          (cond
            esc?     (recur (inc i) in-str? in-cmt? false (blank out))
            in-str?  (cond (= c \\) (recur (inc i) true in-cmt? true (blank out))
                           (= c \") (recur (inc i) false in-cmt? false out)
                           :else    (recur (inc i) true in-cmt? false (blank out)))
            in-cmt?  (recur (inc i) false (not= c \newline) false (blank out))
            ;; 文字リテラル。`\"` を「文字列の開始」と読むと、そこから状態が
            ;; 反転して以降のファイル全部が逆になる —— 実測 2026-09-10、この
            ;; 検出器自身がそれで自分の docstring を 4 件報告した。
            (= c \\) (recur (+ i 2) false false false out)
            (= c \;) (recur (inc i) false true false (blank out))
            (= c \") (recur (inc i) true false false out)
            :else    (recur (inc i) false false false out)))))))

(defn findings-in
  "1 ファイルの該当。`{:rule :a|:b :line n}` の列。"
  [src]
  (let [blanked (blank-strings src)]
   (loop [forms (top-level-forms blanked) pos 0 out []]
    (if-let [form (first forms)]
      (let [at (str/index-of blanked form pos)
            base (inc (count (re-seq #"\n" (subs blanked 0 (or at 0)))))
            line-of (fn [off] (+ base (count (re-seq #"\n" (subs form 0 off)))))
            out (-> out
                    (into (map #(hash-map :rule :printed-then-addressed :line (line-of %))
                               (printed-then-addressed form)))
                    (into (map #(hash-map :rule :threaded-print-to-address :line (line-of %))
                               (threaded-print-to-address form))))]
        (recur (rest forms) (+ (or at pos) (count form)) out))
      out))))

;; ── self-check ────────────────────────────────────────────────────────────
;;
;; 起動のたびに、既知の不良で検出し、既知の正常で 0 になることを示してから走る。
;; 黙って何も見つけなくなった検出器は、clean と同じ値を返してはならない。

(def known-bad-a
  "payload v1 の形そのもの（ADR-2609076000 が記録している）"
  "(defn definition-cid [value]
     (multihash/sha256 (pr-str (canonical-edn value))))")

(def known-bad-b
  "同じ誤りを threading で書いたもの"
  "(defn address [value]
     (-> value pr-str sha256-hex))")

(def known-good
  "値を value codec に通す形と、印字そのものが成果物である形。どちらも非該当。"
  "(defn definition-cid [value]
     (codec/value-cid value))
   (defn block-cid [value]
     (mf/cidv1-dag-cbor (cbor/encode (normalize value))))
   (defn render [digest]
     (-> digest pr-str))
   (defn log-line [event]
     (println (pr-str event)))")

(defn self-check!
  "検出器が discriminate できることの証明。できなければ nil。"
  []
  (let [a (findings-in known-bad-a)
        b (findings-in known-bad-b)
        g (findings-in known-good)]
    (when (and (some #(= :printed-then-addressed (:rule %)) a)
               (some #(= :threaded-print-to-address (:rule %)) b)
               (empty? g))
      {:bad-a (count a) :bad-b (count b) :good (count g)})))

;; ── 走査 ──────────────────────────────────────────────────────────────────

(defn- walk [dir]
  (let [skip #{"node_modules" ".git" ".shadow-cljs" "out" "dist" "target"
               ".cpcache" ".gitlibs" "public" "test" "tests"}]
    (letfn [(go [d]
              (let [ents (try (fs/readdirSync d #js {:withFileTypes true}) (catch :default _ #js []))]
                (mapcat (fn [e]
                          (let [n (.-name e) p (path/join d n)]
                            (cond (.isDirectory e) (if (skip n) [] (go p))
                                  (source-ext (path/extname n)) [p]
                                  :else [])))
                        (array-seq ents))))]
      (go dir))))

(defn- finding! [severity key message]
  (println (str "FINDING\t" severity "\t" key "\t" message)))

(defn -main [& args]
  (let [findings? (some #{"--findings"} args)
        root (or (first (remove #(str/starts-with? % "--") args)) ".")]
    (if-not (self-check!)
      (do (println "REFUSED\tself-check failed; a detector that cannot discriminate must not report clean")
          (js/process.exit 2))
      (if-not (try (.isDirectory (fs/statSync root)) (catch :default _ false))
        (do (println (str "REFUSED\tno such directory: " root))
            (js/process.exit 2))
        (let [files (walk root)
              hits (->> files
                        (mapcat (fn [p]
                                  (let [src (try (fs/readFileSync p "utf8") (catch :default _ nil))]
                                    (if (nil? src)
                                      [{:file p :rule :unreadable :line 0}]
                                      (map #(assoc % :file p) (findings-in src))))))
                        vec)
              unreadable (count (filter #(= :unreadable (:rule %)) hits))
              real (remove #(= :unreadable (:rule %)) hits)]
          (println (str "SCANNED\t" (count files)))
          (when (str/includes? root "orgs")
            (println "NOTE\tcheckouts only; unchecked-out west projects are not scanned"))
          (when (pos? unreadable) (println (str "UNREADABLE\t" unreadable)))
          (cond
            (zero? (count files))
            (do (println "REFUSED\tno source found; a scan of nothing is not a clean result")
                (js/process.exit 2))

            (pos? unreadable)
            (do (println "REFUSED\tsome candidates could not be read; unread is not clean")
                (js/process.exit 2))

            (seq real)
            (do (if findings?
                  (doseq [[[rule file] group] (sort-by key (group-by (juxt :rule :file) real))]
                    (finding! "warn" (str (name rule) ":" file)
                              (str (name rule) " at " file " line(s) "
                                   (str/join "," (sort (map :line group)))
                                   " -- measure it: is the PRINTED TEXT the artifact, or is the VALUE?"
                                   " If the value, the address depends on printer behaviour"
                                   " (map order, float spelling, keyword vs string) and two equal"
                                   " values can get two addresses without anything throwing.")))
                  (doseq [{:keys [file rule line]} (sort-by (juxt :file :line) real)]
                    (println (str (name rule) "\t" file ":" line))))
                (println (str "FINDINGS\t" (count real)))
                (when-not findings?
                  (println "a printed value is a fine artifact; a printed value used as an identity is not"))
                (js/process.exit 1))

            :else
            (do (println "CLEAN\t0") (js/process.exit 0))))))))

(apply -main (vec *command-line-args*))
