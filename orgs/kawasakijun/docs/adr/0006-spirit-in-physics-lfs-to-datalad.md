# ADR-0006: spirit-in-physics サブモジュールの大容量ファイルを Git LFS から DataLad/git-annex へ移行

- **Status**: Accepted（購入ロック解除済み 2026-06-09 — LFS 取得可能、移行は未実行）
- **Date**: 2026-06-01（更新: 2026-06-09 ロック解除確認）
- **Deciders**: 河崎純真 (jun784@gmail.com)
- **Context tags**: submodule, git-lfs, datalad, git-annex, billing-lock, spirit-in-physics, storage-policy

## Context

研究リポジトリを git submodule として取り込んだ（ADR-0007 のリポ再編により
`projects/` → `orgs/com-junkawasaki/` 配下へ移動。`.gitmodules` の論理名は
`projects/*` のまま、`path` は `orgs/com-junkawasaki/*`）：

| submodule | path | 大容量ファイル機構 |
|---|---|---|
| `260208-spirit-in-physics` | `orgs/com-junkawasaki/260208-spirit-in-physics` | なし（通常リポジトリ） |
| `spirit-in-physics` | `orgs/com-junkawasaki/spirit-in-physics` | **Git LFS + 半移行の git-annex（混在）** |
| `webmaster` | `orgs/com-junkawasaki/webmaster` | なし（参考: 同時取り込み、本 ADR の対象外） |

取り込み時に `spirit-in-physics` で以下が判明した：

1. **Git LFS で 115 オブジェクト**（mp3/png/pdf 等、いずれも KB〜MB 級・合計 ~2.6MB）。
   `.gitattributes` は `*.webm *.mp3 *.wav *.mp4 *.mov *.mkv *.pdf *.docx *.pptx *.xlsx
   *.zip *.7z *.tar *.gz *.bz2 *.png *.jpg *.jpeg *.gif *.webp *.ttf *.otf`
   を `filter=lfs` で追跡。
2. **`.webm`（dataset 動画 20 本＝参加者 10 名 × 2 セッション、各 ~175MB）は
   git-annex のポインタファイル**（`/annex/objects/SHA256E-…`）としてコミットされて
   いるが、`git-annex` ブランチが**ローカルにもリモートにも存在せず**、
   annex も未初期化 → 中身の在処を示すメタデータが皆無で**孤児化（取得不能）**。
   過去に「LFS → annex 移行」を途中で止めて `git-annex` ブランチを push せず放置した
   半移行状態であり、これは LFS→DataLad 切替では直らない既存欠陥。
3. **clone が LFS smudge で失敗**するため `GIT_LFS_SKIP_SMUDGE=1` で取り込んだ
   （commit `e75c3e92`：`.gitmodules` + 2 gitlink のみ）。
4. **`git lfs pull` がブロック**：`This repository exceeded its LFS budget.`
   Billing → Budgets and alerts で **Git LFS は $20 budget / $0 spent / Stop usage: Yes**
   と設定済みにもかかわらず解除されない。
5. 根本原因は **アカウント `com-junkawasaki` が「locked from purchases」**
   （`Please update your payment information. Please visit https://support.github.com`）。
   これは支払い方法の不備ではなく **GitHub バックエンドの課金状態(billing state)ロック**で、
   community #19123 等の解決報告では **支払い情報更新では解けず、Support 連絡で解決**。

ワークスペースの保全方針（`deps.edn :personal :storage`：vcs=datalad / content=git-annex /
remote=ipfs）は既に DataLad/git-annex に統一されており、LFS はこの方針から外れる。

## Decision

`spirit-in-physics` の大容量ファイルは **Git LFS をやめ DataLad/git-annex に統一**する
（ワークスペース保全方針との整合）。ただし annex 化には対象ファイルの**実体が必要**で、
実体は LFS サーバ上にのみ存在し購入ロックで取得不能なため、**まず解除が前提**。

1. **GitHub Support にロック解除を依頼**（実施済み: 2026-06-01）。
   Billing and payments → General billing and payments → Other、
   account = `com-junkawasaki`、deployment = GitHub.com で送信。
   → **2026-06-09 解除確認**：`git lfs fetch --all origin` が成功
   （286 オブジェクト検出 / 114 実体 ~2.6MB 取得 / exit 0、budget・lock エラー無し）。
   submodule 取り込み時の smudge も成功し作業ツリーに実体が展開済み。
2. **解除後の移行手順**（履歴は書き換えず前進コミットのみ／**未実行**）：
   ```
   cd orgs/com-junkawasaki/spirit-in-physics
   git lfs pull                 # 115 件(KB〜MB級)の実体取得・即完了（解除済みのため可）
   git lfs uninstall --local    # LFS フック無効化
   datalad create -f .          # 既存リポを DataLad 化（git-annex init 込み）
   # .gitattributes の filter=lfs を annex 管理へ置換
   datalad save                 # 実体を annex 取り込み、LFS ポインタ除去
   ```
   仕上げに superdataset 側で `datalad save` し subdataset コミットを記録。
3. **`260208-spirit-in-physics`** は大容量機構を持たないため対象外（通常 submodule のまま）。
4. **孤児化した `.webm`** は別課題として扱う（実体の在処を別途特定するまで保留）。

## Consequences

- (+) 大容量ファイルが workspace 共通の DataLad/git-annex（→ IPFS, gpg-hybrid）に統一。
- (+) LFS の予算/帯域/ロックという外部課金依存を排除。
- (+) 履歴非書換のため、上流リポへの force-push 不要・既存ブランチ群に非破壊。
- (✓) 購入ロックは 2026-06-09 に解除確認。LFS 実体取得が可能となり、移行の外部依存は解消。
  残るは移行手順の実行（前進コミット）のみ。
- (−) `.webm` dataset 動画 20 本は annex 実体の在処が不明な限り復元不能（既存欠陥として残置）。
- (−) 上流 GitHub リポを annex 化して push し直すか、superdataset 内管理に留めるかは
  解除後に別途判断（未決）。

## Status / Next

- 完了：submodule 取り込み（`e75c3e92`）、課題切り分け、Support 送信（2026-06-01）、
  ADR-0007 リポ再編で `orgs/com-junkawasaki/` 配下へ移動、
  **購入ロック解除確認（2026-06-09: `git lfs fetch --all` 成功）**。
- 解消：~~`com-junkawasaki` 購入ロック~~ → 解除済み。LFS 実体取得が可能になった。
- 次アクション：(a) ~~解除確認~~済 → (b) **上記移行手順（LFS→DataLad/git-annex）を実行**
  → (c) `.webm` 20 本（annex 孤児ポインタ）実体の在処調査（別課題のまま）。
- 関連：`.gitmodules`、`deps.edn`（projects: spirit-in-physics / 260208-spirit-in-physics /
  webmaster）、`deps.edn :personal :storage`、ADR-0007（orgs レイアウト）。

## Alternatives considered

- **Git LFS のまま予算引き上げで運用**：購入ロックで予算設定が効かず、かつ workspace 保全
  方針（DataLad/git-annex 統一）から外れる → 却下。
- **支払い方法を更新してセルフ解除**：本ロックは billing-state 由来で支払い更新では
  解けない報告が多数 → Support 連絡を優先。
- **履歴を書き換えて LFS→annex 変換（git-filter-repo 等）**：別リポへの force-push が必要で
  不可逆、かつ実体が無い状態では無意味 → 却下。
- **annex の特殊リモート(git-lfs)経由で取得**：同じ LFS エンドポイント＝同じロックに当たる
  → 解除前は無効。
