# ADR-2607062010: kotoba-lang/koyomi — 予定共有 actor（schedule-LLM ⊣ ComplianceGovernor）

**Status**: closed(実行完了。scaffold + 独立レビューで見つかった4件の修正、
実 Resend/Slack Distributor 配線まで完了。cloud-itonami 側 UI 配線は follow-up）
**Date**: 2026-07-06
**Closed**: 2026-07-06
**Amended**: 2026-07-07(実チャネル接続)
**Deciders**: Jun Kawasaki

## Addendum(2026-07-07): 実チャネル接続(Resend / Slack)

`koyomi.scheduleport` に実配布実装を追加した:

- **Resend**（`koyomi.distribute/resend-scheduleport`、commit `3db453c`）:
  `koyomi.scheduleport/ics-string` が生成する ICS を `text/calendar` 添付
  として実送信。attendee は email アドレスそのものとして扱う設計
  （プレースホルダ id を使う demo データでは fail-closed で `:invalid-
  recipient` になることを確認済み）。**実際に1通ライブ送信し、Resend の
  message id を確認、ledger に `:tool "resend:<id>"` として記録済み**
  （API キー自体は非出力）。
- **Slack**（`koyomi.scheduleport/slack-scheduleport`）: `chat.postMessage`
  通知の実装コードは用意（ICS 自体の代替ではなく一報として）。Slack
  アプリ登録はオーナー側作業（README に手順明記）。
- `mock-scheduleport` は既定のまま。37 tests / 146 assertions。

## Context

ADR-2607062000（teian）と同じ依頼の一部: `cloud-itonami` の営業・面談・
取締役会・監査イベント等を、関係者に予定として共有する手段が無い。
`kotoba-lang/calendar` は既に event（`:calendar/id/title/start/end/attendees/
links`）を pure EDN で保持する portable モデルを持つが、ICS export・招待送信・
同意管理・governor の概念は一切無い（実測確認済み — `ics|ical|share|publish`を
grepしてゼロヒット）。予定を外部関係者に共有する行為は「本人の同意が無い相手への
情報開示」というリスクを構造的に持つため、`kotoba-lang/tayori`
（ADR-2607061500）の `consent-required` HARD invariant と同種の governor が
必要であり、calendar 自体（pure data model）に持たせるべきではない。

## Decision

新規 repo `kotoba-lang/koyomi`（暦）を起こし、schedule-LLM ⊣
ComplianceGovernor 型の予定共有 actor として実装する。

1. **ScheduleTarget protocol（`koyomi.scheduleport`）**: `fetch-event`
   `propose-revision!`（下書き commit）`share!`（招待送信・外部共有 —
   承認後のみ）。content は `kotoba-lang/calendar` の `calendar.model` event
   EDN をそのまま保持する。`share!` の実体は koyomi 側で組み立てる ICS
   文字列（calendar 自体には export 概念が無いため）+ Distributor port
   （メール等、tayori/gijiroku と同型に注入）。既定は
   `koyomi.scheduleport/mock-scheduleport`。
2. **統一データモデル（`koyomi.model`）**: `draft`（activity-id/event-id/
   content/confidence/cites/redactions/status）+ `contact`（attendee/
   consent/first-contact?）— tayori の contact 型を流用。
3. **二流路の StateGraph（`koyomi.operation`）** — teian/tayori と同型:
   - ingest（常時 ON・LLM 無し）: `:event/register`（既存活動の due-at から
     機械的にイベントを記録）。
   - assess: `:event/draft`（schedule-LLM proposal: title/start/end/
     attendees/agenda、effect は `:draft` 固定）→ `:govern` → `:decide` →
     commit|escalate|hold。`:event/share`（招待送信）は **常に人間承認**。
4. **ComplianceGovernor（`koyomi.governor`）の HARD 不変条件**:
   - **no-actuation** — `:event/draft` proposal の effect は `:draft` のみ。
   - **consent-required** — attendee の `:consent` が `:blocked` なら hard
     violation。`:first-contact?` な社外 attendee は hard ではないが
     high-stakes（tayori と同型）。
   - **tenant-isolation** — event の `:tenant` が activity の
     `:itonami.activity/repo` と不一致なら hard violation。
   SOFT: `calendar.model/overlaps?`（既存の実装済み interval-overlap 関数）
   を使ったダブルブッキング検知 → escalate（hard にはしない。意図的な同時刻
   イベントもあり得るため）。confidence floor → escalate。`:event/share` は
   常に high-stakes（常に人間）。
5. **Phase 0→3**: teian と同型構成（0=ingest-only、1=assisted、
   2/3=draft は auto commit 可、share は常に人間）。
6. **注入 port（swap）**: Store（`MemStore` ‖ `DatomicStore`）/ Advisor
   （mock ‖ `langchain.model`）/ ScheduleTarget（mock ‖ 実 ICS + 実
   Distributor、承認後のみ呼ばれる）。
7. **CACAO 自己発行**（`koyomi.cacao`、kekkai/tayori と同型）: `.koyomi/
   identity.edn`（gitignore）。
8. **台帳 = 予定共有監査台帳（append-only）**。

### cloud-itonami からの利用

`deps.edn` に `io.github.kotoba-lang/koyomi {:local/root
"../../kotoba-lang/koyomi"}` を追加。`cloud_itonami.workspace` 投影層が
`:itonami.effect/kind :calendar/schedule-event` を `koyomi.operation` の
`:event/draft` request に変換し、`:event/share` の人間承認は既存
`cloud_itonami.approval`（ADR-0005）にそのまま乗せる。

## Consequences

- (+) 予定共有（下書き→承認→招待送信）が横断 actor に集約され、calendar の
  既存 EDN モデルを再利用できる。
- (+) 同意管理・テナント分離・no-actuation が governor の型で構造的に
  強制される（誤って社外に予定を漏らす経路が無い）。
- (−) 実 Distributor（実メール/カレンダー招待 API）の live 結合は未検証。
  既定は mock-scheduleport による決定的 sim で動く。
- (−) cloud-itonami 側 UI 配線は本 ADR の範囲外（別 PR）。

## Execution(closing, 2026-07-06)

| 項目 | 状態 | 備考 |
|---|---|---|
| repo scaffold(ScheduleTarget port・統一データモデル・StateGraph・governor・phase・store・CACAO 自己発行) | ✅ 完了 | initial commit `cd18c91` |
| `kotoba-lang/koyomi` GitHub repo 作成・push(public) | ✅ 完了 | `gh repo create` + `git push`、CI(lint/test)green |
| manifest 登録(`repos.edn` + `west.yml --entry koyomi`、pin 検証) | ✅ 完了 | pin == repo HEAD をサーバ側検証で確認 |
| 独立レビュー(governor/operation/ICS 生成中心の敵対的レビュー) | ✅ 完了 | confirmed 4 件: `:event/share` が govern 時点で検証済みの内容ではなく commit 時点で store を再読込する TOCTOU（承認待ちの間に attendee が consent-blocked に変わっても素通り）／governor に「subject(activity) 存在」チェックが無く rogue tenant が黙って auto-commit／ICS 生成が RFC 5545 未エスケープで自由文字列経由の ATTENDEE 行インジェクションが可能／draft 無しの `:event/share` が phantom send + 台帳への偽 `:shared` 記録を許す |
| 上記 4 件の修正 + regression test 追加 | ✅ 完了 | commit `0a2e2ee`。28 tests / 118 assertions(新規6件)、lint clean、sim 再検証。`koyomi.model` の `draft`/`contact` 未使用コンストラクタも配線して解消 |
| manifest pin 前進(`cd18c91`→`0a2e2ee`) | ✅ 完了 | `--entry koyomi` 最小 diff、サーバ側検証 OK |
| cloud-itonami 側 `workspace.cljc` 配線 | ✅ 完了 | `:calendar/schedule-event`/`:calendar/share-event` effect ハンドラ、`cloud-itonami.approval` 経由で人間承認、422 tests / 2949 assertions green |
| superproject `main` 反映 | ✅ 完了 | teian/koyomi/shoko/ichiran 一括登録 commit（feature branch経由のサーバサイドmerge） |

## References

- `orgs/kotoba-lang/kekkai`、`orgs/kotoba-lang/tayori`（同型 actor の手本）
- ADR-2607062000（`kotoba-lang/teian` — 同じ依頼から分岐した資料作成 actor）
- ADR-2607061500（`kotoba-lang/tayori` — consent-required HARD invariant の
  先行実装）
- ADR-2606302300（org taxonomy）
- ADR-2606272330（新規 project 一気通貫登録の実例・恒久承認）
- 本 ADR とペアの .edn
