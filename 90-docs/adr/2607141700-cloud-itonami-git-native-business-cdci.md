---
id: adr-2607141700-cloud-itonami-git-native-business-cdci
title: "ADR-2607141700: cloud-itonami の会社経営を PR/merge 駆動の CD/CI workflow にする — GitHub ではなく kotoba-git/kotoba-rad + kotobase + DataLad backend、部署/社員の CACAO capability で ref 権限を制御"
status: accepted — M0–M8 implemented same-day; pre-production (maturity addendum末尾参照)
doc_type: adr
topic: cloud-itonami-git-native-business-cdci
authoritative: true
last_verified: 2026-07-15 (ops-drain nbb port addendum)
authoritative_for:
  - "cloud-itonami の business operation(受信→提案→承認→実行→監査)を git-native な PR/merge workflow として表現する設計(受信 = proposal ref 到着、承認 = 署名付き merge、実行 = post-merge executor)"
  - "その git backend を GitHub ではなく kotoba-git/kotoba-rad(sovereign refs + push-gate)+ kotobase private tenant + kotoba-ledger-clj file-git/kotobase backend に置く判断"
  - "部署・社員の権限を CACAO delegation chain(did:key)+ ref namespace policy + risk tier で制御する CD/CI 権限モデル"
  - "大容量物(メール添付・m365 facts・帳票)は DataLad/git-annex + B2 参照とし git object に入れない判断"
related:
  - 90-docs/adr/2606271700-cloud-itonami-business-os.md
  - 90-docs/adr/2606301200-kotoba-mail-mailer.md
  - 90-docs/adr/2607061600-kotoba-issue-ledger-shared-libs.md
  - 90-docs/adr/2607072200-kotoba-git-kotoba-rad-content-addressed-vcs.md
  - 90-docs/adr/2607022300-itonami-gftdcojp-private-tenant-kotoba-rad-git-storage.md
  - 90-docs/adr/2607050400-webauthn-cacao-connection.md
  - 90-docs/adr/2607125300-cloud-itonami-crm-fleet-docker-ci.md
  - 90-docs/adr/2607012000-cloud-itonami-isco-occupation-blueprints.md
supersedes: []
superseded_by: []
---

# ADR-2607141700: cloud-itonami — 会社経営を sovereign git 上の PR/merge 駆動 CD/CI にする

**Status**: accepted — M0–M8 implemented same-day; pre-production(成熟度は末尾 addendum)
**Date**: 2026-07-14
**Deciders**: Jun Kawasaki（指示: 「business を PR/merge 駆動に。メール受信は PR の受信、送信は PR merge 後に CD/CI actions で発火。会社のビジネス運営全体を git workflow に。GitHub というよりは kotoba-lang/kotobase の git backend、DataLad の backend などで動くように。部署や社員の権限なども制御できる CD/CI workflow モデルを設計」）

## Context — 現状は PR/merge 駆動では *ない*（2026-07-14 実査）

`orgs/gftdcojp/cloud-itonami` の business loop は **in-process / store-native** であり、
git-native ではない。

- **propose→govern→approve→execute** は `business_loop.cljc` → `business_governor.cljc`
  → `approval.cljc` の in-process 遷移で、承認は EDN Datom store 内の effect status
  （`:proposed`→`:approved`→`:executed`）の書き換え。`approval.cljc` の
  「a proposal's 'PR' merges」という語は**比喩**であり、実 PR/merge は存在しない
  （語彙自体は ADR-2607061600 の `kotoba-issue-clj` 由来で、既に PR 型に揃っている）。
- **メール受信**は Cloudflare Email Worker（`workers/mail-inbound/index.js`）→
  `ITONAMI_DATA` KV staging → `scripts/mail-drain.bb` → `mail.cljc ingest-file!` →
  store。git には一切触れない。
- **メール送信**は approval 済み `:mail/send` effect を `send-via-resend!` handler が
  実行。merge や CI とは無関係。
- **GitHub Actions は 2 本とも inert**（repo-level disabled。`ci.yml` は
  continue-on-error、`deploy.yml` は一度も発火実績なし）。merge トリガの自動化は
  ローカル lefthook の静的サイト deploy のみ。さらに ADR-2607125300 のとおり
  agent の OAuth token には `workflow` scope が無く、GitHub Actions は agent 運用と
  相性が悪いことが実証済み。
- **監査台帳**は ADR-0011（repo 内）で意図的に「git commit から store へ」移した
  append-only Datom store。

一方、必要な building block は**すべて既に存在する**。本 ADR は新規発明ではなく
既存決定の合成である:

| block | 実体 | 状態 |
|---|---|---|
| PR 語彙のゲート | `kotoba-lang/kotoba-issue-clj`（issue/proposal/review/merge/audit） | 実装済み・cloud-itonami が採用済み（ADR-2607061600） |
| git 台帳 backend | `kotoba-lang/kotoba-ledger-clj` の `file-git`（decision ごとに git commit）/ kotobase（CID-pinned）backend | 実装済み・未配線 |
| sovereign git | `kotoba-lang/kotoba-git`（CID object/refs/DAG/ref-policy ff-only）+ `kotoba-lang/kotoba-rad`（RID/delegate/sigref/push-gate/署名付き head-announce） | 実装・テスト済み（ADR-2607072200 + addenda） |
| capability 認証 | CACAO delegation chain（`authorize-push-cacao?`、covers? で権限昇格不可、root-first leaf-last）+ did:key + WebAuthn 接続 | 実装済み（ADR-2607072200 addendum / 2607050400） |
| private tenant | `itonami/org/{org}/repo/{repo}` + `:itonami.repo/visibility :private` + CACAO 必須 edge（`edge/cacao.cljc`、本番稼働） | 実装済み（ADR-2607022300） |
| 大容量 backend | DataLad + git-annex + B2（m365-archive で運用中） | 運用中 |
| 部署/職務の語彙 | ISCO occupation blueprints（`orgs/cloud-itonami/cloud-itonami-isco-*`） | 登録済み（ADR-2607012000） |

## Decision

### 1. 業務イベント ↔ git workflow の対応（正本マッピング）

gftdcojp tenant ごとに **ops-repo**（kotoba-rad RID を持つ sovereign repo、GitHub には
置かない）を 1 つ持ち、業務オブジェクト（kotoba-issue 語彙の proposal/review/merge/
audit を EDN で serialize したもの）を commit として積む。

| 業務イベント | git-native 表現 |
|---|---|
| メール受信 | mail-drain が `refs/itonami/proposals/inbox/<msg-id>` に **proposal commit を作る = 「PR が届く」**。本文が閾値超・添付は annex key/CID 参照のみ |
| agent/advisor の提案（effect proposal） | `refs/itonami/proposals/<lane>/<id>` に proposal commit（rationale = PR description、risk tier 付き） |
| governor（機械 censor） | **pre-merge required check**。hold 判定の proposal は merge 不可（fail-closed） |
| 人間の承認 | 承認者の did:key で**署名された ff-only merge** を `refs/itonami/<lane>/main` へ。push-gate が署名 + capability + ref-policy を検証 |
| request-changes / reject | review object の commit（proposal ref に積む）。main には入らない |
| 実行（mail 送信・課金・外部 API） | **post-merge executor（itonami-runner、後述）が merge された approved effect を handler 実行 = 「merge 後に CD/CI actions で発火」** |
| 実行結果・receipt | `refs/itonami/audit/main` へ append-only の audit commit（mail receipt は ADR-2606301200 の `mail.receipt` をそのまま serialize） |

