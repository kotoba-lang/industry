# -*- coding: utf-8 -*-
"""
研究ベースAI検出回避攻撃手法モジュール

このモジュールは5つの最先端研究論文に基づく攻撃手法を提供します:
1. Adversarial Paraphrasing (2025) - 敵対的言い換え
2. GradEscape (2025, USENIX Security) - 勾配ベース攻撃  
3. SilverSpeak (2024) - ホモグリフ攻撃
4. Syntactic Perturbation (2024, ACL) - 統語的摂動
5. Linguistic Complexity - 言語的複雑性攻撃
"""

from .adversarial_paraphrasing import AdversarialParaphrasing
from .grad_escape import GradEscape
from .silver_speak import SilverSpeak
from .syntactic_perturbation import SyntacticPerturbation
from .linguistic_complexity import LinguisticComplexity

__version__ = "1.0.0"
__author__ = "AI Research Team"

__all__ = [
    "AdversarialParaphrasing",
    "GradEscape", 
    "SilverSpeak",
    "SyntacticPerturbation",
    "LinguisticComplexity"
] 