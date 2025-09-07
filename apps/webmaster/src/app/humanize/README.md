# Research-Based Humanization Algorithm
## 最新研究論文（2024-2025年）の成果を統合した革新的AI検出回避手法

### 📋 概要

本プロジェクトは、最新の学術研究成果を統合して、AI生成テキストの検出を回避するための革新的アルゴリズムを実装しています。5つの異なる攻撃手法を組み合わせることで、従来手法を大幅に上回る回避性能を実現しています。

### 🔬 参考研究論文

1. **Adversarial Paraphrasing (2025)** - T@1%F 87.88%削減
2. **GradEscape (2025, USENIX Security)** - 勾配ベース攻撃
3. **SilverSpeak (2024)** - ホモグリフ攻撃
4. **Navigating the Shadows (2024, ACL)** - 12種類摂動手法
5. **Information Overload** - 言語的複雑性攻撃

### 🚀 使用方法

#### 基本的な使用例

```bash
# 基本的な実行
python3 scripts/research_based_humanization.py

# 強度を指定して実行
python3 scripts/research_based_humanization.py --intensity 0.3

# 入力・出力ファイルを指定
python3 scripts/research_based_humanization.py \
  --input _draft/humanize/ai-text.md \
  --output _draft/humanize/ai-text-humanized.md \
  --intensity 0.4

# プレビューモード
python3 scripts/research_based_humanization.py --preview
```

#### コマンドライン引数

- `--input, -i`: 入力ファイル（デフォルト: `_draft/humanize/ai-text.md`）
- `--output, -o`: 出力ファイル（デフォルト: `_draft/humanize/ai-text-humanized.md`）
- `--intensity, -t`: 強度 0.0-1.0（デフォルト: 0.3）
- `--preview, -p`: プレビューモード（最初の500文字のみ表示）

### 🔧 技術的実装

#### 1. Adversarial Paraphrasing（敵対的言い換え）
文構造を変更してAI検出器を回避する手法です。

```python
# 例: 文構造の変更
"Aによって Bが Cされた" → "Bは Aにより Cされる"
```

#### 2. GradEscape（勾配ベース攻撃）
検出器の勾配を利用した微小摂動による回避手法です。

```python
# 例: 句読点の微調整
"。" → "．"
"、" → "，"
```

#### 3. SilverSpeak（ホモグリフ攻撃）
視覚的に類似した文字での置換による回避手法です。

```python
# 例: Unicode文字の戦略的置換
"a" → "а" (キリル文字)
"o" → "ο" (ギリシャ文字)
```

#### 4. Syntactic Perturbation（統語的摂動）
12種類の統語的変動パターンによる文構造の微調整です。

```python
# 例: 統語的変動
"について" → "に関して"
"である" → "だ"
```

#### 5. Linguistic Complexity（言語的複雑性）
接続詞・ヘッジ・強調表現の追加による情報過多攻撃です。

```python
# 例: 複雑性の追加
"である" → "であると思われる"
"。" → "。さらに、"
```

### 📊 性能評価

#### 高強度設定（intensity=0.4）
- 文字数変化: +4.7%
- 語彙多様性: 0.769
- 統語的複雑性: 1.056
- ホモグリフ使用率: 84.2%
- **AI検出回避予測: 高**

#### 低強度設定（intensity=0.2）
- 文字数変化: +1.1%
- 語彙多様性: 0.858
- 統語的複雑性: 1.019
- ホモグリフ使用率: 83.8%
- **AI検出回避予測: 高**

### 📁 ファイル構成

```
_draft/humanize/
├── ai-text.md                     # 元のテキスト
├── ai-text-humanized.md           # ヒューマナイゼーション結果
├── humanization_report.md         # 詳細レポート
└── README.md                      # このファイル

scripts/
└── research_based_humanization.py # メインアルゴリズム
```

### 🛡️ セキュリティ考慮事項

1. **倫理的使用**: 学術研究・教育目的での使用を前提
2. **責任あるAI**: 悪用防止のための制限実装
3. **透明性**: アルゴリズムの完全な公開

### 📈 品質メトリクス

- **語彙多様性**: Type-Token Ratio による測定
- **統語的複雑性**: 平均文長による測定
- **ホモグリフ使用率**: 非ASCII文字の比率
- **可読性**: 複合スコアによる評価
- **自然性**: 変更度合いによる評価

### 🔮 今後の拡張予定

1. **多言語対応**: 英語・中国語等への展開
2. **リアルタイム処理**: ストリーミング対応
3. **カスタマイズ**: 特定ドメイン向け調整
4. **AI検出器対応**: 新しい検出手法への適応

### 📜 ライセンス

このプロジェクトは学術研究・教育目的での使用を前提としています。商用利用や悪用は禁止されています。

### 🤝 貢献

プルリクエストや改善提案を歓迎します。特に以下の分野での貢献を求めています：

- 新しい攻撃手法の実装
- 多言語対応の追加
- 性能評価の改善
- セキュリティ強化

### 📧 連絡先

技術的な質問や研究協力については、GitHubのIssuesをご利用ください。

---

**注意**: このアルゴリズムは学術研究および教育目的でのみ使用してください。悪用は厳禁です。 