# 🧬 整理版 - 論文図表生成システム

**Molecular Psychiatry論文「Population-specific genetic heterogeneity of intelligence」用の高品質図表生成システム**

## 📁 プロジェクト構造

```
_posts/research/gene/
├── 📊 figures/                    # 図表生成スクリプト
│   ├── figure1/                   # Manhattan & QQ plots
│   │   ├── generate_figure1.py
│   │   └── README.md
│   ├── figure2/                   # 集団間比較解析  
│   │   ├── generate_figure2.py
│   │   └── README.md
│   ├── figure4/                   # 細胞型濃縮解析
│   │   ├── generate_figure4.py
│   │   └── README.md
│   ├── figure5/                   # ポリジェニックスコア
│   │   ├── generate_figure5.py
│   │   └── README.md
│   └── supplementary/             # 補足図表
│       ├── generate_supplementary.py
│       └── README.md
├── 📋 tables/                     # テーブル生成
│   └── table1/                    # トップ変異テーブル
│       ├── generate_table1.py
│       └── README.md
├── ⚙️ config/                     # 設定ファイル
│   ├── colors.py                  # 色設定
│   └── styles.py                  # スタイル設定
├── 🛠️ utils/                      # ユーティリティ
│   ├── data_loader.py             # データ読み込み
│   ├── statistics.py              # 統計計算
│   └── plotting.py                # プロット共通関数
├── 📁 output/                     # 出力ディレクトリ
├── 🚀 run_all_figures.py          # 統合実行スクリプト
├── 📖 article.md                  # 完成論文
├── 📊 gwas-data.csv              # GWASデータ
└── 📚 README_organized_figures.md # このファイル
```

## 🚀 クイックスタート

### 1. 環境セットアップ

```bash
# 仮想環境をアクティベート（既に作成済み）
source figure_env/bin/activate

# または新規作成
python3 -m venv figure_env
source figure_env/bin/activate
pip install -r requirements.txt
```

### 2. 全図表を一括生成

```bash
# 全ての図表を生成
python run_all_figures.py

# 特定の図のみ生成
python run_all_figures.py --figures 1,2

# カスタム出力ディレクトリ
python run_all_figures.py --output ./publication_figures
```

### 3. 個別図表生成

```bash
# Figure 1のみ
cd figures/figure1
python generate_figure1.py

# Figure 2のみ  
cd figures/figure2
python generate_figure2.py
```

## 📊 生成される図表一覧

| 図表 | タイトル | 内容 | ファイル |
|------|----------|------|----------|
| **Figure 1** | Manhattan & QQ Plot | GWAS基本可視化、genomic inflation | `Figure1_Manhattan_QQ.png/pdf` |
| **Figure 2** | Cross-Population Comparison | 集団間P値相関、効果量、異質性 | `Figure2_Cross_Population.png/pdf` |
| **Figure 4** | Cell-Type Enrichment | 脳細胞型特異的濃縮解析 | `Figure4_CellType_Enrichment.png/pdf` |
| **Figure 5** | Polygenic Score Analysis | PGS転用性、ROC曲線、R²値 | `Figure5_Polygenic_Score.png/pdf` |
| **Supp Fig** | Pathway Enrichment | 遺伝子セット・パスウェイ解析 | `Supplementary_Figure_Pathways.png/pdf` |
| **Table 1** | Top Variants | トップ変異のサマリーテーブル | `Table1_Top_Variants.csv` |

## ⚙️ 設定カスタマイズ

### 色設定の変更

```python
# config/colors.py を編集
COLORS = {
    'japanese': '#2E86AB',      # 日本人データ
    'european': '#A23B72',      # ヨーロッパ人データ  
    'convergent': '#F18F01',    # 共通要素
    'significant': '#C73E1D'    # 有意な結果
}
```

### スタイル設定の変更

```python
# config/styles.py を編集
FIGURE_STYLE = {
    'font.size': 12,           # フォントサイズ
    'figure.dpi': 300,         # 解像度
    'font.family': 'Arial'     # フォント
}
```

## 🛠️ 高度な使用方法

### カスタムデータでの実行

```python
from utils.data_loader import GWASDataLoader
from figures.figure1.generate_figure1 import Figure1Generator

# カスタムデータファイル
generator = Figure1Generator('my_custom_data.csv')
generator.generate_figure1()
```

### 個別パネルの生成

