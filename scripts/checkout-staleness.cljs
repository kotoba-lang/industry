#!/usr/bin/env nbb
;; orgs/ 配下の全 checkout について「自分の remote の default branch からどれだけ
;; ずれているか」を **network round trip なしで** 測る。
;;
;; なぜ要るか (ADR-2608136800):
;;   checkout・west pin・repo の main は別物である、という規則は CLAUDE.md に
;;   何度も書かれているが、2026-08-13 の 1 日だけで 4 回破られた。うち 1 回は
;;   ADR-2608135200 の初版で、127 commit 遅れた共有 checkout の deps.edn を
;;   正本として読み「988 commit 遅れている」と書き、その数字が 2 つの agent
;;   指示に転記された。**prose では直らなかったので測る。**
;;
;; 測り方と、その限界(正直に):
;;   - 距離は **ローカルの remote-tracking ref** に対して測る。つまり
;;     **最後に fetch した時点の remote が基準**で、それより後に upstream が
;;     進んでいれば実際の遅れはもっと大きい。だから各 checkout の
;;     `FETCH_HEAD` の mtime を一緒に報告する —— 読み手が「いつの測定か」を
;;     判断できないと、この script 自身が stale な数字の発生源になる。
;;   - **fetch はしない。** 4,415 checkout を fetch するのは論外だし、
;;     hook から呼べなくなる。
;;   - **shallow な checkout の距離は測らない**(`:behind :unavailable`)。
;;     shallow clone は ancestry に誤った答えを、正しい答えと同じ顔で返す
;;     (ADR-2608124400)。推測した数字を出すより「不明」と言う方が有用。
;;   - remote は **`origin` とは限らない**。west は remote に org 名を付ける
;;     ので、4,406 checkout のうち 2,824 には `origin` remote が無い。
;;     `git remote | head -1` は annex repo で `b2` を選ぶ(このワークスペースで
;;     3 回踏んだ誤り)。**URL に github.com を含む remote を選ぶ。**
;;   - **remote-tracking ref はほとんど存在しない。** west は名前付き remote では
;;     なく **URL を直に指定して fetch する**ので、`refs/remotes/<r>/*` が書かれ
;;     ない。実測(2026-08-13): 4,415 checkout のうち `refs/remotes/` ディレクトリ
;;     が在るのは 2,356、しかもその多くは空。**`.git/refs/remotes/...` だけを
;;     見に行くと 4,000 件超を「測定不能」として捨てることになる。**
;;     代わりに **`FETCH_HEAD` を読む** —— 最後の fetch が持ち帰った各 branch の
;;     sha がそのまま書いてある(4,415 中 4,317 が `branch 'main'` 行を持つ)。
;;     これも当然「最後の fetch 時点」の値なので mtime を併記する。
;;
;; 3 段階で走る:
;;   phase 1  pure fs   全 checkout。gitdir / HEAD / remote-tracking ref /
;;                      shallow graft 数 / FETCH_HEAD mtime。git を起動しない。
;;   phase 2  git       phase 1 で HEAD sha ≠ remote sha だったものだけ
;;                      `rev-list --left-right --count` で ahead/behind。
;;   phase 3  git       `status --porcelain` で dirty 判定(全件)。
;;
;; 結果の重み付け: **遅れ自体は害ではない。誰かが読んでいるかどうかが害である。**
;; `:local/root` edge・superproject の script/hook・launchd plist・fleet-ci gate
;; が解決先として持つ checkout を「consequential」として印を付け、
;; `manifest/checkout-consequence.edn` に書き出す(hook はこれを読む)。
;;
;; 使い方:
;;   nbb scripts/checkout-staleness.cljs                 # 人が読む要約
;;   nbb scripts/checkout-staleness.cljs --edn out.edn   # 全件を EDN で
;;   nbb scripts/checkout-staleness.cljs --write-consequence
;;   nbb scripts/checkout-staleness.cljs --root <dir> --jobs 8 --limit 200

