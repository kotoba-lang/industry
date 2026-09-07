#!/usr/bin/env nbb
;; verify-pages-freshness.cljs — 公開されている GitHub Pages が、その repo の
;; default branch より何 commit 前に建てられたものかを数える。
;;
;;   nbb scripts/verify-pages-freshness.cljs [--findings] [--org <name>]... [--limit N]
;;
;; ## なぜ要るか（2026-09-08 に 1 日で 2 件、実測）
;;
;; `kotoba-lang/kami-app-suji` の公開ページは **HTTP 200 を返し、普通に開けた**。
;; 中身は 2 日前の build で、その間に着地した波（可視量 50 → 86、結合解の報告、
;; 動員されていない 11 筋が「軽く負荷」と塗られていた欠陥の修正）は 1 つも
;; live に無かった。**古いページも 200 を返す。** だから誰も気づかない。
;;
;; CLAUDE.md の single-page app 規則が「**見られることは規則の半分**」と書き、
;; 「bundle を作って document を 1 枚も出さない app は開くものが無い」と続けている
;; ——その裏面がこれで、**document は出ているが中身が古い**。ソースからは見えない。
;;
;; 同じ日に見つかった 2 件目: その publish を行うスクリプトの docstring と、
;; 過去の publish commit すべてが「GitHub Actions は repo 全体で無効だから
;; 前回の artifact は失敗せずに古くなった」と書いていた。**GitHub に訊くと
;; `{"enabled":true}`** —— 理由の側が偽になっていた。だから理由を読んで判断せず、
;; **建てられた時刻と、その後に着地した commit を数える**。
;;
;; ## 何を測り、何を測らないか
;;
;; 測るのは 1 つだけ: **最後に build された時刻より後に default branch へ着地した
;; commit の本数**。これは「サイトが間違っている」という主張ではない —— README を
;; 直しただけの commit なら site は正しいままである。**人間が見に行く理由**であって、
;; 判定ではない。だから severity は `warn` で、閾値は無い（`> 0` だけが自然な境界で、
;; 導出できない閾値は発明した定数である）。
;;
;; **build commit と head commit の SHA を比べない。** branch source の Pages では
;; build commit は `gh-pages` 上の commit で、default branch とは別系統である。
;; SHA 比較は「常に違う」を返し、それは何も言っていないのと同じ。
;;
;; ## API だけでは足りない —— URL を実際に引く
;;
;; 実測 2026-09-08: `build_type: workflow` の repo は `/pages` の `status` が null で
;; `pages/builds/latest` が 404 を返すが、**URL は 200 を返して中身を配っている**
;; （kotoba-lang/kotoba, svgraph）。API の沈黙を「建っていない」と読むと嘘になる。
;; 逆に `kototama` は build 記録が在って `status: building` のまま 2026-08-05 から
;; 動かず、**URL は 404**。build 記録の有無と、人が開けるかどうかは別の問い。
;; だから両方引く。
;;
;; ## 全件で測ったら、片方の class は finding にならなかった（2026-09-08）
;;
;; 8 org・**Pages 有効 524 repo**で走らせた結果:
;;
;;     URL が 200          472        URL が 200 を返さない   **52**
;;     build 記録あり      496        記録なし（workflow 等）   28
;;     build より後に commit  444     うち status built 437 / building 5 / errored 1
;;                                    中央値 5 commit、最大 51
;;
;; **444 件を 1 件ずつ finding にすると、この検出器は使われなくなる**（この
;; workspace 自身の規則: 当たりすぎる索引は当たらない索引と同じ）。しかも
;; 「build より後に commit が在る」は欠陥ではない —— README だけの commit なら
;; site は正しいままで、それはこの docstring が最初から言っていることである。
;;
;; だから **finding にするのは境界が導出できる 2 つだけ**にした:
;;
;;   ① URL が 200 を返さない        —— 人が開けない。欠陥である
;;   ② build が完了していない       —— status が built でない（building / errored）。
;;                                     kototama は 2026-08-05 から building のまま
;;
;; 残りは **1 行の集計 finding** にして母数と最悪値を持たせる。個別に列挙しない
;; ことと、数えていないことは別である。
;;
;; ## 答えられなかったときは答えない
;;
;; - org の一覧が引けなかった → **exit 2**（0 でも 1 でもない）。一覧が空なのと
;;   引けなかったのは、この検出器の外からは同じ顔をする。
;; - `pages/builds/latest` が 404 → その repo は `unmeasured`。Pages は有効だが
;;   一度も build されていない（あるいは workflow source で build 記録が無い）。
;;   **clean として数えない。**
;; - 走査対象が 0 件 → exit 2。`SCANNED\t0` を clean にしない。

