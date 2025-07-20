'use client';

import { useEffect, useRef, useState, useCallback } from 'react';
import cytoscape, { Core, NodeSingular, EdgeSingular } from 'cytoscape';
// @ts-ignore
import dagre from 'cytoscape-dagre';
// @ts-ignore
import coseBilkent from 'cytoscape-cose-bilkent';

// Cytoscapeの拡張を登録
if (typeof window !== 'undefined') {
  cytoscape.use(dagre);
  cytoscape.use(coseBilkent);
}

interface TheoryData {
  nodes: Array<{ data: any }>;
  edges: Array<{ data: any }>;
  mathConnections?: Array<{ data: any }>;
}

interface NodeData {
  id: string;
  label: string;
  type: string;
  category: string;
  level: number;
  description: string;
  details: string;
  mathConnection?: string[];
}

interface EdgeData {
  source: string;
  target: string;
  type: string;
  label: string;
  category?: string;
}

export default function CytoscapeVisualization() {
  const containerRef = useRef<HTMLDivElement>(null);
  const cyRef = useRef<Core | null>(null);
  const [editMode, setEditMode] = useState(false);
  const [selectedElement, setSelectedElement] = useState<any>(null);
  const [currentLayout, setCurrentLayout] = useState('cose');
  const [showInfoPanel, setShowInfoPanel] = useState(false);
  const [infoData, setInfoData] = useState<any>(null);
  const [showNodeModal, setShowNodeModal] = useState(false);
  const [showEdgeModal, setShowEdgeModal] = useState(false);
  const [nodeFormData, setNodeFormData] = useState<Partial<NodeData>>({});
  const [edgeFormData, setEdgeFormData] = useState<Partial<EdgeData>>({});
  const [availableNodes, setAvailableNodes] = useState<Array<{id: string, label: string}>>([]);
  const [viewMode, setViewMode] = useState<'math' | 'tech' | 'integrated'>('integrated');
  const [mathData, setMathData] = useState<TheoryData | null>(null);
  const [techData, setTechData] = useState<TheoryData | null>(null);

  // JSONデータの読み込み
  const loadMathData = useCallback(async (): Promise<TheoryData> => {
    try {
      console.log('Loading math theory data...');
      const response = await fetch('/data/complete-theory-data.json');
      
      if (!response.ok) {
        throw new Error(`HTTP error! status: ${response.status}`);
      }
      
      const data = await response.json();
      console.log('Math theory data loaded successfully');
      console.log('Math Nodes:', data.nodes.length);
      console.log('Math Edges:', data.edges.length);
      
      return data;
    } catch (error) {
      console.error('Error loading math theory data:', error);
      throw error;
    }
  }, []);

  const loadTechData = useCallback(async (): Promise<TheoryData> => {
    try {
      console.log('Loading tech ecosystem data...');
      const response = await fetch('/data/tech-ecosystem-data.json');
      
      if (!response.ok) {
        throw new Error(`HTTP error! status: ${response.status}`);
      }
      
      const data = await response.json();
      console.log('Tech ecosystem data loaded successfully');
      console.log('Tech Nodes:', data.nodes.length);
      console.log('Tech Edges:', data.edges.length);
      console.log('Math Connections:', data.mathConnections?.length || 0);
      
      return data;
    } catch (error) {
      console.error('Error loading tech ecosystem data:', error);
      throw error;
    }
  }, []);

  const getIntegratedData = useCallback((): TheoryData => {
    if (!mathData || !techData) {
      return { nodes: [], edges: [] };
    }

    const allNodes = [...mathData.nodes, ...techData.nodes];
    const allEdges = [...mathData.edges, ...techData.edges];
    
    // 数学理論とIT技術の連携エッジを追加
    if (techData.mathConnections) {
      allEdges.push(...techData.mathConnections);
    }

    return {
      nodes: allNodes,
      edges: allEdges,
      mathConnections: techData.mathConnections
    };
  }, [mathData, techData]);

  const getCurrentData = useCallback((): TheoryData => {
    switch (viewMode) {
      case 'math':
        return mathData || { nodes: [], edges: [] };
      case 'tech':
        return techData || { nodes: [], edges: [] };
      case 'integrated':
        return getIntegratedData();
      default:
        return { nodes: [], edges: [] };
    }
  }, [viewMode, mathData, techData, getIntegratedData]);

  // イベントハンドラーの設定
  const setupEventHandlers = useCallback((cy: Core) => {
    // ノードクリック
    cy.on('tap', 'node', function(evt) {
      const node = evt.target;
      const data = node.data();
      setSelectedElement(node);
      
      setInfoData({
        title: data.label.replace(/\n/g, ' '),
        description: data.description || 'このノードの詳細情報が利用可能です。',
        details: data
      });
      setShowInfoPanel(true);
      
      if (!editMode) {
        highlightConnectedNodes(cy, node);
      }
    });

    // エッジクリック
    cy.on('tap', 'edge', function(evt) {
      const edge = evt.target;
      const data = edge.data();
      setSelectedElement(edge);
      
      setInfoData({
        title: `${data.label || 'エッジ'} (${data.source} → ${data.target})`,
        description: `タイプ: ${data.type}`,
        details: data
      });
      setShowInfoPanel(true);
    });

    // 背景クリック
    cy.on('tap', function(evt) {
      if (evt.target === cy) {
        setSelectedElement(null);
        setShowInfoPanel(false);
        cy.elements().removeClass('highlighted dimmed');
      }
    });

    // ダブルクリックで編集
    cy.on('dblclick', 'node', function(evt) {
      if (editMode) {
        const data = evt.target.data();
        setNodeFormData({
          id: data.id,
          label: data.label || '',
          type: data.type || 'foundation',
          category: data.category || '基礎論',
          level: data.level || 0,
          description: data.description || '',
          details: data.details || ''
        });
        setShowNodeModal(true);
      }
    });

    cy.on('dblclick', 'edge', function(evt) {
      if (editMode) {
        const data = evt.target.data();
        setEdgeFormData({
          source: data.source,
          target: data.target,
          type: data.type || 'nutrient_flow',
          label: data.label || ''
        });
        setShowEdgeModal(true);
      }
    });

    // 右クリックで削除
    cy.on('cxttap', 'node, edge', function(evt) {
      if (editMode) {
        if (confirm('この要素を削除しますか？')) {
          evt.target.remove();
          updateAvailableNodes(cy);
        }
      }
    });

    // ホバーエフェクト
    cy.on('mouseover', 'node', function(evt) {
      const node = evt.target;
      node.style({
        'border-width': 3,
        'border-opacity': 0.8
      });
    });

    cy.on('mouseout', 'node', function(evt) {
      const node = evt.target;
      if (!node.selected()) {
        node.style({
          'border-width': 1.5,
          'border-opacity': 0.4
        });
      }
    });
  }, [editMode]);

  // 関連ノードのハイライト
  const highlightConnectedNodes = useCallback((cy: Core, selectedNode: NodeSingular) => {
    cy.elements().addClass('dimmed');
    const connectedNodes = selectedNode.neighborhood().add(selectedNode);
    connectedNodes.removeClass('dimmed').addClass('highlighted');
  }, []);

  // 利用可能なノードの更新
  const updateAvailableNodes = useCallback((cy: Core) => {
    const nodes = cy.nodes().map(node => ({
      id: node.data('id'),
      label: node.data('label') || node.data('id')
    }));
    setAvailableNodes(nodes);
  }, []);

  // Cytoscape初期化
  useEffect(() => {
    const init = async () => {
      if (!containerRef.current) return;

      try {
        // 両方のデータを並行して読み込み
        const [mathTheoryData, techTheoryData] = await Promise.all([
          loadMathData(),
          loadTechData()
        ]);
        
        setMathData(mathTheoryData);
        setTechData(techTheoryData);
        
        const currentData = viewMode === 'math' ? mathTheoryData : 
                           viewMode === 'tech' ? techTheoryData :
                           { 
                             nodes: [...mathTheoryData.nodes, ...techTheoryData.nodes],
                             edges: [...mathTheoryData.edges, ...techTheoryData.edges, ...(techTheoryData.mathConnections || [])]
                           };
        
        const cy = cytoscape({
          container: containerRef.current,
          elements: [...currentData.nodes, ...currentData.edges],
          
          style: [
            // 基本ノードスタイル
            {
              selector: 'node',
              style: {
                'label': 'data(label)',
                'text-valign': 'center',
                'text-halign': 'center',
                'color': '#ffffff',
                'text-shadow': '0 0 4px rgba(0, 0, 0, 0.8)',
                'font-size': '9px',
                'font-weight': 'bold',
                'border-width': 1.5,
                'border-color': '#ffffff',
                'border-opacity': 0.4,
                'text-wrap': 'wrap',
                'text-max-width': '80px'
              }
            },
            
            // 基礎論（根系）
            {
              selector: 'node[type="foundation_root"]',
              style: {
                'background-color': '#654321',
                'shape': 'round-hexagon',
                'width': 100,
                'height': 100,
                'border-color': '#8B7D6B',
                'box-shadow': '0 0 25px rgba(101, 67, 33, 0.8)',
                'font-size': '11px'
              }
            },
            {
              selector: 'node[type="foundation"]',
              style: {
                'background-color': '#8B4513',
                'shape': 'round-hexagon',
                'width': 75,
                'height': 75,
                'border-color': '#CD853F',
                'box-shadow': '0 0 18px rgba(139, 69, 19, 0.7)',
                'font-size': '10px'
              }
            },
            
            // 純粋数学（幹・枝）
            {
              selector: 'node[type="trunk"]',
              style: {
                'background-color': '#2E7D32',
                'shape': 'round-rectangle',
                'width': 95,
                'height': 75,
                'border-color': '#66BB6A',
                'box-shadow': '0 0 20px rgba(46, 125, 50, 0.8)',
                'font-size': '11px'
              }
            },
            {
              selector: 'node[type^="branch_"]',
              style: {
                'background-color': '#388E3C',
                'shape': 'round-rectangle',
                'width': 85,
                'height': 65,
                'border-color': '#81C784',
                'box-shadow': '0 0 15px rgba(56, 142, 60, 0.7)',
                'font-size': '10px'
              }
            },
            {
              selector: 'node[type="sub_branch"]',
              style: {
                'background-color': '#4CAF50',
                'shape': 'round-rectangle',
                'width': 70,
                'height': 55,
                'border-color': '#A5D6A7',
                'box-shadow': '0 0 12px rgba(76, 175, 80, 0.6)',
                'font-size': '9px'
              }
            },
            
            // 応用数学（葉・花）
            {
              selector: 'node[type="leaf_cluster"]',
              style: {
                'background-color': '#F57F17',
                'shape': 'round-diamond',
                'width': 90,
                'height': 90,
                'border-color': '#FFF176',
                'color': '#1A1A1A',
                'text-shadow': '0 0 3px rgba(255, 255, 255, 0.8)',
                'box-shadow': '0 0 20px rgba(245, 127, 23, 0.8)',
                'font-size': '11px'
              }
            },
            {
              selector: 'node[type="leaf"]',
              style: {
                'background-color': '#FBC02D',
                'shape': 'ellipse',
                'width': 75,
                'height': 60,
                'border-color': '#FFEB3B',
                'color': '#1A1A1A',
                'text-shadow': '0 0 2px rgba(255, 255, 255, 0.9)',
                'box-shadow': '0 0 15px rgba(251, 192, 45, 0.7)',
                'font-size': '9px'
              }
            },
            
            // 学際領域（果実・種）
            {
              selector: 'node[type="fruit_cluster"]',
              style: {
                'background-color': '#D32F2F',
                'shape': 'round-octagon',
                'width': 95,
                'height': 95,
                'border-color': '#EF5350',
                'box-shadow': '0 0 22px rgba(211, 47, 47, 0.8)',
                'font-size': '11px'
              }
            },
            {
              selector: 'node[type="fruit"]',
              style: {
                'background-color': '#F44336',
                'shape': 'round-octagon',
                'width': 80,
                'height': 80,
                'border-color': '#FFAB91',
                'box-shadow': '0 0 18px rgba(244, 67, 54, 0.7)',
                'font-size': '10px'
              }
            },
            {
              selector: 'node[type="seed"]',
              style: {
                'background-color': '#FF7043',
                'shape': 'round-triangle',
                'width': 65,
                'height': 65,
                'border-color': '#FFCCBC',
                'box-shadow': '0 0 15px rgba(255, 112, 67, 0.6)',
                'font-size': '9px'
              }
            },
            
            // IT技術系ノードスタイル
            {
              selector: 'node[type="soil"]',
              style: {
                'background-color': '#3E2723',
                'shape': 'round-octagon',
                'width': 110,
                'height': 110,
                'border-color': '#6D4C41',
                'box-shadow': '0 0 30px rgba(62, 39, 35, 0.9)',
                'font-size': '12px'
              }
            },
            {
              selector: 'node[type="mineral"]',
              style: {
                'background-color': '#424242',
                'shape': 'round-hexagon',
                'width': 85,
                'height': 85,
                'border-color': '#757575',
                'box-shadow': '0 0 20px rgba(66, 66, 66, 0.8)',
                'font-size': '10px'
              }
            },
            {
              selector: 'node[type="microbe"]',
              style: {
                'background-color': '#1B5E20',
                'shape': 'ellipse',
                'width': 70,
                'height': 50,
                'border-color': '#4CAF50',
                'box-shadow': '0 0 15px rgba(27, 94, 32, 0.7)',
                'font-size': '9px'
              }
            },
            {
              selector: 'node[type="mycelium"]',
              style: {
                'background-color': '#263238',
                'shape': 'round-rectangle',
                'width': 75,
                'height': 55,
                'border-color': '#607D8B',
                'box-shadow': '0 0 18px rgba(38, 50, 56, 0.8)',
                'font-size': '9px'
              }
            },
            {
              selector: 'node[type="decomposer"]',
              style: {
                'background-color': '#4A148C',
                'shape': 'triangle',
                'width': 65,
                'height': 65,
                'border-color': '#7B1FA2',
                'box-shadow': '0 0 16px rgba(74, 20, 140, 0.7)',
                'font-size': '9px'
              }
            },
            {
              selector: 'node[type="fungi"]',
              style: {
                'background-color': '#BF360C',
                'shape': 'round-diamond',
                'width': 80,
                'height': 80,
                'border-color': '#FF5722',
                'box-shadow': '0 0 20px rgba(191, 54, 12, 0.8)',
                'font-size': '10px'
              }
            },
            {
              selector: 'node[type="herbaceous"]',
              style: {
                'background-color': '#33691E',
                'shape': 'round-rectangle',
                'width': 85,
                'height': 65,
                'border-color': '#8BC34A',
                'box-shadow': '0 0 18px rgba(51, 105, 30, 0.7)',
                'font-size': '10px'
              }
            },
            {
              selector: 'node[type="woody"]',
              style: {
                'background-color': '#1A237E',
                'shape': 'round-rectangle',
                'width': 90,
                'height': 70,
                'border-color': '#3F51B5',
                'box-shadow': '0 0 20px rgba(26, 35, 126, 0.8)',
                'font-size': '10px'
              }
            },
            {
              selector: 'node[type="conifer"]',
              style: {
                'background-color': '#0D47A1',
                'shape': 'round-triangle',
                'width': 75,
                'height': 85,
                'border-color': '#2196F3',
                'box-shadow': '0 0 18px rgba(13, 71, 161, 0.8)',
                'font-size': '10px'
              }
            },
            {
              selector: 'node[type="epiphyte"]',
              style: {
                'background-color': '#006064',
                'shape': 'star',
                'width': 80,
                'height': 80,
                'border-color': '#00BCD4',
                'box-shadow': '0 0 20px rgba(0, 96, 100, 0.8)',
                'font-size': '9px'
              }
            },
            {
              selector: 'node[type="support_structure"]',
              style: {
                'background-color': '#4E342E',
                'shape': 'round-rectangle',
                'width': 95,
                'height': 75,
                'border-color': '#8D6E63',
                'box-shadow': '0 0 22px rgba(78, 52, 46, 0.8)',
                'font-size': '10px'
              }
            },
            {
              selector: 'node[type="pollinator"]',
              style: {
                'background-color': '#E65100',
                'shape': 'pentagon',
                'width': 85,
                'height': 85,
                'border-color': '#FF9800',
                'box-shadow': '0 0 25px rgba(230, 81, 0, 0.9)',
                'font-size': '10px'
              }
            },
            {
              selector: 'node[type="nutrient_cycler"]',
              style: {
                'background-color': '#1B5E20',
                'shape': 'round-octagon',
                'width': 80,
                'height': 80,
                'border-color': '#4CAF50',
                'box-shadow': '0 0 20px rgba(27, 94, 32, 0.8)',
                'font-size': '10px'
              }
            },
            {
              selector: 'node[type="storage_organ"]',
              style: {
                'background-color': '#37474F',
                'shape': 'barrel',
                'width': 90,
                'height': 70,
                'border-color': '#78909C',
                'box-shadow': '0 0 22px rgba(55, 71, 79, 0.8)',
                'font-size': '10px'
              }
            },
            {
              selector: 'node[type="neural_network"]',
              style: {
                'background-color': '#880E4F',
                'shape': 'round-hexagon',
                'width': 85,
                'height': 85,
                'border-color': '#E91E63',
                'box-shadow': '0 0 25px rgba(136, 14, 79, 0.9)',
                'font-size': '10px'
              }
            },
            {
              selector: 'node[type="ecosystem_expansion"]',
              style: {
                'background-color': '#01579B',
                'shape': 'round-octagon',
                'width': 100,
                'height': 100,
                'border-color': '#03A9F4',
                'box-shadow': '0 0 30px rgba(1, 87, 155, 0.9)',
                'font-size': '11px'
              }
            },
            {
              selector: 'node[type="seed_dispersal"]',
              style: {
                'background-color': '#004D40',
                'shape': 'round-diamond',
                'width': 75,
                'height': 75,
                'border-color': '#009688',
                'box-shadow': '0 0 20px rgba(0, 77, 64, 0.8)',
                'font-size': '9px'
              }
            },
            {
              selector: 'node[type="ecosystem_management"]',
              style: {
                'background-color': '#3E2723',
                'shape': 'round-pentagon',
                'width': 85,
                'height': 85,
                'border-color': '#795548',
                'box-shadow': '0 0 22px rgba(62, 39, 35, 0.8)',
                'font-size': '10px'
              }
            },
            {
              selector: 'node[type="immune_system"]',
              style: {
                'background-color': '#B71C1C',
                'shape': 'round-hexagon',
                'width': 80,
                'height': 80,
                'border-color': '#F44336',
                'box-shadow': '0 0 25px rgba(183, 28, 28, 0.9)',
                'font-size': '10px'
              }
            },
            {
              selector: 'node[type="sensory_system"]',
              style: {
                'background-color': '#FF6F00',
                'shape': 'ellipse',
                'width': 85,
                'height': 60,
                'border-color': '#FFC107',
                'color': '#1A1A1A',
                'text-shadow': '0 0 3px rgba(255, 255, 255, 0.8)',
                'box-shadow': '0 0 20px rgba(255, 111, 0, 0.8)',
                'font-size': '9px'
              }
            },
            {
              selector: 'node[type="evolution_seed"]',
              style: {
                'background-color': '#6A1B9A',
                'shape': 'round-triangle',
                'width': 70,
                'height': 70,
                'border-color': '#9C27B0',
                'box-shadow': '0 0 20px rgba(106, 27, 154, 0.8)',
                'font-size': '9px'
              }
            },
            {
              selector: 'node[type="new_ecosystem"]',
              style: {
                'background-color': '#1A237E',
                'shape': 'round-octagon',
                'width': 95,
                'height': 95,
                'border-color': '#3F51B5',
                'box-shadow': '0 0 30px rgba(26, 35, 126, 1.0)',
                'font-size': '11px'
              }
            },

            // 新しい技術系ノードスタイル
            {
              selector: 'node[type="genetic_library"]',
              style: {
                'background-color': '#2C5530',
                'shape': 'round-rectangle',
                'width': 95,
                'height': 70,
                'border-color': '#66BB6A',
                'box-shadow': '0 0 25px rgba(44, 85, 48, 0.9)',
                'font-size': '10px'
              }
            },
            {
              selector: 'node[type="membrane_receptor"]',
              style: {
                'background-color': '#4A148C',
                'shape': 'round-diamond',
                'width': 80,
                'height': 60,
                'border-color': '#9C27B0',
                'box-shadow': '0 0 20px rgba(74, 20, 140, 0.8)',
                'font-size': '9px'
              }
            },
            {
              selector: 'node[type="neural_signal"]',
              style: {
                'background-color': '#FF6F00',
                'shape': 'round-triangle',
                'width': 70,
                'height': 70,
                'border-color': '#FFB74D',
                'box-shadow': '0 0 18px rgba(255, 111, 0, 0.8)',
                'font-size': '9px'
              }
            },
            {
              selector: 'node[type="knowledge_network"]',
              style: {
                'background-color': '#6A1B9A',
                'shape': 'star',
                'width': 90,
                'height': 90,
                'border-color': '#E1BEE7',
                'box-shadow': '0 0 25px rgba(106, 27, 154, 0.9)',
                'font-size': '10px'
              }
            },
            {
              selector: 'node[type="symbiotic_network"]',
              style: {
                'background-color': '#00695C',
                'shape': 'round-hexagon',
                'width': 85,
                'height': 85,
                'border-color': '#26A69A',
                'box-shadow': '0 0 22px rgba(0, 105, 92, 0.8)',
                'font-size': '10px'
              }
            },
            {
              selector: 'node[type="metabolic_regulator"]',
              style: {
                'background-color': '#E65100',
                'shape': 'round-octagon',
                'width': 90,
                'height': 90,
                'border-color': '#FF8A65',
                'box-shadow': '0 0 25px rgba(230, 81, 0, 0.9)',
                'font-size': '10px'
              }
            },
            {
              selector: 'node[type="memory_formation"]',
              style: {
                'background-color': '#3E2723',
                'shape': 'round-diamond',
                'width': 85,
                'height': 70,
                'border-color': '#8D6E63',
                'box-shadow': '0 0 20px rgba(62, 39, 35, 0.8)',
                'font-size': '9px'
              }
            },
            
            // エッジスタイル
            {
              selector: 'edge',
              style: {
                'width': 1.5,
                'line-color': '#607D8B',
                'target-arrow-color': '#607D8B',
                'target-arrow-shape': 'triangle',
                'curve-style': 'bezier',
                'opacity': 0.7,
                'arrow-scale': 1.0
              }
            },
            
            // 数学理論エッジスタイル
            {
              selector: 'edge[type="nutrient_flow"], edge[type="foundation_to_pure"]',
              style: {
                'line-color': '#8B4513',
                'target-arrow-color': '#8B4513',
                'width': 3,
                'line-style': 'solid',
                'opacity': 0.9
              }
            },
            {
              selector: 'edge[type="growth_flow"], edge[type="sub_branch_growth"]',
              style: {
                'line-color': '#388E3C',
                'target-arrow-color': '#388E3C',
                'width': 2.5,
                'line-style': 'solid',
                'opacity': 0.8
              }
            },
            {
              selector: 'edge[type="cross_pollination"]',
              style: {
                'line-color': '#4CAF50',
                'target-arrow-color': '#4CAF50',
                'width': 2,
                'line-style': 'dashed',
                'curve-style': 'unbundled-bezier',
                'opacity': 0.6
              }
            },
            {
              selector: 'edge[type="application_flow"], edge[type="specialization"]',
              style: {
                'line-color': '#FF9800',
                'target-arrow-color': '#FF9800',
                'width': 2.5,
                'line-style': 'solid',
                'opacity': 0.8
              }
            },

            // IT技術系エッジスタイル
            {
              selector: 'edge[type="foundation_support"], edge[type="nutrient_supply"]',
              style: {
                'line-color': '#6D4C41',
                'target-arrow-color': '#6D4C41',
                'width': 3,
                'line-style': 'solid',
                'opacity': 0.9
              }
            },
            {
              selector: 'edge[type="evolution_flow"], edge[type="platform_support"]',
              style: {
                'line-color': '#FF9800',
                'target-arrow-color': '#FF9800',
                'width': 2.5,
                'line-style': 'solid',
                'opacity': 0.8
              }
            },
            {
              selector: 'edge[type="framework_growth"], edge[type="data_flow"]',
              style: {
                'line-color': '#4CAF50',
                'target-arrow-color': '#4CAF50',
                'width': 2.5,
                'line-style': 'solid',
                'opacity': 0.8
              }
            },
            {
              selector: 'edge[type="scaling_need"], edge[type="deployment_evolution"]',
              style: {
                'line-color': '#2196F3',
                'target-arrow-color': '#2196F3',
                'width': 2.5,
                'line-style': 'solid',
                'opacity': 0.8
              }
            },
            {
              selector: 'edge[type="collaboration"], edge[type="data_synergy"]',
              style: {
                'line-color': '#9C27B0',
                'target-arrow-color': '#9C27B0',
                'width': 2,
                'line-style': 'dashed',
                'opacity': 0.7
              }
            },

            // 新しい技術系エッジスタイル
            {
              selector: 'edge[type="data_source"], edge[type="event_storage"]',
              style: {
                'line-color': '#2E7D32',
                'target-arrow-color': '#2E7D32',
                'width': 2.5,
                'line-style': 'solid',
                'opacity': 0.8
              }
            },
            {
              selector: 'edge[type="api_layer"], edge[type="rpc_layer"]',
              style: {
                'line-color': '#7B1FA2',
                'target-arrow-color': '#7B1FA2',
                'width': 2,
                'line-style': 'solid',
                'opacity': 0.8
              }
            },
            {
              selector: 'edge[type="integration_method"]',
              style: {
                'line-color': '#00ACC1',
                'target-arrow-color': '#00ACC1',
                'width': 2.5,
                'line-style': 'solid',
                'opacity': 0.8
              }
            },
            {
              selector: 'edge[type="workflow_trigger"], edge[type="process_recording"]',
              style: {
                'line-color': '#FF9800',
                'target-arrow-color': '#FF9800',
                'width': 2,
                'line-style': 'solid',
                'opacity': 0.8
              }
            },
            {
              selector: 'edge[type="ai_communication"], edge[type="protocol_implementation"]',
              style: {
                'line-color': '#9C27B0',
                'target-arrow-color': '#9C27B0',
                'width': 3,
                'line-style': 'dotted',
                'opacity': 0.9
              }
            },
            {
              selector: 'edge[type="automation_orchestration"], edge[type="service_exposure"]',
              style: {
                'line-color': '#3F51B5',
                'target-arrow-color': '#3F51B5',
                'width': 2,
                'line-style': 'dashed',
                'opacity': 0.7
              }
            },
            {
              selector: 'edge[type="audit_trail"]',
              style: {
                'line-color': '#795548',
                'target-arrow-color': '#795548',
                'width': 2,
                'line-style': 'dashed',
                'curve-style': 'unbundled-bezier',
                'opacity': 0.6
              }
            },

            // 数学-IT連携エッジスタイル
            {
              selector: 'edge[category="mathematical_bridge"]',
              style: {
                'line-color': '#E1BEE7',
                'target-arrow-color': '#E1BEE7',
                'width': 3,
                'line-style': 'dotted',
                'curve-style': 'unbundled-bezier',
                'opacity': 0.8,
                'control-point-distances': [50, -50],
                'control-point-weights': [0.25, 0.75]
              }
            },
            
            // 選択時のスタイル
            {
              selector: 'node:selected',
              style: {
                'border-width': 4,
                'border-color': '#00BCD4',
                'box-shadow': '0 0 30px rgba(0, 188, 212, 1)'
              }
            },
            {
              selector: 'edge:selected',
              style: {
                'width': 4,
                'line-color': '#00BCD4',
                'target-arrow-color': '#00BCD4',
                'source-arrow-color': '#00BCD4'
              }
            },
            
            // ハイライト状態
            {
              selector: '.highlighted',
              style: {
                'opacity': 1,
                'z-index': 10
              }
            },
            {
              selector: '.dimmed',
              style: {
                'opacity': 0.2,
                'z-index': 1
              }
            }
          ],
          
          layout: {
            name: 'cose',
            animate: true,
            animationDuration: 2000,
            nodeRepulsion: 800000,
            nodeOverlap: 40,
            idealEdgeLength: 120,
            edgeElasticity: 200,
            nestingFactor: 10,
            gravity: 100,
            numIter: 1500,
            initialTemp: 300,
            coolingFactor: 0.95,
            minTemp: 1.0,
            fit: true,
            padding: 60
          }
        });

        setupEventHandlers(cy);
        updateAvailableNodes(cy);
        cyRef.current = cy;
        
        console.log('Integrated Math-Tech ecosystem initialized!');
        console.log('Total Nodes:', cy.nodes().length);
        console.log('Total Edges:', cy.edges().length);
        console.log('Current View Mode:', viewMode);
        
      } catch (error) {
        console.error('Error initializing Cytoscape:', error);
      }
    };

    init();
    
    return () => {
      if (cyRef.current) {
        cyRef.current.destroy();
      }
    };
  }, [loadMathData, loadTechData, viewMode, setupEventHandlers, updateAvailableNodes]);

  // ビューモード変更時のデータ更新
  useEffect(() => {
    if (cyRef.current && mathData && techData) {
      const currentData = getCurrentData();
      cyRef.current.elements().remove();
      cyRef.current.add([...currentData.nodes, ...currentData.edges]);
      cyRef.current.layout({ name: currentLayout }).run();
      updateAvailableNodes(cyRef.current);
    }
  }, [viewMode, getCurrentData, currentLayout, updateAvailableNodes]);

  // コントロール関数
  const resetView = useCallback(() => {
    if (cyRef.current) {
      cyRef.current.fit();
      cyRef.current.center();
      cyRef.current.elements().removeClass('highlighted dimmed');
      setShowInfoPanel(false);
      setSelectedElement(null);
    }
  }, []);

  const fitToView = useCallback(() => {
    if (cyRef.current) {
      cyRef.current.fit(cyRef.current.elements(), 60);
    }
  }, []);

  const toggleLayout = useCallback(() => {
    if (!cyRef.current) return;
    
    const layouts = ['cose', 'circle', 'breadthfirst', 'grid', 'concentric'];
    const currentIndex = layouts.indexOf(currentLayout);
    const nextIndex = (currentIndex + 1) % layouts.length;
    const nextLayout = layouts[nextIndex];
    setCurrentLayout(nextLayout);
    
    let layoutOptions: any = { name: nextLayout, animate: true, animationDuration: 1500 };
    
    switch(nextLayout) {
      case 'cose':
        layoutOptions = {
          ...layoutOptions,
          nodeRepulsion: 800000,
          idealEdgeLength: 120,
          edgeElasticity: 200
        };
        break;
      case 'circle':
        layoutOptions.radius = 300;
        break;
      case 'breadthfirst':
        layoutOptions.directed = true;
        layoutOptions.roots = cyRef.current.nodes('[type="foundation_root"]');
        layoutOptions.spacingFactor = 2;
        break;
      case 'grid':
        layoutOptions.rows = 6;
        layoutOptions.cols = 6;
        break;
      case 'concentric':
        layoutOptions.concentric = function(node: any) {
          return 10 - (node.data('level') || 0);
        };
        layoutOptions.levelWidth = function() { return 2; };
        break;
    }
    
    cyRef.current.layout(layoutOptions).run();
  }, [currentLayout]);

  const addNode = useCallback(() => {
    setNodeFormData({
      id: `new_node_${Date.now()}`,
      label: '',
      type: 'foundation',
      category: '基礎論',
      level: 0,
      description: '',
      details: ''
    });
    setShowNodeModal(true);
  }, []);

  const addEdge = useCallback(() => {
    setEdgeFormData({
      source: '',
      target: '',
      type: 'nutrient_flow',
      label: ''
    });
    setShowEdgeModal(true);
  }, []);

  const saveNode = useCallback(() => {
    if (!cyRef.current || !nodeFormData.id) return;
    
    const existingNode = cyRef.current.getElementById(nodeFormData.id);
    
    if (existingNode.length > 0) {
      existingNode.data(nodeFormData);
    } else {
      cyRef.current.add({ data: nodeFormData });
    }
    
    setShowNodeModal(false);
    updateAvailableNodes(cyRef.current);
    cyRef.current.layout({ name: currentLayout }).run();
  }, [nodeFormData, currentLayout, updateAvailableNodes]);

  const saveEdge = useCallback(() => {
    if (!cyRef.current || !edgeFormData.source || !edgeFormData.target) return;
    
    cyRef.current.add({ data: edgeFormData });
    setShowEdgeModal(false);
    cyRef.current.layout({ name: currentLayout }).run();
  }, [edgeFormData, currentLayout]);

  const deleteSelected = useCallback(() => {
    if (selectedElement && cyRef.current) {
      if (confirm('選択された要素を削除しますか？')) {
        selectedElement.remove();
        setSelectedElement(null);
        updateAvailableNodes(cyRef.current);
        setShowInfoPanel(false);
      }
    } else {
      alert('削除する要素を選択してください。');
    }
  }, [selectedElement, updateAvailableNodes]);

  const exportData = useCallback(() => {
    if (!cyRef.current) return;
    
    const exportData = {
      nodes: cyRef.current.nodes().map(node => ({ data: node.data() })),
      edges: cyRef.current.edges().map(edge => ({ data: edge.data() }))
    };
    
    const blob = new Blob([JSON.stringify(exportData, null, 2)], {
      type: 'application/json'
    });
    
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `math-tech-ecosystem-${Date.now()}.json`;
    a.click();
    URL.revokeObjectURL(url);
  }, []);

  const importData = useCallback((event: React.ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0];
    if (!file || !cyRef.current) return;
    
    const reader = new FileReader();
    reader.onload = function(e) {
      try {
        const data = JSON.parse(e.target?.result as string);
        
        if (cyRef.current) {
          cyRef.current.elements().remove();
          cyRef.current.add([...data.nodes, ...data.edges]);
          cyRef.current.layout({ name: currentLayout }).run();
          updateAvailableNodes(cyRef.current);
        }
        
        alert('データが正常にインポートされました。');
      } catch (error) {
        console.error('Import error:', error);
        alert('ファイルの読み込みに失敗しました。');
      }
    };
    
    reader.readAsText(file);
  }, [currentLayout, updateAvailableNodes]);

  return (
    <div className="h-screen flex flex-col bg-gradient-to-br from-slate-900 via-blue-900 to-indigo-900">
      {/* ヘッダー */}
      <div className="bg-black/30 backdrop-blur-lg border-b border-white/10 p-4">
        <div className="flex items-center justify-between">
          <div>
            <h1 className="text-2xl font-bold text-cyan-400 drop-shadow-lg">
              数学理論×IT技術 統合生態系
            </h1>
            <p className="text-sm text-cyan-300 mt-1">
              {viewMode === 'math' && '数学理論の生物的表現'}
              {viewMode === 'tech' && 'IT技術の生態系'}
              {viewMode === 'integrated' && '数学理論とIT技術の統合生態系'}
            </p>
          </div>
          
          <div className="flex items-center gap-4">
            {/* ビューモード切り替え */}
            <div className="flex gap-1 bg-black/30 rounded-full p-1">
              <button
                onClick={() => setViewMode('math')}
                className={`px-3 py-1 rounded-full text-xs transition-all duration-300 ${
                  viewMode === 'math'
                    ? 'bg-green-500 text-white'
                    : 'text-green-400 hover:bg-green-500/20'
                }`}
              >
                数学理論
              </button>
              <button
                onClick={() => setViewMode('tech')}
                className={`px-3 py-1 rounded-full text-xs transition-all duration-300 ${
                  viewMode === 'tech'
                    ? 'bg-blue-500 text-white'
                    : 'text-blue-400 hover:bg-blue-500/20'
                }`}
              >
                IT技術
              </button>
              <button
                onClick={() => setViewMode('integrated')}
                className={`px-3 py-1 rounded-full text-xs transition-all duration-300 ${
                  viewMode === 'integrated'
                    ? 'bg-purple-500 text-white'
                    : 'text-purple-400 hover:bg-purple-500/20'
                }`}
              >
                統合
              </button>
            </div>
            {/* 基本コントロール */}
            <div className="flex gap-2">
              <button
                onClick={resetView}
                className="px-4 py-2 bg-cyan-500/20 border border-cyan-400 rounded-full text-cyan-400 hover:bg-cyan-500/40 transition-all duration-300 text-sm"
              >
                リセット
              </button>
              <button
                onClick={fitToView}
                className="px-4 py-2 bg-cyan-500/20 border border-cyan-400 rounded-full text-cyan-400 hover:bg-cyan-500/40 transition-all duration-300 text-sm"
              >
                全体表示
              </button>
              <button
                onClick={toggleLayout}
                className="px-4 py-2 bg-cyan-500/20 border border-cyan-400 rounded-full text-cyan-400 hover:bg-cyan-500/40 transition-all duration-300 text-sm"
              >
                レイアウト変更
              </button>
            </div>
            
            {/* 編集コントロール */}
            <div className="flex gap-2">
              <button
                onClick={() => setEditMode(!editMode)}
                className={`px-4 py-2 border rounded-full transition-all duration-300 text-sm ${
                  editMode
                    ? 'bg-yellow-500/60 border-yellow-400 text-yellow-100'
                    : 'bg-yellow-500/20 border-yellow-400 text-yellow-400 hover:bg-yellow-500/40'
                }`}
              >
                {editMode ? '編集中' : '編集モード'}
              </button>
              <button
                onClick={addNode}
                className="px-3 py-2 bg-yellow-500/20 border border-yellow-400 rounded-full text-yellow-400 hover:bg-yellow-500/40 transition-all duration-300 text-xs"
              >
                ノード追加
              </button>
              <button
                onClick={addEdge}
                className="px-3 py-2 bg-yellow-500/20 border border-yellow-400 rounded-full text-yellow-400 hover:bg-yellow-500/40 transition-all duration-300 text-xs"
              >
                エッジ追加
              </button>
              <button
                onClick={deleteSelected}
                className="px-3 py-2 bg-red-500/20 border border-red-400 rounded-full text-red-400 hover:bg-red-500/40 transition-all duration-300 text-xs"
              >
                削除
              </button>
            </div>
            
            {/* ファイル操作 */}
            <div className="flex gap-2">
              <button
                onClick={exportData}
                className="px-3 py-2 bg-gray-500/20 border border-gray-400 rounded-full text-gray-400 hover:bg-gray-500/40 transition-all duration-300 text-xs"
              >
                エクスポート
              </button>
              <label className="px-3 py-2 bg-gray-500/20 border border-gray-400 rounded-full text-gray-400 hover:bg-gray-500/40 transition-all duration-300 text-xs cursor-pointer">
                インポート
                <input
                  type="file"
                  accept=".json"
                  onChange={importData}
                  className="hidden"
                />
              </label>
            </div>
          </div>
        </div>
        
        {/* 編集モードインジケーター */}
        {editMode && (
          <div className="mt-2 inline-block bg-yellow-500/90 text-black px-4 py-2 rounded-full font-bold text-sm">
            📝 編集モード: ノードをダブルクリックで編集、右クリックで削除
          </div>
        )}
      </div>
      
      {/* メインコンテンツ */}
      <div className="flex-1 relative">
        {/* Cytoscapeコンテナ */}
        <div ref={containerRef} className="w-full h-full" />
        
        {/* 凡例 */}
        <div className="absolute bottom-4 left-4 bg-black/70 backdrop-blur-lg rounded-lg p-4 border border-white/10 max-w-[280px]">
          <h3 className="text-cyan-400 font-bold text-center mb-3">凡例</h3>
          <div className="space-y-2 text-xs">
            {(viewMode === 'math' || viewMode === 'integrated') && (
              <>
                <div className="text-green-400 font-semibold mb-1">数学理論</div>
                <div className="flex items-center gap-2">
                  <div className="w-3 h-3 rounded-full bg-yellow-900 border border-white/30" />
                  <span className="text-white">基礎論（根系）</span>
                </div>
                <div className="flex items-center gap-2">
                  <div className="w-3 h-3 rounded-full bg-green-700 border border-white/30" />
                  <span className="text-white">純粋数学（幹・枝）</span>
                </div>
                <div className="flex items-center gap-2">
                  <div className="w-3 h-3 rounded-full bg-yellow-600 border border-white/30" />
                  <span className="text-white">応用数学（葉・花）</span>
                </div>
                <div className="flex items-center gap-2">
                  <div className="w-3 h-3 rounded-full bg-red-600 border border-white/30" />
                  <span className="text-white">学際領域（果実）</span>
                </div>
              </>
            )}
            
            {(viewMode === 'tech' || viewMode === 'integrated') && (
              <>
                <div className="text-blue-400 font-semibold mb-1 mt-3">IT技術</div>
                <div className="flex items-center gap-2">
                  <div className="w-3 h-3 rounded-full bg-amber-900 border border-white/30" />
                  <span className="text-white">計算基盤（土壌）</span>
                </div>
                <div className="flex items-center gap-2">
                  <div className="w-3 h-3 rounded-full bg-purple-700 border border-white/30" />
                  <span className="text-white">低レベル言語（分解者）</span>
                </div>
                <div className="flex items-center gap-2">
                  <div className="w-3 h-3 rounded-full bg-orange-700 border border-white/30" />
                  <span className="text-white">システム言語（菌類）</span>
                </div>
                <div className="flex items-center gap-2">
                  <div className="w-3 h-3 rounded-full bg-green-600 border border-white/30" />
                  <span className="text-white">高レベル言語（植物）</span>
                </div>
                <div className="flex items-center gap-2">
                  <div className="w-3 h-3 rounded-full bg-teal-600 border border-white/30" />
                  <span className="text-white">フレームワーク（共生）</span>
                </div>
                <div className="flex items-center gap-2">
                  <div className="w-3 h-3 rounded-full bg-blue-700 border border-white/30" />
                  <span className="text-white">クラウド（生態系拡張）</span>
                </div>
                <div className="flex items-center gap-2">
                  <div className="w-3 h-3 rounded-full bg-green-800 border border-white/30" />
                  <span className="text-white">データベース特化（遺伝子）</span>
                </div>
                <div className="flex items-center gap-2">
                  <div className="w-3 h-3 rounded-full bg-purple-700 border border-white/30" />
                  <span className="text-white">API/通信（受容体・信号）</span>
                </div>
                <div className="flex items-center gap-2">
                  <div className="w-3 h-3 rounded-full bg-orange-700 border border-white/30" />
                  <span className="text-white">ワークフロー（代謝調整）</span>
                </div>
                <div className="flex items-center gap-2">
                  <div className="w-3 h-3 rounded-full bg-indigo-700 border border-white/30" />
                  <span className="text-white">AI通信（知識共有）</span>
                </div>
              </>
            )}
            
            {viewMode === 'integrated' && (
              <>
                <div className="text-purple-400 font-semibold mb-1 mt-3">数学-IT連携</div>
                <div className="flex items-center gap-2">
                  <div className="w-8 h-1 bg-purple-300 border border-white/30 opacity-80" style={{borderStyle: 'dotted'}} />
                  <span className="text-white">理論-実装橋渡し</span>
                </div>
              </>
            )}
          </div>
        </div>
        
        {/* 情報パネル */}
        {showInfoPanel && infoData && (
          <div className="absolute top-4 right-4 w-80 bg-black/90 backdrop-blur-lg rounded-lg p-4 border border-white/10 max-h-96 overflow-y-auto">
            <div className="flex justify-between items-start mb-3">
              <h3 className="text-cyan-400 font-bold text-lg">{infoData.title}</h3>
              <button
                onClick={() => setShowInfoPanel(false)}
                className="text-gray-400 hover:text-white text-xl"
              >
                ×
              </button>
            </div>
            <p className="text-gray-300 text-sm leading-relaxed">{infoData.description}</p>
            {infoData.details && (
              <div className="mt-3 text-xs text-gray-400">
                <div>カテゴリ: {infoData.details.category}</div>
                <div>タイプ: {infoData.details.type}</div>
                {infoData.details.level && <div>レベル: {infoData.details.level}</div>}
              </div>
            )}
          </div>
        )}
      </div>
      
      {/* ノード編集モーダル */}
      {showNodeModal && (
        <div className="fixed inset-0 bg-black/80 backdrop-blur-sm flex items-center justify-center z-50">
          <div className="bg-gradient-to-br from-slate-800 to-blue-900 rounded-lg p-6 w-96 max-w-full border border-white/20">
            <div className="flex justify-between items-center mb-4">
              <h3 className="text-cyan-400 font-bold text-lg">ノード編集</h3>
              <button
                onClick={() => setShowNodeModal(false)}
                className="text-gray-400 hover:text-white text-xl"
              >
                ×
              </button>
            </div>
            
            <div className="space-y-4">
              <div>
                <label className="block text-cyan-400 text-sm font-medium mb-1">ID:</label>
                <input
                  type="text"
                  value={nodeFormData.id || ''}
                  onChange={(e) => setNodeFormData(prev => ({ ...prev, id: e.target.value }))}
                  className="w-full p-2 bg-white/10 border border-white/30 rounded text-white placeholder-gray-400"
                  placeholder="ノードのIDを入力"
                />
              </div>
              
              <div>
                <label className="block text-cyan-400 text-sm font-medium mb-1">ラベル:</label>
                <input
                  type="text"
                  value={nodeFormData.label || ''}
                  onChange={(e) => setNodeFormData(prev => ({ ...prev, label: e.target.value }))}
                  className="w-full p-2 bg-white/10 border border-white/30 rounded text-white placeholder-gray-400"
                  placeholder="表示ラベルを入力"
                />
              </div>
              
              <div>
                <label className="block text-cyan-400 text-sm font-medium mb-1">タイプ:</label>
                <select
                  value={nodeFormData.type || 'foundation'}
                  onChange={(e) => setNodeFormData(prev => ({ ...prev, type: e.target.value }))}
                  className="w-full p-2 bg-white/10 border border-white/30 rounded text-white"
                  aria-label="ノードタイプを選択"
                >
                  <optgroup label="数学理論">
                    <option value="foundation_root">基礎論（根系）</option>
                    <option value="foundation">基礎論</option>
                    <option value="trunk">主幹</option>
                    <option value="branch_algebra">代数枝</option>
                    <option value="branch_geometry">幾何枝</option>
                    <option value="branch_analysis">解析枝</option>
                    <option value="branch_number">数論枝</option>
                    <option value="branch_discrete">離散枝</option>
                    <option value="sub_branch">サブ枝</option>
                    <option value="leaf_cluster">葉群</option>
                    <option value="leaf">葉</option>
                    <option value="fruit_cluster">果実群</option>
                    <option value="fruit">果実</option>
                    <option value="seed">種</option>
                  </optgroup>
                  <optgroup label="IT技術">
                    <option value="soil">計算基盤（土壌）</option>
                    <option value="mineral">ハードウェア（鉱物）</option>
                    <option value="microbe">OS（微生物）</option>
                    <option value="mycelium">ネットワーク（菌糸）</option>
                    <option value="decomposer">低レベル言語（分解者）</option>
                    <option value="fungi">システム言語（菌類）</option>
                    <option value="herbaceous">インタープリタ言語（草本）</option>
                    <option value="woody">コンパイル言語（木本）</option>
                    <option value="conifer">関数型言語（針葉樹）</option>
                    <option value="epiphyte">Webフレームワーク（着生）</option>
                    <option value="support_structure">バックエンド（支持構造）</option>
                    <option value="pollinator">ML（花粉媒介者）</option>
                    <option value="nutrient_cycler">科学計算（栄養循環）</option>
                    <option value="storage_organ">データベース（貯蔵庫）</option>
                    <option value="neural_network">グラフDB（神経網）</option>
                    <option value="ecosystem_expansion">クラウド（拡張）</option>
                    <option value="seed_dispersal">コンテナ（種子散布）</option>
                    <option value="ecosystem_management">DevOps（管理）</option>
                    <option value="immune_system">セキュリティ（免疫）</option>
                    <option value="sensory_system">監視（感覚器官）</option>
                    <option value="evolution_seed">AI/ML（進化の種）</option>
                    <option value="new_ecosystem">量子（新生態系）</option>
                    <option value="genetic_library">PostgreSQL（遺伝子ライブラリ）</option>
                    <option value="membrane_receptor">REST API（細胞膜受容体）</option>
                    <option value="neural_signal">JSON-RPC（神経信号）</option>
                    <option value="knowledge_network">MCP（知識共有網）</option>
                    <option value="symbiotic_network">A2A（共生ネットワーク）</option>
                    <option value="metabolic_regulator">Camunda（代謝調整者）</option>
                    <option value="memory_formation">Event Sourcing（記憶形成）</option>
                  </optgroup>
                </select>
              </div>
              
              <div>
                <label className="block text-cyan-400 text-sm font-medium mb-1">説明:</label>
                <textarea
                  value={nodeFormData.description || ''}
                  onChange={(e) => setNodeFormData(prev => ({ ...prev, description: e.target.value }))}
                  className="w-full p-2 bg-white/10 border border-white/30 rounded text-white placeholder-gray-400 h-20 resize-none"
                  placeholder="ノードの説明を入力してください"
                />
              </div>
              
              <div className="flex gap-2 justify-end">
                <button
                  onClick={() => setShowNodeModal(false)}
                  className="px-4 py-2 bg-gray-500/20 border border-gray-400 rounded text-gray-400 hover:bg-gray-500/40 transition-all duration-300"
                >
                  キャンセル
                </button>
                <button
                  onClick={saveNode}
                  className="px-4 py-2 bg-cyan-500 border border-cyan-400 rounded text-white hover:bg-cyan-600 transition-all duration-300"
                >
                  保存
                </button>
              </div>
            </div>
          </div>
        </div>
      )}
      
      {/* エッジ編集モーダル */}
      {showEdgeModal && (
        <div className="fixed inset-0 bg-black/80 backdrop-blur-sm flex items-center justify-center z-50">
          <div className="bg-gradient-to-br from-slate-800 to-blue-900 rounded-lg p-6 w-96 max-w-full border border-white/20">
            <div className="flex justify-between items-center mb-4">
              <h3 className="text-cyan-400 font-bold text-lg">エッジ編集</h3>
              <button
                onClick={() => setShowEdgeModal(false)}
                className="text-gray-400 hover:text-white text-xl"
              >
                ×
              </button>
            </div>
            
            <div className="space-y-4">
              <div>
                <label className="block text-cyan-400 text-sm font-medium mb-1">開始ノード:</label>
                <select
                  value={edgeFormData.source || ''}
                  onChange={(e) => setEdgeFormData(prev => ({ ...prev, source: e.target.value }))}
                  className="w-full p-2 bg-white/10 border border-white/30 rounded text-white"
                  aria-label="開始ノードを選択"
                >
                  <option value="">選択してください</option>
                  {availableNodes.map(node => (
                    <option key={node.id} value={node.id}>{node.label}</option>
                  ))}
                </select>
              </div>
              
              <div>
                <label className="block text-cyan-400 text-sm font-medium mb-1">終了ノード:</label>
                <select
                  value={edgeFormData.target || ''}
                  onChange={(e) => setEdgeFormData(prev => ({ ...prev, target: e.target.value }))}
                  className="w-full p-2 bg-white/10 border border-white/30 rounded text-white"
                  aria-label="終了ノードを選択"
                >
                  <option value="">選択してください</option>
                  {availableNodes.map(node => (
                    <option key={node.id} value={node.id}>{node.label}</option>
                  ))}
                </select>
              </div>
              
              <div>
                <label className="block text-cyan-400 text-sm font-medium mb-1">タイプ:</label>
                <select
                  value={edgeFormData.type || 'nutrient_flow'}
                  onChange={(e) => setEdgeFormData(prev => ({ ...prev, type: e.target.value }))}
                  className="w-full p-2 bg-white/10 border border-white/30 rounded text-white"
                  aria-label="エッジタイプを選択"
                >
                  <optgroup label="数学理論">
                    <option value="nutrient_flow">栄養供給</option>
                    <option value="foundation_to_pure">基礎→純粋</option>
                    <option value="growth_flow">成長フロー</option>
                    <option value="sub_branch_growth">サブ枝成長</option>
                    <option value="cross_pollination">交差受粉</option>
                    <option value="application_flow">応用フロー</option>
                    <option value="specialization">特化</option>
                    <option value="synthesis_flow">統合フロー</option>
                    <option value="fruition">結実</option>
                    <option value="seed_formation">種形成</option>
                    <option value="direct_application">直接応用</option>
                    <option value="deep_root_support">深層サポート</option>
                  </optgroup>
                  <optgroup label="IT技術">
                    <option value="foundation_support">基盤サポート</option>
                    <option value="nutrient_supply">栄養供給</option>
                    <option value="evolution_flow">進化フロー</option>
                    <option value="platform_support">プラットフォーム支援</option>
                    <option value="framework_growth">フレームワーク成長</option>
                    <option value="data_flow">データフロー</option>
                    <option value="scaling_need">スケール要求</option>
                    <option value="deployment_evolution">デプロイ進化</option>
                    <option value="operational_need">運用要求</option>
                    <option value="security_integration">セキュリティ統合</option>
                    <option value="operational_visibility">運用可視化</option>
                    <option value="platform_evolution">プラットフォーム進化</option>
                    <option value="paradigm_shift">パラダイム転換</option>
                    <option value="collaboration">連携</option>
                    <option value="data_synergy">データシナジー</option>
                    <option value="security_layer">セキュリティ層</option>
                    <option value="intelligent_ops">インテリジェント運用</option>
                    <option value="data_source">データソース提供</option>
                    <option value="api_layer">API層実装</option>
                    <option value="rpc_layer">RPC実装</option>
                    <option value="integration_method">統合手段</option>
                    <option value="workflow_trigger">ワークフロー起動</option>
                    <option value="process_recording">プロセス記録</option>
                    <option value="event_storage">イベント保存</option>
                    <option value="ai_communication">AI間通信</option>
                    <option value="protocol_implementation">プロトコル実装</option>
                    <option value="automation_orchestration">自動化連携</option>
                    <option value="service_exposure">サービス公開</option>
                    <option value="audit_trail">監査証跡</option>
                  </optgroup>
                  <optgroup label="数学-IT連携">
                    <option value="math_implementation">数学実装</option>
                    <option value="math_application">数学応用</option>
                    <option value="math_optimization">数学最適化</option>
                    <option value="math_foundation">数学基盤</option>
                    <option value="math_influence">数学影響</option>
                    <option value="math_evolution">数学進化</option>
                  </optgroup>
                </select>
              </div>
              
              <div>
                <label className="block text-cyan-400 text-sm font-medium mb-1">ラベル:</label>
                <input
                  type="text"
                  value={edgeFormData.label || ''}
                  onChange={(e) => setEdgeFormData(prev => ({ ...prev, label: e.target.value }))}
                  className="w-full p-2 bg-white/10 border border-white/30 rounded text-white placeholder-gray-400"
                  placeholder="エッジのラベルを入力してください"
                />
              </div>
              
              <div className="flex gap-2 justify-end">
                <button
                  onClick={() => setShowEdgeModal(false)}
                  className="px-4 py-2 bg-gray-500/20 border border-gray-400 rounded text-gray-400 hover:bg-gray-500/40 transition-all duration-300"
                >
                  キャンセル
                </button>
                <button
                  onClick={saveEdge}
                  className="px-4 py-2 bg-cyan-500 border border-cyan-400 rounded text-white hover:bg-cyan-600 transition-all duration-300"
                >
                  保存
                </button>
              </div>
            </div>
          </div>
        </div>
      )}
    </div>
  );
} 