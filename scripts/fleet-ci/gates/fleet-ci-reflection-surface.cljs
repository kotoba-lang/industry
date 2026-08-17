#!/usr/bin/env nbb
;; fleet-ci-reflection-surface.cljs — a gate whose failure reaches nobody.
;;
;; WHERE A RED GATE SURFACES. fleet-ci writes a signed receipt to manifest/fleet-ci.edn and,
;; for failures only, opens a Radicle issue. That issue is the ONLY thing that goes looking
;; for a person — the ledger is append-only and nobody reads it proactively. Commit statuses
;; were dropped on 2026-07-26 with the move off the GitHub API, so there is no second surface.
;;
;; And `rad-issue-for-failure!` needs the repo to have a RID in manifest/repos.edn's
;; `:manifest.repos/rad-rids`. Without one it logs "no RID registered … skipped" into a
;; launchd job's stdout and opens nothing.
;;
;; So a repo can be added to gates.edn, run real checks, go red, and **nothing happens**.
;; Measured 2026-08-17 by this gate: of 169 gate entries covering 132 distinct repos,
;; **41 repos have no reflection surface** — including network-isekai, added to the matrix
;; that same day, and `com-junkawasaki/root`, the superproject itself.
;;
;; This gate does not fix those 41. It stops the number growing: a repo newly added to
;; gates.edn must either have a RID, or be named in the baseline below with a date and a
;; reason. That is the same ratchet shape the workspace already uses for docs-edn-only and
;; the verify-* detectors, and it is chosen over a hard failure for the documented reason
;; that a gate which is permanently red is as uninformative as one that never fails.
;;
;; Node side: npx nbb fleet-ci-reflection-surface.cljs <dir>
;;            <dir> first — tick.cljs passes the extracted tree as argument one.

(ns fleet-ci.gates.fleet-ci-reflection-surface
  (:require ["node:fs" :as fs]
            ["node:path" :as path]
            [cljs.reader :as reader]
            [clojure.string :as str]))

