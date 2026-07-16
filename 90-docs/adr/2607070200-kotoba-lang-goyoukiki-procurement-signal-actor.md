# ADR-2607070200: kotoba-lang/goyoukiki — JP 政府調達 signal + bidder-matching actor（match-LLM ⊣ ProcurementGovernor）

**Status**: closed（実行完了。repo scaffold + tests green + manifest 登録 +
実データingestion(ADR-2607070300) + cloud-itonami配線(PR #34、レビュー待ち)
まで完了。US/EU/中国拡張・candidate側実データ取得・teian/tayori 側の応答
文書ドラフト配線は明記済みの follow-up）
**Date**: 2026-07-06
**Closed**: 2026-07-07
**Deciders**: Jun Kawasaki

## Context

オーナーの要望（原文要旨）: 「`cloud-itonami` で日本・米国・EU・中国などの調達
情報を分籍し、まず `kotoba-lang` として調達に必要な提案・マッチングをまとめた
public repo を作る。またこれを提案可能な団体を発見する itonami を設計する。
政府の itonami を先回りして、調査・設計・実装・提案する itonami。」

調査の結果:

1. `cloud-itonami-iso3166-{usa,chn,jpn,...}`（90 か国、ADR-2607032330）は
   既に存在するが、これは**市場参入コンプライアンスの静的 blueprint**（既に
   その国で事業を始めたい operator 向けの登録・入札制度ガイド）であり、案件⟷
   候補団体をマッチングするライブなエンジンではない。日本のみ省庁別に細分化
   （ADR-2607040100、19 省庁）、米国・中国は国単位のみ、EU 単体エントリは無い
   （個別加盟国のみ存在）。
2. UNSPSC/COFOG/GTIN blueprint（ADR-2607031600/031700/031800）は商品区分・
   政府機能区分の分類のみで、「どの vendor がどの調達案件を満たせるか」の
   cross-party matching は一切実装されていない。
3. 「procurement」を含む唯一の既存 ADR（ADR-2607062210、giemon 人型ロボット
   実体発注）は政府調達と無関係（サプライチェーン購買）。
4. 名前から予想される `kotoba-lang/teian`（提案、ADR-2607062000）は実際には
   社内資料（デッキ/文書）作成専用の actor で、入札応答文書のドラフトとは
   無関係。ただし governor パターン・Phase 0→3・port 注入・CACAO 自己発行の
   **構造テンプレートとしては最良の手本**。
5. ADR-2607051300（cross-org-capability-map）が「サプライチェーン企業が情報
   を載せ AI が分析・マッチングする」という **未着手ギャップ** として明記して
   おり、本 actor 相当のものはこれまで一切設計・実装されていない。

オーナー確認済みの4決定（AskUserQuestion）:
- **名称**: `御用聞き`（goyoukiki） — 得意先を定期訪問し、頼まれる前に用向きを
  先回りして伺う商人を指す語。「政府の itonami を先回りする」という要望に
  直接対応する命名。
- **データ範囲**: cache + redistribute（正規化データを repo 側でも保持）。
  ただし v1 が対象とする JP の入札公告・調達予定情報は「政府標準利用規約
  （2.0版）」下で公開され CC-BY 4.0 相当で再頒布可能 — このライセンス基盤が
  無ければこの選択は成立しない前提を Decision/Consequences に明記する。
- **展開範囲**: v1 は日本のみ。米国/EU/中国は別 ADR（既存
  `cloud-itonami-iso3166-{usa,chn,...}` の compliance blueprint と対になる
  形で追う）。
- **実行深度**: full standing-authorization flow（ADR→scaffold→GitHub repo
  作成・push→manifest登録、恒久承認 ADR-2606272330 に基づき本ターンで一気通貫）。

## Decision

**新規 repo `kotoba-lang/goyoukiki` を起こし、match-LLM ⊣ ProcurementGovernor
型の政府調達 signal + bidder-matching actor として実装する。**

1. **統一データモデル（`goyoukiki.model`）**: `opportunity`（:forecast=調達
   予定 または :tender=入札公告 — 先回り観測と公示後観測を同じ ground-fact
   種で扱う）、`candidate`（全省庁統一資格 登録団体）、`match`（actor 自身の
   control-plane record — 提案のみ、応答文書そのものではない）。
2. **MatchTarget protocol（`goyoukiki.matchport`）**: `fetch-match`
   `propose-match!`（提案の commit）`share!`（候補への通知 — 承認後のみ）。
   **構造的安全境界**: `share!` の宛先は常に候補団体自身の登録 `:contact`
   から解決され、発注機関を宛先にできるパラメータが protocol 上に存在しない
   — 未承諾提案・ex-parte 接触のリスク領域を型で封じる（governor のルール
   ではなく port の設計そのもので防ぐ）。
3. **二流路の StateGraph（`goyoukiki.operation`）** — teian/koyomi と同型:
   - ingest（観測・常時ON・LLM無し）: `:opportunity/register`
     `:candidate/register`。調達予定（forecast）の登録は入札公告
     （tender）の登録と全く同じ扱い — 先回り自体に特別な権限は要らない
     （常に既に公開済みの情報のみを観測する）。
   - assess（propose経路）: `:match/propose`（match-LLM proposal:
     候補団体の指名 + score + rationale + confidence + cites + redactions、
     effect は `:draft` 固定）→ `:govern` → `:decide` → commit|escalate|hold。
     `:match/share`（=候補への通知）は **常に人間承認**
     （`interrupt-before #{:request-approval}`）。
4. **ProcurementGovernor（`goyoukiki.governor`）の HARD 不変条件**:
   - **no-actuation** — `:match/propose` proposal の effect は `:draft` のみ。
   - **nomination** — proposal は具体的な candidate を指名しなければならない
     （confidence だけを信用しない）。
   - **eligibility** — 指名された candidate が opportunity の
     `:required-categories`/`:min-rank` を満たすことを検証（share 時は再検証
     — propose 承認後の適格性失効ドリフトを検知）。
   - **redaction-required** — cross-candidate の機密（他候補の非公開能力・
     価格、発注機関内部予算）を引用する場合は `:redactions` 必須。
   - **silence-period** — opportunity が沈黙期間フラグ中は `:match/share`
     が hard violation（`:match/propose` 自体は内部提案で外部効果が無いため
     影響を受けない）。官製談合防止法・入札談合等関与行為防止法が懸念する
     「競争入札の最中に情報の非対称性・調整の経路となる」リスクをこの一点で
     封じる。
   SOFT: confidence floor(<0.6) → escalate。`:match/share` は常に high-stakes。
5. **Phase 0→3**: 0=ingest-only / 1=assisted(propose常に人間) /
   2=assisted-match(propose自動commit可) / 3=supervised(同上、**share は
   phase に関わらず常に人間**)。
6. **注入 port（swap）**: Store（`MemStore` ‖ `DatomicStore`、`langchain.db`
   `:db-api`、`goyoukiki.kotoba` で kotobase.net 実 pod へ配線）/ Advisor
   （mock ‖ `langchain.model`）/ MatchTarget（mock ‖ 実 Notifier、承認後の
   み呼ばれる）。
7. **CACAO 自己発行**（`goyoukiki.cacao`、teian/kekkai と同型）: 秘密鍵は
   `.goyoukiki/identity.edn`（gitignore）。
8. **台帳 = マッチング監査台帳（append-only）**。

### 意図的にスコープ外とするもの

- **発注機関への接続** — 前述の通り protocol レベルで不可能（構造的境界）。
- **入札応答文書のドラフト** — shared match を受けて実際の提案書を書くのは
  `kotoba-lang/teian`/`tayori` の役割（既存の文書ドラフト actor を再利用し、
  重複実装しない）。配線は follow-up。
- **日本以外の国（米国/EU/中国）** — 別 ADR で `cloud-itonami-iso3166-*`
  compliance blueprint と対になる形で追う。
- **実データ取得（live fetcher）** — v1 は `goyoukiki.store/demo-data`
  （手セット/mock）のみ。JP e-Gov・入札情報公開システム・全省庁統一資格への
  実クライアント接続は follow-up（Consequences 参照）。

## Consequences

- (+) 政府調達の「案件を見つける」「適格な候補を発見する」「候補に知らせる」
  が横断 actor に集約され、teian/tayori（文書ドラフト）・
  cloud-itonami-iso3166-*（市場参入コンプライアンス）と役割分担が明確になる。
- (+) 発注機関への接続経路が protocol レベルで存在しないため、未承諾提案・
  ex-parte 接触規制のリスク領域そのものを設計で回避している。
- (+) 沈黙期間・適格性・redaction が governor の型で構造的に強制される。
- (−) v1 は日本のみ、かつ実データソースへの live 接続は未実装（mock/手セット
  データのみで動作検証済み）。実際に JP の入札情報公開システム/e-Gov API・
  全省庁統一資格を接続する際は、各ソースの利用規約（政府標準利用規約
  2.0版はCC-BY相当だが、省庁・システムによって個別規約が異なりうる）を
  接続前に個別確認すること — 本 ADR の「cache+redistribute」承認は
  JP の一般的な政府標準利用規約を前提にしており、無条件の白紙委任ではない。
- (−) 実 Notifier（候補へのメール/ポータル通知等）の live クライアントは
  各社 API/接続方式が前提で、本 repo には含めない（mock-matchport のみ）。
- (−) 米国/EU/中国への展開、teian/tayory 側の応答文書ドラフト配線は
  本 ADR の範囲外（別 PR/別 ADR）。

## Execution(closing, 2026-07-07)

| 項目 | 状態 | 備考 |
|---|---|---|
| repo scaffold（MatchTarget port・統一データモデル・StateGraph・governor・phase・store・CACAO自己発行） | ✅ 完了 | initial commit `20f1b02` |
| `kotoba-lang/goyoukiki` GitHub repo 作成・push（public） | ✅ 完了 | `gh repo create` + `git push` |
| テスト（propose-only contract・store parity・CACAO crypto） | ✅ 完了 | 31 tests / 110 assertions green |
| lint | ✅ 完了 | clj-kondo clean (0 errors, 0 warnings) |
| demo sim 実行確認（ingest→propose自動commit→share人間承認→phase0無効化→沈黙期間でshareのみ block→DatomicStore差し替え） | ✅ 完了 | 全経路期待通り動作確認 |
| manifest 登録（`repos.edn` + `west.yml --entry goyoukiki`、pin検証） | ✅ 完了 | pin == repo HEAD をサーバ側検証で確認 |
| superproject `main` 反映 | ✅ 完了 | feature branch 経由のサーバサイド merge |
| 実データ ingestion（kkj.go.jp 全省庁+団体ライブ入札公告、GEPS 全53府省コード落札実績） | ✅ 完了 | 別ADR。詳細は ADR-2607070300 参照（41 tests/150 assertions green、実データで動作確認済み） |
| `cloud-itonami` への実配線（`cloud_itonami.workspace` 投影層に `:procurement/propose-match`/`:procurement/share-match` 追加、teian/koyomi と同型） | 🟡 PRオープン・マージ待ち | `gftdcojp/cloud-itonami` PR #34（https://github.com/gftdcojp/cloud-itonami/pull/34）。cloud-itonami側テストは429 tests/2983 assertions green（追加前422/2949）。GitHub Actions が同repoで無効化されているためローカル実行で確認、CIには乗らない。本番相当の `itonami.cloud` へ影響するため自動mergeせずレビュー待ちとした |

## References

- `90-docs/adr/2607070300-kotoba-lang-goyoukiki-jp-real-ingestion-connectors.md`
  （実データ ingestion — kkj.go.jp 全省庁+団体ライブ入札公告、GEPS 全53府省
  コード落札実績オープンデータ）
- `90-docs/adr/2607032330-cloud-itonami-iso3166-market-entry-compliance-blueprints.md`
  （役割分担: あちらは市場参入コンプライアンスの静的blueprint、goyoukikiは
  案件⟷候補のライブなmatching control plane）
- `90-docs/adr/2607051300-cross-org-capability-map.md`（"サプライチェーン
  企業が情報を載せAIが分析・マッチングする"という未着手ギャップの記録。
  本 actor がその最初の実装）
- `90-docs/adr/2607062000-kotoba-lang-teian-briefing-actor.md`（governor
  パターン・Phase 0→3・port注入の構造テンプレート。文書ドラフトの委譲先）
- `90-docs/adr/2607062010-kotoba-lang-koyomi-schedule-actor.md`（同型の
  直近実装例）
- `90-docs/adr/2606302300-org-taxonomy-4-orgs.md`（横断基盤は kotoba-lang）
- ADR-2606272330（新規 project 一気通貫登録の実例・恒久承認）
- 本 ADR とペアの .edn
