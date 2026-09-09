#!/usr/bin/env nbb
;; github-actions-disable-sweep.cljs — このワークスペースの全 GitHub アカウントを
;; 走査し、**workflow を持つ repo の GitHub Actions を無効化**する。
;;
;; なぜ（ADR-2607300900 + オーナー指示 2026-08-05「github は使わない、
;; murakumo.cloud の cdci, workflow を使う」）:
;; Actions が 3 org で job を起動しなくなった時、**落ちるのではなく走らなかった**
;; ので repo は green に見えたまま何も検査されていなかった。CI の正本は
;; murakumo fleet（scripts/fleet-ci/）であり、Actions が「たまたま動いている」
;; 状態は、そこに検査があるという誤読を生むだけで価値が無い。
;;
;; **workflow ファイルは消さない。** `.github/workflows/**` の削除には GitHub の
;; `workflow` OAuth scope が要り、このワークスペースの token は持っていない
;; （push も Contents API も通らず、後者は 403 でなく 404 を返す）。Actions を
;; repo 単位で無効化する方は `repo` scope で通るので、そちらで止める。
;; ファイルは inert として残る。
;;
;; org 単位の一括無効化（PUT /orgs/{org}/actions/permissions）は `admin:org` が
;; 要り、これも持っていない（実測 2026-08-05）。だから repo 単位で回す。
;;
;; 使い方:
;;   nbb scripts/github-actions-disable-sweep.cljs            # dry-run（既定）
;;   nbb scripts/github-actions-disable-sweep.cljs --apply    # 実際に無効化
;;   nbb scripts/github-actions-disable-sweep.cljs --apply --owner kotoba-lang
;;
;; 再開可能: 走査済み repo は ~/.itonami/gh-actions-sweep-state.edn に記録され、
;; 再実行では skip する（rate limit で途中終了しても続きから回せる）。
;; --recheck で state を無視して全件見直す。
(ns github-actions-disable-sweep
  (:require [clojure.string :as str]))

(def fs (js/require "fs"))
(def os (js/require "os"))
(def cp (js/require "child_process"))

