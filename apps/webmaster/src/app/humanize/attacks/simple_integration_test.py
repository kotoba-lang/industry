#!/usr/bin/env python3
"""
Simple Integration Test for AI Detection Evasion Attacks

各攻撃手法を個別にテストして統合を確認
"""

def test_all_attacks():
    """全攻撃手法の統合テスト"""
    print("=== AI検出回避攻撃手法 統合テスト ===\n")
    
    test_text = "この研究では、最新の機械学習手法を用いて自然言語処理の性能向上を図った。"
    
    # 1. TokenBreak Attack
    try:
        from token_break import TokenBreakAttack
        attack = TokenBreakAttack()
        result = attack.attack(test_text, "mixed")
        print("✅ TokenBreak Attack: 成功")
        print(f"   Original: {test_text}")
        print(f"   Modified: {result['attacked_text']}")
        print(f"   Invisible Changes: {result['invisible_changes']}")
        print()
    except Exception as e:
        print(f"❌ TokenBreak Attack: 失敗 - {e}\n")
    
    # 2. BERT Attack
    try:
        from bert_attack import BERTAttack
        attack = BERTAttack()
        result = attack.attack(test_text, "synonym_replacement")
        print("✅ BERT Attack: 成功")
        print(f"   Original: {test_text}")
        print(f"   Modified: {result['attacked_text']}")
        print(f"   Synonyms: {result['analysis']['synonym_replacements']}")
        print()
    except Exception as e:
        print(f"❌ BERT Attack: 失敗 - {e}\n")
    
    # 3. CoPA Attack
    try:
        from copa_attack import CoPAAttack
        attack = CoPAAttack()
        result = attack.attack(test_text, "contrastive_paraphrase")
        print("✅ CoPA Attack: 成功")
        print(f"   Original: {test_text}")
        print(f"   Modified: {result['attacked_text']}")
        print(f"   Naturalness: {result['analysis']['naturalness_score']:.3f}")
        print()
    except Exception as e:
        print(f"❌ CoPA Attack: 失敗 - {e}\n")
    
    # 4. Watermark Evasion
    try:
        from watermark_evasion import WatermarkEvasion
        attack = WatermarkEvasion()
        result = attack.attack(test_text, "removal")
        print("✅ Watermark Evasion: 成功")
        print(f"   Original: {test_text}")
        print(f"   Modified: {result['attacked_text']}")
        print(f"   Confidence: {result['analysis']['removal_confidence']:.3f}")
        print()
    except Exception as e:
        print(f"❌ Watermark Evasion: 失敗 - {e}\n")
    
    # 5. Perturbation Attack
    try:
        from perturbation_attack import PerturbationAttack
        attack = PerturbationAttack()
        result = attack.attack(test_text, "mixed")
        print("✅ Perturbation Attack: 成功")
        print(f"   Original: {test_text}")
        print(f"   Modified: {result['attacked_text']}")
        print(f"   Effectiveness: {result['analysis']['effectiveness_score']:.3f}")
        print()
    except Exception as e:
        print(f"❌ Perturbation Attack: 失敗 - {e}\n")
    
    # 6. LLM Attack (簡易テスト)
    try:
        from llm_attack import LLMAttack
        # テスト用の仮のAPIキー
        api_key = "test-key"
        attack = LLMAttack(api_key, intensity=0.5)
        # フォールバック攻撃のテスト
        result = attack._fallback_attack(test_text, "paraphrase")
        print("✅ LLM Attack (Fallback): 成功")
        print(f"   Original: {test_text}")
        print(f"   Modified: {result}")
        print()
    except Exception as e:
        print(f"❌ LLM Attack: 失敗 - {e}\n")
    
    print("=== 統合テスト完了 ===")
    print("Note: 実際のWebアプリケーションでは、これらの攻撃手法が")
    print("      UIで選択され、APIエンドポイント経由で実行されます。")


def test_attack_combination():
    """攻撃手法の組み合わせテスト"""
    print("\n=== 攻撃手法組み合わせテスト ===\n")
    
    test_text = "AI検出システムは、文章の統計的パターンを分析してAI生成テキストを識別する。"
    current_text = test_text
    
    try:
        # Step 1: TokenBreak
        from token_break import TokenBreakAttack
        attack1 = TokenBreakAttack()
        result1 = attack1.attack(current_text, "zero_width")
        current_text = result1['attacked_text']
        print(f"Step 1 - TokenBreak: {current_text}")
        
        # Step 2: BERT Attack
        from bert_attack import BERTAttack
        attack2 = BERTAttack()
        result2 = attack2.attack(current_text, "synonym_replacement")
        current_text = result2['attacked_text']
        print(f"Step 2 - BERT: {current_text}")
        
        # Step 3: Watermark Evasion
        from watermark_evasion import WatermarkEvasion
        attack3 = WatermarkEvasion()
        result3 = attack3.attack(current_text, "removal")
        current_text = result3['attacked_text']
        print(f"Step 3 - Watermark: {current_text}")
        
        print(f"\n✅ 組み合わせ攻撃成功!")
        print(f"Original: {test_text}")
        print(f"Final:    {current_text}")
        
        # 変更統計
        original_len = len(test_text)
        final_len = len(current_text)
        change_ratio = abs(final_len - original_len) / original_len
        print(f"Length Change: {change_ratio:.3f}")
        
    except Exception as e:
        print(f"❌ 組み合わせ攻撃失敗: {e}")


if __name__ == "__main__":
    test_all_attacks()
    test_attack_combination() 