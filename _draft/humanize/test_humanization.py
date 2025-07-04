#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
AI文章人間化処理のテスト実行
ai-text.mdの実際の文章に対して人間化処理を適用
"""

import re
import random
import numpy as np
from typing import List, Dict

class HumanizationPipeline:
    """人間化処理のメインパイプライン"""
    
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
            "システム": ["体系", "機構", "ネットワーク", "システム構造"],
            "明らかになりつつある": ["判明しつつある", "解明されつつある", "見えてきている"],
            "用いられている": ["使用されている", "活用されている", "利用されている", "採用されている"],
            "困難であった": ["難しかった", "困難を極めた", "チャレンジングであった"],
            "次々と": ["相次いで", "続々と", "立て続けに", "連続して"]
        }
        
        # 専門用語の言い換え
        self.technical_variations = {
            "臓器インタラクトミクス": ["臓器間相互作用学", "器官間ネットワーク", "臓器連携システム", "臓器間コミュニケーション"],
            "3Dイメージング": ["三次元画像化", "立体視覚化", "空間画像構築", "三次元可視化"],
            "組織透明化": ["組織クリアリング", "透明化処理", "組織透明化技術"],
            "神経回路": ["神経ネットワーク", "神経経路", "ニューロン回路", "神経系統"],
            "可視化": ["視覚化", "画像化", "描出", "可視的表現"],
            "恒常性": ["ホメオスタシス", "平衡状態", "安定性維持"],
            "レジリエンス": ["回復力", "復元力", "耐性"]
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
            "さらに言えば、{}",
            "ここで重要なのは、{}",
            "興味深い点として、{}"
        ]
        
        # 挿入句パターン
        self.insertion_patterns = [
            "{}、まさに{}",
            "{}、実に{}",
            "{}、確かに{}",
            "{}、実際に{}",
            "{}、特に{}",
            "{}、とりわけ{}",
            "{}、実のところ{}",
            "{}、驚くべきことに{}"
        ]
        
        # 感情的マーカー
        self.emotional_markers = [
            "驚くべきことに", "興味深いことに", "注目すべきは", "重要なことは",
            "特筆すべきは", "実際のところ", "詳しく見ると", "さらに言えば"
        ]
        
        # 個人的視点マーカー
        self.personal_markers = [
            "筆者らの観点では", "我々の研究では", "この研究において", "本研究の結果として",
            "研究者らによると", "本論文では", "著者らは"
        ]
        
    def transform(self, text: str) -> str:
        """文章を人間化する"""
        # ステップ1: 語彙多様化
        text = self._diversify_vocabulary(text)
        
        # ステップ2: 文構造の複雑化
        text = self._complexify_syntax(text)
        
        # ステップ3: 文章長の変動
        text = self._vary_sentence_length(text)
        
        # ステップ4: スタイルの変化
        text = self._shift_style(text)
        
        # ステップ5: 一貫性の維持
        text = self._maintain_coherence(text)
        
        return text
    
    def _diversify_vocabulary(self, text: str) -> str:
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
    
    def _complexify_syntax(self, text: str) -> str:
        """文構造を複雑化する"""
        sentences = self._split_sentences(text)
        modified_sentences = []
        
        for sentence in sentences:
            if len(sentence) > 30 and random.random() < 0.25:
                # hedging languageの追加
                if random.random() < 0.6:
                    pattern = random.choice(self.hedging_patterns)
                    sentence = pattern.format(sentence)
                
                # 挿入句の追加
                elif random.random() < 0.4:
                    words = sentence.split('、')
                    if len(words) >= 2:
                        pattern = random.choice(self.insertion_patterns)
                        sentence = pattern.format(words[0], '、'.join(words[1:]))
            
            modified_sentences.append(sentence)
        
        return ''.join(modified_sentences)
    
    def _vary_sentence_length(self, text: str) -> str:
        """文章長を変動させる"""
        sentences = re.split(r'(?<=[。！？])', text)
        modified_sentences = []
        
        for i, sentence in enumerate(sentences):
            if not sentence.strip():
                continue
                
            # 短文化（約25%の確率）
            if len(sentence) > 80 and random.random() < 0.25:
                # 文を分割
                parts = sentence.split('、')
                if len(parts) >= 3:
                    # 短い文を作成
                    short_sentence = parts[0] + '。'
                    long_sentence = '、'.join(parts[1:])
                    modified_sentences.extend([short_sentence, long_sentence])
                    continue
            
            # 長文化（約15%の確率）
            elif len(sentence) < 40 and random.random() < 0.15:
                # 詳細な説明を追加
                if random.random() < 0.5:
                    detail_phrases = [
                        "このような背景を踏まえると、",
                        "さらに詳しく検討すると、",
                        "より具体的に述べるならば、",
                        "この点については後述するが、"
                    ]
                    detail = random.choice(detail_phrases)
                    sentence = detail + sentence
            
            modified_sentences.append(sentence)
        
        return ''.join(modified_sentences)
    
    def _shift_style(self, text: str) -> str:
        """スタイルを変化させる"""
        sentences = re.split(r'(?<=[。！？])', text)
        modified_sentences = []
        
        for sentence in sentences:
            if not sentence.strip():
                continue
            
            # 感情的ニュアンスの追加（15%の確率）
            if random.random() < 0.15:
                marker = random.choice(self.emotional_markers)
                sentence = marker + "、" + sentence
            
            # 個人的視点の追加（10%の確率）
            elif random.random() < 0.10:
                marker = random.choice(self.personal_markers)
                sentence = marker + "、" + sentence
            
            modified_sentences.append(sentence)
        
        return ''.join(modified_sentences)
    
    def _maintain_coherence(self, text: str) -> str:
        """一貫性を維持する"""
        # 基本的な整合性チェック
        sentences = re.split(r'(?<=[。！？])', text)
        
        # 学術的な文体の維持
        for i, sentence in enumerate(sentences):
            if sentence.strip():
                # 断定調への統一
                if sentence.endswith('です。'):
                    sentence = sentence.replace('です。', 'である。')
                elif sentence.endswith('ます。'):
                    sentence = sentence.replace('ます。', 'る。')
                
                sentences[i] = sentence
        
        return ''.join(sentences)
    
    def _split_sentences(self, text: str) -> List[str]:
        """文を分割する"""
        return re.split(r'(?<=[。！？])', text)

def measure_perplexity_simple(text: str) -> float:
    """簡易的なperplexity測定"""
    # 語彙の多様性
    words = text.split()
    unique_words = len(set(words))
    total_words = len(words)
    
    # 文の長さの分散
    sentences = re.split(r'[。！？]', text)
    sentence_lengths = [len(s) for s in sentences if s.strip()]
    length_variance = np.var(sentence_lengths) if sentence_lengths else 0
    
    # 複合指標
    word_diversity = unique_words / total_words if total_words > 0 else 0
    length_diversity = length_variance / 1000  # 正規化
    
    return (word_diversity + length_diversity) * 100

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

def main():
    """メイン処理"""
    # ai-text.mdから元のテキストを読み込み
    original_text = """生体内の臓器は互いに神経系・免疫系・脈管系を介して情報伝達し、生体システムの恒常性維持やレジリエンス基盤を構築している。このような臓器インタラクトミクスの全体像を描出するため、組織透明化・3Dイメージングをはじめとして、ウイルスによる神経回路マッピング、網羅的な一細胞遺伝子発現解析、光操作技術、生体内カルシウムイメージングといった先端技術が用いられている。これらの技術を複合的に応用することで、心拍や糖代謝を司る特定の神経回路、脳炎症時における頭蓋骨骨髄から髄膜への免疫細胞の新たな侵入経路、さらに迷走神経が「どの臓器・どの組織層・どのような刺激か」を多次元的に符号化して情報を伝達する仕組みなど、これまで解明が困難であった生命現象が次々と明らかになりつつある。"""
    
    # 人間化処理を実行
    pipeline = HumanizationPipeline()
    humanized_text = pipeline.transform(original_text)
    
    # 評価指標の計算
    original_perplexity = measure_perplexity_simple(original_text)
    humanized_perplexity = measure_perplexity_simple(humanized_text)
    
    original_burstiness = measure_burstiness_simple(original_text)
    humanized_burstiness = measure_burstiness_simple(humanized_text)
    
    # 結果の表示
    print("=" * 80)
    print("AI文章人間化処理テスト結果")
    print("=" * 80)
    
    print("\n【元の文章】")
    print("-" * 40)
    print(original_text)
    
    print("\n【人間化処理後】")
    print("-" * 40)
    print(humanized_text)
    
    print("\n【評価指標】")
    print("-" * 40)
    print(f"Perplexity:")
    print(f"  元の文章: {original_perplexity:.2f}")
    print(f"  処理後:   {humanized_perplexity:.2f}")
    print(f"  改善度:   {((humanized_perplexity - original_perplexity) / original_perplexity * 100):.1f}%")
    
    print(f"\nBurstiness:")
    print(f"  元の文章: {original_burstiness:.2f}")
    print(f"  処理後:   {humanized_burstiness:.2f}")
    print(f"  改善度:   {((humanized_burstiness - original_burstiness) / original_burstiness * 100):.1f}%" if original_burstiness > 0 else "  改善度:   N/A")
    
    # 文章長の統計
    original_sentences = [s.strip() for s in re.split(r'[。！？]', original_text) if s.strip()]
    humanized_sentences = [s.strip() for s in re.split(r'[。！？]', humanized_text) if s.strip()]
    
    print(f"\n【文章構造】")
    print("-" * 40)
    print(f"文数:")
    print(f"  元の文章: {len(original_sentences)}文")
    print(f"  処理後:   {len(humanized_sentences)}文")
    
    print(f"\n平均文長:")
    print(f"  元の文章: {np.mean([len(s) for s in original_sentences]):.1f}文字")
    print(f"  処理後:   {np.mean([len(s) for s in humanized_sentences]):.1f}文字")
    
    print(f"\n文長の分散:")
    print(f"  元の文章: {np.var([len(s) for s in original_sentences]):.1f}")
    print(f"  処理後:   {np.var([len(s) for s in humanized_sentences]):.1f}")
    
    print("\n【変換内容の詳細】")
    print("-" * 40)
    # 変換された単語を特定
    original_words = set(original_text.split())
    humanized_words = set(humanized_text.split())
    
    added_words = humanized_words - original_words
    removed_words = original_words - humanized_words
    
    if added_words:
        print(f"追加された語句: {', '.join(list(added_words)[:10])}")
    if removed_words:
        print(f"置換された語句: {', '.join(list(removed_words)[:10])}")
    
    print("\n" + "=" * 80)

if __name__ == "__main__":
    main() 