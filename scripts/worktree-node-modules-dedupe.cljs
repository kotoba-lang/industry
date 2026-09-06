#!/usr/bin/env nbb
;; scripts/worktree-node-modules-dedupe.cljs — re-install a worktree's node_modules
;; through the pnpm content-addressable store so N worktrees of one repo share
;; one copy of every package on disk.
;;
;; Why (ADR-2609061800, measured 2026-09-06): 103 live worktrees carried their
;; own npm-installed node_modules, 12.4 GiB in total, on a disk with 4.7 GiB
;; free; 82 of them were resident bots of ONE repo (app-hyakka), each holding
;; the same ~270 MB. pnpm on APFS installs by clonefile from
;; ~/Library/pnpm/store — `du` still reports the full size (clones are not
;; hardlinks), but `df` moves by ~1 MB per worktree. Measure with df, not du.
;;
;; What it does per worktree (only those with package-lock.json AND node_modules):
;;   1. `pnpm import`            → pnpm-lock.yaml from the npm lockfile (no network)
;;   2. `.npmrc` node-linker=hoisted → npm-shaped flat layout, so shadow-cljs and
;;                                  friends resolve exactly as before
;;   3. add both to <repo>/.git/info/exclude → detached/old worktrees stay clean
;;      (tracked files are unaffected by exclude, so once main carries them
;;      nothing changes for fresh worktrees)
;;   4. rm -rf node_modules; pnpm install --frozen-lockfile --prefer-offline
;;   5. floor check: node_modules/.bin has the same entries it had before
;;
;; Never touches a worktree that is busy (process cwd inside it, or named on
;; a command line), locked, or whose repo cannot be found. Dry-run by default.
;;
;;   nbb scripts/worktree-node-modules-dedupe.cljs [--root <dir>] [--repo orgs/<org>/<repo>] [--limit N] [--apply] [--force]
;;   exit 0 = answered, 1 = a step failed, 2 = could not answer (lsof / pnpm missing, nothing scanned)
(require '[clojure.string :as str])

(def fs (js/require "node:fs"))
(def path (js/require "node:path"))
(def cp (js/require "node:child_process"))
(def os (js/require "node:os"))

(def argv (vec (.slice (.-argv js/process) 2)))
(defn opt [flag default] (let [i (.indexOf argv flag)] (if (neg? i) default (nth argv (inc i) default))))
(defn flag? [f] (some #(= % f) argv))
(def root (.resolve path (opt "--root" (.cwd js/process))))
(def only-repo (opt "--repo" nil))
(def limit (js/parseInt (opt "--limit" "100000") 10))
(def apply? (flag? "--apply"))
(def receipt (.join path (.homedir os) ".gftd" "worktree-node-modules-dedupe.log"))

(defn sh
  ([cmd] (sh cmd nil))
  ([cmd cwd]
   (try {:out (str (.execSync cp cmd #js {:encoding "utf8" :cwd (or cwd root) :timeout 600000
                                          :maxBuffer 64000000 :stdio #js ["pipe" "pipe" "pipe"]}))
         :exit 0}
        (catch :default e
          {:out (str (or (.-stdout e) "")) :err (str (or (.-stderr e) ""))
           :exit (cond (= "ETIMEDOUT" (.-code e)) 124 :else (or (.-status e) 1))}))))
(defn q [s] (str "'" (str/replace s "'" "'\\''") "'"))
(defn exists? [p] (try (.existsSync fs p) (catch :default _ false)))
(defn ls-dirs [p]
  (try (->> (.readdirSync fs p #js {:withFileTypes true}) (filter #(.isDirectory %)) (map #(.-name %)) vec)
       (catch :default _ [])))
(defn df-free
  "Free KB on the volume that holds p. `df /` on macOS answers for the sealed
  system snapshot, not the Data volume the worktrees live on — ask for the path."
  [p] (let [l (last (str/split-lines (str/trim (:out (sh (str "df -k " (q p)))))))] (js/parseInt (nth (str/split l #"\s+") 3) 10)))

(defn repos []
  (for [org (ls-dirs (.join path root "orgs")) r (ls-dirs (.join path root "orgs" org))
        :let [d (.join path root "orgs" org r) rel (str "orgs/" org "/" r)]
        :when (and (exists? (.join path d ".git" "worktrees")) (or (nil? only-repo) (= rel only-repo)))]
    {:dir d :rel rel}))

(defn worktrees [repo]
  (->> (str/split (:out (sh "git worktree list --porcelain" (:dir repo))) #"\n\n")
       (remove str/blank?)
       (map (fn [b] (let [ls (str/split-lines b) kv (fn [k] (some #(when (str/starts-with? % (str k " ")) (subs % (inc (count k)))) ls))]
                      {:path (kv "worktree") :locked (some #(str/starts-with? % "locked") ls)})))
       (drop 1)))

(defn busy-index []
  (let [lsof (sh "lsof -a -d cwd -F n 2>/dev/null") ps (sh "ps -axo command 2>/dev/null")]
    (when (and (= 0 (:exit lsof)) (not (str/blank? (:out lsof))))
      {:cwds (->> (str/split-lines (:out lsof)) (filter #(str/starts-with? % "n")) (map #(subs % 1)) set)
       :cmds (:out ps)})))
(defn busy? [idx wt] (or (some #(or (= % wt) (str/starts-with? % (str wt "/"))) (:cwds idx)) (str/includes? (:cmds idx) wt)))

(defn exclude! [repo]
  (let [f (.join path (:dir repo) ".git" "info" "exclude")
        cur (if (exists? f) (str (.readFileSync fs f "utf8")) "")
        want ["pnpm-lock.yaml" ".npmrc"]
        missing (remove #(some (fn [l] (= (str/trim l) %)) (str/split-lines cur)) want)]
    (when (seq missing)
      (.mkdirSync fs (.dirname path f) #js {:recursive true})
      (.appendFileSync fs f (str (when-not (or (str/blank? cur) (str/ends-with? cur "\n")) "\n")
                                 "# worktree-node-modules-dedupe: pnpm files in worktrees older than main's\n"
                                 (str/join "\n" missing) "\n")))))

(defn bins [wt] (set (try (vec (.readdirSync fs (.join path wt "node_modules" ".bin"))) (catch :default _ []))))

(defn dedupe! [repo wt]
  (let [before-bins (bins wt) f0 (df-free wt)
        imp (if (exists? (.join path wt "pnpm-lock.yaml")) {:exit 0 :out "(pnpm-lock.yaml present)"} (sh "pnpm import" wt))]
    (if (not= 0 (:exit imp))
      {:ok false :step "pnpm import" :err (str/trim (or (:err imp) (:out imp)))}
      (do (when-not (exists? (.join path wt ".npmrc")) (.writeFileSync fs (.join path wt ".npmrc") "node-linker=hoisted\n"))
          (exclude! repo)
          (.rmSync fs (.join path wt "node_modules") #js {:recursive true :force true})
          (let [f-rm (df-free wt)
                ins (sh "pnpm install --frozen-lockfile --prefer-offline" wt)
                after-bins (bins wt) f1 (df-free wt)
                lost (remove after-bins before-bins)]
            ;; two deltas, reported separately: what rm gave back and what the
            ;; install took. On a busy disk the sum is noisy; the second number
            ;; is the one that says whether the store is being shared.
            (cond (not= 0 (:exit ins)) {:ok false :step "pnpm install" :err (str/trim (or (:err ins) (:out ins)))}
                  (seq lost) {:ok false :step "floor: .bin entries lost" :err (str/join "," lost)}
                  :else {:ok true :rm-freed-mb (js/Math.round (/ (- f-rm f0) 1024))
                         :install-took-mb (js/Math.round (/ (- f-rm f1) 1024))
                         :bins (count after-bins)}))))))

(defn log! [m] (try (.mkdirSync fs (.dirname path receipt) #js {:recursive true})
                    (.appendFileSync fs receipt (str (js/JSON.stringify (clj->js m)) "\n")) (catch :default _ nil)))

(defn -main []
  (when (not= 0 (:exit (sh "pnpm --version"))) (println "REFUSED: pnpm not on PATH") (.exit js/process 2))
  (let [idx (busy-index) c (atom {:scanned 0 :done 0 :skipped 0 :failed 0})]
    (when (and apply? (nil? idx)) (println "REFUSED: lsof unavailable — cannot tell idle from busy") (.exit js/process 2))
    (println (str (if apply? "APPLY" "DRY-RUN") " root=" root " store=" (str/trim (:out (sh "pnpm store path")))))
    (doseq [repo (repos) wt (worktrees repo)
            :let [p (:path wt)]
            :when (and (exists? (.join path p "package-lock.json")) (exists? (.join path p "node_modules")))
            :while (< (:done @c) limit)]
      (swap! c update :scanned inc)
      (cond
        (and (exists? (.join path p "node_modules" ".modules.yaml")) (not (flag? "--force")))
        (do (swap! c update :skipped inc) (println (str "skip  " (:rel repo) "  " p "  (already pnpm-managed; --force to redo)")))
        (:locked wt) (do (swap! c update :skipped inc) (println (str "skip  " (:rel repo) "  " p "  (locked)")))
        (and idx (busy? idx p)) (do (swap! c update :skipped inc) (println (str "skip  " (:rel repo) "  " p "  (busy)")))
        (not apply?) (do (swap! c update :done inc)
                         (println (str "WOULD " (:rel repo) "  " p "  node_modules=" (str/trim (first (str/split (:out (sh (str "du -sk " (q (.join path p "node_modules"))))) #"\t"))) "KB(du)")))
        :else (let [r (dedupe! repo p)]
                (log! (merge {:at (.toISOString (js/Date.)) :repo (:rel repo) :path p} r))
                (if (:ok r)
                  (do (swap! c update :done inc) (println (str "DONE  " (:rel repo) "  " p "  rm freed " (:rm-freed-mb r) "MB, install took " (:install-took-mb r) "MB  .bin=" (:bins r))))
                  (do (swap! c update :failed inc) (println (str "FAIL  " (:rel repo) "  " p "  " (:step r) ": " (:err r))))))))
    (let [{:keys [scanned done skipped failed]} @c]
      (println (str "SCANNED " scanned " done " done " skipped " skipped " failed " failed))
      (.exit js/process (cond (zero? scanned) 2 (pos? failed) 1 :else 0)))))
(-main)
