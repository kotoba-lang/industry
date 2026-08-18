#!/usr/bin/env nbb
;; scripts/annex-custody-verify.cljs — proves that annexed content is actually
;; held somewhere other than this machine, and that the remote copy can really
;; be read back.
;;
;; Why this exists (ADR-2607252000): SHIRO & PICO ep01's panels + motion comic
;; were lost even though ASSETS-DATALAD.md asserted for weeks that they lived
;; in a DataLad dataset. The dataset directory did not exist, the IPFS pins had
;; been garbage-collected (all four public gateways returned 504), and the B2
;; bucket the doc named was not reachable with any surviving key. Nothing ever
;; tested the claim, so the gap stayed invisible until the content was needed —
;; at which point it was unrecoverable.
;;
;; The lesson is not "take backups" (a backup was claimed) but "a backup that
;; is never restored is a rumour". This script turns the claim into a check:
;;
;;   1. whereis  — every annexed file must have >= 1 copy on a remote that is
;;                 not this repo. A file whose only copy is `here` is one disk
;;                 failure from gone, which is exactly ep01's end state.
;;   2. fsck     — a deterministic sample is verified with
;;                 `git annex fsck --from <remote>`, which downloads the
;;                 remote's copy and checksums it. This is what distinguishes a
;;                 real backup from a location-tracking entry that points at
;;                 bytes nobody has confirmed in months. It is non-destructive:
;;                 unlike drop -> get it never removes the local copy, so a
;;                 failing remote cannot cost us the last surviving bytes.
;;
;; Sampling is deterministic (evenly-spaced by index, not random) so CI runs are
;; reproducible and a failure can be re-run verbatim. Math.random is also
;; unavailable in some of this repo's script hosts.
;;
;; Usage:
;;   nbb --classpath ".:scripts/nbb_compat" scripts/annex-custody-verify.cljs
;;   nbb ... scripts/annex-custody-verify.cljs --names shiropico,m365-archive
;;   nbb ... scripts/annex-custody-verify.cljs --sample 3     ; fsck 3 files/repo (0 = skip fsck)
;;   nbb ... scripts/annex-custody-verify.cljs --report out.edn
;;
;; Exit 1 if any annexed file has no off-machine copy, or any sampled file
;; fails to verify from its remote. Projects that are not checked out locally
;; are reported as :skipped, not failed — `west update` scope is a separate
;; concern from custody.

