# Rescued candidate list: unregistered compat-actor repo paths

Recovered by `git-cleanup-conflict` (2026-07-07) from two stale stashes found
in the shared superproject checkout:

- `wip-rescue-2607051930-manifest-repos-west` (dated 2026-07-05 19:30, ~270
  candidate `manifest/repos.edn` entries)
- `unrelated WIP: murakumo-studio ADR + manifest additions` (a later,
  partially-overlapping snapshot, ~70 additional candidates plus a
  murakumo-studio ADR addendum that was rescued separately in
  `rescue-2607070900-small-wip`, already merged)

## What this is

118 `"orgs/kotoba-lang/com-*"` path strings that appear in one or both
stashes' `manifest/repos.edn` diff but do **not** exist in current `main`
under any comment/formatting variant (deduplicated, comment-formatting
differences normalized). Each follows the `"was etzhayyim/root/20-actors/
<name>-compat"` naming pattern used elsewhere in `repos.edn` for the
ISIC/UNSPSC compat-actor split-out effort.

## Why this is NOT applied to `repos.edn` directly

Per this repo's registration workflow (`manifest/cleanup-workflow.edn`,
`repos.edn :manifest-workflow`), a `manifest/repos.edn` entry should only be
added once the child repo actually exists (ADR → scaffold → `git init` +
push → `gh repo create` → **then** manifest registration via
`bb scripts/gen-west-manifest.bb --entry <name>`, which fetches the repo's
real HEAD and server-side-verifies the pin). None of these 118 paths were
verified against GitHub in this cleanup pass — some or all of the
corresponding child repos may not exist yet (the stashes are 2+ days old in
a very fast-moving repo; the effort may have been intentionally paused,
redirected under different names, or simply interrupted).

Blindly adding these to `repos.edn` would desync it from `west.yml`
(`gen-west-manifest.bb --check` would report STALE for all 118), and running
the generator over unverified paths would fail loudly for any that don't
exist on GitHub yet.

## What to do with this

For each path below that the owner wants to keep: verify the GitHub repo
exists (`gh api repos/kotoba-lang/<name> --silent`), scaffold+create it if
not, then register it with `bb scripts/gen-west-manifest.bb --entry <name>`
(minimal per-entry diff, server-verified pin) — not a bulk paste into
`repos.edn`. Paths where the repo will never be created should simply be
dropped from this list.

## Candidates (deduplicated, path + original "was" comment)

