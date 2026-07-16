#!/usr/bin/env nbb
;; scripts/compliance-fact-query.cljs — cross-repo compliance-fact federation
;; query (ADR-2607141700, "cross-repo query 層" section: interim thin
;; federation, NOT net-kotobase which is still L0/L1-only).
;;
;; Loads each source repo's schema/*.edn + data/datascript-tx.edn, merges the
;; schemas, unions the tx-data into ONE DataScript db, and lets you `d/q`
;; across all of them at once even though each repo's git history/ownership/
;; visibility stays separate. Same npm `datascript` JS-interop convention as
;; scripts/labor-liberation-sd.cljs and manifest/edn-query.cljs (attrs are
;; bare strings, not keywords, in the JS-facing API).
;;
;; SOURCES grows one entry per Wave (ADR-2607141700 Wave 0: JPN statute.facts +
;; etzhayyim/global-legislation-datoms legal-source + Tokyo ordinance.facts, so
;; far). Add a source here the moment its repo gets a data/datascript-tx.edn --
;; never invent facts inside THIS script, it only unions what the source repos
;; already committed.
;;
;; 使い方:
;;   nbb scripts/compliance-fact-query.cljs count
;;   nbb scripts/compliance-fact-query.cljs jurisdiction JPN
;;   nbb scripts/compliance-fact-query.cljs municipality tokyo
;;   nbb scripts/compliance-fact-query.cljs q '[:find ?url :where [?e "statute/id" "jpn.appi"] [?e "statute/url" ?url]]'

