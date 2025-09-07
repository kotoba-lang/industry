#!/usr/bin/env python3
"""
包括的GEN理論統合テストフレームワーク
Comprehensive GEN Theory Integration Test Framework

GEN-情報理論の全実装要素を統合的に検証する包括的テストスイート

機能:
1. 基本理論実装テスト
2. 数学的厳密性検証
3. 実験検証プロトコルテスト
4. 性能・精度評価
5. 統合システム検証
6. 回帰テスト

Author: Jun Kawasaki
Date: 2025-01-27
Version: 1.0 - Comprehensive Integration Testing
"""

import unittest
import numpy as np
import pandas as pd
import matplotlib.pyplot as plt
import sys
import os
from pathlib import Path
import time
import json
import logging
from typing import Dict, List, Tuple, Any, Optional
from dataclasses import dataclass

# プロジェクトルートをパスに追加
project_root = Path(__file__).parent
sys.path.append(str(project_root))

# GEN理論モジュールのインポート
try:
    from core_framework.generative_gen_physics_framework import GenerativeGENFramework
    from core_framework.gen_sigma8_optimization import GENSigma8Optimizer
    from verification.mathematical_rigor_verification import MathematicalRigorFramework
    from verification.experimental_verification_protocol import ExperimentalVerificationFramework
    from works.generative_energy.advanced_simulation import AdvancedGENSimulation, QuantumFieldParameters
    from works.generative_energy.fast_simulation import FastGENSimulator, FastGENParameters
except ImportError as e:
    logging.warning(f"一部のモジュールをインポートできませんでした: {e}")

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

@dataclass
class TestResult:
    """テスト結果データクラス"""
    test_name: str
    passed: bool
    execution_time: float
    details: Dict[str, Any]
    error_message: Optional[str] = None

class CoreFrameworkTests(unittest.TestCase):
    """コアフレームワークテスト"""
    
    def setUp(self):
        """テスト初期化"""
        self.test_results = []
        
    def test_basic_gen_framework_functionality(self):
        """基本GENフレームワーク機能テスト"""
        start_time = time.time()
        
        try:
            # フレームワーク初期化テスト
            framework = GenerativeGENFramework()
            self.assertIsNotNone(framework)
            
            # 基本計算テスト
            basic_result = framework.calculate_gen_field_evolution()
            self.assertIsNotNone(basic_result)
            self.assertIn('gen_field', basic_result)
            
            # σ8計算テスト
            sigma8_result = framework.calculate_sigma8_prediction()
            self.assertIsInstance(sigma8_result, (int, float))
            self.assertGreater(sigma8_result, 0)
            
            execution_time = time.time() - start_time
            self.test_results.append(TestResult(
                test_name="basic_gen_framework",
                passed=True,
                execution_time=execution_time,
                details={
                    'framework_initialized': True,
                    'basic_calculation': True,
                    'sigma8_calculation': True,
                    'sigma8_value': sigma8_result
                }
            ))
            
        except Exception as e:
            execution_time = time.time() - start_time
            self.test_results.append(TestResult(
                test_name="basic_gen_framework",
                passed=False,
                execution_time=execution_time,
                details={},
                error_message=str(e)
            ))
            self.fail(f"基本GENフレームワークテスト失敗: {e}")
    
    def test_sigma8_optimization(self):
        """σ8最適化テスト"""
        start_time = time.time()
        
        try:
            # 最適化器初期化
            optimizer = GENSigma8Optimizer()
            self.assertIsNotNone(optimizer)
            
            # 最適化実行
            optimization_result = optimizer.optimize_parameters()
            self.assertIsNotNone(optimization_result)
            self.assertIn('optimal_parameters', optimization_result)
            self.assertIn('final_sigma8', optimization_result)
            
            # 精度検証
            final_sigma8 = optimization_result['final_sigma8']
            target_sigma8 = 0.8111  # Planck 2018
            relative_error = abs(final_sigma8 - target_sigma8) / target_sigma8
            
            self.assertLess(relative_error, 0.05, "σ8精度が5%以内であること")
            
            execution_time = time.time() - start_time
            self.test_results.append(TestResult(
                test_name="sigma8_optimization",
                passed=True,
                execution_time=execution_time,
                details={
                    'final_sigma8': final_sigma8,
                    'target_sigma8': target_sigma8,
                    'relative_error': relative_error,
                    'optimization_success': optimization_result.get('success', False)
                }
            ))
            
        except Exception as e:
            execution_time = time.time() - start_time
            self.test_results.append(TestResult(
                test_name="sigma8_optimization",
                passed=False,
                execution_time=execution_time,
                details={},
                error_message=str(e)
            ))
            self.fail(f"σ8最適化テスト失敗: {e}")

