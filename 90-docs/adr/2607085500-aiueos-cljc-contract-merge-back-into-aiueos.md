# ADR-2607085500: aiueos-cljc-contract を aiueos へ統合し直す（repo 分割を取り消す）

**Status**: accepted
**Date**: 2026-07-08
**Deciders**: Jun Kawasaki

## Context

ADR-2607022200 は aiueos の意味論の正本を `aiueos-cljc-contract`（CLJC）という
別 repo に固定し、`kotoba-lang/aiueos` を「examples / deployment docs のみ」に
縮退させる決定をした。この分割の根拠は「decides never executes」——aiueos は
判断するだけで実行しない、という永続的な設計原則だった。

しかし本セッションで、この分割が実際には以下の実害を生んでいたことが判明した:

- `kotoba-lang/aiueos` の GitHub 上の実 main branch に、`aiueos-cljc-contract`
  の内容（`src/aiueos/*.cljc` 一式）がほぼそのまま混入する content-mismatch が
  発生していた（どちらが正本か分からなくなっていた）。
- `kotoba-lang/kototama` の `deps.edn` は分割後も長期間
  `io.github.kotoba-lang/aiueos`（examples-only のはずの repo）を
  git/sha 直指定で参照し続けており、しかもその sha は「do not merge yet」と
  明記された到達不能な孤立コミットだった——分割が dependents 側に正しく
  伝播していなかった。
- `aiueos-cljc-contract` の M5 成熟度トラッキングは「kototama の依存先が
  このrepoでなく aiueos 側の stale duplicate を向いている」というambiguity
  を長期間 unresolved のまま抱えていた。
- 2 repo に同じ examples/ADR群（61ファイル中55ファイル）が bit-for-bit
  重複していた——分割は「決定と実行の分離」を実現しておらず、単なる
  ファイルの二重管理を生んでいた。
- `aiueos-cljc-contract` という repo 名自体が「cljc」という実装言語の詳細を
  パッケージ座標に漏らしており、契約(contract)を指す名前として不自然だった。

「decides never executes」という原則自体は正しいが、これはコード/namespace
境界（`aiueos.broker`・`aiueos.execute` の構造そのものが体現している）であって、
物理的に repo を分ける必然性はない。Clojure の tools.deps は同一 repo 内の
サブディレクトリへの依存（`:deps/root`）もサポートしており、「薄い依存を
外部に提供したい」という実利的な理由も分割を要求しない。

## Decision

`kotoba-lang/aiueos-cljc-contract` の全内容と git 履歴を `kotoba-lang/aiueos`
へ merge し（`git merge --allow-unrelated-histories`、`aiueos@63c13fd`）、
分割を取り消す。`aiueos-cljc-contract` は GitHub 上で archived にする
（削除はしない — 既存の commit pin への参照を生かすため）。

- `kotoba-lang/kototama` の `deps.edn` を `io.github.kotoba-lang/aiueos`
  （統合後の単一 repo）へ向け直す。
- `manifest/west.yml` から `aiueos-cljc-contract` の project entry を削除、
  `manifest/repos.edn` からも registration を削除。
- 統合後の `aiueos` README は「examples + CLJC/EDN 権威」を単一 repo の
  ものとして記述し直し、`../aiueos-cljc-contract` への相対パス参照を除去。
- CI は両 repo が持っていた 2 つの job（CLJC contract tests + EDN
  examples/docs 検証）を 1 つの ci.yml に統合。

「decides never executes」の原則自体は撤回しない——`aiueos.broker`/
`aiueos.execute` の namespace 境界として引き続き有効。撤回するのは
「この境界には別 repo が必要」という部分のみ。

## Consequences

- kototama を含むすべての consumer が単一の `kotoba-lang/aiueos` だけを
  参照すればよくなり、今回発覚したような「分割後にdependentsが古い/間違った
  repoを向いたまま気づかれない」というクラスの drift が構造的に起きなくなる。
- `aiueos-cljc-contract` への既存の外部参照（ドキュメント・古い ADR・
  git dependency pin）は GitHub の archive + 既存 commit の保持により
  解決可能なまま残る。
- 90-docs/adr/2607022200 ほか、分割を前提に書かれた既存 ADR
  （2606290900, 2606290930, 2607022700, 2607022900 等）は、当時の decision
  記録としてはそのまま残し、本 ADR が supersede する（内容を書き換えない）。
