#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
AI文章人間化処理 - 全文テスト
ai-text.mdの全文を対象とした人間化処理
"""

import re
import random
import numpy as np
from typing import List, Dict, Tuple

class ExtendedHumanizationPipeline:
    """拡張された人間化処理パイプライン"""
    
    def __init__(self):
        # 同義語マッピング（拡張版）
        self.synonym_mapping = {
            "明らかにした": ["解明した", "突き止めた", "判明した", "発見した", "特定した", "実証した"],
            "示した": ["実証した", "証明した", "立証した", "提示した", "表明した", "確認した"],
            "可能になった": ["実現した", "達成された", "叶った", "可能となった", "実現可能となった"],
            "重要である": ["肝要である", "重大である", "核心的である", "本質的である", "不可欠である"],
            "発見された": ["見出された", "確認された", "観察された", "検出された", "特定された"],
            "技術": ["テクノロジー", "手法", "方法論", "アプローチ", "技法"],
            "解析": ["分析", "調査", "検討", "評価", "検証", "検査"],
            "研究": ["調査", "探究", "検討", "研究活動", "学術的探求", "検証", "学術研究"],
            "臓器": ["器官", "組織", "生体器官", "生体組織"],
            "システム": ["体系", "機構", "ネットワーク", "システム構造", "メカニズム"],
            "明らかになりつつある": ["判明しつつある", "解明されつある", "見えてきている", "明確になりつつある"],
            "用いられている": ["使用されている", "活用されている", "利用されている", "採用されている", "応用されている"],
            "困難であった": ["難しかった", "困難を極めた", "チャレンジングであった", "複雑であった"],
            "次々と": ["相次いで", "続々と", "立て続けに", "連続して", "次から次へと"],
            "開発した": ["構築した", "創出した", "作成した", "設計した", "実現した"],
            "応用": ["適用", "活用", "利用", "運用", "展開"],
            "機能": ["働き", "役割", "作用", "機能性", "効果"],
            "方法": ["手法", "アプローチ", "技法", "方式", "手段"],
            "結果": ["成果", "結論", "所見", "知見", "発見"],
            "により": ["によって", "を通じて", "を介して", "を用いて"],
            "において": ["では", "にて", "の中で", "の場合"],
            "さらに": ["加えて", "また", "なお", "さらには", "その上"],
            "特に": ["とりわけ", "なかでも", "特別に", "中でも"],
            "これらの": ["これらすべての", "上記の", "こうした", "このような"],
            "複数の": ["多数の", "複数種類の", "各種の", "様々な"],
            "新たな": ["新しい", "革新的な", "画期的な", "斬新な"]
        }
        
        # 専門用語の言い換え（拡張版）
        self.technical_variations = {
            "臓器インタラクトミクス": ["臓器間相互作用学", "器官間ネットワーク", "臓器連携システム", "臓器間コミュニケーション", "器官間相互作用"],
            "3Dイメージング": ["三次元画像化", "立体視覚化", "空間画像構築", "三次元可視化", "立体構造解析"],
            "組織透明化": ["組織クリアリング", "透明化処理", "組織透明化技術", "透明化手法"],
            "神経回路": ["神経ネットワーク", "神経経路", "ニューロン回路", "神経系統", "神経結合"],
            "可視化": ["視覚化", "画像化", "描出", "可視的表現", "映像化"],
            "恒常性": ["ホメオスタシス", "平衡状態", "安定性維持", "恒常性維持"],
            "レジリエンス": ["回復力", "復元力", "耐性", "適応力"],
            "マッピング": ["地図作成", "マップ化", "位置特定", "配置解析"],
            "トレーシング": ["追跡", "経路追跡", "トレース", "標識追跡"],
            "標識": ["ラベル", "マーカー", "目印", "識別子"],
            "イメージング": ["画像化", "映像化", "撮像", "画像取得"],
            "解明": ["発見", "特定", "判明", "解析", "確認"],
            "統合": ["結合", "融合", "一体化", "統一", "合成"],
            "制御": ["調節", "コントロール", "管理", "操作"],
            "評価": ["検討", "解析", "分析", "査定", "判定"]
        }
        
        # 文章装飾パターン（拡張版）
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
            "興味深い点として、{}",
            "特に注目すべきは、{}",
            "最も重要なことは、{}",
            "この点で興味深いのは、{}",
            "特に興味深いのは、{}",
            "ここで特筆すべきは、{}"
        ]
        
        # 個人的視点マーカー（拡張版）
        self.personal_markers = [
            "筆者らの観点では", "我々の研究では", "この研究において", "本研究の結果として",
            "研究者らによると", "本論文では", "著者らは", "研究チームは", "本研究では",
            "我々の検討では", "筆者らの分析では", "本研究グループは", "研究者らの見解では"
        ]
        
        # 接続表現の多様化
        self.connective_variations = {
            "また": ["さらに", "加えて", "なお", "その上", "さらには"],
            "しかし": ["ところが", "だが", "けれども", "一方で", "ただし"],
            "さらに": ["また", "加えて", "なお", "その上", "さらには"],
            "そのため": ["従って", "このため", "よって", "したがって", "それゆえ"],
            "このように": ["このような形で", "かくして", "こうして", "このようにして"]
        }
        
    def transform(self, text: str) -> str:
        """文章を人間化する（拡張版）"""
        # ステップ1: 語彙多様化
        text = self._diversify_vocabulary(text)
        
        # ステップ2: 専門用語の変換
        text = self._transform_technical_terms(text)
        
        # ステップ3: 文構造の複雑化
        text = self._complexify_syntax(text)
        
        # ステップ4: 文章長の変動
        text = self._vary_sentence_length(text)
        
        # ステップ5: スタイルの変化
        text = self._shift_style(text)
        
        # ステップ6: 接続表現の多様化
        text = self._diversify_connectives(text)
        
        # ステップ7: 一貫性の維持
        text = self._maintain_coherence(text)
        
        return text
    
    def _diversify_vocabulary(self, text: str) -> str:
        """語彙を多様化する"""
        result = text
        
        # 同義語の置換（確率を調整）
        for original, synonyms in self.synonym_mapping.items():
            # 複数箇所で同じ語が使われている場合、段階的に置換
            count = result.count(original)
            if count > 0:
                replacement_count = min(count, random.randint(1, max(1, count // 2)))
                for _ in range(replacement_count):
                    if random.random() < 0.5:
                        replacement = random.choice(synonyms)
                        result = result.replace(original, replacement, 1)
        
        return result
    
    def _transform_technical_terms(self, text: str) -> str:
        """専門用語を変換する"""
        result = text
        
        for original, variations in self.technical_variations.items():
            if original in result and random.random() < 0.4:
                replacement = random.choice(variations)
                result = result.replace(original, replacement, 1)
        
        return result
    
    def _complexify_syntax(self, text: str) -> str:
        """文構造を複雑化する"""
        sentences = re.split(r'(?<=[。！？])', text)
        modified_sentences = []
        
        for sentence in sentences:
            if not sentence.strip():
                continue
                
            # 長い文に装飾を追加
            if len(sentence) > 50 and random.random() < 0.3:
                # hedging languageの追加
                if random.random() < 0.7:
                    pattern = random.choice(self.hedging_patterns)
                    sentence = pattern.format(sentence)
            
            # 中程度の文に挿入句を追加
            elif 20 < len(sentence) < 50 and random.random() < 0.2:
                if random.random() < 0.5:
                    marker = random.choice(self.personal_markers)
                    sentence = marker + "、" + sentence
            
            modified_sentences.append(sentence)
        
        return ''.join(modified_sentences)
    
    def _vary_sentence_length(self, text: str) -> str:
        """文章長を変動させる"""
        sentences = re.split(r'(?<=[。！？])', text)
        modified_sentences = []
        
        for sentence in sentences:
            if not sentence.strip():
                continue
                
            # 非常に長い文を分割（100文字以上）
            if len(sentence) > 100 and random.random() < 0.3:
                parts = sentence.split('、')
                if len(parts) >= 4:
                    # 文を2つに分割
                    mid_point = len(parts) // 2
                    first_part = '、'.join(parts[:mid_point]) + '。'
                    second_part = '、'.join(parts[mid_point:])
                    modified_sentences.extend([first_part, second_part])
                    continue
            
            # 短い文を詳細化（30文字以下）
            elif len(sentence) < 30 and random.random() < 0.2:
                detail_phrases = [
                    "このような背景を踏まえると、",
                    "さらに詳しく検討すると、",
                    "より具体的に述べるならば、",
                    "この点については、",
                    "特に注目すべきは、"
                ]
                if random.random() < 0.6:
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
            
            # ランダムにスタイルを変更
            if random.random() < 0.15:
                # 強調表現
                if "明らかに" in sentence or "確認" in sentence:
                    emphatic_phrases = ["実際に", "確実に", "明確に", "間違いなく"]
                    if random.random() < 0.5:
                        emphasis = random.choice(emphatic_phrases)
                        sentence = sentence.replace("明らかに", emphasis)
                        sentence = sentence.replace("確認", emphasis + "確認")
            
            modified_sentences.append(sentence)
        
        return ''.join(modified_sentences)
    
    def _diversify_connectives(self, text: str) -> str:
        """接続表現を多様化する"""
        result = text
        
        for original, variations in self.connective_variations.items():
            if original in result and random.random() < 0.4:
                replacement = random.choice(variations)
                result = result.replace(original, replacement, 1)
        
        return result
    
    def _maintain_coherence(self, text: str) -> str:
        """一貫性を維持する"""
        sentences = re.split(r'(?<=[。！？])', text)
        
        # 学術的な文体の維持
        for i, sentence in enumerate(sentences):
            if sentence.strip():
                # 敬語の除去
                sentence = sentence.replace('です。', 'である。')
                sentence = sentence.replace('ます。', 'る。')
                sentence = sentence.replace('でした。', 'であった。')
                sentence = sentence.replace('ました。', 'た。')
                
                sentences[i] = sentence
        
        return ''.join(sentences)

def advanced_perplexity_measure(text: str) -> float:
    """高度なperplexity測定"""
    # 1. 語彙の多様性 (Type-Token Ratio)
    words = re.findall(r'\b\w+\b', text)
    unique_words = len(set(words))
    total_words = len(words)
    ttr = unique_words / total_words if total_words > 0 else 0
    
    # 2. 文構造の複雑さ
    sentences = re.split(r'[。！？]', text)
    sentence_lengths = [len(s.strip()) for s in sentences if s.strip()]
    
    # 文長の分散
    if len(sentence_lengths) > 1:
        length_variance = np.var(sentence_lengths)
        mean_length = np.mean(sentence_lengths)
        normalized_variance = length_variance / mean_length if mean_length > 0 else 0
    else:
        normalized_variance = 0
    
    # 3. 句読点の多様性
    punctuation_count = text.count('、') + text.count('。') + text.count('：') + text.count('；')
    punctuation_diversity = punctuation_count / len(text) if len(text) > 0 else 0
    
    # 4. 助詞の多様性
    particles = ['は', 'が', 'を', 'に', 'で', 'と', 'も', 'から', 'まで', 'より']
    particle_counts = [text.count(p) for p in particles]
    particle_variance = np.var(particle_counts) if particle_counts else 0
    
    # 複合perplexity指標
    perplexity = (ttr * 100) + (normalized_variance / 10) + (punctuation_diversity * 1000) + (particle_variance / 100)
    
    return perplexity

def advanced_burstiness_measure(text: str) -> float:
    """高度なburstiness測定"""
    sentences = re.split(r'[。！？]', text)
    sentence_lengths = [len(s.strip()) for s in sentences if s.strip()]
    
    if len(sentence_lengths) < 2:
        return 0.0
    
    # 1. 文長の分散係数
    variance = np.var(sentence_lengths)
    mean_length = np.mean(sentence_lengths)
    cv = variance / mean_length if mean_length > 0 else 0
    
    # 2. 連続する文の長さの差の分散
    length_diffs = [abs(sentence_lengths[i+1] - sentence_lengths[i]) for i in range(len(sentence_lengths)-1)]
    diff_variance = np.var(length_diffs) if length_diffs else 0
    
    # 3. 最大文長と最小文長の比
    max_length = max(sentence_lengths)
    min_length = min(sentence_lengths)
    length_ratio = max_length / min_length if min_length > 0 else 0
    
    # 複合burstiness指標
    burstiness = cv + (diff_variance / 100) + (length_ratio / 10)
    
    return burstiness

def analyze_full_document():
    """ai-text.md全文を分析"""
    # ai-text.mdの内容を読み込み
    with open('ai-text.md', 'r', encoding='utf-8') as f:
        content = f.read()
    
    # 本文部分を抽出（タイトルと参考文献を除く）
    lines = content.split('\n')
    
    # 本文の開始と終了を特定
    start_idx = 0
    end_idx = len(lines)
    
    for i, line in enumerate(lines):
        if '生体内の臓器は' in line:
            start_idx = i
            break
    
    for i in range(len(lines)-1, -1, -1):
        if line.strip() and not line.startswith('この文書は') and not line.startswith('1.') and not line.startswith('2.'):
            end_idx = i + 1
            break
    
    # 本文を結合
    main_text = '\n'.join(lines[start_idx:end_idx])
    
    # 段落に分割
    paragraphs = [p.strip() for p in main_text.split('\n\n') if p.strip()]
    
    # 人間化処理
    pipeline = ExtendedHumanizationPipeline()
    
    print("=" * 100)
    print("AI文章人間化処理 - 全文テスト結果")
    print("=" * 100)
    
    results = []
    
    for i, paragraph in enumerate(paragraphs[:3]):  # 最初の3段落をテスト
        print(f"\n【段落 {i+1}】")
        print("-" * 50)
        
        # 元の文章
        original_perplexity = advanced_perplexity_measure(paragraph)
        original_burstiness = advanced_burstiness_measure(paragraph)
        
        # 人間化処理
        humanized = pipeline.transform(paragraph)
        
        # 処理後の評価
        humanized_perplexity = advanced_perplexity_measure(humanized)
        humanized_burstiness = advanced_burstiness_measure(humanized)
        
        print(f"元の文章:")
        print(f"{paragraph[:200]}{'...' if len(paragraph) > 200 else ''}")
        print(f"\n人間化処理後:")
        print(f"{humanized[:200]}{'...' if len(humanized) > 200 else ''}")
        
        print(f"\n評価指標:")
        print(f"  Perplexity: {original_perplexity:.2f} → {humanized_perplexity:.2f} "
              f"({((humanized_perplexity - original_perplexity) / original_perplexity * 100):.1f}%)")
        print(f"  Burstiness: {original_burstiness:.2f} → {humanized_burstiness:.2f} "
              f"({((humanized_burstiness - original_burstiness) / original_burstiness * 100):.1f}%)" if original_burstiness > 0 else "  Burstiness: N/A")
        
        results.append({
            'paragraph': i+1,
            'original_perplexity': original_perplexity,
            'humanized_perplexity': humanized_perplexity,
            'original_burstiness': original_burstiness,
            'humanized_burstiness': humanized_burstiness,
            'perplexity_improvement': ((humanized_perplexity - original_perplexity) / original_perplexity * 100) if original_perplexity > 0 else 0,
            'burstiness_improvement': ((humanized_burstiness - original_burstiness) / original_burstiness * 100) if original_burstiness > 0 else 0
        })
    
    # 全体の統計
    print(f"\n【全体統計】")
    print("-" * 50)
    avg_perplexity_improvement = np.mean([r['perplexity_improvement'] for r in results])
    avg_burstiness_improvement = np.mean([r['burstiness_improvement'] for r in results])
    
    print(f"平均Perplexity改善度: {avg_perplexity_improvement:.1f}%")
    print(f"平均Burstiness改善度: {avg_burstiness_improvement:.1f}%")
    
    print("\n" + "=" * 100)

if __name__ == "__main__":
    analyze_full_document() 