# ADR-2607124900: `cloud-itonami-isic-6201` に実ディスク永続化(`FileStore`)を追加、`DatomicStore`誤称を確認(5820と同一問題)

- Status: Accepted (2026-07-12)
- 関連: ADR-2607124800（`cloud-itonami-isic-5820`のFileStore、直接の手本・
  DatomicStore誤称の初出）、ADR-2607124700(6201のHTTP層)
- Scope: `/loop` 自己ペース継続タスク「成熟度を実運用まで」の第4サイクル

## Context

前サイクルで`cloud-itonami-isic-5820`の`DatomicStore`が実際には
永続化されていない(単なるインメモリatom)ことを発見した。6201にも
同種の`DatomicStore`が存在するため、**推測せず実装を直接確認**した
ところ、全く同じ問題(`(->DatomicStore (langchain.db/create-conn
schema))`、`langchain.db/create-conn`は単なるatom)であることを
確認した。さらに、6201の`-main`のdocstringは修正前まで「永続化したい
運用者は自分で`DatomicStore`をインスタンス化せよ」と**誤った助言**を
していたことも判明(5820の修正前と全く同じ誤り)。

## Decision

1. `src/marketing/file_store.clj`を新設。5820の`crm.file-store`を
   直接の手本とし、write-then-rename EDNスナップショット方式・
   load-or-seed挙動を、このactor自身の`Store`プロトコルメソッド
   （`contact`/`all-contacts`/`campaign`/`all-campaigns`/`send-record`/
   `engagement-history`/`ledger`等）に合わせて適応。
2. `marketing.http/-main`を`$ISIC6201_STORE_FILE`で分岐: 設定時は
   実`FileStore`、未設定時は`seed-db`にフォールバックしつつ
   stderr警告を必ず表示。
3. **実プロセスでのend-to-end検証**: `clojure -M:serve`を実OS
   プロセス(PID記録)として起動→`POST /advance-stage`で実HTTP経由
   コミット(`:lead`→`:mql`)→ディスク上のEDNに反映確認→`kill -9`→
   同一ファイルで再起動→`GET /dashboard`のstage-counts変化で復元確認
   →独立したgovernance check(`stage-sequence-gate`の拒否メッセージが
   復元後の`:mql`を正しく参照——freshシードなら`:lead`になるはずの
   値)で二重確認。単体テストに留まらず実プロセスのkill/restart
   トランスクリプトを取得済み。
4. 誤った`-main`docstringのDatomic永続化助言を修正。

## Consequences

- (+) `cloud-itonami-isic-6201`もプロセス再起動を跨いだstate保持が
  可能になり、5820とのパリティが取れた。
- (+) 「推測せず直接確認する」規律により、5820で発見した問題が
  6201にも存在することを**当て推量でなく実証的に**確認できた——
  fleet全体で同種の問題が広がっている可能性の裏付けが1件増えた。
- (-) 真のDatomic/kotoba-server接続は依然未実装。6202
  （customer-service、別セッション）は本ADRの対象外のまま。
- (-) 6202の`DatomicStore`(存在するなら)が同種の問題を持つかは
  未確認——今回もscope外として明示的に残す。

## Alternatives considered

5820(ADR-2607124800)と同一の理由・却下パターンにつき、そちらを参照。
本ADR固有の追加判断は無い。

## References

- ADR-2607124800（`cloud-itonami-isic-5820`のFileStore、直接の手本）
- `cloud-itonami-isic-6201/src/marketing/file_store.clj`(新設)

## Verification Notes

- commit `7c26335`、push済み。56 tests / 202 assertions(既存52/188から
  4 tests/14 assertions追加)、lint clean。
- 実プロセスでのkill/restart検証済み(このADR本文に記載の手順どおり、
  かつ独立したgovernance check経由での二重確認あり)。
