#!/usr/bin/env nbb
;; adr-ledger-land.cljs — ローカルで append した adr-ledger の行を main へ着地させる。
;; **着地時に :event/seq を採り直す**（並行 landing による seq 衝突の構造的な対策）。
;;
;; なぜ必要か: `scripts/adr-ledger-append.cljs` は **ローカルの写し**から next-seq を
;; 決める。複数セッションが同時に append → それぞれ別に landing すると、同じ seq が
;; 2 行できる（実測: 2026-07-25 に seq 100 が重複。手で振り直して解消した）。
;; seq の単調性は「1 つのファイルへの逐次 append」でしか保証されないので、
;; **main の内容から max+1 を採り直してから追記する**のが正しい。
;;
;; 使い方:
;;   nbb scripts/adr-ledger-land.cljs --adr 2607259000            ;; その ADR のローカル未着地行を landing
;;   nbb scripts/adr-ledger-land.cljs --adr 2607259000 --dry-run
;;
;; * main に既にある行は触らない（内容一致で判定）。
;; * 追記のみ。既存行の書き換え・削除はしない。
;; * 409（他セッションと衝突）なら main を読み直して seq を再採番してリトライ。
(ns adr-ledger-land
  (:require ["node:child_process" :as cp]
            ["node:fs" :as fs]
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

(def repo (or (:repo opts) "com-junkawasaki/root"))
(def branch (or (:branch opts) "main"))
(def ledger-path "90-docs/adr-ledger/adr-ledger.edn")
(def local-path (or (:local opts) (str (or (.-FLEET_ROOT js/process.env) (js/process.cwd)) "/" ledger-path)))

(defn sh [cmd args & [o]]
  (try {:exit 0 :out (str (cp/execFileSync cmd (clj->js (vec args))
                                          (clj->js (merge {:encoding "utf8" :maxBuffer 268435456
                                                           :timeout 300000} o))))}
       (catch :default e {:exit (or (.-status e) 1)
                          :out (str (some-> (.-stdout e) str) (some-> (.-stderr e) str) e)})))

(defn gh! [& a]
  (let [{:keys [exit out]} (sh "gh" a)]
    (when-not (zero? exit) (println "gh failed:" (str/trim out)) (js/process.exit 1))
    (str/trim out)))

(defn fetch-main []
  (gh! "api" "-H" "Accept: application/vnd.github.raw"
       (str "repos/" repo "/contents/" ledger-path "?ref=" branch)))

(defn blob-sha []
  (gh! "api" (str "repos/" repo "/contents/90-docs/adr-ledger?ref=" branch)
       "--jq" ".[] | select(.name==\"adr-ledger.edn\") | .sha"))

(defn max-seq [text]
  (apply max 0 (keep #(some-> (re-find #":event/seq (\d+)" %) second js/parseInt)
                     (str/split-lines text))))

(defn renumber [line n]
  (str/replace-first line #":event/seq \d+" (str ":event/seq " n)))

(let [adr (:adr opts)
      _ (when-not adr (println "usage: --adr <adr-id> [--dry-run]") (js/process.exit 2))
      local (str/split-lines (str (fs/readFileSync local-path "utf8")))
      main-text (fetch-main)
      main-lines (set (str/split-lines main-text))
      ;; 「その ADR について」かつ「main に無い」行だけを対象にする（他セッションの
      ;; 未着地行を巻き込まない）。seq は着地時に振り直すので、seq 部分を除いた本体で
      ;; 既着地判定を行う。
      strip (fn [l] (str/replace l #":event/seq \d+" ""))
      landed (set (map strip main-lines))
      pending (->> local
                   (remove str/blank?)
                   (filter #(str/includes? % (str ":adr/id \"" adr "\"")))
                   (remove #(contains? landed (strip %)))
                   vec)]
  (println "pending for" adr ":" (count pending) "line(s); main max seq =" (max-seq main-text))
  (cond
    (empty? pending) (println "nothing to land")
    (:dry-run opts)
    (doseq [[i l] (map-indexed vector pending)]
      (println "would land as seq" (+ (max-seq main-text) 1 i) ":" (subs l 0 (min 120 (count l)))))
    :else
    (loop [attempt 1]
      (let [cur (fetch-main)
            base (max-seq cur)
            renumbered (map-indexed (fn [i l] (renumber l (+ base 1 i))) pending)
            content (str (if (str/ends-with? cur "\n") cur (str cur "\n"))
                         (str/join "\n" renumbered) "\n")
            br (str "agent/ledger-land-" (subs (str (.getTime (js/Date.))) 4) "-" attempt)
            base-sha (gh! "api" (str "repos/" repo "/git/refs/heads/" branch) "--jq" ".object.sha")
            _ (sh "gh" ["api" "--method" "POST" (str "repos/" repo "/git/refs")
                        "-f" (str "ref=refs/heads/" br) "-f" (str "sha=" base-sha)])
            body (js/JSON.stringify (clj->js {:message (str "adr-ledger: land " (count pending)
                                                           " event(s) for ADR-" adr
                                                           " (seq re-derived at landing)")
                                              :content (.toString (js/Buffer.from content "utf8") "base64")
                                              :branch br :sha (blob-sha)}))
            put (sh "gh" ["api" "--method" "PUT" (str "repos/" repo "/contents/" ledger-path) "--input" "-"]
                    {:input body})]
        (if-not (zero? (:exit put))
          (do (sh "gh" ["api" "--method" "DELETE" (str "repos/" repo "/git/refs/heads/" br)])
              (if (< attempt 4)
                (do (println "retry" attempt "(conflict — re-deriving seq)") (recur (inc attempt)))
                (do (println "failed:" (:out put)) (js/process.exit 1))))
          (let [m (sh "gh" ["api" (str "repos/" repo "/merges") "-f" (str "base=" branch)
                            "-f" (str "head=" br)
                            "-f" (str "commit_message=Merge " br ": adr-ledger events for ADR-" adr)])]
            (sh "gh" ["api" "--method" "DELETE" (str "repos/" repo "/git/refs/heads/" br)])
            (if (zero? (:exit m))
              (println "landed as seq" (str (inc base) "…" (+ base (count pending))))
              (if (< attempt 4)
                (do (println "merge conflict — retry" attempt) (recur (inc attempt)))
                (do (println "merge failed:" (:out m)) (js/process.exit 1))))))))))
