#!/usr/bin/env nbb
;; verify-error-provenance — error records that classify a failure and drop the
;; evidence they classified it from.
;;
;;   nbb --classpath ".:scripts/nbb_compat" scripts/verify-error-provenance.cljs [--findings]
;;
;; ## Why this is a detector and not a sentence in CLAUDE.md
;;
;; It IS already a sentence in CLAUDE.md. Question 3 of the five, repo-wide
;; mandatory since 2026-08-13:
;;
;;   受け取ったエラー本文を捨てていないか。status だけ記録する経路は、
;;   原因が応答の中に書いてあっても読まない
;;
;; It was broken THREE TIMES on 2026-08-19, twice in code written that same
;; day by someone who had read the rule:
;;
;;   claude-bridge :unread-detail   kept the HTTP status, dropped the body
;;   provider/parse-arguments       kept the error type, dropped the arguments
;;   bots/record-turn!              kept the error type, dropped the message
;;
;; The third one cost the most: 205 resident runs filed `:internal-error`, 196
;; of them saying "request timed out", readable only by walking 3,926 goal
;; events. It also hid a fix that was already working.
;;
;; The workspace's own doctrine is that a rule which needs to be remembered is
;; not enforced -- the 848-commit divergence got a SessionStart hook for
;; exactly this reason ("agent が都度思い出して確認する運用は機能しなかった").
;; So this is the mechanical half.
;;
;; ## What it flags, and why the rule is narrow
;;
;; A map literal that reads `(ex-data <sym>)` -- i.e. an exception is IN HAND
;; and being classified -- and sets no key whose name carries `message`,
;; `body`, `detail`, `sample`, `reason` or `cause`.
;;
;; Narrow on purpose. `{:turn/error-type :bot/cancelled}` is NOT flagged: it is
;; a constant, no exception was consulted, and the type IS the whole story.
;; The defect is specifically "we looked at the exception, took one field from
;; it, and threw the rest away".
;;
;; A detector that flagged every error map would be noise, and noise is how a
;; detector stops being read.

(ns verify-error-provenance
  (:require ["node:fs" :as fs]
            ["node:path" :as path]
            ["node:child_process" :as cp]
            [clojure.string :as str]))

