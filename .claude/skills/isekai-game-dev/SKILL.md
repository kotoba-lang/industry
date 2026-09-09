---
name: isekai-game-dev
description: isekai.network のゲームを 1 増分だけ制作して着地させる（登録漏れ 1 件を塞ぐ、または planned なゲームを 1 増分進める）。1 反復 = 1 production step。ローカル Claude loop（cloud.itonami.bot.isekai-game-dev）が候補のある周だけこれを呼ぶが、手で `/isekai-game-dev` と打ってもよい。「ゲーム制作」「新しいゲームを作る」「isekai game dev」「game production loop」で発火。
---

# isekai のゲームを 1 増分だけ作る

**この skill は会話履歴を一切持たない fresh context から読める**ように書いてある。
前の反復が何をしたかは会話ではなく **tick の出力・`~/.itonami/isekai-game-dev/ledger.edn`・
git log** から読む。

対象リポジトリ: `orgs/network-awai/network-isekai`（isekai.network）

## この skill と isekai-qa-loop の分担

**この skill は作る側。直す側は既存の `/isekai-qa-loop`。** 出荷済みゲームの欠陥
（FAIL / LONELY / SUSPECT / visual）を見つけて直すのは isekai-qa-loop の仕事で、
**ここで重複してやらない**。この skill がやるのは:

1. **登録漏れを塞ぐ** — ゲームは在るのに {game.edn / content-rating / thumbnail} が欠けている
2. **planned なゲームを 1 増分進める** — benchmarks catalog の :planned / :next

## この反復の仕事はちょうど 1 つ

```bash
nbb scripts/isekai-game-dev-tick.cljs     # 最終行に EDN で候補が出る
```

- `:outcome :candidate :kind :registration-gap` — `:candidate` の 1 ゲームの
  `:missing`（game.edn / :content-rating / thumbnail.svg）を塞ぐ。**1 件だけ。**
- `:outcome :candidate :kind :mobile-input-gap` — `:candidate` の 1 ゲームに
  touch 入力経路を通す（下記のレシピ）。**1 件だけ**（`:all-mobile-input-gaps` に
  他の該当が並んでいても、この周は candidate の 1 本）。
- `:outcome :candidate :kind :new-game` — `:candidate` の 1 sample を
  **1 つの具体的な増分**だけ進める（下記）。完成させようとしない。
- `:outcome :no-candidates` — 何もしない。無い仕事を作らない。
- `:outcome :not-measured` — **候補 0 件ではない。** 測れなかった理由（checkout が無い /
  catalog が読めない / gh が答えない）を報告してこの周は終わる。

2 件まとめない。「ついでに」QA しない。この loop の価値は **1 周 1 増分が確実に
着地すること**であって、件数ではない。

## 作業場所（共有 checkout を触らない）

このマシンは並行 agent が走っている。west 管理の `orgs/network-awai/network-isekai` を
直接編集しない。sibling `:local/root`（kotoba-lang 群）を解決するため、worktree は
`<x>/network-awai/network-isekai` の形にし、`<x>/kotoba-lang` を symlink する:

```bash
X=/tmp/igd-$(date +%m%d)
mkdir -p $X/network-awai
ln -sfn "$COM_JUNKAWASAKI_ROOT/orgs/kotoba-lang" $X/kotoba-lang
git -C orgs/network-awai/network-isekai fetch origin
git -C orgs/network-awai/network-isekai worktree add -b agent/game-dev-$(date +%m%d) \
  $X/network-awai/network-isekai origin/main       # 分岐元を明示する
```

## 作り方

- **Model-A を踏襲する**: 1 ゲーム = `public/games/<ns>/<game>/` に
  `game.edn`（宣言）+ `scene.edn`（scene）+ `logic.cljc`（ECS logic）。
  renderer 既定は **sprite2d**。既存の同 genre のゲーム
  （例: `public/games/gftd/goriketsu`、`:template` が指すもの）を読んでから書く。
  3D が要るものは CLAUDE.md の kami-engine 規則に従う（第2エンジンを書かない）。
- **new-game の「1 増分」の例**: game.edn + scene.edn の骨格を置いて catalog の
  :status を :in-progress に進める / 既に骨格があるなら core loop の 1 mechanic を
  実装する / 動くようになったら登録一式（下記）を通して :playable に上げる。
  **1 反復でどれか 1 つ。**
- **登録の再生成**: ゲームを足したり登録を直したら、feed-index・content-ratings 投影・
  thumbnail を再生成する。生成スクリプトは `scripts/isekai/` に既に在る
  （`content_ratings.cljs` → `functions/api/_lib/content-ratings-catalog.mjs` は
  生成物、手編集しない）。thumbnail.svg が無いゲームは実フレーム由来か SVG で作る。
