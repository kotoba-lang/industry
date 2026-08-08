(ns itonami.isic-9601.world
  "The world map — 「営みの街」.

  The shop board (`logic.cljc`) is one business. This is the street it stands on: a set of
  districts, each of which is a REAL cloud-itonami governed actor, locked until the shop
  next door has earned its way. It is what makes the game about *cleaning* rather than
  about laundry — the subject widens from clothes to cars, animals, buildings, industrial
  plant, sewers, waste and contaminated ground, which is the actual span of ISIC's
  cleaning-adjacent classes and the actual span of repos in the `cloud-itonami` org.

  ## Every district here is a repo that exists

  Nothing on this map is invented. Each entry's `:ops`, `:phase-labels` and — the one that
  matters — `:never-auto` were read out of that repo's own `phase.cljc` and
  `governor.cljc` on 2026-08-08. `district-evidence` below records where each came from so
  a reader can check rather than trust.

  ## The one rule the whole street shares

  Reading eight sibling actors side by side turns up something the single-shop game could
  only assert: **every one of them keeps at least one operation out of every phase's
  `:auto` set, permanently.** Not as a rollout stage still to come — as a structural fact,
  each with its own reason:

    衣類     洗浄・返却         — 実際に衣類を処理し、客に返す行為
    洗車     安全上の懸念表明    — roadworthiness の判断に触れる
    ペット   紹介の確定         — 実在の人物を他人に引き合わせる
    建物清掃 安全上の懸念表明    — 作業員の身体に関わる
    産業清掃 安全上の懸念表明    — hazmat / 密閉空間
    下水     安全上の懸念表明    — 公衆衛生
    廃棄物   異議申立           — 相手のある紛争行為
    汚染浄化 汚染の懸念表明      — 土地と住民に関わる

  So the map is not eight variations on a theme; it is eight independent confirmations of
  the same boundary. The player meets it once in the laundry and then finds it again in
  every business they unlock — which is the argument the game is making.

  Same subset discipline as `logic.cljc`: pure data and pure functions, no keyword-as-
  function, no interop."
  )

;; --------------------------------------------------------------------------
;; districts
;; --------------------------------------------------------------------------

(def districts
  "The street, in unlock order. `:at` is the world position the map draws it at
  (world units, +y is north/up, matching `kami.sprite2d.layout`).

  `:never-auto` is the op (or ops) that repo's own phase table keeps out of every `:auto`
  set. `:subject` is what that business actually cleans — the thing the widening is about."
  [{:id "isic-9601" :repo "cloud-itonami-isic-9601" :isic "9601"
    :label "クリーニング" :subject "衣類" :en "Washing and dry-cleaning"
    :at [-600 300] :unlock-at 0 :hue [0.36 0.55 0.95]
    :ops [:garment/intake :careplan/verify :certification/screen
          :actuation/apply-cleaning-process :actuation/return-garment]
    :auto-at-3 [:garment/intake]
    :never-auto [:actuation/apply-cleaning-process :actuation/return-garment]
    :never-auto-why "実際に衣類を処理し、客に返す行為"}

   {:id "isic-4520" :repo "cloud-itonami-isic-4520" :isic "4520"
    :label "洗車・整備" :subject "自動車" :en "Maintenance and repair of motor vehicles"
    :at [0 420] :unlock-at 1 :hue [0.30 0.68 0.52]
    :ops [:log-service-record :schedule-service-operation :coordinate-parts-order
          :flag-safety-concern]
    :auto-at-3 [:log-service-record :schedule-service-operation :coordinate-parts-order]
    :never-auto [:flag-safety-concern]
    :never-auto-why "roadworthiness の判断に触れる"}

   {:id "isic-9609" :repo "cloud-itonami-isic-9609" :isic "9609"
    :label "ペットケア" :subject "動物" :en "Other personal service activities n.e.c."
    :at [640 330] :unlock-at 2 :hue [0.92 0.62 0.42]
    :ops [:client/intake :serviceplan/verify :background-check/screen
          :actuation/finalize-referral]
    :auto-at-3 [:client/intake]
    :never-auto [:actuation/finalize-referral]
    :never-auto-why "実在の人物を他人に引き合わせる"}

   {:id "isic-8121" :repo "cloud-itonami-isic-8121" :isic "8121"
    :label "建物清掃" :subject "建物" :en "Community building cleaning operations"
    :at [-700 -180] :unlock-at 3 :hue [0.45 0.72 0.86]
    :ops [:log-service-record :schedule-cleaning-operation :coordinate-supply-order
          :flag-safety-concern]
    :auto-at-3 [:log-service-record :schedule-cleaning-operation :coordinate-supply-order]
    :never-auto [:flag-safety-concern]
    :never-auto-why "作業員の身体に関わる"}

   {:id "isic-8129" :repo "cloud-itonami-isic-8129" :isic "8129"
    :label "産業清掃" :subject "プラント" :en "Other building and industrial cleaning"
    :at [-60 -260] :unlock-at 4 :hue [0.86 0.72 0.30]
    :ops [:log-service-record :schedule-service-operation :coordinate-supply-order
          :flag-safety-concern]
    :auto-at-3 [:log-service-record :schedule-service-operation :coordinate-supply-order]
    :never-auto [:flag-safety-concern]
    :never-auto-why "hazmat / 密閉空間"}

   {:id "isic-3700" :repo "cloud-itonami-isic-3700" :isic "3700"
    :label "下水" :subject "排水" :en "Sewerage operations coordination"
    :at [600 -220] :unlock-at 5 :hue [0.40 0.50 0.62]
    :ops [:log-system-record :schedule-maintenance :order-supplies :flag-safety-concern]
    :auto-at-3 [:log-system-record :schedule-maintenance :order-supplies]
    :never-auto [:flag-safety-concern]
    :never-auto-why "公衆衛生"}

   {:id "isic-3811" :repo "cloud-itonami-isic-3811" :isic "3811"
    :label "廃棄物収集" :subject "ごみ" :en "Collection of non-hazardous waste"
    :at [-380 -620] :unlock-at 6 :hue [0.55 0.66 0.38]
    :ops [:pickup/schedule :manifest/record :dispute/request]
    :auto-at-3 [:pickup/schedule :manifest/record]
    :never-auto [:dispute/request]
    :never-auto-why "相手のある紛争行為"}

   {:id "isic-3900" :repo "cloud-itonami-isic-3900" :isic "3900"
    :label "汚染浄化" :subject "土壌" :en "Remediation activities"
    :at [320 -640] :unlock-at 7 :hue [0.72 0.45 0.72]
    :ops [:log-remediation-record :schedule-remediation-operation :coordinate-disposal
          :flag-contamination-concern]
    :auto-at-3 [:log-remediation-record :schedule-remediation-operation :coordinate-disposal]
    :never-auto [:flag-contamination-concern]
    :never-auto-why "土地と住民に関わる"}])