lane（`:inbox` `:sales` `:contract` `:billing` `:legal` `:procedure` `:employee`
`:plm` `:erp` `:mes` `:keiei` — ADR-2606271700 の既存 lane catalog）ごとに
`main` を分け、部署権限の単位とする。

既存 Datom store は**廃止しない**。CQRS: 署名付き git DAG が write-model
（authorization + 監査の正本）、store は read-model（Datalog query 面、
`business_loop` の observe はこちらを読む）。runner が merge/audit commit を
store へ投影する（方向は git → store の一方向。移行完了までは逆に store → git
の dual-write、§4）。

### 2. backend — GitHub ではなく kotoba/kotobase + DataLad

- **git 実体**: `kotoba-git`（CID-addressed objects/refs）。refs の移動は
  `kotoba-rad.push-gate` を通してのみ行う。ADR-2607072200 addendum が「shape policy
  （ff-only）と identity policy（CACAO）を合成する単一関数はまだ無い」と明記して
  いる gap を本 ADR の M1 で埋める（`authorize-ref-update?` =
  `ref-policy/fast-forward?` ∧ `push-gate/authorize-push-cacao?` ∧ risk-tier 検査）。
- **replication / hosting**: kotobase private tenant（`itonami.cloud/gftdcojp/gftdcojp`、
  CACAO 必須、ADR-2607022300）を authoritative replica とする。kotoba-rad R2
  （object encryption）が未実装の間、ops-repo を P2P 公開**しない**（replication は
  自社管理 node 間のみ）。R2 が landed したら暗号化 object で公開 replication を解禁。
- **ローカル台帳**: `kotoba-ledger-clj` の `file-git` backend（decision ごとに
  git commit）。オフラインでも decision が積め、kotobase 復帰時に announce で同期。
- **大容量物**: メール添付・m365 facts・帳票・生成物は DataLad/git-annex + B2
  （skill `large-binary-datalad` の既存経路）。git object には annex key / CID 参照
  だけを入れる。**business data を GitHub に置かない**。GitHub は従来どおり
  code repo の public mirror に格下げ（ADR-2607022300 Decision 2 と同一方針）。
- **secrets**: commit / proposal / effect に secret 値を入れない。alias のみ
  （ADR-2606301200 と同じ）。runner だけが host capability 経由で解決する。

### 3. 権限モデル — 部署/社員の CACAO capability で ref を制御する CD/CI

**Identity**: 社員 = did:key（WebAuthn から導出可、ADR-2607050400）。
org root key（gftdcojp）→ 部署 delegate → 社員、と CACAO delegation chain で委任する
（`cacao.core/verify-chain`。`covers?` により**部下は上長の持つ resource を超えて
昇格できない**ことがテスト済み — ADR-2607072200 addendum）。

**Resource / ability**（CACAO resource string）:

```
resource: itonami://<org>/<repo>/<lane>
ability:  itonami/propose | itonami/review | itonami/merge | itonami/execute | itonami/read
```

**部署 = lane 集合 + ISCO blueprint**。部署 delegate が持つ resource は担当 lane に
限定され、社員へはその部分集合だけを再委任できる。職種の職務範囲は ISCO blueprint
（`cloud-itonami-isco-*` の `blueprint.edn`）を根拠として lane/ability 既定値を導出する。

**risk tier → merge 要件**（既存の risk gate を branch protection に写像）:

| risk | merge 要件（push-gate が強制） |
|---|---|
| `:read-only` | governor check pass のみで **auto-merge**（bot merge 可）。既存の「read-only は自動実行」を維持 |
| `:external-send`（mail 送信・外部 API） | 当該 lane の `itonami/merge` 保持者 1 名の署名 merge |
| `:financial` | **2 署名**（当該 lane + `:keiei` lane の merge 保持者。self-approve 禁止 = proposer ≠ approver） |
| `:destructive` | org root（owner）署名のみ |

**職務分掌（separation of duties）を鍵で強制する**: proposer（agent/社員）、
approver（merge capability 保持者）、executor（runner bot）は**別の did:key** とし、
runner bot には `itonami/execute` だけを委任する — runner は merge できず、
approver は execute できない。agent（advisor）には `itonami/propose` しか
委任しない（現行の「advisor は proposals-only」の鍵レベルでの強制）。

**失効**: CACAO は expiry 必須（上限 90 日、退職・異動は期限切れ + 部署 delegate の
journal `remove-delegate!` の併用）。ADR-2607072200 が明記する「expiry なし CACAO は
revoke 不能」という既知 gap を、運用ルール（expiry 必須）で塞ぐ。

**read 権限**: lane 単位。`:employee`（人事）や `:legal` lane の read は当該部署 +
keiei のみ。private tenant なので anonymous read パスは存在しない（fail-closed、
ADR-2607022300 の incident の教訓を踏襲）。

### 4. CD/CI runner — 「GitHub Actions」の代替

**`itonami-runner`**: kotoba-server 側（または launchd/cron）の決定論的 executor。

1. 署名付き head-announce（`kotoba-rad.announce` + `kotoba-lang/p2p`、検証済み経路）
   を subscribe、または poll。
2. `refs/itonami/<lane>/main` の新 merge commit を検証（sigref → CACAO chain →
   risk tier 署名数）。**検証に失敗した merge は実行せず alert**（fail-closed）。
3. merge に含まれる approved effect を per-kind handler（`send-via-resend!` /
   Stripe / deploy 等、既存 handler 群）で実行。effect id で dedupe（at-most-once、
   再実行は明示の再 proposal）。
4. receipt/audit commit を `refs/itonami/audit/main` へ append し、store へ投影。

handler が無い effect kind は `:failed`（既存 approval runner と同じ fail-closed）。
governor は pre-merge check として runner とは独立に走る（censor は merge 前、
runner は merge 後 — 二重ゲート構造は現行のまま）。

### 5. 移行ステージ

- **M0（即着手可、既存コードの配線のみ）**: `approval.cljc` の decision/audit を
  `kotoba-ledger-clj` `file-git` backend へ **dual-write**。store 正本のまま、
  「decision ごとに git commit」の監査面だけ先に得る。
- **M1**: ops-repo 実体化。proposal/review/merge を kotoba-git/kotoba-rad の signed
  refs で表現。`authorize-ref-update?`（shape ∧ identity ∧ risk の合成 push-gate）を
  実装。gftdcojp org root key 生成、部署 delegate chain の初回 mint。
- **M2**: itonami-runner 稼働（post-merge 実行）。mail-drain を「KV → store 直行」から
  「KV → proposal commit」へ切替（**受信 = PR の成立**）。lefthook の deploy hook 等、
  既存の merge トリガも runner へ統合。
- **M3**: kotobase XRPC replica を authoritative に昇格、git DAG を write-model の
  正本に（store は read-model）。kotoba-rad R2 landed 後に暗号化 replication 解禁。

各ステージは独立に価値があり、途中で止まっても現行運用は壊れない
（M0 は純追加、M1-M2 は lane 単位で段階切替できる）。

## Alternatives considered

