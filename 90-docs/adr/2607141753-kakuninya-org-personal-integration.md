# ADR-2607141753: 確認屋（kakuninya）— errand 業務の itonami.cloud（組織）/ manimani.cloud（個人）統合

**Status**: accepted, M0+M1 implemented & operating (closing 2026-07-14)
**Date**: 2026-07-14
**Deciders**: Jun Kawasaki

## Context

errand（ADR-2607141654: agent が準備し、人間が外界に触れ、その証拠で台帳が進む）
の運転を claude.ai の schedule routine に置こうとしたが、オーナー指示で方針転換:
**「確認屋業務」= 定期的な確認・催促・証拠回収は、組織のプロダクト面
（itonami.cloud / gftd org）で常設運転し、個人面（manimani.cloud / junkawasaki）
とも連携させる。** アカウント・データ・org 共有の設計を既存資産の調査に基づいて
確定する。

### 調査結果（2026-07-14、3 並列調査 + live 確認。事実ベース）

**組織側（cloud-itonami）— UI/UX はほぼ設計済み:**
- テナントモデル実在: `cloud-itonami.tenant` に org / repo / actor（:human 含む）/
  member（org×actor+role）/ **permission（capability 集合: :queue/read
  :effect/propose :effect/approve :effect/execute :audit/read :admin）**。
- **gftdcojp org は定義済み**（`tenants/gftdcojp.cljc`）: route
  `itonami.cloud/gftdcojp/gftdcojp`（:private、公開 read なし）、owner actor
  `"jun"`（全 capability、did:key は env `GFTDCOJP_OWNER_DID`）。
- 認証: CACAO + did:key が本線（`auth.cljc` が署名・時間窓・resource scope
  `kotoba://itonami/<org>/<repo>` を検証）+ edge に WebAuthn passkey。
- **承認 inbox は実在**: itonami.cloud cockpit（`site/public_cockpit.cljc`）に
  #queue / #approvals。effect は :proposed → 人間の approve/reject
  （cockpit / local shell / CLI / 常駐 tick-loop drain の 3+1 系統、CACAO 必須）。
- license-loop（ADR-2607141654）の outbox/proposal は approval.cljc の effect と
  同型 — `workspace.cljc` の teian/denrei 投影と同じ「薄い投影層」で載る。

**個人側（cloud-manimani）— 受け皿はあるがアカウントが未実装:**
- manimani = **local-first triage + Decision Ledger**（1 決定 = 1 append = 1 git
  commit。policy: reply/todo/waiting/done/archive…）。cloud-manimani は同じ台帳を
  manimani.cloud apex で配信（API: /inbox /decisions /rules。ストレージは
  net-kotobase#153 待ちの INTERIM KV）。**確認屋の個人 UX はこの triage そのもの**。
- **per-user 認証は未実装**: 共有 CACAO 1 本、account-id は「将来の seam」と明記
  された TODO。`junkawasaki` の個人スコープは存在しない。

**identity / 共有基盤:**
- **DID = アカウント**（人間/actor 対称。CACAO 自己発行、kotobase の認可単位は
  graph × auth-did のみ）。org は kotobase 層でなく tenant DID 規約
  （ADR-2607023000）+ itonami の org/repo モデル（ADR-2607022300）で表現。
- kotobase の capability/purpose-scoped redaction は**未配線**（明記済みギャップ）
  — 「org graph の一部だけを個人に共有」は今は直接できない。
- 製品横断の台帳共有語彙は **kotoba-ledger**（ADR-2607061600、append-only、
  backend file+git | kotobase pluggable。itonami は M0 で ops-ledger を dual-write 済み）。

## Decision

### 1. 確認屋 = errand の常設運転を cloud-itonami の org 面に置く

- **定期確認は claude.ai routine でなく、cloud-itonami の既存常駐運転
  （tick-loop / ops drain）に license-loop の tick を組み込む。** 出力
  （draft 提案・stale nudge）は **effect（:proposed）として
  `gftdcojp/gftdcojp` private tenant に投影**し、cockpit の #queue / #approvals
  と local shell（keiei.ui.gftdcojp）に表示する。投影は workspace.cljc と同じ
  薄い層（errand → effect kind `:errand/dispatch` 等）。
