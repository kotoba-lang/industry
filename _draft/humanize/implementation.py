#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
AI文章人間化モデル実装
Humanization Enhancement Model Implementation
"""

import re
import random
import numpy as np
from typing import List, Dict, Tuple
from dataclasses import dataclass
import MeCab
import jaconv

@dataclass
class TransformationRule:
    """変換ルールのデータクラス"""
    pattern: str
    replacements: List[str]
    context: str = ""
    probability: float = 0.7

class PerplexityEnhancer:
    """予測困難度を高めるクラス"""
    
    def __init__(self):
        # 同義語マッピング
        self.synonym_mapping = {
            "明らかにした": ["解明した", "突き止めた", "判明した", "発見した", "特定した"],
            "示した": ["実証した", "証明した", "立証した", "提示した", "表明した"],
            "可能になった": ["実現した", "達成された", "叶った", "成し遂げられた", "可能となった"],
            "重要である": ["肝要である", "重大である", "核心的である", "本質的である"],
            "発見された": ["見出された", "確認された", "観察された", "検出された"],
            "技術": ["テクノロジー", "手法", "方法論", "アプローチ"],
            "解析": ["分析", "調査", "検討", "評価", "検証"],
            "研究": ["調査", "探究", "検討", "研究活動", "学術的探求"],
            "臓器": ["器官", "組織", "生体器官"],
            "システム": ["体系", "機構", "ネットワーク", "システム構造"]
        }
        
        # 専門用語の言い換え
        self.technical_variations = {
            "臓器インタラクトミクス": ["臓器間相互作用学", "器官間ネットワーク", "臓器連携システム", "臓器間コミュニケーション"],
            "3Dイメージング": ["三次元画像化", "立体視覚化", "空間画像構築", "三次元可視化"],
            "組織透明化": ["組織クリアリング", "透明化処理", "組織透明化技術"],
            "神経回路": ["神経ネットワーク", "神経経路", "ニューロン回路", "神経系統"]
        }
        
        # 文章装飾パターン
        self.hedging_patterns = [
            "興味深いことに、{}",
            "注目すべきは、{}",
            "驚くべきことに、{}",
            "重要なことは、{}",
            "特筆すべきは、{}",
            "実際のところ、{}",
            "詳しく見ると、{}",
            "さらに言えば、{}"
        ]
        
        # 挿入句パターン
        self.insertion_patterns = [
            "{}、まさに{}",
            "{}、実に{}",
            "{}、確かに{}",
            "{}、実際に{}",
            "{}、特に{}",
            "{}、とりわけ{}"
        ]
        
        # MeCabの初期化
        self.tagger = MeCab.Tagger('-Owakati')
        
    def diversify_vocabulary(self, text: str) -> str:
        """語彙を多様化する"""
        result = text
        
        # 同義語の置換
        for original, synonyms in self.synonym_mapping.items():
            if original in result and random.random() < 0.4:
                replacement = random.choice(synonyms)
                result = result.replace(original, replacement, 1)
        
        # 専門用語の置換
        for original, variations in self.technical_variations.items():
            if original in result and random.random() < 0.3:
                replacement = random.choice(variations)
                result = result.replace(original, replacement, 1)
        
        return result
    
    def complexify_syntax(self, text: str) -> str:
        """文構造を複雑化する"""
        sentences = self._split_sentences(text)
        modified_sentences = []
        
        for sentence in sentences:
            if len(sentence) > 20 and random.random() < 0.3:
                # hedging languageの追加
                if random.random() < 0.5:
                    pattern = random.choice(self.hedging_patterns)
                    sentence = pattern.format(sentence)
                
                # 挿入句の追加
                elif random.random() < 0.3:
                    words = sentence.split('、')
                    if len(words) >= 2:
                        pattern = random.choice(self.insertion_patterns)
                        sentence = pattern.format(words[0], '、'.join(words[1:]))
            
            modified_sentences.append(sentence)
        
        return ''.join(modified_sentences)
    
    def _split_sentences(self, text: str) -> List[str]:
        """文を分割する"""
        return re.split(r'(?<=[。！？])', text)

class BurstinessEnhancer:
    """局所的ランダム性を高めるクラス"""
    
    def __init__(self):
        self.short_connectors = ["つまり、", "要するに、", "実際、", "確かに、", "もちろん、"]
        self.long_connectors = ["このような背景を踏まえると、", "さらに詳しく検討すると、", "より具体的に述べるならば、"]
        self.emotional_markers = ["驚くべきことに", "興味深いことに", "注目すべきは", "重要なことは"]
        self.personal_markers = ["筆者らの観点では", "我々の研究では", "この研究において", "本研究の結果として"]
        
    def vary_sentence_length(self, text: str) -> str:
        """文章長を変動させる"""
        sentences = re.split(r'(?<=[。！？])', text)
        modified_sentences = []
        
        for i, sentence in enumerate(sentences):
            if not sentence.strip():
                continue
                
            # 短文化（約30%の確率）
            if len(sentence) > 50 and random.random() < 0.3:
                # 文を分割
                parts = sentence.split('、')
                if len(parts) >= 2:
                    # 短い文を作成
                    short_sentence = parts[0] + '。'
                    long_sentence = '、'.join(parts[1:])
                    modified_sentences.extend([short_sentence, long_sentence])
                    continue
            
            # 長文化（約20%の確率）
            elif len(sentence) < 30 and random.random() < 0.2:
                # 詳細な説明を追加
                if random.random() < 0.5:
                    connector = random.choice(self.long_connectors)
                    sentence = connector + sentence
                else:
                    detail = "この点については後述するが、"
                    sentence = detail + sentence
            
            modified_sentences.append(sentence)
        
        return ''.join(modified_sentences)
    
    def shift_style(self, text: str) -> str:
        """スタイルを変化させる"""
        result = text
        
        # 感情的マーカーの追加
        sentences = re.split(r'(?<=[。！？])', result)
        modified_sentences = []
        
        for sentence in sentences:
            if not sentence.strip():
                continue
            
            # 感情的ニュアンスの追加（20%の確率）
            if random.random() < 0.2:
                marker = random.choice(self.emotional_markers)
                sentence = marker + "、" + sentence
            
            # 個人的視点の追加（15%の確率）
            elif random.random() < 0.15:
                marker = random.choice(self.personal_markers)
                sentence = marker + "、" + sentence
            
            modified_sentences.append(sentence)
        
        return ''.join(modified_sentences)

class CoherenceMaintainer:
    """一貫性を維持するクラス"""
    
    def __init__(self):
        self.academic_tone_patterns = [
            r'である$',
            r'であった$',
            r'している$',
            r'された$',
            r'される$'
        ]
        
    def maintain_coherence(self, text: str) -> str:
        """一貫性を維持する"""
        # 基本的な整合性チェック
        # 文末の統一
        sentences = re.split(r'(?<=[。！？])', text)
        
        # 学術的な文体の維持
        for i, sentence in enumerate(sentences):
            if sentence.strip():
                # 断定調への統一
                if not any(re.search(pattern, sentence) for pattern in self.academic_tone_patterns):
                    if sentence.endswith('です。'):
                        sentence = sentence.replace('です。', 'である。')
                    elif sentence.endswith('ます。'):
                        sentence = sentence.replace('ます。', 'る。')
                
                sentences[i] = sentence
        
        return ''.join(sentences)

class HumanizationPipeline:
    """人間化処理のメインパイプライン"""
    
    def __init__(self):
        self.perplexity_enhancer = PerplexityEnhancer()
        self.burstiness_enhancer = BurstinessEnhancer()
        self.coherence_maintainer = CoherenceMaintainer()
        
    def transform(self, text: str) -> str:
        """文章を人間化する"""
        # ステップ1: 語彙多様化
        text = self.perplexity_enhancer.diversify_vocabulary(text)
        
        # ステップ2: 文構造の複雑化
        text = self.perplexity_enhancer.complexify_syntax(text)
        
        # ステップ3: 文章長の変動
        text = self.burstiness_enhancer.vary_sentence_length(text)
        
        # ステップ4: スタイルの変化
        text = self.burstiness_enhancer.shift_style(text)
        
        # ステップ5: 一貫性の維持
        text = self.coherence_maintainer.maintain_coherence(text)
        
        return text

def measure_perplexity_simple(text: str) -> float:
    """簡易的なperplexity測定"""
    # 文字種の多様性
    unique_chars = len(set(text))
    total_chars = len(text)
    
    # 語彙の多様性
    words = text.split()
    unique_words = len(set(words))
    total_words = len(words)
    
    # 文の長さの分散
    sentences = re.split(r'[。！？]', text)
    sentence_lengths = [len(s) for s in sentences if s.strip()]
    length_variance = np.var(sentence_lengths) if sentence_lengths else 0
    
    # 複合指標
    char_diversity = unique_chars / total_chars if total_chars > 0 else 0
    word_diversity = unique_words / total_words if total_words > 0 else 0
    length_diversity = length_variance / 100  # 正規化
    
    return (char_diversity + word_diversity + length_diversity) / 3

def measure_burstiness_simple(text: str) -> float:
    """簡易的なburstiness測定"""
    sentences = re.split(r'[。！？]', text)
    sentence_lengths = [len(s.strip()) for s in sentences if s.strip()]
    
    if len(sentence_lengths) < 2:
        return 0.0
    
    # 文章長の分散
    variance = np.var(sentence_lengths)
    mean_length = np.mean(sentence_lengths)
    
    # 正規化されたburstiness値
    burstiness = variance / mean_length if mean_length > 0 else 0
    return burstiness

def evaluate_transformation(original_text: str, humanized_text: str) -> Dict:
    """変換効果を評価する"""
    original_perplexity = measure_perplexity_simple(original_text)
    humanized_perplexity = measure_perplexity_simple(humanized_text)
    
    original_burstiness = measure_burstiness_simple(original_text)
    humanized_burstiness = measure_burstiness_simple(humanized_text)
    
    return {
        'perplexity': {
            'original': original_perplexity,
            'humanized': humanized_perplexity,
            'improvement': humanized_perplexity - original_perplexity
        },
        'burstiness': {
            'original': original_burstiness,
            'humanized': humanized_burstiness,
            'improvement': humanized_burstiness - original_burstiness
        }
    }

if __name__ == "__main__":
    # テスト実行
    sample_text = """臓器インタラクトミクスの可視化について研究が進んでいる。
    組織透明化技術により、3Dイメージングが可能になった。
    この技術は重要である。"""
    
    pipeline = HumanizationPipeline()
    humanized = pipeline.transform(sample_text)
    
    print("Original:")
    print(sample_text)
    print("\nHumanized:")
    print(humanized)
    
    evaluation = evaluate_transformation(sample_text, humanized)
    print("\nEvaluation:")
    print(f"Perplexity improvement: {evaluation['perplexity']['improvement']:.3f}")
    print(f"Burstiness improvement: {evaluation['burstiness']['improvement']:.3f}") 