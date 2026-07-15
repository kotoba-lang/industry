# ADR-2607082700: `cloud-itonami-isic-4211` disaster/severe-weather safety slice (Construction Advisor ⊣ Construction Governor)

## Status

Accepted. `cloud-itonami-isic-4211` moved from docs-only `:blueprint`
to `:partially-implemented` (NOT `:implemented` -- see Problem 1 and
Consequences).

## Related

- `orgs/cloud-itonami/cloud-itonami-isic-4211/docs/adr/0001-architecture.md`
  (child-repo architecture ADR -- the primary decision record; this
  ADR is the superproject-side pointer + cross-repo context)
- `langgraph-clj` ADR-0001 (StateGraph actor pattern this build reuses)
- `gftdcojp/cloud-itonami`'s `src/cloud_itonami/mail.cljc` (the
  Resend-via-`java.net.http` convention this build's
  `construction.notify` follows for its mail transport)

## Context

A user request asked whether `cloud-itonami`'s construction vertical
had any functionality for typhoon/disaster warnings, safety
inspections, prevention outreach, mail+phone notification, or report
generation for a construction site, and asked which Japanese law(s)
this corresponds to, plus US/EU equivalents. Investigation found:
`orgs/cloud-itonami/cloud-itonami-isic-4211` (the actual ISIC 4211
"building construction" blueprint) had `blueprint.edn` +
`docs/business-model.md` + `docs/operator-guide.md` only -- no `src/`,
no tests, none of the requested functionality. (A same-named but
UNRELATED actor at `orgs/gftdcojp/cloud-itonami` -- a general internal
business-OS whose `itonami.opsllm`/`itonami.governor` pair is scoped
to aircraft-engine certification -- was initially confused for the
construction vertical; this ADR's build is in the correct repo,
`cloud-itonami/cloud-itonami-isic-4211`.)

The user then asked for this to be built, with the legal basis stored
as EDN data (not just prose) including real sources, and the sources/
URLs committed to GitHub.

## Problem

1. `cloud-itonami-isic-4211`'s blueprint described a real-world
   construction operating model (`intake : design : permit : build :
   inspect : handover : audit`) naming a `:construction-governor`, but
   had zero implementing code -- no Store, no Governor, no tests, and
   critically none of the disaster-safety functionality the user
   asked about (no typhoon/weather assessment, no mandatory post-event
   inspection, no alert notification, no report generation).
2. The blueprint's own Operator Guide worked example is a robot
   panel-placement dispatch (structural/envelope work), a DIFFERENT
   slice of the same governor than what was asked. Building both in
   one pass was out of scope; this ADR's build deliberately covers
   ONLY the disaster/severe-weather safety slice, leaving robot
   dispatch for a separate follow-up under the same governor pattern.
3. Real law differs by jurisdiction in a way that is easy to get
   wrong by generalizing from one: Japan's labor-safety regulation
   states a real numeric work-stoppage trigger (労働安全衛生規則
   第522条: 10-min average wind ≥10 m/s / rainfall ≥50 mm per event /
   snowfall ≥25 cm per event), while the USA (OSH Act General Duty
   Clause / 29 CFR 1926.20) and the EU (Framework Directive 89/391/EEC
   Art.5 general risk-assessment duty) impose a risk-assessment duty
   with NO fixed numeric trigger. A naive per-jurisdiction data table
   would either omit this real difference or, worse, invent numbers
   for the USA/EU to make the table look uniform.
4. The user asked for mail AND phone notification. This fleet's only
   prior real outbound-transport precedent (`cloud-itonami.mail` in
   the UNRELATED `gftdcojp/cloud-itonami` repo) covers mail (Resend)
   only -- no phone/voice-call precedent exists anywhere in either
   repo or the wider `orgs/cloud-itonami/*` fleet (confirmed by grep
   before starting; see child ADR-0001 for the exact search).

## Decision

Full decision record (entity/op shape, the seven Construction Governor
HARD checks, the `:actuation/dispatch-alert` auto-commit exception and
its safety-officer-approval prerequisite, report-document rendering,
`construction.notify`'s mail+phone design, Store/Phase/Governor
scaffolding) is recorded in the child repo:
`orgs/cloud-itonami/cloud-itonami-isic-4211/docs/adr/0001-architecture.md`
(Decisions 1-9). Summary of the parts most relevant at the
superproject level:

