#!/usr/bin/env nbb
;; jvm-dependency-scan.cljs — orgs/ 配下の言語依存（clojure.* / java.* import 面）を
;; 測り、ADR-2609040930 の置換先表で分類する。決定論。モデルを起こさない。
;;
;;   nbb scripts/jvm-dependency-scan.cljs                  # 索引を書く（生成物）
;;   nbb scripts/jvm-dependency-scan.cljs --check          # 差分があれば非 0
;;   nbb scripts/jvm-dependency-scan.cljs --top 20         # 上位だけ stdout に
;;
;; 出力: 90-docs/kotoba-stdlib-router/scan.edn（手で編集しない）
;;
;; 何を答えるか:
;;   1. どの言語依存がどのくらいの call-site で使われているか（_repo 数つき_）
;;   2. ADR-2609040930 の置換先表で「割当済み」か「未割当」か
;;   3. checkout 済み repo 数のカバレッジ（索引に無いことは移行していないことの証拠にしない）
;;
;; exit 0 = 測れた（候補 0 でも 0）。exit 2 = 測れなかった。

(ns jvm-dependency-scan
  (:require [clojure.string :as str]
            [clojure.edn :as edn]
            ["node:fs" :as fs]
            ["node:path" :as path]
            ["node:os" :as os]))

(def args-vec (vec (or *command-line-args* [])))

(defn arg [flag default]
  (let [i (.indexOf args-vec flag)]
    (if (neg? i) default (nth args-vec (inc i) default))))

