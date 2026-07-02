#!/usr/bin/env bb
;; gen-west-manifest.bb
;; manifest/repos.edn(ポリシー)+ git の事実(working HEAD)から manifest/west.yml を生成。
;;
;;   ポリシー(remote/既定/group-filter/DataLad/B2)は manifest/repos.edn。
;;   project 一覧と pin は git から採る:
;;     - パス: 既存 west.yml の `path:` 群、無ければ superproject の gitlink(初回)。
;;     - revision: 各 project の working tree HEAD(pin 前進に自動追従)。
;;
;; DataLad dataset(:datalad)は west project にしつつ `datalad` グループへ隔離し
;; userdata に印を付ける(実体取得は `west annex-get`)。
;;
;; 使い方:
;;   bb scripts/gen-west-manifest.bb                    ; west.yml を更新(pin 検証つき)
;;   bb scripts/gen-west-manifest.bb --check            ; 差分があれば exit 1(CI 用)
;;   bb scripts/gen-west-manifest.bb --entry <name>     ; 指定 entry だけ更新(最小 diff。繰り返し/カンマ区切り可)
;;   bb scripts/gen-west-manifest.bb --no-verify-remote ; pin のサーバ側検証をスキップ(緊急用)
;;
;; pin 検証(ADR-2607022900): 生成時、変更される pin を scripts/verify-west-pins.bb で
;; サーバ側(GitHub API)検証する — 「上流に存在」「default branch から到達可能」
;; 「旧 pin から前進」。失敗したら west.yml を書かない。未 push のローカル HEAD を
;; pin 化する事故(wholesale 再生成による 44 pin 破損)と静かな pin 退行を防ぐ。
;; 登録/rename/pin 前進は --entry で当該 entry のみの最小 diff にすること
;; (wholesale 再生成 commit は禁止 — CLAUDE.md / repos.edn :manifest-workflow)。

(require '[clojure.string :as str]
         '[clojure.edn :as edn]
         '[clojure.java.shell :refer [sh]]
         '[clojure.java.io :as io])

(def root (-> (sh "git" "rev-parse" "--show-toplevel") :out str/trim))
(def manifest-dir (io/file root "manifest"))
(def out-file (io/file manifest-dir "west.yml"))
(def cfg (edn/read-string (slurp (io/file manifest-dir "repos.edn"))))

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

(defn project-entry [path dup-names]
  (let [existing (get existing-projects path)
        sha     (or (working-head path) (:revision existing))
        dl      (get-in cfg [:datalad path])
        depth   (when (heavy? path) (get-in cfg [:defaults :clone-depth]))
        groups  (if dl [(:group dl)] [(org-of path)])
        recurse (or (contains? (:force-recurse-submodules cfg) path)
                    (nested? path)
                    (:submodules existing))
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
           (when recurse "      submodules: true\n")
           (when dl (str "      userdata:\n"
                         "        datalad: true\n"
                         "        annex-remote: " (:annex-remote dl) "\n"))))))

(defn render []
  (let [paths (->> (concat (or (seq (paths-from-west-yml)) (paths-from-gitlinks))
                           (:extra-projects cfg))   ; repos.edn の新規追加口を union
                   (filter #(str/starts-with? % "orgs/"))
                   (map canonical-path)
                   distinct sort)
        dups  (dup-basenames paths)]
    (str
     "# west.yml — generated by scripts/gen-west-manifest.bb. DO NOT EDIT BY HAND.\n"
     "# source of truth: manifest/repos.edn  /  再生成: bb scripts/gen-west-manifest.bb\n"
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
      (System/exit 1))
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

;; --- pin のサーバ側検証(scripts/verify-west-pins.bb に委譲) ---
(defn- verify-remote! [content]
  (let [self    (System/getProperty "babashka.file")
        vscript (io/file (.getParentFile (io/file self)) "verify-west-pins.bb")]
    (if-not (.exists vscript)
      (binding [*out* *err*] (println "WARN: verify-west-pins.bb が無いため pin 検証をスキップ"))
      (let [tmp (java.io.File/createTempFile "west-candidate" ".yml")]
        (try
          (spit tmp content)
          (let [{:keys [exit out err]} (sh "bb" (str vscript) "--dir" root "--candidate" (str tmp))]
            (print out) (binding [*out* *err*] (print err)) (flush)
            (when (= 1 exit)
              (binding [*out* *err*]
                (println "pin 検証に失敗したため west.yml は書き込みません(緊急スキップ: --no-verify-remote)。"))
              (System/exit 1))
            (when (contains? #{2 3} exit)
              (binding [*out* *err*] (println "WARN: pin 検証を実行できず fail-open で続行"))))
          (finally (.delete tmp)))))))

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
      (do (println "west.yml is up to date.") (System/exit 0))
      (do (binding [*out* *err*] (println "west.yml is STALE. run: bb scripts/gen-west-manifest.bb"))
          (System/exit 1)))
    (do (when verify? (verify-remote! content))
        (.mkdirs manifest-dir)
        (spit out-file content)
        (println (str "wrote " (.getPath out-file)
                      " (" (count (re-seq #"path: orgs/" content)) " projects"
                      (when (seq entries) (str ", --entry " (str/join "," entries))) ")")))))
