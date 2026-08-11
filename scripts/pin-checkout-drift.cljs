;; Report repos whose west checkout differs from the manifest pin.
;;
;; Reads git refs straight off the filesystem (loose refs + packed-refs) instead
;; of spawning ~4,200 `git rev-parse` processes, so a full fleet sweep is seconds.
;;
;; Only `behind` matters for the maturity scan: a checkout that sits *before* the
;; pin measures already-landed work as absent. `ahead` is normal for actors that
;; commit on their own (yabai-actor ct-watch et al) — HEAD is the truth there.
;;
;;   nbb --classpath ".:scripts/nbb_compat" scripts/pin-checkout-drift.cljs --root <dir>
;;
;; Exit code is always 0: this is a report, not a gate.

(require '[clojure.string :as str]
         '["fs" :as fs]
         '["path" :as path]
         '["child_process" :as cp])

(def argv (vec (drop 2 (js->clj js/process.argv))))

(defn arg [flag default]
  (if-let [i (first (keep-indexed #(when (= %2 flag) %1) argv))]
    (nth argv (inc i) default)
    default))

(def root (arg "--root" (str (.. js/process -env -HOME) "/github/com-junkawasaki")))
(def west (path/join root "manifest" "west.yml"))

;; ---------------------------------------------------------------- west.yml

(defn parse-west
  "west.yml is generated, so its shape is stable: `- name:` starts a project and
   `path:` / `revision:` follow at a deeper indent. A real YAML parser would be
   nicer but pulls a dependency for three keys."
  [text]
  (loop [lines (str/split-lines text) cur nil out []]
    (if-let [l (first lines)]
      (let [t (str/trim l)]
        (cond
          (str/starts-with? t "- name:")
          (recur (rest lines)
                 {:name (str/trim (subs t (count "- name:")))}
                 (if cur (conj out cur) out))

          (and cur (str/starts-with? t "path:"))
          (recur (rest lines) (assoc cur :path (str/trim (subs t (count "path:")))) out)

          (and cur (str/starts-with? t "revision:"))
          (recur (rest lines) (assoc cur :revision (str/trim (subs t (count "revision:")))) out)

          :else (recur (rest lines) cur out)))
      (if cur (conj out cur) out))))

;; ---------------------------------------------------------------- git refs

(defn slurp* [p]
  (try (str/trim (fs/readFileSync p "utf8")) (catch :default _ nil)))

(defn packed-refs
  "refs/heads/* out of .git/packed-refs, read once per repo."
  [gitdir]
  (when-let [txt (slurp* (path/join gitdir "packed-refs"))]
    (into {}
          (keep (fn [l]
                  (when-not (str/starts-with? l "#")
                    (let [[sha ref] (str/split (str/trim l) #"\s+" 2)]
                      (when (and sha ref) [ref sha])))))
          (str/split-lines txt))))

(defn resolve-ref [gitdir ref packed]
  (or (slurp* (path/join gitdir ref)) (get packed ref)))

(defn head-sha
  "Resolve HEAD for a worktree-or-plain checkout. Returns nil if unresolvable."
  [dir]
  (let [dotgit (path/join dir ".git")
        gitdir (cond
                 (try (.isDirectory (fs/statSync dotgit)) (catch :default _ false))
                 dotgit
                 ;; `gitdir: <path>` file — a worktree or a west-managed link
                 :else (when-let [t (slurp* dotgit)]
                         (when (str/starts-with? t "gitdir:")
                           (let [p (str/trim (subs t (count "gitdir:")))]
                             (if (path/isAbsolute p) p (path/join dir p))))))]
    (when gitdir
      (when-let [h (slurp* (path/join gitdir "HEAD"))]
        (if (str/starts-with? h "ref: ")
          (let [ref (str/trim (subs h 5))]
            (or (resolve-ref gitdir ref (packed-refs gitdir))
                ;; linked worktrees keep refs in the common dir
                (when-let [common (slurp* (path/join gitdir "commondir"))]
                  (let [c (if (path/isAbsolute common) common (path/join gitdir common))]
                    (resolve-ref c ref (packed-refs c))))))
          h)))))

;; ---------------------------------------------------------------- ancestry

(defn ancestor?
  "Is `a` an ancestor of `b` inside `dir`? nil when git cannot answer (missing object)."
  [dir a b]
  (try
    (cp/execFileSync "git" (clj->js ["-C" dir "merge-base" "--is-ancestor" a b])
                     #js {:stdio "ignore"})
    true
    (catch :default e
      ;; exit 1 = not an ancestor; anything else = git could not decide
      (if (= 1 (.-status e)) false nil))))

;; ---------------------------------------------------------------- report

(def projects (parse-west (fs/readFileSync west "utf8")))

(println (str "west projects: " (count projects) "  root: " root))

(def rows
  (->> projects
       (keep (fn [{:keys [name path revision]}]
               (when (and path revision (re-matches #"[0-9a-f]{40}" (or revision "")))
                 (let [dir (path/join root path)]
                   (when (fs/existsSync dir)
                     (when-let [head (head-sha dir)]
                       {:name name :path path :dir dir :pin revision :head head}))))))
       (remove #(= (:head %) (:pin %)))
       vec))

(println (str "checked out and differing from pin: " (count rows)))

(def classified
  (mapv (fn [{:keys [dir pin head] :as r}]
          (assoc r :rel (cond
                          (= true (ancestor? dir head pin)) :behind
                          (= true (ancestor? dir pin head)) :ahead
                          :else :diverged-or-unknown)))
        rows))

(doseq [[k label] [[:behind "BEHIND pin (scan would under-measure these)"]
                   [:diverged-or-unknown "DIVERGED / unresolvable"]
                   [:ahead "ahead of pin (normal for self-committing actors — leave alone)"]]]
  (let [g (filterv #(= k (:rel %)) classified)]
    (println (str "\n── " label ": " (count g)))
    (doseq [{:keys [name path pin head]} (take (if (= k :ahead) 10 200) g)]
      (println (str "  " name "  " path
                    "  pin=" (subs pin 0 8) " head=" (subs head 0 8))))
    (when (and (= k :ahead) (> (count g) 10))
      (println (str "  … and " (- (count g) 10) " more")))))

(let [behind (filterv #(= :behind (:rel %)) classified)]
  (when (seq behind)
    (println "\nto sync just these to their pins:")
    (println (str "  printf '%s\\n' " (str/join " " (map :name behind))
                  " | xargs west update --fetch smart"))))
