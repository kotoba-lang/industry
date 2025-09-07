# 論文図表生成システム

**Molecular Psychiatry論文「Population-specific genetic heterogeneity of intelligence」用の高品質図表生成システム**

## 概要

本システムは、日本人高IQ集団のGWAS研究論文用に、出版レベルの図表を自動生成します。実際のGWASデータ（`gwas-data.csv`）に基づいて、以下の図表を生成します：

### 生成される図表

1. **Figure 1**: Manhattan plot & QQ plot
2. **Figure 2**: 集団間比較解析
3. **Figure 4**: 細胞型特異的濃縮解析
4. **Figure 5**: ポリジェニックスコア解析
5. **Supplementary Figure**: パスウェイ濃縮解析
6. **Table 1**: トップ変異のサマリーテーブル

## クイックスタート

### 1. 環境セットアップ

```bash
# 必要なパッケージをインストール
pip install -r requirements.txt
```

### 2. 図表生成実行

```bash
# 全ての図表を一括生成
python run_analysis.py
```

### 3. 生成されるファイル

```
📊 Figure1_Manhattan_QQ.png/pdf         # Manhattan & QQ plots
📊 Figure2_Cross_Population.png/pdf     # 集団間比較
🧬 Figure4_CellType_Enrichment.png/pdf  # 細胞型濃縮
📈 Figure5_Polygenic_Score.png/pdf      # ポリジェニックスコア
🛤️  Supplementary_Figure_Pathways.png/pdf # パスウェイ解析
📋 Table1_Top_Variants.csv              # トップ変異テーブル
📝 Figure_Legends.md                    # 図表の説明文
```

## ファイル構成

```
_posts/research/gene/
├── run_analysis.py              # メイン実行スクリプト
├── generate_figures.py          # 基本図表生成（Figure 1, 2）
├── generate_advanced_figures.py # 高度解析図表（Figure 4, 5, Supp）
├── requirements.txt             # 必要パッケージリスト
├── gwas-data.csv               # 実際のGWASデータ
├── article.md                  # 完成論文
└── README_figures.md           # このファイル
```

## 個別図表生成

### 基本図表（Figure 1, 2）

```python
from generate_figures import GWASFigureGenerator

generator = GWASFigureGenerator('gwas-data.csv')

# Manhattan & QQ plots
generator.generate_manhattan_qq_plot()

# 集団間比較
generator.generate_cross_population_comparison()
```

### 高度解析図表（Figure 4, 5）

```python
from generate_advanced_figures import AdvancedFigureGenerator

generator = AdvancedFigureGenerator()

# 細胞型濃縮解析
generator.generate_celltype_enrichment()

# ポリジェニックスコア解析
generator.generate_polygenic_score_analysis()

# パスウェイ濃縮解析
generator.generate_pathway_enrichment()
```

## カスタマイズ

### 色設定の変更

```python
# generate_figures.py または generate_advanced_figures.py で色を変更
colors = {
    'japanese': '#2E86AB',     # 日本人データの色
    'european': '#A23B72',     # ヨーロッパ人データの色
    'convergent': '#F18F01',   # 共通要素の色
    'significant': '#C73E1D'   # 有意な結果の色
}
```

### 図のサイズ・解像度調整

```python
# matplotlib設定
plt.rcParams.update({
    'figure.dpi': 300,        # 解像度
    'savefig.dpi': 300,      # 保存時解像度
    'font.size': 12,         # フォントサイズ
    'font.family': 'Arial'   # フォント
})
```

## データ要件

### 入力データ形式（gwas-data.csv）

```csv
CHR,SNP,BP,A1,A2,P,BETA,SE,Z
1,rs12345,1234567,A,G,0.001,0.25,0.08,3.125
2,rs67890,2345678,T,C,0.002,-0.18,0.07,-2.571
...
```

**必須カラム:**
- `CHR`: 染色体番号
- `SNP`: 変異ID
- `BP`: 塩基位置
- `P`: P値
- `BETA`: 効果量
- `SE`: 標準誤差

## トラブルシューティング

### よくある問題

1. **パッケージが見つからない**
   ```bash
   pip install --upgrade pip
   pip install -r requirements.txt
   ```

2. **メモリ不足エラー**
   ```python
   # データサイズを制限
   df = df.sample(n=100000)  # 10万変異に制限
   ```

3. **フォントエラー**
   ```bash
   # システムフォントをリフレッシュ
   rm -rf ~/.matplotlib/fontlist-*.json
   ```

### エラー対処

```python
# デバッグモード実行
import logging
logging.basicConfig(level=logging.DEBUG)

# 個別図表をテスト
try:
    generator.generate_manhattan_qq_plot()
except Exception as e:
    print(f"Error: {e}")
    import traceback
    traceback.print_exc()
```

## 出力品質設定

### 論文投稿用（高解像度）

```python
# 高解像度設定
plt.rcParams.update({
    'figure.dpi': 600,
    'savefig.dpi': 600,
    'savefig.format': 'pdf'  # ベクター形式
})
```

### プレゼンテーション用

```python
# 低解像度・大フォント設定
plt.rcParams.update({
    'figure.dpi': 150,
    'font.size': 16,
    'axes.titlesize': 18
})
```

## 統計的考慮事項

### 多重検定補正

```python
# Bonferroni補正
alpha_corrected = 0.05 / n_tests

# FDR補正
from scipy.stats import multipletests
_, p_corrected, _, _ = multipletests(p_values, method='fdr_bh')
```

### 効果量の解釈

```python
# オッズ比計算
OR = np.exp(beta)
CI_lower = np.exp(beta - 1.96 * SE)
CI_upper = np.exp(beta + 1.96 * SE)
```

## 論文投稿チェックリスト

- [ ] 全図表が300 DPI以上で生成されている
- [ ] 図の説明文（Figure legends）が適切
- [ ] 統計的有意性の閾値が正しく表示されている
- [ ] 色使いがカラーブラインド対応
- [ ] フォントサイズが読みやすい（12pt以上）
- [ ] ファイル形式が投稿要件に適合（PDF推奨）

## ライセンス

本研究用スクリプト - 学術利用可

## 連絡先

技術的質問: AI Research Assistant
研究内容: 論文著者

---

**🎉 Molecular Psychiatry投稿用図表の準備完了！** 