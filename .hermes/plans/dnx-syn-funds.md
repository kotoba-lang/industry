# DNX Capital / SYN Ventures fund 関係 調査記録 + kotoba.cloud 化 (2026-09-15/16)

## 1. 調査結論 (実 fetch 済み、2026-09-15/16 HTTP 200)

### 共同投資クラスタ (「過去の fund で一緒」の本体)
| 企業 | ラウンド | DNX 側 | In-Q-Tel/SYN 側 | 出口 |
|---|---|---|---|---|
| Cylance | 2015-07 $42M (DFJ/KKR/Dell/CapOne/TenEleven) → 2015-09 IQT → 2016-06 Series D $100M (Blackstone/Insight) | DNX US fund portfolio "Cylance — Exited" (dnx.vc archive 2023) | IQT portfolio 238 tiles 静的リストに含まず (iqt.org 2019 archive) | BlackBerry $1.4B 2019-02 (Wikipedia) |
| JASK | — | "JASK — Exited" DNX US portfolio | 静的 portion 未収録 (layer: not-verified) | 2021 Splunk 買収 (未実査) |
| Mitiga | — | "Mitiga — Active" DNX US portfolio | 同上 | — |
| Fyde | — | "Fyde — Exited" DNX US portfolio | 同上 | Novoserve (未実査) |
| ICEYE | Series B 2018-05-24 $34M、lead True Ventures、参加: Draper Nexus, Draper Associates, Seraphim, Space Angels + OTB, Tesi, Draper Esprit, Promus (PR 本文一次ソース) | "Iceye — Active" DNX US portfolio + PR 名義 | 同上 | — |

- DNX↔IQT の同一ラウンド併記が確認できたのは Cylance クラスタ (DNX portfolio + IQT wiki/2015-09、Wikipedia: In-Q-Tel 2015-09 投資は実 fetch)。
- In-Q-Tel は iqt.org で存続、2019 portfolio 静的スナップショット = 238 タイル。JASK/Mitiga/Fyde/ICEYE はその静的 portion に無く、lazy-load 後方ページのため未確認として記録 (iqt.org/jask/ 等は 404)。
- SYN Ventures (syncapital.vc / synventures.com): 2021 創業、$900M AUM、focus リストは IQT と同一。In-Q-Tel のリブランド/系統かは secondary-reported として保留。

## 2. kotoba.cloud 公開データ (実装済・live)
- ページ: https://kotoba.cloud/vc/ (200, 26,977B) / /ja/vc/ → 301 → /vc/ (locale negotiation 正常)
- 機械可読: /vc-data/vc/index.json (funds 3 / companies 5 / claims 10 / clusters 2、IPLD head baguqeerahlaob4oudj…)、/vc-data/vc/ontology.jsonld、/vc-data/vc/relations.json
- コード: assets/vc-catalog-src/{funds,companies,relations}.json (正本) → scripts/build-vc-catalog.mjs → assets/vc-catalog/ → src/app_kotoba_cloud/{vc_site.cljk,site.cljk} 配線。test/vc-catalog.mjs (npm run test:vc-catalog)。
- PR #222 MERGED (2026-09-15T16:17:10Z)。deploy: wrangler Version a269dac7-f35d-4866-8063-a77238ac4249。
- system dynamics (loop-1 exit-recycling、loop-2 cross-border diffusion、stock-1 non-capital moat) と strategy st-1..st-3 も index.json に収録。

## 3. bot profile: vc-fund-ops
- profile: ~/.hermes/profiles/vc-fund-ops (donor tm-vbts)。SOUL.md + scripts/vc_fund_ops_evidence.py (live index / repo drift / 全 sourceUrl status / surface status、REFUSED banner)。
- cron: 450adcdeeed8 "50 5 * * *" (JST 05:50)、model z-ai/glm-5.3-flash/openrouter、max_tokens 16384、toolsets terminal/file/web。
- worktree: ~/.gftd/worktrees/vc-fund-ops (kotoba-lang remote 名に注意)。
- ledger: scripts/hermes-cron-jobs/hermes-cron-jobs.json に登録、superproject commit 75301f95cab。
- gateway tick 46 profiles 確認済 (kickstart 01:27 JST)。

## 4. 次の lever (bot に渡す種)
- JASK/Mitiga/Fyde の IQT 側一次証跡 (iqt.org 後方ページ JS 取得、または IQT archive PDF)。
- ICEYE の IQT 関係 (2018 Series B に In-Q-Tel なしか — PR 名簿には無い。後続 round/C での参加有無を実査)。
- SYN Ventures = In-Q-Tel 系統説の一次証跡 (採用ページ/archived diff)。
- JASK→Splunk、Fyde→Novoserve 買収の実 fetch。
- fund の追加: Draper Esprit / Seraphim / SBI / Glarus (Mitiga lead 説) など同一クラスタ周辺。
