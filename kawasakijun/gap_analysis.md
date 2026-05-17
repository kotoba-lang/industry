# Gap Analysis — 現状 vs 理想状態

`profile.jsonld` / `activities.jsonld` / `financial_state.jsonld` /
`litigation_state.jsonld` / `photos_timeline.jsonld` を統合して、
**現状ベクトル (X_now)** と **理想ベクトル (X_star)** の差分を τ × domain 行列で可視化。

差分が大きい順に `reverse_topo_pregel.py` に渡し、依存関係 DAG の
**逆トポロジカルソート** で実行順を決定する。

## 1. 現状ベクトル (X_now, 2026-05 時点)

| domain | 状態 | 詳細 |
|---|---|---|
| **physics** | 進行中 | Verlinde + BlackHole + GenerativeStructure (Lean4 ADR-0001/-0002 公開済) |
| **fiction** | 中盤 | Ghost Hacker Vol.1 Ch.2 KDP / 全8巻計画 |
| **research** | 並走 | Spirit-in-Physics / Wheeler-DeWitt 検証 / High-IQ GWAS Phase 2 |
| **infra** | 厚い | Gftd Japan (ISO 27001 / SSS) + gftd.ai DID + 9 グループ会社 |
| **social** | 進行中 + 重荷 | LingLing 訴訟継続 / 鹿大紛争 / 借入 1.67 億 + 貸付 1.34 億 |
| **health** | 月次通院 | LaVie 渋谷こころのクリニック / 2024 撮影低調 |
| **relations** | 損失後 | 離婚済 / 娘のどか / 再婚希望 + 子 2-3 人希望 |
| **spirit** | 確立 | 大宗寺 僧侶 / 01 Zen / 比叡山出家 (2018) |
| **did** | 構築中 | gftd.ai PDS (did:web:ml1nb0nd) |

## 2. 理想ベクトル (X_star)

| domain | ゴール (X_star) | 達成判定 |
|---|---|---|
| physics | 統合定理 / arXiv / Lean4 で機械検証完了 | 論文1本 + Mathlib PR |
| fiction | Ghost Hacker 8 巻完結 → 映像化 | KDP 8 巻完了 |
| research | 高IQ GWAS / Spirit-in-Physics 査読論文 | 査読通過 |
| infra | Gftd IPO or 持続可能財団 / DID 本番稼働 | 上場 or 認定取得 |
| social | **全係争解決 / 借入完済 / COMMONS 貸付回収** | 残債務ゼロ |
| health | 心身定常 (睡眠7h+, 通院隔月へ) | 医師確認 |
| relations | **再婚パートナー + 子 3-4 人合計** | 法的入籍 + 出産 |
| spirit | 01 Zen 稼働 / 非分離コード公開 | OSS 公開 |
| did | DID/PDS 1,000 アカウント運用 | アクティブ DID 数 |

## 3. ギャップ最大の 5 領域 (優先度順)

| # | ギャップ | 推定難易度 | τ-スケール |
|---|---|---|---|
| 1 | **借入 1.67 億の返済 + COMMONS 1.34 億の回収** | 高 (5–10 年) | year/decade |
| 2 | **LingLing 訴訟の確定的勝訴/和解** | 中 (1–3 年) | year |
| 3 | **再婚 + 子供 +2–3 人** | 高 (3–10 年、相手依存) | year/decade |
| 4 | Ghost Hacker 残 6–7 巻 + 映像化 | 中 (3–5 年) | year |
| 5 | 物理統合理論 + 査読 | 中 (2–5 年) | year |

## 4. 依存関係 DAG (主要エッジのみ)

`→` は「左を達成するには右が前提」(prerequisites)。逆トポロジカルソートでは
**右側 (依存される側)** から処理。

