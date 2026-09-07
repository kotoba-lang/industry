#!/usr/bin/env nbb
;; verify-fuel-literal-vs-policy.cljs — a fuel budget written as a literal in a
;; script or test, in a repo that also carries a `*fuel-policy*.edn`.
;;
;;   nbb --classpath ".:scripts/nbb_compat" scripts/verify-fuel-literal-vs-policy.cljs [<dir>] [--findings]
;;
;; ## なぜ要るか
;;
;; policy ファイルは「この予算はここで決める」という宣言である。その隣で
;; `"--fuel" "1048576"` と書いた script は、**今日は policy と一致している**ので
;; 全部通る。policy を 524288 に下げた日、script は旧値のまま compile を続け、
;; smoke は緑のまま、policy が縛っているのは build 経路 1 本だけになる。
;; 一致は偶然であって参照ではない —— 参照なら literal は要らない。
;;
;; 同じ形の亜種が「policy と `--fuel` を同じコマンドに両方渡す」で、そのときは
;; どちらが効いたかを出力から読めない（実測 2026-09-07、aiueos の
;; build-kotoba-native-kernel.sh は `--policy … --fuel 1048576` を並べている）。
;;
;; ## 何を検出するか
;;
;; policy が 1 つ以上在る repo で、`.edn` 以外のソースの**コメントでない行**にある:
;;
;;   A. `"--fuel" "<digits>"`   — 引数ベクタの中の literal（cljs の spawn）
;;   B. `--fuel <digits>`       — sh / 文字列の中の literal（test の assert を含む）
;;   C. `(def <…fuel…> <digits>)` — 名前に fuel を含む def の literal 初期値
;;
;; 報告は `POLICY=<n>` と `LITERAL=<n>` を並べる。**一致していても finding である**
;; —— 見せたいのは「一致が偶然である」ことなので、一致している行こそ隠さない。
;;
;; **これは「literal が誤り」という主張ではない。** `smoke-qemu-fuel64` の
;; 2,500,000,000 は 2^31 を越えることが目的なので policy 値であってはならない。
;; そういう site は理由をヘッダに書き、`--findings` の key を `:accepted` に載せる
;; （registry の作法。理由の無い literal だけが残る）。
;;
;; ## exit code は三値
;;
;;   0  policy が在り、literal は 0 件
;;   1  literal があった
;;   2  **答えられなかった** —— policy ファイルが無い（この検査は問いを立てられない）、
;;      policy が読めない、self-check 失敗、対象ディレクトリが無い

