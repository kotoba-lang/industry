# `hermes-aiueos-cfree` — aiueos kernel C-free programme (ADR-0220) を進める bot

`kotoba-lang/aiueos` の ADR-0220 は残る全 kernel C file の disposition と順番を
持ち、ADR-0219 / 0221 が 3 wave の方法 (退役 C から contract を採る → kernel-object
方言で書く → oracle で通し壊して落とす → kotoba-native の row と fuel tier → native
gate で recipe 再 compile → boot が落ちることを見る → provenance attest → pin 連鎖)
を固めた。この bot 群はその programme を **1 tick 1 step で実行に移す**側であり、
正本 (reviewable original) はこのディレクトリ。`~/.hermes/` 側へコピーして使う
(`scripts/hermes-aiueos-handoff/` と同型 — Hermes の job は local SQLite にしか無い)。

## 編成

| bot | 形態 | schedule | すること | しないこと |
|---|---|---|---|---|
| `aiueos-cfree` | workstation resident profile + cron (monitor gated) | `30 3,15 * * *` | ADR-0220 の順で 1 tick 1 step: 低 region 予算 → 未測定の測定 (wave 3 token rate) → 次の file。contract / object / gate / ADR / PR / pin まで | 2 file 同時、force-push、CLAUDE.md への測定値定数、K16 handoff (別 bot) |
| `aiueos-handoff` (既存) | 同上 | `30 1 * * *` | K16 物理 TCP handoff (ADR-2609031030) | programme の file 変換 |
| `aiueos-maint` (既存) | 同上 | 30min | CI 赤・issue triage・QEMU flaky | programme 起因の赤の重複 fix |

## ファイル

- `cfree_gate.cljk` — monitor。origin/main の kernel `.c`/`.S` 目録 (file / 行)、
  `os/aiueos/kotoba/*.kotoba` の個数、最大 ADR 番号、programme を名乗る open PR、
  superproject west.yml の aiueos pin。**決定的・時刻自由**、測れない行は `UNKNOWN`。
  実測 2026-09-16: 同一 tree で 2 回走らせて `cmp` 一致、`CFREE_REPO` を存在しない
  repo にすると tip / 目録 / ADR / PR の 4 節が `UNKNOWN` (0 ではない)。
- `cfree_gate.sh` — Hermes の `--script` は `.sh` を bash で、それ以外を Python で
  走らせる。この shim は `exec kbb --backend sci cfree_gate.cljk` の 1 行で logic を
  持たない (CLAUDE.md の「`.sh` を新規に書かない」に対する、起動制約が要求する最小限)。
- `cfree-tick.prompt.md` — agent の手順。ADR-0220 を順番の正本に指名し、12 段の
  反復手順と禁止事項 (捏造 / force-push / wholesale) を持つ。

## monitor 設計 (cost 規律)

cron `--monitor-script` に `cfree_gate.sh` を渡す。出力が前 tick と同一 (exact bytes)
なら agent を起動しない。origin/main が動いた (自分の PR が merge された、他者が
kernel を触った、ADR が増えた、pin が前進した) tick だけ agent が起きる。ホストは
load 100+ で常時 agent 起動の短周期 cron は作らない。

## install

```bash
P=~/.hermes/profiles/aiueos-cfree
H=~/.hermes/hermes-agent/venv/bin/hermes           # hermes は PATH に無い
$H profile create aiueos-cfree --clone-from aiueos-handoff \
   --description 'aiueos kernel C-free programme (ADR-0220): 1 step per tick, monitor-gated cron'
mkdir -p "$P/scripts"
cp scripts/hermes-aiueos-cfree/cfree_gate.sh scripts/hermes-aiueos-cfree/cfree_gate.cljk "$P/scripts/"
cp scripts/hermes-aiueos-cfree/cfree-tick.prompt.md ~/.hermes/scripts/aiueos-cfree-tick.prompt.md
```

workdir は aiueos の **専用 worktree** (superproject の checkout は閲覧専用):

```bash
cd orgs/kotoba-lang/aiueos && git fetch origin \
  && git worktree add -b bot/aiueos-cfree ~/.itonami/worktrees/aiueos-cfree-bot origin/main
ln -s "$PWD/../amu"  ~/.itonami/worktrees/amu    # ../amu  (native gate の AMU 既定)
ln -s "$PWD/../text" ~/.itonami/worktrees/text   # ../text (oracle の classpath)
```

cron 作成 (profile 内から):

```bash
HERMES_HOME=$P $H cron create "30 3,15 * * *" \
  "$(cat ~/.hermes/scripts/aiueos-cfree-tick.prompt.md)" \
  --name aiueos-cfree-tick --monitor-script cfree_gate.sh \
  --workdir ~/.itonami/worktrees/aiueos-cfree-bot --deliver local
python3 scripts/hermes-cron-jobs/export_cron.py      # 台帳へ merge (定義だけ)
```

## 出力規律

- 1 反復 = 1 step。報告は step / green-amber-red-unmeasured / 証拠 (vector 数、赤を
  見た方法、boot marker、object sha256、PR URL、pin commit) / 次の 1 step。
- link 成功・contract 緑・oracle 緑だけでは「走った」に昇格しない (aiueos CLAUDE.md §5)。
- 低 region 予算 (`aiueos_low_end <= 0x1f4000`、PT_LOAD 2 本) を見ずに object を
  足さない (ADR-0221)。
