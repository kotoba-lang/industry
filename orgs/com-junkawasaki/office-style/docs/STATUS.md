# 実装状況 (STATUS)

`office-style` PoC の機能と検証状況。最終更新: 2026-06-24。

## 機能マトリクス

| 領域 | 機能 | 状態 |
|---|---|---|
| 抽出 (決定論) | theme `clrScheme` 12 色 | ✅ |
| | theme `fontScheme` major/minor (latin/ea/cs) | ✅ |
| | slideMaster: 背景・placeholder・p:txStyles | ✅ |
| | slideLayout: type・placeholder・master リンク | ✅ |
| | slideSize + aspect (16:9/4:3/16:10/3:2) | ✅ |
| | guides (1/8 pt → EMU 校正) | ✅ |
| | `clrMap` / `clrMapOvr` 二段解決 | ✅ |
| | 複数 master/theme (master 別 theme + slide→layout→master 束ね) | ✅ |
| | `phClr` の完全解決 (文脈依存) | ⏳ 代替のみ |
| レンダラ | prstGeom (rect/roundRect/ellipse) | ✅ |
| | solidFill / gradFill / pattFill(近似) | ✅ |
| | 回転 (xfrm@rot) / 表 (a:tbl) / 画像枠 (p:pic) | ✅ |
| | placeholder スタイル継承 (master txStyles) | ✅ |
| | custGeom 正確なパス | ⏳ 外接矩形近似 |
| | グラデ放射/パス種・SmartArt・グラフ実体 | ❌ |
| vision | Gemma 4 (transformers.js v4) multimodal | ✅ |
| | Node 実機推論 (cpu/q4, 3.8GB) | ✅ 検証済 |
| | ブラウザ WebGPU (webgpu/q4f16) | ⏳ 配線済・実機 E2E 未 |
| | 複数スライド集約 (`aggregateTones`) | ✅ |
| svgraph | `toSvgraphPresentation` (正規 projection) | ✅ |
| | `toSvgraphIngest` (取り込み最適化・二重ネスト回避) | ✅ |
| | 実 svgraph CLI 流し込み | ✅ 検証済 |

## 検証

| スイート | コマンド | 結果 |
|---|---|---|
| ユニット | `npm test` | 15/15 |
| svgraph 流し込み | `npm run verify:svgraph` | 9/9 (svgraph v0.1.52) |
| 実 OOXML ロバスト性 | `npm run verify:real` | 24/24 (外部生成 pptx) |
| Gemma vision (実機) | `examples/tone.ts` | OK (CPU/q4, ~1524s 初回) |
| 型 | `npm run check` | clean (strict) |

## 今後

- 任意 custGeom の正確なパス、グラデ放射/パス種
- ブラウザ実機 (WebGPU) E2E 検証 (最小 Vite/Worker harness)
- `phClr` の style-matrix ベース完全解決
- 商用デッキ検証 (git-annex 実体の materialize 後)