(require '["node:fs" :as fs]
         '["node:path" :as path]
         '[clojure.edn :as edn]
         '[clojure.string :as str])

(def source-ext #{".clj" ".cljc" ".cljs" ".sh" ".mjs" ".js" ".py" ".bb"})

(defn policy-file? [p]
  (let [n (path/basename p)]
    (and (str/includes? n "fuel-policy") (str/ends-with? n ".edn"))))

(def comment-prefix
  {".clj" ";" ".cljc" ";" ".cljs" ";" ".bb" ";" ".sh" "#" ".py" "#" ".mjs" "//" ".js" "//"})

;; ── 形の判定（純関数。self-check がこれを直接叩く） ────────────────────────

(def literal-res
  [[:argv-literal  #"\"--fuel\"\s+\"(\d+)\""]
   [:flag-literal  #"(?<![\w-])--fuel\s+(\d+)\b"]
   [:def-literal   #"\(def\s+[^\s()]*fuel[^\s()]*\s+(\d+)\s*\)"]])

(defn literal-sites
  "1 ファイル分。`{:rule k :line n :value n}` の列。コメント行は見ない。"
  [ext src]
  (let [cp (get comment-prefix ext ";")]
    (->> (str/split src #"\n" -1)
         (map-indexed vector)
         (mapcat (fn [[i line]]
                   (if (str/starts-with? (str/triml line) cp)
                     []
                     (for [[rule re] literal-res
                           m (re-seq re line)]
                       {:rule rule :line (inc i) :value (js/parseInt (second m) 10)}))))
         vec)))

(defn policy-fuel
  "policy の値。`[:budgets :fuel]` を第一候補にし、無ければ最初に見つかる :fuel。
   読めなければ nil（呼び出し側が UNREADABLE として数える）。"
  [src]
  (try
    (let [v (edn/read-string src)
          direct (get-in v [:budgets :fuel])]
      (or direct
          (some (fn [[k x]] (when (= k :fuel) x))
                (tree-seq coll? seq v))))
    (catch :default e
      (println (str "NOTE\tpolicy-unreadable\t" (.-message e))) nil)))

;; ── self-check ────────────────────────────────────────────────────────────

(def known-bad
  "3 規則それぞれ 1 件。"
  "(def probe-fuel 1048576)
   (def args [\"--target\" \"x\" \"--fuel\" \"1048576\" \"--output\" out])
   (is (str/includes? builder \"--fuel 1048576\"))")

(def known-good
  "policy から読む形 + コメント + 名前に fuel が無い def。0 件であるべき。"
  ";; --fuel 32768 was the measured floor
   (def policy (edn/read-string (slurp \"native-kernel-fuel-policy.edn\")))
   (def fuel (get-in policy [:budgets :fuel]))
   (def args [\"--target\" \"x\" \"--fuel\" (str fuel) \"--output\" out])
   (def retries 3)
   (def fuel-policy-path \"scripts/native-kernel-fuel-policy.edn\")")

(def known-boundary
  "境界: `--fuel-policy` という別フラグの後の数字は literal ではない。"
  "(def args [\"--fuel-policy\" \"policy.edn\" \"--jobs\" \"1048576\"])")

(defn self-check! []
  (let [b (literal-sites ".cljs" known-bad)
        g (literal-sites ".cljs" known-good)
        e (literal-sites ".cljs" known-boundary)
        p (policy-fuel "{:budgets {:fuel 1048576}}")]
    (when (and (= 3 (count b)) (= #{:argv-literal :flag-literal :def-literal} (set (map :rule b)))
               (every? #(= 1048576 (:value %)) b)
               (= 0 (count g)) (= 0 (count e))
               (= 1048576 p))
      {:bad (count b) :good (count g) :boundary (count e) :policy p})))

;; ── 走査 ──────────────────────────────────────────────────────────────────

(defn- walk [dir]
  ;; `build` は aiueos が生成物（policy の写し・.sh の写し）を置く場所で、
  ;; そこを数えると同じ site が 2 度出る。
  (let [skip #{"node_modules" ".git" ".shadow-cljs" "out" "dist" "target"
               ".cpcache" ".gitlibs" "public" "build" ".venv" "__pycache__"}]
    (letfn [(go [d]
              (let [ents (try (fs/readdirSync d #js {:withFileTypes true}) (catch :default _ #js []))]
                (mapcat (fn [e]
                          (let [n (.-name e) p (path/join d n)]
                            (cond (.isDirectory e) (if (skip n) [] (go p))
                                  (or (source-ext (path/extname n)) (policy-file? p)) [p]
                                  :else [])))
                        (array-seq ents))))]
      (go dir))))

(defn- finding! [severity key detail]
  (println (str "FINDING\t" severity "\t" key "\t" detail)))

(defn -main [& argv]
  (let [args (remove #(str/starts-with? % "--") argv)
        root (or (first args) ".")
        findings? (boolean (some #{"--findings"} argv))]
    (when-not (try (.isDirectory (fs/statSync root)) (catch :default e
                                                        (println (str "NOTE\tstat failed: " (.-message e)))
                                                        false))
      (println "SCANNED\t0")
      (println (str "REFUSED\tno such directory: " root))
      (js/process.exit 2))
    (if-not (self-check!)
      (do (println "SCANNED\t0")
          (println "REFUSED\tself-check failed: the detector cannot tell the known-bad shapes from the known-good ones")
          (println (str "  known-bad      -> " (pr-str (literal-sites ".cljs" known-bad)) " (want 3, one per rule)"))
          (println (str "  known-good     -> " (pr-str (literal-sites ".cljs" known-good)) " (want 0)"))
          (println (str "  known-boundary -> " (pr-str (literal-sites ".cljs" known-boundary)) " (want 0)"))
          (println (str "  policy         -> " (pr-str (policy-fuel "{:budgets {:fuel 1048576}}")) " (want 1048576)"))
          (js/process.exit 2))
      (let [all (vec (walk root))
            policies (filter policy-file? all)
            sources (remove policy-file? all)
            read (fn [p] (try (fs/readFileSync p "utf8")
                              (catch :default e
                                ;; 読めなかった理由を捨てない（3 問目）。nil は UNREADABLE として数える。
                                (println (str "NOTE\tunreadable\t" p "\t" (.-message e))) nil)))
            policy-vals (map (fn [p] [p (some-> (read p) policy-fuel)]) policies)
            unreadable-policies (filter (comp nil? second) policy-vals)
            policy-set (set (keep second policy-vals))
            hits (->> sources
                      (mapcat (fn [p]
                                (let [src (read p)]
                                  (if (nil? src)
                                    [{:file p :rule :unreadable :line 0}]
                                    (map #(assoc % :file p) (literal-sites (path/extname p) src))))))
                      vec)
            unreadable (count (filter #(= :unreadable (:rule %)) hits))
            real (remove #(= :unreadable (:rule %)) hits)]
        (println (str "SCANNED\t" (count sources)))
        (println (str "POLICIES\t" (count policies)))
        (when (str/includes? root "orgs")
          (println "NOTE\tcheckouts only; unchecked-out west projects are not scanned"))
        (doseq [[p v] policy-vals] (println (str "POLICY\t" p "\t" (or v "UNREADABLE"))))
        (when (pos? unreadable) (println (str "UNREADABLE\t" unreadable)))
        (cond
          (empty? policies)
          (do (println "REFUSED\tno *fuel-policy*.edn in this tree; without a policy there is nothing for a literal to disagree with")
              (js/process.exit 2))

          (seq unreadable-policies)
          (do (println "REFUSED\ta policy file could not be read as EDN with a :fuel value; an unreadable policy is not a policy")
              (js/process.exit 2))

          (pos? unreadable)
          (do (println "REFUSED\tsome sources could not be read; unread is not clean")
              (js/process.exit 2))

          (seq real)
          (do (let [policy-str (str/join "," (sort policy-set))]
                ;; 一致が偶然であることを見せる行: literal 値ごとに 1 行
                (doseq [[v group] (sort-by key (group-by :value real))]
                  (println (str "POLICY=" policy-str "\tLITERAL=" v "\tsites=" (count group)
                                "\tagreement=" (if (contains? policy-set v) "coincidence" "none"))))
                (if findings?
                  (doseq [[file group] (sort-by key (group-by :file real))]
                    (finding! "warn" (str "fuel-literal:" file)
                              (str "POLICY=" policy-str " LITERAL="
                                   (str/join "," (sort (distinct (map :value group))))
                                   " at " file " line(s) " (str/join "," (sort (map :line group)))
                                   " -- a literal beside a policy agrees by coincidence; read the policy or write the reason")))
                  (doseq [{:keys [file rule line value]} (sort-by (juxt :file :line) real)]
                    (println (str (name rule) "\t" file ":" line "\t" value)))))
              (println (str "FINDINGS\t" (count real)))
              (js/process.exit 1))

          :else
          (do (println (str "POLICY=" (str/join "," (sort policy-set)) "\tLITERAL=-\tsites=0"))
              (println "CLEAN\t0") (js/process.exit 0)))))))

(apply -main (vec *command-line-args*))
