# litigation/rokes — Rokes Exchange / HEC ハッキング被害 証跡

関連: `kawasakijun/litigation_state.jsonld` `kj:lit/rokes-hec` / ADR-0005。

## 対象アカウント
- Workspace user `contact@rokes.exchange` (表示名 "support support", OU=root,
  作成 2023-12-27, Gmail ~77MB / Drive ~6MB)。**削除しない** (証拠隠滅リスク回避)。

## 状態 (2026-05-30)
- [ ] admin Data Export (mbox) — **本人のパスキー認証待ち**
      (`admin.google.com/ac/customertakeout`)。これが**正本**。
- [ ] 取得後: `personal/bin/seal-evidence.sh rokes <export.tgz>` で封緘
      (SHA-256 + OpenTimestamps + annex)。
- [ ] root@jk.luxury へ移管 → MCP 再接続 → 検索用副本を warehouse へ。

## 取り込み手順 (取得後)
```bash
personal/bin/seal-evidence.sh rokes ~/Downloads/takeout-*.tgz
git annex add personal/litigation/rokes/
git add personal/litigation/rokes/MANIFEST.md personal/litigation/rokes/*.ots
git annex copy personal/litigation/rokes/ --to ipfs
git commit -m "evidence(rokes): seal + ingest (ADR-0005)"
```
