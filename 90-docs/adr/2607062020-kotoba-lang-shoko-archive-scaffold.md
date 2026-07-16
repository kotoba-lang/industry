# ADR-2607062020: kotoba-lang/shoko — 保管ガバナンス actor（archive-LLM ⊣ ArchiveGovernor）

**Status**: closed(実行完了。フル実装 + 独立レビューで見つかった2件の修正、
実 R2 ArchiveTarget 配線まで完了。cloud-itonami 側 UI 配線は follow-up)
**Date**: 2026-07-06
**Amended**: 2026-07-07
**Closed**: 2026-07-07
**Deciders**: Jun Kawasaki

## Addendum(2026-07-07): 実 drive I/O(Cloudflare R2)

`shoko.archiveport` に実装した `r2-archiveport`（commit `ef8e3c7`）:
Cloudflare R2 の S3 互換 API に対する JVM SigV4 署名クライアント
（`gftdcojp/net-kotobase` の実装から関数単位で移植、golden vector で
検証済み）。専用の新規バケット `cloud-itonami-shoko-archive` を作成
（既存バケット・bindingには一切触れていない）。認証情報は
`scripts/r2-creds.cljs`（`gftd-r2/*` 1Password vault、`mail-creds.cljs` と
同型）で解決。**実際に share!→fetch-file の実往復を実行し、236バイト
送信・236バイト受信・バイト完全一致を確認、テスト用オブジェクトは
削除して後片付け済み。** `mock-archiveport` は既定のまま変更なし。
46 tests / 194 assertions。

## Context

ADR-2607062000（teian）と同じ依頼のうち「保管」面。`kotoba-lang/drive` は
file/folder を pure EDN で保持するモデルだが、アクセス制御・共有・監査の概念が
一切無い（実測確認済み — `acl|access-control|permission|share` を grep して
ゼロヒット、`validate.cljc` は構造検証のみ）。teian（資料）・koyomi（予定）が
生成する成果物（デッキ・ICS 等）を外部に共有する前段の「誰が何を見られるか」を
governor で強制する層が必要。当初 scaffold-only（本 ADR 初版）としたが、
2026-07-07 にオーナーがフル実装への昇格を指示した。

teian/koyomi の実装 + 独立レビューから得られた教訓（未修正のまま繰り返すべき
でないバグクラス）を設計に**あらかじめ**織り込む:

- koyomi の confirmed bug #2（governor に「subject 存在」チェックが無く
  rogue tenant が素通り）— shoko は `missing-file-violations`/
  `missing-activity-violations` を independent な hard check として最初から
  実装する。
- koyomi の confirmed bug #1（`:event/share` が commit 時点で store を
  再読込する TOCTOU）— shoko の `:file/share` も、govern 時点で検証済みの
  content を checkpoint 経由でそのまま使う（commit 時点で store を再読込
  しない）。
- teian の confirmed bug #2（`:deck/publish` が draft 時のみ検証し publish
  時に再検証しない）— shoko の `:file/share` も、共有の直前に
  `share-requires-acl`/`tenant-isolation` を再検証する。

## Decision

**新規 repo `kotoba-lang/shoko`（書庫）を起こし、archive-LLM ⊣
ArchiveGovernor 型の保管ガバナンス actor として実装する。** shoko は
teian/tayori（content 生成 + draft/publish）と kekkai（deny-by-default ACL）
の両方の性質を持つ: ファイル自体は `kotoba-lang/drive` の file/folder EDN を
そのまま保持し、「誰と共有できるか」は shoko 自身が持つ ACL grant で
deny-by-default に判定する。

1. **ArchiveTarget protocol（`shoko.archiveport`）**: `fetch-file`
   `propose-revision!`（下書き commit — 例: 共有前の redaction 済みコピーを
   提案）`share!`（principal への access 付与・通知 — 承認後のみ）。既定は
   `mock-archiveport`。content は `kotoba-lang/drive` の `drive.model`
   （`file`/`folder` EDN）をそのまま保持する。
2. **統一データモデル（`shoko.model`）**: `draft`（activity-id/file-id/
   content(drive EDN)/confidence/cites/redactions/status）+ `grant`
   （principal/file-id/access #{:read}/granted-by/granted-at）— grant は
   drive.model に無い ACL を shoko 自身が持つ台帳。
3. **二流路の StateGraph（`shoko.operation`）** — teian/koyomi と同型:
   - ingest（常時 ON・LLM 無し）: `:file/register`（既存ファイル/フォルダの
     登録）。
   - assess: `:file/draft`（archive-LLM proposal: drive EDN content +
     confidence + cites + redactions、effect は `:draft` 固定）→
     `:govern` → `:decide` → commit|escalate|hold。`:file/share`
     （principal への共有）は **常に人間承認**。
