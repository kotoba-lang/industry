#!/usr/bin/env nbb
;; scripts/loop-status-publish.cljs — この workstation の com.gftd.* loop 群の
;; 現在地を itonami.cloud の公開 status page へ発行する。**決定論。モデルは
;; 一切呼ばない**（claude 起動も skip-permissions フラグも無い、純粋な publisher）。
;; LaunchAgent com.gftd.loop-status-publish が 30 分ごとに起こす。
;;
;; ## 何を発行するか
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
;;   1. ~/.gftd/<name>/ledger.edn          （isekai-game-dev / kami-lib-update 形）
;;   2. ~/.gftd/<name>.ledger.edn          （fleet-refactor-wave / svelte-cljs-wave 形）
;;   3. repo-bot-drain だけ ~/.gftd/repo-bots/drain.ledger.edn
;;
;; ledger の最終行が EDN として読めなければ outcome null / why "ledger-unreadable"
;; —— **読めなかったものを ok に畳まない**（ADR-2608136000）。
;;
;; ## 発行先
;;
;; PUT https://itonami.cloud/api/bots-status（KV-backed、対の公開 page は
;; itonami.cloud/bots/）。bearer token は ~/.gftd/bots-status-token（mode 600、
;; 値をログにも argv にも出さない — header はファイル経由で curl に渡す）。
;;
;; ## 出力と exit
;;
;;   --dry-run       document(JSON) を印字して PUT しない。exit 0
;;   token が無い    {:outcome :not-measured :why :token-missing}、exit 2
;;                   —— 発行できなかったことを成功に見せない
;;   PUT が 204 以外 {:outcome :publish-failed :status … :body …}、exit 1
;;                   —— **応答 body を捨てない**（原因はたいてい body に書いてある）
;;   PUT が 204      {:outcome :published :loops N}、exit 0
;;
;; usage:
;;   nbb --classpath ".:scripts/nbb_compat" scripts/loop-status-publish.cljs [--dry-run]

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

(defn- document []
  (let [installed (plist-names)
        names (sort (into (ledger-names) installed))]
    {:schema "cloud.itonami.bots-status.v1"
     :updated_at (.toISOString (js/Date.))
     :source (.hostname os)
     :loops (mapv #(row % installed) names)}))

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

(defn -main []
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
              (do (prn {:outcome :published :loops (count (:loops doc))})
                  (js/process.exit 0))
              ;; 応答 body を捨てない（CLAUDE.md 6 問の 3 番目）。
              (do (prn {:outcome :publish-failed :status status
                        :body (let [b (str body)] (subs b 0 (min 500 (count b))))})
                  (js/process.exit 1)))))))))

(-main)
