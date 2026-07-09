# ADR-0017: gftd-keiei-sim の実LLMバックエンドを OpenRouter (MiniMax-M2.7) にする

- **Status**: Superseded
- **Date**: 2026-06-13
- **Superseded**: 2026-06-29
- **Deciders**: 河崎純真 (jun@gftd.group)
- **Context tags**: llm, gftd-keiei-sim, kotoba-llm, openrouter, minimax, modal, self-host, cost, agent
- **Related**: ADR-0013(portable Clojure エージェントスタック — 本ゲームの clj 社員が同型)、ADR-0010(EDN 事実層 + Datalog ビュー)
- **Implementation**: historical `orgs/gftdcojp/gftd-keiei-sim/` source tree was retired on 2026-06-29. Business/keiei operating surface is now `cloud-itonami`; people/talent facts reuse lives in `gftd-talent-actor`.
- **SSoT (machine-readable)**: ルート `deps.edn` no longer registers `gftd-keiei-sim` as an active project. This ADR is retained as backend-selection history.

## 2026-06-29 Closure

`gftd-keiei-sim` is no longer an active root project path. The source tree was retired to remove the stale standalone management-game surface. The durable decisions remain:

- OpenRouter MiniMax-M2.7 remains the historical backend decision for the game experiment.
- `orgs/gftdcojp/minimax-m2-modal/` remains as a `.cljc` eval-only harness and cloud-murakumo reference, not a deployed service.
- Keiei/business activity is represented through `cloud-itonami` lanes and datom logs rather than the standalone Rust/CLJS game tree.

## Context

gftd-keiei-sim は LLM エージェント社員（営業/開発/財務/法務/CEO補佐）が経営判断を生成するゲームで、推論は OpenAI 互換 `/v1/chat/completions` をホスト能力注入で叩く（ADR-0013 の「I/O・推論はホスト注入」と同型）。初期既定はローカル **gemma4 e4b @ Ollama**（`KOTOBA_INFERENCE_MODEL=gemma4:latest`）。

「Opus 4.8 にローカルで最も近いモデルは何か / 自前ホストのコスパは見合うか」を検証した。候補と実測（Modal）:

| モデル | 配布重み | ローカル(128GB)で動く | 検証結果 |
|---|---|---|---|
| **MiniMax-M2.7** | 230GB(FP8) / int4 ~115-130GB | int4 なら可 | ✅ Modal 4×H100 で推論・ツール呼び出し・コーディング全合格 |
| Kimi-K2.7-Code | 595GB(int4, 1T/32B) | ✗（512GB+/多GPU） | コーディング/エージェントで Opus に最も肉薄。ただし起動できず（int4 Marlin が nvcc JIT 要求、要 CUDA-devel 像）。公開ベンチで代替 |
| MiniMax-M3 | 854GB(BF16, 428B) | ✗ | MSA(sparse attention)が vLLM/SGLang 未対応・modeling コード未同梱で**起動不可**（2026-06） |

自前ホスト(Modal)の M2.7 実測コスパ:
- 当初 ~10 tok/s（enforce-eager・FP8 を Triton fallback・単発）は計測条件の歪み。
- **最適化（CUDA-devel イメージ=nvcc / CUDAグラフ / DeepGEMM+FlashInfer / バッチ）後**: 単一ストリーム **10→144 tok/s（14倍）**、並列64で **集約 2,865 tok/s・約 $1.55/1M トークン**。
- ただし **コールドスタート ~18分**（9P Volume 読み + graph capture + DeepGEMM warmup）。GPU snapshot は**マルチGPU非対応**のため、TP=4 では sub秒復帰が使えない（snapshot は単一GPUに載る int4 + sleep-mode 構成でのみ有効）。

OpenRouter の MiniMax-M2.7（`minimax/minimax-m2.7`）は **$0.30/1M(input) / $1.20/1M(output)**・即時・運用ゼロ。

## Decision

gftd-keiei-sim の本番 LLM バックエンドは **OpenRouter の `minimax/minimax-m2.7`** とする（OpenAI 互換のため既存配線そのまま）:

```
KOTOBA_INFERENCE_URL=https://openrouter.ai/api/v1
KOTOBA_INFERENCE_MODEL=minimax/minimax-m2.7
KOTOBA_INFERENCE_API_KEY=<OpenRouter key>
```

- **ローカル gemma4 e4b @ Ollama は開発/オフライン用 fallback** として残す（既定のまま。URL 未設定なら従来どおりローカル or スタブ）。
- **自前ホスト(Modal)は採用しない**（当面）。理由は下記トレードオフ。

## Consequences

- **コスト/運用**: 低〜中稼働では OpenRouter が明確に有利（$0.30/$1.20 per 1M・即時・運用ゼロ）。自前ホストが見合うのは「超高並列・高稼働」or「オフライン/プライバシー要件」のときのみ。
- **品質**: M2.7 はツール呼び出し・段階推論・コーディングいずれも実用十分（実測で確認）。Opus 4.8 比はコーディング/エージェントでやや劣るが、ゲームの社員推論には十分。
- **可搬性**: OpenAI 互換のため、将来 self-host（int4 + 単一GPU + GPU snapshot で sub秒起動）や別プロバイダへ env だけで切替可能。配線変更不要。
- **検証成果物（参考実装、非デプロイ）**: `orgs/gftdcojp/minimax-m2-modal/`（Modal で M2.7/Kimi を起動し直接対決・コスパ計測したスクリプト群）。Modal の Volume/pod は検証後に削除済（課金停止）。
- **非スコープ**: M3 のローカル運用（エンジン対応待ち）、Kimi-K2.7-Code の self-host（CUDA-devel イメージ要・高コスト）、自前ホスト本番化（snapshot/warm 運用が前提）。
