# ADR-2607031100: kotoba-lang/gijiroku — Zoom/Meet/Teams 議事録 actor（scribe-LLM ⊣ PrivacyGovernor）

**Status**: accepted
**Date**: 2026-07-03
**Deciders**: Jun Kawasaki

## Context

「cloud-itonami・cloud-manimani から使える、Zoom / Google Meet / Microsoft Teams に
アクセスして会議を録音・文字起こし・記録してくれる actor」が必要。両アプリは
gftdcojp（human-centric/business）だが、会議録音・文字起こしは特定ビジネス
ドメインに属さない **横断的な能力**（cloud-itonami の商談/職務面談記録、
cloud-manimani のトリアージ会議記録など、複数の org 消費者を持つ）。org taxonomy
（ADR-2606302300）では横断基盤 → `kotoba-lang`（language-substrate、全 org 消費）に
属する。既存の同型実例 `kekkai`（結界 — ゼロトラスト・メッシュ制御面 actor、
`orgs/kotoba-lang/kekkai`）は actor 一式（LLM 封じ込め・governor・台帳）を単一
repo として直接 `kotoba-lang` に置いており、本 actor もこれに倣う（domain lib と
actor repo を分割しない）。

actor の作法は本 workspace の同型実例 — robotaxi-actor（AR1⊣SafetyGovernor）/
gftd-talent-actor（HR-LLM⊣PolicyGovernor）/ ai-gftd-itonami（ops-LLM⊣CertGovernor）/
kekkai（coord-LLM⊣TailnetGovernor）/ ai-gftd-newscaster（anchor-LLM⊣EditorialGovernor）
— に従う: 封じ込めた知能ノードは proposal のみ返し、独立 governor が検閲、
append-only 台帳、Store/Advisor/Phase 注入、langgraph-clj StateGraph、1 run = 1 操作。

**録音取得方式の検討**: (a) 各社の公式クラウド API（Zoom Cloud Recording API /
Google Workspace Meet REST API / Microsoft Graph）を Webhook 経由で会議終了後に
pull する方式、(b) headless ブラウザ（既存の `kotoba-lang/playwright` や
`browser-agent-clj`）が参加者として bot 入室し自前 STT で録音・文字起こしする方式。
(b) はホスト側の録音/文字起こし機能有効化が不要な反面、実装が重く各社 ToS で
無許可 bot 参加を制限する場合がありレガシーリスクが高く UI 変更にも弱い。
**(a) を既定方式として採用し、bot 入室は charter 外（follow-up）とする** —
(b) が必要になった場合も既存の browser 自動化資産を Platform port の別実装として
差し込めるよう設計する（実際には差し替えない）。

## Decision

**新規 repo `kotoba-lang/gijiroku`（west path `orgs/kotoba-lang/gijiroku`）を起こし、
scribe-LLM ⊣ PrivacyGovernor 型の会議記録 actor として実装する。**
「議事録」= 会議の記録・要約という役割そのものを指す命名（kekkai/kudaki と同型の
一語ドメイン名）。

1. **Platform port（`gijiroku.platform`）**: `MeetingPlatform` protocol
   （`fetch-recording` `fetch-transcript` `list-participants` `meeting-meta`
   `verify-webhook`）を Zoom（`gijiroku.zoom` — S2S OAuth、
   `GET /v2/meetings/{id}/recordings`、`recording.completed` webhook、
   `x-zm-signature` HMAC-SHA256 検証）/ Google Meet（`gijiroku.google-meet` —
   Workspace service account、`meet.googleapis.com/v2/conferenceRecords/*`）/
   Teams（`gijiroku.teams` — Graph app-only、
   `/users/{id}/onlineMeetings/{id}/{recordings,transcripts}`）が実装する。
   I/O は kekkai.kotoba と同型に **注入**（`:http-fn` `:json-write` `:json-read`
   `:creds`）— cljc 側は zero-dep のまま、JVM 側で `jvm-http-fn` を既定提供。
   `gijiroku.mock-platform` が既定（決定的な種会議データ）。
2. **統一データモデル（`gijiroku.model`）**: `meeting`（platform/external-id/title/
   host/tenant/participants/status）/ `consent`（recording-announced?/
   participant-consents/legal-basis/jurisdiction）/ `recording`（asset-ref/
   duration/format — **生バイトは git にもストアにも入れず asset-ref のみ台帳化**、
   実体は運用時に B2 等オブジェクトストレージへ）/ `transcript`
   （segments: speaker/t0/t1/text）/ `minutes`（committed assessment）。
3. **二流路の StateGraph（`gijiroku.operation`）** — itonami/newscaster/kekkai と同型:
   - ingest（観測・常時 ON・LLM 無し）: `:meeting/register` `:meeting/status`
     `:consent/record` `:recording/fetch` `:transcript/ingest`。
   - produce（assess 経路）: `:minutes/draft`（scribe-LLM proposal: summary/
     decisions/action-items/cites/redactions）→ `:govern` → `:decide` →
     commit|escalate|hold、`:minutes/distribute`（配布）は **常に人間承認**
     （`interrupt-before #{:request-approval}`、newscaster の publish・kekkai の
     node/admit と同じ charter — phase に関わらず auto に入れない）。
