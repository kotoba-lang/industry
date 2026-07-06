# ADR-2607061500: kotoba-lang/tayori — 通信文下書き actor（reply-LLM ⊣ ComplianceGovernor）

**Status**: closed(実行完了。本 ADR の実行範囲=tayori 自体の scaffold はここで
閉じる。local-manimani 側の配線は当初から本 ADR の範囲外と明記した通り、別 PR の
follow-up として残る)
**Date**: 2026-07-06
**Closed**: 2026-07-06
**Deciders**: Jun Kawasaki

## Context

local-manimani の現状は `reply_llm` 方針で email のみ「Claude が返信ドラフトを
Gmail 下書きに保存 → 送信は必ず人間」が実装済み（`src/manimani/agent.cljc` /
`agents/src/channels/email.clj`）。Slack/WhatsApp/LINE/Facebook は `investigate`
方針の指示書内で「閲覧・要約のみ、送信も投稿もしない」という文脈収集専用の扱いで、
下書き生成・送信の対象チャネルではない。文書作成（メモ/提案書等の新規生成）は
manimani 側に概念自体が無い。

オーナーの要望は「email 返信・文書作成・Slack・WhatsApp 返信を、全て同一の
PR/commit スタイル（下書き=commit、承認=merge）の draft→review→approve→
send/publish パイプラインで、`.cljc`/`.cljs` により manimani 全体として統合したい」
というもの。ADR-2607050600（manimani 系統合）は cloud-manimani の今後を
「Claude Desktop 型の chat/cowork/code 統合」優先と明記しており、本 ADR はその
follow-up にあたる。

チャネル接続（IMAP/SMTP・Slack Web API・WhatsApp Business Cloud API・文書 publish）
自体は manimani 固有のドメインではなく、`kotoba-lang/gijiroku`（ADR-2607031100）が
既に「実配布（メール/Slack/カレンダー添付等）は人間承認後に Distributor port のみが
行う」と同種のポートを予見していた通り、複数 org 消費者（local-manimani/
cloud-manimani、gijiroku、将来の cloud-itonami 等）を持つ横断能力である。org
taxonomy（ADR-2606302300）に従い `kotoba-lang` に置く。直近の同型実例
`kotoba-lang/kekkai`（結界 — coord-LLM ⊣ TailnetGovernor、コード直接参照）と
`kotoba-lang/gijiroku`（scribe-LLM ⊣ PrivacyGovernor、ADR 参照）はどちらも
actor 一式（封じ込め LLM・独立 governor・append-only 台帳・Store/Advisor/Phase
注入・langgraph-clj StateGraph・CACAO 自己発行）を単一 repo として kotoba-lang に
直接置いており、本 actor もこれに倣う（domain lib と actor repo を分割しない）。

## Decision

**新規 repo `kotoba-lang/tayori`（便り — 便り=たより、通信文という役割そのものを
指す一語ドメイン名。kekkai/kudaki/gijiroku と同型の命名）を起こし、reply-LLM ⊣
ComplianceGovernor 型の通信文下書き actor として実装する。**

1. **Channel protocol（`tayori.channel`）**: `fetch-thread` `list-new-messages`
   `send-reply!` を Email（`tayori.channel.email` — IMAP ingress/SMTP egress、
   local-manimani の既存 `channels/email.clj` を横展開・一般化した実装）/
   Slack（`tayori.channel.slack` — Events API 受信 + `chat.postMessage`）/
   WhatsApp（`tayori.channel.whatsapp` — Business Cloud API）が実装する。I/O は
   kekkai/gijiroku と同型に **注入**（`:http-fn` `:json-write` `:json-read`
   `:creds`）。`tayori.channel/mock-channel` が既定（決定的な種スレッド）。
2. **DocTarget protocol（`tayori.docport`）**: 文書作成だけは「本物の PR」にする
   ── `fetch-doc` `propose-revision!`（branch への commit）`publish!`（PR
   マージ）を `tayori.docport.git`（GitHub API、注入 `:http-fn`、superproject
   自身が west.yml pin 前進で使っている「サーバ側 single-entry commit」と同じ
   手筋）が実装する。既定は `tayori.docport/mock-doctarget`。
3. **統一データモデル（`tayori.model`）**: `thread`（channel/external-id/
   participants/tenant/status）/ `message`（thread-id/from/body/ts/direction）/
   `contact`（channel/address/consent/first-contact?）/ `draft`（thread-id/text/
   confidence/cites/redactions/status）/ `document`（target/path/tenant）/
   `revision`（document-id/diff/confidence/cites/redactions/status）。
