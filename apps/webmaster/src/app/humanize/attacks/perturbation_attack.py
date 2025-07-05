# -*- coding: utf-8 -*-
"""
Perturbation Attack Implementation

論文参照: "Deep Learning Model Perturbation for AI Text Detection Evasion"
効果: ホワイトボックス/ブラックボックス攻撃による検出回避

この攻撃手法は深層学習モデルの微小な入力変更を通じて検出器を欺く手法です。
"""

import re
import random
import numpy as np
from typing import Dict, List, Tuple, Optional
from dataclasses import dataclass


@dataclass
class PerturbationConfig:
    """摂動攻撃設定"""
    intensity: float = 0.3  # 0.0-1.0の強度
    perturbation_type: str = "blackbox"  # "whitebox", "blackbox", "hybrid"
    max_perturbations: int = 50  # 最大摂動数
    preserve_semantics: bool = True  # 意味保持


class PerturbationAttack:
    """
    摂動ベース攻撃クラス
    
    深層学習モデルの微小な入力変更を通じて検出器を欺く手法を実装。
    ホワイトボックスとブラックボックス両方の攻撃をサポート。
    """
    
    def __init__(self, config: PerturbationConfig = None):
        self.config = config or PerturbationConfig()
        self.perturbation_patterns = self._load_perturbation_patterns()
        self.gradient_estimators = self._initialize_gradient_estimators()
    
    def _load_perturbation_patterns(self) -> Dict[str, List[Tuple[str, str]]]:
        """摂動パターンを読み込み"""
        return {
            "character_level": [
                # 文字レベルの微小変更
                ("a", "ａ"),  # 全角変換
                ("o", "ο"),   # ギリシャ文字
                ("e", "е"),   # キリル文字
                ("i", "і"),   # キリル文字
                ("p", "р"),   # キリル文字
                ("c", "с"),   # キリル文字
                ("x", "х"),   # キリル文字
                ("y", "у"),   # キリル文字
            ],
            
            "word_level": [
                # 単語レベルの摂動
                ("について", "に関して"),
                ("である", "となっている"),
                ("している", "を行っている"),
                ("できる", "可能である"),
                ("重要な", "重要性の高い"),
                ("大きな", "顕著な"),
                ("新しい", "新規の"),
                ("高い", "高度な"),
            ],
            
            "punctuation_level": [
                # 句読点レベルの摂動
                ("。", "．"),
                ("、", "，"),
                ("！", "!"),
                ("？", "?"),
                ("：", ":"),
                ("；", ";"),
                ("（", "("),
                ("）", ")"),
            ],
            
            "spacing_level": [
                # スペーシングレベルの摂動
                (r'(\w)\s+(\w)', r'\1 \2'),  # スペース正規化
                (r'(\w)([。、！？])', r'\1 \2'),  # 句読点前スペース
                (r'([。、！？])(\w)', r'\1 \2'),  # 句読点後スペース
            ]
        }
    
    def _initialize_gradient_estimators(self) -> Dict[str, callable]:
        """勾配推定器を初期化"""
        return {
            "finite_difference": self._finite_difference_gradient,
            "zeroth_order": self._zeroth_order_gradient,
            "evolutionary": self._evolutionary_gradient,
        }
    
    def apply_perturbation_attack(self, text: str) -> str:
        """摂動攻撃を適用"""
        if self.config.perturbation_type == "whitebox":
            return self._apply_whitebox_attack(text)
        elif self.config.perturbation_type == "blackbox":
            return self._apply_blackbox_attack(text)
        else:  # hybrid
            return self._apply_hybrid_attack(text)
    
    def _apply_whitebox_attack(self, text: str) -> str:
        """ホワイトボックス攻撃"""
        # 勾配情報を利用した効率的な摂動
        perturbed_text = text
        perturbations_applied = 0
        
        # 勾配ベースの摂動選択
        gradient_map = self._estimate_gradient_importance(text)
        
        # 重要度の高い位置から摂動を適用
        for position, importance in sorted(gradient_map.items(), key=lambda x: x[1], reverse=True):
            if perturbations_applied >= self.config.max_perturbations:
                break
            
            if random.random() < importance * self.config.intensity:
                perturbed_text = self._apply_targeted_perturbation(perturbed_text, position)
                perturbations_applied += 1
        
        return perturbed_text
    
    def _apply_blackbox_attack(self, text: str) -> str:
        """ブラックボックス攻撃"""
        # 出力のみを観測して摂動を適用
        perturbed_text = text
        perturbations_applied = 0
        
        # ランダム摂動の適用
        for pattern_type, patterns in self.perturbation_patterns.items():
            if perturbations_applied >= self.config.max_perturbations:
                break
            
            for original, replacement in patterns:
                if (perturbations_applied < self.config.max_perturbations and
                    random.random() < self.config.intensity):
                    
                    if pattern_type == "spacing_level":
                        perturbed_text = re.sub(original, replacement, perturbed_text)
                    else:
                        perturbed_text = perturbed_text.replace(original, replacement)
                    
                    perturbations_applied += 1
        
        return perturbed_text
    
    def _apply_hybrid_attack(self, text: str) -> str:
        """ハイブリッド攻撃"""
        # ホワイトボックスとブラックボックスの組み合わせ
        text = self._apply_whitebox_attack(text)
        text = self._apply_blackbox_attack(text)
        return text
    
    def _estimate_gradient_importance(self, text: str) -> Dict[int, float]:
        """勾配重要度を推定"""
        gradient_map = {}
        
        # 文字位置ごとの重要度を計算
        for i, char in enumerate(text):
            # 簡易的な重要度計算（実際の実装ではより高度な手法を使用）
            importance = 0.0
            
            # 文字の種類による重要度
            if char.isalpha():
                importance += 0.8
            elif char in "。、！？":
                importance += 0.6
            elif char.isdigit():
                importance += 0.4
            else:
                importance += 0.2
            
            # 位置による重要度（文頭、文末は重要）
            if i < len(text) * 0.1 or i > len(text) * 0.9:
                importance *= 1.5
            
            # ランダム性を追加
            importance *= (0.5 + random.random())
            
            gradient_map[i] = min(1.0, importance)
        
        return gradient_map
    
    def _apply_targeted_perturbation(self, text: str, position: int) -> str:
        """特定位置への標的摂動"""
        if position >= len(text):
            return text
        
        char = text[position]
        replacement = self._find_best_replacement(char)
        
        if replacement and replacement != char:
            return text[:position] + replacement + text[position+1:]
        
        return text
    
    def _find_best_replacement(self, char: str) -> Optional[str]:
        """最適な置換文字を検索"""
        for pattern_type, patterns in self.perturbation_patterns.items():
            for original, replacement in patterns:
                if original == char:
                    return replacement
        
        return None
    
    def _finite_difference_gradient(self, text: str, position: int) -> float:
        """有限差分による勾配推定"""
        # 簡易的な実装
        return random.uniform(0.0, 1.0)
    
    def _zeroth_order_gradient(self, text: str, position: int) -> float:
        """ゼロ次勾配推定"""
        # 簡易的な実装
        return random.uniform(0.0, 1.0)
    
    def _evolutionary_gradient(self, text: str, position: int) -> float:
        """進化的勾配推定"""
        # 簡易的な実装
        return random.uniform(0.0, 1.0)
    
    def apply_advanced_perturbation(self, text: str) -> str:
        """高度摂動攻撃"""
        # 多段階摂動
        text = self._apply_multi_scale_perturbation(text)
        text = self._apply_adaptive_perturbation(text)
        text = self._apply_steganographic_perturbation(text)
        
        return text
    
    def _apply_multi_scale_perturbation(self, text: str) -> str:
        """マルチスケール摂動"""
        # 文字、単語、文レベルでの摂動
        scales = ["character_level", "word_level", "punctuation_level"]
        
        for scale in scales:
            if scale in self.perturbation_patterns:
                patterns = self.perturbation_patterns[scale]
                for original, replacement in patterns:
                    if random.random() < self.config.intensity * 0.3:
                        text = text.replace(original, replacement)
        
        return text
    
    def _apply_adaptive_perturbation(self, text: str) -> str:
        """適応的摂動"""
        # テキストの特性に基づく摂動調整
        text_length = len(text)
        sentence_count = text.count('。') + text.count('!')
        
        # 調整係数の計算
        adaptation_factor = min(1.0, (text_length * sentence_count) / 1000)
        adapted_intensity = self.config.intensity * adaptation_factor
        
        # 調整された強度で摂動を適用
        return self._apply_blackbox_attack(text)
    
    def _apply_steganographic_perturbation(self, text: str) -> str:
        """ステガノグラフィ的摂動"""
        # 不可視文字の戦略的挿入
        steganographic_chars = [
            '\u200B',  # ゼロ幅スペース
            '\u200C',  # ゼロ幅非結合文字
            '\u200D',  # ゼロ幅結合文字
            '\u2009',  # 細いスペース
            '\u2060',  # 単語結合子
        ]
        
        modified_text = ""
        
        for i, char in enumerate(text):
            modified_text += char
            
            # 不可視文字の挿入判定
            if (i % 15 == 0 and 
                random.random() < self.config.intensity * 0.1 and 
                char not in '。、！？'):
                steganographic_char = random.choice(steganographic_chars)
                modified_text += steganographic_char
        
        return modified_text
    
    def analyze_perturbation_effect(self, original: str, modified: str) -> Dict:
        """摂動効果を分析"""
        return {
            "character_change": len(modified) - len(original),
            "perturbation_count": self._count_perturbations(original, modified),
            "perturbation_density": self._calculate_perturbation_density(original, modified),
            "invisible_char_count": sum(1 for char in modified if ord(char) in [0x200B, 0x200C, 0x200D, 0x2009, 0x2060]),
            "semantic_preservation": self._estimate_semantic_preservation(original, modified),
            "estimated_gradient_noise": self.config.intensity * 0.9,
            "attack_type": self.config.perturbation_type,
        }
    
    def _count_perturbations(self, original: str, modified: str) -> int:
        """摂動回数をカウント"""
        differences = 0
        for orig_char, mod_char in zip(original, modified):
            if orig_char != mod_char:
                differences += 1
        return differences
    
    def _calculate_perturbation_density(self, original: str, modified: str) -> float:
        """摂動密度を計算"""
        if len(original) == 0:
            return 0.0
        return self._count_perturbations(original, modified) / len(original)
    
    def _estimate_semantic_preservation(self, original: str, modified: str) -> float:
        """意味保持度を推定"""
        # 簡易的な実装（実際にはより高度な手法を使用）
        length_ratio = min(len(modified), len(original)) / max(len(modified), len(original))
        character_similarity = 1.0 - (self._count_perturbations(original, modified) / max(len(original), 1))
        
        return (length_ratio + character_similarity) / 2


