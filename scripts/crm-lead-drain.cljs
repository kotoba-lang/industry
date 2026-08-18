;; crm-lead-drain.cljs — 正規化済みリード EDN を cloud-itonami-isic-5820 の
;; capture ingress (POST /leads) へ流す、source 非依存の drain。
;;
;;   nbb scripts/crm-lead-drain.cljs <leads.edn> [--dry-run] [--allow-empty]
;;                                   [--url URL] [--receipt PATH]
;;
;; ADR-2608170700（kotobase.net / murakumo.cloud の商流計画）の Phase 0-3 /
;; Phase 1 で使う。`gftdcojp/itad` の `scripts/crm-drain.cljs` が先行実装で、
;; あちらは **Cloudflare KV (ITAD_LEADS) 専用**。この repo に置くのは、
;; HubSpot（MCP 経由で吸い出した EDN）・kotobase・murakumo が同じ ingress を
;; 共有するのに、KV を経由しない入力を受けられる drain が要るため。
;; 5820 への POST の作法（curl --config を stdin、insert-if-absent の二重冪等、
;; token を argv に出さない）は itad 版から意図的に踏襲している。
;;
;; ## 入力
;;
;; EDN。次のどちらか:
;;   [{:source "hubspot" :external-id "12345" :email "a@example.com" ...} ...]
;;   {:source "hubspot" :leads [{:external-id "12345" :email ...} ...]}
;;
;; 5820 が知っているフィールドだけを送る（`:source` `:external-id` `:email`
;; `:name` `:company` `:captured-at`）。受け側のスキーマに無いものは押し込まない
;; —— itad 版が :device-count 等を送らないのと同じ理由。
;;
;; ## evidence floor（ADR-2608136000）
;;
;; **「測れなかった」を「問題なし」と同じ値で返さない。**
;;
;;   exit 0 … 実際に測って全件を捌いた（SYNCED / SKIPPED のみ）
;;   exit 1 … 測れたが失敗が在る（次の tick が再試行する）
;;   exit 2 … **答えられなかった** —— 入力が読めない / token が解決できない /
;;            5820 が応答しない / SCANNED=0（--allow-empty が無い限り）
;;
;; SCANNED=0 を clean にしないのは、この drain の失敗が「上流に水が無い」と
;; 同じ顔で出るため。空を合格にしてよいのは、呼び出し側が空を期待していると
;; 明示（--allow-empty）したときだけ。
;;
;; ## 認証
;;
;; `ISIC5820_API_TOKEN` -> kagi (compartment gftdcojp, item ISIC5820_API_TOKEN)
;; -> fail-closed。kagi は必要な 1 件だけを識別子で狙い撃ちする（総当たり列挙は
;; しない）。VMK unlock は OS Keychain 経由なので GUI セッションでしか解錠でき
;; ない —— ssh の非 GUI セッションからは exit 2 になるのが正しい挙動。
;; **このワークステーション (main-2) の kagi にこの item は無い**（実測、
;; ADR-2607271200）。実 drain は 25mbair で回す。ここでは --dry-run で検証する。

