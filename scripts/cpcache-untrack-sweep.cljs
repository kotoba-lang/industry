#!/usr/bin/env nbb
;; cpcache-untrack-sweep.cljs
;; `.cpcache/`（Clojure の classpath キャッシュ）を child repo の tracked content
;; から外し、同時に `.gitignore` へ `.cpcache/` を足す。
;;
;; なぜ必要か（2026-08-14 cleanup で判明）:
;;   `.cpcache/` は絶対パスを含むマシン固有のビルド生成物で、commit されるべき
;;   ものではない。実測 733 repo で tracked かつ ignore されておらず、ローカルで
;;   消されているため `git status` には ` D` 7 件として出る。cleanup-land は
;;   「削除は main に適用しない」（stale な working tree からの削除は他人の作業を
;;   消しうる）ので、この 733 repo は **survey のたびに永久に UNLANDED として
;;   数え直される**。実測: cleanup.cljs の UNLANDED 1,011 repo のうち 695 が
;;   これ 1 件だけを理由に挙がっていた（69%）。
;;
;;   .gitignore を同時に入れないと、untrack した瞬間に `??` untracked になり、
;;   次の cleanup-land が :additive として **commit し直す**。untrack と ignore は
;;   1 commit で行う必要がある。
;;
;; なぜ GraphQL createCommitOnBranch か:
;;   REST の blob→tree→commit→ref は 1 repo あたり 6 往復（733 repo で 4,398）で、
;;   REST の 5,000/hr にほぼ収まらない。createCommitOnBranch は削除と追加を
;;   **1 mutation** で行い、GitHub 側が署名する。query と合わせて 2 往復
;;   （GraphQL は REST と別枠）。
;;
;; 安全性:
;;   - archived repo は skip（owner 判断 2026-08-14、cleanup 対象外）。判定不能は
;;     skip であって「archived でない」ではない（ADR-2608136000）。
;;   - expectedHeadOid を渡すので、走査中に main が動いた repo は **失敗して報告**
;;     される（黙って上書きしない）。
;;   - `.gitignore` は remote の現物を読んでから追記する。ローカルの写しは使わない
;;     （west checkout は pin に居るので remote より古いことがあり、古い .gitignore
;;     で上書きすると他人の変更を巻き戻す）。
;;   - 既に `.cpcache` を ignore 済みで tracked も無い repo は no-op として skip。
;;   - working tree には一切触らない。
;;
;; usage:
;;   nbb scripts/cpcache-untrack-sweep.cljs                 ; dry-run
;;   nbb scripts/cpcache-untrack-sweep.cljs --execute
;;   nbb scripts/cpcache-untrack-sweep.cljs --execute --max 5
;;   nbb scripts/cpcache-untrack-sweep.cljs --execute --names adnet,com-bafin