| 案 | 判定 | 理由 |
|---|---|---|
| GitHub PR + GitHub Actions で実現 | ❌ | 機密 business data（人事・契約・請求）を GitHub に置けない（private tenant 方針、ADR-2607022300）。Actions は repo-level disabled + agent token に workflow scope が無い実害（ADR-2607125300）。主権方針（GitHub は public mirror）に反する |
| 現状維持（store-native のみ） | ❌ | 承認の暗号学的帰属（誰がいつ何を承認したかの署名）・改ざん耐性・オフライン分散が store file には無い。「PR/merge 駆動」というオーナー要求も満たさない |
| Radicle 本家 / Gitea 等の既製 forge | ❌ | 外部スタック依存。kotoba-rad が同等物として実装・テスト済みで、CACAO/kotobase と同一 CID 体系で統合済み |
| git を正本にして Datom store を廃止 | ❌ | Datalog query 面（queue summary・doctor・BMC collect）が失われる。CQRS（git = write-model、store = read-model）で両立する |
| 権限を kotobase 側 ACL だけで制御（git 層は素通し） | ❌ | merge 署名に capability が紐付かず「誰の権限で承認されたか」が commit から検証できない。push-gate + CACAO は既にあるのに使わないことになる |

## Consequences

- (+) 会社運営の全遷移が「PR → review → 署名付き merge → post-merge 実行 → audit
  commit」になり、GitHub 的な開発体験と同型のまま、backend は自社主権
  （kotoba-git/kotoba-rad + kotobase + DataLad/B2）に載る。
- (+) 部署・社員・agent・bot の権限が**鍵と capability で**強制され（ACL 設定ファイル
  ではなく署名検証）、職務分掌（propose/review/merge/execute の分離)が構造的になる。
- (+) 既存資産の合成で済む: kotoba-issue 語彙は採用済み、ledger backend は実装済み、
  push-gate/CACAO chain はテスト済み。新規実装の中心は合成 push-gate と
  itonami-runner の 2 点。
- (−) kotoba-rad R2（object encryption)まで ops-repo の replication は自社 node に
  限定される。
- (−) 鍵運用（org root の保管、部署 delegate の mint/rotation、退職時失効）という
  新しい運用負担が生まれる。expiry 必須ルールで緩和するが、鍵紛失 = merge 不能の
  リスクは残る（org root の recovery 手順は follow-up）。
- (−) merge 署名 UI（承認者が実際に押すボタン）が必要。既存 cockpit
  （itonami.cloud）+ WebAuthn→did:key 経路の拡張として実装する。
- 既存データの破壊的移行はしない（store・KV・handler 群は全て存置。dual-write →
  投影方向の反転、という追加のみ）。

## Follow-up

- M0 配線（approval.cljc → kotoba-ledger file-git dual-write）の実装 PR。
- 合成 push-gate `authorize-ref-update?` を `kotoba-rad` へ（ADR-2607072200 の
  既知 gap の解消として upstream に置く）。
- org root key の生成・保管手順（secrets-location-map に参照を追記)と recovery 設計。
- 部署 → lane / ISCO blueprint → 既定 capability の対応表を
  `cloud-itonami.operating/lane-catalog` に隣接して EDN 化。
- merge 署名 UI（cockpit + WebAuthn）の設計 ADR。
- kotoba-rad R2 進捗の追跡（ADR-2606280300 のロードマップ）。

## References

- ADR-2606271700（business-os、lane catalog / effect lifecycle）
- ADR-2606301200（mail/mailer、draft → approval → send effect → receipt）
- ADR-2607061600（kotoba-issue-clj / kotoba-ledger-clj、PR 語彙と file-git backend）
- ADR-2607072200（kotoba-git/kotoba-rad、push-gate / ref-policy / CACAO delegation / signed head-announce）
- ADR-2607022300（private tenant、GitHub mirror 格下げ、CACAO edge 認証）
- ADR-2607050400（WebAuthn → CACAO）
- ADR-2607125300（GitHub Actions の workflow scope 実害）
- ADR-2607012000（ISCO occupation blueprints）
- 実査結果: `orgs/gftdcojp/cloud-itonami` の `business_loop.cljc` /
  `business_governor.cljc` / `approval.cljc` / `mail.cljc` / `tick.cljc` /
  `workers/mail-inbound/index.js` / `scripts/mail-drain.bb` /
  `.github/workflows/{ci,deploy}.yml`（両方 inert）/ `lefthook.yml`（2026-07-14）

## Addendum (2026-07-14, same day): M0 + M1 implemented and landed

- **M0 done** — `cloud-itonami.ops-ledger`（新規）+ `approval.cljc` hook:
  approve!/reject!/request-changes! の review verdict と merge! の
  merged/failed outcome を kotoba-ledger `file-git` backend へ dual-write
  （1 decision = 1 git commit）。デフォルト無効（opts `:ops-ledger` /
  `ITONAMI_OPS_REPO_DIR`）、fail-open、CLJS no-op、store は正本のまま。
  cloud-itonami `e6eb2d1c`。テスト: dual-write 2-commit 化 / 失敗 outcome /
  二重記録なし / fail-open の 4 本。
- **M1 done（合成 push-gate + ops-repo）** —
  - kotoba-rad `c71ee568`: `push-gate/authorized-signers-cacao` +
    `authorize-push-multi-cacao?`（quorum、signer distinctness、
    `:exclude-dids` による self-approve 禁止、min-signers 0 = auto-merge
    tier、`:cacao-opts {:now}` で expiry 強制）。60 tests / 94 assertions。
  - cloud-itonami `f1263424`: `cloud-itonami.ops-repo` —
    `refs/itonami/<lane>/main` + `refs/itonami/proposals/<lane>/<id>` を
    kotoba-git 上に実体化、`propose!`（lane main を親に持つ proposal
    commit = PR 到着）、`merge-proposal!` =
    `kotoba-git.ref-policy/set-ref-guarded!`（shape: ff-only）∧
    `authorize-merge?`（identity/quorum: risk tier 表のとおり read-only 0 /
    external-send 1 / financial 2+keiei / destructive owner-only、未知 risk
    fail-closed）。実 Ed25519 鍵 + 実 CACAO delegation chain（org root →
    部長 → 部員、sub-delegation は `covers?` で昇格不可）で permission
    matrix を E2E 検証（9 tests）。capability resource は kotoba-rad の
    `kotoba-rad://<rid>/push/<ref>` scheme をそのまま採用（本文の
    `itonami://` 表記はこの scheme に写像される — code repo と ops repo で
    委任語彙を分けない）。
- 本文からの設計上の確定差分: 「合成 push-gate を kotoba-rad へ」は、
  kotoba-git/kotoba-rad の decoupling を保つため 2 段構成にした —
  quorum/identity 側を kotoba-rad（`authorize-push-multi-cacao?`）、
  shape との合成点は既存の `kotoba-git.ref-policy/set-ref-guarded!`
  （caller-supplied predicate）に置き、risk tier の結線は消費者
  （`cloud-itonami.ops-repo/authorize-merge?`）が持つ。
- **未達（M2 以降）**: itonami-runner（post-merge executor）、mail-drain の
  proposal 化（KV → proposal commit）、org root key の実 mint と保管
  （現状テストは fixture seed のみ — 本番鍵の custody 手順は follow-up の
  まま）、merge 署名 UI、kotobase replica 接続。

## Addendum (2026-07-14, same day): M2 implemented and landed — runner + 受信=PR

cloud-itonami `51f99db8`。M2 の中核 3 点:

