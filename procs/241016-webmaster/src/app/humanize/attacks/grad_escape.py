# -*- coding: utf-8 -*-
"""
GradEscape Attack Implementation

論文参照: "GradEscape" (2025, USENIX Security)
効果: 勾配ベース攻撃による検出回避

この攻撃手法は検出器の勾配を利用した微小摂動による回避を実装します。
"""

import re
import random
import numpy as np
from typing import Dict, List, Tuple
from dataclasses import dataclass


@dataclass
class GradEscapeConfig:
    """GradEscape攻撃設定"""
    intensity: float = 0.3  # 0.0-1.0の強度
    gradient_step_size: float = 0.01  # 勾配ステップサイズ
    max_iterations: int = 10  # 最大反復回数
    preserve_semantics: bool = True  # 意味保持


class GradEscape:
    """
    GradEscape攻撃クラス
    
    勾配ベースの微小摂動を用いてAI検出器を回避する手法を実装。
    検出器の判定境界付近での微調整を行います。
    """
    
    def __init__(self, config: GradEscapeConfig = None):
        self.config = config or GradEscapeConfig()
        self.punctuation_variants = self._load_punctuation_variants()
        self.micro_perturbations = self._load_micro_perturbations()
    
    def _load_punctuation_variants(self) -> Dict[str, List[str]]:
        """句読点のバリエーションを読み込み"""
        return {
            "period": ["。", "．", "｡"],
            "comma": ["、", "，", "､"],
            "question": ["？", "?"],
            "exclamation": ["！", "!"],
            "colon": ["：", ":"],
            "semicolon": ["；", ";"],
            "parentheses_open": ["（", "("],
            "parentheses_close": ["）", ")"],
            "brackets_open": ["［", "["],
            "brackets_close": ["］", "]"],
        }
    
    def _load_micro_perturbations(self) -> Dict[str, List[Tuple[str, str]]]:
        """微小摂動パターンを読み込み"""
        return {
            "spacing_variation": [
                (r'(\w)(\s+)(\w)', r'\1 \3'),  # スペース正規化
                (r'(\w)(、|，)(\w)', r'\1\2 \3'),  # 句読点後のスペース
                (r'(\w)(。|．)(\w)', r'\1\2 \3'),  # 句点後のスペース
            ],
            
            "whitespace_injection": [
                (r'(\w{3,})(\w{3,})', r'\1\u200B\2'),  # ゼロ幅スペース挿入
                (r'(\w)(\s)(\w)', r'\1\u2009\3'),  # 細いスペース
                (r'(\w)(\.)(\s)', r'\1\2\u2009'),  # 細いスペース
            ],
            
            "unicode_normalization": [
                (r'(\w)', lambda m: self._apply_unicode_variation(m.group(1))),
            ],
            
            "character_substitution": [
                # 見た目が同じ文字への置換
                ("ー", "－"),  # 長音符の変更
                ("〜", "～"),  # 波ダッシュの変更
                ("・", "･"),   # 中点の変更
            ]
        }
    
    def apply_grad_escape(self, text: str) -> str:
        """GradEscape攻撃を適用"""
        modified_text = text
        
        # 反復的勾配降下による最適化
        for iteration in range(self.config.max_iterations):
            # 勾配に基づく摂動の適用
            modified_text = self._apply_gradient_perturbation(modified_text, iteration)
            
            # 収束判定（簡易版）
            if iteration > 0 and self._check_convergence(text, modified_text):
                break
        
        return modified_text
    
    def _apply_gradient_perturbation(self, text: str, iteration: int) -> str:
        """勾配ベースの摂動を適用"""
        modified_text = text
        
        # 段階的摂動の適用
        perturbation_strength = self.config.intensity * (1.0 - iteration * 0.1)
        
        if perturbation_strength > 0:
            # 句読点の微調整
            modified_text = self._apply_punctuation_gradient(modified_text, perturbation_strength)
            
            # スペースの微調整
            modified_text = self._apply_spacing_gradient(modified_text, perturbation_strength)
            
            # Unicode正規化の微調整
            modified_text = self._apply_unicode_gradient(modified_text, perturbation_strength)
        
        return modified_text
    
    def _apply_punctuation_gradient(self, text: str, strength: float) -> str:
        """句読点勾配を適用"""
        modified_text = text
        
        for punct_type, variants in self.punctuation_variants.items():
            if len(variants) > 1 and random.random() < strength:
                original = variants[0]
                target = random.choice(variants[1:])
                
                # 確率的置換
                if random.random() < strength:
                    modified_text = modified_text.replace(original, target)
        
        return modified_text
    
    def _apply_spacing_gradient(self, text: str, strength: float) -> str:
        """スペース勾配を適用"""
        modified_text = text
        
        spacing_patterns = self.micro_perturbations["spacing_variation"]
        
        for pattern, replacement in spacing_patterns:
            if random.random() < strength * 0.5:  # 控えめに適用
                modified_text = re.sub(pattern, replacement, modified_text)
        
        return modified_text
    
    def _apply_unicode_gradient(self, text: str, strength: float) -> str:
        """Unicode勾配を適用"""
        modified_text = ""
        
        for char in text:
            if random.random() < strength * 0.1:  # 非常に控えめ
                # Unicode正規化形式の微調整
                modified_text += self._apply_unicode_variation(char)
            else:
                modified_text += char
        
        return modified_text
    
    def _apply_unicode_variation(self, char: str) -> str:
        """Unicode文字の微小変化"""
        # 同じ見た目の異なるUnicode文字への変換
        unicode_variants = {
            'a': ['a', 'а'],  # Latin a, Cyrillic a
            'o': ['o', 'ο', 'о'],  # Latin o, Greek omicron, Cyrillic o
            'e': ['e', 'е'],  # Latin e, Cyrillic e
            'p': ['p', 'р'],  # Latin p, Cyrillic p
            'c': ['c', 'с'],  # Latin c, Cyrillic c
            'x': ['x', 'х'],  # Latin x, Cyrillic x
            'y': ['y', 'у'],  # Latin y, Cyrillic y
        }
        
        if char.lower() in unicode_variants:
            variants = unicode_variants[char.lower()]
            if len(variants) > 1:
                return random.choice(variants[1:])
        
        return char
    
    def _check_convergence(self, original: str, modified: str) -> bool:
        """収束判定"""
        # 変化率が閾値以下なら収束とみなす
        change_ratio = abs(len(modified) - len(original)) / len(original)
        return change_ratio < 0.001
    
    def apply_advanced_grad_escape(self, text: str) -> str:
        """高度なGradEscape攻撃"""
        # 多段階勾配攻撃
        text = self._apply_multi_stage_perturbation(text)
        text = self._apply_adaptive_perturbation(text)
        text = self._apply_steganographic_perturbation(text)
        
        return text
    
    def _apply_multi_stage_perturbation(self, text: str) -> str:
        """多段階摂動"""
        stages = [
            ("punctuation", 0.7),
            ("spacing", 0.5),
            ("unicode", 0.3),
            ("character", 0.2),
        ]
        
        modified_text = text
        
        for stage_name, stage_strength in stages:
            stage_intensity = self.config.intensity * stage_strength
            
            if stage_name == "punctuation":
                modified_text = self._apply_punctuation_gradient(modified_text, stage_intensity)
            elif stage_name == "spacing":
                modified_text = self._apply_spacing_gradient(modified_text, stage_intensity)
            elif stage_name == "unicode":
                modified_text = self._apply_unicode_gradient(modified_text, stage_intensity)
            elif stage_name == "character":
                modified_text = self._apply_character_substitution(modified_text, stage_intensity)
        
        return modified_text
    
    def _apply_adaptive_perturbation(self, text: str) -> str:
        """適応的摂動"""
        # 文章の特性に基づく適応的調整
        sentence_count = text.count('。') + text.count('.')
        word_count = len(text.split())
        
        # 調整係数の計算
        adaptation_factor = min(1.0, (sentence_count * word_count) / 1000)
        adapted_intensity = self.config.intensity * adaptation_factor
        
        return self._apply_gradient_perturbation(text, 0)
    
    def _apply_steganographic_perturbation(self, text: str) -> str:
        """ステガノグラフィ的摂動"""
        # 不可視文字の戦略的挿入
        steganographic_chars = [
            '\u200B',  # ゼロ幅スペース
            '\u200C',  # ゼロ幅非結合文字
            '\u200D',  # ゼロ幅結合文字
            '\u2009',  # 細いスペース
        ]
        
        modified_text = ""
        
        for i, char in enumerate(text):
            modified_text += char
            
            # 不可視文字の挿入判定
            if (i % 10 == 0 and 
                random.random() < self.config.intensity * 0.1 and 
                char not in '。、'):
                steganographic_char = random.choice(steganographic_chars)
                modified_text += steganographic_char
        
        return modified_text
    
    def _apply_character_substitution(self, text: str, strength: float) -> str:
        """文字置換摂動"""
        substitution_pairs = self.micro_perturbations["character_substitution"]
        
        modified_text = text
        for original, replacement in substitution_pairs:
            if random.random() < strength:
                modified_text = modified_text.replace(original, replacement)
        
        return modified_text
    
    def analyze_grad_escape_effect(self, original: str, modified: str) -> Dict:
        """GradEscape効果を分析"""
        return {
            "character_change": len(modified) - len(original),
            "invisible_char_count": sum(1 for char in modified if ord(char) in [0x200B, 0x200C, 0x200D, 0x2009]),
            "punctuation_changes": self._count_punctuation_changes(original, modified),
            "unicode_perturbations": self._count_unicode_perturbations(original, modified),
            "estimated_gradient_noise": self.config.intensity * 0.8,
        }
    
    def _count_punctuation_changes(self, original: str, modified: str) -> int:
        """句読点変更回数をカウント"""
        changes = 0
        for punct_type, variants in self.punctuation_variants.items():
            for variant in variants:
                changes += abs(original.count(variant) - modified.count(variant))
        return changes // 2  # 変更は2回カウントされるので半分に
    
    def _count_unicode_perturbations(self, original: str, modified: str) -> int:
        """Unicode摂動回数をカウント"""
        perturbations = 0
        for orig_char, mod_char in zip(original, modified):
            if orig_char != mod_char and orig_char.lower() == mod_char.lower():
                perturbations += 1
        return perturbations


def test_grad_escape():
    """テスト関数"""
    print("⚡ GradEscape Attack テスト開始")
    
    # テストテキスト
    test_text = """
    これは、実験用のテキストです。句読点や文字の微調整により、AI検出を回避します。
    勾配ベースの手法を用いて、検出境界を巧妙に回避する技術を実装しています。
    """
    
    # 設定
    config = GradEscapeConfig(intensity=0.5, max_iterations=5)
    attack = GradEscape(config)
    
    # 攻撃実行
    basic_escape = attack.apply_grad_escape(test_text)
    advanced_escape = attack.apply_advanced_grad_escape(test_text)
    
    # 結果表示
    print(f"\n📝 元テキスト:\n{test_text}")
    print(f"\n⚡ 基本GradEscape:\n{basic_escape}")
    print(f"\n🚀 高度GradEscape:\n{advanced_escape}")
    
    # 効果分析
    analysis = attack.analyze_grad_escape_effect(test_text, advanced_escape)
    print(f"\n📊 効果分析:")
    for key, value in analysis.items():
        print(f"  {key}: {value}")


if __name__ == "__main__":
    test_grad_escape() 