(def args (vec *command-line-args*))
(def root (or (first (remove #(str/starts-with? % "--") args)) "."))

;; ---------------------------------------------------------------------------
;; BASELINE — the 41 gate repos that had no reflection surface on 2026-08-17.
;;
;; Recorded rather than hidden so the count cannot quietly grow, and so that removing an
;; entry is a visible act. Every one of these can go red today and tell nobody.
;;
;; HOW TO CLEAR AN ENTRY: register the repo with Radicle and put its RID in
;; manifest/repos.edn `:manifest.repos/rad-rids`. NOT all of these should be cleared that
;; way — seeding a PRIVATE repo to Radicle is a disclosure decision, not a CI chore, and
;; several of these are private (network-isekai is private, 82 MB). For those the honest
;; options are a different reflection surface or an explicit decision that the receipt
;; ledger is enough; both are the owner's call, which is why this gate reports rather than
;; demands.
;;
;; WHO IS EXPECTED TO SOLVE IT: whoever adds the next gate to one of these repos should ask
;; where its failure goes before adding it.
(def private-on-github
  "PRIVATE repos. **Owner decision 2026-08-18: 「private はひとまず github のままで ok」** —
   they are not seeded to Radicle, so they will never acquire a RID and these entries are
   permanent, not pending work.

   That leaves them with no reflection surface at all: fleet-ci writes commit statuses to
   nothing (removed 2026-07-26) and opens Radicle issues for RIDs that will not exist. A red
   gate on one of these lands a signed receipt in manifest/fleet-ci.edn and stops there.
   `com-junkawasaki/root` — the most-gated repo in the matrix — is one of them.

   This is recorded as a known, decided gap rather than solved, because giving these a
   surface means either a GitHub token in the launchd path (which 2026-07-26 removed on
   purpose) or a different channel entirely. Both are the owner's call."
  #{;; added 2026-08-18 by this gate catching it in the act: another session put a gate
    ;; on it while this change was in flight, and it is private, so the owner decision
    ;; above applies unchanged.
    "net-kotobase/control-plane"
    "com-junkawasaki/org-spirit-in-physics-comics"
    "com-junkawasaki/root"
    "kotoba-lang/kotobase-protocol-core"
    "network-awai/cloud-itonami"
    "network-awai/cloud-murakumo"
    "network-awai/club-shinshi-app"
    "network-awai/network-isekai"})

(def pending-registration
  "PUBLIC repos with no RID. No disclosure question — these can simply be registered, and
   each one that is clears itself from this set (the gate reports it as RESOLVED).

   Measured 2026-08-18. Seven of them were renamed in this set on that date, not fixed: the
   gate used to key on `orgs/<org>/<project-name>` and now keys on west's `path:`, so
   `cloud-itonami/cloud-itonami-gftd-audio-actor` — a repo that does not exist — became
   `cloud-itonami/gftd-audio-actor`, which does."
  #{;; added 2026-08-18: gated by another session while this change was in flight.
    ;; Both public, so registering a RID clears them — ordinary work, no decision.
    "cloud-itonami/keihi"
    "cloud-itonami/shiharai-actor"
    "cloud-itonami/gftd-audio-actor"
    "cloud-itonami/gftd-avatar-actor"
    "cloud-itonami/gftd-illust-actor"
    "cloud-itonami/gftd-motion-actor"
    "cloud-itonami/gftd-rig-actor"
    "cloud-itonami/gftd-sculpt-actor"
    "cloud-itonami/gftd-voice-actor"
    "cloud-itonami/kenbun"
    "cloud-itonami/kintai"
    "cloud-itonami/sakkyokuka"
    "cloud-itonami/tehai"
    "kotoba-lang/columnar"
    "kotoba-lang/datalog"
    "kotoba-lang/datom-source"
    "kotoba-lang/dev-protobuf"
    "kotoba-lang/governor"
    "kotoba-lang/inga"
    "kotoba-lang/io-ipld-car"
    "kotoba-lang/kotobase-block-codec"
    "kotoba-lang/kotobase-lake"
    "kotoba-lang/kotobase-projection"
    "kotoba-lang/kotobase-shard-index"
    "kotoba-lang/kotobase-storage"
    "kotoba-lang/kotobase-storage-pack"
    "kotoba-lang/org-apache-arrow"
    "kotoba-lang/org-apache-avro"
    "kotoba-lang/org-apache-parquet"
    "kotoba-lang/org-ietf-csv"
    "kotoba-lang/org-ietf-nfs"
    "kotoba-lang/org-ietf-oncrpc"
    "kotoba-lang/org-ietf-xdr"
    "kotoba-lang/org-ietf-zstd"
    "kotoba-lang/provider-incidence"
    "kotoba-lang/provider-transport"
    "kotoba-lang/sigv4"
    "kotoba-lang/taxlaw"
    "kotoba-lang/tech-ipfs-specs-unixfs"
    "kotoba-lang/ws-valueflo-algorithms"
    "kotoba-lang/ws-valueflo-vocabulary"})

(def baseline-without-surface
  "The union: every gated repo known on 2026-08-18 to have nowhere to report a failure."
  (into private-on-github pending-registration))

;; Separately, 3 of the 169 gate ENTRIES cannot be resolved to an `<org>/<name>` pair by the
;; rule above (no `:org` in gates.edn and no matching `- name:` / `remote:` pair in west.yml).
;; They are counted and printed on the SCANNED line rather than silently dropped — a repo
;; this gate cannot name is a repo it cannot vouch for either way.

(defn- read-edn [p]
  (let [f (path/join root p)]
    (when (fs/existsSync f)
      (reader/read-string (str (fs/readFileSync f "utf8"))))))

(defn- rad-rids []
  (let [edn (read-edn "manifest/repos.edn")
        blob (:manifest.repos/rad-rids (first edn))]
    (cond
      (string? blob) (reader/read-string blob)
      (map? blob) blob
      :else nil)))

(defn- org-of
  "gates.edn does not carry :org for west projects — it is resolved from west.yml's remote,
   the same way tick.cljs resolves it, so the two cannot disagree about which repo a gate
   names."
  [west n]
  (when-let [m (re-find (re-pattern (str "- name: " n "\\s*\\n\\s+remote: ([a-z0-9-]+)")) west)]
    (second m)))

(defn- west-path-of
  "The project's checkout path — `orgs/<org>/<repo>` — which is what rad-rids is keyed on.

   NOT `orgs/<org>/<project-name>`. west lets `repo-path:` differ from `name:`, and 59 of
   4,191 projects do (measured 2026-08-18). Keying on the name asks about a repo that does
   not exist; 36 projects have a RID under the path key and none under the name key, so the
   name-keyed question answers \"no reflection surface\" for repos that have one.
   tick.cljs's `west-path` does the same lookup — the two must not disagree about which
   string identifies a repo."
  [west n]
  (when-let [m (re-find (re-pattern (str "- name: " n "\\s*\\n(?:\\s+[a-z-]+: [^\\n]*\\n)*?\\s+path: ([^\\n]+)\\n")) west)]
    (str/trim (second m))))

(let [gates (read-edn "scripts/fleet-ci/gates.edn")
      rids (rad-rids)
      west-file (path/join root "manifest/west.yml")
      west (when (fs/existsSync west-file) (str (fs/readFileSync west-file "utf8")))]

  ;; Any missing input means this gate cannot answer. Reporting "0 repos without a surface"
  ;; from a tree that has no gates.edn is the false pass this whole file is about.
  (when (or (nil? gates) (nil? rids) (nil? west))
    (println "FLEET-CI: missing input —"
             (str/join ", " (remove nil? [(when (nil? gates) "scripts/fleet-ci/gates.edn")
                                          (when (nil? rids) "manifest/repos.edn rad-rids")
                                          (when (nil? west) "manifest/west.yml")]))
             "— refusing to report a pass")
    (js/process.exit 90))

  (let [rows (for [g (:repos gates)
                   :let [n (:name g) o (or (:org g) (org-of west n))]]
               {:name n :org o
                ;; An explicit `:org` in gates.edn means "this is NOT a west project" — the
                ;; superproject is the documented case. Looking such an entry up in west.yml
                ;; BY NAME is wrong and not merely useless: west has a project literally
                ;; named `root` under a different org (`orgs/etzhayyim/root`), and it has a
                ;; RID. The first version of this line found that RID and reported
                ;; `com-junkawasaki/root` as having a reflection surface it does not have.
                :key (or (when-not (:org g)
                           (some-> (west-path-of west n) (str/replace #"^orgs/" "")))
                         (when o (str o "/" n)))})
        uniq (vals (into {} (map (juxt :key identity)) (filter :key rows)))
        unresolved (count (remove :key rows))
        without (sort (map :key (remove #(get rids (str "orgs/" (:key %))) uniq)))
        new-without (remove baseline-without-surface without)
        resolved (remove (set without) baseline-without-surface)]

    (println (str "SCANNED\t" (count (:repos gates)) " gate entr(ies), " (count uniq)
                  " distinct repo(s), " unresolved " unresolvable to <org>/<name>"))
    (println (str "NO-SURFACE\t" (count without) " repo(s) whose failures reach nobody"
                  " (" (count baseline-without-surface) " in the baseline)"))

    (when (seq resolved)
      (println (str "\nRESOLVED — " (count resolved)
                    " baseline repo(s) now have a reflection surface. Delete them from"
                    " `baseline-without-surface`; a stale allowlist is a lie the next"
                    " reader cannot catch:"))
      (doseq [r (sort resolved)] (println "  " r)))

    (cond
      (seq new-without)
      (do (println (str "\nFLEET-CI FAIL: " (count new-without)
                        " repo(s) are gated with no reflection surface and are NOT in the"
                        " baseline. A red gate on these opens no Radicle issue and writes no"
                        " commit status — the receipt lands in an append-only ledger and"
                        " that is all that happens."))
          (doseq [r (sort new-without)] (println "  " r))
          (println (str "\nEither register a RID in manifest/repos.edn, or add it to"
                        " `baseline-without-surface` with today's date, a reason, and who is"
                        " expected to solve it."))
          (js/process.exit 1))

      ;; A RESOLVED entry is not a failure — the surface improved — but the baseline must be
      ;; edited or it starts describing a world that no longer exists.
      (seq resolved)
      (do (println "\nFLEET-CI OK: no new repo lacks a reflection surface"
                   "(baseline needs pruning — see RESOLVED above)")
          (js/process.exit 0))

      :else
      (do (println "\nFLEET-CI OK: every gated repo either has a Radicle RID or is a"
                   "recorded, dated exception")
          (js/process.exit 0)))))