- **content-rating は自己申告データ**: `resources/content-ratings.edn` に
  `"/<ns>/<game>"` の entry を、scene.edn / logic.cljc の実際の語彙を根拠
  （:evidence）にして書く。**中身を見ずに書かない。**

## :mobile-input-gap の直し方

tick の判定は「logic.cljc が `key-pressed?` を読み、`(axis …)` を一度も読まない」。
kami.input（`orgs/kotoba-lang/host` の `src/kami/input.cljc`）の pointer 経路は
**axes しか出さず actions は keyboard からしか発火しない**ので、この形の logic には
touch が構造的に届かない（実例: gftd/jintori、オーナー報告 2026-08-29）。
diff は最小に。**art direction は変えない。**

1. **scene.edn の `:input` に stick を宣言する**:
   `:sticks [{:x "MoveX" :y "MoveY" :dead 0.25}]`
   動く実例は `public/games/gftd/at6-texan/scene.edn`（2 本 stick + `:zone`）。
   1 本で足りるゲームは zone 無しの全面 1 本でよい。UI と干渉するなら
   at6-texan に倣って `:zone` を絞る。
   （MoveX/MoveY の :axes を持つ scene は kami.input の default stick でも axes が
   出るが、宣言を書くのが catalog の作法 —— 意図が scene から読めるようにする。）
2. **logic.cljc は key-pressed? を残して axis に fallback する**:
   方向 = keyboard の action がどれか押されていればそれ、押されていなければ
   `(axis "MoveX")` / `(axis "MoveY")` を読み、**±0.35 で -1/0/1 に量子化**して
   **優勢な軸を採る**。⚠ **f32 比較だけで書く** —— guest DSL に f32→int 変換は
   無く、catalog 標準の guest `axis` は f32 を返す（`0.35` との `<` / `>` 比較と
   分岐で -1/0/1 を選ぶ。cast を書かない）。
3. **HUD の操作ヒントに touch / drag を足す**（キーボードの記述は残す）。
4. **検証**: そのゲームの既存 headless harness に **axis 入力の assertion を
   1 本足して**回す（touch 経路が logic に届くことを、キー入力とは別に見る）。
   full build は resource-guard 経由
   （`node scripts/resource-guard.mjs run build -- <command>`、直接叩かない）。
5. **deploy は明示 `--branch main`**。plain な worktree からの deploy は
   **preview を作るだけで本番に載らない**（実測済み）。deploy 後、本番が
   変更を配っていることを実際に見る（該当 JS / scene を curl して確認）。

対象が 2 軸操作に写像できないゲーム（rhythm lane 系など）だった場合は、
新しい UX を発明しない —— それは art direction。理由を ADR に書いて、
`:all-mobile-input-gaps` の次のゲームをこの周の対象にする。

## 記録

- 判断を伴ったら ADR を 1 本、`.edn` tx-data で書き、
  `nbb scripts/isekai/adr_edn_check.cljs` を通す（heredoc で書かない — Write tool）。
- **捏造しない。** 測っていない値を書かない。動作確認していないものを
  :playable と申告しない。
- **art direction は owner の判断**（ADR-0075 / 0078 の系譜）。declared な何かを
  失うトレードオフは、測って選択肢を提示するところまでが engineering。

## 着地

```bash
git -C $X/network-awai/network-isekai push origin agent/game-dev-<date>
gh api repos/network-awai/network-isekai/merges -f base=main \
  -f head=agent/game-dev-<date> -f commit_message="..."
git -C orgs/network-awai/network-isekai worktree remove $X/network-awai/network-isekai
git -C orgs/network-awai/network-isekai branch -D agent/game-dev-<date>
```

rebase / force-push はしない。merge が 409 なら再試行、conflict なら
origin/main から切り直して載せ直す。

**着地したら west pin を前進させる（candidate の種類を問わず標準の 1 歩）。**
子リポの main を直しても、pin が手前のままだと superproject と fleet gate は
古い tip を見続ける（CLAUDE.md「直したら pin も前進させる」）:

```bash
cd "$COM_JUNKAWASAKI_ROOT"    # superproject root から
nbb scripts/west-pin-put.cljs network-isekai <merged-sha>
nbb scripts/verify-west-pins.cljs
```

worktree と branch を消すまでが完了条件。

着地したら 1 行で報告する: どのゲームの / 何を / どの増分だけ進めたか /
証拠（merge commit SHA）。**成否は次周の tick が測る** — 自分で「登録漏れは
消えた」と書かず、`nbb scripts/isekai-game-dev-tick.cljs` をもう一度回して
その候補が消えたことを**見る**。消えていなければ直っていない。

## 絶対にやらないこと

- QA / 修正作業（それは `/isekai-qa-loop`。作る bot と直す bot を混ぜない）
- 1 反復で 2 増分
- 共有 checkout への直接 commit、rebase、force-push
- 測っていない品質・動作の申告
