#!/usr/bin/env nbb
;; scripts/repo-bots/gen-registry.cljs — west に登録された repo 1 本ごとに
;; 常駐 bot を 1 体、名簿として起こす。生成物。手で編集しない。
;;
;;   nbb scripts/repo-bots/gen-registry.cljs            ; 書き出す
;;   nbb scripts/repo-bots/gen-registry.cljs --check    ; 生成器と一致するか
;;
;; ## なぜ「名簿」から始めるのか
;;
;; `manifest/observatories.edn` の冒頭が、この種の仕事の失敗の形をそのまま
;; 記録している —— 22 actor を実際に走らせたら 8 本が走らず、しかも
;; 「壊れていたのではない。誰も一度も走らせていなかった」。README も
;; MATURITY.md も、それらが動くと書いてあった。
;;
;; だから名簿には **charter（何を守る役か）しか書かない**。動いたかどうかは
;; 名簿ではなく tick の state が答える（`~/.itonami/repo-bots/state.edn`）。
;; **名簿に載っていることを「動いている」と読ませない。** これが 4,000 体
;; 規模で最初に壊れるところなので、構造で分けておく。
;;
;; ## bot の個体性はどこにあるか
;;
;; 実行機構は 1 本（`tick.cljs`）を共有する。ADR 0047（hyakka topic residents）が
;; 「テーマごとに Durable Object の *インスタンス* を分け、pipeline は 1 本を
;; 共有する」と決めたのと同じ切り方で、個体性は
;;
;;   - 安定した id（`<org>/<name>`）
;;   - 自分の charter（class 由来の floor 集合）
;;   - 自分の state 行と履歴
;;
;; にある。2 本目の pipeline を生やさない。
;;
;; ## 名簿から外すもの
;;
;;   archived グループ  退役済み。成長を促す対象ではない
;;   datalad グループ   git-annex の実体データ。コード repo ではない

(ns repo-bots-gen-registry
  (:require [clojure.string :as str]
            [cljs.pprint :as pp]))

(def fs (js/require "node:fs"))
(def path (js/require "node:path"))