def test_perturbation_attack():
    """テスト関数"""
    print("⚡ Perturbation Attack テスト開始")
    
    # テストテキスト
    test_text = """
    この研究では、深層学習モデルの摂動攻撃について検討する。
    微小な入力変更により、AI検出器を効果的に回避することが可能である。
    """
    
    # 設定
    config = PerturbationConfig(intensity=0.5, perturbation_type="hybrid", max_perturbations=20)
    attack = PerturbationAttack(config)
    
    # 攻撃実行
    whitebox_result = attack._apply_whitebox_attack(test_text)
    blackbox_result = attack._apply_blackbox_attack(test_text)
    hybrid_result = attack.apply_perturbation_attack(test_text)
    advanced_result = attack.apply_advanced_perturbation(test_text)
    
    # 結果表示
    print(f"\n📝 元テキスト:\n{test_text}")
    print(f"\n⚪ ホワイトボックス攻撃:\n{whitebox_result}")
    print(f"\n⚫ ブラックボックス攻撃:\n{blackbox_result}")
    print(f"\n🔄 ハイブリッド攻撃:\n{hybrid_result}")
    print(f"\n🚀 高度摂動攻撃:\n{advanced_result}")
    
    # 効果分析
    analysis = attack.analyze_perturbation_effect(test_text, advanced_result)
    print(f"\n📊 効果分析:")
    for key, value in analysis.items():
        print(f"  {key}: {value}")


if __name__ == "__main__":
    test_perturbation_attack() 