#!/usr/bin/env nbb
;; manifest/west.yml の 4,000+ repo を名前で検索する。
;;
;; なぜ必要か。2026-08-04 の 1 セッションで、agent(私)が「この workspace には
;; X が無い」と 3 回結論し、3 回とも間違っていた:
;;
;;   * 「semantic-code は kotoba repo にある」→ 実際は #429 で kotoba-lang/codebase
;;     に切り出し済みだった
;;   * 「DHT に announce するには libp2p ノードが要るが無い」→ 実際は
;;     io-libp2p-specs-kad-dht に multi-router quorum 付き delegated routing、
;;     tech-ipfs-specs-ipns に実 IPNS record があった
;;   * 「transport が無い」→ multistream/Yamux は io-libp2p-specs-transport、
;;     Noise XX は noise、multiaddr は io-multiformats、protobuf は dev-protobuf
;;     に全部あった
;;
;; 3 回とも、名前で grep すれば 1 コマンドで見つかった。失敗したのは検索能力では
;; なく「結論する前に検索する」という手順で、prose の指示だけでは守られなかった。
;;
;; そこで: **「無い」と結論する前に、これを引く。**
;;
;;   nbb scripts/repo-search.cljs dht kad routing
;;   nbb scripts/repo-search.cljs noise handshake
;;   nbb scripts/repo-search.cljs protobuf
;;
;; ローカルに checkout 済みの repo は README の見出し行も一緒に出す(未 checkout
;; でも名前だけで当たりは付く。west は必要になるまで取得しないので、
;; 「手元に無い」は「存在しない」ではない —— これも上の 3 回の誤りの一因)。

(require '[clojure.string :as str])

(def fs (js/require "node:fs"))
(def path (js/require "node:path"))

(def top
  (or (.-CLAUDE_PROJECT_DIR (.-env js/process))
      (.cwd js/process)))

(defn- entries
  "west.yml の project 一覧を {:name :path :remote} で返す。

  YAML パーサを持ち込まないのは、この形が完全に規則的で、かつこのスクリプトが
  『何も無いところから 1 コマンドで動く』ことに価値があるため。"
  []
  (let [text (.readFileSync fs (.join path top "manifest" "west.yml") "utf8")
        lines (str/split-lines text)]
    (loop [ls lines current nil out []]
      (if-let [line (first ls)]
        (cond
          (re-find #"^    - name: (\S+)$" line)
          (recur (rest ls) {:name (second (re-find #"^    - name: (\S+)$" line))}
                 (cond-> out current (conj current)))

          (and current (re-find #"^      path: (\S+)$" line))
          (recur (rest ls) (assoc current :path (second (re-find #"^      path: (\S+)$" line))) out)

          (and current (re-find #"^      remote: (\S+)$" line))
          (recur (rest ls) (assoc current :remote (second (re-find #"^      remote: (\S+)$" line))) out)

          :else (recur (rest ls) current out))
        (cond-> out current (conj current))))))

(def readme-scan-bytes
  "README の先頭だけを見る。どの repo も『何であるか』は冒頭で言い切っており、
  全文を読むと 4,000 repo 分で体感できるほど遅くなる。"
  3000)

(defn- readme
  "checkout 済み repo の README 冒頭。未 checkout なら nil。"
  [entry]
  (try
    (let [file (.join path top (:path entry) "README.md")]
      (when (.existsSync fs file)
        (subs (.readFileSync fs file "utf8") 0
              (min readme-scan-bytes (.-length (.readFileSync fs file "utf8"))))))
    (catch :default _ nil)))

(defn- headline
  "README の最初の非空・非見出し行(先頭 160 字)。"
  [text]
  (when text
    (->> (str/split-lines text)
         (drop-while #(or (str/blank? %) (str/starts-with? % "#")
                          (str/starts-with? % "[!")))
         first
         (#(when % (subs % 0 (min 160 (count %))))))))

(defn- score
  "名前・パス・(checkout 済みなら)README 冒頭への当たり具合。

  README も見るのは、探している能力の名前が repo 名に出ないことがあるため:
  multistream と Yamux は `io-libp2p-specs-transport` にあり、どちらの語も
  名前に無い。名前一致を README 一致より重く採るのは、名前が一致したときは
  ほぼ確実に当たりだから。"
  [entry text terms]
  (let [name-hay (str/lower-case (str (:name entry) " " (:path entry)))
        body (some-> text str/lower-case)
        name-hits (filter #(str/includes? name-hay %) terms)
        body-hits (when body (filter #(str/includes? body %) terms))
        hit-terms (set (concat name-hits body-hits))]
    (when (seq hit-terms)
      (+ (* 100 (count name-hits))
         (* 30 (count body-hits))
         (if (= (count hit-terms) (count terms)) 500 0)
         ;; 完全一致の repo 名は、部分一致より常に上に出す。
         (if (some #(= (str/lower-case (:name entry)) %) terms) 1000 0)
         ;; 短い名前ほど的中である可能性が高い(kotoba < kotoba-lang-something)。
         (- (count (:name entry)))))))

(defn- script-args
  "nbb 1.4.210 は script file に *command-line-args* を渡さない。
  gen-west-manifest.cljs と同じ復元をここでも行う。"
  []
  (if (seq *command-line-args*)
    *command-line-args*
    (let [argv (vec (js->clj (.-argv js/process)))
          index (first (keep-indexed
                        (fn [i value]
                          (when (str/ends-with? value "scripts/repo-search.cljs") i))
                        argv))]
      (if (some? index) (subvec argv (inc index)) []))))

(defn -main [& args]
  (let [terms (map str/lower-case args)]
    (if (empty? terms)
      (println "usage: nbb scripts/repo-search.cljs <term> [term...]")
      (let [all (entries)
            ranked (->> all
                        (keep (fn [e]
                                (let [text (readme e)]
                                  (when-let [s (score e text terms)]
                                    (assoc e :score s :headline (headline text))))))
                        (sort-by (comp - :score))
                        (take 25))]
        (println (str "west.yml: " (count all) " projects, "
                      (count ranked) " shown for " (str/join " " terms)))
        (if (empty? ranked)
          (println "  (no name match — try fewer or broader terms before concluding it does not exist)")
          (doseq [e ranked]
            (println (str "  " (:name e)
                          " — " (:path e)
                          (when-let [h (:headline e)] (str "\n      " h))))))
        (println (str "\nnote: README は checkout 済み repo のみ走査。west は必要に"
                      "なるまで取得しないので、\n      『手元に無い』は『存在しない』ではない。"))))))

(apply -main (script-args))