- **decision record の永続化** — `ops-repo/merge-proposal!` は guarded ff move の
  成功後に `refs/itonami/decisions/<lane>/<merged-commit-cid>`（merged commit CID
  キー、直接 lookup 可能）へ decision commit（risk / proposer-did / approvals =
  sigref + CACAO chain、全て plain EDN）を書く。`read-decision` で読み戻す。
- **`cloud-itonami.ops-runner`（post-merge executor = 「Actions」相当の本体）** —
  1 tick = ①各 lane main を走査し **audit chain（`refs/itonami/audit/main` の
  receipt commit 連鎖）との突き合わせで at-most-once dedupe** ②実行前に
  persisted decision を `authorize-merge?` で**再検証（fail-closed: decision 不在
  = gate を迂回して動かされた ref は terminal `:unverified` receipt を積み、
  handler を一切呼ばない — 実テストで raw `set-ref` 迂回を検証）** ③per-kind
  handler 実行（missing/throwing → terminal `:failed` receipt、retry storm なし）
  ④outcome ごとに receipt commit を audit ref へ append。runner は merge
  capability を持たない（職務分掌: bot did には execute のみ委任する前提）。
- **受信 = PR** — `inbound-record->proposal` + `propose-inbound!`: mail-inbound
  worker が KV に stage した drained record を `:inbox` lane の proposal commit 化。
  risk `:read-only` → auto-merge tier → runner の `:mail/ingest` handler が投影。
  E2E テスト: 受信 → PR ref 成立 → auto-merge → runner 発火 → receipt →
  再実行 no-op、を実 record 形で検証（計 5 tests / 25 assertions、スイート全体は
  baseline と同一の既存 failure のみ）。

**M2 の残り（未達のまま）**: 実 Cloudflare KV drain との結線（`scripts/mail-drain.bb`
は今も store 直行 — proposal 経路への切替は ops-repo の永続化経路が決まってから）、
ops-repo 自体の永続化・配布（現状は in-memory arrangement db。`repo/persist!` の
block store 選定と kotobase replica 接続は M3）、cron/launchd での runner 常駐、
lefthook deploy hook の runner 統合。org root key custody / merge 署名 UI も
引き続き follow-up。

## Addendum (2026-07-14, same day): M3 前半 — ops-repo のローカル永続化と mail-drain の git-first 切替

cloud-itonami `28d6fb80`。

- **`cloud-itonami.ops-store`** — kotoba-git の arrangement db(plain な
  4-index 値)を 1 つの EDN ファイルへ round-trip(ipld Link と blob byte
  array を walk でタグ化)。`store.cljc` と同じ file-backed local store 慣習。
  **content-addressed 経路(`kotoba-git.repo/persist!` = ciphertext-over-CID
  snapshot + block store + kotobase replica)は意図的に採らず M3 後半の
  follow-up のまま** — drain ループに今日必要なのはローカル耐久性で、
  後から save/load の差し替えで移行できる。
- **`cloud-itonami.ops-drain`（`clojure -M:ops-drain <ops-repo.edn> <store.edn>
  <records.json>`）** — drained KV records を git-first で処理する一気通貫:
  propose-inbound!（受信=PR。**既存 proposal ref がある id は skip = 再 drain
  冪等**）→ `:read-only` auto-merge（decision record 付き）→ verified/receipted
  runner → store 投影（`mail/record->inbound` を `ingest-file!` から抽出して
  同一変換を共有）。summary の `:failed` 非ゼロで exit 1（KV 鍵は温存）。
- **`scripts/mail-drain.bb` を切替** — `-M:mail ingest`（store 直行）から
  `-M:ops-drain`（git-first）へ。ops repo ファイルは `ITONAMI_OPS_REPO_PATH`
  （既定 `<store>.ops-repo.edn`）。これで **本文 §1 の「メール受信 = PR の受信」
  が実運用経路（Cloudflare KV → drain）で成立**。
- テスト: ops-store round-trip（refs / decision / receipts / blob が生存、
  load 後も dedupe 維持）+ drain E2E（2 records → PR×2 → merge → executed×2 →
  store 投影一致 → 再 drain no-op）+ `drain!` のファイル永続化と冪等性。
  3 tests / 23 assertions、スイート全体は baseline と同一（regression ゼロ）。

**未達（M3 後半以降）**: content-addressed persist + kotobase private tenant
replica、runner/drain の常駐化（cron/launchd routine 化）、org root key mint と
custody、merge 署名 UI、read-only 以外の lane の実運用委任 chain mint。

## Addendum (2026-07-14, same day): drain スクリプトを nbb 化(オーナー指示)

cloud-itonami `38a9e266`。オーナー指示「bb じゃなくて nbb で」により、M3 の
`scripts/mail-drain.bb` を `scripts/mail-drain.cljs`(nbb)へ置換。ロジック同一
(KV list/get → `clojure -M:ops-drain` → 成功時のみ KV delete)。credential
解決は既存資産 `scripts/mail-creds.bb`(env→1Password)への委譲を維持 —
mail-creds 自体の nbb 化は既存 bb tooling の温存原則どおり別スコープ。
fake-token smoke で env guard / creds env-passthrough / wrangler 呼び出し /
fail-fast(KV 鍵温存)を実行確認。以後この ADR 系列で書く新規スクリプト・
ハーネス(runner 常駐化含む)は nbb を正とする。

## Addendum (2026-07-14, same day): outbound 完成 — 送信 = PR merge 後に発火

cloud-itonami `7aed936a`。`cloud-itonami.ops-send` + `clojure -M:ops-send`
(propose / pending / merge / run / run-dry)で ADR 本文のもう一つの主役
「送信は PR merge 後に CD/CI actions で発火」が成立:

- mail draft → `:external-send` proposal ref(outbound の PR。**proposal 時点
  では何も送信されない**。pending queue = lane main に未到達な proposal refs)。
- 部門長の署名 merge(lane 権者 1 署名、proposer 自身の approval は無効)で
  着地 — **mail.draft 自体の approve gate は意図的に使わない**(git-native flow
  では署名 merge が承認そのもの。draft/approved? 前提条件は置き換え対象の
  store-native 経路の持ち物)。
- merge 後、inbound と**同一の** verified/receipted ops-runner tick が
  `mail/send-message-via-resend!` を発火。at-most-once(receipt dedupe、再送は
  明示の新 proposal)、gate 迂回の raw ref move は `:unverified` で transport に
  一切触れない。
- E2E テスト(recording Resend stub): 未 merge proposal は runner に不可視 /
  self-approve 拒否 / 署名 merge 後に **transport 呼び出しがちょうど 1 回**
  (URL・Bearer header 実測)/ rerun no-op / 迂回 ref move は送信ゼロ。
  2 tests / 16 assertions、全ゲート baseline 同一。
- CLI の merge は「approver の 32-byte Ed25519 seed(hex ファイル)+ CACAO
  chain(EDN)」を引数に取る — org root custody が決まるまでの local dev key
  運用。merge 署名 UI(WebAuthn 経由)は引き続き follow-up。

これで ADR §1 のマッピング表の主要行(受信=PR / 提案=PR / 承認=署名 merge /
実行=post-merge runner / 監査=audit ref)がすべて実装・E2E 検証済みになった。
残: kotobase replica / P2P 配布(rad R2 待ち)、runner・drain の常駐化、
org root key custody、merge 署名 UI、BMC 等他 lane への展開。