- 人間の承認・実行報告（evidence）は cockpit のフォーム or チャット返信の
  どちらでも受け、**必ず kyoninka.errand の evidence-schema 検証を通ってから**
  台帳イベント化（ADR-2607141654 の human gate 不変）。
- 会社の許認可情報は private tenant のみ。public cockpit に載せない
  （ADR-2607022300 の既存方針を継承）。
- URL は既存の org route **`itonami.cloud/gftdcojp/gftdcojp`** を正とする
  （オーナー表記の「itonami.cloud/gftd」は将来の org alias として扱い、
  新規の路線は切らない）。

### 2. 個人面は manimani の Decision Ledger に「assignment」として届ける

- org の errand を **個人への assignment** として manimani の /inbox に投影する。
  個人はいつもの triage UX（j/k + policy）で処理:
  `done` = evidence 報告（フォーム/自由文 → 検証）、`waiting` = 実行待ち宣言、
  `todo` = 後で、`declined` = 辞退。**decision は個人の Decision Ledger に
  append され、evidence は org 台帳へ writeback**（:by <did> 付き）。
- そのために **manimani の per-user DID seam を実装**する（宣言済み TODO の解消）:
  パスは **`manimani.cloud/u/junkawasaki`**（user handle → per-user DID 束縛。
  handle は表示用、認可は DID）。認証は itonami と同じ CACAO/passkey。

### 3. アカウント連携 = 「同一人物・同一 DID」

- 人間 1 人 = did:key 1 つを両プロダクトで使う。jun は itonami 側で actor
  `"jun"`（既存）、manimani 側で user `junkawasaki`（新設 seam）— **同じ DID に
  束縛**。これで「org の member」と「個人ユーザー」が名寄せなしで一致する。
- org 共有 = itonami の member/permission（既存 capability 集合）。
  assignment の受領・evidence 提出は :effect/propose 相当、承認は
  :effect/approve 保持者（owner）のみ。

### 4. データ連携 — SSoT は org 台帳、個人には投影が届く

```
[SSoT] org errand 台帳(cloud-itonami resources/licenses/*.edn — file+git、
       kotoba-ledger 語彙。将来 kotobase org-tenant graph へ ADR-2607022300 経路)
   │ 投影(assignment: errand の要約 + 返信フォーマットのみ。org 台帳全体は渡さない)
   ▼
[個人] manimani /inbox の assignment item → triage decision(個人 Decision Ledger)
   │ writeback(evidence。schema 検証合格時のみ)
   ▼
[SSoT] org 台帳へ :errand/validated + :case/step-done(:by <did>)
```

- kotobase の capability redaction が未配線である現実を踏まえ、**当面は
  「graph の直接共有」をせず、API 経由の投影コピー + writeback** にする
  （送るのは assignment に必要な最小情報のみ）。redaction が配線されたら
  capability datom によるスコープ共有へ移行（ADR-2607022300 の R2+capability 路線）。
- 台帳イベントの語彙は kotoba-ledger に寄せる（itonami が既に dual-write
  している共通語彙。属性 namespace は境界でアダプト — 強制統一しない）。

### 5. 課金・flywheel（ADR-2607023000 との接続）

確認屋は itonami org plan の機能、個人側は Manimani Cloud Personal
（¥500/月、Stripe live price 作成済み）の機能とする。内部 tenancy flywheel
（自社 product を org tenant として実課金登録）の消費者がまた 1 つ増える。
課金・口座作成は従来どおり AI が踏まない一線。

## Roadmap

- **M0（org 常設運転）**: license-loop tick を itonami の常駐運転に組込み、
  errand → effect 投影 + cockpit #approvals 表示。evidence はチャット/CLI。
- **M1（個人 seam）**: manimani per-user DID + `/u/junkawasaki`、assignment
  投影 API + writeback。jun の DID を両面で共通化。
- **M2（storage 昇格）**: INTERIM KV → kotobase graph（net-kotobase#153 後）、
  capability-scoped share へ移行。
