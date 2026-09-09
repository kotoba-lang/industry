#!/usr/bin/env nbb
;; scripts/repo-bots/drain-loop.cljs — repo 常駐 bot の findings を 1 件ずつ
;; 塞ぎにいくローカル Claude loop の入口。
;; LaunchAgent `cloud.itonami.bot.repo-bot-drain` が一定間隔でこれを起こす。
;;
;; 2 段構え（姉妹 loop `adr-inventory-loop` と同じ）:
;;   1. `scripts/repo-bots/tick.cljs` が波を測る（決定論。モデルは居ない）
;;   2. 候補があるときだけ `claude -p "/repo-bot-drain"` を起こす
;;
;; 1 反復 = 1 finding。手順の正本は skill `repo-bot-drain`。ここには書かない。
;;
;; 不変条件:
;;   - 候補が 0 ならモデルを起こさない。**無い仕事にモデルを起こさない。**
;;   - **まだ誰も測っていない**（:not-measured）ときもモデルを起こさない ——
;;     それは「候補 0 件」とは別の事実で、混ぜると『きれいだから何もしない』の
;;     顔をして永久に止まる。代わりに波を 1 つ回して、この周は終わる。
;;   - 共有 checkout を書き換えない。git merge --ff-only はしない。
;;   - ledger は追記のみ。**成否は次周の tick が測る**（自分で成功と書かない）。
;;   - exit 0 常に。
;;
;; usage:
;;   nbb scripts/repo-bots/drain-loop.cljs
;;   nbb scripts/repo-bots/drain-loop.cljs --dry-run

(require '[clojure.edn :as edn]
         '[clojure.string :as str])

(def fs (js/require "node:fs"))
(def os (js/require "node:os"))
(def cp (js/require "node:child_process"))

(def home (.homedir os))
(def root (or (aget (.-env js/process) "COM_JUNKAWASAKI_ROOT")
              (str home "/github/com-junkawasaki")))
(def ledger-file (str home "/.itonami/repo-bots/drain.ledger.edn"))
(def dry-run? (boolean (some #{"--dry-run"} *command-line-args*)))

(defn log! [& xs]
  (println (str (.toISOString (js/Date.)) " " (str/join " " (map str xs)))))

(defn- sh [cmd args opts]
  (try
    (let [r (.spawnSync cp cmd (clj->js args)
                        (clj->js (merge {:encoding "utf8" :cwd root} opts)))]
      {:code (aget r "status") :out (str (aget r "stdout")) :err (str (aget r "stderr"))})
    (catch :default e {:code nil :out "" :err (str e)})))

(defn- append-ledger! [m]
  (try (.mkdirSync fs (str home "/.itonami/repo-bots") #js {:recursive true})
       (.appendFileSync fs ledger-file (str (pr-str m) "\n"))
       (catch :default e (log! "ledger 追記に失敗:" (str e)))))

(defn -main []
  (let [started (.toISOString (js/Date.))]
    ;; 分岐を作る前に local を remote に同期する（CLAUDE.md）。ただしこの loop は
    ;; 判定するだけで、破壊的な同期はしない。乖離していたら何もしない。
    (sh "git" ["fetch" "origin" "--quiet"] {})
    (let [{:keys [code]} (sh "git" ["merge-base" "--is-ancestor" "HEAD" "origin/main"] {})]
      (when (not= 0 code)
        (log! "この checkout は origin/main から分岐している。この周は何もしない。")
        (append-ledger! {:at started :outcome :skipped :why :diverged-from-main})
        (js/process.exit 0)))

    ;; 波を 1 つ測る。ここが「bot が動く」の実体で、モデルは関与しない。
    (let [{:keys [out]} (sh "nbb" ["scripts/repo-bots/tick.cljs" "--wave" "200"] {:timeout 900000})]
      (println (str/join "\n" (take-last 12 (str/split-lines (str out))))))

    ;; 無人の周回なので --next-unattended。:landed（他人の未 commit の作業）は
    ;; 人が見ているときだけ触る —— 失われうる唯一の床であることと、無人で触って
    ;; よいことは別。件数は :held-for-a-human として返ってくるので黙らない。
    (let [{:keys [out]} (sh "nbb" ["scripts/repo-bots/tick.cljs" "--next-unattended"] {})
          next (try (edn/read-string (str/trim (str out))) (catch :default _ nil))]
      (cond
        (nil? next)
        (do (log! "--next が読めない。この周は何もしない")
            (append-ledger! {:at started :outcome :skipped :why :next-unreadable}))

        (= :not-measured (:outcome next))
        ;; 測れていないことを「きれい」と読まない。波は上で回したので、次周には
        ;; state が在る。ここでモデルを起こしても直す対象が無い。
        (do (log! "まだ測定が無い。波を回した。この周はモデルを起こさない")
            (append-ledger! {:at started :outcome :skipped :why :not-measured}))

        (= :no-candidates (:outcome next))
        (do (log! "無人で触ってよい床割れは 0 件（測定済み" (:ticked next) "体"
                  (if-let [h (:held-for-a-human next)]
                    (str "、:landed " h " 件は人待ち") "")
                  "）。無い仕事にモデルを起こさない")
            (append-ledger! {:at started :outcome :skipped :why :no-candidates
                             :ticked (:ticked next)
                             :held-for-a-human (:held-for-a-human next)}))

        dry-run?
        (do (log! "--dry-run: 次の候補は" (:bot next) "/" (name (:floor next)) "—" (:detail next)
                  (if-let [h (:held-for-a-human next)] (str "（:landed " h " 件は人待ち）") ""))
            (append-ledger! {:at started :outcome :dry-run :next next}))

        :else
        (let [{:keys [code out err]}
              (sh "claude" ["-p" "/repo-bot-drain" "--dangerously-skip-permissions"]
                  {:timeout 3600000})]
          (println out)
          (when (seq (str/trim (or err ""))) (log! "stderr:" (str/trim err)))
          (log! "claude 終了コード:" code)
          (append-ledger! {:at started
                           :finished (.toISOString (js/Date.))
                           :outcome (if (= 0 code) :ran :failed)
                           :exit code
                           :next-was next
                           :note "成否は次周の tick が測る"}))))
    (js/process.exit 0)))

(-main)
