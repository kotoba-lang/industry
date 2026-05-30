# ADR-0004: Personal Warehouse — Account Scope & File-Body Capture

- Status: Accepted
- Date: 2026-05-30
- Deciders: Jun Kawasaki
- Implementation: `personal/` (this repo)
- Related: ADR-0003 (warehouse: DataLad + git-annex + encrypted IPFS)

## 1. Context

ADR-0003 で個人データウェアハウスの保管・暗号化基盤を定めたが、暗黙に
**単一アカウント (jun784@gmail.com)** かつ **メタデータ/スニペット中心** の取り込みだった。

新たな要件 (ユーザー指示, 2026-05-30):

1. **全 Google アカウント**を ingest 対象に含める:
   `jun784@gmail.com` / `root@jk.luxury` / `root@junkawasaki.com` / `jun@gftd.group`。
2. **全データを download** して datalad で保存する (メタデータだけでなく**ファイル本体**)。

実機で判明した制約:

- **MCP の Google コネクタ (Gmail/Calendar/Drive) は単一 OAuth セッションに固定** (=jun784)。
  アカウント切替パラメータは存在せず、`list_calendars` も jun784 のみ返す。
  → 他アカウントには **この経路では到達不可**。
- Chrome のログイン状態 (アカウントチューザー実測):
  `jun784@gmail.com` (/u/0) と `root@jk.luxury` (/u/1) の **2つのみ**。
  `root@junkawasaki.com` / `jun@gftd.group` は**未ログイン**。
- `download_file_content` は **base64 をツール結果としてインライン返却**する。
  大容量ファイルはモデルのコンテキスト/トークンを非現実的に消費する
  (例: 9.3MB docx → ~12MB base64 → ~3M tokens)。

## 2. Decision

### 2.1 アカウント到達性マトリクス (SSoT = `deps.toml [[personal.account]]`)

| アカウント | 到達経路 | 本ウェアハウスでの扱い |
|---|---|---|
| `jun784@gmail.com` | MCP コネクタ (+ Chrome /u/0) | **ingested** (primary) |
| `root@jk.luxury` | Chrome /u/1 のみ (MCP 未接続) | **pending** |
| `root@junkawasaki.com` | なし | **unreachable** |
| `jun@gftd.group` | なし | **unreachable** |

他アカウントを取り込むための承認経路 (いずれも**ユーザー操作必須**):

1. **MCP コネクタ追加** (推奨): Claude 設定で各アカウントを Google 連携。
   接続後は jun784 と同一手順で構造化 ingest 可能。
2. **Google / Admin Takeout**: 本人認証してエクスポート → アーカイブを DL →
   datalad に取込 (Claude はパスワード入力・サインインを行わない)。
3. **ブラウザ DOM**: 既ログイン (jk.luxury) なら部分的メタデータ取得は可能だが、
   メールボックス全体の倉庫化は非現実的。未ログイン口座はまず Chrome ログインが前提。

### 2.2 ファイル本体キャプチャ方針 (tiered)

- **本体を annex 化する対象 = 高価値・中小容量の原本**:
  確定申告一式 / 金銭消費貸借契約書 / 売買・株式譲渡・登記等の契約・法人原本 /
  暗号資産取引レポート。`download_file_content` → base64 復号 → annex (ADR-0003 の暗号経路)。
  - コンテキスト溢れ防止のため **slice 単位で subagent に委譲**し、base64 は subagent 側で
    受けてディスクに `base64 -d`。親には保存マニフェスト (ファイル名/バイト/型) のみ返す。
- **メタデータのみ保持する対象 = 大容量・低価値・大量**:
  数 MB 級 PDF・画像 (image/jpeg)・zip・機械生成 symlink/git-object サブツリー。
  完全なバイナリ・バックアップが必要なら **Takeout/ブラウザ DL** に回す
  (MCP base64 はトークン非現実的)。インベントリ (`files-inventory-full.json`) で所在は保持。

### 2.3 配置

```
personal/drive/
├─ files-inventory-full.json          # 全 620 件メタデータ (account=jun784)
├─ files-inventory-full-summary.json  # mimeType 別 + 高価値 id リスト
└─ files/                             # 本体バイナリ (annex 化)
   ├─ tax/             令和7年度 確定申告一式 (19)
   ├─ loans/           2025 金銭消費貸借契約書 (19)
   └─ contracts-corp/  オランダ物件売買契約・株式譲渡・登記・取引レポート (9)
```

## 3. Rationale

- **単一 OAuth の制約を設計に明記**: 「全アカウント取得」は MCP 単独では達成不能であり、
  到達経路をアカウント単位で SSoT 化することで、未取得が「やり残し」でなく
  「外部承認待ち」であることを構造的に示す。
- **本体取得 vs コンテキスト予算のトレードオフ**: download は base64 インライン返却のため、
  全件本体取得は非現実的。**価値×容量**で tier を切り、原本性が重要な法務・税務・金融文書を
  優先 annex 化、嵩物は Takeout に委譲する。
- **subagent 隔離**: base64 の嵩を subagent コンテキストに閉じ込め、親の文脈を汚さない
  (ADR-0003 の暗号・annex 経路はそのまま再利用)。

## 4. Consequences

### 4.1 Positive

- 確定申告・借入契約・主要契約の**原本バイナリ**が暗号化ウェアハウスに保全された (47 件)。
- アカウント到達性が `deps.toml` で機械可読化され、次アクション (connector/Takeout) が一意。

### 4.2 Negative / Risks

- `jk.luxury` (LingLing 訴訟・LATOKEN 等の重要口座) が **pending** のまま。
- `junkawasaki.com` / `gftd.group` は **unreachable** で、ユーザーのログイン/連携なしには進めない。
- 大容量・画像・zip の本体は未取得 (メタデータのみ)。完全バックアップは別経路依存。

### 4.3 Mitigations

- 次アクションを明示: jk.luxury を **MCP コネクタ接続** すれば即 jun784 同等の ingest。
- 嵩物は **Takeout** バッチで取得し datalad に取込む手順を README/本 ADR に記録。

## 5. Implementation status

- ✅ アカウント到達性を実機確認し `deps.toml [[personal.account]]` に SSoT 化
- ✅ jun784: Drive 全インベントリ (620) + 本体 47 件 (tax/loans/contracts-corp) を annex 化
- ✅ jun784: Gmail 金融履歴拡充 (finance-history-3) + Calendar 欠落期間補完
- ⏳ jk.luxury: MCP コネクタ接続 or Takeout (warehouse-scale ingest)
- ⏳ junkawasaki.com / gftd.group: Chrome ログイン → connector/Takeout
- ⏳ jun784 嵩物 (大容量 PDF/画像/zip) の Takeout バックアップ
- ⏳ 取得分の IPFS pin (`git annex copy --to ipfs`; 明示操作 — kubo 起動が前提)

## 6. References

- ADR-0003 — warehouse 基盤 (DataLad + git-annex + encrypted IPFS)
- `deps.toml [personal]` / `[[personal.account]]` — アカウント到達性 SSoT
- `personal/analysis/2026-05-30_account-download.md` — 本ループの取得記録
- `personal/drive/files-inventory-full.json` — Drive 全件メタデータ
