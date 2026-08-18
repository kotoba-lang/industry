(ns mk1-cfd
  "叢雲 MK-1 筐体の内部流を、検証済み D3Q19 LBM(kotoba-lang/kami-engine-cfd の
  `kami-cfd.duct`)で解き、結果を `90-docs/hardware/mk1-cfd.edn` に落とす。

  **なぜこれが .clj(JVM)で、nbb ではないのか。**`kami-cfd.duct` 自体は portable
  `.cljc` で nbb でもロードできるが、nbb は SCI(インタプリタ)で実行するため
  19 速度格子の内側ループが桁違いに遅い。実測: 21x3x3 の検証ケースでも nbb では
  分オーダー、本ケース(約 6 万セル x 1500 step)は現実的でない。repo の
  `kami-cfd.runner`(JVM-only CLI)と同じ host-runner パターンに合わせ、
  **数値は計算ホストで解き、編成(nbb の scripts/mk1-cad.cljs)は結果を読む**。
  ADR-2607173000 の『運用 tooling は nbb』は編成スクリプトの話で、計算カーネルを
  インタプリタで回すことを求めていない。

  実行(superproject root を引数で渡す。deps.edn を持つ作業ディレクトリから)。
  deps.edn には :paths に <root>/scripts、:deps に kami-engine-cfd の
  :local/root を書き、`clojure -M -m mk1-cfd <superproject-root>` で起動する。

  **モデル化の中身と、その限界。**
  ・領域 = 筐体内寸を `cell-mm` で離散化。部材は mk1-assembly.edn の AABB を
    そのまま solid セルに焼く(部材形状は箱近似 — フィンやコネクタの凹凸は無い)。
  ・**送風源は B70 blower なので、速度を与えるのは背面 GPU スロット(吸い出し)**。
    前面 passive メッシュは zero-gradient の :outlet(大気開放)にする。
    初版は逆にしていた — 前面メッシュに速度を与え背面を passive outlet にした。
    これは等価ではなく質量を過拘束する: 大きな速度入口が小さな zero-gradient
    出口に流し込めないので領域が加圧され、出口流束がゼロに潰れ、ほぼ全セルが
    停滞と出る。実測でまさにそうなった(入口 51.7 / 出口 0.045、停滞 96.4%)。
  ・収束は固定 step 数でなく `run-until-steady` で判定する。初版は 1500 step
    固定で、6x6x14 の小さなダクトでも収束に 2550 step 掛かることが判明した
    ため、**32x47x79 の本ケースは全く定常でなかった**。
  ・**解いているのは流れだけ。**エネルギー方程式は無いので素子温度は出ない。
    出るのは通過流量・停滞領域・そこから エネルギー保存で出るバルク ΔT。"
  (:require [kami-cfd.duct :as duct]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pp]))

;; superproject root は引数で受ける(既定 ".")。相対パス決め打ちにすると
;; deps.edn の置き場所と cwd が結合してしまう。
(def ^:dynamic *root* ".")
(defn- assembly-path [] (str *root* "/90-docs/hardware/mk1-assembly.edn"))
(defn- out-path [] (str *root* "/90-docs/hardware/mk1-cfd.edn"))

;; 離散化。4mm セルは 8.2L の箱を約 33x48x80 = 127k セルにする。粗いが、
;; 知りたいのが「どこが淀むか」「どれだけ通るか」なので妥当。細かくすれば
;; 精度は上がるが、この解像度で結論が変わらないことを別途確認すべき
;; (格子収束確認は未実施 — 下の :caveats に明記する)。
;; 6mm セル。4mm では 118,816 セル x 1500 step で 17 分掛かり、しかも未収束
;; だった。設計反復に使える道具にするため粗くする(格子収束確認は別途)。
(def ^:private cell-mm 6.0)
;; 収束判定は u0 に対する相対値で与える。初版は :tol 1.0e-7(絶対)で、
;; u0=0.05 に対して 2e-6 相当という無茶な基準だった — 34,503 セルで 60 分回して
;; 収束しなかった。1e-4 は実務的な基準。
(def ^:private max-steps 30000)
(def ^:private check-every 100)
(def ^:private rel-tol 1.0e-4)
(def ^:private u-lat 0.05)      ; 格子入口速度
(def ^:private nu-lat 0.02)     ; 格子動粘性

;; B70 blower の実効吸気風速。データシート値が無いので **assumption**:
;; dual-slot ブラケット開口(約 40x100mm = 0.004 m2)を 25 CFM(0.0118 m3/s)
;; 通すと約 3.0 m/s。ここは実機で風速計を当てて置き換える。
(def ^:private u-phys-ms 3.0)

(defn- mm->cell [mm] (long (Math/floor (/ (double mm) cell-mm))))

