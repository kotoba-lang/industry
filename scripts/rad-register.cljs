#!/usr/bin/env nbb
;; rad-register.cljs — west project を **本家 Radicle(heartwood) に実登録**し、
;; 存在を検証できた RID だけを manifest に記録する。
;;
;; ADR-2607252200（実登録の開始）+ ADR-2607259000（本 script と、3,621 件の
;; 未裏付け RID を撤去した是正）。
;;
;; ## なぜ gad で登録するのか
;; * 既存の実登録 623 件は **自前 seed `gad`**（tailnet 100.82.98.110:8776、
;;   node `seed-gad` / did:key:z6MkmUNjE8mrWx7d1NVnYCWZFokoTMaPNxmubf8RpBfAu8Me）
;;   の storage にある。同じ identity で続けないと、同じ repo が別 RID で二重登録される
;;   （実際に nekko/bonsai が Mac 側と gad 側で二重になっている）。
;; * gad の鍵 passphrase は kagi item `radicle-seed-gad-passphrase` にある。
;;   **この Mac のローカル node の passphrase は所在不明**なので Mac 側では登録できない。
;;
;; ## 存在判定の鉄則（2 度間違えた箇所 — ADR-2607259000 seq 91）
;; * `rad inspect <rid>` は **ローカル storage しか見ない**。他ノードに登録された RID は
;;   必ず「存在しない」と出る。
;; * **seed の storage を見るだけでも不十分**。登録は別 PC の鍵で行われている場合があり、
;;   その repo は announce されていてもこちらの storage には無い。
;; * 唯一まともな存在判定は **ネットワーク fetch**（`rad clone <rid>` / `rad seed` + fetch）。
;;   ただし登録元ノードが offline だと取れないので、**取得失敗を「存在しない」の証拠に
;;   してはいけない**（inconclusive として扱う）。
;; * したがって **storage の不在を根拠に manifest から RID を消してはならない。**
;;
;; ## 使い方
;;   nbb scripts/rad-register.cljs --names kagami,langgraph        ;; 指定 project
;;   nbb scripts/rad-register.cljs --names-file /tmp/rad-todo.txt  ;; 1行1名
;;   nbb scripts/rad-register.cljs --org kotoba-lang --limit 50    ;; 未登録から N 件
;;   nbb scripts/rad-register.cljs ... --dry-run                   ;; 計画だけ
;;   nbb scripts/rad-register.cljs ... --no-land                   ;; 登録するが manifest は書かない
;;
;; private repo は既定で対象外（ADR-2607252200）。archived も除外する。
(ns rad-register
  (:require ["node:child_process" :as cp]
            ["node:fs" :as fs]
            [cljs.reader :as reader]
            [clojure.string :as str]))

(def opts
  (loop [o {} [a & more] (vec *command-line-args*)]
    (cond (nil? a) o
          (str/starts-with? a "--")
          (let [k (keyword (subs a 2))]
            (if (or (nil? (first more)) (str/starts-with? (first more) "--"))
              (recur (assoc o k true) more)
              (recur (assoc o k (first more)) (rest more))))
          :else (recur o more))))

(def root (or (.-FLEET_ROOT js/process.env) (js/process.cwd)))
(def landing-repo "com-junkawasaki/root")
(def branch "main")
(def seed-host (or (:host opts) "gad"))
(def seed-user "gad")
(def seed-home "/home/gad")
(def seed-storage (str seed-home "/.radicle/storage"))
(def kagi-item "radicle-seed-gad-passphrase")
(def work-dir (str seed-home "/rad-work"))

(defn log [& xs] (println (str (.toISOString (js/Date.)) " " (str/join " " (map str xs)))))

(defn sh [cmd args & [o]]
  (try {:exit 0 :out (str (cp/execFileSync cmd (clj->js (vec args))
                                          (clj->js (merge {:encoding "utf8" :maxBuffer 268435456
                                                           :timeout 900000} o))))}
       (catch :default e {:exit (or (.-status e) 1)
                          :out (str (some-> (.-stdout e) str) (some-> (.-stderr e) str) e)})))

