---
id: adr-2606241600-shallow-depth1-git-default
title: "ADR-2606241600: git pull / submodule update --recursive は shallow (--depth 1) をデフォルトにする"
status: active
doc_type: adr
topic: git-operations
authoritative: true
last_verified: 2026-06-24
authoritative_for:
  - superproject の日常的な git pull / fetch の既定オプション
  - git submodule update --init --recursive の既定オプション
  - shallow をやめて full 履歴を取得すべき例外条件
related:
  - CLAUDE.md "## Git operations"
supersedes: []
superseded_by: []
---

# ADR-2606241600: git pull / submodule update --recursive は shallow (--depth 1) をデフォルトにする

**Status**: accepted
**Date**: 2026-06-24
**Deciders**: Jun Kawasaki

## Context

本リポジトリは多数の（かつネストした）submodule を抱える superproject である。
`git pull` と `git submodule update --init --recursive` を全履歴で実行すると、

- submodule ごとに全コミット履歴を fetch するため、初回・同期ともに時間と帯域を消費する。
- ネストが深いツリー全体で巨大なオブジェクトを取得し、ディスクを浪費する。
- 一方で日常作業のほぼ全ては「最新ピン先の作業ツリー」だけを必要とし、
  全履歴を実際に参照する場面（`git bisect`、履歴を跨ぐ `git blame`、過去コミット調査）は稀。

実運用上も、`--depth 1` を付けた pull / submodule update で日常作業は完結している。
従来 CLAUDE.md のコマンド例は full clone 前提（`--depth` なし）だったため、
既定挙動とドキュメントが乖離していた。

## Decision

**`git pull` / `git fetch` / `git submodule update --recursive` は `--depth 1`（shallow）を
デフォルトにする。**

```bash
git fetch --depth 1 origin
git pull --ff-only --depth 1
git submodule update --init --recursive --depth 1
```

- fetch も pull/submodule に揃えて `--depth 1` にする（深さ不一致による無駄取得を避ける）。
- main 同期・push 前同期・stash 退避・force-push 検出などの既存ルール（CLAUDE.md
  "## Git operations"）はそのまま適用する。shallow 化はそれらの**取得深さ**だけを変える。
- 例外: 明示的に full 履歴が必要な場合のみ shallow を解除する。
  - `git bisect`
  - 履歴を跨ぐ `git blame` / 古いコミットの調査
  - その他、過去リビジョンの内容そのものを参照する作業

  その場合は対象リポ/submodule をその時だけ深くする:

  ```bash
  git fetch --unshallow            # 全履歴へ
  git fetch --depth=<n>            # n コミット分だけ深掘り
  ```

## Consequences

**Pros**

- pull / submodule update の所要時間・帯域・ディスクを大幅に削減。
- ドキュメント（CLAUDE.md）と実運用の既定挙動が一致する。

**Cons / 留意点**

- shallow clone では履歴を辿る操作（bisect / 履歴 blame）が直接できない。
  必要時に `--unshallow` / `--depth=<n>` で深掘りする運用でカバーする。
- shallow + force-push 上流では `upload-pack: not our ref` 等が起きやすいが、
  これは CLAUDE.md の既存「force-push 検出時はピン先見直しを報告」ルールで扱う
  （shallow 固有の新規問題ではない）。
- ローカル専用データセット submodule（相対 URL の DataLad / git-annex 系、
  `remote: Not Found`）が clone 失敗するのは従来どおり想定内・無害。

## Notes

git config で恒久化する場合の任意設定（チェックアウト時に自動適用したいとき）:

```bash
git config fetch.recurseSubmodules on-demand   # 必要時のみ submodule を fetch
```

ただし `--depth` のグローバル既定化は履歴が必要な作業を壊しやすいため、
**config では強制せず、コマンド時に `--depth 1` を明示する**運用を基本とする。
