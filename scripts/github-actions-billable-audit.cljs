#!/usr/bin/env nbb
;; github-actions-billable-audit.cljs — 「GitHub Actions を使っていない」を
;; **GitHub 側に訊いて**確かめる読み取り専用の計器。
;;
;; 正本の方針は ADR-2607300900 と CLAUDE.md「CI/CD は murakumo fleet」。
;; ここが答えるのは方針ではなく現在地の 3 問で、**3 問は別々の問い**である:
;;
;;   1. Actions は有効か           GET /repos/{r}/actions/permissions
;;   2. 何が登録されているか        GET /repos/{r}/actions/workflows
;;   3. 何が走り、いくら課金されたか GET /repos/{r}/actions/runs + .../{id}/timing
;;
;; ## なぜ既存の 2 本で足りないのか
;;
;; `scripts/github-actions-disable-sweep.cljs` は無効化する道具で、
;; `scripts/fleet-ci/github-workflow-audit.cljs` は workflow ファイルが fleet で
;; 走るかを測る道具。どちらも **「走ったか」「いくらか」を答えない。**
;;
;; そして「workflow が 0 本」は「Actions が無効」ではない。実測 2026-08-22、
;; `com-junkawasaki/root` は `.github/` を 1 ファイルも持たないのに、
;; registered workflow を 3 本持っていた（Dependabot の dynamic 2 本 +
;; 削除済みファイルの stale entry 1 本）。ファイルの不在から設定を推測すると、
;; **測れなかった検査が、測って問題が無かった検査と同じ値を返す。**
;;
;; ## 課金は推測しない
;;
;; 「走った」と「課金された」は別。同じ実測で、その Dependabot run は
;; 30〜45 分回っていたが `/timing` の `billable.*.total_ms` は **0** だった。
;; 秒数から金額を推測していたら、無害なものを止めに行っていた。
;; **金額を言うときは `/timing` を引く。引いていないなら「未測定」と書く。**
;;
;; ## 対象は明示する（全アカウントを既定にしない）
;;
;;   nbb scripts/github-actions-billable-audit.cljs --repo com-junkawasaki/root
;;   nbb scripts/github-actions-billable-audit.cljs --owner kotoba-lang --since 2026-07-30
;;   ... --max-runs 400        # 既定 200。打ち切ったら必ず出力に出す
;;
;; 引数なしで 4,000 repo を歩かない（CLAUDE.md の `west update` と同じ理由）。
;;
;; ## exit code は三値
;;
;;   0  全部答えられて、有効な Actions も課金も無かった
;;   1  Actions が有効な repo がある / 課金された分がある
;;   2  **答えられなかった**（token 不足・proxy 拒否・API エラー）。0 と混ぜない。
(ns github-actions-billable-audit
  (:require ["node:child_process" :as cp]
            [clojure.string :as str]))

(def args (vec *command-line-args*))
(defn- opts [f] (keep-indexed (fn [i a] (when (= a f) (nth args (inc i) nil))) args))
(defn- opt [f d] (or (first (opts f)) d))

(def since (opt "--since" "2026-07-30"))
(def max-runs (js/parseInt (str (opt "--max-runs" "200")) 10))
(def targets (vec (opts "--repo")))
(def owners (vec (opts "--owner")))