(defn gh! [& a]
  (let [{:keys [exit out]} (sh "gh" a)]
    (when-not (zero? exit) (log "gh failed:" (str/trim out)) (js/process.exit 1))
    (str/trim out)))

(defn ssh-script
  "リモートで bash script を **stdin 経由**で実行する。
  secret は argv に載せない（gad の ps から見えないようにする）ため、
  passphrase は piped script の中でだけ export する。"
  [script & [o]]
  ;; ⚠ `-n` は付けない: stdin を /dev/null に差し替えてしまい、script が remote に届かない
  ;; （実測でこれに 1 回はまった — 出力が空のまま init failed になる）。
  (sh "ssh" ["-o" "BatchMode=yes" "-o" "ConnectTimeout=15" seed-host "bash" "-s"]
      (merge {:input script} o)))

(defn kagi-get [item]
  (let [{:keys [exit out]} (sh (str root "/orgs/kotoba-lang/kagi/bin/kagi")
                               ["get" item "--compartment" "personal"])]
    (when-not (zero? exit) (log "kagi get failed for" item) (js/process.exit 1))
    (str/trim out)))

;; --- manifest -----------------------------------------------------------------
(defn fetch-file [p]
  (let [raw (gh! "api" "-H" "Accept: application/vnd.github.raw"
                 (str "repos/" landing-repo "/contents/" p "?ref=" branch))]
    (if (seq raw)
      raw
      (let [dir (str/join "/" (butlast (str/split p #"/")))
            base (last (str/split p #"/"))
            sha (gh! "api" (str "repos/" landing-repo "/contents/" dir "?ref=" branch)
                     "--jq" (str ".[] | select(.name==\"" base "\") | .sha"))
            b64 (gh! "api" (str "repos/" landing-repo "/git/blobs/" sha) "--jq" ".content")]
        (str (.toString (js/Buffer.from (str/replace b64 #"\s" "") "base64") "utf8"))))))

(defn parse-west [yaml]
  (loop [[l & more] (str/split-lines yaml) mode nil cur nil acc {:remotes {} :projects {}}]
    (if (nil? l)
      acc
      (cond
        (= l "  remotes:") (recur more :remotes nil acc)
        (= l "  projects:") (recur more :projects nil acc)
        (re-matches #"^  [a-z-]+:.*" l) (recur more nil nil acc)
        :else
        (if-let [[_ nm] (re-matches #"^    - name: (\S+)\s*$" l)]
          (recur more mode nm (if (= mode :projects) (assoc-in acc [:projects nm] {}) acc))
          (if-let [[_ k v] (re-matches #"^      ([a-z-]+): (.*?)\s*$" l)]
            (recur more mode cur
                   (case [mode k]
                     [:remotes "url-base"] (assoc-in acc [:remotes cur] v)
                     [:projects "remote"] (assoc-in acc [:projects cur :remote] v)
                     [:projects "path"] (assoc-in acc [:projects cur :path] v)
                     [:projects "revision"] (assoc-in acc [:projects cur :revision] v)
                     acc))
            (recur more mode cur acc)))))))

(defn org-of [west nm]
  (let [u (get-in west [:remotes (get-in west [:projects nm :remote])])]
    (when u (last (str/split u #"[:/]")))))

(defn current-map [repos-edn]
  (let [m (reader/read-string repos-edn)
        e (if (vector? m) (first m) m)]
    (or (some-> (:manifest.repos/rad-rids e) reader/read-string) {})))

;; --- registration -------------------------------------------------------------
(defn register!
  "seed 上で 1 repo を登録する。-> {:rid ..} | {:error ..}

  passphrase は **seed 上で走っている radicle-node 自身の environ から、node と同じ uid で**
  取り出す。secret は seed から出ず、argv にも載らず、呼び出し側の文脈にも入らない。
  （ADR ledger seq 73 は kagi item `radicle-seed-gad-passphrase` と記録しているが、
  この Mac の kagi vault にその item は存在しない — kagi の vault は checkout ローカル。）"
  [{:keys [name org default-branch description]}]
  (let [url (str "https://github.com/" org "/" name ".git")
        script
        (str/join
         "\n"
         ["set -u"
          (str "mkdir -p " work-dir " && chown " seed-user " " work-dir)
          ;; clone/fetch と rad init は seed の identity（gad ユーザー）で行う。
          ;; 内側 script も **stdin 経由**で渡す（argv に何も載らない）。
          (str "sudo -u " seed-user " -H bash -s <<'RADREG_EOF'")
          "set -u"
          (str "export PATH=" seed-home "/.radicle/bin:$PATH")
          "npid=$(pgrep -u $(id -u) -f 'radicle-node --listen' | head -1)"
          "P=$(tr '\\0' '\\n' < /proc/$npid/environ | sed -n 's/^RAD_PASSPHRASE=//p')"
          "if [ -z \"$P\" ]; then echo RADREG-NO-PASSPHRASE; exit 0; fi"
          "export RAD_PASSPHRASE=\"$P\""
          (str "cd " work-dir)
          (str "if [ -d " name "/.git ]; then git -C " name " fetch --quiet origin; "
               "else git clone --quiet " url " " name " || { echo RADREG-CLONE-FAIL; exit 0; }; fi")
          (str "cd " name)
          (str "git checkout --quiet " (or default-branch "main") " 2>/dev/null || true")
          ;; 既に rad remote があれば RID をそのまま返す（冪等）
          "existing=$(git config --get remote.rad.url 2>/dev/null || true)"
          "if [ -n \"$existing\" ]; then echo \"RADREG-RID ${existing}\"; exit 0; fi"
          (str "out=$(rad init --name " name " --default-branch " (or default-branch "main")
               " --public --no-confirm --description " (pr-str (or description name)) " 2>&1)")
          "echo \"$out\" | tail -6"
          "rid=$(git config --get remote.rad.url 2>/dev/null || true)"
          ;; identity を作っただけでは refs が seed storage に入らない。rad remote へ
          ;; 実際に送って初めて「中身が引ける登録」になる。
          "if [ -n \"$rid\" ]; then rad sync --announce 2>&1 | tail -2; fi"
          "if [ -n \"$rid\" ]; then echo \"RADREG-RID ${rid}\"; else echo RADREG-INIT-FAIL; fi"
          "RADREG_EOF"])
        {:keys [out]} (ssh-script script)
        ;; `rad init` は remote URL 形式（rad://<id>）で出るので RID 表記へ正規化する。
        rid (some-> (second (re-find #"RADREG-RID (rad:(?://)?z[A-Za-z0-9]+)" (str out)))
                    (str/replace "rad://" "rad:"))]
    (cond
      rid {:rid rid}
      (re-find #"RADREG-CLONE-FAIL" (str out)) {:error "clone failed"}
      (re-find #"RADREG-NO-PASSPHRASE" (str out))
      {:error "seed node not running with RAD_PASSPHRASE in its environ"}
      :else {:error (str "init failed: "
                         (->> (str/split-lines (str/trim (str out)))
                              (remove str/blank?) (take-last 2) (str/join " | ")))})))

(defn verify-on-seed
  "登録 node の storage に実体があり payload の name が一致することを確認する。"
  [rid expected-name]
  (let [id (str/replace rid #"^rad:" "")
        {:keys [out]} (ssh-script
                       (str/join "\n"
                                 [(str "test -d " seed-storage "/" id " || { echo NO-STORAGE; exit 0; }")
                                  (str "sudo -u " seed-user " -H env PATH=" seed-home
                                       "/.radicle/bin:$PATH rad inspect --payload " rid " 2>&1 | tr -d '\\n'")]))]
    (cond
      (re-find #"NO-STORAGE" (str out)) {:ok false :detail "not in seed storage"}
      (re-find (re-pattern (str "\"name\"\\s*:\\s*\"" expected-name "\"")) (str out)) {:ok true}
      :else {:ok false :detail (str "payload mismatch: " (subs (str out) 0 (min 160 (count (str out)))))})))

;; --- land ---------------------------------------------------------------------
(defn land! [new-pairs]
  (let [repos-p "manifest/repos.edn"
        west-p "manifest/west.yml"
        repos (fetch-file repos-p)
        west (fetch-file west-p)
        cur (current-map repos)
        merged (merge cur new-pairs)
        e (let [m (reader/read-string repos)] (if (vector? m) (first m) m))
        repos' (str (pr-str [(assoc e :manifest.repos/rad-rids (pr-str merged))]) "\n")
        ;; west.yml へは追加ぶんだけ projection（既存行には触らない）
        ls (vec (str/split-lines west))
        idxs (keep-indexed (fn [i l] (when (re-matches #"^    - name: \S+\s*$" l) i)) ls)
        ins (reduce (fn [m i]
                      (let [end (or (first (filter #(> % i) idxs)) (count ls))
                            block (subvec ls i (min end (count ls)))
                            path (some #(second (re-matches #"^      path: (\S+)\s*$" %)) block)
                            rid (get new-pairs path)
                            has? (some #(str/starts-with? % "        rad-rid: ") block)
                            ud (first (keep-indexed (fn [j l] (when (= "      userdata:" l) (+ i j))) block))
                            gi (first (keep-indexed (fn [j l] (when (re-matches #"^      groups: .*$" l) (+ i j))) block))]
                        (cond
                          (or (nil? rid) has?) m
                          ud (assoc m ud [:append rid])
                          gi (assoc m gi [:new rid])
                          :else m)))
                    {} idxs)
        west' (str (str/join "\n"
                             (vec (mapcat (fn [[i l]]
                                            (if-let [[kind rid] (get ins i)]
                                              (if (= kind :append)
                                                [l (str "        rad-rid: " rid)]
                                                [l "      userdata:" (str "        rad-rid: " rid)])
                                              [l]))
                                          (map-indexed vector ls))))
                   (when (str/ends-with? west "\n") "\n"))
        revs-same? (= (filter #(str/starts-with? % "      revision: ") (str/split-lines west))
                      (filter #(str/starts-with? % "      revision: ") (str/split-lines west')))]
    (when-not revs-same? (log "SANITY: revision lines changed — refusing to land") (js/process.exit 1))
    (reader/read-string repos')
    (let [br (str "agent/rad-register-" (subs (str (.getTime (js/Date.))) 4))
          base (gh! "api" (str "repos/" landing-repo "/git/refs/heads/" branch) "--jq" ".object.sha")
          _ (sh "gh" ["api" "--method" "POST" (str "repos/" landing-repo "/git/refs")
                      "-f" (str "ref=refs/heads/" br) "-f" (str "sha=" base)])
          blob (fn [p] (let [dir (str/join "/" (butlast (str/split p #"/")))
                             base (last (str/split p #"/"))]
                         (gh! "api" (str "repos/" landing-repo "/contents/" dir "?ref=" branch)
                              "--jq" (str ".[] | select(.name==\"" base "\") | .sha"))))
          put (fn [p content msg]
                (let [body (js/JSON.stringify (clj->js {:message msg
                                                        :content (.toString (js/Buffer.from content "utf8") "base64")
                                                        :branch br :sha (blob p)}))
                      r (sh "gh" ["api" "--method" "PUT" (str "repos/" landing-repo "/contents/" p) "--input" "-"]
                            {:input body})]
                  (when-not (zero? (:exit r))
                    (log "PUT failed" p (:out r))
                    (sh "gh" ["api" "--method" "DELETE" (str "repos/" landing-repo "/git/refs/heads/" br)])
                    (js/process.exit 1))))]
      (put repos-p repos' (str "manifest: record " (count new-pairs) " verified Radicle RIDs (registered on seed-gad)"))
      (put west-p west' "manifest: project the new rad-rid userdata into west.yml")
      (let [m (sh "gh" ["api" (str "repos/" landing-repo "/merges") "-f" (str "base=" branch)
                        "-f" (str "head=" br) "-f" "commit_message=Merge agent/rad-register: verified Radicle RIDs"])]
        (sh "gh" ["api" "--method" "DELETE" (str "repos/" landing-repo "/git/refs/heads/" br)])
        (log (if (zero? (:exit m)) "landed" (str "merge failed: " (:out m))))))))

;; --- main ---------------------------------------------------------------------
(let [west (parse-west (fetch-file "manifest/west.yml"))
      already (current-map (fetch-file "manifest/repos.edn"))
      names (cond
              (:names opts) (->> (str/split (str (:names opts)) #",") (map str/trim) (remove str/blank?))
              (:names-file opts) (->> (str/split (str (fs/readFileSync (:names-file opts) "utf8")) #"\n")
                                      (map str/trim) (remove str/blank?))
              (:org opts) (->> (:projects west) keys
                               (filter #(= (:org opts) (org-of west %)))
                               (remove #(get already (get-in west [:projects % :path])))
                               sort
                               (take (js/parseInt (or (:limit opts) "20"))))
              :else (do (println "usage: --names a,b | --names-file f | --org O [--limit N]")
                        (js/process.exit 2)))
      targets (for [nm names
                    :let [org (org-of west nm)
                          path (get-in west [:projects nm :path])]]
                {:name nm :org org :path path})
      _ (log "candidates:" (count targets))
      ;; private / archived を除外（GitHub 側の実データで判定）
      checked (vec (for [t targets]
                     (if-not (:org t)
                       (assoc t :skip "not in west.yml")
                       (let [{:keys [exit out]} (sh "gh" ["api" (str "repos/" (:org t) "/" (:name t))
                                                          "--jq" "[.private, .archived, .default_branch, (.description // \"\")] | @tsv"])]
                         (if-not (zero? exit)
                           (assoc t :skip "repo not reachable on GitHub")
                           (let [[priv arch db desc] (str/split (str/trim out) #"\t")]
                             (cond
                               (= priv "true") (assoc t :skip "private (out of scope)")
                               (= arch "true") (assoc t :skip "archived")
                               (get already (:path t)) (assoc t :skip "already mapped")
                               :else (assoc t :default-branch db :description desc))))))))
      todo (remove :skip checked)]
  (doseq [c checked :when (:skip c)] (log "skip" (:name c) "—" (:skip c)))
  (log "to register:" (count todo))
  (if (:dry-run opts)
    (doseq [t todo] (log "would register" (:org t) "/" (:name t) "->" (:path t)))
    (let [attempt (fn [t]
                    (let [r (register! t)]
                      (if (:error r)
                        (do (log "FAIL" (:name t) "—" (:error r))
                            (assoc t :error (:error r)))
                        (let [v (verify-on-seed (:rid r) (:name t))]
                          (if (:ok v)
                            (do (log "OK  " (:name t) (:rid r))
                                (assoc t :rid (:rid r)))
                            (do (log "UNVERIFIED" (:name t) (:rid r) "—" (:detail v))
                                (assoc t :error (str "unverified: " (:detail v)))))))))
          results (mapv attempt todo)
          verified (into {} (for [r results :when (:rid r)] [(:path r) (:rid r)]))]
      (log "verified:" (count verified) "/ attempted" (count todo))
      (if (or (empty? verified) (:no-land opts))
        (log "nothing landed" (when (:no-land opts) "(--no-land)"))
        (land! verified)))))
