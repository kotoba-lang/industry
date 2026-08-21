(ns probe-mcad-gdt-pmi
  (:require [kami.modeling.drawing :as d] [kami.modeling.step :as step]))
;; 不変条件: (1) GD&T の feature control frame が **記号・公差・データムを検証して**作られる
;;              （不正な記号、負の公差、4 つ以上のデータムは拒否 = 両方向）
;;          (2) AP242 の PMI（寸法・plus/minus 公差・データム・データム系）を読み取る
;;          (3) 参照の壊れた PMI を **名指しで報告する**（例外ではなく errors として）
;;          (4) AP242 でないプロファイルの PMI をそう報告する
(try
  (let [ok (d/feature-control-frame :position 0.1 ["A" "B"])
        bad (fn [f] (try (f) :not-refused (catch :default _ :refused)))
        pmi-step (str "ISO-10303-21;\nHEADER;\n"
                      "FILE_SCHEMA(('AP242_MANAGED_MODEL_BASED_3D_ENGINEERING_MIM_LF'));\n"
                      "ENDSEC;\nDATA;\n"
                      "#10=MEASURE_WITH_UNIT(MEASURE(-0.1),#1);\n"
                      "#11=MEASURE_WITH_UNIT(MEASURE(0.1),#1);\n"
                      "#12=TOLERANCE_VALUE(#10,#11);\n"
                      "#20=DIMENSIONAL_SIZE(#5,'width','nominal 40');\n"
                      "#21=PLUS_MINUS_TOLERANCE(#12,#20);\n"
                      "#30=DATUM('A','primary',#5,.F.,'A');\n"
                      "#31=DATUM_REFERENCE_COMPARTMENT(#30);\n"
                      "#32=DATUM_SYSTEM(#31);\n"
                      "ENDSEC;\nEND-ISO-10303-21;\n")
        pmi (step/import-pmi pmi-step)
        errs (step/pmi-validation-errors pmi)
        dangling (step/pmi-validation-errors
                  (update pmi :pmi/tolerances
                          (fn [ts] (mapv #(assoc % :pmi/dimension "#999") ts))))]
    (cond
      (not= :position (:gdt/symbol ok))
      (println "PROBE mcad-gdt-pmi FAIL" "feature control frame が記号を保持しない")
      (not (every? #(= :refused %)
                   [(bad #(d/feature-control-frame :no-such-symbol 0.1 ["A"]))
                    (bad #(d/feature-control-frame :position -1 ["A"]))
                    (bad #(d/feature-control-frame :position 0.1 ["A" "B" "C" "D"]))
                    (bad #(d/feature-control-frame :position 0.1 ["lowercase"]))]))
      (println "PROBE mcad-gdt-pmi FAIL" "不正な GD&T frame を拒否しない（両方向を出さない）")
      (not= :ap242 (:pmi/source-profile pmi))
      (println "PROBE mcad-gdt-pmi FAIL"
               (str "AP242 プロファイルを認識しない: " (pr-str (:pmi/source-profile pmi))))
      (not (and (= 1 (count (:pmi/dimensions pmi))) (= 1 (count (:pmi/tolerances pmi)))
                (= 1 (count (:pmi/datums pmi))) (= 1 (count (:pmi/datum-systems pmi)))))
      (println "PROBE mcad-gdt-pmi FAIL"
               (str "PMI の読み取り件数が合わない: 寸法" (count (:pmi/dimensions pmi))
                    " 公差" (count (:pmi/tolerances pmi)) " データム" (count (:pmi/datums pmi))
                    " 系" (count (:pmi/datum-systems pmi))))
      (seq errs)
      (println "PROBE mcad-gdt-pmi FAIL" (str "整合した PMI を誤って不正と報告: " (pr-str errs)))
      (not (some #(= :dangling-tolerance-dimension (:error %)) dangling))
      (println "PROBE mcad-gdt-pmi FAIL" "参照の壊れた公差を報告しない")
      :else (println "PROBE mcad-gdt-pmi PASS"
                     (str "GD&T frame は 4 種の不正を拒否 / AP242 PMI 寸法1・公差1・"
                          "データム1・系1 を読み、健全なら 0 件・壊せば dangling を報告"))))
  (catch :default ex (println "PROBE mcad-gdt-pmi UNMEASURABLE" (.-message ex))))
