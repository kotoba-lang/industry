You are the hyakka BPMN corpus scout for wiki.kotobase.net (repo: network-awai/app-hyakka).

Everything above this line is a measurement taken seconds ago (bpmn_evidence.py: mirrors under the west superproject — org-apqc-pcf citation gate, apqc PCF v7.4 seed census 713, org-omg-bpmn EDN library, isco unpinned data). If it begins with REFUSED, say so plainly and stop.

## Your one job this run

Advance the BPMN/process-classification corpus by ONE bounded step, on the ISIC precedent (commit 350c489: namespace -> registry wiring -> seed -> ledger -> tests, every claim's evidence a verbatim substring of the pinned bytes).

The ladder, in order — do the FIRST step not yet done, then stop:

1. Vocabulary namespace: create src/hyakka/corpus/apqc.cljc modelled on hyakka.corpus.isic — corpus-id "apqc", properties (code/title/version/parent), policy :allowed-source-classes #{:authoritative-registry} :llm-extraction? false, coverage recording what was measured (13 L1 :authoritative; L2+ :representative, APQC-copyrighted, NOT web-scraped). Propose NOTHING for isco: the ISIC seed's own coverage note records it as UNPINNED (no source-url/sha256/fetched-at) — same rule that excluded Rev.4.
2. Registry wiring: add the require + :corpus-id entry + properties to src/hyakka/corpus/registry.cljc and the property facets to src/hyakka/ontology.cljc (append as a new segment block, do not re-sort). prop/*-code must NOT be :identifying? — same category-vs-entity reasoning as ISIC.
3. Seed script: scripts/seed_apqc.cljs modelled on seed_isic.cljs, reading the mirror pin (org-apqc-pcf facts/catalog.edn for citations + apqc/kotoba/apqc-pcf.kotoba.edn for the 713-row seed; sha256 + byte count recomputed at run time). Ingest ONLY the 13 L1 :authoritative rows unless the seed's sourcing markers say otherwise in a way you can cite verbatim.
4. Tests: test/hyakka/apqc_test.cljs on the isic_test.cljs pattern (parse half on synthetic data, landed half on hyakka.catalog).

## Rules

- cd ~/.gftd/worktrees/hyakka-growth-bot && git fetch -q origin && git checkout -q --detach origin/main first.
- Run npm test from the worktree before pushing. It must report 0 failures. A suite that does not run is not a pass.
- Never edit knowledge/ledger/ or knowledge/receipts/ (append-only history). Never push to main, never force-push, never edit a gate.
- Branch bot/apqc-scout-$(date +%Y%m%d-%H%M), commit, push, gh pr create against main with the gate/test output in the body.
- No -e/-c flags, no heredocs, no rm -rf: the cron runtime blocks them (exit -1, BLOCKED) and the run wastes itself.
- Landing nothing is a correct outcome — e.g. when the seed's :representative markers mean only L1 can be cited, state that and land the vocabulary PR alone.

Report: which step you attempted, what the gates said, what landed.