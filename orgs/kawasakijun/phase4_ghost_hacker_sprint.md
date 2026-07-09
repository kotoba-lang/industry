# Phase 4.A — Ghost Hacker 集中執筆スプリント (残 6 巻 / 12 ヶ月)

CPM の新ボトルネックは **`09_ghosthacker_scenarios` (2y)**。
これを **1y に圧縮** すると baseline 7.00y → 6.00y、映像化前倒しで連鎖効果。

## 既存資産

| 資産 | 状態 |
|---|---|
| Vol.1 全章 | KDP 出版済 (FXL コミック) |
| Vol.2-5 スクリプト | 既に書き上げ (`260208-spirit-in-physics` リポに json-ld) |
| 全 8 巻構造 | `story-bible/` で正準化済 |
| エピソード 1-12 | OWL/SHACL TTL + emotional outline 完備 |
| キャラクター | tamaki / Nei-Chan / Kaede / Hibiki / Aoi / Ren / Akito / Elias / Logos 確立 |
| 設定 | Tokyo (水都) / Ghost Hacking / Tree of Life (Etzhayyim) |
| 画パイプライン | `260208-spirit-in-physics/resources/` per-panel cine PNG |
| 出版パイプライン | JSON-LD → EPUB build (Vol.2-5) / FXL Kindle Comic / 4-panel FXL |
| Generator | Danbooru-tag prompts (Ch.2 で確立) |

**結論**: Vol.2-5 はスクリプト済、Vol.6-8 をシナリオ → 作画 → 出版に流すのが残作業。

## スプリント設計

### 巻別タイムライン (12 ヶ月)

| Month | Volume | Phase | Owner |
|---|---|---|---|
| 2026-06 | Vol.2 | 既存スクリプト → 作画 → KDP | 河崎 |
| 2026-07 | Vol.3 | 既存スクリプト → 作画 → KDP | 河崎 |
| 2026-08 | Vol.4 | 既存スクリプト → 作画 → KDP | 河崎 |
| 2026-09 | Vol.5 | 既存スクリプト → 作画 → KDP | 河崎 |
| 2026-10 | Vol.6 | **新規シナリオ + 作画 + KDP** | 河崎 |
| 2026-11 | Vol.6 ↺ | (作画継続) | 河崎 |
| 2026-12 | Vol.7 | **新規シナリオ + 作画 + KDP** | 河崎 |
| 2027-01 | Vol.7 ↺ | のどか受験本番 — 余裕優先 | 河崎 |
| 2027-02 | Vol.8 | **新規シナリオ + 完結巻** | 河崎 |
| 2027-03 | Vol.8 ↺ | 作画ピーク | 河崎 |
| 2027-04 | Vol.8 完結 | KDP 出版 + 全巻ボックスセット | 河崎 |
| 2027-05 | 映像化交渉 | 制作委員会向けピッチ | 河崎 |

### 日次スプリント (Vol.6-8 新規シナリオ期)

```
06:00-07:00 (60min)   執筆 — シナリオ / 章立て
07:00-08:00            身支度・娘との時間
08:00-12:00            Gftd 業務 (主に lawfirm + crypto-victim 立ち上げ)
12:00-13:00            昼休 (運動 / 通院)
13:00-18:00            Gftd 業務 / Etzhayyim
18:00-19:00            娘との時間 / 食事
19:00-21:00 (120min)  作画 — animeka pipeline へ投入 (RunPod Serverless)
21:00-22:00            読書 / 妻探し (会食含む)
22:00                  睡眠
```

**週末**:
- 土曜: シナリオ集中執筆 (4-6h)
- 日曜: 娘との時間 + 海外出張準備

### 週次マイルストーン

| 週 | DOD |
|---|---|
| W1 | Vol.X 章立て完成 |
| W2 | シナリオ前半 5 章完成 |
| W3 | シナリオ後半 5 章完成 |
| W4 | 作画パイプライン投入 + 翌巻準備 |

## 作画パイプライン (Vol.2-8 共通)

```
1. JSON-LD scene definition (story-bible)
   ↓
2. Danbooru-tag prompt 自動生成 (ChatGPT/Claude)
   ↓
3. animeka.gftd.ai (RunPod Serverless v9si0sflsm0gh0)
   - ComfyUI + Animagine XL 4.0 (network volume 43k3uq9ldn)
   - cold start 60-180s, 1 image ~30s
   ↓
4. PDS (atproto.gftd.ai) に panel ごとに upload
   ↓
5. EPUB build (260208-spirit-in-physics の既存 pipeline)
   ↓
6. KDP 投入 (FXL Kindle Comic)
```

**Vol.6-8 で必要な拡張**:
- 新キャラクター画 (シナリオ未確定キャラ)
- クライマックスシーン (multi-panel coherence)
- Etzhayyim (Tree of Life) ビジュアル展開

## ペース管理 KPI

| KPI | 計測 | 閾値 |
|---|---|---|
| 巻完成率 | KDP 投入完了月数 | 1巻/月以上 (Vol.2-5期) |
| 章執筆速度 | シナリオ章/週 | 5+ (Vol.6-8期) |
| 作画 panel/日 | animeka 完成 panel 数 | 10+ |
| 河崎 daily 執筆 60min | Calendar block | 5/7 days |
| 娘との時間 | 週次集計 | ≥ 8h/week |

## 並走可能タスク (Slack 9-12y のもの)

- 物理研究 (ADR-0003 / Wheeler-DeWitt) — 週末 1-2h で進められる
- Etzhayyim 9 領域継続 — git commit ベースで進む
- Magatama SDK 公開 — 既存資産活用

## 短縮ノード case

```python
"09_ghosthacker_scenarios": Goal(
    "09_ghosthacker_scenarios",
    "Ghost Hacker 残 6 巻完結 (Vol.2-5 = 既存スクリプト消化, Vol.6-8 = 新規執筆)",
    "year", "fiction", 1.0, 0.20,  # 2.0y → 1.0y
)
```

CPM 上の効果: 7.00y → **6.00y** (Vol.8 完結後すぐ映像化交渉へ)

## 映像化連鎖

Vol.8 完結 (2027-04) すぐにアクション:

1. **制作委員会組成** (5月)
2. **Pitch deck**: animeka pipeline で生成済の panel 群 + 8 巻完結シナリオ
3. **配信プラットフォーム**: 既存の `60-apps/ai-gftd-project-anime` 経由
4. **Crowdfunding**: 既存読者 (Wattpad / KDP) を seed に

→ `19_ghosthacker_film` ノードの est_years を 5y → 3y に圧縮可能 (シナリオ完成済の利点)

## 全体効果

| Phase | Ghost Hacker | 映像化 | Baseline |
|---|---:|---:|---:|
| 3.0 | 2.0y | 5.0y | 7.00y |
| **4.A** | **1.0y** | **3.0y** | **4.00y** |

**4 年で全 24+ ノード完了見込み**。
