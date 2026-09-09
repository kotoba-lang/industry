# repo-bots — repo 1 本につき常駐 bot 1 体

west に登録された repo それぞれに、自分の現在地を測る常駐 bot を 1 体置く。
名簿は生成物 `manifest/repo-bots.edn`、稼働は `~/.itonami/repo-bots/state.edn`。

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
- 自分の state 行と履歴（`~/.itonami/repo-bots/observations.ledger.edn`）

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

書くのは `~/.itonami/repo-bots/` の下だけ。4,000 本の checkout は**読むだけ**で、git の
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

## murakumo を繋ぐ側（提案は模型・判定は gate）

```bash
nbb scripts/repo-bots/propose.cljs --batch 8      # 波で草稿を作る（常駐用）
nbb scripts/repo-bots/propose.cljs --bot <id>     # 1 体だけ
nbb scripts/repo-bots/propose.cljs --dry-run      # 模型を呼ばず証拠の量だけ見る
nbb scripts/repo-bots/propose.cljs --bot <id> --check-draft <file>   # gate だけ通す
```

    tick（決定論） → 証拠（決定論） → 模型が起草 → gate（決定論） → 草稿

**測定に模型は入らない。** どの repo のどの床が割れているかは tick が決めてあり、
模型がするのはその 1 件に対する文章の起草だけ。受理を決めるのは gate:

1. 床を実際に越えるか（README なら 200 byte 以上）
2. 挙げたパスが**実在するか** —— 1 つでも実在しなければ却下
3. 挙げた URL のホストが証拠に在るか（`github.com/<org>/<name>` だけは許す）
4. 雛形の痕跡（TODO / FIXME / placeholder / lorem）が無いか
5. repo 名を名乗っているか

却下された草稿も `.rejected.md` として残す。**何を却下したか読めないと、gate が
効いているのか単に呼べていないのか区別できない。**

**5 つとも、その理由だけで落ちることを実測した**（2026-08-27。受理された実物の
草稿に対し、実在しないパスを 1 つ混ぜる / 証拠に無いホストの URL を足す / TODO を
足す / 120 byte に切る、をそれぞれ当てて、**それぞれ自分の理由で**却下された）。

### 模型に訊く前に止める 2 つの床

- **証拠 400 byte 未満なら呼ばない。** 空の repo に「何が足りないか」を訊けば、
  模型は流暢に捏造する。gate で落とせば済む話ではない —— 落ちると分かっている
  呼び出しに fleet の時間を使い、receipt に却下が積み上がって本物の却下が埋もれる。
  実測: 最初の波 8 件のうち **6 件がこれ**（`cloud-itonami/app-*` の空 scaffold）。
- **未着地の草稿が 40 本を超えたら呼ばない**（`REPO_BOT_PENDING_CAP`）。書く側は
  1 時間に 8 本、着地は 1 反復 1 件なので、上限が無ければ数百本の未読の草稿が
  積み上がり「提案は出ている」という見た目だけが残る。上限に当たったとき
  **詰まっているのは書く側ではない。**

### モデル名を焼かない

alias `murakumo-main` だけを送る（ADR-2607173100）。receipt には呼んだ時点の
`alias-for` を記録するが、**次も同じ実体だとは仮定しない**。endpoint は
`https://api.murakumo.cloud`（`MURAKUMO_API_BASE` で上書き可）。
実測 2026-08-27: 認証不要、1 提案あたり 1,400〜1,900 token、20〜30 秒。

## 動かし続ける

3 つの LaunchAgent を `~/Library/LaunchAgents/` に置いて `launchctl load`:

| plist | 間隔 | 何をするか |
|---|---|---|
| `cloud.itonami.bot.repo-bots-tick` | 1h | 200 体を測る。全 4,186 体の一周におよそ 21 時間 |
| `cloud.itonami.bot.repo-bot-propose` | 1h | 8 本まで草稿を作る（滞留 40 本で自動停止） |
| `cloud.itonami.bot.repo-bot-drain` | 4h | 草稿を 1 件だけ着地させる（候補が無ければモデルを起こさない） |

止めるときは `launchctl unload`。測る側だけ残して直す側を止める、もできる。