```python
from figures.figure2.generate_figure2 import Figure2Generator

generator = Figure2Generator()
generator.load_data()

# Panel Aのみ生成
fig, ax = plt.subplots()
generator.generate_pvalue_correlation(ax)
```

### 統計解析のカスタマイズ

```python
from utils.statistics import calculate_heterogeneity

# 異質性の計算
het_result = calculate_heterogeneity(
    beta1=0.5, se1=0.1,
    beta2=0.3, se2=0.12
)
print(f"I² = {het_result['I2']:.1f}%")
```

## 📋 コマンドライン オプション

```bash
# ヘルプ表示
python run_all_figures.py --help

# 要件チェックのみ
python run_all_figures.py --check-only

# 特定の図とテーブル
python run_all_figures.py --figures 1,2 --tables 1

# カスタム出力
python run_all_figures.py --output ./custom_output
```

## 🔍 品質管理

### 自動チェック項目

- [ ] **データ品質**: 欠損値、外れ値の処理
- [ ] **統計的妥当性**: λ値、多重検定補正
- [ ] **視覚的品質**: 解像度、フォント、色使い
- [ ] **再現性**: 同一結果の再現確認

### 出力品質基準

| 項目 | 基準 | チェック方法 |
|------|------|-------------|
| 解像度 | ≥300 DPI | ファイルプロパティ確認 |
| ファイルサイズ | PNG: 2-10MB | ls -lh で確認 |
| フォント | Arial, 12pt | 図表の視覚確認 |
| 色使い | カラーブラインド対応 | 色覚シミュレータで確認 |

## 🧪 テスト・デバッグ

### デバッグモード実行

```python
import logging
logging.basicConfig(level=logging.DEBUG)

# エラー詳細の出力
try:
    generator.generate_figure1()
except Exception as e:
    import traceback
    traceback.print_exc()
```

### パフォーマンス監視

```python
import time
import psutil

start_time = time.time()
# 図表生成
end_time = time.time()

print(f"実行時間: {end_time - start_time:.1f}秒")
print(f"メモリ使用量: {psutil.virtual_memory().percent}%")
```

## 📖 論文投稿チェックリスト

### 図表品質
- [ ] 全図表が300 DPI以上
- [ ] PDF形式で保存済み
- [ ] フォントが埋め込み済み
- [ ] 色使いがカラーブラインド対応

### 内容確認
- [ ] 統計的有意性の表示が正確
- [ ] 図表番号と説明文が一致
- [ ] データの解釈が論文本文と整合

### Molecular Psychiatry誌 要件
- [ ] 図サイズ: 最大170mm幅
- [ ] 解像度: 300-600 DPI
- [ ] ファイル形式: PDF推奨
- [ ] フォント: Arial または Helvetica

## 🚨 トラブルシューティング

### よくある問題と解決法

1. **ModuleNotFoundError**
   ```bash
   pip install pandas matplotlib seaborn numpy scipy
   ```

2. **メモリ不足エラー**
   ```python
   # データサンプリング
   df = df.sample(n=50000)
   ```

3. **フォント関連エラー**
   ```bash
   # フォントキャッシュクリア
   rm -rf ~/.matplotlib/fontlist-*.json
   ```

4. **Permission denied エラー**
   ```bash
   chmod +x run_all_figures.py
   ```

### ログファイルの確認

```bash
# 実行ログの保存
python run_all_figures.py 2>&1 | tee generation.log

# エラーのみ確認
grep -i error generation.log
```

## 📊 システム要件

### 推奨環境
- **OS**: macOS 10.15+, Linux, Windows 10+
- **Python**: 3.8+
- **RAM**: 8GB以上
- **ストレージ**: 2GB以上の空き容量

### 依存パッケージ
```
pandas>=1.5.0
numpy>=1.20.0
matplotlib>=3.5.0
seaborn>=0.11.0
scipy>=1.8.0
scikit-learn>=1.0.0
```

## 🔗 関連リソース

- **論文本文**: `article.md`
- **オリジナルデータ**: `gwas-data.csv`
- **研究戦略**: `strategy/`
- **依存パッケージ**: `requirements.txt`

## 📞 サポート

### 技術的な問題
1. 各figureフォルダのREADMEを確認
2. ログファイルでエラー詳細を確認
3. GitHubリポジトリのIssueを検索

### 研究内容に関する質問
- 論文著者に直接問い合わせ
- `article.md`の方法論セクションを参照

---

**🎉 高品質な論文図表で研究成果を最大化しましょう！** 