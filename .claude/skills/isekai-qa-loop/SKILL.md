---
name: isekai-qa-loop
description: isekai.network のゲームを agent が実際にプレイして品質を検査し、見つけた欠陥を 1 件だけ直して着地させる。1 反復 = gameplay 実測 + visual 実測 + 修正 1 件。ローカル Claude loop（com.gftd.isekai-qa-loop）が毎周これを呼ぶが、手で `/isekai-qa-loop` と打ってもよい。「ゲームの QA loop」「isekai の品質を上げる」「playtest loop」「visual test を回す」で発火。
---

# isekai のゲームを実際に遊んで、欠陥を 1 件直す

**この skill は会話履歴を一切持たない fresh context から読める**ように書いてある。
前の反復が何をしたかは会話ではなく **evidence EDN と git log** から読む。

対象リポジトリ: `orgs/network-awai/network-isekai`

## この loop が存在する理由

2026-08-08 の実測がこの loop を生んだ。カタログ 29 本のゲームプレイ gate は本番に対して
**29/29 で通っていた**。gate が緑だったのは、それが「プレイヤーが宣言速度で動くか」しか
訊いていなかったからである（ADR-0074 はまさに「どの gate も *そのゲームは動くのか* を
訊いていない」ために作られた。次の死角は **動く先に何かあるのか** だった）。

**緑の gate は品質の証明ではない。訊いていない質問については何も言っていない。**
この loop の仕事は、毎周ひとつ新しい質問を実際に訊くことである。

**そして 2026-08-17、この節自身がその教訓の実例になった。** 初版はここで
「`gftd/palisade` は entity 1、対戦相手が 1 体もいなかった。3.8 秒の窓で測り直しても
変わらない」と断言していた。**間違いだった。** 窓は開いていたが、数は**窓が開く前の
frame** から読まれていた（`:entities` は t=0 の snapshot）。窓の後で数えると palisade は
2、`waves` は 21。**「測り直しても変わらなかった」のは、測り直していなかったからである。**

つまりこの loop を生んだ発見そのものが、それが警告している形をしていた ——
**新しい質問を訊いたつもりで、古い答えを読んでいた。** 詳細は network-isekai の ADR-0080。

## 1 反復でやること

### 1. 現在地を測る（作る前に測る）

```bash
cd orgs/network-awai/network-isekai
git fetch origin && git log --oneline -1 origin/main

# gameplay: 本番に対して実 Chrome + 実キーイベント + ECS tick（42 本中 30 本 steerable、約 12 分）
PLAYTEST_URL=https://isekai.network \
  nbb scripts/isekai/playtest_headless.cljs --out /tmp/qa-gameplay-$(date +%Y%m%d).edn
```

出力の読み方:

| 行 | 意味 |
|---|---|
| `PASS` / `FAIL` | プレイヤーが宣言速度で動いたか。**これだけでは品質を意味しない** |
| `SUSPECT` | 動いたが宣言速度の 25% 未満。collider が食っている可能性があり、失敗にはしない |
| `LONELY` | **combat を宣言しているのに entity が 1** = 相手が居ない。`adversary-missing-baseline` に載っている既知分 |
| `combat title(s) ... NOT in baseline` | **新しく増えた。これは直すか、日付と理由つきで baseline に足す** |
| `now has an opponent` | 既知分に相手が付いた。**baseline から消す**（古い allowlist は嘘になる） |

`:adversary-missing` の判定は現在 **報告のみで exit code を左右しない**。2026-08-17 に
「どの snapshot を読むか」を訂正したばかりなので（`:entities` t=0 → `:entities-end` 窓の後）、
訂正後の計測が本番で数周 安定してから enforcing に上げること。**上げるときは、壊したコピーで
exit 1 になることを実際に見てから**（`gftd/waves` の spawner を潰したコピーで両方向は
確認済み）。

### 2. visual を測る

```bash
# 本番の実レンダリング（loading を抜け、640x360 以上に 3% 以上の非黒 pixel、error overlay 無し）
ISEKAI_M6_VISUAL_URL='https://isekai.network/play?game=gftd/palisade' \
ISEKAI_M6_VISUAL_OUT=/tmp/qa-visual-$(date +%Y%m%d) \
  clojure -M:m6-visual -m isekai.m6-visual-gate
```

より深い視覚評価が要るなら **playtest co-scientist**（実 Chromium プレイスルー →
実 vision critic → qa-governor → 追記型台帳）を使う。**新しく作らないこと** ——
`scripts/isekai/playtest_coscientist.clj` に既にある（ADR-0060）。

```bash
MURAKUMO_CLAUDE_TOKEN=$(kagi get MURAKUMO_CRITIC_TOKEN) \
  nbb scripts/run_playtest_coscientist.cljs --game gftd/palisade --rounds 2
```

⚠ **critic token が取れない機では vision critic が静かに offline heuristic に劣化する**
（ADR-0060 が明記）。劣化したまま「vision で評価した」と報告しない。取れないなら
その反復では visual は m6 gate だけにして、劣化した旨を残す。

### 2.5 「実際に遊ばせる」なら既存の autoplay を先に見る

`kotoba-lang/loop-game-autoplay` は **実ゲームをプレイする policy を進化させ、
champion を実機 iPhone Simulator で検証してフレームを記録する** loop である。
ゲームは改変せず、出荷済みページに driver を注入し、ゲームが既に持っている状態を読み、
ゲームが既に polling している key map を書く。

```bash
nbb --classpath src:../shinka/src -m loop-game-autoplay.train \
    --game <path-or-url> --generations 12 --population 24 --episode-ms 60000 --seeds 3
nbb --classpath src:../shinka/src:../hinshitsu/src -m loop-game-autoplay.qualify \
    --champion target/run-champion.edn --seed 1
```

