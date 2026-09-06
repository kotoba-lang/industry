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
                     "--jq" "[.html_url, .build_type, (.status // \"null\")] | @tsv")]
    (let [[url kind status] (str/split (str/trim out) #"\t")]
      {:url url :build-type kind :status status})))

(defn- http-status
  "その URL を実際に引く。引けなければ nil（`000` と区別する）。"
  [url]
  (when (and url (not (str/blank? url)))
    (let [r (cp/spawnSync "curl" #js ["-sS" "-o" "/dev/null" "-w" "%{http_code}"
                                      "--max-time" "20" url]
                          #js {:encoding "utf8" :timeout 30000})]
      (when (zero? (.-status r)) (str/trim (.-stdout r))))))

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
                                    b    (latest-build full)
                                    n    (when b (commits-since full branch (:created b)))]]
                          {:repo full :branch branch :cfg cfg :http code
                           :build b :behind n :capped? (= n 100)}))
              serving?   (fn [r] (= "200" (:http r)))
              not-serving (remove serving? rows)
              built       (filter :build rows)
              stale       (filter #(and (:behind %) (pos? (:behind %))) built)
              no-record   (remove :build rows)]
          (println (str "SERVING\t" (count (filter serving? rows)) "\tURL が 200 を返した"))
          (println (str "BUILD-RECORD\t" (count built) "\tbuild 記録が引けた（残り "
                        (count no-record) " 件は build_type が workflow 等で記録が無い）"))
          (when (every? nil? (map :http rows))
            (refuse! "1 件も URL を引けなかった（curl が使えないか、外向きが塞がっている）"))
          (println)
          (println "① 人が開けない（URL が 200 を返さない）:")
          (if (seq not-serving)
            (doseq [r (sort-by :repo not-serving)]
              (println (str "  " (.padEnd (:repo r) 42) "HTTP " (or (:http r) "引けず")
                            "\tbuild " (or (:created (:build r)) "記録なし")
                            " (" (or (:status (:build r)) (:status (:cfg r))) ")")))
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
            (doseq [r (sort-by :repo not-serving)]
              (println (str "FINDING\twarn\tpages-url-not-serving\t" (:repo r)
                            "\t" (:url (:cfg r)) " returned " (or (:http r) "no answer")
                            "; build record " (or (:created (:build r)) "absent")
                            " (" (or (:status (:build r)) (:status (:cfg r))) ")")))
            (doseq [r (sort-by (comp - :behind) stale)]
              (println (str "FINDING\twarn\tpages-behind-default-branch\t" (:repo r)
                            "\t" (:behind r) (when (:capped? r) "+ (capped)")
                            " commits landed on " (:branch r)
                            " after the site was last built (" (:created (:build r)) ")"))))
          (.exit js/process (if (or (seq not-serving) (seq stale)) 1 0)))))))

(-main)