(require '[scripts.nbb-compat :refer [slurp exit]]
         '[clojure.edn :as edn]
         '[clojure.string :as str]
         '[clojure.java.shell :as shell]
         '["datascript" :as ds-mod])

(def ds (.-default ds-mod))

(def root (str/trim (:out (shell/sh "git" "rev-parse" "--show-toplevel"))))

(def SOURCES
  "Wave 0 (ADR-2607141700). Each entry: repo-relative schema + data path,
  plus a human label used only in `count` output."
  [{:label "cloud-itonami-iso3166-jpn statute.facts"
    :schema "orgs/cloud-itonami/cloud-itonami-iso3166-jpn/schema/statute.edn"
    :data "orgs/cloud-itonami/cloud-itonami-iso3166-jpn/data/datascript-tx.edn"}
   {:label "etzhayyim/global-legislation-datoms legal-source"
    :schema "orgs/etzhayyim/global-legislation-datoms/schema/legislation.edn"
    :data "orgs/etzhayyim/global-legislation-datoms/data/datascript-tx.edn"}
   {:label "cloud-itonami-municipality-jpn-tokyo ordinance.facts"
    :schema "orgs/cloud-itonami/cloud-itonami-municipality-jpn-tokyo/schema/ordinance.edn"
    :data "orgs/cloud-itonami/cloud-itonami-municipality-jpn-tokyo/data/datascript-tx.edn"}
   {:label "cloud-itonami-assoc-6419-jpn-zenginkyo association.facts"
    :schema "orgs/cloud-itonami/cloud-itonami-assoc-6419-jpn-zenginkyo/schema/association-rule.edn"
    :data "orgs/cloud-itonami/cloud-itonami-assoc-6419-jpn-zenginkyo/data/datascript-tx.edn"}
   {:label "cloud-itonami-iso3166-usa statute.facts"
    :schema "orgs/cloud-itonami/cloud-itonami-iso3166-usa/schema/statute.edn"
    :data "orgs/cloud-itonami/cloud-itonami-iso3166-usa/data/datascript-tx.edn"}
   {:label "cloud-itonami-assoc-6512-jpn-sonpo association.facts"
    :schema "orgs/cloud-itonami/cloud-itonami-assoc-6512-jpn-sonpo/schema/association-rule.edn"
    :data "orgs/cloud-itonami/cloud-itonami-assoc-6512-jpn-sonpo/data/datascript-tx.edn"}
   {:label "cloud-itonami-iso3166-gbr statute.facts"
    :schema "orgs/cloud-itonami/cloud-itonami-iso3166-gbr/schema/statute.edn"
    :data "orgs/cloud-itonami/cloud-itonami-iso3166-gbr/data/datascript-tx.edn"}
   {:label "cloud-itonami-assoc-6612-jpn-jsda association.facts"
    :schema "orgs/cloud-itonami/cloud-itonami-assoc-6612-jpn-jsda/schema/association-rule.edn"
    :data "orgs/cloud-itonami/cloud-itonami-assoc-6612-jpn-jsda/data/datascript-tx.edn"}
   {:label "cloud-itonami-iso3166-deu statute.facts"
    :schema "orgs/cloud-itonami/cloud-itonami-iso3166-deu/schema/statute.edn"
    :data "orgs/cloud-itonami/cloud-itonami-iso3166-deu/data/datascript-tx.edn"}
   {:label "cloud-itonami-iso3166-fra statute.facts"
    :schema "orgs/cloud-itonami/cloud-itonami-iso3166-fra/schema/statute.edn"
    :data "orgs/cloud-itonami/cloud-itonami-iso3166-fra/data/datascript-tx.edn"}
   {:label "cloud-itonami-assoc-6419-deu-bankenverband association.facts"
    :schema "orgs/cloud-itonami/cloud-itonami-assoc-6419-deu-bankenverband/schema/association-rule.edn"
    :data "orgs/cloud-itonami/cloud-itonami-assoc-6419-deu-bankenverband/data/datascript-tx.edn"}
   {:label "cloud-itonami-iso3166-can statute.facts"
    :schema "orgs/cloud-itonami/cloud-itonami-iso3166-can/schema/statute.edn"
    :data "orgs/cloud-itonami/cloud-itonami-iso3166-can/data/datascript-tx.edn"}
   {:label "cloud-itonami-assoc-6612-usa-finra association.facts"
    :schema "orgs/cloud-itonami/cloud-itonami-assoc-6612-usa-finra/schema/association-rule.edn"
    :data "orgs/cloud-itonami/cloud-itonami-assoc-6612-usa-finra/data/datascript-tx.edn"}
   {:label "cloud-itonami-assoc-6512-usa-naic association.facts"
    :schema "orgs/cloud-itonami/cloud-itonami-assoc-6512-usa-naic/schema/association-rule.edn"
    :data "orgs/cloud-itonami/cloud-itonami-assoc-6512-usa-naic/data/datascript-tx.edn"}
   {:label "cloud-itonami-iso3166-aus statute.facts"
    :schema "orgs/cloud-itonami/cloud-itonami-iso3166-aus/schema/statute.edn"
    :data "orgs/cloud-itonami/cloud-itonami-iso3166-aus/data/datascript-tx.edn"}
   {:label "cloud-itonami-assoc-6920-jpn-jicpa association.facts"
    :schema "orgs/cloud-itonami/cloud-itonami-assoc-6920-jpn-jicpa/schema/association-rule.edn"
    :data "orgs/cloud-itonami/cloud-itonami-assoc-6920-jpn-jicpa/data/datascript-tx.edn"}
   {:label "cloud-itonami-iso3166-kor statute.facts"
    :schema "orgs/cloud-itonami/cloud-itonami-iso3166-kor/schema/statute.edn"
    :data "orgs/cloud-itonami/cloud-itonami-iso3166-kor/data/datascript-tx.edn"}
   {:label "cloud-itonami-assoc-6920-usa-aicpa association.facts"
    :schema "orgs/cloud-itonami/cloud-itonami-assoc-6920-usa-aicpa/schema/association-rule.edn"
    :data "orgs/cloud-itonami/cloud-itonami-assoc-6920-usa-aicpa/data/datascript-tx.edn"}
   {:label "cloud-itonami-assoc-6419-fra-fbf association.facts"
    :schema "orgs/cloud-itonami/cloud-itonami-assoc-6419-fra-fbf/schema/association-rule.edn"
    :data "orgs/cloud-itonami/cloud-itonami-assoc-6419-fra-fbf/data/datascript-tx.edn"}
   {:label "cloud-itonami-municipality-usa-washington-dc ordinance.facts"
    :schema "orgs/cloud-itonami/cloud-itonami-municipality-usa-washington-dc/schema/ordinance.edn"
    :data "orgs/cloud-itonami/cloud-itonami-municipality-usa-washington-dc/data/datascript-tx.edn"}
   {:label "cloud-itonami-assoc-6511-jpn-seiho association.facts"
    :schema "orgs/cloud-itonami/cloud-itonami-assoc-6511-jpn-seiho/schema/association-rule.edn"
    :data "orgs/cloud-itonami/cloud-itonami-assoc-6511-jpn-seiho/data/datascript-tx.edn"}
   {:label "cloud-itonami-iso3166-nld statute.facts"
    :schema "orgs/cloud-itonami/cloud-itonami-iso3166-nld/schema/statute.edn"
    :data "orgs/cloud-itonami/cloud-itonami-iso3166-nld/data/datascript-tx.edn"}
   {:label "cloud-itonami-assoc-6910-jpn-nichibenren association.facts"
    :schema "orgs/cloud-itonami/cloud-itonami-assoc-6910-jpn-nichibenren/schema/association-rule.edn"
    :data "orgs/cloud-itonami/cloud-itonami-assoc-6910-jpn-nichibenren/data/datascript-tx.edn"}
   {:label "cloud-itonami-iso3166-ita statute.facts"
    :schema "orgs/cloud-itonami/cloud-itonami-iso3166-ita/schema/statute.edn"
    :data "orgs/cloud-itonami/cloud-itonami-iso3166-ita/data/datascript-tx.edn"}
   {:label "cloud-itonami-assoc-6810-jpn-recaj association.facts"
    :schema "orgs/cloud-itonami/cloud-itonami-assoc-6810-jpn-recaj/schema/association-rule.edn"
    :data "orgs/cloud-itonami/cloud-itonami-assoc-6810-jpn-recaj/data/datascript-tx.edn"}
   {:label "cloud-itonami-iso3166-esp statute.facts"
    :schema "orgs/cloud-itonami/cloud-itonami-iso3166-esp/schema/statute.edn"
    :data "orgs/cloud-itonami/cloud-itonami-iso3166-esp/data/datascript-tx.edn"}
   {:label "cloud-itonami-assoc-6411-jpn-boj association.facts"
    :schema "orgs/cloud-itonami/cloud-itonami-assoc-6411-jpn-boj/schema/association-rule.edn"
    :data "orgs/cloud-itonami/cloud-itonami-assoc-6411-jpn-boj/data/datascript-tx.edn"}
   {:label "cloud-itonami-municipality-gbr-london ordinance.facts"
    :schema "orgs/cloud-itonami/cloud-itonami-municipality-gbr-london/schema/ordinance.edn"
    :data "orgs/cloud-itonami/cloud-itonami-municipality-gbr-london/data/datascript-tx.edn"}
   {:label "cloud-itonami-iso3166-swe statute.facts"
    :schema "orgs/cloud-itonami/cloud-itonami-iso3166-swe/schema/statute.edn"
    :data "orgs/cloud-itonami/cloud-itonami-iso3166-swe/data/datascript-tx.edn"}
   {:label "cloud-itonami-assoc-6120-usa-ctia association.facts"
    :schema "orgs/cloud-itonami/cloud-itonami-assoc-6120-usa-ctia/schema/association-rule.edn"
    :data "orgs/cloud-itonami/cloud-itonami-assoc-6120-usa-ctia/data/datascript-tx.edn"}
   {:label "cloud-itonami-municipality-can-toronto ordinance.facts"
    :schema "orgs/cloud-itonami/cloud-itonami-municipality-can-toronto/schema/ordinance.edn"
    :data "orgs/cloud-itonami/cloud-itonami-municipality-can-toronto/data/datascript-tx.edn"}
   {:label "cloud-itonami-iso3166-nor statute.facts"
    :schema "orgs/cloud-itonami/cloud-itonami-iso3166-nor/schema/statute.edn"
    :data "orgs/cloud-itonami/cloud-itonami-iso3166-nor/data/datascript-tx.edn"}
   {:label "cloud-itonami-assoc-5110-usa-a4a association.facts"
    :schema "orgs/cloud-itonami/cloud-itonami-assoc-5110-usa-a4a/schema/association-rule.edn"
    :data "orgs/cloud-itonami/cloud-itonami-assoc-5110-usa-a4a/data/datascript-tx.edn"}
   {:label "cloud-itonami-iso3166-dnk statute.facts"
    :schema "orgs/cloud-itonami/cloud-itonami-iso3166-dnk/schema/statute.edn"
    :data "orgs/cloud-itonami/cloud-itonami-iso3166-dnk/data/datascript-tx.edn"}
   {:label "cloud-itonami-municipality-deu-berlin ordinance.facts"
    :schema "orgs/cloud-itonami/cloud-itonami-municipality-deu-berlin/schema/ordinance.edn"
    :data "orgs/cloud-itonami/cloud-itonami-municipality-deu-berlin/data/datascript-tx.edn"}
   {:label "cloud-itonami-assoc-3510-usa-eei association.facts"
    :schema "orgs/cloud-itonami/cloud-itonami-assoc-3510-usa-eei/schema/association-rule.edn"
    :data "orgs/cloud-itonami/cloud-itonami-assoc-3510-usa-eei/data/datascript-tx.edn"}
   {:label "cloud-itonami-iso3166-fin statute.facts"
    :schema "orgs/cloud-itonami/cloud-itonami-iso3166-fin/schema/statute.edn"
    :data "orgs/cloud-itonami/cloud-itonami-iso3166-fin/data/datascript-tx.edn"}
   {:label "cloud-itonami-assoc-2910-deu-vda association.facts"
    :schema "orgs/cloud-itonami/cloud-itonami-assoc-2910-deu-vda/schema/association-rule.edn"
    :data "orgs/cloud-itonami/cloud-itonami-assoc-2910-deu-vda/data/datascript-tx.edn"}
   {:label "cloud-itonami-municipality-fra-paris ordinance.facts"
    :schema "orgs/cloud-itonami/cloud-itonami-municipality-fra-paris/schema/ordinance.edn"
    :data "orgs/cloud-itonami/cloud-itonami-municipality-fra-paris/data/datascript-tx.edn"}
   {:label "cloud-itonami-iso3166-prt statute.facts"
    :schema "orgs/cloud-itonami/cloud-itonami-iso3166-prt/schema/statute.edn"
    :data "orgs/cloud-itonami/cloud-itonami-iso3166-prt/data/datascript-tx.edn"}
   {:label "cloud-itonami-assoc-5510-usa-ahla association.facts"
    :schema "orgs/cloud-itonami/cloud-itonami-assoc-5510-usa-ahla/schema/association-rule.edn"
    :data "orgs/cloud-itonami/cloud-itonami-assoc-5510-usa-ahla/data/datascript-tx.edn"}
   {:label "cloud-itonami-municipality-nld-amsterdam ordinance.facts"
    :schema "orgs/cloud-itonami/cloud-itonami-municipality-nld-amsterdam/schema/ordinance.edn"
    :data "orgs/cloud-itonami/cloud-itonami-municipality-nld-amsterdam/data/datascript-tx.edn"}
   {:label "cloud-itonami-iso3166-bel statute.facts"
    :schema "orgs/cloud-itonami/cloud-itonami-iso3166-bel/schema/statute.edn"
    :data "orgs/cloud-itonami/cloud-itonami-iso3166-bel/data/datascript-tx.edn"}
   {:label "cloud-itonami-assoc-2100-usa-phrma association.facts"
    :schema "orgs/cloud-itonami/cloud-itonami-assoc-2100-usa-phrma/schema/association-rule.edn"
    :data "orgs/cloud-itonami/cloud-itonami-assoc-2100-usa-phrma/data/datascript-tx.edn"}
   {:label "cloud-itonami-municipality-esp-madrid ordinance.facts"
    :schema "orgs/cloud-itonami/cloud-itonami-municipality-esp-madrid/schema/ordinance.edn"
    :data "orgs/cloud-itonami/cloud-itonami-municipality-esp-madrid/data/datascript-tx.edn"}
   {:label "cloud-itonami-iso3166-bra statute.facts"
    :schema "orgs/cloud-itonami/cloud-itonami-iso3166-bra/schema/statute.edn"
    :data "orgs/cloud-itonami/cloud-itonami-iso3166-bra/data/datascript-tx.edn"}
   {:label "cloud-itonami-assoc-4719-usa-nrf association.facts"
    :schema "orgs/cloud-itonami/cloud-itonami-assoc-4719-usa-nrf/schema/association-rule.edn"
    :data "orgs/cloud-itonami/cloud-itonami-assoc-4719-usa-nrf/data/datascript-tx.edn"}
   {:label "cloud-itonami-municipality-kor-seoul ordinance.facts"
    :schema "orgs/cloud-itonami/cloud-itonami-municipality-kor-seoul/schema/ordinance.edn"
    :data "orgs/cloud-itonami/cloud-itonami-municipality-kor-seoul/data/datascript-tx.edn"}
   {:label "cloud-itonami-iso3166-mex statute.facts"
    :schema "orgs/cloud-itonami/cloud-itonami-iso3166-mex/schema/statute.edn"
    :data "orgs/cloud-itonami/cloud-itonami-iso3166-mex/data/datascript-tx.edn"}
   {:label "cloud-itonami-assoc-4100-usa-agc association.facts"
    :schema "orgs/cloud-itonami/cloud-itonami-assoc-4100-usa-agc/schema/association-rule.edn"
    :data "orgs/cloud-itonami/cloud-itonami-assoc-4100-usa-agc/data/datascript-tx.edn"}
   {:label "cloud-itonami-municipality-ita-roma ordinance.facts"
    :schema "orgs/cloud-itonami/cloud-itonami-municipality-ita-roma/schema/ordinance.edn"
    :data "orgs/cloud-itonami/cloud-itonami-municipality-ita-roma/data/datascript-tx.edn"}
   {:label "cloud-itonami-iso3166-chl statute.facts"
    :schema "orgs/cloud-itonami/cloud-itonami-iso3166-chl/schema/statute.edn"
    :data "orgs/cloud-itonami/cloud-itonami-iso3166-chl/data/datascript-tx.edn"}
   {:label "cloud-itonami-assoc-6020-usa-nab association.facts"
    :schema "orgs/cloud-itonami/cloud-itonami-assoc-6020-usa-nab/schema/association-rule.edn"
    :data "orgs/cloud-itonami/cloud-itonami-assoc-6020-usa-nab/data/datascript-tx.edn"}
   {:label "cloud-itonami-municipality-aus-sydney ordinance.facts"
    :schema "orgs/cloud-itonami/cloud-itonami-municipality-aus-sydney/schema/ordinance.edn"
    :data "orgs/cloud-itonami/cloud-itonami-municipality-aus-sydney/data/datascript-tx.edn"}
   {:label "cloud-itonami-iso3166-arg statute.facts"
    :schema "orgs/cloud-itonami/cloud-itonami-iso3166-arg/schema/statute.edn"
    :data "orgs/cloud-itonami/cloud-itonami-iso3166-arg/data/datascript-tx.edn"}
   {:label "cloud-itonami-assoc-3600-usa-awwa association.facts"
    :schema "orgs/cloud-itonami/cloud-itonami-assoc-3600-usa-awwa/schema/association-rule.edn"
    :data "orgs/cloud-itonami/cloud-itonami-assoc-3600-usa-awwa/data/datascript-tx.edn"}
   {:label "cloud-itonami-municipality-arg-buenos-aires ordinance.facts"
    :schema "orgs/cloud-itonami/cloud-itonami-municipality-arg-buenos-aires/schema/ordinance.edn"
    :data "orgs/cloud-itonami/cloud-itonami-municipality-arg-buenos-aires/data/datascript-tx.edn"}
   {:label "cloud-itonami-iso3166-zaf statute.facts"
    :schema "orgs/cloud-itonami/cloud-itonami-iso3166-zaf/schema/statute.edn"
    :data "orgs/cloud-itonami/cloud-itonami-iso3166-zaf/data/datascript-tx.edn"}
   {:label "cloud-itonami-municipality-fin-helsinki ordinance.facts"
    :schema "orgs/cloud-itonami/cloud-itonami-municipality-fin-helsinki/schema/ordinance.edn"
    :data "orgs/cloud-itonami/cloud-itonami-municipality-fin-helsinki/data/datascript-tx.edn"}
   {:label "cloud-itonami-assoc-4923-usa-ata association.facts"
    :schema "orgs/cloud-itonami/cloud-itonami-assoc-4923-usa-ata/schema/association-rule.edn"
    :data "orgs/cloud-itonami/cloud-itonami-assoc-4923-usa-ata/data/datascript-tx.edn"}
   {:label "cloud-itonami-municipality-dnk-copenhagen ordinance.facts"
    :schema "orgs/cloud-itonami/cloud-itonami-municipality-dnk-copenhagen/schema/ordinance.edn"
    :data "orgs/cloud-itonami/cloud-itonami-municipality-dnk-copenhagen/data/datascript-tx.edn"}
   {:label "cloud-itonami-iso3166-col statute.facts"
    :schema "orgs/cloud-itonami/cloud-itonami-iso3166-col/schema/statute.edn"
    :data "orgs/cloud-itonami/cloud-itonami-iso3166-col/data/datascript-tx.edn"}
   {:label "cloud-itonami-municipality-nor-oslo ordinance.facts"
    :schema "orgs/cloud-itonami/cloud-itonami-municipality-nor-oslo/schema/ordinance.edn"
    :data "orgs/cloud-itonami/cloud-itonami-municipality-nor-oslo/data/datascript-tx.edn"}
   {:label "cloud-itonami-assoc-5610-usa-nra association.facts"
    :schema "orgs/cloud-itonami/cloud-itonami-assoc-5610-usa-nra/schema/association-rule.edn"
    :data "orgs/cloud-itonami/cloud-itonami-assoc-5610-usa-nra/data/datascript-tx.edn"}
   {:label "cloud-itonami-municipality-bel-brussels ordinance.facts"
    :schema "orgs/cloud-itonami/cloud-itonami-municipality-bel-brussels/schema/ordinance.edn"
    :data "orgs/cloud-itonami/cloud-itonami-municipality-bel-brussels/data/datascript-tx.edn"}
   {:label "cloud-itonami-iso3166-ury statute.facts"
    :schema "orgs/cloud-itonami/cloud-itonami-iso3166-ury/schema/statute.edn"
    :data "orgs/cloud-itonami/cloud-itonami-iso3166-ury/data/datascript-tx.edn"}
   {:label "cloud-itonami-municipality-chl-santiago ordinance.facts"
    :schema "orgs/cloud-itonami/cloud-itonami-municipality-chl-santiago/schema/ordinance.edn"
    :data "orgs/cloud-itonami/cloud-itonami-municipality-chl-santiago/data/datascript-tx.edn"}
   {:label "cloud-itonami-assoc-2011-usa-acc association.facts"
    :schema "orgs/cloud-itonami/cloud-itonami-assoc-2011-usa-acc/schema/association-rule.edn"
    :data "orgs/cloud-itonami/cloud-itonami-assoc-2011-usa-acc/data/datascript-tx.edn"}
   {:label "cloud-itonami-municipality-col-bogota ordinance.facts"
    :schema "orgs/cloud-itonami/cloud-itonami-municipality-col-bogota/schema/ordinance.edn"
    :data "orgs/cloud-itonami/cloud-itonami-municipality-col-bogota/data/datascript-tx.edn"}
   {:label "cloud-itonami-iso3166-cri statute.facts"
    :schema "orgs/cloud-itonami/cloud-itonami-iso3166-cri/schema/statute.edn"
    :data "orgs/cloud-itonami/cloud-itonami-iso3166-cri/data/datascript-tx.edn"}
   {:label "cloud-itonami-municipality-cri-san-jose ordinance.facts"
    :schema "orgs/cloud-itonami/cloud-itonami-municipality-cri-san-jose/schema/ordinance.edn"
    :data "orgs/cloud-itonami/cloud-itonami-municipality-cri-san-jose/data/datascript-tx.edn"}
   {:label "cloud-itonami-assoc-8621-usa-ama association.facts"
    :schema "orgs/cloud-itonami/cloud-itonami-assoc-8621-usa-ama/schema/association-rule.edn"
    :data "orgs/cloud-itonami/cloud-itonami-assoc-8621-usa-ama/data/datascript-tx.edn"}
   {:label "cloud-itonami-municipality-bra-sao-paulo ordinance.facts"
    :schema "orgs/cloud-itonami/cloud-itonami-municipality-bra-sao-paulo/schema/ordinance.edn"
    :data "orgs/cloud-itonami/cloud-itonami-municipality-bra-sao-paulo/data/datascript-tx.edn"}
   {:label "cloud-itonami-iso3166-pan statute.facts"
    :schema "orgs/cloud-itonami/cloud-itonami-iso3166-pan/schema/statute.edn"
    :data "orgs/cloud-itonami/cloud-itonami-iso3166-pan/data/datascript-tx.edn"}
   {:label "cloud-itonami-municipality-ury-montevideo ordinance.facts"
    :schema "orgs/cloud-itonami/cloud-itonami-municipality-ury-montevideo/schema/ordinance.edn"
    :data "orgs/cloud-itonami/cloud-itonami-municipality-ury-montevideo/data/datascript-tx.edn"}
   {:label "cloud-itonami-assoc-6201-usa-gtia association.facts"
    :schema "orgs/cloud-itonami/cloud-itonami-assoc-6201-usa-gtia/schema/association-rule.edn"
    :data "orgs/cloud-itonami/cloud-itonami-assoc-6201-usa-gtia/data/datascript-tx.edn"}
   {:label "cloud-itonami-municipality-zaf-cape-town ordinance.facts"
    :schema "orgs/cloud-itonami/cloud-itonami-municipality-zaf-cape-town/schema/ordinance.edn"
    :data "orgs/cloud-itonami/cloud-itonami-municipality-zaf-cape-town/data/datascript-tx.edn"}
   {:label "cloud-itonami-iso3166-ecu statute.facts"
    :schema "orgs/cloud-itonami/cloud-itonami-iso3166-ecu/schema/statute.edn"
    :data "orgs/cloud-itonami/cloud-itonami-iso3166-ecu/data/datascript-tx.edn"}
   {:label "cloud-itonami-municipality-ecu-quito ordinance.facts"
    :schema "orgs/cloud-itonami/cloud-itonami-municipality-ecu-quito/schema/ordinance.edn"
    :data "orgs/cloud-itonami/cloud-itonami-municipality-ecu-quito/data/datascript-tx.edn"}
   {:label "cloud-itonami-assoc-0610-usa-api association.facts"
    :schema "orgs/cloud-itonami/cloud-itonami-assoc-0610-usa-api/schema/association-rule.edn"
    :data "orgs/cloud-itonami/cloud-itonami-assoc-0610-usa-api/data/datascript-tx.edn"}
   {:label "cloud-itonami-municipality-swe-gothenburg ordinance.facts"
    :schema "orgs/cloud-itonami/cloud-itonami-municipality-swe-gothenburg/schema/ordinance.edn"
    :data "orgs/cloud-itonami/cloud-itonami-municipality-swe-gothenburg/data/datascript-tx.edn"}
   {:label "cloud-itonami-iso3166-pry statute.facts"
    :schema "orgs/cloud-itonami/cloud-itonami-iso3166-pry/schema/statute.edn"
    :data "orgs/cloud-itonami/cloud-itonami-iso3166-pry/data/datascript-tx.edn"}
   {:label "cloud-itonami-municipality-pry-asuncion ordinance.facts"
    :schema "orgs/cloud-itonami/cloud-itonami-municipality-pry-asuncion/schema/ordinance.edn"
    :data "orgs/cloud-itonami/cloud-itonami-municipality-pry-asuncion/data/datascript-tx.edn"}
   {:label "cloud-itonami-municipality-mex-guadalajara ordinance.facts"
    :schema "orgs/cloud-itonami/cloud-itonami-municipality-mex-guadalajara/schema/ordinance.edn"
    :data "orgs/cloud-itonami/cloud-itonami-municipality-mex-guadalajara/data/datascript-tx.edn"}
   {:label "cloud-itonami-iso3166-gtm statute.facts"
    :schema "orgs/cloud-itonami/cloud-itonami-iso3166-gtm/schema/statute.edn"
    :data "orgs/cloud-itonami/cloud-itonami-iso3166-gtm/data/datascript-tx.edn"}
   {:label "cloud-itonami-municipality-fra-lyon ordinance.facts"
    :schema "orgs/cloud-itonami/cloud-itonami-municipality-fra-lyon/schema/ordinance.edn"
    :data "orgs/cloud-itonami/cloud-itonami-municipality-fra-lyon/data/datascript-tx.edn"}
   {:label "cloud-itonami-assoc-0150-usa-afbf association.facts"
    :schema "orgs/cloud-itonami/cloud-itonami-assoc-0150-usa-afbf/schema/association-rule.edn"
    :data "orgs/cloud-itonami/cloud-itonami-assoc-0150-usa-afbf/data/datascript-tx.edn"}
   {:label "cloud-itonami-iso3166-hnd statute.facts"
    :schema "orgs/cloud-itonami/cloud-itonami-iso3166-hnd/schema/statute.edn"
    :data "orgs/cloud-itonami/cloud-itonami-iso3166-hnd/data/datascript-tx.edn"}
   {:label "cloud-itonami-municipality-ind-new-delhi ordinance.facts"
    :schema "orgs/cloud-itonami/cloud-itonami-municipality-ind-new-delhi/schema/ordinance.edn"
    :data "orgs/cloud-itonami/cloud-itonami-municipality-ind-new-delhi/data/datascript-tx.edn"}
   {:label "cloud-itonami-assoc-2910-gbr-smmt association.facts"
    :schema "orgs/cloud-itonami/cloud-itonami-assoc-2910-gbr-smmt/schema/association-rule.edn"
    :data "orgs/cloud-itonami/cloud-itonami-assoc-2910-gbr-smmt/data/datascript-tx.edn"}
   {:label "cloud-itonami-municipality-pol-warsaw ordinance.facts"
    :schema "orgs/cloud-itonami/cloud-itonami-municipality-pol-warsaw/schema/ordinance.edn"
    :data "orgs/cloud-itonami/cloud-itonami-municipality-pol-warsaw/data/datascript-tx.edn"}
   {:label "cloud-itonami-iso3166-ind statute.facts"
    :schema "orgs/cloud-itonami/cloud-itonami-iso3166-ind/schema/statute.edn"
    :data "orgs/cloud-itonami/cloud-itonami-iso3166-ind/data/datascript-tx.edn"}
   {:label "cloud-itonami-municipality-ken-nairobi ordinance.facts"
    :schema "orgs/cloud-itonami/cloud-itonami-municipality-ken-nairobi/schema/ordinance.edn"
    :data "orgs/cloud-itonami/cloud-itonami-municipality-ken-nairobi/data/datascript-tx.edn"}
   {:label "cloud-itonami-assoc-6419-aus-aba association.facts"
    :schema "orgs/cloud-itonami/cloud-itonami-assoc-6419-aus-aba/schema/association-rule.edn"
    :data "orgs/cloud-itonami/cloud-itonami-assoc-6419-aus-aba/data/datascript-tx.edn"}
   {:label "cloud-itonami-iso3166-ken statute.facts"
    :schema "orgs/cloud-itonami/cloud-itonami-iso3166-ken/schema/statute.edn"
    :data "orgs/cloud-itonami/cloud-itonami-iso3166-ken/data/datascript-tx.edn"}
   {:label "cloud-itonami-municipality-tha-bangkok ordinance.facts"
    :schema "orgs/cloud-itonami/cloud-itonami-municipality-tha-bangkok/schema/ordinance.edn"
    :data "orgs/cloud-itonami/cloud-itonami-municipality-tha-bangkok/data/datascript-tx.edn"}
   {:label "cloud-itonami-assoc-5010-nor-rederiforbundet association.facts"
    :schema "orgs/cloud-itonami/cloud-itonami-assoc-5010-nor-rederiforbundet/schema/association-rule.edn"
    :data "orgs/cloud-itonami/cloud-itonami-assoc-5010-nor-rederiforbundet/data/datascript-tx.edn"}])

;; ---------- keyword → 裸文字列変換（labor-liberation-sd.cljs と同一方針） ----------

(defn kw->attr [k]
  (if (keyword? k)
    (if-let [ns (namespace k)] (str ns "/" (name k)) (name k))
    (str k)))

(defn ->ds-scalar [v] (if (keyword? v) (kw->attr v) v))

(defn ->ds-value [v]
  (cond
    (map? v) (pr-str v)
    (or (vector? v) (seq? v) (set? v)) (into-array (map ->ds-scalar v))
    :else (->ds-scalar v)))

(defn entity->js [source-label m]
  (let [obj (js-obj)]
    (doseq [[k v] m]
      (aset obj (kw->attr k) (->ds-value v)))
    (aset obj "compliance-fact/source" source-label)
    obj))

;; npm datascript's JS wrapper needs schema CONFIG values that are themselves
;; keywords (:db.cardinality/many, :db.unique/identity, ...) written as
;; colon-prefixed strings (":db.cardinality/many") -- unlike entity attrs and
;; schema attr-names, which stay bare (no colon). Empirically verified
;; (2026-07-14): a bare "db.cardinality/many" or "many" string is silently
;; ignored (falls back to cardinality-one, array values become one opaque
;; datom instead of exploding into N datoms); only the colon-prefixed form
;; actually engages cardinality-many.
(defn ->ds-schema-value [v] (if (keyword? v) (str ":" (kw->attr v)) v))

(defn schema-entry->js [config]
  (let [obj (js-obj)]
    (doseq [[k v] config] (aset obj (kw->attr k) (->ds-schema-value v)))
    obj))

(defn schema->js [schema-map]
  (let [obj (js-obj)]
    (doseq [[attr config] schema-map] (aset obj (kw->attr attr) (schema-entry->js config)))
    obj))

;; ---------- load + union ----------

(defn load-source [{:keys [label schema data]}]
  (let [schema-path (str root "/" schema)
        data-path (str root "/" data)]
    (if (and (.existsSync (js/require "node:fs") schema-path)
             (.existsSync (js/require "node:fs") data-path))
      {:label label
       :schema (edn/read-string (slurp schema-path))
       :entities (edn/read-string (slurp data-path))}
      (do (println "SKIP (missing schema or data):" label) nil))))

(def loaded (into [] (keep load-source) SOURCES))

(def merged-schema
  (-> (reduce merge {} (map :schema loaded))
      (assoc "compliance-fact/source" {})))

(defn build-conn []
  (let [conn (.create_conn ds (schema->js merged-schema))
        tx (mapcat (fn [{:keys [label entities]}]
                      (map #(entity->js label %) entities))
                    loaded)]
    (.transact ds conn (into-array tx))
    conn))

(def conn (build-conn))
(def db (.db ds conn))

(defn q [query-str & args]
  (js->clj (.apply (.-q ds) ds (into-array (concat [query-str db] args)))))

;; ---------- commands ----------

(defn print-count []
  (doseq [{:keys [label entities]} loaded]
    (println (str (count entities) "\t" label)))
  (println (str (reduce + (map (comp count :entities) loaded)) "\tTOTAL")))

(defn print-jurisdiction [iso3]
  (println (str "== " iso3 " (statute.facts) =="))
  (let [rows (q (str "[:find ?id ?title ?url :in $ ?j :where
                       [?e \"statute/jurisdiction\" ?j]
                       [?e \"statute/id\" ?id] [?e \"statute/title\" ?title]
                       [?e \"statute/url\" ?url]]")
                iso3)]
    (doseq [[id title url] rows] (println (str "  " id "  " title "  <" url ">"))))
  (println (str "== " iso3 " (legal-source) =="))
  (let [rows (q (str "[:find ?name ?url :in $ ?j :where
                       [?e \"legal-source/jurisdiction\" ?j]
                       [?e \"legal-source/name\" ?name] [?e \"legal-source/url\" ?url]]")
                iso3)]
    (doseq [[name url] rows] (println (str "  " name "  <" url ">")))))

(defn print-municipality [muni]
  (println (str "== " muni " (ordinance.facts) =="))
  (let [rows (q (str "[:find ?id ?title ?url :in $ ?m :where
                       [?e \"ordinance/municipality\" ?m]
                       [?e \"ordinance/id\" ?id] [?e \"ordinance/title\" ?title]
                       [?e \"ordinance/url\" ?url]]")
                muni)]
    (doseq [[id title url] rows] (println (str "  " id "  " title "  <" url ">")))))

(defn print-association [assoc-slug]
  (println (str "== " assoc-slug " (association.facts) =="))
  (let [rows (q (str "[:find ?id ?title ?url :in $ ?a :where
                       [?e \"association-rule/association\" ?a]
                       [?e \"association-rule/id\" ?id] [?e \"association-rule/title\" ?title]
                       [?e \"association-rule/url\" ?url]]")
                assoc-slug)]
    (doseq [[id title url] rows] (println (str "  " id "  " title "  <" url ">")))))

(defn -main [& args]
  (let [[mode arg] args]
    (case mode
      "count" (print-count)
      "jurisdiction" (print-jurisdiction arg)
      "municipality" (print-municipality arg)
      "association" (print-association arg)
      "q" (println (pr-str (q arg)))
      (do (println "usage: nbb scripts/compliance-fact-query.cljs [count|jurisdiction <ISO3>|municipality <slug>|association <slug>|q '<datalog>']")
          (exit 1)))))

(apply -main *command-line-args*)
