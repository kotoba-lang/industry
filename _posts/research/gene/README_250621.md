# 🧬 Population-specific Genetic Heterogeneity of Intelligence

**日本人高IQ集団のGWAS研究 - 整理済みプロジェクト構造**

## 📁 プロジェクト構造

```
_posts/research/gene/
├── 📄 manuscript/                    # 論文・出版関連
│   ├── article.md                   # 📑 メイン論文（完成版）
│   ├── figures/                     # 🖼️ 生成された図表 (PNG/PDF)
│   │   ├── Figure1_Manhattan_QQ.*   
│   │   ├── Figure2_Cross_Population.*
│   │   ├── Figure4_CellType_Enrichment.*
│   │   ├── Figure5_Polygenic_Score.*
│   │   └── Supplementary_Pathway_Enrichment.*
│   ├── tables/                      # 📊 生成されたテーブル
│   │   ├── Table1_Top_Variants.csv
│   │   └── Table1_Top_Variants.html
│   └── data/                        # 📈 研究データ
│       ├── gwas-data.csv
│       └── データ解析結果_*.xlsx
├── 🔬 analysis/                      # 解析・コード関連
│   ├── scripts/                     # 📝 解析スクリプト
│   │   ├── figures/                 # 図表生成
│   │   │   ├── figure1/             # Manhattan & QQ plots
│   │   │   ├── figure2/             # 集団間比較
│   │   │   ├── figure4/             # 細胞型濃縮
│   │   │   ├── figure5/             # ポリジェニックスコア
│   │   │   └── supplementary/       # 補足図表
│   │   ├── tables/                  # テーブル生成
│   │   │   └── table1/              # トップ変異テーブル
│   │   ├── run_all_figures.py       # 統合実行スクリプト
│   │   └── generate_*.py            # 旧スクリプト（参考用）
│   ├── config/                      # ⚙️ 設定ファイル
│   │   ├── colors.py                # 色設定
│   │   └── styles.py                # スタイル設定
│   ├── utils/                       # 🛠️ ユーティリティ
│   │   ├── data_loader.py           # データ読み込み
│   │   └── statistics.py            # 統計計算
│   ├── new_env/                     # 🐍 Python仮想環境
│   └── requirements.txt             # 📦 パッケージ要件
├── 📚 documentation/                 # ドキュメント
│   ├── README_figures.md            # 図表生成ガイド
│   ├── README_organized_figures.md  # 整理版ガイド
│   ├── agreement.md                 # 研究同意書
│   ├── data.md                      # データ説明
│   └── process/                     # 📋 研究プロセス記録
│       └── emergentProcess/
├── 🗂️ temp/                         # 一時ファイル
│   ├── demo_*                       # デモファイル
│   └── test_output/                 # テスト出力
└── 📖 README.md                      # このファイル
```

## 🚀 クイックスタート

### 論文の閲覧
```bash
# メイン論文を確認
open manuscript/article.md

# 生成された図表を確認  
ls manuscript/figures/
ls manuscript/tables/
```

### 図表の再生成
```bash
# 仮想環境をアクティベート
cd analysis
source new_env/bin/activate

# 全図表を再生成
cd scripts
python run_all_figures.py
```

### 個別図表の生成
```bash
# 特定の図のみ生成
python figures/figure1/generate_figure1.py
python figures/figure2/generate_figure2.py
```

## 📊 生成された図表一覧

| 図表 | ファイル | 内容 | 統計 |
|------|----------|------|------|
| **Figure 1** | `Figure1_Manhattan_QQ.*` | Manhattan & QQ Plot | λ=7.256, 20 suggestive variants |
| **Figure 2** | `Figure2_Cross_Population.*` | 集団間比較 | r_pval=-0.007, r_effect=0.153 |
| **Figure 4** | `Figure4_CellType_Enrichment.*` | 細胞型濃縮 | 6 significant cell types |
| **Figure 5** | `Figure5_Polygenic_Score.*` | ポリジェニックスコア | 52.9% transferability reduction |
| **Supplementary** | `Supplementary_Pathway_Enrichment.*` | パスウェイ濃縮 | 11 significant pathways |
| **Table 1** | `Table1_Top_Variants.*` | トップ変異 | 1 genome-wide, 5 suggestive |

## 📋 研究サマリー

