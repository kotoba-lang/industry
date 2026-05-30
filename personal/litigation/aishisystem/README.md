# litigation/aishisystem — アイシステム送金履歴 証跡

関連: `kawasakijun/litigation_state.jsonld` `kj:evidence_ledger` /
`kj:lit/ling-ling-cluster` (共通根=2021年 預かり資金移動) / ADR-0005。

## 受領済み (水鳥智仁 / 税理士法人TOTAL より)
- `添付資料.zip` — 2025-06-30 受領。2021年 預かり資金移動・アイシステム振込履歴・仕訳。
- `追加資料_20260226.zip` — 2026-02-06 受領。追加証跡。
- `判決（令和7年10月8日付）.pdf` — 2026-01-05 本人回覧。

## 状態 (2026-05-30)
- [x] 開示完了 (DISCLOSED) — Gmail thread `197abcf1ab4ac875` 添付。
- [ ] warehouse 取り込み (NOT_YET_INGESTED) — Gmail MCP は添付DL不可。
      → ブラウザDL or Drive 再共有でローカルに落とす必要。

## 取り込み手順 (zip 取得後)
```bash
personal/bin/seal-evidence.sh aishisystem ~/Downloads/添付資料.zip ~/Downloads/追加資料_20260226.zip
git annex add personal/litigation/aishisystem/
git add personal/litigation/aishisystem/MANIFEST.md personal/litigation/aishisystem/*.ots
git annex copy personal/litigation/aishisystem/ --to ipfs
git commit -m "evidence(aishisystem): seal + ingest (ADR-0005)"
```

## 注意
- 内容には第三者PII (OUCHI/NETHユーザー氏名・住所・ウォレット・KYC) が含まれ得る。
  **派生分析ファイルに第三者PIIをコンパイルしない**。原本は annex 暗号化のみ。
- 2021年資金移動の全件突合 (MoneyForward × 220415) は本人/弁護団管理下で。