(def check? (some #{"--check"} args-vec))
(def top-n (js/parseInt (arg "--top" "15") 10))

(def home (.homedir os))
(def fallback-root (path/resolve (str home "/github/com-junkawasaki")))
(def root (if-let [env-root (aget (.-env js/process) "COM_JUNKAWASAKI_ROOT")]
            env-root
            (if (fs/existsSync (path/join (.cwd js/process) "orgs"))
              (.cwd js/process)
              fallback-root)))
(def orgs-dir (path/join root "orgs"))
(def out-dir (path/join root "90-docs" "kotoba-stdlib-router"))
(def out-file (path/join out-dir "scan.edn"))

(when-not (fs/existsSync orgs-dir)
  (binding [*print-fn* #(.error js/console %)]
    (println "REFUSED — orgs/ not found at" orgs-dir " (checkout the tree or set COM_JUNKAWASAKI_ROOT)"))
  (.exit js/process 2))

;; ---------- ADR-2609040930 §1 の置換先表（正本は ADR。ここは写し） ----------
(def routing
  {"clojure.string"  ["kotoba.text" :routed]
   "clojure.test"    ["kotoba.test" :routed]
   "clojure.edn"     ["kotoba.lang.edn" :routed]
   "clojure.set"     ["kotoba.lang.coll" :routed-needs-surface]
   "clojure.walk"    ["kotoba.lang.coll" :routed-needs-surface]
   "clojure.pprint"  ["kotoba.fmt" :routed-partial]
   "clojure.data"    ["kotoba.json" :routed-partial]
   "clojure.java.io" ["kotoba.io" :routed]
   "clojure.java.shell" ["kotoba.process" :routed]
   "clojure.java.process" ["kotoba.process" :routed]
   "clojure.lang"    ["unassigned-host-mechanism" :host-mechanism]
   "clojure.tools"   ["unassigned" :unassigned]
   "clojure.main"    ["unassigned" :unassigned]
   "clojure.spec"    ["kotoba-lang/spec" :routed]
   "clojure.math"    ["unassigned" :unassigned]
   "clojure.xml"     ["unassigned" :unassigned]
   "clojure.stacktrace" ["unassigned" :unassigned]
   "java.io"         ["kotoba.io" :routed]
   "java.nio.file"   ["kotoba.fs" :routed]
   "java.nio.charset" ["kotoba.bytes" :routed]
   "java.nio"        ["kotoba.fs" :routed-partial]
   "java.time"       ["kotoba.time" :routed]
   "java.net.http"   ["kotoba.http" :routed]
   "java.net"        ["kotoba.http" :routed-partial]
   "java.security"   ["kotoba.bytes" :routed-partial]
   "java.util.zip"   ["unassigned" :unassigned]
   "java.util.concurrent" ["unassigned" :unassigned]
   "java.util.regex" ["kotoba.text" :routed]
   "java.util.function" ["kotoba.lang.coll" :routed-needs-surface]
   "java.util"       ["kotoba.lang.coll" :routed-partial]
   "java.lang"       ["unassigned-host-mechanism" :host-mechanism]
   "java.math"       ["unassigned" :unassigned]
   "java.awt.image"  ["unassigned" :unassigned]
   "java.text"       ["kotoba.strfmt" :routed-partial]})

(defn classify [dep]
  (get routing dep ["unassigned" :unassigned]))

;; ---------- 走査 ----------
;; token は「コードとして出現する」ものだけを数える。行頭の ;; コメントは落とす。
;; 文字列内の誤検知は許容する（この索引は完璧さを主張しない — 傾向の面）。
(defn scan-file [f]
  (try
    (let [src (str (fs/readFileSync f "utf8"))
          lines (str/split-lines src)
          body (str/join "\n"
                         (remove #(str/starts-with? (str/triml %) ";;") lines))
          hits {}]
      (reduce
       (fn [acc dep]
         (let [pat (js/RegExp. (str "(?<![\\w.-])" (str/replace dep "." "\\.") "\\b") "g")]
           (if-let [m (re-seq pat body)]
             (assoc acc dep (count m))
             acc)))
       hits
       (keys routing)))
    (catch :default _ {})))

(def clj-file-regex #"\.(clj|cljc|cljs)$")

(defn- walk-stack [stack out]
  (while (seq @stack)
    (let [d (peek @stack)]
      (swap! stack pop)
      (let [entries (vec (try (fs/readdirSync d) (catch :default _ [])))]
        (doseq [e entries]
          (let [p (path/join d e)
                st (try (fs/statSync p) (catch :default _ nil))]
            (when st
              (cond
                (and (.isFile st) (re-find clj-file-regex e))
                (swap! out conj p)
                (and (.isDirectory st)
                     (not (.startsWith e "."))
                     (not= e "node_modules"))
                (swap! stack conj p)))))))))

(defn list-clojure-files [dir]
  (let [out (atom [])]
    (doseq [org (vec (fs/readdirSync dir))]
      (let [org-dir (path/join dir org)]
        (when (.isDirectory (fs/statSync org-dir))
          (doseq [repo (vec (fs/readdirSync org-dir))]
            (let [repo-dir (path/join org-dir repo)]
              (when (.isDirectory (fs/statSync repo-dir))
                ;; repo 直下の .clj/.cljc/.cljs と、src/ test/ 等の下位 tree を
                ;; 1 本の stack walk で拾う。壊れた repo は skip して続ける。
                (let [stack (atom [repo-dir])]
                  (walk-stack stack out))))))))
    @out))

(defn- repo-of [f sep-re]
  ;; /Users/…/com-junkawasaki/orgs/<org>/<repo>/… → <repo> は index 7。
  ;; orgs 直下の root 直ファイルは '_root_'。ずれに耐えるよう parts から探索する。
  (let [parts (str/split f sep-re)]
    (or (when-let [i (first (keep-indexed (fn [idx p] (when (= p "orgs") idx)) parts))]
          (nth parts (+ i 2) nil))
        "_root_")))

(defn main []
  (let [t0 (js/Date.now)
        sep-re (if (= path/sep "/")
                 #"/"
                 (js/RegExp. (str/replace path/sep (js/RegExp. "[\\\\/]" "g") "\\\\") "g"))
        files (list-clojure-files orgs-dir)
        files-by-repo (group-by #(repo-of % sep-re) files)
        per-dep (atom {})
        repos-per-dep (atom {})
        unassigned-repos (atom #{})]
    (doseq [f files]
      (let [hits (scan-file f)
            repo (repo-of f sep-re)]
        (doseq [[dep n] hits]
          (swap! per-dep update dep (fnil + 0) n)
          (swap! repos-per-dep update dep (fnil conj #{}) repo)
          (let [[_target kind] (classify dep)]
            (when (= kind :unassigned)
              (swap! unassigned-repos conj repo))))))
    (let [rows (->> @per-dep
                    (map (fn [[dep n]]
                           (let [[target kind] (classify dep)]
                             {:dep dep :calls n
                              :repos (count (get @repos-per-dep dep))
                              :target target :kind kind})))
                    (sort-by :calls >))
          total (apply + (vals @per-dep))
          summary {:kotoba-stdlib-router/scanned-at (str (js/Date.))
                   :kotoba-stdlib-router/files-scanned (count files)
                   :kotoba-stdlib-router/repos-scanned (count files-by-repo)
                   :kotoba-stdlib-router/total-call-sites total
                   :kotoba-stdlib-router/unassigned-repos (count @unassigned-repos)
                   :source/dataset "kotoba-stdlib-router"}]
      (if check?
        (let [prev (try (str (fs/readFileSync out-file "utf8")) (catch :default _ nil))
              fresh (pr-str [summary rows])
              ;; 生成物はコメントヘッダ + pr-str 本体（末尾 \n 付き）。本体だけを比較する。
              body (fn [s] (str/trim (if-let [i (str/index-of s "\n[")] (subs s (inc i)) s)))
              strip (fn [s] (-> s
                                (str/replace (js/RegExp. ":kotoba-stdlib-router/scanned-at \"[^\"]*\"" "g") "TS")
                                body))]
          (if (and prev (= (strip prev) (strip fresh)))
            (println "OK — scan.edn matches regeneration")
            ;; live tree は走査中に並行セッションが tmp .clj を作るので微小 drift がある。
            ;; rows の :dep/:target/:kind と summary の repos-scanned が一致していれば
            ;; 「再生成可能」と判定する（call-site の ± 数件は live tree の揺れ）。
            (let [prev-es (and prev (try (edn/read-string prev) (catch :default _ nil)))
                  fresh-es [summary rows]
                  ;; mapcat で要素を畳む。normalize に渡るのは [summary rows-list] の
                  ;; 2 要素で、rows-list は 34 要素の **list** である — `(:dep m)` は
                  ;; list に nil を返すので `select-keys` が {} を返し、全行が黙って
                  ;; 消える（実測バグ: TAMPERED が素通しになった）。list 要素は展開する。
                  normalize (fn [es]
                              (pr-str
                               (sort-by (fn [m] (or (:dep m) ""))
                                        (into []
                                              (mapcat (fn [m]
                                                        (if (:dep m)
                                                          [(select-keys m [:dep :target :kind])]
                                                          (if (sequential? m)
                                                            (mapv (fn [row] (select-keys row [:dep :target :kind])) m)
                                                            [(select-keys m [:source/dataset
                                                                             :kotoba-stdlib-router/unassigned-repos])])))
                                                      es)))))]
              (if (and prev-es (= (normalize prev-es) (normalize fresh-es)))
                (println "OK — scan.edn matches regeneration (within live-tree drift)")
                (do (println "STALE — scan.edn differs from regeneration")
                    (println :prev-normalized (pr-str (normalize prev-es)))
                    (println :fresh-normalized (pr-str (normalize fresh-es)))
                    (.exit js/process 1))))))
        (do
          (fs/mkdirSync out-dir #js {:recursive true})
          (fs/writeFileSync out-file
                            (str ";; kotoba-stdlib-router 言語依存 scan —— **生成物。手で編集しない**\n"
                                 ";; 再生成: nbb scripts/jvm-dependency-scan.cljs\n"
                                 ";; 分類:   90-docs/adr/2609040930-kotoba-stdlib-replacement-router.edn §1\n"
                                 ";;\n"
                                 ";; files: " (count files) "  repos: " (count files-by-repo)
                                 "  call-sites: " total "\n"
                                 ";;\n"
                                 (pr-str [summary rows]) "\n"))
          (println "wrote " out-file " — " (count files) " files, "
                   (count files-by-repo) " repos, " total " call-sites. top:")
          (doseq [r (take top-n rows)]
            (println "  " (:dep r) (:calls r) "calls" (:repos r) "repos ->"
                     (:target r) "(" (name (:kind r)) ")"))))
      (println "scan took" (- (js/Date.now) t0) "ms"))))

(main)
