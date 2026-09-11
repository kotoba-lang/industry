(ns mk1-costmodel
  "murakumo.cloud 叢雲 MK-1 (Arc Pro B70 AI mini workstation) — BOM / COGS /
   margin / fleet-gate model. Pure arithmetic, nbb (ADR-2607173000: nbb-only
   script host). Every input is a cited July-2026 observation or an explicitly
   labelled assumption — see :src on each row.")

(def fx 163.8)          ; USD/JPY, 2026-07-24/25 (Fed H.10 / tradingeconomics)
(def yen-per-kwh 31.0)  ; JP household 1kWh 目安 2026 (contents.shirokumapower / pps-net)

;; ── BOM ────────────────────────────────────────────────────────────────────
;; :s1 = street price, qty 1, July 2026.  :s50 = target landed unit cost at a
;; 50-unit PO.  RAM/SSD get near-zero volume discount — allocation market.
(def bom
  [{:k :gpu   :n "Intel Arc Pro B70 32GB GDDR6 (ASRock Creator / Sparkle blower)" :s1 1126 :s50 1065 :src "street $1,126 (11 listings, StorageReview 2026-07-14); MSRP $949"}
   {:k :cpu   :n "Ryzen 7 7700 8C/16T 65W AM5"                                    :s1  280 :s50  262 :src "Ryzen 7 7700X3D $329→$279 Jul-2026; JP Ryzen +50%"}
   {:k :mobo  :n "ASRock B650I Lightning WiFi mITX (PCIe 5.0 x16, 2x M.2, 2.5GbE)" :s1 170 :s50  152 :src "Micro Center $169.99"}
   {:k :ram   :n "32GB (2x16) DDR5-5600"                                          :s1  400 :s50  392 :src "cheapest in-stock 32GB DDR5 kit ~$400, 2026-07-24"}
   {:k :ssd   :n "1TB PCIe 4.0 NVMe"                                              :s1  150 :s50  141 :src "2TB Gen4 $120-289; 1TB pro-rata mid"}
   {:k :psu   :n "750W SFX-L 80+ Gold, ATX 3.1 / 12V-2x6"                          :s1  145 :s50  128 :src "SFX 850W ~$123-165 band"}
   {:k :case  :n "NR200-class SFF mITX, 330mm GPU clearance, dual-slot, 18.25L"    :s1   75 :s50   60 :src "Cooler Master NR200 $64.99"}
   {:k :cool  :n "low-profile 47mm tower cooler, 65W"                             :s1   45 :s50   36 :src "assumption"}
   {:k :cord  :n "PSE-marked (diamond) JP cordset"                                :s1    6 :s50    4 :src "pre-certified component — no cert project"}
   {:k :brand :n "laser-etched badge + branded front mesh panel"                  :s1   25 :s50   14 :src "assumption"}
   {:k :misc  :n "paste, cables, screws, filters, desiccant"                      :s1   15 :s50   11 :src "assumption"}])

(defn bom-total [k] (reduce + 0 (map k bom)))

;; ── conversion cost (qty 50, JP assembly) ──────────────────────────────────
(def labor-yen-per-h 3500.0)                       ; loaded JP tech rate (assumption)
(defn hr [h] (/ (* h labor-yen-per-h) fx))

(def conversion
  [{:k :freight  :n "inbound freight + insurance (air/sea blend to JP)"        :usd 28 :src "assumption"}
   {:k :duty     :n "import duty on PC parts/GPU into JP"                      :usd  0 :src "WTO ITA — duty-free; 10% consumption tax is recoverable input tax, not COGS"}
   {:k :assembly :n "SFF assembly, 2.5h touch time"                            :usd (hr 2.5) :src "labor @ JPY3,500/h"}
   {:k :burnin   :n "burn-in + QC: memtest, 2h LLM soak, thermal log (0.5h tech + power)" :usd (+ (hr 0.5) 3) :src "labor + electricity"}
   {:k :image    :n "OS + murakumo-studio image + BIOS/ReBAR config (0.3h)"     :usd (hr 0.3) :src "labor"}
   {:k :pack     :n "retail packaging: double-wall box, EPE foam, printed QSG"  :usd 19 :src "assumption"}
   {:k :outbound :n "outbound shipping, JP domestic ~12kg"                      :usd 15 :src "assumption"}
   {:k :warranty :n "3-yr RMA reserve (5% incidence x $400 avg + return logistics)" :usd 45 :src "assumption"}
   {:k :scrap    :n "QC scrap / rework reserve (1.5% of parts)"                 :usd (* 0.015 (bom-total :s50)) :src "assumption"}])

(def parts-50 (bom-total :s50))
(def conv-50  (reduce + 0 (map :usd conversion)))
(def cogs-50  (+ parts-50 conv-50))

;; ── margin ─────────────────────────────────────────────────────────────────
(defn gm [price cogs] {:price price :jpy-ex (Math/round (* price fx))
                       :jpy-inc (Math/round (* price fx 1.10))
                       :gm-usd (Math/round (- price cogs))
                       :gm-pct (/ (Math/round (* 1000.0 (/ (- price cogs) price))) 10.0)})

;; below-the-line, per unit
(def stripe-frac 0.036)
(def cac-per-unit 200)      ; B2B/prosumer allocation (assumption)
(def support-per-unit (hr 2.0))

(defn contribution [price cogs]
  (let [g (- price cogs)
        blw (+ (* price stripe-frac) cac-per-unit support-per-unit)]
    {:price price :gross (Math/round g) :below (Math/round blw)
     :contrib (Math/round (- g blw))
     :contrib-pct (/ (Math/round (* 1000.0 (/ (- g blw) price))) 10.0)}))

;; ── fixed costs, year 1 (JP-only, off-shelf chassis) ───────────────────────
(def fixed-y1
  [{:n "VCCI admission (one-time) JPY55,000 incl tax"        :usd (/ 55000 fx)  :src "vcci.jp/english/membership/join.html"}
   {:n "VCCI Regular C annual JPY220,000 incl tax (<10 reports/yr)" :usd (/ 220000 fx) :src "same"}
   {:n "VCCI conformity handling fee JPY2,750/report"          :usd (/ 2750 fx)  :src "same"}
   {:n "3rd-party EMC test, VCCI Class B, 1 model (JPY600,000)" :usd (/ 600000 fx) :src "assumption, JPY400-800k band"}
   {:n "PSE certification"                                     :usd 0 :src "PC本体 (DC device) is 対象外; cordset bought pre-certified"}
   {:n "PL (product liability) insurance JPY150,000/yr"        :usd (/ 150000 fx) :src "assumption"}
   {:n "engineering: validation, image, docs (one-time)"       :usd 25000 :src "assumption"}])

(def fixed-y1-total (reduce + 0 (map :usd fixed-y1)))

;; ── fleet gate: cost.cljc  fleet-yen-per-mtok <= spot-yen-per-mtok ─────────
;; yen/Mtok = load_W * yen_per_kwh / (tok_s * 3.6)
(defn yen-per-mtok [load-w tok-s] (/ (* load-w yen-per-kwh) (* tok-s 3.6)))
(def spot-ref {:usd-h 2.99 :tok-s 2000.0})         ; cost.cljc default-spot-ref
(def spot-yen-mtok (* (/ (* (:usd-h spot-ref) fx) (* (:tok-s spot-ref) 3600.0)) 1e6))
(def mk1-load-w 310.0)                             ; B70 230W TBP + host ~80W
(defn gate [load-w tok-s]
  (let [f (yen-per-mtok load-w tok-s)]
    {:tok-s tok-s :yen-per-mtok (/ (Math/round (* 10 f)) 10.0)
     :ratio (/ (Math/round (* 100 (/ f spot-yen-mtok))) 100.0)
     :pass (<= f spot-yen-mtok)}))
(def break-even-tok-s (/ (* mk1-load-w yen-per-kwh) (* spot-yen-mtok 3.6)))

;; ── output ─────────────────────────────────────────────────────────────────
;; cljs has no clojure.core/format — local pad/round helpers instead.
(defn rp [s w] (let [s (str s)] (str s (apply str (repeat (max 0 (- w (count s))) " ")))))
(defn lp [s w] (let [s (str s)] (str (apply str (repeat (max 0 (- w (count s))) " ")) s)))
(defn r0 [x] (str (Math/round (double x))))
(defn r1 [x] (let [v (/ (Math/round (* 10.0 (double x))) 10.0)] (str v)))
(defn r2 [x] (let [v (/ (Math/round (* 100.0 (double x))) 100.0)] (str v)))
(defn row [n a b] (println (str (rp n 70) (lp a 8) (lp b 8))))

(println "=== BOM (USD) ===   [name, street qty1, target @qty50]")
(doseq [{:keys [n s1 s50]} bom] (row n (r0 s1) (r0 s50)))
(row "PARTS SUBTOTAL" (r0 (bom-total :s1)) (r0 parts-50))
(println "\n=== conversion cost @qty50 (USD) ===")
(doseq [{:keys [n usd]} conversion] (row n "" (r1 usd)))
(row "CONVERSION SUBTOTAL" "" (r1 conv-50))
(row "TOTAL COGS / unit" "" (r1 cogs-50))

(println "\n=== price ladder, MK-1 Solo (COGS " (r1 cogs-50) ") ===")
(doseq [p [3480 3780 3980 4280 4780]]
  (let [{:keys [price jpy-ex jpy-inc gm-usd gm-pct]} (gm p cogs-50)]
    (println (str "$" (lp price 5) "  JPY ex " (lp jpy-ex 8) "  inc " (lp jpy-inc 8)
                  "  GM $" (lp gm-usd 6) "  GM% " (lp gm-pct 5)))))
(println "\n=== contribution @ $3,980 ===")
(println (contribution 3980 cogs-50))

(println "\n=== fixed year-1 ===")
(doseq [{:keys [n usd]} fixed-y1] (row n "" (r0 usd)))
(row "FIXED Y1 TOTAL" "" (r0 fixed-y1-total))
(let [c (:contrib (contribution 3980 cogs-50))]
  (println (str "break-even units @ $3,980 = " (r1 (/ fixed-y1-total c))))
  (doseq [u [25 50 100 200]]
    (let [rev (* u 3980.0) contr (* u (double c)) op (- contr fixed-y1-total)]
      (println (str "vol " (lp u 4) " -> revenue $" (lp (r0 rev) 9)
                    "  contribution $" (lp (r0 contr) 8)
                    "  OP after Y1 fixed $" (lp (r0 op) 8)
                    "  OP% " (lp (r1 (* 100.0 (/ op rev))) 5))))))

(println "\n=== fleet cost gate (local-murakumo cost.cljc) ===")
(println (str "spot ref yen/Mtok = " (r1 spot-yen-mtok)
              "  ($" (:usd-h spot-ref) "/h, " (:tok-s spot-ref) " tok/s, fx " fx ")"))
(println (str "gad (MEASURED 64.08W, 60.48 tok/s) -> " (gate 64.08 60.48)))
(doseq [t [16.3 30.5 39.3 50 70.09 100]] (println (str "MK-1 @310W -> " (gate mk1-load-w t))))
(println (str "BREAK-EVEN single-card decode = " (r1 break-even-tok-s)
              " tok/s on qwen3.6-35b-a3b Q4_K_M"))

(println "\n=== customer payback vs $2.99/h H100 spot, 8h/day x 250d ===")
(let [spot-yr (* 2.99 8 250)
      kwh (* (/ mk1-load-w 1000.0) 8 250)
      elec (/ (* kwh yen-per-kwh) fx)
      save (- spot-yr elec)]
  (println (str "spot/yr $" (r0 spot-yr) "   MK-1 elec/yr $" (r0 elec) " (" (r0 kwh) " kWh)"
                "   net saving/yr $" (r0 save)
                "   payback " (r2 (/ 3980.0 save)) " yr (" (r1 (* 12 (/ 3980.0 save))) " months)")))

(println "\n=== MK-4 Ring (4x Solo, 128GB VRAM, 4 INDEPENDENT heads, no tensor-parallel) ===")
(let [c (+ (* 4 cogs-50) -40 85 25 60 (hr 1.0)) p 15200]
  (println (str "COGS $" (r0 c) "  price $" p " (vs 4x$3,980=$15,920)  GM $" (r0 (- p c))
                "  GM% " (r1 (* 100.0 (/ (- p c) p))))))

(println "\n=== MK-1 Solo/64 (64GB RAM, 2TB) — RAM pass-through warning ===")
(let [c (+ cogs-50 400 148) p 4980]   ; +32GB RAM (+$400), 1TB->2TB (+$148)
  (println (str "COGS $" (r0 c) "  price $" p "  GM $" (r0 (- p c))
                "  GM% " (r1 (* 100.0 (/ (- p c) p)))
                "   incremental GM on the $548 BOM upgrade priced at $1,000: "
                (r1 (* 100.0 (/ (- 1000 548) 1000))) "%")))
