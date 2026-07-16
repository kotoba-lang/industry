# ADR-2607122100: `cloud-itonami-isic-6201`（Computer programming activities、HubSpot Marketing Hub/Salesforce Marketing Cloudクラスのマーケティングオートメーション actor）を `:implemented` へ深化

- Status: Accepted (2026-07-12)
- 関連: ADR-2607121900（`cloud-itonami-isic-5820`、Sales/Subscription core、
  直接のsibling)、`cloud-itonami-isic-7310`（広告代理店actor、区別対象)、
  ADR-2607071351（`cloud-itonami-isic-6920`、double-guard の教訓元)、
  `kotoba-lang/crm`（この build で `kotoba.crm.leadscore` を追加)

## Context

ADR-2607121900 で `cloud-itonami-isic-5820`（Sales/Subscription core CRM
actor）を実装した際、フルパリティのロードマップとして
marketing-automation・customer-service の各 hub を将来の sibling actor
として明記していた。本ADRはそのうち marketing-automation hub
（HubSpot Marketing Hub / Salesforce Marketing Cloud クラス）を実装する。

オーナー指示（引き続き有効）: (1) business model ごとに責任・権限を
`cloud-itonami-*` actor へ分ける、(2) 技術的な共通ロジックは
`kotoba-lang` org に置く。

## Decision

1. ISIC 6201（"Computer programming activities"、広い n.e.c. コード）を
   マーケティングオートメーションSaaSプラットフォーム事業に narrow する。
   `cloud-itonami-isic-7310`（広告代理店サービスactor、AdOps-LLM ⊣
   Campaign Governor — クライアント向けに広告を購入・出稿するサービス業）
   とは明確に別スコープであり、また `cloud-itonami-isic-5820`（Sales/
   Subscription commerce、別のbusiness model sibling）とも統合しない。
2. **MarketingOps-LLM** を封じ込めた sealed advisor とし、独立した
   **ConsentGovernor** が全ての送信確定・stage遷移・score更新を検閲する。
   単一不変条件は他の全actorと同じ形。
3. domain-unique HARD チェック(このfleetで新規): `consent-revoked-send-gate`
   — 「authorization-to-contact 妥当性チェック」という genuinely new な
   check kind（arithmetic でも party-screening でも engagement-type でも
   entitlement-scope でも stage-sequence でもない）。consent-status が
   `:opted-in` でない、または `:unsubscribed?` フラグが立っている contact
   への送信を拒否する。実規制根拠: CAN-SPAM Act (15 U.S.C. §7704、
   unsubscribe遵守・送信者識別)、EU GDPR Art. 6(1)(a) + Art. 7（撤回可能な
   適法根拠としての同意）、Canada's CASL (S.C. 2010, c. 23、明示/黙示同意・
   unsubscribe機構の義務化)。`:consent-status`/`:unsubscribed?` という
   専用factで判定し、`:lifecycle-stage` から推測しない
   （`cloud-itonami-isic-6920`のstatus-lifecycleバグ、ADR-2607071351、と
   同じ失敗モードを踏まえた設計）。
4. `stage-sequence-gate` は `kotoba-lang/crm` の `kotoba.crm.pipeline` を
   再利用（reimplementしない）— lead lifecycle stage
   `[:subscriber :lead :mql :sql :customer]`、exit-stages
   `#{:unsubscribed :bounced :disqualified}`。
5. `double-send-gate` は専用 `:sent?` boolean fact（campaign, contact
   ペアごと）を使用し、`:status` 値では判定しない（同じ6920の教訓、
   5820のdouble-close-gateと同じ設計判断）。
6. `lead-score-mismatch`（SOFT、常時escalate）は `kotoba-lang/crm` に
   新規追加した `kotoba.crm.leadscore`（固定加重ポイントモデルによる
   pure ground-truth recompute: email-open=1, email-click=3,
   pricing-page-view=5, form-fill=10, demo-request=20。ASC 606/IFRS 15
   のような外部標準は存在しないため、この namespace 自身が定義する
   documented spec と明記）を使い、proposed score を常に recompute と
   照合する。
7. `kotoba-lang/crm` は5820に続き2件目の消費者を得て「技術的な共通は
   kotoba-lang に置く」というオーナー方針を実証。