class MathematicalRigorTests(unittest.TestCase):
    """数学的厳密性テスト"""
    
    def setUp(self):
        """テスト初期化"""
        self.test_results = []
        
    def test_axiom_system_consistency(self):
        """公理系一貫性テスト"""
        start_time = time.time()
        
        try:
            # 数学的厳密性フレームワーク初期化
            rigor_framework = MathematicalRigorFramework()
            self.assertIsNotNone(rigor_framework)
            
            # 一貫性証明実行
            consistency_results = rigor_framework.consistency_prover.prove_axiom_consistency()
            self.assertIsNotNone(consistency_results)
            
            # 一貫性検証
            self.assertTrue(consistency_results['overall_consistent'], "公理系が一貫していること")
            self.assertTrue(consistency_results['self_consistency']['is_consistent'])
            self.assertTrue(consistency_results['mutual_consistency']['is_consistent'])
            
            execution_time = time.time() - start_time
            self.test_results.append(TestResult(
                test_name="axiom_system_consistency",
                passed=True,
                execution_time=execution_time,
                details=consistency_results
            ))
            
        except Exception as e:
            execution_time = time.time() - start_time
            self.test_results.append(TestResult(
                test_name="axiom_system_consistency",
                passed=False,
                execution_time=execution_time,
                details={},
                error_message=str(e)
            ))
            self.fail(f"公理系一貫性テスト失敗: {e}")
    
    def test_theorem_proofs(self):
        """定理証明テスト"""
        start_time = time.time()
        
        try:
            rigor_framework = MathematicalRigorFramework()
            
            # 基本定理証明
            theorem_proofs = rigor_framework.theorem_prover.prove_fundamental_theorems()
            self.assertIsNotNone(theorem_proofs)
            
            # 証明成功数の確認
            proven_count = sum(1 for proof in theorem_proofs.values() if proof.get('proven', False))
            total_count = len(theorem_proofs)
            proof_ratio = proven_count / total_count
            
            self.assertGreaterEqual(proof_ratio, 0.8, "80%以上の定理が証明されていること")
            
            execution_time = time.time() - start_time
            self.test_results.append(TestResult(
                test_name="theorem_proofs",
                passed=True,
                execution_time=execution_time,
                details={
                    'proven_count': proven_count,
                    'total_count': total_count,
                    'proof_ratio': proof_ratio,
                    'theorem_details': theorem_proofs
                }
            ))
            
        except Exception as e:
            execution_time = time.time() - start_time
            self.test_results.append(TestResult(
                test_name="theorem_proofs",
                passed=False,
                execution_time=execution_time,
                details={},
                error_message=str(e)
            ))
            self.fail(f"定理証明テスト失敗: {e}")

