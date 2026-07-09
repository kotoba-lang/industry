# ADR-2607021700: portfolio 成熟度スコアリング（BMC 成熟度 / YC bench 成熟度）

**Status**: accepted
**Date**: 2026-07-02
**Deciders**: Jun Kawasaki

## Context

ADR-2607021500 の 8 プロダクト lean canvas と ADR-2607021600 の CLI に対し、
オーナー指示（2026-07-02）: 各プロダクトの「business model canvas としての
成熟度」と「YC bench としての成熟度」をスコア化する。YC bench の先行基準は
net-kotobase `docs/GO-TO-MARKET-LEAN-YCBENCH.md`（acute problem / narrow wedge /
10x insight / founder-market fit / distribution / defensibility + 30 日 pass 条件）。

## Decision

`gftd.score`（70-tools/bmc、.cljc）として実装し、`<cli> score` / `gftd score md`
で算出・生成する。**2 軸、各 0–100**:

1. **BMC 成熟度** = 5 次元 × 0–5 の合計 / 25。
   - 自動 3 次元（canvas から機械判定）: `completeness`（9 block 完備・item 濃度）、
     `hypothesis`（riskiest 仮説 + gate 定義）、`validation`（ledger fold 済の
     hyp status: untested=0 / refuted=2（学習）/ validated=5）
   - facts 2 次元: `pricing`（価格確定度）、`grounding`（solution 実装接地度）
2. **YC bench 成熟度** = design 6 次元（YCBench 基準と同名: acute-problem /
   wedge / tenx / founder-fit / distribution / defensibility、/30 → 50%）+
   traction 3 次元（launched / users / revenue、/15 → 50%）。

主観入力は **`90-docs/business/maturity-facts.edn`**（日付つき、捏造ゼロ:
実測のない次元は低く付ける）に分離。`90-docs/business/maturity-scores.md` は
生成物（手編集禁止）。**validation が ledger 駆動**なので、`hyp pass|fail` で
検証が進むと BMC スコアが自動で動く — スコアは静的な評点でなく検証の進捗計。

## スコア（as-of 2026-07-02、初回）

| product | BMC | YC bench | 律速 |
|---|---|---|---|
| net-kotobase | 76 | 58 | 外部 paid tenant 0（billing は配線済） |
| etzhayyim | 64 | 57※ | 資金調達（寄付/助成/endowment）未着手。※非営利につき参考値 |
| cloud-murakumo | 60 | 48 | tok 単価未実測（riskiest gate 未通過）・外部需要 0 |
| cloud-itonami | 56 | 43 | wedge=2（「全業種」は楔が太い — 初期 vertical 未絞込）・外販 0 |
| app-aozora | 60 | 40 | shinshi 隣接実証はあるが aozora 自体の公開・外部ユーザー 0 |
| cloud-manimani | 56 | 40 | 価格未設計・OSS→cloud 転換未実証 |
| ai-gftd-apex | 56 | 38 | privacy premium の需要実証 0・獲得経路未着手 |
| app-aozora-yoro | 44 | 30 | 設計のみ（repo 未作成）・traction 全て 0 |

横断所見: **validation は全プロダクト 0**（riskiest 仮説が 1 つも gate を
通っていない）が BMC 側の共通律速、**revenue は全プロダクト 0–1** が YC 側の
共通律速。ポートフォリオの実収益は依然 shinshi ad 1 本（ADR-2606130000）。
最短の底上げは (1) kotobase の初 paid tenant（YCBench 30 日 pass 条件の実行）、
(2) murakumo の tok 原価実測（社内 3 アプリ移管）、(3) itonami の初期 vertical
絞り込み。

## Consequences

- (+) 8 プロダクトの成熟度が同一 rubric で比較可能になり、`hyp pass|fail` →
  スコア上昇という進化 loop（ADR-2607021600）の計器盤が付いた。
- (+) 主観 facts と機械判定の分離で「何が意見で何が事実か」が監査可能。
- (−) facts の初回値はオーナー未レビューの自己評価 — 見直しは
  maturity-facts.edn の編集 + `gftd score md` 再生成で行う。
- (−) YC bench の traction 次元は実測 telemetry（shinshi H1/H2 型）未接続 —
  follow-up で kotobase billing / murakumo run ledger から自動化。

## References

- ADR-2607021500 / ADR-2607021600
- net-kotobase `docs/GO-TO-MARKET-LEAN-YCBENCH.md`（YCBench 基準・30 日 pass 条件）
- `90-docs/business/maturity-facts.edn`（facts SSoT）/ `maturity-scores.md`（生成物）
