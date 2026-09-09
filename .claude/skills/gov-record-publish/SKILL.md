---
name: gov-record-publish
description: 政府・自治体の活動（調達・入札談合・補助金・随意契約など）を、当事者自身の一次資料だけで 1 件、wiki.kotobase.net の hyakka（百科）に記録して着地させる。1 反復 = 1 事案。ローカル/クラウド loop（com.gftd.gov-record-publish）が定期的にこれを呼ぶが、手で `/gov-record-publish` と打ってもよい。「政府活動を記録」「もう1件調達の記録」「入札談合を百科に」「gov record」で発火。
---

# 政府活動の記録を 1 件、hyakka に着地させる

**正本は `network-awai/app-hyakka` の `docs/adr/0006-public-procurement-corpus.md`
（superproject mirror: `90-docs/adr/2608270900-hyakka-public-procurement-corpus.edn`）。**
この skill はその手法を繰り返すための手順書で、**会話履歴を持たない fresh context
から読める**ように書いてある。前の反復が何をしたかは会話ではなく
`orgs/network-awai/app-hyakka` の git log と `knowledge/ledger/` から読む。

実例 2 件（御杖村の公衆トイレ、香川県の入札談合事件）が同じ corpus
`koukyou-chotatsu` に入っており、**この手順はその 2 件から抽出したもので、
仮説ではない。**

## この反復の仕事はちょうど 1 つ

> **主要な事実が「当事者自身が公表した一次資料」（PDF が望ましい）で
> 裏付けられる政府活動の事案を 1 件見つけ、評価して、hyakka に着地させる。**

1 反復 = 1 事案。複数の事案を一度にまとめない —— 1 件ずつ evidence 検証と
break-test を通すことで、着地の質を保つ。

## 何を探すか

「政府活動」は公共調達に限らない。候補の型（例、網羅ではない）:

- 公共工事・調達の高額契約・変更契約（御杖村の先例）
- 独占禁止法違反の入札談合・カルテル（公正取引委員会の排除措置命令・課徴金納付命令。
  香川県の先例）
- 補助金・助成金の不正受給・目的外使用（会計検査院の検査報告）
- 随意契約の乱発・天下り先への発注
- 情報公開請求への不開示・のり弁（開示決定通知書自体が一次資料になる）
- 地方議会での議決の形骸化（質疑なし・討論なしでの可決、委員会での「割愛」）

**探すときの絞り込み条件**（これを満たさない候補は着地させない）:

1. **当事者自身（発注者、または執行機関）が公表した一次資料が存在する。**
   報道だけの事案は不可 —— 報道は corpus policy 上 admit しない
   （ADR 0006 の「これは意図的に高くついた選択」を読む）。
2. **一次資料が PDF、または `pdftotext` 相当で決定的にテキスト抽出できる形式。**
   HTML のみで JS 描画される資料（例: 欧州委員会の press corner）は evidence
   照合ができないため見送る（chotatsu の `:coverage :missing` に前例あり）。
3. **測って確かめられる型と、言われているだけの型を区別できる。** 中抜き・癒着の
   ような疑惑は、それを裏付ける一次資料（行政処分・判決）が無い限り
   `:alleged` のまま —— claim を付けない。

## 手順

### 0. 現在地を測る

```bash
cd ~/github/com-junkawasaki
git fetch origin
nbb scripts/repo-search.cljs koukyou-chotatsu chotatsu
```

`orgs/network-awai/app-hyakka` を最新化する（west pin 経由、または直接 fetch）。
既存の corpus（`src/hyakka/corpus/chotatsu.cljc`、他に近い形があれば
`kaiyaku.cljc` も）を読み、この事案が**既存の corpus に自然に足せるか**、
**新しい corpus が要るか**を判断する。

- 御杖村の先例は procurement/contract の shape。
- 香川県の先例は enforcement-action の shape（kaiyaku から
  `enforcement-authority` / `case-status` / `legal-basis` を reuse）。
- 全く違う型（例: 補助金の不正受給は「受給者」「交付決定」「返還命令」という
  別の shape）なら、chotatsu を汚さず**新しい corpus namespace**を起こす
  （`hyakka.corpus.registry` への登録手順は `chotatsu.cljc` を写経すればよい）。

### 1. worktree を切る（共有 checkout を触らない）

