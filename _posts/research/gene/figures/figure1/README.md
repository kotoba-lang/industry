# Figure 1: Manhattan Plot and QQ Plot

## 概要

Figure 1は、日本人高IQ集団のGWAS解析における基本的な可視化を提供します：

- **Manhattan Plot**: 全染色体にわたる-log₁₀(P値)の分布
- **QQ Plot**: 観測P値と期待P値の比較
- **Genomic Inflation Factor (λ)**: 集団層化の評価

## 生成されるファイル

- `Figure1_Manhattan_QQ.png` - 高解像度PNG画像
- `Figure1_Manhattan_QQ.pdf` - ベクター形式PDF

## 実行方法

### 個別実行
```bash
cd figures/figure1
python generate_figure1.py
```

### メインスクリプトから実行
```bash
python run_all_figures.py --figures 1
```

## パネル説明

### Panel A: Manhattan Plot
- **目的**: ゲノム全体での関連の可視化
- **表示内容**:
  - 各点 = 1変異のP値
  - 染色体別の色分け（偶数/奇数）
  - ゲノムワイド有意性線（P = 5×10⁻⁸）
  - 示唆的有意性線（P = 1×10⁻⁶）
  - トップ5変異のハイライト

### Panel B: QQ Plot  
- **目的**: 統計的検定の妥当性評価
- **表示内容**:
  - 観測P値 vs 期待P値
  - 対角線（帰無仮説下での期待）
  - Genomic inflation factor (λ)
  - 等比率軸での表示

## 統計的解釈

### Lambda (λ) 値の解釈
- **λ ≈ 1.0**: 理想的（集団層化なし）
- **1.0 < λ < 1.05**: 許容範囲
- **λ > 1.05**: 集団層化または暗号化の可能性

### 有意性閾値
- **ゲノムワイド有意**: P < 5×10⁻⁸
- **示唆的有意**: P < 1×10⁻⁶
- **名目有意**: P < 0.05

## カスタマイズ

### 色の変更
```python
# config/colors.py で設定
COLORS = {
    'chr_even': '#2E86AB',  # 偶数染色体
    'chr_odd': '#A23B72',   # 奇数染色体
    'significant': '#C73E1D' # 有意変異
}
```

### 有意性線の変更
```python
# config/styles.py で設定
SIGNIFICANCE_LINES = {
    'genome_wide': {'y': -7.3, 'color': 'red'},
    'suggestive': {'y': -6.0, 'color': 'orange'}
}
```

## 品質管理指標

### 生成時にチェックされる項目
- [ ] 総変異数
- [ ] 染色体カバレッジ
- [ ] P値範囲
- [ ] Genomic inflation factor
- [ ] 有意変異数

### 期待される出力品質
- 解像度: 300 DPI以上
- ファイル形式: PNG + PDF
- フォント: Arial, 12pt
- 図サイズ: 8×12インチ

## トラブルシューティング

### よくある問題

1. **データファイルが見つからない**
   - 自動的に合成データを生成
   - 警告メッセージを確認

2. **メモリ不足**
   ```python
   # データサイズを制限
   df = df.sample(n=100000)
   ```

3. **フォントエラー**
   ```bash
   # フォントキャッシュをクリア
   rm -rf ~/.matplotlib/fontlist-*.json
   ```

4. **Lambda値が異常**
   - データの品質を確認
   - 集団層化の可能性を検討

## 技術仕様

- **依存関係**: matplotlib, numpy, pandas, scipy
- **メモリ使用量**: ~500MB（50万変異）
- **実行時間**: ~30秒（通常環境）
- **出力サイズ**: ~2-5MB（PNG）、~1-2MB（PDF） 