(defn- load-placement []
  (let [d (edn/read-string (slurp (assembly-path)))
        {:keys [inner-mm wall-mm]} (:mk1/enclosure d)
        w (double wall-mm)]
    {:inner inner-mm
     :wall w
     :boxes (mapv (fn [{:keys [part aabb-mm]}]
                    (let [[mn mx] aabb-mm]
                      {:part part
                       ;; 筐体内寸座標へ移す(壁厚を引く)
                       :lo (mapv #(mm->cell (- (double %) w)) mn)
                       :hi (mapv #(mm->cell (- (double %) w)) mx)}))
                  (:mk1/placement d))
     :heat-w (:heat-w (:mk1/sim d))}))

(defn -main [& args]
  (binding [*root* (or (first args) ".")]
   (let [{:keys [inner wall boxes heat-w]} (load-placement)
        [ix iy iz] (mapv #(mm->cell %) inner)
        _ (println (format "domain %d x %d x %d cells (%.1f mm/cell) = %d cells"
                           ix iy iz cell-mm (* ix iy iz)))
        solid-boxes (mapv (fn [{:keys [lo hi]}] [lo hi]) boxes)
        dom (duct/domain ix iy iz (duct/boxes->solid-fn solid-boxes))
        _ (println (format "solid cells %d (%.1f%% of domain)"
                           (:solid-count dom)
                           (* 100.0 (/ (double (:solid-count dom)) (* ix iy iz)))))
        ;; 入口: 前面(z-max)の board 室側。出口: 背面(z-min)の GPU スロット帯。
        gpu (first (filter #(= "gpu-b70" (:part %)) boxes))
        gx0 (nth (:lo gpu) 0) gx1 (nth (:hi gpu) 0)
        gy0 (nth (:lo gpu) 1) gy1 (min (nth (:hi gpu) 1) (dec iy))
        lbm0 (duct/duct-new
              dom {:nu nu-lat :u0 u-lat
                   ;; 背面 GPU スロット = blower。inward normal は +z なので
                   ;; 外向き(吸い出し)は負の u。
                   :patches [(duct/patch :z-min :inlet
                                         (fn [x y] (and (>= x gx0) (<= x gx1)
                                                        (>= y gy0) (<= y gy1)))
                                         (- u-lat))
                             ;; 前面 passive メッシュ = 大気開放
                             (duct/patch :z-max :outlet
                                         (fn [x _] (< x gx0)) nil)]})
        _ (println (format "running until steady (max %d, check every %d)..."
                           max-steps check-every))
        t0 (System/currentTimeMillis)
        conv (duct/run-until-steady lbm0 {:rel-tol rel-tol :max-steps max-steps
                                          :check-every check-every})
        lbm (:lbm conv)
        secs (/ (- (System/currentTimeMillis) t0) 1000.0)
        fin (duct/patch-flux lbm :inlet)
        fout (duct/patch-flux lbm :outlet)
        stag (duct/stagnant-fraction lbm 0.10)
        {:keys [q-m3s cfm]} (duct/lattice->cfm (Math/abs (double fin)) cell-mm u-lat u-phys-ms)
        dt (duct/bulk-delta-t (double heat-w) q-m3s)
        val (duct/validate-poiseuille)
        result
        {:solver "kami-cfd.duct (kotoba-lang/kami-engine-cfd)"
         :lattice "D3Q19 BGK + Smagorinsky LES"
         :domain-cells [ix iy iz]
         :cell-mm cell-mm
         :steps (:steps conv)
         :convergence (:status conv)
         :residual (:residual conv)
         :residual-tol (:tol conv)
         :rel-tol rel-tol
         :solid-fraction (/ (Math/round (* 1000.0 (/ (double (:solid-count dom)) (* ix iy iz)))) 1000.0)
         :inlet-flux-lattice fin
         :outlet-flux-lattice fout
         :q-m3s q-m3s
         :cfm cfm
         :stagnant-fraction stag
         :heat-w heat-w
         :bulk-delta-t-k dt
         :u-phys-ms u-phys-ms
         :validation-case "force-driven plane Poiseuille vs closed form"
         :validation-l2 (:l2-rel val)
         :validation-tol (:tol val)
         :validation-pass (:pass? val)
         :wall-clock-s secs
         :mass-balance-residual (if (zero? fin) nil (Math/abs (/ (+ fin fout) fin)))
         :caveats
         ["部材は AABB の箱近似。ヒートシンクのフィン形状・コネクタの凹凸は無い。"
          "格子収束確認(cell-mm を半分にして結論が変わらないこと)は未実施。"
          "convergence が :max-steps なら流れ場はまだ変化中で、流束は暫定値。"
          "入口風速 3.0 m/s は B70 blower の assumption。実機で風速計を当てて置き換える。"
          "エネルギー方程式なし → 素子ジャンクション温度は出ない。bulk-delta-t-k は排気空気の上昇。"
          "ケーブルは solid として入れていない(mk1-cad.cljs の 6c で別に経路判定)。"]
         :generated-by "scripts/mk1_cfd.clj"}]
    (println (format "inlet flux %.4f / outlet flux %.4f (lattice)" fin fout))
    (println (format "-> %.4f m3/s = %.1f CFM" q-m3s cfm))
    (println (format "stagnant fraction %.3f" stag))
    (println (format "bulk dT %.2f K for %d W" dt (long heat-w)))
    (println (format "convergence %s after %d steps (residual %.3e, tol %.3e = %.0e x u0)"
                     (name (:status conv)) (:steps conv) (:residual conv)
                     (:tol conv) rel-tol))
    (println (format "mass balance: |inlet+outlet| / |inlet| = %.4f (0 = perfect)"
                     (if (zero? fin) ##Inf (Math/abs (/ (+ fin fout) fin)))))
    (println (format "poiseuille validation L2=%.5f pass=%s" (:l2-rel val) (:pass? val)))
    (println (format "wall clock %.1f s" secs))
    (io/make-parents (out-path))
    (spit (out-path)
          (str ";; 生成物 — 手編集しない。再生成: scripts/mk1_cfd.clj (JVM)\n"
               ";; 検証: force-driven plane Poiseuille を閉形解と突き合わせ済み\n"
               (with-out-str (pp/pprint result))))
    (println "wrote" (out-path)))))
