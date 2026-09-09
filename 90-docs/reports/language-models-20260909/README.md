# Public-copy language model research: 2026-09-09

Scope: draft generation for kotobase.net, murakumo.cloud, kotoba-lang.org, kotoba.cloud, x402.nexus and itonami.cloud. General Bot routing, private content and automatic publishing are outside this change.

## Sources and interpretation

The supplied [Reddit post](https://www.reddit.com/r/ProgrammingLanguages/comments/1wbeqxi/lispshaped_and_the_compiler_is_the_part_worth/) was removed by Reddit filters; its body was unavailable. This work interprets language as the 17 natural languages requested immediately before it.

Prices: [official catalog](https://openrouter.ai/api/v1/models). Usage: [official data API](https://openrouter.ai/docs/cookbook/administration/data-api), September 2-8, 2026. Usage is global public model prompt + completion tokens, not language-specific usage or traffic on these sites. Missing daily top-50 entries are unknown, not zero.

Source: OpenRouter (https://openrouter.ai/rankings), as of 2026-09-09T09:10:50.529Z. Licensed under CC BY 4.0.

## Snapshot prices and adoption

USD per million tokens; provider availability and future pricing may change.

| Model | Input | Output | Public tokens, 7 days |
|---|---:|---:|---:|
| inception/mercury-2.5 | 0.04 | 0.15 | unknown |
| google/gemini-3.8-flash | 0.75 | 3.75 | 1909926559760 |
| qwen/qwen3.8-flash | 0.15 | 0.47 | 69938982818 |
| z-ai/glm-5.3-flash | 0.075 | 0.25 | 12171327216756 |
| openai/gpt-5.6-luna | 0.2 | 1.2 | 14454071776447 |
| deepseek/deepseek-v4-flash | 0.088606 | 0.177212 | 4993215183447 |
| openai/gpt-4.1-mini | 0.4 | 1.6 | unknown |

## Provisional routes

Only current-prompt contract-passing candidates with observed public usage are admitted. Routine prioritizes observed wall time; bulk prioritizes reported cost for the same five strings. The boundary of one million monthly output tokens is an initial operating choice, not measured demand. One sample is not a statistical latency or semantic-quality ranking. Newly listed cheapest models without measured usage are not automatically adopted.

| Locale | Routine first | Bulk first |
|---|---|---|
| en: English | English source identity | English source identity |
| zh-Hans: Simplified Chinese | openai/gpt-5.6-luna | z-ai/glm-5.3-flash |
| hi: Hindi | openai/gpt-5.6-luna | deepseek/deepseek-v4-flash |
| es: Spanish | openai/gpt-5.6-luna | z-ai/glm-5.3-flash |
| ar: Modern Standard Arabic | openai/gpt-5.6-luna | z-ai/glm-5.3-flash |
| fr: French | openai/gpt-5.6-luna | z-ai/glm-5.3-flash |
| bn: Bengali | z-ai/glm-5.3-flash | z-ai/glm-5.3-flash |
| pt: Portuguese | openai/gpt-5.6-luna | deepseek/deepseek-v4-flash |
| id: Indonesian | openai/gpt-5.6-luna | deepseek/deepseek-v4-flash |
| ur: Urdu | openai/gpt-5.6-luna | deepseek/deepseek-v4-flash |
| ru: Russian | z-ai/glm-5.3-flash | deepseek/deepseek-v4-flash |
| de: German | openai/gpt-5.6-luna | z-ai/glm-5.3-flash |
| ja: Japanese | z-ai/glm-5.3-flash | deepseek/deepseek-v4-flash |
| ko: Korean | openai/gpt-5.6-luna | z-ai/glm-5.3-flash |
| pcm: Nigerian Pidgin, not standard English | openai/gpt-5.6-luna | deepseek/deepseek-v4-flash |
| arz: Egyptian Arabic, not Modern Standard Arabic | openai/gpt-5.6-luna | openai/gpt-5.6-luna |
| mr: Marathi | openai/gpt-5.6-luna | deepseek/deepseek-v4-flash |

## Measurements and limits

Initial runs and retries: 86 requests, 63 responses, 23 HTTP errors; response-reported cost $0.006227591154. Initial error bodies were unavailable. GLM required a corrected low-reasoning request profile. Qwen remained rate-limited after retries and is excluded.

Current prompt: 48 requests, 48 responses, 37 contract passes, response-reported cost $0.004191872288. Eleven failures changed currency, URL boundaries, brand tokens or numbers. The strict numeric rule can conservatively reject a legitimate translation adding a numeral, such as Japanese one month. The five source strings are fictional public test copy, not service price announcements.

These checks cover JSON keys, nonempty values, exact fixed-token multisets per value, minimal script presence, changed-from-English output and normal completion. They do not certify semantics, negation, dialect or naturalness. Native review remains unverified for every language, especially pcm and arz.

Reported costs exclude unknown charges for failed requests and exclude separate activation receipts. Canonical slugs, provider, generation ID and timestamps are retained; request aliases do not prove immutable model weights.

## Activated workflow

manifest/public-site-locales.edn references the shared policy and the existing scripts/model-eval/bench.cljs translate command. Locale and expected monthly output volume choose an ordered list. Failed validation triggers only admitted fallbacks; exhaustion exits 1. English needs no inference. This provides a shared draft-generation entry point, not automatic replacement of every historical site-specific generator.

Public input requires an explicit flag. Each batch is limited to 20 keys and 1500 UTF-8 bytes, output to 4096 tokens. Provider price bounds and a $0.05 estimated admission budget apply. This is not an account-wide billing hard cap; unknown cost is conservatively accounted and reported overruns stop after recording. Evidence expires 2026-10-09 and generation then fails closed until refresh. No site is automatically published; Kotobase legal publication hold is unchanged.

Initial ar activation rejected GLM currency loss and succeeded with Luna, accounting $0.000218935 including the rejected response. activation/policy-initial.edn preserves that earlier candidate policy. Final-policy runs are recorded separately in activation-final.

## Evidence

models.json, usage.json, evaluations.json (initial profile), evaluations-v2.json (current profile), checks-v2.json, sample.json, activation/ and activation-final/. The routing source of truth is manifest/public-language-models.edn.

## Final verification

23 routing and preservation assertions passed. A response with an HTTP-error status and a truncated completion with otherwise valid text both failed admission. All 17 locale entries and model references resolve; changed EDN documents parse. Independent read-only review recomputed all 37 passing routes and found no blocking issue. Final ja / arz / zh-Hans requests succeeded with the selected models; en used source identity at zero inference cost. Final reported activation cost: $0.00026456. These are local and provider observations, not a fleet CI receipt.
