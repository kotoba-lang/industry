#!/usr/bin/env nbb
;; scripts/west-pull-bot.cljs — the superproject's "west pull" resident bot.
;;
;; Owner direction (2026-09-03): 「必ず review and merge, また west pull を行う
;; bot profile も組織化して」— a standing resident that performs the SYNC HALF
;; of AGENTS.md's "常に main と同期し、乖離を作らない" rule on a schedule.
;;
;; ## What one tick does (bounded, in order)
;;
;;   1. git fetch origin                       (root superproject)
;;   2. behind? = rev-list --count HEAD..origin/main
;;      - behind == 0  → record :in-sync, stop. No writes happen.
;;      - diverged (HEAD not ancestor of origin/main) → record :diverged,
;;        **stop without writing**. Reconciliation is an agent/human action
;;        (AGENTS.md: rebase 禁止、乖離解消は clean branch 経路)。
;;   3. git merge --ff-only origin/main        (the safe pull)
;;   4. west.yml changed? compare pre/post blob
;;      - changed → parse manifest/west.yml for projects whose local checkout
;;        exists, is clean, HEAD == old pin, and new pin is present locally
;;        (fast-forward safety); run `west update --fetch smart` on THOSE ONLY
;;        (never bare `west update` — AGENTS.md: 4,100 project 全部を歩かない).
;;      - dirty / ahead / pin-not-local children are SKIPPED and recorded —
;;        a bot never destroys someone's WIP (AGENTS.md 並行エージェント運用).
;;
;; ## Invariants
;;
;;   - Only the shared root checkout's OWN pull is written by step 3. Child
;;     checkouts are only moved by west update when every guard above passes.
;;   - No rebase, no force anything, no stash. Divergence = detect + stop.
;;   - Every run appends exactly one EDN map to ~/.itonami/west-pull-bot/ledger.edn
;;     (append-only event column; failures are events too).
;;   - exit 0 always when it ran (monitoring, not a gate); 2 = could not run.
;;
;; usage:
;;   nbb scripts/west-pull-bot.cljs            # one tick
;;   nbb scripts/west-pull-bot.cljs --dry-run  # measure + plan, no writes

(ns west-pull-bot
  (:require ["node:child_process" :as cp]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            [clojure.string :as str]
            [clojure.edn :as edn]))

(def home (.homedir os))
(def root (or (.-COM_JUNKAWASAKI_ROOT js/process.env)
              (str home "/github/com-junkawasaki")))
