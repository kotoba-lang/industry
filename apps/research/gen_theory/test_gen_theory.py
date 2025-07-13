#!/usr/bin/env python3
"""
GEN-Information Theory Test Suite
================================

GEN理論の主要機能をテストするテストスイート

Author: Jun Kawasaki
Date: 2025-01-27
"""

import sys
import os
import time
import traceback

# パスの設定
current_dir = os.path.dirname(os.path.abspath(__file__))
sys.path.append(os.path.join(current_dir, 'core_framework'))
sys.path.append(os.path.join(current_dir, 'comparison'))
sys.path.append(os.path.join(current_dir, 'verification'))

def test_core_framework():
    """コアフレームワークのテスト"""
    print("🔬 Testing Core Framework...")
    try:
        from generative_gen_physics_framework import GENPhysicsConfig, GENComputationalFramework
        
        # 設定初期化
        config = GENPhysicsConfig()
        print(f"   ✅ GENPhysicsConfig initialized")
        
        # フレームワーク初期化
        gen_framework = GENComputationalFramework(config)
        print(f"   ✅ GENComputationalFramework initialized")
        
        # 基本計算テスト
        results = gen_framework.run_comprehensive_gen_analysis()
        print(f"   ✅ Comprehensive analysis completed")
        print(f"   📊 σ₈ accuracy: {results['performance']['sigma8_accuracy']:.1f}%")
        print(f"   📊 H₀ improvement: {results['performance']['h0_tension_improvement']:.1f}%")
        
        return True
        
    except Exception as e:
        print(f"   ❌ Core Framework test failed: {e}")
        traceback.print_exc()
        return False

def test_sigma8_optimization():
    """σ₈最適化システムのテスト"""
    print("\n🎯 Testing σ₈ Optimization...")
    try:
        from gen_sigma8_optimization import OptimizedGENSigma8Solver
        
        # ソルバー初期化
        solver = OptimizedGENSigma8Solver()
        print(f"   ✅ OptimizedGENSigma8Solver initialized")
        
        # σ₈計算
        results = solver.calculate_sigma8_gen_optimized()
        print(f"   ✅ σ₈ analysis completed")
        print(f"   📊 Calculated σ₈: {results['sigma_8_gen']:.4f}")
        print(f"   📊 Error percentage: {results['error_percent']:.2f}%")
        
        return True
        
    except Exception as e:
        print(f"   ❌ σ₈ Optimization test failed: {e}")
        traceback.print_exc()
        return False

def test_theory_comparison():
    """理論比較システムのテスト"""
    print("\n⚖️ Testing Theory Comparison...")
    try:
        from unified_theory_comparison_framework import UnifiedTheoryComparator
        
        # 比較システム初期化
        comparator = UnifiedTheoryComparator()
        print(f"   ✅ UnifiedTheoryComparator initialized")
        
        # 比較分析実行
        analysis = comparator.comparative_analysis()
        print(f"   ✅ Comparative analysis completed")
        
        overall = analysis['overall_comparison']
        print(f"   📊 IPF Score: {overall['ipf_total_score']:.1f}")
        print(f"   📊 GIT Score: {overall['git_total_score']:.1f}")
        print(f"   📊 Winner: {overall['overall_winner']}")
        
        # 最終勧告生成
        recommendation = comparator.generate_final_recommendation()
        print(f"   📋 Final Recommendation: {recommendation['final_decision']}")
        
        return True
        
    except Exception as e:
        print(f"   ❌ Theory Comparison test failed: {e}")
        traceback.print_exc()
        return False

def test_axioms_verification():
    """公理系検証のテスト"""
    print("\n🔍 Testing Axioms Verification...")
    try:
        from axioms_verification import GenerativePhysicsAxiomSystem
        
        # 公理系初期化
        axiom_system = GenerativePhysicsAxiomSystem()
        print(f"   ✅ GenerativePhysicsAxiomSystem initialized")
        print(f"   📊 Number of axioms: {len(axiom_system.axioms)}")
        
        return True
        
    except Exception as e:
        print(f"   ❌ Axioms Verification test failed: {e}")
        traceback.print_exc()
        return False

def performance_benchmark():
    """性能ベンチマーク"""
    print("\n⚡ Performance Benchmark...")
    
    # σ₈計算の性能測定
    try:
        from gen_sigma8_optimization import OptimizedGENSigma8Solver
        
        solver = OptimizedGENSigma8Solver()
        
        start_time = time.time()
        results = solver.calculate_sigma8_gen_optimized()
        end_time = time.time()
        
        calculation_time = end_time - start_time
        print(f"   ⏱️ σ₈ calculation time: {calculation_time:.2f} seconds")
        
        if calculation_time < 5.0:
            print(f"   ✅ Performance target achieved (<5s)")
        else:
            print(f"   ⚠️ Performance target not achieved (≥5s)")
            
        return calculation_time < 5.0
        
    except Exception as e:
        print(f"   ❌ Performance benchmark failed: {e}")
        return False

def main():
    """メインテスト実行"""
    print("🌟 GEN-Information Theory Test Suite")
    print("=" * 50)
    
    test_results = []
    
    # 各テストを実行
    test_results.append(("Core Framework", test_core_framework()))
    test_results.append(("σ₈ Optimization", test_sigma8_optimization()))
    test_results.append(("Theory Comparison", test_theory_comparison()))
    test_results.append(("Axioms Verification", test_axioms_verification()))
    test_results.append(("Performance Benchmark", performance_benchmark()))
    
    # 結果サマリー
    print("\n" + "=" * 50)
    print("📋 Test Results Summary")
    print("=" * 50)
    
    passed = 0
    total = len(test_results)
    
    for test_name, result in test_results:
        status = "✅ PASSED" if result else "❌ FAILED"
        print(f"{test_name:20} : {status}")
        if result:
            passed += 1
    
    print(f"\nOverall: {passed}/{total} tests passed ({passed/total*100:.1f}%)")
    
    if passed == total:
        print("🎉 All tests passed! GEN-Information Theory is ready for use.")
    else:
        print("⚠️ Some tests failed. Please check the implementation.")
    
    return passed == total

if __name__ == "__main__":
    success = main()
    sys.exit(0 if success else 1) 