class ExperimentalVerificationTests(unittest.TestCase):
    """実験検証テスト"""
    
    def setUp(self):
        """テスト初期化"""
        self.test_results = []
        
    def test_experimental_protocol_design(self):
        """実験プロトコル設計テスト"""
        start_time = time.time()
        
        try:
            # 実験検証フレームワーク初期化
            exp_framework = ExperimentalVerificationFramework()
            self.assertIsNotNone(exp_framework)
            
            # 包括的検証プロトコル作成
            protocol = exp_framework.create_comprehensive_verification_protocol()
            self.assertIsNotNone(protocol)
            
            # プロトコル要素の確認
            self.assertIn('experimental_designs', protocol)
            self.assertIn('statistical_analysis_plan', protocol)
            self.assertIn('detection_capabilities', protocol)
            
            # 検出能力評価
            detection_capabilities = protocol['detection_capabilities']
            avg_detection_prob = np.mean([
                cap['detection_probability'] for cap in detection_capabilities.values()
            ])
            
            self.assertGreater(avg_detection_prob, 0.7, "平均検出確率が70%以上であること")
            
            execution_time = time.time() - start_time
            self.test_results.append(TestResult(
                test_name="experimental_protocol_design",
                passed=True,
                execution_time=execution_time,
                details={
                    'protocol_created': True,
                    'avg_detection_probability': avg_detection_prob,
                    'experimental_types': list(protocol['experimental_designs'].keys())
                }
            ))
            
        except Exception as e:
            execution_time = time.time() - start_time
            self.test_results.append(TestResult(
                test_name="experimental_protocol_design",
                passed=False,
                execution_time=execution_time,
                details={},
                error_message=str(e)
            ))
            self.fail(f"実験プロトコル設計テスト失敗: {e}")

class PerformanceTests(unittest.TestCase):
    """性能テスト"""
    
    def setUp(self):
        """テスト初期化"""
        self.test_results = []
        
    def test_advanced_simulation_performance(self):
        """高精度シミュレーション性能テスト"""
        start_time = time.time()
        
        try:
            # パラメータ初期化
            params = QuantumFieldParameters()
            simulator = AdvancedGENSimulation(params)
            
            # 基本計算性能テスト
            calc_start = time.time()
            result = simulator.energy_generation_rate(
                matter_density=0.1,
                energy_density=1e5,
                field_strength=0.001,
                volume=0.00005
            )
            calc_time = time.time() - calc_start
            
            self.assertIsNotNone(result)
            self.assertIn('quantum_corrected_rate', result)
            self.assertLess(calc_time, 10.0, "基本計算が10秒以内で完了すること")
            
            execution_time = time.time() - start_time
            self.test_results.append(TestResult(
                test_name="advanced_simulation_performance",
                passed=True,
                execution_time=execution_time,
                details={
                    'calculation_time': calc_time,
                    'generation_rate': result['quantum_corrected_rate'],
                    'efficiency': result['efficiency']
                }
            ))
            
        except Exception as e:
            execution_time = time.time() - start_time
            self.test_results.append(TestResult(
                test_name="advanced_simulation_performance",
                passed=False,
                execution_time=execution_time,
                details={},
                error_message=str(e)
            ))
            self.fail(f"高精度シミュレーション性能テスト失敗: {e}")
    
    def test_fast_simulation_performance(self):
        """高速シミュレーション性能テスト"""
        start_time = time.time()
        
        try:
            # パラメータ初期化
            params = FastGENParameters()
            simulator = FastGENSimulator(params)
            
            # 高速計算性能テスト
            calc_start = time.time()
            result = simulator.energy_generation_rate_fast(
                matter_density=0.1,
                energy_density=1e5,
                magnetic_field=0.001,
                volume=0.00005
            )
            calc_time = time.time() - calc_start
            
            self.assertIsNotNone(result)
            self.assertIn('generation_rate', result)
            self.assertLess(calc_time, 1.0, "高速計算が1秒以内で完了すること")
            
            execution_time = time.time() - start_time
            self.test_results.append(TestResult(
                test_name="fast_simulation_performance",
                passed=True,
                execution_time=execution_time,
                details={
                    'calculation_time': calc_time,
                    'generation_rate': result['generation_rate'],
                    'efficiency': result['efficiency']
                }
            ))
            
        except Exception as e:
            execution_time = time.time() - start_time
            self.test_results.append(TestResult(
                test_name="fast_simulation_performance",
                passed=False,
                execution_time=execution_time,
                details={},
                error_message=str(e)
            ))
            self.fail(f"高速シミュレーション性能テスト失敗: {e}")

