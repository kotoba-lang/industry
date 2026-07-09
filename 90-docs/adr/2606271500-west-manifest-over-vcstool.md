---
id: adr-2606271500-west-manifest-over-vcstool
title: "ADR-2606271500: west manifest を採用し submodule を全廃（vcstool/repo/meta 比較）"
status: active
doc_type: adr
topic: repo-management
authoritative: true
last_verified: 2026-06-27
authoritative_for:
  - manifest repo 管理ツールの選定 (west vs vcstool / google repo / meta)
  - git submodule から west manifest への移行方針 (server-side clean commit)
  - west の source of truth と生成 (manifest/repos.edn → scripts/gen-west-manifest.bb → west.yml)
  - DataLad の west 統合 (userdata + datalad group + west annex-get/annex-drop)
  - B2 認証の解決順 (env→1Password→Keychain, scripts/b2-creds.bb)
related:
  - adr-2606241428-submodule-weight-shallow-b2-datalad
  - adr-2606241600-shallow-depth1-git-default
supersedes: []
superseded_by: []
---

# ADR-2606271500: west manifest を採用し submodule を全廃（vcstool/repo/meta 比較）

**Status**: accepted
**Date**: 2026-06-27
**Deciders**: Jun Kawasaki

## Context

superproject は git submodule（一部ネスト）で子リポを抱えていたが、subtree/submodule の
直運用では「綺麗に管理できない」状態だった。スケールは大きい:

- トップレベル submodule **30**、ツリー全体の submodule 実体 **124**、ネスト深度 **最大 21 階層**
  （`orgs/<org>/<repo>` の組織入れ子 + 子リポ自身の `.gitmodules`）。
- CLAUDE.md は shallow（`--depth 1`）既定・main 同期最優先・大容量は B2+DataLad を要求。

manifest repo 方式（宣言ファイルで複数リポを一括管理）への移行を検討し、
**vcstool / google repo / west / meta** を比較した。

## Decision

**west を採用する。** 加重スコア（このリポの要件で重み付け）で west が最上位:

| 評価軸 | 重み | vcstool | google repo | west | meta |
|---|---|---|---|---|---|
| 階層ネスト対応(21階層) | 0.25 | 2 | 3 | **5** | 1 |
| shallow/depth-1 per-project | 0.20 | 4 | 5 | 5 | 2 |
| スケール/並列 | 0.15 | 4 | **5** | 3 | 2 |
| 成熟度/保守 | 0.15 | 4 | **5** | 4 | 2 |
| シンプルさ | 0.10 | **5** | 2 | 3 | 4 |
| submodule からの移行性 | 0.10 | 4 | 3 | 3 | 4 |
| エコシステム適合(bb 化等) | 0.05 | 4 | 4 | 4 | 4 |
| **加重合計** | 1.00 | **3.60** | **3.95** | **4.10** | **2.25** |

決め手は、実際に運用で使い切った 3 機能:

1. **repo-in-repo（真の入れ子）の再帰**。"nest" には 2 種ある:
   - (a) パスの入れ子（ただのディレクトリ階層 `orgs/a/b/repo`）— どのツールでも可。
   - (b) クローンした repo が自分の `.gitmodules` を持ち、その子もまた持つ（このリポは
     ai-gftd-apps 配下の Solidity lib が **21 階層**）。
   **vcstool は (b) を辿れない** — `.repos` はフラットな repo 列挙で、クローン先の内部
   submodule は未初期化のまま放置する（自己記述な階層を展開しない）。west は
   `submodules: true` で git submodule を再帰取得し、`import:` で子の west.yml を
   再帰取り込みできる。
2. **per-project の `clone-depth: 1`**（CLAUDE.md の shallow 既定をリポ単位で宣言）。
   vcstool の shallow は全体一括のみ。
3. **拡張性**（west 拡張コマンド / `userdata` / `group-filter`）。これにより DataLad を
   west の中に一元化できた（後述）。vcstool には拡張コマンド機構が無い。

### vcstool で同等にするコスト（=「flat 化」の意味）

vcstool で (b) をカバーするには、入れ子の子・孫 submodule を **1 枚の `.repos` に手で
平らに展開**し、上流が submodule を足すたびに手で追従する必要がある。階層の知識を
manifest 側に全部持たせ続ける運用コストが発生する。階層を 2〜3 層へ恒久的に
平坦化できるなら vcstool が最良（最もシンプル・`vcs export` で移行も容易）だが、
本リポは現構造を保ったまま管理 + DataLad 統合を求めたため west を選んだ。

### 採用構成

- **source of truth = `manifest/repos.edn`**（ポリシー: remotes / 既定 / group-filter /
  DataLad / B2）。`scripts/gen-west-manifest.bb`（babashka）が EDN + git の事実
  （各 working tree の HEAD）から `manifest/west.yml` を生成する（手書き禁止 / `--check` で CI）。
- **トポロジ**: topdir = superproject ルート、manifest repo = `manifest/` サブディレクトリ
  （`self.path: manifest`）。project の `path:` は旧 submodule と同一（`orgs/<org>/<repo>`）で、
  チェックアウトのディレクトリ名に依存しない（topdir を manifest の親として導出）。