(require '[clojure.string :as str])

(def fs   (js/require "node:fs"))
(def path (js/require "node:path"))
(def os   (js/require "node:os"))
(def cp   (js/require "node:child_process"))

;; ---------------------------------------------------------------- args

(def argv (vec *command-line-args*))

(defn flag? [n] (some #(= n %) argv))
(defn opt [n d]
  (let [i (.indexOf argv n)]
    (if (neg? i) d (get argv (inc i) d))))

(def root  (or (not-empty (opt "--root" nil))
               (not-empty (str (or js/process.env.CLAUDE_PROJECT_DIR "")))
               (.cwd js/process)))
(def jobs  (js/parseInt (opt "--jobs" "8")))
(def limit (when-let [l (opt "--limit" nil)] (js/parseInt l)))

(defn load1 [] (aget (.loadavg os) 0))

;; ---------------------------------------------------------------- fs helpers

(defn slurp* [p]
  (try (.readFileSync fs p "utf8") (catch :default _ nil)))

(defn exists? [p] (try (.existsSync fs p) (catch :default _ false)))

(defn mtime-ms [p]
  (try (.-mtimeMs (.statSync fs p)) (catch :default _ nil)))

(defn dirents [p]
  (try (vec (.readdirSync fs p #js {:withFileTypes true})) (catch :default _ [])))

;; ---------------------------------------------------------------- checkout enumeration

(defn checkouts
  "orgs/<org>/<repo> を列挙する。深さ 2 固定 —— これが west の path 契約。"
  [base]
  (let [od (.join path base "orgs")]
    (when (exists? od)
      (vec
        (for [o (dirents od) :when (.isDirectory o)
              :let [op (.join path od (.-name o))]
              r (dirents op) :when (.isDirectory r)]
          {:checkout (.join path op (.-name r))
           :label    (str "orgs/" (.-name o) "/" (.-name r))})))))

;; ---------------------------------------------------------------- git plumbing, read directly

(defn resolve-gitdir
  "checkout パス → gitdir。`.git` がディレクトリならそれ、`gitdir: <p>` の
   ポインタファイルなら解決先。どちらでもなければ nil(= repo ではない)。"
  [c]
  (let [g (.join path c ".git")]
    (try
      (let [st (.lstatSync fs g)]
        (cond
          (.isDirectory st) g
          (.isFile st) (when-let [m (re-find #"(?m)^gitdir:\s*(.+)$" (or (slurp* g) ""))]
                         (.resolve path (.dirname path g) (str/trim (second m))))
          :else nil))
      (catch :default _ nil))))

(defn common-dir
  "linked worktree の gitdir は `commondir` ファイルで元 repo を指す。
   shallow ファイル・packed-refs・config は **common dir 側**にある。"
  [gitdir]
  (let [cd (.join path gitdir "commondir")]
    (if-let [t (slurp* cd)]
      (.resolve path gitdir (str/trim t))
      gitdir)))

(defn packed-refs
  "packed-refs を {ref sha} に。無ければ空 map。peeled 行(`^`)は捨てる。"
  [cdir]
  (if-let [txt (slurp* (.join path cdir "packed-refs"))]
    (into {}
          (keep (fn [line]
                  (when-not (or (str/blank? line)
                                (str/starts-with? line "#")
                                (str/starts-with? line "^"))
                    (let [[sha ref] (str/split (str/trim line) #"\s+" 2)]
                      (when (and sha ref) [ref sha]))))
                (str/split-lines txt)))
    {}))

(defn resolve-ref
  "ref 名 → sha。loose ref ファイルを先に見て、無ければ packed-refs。
   `ref: <other>` の symref は 1 段だけ追う(remotes/<r>/HEAD がこの形)。"
  [cdir packed ref]
  (let [loose (slurp* (.join path cdir ref))]
    (cond
      (and loose (str/starts-with? (str/trim loose) "ref: "))
      (let [target (str/trim (subs (str/trim loose) 5))]
        (or (some-> (slurp* (.join path cdir target)) str/trim not-empty)
            (get packed target)))

      (and loose (not (str/blank? loose))) (str/trim loose)
      :else (get packed ref))))

(defn symref-target
  "`ref: refs/remotes/<r>/<b>` を書いた ref ファイルの指し先を返す。"
  [cdir ref]
  (when-let [t (slurp* (.join path cdir ref))]
    (when (str/starts-with? (str/trim t) "ref: ")
      (str/trim (subs (str/trim t) 5)))))

(defn head-info
  "HEAD → {:branch <name>|nil :sha <sha>|nil}。detached なら :branch nil。"
  [gitdir cdir packed]
  (let [h (some-> (slurp* (.join path gitdir "HEAD")) str/trim)]
    (cond
      (nil? h) {}
      (str/starts-with? h "ref: ")
      (let [ref (str/trim (subs h 5))]
        {:branch (str/replace ref #"^refs/heads/" "")
         :ref    ref
         :sha    (resolve-ref cdir packed ref)})
      :else {:branch nil :sha h})))

(defn remotes
  "config から [{:name :url}] を順序保存で。"
  [cdir]
  (let [txt (or (slurp* (.join path cdir "config")) "")]
    (loop [lines (str/split-lines txt), cur nil, acc []]
      (if-let [l (first lines)]
        (let [t (str/trim l)]
          (cond
            (re-find #"^\[remote\s+\"" t)
            (recur (next lines) (second (re-find #"^\[remote\s+\"([^\"]+)\"" t)) acc)

            (str/starts-with? t "[")
            (recur (next lines) nil acc)

            (and cur (re-find #"^url\s*=" t))
            (recur (next lines) cur
                   (conj acc {:name cur :url (str/trim (str/replace t #"^url\s*=\s*" ""))}))

            :else (recur (next lines) cur acc)))
        acc))))

(defn pick-remote
  "**`origin` を仮定しない。** west は remote に org 名を付けるので、
   4,406 checkout のうち 2,824 に `origin` は無い。annex repo には `b2` が
   居るので `git remote | head -1` も誤る(実測 3 回)。
   URL に github.com を含むものを採り、その中で origin を優先する。"
  [rs]
  (let [gh (filter #(str/includes? (str/lower-case (or (:url %) "")) "github.com") rs)]
    (or (first (filter #(= "origin" (:name %)) gh))
        (first gh)
        ;; github が無ければ origin、それも無ければ諦める(b2 等を選ばない)
        (first (filter #(= "origin" (:name %)) rs)))))

(defn remote-default-branch
  "remote の default branch 名。refs/remotes/<r>/HEAD の symref が正。
   無ければ実在する remote-tracking ref から main → master → trunk の順で拾う。
   **推測した名前を『既定』として報告しない** —— 由来を :via で返す。"
  [cdir packed rname]
  (let [hd (str "refs/remotes/" rname "/HEAD")]
    (if-let [t (or (symref-target cdir hd) (get packed hd))]
      {:branch (str/replace t (re-pattern (str "^refs/remotes/" rname "/")) "") :via :head-symref}
      (let [try-one (fn [b] (when (resolve-ref cdir packed (str "refs/remotes/" rname "/" b)) b))]
        (when-let [b (some try-one ["main" "master" "trunk"])]
          {:branch b :via :guessed})))))

(defn parse-fetch-head
  "FETCH_HEAD を [{:sha :for-merge? :kind :name :url}] に。
   1 行の形は `<sha>\\t<not-for-merge|空>\\t<description>`。
   description は `branch 'main' of git@github.com:org/repo` 等。"
  [txt]
  (when txt
    (keep (fn [line]
            (let [[sha mid desc] (str/split line #"\t" 3)]
              (when (and sha desc (re-find #"^[0-9a-f]{40}$" sha))
                (let [m (re-find #"^(branch|tag)\s+'([^']+)'\s+of\s+(.*)$" (str/trim desc))]
                  (when m
                    {:sha sha :for-merge? (str/blank? (or mid ""))
                     :kind (keyword (nth m 1)) :name (nth m 2) :url (str/trim (nth m 3))})))))
          (str/split-lines txt))))

(defn fetch-head-default
  "FETCH_HEAD から default branch の sha を拾う。
   main → master → for-merge 行、の順。**推測の順序を隠さない**ので :via を返す。"
  [entries]
  (let [branches (filter #(= :branch (:kind %)) entries)
        by-name  (fn [n] (first (filter #(= n (:name %)) branches)))]
    (or (some-> (by-name "main")   (assoc :via :fetch-head-main))
        (some-> (by-name "master") (assoc :via :fetch-head-master))
        (some-> (first (filter :for-merge? branches)) (assoc :via :fetch-head-for-merge)))))

(defn graft-count
  "**shallow ファイルの存在ではなく graft 行数**で判定する。
   `git rev-parse --is-shallow-repository` は中身が空でも true を返す
   (shallow.c の is_repository_shallow が fopen 成功時点で確定させるため)。"
  [cdir]
  (let [f (.join path cdir "shallow")]
    (if-not (exists? f)
      0
      (count (remove str/blank? (str/split-lines (or (slurp* f) "")))))))

(defn scan-one
  "pure fs のみ。git を 1 回も起動しない。"
  [{:keys [checkout label]}]
  (if-let [gitdir (resolve-gitdir checkout)]
    (let [cdir   (common-dir gitdir)
          packed (packed-refs cdir)
          head   (head-info gitdir cdir packed)
          rs     (remotes cdir)
          r      (pick-remote rs)
          grafts (graft-count cdir)
          fh     (mtime-ms (.join path cdir "FETCH_HEAD"))
          db     (when r (remote-default-branch cdir packed (:name r)))
          rref   (when (and r db) (str "refs/remotes/" (:name r) "/" (:branch db)))
          tsha   (when rref (resolve-ref cdir packed rref))
          ;; **remote-tracking ref を無条件に信用しない。** west が URL 直指定で
          ;; fetch すると refs/remotes は取り残され、FETCH_HEAD だけが進む。
          ;; 実測(2026-08-13): この取り違えで com-* 系 ~680 repo が
          ;; 「+2 ahead」に見えていたが、FETCH_HEAD では HEAD と同一だった ——
          ;; **stale な観測を正本として読む**という、この ADR がまさに対象に
          ;; している誤りを、測定器の側でやっていた。
          ;; だから候補を 2 つとも持ち、phase 2 で **より進んだ方**を採る。
          fhd    (fetch-head-default (parse-fetch-head (slurp* (.join path cdir "FETCH_HEAD"))))
          cands  (distinct (remove nil? [(when tsha {:sha tsha :via (or (:via db) :tracking-ref)})
                                         (when (:sha fhd) {:sha (:sha fhd) :via (:via fhd)})]))
          rsha   (:sha (first cands))
          rbr    (or (:branch db) (:name fhd))]
      (cond-> {:label label :checkout checkout
               :branch (:branch head) :head (:sha head)
               :remote (:name r) :remote-url (:url r)
               :remote-branch rbr :remote-branch-via (:via (first cands))
               :remote-ref rref :remote-head rsha :remote-candidates (vec cands)
               :grafts grafts :fetch-head-ms fh
               :remote-count (count rs)}
        (nil? r)             (assoc :status :no-github-remote)
        (and r (nil? rsha))  (assoc :status :no-remote-answer)
        (pos? grafts)        (assoc :status :shallow :behind :unavailable :ahead :unavailable)
        (and (seq cands) (:sha head) (zero? grafts)
             (every? #(= (:sha head) (:sha %)) cands))
        (assoc :status :current :behind 0 :ahead 0)))
    {:label label :checkout checkout :status :not-a-repo}))

;; ---------------------------------------------------------------- git phase (bounded concurrency)

(defn run-git
  "git を 1 本走らせて promise を返す。失敗は nil に潰す(fail-open)。"
  [dir args]
  (js/Promise.
    (fn [resolve* _]
      (.execFile cp "git" (clj->js (into ["-C" dir] args))
                 #js {:encoding "utf8" :timeout 25000 :maxBuffer (* 8 1024 1024)}
                 (fn [err stdout _] (resolve* (when-not err (str/trim (or stdout "")))))))))

(defn pool
  "items を n 並列で f にかける。f は promise を返す。全部終わったら promise。"
  [n items f]
  (js/Promise.
    (fn [done _]
      (let [items (vec items)
            total (count items)
            idx   (atom 0)
            live  (atom 0)]
        (if (zero? total)
          (done nil)
          (letfn [(pump []
                    (while (and (< @live n) (< @idx total))
                      (let [i (first (swap-vals! idx inc))]
                        (when (< i total)
                          (swap! live inc)
                          (-> (f (nth items i))
                              (.then (fn [_] (swap! live dec)
                                       (if (and (>= @idx total) (zero? @live)) (done nil) (pump))))
                              (.catch (fn [_] (swap! live dec)
                                        (if (and (>= @idx total) (zero? @live)) (done nil) (pump)))))))))]
            (pump)))))))

(defn parse-lr
  "`rev-list --left-right --count A...B` の出力 \"<ahead>\\t<behind>\"。
   A=HEAD 側が left なので left=ahead、right=behind。"
  [s]
  (when s
    (let [[a b] (str/split (str/trim s) #"\s+")]
      (when (and a b) {:ahead (js/parseInt a) :behind (js/parseInt b)}))))

;; ---------------------------------------------------------------- consequence: who reads it

(defn read-tracked-text
  "superproject 側の、hardcode を探して良い範囲のファイル一覧。
   **orgs/ は歩かない**(4,415 checkout の全文検索は必ず時間切れになる)。"
  [base]
  (let [dirs ["scripts" ".claude/hooks" "manifest" "70-tools"]
        acc  (atom [])]
    (letfn [(walk [d depth]
              (when (< depth 5)
                (doseq [e (dirents d)]
                  (let [p (.join path d (.-name e))]
                    (cond
                      (and (.isDirectory e)
                           (not (contains? #{"node_modules" ".git" "target" ".cpcache"} (.-name e))))
                      (walk p (inc depth))
                      (and (.isFile e)
                           (re-find #"\.(cljs|cljc|clj|edn|yml|yaml|json|plist)$" (.-name e)))
                      (swap! acc conj p))))))]
      (doseq [d dirs]
        (let [p (.join path base d)] (when (exists? p) (walk p 0)))))
    @acc))

(defn west-pins
  "manifest/west.yml の `path:` → `revision:` を拾う。west.yml は生成物なので
   YAML パーサを持ち込まず行指向で読む(この 2 キーの並びは生成器が固定している)。
   **pin は 3 つ目の状態**である —— checkout でも repo の main でもない。
   ADR-2608135200 の事故はこの 3 つを取り違えたことから起きた。"
  [base]
  (let [txt (or (slurp* (.join path base "manifest" "west.yml")) "")]
    ;; ⚠ 生成器が出す順序は **`revision:` が先、`path:` が後**。
    ;; 逆に仮定した初版は 4,164 件すべてで pin を取りこぼし
    ;; 「pin == checkout は 0 件」というありえない値を出した ——
    ;; **0 も 4,164 も、目で見て変だと分かる値だから気づけた。**
    ;; 中間の値だったら、そのまま ADR に載っていた。
    (loop [lines (str/split-lines txt), rev nil, acc {}]
      (if-let [l (first lines)]
        (cond
          (re-find #"^\s*revision:\s*([0-9a-f]{40})\s*$" l)
          (recur (next lines) (second (re-find #"^\s*revision:\s*([0-9a-f]{40})\s*$" l)) acc)
          (re-find #"^\s*path:\s*(\S+)" l)
          (recur (next lines) nil
                 (if rev (assoc acc (second (re-find #"^\s*path:\s*(\S+)" l)) rev) acc))
          :else (recur (next lines) rev acc))
        acc))))

(defn launch-agent-files []
  (let [d (.join path (.homedir os) "Library" "LaunchAgents")]
    (if (exists? d)
      (->> (dirents d)
           (filter #(.isFile %))
           (map #(.join path d (.-name %)))
           vec)
      [])))

(defn local-root-edges
  "各 checkout の root deps.edn の `:local/root \"...\"` を解決する。
   **文字列として拾う** —— deps.edn を EDN として読むと 1 件の壊れたファイルで
   全体が落ちるし、reader tag も踏む。ここで欲しいのは辺だけ。"
  [cs]
  (let [acc (atom {})]
    (doseq [{:keys [checkout label]} cs]
      (let [f (.join path checkout "deps.edn")]
        (when-let [txt (slurp* f)]
          (doseq [m (re-seq #":local/root\s+\"([^\"]+)\"" txt)]
            (let [target (.resolve path checkout (second m))]
              (swap! acc update target (fnil conj #{}) (str label "/deps.edn")))))))
    @acc))

(def registry-threshold
  "1 ファイルがこの数より多くの checkout を名指ししていたら、それは
   **consumer ではなく registry** —— west.yml / fleet-db.edn / repo-maturity.edn の
   ように全 repo を列挙するだけの目録である。目録に載っていることは
   「何かがそれを読んでいる」ことを意味しない。閾値で切るのは乱暴に見えるが、
   実測の分布は二峰性で(consumer は 1〜10 件、registry は 400〜4,400 件)、
   中間に何も無い。閾値を変えても答えは動かない。"
  30)

(defn hardcoded-edges
  "superproject の script/hook/manifest/plist に `orgs/<org>/<repo>` として
   直接書かれている checkout。**その文字列が実在の checkout を指すときだけ**
   数える(regex の巻き添えを避ける)。registry ファイルは除外し、その一覧を
   :registries として返す —— 黙って捨てると『なぜ数が減ったか』が追えない。"
  [base files valid-labels]
  (let [acc (atom {}) regs (atom [])]
    (doseq [f files]
      (when-let [txt (slurp* f)]
        (let [hits (into #{} (filter valid-labels)
                         (map first (re-seq #"orgs/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+" txt)))
              rel  (str/replace f (str base "/") "")]
          (if (> (count hits) registry-threshold)
            (swap! regs conj {:file rel :mentions (count hits)})
            (doseq [m hits]
              (swap! acc update (.join path base m) (fnil conj #{}) rel))))))
    {:edges @acc :registries @regs}))

;; ---------------------------------------------------------------- reporting

(defn ago [ms]
  (if (nil? ms)
    "never fetched"
    (let [d (/ (- (js/Date.now) ms) 86400000.0)]
      (cond (< d 1) (str (js/Math.round (* d 24)) "h ago")
            (< d 400) (str (js/Math.round d) "d ago")
            :else (str (.toFixed (/ d 365) 1) "y ago")))))

(defn fmt-row [{:keys [label behind ahead dirty untracked grafts fetch-head-ms status readers
                       remote-view-conflict branch head-state]}]
  (str label
       (when (= :on-named-branch head-state) (str " @" branch))
       " " (cond (= status :shallow) (str "behind=? (shallow, " grafts " grafts)")
                 (number? behind) (str "-" behind)
                 :else (str "[" (name (or status :unknown)) "]"))
       (when (and (number? ahead) (pos? ahead)) (str " +" ahead " AHEAD"))
       (when (and dirty (pos? dirty))
         (str " dirty=" dirty " (" (- dirty (or untracked 0)) "t/" (or untracked 0) "u)"))
       (when remote-view-conflict " [remote views disagree]")
       " fetched " (ago fetch-head-ms)
       (when (seq readers) (str " ← " (count readers) " reader(s): "
                               (str/join ", " (take 3 (sort readers)))))))

;; ---------------------------------------------------------------- main

(def t0 (js/Date.now))

(defn -main []
  (let [cs0 (checkouts root)
        cs  (if limit (vec (take limit cs0)) cs0)
        _   (println (str ";; root=" root " checkouts=" (count cs)
                          " load1=" (.toFixed (load1) 1)))
        p1  (js/Date.now)
        rows (mapv scan-one cs)
        _   (println (str ";; phase1 (pure fs, no git): " (- (js/Date.now) p1) "ms"))

        by-label (into {} (map (juxt :label identity)) rows)
        valid    (set (keys by-label))

        ;; consequence edges
        lr-edges (local-root-edges cs)
        files    (concat (read-tracked-text root) (launch-agent-files))
        hc       (hardcoded-edges root files valid)
        edges    (merge-with into lr-edges (:edges hc))
        _        (println (str ";; scanned " (count files) " superproject/LaunchAgent files; "
                               (count (:registries hc)) " were registries (excluded), "
                               (count edges) " checkouts have a real reader"))

        rows (mapv (fn [r]
                     (let [rd (get edges (:checkout r))]
                       (cond-> r (seq rd) (assoc :readers (vec rd)))))
                   rows)

        ;; phase 2: only where HEAD sha differs from the remote-tracking sha
        need-lr (filterv (fn [r] (and (:head r) (seq (:remote-candidates r))
                                      (not= :current (:status r))
                                      (zero? (or (:grafts r) 0))))
                         rows)
        _ (println (str ";; phase2 (git rev-list) on " (count need-lr) " divergent checkouts, jobs=" jobs))
        lr-res (atom {})
        st-res (atom {})]

    (-> (pool jobs need-lr
              (fn [r]
                ;; **候補ごとに測って、いちばん進んだ remote 観測を採る。**
                ;; tsha が fhsha の祖先なら behind(fhsha) >= behind(tsha) かつ
                ;; ahead(fhsha) <= ahead(tsha) が必ず成り立つので、
                ;; 「behind 最大・同値なら ahead 最小」を選べば新しい方が出る。
                ;; 順序が付かない(remote 観測どうしが分岐している)場合だけ
                ;; :remote-view-conflict を立てて、黙って片方を採ったことにしない。
                (-> (js/Promise.all
                      (clj->js
                        (mapv (fn [c]
                                (-> (run-git (:checkout r)
                                             ["rev-list" "--left-right" "--count"
                                              (str (:head r) "..." (:sha c))])
                                    (.then (fn [out] (some-> (parse-lr out) (assoc :via (:via c)
                                                                                   :sha (:sha c)))))))
                              (:remote-candidates r))))
                    (.then (fn [res]
                             (let [ok (remove nil? (vec res))]
                               (when (seq ok)
                                 (let [best (first (sort-by (juxt (comp - :behind) :ahead) ok))]
                                   (swap! lr-res assoc (:label r)
                                          (cond-> best
                                            (> (count (distinct (map :sha ok))) 1)
                                            (assoc :remote-view-conflict true))))))
                             nil)))))
        (.then (fn [_]
                 (println (str ";; phase2 done " (- (js/Date.now) t0) "ms, load1=" (.toFixed (load1) 1)))
                 (pool jobs rows
                       (fn [r]
                         (-> (run-git (:checkout r) ["status" "--porcelain"])
                             (.then (fn [out]
                                      (let [ls (remove str/blank? (str/split-lines (or out "")))
                                            un (count (filter #(str/starts-with? % "??") ls))]
                                        (swap! st-res assoc (:label r)
                                               {:dirty (count ls) :untracked un
                                                :tracked-dirty (- (count ls) un)}))
                                      nil)))))))
        (.then
          (fn [_]
            (let [rows (mapv (fn [r]
                               (let [lr (get @lr-res (:label r))
                                     d  (get @st-res (:label r))]
                                 (cond-> (merge r d)
                                   lr (merge (select-keys lr [:ahead :behind :remote-view-conflict]))
                                   lr (assoc :remote-head (:sha lr) :remote-branch-via (:via lr))
                                   (and lr (pos? (:behind lr))) (assoc :status :behind)
                                   (and lr (zero? (:behind lr)) (pos? (:ahead lr))) (assoc :status :ahead)
                                   (and lr (pos? (:behind lr)) (pos? (:ahead lr))) (assoc :status :diverged)
                                   (and lr (zero? (:behind lr)) (zero? (:ahead lr))) (assoc :status :current))))
                             rows)
                  ;; **「ahead」を「未 push の作業」と読まない。** 実測(2026-08-13):
                  ;; ahead + dirty だった 9 件の HEAD sha を GitHub に問い合わせたところ
                  ;; **9 件とも remote に存在した** —— どれも `agent/*` や
                  ;; `rescue/wip-*` という *push 済みの側枝* に checkout が停まって
                  ;; いるだけで、commit はどこにも無いどころか全部 landed していた。
                  ;; したがって危険な class は 2 つに割れる:
                  ;;   (a) default branch に居て ahead   = 本当に未 push の commit
                  ;;   (b) default branch に居ない       = 読む側が main ではない木を掴む
                  ;; そして「ここにしか無いもの」は commit ではなく **dirty file** である。
                  ;; **detached HEAD は west の既定であって異常ではない。**
                  ;; west は pin の sha へ detach する。初版はこれを「default branch に
                  ;; 居ない」として 4,391 件を異常に数えてしまい、数字が無意味になった
                  ;; —— 母集団のほぼ全部を含む class は何も指していない。
                  ;; 本当の異常は **名前つきの非 default branch に checkout が停まって
                  ;; いる**ことで、これは誰かが手で切り替えて戻していない痕跡である。
                  rows (mapv (fn [r]
                               (let [b (:branch r), d (:remote-branch r)
                                     st (cond (nil? b) :detached-at-pin
                                              (and d (= b d)) :on-default
                                              :else :on-named-branch)]
                                 (assoc r :head-state st
                                          :on-default? (= st :on-default))))
                             rows)
                  ;; 3 つ目の軸: west pin。**pin == checkout == remote の 3 点が
                  ;; 揃って初めて「同じもの」と言える。** `west update` が checkout を
                  ;; 合わせにいく先は pin であって remote の main ではないので、
                  ;; pin 自体が遅れていれば west update は遅れを直さない。
                  pins (west-pins root)
                  rows (mapv (fn [r]
                               (let [p (get pins (:label r))]
                                 (cond-> r
                                   p (assoc :pin p
                                            :pin=head (= p (:head r))
                                            :pin=remote (= p (:remote-head r))))))
                             rows)
                  total (count rows)
                  cnt   (fn [pred] (count (filter pred rows)))
                  n     (fn [k] (count (filter #(= k (:status %)) rows)))
                  dirty (filter #(pos? (or (:dirty %) 0)) rows)
                  ahead-dirty (filter #(and (pos? (or (:ahead %) 0)) (pos? (or (:dirty %) 0))) rows)
                  unpushed (filter #(and (:on-default? %) (pos? (or (:ahead %) 0))) rows)
                  hs (fn [k] (count (filter #(= k (:head-state %)) rows)))
                  named (filter #(= :on-named-branch (:head-state %)) rows)
                  named-read (filter :readers named)
                  shallow (filter #(pos? (or (:grafts %) 0)) rows)
                  behindish (filter #(pos? (or (:behind %) 0)) rows)
                  consequential (filter :readers rows)
                  never (filter #(nil? (:fetch-head-ms %)) rows)
                  old90 (filter #(and (:fetch-head-ms %)
                                      (> (- (js/Date.now) (:fetch-head-ms %)) (* 90 86400000))) rows)]

              (println)
              (println "==== distribution ====")
              (println (str "total checkouts            " total))
              (println (str "  current (== last fetch)  " (n :current)))
              (println (str "  behind only              " (n :behind)))
              (println (str "  ahead only               " (n :ahead)))
              (println (str "  diverged (both)          " (n :diverged)))
              (println (str "  shallow (behind unknown) " (count shallow)))
              (println (str "  no github remote         " (n :no-github-remote)))
              (println (str "  no local remote answer   " (n :no-remote-answer)))
              (println (str "  not a repo               " (n :not-a-repo)))
              (println (str "dirty working tree         " (count dirty)))
              (println (str "AHEAD + dirty              " (count ahead-dirty)))
              (println (str "ahead WHILE ON default br  " (count unpushed) "  <- the only 'unpushed commits' class"))
              (println (str "HEAD detached at pin       " (hs :detached-at-pin) "  (west の既定。異常ではない)"))
              (println (str "HEAD on default branch     " (hs :on-default)))
              (println (str "HEAD on a NAMED other br   " (hs :on-named-branch) "  <- 手で切り替えて戻していない"))
              (println (str "  of those, read by sth    " (count named-read)))
              (println (str "never fetched (no FETCH_HEAD) " (count never)))
              (println (str "last fetch > 90d ago       " (count old90)))
              (println (str "read by something          " (count consequential)))
              (println (str "  of those, behind         " (count (filter #(pos? (or (:behind %) 0)) consequential))))
              (let [pinned (filter :pin rows)]
                (println (str "\n---- the three states (west pin vs checkout vs last-fetched main) ----"))
                (println (str "registered in west.yml     " (count pinned)))
                (println (str "  pin == checkout          " (count (filter :pin=head pinned))))
                (println (str "  pin == last-fetched main " (count (filter :pin=remote pinned))))
                (println (str "  all three agree          "
                              (count (filter #(and (:pin=head %) (:pin=remote %)) pinned))))
                (println (str "  behind AND pin==checkout " (count (filter #(and (:pin=head %) (pos? (or (:behind %) 0))) pinned))
                              "  <- `west update` はこれを直さない(pin 自体が遅れている)")))

              (println)
              (println "==== ahead WHILE ON the default branch (真に未 push の commit) ====")
              (doseq [r (sort-by (comp - :ahead) unpushed)] (println (str "  " (fmt-row r))))
              (when (empty? unpushed) (println "  (none)"))

              (println)
              (println "==== AHEAD + dirty (branch を併記 —— ahead の大半は側枝への駐車) ====")
              (doseq [r (sort-by (comp - :ahead) ahead-dirty)] (println (str "  " (fmt-row r))))
              (when (empty? ahead-dirty) (println "  (none)"))

              (println)
              (println "==== HEAD on a NAMED non-default branch (full list) ====")
              (println "     (:local/root で解決する側は main ではないその木を掴む。")
              (println "      detached-at-pin は west の既定なのでここには出さない)")
              (doseq [r (sort-by (fn [r] [(- (count (or (:readers r) []))) (:label r)]) named)]
                (println (str "  " (fmt-row r))))
              (when (empty? named) (println "  (none)"))

              (println)
              (println "==== ahead (any), full list ====")
              (doseq [r (sort-by (comp - :ahead) (filter #(pos? (or (:ahead %) 0)) rows))]
                (println (str "  " (fmt-row r))))

              (println)
              (println "==== consequence-ranked: behind AND read by something ====")
              (doseq [r (take 30 (sort-by (fn [r] (- (* (count (:readers r)) (:behind r))))
                                          (filter #(pos? (or (:behind %) 0)) consequential)))]
                (println (str "  " (fmt-row r))))

              (println)
              (println "==== most behind overall (top 15) ====")
              (doseq [r (take 15 (sort-by (comp - :behind) behindish))]
                (println (str "  " (fmt-row r))))

              (when-let [out (not-empty (opt "--edn" nil))]
                (.writeFileSync fs out (pr-str (mapv #(dissoc % :checkout) rows)))
                (println (str "\n;; wrote " out)))

              (when (flag? "--write-consequence")
                (let [top (->> consequential
                               (sort-by (fn [r] [(- (count (:readers r)))
                                                 (- (or (:behind r) 0))]))
                               (mapv (fn [r] {:path (:label r)
                                              :readers (vec (sort (:readers r)))})))
                      f   (.join path root "manifest" "checkout-consequence.edn")]
                  (.writeFileSync
                    fs f
                    (str ";; GENERATED by scripts/checkout-staleness.cljs --write-consequence\n"
                         ";; 手編集しない。orgs/ 配下の checkout のうち、何かが実際に解決先として\n"
                         ";; 読んでいるもの。SessionStart hook はこの一覧だけを実測する\n"
                         ";; (4,415 全部を毎セッション測ると誰も読まない壁になる)。\n"
                         ";; :readers は :local/root edge と、superproject の script/hook/manifest/\n"
                         ";; LaunchAgent plist に orgs/<org>/<repo> と直接書かれた箇所。\n"
                         ";; **registry(30 件超を名指しするファイル)は reader に数えない** ——\n"
                         ";; 目録に載っていることは誰かが読んでいることを意味しない。\n"
                         ";; :summary は生成時点の分布。hook はこれを **日付つきで** 引用し、\n"
                         ";; consequence 側だけを毎回その場で測り直す。\n"
                         (pr-str {:generated-at (.toISOString (js/Date.))
                                  :count (count top)
                                  :registries-excluded (vec (sort-by :file (:registries hc)))
                                  :summary {:total total
                                            :current (n :current)
                                            :behind (n :behind)
                                            :ahead (n :ahead)
                                            :diverged (n :diverged)
                                            :shallow (count shallow)
                                            :dirty (count dirty)
                                            :ahead-and-dirty (count ahead-dirty)
                                            :ahead-on-default-branch (count unpushed)
                                            :head-detached-at-pin (hs :detached-at-pin)
                                            :head-on-default (hs :on-default)
                                            :head-on-named-branch (hs :on-named-branch)
                                            :head-on-named-branch-and-read (count named-read)
                                            :no-github-remote (n :no-github-remote)
                                            :no-remote-answer (n :no-remote-answer)
                                            :never-fetched (count never)
                                            :fetch-older-than-90d (count old90)
                                            :load1 (js/parseFloat (.toFixed (load1) 2))}
                                  :entries top})
                         "\n"))
                  (println (str ";; wrote " f " (" (count top) " entries)"))))

              (println (str "\n;; total " (- (js/Date.now) t0) "ms, load1=" (.toFixed (load1) 1)))))))))

(-main)
