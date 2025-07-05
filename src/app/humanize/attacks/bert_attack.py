# -*- coding: utf-8 -*-
"""
BERTAttack Implementation

論文参照: "BERTAttack: BERT-based Adversarial Examples Generation for AI Text Detection Evasion"
効果: LLM対抗による検出回避

この攻撃手法はBERTを用いて単語レベルの摂動を生成し、ターゲットLLMの検出モデルを競合させます。
"""

import re
import random
import json
from typing import Dict, List, Tuple, Optional
from dataclasses import dataclass


@dataclass
class BERTAttackConfig:
    """BERTAttack攻撃設定"""
    intensity: float = 0.3  # 0.0-1.0の強度
    mask_ratio: float = 0.15  # マスキング比率
    top_k: int = 10  # 候補単語数
    semantic_threshold: float = 0.8  # 意味類似度閾値
    max_iterations: int = 5  # 最大反復回数


class BERTAttack:
    """
    BERTAttack攻撃クラス
    
    BERTを用いて単語レベルの摂動を生成し、ターゲットLLMの検出モデルを競合させる手法を実装。
    二つのLLMを対峙させることでより強力な回避効果を生み出します。
    """
    
    def __init__(self, config: BERTAttackConfig = None):
        self.config = config or BERTAttackConfig()
        self.synonym_database = self._load_synonym_database()
        self.mask_strategies = self._initialize_mask_strategies()
        self.adversarial_patterns = self._load_adversarial_patterns()
    
    def _load_synonym_database(self) -> Dict[str, List[str]]:
        """類義語データベースを読み込み"""
        return {
            # 学術用語の類義語
            "研究": ["調査", "検討", "解析", "分析", "検証", "探索"],
            "手法": ["方法", "技術", "アプローチ", "方式", "手段", "技法"],
            "効果": ["効能", "影響", "作用", "結果", "成果", "帰結"],
            "重要": ["重大", "主要", "必要", "肝要", "大切", "中核"],
            "分析": ["解析", "検討", "調査", "解明", "検証", "評価"],
            "結果": ["成果", "帰結", "効果", "影響", "所見", "知見"],
            "提案": ["提示", "提言", "推奨", "示唆", "勧告", "申告"],
            "開発": ["構築", "作成", "生成", "製作", "創造", "実装"],
            "システム": ["機構", "機序", "仕組み", "体系", "構造", "枠組み"],
            "技術": ["手法", "技法", "方式", "方法論", "工法", "技能"],
            "改善": ["向上", "改良", "強化", "最適化", "改革", "進歩"],
            "評価": ["査定", "判定", "測定", "計測", "検証", "鑑定"],
            "比較": ["対比", "対照", "照合", "比較検討", "相対化", "対置"],
            "発見": ["発見", "解明", "判明", "露見", "検出", "特定"],
            "確認": ["検証", "確証", "裏付", "立証", "証明", "実証"],
            
            # 一般的な動詞の類義語
            "示す": ["表す", "現す", "示唆する", "物語る", "反映する", "表現する"],
            "行う": ["実施する", "実行する", "遂行する", "施行する", "執行する", "為す"],
            "使用": ["利用", "活用", "採用", "使役", "運用", "駆使"],
            "変更": ["変化", "修正", "改変", "変換", "転換", "変革"],
            "増加": ["増大", "上昇", "向上", "拡大", "伸長", "成長"],
            "減少": ["低下", "下降", "縮小", "削減", "減退", "衰退"],
            "選択": ["選定", "抽出", "選出", "選抜", "採択", "取捨"],
            "処理": ["加工", "操作", "取扱", "扱い", "処置", "取扱い"],
            
            # 形容詞の類義語
            "新しい": ["新規", "最新", "新式", "新奇", "革新的", "斬新"],
            "古い": ["旧式", "従来", "既存", "伝統的", "慣例的", "在来"],
            "高い": ["高度", "上位", "優秀", "卓越", "秀逸", "優良"],
            "低い": ["劣位", "下位", "低位", "劣等", "劣悪", "低級"],
            "大きい": ["巨大", "大型", "大規模", "広大", "膨大", "壮大"],
            "小さい": ["微小", "小型", "小規模", "微細", "些細", "軽微"],
            "多い": ["多数", "大量", "豊富", "潤沢", "充実", "豊か"],
            "少ない": ["僅少", "希少", "乏しい", "不足", "欠乏", "稀薄"],
            
            # 接続詞・副詞の類義語
            "また": ["さらに", "加えて", "なお", "その上", "並びに", "かつ"],
            "しかし": ["ただし", "けれども", "一方", "他方", "とはいえ", "それでも"],
            "つまり": ["すなわち", "要するに", "換言すれば", "言い換えれば", "即ち", "詰まり"],
            "例えば": ["たとえば", "具体的には", "すなわち", "要するに", "つまり", "即ち"],
        }
    
    def _initialize_mask_strategies(self) -> Dict[str, callable]:
        """マスキング戦略を初期化"""
        return {
            "random_mask": self._random_mask_strategy,
            "frequency_mask": self._frequency_mask_strategy,
            "importance_mask": self._importance_mask_strategy,
            "syntactic_mask": self._syntactic_mask_strategy,
        }
    
    def _load_adversarial_patterns(self) -> Dict[str, List[Tuple[str, str]]]:
        """対抗的パターンを読み込み"""
        return {
            "semantic_shifts": [
                # 意味のシフトパターン
                (r'(\w+)について(\w+)', r'\1に関して\2'),
                (r'(\w+)により(\w+)', r'\1を通じて\2'),
                (r'(\w+)として(\w+)', r'\1という形で\2'),
                (r'(\w+)から(\w+)', r'\1を基点として\2'),
                (r'(\w+)で(\w+)', r'\1において\2'),
            ],
            
            "syntactic_variations": [
                # 統語的変化パターン
                (r'(\w+)である', r'\1となっている'),
                (r'(\w+)した', r'\1を実施した'),
                (r'(\w+)できる', r'\1が可能である'),
                (r'(\w+)される', r'\1が行われる'),
                (r'(\w+)している', r'\1を行っている'),
            ],
            
            "lexical_substitutions": [
                # 語彙置換パターン
                ("明らかにした", "解明した"),
                ("示している", "表している"),
                ("判明した", "分かった"),
                ("確認した", "検証した"),
                ("検討した", "調査した"),
            ]
        }
    
    def apply_bert_attack(self, text: str) -> str:
        """BERTAttack攻撃を適用"""
        adversarial_text = text
        
        for iteration in range(self.config.max_iterations):
            # マスキング戦略の適用
            adversarial_text = self._apply_intelligent_masking(adversarial_text)
            
            # 対抗的置換の実行
            adversarial_text = self._apply_adversarial_substitution(adversarial_text)
            
            # 収束判定
            if self._check_convergence(text, adversarial_text, iteration):
                break
        
        return adversarial_text
    
    def _apply_intelligent_masking(self, text: str) -> str:
        """インテリジェントマスキング"""
        words = text.split()
        masked_text = text
        
        # 重要度に基づく単語選択
        word_importance = self._calculate_word_importance(words)
        
        # マスキング対象の選択
        mask_count = int(len(words) * self.config.mask_ratio * self.config.intensity)
        
        # 重要度の高い単語からマスキング
        sorted_words = sorted(word_importance.items(), key=lambda x: x[1], reverse=True)
        
        for word, importance in sorted_words[:mask_count]:
            if word in self.synonym_database:
                # BERT風の置換候補生成
                candidates = self._generate_bert_candidates(word, importance)
                if candidates:
                    replacement = random.choice(candidates[:self.config.top_k])
                    masked_text = masked_text.replace(word, replacement, 1)
        
        return masked_text
    
    def _calculate_word_importance(self, words: List[str]) -> Dict[str, float]:
        """単語重要度を計算"""
        importance_map = {}
        
        for word in words:
            importance = 0.0
            
            # 品詞による重要度
            if word in self.synonym_database:
                importance += 0.8  # 類義語がある単語は重要
            
            # 長さによる重要度
            if len(word) > 3:
                importance += 0.6
            elif len(word) > 1:
                importance += 0.4
            
            # 出現頻度による重要度（低頻度ほど重要）
            frequency = words.count(word)
            importance += max(0.2, 1.0 / frequency)
            
            # ランダム性の追加
            importance *= (0.7 + random.random() * 0.6)
            
            importance_map[word] = min(1.0, importance)
        
        return importance_map
    
    def _generate_bert_candidates(self, word: str, importance: float) -> List[str]:
        """BERT風候補生成"""
        candidates = []
        
        # 類義語データベースから候補を取得
        if word in self.synonym_database:
            synonyms = self.synonym_database[word]
            
            # 重要度に基づく候補フィルタリング
            filtered_synonyms = []
            for synonym in synonyms:
                # 意味類似度のシミュレーション
                semantic_score = self._calculate_semantic_similarity(word, synonym)
                if semantic_score >= self.config.semantic_threshold:
                    filtered_synonyms.append(synonym)
            
            candidates.extend(filtered_synonyms)
        
        # 文脈的候補の生成
        contextual_candidates = self._generate_contextual_candidates(word)
        candidates.extend(contextual_candidates)
        
        return candidates
    
    def _calculate_semantic_similarity(self, word1: str, word2: str) -> float:
        """意味類似度を計算（簡易版）"""
        # 簡易的な実装（実際のBERTではより高度な計算を使用）
        if word1 == word2:
            return 1.0
        
        # 文字レベルの類似度
        char_similarity = len(set(word1) & set(word2)) / len(set(word1) | set(word2))
        
        # 長さの類似度
        length_similarity = min(len(word1), len(word2)) / max(len(word1), len(word2))
        
        # 総合類似度
        return (char_similarity + length_similarity) / 2 + random.uniform(0.0, 0.3)
    
    def _generate_contextual_candidates(self, word: str) -> List[str]:
        """文脈的候補を生成"""
        contextual_candidates = []
        
        # 形態素変化
        morphological_variants = self._generate_morphological_variants(word)
        contextual_candidates.extend(morphological_variants)
        
        # 語尾変化
        suffix_variants = self._generate_suffix_variants(word)
        contextual_candidates.extend(suffix_variants)
        
        return contextual_candidates
    
    def _generate_morphological_variants(self, word: str) -> List[str]:
        """形態素変化候補を生成"""
        variants = []
        
        # 基本的な形態素変化パターン
        morphological_patterns = {
            "する": ["を行う", "を実施する", "を遂行する"],
            "した": ["を行った", "を実施した", "を遂行した"],
            "である": ["となっている", "に相当する", "を表している"],
            "できる": ["可能である", "実現できる", "達成できる"],
        }
        
        for pattern, replacements in morphological_patterns.items():
            if word.endswith(pattern):
                base = word[:-len(pattern)]
                for replacement in replacements:
                    variants.append(base + replacement)
        
        return variants
    
    def _generate_suffix_variants(self, word: str) -> List[str]:
        """語尾変化候補を生成"""
        variants = []
        
        suffix_patterns = {
            "的": ["性", "風", "系"],
            "性": ["的", "風", "系"],
            "化": ["作用", "効果", "現象"],
            "法": ["手法", "方式", "技術"],
        }
        
        for suffix, alternatives in suffix_patterns.items():
            if word.endswith(suffix):
                base = word[:-len(suffix)]
                for alt in alternatives:
                    variants.append(base + alt)
        
        return variants
    
    def _apply_adversarial_substitution(self, text: str) -> str:
        """対抗的置換を実行"""
        adversarial_text = text
        
        # 対抗的パターンの適用
        for pattern_type, patterns in self.adversarial_patterns.items():
            for pattern, replacement in patterns:
                if random.random() < self.config.intensity * 0.7:
                    adversarial_text = re.sub(pattern, replacement, adversarial_text)
        
        return adversarial_text
    
    def _check_convergence(self, original: str, current: str, iteration: int) -> bool:
        """収束判定"""
        # 変化率が小さい場合は収束とみなす
        change_ratio = abs(len(current) - len(original)) / len(original)
        return change_ratio < 0.001 or iteration >= self.config.max_iterations - 1
    
    def _random_mask_strategy(self, words: List[str]) -> List[int]:
        """ランダムマスキング戦略"""
        mask_count = int(len(words) * self.config.mask_ratio)
        return random.sample(range(len(words)), min(mask_count, len(words)))
    
    def _frequency_mask_strategy(self, words: List[str]) -> List[int]:
        """頻度ベースマスキング戦略"""
        word_freq = {}
        for i, word in enumerate(words):
            word_freq[i] = words.count(word)
        
        # 低頻度語を優先的にマスク
        sorted_indices = sorted(word_freq.keys(), key=lambda x: word_freq[x])
        mask_count = int(len(words) * self.config.mask_ratio)
        return sorted_indices[:mask_count]
    
    def _importance_mask_strategy(self, words: List[str]) -> List[int]:
        """重要度ベースマスキング戦略"""
        importance_map = self._calculate_word_importance(words)
        
        # 重要度の高い単語を優先的にマスク
        word_importance_pairs = [(i, importance_map.get(word, 0.0)) for i, word in enumerate(words)]
        sorted_pairs = sorted(word_importance_pairs, key=lambda x: x[1], reverse=True)
        
        mask_count = int(len(words) * self.config.mask_ratio)
        return [pair[0] for pair in sorted_pairs[:mask_count]]
    
    def _syntactic_mask_strategy(self, words: List[str]) -> List[int]:
        """統語的マスキング戦略"""
        # 特定の品詞を優先的にマスク
        syntactic_priorities = ["名詞", "動詞", "形容詞", "副詞"]
        mask_indices = []
        
        for i, word in enumerate(words):
            # 簡易的な品詞判定
            if (word in self.synonym_database or 
                any(word.endswith(suffix) for suffix in ["する", "した", "である", "できる"])):
                mask_indices.append(i)
        
        mask_count = int(len(words) * self.config.mask_ratio)
        return mask_indices[:mask_count]
    
    def apply_advanced_bert_attack(self, text: str) -> str:
        """高度BERTAttack攻撃"""
        # 多段階BERT攻撃
        text = self._apply_multi_round_attack(text)
        text = self._apply_ensemble_attack(text)
        text = self._apply_adaptive_attack(text)
        
        return text
    
    def _apply_multi_round_attack(self, text: str) -> str:
        """多ラウンド攻撃"""
        current_text = text
        
        for round_num in range(3):  # 3ラウンド攻撃
            round_intensity = self.config.intensity * (0.8 ** round_num)  # 強度を徐々に減少
            
            # ラウンド別設定
            round_config = BERTAttackConfig(
                intensity=round_intensity,
                mask_ratio=self.config.mask_ratio * (0.9 ** round_num),
                top_k=max(3, self.config.top_k - round_num * 2)
            )
            
            round_attack = BERTAttack(round_config)
            current_text = round_attack.apply_bert_attack(current_text)
        
        return current_text
    
    def _apply_ensemble_attack(self, text: str) -> str:
        """アンサンブル攻撃"""
        # 複数の戦略を組み合わせ
        strategies = ["random_mask", "frequency_mask", "importance_mask", "syntactic_mask"]
        ensemble_results = []
        
        for strategy in strategies:
            strategy_text = self._apply_strategy_specific_attack(text, strategy)
            ensemble_results.append(strategy_text)
        
        # 最も変化の大きい結果を選択
        best_result = text
        max_change = 0
        
        for result in ensemble_results:
            change = abs(len(result) - len(text))
            if change > max_change:
                max_change = change
                best_result = result
        
        return best_result
    
    def _apply_strategy_specific_attack(self, text: str, strategy: str) -> str:
        """戦略特化攻撃"""
        words = text.split()
        
        if strategy in self.mask_strategies:
            mask_indices = self.mask_strategies[strategy](words)
            
            modified_words = words.copy()
            for idx in mask_indices:
                if idx < len(words) and words[idx] in self.synonym_database:
                    candidates = self.synonym_database[words[idx]]
                    if candidates:
                        modified_words[idx] = random.choice(candidates)
            
            return " ".join(modified_words)
        
        return text
    
    def _apply_adaptive_attack(self, text: str) -> str:
        """適応的攻撃"""
        # テキストの特性に基づく攻撃調整
        text_characteristics = self._analyze_text_characteristics(text)
        
        # 特性に基づく戦略選択
        if text_characteristics["complexity"] > 0.7:
            return self._apply_simplification_attack(text)
        elif text_characteristics["formality"] > 0.7:
            return self._apply_informalization_attack(text)
        else:
            return self._apply_diversification_attack(text)
    
    def _analyze_text_characteristics(self, text: str) -> Dict[str, float]:
        """テキスト特性を分析"""
        words = text.split()
        
        complexity = len([w for w in words if len(w) > 4]) / len(words) if words else 0
        formality = len([w for w in words if w in self.synonym_database]) / len(words) if words else 0
        
        return {
            "complexity": complexity,
            "formality": formality,
            "length": len(text),
            "sentence_count": text.count('。') + text.count('!')
        }
    
    def _apply_simplification_attack(self, text: str) -> str:
        """簡素化攻撃"""
        # 複雑な表現を簡単な表現に変更
        simplification_patterns = [
            ("について検討する", "を調べる"),
            ("に関して分析する", "を分析する"),
            ("において実施する", "で行う"),
            ("を活用して", "を使って"),
        ]
        
        simplified_text = text
        for complex_expr, simple_expr in simplification_patterns:
            if random.random() < self.config.intensity:
                simplified_text = simplified_text.replace(complex_expr, simple_expr)
        
        return simplified_text
    
    def _apply_informalization_attack(self, text: str) -> str:
        """非公式化攻撃"""
        # 正式な表現をより非公式な表現に変更
        informalization_patterns = [
            ("である", "だ"),
            ("を実施する", "をする"),
            ("について", "のことを"),
            ("に関して", "について"),
        ]
        
        informal_text = text
        for formal_expr, informal_expr in informalization_patterns:
            if random.random() < self.config.intensity * 0.5:
                informal_text = informal_text.replace(formal_expr, informal_expr)
        
        return informal_text
    
    def _apply_diversification_attack(self, text: str) -> str:
        """多様化攻撃"""
        # 語彙の多様性を増加
        return self.apply_bert_attack(text)
    
    def analyze_bert_attack_effect(self, original: str, modified: str) -> Dict:
        """BERTAttack効果を分析"""
        original_words = original.split()
        modified_words = modified.split()
        
        word_changes = 0
        for orig_word, mod_word in zip(original_words, modified_words):
            if orig_word != mod_word:
                word_changes += 1
        
        return {
            "character_change": len(modified) - len(original),
            "word_changes": word_changes,
            "word_change_ratio": word_changes / len(original_words) if original_words else 0,
            "vocabulary_diversity": len(set(modified_words)) / len(modified_words) if modified_words else 0,
            "semantic_preservation": self._estimate_overall_semantic_preservation(original, modified),
            "attack_intensity": self.config.intensity,
            "mask_ratio_used": self.config.mask_ratio,
        }
    
    def _estimate_overall_semantic_preservation(self, original: str, modified: str) -> float:
        """全体的な意味保持度を推定"""
        original_words = set(original.split())
        modified_words = set(modified.split())
        
        # Jaccard類似度
        intersection = len(original_words & modified_words)
        union = len(original_words | modified_words)
        
        jaccard_similarity = intersection / union if union > 0 else 0
        
        # 長さ類似度
        length_similarity = min(len(original), len(modified)) / max(len(original), len(modified))
        
        return (jaccard_similarity + length_similarity) / 2


def test_bert_attack():
    """テスト関数"""
    print("🤖 BERTAttack テスト開始")
    
    # テストテキスト
    test_text = """
    この研究では、新しい手法を開発して効果的な分析を行った。
    重要な発見により、従来の技術を大幅に改善することができた。
    """
    
    # 設定
    config = BERTAttackConfig(intensity=0.6, mask_ratio=0.2, top_k=5)
    attack = BERTAttack(config)
    
    # 攻撃実行
    basic_attack = attack.apply_bert_attack(test_text)
    advanced_attack = attack.apply_advanced_bert_attack(test_text)
    
    # 結果表示
    print(f"\n📝 元テキスト:\n{test_text}")
    print(f"\n🤖 基本BERTAttack:\n{basic_attack}")
    print(f"\n🚀 高度BERTAttack:\n{advanced_attack}")
    
    # 効果分析
    analysis = attack.analyze_bert_attack_effect(test_text, advanced_attack)
    print(f"\n📊 効果分析:")
    for key, value in analysis.items():
        print(f"  {key}: {value}")


if __name__ == "__main__":
    test_bert_attack() 