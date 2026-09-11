# tasuke-app — 助 の被害後対応を、実際に開ける 1 枚のページに

アカウント乗っ取り・不正送金などサイバー被害の**初動・窓口・書面**を、被害者本人が
その場で組み立てられる single-page app。判定・窓口・手順・書面のすべては
`kotoba/triage_core.kotoba`（guest authority）が決め、コンパイル済みの KIR を
**ブラウザがそのまま実行**する。

```
kotoba/triage_core.kotoba          ← 決定はここにしか無い
        │ kbb -M:gen           （amu。compiler は :gen / :test にしか居ない）
        ▼
resources/tasuke_app/oracle/triage-core.kir.edn   ← 出荷される成果物
        │
        ├── JVM   : io/resource で読む         → kbb -M:test（34 assertion）
        └── cljs  : kir-embed が build 時に埋込 → node test（21 assertion）+ ブラウザ
```

## なぜ「.kotoba を cljs で実行する」のか

jp-go-dds の `kotoba_oracle.clj` は、**JVM でだけ** guest に委譲し、ClojureScript
では「規則の 2 つ目の実装」が残ることを自分で明記している（170 repo に
`kotoba.kir` を classpath へ足させる対価が高いため）。この app は consumer が
1 つなので、その seam を渡る。したがってここには**規則の cljs 実装が 1 行も無い**。

その代わり、jp-go-dds が名指しした 2 つの非対称を設計で避けている:

| 非対称 | この core の避け方 |
|---|---|
| record 内の `:i64` は cljs で `js/BigInt` を要求する | record は 1 つだけ（被害届の 6 値が ABI の 5 引数上限を超えるため）で、**全フィールドが `:string`**。`:i64` は必ず top-level 引数（`kir/execute` が coerce する） |
| `utf8-substring!` の `integer?` guard が BigInt で壊れる | この core は `yen` で **整数を文字列に整形する** —— つまり露出する形そのもの。この pin 対（amu 1e21a1f / kir 6d08e3c）では通ることを cljs gate で**実測**した（jp-go-dds は「露出していない」としか書けていなかった） |

多値は `:document` ではなく改行区切りの文字列で返し、host が split する。
**両方の runtime で通ることが分かっている形を選ぶ**（豊かだが片方でしか走らせて
いない形より優先する）。

## 使う

```bash
npm install
kbb -M:gen        # .kotoba → KIR（決定を変えたら必ず）
kbb -M:gen-page   # public/index.html（1 文書）
amu compile --target wasm32-browser app
kbb --backend sci scripts/verify_browser.cljk   # 実ブラウザで 10 項目
```

## 検査（4 つ。どれも「飛ばした」と「合格した」が区別できる）

| 検査 | 何を言うか |
|---|---|
| `kbb -M:test` | 出荷 artifact が真理値表に答える + **artifact が今の `.kotoba` と一致する**（stale artifact は永久に緑になるので、この 2 つ目が要る） |
| `npm run test:cljs` | **同じ artifact**を ClojureScript で実行して同じ答えが返る |
| `kbb --backend sci scripts/verify_browser.cljk` | guest の答えが画面に出る / view を跨いでも document を読み込まない / 跨いでも state が残る / chip が本当に塗られている |
| 負のコントロール | 未 export の関数呼び出しが `"function is not exported"` **その理由で**拒否される（理由の literal を pin する） |

書面は 7 種とも guest が本文を持つ（被害届 / 被害状況報告書 / 証拠目録 / 被害額算定書 /
銀行組戻し依頼 / プラットフォーム凍結復旧依頼 / アカウント復旧手順書）。

いずれも壊して赤くなることを確認済み（2026-08-29）。

## この画面がしないこと

- **代理ログインをしない / 代理提出をしない。** guest に、本人以外を author に
  できる分岐が無い（G2/G3）。
- **有料の紹介をしない**（G5）。`support-cost-jpy` は引数を取らない（G1）。
- **入力を保存も送信もしない。** localStorage も使わない —— 乗っ取り被害では
  その端末が既に危ういことが有り得るので、タブを閉じたら消えるのが正しい。
  証拠はブラウザ内で sha256 を取り、**中身は保持しない**（G6）。

## まだ無いもの（正直に）

- **live な公開先**。この session に Cloudflare の credential が無く deploy して
  いない。`public/` は静的ファイルなので、置けばそのまま動く。
- **本来の置き場所への着地**。この app は `cloud-itonami/tasuke` に属する。この
  session は `com-junkawasaki/root` にしか push 権が無いため、ここに staging
  している（ADR-2608290100 の Consequences に移送手順）。

## 見つけた上流の欠陥（tasuke 本体へ）

1. `methods/triage.cljc` の乗っ取りキーワードは `"乗っ取り"` で、**「乗っ取られた」に
   当たらない**。この app の起点になった報告文がまさにその語形で、原文の分類器は
   sns-fraud の既定に落ちる。guest では語幹 `"乗っ取"` に直した（意図的な乖離、
   gate に明記）。
2. `unauthorized-transfer` で被害額が未確定（0）だと severity が最低の `info` に
   なる。同じ kind の `deadlines` は「認知後ただちに銀行へ」を含む —— 判定と
   締切が矛盾している。**直さず継承**し、gate に明記した（黙って直すと actor と
   食い違う）。画面は severity に関わらず時計を出す。
3. `methods/{triage,evidence,report_gen,packet}.cljc` は拡張子が `.cljc` だが
   **cljs でコンパイルできない**（`Long/parseLong` / `catch Exception` が reader
   conditional の外、`evidence/sha256-hex` は `:cljs` で throw、`packet` は private
   var を `deref`）。「`.cljc` だからブラウザで動く」は成り立たない。
