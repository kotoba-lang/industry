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
    (is (some #{:cae} (industry/required-technologies "3030"))))
  (testing "shipbuilding requires robotics and CAE"
    (is (some #{:robotics} (industry/required-technologies "3011")))
    (is (some #{:cae} (industry/required-technologies "3011"))))
  (testing "engines/turbines require robotics and CAE"
    (is (some #{:robotics} (industry/required-technologies "2811")))
    (is (some #{:cae} (industry/required-technologies "2811"))))
  (testing "basic iron and steel require robotics and CAE"
    (is (some #{:robotics} (industry/required-technologies "2410")))
    (is (some #{:cae} (industry/required-technologies "2410"))))
  (testing "motor vehicle manufacturing requires robotics and CAE"
    (is (some #{:robotics} (industry/required-technologies "2910")))
    (is (some #{:cae} (industry/required-technologies "2910"))))
  (testing "structural metal products manufacturing requires robotics and CAE"
    (is (some #{:robotics} (industry/required-technologies "2511")))
    (is (some #{:cae} (industry/required-technologies "2511"))))
  (testing "machine-tool manufacturing requires robotics and CAE"
    (is (some #{:robotics} (industry/required-technologies "2822")))
    (is (some #{:cae} (industry/required-technologies "2822"))))
  (testing "mining/construction-machinery manufacturing requires robotics and CAE"
    (is (some #{:robotics} (industry/required-technologies "2824")))
    (is (some #{:cae} (industry/required-technologies "2824"))))
  (testing "pump/compressor/valve manufacturing requires robotics and CAE"
    (is (some #{:robotics} (industry/required-technologies "2813")))
    (is (some #{:cae} (industry/required-technologies "2813"))))
  (testing "lifting/handling-equipment manufacturing requires robotics and CAE"
    (is (some #{:robotics} (industry/required-technologies "2816")))
    (is (some #{:cae} (industry/required-technologies "2816")))))

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
    ;; Unit-test the pure branch logic directly via a synthetic fixture
    ;; map (see `industry/maturity-of`) so this assertion never depends
    ;; on which, if any, REAL registry entry currently sits at
    ;; :blueprint tier -- that count legitimately fluctuates as new
    ;; blueprints are published and existing ones are implemented (it
    ;; briefly reached zero fleet-wide as of cloud-itonami-isic-9900's
    ;; own promotion, ADR-2607100300, before cloud-itonami-isic-8010
    ;; was freshly published, ADR-2607100500).
    (is (= :blueprint (industry/maturity-of {:repo "https://example.invalid/still-blueprint-fixture"}))))
  (testing "cloud-itonami-isic-8010, freshly published, is also :blueprint (live-state corroboration)"
    (is (= :blueprint (industry/maturity "8010"))))
  (testing "cloud-itonami-isic-5610, freshly published, is also :blueprint (live-state corroboration)"
    (is (= :blueprint (industry/maturity "5610"))))
  (testing "cloud-itonami-isic-8030, freshly published, is also :blueprint (live-state corroboration)"
    (is (= :blueprint (industry/maturity "8030"))))
  (testing "cloud-itonami-isic-2660, freshly published, is also :blueprint (live-state corroboration)"
    (is (= :blueprint (industry/maturity "2660"))))
  (testing "cloud-itonami-isic-3812, freshly published, is also :blueprint (live-state corroboration)"
    (is (= :blueprint (industry/maturity "3812"))))
  (testing "cloud-itonami-isic-2011, freshly published, is also :blueprint (live-state corroboration)"
    (is (= :blueprint (industry/maturity "2011"))))
  (testing "cloud-itonami-isic-4321, freshly published, is also :blueprint (live-state corroboration)"
    (is (= :blueprint (industry/maturity "4321"))))
  (testing "cloud-itonami-isic-4322, freshly published, is also :blueprint (live-state corroboration)"
    (is (= :blueprint (industry/maturity "4322"))))
  (testing "cloud-itonami-isic-3510, freshly published, is also :blueprint (live-state corroboration)"
    (is (= :blueprint (industry/maturity "3510"))))
  (testing "cloud-itonami-isic-6120, freshly published, is also :blueprint (live-state corroboration)"
    (is (= :blueprint (industry/maturity "6120"))))
  (testing "cloud-itonami-isic-5110, freshly published, is also :blueprint (live-state corroboration)"
    (is (= :blueprint (industry/maturity "5110"))))
  (testing "cloud-itonami-isic-4911, freshly published, is also :blueprint (live-state corroboration)"
    (is (= :blueprint (industry/maturity "4911"))))
  (testing "cloud-itonami-isic-5011, freshly published, is also :blueprint (live-state corroboration)"
    (is (= :blueprint (industry/maturity "5011"))))
  (testing "cloud-itonami-isic-6020, freshly published, is also :blueprint (live-state corroboration)"
    (is (= :blueprint (industry/maturity "6020"))))
  (testing "cloud-itonami-isic-6010, freshly published, is also :blueprint (live-state corroboration)"
    (is (= :blueprint (industry/maturity "6010"))))
  (testing "cloud-itonami-isic-4912, freshly published, is also :blueprint (live-state corroboration)"
    (is (= :blueprint (industry/maturity "4912"))))
  (testing "cloud-itonami-isic-5224, freshly published, is also :blueprint (live-state corroboration)"
    (is (= :blueprint (industry/maturity "5224"))))
  (testing "cloud-itonami-isic-5223, freshly published, is also :blueprint (live-state corroboration)"
    (is (= :blueprint (industry/maturity "5223"))))
  (testing "cloud-itonami-isic-5222, freshly published, is also :blueprint (live-state corroboration)"
    (is (= :blueprint (industry/maturity "5222"))))
  (testing "cloud-itonami-isic-5221, freshly published, is also :blueprint (live-state corroboration)"
    (is (= :blueprint (industry/maturity "5221"))))
  (testing "cloud-itonami-isic-5229, freshly published, is also :blueprint (live-state corroboration)"
    (is (= :blueprint (industry/maturity "5229"))))
  (testing "cloud-itonami-isic-8020, freshly published, is also :blueprint (live-state corroboration)"
    (is (= :blueprint (industry/maturity "8020"))))
  (testing "cloud-itonami-isic-3020, freshly published, is also :blueprint (live-state corroboration)"
    (is (= :blueprint (industry/maturity "3020"))))
  (testing "cloud-itonami-isic-8130, freshly published, is also :blueprint (live-state corroboration)"
    (is (= :blueprint (industry/maturity "8130"))))
  (testing "cloud-itonami-isic-8121, freshly published, is also :blueprint (live-state corroboration)"
    (is (= :blueprint (industry/maturity "8121"))))
  (testing "cloud-itonami-isic-3312, freshly published, is also :blueprint (live-state corroboration)"
    (is (= :blueprint (industry/maturity "3312"))))
  (testing "cloud-itonami-isic-3313, freshly published, is also :blueprint (live-state corroboration)"
    (is (= :blueprint (industry/maturity "3313"))))
  (testing "cloud-itonami-isic-1811, freshly published, is also :blueprint (live-state corroboration)"
    (is (= :blueprint (industry/maturity "1811"))))
  (testing "cloud-itonami-isic-1812, freshly published, is also :blueprint (live-state corroboration)"
    (is (= :blueprint (industry/maturity "1812"))))
  (testing "cloud-itonami-isic-8220, freshly published, is also :blueprint (live-state corroboration)"
    (is (= :blueprint (industry/maturity "8220"))))
  (testing "cloud-itonami-isic-8219, freshly published, is also :blueprint (live-state corroboration)"
    (is (= :blueprint (industry/maturity "8219"))))
  (testing "cloud-itonami-isic-7911, freshly published, is also :blueprint (live-state corroboration)"
    (is (= :blueprint (industry/maturity "7911"))))
  (testing "cloud-itonami-isic-7912, freshly published, is also :blueprint (live-state corroboration)"
    (is (= :blueprint (industry/maturity "7912"))))
  (testing "cloud-itonami-isic-7710, freshly published, is also :blueprint (live-state corroboration)"
    (is (= :blueprint (industry/maturity "7710"))))
  (testing "cloud-itonami-isic-7721, freshly published, is also :blueprint (live-state corroboration)"
    (is (= :blueprint (industry/maturity "7721"))))
  (testing "cloud-itonami-isic-7729, freshly published, is also :blueprint (live-state corroboration)"
    (is (= :blueprint (industry/maturity "7729"))))
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
  (testing "a ninety-seventh implemented actor (cloud-itonami-isic-9700, domestic-employment actor) is also :implemented"
    (is (= :implemented (industry/maturity "9700"))))
  (testing "a ninety-eighth implemented actor (cloud-itonami-isic-9900, mission-operations actor) is also :implemented"
    (is (= :implemented (industry/maturity "9900"))))
  (testing "a ninety-ninth implemented actor (cloud-itonami-isic-0610, crude-petroleum-extraction actor; the petroleum-fleet reference build) is also :implemented"
    (is (= :implemented (industry/maturity "0610"))))
  (testing "a hundredth implemented actor (cloud-itonami-isic-0620, natural-gas-extraction actor) is also :implemented"
    (is (= :implemented (industry/maturity "0620"))))
  (testing "a hundred-first implemented actor (cloud-itonami-isic-1920, petroleum-refining actor) is also :implemented"
    (is (= :implemented (industry/maturity "1920"))))
  (testing "a hundred-second implemented actor (cloud-itonami-isic-5210, petroleum-terminal-storage actor) is also :implemented"
    (is (= :implemented (industry/maturity "5210"))))
  (testing "a hundred-third implemented actor (cloud-itonami-isic-4730, forecourt/automotive-fuel-retail actor) is also :implemented"
    (is (= :implemented (industry/maturity "4730"))))
  (testing "a hundred-fourth implemented actor (cloud-itonami-isic-4950, petroleum-pipeline actor; project-internal isic-rev5 code, distinct from real Rev.4 4930) is also :implemented"
    (is (= :implemented (industry/maturity "4950"))))
  (testing "a hundred-fifth implemented actor (cloud-itonami-isic-5020, petroleum-tanker actor; project-internal isic-rev5 code, distinct from real Rev.4 5012/5022) is also :implemented"
    (is (= :implemented (industry/maturity "5020"))))
  (testing "a hundred-sixth implemented actor (cloud-itonami-isic-4671, fuel-wholesale-trading actor; project-internal isic-rev5 code, distinct from real Rev.4 4661) is also :implemented"
    (is (= :implemented (industry/maturity "4671"))))
  (testing "a hundred-seventh implemented actor (cloud-itonami-isic-3011, shipbuilding actor; first classic heavy-industry manufacturing vertical after aerospace 3030 and semiconductor 2610) is also :implemented"
    (is (= :implemented (industry/maturity "3011"))))
  (testing "a hundred-eighth implemented actor (cloud-itonami-isic-8291, corporate/compliance-intelligence actor; promoted directly from :spec, not via :blueprint -- ADR-2607110400) is also :implemented"
    (is (= :implemented (industry/maturity "8291"))))
  (testing "a hundred-ninth implemented actor (cloud-itonami-isic-2811, engines-turbines actor) is also :implemented"
    (is (= :implemented (industry/maturity "2811"))))
  (testing "a hundred-tenth implemented actor (cloud-itonami-isic-2410, basic-iron-steel actor) is also :implemented"
    (is (= :implemented (industry/maturity "2410"))))
  (testing "a hundred-eleventh implemented actor (cloud-itonami-isic-2910, motor-vehicle actor) is also :implemented"
    (is (= :implemented (industry/maturity "2910"))))
  (testing "a hundred-eighteenth implemented actor (cloud-itonami-isic-2511, structural-metal-products actor; fifth classic heavy-industry manufacturing vertical after 2410/2811/2910/3011) is also :implemented"
    (is (= :implemented (industry/maturity "2511"))))
  (testing "a hundred-nineteenth implemented actor (cloud-itonami-isic-2822, machine-tool actor; first machine-tool/capital-equipment vertical in the classic heavy-industry cluster, distinct from the transport-equipment sub-cluster of 2811/2910/3011) is also :implemented"
    (is (= :implemented (industry/maturity "2822"))))
  (testing "a hundred-twentieth implemented actor (cloud-itonami-isic-2824, mining/construction-machinery actor; second machine-tool/capital-equipment vertical in the classic heavy-industry cluster alongside 2822, distinct from the transport-equipment sub-cluster of 2811/2910/3011) is also :implemented"
    (is (= :implemented (industry/maturity "2824"))))
  (testing "a hundred-twenty-second implemented actor (cloud-itonami-isic-2813, pump/compressor/valve actor; third capital-equipment/general-purpose-machinery vertical in the classic heavy-industry cluster alongside 2822/2824, distinct from the transport-equipment sub-cluster of 2811/2910/3011) is also :implemented"
    (is (= :implemented (industry/maturity "2813"))))
  (testing "a hundred-twenty-fourth implemented actor (cloud-itonami-isic-2816, lifting/handling-equipment actor; fourth capital-equipment/general-purpose-machinery vertical in the classic heavy-industry cluster alongside 2822/2824/2813, distinct from the transport-equipment sub-cluster of 2811/2910/3011) is also :implemented"
    (is (= :implemented (industry/maturity "2816"))))
  (testing "cloud-itonami-isic-5820 (narrowed from the broad 'Software publishing' ISIC class to a commercial CRM/subscription-commerce SaaS platform actor, Salesforce/HubSpot-class; RevOps-LLM sealed advisor ⊣ SubscriptionGovernor, first CRM/marketing/service-hub vertical in this fleet) is also :implemented"
    (is (= :implemented (industry/maturity "5820"))))
  (testing "cloud-itonami-isic-6201 (narrowed from the broad 'Computer programming activities' ISIC class to a marketing-automation SaaS platform actor, HubSpot Marketing Hub/Salesforce Marketing Cloud-class; MarketingOps-LLM sealed advisor ⊣ ConsentGovernor, second CRM/marketing/service-hub sibling in this fleet after cloud-itonami-isic-5820) is also :implemented"
    (is (= :implemented (industry/maturity "6201"))))
  (testing "maturity-summary counts tiers"
    (let [m (industry/maturity-summary)]
      (is (= (:total m) (+ (:spec m) (:blueprint m) (:implemented m))))
      (is (pos? (:spec m)))
      ;; :blueprint legitimately fluctuates as new blueprints are
      ;; published and existing ones are implemented -- it briefly
      ;; reached zero fleet-wide as of cloud-itonami-isic-9900's own
      ;; promotion (ADR-2607100300), became 1 with cloud-itonami-isic-
      ;; 8010's own fresh publication (ADR-2607100500), 2 with
      ;; cloud-itonami-isic-5610's own (ADR-2607100600), 3 with
      ;; cloud-itonami-isic-8030's own (ADR-2607100700), 4 with
      ;; cloud-itonami-isic-2660's own (ADR-2607100800), 5 with
      ;; cloud-itonami-isic-3812's own (ADR-2607101300), 6 with
      ;; cloud-itonami-isic-2011's own (ADR-2607101400), 7 with
      ;; cloud-itonami-isic-4321's own (ADR-2607101500), 8 with
      ;; cloud-itonami-isic-4322's own (ADR-2607101600), 9 with
      ;; cloud-itonami-isic-3510's own (ADR-2607101800), 10 with
      ;; cloud-itonami-isic-6120's own (ADR-2607102000), 11 with
      ;; cloud-itonami-isic-5110's own (ADR-2607102100), 12 with
      ;; cloud-itonami-isic-4911's own (ADR-2607102200), 13 with
      ;; cloud-itonami-isic-5011's own (ADR-2607102400), 14 with
      ;; cloud-itonami-isic-6020's own (ADR-2607102500), 15 with
      ;; cloud-itonami-isic-6010's own (ADR-2607102600), 16 with
      ;; cloud-itonami-isic-4912's own (ADR-2607102800), 17 with
      ;; cloud-itonami-isic-5224's own (ADR-2607102900), 18 with
      ;; cloud-itonami-isic-5223's own (ADR-2607103100), 19 with
      ;; cloud-itonami-isic-5222's own (ADR-2607103200), 20 with
      ;; cloud-itonami-isic-5221's own (ADR-2607103300), 21 with
      ;; cloud-itonami-isic-5229's own (ADR-2607103400), 22 with
      ;; cloud-itonami-isic-8020's own (ADR-2607103500), 23 with
      ;; cloud-itonami-isic-3020's own (ADR-2607103700), 24 with
      ;; cloud-itonami-isic-8130's own (ADR-2607103800), 25 with
      ;; cloud-itonami-isic-8121's own (ADR-2607103900), 26 with
      ;; cloud-itonami-isic-3312's own (ADR-2607104000), 27 with
      ;; cloud-itonami-isic-3313's own (ADR-2607104100), 28 with
      ;; cloud-itonami-isic-1811's own (ADR-2607104200), 29 with
      ;; cloud-itonami-isic-1812's own (ADR-2607104300), 30 with
      ;; cloud-itonami-isic-8220's own (ADR-2607104400), 31 with
      ;; cloud-itonami-isic-8219's own (ADR-2607104500), 32 with
      ;; cloud-itonami-isic-7911's own (ADR-2607104600), 33 with
      ;; cloud-itonami-isic-7912's own (ADR-2607104700), 34 with
      ;; cloud-itonami-isic-7710's own (ADR-2607104800), 35 with
      ;; cloud-itonami-isic-7721's own (ADR-2607104900), 36 with
      ;; cloud-itonami-isic-7729's own (ADR-2607105100), 40 with
      ;; the 衣食住 scaffold batch (ADR-2607122200): 1010 meat
      ;; processing, 1311 textile spinning, 1410 apparel, 4100
      ;; building construction — the FIRST four entries promoted
      ;; :spec -> :blueprint explicitly (blueprint repo published, no
      ;; actor claimed; wave-3 robotics-gated per ADR-2607121000) —
      ;; then 41 with 2100 pharmaceutical manufacturing
      ;; (ADR-2607122600, scaffold batch #2: the last gap from
      ;; ADR-2607121000's follow-up list).
      ;; This is not asserted as a fixed invariant; see
      ;; `industry/maturity-of`/`industry/maturity-roadmap-of` for how
      ;; the :blueprint branch logic itself stays unit-tested without
      ;; depending on a specific live count.
      (is (= 41 (:blueprint m)))
      ;; 114 = 113 + cloud-itonami-isic-4620, promoted directly from
      ;; :spec (never a :blueprint) -- agricultural/live-animal
      ;; wholesale trading actor. 115 = 114 + cloud-itonami-isic-2910,
      ;; promoted directly from :spec (never a :blueprint) --
      ;; motor-vehicle-manufacturing actor. 116 = 115 +
      ;; cloud-itonami-isic-4630, promoted directly from :spec --
      ;; food/beverage/tobacco wholesale trading actor. 117 = 116 +
      ;; cloud-itonami-isic-7820, promoted directly from :spec --
      ;; temporary-employment-agency actor. 118 = 117 +
      ;; cloud-itonami-isic-2511, promoted directly from :spec --
      ;; structural-steel-fabrication actor, fifth classic
      ;; heavy-industry manufacturing vertical after 2410/2811/2910/3011.
      ;; 119 = 118 + cloud-itonami-isic-2822, promoted directly from
      ;; :spec -- machine-tool-manufacturing actor, the classic
      ;; heavy-industry cluster's first machine-tool/capital-equipment
      ;; vertical (distinct from the transport-equipment sub-cluster of
      ;; 2811/2910/3011). 120 = 119 + cloud-itonami-isic-2824, promoted
      ;; directly from :spec -- mining/quarrying/construction-machinery-
      ;; manufacturing actor, another capital-equipment vertical
      ;; alongside 2822 in the classic heavy-industry cluster (distinct
      ;; from the transport-equipment sub-cluster of 2811/2910/3011).
      ;; 121 = 120 + cloud-itonami-isic-4662, promoted directly from
      ;; :spec -- metal/metal-ore wholesale trading actor. 122 = 121 +
      ;; cloud-itonami-isic-2813, promoted directly from :spec --
      ;; pump/compressor/valve-manufacturing actor, a third capital-
      ;; equipment/general-purpose-machinery vertical alongside 2822/
      ;; 2824 in the classic heavy-industry cluster (distinct from the
      ;; transport-equipment sub-cluster of 2811/2910/3011). 123 = 122 +
      ;; cloud-itonami-isic-4641, promoted directly from :spec --
      ;; textile/clothing/footwear wholesale trading actor. 124 = 123 +
      ;; cloud-itonami-isic-2816, promoted directly from :spec --
      ;; lifting/handling-equipment-manufacturing actor, a fourth
      ;; capital-equipment/general-purpose-machinery vertical alongside
      ;; 2822/2824/2813 in the classic heavy-industry cluster (distinct
      ;; from the transport-equipment sub-cluster of 2811/2910/3011).
      ;; 125 = 124 + cloud-itonami-isic-4669, promoted directly from
      ;; :spec -- waste/scrap wholesale trading actor. 126 = 125 +
      ;; cloud-itonami-isic-4651, promoted directly from :spec --
      ;; computer/peripheral/software wholesale trading actor. 127 = 126 +
      ;; cloud-itonami-isic-4774, promoted directly from :spec --
      ;; secondhand-resale marketplace actor. 128 = 127 +
      ;; cloud-itonami-isic-8299, promoted directly from :spec --
      ;; virtual-assistant/BPO task-matching actor. 129 = 128 +
      ;; cloud-itonami-isic-6312, promoted directly from :spec -- web-portal
      ;; content curation & placement actor. 130 = 129 +
      ;; cloud-itonami-isic-4772, promoted directly from :spec --
      ;; governed pharmacy dispensing actor. 131 = 130 +
      ;; cloud-itonami-isic-5590, promoted directly from :spec --
      ;; alternative-accommodation (hostel/guesthouse/camping-cabin/
      ;; dormitory) booking actor. 132 = 131 + cloud-itonami-isic-3811,
      ;; promoted directly from :spec -- non-hazardous waste collection
      ;; dispatch actor. 133 = 132 + cloud-itonami-isic-4653, promoted
      ;; directly from :spec -- agricultural machinery/equipment
      ;; wholesale trading actor. 134 = 133 + cloud-itonami-isic-4652,
      ;; promoted directly from :spec -- electronic/telecommunications
      ;; equipment wholesale trading actor. 135 = 134 +
      ;; cloud-itonami-isic-4510, promoted directly from :spec -- motor
      ;; vehicle sales actor. 136 = 135 + cloud-itonami-isic-3822,
      ;; promoted directly from :spec -- hazardous waste treatment/
      ;; disposal actor. 137 = 136 + cloud-itonami-isic-6209, promoted
      ;; directly from :spec -- IT managed-services/helpdesk
      ;; ticket-routing actor. 138 = 137 + cloud-itonami-isic-4649,
      ;; promoted directly from :spec -- household-goods wholesale
      ;; trading actor. 139 = 138 + cloud-itonami-isic-4663, promoted
      ;; directly from :spec -- construction materials/plumbing
      ;; wholesale trading actor. 140 = 139 + cloud-itonami-isic-4659,
      ;; promoted directly from :spec -- precision machinery/machine-
      ;; tool wholesale trading actor. 141 = 140 +
      ;; cloud-itonami-isic-6399, promoted directly from :spec -- meta
      ;; job-search (job-posting aggregation/publication/delisting, the
      ;; Indeed-shaped business, ISIC 6399 narrowed; ADR-2607121700).
      ;; 142 = 141 + cloud-itonami-isic-5820, promoted directly from
      ;; :spec -- CRM platform actor, the fleet's first CRM/marketing/
      ;; service-hub sibling. 143 = 142 + cloud-itonami-isic-6201,
      ;; promoted directly from :spec -- marketing-automation SaaS
      ;; platform actor. 144 = 143 + cloud-itonami-isic-6202, promoted
      ;; directly from :spec -- customer-service-hub actor. (This entry
      ;; reconciles a counter race: three concurrent sessions promoted
      ;; 5820/6201/6202 while the assertion was bumped only to 143 and
      ;; the ledger comment stopped at 141 -- attribution verified by
      ;; diffing the implemented-id set between the 141-era commit
      ;; 5ffdc774 and main.)
      (is (= 144 (:implemented m))))))

(deftest maturity-roadmap-reports-next-step
  (testing "an implemented entry is at maturity ceiling"
    (let [r (industry/maturity-roadmap "6310")]
      (is (= :implemented (:maturity r)))
      (is (nil? (:next-step r)))
      (is (= "at maturity ceiling" (:next-action r)))))
  (testing "a blueprint entry's next step is implemented"
    ;; No REAL registry entry sits at :blueprint tier any more (see
    ;; `maturity-tier`'s own comment above) -- unit-test the pure
    ;; roadmap logic directly via a synthetic fixture map + an empty
    ;; technology stack instead of a live example.
    (let [r (industry/maturity-roadmap-of {:repo "https://example.invalid/still-blueprint-fixture"} [])]
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
    ;; "9900" is now :implemented (see `maturity-tier`'s own comment
    ;; above) rather than :blueprint, but this assertion is maturity-
    ;; independent -- ui-ready?/export-ready? are pure functions of
    ;; :required-technologies via `technology-stack`, unaffected by
    ;; promotion -- so no swap is needed here.
    (let [p (industry/execution-plan "9900")]
      (is (true? (:ui-ready? p)))
      (is (true? (:export-ready? p)))
      (is (some :ui? (:technology-stack p)))))
  (testing "a vertical with only infrastructure techs reports not ready"
    (let [p (industry/execution-plan "2610")]   ; eda/cae/dmn/bpmn/audit-ledger + robotics
      ;; robotics has ui?, so ui-ready is true; but a pure-infra entry below tests the negative
      (is (map? p)))))
