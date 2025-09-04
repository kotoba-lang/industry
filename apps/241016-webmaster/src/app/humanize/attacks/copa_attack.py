# -*- coding: utf-8 -*-
"""
CoPA Attack Implementation

論文参照: "Contrastive Paraphrase Attack (CoPA)" (2024)
効果: 対比学習による検出回避

この攻撃手法はLLMの生成する「機械らしい語彙分布」を対比的に用い、
人間らしい分布から機械的パターンを差し引くことで検出器を欺きます。
"""

import re
import random
import math
from typing import Dict, List, Tuple, Optional
from dataclasses import dataclass
from collections import Counter, defaultdict


@dataclass
class CoPAConfig:
    """CoPA攻撃設定"""
    intensity: float = 0.3  # 0.0-1.0の強度
    contrastive_ratio: float = 0.8  # 対比率
    human_weight: float = 0.7  # 人間らしさの重み
    machine_weight: float = -0.3  # 機械らしさの重み（負の値）
    diversity_threshold: float = 0.6  # 多様性閾値


class CoPAAttack:
    """
    CoPA (Contrastive Paraphrase Attack) 攻撃クラス
    
    LLMの生成する「機械らしい語彙分布」を対比的に用い、
    人間らしい分布から機械的パターンを差し引くことで検出器を欺く手法を実装。
    """
    
    def __init__(self, config: CoPAConfig = None):
        self.config = config or CoPAConfig()
        self.human_patterns = self._load_human_patterns()
        self.machine_patterns = self._load_machine_patterns()
        self.contrastive_vocabulary = self._build_contrastive_vocabulary()
        self.linguistic_features = self._initialize_linguistic_features()
    
    def _load_human_patterns(self) -> Dict[str, List[str]]:
        """人間らしいパターンを読み込み"""
        return {
            "informal_expressions": [
                "ちょっと", "なんか", "やっぱり", "きっと", "たぶん", 
                "でも", "だけど", "けど", "まあ", "とりあえず"
            ],
            "hedging_phrases": [
                "と思います", "かもしれません", "ような気がします", 
                "ように見えます", "と考えられます", "らしいです"
            ],
            "discourse_markers": [
                "要するに", "つまり", "ということで", "それで", 
                "そういうわけで", "だから", "なので"
            ],
            "personal_expressions": [
                "私的には", "個人的に", "正直なところ", "率直に言うと",
                "経験上", "実際のところ", "思うに"
            ],
            "colloquial_words": [
                "すごく", "めちゃくちゃ", "とても", "かなり", 
                "結構", "わりと", "そこそこ", "ちょっと"
            ],
            "natural_transitions": [
                "ところで", "そういえば", "話は変わりますが", 
                "ちなみに", "余談ですが", "関連して"
            ]
        }
    
    def _load_machine_patterns(self) -> Dict[str, List[str]]:
        """機械らしいパターンを読み込み"""
        return {
            "formal_expressions": [
                "については", "に関しましては", "におきましては",
                "について述べる", "に関して論じる", "を検討する"
            ],
            "academic_jargon": [
                "アプローチ", "フレームワーク", "パラダイム", "メソドロジー",
                "ソリューション", "イノベーション", "オプティマイゼーション"
            ],
            "technical_phrases": [
                "実装する", "デプロイする", "最適化する", "効率化する",
                "パフォーマンス", "インターフェース", "アーキテクチャ"
            ],
            "repetitive_structures": [
                "である", "します", "されます", "となります",
                "を行います", "を実施します", "を提供します"
            ],
            "formal_connectives": [
                "したがって", "ゆえに", "それゆえ", "このため",
                "その結果", "これにより", "よって"
            ],
            "abstract_nouns": [
                "概念", "要素", "側面", "観点", "視点", "次元",
                "レベル", "段階", "プロセス", "システム"
            ]
        }
    
    def _build_contrastive_vocabulary(self) -> Dict[str, Dict[str, float]]:
        """対比語彙を構築"""
        contrastive_vocab = {
            "human_to_machine": {},
            "machine_to_human": {},
            "neutral_alternatives": {}
        }
        
        # 人間→機械パターンのマッピング
        human_machine_pairs = [
            ("ちょっと", "わずかに"),
            ("すごく", "非常に"),
            ("やっぱり", "やはり"),
            ("だけど", "しかし"),
            ("なんか", "何らか"),
            ("きっと", "確実に"),
            ("たぶん", "おそらく"),
            ("でも", "ただし"),
            ("まあ", "概ね"),
            ("とりあえず", "暫定的に"),
        ]
        
        for human_expr, machine_expr in human_machine_pairs:
            contrastive_vocab["human_to_machine"][human_expr] = machine_expr
            contrastive_vocab["machine_to_human"][machine_expr] = human_expr
        
        # 中立的な代替表現
        neutral_alternatives = {
            "研究": ["調査", "検討", "分析", "探索"],
            "方法": ["手法", "技術", "アプローチ", "方式"],
            "結果": ["成果", "所見", "知見", "帰結"],
            "重要": ["重大", "主要", "肝要", "必要"],
            "効果": ["効能", "影響", "作用", "結果"],
        }
        
        contrastive_vocab["neutral_alternatives"] = neutral_alternatives
        
        return contrastive_vocab
    
    def _initialize_linguistic_features(self) -> Dict[str, callable]:
        """言語的特徴量を初期化"""
        return {
            "lexical_diversity": self._calculate_lexical_diversity,
            "syntactic_complexity": self._calculate_syntactic_complexity,
            "semantic_coherence": self._calculate_semantic_coherence,
            "stylistic_consistency": self._calculate_stylistic_consistency,
            "naturalness_score": self._calculate_naturalness_score,
        }
    
    def apply_copa_attack(self, text: str) -> str:
        """CoPA攻撃を適用"""
        # 対比分析の実行
        human_score, machine_score = self._perform_contrastive_analysis(text)
        
        # 対比に基づくパラフレーズ
        contrasted_text = self._apply_contrastive_paraphrasing(text, human_score, machine_score)
        
        # 分布調整
        adjusted_text = self._adjust_distribution(contrasted_text)
        
        # 最終検証と微調整
        final_text = self._final_adjustment(adjusted_text)
        
        return final_text
    
    def _perform_contrastive_analysis(self, text: str) -> Tuple[float, float]:
        """対比分析を実行"""
        words = text.split()
        
        # 人間らしさスコア計算
        human_score = 0.0
        for category, patterns in self.human_patterns.items():
            for pattern in patterns:
                if pattern in text:
                    human_score += 1.0
        
        human_score = human_score / len(words) if words else 0
        
        # 機械らしさスコア計算
        machine_score = 0.0
        for category, patterns in self.machine_patterns.items():
            for pattern in patterns:
                if pattern in text:
                    machine_score += 1.0
        
        machine_score = machine_score / len(words) if words else 0
        
        return human_score, machine_score
    
    def _apply_contrastive_paraphrasing(self, text: str, human_score: float, machine_score: float) -> str:
        """対比パラフレーズを適用"""
        contrasted_text = text
        
        # 対比率に基づく変換戦略決定
        if machine_score > human_score * self.config.contrastive_ratio:
            # 機械らしさが高い場合：人間らしさを増加
            contrasted_text = self._humanize_text(contrasted_text)
        else:
            # バランスを取るための調整
            contrasted_text = self._balance_text(contrasted_text)
        
        return contrasted_text
    
    def _humanize_text(self, text: str) -> str:
        """テキストを人間らしくする"""
        humanized_text = text
        
        # 機械的表現を人間的表現に変換
        for machine_expr, human_expr in self.contrastive_vocabulary["machine_to_human"].items():
            if random.random() < self.config.intensity:
                humanized_text = humanized_text.replace(machine_expr, human_expr)
        
        # 人間らしい表現を追加
        humanized_text = self._inject_human_expressions(humanized_text)
        
        # 文構造を自然化
        humanized_text = self._naturalize_sentence_structure(humanized_text)
        
        return humanized_text
    
    def _inject_human_expressions(self, text: str) -> str:
        """人間らしい表現を注入"""
        sentences = text.split('。')
        modified_sentences = []
        
        for sentence in sentences:
            if sentence.strip():
                # ヘッジ表現の追加
                if random.random() < self.config.intensity * 0.3:
                    hedge = random.choice(self.human_patterns["hedging_phrases"])
                    sentence = sentence.strip() + hedge
                
                # 談話標識の追加
                if random.random() < self.config.intensity * 0.2:
                    marker = random.choice(self.human_patterns["discourse_markers"])
                    sentence = marker + "、" + sentence.strip()
                
                modified_sentences.append(sentence)
        
        return '。'.join(modified_sentences)
    
    def _naturalize_sentence_structure(self, text: str) -> str:
        """文構造を自然化"""
        # 固い表現をやわらかくする
        naturalization_patterns = [
            (r'について検討する', r'について考える'),
            (r'を実施する', r'をする'),
            (r'において', r'で'),
            (r'に関して', r'について'),
            (r'したがって', r'だから'),
            (r'ゆえに', r'ので'),
        ]
        
        naturalized_text = text
        for formal, casual in naturalization_patterns:
            if random.random() < self.config.intensity * 0.4:
                naturalized_text = re.sub(formal, casual, naturalized_text)
        
        return naturalized_text
    
    def _balance_text(self, text: str) -> str:
        """テキストのバランスを調整"""
        balanced_text = text
        
        # 中立的な代替表現を使用
        for original, alternatives in self.contrastive_vocabulary["neutral_alternatives"].items():
            if original in balanced_text and random.random() < self.config.intensity * 0.5:
                replacement = random.choice(alternatives)
                balanced_text = balanced_text.replace(original, replacement, 1)
        
        return balanced_text
    
    def _adjust_distribution(self, text: str) -> str:
        """分布を調整"""
        # 語彙分布の調整
        adjusted_text = self._adjust_lexical_distribution(text)
        
        # 統語分布の調整
        adjusted_text = self._adjust_syntactic_distribution(adjusted_text)
        
        return adjusted_text
    
    def _adjust_lexical_distribution(self, text: str) -> str:
        """語彙分布を調整"""
        words = text.split()
        word_freq = Counter(words)
        
        # 高頻度語の一部を低頻度の類義語に置換
        adjusted_words = []
        
        for word in words:
            if (word_freq[word] > 2 and 
                word in self.contrastive_vocabulary["neutral_alternatives"] and
                random.random() < self.config.intensity * 0.3):
                
                alternatives = self.contrastive_vocabulary["neutral_alternatives"][word]
                replacement = random.choice(alternatives)
                adjusted_words.append(replacement)
                word_freq[word] -= 1  # 頻度を減らす
            else:
                adjusted_words.append(word)
        
        return ' '.join(adjusted_words)
    
    def _adjust_syntactic_distribution(self, text: str) -> str:
        """統語分布を調整"""
        # 文長の多様化
        sentences = text.split('。')
        adjusted_sentences = []
        
        for sentence in sentences:
            if sentence.strip():
                # 短い文を長くする
                if len(sentence.split()) < 5 and random.random() < self.config.intensity * 0.4:
                    extension = random.choice([
                        "ということです",
                        "と考えられます",
                        "ことが分かります"
                    ])
                    sentence = sentence.strip() + extension
                
                # 長い文を短くする
                elif len(sentence.split()) > 20 and random.random() < self.config.intensity * 0.3:
                    # 文を分割
                    midpoint = len(sentence) // 2
                    split_point = sentence.find('、', midpoint)
                    if split_point != -1:
                        sentence = sentence[:split_point] + '。' + sentence[split_point+1:]
                
                adjusted_sentences.append(sentence)
        
        return '。'.join(adjusted_sentences)
    
    def _final_adjustment(self, text: str) -> str:
        """最終調整"""
        # 品質チェック
        quality_score = self._assess_quality(text)
        
        if quality_score < self.config.diversity_threshold:
            # 追加の多様化
            text = self._increase_diversity(text)
        
        # 一貫性チェック
        text = self._ensure_consistency(text)
        
        return text
    
    def _assess_quality(self, text: str) -> float:
        """品質評価"""
        scores = []
        
        for feature_name, feature_func in self.linguistic_features.items():
            score = feature_func(text)
            scores.append(score)
        
        return sum(scores) / len(scores) if scores else 0.0
    
    def _increase_diversity(self, text: str) -> str:
        """多様性を増加"""
        # 語彙の多様化
        diversified_text = self._diversify_vocabulary(text)
        
        # 構文の多様化
        diversified_text = self._diversify_syntax(diversified_text)
        
        return diversified_text
    
    def _diversify_vocabulary(self, text: str) -> str:
        """語彙を多様化"""
        words = text.split()
        diversified_words = []
        
        for word in words:
            if (word in self.contrastive_vocabulary["neutral_alternatives"] and
                random.random() < 0.2):
                alternatives = self.contrastive_vocabulary["neutral_alternatives"][word]
                diversified_words.append(random.choice(alternatives))
            else:
                diversified_words.append(word)
        
        return ' '.join(diversified_words)
    
    def _diversify_syntax(self, text: str) -> str:
        """構文を多様化"""
        syntax_variations = [
            (r'(\w+)である', r'\1となっている'),
            (r'(\w+)する', r'\1を行う'),
            (r'(\w+)した', r'\1を実施した'),
            (r'(\w+)できる', r'\1が可能である'),
        ]
        
        diversified_text = text
        for pattern, replacement in syntax_variations:
            if random.random() < 0.15:
                diversified_text = re.sub(pattern, replacement, diversified_text)
        
        return diversified_text
    
    def _ensure_consistency(self, text: str) -> str:
        """一貫性を確保"""
        # 敬語の統一
        consistency_patterns = [
            (r'です。ですが', r'です。しかし'),
            (r'である。である', r'である。また'),
            (r'ます。ます', r'ます。また'),
        ]
        
        consistent_text = text
        for pattern, replacement in consistency_patterns:
            consistent_text = re.sub(pattern, replacement, consistent_text)
        
        return consistent_text
    
    def _calculate_lexical_diversity(self, text: str) -> float:
        """語彙多様性を計算"""
        words = text.split()
        if not words:
            return 0.0
        
        unique_words = len(set(words))
        total_words = len(words)
        
        return unique_words / total_words
    
    def _calculate_syntactic_complexity(self, text: str) -> float:
        """統語的複雑性を計算"""
        sentences = text.split('。')
        if not sentences:
            return 0.0
        
        avg_sentence_length = sum(len(s.split()) for s in sentences) / len(sentences)
        
        # 正規化（20語を基準とする）
        return min(1.0, avg_sentence_length / 20)
    
    def _calculate_semantic_coherence(self, text: str) -> float:
        """意味的一貫性を計算"""
        # 簡易的な実装
        sentences = text.split('。')
        if len(sentences) < 2:
            return 1.0
        
        # 隣接文間の語彙重複率
        coherence_scores = []
        for i in range(len(sentences) - 1):
            words1 = set(sentences[i].split())
            words2 = set(sentences[i + 1].split())
            
            if words1 and words2:
                overlap = len(words1 & words2)
                union = len(words1 | words2)
                coherence = overlap / union if union > 0 else 0
                coherence_scores.append(coherence)
        
        return sum(coherence_scores) / len(coherence_scores) if coherence_scores else 0
    
    def _calculate_stylistic_consistency(self, text: str) -> float:
        """文体一貫性を計算"""
        # 敬語の使用率
        formal_patterns = ["である", "します", "ます", "です"]
        informal_patterns = ["だ", "する", "っぽい", "みたい"]
        
        formal_count = sum(text.count(pattern) for pattern in formal_patterns)
        informal_count = sum(text.count(pattern) for pattern in informal_patterns)
        
        total_count = formal_count + informal_count
        if total_count == 0:
            return 1.0
        
        # 一方が支配的な場合は一貫性が高い
        dominant_ratio = max(formal_count, informal_count) / total_count
        return dominant_ratio
    
    def _calculate_naturalness_score(self, text: str) -> float:
        """自然さスコアを計算"""
        human_expressions = 0
        machine_expressions = 0
        
        for patterns in self.human_patterns.values():
            for pattern in patterns:
                human_expressions += text.count(pattern)
        
        for patterns in self.machine_patterns.values():
            for pattern in patterns:
                machine_expressions += text.count(pattern)
        
        total_expressions = human_expressions + machine_expressions
        if total_expressions == 0:
            return 0.5
        
        return human_expressions / total_expressions
    
    def apply_advanced_copa_attack(self, text: str) -> str:
        """高度CoPA攻撃"""
        # 多段階対比攻撃
        text = self._apply_multi_stage_contrastive_attack(text)
        text = self._apply_adaptive_contrastive_attack(text)
        text = self._apply_ensemble_contrastive_attack(text)
        
        return text
    
    def _apply_multi_stage_contrastive_attack(self, text: str) -> str:
        """多段階対比攻撃"""
        current_text = text
        
        stages = [
            ("lexical", 0.8),
            ("syntactic", 0.6),
            ("stylistic", 0.4),
        ]
        
        for stage_name, stage_intensity in stages:
            stage_config = CoPAConfig(
                intensity=self.config.intensity * stage_intensity,
                contrastive_ratio=self.config.contrastive_ratio,
                human_weight=self.config.human_weight,
                machine_weight=self.config.machine_weight
            )
            
            stage_attack = CoPAAttack(stage_config)
            current_text = stage_attack.apply_copa_attack(current_text)
        
        return current_text
    
    def _apply_adaptive_contrastive_attack(self, text: str) -> str:
        """適応的対比攻撃"""
        # テキストの特性分析
        human_score, machine_score = self._perform_contrastive_analysis(text)
        
        # 特性に基づく戦略調整
        if human_score < 0.3:
            # 人間らしさが不足
            return self._intensive_humanization(text)
        elif machine_score > 0.7:
            # 機械らしさが過多
            return self._aggressive_dehumanization(text)
        else:
            # バランス調整
            return self._fine_tune_balance(text)
    
    def _intensive_humanization(self, text: str) -> str:
        """集中的人間化"""
        humanized = text
        
        # より多くの人間的表現を注入
        for _ in range(3):
            humanized = self._inject_human_expressions(humanized)
        
        return humanized
    
    def _aggressive_dehumanization(self, text: str) -> str:
        """積極的非機械化"""
        dehumanized = text
        
        # 機械的表現を積極的に除去
        for machine_expr in sum(self.machine_patterns.values(), []):
            if machine_expr in dehumanized:
                if machine_expr in self.contrastive_vocabulary["machine_to_human"]:
                    human_expr = self.contrastive_vocabulary["machine_to_human"][machine_expr]
                    dehumanized = dehumanized.replace(machine_expr, human_expr)
        
        return dehumanized
    
    def _fine_tune_balance(self, text: str) -> str:
        """バランス微調整"""
        return self._balance_text(text)
    
    def _apply_ensemble_contrastive_attack(self, text: str) -> str:
        """アンサンブル対比攻撃"""
        # 複数の戦略を並行実行
        strategies = [
            self._humanize_text,
            self._balance_text,
            self._adjust_distribution,
        ]
        
        results = []
        for strategy in strategies:
            result = strategy(text)
            results.append(result)
        
        # 最適な結果を選択
        best_result = text
        best_score = 0
        
        for result in results:
            score = self._assess_quality(result)
            if score > best_score:
                best_score = score
                best_result = result
        
        return best_result
    
    def analyze_copa_effect(self, original: str, modified: str) -> Dict:
        """CoPA効果を分析"""
        original_human, original_machine = self._perform_contrastive_analysis(original)
        modified_human, modified_machine = self._perform_contrastive_analysis(modified)
        
        return {
            "character_change": len(modified) - len(original),
            "human_score_change": modified_human - original_human,
            "machine_score_change": modified_machine - original_machine,
            "contrastive_improvement": (modified_human - modified_machine) - (original_human - original_machine),
            "lexical_diversity": self._calculate_lexical_diversity(modified),
            "syntactic_complexity": self._calculate_syntactic_complexity(modified),
            "semantic_coherence": self._calculate_semantic_coherence(modified),
            "naturalness_score": self._calculate_naturalness_score(modified),
            "overall_quality": self._assess_quality(modified),
        }


def test_copa_attack():
    """テスト関数"""
    print("🎭 CoPA Attack テスト開始")
    
    # テストテキスト
    test_text = """
    本研究におきましては、新規のアプローチを実装し、効率的な最適化を実施しました。
    その結果、従来のソリューションと比較して、パフォーマンスが向上しました。
    """
    
    # 設定
    config = CoPAConfig(intensity=0.7, contrastive_ratio=0.8, human_weight=0.8, machine_weight=-0.4)
    attack = CoPAAttack(config)
    
    # 攻撃実行
    basic_copa = attack.apply_copa_attack(test_text)
    advanced_copa = attack.apply_advanced_copa_attack(test_text)
    
    # 結果表示
    print(f"\n📝 元テキスト:\n{test_text}")
    print(f"\n🎭 基本CoPA:\n{basic_copa}")
    print(f"\n🚀 高度CoPA:\n{advanced_copa}")
    
    # 効果分析
    analysis = attack.analyze_copa_effect(test_text, advanced_copa)
    print(f"\n📊 効果分析:")
    for key, value in analysis.items():
        print(f"  {key}: {value}")


if __name__ == "__main__":
    test_copa_attack() 