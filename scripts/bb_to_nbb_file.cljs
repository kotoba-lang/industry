#!/usr/bin/env nbb
;; Mechanical bb → nbb file converter (ADR-2607173000).
;; Best-effort rewrite of common babashka/JVM patterns to Node.
;;
;;   nbb scripts/bb_to_nbb_file.cljs <path.bb> [<path2.bb> ...]
;;
;; Writes <path>.cljs alongside and leaves .bb for the caller to delete.
(require '[clojure.string :as str]
         '[scripts.nbb-compat :refer [slurp spit exit]])

(def preamble
  ";; --- nbb shims (auto, ADR-2607173000) ---------------------------------
(def ^:private __fs (js/require \"node:fs\"))
(def ^:private __path (js/require \"node:path\"))
(def ^:private __cp (js/require \"node:child_process\"))
(def ^:private __os (js/require \"node:os\"))
(def ^:private __crypto (js/require \"node:crypto\"))
(defn- __sh [& args]
  (let [opts (when (map? (last args)) (last args))
        cmd (if opts (butlast args) args)
        r (.spawnSync __cp (first cmd) (to-array (rest cmd))
                      (clj->js (merge {:encoding \"utf8\"} (when opts {:cwd (:dir opts)}))))]
    {:exit (or (.-status r) 1) :out (or (.-stdout r) \"\") :err (or (.-stderr r) \"\")}))
(defn- __shell [& args]
  (let [opts (when (map? (first args)) (first args))
        cmd (if opts (rest args) args)
        r (.spawnSync __cp (first cmd) (to-array (rest cmd))
                      (clj->js (merge {:stdio \"inherit\" :encoding \"utf8\"}
                                      (when opts {:cwd (:dir opts)}))))]
    (when-not (zero? (or (.-status r) 1))
      (throw (js/Error. (str \"shell failed: \" (pr-str cmd)))))
    {:exit (or (.-status r) 0) :out \"\" :err \"\"}))
;; -----------------------------------------------------------------------
")

(defn- rewrite [src]
  (-> src
      (str/replace #"#!/usr/bin/env bb" "#!/usr/bin/env nbb")
      (str/replace #"#!/usr/bin/bb" "#!/usr/bin/env nbb")
      ;; drop babashka requires — shims provide sh/shell; cheshire via JSON
      (str/replace #"\[babashka\.process[^\]]*\]\n?" "")
      (str/replace #"\[babashka\.fs[^\]]*\]\n?" "")
      (str/replace #"\[babashka\.classpath[^\]]*\]\n?" "")
      (str/replace #"\[babashka\.curl[^\]]*\]\n?" "")
      (str/replace #"\[clojure\.java\.shell[^\]]*\]\n?" "")
      (str/replace #"\[clojure\.java\.io[^\]]*\]\n?" "")
      (str/replace #"\[cheshire\.core :as json\]" "")
      (str/replace #"\[cheshire\.core :as json\]\n?" "")
      ;; System
      (str/replace #"\(System/exit ([^)]+)\)" "(.exit js/process $1)")
      (str/replace #"\(System/getenv \"([^\"]+)\"\)" "(aget (.-env js/process) \"$1\")")
      (str/replace #"\(System/getenv\)" "(js->clj (.-env js/process))")
      (str/replace #"\(System/getProperty \"user.home\"\)" "(.-homedir __os)")
      (str/replace #"\(System/getProperty \"babashka.file\"\)" "*file*")
      ;; java.time
      (str/replace #"\(str \(java\.time\.LocalDate/now\)\)"
                   "(.slice (.toISOString (js/Date.)) 0 10)")
      (str/replace #"\(java\.time\.LocalDate/now\)"
                   "(.slice (.toISOString (js/Date.)) 0 10)")
      ;; process sh/shell — unqualified after require drop
      (str/replace #"\(apply sh " "(apply __sh ")
      (str/replace #"\(sh " "(__sh ")
      (str/replace #"\(apply shell " "(apply __shell ")
      (str/replace #"\(shell " "(__shell ")
      ;; refer shell/sh aliases if still qualified
      (str/replace #"babashka\.process/sh" "__sh")
      (str/replace #"babashka\.process/shell" "__shell")
      (str/replace #"clojure\.java\.shell/sh" "__sh")
      ;; File helpers (common patterns)
      (str/replace #"\(-> \*file\* \(java\.io\.File\.\) \.getAbsoluteFile \.getParentFile\)"
                   "(__path.dirname *file*)")
      (str/replace #"\(java\.io\.File\. ([^\)]+)\)" "(__path.resolve $1)")
      (str/replace #"\(\.getPath ([^\)]+)\)" "(str $1)")
      (str/replace #"\(\.getName \(io/file ([^\)]+)\)\)" "(__path.basename $1)")
      (str/replace #"\(\.getName \(__path\.resolve ([^\)]+)\)\)" "(__path.basename $1)")
      (str/replace #"\(\.exists \(java\.io\.File\. ([^\)]+)\)\)" "(__fs.existsSync $1)")
      (str/replace #"\(\.isDirectory ([^\)]+)\)" "(try (.isDirectory (__fs.statSync $1)) (catch :default _ false))")
      (str/replace #"\(\.listFiles ([^\)]+)\)"
                   "(mapv #(__path.join $1 %) (seq (__fs.readdirSync $1)))")
      (str/replace #"\(\.getAbsoluteFile ([^\)]+)\)" "(__path.resolve $1)")
      (str/replace #"\(\.getParentFile ([^\)]+)\)" "(__path.dirname $1)")
      (str/replace #"io/file" "__path.resolve")
      (str/replace #"\(io/reader " "(js/undefined ; io/reader-removed ")
      ;; add-classpath drop (nbb uses --classpath / nbb.edn)
      (str/replace #"\(add-classpath [^\)]+\)\n?" "")
      ;; MessageDigest sha256 common pattern — leave and hope; many use custom
      (str/replace #"\(java\.security\.MessageDigest/getInstance \"SHA-256\"\)"
                   "nil #_MessageDigest-use-__crypto")
      ;; JSON via cheshire → simple helpers (inject after preamble if json used)
      (str/replace #"json/parse-string" "__json-parse")
      (str/replace #"json/generate-string" "__json-gen")
      (str/replace #"\(json/parse-string" "(__json-parse")
      (str/replace #"\(json/generate-string" "(__json-gen")
      ;; bb runtime path refs
      (str/replace #"publish\.bb" "publish.cljs")
      (str/replace #"\"bb\"" "\"nbb\"")
      (str/replace #"\bbb " "nbb ")))

(defn- needs-json? [s]
  (or (str/includes? s "__json-parse") (str/includes? s "__json-gen")
      (str/includes? s "cheshire")))

(defn- json-helpers []
  "(defn- __json-parse [s & _] (js->clj (js/JSON.parse s) :keywordize-keys true))
(defn- __json-gen [x & _] (js/JSON.stringify (clj->js x)))
")

(defn convert-file! [bb-path]
  (let [src (slurp bb-path)
        body (rewrite src)
        out (str/replace bb-path #"\.bb$" ".cljs")
        with-pre (if (str/starts-with? body "#!/usr/bin/env nbb")
                   (str "#!/usr/bin/env nbb\n" preamble
                        (when (needs-json? body) (json-helpers))
                        (str/replace-first body #"#!/usr/bin/env nbb\n" ""))
                   (str preamble body))]
    (spit out with-pre)
    (println "wrote" out)
    out))

(let [args (vec *command-line-args*)
      args (if (and (seq args) (str/includes? (str (first args)) "bb_to_nbb_file"))
             (subvec args 1) args)]
  (when (empty? args)
    (println "usage: bb_to_nbb_file.cljs <file.bb>…")
    (exit 2))
  (doseq [f args]
    (if (.endsWith f ".bb")
      (convert-file! f)
      (println "skip (not .bb):" f))))
