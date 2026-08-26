---
name: kotoba-clj-to-kotoba
description: clj / cljc から .cljk と .kotoba へ、決定核を 1 スライスだけ正しく移す。判断核は切り方であって文字列禁止ではない。『clj から kotoba』『cljk』『decision core』『kotoba compile』で発火。
---

# clj → .cljk / .kotoba（1 スライス）

**正本は ADR-2608261000**（切り方と手順）。分類は ADR-2608650000 と
`lang/surface-status.edn` の `:disposition`。この skill は 1 スライスの手順書。

## 混ぜない

| 層 | 例 | 扱い |
|---|---|---|
| 恒久の安全 | `throw`、ホスト interop、ソケット、`:max-parameters 5` | 広げない |
| 意味の単純化 | bool は数ではない | 広げない |
| 部分実装 / 未達 | map の native、正規表現演算、untyped `or` が i64 | 待たずに切るか、型で回避 |
| **このスライスの切り方** | 線の CRLF、走査パース、使われない第二 formatter | 核にしない。言語の天井と書かない |

**文字列は禁止されていない。** 判断が文字列の上に載るなら核に入れる。
『判断だけ』を『文字列を持たない』と読まない。

## 手順

対象は west 管理なら **origin/main から worktree**（共有 `orgs/` を直接編集しない）。

1. **切る判断を名指しする。** ゲストが JVM も Node も持たずに答える問。効果と
   線の形は残す。残す理由を disposition で書く。
2. **`.cljc` を oracle のまま残す。** 核を require しない。set / map / nil /
   線形式は adapter がスカラー（と必要な文字列）へ落とす。引数は 5 以下。
3. **同じ判断を `.kotoba` と `.cljk` に書く。** `.kotoba` は typed safe core、
   `.cljk` は `:clj-kotoba`（JVM target ではない）。本体が同じなのは欠陥ではない。
   untyped の `and`/`or` が i64 になるなら型注釈を付けて表を保つ。
   `cond` は grammar が desugar する。入れ子 `if` を様式にしない。
   `ex-info` はこの機会に Result へ移す。
4. **公開 compile は CLI。**

```
kotoba compile path/to/core.kotoba --target wasm -o core.wasm
kotoba compile path/to/core.cljk  --target wasm -o core.wasm
kotoba compile path/to/core.kotoba --target web  -o core.mjs
```

5. **parity は表の直積。** `compiler/compile-source` → `ir/execute` を既存関数と
   突き合わせる。CLI compile は binary が無ければ skip し、skip と pass を
   同じ顔にしない。意味を 1 枝だけひっくり返して赤になることを見る。
   reader を壊した赤は数えない。
6. **着地。** feature branch を push し `gh api .../merges` で main へ。
   west pin は `nbb scripts/west-pin-put.cljs <entry> HEAD`（共有 checkout で
   west.yml を手編集しない）。

先例: `kotoba-lang/murakumo` の `kotoba/*_core.kotoba`、
`kotoba-lang/org-ietf-smtp` の `kotoba/smtp/protocol_core.{kotoba,cljk}`
（2026-08-26、KIR digest 一致、SASL 全直積の parity）。