この skill の gate は **20〜240 tick の決定的な短い窓**で「動くか / 相手が居るか」を訊く。
autoplay は **60 秒級のエピソードを何世代も回して「遊べるか」**を訊く。役割が違うので
どちらも要るが、**後者を新しく書かないこと。**

この項は 2026-08-08 の追記である。初版はこの repo の存在を確認せずに書かれた ——
「無いと結論する前に検索する」を skill の著者自身が守れていなかった。
`nbb scripts/repo-search.cljs <語>` は 4,140 repo を引く。**手元に無いことは存在しない
ことではない。**

### 3. 欠陥を 1 件選んで直す

優先順（上ほど先）:

1. **`combat title(s) ... NOT in baseline`** — 新しく相手を失ったもの
2. **`FAIL`** — 動かないゲーム
3. **`now has an opponent`** — baseline の掃除（1 行削除）
4. **`SUSPECT`** — 宣言速度と実測の乖離
5. **`LONELY` の既知分に相手を実装する** — 一番価値が高いが一番重い。
   1 反復では **1 本だけ**。⚠ **着手する前に、その数がどの snapshot から来たか確かめる**
   （`:entities` は t=0、`:entities-end` は窓の後）。2026-08-08〜17 の baseline 3 本は
   **全部これで誤っており**、実装していれば既に相手が居るゲームに 2 体目を足していた

**gate 自身の欠陥も 1 件に数える。** ADR-0074 の初回本番実行では、5 件の「失敗」のうち
4 件が harness 由来だった（CDP ポート固定・引数解釈・例外メッセージの欠落）。
**自分の gate の出力を、それが測れていないものの証拠として読まないこと。**

### 4. 着地させる

- 共有 checkout で作業しない。`git worktree add -b agent/<name> <path> origin/main`
  を **superproject の外**に切る。sibling `:local/root` を解決するため
  `<path>` は `<x>/network-awai/network-isekai` の形にし、`<x>/kotoba-lang` を
  `orgs/kotoba-lang` への symlink にする
- **gate を足す/変えたら、壊したコピーで exit 1 になることを実際に見る。** 落ちない gate は劇場
- `gh api repos/network-awai/network-isekai/merges` でサーバ側マージ。rebase / force-push はしない
- worktree と branch を消すまでが完了条件

### 5. 記録する

- evidence EDN を `90-docs/evidence/` に置く（`catalog-gameplay-<date>.edn` の形）
- 判断を伴ったら ADR を 1 本（`.edn` tx-data、`nbb scripts/isekai/adr_edn_check.cljs` を通す）
- **数えた分母を偽らない。** 対象外にしたものは理由つきで報告する（`out-of-reach` の作法）

## 絶対にやらないこと

- **捏造しない。** 測っていない値を書かない。vision critic が劣化していたらそう書く
- **緑にするために質問を消さない。** baseline に足すのは、日付と理由と、次に誰が解くかを
  書いたときだけ
- **art direction を代行しない。** 「junction 建物を減らして authored な遮蔽物を出すか」の
  ような、declared な何かを失うトレードオフは owner の判断（ADR-0075 / 0078）。
  測って選択肢を提示するところまでが engineering
- **本番に書き込むテストをしない。** gate は読み取りとローカル ECS tick だけ

## 現在地（2026-08-17、次の反復はここから読む）

| | 実測 |
|---|---|
| catalog | 42 本（29 ではない）。うち steerable 30 / declared-stationary 11 / data-only 1 |
| gameplay | **30/30 PASS（本番、deploy 後）** |
| adversary | **LONELY ゼロ。baseline は空。** 下記の訂正を読むこと |
| link card | 42/42 に実フレームの 1200x630 PNG。`og:`/`twitter:` は edge 注入（ADR-0080） |
| aozora.app | 42 本すべて登録（`palisade` を含む 3 本が欠けていた）。avatar は実フレーム |
| render load | royale = 197 instance / 5,184 triangle / 材質 3 / 外部アセット 0 |
| AAA signoff | 物理 GPU 1/6 class、frame p95 16.8ms vs 目標 16.7ms、stock 凍結中 |
| scene 7 指標 | 全て `-1`（未測定）。校正済みリファレンス待ち = art-direction |
| playtest co-scientist | 2026-08-17 05:00 の standing run あり（`playtest-coscientist-*` branch） |

### ⚠ 2026-08-08 版のこの節が推奨していた「最初の一手」は間違いだった

旧版はこう書いていた ——「`palisade` に対戦相手を 1 体入れる。訪問者が最初に触るのが
敵のいないバトルロイヤルになっている」。**palisade には最初から相手が居た。**

`:entities` は **t=0 の snapshot** から読まれていた。spawner が発火するために*わざわざ
開けた* 240 tick の窓を、**窓が開く前のフレーム**から判定していた。窓の後で数え直すと
palisade 1→2 / royale 1→2 / waves 1→**21**。palisade の HUD は同じ snapshot から
`ALIVE 2` を 9 日間 描き続けていた。

**実行していれば、既に相手が居るゲームに 2 体目を足していた。** gate の出力を、それが
測れていないものの証拠として読むな —— この skill 自身がその見本になっていた。

**今の最初の一手**: 決まったものは無い。**測ってから選ぶ**。`:entities-end` と
`:tags-end` が evidence EDN に入るようになったので、`1 → N` の N と tag の内訳を見て、
declared な genre に対して薄いものを探す。adversary 判定は**まだ exit code を左右しない**
（訂正後の計測が本番で数周 安定してから enforcing に上げること）。
