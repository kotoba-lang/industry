#!/usr/bin/env nbb
;; scripts/svelte-cljs-wave-tick.cljs — Svelte → cljs 移行の候補を**測る**
;; （ADR-2608260900）。決定論。モデルを起こさない。
;;
;;   nbb --classpath ".:scripts/nbb_compat" scripts/svelte-cljs-wave-tick.cljs [--limit 4]
;;
;; 姉妹 tick（industry-stack-wave / itonami-os-connect）と同型で、loop は
;; 「候補があるときだけ」claude を起こす。手順の正本は skill `svelte-cljs-wave`
;; であって、ここではない（2 箇所に書くと必ず片方が古くなる）。
;;
;; ## 候補の選び方
;;
;; **1 repo あたりの .svelte 行数が少ない順。** 完走確率が高い順に消す。
;; 大物（app-aozora-svelte 34k 行、svelte-design-system 5k 行 55 component）は
;; この loop の対象にしない —— design system の移植は jp-go-dds への上流拡張
;; という別の判断を要する仕事で、機械的な波で流すものではない。
;;
;; ## 除外は宣言する（黙って除くと移行が進んだように見える）
;;
;;   node_modules / .git / dist / build / out / target / .shadow-cljs / .claude
;;   .claude/worktrees   使い捨て。同じファイルが複数 worktree に複製される
;;   contracts/lib/      vendored な第三者サイト。我々が書いていない
;;   m365-archive/       OneDrive 履歴の DataLad dataset。live なコードではない
;;   :max-files 超       1 repo に多数ある repo はこの波の対象外（上記の理由）
;;   archived            push を受け付けない。**着地できない**（下）
;;
;; ## exit code
;;
;;   0  測れた（候補 0 本でも 0。これは gate ではなく測定器）
;;   2  **測れなかった** —— orgs/ が無い、find が失敗した、走査 0 件、
;;      west.yml から archived 印を読めなかった。
;;      0 と区別されることが要点（ADR-2608136000）。

(ns svelte-cljs-wave-tick
  (:require [clojure.string :as str]))

(def fs (js/require "fs"))
(def cp (js/require "child_process"))
(def os (js/require "os"))
(def path (js/require "path"))

(def home (.homedir os))
(def root (or (aget (.-env js/process) "COM_JUNKAWASAKI_ROOT")
              (str home "/github/com-junkawasaki")))
(def ledger-file (str home "/.itonami/svelte-cljs-wave-tick.ledger.edn"))

