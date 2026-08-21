#!/usr/bin/env nbb
;; rename-residue-test.cljs — proof for scripts/rename_residue.cljs.
;;
;;   nbb scripts/rename-residue-test.cljs
;;       fixture repo built from scratch + pure classify cases. Offline, no network.
;;
;;   nbb scripts/rename-residue-test.cljs --repo <dir> --base <ref> --paths a,b,c
;;       run the scanner against a real checkout. Used to replay the
;;       net-kotobase/control-plane incident against the actual repository;
;;       see the :residue-gate section of manifest/cleanup-workflow.edn.
;;
;; The fixture reproduces the *shape* of that incident rather than a snapshot of
;; it, so the test keeps working without a network round-trip: a directory
;; renamed twice, leftovers at the dead address, a build artifact that
;; .gitignore names only at its live address, and genuine WIP that must survive.
(require '[scripts.rename-residue :as rr]
         '[scripts.nbb-compat :refer [format]]
         '[clojure.string :as str]
         '[clojure.java.shell :refer [sh]])

(def node-fs (js/require "node:fs"))
(def node-os (js/require "node:os"))

(def args *command-line-args*)
(defn- opt [flag] (second (drop-while #(not= % flag) args)))

(def failures (atom 0))
(def passes (atom 0))
(defn- check [ok? label detail]
  (if ok?
    (do (swap! passes inc) (println (format "OK   %s" label)))
    (do (swap! failures inc)
        (println (format "FAIL %s — %s" label detail)))))

;; ------------------------------------------------------------ pure unit cases
;; classify/ is pure, so the incident's own evidence can be replayed as data.

(def pure-cases
  [{:label "rename source + bytes in history -> residue (the 16-file half)"
    :facts {:path "clj-edge/bench/evidence.edn" :rename-source
            {:similarity 94 :to "kotobase-api-gateway-cljs/bench/evidence.edn"
             :commit "5a6a55b"}
            :history-blob {:commit "fb4068f"}}
    :expect [:residue :rename-source-restored]}

   {:label "deleted path restored from history -> residue"
    :facts {:path "docs/old-doctrine.md" :history-blob {:commit "947d5c0"}}
    :expect [:residue :deleted-path-restored]}

   {:label "dead dir + byte-identical copy live elsewhere -> residue"
    :facts {:path "worker/util.clj" :dead-dir "worker"
            :mapped-path "gateway/util.clj" :mapped-on-base? true
            :mapped-same-content? true}
    :expect [:residue :duplicate-of-live-path]}

   {:label "dead dir + ignored at live path -> residue (the .gitignore half)"
    :facts {:path "worker/src/edge-app.generated.mjs" :dead-dir "worker/src"
            :mapped-path "kotobase-api-gateway/src/edge-app.generated.mjs"
            :mapped-ignored? true}
    :expect [:residue :ignored-artifact-at-dead-path]}

   {:label "rename source but content NOT in history -> suspect, not residue"
    :facts {:path "worker/edited.clj"
            :rename-source {:similarity 100 :to "gateway/edited.clj" :commit "04e1514"}}
    :expect [:suspect :rename-source-modified]}

   {:label "dead dir, live copy differs -> suspect (may be a real edit)"
    :facts {:path "worker/util.clj" :dead-dir "worker"
            :mapped-path "gateway/util.clj" :mapped-on-base? true
            :mapped-same-content? false}
    :expect [:suspect :edited-copy-at-dead-path]}

   {:label "dead dir only -> suspect"
    :facts {:path "worker/brand-new.clj" :dead-dir "worker"}
    :expect [:suspect :under-renamed-away-directory]}

   {:label "path never in the index, no dead ancestor -> WIP (protective case)"
    :facts {:path "feature/new-thing.cljc"}
    :expect [:wip :new-path]}

   {:label "path exists on base -> left to drop-already-landed"
    :facts {:path "README.md" :on-base? true}
    :expect [:wip :exists-on-base]}])

(println "── pure classify cases ──")
(doseq [{:keys [label facts expect]} pure-cases]
  (let [{:keys [verdict reason]} (rr/classify facts)]
    (check (= expect [verdict reason]) label (str "got " [verdict reason]))))

(println "\n── directory-move derivation ──")
(check (= ["worker" "kotobase-edge"]
          (rr/rename->dir-move ["worker/src/edge-app.cljc" "kotobase-edge/src/edge-app.cljc"]))
       "a moved directory yields a directory move" "")
(check (nil? (rr/rename->dir-move ["clj-edge/src/kotobase/cacao.cljc"
                                   "clj-edge/src/kotobase/edge_cacao.cljc"]))
       "a basename-only rename condemns no directory" "")
(check (= {:live "kotobase-api-gateway/src" :hops ["kotobase-edge/src" "kotobase-api-gateway/src"]}
          (rr/resolve-live-dir {"worker/src" {:to "kotobase-edge/src"}
                                "kotobase-edge/src" {:to "kotobase-api-gateway/src"}}
                               #{"kotobase-api-gateway/src"} "worker/src"))
       "a two-hop rename chain resolves to the live directory" "")
(check (nil? (rr/resolve-live-dir {"a" {:to "b"} "b" {:to "a"}} #{"c"} "a"))
       "a rename cycle terminates instead of looping" "")

;; --------------------------------------------------------- fixture repository

(defn- g! [dir & xs]
  (let [{:keys [exit err]} (apply sh "git" "-C" dir xs)]
    (when-not (zero? exit)
      (println (format "git %s failed: %s" (pr-str xs) (str/trim (str err))))
      (js/process.exit 1))))

(defn- w! [dir path content]
  (let [f (str dir "/" path)]
    (.mkdirSync node-fs (.substring f 0 (.lastIndexOf f "/")) #js {:recursive true})
    (.writeFileSync node-fs f content)))

(defn- build-fixture! []
  (let [dir (str (.tmpdir node-os) "/rr-fixture-" (rand-int 1e9))]
    (.mkdirSync node-fs dir #js {:recursive true})
    (g! dir "init" "-q" "-b" "main")
    (g! dir "config" "user.email" "test@example.invalid")
    (g! dir "config" "user.name" "test")
    ;; r1 — the original layout
    (w! dir "worker/handler.clj" "(ns worker.handler)\n(defn handle [r] r)\n")
    (w! dir "worker/policy.clj" "(ns worker.policy)\n;; original doctrine\n")
    (w! dir "worker/src/app.cljc" "(ns app)\n(def entry :worker)\n")
    (w! dir "docs/doctrine.md" "# doctrine\n\nthe old argument\n")
    (w! dir ".gitignore" "*.log\n")
    (g! dir "add" "-A") (g! dir "commit" "-qm" "r1: original layout")
    ;; r2 — first rename, worker/ -> edge/
    (g! dir "mv" "worker" "edge")
    (g! dir "commit" "-qam" "r2: rename worker/ -> edge/")
    ;; r3 — second rename, edge/ -> gateway/, and ignore the build artifact at
    ;;      its NEW address only. This is the .gitignore asymmetry that let the
    ;;      real leftover through.
    (g! dir "mv" "edge" "gateway")
    (w! dir ".gitignore" "*.log\ngateway/src/app.generated.mjs\n")
    (g! dir "add" "-A") (g! dir "commit" "-qam" "r3: rename edge/ -> gateway/")
    ;; r4 — the doctrine is argued out: policy.clj is superseded on the live path
    ;;      and docs/doctrine.md is deleted outright (no rename involved).
    (w! dir "gateway/policy.clj" "(ns gateway.policy)\n;; superseded doctrine\n")
    (g! dir "rm" "-q" "docs/doctrine.md")
    (g! dir "commit" "-qam" "r4: supersede the policy doctrine")
    (g! dir "update-ref" "refs/remotes/origin/main" "HEAD")

    ;; The working tree as a cleanup pass would find it after the moves.
    ;; (1) residue: exact pre-rename bytes at the dead path
    (w! dir "worker/handler.clj" "(ns worker.handler)\n(defn handle [r] r)\n")
    ;; (2) residue: the superseded doctrine, byte-identical to r1..r3
    (w! dir "worker/policy.clj" "(ns worker.policy)\n;; original doctrine\n")
    ;; (2b) residue via a plain delete, no rename anywhere in the story
    (w! dir "docs/doctrine.md" "# doctrine\n\nthe old argument\n")
    ;; (3) residue: build artifact ignored at gateway/src/, not at worker/src/
    (w! dir "worker/src/app.generated.mjs" "export const entry = 'worker';\n")
    ;; (4) suspect: someone edited a stale copy — content is in nobody's history
    (w! dir "worker/src/app.cljc" "(ns app)\n(def entry :worker)\n(def extra true)\n")
    ;; (5) suspect: brand-new file created under the dead directory
    (w! dir "worker/scratch.clj" "(ns worker.scratch)\n")
    ;; (6) WIP: real work at a path that has never existed. MUST survive.
    (w! dir "feature/new_thing.cljc" "(ns feature.new-thing)\n(def v 1)\n")
    dir))

(println "\n── fixture repository ──")
(let [dir (build-fixture!)
      paths ["worker/handler.clj" "worker/policy.clj" "docs/doctrine.md"
             "worker/src/app.generated.mjs"
             "worker/src/app.cljc" "worker/scratch.clj" "feature/new_thing.cljc"]
      {:keys [results]} (rr/scan dir "origin/main" paths)
      by-path (into {} (map (juxt :path identity) results))
      expect {"worker/handler.clj"           [:residue :rename-source-restored]
              "worker/policy.clj"            [:residue :rename-source-restored]
              "docs/doctrine.md"             [:residue :deleted-path-restored]
              "worker/src/app.generated.mjs" [:residue :ignored-artifact-at-dead-path]
              "worker/src/app.cljc"          [:suspect :rename-source-modified]
              "worker/scratch.clj"           [:suspect :under-renamed-away-directory]
              "feature/new_thing.cljc"       [:wip :new-path]}]
  (doseq [[p e] expect]
    (let [{:keys [verdict reason]} (get by-path p)]
      (check (= e [verdict reason]) p (str "got " [verdict reason]))))
  (check (= 1 (count (filter #(= :wip (:verdict %)) results)))
         "exactly one candidate survives as WIP"
         (str "got " (mapv (juxt :path :verdict) results)))
  (println (format "     fixture at %s" dir)))

;; ------------------------------------------------------- real-repository mode

(when-let [repo (opt "--repo")]
  (println "\n── real repository ──")
  (let [base (or (opt "--base") "origin/main")
        paths (if-let [p (opt "--paths")]
                (str/split p #",")
                (->> (:out (sh "git" "-C" repo "ls-files" "--others" "--exclude-standard"))
                     str/split-lines (remove str/blank?)))
        {:keys [results renames truncated?]} (rr/scan repo base paths)
        {:keys [residue suspect wip]} (rr/split-verdicts results)]
    (println (format "repo=%s base=%s candidates=%d renames-seen=%d truncated=%s"
                     repo base (count paths) renames truncated?))
    (doseq [[k rows] [[:residue residue] [:suspect suspect] [:wip wip]]]
      (println (format "\n%s: %d" (name k) (count rows)))
      (doseq [r rows]
        (println (format "  %-52s %-30s %s" (:path r) (name (:reason r))
                         (or (:renamed-to r) (:mapped-path r) "")))))))

;; The summary line is machine-read by the fleet gate. It reports the number of
;; assertions that actually ran, not just the exit code — a run that resolves no
;; namespace and asserts nothing would otherwise exit 0 and read as a pass.
(println (format "\n%s — %d cases OK, %d failure%s"
                 (if (zero? @failures) "PASS" "FAIL") @passes @failures
                 (if (= 1 @failures) "" "s")))
(js/process.exit (if (zero? @failures) 0 1))