## Addendum (2026-07-14, same day): org root key custody = kotoba-lang/kagi + kagitaba(オーナー決定)

オーナー指示「org root key は kotoba-lang/kagi, kagitaba」により、custody の
follow-up を確定・実装した。cloud-itonami `82c3c9ce`(`cloud-itonami.ops-keys`)。

- **本番 seed は kagi PQC vault(kagitaba item model)に封緘**。ops-send の
  seed spec は `kagi:<item>`(`kagi get` で都度解決、このプロセスは seed を
  ディスクに書かない)。hex ファイルは dev fixture 専用に降格。
- **`clojure -M:ops-send keygen <kagi-item>`** — 32-byte Ed25519 seed を生成し
  stdin 経由で `kagi add` に封緘、**戻り値は公開 did:key のみ**。
- **`clojure -M:ops-send mint-chain <seed-spec> <delegate-did> <out.edn>
  <lanes-csv> [base|-] [days=90]`** — CACAO 委任 chain の mint/延長。
  **expiry 必須・既定 90 日**(§3 の revocation floor をコードで既定化)。
  sub-delegation は base chain 引数で(covers? により昇格不可)。
- **unlock は kagi 側の責務のまま**(KAGI_MASTER / Apple Keychain)。本コードは
  passphrase に触れない。
- テスト: stub kagi CLI(argv/stdin/stdout 契約のみ模擬)で vault 往復・
  did 一致・kagi 産 chain での実 merge・**day-89/day-91 の expiry 境界**・
  sub-delegation の cap を検証(2 tests / 13 assertions、全ゲート baseline 同一)。

**実鍵の bootstrap はオーナー操作**(kagi vault の unlock を要するため):
```bash
cd <kagi-vault-dir> && bin/kagi init            # 済みなら不要
clojure -M:ops-send keygen itonami-org-root     # → did:key を ITONAMI_OPS_OWNER_DID へ
clojure -M:ops-send keygen itonami-<dept>-head  # 部門ごと
clojure -M:ops-send mint-chain kagi:itonami-org-root <head-did> <dept>-chain.edn <lane>
```
実行後、skill `secrets-location-map` に kagi item 名を追記すること(follow-up)。

## Addendum (2026-07-14, same day): 実鍵 bootstrap 完了(kagi vault、オーナー指示「do it」)

kagi vault(`orgs/kotoba-lang/kagi/.kagi/`、OS Keychain unlock)へ**実鍵を mint 済み**。
seed はすべて vault 内に封緘され、このセッションのどこにも露出していない
(keygen の出力は公開 did のみ)。

| kagi item | 公開 did / 内容 |
|---|---|
| `itonami-org-root` | `did:key:z6MkqN7wed8dfK7qbB3rEQFuoDUrRWR7fytwNHBLhDqzCKQt` |
| `itonami-sales-head` | `did:key:z6Mkvyz2SWVcbxNwAzrdRjvV6oTEJH99bpJDDJe4cCK3w5KM`(lanes: sales) |
| `itonami-billing-head` | `did:key:z6MkeaC5zGnxrYB787PgT8e8frhjP1RmvsfjA2bDLygYkhFo`(lanes: billing) |
| `itonami-keiei-head` | `did:key:z6MkppkpV8bZfniDNYweXeWwTUWLexeoU7nnHMc8TTLij1fL`(lanes: keiei,billing) |
| `itonami-<dept>-head-chain` ×3 | org root 発行の CACAO 委任 chain(**expiry ≈ 2026-10-12、要再 mint**) |

- sales chain は mint 直後に実 `authorized-by-chain?`(expiry 込み)で検証済み。
- 公開 identity は `resources/ops-identity.edn` として cloud-itonami にコミット
  (`fe82e33a`)。`ops-keys/ops-identity` が env → この resource → dev default の
  優先順で解決し、ops-send / ops-drain が共有。
- 参照先は skill `secrets-location-map` に追記済み。
- 実行時メモ: 並行セッションが `orgs/kotoba-lang/langchain` を編集中で共有
  checkout が一時コンパイル不能だったため、langchain の committed HEAD を
  worktree に切った隔離 sibling layout で実行した(CLAUDE.md の
  worktree-per-agent 原則の実適用)。
- これで §3 の権限モデルは実鍵で運用可能。financial(2 署名 + keiei)も
  billing-head + keiei-head の実鍵・実 chain で成立する。残 follow-up:
  merge 署名 UI(WebAuthn)、chain の失効前再 mint 運用(≈2026-10-12)、
  kotobase replica、runner 常駐化(nbb)。

## Addendum (2026-07-14, same day): M8 — runtime を JVM clojure から nbb/cljs へ(オーナー指示)

オーナー指示「clojure じゃなくて nbb, cljs を想定」により、ops workflow の
第一級 runtime を nbb(ClojureScript on Node)へ。ブロッカーだった署名検証
スタックを upstream から cljc 化した:

- **org-ietf-ed25519 `e96c4d03`** — core.clj → core.cljc。:clj は従来どおり
  (pure BigInteger 導出 + JCA、bb 互換)。:cljs は node:crypto の**同期**
  Ed25519(手組み PKCS8/SPKI DER + sign/verify(nil digest)) — Promise 感染
  なし・npm 依存なし。b58 は BigInt を使わず古典 long-division(nbb の SCI に
  `js*` が無いため)。**JVM と nbb が同一 seed/msg でバイト同一の決定論署名を
  生成し相互検証**(committed `test/nbb_smoke.cljs`)。
- **org-chainagnostic-cacao `b56f2431`** — core.clj → core.cljc(base64 =
  Buffer / utf8 = TextEncoder / NonceStore を cljs.core/Atom にも extend)。
  **JVM mint の 2-link 委任 chain が nbb の verify-chain で valid**、nbb mint
  が JVM で valid、expiry / nonce-replay / resource-escalation はすべて nbb 側
  でも拒否(committed `test/nbb_smoke.cljs`)。ed25519 pin も bump。
- **cloud-itonami `6aa3bf01`** — ops-store(:cljs = Node sync fs、JVM と同一
  ファイル形式で相互 load 可)、ops-keys 完全 portable(kagi = execFileSync)、
  **`scripts/ops-cli.cljs`(nbb エントリ)+ `scripts/ops-classpath.sh`**:
  propose / pending / merge / run-dry / receipts / keygen / mint-chain。
  nbb 実 E2E: propose → PR ref → **nbb が mint した chain での署名 merge
  (検証は nbb 上の node:crypto で本物)** → verified runner → receipt →
  再実行 no-op、および owner 署名 + head chain(holder 不一致)の merge 拒否
  (lane main 不動)まで確認。`@noble/hashes` は io-multiformats 自身の宣言済み
  cljs 依存(sha256 CID)。
- **残り(JVM に留まるもの)**: 実 Resend 送信と Datom store 投影は当面 JVM
  runner コマンド(Node の fetch は Promise-only で、runner の同期契約を
  意図的に維持しているため)。nbb 側での async 送信統合は follow-up。
  kotoba-rad 自身の deps pin(ed25519 旧 sha)も機会あるとき bump。

## Addendum (2026-07-14, same day): 成熟度評価(オーナー照会「今の成熟度は?」)

尺度: **D**(設計のみ)→ **I**(実装済み)→ **V**(E2E テストで検証済み)→
**K**(実鍵・実データ・実経路まで配線済み)→ **P**(本番運転中)。

