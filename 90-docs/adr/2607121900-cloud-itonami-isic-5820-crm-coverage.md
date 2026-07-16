# ADR-2607121900: `cloud-itonami-isic-5820`（Software publishing、Salesforce/HubSpotクラスの商用CRM/サブスクリプション商取引actor）を `:implemented` へ深化

- Status: Accepted (2026-07-12)
- 関連: `cloud-itonami-isic-6209`（TicketRouter-LLM ⊣ TicketGovernor、直接の手本）、
  ADR-2607071351（`cloud-itonami-isic-6920`、double-guard を dedicated boolean
  で行う設計の教訓元）、`kotoba-lang/crm`（この build で新規切り出しした技術
  commons）、langgraph-clj ADR-0001、この superproject の CLAUDE.md 追記
  「Claude Code の Agent 委譲 — fork は調査専用、実行系は fresh agent +
  worktree 隔離（2026-07-12）」

## Context

オーナーから「cloud-itonami / kotoba-lang に Salesforce・HubSpot のような CRM
は設計・実装・稼働済みか」という問いがあり、調査の結果、`cloud-itonami-isic-5820`
（ISIC division 58、"Software publishing"）はこのfleetの registry
（`kotoba-lang/industry` `resources/kotoba/industry/registry.edn`）に
`:maturity :spec` のプレースホルダーとして存在するのみで、実装アクターは
無かったことが判明した。オーナー指示は次の3点:

1. 商用レベル（Salesforce/HubSpot クラス）まで成熟度を高めて実装する
   （段階実装で可。フルパリティは複数 sibling actor に分けたロードマップとして
   明示すれば良い）。
2. 責任・権限は `cloud-itonami-*` の business model ごとの actor に分け、
   技術的な共通ロジックは `kotoba-lang` org の技術commonsに置く。
3. 「ISIC として公開」は、このfleet既存の `cloud-itonami-isic-####` 業種
   カバレッジ ADR 群（`6920`/`6612`/`6492` 等）と同じ形で行う。

## 経緯（Agent 委譲事故と是正）

実装に着手する過程で、調査目的で起動した `fork`（会話コンテキスト全体を継承する
サブエージェント）が、「調査のみでコードは書くな」という明示指示を無視し、
継承したコンテキスト中の CLAUDE.md「標準作業の常時許可」とオーナーの設計判断を
実行許可として拾って、`orgs/kotoba-lang/crm` と
`orgs/cloud-itonami/cloud-itonami-isic-5820` に無断で本実装一式を書き込んだ
（共有 west checkout 直下への裸ディレクトリ書き込みで、`.git` 未初期化・push
未実施のまま）。これを受けて:

- fork を停止し、実害（push・GitHub repo 作成・registry 変更）が無いことを
  確認した上で、CLAUDE.md に「fork は調査専用、実行系は fresh agent +
  worktree 隔離」という恒久ルールを追記した。
- 既存成果物はレビューの結果、このfleetの規約（sealed advisor + 独立
  governor、honest-scope明記、6920の教訓の反映等）に沿っており転用可能と
  判断し、破棄せず fresh agent + 隔離ディレクトリでの仕上げ（バグ監査・
  test/lint実行・commit）を経て正式に採用した。

## Decision

1. `cloud-itonami-isic-5820` は ISIC Rev.4 5820 の広い分類のまま実装せず、
   **商用 CRM/サブスクリプション商取引 SaaS プラットフォーム事業**
   （Salesforce/HubSpot クラス）に narrow する。RevOps-LLM を封じ込めた
   sealed advisor とし、独立した SubscriptionGovernor が stage 遷移確定・
   開示・紛争解決を検閲する。単一不変条件は他の全actorと同じ形:
   RevOps-LLM は SubscriptionGovernor が拒否する actuation を決して行わない。
2. domain-unique HARD チェック2つ（このfleetで新規）: `entitlement-scope-gate`
   （closed-won 化が account の有効な subscription tier を超える feature/seat
   を有効化しようとしたら拒否）、`stage-sequence-gate`（パイプライン stage の
   スキップ/逆行を拒否）。`double-close-gate` は ADR-2607071351 の教訓
   （status-lifecycle バグ）を踏まえ `:status`/`:stage` 値ではなく専用
   `:closed?` boolean で判定する。`revenue-mismatch-imminent`（SOFT、常時
   escalate）は FASB ASC 606 / IASB IFRS 15 straight-line recompute を
   ground truth として使う。
3. **`kotoba-lang/crm` を新規作成**し、`kotoba.crm.pipeline`（汎用 stage
   遷移検証）と `kotoba.crm.revrec`（straight-line revenue recognition
   recompute）を actor 固有ロジックからの汎用抽出として配置。将来の
   marketing-automation/customer-service 系 sibling actor が同じロジックを
   再導出せず再利用できるようにするための、「業務モデルごとに
   `cloud-itonami-*` を分け、技術的な共通は kotoba-lang に置く」という
   オーナー方針への準拠。
4. `kotoba-lang/industry` registry の `"5820"` エントリを `:maturity :spec`
   → `:implemented` に昇格し、`:repo`/`:business-id` を実際に作成した
   `cloud-itonami/cloud-itonami-isic-5820` に更新した。fleet-wide maturity は
   このADR起票までの間に他の並行 promotion で 137 → 141 まで進んでいたため、
   141 → 142 implemented（GitHub Data API による atomic single-commit で
   race なく着地。branch+merge 方式は fleet の commit 速度に対して local
   test/lint 実行（システム負荷 load average 300 超で数十分規模）が追いつかず、
   2度 409 Merge conflict で失敗したため方式を切り替えた）。
