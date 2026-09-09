#!/usr/bin/env nbb
;; scripts/loop-status-publish.cljs — この workstation の com.gftd.* loop 群の
;; 現在地を itonami.cloud の公開 status page へ発行する。**決定論。モデルは
;; 一切呼ばない**（claude 起動も skip-permissions フラグも無い、純粋な publisher）。
;; LaunchAgent com.gftd.loop-status-publish が 30 分ごとに起こす。
;;
;; ## 何を発行するか
;;
;; 2 つの population を **1 通の document** に載せる（ADR-2608318000 /
;; cloud-itonami ADR-0053）:
;;
;;   `loops`      launchd の com.gftd.* Claude loop（従来どおり）
;;   `residents`  Hermes の cron 常駐（~/.hermes/cron/jobs.json）
;;
;; **1 通なのは KV の key が 1 つだからである。** `/api/bots-status` は
;; `current` を丸ごと上書きするので、publisher を 2 本にすると片方が
;; もう片方を静かに消す —— しかもどちらも 204 を受け取る。
;;
;; residents は **workdir も prompt も載せない**（前者は operator の home
;; 配下の絶対パス、後者は agent の指示そのもの）。載せるのは name /
;; schedule / last_run / next_run / last_status / failure_streak /
;; enabled / paused / model / provider / script（ファイル名のみ）/
;; has_error（**本文ではなく有無**）。
;;
;; 読めなかったときは `residents.why` に理由を入れて **空配列を送らない
;; ようにする** —— 空の jobs は「常駐は 1 体も居ない」と読め、それは
;; 失敗が生んではならない答え。page 側は why が在れば section 全体を
;; unmeasured にする。
;;
;; loop の列挙は **union**（片方に居ないものを黙って落とさない）:
;;   (a) ledger を持つ loop — 対応する scripts/<name>-loop.cljs
;;       （repo-bot-drain は scripts/repo-bots/drain-loop.cljs）が存在するもの
;;   (b) ~/Library/LaunchAgents/com.gftd.<name>.plist が installed なもの
;;
;; plist は在るのに ledger がまだ無い loop も **last_run null で載せる** ——
;; 不在は見えなければならない（省略は「動いている」と同じ顔をする）。
;;
;; ledger の置き場は歴代 loop で 3 形あるので、name ごとに順に引く:
;;   1. ~/.itonami/<name>/ledger.edn          （isekai-game-dev / kami-lib-update 形）
;;   2. ~/.itonami/<name>.ledger.edn          （fleet-refactor-wave / svelte-cljs-wave 形）
;;   3. repo-bot-drain だけ ~/.itonami/repo-bots/drain.ledger.edn
;;
;; ledger の最終行が EDN として読めなければ outcome null / why "ledger-unreadable"
;; —— **読めなかったものを ok に畳まない**（ADR-2608136000）。
;;
;; ## 発行先
;;
;; PUT https://itonami.cloud/api/bots-status（KV-backed、対の公開 page は
;; itonami.cloud/bots/）。bearer token は ~/.itonami/bots-status-token（mode 600、
;; 値をログにも argv にも出さない — header はファイル経由で curl に渡す）。
;;
;; ## 出力と exit
;;
;;   --dry-run       document(JSON) を印字して PUT しない。exit 0
;;   --self-test     residents の読み取りを両方向で確かめる。exit 0/1
;;   token が無い    {:outcome :not-measured :why :token-missing}、exit 2
;;                   —— 発行できなかったことを成功に見せない
;;   PUT が 204 以外 {:outcome :publish-failed :status … :body …}、exit 1
;;                   —— **応答 body を捨てない**（原因はたいてい body に書いてある）
;;   PUT が 204      {:outcome :published :loops N :residents N|:unmeasured}、
;;                   exit 0 —— residents が読めなかった run を「発行できた」
;;                   だけで済ませない（数と理由を両方出す）
;;
;; usage:
;;   nbb --classpath ".:scripts/nbb_compat" scripts/loop-status-publish.cljs [--dry-run|--self-test]

