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
;; 再開可能: 走査済み repo は ~/.gftd/gh-actions-sweep-state.edn に記録され、
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

(def state-path (str (.homedir os) "/.gftd/gh-actions-sweep-state.edn"))

(defn log [& xs]
  (println (str "[" (.toISOString (js/Date.)) "] " (str/join " " (map str xs)))))

(def token
  (str/trim (str (.execFileSync cp "gh" #js ["auth" "token"] #js {:encoding "utf8"}))))

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

(defn list-repos
  "アカウントの全 repo（ページング）。-> promise of [full_name …]"
  [{:keys [name kind]}]
  (let [base (if (= :user kind) (str "/users/" name "/repos") (str "/orgs/" name "/repos"))]
    (letfn [(page [n acc]
              (-> (api (str base "?per_page=100&page=" n))
                  (.then (fn [{:keys [status body]}]
                           (if-not (= 200 status)
                             (do (log "WARN" name "repo listing page" n "status" status)
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
               (let [enabled? (and (= 200 status) (.-enabled body))]
                 (cond
                   (not enabled?) (js/Promise.resolve
                                   {:repo full-name :workflows n :already-disabled true})
                   (not apply?) (js/Promise.resolve
                                 {:repo full-name :workflows n :would-disable true})
                   :else (disable! full-name n)))))))

(defn handle-repo [state full-name]
  (if (contains? state full-name)
    (js/Promise.resolve {:repo full-name :skipped true})
    (-> (api (str "/repos/" full-name "/actions/workflows"))
        (.then (fn [{:keys [status body]}]
                 (cond
                   ;; Actions が repo で無効だと workflows は 404 を返す。
                   ;; これは「対象外」であって失敗ではない。
                   (= 404 status) (js/Promise.resolve
                                   {:repo full-name :workflows 0 :note :actions-off})
                   (not= 200 status) (js/Promise.resolve
                                      {:repo full-name :error status})
                   :else (let [n (.-total_count body)]
                           (if (zero? n)
                             (js/Promise.resolve {:repo full-name :workflows 0})
                             (decide-permissions full-name n)))))))))

(defn -main []
  (log (if apply? "APPLY mode — Actions will be disabled" "dry-run (pass --apply to act)"))
  (let [state (atom (read-state))
        accts (if only-owner (filterv #(= only-owner (:name %)) accounts) accounts)]
    (-> (reduce (fn [p acct]
                  (.then p (fn [acc]
                             (-> (list-repos acct)
                                 (.then (fn [rs]
                                          (log (:name acct) ":" (count rs) "repos")
                                          (into acc rs)))))))
                (js/Promise.resolve [])
                accts)
        (.then (fn [repos]
                 (log "scanning" (count repos) "repos with pool" concurrency
                      "(" (count @state) "already recorded)")
                 (pooled concurrency repos
                         (fn [r i]
                           (-> (handle-repo @state r)
                               (.then (fn [res]
                                        (when-not (:skipped res)
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
                       errs (filter :error results)]
                   (log "scanned" (count results)
                        "| with workflows" (count with-wf)
                        "| disabled now" (count disabled)
                        "| already disabled" (count already)
                        "| would disable" (count would)
                        "| errors" (count errs))
                   (doseq [r (take 40 (concat disabled would))]
                     (println "  " (if (:disabled r) "DISABLED" "would-disable")
                              (:repo r) (str "(" (:workflows r) " workflow(s))")))
                   (doseq [r (take 10 errs)]
                     (println "   ERROR" (:repo r) (:error r)))
                   (println "state:" state-path))))
        (.catch (fn [e] (log "FATAL" (str e)) (set! (.-exitCode js/process) 1))))))

(-main)