(def argv (vec (drop 2 (.-argv js/process))))
(defn flag? [n] (some #(= n %) argv))
(def findings? (flag? "--findings"))
(def self-test? (flag? "--self-test"))

(def evidence-key-rx #"(?i)message|body|detail|sample|reason|cause|text|stderr|output")

(defn- balanced-map-at
  "The `{...}` literal starting at `start`, or nil. Brace-counting is enough
  here: Clojure string literals cannot contain an unescaped brace-affecting
  construct that survives `\\\"` handling, which is done."
  [s start]
  (loop [i start depth 0 in-str? false esc? false]
    (if (>= i (count s))
      nil
      (let [c (nth s i)]
        (cond
          esc? (recur (inc i) depth in-str? false)
          (and in-str? (= c \\)) (recur (inc i) depth true true)
          (= c \") (recur (inc i) depth (not in-str?) false)
          in-str? (recur (inc i) depth true false)
          (= c \{) (recur (inc i) (inc depth) false false)
          (= c \}) (if (= 1 depth)
                     (subs s start (inc i))
                     (recur (inc i) (dec depth) false false))
          :else (recur (inc i) depth false false))))))

(defn- map-literals
  "Every top-level `{...}` literal, with its offset.

  Braces INSIDE string literals are skipped. Without that the scan opened a
  map at a `{` that was test data -- measured against org-w3-nquads, whose
  URI fixtures contain one -- and then brace-matched its way across unrelated
  code into a false finding."
  [src]
  (let [n (count src)]
    (loop [i 0 in-str? false esc? false acc []]
      (if (>= i n)
        acc
        (let [c (nth src i)]
          (cond
            esc? (recur (inc i) in-str? false acc)
            (and in-str? (= c \\)) (recur (inc i) true true acc)
            (= c \") (recur (inc i) (not in-str?) false acc)
            in-str? (recur (inc i) true false acc)
            (= c \{) (if-let [m (balanced-map-at src i)]
                       (recur (+ i (count m)) false false (conj acc [i m]))
                       (recur (inc i) false false acc))
            :else (recur (inc i) false false acc)))))))

(defn- line-of [src idx]
  (inc (count (re-seq #"\n" (subs src 0 idx)))))

(defn- offenders
  "Map literals in `src` that consult an exception and record no evidence."
  [src]
  (for [[idx m] (map-literals src)
        ;; an exception is in hand and being read
        :when (re-find #"\(ex-data\s" m)
        ;; and something type-shaped is being stored from it
        :when (re-find #"(?i)[:\w/-]*(error-type|:type)\s" m)
        ;; and nothing carries the evidence
        :when (not (re-find evidence-key-rx m))
        ;; not the ex-info CONSTRUCTION site: `(ex-info "msg" {...})` already
        ;; carries its message in the string beside the map.
        :when (not (re-find #"ex-info" (subs src (max 0 (- idx 200)) idx)))]
    {:line (line-of src idx)
     :snippet (-> m (str/replace #"\s+" " ") (subs 0 (min 130 (count m))))}))

(defn- clj-files
  "`{:files [..]}` or `{:error \"..\"}` -- never a bare empty list.

  The distinction is the point, and this detector got it wrong first: a failed
  `git ls-files` returned \"\" and the repo contributed zero files, silently.
  Measured on the first real run: 3,256 listings failed with `could not read
  IPC response` (git's fsmonitor daemon) while SCANNED printed 45,618 and the
  run looked clean. That is the exact defect this detector exists to find,
  committed by the detector."
  [root]
  (try
    (let [out (.toString (cp/execSync
                          (str "git -c core.fsmonitor=false -C " root
                               " ls-files -- '*.clj' '*.cljc' '*.cljs'")
                          #js {:maxBuffer (* 64 1024 1024)
                               :stdio #js ["pipe" "pipe" "pipe"]}))]
      {:files (->> (str/split-lines out)
                   (remove str/blank?)
                   ;; test fixtures carry deliberate malformations
                   (remove #(re-find #"(^|/)test/|(^|/)\.nbb/" %))
                   vec)})
    (catch :default e
      (let [m (str (or (.-message e) e))]
        ;; "not a git repository" is not a failure to read a repo -- it is a
        ;; path that is not one. Measured: 117 of these, all orphaned worktree
        ;; registrations whose gitdir is gone. Counting them as unreadable
        ;; would make this detector permanently unable to answer, which is a
        ;; different way of being useless than a false pass.
        (if (re-find #"not a git repository" m)
          {:files [] :skipped m}
          {:error m})))))

(defn- scan-repo [root label]
  (let [{:keys [files error skipped]} (clj-files root)]
    {:label label
     :error error
     :skipped skipped
     :scanned (count files)
     :findings (vec (for [f files
                          :let [p (path/join root f)
                                src (try (str (fs/readFileSync p "utf8"))
                                         (catch :default _ nil))]
                          :when src
                          o (offenders src)]
                      (assoc o :file f)))}))

;; ── self-test: both directions, or this is theatre ──────────────────────
(def ^:private bad-sample
  "(record-turn! bot id {:turn/state :failed
                         :turn/error-type (or (:type (ex-data error)) :internal-error)
                         :turn/error-status (:status (ex-data error))})")
(def ^:private good-sample
  "(record-turn! bot id {:turn/state :failed
                         :turn/error-type (or (:type (ex-data error)) :internal-error)
                         :turn/error-message (error-message error)})")
(def ^:private constant-sample
  "(record-turn! bot id {:turn/state :cancelled :turn/error-type :bot/cancelled})")

(defn- self-test! []
  (let [bad (count (offenders bad-sample))
        good (count (offenders good-sample))
        constant (count (offenders constant-sample))]
    (println "self-test  flagged-when-evidence-dropped:" bad "(expect 1)")
    (println "self-test  silent-when-message-recorded:" good "(expect 0)")
    (println "self-test  silent-on-constant-type:" constant "(expect 0)")
    (if (and (= 1 bad) (zero? good) (zero? constant))
      (do (println "self-test OK — the check discriminates in both directions") 0)
      (do (println "self-test FAILED — a check that cannot fail is theatre") 1))))

(defn -main []
  (if self-test?
    (set! (.-exitCode js/process) (self-test!))
    (let [root (.cwd js/process)
          orgs (path/join root "orgs")
          repos (cons [root "root"]
                      (when (fs/existsSync orgs)
                        (for [org (sort (fs/readdirSync orgs))
                              :let [od (path/join orgs org)]
                              :when (try (.isDirectory (fs/statSync od)) (catch :default _ false))
                              r (sort (fs/readdirSync od))
                              :let [rd (path/join od r)]
                              :when (fs/existsSync (path/join rd ".git"))]
                          [rd (str org "/" r)])))
          results (doall (map (fn [[d l]] (scan-repo d l)) repos))
          scanned (reduce + 0 (map :scanned results))
          all (mapcat (fn [r] (map #(assoc % :repo (:label r)) (:findings r))) results)]
      ;; Evidence floor. A repo whose listing FAILED is not a repo with no
      ;; findings, and the two used to print the same number.
      (let [broken (filterv :error results)
            skipped (filterv :skipped results)]
        (println (str "SCANNED\t" scanned "/" (count results) " repo(s)"
                      (when (seq skipped) (str "\tNOT-A-REPO\t" (count skipped)))
                      (when (seq broken) (str "\tUNREADABLE\t" (count broken)))))
        (doseq [b (take 3 broken)]
          (binding [*print-fn* *print-err-fn*]
            (println (str "  unreadable: " (:label b) " — " (:error b)))))
        (when (or (zero? scanned) (seq broken))
          (binding [*print-fn* *print-err-fn*]
            (println (str "CANNOT ANSWER — " (count broken) " repo(s) could not be listed"
                          " and " scanned " file(s) were read."
                          " Refusing to report clean on a partial scan.")))
          (set! (.-exitCode js/process) 2)))
      (doseq [f (sort-by (juxt :repo :file :line) all)]
        (if findings?
          (println (str "FINDING\twarn\terror-provenance:" (:repo f) ":" (:file f) ":" (:line f)
                        "\tclassifies an exception and records no message/body/detail: "
                        (:snippet f)))
          (println (str "  " (:repo f) " " (:file f) ":" (:line f) "  " (:snippet f))))))))

(-main)
