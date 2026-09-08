# AWAI models on Murakumo — development and application basis

Owner direction: 2026-09-08. Application remains unsubmitted. This document
supersedes the earlier application's exclusion of original models as a product
direction, not as a claim that trained checkpoints already exist.

## Three connected differentiators

| Feature | Product | Evidence required before selecting it on the application |
| --- | --- | --- |
| Unique Models | AWAI develops Basho for Japanese writing/editing and Hokusai for Japanese audiovisual direction | Trained adapter/checkpoint readback, pinned base and dataset lineage, commercial rights, blind base-versus-adapted evaluation, serving proof |
| Low Pricing | Murakumo offers a capacity-limited economy tier for latency-tolerant work | Cost per successful useful output, including failed attempts and backup use; dated comparable market prices; sustainable published tariff |
| Unique Infrastructure | Murakumo fleet is primary; Modal is a stability backup with fleet recovery | Actual deployment inventory and placement; identical artifact on failover/failback; measured admission, cancellation and recovery |

These are the target three selections, not three presently proven claims. Do not
select low latency, high throughput, strategic partnership or decentralization
by inference from these choices. AWAI is the model developer; Murakumo is the
inference provider; the legal operator is AWAI Network, L.L.C. Public
representative: Ryo Awai. Keep application progress and company phone off the LP.

## Basho: Japanese editing with faithful meaning

First release hypothesis: a compact text model makes economy pricing and fleet
replication practical. The exact base is not yet selected. Historical plans for
Qwen3.8-Flash-Next and the existing GLM relay are not a trained Basho artifact.
Do not silently switch that production relay when experimenting with a new base.

Specialization: rewrite register without changing facts; shorten Japanese while
preserving constraints; preserve uncertainty and attribution; handle quoted
instructions as text; produce natural Japanese without invented promises.
Literary atmosphere is a secondary evaluated capability, not a substitute for
utility. No imitation of a living author's style is needed for this dataset.

Start with parameter-efficient supervised fine-tuning after base license and
runtime compatibility are verified. Pin base revision, tokenizer/chat template,
adapter hash, training code revision, seed and dataset hashes. A GGUF rename,
system prompt, routing policy or quantization alone is not a unique model.

The seed dataset is newly authored synthetic material, clearly labeled. It is a
pipeline/development seed, not a sufficient production corpus or a claim of
commercial clearance. It contains no customer conversations or internal corpus.
Expand independently reviewed training coverage; keep the evaluation set out of
training and teacher prompts. Human-review the linguistic quality and rights
basis before commercial training. Do not make current workspace documents public
through a trained model: their consent is workspace-internal.

## Hokusai: Japanese scene direction in short audiovisual clips

Keep the existing video modality. Hypothesis: controlled ma (pauses), restrained
camera motion, specified scene order, and audiovisual timing under Japanese
instructions. Evaluate these with observable shot/timing constraints, not a
vague claim of Japanese sensibility. Start with one supported resolution,
duration and input mode, then expand only after measurement.

MiniMax-H3 remains the candidate base, subject to license and training-path
qualification. Its model card describes a custom community license, an open
base, and hosted Context-IR and 2K components. Do not claim the complete hosted
system runs on fleet or assume an SDXL character LoRA trainer supports H3.
The currently found character-LoRA runner is an SDXL skeleton, not an H3 trainer.

Acquire original or explicitly licensed paired clips, captions and audio with
per-asset hashes and rights records for model training and commercial serving.
Actor/voice permissions, music rights and reference rights must cover intended
use. Storyboard text alone cannot train or qualify a video generator. Do not
silently reuse SHIRO & PICO, REN, private files or customer uploads as a corpus.
Prototype the H3 training step and adapter reload on a small cleared batch;
measure memory and step time before a full run. No paid always-on GPU deployment
is part of this document.

## Evaluation and release

The checked-in seed suite has development tasks, not an independent benchmark.
Freeze a larger untouched holdout before optimization. For a release, compare
base and adapted outputs with identical prompts, decoding and runtime; blind
the labels and use human Japanese reviewers. Report all failures and ties.
Provisional targets: at least 200 Japanese text cases and 50 video prompts;
target-skill preference confidence interval above chance; no material regression
on factual preservation, safety and general instruction-following. Set and
record non-inferiority margins before scoring. These are internal targets, not
OpenRouter requirements. An automated format check does not score writing quality.

Release bundle: model card, attribution/license, dataset manifest/hash, training
receipt, loadable artifact/hash, blind evaluation report, serving catalog,
capacity/price worksheet and same-artifact recovery evidence. An adapter must be
served with the exact base it was trained against. Publish only measured
capabilities. Text and video require separate costs and billing units.

## Execution order and current limitations

Development seed commands (existing corpus generator; run from root):

```sh
nbb scripts/gen-training-corpus.cljs --awai-self-test
nbb scripts/gen-training-corpus.cljs --awai-research-export /tmp/awai-seed-new-directory
```

The export writes 12 text training examples, 8 text evaluation cases, 6 video
evaluation cases and a hash receipt. It refuses an existing destination and
rejects empty data, repeated prompts and cross-split family reuse. The JSONL
training file uses user/assistant messages. No video training data is fabricated.
The receipt deliberately retains research-only status and pending reviews.

## Cost experiment to run with each candidate

Use actual invoice, metered power and useful-output counts from the same time
window. Allocate idle cost to reserved capacity; include cancellations, retries,
failed generations, payment fees, storage/egress and operator/hardware costs.
Keep package-energy readings distinct from wall energy. Count all paid Modal
attempts, even if a later fleet attempt succeeds. Separate fixed monthly cost
from marginal work and report results at measured occupancy, not assumed 100%.

For text, retain input/output token counts separately and replay representative
short/long input ratios against a proposed tariff. For video, record output
seconds, resolution, audio mode and successful artifact downloads; do not reuse
text-token economics. Require positive contribution at the measured workload
mix and budgeted backup fraction before calling a tariff sustainable. Compare
against dated offers for equivalent model quality, quantization and capabilities;
an original model also needs a quality-matched comparison. No current price is
activated by this worksheet.

1. Review the Basho seed and expand training data; select a fleet-compatible base.
2. Verify a bounded training step and adapter reload; then train a candidate.
3. Run held-out base/adapted comparisons and actual endpoint verification.
4. Acquire cleared Hokusai clips and qualify H3 training on available hardware.
5. Measure fleet-primary economics and same-artifact Modal recovery per model.
6. Rewrite and submit the provider application only with completed evidence.

Current evidence: no trained Basho or Hokusai revision was found in the existing
application/routing records. The private workspace corpus and research corpus
have use restrictions. The 2026-09-08 live fleet snapshot returned unavailable
nodes and a heavily utilized GPU node; online inventory is not free training
capacity. Do not interrupt existing workloads to manufacture a training success.

References checked 2026-09-08:
- https://openrouter.ai/providers/apply/form
- https://openrouter.ai/providers/apply
- https://huggingface.co/MiniMaxAI/MiniMax-H3
- https://huggingface.co/Qwen/Qwen3.8-Flash-Next

The OpenRouter feature names express differentiation, not guaranteed acceptance.
Its current provider page prioritizes proprietary models and allows monthly
invoicing. An open-base fine-tune's acceptance remains OpenRouter's decision.
