# -*- coding: utf-8 -*-
"""
Syntactic Perturbation Attack Implementation

論文参照: "Navigating the Shadows" (2024, ACL)
効果: 12種類の統語摂動技術によるAI検出回避

この攻撃手法は文構造の変更による検出回避を実装します。
"""

import re
import random
from typing import Dict, List, Tuple, Optional
from dataclasses import dataclass


@dataclass
class SyntacticPerturbationConfig:
    """統語摂動攻撃設定"""
    intensity: float = 0.3  # 0.0-1.0の強度
    preserve_meaning: bool = True  # 意味保持
    target_language: str = "ja"  # 対象言語
    max_transformations: int = 15  # 最大変換数


class SyntacticPerturbation:
    """統語摂動攻撃クラス"""
    
    def __init__(self, config: SyntacticPerturbationConfig = None):
        self.config = config or SyntacticPerturbationConfig()
        self.transformation_patterns = self._load_transformation_patterns()
        self.syntactic_variations = self._load_syntactic_variations()
    
    def _load_transformation_patterns(self) -> Dict[str, List[Tuple[str, str]]]:
        """変換パターンを読み込み"""
        return {
            "verb_form_variation": [
                (r'(\w+)している', r'\1を行っている'),
                (r'(\w+)する', r'\1を実施する'),
                (r'(\w+)した', r'\1を行った'),
                (r'(\w+)される', r'\1が実施される'),
                (r'(\w+)できる', r'\1が可能である'),
            ],
            "sentence_restructuring": [
                (r'(\w+)のため、(\w+)', r'\1を原因として、\2'),
                (r'(\w+)により、(\w+)', r'\1を通じて、\2'),
                (r'(\w+)で、(\w+)', r'\1において、\2'),
                (r'(\w+)から、(\w+)', r'\1を起点として、\2'),
            ],
            "clause_embedding": [
                (r'(\w+)である', r'\1となっている'),
                (r'(\w+)だった', r'\1であった'),
                (r'(\w+)です', r'\1となります'),
                (r'(\w+)した', r'\1を実施した'),
            ],
        }
    
    def _load_syntactic_variations(self) -> Dict[str, List[str]]:
        """統語的変化パターン"""
        return {
            "conjunctions": [
                "そして", "さらに", "また", "加えて", "なお", "ちなみに",
                "一方で", "他方で", "それに対して", "それと同時に"
            ],
            "hedges": [
                "おそらく", "恐らく", "たぶん", "多分", "と思われる",
                "と考えられる", "と推測される", "ようである", "らしい"
            ],
            "discourse_markers": [
                "つまり", "すなわち", "いわゆる", "要するに", "換言すれば",
                "言い換えれば", "具体的には", "例えば", "特に"
            ],
            "sentence_starters": [
                "研究者の見解では", "学術的見地から言えば", "専門家によれば",
                "最新の知見では", "包括的な分析の結果", "多面的な検証を通じて",
                "案の定", "やはり", "当然のことながら"
            ]
        }
    
    def apply_syntactic_perturbation(self, text: str) -> str:
        """統語摂動攻撃を適用"""
        modified_text = text
        transformations_applied = 0
        
        for pattern_type, patterns in self.transformation_patterns.items():
            if transformations_applied >= self.config.max_transformations:
                break
            
            for pattern, replacement in patterns:
                if (random.random() < self.config.intensity and
                    transformations_applied < self.config.max_transformations):
                    
                    if re.search(pattern, modified_text):
                        modified_text = re.sub(pattern, replacement, modified_text, count=1)
                        transformations_applied += 1
        
        return modified_text
    
    def apply_advanced_syntactic_perturbation(self, text: str) -> str:
        """高度な統語摂動攻撃"""
        text = self._apply_discourse_enhancement(text)
        text = self._apply_syntactic_complexity(text)
        text = self._apply_structural_variation(text)
        text = self._apply_hedge_insertion(text)
        
        return text
    
    def _apply_discourse_enhancement(self, text: str) -> str:
        """談話強化"""
        enhanced_text = text
        sentences = self._split_sentences(enhanced_text)
        
        modified_sentences = []
        for i, sentence in enumerate(sentences):
            if random.random() < self.config.intensity * 0.3:
                if i > 0:
                    discourse_marker = random.choice(
                        self.syntactic_variations["discourse_markers"]
                    )
                    sentence = f"{discourse_marker}、{sentence}"
            
            modified_sentences.append(sentence)
        
        return "".join(modified_sentences)
    
    def _apply_syntactic_complexity(self, text: str) -> str:
        """統語的複雑性の増加"""
        complex_text = text
        
        complexity_patterns = [
            (r'(\w+)する', r'\1を実施する'),
            (r'(\w+)した', r'\1を行った'),
            (r'(\w+)である', r'\1となっている'),
        ]
        
        for pattern, replacement in complexity_patterns:
            if random.random() < self.config.intensity * 0.5:
                complex_text = re.sub(pattern, replacement, complex_text)
        
        return complex_text
    
    def _apply_structural_variation(self, text: str) -> str:
        """構造的変化"""
        varied_text = text
        
        for pattern_type in ["verb_form_variation", "sentence_restructuring", "clause_embedding"]:
            if pattern_type in self.transformation_patterns:
                patterns = self.transformation_patterns[pattern_type]
                
                for pattern, replacement in patterns:
                    if random.random() < self.config.intensity * 0.4:
                        varied_text = re.sub(pattern, replacement, varied_text, count=1)
        
        return varied_text
    
    def _apply_hedge_insertion(self, text: str) -> str:
        """ヘッジ表現の挿入"""
        hedged_text = text
        sentences = self._split_sentences(hedged_text)
        
        modified_sentences = []
        for sentence in sentences:
            if random.random() < self.config.intensity * 0.2:
                hedge = random.choice(self.syntactic_variations["hedges"])
                
                if sentence.endswith('。'):
                    sentence = sentence[:-1] + hedge + '。'
                else:
                    sentence = hedge + sentence
            
            modified_sentences.append(sentence)
        
        return "".join(modified_sentences)
    
    def _split_sentences(self, text: str) -> List[str]:
        """文を分割"""
        sentences = re.split(r'[。！？]', text)
        delimiters = re.findall(r'[。！？]', text)
        
        result = []
        for i, sentence in enumerate(sentences):
            if sentence.strip():
                if i < len(delimiters):
                    result.append(sentence + delimiters[i])
                else:
                    result.append(sentence)
        
        return result
    
    def analyze_syntactic_perturbation_effect(self, original: str, modified: str) -> Dict:
        """統語摂動効果を分析"""
        return {
            "character_change": len(modified) - len(original),
            "sentence_count": len(self._split_sentences(modified)),
            "transformation_count": self._count_transformations(original, modified),
            "syntactic_complexity": self._calculate_syntactic_complexity(modified),
            "discourse_markers": self._count_discourse_markers(modified),
            "hedges_count": self._count_hedges(modified),
            "estimated_naturalness": self._estimate_naturalness(modified),
        }
    
    def _count_transformations(self, original: str, modified: str) -> int:
        """変換回数をカウント"""
        change_ratio = abs(len(modified) - len(original)) / len(original)
        return int(change_ratio * 10)
    
    def _calculate_syntactic_complexity(self, text: str) -> float:
        """統語的複雑性を計算"""
        sentences = self._split_sentences(text)
        if not sentences:
            return 1.0
        
        total_complexity = 0
        for sentence in sentences:
            words = len(sentence.split())
            clauses = sentence.count('、') + sentence.count(',') + 1
            complexity = words / clauses if clauses > 0 else words
            total_complexity += complexity
        
        return total_complexity / len(sentences)
    
    def _count_discourse_markers(self, text: str) -> int:
        """談話標識の数をカウント"""
        markers = self.syntactic_variations.get("discourse_markers", [])
        count = 0
        for marker in markers:
            count += text.count(marker)
        return count
    
    def _count_hedges(self, text: str) -> int:
        """ヘッジ表現の数をカウント"""
        hedges = self.syntactic_variations.get("hedges", [])
        count = 0
        for hedge in hedges:
            count += text.count(hedge)
        return count
    
    def _estimate_naturalness(self, text: str) -> float:
        """自然さを推定"""
        sentences = self._split_sentences(text)
        if not sentences:
            return 1.0
        
        naturalness_score = 0.0
        for sentence in sentences:
            words = len(sentence.split())
            if 10 <= words <= 30:
                naturalness_score += 1.0
            elif 5 <= words <= 40:
                naturalness_score += 0.8
            else:
                naturalness_score += 0.5
        
        return naturalness_score / len(sentences)


def test_syntactic_perturbation():
    """テスト関数"""
    print("📝 Syntactic Perturbation Attack テスト開始")
    
    test_text = """
    この研究では、新しい手法を開発した。その結果、精度が向上した。
    従来の方法と比較して、性能が大幅に改善されている。
    """
    
    config = SyntacticPerturbationConfig(intensity=0.6, max_transformations=10)
    attack = SyntacticPerturbation(config)
    
    basic_perturbation = attack.apply_syntactic_perturbation(test_text)
    advanced_perturbation = attack.apply_advanced_syntactic_perturbation(test_text)
    
    print(f"\n📝 元テキスト:\n{test_text}")
    print(f"\n📝 基本統語摂動:\n{basic_perturbation}")
    print(f"\n🚀 高度統語摂動:\n{advanced_perturbation}")
    
    analysis = attack.analyze_syntactic_perturbation_effect(test_text, advanced_perturbation)
    print(f"\n📊 効果分析:")
    for key, value in analysis.items():
        print(f"  {key}: {value}")


if __name__ == "__main__":
    test_syntactic_perturbation() 