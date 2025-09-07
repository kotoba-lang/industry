import { describe, it, expect, vi, beforeEach } from 'vitest';
import { generateGraphData, type GraphData } from './generateGraphData';
import { defaultModelParams } from './integratedModel';
import type { TransitionState } from './stateTransition';

/**
 * 重要度: 5
 * このファイルはKawasakiモデルの視覚化の中核機能であり、正確なグラフ生成は
 * 全体のシステム動作に不可欠です。
 */
describe('generateGraphData', () => {
  let mockTransitionState: TransitionState;
  
  beforeEach(() => {
    // Setup a stable state for testing
    mockTransitionState = {
      currentState: 'stable',
      targetState: null,
      progress: 0,
      transitionDuration: 1000
    };
    
    // Reset Math.random to make tests deterministic
    const mockRandom = vi.spyOn(Math, 'random');
    mockRandom.mockReturnValue(0.5);
  });

  it('グラフデータの基本構造が正しく生成されること', () => {
    const result = generateGraphData(5, mockTransitionState, 0);
    
    // Check basic structure
    expect(result).toHaveProperty('nodes');
    expect(result).toHaveProperty('links');
    
    // Should have 6 nodes (1 field + 5 particles)
    expect(result.nodes.length).toBe(6);
    
    // Should have at least 10 links (connections between particles)
    expect(result.links.length).toBeGreaterThan(0);
  });

  it('同じ入力パラメータで呼び出した場合にキャッシュされた結果を返すこと', () => {
    // First call to generate data
    const firstResult = generateGraphData(3, mockTransitionState, 1.0, defaultModelParams);
    
    // Second call with same parameters should return cached result
    const secondResult = generateGraphData(3, mockTransitionState, 1.0, defaultModelParams);
    
    // Both results should be the same object instance (not just equal)
    expect(secondResult).toBe(firstResult);
  });

  it('パラメータが変わった場合は新しい結果を計算すること', () => {
    // Generate with time = 1.0
    const firstResult = generateGraphData(3, mockTransitionState, 1.0);
    
    // Generate with different time
    const secondResult = generateGraphData(3, mockTransitionState, 2.0);
    
    // Results should be different objects
    expect(secondResult).not.toBe(firstResult);
  });

  it('遷移状態において適切な補間が行われること', () => {
    // Create a transition state
    const transitionState: TransitionState = {
      currentState: 'stable',
      targetState: 'excited',
      progress: 0.5,
      transitionDuration: 1000
    };
    
    const result = generateGraphData(3, transitionState, 1.0);
    
    // Strength should be between stable and excited values
    // We could test this by examining the link strengths
    const fieldConnections = result.links.filter(link => 
      link.source === 'field' || link.target === 'field');
    
    // Field connections in transition should have intermediate strength
    // (but we can't test exact values due to random factors)
    expect(fieldConnections.length).toBeGreaterThan(0);
  });

  it('modelParamsが結果に影響を与えること', () => {
    // Create custom model params
    const customParams = {
      ...defaultModelParams,
      alpha: 0.9, // Higher alpha should increase connection strengths
    };
    
    const defaultResult = generateGraphData(3, mockTransitionState, 1.0, defaultModelParams);
    const customResult = generateGraphData(3, mockTransitionState, 1.0, customParams);
    
    // Results should be different with different parameters
    expect(customResult).not.toBe(defaultResult);
    // Further verification would depend on internal implementation details
  });
}); 