#!/usr/bin/env nbb
;; verify-codec-seq-expansion.cljs — 基盤 codec の中の「1 要素ずつ seq に展開して
;; から組み直す」形の検出。
;;
;;   nbb scripts/verify-codec-seq-expansion.cljs [<dir>] [--findings]
;;
;; ## なぜ要るか
;;
;; **基盤 codec の定数倍は、それを使う側の profile には現れない。**
;; 呼び出し側から見えるのは「ipld/decode が遅い」だけで、遅い理由は 2 つ下の
;; ライブラリに在る。しかも codec は正しさが最優先なので、正しく書かれた遅い
;; 実装は test を全部通り、review でも通る。
;;
;; 実測 2026-09-05（ADR-2609051700）:
;;
;;   multiformats.base32/encode は 1 バイトを 8 要素の lazy seq に展開し、
;;   partition 5 で組み直し、各群を concat/repeat/count/reduce で再構築して
;;   いた。36 バイトの CID 1 本で 288 seq 要素と 58 個の中間コレクション。
;;
;;   DAG-CBOR のリンクは全部 CID なので、ipld/decode は canonical 検証の
;;   再エンコードでリンク 1 本につき 1 回これを払う。643 リンクを持つ実在の
;;   30 KB catalog-directory ブロックで、124 ms 中 97 ms がここだった。
;;
;;   結果として api.murakumo.cloud の `/infer/queue` は、**2 バイトの空配列を
;;   返すのに 760 ms の Worker CPU** を使い、Cloudflare アカウント全体の
;;   Worker CPU の 98.5%（週 170 万 CPU 秒）を 1 本で占めていた。
;;
;; 直した後: base32/encode 23.6x、ipld/decode 124 ms -> 18.3 ms、本番の
;; `/infer/queue` 760 ms -> 285 ms。
;;
;; ## 何を検出するか
;;
;;   A. expand-then-regroup — codec 形の namespace の 1 つの top-level form が
;;      `mapcat` と `partition` の両方を含む。これが「展開してから組み直す」形。
;;   B. materialise-to-compare — 同じファイルで `(mapv ... (seq ...))` として
;;      定義された helper が、1 つの `=` の両辺で呼ばれている。バイト列の
;;      等価判定のために、両側を persistent vector に materialise する形。
;;      （io-ipld の canonical 検証がこれで、30,155 要素の vector を 2 本
;;      作っていた。）
;;
;; **どちらも「遅い」ことの証明ではない。** 検出するのは形であって計測値では
;; ないので、報告は「ここを測れ」であって「ここが遅い」ではない。速さの正本は
;; その repo の bench であり、この検出器ではない。
;;
;; ## exit code は三値
;;
;;   0  走査して、該当が無かった
;;   1  該当があった
;;   2  **答えられなかった** —— 自己検証に失敗した、走査対象が 0 件だった、
;;      または対象ディレクトリが無い。検査できなかった実行が「異常なし」と
;;      同じ値を返してはならない（ADR-2608136000）。

(require '["node:fs" :as fs]
         '["node:path" :as path]
         '[clojure.string :as str])

(def source-ext #{".clj" ".cljc" ".cljs"})

(def codec-ns-hint
  "codec 形とみなす namespace / ファイル名の断片。ここを広げるほど偽陽性が増える
   ので、**バイト列を符号化する層だけ**に留める。"
  ["base16" "base32" "base58" "base64" "basex" "hex" "varint" "leb128"
   "multihash" "multibase" "multiaddr" "cbor" "codec" "blockcodec"
   "utf8" "bitstring" "bech32" "rlp" "ipld" "dag_cbor" "dag-cbor"])

(def allowed
  "形として該当するが、測って問題ないと判断済みのパス接尾辞と理由。
   **接尾辞一致であって glob ではない。**"
  {})

;; ── 形の判定（純関数。self-check がこれを直接叩く） ────────────────────────

(defn top-level-forms
  "`src` を top-level form 単位に割る。括弧の対応だけを見る粗い分割で、文字列と
   行コメントの中の括弧は数えない。パースではないので、壊れたソースでも止まらない。"
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
          ;; 文字リテラル。`\"` を「文字列の開始」と読むと、そこから状態が反転して
          ;; 以降のファイル全部が逆になる。実測 2026-09-10、`(= c \")` を含む 2 form の
          ;; 入力で、この関数は form を 2 個ではなく **1 個** と数えていた —— 呼び出し側
          ;; から見ると findings が静かに減る。`\)` を数えないためにも、ここで飛ばす。
          (= c \\)  (recur (+ i 2) depth start false false false out)
          (= c \;)  (recur (inc i) depth start false true false out)
          (= c \")  (recur (inc i) depth start true false false out)
          (= c \()  (recur (inc i) (inc depth) (if (zero? depth) i start) false false false out)
          (= c \))  (let [d (dec depth)]
                      (if (zero? d)
                        (recur (inc i) 0 (inc i) false false false (conj out (subs src start (inc i))))
                        (recur (inc i) d start false false false out)))
          :else     (recur (inc i) depth start false false false out))))))

