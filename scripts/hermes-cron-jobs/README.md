# hermes-cron-jobs — cron 定義台帳（正本は repo、稼働は端末ローカル）

## 何のため

Hermes profile の cron job 実体（`~/.hermes/profiles/<p>/cron/jobs.json`）は
**端末固有の稼働状態**であり、git 管理されず他端末に同期されない。一方、
bot の役割・手順は `90-docs/business/*bot-profiles*.json` と各 runbook が
正本としてこの repo に住んでいる。この directory はその間のギャップを埋める:
**cron job の定義（schedule / prompt / script / deliver）を re-register 可能な
粒度で台帳化し、新端末での再構築を手順化する。**

## ファイル

- `export_cron.py` — 台帳生成器。全 profile の jobs.json を読み、state 系
  （last_run / next_run / execution id）を落として定義だけを
  `hermes-cron-jobs.json` に焼く。credential 値は形状検出して
  `_credential_warning` を付け、値そのものは絶対に書かない。
- `hermes-cron-jobs.json` — 生成物（手編集禁止）。schema
  `itonami.hermes-cron-jobs.v1`。

## 再生成・検査

```bash
python3 scripts/hermes-cron-jobs/export_cron.py          # 再生成
python3 scripts/hermes-cron-jobs/export_cron.py --check  # 差分検査 (CI/fleet gate にも使える)
```

`--check` が検出するのは **定義の変更だけ**。`repeat` は定義 (`times`) と
状態 (`completed`) の両方を運ぶので、`completed` は落としてある —— 落とす前は
**184 job のどれか 1 本が走るたびに STALE になり**、gate として使えば恒久的に
赤だった。実測 2026-09-06、この修正の前後:

| 出来事 | 修正前 | 修正後 |
|---|---|---|
| job が 1 回走った | STALE | up to date |
| job を 1 本足した | STALE | STALE |
| その job を消した | — | up to date |

**両方向を確かめてから landed とする**（落ちない検査も、常に落ちる検査も、
同じだけ無内容）。

## ⚠ 新規 profile を作った直後、agent job は必ず一度落ちる

`hermes profile create <p>` が書く `config.yaml` は `model.provider` に
`openrouter-free` を**書くが、その provider を定義しない**。`providers:` ブロックも
`secrets.command` も入らないので、最初の agent job は

```
[blocked_config] provider credential missing: Unknown provider 'openrouter-free'
```

で失敗する。**`hermes cron list` では `[active]` のままなので、実際に走らせるまで
分からない。** script job（`--no-agent`）は model を使わないので成功し、
「片方は動いているから設定は足りている」と読めてしまう。

直すのは config だけで、鍵に触る必要はない（鍵は login Keychain から
`secrets.command` 経由で入る）。稼働中の profile（例 `cron-health`）から
次の 2 ブロックを写す:

```yaml
secrets:
  command:
    enabled: true
    command: printf 'OPENROUTER_API_KEY=%s\n' "$(security find-generic-password -s gftd.openrouter -w)"
    helper_timeout_seconds: 10
providers:
  openrouter-free:
    base_url: https://openrouter.ai/api/v1
    api_mode: chat_completions
    key_env: OPENROUTER_API_KEY
    stale_timeout_seconds: 600
```

**登録したら必ず `hermes cron run <id>` で一度発火させ、`hermes cron runs` が
`completed` を記録することまで見る。** 実測 2026-09-06（`kumiai-sources`）:
`[active]` の 2 job のうち script 側は成功、agent 側は上記で失敗した。
台帳に載っていることも、`[active]` であることも、動くことではない。

## 新端末での re-register 手順

1. `hermes profile list` で profile を確認。無い profile は
   `hermes profile create <name>` で足す（SOUL.md は台帳や ADR から復元）。
2. 台帳の 1 job につき 1 回:

```bash
HERMES_HOME=~/.hermes/profiles/<p> hermes cron create '<schedule>' \
  --name <job-name> --prompt '<台帳の prompt>'
# script 付き job は台帳の script/workdir を渡す。
# deliver も台帳の値をそのまま指定する。
```

   - `once` kind（例: yui-weekly-resident の `once in 7d`）は次回時刻が
     台帳に無いので、意図した周期を owner 確認の上で指定し直す。
   - script は superproject 正本（例: `scripts/hermes-hyakka-bots/`）から
     profile 側へ re-copy してから登録する（re-copy 紀律は
     itonami-business-bots skill）。
3. credential を要する job は Keychain 参照（kagi 等）が解決できることを
   先に確認する — 台帳には値が無い（無いのが正しい）。
4. `hermes cron list`（HERMES_HOME 指定）で全 job が
   `[active]` になること、および gateway 生存（ticker heartbeat が新鮮）を
   確認してから「復元完了」と言う。list に載っているだけでは発火しない。

## 運用紀律

- **cron job を作る/変える/消したら `export_cron.py` を回して台帳を更新し、
  repo に着地させる。** 台帳の差分 = cron 変更の記録（git log が履歴を持つ）。
- schedule の錯開・差分検知 prompt・paused 理由記録などの設計規約は
  skill `bot-cron-organize` が正本。
- 台帳は「定義」だけを持つ。稼働状態（paused/running/failure_streak）は
  各端末の jobs.json と cron list が唯一の真実。
