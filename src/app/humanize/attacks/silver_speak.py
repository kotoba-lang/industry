# -*- coding: utf-8 -*-
"""
SilverSpeak Attack Implementation

論文参照: "SilverSpeak" (2024)
効果: ホモグリフ攻撃による視覚的検出回避

この攻撃手法は視覚的に類似した文字での置換による回避を実装します。
"""

import random
import unicodedata
from typing import Dict, List, Set, Tuple
from dataclasses import dataclass


@dataclass
class SilverSpeakConfig:
    """SilverSpeak攻撃設定"""
    intensity: float = 0.3  # 0.0-1.0の強度
    preserve_readability: bool = True  # 可読性保持
    homoglyph_ratio: float = 0.2  # ホモグリフ使用比率
    unicode_coverage: str = "extended"  # "basic", "extended", "full"


class SilverSpeak:
    """
    SilverSpeak攻撃クラス
    
    ホモグリフ（視覚的に類似した文字）を用いてAI検出を回避する手法を実装。
    Unicode文字の豊富なバリエーションを活用します。
    """
    
    def __init__(self, config: SilverSpeakConfig = None):
        self.config = config or SilverSpeakConfig()
        self.homoglyph_map = self._build_homoglyph_map()
        self.confusion_matrix = self._build_confusion_matrix()
    
    def _build_homoglyph_map(self) -> Dict[str, List[str]]:
        """ホモグリフマッピングを構築"""
        basic_homoglyphs = {
            # ASCII文字のホモグリフ
            'a': ['a', 'а', 'ɑ', 'α', 'ａ'],  # Latin, Cyrillic, Greek, Fullwidth
            'o': ['o', 'о', 'ο', 'օ', 'ｏ'],  # Latin, Cyrillic, Greek, Armenian, Fullwidth
            'e': ['e', 'е', 'ε', 'ｅ'],      # Latin, Cyrillic, Greek, Fullwidth
            'p': ['p', 'р', 'ρ', 'ｐ'],      # Latin, Cyrillic, Greek, Fullwidth
            'c': ['c', 'с', 'ϲ', 'ｃ'],      # Latin, Cyrillic, Greek, Fullwidth
            'x': ['x', 'х', 'χ', 'ｘ'],      # Latin, Cyrillic, Greek, Fullwidth
            'y': ['y', 'у', 'γ', 'ｙ'],      # Latin, Cyrillic, Greek, Fullwidth
            'i': ['i', 'і', 'ι', 'ｉ'],      # Latin, Cyrillic, Greek, Fullwidth
            'j': ['j', 'ј', 'ｊ'],           # Latin, Cyrillic, Fullwidth
            'l': ['l', 'ⅼ', 'ɭ', 'ｌ'],      # Latin, Roman numeral, Fullwidth
            'n': ['n', 'ո', 'ｎ'],           # Latin, Armenian, Fullwidth
            'm': ['m', 'ｍ', 'ｍ'],           # Latin, Fullwidth
            's': ['s', 'ѕ', 'ｓ'],           # Latin, Cyrillic, Fullwidth
            't': ['t', 'т', 'τ', 'ｔ'],      # Latin, Cyrillic, Greek, Fullwidth
            'u': ['u', 'υ', 'ｕ'],           # Latin, Greek, Fullwidth
            'v': ['v', 'ѵ', 'ν', 'ｖ'],      # Latin, Cyrillic, Greek, Fullwidth
            'w': ['w', 'ω', 'ｗ'],           # Latin, Greek, Fullwidth
            'z': ['z', 'ｚ'],               # Latin, Fullwidth
            
            # 数字のホモグリフ
            '0': ['0', 'О', 'Ο', '０'],      # Digit, Cyrillic O, Greek O, Fullwidth
            '1': ['1', 'Ⅰ', 'l', '１'],      # Digit, Roman, Latin l, Fullwidth
            '2': ['2', '２'],               # Digit, Fullwidth
            '3': ['3', 'З', '３'],           # Digit, Cyrillic, Fullwidth
            '4': ['4', '４'],               # Digit, Fullwidth
            '5': ['5', 'Ѕ', '５'],           # Digit, Cyrillic, Fullwidth
            '6': ['6', 'б', '６'],           # Digit, Cyrillic, Fullwidth
            '7': ['7', '７'],               # Digit, Fullwidth
            '8': ['8', '８'],               # Digit, Fullwidth
            '9': ['9', '９'],               # Digit, Fullwidth
        }
        
        # 拡張ホモグリフ
        extended_homoglyphs = {
            'A': ['A', 'А', 'Α', 'Ａ'],      # Latin, Cyrillic, Greek, Fullwidth
            'B': ['B', 'В', 'Β', 'Ｂ'],      # Latin, Cyrillic, Greek, Fullwidth
            'C': ['C', 'С', 'Ϲ', 'Ｃ'],      # Latin, Cyrillic, Greek, Fullwidth
            'D': ['D', 'Ｄ'],               # Latin, Fullwidth
            'E': ['E', 'Е', 'Ε', 'Ｅ'],      # Latin, Cyrillic, Greek, Fullwidth
            'F': ['F', 'Ｆ'],               # Latin, Fullwidth
            'G': ['G', 'Ｇ'],               # Latin, Fullwidth
            'H': ['H', 'Н', 'Η', 'Ｈ'],      # Latin, Cyrillic, Greek, Fullwidth
            'I': ['I', 'І', 'Ι', 'Ｉ'],      # Latin, Cyrillic, Greek, Fullwidth
            'J': ['J', 'Ј', 'Ｊ'],           # Latin, Cyrillic, Fullwidth
            'K': ['K', 'К', 'Κ', 'Ｋ'],      # Latin, Cyrillic, Greek, Fullwidth
            'L': ['L', 'Ｌ'],               # Latin, Fullwidth
            'M': ['M', 'М', 'Μ', 'Ｍ'],      # Latin, Cyrillic, Greek, Fullwidth
            'N': ['N', 'Ν', 'Ｎ'],           # Latin, Greek, Fullwidth
            'O': ['O', 'О', 'Ο', 'Ｏ'],      # Latin, Cyrillic, Greek, Fullwidth
            'P': ['P', 'Р', 'Ρ', 'Ｐ'],      # Latin, Cyrillic, Greek, Fullwidth
            'Q': ['Q', 'Ｑ'],               # Latin, Fullwidth
            'R': ['R', 'Ｒ'],               # Latin, Fullwidth
            'S': ['S', 'Ѕ', 'Σ', 'Ｓ'],      # Latin, Cyrillic, Greek, Fullwidth
            'T': ['T', 'Т', 'Τ', 'Ｔ'],      # Latin, Cyrillic, Greek, Fullwidth
            'U': ['U', 'Ｕ'],               # Latin, Fullwidth
            'V': ['V', 'Ｖ'],               # Latin, Fullwidth
            'W': ['W', 'Ｗ'],               # Latin, Fullwidth
            'X': ['X', 'Х', 'Χ', 'Ｘ'],      # Latin, Cyrillic, Greek, Fullwidth
            'Y': ['Y', 'У', 'Υ', 'Ｙ'],      # Latin, Cyrillic, Greek, Fullwidth
            'Z': ['Z', 'Ｚ'],               # Latin, Fullwidth
        }
        
        # 設定に基づいてマップを構築
        if self.config.unicode_coverage == "basic":
            return basic_homoglyphs
        elif self.config.unicode_coverage == "extended":
            combined = {**basic_homoglyphs, **extended_homoglyphs}
            return combined
        else:  # full
            combined = {**basic_homoglyphs, **extended_homoglyphs}
            # 追加の特殊文字
            combined.update(self._get_advanced_homoglyphs())
            return combined
    
    def _get_advanced_homoglyphs(self) -> Dict[str, List[str]]:
        """高度なホモグリフマッピング"""
        return {
            # 特殊記号
            '-': ['-', '‐', '‑', '‒', '–', '—', '－'],  # Various dashes
            '.': ['.', '․', '‧', '。'],                # Various dots
            ',': [',', '‚', '，'],                     # Various commas
            ';': [';', '；'],                          # Semicolons
            ':': [':', '：'],                          # Colons
            '!': ['!', '！'],                          # Exclamations
            '?': ['?', '？'],                          # Questions
            '(': ['(', '（'],                          # Left parentheses
            ')': [')', '）'],                          # Right parentheses
            '[': ['[', '［'],                          # Left brackets
            ']': [']', '］'],                          # Right brackets
            '"': ['"', '"', '"', '＂'],                # Various quotes
            "'": ["'", ''', ''', '＇'],                # Various apostrophes
        }
    
    def _build_confusion_matrix(self) -> Dict[Tuple[str, str], float]:
        """文字混同行列を構築"""
        confusion_matrix = {}
        
        for original, variants in self.homoglyph_map.items():
            for variant in variants[1:]:  # 最初の文字は元の文字なのでスキップ
                # 視覚的類似度スコア（簡易版）
                similarity_score = self._calculate_visual_similarity(original, variant)
                confusion_matrix[(original, variant)] = similarity_score
        
        return confusion_matrix
    
    def _calculate_visual_similarity(self, char1: str, char2: str) -> float:
        """視覚的類似度を計算"""
        # Unicode文字の性質に基づく簡易計算
        if char1 == char2:
            return 1.0
        
        # 文字名の類似性チェック
        try:
            name1 = unicodedata.name(char1, "")
            name2 = unicodedata.name(char2, "")
            
            # 同じベース文字を持つ場合は高いスコア
            if any(common in name1 and common in name2 for common in ["LATIN", "SMALL", "CAPITAL"]):
                return 0.9
        except:
            pass
        
        # デフォルトのスコア
        return 0.7
    
    def apply_silver_speak(self, text: str) -> str:
        """SilverSpeak攻撃を適用"""
        modified_text = ""
        homoglyph_count = 0
        total_chars = len([c for c in text if c.isalnum()])
        target_homoglyphs = int(total_chars * self.config.homoglyph_ratio)
        
        for char in text:
            if (char in self.homoglyph_map and 
                len(self.homoglyph_map[char]) > 1 and
                homoglyph_count < target_homoglyphs and
                random.random() < self.config.intensity):
                
                # ホモグリフを選択
                variants = self.homoglyph_map[char][1:]  # 元の文字を除外
                selected_variant = self._select_best_homoglyph(char, variants)
                modified_text += selected_variant
                homoglyph_count += 1
            else:
                modified_text += char
        
        return modified_text
    
    def _select_best_homoglyph(self, original: str, variants: List[str]) -> str:
        """最適なホモグリフを選択"""
        if not variants:
            return original
        
        # 視覚的類似度に基づいて選択
        best_variant = variants[0]
        best_score = 0.0
        
        for variant in variants:
            if (original, variant) in self.confusion_matrix:
                score = self.confusion_matrix[(original, variant)]
                # 可読性を考慮して調整
                if self.config.preserve_readability:
                    score *= 0.8  # 可読性重視
                
                if score > best_score:
                    best_score = score
                    best_variant = variant
        
        return best_variant
    
    def apply_advanced_silver_speak(self, text: str) -> str:
        """高度なSilverSpeak攻撃"""
        # 段階的ホモグリフ適用
        text = self._apply_strategic_homoglyphs(text)
        text = self._apply_positional_homoglyphs(text)
        text = self._apply_frequency_based_homoglyphs(text)
        
        return text
    
    def _apply_strategic_homoglyphs(self, text: str) -> str:
        """戦略的ホモグリフ適用"""
        # 重要な位置（文頭、単語の開始など）により高い確率でホモグリフを適用
        modified_text = ""
        
        for i, char in enumerate(text):
            is_strategic_position = (
                i == 0 or  # 文頭
                (i > 0 and text[i-1] in ' 　\n') or  # 単語開始
                (i > 0 and text[i-1] in '。、！？')   # 文区切り後
            )
            
            application_probability = (
                self.config.intensity * 1.5 if is_strategic_position 
                else self.config.intensity
            )
            
            if (char in self.homoglyph_map and 
                random.random() < application_probability):
                variants = self.homoglyph_map[char][1:]
                if variants:
                    modified_text += self._select_best_homoglyph(char, variants)
                else:
                    modified_text += char
            else:
                modified_text += char
        
        return modified_text
    
    def _apply_positional_homoglyphs(self, text: str) -> str:
        """位置ベースホモグリフ適用"""
        modified_text = ""
        
        # 文字の位置パターンに基づく適用
        for i, char in enumerate(text):
            # 特定のパターン（例：3文字ごと）でホモグリフを適用
            if (i % 3 == 0 and 
                char in self.homoglyph_map and
                random.random() < self.config.intensity * 0.7):
                
                variants = self.homoglyph_map[char][1:]
                if variants:
                    modified_text += self._select_best_homoglyph(char, variants)
                else:
                    modified_text += char
            else:
                modified_text += char
        
        return modified_text
    
    def _apply_frequency_based_homoglyphs(self, text: str) -> str:
        """頻度ベースホモグリフ適用"""
        # 文字の出現頻度を分析
        char_frequency = {}
        for char in text:
            if char.isalnum():
                char_frequency[char] = char_frequency.get(char, 0) + 1
        
        # 高頻度文字により積極的にホモグリフを適用
        modified_text = ""
        
        for char in text:
            if char in char_frequency:
                frequency_factor = min(2.0, char_frequency[char] / 10)
                application_probability = self.config.intensity * frequency_factor
                
                if (char in self.homoglyph_map and 
                    random.random() < application_probability):
                    variants = self.homoglyph_map[char][1:]
                    if variants:
                        modified_text += self._select_best_homoglyph(char, variants)
                    else:
                        modified_text += char
                else:
                    modified_text += char
            else:
                modified_text += char
        
        return modified_text
    
    def apply_homoglyph_clustering(self, text: str) -> str:
        """ホモグリフクラスタリング攻撃"""
        # 類似文字をクラスターとして扱い、一貫性を保って置換
        modified_text = ""
        char_replacements = {}  # 一貫性のための文字置換マップ
        
        for char in text:
            if char in self.homoglyph_map:
                if char not in char_replacements:
                    # 初回出現時に置換文字を決定
                    if random.random() < self.config.intensity:
                        variants = self.homoglyph_map[char][1:]
                        if variants:
                            char_replacements[char] = self._select_best_homoglyph(char, variants)
                        else:
                            char_replacements[char] = char
                    else:
                        char_replacements[char] = char
                
                modified_text += char_replacements[char]
            else:
                modified_text += char
        
        return modified_text
    
    def analyze_silver_speak_effect(self, original: str, modified: str) -> Dict:
        """SilverSpeak効果を分析"""
        homoglyph_count = 0
        total_chars = 0
        unicode_scripts = set()
        
        for orig_char, mod_char in zip(original, modified):
            if orig_char.isalnum():
                total_chars += 1
                if orig_char != mod_char:
                    homoglyph_count += 1
            
            # Unicode文字体系の分析
            try:
                script = unicodedata.name(mod_char).split()[0]
                unicode_scripts.add(script)
            except:
                pass
        
        return {
            "homoglyph_count": homoglyph_count,
            "homoglyph_ratio": homoglyph_count / max(1, total_chars),
            "unicode_scripts_used": len(unicode_scripts),
            "character_change": len(modified) - len(original),
            "visual_similarity_score": self._calculate_overall_similarity(original, modified),
            "estimated_detection_evasion": min(0.87, homoglyph_count / max(1, total_chars)),
        }
    
    def _calculate_overall_similarity(self, original: str, modified: str) -> float:
        """全体の視覚的類似度を計算"""
        if len(original) == 0:
            return 1.0
        
        similarity_sum = 0.0
        comparison_count = 0
        
        for orig_char, mod_char in zip(original, modified):
            if orig_char.isalnum() and mod_char.isalnum():
                if orig_char == mod_char:
                    similarity_sum += 1.0
                elif (orig_char, mod_char) in self.confusion_matrix:
                    similarity_sum += self.confusion_matrix[(orig_char, mod_char)]
                else:
                    similarity_sum += 0.5  # デフォルト類似度
                comparison_count += 1
        
        return similarity_sum / max(1, comparison_count)


