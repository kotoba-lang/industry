# 人間化処理評価フレームワーク

## 概要
AI生成文章の人間化処理効果を定量的・定性的に評価するための包括的フレームワーク。

## 1. 定量的評価指標

### 1.1 Perplexity（予測困難度）測定

```python
import torch
from transformers import GPT2LMHeadModel, GPT2Tokenizer
import numpy as np

class PerplexityEvaluator:
    def __init__(self, model_name='gpt2'):
        self.model = GPT2LMHeadModel.from_pretrained(model_name)
        self.tokenizer = GPT2Tokenizer.from_pretrained(model_name)
        self.model.eval()
    
    def calculate_perplexity(self, text: str) -> float:
        """
        GPT-2を用いたperplexity計算
        より高い値 = より予測困難 = より人間的
        """
        inputs = self.tokenizer(text, return_tensors='pt', truncation=True, max_length=512)
        
        with torch.no_grad():
            outputs = self.model(**inputs, labels=inputs['input_ids'])
            loss = outputs.loss
            
        return torch.exp(loss).item()
```

### 1.2 Burstiness（局所的ランダム性）測定

```python
import re
import numpy as np

class BurstinessEvaluator:
    def __init__(self):
        self.sentence_splitter = r'[。！？\.\!\?]'
        
    def calculate_sentence_burstiness(self, text: str) -> float:
        """文章長の変動によるburstiness測定"""
        sentences = re.split(self.sentence_splitter, text)
        sentence_lengths = [len(s.strip()) for s in sentences if s.strip()]
        
        if len(sentence_lengths) < 2:
            return 0.0
        
        # 変動係数 (Coefficient of Variation)
        mean_length = np.mean(sentence_lengths)
        std_length = np.std(sentence_lengths)
        
        return std_length / mean_length if mean_length > 0 else 0.0
    
    def calculate_lexical_burstiness(self, text: str) -> float:
        """語彙使用の不規則性によるburstiness測定"""
        words = text.split()
        word_freq = {}
        
        for word in words:
            word_freq[word] = word_freq.get(word, 0) + 1
        
        # 語彙分布のエントロピー
        total_words = len(words)
        entropy = 0
        
        for freq in word_freq.values():
            p = freq / total_words
            entropy -= p * np.log2(p)
        
        return entropy
```

### 1.3 語彙多様性測定

```python
class VocabularyDiversityEvaluator:
    def __init__(self):
        self.stopwords = {'の', 'に', 'は', 'を', 'が', 'と', 'で', 'から', 'より', 'まで'}
    
    def calculate_ttr(self, text: str) -> float:
        """Type-Token Ratio: 語彙の豊富さ"""
        words = [w for w in text.split() if w not in self.stopwords]
        if not words:
            return 0.0
        
        unique_words = len(set(words))
        total_words = len(words)
        
        return unique_words / total_words
```

## 2. 統合評価システム

```python
class ComprehensiveEvaluator:
    def __init__(self):
        self.perplexity_evaluator = PerplexityEvaluator()
        self.burstiness_evaluator = BurstinessEvaluator()
        self.vocabulary_evaluator = VocabularyDiversityEvaluator()
    
    def evaluate_text(self, original_text: str, humanized_text: str) -> dict:
        """包括的な評価を実行"""
        results = {
            'perplexity': {
                'original': self.perplexity_evaluator.calculate_perplexity(original_text),
                'humanized': self.perplexity_evaluator.calculate_perplexity(humanized_text)
            },
            'burstiness': {
                'original': self.burstiness_evaluator.calculate_sentence_burstiness(original_text),
                'humanized': self.burstiness_evaluator.calculate_sentence_burstiness(humanized_text)
            },
            'vocabulary_diversity': {
                'original': self.vocabulary_evaluator.calculate_ttr(original_text),
                'humanized': self.vocabulary_evaluator.calculate_ttr(humanized_text)
            }
        }
        
        improvements = self._calculate_improvements(results)
        
        return {
            'metrics': results,
            'improvements': improvements,
            'assessment': self._generate_assessment(improvements)
        }
    
    def _calculate_improvements(self, results: dict) -> dict:
        """改善度を計算"""
        improvements = {}
        
        for metric in ['perplexity', 'burstiness', 'vocabulary_diversity']:
            orig = results[metric]['original']
            human = results[metric]['humanized']
            improvements[metric] = ((human - orig) / orig * 100) if orig > 0 else 0
        
        return improvements
    
    def _generate_assessment(self, improvements: dict) -> str:
        """総合評価を生成"""
        perp_improved = improvements['perplexity'] > 10
        burst_improved = improvements['burstiness'] > 10
        vocab_improved = improvements['vocabulary_diversity'] > 5
        
        if perp_improved and burst_improved:
            return "効果的な人間化処理が実現されています"
        elif perp_improved or burst_improved:
            return "部分的な改善が見られます"
        else:
            return "更なる改善が必要です"
```

## 3. AI検出器評価

