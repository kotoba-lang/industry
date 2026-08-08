(ns isekai.games.boutique-stack.world
  "The shop floor: zones, fixtures, and the geometry agents walk over.

  ## Units

  Positions are **integers**, one unit = one centimetre, one floor tile = 100
  units. Not floats. Every agent moves a whole number of units per tick, so
  the simulation is exactly reproducible on the JVM and in ClojureScript
  without any reasoning about rounding — which is what the replay digest and
  the browser/shell parity check both rest on.

  ## Zones

  A zone is the unit of unlocking. Locked zones are not merely invisible: no
  agent may target a fixture inside one, so a locked department cannot leak
  sales, cannot be restocked, and cannot appear in a customer's list.

  ## Routing

  Agents walk to a zone's entry point and then straight to the fixture. There
  is no navmesh and no collision — deliberately. At this layer the question is
  \"how long does a trip take and what does it block\", and a real path would
  change the answer by a few percent while making every test a geometry
  puzzle. The renderer is free to draw a nicer path over the same timing."
  (:require [isekai.games.boutique-stack.catalog :as catalog]))

(def tile 100)

(def interaction-radius
  "How close is close enough to use a fixture. Just over half a tile, so
  standing on the adjacent tile does not silently fail to work."
  60)

;; ------------------------------------------------------------------- geometry

(defn- square [n] (* n n))

(defn dist2
  "Squared distance. Squared on purpose: comparing distances never needs the
  square root, and taking one would put a float into an integer simulation."
  [[ax ay] [bx by]]
  (+ (square (- ax bx)) (square (- ay by))))

(defn near? [a b radius] (<= (dist2 a b) (square radius)))

(defn step-toward
  "Moves `from` up to `speed` units toward `to`, snapping on arrival.

  Integer arithmetic throughout. The per-axis split of the step is
  proportional to the axis deltas and biased so the remainder never vanishes —
  a naive `(quot (* dx speed) d)` stalls forever one unit short when the
  quotient rounds to zero on both axes."
  [from to speed]
  (let [[fx fy] from
        [tx ty] to
        dx (- tx fx)
        dy (- ty fy)
        adx (if (neg? dx) (- dx) dx)
        ady (if (neg? dy) (- dy) dy)
        manhattan (+ adx ady)]
    (cond
      (zero? manhattan) from
      (<= manhattan speed) to
      :else
      (let [sx (quot (* dx speed) manhattan)
            sy (quot (* dy speed) manhattan)
            ;; Guarantee progress even when both quotients truncate to zero.
            [sx sy] (if (and (zero? sx) (zero? sy))
                      (if (>= adx ady)
                        [(if (pos? dx) 1 -1) 0]
                        [0 (if (pos? dy) 1 -1)])
                      [sx sy])]
        [(+ fx sx) (+ fy sy)]))))

;; ---------------------------------------------------------------- world model

(defn build
  "Turns a shop EDN definition into an indexed world.

  Throws on a fixture in an unknown zone, or a rack or crate holding an item
  that is not in the catalog. Both are typos that would otherwise show up as
  a department that quietly never sells anything."
  [shop]
  (let [zones (into {} (map (juxt :zone/id identity) (:shop/zones shop)))
        fixtures (into {} (map (juxt :fixture/id identity) (:shop/fixtures shop)))]
    (doseq [f (vals fixtures)]
      (when-not (contains? zones (:fixture/zone f))
        (throw (ex-info "fixture in an unknown zone"
                        {:fixture (:fixture/id f) :zone (:fixture/zone f)})))
      (when (and (:fixture/item f) (not (contains? catalog/items (:fixture/item f))))
        (throw (ex-info "fixture holds an item that is not in the catalog"
                        {:fixture (:fixture/id f) :item (:fixture/item f)}))))
    {:shop shop
     :zones zones
     :fixtures fixtures
     ;; Stable id order, used everywhere a deterministic scan over fixtures is
     ;; needed. Sorting once here means no caller has to remember to.
     :fixture-order (vec (sort (keys fixtures)))
     :zone-order (vec (sort (keys zones)))}))

