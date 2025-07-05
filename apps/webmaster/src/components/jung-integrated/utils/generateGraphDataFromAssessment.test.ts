import { describe, it, expect, vi, beforeEach } from 'vitest';
import { generateGraphDataFromWordAssessment } from './generateGraphDataFromAssessment';
import { defaultModelParams } from '@/components/kawasaki-model/utils/integratedModel';
import type { TestResults, WordResponse } from '@/components/jung-word-assessment/types';
import type { TransitionState } from '@/components/kawasaki-model/utils/stateTransition';

/**
 * 重要度: 5
 * このファイルはJungの言語連想テストの結果を視覚的に表現する中核機能であり、
 * 心理学的データの正確な表現と解釈に不可欠です。
 */
describe('generateGraphDataFromAssessment', () => {
  let mockTransitionState: TransitionState;
  let mockTestResults: TestResults;
  
  beforeEach(() => {
    // Setup a stable state for testing
    mockTransitionState = {
      currentState: 'stable',
      targetState: null,
      progress: 0,
      transitionDuration: 1000
    };
    
    // Setup mock test results
    mockTestResults = {
      responses: [
        { stimulus: 'water', response: 'ocean', reactionTimeMs: 800, isDelayed: false },
        { stimulus: 'fire', response: 'hot', reactionTimeMs: 600, isDelayed: false },
        { stimulus: 'mother', response: 'care', reactionTimeMs: 2500, isDelayed: true },
        { stimulus: 'father', response: 'strict', reactionTimeMs: 900, isDelayed: false },
        { stimulus: 'water', response: 'river', reactionTimeMs: 750, isDelayed: false }
      ],
      averageReactionTimeMs: 1110,
      delayedResponseCount: 1,
      completedAt: new Date()
    };
    
    // Reset Math.random to make tests deterministic
    const mockRandom = vi.spyOn(Math, 'random');
    mockRandom.mockReturnValue(0.5);
  });

  it('グラフデータの基本構造が正しく生成されること', () => {
    const result = generateGraphDataFromWordAssessment(mockTestResults, mockTransitionState, 0);
    
    // Check basic structure
    expect(result).toHaveProperty('nodes');
    expect(result).toHaveProperty('links');
    
    // Should have 8 nodes (1 field + 7 unique words: water, ocean, fire, hot, mother, care, father, strict)
    // Note: "water" appears twice but should only have one node
    expect(result.nodes.length).toBe(8);
    
    // Should have at least 7 links (field->words + stimulus->response connections)
    expect(result.links.length).toBeGreaterThanOrEqual(7);
  });

  it('同じ単語は複数回現れても一度だけノード化されること', () => {
    const result = generateGraphDataFromWordAssessment(mockTestResults, mockTransitionState, 0);
    
    // Count "water" nodes - should only be one
    const waterNodes = result.nodes.filter(node => node.name === 'water');
    expect(waterNodes.length).toBe(1);
    
    // But "water" should appear in multiple links
    const waterLinks = result.links.filter(link => 
      result.nodes.find(n => n.id === link.source)?.name === 'water' || 
      result.nodes.find(n => n.id === link.target)?.name === 'water'
    );
    expect(waterLinks.length).toBeGreaterThan(1);
  });

  it('遅延反応（心理的複合体の兆候）の接続強度が強調されること', () => {
    const result = generateGraphDataFromWordAssessment(mockTestResults, mockTransitionState, 0, {
      ...defaultModelParams,
      gamma: 2.0 // Set gamma high to emphasize delayed responses
    });
    
    // Find link between "mother" and "care" (the delayed response)
    const motherCareLink = result.links.find(link => {
      const sourceNode = result.nodes.find(n => n.id === link.source);
      const targetNode = result.nodes.find(n => n.id === link.target);
      return (sourceNode?.name === 'mother' && targetNode?.name === 'care') ||
             (sourceNode?.name === 'care' && targetNode?.name === 'mother');
    });
    
    // Find a normal (non-delayed) link for comparison
    const normalLink = result.links.find(link => {
      const sourceNode = result.nodes.find(n => n.id === link.source);
      const targetNode = result.nodes.find(n => n.id === link.target);
      return (sourceNode?.name === 'fire' && targetNode?.name === 'hot') ||
             (sourceNode?.name === 'hot' && targetNode?.name === 'fire');
    });
    
    // The delayed response link should be stronger than normal links
    // due to the gamma multiplier despite longer reaction time
    expect(motherCareLink).toBeDefined();
    expect(normalLink).toBeDefined();
    expect(motherCareLink!.strength).toBeGreaterThan(normalLink!.strength);
  });

  it('反応時間が短いほど接続強度が強くなること', () => {
    // Create test results with only reaction time differences
    const testResults: TestResults = {
      responses: [
        { stimulus: 'test1', response: 'fast', reactionTimeMs: 500, isDelayed: false },
        { stimulus: 'test2', response: 'slow', reactionTimeMs: 1500, isDelayed: false }
      ],
      averageReactionTimeMs: 1000,
      delayedResponseCount: 0,
      completedAt: new Date()
    };
    
    const result = generateGraphDataFromWordAssessment(testResults, mockTransitionState, 0);
    
    // Find links
    const fastLink = result.links.find(link => {
      const sourceNode = result.nodes.find(n => n.id === link.source);
      const targetNode = result.nodes.find(n => n.id === link.target);
      return (sourceNode?.name === 'test1' && targetNode?.name === 'fast') ||
             (sourceNode?.name === 'fast' && targetNode?.name === 'test1');
    });
    
    const slowLink = result.links.find(link => {
      const sourceNode = result.nodes.find(n => n.id === link.source);
      const targetNode = result.nodes.find(n => n.id === link.target);
      return (sourceNode?.name === 'test2' && targetNode?.name === 'slow') ||
             (sourceNode?.name === 'slow' && targetNode?.name === 'test2');
    });
    
    // Faster reaction time should result in stronger connection
    expect(fastLink).toBeDefined();
    expect(slowLink).toBeDefined();
    expect(fastLink!.strength).toBeGreaterThan(slowLink!.strength);
  });

  it('モデルパラメータが結果に影響を与えること', () => {
    // Test with default params
    const defaultResult = generateGraphDataFromWordAssessment(
      mockTestResults, 
      mockTransitionState, 
      0, 
      defaultModelParams
    );
    
    // Test with modified params
    const customParams = {
      ...defaultModelParams,
      gamma: 3.0 // Higher gamma emphasizes psychological complexes
    };
    
    const customResult = generateGraphDataFromWordAssessment(
      mockTestResults, 
      mockTransitionState, 
      0, 
      customParams
    );
    
    // Results should be different with different parameters
    expect(defaultResult).not.toEqual(customResult);
  });
}); 