| 領域 | 段階 | 事実 |
|---|---|---|
| 提案 → 署名 merge gate(risk tier / quorum / self-approve 禁止 / ff-only / fail-closed) | **K** | JVM + nbb 両 runtime で E2E 済み、実鍵(kagi)で今日から運用可能 |
| 鍵 custody(kagi/kagitaba) | **K** | 実鍵 4 + 委任 chain 3 を vault 配備済み(kagi item 7 件)。**exp ≈ 2026-10-12 の再 mint は手動運用** |
| post-merge runner(検証 fail-closed・at-most-once・receipt) | **V** | 迂回 ref move の不実行まで実証。**常駐化なし(手動 tick)** |
| 受信 = PR(mail-drain git-first) | **V** | drain E2E + 実 wrangler 経路 smoke 済み。**実メールでの本番 drain は未実施** |
| 送信 = merge 後発火 | **V** | recording stub で transport 1 回・URL/Bearer まで実証。**実 Resend 本番送信はゼロ** |
| runtime = nbb/cljs 第一級 | **V** | core flow(propose/merge/run-dry/mint-chain)は nbb で本物の署名検証込みで動作。**実送信 + store 投影は JVM 残置** |
| 監査面(audit ref + M0 file-git dual-write) | **V/I** | audit ref は runner 経路で常時。**M0 dual-write は既定 off(opt-in)**、store-native 旧経路(approval.cljc)が並存し CQRS の write-model 反転は未完 |
| 永続化・配布 | **I** | local EDN のみ。content-addressed persist / kotobase private tenant replica / P2P(rad R2 待ち)未着手 |
| **governor pre-merge check** | **D** | **§1 の表の「pre-merge required check」が ops 経路に未結線**(docstring 言及のみ)。現状の merge gate は署名/quorum のみで、machine censor は旧 in-process loop 側に残っている — 次の最重要 gap |
| runner bot の execute-only 鍵(職務分掌の execute 側) | **D** | runner は鍵レスで動く。bot did の mint + execute 委任は未実施 |
| merge 署名 UI(WebAuthn) | **D** | CLI のみ |
| lane 展開 | 部分 | 委任済みは sales / billing / keiei(+ inbox auto)。procedure/legal/plm/erp/mes/employee は未委任 |

**総括: pre-production(実装 3.5/5 相当)。** §1 マッピング表の全行が実装・E2E
検証済み(12 領域中 V 以上 7、うち K 2)で、実鍵も配備済み — だが**本番運転は
ゼロ**(実メール drain・実送信・常駐 tick のいずれも未実施)。設計からの残
ギャップで重要な順: ①governor pre-merge 結線(machine censor が git 経路を
まだ守っていない)②runner/drain の常駐化(nbb)③実運転の初回実施(実メール
1 通を PR → merge → 実送信まで通す)④chain 再 mint 運用(≈2026-10-12)
⑤kotobase replica / rad R2 ⑥merge 署名 UI ⑦残 lane への委任展開。

## Addendum (2026-07-14, same day): 成熟度表の最重要 gap ①を解消 — governor を git merge 経路に結線

cloud-itonami `e17f5fa9`(`cloud-itonami.ops-governor`)。成熟度表の
「governor pre-merge check: **D**」→ **V** に更新する。

- `merge-proposal!` が ctx `:governor-check` を取り、**署名検査より前に**
  machine censor を実行 — `:hold` は `:reason :governor-hold` で merge 拒否
  (ref 不動・decision 不記録)、`:pass` は verdict を decision record に永続化。
  production 入口 3 箇所(nbb `ops-cli.cljs` / `ops-drain` / `ops-send` CLI)は
  無条件で結線済み。
- rule: `:mail/ingest`(provider message id 必須)/ `:mail/send`(実在する
  message + from / 宛先 / subject / body 必須)は ops-governor 自身が検閲、
  それ以外の kind は `business-governor/check` へ委譲(未知 kind hold =
  end-to-end fail-closed。kind 無し/読めない proposal も hold)。
- nbb で censor を動かすため、`business-governor` の唯一の funding 依存だった
  `equity-blocklist` を `kernels.substance`(zero-dep SSoT)へ移設(funding は
  旧名で再公開、既存 caller 不変)— これで censor のロードが store/langchain.db
  連鎖を引かなくなった。
- 検証: JVM 4 tests(hold が署名前に拒否・ref 不動・decision 無し / pass の
  verdict 永続化 / mail rule 行列 / business kind 委譲 / fail-closed)+
  **nbb 実 CLI で宛先なし send の merge 拒否と正常 send の merge→run-dry を実証**。
  全ゲート baseline 同一。
- これで auto-merge tier(:read-only)も「governor pass のみで merge」という
  §1 の表の記述どおりに動く。残 gap の先頭は ②常駐化 → ③初の実運転。

## Addendum (2026-07-14, same day): gap ②③ 解消 — 常駐化 + 双方向の本番初運転

cloud-itonami `f5a5d084`。**この ADR の loop は本番運転に入った。**

- **② 常駐化(受信側)**: launchd LaunchAgent
  `com.gftdcojp.itonami.mail-drain`(template:
  `scripts/launchd/com.gftdcojp.itonami.mail-drain.plist`)が **15 分間隔で
  稼働中**(このマシン、tick 実測 exit 0)。認証は wrangler の **OAuth
  セッション**に切替 — 実測で 1Password の CLOUDFLARE_API_TOKEN は
  analytics 狭 scope で KV に届かず(error 10000)、drain 経路から 1Password
  依存を排除した。あわせて廃止済み `kv key delete --force` フラグを修正。
- **③-受信の本番初運転**: KV に staged されていた**実メール 1 通**
  (SES 検証メール)が git-first 経路を完走 — proposal ref → governor
  `:pass` → auto-merge → verified runner → `:executed` receipt → store 投影。
  再 drain は `:already-proposed` skip(冪等実証)、KV 鍵削除まで完了。
  本番 ops repo / store の所在: **`~/.itonami/store.edn(.ops-repo.edn)`**
  (このマシン。logs は `~/.itonami/logs/`)。
- **③-送信の本番初運転**: 実 draft(`mail:send:live-first-run`、
  ops@mail.itonami.cloud → root@junkawasaki.com)を nbb CLI で PR 化 →
  governor pass → **kagi 実鍵(itonami-sales-head)+ 実 chain による署名
  merge** → JVM runner が **実 Resend 送信**(provider-message-id
  `426c6dff-067f-4b3d-8986-d6b5c04b37af`)。直後の再 run は results 空
  (at-most-once を本番 repo で実証)。
- 成熟度表の更新: 受信=PR **P**(常駐 + 実運転)/ 送信 **K→P 初回達成**
  (送信トリガーは手動 CLI のまま — 送信側の常駐は要らない設計: merge が
  トリガー)/ runner **V→P**(本番 repo で稼働)。
- 副次修正: M8c の package.json 追加が旧 package-lock(空 stub)を痩身化した
  件を確認 — 消えたのはローカル一時 node_modules のみで追跡物の損失なし、
  wrangler は npx 解決で健在(4.103.0)。
- 残 gap(優先順): chain 再 mint 運用(≈2026-10-12)、runner bot の
  execute-only 鍵、kotobase replica / rad R2、merge 署名 UI、残 lane 委任、
  nbb での実送信(async fetch)。

## Addendum (2026-07-14, same day): chain 失効監視 + 再 mint(運用 gap 解消)

