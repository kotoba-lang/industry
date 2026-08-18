(ns tower100-b70-cad
  "Thermaltake The Tower 100 + Intel Arc Pro B70 Creator 構成の 3D モデル化と
   組み立て成立性の検査。

   既存 kernel を消費者として使い、新規に幾何を書かない(ADR-2607255000 / 2607270000):
     brep.kernel     (org-iso-10303)   — make-box / bounding-box
     brep.tessellate (同)              — volume / surface-area / 三角形メッシュ
     brep.step       (同)              — ISO 10303-21 (STEP) 書き出し・読み戻し
     brep.assembly   (同)              — part-instance / 制約 / BOM
     kami-cfd.duct   (kami-engine-cfd) — 検証済み D3Q19 LBM(内部流 BC)
   Rust は書かない(ADR-2607072000)。nbb / cljs のみ(ADR-2607173000)。

   実行:
     nbb --classpath orgs/kotoba-lang/org-iso-10303/src:orgs/kotoba-lang/kami-engine-cfd/src \\
         scripts/tower100-b70-cad.cljs

   **MK-1(ADR-2607270000)との決定的な違い。**
   MK-1 は自社板金なので『部材 envelope + クリアランス規則から筐体寸法を導出』した。
   ここは **市販ケース**なので寸法は与えられており、導出するのは逆に **内部座標**である。
   そして市販ケースの内部形状は公表されていない —— 公表されているのは
   VGA 330 / cooler 190 / PSU 180 / slots 2 の 4 つのクリアランス制限だけ。

   したがって判定を 2 段に分ける。混ぜると、推定した内部座標に対する AABB 判定が
   メーカー公表値に対する判定と同じ顔をしてしまう:

     [A] 公表クリアランスに対する判定  — grade :spec / :datasheet。**これが契約**
     [B] 推定内部座標に対する AABB sim — grade :estimate。補助。実形状ではない

   **AABB は『重ならない』の意味であって、メッシュ精度の衝突判定ではない。**")

(require '[clojure.string :as str]
         '[brep.kernel :as k]
         '[brep.assembly :as asm]
         '[brep.tessellate :as tess]
         '[brep.step :as step]
         '[kami-cfd.duct :as duct]
         '["fs" :as fs]
         '[cljs.reader :as edn])

;; ── 書式ヘルパ(cljs に format は無い) ─────────────────────────────────────
(defn rp [s w] (let [s (str s)] (str s (apply str (repeat (max 0 (- w (count s))) " ")))))
(defn lp [s w] (let [s (str s)] (str (apply str (repeat (max 0 (- w (count s))) " ")) s)))
(defn r1 [x] (str (/ (Math/round (* 10.0 (double x))) 10.0)))
(defn r2 [x] (str (/ (Math/round (* 100.0 (double x))) 100.0)))
(defn r0 [x] (str (Math/round (double x))))
(defn hr [c] (println (apply str (repeat 78 c))))

;; ── 部材カタログ(EDN が正本。寸法をコードに焼かない) ──────────────────────
;; カタログのパスは引数で差し替えられる。**判別能力の実測(壊したコピーで赤くなるか)を
;; するため**であって、本番は既定値を使う。
(def default-parts-path "90-docs/hardware/tower100-b70-parts.datoms.edn")
(def parts-path (or (first *command-line-args*) default-parts-path))
;; 既定カタログのときだけ成果物を書く。**壊したコピーでの判別実測が、committed な
;; STEP / assembly.edn を静かに上書きするのを防ぐ**(実際に一度やった)。
(def write-artifacts? (= parts-path default-parts-path))
(def catalog (edn/read-string (fs/readFileSync parts-path "utf8")))
(def parts (->> catalog (filter :part/id)))
(def limits (->> catalog (filter :limit/id)))
(def cables (->> catalog (filter :cable/id)))
(def by-id (into {} (map (juxt :part/id identity)) parts))
(def lim-by-id (into {} (map (juxt :limit/id identity)) limits))
(defn env [id] (edn/read-string (:part/envelope (by-id id))))
(defn tol [id] (edn/read-string (:part/tolerance-mm (by-id id))))
(defn ex [id] (nth (env id) 0))
(defn ey [id] (nth (env id) 1))
(defn ez [id] (nth (env id) 2))
(defn grade [id] (:part/dim-grade (by-id id)))

(def findings (atom []))                     ; [severity id text]
(defn finding! [sev id text] (swap! findings conj [sev id text]))

(hr "═")
(println "Tower 100 + Arc Pro B70 — 3D モデル化と組み立て成立性の検査")
(println (str "部材カタログ: " parts-path
              "  (" (count parts) " 部材 / " (count limits) " 公表制限 / " (count cables) " ケーブル)"))
(hr "═")

;; ════════════════════════════════════════════════════════════════════════════
;; [A] メーカー公表クリアランスに対する判定 —— これが組み立て成立性の契約
;; ════════════════════════════════════════════════════════════════════════════
(println)
(hr "═")
(println "[A] 公表クリアランスに対する判定 (grade :spec/:datasheet — これが契約)")
(hr "═")

;; ASRock は B70 について「電源ケーブル取り付けのため長さ方向に +30mm」を明記する。
;; この筐体では GPU が天面から吊り下がるので、その 30mm はカード下端の下に要る。
(def gpu-cable-allowance 30.0)

(def checks-a
  [{:id "vga-length"
    :name "GPU 長さ(カード + 電源ケーブル逃げ)"
    :need (+ (ey "gpu-b70") gpu-cable-allowance)
    :limit (:limit/max-mm (lim-by-id "lim-vga"))
    :unit "mm" :grade :datasheet
    :note (str "カード " (r0 (ey "gpu-b70")) "mm + ASRock 指定の +"
               (r0 gpu-cable-allowance) "mm")}
   {:id "vga-slots"
    :name "GPU スロット厚"
    :need 2 :limit (:limit/max-mm (lim-by-id "lim-slots"))
    :unit "slot" :grade :datasheet
    :note "B70 Creator は 2-slot(39mm)。筐体の拡張スロットは 2"}
   {:id "cooler-height"
    :name "CPU クーラー高さ"
    :need (ex "cooler-wraith") :limit (:limit/max-mm (lim-by-id "lim-cooler"))
    :unit "mm" :grade :estimate
    :note "7500F BOX 同梱の Wraith Stealth。寸法は推定だが余裕が桁違い"}
   {:id "psu-length"
    :name "PSU 奥行"
    :need (ez "psu-pro750b") :limit (:limit/max-mm (lim-by-id "lim-psu"))
    :unit "mm" :grade :datasheet
    :note "PRO-750B は PS2/ATX 標準 140mm 奥行"}
   {:id "mobo-form"
    :name "マザーボード外形(Mini-ITX 170mm 角)"
    :need (ey "mobo-b650i") :limit 170
    :unit "mm" :grade :spec
    :note "筐体は 6.7\" x 6.7\" (Mini ITX) を受ける"}])

(println (str "  " (rp "検査" 30) (lp "必要" 9) (lp "制限" 9) (lp "余裕" 9) "  判定"))
(hr "─")
(doseq [{:keys [id name need limit unit grade note]} checks-a]
  (let [slack (- limit need)
        ok (>= slack 0)]
    (when-not ok (finding! :block id (str name ": " (r1 need) unit " > 制限 " limit unit)))
    (println (str "  " (rp name 30) (lp (r1 need) 9) (lp (str limit) 9) (lp (r1 slack) 9)
                  "  " (if ok "○" "× 不成立") "  [" (subs (str grade) 1) "]"))
    (println (str "  " (rp "" 30) "  " note))))

;; 140mm ファンの搭載可否は「サイズが載るか」ではなく「140 を受ける口が何箇所あるか」
(def fan-140-mounts 3)                      ; Top / Rear / Power Cover(公式 Fan Support)
(def fan-qty (:part/qty (by-id "fan-140")))
(println)
(println (str "  140mm ファン: 手配 " fan-qty " 個 / 筐体側の 140mm 受け口 "
              fan-140-mounts " 箇所 (Top / Rear / Power Cover)  "
              (if (<= fan-qty fan-140-mounts) "○" "× 余る")))
(when (> fan-qty fan-140-mounts)
  (finding! :block "fan-mounts" "140mm ファンの数が受け口を超える"))
(println "    ※ 筐体には 120mm 排気が Top / Rear に 2 個プリインストール済み。")
(println "      F140Q 2 個は『追加』ではなく『置換 + 1 箇所増設』になる。")

;; ════════════════════════════════════════════════════════════════════════════
;; [B] 内部座標の導出 —— 公表クリアランスから逆算する(写真から推定しない)
;; ════════════════════════════════════════════════════════════════════════════
(println)
(hr "═")
(println "[B] 内部座標の導出(公表制限からの逆算。grade :estimate)")
(hr "═")

(def outer (env "chassis-tower100"))
(def outer-x (nth outer 0)) (def outer-y (nth outer 1)) (def outer-z (nth outer 2))

;; 側面はガラス 4mm + フレーム。内空は公表されていないので許容を置く。
(def side-allow 8.0)
(def inner-x (- outer-x (* 2 side-allow)))
(def inner-z (- outer-z (* 2 side-allow)))

;; 縦方向は「公表制限から逆算する」——ここが導出の要。
;; PSU(86mm)の上に化粧カバーが載り、その上端が VGA 330mm の下端になる。
(def psu-h (ey "psu-pro750b"))
(def psu-cover 19.0)
(def psu-cover-y (+ psu-h psu-cover))                 ; = 105
(def vga-limit (:limit/max-mm (lim-by-id "lim-vga")))
(def bracket-y (+ psu-cover-y vga-limit))             ; = 435 : PCI ブラケット面
(def top-allow 5.0)
(def inner-y (+ bracket-y top-allow))

(def standoff 6.0) (def pcb 1.6)
(def comp-x (+ standoff pcb))                          ; mobo 実装面の X
(def mobo-z0 40.0)

(println (str "  外形 = [" (r1 outer-x) " " (r1 outer-y) " " (r1 outer-z) "] mm = "
              (r2 (/ (* outer-x outer-y outer-z) 1.0e6)) " L"))
(println (str "  内空(推定) = [" (r1 inner-x) " " (r1 inner-y) " " (r1 inner-z) "] mm"))
(println (str "  PSU 上端 " (r1 psu-h) " + カバー " (r1 psu-cover) " = " (r1 psu-cover-y) "mm"))
(println (str "  → ブラケット面 Y = " (r1 psu-cover-y) " + VGA 制限 " vga-limit
              " = " (r1 bracket-y) "mm  (これが GPU が吊り下がる基準面)"))
(println (str "  内空高さ " (r1 inner-y) " ≦ 外形高さ " (r1 outer-y) " → "
              (if (<= inner-y outer-y) "○ 整合" "× 逆算が外形を超える")))
(when (> inner-y outer-y)
  (finding! :block "inner-y" "逆算した内空高さが外形を超える — 前提が誤り"))

;; 配置(最小角 [x y z])。GPU は天面から下へ吊り下がる。
(def gpu-y0 (- bracket-y (ey "gpu-b70")))
(def slot-z 52.0)                                      ; x16 スロット中心の Z(推定)
(def socket-z 140.0)                                   ; AM5 ソケット中心の Z(推定)
(def socket-y (- bracket-y 60.0))              ; AM5 ソケット中心の Y(I/O 端から 60mm)
(def placement
  [{:id "mobo-b650i"    :pos [0.0 (- bracket-y (ey "mobo-b650i")) mobo-z0]}
   {:id "cpu-7500f"     :pos [comp-x (- socket-y 20.0) (- socket-z 20.0)]}
   {:id "cooler-wraith" :pos [comp-x (- socket-y 47.5) (- socket-z 47.5)]}
   {:id "ram-ddr5"      :pos [comp-x (- socket-y 80.0) (+ mobo-z0 15.0)]}
   {:id "ssd-m2"        :pos [1.0 (- socket-y 120.0) (+ mobo-z0 50.0)]}  ; 裏面 M.2
   {:id "gpu-b70"       :pos [comp-x gpu-y0 (- slot-z 30.0)]}
   {:id "psu-pro750b"   :pos [40.0 0.0 (- inner-z (ez "psu-pro750b"))]}])

(println)
(println (str "  " (rp "部材" 16) (rp "最小角 [x y z]" 26) (rp "寸法 [x y z]" 24) "grade"))
(hr "─")
(doseq [{:keys [id pos]} placement]
  (println (str "  " (rp id 16)
                (rp (str "[" (str/join " " (map r1 pos)) "]") 26)
                (rp (str "[" (str/join " " (map r1 (env id))) "]") 24)
                (subs (str (grade id)) 1))))

;; ── GPU 下端の逃げ ──────────────────────────────────────────────────────────
(def gpu-cable-gap (- gpu-y0 psu-cover-y))
(println)
(println (str "  GPU 下端 Y = " (r1 gpu-y0) " / PSU カバー上端 Y = " (r1 psu-cover-y)
              " → 逃げ " (r1 gpu-cable-gap) "mm  (ASRock 要求 "
              (r0 gpu-cable-allowance) "mm) "
              (if (>= gpu-cable-gap gpu-cable-allowance) "○" "× 不足")))
(when (< gpu-cable-gap gpu-cable-allowance)
  (finding! :block "gpu-cable-gap" "GPU 下端の 12V-2x6 逃げが不足"))

;; ── 導出そのものの整合検査 ─────────────────────────────────────────────────
;; 逆算した bracket-y が正しければ、その上に 140mm ファン(厚み 25mm)が載る隙間が
;; 残っていなければならない —— メーカーが Top 140mm を受けると公表しているから。
;; 残らなければ、逆算に使った psu-cover 19mm の見立てが大きすぎるということ。
;; **この検査は構成の可否ではなく、私のモデルの分解能を測っている。**
(def top-space (- outer-y bracket-y))
(def fan-thick (ez "fan-140"))
(println (str "  導出の整合: ブラケット面より上の空間 " (r1 top-space)
              "mm vs Top 140mm ファン厚 " (r1 fan-thick) "mm → "
              (if (>= top-space fan-thick)
                (str "○ 残余 " (r1 (- top-space fan-thick)) "mm")
                "△ 逆算が過大 — psu-cover の見立てを疑う")))
(println (str "    ※ 残余 " (r1 (- top-space fan-thick))
              "mm は、この spec 逆算モデルの分解能そのもの。"))
(println "      これより細かい内部寸法の主張はこのモデルからは出せない。")

;; X 方向: 最も張り出すのは CPU クーラーではなく GPU(カード高さ 112mm)
(def gpu-x-max (+ comp-x (ex "gpu-b70")))
(def cooler-x-max (+ comp-x (ex "cooler-wraith")))
(println (str "  X 張り出し: GPU " (r1 gpu-x-max) "mm > クーラー " (r1 cooler-x-max)
              "mm / 内空幅 " (r1 inner-x) "mm → "
              (if (<= gpu-x-max inner-x) (str "○ 余裕 " (r1 (- inner-x gpu-x-max)) "mm") "×")))
(println "    ※ この筐体で幅を決めるのは CPU クーラーではなく GPU のカード高さ。")

;; ════════════════════════════════════════════════════════════════════════════
;; 3. 3D モデルの実体化 (brep.kernel) と STEP 書き出し
;; ════════════════════════════════════════════════════════════════════════════
(println)
(hr "═")
(println "3. 3D モデルの実体化 (brep.kernel/make-box) と STEP 書き出し")
(hr "═")

(def outer-box (k/make-box 1 [0.0 0.0 0.0] [outer-x outer-y outer-z]))
(def inner-box (k/make-box 2 [side-allow 0.0 side-allow]
                           [(+ side-allow inner-x) inner-y (+ side-allow inner-z)]))

(defn box-report [label [s e v]]
  (println (str "  " (rp label 22) "面 " (lp (k/face-count s) 2)
                " / 稜線 " (lp (count e) 3) " / 頂点 " (lp (count v) 3)
                " / 体積 " (lp (r0 (tess/volume s e v)) 10) " mm3")))
(box-report "筐体 外形" outer-box)
(box-report "筐体 内空(推定)" inner-box)

;; 各部材を solid にする(3D モデル本体)。id は 10 番から。
(def part-solids
  (vec (map-indexed
        (fn [i {:keys [id pos]}]
          (let [[dx dy dz] (env id)
                mn (mapv double pos)
                mx (mapv + mn [dx dy dz])]
            {:id id :box (k/make-box (+ 10 i) mn mx) :min mn :max mx}))
        placement)))
(println (str "  部材 solid: " (count part-solids) " 個"))
(doseq [{:keys [id box]} part-solids]
  (let [[s e v] box]
    (println (str "    " (rp id 16) "面 " (k/face-count s) " / 稜線 " (count e)
                  " / 頂点 " (count v) " / 体積 " (lp (r0 (apply tess/volume box)) 9) " mm3"))))

;; STEP は 1 solid = 1 ファイル(write-step は MANIFOLD_SOLID_BREP を 1 つ出す)
(def step-text (apply step/write-step outer-box))
(def step-path "90-docs/hardware/tower100-b70-chassis.step")
(if write-artifacts?
  (do (fs/writeFileSync step-path step-text)
      (println (str "  STEP 書き出し: " step-path " (" (count step-text) " chars)")))
  (println (str "  STEP 書き出し: SKIP(非既定カタログ) — " (count step-text) " chars を生成のみ")))
(println (str "    先頭: " (first (.split step-text "\n"))))

;; 往復検証 —— 書いたものが読み戻せることを実際に確かめる
(let [rt (step/read-step step-text)
      [s e v] outer-box
      rs (first rt)]
  (println (str "  STEP 往復: 面 " (k/face-count rs) "/" (k/face-count s)
                " 稜線 " (count (second rt)) "/" (count e)
                " 頂点 " (count (nth rt 2)) "/" (count v)
                (if (and (= (k/face-count rs) (k/face-count s))
                         (= (count (nth rt 2)) (count v)))
                  "  ○ 一致" "  × 不一致"))))

(def mesh (apply tess/tessellate-solid outer-box))
(println (str "  三角形メッシュ: 頂点 " (count (first mesh)) " / index " (count (second mesh))))

;; assembly(設計意図の記録。solve は参照検証のみで transform は解かない)
(def A
  (reduce (fn [a {:keys [id pos]}]
            (let [[_ a'] (asm/add-instance a (asm/part-ref (hash id) (:part/name (by-id id)))
                                           {:translate pos} id)]
              a'))
          (asm/assembly "tower100-b70") placement))
(println (str "  assembly: instances=" (count (asm/instances A))
              " solve=" (first (asm/solve A))
              "  ※ solve は制約の参照検証のみ(transform は解かない)"))

;; ════════════════════════════════════════════════════════════════════════════
;; 4. 組み立て sim (AABB レベル。grade :estimate)
;; ════════════════════════════════════════════════════════════════════════════
(println)
(hr "═")
(println "4. 組み立て sim (AABB レベル / 推定内部座標)")
(hr "═")

(def part-part 2.0)
(defn aabb [{:keys [id pos]}]
  (let [[dx dy dz] (env id)] [(mapv double pos) (mapv + (mapv double pos) [dx dy dz])]))
(defn overlap-1d [[a0 a1] [b0 b1] gap] (and (< (+ a0 gap) b1) (< (+ b0 gap) a1)))
(defn intersect? [[amin amax] [bmin bmax] gap]
  (every? true? (map (fn [i] (overlap-1d [(nth amin i) (nth amax i)]
                                         [(nth bmin i) (nth bmax i)] (- gap)))
                     (range 3))))

;; 4-1 内包
(println "  4-1 内包判定(推定内空に収まるか)")
(defn inside? [[mn mx]]
  (and (>= (nth mn 0) -0.001) (<= (nth mx 0) (+ inner-x 0.001))
       (>= (nth mn 1) -0.001) (<= (nth mx 1) (+ inner-y 0.001))
       (>= (nth mn 2) -0.001) (<= (nth mx 2) (+ inner-z 0.001))))
(def containment (for [p placement] [(:id p) (inside? (aabb p))]))
(doseq [[id ok] containment]
  (println (str "    " (rp id 16) (if ok "○ 収まる" "× はみ出し"))))
(def n-out (count (remove second containment)))
(when (pos? n-out) (finding! :warn "containment" (str "内包 NG " n-out " 件")))

;; 4-2 相互干渉。
;;
;; **基板の上の部材どうし(CPU / クーラー / DIMM / M.2 / GPU の付け根)は、この
;;   モデルでは判定しない。**その座標を私は測っていないので、推定した位置から
;;   干渉を報告すれば「測らなかった検査が、測って問題が無かった検査と同じ顔で
;;   答えを返す」ことになる(CLAUDE.md ADR-2608136000 の class そのもの)。
;;   実際、初版はここで RAM と 3 件の「干渉」を出した —— あれは基板の設計ではなく
;;   私が置いた座標の産物だった。
;;
;;   基板内クリアランスを保証しているのは AABB ではなく規格である:
;;     ・AM5 ソケットのキープアウト(AMD Socket AM5 Design Guide)
;;     ・Wraith Stealth は AMD 純正クーラー = そのキープアウト内に収まるよう設計
;;     ・Mini-ITX(170mm 角)の DIMM / x16 スロット配置
;;   したがって「純正クーラー + ヒートスプレッダ無し DIMM + 2-slot カード」は
;;   規格レベルで成立する。ここは :spec grade の主張であって sim の出力ではない。
;;
;;   判定するのは **筐体レベル**、すなわち bench 先組み一体 / GPU / PSU の 3 つ。
(defn mount-chain [id]
  (loop [x id acc #{}]
    (if-let [p (:part/mounts-on (by-id x))] (recur p (conj acc p)) acc)))
(defn related? [a b]
  (or (contains? (mount-chain a) b) (contains? (mount-chain b) a)))

(def bench-ids (->> placement (map :id)
                    (filter #(= :bench (:part/subassembly (by-id %))))))
(def by-pid (into {} (map (juxt :id identity)) placement))
(def subassy-ids (cons "mobo-b650i" bench-ids))
(def subassy-aabb
  (let [bs (map #(aabb (by-pid %)) subassy-ids)]
    [(reduce k/v-min (map first bs)) (reduce k/v-max (map second bs))]))

(println "  4-2 相互干渉判定 — **筐体レベルのみ**")
(println (str "    基板上の部材は判定しない(座標を測っていない)。"
              "AM5 キープアウト + Mini-ITX 規格が担保する [spec]"))
(println (str "    bench 先組み一体の合併 AABB = ["
              (str/join " " (map r1 (first subassy-aabb))) "] .. ["
              (str/join " " (map r1 (second subassy-aabb))) "]"))
(def case-level [["mobo-subassy" subassy-aabb]
                 ["gpu-b70" (aabb (by-pid "gpu-b70"))]
                 ["psu-pro750b" (aabb (by-pid "psu-pro750b"))]])
(def collisions
  (for [[[na ba] [nb bb]] (for [x case-level y case-level
                                :when (neg? (compare (first x) (first y)))] [x y])
        ;; GPU は mobo に挿さっているので合併箱と重なるのが正常 — 除外する
        :when (and (not (and (= na "gpu-b70") (= nb "mobo-subassy")))
                   (not (and (= nb "gpu-b70") (= na "mobo-subassy")))
                   (intersect? ba bb part-part))]
    [na nb]))
(doseq [[[na ba] [nb bb]] (for [x case-level y case-level
                                :when (neg? (compare (first x) (first y)))] [x y])]
  (let [skip (or (and (= na "gpu-b70") (= nb "mobo-subassy"))
                 (and (= nb "gpu-b70") (= na "mobo-subassy")))]
    (println (str "    " (rp (str na " ∩ " nb) 34)
                  (cond skip "— 除外(GPU は mobo に挿さる)"
                        (intersect? ba bb part-part) "× 干渉"
                        :else "○ 干渉なし")))))
(doseq [[a b] collisions] (finding! :warn (str a "-" b) (str "AABB 干渉: " a " ∩ " b)))

;; 4-3 挿入経路。bench 先組み(mobo に CPU/クーラー/RAM/SSD)は筐体外なので対象外
(println "  4-3 挿入経路判定")
(println (str "    bench 先組み(作業台): " (str/join ", " bench-ids)))
(defn sweep-1 [[mn mx] ax]
  (case ax
    :-y [(assoc (vec mn) 1 (nth mx 1)) (assoc (vec mx) 1 inner-y)]  ; 上から降ろす
    :+z [(vec mn) (assoc (vec mx) 2 inner-z)]                        ; 背面から入れる
    :-x [(assoc (vec mn) 0 0.0) (vec mx)]
    [mn mx]))
(def chassis-order ["mobo-b650i" "psu-pro750b" "gpu-b70"])
(def insertion
  (loop [remaining chassis-order installed [] out []]
    (if (empty? remaining) out
        (let [id (first remaining)
              ax (:part/insert-axis (by-id id))
              ;; mobo は bench 一体で入るので構成部材ごとの箱で掃引する
              boxes (if (= id "mobo-b650i")
                      (mapv #(aabb (by-pid %)) (cons id bench-ids))
                      [(aabb (by-pid id))])
              sweeps (mapv #(sweep-1 % ax) boxes)
              hits (for [q installed
                         :when (and (not (related? id q))
                                    (some (fn [sw] (intersect? sw (aabb (by-pid q)) 0.0)) sweeps))]
                     q)]
          (recur (rest remaining) (conj installed id) (conj out [id ax (vec hits)]))))))
(doseq [[i [id ax hits]] (map-indexed vector insertion)]
  (println (str "    " (lp (inc i) 3) ". " (rp id 16) (rp (str ax) 6)
                (if (seq hits) (str "× 干渉: " (str/join ", " hits)) "○ 挿入可"))))
(def n-block (count (remove #(empty? (nth % 2)) insertion)))
(when (pos? n-block) (finding! :warn "insertion" (str "挿入不可 " n-block " 件")))

;; 4-4 公差 worst-case stack-up(部材を +公差、内空を -公差に振る)
(println "  4-4 公差 worst-case stack-up")
(def wall-tol 1.0)
(def wc
  (for [{:keys [id pos]} placement]
    (let [[dx dy dz] (env id) [tx ty tz] (tol id)
          mx (mapv + (mapv double pos) [(+ dx tx) (+ dy ty) (+ dz tz)])
          over (and (<= (nth mx 0) (+ inner-x wall-tol))
                    (<= (nth mx 1) (+ inner-y wall-tol))
                    (<= (nth mx 2) (+ inner-z wall-tol)))]
      [id over (grade id)])))
(doseq [[id ok g] wc]
  (println (str "    " (rp id 16) (if ok "○ 最悪ケースでも収まる" "× 最悪ケースではみ出し")
                "  [" (subs (str g) 1) "]")))
(def n-wc (count (remove second wc)))
(when (pos? n-wc) (finding! :warn "tolerance" (str "公差最悪ケース NG " n-wc " 件")))

;; VGA 制限に対する最悪ケース(これは公表値なので契約側の判定)
(let [need (+ (ey "gpu-b70") (nth (tol "gpu-b70") 1) gpu-cable-allowance)]
  (println (str "    GPU 最悪ケース長さ " (r1 need) "mm vs 制限 " vga-limit "mm → "
                (if (<= need vga-limit) (str "○ 余裕 " (r1 (- vga-limit need)) "mm")
                    "× 不成立"))))

;; 4-5 ケーブル経路(最小曲げ半径)
(println "  4-5 ケーブル経路 — 折れ角と最小曲げ半径")
(defn v- [a b] (mapv - a b))
(defn dot [a b] (reduce + (map * a b)))
(defn norm [a] (Math/sqrt (dot a a)))
(defn bend-radius
  "waypoint 折れ線の 1 頂点での曲げ半径。隣接区間長の短い方と折れ角から。
   直線(折れ角 0)は無限大。docstring と実装が食い違わないよう acos の定義に従う。"
  [p0 p1 p2]
  (let [a (v- p0 p1) b (v- p2 p1)
        la (norm a) lb (norm b)]
    (if (or (zero? la) (zero? lb)) ##Inf
        (let [c (/ (dot a b) (* la lb))
              c (max -1.0 (min 1.0 c))
              theta (Math/acos c)]            ; theta = pi なら直線
          (if (> theta 3.1415) ##Inf
              (* (min la lb) (Math/tan (/ theta 2.0))))))))
(def cable-results
  (for [c cables]
    (let [wps (edn/read-string (:cable/waypoints c))
          dia (:cable/bundle-dia-mm c)
          need (* dia (:cable/min-bend-factor c))
          rs (for [i (range 1 (dec (count wps)))]
               (bend-radius (nth wps (dec i)) (nth wps i) (nth wps (inc i))))
          worst (if (seq rs) (apply min rs) ##Inf)]
      [(:cable/id c) dia need worst (>= worst need)])))
(println (str "    " (rp "ケーブル" 14) (lp "束径" 6) (lp "要求R" 8) (lp "最小R" 9) "  判定"))
(doseq [[id dia need worst ok] cable-results]
  (println (str "    " (rp id 14) (lp (r0 dia) 6) (lp (r1 need) 8)
                (lp (if (= ##Inf worst) "∞" (r1 worst)) 9)
                "  " (if ok "○" "× 曲げ不足")))
  (when-not ok (finding! :warn (str "bend-" id) (str id " の曲げ半径が不足"))))

;; 4-6 質量・重心・転倒
(println "  4-6 質量・重心")
(def total-mass
  (reduce + (map #(* (:part/mass-g (by-id (:id %))) (:part/qty (by-id (:id %)))) placement)))
(def chassis-mass (:part/mass-g (by-id "chassis-tower100")))
(def fan-mass (* (:part/mass-g (by-id "fan-140")) (:part/qty (by-id "fan-140"))))
(def cog
  (let [wsum (reduce (fn [acc p]
                       (let [pt (by-id (:id p)) mm (* (:part/mass-g pt) (:part/qty pt))
                             [mn mx] (aabb p)
                             c (mapv #(/ (+ %1 %2) 2.0) mn mx)]
                         (mapv + acc (mapv #(* mm %) c))))
                     [0.0 0.0 0.0] placement)]
    (mapv #(/ % total-mass) wsum)))
(println (str "    部材質量 " (r0 total-mass) " g + ファン " (r0 fan-mass)
              " g + 筐体 " (r0 chassis-mass) " g = "
              (r2 (/ (+ total-mass fan-mass chassis-mass) 1000.0)) " kg"))
(println (str "    重心(部材のみ) = [" (str/join " " (map r1 cog)) "] mm"))
(let [fy (/ (nth cog 1) inner-y)]
  (println (str "    重心の高さ比 Y/H = " (r2 fy) "  "
                (if (< fy 0.5) "○ 低重心(PSU が底、GPU が下半分)"
                    "△ 高重心 — 縦長筐体なので転倒に注意"))))

;; ════════════════════════════════════════════════════════════════════════════
;; 5. 電力収支
;; ════════════════════════════════════════════════════════════════════════════
(println)
(hr "═")
(println "5. 電力収支")
(hr "═")
(def load-w
  (+ (reduce + (map #(* (:part/power-w (by-id (:id %))) (:part/qty (by-id (:id %)))) placement))
     (* (:part/power-w (by-id "fan-140")) (:part/qty (by-id "fan-140")))))
(def psu-w 750)
(def gpu-rec-psu 600)
(doseq [{:keys [id]} placement]
  (let [p (by-id id) w (* (:part/power-w p) (:part/qty p))]
    (when (pos? w) (println (str "    " (rp id 16) (lp w 5) " W")))))
(println (str "    " (rp "fan-140 x2" 16) (lp (* 2 (:part/power-w (by-id "fan-140"))) 5) " W"))
(hr "─")
(println (str "    " (rp "合計(上界)" 16) (lp load-w 5) " W   / PSU " psu-w
              " W → 負荷率 " (r0 (* 100.0 (/ load-w psu-w))) "%"))
(println (str "    GPU 単体の推奨 PSU " gpu-rec-psu " W → " psu-w " W は "
              (if (>= psu-w gpu-rec-psu) "○ 満たす" "× 不足")))
(when (< psu-w gpu-rec-psu) (finding! :block "psu-w" "PSU 容量が GPU 推奨を下回る"))
(println "    12V-2x6: PSU が native(450W) / GPU が 1x 要求 → ○ 変換アダプタ不要")

;; ════════════════════════════════════════════════════════════════════════════
;; 6. 熱・気流 —— 何が言えて何が言えないかを分ける
;; ════════════════════════════════════════════════════════════════════════════
(println)
(hr "═")
(println "6. 熱・気流 (kami-cfd.duct — 検証済み D3Q19 LBM)")
(hr "═")

;; 6-1 まずソルバ自身の検証を回す。これを通さない数値は意味が無い(namespace docstring)
(println "  6-1 ソルバ検証: 力駆動平面 Poiseuille 流 vs 閉形解")
(let [v (duct/validate-poiseuille)]
  (println (str "    相対 L2 誤差 = " (r2 (* 100.0 (:l2-rel v))) "% (許容 "
                (r0 (* 100.0 (:tol v))) "%) / ny=" (:ny v) " steps=" (:steps v)
                " → " (if (:pass? v) "○ PASS" "× FAIL")))
  (when-not (:pass? v) (finding! :block "cfd-validate" "CFD ソルバの検証が落ちた")))

;; 6-2 この筐体がソルバの適用範囲に入るかを**計算して**判定する
(println "  6-2 適用範囲の判定(reachable-reynolds)")
(def cell-mm 6.0)
(def char-len-mm 250.0)                        ; 内空の代表長さ
(def l-cells (/ char-len-mm cell-mm))
(def u-ms 2.5)                                  ; 筐体内の代表流速(推定)
(def re-phys (/ (* u-ms (/ char-len-mm 1000.0)) 1.5e-5))
(def probe (duct/duct-new (duct/domain 4 4 4 (fn [_ _ _] false)) {:nu 0.02 :u0 0.05 :patches []}))
(def rr (duct/reachable-reynolds probe l-cells re-phys))
(println (str "    物理 Re = " u-ms " m/s x " (r2 (/ char-len-mm 1000.0)) " m / 1.5e-5 = "
              (r0 re-phys)))
(println (str "    格子 Re(現行) = " (r0 (:re-current rr))
              " / 安定域上限 = " (r0 (:re-max-stable rr))))
(println (str "    目標 Re に必要な tau = " (r2 (:tau-for-target rr))
              " / 沈静化 step = " (r0 (:settling-steps-for-target rr))))
(println (str "    → :feasible? " (:feasible? rr) "  "
              (if (:feasible? rr) "" "= **この筐体の流れは LBM では解けない**")))
(println "    したがって CFM / 流速 / 停滞率を LBM から出して報告しない。")
(println "    (kami-cfd.duct の namespace docstring が明示する立場と同じ:")
(println "     間違ったソルバの数値を自信たっぷりに報告するのは、数値が無いことより悪い)")

;; 6-3 言えること: 通過流に対する定常エネルギー保存(CFD ではない)
(println "  6-3 排気の温度上昇(定常エネルギー保存。CFD ではない)")
(def fan-cfm 102.9)                             ; F140Q 公称(自由吹き出し)
(defn cfm->m3s [c] (/ c 2118.88))
(doseq [[label eff] [["公称値そのまま(非現実)" 1.0]
                     ["実装後 50%(メッシュ+フィルタ+背圧)" 0.5]
                     ["実装後 30%(保守側)" 0.3]]]
  (let [q (cfm->m3s (* fan-cfm 2 eff))
        dt (duct/bulk-delta-t load-w q)]
    (println (str "    " (rp label 34) " Q=" (lp (r2 (* fan-cfm 2 eff)) 6) " CFM → 排気 ΔT = "
                  (lp (r1 dt) 6) " K"))))
(println (str "    発熱密度 = " (r1 (/ load-w (/ (* outer-x outer-y outer-z) 1.0e6))) " W/L"))
(println "    ※ ΔT は『空気が通り抜ける間に何度上がるか』であって素子温度ではない。")
(println "      素子温度には共役熱伝達が要り、このソルバは持たない。")

;; ════════════════════════════════════════════════════════════════════════════
;; 7. 判定
;; ════════════════════════════════════════════════════════════════════════════
(println)
(hr "═")
(println "7. 判定")
(hr "═")
(def blocks (filter #(= :block (first %)) @findings))
(def warns (filter #(= :warn (first %)) @findings))
(if (empty? blocks)
  (println "  ■ 組み立ては成立する(公表クリアランスに対して阻却要因 0 件)")
  (do (println (str "  ■ 組み立て不成立 — 阻却要因 " (count blocks) " 件"))
      (doseq [[_ id t] blocks] (println (str "     × " id ": " t)))))
(if (empty? warns)
  (println "  ■ 警告 0 件")
  (do (println (str "  ■ 警告 " (count warns) " 件(sim / 推定座標側)"))
      (doseq [[_ id t] warns] (println (str "     △ " id ": " t)))))

;; 成果物を EDN に落とす
(def out-path "90-docs/hardware/tower100-b70-assembly.edn")
(when write-artifacts?
 (fs/writeFileSync
  out-path
  (pr-str
  {:model/id "tower100-b70"
   :model/source parts-path
   :model/frame "X=幅(mobo面→反対パネル) / Y=高さ(床→天面) / Z=奥行(前→後)"
   :chassis {:outer [outer-x outer-y outer-z]
             :inner-estimated [inner-x inner-y inner-z]
             :bracket-y bracket-y :psu-cover-y psu-cover-y
             :derivation "bracket-y = psu-cover-y + VGA制限330。内寸は非公表なので逆算"}
   :placement (vec (for [{:keys [id pos]} placement]
                     {:id id :pos pos :size (env id) :grade (grade id)}))
   :checks-declared (vec (for [{:keys [id name need limit unit grade]} checks-a]
                           {:id id :name name :need need :limit limit :unit unit
                            :grade grade :pass (>= limit need)}))
   :sim {:containment-ng n-out :collisions (vec collisions)
         :insertion-blocked n-block :tolerance-ng n-wc
         :cable (vec (for [[id _ need worst ok] cable-results]
                       {:id id :required-r need :worst-r (if (= ##Inf worst) :inf worst) :pass ok}))}
   :power {:load-w load-w :psu-w psu-w :gpu-recommended-psu-w gpu-rec-psu}
   :thermal {:w-per-litre (/ load-w (/ (* outer-x outer-y outer-z) 1.0e6))
             :lbm-feasible? (:feasible? rr)
             :note "LBM は適用範囲外。素子温度は未解決"}
   :findings {:blocking (mapv (fn [[_ id t]] {:id id :text t}) blocks)
              :warnings (mapv (fn [[_ id t]] {:id id :text t}) warns)}
   :step-file step-path})))
(if write-artifacts?
  (do (println (str "  成果物: " out-path))
      (println (str "          " step-path)))
  (println "  成果物: SKIP(非既定カタログでの実行)"))
(hr "═")
