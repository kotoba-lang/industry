#!/usr/bin/env nbb
;; check-foundation-deps.cljs — kotobase/kotoba foundation の deps.edn pin drift を
;; サーバ側(GitHub API)で検出する。
;;
;; 問題: foundation は layered な複数 repo(multiformats → ipld → prolly-tree →
;; quad-store → kqe → kotobase-engine …)で、各 repo が下層を `:git/sha` で pin する。
;; 下層の fix(例: prolly-tree の scan-prefix pruning ef43a9d)は、上層が pin を
;; bump するまで伝わらない。この bump が漏れると **静かな drift** になる
;; (実測: quad-store が prolly-tree を pruning 前の 8da6d1e に据え置き → kqe /
;; kotobase-engine すべてが pruning 無しの prolly-tree を引いていた。keyed read が
;; O(path) でなく全ツリー walk になっていた。ADR-2607032300)。
;;
;; このスクリプトは各 foundation repo の deps.edn を origin/main から読み、
;; `io.github.kotoba-lang/<dep> {:git/sha S}` の各 pin を、その dep の origin/main
;; HEAD H と `compare S...H` で比較する:
;;   identical            → OK(pin == HEAD)
;;   H が S の ahead      → DRIFT(pin が behind_by N。下層 fix が届いていない可能性)
;;   diverged / behind    → BROKEN(pin が HEAD 系統外 / HEAD より先。要調査)
;;
;; 判定はすべて GitHub API(サーバ側 full 履歴)。ローカルの ancestry 判定だけに
;; 頼らない(CLAUDE.md「マージ / ancestry 判定」節、verify-west-pins.cljs と同方針)。
;;
;; 使い方:
;;   nbb scripts/check-foundation-deps.cljs              ; 既定の foundation 集合を検査
;;   nbb scripts/check-foundation-deps.cljs r1 r2 ...     ; repo を明示指定
;;   ORG=kotoba-lang で org 上書き(既定 kotoba-lang)
;;
;; exit 0: drift なし(全 pin == dep HEAD)
;; exit 1: drift / broken pin あり(bump 順を併記)
;; exit 2: gh が無い / 認証が無い

(require '[scripts.nbb-compat :refer [slurp spit file-seq format]]
         '[clojure.edn :as edn]
         '[clojure.string :as str]
         '[cheshire.core :as json]
         '[babashka.process :refer [shell]])

(def org (or (scripts.nbb-compat/getenv "ORG") "kotoba-lang"))

;; 既定の foundation 集合(bottom-up 順)。deps は各 deps.edn から DISCOVER するので
;; ここは「検査対象の repo」列挙のみ(DAG はハードコードしない)。
(def default-repos
  ["multiformats" "dag-cbor" "ipld" "prolly-tree" "commit-dag"
   "quad-store" "kqe" "datom" "kotobase-engine" "kotobase-client"])

(defn gh-json [& args]
  (let [{:keys [out exit]} (apply shell {:out :string :err :string :continue true} "gh" "api" args)]
    (when (zero? exit)
      (try (json/parse-string out true) (catch :default _ nil)))))

(defn gh-raw [& args]
  (let [{:keys [out exit]} (apply shell {:out :string :err :string :continue true} "gh" "api" args)]
    (when (zero? exit) out)))

(defn have-gh? []
  (let [{:keys [exit]} (shell {:out :string :err :string :continue true} "gh" "auth" "status")]
    (zero? exit)))

(defn head-sha [repo]
  (some-> (gh-raw (str "repos/" org "/" repo "/commits/main") "--jq" ".sha") str/trim not-empty))

(defn deps-edn [repo]
  ;; deps.edn を origin/main から取得して EDN パース。無ければ nil。
  (when-let [b64 (some-> (gh-raw (str "repos/" org "/" repo "/contents/deps.edn?ref=main") "--jq" ".content")
                         str/trim not-empty)]
    (try (edn/read-string (.toString (.from js/Buffer (str/replace b64 #"\s" "") "base64") "utf8"))
         (catch :default _ nil))))

(defn kotoba-deps
  "deps.edn map から io.github.<org>/<name> {:git/sha S} を [name S] で抜く。"
  [deps repo]
  (let [prefix (str "io.github." org "/")]
    (for [[k v] (:deps deps)
          :let [n (name k)]
          :when (str/starts-with? (str (namespace k) "/" n)
                                  (str "io.github." org "/"))
          :let [sha (:git/sha v)]
          :when sha]
      [n sha])))

(defn compare-status
  "pin S を dep D の HEAD H と比較 → {:status … :ahead-by … :behind-by …}。"
  [dep s h]
  (if (= s h)
    {:status "identical"}
    (or (gh-json (str "repos/" org "/" dep "/compare/" s "..." h)
                 "--jq" "{status: .status, ahead: .ahead_by, behind: .behind_by}")
        {:status "unknown"})))

(defn -main [& args]
  (when-not (have-gh?)
    (println "gh 未認証 — foundation deps 検査を skip") (scripts.nbb-compat/exit 2))
  (let [repos (if (seq args) (vec args) default-repos)
        head-cache (into {} (for [r repos] [r (head-sha r)]))
        drifts (atom [])]
    (println (str "== foundation deps pin check (org=" org ", " (count repos) " repos) =="))
    (doseq [repo repos]
      (if-let [deps (deps-edn repo)]
        (doseq [[dep s] (kotoba-deps deps repo)]
          (let [h (get head-cache dep (head-sha dep))
                cs (if h (compare-status dep s h) {:status "no-head"})
                st (:status cs)]
            (cond
              (= st "identical")
              (println (format "  ✓ %-18s → %-16s %s (== HEAD)" repo dep (subs s 0 12)))
              (= st "ahead")   ; HEAD ahead of pin = pin is (ahead_by) commits behind = drift
              (do (swap! drifts conj {:repo repo :dep dep :pin s :head h :behind (:ahead cs)})
                  (println (format "  ✗ %-18s → %-16s %s  DRIFT: pin is %s commit(s) behind HEAD %s"
                                   repo dep (subs s 0 12) (:ahead cs) (subs h 0 12))))
              :else
              (do (swap! drifts conj {:repo repo :dep dep :pin s :head h :status st})
                  (println (format "  ! %-18s → %-16s %s  %s (HEAD %s)"
                                   repo dep (subs s 0 12) st (some-> h (subs 0 12))))))))
        (println (format "  · %-18s (deps.edn 無し / 解析不可 — skip)" repo))))
    (if (seq @drifts)
      (do (println (str "\nDRIFT " (count @drifts) " 件。bottom-up に bump せよ(下層から):"))
          (doseq [{:keys [repo dep head]} @drifts]
            (println (format "  %s: %s pin → %s" repo dep (some-> head (subs 0 12)))))
          (scripts.nbb-compat/exit 1))
      (do (println "\n✓ drift なし — foundation の pin はすべて dep の HEAD と一致")
          (scripts.nbb-compat/exit 0)))))

(apply -main *command-line-args*)
