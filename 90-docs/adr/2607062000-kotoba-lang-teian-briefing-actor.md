# ADR-2607062000: kotoba-lang/teian — 資料作成 actor（deck-LLM ⊣ BriefingGovernor）

**Status**: closed(実行完了。scaffold + 独立レビューで見つかった2件の修正、
ichiran のレビューで判明した3件目の遡及修正、実 Resend/Slack Distributor
配線まで完了。cloud-itonami 側 UI 配線は follow-up）
**Date**: 2026-07-06
**Closed**: 2026-07-06
**Amended**: 2026-07-07(ADR-2607062030 ichiran のレビューで発見された
teian 由来のバグの遡及修正、実チャネル接続)
**Deciders**: Jun Kawasaki

## Addendum(2026-07-07): 実チャネル接続(Resend / Slack)

`teian.deckport` に実配布実装を追加した:

- **Resend**（`teian.distribute/resend-distribute-fn`、commit `4c674f6`→
  merge `97161d2`）: `kotoba-lang/mailer` 経由で Resend API へ実送信。
  `slides.office` による pptx export は実際に動作することを確認
  （5769-byte の有効な pptx を生成）し、添付として送信。**実際に1通
  ライブ送信し、Resend が `delivered` を返すことまで確認済み**
  （message id は監査目的で ledger の `:tool` フィールドに記録。
  APIキー自体はログ・コミットに一切出力していない）。
- **Slack**（`teian.deckport/slack-deckport`、commit `844d435`）:
  `chat.postMessage` によるテキスト通知の実装コードは用意したが、
  Slack アプリ登録・bot token 発行はオーナー側作業のため未実行
  （README に手順を明記）。
- `mock-deckport` は既定のまま変更なし。実 Distributor はどちらも
  明示的な注入でのみ有効化される。46 tests / 154 assertions。

## Addendum(2026-07-07): 3件目のバグ — publish 配信の TOCTOU

ADR-2607062030（ichiran）の独立レビューが、teian からコピーされた
`commit-effects!` の `:deck/publish` 分岐に **同型の TOCTOU** が残っていた
ことを発見した: 2026-07-06 の修正は `teian.governor` の `:deck/publish`
hard-check が「govern 時点で」store の現在値を再検証するようにしたが、
`:request-approval` interrupt で人間承認を待つ間に store の draft が
変更された場合、`commit-effects!` 自身が **配信直前にもう一度独立に
store を再読込**しており、その再読込は governor を一切通らない
（`:govern` ノードは resume 時に再実行されない — langgraph の resume は
`:request-approval`→`:commit` に直行するため）。koyomi/shoko の
`:event/share`/`:file/share` は最初から checkpoint 済み content のみを
使う設計だったため影響を受けなかったが、teian（とそれをコピーした
ichiran）の `:deck/publish`/`:tally/publish` は govern-time の再検証を
追加しただけで、delivery 自体の再読込は温存されたままだった。

commit `1bafac1` で修正: `commit-effects!` は checkpoint 済みの
`(:content proposal)` のみを `deckport/publish!` に渡し、store の
再読込を行わない（koyomi/shoko と同型）。regression test
`publish-uses-governed-content-not-a-stale-commit-time-store-read` を
`test/teian/governor_contract_test.clj` に追加。30 tests / 108 assertions
（新規1件）、lint clean。

## Context

`cloud-itonami` は `activity → decision → effect → audit` の台帳上で法人活動を
扱う業務OSだが、営業/経営会議/取締役会向けの資料（デッキ・レポート・集計表）を
作る手段を持たない。オーナーの要望は「`kotoba-lang/slides`・`docs`・`drive`・
`calendar`・`sheets`（GFTD workspace surface — deck/doc/sheet/file/folder/event/
workbook を EDN ネイティブに保持する pure data model 群、既に実装・テスト済み）
を組み込んで資料作成・予定共有を実現する」というもの。

調査の結果 `kotoba-lang/{slides,docs,drive,calendar,sheets}` はいずれも
LLM・governor を持たない pure data model（`slides.model` の `workspace`/`deck`/
`doc`/`sheet`、`slides.office` による pptx export、README にある通り Google
Docs/Sheets・Word/Excel とのロードトリップを企図した wire format）であり、
生成・配布の**判断**を担う層が無い。「文書」（メモ/提案書の draft→承認→publish）
は `kotoba-lang/tayori`（ADR-2607061500、closed）の `DocTarget` port と役割が
完全に重複するため、本 ADR の対象外とし cloud-itonami は tayori をそのまま消費
する。残る「資料作成」（デッキ生成・配布）は tayori のモデル（git diff ベースの
テキスト文書revision）ではカバーされない構造化データ（slides の deck/sheet EDN）
であり、独立した actor が必要。

直近の同型実例 `kotoba-lang/kekkai`（結界 — coord-LLM ⊣ TailnetGovernor）と
`kotoba-lang/tayori`（便り — reply-LLM ⊣ ComplianceGovernor）はどちらも actor
一式（封じ込め LLM・独立 governor・append-only 台帳・Store/Advisor/Phase 注入・
langgraph-clj StateGraph・CACAO 自己発行）を単一 repo として kotoba-lang に
直接置いており、本 actor もこれに倣う。横断能力（cloud-itonami だけでなく将来の
cloud-manimani 等でも「資料を作って配る」需要はある）なので org taxonomy
（ADR-2606302300）に従い `kotoba-lang` に置く。

## Decision

**新規 repo `kotoba-lang/teian`（提案 — 提案書・デッキという成果物そのものを
指す一語ドメイン名。kekkai/tayori と同型の命名）を起こし、deck-LLM ⊣
BriefingGovernor 型の資料作成 actor として実装する。**

1. **DeckTarget protocol（`teian.deckport`）**: `fetch-deck` `propose-revision!`
   （下書きの commit）`publish!`（配布 — 承認後のみ）を実装する。既定は
   `teian.deckport/mock-deckport`（決定的な種デッキ）。content は
   `kotoba-lang/slides` の `slides.model`（`workspace`/`deck`/`doc`/`sheet`
   EDN）をそのまま保持する — teian は独自のデッキ表現を作らない。`publish!`
   の実体は `slides.office`（pptx export）+ Distributor port（メール/Slack
   添付、tayori/gijiroku と同型に注入）。
2. **統一データモデル（`teian.model`）**: `draft`（activity-id/kind
   `:deck|:doc|:sheet`/content(slides EDN)/confidence/cites/redactions/
   status）— tayori の `draft` 型と同型。
3. **二流路の StateGraph（`teian.operation`）** — kekkai/tayori と同型:
   - ingest（観測・常時 ON・LLM 無し）: `:artifact/register`（既存資料の登録）。
   - assess（propose 経路）: `:deck/draft`（deck-LLM proposal: slides EDN
     content + confidence + cites + redactions、effect は `:draft` 固定）→
     `:govern` → `:decide` → commit(=draft を記録するだけ) |escalate|hold。
     `:deck/publish`（=配布）は **常に人間承認**（`interrupt-before
     #{:request-approval}`、tayori の `:document/publish`・gijiroku の
     `:minutes/distribute` と同じ charter — phase に関わらず auto に入れない）。
4. **BriefingGovernor（`teian.governor`）の HARD 不変条件**（人間でも上書き
   不可）:
   - **no-actuation** — `:deck/draft` proposal の effect は `:draft` のみ。
     実配布は人間承認後に DeckTarget port のみが行う。
   - **redaction-required** — proposal の `:cites` が機微区分（財務/法務/
     人事とタグ付けされた文脈）を `:redactions` 無しに引用したら hard
     violation（tayori/gijiroku と同型）。
   - **tenant-isolation** — draft の `:tenant`/`:repo` が itonami activity の
     `:itonami.activity/repo` と不一致（未登録リポジトリ・別テナントへの
     draft 生成）なら hard violation。
   SOFT: confidence floor → escalate。`:deck/publish` は常に high-stakes
   （常に人間）。
5. **Phase 0→3**: 0 = ingest-only（artifact 登録のみ、LLM 起動なし）/
   1 = assisted（draft 許可、publish は常に人間）/ 2 = assisted-draft
   （draft は clean+confident で auto commit、publish は常に人間）/
   3 = supervised（同上、**publish は phase に関わらず常に人間**）。
6. **注入 port（swap）**: Store（`MemStore` ‖ `DatomicStore`、`langchain.db`
   `:db-api`、`teian.kotoba` で kotobase.net 実 pod へ配線）/ Advisor（mock ‖
   `langchain.model`）/ DeckTarget（mock ‖ slides.office 実 export + 実
   Distributor、承認後のみ呼ばれる）。
7. **CACAO 自己発行**（`teian.cacao`、kekkai/tayori と同型）: actor は自分の
   Ed25519 鍵を発行し、鍵由来 IPNS を自分の graph として自己 mint。秘密鍵は
   `.teian/identity.edn`（gitignore）。
8. **台帳 = 資料作成監査台帳（append-only）**。ingest/draft/govern/commit/
   escalate/publish の全 disposition を積み、「いつ・どの活動の・どの根拠で・
   誰が承認して配布したか」を不変に残す。

### cloud-itonami からの利用

`deps.edn` に `io.github.kotoba-lang/teian {:local/root
"../../kotoba-lang/teian"}` を追加して in-process 消費する。新規
`cloud_itonami.workspace` 投影層が `:itonami.effect/kind
:document/generate-deck` の effect を `teian.operation` の `:deck/draft`
request に変換し、`:deck/publish` の人間承認は既存
`cloud_itonami.approval`（ADR-0005）にそのまま乗せる（新規承認 UI は作らない）。

## Consequences

- (+) 資料作成（デッキ・レポート・集計表の下書き→承認→配布）が横断 actor に
  集約され、cloud-itonami を含む全 org が同一契約（DeckTarget port・Store
  契約）で消費できる。
- (+) `kotoba-lang/slides` の既存 EDN モデル・pptx export をそのまま再利用し、
  teian 自身は独自のデータ表現を作らない（重複実装を避ける）。
- (+) 機微情報の最小開示・テナント分離・no-actuation が governor の型で構造的
  に強制される。
- (−) 実配布（メール/Slack 添付等の Distributor）の実クライアントは各社 API
  token 発行が前提で、live 結合は未検証。既定は mock-deckport による決定的
  sim で動く。
- (−) cloud-itonami 側の re-frame UI からの起動ボタン等の配線は本 ADR の
  範囲外（別 PR）。

## Execution(closing, 2026-07-06)

| 項目 | 状態 | 備考 |
|---|---|---|
| repo scaffold(DeckTarget port・統一データモデル・StateGraph・governor・phase・store・CACAO 自己発行) | ✅ 完了 | initial commit `d51a943` |
| `kotoba-lang/teian` GitHub repo 作成・push(public) | ✅ 完了 | `gh repo create` + `git push`、CI(lint/test)green |
| manifest 登録(`repos.edn` + `west.yml --entry teian`、pin 検証) | ✅ 完了 | pin == repo HEAD をサーバ側検証で確認 |
| 独立レビュー(governor/operation/phase 中心の敵対的レビュー) | ✅ 完了 | confirmed 2 件: `MemStore.seed!` が `:artifacts` を丸ごと置換（per-id upsert 契約違反）／`:deck/publish` が draft 時のみ redaction/tenant を検証し publish 時に再検証しない（docstring は二重検証を謳うが未実装）。`:target` 未検証の指摘は plausible 止まりで、teian の artifact モデルに正本destination フィールドが無いことを確認した上で見送り（存在しないフィールドを捏造しない判断） |
| 上記 2 件の修正 + regression test 追加 | ✅ 完了 | commit `5fdd8e7`。29 tests / 99 assertions(新規4件)、lint clean |
| manifest pin 前進(`d51a943`→`5fdd8e7`) | ✅ 完了 | `--entry teian` 最小 diff、サーバ側検証 OK |
| cloud-itonami 側 `workspace.cljc` 配線 | ✅ 完了 | `:document/generate-deck`/`:document/publish-deck` effect ハンドラ、`cloud-itonami.approval` 経由で人間承認、422 tests / 2949 assertions green |
| superproject `main` 反映 | ✅ 完了 | teian/koyomi/shoko/ichiran 一括登録 commit（feature branch経由のサーバサイドmerge） |

## References

- `orgs/kotoba-lang/kekkai` src/docs（同型 actor の手本）
- ADR-2607061500（`kotoba-lang/tayori` — DocTarget port の先行実装、文書作成は
  tayori に委譲する判断の根拠）
- ADR-2607031100（`kotoba-lang/gijiroku` — Distributor port の先行予見、
  Phase 0→3 の型）
- ADR-2606302300（org taxonomy — 横断基盤は kotoba-lang）
- `orgs/gftdcojp/cloud-itonami/docs/adr/0006-gijiroku-meeting-record-integration.md`
  （cloud-itonami 側の消費パターンの先行実例）
- ADR-2606272330（新規 project 一気通貫登録の実例・恒久承認）
- 本 ADR とペアの .edn