- **M3（水平展開）**: 確認屋を license 以外の errand（BMC human 実験・営業
  follow-up）へ — この時点で ADR-2607141654 の「2 個目の消費者」条件が成立し、
  errand の kotoba-lang 切り出しを再評価。

## Addendum 1 — M0 実装完了（2026-07-14 同日、cloud-itonami `2eca25c5`）

`license_effects.cljc`（errand → activity/effect の薄い投影。effect id は
case+step から決定的 + transact 前存在確認で upsert 巻き戻しを防止）、
runtime-handlers に `:license.errand/dispatch`（**承認 = 実行を引き受けた記録。
外部送信ゼロ — 台帳へ `:errand/sent` を追記するだけ**）、tick-loop に license
pass（`:license-ledger-path` opt、drain! より前）。JVM E2E smoke 実測:
propose 2 → 承認 merge → `:errand/sent` → 以後 propose 0 / 早期 nudge 0。

実測で直した flow バグ: in-flight errand がある case が次 step まで起案されて
いた → **1 case 1 in-flight** に修正（license-loop propose）。evidence は M0
どおりチャット / CLI。suite 358 tests / 4041 assertions（既存 baseline 2 fail のみ）。

運転開始は launchd/cron の `clojure -M:tick-loop` 呼び出しに
`:license-ledger-path resources/licenses/itad-license-ledger.edn` を渡すだけ
（既存 tick-loop 運用に相乗り）。

## Addendum 2 — M1 実装完了（2026-07-14 同日。manimani `1203feaf` / itonami `787616b6`、worker deploy 済み `1b5163f3`）

**manimani per-user seam**（宣言済み TODO の解消）: `user.cljc` + `/u/<handle>/*`
routes（登録は admin gate、inbox/decisions は self/admin。per-user collection
`u:<handle>:*` で既存グローバル面と分離、2-arity handle は back-compat）。
token は M1 interim transport auth（CACAO 署名検証への昇格は M2）。
**live 検証済み**: 登録 200 / 無認証 401 / 他人 401 / admin の decision 投稿 403 /
self 投稿 201 + admin since-pull 200（テストレコードは検証後削除）。

**org 側同期**（`license_manimani.cljc`）: org 主導の push（最小投影 assignment）+
pull（decisions?since=cursor）→ evidence は kyoninka 検証合格時のみ writeback
（**`:by` に manimani user の DID — 「同一人物・同一 DID」の実配線**）。不合格は
ask-back を inbox へ push-back（台帳無変更）、declined は `:errand/declined`、
todo/waiting は台帳に触れない。`license_effects/run-once!` の opts `:manimani`
で tick-loop からそのまま運転。JVM E2E smoke: push 2 → 不正 evidence → ask-back
→ 訂正 → validated + `:case/step-done(:by did:key:…)`。

実測バグ 2 件を修正: ①routes の認可 gate が response を引数評価し **403 でも
store 副作用が先に実行**されていた → thunk 化 ②deploy 直後の edge cache 谷で
旧版 404 が混じる（itad で既知の現象 — 検証は版切替を跨いで 2 回）。

**残タスク（owner 入力待ち）**: 実 user `junkawasaki` の登録には owner の実 DID
（itonami 側 `GFTDCOJP_OWNER_DID` と同じ値）と user token の発行が必要 —
値を捏造しないため未登録のまま。DID を貰えば
`PUT /u/junkawasaki {:did … :token …}`（admin token は wrangler secret
`MANIMANI_ADMIN_TOKEN`）で即有効化できる。

## Addendum 3 — owner DID 生成と実運転開始（2026-07-14 同日、オーナー指示「did を local で生成して ok」）

- **owner identity をローカル生成**: `cloud-itonami.identity/load-or-create-identity!`
  （actor `"junkawasaki"`、CREATE_NEW race 対策付きの既存 JVM 経路）。
  **DID（公開値）**: `did:key:z6MkmCrDjqsUiHM4bK6zVyzuYGitMGjCVRb1eTrF122mxrNU`、
  鍵由来 graph: `k51qzi5uqu5dioljl2f10ymemfn73q6ht6ebmvmpiudahsabg3mef8vruu9eyh`。
  秘密鍵 seed は gitignored `.junkawasaki/identity.edn`（コミットされないことを確認済み）。