(require '["child_process" :as cp]
         '["fs" :as fs]
         '["os" :as os]
         '["path" :as node-path]
         '[clojure.edn :as edn]
         '[clojure.string :as str])

(def ^:private argv
  ;; nbb の `js/process.argv` は **script 自身のパスを含む**（`.slice 2` の
  ;; 先頭要素が `scripts/....cljs` になる）。`gftdcojp/itad` の先行実装は
  ;; `--dry-run` の有無しか見ないので踏まなかったが、位置引数を取るこの
  ;; script では先頭が入力ファイルだと誤読して「入力の形が違う」と報告する。
  ;; nbb が渡す `*command-line-args*` は script を除いた実引数だけを持つ。
  (vec *command-line-args*))

(defn- flag? [f] (boolean (some #{f} argv)))

(defn- opt
  "--k v を読む。無ければ nil。"
  [k]
  (let [i (.indexOf (into-array argv) k)]
    (when (and (nat-int? i) (pos? i) (< (inc i) (count argv)))
      (nth argv (inc i)))))

(def ^:private dry-run? (flag? "--dry-run"))
(def ^:private allow-empty? (flag? "--allow-empty"))

(def ^:private input-path
  ;; 位置引数は先頭に置く（fleet gate が <dir> を先に渡すのと同じ規約。
  ;; --flag の後ろの値を tree と誤読する事故を構造的に避ける）。
  (first (remove #(str/starts-with? % "--") argv)))

(def ^:private crm-url
  (or (opt "--url") (.-ISIC5820_URL js/process.env)
      "https://itonami-5820.etzhayyim.com"))

(defn- log [& xs] (js/console.log (str/join " " (map str xs))))
(defn- warn [& xs] (js/console.error (str/join " " (map str xs))))

(defn- unanswered!
  "測れなかった。0 でも 1 でもない値で終わる。"
  [reason]
  (warn (str "UNANSWERED\t" reason))
  (warn "crm-lead-drain: refusing to report a pass — this run did not measure anything.")
  (js/process.exit 2))

(defn- sh
  [cmd args & [{:keys [input]}]]
  (try
    {:exit 0
     :out (str (cp/execFileSync cmd (into-array args)
                                (clj->js (cond-> {:encoding "utf8"
                                                  :maxBuffer (* 16 1024 1024)}
                                           input (assoc :input input)))))
     :err ""}
    (catch :default e
      {:exit (or (.-status e) 1)
       :out (str (.-stdout e))
       :err (str (or (.-stderr e) (.-message e)))})))

;; ───────────────────────── token（1 件だけ狙い撃ち） ─────────────────────────

(defn- kagi-bin []
  (or (.-KAGI_BIN js/process.env)
      (node-path/join (js/process.cwd) "orgs" "kotoba-lang" "kagi" "bin" "kagi")))

(defn- token-from-kagi []
  (let [{:keys [out exit]} (sh (kagi-bin) ["get" "ISIC5820_API_TOKEN"
                                           "--compartment" "gftdcojp"])]
    (when (zero? exit)
      (let [t (str/trim (str out))]
        (when-not (str/blank? t) t)))))

(def ^:private crm-token
  (or (.-ISIC5820_API_TOKEN js/process.env)
      (token-from-kagi)
      (when dry-run? "")))

;; ───────────────────────── payload ─────────────────────────

(def ^:private safe-id-re
  ;; 5820 の crm.http/safe-id-re と同じ charset。ここで弾いておかないと、
  ;; 受け側の 400 を「失敗」として数えることになり、原因が読めない。
  #"^[A-Za-z0-9][A-Za-z0-9._@:+-]{0,127}$")

(def ^:private sendable-keys
  "5820 の POST /leads が知っているフィールドだけ。"
  [:source :external-id :email :name :company :captured-at])

(defn- ->payload [default-source lead]
  (let [m (assoc lead :source (or (:source lead) default-source))]
    (into {} (for [k sendable-keys
                   :let [v (get m k)]
                   :when (and (some? v) (not (str/blank? (str v))))]
               [k (str v)]))))

(defn- invalid-reason [p]
  (cond
    (not (re-matches safe-id-re (str (:source p))))      "invalid :source"
    (not (re-matches safe-id-re (str (:external-id p)))) "invalid :external-id"
    (str/blank? (str (:email p)))                        "missing :email"))

(defn- lead-key [p] (str (:source p) ":" (:external-id p)))

;; ───────────────────────── receipt（二重冪等の 2 本目） ─────────────────────────

(def ^:private receipt-path
  (or (opt "--receipt")
      (when input-path (str input-path ".receipt.edn"))))

(defn- read-receipt []
  (if (and receipt-path (.existsSync fs receipt-path))
    (try (edn/read-string (str (.readFileSync fs receipt-path "utf8")))
         (catch :default e
           ;; 壊れた receipt を空として扱うと全件を再 POST する。5820 側が
           ;; insert-if-absent なので破壊はしないが、黙って握り潰さない。
           (unanswered! (str "receipt unreadable: " receipt-path " " (.-message e)))))
    {}))

(defn- write-receipt! [m]
  (when (and receipt-path (not dry-run?))
    (.writeFileSync fs receipt-path (str (pr-str m) "\n") "utf8")))

;; ───────────────────────── HTTP ─────────────────────────

(defn- preflight!
  "5820 が生きていることを先に確かめる。落ちているのに 0 件成功で終わらない。"
  []
  (let [{:keys [out exit]} (sh "curl" ["-s" "-m" "15" "-o" "/dev/null"
                                       "-w" "%{http_code}" (str crm-url "/health")])]
    (when-not (and (zero? exit) (= "200" (str/trim (str out))))
      (unanswered! (str "5820 /health did not answer 200 at " crm-url
                        " (got " (str/trim (str out)) ")")))))

(defn- post-lead!
  "token は curl の --config(stdin) 経由でのみ渡す — argv にもファイルにも
  出さないので `ps` に残らない。"
  [payload]
  (let [tmp (node-path/join (os/tmpdir) (str "crm-lead-drain-" (.now js/Date) "-"
                                             (rand-int 1e6) ".json"))]
    (.writeFileSync fs tmp (js/JSON.stringify (clj->js payload)) #js {:mode 0600})
    (try
      (let [config (str "url = \"" crm-url "/leads\"\n"
                        "request = \"POST\"\n"
                        "header = \"Authorization: Bearer " crm-token "\"\n"
                        "header = \"Content-Type: application/json\"\n"
                        "data = @" tmp "\n"
                        "silent\nshow-error\nmax-time = 30\n"
                        "write-out = \"\\n%{http_code}\"\n")
            {:keys [out err exit]} (sh "curl" ["--config" "-"] {:input config})]
        (if (zero? exit)
          (let [nl (.lastIndexOf out "\n")]
            {:status (js/parseInt (subs out (inc nl)) 10)
             :body (subs out 0 nl)})
          {:status 0 :body (str "curl failed: " err)}))
      (finally
        (try (.unlinkSync fs tmp) (catch :default _ nil))))))

;; ───────────────────────── main ─────────────────────────

(when-not input-path
  (warn "usage: nbb scripts/crm-lead-drain.cljs <leads.edn> [--dry-run] [--allow-empty] [--url URL] [--receipt PATH]")
  (js/process.exit 1))

(when-not (.existsSync fs input-path)
  (unanswered! (str "input not found: " input-path)))

(def ^:private parsed
  (try (edn/read-string (str (.readFileSync fs input-path "utf8")))
       (catch :default e
         (unanswered! (str "input unreadable: " input-path " " (.-message e))))))

(def ^:private default-source
  (or (opt "--source") (when (map? parsed) (:source parsed))))

(def ^:private raw-leads
  (cond
    (vector? parsed) parsed
    (and (map? parsed) (vector? (:leads parsed))) (:leads parsed)
    :else (unanswered! "input is neither a vector of leads nor {:source .. :leads [..]}")))

(def ^:private payloads (mapv #(->payload default-source %) raw-leads))

(log (str "SCANNED\t" (count payloads)))

(when (and (zero? (count payloads)) (not allow-empty?))
  (unanswered! (str "0 lead(s) in " input-path
                    " — pass --allow-empty if an empty batch is the expected answer")))

(when-not (or dry-run? (seq crm-token))
  (unanswered! (str "could not resolve the 5820 token. Set ISIC5820_API_TOKEN, or unlock kagi "
                    "(compartment gftdcojp, item ISIC5820_API_TOKEN) in a GUI session. "
                    "Note: this item is NOT in main-2's kagi — the real drain runs on 25mbair.")))

(when-not dry-run? (preflight!))

(let [receipt (read-receipt)
      result
      (reduce
       (fn [{:keys [receipt] :as acc} p]
         (let [k (lead-key p)]
           (cond
             (invalid-reason p)
             (do (warn (str "  ! " k ": " (invalid-reason p)))
                 (update acc :invalid inc))

             (get receipt k)
             (do (log (str "  - " k " (receipt -> " (:lead-id (get receipt k)) ")"))
                 (update acc :skipped inc))

             dry-run?
             (do (log (str "  ~ " k " (dry-run) -> " (pr-str p)))
                 (update acc :skipped inc))

             :else
             (let [{:keys [status body]} (post-lead! p)
                   parsed-body (try (js->clj (js/JSON.parse body) :keywordize-keys true)
                                    (catch :default _ nil))]
               (if (#{200 201} status)
                 ;; 200 = 既に capture 済み。drain としてはどちらも成功。
                 (do (log (str "  + " k " -> " (:lead-id parsed-body)
                               (if (= 201 status) " (created)" " (already present)")))
                     (-> acc
                         (update :synced inc)
                         (assoc-in [:receipt k] {:lead-id (:lead-id parsed-body)
                                                 :synced-at (.toISOString (js/Date.))})))
                 (do
                   ;; 応答本文を捨てない（status だけ記録すると原因が読めない）。
                   (warn (str "  ! " k ": 5820 returned " status " " body))
                   (update acc :failed inc)))))))
       {:synced 0 :skipped 0 :invalid 0 :failed 0 :receipt receipt}
       payloads)]

  (write-receipt! (:receipt result))
  (log (str "SYNCED\t" (:synced result)
            "\tSKIPPED\t" (:skipped result)
            "\tINVALID\t" (:invalid result)
            "\tFAILED\t" (:failed result)
            (when dry-run? "\t[dry-run]")))
  (when (pos? (+ (:failed result) (:invalid result)))
    (js/process.exit 1)))