class IntegrationTests(unittest.TestCase):
    """統合テスト"""
    
    def setUp(self):
        """テスト初期化"""
        self.test_results = []
        
    def test_full_system_integration(self):
        """完全システム統合テスト"""
        start_time = time.time()
        
        try:
            # 各サブシステムの初期化
            gen_framework = GenerativeGENFramework()
            rigor_framework = MathematicalRigorFramework()
            exp_framework = ExperimentalVerificationFramework()
            
            # 統合動作テスト
            # 1. 基本理論計算
            theory_result = gen_framework.calculate_gen_field_evolution()
            
            # 2. 数学的検証
            rigor_result = rigor_framework.complete_mathematical_rigor()
            
            # 3. 実験プロトコル生成
            exp_protocol = exp_framework.create_comprehensive_verification_protocol()
            
            # 統合結果の検証
            self.assertIsNotNone(theory_result)
            self.assertIsNotNone(rigor_result)
            self.assertIsNotNone(exp_protocol)
            
            # 一貫性チェック
            theory_consistent = rigor_result['consistency_results']['overall_consistent']
            experimental_feasible = all(
                cap['detection_probability'] > 0.5 
                for cap in exp_protocol['detection_capabilities'].values()
            )
            
            self.assertTrue(theory_consistent, "理論的一貫性が保たれていること")
            self.assertTrue(experimental_feasible, "実験的実現可能性があること")
            
            execution_time = time.time() - start_time
            self.test_results.append(TestResult(
                test_name="full_system_integration",
                passed=True,
                execution_time=execution_time,
                details={
                    'theory_consistent': theory_consistent,
                    'experimental_feasible': experimental_feasible,
                    'rigor_level': rigor_result['rigor_assessment']['level']
                }
            ))
            
        except Exception as e:
            execution_time = time.time() - start_time
            self.test_results.append(TestResult(
                test_name="full_system_integration",
                passed=False,
                execution_time=execution_time,
                details={},
                error_message=str(e)
            ))
            self.fail(f"完全システム統合テスト失敗: {e}")

