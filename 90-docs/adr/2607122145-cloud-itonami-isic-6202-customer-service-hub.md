# ADR-2607122145: `cloud-itonami-isic-6202`（Computer consultancy and computer facilities management activities、CRM連携カスタマーサービスハブactor）を新規実装

- Status: Accepted (2026-07-12)
- 関連: `cloud-itonami-isic-5820`（RevOps-LLM ⊣ SubscriptionGovernor、
  この build を自身の `docs/business-model.md` sibling-actor roadmap で
  名指ししている直接の根拠）、ADR-2607121900（`cloud-itonami-isic-5820`
  自身の実装ADR）、`cloud-itonami-isic-6209`（TicketRouter-LLM ⊣
  TicketGovernor、形は似るが業務モデルが異なる sibling、差別化対象）、
  `cloud-itonami-isic-8220`（call centre、これも異なる業務モデル、
  差別化対象）、ADR-2607071351（`cloud-itonami-isic-6920`、double-guard
  を dedicated boolean で行う設計の教訓元）、`kotoba-lang/crm`（この build
  が再利用した技術commons、2件目の consumer）、langgraph-clj ADR-0001

## Context

`cloud-itonami-isic-5820`（ADR-2607121900 で実装、商用CRM/サブスクリプション
商取引actor）は自身の `docs/business-model.md` "Sibling-actor roadmap" 節で、
未着手の sibling actor 2件を明記していた:

1. marketing-automation hub（campaigns/email sequences/lead scoring）
2. **customer-service hub（support cases, SLAs, knowledge base）** —
   「`cloud-itonami-isic-6209` は既に IT-managed-services/helpdesk
   ticket routing を対象にしているが、CRM連携カスタマーサービスハブは
   account/subscription を意識した別のsibling actorであり、6209の
   重複ではない」と明記。

本ADRは(2)を実装したもの。`kotoba-lang/industry` registry
（`resources/kotoba/industry/registry.edn`）を調査した結果、ISIC 6201
（"Computer programming activities"）は marketing-automation sibling
（(1)の実装、`cloud-itonami-isic-6201`、並行して別セッションが
promotion 中と判明——`kotoba-lang/crm`のREADMEが既に "cloud-itonami-isic-6201
(marketing-automation SaaS platform business ...) uses pipeline and
leadscore" と記載していたことから確認）に予約されており、6209/8220 は
既に別の業務モデルで claimed 済みだったため、customer-service hub には
別の ISIC コードを選定する必要があった。

## Decision

1. `kotoba-lang/industry` registry を調査し、ISIC division 58/62/63
   （software/IT/data）と 82（business support）の 4-digit class を
   全数チェック。5820（claimed, CRM）・6201（marketing-automationに予約
   済み、並行agentが実装中）・6209（claimed, IT-helpdesk ticket
   routing）・6311/6312/6399（claimed, 別業務）・8219/8220/8291/8299
   （claimed, 別業務）を除外した結果、未claimedの `:spec` entry の中で
   ISIC Rev.4 **6202「Computer consultancy and computer facilities
   management activities」** を採用。UNSD の公式 explanatory note
   （classification detail, code 6202）が明記する "provision of on-site
   management and operation of clients' computer systems and/or data
   processing facilities, **plus related support services**" のうち
   "related support services" を、ホスト型・マルチテナントの
   customer-service-hub SaaS（clientの support desk を facility として
   運用する形態）として narrow した。他の未claimed候補（6391/8211/8230/
   8292/8412）は業務内容が一致せず棄却（下記 Alternatives 参照）。
2. `cloud-itonami-isic-6202` を ISIC Rev.4 6202 の広い分類のまま実装
   せず、**CRM/subscription 連携カスタマーサービスハブ SaaS プラット
   フォーム事業**（HubSpot Service Hub/Salesforce Service Cloud
   クラス）に narrow する。SupportOps-LLM を封じ込めた sealed advisor
   とし、独立した ServiceGovernor が case status 遷移確定・KB 公開・
   開示・紛争解決を検閲する。単一不変条件は他の全actorと同じ形:
   SupportOps-LLM は ServiceGovernor が拒否する actuation を決して
   行わない。