(ns verify-pages-freshness
  (:require ["node:child_process" :as cp]
            [clojure.string :as str]))

(def argv (vec (drop 2 (js->clj (.-argv js/process)))))
(def findings? (some #{"--findings"} argv))

(defn- flag-values [flag]
  (->> (partition 2 1 argv)
       (keep (fn [[a b]] (when (= a flag) b)))
       vec))

(def limit
  (let [v (first (flag-values "--limit"))]
    (when v (js/parseInt v 10))))

(defn- gh
  "gh api を 1 回。stdout を返す。失敗は nil（**空文字列と区別する**）。"
  [& args]
  (let [r (cp/spawnSync "gh" (clj->js (into ["api"] args))
                        #js {:encoding "utf8" :timeout 60000})]
    (when (zero? (.-status r)) (.-stdout r))))

(defn- refuse! [why]
  (println (str "REFUSED\t" why))
  (println "  この検出器は答えを出せなかった。clean ではない。")
  (.exit js/process 2))

(defn- orgs-from-argv []
  (let [given (flag-values "--org")]
    (if (seq given)
      given
      ;; 既定は west.yml が pin している org。**引数なしで全 GitHub を歩かない。**
      (let [r (cp/spawnSync "/bin/sh"
                            #js ["-c" "grep -o 'path: orgs/[a-z0-9-]*' manifest/west.yml | sed 's|path: orgs/||' | sort -u"]
                            #js {:encoding "utf8" :timeout 30000})]
        (if (zero? (.-status r))
          (->> (str/split-lines (.-stdout r)) (map str/trim) (remove str/blank?) vec)
          [])))))

(defn- pages-repos
  "その org の、Pages が有効な repo → [full-name default-branch]。
   org でも user でもない / 引けない場合は nil を返す（空ベクタと区別する）。"
  [org]
  (some (fn [kind]
          (when-let [out (gh (str "/" kind "/" org "/repos?per_page=100") "--paginate"
                             "--jq" ".[] | select(.has_pages) | [.full_name,.default_branch] | @tsv")]
            (->> (str/split-lines out)
                 (remove str/blank?)
                 (mapv #(str/split % #"\t")))))
        ["orgs" "users"]))

(defn- pages-config
  "`/pages` —— html_url / build_type / status。引けなければ nil。"
  [full]
  (when-let [out (gh (str "repos/" full "/pages")
                     "--jq" "[.html_url, .build_type, (.status // \"null\"), (.source.path // \"/\")] | @tsv")]
    (let [[url kind status path] (str/split (str/trim out) #"\t")]
      {:url url :build-type kind :status status :path path})))

(defn- http-status
  "その URL を実際に引く。引けなければ nil（`000` と区別する）。"
  [url]
  (when (and url (not (str/blank? url)))
    (let [r (cp/spawnSync "curl" #js ["-sS" "-o" "/dev/null" "-w" "%{http_code}"
                                      "--max-time" "20" url]
                          #js {:encoding "utf8" :timeout 30000})]
      (when (zero? (.-status r)) (str/trim (.-stdout r))))))

(defn- first-servable-path
  "Pages の source ディレクトリの中に実在するファイルを 1 つ選び、**配信 URL 上の
   パス**に直して返す。無ければ nil。

   ここが要る理由（2026-09-08 実測）: この検出器の最初の版は **root だけ**を引いて
   404 なら『人が開けない』と報告した。52 件が当たったが、そのうち少なくとも
   48 件は **site そのものは配信していて root に index が無いだけ**だった ——
   `cloud-itonami/cargo` は `/` が 404 で `/.well-known/did.json` が 200、
   `cloud-itonami-isco-1111` は `/` が 404 で `/samples/operator-console.html`
   が 200。root 1 本では『死んでいる』と『入口が無い』を区別できない。

   選び方は**導出**であって発明ではない: source ディレクトリを listing し、
   **ブラウザが描くもの（`.html`）を優先して**名前順で 1 つ取り、無ければ任意の
   ファイルを 1 つ取る。source が `/docs` なら配信 root は `docs/` なので、
   そのぶんを剥がしたパスが URL になる。`.nojekyll` が 200 を返すことも
   『配信している』の証拠ではあるが、証人としては弱い。"
  [full branch src-path]
  (let [dir (str/replace (or src-path "/") #"^/" "")
        api (str "repos/" full "/contents/" dir "?ref=" branch)]
    (letfn [(pick [listing-api]
              (when-let [out (gh listing-api "--jq"
                                "[.[] | select(.type==\"file\") | .path] as $f | (($f | map(select(endswith(\".html\"))) | sort | .[0]) // ($f | sort | .[0]) // empty)")]
                (let [rel (str/trim out)] (when-not (str/blank? rel) rel))))
            (subdir [listing-api]
              (when-let [out (gh listing-api "--jq"
                                "[.[] | select(.type==\"dir\") | .path] | sort | .[0] // empty")]
                (let [d (str/trim out)] (when-not (str/blank? d) d))))]
      ;; **1 段だけ降りる。** 実測 2026-09-08: 44 の isco repo は source が /docs で
      ;; `docs/` の中身が `samples` ディレクトリ 1 つだけ。file が 0 なので浅い
      ;; probe は「対象なし」を返し、**配信している site を「何も配信していない」と
      ;; 報告した**（`/samples/operator-console.html` は 200 を返す）。
      ;; 無界に降りない —— 1 段で足りることを測ったので 1 段にする。
      (when-let [rel (or (pick api)
                         (when-let [d (subdir api)]
                           (pick (str "repos/" full "/contents/" d "?ref=" branch))))]
        (if (str/blank? dir) rel (str/replace rel (re-pattern (str "^" dir "/")) ""))))))

(defn- latest-build [full]
  (when-let [out (gh (str "repos/" full "/pages/builds/latest")
                     "--jq" "[.status, .created_at] | @tsv")]
    (let [[status created] (str/split (str/trim out) #"\t")]
      (when (and created (not (str/blank? created)))
        {:status status :created created}))))

(defn- commits-since
  "default branch に、その時刻より後に着地した commit の本数。引けなければ nil。

   **100 で頭打ちになる**（1 ページぶんしか引かない）。100 を返したときは
   『100 以上』であって『ちょうど 100』ではない —— 黙って切られた数は、
   切られたことが出力から分からない数と同じ害を持つので、呼び出し側が
   `:capped?` を持って印字する。"
  [full branch since]
  (when-let [out (gh (str "repos/" full "/commits?sha=" branch "&since=" since "&per_page=100")
                     "--jq" "length")]
    (let [n (js/parseInt (str/trim out) 10)]
      (when-not (js/isNaN n) n))))

(defn -main []
  (let [orgs (orgs-from-argv)]
    (when (empty? orgs) (refuse! "org を 1 つも決められなかった（manifest/west.yml が読めたか）"))
    (let [listed (mapv (fn [o] [o (pages-repos o)]) orgs)
          failed (mapv first (filter (comp nil? second) listed))]
      (when (seq failed)
        (refuse! (str "org の一覧が引けなかった: " (str/join ", " failed)
                      " —— 一覧が空なのと引けなかったのは外からは同じに見える")))
      (let [repos (cond->> (vec (mapcat second listed))
                    limit (take limit)
                    true vec)]
        (when (empty? repos) (refuse! "Pages が有効な repo が 1 つも無かった（測っていない）"))
        (println (str "SCANNED\t" (count repos) "\tPages が有効な repo（org " (count orgs) " 件）"))
        (let [rows (vec (for [[full branch] repos
                              :let [cfg  (pages-config full)
                                    code (http-status (:url cfg))
                                    ;; root が 404 のときだけ、site 自体が配信して
                                    ;; いるかを 1 本だけ確かめる。**root の 404 は
                                    ;; 死んでいることの証拠ではない。**
                                    probe (when (not= "200" code)
                                            (when-let [rel (first-servable-path
                                                            full branch (:path cfg))]
                                              {:rel rel
                                               :http (http-status
                                                      (str (str/replace (:url cfg) #"/$" "")
                                                           "/" rel))}))
                                    b    (latest-build full)
                                    n    (when b (commits-since full branch (:created b)))]]
                          {:repo full :branch branch :cfg cfg :http code :probe probe
                           :build b :behind n :capped? (= n 100)}))
              serving?   (fn [r] (= "200" (:http r)))
              ;; root が 404 でも、site の中の実ファイルが 200 なら **配信はしている**。
              site-serves? (fn [r] (or (serving? r) (= "200" (:http (:probe r)))))
              nothing-serves (remove site-serves? rows)
              no-entrance    (filter #(and (not (serving? %)) (= "200" (:http (:probe %)))) rows)
              built       (filter :build rows)
              stale       (filter #(and (:behind %) (pos? (:behind %))) built)
              ;; **完了しなかった build** は、遅れているのとは別の欠陥。
              unfinished  (filter #(and (:build %) (not= "built" (:status (:build %)))) rows)
              plain-stale (remove (set unfinished) stale)
              no-record   (remove :build rows)]
          (println (str "SERVING\t" (count (filter site-serves? rows))
                        "\tsite が配信している（root 200 " (count (filter serving? rows))
                        " + root 404 だが中は 200 " (count no-entrance) "）"))
          (println (str "BUILD-RECORD\t" (count built) "\tbuild 記録が引けた（残り "
                        (count no-record) " 件は build_type が workflow 等で記録が無い）"))
          (when (every? nil? (map :http rows))
            (refuse! "1 件も URL を引けなかった（curl が使えないか、外向きが塞がっている）"))
          (println)
          (println "① site そのものが何も配信していない:")
          (if (seq nothing-serves)
            (doseq [r (sort-by :repo nothing-serves)]
              (println (str "  " (.padEnd (:repo r) 42) "root " (or (:http r) "引けず")
                            "  probe " (or (:rel (:probe r)) "対象なし") " → "
                            (or (:http (:probe r)) "-")
                            "\tbuild " (or (:created (:build r)) "記録なし")
                            " (" (or (:status (:build r)) (:status (:cfg r))) ")")))
            (println "  （無し）"))
          (println)
          (println "①' 配信はしているが root に入口が無い（root 404 / 中のファイルは 200）:")
          (if (seq no-entrance)
            (doseq [r (sort-by :repo no-entrance)]
              (println (str "  " (.padEnd (:repo r) 42) "root " (:http r)
                            "  " (:rel (:probe r)) " → 200")))
            (println "  （無し）"))
          (println)
          (println "② build より後に default branch へ commit が着地している:")
          (if (seq stale)
            (doseq [r (sort-by (comp - :behind) stale)]
              (println (str "  " (.padEnd (:repo r) 42)
                            (:behind r) (if (:capped? r) "+ commit（100 で頭打ち）" " commit")
                            "\tbuild " (:created (:build r)) " (" (:status (:build r)) ")")))
            (println "  （無し）"))
          (println)
          (println "NOT-MEASURED\tこの検出器は『サイトが間違っている』とは言わない。")
          (println "            \tREADME だけを直した commit なら site は正しいままである。")
          (println "            \t数えているのは、人が見に行く理由の有無だけ。")
          (println (str "            \tbuild 記録の無い " (count no-record)
                        " 件について、記録の不在は不在の証拠ではない —— そのうち "
                        (count (filter serving? no-record)) " 件は URL が 200 を返している。"))
          (when findings?
            (println)
            ;; ① 人が開けない。これは欠陥なので 1 件ずつ出す。
            (doseq [r (sort-by :repo nothing-serves)]
              (println (str "FINDING\twarn\tpages-serves-nothing\t" (:repo r)
                            "\t" (:url (:cfg r)) " returned " (or (:http r) "no answer")
                            " and so did " (or (:rel (:probe r)) "(no file to probe)")
                            "; build record " (or (:created (:build r)) "absent")
                            " (" (or (:status (:build r)) (:status (:cfg r))) ")")))
            (when (seq no-entrance)
              (println (str "FINDING\tinfo\tpages-root-has-no-index\t" (count no-entrance)
                            " repos\ttheir root 404s while files inside serve 200"
                            " (e.g. " (:repo (first no-entrance)) " "
                            (:rel (:probe (first no-entrance))) ")"
                            " — an entrance is missing, the site is not dead")))
            ;; ② build が完了していない。これも 1 件ずつ。
            (doseq [r (sort-by :repo unfinished)]
              (println (str "FINDING\twarn\tpages-build-never-completed\t" (:repo r)
                            "\tlast build is " (:status (:build r)) " since "
                            (:created (:build r))
                            (when (:behind r) (str "; " (:behind r) " commits landed since")))))
            ;; 残りは母数のまま 1 行。**列挙しないことと数えていないことは別。**
            (when (seq plain-stale)
              (let [worst (first (sort-by (comp - :behind) plain-stale))]
                (println (str "FINDING\tinfo\tpages-behind-default-branch-population\t"
                              (count plain-stale) " repos"
                              "\t" (count plain-stale) " of " (count built)
                              " sites with a completed build have commits on their default"
                              " branch since; worst " (:repo worst) " at " (:behind worst)
                              (when (:capped? worst) "+")
                              ". Listed in full in this run's output, not as findings:"
                              " a commit that touched only a README leaves the site correct.")))))
          (.exit js/process (if (or (seq nothing-serves) (seq unfinished)) 1 0)))))))

(-main)