class ComprehensiveTestSuite:
    """包括的テストスイート"""
    
    def __init__(self):
        self.all_test_results = []
        
    def run_all_tests(self) -> Dict[str, Any]:
        """全テストの実行"""
        
        logger.info("🧪 包括的GEN理論テストスイート開始")
        print("=" * 60)
        
        test_classes = [
            CoreFrameworkTests,
            MathematicalRigorTests,
            ExperimentalVerificationTests,
            PerformanceTests,
            IntegrationTests
        ]
        
        total_start_time = time.time()
        
        for test_class in test_classes:
            print(f"\n🔬 {test_class.__name__} 実行中...")
            
            suite = unittest.TestLoader().loadTestsFromTestCase(test_class)
            runner = unittest.TextTestRunner(verbosity=1, stream=open(os.devnull, 'w'))
            result = runner.run(suite)
            
            # テスト結果の収集
            test_instance = test_class()
            test_instance.setUp()
            
            for test_method in unittest.TestLoader().getTestCaseNames(test_class):
                try:
                    getattr(test_instance, test_method)()
                    if hasattr(test_instance, 'test_results'):
                        self.all_test_results.extend(test_instance.test_results)
                except Exception as e:
                    self.all_test_results.append(TestResult(
                        test_name=test_method,
                        passed=False,
                        execution_time=0,
                        details={},
                        error_message=str(e)
                    ))
        
        total_execution_time = time.time() - total_start_time
        
        # 結果サマリー
        summary = self.generate_test_summary(total_execution_time)
        
        print(f"\n📊 テスト完了")
        print(f"総実行時間: {total_execution_time:.2f}秒")
        print(f"合格率: {summary['pass_rate']:.1f}%")
        print(f"合格/総数: {summary['passed_count']}/{summary['total_count']}")
        
        return summary
    
    def generate_test_summary(self, total_execution_time: float) -> Dict[str, Any]:
        """テストサマリーの生成"""
        
        passed_count = sum(1 for result in self.all_test_results if result.passed)
        total_count = len(self.all_test_results)
        pass_rate = (passed_count / total_count * 100) if total_count > 0 else 0
        
        # カテゴリ別集計
        category_results = {}
        for result in self.all_test_results:
            category = result.test_name.split('_')[0] if '_' in result.test_name else 'other'
            if category not in category_results:
                category_results[category] = {'passed': 0, 'total': 0}
            category_results[category]['total'] += 1
            if result.passed:
                category_results[category]['passed'] += 1
        
        # 失敗したテストの詳細
        failed_tests = [result for result in self.all_test_results if not result.passed]
        
        summary = {
            'total_execution_time': total_execution_time,
            'total_count': total_count,
            'passed_count': passed_count,
            'failed_count': total_count - passed_count,
            'pass_rate': pass_rate,
            'category_results': category_results,
            'failed_tests': [
                {
                    'name': test.test_name,
                    'error': test.error_message,
                    'execution_time': test.execution_time
                } for test in failed_tests
            ],
            'performance_metrics': {
                'avg_execution_time': np.mean([r.execution_time for r in self.all_test_results]),
                'max_execution_time': max([r.execution_time for r in self.all_test_results]),
                'min_execution_time': min([r.execution_time for r in self.all_test_results])
            }
        }
        
        return summary
    
    def save_test_report(self, summary: Dict[str, Any], filename: str = "comprehensive_test_report.json"):
        """テストレポートの保存"""
        
        # 結果をJSONシリアライズ可能な形式に変換
        serializable_results = []
        for result in self.all_test_results:
            serializable_results.append({
                'test_name': result.test_name,
                'passed': result.passed,
                'execution_time': result.execution_time,
                'details': self.make_serializable(result.details),
                'error_message': result.error_message
            })
        
        report = {
            'summary': summary,
            'detailed_results': serializable_results,
            'timestamp': time.time(),
            'test_framework_version': '1.0'
        }
        
        with open(filename, 'w', encoding='utf-8') as f:
            json.dump(report, f, indent=2, ensure_ascii=False, default=str)
        
        logger.info(f"テストレポートを {filename} に保存しました")
        
    def make_serializable(self, obj):
        """オブジェクトをJSONシリアライズ可能にする"""
        if isinstance(obj, dict):
            return {k: self.make_serializable(v) for k, v in obj.items()}
        elif isinstance(obj, (list, tuple)):
            return [self.make_serializable(item) for item in obj]
        elif isinstance(obj, np.ndarray):
            return obj.tolist()
        elif isinstance(obj, (np.integer, np.floating)):
            return obj.item()
        else:
            return obj

def main():
    """メイン実行関数"""
    
    print("🧪 GEN理論包括的統合テストフレームワーク")
    print("=" * 70)
    
    # テストスイート実行
    test_suite = ComprehensiveTestSuite()
    summary = test_suite.run_all_tests()
    
    # レポート保存
    test_suite.save_test_report(summary)
    
    # 最終評価
    if summary['pass_rate'] >= 90:
        print("\n🎉 優秀！GEN理論実装は高品質です")
        print("統合判定への準備が整いました。")
    elif summary['pass_rate'] >= 75:
        print("\n⭐ 良好！GEN理論実装は実用レベルです")
        print("一部の改善により完成度を高められます。")
    elif summary['pass_rate'] >= 50:
        print("\n🔧 改善必要！基本機能は動作しています")
        print("品質向上のための追加作業が必要です。")
    else:
        print("\n🚨 重大な問題があります")
        print("基本的な実装に問題があります。")
    
    print(f"\n詳細なテスト結果は comprehensive_test_report.json をご確認ください。")
    
    return summary

if __name__ == "__main__":
    main() 