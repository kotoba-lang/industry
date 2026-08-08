#!/usr/bin/env nbb
;; itonami-org-id-check.cljs — cloud-itonami の org id 受付が、この workspace が
;; 実際に使っている org 名と同じ導出をしているかを見る。
;;
;; **何を検査するか。** ADR-2608062100 は「予約語以外の org id は registrable
;; domain のラベル逆順」と決め、門を **intake と generate-sites-registry の 2 箇所**
;; に置くと書いた。registry 側は `cloud-itonami-sites-check` gate が repo 自身の
;; `generate-sites-registry.cljs --check`（= `org-id/admit`）を呼ぶので覆われている。
;; **intake 側を見るものは無かった。**
;;
;; 実測 2026-08-08、live の `POST /api/onboarding/challenge` は逆順ではなく順方向を
;; 返していた:
;;
;;     awai.network    -> awai-network      (規則は network-awai)
;;     gftd.co.jp      -> gftd-co-jp        (規則は jp-co-gftd)
;;     junkawasaki.com -> junkawasaki-com   (規則は com-junkawasaki)
;;
;; 3 件とも、逆順にした形が **この workspace に実在する org 名そのもの**である。
;; つまり誤りは理論上のものではなく、intake が発行する org id が、同じ組織を指す
;; 既存の名前と一致しない。
;;
;; **規則を再実装しない。** 期待値は `manifest/repository-rules.edn` の
;; `:org-domain` vocabulary（ADR-2608040170、2026-08-05 に 7 org すべて DNS 実測済み）
;; が持つ `:domain` -> `:reversed` の対を**そのまま**使う。gate が 3 つ目の導出を
;; 書くと、規則が 2 つある問題を 3 つある問題にして直したことにしてしまう。
;;
;; **観測できないことを合格にしない。** 到達できなければ INCONCLUSIVE で落とす
;; （exit 1）。「検査できなかった」を「検査して合格した」と読ませないため —— これは
;; `cloud_itonami/app/fleet.clj` が timeout/DNS/TLS を「壊れている」と区別する
;; のと同じ理由で、区別できないと片方がもう片方に化ける。
;;
;; ノード側で `npx nbb itonami-org-id-check.cljs <dir> [--min N] [--base URL]`。

(ns fleet-ci.gates.itonami-org-id-check
  (:require ["node:fs" :as fs]
            ["node:path" :as path]
            [clojure.edn :as edn]
            [clojure.string :as str]))

;; `process.argv` を直に読まない（cloud-itonami-sites-check と同じ理由: nbb では
;; argv[2] がスクリプト自身のパスになり、root がスクリプトを指して cwd が壊れる）。
(def args (vec *command-line-args*))

(def ^:private value-flags #{"--min" "--base"})

(def root
  (loop [[a & more] args prev nil]
    (cond (nil? a) "."
          (str/starts-with? a "--") (recur more a)
          (contains? value-flags prev) (recur more nil)
          :else a)))

(defn- opt [flag default]
  (let [i (.indexOf args flag)]
    (if (neg? i) default (get args (inc i)))))

(def min-pairs (js/parseInt (opt "--min" "7")))
(def base (str/replace (opt "--base" "https://itonami.cloud") #"/$" ""))

(def failures (atom []))

(defn- fail! [& parts]
  (let [m (str/join " " (map str parts))]
    (swap! failures conj m)
    (println "FAIL" m)))

;; ---------------------------------------------------------------------------
;; 期待値: repository-rules.edn の :org-domain vocabulary をそのまま読む。

(def rules-path (path/join root "manifest" "repository-rules.edn"))

(defn- org-domain-rules []
  (when-not (fs/existsSync rules-path)
    (println "FAIL missing" rules-path)
    (swap! failures conj "repository-rules.edn not in the shipped tree")
    (set! (.-exitCode js/process) 1))
  (when (fs/existsSync rules-path)
    (let [doc (edn/read-string (fs/readFileSync rules-path "utf8"))
          vocabs (:vocabularies doc)
          v (first (filter #(= :org-domain (:vocabulary/id %)) vocabs))]
      (vec (:vocabulary/rules v)))))

;; ---------------------------------------------------------------------------
;; 観測: live intake に domain を出して、返ってくる org を読む。

(defn- challenge! [domain]
  (-> (js/fetch (str base "/api/onboarding/challenge")
                #js {:method "POST"
                     :headers #js {"content-type" "application/json"}
                     :body (js/JSON.stringify #js {:domain domain})})
      (.then (fn [r] (.json r)))
      (.then (fn [j] {:ok true
                      :org (some-> (aget j "org") str)
                      :refusal (some-> (aget j "error") str)}))
      (.catch (fn [e] {:ok false :error (.-message e)}))))

(defn- check-one! [{:keys [org domain reversed]}]
  (-> (challenge! domain)
      (.then (fn [{:keys [ok error refusal] :as res}]
               (let [observed (:org res)]
                 (cond
                   (not ok)
                   (do (fail! "INCONCLUSIVE" domain "— intake unreachable:" error) :inconclusive)

                   ;; 予約は明示列挙（ADR-2608062100）。運用者のドメインを自己登録
                   ;; させないのは正しい拒否なので、検査済みとして数える。
                   (= refusal "reserved-domain")
                   (do (println "ok  " domain "— reserved, refused as designed") :reserved)

                   (str/blank? (str observed))
                   (do (fail! domain "— intake returned no org id"
                              (if refusal (str "(" refusal ")") "")) :bad)

                   (not= observed reversed)
                   (do (fail! domain "-> intake says" (str "'" observed "'")
                              "but the reverse-DNS rule says" (str "'" reversed "'")
                              (str "(org '" org "')"))
                       :bad)

                   :else
                   (do (println "ok  " domain "->" observed) :ok)))))))

(defn- main! []
  (let [rules (org-domain-rules)]
    (when (seq rules)
      (println "org-domain rules:" (count rules) "· intake:" base)
      (-> (js/Promise.all (clj->js (map check-one! rules)))
          (.then
           (fn [outcomes]
             (let [outcomes (vec outcomes)
                   checked (count outcomes)]
               ;; 絞り込みが壊れて 0 件を「合格」にしない床。上限ではない。
               (when (< checked min-pairs)
                 (fail! "only" checked "org/domain pairs checked, floor is" min-pairs))
               (println (str "checked " checked " pairs, " (count @failures) " failing"))
               (when (seq @failures)
                 (println "")
                 (println "ADR-2608062100: 予約語以外の org id は registrable domain の")
                 (println "ラベル逆順。門は intake と generate-sites-registry の 2 箇所。")
                 (set! (.-exitCode js/process) 1)))))))))

(main!)