```
"orgs/kotoba-lang/com-bny-mellon-api" ; was etzhayyim/root/20-actors/bny_mellon_api-compat
 "orgs/kotoba-lang/com-inDrive" ; was etzhayyim/root/20-actors/inDrive-compat
 "orgs/kotoba-lang/com-ironSource" ; was etzhayyim/root/20-actors/ironSource-compat
 "orgs/kotoba-lang/com-rocket-lab" ; was etzhayyim/root/20-actors/rocket_lab-compat
 "orgs/kotoba-lang/com-rockwell-factorytalk" ; was etzhayyim/root/20-actors/rockwell_factorytalk-compat
 "orgs/kotoba-lang/com-rolls-royce-flight" ; was etzhayyim/root/20-actors/rolls_royce_flight-compat
 "orgs/kotoba-lang/com-ros-robotics" ; was etzhayyim/root/20-actors/ros_robotics-compat
 "orgs/kotoba-lang/com-ros2-nav" ; was etzhayyim/root/20-actors/ros2_nav-compat
 "orgs/kotoba-lang/com-rosettanet" ; was etzhayyim/root/20-actors/rosettanet-compat
 "orgs/kotoba-lang/com-rtgs-global" ; was etzhayyim/root/20-actors/rtgs_global-compat
 "orgs/kotoba-lang/com-rtos" ; was etzhayyim/root/20-actors/rtos-compat
 "orgs/kotoba-lang/com-rystad-energy" ; was etzhayyim/root/20-actors/rystad_energy-compat
 "orgs/kotoba-lang/com-sabre-airlines" ; was etzhayyim/root/20-actors/sabre_airlines-compat
 "orgs/kotoba-lang/com-sabre-gds" ; was etzhayyim/root/20-actors/sabre_gds-compat
 "orgs/kotoba-lang/com-sabre-sonic" ; was etzhayyim/root/20-actors/sabre_sonic-compat
 "orgs/kotoba-lang/com-saic-networks" ; was etzhayyim/root/20-actors/saic_networks-compat
 "orgs/kotoba-lang/com-saildrone" ; was etzhayyim/root/20-actors/saildrone-compat
 "orgs/kotoba-lang/com-sailpoint" ; was etzhayyim/root/20-actors/sailpoint-compat
 "orgs/kotoba-lang/com-salesforce" ; was etzhayyim/root/20-actors/salesforce-compat
 "orgs/kotoba-lang/com-samsara" ; was etzhayyim/root/20-actors/samsara-compat
 "orgs/kotoba-lang/com-sanity" ; was etzhayyim/root/20-actors/sanity-compat
 "orgs/kotoba-lang/com-sansan" ; was etzhayyim/root/20-actors/sansan-compat
 "orgs/kotoba-lang/com-sap-plm" ; was etzhayyim/root/20-actors/sap_plm-compat
 "orgs/kotoba-lang/com-sap" ; was etzhayyim/root/20-actors/sap-compat
 "orgs/kotoba-lang/com-saphybris" ; was etzhayyim/root/20-actors/saphybris-compat
 "orgs/kotoba-lang/com-sapiens" ; was etzhayyim/root/20-actors/sapiens-compat
 "orgs/kotoba-lang/com-sbb-swiss" ; was etzhayyim/root/20-actors/sbb_swiss-compat
 "orgs/kotoba-lang/com-scaleai" ; was etzhayyim/root/20-actors/scaleai-compat
 "orgs/kotoba-lang/com-schlumberger-osdu" ; was etzhayyim/root/20-actors/schlumberger_osdu-compat
 "orgs/kotoba-lang/com-secgov-edgar" ; was etzhayyim/root/20-actors/secgov_edgar-compat
 "orgs/kotoba-lang/com-securiti" ; was etzhayyim/root/20-actors/securiti-compat
 "orgs/kotoba-lang/com-segment" ; was etzhayyim/root/20-actors/segment-compat
 "orgs/kotoba-lang/com-sel4" ; was etzhayyim/root/20-actors/sel4-compat
 "orgs/kotoba-lang/com-semicon-robotics" ; was etzhayyim/root/20-actors/semicon_robotics-compat
 "orgs/kotoba-lang/com-sendbird" ; was etzhayyim/root/20-actors/sendbird-compat
 "orgs/kotoba-lang/com-sendgrid" ; was etzhayyim/root/20-actors/sendgrid-compat
 "orgs/kotoba-lang/com-sentinelone" ; was etzhayyim/root/20-actors/sentinelone-compat
 "orgs/kotoba-lang/com-sepa-ct" ; was etzhayyim/root/20-actors/sepa_ct-compat
 "orgs/kotoba-lang/com-serdes-phy" ; was etzhayyim/root/20-actors/serdes_phy-compat
 "orgs/kotoba-lang/com-servicenow" ; was etzhayyim/root/20-actors/servicenow-compat
 "orgs/kotoba-lang/com-sf-commerce" ; was etzhayyim/root/20-actors/sf-commerce-compat
 "orgs/kotoba-lang/com-shares-pms" ; was etzhayyim/root/20-actors/shares_pms-compat
 "orgs/kotoba-lang/com-shipbob" ; was etzhayyim/root/20-actors/shipbob-compat
 "orgs/kotoba-lang/com-shippo" ; was etzhayyim/root/20-actors/shippo-compat
 "orgs/kotoba-lang/com-shipstation" ; was etzhayyim/root/20-actors/shipstation-compat
 "orgs/kotoba-lang/com-shodan" ; was etzhayyim/root/20-actors/shodan-compat
 "orgs/kotoba-lang/com-shopee" ; was etzhayyim/root/20-actors/shopee-compat
 "orgs/kotoba-lang/com-shopify" ; was etzhayyim/root/20-actors/shopify-compat
 "orgs/kotoba-lang/com-sic-codes" ; was etzhayyim/root/20-actors/sic_codes-compat
 "orgs/kotoba-lang/com-siemens-simatic" ; was etzhayyim/root/20-actors/siemens_simatic-compat
 "orgs/kotoba-lang/com-siemens-teamcenter" ; was etzhayyim/root/20-actors/siemens_teamcenter-compat
 "orgs/kotoba-lang/com-signal-api" ; was etzhayyim/root/20-actors/signal_api-compat
 "orgs/kotoba-lang/com-silicon-photonics" ; was etzhayyim/root/20-actors/silicon_photonics-compat
 "orgs/kotoba-lang/com-singpass" ; was etzhayyim/root/20-actors/singpass-compat
 "orgs/kotoba-lang/com-sita-gds" ; was etzhayyim/root/20-actors/sita_gds-compat
 "orgs/kotoba-lang/com-sita-onair" ; was etzhayyim/root/20-actors/sita_onair-compat
 "orgs/kotoba-lang/com-sitecore" ; was etzhayyim/root/20-actors/sitecore-compat
 "orgs/kotoba-lang/com-siteminder" ; was etzhayyim/root/20-actors/siteminder-compat
 "orgs/kotoba-lang/com-sketch" ; was etzhayyim/root/20-actors/sketch-compat
 "orgs/kotoba-lang/com-skydio" ; was etzhayyim/root/20-actors/skydio-compat
 "orgs/kotoba-lang/com-slack" ; was etzhayyim/root/20-actors/slack-compat
 "orgs/kotoba-lang/com-smart-meter-dms" ; was etzhayyim/root/20-actors/smart_meter_dms-compat
 "orgs/kotoba-lang/com-smart-on-fhir" ; was etzhayyim/root/20-actors/smart_on_fhir-compat
 "orgs/kotoba-lang/com-smartdubai" ; was etzhayyim/root/20-actors/smartdubai-compat
 "orgs/kotoba-lang/com-smpp-gw" ; was etzhayyim/root/20-actors/smpp_gw-compat
 "orgs/kotoba-lang/com-sncf-france" ; was etzhayyim/root/20-actors/sncf_france-compat
 "orgs/kotoba-lang/com-snomed-ct" ; was etzhayyim/root/20-actors/snomed_ct-compat
 "orgs/kotoba-lang/com-snowflake" ; was etzhayyim/root/20-actors/snowflake-compat
 "orgs/kotoba-lang/com-social-code-compliance" ; was etzhayyim/root/20-actors/social_code_compliance-compat
 "orgs/kotoba-lang/com-solidworks" ; was etzhayyim/root/20-actors/solidworks-compat
 "orgs/kotoba-lang/com-sonardyne" ; was etzhayyim/root/20-actors/sonardyne-compat
 "orgs/kotoba-lang/com-sonarqube" ; was etzhayyim/root/20-actors/sonarqube-compat
 "orgs/kotoba-lang/com-sophos" ; was etzhayyim/root/20-actors/sophos-compat
 "orgs/kotoba-lang/com-spacex-telemetry" ; was etzhayyim/root/20-actors/spacex_telemetry-compat
 "orgs/kotoba-lang/com-spatial-os" ; was etzhayyim/root/20-actors/spatial_os-compat
 "orgs/kotoba-lang/com-spire-global" ; was etzhayyim/root/20-actors/spire_global-compat
 "orgs/kotoba-lang/com-splunk" ; was etzhayyim/root/20-actors/splunk-compat
 "orgs/kotoba-lang/com-spryker" ; was etzhayyim/root/20-actors/spryker-compat
 "orgs/kotoba-lang/com-square-retail" ; was etzhayyim/root/20-actors/square-retail-compat
 "orgs/kotoba-lang/com-square" ; was etzhayyim/root/20-actors/square-compat
 "orgs/kotoba-lang/com-ss7-sigtran" ; was etzhayyim/root/20-actors/ss7_sigtran-compat
 "orgs/kotoba-lang/com-starlink-telemetry" ; was etzhayyim/root/20-actors/starlink_telemetry-compat
 "orgs/kotoba-lang/com-starlink" ; was etzhayyim/root/20-actors/starlink-compat
 "orgs/kotoba-lang/com-statestreet-api" ; was etzhayyim/root/20-actors/statestreet_api-compat
 "orgs/kotoba-lang/com-stc-pay" ; was etzhayyim/root/20-actors/stc_pay-compat
 "orgs/kotoba-lang/com-strapi" ; was etzhayyim/root/20-actors/strapi-compat
 "orgs/kotoba-lang/com-stratus-clearpath" ; was etzhayyim/root/20-actors/stratus_clearpath-compat
 "orgs/kotoba-lang/com-subcom-telemetry" ; was etzhayyim/root/20-actors/subcom_telemetry-compat
 "orgs/kotoba-lang/com-supabase" ; was etzhayyim/root/20-actors/supabase-compat
 "orgs/kotoba-lang/com-superagi" ; was etzhayyim/root/20-actors/superagi-compat
 "orgs/kotoba-lang/com-surescripts" ; was etzhayyim/root/20-actors/surescripts-compat
 "orgs/kotoba-lang/com-surveymonkey" ; was etzhayyim/root/20-actors/surveymonkey-compat
 "orgs/kotoba-lang/com-swell" ; was etzhayyim/root/20-actors/swell-compat
 "orgs/kotoba-lang/com-swift-iso20022" ; was etzhayyim/root/20-actors/swift_iso20022-compat
 "orgs/kotoba-lang/com-swift-mt" ; was etzhayyim/root/20-actors/swift_mt-compat
 "orgs/kotoba-lang/com-swiggy" ; was etzhayyim/root/20-actors/swiggy-compat
 "orgs/kotoba-lang/com-swish-sweden" ; was etzhayyim/root/20-actors/swish_sweden-compat
 "orgs/kotoba-lang/com-swiss-re-api" ; was etzhayyim/root/20-actors/swiss_re_api-compat
 "orgs/kotoba-lang/com-symbility" ; was etzhayyim/root/20-actors/symbility-compat
 "orgs/kotoba-lang/com-synchron" ; was etzhayyim/root/20-actors/synchron-compat
 "orgs/kotoba-lang/com-syngenta" ; was etzhayyim/root/20-actors/syngenta-compat
 "orgs/kotoba-lang/com-synopsys-eda" ; was etzhayyim/root/20-actors/synopsys_eda-compat
 "orgs/kotoba-lang/com-synthego" ; was etzhayyim/root/20-actors/synthego-compat
 "orgs/kotoba-lang/com-tableau" ; was etzhayyim/root/20-actors/tableau-compat
 "orgs/kotoba-lang/com-tae-technologies" ; was etzhayyim/root/20-actors/tae_technologies-compat
 "orgs/kotoba-lang/com-talabat" ; was etzhayyim/root/20-actors/talabat-compat
 "orgs/kotoba-lang/com-tcp-ip" ; was etzhayyim/root/20-actors/tcp_ip-compat
 "orgs/kotoba-lang/com-teledyne-marine" ; was etzhayyim/root/20-actors/teledyne_marine-compat
 "orgs/kotoba-lang/com-telegram-api" ; was etzhayyim/root/20-actors/telegram_api-compat
 "orgs/kotoba-lang/com-tensorrt" ; was etzhayyim/root/20-actors/tensorrt-compat
 "orgs/kotoba-lang/com-terraform" ; was etzhayyim/root/20-actors/terraform-compat
 "orgs/kotoba-lang/com-tesla-api" ; was etzhayyim/root/20-actors/tesla_api-compat
 "orgs/kotoba-lang/com-tesla-fsd" ; was etzhayyim/root/20-actors/tesla_fsd-compat
 "orgs/kotoba-lang/com-tesla-nacs" ; was etzhayyim/root/20-actors/tesla_nacs-compat
 "orgs/kotoba-lang/com-tfl" ; was etzhayyim/root/20-actors/tfl-compat
 "orgs/kotoba-lang/com-tga-australia" ; was etzhayyim/root/20-actors/tga_australia-compat
 "orgs/kotoba-lang/com-zte-cbss" ; was etzhayyim/root/20-actors/zte_cbss-compat
 "orgs/kotoba-lang/com-zymergen" ; was etzhayyim/root/20-actors/zymergen-compat
```
