"""
Attack Tree Test - スタンドアロン版

複数の攻撃手法を組み合わせた複合攻撃のテスト
"""

import sys
import os
sys.path.append(os.path.dirname(os.path.abspath(__file__)))

from perturbation_attack import PerturbationAttack
from bert_attack import BERTAttack
from copa_attack import CoPAAttack
from watermark_evasion import WatermarkEvasion
from token_break import TokenBreakAttack


class SimpleAttackTree:
    """簡単な攻撃ツリー実装"""
    
    def __init__(self):
        # 各攻撃手法のインスタンスを初期化
        self.attacks = {
            'perturbation': PerturbationAttack(),
            'bert': BERTAttack(),
            'copa': CoPAAttack(),
            'watermark': WatermarkEvasion(),
            'token_break': TokenBreakAttack()
        }
    
    def execute_multi_attack(self, text: str, attack_sequence: list) -> dict:
        """複数攻撃を順次実行"""
        current_text = text
        results = []
        
        for attack_name in attack_sequence:
            if attack_name not in self.attacks:
                print(f"Unknown attack: {attack_name}")
                continue
            
            print(f"\n--- Applying {attack_name.upper()} ---")
            attack_instance = self.attacks[attack_name]
            
            # 攻撃を実行
            if attack_name == "perturbation":
                result = attack_instance.attack(current_text, mode="mixed", intensity=0.4)
            elif attack_name == "bert":
                result = attack_instance.attack(current_text, strategy="synonym_replacement", intensity=0.4)
            elif attack_name == "copa":
                result = attack_instance.attack(current_text, strategy="contrastive_paraphrase", intensity=0.3)
            elif attack_name == "watermark":
                result = attack_instance.attack(current_text, strategy="removal", intensity=0.5)
            elif attack_name == "token_break":
                result = attack_instance.attack(current_text, strategy="mixed", intensity=0.3)
            
            print(f"Original: {current_text[:100]}...")
            print(f"Attacked: {result['attacked_text'][:100]}...")
            
            results.append({
                "attack": attack_name,
                "result": result
            })
            
            current_text = result["attacked_text"]
        
        return {
            "original_text": text,
            "final_text": current_text,
            "attack_sequence": attack_sequence,
            "individual_results": results
        }


def test_attack_tree():
    """Attack Treeのテスト"""
    print("=== Attack Tree Integration Test ===")
    
    tree = SimpleAttackTree()
    
    test_text = """
    この研究では、大規模言語モデルの性能評価を行った。実験の結果、
    従来の手法と比較して顕著な改善が見られた。特に、精度の向上が
    重要な成果として挙げられる。今後の研究では、さらなる最適化を
    検討する予定である。
    """
    
    # 攻撃シーケンスのテスト
    attack_sequences = [
        ["token_break", "bert"],
        ["perturbation", "copa", "watermark"],
        ["bert", "token_break", "watermark"],
        ["copa", "perturbation"],
        ["watermark", "bert", "token_break"]
    ]
    
    for i, sequence in enumerate(attack_sequences):
        print(f"\n{'='*50}")
        print(f"Attack Sequence {i+1}: {' -> '.join(sequence)}")
        print(f"{'='*50}")
        
        result = tree.execute_multi_attack(test_text.strip(), sequence)
        
        print(f"\nFinal Result:")
        print(f"Original Length: {len(result['original_text'])}")
        print(f"Final Length: {len(result['final_text'])}")
        print(f"Attacks Applied: {len(result['individual_results'])}")
        
        # 変更率の計算
        original_words = set(result['original_text'].split())
        final_words = set(result['final_text'].split())
        change_rate = 1 - (len(original_words & final_words) / len(original_words))
        print(f"Word Change Rate: {change_rate:.3f}")
        
        print(f"\nFinal Text Preview:")
        print(f"{result['final_text'][:200]}...")
    
    print(f"\n{'='*50}")
    print("Integration Test Complete!")
    print(f"{'='*50}")


if __name__ == "__main__":
    test_attack_tree() 