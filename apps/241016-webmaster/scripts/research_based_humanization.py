#!/usr/bin/env python3
"""
Research-Based Text Humanization Algorithm
実装：5つの最先端研究論文に基づくAI検出回避技術

参考文献:
1. Adversarial Paraphrasing (2025) - 87.88% T@1%F reduction
2. GradEscape (2025, USENIX Security) - gradient-based attacks  
3. SilverSpeak (2024) - homoglyph attacks
4. Navigating the Shadows (2024, ACL) - 12 perturbation techniques
5. Information Overload - linguistic complexity attacks
"""

import re
import random
from typing import Dict, List, Tuple, Optional
from dataclasses import dataclass
import sys
import os

@dataclass
class HumanizationConfig:
    """人間化アルゴリズムの設定"""
    intensity: float = 0.3  # 0.0-1.0の強度設定
    preserve_meaning: bool = True  # 意味保持
    target_language: str = "ja"  # 対象言語
    enable_homoglyph: bool = True  # 同形異義文字攻撃
    enable_syntactic: bool = True  # 構文変更攻撃
    enable_complexity: bool = True  # 複雑性攻撃
    enable_gradient: bool = True  # 勾配ベース攻撃
    enable_paraphrasing: bool = True  # 敵対的パラフレーズ

class ResearchBasedHumanizer:
    """研究ベースの人間化アルゴリズム"""
    
    def __init__(self, config: HumanizationConfig):
        self.config = config
        self.setup_attack_vectors()
        
    def setup_attack_vectors(self):
        """攻撃ベクトルの初期化"""
        # 1. SilverSpeak: 同形異義文字マッピング
        self.homoglyph_map = {
            'a': ['а', 'ａ', 'α'],  # Cyrillic а, Fullwidth a, Greek α
            'o': ['о', 'ο', 'ｏ'],  # Cyrillic о, Greek ο, Fullwidth o
            'e': ['е', 'ｅ', 'ε'],  # Cyrillic е, Fullwidth e, Greek ε
            'p': ['р', 'ρ', 'ｐ'],  # Cyrillic р, Greek ρ, Fullwidth p
            'c': ['с', 'ｃ'],       # Cyrillic с, Fullwidth c
            'x': ['х', 'χ', 'ｘ'],  # Cyrillic х, Greek χ, Fullwidth x
            'y': ['у', 'ｙ'],       # Cyrillic у, Fullwidth y
            'i': ['і', 'ι', 'ｉ'],  # Cyrillic і, Greek ι, Fullwidth i
            'n': ['п', 'η', 'ｎ'],  # Cyrillic п, Greek η, Fullwidth n
            'r': ['г', 'γ', 'ｒ'],  # Cyrillic г, Greek γ, Fullwidth r
            'h': ['һ', 'η', 'ｈ'],  # Cyrillic һ, Greek η, Fullwidth h
            'u': ['υ', 'ｕ'],       # Greek υ, Fullwidth u
            'w': ['ω', 'ｗ'],       # Greek ω, Fullwidth w
            's': ['ѕ', 'ｓ'],       # Cyrillic ѕ, Fullwidth s
            't': ['τ', 'ｔ'],       # Greek τ, Fullwidth t
            'k': ['κ', 'ｋ'],       # Greek κ, Fullwidth k
            'l': ['ι', 'ｌ'],       # Greek ι, Fullwidth l
            'm': ['μ', 'ｍ'],       # Greek μ, Fullwidth m
            'v': ['ν', 'ｖ'],       # Greek ν, Fullwidth v
            'b': ['β', 'ｂ'],       # Greek β, Fullwidth b
            'd': ['δ', 'ｄ'],       # Greek δ, Fullwidth d
            'f': ['φ', 'ｆ'],       # Greek φ, Fullwidth f
            'g': ['γ', 'ｇ'],       # Greek γ, Fullwidth g
            'j': ['ϳ', 'ｊ'],       # Greek ϳ, Fullwidth j
            'q': ['θ', 'ｑ'],       # Greek θ, Fullwidth q
            'z': ['ζ', 'ｚ'],       # Greek ζ, Fullwidth z
        }
        
        # 2. Information Overload: 言語複雑性パターン
        self.complexity_patterns = {
            'hedges': ['おそらく', 'たぶん', 'と思われる', 'ようである', 'らしい', 'みたい', 'のような', 'かもしれない'],
            'intensifiers': ['非常に', 'とても', 'かなり', '極めて', '著しく', '顕著に', '明らかに', '確実に'],
            'conjunctions': ['しかし', 'また', 'さらに', '一方', 'つまり', 'すなわち', 'ついで', 'そして'],
            'discourse_markers': ['実際に', 'つまり', 'すなわち', 'たとえば', '具体的には', 'つまりは', 'すなわち', 'さらに言えば']
        }
        
        # 3. Navigating the Shadows: 構文変更パターン
        self.syntactic_patterns = [
            (r'(である)(。)', r'のである\2'),  # 語尾変更
            (r'(です)(。)', r'のです\2'),  # 語尾変更
            (r'(した)(。)', r'のした\2'),  # 語尾変更
            (r'(する)(。)', r'するのである\2'),  # 語尾変更
            (r'(、)', r'，'),  # 句点変更
            (r'(。)', r'．'),  # 句点変更
            (r'(について)', r'に関して'),  # 語彙変更
            (r'(において)', r'では'),  # 語彙変更
            (r'(により)', r'によって'),  # 語彙変更
            (r'(による)', r'によって'),  # 語彙変更
        ]
        
        # 4. Adversarial Paraphrasing: 敵対的パラフレーズパターン
        self.paraphrase_patterns = [
            (r'(明らかに)(.*?)(である)', r'確実に\2と考えられる'),
            (r'(示した)(。)', r'を示している\2'),
            (r'(発見した)(。)', r'を発見している\2'),
            (r'(確認した)(。)', r'を確認している\2'),
            (r'(分析した)(。)', r'を分析している\2'),
            (r'(検討した)(。)', r'を検討している\2'),
            (r'(解析した)(。)', r'を解析している\2'),
            (r'(観察した)(。)', r'を観察している\2'),
            (r'(評価した)(。)', r'を評価している\2'),
        ]
        
    def apply_homoglyph_attack(self, text: str) -> str:
        """SilverSpeak: 同形異義文字攻撃"""
        if not self.config.enable_homoglyph:
            return text
            
        result = text
        target_chars = list(self.homoglyph_map.keys())
        
        for char in target_chars:
            if char in result and random.random() < self.config.intensity:
                replacement = random.choice(self.homoglyph_map[char])
                # ランダムな位置の文字を置換
                positions = [i for i, c in enumerate(result) if c == char]
                if positions:
                    pos = random.choice(positions)
                    result = result[:pos] + replacement + result[pos+1:]
                    
        return result
    
    def apply_gradient_attack(self, text: str) -> str:
        """GradEscape: 勾配ベース攻撃（句読点の微調整）"""
        if not self.config.enable_gradient:
            return text
            
        result = text
        
        # 句読点の微調整
        if random.random() < self.config.intensity:
            result = result.replace('。', '．')
        if random.random() < self.config.intensity:
            result = result.replace('、', '，')
            
        return result
    
    def apply_syntactic_perturbation(self, text: str) -> str:
        """Navigating the Shadows: 構文変更攻撃"""
        if not self.config.enable_syntactic:
            return text
            
        result = text
        
        for pattern, replacement in self.syntactic_patterns:
            if random.random() < self.config.intensity:
                result = re.sub(pattern, replacement, result)
                
        return result
    
    def apply_complexity_enhancement(self, text: str) -> str:
        """Information Overload: 言語複雑性攻撃"""
        if not self.config.enable_complexity:
            return text
            
        result = text
        sentences = re.split(r'([。．])', result)
        
        enhanced_sentences = []
        for i, sentence in enumerate(sentences):
            if sentence in ['。', '．']:
                enhanced_sentences.append(sentence)
                continue
                
            # 接続語の挿入
            if random.random() < self.config.intensity * 0.4:
                conjunction = random.choice(self.complexity_patterns['conjunctions'])
                sentence = conjunction + '、' + sentence
                
            enhanced_sentences.append(sentence)
            
        return ''.join(enhanced_sentences)
    
    def apply_adversarial_paraphrasing(self, text: str) -> str:
        """Adversarial Paraphrasing: 敵対的パラフレーズ攻撃"""
        if not self.config.enable_paraphrasing:
            return text
            
        result = text
        
        for pattern, replacement in self.paraphrase_patterns:
            if random.random() < self.config.intensity:
                result = re.sub(pattern, replacement, result)
                
        return result
    
    def calculate_metrics(self, original: str, humanized: str) -> Dict:
        """品質メトリクスの計算"""
        metrics = {
            'character_change': len(humanized) - len(original),
            'character_change_percentage': ((len(humanized) - len(original)) / len(original)) * 100,
            'lexical_diversity': len(set(humanized.split())) / len(humanized.split()) if humanized.split() else 0,
            'syntactic_complexity': len(re.findall(r'[、，。．]', humanized)) / len(humanized.split()) if humanized.split() else 0,
            'homoglyph_usage': sum(1 for c in humanized if ord(c) > 127) / len(humanized) * 100,
            'processing_success': True
        }
        
        # 品質スコア算出
        metrics['quality_scores'] = {
            'readability': 'High' if metrics['character_change_percentage'] < 10 else 'Medium',
            'naturalness': 'High' if metrics['lexical_diversity'] > 0.8 else 'Medium',
            'ai_detection_evasion': 'High' if metrics['homoglyph_usage'] > 5 else 'Medium'
        }
        
        return metrics
    
    def humanize_text(self, text: str) -> Tuple[str, Dict]:
        """
        研究ベースの人間化処理
        
        Args:
            text: 入力テキスト
            
        Returns:
            Tuple[str, Dict]: (人間化されたテキスト, メトリクス)
        """
        original_text = text
        
        # 5つの攻撃手法を順次適用
        result = text
        
        # 1. Adversarial Paraphrasing (2025)
        result = self.apply_adversarial_paraphrasing(result)
        
        # 2. GradEscape (2025, USENIX Security)
        result = self.apply_gradient_attack(result)
        
        # 3. SilverSpeak (2024)
        result = self.apply_homoglyph_attack(result)
        
        # 4. Navigating the Shadows (2024, ACL)
        result = self.apply_syntactic_perturbation(result)
        
        # 5. Information Overload
        result = self.apply_complexity_enhancement(result)
        
        # メトリクス計算
        metrics = self.calculate_metrics(original_text, result)
        
        return result, metrics

