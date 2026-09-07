---
name: kotoba-clj-to-kotoba
description: clj / cljc から .kotoba へ、kotoba/app の vertical slice を 1 本移す。判断核は named backend が値を通せないときの fallback。『clj から kotoba』『cljk』『decision core』『todo-app』『kotoba compile』で発火。
---

# clj → .kotoba（vertical app slice）

**正本は ADR-2608261100**（移行単位）。言語は ADR-2607201300 / ADR-2607279200。
分類は ADR-2608650000 と `lang/surface-status.edn` の `:disposition`。
文字列禁止の否定は ADR-2608261000。この skill は 1 slice の手順書。

判断核は既定ではない。参照は amu の `examples/todo-app.kotoba`
（`init` / `view` / `step`。文字列、`:document`、`cond`）。

## 混ぜない

| 層 | 例 | 扱い |
|---|---|---|
| 恒久の安全 | `throw`、ホスト interop、ソケット | 広げない |
| 意味の単純化 | bool は数ではない | 広げない |
| 部分実装 / 未達 | native の word 型、正規表現演算 | その backend にだけ fallback。言語から削らない |
| **guest / host** | 線の CRLF 送信、DOM 破壊、credential | 機構は host。product semantics はゲスト |

**文字列は禁止されていない。** 『判断だけ』を『文字列を持たない』と読まない。
『1 スライス = 1 判断表』にしない。

## 手順

対象は west 管理なら **origin/main から worktree**（共有 `orgs/` を直接編集しない）。

1. **4 分類する**（ADR-2607279200 決定 5）。portable pure / portable effectful
   app / host mechanism / operational script。書けない理由は `:disposition`。
2. **product semantics を Clojure-shaped の `.kotoba` に書く。** map / 文字列 /
   record / document / `cond`。契約は state + event → next-state + inert
   effects。capability ID は通常書かない。`.cljk` は `:clj-kotoba`
   （JVM target ではない）。
3. **機構は host に残す。** ソケット、credential、DOM 破壊、SDK。
   コマンド線・応答の意味はゲスト。`.cljc` に同じ builder があるのは
   oracle（parity / require-graph）であって、そこに意味を置くためではない。
   『コマンド文字列は `.cljc`』は不適切。`.kotoba` を require しない。
4. **named backend が値を admit できないときだけ** 決定核へ畳む。ヘッダに
   欠落と撤去条件を書く。wasm / web に対して最初から潰さない。
   `:max-parameters 5` は record / document に畳む理由であって、プログラムを
   述語群へ分解する理由ではない。
5. **公開 compile は CLI。起動形は実測 2026-08-26 でこれ**（ADR-2608262000）。

```
kotoba -M compile /ABS/path/app.kotoba --target wasm32-browser --output app.wasm
kotoba -M compile /ABS/path/app.kotoba --target js-browser    --output app.mjs
```

   4 つとも間違えると別々の顔で落ちる: `-M` が無いと
   `compiler commands require the -M execution boundary`（exit 2）、
   target は `wasm` / `web` ではなく `wasm32-browser` / `js-browser`、
   フラグは `-o` ではなく `--output`、そして **相対パスは
   `:decode` / `input could not be read`** になる。
   `orgs/kotoba-lang/amu/bin/kotoba` が動く実体。

6. **成果物を実行する。ビルドは受け入れではない。** target が受理しても
   **誤った答えを返す**ことがある —— 拒否より悪い（拒否は設計判断を 1 つ生むが、
   誤答は何も生まない）。実測 2026-09-06、kotoba-lang/amu#835:
   `(= :passed :passed)` は `aarch64-macos` で **常に false**、同じビルドで
   i64 の `=` は正しい。全分岐が keyword で回るモジュールは全部が誤った枝へ行き、
   **self-check が失敗の「個数」を返していたこと**だけが気づけた理由だった
   （boolean だと 1 件の退行と壊れたビルドを区別できない）。
   だから `main` を置き、`amu extract-native --symbol main` + `tools/kexe_loader.c`
   で **native を走らせ**、wasm は `runtime/browser-host.mjs` で **走らせる**。
   `:ok true` は「ビルドできた」であって「正しい」ではない。

6b. **backend の欠陥は block であって作り直しではない。** 回避のために表現を
   変えない（keyword を i64 にする等）—— Q9 は未対応 target を fallback ではなく
   `:blocked` と定め、ドメインを backend の欠陥に合わせて整形するのは欠陥を隠す。
   **撤去条件は機械化する**: 「その欠陥がまだ再現すること」を assert する probe を
   acceptance に置く。上流が直した日に probe が**赤くなり**、unblock を促す。
   誰かが覚えている必要が無くなる。

7. **parity。** 既存関数と突き合わせる。CLI は binary が無ければ skip し、
   skip と pass を同じ顔にしない。意味を 1 枝だけひっくり返して赤になることを
   見る。reader を壊した赤は数えない。`ex-info` はこの機会に Result へ移す。

   **可用性は `which` で測らない。実行して exit code を読む。** 実測
   2026-08-26、`~/.local/bin/kotoba` は消えた `/tmp` の実体を exec する
   2 行の shim で、`which` は通り実行が 126 で落ち、skip されるはずの
   3 test が **16 assertion の赤**になっていた（org-ietf-smtp）。
   `(zero? (:exit (shell/sh bin "--help")))` なら両方向が出る。

   ⚠ **比較を持つ検査には境界ちょうどの入力を 1 つ置く。** 実測 2026-09-06、
   上限比較を `>` から `>=` に反転しても self-check は緑のままだった —— 通る例も
   落ちる例も在ったが線上のケースが無く、2 つの演算子が区別できていなかった。

   ⚠ **JVM-free を主張するなら、無いのではなく拒否して記録する。**
   `java`/`javac`/`clojure`/`clj` の stub を PATH 先頭に置き、呼ばれたら log に
   追記して非ゼロで終わらせ、log が空であることを assert する。**そして その log が
   空でないことを 1 度は見せる**（実測: `amu test` だけが `clojure -M:run` に落ちて
   踏む）—— 踏まれたことのない trace は、常に空な trace と区別できない。
8. **着地。** feature branch を push し `gh api .../merges` で main へ。
   west pin は `nbb scripts/west-pin-put.cljs <entry> HEAD`。

先例: amu `examples/todo-app.kotoba`（application）、
`kotoba-lang/org-ietf-smtp` の `kotoba/smtp/protocol_commands`（コマンド文字列）と
`kotoba/smtp/protocol_response`（返信行の構造。走査を待たずに位置パースへ設計変更）。
fallback: `kotoba-lang/murakumo` の `kotoba/*_core.kotoba`、
`kotoba-lang/org-ietf-smtp` の `kotoba/smtp/protocol_core.{kotoba,cljk}`。
