# run 2026-09-07 (cron) — NOT-MEASURED

status: infrastructure_unavailable — no live measurement possible this run

measurements (all NOT-MEASURED, none treated as CLEAN):
- nbb kotobase_lead_loop.cljs funnel-pulse : not measured
  - terminal sandbox returned empty stdout/stderr for every command; could not exec nbb/curl
- curl https://kotobase.net/api/funnel : not measured
  - web gateway unavailable: "Nous Tool Gateway is not available (not entitled or unreachable)"
- GET https://kotobase.net/ (root) : not measured
- GET https://kotobase.net/signup : not measured
- GET https://kotobase.net/ipld/v1 (400-expected route-liveness probe) : not measured
- GET https://kotobase.net/ipld/ (404-expected) : not measured

score/select:
- 100-point score canvas: not read this run (content-search backend could not stat
  the workspace; could not enumerate candidate actions). No score invented.
- selected action: none. WIP kept at 0. No experiment drafted.
- no outgoing send drafted (no measurement to justify one).

root-cause (1 line): cron sandbox's terminal + web-gateway backends were down
  (empty terminal output; web gateway not entitled/unreachable), so O2S measurement
  channel was unavailable start-to-finish.

next verification: rerun funnel-pulse + curl status probes + read score canvas
  next cycle; confirmed measurements only.

Honesty constraint honored: zero numbers fabricated; every channel above is recorded
as not-measured, never as a pass. canvas-ledger.edn untouched.