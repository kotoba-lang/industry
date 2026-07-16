---
id: adr-2607999990-cloud-itonami-isic-6611-cryptoexchange-maturity-verification
title: "ADR-2607999990: cloud-itonami-isic-6611-cryptoexchange の :blueprint→:implemented 昇格を検証する"
status: accepted
date: 2026-07-16
deciders:
  - Jun Kawasaki（/loop 15min「成熟度を向上」継続。registry.edn全648 entry中、
    唯一`:maturity :blueprint`のまま残っていたentryを発見。isic-0710の
    「REVERTED後、実際に検証せず放置」という失敗を教訓に、必ず実テストを
    走らせてから判断する）
related:
  - 90-docs/adr/2607141920-cloud-itonami-isic-0710-iron-ore-mining-coverage.md（"必ず実テストを実行してから完了と報告する"教訓の直接の踏襲元）
supersedes: []
superseded_by: []
last_verified: 2026-07-16
doc_type: adr
topic: cloud-itonami-financial-actor-maturity
authoritative: true
authoritative_for:
  - "cloud-itonami-isic-6611-cryptoexchange の :maturity 判定（:blueprint のまま
    据え置くか :implemented へ昇格するか）は、実際に依存関係を解決して
    テストスイートを実走した結果のみを根拠とする、という検証規律の正本"
---

# ADR-2607999990: cloud-itonami-isic-6611-cryptoexchange の :blueprint→:implemented 昇格を検証する

**Status**: accepted
**Date**: 2026-07-16
**Deciders**: Jun Kawasaki

## Context

`orgs/kotoba-lang/industry/registry.edn`（648 entry）の中で、唯一`:maturity
:blueprint`のまま残っているのが`cloud-itonami-isic-6611-cryptoexchange`
（incident-proof crypto-asset exchange, full-reserve/spot-only, ADR-2607141200,
14個のHARD invariant + 4個のsafety kernel: solvency/custody/conservation/
conflict）だった。リポジトリの実態を確認したところ、単なるscaffoldではなく
`actor.cljc`/`advisor.cljc`/`attest.cljc`/`censor.cljc`/`governor.cljc`/
`kernels/`/`ledger.cljc`/`matching.cljc`/`wysiwys.cljc`/`wysiwys_btc.clj`/
`wysiwys_eth.clj`等、matching engine・WYSIWYS署名検証を含む相当量の実装が
既に存在しており、コミット履歴にも「financial-core boundary coverage」
「adversarial truncation/robustness coverage for the WYSIWYS tx decoders」
といった実質的なテスト追加が見える——registryが単に実態に追いついていない
可能性が高い。

## Decision

**推測で昇格させない。isic-0710の教訓（一度「実装済み」と誤って昇格させ、
コンパイルエラーで数日reverted状態のまま放置された）を踏まえ、必ず以下を
実際に行ってから判定する:**

1. 依存関係（`langgraph-clj`/`langchain-store`/`merkle-sum`/`btc-crypto`/
   `eth-crypto`等、この actor 固有の複数library）を実際に解決できる
   isolated環境を構築する。
2. `clojure -M:test`（またはdeps.edn実際のalias名）を実行し、実際に
   green/redを確認する。lintも実行する。
3. **green ならば**: registryの`:maturity`を`:implemented`へ昇格し、
   実測したテスト数・アサーション数を根拠として記録する
   （isic-0710/isic-0729/isic-2670等の既存パターンと同じ形式）。
4. **red、または依存解決自体が不可能ならば**: 昇格させず、具体的に何が
   壊れているか（isic-0710の"private var" classのような具体的原因）を
   registryのコメントに正直に記録し、修正は別セッションのfollow-upとする
   ——**推測やコード上の見た目だけで「実装済み」を主張しない**。
5. 金融・safety-critical領域（14個のHARD invariant、実資金は`:real-funds-gate`
   invariantにより明示的にスコープ外——本ADRもこの境界を一切変更しない）
   であることを踏まえ、通常のmanufacturing verticalより高い検証水準
   （実際に依存解決・実テスト実行・可能ならフレッシュcloneでの再検証）を
   要求する。

## Consequences

(+) registry全体で唯一の`:blueprint`ホールドアウトが、実測に基づいて
正確な状態（:implementedへ昇格、または具体的な既知課題を伴う:blueprint維持）
に整理される。
(−) 本ADRは実装作業そのものは行わない——既存コードの検証のみ。壊れていた
場合の修正range・スコープは別途判断する。

## References

- https://github.com/cloud-itonami/cloud-itonami-isic-6611-cryptoexchange
- 90-docs/adr/2607141920-cloud-itonami-isic-0710-iron-ore-mining-coverage.md（教訓の直接の踏襲元）