4. **二流路の StateGraph（`tayori.operation`）** — kekkai/gijiroku と同型:
   - ingest（観測・常時 ON・LLM 無し）: `:thread/register` `:message/ingest`
     `:contact/register` `:document/register`。
   - assess（propose 経路）: `:reply/draft`・`:document/revise`（reply-LLM
     proposal: text/diff + confidence + cites + redactions、effect は
     `:draft`/`:revision` 固定）→ `:govern` → `:decide` →
     commit(=draft を thread/document に記録するだけ、送信・publish はしない)
     |escalate|hold。**draft の commit 自体は「気軽な git commit」に相当し、
     phase に応じて自動化してよい。** `:reply/send`・`:document/publish`
     （=「PR の merge」に相当）は **常に人間承認**（`interrupt-before
     #{:request-approval}`、gijiroku の `:minutes/distribute`・kekkai の
     `:node/admit`/exit-route 承認と同じ charter — phase に関わらず auto に
     入れない）。
5. **ComplianceGovernor（`tayori.governor`）の HARD 不変条件**（人間でも上書き
   不可）:
   - **no-actuation** — `:reply/draft`/`:document/revise` proposal の effect
     は `:draft`/`:revision` のみ。実送信/実 publish は人間承認後に
     Channel/DocTarget port のみが行う。
   - **redaction-required** — proposal の `:cites` が機微区分（health/legal/
     financial とタグ付けされた文脈）を `:redactions` 無しに引用したら hard
     violation（talent-actor の保護属性ゲート・gijiroku の protected-content
     redaction と同型）。
   - **consent-required**（`:reply/send`）— 宛先 contact の `:consent` が
     `:blocked` なら hard violation。`:first-contact?` な相手への送信は
     hard ではないが high-stakes（常に人間）。
   - **tenant-isolation**（`:document/publish`）— publish 先が当該 document の
     登録 `:tenant`/`:target` に属さない（未登録リポジトリ・別ドメインへの
     書き込み）なら hard violation。
   SOFT: confidence floor → escalate。`:reply/send`・`:document/publish` は
   常に high-stakes（常に人間）。
6. **Phase 0→3**: 0 = ingest-only（thread/message/contact/document の記録のみ、
   LLM 起動なし）/ 1 = assisted（draft 許可、send/publish は常に人間）/
   2 = assisted-draft（draft は clean+confident で auto commit、send/publish は
   常に人間）/ 3 = supervised（同上、**send/publish は phase に関わらず常に
   人間**）。
7. **注入 port（swap）**: Store（`MemStore` ‖ `DatomicStore`、`langchain.db`
   `:db-api`、`tayori.kotoba` で kotobase.net 実 pod へ配線）/ Advisor（mock ‖
   `langchain.model`）/ Channel（mock ‖ Email/Slack/WhatsApp 実クライアント）/
   DocTarget（mock ‖ git 実クライアント、承認後のみ呼ばれる）。
8. **CACAO 自己発行**（`tayori.cacao`、kekkai/gijiroku と同型）: actor は自分の
   Ed25519 鍵を発行し、鍵由来 IPNS を自分の graph として自己 mint。秘密鍵は
   `.tayori/identity.edn`（gitignore）。
9. **台帳 = 通信文下書き監査台帳（append-only）**。ingest/draft/govern/commit/
   escalate/send/publish の全 disposition を積み、「いつ・どのスレッド/文書の・
   どの根拠で・誰が承認して送信/publish したか」を不変に残す。

### local-manimani / cloud-manimani からの利用

両アプリは `deps.edn` に `io.github.kotoba-lang/tayori {:local/root
"../../kotoba-lang/tayori"}` を追加して in-process 消費するか、
`tayori.kotoba/kotoba-store` 経由で kotobase.net pod 上の graph を XRPC で読む。
local-manimani 側の `reply_llm` 方針は、この ADR のスキーマへ乗り換える
schema 拡張（Decision Ledger に `:channel/type` `:draft/id` `:draft/diff`
`:governor/verdict` `:approval/*` `:sent/*` を追加）を **別 PR**（local-manimani
リポジトリ側）で行う — 本 ADR は tayori 自体の scaffold のみを実行範囲とする。
製品固有の UI・通知・triage 8方針（reply_llm/todo/waiting/…）との統合は
manimani 側の責務とし、tayori は「下書き・審査・チャネル送受信・台帳」の
共通コアのみを提供する。

