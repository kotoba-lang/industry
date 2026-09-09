;; Make a source namespace point at its own split's protocol instead of
;; declaring a second one.
;;
;; A protocol split into its own repo is a different protocol from the one the
;; source namespace still declares, and an implementation extending one is not
;; accepted by the other. Measured 2026-09-09: a filesystem reified against
;; kotoba.lang.fs/IFilesystem answers through kotoba.lang.fs and fails through
;; kotoba.fs -- "No implementation of method: :exists?".
;;
;; This rewrites the (defprotocol IX ...) form into
;;   (def IX <split-alias>/X)      ; the same protocol, not a second one
;;   (def <method> <split-alias>/<method>)  ; one per method
;; and adds the require. The methods have to be interned, not :refer'd: a
;; referred var is a mapping, and a qualified reference like fs/read to a merely
;; referred var does not compile ("No such var: fs/read").
;;
;; KNOWN LIMIT, measured: a copied protocol var works for `reify` and NOT for
;; `extend-type` -- extend writes into the original var and dispatch through the
;; copy does not see it. Every implementation of these protocols in this
;; workspace uses reify: 46,357 files scanned, 69 reify, zero extend-type,
;; extend-protocol or extend. That is why this is a repair rather than a trade.
;;
;; Usage: nbb unfork.cljs <source-file> <graph-file> <repos-tsv> <target-ns>
(require '[clojure.string :as s])
(def fs (js/require "node:fs"))
(def src-path (nth *command-line-args* 0))
(def graph-path (nth *command-line-args* 1))
(def repos-path (nth *command-line-args* 2))
(def target-ns (nth *command-line-args* 3))

(def src (str (.readFileSync fs src-path "utf8")))
(def graph-lines (s/split-lines (str (.readFileSync fs graph-path "utf8"))))
(def repo-of
  (into {} (for [l (s/split-lines (str (.readFileSync fs repos-path "utf8")))
                 :let [[n r] (s/split l #"\t")]
                 :when (and n r)]
             [n r])))

;; protocol -> [methods...] from the extractor's PROVIDES lines
(def provides
  (into {} (for [l graph-lines
                 :let [[tag nm ps] (s/split l #"\t" 3)]
                 :when (= "PROVIDES" tag)]
             [nm (s/split ps #",")])))
(def protocols
  (vec (for [l graph-lines
             :let [[tag nm kind] (s/split l #"\t" 4)]
             :when (and (= "DEF" tag) (= "defprotocol" kind))]
         nm)))

(when (empty? protocols)
  (println "REFUSING: no defprotocol in this namespace -- nothing to unfork.")
  (js/process.exit 2))

;; the namespace segment the generator gave each protocol: repo name minus prefix
(defn ns-seg [proto]
  (let [r (get repo-of proto)]
    (when-not r
      (println (str "REFUSING: " proto " has no repo in " repos-path))
      (js/process.exit 2))
    ;; kotoba.<target>.<segment>; the segment is the repo name after its prefix,
    ;; and the prefix is whatever precedes the generator's slug.
    r))

;; the name the protocol takes inside its own repo (the leading I is dropped
;; unless that would collide with a java.lang auto-import -- the generator's rule)
(defn split-name [proto seg-file]
  (let [t (str (.readFileSync fs seg-file "utf8"))
        m (re-find #"\(defprotocol\s+([A-Za-z][A-Za-z0-9]*)" t)]
    (when-not m
      (println (str "REFUSING: no defprotocol found in " seg-file))
      (js/process.exit 2))
    (second m)))

(def out (atom src))
(def requires (atom []))

(doseq [proto protocols]
  (let [repo (ns-seg proto)
        ;; the repo's single source file, found rather than guessed
        dir (str (nth *command-line-args* 4) "/" repo "/src")
        files (loop [todo [dir] acc []]
                (if (empty? todo) acc
                  (let [x (first todo) st (.statSync fs x)]
                    (if (.isDirectory st)
                      (recur (concat (rest todo) (map #(str x "/" %) (.readdirSync fs x))) acc)
                      (recur (rest todo) (conj acc x))))))
        f (first (filter #(s/ends-with? % ".cljc") files))
        sname (split-name proto f)
        pns (str (second (re-find #"\(ns\s+([a-z][a-zA-Z0-9._*<>=-]*)" (str (.readFileSync fs f "utf8")))))
        alias (str (s/lower-case sname) "-p")
        methods (remove #(= % proto) (get provides proto []))
        block (str "(def " proto "\n"
                   "  \"The protocol itself lives in one repo of its own now. This name is that\n"
                   "  SAME protocol, not a second one: an implementation reified against either\n"
                   "  is accepted by both (ADR-2609091900).\"\n"
                   "  " alias "/" sname ")\n\n"
                   (s/join "" (for [m methods] (str "(def " m " " alias "/" m ")\n")))
                   "\n")
        pat (re-pattern (str "\\(defprotocol\\s+" proto "[\\s\\S]*?\\n\\n"))]
    (when-not (re-find pat @out)
      (println (str "REFUSING: could not find the (defprotocol " proto " ...) form."))
      (js/process.exit 2))
    (swap! requires conj (str "[" pns " :as " alias "]"))
    (swap! out s/replace-first pat block)))

;; Add the require at the END of the ns form, found by a balanced scan.
;;
;; Anchoring on "(ns <name>" put the clause BEFORE the docstring, which made the
;; docstring itself read as a clause: every namespace with a docstring and no
;; :refer-clojure failed with ":ns-clauses :refer-clojure - failed:
;; (or (nil? %) (sequential? %))". Three of the seven families did.
(let [t @out
      start (.indexOf t "(ns ")
      end (loop [i start, depth 0, in-str? false, esc? false]
            (let [c (nth t i)]
              (cond
                esc? (recur (inc i) depth in-str? false)
                (and in-str? (= c \\)) (recur (inc i) depth true true)
                (= c \") (recur (inc i) depth (not in-str?) false)
                in-str? (recur (inc i) depth true false)
                (= c \() (recur (inc i) (inc depth) false false)
                (= c \)) (if (= depth 1) i (recur (inc i) (dec depth) false false))
                :else (recur (inc i) depth false false))))]
  (reset! out (str (subs t 0 end)
                   "\n  (:require " (s/join "\n            " @requires) ")"
                   (subs t end))))

(.writeFileSync fs src-path @out)
(println (str "UNFORKED\t" (count protocols) "\tprotocol(s) in " src-path ": "
              (s/join ", " protocols)))