cloud-itonami `4d4eeb22`。live 運転で最大の運用リスクだった「委任 chain が
≈2026-10-12 に一斉失効 → merge が黙って通らなくなる」を塞ぐ:

- `ops-keys/chain-status` / `delegate-status`: 各 delegate chain の鮮度
  (valid? / expires / days-left / warn 窓内の expiring? / expired?)を kagi
  item から読む(never-throw)。
- `ops-keys/rotate-chain!`: org root から chain を再 mint し、**同じ kagi
  item に上書き封緘**(upsert)。ops-identity は item 参照なので、rotate 後は
  merge caller が設定変更なしで新 chain を拾う。
- CLI 2 経路: `expiry [warn-days]`(nbb `ops-cli.cljs` / JVM `ops-send`。
  期限接近 or 無効な chain があれば **exit 1** — cron/monitor 向き)と
  `rotate <delegate-key> [days]`。
- **live 実測(読み取りのみ、安全)**: 実 vault で sales/billing/keiei head
  の 3 chain すべて valid・**89.9 日残**・exit 0。rotation は stub vault で
  E2E(失効 chain → 再封緘後は実 now で valid かつ当該 lane を authorize)。
- 運用手順: `~2026-10 初旬`に `nbb ... ops-cli.cljs expiry` が exit 1 を
  返し始めたら、kagi unlock 済みで
  `nbb ... ops-cli.cljs rotate sales-head`(billing-head / keiei-head も)を
  実行するだけ。将来はこの expiry check を launchd 週次 tick に足せば
  自動アラート化できる(follow-up)。
- 成熟度表の更新: 「chain 再 mint 運用」gap を **D→V**(監視 + 再 mint とも
  実装・検証済み、live で監視実測)。残 gap: runner bot の execute-only 鍵、
  kotobase replica / rad R2、merge 署名 UI、残 lane 委任、nbb 実送信。

## Addendum (2026-07-14, same day): execute-only runner bot 鍵 — 職務分掌の execute 側を暗号学的に閉じた

cloud-itonami `e6437585`。§3 の職務分掌は propose/approve までは鍵で強制
されていたが、execute 側(runner)は鍵レスで receipt も無署名だった。これを塞ぐ:

- runner が各 receipt を **execute-only bot 鍵**(kagi
  `itonami-runner-bot`、did `z6MkvmMJ…FWmq`、`ITONAMI_OPS_RUNNER_SEED` でも可)
  で署名 — `:runner-did` + `:runner-sig`(receipt の安定 identity =
  merged-cid/lane/effect-id/kind/status/ts に対する Ed25519)。
  `ops-runner/verify-receipt` が replay 検証。
- **bot 鍵には merge chain を一切発行しない**(ops-identity の delegate に
  ならない)。これで proposer / approver / executor が**3 つの相異なる鍵**に
  なった — bot did を merge signer として提示しても authorize する chain が
  無いので何も通らない。
- opt-in: bot 鍵未設定なら receipt 無署名(後方互換)。exec 3 経路
  (ops-drain / ops-send / ops-cli.cljs run)に配線、`ops-cli.cljs audit
  <ops-repo> [expect-did]` で全 receipt の bot 署名を検証。
- 検証: JVM 3 tests(署名 receipt の検証・改ざん検知・expect-did 一致/不一致・
  無署名は attestation なし・bot did ≠ owner)+ **nbb 実運用**(kagi 実 bot 鍵で
  署名 run-dry → `audit` が `runner-verified? true`)。ed25519 は cljc(M8a)
  なので nbb でも署名・検証する。参照先は skill `secrets-location-map` に追記。
- 成熟度表: 「runner bot の execute-only 鍵」gap を **D→K**(実鍵配備 + 実運用
  検証)。残 gap: kotobase replica / rad R2、merge 署名 UI、残 lane 委任、
  nbb 実送信、expiry check の自動アラート化。

## Addendum (2026-07-14, same day): chain 失効の週次自動アラーム

cloud-itonami `a78e8d50`。live loop の唯一の「黙って死ぬ」経路 —
委任 chain が ≈2026-10-12 に失効して merge が通らなくなる — を自動監視化:

- `scripts/expiry-alert.cljs`(nbb、read-only): `delegate-status` を読み、
  warn 窓内 or 無効な chain があれば `~/.itonami/logs/expiry-alert.log` に
  ACTION NEEDED 行 + fire-and-forget(detached/unref、絶対にブロックしない)
  macOS 通知を出し **exit 1**、健全なら静かな OK 行 1 本。**rotate はしない**
  (org root seed が要る人手 `ops-cli.cljs rotate <delegate>` のまま)。
- `scripts/launchd/com.gftdcojp.itonami.expiry-alert.plist`: 毎週月 09:00。
  **このマシンにインストール済み・稼働確認済み**(launchd 経由の実行で
  3 chain fresh の OK 行を確認、exit 0)。alarm 経路も warn-days 3650 で
  全 delegate を rotate 対象として列挙し exit 1 することを実測。
- これで cloud-itonami の常駐 agent は 2 本(mail-drain 15 分、expiry-alert
  週次)。運用は「10 月に通知が来たら rotate を打つ」だけ。
- 成熟度表: 「expiry check の自動アラート化」follow-up を **完了**に。
  残 gap: kotobase replica / rad R2、merge 署名 UI、残 lane 委任、nbb 実送信。

## Addendum (2026-07-14, same day): kotoba-rad R2 の暗号コアが着地 — private replica への道が開いた

kotoba-rad `403d2b05`(ADR-2606280300 addendum 参照)。本 ADR の §2/§5 が
「R2(object encryption)landed 後に暗号化 replication を解禁」と明記していた、
その R2 の暗号コアが実装された:

- `kotoba-rad.recipient-grant`: X25519 sealed box で epoch key を recipient
  の X25519 pubkey に wrap、`rotate` で失効(= epoch rotation)。
- `kotoba-rad.private-object`: AES-256-GCM で object bytes を封緘、
  replication id = ciphertext CID(grant 無き peer は複製できても読めない)。
- fully cljc、JVM⇄nbb クロス検証済み。

これで ops-repo の private replica(§2 の kotobase private tenant を
authoritative replica にしつつ P2P へ暗号化 object を配布)を組む土台ができた。
**まだ残っているのは「本 ops-repo の各 object を private-object で封緘し、
ciphertext を kotobase/P2P に置く storage-layer の配線」**(RecipientGrant を
どの capability datom に紐付けるか含む)— R2 暗号プリミティブは揃ったので、
これは crypto ではなく storage 統合の作業。成熟度表: 「kotoba-rad R2」を
**部分達成**(暗号コア V、repo 統合は未着手)。残 gap: R2 storage 配線、
merge 署名 UI、残 lane 委任、nbb 実送信、R4 PQ hybrid。

## Addendum (2026-07-14, same day): R2 storage 配線 — ops repo が private ciphertext replica になった

cloud-itonami `3e04db5c`。R2 の暗号コア(kotoba-rad)を消費して、§2 の
「private replica」が実体化した:

- `cloud-itonami.ops-private`: ops repo 全体(`ops-store/db->string`)を
  1 つの private-object に epoch key で封緘し、その key を各 recipient の
  X25519 pubkey に grant。replica(kotobase private tenant / B2 / P2P peer)は
  ciphertext だけを持ち、grant 保持者のみが db を復元。rotate は reseal 時に
  drop 対象 pub を外すだけ(= epoch rotation による失効)。
