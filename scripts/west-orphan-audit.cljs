#!/usr/bin/env nbb
;; west-orphan-audit.cljs — local orgs/*/* vs west.yml / :local/root 依存の orphan 検出。
;;
;; 背景 (2026-07-17 実測):
;;   - kotoba-lang/crm は GitHub に push 済みなのに west 未登録・local 未 checkout。
;;     cloud-itonami-isic-5820/6201/6202 の deps.edn が
;;     {:local/root "../../kotoba-lang/crm"} を指すため fresh checkout で壊れる。
;;   - orgs/ 直下には path-override 残骸・worktree・personal データ・真の未登録 git
;;     が混在する。「local にある = west に無い」をそのまま登録対象にすると誤爆する。
;;
;; 分類:
;;   :path-override-leftover  — repos.edn :path-overrides の旧 path（新 path が west にある）
;;   :personal                — orgs/personal/*（west 対象外）
;;   :worktree-scratch        — _* / *-current / *-boundary / _wt-* 等
;;   :worktree-of-registered  — .git がファイル（git worktree）で、gitdir: の指す base
;;                              repo が west 登録済み。誤検出防止（実測 2026-07-22:
;;                              272 true-orphan-git 中 249 件がこれだった — network-isekai-*
;;                              / render-* 等の feature worktree を orgs/ 直下に作る運用）。
;;   :tracked-superproject    — superproject 自身が tracked content として持っている
;;                              （west project ではない）。**sparse-checkout でディスク上に
;;                              materialize されていないと、中身が空/ゴミだけに見える** ので
;;                              orphan と誤認されやすい。実測 2026-07-25: cleanup 中に
;;                              orgs/com-junkawasaki/2604-linde が :true-orphan-nongit と
;;                              分類され、ディスク上は .DS_Store 1個しか無いため「削除可能な
;;                              残骸」と判断されかけたが、実際は Lean 証明 11 ファイルを
;;                              tracked で持っていた。破壊的判断の前段でこれを分離する。
;;   :true-orphan-git         — local git があり west path に無い（登録 or 退役候補）
;;   :true-orphan-nongit      — local dir のみ（scaffold 残骸等）。superproject にも
;;                              tracked されていないので、消せばどこにも残らない。
;;   :local-root-broken       — deps.edn の :local/root が指す project が
;;                              未存在 or west 未登録（fresh checkout 破壊）
;;
;; 使い方:
;;   nbb scripts/west-orphan-audit.cljs              ; 人間可読サマリ
;;   nbb scripts/west-orphan-audit.cljs --all        ; 全件列挙
;;   nbb scripts/west-orphan-audit.cljs --blocking   ; :local-root-broken のみ
;;   nbb scripts/west-orphan-audit.cljs --edn        ; 機械可読 EDN
;;
;; exit 0: blocking なし
;; exit 1: :local-root-broken が1件以上（登録漏れが consumer を壊している）

(require '[scripts.nbb-compat :refer [slurp sh]]
         '[clojure.string :as str]
         '[clojure.edn :as edn])

(def node-fs (js/require "node:fs"))
(def node-path (js/require "node:path"))

(def root
  (-> (sh "git" "rev-parse" "--show-toplevel") :out str/trim))

(defn exists? [p]
  (try (.existsSync node-fs p) (catch :default _ false)))

(defn is-dir? [p]
  (try (.isDirectory (.statSync node-fs p)) (catch :default _ false)))

(defn is-file? [p]
  (try (.isFile (.statSync node-fs p)) (catch :default _ false)))

(defn list-dirs [p]
  (when (is-dir? p)
    (->> (.readdirSync node-fs p)
         (map str)
         (remove #(str/starts-with? % "."))
         (map #(node-path.join p %))
         (filter is-dir?)
         sort
         vec)))

(defn read-text [p]
  (when (exists? p)
    (try (slurp p) (catch :default _ nil))))

(defn west-paths
  "west.yml の path: 行を set で返す。"
  []
  (let [text (or (read-text (node-path.join root "manifest/west.yml")) "")]
    (->> (re-seq #"(?m)^\s+path:\s+(\S+)" text)
         (map second)
         set)))

(defn west-rev->path
  "west.yml の revision → path map。

  ある local repo の HEAD がここに載っていれば、その中身は **別の path で既に
  west 登録済み**であり、orphan ではなく旧 path の残骸である。repos.edn の
  :path-overrides に載っていないリネーム（org 移動・改名）はこの表でしか捕まらない。

  実測 2026-07-30: true-orphan-git 270 件のうち **257 件**がこれだった。
  etzhayyim/root の multirepo 抽出（2026-07-19/20）で、successor を push・登録した
  あと旧 path の checkout が残ったもので、HEAD が pin と完全一致する
  （com-etzhayyim-app-arbitrage の HEAD 412fa8ae = orgs/cloud-itonami/arbitrage の
  pin、app-aidesk → cloud-itonami/aidesk、app-aima → kotoba-lang/aima …）。
  この判定を入れる前は 270 件が「登録 or 退役」として報告され、本当に未登録だった
  6 件が埋もれていた。"
  []
  (let [text (or (read-text (node-path.join root "manifest/west.yml")) "")]
    (into {} (for [[_ rev p] (re-seq #"(?m)^\s+revision:\s+(\S+)\n\s+path:\s+(\S+)" text)]
               [rev p]))))

(defn git-head [rel]
  (let [{:keys [exit out]} (sh "git" "-C" (node-path.join root rel) "rev-parse" "HEAD")]
    (when (zero? exit) (not-empty (str/trim out)))))

(defn parse-string-map-blob
  "repos.edn 内の string 化された map リテラルから \"k\" \"v\" ペアを抜く。"
  [blob]
  (when blob
    (into {}
          (for [[_ k v] (re-seq #"\"([^\"]+)\"\s+\"([^\"]+)\"" blob)]
            [k v]))))

(defn path-overrides
  "repos.edn :path-overrides の old→new map。"
  []
  (let [text (or (read-text (node-path.join root "manifest/repos.edn")) "")
        m (re-find #":manifest\.repos/path-overrides\s+\"(.*?)\",\s*:manifest"
                   text)]
    (if m
      (parse-string-map-blob (str/replace (second m) #"\\\"" "\""))
      {})))

(defn local-org-projects
  "orgs/<org>/<repo> の相対 path 一覧。"
  []
  (let [orgs-root (node-path.join root "orgs")]
    (vec
     (for [org (list-dirs orgs-root)
           repo (list-dirs org)
           :let [rel (str "orgs/" (node-path.basename org) "/" (node-path.basename repo))]]
       rel))))

(defn git? [rel]
  (let [g (node-path.join root rel ".git")]
    (or (exists? g) (is-file? g))))

(defn worktree-base-rel
  "rel が git worktree（.git がファイルで `gitdir: .../<base>/.git/worktrees/<name>`
   を指す）なら、その base repo の orgs/ 相対 path を返す。worktree でなければ nil。
   249/272 件の true-orphan-git 誤検出（gpu-character-detail 等）の原因だった —
   base repo が west 登録済みなら本当の orphan ではない。"
  [rel]
  (let [g (node-path.join root rel ".git")]
    (when (is-file? g)
      (when-let [content (read-text g)]
        (when-let [m (re-find #"gitdir:\s*(.*)" (str/trim content))]
          (when-let [b (re-find #"(.*)/\.git/worktrees/" (second m))]
            (try
              (let [abs-base (second b)
                    rel-base (node-path.relative root abs-base)]
                (when-not (str/starts-with? rel-base "..")
                  rel-base))
              (catch :default _ nil))))))))

(defn git-remote [rel]
  (let [{:keys [exit out]} (sh "git" "-C" (node-path.join root rel)
                               "remote" "get-url" "origin")]
    (when (zero? exit) (not-empty (str/trim out)))))

(defn canonical-slug
  "GitHub の改名リダイレクトを辿った owner/name。引けなければ nil。

  `git remote get-url` が返すのは **改名される前の名前**でありうる（GitHub は
  リダイレクトするので fetch は成功し続け、ローカルは古い名前のままになる）。
  full_name は改名後の実体を返すので、これが唯一の権威ある信号。"
  [origin-url]
  (when-let [slug (some-> (re-find #"github\.com[:/]([^/]+/[^/]+?)(?:\.git)?$" origin-url) second)]
    (let [{:keys [exit out]} (sh "gh" "api" (str "repos/" slug) "--jq" ".full_name")]
      (when (zero? exit) (not-empty (str/trim out))))))

(defn reclassify-renamed
  "`:true-orphan-git` のうち、remote が **west に登録済みの repo へリダイレクトする**
  ものを `:renamed-upstream` へ移す。

  なぜ必要か（実測 2026-08-14）: `:registered-elsewhere` は HEAD が後継の pin と
  完全一致することを要求するので、旧 path の checkout が少しでも drift すると
  捕まらない。その結果 true-orphan-git 40 件が**全て**改名残骸だったのに
  「register or retire」として報告されていた。これは危険な誤報である —— 読んだ
  agent が旧 path を登録し直す（規約違反）か、退役させる（実害）ことになる。
  実際 40 件のうち 3 件（com-etzhayyim-{rasen,inochi,masago}）は observatory
  レジストリが名指しする**稼働中の checkout** で、rasen の 38KB のゲノム台帳は
  後継 path に存在しない（twin=absent）。

  判定は 1 候補 1 往復。true-orphan-git は小さい集合なので許容できる。
  引けなかったものは true-orphan-git に**残す**（『確かめられなかった』を
  『改名ではない』に潰さない。ADR-2608136000）。"
  [acc west]
  (let [{:keys [renamed orphan]}
        (reduce (fn [m {:keys [path origin] :as row}]
                  (let [canon (when (seq origin) (canonical-slug origin))
                        canon-path (when canon (str "orgs/" canon))]
                    (if (and canon-path (contains? west canon-path))
                      (update m :renamed conj (assoc row :redirects-to canon
                                                     :registered-at canon-path))
                      (update m :orphan conj row))))
                {:renamed [] :orphan []}
                (:true-orphan-git acc))]
    (assoc acc :true-orphan-git orphan :renamed-upstream renamed)))

(defn tracked-in-superproject?
  "superproject の index に <rel> 配下の tracked file があるか。

  sparse-checkout 下ではディスク上の見た目と index が乖離するため、ファイル走査では
  判定できない（materialize されていない tracked file は find に映らない）。
  `git ls-files` は index を見るので sparse 状態に影響されない。"
  [rel]
  (let [{:keys [exit out]} (sh "git" "-C" root "ls-files" "--" rel)]
    (and (zero? exit) (not (str/blank? out)))))

(defn tracked-file-count [rel]
  (let [{:keys [exit out]} (sh "git" "-C" root "ls-files" "--" rel)]
    (if (zero? exit)
      (count (remove str/blank? (str/split-lines out)))
      0)))

(defn worktree-scratch?
  [rel]
  (let [name (node-path.basename rel)]
    (or (str/starts-with? name "_")
        (str/starts-with? name "wt-")
        (str/includes? name "worktree")
        (str/ends-with? name "-current")
        (str/ends-with? name "-boundary")
        (boolean (re-matches #".*-pr\d+" name))
        (= name "__pycache__"))))

(defn classify-unregistered
  "local にあって west path に無いものを分類。"
  [local west overrides rev->path]
  (let [unreg (filter #(not (contains? west %)) local)]
    (reduce
     (fn [acc rel]
       (cond
         (contains? overrides rel)
         (update acc :path-override-leftover conj
                 {:path rel :maps-to (get overrides rel)
                  :target-in-west? (contains? west (get overrides rel))})

         (str/starts-with? rel "orgs/personal/")
         (update acc :personal conj {:path rel})

         (worktree-scratch? rel)
         (update acc :worktree-scratch conj
                 {:path rel :git? (git? rel)
                  :origin (when (git? rel) (git-remote rel))})

         (when-let [base (worktree-base-rel rel)] (contains? west base))
         (update acc :worktree-of-registered conj
                 {:path rel :base (worktree-base-rel rel)})

         ;; west project ではなく superproject 自身の tracked content。
         ;; git? / nongit の判定より先に分離する — sparse-checkout でディスク上が
         ;; 空に見えても index には中身があり、orphan として消してはならない。
         (tracked-in-superproject? rel)
         (update acc :tracked-superproject conj
                 {:path rel :tracked (tracked-file-count rel)})

         ;; HEAD が別 path の pin と一致 = 登録済み repo の旧 path 残骸。
         ;; :path-overrides に無いリネームはこの表でしか捕まらない（west-rev->path 参照）。
         (and (git? rel) (some-> (git-head rel) rev->path))
         (update acc :registered-elsewhere conj
                 {:path rel :head (git-head rel)
                  :registered-at (rev->path (git-head rel))})

         (git? rel)
         (update acc :true-orphan-git conj
                 {:path rel :origin (or (git-remote rel) "")})

         :else
         (update acc :true-orphan-nongit conj {:path rel})))
     {:registered-elsewhere []
      :path-override-leftover []
      :personal []
      :worktree-scratch []
      :worktree-of-registered []
      :tracked-superproject []
      :true-orphan-git []
      :true-orphan-nongit []}
     unreg)))

(defn project-of-abs
  "絶対 path を orgs/org/repo に畳む。失敗時は relative string。
  deps.edn の ../../../ が workspace 外へ抜ける場合は path 末尾の
  <org>/<repo> を orgs/ 付きで推定する。"
  [abs]
  (try
    (let [rel (node-path.relative root abs)
          parts (vec (str/split rel #"[\\/]"))]
      (cond
        (and (>= (count parts) 3) (= (first parts) "orgs"))
        (str/join "/" (take 3 parts))

        ;; abs 自体が .../orgs/org/repo を含む
        :else
        (let [abs-parts (vec (str/split abs #"[\\/]"))
              idx (.indexOf abs-parts "orgs")]
          (if (and (>= idx 0) (>= (count abs-parts) (+ idx 3)))
            (str/join "/" (subvec abs-parts idx (+ idx 3)))
            rel))))
    (catch :default _
      abs)))

(defn strip-edn-comments
  "行コメント（`;` 以降）を落とす。文字列リテラルの中の `;` は残す。

  なぜ要るか（2026-08-13 実測）: :local/root の走査は生テキストに正規表現を当てて
  いたので、**コメントに書かれたパスを本物の依存として数えていた**。壊れた
  `:local/root` を git dep に直し、その経緯を `;; これは以前 {:local/root \"../x\"}
  だった` と説明として書き添えたところ、直したはずの blocking edge が消えず、
  同じ 1 件を報告し続けた —— 修正の説明そのものが警報を再点火していた。
  ドキュメントを書くと検査が赤くなる仕掛けは、ドキュメントを書かせない方向に効く。"
  [text]
  (->> (str/split-lines text)
       (map (fn [line]
              (loop [i 0 in-str? false]
                (cond
                  (>= i (count line)) line
                  :else
                  (let [c (nth line i)]
                    (cond
                      (and in-str? (= c \\)) (recur (+ i 2) true)
                      (= c \")               (recur (inc i) (not in-str?))
                      (and (not in-str?) (= c \;)) (subs line 0 i)
                      :else                  (recur (inc i) in-str?)))))))
       (str/join "\n")))

(defn scan-local-root-deps
  "orgs/*/*/deps.edn の :local/root を走査し、未存在 or west 未登録を列挙。

  ⚠ 走査するのは **project 直下の deps.edn だけ**。monorepo 的に
  `orgs/<org>/<repo>/<package>/deps.edn` を持つ repo（例 net-kotobase/control-plane）の
  入れ子 deps.edn は対象外なので、そこの :local/root は検出されない。"
  [west]
  (let [orgs-root (node-path.join root "orgs")
        hits (atom [])]
    (doseq [org (list-dirs orgs-root)
            repo (list-dirs org)
            :let [deps (node-path.join repo "deps.edn")]
            :when (exists? deps)
            ;; コメントを落としてから走査する。落とさないと、修正の経緯を書いた
            ;; コメントが依存として数えられる（strip-edn-comments の docstring 参照）。
            :let [text (strip-edn-comments (or (read-text deps) ""))]
            :when (str/includes? text ":local/root")]
      (doseq [[_ target-rel] (re-seq #":local/root\s+\"([^\"]+)\"" text)]
        (let [abs (node-path.resolve repo target-rel)
              proj (project-of-abs abs)
              dir-exists? (exists? abs)
              in-west? (contains? west proj)]
          (when (or (not dir-exists?) (not in-west?))
            (swap! hits conj
                   {:consumer (str "orgs/" (node-path.basename org) "/"
                                   (node-path.basename repo) "/deps.edn")
                    :local-root target-rel
                    :project proj
                    :dir-exists? dir-exists?
                    :in-west? in-west?})))))
    ;; unique by project, keep first consumer as example
    (->> @hits
         (group-by :project)
         (map (fn [[proj rows]]
                (assoc (first rows)
                       :consumers (vec (distinct (map :consumer rows)))
                       :consumer-count (count (distinct (map :consumer rows))))))
         (sort-by :project)
         vec)))

(defn parse-args [argv]
  (let [s (set argv)]
    {:all? (contains? s "--all")
     :blocking? (contains? s "--blocking")
     :edn? (contains? s "--edn")}))

(defn print-human [report opts]
  (let [{:keys [unregistered local-root-broken counts]} report]
    (println "=== west-orphan-audit ===")
    (println (str "local orgs projects: " (:local counts)
                  "  west paths: " (:west counts)
                  "  unregistered total: " (:unregistered counts)))
    (println (str "  path-override leftovers: " (count (:path-override-leftover unregistered))))
    (println (str "  personal/*: " (count (:personal unregistered))))
    (println (str "  worktree/scratch: " (count (:worktree-scratch unregistered))))
    (println (str "  worktree-of-registered: " (count (:worktree-of-registered unregistered))))
    (println (str "  tracked-superproject: " (count (:tracked-superproject unregistered))
                  "  (NOT orphans — 消さないこと)"))
    (println (str "  registered-elsewhere (HEAD == another path's pin): "
                  (count (:registered-elsewhere unregistered))
                  "  (旧 path 残骸。orphan ではない)"))
    (println (str "  renamed-upstream: " (count (:renamed-upstream unregistered))
                  "  (remote が west 登録済み repo へリダイレクト = 改名残骸。**登録し直さない**)"))
    (println (str "  true-orphan-git: " (count (:true-orphan-git unregistered))))
    (println (str "  true-orphan-nongit: " (count (:true-orphan-nongit unregistered))))
    (println (str "  local-root-broken (blocking): " (count local-root-broken)))
    (when (seq local-root-broken)
      (println)
      (println "## BLOCKING: :local/root → missing or not-in-west")
      (doseq [row local-root-broken]
        (println (str "  " (:project row)
                      "  dir=" (:dir-exists? row)
                      " west=" (:in-west? row)
                      " consumers=" (:consumer-count row)))
        (when (:all? opts)
          (doseq [c (:consumers row)]
            (println (str "    - " c))))))
    (when (and (not (:blocking? opts)) (or (:all? opts) true))
      (println)
      (println "## true-orphan-git (register or retire)")
      (println "   ⚠ origin=(none) の行は **改名残骸かどうか判定できていない**（リダイレクトを引く")
      (println "     remote が無い）。『true orphan』は『後継を探して見つからなかった』ではなく")
      (println "     『探せなかった』の意味なので、登録も退役もする前に west.yml を名前で引くこと。")
      (println "     実測 2026-08-14: これらの一部は observatory レジストリが名指しする稼働中の")
      (println "     checkout で、その観測台帳は後継 path に存在しない（消すと復元できない）。")
      (let [rows (:true-orphan-git unregistered)
            show (if (:all? opts) rows (take 25 rows))]
        (doseq [row show]
          (println (str "  " (:path row)
                        (if (str/blank? (:origin row))
                          "  origin=(none)"
                          (str "  origin=" (:origin row))))))
        (when (and (not (:all? opts)) (> (count rows) 25))
          (println (str "  ... +" (- (count rows) 25)
                        " more (pass --all)")))))
    (when (:all? opts)
      (println)
      (println "## path-override leftovers (do NOT re-register old path)")
      (doseq [row (:path-override-leftover unregistered)]
        (println (str "  " (:path row) " → " (:maps-to row)
                      " (new-in-west=" (:target-in-west? row) ")")))
      (println)
      (println "## tracked-superproject (west project ではなく superproject の tracked content)")
      (println "## sparse-checkout でディスク上が空に見えても index には中身がある。削除禁止。")
      (doseq [row (:tracked-superproject unregistered)]
        (println (str "  " (:path row) "  tracked-files=" (:tracked row))))
      (println)
      (println "## worktree/scratch")
      (doseq [row (:worktree-scratch unregistered)]
        (println (str "  " (:path row) " git=" (:git? row))))
      (println)
      (println "## worktree-of-registered (git worktree of an already-registered repo; not a real orphan)")
      (doseq [row (:worktree-of-registered unregistered)]
        (println (str "  " (:path row) " -> " (:base row))))
      (println)
      (println "## true-orphan-nongit")
      (doseq [row (:true-orphan-nongit unregistered)]
        (println (str "  " (:path row)))))))

(defn -main [& args]
  (let [opts (parse-args (or args #js []))
        west (west-paths)
        overrides (path-overrides)
        local (local-org-projects)
        unreg (-> (classify-unregistered local west overrides (west-rev->path))
                  (reclassify-renamed west))
        broken (scan-local-root-deps west)
        report {:counts {:local (count local)
                         :west (count west)
                         :unregistered (+ (count (:registered-elsewhere unreg))
                                          (count (:path-override-leftover unreg))
                                          (count (:personal unreg))
                                          (count (:worktree-scratch unreg))
                                          (count (:worktree-of-registered unreg))
                                          (count (:true-orphan-git unreg))
                                          (count (:true-orphan-nongit unreg)))}
                :unregistered unreg
                :local-root-broken broken
                :blocking-count (count broken)}]
    (if (:edn? opts)
      (println (pr-str report))
      (print-human report opts))
    ;; process.exit を先に呼ばないと nbb が常に 0 で落ちることがある
    (.exit js/process (if (pos? (count broken)) 1 0))))

(apply -main *command-line-args*)
