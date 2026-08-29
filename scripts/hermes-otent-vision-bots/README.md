# `hermes-otent-vision-bots`

Six daily Hermes bots extend the existing Otent globe/lake into licensed Earth and street-level image observations and connect them to Hyakka.

| bot | daily JST | responsibility |
|---|---:|---|
| `otent-geo-ontology` | 18:10 | Hyakka imagery/model-run/detection ontology |
| `otent-earth-imagery` | 19:10 | licensed Earth/aerial imagery ingest and coverage |
| `otent-earth-vision` | 20:10 | reproducible Earth-image analysis |
| `otent-street-imagery` | 21:10 | bounded Mapillary/KartaView/authority imagery ingest |
| `otent-street-vision` | 22:10 | privacy-safe feature observations |
| `otent-hyakka-publish` | 23:10 | signed Hyakka connector and query/readback |

The family reuses `cloud-itonami/otent`, `app-otent`, `com-mapillary-graph-api`, Overpass and `app-hyakka`. It does not create a second globe or image client. `otent-vision-scope.edn` fixes provenance, licence, uncertainty and privacy boundaries.

Google Street View imagery is not persisted/mined unless explicit rights are independently verified. Face identity/embedding, plate OCR, tracking, re-identification, protected-trait inference, home occupancy and sensitive-site targeting are forbidden. Every detection remains a dated model/provider observation, not ground truth.

Each bot has a dedicated worktree, adds at most one source/area/model per run, and opens at most one PR. It cannot push main, merge, deploy, publish or hand-edit ledgers. Verify with `python3 scripts/hermes-otent-vision-bots/verify_bundle.py`. Dashboard: `http://127.0.0.1:9119/cron`.