- `ops-keys`: `recipient-keygen!`(X25519 鍵 → kagi、署名用 Ed25519 とは別)、
  `recipient-priv` / `recipient-pubs`。`ops-identity.edn` に `:recipients`
  (org-root + sales-head の X25519 pub、実 mint 済み)。
- nbb CLI: `recipient-keygen` / `replica-seal` / `replica-open`。
- **live 実証**: 本番 ops repo(`~/.itonami/store.edn.ops-repo.edn`)を
  2-recipient の ciphertext bundle に封緘 → sales-head 鍵で開いて **元ファイルと
  バイト一致**、ciphertext-cid ≠ plaintext。
- 成熟度表: 「kotoba-rad R2」を **暗号コア + repo 統合とも達成(V/K)**に更新。
  **残るのは storage の endpoint 配線のみ** — この ciphertext bundle を実際の
  kotobase/B2 replica へ push/pull する経路(と、whole-repo でなく per-object
  粒度への細分化)。crypto と repo 統合は完了、あとは転送層。
  他の残 gap: merge 署名 UI、残 lane 委任、nbb 実送信、R4 PQ hybrid。

## Addendum (2026-07-14, same day): nbb 実送信 — outbound の JVM 残渣を解消

cloud-itonami `d6b307a5`。オーナー方針「nbb, cljs を想定」の最後のピース。
send は JVM に残っていた(mailer は cljc だが mail.cljc が cheshire/
java.net.http 依存)が、これを portable 化した:

- `cloud-itonami.mail-send`: Resend POST を `mailer.core`(cljc)だけから
  組み、**両ホスト同期**の transport で送る — :clj は java.net.http、:cljs は
  `child_process.execFileSync` 経由の **curl**(Node の fetch は Promise-only
  なので、runner の sync 契約を守るため curl 同期。npm 依存なし)。
  `send-handler` が runner の :mail/send handler。
- `ops-cli.cljs` に実 `run` コマンド(classpath に mail/mailer 追加)。これで
  **outbound の全経路が nbb で完結**: propose → governor → 署名 merge →
  runner → **実 Resend 送信** → bot 署名 receipt。
- **nbb live 実証**: 実 draft PR を kagi の sales-head 鍵で署名 merge →
  `ops-cli.cljs run`(curl POST)で **実送信**(provider id 返却)、receipt の
  bot 署名も verified。**経路に JVM 無し**。
- JVM の `-M:ops-send run` は第 2 ホストとして存置。**唯一残る JVM-only は
  inbound mail の Datom store 投影(`-M:ops-drain`)**。
- 成熟度表: 「nbb 実送信」gap を **完了**。残 gap: R2 transport endpoint 配線、
  merge 署名 UI、残 lane 委任、~~inbound の store 投影の nbb 化~~（2026-07-15
  Addendum参照）、R4 PQ hybrid。

## Addendum（2026-07-15）— inbound の store 投影も nbb 化（唯一残る JVM-only を解消）

cloud-itonami `e055ce9a`。上記addendumが「唯一残るJVM-only」と記録した
`inbound mail の Datom store 投影(-M:ops-drain)`を解消した。
`scripts/mail-drain.cljs`は従来`clojure -M:ops-drain`をsubprocessとして
shell outしていたが、今は`cloud-itonami.ops-drain/drain!`をin-processで
直接呼ぶ——outboundの`ops-cli.cljs run`と同じ「経路にJVM無し」をinbound側
でも達成した。

**想定外だった3つの実gap**（実装前は「単にreader-conditionalを足すだけ」
と思っていたが、掘ってみると想定より深かった）:

- `cloud-itonami.store`の`open-file-conn`/`save-file-conn!`が`:cljs`分岐を
  一切持っていなかった（JVM-only java.io）——ほぼ同じ役割の
  `cloud-itonami.ops-store`（ops-repoのdb用）は既にcljs対応済みだったのと
  非対称。同じ「EDN経由でNodeの`fs`」パターンを追加、`.bin`パスは
  cljsでは明確なエラーを投げる（JVMの`ObjectOutputStream`形式にcljs側の
  対応物は無いため）。
- `cloud-itonami.mail`が無条件の`(require [cheshire.core :as json] ...)`
  を持ち、かつ「JVM専用」とコメントで既に明記されていた複数関数
  （`ingest-file!`/`jvm-http-fn`/`send-message-via-resend!`/
  `send-via-resend!`/`send-marketing-outreach!`/`mail/-main`）が実際には
  reader-conditionalで囲われていなかった——このため**namespace全体が
  cljsではコンパイルすら通らず**、`ops-drain`自身のrequireをブロックして
  いた。JVM専用と明記済みのセクション全体を1つの`#?(:clj (do ...))`
  ブロックで包んだ。`ops-drain`が実際に使う`record->inbound`/
  `inbound->tx`は既にportableで変更不要だった。
- `cloud-itonami.ops-keys`は実は**既に完全にportable**だった
  （outboundの`ops-cli.cljs`は既にcljs下でこれを呼んでいる）——
  `ops_drain.cljc`自身の`#?(:clj [cloud-itonami.ops-keys :as ops-keys])`
  というrequireの縛りは、本物のブロッカーではなく単に過度に慎重
  だっただけ。これを外し、それが引き起こしていた小さな不整合も修正:
  `drain-records!`は`:runner-seed #?(:clj (ops-keys/runner-seed) :cljs
  nil)`としていたため、inbound-drainのreceiptはcljs下では**未署名**に
  なる一方、outboundは同じ関数を無条件に呼び常に署名する、という非対称
  があった——今はoutboundと同じく無条件に呼ぶよう修正。

**検証**: `clojure -M:test`: 834 tests / 6886 assertions、6 failures
（変更前のclean baselineと同じ6件の無関係な既存失敗——
doctor-test/isco-1212-test/marketplace-test、store/ops-drain/mailとは
無関係）。clj-kondo: 触った4ファイルとも0 errors/0 warnings（repo全体の
1件の既存lint errorは無関係な別テストファイル）。実`nbb`実行で
`ops-drain/drain!`を隔離sandbox内の実`records.json`に対して実行し
（本番ledgerには一切触れず）、propose→auto-merge→execute→実store投影の
永続化（保存後に再読込してdatom件数を確認）、同一recordsの2回目実行が
正しくidempotentにskipされることを確認。同一のJVM実行
（`clojure -M:ops-drain`）が同じ結果を出すことも並べて確認。
`scripts/mail-drain.cljs`自体も実nbb経由でend-to-end実行を確認
（実際の読み取り専用`wrangler kv key list`呼び出しが通ることまで含め、
requireとスクリプト全体のコンパイル・実行を確認）。

**着地**: `gftdcojp/cloud-itonami`、`main`→`e055ce9a34641ed2a41528c4955da2256b16b822`、
sibling worktree + `gh api .../merges`サーバ側マージ + branch cleanup。
west pin前進（`d6b307a5`→`e055ce9a`、`gh api compare`で`ahead_by=10`
[無関係な8コミット+本変更]、`behind_by=0`を確認済み）。

**正直に記録する残りgap**: R2 transport endpoint配線（封緘済み
ciphertext ops-repo bundleを実kotobase/B2 replicaへpush/pullする経路）は
本addendumでは未着手——storage credentialを伴う別作業であり、今回は
着手していない。
