#!/usr/bin/env python3
"""
Advanced Humanization Script
91% AI detection rate を 20% まで下げるための高度な人間化戦略
"""

import re
import random
import json
from typing import List, Dict, Tuple

class AdvancedHumanizer:
    def __init__(self):
        # より積極的な人間化パターン
        self.academic_personal_phrases = [
            "実際のところ、", "興味深いことに、", "驚くべきことに、",
            "私たちの研究では、", "これまでの経験から、", "実験を通じて、",
            "研究室での観察によると、", "データを詳しく見ると、", "正直に言うと、",
            "この結果は予想外で、", "当初の仮説とは異なり、", "実際に確認してみると、"
        ]
        
        self.conversational_transitions = [
            "ところで、", "そういえば、", "ちなみに、", "なお、",
            "一方で、", "他方、", "むしろ、", "さらに言うと、",
            "要するに、", "つまるところ、", "結局のところ、", "端的に言えば、"
        ]
        
        self.uncertainty_markers = [
            "おそらく", "恐らく", "〜かもしれない", "〜と思われる",
            "〜の可能性が高い", "〜と考えられる", "〜かもしれません",
            "〜のようだ", "〜らしい", "〜と見られる"
        ]
        
        self.informal_academic_terms = {
            "明らかにした": ["突き止めた", "発見した", "見つけ出した", "解明した"],
            "示された": ["分かった", "明らかになった", "判明した", "証明された"],
            "確認された": ["確かめられた", "検証された", "実証された", "認められた"],
            "可能にした": ["実現した", "達成した", "成功した", "可能とした"],
            "機能": ["働き", "役割", "効果", "作用"],
            "技術": ["手法", "方法", "アプローチ", "技法"],
            "解析": ["分析", "調査", "検討", "研究"],
            "特定": ["同定", "識別", "発見", "特定"],
        }
        
        self.sentence_starters = [
            "実は、", "面白いことに、", "注目すべきは、", "特筆すべきは、",
            "重要なポイントとして、", "ここで重要なのは、", "最も興味深いのは、",
            "意外なことに、", "驚いたことに、", "予想通り、", "案の定、"
        ]
        
        # 文章の自然な不規則性を作るためのパターン
        self.natural_irregularities = [
            ("、", "。", 0.1),  # 句読点の変更
            ("である", "だ", 0.3),  # 語尾の変更
            ("〜ている", "〜てる", 0.2),  # 縮約形
            ("〜ということ", "〜こと", 0.4),  # 冗長性の削減
        ]

    def add_personal_voice(self, text: str) -> str:
        """研究者の個人的な声を追加"""
        sentences = text.split('。')
        result = []
        
        for i, sentence in enumerate(sentences):
            if not sentence.strip():
                continue
                
            # 20%の確率で個人的なフレーズを追加
            if random.random() < 0.2:
                personal_phrase = random.choice(self.academic_personal_phrases)
                sentence = personal_phrase + sentence
            
            # 15%の確率で会話的な遷移を追加
            if random.random() < 0.15 and i > 0:
                transition = random.choice(self.conversational_transitions)
                sentence = transition + sentence
            
            # 10%の確率で不確実性マーカーを追加
            if random.random() < 0.1:
                uncertainty = random.choice(self.uncertainty_markers)
                sentence = sentence.replace("である", uncertainty)
                sentence = sentence.replace("だ", uncertainty)
            
            result.append(sentence)
        
        return '。'.join(result)

    def vary_vocabulary(self, text: str) -> str:
        """語彙のバリエーションを増やす"""
        result = text
        
        for formal_term, alternatives in self.informal_academic_terms.items():
            if formal_term in result:
                # 50%の確率で代替語に置換
                if random.random() < 0.5:
                    alternative = random.choice(alternatives)
                    result = result.replace(formal_term, alternative, 1)
        
        return result

    def add_natural_irregularities(self, text: str) -> str:
        """自然な不規則性を追加"""
        result = text
        
        for pattern, replacement, probability in self.natural_irregularities:
            if random.random() < probability:
                result = result.replace(pattern, replacement, 1)
        
        return result

    def add_sentence_variety(self, text: str) -> str:
        """文章の多様性を追加"""
        sentences = text.split('。')
        result = []
        
        for i, sentence in enumerate(sentences):
            if not sentence.strip():
                continue
            
            # 15%の確率で文の開始を変更
            if random.random() < 0.15:
                starter = random.choice(self.sentence_starters)
                sentence = starter + sentence
            
            # 文の長さを自然にバラつかせる
            if len(sentence) > 100 and random.random() < 0.3:
                # 長い文を分割
                mid_point = len(sentence) // 2
                split_point = sentence.find('、', mid_point)
                if split_point != -1:
                    part1 = sentence[:split_point]
                    part2 = sentence[split_point+1:]
                    result.extend([part1, part2])
                    continue
            
            result.append(sentence)
        
        return '。'.join(result)

    def add_emotional_expressions(self, text: str) -> str:
        """感情的な表現を追加"""
        # 研究に対する感情的な反応を追加
        emotional_patterns = [
            (r'明らかになった', '明らかになった（これは本当に驚きだった）'),
            (r'示された', '示された（予想以上の結果で）'),
            (r'成功した', '成功した（正直、うまくいくか不安だったが）'),
            (r'発見された', '発見された（これは大きな発見だと思う）'),
        ]
        
        result = text
        for pattern, replacement in emotional_patterns:
            if random.random() < 0.1:  # 10%の確率で置換
                result = re.sub(pattern, replacement, result, count=1)
        
        return result

    def add_colloquial_elements(self, text: str) -> str:
        """口語的な要素を追加"""
        colloquial_replacements = {
            "非常に": "とても",
            "極めて": "かなり",
            "著しく": "大幅に",
            "顕著に": "はっきりと",
            "詳細に": "詳しく",
            "複雑な": "複雑で",
            "重要な": "大切な",
            "特異的": "独特な",
        }
        
        result = text
        for formal, informal in colloquial_replacements.items():
            if random.random() < 0.4:  # 40%の確率で置換
                result = result.replace(formal, informal, 1)
        
        return result

    def humanize_text(self, text: str) -> str:
        """包括的な人間化処理"""
        # 段階的に人間化を適用
        result = text
        
        # 1. 個人的な声を追加
        result = self.add_personal_voice(result)
        
        # 2. 語彙のバリエーション
        result = self.vary_vocabulary(result)
        
        # 3. 自然な不規則性
        result = self.add_natural_irregularities(result)
        
        # 4. 文章の多様性
        result = self.add_sentence_variety(result)
        
        # 5. 感情的な表現
        result = self.add_emotional_expressions(result)
        
        # 6. 口語的な要素
        result = self.add_colloquial_elements(result)
        
        return result

def main():
    # 元のテキストを読み込み
    with open('_draft/humanize/ai-text.md', 'r', encoding='utf-8') as f:
        original_text = f.read()
    
    # 高度な人間化を適用
    humanizer = AdvancedHumanizer()
    humanized_text = humanizer.humanize_text(original_text)
    
    # 結果を保存
    with open('_draft/humanize/ultra-humanized-text.md', 'w', encoding='utf-8') as f:
        f.write(humanized_text)
    
    print("高度な人間化が完了しました！")
    print("出力ファイル: _draft/humanize/ultra-humanized-text.md")

if __name__ == "__main__":
    main() 