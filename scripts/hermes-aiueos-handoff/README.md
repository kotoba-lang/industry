# `hermes-aiueos-handoff` — ADR-2609031030 の handoff を実行する bot 群

ADR-2609031030 (`90-docs/adr/2609031030-aiueos-k16-pure-kotoba-physical-tcp-handoff.edn`)
は「別 agent が会話/一時 dir 無しで安全かつ正直に再開できる」ことを目的に書かれた。
この bot 群はその handoff を**実行に移す**側であり、正本 (reviewable original) は
このディレクトリ。`~/.hermes/` 側へコピーして使う (ADR-2608271450 の itonami/hyakka
bots と同型 — Hermes の job は local SQLite にしか無い)。

## 編成

| bot | 形態 | schedule | すること | しないこと |
|---|---|---|---|---|
| `aiueos-handoff` | workstation resident profile + cron (monitor gated) | `30 9,21 * * *` | gate N1〜N6 を 1 tick 1 gate で進行。contract へ証拠追記、non-force push、PR | 実験 branch の wholesale merge / root pin 前進 / 捏造 |
| `aiueos-maint` (既存) | workstation resident + cron 30min | `23,53 * * * *` | CI 赤・issue triage・QEMU flaky (既存のまま) | handoff 系の重複 fix |

責任境界は ADR §現在の責任境界 に従う: Kotoba source / Amu (`13d2f5dfe1ad…`) /
capability 4 repo (exact SHA) / AIUEOS / `org-ietf-tls` / Kototama / Kotobase/IPFS /
`auth.kotoba.cloud` / Murakumo / infer-num-torch。bot は AIUEOS+Amu+capability の
層だけを触り、Murakumo node 登録 (N5) と Qwen receipt (N6) は各々該当層の
authority が持つ gate として**記録だけ**行う。

## monitor 設計 (cost 規律)

cron `monitor` に `handoff_gate.sh` を渡す。出力が前 tick と同一なら agent を
起動しない (決定的・時刻自由な出力のため)。branch tip / PXE log marker / PR 一覧が
動いた tick だけ agent が起きる。ホストは 130+ load で crash loop するため、
常時 agent 起動の 30min cron は作らない。

## install

```bash
mkdir -p ~/.hermes/profiles/aiueos-handoff/scripts
cp scripts/hermes-aiueos-handoff/handoff_gate.sh       ~/.hermes/profiles/aiueos-handoff/scripts/
cp scripts/hermes-aiueos-handoff/handoff-tick.prompt.md ~/.hermes/scripts/aiueos-handoff-tick.prompt.md
```

cron 作成 (profile 内から):

```bash
HERMES_HOME=~/.hermes/profiles/aiueos-handoff hermes cron create \
  "30 9,21 * * *" "$(cat ~/.hermes/scripts/aiueos-handoff-tick.prompt.md)" \
  --name aiueos-handoff-tick --script handoff_gate.sh --workdir <aiueos worktree> \
  --deliver local
```

実機再開には `~/Library/Application Support/AIUEOS/K16 PXE/` (PXE artifact +
`run-k16-pxe.sh` + launchd `cloud.murakumo.aiueos-k16-pxe`) と
`~/Library/Logs/AIUEOS/k16-pxe.stdout.log` (wire marker) がこの Mac 上に必要。

## 出力規律

- 1 反復 = 1 gate。報告は gate / green-amber-red-unmeasured / 証拠 (screen code,
  wire marker, artifact hash, host test) / 次の 1 gate。
- monitor 表示だけ・Mac service ready だけ・QEMU だけ・HTTP 200 だけでは上位 gate へ
  昇格しない (ADR Consequences 第 3 条)。
- `43` 停止の単一根因は未分離 — 三変更の A/B 分離実験をするまでは
  「根因は確定していない」と書く。