## Consequences

- (+) email/Slack/WhatsApp/文書作成の下書き→承認→送信/publish が1つの横断
  actor に集約され、local-manimani/cloud-manimani を含む全 org が同一契約
  （Channel port・DocTarget port・Store 契約）で消費できる。
- (+) 「draft = 気軽な commit、send/publish = 常に人間による merge」という
  PR/commit の語彙をそのまま actor の governor 語彙（propose/govern/approve/
  commit）に流用でき、オーナーが要望した「liner 的な PR/commit スタイル」を
  文書作成については **文字通りの git PR** として実現できる。
- (+) 同意記録・テナント分離・機微情報の最小開示・no-actuation が governor の
  型で構造的に強制される（LLM の幻覚や誤送信が「送信」に直結する経路が無い）。
- (+) Channel/DocTarget が protocol なので、将来チャネル追加（LINE/Discord 等）
  は実装差し替えのみで済む。
- (−) Slack/WhatsApp/GitHub の実クライアントは各社 OAuth app 登録・API token
  発行が前提で、live 結合は未検証（kekkai の kotobase.net 522 と同種の
  「オフライン契約は保証、live は creds 到着後」の既知状況）。既定は
  mock-channel/mock-doctarget による決定的 sim で動く。
- (−) local-manimani 側の `reply_llm` 方針を tayori 経由へ実際に配線する作業は
  本 ADR の範囲外（別 PR）。

## Execution(closing, 2026-07-06)

| 項目 | 状態 | 備考 |
|---|---|---|
| repo scaffold(Channel/DocTarget port・統一データモデル・StateGraph・governor・phase・store・CACAO 自己発行) | ✅ 完了 | initial commit `bc31bff0` |
| `kotoba-lang/tayori` GitHub repo 作成・push(public) | ✅ 完了 | `gh repo create` + `git push`、CI(lint/test)green |
| manifest 登録(`repos.edn` + `west.yml --entry tayori`、pin 検証) | ✅ 完了 | pin == repo HEAD をサーバ側検証で確認 |
| 独立レビュー(8 finder angle: 正誤3・cleanup3・altitude・CLAUDE.md conventions) | ✅ 完了 | confirmed 6 件(governor の subject 未存在チェック欠落・consent 判定が先頭 participant のみ・fail-open な未知 op・commit 前に store を書く順序バグ・GitHub Contents API の sha 欠落・email list-new-messages の重複取り込み) |
| 上記 6 件の修正 + regression test 追加 | ✅ 完了 | commit `bf44f3f2`。28 tests / 99 assertions(新規7件)、lint clean、sim 再検証 |
| manifest pin 前進(`bc31bff0`→`bf44f3f2`) | ✅ 完了 | `--entry tayori` 最小 diff、サーバ側検証 OK |
| superproject `main` 反映 | ✅ 完了 | 並行セッションとの race を検知(shallow 偽陽性ではなく本物の diverge を GitHub API で確認) → feature branch 経由の `gh api .../merges` サーバ側マージ(`c6dcf96c`)でローカル rebase/force を使わず解消 |
| local-manimani 側 `reply_llm` の tayori 配線(Decision Ledger schema 拡張) | ⏸ 範囲外のまま | 当初から別 PR と明記(Decision セクション「local-manimani/cloud-manimani からの利用」参照)。tracked as follow-up |

残 1 件(local-manimani 配線)は「本 ADR の意図的な非対象」であり本 ADR の未完了ではない。着手する際は新規 ADR または本 ADR への追記のどちらでもよいが、tayori 側の Channel/DocTarget/Store 契約は本 ADR の decision がそのまま正本であり続ける。

## References

- `orgs/kotoba-lang/kekkai` src/docs（同型 actor の直近の手本 — governor/
  operation/store/phase/cacao の構造をそのまま踏襲）
- ADR-2607031100（kotoba-lang/gijiroku — メール/Slack Distributor port の
  先行予見、Phase 0→3 の型）
- ADR-2606302300（org taxonomy — 横断基盤は kotoba-lang）
- ADR-2607050600（manimani 系統合 — cloud-manimani 今後は Claude Desktop 型
  chat/cowork/code 統合を優先、work board 等は移植しない）
- `90-docs/business/cloud-manimani-business-model.md`（UVP: 受信 queue→方針
  選択→Decision Ledger→agent loop の個人 OS）
- ADR-2606272330（新規 project 一気通貫登録の実例・恒久承認）
- 本 ADR とペアの .edn