**superproject の外**に切る。分岐元は `origin/main` を明示する。

```bash
cd /Users/junkawasaki/github/com-junkawasaki/orgs/network-awai/app-hyakka
git fetch -q origin
W=/tmp/hyakka-gov-$(date +%s)
git worktree add -b agent/<短い名前> "$W" origin/main
cd "$W"
npm install --no-audit --no-fund
ln -sfn /Users/junkawasaki/github/com-junkawasaki/orgs/kotoba-lang "$(dirname "$W")/kotoba-lang" 2>/dev/null || true
```

（`deps.edn` の `../../kotoba-lang/kotobase-client/src` を解決するため、worktree の
親ディレクトリに `kotoba-lang` への symlink を張る。superproject 本体の
`orgs/kotoba-lang` を指す。）

### 2. 一次資料を取得し、逐語 evidence を検証する

一次資料の URL を集める。JFTC のような一部のサイトは Akamai が UA なしの
`curl` を拒否する（Access Denied、reference id 付き）— **これは bot 拒否であって
資料が無いのではない。** `Mozilla/5.0 (hyakka-knowledge-ingest; …)`
のような身元を名乗る UA を付ければ通ることが多い（`scripts/seed_chotatsu.cljs`
の `user-agent` を流用する）。それでも拒否される場合、ブラウザ相当の UA を
一時的に使ってよいが、**恒久的な UA として書き込まない**。

各 PDF を `pdftotext -enc UTF-8 <f> -` で抽出し、改行を除去して正規化する
（日本語は単語間空白が無いので改行除去がそのまま文をつなぐ —— seed script が
自動でやる。手動確認する場合は python で `re.sub(r'[\n\f\r]','',text)`）。

**すべての evidence 候補文字列を、書く前に正規化テキストへの部分文字列一致で
確認する。** 一括チェックスクリプトの例:

```python
checks = [('key', 'evidence string'), ...]
for k, e in checks:
    print('OK' if e in normalized_text[k] else 'MISS', k, e[:40])
```

MISS が出たら evidence を実際のテキストに合わせて直す（PDF の表組みは列の
読み順が期待と違うことがある —— 実測: JFTC の命令書は表の見出しと数値が
本文と違う順で抽出された）。

### 3. corpus の vocabulary / pattern catalogue を拡張する

- 新しいプロパティが要れば `src/hyakka/corpus/<corpus>.cljc` の `properties` に足す。
  **既存 corpus（kaiyaku・tetsuzuki）に同じ意味のプロパティが既にあれば
  redeclare せず reuse する** —— `reused-property-ids` セットに書き、
  対応するテスト（`reused-properties-really-are-declared-elsewhere`）を足す。
- 新しい pattern（型）が実測されるなら、`:standing` を `:alleged`/`:catalogued` から
  `:measured` へ**この反復で明示的に**昇格する。昇格には必ず: (a) 実際の claim
  (b) pattern の `:description` の書き換え（「収録例は無い」という文言が
  嘘にならないように）(c) 「この事案にのみ根拠がある。他の収録事案には
  遡って適用されない」と書くテスト。standing は corpus 全体の主張であって、
  個々の procurement の主張ではないことを必ず確認する（`world/procurement/X`
  に claim が無ければ、その procurement には型が付いていない —— retroactivity
  guard のテストを書く）。
- item kind が新しければ `<kind>-item` ビルダー関数を足す（既存パターンを写経）。

### 4. seed EDN を書く

`knowledge/seeds/<corpus>.edn` に `:sources` / `:items` / `:claims` を足す
（新規 corpus なら新しいファイル）。

- `:sources` は当事者自身の URL のみ。`:source-class` は
  `:authoritative-registry`（当事者の登録簿・処分の公表資料の原本）か
  `:first-party-site`（同じ当事者の広報）。
- 1 claim = 1 evidence。evidence は正規化テキストへの逐語部分文字列。
- item-valued なプロパティ（`:datatype "item"`）には `:value-item` を、
  literal なプロパティには `:value` を書く。取り違えると
  `item-valued-properties-are-written-as-items` が落ちる。

### 5. Dry-run → 実行

