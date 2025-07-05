"""
LLM Attack: Claude APIベース攻撃

Anthropic Claude APIを使用して、高度な自然言語理解に基づく
AI検出回避攻撃を実行するクラスです。

主な機能:
1. Claude APIを使った高度なパラフレーズ攻撃
2. 文脈理解に基づく自然な変換
3. AI検出を回避する文書の再生成
4. 複数のプロンプト戦略
"""

import requests
import json
import random
import time
from typing import Dict, List, Any, Optional


class LLMAttack:
    """Claude APIベース攻撃の実装クラス"""
    
    def __init__(self, api_key: str, intensity: float = 0.5):
        """
        LLM攻撃を初期化
        
        Args:
            api_key: Anthropic APIキー
            intensity: 攻撃の強度（0.0-1.0）
        """
        self.api_key = api_key
        self.intensity = intensity
        self.base_url = "https://api.anthropic.com/v1/messages"
        
        # プロンプト戦略の定義
        self.prompt_strategies = {
            "paraphrase": self._get_paraphrase_prompts(),
            "humanize": self._get_humanize_prompts(),
            "rewrite": self._get_rewrite_prompts(),
            "style_transfer": self._get_style_transfer_prompts(),
            "steganographic": self._get_steganographic_prompts()
        }
        
        # APIレート制限管理
        self.last_request_time = 0
        self.min_request_interval = 1.0  # 秒
    
    def _get_paraphrase_prompts(self) -> List[str]:
        """パラフレーズ用プロンプトを取得"""
        return [
            "Please rewrite the following text using different words and sentence structures while maintaining the exact same meaning. Focus on natural, human-like expression:",
            "Transform this text by changing vocabulary and phrasing, but keep the core message identical. Make it sound more conversational and natural:",
            "Rephrase this content using alternative expressions and sentence patterns. Maintain the original meaning while making it feel more human-written:",
            "Please provide a natural paraphrase of this text, using different word choices and structures while preserving the exact meaning:",
            "Rewrite this passage with varied vocabulary and syntax, ensuring it sounds naturally written by a human while conveying the same information:"
        ]
    
    def _get_humanize_prompts(self) -> List[str]:
        """人間化用プロンプトを取得"""
        return [
            "Make this text sound more human and less AI-generated. Add natural imperfections, varied sentence lengths, and personal touches:",
            "Transform this content to feel like it was written by a real person. Include natural flow, occasional informal language, and human-like expressions:",
            "Humanize this text by adding personality, natural rhythm, and the kind of small imperfections that make writing feel authentic:",
            "Rewrite this to sound like natural human writing. Include conversational elements, varied pacing, and authentic voice:",
            "Make this text feel genuinely human-authored by incorporating natural speech patterns, personal perspective, and organic flow:"
        ]
    
    def _get_rewrite_prompts(self) -> List[str]:
        """リライト用プロンプトを取得"""
        return [
            "Completely rewrite this text from scratch while maintaining the core information. Use a different approach and structure:",
            "Create a fresh version of this content using entirely different language and organization while preserving the key points:",
            "Generate a new version of this text with a completely different style and approach, but keep the essential meaning:",
            "Rewrite this content using a different perspective and structure, maintaining accuracy while changing the presentation:",
            "Create an alternative version of this text using different reasoning paths and expressions while keeping the core message:"
        ]
    
    def _get_style_transfer_prompts(self) -> List[str]:
        """スタイル転送用プロンプトを取得"""
        return [
            "Rewrite this text in a more casual, conversational style while keeping the information accurate:",
            "Transform this content into a more formal, academic tone while preserving all key details:",
            "Convert this text to sound more like creative writing, with vivid language and engaging prose:",
            "Rewrite this in a journalistic style, as if for a newspaper or magazine article:",
            "Transform this content into a more technical, precise style suitable for professional documentation:"
        ]
    
    def _get_steganographic_prompts(self) -> List[str]:
        """ステガノグラフィ用プロンプトを取得"""
        return [
            "Rewrite this text while subtly embedding the essence of natural human writing patterns and unconscious stylistic choices:",
            "Transform this content to include natural variations in sentence structure and word choice that reflect authentic human writing:",
            "Modify this text to incorporate subtle linguistic patterns that mirror natural human expression and thought processes:",
            "Rewrite this content with embedded natural language variations that reflect genuine human writing characteristics:",
            "Transform this text to include organic stylistic elements that naturally occur in human-authored content:"
        ]
    
    def attack(self, text: str, strategy: str = "paraphrase") -> Dict[str, Any]:
        """
        LLM攻撃を実行
        
        Args:
            text: 攻撃対象のテキスト
            strategy: 攻撃戦略 ("paraphrase", "humanize", "rewrite", "style_transfer", "steganographic")
        
        Returns:
            攻撃結果の辞書
        """
        if strategy not in self.prompt_strategies:
            strategy = "paraphrase"
        
        # レート制限の管理
        self._manage_rate_limit()
        
        # プロンプト選択
        prompts = self.prompt_strategies[strategy]
        selected_prompt = random.choice(prompts)
        
        # API呼び出し
        try:
            response = self._call_claude_api(selected_prompt, text)
            attacked_text = response.get("content", [{}])[0].get("text", text)
            
            # 効果分析
            analysis = self._analyze_effectiveness(text, attacked_text, strategy)
            
            return {
                "original_text": text,
                "attacked_text": attacked_text,
                "strategy": strategy,
                "prompt_used": selected_prompt,
                "intensity": self.intensity,
                "analysis": analysis,
                "attack_type": "LLM",
                "api_response": response
            }
            
        except Exception as e:
            # エラーハンドリング - フォールバック攻撃
            fallback_text = self._fallback_attack(text, strategy)
            
            return {
                "original_text": text,
                "attacked_text": fallback_text,
                "strategy": strategy,
                "intensity": self.intensity,
                "analysis": self._analyze_effectiveness(text, fallback_text, strategy),
                "attack_type": "LLM_Fallback",
                "error": str(e)
            }
    
    def _call_claude_api(self, prompt: str, text: str) -> Dict[str, Any]:
        """Claude APIを呼び出し"""
        headers = {
            "Content-Type": "application/json",
            "x-api-key": self.api_key,
            "anthropic-version": "2023-06-01"
        }
        
        # メッセージの構築
        full_prompt = f"{prompt}\n\nText to transform:\n{text}\n\nTransformed text:"
        
        data = {
            "model": "claude-3-sonnet-20240229",
            "max_tokens": min(4000, len(text) * 3),  # 元テキストの3倍まで
            "temperature": self.intensity,
            "messages": [
                {
                    "role": "user",
                    "content": full_prompt
                }
            ]
        }
        
        response = requests.post(self.base_url, headers=headers, json=data, timeout=30)
        
        if response.status_code == 200:
            return response.json()
        else:
            raise Exception(f"API call failed: {response.status_code} - {response.text}")
    
    def _manage_rate_limit(self):
        """APIレート制限を管理"""
        current_time = time.time()
        time_since_last_request = current_time - self.last_request_time
        
        if time_since_last_request < self.min_request_interval:
            sleep_time = self.min_request_interval - time_since_last_request
            time.sleep(sleep_time)
        
        self.last_request_time = time.time()
    
    def _fallback_attack(self, text: str, strategy: str) -> str:
        """API失敗時のフォールバック攻撃"""
        words = text.split()
        
        if strategy == "paraphrase":
            # 簡単な同義語置換
            synonyms = {
                "good": ["excellent", "fine", "great"],
                "bad": ["poor", "terrible", "awful"],
                "big": ["large", "huge", "enormous"],
                "small": ["tiny", "little", "mini"],
                "fast": ["quick", "rapid", "swift"],
                "slow": ["gradual", "leisurely", "unhurried"]
            }
            
            for i, word in enumerate(words):
                word_lower = word.lower().strip(".,!?")
                if word_lower in synonyms and random.random() < self.intensity:
                    replacement = random.choice(synonyms[word_lower])
                    words[i] = replacement if word.islower() else replacement.capitalize()
        
        elif strategy == "humanize":
            # 簡単な文構造変更
            if len(words) > 5 and random.random() < self.intensity:
                # 文の順序をランダムに変更
                mid_point = len(words) // 2
                first_half = words[:mid_point]
                second_half = words[mid_point:]
                words = second_half + [","] + first_half
        
        return " ".join(words)
    
    def _analyze_effectiveness(self, original: str, attacked: str, strategy: str) -> Dict[str, Any]:
        """攻撃効果の分析"""
        return {
            "length_change_ratio": len(attacked) / len(original) if original else 1.0,
            "word_change_rate": self._calculate_word_change_rate(original, attacked),
            "semantic_similarity": self._estimate_semantic_similarity(original, attacked),
            "naturalness_score": self._estimate_naturalness(attacked),
            "strategy_effectiveness": self._evaluate_strategy_effectiveness(strategy, original, attacked),
            "api_dependency": True
        }
    
    def _calculate_word_change_rate(self, original: str, attacked: str) -> float:
        """単語変更率を計算"""
        orig_words = original.lower().split()
        attack_words = attacked.lower().split()
        
        if not orig_words:
            return 0.0
        
        max_len = max(len(orig_words), len(attack_words))
        changes = 0
        
        for i in range(max_len):
            orig_word = orig_words[i] if i < len(orig_words) else ""
            attack_word = attack_words[i] if i < len(attack_words) else ""
            
            if orig_word != attack_word:
                changes += 1
        
        return changes / max_len
    
    def _estimate_semantic_similarity(self, original: str, attacked: str) -> float:
        """意味的類似度を推定"""
        # 簡易的な実装 - 共通単語の割合
        orig_words = set(original.lower().split())
        attack_words = set(attacked.lower().split())
        
        if not orig_words:
            return 1.0
        
        intersection = orig_words.intersection(attack_words)
        union = orig_words.union(attack_words)
        
        return len(intersection) / len(union) if union else 1.0
    
    def _estimate_naturalness(self, text: str) -> float:
        """自然さスコアを推定"""
        # 簡易的な自然さ指標
        words = text.split()
        sentences = text.split('.')
        
        # 文長の多様性
        sentence_lengths = [len(sent.split()) for sent in sentences if sent.strip()]
        length_variance = len(set(sentence_lengths)) / len(sentence_lengths) if sentence_lengths else 0
        
        # 語彙の多様性
        unique_words = len(set(word.lower() for word in words))
        vocabulary_diversity = unique_words / len(words) if words else 0
        
        return (length_variance + vocabulary_diversity) / 2
    
    def _evaluate_strategy_effectiveness(self, strategy: str, original: str, attacked: str) -> float:
        """戦略の効果を評価"""
        base_score = 0.5
        
        if strategy == "paraphrase":
            # パラフレーズの効果 - 単語変更率に基づく
            word_change_rate = self._calculate_word_change_rate(original, attacked)
            base_score = min(word_change_rate, 1.0)
        
        elif strategy == "humanize":
            # 人間化の効果 - 自然さスコアに基づく
            naturalness = self._estimate_naturalness(attacked)
            base_score = naturalness
        
        elif strategy == "rewrite":
            # リライトの効果 - 構造変更の程度
            structure_change = abs(len(attacked.split('.')) - len(original.split('.')))
            base_score = min(structure_change / max(len(original.split('.')), 1), 1.0)
        
        return base_score
    
    def multi_strategy_attack(self, text: str, strategies: List[str] = None) -> Dict[str, Any]:
        """複数戦略を組み合わせた攻撃"""
        if strategies is None:
            strategies = ["paraphrase", "humanize"]
        
        current_text = text
        results = []
        
        for strategy in strategies:
            result = self.attack(current_text, strategy)
            results.append(result)
            current_text = result["attacked_text"]
            
            # 各段階の間に短い休憩（APIレート制限対応）
            time.sleep(0.5)
        
        return {
            "original_text": text,
            "final_text": current_text,
            "strategies_used": strategies,
            "individual_results": results,
            "cumulative_analysis": self._analyze_effectiveness(text, current_text, "multi_strategy")
        }
    
    def adaptive_attack(self, text: str, target_change_rate: float = 0.7) -> Dict[str, Any]:
        """適応的攻撃 - 目標変更率に達するまで反復"""
        current_text = text
        attempts = []
        max_attempts = 5
        
        for attempt in range(max_attempts):
            # 現在の変更率を評価
            current_change_rate = self._calculate_word_change_rate(text, current_text)
            
            if current_change_rate >= target_change_rate:
                break
            
            # 不足分に応じて戦略を選択
            if current_change_rate < 0.3:
                strategy = "rewrite"
            elif current_change_rate < 0.5:
                strategy = "paraphrase"
            else:
                strategy = "humanize"
            
            result = self.attack(current_text, strategy)
            attempts.append({
                "attempt": attempt + 1,
                "strategy": strategy,
                "change_rate": current_change_rate,
                "text": result["attacked_text"]
            })
            current_text = result["attacked_text"]
        
        return {
            "original_text": text,
            "final_text": current_text,
            "target_change_rate": target_change_rate,
            "achieved_change_rate": self._calculate_word_change_rate(text, current_text),
            "attempts": attempts,
            "converged": self._calculate_word_change_rate(text, current_text) >= target_change_rate
        }


def test_llm_attack():
    """LLM攻撃のテスト関数"""
    print("=== LLM Attack Test ===")
    
    # APIキーを使用
    api_key = "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"
    attack = LLMAttack(api_key, intensity=0.6)
    
    test_texts = [
        "This is a sample text for testing LLM-based attack methods.",
        "Artificial intelligence systems can detect AI-generated content.",
        "The quick brown fox jumps over the lazy dog."
    ]
    
    strategies = ["paraphrase", "humanize", "rewrite"]
    
    for text in test_texts:
        print(f"\n--- Original: {text} ---")
        
        for strategy in strategies:
            print(f"\n{strategy.upper()} Strategy:")
            try:
                result = attack.attack(text, strategy)
                print(f"Attacked: {result['attacked_text']}")
                print(f"Word Change Rate: {result['analysis']['word_change_rate']:.3f}")
                print(f"Semantic Similarity: {result['analysis']['semantic_similarity']:.3f}")
                print(f"Naturalness: {result['analysis']['naturalness_score']:.3f}")
            except Exception as e:
                print(f"Error: {e}")
        
        print("-" * 50)
    
    print("\n=== Test Complete ===")


if __name__ == "__main__":
    test_llm_attack() 