(ns itonami.isic-9601.inspect
  "Agent inspection over a street frame — annotate + pick (ADR-2608108000 / #1753).

  Coordinates come from `kami.webgpu.pick/project` and hits from `kami.webgpu.pick/pick`.
  This namespace does not invent a second projection. CLI `--annotate` / `--pick` are thin
  wrappers over these helpers.

  `--pick` is unfiltered: inspection wants truth. The shop-only `:filter` in
  `street/tap->district` stays on the tap path; it is not applied here. Empty sky (a ray
  that misses every instance) returns nil — there is no nearest-building fallback."
  (:require [kami.webgpu.pick :as pick]))

(defn hit->edn
  "Flatten a `pick/pick` hit into the CLI / MCP shape, or nil for a miss:
  `{:index i :district id-or-nil :kind k :point [x y z] :t distance}`."
  [hit]
  (when hit
    {:index (:index hit)
     :district (get-in hit [:instance :district])
     :kind (get-in hit [:instance :kind])
     :point (:point hit)
     :t (:t hit)}))

(defn pick-at
  "Unfiltered screen→instance pick. Returns the `hit->edn` map, or nil."
  [ir [x y] W H]
  (hit->edn (pick/pick ir [x y] W H)))

(defn projected-bounds
  "Axis-aligned screen bounds of an instance's oriented box, or nil when every corner is
  behind the camera. Built by projecting the eight corners through `pick/project` — the
  same path labels use — so 'label sits inside projected bounds' is a statement about one
  projection, not two."
  [ir inst W H]
  (let [box (pick/instance-box inst)
        [cx cy cz] (:center box)
        [hx hy hz] (:half box)
        yaw (double (or (:yaw box) 0.0))
        c (Math/cos yaw)
        s (Math/sin yaw)
        corners (for [ox [(- hx) hx]
                      oy [(- hy) hy]
                      oz [(- hz) hz]]
                  (let [rx (- (* c ox) (* s oz))
                        rz (+ (* s ox) (* c oz))]
                    [(+ cx rx) (+ cy oy) (+ cz rz)]))
        projs (vec (keep (fn [p] (pick/project ir p W H)) corners))]
    (when (seq projs)
      {:min-x (apply min (map first projs))
       :max-x (apply max (map first projs))
       :min-y (apply min (map second projs))
       :max-y (apply max (map second projs))})))

(defn- inside-bounds?
  "Is screen point `[x y]` inside `bounds` (inclusive, with a tiny pad for float noise)?"
  [[x y] {:keys [min-x max-x min-y max-y]}]
  (let [pad 0.5]
    (and (>= x (- min-x pad)) (<= x (+ max-x pad))
         (>= y (- min-y pad)) (<= y (+ max-y pad)))))

(defn label-text
  "Burned string: instance index and `:district`. Instances without a district (road,
  ground, trees) are not named by annotate — agents name shops; scenery is for `--pick`."
  [index district]
  (str index ":" district))

(defn annotate-labels
  "Labels for `--annotate`: one per district-bearing instance whose own box centre
  projects on screen **and wins the unfiltered pick at that pixel**. Pieces occluded by
  a nearer sibling (awning behind a roof, etc.) are skipped so every burned label names
  the instance `--pick` returns at its coordinates — the project/pick loop stays closed.

  Each label's `:x`/`:y` is exactly `pick/project` of that instance's box centre — not
  re-derived.

  Returns a vector of
  `{:index i :district id :text s :x sx :y sy :point [wx wy wz]}`."
  [ir W H]
  (vec
   (keep
    (fn [[i inst]]
      (when-let [district (:district inst)]
        (let [c (:center (pick/instance-box inst))]
          (when-let [[sx sy] (pick/project ir c W H)]
            (when (= i (:index (pick/pick ir [sx sy] W H)))
              {:index i
               :district district
               :text (label-text i district)
               :x sx
               :y sy
               :point c})))))
    (map-indexed vector (:instances ir)))))

(defn road-sample
  "A screen pixel that unfiltered `pick` resolves to `:road` or `:road-line`, for
  acceptance. Tries box-centre offsets so the centerline stripe (`:road-line`) or a
  neighbouring slab does not steal every sample. Returns `{:point :pixel :hit}` or nil."
  [ir W H]
  (let [candidates
        (for [[i inst] (map-indexed vector (:instances ir))
              :when (= (:kind inst) :road)
              :let [box (pick/instance-box inst)
                    [cx cy cz] (:center box)
                    [hx _ hz] (:half box)]
              s [0.0 0.25 -0.25 0.4 -0.4]
              t [0.0 0.25 -0.25 0.4 -0.4]
              :let [pt [(+ cx (* s hx)) cy (+ cz (* t hz))]
                    pixel (pick/project ir pt W H)]
              :when pixel
              :let [hit (hit->edn (pick/pick ir pixel W H))]
              :when (and hit (#{:road :road-line} (:kind hit)))]
          {:point pt :pixel pixel :hit hit :road-index i})]
    (first candidates)))

(defn labels-inside-bounds?
  "Acceptance helper: every annotate label sits inside the projected bounds of the
  instance it names. Returns `{:ok true}` or `{:ok false :bad [...]}`."
  [ir W H]
  (let [labels (annotate-labels ir W H)
        bad (vec
             (keep
              (fn [lab]
                (let [inst (nth (:instances ir) (:index lab))
                      b (projected-bounds ir inst W H)]
                  (cond (nil? b)
                        (assoc lab :reason :no-bounds)
                        (not (inside-bounds? [(:x lab) (:y lab)] b))
                        (assoc lab :reason :outside :bounds b)
                        :else nil)))
              labels))]
    (if (empty? bad)
      {:ok true :count (count labels)}
      {:ok false :bad bad :count (count labels)})))

(defn pick-loop-closed?
  "Picking at a label's own coordinates (unfiltered) returns the instance that label
  names — the project/pick loop for annotate."
  [ir W H]
  (let [labels (annotate-labels ir W H)
        bad (vec
             (keep
              (fn [lab]
                (let [hit (pick-at ir [(:x lab) (:y lab)] W H)]
                  (when-not (and hit (= (:index lab) (:index hit)))
                    {:label lab :hit hit})))
              labels))]
    (if (empty? bad)
      {:ok true :count (count labels)}
      {:ok false :bad bad})))