```python
class AIDetectorEvaluator:
    def __init__(self):
        # 実際の実装では各検出器のAPIを使用
        self.detectors = ['GPTZero', 'OpenAI', 'Originality.AI']
    
    def evaluate_with_detectors(self, text: str) -> dict:
        """複数のAI検出器で評価"""
        results = {}
        
        for detector_name in self.detectors:
            score = self._mock_detector_score(text, detector_name)
            results[detector_name] = {
                'ai_probability': score,
                'human_probability': 1 - score,
                'classification': 'AI' if score > 0.5 else 'Human'
            }
        
        return results
    
    def _mock_detector_score(self, text: str, detector_name: str) -> float:
        """検出器スコアのモック実装"""
        # 実際の実装では各検出器のAPIを呼び出し
        # 簡易的な計算例
        words = text.split()
        unique_ratio = len(set(words)) / len(words) if words else 0
        
        # 語彙多様性が高いほど人間的と判定
        if unique_ratio > 0.6:
            return 0.3  # 人間的
        elif unique_ratio > 0.4:
            return 0.5  # 中間
        else:
            return 0.7  # AI的
```

## 4. 使用例

```python
def main():
    # 評価システムの初期化
    evaluator = ComprehensiveEvaluator()
    detector_evaluator = AIDetectorEvaluator()
    
    # テストテキスト
    original_text = """
    臓器インタラクトミクスの可視化について研究が進んでいる。
    組織透明化技術により、3Dイメージングが可能になった。
    この技術は重要である。
    """
    
    # 人間化処理
    # (HumanizationPipelineクラスは別途実装)
    humanized_text = transform_text(original_text)
    
    # 評価実行
    evaluation = evaluator.evaluate_text(original_text, humanized_text)
    detector_results = {
        'original': detector_evaluator.evaluate_with_detectors(original_text),
        'humanized': detector_evaluator.evaluate_with_detectors(humanized_text)
    }
    
    # 結果表示
    print("=== 評価結果 ===")
    print(f"Perplexity改善: {evaluation['improvements']['perplexity']:.1f}%")
    print(f"Burstiness改善: {evaluation['improvements']['burstiness']:.1f}%")
    print(f"語彙多様性改善: {evaluation['improvements']['vocabulary_diversity']:.1f}%")
    print(f"総合評価: {evaluation['assessment']}")
    
    print("\n=== AI検出器結果 ===")
    for detector, result in detector_results['humanized'].items():
        print(f"{detector}: {result['classification']} ({result['ai_probability']:.2f})")

def transform_text(text: str) -> str:
    """
    人間化処理の実装
    (実際のHumanizationPipelineクラスを使用)
    """
    # 簡易的な変換例
    transformations = [
        ('明らかになった', '判明した'),
        ('重要である', '肝要である'),
        ('技術', '手法'),
        ('3Dイメージング', '三次元画像化')
    ]
    
    result = text
    for original, replacement in transformations:
        result = result.replace(original, replacement)
    
    return result

if __name__ == "__main__":
    main()
```

## 5. 継続的改善

```python
class ContinuousImprovementSystem:
    def __init__(self):
        self.evaluation_history = []
        self.successful_patterns = []
    
    def learn_from_evaluation(self, evaluation_result: dict, transformation_params: dict):
        """評価結果から学習"""
        self.evaluation_history.append({
            'timestamp': datetime.now(),
            'evaluation': evaluation_result,
            'parameters': transformation_params
        })
        
        # 成功パターンの学習
        if self._is_successful(evaluation_result):
            self.successful_patterns.append(transformation_params)
    
    def _is_successful(self, evaluation_result: dict) -> bool:
        """成功判定"""
        improvements = evaluation_result.get('improvements', {})
        return (improvements.get('perplexity', 0) > 10 and 
                improvements.get('burstiness', 0) > 10)
    
    def recommend_parameters(self) -> dict:
        """推奨パラメータの提案"""
        if not self.successful_patterns:
            return {
                'synonym_replacement_rate': 0.4,
                'sentence_modification_rate': 0.3,
                'hedging_insertion_rate': 0.2
            }
        
        # 成功パターンの平均を計算
        avg_params = {}
        for param in self.successful_patterns[0].keys():
            values = [p[param] for p in self.successful_patterns]
            avg_params[param] = np.mean(values)
        
        return avg_params
```

## 6. 評価指標の解釈

### 6.1 Perplexity
- **低値 (< 30)**: AI生成文章の可能性が高い
- **中値 (30-100)**: 判定が困難な範囲
- **高値 (> 100)**: 人間の文章の可能性が高い

### 6.2 Burstiness
- **低値 (< 0.3)**: 均一な文章構造（AI的）
- **中値 (0.3-0.6)**: 適度な変動
- **高値 (> 0.6)**: 高い変動性（人間的）

### 6.3 語彙多様性
- **低値 (< 0.5)**: 語彙が限定的
- **中値 (0.5-0.7)**: 適度な多様性
- **高値 (> 0.7)**: 豊富な語彙使用

## 7. 注意事項

### 7.1 評価の限界
- 定量的指標だけでは人間らしさを完全には捉えられない
- 文脈や分野特有の表現への考慮が必要
- 継続的な検証と改善が重要

### 7.2 倫理的配慮
- 学術的誠実性の維持
- 技術の適切な使用
- 検出回避を目的とした悪用の防止

---

この評価フレームワークにより、人間化処理の効果を客観的に測定し、継続的な改善を図ることができます。
 