# hermes-kumiai-kotoba — Kotoba 移行 frontier の watch

## 何のため

`cloud-itonami/cloud-itonami-isic-6820` の管理組合 actor は、判定核と発注ステップを
`.kotoba` に持ち、**JDK を拒否した環境で** build と acceptance を通している
（`scripts/kotoba_native_acceptance.cljs`）。この bot はその frontier を測り続ける:

- **gate が緑のままか**（退行の検出）
- **kotoba-lang/amu#835 が直ったか** —— native の keyword `=` が常に false という
  欠陥。直った日に `works_core.kotoba` の native block を外せる。gate 自身が
  「欠陥がまだ再現すること」を assert しているので、**直ると gate が赤くなる**
- **slice が増えたか / qualification が変わったか**（module header から読む）

## 正本と稼働の分離

正本はこのディレクトリ。稼働は端末ローカルの Hermes profile `kumiai-kotoba`。
**profile 側は写し**であり、編集はここで行って re-copy する。

## 3 つの exit code

```
0  走った（findings 無しなら silent）
1  gate が落ちた —— 退行、または amu#835 が直って unblock 可能
2  COULD NOT MEASURE —— checkout / toolchain / nbb が無い
```

**2 が 0 でも 1 でもないのが要点。** 測れなかった実行が、測って問題が無かった
実行と同じ値を返してはならない（CLAUDE.md 8 問、ADR-2609072000）。

加えて **evidence floor**: gate が自分の測定行を 1 つも印字しなかったら、
exit code が何であれ「走っていない」として exit 2 にする。

## 実行

```bash
python3 scripts/hermes-kumiai-kotoba/kotoba_migration_tick.py
KUMIAI_LEDGER=/tmp/x.jsonl python3 scripts/hermes-kumiai-kotoba/kotoba_migration_tick.py  # 隔離
```

## profile への配置

```bash
P=~/.hermes/profiles/kumiai-kotoba
mkdir -p $P/scripts $P/workspace
cp scripts/hermes-kumiai-kotoba/kotoba_migration_tick.py $P/scripts/
cp scripts/hermes-kumiai-kotoba/SOUL.md                  $P/SOUL.md
```

⚠ **新規 profile は `providers:` と `secrets.command` を持たないので、最初の
agent job は `blocked_config` で落ちる。** 直し方と、登録後に必ず
`hermes cron run <id>` で発火まで確かめる理由は
`scripts/hermes-cron-jobs/README.md`。**`[active]` は動くことではない。**
