(ns isekai.games.kingdom-cascade.core
  "The game itself: state, legal moves, and cascade resolution.

  Everything in this namespace is pure. `swap` and `tap` take a state and
  return a new one; nothing is read from or written to the world. That is what
  makes the same code the authority in three places at once — the browser
  build, the packaged mobile shell, and the headless nbb runner used by tests
  and by the replay digest.

  A state:

      {:level      level-edn
       :board      board
       :seed       rng-state
       :moves-left int
       :progress   {goal-index count}
       :score      int
       :turn       int
       :status     :playing | :won | :lost
       :frames     [frame ...]}   ; produced by the last action, for animation

  `:frames` is the animation script for one player action. The renderer walks
  it; it is never the source of truth for the board."
  (:require [clojure.string :as str]
            [isekai.games.kingdom-cascade.blast :as blast]
            [isekai.games.kingdom-cascade.board :as b]
            [isekai.games.kingdom-cascade.clear :as clear]
            [isekai.games.kingdom-cascade.gravity :as gravity]
            [isekai.games.kingdom-cascade.level :as level]
            [isekai.games.kingdom-cascade.matcher :as matcher]
            [isekai.games.kingdom-cascade.rng :as rng]))

(def piece-score 60)

;; ------------------------------------------------------------------ new game