(require '[scripts.nbb-compat :as io :refer [sh format]]
         '[clojure.string :as str])

(def argv (vec (drop 2 (js->clj (aget js/process "argv")))))
(defn opt [k] (let [i (.indexOf argv k)] (when (>= i 0) (get argv (inc i)))))
(def execute? (some #{"--execute"} argv))
(def max-n (some-> (opt "--max") js/parseInt))
(def only-names (some-> (opt "--names") (str/split #",") set))

(def root (-> (sh "git" "rev-parse" "--show-toplevel") :out str/trim))

(defn sh! [& args] (apply sh args))

(defn gh-json
  "gh api を叩いて JSON を返す。失敗は nil（呼び手が『判定できなかった』として扱う）。"
  [& args]
  (let [{:keys [exit out]} (apply sh! "gh" args)]
    (when (and (zero? exit) (seq (str/trim out)))
      (try (js->clj (js/JSON.parse out) :keywordize-keys true) (catch :default _ nil)))))

;; ---------------------------------------------------------------- discovery

(defn primary-remote [dir]
  (let [rs (->> (sh! "git" "-C" dir "remote") :out str/split-lines (remove str/blank?))]
    (or (some #{"origin"} rs) (first rs))))

(defn slug-of [dir]
  (when-let [r (primary-remote dir)]
    (let [url (-> (sh! "git" "-C" dir "remote" "get-url" r) :out str/trim)]
      (some-> (re-find #"github\.com[:/]([^/]+/[^/]+?)(?:\.git)?$" url) second))))

(defn candidates []
  (->> (:out (sh! "bash" "-c" (str "ls -d " root "/orgs/*/*/ 2>/dev/null")))
       str/split-lines
       (remove str/blank?)
       (map #(str/replace % #"/$" ""))
       (filter #(seq (str/trim (:out (sh! "git" "-C" % "ls-files" ".cpcache")))))
       (filter (fn [d] (if only-names
                         (some #(str/ends-with? d (str "/" %)) only-names)
                         true)))))

;; ---------------------------------------------------------------- graphql

(def q
  "query($owner:String!,$name:String!){
     repository(owner:$owner,name:$name){
       isArchived
       defaultBranchRef{ name target{ oid } }
       gi: object(expression:\"HEAD:.gitignore\"){ ... on Blob { text } }
       cp: object(expression:\"HEAD:.cpcache\"){ ... on Tree { entries { name type } } }
     }
   }")

(defn probe [slug]
  (let [[owner name] (str/split slug #"/")]
    (gh-json "api" "graphql" "-f" (str "query=" q)
             "-f" (str "owner=" owner) "-f" (str "name=" name))))

(defn b64 [s] (.toString (.from js/Buffer s "utf8") "base64"))

(defn commit! [slug branch head-oid deletions gitignore-text]
  (let [input {:branch {:repositoryNameWithOwner slug :branchName branch}
               :expectedHeadOid head-oid
               :message {:headline "cleanup: untrack .cpcache and ignore it"
                         :body (str ".cpcache/ is the Clojure classpath cache: a build artifact "
                                    "containing absolute machine-local paths. It was committed by "
                                    "mistake and is not gitignored, so every survey counts this repo "
                                    "as holding un-landed work.\n\n"
                                    "Untrack it and ignore it in one commit -- untracking alone would "
                                    "leave the files untracked-but-not-ignored, and the next cleanup "
                                    "pass would commit them straight back.")}
               :fileChanges {:additions [{:path ".gitignore" :contents (b64 gitignore-text)}]
                             :deletions (mapv (fn [p] {:path p}) deletions)}}
        mut "mutation($input:CreateCommitOnBranchInput!){createCommitOnBranch(input:$input){commit{oid}}}"
        tmp (str "/tmp/cpcache-input-" (rand-int 1e9) ".json")]
    (io/spit tmp (js/JSON.stringify (clj->js {:query mut :variables {:input input}})))
    (let [{:keys [exit out]} (sh! "gh" "api" "graphql" "--input" tmp)]
      (sh! "rm" "-f" tmp)
      (if (zero? exit)
        (let [r (try (js->clj (js/JSON.parse out) :keywordize-keys true) (catch :default _ nil))]
          (if-let [oid (get-in r [:data :createCommitOnBranch :commit :oid])]
            {:ok true :oid oid}
            {:ok false :err (or (some-> (:errors r) first :message) "unknown graphql error")}))
        {:ok false :err (str/trim (or out "gh failed"))}))))

;; ---------------------------------------------------------------- main

(defn ignore-text
  "remote の .gitignore に `.cpcache/` を足したテキスト。既にあれば nil。"
  [current]
  (let [cur (or current "")
        lines (str/split-lines cur)
        has? (some #(#{".cpcache" ".cpcache/" "/.cpcache" "/.cpcache/"} (str/trim %)) lines)]
    (when-not has?
      (str (if (or (str/blank? cur) (str/ends-with? cur "\n")) cur (str cur "\n"))
           ".cpcache/\n"))))

(defn -main []
  (println (if execute? "cpcache-untrack-sweep APPLY" "cpcache-untrack-sweep DRY-RUN（--execute で実行）"))
  (let [cands (cond->> (candidates) max-n (take max-n))
        stats (atom {:done 0 :skip-archived 0 :skip-noop 0 :skip-unknown 0 :fail 0 :files 0})]
    (println (format "対象候補 %d repo（.cpcache が tracked）" (count cands)))
    (doseq [dir cands]
      (let [rel  (str/replace dir (str root "/") "")
            slug (slug-of dir)]
        (if-not slug
          (do (swap! stats update :skip-unknown inc)
              (println (format "  skip  %-52s remote から slug を解決できない" rel)))
          (let [r (probe slug)
                repo (get-in r [:data :repository])]
            (cond
              (nil? repo)
              (do (swap! stats update :skip-unknown inc)
                  (println (format "  skip  %-52s 照会できなかった（archived か不明。false にしない）" rel)))

              (:isArchived repo)
              (do (swap! stats update :skip-archived inc)
                  (println (format "  skip  %-52s archived（cleanup 対象外）" rel)))

              :else
              (let [branch   (get-in repo [:defaultBranchRef :name])
                    head-oid (get-in repo [:defaultBranchRef :target :oid])
                    entries  (get-in repo [:cp :entries])
                    dels     (mapv #(str ".cpcache/" (:name %)) (filter #(= "blob" (:type %)) entries))
                    gi-new   (ignore-text (get-in repo [:gi :text]))]
                (cond
                  (and (empty? dels) (nil? gi-new))
                  (do (swap! stats update :skip-noop inc)
                      (println (format "  noop  %-52s remote には既に無く ignore 済み" rel)))

                  (nil? branch)
                  (do (swap! stats update :skip-unknown inc)
                      (println (format "  skip  %-52s default branch が無い" rel)))

                  :else
                  (if-not execute?
                    (do (swap! stats update :done inc)
                        (swap! stats update :files + (count dels))
                        (println (format "  plan  %-52s -%d file(s)%s" rel (count dels)
                                         (if gi-new " +.gitignore" " (.gitignore 済)"))))
                    (let [{:keys [ok oid err]} (commit! slug branch head-oid dels
                                                        (or gi-new (get-in repo [:gi :text]) ""))]
                      (if ok
                        (do (swap! stats update :done inc)
                            (swap! stats update :files + (count dels))
                            (println (format "  ok    %-52s -%d file(s) → %s" rel (count dels) (subs oid 0 8))))
                        (do (swap! stats update :fail inc)
                            (println (format "  FAIL  %-52s %s" rel (subs (str err) 0 (min 110 (count (str err))))))))))))))))
      nil)
    (println)
    (println (str "完了: " (pr-str @stats)))
    (println "※ ローカルの working tree には一切触っていない。")))

(-main)