(defn expand-then-regroup?
  "A: 1 つの form が mapcat と partition の両方を含む。"
  [form]
  (and (str/includes? form "mapcat")
       (re-find #"\(partition\s" form)))

(defn materialising-helpers
  "B の前半: `(defn- NAME [..] ... (mapv ... (seq ...)))` として定義された helper 名。"
  [src]
  (->> (top-level-forms src)
       (keep (fn [form]
               (when (and (re-find #"^\(defn-?\s" form)
                          (str/includes? form "mapv")
                          (re-find #"\(seq\s" form))
                 (second (re-find #"^\(defn-?\s+(?:\^\S+\s+)?([A-Za-z0-9\-\>\<\?\!\*\+\=\_]+)" form)))))
       (remove nil?)
       set))

(defn- call-count
  "`form` の中で `(NAME` の後に空白か `)` が続く箇所の数。正規表現を使わない ——
   helper 名には `?` `-` `>` が普通に入り、エスケープの誤りは静かに 0 件を返す
   （それは「呼ばれていない」と見分けがつかない）。"
  [form name]
  (let [needle (str "(" name)
        n (count needle)]
    (loop [from 0 c 0]
      (if-let [i (str/index-of form needle from)]
        (let [after (when (< (+ i n) (count form)) (nth form (+ i n)))]
          (recur (inc i) (if (or (nil? after) (contains? #{\space \newline \tab \)} after)) (inc c) c)))
        c))))

(defn- has-indexed-path?
  "その form が、添字アクセスによる比較経路を既に持っているか。持っているなら
   materialise は fallback であって hot path ではない —— 形だけで判定すると、
   **直した後のコードを直す前と同じように報告する**（それは discriminate して
   いない検査である。ADR-2608136000 の 5 問目）。"
  [form]
  (boolean (or (str/includes? form "aget")
               (str/includes? form "alength")
               (str/includes? form "Arrays/equals")
               (str/includes? form "subarray"))))

(defn materialise-to-compare?
  "B: 1 つの form の中で、materialising helper が `=` の両辺に現れ、かつ添字に
   よる比較経路がその form に無い。"
  [helpers form]
  (and (some? (re-find #"\(=\s*\(" form))
       (not (has-indexed-path? form))
       (boolean (some #(<= 2 (call-count form %)) helpers))))

(defn blank-strings
  "文字列リテラルと行コメントの中身を、長さと改行を保ったまま空白に置き換える。

   これが無いと、**検出器は自分の docstring と自分の fixture を検出する** ——
   実測 2026-09-10、このファイル自身が 2 件（`known-bad-a` と、splitter の
   fixture）を報告していた。位置を保つのは行番号をずらさないため。"
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
            (= c \\) (recur (+ i 2) false false false out)
            (= c \;) (recur (inc i) false true false (blank out))
            (= c \") (recur (inc i) true false false out)
            :else    (recur (inc i) false false false out)))))))

(defn findings-in
  "1 ファイルの該当。`{:rule :a|:b :line n}` の列。"
  [src]
  (let [blanked (blank-strings src)
        helpers (materialising-helpers blanked)]
    (loop [forms (top-level-forms blanked) pos 0 out []]
      (if-let [form (first forms)]
        (let [at (str/index-of blanked form pos)
              line (inc (count (re-seq #"\n" (subs blanked 0 (or at 0)))))
              out (cond-> out
                    (expand-then-regroup? form)        (conj {:rule :expand-then-regroup :line line})
                    (materialise-to-compare? helpers form) (conj {:rule :materialise-to-compare :line line}))]
          (recur (rest forms) (+ (or at pos) (count form)) out))
        out))))

;; ── self-check: 既知の不良と既知の正常に、毎回正しく答えられることを示す ────
;;
;; 検出器が黙って何も見つけなくなる形は、この class で最も安い失敗である
;; （ADR-2608136000 の「入力が無いとき何を返すか」）。だから起動のたびに
;; 実際の不良（この検出器を生んだ 2 つの実装）で 1 件ずつ検出し、直した後の
;; 実装で 0 件になることを示してから走る。

(def known-bad-a
  "multiformats.base32/encode（2026-09-05 に置き換えたもの）"
  "(defn encode [bytes]
     (let [bits (mapcat (fn [byte] (map #(bit-and (bit-shift-right (int byte) %) 1)
                                        [7 6 5 4 3 2 1 0]))
                        (seq bytes))]
       (->> bits (partition 5 5 nil) (map (fn [chunk] chunk)) (apply str))))")

(def known-bad-b
  "ipld.core/decode の canonical 検証（同上）"
  "(defn- byte-vector [value] (mapv #(bit-and % 0xff) (seq value)))
   (defn decode [bytes]
     (let [canonical (cbor/encode node)]
       (when-not (= (byte-vector bytes) (byte-vector canonical))
         (throw (ex-info \"not canonical\" {})))))")

(def known-good-with-char-literal
  "正しい形だが、**文字リテラル `\\\"` を含む**。splitter がこれを文字列の開始と
   読むと、続く form が丸ごと飲み込まれて検出が静かに止まる。ここでは 2 つの
   top-level form に割れることを self-check が要求する。"
  "(defn scan [c] (if (= c \\\") 1 2))
   (defn encode [bytes]
     (let [bits (mapcat (fn [b] [b]) (seq bytes))]
       (->> bits (partition 5 5 nil) (apply str))))")

(def known-good
  "置き換えた後の形。どちらの規則にも当たらない。"
  "(defn encode [bytes]
     (loop [xs (seq bytes) buffer 0 bits 0 out (transient [])]
       (if xs (recur (next xs) buffer bits out) (apply str (persistent! out)))))
   (defn- same-bytes? [a b]
     (loop [i 0] (if (= i (count a)) true (recur (inc i)))))")

(defn self-check!
  "検出器が discriminate できることの証明。できなければ nil を返し、呼び出し側が
   exit 2 にする —— 答えられない検出器は、clean と同じ値を返してはならない。"
  []
  (let [a (findings-in known-bad-a)
        b (findings-in known-bad-b)
        g (findings-in known-good)
        ;; 文字リテラルで splitter の状態が反転すると、この 2 form の入力は 1 form に
        ;; なり、2 つ目に置いた既知の不良が見えなくなる。見えることを要求する。
        cl (findings-in known-good-with-char-literal)
        forms (count (top-level-forms known-good-with-char-literal))]
    (when (and (some #(= :expand-then-regroup (:rule %)) a)
               (some #(= :materialise-to-compare (:rule %)) b)
               (empty? g)
               (= 2 forms)
               (some #(= :expand-then-regroup (:rule %)) cl))
      {:bad-a (count a) :bad-b (count b) :good (count g) :char-literal-forms forms})))

;; ── 走査 ──────────────────────────────────────────────────────────────────

(defn- walk [dir]
  (let [skip #{"node_modules" ".git" ".shadow-cljs" "out" "dist" "target"
               ".cpcache" ".gitlibs" "public"}]
    (letfn [(go [d]
              (let [ents (try (fs/readdirSync d #js {:withFileTypes true}) (catch :default _ #js []))]
                (mapcat (fn [e]
                          (let [n (.-name e) p (path/join d n)]
                            (cond (.isDirectory e) (if (skip n) [] (go p))
                                  (source-ext (path/extname n)) [p]
                                  :else [])))
                        (array-seq ents))))]
      (go dir))))

(defn- test-file?
  "テストは hot path ではない。ここを走査すると、置き換えた実装を oracle として
   残した差分テスト（まさにこの検出器が求める形）を毎回 finding として返す。"
  [p]
  (let [lower (str/lower-case p)]
    (or (str/includes? lower "/test/")
        (str/includes? lower "_test.")
        (str/includes? lower "-test.")
        (str/includes? lower "/bench/"))))

(defn- codec-shaped? [p]
  (let [lower (str/lower-case p)]
    (and (not (test-file? p))
         (some #(str/includes? lower %) codec-ns-hint))))

(defn- allowed? [p] (some #(str/ends-with? p %) (keys allowed)))

(defn- finding!
  "FINDING<TAB>severity<TAB>key<TAB>detail — the orgs-detector protocol
  (manifest/orgs-detectors.edn). The key is `<rule>:<path>`, NOT the line
  number: a finding must stay the same across reruns while the file is edited
  around it, or every unrelated commit reports it as new."
  [severity key detail]
  (println (str "FINDING\t" severity "\t" key "\t" detail)))

(defn -main [& argv]
  (let [args (remove #(str/starts-with? % "--") argv)
        root (or (first args) ".")
        findings? (boolean (some #{"--findings"} argv))]
    (when-not (try (.isDirectory (fs/statSync root)) (catch :default _ false))
      (println "SCANNED\t0")
      (println (str "REFUSED\tno such directory: " root))
      (js/process.exit 2))
    (if-not (self-check!)
      (do (println "SCANNED\t0")
          (println "REFUSED\tself-check failed: the detector cannot tell the known-bad shapes from the known-good one")
          (println (str "  known-bad-a  -> " (pr-str (findings-in known-bad-a))))
          (println (str "  known-bad-b  -> " (pr-str (findings-in known-bad-b))))
          (println (str "  known-good   -> " (pr-str (findings-in known-good))))
          (js/process.exit 2))
      (let [files (->> (walk root) (filter codec-shaped?) (remove allowed?))
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
        ;; `orgs/` は west 管理で、checkout されていない repo は fs に映らない。
        ;; SCANNED を「この workspace の codec ファイル数」と読ませないための行。
        (when (str/includes? root "orgs")
          (println "NOTE\tcheckouts only; unchecked-out west projects are not scanned"))
        (when (pos? unreadable) (println (str "UNREADABLE\t" unreadable)))
        (cond
          (zero? (count files))
          (do (println "REFUSED\tno codec-shaped source found; a scan of nothing is not a clean result")
              (js/process.exit 2))

          (pos? unreadable)
          (do (println "REFUSED\tsome candidates could not be read; unread is not clean")
              (js/process.exit 2))

          (seq real)
          (do (if findings?
                ;; one finding per (rule, file): the key must be unique, and line
                ;; numbers churn on every unrelated edit to the same file.
                (doseq [[[rule file] group] (sort-by key (group-by (juxt :rule :file) real))]
                  (finding! "warn" (str (name rule) ":" file)
                            (str (name rule) " at " file " line(s) "
                                 (str/join "," (sort (map :line group)))
                                 " -- measure it before changing it; this is a shape, not a timing")))
                (doseq [{:keys [file rule line]} (sort-by (juxt :file :line) real)]
                  (println (str (name rule) "\t" file ":" line))))
              (println (str "FINDINGS\t" (count real)))
              (when-not findings?
                (println "measure these before changing them; this reports a shape, not a timing"))
              (js/process.exit 1))

          :else
          (do (println "CLEAN\t0") (js/process.exit 0)))))))

(apply -main (vec *command-line-args*))