```bash
HYAKKA_ARCHIVE_DIR=~/.itonami/hyakka-archive nbb --classpath src scripts/seed_<corpus>.cljs --dry-run
HYAKKA_ARCHIVE_DIR=~/.itonami/hyakka-archive nbb --classpath src scripts/seed_<corpus>.cljs
```

dry-run が `admitted` を返してから実行する。evidence が 1 件でも一致しなければ
**何も書かれない**（`EVIDENCE ADMISSION FAILED`）—— それでよい、直してから
再実行する。

### 6. catalogue を再生成し、テストを通す

```bash
npm run catalog
node /Users/junkawasaki/github/com-junkawasaki/scripts/resource-guard.mjs run build -- npm test
```

新しいテストを最低 2 種類足す:
- 構造テスト（既存の `*-test.cljs` を写経: 未宣言プロパティ・dangling
  value-item・evidence 必須・item ↔ literal 取り違え）
- **この反復固有の break-test**: 昇格させた pattern を別の procurement に
  誤って付けたら fail するテスト（retroactivity guard）、または
  この事案特有の不変条件

**壊し方を実際に試す。** 1 箇所を意図的に壊して exit 1 / test fail になることを
見て、戻して green になることを見る（「落ちない gate は劇場」、CLAUDE.md）。

### 7. 着地

```bash
git add -A && git commit -m "<corpus>: <一言で何を足したか>"
git push origin agent/<短い名前>
gh api repos/network-awai/app-hyakka/merges -f base=main -f head=agent/<短い名前> \
  -f commit_message="<同上>"
```

### 8. デプロイと検証

```bash
git fetch -q origin && git merge --ff-only origin/main -q
node /Users/junkawasaki/github/com-junkawasaki/scripts/resource-guard.mjs run build -- npm run build
node /Users/junkawasaki/github/com-junkawasaki/scripts/resource-guard.mjs run deploy -- npx wrangler deploy --config worker/wrangler.jsonc
curl -sS "https://wiki.kotobase.net/health?catalog=<catalog-idの先頭>" # catalog-id が一致することを確認
curl -sS "https://wiki.kotobase.net/item/<新しい item の id>"          # 実際にレンダリングされることを確認
```

`?catalog=` パラメータでの health check 一致が「デプロイした版が live」の証拠。
新しい item ページを実際に fetch して、期待した claim が本文に現れることを見る
（レンダリングされない claim は無いのと同じ —— 実例: item に `:item/description`
が render されていなかった障害を 2026-08-27 に発見・修正済み）。

### 9. R2 Data Catalog へ同期（`cloud-itonami-datalake`）

```bash
nbb --classpath src scripts/datalake_sync.cljs --replace
```

詳細は `docs/r2-data-catalog.md`。`--replace` は明示的な全表再構築で、
hyakka の ledger は継続的に伸びるためこれが既定の再同期経路。

### 10. west pin を前進させる

```bash
cd /Users/junkawasaki/github/com-junkawasaki
git fetch -q origin && git status -sb   # main が遅れていないか
nbb scripts/west-pin-put.cljs app-hyakka HEAD --message "pin: advance app-hyakka to <一言>"
```

### 11. 後片付け

```bash
cd /Users/junkawasaki/github/com-junkawasaki
git worktree remove "$W"
gh api -X DELETE repos/network-awai/app-hyakka/git/refs/heads/agent/<短い名前> 2>/dev/null || true
```

## やらないこと

- **報道・SNS・まとめブログを一次資料として admit しない。** corpus policy が
  拒否する（`:press-original-reporting` / `:press-aggregation` /
  `:user-generated` はどの procurement corpus でも forbidden）。報じられた
  数字が一次資料に無ければ、無いと明示する（`:coverage :missing`）—— 補って
  埋めない。
- **中抜き・癒着のような疑惑を、それを裏付ける一次資料無しに claim として
  書かない。** カタログに `:alleged` として型を持たせるのはよいが、claim は
  付けない。
- **複数の事案を 1 反復にまとめない。** 1 反復 1 事案。
- **evidence を要約・言い換えで書かない。** 正規化テキストへの逐語部分文字列
  でなければ seed script が拒否する（意図した挙動）。
- **判断がつかなければ着地させない。** 一次資料が見つからない、PDF 化できない、
  evidence が確認できない場合は、何が確認できなかったかを報告して終わる。
  次の周が別の候補を探す。
