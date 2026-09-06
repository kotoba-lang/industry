# hermes-kumiai-sources — 管理組合カタログの一次資料 watch

## 何のため

`cloud-itonami/cloud-itonami-isic-6820` の管理組合 actor
（`realty.kumiai.facts`）は「一次資料から読めた法域だけが決議要件テーブルを
持つ」という規則で動く。この bot はその線が時間とともに嘘にならないように
する:

- **未検証の法域**（ITA / CHN）の候補 URL を回し、本文が返るようになったら
  「窓が開いた」と報告する。
- **検証済みの法域**の出典を再測定し、読めなくなったら regression として
  報告する（出典は移動する。SGP は法律の改題で識別子ごと変わった）。

## 正本と稼働の分離

正本はこのディレクトリ。稼働は端末ローカルの Hermes profile
`~/.hermes/profiles/kumiai-sources/`。**profile 側は写し**であり、
編集はここで行って re-copy する（`scripts/hermes-cron-jobs/README.md` の
re-copy 紀律と同じ）。

| ファイル | 役割 |
|---|---|
| `sources.json` | 何を、どの候補 URL で、どの marker で測るか |
| `probe_kumiai_sources.py` | 測定器。**status ではなく本文**で分類する |
| `SOUL.md` | bot の判断規則と報告形式。profile 直下に置く |

## 設計上の一点

**`grep` を使わない。** 独 gesetze-im-internet.de は ISO-8859-1 を返し、
shell の `grep` はそれを binary と見なして**理由を言わずに 0 件**を返す。
「読めなかった検査」と「読んで見つからなかった検査」が同じ値になるのは
このワークスペースが繰り返し警告している形なので、probe は明示的に復号し、
復号できなければ `undecodable` という**別の答え**を返す。

## 再生成・実行

```bash
python3 scripts/hermes-kumiai-sources/probe_kumiai_sources.py   # 単発（ledger は profile 側）
KUMIAI_LEDGER=/tmp/x.jsonl python3 scripts/hermes-kumiai-sources/probe_kumiai_sources.py  # 隔離して実行
```

exit 0 = 走った / exit 2 = **全 source がエラーで何も測れなかった**
（0 でも 1 でもない値にしてあるのは、測れなかった実行が「問題なし」と
同じ顔をしないため）。

## profile への配置

```bash
P=~/.hermes/profiles/kumiai-sources
mkdir -p $P/scripts $P/workspace
cp scripts/hermes-kumiai-sources/probe_kumiai_sources.py $P/scripts/
cp scripts/hermes-kumiai-sources/sources.json            $P/scripts/
cp scripts/hermes-kumiai-sources/SOUL.md                 $P/SOUL.md
```

cron job の定義は `scripts/hermes-cron-jobs/hermes-cron-jobs.json`
（`export_cron.py` が生成）に台帳化される。

**新規 profile は `providers:` と `secrets.command` を持たないので、最初の
agent job は `blocked_config` で落ちる。** 直し方と、登録後に必ず
`hermes cron run <id>` で発火まで確かめる理由は
`scripts/hermes-cron-jobs/README.md` に書いた。実測 2026-09-06 の
`kumiai-sources` はこれを踏んでいる —— script job は成功し、agent job だけが
落ちたので、片方の成功を設定の十分性と読むと見逃す。
