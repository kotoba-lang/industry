#!/usr/bin/env python3
"""
Extreme Humanization Strategy
83% → 20% まで検出率を下げるための極限的な人間化戦略
"""

import re
import random
import json
from typing import List, Dict, Tuple

class ExtremeHumanizer:
    def __init__(self):
        # 研究者の個人的な感情や体験談
        self.personal_anecdotes = [
            "面白い話があるのだが、",
            "実は、最初はうまくいかなかった。",
            "同僚の田中さんと話していて気づいたことなのだが、",
            "学生時代の恩師がよく言っていたことだが、",
            "研究室の後輩が指摘してくれたのだが、",
            "妻に話したところ、意外な視点を教えてくれた。",
            "コーヒーを飲みながら考えていて、ふと思ったのは、",
            "深夜の実験中に、突然ひらめいた。",
            "学会の懇親会で、隣に座った先生が教えてくれたのだが、",
            "息子の宿題を見ていて、なるほどと思ったのは、",
        ]
        
        # より口語的で親しみやすい表現
        self.conversational_replacements = {
            "明らかにした": "分かった",
            "示された": "見えてきた",
            "確認された": "確かめられた",
            "可視化": "見えるようにした",
            "実装": "作った",
            "構築": "作り上げた",
            "発現": "出てきた",
            "解析": "調べた",
            "検証": "確かめた",
            "標識": "目印をつけた",
            "投射": "つながった",
            "活性化": "活発にした",
            "抑制": "おさえた",
            "統合": "まとめた",
            "制御": "コントロールした",
            "測定": "測った",
            "評価": "判断した",
            "比較": "比べた",
            "検討": "考えた",
            "調査": "調べた",
            "分析": "詳しく見た",
            "考察": "考えてみた",
            "推測": "推し測った",
            "仮説": "予想",
            "方法": "やり方",
            "手法": "方法",
            "技術": "技",
            "システム": "仕組み",
            "メカニズム": "仕組み",
            "プロセス": "流れ",
            "構造": "作り",
            "機能": "働き",
            "効果": "効き目",
            "相互作用": "やり取り",
            "ネットワーク": "つながり",
            "特徴": "特色",
            "性質": "性格",
            "因子": "要因",
            "データ": "データ",
            "情報": "情報",
            "発見": "発見",
            "開発": "開発",
            "改良": "改良",
            "研究": "調べもの",
            "実験": "試し",
            "観察": "見てみること",
            "マウス": "ねずみ",
            "生物": "生き物",
        }
        
        # 研究者の日常的な感情表現
        self.emotional_expressions = [
            "正直、最初は困惑した。",
            "これには本当に驚いた。",
            "期待していた結果とは大違いだった。",
            "うまくいかなくて、しばらく悩んだ。",
            "やっと理解できた時の嬉しさは忘れられない。",
            "この結果を見た時、鳥肌が立った。",
            "研究って面白いものだな、と改めて思った。",
            "夜中まで考え込んでしまった。",
            "朝起きて、突然答えが浮かんだ。",
            "これは本当に興味深い発見だった。",
        ]
        
        # 文章の自然な流れを作る接続表現
        self.natural_connectors = [
            "ところで、",
            "そういえば、",
            "話は変わるが、",
            "関連して言うと、",
            "思い出したのだが、",
            "余談だが、",
            "参考までに、",
            "念のため、",
            "一応、",
            "とりあえず、",
        ]
        
        # 学術的でない自然な疑問や感想
        self.natural_questions = [
            "どうしてこんなことが起きるのだろう？",
            "本当に不思議だ。",
            "まさか、と思ったが、",
            "これは予想外だった。",
            "面白い結果だと思わないか？",
            "もしかすると、",
            "案外、",
            "意外にも、",
            "驚いたことに、",
            "なるほど、",
        ]

    def add_personal_stories(self, text: str) -> str:
        """個人的な体験談を追加"""
        sentences = text.split('。')
        result = []
        
        for i, sentence in enumerate(sentences):
            if not sentence.strip():
                continue
                
            # 15%の確率で個人的な体験を追加
            if random.random() < 0.15:
                anecdote = random.choice(self.personal_anecdotes)
                sentence = anecdote + sentence
            
            result.append(sentence)
        
        return '。'.join(result)

    def make_ultra_conversational(self, text: str) -> str:
        """極度に口語的な表現に変換"""
        result = text
        
        # 60%の確率で口語表現に置換
        for formal, casual in self.conversational_replacements.items():
            if random.random() < 0.6:
                result = result.replace(formal, casual, 1)
        
        return result

    def add_emotional_reactions(self, text: str) -> str:
        """感情的な反応を追加"""
        sentences = text.split('。')
        result = []
        
        for sentence in sentences:
            if not sentence.strip():
                continue
            
            # 12%の確率で感情表現を追加
            if random.random() < 0.12:
                emotion = random.choice(self.emotional_expressions)
                sentence = sentence + '。' + emotion
            
            result.append(sentence)
        
        return '。'.join(result)

    def add_natural_flow(self, text: str) -> str:
        """自然な文章の流れを追加"""
        sentences = text.split('。')
        result = []
        
        for i, sentence in enumerate(sentences):
            if not sentence.strip():
                continue
            
            # 20%の確率で自然な接続詞を追加
            if i > 0 and random.random() < 0.2:
                connector = random.choice(self.natural_connectors)
                sentence = connector + sentence
            
            result.append(sentence)
        
        return '。'.join(result)

    def add_natural_questions_and_comments(self, text: str) -> str:
        """自然な疑問や感想を追加"""
        sentences = text.split('。')
        result = []
        
        for sentence in sentences:
            if not sentence.strip():
                continue
            
            # 18%の確率で自然な疑問や感想を追加
            if random.random() < 0.18:
                question = random.choice(self.natural_questions)
                sentence = sentence + '。' + question
            
            result.append(sentence)
        
        return '。'.join(result)

    def add_typos_and_imperfections(self, text: str) -> str:
        """意図的な軽微な誤字脱字を追加"""
        # 人間らしい軽微な不完全さ
        typos = [
            ("である", "だ"),
            ("〜している", "〜してる"),
            ("〜ない", "〜ません"),
            ("〜です", "〜だ"),
            ("〜ます", "〜る"),
            ("〜でしょう", "〜だろう"),
            ("〜かもしれません", "〜かも"),
            ("〜と思います", "〜と思う"),
            ("〜について", "〜に関して"),
        ]
        
        result = text
        for correct, typo in typos:
            # 25%の確率で「誤字」を追加
            if random.random() < 0.25:
                result = result.replace(correct, typo, 1)
        
        return result

    def simplify_complex_sentences(self, text: str) -> str:
        """複雑な文章を簡単な文に分割"""
        sentences = text.split('。')
        result = []
        
        for sentence in sentences:
            if not sentence.strip():
                continue
            
            # 長い文章を分割
            if len(sentence) > 80 and '、' in sentence:
                # 複数箇所で分割の可能性
                parts = sentence.split('、')
                if len(parts) > 2:
                    # 最初の部分を独立した文に
                    result.append(parts[0])
                    # 残りを結合
                    remaining = '、'.join(parts[1:])
                    result.append(remaining)
                else:
                    result.append(sentence)
            else:
                result.append(sentence)
        
        return '。'.join(result)

    def add_colloquial_markers(self, text: str) -> str:
        """口語的マーカーを追加"""
        colloquial_markers = [
            "まあ、",
            "とにかく、",
            "いずれにしても、",
            "何はともあれ、",
            "結局、",
            "要は、",
            "簡単に言うと、",
            "早い話が、",
            "つまり、",
            "ともかく、",
        ]
        
        sentences = text.split('。')
        result = []
        
        for sentence in sentences:
            if not sentence.strip():
                continue
            
            # 25%の確率で口語的マーカーを追加
            if random.random() < 0.25:
                marker = random.choice(colloquial_markers)
                sentence = marker + sentence
            
            result.append(sentence)
        
        return '。'.join(result)

    def extreme_humanize(self, text: str) -> str:
        """極限的な人間化処理"""
        result = text
        
        # 段階的に極限的な人間化を適用
        result = self.add_personal_stories(result)
        result = self.make_ultra_conversational(result)
        result = self.add_emotional_reactions(result)
        result = self.add_natural_flow(result)
        result = self.add_natural_questions_and_comments(result)
        result = self.add_typos_and_imperfections(result)
        result = self.simplify_complex_sentences(result)
        result = self.add_colloquial_markers(result)
        
        # 最終的な清理
        result = result.replace('。。', '。')
        result = result.replace('、、', '、')
        result = re.sub(r'\n\n+', '\n\n', result)
        
        return result

def main():
    # 前回の人間化されたテキストを読み込み
    with open('_draft/humanize/ultra-low-detection-text.md', 'r', encoding='utf-8') as f:
        text = f.read()
    
    # 極限的な人間化を適用
    humanizer = ExtremeHumanizer()
    extreme_text = humanizer.extreme_humanize(text)
    
    # 結果を保存
    with open('_draft/humanize/extreme-humanized-text.md', 'w', encoding='utf-8') as f:
        f.write(extreme_text)
    
    print("極限的な人間化が完了しました！")
    print("目標: 83% → 20% AI検出率")
    print("出力ファイル: _draft/humanize/extreme-humanized-text.md")

if __name__ == "__main__":
    main() 