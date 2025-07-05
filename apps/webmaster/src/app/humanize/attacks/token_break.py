"""
TokenBreak Attack: トークン化レベル回避攻撃

この攻撃手法は、テキストのトークン化プロセスに介入することで
AI検出器の分析を回避する手法です。見た目は同じでも、
トークン化が異なる表現を生成します。

主な手法:
1. 単語分割パターンの変更
2. ゼロ幅文字の挿入
3. Unicode正規化の操作
4. サブワードトークン化の攻撃
5. 文字エンコーディングの変更
6. 空白・改行パターンの操作
"""

import random
import re
import unicodedata
from typing import Dict, List, Tuple, Any
import string


class TokenBreakAttack:
    """TokenBreak攻撃の実装クラス"""
    
    def __init__(self, intensity: float = 0.3):
        """
        TokenBreak攻撃を初期化
        
        Args:
            intensity: 攻撃の強度（0.0-1.0）
        """
        self.intensity = intensity
        
        # ゼロ幅文字の定義
        self.zero_width_chars = [
            '\u200B',  # Zero Width Space
            '\u200C',  # Zero Width Non-Joiner
            '\u200D',  # Zero Width Joiner
            '\u200E',  # Left-to-Right Mark
            '\u200F',  # Right-to-Left Mark
            '\u2060',  # Word Joiner
            '\uFEFF',  # Zero Width No-Break Space
        ]
        
        # Unicode変換パターン
        self.unicode_variants = {
            'a': ['а', 'ａ', 'ã', 'à', 'á'],  # キリル文字・全角・アクセント付き
            'e': ['е', 'ｅ', 'é', 'è', 'ê'],
            'o': ['о', 'ｏ', 'ó', 'ò', 'ô'],
            'i': ['і', 'ｉ', 'í', 'ì', 'î'],
            'u': ['ս', 'ｕ', 'ú', 'ù', 'û'],
            'c': ['с', 'ｃ', 'ç'],
            'p': ['р', 'ｐ'],
            'x': ['х', 'ｘ'],
            'y': ['у', 'ｙ', 'ý'],
            'n': ['ｎ', 'ñ'],
            'r': ['ｒ'],
            's': ['ѕ', 'ｓ'],
            't': ['ｔ'],
            'm': ['ｍ'],
            'h': ['һ', 'ｈ'],
            'k': ['κ', 'ｋ'],
            'v': ['ν', 'ｖ'],
            'w': ['ｗ'],
            'l': ['ｌ'],
            'f': ['ｆ'],
            'g': ['ｇ'],
            'j': ['ｊ'],
            'z': ['ｚ'],
            'b': ['Ь', 'ｂ'],
            'd': ['ｄ'],
        }
    
    def attack(self, text: str, strategy: str = "mixed") -> Dict[str, Any]:
        """
        TokenBreak攻撃を実行
        
        Args:
            text: 攻撃対象のテキスト
            strategy: 攻撃戦略 ("zero_width", "unicode", "split", "combine", "encoding", "mixed")
        
        Returns:
            攻撃結果の辞書
        """
        original_text = text
        
        if strategy == "zero_width":
            attacked_text = self._zero_width_attack(text)
        elif strategy == "unicode":
            attacked_text = self._unicode_attack(text)
        elif strategy == "mixed":
            attacked_text = self._mixed_attack(text)
        else:
            attacked_text = self._mixed_attack(text)
        
        # 効果分析
        analysis = self._analyze_effectiveness(original_text, attacked_text)
        
        return {
            "original_text": original_text,
            "attacked_text": attacked_text,
            "strategy": strategy,
            "intensity": self.intensity,
            "analysis": analysis,
            "attack_type": "TokenBreak",
            "invisible_changes": self._count_invisible_changes(original_text, attacked_text)
        }
    
    def _zero_width_attack(self, text: str) -> str:
        """ゼロ幅文字挿入攻撃"""
        words = text.split()
        attacked_words = []
        
        for word in words:
            if random.random() < self.intensity:
                # 単語内の任意の位置にゼロ幅文字を挿入
                if len(word) > 2:
                    pos = random.randint(1, len(word) - 1)
                    zero_char = random.choice(self.zero_width_chars)
                    word = word[:pos] + zero_char + word[pos:]
            attacked_words.append(word)
        
        return ' '.join(attacked_words)
    
    def _unicode_attack(self, text: str) -> str:
        """Unicode文字置換攻撃"""
        result = []
        
        for char in text:
            if char.lower() in self.unicode_variants and random.random() < self.intensity:
                # 類似のUnicode文字に置換
                variants = self.unicode_variants[char.lower()]
                replacement = random.choice(variants)
                # 大文字小文字を保持
                if char.isupper():
                    replacement = replacement.upper()
                result.append(replacement)
            else:
                result.append(char)
        
        return ''.join(result)
    
    def _mixed_attack(self, text: str) -> str:
        """複合攻撃（複数手法の組み合わせ）"""
        # 段階的に複数の攻撃を適用
        result = text
        
        strategies = ["zero_width", "unicode"]
        
        # インテンシティに基づいて攻撃数を決定
        num_attacks = max(1, int(self.intensity * len(strategies)))
        selected_strategies = random.sample(strategies, num_attacks)
        
        for strategy in selected_strategies:
            if strategy == "zero_width":
                result = self._zero_width_attack(result)
            elif strategy == "unicode":
                result = self._unicode_attack(result)
        
        return result
    
    def _analyze_effectiveness(self, original: str, attacked: str) -> Dict[str, Any]:
        """攻撃効果の分析"""
        return {
            "visual_similarity": self._calculate_visual_similarity(original, attacked),
            "tokenization_difference": self._calculate_tokenization_diff(original, attacked),
            "invisible_char_count": self._count_invisible_chars(attacked),
            "unicode_complexity": self._calculate_unicode_complexity(attacked)
        }
    
    def _calculate_visual_similarity(self, original: str, attacked: str) -> float:
        """視覚的類似度を計算"""
        # 見た目の類似度（ゼロ幅文字を除去して比較）
        clean_original = self._remove_invisible_chars(original)
        clean_attacked = self._remove_invisible_chars(attacked)
        
        if clean_original == clean_attacked:
            return 1.0
        
        # レーベンシュタイン距離ベースの類似度
        return 1.0 - (self._levenshtein_distance(clean_original, clean_attacked) / 
                     max(len(clean_original), len(clean_attacked)))
    
    def _calculate_tokenization_diff(self, original: str, attacked: str) -> float:
        """トークン化の違いを計算"""
        # 簡単なトークン化（空白分割）での違い
        orig_tokens = original.split()
        attack_tokens = attacked.split()
        
        if len(orig_tokens) == 0:
            return 0.0
        
        return abs(len(attack_tokens) - len(orig_tokens)) / len(orig_tokens)
    
    def _count_invisible_chars(self, text: str) -> int:
        """不可視文字の数をカウント"""
        count = 0
        for char in text:
            if char in self.zero_width_chars or unicodedata.category(char) in ['Mn', 'Cf']:
                count += 1
        return count
    
    def _count_invisible_changes(self, original: str, attacked: str) -> int:
        """不可視な変更の数をカウント"""
        return self._count_invisible_chars(attacked) - self._count_invisible_chars(original)
    
    def _calculate_unicode_complexity(self, text: str) -> float:
        """Unicode複雑度を計算"""
        categories = set()
        
        for char in text:
            categories.add(unicodedata.category(char))
        
        return len(categories) / len(text) if text else 0
    
    def _remove_invisible_chars(self, text: str) -> str:
        """不可視文字を除去"""
        result = []
        for char in text:
            if char not in self.zero_width_chars and unicodedata.category(char) not in ['Mn', 'Cf']:
                result.append(char)
        return ''.join(result)
    
    def _levenshtein_distance(self, s1: str, s2: str) -> int:
        """レーベンシュタイン距離を計算"""
        if len(s1) < len(s2):
            return self._levenshtein_distance(s2, s1)
        
        if len(s2) == 0:
            return len(s1)
        
        previous_row = list(range(len(s2) + 1))
        for i, c1 in enumerate(s1):
            current_row = [i + 1]
            for j, c2 in enumerate(s2):
                insertions = previous_row[j + 1] + 1
                deletions = current_row[j] + 1
                substitutions = previous_row[j] + (c1 != c2)
                current_row.append(min(insertions, deletions, substitutions))
            previous_row = current_row
        
        return previous_row[-1]


def test_token_break_attack():
    """TokenBreak攻撃のテスト関数"""
    print("=== TokenBreak Attack Test ===")
    
    attack = TokenBreakAttack(intensity=0.5)
    
    test_texts = [
        "This is a test sentence for tokenization attack.",
        "The quick brown fox jumps over the lazy dog.",
        "AI detection systems analyze text patterns."
    ]
    
    strategies = ["zero_width", "unicode", "mixed"]
    
    for text in test_texts:
        print(f"\n--- Original: {text} ---")
        
        for strategy in strategies:
            result = attack.attack(text, strategy)
            print(f"\n{strategy.upper()} Strategy:")
            print(f"Attacked: {result['attacked_text']}")
            print(f"Visual Similarity: {result['analysis']['visual_similarity']:.3f}")
            print(f"Tokenization Diff: {result['analysis']['tokenization_difference']:.3f}")
            print(f"Invisible Chars: {result['analysis']['invisible_char_count']}")
        
        print("-" * 50)


if __name__ == "__main__":
    test_token_break_attack() 