- **submodule は全廃**（`.gitmodules` 削除、gitlink 0）。コードリポは全て west。
- **DataLad を west に統合**: DataLad dataset（`m365-archive`）も west project にし、
  `userdata.datalad: true` + `datalad` グループ（既定 `group-filter` の `-datalad` で off=
  opt-in）に隔離。git/annex スケルトンは `west update --group-filter +datalad <name>`、
  実体（B2）の取得/破棄は west 拡張コマンド `west annex-get` / `west annex-drop`
  （`manifest/west_annex.py`）。
- **B2 認証は `scripts/b2-creds.bb` が解決**（順序 `env → 1Password(op) → Keychain(security)`、
  参照先は `repos.edn :b2 :credentials`）。秘密はリポジトリに置かず、`op://` パスや
  Keychain service 名といった非機密の参照先だけを EDN に置く。CI は env を最優先。
- **運用ルール（CLAUDE.md）を west 前提に改訂**（`git submodule update` → `west update`、
  DataLad 連携を追記）。shallow / merge-base 狙い撃ち / main 同期 / push-guard / B2 の
  既存原則は維持。

### 移行の実施（shallow マージを避けサーバ側でクリーン commit）

ローカルは shallow + main から乖離（ahead/behind）していたため、ローカルマージを
戦わず **main の現在 tree をベースに GitHub API でクリーン commit を作成 → PR → merge**
した（CLAUDE.md の PR #61/#62 と同方式。merge-base/ancestry はサーバが full 履歴で計算）。

| PR | 内容 | 差分 |
|---|---|---|
| #86 | 31 リポを west 化（gitlink 撤去・clone-depth:1・ネスト再帰） | ahead 1 / behind 0 |
| #87 | 残り全リポを west 化 + `repos.edn` source of truth + DataLad 統合（**submodule 全廃**） | ahead 1 / behind 0 |
| #88 | B2 認証の自動解決（env→1Password→Keychain, `b2-creds.bb`） | ahead 1 / behind 0 |

いずれも main の記録 pin を基準にしたため既存 submodule 更新の regression なし。
ローカル未コミット/未push の作業が残るリポ（root / network-isekai）は pin を
main 記録値に固定し、west は untracked を消さず tracked 衝突時は checkout を拒否する
ため、ローカル作業は保全される（owner が reconcile してから pin 前進）。

## Consequences

- (+) 21 階層のネストを `submodules: true` 一行で再帰取得。per-project shallow を宣言で固定。
- (+) submodule の deinit/再 init 地獄から解放。取得は `west update --fetch smart` に一本化。
- (+) ポリシーが `repos.edn` に集約され、`west.yml` は生成物（人は EDN だけ編集）。
- (+) DataLad/B2 が west の中（`west annex-get/drop` + opt-in グループ）に統合され、
  重い annex は既定で引かない。認証は 1Password/Keychain/env を同一コマンドで解決。
- (−) west は vcstool より概念が多く学習コストが高い（YAML 1 枚では済まない）。
- (−) west 1.5 の `west update` は `-j` 非対応（直列）。複数指定は zsh の単語分割に注意し
  `xargs` を使う。
- (−) `west annex-get/drop` は実機（B2 creds + 実 clone）未検証のスキャフォールド。初回は
  `repos.edn :b2 :credentials` の参照先編集と `scripts/datalad-b2-init.bb` での initremote 要確認。
- (−) 将来「階層を浅くして簡素化」に方針転換するなら vcstool が有力。`west.yml → .repos` は
  機械変換できる。

## References

- 生成/ポリシー: `manifest/repos.edn`, `scripts/gen-west-manifest.bb`, `manifest/west.yml`
- DataLad 統合: `manifest/west_annex.py`, `manifest/west-commands.yml`
- B2 認証解決: `scripts/b2-creds.bb`（env→1Password→Keychain）
- 運用: `manifest/README.md`, `CLAUDE.md`（west 前提に改訂）
- 関連: ADR-2606241428（肥大 submodule の shallow 運用 + B2/DataLad）,
  ADR-2606241600（shallow depth1 を git 既定に）
- PR: #86 / #87 / #88（com-junkawasaki/root）

## 完了・最終状態（2026-06-27 追記）

移行は完了し、superproject(`com-junkawasaki/root`)は west 一元管理になった。

- **submodule 全廃**: `.gitmodules` なし / index gitlink 0 / ローカル `.git/config` の
  旧 submodule entry(76件・`submodule.recurse=true` 含む)も掃除済み。
- **manifest が source of truth**: 35 project(34 通常 + DataLad の m365-archive 1)。
  新規リポ(aiueos / ai-gftd-yukkuri / club-shinshi など)は submodule ではなく
  manifest に追加する運用が定着。
- **改名の統合**: `drawingml-svg` は `svgraph` へ改名済み。旧名の重複エントリ
  (孤児 pin 0035b035、origin から消失)を manifest/.gitignore から除去した。
- **B2 認証**: `scripts/b2-creds.bb` が env→1Password→Keychain で解決(PR #88)。
- **未 PR 作業の救済**: 各 project の未push 作業/stash は reconcile ブランチ・PR 化して
  消失を防止(stash は branch に移行)。機密(litigation)は push せずローカル branch で温存。
- **残レガシー(無害)**: `.git/modules` に network-isekai/root の git 実体が gitfile 参照で
  残るが機能上問題なし(完全除去は .git 再配置が必要なため見送り)。
