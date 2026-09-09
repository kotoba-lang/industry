---
name: cua-bots
description: 画面操作の常駐 bot（CUA bot、selectable-backend）を 1 体だけ手で回す。名簿は manifest/cua-bots.edn、実行機構は kotoba-lang/computer-use の bin/cua_bot_run.cljs（backend 10 種から bot ごとに 1 keyword で選ぶ）。ローカル loop（com.gftd.cua-bots）が毎時 due を測って回すが、手で `/cua-bots <bot-id>` と打ってもよい。「cua bot」「画面操作 bot」「bots page を目視確認」「touch QA を回す」で発火。
---

# CUA bot を 1 体回す

**この skill は会話履歴を持たない fresh context から読める**ように書いてある。
状態は会話ではなく **receipt（`~/.itonami/cua-bots/receipts/<bot-id>/`）と
ledger（`~/.itonami/cua-bots/ledger.edn`）** から読む。

正本 ADR: `adr-2608291900-cua-bots-selectable-backend-residents`。

## 部品はどこに何があるか

| 何 | どこ | 誰が書く |
|---|---|---|
| **名簿**（どの bot が・何を・どの backend で） | `manifest/cua-bots.edn` | 人（policy、手書き） |
| **実行機構**（backend registry + session） | `orgs/kotoba-lang/computer-use` の `bin/cua_bot_run.cljs` | kotoba-lang/computer-use |
| due 判定 | `scripts/cua-bots-tick.cljs`（決定論） | superproject |
| 常駐 | `scripts/cua-bots-loop.cljs` + `com.gftd.cua-bots.plist`（1h） | superproject |
| receipt | `~/.itonami/cua-bots/receipts/<bot-id>/` | lib の session |
| ledger | `~/.itonami/cua-bots/ledger.edn`（追記のみ） | loop |

## backend の選び方（この system の中心）

backend は 10 種: `:macos-local` `:window-scoped` `:agent-space` `:fleet-node`
`:macos-vm` `:linux-container` `:cf-browser` `:cf-sandbox` `:saas-sandbox`
`:host-object`。**bot の昇格・降格 = 名簿の `:bot/backend` を 1 keyword
書き換えるだけ**（goal も手順も変わらない）。どの backend が何を提供するかの
正本は kotoba-lang/computer-use の registry —— **この表をここに写さない**
（写しは registry が進んだ日に嘘になる）。

## 1 体回す

```bash
# due を見る（決定論。モデル無し）
nbb scripts/cua-bots-tick.cljs

# 1 体を手で回す（lib の contract そのまま）
cd orgs/kotoba-lang/computer-use
nbb bin/cua_bot_run.cljs \
  --roster "$COM_JUNKAWASAKI_ROOT/manifest/cua-bots.edn" \
  --bot <bot-id> \
  --receipts-dir ~/.itonami/cua-bots/receipts/<bot-id> \
  [--dry-run]        # validate + backend probe だけ。行動しない
```

## fail-closed の語彙（混ぜない）

| exit | 意味 | 読み方 |
|---|---|---|
| 0 | **done** — session が完了した | receipt を読む。done ≠ 異常ゼロ（bot の仕事は異常の**報告**でもある） |
| 1 | **failed** — session が失敗した | 原因は receipt / stderr に在る。捨てない |
| 2 | **could-not-measure** — 測れなかった | **failed ではない。done でもない。** backend 不在・probe 失敗・依存 checkout 欠け。「問題なし」と読んだ瞬間に ADR-2608136000 の形になる |

loop 側の ledger も同じ 3 値 + `:not-measured :why :lib-missing/:cli-missing`
（lib がまだ landing していない周。この loop は lib より先に land してよい）。

## この名簿の初期 2 体

- `uiux-bots-page-qa`（`:window-scoped`、actions `#{:script}`）—
  itonami.cloud/bots/ の 3 セクション（Grok runtime / workstation loops / hyakka）が
  live データで描画されているかの目視相当 QA。
- `isekai-touch-qa`（`:host-object`、actions `#{}`）— jintori の touch 経路。
  いまは network-isekai の決定論 gate `nbb scripts/run-task.cljs jintori-touch`
  （`scripts/tasks.edn` の `:jintori-touch`、exit 2 を自分で申告する gate）を
  対象にしている。実 touch drag を駆動できる backend が qualify したら、
  `:bot/backend` の 1 keyword を書き換えて昇格する。

## 絶対にやらないこと

- **could-not-measure を green として報告しない**（loop も人も）
- 名簿に無い bot を回さない。名簿の形を崩す変更（id 重複・action 集合外）は
  tick が exit 2 で止める —— それを黙らせるために tick を緩めない
- backend の能力表をこの skill や名簿のコメントに書き写さない（registry が正本）
- receipt を書かずに「回した」と言わない