(require '[scripts.nbb-compat :refer [slurp spit sh exit getenv getenv-all]]
         '[clojure.string :as str])

(def fs (js/require "node:fs"))
(def root (str/trim (:out (sh "git" "rev-parse" "--show-toplevel"))))

(defn- fail [msg]
  (binding [*out* *err*] (println msg))
  (exit 1))

;; ---------- west.yml projects ----------

(defn projects
  "west.yml projects marked `userdata: datalad: true`. Same block-scan approach
   as manifest/west_annex.cljs, including its (?![\\s\\S]) end-of-input idiom —
   JS regex has no \\z, and a literal 'z' terminator silently truncates any
   block whose text contains one."
  []
  (->> (re-seq #"(?ms)^\s+- name:\s*([^\n]+)(.*?)(?=^\s+- name:|(?![\s\S]))"
               (slurp (str root "/manifest/west.yml")))
       (keep (fn [[_ nm block]]
               (when (re-find #"(?m)^\s+datalad:\s*true\s*$" block)
                 (when-let [[_ path] (re-find #"(?m)^\s+path:\s*([^\s]+)\s*$" block)]
                   {:name (str/trim nm)
                    :path path
                    :remote (or (second (re-find #"(?m)^\s+annex-remote:\s*([^\s]+)\s*$" block))
                                "b2")}))))))

;; ---------- credentials ----------

(defn resolve-b2!
  "git-annex's S3 remote reads AWS_* from the environment. Delegates to
   scripts/b2-creds.cljs (env -> 1Password -> Keychain) exactly as
   manifest/west_annex.cljs does, so there is one resolution path, not two."
  []
  (if (seq (getenv "AWS_ACCESS_KEY_ID"))
    {}
    (let [script (str root "/scripts/b2-creds.cljs")
          cp (str root ":" root "/scripts/nbb_compat:"
                  root "/orgs/kotoba-lang/secret-resolve/src")
          {:keys [exit out]} (sh "nbb" "--classpath" cp script "--json")]
      (if (zero? exit)
        (try (js->clj (.parse js/JSON out)) (catch :default _ {}))
        {}))))

;; ---------- per-repo audit ----------

(defn- local-uuid [dir]
  (str/trim (:out (sh "git" "config" "annex.uuid" {:cwd dir}))))

(defn whereis
  "Parses `git annex whereis --json` (one JSON object per line).

   git-annex reports two separate location sets and conflating them lies in both
   directions. `whereis` holds locations annex will rely on; `untrusted` holds
   locations that may hold the content but whose state annex cannot vouch for —
   notably importtree remotes, whose objects can be replaced out-of-band, so a
   recorded copy is not proof the same bytes are still there.

   An earlier version read only `whereis` and therefore told us 272 imported
   files 'exist ONLY on this machine' when each in fact had an untrusted remote
   copy. That is a different (milder) problem than having no copy at all, and
   printing the harsher one erodes trust in the check."
  [dir]
  (let [{:keys [out]} (sh "git" "annex" "whereis" "--json" {:cwd dir})]
    (->> (str/split-lines (or out ""))
         (remove str/blank?)
         (keep (fn [line]
                 (try
                   (let [m (js->clj (.parse js/JSON line))
                         off #(remove (fn [x] (true? (get x "here"))) %)
                         trusted (off (get m "whereis" []))
                         untrusted (off (get m "untrusted" []))]
                     (when-let [f (get m "file")]
                       {:file f
                        :copies (count trusted)
                        :untrusted (count untrusted)
                        :on (set (map #(get % "description") trusted))
                        :where (mapv #(get % "description") (concat trusted untrusted))}))
                   (catch :default _ nil)))))))

(defn- sample-indices
  "Evenly-spaced indices — deterministic so a CI failure reproduces exactly."
  [total n]
  (if (or (zero? total) (zero? n))
    []
    (let [n (min n total)
          step (max 1 (quot total n))]
      (->> (range 0 total step) (take n) vec))))

(defn- classify-fsck-error
  "Distinguishes \"the bytes are bad/gone\" from \"we lack the key to look\".
   Both leave custody unproven and both must fail the check — an unverifiable
   backup is precisely what ep01 had — but they need different fixes (restore
   vs. provision a key), so they are not collapsed into one message."
  [msg]
  (if (re-find #"(?i)gpg|decrypt|公開鍵|復号" (str msg))
    :key-unavailable
    :unreadable))

(defn fsck-sample!
  "Verifies the remote's copy by downloading + checksumming it. Non-destructive:
   never drops the local copy, so a bad remote cannot destroy the last bytes."
  [dir remote files env]
  (reduce
   (fn [acc f]
     (let [{:keys [exit err out]} (sh "git" "annex" "fsck" "--from" remote "--quiet" f
                                      {:cwd dir :env env})
           msg (str/trim (str (or err "") (or out "")))]
       (if (zero? exit)
         (update acc :ok conj f)
         (update acc :failed conj {:file f :error msg :kind (classify-fsck-error msg)}))))
   {:ok [] :failed []}
   files))

(defn creds-health
  "Every git-annex special remote whose credentials are stored LOCALLY, and
  whether that stored value is usable.

  Added 2026-08-10 after `xyz.farcaster.fnames` spent days unable to reach B2.
  The B2 key was fine — valid, correctly scoped, and working from every other
  dataset. What was broken was `.git/annex/creds/<uuid>`: **2 bytes, zero
  fields.** git-annex signed with nothing and B2 answered 403 to reads and
  writes alike, which was recorded as `B2 は account 全体で 403` — a diagnosis
  about the account, from evidence that was entirely local.

  An empty credential is indistinguishable from a revoked key at the HTTP
  layer, so this cannot be inferred from the failure; it has to be looked at.
  The file is two whitespace-separated fields, so `< 2 fields` is the whole
  test — and it is worth having precisely because it is that cheap next to the
  days the wrong diagnosis cost.

  Never reads the VALUES, only their shape: a custody report is not a place to
  put a secret, and this one is written to disk with --report."
  [dir]
  (let [{:keys [out]} (sh "git" "config" "--get-regexp" "^remote\\..*\\.annex-uuid" {:cwd dir})]
    (->> (str/split-lines (or out ""))
         (remove str/blank?)
         (keep (fn [line]
                 (let [[k uuid] (str/split (str/trim line) #"\s+")
                       rname (second (re-find #"^remote\.(.+)\.annex-uuid$" (or k "")))
                       f (str dir "/.git/annex/creds/" uuid)]
                   (when (and rname uuid (.existsSync fs f))
                     (let [fields (->> (str/split (str (.readFileSync fs f "utf8")) #"\s+")
                                       (remove str/blank?)
                                       count)]
                       {:remote rname :fields fields :usable? (>= fields 2)})))))
         vec)))

(defn audit-project [{:keys [name path remote]} env sample-n]
  (let [dir (str root "/" path)]
    (if-not (.existsSync fs dir)
      {:name name :status :skipped :reason "not checked out locally"}
      (let [uuid (local-uuid dir)]
        (if (str/blank? uuid)
          {:name name :status :skipped :reason "not a git-annex repo (no annex.uuid)"}
          (let [entries (whereis dir)
                total (count entries)
                creds (creds-health dir)
                broken-creds (filterv #(not (:usable? %)) creds)
                at-risk (filter #(and (zero? (:copies %)) (zero? (:untrusted %))) entries)
                only-untrusted (filter #(and (zero? (:copies %)) (pos? (:untrusted %))) entries)
                ;; Only fsck files the *configured* remote actually claims —
                ;; sampling a file that lives on some other remote makes the
                ;; check fail for a bookkeeping reason rather than a custody one.
                candidates (->> entries
                                (filter #(contains? (:on %) (str "[" remote "]")))
                                (mapv :file))
                idxs (sample-indices (count candidates) sample-n)
                sampled (mapv #(nth candidates %) idxs)
                fsck (if (seq sampled)
                       (fsck-sample! dir remote sampled env)
                       {:ok [] :failed []})]
            {:name name :path path :remote remote
             :status (if (or (seq at-risk) (seq only-untrusted) (seq (:failed fsck))
                             (seq broken-creds))
                       :fail :ok)
             :annexed total
             :broken-creds broken-creds
             :at-risk (mapv :file at-risk)
             :only-untrusted (mapv :file only-untrusted)
             :sampled sampled
             :fsck-failed (:failed fsck)}))))))

;; ---------- main ----------

(defn- arg [argv k]
  (second (drop-while #(not= % k) argv)))

(let [argv (vec *command-line-args*)
      ;; CI passes an empty string for an unset workflow input; treating that as
      ;; a filter set of #{""} would match nothing and fail with a misleading
      ;; "no projects" error on every scheduled run.
      wanted (let [v (arg argv "--names")]
               (when-not (str/blank? v)
                 (set (remove str/blank? (str/split v #",")))))
      sample-n (let [n (js/parseInt (or (arg argv "--sample") "1") 10)]
                 (if (js/isNaN n) 1 n))
      report-path (arg argv "--report")
      targets (cond->> (projects) wanted (filter #(contains? wanted (:name %))))]
  ;; `--list-projects` keeps CI from having to re-implement the west.yml scan as
  ;; an inline one-liner (quoting that inside YAML is a reliable way to ship a
  ;; broken workflow).
  ;; `--self-test` proves the credential detector can FAIL. Without it the
  ;; check is a line that always prints nothing, which is the shape a decorative
  ;; gate has: it passed on the day the credential was empty too, because it did
  ;; not exist yet, and it would go on passing if the field-count logic were
  ;; inverted. The fixture is built here rather than mocked so it exercises the
  ;; same `git config` parse the real path uses.
  (when (some #{"--self-test"} argv)
    (let [dir (str (or (.-TMPDIR (.-env js/process)) "/tmp") "/annex-creds-selftest")]
      (sh "rm" "-rf" dir {})
      (.mkdirSync fs (str dir "/.git/annex/creds") #js {:recursive true})
      (sh "git" "init" "-q" dir {})
      (sh "git" "config" "remote.emptycreds.annex-uuid" "aaaa-empty" {:cwd dir})
      (sh "git" "config" "remote.goodcreds.annex-uuid" "bbbb-good" {:cwd dir})
      (.writeFileSync fs (str dir "/.git/annex/creds/aaaa-empty") "\n")
      (.writeFileSync fs (str dir "/.git/annex/creds/bbbb-good") "KEYID SECRETVALUE\n")
      (let [health (creds-health dir)
            by-name (into {} (map (juxt :remote identity)) health)
            empty-flagged? (false? (:usable? (get by-name "emptycreds")))
            good-passed? (true? (:usable? (get by-name "goodcreds")))]
        (sh "rm" "-rf" dir {})
        (println (str "self-test: remotes=" (count health)
                      " empty-flagged=" empty-flagged?
                      " good-passed=" good-passed?))
        (when-not (and empty-flagged? good-passed? (= 2 (count health)))
          (fail "self-test 失敗: creds-health が空の credential を検出できていません。"))
        (println "self-test ok")
        (exit 0))))
  (when (some #{"--list-projects"} argv)
    (doseq [{:keys [name path]} targets] (println (str name " " path)))
    (exit 0))
  (when (empty? targets)
    (fail "対象となる DataLad project がありません（west.yml の userdata.datalad: true を確認）。"))
  (let [env (merge (getenv-all) (resolve-b2!))
        results (mapv #(audit-project % env sample-n) targets)
        failed (filter #(= :fail (:status %)) results)
        skipped (filter #(= :skipped (:status %)) results)
        ok (filter #(= :ok (:status %)) results)]
    (println (str "annex-custody-verify: projects=" (count results)
                  " ok=" (count ok) " fail=" (count failed) " skipped=" (count skipped)
                  " (sample=" sample-n "/repo)"))
    (doseq [r results]
      (case (:status r)
        :skipped (println (str "  - " (:name r) ": SKIP (" (:reason r) ")"))
        :ok (println (str "  - " (:name r) ": OK — " (:annexed r) " annexed, all with off-machine copies"
                          (when (seq (:sampled r))
                            (str "; verified from " (:remote r) ": " (str/join ", " (:sampled r))))))
        :fail (do
                (println (str "  - " (:name r) ": FAIL — " (:annexed r) " annexed"))
                (when (seq (:broken-creds r))
                  (println (str "      " (count (:broken-creds r))
                                " remote(s) have an UNUSABLE stored credential — git-annex will"
                                " sign with nothing and the remote will answer 403/401, which"
                                " reads as a revoked key rather than a local file problem:"))
                  (doseq [c (:broken-creds r)]
                    (println (str "        remote " (:remote c) ": .git/annex/creds/<uuid> has "
                                  (:fields c) " field(s), expected 2")))
                  (println (str "      直し方: AWS_ACCESS_KEY_ID/AWS_SECRET_ACCESS_KEY を与えて"
                                " `git annex enableremote <remote>`")))
                (when (seq (:at-risk r))
                  (println (str "      " (count (:at-risk r))
                                " file(s) exist ONLY on this machine (no off-machine copy at all):"))
                  (doseq [f (take 10 (:at-risk r))] (println (str "        " f)))
                  (when (> (count (:at-risk r)) 10)
                    (println (str "        ... +" (- (count (:at-risk r)) 10) " more"))))
                (when (seq (:only-untrusted r))
                  (println (str "      " (count (:only-untrusted r))
                                " file(s) have ONLY an untrusted copy (importtree remote — its"
                                " objects can be replaced out-of-band, so presence is not proof):"))
                  (doseq [f (take 5 (:only-untrusted r))] (println (str "        " f)))
                  (when (> (count (:only-untrusted r)) 5)
                    (println (str "        ... +" (- (count (:only-untrusted r)) 5) " more"))))
                (doseq [{:keys [file error kind]} (:fsck-failed r)]
                  (println (str "      "
                                (if (= kind :key-unavailable)
                                  "custody UNPROVEN (remote is encrypted and the key is unavailable here): "
                                  "remote copy UNREADABLE: ")
                                file))
                  (doseq [l (take 3 (remove str/blank? (str/split-lines (str error))))]
                    (println (str "        " l)))))))
    (when report-path
      (spit report-path (str (pr-str {:annex-custody/results results}) "\n"))
      (println (str "report -> " report-path)))
    (if (seq failed)
      (do (println "annex-custody-verify: FAIL") (exit 1))
      (println "annex-custody-verify: OK"))))
