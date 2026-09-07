#!/usr/bin/env nbb
;; docs-reality-tick.cljs — docs-audit bot の monitor。1 tick 分の測定だけを行い、
;; 判断はしない。ADR-2609072600。
;;
;; 出力がこの bot の知るすべてになる。したがって:
;;   - **測れなかったことを、測って問題が無かったことと同じ形で書かない。**
;;     同期できなかった / 検査が答えを拒否した場合は SYNC= / *-REFUSED を出す。
;;   - 件数を必ず出す（boolean は 1 件の退行と壊れた測定を区別できない）。
;;
;;   nbb scripts/docs-reality-tick.cljs [--root <dir>]

(require '[clojure.string :as str])

(def cp (js/require "node:child_process"))
(def fsm (js/require "node:fs"))

(def argv (vec *command-line-args*))
(defn- flag [n d] (or (second (drop-while #(not= % n) argv)) d))
(def root (flag "--root" (or (js/process.cwd) ".")))

(defn- sh [cmd args]
  (let [r (.spawnSync cp cmd (clj->js args)
                      #js {:cwd root :encoding "utf8" :maxBuffer 268435456})]
    {:code (if (nil? (.-status r)) 2 (.-status r))
     :out (str (.-stdout r)) :err (str (.-errorno r))
     :stderr (str (.-stderr r))}))

(println (str "=== docs-reality-tick " (.toISOString (js/Date.)) " ==="))
(println (str "root=" root))

;; ---------- 1. origin/main に合わせる（破壊的なことはしない） ----------
;; 遅れた checkout を正本として読むのは、この workspace が ADR-2608135200 で
;; 記録した実事故そのもの。同期できなかったときは、そう言う。
(let [_ (sh "git" ["fetch" "origin" "main" "--quiet"])
      behind (str/trim (:out (sh "git" ["rev-list" "--count" "HEAD..origin/main"])))
      ff (sh "git" ["merge" "--ff-only" "origin/main"])]
  (println (str "SYNC\tbehind-before=" (if (str/blank? behind) "unknown" behind)
                " ff-exit=" (:code ff)
                (when-not (zero? (:code ff))
                  (str " ff-blocked=" (str/replace (str/trim (str (:stderr ff))) #"\s+" " ")))))
  (println (str "HEAD\t" (str/trim (:out (sh "git" ["rev-parse" "--short" "HEAD"])))
                " behind-after=" (str/trim (:out (sh "git" ["rev-list" "--count" "HEAD..origin/main"]))))))

;; ---------- 2. agent 指示 ↔ ADR/tree の drift（決定論） ----------
(let [r (sh "nbb" ["scripts/verify-doc-reality.cljs" root "--findings"])]
  (println "\n--- verify-doc-reality ---")
  (println (str/trim (:out r)))
  (println (str "DOC-REALITY\texit=" (:code r)
                (case (:code r) 0 " (findings 0)" 1 " (findings あり)"
                                " (REFUSED — 測れなかった。この tick の drift 判定は使えない)"))))

;; ---------- 3. ADR 棚卸しの残（継続作業の backlog） ----------
(let [r (sh "nbb" ["--classpath" ".:scripts/nbb_compat" "scripts/adr-inventory.cljs"])
      head (take 8 (str/split-lines (:out r)))]
  (println "\n--- adr-inventory (backlog; 判断は skill adr-inventory が 1 件ずつ) ---")
  (if (zero? (:code r))
    (println (str/join "\n" head))
    (println (str "ADR-INVENTORY-REFUSED\texit=" (:code r) " — backlog 件数は未測定")))
  (println (str "ADR-INVENTORY\texit=" (:code r))))

;; ---------- 4. AGENTS.md 生成不変条件 ----------
(let [r (sh "nbb" ["scripts/gen-agents-md.cljs" "--check"])]
  (println (str "\nAGENTS-MD\texit=" (:code r)
                (case (:code r) 0 " (CLAUDE.md と一致)" 1 " (STALE — 再生成が要る)"
                                " (REFUSED — 置換表が source と食い違った)"))))

(println "\n=== end tick ===")
