(ns tower100-b70-drawing
  "tower100-b70-assembly.edn から直交三面図(SVG)を生成する。

   **手描きしない。**図面を手で書くと、モデルを直したときに図だけ古くなり、
   しかも見た目は正しいままなので誰も気づかない。座標は必ず生成物から読む。

   出力の SVG は色を CSS custom property で持つので、埋め込み先の
   light/dark トークンにそのまま追従する。

   実行: nbb scripts/tower100-b70-drawing.cljs [out.svg]")

(require '[clojure.string :as str]
         '["fs" :as fs]
         '[cljs.reader :as edn])

(def model (edn/read-string (fs/readFileSync "90-docs/hardware/tower100-b70-assembly.edn" "utf8")))
(def out-path (or (first *command-line-args*) "90-docs/hardware/tower100-b70-views.svg"))

(def chassis (:chassis model))
(def outer (:outer chassis))
(def OX (nth outer 0))
(def OY (nth outer 1))
(def OZ (nth outer 2))
(def inner (:inner-estimated chassis))
(def placement (:placement model))

(def s 0.62)                       ; mm -> px
(def pad 46)                       ; 各ビューの余白
(def gap 34)

(defn px [v] (* s v))

;; カテゴリ色(埋め込み先のトークンに追従)
(def part-fill
  {"gpu-b70"       "var(--p-gpu)"
   "mobo-b650i"    "var(--p-mobo)"
   "cpu-7500f"     "var(--p-cpu)"
   "cooler-wraith" "var(--p-cool)"
   "ram-ddr5"      "var(--p-ram)"
   "ssd-m2"        "var(--p-ssd)"
   "psu-pro750b"   "var(--p-psu)"})

(defn esc [t] (-> (str t) (str/replace "&" "&amp;") (str/replace "<" "&lt;")))

(defn rect
  [x y w h fill extra]
  (str "<rect x=\"" (.toFixed x 2) "\" y=\"" (.toFixed y 2)
       "\" width=\"" (.toFixed (max 0.6 w) 2) "\" height=\"" (.toFixed (max 0.6 h) 2)
       "\" fill=\"" fill "\" " extra "/>"))

;; ── 1 ビューを描く ─────────────────────────────────────────────────────────
;; ax/ay = モデルの軸番号(0=X 1=Y 2=Z)。flip-y? は「上が +」の軸を画面座標に直す。
(defn view
  [{:keys [ox oy label ax ay w-mm h-mm]}]
  (let [vw (px w-mm) vh (px h-mm)
        ;; 画面 Y は下向き。モデルの Y(高さ)と Z(奥行)はどちらも
        ;; 「値が大きいほど上/奥」なので反転する。
        sy (fn [v size] (- vh (px (+ v size))))
        body
        (str
         ;; 筐体外形
         (rect ox oy vw vh "var(--sheet)" "stroke=\"var(--ink)\" stroke-width=\"1.6\" rx=\"2\"")
         ;; 内空(推定) — 破線。**床合わせ**にする(部材は床の上に立つので、
         ;; 内空を宙に浮かせて描くと部材とだけずれて、図が静かに嘘になる)
         (let [iw (nth inner ax) ih (nth inner ay)
               offa (/ (- w-mm iw) 2)]
           (rect (+ ox (px offa)) (+ oy vh (- (px ih))) (px iw) (px ih)
                 "none" "stroke=\"var(--grid)\" stroke-width=\"1\" stroke-dasharray=\"4 3\""))
         ;; 部材。座標は内空基準なので、外形基準の筐体矩形に載せるため水平方向に寄せる。
         ;; **投影面積の大きい順に描く** —— 描画順のままだと GPU(112x271)が mobo と
         ;; クーラーを完全に覆い、図から消える。大きいものを先に敷いて小さいものを上に。
         (str/join
          (for [{:keys [id pos size]}
                (sort-by (fn [{:keys [size]}] (- (* (nth size ax) (nth size ay)))) placement)
                :let [offa (/ (- w-mm (nth inner ax)) 2)
                      a (+ (nth pos ax) offa) b (nth pos ay)
                      sa (nth size ax) sb (nth size ay)]]
            (rect (+ ox (px a)) (+ oy (sy b sb)) (px sa) (px sb)
                  (get part-fill id "var(--p-etc)")
                  "stroke=\"var(--ink)\" stroke-width=\"0.7\" fill-opacity=\"0.72\"")))
         ;; ビュー名
         "<text x=\"" (.toFixed ox 2) "\" y=\"" (.toFixed (- oy 14) 2)
         "\" class=\"vlabel\">" (esc label) "</text>")]
    body))

;; ── 寸法線 ─────────────────────────────────────────────────────────────────
(defn dim-v
  "縦の寸法線(右側)。y0/y1 は画面座標。"
  [x y0 y1 text]
  (str "<g class=\"dim\">"
       "<line x1=\"" x "\" y1=\"" (.toFixed y0 2) "\" x2=\"" x "\" y2=\"" (.toFixed y1 2) "\"/>"
       "<line x1=\"" (- x 4) "\" y1=\"" (.toFixed y0 2) "\" x2=\"" (+ x 4) "\" y2=\"" (.toFixed y0 2) "\"/>"
       "<line x1=\"" (- x 4) "\" y1=\"" (.toFixed y1 2) "\" x2=\"" (+ x 4) "\" y2=\"" (.toFixed y1 2) "\"/>"
       "<text x=\"" (+ x 7) "\" y=\"" (.toFixed (/ (+ y0 y1) 2) 2) "\" class=\"dimtext\">"
       (esc text) "</text></g>"))

(def v1-x (+ pad 30))            ; 左に全高の寸法線を置くぶん寄せる
(def v1-y (+ pad 22))
(def v1-w (px OX))
(def v1-h (px OY))
(def v2-x (+ v1-x v1-w gap 62))
(def v2-w (px OZ))
(def v3-y (+ v1-y v1-h gap 20))
(def v3-h (px OZ))

(def svg-w (+ v2-x v2-w pad 96))
(def svg-h (+ v3-y v3-h pad))

(def bracket-y (:bracket-y chassis))
(def psu-cover-y (:psu-cover-y chassis))
(def gpu (first (filter #(= "gpu-b70" (:id %)) placement)))
(def gpu-y0 (nth (:pos gpu) 1))
(def gpu-y1 (+ gpu-y0 (nth (:size gpu) 1)))

(def svg
  (str
   "<svg viewBox=\"0 0 " (.toFixed svg-w 0) " " (.toFixed svg-h 0)
   "\" width=\"100%\" role=\"img\" aria-label=\"Tower 100 + Arc Pro B70 直交三面図\" "
   "xmlns=\"http://www.w3.org/2000/svg\" class=\"views\">"
   "<style>"
   ".views text{font-family:ui-monospace,SFMono-Regular,Menlo,monospace;fill:var(--muted)}"
   ".views .vlabel{font-size:11px;letter-spacing:.10em;text-transform:uppercase;fill:var(--ink);font-weight:600}"
   ".views .dim line{stroke:var(--accent);stroke-width:.9}"
   ".views .dimtext{font-size:10px;fill:var(--accent);dominant-baseline:middle}"
   "</style>"

   ;; 正面図 (X 幅 x Y 高さ)
   (view {:ox v1-x :oy v1-y :label "正面 / front — X 幅 x Y 高さ" :ax 0 :ay 1 :w-mm OX :h-mm OY})
   ;; 側面図 (Z 奥行 x Y 高さ)
   (view {:ox v2-x :oy v1-y :label "側面 / side — Z 奥行 x Y 高さ" :ax 2 :ay 1 :w-mm OZ :h-mm OY})
   ;; 平面図 (X 幅 x Z 奥行)
   (view {:ox v1-x :oy v3-y :label "平面 / plan — X 幅 x Z 奥行" :ax 0 :ay 2 :w-mm OX :h-mm OZ})

   ;; 寸法: GPU の縦方向予算(この構成で最も見るべき軸)
   (let [top (fn [mm] (+ v1-y (- (px OY) (px mm))))]
     (str
      (dim-v (+ v2-x v2-w 20) (top bracket-y) (top gpu-y0)
             (str "GPU " (.toFixed (- bracket-y gpu-y0) 0) "mm"))
      (dim-v (+ v2-x v2-w 60) (top bracket-y) (top psu-cover-y)
             "VGA制限 330mm")
      (dim-v (+ v2-x v2-w 20) (top gpu-y0) (top psu-cover-y)
             (str "逃げ " (.toFixed (- gpu-y0 psu-cover-y) 0) "mm"))))

   ;; 全高。テキストは寸法線の左に出す(既定の右出しだと筐体に重なる)
   (let [x (- v1-x 22) y0 v1-y y1 (+ v1-y v1-h)]
     (str "<g class=\"dim\">"
          "<line x1=\"" x "\" y1=\"" y0 "\" x2=\"" x "\" y2=\"" (.toFixed y1 2) "\"/>"
          "<line x1=\"" (- x 4) "\" y1=\"" y0 "\" x2=\"" (+ x 4) "\" y2=\"" y0 "\"/>"
          "<line x1=\"" (- x 4) "\" y1=\"" (.toFixed y1 2) "\" x2=\"" (+ x 4) "\" y2=\"" (.toFixed y1 2) "\"/>"
          "<text x=\"" (- x 7) "\" y=\"" (.toFixed (/ (+ y0 y1) 2) 2)
          "\" class=\"dimtext\" text-anchor=\"middle\" transform=\"rotate(-90 "
          (- x 7) " " (.toFixed (/ (+ y0 y1) 2) 2) ")\">" (.toFixed OY 1) "mm</text></g>"))
   "</svg>"))

(fs/writeFileSync out-path svg)
(println (str "書き出し: " out-path " (" (count svg) " chars / "
              (count placement) " 部材 / viewBox "
              (.toFixed svg-w 0) "x" (.toFixed svg-h 0) ")"))