- **manimani 実登録**: `PUT /u/junkawasaki` 200。token 類は macOS Keychain へ永続化
  （参照は skill `secrets-location-map` — 値は書かない）。
- **実運転第 1 回**: run-once!（:manimani 付き）→ `:errand/proposed` 2 件を org 台帳へ
  commit、**assignment 2 件が live の個人 inbox に着信**
  （`GET /u/junkawasaki/inbox` で実確認。返信フォーマット付き日本語 draft）。
- 実測バグ: default-http-fn が pr-str EDN を application/json として送り全 PUT が
  500（`:pushed 0`）→ JSON 直列化に修正（`ff6a3f1b`）。**stub http-fn の smoke は
  transport 直列化を検証できない** — live 検証を省略しない教訓がまた 1 つ。

## Addendum 4 — closing（2026-07-14、オーナー指示）

本 ADR と関連 4 ADR（2607141446 ITAD/LP・2607141550 persona loop・
2607141620 kyoninka・2607141654 errand）を close する。**最終状態**:

- **稼働中**: itad.gftd.ai（LP、audit 100.00）/ manimani.cloud per-user
  （`/u/junkawasaki` 有効、owner DID `did:key:z6MkmCrDjqsUiHM4bK6zVyzuYGitMGjCVRb1eTrF122mxrNU`）/
  確認屋 M0+M1（errand→effect 投影 + assignment push/evidence writeback、
  実運転第 1 回済み — **個人 inbox に errand 2 件が evidence 待ちで live**）。
- **本セッションで live/実測が捕まえた欠陥 7 件**（すべて修正済み。設計論より
  実測を信じる根拠として記録）: cljs `:advanced` の property munge / screenshot
  素撮りの偽見切れ（persona 偽陽性）/ 比較表の自社列切れ / `case` 束縛の macro
  shadow / checkpointer thread-id 再開 / 認可 gate の副作用先行評価 /
  EDN-as-JSON transport。

**未了事項の集約（single list — 各 ADR の Follow-ups はここに統合）**:
1. 【owner・今すぐ】inbox の errand 2 件の実行（丸の内署・東京都環境局の実値確認）
   → 返信 or `POST /u/junkawasaki/decisions`。
2. 【1 行】tick-loop launchd 常設化（`:license-ledger-path` + `:manimani` opts）。
3. 【owner 決裁】行政書士相談（廃棄物該当性 = 収集運搬許可の要否）、JW 講習予約、
   確定価格、gftd.co.jp 配置、BMC base datoms 登録、LINE 公式・広告 ID 投入。
4. 【M2】kotobase graph 昇格 + CACAO 署名検証 + capability share（net-kotobase#153 後）。
5. 【M3】errand 水平展開（2 個目の消費者で kotoba-lang 切り出し再評価）。
6. 【小】persona panel 3-judge 化・:telemetry 較正、site raw-hex baseline 2 fail
   （kotoba-ui theme-color meta の上流 drift）、claude.ai stand-up routine
   （任意・GitHub 連携後）。

## Addendum 5 — 常設化完了（2026-07-15。closing list item 2 消込み、cloud-itonami `3cb05ce8`）

確認屋は launchd 常駐になった（**JVM 経路なし** — mail-drain/expiry-alert と同型）:

- **portable 化**: `license-manimani/sync*!`（cursor 注入 in / 返り値 out —
  **内部永続化しない**。先に cursor を進めると git 着地に失敗した evidence が
  次 tick で pull されず失われるため、呼び出し側が着地成功後にだけ persist）+
  `license-effects/run-pass!`（起案 → effect 投影 → manimani sync の 1 pass 合成）。
  `:clj` の `sync!`/`run-once!` は薄い委譲（外部挙動不変）。
- **runner** `scripts/kakuninya-tick.cljs`（nbb）: operational clone
  `~/.itonami/kakuninya-repo`（共有 west checkout 不使用）→ run-pass! →
  台帳 append → branch push + サーバ側マージ → **成功時のみ** cursor persist
  （`~/.itonami/kakuninya-cursor.edn`）。409 は次 tick が pull からやり直す。
  secrets は Keychain、http は curl 同期。
