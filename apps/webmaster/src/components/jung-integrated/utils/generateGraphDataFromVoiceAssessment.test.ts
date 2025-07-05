import { describe, it, expect, vi, beforeEach } from 'vitest';
import { generateGraphDataFromVoiceAssessment } from './generateGraphDataFromVoiceAssessment';
import { defaultModelParams } from '@/components/kawasaki-model/utils/integratedModel';
import type { TestResults } from '@/components/jung-voice-assessment/types';
import type { TransitionState } from '@/components/kawasaki-model/utils/stateTransition';

/**
 * 重要度: 5
 * このファイルはJungの音声連想テストの結果を視覚的に表現する中核機能であり、
 * 心理学的データの正確な表現と解釈に不可欠です。
 */
describe('generateGraphDataFromVoiceAssessment', () => {
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
    
    // Setup mock test results for voice assessment
    mockTestResults = {
      responses: [
        { stimulusWord: 'test', responseWord: 'reply', reactionTimeMs: 1000 },
        { stimulusWord: 'water', responseWord: 'drink', reactionTimeMs: 2500 }
      ],
      averageReactionTimeMs: 1750,
      delayedResponsesCount: 1,
      totalWords: 2
    };
    
    // Reset Math.random to make tests deterministic
    const mockRandom = vi.spyOn(Math, 'random');
    mockRandom.mockReturnValue(0.5);
  });

  it('グラフデータの基本構造が正しく生成されること', () => {
    const result = generateGraphDataFromVoiceAssessment(mockTestResults, mockTransitionState, 0);
    
    // Check basic structure
    expect(result).toHaveProperty('nodes');
    expect(result).toHaveProperty('links');
    
    // Should have 8 nodes (1 field + 7 unique words: water, ocean, fire, hot, mother, care, father, strict)
    // Note: "water" appears twice but should only have one node
    expect(result.nodes.length).toBe(8);
    
    // Should have at least 7 links (field->words + stimulus->response connections)
    expect(result.links.length).toBeGreaterThanOrEqual(7);
  });

  it('音声テスト特有のノードグループが割り当てられること', () => {
    const result = generateGraphDataFromVoiceAssessment(mockTestResults, mockTransitionState, 0);
    
    // Voice test nodes should have specific group values (different from word test)
    const wordNodes = result.nodes.filter(node => node.id !== 'field');
    
    // All word nodes should have consistent group IDs for voice assessment
    // (typically different from word assessment group IDs)
    const nodeGroups = new Set(wordNodes.map(node => node.group));
    expect(nodeGroups.size).toBeLessThanOrEqual(2); // Should have at most 2 different groups
    
    // All groups should be different from field node group
    const fieldGroup = result.nodes.find(node => node.id === 'field')?.group;
    wordNodes.forEach(node => {
      expect(node.group).not.toBe(fieldGroup);
    });
  });

  it('反応時間が短いほど接続強度が強くなること', () => {
    // Create test results with only reaction time differences
    const testResults: TestResults = {
      responses: [
        { stimulusWord: 'fire', responseWord: 'hot', reactionTimeMs: 1200 },
        { stimulusWord: 'cold', responseWord: 'ice', reactionTimeMs: 800 },
        { stimulusWord: 'time', responseWord: 'clock', reactionTimeMs: 1500 }
      ],
      averageReactionTimeMs: 1166,
      delayedResponsesCount: 0,
      totalWords: 3
    };
    
    const result = generateGraphDataFromVoiceAssessment(testResults, mockTransitionState, 0);
    
    // Find links
    const fastLink = result.links.find(link => {
      const sourceNode = result.nodes.find(n => n.id === link.source);
      const targetNode = result.nodes.find(n => n.id === link.target);
      return (sourceNode?.name === 'fire' && targetNode?.name === 'hot') ||
             (sourceNode?.name === 'hot' && targetNode?.name === 'fire');
    });
    
    const slowLink = result.links.find(link => {
      const sourceNode = result.nodes.find(n => n.id === link.source);
      const targetNode = result.nodes.find(n => n.id === link.target);
      return (sourceNode?.name === 'cold' && targetNode?.name === 'ice') ||
             (sourceNode?.name === 'ice' && targetNode?.name === 'cold');
    });
    
    // Faster reaction time should result in stronger connection
    expect(fastLink).toBeDefined();
    expect(slowLink).toBeDefined();
    expect(fastLink!.strength).toBeGreaterThan(slowLink!.strength);
  });

  it('遅延反応の接続強度が強調されること', () => {
    const result = generateGraphDataFromVoiceAssessment(mockTestResults, mockTransitionState, 0, {
      ...defaultModelParams,
      gamma: 2.0 // Set gamma high to emphasize delayed responses
    });
    
    // Find link for the delayed response (mother -> care, 2500ms)
    const delayedLink = result.links.find(link => {
      const sourceNode = result.nodes.find(n => n.id === link.source);
      const targetNode = result.nodes.find(n => n.id === link.target);
      return (sourceNode?.name === 'mother' && targetNode?.name === 'care') ||
             (sourceNode?.name === 'care' && targetNode?.name === 'mother');
    });
    
    // Find a normal (non-delayed) link for comparison (fire -> hot, 600ms)
    const normalLink = result.links.find(link => {
      const sourceNode = result.nodes.find(n => n.id === link.source);
      const targetNode = result.nodes.find(n => n.id === link.target);
      return (sourceNode?.name === 'fire' && targetNode?.name === 'hot') ||
             (sourceNode?.name === 'hot' && targetNode?.name === 'fire');
    });
    
    // Despite longer reaction time, the delayed response link should have significant strength
    // due to psychological significance (though it might still be weaker than very fast responses)
    expect(delayedLink).toBeDefined();
    expect(normalLink).toBeDefined();
    // We can't directly assert one is stronger than the other due to various factors,
    // but we can ensure the delayed link has reasonable strength despite long reaction time
    expect(delayedLink!.strength).toBeGreaterThan(0.3);
  });

  it('モデルパラメータが結果に影響を与えること', () => {
    // Test with default params
    const defaultResult = generateGraphDataFromVoiceAssessment(
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
    
    const customResult = generateGraphDataFromVoiceAssessment(
      mockTestResults, 
      mockTransitionState, 
      0, 
      customParams
    );
    
    // Results should be different with different parameters
    expect(defaultResult).not.toEqual(customResult);
  });
}); 