3. domain-unique HARD チェック2つ（このfleetで新規）: `sla-tier-gate`
   （case status 遷移が account の有効な subscription/SLA tier より速い
   応答時間コミットを行おうとしたら拒否 — -isic-5820 の
   entitlement-scope-gate と同型だが対象は feature/seat ではなく応答
   時間）、`case-status-sequence-gate`（case ライフサイクルのスキップ/
   逆行を拒否 — `kotoba-lang/crm` の `kotoba.crm.pipeline` を再利用、
   2件目の consumer）。`double-close-gate` は ADR-2607071351 の教訓
   （status-lifecycle バグ）を踏まえ `:status` 値ではなく専用
   `:closed?` boolean で判定する。SOFT gate 3つ: `kb-embargo-gate`
   （このfleetで新規の check kind — エンバーゴ済み KB 記事の公開提案は
   確信度に関わらず常に人間承認）、`refund-commitment-gate`（返金/
   クレジットを示唆する case コミットメントは常に人間承認）、
   `dispute-request`（無条件、どの phase でも auto-commit しない）。
4. `kotoba-lang/crm` の `kotoba.crm.pipeline`（-isic-5820が新規切り出し
   した技術commons）を case ライフサイクル（`:new → :in-progress →
   :resolved → :closed` + `:cancelled` exit）に再利用。`kotoba.crm.revrec`/
   `kotoba.crm.leadscore` は本actorの責務外（revenue recognition/lead
   scoring不要）なので採用しない。
5. `kotoba-lang/industry` registry の `"6202"` エントリを `:maturity
   :spec`（stale な `gftdcojp/cloud-itonami-J6202` placeholder付き）から
   `:implemented` に昇格し、`:repo`/`:business-id` を実際に作成した
   `cloud-itonami/cloud-itonami-isic-6202` に更新した。GitHub Contents
   API（`sha=`指定PUT）による atomic single-commit で着地、当該entryの
   行のみの diff（46行）であることを事前に確認済み。fleet-wide
   `:maturity :implemented` count は 143 → 144（本ADR起票時点の
   registry-verify.edn 集計、並行 promotion により流動的）。
6. **west/manifest 登録は行わない** — 既存140超の
   `cloud-itonami-isic-####` 実装済みactorのいずれも、この
   superproject の `manifest/west.yml` には登録されていない（ADR-2607121900
   で確認済みの慣行を継承）。本ADRもこの慣行に倣う。

## Consequences

- (+) `kotoba-lang/industry` registry 6202 スロットが実装へ昇格し、
  `cloud-itonami-isic-5820` 自身のロードマップが名指しした2件の sibling
  actor のうち customer-service hub を完了。
- (+) narrowing 判断を UNSD explanatory note の実文言引用で明記
  （恣意的な relabeling を回避）。
- (+) `sla-tier-gate`/`case-status-sequence-gate`/`kb-embargo-gate` は
  このfleetの check-kind 語彙への genuine な追加。
- (+) `kotoba.crm.pipeline` の2件目の consumer として、
  `kotoba-lang/crm`「業務モデルごとに actor を分け、技術的な共通は
  kotoba-lang に置く」という設計方針が実証された。
- (+) `MemStore` ‖ `DatomicStore` parity は
  `test/svcdesk/store_contract_test.clj` で証明。テスト全パス・lint
  clean（下記 Verification Notes）。
- (-) R0 は support-case + SLA-entitlement + knowledge-base governance
  のみ。marketing-automation hub は別ADR/別 promotion（並行agent担当）。
- (-) SLA governance は単一の応答時間コミットメント check のみ、KB
  governance は `:embargoed?` boolean のみ（詳細は `docs/business-model.md`
  「Honest scope (R0)」参照）。
- (-) 本 superproject の `manifest/west.yml` には登録しないため、
  `west update` では取得されない（既存fleet慣行どおり、独立 clone/`gh
  repo clone` が必要）。

## Alternatives considered

