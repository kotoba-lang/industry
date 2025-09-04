import { describe, it, expect } from 'vitest';
import { mergeGraphData } from './mergeGraphData';
import { type GraphData } from '@/components/kawasaki-model/utils/generateGraphData';

/**
 * 重要度: 4
 * このファイルは単語テストと音声テストのグラフデータを統合する機能を担い、
 * システムの視覚化における重要な役割を果たします。
 */
describe('mergeGraphData', () => {
  // Test GraphData objects
  const graphData1: GraphData = {
    nodes: [
      { id: 'field_1', group: 1, name: 'Word Association Field', x: 0, y: 0, z: 0 },
      { id: 'word_water', group: 2, name: 'water', x: 10, y: 10, z: 10 },
      { id: 'word_fire', group: 2, name: 'fire', x: 20, y: 20, z: 20 }
    ],
    links: [
      { source: 'field_1', target: 'word_water', strength: 1, name: 'Field -> water' },
      { source: 'word_water', target: 'word_fire', strength: 0.5, name: 'water -> fire' }
    ]
  };
  
  const graphData2: GraphData = {
    nodes: [
      { id: 'field_2', group: 1, name: 'Voice Association Field', x: 0, y: 0, z: 0 },
      { id: 'voice_water', group: 3, name: 'water', x: -10, y: -10, z: -10 },
      { id: 'voice_ocean', group: 3, name: 'ocean', x: -20, y: -20, z: -20 }
    ],
    links: [
      { source: 'field_2', target: 'voice_water', strength: 1, name: 'Field -> water' },
      { source: 'voice_water', target: 'voice_ocean', strength: 0.8, name: 'water -> ocean' }
    ]
  };

  it('両方のグラフデータのノードとリンクが統合されること', () => {
    const result = mergeGraphData(graphData1, graphData2);
    
    // Should include all nodes from both sources
    expect(result.nodes.length).toBe(6); // 3 from each source
    
    // Should include all links from both sources
    expect(result.links.length).toBeGreaterThanOrEqual(4); // At least the 4 original links
    
    // Check if all original nodes exist in result
    const allOriginalNodeIds = [
      'field_1', 'word_water', 'word_fire', 
      'field_2', 'voice_water', 'voice_ocean'
    ];
    
    allOriginalNodeIds.forEach(id => {
      expect(result.nodes.some(node => node.id === id)).toBe(true);
    });
  });

  it('共通の単語（異なるソースに現れる同じ単語）に特別な接続が作成されること', () => {
    const result = mergeGraphData(graphData1, graphData2);
    
    // Should create a link between "word_water" and "voice_water" (same word in different sources)
    const connectionLink = result.links.find(link => 
      (link.source === 'word_water' && link.target === 'voice_water') ||
      (link.source === 'voice_water' && link.target === 'word_water')
    );
    
    expect(connectionLink).toBeDefined();
    expect(connectionLink?.strength).toBeGreaterThan(0);
    expect(connectionLink?.name).toContain('Cross-modal connection');
  });

  it('一方のグラフデータが空の場合でも正しく処理されること', () => {
    const emptyGraph: GraphData = { nodes: [], links: [] };
    
    // Test with first graph empty
    const result1 = mergeGraphData(emptyGraph, graphData2);
    expect(result1.nodes.length).toBe(graphData2.nodes.length);
    expect(result1.links.length).toBe(graphData2.links.length);
    
    // Test with second graph empty
    const result2 = mergeGraphData(graphData1, emptyGraph);
    expect(result2.nodes.length).toBe(graphData1.nodes.length);
    expect(result2.links.length).toBe(graphData1.links.length);
  });

  it('両方のグラフデータが空の場合でも空のグラフデータが返されること', () => {
    const emptyGraph: GraphData = { nodes: [], links: [] };
    const result = mergeGraphData(emptyGraph, emptyGraph);
    
    expect(result.nodes.length).toBe(0);
    expect(result.links.length).toBe(0);
  });

  it('フィールドノード間の接続が作成されること', () => {
    const result = mergeGraphData(graphData1, graphData2);
    
    // Should create a link between the two field nodes
    const fieldConnection = result.links.find(link => 
      (link.source === 'field_1' && link.target === 'field_2') ||
      (link.source === 'field_2' && link.target === 'field_1')
    );
    
    expect(fieldConnection).toBeDefined();
    expect(fieldConnection?.name).toContain('Field connection');
  });
}); 