# Development baseline — 2026-09-08

Reference route: `qwen3.8-27b-throughput-b70`, not Basho and not the selected
fine-tuning base. Eight sequential non-streaming requests used the eight Basho
development prompts, temperature 0 and max_tokens 192. All eight returned HTTP
200 with the requested model identifier; seven stopped, one hit the output
limit. This is neither uptime evidence nor proof of a trained model.

The first Python urllib attempt received HTTP 403 and stopped immediately.
The subsequent curl run completed. Preserve both logs; this client difference
is not resolved by the successful second run. Requests contain only the newly
authored development prompts, no credentials or customer data.

Agent inspection (not independent or blind judging) found useful failure modes:

- `b-e02`: adds “往復” to the unspecified shipping-cost condition, returns
  multiple alternatives and terminates with `length`. The token budget may
  explain truncation; it does not explain the added condition.
- `b-e06`: treats an unannounced location as “未定（不明）”. Undisclosed and
  undecided are different facts. Faithful editing should preserve that boundary.
- `b-e03`: follows the two-sentence/no-forbidden-word constraints, but depicts
  tracks/trees/mountains without clearly identifying the requested snowy station.
  This is a relevance concern for review, not an automatic failure score.

Do not derive a win rate from these observations. No adapted model exists in
this comparison. This suite has now been inspected and remains a development
suite: it must not be reported later as an untouched independent holdout.
Collect and freeze the larger final holdout separately.

`export-receipt.edn` binds the seed and JSONL bytes. Raw observations preserve
requests, returned model, finish reason, content, usage and elapsed time.