1. **Legal basis is EDN data, cited, sourced** --
   `src/construction/facts.cljc`'s `catalog` seeds JPN/USA/DEU (DEU as
   the EU-jurisdiction proxy, the SAME convention `aerospace.facts`
   already established for EASA) with real official sources:

   | Jurisdiction | Legal basis | Source URL |
   |---|---|---|
   | JPN | 労働安全衛生規則 第522条・第655条・第97条／建築基準法 第12条 | https://laws.e-gov.go.jp/law/347M50002000032 ／ https://www.mlit.go.jp/jutakukentiku/build/jutakukentiku_house_tk_000039.html |
   | USA | OSH Act §5(a)(1) / 29 CFR 1926.20 / 29 CFR 1904 | https://www.osha.gov/laws-regs/regulations/standardnumber/1926/1926.20 ／ https://www.osha.gov/recordkeeping |
   | EU (DEU proxy) | Framework Directive 89/391/EEC Art.5 / Construction Sites Directive 92/57/EEC / Baustellenverordnung | https://eur-lex.europa.eu/legal-content/EN/ALL/?uri=celex:31992L0057 ／ https://osha.europa.eu/en/legislation/directives/15 |

2. **Honest quantitative/qualitative split.**
   `weather-threshold-exceeded?` returns `true`/`false` only for
   Japan's real numeric trigger; for the USA/EU it returns the keyword
   `:qualitative` rather than a fabricated boolean -- and the
   Construction Governor's HARD "still exceeds threshold" check
   (which a human CANNOT override) fires ONLY on the `true` case,
   never on `:qualitative`. The USA/EU work-resume decision instead
   always reaches a human via a SOFT, unconditional high-stakes gate
   -- a genuine legal difference reflected in enforcement layer, not
   just prose.
3. **`:actuation/dispatch-alert` (mail+phone) is the one actuation
   event in this repo that MAY auto-commit** when the Construction
   Governor is clean AND a human already approved the underlying
   `:weather/assess` stop-work determination -- because for a disaster
   warning, delay (not an extra warning) is the harm to guard against.
   Every other actuation (`:actuation/authorize-resume`,
   `:actuation/file-accident-report`, `:actuation/file-periodic-
   report`) never auto-commits, matching every prior sibling actor's
   posture.
4. **`construction.notify`** adds real `resend-mail-notifier` (mail)
   and `twilio-voice-notifier` (phone -- an actual outbound voice call
   speaking inline TwiML `<Say>`, not SMS, per the request), following
   `cloud-itonami.mail`'s injectable-`:http-fn` transport convention
   exactly; `mock-notifier` is the default everywhere (dev/tests/demo,
   no network).
5. **Report generation renders an actual document.**
   `construction.registry/render-accident-report` /
   `render-periodic-report` produce human-readable report text citing
   the jurisdiction's legal basis inline -- verified end-to-end via
   `clojure -M:dev:run`.

## Consequences

- `cloud-itonami-isic-4211`'s `blueprint.edn` now carries
  `:itonami.blueprint/maturity :partially-implemented` (not
  `:implemented`) and `:itonami.blueprint/implemented-slice`
  describing exactly what this build covers -- robot-dispatch (the
  Operator Guide's panel-placement example) remains unbuilt, and this
  ADR does not claim otherwise.
- 59 tests / 238 assertions pass; `clj-kondo` lint clean; the demo
  (`clojure -M:dev:run`) walks a full typhoon episode end-to-end
  (weather assessment → mandatory inspection → auto-dispatched
  mail+phone alert → blocked premature resume → cleared resume →
  injury → filed accident report → filed periodic report) plus every
  HARD-hold case, and prints the two rendered report documents.
- Commits pushed to `cloud-itonami/cloud-itonami-isic-4211` main
  (`8cad4e0`, `7d0694d`). This repo is NOT a west-managed project
  (confirmed: absent from both `manifest/repos.edn` and
  `manifest/west.yml` -- the entire `orgs/cloud-itonami/*` ISIC
  blueprint fleet sits outside this superproject's west manifest), so
  no `manifest/west.yml` pin-advance or `nbb scripts/gen-west-
  manifest.cljs` step applies to this change.
- **Known follow-up, not fixed in this ADR:**
  `kotoba-lang/industry`'s `resources/kotoba/industry/registry.edn`
  entry for `"4211"` is stale (`:repo`/`:business-id` point at
  `gftdcojp/cloud-itonami-4211`, missing the `isic-` infix and naming
  the wrong org; the real repo is `cloud-itonami/cloud-itonami-isic-
  4211`) and has no `:maturity` field at all, unlike most of that
  registry's other entries. Left for a separate PR in that repo --
  see the child ADR-0001's own Follow-ups section for why promoting it
  to that registry's `:implemented` tier is not appropriate given this
  build's `:partially-implemented` scope.
- Robot-dispatch (panel placement etc.) remains a separate,
  not-yet-implemented follow-up slice of the same
  `:construction-governor`.
