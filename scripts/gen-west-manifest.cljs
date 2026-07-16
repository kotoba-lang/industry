#!/usr/bin/env nbb
;; gen-west-manifest.cljs
;; manifest/repos.edn(ポリシー)+ git の事実(working HEAD)から manifest/west.yml を生成。
;;
;;   ポリシー(remote/既定/group-filter/DataLad/B2)は manifest/repos.edn。
;;   project 一覧と pin は git から採る:
;;     - パス: 既存 west.yml の `path:` 群、無ければ superproject の gitlink(初回)。
;;     - revision: 各 project の working tree HEAD(pin 前進に自動追従)。
;;
;; DataLad dataset(:datalad)は west project にしつつ `datalad` グループへ隔離し
;; userdata に印を付ける(実体取得は `nbb manifest/west_annex.cljs annex-get`)。
;; Archived package(:archived)は `archived` グループへ隔離し既定 group-filter の
;; `-archived` で west update 対象外にする(ADR-2607102200 addendum 6)。
;;
;; 使い方:
;;   nbb scripts/gen-west-manifest.cljs                    ; west.yml を更新(pin 検証つき)
;;   nbb scripts/gen-west-manifest.cljs --check            ; 差分があれば exit 1(CI 用)
;;   nbb scripts/gen-west-manifest.cljs --entry <name>     ; 指定 entry だけ更新(最小 diff。繰り返し/カンマ区切り可)
;;   nbb scripts/gen-west-manifest.cljs --no-verify-remote ; pin のサーバ側検証をスキップ(緊急用)
;;
;; pin 検証(ADR-2607022900): 生成時、変更される pin を scripts/verify-west-pins.cljs で
;; サーバ側(GitHub API)検証する — 「上流に存在」「default branch から到達可能」
;; 「旧 pin から前進」。失敗したら west.yml を書かない。未 push のローカル HEAD を
;; pin 化する事故(wholesale 再生成による 44 pin 破損)と静かな pin 退行を防ぐ。
;; 登録/rename/pin 前進は --entry で当該 entry のみの最小 diff にすること
;; (wholesale 再生成 commit は禁止 — CLAUDE.md / repos.edn :manifest-workflow)。

