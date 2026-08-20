(ns ipni-maturity.probe
  "Measure the IPNI publishing pipeline. No opinions — every value here is
  either an HTTP status from the live surface or a fact read out of the
  repositories, and each carries how it was obtained.

  Run:
    nbb --classpath 90-docs 90-docs/ipni-maturity/probe.cljs [--out FILE]

  Why a probe and not hand-typed inputs: a maturity score assembled from
  remembered facts measures the memory. Two of the facts below were wrong in
  this session's own notes within an hour of being written -- a router list
  read from a checkout 11 commits stale, and a claim that nothing drained the
  outbox that was true but not the first blocker. The judge gets measurements
  or it gets nothing.

  An unreachable endpoint is `:unknown`, never a zero. A zero says 'measured,
  and it is not there'; :unknown says 'could not measure', and the audit
  refuses to score it rather than counting silence as failure."
  (:require [clojure.string :as str]
            [cljs.pprint :as pp]
            ["fs" :as fs]
            ["child_process" :as cp]))

(def publisher-host "https://ipni.kotobase.net")
(def gateway-host "https://ipfs.kotobase.net")
(def indexer "https://cid.contact")

;; A CID the gateway is known to serve, used as the retrieval witness.
(def ^:private re-call-site
  "A require or use of the ipni namespaces. Matches the alias form the
  library is actually consumed through."
  "\\[ipni\\.(advertise|announce|ad|head|find|http|metadata) ")

(def witness-cid "bafkreiblkbkzpwvhg3ytykiqyjqorxvrv45sb77doxvv4anaapus6va5xe")

(defn- sh
  "Run a command, returning trimmed stdout or nil. Never throws.

  nil means the command did not answer -- a timeout included. Callers turn
  that into :unknown, never into 0. A workspace-wide grep over ~4,400
  checkouts does not finish in 60s, and the first run of this probe reported
  the two heaviest axes as unmeasured for exactly that reason. It said so,
  which is the point; the fix is a longer bound, not a default of zero."
  ([cmd] (sh cmd 60000))
  ([cmd timeout-ms]
   (try
     (str/trim (str (cp/execSync cmd #js {:encoding "utf8"
                                          :stdio #js ["pipe" "pipe" "pipe"]
                                          :timeout timeout-ms})))
     (catch :default _ nil))))

