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
}

interface NodeData {
  id: string;
  label: string;
  type: string;
  category: string;
  level: number;
  description: string;
  details: string;
}

interface EdgeData {
  source: string;
  target: string;
  type: string;
  label: string;
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

  // JSONデータの読み込み
  const loadTheoryData = useCallback(async (): Promise<TheoryData> => {
    try {
      console.log('Loading complete theory data...');
      const response = await fetch('/data/complete-theory-data.json');
      
      if (!response.ok) {
        throw new Error(`HTTP error! status: ${response.status}`);
      }
      
      const data = await response.json();
      console.log('Complete theory data loaded successfully');
      console.log('Nodes:', data.nodes.length);
      console.log('Edges:', data.edges.length);
      
      return data;
    } catch (error) {
      console.error('Error loading theory data:', error);
      throw error;
    }
  }, []);

  // Cytoscapeの初期化
  const initializeCytoscape = useCallback(async () => {
    if (!containerRef.current) return;

    try {
      const theoryData = await loadTheoryData();
      
      const cy = cytoscape({
        container: containerRef.current,
        elements: [...theoryData.nodes, ...theoryData.edges],
        
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
          
          // エッジタイプ別スタイル
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
      
      console.log('Editable mathematical theory organism initialized!');
      console.log('Nodes:', cy.nodes().length);
      console.log('Edges:', cy.edges().length);
      
    } catch (error) {
      console.error('Error initializing Cytoscape:', error);
    }
  }, [loadTheoryData]);

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

  useEffect(() => {
    initializeCytoscape();
    
    return () => {
      if (cyRef.current) {
        cyRef.current.destroy();
      }
    };
  }, [initializeCytoscape]);

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
  }, [nodeFormData, currentLayout]);

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
  }, [selectedElement]);

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
    a.download = `math-theory-${Date.now()}.json`;
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
  }, [currentLayout]);

  return (
    <div className="h-screen flex flex-col bg-gradient-to-br from-slate-900 via-blue-900 to-indigo-900">
      {/* ヘッダー */}
      <div className="bg-black/30 backdrop-blur-lg border-b border-white/10 p-4">
        <div className="flex items-center justify-between">
          <h1 className="text-2xl font-bold text-cyan-400 drop-shadow-lg">
            数学理論生物体系図 - 編集可能版
          </h1>
          
          <div className="flex items-center gap-4">
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
        <div className="absolute bottom-4 left-4 bg-black/70 backdrop-blur-lg rounded-lg p-4 border border-white/10 min-w-[200px]">
          <h3 className="text-cyan-400 font-bold text-center mb-3">凡例</h3>
          <div className="space-y-2">
            <div className="flex items-center gap-2 text-sm">
              <div className="w-4 h-4 rounded-full bg-yellow-900 border border-white/30" />
              <span className="text-white">基礎論（根系）</span>
            </div>
            <div className="flex items-center gap-2 text-sm">
              <div className="w-4 h-4 rounded-full bg-green-700 border border-white/30" />
              <span className="text-white">純粋数学（幹・枝）</span>
            </div>
            <div className="flex items-center gap-2 text-sm">
              <div className="w-4 h-4 rounded-full bg-yellow-600 border border-white/30" />
              <span className="text-white">応用数学（葉・花）</span>
            </div>
            <div className="flex items-center gap-2 text-sm">
              <div className="w-4 h-4 rounded-full bg-red-600 border border-white/30" />
              <span className="text-white">学際領域（果実）</span>
            </div>
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
                />
              </div>
              
              <div>
                <label className="block text-cyan-400 text-sm font-medium mb-1">ラベル:</label>
                <input
                  type="text"
                  value={nodeFormData.label || ''}
                  onChange={(e) => setNodeFormData(prev => ({ ...prev, label: e.target.value }))}
                  className="w-full p-2 bg-white/10 border border-white/30 rounded text-white placeholder-gray-400"
                />
              </div>
              
              <div>
                <label className="block text-cyan-400 text-sm font-medium mb-1">タイプ:</label>
                <select
                  value={nodeFormData.type || 'foundation'}
                  onChange={(e) => setNodeFormData(prev => ({ ...prev, type: e.target.value }))}
                  className="w-full p-2 bg-white/10 border border-white/30 rounded text-white"
                >
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
                >
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