4. **PrivacyGovernor（`gijiroku.governor`）の HARD 不変条件**（人間でも上書き不可）:
   - **consent-required** — 当該 meeting に `:consent/record`
     （recording-announced? true かつ legal-basis 記載）が無ければ commit 不可。
   - **tenant-isolation** — `:minutes/distribute` の宛先は当該 meeting の
     `:tenant`（cloud-itonami|cloud-manimani|…）に属する者に限る（越境漏洩防止）。
   - **protected-content redaction** — proposal の `:cites` が機微区分
     （health/legal/financial とタグ付けされた segment）を `:redactions` 無しに
     summary へ引用したら hard violation（talent-actor の保護属性ゲートと同型）。
   - **no-actuation** — scribe-LLM の effect は `:minutes`（データ record）のみ。
     実配布（メール/Slack/カレンダー添付等）は人間承認後に Distributor port のみが行う。
   SOFT: confidence floor → escalate。外部ドメイン参加者が居る会議の配布は
   high-stakes（常に人間）。`:minutes/distribute` は常に high-stakes。
5. **Phase 0→3**: 0 = ingest-only（録音/文字起こし取得のみ、LLM 起動なし）/
   1 = assisted（draft 許可、配布は常に人間）/ 2 = assisted-draft（draft は
   clean+confident で auto、配布は常に人間）/ 3 = supervised（同上、**配布は
   phase に関わらず常に人間**）。
6. **注入 port（swap）**: Store（`MemStore` ‖ `DatomicStore`、`langchain.db`
   `:db-api`、`gijiroku.kotoba` で kotobase.net 実 pod へ配線）/ Advisor（mock ‖
   `langchain.model`）/ Platform（mock ‖ Zoom/Meet/Teams 実クライアント）/
   Distributor（mock ‖ 実配布 — 承認後のみ呼ばれる）。
7. **CACAO 自己発行**（`gijiroku.cacao`、kekkai/itonami と同型）: actor は自分の
   Ed25519 鍵を発行し、鍵由来 IPNS を自分の graph として自己 mint。owner hand-off
   や共有 token は不要。秘密鍵は `.gijiroku/identity.edn`（gitignore）。
8. **台帳 = 議事録監査台帳（append-only）**。ingest/draft/govern/commit/escalate/
   distribute の全 disposition を積み、「いつ・どの会議の・どの発言根拠で・誰が
   承認して配布したか」を不変に残す。

### cloud-itonami / cloud-manimani からの利用

両アプリは `deps.edn` に `io.github.kotoba-lang/gijiroku
{:local/root "../../kotoba-lang/gijiroku"}` を追加して in-process 消費するか、
`gijiroku.kotoba/kotoba-store` 経由で kotobase.net pod 上の gijiroku graph を
XRPC で読む（Store 契約は同一のため製品側コードは backend を選ばない）。
製品固有の UI・通知・カレンダー連携は各アプリ側の責務とし、gijiroku は
「アクセス・録音・文字起こし・記録」の共通コアのみを提供する。

## Consequences

- (+) Zoom/Meet/Teams への接続・録音/文字起こし取得・議事録化・監査台帳が
  1 つの横断 actor に集約され、cloud-itonami/cloud-manimani を含む全 org が
  同一契約（Platform port・Store 契約）で消費できる。
- (+) 同意記録・テナント分離・機微情報の最小開示が governor の型で構造的に
  強制される（LLM の幻覚や誤配布が「配布」に直結する経路が無い）。
- (+) Platform port が protocol なので、将来 bot 参加方式（browser-agent-clj 等）が
  必要になっても実装差し替えのみで済む。
- (−) Zoom/Meet/Teams の実クライアントは OAuth app 登録・admin consent（Zoom
  Server-to-Server OAuth app、Google Workspace domain-wide delegation、Entra ID
  app registration）が前提で、live 結合は未検証（kekkai の kotobase.net 522 と
  同種の「オフライン契約は保証、live は creds 到着後」の既知状況）。既定は
  mock-platform による決定的 sim で動く。
- (−) 各社ネイティブの録音/文字起こし機能が組織側で有効化されていない会議は
  取得不可（bot 参加方式は charter 外・follow-up）。

## References

- `orgs/kotoba-lang/kekkai` docs/DESIGN.md・ADR-0001（同型 actor の直近の手本 —
  domain lib を分割せず kotoba-lang に単一 repo として置く前例）
- `orgs/gftdcojp/gftd-talent-actor` docs/DESIGN.md（PolicyGovernor 保護属性ゲートの手本）
- `orgs/gftdcojp/ai-gftd-itonami` docs/DESIGN.md（CACAO 自己発行の手本）
- ADR-2606272330（新規 project 一気通貫登録の実例・恒久承認）
- ADR-2606302300（org taxonomy — 横断基盤は kotoba-lang）
- 本 ADR とペアの .edn