(def district-evidence
  "Where each district's op tables were read from, so the claim is checkable. Read
  2026-08-08 from the repos' default branches."
  {:read-on "2026-08-08"
   :from "<repo>/src/<domain>/phase.cljc (:writes / :auto) and governor.cljc (allowed-ops / high-stakes)"
   :note "cloud-itonami-isic-8121/8129/3700/3900 の blueprint id は `cloud-itonami-<code>`
          (isic- 抜き) で、west の project 名とは綴りが違う。repo 名は :repo が正。"})

(def district-count (count districts))

(defn district
  "Look a district up by id. nil when there is no such district."
  [id]
  (first (filter (fn [d] (= (:id d) id)) districts)))

(defn never-auto-op-count
  "How many operations on this street can never run unattended. The map's headline
  number, and the reason it is a street and not one shop."
  []
  (reduce + 0 (map (fn [d] (count (:never-auto d))) districts)))

;; --------------------------------------------------------------------------
;; progress
;; --------------------------------------------------------------------------

(defn unlocked?
  "A district opens once `cleared` earlier districts have closed their audit. The laundry
  (`:unlock-at 0`) is always open."
  [d cleared]
  (>= (or cleared 0) (:unlock-at d)))

(defn init
  "Fresh world progress. `:cleared` counts districts whose audit has closed; `:in`
  is the district currently being played, or nil while on the map."
  []
  {:cleared 0 :in nil :best {}})

(defn enter
  "Walk into a district. Refuses a locked one -- the map is the gate, not the UI."
  [w id]
  (let [d (district id)]
    (if (and d (unlocked? d (:cleared w)))
      (assoc w :in id)
      w)))

(defn leave [w] (assoc w :in nil))

(defn clear-district
  "Record that `id` closed its audit with `returned` jobs. Only advances the unlock
  ladder the first time, so replaying a district cannot farm unlocks."
  [w id returned]
  (let [d (district id)
        first? (not (contains? (:best w) id))]
    (if-not d
      w
      (-> w
          (update :best (fn [b] (assoc b id (max (get b id 0) (or returned 0)))))
          (update :cleared (fn [c] (if first? (max c (inc (:unlock-at d))) c)))))))

(defn status
  "Flat view of the street for a host to draw."
  [w]
  {:cleared (:cleared w)
   :total district-count
   :in (:in w)
   :never-auto-total (never-auto-op-count)
   :districts (mapv (fn [d]
                      {:id (:id d) :label (:label d) :subject (:subject d)
                       :isic (:isic d) :repo (:repo d)
                       :unlocked? (unlocked? d (:cleared w))
                       :playable? (= (:id d) "isic-9601")
                       :best (get (:best w) (:id d) 0)
                       :ops (count (:ops d))
                       :never-auto (mapv (fn [o] (str o)) (:never-auto d))
                       :never-auto-why (:never-auto-why d)})
                    districts)})

;; --------------------------------------------------------------------------
;; render-IR for the KAMI 2D stack
;; --------------------------------------------------------------------------
;; The map is emitted as a `kami.sprite2d` scene + entity snapshot -- the same pair
;; `layout/draw-list`, `layout/pick` and the Canvas2D painter consume. Three things this
;; needs did not exist in the package before 2026-08-08 and were added upstream (see
;; `sdk-patches/`): the `:fit` camera (a street has a known extent and no avatar to
;; follow), the `:text` primitive (each building carries its own name and ISIC code), and
;; `layout/pick` (the whole interaction is tapping a building).

(def world-rect
  "The extent the camera frames. Derived from the district positions with a margin, so
  moving a building cannot push it off screen."
  (let [xs (map (fn [d] (nth (:at d) 0)) districts)
        ys (map (fn [d] (nth (:at d) 1)) districts)
        m 320]
    [(- (reduce min xs) m) (- (reduce min ys) m)
     (+ (reduce max xs) m) (+ (reduce max ys) m)]))

(defn- shop-sprite
  "A building as EDN primitives. Locked shops are drawn desaturated with a padlock, which
  is the whole vocabulary the map needs: awning, sign, name, code."
  [d unlocked?]
  (let [hue (:hue d)
        dim (fn [c] (mapv (fn [x] (+ 0.42 (* 0.24 x))) c))
        body (if unlocked? hue (dim hue))
        ink (if unlocked? [1 1 1] [0.75 0.75 0.78])]
    (vec
     (concat
      [[:rect {:dx 0 :dy 0 :w 260 :h 170 :fill body}]
       ;; awning
       [:rect {:dx 0 :dy -70 :w 270 :h 34 :fill (if unlocked? [0.98 0.98 1] [0.8 0.8 0.82])}]
       ;; sign board
       [:rect {:dx 0 :dy -118 :w 230 :h 54 :fill (if unlocked? [1 1 1] [0.72 0.72 0.75])}]
       [:text {:dx 0 :dy -118 :text (:label d) :size 34 :weight 800
               :fill (if unlocked? [0.08 0.09 0.12] [0.42 0.42 0.46])}]
       [:text {:dx 0 :dy 34 :text (str "ISIC " (:isic d)) :size 22 :weight 700 :fill ink}]
       [:text {:dx 0 :dy 72 :text (:subject d) :size 26 :weight 800 :fill ink}]]
      (when-not unlocked?
        ;; padlock: shackle + body, the same shape the reference art uses
        [[:arc {:dx 0 :dy -6 :r 26 :a0 3.14159 :a1 6.28318 :w 12 :stroke [0.99 0.79 0.16]}]
         [:rect {:dx 0 :dy 22 :w 62 :h 52 :fill [0.99 0.79 0.16]}]])))))

(defn scene
  "The `kami.sprite2d` scene EDN for the street. `:camera {:mode :fit}` frames
  `world-rect` on any viewport; without it the default camera would look for a `\"player\"`
  entity, find none, and silently anchor at world origin."
  [w]
  {:render/sky {:zenith [0.52 0.72 0.92] :ground [0.44 0.68 0.40]}
   :render/sprite2d {:camera {:mode :fit :rect world-rect :pad 0.05}
                     ;; a street is not a forest
                     :tree-count 0}
   :sprites (reduce (fn [acc d]
                      (assoc acc (keyword (:id d))
                             (shop-sprite d (unlocked? d (:cleared w)))))
                    {} districts)})

(defn snapshot
  "The entity snapshot for the street: one entity per district, tagged with its id so
  `layout/pick` hands the id straight back."
  [_w]
  (mapv (fn [d] {:tag (:id d) :pos [(nth (:at d) 0) (nth (:at d) 1) 0]}) districts))

(defn render-ir
  "Everything a host needs to draw and hit-test the map."
  [w]
  {:scene (scene w) :snap (snapshot w) :rect world-rect})
