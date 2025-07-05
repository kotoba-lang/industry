# -*- coding: utf-8 -*-
"""
Watermark Evasion Attack Implementation

論文参照: "SynthID-Text Watermark Attack and Defense" (2024)
効果: ウォーターマーク除去・偽装による検出回避

この攻撃手法は生成モデルに埋め込まれた識別情報（ウォーターマーク）を
除去または偽造して、AI検出を回避します。
"""

import re
import random
import hashlib
import numpy as np
from typing import Dict, List, Tuple, Optional, Set
from dataclasses import dataclass
from collections import defaultdict


@dataclass
class WatermarkEvasionConfig:
    """ウォーターマーク回避攻撃設定"""
    intensity: float = 0.3  # 0.0-1.0の強度
    evasion_strategy: str = "removal"  # "removal", "spoofing", "hybrid"
    token_replacement_ratio: float = 0.15  # トークン置換比率
    statistical_threshold: float = 0.6  # 統計的閾値
    semantic_preservation: float = 0.9  # 意味保持度


class WatermarkEvasion:
    """
    ウォーターマーク回避攻撃クラス
    
    生成モデルに埋め込まれた識別情報（ウォーターマーク）を除去または偽造して、
    AI検出を回避する手法を実装。
    """
    
    def __init__(self, config: WatermarkEvasionConfig = None):
        self.config = config or WatermarkEvasionConfig()
        self.watermark_patterns = self._load_watermark_patterns()
        self.statistical_signatures = self._initialize_statistical_signatures()
        self.token_distribution_maps = self._build_token_distribution_maps()
        self.steganographic_channels = self._identify_steganographic_channels()
    
    def _load_watermark_patterns(self) -> Dict[str, List[str]]:
        """ウォーターマークパターンを読み込み"""
        return {
            "gpt_patterns": [
                # GPT系のよく使われる表現パターン
                "については", "に関して", "について", "を考慮すると",
                "と言えるでしょう", "ことができます", "と思われます",
                "の観点から", "という点で", "を踏まえると"
            ],
            "gemini_patterns": [
                # Gemini系の特徴的パターン
                "重要なポイントとして", "考慮すべき要素として", "注目すべき点は",
                "まず第一に", "さらに詳しく", "具体的には", "例を挙げると"
            ],
            "claude_patterns": [
                # Claude系の特徴的パターン
                "興味深いことに", "注目すべきは", "重要な観点として",
                "考察してみると", "分析してみましょう", "詳しく見ていくと"
            ],
            "repetitive_structures": [
                # 反復的構造（ウォーターマークの可能性）
                "ます。ます。", "です。です。", "である。である。",
                "について、について", "に関して、に関して"
            ],
            "statistical_anomalies": [
                # 統計的異常パターン
                "の", "は", "を", "に", "が", "で", "と", "も", "から", "まで"
            ]
        }
    
    def _initialize_statistical_signatures(self) -> Dict[str, callable]:
        """統計的署名を初期化"""
        return {}
    
    def _build_token_distribution_maps(self) -> Dict[str, Dict[str, float]]:
        """トークン分布マップを構築"""
        return {}
    
    def _identify_steganographic_channels(self) -> Dict[str, List[str]]:
        """ステガノグラフィチャネルを特定"""
        return {}
    
    def apply_watermark_evasion(self, text: str) -> str:
        """ウォーターマーク回避攻撃を適用"""
        if self.config.evasion_strategy == "removal":
            return self._apply_watermark_removal(text)
        elif self.config.evasion_strategy == "spoofing":
            return self._apply_watermark_spoofing(text)
        else:  # hybrid
            return self._apply_hybrid_evasion(text)
    
    def _apply_watermark_removal(self, text: str) -> str:
        """ウォーターマーク除去攻撃"""
        # ウォーターマークの検出
        watermark_locations = self._detect_watermarks(text)
        
        # パターンベース除去
        modified_text = text
        for watermark in watermark_locations:
            if watermark["confidence"] > 0.7:
                # 高信頼度のウォーターマークを置換
                pattern = watermark["text"]
                replacement = self._get_neutral_replacement(pattern)
                modified_text = modified_text.replace(pattern, replacement, 1)
        
        return modified_text
    
    def _apply_watermark_spoofing(self, text: str) -> str:
        """ウォーターマーク偽装攻撃"""
        # 偽のウォーターマークパターンを注入
        fake_patterns = ["と考察されます", "に留意すべきです", "という観点から"]
        
        sentences = text.split('。')
        spoofed_sentences = []
        
        for sentence in sentences:
            if sentence.strip():
                # 確率的に偽パターンを追加
                if random.random() < self.config.intensity * 0.2:
                    fake_pattern = random.choice(fake_patterns)
                    sentence = sentence.strip() + fake_pattern
                
                spoofed_sentences.append(sentence)
        
        return '。'.join(spoofed_sentences)
    
    def _apply_hybrid_evasion(self, text: str) -> str:
        """ハイブリッド回避攻撃"""
        # 除去と偽装の組み合わせ
        text = self._apply_watermark_removal(text)
        text = self._apply_watermark_spoofing(text)
        return text
    
    def _detect_watermarks(self, text: str) -> List[Dict]:
        """ウォーターマークを検出"""
        watermark_locations = []
        
        # パターンベース検出
        for category, patterns in self.watermark_patterns.items():
            for pattern in patterns:
                matches = [(m.start(), m.end(), pattern, category) 
                          for m in re.finditer(re.escape(pattern), text)]
                for start, end, matched_text, cat in matches:
                    watermark_locations.append({
                        "start": start,
                        "end": end,
                        "text": matched_text,
                        "category": cat,
                        "confidence": self._calculate_watermark_confidence(matched_text, cat)
                    })
        
        # 信頼度で並び替え
        watermark_locations.sort(key=lambda x: x["confidence"], reverse=True)
        
        return watermark_locations
    
    def _calculate_watermark_confidence(self, text: str, category: str) -> float:
        """ウォーターマーク信頼度を計算"""
        base_confidence = 0.5
        
        # カテゴリ別の重み
        category_weights = {
            "gpt_patterns": 0.8,
            "gemini_patterns": 0.9,
            "claude_patterns": 0.7,
            "repetitive_structures": 0.95,
            "statistical_anomalies": 0.6
        }
        
        confidence = base_confidence * category_weights.get(category, 0.5)
        
        # テキスト長による調整
        length_factor = min(1.0, len(text) / 20)
        confidence *= length_factor
        
        # ランダム性の追加
        confidence += random.uniform(-0.1, 0.1)
        
        return max(0.0, min(1.0, confidence))
    
    def _get_neutral_replacement(self, pattern: str) -> str:
        """中立的な置換表現を取得"""
        replacements = {
            "については": "について",
            "に関して": "について",
            "と言えるでしょう": "と考えられます",
            "ことができます": "ことが可能です",
            "重要なポイントとして": "重要なのは",
            "興味深いことに": "注目すべきは",
            "分析してみましょう": "分析します",
        }
        return replacements.get(pattern, pattern)
    
    def analyze_watermark_evasion_effect(self, original: str, modified: str) -> Dict:
        """ウォーターマーク回避効果を分析"""
        original_watermarks = self._detect_watermarks(original)
        modified_watermarks = self._detect_watermarks(modified)
        
        return {
            "character_change": len(modified) - len(original),
            "watermarks_removed": len(original_watermarks) - len(modified_watermarks),
            "watermark_reduction_ratio": 1.0 - (len(modified_watermarks) / max(1, len(original_watermarks))),
            "evasion_strategy": self.config.evasion_strategy,
        }


def test_watermark_evasion():
    """テスト関数"""
    print("🏷️  Watermark Evasion Attack テスト開始")
    
    # テストテキスト（ウォーターマークを含む）
    test_text = """
    この研究については、重要なポイントとして、新しいアプローチを提案します。
    興味深いことに、従来の手法と比較して、大幅な改善が見られました。
    と言えるでしょう。さらに詳しく分析してみましょう。
    """
    
    # 設定
    config = WatermarkEvasionConfig(
        intensity=0.6, 
        evasion_strategy="hybrid", 
        token_replacement_ratio=0.2
    )
    attack = WatermarkEvasion(config)
    
    # 攻撃実行
    hybrid_result = attack.apply_watermark_evasion(test_text)
    
    # 結果表示
    print(f"\n📝 元テキスト:\n{test_text}")
    print(f"\n🔄 ハイブリッド攻撃:\n{hybrid_result}")
    
    # 効果分析
    analysis = attack.analyze_watermark_evasion_effect(test_text, hybrid_result)
    print(f"\n📊 効果分析:")
    for key, value in analysis.items():
        print(f"  {key}: {value}")


if __name__ == "__main__":
    test_watermark_evasion()