4. **ArchiveGovernor（`shoko.governor`）の HARD 不変条件**:
   - **no-actuation** — `:file/draft` proposal の effect は `:draft` のみ。
   - **missing-subject**（独立・無条件）— 参照する file/activity が
     store に存在しない場合、`content`/`tenant` の有無に関わらず必ず hard
     violation（koyomi confirmed bug #2 の再発防止）。
   - **share-requires-acl**（deny-by-default）— `:file/share` の対象
     principal が shoko 自身の `grant` 台帳に登録されていない（未知/未承認の
     principal）なら hard violation。kekkai の `deny-by-default-violations`
     と同型。
   - **tenant-isolation** — file の `:tenant` が activity の
     `:itonami.activity/repo` と不一致なら hard violation。
   - SOFT: confidence floor → escalate。`:file/share` は常に high-stakes
     （常に人間）。
   - **`:file/share` は commit 時点で checkpoint 済みの content を使い、
     store の再読込はしない**（teian/koyomi の TOCTOU 教訓を最初から回避）。
     ただし `share-requires-acl`/`tenant-isolation` は共有直前に再評価する
     （teian の publish-time re-check 教訓を最初から実装）。
5. **Phase 0→3**: teian/koyomi と同型構成。
6. **注入 port（swap）**: Store（`MemStore` ‖ `DatomicStore`）/ Advisor
   （mock ‖ `langchain.model`）/ ArchiveTarget（mock ‖ 実 drive I/O + 実
   Distributor、承認後のみ呼ばれる）。
7. **CACAO 自己発行**（`shoko.cacao`）: `.shoko/identity.edn`（gitignore）。
8. **台帳 = 保管ガバナンス監査台帳（append-only）**。

### cloud-itonami からの利用

`deps.edn` に `io.github.kotoba-lang/shoko {:local/root
"../../kotoba-lang/shoko"}` は既に先行登録済み。`cloud_itonami.workspace`
への実配線（`:file/share` effect ハンドラ）は本 ADR の範囲外（別 follow-up）。

## Consequences

- (+) 保管・共有ガバナンスが横断 actor に集約され、drive の既存 EDN モデルを
  再利用できる。
- (+) koyomi/teian のレビューで見つかったバグクラス（subject 存在チェック
  欠落・TOCTOU・publish時再検証欠落）を設計段階で回避。
- (−) 実 Distributor・実 drive I/O の live 結合は未検証。既定は
  mock-archiveport による決定的 sim で動く。
- (−) cloud-itonami 側の実配線・UI は本 ADR の範囲外（別 PR）。

## Execution(closing, 2026-07-07)

| 項目 | 状態 | 備考 |
|---|---|---|
| repo scaffold(README/deps.edn/namespace骨組み/smokeテスト) | ✅ 完了 | 2026-07-06、scaffold-only 版として。commit `9f71db2` |
| フル実装への昇格(ArchiveTarget port・統一データモデル・StateGraph・governor・phase・store・CACAO 自己発行) | ✅ 完了 | commit `123c4c8`。30 tests / 138 assertions |
| 独立レビュー(governor/ACL 中心の敵対的レビュー) | ✅ 完了 | confirmed 2 件: `share-requires-acl` がテナントに紐付いておらず、一方のテナントでのみ known な principal が別テナントのファイルへ「違反ゼロ」で共有されてしまうクロステナント権限昇格／`:granted-at` が `:now` 省略時にハードコードされたデモ時刻へ silent fallback（台帳の時刻整合性を損なう） |
| 上記 2 件の修正 + regression test 追加 | ✅ 完了 | commit `fa03d3c`。33 tests / 154 assertions(新規3件)、lint clean。`principal-known?`/`unregistered-principal-violations` をテナントスコープ化（principalの既存grantが対象ファイルと同一テナントかを store 参照で判定）、`:granted-at` は `:now` 省略時に実時刻(`store/real-now`)へフォールバック |
| manifest pin 前進(`9f71db2`→`123c4c8`→`fa03d3c`) | ✅ 完了 | `--entry shoko` 最小 diff、サーバ側検証 OK |
| superproject `main` 反映 | ✅ 完了 | 4actor 一括 pin 前進コミット |

## References

- ADR-2607062000（teian）、ADR-2607062010（koyomi）— 同じ依頼から分岐、
  レビューで見つかったバグクラスの教訓元
- `orgs/kotoba-lang/kekkai`（deny-by-default ACL の手本）、
  `orgs/kotoba-lang/tayori`（draft/publish の手本）
- ADR-2606272330（新規 project 一気通貫登録の実例・恒久承認）
- 本 ADR とペアの .edn