(defn fixture [world id] (get-in world [:fixtures id]))

(defn zone [world id] (get-in world [:zones id]))

(defn fixtures-of-kind
  [world kind]
  (->> (:fixture-order world)
       (map #(fixture world %))
       (filter #(= kind (:fixture/kind %)))
       vec))

(defn zone-unlocked?
  [world unlocked zone-id]
  (or (zero? (:zone/cost (zone world zone-id) 0))
      (contains? unlocked zone-id)))

(defn open-fixtures
  "Fixtures of `kind` that are currently reachable — the ones in unlocked
  zones. A locked zone's racks must not be restocked, shopped from, or
  counted in any total, and doing that filtering here rather than at each
  call site is what keeps that true."
  [world unlocked kind]
  (filterv #(zone-unlocked? world unlocked (:fixture/zone %))
           (fixtures-of-kind world kind)))

(defn open-departments
  "Departments the shop is currently selling from."
  [world unlocked]
  (->> (open-fixtures world unlocked :rack)
       (keep #(catalog/dept-of (:fixture/item %)))
       distinct
       (sort-by catalog/dept-order)
       vec))

(defn sellable-items
  [world unlocked]
  (->> (open-fixtures world unlocked :rack)
       (map :fixture/item)
       distinct
       sort
       vec))

(defn entry-of
  "Where an agent enters a fixture's zone from. Falls back to the fixture
  itself for zones that did not bother to name one."
  [world fixture-id]
  (let [f (fixture world fixture-id)]
    (or (:zone/entry (zone world (:fixture/zone f)))
        (:fixture/pos f))))

(defn capacity-of [world fixture-id] (:fixture/capacity (fixture world fixture-id) 0))

;; ------------------------------------------------------------------ validation

(defn- try-build
  "Builds a world, turning a construction failure into a reportable problem.

  `build` throws on a typo'd zone or item, which is right for a caller that
  is about to run the shop. It is wrong for `validate`, whose entire job is
  to *report* malformed shops: an exception there takes down the whole
  validation run and hides every other shop in it."
  [shop]
  (try
    {:world (build shop)}
    (catch #?(:clj Exception :cljs :default) e
      {:problem (merge {:problem :malformed-shop :detail (ex-message e)}
                       (ex-data e))})))

(defn- validate-buildable
  "The checks that need a built world. Only reachable once `try-build`
  has confirmed there is one."
  [shop]
  (let [world (build shop)
        unlocked (set (map :zone/id (:shop/zones shop)))
        racks (open-fixtures world unlocked :rack)
        crates (open-fixtures world unlocked :crate)
        crate-items (set (map :fixture/item crates))
        zone-ids (set (keys (:zones world)))]
    (cond-> []
      (empty? (fixtures-of-kind world :entrance))
      (conj {:problem :no-entrance})

      (empty? (fixtures-of-kind world :checkout))
      (conj {:problem :no-checkout})

      (empty? racks)
      (conj {:problem :no-racks})

      :always
      (into (keep (fn [r]
                    (when-not (contains? crate-items (:fixture/item r))
                      {:problem :rack-without-a-crate
                       :fixture (:fixture/id r)
                       :item (:fixture/item r)}))
                  racks))

      :always
      (into (keep (fn [z]
                    (let [missing (remove zone-ids (:zone/requires z))]
                      (when (seq missing)
                        {:problem :zone-requires-unknown-zone
                         :zone (:zone/id z) :missing (vec missing)})))
                  (:shop/zones shop)))

      :always
      (into (keep (fn [r]
                    (when-not (pos? (:fixture/capacity r 0))
                      {:problem :rack-with-no-capacity :fixture (:fixture/id r)}))
                  racks)))))

(defn validate
  "Problems with a shop definition. Empty means usable.

  Never throws: a shop too malformed to build comes back as a problem like
  any other, so one bad file cannot take down a whole validation run."
  [shop]
  (if-let [fatal (:problem (try-build shop))]
    [fatal]
    (validate-buildable shop)))
