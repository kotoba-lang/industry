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
  (testing "cloud-itonami-isic-2660, promoted to :implemented (medical-device actor deployed)"
    (is (= :implemented (industry/maturity "2660"))))
  (testing "cloud-itonami-isic-3812, now :implemented with real actor, was :blueprint (2026-07-14)"
    (is (= :implemented (industry/maturity "3812"))))
  (testing "cloud-itonami-isic-2011, promoted to :implemented (basic-chemicals actor deployed)"
    (is (= :implemented (industry/maturity "2011"))))
  (testing "cloud-itonami-isic-4321, now implemented, is :implemented (live-state corroboration)"
    (is (= :implemented (industry/maturity "4321"))))
  (testing "cloud-itonami-isic-4322, promoted to :implemented (plumbing/HVAC actor deployed)"
    (is (= :implemented (industry/maturity "4322"))))
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
  (testing "cloud-itonami-isic-3020, promoted to :implemented (rolling-stock manufacturing actor deployed)"
    (is (= :implemented (industry/maturity "3020"))))
  (testing "cloud-itonami-isic-8130, freshly published, is also :blueprint (live-state corroboration)"
    (is (= :blueprint (industry/maturity "8130"))))
  (testing "cloud-itonami-isic-8121, freshly published, is also :blueprint (live-state corroboration)"
    (is (= :blueprint (industry/maturity "8121"))))
  (testing "cloud-itonami-isic-3312, machinery-repair actor, is now :implemented (live-state corroboration)"
    (is (= :implemented (industry/maturity "3312"))))
  (testing "cloud-itonami-isic-3313, promoted from :blueprint, is also :implemented (live-state corroboration)"
    (is (= :implemented (industry/maturity "3313"))))
  (testing "cloud-itonami-isic-1811, promoted to :implemented (printing actor deployed)"
    (is (= :implemented (industry/maturity "1811"))))
  (testing "cloud-itonami-isic-1812, implemented, is :implemented (live-state corroboration)"
    (is (= :implemented (industry/maturity "1812"))))
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
  (testing "cloud-itonami-isic-6611-cryptoexchange, freshly published, is also :blueprint (live-state corroboration; FIRST role-suffix satellite id under an already-:implemented class — wave-of still resolves via the 66 division prefix)"
    (is (= :blueprint (industry/maturity "6611-cryptoexchange")))
    (is (= 0 (:wave (industry/maturity-roadmap "6611-cryptoexchange")))))
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
  (testing "cloud-itonami-isic-6493 (Factoring activities, ISIC Rev.5 division 649 -- a genuine standalone class distinct from 6491/6492/6499/6494/6495; Factoring-LLM sealed advisor ⊣ Factoring Governor, two actuation events, registered directly at :implemented with no prior :spec placeholder) is also :implemented"
    (is (= :implemented (industry/maturity "6493"))))
  (testing "cloud-itonami-isic-6120, promoted from :blueprint (spectrum-licensed mobile-network-operator actor, Network Operations Advisor ⊣ Mobile Network Governor, mirroring cloud-itonami-isic-6190's module shape -- same ISIC 61xx telecom industry) is also :implemented"
    (is (= :implemented (industry/maturity "6120"))))
  (testing "cloud-itonami-isic-6130 (Satellite telecommunications activities, fresh scaffold -- no prior blueprint repo; Satellite Operations Advisor ⊣ Satellite Network Governor, mirroring cloud-itonami-isic-6190's/6120's module shape -- same ISIC 61xx telecom industry -- while citing satellite-licensing/ITU-coordination spec-basis, a distinct regulatory regime; registered directly at :implemented with no prior :blueprint stage) is also :implemented"
    (is (= :implemented (industry/maturity "6130"))))
  (testing "cloud-itonami-isic-6391 (News agency activities, fresh scaffold -- no prior blueprint repo; Wire Advisor ⊣ Wire Governor, domain logic modeled on cloud-itonami-isco-3521 [this fleet's closest domain analog], repo-layout on cloud-itonami-isic-6130; HARD-gates a story's sourcing completeness and an independently ground-truth-recomputed embargo instant, SOFT-escalates a legally-sensitive flag recomputed the same way [no 'forgot to screen' loophole]; this fleet's FIRST asymmetric dual-actuation shape -- :actuation/distribute may auto-commit at phase 3 when clean, :actuation/issue-correction never auto-commits at any phase; registered directly at :implemented with no prior :blueprint stage; closes ISIC Wave 0's LAST class-level gap alongside cloud-itonami-isic-6130's own same-day promotion) is also :implemented"
    (is (= :implemented (industry/maturity "6391"))))
  (testing "cloud-itonami-isic-3510 (Electric power generation, transmission and distribution -- promoted from a prior published :blueprint repo [ADR-2607101800]; Grid Distribution Advisor ⊣ Grid Transmission Governor, repo-layout modeled on cloud-itonami-isic-6130, infrastructure/utility domain shape modeled on cloud-itonami-isic-3600 [this fleet's closest domain analog]; protected-recipient-violations is this fleet's FIRST always-un-overridable HARD check -- a meter flagged life-support/critical-infrastructure can never be disconnected, at any confidence or human approval; capacity-over-threshold-violations is this fleet's SECOND asymmetric dual-actuation shape [after cloud-itonami-isic-6391], on a new value-driven dimension -- clean under-threshold :actuation/provision-service may auto-commit at phase 3, over-threshold always escalates; :actuation/disconnect-service permanently excluded from every phase's :auto set; first ISIC Wave 1 class promoted, ADR-2607121000's own Top-10 value-ranking item #6) is also :implemented"
    (is (= :implemented (industry/maturity "3510"))))
  (testing "cloud-itonami-isic-1061 (Manufacture of grain mill products, fresh scaffold -- the entry pointed at a never-created gftdcojp/cloud-itonami-C1061 placeholder repo; MillOpsAdvisor ⊣ Mill Governor plant-operations-coordination actor mirroring cloud-itonami-isic-1071's [Bakery products] verified module shape; mycotoxin-level-exceeded-violations is a genuinely new independently-verified physical check for this fleet's food-manufacturing cluster [per-product-type ppb ceiling, e.g. corn meal's stricter 20ppb aflatoxin limit vs. wheat flour's 1000ppb DON limit]; foreign-material-detected-violations and magnet-calibration-overdue-violations [90-day interval] are likewise new; also fixes a latent JVM-only `.indexOf` interop bug present in the mirrored cloud-itonami-isic-1071/src/bakeryops/phase.cljc reference [ClojureScript's PersistentVector does not implement `.indexOf`] with a portable keep-indexed-based helper) is also :implemented"
    (is (= :implemented (industry/maturity "1061"))))
  (testing "cloud-itonami-isic-0145 (Raising of swine/pigs, fresh scaffold -- no prior blueprint repo; corrected from a task assignment that mislabeled this ISIC class as 0144 [which this registry, matching the real UN ISIC Rev.4 standard, correctly assigns to 'Raising of sheep and goats' -- the 0144 entry itself is untouched by this promotion]; SwineOpsAdvisor ⊣ SwineFarmOperationsGovernor swine-farm-operations-coordination actor mirroring cloud-itonami-isic-0141's [Raising of cattle and buffaloes] verified module shape module-for-module -- barn/pen facility registration in place of pasture, breed reference data [landrace/duroc/yorkshire/berkshire] in place of species, and a biosecurity/notifiable-disease reference vocabulary [ASF/CSF/FMD/PRRS] in place of generic disease citation; flag-animal-health-concern always escalates regardless of confidence, matching 0141's own animal-welfare-escalation invariant; superproject ADR-2607154000) is also :implemented"
    (is (= :implemented (industry/maturity "0145"))))
  (testing "cloud-itonami-isic-4311 (Demolition, fresh scaffold -- no prior failed attempt; correctly registered against this registry's own 4311='Demolition' entry, avoiding the 0144/0145-style code-mislabel failure mode; DemolitionAdvisor ⊣ DemolitionGovernor demolition-project-OPERATIONS-COORDINATION actor structured after cloud-itonami-isic-4211's [Community Building Construction] robotics-premise module shape but deliberately NARROWED -- no robotics/simphysics module, no heavy-equipment-control or structural-engineering-decision/demolition-plan-finalization authority [permanent, un-overridable governor hard-blocks], every proposal's :effect is :propose only [governor HARD-holds anything else, defense-in-depth]; per-jurisdiction {JPN/USA/DEU} hazmat-survey/demolition-notification legal-basis catalog citing real official sources [石綿障害予防規則, 建設リサイクル法, OSHA 29 CFR 1926.1101, 40 CFR 61.145 NESHAP, EU Directive 2009/148/EC]; schedule-demolition-operation and flag-safety-concern always escalate to a human at every phase, unconditionally; DatomicStore uses langchain-store.core [ADR-2607141600] instead of hand-rolling the EDN-blob codec; superproject ADR-2607155000) is also :implemented"
    (is (= :implemented (industry/maturity "4311"))))
  (testing "cloud-itonami-isic-0146 (Raising of poultry, fresh scaffold -- no prior repository; identity ({:id \"0146\" :name \"Raising of poultry\"}) independently verified against a fresh clone before any work began, per this fleet's ID/name-mismatch caution; PoultryOpsAdvisor ⊣ PoultryFarmOperationsGovernor poultry-farm-operations-coordination actor mirroring cloud-itonami-isic-0145's [Raising of swine/pigs] verified module shape module-for-module -- barn/house facility registration, broiler/layer breed reference data [Cobb 500/Ross 308/White Leghorn/ISA Brown] in place of swine breeds, and a biosecurity/notifiable-disease reference vocabulary [HPAI/Newcastle Disease/Infectious Bronchitis/Infectious Bursal Disease] in place of ASF/CSF/FMD/PRRS; flag-animal-health-concern always escalates regardless of confidence, matching 0145's own animal-welfare-escalation invariant; treatment-or-culling-blocked permanently blocks :administer-treatment and :order-culling; superproject ADR-2607160300) is also :implemented"
    (is (= :implemented (industry/maturity "0146"))))
  (testing "cloud-itonami-isic-4210 (Construction of roads and railways, fresh scaffold -- no prior repository at either the stale gftdcojp/cloud-itonami-F4210 placeholder or the real cloud-itonami org [gh api 404 confirmed]; identity ({:id \"4210\" :name \"Construction of roads and railways\"}) independently verified against a fresh clone before any work began, per this fleet's ID/name-mismatch caution; Road-Rail Advisor ⊣ Road-Rail Governor road/railway-construction-project OPERATIONS COORDINATION actor structured after cloud-itonami-isic-4211's [Community Building Construction] robotics-premise module shape but deliberately NARROWED, same coordination-only shape as cloud-itonami-isic-4311 [Demolition] -- no heavy-equipment-control or engineering-design/grade-plan-finalization authority [permanent, un-overridable governor hard-blocks], every proposal's :effect is :propose only [governor HARD-holds anything else, defense-in-depth]; per-jurisdiction {JPN/USA/DEU} utility-strike-prevention/traffic-control legal-basis catalog citing real official sources [labor safety ordinance 労働安全衛生規則 第355条, road traffic act 道路交通法 第77条, construction recycling act 建設リサイクル法 第10条 in Japan; OSHA 29 CFR 1926.651, 23 CFR 630/645 in the USA; EU Directive 92/57/EEC, StVO §45 in Germany/EU]; schedule-construction-operation and flag-safety-concern always escalate to a human at every phase, unconditionally; DatomicStore uses langchain-store.core [ADR-2607141600] instead of hand-rolling the EDN-blob codec; 68 tests / 258 assertions green, independently re-verified against a fresh clone; superproject ADR-2607161700) is also :implemented"
    (is (= :implemented (industry/maturity "4210"))))
  (testing "cloud-itonami-isic-3100 (Manufacture of furniture, fresh scaffold -- no prior repository at either the stale gftdcojp/cloud-itonami-C3100 placeholder or the real cloud-itonami org [gh api 404 confirmed]; identity ({:id \"3100\" :name \"Manufacture of furniture\"}) independently verified against a fresh clone before any work began, per this fleet's ID/name-mismatch caution; FurnitureAdvisor ⊣ Furniture Plant Operations Governor furniture-factory plant-operations-COORDINATION actor, mirroring cloud-itonami-isic-1610's [Sawmilling and planing of wood] verified module shape module-for-module -- cutting/sanding/finishing-line equipment registration in place of saw/planer/kiln, quality-grade/unit-count/defect-rate production-batch fields in place of lumber-grade/volume/moisture-content, and a line-run-finalize permanent block [furnituremfg.governor's line-finalize-blocked-violations] in place of kiln-schedule-finalize; safety-concern flagging always escalates regardless of confidence [materials safety -- VOC finish-fume exposure, equipment safety, labor safety]; 71 tests / 195 assertions green, independently re-verified against a fresh clone; superproject ADR-2607161800) is also :implemented"
    (is (= :implemented (industry/maturity "3100"))))
  (testing "cloud-itonami-isic-0123 (Growing of citrus fruits, fresh scaffold -- no prior repository [gh api 404 confirmed]; identity ({:id \"0123\" :name \"Growing of citrus fruits\"}) independently verified against a fresh clone before any work began, per this fleet's ID/name-mismatch caution; CitrusOpsAdvisor ⊣ CitrusOperationsGovernor citrus-orchard-operations-coordination actor mirroring cloud-itonami-isic-0122's [Growing of tropical and subtropical fruits] verified module shape module-for-module -- orange/lemon/lime/grapefruit fruit-class reference data in place of mango/banana/papaya/avocado/pineapple; flag-crop-health-concern (e.g. citrus greening/HLB) always escalates regardless of confidence, matching 0122's own crop-health-escalation invariant; field-equipment-or-spray-blocked permanently blocks :operate-field-equipment and :finalize-spray-application; 30 tests / 92 assertions green, independently re-verified against a fresh clone; superproject ADR-2607172000) is also :implemented"
    (is (= :implemented (industry/maturity "0123"))))
  (testing "cloud-itonami-isic-3320 (Installation of industrial machinery and equipment, fresh scaffold -- no prior repository at either the stale gftdcojp/cloud-itonami-C3320 placeholder or the real cloud-itonami org [gh api 404 confirmed]; identity ({:id \"3320\" :name \"Installation of industrial machinery and equipment\"}) independently verified against a fresh clone before any work began, per this fleet's ID/name-mismatch caution; Installation Advisor ⊣ Installation Governor industrial-machinery-installation-project OPERATIONS COORDINATION actor, coordination-only and narrower than the cloud-itonami-isic-4211 robotics-premise reference, same shape as cloud-itonami-isic-4311 [Demolition] and cloud-itonami-isic-4210 [Roads/railways construction]; closed op allowlist (log-installation-record/schedule-installation-operation/flag-safety-concern/order-supplies), all :effect :propose only; heavy-lift/rigging-equipment control and commissioning-energization sign-off permanently blocked; per-jurisdiction [JPN/USA/DEU] lift-plan/installation-notification legal-basis catalog citing real official sources (Japan's Industrial Safety and Health Act Art. 88 -- the sole :quantitative jurisdiction, 30-calendar-day lead-time; OSHA 29 CFR 1926 Subpart CC + 1910.147 for the USA; BetrSichV Sec.15 + EU Machinery Directive 2006/42/EC for Germany/EU -- USA/DEU honestly :qualitative); no JVM-only interop anywhere in src/ (mock-only notifier); 64 tests / 241 assertions green, independently re-verified against a fresh clone; superproject ADR-2607171900) is also :implemented"
    (is (= :implemented (industry/maturity "3320"))))
  (testing "cloud-itonami-isic-1062 (Manufacture of starches and starch products, fresh scaffold -- the entry pointed at a never-created gftdcojp/cloud-itonami-C1062 placeholder repo, both this and the real cloud-itonami org target name independently confirmed 404 before scaffolding; identity ({:id \"1062\" :name \"Manufacture of starches and starch products\"}) independently verified against a fresh clone before any work began; StarchOpsAdvisor ⊣ Starch Governor plant-operations-coordination actor mirroring cloud-itonami-isic-1061's [Manufacture of grain mill products] verified module shape module-for-module -- raw-material intake (corn/potato/cassava/wheat) in place of grain intake, steep/extract/refine/dry phase split in place of a single :mill phase; sulfite-residue-exceeded-violations and microbial-load-exceeded-violations are this actor's independently-verified food-safety physical checks in place of 1061's single mycotoxin-level-exceeded-violations [18 hard-violation checks total, one more than 1061's 17, because sulfite-residue from steeping and microbial-load from extended slurry dwell time are genuinely distinct hazards]; detection-equipment-calibration-overdue-violations uses a 60-day interval, shorter than 1061's 90-day magnet-calibration interval, reflecting the higher fouling/drift rate of continuous wet-processing equipment; wheat starch retains a genuine :wheat/gluten allergen unlike corn/potato/cassava starch, mirroring 1061's oat/wheat shared-milling-line cross-contact hazard; 52 tests / 175 assertions green, independently re-verified against a fresh clone; superproject ADR-2607152500) is also :implemented"
    (is (= :implemented (industry/maturity "1062"))))
  (testing "cloud-itonami-isic-0126 (Growing of oleaginous fruits, fresh scaffold -- the entry pointed at a never-created gftdcojp/cloud-itonami-A0126 placeholder repo, both this and the real cloud-itonami org target name independently confirmed 404 before scaffolding; identity ({:id \"0126\" :name \"Growing of oleaginous fruits\"}) independently verified against a fresh clone before any work began, per this fleet's ID/name-mismatch caution; OleaginousOpsAdvisor ⊣ OleaginousOperationsGovernor oleaginous-fruit-plantation-operations-coordination actor mirroring cloud-itonami-isic-0125's [Growing of other tree and bush fruits and nuts] verified module shape module-for-module -- oil-palm/olive/coconut/candlenut fruit-class reference data (palm/drupe/nut groups) in place of blueberry/raspberry/blackcurrant/almond/walnut/hazelnut/pecan (bush/nut groups); flag-crop-health-concern (e.g. bud rot/Ganoderma boninense, drought-stress) always escalates regardless of confidence, matching 0125's own crop-health-escalation invariant; field-equipment-or-spray-blocked permanently blocks :operate-field-equipment and :finalize-spray-application; 31 tests / 99 assertions green, independently re-verified against a fresh clone; superproject ADR-2607151330) is also :implemented"
    (is (= :implemented (industry/maturity "0126"))))
  (testing "cloud-itonami-isic-1104 (Manufacture of soft drinks; production of mineral waters and other bottled waters, fresh scaffold -- no prior repository at either the stale gftdcojp/cloud-itonami-C1104 placeholder or the real cloud-itonami org [gh api 404 confirmed]; identity ({:id \"1104\" :name \"Manufacture of soft drinks\"}) independently verified against a fresh clone before any work began, per this fleet's ID/name-mismatch caution; SoftDrinkOpsAdvisor ⊣ SoftDrinkOps Governor bottling-plant-operations-coordination actor mirroring cloud-itonami-isic-1102's [Manufacture of wines] verified module shape module-for-module (softdrinkops.* in place of wineops.*) -- water/ingredient intake -> mixing -> carbonation -> filling -> inspection -> audit -> archived phase sequence in place of intake -> crush -> fermentation -> pressing -> aging -> bottling -> audit -> archived; carbonation-tolerance/Brix-sugar-content-window/preservative-residue/microbial-load/fill-volume/mineral-content-minimum compliance parameters in place of ABV-tolerance/residual-sugar-window/volatile-acidity/SO2-residue/fill-volume/vintage-percent-minimum, per US FDA 21 CFR Part 165/110/101, EU Reg (EC) 1333/2008 / Directive 2009/54/EC, and JP 食品衛生法 (清涼飲料水の規格基準, 厚生労働省/消費者庁); direct mixing/carbonation/filling-line control and food-safety-certification authority permanently blocked by the closed op allowlist (log-production-batch/schedule-maintenance/flag-food-safety-concern/coordinate-shipment, all :effect :propose); :flag-food-safety-concern always escalates regardless of confidence, matching wine's own food-safety-escalation invariant; 58 tests / 195 assertions green, independently re-verified against a fresh clone; superproject ADR-2607157000) is also :implemented"
    (is (= :implemented (industry/maturity "1104"))))
  (testing "cloud-itonami-isic-0130 (Plant propagation -- a prior repository already existed [cloud-itonami/cloud-itonami-isic-0130, commit 3d450370a28e] but the registry entry stayed at :spec because that prior attempt was broken [op names divergent from the domain design, no deps.edn/blueprint.edn/GOVERNANCE.md/CODE_OF_CONDUCT.md/CONTRIBUTING.md/SECURITY.md, missing facts_test/phase_test/registry_test/store_test, and facts.cljc used unconditional `js/Date.` (previously unguarded JVM-only `(new Date ...)`), a cljs-first violation outside any reader conditional -- confirmed broken by direct test run rather than assumed]; identity ({:id \"0130\" :name \"Plant propagation\"}) independently verified against a fresh clone before any work began, per this fleet's ID/name-mismatch caution; rebuilt in place on the same repo (not a fresh scaffold) as a PropagationAdvisor ⊣ NurseryOperationsGovernor plant-propagation-nursery OPERATIONS COORDINATION actor mirroring cloud-itonami-isic-0164's [Seed processing for propagation] verified module shape module-for-module -- propagation-method (cutting/graft/tissue-culture/seed-sown) rooting-rate/hardening-period viability windows and genetic-fidelity risk table in place of seed-lot moisture/germination/purity windows and GM-trait table; closed op allowlist (log-propagation-batch/schedule-maintenance/flag-quality-concern/coordinate-shipment), all :effect :propose only; greenhouse/irrigation/propagation-equipment control and phytosanitary-certification authority permanently out of scope via the closed allowlist; 14 independently-verified hard-violation checks including a batch-registration-before-any-action invariant applied across all four allowed ops (broader than 0164's shipment-only registration check, per this actor's explicit domain-design requirement); all host-clock access isolated behind #?(:clj/:cljs) reader conditionals; 43 tests / 134 assertions green, independently re-verified against a fresh clone; superproject ADR-2607191000) is also :implemented"
    (is (= :implemented (industry/maturity "0130"))))
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
      ;; ADR-2607121000's follow-up list), then 42 with
      ;; 6611-cryptoexchange (ADR-2607141200): the FIRST role-suffix
      ;; satellite entry under an already-:implemented class —
      ;; incident-proof full-reserve crypto-asset exchange blueprint,
      ;; no actor claimed, real-funds operation permanently gated on
      ;; jurisdiction licensing + owner decision (INV-14).
      ;; This is not asserted as a fixed invariant; see
      ;; `industry/maturity-of`/`industry/maturity-roadmap-of` for how
      ;; the :blueprint branch logic itself stays unit-tested without
      ;; depending on a specific live count.
      ;; 42 -> 41: cloud-itonami-isic-6120 promoted :blueprint ->
      ;; :implemented (spectrum-licensed mobile-network-operator actor,
      ;; mirroring cloud-itonami-isic-6190's module shape).
      ;; 41 -> 40: cloud-itonami-isic-3510 promoted :blueprint ->
      ;; :implemented (Grid Distribution Advisor ⊣ Grid Transmission
      ;; Governor, electric-power transmission/distribution actor).
      ;; 40 -> 39: cloud-itonami-isic-1812 promoted :blueprint ->
      ;; :implemented (Print Support Services, pre-press/bindery actor).
      ;; 39 -> 38: cloud-itonami-isic-3812 promoted :blueprint ->
      ;; :implemented (Hazardous Waste Collection, HazardousWasteDispatch-LLM
      ;; advisor, HazardousWasteGovernor, langgraph-clj StateGraph, 2026-07-14).
      ;; Wave-3 batch (2026-07-14): 1010/1311/1410/1811/1812/2011/2100/2660/
      ;; 3020/3312/3313/3812/4100/4321/4322 all promoted :blueprint ->
      ;; :implemented, clearing the remaining blueprint-tier backlog to 25.
      (is (= 25 (:blueprint m)))
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
      ;; 144 -> 146: registry<->repo drift sync (ADR-2607131000) — 5320
      ;; courier/delivery and 7830 human-resources-provision satellites
      ;; existed with src+test while the registry still said :spec with
      ;; no :repo link (the isco-3521 drift pattern, full-org scan).
      ;; Both suites were RUN green before claiming :implemented
      ;; (5320: 40 tests/200 assertions; 7830: 33/115).
      ;; 147 = 146 + cloud-itonami-isic-6493, newly registered directly
      ;; at :implemented -- unlike every entry above, this ISIC had NO
      ;; prior placeholder at ANY maturity tier in this registry (not
      ;; even :spec); confirmed via the official ISIC Rev.5 structure/
      ;; explanatory-notes cross-check (superproject ADR-2607141700).
      ;; Factoring actor (Factoring-LLM ⊣ Factoring Governor), the third
      ;; division-649 vertical alongside 6491/6492/6499, with TWO
      ;; actuation events (advance/settle) and three first-class
      ;; anti-Zentoshin mitigations (publicly-queryable ledger-recomputed
      ;; solvency attestation, distributed named-funder concentration
      ;; limits, a published/versioned fee schedule) -- see
      ;; cloud-itonami-isic-6493/docs/adr/0001-architecture.md.
      ;; 147 -> 148: cloud-itonami-isic-6120 promoted :blueprint ->
      ;; :implemented -- spectrum-licensed mobile-network-operator actor
      ;; (Network Operations Advisor ⊣ Mobile Network Governor), module
      ;; shape mirrors cloud-itonami-isic-6190 (same ISIC 61xx telecom
      ;; industry). 36 tests/173 assertions run green before claiming
      ;; :implemented.
      ;; 148 -> 149: cloud-itonami-isic-6130, registered directly at
      ;; :implemented -- no prior :blueprint stage (unlike 6120's path);
      ;; the legacy placeholder :repo (gftdcojp/cloud-itonami-J6130) was
      ;; confirmed 404 before this edit. Satellite Operations Advisor ⊣
      ;; Satellite Network Governor actor, module shape mirrors
      ;; cloud-itonami-isic-6190/6120 (same ISIC 61xx telecom industry)
      ;; while citing satellite-licensing/ITU frequency-and-orbital-slot-
      ;; coordination spec-basis (a distinct regulatory regime from a
      ;; terrestrial numbering plan or terrestrial mobile-spectrum
      ;; license). satellite-number-invalid-format? is the THIRD
      ;; application of this fleet's format/syntactic-validity check
      ;; family (after 6190's e164-invalid-format? and 6120's
      ;; msisdn-invalid-format?). 36 tests/173 assertions run green
      ;; before claiming :implemented. Closes the last ISIC Wave 0
      ;; class-level gap alongside "6391" (left untouched -- a possible
      ;; concurrent sibling build). See superproject ADR-2607141800.
      ;; 149 -> 150: cloud-itonami-isic-6391, registered directly at
      ;; :implemented -- no prior :blueprint stage; the legacy
      ;; placeholder :repo (gftdcojp/cloud-itonami-J6391) was confirmed
      ;; 404 before this edit. Wire Advisor ⊣ Wire Governor news-wire-
      ;; operator actor; domain logic (advisor/governor/store split, no
      ;; DatomicStore) modeled on cloud-itonami-isco-3521 (this fleet's
      ;; closest domain analog -- media content moving from creation/
      ;; verification to distribution), repo-layout/deps.edn/ADR
      ;; convention on cloud-itonami-isic-6130 (this fleet's most
      ;; recent fresh-scaffold precedent, landed the same day).
      ;; embargo-violated? (newswire.registry) is a genuinely NEW
      ;; temporal ground-truth-recompute check kind for this fleet
      ;; (independently recomputes whether `now` is still before the
      ;; story's own recorded embargo instant); legally-sensitive-
      ;; violations is likewise independently ground-truth-recomputed
      ;; off the story's own field rather than gated behind a prior
      ;; screening call. This fleet's FIRST asymmetric dual-actuation
      ;; shape: :actuation/distribute may auto-commit at phase 3 when
      ;; clean/non-sensitive, but :actuation/issue-correction never
      ;; auto-commits at any phase (enforced independently by both the
      ;; governor's high-stakes set and the phase table) -- issuing a
      ;; correction/retraction for an already-distributed story is a
      ;; distinct, always-human-signoff act. 37 tests/94 assertions run
      ;; green before claiming :implemented. Closes ISIC Wave 0's LAST
      ;; class-level gap -- ISIC Wave 0 is now 100% :implemented at the
      ;; class level. See superproject ADR-2607142200 (renumbered from
      ;; 2607142100: a concurrent session claimed that slot before this
      ;; ADR landed, mirroring 6130's own same-day renumbering).
      ;; 150 -> 151: cloud-itonami-isic-3510, promoted from a prior
      ;; published :blueprint repo (ADR-2607101800) -- Grid Distribution
      ;; Advisor ⊣ Grid Transmission Governor, electric-power
      ;; transmission/distribution actor. Repo-layout modeled on
      ;; cloud-itonami-isic-6130, infrastructure/utility domain shape
      ;; modeled on cloud-itonami-isic-3600 (this fleet's closest domain
      ;; analog, checked explicitly -- neither models an analogous
      ;; protected-recipient concept). protected-recipient-violations is
      ;; this fleet's FIRST always-un-overridable HARD check: a meter
      ;; flagged life-support/critical-infrastructure can never be
      ;; disconnected, at any confidence level or human approval.
      ;; capacity-over-threshold-violations is this fleet's SECOND
      ;; asymmetric dual-actuation shape (after cloud-itonami-isic-6391),
      ;; on a genuinely new value-driven (not op-kind-driven) dimension:
      ;; a clean, under-threshold :actuation/provision-service may
      ;; auto-commit at phase 3, the same proposal over the capacity
      ;; threshold always escalates. :actuation/disconnect-service is
      ;; permanently excluded from every phase's :auto set, enforced
      ;; independently by both the governor's high-stakes set and the
      ;; phase table. 42 tests/194 assertions run green before claiming
      ;; :implemented. First ISIC Wave 1 class promoted -- ADR-2607121000's
      ;; own explicit Top-10 value-ranking item #6. See superproject
      ;; ADR-2607142400. 0910 (Petroleum Services) promoted to :implemented
      ;; per ADR-2607142600. 2394/2620/2930/4741 promoted to :implemented
      ;; per ADR-2607142800's robotics-process-simulation value-chain
      ;; build-out (auto-parts/cement-mill/device-assembly/computer-retail).
      ;; 166 -> 167: cloud-itonami-isic-1812 promoted :blueprint ->
      ;; :implemented (Print Support Services, pre-press/bindery services actor).
      ;; 167 -> 168: cloud-itonami-isic-2011 promoted :blueprint ->
      ;; :implemented (Basic Chemicals Manufacturing, operations coordinator actor).
      ;; Wave-3 batch (2026-07-14): 1010/1311/1410/1811/1812/2011/2100/2660/
      ;; 3020/3312/3313/3812/4100/4321/4322 all landed :implemented (this
      ;; assertion's own count-tracking had fallen behind several concurrent
      ;; agents' individual landings); corrected to the actual final total: 181.
      ;; A second Wave-3 batch of 18 fresh-scaffold agriculture/forestry/
      ;; fishing/mining/food agents landed the same day; a post-hoc audit
      ;; (real `clojure -M:test` runs, not agents' self-reports) found 11 of
      ;; the 18 were broken (missing src modules, JVM-only interop bugs,
      ;; real test failures, or unlanded registry commits) despite claiming
      ;; green -- reverted those 11 back to :spec with reasons recorded on
      ;; each entry. Genuinely verified good: 0321/0891/1020/1050/1200/1312.
      ;; 181 -> 187. cloud-itonami-isic-0311 (Marine fishing) redone from
      ;; scratch (fishing.governor/store/llm-advisor/operation/sim, 20
      ;; tests / 31 assertions green, independently re-verified against a
      ;; fresh clone) after its own prior docs-only/fabricated-report
      ;; revert; promoted :spec -> :implemented, ADR-2607151700. 187 -> 188.
      ;; 188 -> 189: cloud-itonami-isic-0141 promoted :spec -> :implemented
      ;; (Raising of cattle and buffaloes; CattleOpsAdvisor ⊣
      ;; RanchingOperationsGovernor ranch-operations-coordination actor,
      ;; module structure mirrored from cloud-itonami-isic-1010. Reverts a
      ;; prior same-day attempt that pushed docs+tests with no src/. 30
      ;; tests/90 assertions run green before claiming :implemented,
      ;; independently re-verified against a fresh clone. ADR-2607152100).
      ;; 189 -> 190: cloud-itonami-isic-0510 (Mining of hard coal) redone
      ;; from scratch (coalops.advisor/governor/phase/operation/store/sim,
      ;; module shape mirrored on cloud-itonami-isic-0891, 43 tests / 133
      ;; assertions green, independently re-verified against a fresh
      ;; post-merge clone) after its own prior "all tests green" claim was
      ;; found false (16 tests, 10 real failures -- a string-vs-keyword
      ;; map-key mismatch in the site-directory test fixtures silently
      ;; defeated every site lookup) and reverted; promoted :spec ->
      ;; :implemented, ADR-2607152100.
      ;; 190 -> 191: cloud-itonami-isic-1071 (Manufacture of bakery
      ;; products) completed (facts/registry/store modules, deps.edn,
      ;; blueprint.edn, README.md added; governor bugs found by actually
      ;; running the tests fixed -- high-stakes/actuation status was read
      ;; off a field no caller set, an unconditional js/Date.now broke
      ;; JVM compilation, phase.cljc returned nil instead of false) after
      ;; a prior missing-modules / fabricated-"35 tests, all green"-report
      ;; revert; 37 tests / 124 assertions green, independently
      ;; re-verified against a fresh clone; promoted :spec ->
      ;; :implemented, ADR-2607151800.
      ;; 191 -> 192: cloud-itonami-isic-0210 (Silviculture and other
      ;; forestry activities) promoted :spec -> :implemented
      ;; (ForestryAdvisor <-> Forest Coordination Governor back-office
      ;; coordination actor). Reverts a prior same-day attempt that
      ;; scaffolded operation.cljc/phase.cljc/sim.cljc/tests but shipped
      ;; no deps.edn and never implemented governor.cljc/store.cljc/
      ;; advisor.cljc/registry.cljc -- no real safety-gated actor existed.
      ;; Redone from scratch mirroring cloud-itonami-isic-0891/1010's
      ;; module shape; 64 tests/160 assertions run green before claiming
      ;; :implemented, independently re-verified against a fresh clone.
      ;; ADR-2607142200.
      ;; 192 -> 193: cloud-itonami-isic-1040 (Manufacture of vegetable and
      ;; animal oils and fats) redone from scratch (oilsfats.governor/
      ;; advisor/registry/store/facts + deps.edn, 56 tests / 139 assertions
      ;; green, independently re-verified against a fresh clone) after its
      ;; own prior missing-modules revert; promoted :spec -> :implemented,
      ;; ADR-2607151800.
      ;; 193 -> 194: cloud-itonami-isic-0121 (Growing of grapes) fresh
      ;; scaffold (vineyardops.governor/advisor/facts/registry/store/
      ;; operation/phase/sim, VineyardOpsAdvisor <-> VineyardOperations
      ;; Governor vineyard-operations-coordination actor, module structure
      ;; mirrored from cloud-itonami-isic-0141). 30 tests / 90 assertions
      ;; run green before claiming :implemented, independently
      ;; re-verified against a fresh clone; promoted :spec ->
      ;; :implemented, ADR-2607152200.
      ;; 194 -> 195: cloud-itonami-isic-0520 (Mining of lignite) fresh
      ;; from-scratch scaffold (no prior actor existed -- the entry
      ;; pointed at a never-created gftdcojp/cloud-itonami-B0520
      ;; placeholder). LigniteOpsAdvisor <-> LigniteMiningGovernor
      ;; operations-coordination actor mirroring cloud-itonami-isic-0510's
      ;; verified module shape; 45 tests / 136 assertions green,
      ;; independently re-verified against a fresh clone; promoted
      ;; :spec -> :implemented, ADR-2607152300.
      ;; 195 -> 196: cloud-itonami-isic-0230 (Gathering of non-wood forest
      ;; products) fresh scaffold (nwfp.governor/advisor/registry/store/
      ;; phase/operation/sim, NwfpAdvisor <-> NWFP Gathering Coordination
      ;; Governor back-office coordination actor, module structure
      ;; mirrored from cloud-itonami-isic-0210 -- this fleet's closest
      ;; domain analog). The prior gftdcojp/cloud-itonami-A0230 repo
      ;; reference was never published (confirmed: repo did not exist).
      ;; 66 tests / 161 assertions run green before claiming :implemented,
      ;; independently re-verified against a fresh clone; promoted :spec ->
      ;; :implemented, ADR-2607152400.
      ;; 196 -> 197: cloud-itonami-isic-0312 (Freshwater fishing) fresh
      ;; scaffold (no prior repo), mirroring cloud-itonami-isic-0311
      ;; (Marine fishing)'s verified pattern: freshwater-fishing.governor/
      ;; store/llm-advisor/operation/sim, :water-body replacing 0311's
      ;; :flag-state (no maritime-zone/EEZ concept for inland waters);
      ;; 20 tests / 31 assertions green, independently re-verified against
      ;; a fresh clone; promoted :spec -> :implemented, ADR-2607142230.
      ;; 197 -> 198: cloud-itonami-isic-0111 (Growing of cereals, except
      ;; rice) rebuilt from scratch (cerealops.governor/store/advisor/
      ;; facts/registry/operation/phase/sim, module structure mirrored
      ;; from cloud-itonami-isic-0141, 30 tests / 96 assertions green,
      ;; independently re-verified against a fresh clone) after deleting
      ;; a broken same-day stray artifact (missing advisor.cljc, missing
      ;; 3/5 test namespaces, unresolvable deps.edn local/root
      ;; dependency); promoted :spec -> :implemented, superproject
      ;; ADR-2607152500 (renumbered from an original 2607152200 slot,
      ;; claimed first by a concurrent session's cloud-itonami-isic-0121
      ;; ADR). Live-recomputed via `(industry/maturity-summary)`
      ;; immediately before this edit, per the batch's concurrent-agent
      ;; count-drift caution.
      ;; 198 -> 199: cloud-itonami-isic-1061 (Manufacture of grain mill
      ;; products) fresh from-scratch scaffold (no prior actor existed --
      ;; the entry pointed at a never-created gftdcojp/cloud-itonami-C1061
      ;; placeholder). MillOpsAdvisor <-> Mill Governor plant-operations-
      ;; coordination actor mirroring cloud-itonami-isic-1071's [Bakery
      ;; products] verified module shape; 49 tests / 160 assertions green,
      ;; independently re-verified against a fresh clone; promoted
      ;; :spec -> :implemented, superproject ADR-2607152400
      ;; (cloud-itonami-isic-1061-grain-mill-products-coverage.md --
      ;; NOTE: this same 2607152400 timestamp slot was independently
      ;; claimed the same day by an unrelated ADR for a different ISIC
      ;; class; both files exist under distinct full filenames with no
      ;; path collision, left un-renumbered per this fleet's existing
      ;; same-day-renumbering-is-normal precedent, e.g. ADR-2607142200).
      ;; 199 -> 200: cloud-itonami-isic-0113 (Growing of vegetables and
      ;; melons, roots and tubers) fresh from-scratch scaffold (no prior
      ;; actor existed -- the entry pointed at a never-created
      ;; gftdcojp/cloud-itonami-A0113 placeholder). VegOpsAdvisor <->
      ;; FieldOperationsGovernor field-operations-coordination actor
      ;; mirroring cloud-itonami-isic-0111's [Growing of cereals]
      ;; verified module shape (vegops.* in place of cerealops.*); 30
      ;; tests / 99 assertions green, independently re-verified against
      ;; a fresh clone; promoted :spec -> :implemented, superproject
      ;; ADR-2607153000. Live-recomputed via `(industry/maturity-summary)`
      ;; immediately before this edit (a clojure.test failure diff, not
      ;; an assumed fixed number), per the batch's concurrent-agent
      ;; count-drift caution: 199 -> 200 confirmed by the test runner
      ;; itself (`expected: (= 199 (:implemented m)) actual: (not (= 199
      ;; 200))`), not by a static grep.
      ;; 200 -> 201: cloud-itonami-isic-0112 (Growing of rice) fresh
      ;; from-scratch scaffold (entry pointed at a never-created
      ;; gftdcojp/cloud-itonami-A0112 placeholder). RiceOpsAdvisor <->
      ;; PaddyOperationsGovernor rice-paddy-operations-coordination actor
      ;; mirroring cloud-itonami-isic-0111's [Growing of cereals, except
      ;; rice] verified module shape, with a paddy-specific water-level
      ;; validity check (water-level-invalid, mirrors 0111's
      ;; field-record-invalid for acreage) and an :operate-irrigation-
      ;; equipment hard block (flooding/drainage valves, pumps) added
      ;; alongside the mirrored :operate-field-equipment /
      ;; :finalize-pesticide-application blocks; 35 tests / 107
      ;; assertions green, independently re-verified against a fresh
      ;; clone; promoted :spec -> :implemented, superproject
      ;; ADR-2607152600 (cloud-itonami-isic-0112-rice-growing-coverage.md).
      ;; Live-recomputed via `(industry/maturity-summary)` immediately
      ;; before this edit on a freshly re-fetched origin/main (201, after
      ;; a first landing attempt hit a 409 because a sibling
      ;; cloud-itonami-isic-0113 promotion landed first and bumped the
      ;; baseline from 199 to 200).
      ;; 201 -> 202: cloud-itonami-isic-0322 (Freshwater aquaculture)
      ;; fresh from-scratch scaffold (no prior actor existed -- the
      ;; entry pointed at a never-created gftdcojp/cloud-itonami-A0322
      ;; placeholder). Freshwater Advisor <-> FreshwaterAquacultureOperations
      ;; Governor pond/tank/raceway operations-coordination actor
      ;; mirroring cloud-itonami-isic-0321's [Marine aquaculture] verified
      ;; module shape (no maritime-zone/vessel concepts; water-quality/
      ;; pond-registration concepts instead), with an explicit closed
      ;; op-allowlist hard check (`:unknown-operation`) strengthening the
      ;; 0321 reference's implicit `op->effect`-lookup gap; 23 tests / 34
      ;; assertions green, independently re-verified against a fresh
      ;; clone; promoted :spec -> :implemented, superproject
      ;; ADR-2607152600 (cloud-itonami-isic-0322-freshwater-aquaculture-
      ;; coverage.md -- NOTE: this same 2607152600 timestamp slot was
      ;; independently claimed the same day by the unrelated
      ;; cloud-itonami-isic-0112 promotion above; both files exist under
      ;; distinct full filenames with no path collision, left
      ;; un-renumbered per this fleet's existing
      ;; same-day-renumbering-is-normal precedent). Live-recomputed via
      ;; `(industry/maturity-summary)` immediately before this edit on a
      ;; freshly re-fetched origin/main (201, not assumed), after a first
      ;; landing attempt against a now-stale base hit a 409 because the
      ;; sibling cloud-itonami-isic-0112/0113 promotions landed first.
      ;; 202 -> 203: cloud-itonami-isic-0893 (Extraction of salt) fresh
      ;; from-scratch scaffold (no prior actor existed -- the entry
      ;; pointed at a never-created gftdcojp/cloud-itonami-B0893
      ;; placeholder; a sibling agent originally assigned neighboring
      ;; class 0892 verified the registry first, found 0892 is actually
      ;; "Extraction of peat", halted, and identified 0893 as the true
      ;; salt slot). SaltOpsAdvisor <-> SaltExtractionGovernor
      ;; operations-coordination actor mirroring cloud-itonami-isic-0520's
      ;; [Mining of lignite] verified module shape (saltops.* in place of
      ;; ligniteops.*), domain-adapted to cover both rock-salt dry/
      ;; underground mining and solution mining/evaporation; 45 tests /
      ;; 136 assertions green, independently re-verified against a fresh
      ;; clone; promoted :spec -> :implemented, superproject
      ;; ADR-2607152700. Live-recomputed via
      ;; `(industry/maturity-summary)` immediately before this edit on a
      ;; freshly re-fetched origin/main (202, not assumed) after a first
      ;; landing attempt against a now-stale base hit a 409.
      ;; 203 -> 204: cloud-itonami-isic-1030 (Processing and preserving of
      ;; fruit and vegetables), promoted :spec -> :implemented. The entry
      ;; had pointed at a never-created gftdcojp/cloud-itonami-C1030
      ;; placeholder. A prior attempt on this class had honestly
      ;; self-reported that its own registry-promotion commit never
      ;; landed (left correctly at :spec), but independent verification
      ;; found the actor code it had pushed was itself genuinely broken,
      ;; not just the registry step: `facts.cljc` still defined MEAT
      ;; product types (fresh-beef/fresh-pork/fresh-poultry/processed-
      ;; sausage) and FSIS citations left over from a copy-pasted
      ;; meat-processing template, and `governor.cljc` read a
      ;; `:storage-time-max-days` key those meat-domain jurisdictions
      ;; never had, throwing a NullPointerException on a real
      ;; `:log-production-batch` proposal (baseline `clojure -M:test`:
      ;; 28 tests / 106 assertions / 4 failures / 2 errors). Rebuilt
      ;; `facts`/`store`/`advisor` with real canned/frozen/dried
      ;; fruit-and-vegetable domain content (FDA/EFSA/MHLW jurisdictions,
      ;; harvest-lot/residue-screening/spoilage-flag evidence, a
      ;; low-acid-canning botulism-risk scheduled-process citation), and
      ;; closed a governance gap: the Governor was missing three of the
      ;; domain design's explicit HARD invariants (closed op-allowlist,
      ;; `:effect :propose`-only, plant/batch-must-be-registered) and
      ;; read high-stakes/escalation off the advisor's self-reported
      ;; `:stake` instead of `(:op request)`, which would have let
      ;; `:flag-food-safety-concern` auto-commit instead of always
      ;; escalating -- fixed by adopting cloud-itonami-isic-1071's more
      ;; defensible op-based pattern (module shape otherwise mirrors
      ;; cloud-itonami-isic-1050's MockAdvisor + StateGraph-stub + 0->3
      ;; phase-gate shape). 39 tests / 160 assertions green, independently
      ;; re-verified against a fresh clone; superproject
      ;; ADR-2607152800 (cloud-itonami-isic-1030-fruit-vegetable-
      ;; processing-coverage.md). Live-recomputed via
      ;; `(industry/maturity-summary)` immediately before this edit on a
      ;; freshly re-fetched origin/main (203, not assumed) after a first
      ;; landing attempt against a now-stale base hit a 409 because the
      ;; sibling cloud-itonami-isic-0893 promotion landed first --
      ;; confirmed by the test runner itself, `expected: (= 203
      ;; (:implemented m)) actual: (not (= 203 204))`, not by a static
      ;; grep.
      ;; 204 -> 205: cloud-itonami-isic-0220 (Logging) verified
      ;; from-scratch redo (LoggingAdvisor vs Logging Coordination
      ;; Governor) superseding a prior REVERTED attempt that shipped no
      ;; deps.edn and no registry.cljc/store.cljc, mirroring
      ;; cloud-itonami-isic-0210's [Silviculture] verified module shape
      ;; (logging.* in place of forestry.*, site/permit in place of
      ;; stand, plus a logging-specific independent permit-allowance
      ;; recompute for felling volume in place of stand-maturity); 70
      ;; tests / 175 assertions green, independently re-verified against
      ;; a fresh clone at merge commit
      ;; e39e6558713899ede4f1d41b97c60a236500921f; promoted
      ;; :spec -> :implemented, superproject ADR-2607153500.
      ;; Live-recomputed via `(industry/maturity-summary)` on a freshly
      ;; re-fetched origin/main immediately before this edit (concurrent-
      ;; agent count-drift caution -- not an assumed fixed number):
      ;; 204 -> 205.
      ;; 205 -> 206: cloud-itonami-isic-0114 (Growing of sugar cane) fresh
      ;; scaffold (no prior actor existed -- the entry pointed at a
      ;; never-created gftdcojp/cloud-itonami-A0114 placeholder).
      ;; CaneOpsAdvisor <-> CaneOperationsGovernor sugar-cane-plantation-
      ;; operations-coordination actor mirroring cloud-itonami-isic-0112's
      ;; [Growing of rice] verified module shape (caneops.* in place of
      ;; riceops.*), with a perennial-crop ratoon-cycle validity check
      ;; (ratoon-cycle-invalid, replacing 0112's paddy water-level-invalid)
      ;; and a :finalize-burn-decision hard block (pre-harvest cane-field
      ;; burning) added alongside the mirrored :operate-field-equipment /
      ;; :finalize-pesticide-application blocks; 35 tests / 107 assertions
      ;; green, independently re-verified against a fresh clone; promoted
      ;; :spec -> :implemented, superproject ADR-2607154500
      ;; (cloud-itonami-isic-0114-sugar-cane-growing-coverage.md).
      ;; Live-recomputed via `(industry/maturity-summary)` immediately
      ;; before this edit on a freshly re-fetched origin/main (concurrent-
      ;; agent count-drift caution -- confirmed by the test runner itself,
      ;; `expected: (= 205 (:implemented m)) actual: (not (= 205 206))`,
      ;; not by a static grep): 205 -> 206.
      ;; 206 -> 207: cloud-itonami-isic-3700 (Sewerage) fresh scaffold (the
      ;; entry pointed at a never-created gftdcojp/cloud-itonami-E3700
      ;; placeholder, :required-technologies wrongly carried :robotics/
      ;; :telemetry for what is a non-robotics back-office coordination
      ;; actor). SewerOpsAdvisor <-> SewerageOpsGovernor municipal-
      ;; sewerage-operations-coordination actor mirroring
      ;; cloud-itonami-isic-0510's [Mining of hard coal] verified module
      ;; shape (sewerops.* in place of coalops.*, facility in place of
      ;; site), plus a genuinely new supply-cost-threshold escalate gate
      ;; on `:order-supplies` (a high-value procurement proposal always
      ;; escalates for human budget sign-off, independent of rollout
      ;; phase or confidence) alongside the mirrored pump/valve-control
      ;; and public-health-authority-discharge-decision scope-exclusion
      ;; blocks; 48 tests / 147 assertions green, independently
      ;; re-verified against a fresh clone; promoted :spec -> :implemented,
      ;; superproject ADR-2607153900
      ;; (cloud-itonami-isic-3700-sewerage-coverage.md).
      ;; Live-recomputed via `(industry/maturity-summary)` immediately
      ;; before this edit on a freshly re-fetched origin/main
      ;; (concurrent-agent count-drift caution -- not an assumed fixed
      ;; number): 206 -> 207.
      ;; 207 -> 208: cloud-itonami-isic-1610 (Sawmilling and planing of
      ;; wood) fresh from-scratch scaffold (no prior repo or reverted
      ;; attempt existed; the entry's old :repo/:business-id pointed at a
      ;; never-created gftdcojp/cloud-itonami-C1610 placeholder).
      ;; SawmillAdvisor <-> Sawmill Plant Operations Governor sawmill-
      ;; plant-operations-coordination actor mirroring
      ;; cloud-itonami-isic-0220's [Logging] verified module shape
      ;; (sawmilling.* in place of logging.*), with a genuinely new
      ;; two-entity-kind independent verification gate (equipment for
      ;; `:schedule-maintenance`, batch for `:coordinate-shipment` --
      ;; 0220 has only one, site/permit) plus a kiln-schedule-finalize
      ;; permanent block (in place of harvest-cut-plan-finalize) and a
      ;; moisture-content-plausibility check (in place of species
      ;; validation); 71 tests / 197 assertions green, independently
      ;; re-verified against a fresh clone at merge commit
      ;; e528229eb7f842b363822b39a7919ccda7d1f639; promoted
      ;; :spec -> :implemented, superproject ADR-2607154200
      ;; (cloud-itonami-isic-1610-sawmilling-coverage.md).
      ;; Live-recomputed via `(industry/maturity-summary)` immediately
      ;; before this edit on a freshly re-fetched origin/main
      ;; (concurrent-agent count-drift caution -- not an assumed fixed
      ;; number): 207 -> 208.
      ;; 208 -> 209: cloud-itonami-isic-0145 (Raising of swine/pigs)
      ;; promoted :spec -> :implemented (see `maturity-tier`'s own
      ;; comment above for the 0144/0145 code-correction note --
      ;; the task assignment mislabeled this class as 0144, which this
      ;; registry, matching the real UN ISIC Rev.4 standard, correctly
      ;; assigns to "Raising of sheep and goats"; that entry is
      ;; untouched); superproject ADR-2607154000. Live-recomputed via
      ;; `(industry/maturity-summary)` on a freshly re-fetched origin/main
      ;; immediately before this edit (concurrent-agent count-drift
      ;; caution -- not an assumed fixed number): 208 -> 209.
      ;; 209 -> 210: cloud-itonami-isic-1072 (Manufacture of sugar) fresh
      ;; from-scratch scaffold (no prior repo existed; the entry's old
      ;; :repo/:business-id pointed at a never-created
      ;; gftdcojp/cloud-itonami-C1072 placeholder). SugarOpsAdvisor <->
      ;; Sugar Governor sugar-manufacturing plant-operations-coordination
      ;; actor mirroring cloud-itonami-isic-1061's [Grain mill products]
      ;; verified module shape (sugarops.* in place of millops.*), with
      ;; polarization-below-minimum/color-exceeds-max/
      ;; ash-content-exceeds-max/so2-residue-exceeded independently
      ;; verified physical checks in place of mycotoxin/ash-range, a
      ;; sulfite-label-mismatch check driven by an SO2-ppm declaration
      ;; threshold (in place of a grain-source allergen table), and a
      ;; batch-not-registered invariant generalized to every allowed op
      ;; (not only :coordinate-shipment as in millops); 55 tests / 179
      ;; assertions green, independently re-verified against a fresh
      ;; clone; promoted :spec -> :implemented, superproject
      ;; ADR-2607154600
      ;; (cloud-itonami-isic-1072-sugar-manufacturing-coverage.md).
      ;; Live-recomputed via `(industry/maturity-summary)` immediately
      ;; before this edit on a freshly re-fetched origin/main
      ;; (concurrent-agent count-drift caution -- not an assumed fixed
      ;; number): 209 -> 210.
      ;; 210 -> 211: cloud-itonami-isic-4311 (Demolition) promoted
      ;; :spec -> :implemented, superproject ADR-2607155000
      ;; (cloud-itonami-isic-4311-demolition-coverage.md). 68 tests /
      ;; 252 assertions run green before claiming :implemented,
      ;; independently re-verified against a fresh clone. Live-
      ;; recomputed via `(industry/maturity-summary)` on a freshly
      ;; re-fetched origin/main immediately before this edit -- not an
      ;; assumed fixed number.
      ;; 211 -> 213: cloud-itonami-isic-2630 (communication equipment /
      ;; smartphones, Radio-Compliance Governor) and cloud-itonami-isic-2920
      ;; (motor-vehicle bodies/coachwork, Stamping Governor) promoted
      ;; :spec -> :implemented, superproject ADR-2607160100/ADR-2607160200 --
      ;; both native real physics-2d time-stepped simulations from day one.
      ;; 213 -> 214: cloud-itonami-isic-0122 (Growing of tropical and
      ;; subtropical fruits) promoted :spec -> :implemented, superproject
      ;; ADR-2607152500 (cloud-itonami-isic-0122-tropical-fruit-growing-
      ;; coverage.md). OrchardOpsAdvisor <-> OrchardOperationsGovernor
      ;; orchard-operations-coordination actor mirroring
      ;; cloud-itonami-isic-0121's [Growing of grapes] verified module
      ;; shape (orchardops.* in place of vineyardops.*), covering
      ;; mango/banana/papaya/avocado/pineapple orchards; 30 tests / 93
      ;; assertions green, independently re-verified against a fresh
      ;; clone. Fresh from-scratch scaffold (no prior repo existed; the
      ;; entry's old :repo/:business-id pointed at a never-created
      ;; gftdcojp/cloud-itonami-A0122 placeholder). Live-recomputed via
      ;; `(industry/maturity-summary)` on a freshly re-fetched origin/main
      ;; immediately before this edit (concurrent-agent count-drift
      ;; caution -- not an assumed fixed number): 213 -> 214.
      ;; 214 -> 215: cloud-itonami-isic-1074 (Manufacture of macaroni,
      ;; noodles, couscous and similar farinaceous products) promoted
      ;; :spec -> :implemented, superproject ADR-2607161500
      ;; (cloud-itonami-isic-1074-macaroni-noodles-coverage.md).
      ;; PastaOpsAdvisor <-> Governor plant-operations-coordination actor
      ;; mirroring cloud-itonami-isic-1071's [Bakery products] verified
      ;; module shape (pastaops.* in place of bakeryops.*), covering
      ;; macaroni/spaghetti/egg-noodle/couscous extrusion-drying batches;
      ;; also fixes that reference's latent JVM-only `.indexOf` interop
      ;; bug in phase.cljc with a portable keep-indexed-based helper.
      ;; 41 tests / 135 assertions green, independently re-verified
      ;; against a fresh clone. Fresh from-scratch scaffold (no prior
      ;; repo existed; the entry's old :repo/:business-id pointed at a
      ;; never-created gftdcojp/cloud-itonami-C1074 placeholder).
      ;; Live-recomputed via a fresh `clojure -M:test` run against a
      ;; freshly re-fetched origin/main immediately before this edit
      ;; (concurrent-agent count-drift caution -- the pre-edit run
      ;; against this same fresh clone showed 215, i.e. another
      ;; concurrent promotion had already landed between this ADR's
      ;; earlier baseline read of 213 and this edit -- not an assumed
      ;; fixed number): 214 -> 215.
      ;; 215 -> 216: cloud-itonami-isic-0146 (Raising of
      ;; poultry) promoted :spec -> :implemented, superproject
      ;; ADR-2607160300
      ;; (cloud-itonami-isic-0146-poultry-raising-coverage.md).
      ;; PoultryOpsAdvisor <-| PoultryFarmOperationsGovernor,
      ;; poultry-farm (broiler/layer) back-office coordination actor
      ;; mirroring cloud-itonami-isic-0145's (swine-raising) module
      ;; shape module-for-module. 32 tests / 103 assertions run green
      ;; before claiming :implemented, independently re-verified
      ;; against a fresh clone. Live-recomputed via
      ;; `(industry/maturity-summary)` on a freshly re-fetched
      ;; origin/main immediately before this edit -- not an assumed
      ;; fixed number.
      ;; 216 -> 217: cloud-itonami-isic-0116 (Growing of fibre crops) fresh
      ;; from-scratch scaffold (no prior repo existed; `gh api` 404
      ;; confirmed before work began). FibreOpsAdvisor <-> Field
      ;; Operations Governor field-operations-coordination actor
      ;; mirroring cloud-itonami-isic-0111's [Growing of cereals] module
      ;; shape (fibreops.* in place of cerealops.*), with a new
      ;; domain-specific quality-grade-invalid HARD check (closed
      ;; fibre-quality-grade vocabulary, adapted from 0114/caneops's
      ;; ratoon-cycle-invalid? pattern) in place of a perennial-cycle
      ;; check; 35 tests / 120 assertions green, independently
      ;; re-verified against a fresh clone; promoted :spec ->
      ;; :implemented, superproject ADR-2607160400 (renumbered from
      ;; 2607160300 after a same-day slot collision with
      ;; cloud-itonami-isic-0146's own ADR-2607160300, above)
      ;; (cloud-itonami-isic-0116-fibre-crops-growing-coverage.md).
      ;; Live-recomputed via `(industry/maturity-summary)` immediately
      ;; before this edit on a freshly re-fetched origin/main
      ;; (concurrent-agent count-drift caution -- not an assumed fixed
      ;; number): 216 -> 217.
      ;; 217 -> 218: cloud-itonami-isic-1701 (Manufacture of pulp, paper
      ;; and paperboard) promoted :spec -> :implemented, superproject
      ;; ADR-2607161600 (cloud-itonami-isic-1701-pulp-paper-coverage.md).
      ;; PulpPaperAdvisor <-> Pulp & Paper Plant Operations Governor
      ;; plant-operations-coordination actor mirroring
      ;; cloud-itonami-isic-1610's [Sawmilling and planing of wood]
      ;; verified module shape (pulppaper.* in place of sawmilling.*),
      ;; covering chemical/mechanical pulping digesters, paper machines
      ;; and effluent-treatment plant; discharge-authorize-blocked-
      ;; violations is a genuinely new domain-specific PERMANENT-block
      ;; check for this fleet (effluent-discharge authorization, distinct
      ;; from 1610's kiln-schedule-finalize block on the same structural
      ;; axis). 71 tests / 200 assertions green, independently
      ;; re-verified against a fresh clone. Fresh from-scratch scaffold
      ;; (no prior repo existed; the entry's old :repo/:business-id
      ;; pointed at a never-created gftdcojp/cloud-itonami-C1701
      ;; placeholder). Live-recomputed via a fresh `clojure -M:test` run
      ;; against a freshly re-fetched origin/main immediately before this
      ;; edit (concurrent-agent count-drift caution -- not an assumed
      ;; fixed number): 217 -> 218.
      ;; 218 -> 219: cloud-itonami-isic-4210 (Construction of roads and
      ;; railways) promoted :spec -> :implemented, superproject
      ;; ADR-2607161700 (cloud-itonami-isic-4210-roads-railways-
      ;; construction-coverage.md). Live-recomputed via
      ;; `(industry/maturity-summary)` immediately before this edit on a
      ;; freshly re-fetched origin/main (concurrent-agent count-drift
      ;; caution -- not an assumed fixed number).
      ;; 219 -> 220: cloud-itonami-isic-0127 (Growing of beverage crops)
      ;; fresh from-scratch scaffold (no prior repo existed; `gh api` 404
      ;; confirmed before work began). BeverageOpsAdvisor <-> Beverage
      ;; Operations Governor plantation-operations-coordination actor
      ;; mirroring cloud-itonami-isic-0122's [Growing of tropical and
      ;; subtropical fruits] verified module shape (beverageops.* in place
      ;; of orchardops.*), covering coffee/tea/cacao/yerba-mate
      ;; plantations; 30 tests / 92 assertions green, independently
      ;; re-verified against a fresh clone; promoted :spec ->
      ;; :implemented, superproject ADR-2607170000
      ;; (cloud-itonami-isic-0127-beverage-crops-growing-coverage.md).
      ;; Live-recomputed via `(industry/maturity-summary)` immediately
      ;; before this edit on a freshly re-fetched origin/main
      ;; (concurrent-agent count-drift caution -- not an assumed fixed
      ;; number): 219 -> 220.
      ;; 220 -> 221: cloud-itonami-isic-0164 (Seed processing for
      ;; propagation) fresh from-scratch scaffold (the entry's old
      ;; :repo/:business-id pointed at a never-created
      ;; gftdcojp/cloud-itonami-A0164 placeholder; identity ({:id "0164"
      ;; :name "Seed processing for propagation"}) independently verified
      ;; against a fresh clone before any work began). SeedOpsAdvisor ⊣
      ;; Seed Processing Governor facility-operations-coordination actor
      ;; mirroring cloud-itonami-isic-1061's [Manufacture of grain mill
      ;; products] verified module shape (seedops.* in place of
      ;; millops.*), covering seed-lot cleaning/grading/germination-
      ;; testing/certification-support facilities for propagation seed
      ;; (not on-farm growing, not consumption processing);
      ;; germination-rate-below-minimum-violations and
      ;; purity-below-minimum-violations are the seed-viability/varietal-
      ;; purity analogs of 1061's mycotoxin-level-exceeded/ash-content
      ;; checks; 49 tests / 156 assertions green, independently
      ;; re-verified against a fresh clone; promoted :spec ->
      ;; :implemented, superproject ADR-2607152500
      ;; (cloud-itonami-isic-0164-seed-processing-coverage.md).
      ;; Live-recomputed via `(industry/maturity-summary)` immediately
      ;; before this edit on a freshly re-fetched origin/main
      ;; (concurrent-agent count-drift caution -- not an assumed fixed
      ;; number): 220 -> 221.
      ;; 221 -> 222: cloud-itonami-isic-1520 (Manufacture of footwear)
      ;; promoted :spec -> :implemented, superproject ADR-2607162200
      ;; (cloud-itonami-isic-1520-footwear-manufacturing-coverage.md).
      ;; FootwearAdvisor ⊣ Footwear Plant Operations Governor,
      ;; fresh from-scratch scaffold (no prior repo existed; the old
      ;; :repo/:business-id pointed at a never-created
      ;; gftdcojp/cloud-itonami-C1520 placeholder), mirroring
      ;; cloud-itonami-isic-1610's [Sawmilling and planing of wood]
      ;; verified module shape. 71 tests / 194 assertions green,
      ;; independently re-verified against a fresh clone.
      ;; Live-recomputed via `(industry/maturity-summary)` immediately
      ;; before this edit on a freshly re-fetched origin/main
      ;; (concurrent-agent count-drift caution -- not an assumed fixed
      ;; number): 221 -> 222.
      ;; 222 -> 224: cloud-itonami-isic-2220 (Manufacture of plastics
      ;; products) fresh from-scratch scaffold (no prior repo or reverted
      ;; attempt existed at cloud-itonami/cloud-itonami-isic-2220; `gh
      ;; api` 404 confirmed before work began; the entry's old
      ;; :repo/:business-id pointed at a never-created
      ;; gftdcojp/cloud-itonami-C2220 placeholder). PlasticsAdvisor <->
      ;; Plastics Plant Operations Governor plant-operations-coordination
      ;; actor mirroring cloud-itonami-isic-1610's [Sawmilling and
      ;; planing of wood] verified module shape (plasticsmfg.* in place
      ;; of sawmilling.*), covering injection-molding/extrusion/
      ;; blow-molding plant operations; 71 tests / 198 assertions green,
      ;; independently re-verified against a fresh clone; promoted :spec
      ;; -> :implemented, superproject ADR-2607162200
      ;; (cloud-itonami-isic-2220-plastics-products-coverage.md). This
      ;; single promotion only accounts for +1 of the +2 delta below --
      ;; the freshly re-fetched origin/main at edit time (commit
      ;; d51c1a5f76e6e1cd33aeabc70f3b30f90c0c3a91) already carried
      ;; cloud-itonami-isic-3100 (Manufacture of furniture) promoted to
      ;; :implemented in registry.edn WITHOUT this assertion having been
      ;; bumped for it (that commit only touched registry.edn); this
      ;; edit both accounts for that untracked drift and adds 2220's own
      ;; promotion. Live-recomputed via `(industry/maturity-summary)`
      ;; immediately before this edit on that freshly re-fetched
      ;; origin/main (concurrent-agent count-drift caution -- not an
      ;; assumed fixed number): 222 -> 224.
      ;; NOTE (fixup, 2026-07-15): a since-superseded commit here briefly
      ;; asserted 223 (dropping a closing paren, which broke `clojure
      ;; -M:test` for everyone with a read syntax error, and the 224 ->
      ;; 223 count itself was also wrong -- likely computed via a raw
      ;; grep of `:maturity :implemented` occurrences in registry.edn
      ;; rather than the live `(industry/maturity-summary)` value, which
      ;; differs from the raw grep count by +1 because of registry
      ;; entries with no explicit `:maturity` key that
      ;; `kotoba.industry/maturity-of` resolves via its `:implemented?`/
      ;; `:repo` fallback (see that fn's docstring). Restored to the
      ;; correct 224, re-verified live against this exact registry.edn
      ;; via a fresh `clojure -M:test` run (see this repo's commit
      ;; history around 2026-07-15 for the isic-3100/isic-2220
      ;; promotions this count reflects).
            ;; CORRECTIVE FIX: a prior automated edit here mistakenly matched
      ;; the loose substring "cloud-itonami-isic-3100" inside a DIFFERENT
      ;; concurrent agent's own prose (cloud-itonami-isic-2220/plastics'
      ;; own count-drift note, which correctly accounted for BOTH 2220's
      ;; own promotion and 3100's untracked registry.edn promotion at
      ;; 222 -> 224), treated that as "3100's own testing block already
      ;; present", skipped adding the actual `(industry/maturity "3100")`
      ;; assertion, and wrote a regressed count (224 -> 223) that silently
      ;; undid 2220's own correct accounting -- caught and fixed here in
      ;; the same session before any other agent could build on the wrong
      ;; value. Live-recomputed via `(industry/maturity-summary)` fresh at
      ;; fix time (concurrent-agent count-drift caution -- not an assumed
      ;; fixed number): 224 -> 224.
      ;; 224 -> 225: cloud-itonami-isic-1102 (Manufacture of wines)
      ;; promoted :spec -> :implemented, superproject ADR-2607171000
      ;; (cloud-itonami-isic-1102-wine-manufacturing-coverage.md).
      ;; WineOpsAdvisor ⊣ Wine Governor, fresh from-scratch scaffold (no
      ;; prior repo existed; the old :repo/:business-id pointed at a
      ;; never-created gftdcojp/cloud-itonami-C1102 placeholder),
      ;; mirroring cloud-itonami-isic-1072's [Manufacture of sugar]
      ;; verified module shape. 54 tests / 180 assertions green,
      ;; independently re-verified against a fresh clone.
      ;; Live-recomputed via `(industry/maturity-summary)` immediately
      ;; before this edit on a freshly re-fetched origin/main
      ;; (concurrent-agent count-drift caution -- not an assumed fixed
      ;; number): 224 -> 225.
      ;; 225 -> 226: cloud-itonami-isic-0142 (Raising of horses and other
      ;; equines) promoted :spec -> :implemented, superproject
      ;; ADR-2607171200
      ;; (cloud-itonami-isic-0142-equine-raising-coverage.md).
      ;; EquineOpsAdvisor ⊣ Equine Facility Operations Governor, fresh
      ;; from-scratch scaffold (no prior repo existed; the old
      ;; :repo/:business-id pointed at a never-created
      ;; gftdcojp/cloud-itonami-A0142 placeholder), mirroring
      ;; cloud-itonami-isic-0141's [Raising of cattle and buffaloes]
      ;; verified module shape (equineops.* in place of cattleops.*,
      ;; direct treatment administration and breeding/culling decisions
      ;; permanently blocked in place of treatment/slaughter). 31 tests /
      ;; 96 assertions green, independently re-verified against a fresh
      ;; clone. Live-recomputed via `(industry/maturity-summary)`
      ;; immediately before this edit on a freshly re-fetched origin/main
      ;; (concurrent-agent count-drift caution -- not an assumed fixed
      ;; number, confirmed via the test runner's own failure diff: 225 ->
      ;; 226).
      ;; 226 -> 227: cloud-itonami-isic-1511 (Tanning and dressing of
      ;; leather) promoted :spec -> :implemented, superproject
      ;; ADR-2607151105
      ;; (cloud-itonami-isic-1511-leather-tanning-coverage.md).
      ;; LeatherTanningAdvisor ⊣ Leather Tanning Plant Operations
      ;; Governor, fresh from-scratch scaffold (no prior repo existed;
      ;; the old :repo/:business-id pointed at a never-created
      ;; gftdcojp/cloud-itonami-C1511 placeholder), mirroring
      ;; cloud-itonami-isic-1701's [Manufacture of pulp, paper and
      ;; paperboard] verified module shape (leathertanning.* in place
      ;; of pulppaper.*). 71 tests / 200 assertions green, independently
      ;; re-verified against a fresh clone. Live-recomputed via
      ;; `(industry/maturity-summary)` immediately before this edit on
      ;; a freshly re-fetched origin/main (concurrent-agent count-drift
      ;; caution -- not an assumed fixed number, confirmed via the test
      ;; runner's own failure diff: 226 -> 227).
            ;; 227 -> 228: cloud-itonami-isic-0123 (Growing of citrus fruits) promoted :spec -> :implemented, superproject
      ;; ADR-2607172000 (cloud-itonami-isic-0123-citrus-fruit-growing-coverage.md).
      ;; CitrusOpsAdvisor ⊣ CitrusOperationsGovernor, fresh from-scratch scaffold (no prior repo existed; the old
      ;; :repo/:business-id pointed at a never-created gftdcojp/cloud-itonami-A0123 placeholder), mirroring
      ;; cloud-itonami-isic-0122's [Growing of tropical and subtropical fruits] verified module shape
      ;; module-for-module. 30 tests / 92 assertions green, independently re-verified against a fresh clone.
      ;; Live-recomputed via `(industry/maturity-summary)` immediately before this edit on a freshly re-fetched
      ;; origin/main (concurrent-agent count-drift caution -- not an assumed fixed number): 227 -> 228.
      ;; 228 -> 229: cloud-itonami-isic-2013 (Manufacture of plastics and
      ;; synthetic rubber in primary forms) promoted :spec ->
      ;; :implemented, superproject ADR-2607171200
      ;; (cloud-itonami-isic-2013-plastics-primary-forms-coverage.md).
      ;; ResinAdvisor advisor-governor pair, fresh from-scratch scaffold
      ;; (no prior repo existed; the old :repo/:business-id pointed at a
      ;; never-created gftdcojp/cloud-itonami-C2013 placeholder),
      ;; mirroring cloud-itonami-isic-2220's verified module shape,
      ;; adapted to the UPSTREAM primary-forms chemical-process plant.
      ;; 71 tests / 204 assertions green, independently re-verified
      ;; against a fresh clone. Live-recomputed via
      ;; `(industry/maturity-summary)` immediately before this edit on a
      ;; freshly re-fetched origin/main (concurrent-agent count-drift
      ;; caution -- not an assumed fixed number): 228 -> 229.
      ;; 229 -> 230: cloud-itonami-isic-1101 (Distilling, rectifying and blending of spirits) re-promoted
      ;; :spec -> :implemented after a root-cause fix of the 2026-07-14 revert's 4 real test failures (a
      ;; contains?-on-vector test bug, a nil-vs-false :record bug, and a self-contradictory proof/declared-ABV
      ;; test fixture -- not a defect in the tolerance-check logic itself) plus closing a Governor-invariant gap
      ;; (closed op-allowlist, :effect :propose, batch-registration) found on honest re-review against
      ;; cloud-itonami-isic-1102, superproject ADR-2607171500 (cloud-itonami-isic-1101-spirits-distilling-coverage.md).
      ;; 30 tests / 111 assertions green, independently re-verified against a fresh clone. Live-recomputed via
      ;; `(industry/maturity-summary)` immediately before this edit on a freshly re-fetched origin/main
      ;; (concurrent-agent count-drift caution -- not an assumed fixed number; also confirms the 1812
      ;; `:implemented?` special-case entry `maturity-of` already accounts for): 229 -> 230.
      ;; 230 -> 231: cloud-itonami-isic-3320 (Installation of industrial machinery and equipment) promoted
      ;; :spec -> :implemented, superproject ADR-2607171900
      ;; (cloud-itonami-isic-3320-industrial-machinery-installation-coverage.md). Live-recomputed via
      ;; `(industry/maturity-summary)` immediately before this edit on a freshly re-fetched origin/main
      ;; (concurrent-agent count-drift caution -- not an assumed fixed number): 230 -> 231.
      ;; 231 -> 232: cloud-itonami-isic-0119 (Growing of other non-perennial crops) promoted
      ;; :spec -> :implemented, superproject ADR-2607172100
      ;; (cloud-itonami-isic-0119-other-non-perennial-crops-coverage.md). Live-recomputed via
      ;; `(industry/maturity-summary)` immediately before this edit on a freshly re-fetched origin/main
      ;; (confirmed HEAD's own pinned 231 assertion was still green pre-edit, ruling out drift from a
      ;; raw `grep -c` undercount before trusting the delta): 231 -> 232.
      ;; 232 -> 233: cloud-itonami-isic-2431 (Casting of iron and steel) promoted
      ;; :spec -> :implemented, superproject ADR-2607152500
      ;; (cloud-itonami-isic-2431-iron-steel-casting-coverage.md). Live-recomputed via
      ;; `(industry/maturity-summary)` immediately before this edit on a freshly re-fetched origin/main
      ;; (confirmed HEAD's own pinned 232 assertion was still green pre-edit, ruling out drift from a
      ;; raw `grep -c` undercount before trusting the delta): 232 -> 233.
      ;; 233 -> 234: cloud-itonami-isic-0163 (Post-harvest crop activities) promoted :spec -> :implemented
      ;; (fresh scaffold -- the prior :repo pointed at a never-created gftdcojp/cloud-itonami-A0163
      ;; placeholder, confirmed 404 via gh api before any work began), superproject ADR-2607172200
      ;; (cloud-itonami-isic-0163-post-harvest-crop-activities-coverage.md). Live-recomputed via
      ;; `(industry/maturity-summary)` immediately before this edit on a freshly re-fetched origin/main
      ;; (confirmed HEAD's own pinned 233 assertion was still green pre-edit, ruling out drift from a
      ;; raw `grep -c` undercount before trusting the delta): 233 -> 234.
      ;; 234 -> 235: cloud-itonami-isic-1062 (Manufacture of starches and starch products) promoted
      ;; :spec -> :implemented, superproject ADR-2607152500
      ;; (cloud-itonami-isic-1062-starches-coverage.md). Live-recomputed via
      ;; `(industry/maturity-summary)` immediately before this edit on a freshly re-fetched origin/main
      ;; (confirmed HEAD's own pinned 234 assertion was still green pre-edit, ruling out drift from a
      ;; raw `grep -c` undercount before trusting the delta): 234 -> 235.
            ;; 235 -> 236: cloud-itonami-isic-1621 (Manufacture of veneer sheets and wood-based panels)
      ;; promoted :spec -> :implemented, superproject ADR-2607181000
      ;; (cloud-itonami-isic-1621-veneer-wood-panels-coverage.md). VeneerPanelAdvisor ⊣ Wood Panel Plant
      ;; Operations Governor, fresh from-scratch scaffold (no prior repo existed; the old
      ;; :repo/:business-id pointed at a never-created gftdcojp/cloud-itonami-C1621 placeholder), mirroring
      ;; cloud-itonami-isic-1610's [Sawmilling and planing of wood] verified module shape module-for-module.
      ;; 71 tests / 197 assertions green, independently re-verified against a fresh clone. Live-recomputed
      ;; via `(industry/maturity-summary)` immediately before this edit on a freshly re-fetched origin/main
      ;; (confirmed HEAD's own pinned 235 assertion was still green pre-edit, ruling out drift): 235 -> 236.
      ;; 236 -> 237: cloud-itonami-isic-2211 (Manufacture of rubber tyres and tubes) promoted
      ;; :spec -> :implemented, superproject ADR-2607151145
      ;; (cloud-itonami-isic-2211-rubber-tyres-coverage.md). Live-recomputed via
      ;; `(industry/maturity-summary)` immediately before this edit on a freshly re-fetched origin/main.
      ;; 237 -> 238: cloud-itonami-isic-0143 (Raising of camels and camelids) promoted
      ;; :spec -> :implemented, superproject ADR-2607181100
      ;; (cloud-itonami-isic-0143-camel-raising-coverage.md). Fresh scaffold -- no prior repo existed at
      ;; either the stale gftdcojp/cloud-itonami-A0143 placeholder or the real cloud-itonami org (gh api
      ;; 404 confirmed before any work began); identity ({:id "0143" :name "Raising of camels and
      ;; camelids"}) independently verified against a fresh clone before scaffolding. CamelOpsAdvisor ⊣
      ;; Camelid Facility Operations Governor camelid-facility-operations-coordination actor mirroring
      ;; cloud-itonami-isic-0141's [Raising of cattle and buffaloes] verified module shape
      ;; module-for-module -- dromedary/bactrian-camel/llama/alpaca species reference data in place of
      ;; cattle/buffalo, dairy/fiber/pack-animal use classes, and a fiber-yield-invalid check (negative
      ;; fiber yield rejected, zero valid) genuinely new to this fiber-producing vertical. 35 tests / 106
      ;; assertions green, independently re-verified against a fresh clone. Live-recomputed via
      ;; `(industry/maturity-summary)` immediately before this edit on a freshly re-fetched origin/main
      ;; (confirmed HEAD's own pinned 237 assertion was still green pre-edit, ruling out drift): 237 -> 238.
      ;; 238 -> 239: cloud-itonami-isic-0124 (Growing of pome fruits and stone fruits) promoted
      ;; :spec -> :implemented (fresh scaffold -- no prior repository at either the stale
      ;; gftdcojp/cloud-itonami-A0124 placeholder or the real cloud-itonami org [gh api 404
      ;; confirmed]), superproject ADR-2607152500
      ;; (cloud-itonami-isic-0124-pome-stone-fruit-growing-coverage.md). PomeStoneOpsAdvisor ⊣
      ;; PomeStoneOperationsGovernor pome/stone-fruit-orchard-operations-coordination actor,
      ;; mirroring cloud-itonami-isic-0123's [Growing of citrus fruits] verified module shape
      ;; module-for-module -- apple/pear/quince (pome) and peach/plum/cherry/apricot (stone)
      ;; fruit-class reference data in place of orange/lemon/lime/grapefruit; flag-crop-health-
      ;; concern (e.g. codling moth/fire blight) always escalates regardless of confidence;
      ;; field-equipment-or-spray-blocked permanently blocks :operate-field-equipment and
      ;; :finalize-spray-application; 31 tests / 105 assertions green, independently re-verified
      ;; against a fresh clone. Live-recomputed via `(industry/maturity-summary)` immediately
      ;; before this edit on a freshly re-fetched origin/main (confirmed HEAD's own pinned 238
      ;; assertion was still green pre-edit, ruling out drift from a raw `grep -c` undercount
      ;; before trusting the delta): 238 -> 239.
      ;; 239 -> 240: cloud-itonami-isic-1073 (Manufacture of cocoa, chocolate and sugar
      ;; confectionery) promoted :spec -> :implemented (fresh scaffold -- no prior repository
      ;; at either the stale gftdcojp/cloud-itonami-C1073 placeholder or the real cloud-itonami
      ;; org [gh api 404 confirmed]; identity ({:id "1073" :name "Manufacture of cocoa,
      ;; chocolate and sugar confectionery"}) independently verified against a fresh clone
      ;; before any work began), superproject ADR-2607181500
      ;; (cloud-itonami-isic-1073-cocoa-chocolate-confectionery-coverage.md). ChocOpsAdvisor
      ;; <-> chocops.governor cocoa/chocolate/sugar-confectionery-plant-operations-coordination
      ;; actor, mirroring cloud-itonami-isic-1072's [Manufacture of sugar] verified module
      ;; shape module-for-module -- cocoa-content compositional-grade/particle-size/tempering-
      ;; process-temperature/cadmium-residue/molding-line-viscosity product-quality fields in
      ;; place of polarization/color/ash-content/SO2-residue/granulation, and an
      ;; allergen-label-mismatch check (cross-contact-risk set not fully covered by
      ;; declared-allergens) in place of the threshold-based sulfite-label-mismatch check; 53
      ;; tests / 174 assertions green, independently re-verified against a fresh clone.
      ;; Live-recomputed via `(industry/maturity-summary)` immediately before this edit on a
      ;; freshly re-fetched origin/main (confirmed HEAD's own pinned 239 assertion was still
      ;; green pre-edit, ruling out drift from a raw `grep -c` undercount before trusting the
      ;; delta): 239 -> 240.
      ;; 240 -> 241: cloud-itonami-isic-2710 (Manufacture of electric motors, generators, transformers
      ;; and electricity distribution and control apparatus) promoted :spec -> :implemented, superproject
      ;; ADR-2607156500 (cloud-itonami-isic-2710-electric-motors-generators-transformers-coverage.md).
      ;; Fresh scaffold -- no prior repo existed (the old :repo/:business-id pointed at a never-created
      ;; gftdcojp/cloud-itonami-C2710 placeholder, confirmed 404 via gh api before any work began);
      ;; identity ({:id "2710" :name "Manufacture of electric motors, generators, transformers an..."})
      ;; independently verified against a fresh clone before scaffolding, per this fleet's ID/name-
      ;; mismatch caution. ElecEquipAdvisor (+/- an independent Electrical Equipment Plant Operations
      ;; Governor) plant-operations-coordination actor mirroring cloud-itonami-isic-2211's [Manufacture
      ;; of rubber tyres and tubes] verified module shape module-for-module -- winding/assembly/test-
      ;; bench-equipment registration in place of building/curing-line equipment, product-type/
      ;; dielectric-test-kv/quantity/defect-rate production-batch fields in place of tyre-category/load-
      ;; index/quantity/defect-rate, and an equipment-actuate permanent block
      ;; [elecequipmfg.governor's equipment-actuate-blocked-violations] in place of line-actuate-blocked,
      ;; plus a certification-authority permanent block (electrical-safety marks e.g. UL/CE/IEC in place
      ;; of DOT/ECE tyre-safety). 77 tests / 210 assertions green, independently re-verified against a
      ;; fresh clone. Live-recomputed via `(industry/maturity-summary)` immediately before this edit on a
      ;; freshly re-fetched origin/main (confirmed HEAD's own pinned 240 assertion was still green
      ;; pre-edit, ruling out drift): 240 -> 241.
      ;; 241 -> 242: cloud-itonami-isic-1622 (Manufacture of builders' carpentry and joinery)
      ;; promoted :spec -> :implemented (fresh scaffold -- no prior repository at either the
      ;; stale gftdcojp/cloud-itonami-C1622 placeholder or the real cloud-itonami org [gh api
      ;; 404 confirmed]), superproject ADR-2607181100
      ;; (cloud-itonami-isic-1622-builders-carpentry-joinery-coverage.md). MillworkAdvisor ⊣
      ;; Millwork Shop Plant Operations Governor millwork-shop-plant-operations-coordination
      ;; actor, mirroring cloud-itonami-isic-1621's [Manufacture of veneer sheets and
      ;; wood-based panels] verified module shape module-for-module -- panel-saw/CNC-router/
      ;; tenoning-machine/edge-bander/finishing-line equipment registration in place of
      ;; veneer-lathe/hot-press/glue-spreader, dimensional-spec/unit-count/output-quality
      ;; production-batch fields in place of grade/volume/output-quality, and a
      ;; cutting-line-finalize-blocked permanent block in place of press-finalize-blocked;
      ;; 71 tests / 195 assertions green, independently re-verified against a fresh clone.
      ;; Live-recomputed via `(industry/maturity-summary)` immediately before this edit on a
      ;; freshly re-fetched origin/main (concurrent-agent count-drift caution -- not an assumed
      ;; fixed number; confirmed HEAD's own pinned 241 assertion was still green pre-edit):
      ;; 241 -> 242.
            ;; 242 -> 243: cloud-itonami-isic-2023 (Manufacture of soap and detergents, cleaning and
      ;; polishing preparations, perfumes and toilet preparations) promoted :spec -> :implemented
      ;; (fresh scaffold -- no prior repository at either the stale gftdcojp/cloud-itonami-C2023
      ;; placeholder or the real cloud-itonami org [gh api 404 confirmed]), superproject
      ;; ADR-2607150319 (cloud-itonami-isic-2023-soap-detergents-cosmetics-coverage.md).
      ;; SoapAdvisor ⊣ Soap & Detergent Plant Operations Governor plant-operations-coordination
      ;; actor mirroring cloud-itonami-isic-2013's [Manufacture of plastics and synthetic rubber
      ;; in primary forms] verified module shape module-for-module -- saponification/mixing/
      ;; formulation-kettle and filling-line equipment registration in place of polymerization-
      ;; reactor/compounding-extruder equipment, product-type/weight/off-spec-rate production-
      ;; batch fields in place of polymer-grade/weight/off-spec-rate; fragrance-allergen-labeling-
      ;; incomplete-violations is a genuinely new independently-verified regulatory-disclosure
      ;; check for this vertical's own product mix (EU Regulation (EC) No 1223/2009 Annex III
      ;; designated-allergen labeling obligation, never taken on the advisor's self-report); 82
      ;; tests / 219 assertions green, independently re-verified against a fresh clone.
      ;; Live-recomputed via `(industry/maturity-summary)` immediately before this edit on a
      ;; freshly re-fetched origin/main (confirmed HEAD's own pinned 242 assertion was still
      ;; green pre-edit, ruling out drift from a raw `grep -c` undercount before trusting the
      ;; delta): 242 -> 243.
      ;; 243 -> 244: cloud-itonami-isic-0125 (Growing of other tree and bush fruits and nuts)
      ;; promoted :spec -> :implemented (fresh scaffold -- no prior repository at either the
      ;; stale gftdcojp/cloud-itonami-A0125 placeholder or the real cloud-itonami org [gh api
      ;; 404 confirmed]), superproject ADR-2607181600
      ;; (cloud-itonami-isic-0125-tree-bush-fruits-nuts-coverage.md). BerryNutOpsAdvisor ⊣
      ;; BerryNutOperationsGovernor orchard/grove-operations-coordination actor mirroring
      ;; cloud-itonami-isic-0124's [Growing of pome fruits and stone fruits] verified module
      ;; shape module-for-module (berrynutops.* in place of pomestoneops.*) -- bush-fruit
      ;; (blueberry/raspberry/blackcurrant) and tree-nut (almond/walnut/hazelnut/pecan)
      ;; fruit-class reference data in place of pome/stone; flag-crop-health-concern (e.g.
      ;; spotted wing drosophila/walnut blight) always escalates regardless of confidence,
      ;; matching 0124's own crop-health-escalation invariant; field-equipment-or-spray-
      ;; blocked permanently blocks :operate-field-equipment and
      ;; :finalize-spray-application; 31 tests / 105 assertions green, independently
      ;; re-verified against a fresh clone. Live-recomputed via
      ;; `(industry/maturity-summary)` immediately before this edit on a freshly re-fetched
      ;; origin/main (confirmed HEAD's own pinned 243 assertion was still green pre-edit,
      ;; ruling out drift before trusting the delta): 243 -> 244.
      ;; 244 -> 245: cloud-itonami-isic-0150 (Mixed farming) promoted :spec -> :implemented
      ;; (fresh scaffold -- no prior repository at either the stale gftdcojp/cloud-itonami-A0150
      ;; placeholder or the real cloud-itonami org [gh api 404 confirmed]), superproject
      ;; ADR-2607190500 (cloud-itonami-isic-0150-mixed-farming-coverage.md). MixedFarmAdvisor ⊣
      ;; MixedFarmingOperationsGovernor mixed-farming-operations-coordination actor, mirroring
      ;; cloud-itonami-isic-0141's [Raising of cattle and buffaloes] (cattleops.*) and
      ;; cloud-itonami-isic-0111's [Growing of cereals (except rice)] (cerealops.*) verified
      ;; module shapes, broadened to cover BOTH crop-field AND herd records in one operation
      ;; (mixedfarmops.* -- log-farm-record/schedule-farm-operation/flag-health-concern/
      ;; order-supplies, all :effect :propose) since ISIC 0150 is defined by neither activity
      ;; dominating the operation's gross margin. Direct field-equipment operation, direct
      ;; animal-handling-equipment operation, finalizing a pesticide-application decision, and
      ;; slaughter/culling decisions are all permanently blocked; a :log-farm-record's crop
      ;; yield and herd count are independently verified positive whenever either metric is
      ;; present, so a valid one never masks an invalid other. 39 tests / 120 assertions green,
      ;; independently re-verified against a fresh clone. Live-recomputed via
      ;; `(industry/maturity-summary)` immediately before this edit on a freshly re-fetched
      ;; origin/main (confirmed HEAD's own pinned 244 assertion was still green pre-edit,
      ;; ruling out drift from a raw `grep -c` undercount before trusting the delta; test
      ;; runner's own failure diff: `expected: (= 244 (:implemented m)) actual: (not (= 244
      ;; 245))`): 244 -> 245.
      ;; 245 -> 246: cloud-itonami-isic-1075 (Manufacture of prepared meals and dishes)
      ;; promoted :spec -> :implemented (fresh scaffold -- no prior repository at either the
      ;; stale gftdcojp/cloud-itonami-C1075 placeholder or the real cloud-itonami org [gh api
      ;; 404 confirmed]), superproject ADR-2607151900
      ;; (cloud-itonami-isic-1075-prepared-meals-coverage.md). MealOpsAdvisor ⊣ MealOps
      ;; Governor prepared-meals/ready-dish (cook-chill/cook-freeze) plant-operations-
      ;; coordination actor mirroring cloud-itonami-isic-1073's [Cocoa, chocolate and sugar
      ;; confectionery] verified module shape module-for-module (mealops.* in place of
      ;; chocops.*) -- HACCP critical-control-point reference data (core-cook-temperature
      ;; CCP1 lethality floor, chill/freeze-down-time CCP2 ceiling, cold-storage/cold-chain
      ;; window, shelf-life ceiling, water-activity ceiling, pH ceiling for reduced-oxygen-
      ;; packaged secondary botulinum control) in place of tempering-curve/cadmium/viscosity
      ;; confectionery data, plus a new packaging-seal-compromised independent check not
      ;; present in the 1073 reference (vacuum/MAP seal integrity is itself a distinct
      ;; food-safety hazard axis for cook-chill product); 56 tests / 179 assertions green,
      ;; independently re-verified against a fresh clone. Live-recomputed via
      ;; `(industry/maturity-summary)` immediately before this edit on a freshly re-fetched
      ;; origin/main (confirmed HEAD's own pinned 245 assertion was still green pre-edit,
      ;; ruling out drift before trusting the delta; test runner's own failure diff:
      ;; `expected: (= 245 (:implemented m)) actual: (not (= 245 246))`): 245 -> 246.
            ;; 246 -> 247: cloud-itonami-isic-3250 (Manufacture of medical and dental
      ;; instruments and supplies) promoted :spec -> :implemented (fresh scaffold -- no prior
      ;; repository at either the stale gftdcojp/cloud-itonami-C3250 placeholder or the real
      ;; cloud-itonami org [gh api 404 confirmed for both]), superproject ADR-2607151300
      ;; (cloud-itonami-isic-3250-medical-dental-instruments-coverage.md). MedInstrAdvisor ⊣
      ;; Medical Instrument Plant Operations Governor plant-operations-coordination actor
      ;; mirroring cloud-itonami-isic-2211's [Manufacture of rubber tyres and tubes] verified
      ;; module shape module-for-module (medinstrmfg.* in place of tyremfg.*) -- precision
      ;; machining/molding/sterilization-validation equipment registration in place of
      ;; building/curing-line equipment, device-class/sterility-assurance-level/
      ;; nonconformance-rate production-batch fields in place of tyre-category/load-index/
      ;; defect-rate, and a permanent equipment-actuation block
      ;; (medinstrmfg.governor/actuate-equipment-blocked-violations) plus a permanent
      ;; regulatory-clearance-authority block
      ;; (medinstrmfg.governor/clearance-authority-blocked-violations, FDA 510(k)/CE-mark, in
      ;; place of 2211's DOT/ECE tyre-safety-certification block); flag-safety-concern always
      ;; escalates regardless of confidence, matching 2211's own safety-escalation invariant.
      ;; 77 tests / 209 assertions green, independently re-verified against a fresh clone.
      ;; Live-recomputed via `(industry/maturity-summary)` immediately before this edit on a
      ;; freshly re-fetched origin/main (confirmed HEAD's own pinned 246 assertion was still
      ;; green pre-edit, ruling out drift from a raw `grep -c` undercount before trusting the
      ;; delta; test runner's own failure diff: `expected: (= 246 (:implemented m)) actual:
      ;; (not (= 246 247))`): 246 -> 247.
      ;; 247 -> 248: cloud-itonami-isic-1623 (Manufacture of wooden containers) promoted
      ;; :spec -> :implemented (fresh scaffold -- no prior repository at either the stale
      ;; gftdcojp/cloud-itonami-C1623 placeholder or the real cloud-itonami org [gh api 404
      ;; confirmed]), superproject ADR-2607190000
      ;; (cloud-itonami-isic-1623-wooden-containers-coverage.md). WoodenContainerAdvisor ⊣
      ;; Wooden Container Shop Plant Operations Governor plant-operations-coordination actor
      ;; mirroring cloud-itonami-isic-1621's [Manufacture of veneer sheets and wood-based
      ;; panels] and cloud-itonami-isic-1622's [Manufacture of builders' carpentry and
      ;; joinery] verified module shape module-for-module (woodcontainer.* in place of
      ;; veneerpanel.*/millwork.*) -- crate/pallet-nailing-machine and stave-jointer
      ;; (cooperage) equipment in place of veneer-lathe/hot-press/glue-spreader or
      ;; panel-saw/CNC-router/tenoning-machine equipment, crate/pallet/barrel
      ;; dimensional-spec values in place of door/window/staircase/glulam, and a
      ;; cutting/assembly-line-finalize permanent block in place of press-cycle-finalize/
      ;; cutting-line-finalize; flag-safety-concern covers materials-safety/equipment-safety/
      ;; ISPM-15 heat-treatment-compliance (a domain-specific addition for
      ;; internationally-shipped wood packaging) and always escalates regardless of
      ;; confidence, matching every prior sibling actor's own safety-concern-escalation
      ;; invariant; 71 tests / 195 assertions green, independently re-verified against a
      ;; fresh clone. Live-recomputed via `(industry/maturity-summary)` on a freshly
      ;; re-fetched origin/main immediately before this single-file edit (registry.edn's
      ;; own promotion landed separately via a Contents-API single-file PUT, commit
      ;; 7e673e0069351c2903da224ed82b5b65e9761fe3, ahead of this test-file edit): 247 -> 248.
      ;; 248 -> 249: cloud-itonami-isic-2392 (Manufacture of clay building materials) promoted
      ;; :spec -> :implemented (fresh scaffold -- no prior repository at either the stale
      ;; gftdcojp/cloud-itonami-C2392 placeholder or the real cloud-itonami org [gh api 404
      ;; confirmed]), superproject ADR-2607181600
      ;; (cloud-itonami-isic-2392-clay-building-materials-coverage.md). ClayAdvisor ⊣ Clay
      ;; Plant Operations Governor plant-operations-coordination actor for clay building
      ;; material (brick/roof-tile) plants, mirroring cloud-itonami-isic-2431's [Casting of
      ;; iron and steel] verified module shape module-for-module (claymfg.* in place of
      ;; foundrymfg.*) -- extrusion-press/kiln-line equipment and brick/tile production-batch
      ;; (product-type/dimensional-deviation/defect-rate) vocabulary in place of melting-
      ;; furnace/pouring-line and alloy-grade/defect-rate; :flag-safety-concern (kiln-fire/
      ;; thermal-hazard, clay/silica-dust-hazard) always escalates regardless of confidence,
      ;; matching 2431's own safety-concern-escalation invariant; the proposal-effect
      ;; allowlist plus a permanent kiln-line-actuate block (`:actuate-kiln-line? true`)
      ;; structurally prevent any direct extrusion-press/kiln-line-equipment control, with no
      ;; human-approval override path. 76 tests / 209 assertions green, independently
      ;; re-verified against a fresh clone. Live-recomputed via `(industry/maturity-summary)`
      ;; immediately before this edit on a freshly re-fetched origin/main (concurrent-agent
      ;; count-drift caution -- not an assumed fixed number; test runner's own failure diff:
      ;; `expected: (= 248 (:implemented m)) actual: (not (= 248 249))`): 248 -> 249.
      ;; cloud-itonami-isic-0126 (Growing of oleaginous fruits) promoted :spec ->
      ;; :implemented (OleaginousOpsAdvisor ⊣ OleaginousOperationsGovernor,
      ;; superproject ADR-2607151330). Live-recomputed via
      ;; `(industry/maturity-summary)` immediately before this edit on a freshly
      ;; re-fetched origin/main: 249 -> 250.
      ;; cloud-itonami-isic-1104 (Manufacture of soft drinks; production of
      ;; mineral waters and other bottled waters) promoted :spec -> :implemented
      ;; (fresh scaffold -- no prior repository under either the stale
      ;; gftdcojp/cloud-itonami-C1104 placeholder or the real cloud-itonami org
      ;; [gh api 404 confirmed]), superproject ADR-2607157000
      ;; (cloud-itonami-isic-1104-soft-drinks-coverage.md). SoftDrinkOpsAdvisor ⊣
      ;; SoftDrinkOps Governor bottling-plant-operations-coordination actor
      ;; mirroring cloud-itonami-isic-1102's [Manufacture of wines] verified
      ;; module shape module-for-module (softdrinkops.* in place of wineops.*)
      ;; -- carbonation/Brix-sugar-content/preservative/microbial-load/
      ;; fill-volume/mineral-content compliance parameters in place of
      ;; ABV/residual-sugar/volatile-acidity/SO2/fill-volume/vintage-percent;
      ;; mixing/carbonation/filling-line control and food-safety-certification
      ;; authority permanently blocked by the closed op allowlist;
      ;; :flag-food-safety-concern always escalates regardless of confidence;
      ;; 58 tests / 195 assertions green, independently re-verified against a
      ;; fresh clone. Live-recomputed via `(industry/maturity-summary)`
      ;; immediately before this edit on a freshly re-fetched origin/main
      ;; (confirmed HEAD's own pinned 250 assertion was still green pre-edit,
      ;; ruling out drift before trusting the delta): 250 -> 251.
      ;; cloud-itonami-isic-0130 (Plant propagation) promoted :spec ->
      ;; :implemented (PropagationAdvisor ⊣ NurseryOperationsGovernor,
      ;; superproject ADR-2607191000; fixed a prior broken attempt on the same
      ;; repo rather than a fresh scaffold -- see the dedicated maturity test
      ;; above for what was wrong). Live-recomputed via
      ;; `(industry/maturity-summary)` immediately before this edit on a
      ;; freshly re-fetched origin/main (retried after a first-attempt 409
      ;; merge conflict against the concurrent cloud-itonami-isic-1104
      ;; promotion; re-fetched and redone against the new tip): 251 -> 252.
      ;; 252 -> 255: cloud-itonami-isic-1629 (Manufacture of other products of
      ;; wood; manufacture of articles of cork, straw and plaiting materials)
      ;; fresh from-scratch scaffold (no prior repository at either the stale
      ;; gftdcojp/cloud-itonami-C1629 placeholder or the real cloud-itonami org
      ;; [gh api 404 confirmed]). WoodCorkStrawAdvisor ⊣ Wood, Cork & Straw
      ;; Products Shop Plant Operations Governor plant-operations-coordination
      ;; actor, mirroring cloud-itonami-isic-1621/1622/1623's verified module
      ;; shape module-for-module, adapted to ISIC 1629's residual/miscellaneous
      ;; multi-product-family scope (handle-turning-lathe/cork-stopper-molding-
      ;; press/wicker-weaving-loom equipment in place of a single processing-
      ;; line equipment family); safety-concern flagging covers plain
      ;; materials-safety/equipment-safety (no ISPM-15-style dimension, unlike
      ;; 1623) and always escalates regardless of confidence. 71 tests / 195
      ;; assertions green, independently re-verified against a fresh clone;
      ;; superproject ADR-2607190600. Live-recomputed via
      ;; `(industry/maturity-summary)` on a freshly re-fetched origin/main
      ;; immediately before this edit (retry-loop landing under heavy
      ;; concurrent-agent load -- prior attempts 409'd as sibling promotions
      ;; landed first): 252 -> 255.
      (is (= 255 (:implemented m))))))

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
