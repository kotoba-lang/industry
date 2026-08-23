#!/usr/bin/env nbb
(ns checkout-holds
  "checkout が「何を持っているか」を 5 項目で答える。撤去してよいかの判定に使う。

  2026-08-23 の事故対策。それまでの判定は branch / untracked / dirty / stash の
  4 項目で、**git-annex の content object を数えていなかった**。結果、7.7 GB の
  実データを抱えた DataLad checkout 6 件が「空」と判定され、`rm -rf` で壊れた
  （annex object は read-only なので rm が途中で止まり、.git のメタデータだけが
  消えて object が孤立した）。

  もう 1 つの穴も塞いである: `.git` が無い checkout に対して `git` を呼ぶと、
  git は**親ディレクトリを辿って superproject を答える**。壊れた repo が
  「HEAD が読める＝生きている」ように見えた。ここでは `.git` の不在を
  NO_GIT / exit 2 で返し、EMPTY(0) とも HOLDS(1) とも別の値にする。

  usage: nbb scripts/checkout-holds.cljs <path> [<path> ...]
  exit  0 = 全部 EMPTY / 1 = HOLDS が在る / 2 = 判定できないものが在る"
  (:require ["node:child_process" :as cp]
            ["node:fs" :as fs]
            ["node:path" :as path]))

(defn- sh [args opts]
  (try (-> (cp/execFileSync "git" (clj->js args)
             (clj->js (merge {:encoding "utf8" :stdio ["ignore" "pipe" "ignore"]} opts)))
           str)
       (catch :default _ "")))

(defn- lines [s] (remove empty? (.split (or s "") "\n")))

(defn- count-annex-objects
  "annex object を数える。走査した根の不在と 0 件を区別する。"
  [p]
  (let [root (path/join p ".git" "annex" "objects")]
    (if-not (fs/existsSync root)
      0
      (loop [stack [root] n 0]
        (if-let [d (peek stack)]
          (let [ents (try (fs/readdirSync d #js {:withFileTypes true}) (catch :default _ #js []))]
            (recur (into (pop stack)
                         (keep #(when (.isDirectory %) (path/join d (.-name %))) ents))
                   (+ n (count (filter #(not (.isDirectory %)) ents)))))
          n)))))

(defn holds [p]
  (cond
    (not (fs/existsSync p))                      {:path p :verdict :gone}
    (not (fs/existsSync (path/join p ".git")))   {:path p :verdict :no-git}
    :else
    (let [br (->> (sh ["-C" p "for-each-ref" "--format=%(refname:short)" "refs/heads"] {})
                  lines
                  (remove #{"main" "master" "HEAD" "git-annex"})
                  count)
          st (lines (sh ["-C" p "status" "--porcelain"] {}))
          m  {:branches br
              :untracked (count (filter #(.startsWith % "??") st))
              :dirty     (count (filter #(re-find #"^( M| D|M |D |A |AM|R )" %) st))
              :stashes   (count (lines (sh ["-C" p "stash" "list"] {})))
              :annex     (count-annex-objects p)}]
      (assoc m :path p
               :verdict (if (zero? (apply + (vals m))) :empty :holds)))))

(let [args (vec *command-line-args*)]
  (if (empty? args)
    (do (println "usage: nbb scripts/checkout-holds.cljs <path> [<path> ...]") (js/process.exit 64))
    (let [rs (map holds args)]
      (doseq [{:keys [path verdict branches untracked dirty stashes annex]} rs]
        (println (str (name verdict) "\t" path
                      (when (= verdict :holds)
                        (str "\tbranches=" branches " untracked=" untracked
                             " dirty=" dirty " stashes=" stashes " annex=" annex)))))
      (println (str "SCANNED\t" (count rs)))
      (js/process.exit (cond (some #(= :no-git (:verdict %)) rs) 2
                             (some #(= :holds (:verdict %)) rs)  1
                             :else 0)))))
