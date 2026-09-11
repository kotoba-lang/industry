# 本来の家（cloud-itonami/tasuke）への移送 — 実行して検証済みの手順

この app は `cloud-itonami/tasuke` に属する。ここに置いてあるのは、この session が
git proxy の cross-tier 制限で `com-junkawasaki/root` にしか push できないから
（ADR-2608290300 D5）。**移送は机上ではなく、実際に tasuke の clone で最後まで
実行し、3 本の gate を通してある**（2026-08-29）。以下はその記録であり、次に
やる者が推測しなくてよいようにするためのもの。

## 適用する

`cloud-itonami/tasuke` を初期 source にした session（または push 権のある手元）で:

```bash
git clone https://github.com/cloud-itonami/tasuke && cd tasuke
git fetch <この bundle のパス> feat/first-response-app:feat/first-response-app
git checkout feat/first-response-app
npm install && kbb -M:test          # 21 tests / 2216 assertions
```

bundle は base として `6c7ac667`（移送時の tasuke main）を要求する。main が進んで
いたら、bundle を当てずに下の対応表どおり手で置き直す方が安全（generated file が
2 つあるので textual merge に向かない）。

## 何がどこへ行くか

| ここ | tasuke |
|---|---|
| `kotoba/triage_core.kotoba` | 同じ |
| `resources/tasuke_app/oracle/triage-core.kir.edn` | `resources/tasuke/app/oracle/…` |
| `src/tasuke_app/*` | `src/tasuke/app/*`（ns `tasuke-app.*` → `tasuke.app.*`） |
| `gen/tasuke_app/*` | `gen/tasuke/app/*` |
| `test/tasuke_app/*` | `test/tasuke/app/*` |
| `public/index.html` + `public/js/` | **`app/index.html` + `app/js/`（旧 app を置換）** |
| `deps.edn` | tasuke の deps.edn に統合（`:paths ["src" "resources"]`） |
| `package.json` / `shadow-cljs.edn` / `scripts/` / `.gitignore` | repo 直下 |

⚠ **一括置換 `tasuke-app.` → `tasuke.app.` は文字列 `tasuke-app.js` にも当たる。**
実測 2026-08-29: それで `<script src="js/tasuke.app.js">` が生成され、ページは
200 で返るのに **JS が 404 で mount しない**。ブラウザ gate だけが捕まえた
（コンパイルもテストも緑のまま）。ns だけを置換すること。

## tasuke 側で必要だった 4 つの変更（どれも移送の一部）

1. **`app/index.html` の置換。** 旧版は規則と 7 種の書面を JavaScript で再実装した
   213 行だった。だから**先に 5 種の書面を guest へ移した** —— そうしないと移送が
   機能後退になる。移送後、書面は 7 種とも guest が本文を持つ。
2. **`test_app_parity.cljc` → `app_parity_test.clj`。** 旧 drift-lock は HTML から
   `const SCAM_KINDS = [...]` を grep して ontology と比べていた。新しい面には
   その配列が無い（規則が 1 実装しかないので）。後継は **ontology が宣言する全
   kind に対して guest が分類・窓口・手順・書面を答えられるか**を検査し、加えて
   出荷 bytes（page + bundle）の no-network / no-storage を測る。
   ns を `*-test` で終わる形に改名した —— cognitect の test-runner は既定で
   `test-*` 始まりの ns を**拾わない**（下記の finding 4）。
3. **`repository-contracts.edn` と `repository_contract_test.clj` の例外追加。**
   `external-json-is-wire-only` は「`wire/` 以外の .json を禁止」で、npm の
   `package.json` が直下にしか置けないため衝突する。build manifest は交換する
   表現ではないので、名指しの例外として追記した。
4. **`run_tests.clj` から `tasuke.methods.test-app-parity` を除去。** 後継は
   出荷 KIR を実行するので JVM classpath（`kotoba.kir`）が要り、bb では動かない。
   bb 自体 ADR-2607173000 で退役済み。

## 移送後に実測した値（tasuke の tree で）

| gate | 結果 |
|---|---|
| `kbb -M:test` | **21 tests / 2216 assertions** 緑（tasuke の repository contract test を含む） |
| `amu compile --target wasm32-browser oracle-test` + node | **7 tests / 34 assertions** 緑 |
| `kbb --backend sci scripts/verify_browser.cljk` | **12 項目**緑（実 Chromium、書面 6 種のレンダリングを含む） |

`kbb -M:gen` が tasuke の tree で出す KIR は、ここで出すものと **byte 一致**
（35,204 bytes）。決定は移送で変わっていない。

## finding 4 — tasuke の JVM 側では domain suite が 1 つも走っていない

`kbb run_tests.cljk` が列挙する 11 suite は `tasuke.methods.test-triage` のように
**`test-` で始まり `-test` で終わらない**。cognitect の test-runner は既定で
`#".*-test$"` しか拾わないので、`kbb -M:test` はそれらを**1 つも実行しない**
のに緑を返す。bb は退役済みなので、**あの suite は今どの経路でも走っていない**
可能性が高い（この container に bb が無く、そこは未測定）。
直し方は `-r` を渡すか ns を改名するかだが、それは移送の範囲外なので触っていない。
