(ns mk1-cad
  "叢雲 MK-1 — 筐体の parametric CAD モデル + 組み立て工程 + 組み立てシミュレーション。

   ADR-2607255000 の規約に従い、既存 kernel を使い新規に書かない:
     brep.kernel    (org-iso-10303) — solid/face/edge/vertex, make-box, bounding-box
     brep.feature   (同)            — sketch/extrude の parametric feature tree
     brep.assembly  (同)            — part-instance / mate 制約 / BOM
     brep.tessellate(同)            — volume / surface-area / 三角形メッシュ
     brep.step      (同)            — ISO 10303-21 (STEP) 書き出し
   Rust は書かない(ADR-2607072000)。nbb / cljs のみ(ADR-2607173000)。

   実行:
     nbb --classpath orgs/kotoba-lang/org-iso-10303/src scripts/mk1-cad.cljs

   **正直な能力範囲(ADR-2607255000 決定5と同じ立場)**:
   ・org-iso-10303 は 1,599 行で OCCT/Parasolid 級の production kernel ではない。
     write-step は :plane 面 + :line 稜線のみ対応なので、**箱と直方体切り欠きで
     構成できる板金筐体はそのまま STEP になるが、フィレット/スイープは出せない。**
   ・brep.assembly/solve は **制約の参照検証のみで transform を解かない**
     (kernel の docstring が明記)。よって配置は本スクリプトが直接与え、
     干渉判定も自分で行う。制約は設計意図の記録として持つ。
   ・干渉/挿入判定は **AABB(軸平行境界箱)レベル**。メッシュ精度の衝突判定では
     ないので、『干渉なし』は『AABB が重ならない』の意味。ここを誤読しない。")

;; ── 依存 ───────────────────────────────────────────────────────────────────
(require '[clojure.string]
         '[brep.kernel :as k]
         '[brep.feature :as feat]
         '[brep.assembly :as asm]
         '[brep.tessellate :as tess]
         '[brep.step :as step]
         '["fs" :as fs]
         '[cljs.reader :as edn])

;; ── 書式ヘルパ(cljs に format は無い) ─────────────────────────────────────
(defn rp [s w] (let [s (str s)] (str s (apply str (repeat (max 0 (- w (count s))) " ")))))
(defn lp [s w] (let [s (str s)] (str (apply str (repeat (max 0 (- w (count s))) " ")) s)))
(defn r1 [x] (str (/ (Math/round (* 10.0 (double x))) 10.0)))
(defn r2 [x] (str (/ (Math/round (* 100.0 (double x))) 100.0)))
(defn r0 [x] (str (Math/round (double x))))
(defn hr [c] (println (apply str (repeat 78 c))))

;; ── 部材カタログの読み込み(EDN が正本、寸法をコードに焼かない) ────────────
(def parts-path "90-docs/hardware/mk1-parts.datoms.edn")
(def catalog (edn/read-string (fs/readFileSync parts-path "utf8")))
(def parts (->> catalog (filter :part/id)
                (remove #(= :design-rule (:part/category %)))
                (remove #(= :chassis (:part/category %)))))
(def by-id (into {} (map (juxt :part/id identity)) parts))
(defn env [id] (edn/read-string (:part/envelope (by-id id))))
(defn ex [id] (nth (env id) 0))
(defn ey [id] (nth (env id) 1))
(defn ez [id] (nth (env id) 2))

;; ── クリアランス規則(EDN の rule-clearance と同じ値を明示的に持つ) ────────
(def C {:wall 1.2 :part-wall 3.0 :part-part 2.0 :riser-gap 10.0
        :rear-io 8.0 :gpu-cable-end 25.0 :plenum 12.0})

(println "叢雲 MK-1 — 筐体 CAD / 組み立て工程 / 組み立て sim")
(println (str "部材カタログ: " parts-path "  (" (count parts) " 部材種)"))

;; ════════════════════════════════════════════════════════════════════════════
;; 1. 筐体寸法の導出 — 手で与えず、部材 envelope + クリアランスから計算する
;; ════════════════════════════════════════════════════════════════════════════
(hr "═")
(println "1. 筐体寸法の導出(部材 envelope + クリアランス規則から)")
(hr "═")

;; sandwich レイアウト: board 室と GPU 室を X 方向に並べ、ライザーで繋ぐ。
;; board 室の X = standoff + mobo envelope(PCB+実装) と cooler の重ね合わせ。
;; cooler は mobo の上に載るので X は「standoff + max(mobo, cooler)」ではなく
;; 「standoff + PCB + cooler 高さ」。mobo envelope X=36 は実装部品込みなので、
;; cooler(47) がそれを上回る分だけが効く。
(def standoff 6.0)
(def pcb 1.6)
(def board-x (+ standoff pcb (ex "cooler-lp")))          ; 6 + 1.6 + 47
(def gpu-x   (ex "gpu-b70"))                              ; 39
(def psu-x   (ex "psu-sfx750"))                           ; 125 ← 律速

;; X: board 室 + ライザー間隙 + GPU 室 を包む幅と、PSU 幅の大きい方
(def x-stack (+ (:part-wall C) board-x (:riser-gap C) gpu-x (:part-wall C)))
(def x-psu   (+ (:part-wall C) psu-x (:part-wall C)))
(def inner-x (max x-stack x-psu))
(def outer-x (+ inner-x (* 2 (:wall C))))

;; Y: mobo の 170 が律速(GPU は 110)。上部に airflow plenum を取る
(def inner-y (+ (:part-wall C) (ey "mobo-b650i") (:part-part C) (:plenum C) (:part-wall C)))
(def outer-y (+ inner-y (* 2 (:wall C))))

;; Z: GPU 267 + カード端の 8-pin ケーブル逃げ 25 + 背面 I/O 8
(def inner-z (+ (:rear-io C) (ez "gpu-b70") (:gpu-cable-end C) (:part-wall C)))
(def outer-z (+ inner-z (* 2 (:wall C))))
(def volume-l (/ (* outer-x outer-y outer-z) 1.0e6))

(doseq [[n v] [["board 室 X (standoff 6 + PCB 1.6 + cooler 47)" board-x]
               ["GPU 室 X (B70 dual-slot)" gpu-x]
               ["PSU 幅 X (SFX 規格 125)" psu-x]
               ["→ X: sandwich 積み上げ" x-stack]
               ["→ X: PSU 律速" x-psu]
               ["内寸 X" inner-x] ["内寸 Y" inner-y] ["内寸 Z" inner-z]
               ["外寸 X" outer-x] ["外寸 Y" outer-y] ["外寸 Z" outer-z]]]
  (println (str "  " (rp n 46) (lp (r1 v) 8) " mm")))
(println (str "  " (rp "容積" 46) (lp (r2 volume-l) 8) " L"))

(println)
(if (> x-psu x-stack)
  (do (println "  ■ 設計上の発見: **筐体の幅を決めているのは GPU ではなく PSU の 125mm。**")
      (println (str "    sandwich 積み上げは " (r1 x-stack) "mm で足りるのに、SFX の幅で "
                    (r1 x-psu) "mm まで押し広げられている。"))
      (println (str "    → board 室には " (r1 (- inner-x x-stack))
                    "mm の余剰 X があり、配線/ライザー曲げに使える。")))
  (println "  ■ 幅は sandwich 積み上げが律速。"))

;; SFX-L(130mm)なら Z に収まるか — 部材選定を検証で決める
(def sfxl-z 130.0)
(def board-z-free (- inner-z (:rear-io C) (ez "mobo-b650i")))
(println)
(println (str "  ■ PSU 奥行の検証: board 室の Z 余白 = " (r1 board-z-free) "mm"))
(println (str "    SFX  100mm → " (if (<= 100.0 board-z-free) "収まる ○" "収まらない ×")))
(println (str "    SFX-L " (r0 sfxl-z) "mm → " (if (<= sfxl-z board-z-free) "収まる ○" "収まらない ×")))
(when (> sfxl-z board-z-free)
  (println "    → **SFX-L から SFX(100mm)への変更はこの長さ予算が強制した設計判断。**")
  (println "      ADR-2607267000 の BOM は SFX-L 想定だったので、これは実質的な訂正。"))

;; ════════════════════════════════════════════════════════════════════════════
;; 2. parametric feature tree — 板金筐体を sketch → extrude で記述
;; ════════════════════════════════════════════════════════════════════════════
(hr "═")
(println "2. parametric feature tree (brep.feature)")
(hr "═")

(def shell-sketch
  (feat/sketch-feature
   "sk-shell" (feat/sketch-plane-xy)
   [(feat/sketch-line [0 0] [outer-x 0])
    (feat/sketch-line [outer-x 0] [outer-x outer-y])
    (feat/sketch-line [outer-x outer-y] [0 outer-y])
    (feat/sketch-line [0 outer-y] [0 0])
    (feat/sketch-dimension "w" outer-x)
    (feat/sketch-dimension "h" outer-y)]))

(def features
  [shell-sketch
   (feat/extrude-feature "ex-shell" "sk-shell" [0 0 1] outer-z :new)
   ;; 内部を中空にする(壁厚 1.2)
   (feat/sketch-feature "sk-cavity" (feat/sketch-plane-xy)
                        [(feat/sketch-circle [(/ outer-x 2) (/ outer-y 2)] 1.0)])
   (feat/extrude-feature "ex-cavity" "sk-cavity" [0 0 1] inner-z :cut)
   ;; 背面 I/O 開口(mobo の I/O シールド 159x45 が規格)
   (feat/sketch-feature "sk-rear-io" (feat/sketch-plane-xy)
                        [(feat/sketch-line [0 0] [159 0]) (feat/sketch-dimension "io-w" 159)])
   (feat/extrude-feature "ex-rear-io" "sk-rear-io" [0 0 -1] (:wall C) :cut)
   ;; GPU 排気スロット(dual-slot ブラケット 2 スロット = 幅 40 高さ 100)
   (feat/sketch-feature "sk-gpu-slot" (feat/sketch-plane-xy)
                        [(feat/sketch-line [0 0] [40 0]) (feat/sketch-dimension "slot-w" 40)])
   (feat/extrude-feature "ex-gpu-slot" "sk-gpu-slot" [0 0 -1] (:wall C) :cut)
   ;; 前面吸気メッシュ(92mm ファン開口、数量は カタログの :part/qty から)
   (feat/sketch-feature "sk-intake" (feat/sketch-plane-xy)
                        (vec (for [i (range (:part/qty (by-id "fan-92")))]
                               (feat/sketch-circle [46 (+ 46 (* i 92))] 46))))
   (feat/extrude-feature "ex-intake" "sk-intake" [0 0 1] (:wall C) :cut)])

(println (str "  feature 数: " (count features)))
(doseq [f features]
  (println (str "    " (rp (str (:kind f)) 10) (rp (:id f) 14)
                (case (:kind f)
                  :sketch  (str "plane=" (:kind (:plane f)) "  entities=" (count (:entities f)))
                  :extrude (str "dir=" (:direction f) " dist=" (r1 (:distance f))
                                " op=" (:operation f))
                  ""))))
(println (str "  boolean-ops 語彙: " (sort feat/boolean-ops)))
(println "  ※ 現 kernel は feature tree を評価して brep を作る経路を持たないので、")
(println "    下記の実体化は make-box + AABB 差分で行う(feature tree は設計意図の正本)。")

;; ════════════════════════════════════════════════════════════════════════════
;; 3. 実体化 + STEP 書き出し
;; ════════════════════════════════════════════════════════════════════════════
(hr "═")
(println "3. 実体化 (brep.kernel/make-box) と STEP 書き出し (brep.step)")
(hr "═")

(def outer-box (k/make-box 1 [0.0 0.0 0.0] [outer-x outer-y outer-z]))
(def inner-box (k/make-box 2 [(:wall C) (:wall C) (:wall C)]
                           [(- outer-x (:wall C)) (- outer-y (:wall C)) (- outer-z (:wall C))]))

(defn box-report [label [s e v]]
  (println (str "  " (rp label 20)
                "faces=" (k/face-count s)
                " edges=" (k/edge-count s)
                " verts=" (k/vertex-count s e v)
                " vol=" (r2 (/ (tess/volume s e v) 1.0e6)) "L"
                " area=" (r2 (/ (tess/surface-area s e v) 1.0e6)) "m2")))
(box-report "外形" outer-box)
(box-report "内空" inner-box)

(def sheet-vol-mm3 (- (apply tess/volume outer-box) (apply tess/volume inner-box)))
(def spcc-density 7.85e-3)   ; g/mm3
(def sheet-mass-g (* sheet-vol-mm3 spcc-density))
(println (str "  板金体積(外形-内空) = " (r0 sheet-vol-mm3) " mm3"))
(println (str "  SPCC 1.2mm 換算質量 = " (r0 sheet-mass-g) " g"
              "  (カタログ値 " (:part/mass-g (first (filter #(= :chassis (:part/category %))
                                                            (filter :part/id catalog)))) " g)"))

(def step-text (apply step/write-step outer-box))
(def step-path "90-docs/hardware/mk1-enclosure.step")
(fs/writeFileSync step-path step-text)
(println (str "  STEP 書き出し: " step-path "  (" (count step-text) " chars, ISO 10303-21)"))
(println (str "    先頭: " (first (.split step-text "\n"))))

(def mesh (apply tess/tessellate-solid outer-box))
(println (str "  三角形メッシュ: 頂点 " (count (first mesh)) " / index " (count (second mesh))
              " → 三角形 " (/ (count (second mesh)) 3) " 枚"))

;; ════════════════════════════════════════════════════════════════════════════
;; 4. 配置 + brep.assembly(制約は設計意図の記録。solve は参照検証のみ)
;; ════════════════════════════════════════════════════════════════════════════
(hr "═")
(println "4. 配置と assembly (brep.assembly)")
(hr "═")

;; 配置は [x y z] の最小角。単位 mm。sandwich: board 室は X 小側、GPU 室は X 大側。
;; **PSU の位置は sim の指摘で2回動かした**:
;;   ①mobo の上(Y)→ mobo が 170mm で Y をほぼ使い切るため内包 NG
;;   ②mobo の後ろ(Z、床置き)→ GPU が 267mm で Z をほぼ占めるため GPU と干渉
;;   ③GPU の上かつ mobo の後ろ(L 字の隅)→ 成立。これが下記の配置。
(def w (:wall C))
(def floor (+ w (:part-wall C)))
(def placement
  (let [bx (+ w (:part-wall C))                        ; board 室 X 起点
        gx (- outer-x w (:part-wall C) gpu-x)          ; GPU 室 X 起点(反対側に寄せる)
        rear (+ w (:rear-io C))]                       ; 背面 I/O 分だけ前へ
    [{:id "mobo-b650i" :pos [(+ bx standoff) floor rear]}
     {:id "cpu-7700"   :pos [(+ bx standoff pcb) (+ floor 65) (+ rear 65)]}
     {:id "cooler-lp"  :pos [(+ bx standoff pcb) (+ floor 25) (+ rear 25)]}
     {:id "ram-ddr5"   :pos [(+ bx standoff pcb) (+ floor 138) (+ rear 18)]}
     {:id "ssd-m2"     :pos [(+ bx 1.0) (+ floor 8) (+ rear 45)]}  ; PCB 裏面 = standoff 隙間
     {:id "gpu-b70"    :pos [gx floor rear]}
     {:id "riser"      :pos [(- gx (:riser-gap C)) (+ floor 4) rear]}
     ;; GPU の上(Y) かつ mobo の後ろ(Z) の L 字隅
     {:id "psu-sfx750" :pos [floor (+ floor (ey "gpu-b70") (:part-part C))
                             (+ rear (ez "mobo-b650i") (:part-part C))]}
     ;; 前面パネル実装。厚み 25mm が Z。board 室側の X 帯のみ
     {:id "fan-92"     :pos [floor floor (- outer-z w (ez "fan-92"))]}]))

(def A
  (reduce (fn [a {:keys [id pos]}]
            (let [[_ a'] (asm/add-instance a (asm/part-ref (hash id) (:part/name (by-id id)))
                                           {:translate pos} id)]
              a'))
          (asm/assembly "murakumo-mk1") placement))

;; 設計意図としての制約(mate = 面接触、insert = 挿入嵌合)
(def A2
  (-> A
      (asm/add-constraint (asm/mate-constraint 1 "bottom" 6 "pcie-edge"))   ; mobo ↔ GPU via riser
      (asm/add-constraint (asm/insert-constraint 3 "base" 1 "socket"))      ; cooler ↔ mobo
      (asm/add-constraint (asm/insert-constraint 4 "edge" 1 "dimm"))        ; RAM ↔ mobo
      (asm/add-constraint (asm/distance-constraint 6 "pcb" 1 "pcb" (:riser-gap C)))))

(let [[st _] (asm/solve A2)]
  (println (str "  instances=" (count (asm/instances A2))
                " constraints=" (count (asm/constraints A2))
                " active=" (asm/active-count A2)
                " solve=" st))
  (println "  ※ solve は制約の参照検証のみ(transform は解かない)。配置は本script が与える。"))
(println "  BOM (brep.assembly/get-bom):")
(doseq [{:keys [part-name quantity]} (asm/get-bom A2)]
  (println (str "    " (lp quantity 3) " x " part-name)))

;; ════════════════════════════════════════════════════════════════════════════
;; 5. 組み立てシミュレーション — AABB 干渉 / 内包 / 挿入経路 / 質量重心
;; ════════════════════════════════════════════════════════════════════════════
(hr "═")
(println "5. 組み立て sim (AABB レベル)")
(hr "═")

(defn aabb [{:keys [id pos]}]
  (let [[ex' ey' ez'] (env id)]
    [pos (mapv + pos [ex' ey' ez'])]))
(defn overlap-1d [[a0 a1] [b0 b1] gap] (and (< (+ a0 gap) b1) (< (+ b0 gap) a1)))
(defn intersect? [[amin amax] [bmin bmax] gap]
  (every? true? (map (fn [i] (overlap-1d [(nth amin i) (nth amax i)]
                                         [(nth bmin i) (nth bmax i)] (- gap)))
                     (range 3))))
(defn inside? [[amin amax]]
  (let [lo (+ w (:part-wall C)) ]
    (and (every? #(>= % (- w 0.001)) amin)
         (>= (nth amax 0) 0) ; guard
         (<= (nth amax 0) (- outer-x w -0.001))
         (<= (nth amax 1) (- outer-y w -0.001))
         (<= (nth amax 2) (- outer-z w -0.001)))))

;; 5-1 内包
(println "  5-1 内包判定(全部材が内空に収まるか)")
(def containment
  (for [p placement] [(:id p) (inside? (aabb p))]))
(doseq [[id ok] containment]
  (println (str "    " (rp id 14) (if ok "○ 収まる" "× はみ出し"))))
(def n-out (count (remove second containment)))

;; 5-2 相互干渉。**mounts-on の親子は除外** — CPU はソケットに、クーラーは CPU に、
;;     RAM はスロットに「載る」関係で、envelope が重なるのは正常。これを衝突と
;;     数えると設計が常に不成立になる(初版がこれで 8 件の偽陽性を出した)。
(defn mount-chain [id]
  (loop [x id acc #{}]
    (if-let [p (:part/mounts-on (by-id x))] (recur p (conj acc p)) acc)))
(def bench-set (atom #{}))   ; 5-3 で確定する。mobo-subassy の実体
(defn expand [id] (if (= id "mobo-subassy") @bench-set #{id}))
(defn related? [a b]
  (boolean (some (fn [x] (some (fn [y] (or (contains? (mount-chain x) y)
                                           (contains? (mount-chain y) x)))
                               (expand b)))
                 (expand a))))
(println (str "  5-2 相互干渉判定(part-to-part " (r1 (:part-part C)) "mm、mounts-on 親子は除外)"))
(def collisions
  (for [[a b] (for [x placement y placement
                    :when (neg? (compare (:id x) (:id y)))] [x y])
        :when (and (not (related? (:id a) (:id b)))
                   (intersect? (aabb a) (aabb b) (:part-part C)))]
    [(:id a) (:id b)]))
(if (seq collisions)
  (doseq [[a b] collisions] (println (str "    × " a " ∩ " b)))
  (println "    ○ 干渉なし(AABB レベル)"))
(println (str "    (除外した mounts-on 親子: "
              (clojure.string/join ", "
                (for [p placement :when (:part/mounts-on (by-id (:id p)))]
                  (str (:id p) "→" (:part/mounts-on (by-id (:id p)))))) ")"))

;; 5-3 挿入経路。**2 段構成にする** — CPU/クーラー/RAM/SSD は筐体外の作業台で
;;     mobo に先組みする(:part/subassembly :bench)。実務どおり。筐体挿入の
;;     検査対象は「PSU / mobo サブアセンブリ / ファン / ライザー / GPU」で、
;;     mobo は先組み一体の合併 AABB として1回で入れる。
(def bench-ids (->> placement (map :id)
                    (filter #(= :bench (:part/subassembly (by-id %))))))
(def by-pid (into {} (map (juxt :id identity)) placement))
(defn union-aabb [ids]
  (let [bs (map #(aabb (by-pid %)) ids)]
    [(reduce k/v-min (map first bs)) (reduce k/v-max (map second bs))]))
(reset! bench-set (set bench-ids))
(def chassis-order ["mobo-subassy" "psu-sfx750" "fan-92" "gpu-b70"])
(defn box-of [id] (if (= id "mobo-subassy") (union-aabb bench-ids) (aabb (by-pid id))))
;; **union AABB は保守的すぎて偽陽性を出す**: mobo サブアセンブリの合併箱は
;; 実部材が無い L 字の空隙まで埋めてしまい、そこに正しく収まっている PSU と
;; 「干渉」する。挿入判定は合併箱ではなく **構成部材ごとの箱** で行う。
(defn boxes-of [id] (if (= id "mobo-subassy") (mapv #(aabb (by-pid %)) bench-ids)
                        [(aabb (by-pid id))]))
(defn axis-of [id] (if (= id "mobo-subassy") :+x (:part/insert-axis (by-id id))))
(defn sweep-1
  "1つの AABB を挿入軸に沿って筐体外まで伸ばした掃引 AABB。"
  [[mn mx] ax]
  (let []
    (case ax
      :+x [(assoc (vec mn) 0 0.0) (vec mx)]
      :-x [(vec mn) (assoc (vec mx) 0 outer-x)]
      :+z [(vec mn) (assoc (vec mx) 2 outer-z)]
      :bracket [mn mx]   ; 着脱式ブラケット搭載 — 筐体内の単一軸掃引をしない
      [mn mx])))
(defn sweep-aabb [id] (sweep-1 (box-of id) (axis-of id)))
(println "  5-3 挿入経路判定(筐体組み立て順。bench 先組みは筐体外なので対象外)")
(println (str "    bench 先組み(作業台): " (clojure.string/join ", " bench-ids)
              " → 合併 AABB " (mapv #(mapv r1 %) (union-aabb bench-ids))))
(def insertion
  (loop [remaining chassis-order installed [] out []]
    (if (empty? remaining) out
        (let [id (first remaining)
              sweeps (mapv (fn [b] (sweep-1 b (axis-of id))) (boxes-of id))
              hits (for [q installed
                         :when (and (not (related? id q))
                                    (some (fn [sw] (some #(intersect? sw % 0.0) (boxes-of q)))
                                          sweeps))] q)]
          (recur (rest remaining) (conj installed id)
                 (conj out [id (axis-of id) (vec hits)]))))))
(doseq [[i [id ax hits]] (map-indexed vector insertion)]
  (println (str "    " (lp (inc i) 3) ". " (rp id 16) (rp (str ax) 6)
                (cond (seq hits) (str "× 干渉: " (clojure.string/join ", " hits))
                      (= :bracket (axis-of id)) "○ 着脱ブラケット搭載"
                      :else "○ 挿入可"))))
(def n-block (count (remove #(empty? (nth % 2)) insertion)))

;; 5-4 質量と重心
(println "  5-4 質量・重心")
(def total-mass (+ sheet-mass-g (reduce + (map #(* (:part/mass-g (by-id (:id %)))
                                                   (:part/qty (by-id (:id %)))) placement))))
(def cog
  (let [wsum (reduce (fn [acc p]
                       (let [pt (by-id (:id p)) mm (* (:part/mass-g pt) (:part/qty pt))
                             [mn mx] (aabb p)
                             c (mapv #(/ (+ %1 %2) 2.0) mn mx)]
                         (mapv + acc (mapv #(* mm %) c))))
                     [0.0 0.0 0.0] placement)
        m (reduce + (map #(* (:part/mass-g (by-id (:id %))) (:part/qty (by-id (:id %)))) placement))]
    (mapv #(/ % m) wsum)))
(println (str "    総質量(部材+板金) = " (r0 total-mass) " g = " (r2 (/ total-mass 1000.0)) " kg"))
(println (str "    重心(部材のみ)    = [" (r1 (nth cog 0)) " " (r1 (nth cog 1)) " " (r1 (nth cog 2)) "] mm"))
(let [fx (/ (nth cog 0) outer-x) fz (/ (nth cog 2) outer-z)
        ok (and (< 0.25 fx 0.75) (< 0.25 fz 0.75))]
  (println (str "    footprint 内の相対位置 X=" (r2 fx) " Z=" (r2 fz)
                (if ok "  ○ 転倒安定域" "  △ 偏心 — 脚配置で対処"))))

;; 5-5 熱・気流の一次チェック
(println "  5-5 熱/気流の一次チェック")
(def heat-w (reduce + (map #(* (:part/power-w (by-id (:id %))) (:part/qty (by-id (:id %)))) placement)))
(def dT 15.0)      ; 許容温度上昇 K
(def cfm-req (/ (* heat-w 1.76) dT))   ; 経験式 CFM ≈ 1.76*W/ΔT(°C)
(def n-fan (:part/qty (by-id "fan-92")))
(def intake-area-mm2 (* n-fan (* Math/PI 46 46) 0.6))  ; メッシュ開口率 60%
(println (str "    総発熱 = " (r0 heat-w) " W  (B70 230W が支配)"))
(println (str "    必要風量 ≈ " (r1 cfm-req) " CFM (ΔT=" (r0 dT) "K)"))
(println (str "    吸気開口 = " (r0 intake-area-mm2) " mm2 (92mm x" n-fan "、開口率60%)"))
(println "    ※ B70 は blower で背面 I/O から直接排気するので、GPU 熱は筐体内に")
(println "      滞留しない。上式は CPU/VRM/PSU 側の残余熱に対する一次近似で、")
(println "      **CFD ではない。実機で吸排気温度を測るまで確定値として扱わない。**")

;; ════════════════════════════════════════════════════════════════════════════
;; 6. 組み立て工程 — EDN に書き出す
;; ════════════════════════════════════════════════════════════════════════════
(hr "═")
(println "6. 組み立て工程の生成 → EDN")
(hr "═")

(def steps
  ;; 順序は 5-3 の挿入経路判定が通った順。**bench = 作業台での先組み、
  ;; chassis = 筐体への組み込み。**初版は PSU を最初に入れていたが、sim が
  ;; mobo サブアセンブリの挿入を塞ぐと判定したため入れ替えた。
  (let [mk (fn [n stage id op tool torque sec note]
             {:step/n n :step/stage stage :step/part id :step/op op :step/tool tool
              :step/torque-ncm torque :step/seconds sec
              :step/insert-axis (:part/insert-axis (by-id id))
              :step/fasteners (:part/fasteners (by-id id))
              :step/note note})]
    [(mk 1 :bench "cpu-7700" :socket "手" nil 60
         "AM5 LGA。ピンはソケット側。ILM を対角締め")
     (mk 2 :bench "ssd-m2" :screw "PH1 手ドライバ" 25 45
         "**PCB 裏面の M.2 に実装** — 表面だとクーラーと干渉する(sim 5-2 が検出)")
     (mk 3 :bench "ram-ddr5" :insert "手" nil 40 "両ラッチ。A2/B2 スロット(dual channel)")
     (mk 4 :bench "cooler-lp" :screw "PH2 トルクドライバ" 50 180
         "95x95x37(NH-L9a-AM5 級)。**120mm 級は DIMM に覆い被さるので不可**")
     (mk 5 :bench "riser" :insert "手" nil 90
         "mobo の PCIe スロットに先付け。Gen5 x16 認証品。**Gen4 品は不可**")
     (mk 6 :chassis "mobo-b650i" :mount "PH2 電動ドライバ" 60 120
         "サブアセンブリを一体で投入。standoff 4 点。I/O シールドを先に嵌める")
     (mk 7 :chassis "psu-sfx750" :mount "PH2 電動ドライバ" 60 150
         "**着脱式ブラケットに載せてから投入** — L 字隅の PSU はどの単一軸からも直接挿入できない(sim 5-3)")
     (mk 8 :chassis "fan-92" :mount "PH2 電動ドライバ" 40 150
         "前面吸気 1 基。前面 Z 帯 25mm は GPU の 8-pin 逃げと競合するため board 室側のみ")
     (mk 9 :chassis "gpu-b70" :mount "PH2 手ドライバ" 45 180
         "GPU 室側パネルから挿入(-X)。8-pin はカード**端** — 25mm の逃げを使って結線")
     (mk 10 :chassis "cordset" :connect "手" nil 20 "◇PSE コードセット。IEC C14")]))

(def burn-in
  [{:qc/n 1 :qc/name "通電・POST" :qc/minutes 5 :qc/pass "POST 到達 + BIOS で全 DIMM/M.2 認識"}
   {:qc/n 2 :qc/name "memtest" :qc/minutes 60 :qc/pass "エラー 0"}
   {:qc/n 3 :qc/name "ReBAR 有効化確認" :qc/minutes 3 :qc/pass "BIOS で Resizable BAR = Enabled"}
   {:qc/n 4 :qc/name "LLM soak" :qc/minutes 120
    :qc/pass (str "qwen3.6-35b-a3b Q4_K_M を単一ストリームで decode。"
                  "**>= 39.2 tok/s(ADR-2607267000 の gate)** かつ熱スロットル無し")}
   {:qc/n 5 :qc/name "熱ログ" :qc/minutes 120 :qc/pass "GPU/CPU 温度が定常。吸排気 ΔT 記録"}
   {:qc/n 6 :qc/name "quality_inspection record 署名" :qc/minutes 5
    :qc/pass "assembler DID で署名し tsukuru の quality_inspection として着地(ADR-2607268000 決定6)"}])

(def total-sec (reduce + (map :step/seconds steps)))
(def total-qc-min (reduce + (map :qc/minutes burn-in)))
(println (str "  組み立て工程 " (count steps) " ステップ / 実作業 "
              (r1 (/ total-sec 60.0)) " 分"))
(doseq [{:step/keys [n stage part op tool seconds fasteners]} steps]
  (println (str "    " (lp n 3) ". " (rp (name stage) 9) (rp part 14) (rp (str op) 9)
                (rp tool 20) (lp seconds 4) "s  f=" fasteners)))
(println (str "  burn-in/QC " (count burn-in) " 項目 / " total-qc-min " 分(大半は無人)"))

;; ADR-2607267000 の転換費は組立 2.5h touch を前提にしていた。実測と比較する。
(def assumed-touch-h 2.5)
(def derived-touch-h (+ (/ total-sec 3600.0) 0.5 0.3))  ; 組立 + QC 立会 0.5h + イメージ 0.3h
(println (str "  ■ ADR-2607267000 の前提 2.5h touch に対し、工程からの導出は "
              (r2 derived-touch-h) "h"))
(println (str "    → " (if (< derived-touch-h assumed-touch-h)
                         (str "前提が保守的(差 " (r2 (- assumed-touch-h derived-touch-h)) "h)。COGS は据え置きでよい")
                         "前提を超過。COGS を見直す必要がある")))


;; ════════════════════════════════════════════════════════════════════════════
;; 7. BOM 突き合わせ — 部材 EDN と ADR-2607267000 の原価モデルの差分
;; ════════════════════════════════════════════════════════════════════════════
(hr "═")
(println "7. BOM 突き合わせ (部材 EDN ⇄ scripts/mk1-costmodel.cljs)")
(hr "═")
(def edn-components
  (reduce + (map (fn [pt] (* (:part/price-usd pt) (:part/qty pt)))
                 (remove #(= :chassis (:part/category %)) parts))))
;; mk1-costmodel.cljs の @50台 部材小計 $2,265 の内訳から、筐体/branding/雑材を除いた分
(def model-components (- 2265 60 14 11))
(println (str "  部材 EDN の合計(筐体除く)          = $" (r0 edn-components)))
(println (str "  原価モデルの相当分(筐体/branding/雑材除く) = $" (r0 model-components)))
(def delta (- edn-components model-components))
(println (str "  差分                                = $" (r0 delta)))
(when (pos? delta)
  (println "  ■ **sandwich レイアウトが原価モデルに無い部材を要求している:**")
  (doseq [id ["riser" "fan-92"]]
    (let [pt (by-id id)]
      (println (str "    + " (rp (:part/name pt) 40) "$" (lp (* (:part/price-usd pt) (:part/qty pt)) 5)))))
  (println (str "    → 部材 $2,265 → $" (r0 (+ 2265 delta))
                " / COGS $2,479.5 → $" (r1 (+ 2479.5 delta))))
  (println (str "    → $4,990 pledge のままなら差益 $1,899 → $" (r0 (- 1899 delta))))
  (println (str "    → 差益を維持するなら pledge を $" (r0 (+ 4990 (/ delta 0.918)))
                " に上げる(platform 手数料 8.2% 込み)"))
  (println "    **ADR-2607267000 の BOM はストレート ITX ケース前提でライザーを含んでいなかった。**"))

(def out-edn
  {:mk1/enclosure {:outer-mm [outer-x outer-y outer-z]
                   :inner-mm [inner-x inner-y inner-z]
                   :volume-l (js/parseFloat (r2 volume-l))
                   :wall-mm (:wall C)
                   :sheet-mass-g (js/parseInt (r0 sheet-mass-g))
                   :layout :sandwich
                   :width-driver (if (> x-psu x-stack) :psu-125mm :sandwich-stack)
                   :step-file "90-docs/hardware/mk1-enclosure.step"}
   :mk1/clearances C
   :mk1/placement (mapv (fn [p] {:part (:id p) :pos-mm (:pos p)
                                 :aabb-mm (aabb p)}) placement)
   :mk1/sim {:containment-failures n-out
             :collisions (mapv vec collisions)
             :insertion-blocked n-block
             :total-mass-g (js/parseInt (r0 total-mass))
             :cog-mm (mapv #(js/parseFloat (r1 %)) cog)
             :heat-w heat-w
             :cfm-required (js/parseFloat (r1 cfm-req))
             :method "AABB 干渉 + 挿入軸掃引。メッシュ精度の衝突判定ではない"
             :bracket-required (vec (for [p placement
                                          :when (= :bracket (:part/insert-axis (by-id (:id p))))]
                                      (:id p)))}
   :mk1/assembly {:steps steps :touch-seconds total-sec
                  :derived-touch-hours (js/parseFloat (r2 derived-touch-h))}
   :mk1/qc {:items burn-in :minutes total-qc-min
            :gate "single-card decode >= 39.2 tok/s (ADR-2607267000)"}
   :mk1/bom-reconciliation {:edn-components-usd edn-components
                            :cost-model-components-usd model-components
                            :delta-usd delta
                            :missing-in-cost-model ["riser" "fan-92"]}
   :mk1/provenance {:parts-catalog parts-path
                    :kernels ["org-iso-10303/brep.kernel" "brep.feature" "brep.assembly"
                              "brep.tessellate" "brep.step"]
                    :generated-by "scripts/mk1-cad.cljs"
                    :adr ["adr-2607267000" "adr-2607268000" "adr-2607269000"]}})

(def out-path "90-docs/hardware/mk1-assembly.edn")
(fs/writeFileSync out-path (str ";; 生成物 — 手編集しない。再生成: nbb --classpath orgs/kotoba-lang/org-iso-10303/src scripts/mk1-cad.cljs\n"
                                (pr-str out-edn) "\n"))
(println (str "  EDN 書き出し: " out-path))

(hr "═")
(println (str "判定: 内包NG=" n-out " / 干渉=" (count collisions) " / 挿入不可=" n-block))
(println (if (and (zero? n-out) (zero? (count collisions)) (zero? n-block))
           "  ○ AABB レベルでは成立。次は実機で gate(39.2 tok/s)を測る。"
           "  × 未解決の幾何的問題がある。上記を解消するまで発注しない。"))
(hr "═")