(def out-dir (path/join home ".itonami" "west-pull-bot"))
(def ledger (path/join out-dir "ledger.edn"))
(def dry-run? (boolean (some #{"--dry-run"} js/process.argv)))
(def max-children 40)                       ; bounded wave per tick

(defn- now [] (.toISOString (js/Date.)))
(defn- log! [& xs] (js/console.error (str/join " " (map str xs))))

(defn- ensure-dir! [d] (fs/mkdirSync d #js {:recursive true}))

(defn- append-ledger! [m]
  (try (ensure-dir! out-dir)
       (.appendFileSync fs ledger (str (pr-str m) "\n"))
       (catch :default e (log! "ledger append failed:" (str e)))))

(defn- sh
  "Run one command with args in cwd. Never throws. Returns {:ok? :code :out :err}."
  [cwd cmd args]
  (try
    (let [r (.spawnSync cp cmd (clj->js args)
                        #js {:encoding "utf8" :cwd cwd
                             :timeout 600000 :maxBuffer (* 64 1024 1024)})]
      {:ok? (zero? (.-status r)) :code (.-status r)
       :out (str (.-stdout r)) :err (str (.-stderr r))})
    (catch :default e {:ok? false :code -1 :out "" :err (str e)})))

(defn- git [cwd & argv] (sh cwd "git" (vec argv)))

(defn- git-ok? [cwd & argv] (:ok? (apply git cwd argv)))

(defn- git-out [cwd & argv]
  (let [r (apply git cwd argv)]
    (when (:ok? r) (str/trim (:out r)))))

;; ---------- west.yml parsing (bounded: names + pins only) ----------

(defn- parse-projects
  "Extract [{:name :path :revision}] from west.yml text. Line-oriented —
   the file is generated (never hand-written) so its shape is stable."
  [text]
  (let [lines (str/split-lines text)]
    (loop [ls lines, cur nil, acc []]
      (if-let [l (first ls)]
        (let [t (str/trim l)]
          (cond
            (str/starts-with? t "- name:")
            (recur (rest ls)
                   {:name (str/trim (subs t 7))}
                   acc)
            (and cur (str/starts-with? t "path:"))
            (recur (rest ls) (assoc cur :path (str/trim (subs t 5))) acc)
            (and cur (str/starts-with? t "revision:"))
            (recur (rest ls) (assoc cur :revision (str/trim (subs t 9))) acc)
            :else (recur (rest ls) cur
                         (if (and cur (:path cur) (:revision cur))
                           (conj acc cur) acc))))
        (if (and cur (:path cur) (:revision cur))
          (conj acc cur) acc)))))

(defn- pin-map [text]
  (into {} (map (juxt :path :revision)) (parse-projects text)))

(defn- rev->blob
  "Blob sha of <file> at <rev> (empty string when absent)."
  [rev file]
  (or (git-out root "rev-parse" (str rev ":" file)) ""))

;; ---------- child guards ----------

(defn- child-state
  "Measure one west child checkout. Returns a map; never throws."
  [project old-pin new-pin]
  (let [p (path/join root (:path project))
        exists (and (fs/existsSync p) (fs/existsSync (path/join p ".git")))]
    (cond
      (not exists) {:state :absent}
      :else
      (let [head (git-out p "rev-parse" "HEAD")
            dirty? (not (str/blank? (git-out p "status" "--porcelain")))
            new-pin-local? (and head (git-ok? p "cat-file" "-e" (str new-pin "^{commit}")))
            old-pin= (and head (= head old-pin))]
        {:state (cond
                  (not head) :unreadable
                  dirty? :dirty
                  (not old-pin=) :not-at-old-pin
                  (not new-pin-local?) :pin-not-local
                  :else :advancable)
         :head head}))))

;; ---------- main tick ----------

(defn -main []
  (let [t0 (now)
        fetch (git root "fetch" "origin" "--quiet")]
    (if-not (:ok? fetch)
      (do (log! "fetch failed:" (str/trim (:err fetch)))
          (append-ledger! {:at t0 :tick :fetch-failed :err (str/trim (:err fetch))})
          (js/process.exit 2))
      (let [head (git-out root "rev-parse" "HEAD")
            origin-main (git-out root "rev-parse" "origin/main")
            ancestor? (and head origin-main
                           (git-ok? root "merge-base" "--is-ancestor" head origin-main))
            behind (if (and head origin-main)
                     (parse-long (or (git-out root "rev-list" "--count" (str head ".." origin-main)) "0") 0)
                     0)]
        (cond
          ;; already in sync — the common, cheap case
          (and (= head origin-main) (some? head))
          (do (append-ledger! {:at t0 :tick :in-sync :head head})
              (log! "in-sync" head))

          ;; diverged — detect and STOP (no merge, no rebase, no force)
          (not ancestor?)
          (do (log! "DIVERGED from origin/main — stopping without writing")
              (append-ledger! {:at t0 :tick :diverged :head head :origin origin-main})
              (js/process.exit 0))

          ;; behind — the work case
          :else
          (let [old-yaml (rev->blob "HEAD" "manifest/west.yml")
                pre-yaml (git-out root "show" "HEAD:manifest/west.yml")
                r (if dry-run?
                    {:ok? true :out "(dry-run) would merge --ff-only" :err "" :code 0}
                    (git root "merge" "--ff-only" "origin/main"))]
            (if-not (:ok? r)
              (do (log! "ff-only merge failed (unexpected after ancestor check):" (str/trim (:err r)))
                  (append-ledger! {:at t0 :tick :merge-failed :err (str/trim (:err r))})
                  (js/process.exit 0))
              (let [post-yaml (git-out root "show" "HEAD:manifest/west.yml")
                    yaml-changed? (not= old-yaml post-yaml)
                    [old-pins new-pins] [(pin-map pre-yaml) (pin-map post-yaml)]
                    moved (->> (keys new-pins)
                               (filter #(and (contains? old-pins %)
                                             (not= (get old-pins %) (get new-pins %))))
                               (map (fn [pth]
                                      {:name pth
                                       :path pth
                                       :old-pin (get old-pins pth)
                                       :new-pin (get new-pins pth)}))
                               (into []))
                    ;; measure guards for each moved child (bounded wave)
                    states (map (fn [p] [p (child-state p (get old-pins (:path p)) (get new-pins (:path p)))])
                                (take max-children (map #(assoc % :path (:name %)) moved)))
                    advancable (filter (fn [[_ st]] (= :advancable (:state st))) states)
                    skipped (filter (fn [[_ st]] (not= :advancable (:state st))) states)
                    upd (if (or dry-run? (empty? advancable))
                          {:ok? true :out "(none)" :err ""}
                          (sh root "west" (into ["update" "--fetch" "smart"]
                                                (mapv (fn [[p _]] (:name p)) advancable))))]
                (append-ledger! {:at t0 :tick :pulled
                                 :behind behind
                                 :head-after (git-out root "rev-parse" "HEAD")
                                 :yaml-changed yaml-changed?
                                 :moved-pins (count moved)
                                 :advanced (mapv (fn [[p _]] (:name p)) advancable)
                                 :skipped (mapv (fn [[p st]] {:name (:name p) :why (:state st)}) skipped)
                                 :west-update-ok (:ok? upd)
                                 :dry-run dry-run?})
                (log! "pulled" behind "commits; yaml-changed" yaml-changed?
                      "advanced" (count advancable) "skipped" (count skipped))
                (js/process.exit 0)))))))))

(-main)
