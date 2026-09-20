# jev で kotoba の coding / refactor はできるか — 2026-09-19〜20 の実測まとめ

正本は ADR `adr-2609191200`（前半）と `adr-2609201400`（後半・closing）、数字の出所は
`kotoba-lang/typed-decisions` の README 第5〜9反復と `reports/`。この md は人が一気に読むための要約。

## 問い

TypeSafe の Jev（System One model: 自然文を読むが、返すのは `choice` / `score` / `noul` の型付き決定だけで、
自由文もコードも生成しない）だけで、kotoba の unison 的性質を使った coding / refactor / tool call ができるか。

## 答え（3 層）

| 層 | できるか | 根拠 |
|---|---|---|
| **既存の名前への繋ぎ替え**（keyword の rename、名前文字列、依存の rewire `clojure.string`→`kotoba.lang.text`） | **できる。** 候補源が repo なら到達 64%、keyword 0.68〜0.78、名前文字列 0.69、較正ゲート 0.8 で精度 0.86〜0.92 / 適用率 0.27〜0.34。rename refactor 1 本（`kototama-de177068`）を choice だけで kbb PASS | 第7・9反復 |
| **論理の選択**（`second`→`first`、`inc`→`quot`、局所変数の命名） | **単独では無理。** top-1 0〜0.3、rank 2 が多く backtracking の入口にはなる | 第6・8反復 |
| **新規の名前・新規の式** | **choice の外。** 変更 token の 86〜93%、穴の 36% がこれ | 第6・8・9反復 |

「jev は生成しないから refactor は無理」は per-call の話で、系としては誤り。生成は反復された choice であり、
jev に欠けていたのは駆動ループ・候補列挙・verifier で、それは外から与えられる。kotoba が有利なのは
名前が identity でない（content-addressed）、capability import が有限、型検査器と test が verifier として在る
（掛け算ではなく探索になる）の 3 点。

## 何を作ったか

- `com-junkawasaki/root` `scripts/jev-decide.cljk`（PR #3231）: state + questions → Jev（OpenRouter
  `POST /api/alpha/decisions`、`typesafe/jev-1.13` に pin —— `jev-latest` alias はこの endpoint で 400）。
  `--record` で typed-decisions 互換の JSONL（gold は null、答えは `prediction`）。exit 0/1/2 の 3 値。
- `kotoba-lang/typed-decisions`（PR #1〜#6）:
  - `jev_holes.py` — 穴の抽出、jev choice、kbb verifier、backtracking、pool / role の arm
  - `hole_data.py` — git 履歴から gold 付き `code-holes` family を掘る（`changed_tokens` 付き、repo pool は blob cache）
  - `hole_eval.py` — held-out で jev を採点、`--reshape` で候補整形、閾値ゲートの表
  - README 第5〜9反復、`reports/` 10 本
- Hugging Face（`com-kotobalabs`、Apache-2.0）:
  - `typed-decisions-repo-governance` — ungoaled、n=1 の種（compliance finding の実データ、owner 承認済み）
  - `typed-decisions-code-holes` — gold 付き。config `default` 1,232 件（64 public repo）、`repo-pool` 1,160 件
    （18 repo）。private の `app-kotoba-cloud` は GitHub API で visibility を実測して除外

## 途中で直した自分の欠陥（結論を変えたもの）

1. 置換前の token を option に残していた → 誤答が「変えない」を conf 0.9 で選ぶ現状維持バイアス（第6）
2. 文字列 literal を丸ごと除外していた → 名前文字列の穴を埋めず rename が落ちていた（第7）
3. 大きな書き換えの中の 1:1 alignment を穴と数えていた → held-out 200 穴中 167、symbol 0.05（第8）。
   `changed_tokens` を持たせて isolated だけ評価
4. 候補整形を無条件に掛けて実 refactor の gold を 4 つ消した → 穴の形で条件付け（第9）
5. jev の呼び出し間の揺れは ±1.5 pt。これを下回る差は結論にしない

## 数字（isolated ≤100 changed tokens、chance ≈ 1/100〜1/250）

| | n | top-1 | ゲート 0.8 正/誤/escalate |
|---|---|---|---|
| file pool、80 repo（第8） | 344 | 0.558（kw 0.683 / str 0.50 / sym 0.397） | 108 / 9 / 227 |
| 同、候補整形（第9） | 344 | 0.567（sym 0.412） | 104 / 12 / 228 |
| repo pool、20 repo（第9） | 355 | 0.48（kw 0.77 / str 0.69 / sym 0.21） | 83 / 14 / 258 |
| うち repo でしか届かない穴 | 208 | 0.34（kw 0.73 / str 0.69 / sym 0.19） | |

到達: file pool 22% → repo pool 64%、なお 36% は sha 時点の repo に無い名前。

## 次の一手（優先順）

1. `code-holes` で student（DeBERTa-v3-large）を訓練し in-domain / OOD を測る —
   `modal run modal_app.py::encoder --data-dir data-holes`（$0.2 程度）。jev の 0.56 を超えるか。
2. 255 の中を役割・型で刈る、または jev の top-k を型検査・test に渡す二段構成（symbol の 0.2〜0.4 を上げる唯一の経路）
3. `.kotoba` の pair が溜まったら kotoba-sema で刈って同じ表を取り直す（今は pair が `.cljc` で不可）
4. `repo-governance` の gold 配線（owner / governor の裁定を書き戻す）
5. `typesafe/jev-latest` alias の再測定（直っていれば `jev-decide.cljk` の pin を外す）

## 再開するなら最初に打つコマンド

```
cd orgs/kotoba-lang/typed-decisions && git pull --ff-only
PYTHONPATH=src python3 -m typed_decisions.hole_eval --reshape --data data-holes/all-iso100.jsonl --limit 50 --out /tmp/smoke.json
```
（`data-holes/` が無ければ `python -m typed_decisions.hole_data --top ../../.. --out data-holes` で 25 分）