(defn new-game
  "Deals a level into a playable state.

  The opening deal is settled before play so a level never starts mid-cascade,
  and the moves spent doing it are not charged to the player."
  [lvl]
  (let [board (b/parse-grid (:level/grid lvl))
        colors (:level/colors lvl)
        {:keys [board seed]} (level/deal board (rng/seed (:level/seed lvl)) colors)
        {:keys [board seed]} (loop [bd board sd seed n 0]
                               (let [groups (matcher/find-groups bd)]
                                 (if (or (empty? groups) (> n 32))
                                   {:board bd :seed sd}
                                   (let [cells (mapcat :cells groups)
                                         {bd' :board} (clear/apply-clear bd cells)
                                         st (gravity/settle bd' sd colors)]
                                     (recur (:board st) (:seed st) (inc n))))))]
    {:level lvl
     :board board
     :seed seed
     :moves-left (:level/moves lvl)
     :progress {}
     :score 0
     :turn 0
     :status :playing
     :frames []}))

;; ------------------------------------------------------------- legal moves

(defn- creates-match?
  "Would swapping two ordinary pieces open a match?"
  [board a bpos]
  (matcher/any-match? (b/swap-pieces board a bpos)))

(defn legal-swap?
  "A swap is legal when both cells are draggable and adjacent, and either a
  special is involved or the swap opens a match.

  Special pieces are colour-neutral, so a swap that only moves one can never
  form a colour run; involving a special is instead its own reason to allow
  the swap, because that is how specials are fired."
  [{:keys [board status]} a bpos]
  (boolean
   (and (= :playing status)
        (b/adjacent? a bpos)
        (b/swappable? board a)
        (b/swappable? board bpos)
        (or (b/power-of (b/piece-at board a))
            (b/power-of (b/piece-at board bpos))
            (creates-match? board a bpos)))))

(defn legal-swaps
  "Every legal swap, in reading order. The hint system and the deadlock
  detector are the same query asked with different urgency."
  [state]
  (let [board (:board state)]
    (for [pos (b/positions board)
          other [[(inc (first pos)) (second pos)]
                 [(first pos) (inc (second pos))]]
          :when (legal-swap? state pos other)]
      [pos other])))

(defn hint [state] (first (legal-swaps state)))

(defn deadlocked? [state] (empty? (legal-swaps state)))

(defn reshuffle
  "Redeals the loose pieces in place when no legal move exists.

  Blocks, covers and pinned pieces keep their cells — a reshuffle rearranges
  what the player could have moved anyway, and moving obstacles around would
  rewrite the level's difficulty behind their back."
  [state]
  (let [board (:board state)
        movable (filter #(b/swappable? board %) (b/positions board))
        pieces (mapv #(b/piece-at board %) movable)]
    (loop [seed (:seed state) n 0]
      (let [[shuffled seed'] (rng/shuffle seed pieces)
            board' (reduce (fn [bd [pos pc]] (b/put-piece bd pos pc))
                           board
                           (map vector movable shuffled))
            candidate (assoc state :board board' :seed seed')]
        (cond
          (and (not (matcher/any-match? board')) (not (deadlocked? candidate)))
          (assoc candidate :frames [{:frame/kind :reshuffle :frame/board board'}])

          (> n 64)
          ;; Give up on elegance rather than loop forever; the cascade loop
          ;; will absorb whatever matches the last attempt left behind.
          (assoc candidate :frames [{:frame/kind :reshuffle :frame/board board'}])

          :else (recur seed' (inc n)))))))

;; ----------------------------------------------------------------- cascading

(defn- settle-into
  [ctx]
  (let [{:keys [board seed frames]} (gravity/settle (:board ctx) (:seed ctx) (:colors ctx))]
    (-> ctx
        (assoc :board board :seed seed)
        (update :frames into frames))))

(defn- absorb
  "Folds one clear report into the running context.

  `board` is the board the frame should show, which is the report's board
  unless the caller has something to add to it afterwards (a match promotes a
  special into a cell it just emptied, and the frame should show that special
  rather than the hole it briefly left)."
  ([ctx report frame] (absorb ctx report frame (:board report)))
  ([ctx report frame board]
   (-> ctx
       (assoc :board board)
       (update :collected into (:collected report))
       (update :blasts into (:triggered report))
       (update :score + (* piece-score (count (:pieces report)) (max 1 (:depth ctx))))
       (update :frames conj (assoc frame :frame/board board)))))

(defn- detonate
  "Pops one pending special and applies its area."
  [ctx]
  (let [[[pos power] & rest] (:blasts ctx)
        cells (blast/area (:board ctx) pos power)
        report (clear/apply-clear (:board ctx) cells)]
    (-> ctx
        (assoc :blasts (vec rest))
        (absorb report {:frame/kind :blast
                        :frame/origin pos
                        :frame/power power
                        :frame/cells cells}))))

(defn- resolve-matches
  "Clears every group currently on the board and promotes the rewards."
  [ctx]
  (let [board (:board ctx)
        groups (matcher/find-groups board)
        cells (into #{} (mapcat :cells groups))
        report (clear/apply-clear board cells)
        removed (set (keys (:pieces report)))
        rewards (keep (fn [g]
                        (when-let [power (:reward g)]
                          (let [site (matcher/promotion-site
                                      (update g :cells #(filterv removed %))
                                      (:touched ctx))]
                            (when site [site power]))))
                      groups)
        promoted (reduce (fn [bd [site power]] (b/put-piece bd site {:kind power}))
                         (:board report)
                         rewards)]
    (-> ctx
        (absorb report
                {:frame/kind :match
                 :frame/groups (mapv #(select-keys % [:color :cells :reward]) groups)
                 :frame/rewards (vec rewards)}
                promoted)
        (update :depth inc))))

(defn- cascade
  "Runs blasts, matches and settling to a fixed point."
  [ctx]
  (loop [ctx ctx, n 0]
    (cond
      (> n 256) (assoc ctx :exhausted? true)
      (seq (:blasts ctx)) (recur (settle-into (detonate ctx)) (inc n))
      (matcher/any-match? (:board ctx)) (recur (settle-into (resolve-matches ctx)) (inc n))
      :else ctx)))

;; -------------------------------------------------------------- player moves

(defn- finish
  "Charges the move, credits goals, and settles the win/lose question."
  [state ctx]
  (let [progress (level/credit (:level/goals (:level state)) (:progress state) (:collected ctx))
        moves-left (max 0 (dec (:moves-left state)))
        won? (level/all-goals-met? (:level/goals (:level state)) progress)]
    (assoc state
           :board (:board ctx)
           :seed (:seed ctx)
           :frames (:frames ctx)
           :score (+ (:score state) (:score ctx))
           :progress progress
           :moves-left moves-left
           :turn (inc (:turn state))
           :status (cond won? :won
                         (zero? moves-left) :lost
                         :else :playing))))

(defn- start-ctx
  [state touched]
  {:board (:board state)
   :seed (:seed state)
   :colors (:level/colors (:level state))
   :collected []
   :blasts []
   :frames []
   :score 0
   :depth 1
   :touched touched})

(defn swap
  "Plays one swap. Returns the state unchanged if the move is not legal, so a
  caller may treat `swap` as a filter and does not need to pre-check."
  [state a bpos]
  (if-not (legal-swap? state a bpos)
    state
    (let [board (:board state)
          pa (b/power-of (b/piece-at board a))
          pb (b/power-of (b/piece-at board bpos))
          swapped (b/swap-pieces board a bpos)
          ctx (-> (start-ctx state [a bpos])
                  (assoc :board swapped)
                  (update :frames conj {:frame/kind :swap
                                        :frame/board swapped
                                        :frame/a a
                                        :frame/b bpos}))
          ;; After the swap the pieces have traded cells: what was at `a` is
          ;; now at `bpos`. Detonations are described from where the player
          ;; dragged to, which is `bpos`.
          ctx (cond
                (and pa pb)
                (let [{:keys [cells combo]} (blast/combo swapped bpos a pa pb)
                      report (clear/apply-clear swapped cells)]
                  (absorb ctx report {:frame/kind :combo :frame/combo combo
                                      :frame/cells cells :frame/at bpos}))

                (or (= pa :prism) (= pb :prism))
                (let [prism-at (if (= pa :prism) bpos a)
                      other (if (= pa :prism) a bpos)
                      {:keys [cells color]} (blast/prism-swallow swapped prism-at other)
                      report (clear/apply-clear swapped (conj cells prism-at))]
                  (absorb ctx report {:frame/kind :combo :frame/combo :prism-color
                                      :frame/color color :frame/cells cells
                                      :frame/at prism-at}))

                (or pa pb)
                (let [power (or pa pb)
                      at (if pa bpos a)
                      cells (blast/area swapped at power)
                      report (clear/apply-clear swapped cells)]
                  (absorb ctx report {:frame/kind :blast :frame/origin at
                                      :frame/power power :frame/cells cells}))

                :else ctx)]
      (finish state (cascade (settle-into ctx))))))

(defn tap
  "Fires a special in place. Costs a move, like a swap does — a free
  detonation would make holding specials strictly better than using them."
  [state pos]
  (let [power (b/power-of (b/piece-at (:board state) pos))]
    (if-not (and (= :playing (:status state)) power (b/swappable? (:board state) pos))
      state
      (let [cells (blast/area (:board state) pos power)
            report (clear/apply-clear (:board state) cells)
            ctx (-> (start-ctx state [pos])
                    (absorb report {:frame/kind :blast :frame/origin pos
                                    :frame/power power :frame/cells cells}))]
        (finish state (cascade (settle-into ctx)))))))

;; ------------------------------------------------------------------- reading

(defn hud
  "Everything the HUD needs, and nothing it does not."
  [state]
  {:score (:score state)
   :moves-left (:moves-left state)
   :status (:status state)
   :goals (mapv (fn [goal n] (assoc goal :goal/remaining n))
                (:level/goals (:level state))
                (level/remaining (:level/goals (:level state)) (:progress state)))})

(def ^:private digest-modulus 1000000007)

(defn- char-code
  [ch]
  #?(:clj (int ^Character ch)
     :cljs (.charCodeAt ^string ch 0)))

(defn- roll
  "Polynomial rolling hash. Deliberately not `clojure.core/hash`: that is
  free to differ between Clojure and ClojureScript, which would make the
  digest useless for exactly the comparison it exists to make.

  The modulus keeps every intermediate product under 2^53, so the JVM's longs
  and ClojureScript's doubles agree on every step."
  [h s]
  (reduce (fn [acc ch] (mod (+ (* acc 31) (char-code ch)) digest-modulus))
          h
          (seq (str s))))

(defn digest
  "A replay fingerprint. Same level, same seed, same moves must give the same
  number on every runtime, so the browser build, the packaged mobile shell and
  the headless nbb runner can be checked against each other."
  [state]
  (reduce roll 7
          [(str/join "/" (b/render-ascii (:board state)))
           (:score state)
           (:moves-left state)
           (pr-str (into (sorted-map) (:progress state)))
           (:status state)]))
