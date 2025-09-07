"""
Attack Tree: 複合攻撃シナリオ実行システム
"""

import random
import json
from typing import Dict, List, Tuple, Any, Optional
from dataclasses import dataclass
from enum import Enum

# 各攻撃手法をインポート
from .perturbation_attack import PerturbationAttack
from .bert_attack import BERTAttack
from .copa_attack import CoPaAttack
from .watermark_evasion import WatermarkEvasionAttack
from .token_break import TokenBreakAttack


class AttackPriority(Enum):
    """攻撃優先度"""
    LOW = 1
    MEDIUM = 2
    HIGH = 3
    CRITICAL = 4


class AttackCategory(Enum):
    """攻撃カテゴリ"""
    PERTURBATION = "perturbation"
    SEMANTIC = "semantic"
    LINGUISTIC = "linguistic"
    TECHNICAL = "technical"
    STEGANOGRAPHY = "steganography"


@dataclass
class AttackNode:
    """攻撃ツリーのノード"""
    name: str
    attack_method: str
    category: AttackCategory
    priority: AttackPriority
    success_probability: float
    execution_time: float
    stealth_level: float
    prerequisites: List[str]
    exclusions: List[str]
    parameters: Dict[str, Any]


class AttackTree:
    """攻撃ツリーの実装クラス"""
    
    def __init__(self):
        """攻撃ツリーを初期化"""
        # 各攻撃手法のインスタンスを初期化
        self.attacks = {
            'perturbation': PerturbationAttack(),
            'bert': BERTAttack(),
            'copa': CoPaAttack(),
            'watermark': WatermarkEvasionAttack(),
            'token_break': TokenBreakAttack()
        }
        
        # 攻撃ノードの定義
        self.attack_nodes = self._initialize_attack_nodes()
        
        # 攻撃履歴
        self.execution_history = []
    
    def _initialize_attack_nodes(self) -> Dict[str, AttackNode]:
        """攻撃ノードを初期化"""
        return {
            "char_perturbation": AttackNode(
                name="文字レベル摂動",
                attack_method="perturbation",
                category=AttackCategory.PERTURBATION,
                priority=AttackPriority.MEDIUM,
                success_probability=0.7,
                execution_time=0.5,
                stealth_level=0.8,
                prerequisites=[],
                exclusions=["word_perturbation"],
                parameters={"mode": "character", "intensity": 0.3}
            ),
            "bert_synonym": AttackNode(
                name="BERT類義語攻撃",
                attack_method="bert",
                category=AttackCategory.SEMANTIC,
                priority=AttackPriority.HIGH,
                success_probability=0.8,
                execution_time=1.0,
                stealth_level=0.9,
                prerequisites=[],
                exclusions=[],
                parameters={"strategy": "synonym_replacement", "intensity": 0.4}
            ),
            "copa_basic": AttackNode(
                name="基本CoPA攻撃",
                attack_method="copa",
                category=AttackCategory.LINGUISTIC,
                priority=AttackPriority.MEDIUM,
                success_probability=0.7,
                execution_time=0.9,
                stealth_level=0.9,
                prerequisites=[],
                exclusions=[],
                parameters={"strategy": "contrastive_paraphrase", "intensity": 0.3}
            ),
            "watermark_removal": AttackNode(
                name="ウォーターマーク除去",
                attack_method="watermark",
                category=AttackCategory.STEGANOGRAPHY,
                priority=AttackPriority.HIGH,
                success_probability=0.8,
                execution_time=0.6,
                stealth_level=0.7,
                prerequisites=[],
                exclusions=[],
                parameters={"strategy": "removal", "intensity": 0.6}
            ),
            "token_zero_width": AttackNode(
                name="ゼロ幅文字挿入",
                attack_method="token_break",
                category=AttackCategory.TECHNICAL,
                priority=AttackPriority.LOW,
                success_probability=0.6,
                execution_time=0.3,
                stealth_level=0.95,
                prerequisites=[],
                exclusions=[],
                parameters={"strategy": "zero_width", "intensity": 0.4}
            )
        }
    
    def execute_attack_chain(self, text: str, attack_names: List[str]) -> Dict[str, Any]:
        """攻撃チェーンを実行"""
        current_text = text
        results = []
        
        for attack_name in attack_names:
            if attack_name not in self.attack_nodes:
                continue
                
            node = self.attack_nodes[attack_name]
            attack_instance = self.attacks[node.attack_method]
            
            # 攻撃を実行
            if node.attack_method == "perturbation":
                result = attack_instance.attack(current_text, **node.parameters)
            elif node.attack_method == "bert":
                result = attack_instance.attack(current_text, **node.parameters)
            elif node.attack_method == "copa":
                result = attack_instance.attack(current_text, **node.parameters)
            elif node.attack_method == "watermark":
                result = attack_instance.attack(current_text, **node.parameters)
            elif node.attack_method == "token_break":
                result = attack_instance.attack(current_text, **node.parameters)
            else:
                continue
            
            results.append({"attack_name": attack_name, "result": result})
            current_text = result["attacked_text"]
        
        return {
            "original_text": text,
            "final_text": current_text,
            "attack_chain": attack_names,
            "individual_results": results
        }


def test_attack_tree():
    """AttackTreeのテスト関数"""
    print("=== Attack Tree Test ===")
    
    tree = AttackTree()
    test_text = "This is a sample text for testing the attack tree functionality."
    
    # 攻撃チェーンのテスト
    print("\n--- Attack Chain Test ---")
    chain = ["token_zero_width", "bert_synonym", "watermark_removal"]
    chain_result = tree.execute_attack_chain(test_text, chain)
    print(f"Original: {chain_result['original_text']}")
    print(f"Final: {chain_result['final_text']}")
    print(f"Chain: {chain_result['attack_chain']}")
    
    print("\n=== Test Complete ===")


if __name__ == "__main__":
    test_attack_tree() 