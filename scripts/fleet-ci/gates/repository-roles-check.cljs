#!/usr/bin/env nbb
;; repository-roles-check.cljs — repository role 宣言の gate。
;;
;; 展開済みの superproject tree を受け取り、`manifest/repository-rules.edn`
;; （workspace authority、ADR-2607299000）に対して子 repo の宣言を検証する。
;;
;; **検証ロジックはここに複製しない。** 権威も検査も
;; `scripts/verify-repository-roles.cljs` が正本で、この gate はその入力
;; （子 repo 2 ファイル × N）を用意して同じ verifier を呼ぶだけ。GitHub Actions
;; 側と fleet 側で別実装を持つと、片方だけ通る状態が黙って生まれる。
;;
;; 対象 repo は authority の :checked-repos から引く（consumer ごとのハードコード
;; を作らない — それが 5 repo の乖離を見逃した原因そのもの）。
;;
;; ネットワーク: 子 repo は全て public なので raw.githubusercontent.com から
;; **token 無し**で取る。ノードに credential を置かない不変条件を崩さない
;; （ノードは既に npx で npm から nbb を取っているので、外向き HTTPS は前提）。
;;
;; ノード側で `npx nbb repository-roles-check.cljs <dir> [--min N]` として実行。
(ns fleet-ci.gates.repository-roles-check
  (:require ["node:child_process" :as cp]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            [cljs.reader :as reader]
            [clojure.string :as str]))

(def args (vec *command-line-args*))

(defn- flag [name default]
  (let [i (.indexOf args name)]
    (if (neg? i) default (nth args (inc i)))))

(def root (or (first (remove #(str/starts-with? % "--")
                             (remove #{(str (flag "--min" ""))} args)))
              "."))
;; false-pass の床。:checked-repos が壊れて空になったときに「0 件検証して合格」
;; を返さないため（docs-edn-check の --min と同じ役割）。
(def min-repos (js/parseInt (str (flag "--min" 20)) 10))

(defn- die! [code & msg]
  (println (str "FLEET-CI: " (str/join " " msg)))
  (js/process.exit code))

(def authority-file (path/join root "manifest" "repository-rules.edn"))
(def verifier (path/join root "scripts" "verify-repository-roles.cljs"))

(when-not (fs/existsSync authority-file)
  (die! 90 "authority not found:" authority-file
        "— extraction or :include-ext is wrong, refusing to report pass"))
(when-not (fs/existsSync verifier)
  (die! 90 "verifier not found:" verifier
        "— .cljs is missing from the shipped tree (:include-ext), refusing to report pass"))

(def authority
  (try (reader/read-string (str (fs/readFileSync authority-file "utf8")))
       (catch :default e (die! 1 "authority is unparsable:" (ex-message e)))))

(def targets
  (vec (for [{:keys [org names]} (:checked-repos authority)
             n names]
         {:org org :name n})))

(when (< (count targets) min-repos)
  (die! 90 "only" (count targets) "repos listed in :checked-repos (<" min-repos
        ") — refusing to report pass on a near-empty target list"))

(defn- fetch
  "raw.githubusercontent から 1 ファイル。-> string | :missing | :error"
  [org name p]
  (let [url (str "https://raw.githubusercontent.com/" org "/" name "/HEAD/" p)
        r (try (cp/execFileSync "curl" #js ["-fsSL" "--max-time" "30" url]
                                #js {:encoding "utf8" :maxBuffer 33554432})
               (catch :default e {:err (or (.-status e) 1)}))]
    (cond
      (string? r) r
      ;; curl は HTTP エラーで exit 22。404（宣言・deps が無い）と、ネットワーク
      ;; 障害とを区別する — 後者を「ファイル無し」と読むと gate が静かに緩む。
      (= 22 (:err r)) :missing
      :else :error)))

(def tmp (fs/mkdtempSync (path/join (os/tmpdir) "roles-gate-")))

(let [fetched (atom []) missing (atom []) errored (atom [])]
  (doseq [{:keys [org name]} targets]
    (let [rules (fetch org name "resources/repository-rules.edn")]
      (cond
        (= :error rules) (swap! errored conj [name "resources/repository-rules.edn"])
        (= :missing rules) (swap! missing conj name)
        :else
        (let [d (path/join tmp name)
              deps (fetch org name "deps.edn")]
          (if (= :error deps)
            (swap! errored conj [name "deps.edn"])
            (do (fs/mkdirSync (path/join d "resources") #js {:recursive true})
                (fs/writeFileSync (path/join d "resources" "repository-rules.edn") rules)
                ;; deps.edn が無いのは正常（nbb repo）。verifier がその場合を
                ;; unverified として報告する。
                (when (string? deps) (fs/writeFileSync (path/join d "deps.edn") deps))
                (swap! fetched conj d)))))))

  (println "targets:" (count targets)
           "fetched:" (count @fetched)
           "missing-declaration:" (count @missing)
           "fetch-errors:" (count @errored))
  (doseq [n @missing] (println "  MISSING" n "has no resources/repository-rules.edn"))
  (doseq [[n p] @errored] (println "  FETCH-ERROR" n p))

  ;; ネットワーク障害は「検証できなかった」であって「合格」ではない。
  (when (seq @errored)
    (die! 91 (count @errored) "files could not be fetched — refusing to report pass"))
  ;; :checked-repos に載っているのに宣言が無いのは、authority と実態の乖離＝失敗。
  (when (seq @missing)
    (die! 1 (count @missing) "listed repos have no declaration"))
  (when (< (count @fetched) min-repos)
    (die! 90 "only" (count @fetched) "repos materialized (<" min-repos
          ") — refusing to report pass"))

  (let [r (try (cp/execFileSync "npx" (clj->js (into ["--yes" "nbb" "--classpath" root verifier]
                                                     @fetched))
                                #js {:encoding "utf8" :cwd root :maxBuffer 33554432})
               (catch :default e
                 {:err (or (.-status e) 1)
                  :out (str (or (some-> (.-stdout e) str) "")
                            (or (some-> (.-stderr e) str) ""))}))]
    (if (string? r)
      (do (print r) (println "OK —" (count @fetched) "repositories verified"))
      (do (print (:out r))
          (js/process.exit (:err r))))))