(def argv (vec (drop 2 js/process.argv)))
(defn- flag [n] (some #{n} argv))

(def cp (js/require "node:child_process"))

(def top (or (.-CLAUDE_PROJECT_DIR (.-env js/process)) (.cwd js/process)))
(def out-file (.join path top "manifest" "repo-bots.edn"))

(defn- west-yaml
  "west.yml の本文を **origin/main から** 読む。working tree の写しは読まない。

  ここが `:pinned` 床の入力で、床の意味は「checkout が west pin と一致するか」。
  その pin の正本は superproject の origin/main であって、手元の checkout では
  ない —— CLAUDE.md / ADR-2608136800 が『checkout・west pin・repo の main は
  3 つの別物。結論を出す前に origin/main を読む』と定めているとおり。

  **実測 2026-09-01 の事故**: 手元の superproject が origin/main から 401 commit
  遅れていた日、working tree の west.yml と origin/main の west.yml で **55 本の
  pin が食い違っていた**。名簿はその古い写しから生成され、tick は
  `HEAD c2d9fe1 ≠ pin cbb2b72` という *実在しない* 違反を報告し続けた。
  さらに悪いことに、その報告に素直に従って `west update` を回すと checkout は
  古い pin へ **後退する**。誤検出が、退行を指示していた。

  読めなかったときは **exit 2**（0 でも 1 でもない = 「答えられなかった」）で
  終わる。古い写しへ黙って落ちない —— 落ちた先が、まさにこのバグである。"
  []
  (let [r (.spawnSync cp "git" #js ["-C" top "show" "origin/main:manifest/west.yml"]
                      #js {:encoding "utf8" :maxBuffer (* 64 1024 1024)})]
    (if (and (zero? (.-status r)) (not (str/blank? (.-stdout r))))
      (.-stdout r)
      (do (println (str "REFUSED\torigin/main:manifest/west.yml を読めなかった — "
                        (str/trim (str (.-stderr r)))))
          (println "  名簿は pin の正本から生成する。手元の west.yml へは落とさない")
          (println "  (古い写しからの生成は、実在しない :pinned 違反と、checkout の退行指示を作る)")
          (println "  直す: git -C <superproject> fetch origin")
          (.exit js/process 2)))))

(defn- projects
  "origin/main の west.yml の project を {:name :path :remote :revision :groups} で返す。

  YAML パーサを持ち込まないのは repo-search.cljs と同じ理由 —— この形は完全に
  規則的で、道具立て無しで動くことに価値がある。"
  []
  (let [lines (str/split-lines (west-yaml))]
    (loop [ls lines cur nil out []]
      (if-let [line (first ls)]
        (let [push (fn [o] (cond-> o cur (conj cur)))]
          (cond
            (re-find #"^    - name: (\S+)$" line)
            (recur (rest ls) {:name (second (re-find #"^    - name: (\S+)$" line))} (push out))

            (and cur (re-find #"^      path: (\S+)$" line))
            (recur (rest ls) (assoc cur :path (second (re-find #"^      path: (\S+)$" line))) out)

            (and cur (re-find #"^      remote: (\S+)$" line))
            (recur (rest ls) (assoc cur :remote (second (re-find #"^      remote: (\S+)$" line))) out)

            (and cur (re-find #"^      revision: (\S+)$" line))
            (recur (rest ls) (assoc cur :revision (second (re-find #"^      revision: (\S+)$" line))) out)

            (and cur (re-find #"^      groups: \[(.*)\]$" line))
            (recur (rest ls)
                   (assoc cur :groups (->> (str/split (second (re-find #"^      groups: \[(.*)\]$" line)) #",")
                                           (map str/trim) (remove str/blank?) vec))
                   out)

            :else (recur (rest ls) cur out)))
        (cond-> out cur (conj cur))))))

;; class は名前の面（ADR-2608040100）から引く。判断を足さない —— prefix が
;; 決まっているものだけを分類し、残りは :library に落とす。class が効くのは
;; charter の文面と、tick が「この repo に test を期待してよいか」を
;; *測る前に* 決めないための注記だけ。floor の可否は tick が実測する。
(def ^:private spec-prefixes ["org-" "io-" "tech-" "dev-" "gov-" "int-" "capability-" "jp-go-" "jp-or-" "com-"])

(defn- classify [{:keys [name groups]}]
  (let [g (set groups)]
    (cond
      (g "datalad")                             :data
      (re-find #"^cloud-itonami-(isic|isco)-" name) :blueprint
      (re-find #"^cloud-itonami-(lei|assoc|municipality|iso3166)-" name) :registry-mirror
      (str/starts-with? name "loop-")           :loop
      (str/starts-with? name "skill-")          :skill
      (str/starts-with? name "action-")         :action
      (str/starts-with? name "actor-")          :actor
      (str/starts-with? name "app-")            :app
      (str/starts-with? name "person-")         :person
      (some #(str/starts-with? name %) spec-prefixes) :spec-mirror
      :else                                     :library)))

;; floor は「割ったら 1 件だけ提案が立つ床」。全 class 共通にしてあるのは、
;; class ごとに床を変えると『この class は測らなくてよい』が静かに増えるため。
;; 適用外は tick が :n/a として**測って**返す（宣言では決めない）。
(def ^:private base-floors [:checkout :pinned :landed :readme :test-signal])

(defn- charter [{:keys [name]} cls]
  (str "repo `" name "` の現在地を測り、床を割っているものが在れば 1 件だけ提案する。"
       "class=" (clojure.core/name cls) "。"
       "床: checkout が在る / west pin と一致 / 未着地の作業が無い / README が読める / "
       "コードが在るなら test 信号が在る。"))

(defn- entry [{:keys [name path remote revision groups] :as p}]
  (let [cls (classify p)]
    {:bot/id (str (or remote (second (str/split (or path "") #"/"))) "/" name)
     :bot/name name
     :bot/repo path
     :bot/org (or remote (second (str/split (or path "") #"/")))
     :bot/class cls
     :bot/pin revision
     :bot/groups (vec groups)
     :bot/floors base-floors
     :bot/charter (charter p cls)}))

(defn- roster []
  (->> (projects)
       (filter :path)
       (remove #(some #{"archived"} (:groups %)))
       (remove #(some #{"datalad"} (:groups %)))
       (map entry)
       (sort-by :bot/id)
       vec))

(defn- render [rs]
  (str ";; manifest/repo-bots.edn — generated by scripts/repo-bots/gen-registry.cljs.\n"
       ";; DO NOT EDIT BY HAND. Regenerate: nbb scripts/repo-bots/gen-registry.cljs\n"
       ";;\n"
       ";; west に登録された repo 1 本につき常駐 bot 1 体の名簿。\n"
       ";; **ここに載っていることは「動いている」ことを意味しない** —— 動いたかは\n"
       ";; ~/.itonami/repo-bots/state.edn（tick の state）だけが答える。名簿と稼働を\n"
       ";; 分けているのは manifest/observatories.edn が記録した失敗（22 actor 中 8 本が\n"
       ";; 「壊れていたのではなく、誰も一度も走らせていなかった」）を構造で防ぐため。\n"
       ";;\n"
       ";; archived / datalad グループは名簿に入れない。\n"
       ";;\n"
       ";; roster size: " (count rs) "\n\n"
       (with-out-str (pp/pprint rs))))

(let [rs (roster)
      text (render rs)]
  (if (flag "--check")
    (let [cur (try (.readFileSync fs out-file "utf8") (catch :default _ nil))]
      (if (= cur text)
        (do (println "CURRENT\t" (count rs) "bots") (js/process.exit 0))
        (do (println "STALE\tregenerate: nbb scripts/repo-bots/gen-registry.cljs")
            (js/process.exit 1))))
    (do (.writeFileSync fs out-file text)
        (println "SCANNED\t" (count rs) "\tbots")
        (println "wrote\t" out-file)
        ;; class 別の内訳を出すのは、名簿が偏っていること自体が読み手への情報だから。
        (doseq [[k n] (sort-by (comp - val) (frequencies (map :bot/class rs)))]
          (println (str "  " (name k) "\t" n)))
        (js/process.exit 0))))
