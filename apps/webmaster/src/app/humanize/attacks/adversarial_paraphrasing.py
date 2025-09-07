# -*- coding: utf-8 -*-
"""
Adversarial Paraphrasing Attack Implementation

論文参照: "Adversarial Paraphrasing" (2025)
効果: T@1%F 87.88%削減

この攻撃手法は文構造を変更してAI検出器を回避する敵対的言い換えを実装します。
"""

import re
import random
from typing import Dict, List, Tuple
from dataclasses import dataclass


@dataclass
class ParaphrasingConfig:
    """敵対的言い換え設定"""
    intensity: float = 0.3  # 0.0-1.0の強度
    preserve_meaning: bool = True  # 意味保持
    target_language: str = "ja"  # 対象言語
    

class AdversarialParaphrasing:
    """
    敵対的言い換え攻撃クラス
    
    文構造を変更してAI検出を回避する手法を実装。
    意味を保持しながら構文パターンを変更します。
    """
    
    def __init__(self, config: ParaphrasingConfig = None):
        self.config = config or ParaphrasingConfig()
        self.paraphrasing_patterns = self._load_paraphrasing_patterns()
    
    def _load_paraphrasing_patterns(self) -> Dict[str, List[Tuple[str, str]]]:
        """言い換えパターンを読み込み"""
        return {
            "passive_voice": [
                (r'(\w+)によって(\w+)が(\w+)された', r'\2は\1により\3される'),
                (r'(\w+)が(\w+)を(\w+)した', r'\2は\1によって\3された'),
                (r'(\w+)は(\w+)を(\w+)する', r'\2は\1によって\3される'),
            ],
            
            "sentence_structure": [
                (r'(\w+)について(\w+)する', r'\1に関して\2を行う'),
                (r'(\w+)のため(\w+)', r'\1により\2'),
                (r'(\w+)である', r'\1となっている'),
                (r'(\w+)した', r'\1を行った'),
                (r'(\w+)することで', r'\1を行うことで'),
            ],
            
            "conjunction_variation": [
                (r'しかし、', r'一方で、'),
                (r'また、', r'さらに、'),
                (r'そして、', r'そうして、'),
                (r'つまり、', r'すなわち、'),
                (r'例えば、', r'具体的には、'),
            ],
            
            "expression_formality": [
                (r'(\w+)だ', r'\1である'),
                (r'(\w+)である', r'\1だ'),
                (r'(\w+)している', r'\1を行っている'),
                (r'使用する', r'用いる'),
                (r'利用する', r'活用する'),
            ],
            
            "academic_style": [
                (r'明らかにした', r'解明した'),
                (r'示している', r'示唆している'),
                (r'分かった', r'判明した'),
                (r'考えられる', r'想定される'),
                (r'重要である', r'重要性が高い'),
            ]
        }
    
    def apply_paraphrasing(self, text: str) -> str:
        """敵対的言い換えを適用"""
        modified_text = text
        changes_made = 0
        total_changes_target = int(len(text.split()) * self.config.intensity * 0.1)
        
        # 各パターンカテゴリを順次適用
        for category, patterns in self.paraphrasing_patterns.items():
            if changes_made >= total_changes_target:
                break
                
            for pattern, replacement in patterns:
                if changes_made >= total_changes_target:
                    break
                    
                # 確率的適用（強度に基づく）
                if random.random() < self.config.intensity:
                    matches = list(re.finditer(pattern, modified_text))
                    if matches:
                        # ランダムに一部のマッチを置換
                        selected_matches = random.sample(
                            matches, 
                            min(len(matches), max(1, int(len(matches) * self.config.intensity)))
                        )
                        
                        for match in reversed(selected_matches):
                            modified_text = (
                                modified_text[:match.start()] + 
                                re.sub(pattern, replacement, match.group()) +
                                modified_text[match.end():]
                            )
                            changes_made += 1
        
        return modified_text
    
    def apply_complex_paraphrasing(self, text: str) -> str:
        """複合的言い換えを適用"""
        # 段階的変換
        text = self._apply_sentence_restructuring(text)
        text = self._apply_expression_variation(text)
        text = self._apply_academic_formalization(text)
        
        return text
    
    def _apply_sentence_restructuring(self, text: str) -> str:
        """文構造の再構築"""
        # 複雑な文構造変更
        restructuring_patterns = [
            # 因果関係の変更
            (r'(\w+)のため、(\w+)', r'\1により、\2'),
            (r'(\w+)によって(\w+)', r'\2は\1を原因として'),
            
            # 時系列表現の変更
            (r'その後、(\w+)', r'続いて、\1'),
            (r'最初に(\w+)', r'はじめに\1'),
            
            # 比較表現の変更
            (r'(\w+)より(\w+)', r'\2と比較して\1'),
            (r'(\w+)と同様に', r'\1と同じく'),
        ]
        
        modified_text = text
        for pattern, replacement in restructuring_patterns:
            if random.random() < self.config.intensity:
                modified_text = re.sub(pattern, replacement, modified_text)
        
        return modified_text
    
    def _apply_expression_variation(self, text: str) -> str:
        """表現のバリエーション追加"""
        variation_patterns = [
            # 修飾語の追加
            (r'(\w+である)', r'\1と考えられる'),
            (r'(\w+している)', r'\1と思われる'),
            
            # 強調表現の変更
            (r'重要な(\w+)', r'重要性の高い\1'),
            (r'大きな(\w+)', r'顕著な\1'),
            
            # 専門的表現への変更
            (r'方法', r'手法'),
            (r'結果', r'成果'),
            (r'問題', r'課題'),
        ]
        
        modified_text = text
        for pattern, replacement in variation_patterns:
            if random.random() < self.config.intensity * 0.7:  # やや控えめに適用
                modified_text = re.sub(pattern, replacement, modified_text)
        
        return modified_text
    
    def _apply_academic_formalization(self, text: str) -> str:
        """学術的形式化"""
        formalization_patterns = [
            # より正式な表現への変更
            (r'調べた', r'調査した'),
            (r'見つけた', r'発見した'),
            (r'作った', r'作成した'),
            (r'使った', r'使用した'),
            
            # 学術的接続語の追加
            (r'。(\w+)', r'。\1に関して'),
            (r'(\w+)。', r'\1と報告されている。'),
        ]
        
        modified_text = text
        for pattern, replacement in formalization_patterns:
            if random.random() < self.config.intensity * 0.5:  # 控えめに適用
                modified_text = re.sub(pattern, replacement, modified_text)
        
        return modified_text
    
    def analyze_paraphrasing_effect(self, original: str, modified: str) -> Dict:
        """言い換え効果を分析"""
        return {
            "character_change": len(modified) - len(original),
            "character_change_ratio": (len(modified) - len(original)) / len(original),
            "word_change_ratio": len(set(modified.split()) - set(original.split())) / len(original.split()),
            "sentence_count_change": modified.count('。') - original.count('。'),
            "estimated_detection_reduction": min(0.87, self.config.intensity * 0.9),  # 研究論文ベース
        }


def test_adversarial_paraphrasing():
    """テスト関数"""
    print("🔄 Adversarial Paraphrasing Attack テスト開始")
    
    # テストテキスト
    test_text = """
    この研究では、新しい手法を開発した。実験により、その効果が明らかになった。
    従来の方法と比較して、大きな改善が見られた。しかし、いくつかの課題も発見された。
    """
    
    # 設定
    config = ParaphrasingConfig(intensity=0.5)
    attack = AdversarialParaphrasing(config)
    
    # 攻撃実行
    modified_text = attack.apply_paraphrasing(test_text)
    complex_text = attack.apply_complex_paraphrasing(test_text)
    
    # 結果表示
    print(f"\n📝 元テキスト:\n{test_text}")
    print(f"\n🎯 基本言い換え:\n{modified_text}")
    print(f"\n🚀 複合言い換え:\n{complex_text}")
    
    # 効果分析
    analysis = attack.analyze_paraphrasing_effect(test_text, complex_text)
    print(f"\n📊 効果分析:")
    for key, value in analysis.items():
        print(f"  {key}: {value}")


if __name__ == "__main__":
    test_adversarial_paraphrasing() 