8. `kotoba-lang/industry` registry の `"6201"` エントリを `:maturity :spec`
   → `:implemented` に昇格。ADR起票までの並行promotionでfleet-wide
   maturityは142まで進んでいたため、142 → 143 implemented（前回同様
   GitHub Data API による atomic single-commit で着地）。
9. customer-service hub（Salesforce Service Cloud/HubSpot Service
   Hub クラス）は引き続き未着手の次期sibling actorとして
   `cloud-itonami-isic-6201`自身のADR-0001に明記する。

## Consequences

- (+) `kotoba-lang/industry` registry 6201 スロットが実装へ昇格。
- (+) narrowing判断（7310・5820との違い）を明記し、redundancyを回避。
- (+) `consent-revoked-send-gate` はこのfleetのcheck-kind語彙への
  genuineな追加（authorization-to-contact妥当性という新カテゴリ）。
- (+) `kotoba-lang/crm`が2件目の消費者を得て技術commons化の効果を実証。
  `kotoba.crm.pipeline`をゼロから再実装せず再利用できた。
- (+) 両repoともテスト全パス・lint clean（下記Verification Notes）。
  test-authoring段階でphase-gate周りのテスト設計ミス（phase 1で
  `:lead/update-score`が無効なことを見落とし）を出荷前に自己発見・修正。
- (-) marketing-automation hubはメール送信・stage遷移・スコアリングの
  中核のみ(R0)。マルチステップnurtureシーケンスの自動トリガー、
  A/Bテスト、ランディングページビルダー等は対象外。
- (-) customer-service hub は引き続き未着手。

## Alternatives considered

| Option | Verdict | Reason |
|---|---|---|
| ISIC 6201 の代わりに 7310 (Advertising) を使う | ❌ | 7310は既に実装済みの広告代理店actor(サービス業)が占有。マーケティングオートメーションSaaSプラットフォームとは別業態 |
| cloud-itonami-isic-5820 に統合 | ❌ | オーナー指示「business modelごとに設計」に反する。5820自身のADRで既にmarketing-automationをsibling actor候補として明示的に分離済み |
| kotoba.crm.leadscoreをactor内にprivateで実装 | ❌ | オーナー指示「技術的な共通はkotoba-lang org に」に反する。将来のcustomer-service hub等が再利用できなくなる |
| lead-scoreの外部標準を探して引用する | ❌ | revrecのASC606/IFRS15と異なり、lead-scoringに公的な標準は存在しない。「存在しない標準を捏造しない」というfleet規律に従い、自己定義のdocumented specと正直に明記 |

## References

- ADR-2607121900（`cloud-itonami-isic-5820`、直接のsibling・フルパリティ
  ロードマップの出典）
- ADR-2607071351（`cloud-itonami-isic-6920`、double-guard設計の教訓元）
- `cloud-itonami-isic-6201/docs/adr/0001-architecture.md`（このactor自身の
  authoritative architecture record）
- `kotoba-lang/crm`（`kotoba.crm.leadscore`をこのbuildで追加）
- `kotoba-lang/industry` `resources/kotoba/industry/registry.edn`
  （fleet-wide maturity registry）

## Verification Notes

- `kotoba-lang/crm`（leadscore追加分）: `clojure -M:test` 16 tests /
  52 assertions、0 failures。`clojure -M:lint` 0 errors / 0 warnings。
  commit `e89d90672524c47153747415733a39ef425a1382`、push済み。
- `cloud-itonami-isic-6201`: `clojure -M:dev:test` 31 tests /
  114 assertions、0 failures。`clojure -M:lint` 0 errors / 0 warnings。
  sim run(`clojure -M:dev:run`)で10件のデモopが設計どおりに動作
  （正常送信・opted-out/expired/unsubscribed各ケースの拒否・二重送信拒否・
  stageスキップ拒否・正常stage前進・score一致・score不一致時escalate・
  権限外role拒否を確認）。commit `b6cad1bb0e77c222f4d58092ee4d308ae7249fdd`、
  push済み(`github.com/cloud-itonami/cloud-itonami-isic-6201`)。
- `kotoba-lang/industry`: registryとtest両方を142→143 implementedへ
  atomic single-commit（`b73178ac9c2fa7361ae3526402cfd0c5f7d6ae44`）で
  着地。
