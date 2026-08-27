# repo-bots — repo 1 本につき常駐 bot 1 体

west に登録された repo それぞれに、自分の現在地を測る常駐 bot を 1 体置く。
名簿は生成物 `manifest/repo-bots.edn`、稼働は `~/.gftd/repo-bots/state.edn`。

```bash
nbb scripts/repo-bots/gen-registry.cljs          # 名簿を起こす（生成物）
nbb scripts/repo-bots/gen-registry.cljs --check  # 生成器と一致するか
nbb scripts/repo-bots/tick.cljs                  # 波 1 つ（既定 200 体）
nbb scripts/repo-bots/tick.cljs --report         # 測らず、いまの現在地
nbb scripts/repo-bots/tick.cljs --only kotoba-lang/amu
```

## なぜ「名簿」と「稼働」を別の場所に置くか

`manifest/observatories.edn` の冒頭が、この種の仕事の失敗をそのまま記録している ——
22 actor を実際に走らせたら 8 本が走らず、しかも**壊れていたのではなく、誰も一度も
走らせていなかった**。README も MATURITY.md も、それらが動くと書いてあった。

だから名簿には charter しか書かない。**名簿に載っていることを「動いている」と
読ませない。** 動いたかどうかは tick の state だけが答える。4,000 体規模で最初に
壊れるのはここなので、構造で分けてある。

## bot の個体性はどこにあるか

実行機構は `tick.cljs` の 1 本を共有する。cloud-itonami の ADR 0047（hyakka topic
residents）が「テーマごとに Durable Object の *インスタンス* を分け、pipeline は
1 本を共有する」と決めたのと同じ切り方で、個体性は

- 安定した id（`<org>/<name>`）
- 自分の charter（class 由来の床）
- 自分の state 行と履歴（`~/.gftd/repo-bots/observations.ledger.edn`）

にある。2 本目の pipeline を生やさない。

## 床（floor）

| 床 | 割れている状態 |
|---|---|
| `:checkout` | checkout が無い / git repo でない → **:unmeasured**（違反ではない） |
| `:pinned` | local HEAD が west pin と違う |
| `:landed` | 未 commit の変更がある / upstream より前に出た commit がある |
| `:readme` | README.md が無い、または 200 byte 未満 |
| `:test-signal` | コードが在るのに test dir も test alias も無い（コードが無ければ `:n/a`） |

## 4 値であることが設計の中心

床は `:ok` / `:broken` / `:n/a` / `:unmeasured` を返す。ADR-2608136000 が
「測れなかった検査が、測って問題が無かった検査と同じ値を返す」を 1 日で 14 箇所
見つけた形そのものなので、**測れなかったことを ok に畳まない**:

- checkout が無い bot は「違反 0 件」ではなく `:unmeasured`
- upstream を解決できない branch の `:landed` も `:unmeasured`（ok ではない）
- 上流 default branch との遅れ（pin 鮮度）は network が要るので**ここでは測らない**。
  測っていないものを、測ったように見せない

`:n/a` も「測った上での適用外」であって、宣言で検査対象から外したものではない
（`:test-signal` はコードマーカーの有無を実際に見てから n/a になる）。

## 出力の class

`scripts/orgs-detector-tick.cljs` と同じ 4 分類。理由も同じで、**標準的に赤いものは
沈黙と区別が付かない**（ADR-2608124800 が 867 / 282 / 269 / 268 連続失敗を数えた）。

```
NEW        この波で初めて割れた床。名指しで出す
RESOLVED   前は割れていて、いま塞がった床。名指しで 1 度だけ
STANDING   それ以外。件数と最古の齢だけ。列挙しない
UNMEASURED 測れなかったもの。件数と理由の内訳
```

その bot の初回は BASELINE として数える。初日は全部 new なので、叫べば同じ嘘を
反対向きにやることになる。

## 成長はどこに出るか

`GROWTH  7d cleared N / broke M` が、床が塞がった数と割れた数の差。ledger
（append-only、1 行 1 EDN map）が全イベントを持つので、後から repo 別・床別に
畳める。**測定は event 列なので append-only**（CLAUDE.md の「文書は現在値、測定は
追記」の後者）。

## 共有 checkout を書かない

書くのは `~/.gftd/repo-bots/` の下だけ。4,000 本の checkout は**読むだけ**で、git の
書き込みコマンドは 1 つも呼ばない。並行 agent が走っているマシンなので、ここを
緩めるとこの tick が他人の working tree を壊す側になる。

## exit code

```
0  波を測った（findings が在っても 0。これは gate ではなく監視）
2  測れなかった（名簿が無い / 波が空 / lock 競合 / SCANNED 0）
```

0 でも 1 でもない値を「答えられなかった」に割り当ててある。

## 床が実際に落ちることを確かめてある

`gate は「落ちること」を確かめてから landed とする。落ちない gate は劇場`
（CLAUDE.md）。5 つの床それぞれについて、**その床だけを壊した fixture repo**で
赤くなり、無改変で緑になることを実測した（2026-08-27）。`:test-signal` は最初の
fixture 作りが失敗していて（空の `test/` は git が追跡しないので `git rm` が
no-op になった）**壊せていないのに緑を「噛まなかった」と読みかけた** —— 壊した
ものと報告されたものが一致することを確かめること。

## 動かし続ける

`scripts/com.gftd.repo-bots-tick.plist` を `~/Library/LaunchAgents/` に置いて
`launchctl load`。既定は 1 時間ごとに 200 体なので、全 4,186 体を一周するのに
おおよそ 21 時間かかる。