```
ipo                       → 内部統制 + 監査法人選定 + 黒字化 3 期 + 訴訟解決
再婚                       → 出会いの場 + 健康 + 経済安定 + 時間的余裕
時間的余裕                  → 訴訟負荷低減 + 借入返済原資確保
借入返済原資                 → Gftd 月次黒字 + COMMONS 貸付回収
COMMONS 貸付回収            → COMMONS 宿泊業の営業 CF + LingLing 解決
LingLing 解決               → 経理データ整合性証明 + アイシステム送金履歴開示
経理データ整合性証明           → 2021年 預かり資金移動 全件突合 (MoneyForward × 220415 経費確認)
ghost_hacker_完結           → 残巻シナリオ (3,4,5,...) + 作画パイプライン
作画パイプライン               → Spirit-in-Physics 実験パイプライン流用 (Kuzu/Inngest)
物理統合論文                  → ADR-0003 (5層整合性) + Wheeler-DeWitt 検証 + arXiv 提出
ADR-0003                    → Lean4 mathlib v4.25 互換 + GenerativeStructure 完成
01_zen_稼働                  → post-religion ドキュメント + blockchain 実装 + 大宗寺承認
子供 +2–3 人                 → 再婚 + 健康 + のどかとの関係安定
```

## 5. 逆トポロジカルソート結果 (実行順)

末端 (依存先) から実行する順序:

```
01. アイシステム送金履歴の完全開示 (税理士・経理から取得)
02. 2021年 預かり資金移動 全件突合 (MoneyForward × COMMONS 220415)
03. Lean4 mathlib v4.25 互換性検証
04. 経理データ整合性証明書 作成 (ZeLo + 水鳥 連名)
05. ADR-0003 (5層整合性) + GenerativeStructure 完成
06. LingLing 準備書面 完成 + 期日対応
07. Wheeler-DeWitt 検証 → arXiv 投稿
08. Gftd Japan 月次キャッシュフロー黒字化 (営業案件積み上げ)
09. Ghost Hacker 残巻シナリオ確定 (3–8 巻)
10. COMMONS 宿泊業 営業 CF 達成
11. 健康指標安定化 (通院隔月化)
12. 訴訟負荷の弁護団移譲 + 自己時間確保
13. 出会いの場形成 (国際出張 / コミュニティ)
14. 借入返済加速 + COMMONS 貸付段階的回収
15. 再婚
16. 子供 +2–3 人
17. 内部統制 + 監査法人選定
18. Gftd IPO or 財団化
19. Ghost Hacker 映像化
20. 01 Zen OSS 公開 + 非分離コード共有
```

## 6. Pregel 実行設計

`reverse_topo_pregel.py` (新規):

- **vertex** = 上記 20 ノード (各 τ-domain にタグ付け)
- **edge** = 上記 DAG のエッジ
- **super-step** = 逆トポロジカル順の 1 ステップ
- **state per vertex** = `{completion: [0,1], W_contribution: float, blocked_by: list[node_id]}`
- **message** = 「私は完了した → あなたのブロック解除」シグナル
- **halt** = 全ノード `completion ≥ θ` または budget (時間/資金/注意) 枯渇
- **dynamic re-routing**: 解除されない (相手依存) ノードは `pending`、
  代替経路 (例: 再婚 → 養子・代理出産・パートナーシップ非入籍) に切替可

## 7. 不確実性と外部依存

| ノード | 外部依存 | 代替経路 |
|---|---|---|
| 再婚 | 相手 | 事実婚 / パートナーシップ / 養子 |
| LingLing 勝訴 | 裁判所判断 | 和解金抑制 + 控訴戦略 |
| Gftd IPO | 市場環境 | 持続可能財団化 |
| 子供 +2–3 人 | パートナー + 健康 | 養子 / 教育投資による次世代育成 |
| 借入完済 | 売上達成 | 借換 + 部分早期返済 |

## 8. Pregel 出力の使い方

```bash
python kawasakijun/reverse_topo_pregel.py --budget-years 10 --report markdown
```

出力:
- 各 super-step での実行ノード集合
- 完了見込み τ-curve (W(τ) の推移)
- ボトルネックノードと並走可能ノードの分離
- 月次レビュー用 KPI (各 τ-domain の進捗率)
