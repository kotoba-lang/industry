# ADR-0004: 係争関連 Workspace アカウントの証拠保全（削除しない・管理者保全）

- **Status**: Accepted
- **Date**: 2026-05-30
- **Deciders**: 河崎純真 (jun784@gmail.com)
- **Context tags**: litigation, evidence-preservation, google-workspace, custody, rokes, privacy

## Context

Google Workspace ユーザー `contact@rokes.exchange`（表示名 "support support",
OU=root, 作成 2023-12-27, Gmail 77MB / Drive 6MB, Business Standard）の
削除と root@jk.luxury へのデータ引き継ぎを検討した。

しかし当該アドレスは **係争中の「Rokes Exchange / HEC ハッキング被害」案件
（`litigation_state.jsonld` の `kj:lit/rokes-hec`）の当事者アドレス**であり、
メール履歴は法的証跡である。検討の結果、以下が判明した：

1. **削除は証拠隠滅（スポリエーション）リスク**になり得る。係争中の当事者
   アカウントの削除は不適切。削除後 ~20日で原本が消滅する。
2. **Gmail MCP には添付ダウンロード機能がない**ため、MCP 単体では証跡グレードの
   完全保存ができない（添付欠落）。
3. MCP の OAuth は `jun784@gmail.com` 固定であり、**管理者権限があっても別ユーザー
   の受信箱を MCP で直接読めない**。
4. アイシステム送金履歴（ボトルネック#1）の証跡は、別ルートで税理士法人TOTAL
   水鳥氏より受領済み（`kj:evidence_ledger` 参照）。

## Decision

係争関連アカウントの証拠保全は **「削除しない」を原則**とし、管理者権限のみで
（対象ユーザーのパスワード不要で）完結する手段で保全する。

1. **削除ではなく停止 (suspend)**：原本を生かしたまま利用・課金を止める。
2. **一括スナップショット**：管理コンソール → アカウント →
   「データのエクスポート」（admin 版 Takeout）で全ユーザー分を一括書き出し。
   メールは mbox。これを**正本**とする。※実行・最終ボタンは本人が操作
   （Claude は読み取りナビのみ、破壊的・認証操作は代行しない）。
3. **恒久ホールド（任意・余裕があれば）**：Google Vault の retention + hold。
   Business Standard は Vault 非対応のため対象 1 ユーザーを Business Plus 等へ。
   eDiscovery エクスポートは監査ログ・ハッシュ付き。
4. **改ざん不能の封緘**：取得物に SHA-256 を計算し OpenTimestamps（オンチェーン
   存在証明）または RFC3161 TSA でタイムスタンプ。`personal/litigation/` 配下の
   **git-annex（キー＝コンテンツハッシュ）**に取り込み custody chain を担保。
5. **保全方法は代理人主導**：提出形式・ホールド要否は ZeLo / AMT の指示に従う
   （admissibility に影響するため）。

## Consequences

- (+) 原本を保全し、スポリエーション・リスクを回避。
- (+) 管理者権限のみで完結（対象ユーザーのパスワード・生体認証を Claude が扱わない）。
- (+) annex のハッシュアドレス + OpenTimestamps で改ざん検知可能な custody。
- (−) 停止継続で月額課金（¥1,900/月）が残る。証拠価値 ≫ 月額として許容。
- (−) Vault を使う場合はライセンス上げが必要。
- (−) MCP による warehouse 取り込みは、root@jk.luxury へ移管後に MCP を
  再接続して初めて可能（add-on 的に後続）。

## Status / Next

- 進行中：管理コンソールの「データのエクスポート」画面まで遷移、Google の
  パスキー段階認証で停止（本人による認証待ち）。
- 次アクション：(a) 本人がパスキー認証 → データエクスポート実行 →
  (b) Claude が SHA-256 封緘 + OpenTimestamps + `personal/litigation/rokes/` 取り込み。
- 関連：`kawasakijun/litigation_state.jsonld` `kj:evidence_ledger` /
  `kj:lit/rokes-hec`、`personal/analysis/observations.md` loop #7。

## Alternatives considered

- **削除 + データ移行のみ**：原本消滅 → 却下（証拠隠滅リスク）。
- **MCP のみで warehouse 保存**：添付欠落・別ユーザー読取不可 → 不十分。
- **本人サインインで Takeout**：パスワード/生体認証が必要で Claude は扱えない。
  管理者一括エクスポート（B）の方が権限のみで完結し優位。