(def args (vec *command-line-args*))
(defn- arg [flag default]
  (if-let [i (first (keep-indexed #(when (= %2 flag) %1) args))]
    (js/parseInt (nth args (inc i)) 10)
    default))

(def limit (arg "--limit" 4))
;; 1 repo あたりこの本数を超える Svelte は、この機械的な波の対象にしない。
(def max-files (arg "--max-files" 2))

(def vendored-prefixes
  ["orgs/etzhayyim/root/50-infra/vultr/geth-private/contracts/lib/"
   "orgs/gftdcojp/apps-gftdcojp/50-infra/vultr/geth-private/contracts/lib/"
   "orgs/gftdcojp/m365-archive/"])

(defn- vendored? [rel] (some #(str/starts-with? rel %) vendored-prefixes))
(defn- stale-worktree?
  "`orgs/<org>/.foo/...` —— repo 名が `.` で始まるものは west project ではなく、
   orgs/ の中に置き去りにされた linked worktree。実測 2026-08-26:
   `orgs/cloud-itonami/.wave5-3520` は `cloud-itonami-isic-3520` の worktree
   （Aug 12 から放置）で、**同じ .svelte を 2 度数えさせ、親 repo を
   in-flight にも見せていた**。撤去は git-cleanup-conflict の仕事なので、
   ここでは数えないだけにする。"
  [rel]
  (str/starts-with? (str (nth (str/split rel #"/") 2 "")) "."))
(defn- worktree? [rel] (str/includes? (str "/" rel) "/.claude/worktrees/"))

(defn- scan!
  "orgs/ を 1 パス走査して .svelte の rel-path を返す。失敗時 nil。
   find の stdout を execFileSync でパイプ受けすると、この機械では 11.5s の
   走査が 69s になる（実測 2026-08-26、load 100 超）。ファイルに落として読む。"
  []
  (let [tmp (path.join (or (aget (.-env js/process) "TMPDIR") "/tmp")
                       (str "scw-" (.getTime (js/Date.)) ".txt"))
        args ["orgs"
              "(" "-name" "node_modules" "-o" "-name" ".git" "-o" "-name" "dist"
              "-o" "-name" "build" "-o" "-name" "out" "-o" "-name" "target"
              "-o" "-name" ".shadow-cljs" "-o" "-name" ".claude" ")" "-prune" "-o"
              "-name" "*.svelte" "-type" "f" "-print"]]
    (try
      (let [fd (.openSync fs tmp "w")]
        (try (.execFileSync cp "find" (clj->js args)
                            #js {:cwd root :stdio #js ["ignore" fd "ignore"]})
             (finally (.closeSync fs fd)))
        (let [rels (->> (str/split-lines (str (.readFileSync fs tmp "utf8")))
                        (remove str/blank?)
                        (remove worktree?)
                        (remove stale-worktree?)
                        (remove vendored?)
                        vec)]
          (.unlinkSync fs tmp)
          rels))
      (catch :default _ nil))))

(defn- repo-of [rel] (str/join "/" (take 3 (str/split rel #"/"))))

(defn- lines-of [rel]
  (try (count (str/split-lines (str (.readFileSync fs (path.join root rel) "utf8"))))
       (catch :default _ 0)))

(defn- svelte-dir
  "その repo の svelte ディレクトリ（appview/<name>/svelte 等）。無ければ nil。"
  [rels]
  (some (fn [r]
          (let [i (str/index-of r "/svelte/")]
            (when i (subs r 0 (+ i 7)))))
        rels))

(defn- manifest-facts
  "west.yml から 2 つの集合を読む。
   `{:paths #{登録済み path} :archived #{archived と印の付いた path}}`。
   **読めなかった / 1 件も path を拾えなかった場合は nil**（= 測れなかった）。

   ## :archived —— 「まだ着地していない」ではなく「着地できない」

   実測 2026-09-07: この tick は archived な repo を候補に出していた。
   `open-banking` に agent を 1 本投げ、11 分 194k token を使ったあとに
   `This repository was archived so it is read-only.` で push が拒否された ——
   **着地できない仕事と、まだ着地していない仕事が、同じ顔で候補に並んでいた。**
   west.yml は最初からそれを知っていて（`groups: [archived]` /
   `userdata: {archived: true}`）、tick が訊いていなかっただけ。
   同じ形の repo は測った時点で pool に 9 件在る。

   ⚠ **これは GitHub の状態の写しであって GitHub ではない。** manifest が
   遅れている可能性は消えないので、最終候補には `archived-on-github` で
   直接訊く（下）。ここは全候補に当てられる安い一次フィルタ。

   ## :paths —— 改名前の古い checkout を候補にしない

   実測 2026-09-07: 候補に出た `orgs/cloud-itonami/open-cofog` は west.yml にも
   fleet-db にも無かった。GitHub 側で **`org-un-cofog` に改名済み**で、この path は
   改名前の残骸だったため（`gh api repos/cloud-itonami/open-cofog` は
   `cloud-itonami/org-un-cofog` を返す）。

   残骸が厄介なのは、**誰もそれを更新しないので永久に候補であり続ける**こと。
   pin が無いので `west update` が触らず、`svelte/` が消えないまま毎周
   候補に並び、毎周 agent が『移行』して no-op を着地させる。実際この周は
   `open-cofog`（残骸）と `org-un-cofog`（本体）が**同じ 1 個の GitHub repo に
   対して 2 つの候補として並んだ**。

   撤去は git-cleanup-conflict の仕事なので、ここでは候補にしないだけにする。
   **名前を出して報告する**（黙って除くと移行が進んだように見える）。"
  []
  (try
    (let [txt   (str (.readFileSync fs (path.join root "manifest" "west.yml") "utf8"))
          state (reduce
                 (fn [{:keys [cur] :as st} l]
                   (cond
                     (re-find #"^\s*- name:\s*\S" l)
                     (assoc st :cur nil)

                     (re-find #"^\s*path:\s*\S" l)
                     (let [p (second (re-find #"^\s*path:\s*(\S+)" l))]
                       (-> st (assoc :cur p) (update :paths conj p)))

                     (and cur (re-find #"^\s*groups:.*\barchived\b" l))
                     (update st :acc conj cur)

                     (and cur (re-find #"^\s*archived:\s*true\s*$" l))
                     (update st :acc conj cur)

                     :else st))
                 {:cur nil :acc #{} :paths #{}}
                 (str/split-lines txt))]
      ;; evidence floor —— path を 1 件も拾えていないなら parse が壊れている。
      ;; 空集合を「archived は無い」「登録が無い」として返さない（ADR-2608136000）。
      (when (seq (:paths state))
        {:paths (:paths state) :archived (:acc state)}))
    (catch :default _ nil)))

(defn- archived-on-github
  "GitHub 本体に訊く。true / false / :unmeasured。

   manifest の印は写しなので、**agent に渡す直前の候補だけ**はここで直接訊く。
   訊けなかったことを false に畳まない —— それをやると、この関数が在ることで
   かえって「確かめた」という誤った安心が付く。"
  [{:keys [org name]}]
  (try
    (let [r (.spawnSync cp "gh"
                        (clj->js ["api" (str "repos/" org "/" name) "--jq" ".archived"])
                        #js {:encoding "utf8" :timeout 30000})]
      (if (not= 0 (aget r "status"))
        :unmeasured
        (case (str/trim (str (aget r "stdout")))
          "true"  true
          "false" false
          :unmeasured)))
    (catch :default _ :unmeasured)))

(defn- landed-on-github
  "その repo の **default branch** に `.svelte` がまだ在るか。
   true = もう無い（着地済み）/ false = まだ在る / :unmeasured。

   ## なぜ要るか —— 走査しているのは checkout であって main ではない

   `scan!` は `orgs/` の**作業ツリー**を歩く。west は pin で checkout を止めるので、
   **上流で移行が着地しても、pin が手前にある限り `.svelte` はディスクに残る。**
   その repo は毎周候補として出続け、投げられた agent は「もう終わっている」と
   気づくところから始めることになる。

   実測 2026-09-07: 候補 4 件のうち **3 件が既に main で移行済み**だった
   （open-jpn-gov / outreach / port）。3 agent 分の仕事が、着地済みの repo の
   再監査に費やされた。checkout・west pin・repo の main は 3 つの別物という
   ADR-2608136800 の形そのもので、**この tick は 3 番目を一度も見ていなかった。**

   ## truncated を 0 件に畳まない

   trees API は巨大な tree で `truncated: true` を返し、その時 `.tree` は
   **途中まで**しか入っていない。件数だけ見ると 0 件＝着地済みに見えるので、
   truncated なら :unmeasured を返す。測れなかった検査が、測って問題が無かった
   検査と同じ値を返してはいけない（ADR-2608136000）。"
  [{:keys [org name]}]
  (try
    (let [r (.spawnSync cp "gh"
                        (clj->js ["api" (str "repos/" org "/" name "/git/trees/HEAD?recursive=1")
                                  "--jq" "[(.truncated|tostring), ([.tree[].path|select(test(\"\\\\.svelte$\"))]|length|tostring)]|join(\"\\t\")"])
                        #js {:encoding "utf8" :timeout 60000})]
      (if (not= 0 (aget r "status"))
        :unmeasured
        (let [[trunc n] (str/split (str/trim (str (aget r "stdout"))) #"\t")]
          (cond (= "true" trunc)   :unmeasured
                (not= "false" trunc) :unmeasured
                (nil? n)           :unmeasured
                (= "0" n)          true
                :else              false))))
    (catch :default _ :unmeasured)))

(defn- custody-gated?
  "その repo が **machine-enforced な custody 契約**を持っていれば true。触らない。

   実測 2026-08-26: 投げた agent が 4 回**正しく拒否した**。それらの repo は
   `migration.edn` で「N ファイル / M バイトを出所から verbatim に持ってきた」と
   宣言し、検査器がそれを **sha256 で pin して今日 PASS している**。`svelte/` を
   消すと 12/14 とか 9/19 とかの pin が外れ、**直しようのない FAIL** になる。
   文書の陳腐化ではなく**契約違反**で、移行するなら migration.edn と検査器を
   書き換える統治判断が要る —— バッチの agent が単独で決めることではない。

   ⚠ **ファイル名で判定しない。** 最初 `docs/verify-custody.cljs` だけを見ていて
   **3 つ取りこぼした**。実際に使われている名前と場所は少なくとも 4 通り:

     docs/verify-custody.cljs           app-global, app-maps, app-roukisho, …
     docs/verify-docs-claims.cljs       app-sos
     docs/check-migration-identity.cljs gol-d-roger
     scripts/verify-docs-claims.cljs    app-public-kafun-bokumetsu

   だから**中身で判定する**: `docs/` か `scripts/` の `.cljs` が `migration.edn`
   か `svelte/` に言及していれば custody 契約とみなす。

   判別能は既知の陽性 9 件・陰性 9 件（実際に移行が通った repo）で検証済み ——
   9/9 と 9/9。**両方向を見てから landed とした。**"
  [{:keys [repo]}]
  (try
    (let [r (.spawnSync cp "sh"
                        (clj->js
                         ["-c"
                          (str "find " (path.join root repo)
                               " \\( -path '*/docs/*.cljs' -o -path '*/scripts/*.cljs' \\)"
                               " -not -path '*/node_modules/*' 2>/dev/null"
                               " | xargs grep -l -e 'migration\\.edn' -e 'svelte/' 2>/dev/null"
                               " | head -1")])
                        #js {:encoding "utf8" :timeout 30000})]
      (not (str/blank? (str (aget r "stdout")))))
    (catch :default _ false)))

(defn- has-linked-worktree?
  "その repo に linked worktree が在れば true（= 誰かが今まさに作業中）。

   ⚠ **remote の branch だけを見ていては足りない。** 実測 2026-08-26: loop が
   起きた瞬間、手で走らせていた 4 agent はまだ branch を push しておらず、
   `IN-FLIGHT-SKIPPED 0` と出て **loop が同じ 4 repo を選んだ**。loop の agent は
   別の path・別の branch 名（`/private/tmp/wave-sys-tms` / `agent/svelte-to-cljs`）
   を使ったので git は衝突せず、**2 つの agent が同じ repo を二重に移行しかけた**。

   agent は作業開始時に必ず linked worktree を作るので、**push より早く立つ印**が
   これ。branch 名や path を決め打ちせず、linked worktree が 1 つでも在れば触らない。

   ⚠ **ただし「在る」のは実体であって台帳の行ではない。** `git worktree list` は
   **実体が消えた worktree も 1 行として出し続ける**（`prunable <理由>` 付き）。
   それは「誰かが作業中」ではなく**残骸**である。

   実測 2026-09-07: IN-FLIGHT 15 件のうち **8 件が prunable のみ**で、
   その 8 repo の branch はどれも main から **0 ahead**（中身は既に着地済み、
   ディレクトリは OS の /tmp 掃除で消えていた）。残骸を live と数えていたので、
   候補が 4 本出るはずの周に **1 本しか出ず**、しかも出力は正常に見えた ——
   `git worktree prune` で 8 件消したら候補は 1 → 4 に戻った。
   **走っている波と、走った跡が、同じ顔で数えられていた**（ADR-2608136000）。"
  [{:keys [repo]}]
  (try
    (let [r (.spawnSync cp "git"
                        (clj->js ["-C" (path.join root repo) "worktree" "list" "--porcelain"])
                        #js {:encoding "utf8" :timeout 30000})]
      (if (not= 0 (aget r "status"))
        true                                     ; 訊けなければ触らない側に倒す
        ;; porcelain は空行区切りのレコード列で、先頭が本体。実体が消えたものには
        ;; `prunable` 行が付く。locked な worktree は git が prune しないので
        ;; prunable にならず、ここでも live 側に残る（正しい）。
        (let [linked (->> (str/split (str (aget r "stdout")) #"\n\s*\n")
                          (map str/trim)
                          (remove str/blank?)
                          rest)]
          (boolean (some #(not (re-find #"(?m)^prunable(\s|$)" %)) linked)))))
    (catch :default _ true)))

(defn- remote-name
  "その checkout の remote 名。**仮定せず git に訊く。**

   west の慣習では org 名だが、実測 2026-08-26 でそうでない checkout が在る ——
   `cloud-itonami-isic-3510` ほか 4 repo の remote は `origin` で、org 名で
   ls-remote すると `fatal: 'cloud-itonami' does not appear to be a git
   repository` (rc=128) になる。in-flight? はそれを保守側に倒して in-flight と
   数えていたので、**available な repo 5 本が in-flight として隠れ**、候補が
   4 本出るはずの周に 1 本しか出なかった。名前が分からないのと、branch が
   在るのは、別のことである（ADR-2608136000）。"
  [{:keys [repo org]}]
  (try
    (let [r (.spawnSync cp "git" (clj->js ["-C" (path.join root repo) "remote"])
                        #js {:encoding "utf8" :timeout 30000})
          names (->> (str/split-lines (str (aget r "stdout"))) (remove str/blank?) set)]
      (cond (contains? names org)    org
            (contains? names "origin") "origin"
            :else                    (first (sort names))))
    (catch :default _ nil)))

(defn- in-flight
  "別の波がその repo を持っていれば理由の keyword、持っていなければ nil。

   2 つの印を見る —— **linked worktree**（push 前、同じマシンの波だけ見える）と
   **remote の branch**（push 済み、他マシンの波も見える）。前者だけでは
   push 済みで worktree を畳んだ波を見落とし、後者だけでは agent の起動から
   push までの窓が空く（実測 2026-08-26、そこで loop と手動の波が衝突した）。

   **訊けなかった場合は `:unmeasured` を返す。** 触らない点は in-flight と同じ
   だが、報告では分ける —— 「別の波が持っている」と「こちらが測れなかった」を
   同じ数字に畳むと、器の故障がプールの混雑に見える。"
  [c]
  (if (has-linked-worktree? c)
    :worktree
    (if-let [rem (remote-name c)]
      (try
        (let [r (.spawnSync cp "git"
                            (clj->js ["-C" (path.join root (:repo c))
                                      "ls-remote" "--heads" rem "agent/cljs-migration"])
                            #js {:encoding "utf8" :timeout 30000})]
          (cond (not= 0 (aget r "status"))        :unmeasured
                (str/blank? (str (aget r "stdout"))) nil
                :else                             :branch))
        (catch :default _ :unmeasured))
      :unmeasured)))

(defn -main []
  (let [rels (scan!)]
    (when (nil? rels)
      (println "REFUSING\tfind(1) failed — the scan did not run")
      (println "SCANNED\t0")
      (js/process.exit 2))
    (when (zero? (count rels))
      (println "REFUSING\tscanned 0 .svelte files — the walk is broken, not the tree")
      (println "SCANNED\t0")
      (js/process.exit 2))

    (when (nil? (manifest-facts))
      (println "REFUSING\tmanifest/west.yml を読めなかった —— archived / 登録の判定ができない")
      (println (str "SCANNED\t" (count rels)))
      (js/process.exit 2))

    (let [by-repo (reduce (fn [m r] (update m (repo-of r) (fnil conj []) r)) {} rels)
          {:keys [paths archived]} (manifest-facts)
          arch    archived
          pool    (count by-repo)
          cands   (->> by-repo
                       (keep (fn [[repo fl]]
                               (when (<= (count fl) max-files)
                                 (let [n (reduce + (map lines-of fl))]
                                   {:repo repo
                                    :files (count fl)
                                    :lines n
                                    :svelte-dir (svelte-dir fl)
                                    :org (second (str/split repo #"/"))
                                    :name (nth (str/split repo #"/") 2)}))))
                       (filter :svelte-dir)
                       (sort-by (juxt :lines :repo))
                       vec)
          ;; in-flight 判定は 1 repo 1 network round trip なので、順位上位だけに当てる。
          ;; custody 判定はローカル（安い）ので全候補に当てる。
          custody (atom [])
          ;; archived は「まだ着地していない」ではなく「着地できない」。
          ;; ローカルで判る（west.yml の写し）ので全候補に当てる。
          archived (atom [])
          ;; 改名前の残骸は誰も更新しないので、除かないと毎周候補に出続ける。
          unregistered (atom [])
          ;; 上流で着地済みなのに pin が手前で止まっている repo。
          ;; 走査しているのは checkout なので、main を訊かないと毎周出続ける。
          landed (atom [])
          open    (->> cands
                       (remove (fn [c]
                                 (when-not (contains? paths (:repo c))
                                   (swap! unregistered conj (:repo c)) true)))
                       (remove (fn [c]
                                 (when (contains? arch (:repo c))
                                   (swap! archived conj (:repo c)) true)))
                       (remove (fn [c]
                                 (when (custody-gated? c)
                                   (swap! custody conj (:repo c)) true)))
                       vec)
          ;; in-flight 判定は 1 repo 1 network round trip なので、順位上位だけに当てる。
          ranked  (take (* 4 limit) open)
          skipped (atom [])
          unmeasured (atom [])
          picked  (->> ranked
                       (remove (fn [c]
                                 (case (in-flight c)
                                   nil         false
                                   :unmeasured (do (swap! unmeasured conj (:repo c)) true)
                                   (do (swap! skipped conj (:repo c)) true))))
                       ;; manifest は写しなので、渡す直前の候補だけ GitHub に直接訊く。
                       (remove (fn [c]
                                 (case (archived-on-github c)
                                   false       false
                                   :unmeasured (do (swap! unmeasured conj (:repo c)) true)
                                   (do (swap! archived conj (:repo c)) true))))
                       ;; ディスクの .svelte は checkout の話。main を訊くまで
                       ;; 「まだ移行していない」は未測定（ADR-2608136800）。
                       (remove (fn [c]
                                 (case (landed-on-github c)
                                   false       false
                                   :unmeasured (do (swap! unmeasured conj (:repo c)) true)
                                   (do (swap! landed conj (:repo c)) true))))
                       (take limit)
                       vec)
          rec {:at (.toISOString (js/Date.))
               :pool pool
               :files (count rels)
               :eligible (count (filter #(<= (count (second %)) max-files) by-repo))
               :limit limit
               :unregistered-skipped @unregistered
               :archived-skipped @archived
               :already-landed-skipped @landed
               :custody-skipped @custody
               :in-flight-skipped @skipped
               :unmeasured-skipped @unmeasured
               :candidates picked}
          cands picked]

      (println (str "SCANNED\t" (count rels)))
      (println (str "POOL\t" pool "\trepos still carrying .svelte"))
      (println (str "ELIGIBLE\t" (:eligible rec) "\t(<=" max-files " files/repo)"))
      ;; 「着地できない」を「まだ着地していない」に畳まない。
      (println (str "UNREGISTERED-SKIPPED\t" (count @unregistered)
                    (when (seq @unregistered) (str "\t" (str/join " " @unregistered)))))
      (println (str "ARCHIVED-SKIPPED\t" (count @archived)
                    (when (seq @archived) (str "\t" (str/join " " @archived)))))
      ;; 「checkout に残っている」を「まだ移行していない」に畳まない。
      (println (str "ALREADY-LANDED-SKIPPED\t" (count @landed)
                    (when (seq @landed) (str "\t" (str/join " " @landed)))))
      (println (str "CUSTODY-SKIPPED\t" (count @custody)
                    (when (seq @custody) (str "\t" (str/join " " @custody)))))
      (println (str "IN-FLIGHT-SKIPPED\t" (count @skipped)
                    (when (seq @skipped) (str "\t" (str/join " " @skipped)))))
      ;; 「測れなかった」を in-flight に畳まない —— 器の故障が混雑に見える。
      (println (str "UNMEASURED-SKIPPED\t" (count @unmeasured)
                    (when (seq @unmeasured) (str "\t" (str/join " " @unmeasured)))))
      (println (str "CANDIDATES\t" (count cands)))
      (doseq [c cands]
        (println (str "  " (str/join "\t" [(:repo c) (str (:files c) "f")
                                           (str (:lines c) "L") (:svelte-dir c)]))))
      (try (.appendFileSync fs ledger-file (str (pr-str rec) "\n"))
           (catch :default e (println "ledger 追記失敗:" (str e))))
      (js/process.exit 0))))

(-main)