(ns loop-status-publish
  (:require [clojure.edn :as edn]
            [clojure.string :as str]))

(def fs (js/require "node:fs"))
(def os (js/require "node:os"))
(def cp (js/require "node:child_process"))

(def home (.homedir os))
(def root (or (aget (.-env js/process) "COM_JUNKAWASAKI_ROOT")
              (str home "/github/com-junkawasaki")))
(def gftd (str home "/.gftd"))
(def agents-dir (str home "/Library/LaunchAgents"))
(def token-file (str gftd "/bots-status-token"))
(def endpoint "https://itonami.cloud/api/bots-status")
(def dry-run? (boolean (some #{"--dry-run"} *command-line-args*)))
(def self-test? (boolean (some #{"--self-test"} *command-line-args*)))

(defn- exists? [p] (try (.existsSync fs p) (catch :default _ false)))
(defn- read-file [p] (try (.readFileSync fs p "utf8") (catch :default _ nil)))
(defn- list-names [p]
  (try (vec (.readdirSync fs p)) (catch :default _ [])))

;; ---------------------------------------------------------------- enumerate

(defn- loop-script?
  "この name の loop 実体が repo に在るか。"
  [name]
  (or (exists? (str root "/scripts/" name "-loop.cljs"))
      (and (= name "repo-bot-drain")
           (exists? (str root "/scripts/repo-bots/drain-loop.cljs")))))

(defn- ledger-path
  "name → 実在する ledger のパス（歴代 3 形を順に）。無ければ nil。"
  [name]
  (cond
    (= name "repo-bot-drain")
    (let [p (str gftd "/repo-bots/drain.ledger.edn")] (when (exists? p) p))
    :else
    (or (let [p (str gftd "/" name "/ledger.edn")] (when (exists? p) p))
        (let [p (str gftd "/" name ".ledger.edn")] (when (exists? p) p)))))

(defn- ledger-names
  "ledger を持ち、loop script も在る name の集合。"
  []
  (let [dir-form (for [d (list-names gftd)
                       :when (exists? (str gftd "/" d "/ledger.edn"))]
                   d)
        flat-form (for [f (list-names gftd)
                        :when (str/ends-with? f ".ledger.edn")]
                    (subs f 0 (- (count f) (count ".ledger.edn"))))
        drain (when (exists? (str gftd "/repo-bots/drain.ledger.edn"))
                ["repo-bot-drain"])]
    (->> (concat dir-form flat-form drain)
         (filter loop-script?)
         set)))

(defn- plist-names
  "installed な com.gftd.<name>.plist の name 集合。"
  []
  (->> (list-names agents-dir)
       (keep #(second (re-matches #"com\.gftd\.(.+)\.plist" %)))
       set))

;; ---------------------------------------------------------------- per-loop row

(defn- interval-s
  "plist の StartInterval。無い / 読めない → nil。"
  [name]
  (when-let [txt (read-file (str agents-dir "/com.gftd." name ".plist"))]
    (when-let [m (re-find #"<key>StartInterval</key>\s*<integer>(\d+)</integer>" txt)]
      (js/parseInt (second m) 10))))

(defn- last-ledger-entry
  "ledger の最終非空行。{:lines n :entry m|nil :unreadable? bool}。
  読めない行を nil entry のまま返す —— ok に畳まない。"
  [path]
  (when-let [txt (read-file path)]
    (let [lines (->> (str/split-lines txt) (remove str/blank?) vec)]
      (if (empty? lines)
        {:lines 0 :entry nil :unreadable? false}
        (let [entry (try (edn/read-string (peek lines)) (catch :default _ ::unreadable))]
          (if (= ::unreadable entry)
            {:lines (count lines) :entry nil :unreadable? true}
            {:lines (count lines) :entry entry :unreadable? false}))))))

(defn- row [name installed]
  (let [lp (ledger-path name)
        led (when lp (last-ledger-entry lp))
        entry (:entry led)]
    {:name name
     :label (str "com.gftd." name)
     :installed (contains? installed name)
     :interval_s (interval-s name)
     :last_run (when entry (:at entry))
     :last_outcome (cond
                     (:unreadable? led) nil
                     (some? (:outcome entry)) (clojure.core/name (:outcome entry))
                     :else nil)
     :why (cond
            (:unreadable? led) "ledger-unreadable"
            (nil? lp) "no-ledger"
            (:why entry) (clojure.core/name (:why entry))
            :else nil)
     :ledger_lines (:lines led 0)}))

;; ---------------------------------------------------------- hermes residents

(def hermes-home
  (or (aget (.-env js/process) "HERMES_HOME") (str home "/.hermes")))

(defn- read-one-jobs
  "<dir>/cron/jobs.json → {:jobs [job…]} か {:why \\\"…\\\"}。
  読めなかった理由を名前で返す（read-hermes-jobs が全体を束ねる）。"
  [f]
  (if (not (exists? f))
    {:why "jobs-file-absent"}
    (let [txt (read-file f)]
      (if (nil? txt)
        {:why "jobs-file-unreadable"}
        (let [parsed (try (js/JSON.parse txt) (catch :default _ ::bad))]
          (cond
            (= ::bad parsed) {:why "jobs-file-invalid-json"}
            (nil? parsed) {:why "jobs-file-invalid-json"}
            :else
            (let [arr (aget parsed "jobs")]
              (if-not (array? arr)
                {:why "jobs-file-shape-unexpected"}
                {:jobs (vec arr)}))))))))


(defn- read-hermes-jobs
  "All of <home>/cron/jobs.json AND <home>/profiles/*/cron/jobs.json →
  {:jobs [job…] :scanned n} か {:why \\\"…\\\"}。

  ADR-0053 puts the Hermes cron residents in the bots plane, and that
  population is spread across *every* Hermes profile, not just the root:
  `~/.hermes/cron/jobs.json` holds the default profile's jobs, while
  per-profile bots (e.g. itonami-isco-1-scout, wiki-kaonavi-crawl) live in
  `~/.hermes/profiles/<name>/cron/jobs.json`. A reader that only looks at
  the root file silently misses the per-profile majority — which is the
  exact 'unmeasured reports as zero' shape this function exists to prevent.
  Real population measured 2026-09-08: 59 default jobs, plus ~30 per-profile
  occupation bots invisible to the old single-file reader.

  **読めなかった理由を捨てない。** Each jobs file can fail in the same
  5 ways as before, and each is still named. A broken root file with healthy
  profiles still yields the profiles' jobs, so a single broken file cannot
  blank the whole population; only a total failure (no home, or no readable
  jobs anywhere) yields a bare why with no jobs.

  home を引数に取るのは self-test がこの関数**そのもの**を回すため。
  分類器を写して回すテストは、分類器が壊れても緑のままになる。"
  ([] (read-hermes-jobs hermes-home))
  ([hermes-home]
   (if-not (exists? hermes-home)
     {:why "hermes-home-absent"}
     (let [profiles-dir (str hermes-home "/profiles")
           root (read-one-jobs (str hermes-home "/cron/jobs.json"))
           pdirs (when (exists? profiles-dir)
                   (let [es (try (vec (.readdirSync fs profiles-dir))
                                 (catch :default _ []))]
                     (remove #(or (.startsWith % ".") (.endsWith % ".bak"))
                             (mapv str es))))
           files (cond
                   (string? pdirs) [(str hermes-home "/cron/jobs.json")]
                   (nil? pdirs) [(str hermes-home "/cron/jobs.json")]
                   :else (cons (str hermes-home "/cron/jobs.json")
                               (map #(str profiles-dir "/" % "/cron/jobs.json")
                                    pdirs)))
           results (map read-one-jobs files)
           good (filter :jobs results)
           jobs (vec (apply concat (map :jobs good)))
           errs (keep :why results)]
       (if (seq jobs)
         {:jobs jobs :scanned (count results)}
         ;; a total failure carries a reason and NO jobs key — the page must
         ;; render `unmeasured`, never "no residents" (ADR-0053).
         {:why (or (first errs) "jobs-file-absent")})))))


(defn- resident-row
  "1 job → 発行する facts だけ。**workdir と prompt は写さない。**"
  [job]
  (let [g #(let [v (aget job %)] (when-not (undefined? v) v))]
    {:name (g "name")
     :enabled (boolean (g "enabled"))
     :paused (some? (g "paused_at"))
     :schedule (or (g "schedule_display")
                   (some-> (g "schedule") (aget "expr")))
     :last_run (g "last_run_at")
     :next_run (g "next_run_at")
     :last_status (g "last_status")
     ;; **本文は載せない。** hermes の last_error は失敗した script の
     ;; stdout をそのまま抱えており（実測: at:// URI と投稿本文が入って
     ;; いた）、それを公開ページに転記するのは任意のプロセス出力を公開
     ;; することになる。「失敗していて、記録が在る」までが公開してよい
     ;; 事実で、中身は operator が手元で読む。
     :has_error (some? (g "last_error"))
     :failure_streak (g "failure_streak")
     :agent (not (true? (g "no_agent")))
     ;; script は path ではなくファイル名（~/.hermes/scripts 配下の名前）
     :script (g "script")
     :model (g "model")
     :provider (g "provider")}))

(defn- residents []
  (let [{:keys [jobs why]} (read-hermes-jobs)]
    {:runtime "hermes"
     :observed_at (.toISOString (js/Date.))
     :why why
     :jobs (if why [] (mapv resident-row jobs))}))

(defn- document []
  (let [installed (plist-names)
        names (sort (into (ledger-names) installed))]
    {:schema "cloud.itonami.bots-status.v1"
     :updated_at (.toISOString (js/Date.))
     :source (.hostname os)
     :loops (mapv #(row % installed) names)
     :residents (residents)}))

;; ---------------------------------------------------------------- publish

(defn- put!
  "curl で PUT。token は argv に出さず header ファイル（mode 600）経由で渡す。
  返り値 {:status int|nil :body str}。"
  [token json]
  (let [hdr (str gftd "/.bots-status-hdr")]
    (try
      (.writeFileSync fs hdr (str "Authorization: Bearer " token "\n") #js {:mode 0600})
      (let [r (.spawnSync cp "curl"
                          (clj->js ["-sS" "-X" "PUT" endpoint
                                    "-H" (str "@" hdr)
                                    "-H" "Content-Type: application/json"
                                    "--data-binary" "@-"
                                    "-o" "-" "-w" "\n%{http_code}"])
                          #js {:encoding "utf8" :input json :timeout 30000})
            out (str (aget r "stdout"))
            err (str (aget r "stderr"))
            lines (str/split-lines out)
            code (js/parseInt (or (peek lines) "") 10)
            body (str/join "\n" (butlast lines))]
        (if (js/Number.isFinite code)
          {:status code :body (if (str/blank? body) (str/trim err) body)}
          {:status nil :body (str/trim (str out "\n" err))}))
      (finally (try (.unlinkSync fs hdr) (catch :default _ nil))))))

;; ---------------------------------------------------------------- self-test
;;
;; Both directions, or it is theatre. The reader must produce jobs from a
;; well-formed file AND a named reason from each way it can fail — a reader
;; that returns `{:jobs []}` on every error reports "no residents" for a
;; broken read, which is the one answer this whole block exists to prevent.

(defn- self-test! []
  (let [tmp (str gftd "/.loop-status-self-test")
        write! (fn [body]
                 (.mkdirSync fs (str tmp "/cron") #js {:recursive true})
                 (.writeFileSync fs (str tmp "/cron/jobs.json") body))]
    (try
      (write! (js/JSON.stringify
               (clj->js {:jobs [{:name "a-scout" :enabled true
                                 :schedule_display "0 3 * * *"
                                 :last_run_at "2026-08-30T03:00:00Z"
                                 :next_run_at "2026-08-31T03:00:00Z"
                                 :last_status "ok" :failure_streak 0
                                 :model "m" :provider "p" :script "e.py"
                                 :workdir "/Users/someone/.gftd/worktrees/x"
                                 :last_error "Script exited with code 1\nstdout: boom"
                                 :prompt "SECRET INSTRUCTIONS"}]})))
      (let [good (read-hermes-jobs tmp)
            row (resident-row (first (:jobs good)))
            emitted (pr-str row)
            _ (write! "{not json")
            broken (read-hermes-jobs tmp)
            _ (write! "{\"jobs\": \"not-an-array\"}")
            shaped (read-hermes-jobs tmp)
            _ (.rmSync fs (str tmp "/cron/jobs.json") #js {:force true})
            missing (read-hermes-jobs tmp)
            absent (read-hermes-jobs (str tmp "-no-such-home"))
            checks
            [["a well-formed file yields jobs"     (= 1 (count (:jobs good)))]
             ["…and no why"                        (nil? (:why good))]
             ["invalid JSON is named, not empty"   (= "jobs-file-invalid-json" (:why broken))]
             ["a wrong shape is named, not empty"  (= "jobs-file-shape-unexpected" (:why shaped))]
             ["an absent file is named, not empty" (= "jobs-file-absent" (:why missing))]
             ["an absent home is named separately" (= "hermes-home-absent" (:why absent))]
             ["a failed read carries no jobs key"  (nil? (:jobs broken))]
             ["the row keeps the name"             (= "a-scout" (:name row))]
             ["the row drops workdir"              (not (str/includes? emitted "/Users/"))]
             ["the row drops the prompt"           (not (str/includes? emitted "SECRET"))]
             ["the row drops the error body"       (not (str/includes? emitted "boom"))]
             ["…but keeps that there was one"      (true? (:has_error row))]]]
        (doseq [[label ok?] checks]
          (println (if ok? "  ok  " "  FAIL") label))
        (if (every? second checks)
          (do (println "self-test OK — a failed read is named, never an empty resident list") 0)
          (do (println "self-test FAILED — a check that cannot fail is theatre") 1)))
      (finally
        (try (.rmSync fs tmp #js {:recursive true :force true})
             (catch :default _ nil))))))

(defn -main []
  (when self-test?
    (js/process.exit (self-test!)))
  (let [doc (document)
        json (js/JSON.stringify (clj->js doc) nil 1)]
    (cond
      dry-run?
      (do (println json)
          (js/process.exit 0))

      (not (exists? token-file))
      ;; 発行できなかった。**発行済みとは別の事実** —— exit も 0/1 でない値。
      (do (prn {:outcome :not-measured :why :token-missing :token-file token-file})
          (js/process.exit 2))

      :else
      (let [token (str/trim (str (read-file token-file)))]
        (if (str/blank? token)
          (do (prn {:outcome :not-measured :why :token-empty :token-file token-file})
              (js/process.exit 2))
          (let [{:keys [status body]} (put! token json)]
            (if (= 204 status)
              (do (prn {:outcome :published
                        :loops (count (:loops doc))
                        :residents (if-let [w (:why (:residents doc))]
                                     [:unmeasured w]
                                     (count (:jobs (:residents doc))))})
                  (js/process.exit 0))
              ;; 応答 body を捨てない（CLAUDE.md 6 問の 3 番目）。
              (do (prn {:outcome :publish-failed :status status
                        :body (let [b (str body)] (subs b 0 (min 500 (count b))))})
                  (js/process.exit 1)))))))))

(-main)