(def args (vec *command-line-args*))
(defn flag? [f] (some #(= f %) args))
(defn opt [f] (let [i (.indexOf args f)] (when-not (neg? i) (nth args (inc i) nil))))

(def apply? (flag? "--apply"))
(def recheck? (flag? "--recheck"))
(def only-owner (opt "--owner"))
;; --repo owner/name（複数可）。列挙を経由せず 1 件だけ扱う。
;; 列挙が通らない環境で handle-repo / decide-permissions の分岐を実際に走らせて
;; 確かめるために要る —— 走らせたことのない分岐を landed としない。
(def only-repos
  (vec (keep-indexed (fn [i a] (when (= a "--repo") (nth args (inc i) nil))) args)))
(def concurrency (js/parseInt (or (opt "--jobs") "6") 10))

;; 走査対象のアカウント。west.yml の remote が正だが、ここは「GitHub 上の
;; アカウント」の列挙なので west に無い repo も含めて掃くために直接持つ。
;; com-junkawasaki は org ではなく **user** アカウント（orgs/ は 404、users/ が返る）。
(def accounts
  [{:name "kotoba-lang" :kind :org}
   {:name "cloud-itonami" :kind :org}
   {:name "etzhayyim" :kind :org}
   {:name "gftdcojp" :kind :org}
   {:name "network-awai" :kind :org}
   {:name "jk-luxury" :kind :org}
   {:name "com-junkawasaki" :kind :user}])

(def state-path (str (.homedir os) "/.itonami/gh-actions-sweep-state.edn"))

(defn log [& xs]
  (println (str "[" (.toISOString (js/Date.)) "] " (str/join " " (map str xs)))))

;; gh が無い環境（remote container / CI）でも動くように env へ落ちる。
;; gh があるときは従来どおり gh が勝つ。
(def token
  (or (try (str/trim (str (.execFileSync cp "gh" #js ["auth" "token"]
                                         #js {:encoding "utf8" :stdio "pipe"})))
           (catch :default _ nil))
      (.-GH_TOKEN js/process.env)
      (.-GITHUB_TOKEN js/process.env)))

(defn sleep [ms] (js/Promise. (fn [res] (js/setTimeout res ms))))

;; rate limit。残りが少なくなったら reset まで待つ。走り切れずに黙って
;; 短い結果を返すより、待って完走する方がよい（部分結果は「対象が少なかった」
;; と誤読される）。
(def remaining (atom 5000))
(def reset-at (atom 0))

;; **予備枠を大きめに取る理由**: この 5,000/h は fleet-ci と共有している。
;; tick は 5 分ごとに API を叩くので、掃き切るために使い切ると CI が 1 時間
;; 止まる。400 残して待つと sweep は遅くなるが CI は動き続ける。
(def rate-reserve 400)

(defn api
  "GET/PUT を rate-limit 対応で叩く。-> promise of {:status n :body parsed|nil}"
  ([path] (api path {}))
  ([path {:keys [method body tries] :or {method "GET" tries 4}}]
   (letfn [(attempt [n]
             (-> (js/Promise.resolve
                  (when (and (< @remaining rate-reserve) (pos? @reset-at))
                    (let [wait (max 0 (- (* 1000 @reset-at) (.now js/Date)))]
                      (when (pos? wait)
                        (log "rate limit low (" @remaining ") — sleeping"
                             (js/Math.round (/ wait 1000)) "s until reset")
                        (sleep (+ wait 2000))))))
                 (.then (fn [_]
                          (js/fetch (str "https://api.github.com" path)
                                    (clj->js (cond-> {:method method
                                                      :headers {"Authorization" (str "Bearer " token)
                                                                "Accept" "application/vnd.github+json"
                                                                "X-GitHub-Api-Version" "2022-11-28"
                                                                "User-Agent" "gh-actions-disable-sweep"}}
                                               body (assoc :body (js/JSON.stringify (clj->js body))))))))
                 (.then (fn [^js resp]
                          (when-let [r (.get (.-headers resp) "x-ratelimit-remaining")]
                            (reset! remaining (js/parseInt r 10)))
                          (when-let [r (.get (.-headers resp) "x-ratelimit-reset")]
                            (reset! reset-at (js/parseInt r 10)))
                          (let [st (.-status resp)]
                            (if (and (< n tries) (or (>= st 500) (= 429 st) (= 403 st)))
                              (-> (sleep (* 2000 n)) (.then (fn [_] (attempt (inc n)))))
                              (-> (.text resp)
                                  (.then (fn [t]
                                           {:status st
                                            :body (try (js/JSON.parse t) (catch :default _ nil))})))))))
                 (.catch (fn [e]
                           (if (< n tries)
                             (-> (sleep (* 2000 n)) (.then (fn [_] (attempt (inc n)))))
                             {:status :error :error (str e)})))))]
     (attempt 1))))

;; 列挙できなかったページを覚えておく。**これが無いと、1 件も列挙できなかった
;; sweep が「scanned 0 | errors 0」= 掃き切ったのと同じ出力になる**（実測
;; 2026-08-22、proxy が repo listing を 403 で拒否した run が exit 0 を返した）。
(def listing-failures (atom []))

(defn list-repos
  "アカウントの全 repo（ページング）。-> promise of [full_name …]"
  [{:keys [name kind]}]
  (let [base (if (= :user kind) (str "/users/" name "/repos") (str "/orgs/" name "/repos"))]
    (letfn [(page [n acc]
              (-> (api (str base "?per_page=100&page=" n))
                  (.then (fn [{:keys [status body]}]
                           (if-not (= 200 status)
                             (do (swap! listing-failures conj (str name " page " n " -> " status))
                                 (log "UNVERIFIED-LISTING" name "repo listing page" n
                                      "status" status)
                                 acc)
                             (let [rs (mapv #(.-full_name %) (array-seq body))]
                               (if (< (count rs) 100)
                                 (into acc rs)
                                 (page (inc n) (into acc rs)))))))))]
      (page 1 []))))

(defn pooled [n items f]
  (let [items (vec items) total (count items) idx (atom 0) out (atom [])]
    (letfn [(worker []
              (let [i @idx]
                (if (>= i total)
                  (js/Promise.resolve nil)
                  (do (reset! idx (inc i))
                      (-> (f (nth items i) i)
                          (.then (fn [r] (swap! out conj r) (worker))))))))]
      (-> (js/Promise.all (clj->js (map (fn [_] (worker)) (range (min n total)))))
          (.then (fn [_] @out))))))

(defn read-state []
  (if (and (not recheck?) (.existsSync fs state-path))
    (try (let [edn (js/require "edamame/lib/edamame/core.js")]
           (js->clj (.parseString edn (str (.readFileSync fs state-path "utf8")))))
         (catch :default _ {}))
    {}))

(defn write-state! [m]
  (.mkdirSync fs (str (.homedir os) "/.gftd") #js {:recursive true})
  (.writeFileSync fs state-path (str (pr-str m) "\n")))

(defn- disable! [full-name n]
  (-> (api (str "/repos/" full-name "/actions/permissions")
           {:method "PUT" :body {:enabled false}})
      (.then (fn [{:keys [status]}]
               (if (< status 300)
                 {:repo full-name :workflows n :disabled true}
                 {:repo full-name :workflows n :error (str "disable failed " status)})))))

(defn- decide-permissions [full-name n]
  (-> (api (str "/repos/" full-name "/actions/permissions"))
      (.then (fn [{:keys [status body]}]
               (cond
                 ;; permissions が読めなかったことを「無効」と読まない。
                 ;; 最初の版は `(and (= 200 status) (.-enabled body))` を
                 ;; enabled? とし、403 も 404 も network error も
                 ;; `:already-disabled` に落としていた —— **答えられなかった
                 ;; repo が、確かめて無効だった repo と同じ列に並ぶ。**
                 (not= 200 status)
                 (js/Promise.resolve {:repo full-name :workflows n
                                      :unverified (str "permissions " status)})

                 (not (.-enabled body))
                 (js/Promise.resolve {:repo full-name :workflows n :already-disabled true})

                 (not apply?)
                 (js/Promise.resolve {:repo full-name :workflows n :would-disable true})

                 :else (disable! full-name n))))))

(defn handle-repo [state full-name]
  (if (contains? state full-name)
    (js/Promise.resolve {:repo full-name :skipped true})
    (-> (api (str "/repos/" full-name "/actions/workflows"))
        (.then (fn [{:keys [status body]}]
                 (cond
                   ;; **404 だけでは「Actions が無効」と言えない。** Actions が
                   ;; 無効な repo も 404 を返すが、見えない repo・改名された repo・
                   ;; scope の足りない token も同じ 404 を返す。1 つの status に
                   ;; 1 つの原因を割り当てると、答えられなかったものが答えの中に
                   ;; 混ざる。だから 404 では判定せず、permissions に訊きに行って
                   ;; そちらに答えさせる（読めれば confirmed、読めなければ
                   ;; :unverified になる）。
                   (= 404 status) (decide-permissions full-name 0)

                   (not= 200 status)
                   (js/Promise.resolve {:repo full-name :unverified (str "workflows " status)})

                   ;; **workflow が 0 本でも permissions を必ず引く。**
                   ;; 「登録された workflow が無い」は「Actions が無効」ではない。
                   ;; 実測 2026-08-22: com-junkawasaki/root は `.github/` を
                   ;; 1 ファイルも持たないのに registered workflow を 3 本持つ
                   ;; （Dependabot の dynamic 2 本 + 削除済みファイルの stale 1 本）。
                   ;; 逆に、本当に 0 本でも Actions が有効なままなら、workflow が
                   ;; 1 つ載った瞬間に走り出す —— 0 本は無効化を省く理由にならない。
                   :else (decide-permissions full-name (.-total_count body))))))))

(defn- sweep! []
  (log (if apply? "APPLY mode — Actions will be disabled" "dry-run (pass --apply to act)"))
  (let [state (atom (read-state))
        accts (if only-owner (filterv #(= only-owner (:name %)) accounts) accounts)]
    (-> (if (seq only-repos)
          (do (log "explicit targets:" (count only-repos) "repo(s) — no listing")
              (js/Promise.resolve only-repos))
          (reduce (fn [p acct]
                  (.then p (fn [acc]
                             (-> (list-repos acct)
                                 (.then (fn [rs]
                                          (log (:name acct) ":" (count rs) "repos")
                                          (into acc rs)))))))
                  (js/Promise.resolve [])
                  accts))
        (.then (fn [repos]
                 (log "scanning" (count repos) "repos with pool" concurrency
                      "(" (count @state) "already recorded)")
                 (pooled concurrency repos
                         (fn [r i]
                           (-> (handle-repo @state r)
                               (.then (fn [res]
                                        ;; 答えられなかった repo を state に書かない。
                                        ;; 書くと次回 skip され、非回答が回答として固定される。
                                        (when-not (or (:skipped res) (:unverified res) (:error res))
                                          (swap! state assoc (:repo res) (dissoc res :repo))
                                          (when (zero? (mod (inc i) 200))
                                            (write-state! @state)
                                            (log "progress" (inc i) "/" (count repos)
                                                 "rate-remaining" @remaining)))
                                        res)))))))
        (.then (fn [results]
                 (write-state! @state)
                 (let [with-wf (filter #(pos? (or (:workflows %) 0)) results)
                       disabled (filter :disabled results)
                       would (filter :would-disable results)
                       already (filter :already-disabled results)
                       unver (filter :unverified results)
                       errs (filter :error results)]
                   (log "scanned" (count results)
                        "| with workflows" (count with-wf)
                        "| disabled now" (count disabled)
                        "| already disabled" (count already)
                        "| would disable" (count would)
                        "| UNVERIFIED" (count unver)
                        "| errors" (count errs))
                   (doseq [r (take 40 (concat disabled would))]
                     (println "  " (if (:disabled r) "DISABLED" "would-disable")
                              (:repo r) (str "(" (:workflows r) " workflow(s))")))
                   (doseq [r (take 20 unver)]
                     (println "   UNVERIFIED" (:repo r) (:unverified r)))
                   (doseq [r (take 10 errs)]
                     (println "   ERROR" (:repo r) (:error r)))
                   (println "state:" state-path)
                   (doseq [f @listing-failures] (println "   UNVERIFIED-LISTING" f))
                   ;; 走り切ったことと掃き切ったことは別。答えられなかった repo が
                   ;; 1 つでもあれば exit 0 にしない —— 呼び出し側（loop / routine）が
                   ;; 「sweep は通った」と読めてしまう。列挙自体が失敗していたら、
                   ;; 走査 0 件は「対象が無かった」ではなく「訊けなかった」なので 2。
                   (cond
                     (seq @listing-failures)
                     (do (println (str "Refusing to report a clean sweep: "
                                       (count @listing-failures)
                                       " repo listing(s) failed. Zero scanned here means"
                                       " 'not asked', not 'nothing to disable'."))
                         (set! (.-exitCode js/process) 2))
                     (or (seq unver) (seq errs) (seq would))
                     (set! (.-exitCode js/process) 1)))))
        (.catch (fn [e] (log "FATAL" (str e)) (set! (.-exitCode js/process) 1))))))

(defn -main []
  ;; token が無いのは「掃くものが無かった」ではない。走らずに 2 で終わる。
  (if token
    (sweep!)
    (do (log "Refusing to sweep: no token (gh auth token / GH_TOKEN / GITHUB_TOKEN).")
        (set! (.-exitCode js/process) 2))))

(-main)
