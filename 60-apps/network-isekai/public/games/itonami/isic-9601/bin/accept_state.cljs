(ns accept-state
  "CLI acceptance for #1749 — dump/load/dump, script replay, version error, render world.

  Run from the package root:
    npx nbb --classpath src bin/accept_state.cljs

  Exercises the real `bin/kuriningu.cljs` entry points (not only the library)."
  (:require ["node:fs" :as fs]
            ["node:path" :as path]
            ["node:child_process" :as cp]
            [clojure.string :as str]
            [itonami.isic-9601.logic :as l]
            [itonami.isic-9601.state :as state]
            [itonami.isic-9601.world :as world]))

(def here (path/resolve (path/dirname *file*) ".."))
(def tmp (path/join here ".build/accept-state"))
(def failures (atom 0))

(defn- sh [args]
  (let [r (cp/spawnSync "npx" (clj->js (into ["nbb" "--classpath" "src"] args))
                        #js {:cwd here :encoding "utf8"})]
    {:status (.-status r)
     :stdout (or (.-stdout r) "")
     :stderr (or (.-stderr r) "")}))

(defn- fail! [label detail]
  (swap! failures inc)
  (println "  FAIL:" label)
  (when detail (println "       " detail)))

(defn- ok! [label] (println "  ok  " label))

(fs/mkdirSync tmp #js {:recursive true})

(println "== dump → load → dump identical")
(let [a (path/join tmp "a.edn")
      b (path/join tmp "b.edn")
      script "tick*8 intake tick*4 renew"
      r1 (sh ["bin/kuriningu.cljs" "play"
              "--seed" "20260808" "--district" "isic-9601"
              "--script" script "--dump" a "--format" "edn"])
      _ (when-not (zero? (:status r1))
          (fail! "play --dump" (str "exit " (:status r1) " " (:stderr r1)
                                    "\n" (:stdout r1))))
      r2 (sh ["bin/kuriningu.cljs" "play" "--state" a "--dump" b "--format" "edn"])
      _ (when-not (zero? (:status r2))
          (fail! "play --state --dump" (str "exit " (:status r2) " " (:stderr r2))))
      ta (when (fs/existsSync a) (fs/readFileSync a "utf8"))
      tb (when (fs/existsSync b) (fs/readFileSync b "utf8"))]
  (cond
    (nil? ta) (fail! "dump→load→dump" "a.edn missing")
    (= ta tb) (ok! "dump→load→dump byte-identical")
    :else (fail! "dump→load→dump" (str "len a=" (count ta) " b=" (count tb)))))

(println "== replay {seed,district,script} equals dump")
(let [a (path/join tmp "replay-a.edn")
      b (path/join tmp "replay-b.edn")
      script "tick*12 intake renew"
      _ (sh ["bin/kuriningu.cljs" "play" "--seed" "7" "--script" script
             "--dump" a "--format" "edn"])
      _ (sh ["bin/kuriningu.cljs" "play" "--seed" "7" "--script" script
             "--dump" b "--format" "edn"])
      ea (state/parse (fs/readFileSync a "utf8"))
      eb (state/parse (fs/readFileSync b "utf8"))]
  (if (= (:shop ea) (:shop eb))
    (ok! "two dumps of the same input column agree on :shop")
    (fail! "replay equality" "shops differ"))
  (if (= 7 (:seed ea))
    (ok! "envelope :seed is the starting seed")
    (fail! "envelope seed" (str (:seed ea)))))

(println "== unknown :version errors loudly")
(let [bad (path/join tmp "bad-version.edn")
      _ (fs/writeFileSync bad (state/encode {:kind :itonami-game/state :version 99
                                             :district "isic-9601" :seed 1
                                             :world (world/init) :shop nil}))
      r (sh ["bin/kuriningu.cljs" "play" "--state" bad "--format" "edn"])]
  (if (and (= 2 (:status r))
           (re-find #"version|unsupported" (str (:stdout r) (:stderr r))))
    (ok! "exit 2 and message on unknown version")
    (fail! "unknown version" (str "status=" (:status r)
                                  " out=" (str/trim (:stdout r))))))

(println "== victory dump unlocks for render --state")
(let [shop (assoc (l/init 1 "isic-9601") :flow :victory :returned 40)
      world (world/clear-district (assoc (world/init) :in "isic-9601")
                                  "isic-9601" 40)
      env (state/wrap shop world 1 "isic-9601")
      f (path/join tmp "victory.edn")
      _ (fs/writeFileSync f (state/encode env))
      loaded (state/parse (fs/readFileSync f "utf8"))
      unlocked (count (filter :unlocked? (:districts (world/status (:world loaded)))))]
  (if (and (= 1 (:cleared (:world loaded))) (= 2 unlocked))
    (ok! "state file carries cleared=1 / 2 unlocked districts")
    (fail! "render unlock signal" (str "cleared=" (:cleared (:world loaded))
                                       " unlocked=" unlocked))))

(println "== --format edn keeps a parseable envelope on stdout")
(let [r (sh ["bin/kuriningu.cljs" "play" "--seed" "1" "--script" "tick*2"
             "--format" "edn"])
      env (try (state/parse (:stdout r)) (catch :default _ nil))]
  (if (and (zero? (:status r)) env (= :itonami-game/state (:kind env)))
    (ok! "play --format edn prints a valid envelope")
    (fail! "format edn" (str "status=" (:status r)))))

(println "== stalled exit code")
(let [stalled-env (state/wrap
                   (assoc (l/init 1) :cert-current? false :cert-ticks 0 :cash 10
                          :flow :stalled)
                   (world/init) 1 "isic-9601")
      f (path/join tmp "stalled.edn")
      _ (fs/writeFileSync f (state/encode stalled-env))
      r (sh ["bin/kuriningu.cljs" "play" "--state" f])]
  (if (= 3 (:status r))
    (ok! "play exits 3 on :flow :stalled")
    (fail! "stalled exit" (str "status=" (:status r)
                               "\n" (str/trim (:stdout r))))))

(println)
(if (pos? @failures)
  (do (println (str "ACCEPT FAIL — " @failures " check(s)"))
      (js/process.exit 1))
  (do (println "ACCEPT OK")
      (js/process.exit 0)))