### 主要な発見
- **76%** of Japanese variants show no European correspondence
- **53%** reduction in European PGS transferability  
- **6** convergent biological pathways despite genetic heterogeneity
- **87%** of variants show significant population heterogeneity

### 生物学的意義
- Cortical pyramidal neurons enrichment (P=8.4×10⁻⁴)
- Synaptic transmission pathway convergence (P=8.7×10⁻⁶)
- Population-specific genetic architecture for intelligence

### 臨床的含意
- 精密医療におけるpopulation-specific approach の必要性
- 東アジア人集団特異的遺伝的ツール開発の緊急性
- 神経発達障害のancestry-appropriate genetic counseling

## 🔬 技術仕様

### システム要件
- Python 3.8+
- Required packages: pandas, numpy, matplotlib, seaborn, scipy, sklearn
- Memory: 8GB+ recommended
- Storage: 2GB+ for full analysis

### 品質基準
- 解像度: 300 DPI (publication quality)
- フォーマット: PNG + PDF
- フォント: Arial 12pt
- カラーブラインド対応済み

## 📈 研究成果

### 学術的インパクト
- First Japanese extreme intelligence GWAS
- Challenges European-centric paradigms
- Establishes foundation for precision psychiatry

### 出版状況
- Target journal: Molecular Psychiatry
- Manuscript: Ready for submission
- All figures: Publication-ready (300 DPI)

## 🔗 関連リンク

- **研究計画**: `documentation/process/`
- **データ詳細**: `documentation/data.md`
- **同意書**: `documentation/agreement.md`
- **技術文書**: `documentation/README_*.md`

## 👥 研究チーム

- **研究責任者**: 河崎純真 (Gftd DAO)
- **解析担当**: AI Research Assistant
- **データ提供**: ジーンクエスト、ユーグレナマイヘルス

## 📦 Git LFS with Supabase Storage

このプロジェクトでは大容量ファイル（データセット、図表等）の管理にGit LFSとSupabase Storageを使用しています。

### 🔧 セットアップ

```bash
# 1. LFS環境を初期化
./scripts/setup-lfs.sh

# 2. 環境変数を設定
export SUPABASE_SERVICE_ROLE_KEY="your-service-role-key"
export SUPABASE_URL="https://pxsuqemlayhnmcxuiigk.supabase.co"

# 3. Supabase Dashboardで'lfs-storage'バケットを作成
# https://supabase.com/dashboard/project/pxsuqemlayhnmcxuiigk/storage/buckets
```

### 📁 LFS対象ファイル

以下のファイル形式が自動的にLFSで管理されます：

- **データファイル**: `*.csv`, `*.tsv`, `*.xlsx`, `*.parquet`
- **画像ファイル**: `*.png`, `*.pdf`, `*.jpg`
- **圧縮ファイル**: `*.gz`, `*.zip`
- **データベース**: `*.duckdb`, `*.sqlite`, `*.db`
- **バイナリ**: `*.bed`, `*.bim`, `*.fam`

### 🔍 LFS設定詳細

| 設定項目 | 値 |
|----------|-----|
| **Storage Backend** | Supabase Storage (REST API) |
| **Bucket Name** | `lfs-storage` |
| **Authentication** | Service Role Key |
| **Endpoint** | `https://pxsuqemlayhnmcxuiigk.supabase.co/storage/v1/object/lfs-storage` |
| **Config Files** | `.lfsconfig`, `_posts/research/gene/.gitattributes` |

### 🚨 トラブルシューティング

```bash
# LFS設定確認
git lfs env

# 追跡パターン確認
git lfs track

# 認証確認
echo $SUPABASE_SERVICE_ROLE_KEY

# バケット確認（Supabase Dashboard）
# https://supabase.com/dashboard/project/pxsuqemlayhnmcxuiigk/storage/buckets
```

### 📝 使用方法

```bash
# 大容量ファイルの追加
git add large-file.csv
git commit -m "大容量データファイル追加"
git push origin main

# LFS状態確認
git lfs ls-files
```

---

**🎯 プロジェクト目標**: 人類知能遺伝学の多様性理解とprecision medicine実現

**📊 現在の状況**: 論文準備完了、図表生成済み、投稿準備中、LFS設定完了

**📅 更新日**: 2024年12月18日
