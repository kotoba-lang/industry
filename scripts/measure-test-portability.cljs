#!/usr/bin/env nbb
;; How much of the no-ClojureScript-runner population is blocked by a MISSING
;; RUNNER, and how much by tests that are not portable in the first place.
;;
;; nekko is the reason to ask. Its library was properly portable -- `nekko.bytes`
;; is all reader conditionals -- while nine of its `.cljc` test files built key
;; seeds with `(byte-array (repeat 32 (byte 1)))`. Both of those are JVM-only, so
;; the tests could never load under ClojureScript no matter what runner was
;; pointed at them. If that shape is common, then "add a runner" is the wrong
;; first step for most of these repos and "make the tests portable" is the first
;; step.
;;
;; Two bounds are reported rather than one number, because deciding whether a
;; token sits inside a `#?(:clj ...)` branch needs paren-balanced reader-
;; conditional parsing, and a wrong single number here would be worse than an
;; honest range:
;;
;;   FLOOR  the file uses a JVM-only token AND contains no `#?(` at all, so the
;;          token cannot be guarded. Definitely not portable.
;;   CEIL   the file uses a JVM-only token anywhere, guarded or not.
;;
;; The truth is between them. nekko's nine files were in the FLOOR set.
(require '[clojure.string :as str])

(def fs (js/require "node:fs"))
(def node-path (js/require "node:path"))
(def root (.cwd js/process))
(defn full [p] (.join node-path root p))
(defn read-text [p] (try (.readFileSync fs p "utf8") (catch :default _ nil)))

(def prune-dirs
  #{"node_modules" ".git" "target" ".cpcache" ".shadow-cljs" "out" "dist"
    ".datalad" ".claude" "vendor"})

(defn walk [dir]
  (let [acc (volatile! [])]
    (letfn [(go [d depth]
              (when (< depth 12)
                (doseq [e (try (.readdirSync fs d #js {:withFileTypes true})
                               (catch :default _ #js []))]
                  (let [nm (.-name e) p (.join node-path d nm)]
                    (cond
                      (.isDirectory e)
                      (when-not (or (prune-dirs nm) (str/starts-with? nm "."))
                        (go p (inc depth)))
                      (.isFile e) (vswap! acc conj p))))))]
      (go dir 0))
    @acc))

;; The tokens that make a file JVM-only. Chosen from what actually blocked nekko
;; plus the neighbours that fail the same way under a ClojureScript host.
(def jvm-tokens
  [["byte-array"   #"\(\s*byte-array\b"]
   ["byte"         #"\(\s*byte\s"]
   ["clojure.lang" #"\bclojure\.lang\."]
   ["java-import"  #"\(:import\b"]
   ["java-pkg"     #"\bjava\.[a-z]"]
   ["System/"      #"\bSystem/"]
   ["Thread/"      #"\bThread/"]
   ["clojure.java" #"\bclojure\.java\."]
   ["proxy"        #"\(\s*proxy\b"]
   ["reflection"   #"\.getDeclared|\bClass/forName"]])

(def registered
  (->> (str/split-lines (read-text (full "manifest/west.yml")))
       (keep #(second (re-find #"^\s+path:\s*(\S+)\s*$" %)))
       (into (sorted-set))))

(def stats (atom {:repos-scanned 0 :no-runner 0
                  :floor-repos 0 :ceil-repos 0 :clean-repos 0
                  :floor-files 0 :ceil-files 0 :portable-test-files 0}))
(def token-hits (atom {}))
(def examples (atom []))

(doseq [rel registered]
  (let [abs (full rel)]
    (when (try (.isDirectory (.statSync fs abs)) (catch :default _ false))
      (swap! stats update :repos-scanned inc)
      (let [files (walk abs)
            rels (map #(.relative node-path abs %) files)
            by-rel (zipmap rels files)
            txt (fn [r] (read-text (by-rel r)))
            portable? #(re-find #"\.clj[sc]$" %)
            deftest? (fn [r] (re-find #"\(\s*deftest" (or (txt r) "")))
            test-files (filter #(and (portable? %) (deftest? %)) rels)
            has-runner? (some (fn [r]
                                (and (portable? r) (not (deftest? r))
                                     (re-find #"\(\s*[a-zA-Z0-9._/-]*run-(all-)?tests"
                                              (or (txt r) ""))))
                              rels)]
        (when (and (seq test-files) (not has-runner?))
          (swap! stats update :no-runner inc)
          (swap! stats update :portable-test-files + (count test-files))
          (let [judged (for [r test-files
                             :let [s (or (txt r) "")
                                   hits (keep (fn [[nm re]] (when (re-find re s) nm))
                                              jvm-tokens)
                                   cond? (str/includes? s "#?(")]]
                         {:file r :hits (vec hits) :guarded-possible? cond?})
                ceil (filter #(seq (:hits %)) judged)
                floor (filter #(and (seq (:hits %)) (not (:guarded-possible? %))) judged)]
            (swap! stats update :ceil-files + (count ceil))
            (swap! stats update :floor-files + (count floor))
            (doseq [h (mapcat :hits ceil)] (swap! token-hits update h (fnil inc 0)))
            (cond
              (seq floor) (do (swap! stats update :floor-repos inc)
                              (when (< (count @examples) 12)
                                (swap! examples conj
                                       (str rel " :: " (:file (first floor))
                                            " " (pr-str (:hits (first floor)))))))
              (seq ceil)  (swap! stats update :ceil-repos inc)
              :else       (swap! stats update :clean-repos inc))))))))

(let [{:keys [repos-scanned no-runner floor-repos ceil-repos clean-repos
              floor-files ceil-files portable-test-files]} @stats]
  (println "registered checkouts scanned:" repos-scanned)
  (println "  with portable tests and NO ClojureScript runner:" no-runner)
  (println "    portable test files in them:" portable-test-files)
  (println)
  (println "  FLOOR  repos with >=1 test file that uses a JVM-only token and has")
  (println "         no reader conditional at all (cannot be guarded):" floor-repos)
  (println "  MIDDLE repos whose only JVM-only tokens might be guarded:" ceil-repos)
  (println "  CLEAN  repos whose tests use none of these tokens:" clean-repos)
  (println)
  (println "  test files: FLOOR" floor-files "/ CEIL" ceil-files "of" portable-test-files)
  (println)
  (println "  token frequency across CEIL files:")
  (doseq [[k v] (sort-by (comp - val) @token-hits)]
    (println (str "    " v "\t" k)))
  (println)
  (println "  FLOOR examples:")
  (doseq [e @examples] (println "   " e)))