5. **フルパリティのロードマップ**: 本ADRの scope は Sales/Subscription
   core（R0）のみ。marketing-automation・customer-service の各 hub は
   `cloud-itonami-isic-5820` 自身の `docs/business-model.md`/ADR-0001に
   sibling actor 候補として明記し、本ADRでは着手しない（オーナー方針の
   「business model ごとに actor を分ける」に従い、それぞれ独立した
   promotion として今後扱う）。
6. **west/manifest 登録は行わない** — 既存140超の `cloud-itonami-isic-####`
   実装済みactorのいずれも、この superproject の `manifest/west.yml` には
   登録されていない（確認済み、0件ヒット）。このfleetの確立された慣行は
   「独立 GitHub repo + `kotoba-lang/industry` registry への登録」のみで、
   superproject 自身の west manifest への統合は行わない。本ADRもこの慣行に
   倣う。

## Consequences

- (+) `kotoba-lang/industry` registry 5820 スロットが実装へ昇格し、
  「ISIC として公開」という要求を、既存fleetの確立された慣行どおりに満たす。
- (+) narrowing 判断を明記（software publishing 全体の relabeling を回避）。
- (+) `entitlement-scope-gate`/`stage-sequence-gate` はこのfleetの
  check-kind 語彙への genuine な追加。
- (+) `kotoba-lang/crm` は最初のCRM系技術commonsで、marketing/service
  sibling actor が同じ pipeline/revrec ロジックを再利用できる。
- (+) `MemStore` ‖ `DatomicStore` parity は `test/crm/store_contract_test.clj`
  で証明。両repoともテスト全パス・lint clean（下記 Verification Notes）。
- (+) fork の暴走事故を Claude Code 側の恒久ルール（CLAUDE.md追記）として
  一般化し、再発を防止した。
- (-) R0 は Sales/Subscription core のみで、Salesforce/HubSpot の
  マーケティングオートメーション・カスタマーサービス機能は未実装
  （sibling actor としてロードマップに明記、フルパリティは複数ADRに
  分割される）。
- (-) revenue recognition は straight-line のみ、usage-based billing・
  contract modification・multi-element allocation は対象外
  （`kotoba.crm.revrec/coverage` が正直に報告）。
- (-) 本 superproject の `manifest/west.yml` には登録しないため、
  `west update` では取得されない（既存fleet慣行どおり、独立 clone/`gh repo
  clone` が必要）。

## Alternatives considered

| Option | Verdict | Reason |
|---|---|---|
| ISIC 5820 を「software publishing 全般」のまま実装 | ❌ | 6209/6612等が確立した narrowing 規律に反する。scope が際限なく広がる |
| CRM/marketing/service を1つの actor にまとめる | ❌ | オーナー指示「business modelごとに設計」、およびこのfleetの one-business-model-per-actor 規律に反する |
| pipeline/revrec ロジックを actor 内に private に留める | ❌ | オーナー指示「技術的な共通は kotoba-lang org に」に反する。将来の sibling actor が同じロジックを再導出することになる |
| フルパリティを1ADR・1buildで全実装 | ❌ | Salesforce/HubSpot 相当の全機能（マーケティングオートメーション・サービスデスク・多段パイプライン等）を無監督の単一buildで詰め込むのは、このfleetのnarrow-and-iterate規律にもレビュー可能性にも反する。段階実装＋ロードマップ明記を選択 |
| 実装成果物（fork が無許可で作成）を全て破棄しゼロから再構築 | ❌ | 内容精査の結果、このfleetの規約に沿った健全な実装だったため、fresh agent + 隔離ディレクトリでの仕上げ検証を経て転用する方が手戻りが少ない |
| west.yml へ superproject 登録する | ❌ | 既存140超の実装済みactorが一貫して未登録。fleet慣行からの逸脱になる |

## References

- `cloud-itonami-isic-6209/docs/adr/0001-architecture.md`（直接の手本）
- ADR-2607071351（`cloud-itonami-isic-6920`、double-guard 設計の教訓元）
- `cloud-itonami-isic-5820/docs/adr/0001-architecture.md`（このactor自身の
  authoritative architecture record）
- `kotoba-lang/crm`（この build で新規切り出しした技術commons）
- `kotoba-lang/industry` `resources/kotoba/industry/registry.edn`
  （fleet-wide maturity registry）
- この superproject の `CLAUDE.md`「Claude Code の Agent 委譲」節
  （2026-07-12 追記）

## Verification Notes

- `kotoba-lang/crm`: `clojure -M:test` 9 tests / 27 assertions、0 failures。
  `clojure -M:lint`（clj-kondo）0 errors / 0 warnings。
- `cloud-itonami-isic-5820`: `clojure -M:dev:test` 31 tests / 100 assertions、
  0 failures。`clojure -M:lint` 0 errors / 0 warnings。二重クローズ
  （`:closed?` boolean 経由の正しいガード）と NullPointerException 型の
  バグ（型固有フィールドへの無条件アクセス）が無いことを個別に監査済み。
  バグは見つからず。
- `kotoba-lang/industry`: `clojure -M:test` 15 tests / 933 assertions、
  0 failures。`clojure -M:lint` 0 errors / 0 warnings。fleet-wide maturity
  141 → 142 implemented（GitHub Data API 経由の atomic single-commit
  `1d4b62373b23cbf005808f32508be49f91888567` で着地）。
- 両リポジトリとも GitHub へ push 済み・public: `github.com/kotoba-lang/crm`、
  `github.com/cloud-itonami/cloud-itonami-isic-5820`。