(defn- http-status
  "HTTP status as a long, or :unknown when the request could not be made."
  [url]
  (let [out (sh (str "curl -sS -o /dev/null -w '%{http_code}' --max-time 25 "
                     (pr-str url)))]
    (if (and out (re-matches #"\d{3}" out) (not= "000" out))
      (js/parseInt out 10)
      :unknown)))

(defn- http-status-following
  "Like `http-status` but follows redirects. The gateway 301s /ipfs/{cid} to
  the CID subdomain by design, so a bare status there measures the redirect,
  not whether the bytes exist."
  [url]
  (let [out (sh (str "curl -sSL -o /dev/null -w '%{http_code}' --max-time 30 "
                     (pr-str url)))]
    (if (and out (re-matches #"\d{3}" out) (not= "000" out))
      (js/parseInt out 10)
      :unknown)))

(defn- http-body [url]
  (sh (str "curl -sS --max-time 25 " (pr-str url))))

(defn- show-on-main
  "A file as it exists on the repo's own default branch, or nil.

  NOT the working checkout. Measured 2026-08-20: reading
  kad/routing.cljc from the checkout reported one router where main has
  two, because that checkout was 11 commits behind -- and the wrong answer
  was indistinguishable from a real finding. ADR-2608136800: checkout, west
  pin and the repo's main are three different things."
  [repo-dir rel]
  (let [remote (sh (str "git -C " (pr-str repo-dir) " remote | head -1"))]
    (when (and remote (seq remote))
      (sh (str "git -C " (pr-str repo-dir) " show "
               (pr-str (str remote "/main:" rel)))))))

(defn- repo-root []
  (or (sh "git rev-parse --show-toplevel") "."))

(defn- call-sites-in
  "Call sites of the ipni namespaces, searched ONLY in the repos that declare
  the dependency.

  This is a derivation, not a shortcut. A Clojure namespace cannot be
  required without being on the classpath, and on this workspace the
  classpath comes from deps.edn / upstream-lock.json. So a repo that does not
  declare io-ipni-specs cannot contain a call site, and zero declarations
  implies zero call sites -- provably, where a grep would only fail to find
  one.

  It is also the only version of this that finishes. Three earlier attempts
  swept orgs/ directly; on 4,400 checkouts under load none completed, and
  each reported :unknown for the two heaviest axes in the rubric."
  [root repos]
  (if (empty? repos)
    {:count 0 :derived "no repo declares io-ipni-specs, so no repo can require it"}
    (let [out (sh (str "grep -rl --exclude-dir=.git --include='*.cljc' --include='*.cljs' "
                       "--include='*.clj' -E " (pr-str re-call-site) " "
                       (str/join " " (map #(pr-str (str root "/" % "/src")) repos))
                       " 2>/dev/null | wc -l")
                  300000)]
      {:count (if out (js/parseInt (str/trim out) 10) :unknown)
       :searched (vec repos)})))

(defn- dep-declaration-count
  "How many dependency manifests name io-ipni-specs.

  Enumerated from `manifest/west.yml`'s `path:` entries and stat-ed directly,
  NOT by walking orgs/. A `find` over this workspace does not finish in
  minutes -- 4,221 checkouts, and the first three versions of this function
  each timed out and reported :unknown for a heavy axis. Reading the manifest
  turns a filesystem walk into 4,221 stats."
  [root]
  (let [west (str root "/manifest/west.yml")]
    (if-not (fs/existsSync west)
      :unknown
      (let [paths (->> (str/split-lines (str (fs/readFileSync west "utf8")))
                       (keep (fn [l] (second (re-find #"^\s*path:\s*(\S+)\s*$" l))))
                       distinct)
            files (->> paths
                       (mapcat (fn [rel] [(str root "/" rel "/deps.edn")
                                          (str root "/" rel "/upstream-lock.json")]))
                       (filterv #(fs/existsSync %)))
            hit-files (filterv #(str/includes? (str (fs/readFileSync % "utf8")) "io-ipni-specs")
                               files)
            hit-repos (mapv (fn [f]
                              (-> f (str/replace (str root "/") "")
                                  (str/replace #"/(deps\.edn|upstream-lock\.json)$" "")))
                            hit-files)]
        {:hits (count hit-files) :repos (vec (distinct hit-repos))
         :manifests-read (count files) :repos-listed (count paths)}))))

(defn probe []
  (let [root (repo-root)
        orgs (str root "/orgs")
        health          (http-status (str publisher-host "/health"))
        head            (http-status (str publisher-host "/ipni/v1/head"))
        ad-serves       (http-status (str publisher-host "/ipni/v1/ad/" witness-cid))
        narrow          (http-status (str publisher-host "/ipfs/" witness-cid))
        pinning-head    (http-status "https://pinning.kotobase.net/ipni/v1/head")
        gateway-serves  (http-status-following (str gateway-host "/ipfs/" witness-cid))
        providers-body  (http-body (str indexer "/routing/v1/providers/" witness-cid))
        ;; repo-side facts
        dep-result      (dep-declaration-count root)
        consumer-repos  (if (map? dep-result) (:repos dep-result) [])
        call-result     (if (map? dep-result) (call-sites-in root consumer-repos) {:count :unknown})
        call-sites      (:count call-result)
        declared-dep    (if (map? dep-result) (:hits dep-result) :unknown)
        hamt-src        (show-on-main (str orgs "/kotoba-lang/io-ipni-specs")
                                      "src/ipni/hamt.cljc")
        hamt-impl       (cond
                          (nil? hamt-src) :unknown
                          (str/includes? hamt-src ":not-yet-implemented") 0
                          :else 1)
        routers-src     (show-on-main (str orgs "/kotoba-lang/io-libp2p-specs-kad-dht")
                                      "src/kad/routing.cljc")]
    {:measured-at (or (sh "date -u +%Y-%m-%dT%H:%M:%SZ") "unknown")
     :method {:http "live HTTP status, curl, 25s timeout"
              :single-file "git show <remote>/main:<path> — the repo's branch, not the checkout"
              :dependencies "every deps.edn / upstream-lock.json named by manifest/west.yml, read directly -- :write/dep-scan reports how many were read, so 0 hits cannot be read as 0 looked at"
              :call-sites "derived: a namespace cannot be required without its dependency on the classpath, so zero declarations implies zero call sites"}
     :publisher/health health
     :publisher/head head
     :publisher/ad-bytes ad-serves
     :publisher/narrow-surface narrow
     :publisher/credentialed-alternative pinning-head
     :gateway/serves gateway-serves
     :indexer/knows-witness (cond
                              (nil? providers-body) :unknown
                              (str/includes? providers-body "Providers") 1
                              :else 0)
     :indexer/lists-kotobase (cond
                               (nil? providers-body) :unknown
                               (str/includes? providers-body "kotobase") 1
                               :else 0)
     :read/cid-contact-router (cond
                                (nil? routers-src) :unknown
                                (str/includes? routers-src "cid.contact") 1
                                :else 0)
     :write/call-sites call-sites
     :write/declared-dependency declared-dep
     :write/dep-scan (if (map? dep-result) (dissoc dep-result :hits) :unknown)
     :entries/hamt hamt-impl}))

(defn -main [& args]
  (let [argv (vec (or (seq args) (seq *command-line-args*) []))
        out (loop [a argv] (cond (empty? a) nil
                                 (= "--out" (first a)) (second a)
                                 :else (recur (rest a))))
        p (probe)]
    (if out
      (do (fs/writeFileSync out (with-out-str (pp/pprint p)))
          (println "wrote" out))
      (pp/pprint p))))

(apply -main *command-line-args*)