| Option | Verdict | Reason |
|---|---|---|
| ISIC 6209（Other information technology and computer service activities） | ❌ | 既に IT-helpdesk ticket-routing で claimed（`cloud-itonami-isic-6209`、`:maturity :implemented`）。account/subscription エンティティを一切持たない別業務モデル |
| ISIC 8220（Activities of call centres） | ❌ | 既に call-centre staffing/BPO で claimed。ソフトウェア製品ではなく人材派遣サービス業で業務モデルが根本的に異なる |
| ISIC 6201（Computer programming activities） | ❌ | registry上 `:maturity :spec` で未claimedだったが、`kotoba-lang/crm` の README が marketing-automation actor（`cloud-itonami-isic-6201`）の予約先と明記済みで、並行agentが実装中と判明。customer-service hubとは異なる業務モデル |
| ISIC 8211（Combined office administrative service activities） | ❌ | 公式定義は reception/財務計画/請求書処理/郵便業務等の一般オフィス事務代行であり、support-case/SLA/KB を持つカスタマーサービスSaaSとは業務モデルが一致しない |
| ISIC 6391（News agency activities）/8230（Organization of conventions and trade shows）/8292（Packaging activities）/8412（Regulation of health/education activities） | ❌ | 業務内容がカスタマーサービスハブと無関係 |
| ISIC 6202（Computer consultancy and computer facilities management activities）— 採用 | ✅ | UNSD explanatory noteが明記する "on-site management and operation of clients' computer systems ... plus **related support services**" を、ホスト型・マルチテナントのcustomer-service-hub SaaSとして narrow できる。未claimedの`:spec` entryの中で、IT/software領域かつ「サポートサービス」という語句を公式定義が明示的に含む唯一の候補 |
| CRM(5820)/marketing(6201)/customer-service を1つの actor にまとめる | ❌ | オーナー指示「business modelごとに設計」、このfleetの one-business-model-per-actor 規律に反する |
| pipeline ロジックを actor 内に private に留める | ❌ | `kotoba-lang/crm` の既存設計方針（-isic-5820が明記）に反する |
| double-close を `:status` 値だけで判定 | ❌ | ADR-2607071351 で確認済みの status-lifecycle バグと同じ罠 |
| west.yml へ superproject 登録する | ❌ | 既存140超の実装済みactorが一貫して未登録。fleet慣行からの逸脱になる |

## References

- `cloud-itonami-isic-5820/docs/business-model.md`（この actor を
  sibling-actor roadmap で名指しした直接の根拠）
- ADR-2607121900（`cloud-itonami-isic-5820` 自身の実装ADR、直接の手本）
- `cloud-itonami-isic-6209/docs/business-model.md`（差別化対象、直接比較）
- `cloud-itonami-isic-8220/docs/business-model.md`（差別化対象、直接比較）
- ADR-2607071351（`cloud-itonami-isic-6920`、double-guard 設計の教訓元）
- UNSD ISIC Rev.4 Classification Detail, code 6202
  (https://unstats.un.org/unsd/classifications/Econ/Structure/Detail/EN/27/6202)
- `kotoba-lang/crm`（この build が再利用した技術commons）
- `cloud-itonami-isic-6202/docs/adr/0001-architecture.md`（このactor自身の
  authoritative architecture record）
- `kotoba-lang/industry` `resources/kotoba/industry/registry.edn`
  （fleet-wide maturity registry）

## Verification Notes

- `cloud-itonami-isic-6202`: `clojure -M:dev:test` 33 tests / 110
  assertions、0 failures / 0 errors。`clojure -M:lint`（clj-kondo）0
  errors / 0 warnings。`clojure -M:dev:run` の12-operation demoで
  HARD gate 6種・SOFT/always-escalate gate 3種すべての発火を確認。
- 二重クローズ（`:closed?` boolean 経由の正しいガード）を
  `double-close-violation-is-held` テストで個別に検証済み。
- `kotoba-lang/industry`: registry.edn を GitHub Contents API 経由の
  atomic single-commit（`619b02e7e00f159f1a10f0ccbc00c56ef4903fc1`）で
  更新。事前に diff を46行（6202 entryのみ）に限定して確認、他の並行
  promotion（marketing-automationのADR-2607122130等）と衝突しないことを
  確認済み。
- リポジトリを GitHub へ push 済み・public:
  `github.com/cloud-itonami/cloud-itonami-isic-6202`。