- **launchd** `com.gftdcojp.itonami.kakuninya`（30 分間隔、コードは共有
  checkout の絶対 classpath = 読み取り専用）。**インストール済み・kickstart で
  実 tick 成功をログ確認**（in-flight 2 errand を正しく skip、冪等）。

既知の M0 残差: 初回実運転（addendum 3）の dispatch effect 2 件は当時の
ephemeral conn にのみ存在し、production store（cockpit #approvals）には
現 2 errand が写っていない — 次の errand からは常駐 tick が production store に
投影する。現 2 件の完了は manimani inbox（配信済み）の evidence 経路で進む。

## Addendum 6 — M2 部分実装: per-user 認証の CACAO 昇格（2026-07-15。manimani `ad8c857` / cacao lib `ea09687`）

M2 のうち **net-kotobase#153 にブロックされない認証層**を昇格した:

- **切り出し規則の発動**: cloud-manimani が cloud-itonami edge verify の
  2 個目の消費者になった → ADR-2607141654 の規則どおり
  `cacao.edge.{base58,cbor,verify}` を **org-chainagnostic-cacao へ verbatim
  抽出**（crypto.subtle ベース — `cacao.core` の :cljs は node:crypto 依存で
  Worker では動かない）。cross round-trip（core/mint → edge/verify）を nbb で
  実証。cloud-itonami 自身の migration は follow-up。
- **manimani worker**: `Authorization: CACAO <b64>` 提示時のみ署名 + 時間窓を
  検証し、`iss` を verified-did として authz へ。**verified-did == user record
  :did → :self**（署名検証は transport 層、純関数 authz は DID 同一性のみ）。
  失敗は fail-closed で既存経路（401/403）。Bearer（admin / M1 interim user
  token）は後方互換で維持。`scripts/mint_owner_cacao.cljs` が owner identity
  (seed) から 1h CACAO を mint するクライアント。
- **live E2E**（version `0f53d669`）: 実 owner CACAO 200 / garbage CACAO 401 /
  Bearer 後方互換 200 / CACAO での decision POST 201（テスト decision は
  `policy: todo` の errand 外 item — kakuninya tick は触れない）。
- **実測バグ（本シリーズ 11 個目）**: verify.cljc 内部の aget(string) 併用で
  返り値 literal の `"iss"` が温存される一方、worker 側の `(.-iss r)` dot
  アクセスだけが :advanced で rename され undefined（live 401 → mock KV での
  local repro で確定）。unchecked-get に統一 + 認証失敗理由のログ追加
  （無言 nil はデバッグ不能、が今回も再確認された教訓）。

M2 残り: INTERIM KV → kotobase graph（net-kotobase#153 待ち）と
capability-scoped share、interim user token の廃止（CACAO 定着後）。

## Consequences

- (+) 新しい UI をゼロから作らない: 組織 = 既存 cockpit approvals、個人 = 既存
  manimani triage。確認屋は「投影 + seam 実装」だけで両面に乗る。
- (+) DID 対称性により、org member と個人ユーザーの名寄せ問題が発生しない。
- (−) manimani per-user seam と errand→effect 投影は未実装（M0/M1 が本体作業）。
- (−) kotobase capability redaction 未配線のため、当面の共有は API 投影コピー
  （graph 共有の粒度制御は M2 まで持ち越し）。
- (−) claude.ai stand-up routine（ADR-2607141654 §3）は補助チャネルに降格
  （GitHub 連携ブロッカーも moot になる。作るとしても M0 の後）。

## Follow-ups

1. M0: `cloud_itonami/license_effects.cljc`（errand→effect 投影）+ tick-loop 組込み。
2. M1: cloud-manimani per-user DID seam + assignment API（設計は本 ADR §2/§4）。
3. kotobase capability redaction の配線（M2 の前提。net-kotobase 側 issue 化）。
4. 「gftd」org alias の要否をオーナー決裁（既存 org id は gftdcojp）。