def test_silver_speak():
    """テスト関数"""
    print("🔤 SilverSpeak Attack テスト開始")
    
    # テストテキスト
    test_text = """
    This is a test text for homoglyph attack demonstration.
    これはホモグリフ攻撃のテストテキストです。Visual similarity is key!
    """
    
    # 設定
    config = SilverSpeakConfig(intensity=0.6, homoglyph_ratio=0.3, unicode_coverage="extended")
    attack = SilverSpeak(config)
    
    # 攻撃実行
    basic_attack = attack.apply_silver_speak(test_text)
    advanced_attack = attack.apply_advanced_silver_speak(test_text)
    clustering_attack = attack.apply_homoglyph_clustering(test_text)
    
    # 結果表示
    print(f"\n📝 元テキスト:\n{test_text}")
    print(f"\n🔤 基本SilverSpeak:\n{basic_attack}")
    print(f"\n🚀 高度SilverSpeak:\n{advanced_attack}")
    print(f"\n🎯 クラスタリング攻撃:\n{clustering_attack}")
    
    # 効果分析
    analysis = attack.analyze_silver_speak_effect(test_text, advanced_attack)
    print(f"\n📊 効果分析:")
    for key, value in analysis.items():
        print(f"  {key}: {value}")
    
    # ホモグリフマップの表示
    print(f"\n🗺️  利用可能なホモグリフ数: {len(attack.homoglyph_map)}")


if __name__ == "__main__":
    test_silver_speak() 