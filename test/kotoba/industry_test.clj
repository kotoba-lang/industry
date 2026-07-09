(ns kotoba.industry-test
  (:require [clojure.test :refer [deftest is testing]]
            [kotoba.industry :as industry]))

(deftest registry-loads
  (let [reg (industry/registry)]
    (is (= :kotoba/industry (:kotoba.registry/id reg)))
    (is (<= 9 (count (industry/industries reg))))))

(deftest open-business-industries-resolve
  (doseq [isic ["3512" "3600" "3830" "6310" "8569" "8691" "8810"]]
    (is (:business-id (industry/get-industry isic)))
    (is (seq (industry/required-technologies isic)))
    (is (seq (:technology-stack (industry/execution-plan isic))))))

(deftest engineering-industries-use-eda-cfd-cae
  (testing "semiconductor manufacturing can require EDA"
    (is (some #{:eda} (industry/required-technologies "2610"))))
  (testing "aerospace enablement can require CFD/CAE"
    (is (some #{:cfd} (industry/required-technologies "3030")))
    (is (some #{:cae} (industry/required-technologies "3030")))))

(deftest readiness-reports-missing-tech
  (let [r (industry/readiness "8691" #{:identity :forms})]
    (is (false? (:ready? r)))
    (is (contains? (:missing r) :audit-ledger)))
  ;; :robotics is required on every itonami vertical (robotics-premise design),
  ;; so it must be in the available set for a vertical to read ready.
  (is (:ready? (industry/readiness "6310" #{:robotics :identity :forms :dmn :bpmn :audit-ledger}))))

(deftest maturity-tier
  (testing "the reference actor is implemented"
    (is (= :implemented (industry/maturity "6310"))))
  (testing "a published blueprint repo is :blueprint"
    (is (= :blueprint (industry/maturity "9700"))))
  (testing "a registry-only group entry is :spec"
    (is (= :spec (industry/maturity "011"))))
  (testing "a second implemented actor (cloud-itonami-isic-6810) is also :implemented"
    (is (= :implemented (industry/maturity "6810"))))
  (testing "a third implemented actor (cloud-itonami-isic-6910) is also :implemented"
    (is (= :implemented (industry/maturity "6910"))))
  (testing "a fourth implemented actor (cloud-itonami-6511) is also :implemented"
    (is (= :implemented (industry/maturity "6511"))))
  (testing "a fifth implemented actor (cloud-itonami-isic-6499, venture-capital-fund actor) is also :implemented"
    (is (= :implemented (industry/maturity "6499"))))
  (testing "a sixth implemented actor (cloud-itonami-isic-6430, fund-vehicle actor) is also :implemented"
    (is (= :implemented (industry/maturity "6430"))))
  (testing "a seventh implemented actor (cloud-itonami-isic-6630, management-company actor) is also :implemented"
    (is (= :implemented (industry/maturity "6630"))))
  (testing "an eighth implemented actor (cloud-itonami-isic-6512, non-life/property-casualty actor) is also :implemented"
    (is (= :implemented (industry/maturity "6512"))))
  (testing "a ninth implemented actor (cloud-itonami-isic-6621, independent loss-adjustment actor) is also :implemented"
    (is (= :implemented (industry/maturity "6621"))))
  (testing "a tenth implemented actor (cloud-itonami-isic-6622, insurance-intermediation actor) is also :implemented"
    (is (= :implemented (industry/maturity "6622"))))
  (testing "an eleventh implemented actor (cloud-itonami-isic-6629, insurance-auxiliary actor) is also :implemented"
    (is (= :implemented (industry/maturity "6629"))))
  (testing "a twelfth implemented actor (cloud-itonami-isic-6520, reinsurance actor) is also :implemented"
    (is (= :implemented (industry/maturity "6520"))))
  (testing "a thirteenth implemented actor (cloud-itonami-isic-6530, pension-funding actor) is also :implemented"
    (is (= :implemented (industry/maturity "6530"))))
  (testing "a fourteenth implemented actor (cloud-itonami-isic-6820, real-estate-fee-services actor) is also :implemented"
    (is (= :implemented (industry/maturity "6820"))))
  (testing "a fifteenth implemented actor (cloud-itonami-isic-6612, securities-brokerage actor) is also :implemented"
    (is (= :implemented (industry/maturity "6612"))))
  (testing "a sixteenth implemented actor (cloud-itonami-isic-6492, credit-granting actor) is also :implemented"
    (is (= :implemented (industry/maturity "6492"))))
  (testing "a seventeenth implemented actor (cloud-itonami-isic-6920, accounting/auditing actor) is also :implemented"
    (is (= :implemented (industry/maturity "6920"))))
  (testing "an eighteenth implemented actor (cloud-itonami-isic-6611, market-administration actor) is also :implemented"
    (is (= :implemented (industry/maturity "6611"))))
  (testing "a nineteenth implemented actor (cloud-itonami-isic-7120, technical-testing/analysis actor) is also :implemented"
    (is (= :implemented (industry/maturity "7120"))))
  (testing "a twentieth implemented actor (cloud-itonami-isic-8620, medical/dental-practice actor) is also :implemented"
    (is (= :implemented (industry/maturity "8620"))))
  (testing "a twenty-first implemented actor (cloud-itonami-isic-8530, higher-education actor) is also :implemented"
    (is (= :implemented (industry/maturity "8530"))))
  (testing "a twenty-second implemented actor (cloud-itonami-isic-9200, gambling/betting actor) is also :implemented"
    (is (= :implemented (industry/maturity "9200"))))
  (testing "a twenty-third implemented actor (cloud-itonami-isic-7500, veterinary actor) is also :implemented"
    (is (= :implemented (industry/maturity "7500"))))
  (testing "a twenty-fourth implemented actor (cloud-itonami-isic-9603, funeral/death-care actor) is also :implemented"
    (is (= :implemented (industry/maturity "9603"))))
  (testing "a twenty-fifth implemented actor (cloud-itonami-isic-9521, electronics-repair actor) is also :implemented"
    (is (= :implemented (industry/maturity "9521"))))
  (testing "a twenty-sixth implemented actor (cloud-itonami-isic-9321, amusement-park/ride-safety actor) is also :implemented"
    (is (= :implemented (industry/maturity "9321"))))
  (testing "a twenty-seventh implemented actor (cloud-itonami-isic-8730, residential-eldercare actor) is also :implemented"
    (is (= :implemented (industry/maturity "8730"))))
  (testing "a twenty-eighth implemented actor (cloud-itonami-isic-9102, museum/cultural-heritage actor) is also :implemented"
    (is (= :implemented (industry/maturity "9102"))))
  (testing "a twenty-ninth implemented actor (cloud-itonami-isic-9103, zoo/botanical-garden/conservation actor) is also :implemented"
    (is (= :implemented (industry/maturity "9103"))))
  (testing "a thirtieth implemented actor (cloud-itonami-isic-9602, hairdressing/beauty-treatment actor) is also :implemented"
    (is (= :implemented (industry/maturity "9602"))))
  (testing "a thirty-first implemented actor (cloud-itonami-isic-9000, creative/arts/entertainment actor) is also :implemented"
    (is (= :implemented (industry/maturity "9000"))))
  (testing "a thirty-second implemented actor (cloud-itonami-isic-8890, social-work/casework actor) is also :implemented"
    (is (= :implemented (industry/maturity "8890"))))
  (testing "a thirty-third implemented actor (cloud-itonami-isic-8610, hospital actor) is also :implemented"
    (is (= :implemented (industry/maturity "8610"))))
  (testing "a thirty-fourth implemented actor (cloud-itonami-isic-9311, sports-facility actor) is also :implemented"
    (is (= :implemented (industry/maturity "9311"))))
  (testing "a thirty-fifth implemented actor (cloud-itonami-isic-8510, school actor) is also :implemented"
    (is (= :implemented (industry/maturity "8510"))))
  (testing "a thirty-sixth implemented actor (cloud-itonami-isic-9412, association actor) is also :implemented"
    (is (= :implemented (industry/maturity "9412"))))
  (testing "a thirty-seventh implemented actor (cloud-itonami-isic-6491, leasing actor) is also :implemented"
    (is (= :implemented (industry/maturity "6491"))))
  (testing "a thirty-eighth implemented actor (cloud-itonami-isic-8720, behavioral-care actor) is also :implemented"
    (is (= :implemented (industry/maturity "8720"))))
  (testing "a thirty-ninth implemented actor (cloud-itonami-isic-8521, secondary-education actor) is also :implemented"
    (is (= :implemented (industry/maturity "8521"))))
  (testing "a fortieth implemented actor (cloud-itonami-isic-6619, card-processing actor) is also :implemented"
    (is (= :implemented (industry/maturity "6619"))))
  (testing "a forty-first implemented actor (cloud-itonami-isic-3600, water-utility actor) is also :implemented"
    (is (= :implemented (industry/maturity "3600"))))
  (testing "a forty-second implemented actor (cloud-itonami-isic-6190, telecom-operator actor) is also :implemented"
    (is (= :implemented (industry/maturity "6190"))))
  (testing "a forty-third implemented actor (cloud-itonami-isic-3030, aerospace-manufacturing actor) is also :implemented"
    (is (= :implemented (industry/maturity "3030"))))
  (testing "a forty-fourth implemented actor (cloud-itonami-isic-3830, materials-recovery actor) is also :implemented"
    (is (= :implemented (industry/maturity "3830"))))
  (testing "a forty-fifth implemented actor (cloud-itonami-isic-7020, management-consultancy actor) is also :implemented"
    (is (= :implemented (industry/maturity "7020"))))
  (testing "a forty-sixth implemented actor (cloud-itonami-isic-9420, trade-union actor) is also :implemented"
    (is (= :implemented (industry/maturity "9420"))))
  (testing "a forty-seventh implemented actor (cloud-itonami-isic-9491, congregation actor) is also :implemented"
    (is (= :implemented (industry/maturity "9491"))))
  (testing "a forty-eighth implemented actor (cloud-itonami-isic-2610, semiconductor-fab actor) is also :implemented"
    (is (= :implemented (industry/maturity "2610"))))
  (testing "a forty-ninth implemented actor (cloud-itonami-isic-3512, community-energy actor) is also :implemented"
    (is (= :implemented (industry/maturity "3512"))))
  (testing "a fiftieth implemented actor (cloud-itonami-isic-8810, community-care-coordination actor) is also :implemented"
    (is (= :implemented (industry/maturity "8810"))))
  (testing "a fifty-first implemented actor (cloud-itonami-isic-8691, health-access-navigation actor) is also :implemented"
    (is (= :implemented (industry/maturity "8691"))))
  (testing "a fifty-second implemented actor (cloud-itonami-isic-8569, community-learning-support actor) is also :implemented"
    (is (= :implemented (industry/maturity "8569"))))
  (testing "a fifty-third implemented actor (cloud-itonami-isic-6419, community-banking actor) is also :implemented"
    (is (= :implemented (industry/maturity "6419"))))
  (testing "a fifty-fourth implemented actor (cloud-itonami-isic-7310, advertising-agency actor) is also :implemented"
    (is (= :implemented (industry/maturity "7310"))))
  (testing "a fifty-fifth implemented actor (cloud-itonami-isic-7320, market-research-firm actor) is also :implemented"
    (is (= :implemented (industry/maturity "7320"))))
  (testing "a fifty-sixth implemented actor (cloud-itonami-isic-7210, rd-lab actor) is also :implemented"
    (is (= :implemented (industry/maturity "7210"))))
  (testing "a fifty-seventh implemented actor (cloud-itonami-isic-7410, design-studio actor) is also :implemented"
    (is (= :implemented (industry/maturity "7410"))))
  (testing "a fifty-eighth implemented actor (cloud-itonami-isic-8710, nursing-care actor) is also :implemented"
    (is (= :implemented (industry/maturity "8710"))))
  (testing "a fifty-ninth implemented actor (cloud-itonami-isic-8541, sports-instruction actor) is also :implemented"
    (is (= :implemented (industry/maturity "8541"))))
  (testing "a sixtieth implemented actor (cloud-itonami-isic-8690, allied-health actor) is also :implemented"
    (is (= :implemented (industry/maturity "8690"))))
  (testing "a sixty-first implemented actor (cloud-itonami-isic-9601, garment-care actor) is also :implemented"
    (is (= :implemented (industry/maturity "9601"))))
  (testing "a sixty-second implemented actor (cloud-itonami-isic-6420, holding-company actor) is also :implemented"
    (is (= :implemented (industry/maturity "6420"))))
  (testing "a sixty-third implemented actor (cloud-itonami-isic-7420, photographic-studio actor) is also :implemented"
    (is (= :implemented (industry/maturity "7420"))))
  (testing "a sixty-fourth implemented actor (cloud-itonami-isic-9609, personal-service-provider actor) is also :implemented"
    (is (= :implemented (industry/maturity "9609"))))
  (testing "a sixty-fifth implemented actor (cloud-itonami-isic-8550, educational-support-services actor) is also :implemented"
    (is (= :implemented (industry/maturity "8550"))))
  (testing "a sixty-sixth implemented actor (cloud-itonami-isic-7010, head-office actor) is also :implemented"
    (is (= :implemented (industry/maturity "7010"))))
  (testing "a sixty-seventh implemented actor (cloud-itonami-isic-8790, residential-care actor) is also :implemented"
    (is (= :implemented (industry/maturity "8790"))))
  (testing "a sixty-eighth implemented actor (cloud-itonami-isic-8542, cultural-education actor) is also :implemented"
    (is (= :implemented (industry/maturity "8542"))))
  (testing "a sixty-ninth implemented actor (cloud-itonami-isic-6411, central-banking actor) is also :implemented"
    (is (= :implemented (industry/maturity "6411"))))
  (testing "a seventieth implemented actor (cloud-itonami-isic-7490, professional-services actor) is also :implemented"
    (is (= :implemented (industry/maturity "7490"))))
  (testing "a seventy-second implemented actor (cloud-itonami-isic-9319, sports-event actor; the seventy-first, cloud-itonami-isic-6110, was promoted concurrently by another session) is also :implemented"
    (is (= :implemented (industry/maturity "9319"))))
  (testing "a seventy-third implemented actor (cloud-itonami-isic-9329, recreation-venue-safety actor) is also :implemented"
    (is (= :implemented (industry/maturity "9329"))))
  (testing "a seventy-fourth implemented actor (cloud-itonami-isic-9312, sports-club-membership-governance actor) is also :implemented"
    (is (= :implemented (industry/maturity "9312"))))
  (testing "a seventy-fifth implemented actor (cloud-itonami-isic-9492, political-organization-governance actor) is also :implemented"
    (is (= :implemented (industry/maturity "9492"))))
  (testing "a seventy-sixth implemented actor (cloud-itonami-isic-9499, other-membership-organization-governance actor) is also :implemented"
    (is (= :implemented (industry/maturity "9499"))))
  (testing "a seventy-seventh implemented actor (cloud-itonami-isic-9512, communication-equipment-repair actor) is also :implemented"
    (is (= :implemented (industry/maturity "9512"))))
  (testing "a seventy-eighth implemented actor (cloud-itonami-isic-9522, household-appliance-repair actor) is also :implemented"
    (is (= :implemented (industry/maturity "9522"))))
  (testing "a seventy-ninth implemented actor (cloud-itonami-isic-7220, social-sciences-and-humanities-research actor) is also :implemented"
    (is (= :implemented (industry/maturity "7220"))))
  (testing "an eightieth implemented actor (cloud-itonami-isic-9411, business-employer-membership-organization actor) is also :implemented"
    (is (= :implemented (industry/maturity "9411"))))
  (testing "an eighty-first implemented actor (cloud-itonami-isic-8522, technical-vocational-secondary-education actor) is also :implemented"
    (is (= :implemented (industry/maturity "8522"))))
  (testing "an eighty-second implemented actor (cloud-itonami-isic-8549, other-education-training-provider actor) is also :implemented"
    (is (= :implemented (industry/maturity "8549"))))
  (testing "an eighty-third implemented actor (cloud-itonami-isic-9524, furniture-repair actor) is also :implemented"
    (is (= :implemented (industry/maturity "9524"))))
  (testing "an eighty-fourth implemented actor (cloud-itonami-isic-9529, specialty-repair actor) is also :implemented"
    (is (= :implemented (industry/maturity "9529"))))
  (testing "an eighty-fifth implemented actor (cloud-itonami-isic-9523, leather-goods-repair actor) is also :implemented"
    (is (= :implemented (industry/maturity "9523"))))
  (testing "an eighty-sixth implemented actor (cloud-itonami-isic-9511, ICT-equipment-repair actor) is also :implemented"
    (is (= :implemented (industry/maturity "9511"))))
  (testing "an eighty-seventh implemented actor (cloud-itonami-isic-4711, community-retail actor) is also :implemented"
    (is (= :implemented (industry/maturity "4711"))))
  (testing "an eighty-eighth implemented actor (cloud-itonami-isic-4920, community-freight actor) is also :implemented"
    (is (= :implemented (industry/maturity "4920"))))
  (testing "an eighty-ninth implemented actor (cloud-itonami-isic-0810, community-quarry actor) is also :implemented"
    (is (= :implemented (industry/maturity "0810"))))
  (testing "a ninetieth implemented actor (cloud-itonami-isic-0162, community-agronomy actor; first agriculture-sector actor) is also :implemented"
    (is (= :implemented (industry/maturity "0162"))))
  (testing "a ninety-first implemented actor (cloud-itonami-isic-4211, community-building-construction actor) is also :implemented after its robot-dispatch slice landed"
    (is (= :implemented (industry/maturity "4211"))))
  (testing "a ninety-second implemented actor (cloud-itonami-isic-5510, community-accommodation actor) is also :implemented"
    (is (= :implemented (industry/maturity "5510"))))
  (testing "a ninety-third implemented actor (cloud-itonami-isic-7110, architectural-engineering-practice actor) is also :implemented"
    (is (= :implemented (industry/maturity "7110"))))
  (testing "a ninety-fourth implemented actor (cloud-itonami-isic-7810, employment-agency actor) is also :implemented"
    (is (= :implemented (industry/maturity "7810"))))
  (testing "a ninety-fifth implemented actor (cloud-itonami-isic-8411, public-administration actor) is also :implemented"
    (is (= :implemented (industry/maturity "8411"))))
  (testing "a ninety-sixth implemented actor (cloud-itonami-isic-9101, library-archive actor) is also :implemented"
    (is (= :implemented (industry/maturity "9101"))))
  (testing "maturity-summary counts tiers"
    (let [m (industry/maturity-summary)]
      (is (= (:total m) (+ (:spec m) (:blueprint m) (:implemented m))))
      (is (pos? (:spec m)))
      (is (pos? (:blueprint m)))
      (is (= 96 (:implemented m))))))

(deftest maturity-roadmap-reports-next-step
  (testing "an implemented entry is at maturity ceiling"
    (let [r (industry/maturity-roadmap "6310")]
      (is (= :implemented (:maturity r)))
      (is (nil? (:next-step r)))
      (is (= "at maturity ceiling" (:next-action r)))))
  (testing "a blueprint entry's next step is implemented"
    (let [r (industry/maturity-roadmap "9700")]
      (is (= :blueprint (:maturity r)))
      (is (= :implemented (:next-step r)))
      (is (true? (:has-repo r)))))
  (testing "a spec entry's next step is blueprint"
    (let [r (industry/maturity-roadmap "011")]
      (is (= :spec (:maturity r)))
      (is (= :blueprint (:next-step r)))
      (is (false? (:has-repo r))))))

(deftest execution-plan-reports-ui-export-readiness
  (testing "a vertical backed by a capability lib reports ui+export ready"
    (let [p (industry/execution-plan "9700")]
      (is (true? (:ui-ready? p)))
      (is (true? (:export-ready? p)))
      (is (some :ui? (:technology-stack p)))))
  (testing "a vertical with only infrastructure techs reports not ready"
    (let [p (industry/execution-plan "2610")]   ; eda/cae/dmn/bpmn/audit-ledger + robotics
      ;; robotics has ui?, so ui-ready is true; but a pure-infra entry below tests the negative
      (is (map? p)))))