;; clojure.java.shell/clojure.java.io are JVM-only and unavailable under nbb
;; (ClojureScript-on-Node) -- scripts.nbb-compat provides `sh`/`file` with the
;; same shape, aliased as `io` too so the existing `io/file` call-sites below
;; keep working unchanged.
(require '[scripts.nbb-compat :as io :refer [slurp spit file-seq format sh]]
         '[clojure.string :as str]
         '[clojure.edn :as edn])

(def root (-> (sh "git" "rev-parse" "--show-toplevel") :out str/trim))
(def manifest-dir (io/file root "manifest"))
(def out-file (io/file manifest-dir "west.yml"))

;; repos.edn は manifest/edn-datomize.cljs により [{:db/id -1 :manifest.repos/orgs ...}]
;; という Datomic/Datascript tx-data 形式に変換済み。元のトップレベル map(:orgs :b2 等)を
;; 復元する: 名前空間を剥がし、非scalar値は pr-str された blob 文字列なので
;; edn/read-string で元の入れ子構造に戻す。
(defn- unblob [v]
  (if (string? v)
    (try (let [parsed (edn/read-string v)] (if (coll? parsed) parsed v))
         (catch :default _ v))
    v))

(defn- reconstitute-entity [tx-data]
  (into {} (map (fn [[k v]] [(keyword (name k)) (unblob v)]))
        (dissoc (first tx-data) :db/id)))

(def cfg (reconstitute-entity (edn/read-string (slurp (io/file manifest-dir "repos.edn")))))
(def kotoba-workspace
  (let [f (io/file manifest-dir "kotoba-workspace.edn")]
    (when (.exists f) (edn/read-string (slurp f)))))

(defn paths-from-west-yml []
  (when (.exists out-file)
    (->> (str/split-lines (slurp out-file))
         (keep #(second (re-find #"^\s*path:\s*(\S+)" %))))))

(defn projects-from-west-yml []
  (when (.exists out-file)
    (let [entries (atom {})
          current (atom nil)]
      (doseq [line (str/split-lines (slurp out-file))]
        (when-let [name (second (re-find #"^\s*- name:\s*(\S+)" line))]
          (reset! current {:name name}))
        (when @current
          (when-let [revision (second (re-find #"^\s*revision:\s*(\S+)" line))]
            (swap! current assoc :revision revision))
          (when-let [path (second (re-find #"^\s*path:\s*(\S+)" line))]
            (swap! current assoc :path path))
          (when (re-find #"^\s*submodules:\s*true\s*$" line)
            (swap! current assoc :submodules true))
          (when-let [path (:path @current)]
            (swap! entries assoc path @current))))
      @entries)))

(defn paths-from-gitlinks []
  (->> (:out (sh "git" "ls-tree" "-r" "HEAD"))
       str/split-lines
       (keep (fn [l] (when (str/includes? l " commit ")
                       (-> (str/split l #"\t" 2) second))))))

(defn separate-working-tree? [path]
  (let [path-file (io/file root path)
        {:keys [exit out]} (sh "git" "-C" (str path-file) "rev-parse" "--show-toplevel")]
    (and (zero? exit)
         (not= (.getCanonicalPath (io/file root))
               (.getCanonicalPath (io/file (str/trim out)))))))

(defn working-head [path]
  (when (separate-working-tree? path)
    (let [{:keys [exit out]} (sh "git" "-C" (str (io/file root path)) "rev-parse" "HEAD")]
      (when (zero? exit) (str/trim out)))))

(defn org-of  [p] (second (str/split p #"/")))
(defn name-of [p] (last (str/split p #"/")))
(defn nested? [p] (.exists (io/file root p ".gitmodules")))
(defn remote-of [p] (get (:remote-overrides cfg) p (org-of p)))
(defn canonical-path [p] (get (:path-overrides cfg) p p))
(defn heavy? [p] (contains? (:heavy cfg) p))
(defn dup-basenames [paths]
  (->> paths (map name-of) frequencies (keep (fn [[n c]] (when (> c 1) n))) set))
(defn west-name [path dup-names]
  (let [n (name-of path)]
    (if (contains? dup-names n) (str (org-of path) "-" n) n)))

(def existing-projects (projects-from-west-yml))

(defn submodule-paths
  "project の .gitmodules から submodule path 一覧を返す(excl を除外)。
   west は `git submodule update --init --checkout` を使い `update = none` を
   コマンドラインで override するため、local-only submodule(baien/datasets 等)は
   `submodules: true` でなく明示 path リストから外すしかない(ADR-2607022900)。"
  [path excl]
  (let [gm (io/file root path ".gitmodules")]
    (when (.exists gm)
      (->> (str/split-lines (:out (sh "git" "config" "-f" (str gm)
                                      "--get-regexp" "\\.path$")))
           (keep #(second (re-find #"\s(\S+)$" %)))
           (remove (set excl))
           seq))))

(defn project-entry [path dup-names]
  (let [existing (get existing-projects path)
        sha     (or (working-head path) (:revision existing))
        dl      (get-in cfg [:datalad path])
        arch    (get-in cfg [:archived path])
        depth   (when (heavy? path) (get-in cfg [:defaults :clone-depth]))
        groups  (cond
                  dl   [(:group dl)]
                  arch [(:group arch "archived")]
                  :else [(org-of path)])
        recurse (or (contains? (:force-recurse-submodules cfg) path)
                    (nested? path)
                    (:submodules existing))
        excl    (get (:submodule-excludes cfg) path)
        subs    (when (and recurse (seq excl)) (submodule-paths path excl))
        wname   (west-name path dup-names)
        base    (name-of path)]
    (when sha
      (str "    - name: " wname "\n"
           "      remote: " (remote-of path) "\n"
           ;; dedup により name(=west-name) と実 repo 名(basename) が乖離する場合、
           ;; repo-path で実 repo 名を明示しないと west が url-base/name で
           ;; 存在しない repo を叩く(例: gftdcojp-cloud-murakumo)。
           (when (not= wname base) (str "      repo-path: " base "\n"))
           "      revision: " sha "\n"
           "      path: " path "\n"
           (when depth (str "      clone-depth: " depth "\n"))
           "      groups: [" (str/join ", " groups) "]\n"
           (cond
             subs    (str "      submodules:\n"
                          (apply str (for [p subs] (str "        - path: " p "\n"))))
             recurse "      submodules: true\n")
           (when dl (str "      userdata:\n"
                         "        datalad: true\n"
                         "        annex-remote: " (:annex-remote dl) "\n"))
           (when (and arch (not dl))
             (str "      userdata:\n"
                  "        archived: true\n"))))))

(defn render []
  (let [workspace-projects (->> (:manifest.kotoba-workspace/components kotoba-workspace)
                                vals
                                (mapcat #(if (sequential? %) % [%])))
        paths (->> (concat (or (seq (paths-from-west-yml)) (paths-from-gitlinks))
                           (:extra-projects cfg)   ; repos.edn の新規追加口を union
                           workspace-projects)    ; Kotoba product boundary の宣言も登録口
                   (filter #(str/starts-with? % "orgs/"))
                   (map canonical-path)
                   distinct sort)
        dups  (dup-basenames paths)]
    (str
     "# west.yml — generated by scripts/gen-west-manifest.cljs. DO NOT EDIT BY HAND.\n"
     "# source of truth: manifest/repos.edn  /  再生成: nbb scripts/gen-west-manifest.cljs\n"
     "manifest:\n  version: \"1.0\"\n\n  remotes:\n"
     (apply str (for [[n u] (sort (:remotes cfg))]
                  (str "    - name: " n "\n      url-base: " u "\n")))
     "\n  defaults:\n    revision: " (get-in cfg [:defaults :revision]) "\n\n"
     "  group-filter: [" (str/join ", " (:group-filter cfg)) "]\n\n"
     "  projects:\n"
     (apply str (keep #(project-entry % dups) paths))
     "\n  self:\n    path: manifest\n    west-commands: west-commands.yml\n")))

;; --- --entry splice: 既存 west.yml をベースに指定 entry の block だけ差し替える ---
(defn- split-blocks
  "content → {:prefix [lines] :entries [[name [lines]] ...] :suffix [lines]}
   projects セクションの `    - name: X` を block 境界とする。"
  [content]
  (let [lines (vec (str/split-lines content))
        n (count lines)
        proj-idx (first (keep-indexed (fn [i l] (when (re-find #"^  projects:\s*$" l) i)) lines))
        entry-start? (fn [l] (re-find #"^    - name:\s*\S+\s*$" l))
        first-entry (when proj-idx
                      (first (filter #(and (> % proj-idx) (entry-start? (lines %))) (range n))))]
    (if (nil? first-entry)
      {:prefix lines :entries [] :suffix []}
      (loop [i first-entry entries [] cur-name nil cur []]
        (if (= i n)
          {:prefix (subvec lines 0 first-entry)
           :entries (if cur-name (conj entries [cur-name cur]) entries)
           :suffix []}
          (let [l (lines i)]
            (cond
              (entry-start? l)
              (recur (inc i)
                     (if cur-name (conj entries [cur-name cur]) entries)
                     (second (re-find #"^    - name:\s*(\S+)" l))
                     [l])
              (or (str/blank? l) (re-find #"^  \S" l))
              {:prefix (subvec lines 0 first-entry)
               :entries (if cur-name (conj entries [cur-name cur]) entries)
               :suffix (subvec lines i)}
              :else (recur (inc i) entries cur-name (conj cur l)))))))))

(defn- block-path [ls] (some #(second (re-find #"^      path:\s*(\S+)" %)) ls))

(defn- splice
  "existing の entry 群のうち names のものだけ rendered の block に差し替える。
   existing に無い名前は path 順の位置へ挿入。それ以外の行は byte 不変(最小 diff)。"
  [existing-content rendered-content names]
  (let [ex (split-blocks existing-content)
        rmap (into {} (:entries (split-blocks rendered-content)))
        missing (remove rmap names)]
    (when (seq missing)
      (binding [*out* *err*]
        (println (str "--entry: 生成結果に entry がありません: " (str/join ", " missing)
                      " (repos.edn :extra-projects への登録と clone 済み working tree を確認)")))
      (scripts.nbb-compat/exit 1))
    (let [names-set (set names)
          ex-names  (set (map first (:entries ex)))
          replaced  (mapv (fn [[n ls]] [n (if (names-set n) (rmap n) ls)]) (:entries ex))
          with-new  (reduce (fn [entries n]
                              (let [blk (rmap n)
                                    p   (block-path blk)
                                    idx (or (first (keep-indexed
                                                     (fn [i [_ ls]]
                                                       (when (and p (pos? (compare (block-path ls) p))) i))
                                                     entries))
                                            (count entries))]
                                (vec (concat (subvec entries 0 idx) [[n blk]] (subvec entries idx)))))
                            replaced (remove ex-names names))]
      (str (str/join "\n" (concat (:prefix ex) (mapcat second with-new) (:suffix ex))) "\n"))))

;; --- pin のサーバ側検証(scripts/verify-west-pins.cljs に委譲) ---
(defn- nbb-bin
  "検証の子プロセスに使う nbb を解決する。裸の \"nbb\" は PATH に無い環境
  (repo-local install のみの checkout)で spawn が即失敗し、pin 検証が
  常時 FAIL 扱い → west.yml を一切書けなくなる実障害があったため、
  repo-local の node_modules/.bin/nbb を優先し、無ければ PATH に頼る。"
  []
  (let [local (io/file root "node_modules" ".bin" "nbb")]
    (if (.exists local) (str local) "nbb")))

(defn- verify-remote! [content entry-names]
  (let [self    (scripts.nbb-compat/get-property "babashka.file")
        vscript (io/file (scripts.nbb-compat/parent-path self) "verify-west-pins.cljs")]
    (if-not (.exists vscript)
      (binding [*out* *err*] (println "WARN: verify-west-pins.cljs が無いため pin 検証をスキップ"))
      ;; mkdtempSync でユニークな tmp dir を切る — Date.now() ms 分解能だけだと、この
      ;; リポの並行 agent 運用下では同一 ms 内の2回起動が同一パスへ衝突しうる。
      (let [node-fs  (js/require "node:fs")
            node-os  (js/require "node:os")
            node-path (js/require "node:path")
            tmp-dir  (.mkdtempSync node-fs (.join node-path (.tmpdir node-os) "west-candidate-"))
            tmp      (io/file tmp-dir "west.yml")]
        (try
          (spit tmp content)
          ;; --entry 指定時は検証もその entry に絞る(--only)。ローカル west.yml が
          ;; main と乖離した checkout では、絞らないと無関係 entry の大量 API 検証で
          ;; 数分単位の timeout になる(書き込むのは当該 entry だけなので検証もそこだけでよい)。
          (let [{:keys [exit out err]} (apply sh (nbb-bin) (str vscript) "--dir" root "--candidate" (str tmp)
                                              (mapcat (fn [n] ["--only" n]) entry-names))]
            (print out) (binding [*out* *err*] (print err)) (flush)
            (when (= 1 exit)
              (binding [*out* *err*]
                (println "pin 検証に失敗したため west.yml は書き込みません(緊急スキップ: --no-verify-remote)。"))
              (scripts.nbb-compat/exit 1))
            (when (contains? #{2 3} exit)
              (binding [*out* *err*] (println "WARN: pin 検証を実行できず fail-open で続行"))))
          (finally (.rmSync node-fs tmp-dir #js {:recursive true :force true})))))))

(let [args     *command-line-args*
      check?   (some #{"--check"} args)
      verify?  (not (some #{"--no-verify-remote"} args))
      entries  (->> (map vector args (rest args))
                    (keep (fn [[a b]] (when (= a "--entry") b)))
                    (mapcat #(str/split % #","))
                    (map str/trim) (remove str/blank?) vec)
      rendered (render)
      content  (if (and (seq entries) (.exists out-file))
                 (splice (slurp out-file) rendered entries)
                 rendered)]
  (if check?
    ;; --check は従来どおり full canonical と比較(entry 指定は無視)
    (if (= (when (.exists out-file) (slurp out-file)) rendered)
      (do (println "west.yml is up to date.") (scripts.nbb-compat/exit 0))
      (do (binding [*out* *err*] (println "west.yml is STALE. run: nbb scripts/gen-west-manifest.cljs"))
          (scripts.nbb-compat/exit 1)))
    (do (when verify? (verify-remote! content entries))
        (.mkdirs manifest-dir)
        (spit out-file content)
        (println (str "wrote " (.getPath out-file)
                      " (" (count (re-seq #"path: orgs/" content)) " projects"
                      (when (seq entries) (str ", --entry " (str/join "," entries))) ")")))))
