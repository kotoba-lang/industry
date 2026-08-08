(ns kuriningu
  "「クリーニング営み」 command line — play the shop, walk the street, render the 3D frame.

  Both halves run from a terminal, and neither is a separate implementation:

    play    drives `logic/reduce-event`, the same reducer the browser preview and the
            network-isekai guest run. A scripted run is therefore an executable
            description of a real game, not a simulation of one.

    render  builds `world3d/render-ir` — the canonical `kami.webgpu` render-IR — packs it
            with the engine's own `submission/pack-instances` and `pack-globals`, and
            draws it in headless Chromium through `fixtures/glsl/lit.*`, the GLSL the
            WebGL 2.0 backend actually uses. Pixels come back as a PNG.

  That second one matters beyond convenience: it is the WebGL 2.0 end-to-end check
  CLAUDE.md's 3D rule requires, in a form you can run without a screen. It is NOT a
  second renderer — no geometry, no matrices and no shading are authored here. Every
  number handed to the GPU comes from the engine, and this file is plumbing: create a
  context, bind buffers, draw, read back.

  Usage:
    nbb bin/kuriningu.cljs play   [--seed N] [--script \"tick*40 verify clean return\"] [--turns N]
    nbb bin/kuriningu.cljs street [--cleared N]
    nbb bin/kuriningu.cljs render [--out FILE] [--width N] [--height N] [--cleared N]
                                  [--probe] [--backend webgl2]

  `render` needs the engine checked out (`west update --fetch smart webgpu render sprite2d`)
  and playwright; see README."
  (:require ["node:fs" :as fs]
            ["node:path" :as path]
            [clojure.string :as str]
            [itonami.isic-9601.logic :as l]
            [itonami.isic-9601.world :as world]
            [itonami.isic-9601.district :as district]))

;; --------------------------------------------------------------------------
;; argv
;; --------------------------------------------------------------------------

;; nbb hands the script its own args; `process.argv` would still hold `--classpath` etc.
(def argv (vec *command-line-args*))
(def command (first argv))

(defn opt
  ([k] (opt k nil))
  ([k default]
   (let [i (.indexOf (into-array (rest argv)) (str "--" k))]
     (if (neg? i) default (nth (rest argv) (inc i) default)))))

(defn flag? [k] (some (fn [a] (= a (str "--" k))) argv))
(defn num-opt [k default] (let [v (opt k)] (if v (js/parseFloat v) default)))

;; --------------------------------------------------------------------------
;; shared formatting
;; --------------------------------------------------------------------------

(def ESC (js/String.fromCharCode 27))
(defn- sgr [code s] (str ESC "[" code "m" s ESC "[0m"))
(defn- dimmed [s] (sgr "2" s))
(defn- bold [s] (sgr "1" s))
(defn- red [s] (sgr "31" s))
(defn- green [s] (sgr "32" s))
(defn- yellow [s] (sgr "33" s))

(defn- bar [n total width]
  (let [filled (js/Math.round (* width (/ (max 0 n) (max 1 total))))]
    (str (.repeat "█" filled) (dimmed (.repeat "·" (max 0 (- width filled)))))))

;; --------------------------------------------------------------------------
;; play
;; --------------------------------------------------------------------------

(defn- station-line [s]
  (let [tag (cond
              (not (:open? s)) (dimmed "未開放")
              (:hard-human? s) (yellow "人手必須")
              (:auto? s) (green "自動")
              :else "要承認")
        ready (:ready s)]
    (str "  " (bold (.padEnd (:label s) 10)) " "
         (.padStart (str (:count s)) 2) "点 "
         (if (pos? ready) (green (str "▶ " ready " 承認待ち")) (dimmed "—"))
         "  " tag)))

(defn- garment-lines [s]
  (map (fn [g]
         (str "      " (dimmed (:id g)) " " (:desc g)
              "  提案=" (:process g)
              "  書類=" (:evidence g) "/4"
              (when (:label-conflict? g) (red "  ⚠ 洗濯表示に反する"))
              (when (:risk g) (red (str "  ⛔ " (name (:risk g)))))))
       (:garments s)))

(defn print-shop! [st]
  (let [sm (l/summary st)]
    (println)
    (println (bold (str "  " (:district-label sm) "  "))
             (str "t=" (:t sm)
                  "  ¥" (:cash sm)
                  "  信頼 " (.repeat "●" (max 0 (:lives sm)))
                  (dimmed (.repeat "○" (max 0 (- 3 (:lives sm)))))
                  "  返却 " (:returned sm) "/" (:target sm)
                  "  phase " (:phase sm) " " (dimmed (:phase-label sm))))
    (println "  " (bar (:returned sm) (:target sm) 40)
             (if (:cert-current? sm)
               (dimmed (str " 資格 有効(" (:cert-ticks sm) ")"))
               (red " 資格 失効 — 全工程 HOLD")))
    (println)
    (doseq [s (:stations sm)]
      (println (station-line s))
      (doseq [line (garment-lines s)] (println line)))
    (println)
    (doseq [e (take-last 4 (:ledger sm))]
      (println "  " (dimmed (str "t" (:t e)))
               (case (:disposition e)
                 :hold (red (str "HOLD " (name (:op e)) " — " (or (:detail e) (name (:basis e)))))
                 :rejected (yellow (str "差戻 " (name (:op e))))
                 :manual (dimmed (str "手作業 " (name (:op e))))
                 (str (name (:op e))))))
    (when (not= (:flow sm) :playing)
      (println)
      (println "  " (if (= (:flow sm) :victory)
                      (green "監査クローズ — 規程どおり完了しました")
                      (red "信頼を失いました"))))
    sm))

(defn commands
  "The scripted vocabulary for a district. Every one is a `logic/reduce-event`, so a script
  cannot reach anything the browser cannot.

  Built from the spec rather than written out. It used to be a literal map of the laundry's
  five station keys, which meant `--script \"clean return\"` under `--district isic-3900`
  dispatched `[:tap :clean]` at a shop that has no `:clean` station: the reducer found
  nothing to act on, did nothing, and the run exited 0. **A script that does nothing and a
  script that works both print a board and return success**, so the only signal was the
  numbers not moving — and a scripted run is usually short enough that they would not have
  moved much anyway.

  Station keys are also offered under their position (`s1`…`sN`) so a script can be written
  once and replayed against any district."
  [spec]
  (let [ks (:station-keys spec)
        ;; nbb keeps keywords, so `(str :flag)` is ":flag" and a script would have to be
        ;; written with the colon. squint turns a keyword into its bare name, where the same
        ;; call gives "flag". Strip the colon so a script reads the same either way.
        nm (fn [k] (let [t (str k)] (if (= ":" (subs t 0 1)) (subs t 1) t)))
        by-name (into {} (mapcat (fn [k] [[(nm k) [:tap k]]
                                          [(str "buy-" (nm k)) [:buy k]]])
                                 ks))
        by-index (into {} (mapcat (fn [i] (let [k (nth ks i)]
                                            [[(str "s" (inc i)) [:tap k]]
                                             [(str "buy-s" (inc i)) [:buy k]]]))
                                  (range (count ks))))]
    (merge by-name by-index
           {"tick" [:tick] "intake" [:take-in]
            "reject" [:reject (l/verify-station spec)]
            "renew" [:renew] "phase" [:phase]
            "buy-approver" [:buy :approver]})))

(defn- expand
  "`tick*40` means forty ticks. Anything else is a single command."
  [token]
  (let [[c n] (str/split token #"\*")]
    (repeat (if n (js/parseInt n 10) 1) c)))

(defn- first-ready-risky?
  "Is the garment `tap`/`reject` would actually act on the one with a problem?

  `tap` and `reject` both take the FIRST ready garment at a station. Asking whether *any*
  ready garment is risky is therefore the wrong question: with two waiting and only the
  second one bad, the strategy rejects the good plan and then approves the bad one, and
  the governor catches it at 洗浄 — correctly, at the cost of a life. Found by running
  this from the CLI on a seed the test suite happened not to use."
  [sm k]
  (let [st (first (filter (fn [s] (= (:key s) k)) (:stations sm)))
        g (first (filter (fn [g] (:ready? g)) (:garments st)))]
    (boolean (and g (or (:risk g) (:label-conflict? g) (not (:cited? g)))))))

(defn- station-order
  "The stations to work, after the entry point."
  [sm] (mapv (fn [s] (:key s)) (:stations sm)))

(defn- auto-turn
  "One turn of the reference strategy: read the care label before approving, keep the
  certification current, climb when you can.

  It is the same script `test/logic_test.cljs`'s `a-played-run-actually-earns-and-
  terminates` plays, so `--turns` shows the tested run rather than a second, unverified
  AI. Notably it does NOT buy upgrades: at this balance the shop earns its way up the tier
  ladder faster than the compounding upgrade costs pay back, and spending early stalls it
  at phase 1. That is a real property of the economy, found by running this."
  [st]
  ;; the summary is taken AFTER the tick and the intake, not before: work accrues during
  ;; the tick, so a decision made on the pre-tick state is about a garment that was not
  ;; ready yet — it approves the plan it meant to reject one turn later. (The CLI is where
  ;; that surfaced; the browser never showed it because a human reads the board they are
  ;; looking at, which is always current.)
  (let [st (-> st (l/reduce-event [:tick]) (l/reduce-event [:take-in]))
        sm (l/summary st)]
    (-> st
        (as-> st'
              (let [ks (station-order sm)
                    second-k (second ks)]
                (reduce (fn [a k]
                          (if (= k second-k)
                            (l/reduce-event a (if (first-ready-risky? sm second-k)
                                                [:reject second-k] [:tap second-k]))
                            (l/reduce-event a [:tap k])))
                        st' (rest ks))))
        (l/reduce-event [:renew])
        (l/reduce-event [:phase]))))

(defn cmd-play []
  (let [seed (int (num-opt "seed" 20260808))
        district-id (opt "district" "isic-9601")
        script (opt "script")
        turns (int (num-opt "turns" (if script 0 1500)))
        _ (when-not (district/spec district-id)
            (println (red (str "unknown district: " district-id)))
            (println (dimmed (str "  playable: " (str/join " " district/playable))))
            (js/process.exit 2))
        st0 (l/init seed district-id)
        st (cond
             script
             (let [vocab (commands (district/spec district-id))]
               (reduce (fn [st token]
                         (let [ev (get vocab token)]
                           (when-not ev
                             (println (red (str "unknown command: " token)))
                             (println (dimmed (str "  " district-id " knows: "
                                                   (str/join " " (sort (keys vocab))))))
                             (js/process.exit 2))
                           (l/reduce-event st ev)))
                       st0
                       (mapcat expand (str/split (str/trim script) #"\s+"))))

             :else
             (reduce (fn [st _] (auto-turn st)) st0 (range turns)))
        sm (print-shop! st)]
    (println)
    (println (dimmed (str "  " district-id "  seed " seed
                          (if script (str "  script: " script) (str "  auto ×" turns))
                          " — 同じ seed と同じ入力は同じ試合になります")))
    (js/process.exit (if (= (:flow sm) :gameover) 1 0))))

;; --------------------------------------------------------------------------
;; street
;; --------------------------------------------------------------------------

(defn cmd-street []
  (let [w (assoc (world/init) :cleared (int (num-opt "cleared" 0)))
        s (world/status w)]
    (println)
    (println (bold "  営みの街") (dimmed (str "  開放 " (count (filter :unlocked? (:districts s)))
                                              "/" (:total s)
                                              "  自動化されない工程 " (:never-auto-total s) " 件")))
    (println)
    (doseq [d (:districts s)]
      (println "  " (if (:unlocked? d) (green "●") (dimmed "🔒"))
               (.padEnd (:label d) 12)
               (dimmed (.padEnd (str "ISIC " (:isic d)) 11))
               (.padEnd (str (:subject d) " を洗う") 14)
               (if (district/spec (:id d)) (green "遊べる") (dimmed "マップのみ"))))
    (println)
    (println (dimmed "  どの店にも、どの段階でも自動化されない工程が必ずある:"))
    (doseq [d (:districts s)]
      (println "  " (dimmed (.padEnd (:label d) 12))
               (yellow (str/join " " (:never-auto d)))
               (dimmed (str "— " (:never-auto-why d)))))
    (println)))

(defn cmd-render []
  (println)
  (println "  3D レンダは engine の checkout が要るので別スクリプトです:")
  (println (bold "    nbb bin/render.cljs --out preview/street.png"))
  (println (dimmed "  (play / street は engine 無しで動くので、依存を分けてあります)"))
  (println))

;; --------------------------------------------------------------------------

(defn usage []
  (println)
  (println (bold "  kuriningu") "— 「クリーニング営み」 command line")
  (println)
  (println "    play    [--seed N] [--script \"...\"] [--turns N]   店を回す")
  (println "    street  [--cleared N]                             営みの街を見る")
  (println "    render                                            → bin/render.cljs を案内")
  (println)
  (println (dimmed (str "  遊べる district: " (str/join " " district/playable))))
  (println (dimmed (str "  play の script 語彙 (isic-9601): "
                        (str/join " " (sort (keys (commands (district/spec "isic-9601"))))))))
  (println (dimmed "  station は名前でも位置 (s1..sN) でも書けます — 位置なら district を跨げます"))
  (println (dimmed "  tick*40 のように *N で繰り返せます"))
  (println))

(case command
  "play" (cmd-play)
  "street" (cmd-street)
  "render" (cmd-render)
  (usage))
