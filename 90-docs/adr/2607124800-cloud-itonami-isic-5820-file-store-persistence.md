# ADR-2607124800: `cloud-itonami-isic-5820` に実ディスク永続化(`FileStore`)を追加、`DatomicStore`の誤称を発見・記録

- Status: Accepted (2026-07-12)
- 関連: ADR-2607124600（HTTP層追加）
- Scope: `/loop` 自己ペース継続タスク「成熟度を実運用まで」の第3サイクル

## Context

前サイクルのHTTP層追加時、`-main`が`crm.store/seed-db`（インメモリ
デモデータ）のみで動作し、プロセス再起動でstateが失われる、という
honest-scope上の既知ギャップを残していた。本サイクルでこれを解消する
作業を行ったところ、**より重要な発見**があった。

## 重要な発見: `DatomicStore` は実際には永続化されていない

`crm.store/DatomicStore`（`crm.store/datomic-store`）は、この actor の
最初のbuildから存在する実装で、名前とこれまでのADR記述
（「`MemStore` ‖ `DatomicStore` parity」）から実務者は「本番では
Datomicに差し替えられる永続バックエンド」と誤解しやすい。しかし
実装を読むと、そのコンストラクタは
`(->DatomicStore (langchain.db/create-conn schema))` であり、
`langchain.db/create-conn` は単なる `(atom {:db ... :log []})` を
返すだけ——接続URIも、ソケットも、ファイルも無く、JVMヒープの外に
何も残らない。つまり**`DatomicStore`はDatomic APIの形をしている
だけで、Datomicにbackされていない**。`MemStore`と全く同じ寿命しか
持たない。

`store_contract_test.clj`が証明する「`MemStore` ‖ `DatomicStore`
parity」は read/write の互換性としては真実であり有用だが、
「どちらも永続化はしていない」という文脈を欠いたまま「Datomic」を
名乗ることは、実運用判断を誤らせるミスリードだった。

この命名パターンはこのfleet全体（140超のcloud-itonami-isic-####
actor）で同一の慣習(`MemStore`‖`DatomicStore`)として繰り返されており、
同種の誤解が他のactorにも存在する可能性が高い。本ADRは
`cloud-itonami-isic-5820`単体の是正のみを行い、fleet全体の監査は
スコープ外として明示的に記録するに留める（オーナー判断で別途着手
可能）。

## Decision

1. 実Datomic接続への配線は本サンドボックスでは実現不可能と判断
   （`crm.store`を`:db-api`注入型へリファクタしlanggraph経由の実
   kotoba-serverまたはDatomic Localプロセスに接続する必要があるが、
   稼働中podも認証情報もこの環境には無い）。**「永続化しているように
   見えるが実際はしていない」ものを偽装しない**という判断のもと、
   `ISIC5820_DATOMIC_URI`のようなenv varで`DatomicStore`を選択させる
   実装は行わなかった。
2. 代わりに `src/crm/file_store.clj` を新規実装: 変更ごとに全DBを
   EDNスナップショットとしてディスクに書き込み(write-then-rename、
   crash時に旧snapshotを保護)、起動時に読み込む`FileStore`。
   `$ISIC5820_STORE_FILE`設定時はこれを使用、未設定時は従来の
   `seed-db`にフォールバックするが**stderr警告を必ず表示する**
   （以前は無音でephemeral動作していた）。
3. **実プロセスでのend-to-end検証を実施**: `clojure -M:serve`を実OS
   プロセスとして起動→`POST /propose`で実HTTP経由コミット→ファイル
   変更確認→`kill -9`→再起動→`GET /dashboard`と独立したgovernance
   check(`stage-sequence-gate`が復元後のstateに基づき逆行遷移を
   正しく拒否)の両方でstate復元を確認。単体テストだけでなく実プロセス
   でのkill/restartまで検証済み。
4. honest scope明記: single-writer専用（複数プロセスの同時書き込みは
   非対応）、query engineでもtx historyでもない、単純なsnapshot
   永続化。

## Consequences

- (+) `cloud-itonami-isic-5820`が初めて、プロセス再起動を跨いで実際に
  stateを保持できるようになった。
- (+) 「偽の永続化を実装しない」という判断により、`DatomicStore`の
  誤称という潜在的なfleet全体の問題を発見・文書化できた——これは
  「推測せず、常に正直に報告する」というfleetの規律が実際に機能した
  実例。
- (+) end-to-end検証（実プロセスkill/restart）を行い、単体テストの
  範囲を超えた実証を得た。
- (-) 真のDatomic/kotoba-server接続は依然として未実装。将来
  `crm.store`を`:db-api`注入型にリファクタし、稼働中podに接続する
  作業が必要。
- (-) `FileStore`はsingle-writer専用で、水平スケール・複数プロセスでの
  共有には使えない。
- (-) fleet全体（他139超のactor）の`DatomicStore`命名が同様の誤解を
  招く可能性は、本ADRでは指摘のみに留め、監査・是正はスコープ外。

## Alternatives considered

| Option | Verdict | Reason |
|---|---|---|
| `DatomicStore`をenv varで選択可能にする(見せかけの永続化) | ❌ | 実際には永続化しないものを永続化として提示するのは、本番でのデータ消失事故に直結する誤誘導。fleetの「推測しない」規律に反する |
| リファクタして実kotoba-server podに接続する | ❌(今回は不可) | 稼働中pod・認証情報がこのsandboxに存在しない。フェイクせず follow-up として記録 |
| ファイル永続化を諦めseed-dbのまま警告だけ追加 | ❌ | 警告追加自体は良いが、実運用に近づけるという目的からすると不十分。実現可能な範囲(disk永続化)まで踏み込んだ |

## References

- ADR-2607124600（HTTP層）
- `cloud-itonami-isic-5820/src/crm/file_store.clj`(新設、docstringに
  DatomicStore誤称の詳細な経緯を記載)
- `cloud-itonami-isic-5820/docs/api.md`(Persistence節を追記)

## Verification Notes

- commit `41927ea`、push済み。48 tests / 170 assertions、lint clean。
- 実プロセスでのkill/restart検証済み(このADR本文に記載の手順どおり)。