(def token
  (or (try (str/trim (str (.execFileSync cp "gh" #js ["auth" "token"] #js {:encoding "utf8"})))
           (catch :default _ nil))
      (.-GH_TOKEN js/process.env)
      (.-GITHUB_TOKEN js/process.env)))

(defn- api [path]
  (-> (js/fetch (str "https://api.github.com" path)
                #js {:headers #js {"Authorization" (str "Bearer " token)
                                   "Accept" "application/vnd.github+json"
                                   "X-GitHub-Api-Version" "2022-11-28"
                                   "User-Agent" "github-actions-billable-audit"}})
      (.then (fn [^js r]
               (-> (.text r)
                   (.then (fn [t]
                            {:status (.-status r)
                             :body (try (js/JSON.parse t) (catch :default _ nil))
                             :text t})))))
      (.catch (fn [e] {:status :network :text (str e)}))))

;; 拒否された理由は捨てない。status だけ記録する経路は、原因が応答の中に
;; 書いてあっても読まない（CLAUDE.md、HTTP 400 を 20 回捨てた実測）。
(defn- why [{:keys [status body text]}]
  (str status " " (or (some-> body .-message) (subs (str text) 0 160))))

;; 列挙に失敗したアカウントを覚えておく。0 件の理由が「repo が無い」なのか
;; 「訊けなかった」なのかを、最後の refusal で区別するため。
(def listing-failures (atom []))

(defn- list-repos [owner]
  (letfn [(page [kind n acc]
            (-> (api (str "/" kind "/" owner "/repos?per_page=100&page=" n))
                (.then (fn [{:keys [status body] :as r}]
                         (cond
                           (and (= 404 status) (= kind "orgs")) (page "users" 1 acc)
                           (not= 200 status) (do (swap! listing-failures conj owner)
                                                 (println "  UNVERIFIED-LISTING" owner (why r))
                                                 acc)
                           :else (let [rs (mapv #(.-full_name %) (array-seq body))]
                                   (if (< (count rs) 100)
                                     (into acc rs)
                                     (page kind (inc n) (into acc rs)))))))))]
    (page "orgs" 1 [])))

(defn- timing-ms [full-name ids]
  (letfn [(step [remaining acc]
            (if-let [id (first remaining)]
              (-> (api (str "/repos/" full-name "/actions/runs/" id "/timing"))
                  (.then (fn [{:keys [status body]}]
                           (if (= 200 status)
                             (let [b (.-billable body)
                                   ms (reduce + 0 (for [k (js-keys b)]
                                                    (or (.-total_ms (aget b k)) 0)))]
                               (step (rest remaining) (-> acc (update :ms + ms) (update :read inc))))
                             (step (rest remaining) (update acc :unread inc))))))
              (js/Promise.resolve acc)))]
    (step ids {:ms 0 :read 0 :unread 0})))

(defn- audit [full-name]
  (-> (js/Promise.all
       #js [(api (str "/repos/" full-name "/actions/permissions"))
            (api (str "/repos/" full-name "/actions/workflows?per_page=100"))
            (api (str "/repos/" full-name "/actions/runs?per_page=100&created=%3E" since))])
      (.then
       (fn [[perm wf runs]]
         (let [enabled (when (= 200 (:status perm)) (.-enabled (:body perm)))
               wfs (when (= 200 (:status wf)) (vec (array-seq (.-workflows (:body wf)))))
               all-runs (when (= 200 (:status runs)) (vec (array-seq (.-workflow_runs (:body runs)))))
               ids (mapv #(.-id %) (take max-runs (or all-runs [])))]
           (-> (timing-ms full-name ids)
               (.then (fn [t]
                        {:repo full-name
                         :enabled enabled
                         :perm-unverified (when (not= 200 (:status perm)) (why perm))
                         :workflows wfs
                         :wf-unverified (when (not= 200 (:status wf)) (why wf))
                         :runs (count (or all-runs []))
                         :runs-total (when all-runs (.-total_count (:body runs)))
                         :runs-unverified (when (not= 200 (:status runs)) (why runs))
                         :capped (max 0 (- (count (or all-runs [])) max-runs))
                         :timing t}))))))))

(defn- report [{:keys [repo enabled perm-unverified workflows wf-unverified
                       runs runs-total runs-unverified capped timing]}]
  (println repo)
  (println (str "  permissions        "
                (cond perm-unverified (str "UNVERIFIED (" perm-unverified ")")
                      enabled "ENABLED  <- GitHub may start a runner for this repo"
                      :else "disabled")))
  (if wf-unverified
    (println (str "  workflows          UNVERIFIED (" wf-unverified ")"))
    (let [dyn (filterv #(str/starts-with? (.-path %) "dynamic/") workflows)
          file (filterv #(not (str/starts-with? (.-path %) "dynamic/")) workflows)
          active (filterv #(= "active" (.-state %)) workflows)]
      (println (str "  workflows          " (count workflows) " registered"
                    "  (file-based " (count file) ", dynamic " (count dyn) ")"
                    "  active " (count active)))
      (doseq [w (sort-by #(.-path %) workflows)]
        (println (str "    " (.padEnd (.-state w) 22) (.-path w))))))
  (if runs-unverified
    (println (str "  runs since " since "  UNVERIFIED (" runs-unverified ")"))
    (println (str "  runs since " since "  " runs
                  (when (and runs-total (> runs-total runs)) (str " of " runs-total))
                  "   billable " (.toFixed (/ (:ms timing) 60000.0) 1) " min"
                  "   (timing read " (:read timing) "/" (+ (:read timing) (:unread timing)) ")"
                  (when (pos? capped) (str "   CAPPED: " capped " run(s) not timed"))))))

(defn -main []
  (if-not token
    (do (println "Refusing to report a verdict: no token (gh auth token / GH_TOKEN / GITHUB_TOKEN).")
        (js/process.exit 2))
    (-> (reduce (fn [p o] (.then p (fn [acc] (-> (list-repos o) (.then #(into acc %))))))
                (js/Promise.resolve targets) owners)
        (.then (fn [repos]
                 (if (empty? repos)
                   (do (if (seq @listing-failures)
                         (println (str "Refusing to report a verdict: could not list "
                                       (str/join ", " @listing-failures)
                                       ". Zero repos here means 'not asked', not 'none'."))
                         (println "Nothing to audit. Pass --repo owner/name or --owner name."
                                  "This tool does not default to every account on purpose."))
                       (js/process.exit 2))
                   (do (println (str "auditing " (count repos) " repo(s), runs created > " since))
                       (reduce (fn [p r] (.then p (fn [acc] (-> (audit r) (.then #(conj acc %))))))
                               (js/Promise.resolve []) repos)))))
        (.then (fn [results]
                 (let [results (vec results)
                       on (filterv :enabled results)
                       billed (filterv #(pos? (get-in % [:timing :ms] 0)) results)
                       unver (filterv #(or (:perm-unverified %) (:wf-unverified %)
                                           (:runs-unverified %) (pos? (get-in % [:timing :unread] 0)))
                                      results)]
                   (doseq [r results] (report r))
                   (println)
                   (println (str "SUMMARY  repos " (count results)
                                 " | actions enabled " (count on)
                                 " | with billable minutes " (count billed)
                                 " | UNVERIFIED " (count unver)))
                   (doseq [r unver] (println (str "  UNVERIFIED " (:repo r))))
                   (println (str "Not answered here: whether a repo's CI is covered on the fleet."
                                 " That is scripts/fleet-ci/gates.edn, not this tool."))
                   (js/process.exit (cond (or (seq on) (seq billed)) 1
                                          (seq unver) 2
                                          :else 0)))))
        (.catch (fn [e] (println "Refusing to report a verdict:" (str e)) (js/process.exit 2))))))

(-main)
