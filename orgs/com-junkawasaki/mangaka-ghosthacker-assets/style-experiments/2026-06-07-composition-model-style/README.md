# 2026-06-07 — Composition model × style comparison

ghosthacker arc0-1 の **構図画像生成 model 比較**。狙いは process
**「構図画像生成 model → ControlNet → Animagine(最終)」** の前段(=studio の
previs / レイアウト 工程の AI 版)で、どの model / style が最良の構図を作るか特定すること。

## 比較した model(OpenRouter image-output、全てプロプライエタリ)
- `google/gemini-2.5-flash-image`(正方形固定)
- `google/gemini-3-pro-image-preview`(アスペクト尊重・環境リッチ)
- `google/gemini-3.1-flash-image-preview`(アスペクト尊重・速い・本命候補)
- `openai/gpt-5-image`(クリーン・テキスト強い)
- `openai/gpt-5.4-image-2`(framing 最良・ただし遅い/不安定)
- OSS baseline = Animagine XL 4.0(`/tmp/m4-render`、ControlNet-depth m4。構図は最弱)

## style(`scripts/compare_openrouter.py STYLES`)
基本: manga(白黒) / jitsusha(実写) / hollywood-cg / artwork / kyoani(京アニ)
工程: ekonte(絵コンテ) / storyboard / layout(レイアウト) / previs(3D gray) /
postviz / virtual-prod(Unreal LED) / pixar(3D)

## 採点(scores/orcmp-scores.json)
中立 judge `anthropic/claude-opus-4.8-fast` が ControlNet 入力適性で盲検採点
(framing / placement / perspective / anatomy / readability)。n=11 full-coverage では
**gpt-5.4-image-2 = 7.93 が最良**、gemini-3-pro/3.1-flash が 8.0 前後(サンプル少)。
プロプライエタリは全て 7.7–8.05 で Animagine を大きく上回る。

## フォルダ
| | |
|---|---|
| `panels-openrouter/<model>/` | manga(白黒)パネル(p0/p1 全コマ × 5 model) |
| `panels-openrouter/<style>__<model>/` | style 別パネル(p0 + バトル p37-39 / 部屋 p6) |
| `full-pages/p{6,37,38,39}-{virtual-prod,pixar,ekonte}.png` | フルページ komawari 合成 |
| `full-pages/p{N}-compare.png` | 3 style 横並び比較シート |
| `compare-sheets/orcmp_p0_full.png` | p0 全コマ × 6 model |
| `compare-sheets/p1_allstyles.png` | p0#1 を全 12 style × 2 model |
| `compare-sheets/style_grid.png` | p0 を 5 style × 2 model |
| `scores/orcmp-scores.json` | 構図採点(judge=claude-opus-4.8-fast) |

## 所見
- 静/雰囲気(p6 Ren の部屋)→ **virtual-prod / Pixar** が質感・奥行きで圧勝
- 動/バトル(p39 /dev/null SLASH)→ **絵コンテ(線画)** の躍動感が勝つ(実写/3D は静止画だと迫力減)
- ControlNet 制御ソース適性: layout(canny)/ previs・実写(depth)/ ekonte(線+構図)
- ユーザ評価: virtual-prod / pixar / ekonte が有力 → シーン種別ハイブリッド or ekonte 統一が候補

## 生成スクリプト(git 側 `60-apps/ai-gftd-project-mangaka/lg/scripts/`)
`compare_openrouter.py`(構図生成・style/action), `score_composition.py`(盲検採点),
`render_style_pages.py`(フルページ合成), `compose_blueprint.py`(depth 構図設計図),
`modal_render_panels.py`(Method4 = ControlNet-depth + SHOT_STRENGTH)

Pattern: ADR-2605302300(DataLad + git-annex + IPFS pin)。
