'use client';

import { useEffect, useRef, useState, useCallback } from 'react';
import cytoscape, { Core, NodeSingular, EdgeSingular } from 'cytoscape';
// @ts-ignore
import dagre from 'cytoscape-dagre';
// @ts-ignore
import coseBilkent from 'cytoscape-cose-bilkent';
import Hammer from 'hammerjs';
import TrackpadControls from './TrackpadControls';

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
  id: string;
  source: string;
  target: string;
  label: string;
  type: string;
}

interface SubNetwork {
  id: string;
  name: string;
  description: string;
  color: string;
  edgeIds: string[];
}

interface InfoData {
  title: string;
  description: string;
  details: any;
}

export default function CytoscapeVisualization() {
  // Core refs and state
  const containerRef = useRef<HTMLDivElement>(null);
  const cyRef = useRef<Core | null>(null);
  
  // View state
  const [viewMode, setViewMode] = useState<'math' | 'tech' | 'org' | 'gftd' | 'integrated'>('integrated');
  const [currentLayout, setCurrentLayout] = useState<'dagre' | 'cose-bilkent' | 'grid' | 'circle'>('cose-bilkent');
  const [editMode, setEditMode] = useState(false);
  
  // Data state
  const [mathData, setMathData] = useState<TheoryData | null>(null);
  const [techData, setTechData] = useState<TheoryData | null>(null);
  const [orgData, setOrgData] = useState<TheoryData | null>(null);
  const [gftdData, setGftdData] = useState<TheoryData | null>(null);
  const [userEdits, setUserEdits] = useState<{nodes: any[], edges: any[]}>({nodes: [], edges: []});
  const [hasUnsavedChanges, setHasUnsavedChanges] = useState(false);

  // UI state
  const [showInfoPanel, setShowInfoPanel] = useState(false);
  const [infoData, setInfoData] = useState<InfoData | null>(null);
  const [showTrackpadHelp, setShowTrackpadHelp] = useState(false);
  
  // 3ペイン構成用のUI状態
  const [leftPanelTab, setLeftPanelTab] = useState<'nodes' | 'edges' | 'subnets'>('nodes');
  const [selectedNodeIds, setSelectedNodeIds] = useState<string[]>([]);
  const [selectedEdgeIds, setSelectedEdgeIds] = useState<string[]>([]);
  const [editingItem, setEditingItem] = useState<{type: 'node' | 'edge', id: string, data: any} | null>(null);

  // サブネットワーク関連の状態
  const [subNetworks, setSubNetworks] = useState<SubNetwork[]>([]);
  const [selectedEdges, setSelectedEdges] = useState<string[]>([]);
  const [showSubNetworkModal, setShowSubNetworkModal] = useState(false);
  const [subNetworkFormData, setSubNetworkFormData] = useState<Partial<SubNetwork>>({});
  const [activeSubNetworks, setActiveSubNetworks] = useState<string[]>([]);
  const [edgeSelectionMode, setEdgeSelectionMode] = useState(false);

  // Modal state
  const [showNodeModal, setShowNodeModal] = useState(false);
  const [showEdgeModal, setShowEdgeModal] = useState(false);
  const [nodeFormData, setNodeFormData] = useState<Partial<NodeData>>({});
  const [edgeFormData, setEdgeFormData] = useState<Partial<EdgeData>>({});
  
  // JSONデータの読み込み
  const loadMathData = useCallback(async (): Promise<TheoryData> => {
    try {
      console.log('Loading math theory data...');
      const response = await fetch('/data/complete-theory-data.json');
      if (!response.ok) {
        throw new Error(`Failed to fetch math data: ${response.status}`);
      }
      const data = await response.json();
      console.log('Math theory data loaded:', data);
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
        throw new Error(`Failed to fetch tech data: ${response.status}`);
      }
      const data = await response.json();
      console.log('Tech ecosystem data loaded:', data);
      return data;
    } catch (error) {
      console.error('Error loading tech ecosystem data:', error);
      throw error;
    }
  }, []);

  const loadOrgData = useCallback(async (): Promise<TheoryData> => {
    try {
      console.log('Loading organization ecosystem data...');
      const response = await fetch('/data/organization-ecosystem-data.json');
      if (!response.ok) {
        throw new Error(`Failed to fetch org data: ${response.status}`);
      }
      const data = await response.json();
      console.log('Organization ecosystem data loaded:', data);
      return data;
    } catch (error) {
      console.error('Error loading organization ecosystem data:', error);
      throw error;
    }
  }, []);

  const loadGftdData = useCallback(async (): Promise<TheoryData> => {
    try {
      console.log('Loading gftd.ai ecosystem data...');
      const response = await fetch('/data/gftd-ai-ecosystem-data.json');
      if (!response.ok) {
        throw new Error(`Failed to fetch gftd data: ${response.status}`);
      }
      const data = await response.json();
      console.log('GFTD.ai ecosystem data loaded:', data);
      return data;
    } catch (error) {
      console.error('Error loading gftd.ai ecosystem data:', error);
      throw error;
    }
  }, []);

  // 統合データの取得
  const getIntegratedData = useCallback((): TheoryData => {
    if (!mathData || !techData || !orgData || !gftdData) {
      return { nodes: [], edges: [] };
    }

    return { 
      nodes: [...mathData.nodes, ...techData.nodes, ...orgData.nodes, ...gftdData.nodes],
      edges: [...mathData.edges, ...techData.edges, ...orgData.edges, ...gftdData.edges, ...(techData.mathConnections || [])]
    };
  }, [mathData, techData, orgData, gftdData]);

  // 現在のビューモードに応じたデータを取得
  const getCurrentData = useCallback((): TheoryData => {
    const baseData = (() => {
      switch (viewMode) {
        case 'math':
          return mathData || { nodes: [], edges: [] };
        case 'tech':
          return techData || { nodes: [], edges: [] };
        case 'org':
          return orgData || { nodes: [], edges: [] };
        case 'gftd':
          return gftdData || { nodes: [], edges: [] };
        case 'integrated':
          return getIntegratedData();
        default:
          return { nodes: [], edges: [] };
      }
    })();

    // ユーザー編集を適用
    const updatedNodes = baseData.nodes.map(node => {
      const userEdit = userEdits.nodes.find(edit => edit.data.id === node.data.id);
      return userEdit || node;
    }).concat(userEdits.nodes.filter(userNode => 
      !baseData.nodes.some(baseNode => baseNode.data.id === userNode.data.id)
    ));

    const updatedEdges = baseData.edges.map(edge => {
      const userEdit = userEdits.edges.find(edit => edit.data.id === edge.data.id);
      return userEdit || edge;
    }).concat(userEdits.edges.filter(userEdge => 
      !baseData.edges.some(baseEdge => baseEdge.data.id === userEdge.data.id)
    ));

    return { nodes: updatedNodes, edges: updatedEdges };
  }, [viewMode, mathData, techData, orgData, gftdData, getIntegratedData, userEdits]);

  // ユーザー編集の追跡
  const trackUserEdit = useCallback((type: 'node' | 'edge', action: 'add' | 'update' | 'delete', data: any) => {
    setUserEdits(prev => {
      const newUserEdits = { ...prev };
      
      if (type === 'node') {
        if (action === 'add') {
          newUserEdits.nodes = [...prev.nodes, { data }];
        } else if (action === 'update') {
          const index = prev.nodes.findIndex(n => n.data.id === data.id);
          if (index >= 0) {
            newUserEdits.nodes = [...prev.nodes];
            newUserEdits.nodes[index] = { data };
          } else {
            newUserEdits.nodes = [...prev.nodes, { data }];
          }
        } else if (action === 'delete') {
          newUserEdits.nodes = prev.nodes.filter(n => n.data.id !== data.id);
        }
      } else if (type === 'edge') {
        if (action === 'add') {
          newUserEdits.edges = [...prev.edges, { data }];
        } else if (action === 'update') {
          const index = prev.edges.findIndex(e => e.data.id === data.id);
          if (index >= 0) {
            newUserEdits.edges = [...prev.edges];
            newUserEdits.edges[index] = { data };
          } else {
            newUserEdits.edges = [...prev.edges, { data }];
          }
        } else if (action === 'delete') {
          newUserEdits.edges = prev.edges.filter(e => e.data.id !== data.id);
        }
      }
      
      setHasUnsavedChanges(true);
      return newUserEdits;
    });
  }, []);

  // エッジタイプ取得
  const getAvailableEdgeTypes = useCallback((): string[] => {
    if (!cyRef.current) return [];
    const edgeTypes = new Set<string>();
    cyRef.current.edges().forEach((edge: EdgeSingular) => {
      const type = edge.data('type');
      if (type) edgeTypes.add(type);
    });
    return Array.from(edgeTypes).sort();
  }, []);

  // エッジ選択
  const toggleEdgeSelection = useCallback((edgeId: string) => {
    setSelectedEdges(prev => {
      if (prev.includes(edgeId)) {
        return prev.filter(id => id !== edgeId);
      } else {
        return [...prev, edgeId];
      }
    });
  }, []);

  // エッジタイプ別選択
  const selectEdgesByType = useCallback((edgeType: string) => {
    if (!cyRef.current) return;
    
    const edgesOfType = cyRef.current.edges().filter((edge: EdgeSingular) => {
      return edge.data('type') === edgeType;
    });
    
    const edgeIds = edgesOfType.map((edge: EdgeSingular) => edge.id());
    
    setSelectedEdges(prev => {
      const newSelection = new Set([...prev, ...edgeIds]);
      return Array.from(newSelection);
    });
  }, []);

  // サブネットワーク作成
  const createSubNetwork = useCallback(() => {
    if (selectedEdges.length === 0) return;
    
    setSubNetworkFormData({
      name: `サブネット ${subNetworks.length + 1}`,
      description: `${selectedEdges.length}個のエッジを含むサブネットワーク`,
      color: '#666666',
      edgeIds: selectedEdges
    });
    
    setShowSubNetworkModal(true);
  }, [selectedEdges, subNetworks.length]);

  // サブネットワーク保存
  const saveSubNetwork = useCallback(() => {
    if (!subNetworkFormData.name || !subNetworkFormData.edgeIds) return;
    
    const newSubNetwork: SubNetwork = {
      id: `subnet_${Date.now()}`,
      name: subNetworkFormData.name,
      description: subNetworkFormData.description || '',
      color: subNetworkFormData.color || '#666666',
      edgeIds: subNetworkFormData.edgeIds
    };
    
    setSubNetworks(prev => {
      const existing = prev.find(sn => sn.id === newSubNetwork.id);
      if (existing) {
        return prev.map(sn => sn.id === newSubNetwork.id ? newSubNetwork : sn);
      } else {
        return [...prev, newSubNetwork];
      }
    });
    
    setShowSubNetworkModal(false);
    setSubNetworkFormData({});
    setSelectedEdges([]);
  }, [subNetworkFormData]);

  // サブネットワーク切り替え
  const toggleSubNetwork = useCallback((subNetworkId: string) => {
    setActiveSubNetworks(prev => {
      if (prev.includes(subNetworkId)) {
        return prev.filter(id => id !== subNetworkId);
      } else {
        return [...prev, subNetworkId];
      }
    });
  }, []);

  // サブネットワークフィルタ適用
  const applySubNetworkFilters = useCallback(() => {
    if (!cyRef.current) return;
    
    if (activeSubNetworks.length === 0) {
      // 全て表示
      cyRef.current.elements().style('display', 'element');
      return;
    }
    
    // 表示するエッジIDを収集
    const visibleEdgeIds = new Set<string>();
    activeSubNetworks.forEach(subNetworkId => {
      const subnet = subNetworks.find(sn => sn.id === subNetworkId);
      if (subnet) {
        subnet.edgeIds.forEach(edgeId => visibleEdgeIds.add(edgeId));
        
        // サブネットワークの色でエッジを表示
        subnet.edgeIds.forEach(edgeId => {
          const edge = cyRef.current?.getElementById(edgeId);
          if (edge && edge.length > 0) {
            edge.style({
              'line-color': subnet.color,
              'target-arrow-color': subnet.color,
            });
          }
        });
      }
    });
    
    // エッジの表示/非表示
    cyRef.current.edges().forEach((edge: EdgeSingular) => {
      if (visibleEdgeIds.has(edge.id())) {
        edge.style('display', 'element');
      } else {
        edge.style('display', 'none');
      }
    });
    
    // 表示されたエッジに接続するノードのみ表示
    const visibleNodeIds = new Set<string>();
    cyRef.current.edges(':visible').forEach((edge: EdgeSingular) => {
      visibleNodeIds.add(edge.source().id());
      visibleNodeIds.add(edge.target().id());
    });
    
    cyRef.current.nodes().forEach((node: NodeSingular) => {
      if (visibleNodeIds.has(node.id())) {
        node.style('display', 'element');
      } else {
        node.style('display', 'none');
      }
    });
  }, [activeSubNetworks, subNetworks]);

  // プリセットサブネットワークの定義
  const getPresetSubNetworks = useCallback((): SubNetwork[] => {
    return [
      {
        id: 'math_foundation',
        name: '数学基盤ネットワーク',
        description: '基礎理論の栄養供給と成長フロー',
        color: '#4ade80',
        edgeIds: []
      },
      {
        id: 'math_development', 
        name: '数学発展ネットワーク',
        description: '理論の発展と応用への分岐',
        color: '#22c55e',
        edgeIds: []
      },
      {
        id: 'math_collaboration',
        name: '数学協働ネットワーク',
        description: '分野間交流と学際的結実',
        color: '#16a34a',
        edgeIds: []
      },
      {
        id: 'tech_infrastructure',
        name: 'IT基盤ネットワーク',
        description: 'インフラと支援システム',
        color: '#3b82f6',
        edgeIds: []
      },
      {
        id: 'tech_development',
        name: 'IT開発ネットワーク',
        description: '開発・デプロイ・スケーリング',
        color: '#2563eb',
        edgeIds: []
      },
      {
        id: 'data_integration',
        name: 'データ統合ネットワーク',
        description: 'データフロー・API・統合',
        color: '#1d4ed8',
        edgeIds: []
      },
      {
        id: 'ai_communication',
        name: 'AI通信ネットワーク',
        description: 'AI間通信・プロトコル・自動化',
        color: '#1e40af',
        edgeIds: []
      },
      {
        id: 'workflow_automation',
        name: 'ワークフロー自動化ネットワーク',
        description: 'ワークフロー・プロセス・イベント',
        color: '#1e3a8a',
        edgeIds: []
      },
      {
        id: 'organizational_governance',
        name: '組織ガバナンスネットワーク',
        description: '統治・戦略・リソース配分',
        color: '#f97316',
        edgeIds: []
      },
      {
        id: 'organizational_operations',
        name: '組織運営ネットワーク',
        description: '日常運営・プロセス・コンプライアンス',
        color: '#ea580c',
        edgeIds: []
      },
      {
        id: 'knowledge_collaboration',
        name: '知識協働ネットワーク',
        description: 'チーム連携・知識共有・顧客接点',
        color: '#dc2626',
        edgeIds: []
      },
      {
        id: 'gftd_neural',
        name: 'gftd.ai神経系ネットワーク',
        description: '中枢制御と神経伝達',
        color: '#06b6d4',
        edgeIds: []
      },
      {
        id: 'gftd_service_platform',
        name: 'gftd.aiサービスプラットフォーム',
        description: 'サービス発見・デプロイ・API',
        color: '#0891b2',
        edgeIds: []
      },
      {
        id: 'gftd_data_pipeline',
        name: 'gftd.aiデータパイプライン',
        description: 'データパイプライン・解析・監視',
        color: '#0e7490',
        edgeIds: []
      },
      {
        id: 'security_compliance',
        name: 'セキュリティ・コンプライアンス',
        description: '認証・暗号化・コンプライアンス',
        color: '#374151',
        edgeIds: []
      },
      {
        id: 'innovation_growth',
        name: 'イノベーション・成長',
        description: '学習・研究・革新・発展',
        color: '#7c3aed',
        edgeIds: []
      }
    ];
  }, []);

  // 左ペインでのアイテム選択処理
  const selectItemInLeftPanel = useCallback((type: 'node' | 'edge', id: string) => {
    if (cyRef.current) {
      // Cytoscapeでの選択
      cyRef.current.elements().unselect();
      const element = cyRef.current.getElementById(id);
      if (element.length > 0) {
        element.select();
        
        // 中央に表示
        cyRef.current.center(element);
        
        // 情報パネルに表示
        const data = element.data();
        if (type === 'node') {
          setInfoData({
            title: data.label || data.id,
            description: data.description || '',
            details: data
          });
        } else {
          setInfoData({
            title: `${data.label || 'エッジ'} (${data.source} → ${data.target})`,
            description: `タイプ: ${data.type}`,
            details: data
          });
        }
        setShowInfoPanel(true);
      }
    }
    
    // 選択状態を更新
    if (type === 'node') {
      setSelectedNodeIds([id]);
      setSelectedEdgeIds([]);
    } else {
      setSelectedEdgeIds([id]);
      setSelectedNodeIds([]);
    }
  }, []);

  // 左ペインでのアイテム編集
  const editItemInLeftPanel = useCallback((type: 'node' | 'edge', id: string) => {
    if (cyRef.current) {
      const element = cyRef.current.getElementById(id);
      if (element.length > 0) {
        const data = element.data();
        setEditingItem({ type, id, data: { ...data } });
      }
    }
  }, []);

  // 左ペインでの編集保存
  const saveItemEdit = useCallback(() => {
    if (editingItem && cyRef.current) {
      const element = cyRef.current.getElementById(editingItem.id);
      if (element.length > 0) {
        element.data(editingItem.data);
        trackUserEdit(editingItem.type, 'update', editingItem.data);
        setEditingItem(null);
      }
    }
  }, [editingItem, trackUserEdit]);

  // 基本操作
  const resetView = useCallback(() => {
    if (cyRef.current) {
      cyRef.current.zoom(1);
      cyRef.current.center();
    }
  }, []);

  const fitToView = useCallback(() => {
    if (cyRef.current) {
      cyRef.current.fit();
    }
  }, []);

  const toggleLayout = useCallback(() => {
    const layouts: Array<'dagre' | 'cose-bilkent' | 'grid' | 'circle'> = ['dagre', 'cose-bilkent', 'grid', 'circle'];
    const currentIndex = layouts.indexOf(currentLayout);
    const nextLayout = layouts[(currentIndex + 1) % layouts.length];
    setCurrentLayout(nextLayout);
    
    if (cyRef.current) {
      cyRef.current.layout({ name: nextLayout }).run();
    }
  }, [currentLayout]);

  // ノード・エッジ追加
  const addNode = useCallback(() => {
    setNodeFormData({
      id: `node_${Date.now()}`,
      label: '新しいノード',
      type: 'custom',
      category: 'user',
      level: 1,
      description: '',
      details: ''
    });
    setShowNodeModal(true);
  }, []);

  const addEdge = useCallback(() => {
    setEdgeFormData({
      id: `edge_${Date.now()}`,
      source: '',
      target: '',
      label: '新しいエッジ',
      type: 'custom'
    });
    setShowEdgeModal(true);
  }, []);

  // エクスポート・インポート
  const exportData = useCallback(() => {
    const data = {
      viewMode,
      currentData: getCurrentData(),
      userEdits,
      subNetworks,
      activeSubNetworks
    };
    
    const blob = new Blob([JSON.stringify(data, null, 2)], { type: 'application/json' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `cytoscape-data-${new Date().toISOString().slice(0, 19)}.json`;
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    URL.revokeObjectURL(url);
  }, [viewMode, getCurrentData, userEdits, subNetworks, activeSubNetworks]);

  const importData = useCallback((event: React.ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0];
    if (!file) return;
    
    const reader = new FileReader();
    reader.onload = (e) => {
      try {
        const data = JSON.parse(e.target?.result as string);
        if (data.userEdits) setUserEdits(data.userEdits);
        if (data.subNetworks) setSubNetworks(data.subNetworks);
        if (data.activeSubNetworks) setActiveSubNetworks(data.activeSubNetworks);
        if (data.viewMode) setViewMode(data.viewMode);
        
        // Cytoscapeを更新
        if (cyRef.current && data.currentData) {
          cyRef.current.elements().remove();
          cyRef.current.add([...data.currentData.nodes, ...data.currentData.edges]);
          cyRef.current.layout({ name: currentLayout }).run();
        }
      } catch (error) {
        console.error('Import error:', error);
        alert('ファイルの読み込みに失敗しました。');
      }
    };
    
    reader.readAsText(file);
  }, [currentLayout]);

  // Cytoscape初期化
  useEffect(() => {
    if (!containerRef.current) return;

    console.log('Initializing Cytoscape...');

    // データ読み込み
    Promise.all([
      loadMathData().then(setMathData),
      loadTechData().then(setTechData),
      loadOrgData().then(setOrgData),
      loadGftdData().then(setGftdData)
    ]).then(() => {
      console.log('All data loaded successfully');
    }).catch(error => {
      console.error('Error loading data:', error);
    });

    const cy = cytoscape({
      container: containerRef.current,
      elements: [],
      style: [
        {
          selector: 'node',
          style: {
            'label': 'data(label)',
            'text-valign': 'center',
            'text-halign': 'center',
            'background-color': (ele: any) => {
              const category = ele.data('category');
              const colors: { [key: string]: string } = {
                'theory': '#22c55e',
                'application': '#3b82f6',
                'tool': '#f59e0b',
                'platform': '#ef4444',
                'infrastructure': '#8b5cf6',
                'process': '#06b6d4',
                'organization': '#f97316',
                'service': '#10b981',
                'default': '#6b7280'
              };
              return colors[category] || colors.default;
            },
            'color': '#ffffff',
            'font-size': '12px',
            'font-weight': 'bold',
            'text-outline-width': '2px',
            'text-outline-color': '#000000',
            'width': (ele: any) => Math.max(30, (ele.data('label')?.length || 0) * 8),
            'height': (ele: any) => Math.max(30, (ele.data('label')?.length || 0) * 4),
            'shape': (ele: any) => {
              const type = ele.data('type');
              const shapes: { [key: string]: string } = {
                'theory': 'ellipse',
                'application': 'rectangle',
                'tool': 'triangle',
                'platform': 'diamond',
                'infrastructure': 'hexagon',
                'process': 'octagon',
                'organization': 'pentagon',
                'service': 'star',
                'default': 'ellipse'
              };
              return shapes[type] || shapes.default;
            },
            'border-width': '2px',
            'border-color': '#ffffff',
            'text-wrap': 'wrap',
            'text-max-width': '100px'
          }
        },
        {
          selector: 'edge',
          style: {
            'curve-style': 'bezier',
            'target-arrow-shape': 'triangle',
            'target-arrow-color': '#64748b',
            'line-color': '#64748b',
            'width': 2,
            'label': 'data(label)',
            'font-size': '10px',
            'color': '#e2e8f0',
            'text-outline-width': '1px',
            'text-outline-color': '#000000',
            'source-arrow-shape': 'none',
            'arrow-scale': 1.5
          }
        },
        {
          selector: ':selected',
          style: {
            'border-width': '4px',
            'border-color': '#06b6d4',
            'line-color': '#06b6d4',
            'target-arrow-color': '#06b6d4'
          }
        }
      ],
      layout: {
        name: 'cose-bilkent',
        animate: true,
        animationDuration: 1000,
        randomize: false,
        fit: true,
        padding: 50
      }
    });

    cyRef.current = cy;

    // イベントハンドラー
    cy.on('tap', 'node', function(evt) {
      const node = evt.target;
      const data = node.data();
      
      selectItemInLeftPanel('node', data.id);
    });

    cy.on('tap', 'edge', function(evt) {
      const edge = evt.target;
      const data = edge.data();
      
      if (edgeSelectionMode) {
        toggleEdgeSelection(edge.id());
        
        if (selectedEdges.includes(edge.id())) {
          edge.style({
            'line-color': '#00BCD4',
            'target-arrow-color': '#00BCD4',
            'width': 4,
            'opacity': 1
          });
        } else {
          edge.style({
            'line-color': '#64748b',
            'target-arrow-color': '#64748b',
            'width': 2,
            'opacity': 1
          });
        }
      } else {
        selectItemInLeftPanel('edge', data.id);
      }
    });

    // 背景クリック
    cy.on('tap', function(evt) {
      if (evt.target === cy) {
        setShowInfoPanel(false);
        setSelectedNodeIds([]);
        setSelectedEdgeIds([]);
      }
    });

    return () => {
      cy.destroy();
    };
  }, [loadMathData, loadTechData, loadOrgData, loadGftdData, selectItemInLeftPanel, edgeSelectionMode, toggleEdgeSelection, selectedEdges]);

  // データが変更されたときにCytoscapeを更新
  useEffect(() => {
    if (cyRef.current && (mathData || techData || orgData || gftdData)) {
      const currentData = getCurrentData();
      cyRef.current.elements().remove();
      cyRef.current.add([...currentData.nodes, ...currentData.edges]);
      cyRef.current.layout({ name: currentLayout }).run();
    }
  }, [viewMode, currentLayout, mathData, techData, orgData, gftdData, userEdits, getCurrentData]);

  // サブネットワーク適用
  useEffect(() => {
    applySubNetworkFilters();
  }, [activeSubNetworks, applySubNetworkFilters]);

  return (
    <div className="h-screen flex flex-col bg-gradient-to-br from-slate-900 via-blue-900 to-indigo-900 animate-fadeIn">
      {/* ヘッダー */}
      <div className="glass-dark border-b border-white/10 p-4">
        <div className="flex items-center justify-between">
          <div>
            <h1 className="text-3xl font-bold text-gradient-primary drop-shadow-lg">
              数学理論×IT技術 統合生態系
            </h1>
            <p className="text-sm text-gray-300 mt-1">
              {viewMode === 'math' && '数学理論の生物的表現'}
              {viewMode === 'tech' && 'IT技術の生態系'}
              {viewMode === 'org' && '会社組織の生態系'}
              {viewMode === 'gftd' && 'gftd.ai技術スタックの生命体構図'}
              {viewMode === 'integrated' && '数学理論×IT技術×組織×gftd.aiの統合生態系'}
            </p>
          </div>
          
          <div className="flex items-center gap-4">
            {/* ビューモード切り替え */}
            <div className="flex gap-1 glass rounded-full p-1">
              <button
                onClick={() => setViewMode('math')}
                className={`px-4 py-2 rounded-full text-sm transition-all duration-300 ${
                  viewMode === 'math'
                    ? 'gradient-success text-white shadow-neon-green'
                    : 'text-green-400 hover:bg-green-500/20'
                }`}
              >
                数学理論
              </button>
              <button
                onClick={() => setViewMode('tech')}
                className={`px-4 py-2 rounded-full text-sm transition-all duration-300 ${
                  viewMode === 'tech'
                    ? 'bg-blue-500 text-white shadow-neon'
                    : 'text-blue-400 hover:bg-blue-500/20'
                }`}
              >
                IT技術
              </button>
              <button
                onClick={() => setViewMode('org')}
                className={`px-4 py-2 rounded-full text-sm transition-all duration-300 ${
                  viewMode === 'org'
                    ? 'gradient-warning text-white'
                    : 'text-orange-400 hover:bg-orange-500/20'
                }`}
              >
                組織
              </button>
              <button
                onClick={() => setViewMode('gftd')}
                className={`px-4 py-2 rounded-full text-sm transition-all duration-300 ${
                  viewMode === 'gftd'
                    ? 'bg-cyan-500 text-white shadow-neon'
                    : 'text-cyan-400 hover:bg-cyan-500/20'
                }`}
              >
                gftd.ai
              </button>
              <button
                onClick={() => setViewMode('integrated')}
                className={`px-4 py-2 rounded-full text-sm transition-all duration-300 ${
                  viewMode === 'integrated'
                    ? 'bg-purple-500 text-white shadow-neon-purple'
                    : 'text-purple-400 hover:bg-purple-500/20'
                }`}
              >
                統合
              </button>
            </div>
          </div>
        </div>
      </div>

      {/* 3ペイン構成のメインコンテンツ */}
      <div className="flex flex-1 overflow-hidden">
        {/* 左ペイン: データエディタ */}
        <div className="w-1/4 glass-dark border-r border-white/10 flex flex-col animate-slideInLeft">
          {/* タブ切り替え */}
          <div className="flex border-b border-white/10">
            <button
              onClick={() => setLeftPanelTab('nodes')}
              className={`flex-1 px-4 py-3 text-sm font-medium transition-all duration-300 ${
                leftPanelTab === 'nodes' ? 'tab-active' : 'tab-inactive'
              }`}
            >
              <span className="flex items-center justify-center gap-2">
                🔸 ノード 
                <span className="badge badge-primary">
                  {cyRef.current?.nodes().length || 0}
                </span>
              </span>
            </button>
            <button
              onClick={() => setLeftPanelTab('edges')}
              className={`flex-1 px-4 py-3 text-sm font-medium transition-all duration-300 ${
                leftPanelTab === 'edges' ? 'tab-active' : 'tab-inactive'
              }`}
            >
              <span className="flex items-center justify-center gap-2">
                🔗 エッジ 
                <span className="badge badge-primary">
                  {cyRef.current?.edges().length || 0}
                </span>
              </span>
            </button>
            <button
              onClick={() => setLeftPanelTab('subnets')}
              className={`flex-1 px-4 py-3 text-sm font-medium transition-all duration-300 ${
                leftPanelTab === 'subnets' ? 'tab-active' : 'tab-inactive'
              }`}
            >
              <span className="flex items-center justify-center gap-2">
                🕸️ サブネット 
                <span className="badge badge-primary">
                  {subNetworks.length}
                </span>
              </span>
            </button>
          </div>
          
          {/* コンテンツエリア */}
          <div className="flex-1 overflow-hidden flex flex-col p-4">
            {leftPanelTab === 'nodes' && (
              <div className="flex flex-col h-full">
                <h3 className="panel-title mb-3">ノード一覧</h3>
                <div className="flex-1 overflow-y-auto space-y-2">
                  {cyRef.current?.nodes().map((node: any) => {
                    const data = node.data();
                    const isSelected = selectedNodeIds.includes(data.id);
                    return (
                      <div
                        key={data.id}
                        className={`list-item hover-lift ${
                          isSelected ? 'list-item-selected' : 'list-item-default'
                        }`}
                        onClick={() => selectItemInLeftPanel('node', data.id)}
                      >
                        <div className="flex justify-between items-start">
                          <div className="flex-1 min-w-0">
                            <div className="text-sm font-medium text-white truncate">
                              {data.label || data.id}
                            </div>
                            <div className="text-xs text-gray-400 truncate flex gap-2 mt-1">
                              <span className="badge badge-success">{data.type}</span>
                              <span className="badge badge-warning">{data.category}</span>
                            </div>
                          </div>
                          <button
                            onClick={(e) => {
                              e.stopPropagation();
                              editItemInLeftPanel('node', data.id);
                            }}
                            className="ml-2 btn-ghost px-2 py-1 text-xs"
                          >
                            編集
                          </button>
                        </div>
                      </div>
                    );
                  }) || []}
                </div>
              </div>
            )}
            
            {leftPanelTab === 'edges' && (
              <div className="flex flex-col h-full">
                <h3 className="panel-title mb-3">エッジ一覧</h3>
                <div className="flex-1 overflow-y-auto space-y-2">
                  {cyRef.current?.edges().map((edge: any) => {
                    const data = edge.data();
                    const isSelected = selectedEdgeIds.includes(data.id);
                    return (
                      <div
                        key={data.id}
                        className={`list-item hover-lift ${
                          isSelected ? 'list-item-selected' : 'list-item-default'
                        }`}
                        onClick={() => selectItemInLeftPanel('edge', data.id)}
                      >
                        <div className="flex justify-between items-start">
                          <div className="flex-1 min-w-0">
                            <div className="text-sm font-medium text-white truncate">
                              {data.source} → {data.target}
                            </div>
                            <div className="text-xs text-gray-400 truncate flex gap-2 mt-1">
                              <span className="badge badge-primary">{data.type}</span>
                              <span className="text-gray-500">{data.label || 'エッジ'}</span>
                            </div>
                          </div>
                          <button
                            onClick={(e) => {
                              e.stopPropagation();
                              editItemInLeftPanel('edge', data.id);
                            }}
                            className="ml-2 btn-ghost px-2 py-1 text-xs"
                          >
                            編集
                          </button>
                        </div>
                      </div>
                    );
                  }) || []}
                </div>
              </div>
            )}
            
            {leftPanelTab === 'subnets' && (
              <div className="flex flex-col h-full">
                <h3 className="panel-title mb-3">サブネットワーク</h3>
                <div className="flex-1 overflow-y-auto space-y-2">
                  {subNetworks.map(subnet => (
                    <div
                      key={subnet.id}
                      className="card hover-lift"
                    >
                      <div className="flex items-center justify-between mb-2">
                        <div className="flex items-center gap-2">
                          <div
                            className="w-4 h-4 rounded-full shadow-lg"
                            style={{ backgroundColor: subnet.color }}
                          />
                          <span className="text-sm font-medium text-white">
                            {subnet.name}
                          </span>
                        </div>
                        <label className="flex items-center cursor-pointer">
                          <input
                            type="checkbox"
                            checked={activeSubNetworks.includes(subnet.id)}
                            onChange={() => toggleSubNetwork(subnet.id)}
                            className="sr-only"
                            aria-label={`${subnet.name}を切り替え`}
                          />
                          <div className={`w-5 h-5 rounded border-2 transition-all duration-200 ${
                            activeSubNetworks.includes(subnet.id)
                              ? 'bg-cyan-500 border-cyan-500 shadow-neon'
                              : 'border-gray-400 hover:border-cyan-400'
                          }`}>
                            {activeSubNetworks.includes(subnet.id) && (
                              <svg className="w-4 h-4 text-white" fill="currentColor" viewBox="0 0 20 20">
                                <path fillRule="evenodd" d="M16.707 5.293a1 1 0 010 1.414l-8 8a1 1 0 01-1.414 0l-4-4a1 1 0 011.414-1.414L8 12.586l7.293-7.293a1 1 0 011.414 0z" clipRule="evenodd" />
                              </svg>
                            )}
                          </div>
                        </label>
                      </div>
                      <div className="text-xs text-gray-400 mb-2">
                        {subnet.description}
                      </div>
                      <div className="flex justify-between items-center">
                        <span className="badge badge-primary">
                          {subnet.edgeIds.length}個のエッジ
                        </span>
                      </div>
                    </div>
                  ))}
                </div>
              </div>
            )}
          </div>
        </div>
        
        {/* 中央ペイン: Cytoscapeビューアー */}
        <div className="flex-1 relative bg-gradient-to-br from-black/20 to-transparent">
          <div 
            ref={containerRef} 
            className="w-full h-full"
          />
          
          {/* オーバーレイコントロール */}
          <div className="absolute top-4 left-4 flex gap-2 animate-slideInUp">
            <button onClick={resetView} className="btn-secondary text-sm">
              🔄 リセット
            </button>
            <button onClick={fitToView} className="btn-secondary text-sm">
              🎯 全体表示
            </button>
            <button onClick={toggleLayout} className="btn-secondary text-sm">
              📐 {currentLayout}
            </button>
          </div>
        </div>
        
        {/* 右ペイン: コンテクストメニュー・情報 */}
        <div className="w-1/4 glass-dark border-l border-white/10 flex flex-col overflow-hidden animate-slideInRight">
          {/* 編集・操作コントロール */}
          <div className="p-4 border-b border-white/10">
            <h3 className="panel-title mb-4">🎮 操作・編集</h3>
            <div className="space-y-3">
              <button
                onClick={() => setEditMode(!editMode)}
                className={editMode ? 'btn-warning w-full' : 'btn-secondary w-full'}
              >
                {editMode ? '📝 編集中' : '✏️ 編集モード'}
              </button>
              
              <div className="grid grid-cols-2 gap-2">
                <button onClick={addNode} className="btn-success text-sm">
                  ➕ ノード
                </button>
                <button onClick={addEdge} className="btn-primary text-sm">
                  🔗 エッジ
                </button>
              </div>
              
              <button
                onClick={() => setEdgeSelectionMode(!edgeSelectionMode)}
                className={edgeSelectionMode ? 'btn-primary w-full' : 'btn-secondary w-full'}
              >
                {edgeSelectionMode ? '🎯 選択中' : '🔍 エッジ選択'}
              </button>
              
              <button
                onClick={() => setShowSubNetworkModal(true)}
                className="btn-secondary w-full"
                disabled={selectedEdges.length === 0}
              >
                🕸️ サブネット作成 
                {selectedEdges.length > 0 && (
                  <span className="badge badge-success ml-2">
                    {selectedEdges.length}
                  </span>
                )}
              </button>
            </div>
            
            {/* データ管理 */}
            <div className="mt-6 pt-4 border-t border-white/10">
              <h4 className="panel-subtitle mb-3">💾 データ管理</h4>
              <div className="grid grid-cols-2 gap-2">
                <button onClick={exportData} className="btn-warning text-sm">
                  📤 Export
                </button>
                <label className="cursor-pointer">
                  <input
                    type="file"
                    accept=".json"
                    onChange={importData}
                    className="hidden"
                    aria-label="JSONファイルをインポート"
                    title="JSONファイルをインポート"
                  />
                  <span className="btn-primary text-sm block text-center">
                    📥 Import
                  </span>
                </label>
              </div>
            </div>
          </div>
           
          {/* 情報パネル */}
          <div className="flex-1 overflow-hidden flex flex-col">
            {showInfoPanel && infoData ? (
              <div className="p-4 border-b border-white/10 animate-scaleIn">
                <div className="panel-header">
                  <h3 className="text-lg font-bold text-cyan-400">{infoData.title}</h3>
                  <button
                    onClick={() => setShowInfoPanel(false)}
                    className="btn-ghost text-lg px-2 py-1"
                  >
                    ✕
                  </button>
                </div>
                <p className="text-gray-300 text-sm leading-relaxed mb-3">{infoData.description}</p>
                {infoData.details && (
                  <div className="space-y-2">
                    <div className="flex gap-2">
                      <span className="badge badge-warning">カテゴリ</span>
                      <span className="text-sm text-gray-300">{infoData.details.category}</span>
                    </div>
                    <div className="flex gap-2">
                      <span className="badge badge-success">タイプ</span>
                      <span className="text-sm text-gray-300">{infoData.details.type}</span>
                    </div>
                    {infoData.details.level && (
                      <div className="flex gap-2">
                        <span className="badge badge-primary">レベル</span>
                        <span className="text-sm text-gray-300">{infoData.details.level}</span>
                      </div>
                    )}
                  </div>
                )}
              </div>
            ) : (
              <div className="p-6 text-center text-gray-500">
                <div className="text-4xl mb-3 opacity-50">🎯</div>
                <p className="text-sm">ノードまたはエッジを選択してください</p>
              </div>
            )}
            
            {/* アイテム編集フォーム */}
            {editingItem && (
              <div className="p-4 border-b border-white/10 glass animate-scaleIn">
                <h3 className="panel-title mb-4">
                  {editingItem.type === 'node' ? '🔸 ノード編集' : '🔗 エッジ編集'}
                </h3>
                <div className="space-y-4">
                  <div>
                    <label className="form-label">ラベル:</label>
                    <input
                      type="text"
                      value={editingItem.data.label || ''}
                      onChange={(e) => setEditingItem(prev => 
                        prev ? {...prev, data: {...prev.data, label: e.target.value}} : null
                      )}
                      className="form-input"
                      placeholder="ラベルを入力"
                    />
                  </div>
                  
                  {editingItem.type === 'node' && (
                    <>
                      <div>
                        <label className="form-label">説明:</label>
                        <textarea
                          value={editingItem.data.description || ''}
                          onChange={(e) => setEditingItem(prev => 
                            prev ? {...prev, data: {...prev.data, description: e.target.value}} : null
                          )}
                          className="form-textarea"
                          rows={3}
                          placeholder="説明を入力"
                        />
                      </div>
                      
                      <div>
                        <label className="form-label">カテゴリ:</label>
                        <input
                          type="text"
                          value={editingItem.data.category || ''}
                          onChange={(e) => setEditingItem(prev => 
                            prev ? {...prev, data: {...prev.data, category: e.target.value}} : null
                          )}
                          className="form-input"
                          placeholder="カテゴリを入力"
                        />
                      </div>
                    </>
                  )}
                  
                  {editingItem.type === 'edge' && (
                    <div>
                      <label className="form-label">タイプ:</label>
                      <input
                        type="text"
                        value={editingItem.data.type || ''}
                        onChange={(e) => setEditingItem(prev => 
                          prev ? {...prev, data: {...prev.data, type: e.target.value}} : null
                        )}
                        className="form-input"
                        placeholder="タイプを入力"
                      />
                    </div>
                  )}
                  
                  <div className="flex gap-2">
                    <button onClick={saveItemEdit} className="btn-success flex-1">
                      💾 保存
                    </button>
                    <button onClick={() => setEditingItem(null)} className="btn-secondary flex-1">
                      ❌ キャンセル
                    </button>
                  </div>
                </div>
              </div>
            )}
          </div>
        </div>
      </div>
       
      {/* モーダル群 */}
      {/* サブネットワーク作成モーダル */}
      {showSubNetworkModal && (
        <div className="modal-backdrop animate-fadeIn">
          <div className="modal-content animate-scaleIn">
            <div className="panel-header">
              <h3 className="panel-title">🕸️ サブネットワーク作成</h3>
              <button
                onClick={() => setShowSubNetworkModal(false)}
                className="btn-ghost text-xl px-2 py-1"
              >
                ✕
              </button>
            </div>
            
            <div className="space-y-4">
              <div>
                <label className="form-label">名前:</label>
                <input
                  type="text"
                  value={subNetworkFormData.name || ''}
                  onChange={(e) => setSubNetworkFormData(prev => ({ ...prev, name: e.target.value }))}
                  className="form-input"
                  placeholder="サブネットワークの名前を入力してください"
                />
              </div>
              
              <div>
                <label className="form-label">説明:</label>
                <textarea
                  value={subNetworkFormData.description || ''}
                  onChange={(e) => setSubNetworkFormData(prev => ({ ...prev, description: e.target.value }))}
                  className="form-textarea"
                  placeholder="サブネットワークの説明を入力してください"
                  rows={3}
                />
              </div>
              
              <div>
                <label className="form-label">色:</label>
                <div className="flex items-center gap-3">
                  <input
                    type="color"
                    value={subNetworkFormData.color || '#666666'}
                    onChange={(e) => setSubNetworkFormData(prev => ({ ...prev, color: e.target.value }))}
                    className="w-12 h-10 border border-white/30 rounded-lg cursor-pointer"
                    title="サブネットワークの色を選択"
                    aria-label="サブネットワークの色を選択"
                  />
                  <span className="text-gray-300 text-sm">エッジの色を選択</span>
                </div>
              </div>
              
              <div className="flex gap-3 justify-end pt-4">
                <button
                  onClick={() => setShowSubNetworkModal(false)}
                  className="btn-secondary"
                >
                  キャンセル
                </button>
                <button onClick={saveSubNetwork} className="btn-primary">
                  🚀 作成
                </button>
              </div>
            </div>
          </div>
        </div>
      )}

      {/* ノード追加モーダル */}
      {showNodeModal && (
        <div className="modal-backdrop animate-fadeIn">
          <div className="modal-content animate-scaleIn">
            <div className="panel-header">
              <h3 className="panel-title">🔸 ノード追加</h3>
              <button
                onClick={() => setShowNodeModal(false)}
                className="btn-ghost text-xl px-2 py-1"
              >
                ✕
              </button>
            </div>
            
            <div className="space-y-4">
              <div>
                <label className="form-label">ラベル:</label>
                <input
                  type="text"
                  value={nodeFormData.label || ''}
                  onChange={(e) => setNodeFormData(prev => ({ ...prev, label: e.target.value }))}
                  className="form-input"
                  placeholder="ノードのラベルを入力してください"
                />
              </div>
              
              <div>
                <label className="form-label">説明:</label>
                <textarea
                  value={nodeFormData.description || ''}
                  onChange={(e) => setNodeFormData(prev => ({ ...prev, description: e.target.value }))}
                  className="form-textarea"
                  placeholder="ノードの説明を入力してください"
                  rows={3}
                />
              </div>
              
              <div className="grid grid-cols-2 gap-4">
                <div>
                  <label className="form-label">タイプ:</label>
                  <input
                    type="text"
                    value={nodeFormData.type || ''}
                    onChange={(e) => setNodeFormData(prev => ({ ...prev, type: e.target.value }))}
                    className="form-input"
                    placeholder="theory, application等"
                  />
                </div>
                
                <div>
                  <label className="form-label">カテゴリ:</label>
                  <input
                    type="text"
                    value={nodeFormData.category || ''}
                    onChange={(e) => setNodeFormData(prev => ({ ...prev, category: e.target.value }))}
                    className="form-input"
                    placeholder="math, tech等"
                  />
                </div>
              </div>
              
              <div className="flex gap-3 justify-end pt-4">
                <button
                  onClick={() => setShowNodeModal(false)}
                  className="btn-secondary"
                >
                  キャンセル
                </button>
                <button
                  onClick={() => {
                    if (nodeFormData.label && cyRef.current) {
                      const newNode = {
                        ...nodeFormData,
                        id: nodeFormData.id || `node_${Date.now()}`
                      };
                      cyRef.current.add({ data: newNode });
                      trackUserEdit('node', 'add', newNode);
                      setShowNodeModal(false);
                      setNodeFormData({});
                    }
                  }}
                  className="btn-success"
                >
                  ➕ 追加
                </button>
              </div>
            </div>
          </div>
        </div>
      )}

      {/* エッジ追加モーダル */}
      {showEdgeModal && (
        <div className="modal-backdrop animate-fadeIn">
          <div className="modal-content animate-scaleIn">
            <div className="panel-header">
              <h3 className="panel-title">🔗 エッジ追加</h3>
              <button
                onClick={() => setShowEdgeModal(false)}
                className="btn-ghost text-xl px-2 py-1"
              >
                ✕
              </button>
            </div>
            
            <div className="space-y-4">
              <div className="grid grid-cols-2 gap-4">
                <div>
                  <label className="form-label">ソース:</label>
                  <input
                    type="text"
                    value={edgeFormData.source || ''}
                    onChange={(e) => setEdgeFormData(prev => ({ ...prev, source: e.target.value }))}
                    className="form-input"
                    placeholder="ソースノードID"
                  />
                </div>
                
                <div>
                  <label className="form-label">ターゲット:</label>
                  <input
                    type="text"
                    value={edgeFormData.target || ''}
                    onChange={(e) => setEdgeFormData(prev => ({ ...prev, target: e.target.value }))}
                    className="form-input"
                    placeholder="ターゲットノードID"
                  />
                </div>
              </div>
              
              <div>
                <label className="form-label">タイプ:</label>
                <input
                  type="text"
                  value={edgeFormData.type || ''}
                  onChange={(e) => setEdgeFormData(prev => ({ ...prev, type: e.target.value }))}
                  className="form-input"
                  placeholder="関係のタイプを入力してください"
                />
              </div>
              
              <div>
                <label className="form-label">ラベル:</label>
                <input
                  type="text"
                  value={edgeFormData.label || ''}
                  onChange={(e) => setEdgeFormData(prev => ({ ...prev, label: e.target.value }))}
                  className="form-input"
                  placeholder="エッジのラベルを入力してください"
                />
              </div>
              
              <div className="flex gap-3 justify-end pt-4">
                <button
                  onClick={() => setShowEdgeModal(false)}
                  className="btn-secondary"
                >
                  キャンセル
                </button>
                <button
                  onClick={() => {
                    if (edgeFormData.source && edgeFormData.target && cyRef.current) {
                      const newEdge = {
                        ...edgeFormData,
                        id: edgeFormData.id || `edge_${Date.now()}`
                      };
                      cyRef.current.add({ data: newEdge });
                      trackUserEdit('edge', 'add', newEdge);
                      setShowEdgeModal(false);
                      setEdgeFormData({});
                    }
                  }}
                  className="btn-primary"
                >
                  🔗 追加
                </button>
              </div>
            </div>
          </div>
        </div>
      )}

      {/* Mac Trackpad操作ガイド */}
      {showTrackpadHelp && (
        <TrackpadControls onClose={() => setShowTrackpadHelp(false)} />
      )}
    </div>
  );
} 