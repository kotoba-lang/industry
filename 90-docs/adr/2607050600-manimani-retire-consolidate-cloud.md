# ADR-2607050600: ai-gftd-manimani を退役し、manimani 系を manimani(OSS)+cloud-manimani の2系統に集約

**Status**: accepted
**Date**: 2026-07-05
**Deciders**: Jun Kawasaki

## Context

「manimani」を名乗る実装が portfolio 内に事実上 4 系統併存していた(2026-07-04 の成熟度診断で判明):
OSS `gftdcojp/manimani`(triage デスクトップ/mobile)+ `gftdcojp/cloud-manimani`(その cljs Worker
API)、`gftdcojp/ai-gftd-manimani`(langgraph pod + kotoba/D1 + Linear 風 work board の
enterprise actor)、`etzhayyim/root` 内の別実装(自己申告で "SUPERSEDED" 済・対象外)。

`ai-gftd-manimani` の実態を精査した結果、CLAUDE.md の "LIVE" 記載は stale と判明:

- **標準リポジトリ(`gftdcojp/ai-gftd-manimani`)は空のスキャフォールド**: 892行・commit 2件、
  `clj/` の全グラフ(`daily_digest`/`context_query`/`record_decision`/`work_board` 等)は入力を
  そのまま返すだけで I/O が一切無い。appview は Svelte ソースが無くビルド不能。
- **本物の実装は別リポ**(`gftdcojp/ai-gftd-apps-gftdcojp/60-apps/ai-gftd-project-manimani/`)に
  あり、そちらは Python LangGraph pod + 本物の Svelte kanban + WebAuthn PRF 暗号化(4,850行)。
  だがそこでも: (a) work board の plan→execute ループは 2026-06-15 の D1 移行で壊れたまま
  (pod は D1 に書くが、ローカル executor は今も旧 kotoba datomic を見ていて噛み合わない)、
  (b) passkey/CACAO の consent 検証は形式チェックのみで暗号署名検証が無い(`alg:"none"`)、
  (c) helm chart(`mitama-manimani-pool`)は 2026-06-13 の RW-decommission 掃除で
  `Chart.yaml`/`values.yaml` が既に削除され再デプロイ不能、(d) 2026-07-04 時点で
  `manimani.gftd.ai`/`lg-manimani.gftd.ai` は 522/1033 で到達不能を確認。
- overlap は概念レベル(triage inbox・decision ledger)のみで、schema/機能レベルの重複は無い
  (work board の EAV アクション状態機械・LLM分解・承認ゲートは `cloud-manimani` に対応物なし)。

オーナー判断(2026-07-05):標準リポジトリのみを退役対象とし、本番 hostname
(`manimani.gftd.ai`・`lg-manimani.gftd.ai`)も併せて退役する。work board / decision-support の
機能は cloud-manimani へ移植しない — cloud-manimani は Claude Desktop(chat/cowork/code 統合)の
設計を優先し、ai-gftd-manimani の schema を継承しない。

## Decision

1. **`gftdcojp/ai-gftd-manimani`(標準リポジトリ)を退役** — GitHub 上で archive、
   `manifest/repos.edn` / `manifest/west.yml` から entry を削除(本 PR、最小 diff)。
2. **本番 hostname を2つとも退役**: `manimani.gftd.ai/*`(Worker `magatama-m4n1m4n1`)の
   route unbind、`lg-manimani.gftd.ai` は共有 Tunnel(`cedba8e6`、chat-agent/pregel/
   lg-shinshi/ses-api と相乗り)から**このホスト名の ingress rule だけ**削除しトンネル本体は
   触らない。D1 database `ai-gftd-manimani`(uuid `4473765b-c767-4e5e-9538-0cc8a53975e6`、
   num_tables=0 で実質未使用)を drop。
3. **機能移植はしない**: decision-support(daily_digest/context_query/proactive_suggest/
   record_decision)も work board(Linear 風 kanban + plan/execute + 承認ゲート)も
   cloud-manimani へは持ち込まない。cloud-manimani の今後の設計は
   Claude Desktop 型(chat / cowork / code 統合)を優先し、ai-gftd-manimani の schema・
   T0/T1/T2 境界モデルを継承しない(必要なら独立に再設計する)。
4. **k8s 側(`lg-manimani` pod, Vultr VKE)は本 PR の範囲外** — 作業環境から当該クラスタへ
   接続不可(connection refused)のため直接 teardown できない。クラスタへ到達可能な環境での
   手動確認・削除が残作業。
5. **モノレポ内ソース(`ai-gftd-apps-gftdcojp/60-apps/ai-gftd-project-manimani/`)は本 PR では
   削除しない** — 他アプリと同居する共有モノレポであり、退役範囲は「標準リポジトリ+本番
   hostname」に限定する今回のオーナー判断に従う。デッドコードとして残置、削除は follow-up。

## Consequences

- (+) portfolio の "manimani" 実装が 4 系統 → 2 系統(OSS `manimani` + `cloud-manimani`)に収束。
  概念的重複はあったが schema 重複が無かったため、移植コストなしに退役できた。
- (+) 既に壊れていた plan/execute ループ・未検証の consent 署名・deploy 不能な helm chart を
  「動いている前提で移植する」リスクを避けられた。
- (+) cloud-manimani は ai-gftd-manimani の重い T0/T1/T2 境界モデルを背負わずに、
  Claude Desktop 型の chat/cowork/code 統合という別方向の設計に進める(2026-07-04 成熟度診断の
  P3–P5 に相当。実装は別途)。
- (−) k8s pod のクリーンアップが未完了のまま残る(到達可能な環境での手動作業が必要)。
- (−) モノレポ内ソース・lexicon・helm chart はデッドコードとして残置(follow-up 課題)。
- (−) `manimani.gftd.ai`/`lg-manimani.gftd.ai` の 522/1033 は本 ADR の判断根拠であって
  独立障害の追跡ではない — 退役完了後は「意図した停止」として扱う。

## References

- 2026-07-04 成熟度診断(`manimani`/`cloud-manimani` 実態調査、本セッションの調査結果)
- ADR-2606282000(cloud-workers-kotobase-external-storage — manimani/cloud-manimani の
  IStore ポート設計)
- ADR-2607021700(portfolio 成熟度スコアリング — cloud-manimani BMC 56/YC 40)
- ADR-2607022200(per-product-gate-instruments — cloud-manimani telemetry emitter)
- `gftdcojp/ai-gftd-manimani/CLAUDE.md`(stale, 本 ADR により superseded)
- `gftdcojp/ai-gftd-apps-gftdcojp/60-apps/ai-gftd-project-manimani/`(実体調査対象、削除は範囲外)