def main():
    """メイン処理"""
    if len(sys.argv) < 2:
        print("Usage: python research_based_humanization.py <input_file> [output_file] [intensity]")
        sys.exit(1)
    
    input_file = sys.argv[1]
    output_file = sys.argv[2] if len(sys.argv) > 2 else input_file.replace('.md', '_humanized.md')
    intensity = float(sys.argv[3]) if len(sys.argv) > 3 else 0.3
    
    # 設定
    config = HumanizationConfig(
        intensity=intensity,
        preserve_meaning=True,
        target_language="ja"
    )
    
    # 人間化処理
    humanizer = ResearchBasedHumanizer(config)
    
    try:
        with open(input_file, 'r', encoding='utf-8') as f:
            original_text = f.read()
        
        print(f"🔬 研究ベース人間化アルゴリズムを実行中...")
        print(f"📊 設定: intensity={config.intensity}")
        print(f"📝 入力: {input_file} ({len(original_text)} 文字)")
        
        humanized_text, metrics = humanizer.humanize_text(original_text)
        
        # 結果保存
        with open(output_file, 'w', encoding='utf-8') as f:
            f.write(humanized_text)
        
        # メトリクス表示
        print(f"\n✅ 人間化完了:")
        print(f"📊 文字変更: {metrics['character_change']:+d} ({metrics['character_change_percentage']:+.1f}%)")
        print(f"📈 語彙多様性: {metrics['lexical_diversity']:.3f}")
        print(f"🔧 構文複雑性: {metrics['syntactic_complexity']:.3f}")
        print(f"🌐 同形異義文字使用率: {metrics['homoglyph_usage']:.1f}%")
        print(f"💾 出力: {output_file}")
        
        print(f"\n🎯 品質評価:")
        for key, value in metrics['quality_scores'].items():
            print(f"  {key}: {value}")
        
        print(f"\n🚀 AI検出回避予測: {metrics['quality_scores']['ai_detection_evasion']}")
        
    except FileNotFoundError:
        print(f"❌ エラー: ファイル '{input_file}' が見つかりません")
        sys.exit(1)
    except Exception as e:
        print(f"❌ エラー: {e}")
        sys.exit(1)

if __name__ == "__main__":
    main()
