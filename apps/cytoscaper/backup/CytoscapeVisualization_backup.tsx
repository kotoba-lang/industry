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
  source: string;
  target: string;
  type: string;
  label: string;
  category?: string;
}

/**
 * サブネットワーク情報のインターフェース
 */
interface SubNetwork {
  id: string;
  name: string;
  description: string;
  edgeTypes: string[];
  color: string;
  visible: boolean;
  created: string;
}

export default function CytoscapeVisualization() {
  const containerRef = useRef<HTMLDivElement>(null);
  const cyRef = useRef<Core | null>(null);
  const [editMode, setEditMode] = useState(false);
  const [selectedElement, setSelectedElement] = useState<any>(null);
  const [currentLayout, setCurrentLayout] = useState('cose');
  const [showInfoPanel, setShowInfoPanel] = useState(false);
  const [infoData, setInfoData] = useState<any>(null);
  
  // 3ペイン構成用のUI状態
  const [leftPanelTab, setLeftPanelTab] = useState<'nodes' | 'edges' | 'subnets'>('nodes');
  const [selectedNodeIds, setSelectedNodeIds] = useState<string[]>([]);
  const [selectedEdgeIds, setSelectedEdgeIds] = useState<string[]>([]);
  const [editingItem, setEditingItem] = useState<{type: 'node' | 'edge', id: string, data: any} | null>(null);
  const [showNodeModal, setShowNodeModal] = useState(false);
  const [showEdgeModal, setShowEdgeModal] = useState(false);
  const [nodeFormData, setNodeFormData] = useState<Partial<NodeData>>({});
  const [edgeFormData, setEdgeFormData] = useState<Partial<EdgeData>>({});
  const [availableNodes, setAvailableNodes] = useState<Array<{id: string, label: string}>>([]);
  const [viewMode, setViewMode] = useState<'math' | 'tech' | 'org' | 'gftd' | 'integrated'>('integrated');
  const [mathData, setMathData] = useState<TheoryData | null>(null);
  const [techData, setTechData] = useState<TheoryData | null>(null);
  const [orgData, setOrgData] = useState<TheoryData | null>(null);
  const [gftdData, setGftdData] = useState<TheoryData | null>(null);
  const [showTrackpadHelp, setShowTrackpadHelp] = useState(true);
  const [userEdits, setUserEdits] = useState<{nodes: any[], edges: any[]}>({nodes: [], edges: []});
  const [hasUnsavedChanges, setHasUnsavedChanges] = useState(false);

  // サブネットワーク関連の状態
  const [subNetworks, setSubNetworks] = useState<SubNetwork[]>([]);
  const [selectedEdges, setSelectedEdges] = useState<string[]>([]);
  const [showSubNetworkPanel, setShowSubNetworkPanel] = useState(false);
  const [showSubNetworkModal, setShowSubNetworkModal] = useState(false);
  const [subNetworkFormData, setSubNetworkFormData] = useState<Partial<SubNetwork>>({});
  const [activeSubNetworks, setActiveSubNetworks] = useState<string[]>([]);
  const [edgeSelectionMode, setEdgeSelectionMode] = useState(false);

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

  const loadOrgData = useCallback(async (): Promise<TheoryData> => {
    try {
      console.log('Loading organization data...');
      const response = await fetch('/data/organization-ecosystem-data.json');
      
      if (!response.ok) {
        throw new Error(`HTTP error! status: ${response.status}`);
      }
      
      const data = await response.json();
      console.log('Organization data loaded successfully');
      console.log('Org Nodes:', data.nodes.length);
      console.log('Org Edges:', data.edges.length);
      console.log('Math Connections:', data.mathConnections?.length || 0);
      
      return data;
    } catch (error) {
      console.error('Error loading organization data:', error);
      throw error;
    }
  }, []);

  const loadGftdData = useCallback(async (): Promise<TheoryData> => {
    try {
      console.log('Loading gftd.ai ecosystem data...');
      const response = await fetch('/data/gftd-ai-ecosystem-data.json');
      
      if (!response.ok) {
        throw new Error(`HTTP error! status: ${response.status}`);
      }
      
      const data = await response.json();
      console.log('gftd.ai ecosystem data loaded successfully');
      console.log('GFTD Nodes:', data.nodes.length);
      console.log('GFTD Edges:', data.edges.length);
      console.log('Math Connections:', data.mathConnections?.length || 0);
      
      return data;
    } catch (error) {
      console.error('Error loading gftd.ai ecosystem data:', error);
      throw error;
    }
  }, []);

  const getIntegratedData = useCallback((): TheoryData => {
    const allNodes = [];
    const allEdges = [];
    const allMathConnections = [];

    if (mathData) {
      allNodes.push(...mathData.nodes);
      allEdges.push(...mathData.edges);
    }
    
    if (techData) {
      allNodes.push(...techData.nodes);
      allEdges.push(...techData.edges);
      if (techData.mathConnections) {
        allMathConnections.push(...techData.mathConnections);
        allEdges.push(...techData.mathConnections);
      }
    }
    
    if (orgData) {
      allNodes.push(...orgData.nodes);
      allEdges.push(...orgData.edges);
      if (orgData.mathConnections) {
        allMathConnections.push(...orgData.mathConnections);
        allEdges.push(...orgData.mathConnections);
      }
    }

    if (gftdData) {
      allNodes.push(...gftdData.nodes);
      allEdges.push(...gftdData.edges);
      if (gftdData.mathConnections) {
        allMathConnections.push(...gftdData.mathConnections);
        allEdges.push(...gftdData.mathConnections);
      }
    }

    // ユーザー編集データを追加
    allNodes.push(...userEdits.nodes);
    allEdges.push(...userEdits.edges);

    return {
      nodes: allNodes,
      edges: allEdges,
      mathConnections: allMathConnections
    };
  }, [mathData, techData, orgData, gftdData, userEdits]);

  const getCurrentData = useCallback((): TheoryData => {
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
  }, [viewMode, mathData, techData, orgData, gftdData, getIntegratedData]);

  // ローカルストレージ機能
  const saveToLocalStorage = useCallback(() => {
    try {
      const saveData = {
        userEdits,
        timestamp: new Date().toISOString(),
        version: '1.0'
      };
      localStorage.setItem('cytoscapeUserEdits', JSON.stringify(saveData));
      setHasUnsavedChanges(false);
      console.log('User edits saved to localStorage');
      return true;
    } catch (error) {
      console.error('Error saving to localStorage:', error);
      return false;
    }
  }, [userEdits]);

  const loadFromLocalStorage = useCallback(() => {
    try {
      const saved = localStorage.getItem('cytoscapeUserEdits');
      if (saved) {
        const saveData = JSON.parse(saved);
        setUserEdits(saveData.userEdits || {nodes: [], edges: []});
        setHasUnsavedChanges(false);
        console.log('User edits loaded from localStorage');
        return true;
      }
      return false;
    } catch (error) {
      console.error('Error loading from localStorage:', error);
      return false;
    }
  }, []);

  const clearLocalStorage = useCallback(() => {
    try {
      localStorage.removeItem('cytoscapeUserEdits');
      setUserEdits({nodes: [], edges: []});
      setHasUnsavedChanges(false);
      console.log('LocalStorage cleared');
      return true;
    } catch (error) {
      console.error('Error clearing localStorage:', error);
      return false;
    }
  }, []);

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
          const index = prev.edges.findIndex(e => e.data.source === data.source && e.data.target === data.target);
          if (index >= 0) {
            newUserEdits.edges = [...prev.edges];
            newUserEdits.edges[index] = { data };
          } else {
            newUserEdits.edges = [...prev.edges, { data }];
          }
        } else if (action === 'delete') {
          newUserEdits.edges = prev.edges.filter(e => !(e.data.source === data.source && e.data.target === data.target));
        }
      }
      
      return newUserEdits;
    });
    setHasUnsavedChanges(true);
  }, []);

  // サブネットワーク管理機能
  /**
   * 利用可能なエッジタイプを取得
   */
  const getAvailableEdgeTypes = useCallback((): string[] => {
    if (!cyRef.current) return [];
    
    const edgeTypes = new Set<string>();
    cyRef.current.edges().forEach(edge => {
      const type = edge.data('type');
      if (type) edgeTypes.add(type);
    });
    
    return Array.from(edgeTypes).sort();
  }, []);

  /**
   * エッジ選択状態を切り替え
   */
  const toggleEdgeSelection = useCallback((edgeId: string) => {
    setSelectedEdges(prev => {
      if (prev.includes(edgeId)) {
        return prev.filter(id => id !== edgeId);
      } else {
        return [...prev, edgeId];
      }
    });
  }, []);

  /**
   * エッジタイプ別にエッジを選択
   */
  const selectEdgesByType = useCallback((edgeType: string) => {
    if (!cyRef.current) return;
    
    const edgesOfType = cyRef.current.edges(`[type="${edgeType}"]`);
    const edgeIds = edgesOfType.map(edge => edge.id());
    
    setSelectedEdges(prev => {
      const newSelection = new Set(prev);
      edgeIds.forEach(id => newSelection.add(id));
      return Array.from(newSelection);
    });
  }, []);

  /**
   * すべてのエッジ選択をクリア
   */
  const clearEdgeSelection = useCallback(() => {
    setSelectedEdges([]);
  }, []);

  /**
   * サブネットワークを作成
   */
  const createSubNetwork = useCallback(() => {
    if (selectedEdges.length === 0) {
      alert('エッジを選択してからサブネットワークを作成してください。');
      return;
    }

    // 選択されたエッジのタイプを取得
    const edgeTypes = new Set<string>();
    if (cyRef.current) {
      selectedEdges.forEach(edgeId => {
        const edge = cyRef.current!.getElementById(edgeId);
        const type = edge.data('type');
        if (type) edgeTypes.add(type);
      });
    }

    setSubNetworkFormData({
      id: `subnet_${Date.now()}`,
      name: '',
      description: '',
      edgeTypes: Array.from(edgeTypes),
      color: '#' + Math.floor(Math.random()*16777215).toString(16),
      visible: true,
      created: new Date().toISOString()
    });
    setShowSubNetworkModal(true);
  }, [selectedEdges]);

  /**
   * サブネットワークを保存
   */
  const saveSubNetwork = useCallback(() => {
    if (!subNetworkFormData.name || !subNetworkFormData.id) {
      alert('サブネットワーク名を入力してください。');
      return;
    }

    const newSubNetwork: SubNetwork = {
      id: subNetworkFormData.id!,
      name: subNetworkFormData.name,
      description: subNetworkFormData.description || '',
      edgeTypes: subNetworkFormData.edgeTypes || [],
      color: subNetworkFormData.color || '#666666',
      visible: true,
      created: subNetworkFormData.created || new Date().toISOString()
    };

    setSubNetworks(prev => {
      const existing = prev.find(sn => sn.id === newSubNetwork.id);
      if (existing) {
        return prev.map(sn => sn.id === newSubNetwork.id ? newSubNetwork : sn);
      } else {
        return [...prev, newSubNetwork];
      }
    });

    setActiveSubNetworks(prev => [...prev, newSubNetwork.id]);
    setShowSubNetworkModal(false);
    setSelectedEdges([]);
    applySubNetworkFilters();
  }, [subNetworkFormData]);

  /**
   * サブネットワークの表示/非表示を切り替え
   */
  const toggleSubNetworkVisibility = useCallback((subNetworkId: string) => {
    setActiveSubNetworks(prev => {
      if (prev.includes(subNetworkId)) {
        return prev.filter(id => id !== subNetworkId);
      } else {
        return [...prev, subNetworkId];
      }
    });
  }, []);

  /**
   * サブネットワークフィルターを適用
   */
  const applySubNetworkFilters = useCallback(() => {
    if (!cyRef.current) return;

    const cy = cyRef.current;
    
    if (activeSubNetworks.length === 0) {
      // すべて表示
      cy.elements().style('display', 'element');
      cy.elements().removeClass('subnet-filtered');
      return;
    }

    // アクティブなサブネットワークのエッジタイプを取得
    const activeEdgeTypes = new Set<string>();
    activeSubNetworks.forEach(subNetId => {
      const subnet = subNetworks.find(sn => sn.id === subNetId);
      if (subnet) {
        subnet.edgeTypes.forEach(type => activeEdgeTypes.add(type));
      }
    });

    // エッジのフィルタリング
    cy.edges().forEach(edge => {
      const edgeType = edge.data('type');
      if (activeEdgeTypes.has(edgeType)) {
        edge.style('display', 'element');
        edge.removeClass('subnet-filtered');
        
        // サブネットワークの色を適用
        const subnet = subNetworks.find(sn => 
          activeSubNetworks.includes(sn.id) && sn.edgeTypes.includes(edgeType)
        );
        if (subnet) {
          edge.style({
            'line-color': subnet.color,
            'target-arrow-color': subnet.color
          });
        }
      } else {
        edge.style('display', 'none');
        edge.addClass('subnet-filtered');
      }
    });

    // 関連するノードのフィルタリング
    const visibleEdges = cy.edges(':visible');
    const connectedNodeIds = new Set<string>();
    
    visibleEdges.forEach(edge => {
      connectedNodeIds.add(edge.source().id());
      connectedNodeIds.add(edge.target().id());
    });

    cy.nodes().forEach(node => {
      if (connectedNodeIds.has(node.id())) {
        node.style('display', 'element');
        node.removeClass('subnet-filtered');
      } else {
        node.style('display', 'none');
        node.addClass('subnet-filtered');
      }
    });

  }, [activeSubNetworks, subNetworks]);

  /**
   * すべてのサブネットワークをクリア
   */
  const clearAllSubNetworks = useCallback(() => {
    setActiveSubNetworks([]);
    if (cyRef.current) {
      cyRef.current.elements().style('display', 'element');
      cyRef.current.elements().removeClass('subnet-filtered');
      // エッジの色をデフォルトに戻す
      cyRef.current.edges().style({
        'line-color': '#607D8B',
        'target-arrow-color': '#607D8B'
      });
    }
  }, []);

  /**
   * サブネットワークを削除
   */
  const deleteSubNetwork = useCallback((subNetworkId: string) => {
    if (confirm('このサブネットワークを削除しますか？')) {
      setSubNetworks(prev => prev.filter(sn => sn.id !== subNetworkId));
      setActiveSubNetworks(prev => prev.filter(id => id !== subNetworkId));
      applySubNetworkFilters();
    }
  }, [applySubNetworkFilters]);

  /**
   * プリセットサブネットワークの定義
   */
  const getPresetSubNetworks = useCallback((): SubNetwork[] => {
    return [
      {
        id: 'math_foundation',
        name: '数学基盤ネットワーク',
        description: '数学理論の基礎的な栄養供給と成長フローを表示',
        edgeTypes: ['nutrient_flow', 'foundation_to_pure', 'growth_flow', 'deep_root_support'],
        color: '#2E7D32',
        visible: true,
        created: new Date().toISOString()
      },
      {
        id: 'math_development',
        name: '数学発展ネットワーク',
        description: '数学理論の発展と応用への分岐を表示',
        edgeTypes: ['sub_branch_growth', 'application_flow', 'specialization', 'synthesis_flow'],
        color: '#FF9800',
        visible: true,
        created: new Date().toISOString()
      },
      {
        id: 'math_collaboration',
        name: '数学協働ネットワーク',
        description: '数学分野間の交流と学際的な結実を表示',
        edgeTypes: ['cross_pollination', 'fruition', 'seed_formation', 'direct_application'],
        color: '#9C27B0',
        visible: true,
        created: new Date().toISOString()
      },
      {
        id: 'tech_infrastructure',
        name: 'IT基盤ネットワーク',
        description: 'IT技術の基盤となるインフラと支援システムを表示',
        edgeTypes: ['foundation_support', 'nutrient_supply', 'platform_support', 'deployment_platform'],
        color: '#1976D2',
        visible: true,
        created: new Date().toISOString()
      },
      {
        id: 'tech_development',
        name: 'IT開発ネットワーク',
        description: '開発・デプロイ・スケーリングのフローを表示',
        edgeTypes: ['framework_growth', 'evolution_flow', 'scaling_need', 'deployment_evolution'],
        color: '#00ACC1',
        visible: true,
        created: new Date().toISOString()
      },
      {
        id: 'data_integration',
        name: 'データ統合ネットワーク',
        description: 'データフロー・API・統合関連の接続を表示',
        edgeTypes: ['data_flow', 'data_source', 'api_layer', 'rpc_layer', 'integration_method'],
        color: '#4CAF50',
        visible: true,
        created: new Date().toISOString()
      },
      {
        id: 'ai_communication',
        name: 'AI通信ネットワーク',
        description: 'AI間通信・プロトコル・自動化の連携を表示',
        edgeTypes: ['ai_communication', 'protocol_implementation', 'automation_orchestration', 'message_passing'],
        color: '#E91E63',
        visible: true,
        created: new Date().toISOString()
      },
      {
        id: 'workflow_automation',
        name: 'ワークフロー自動化ネットワーク',
        description: 'ワークフロー・プロセス・イベント処理を表示',
        edgeTypes: ['workflow_trigger', 'process_recording', 'event_storage', 'stream_processing'],
        color: '#FF5722',
        visible: true,
        created: new Date().toISOString()
      },
      {
        id: 'organizational_governance',
        name: '組織ガバナンスネットワーク',
        description: '組織の統治・戦略・リソース配分を表示',
        edgeTypes: ['energy_governance', 'strategic_direction', 'execution_flow', 'resource_allocation'],
        color: '#FFD700',
        visible: true,
        created: new Date().toISOString()
      },
      {
        id: 'organizational_operations',
        name: '組織運営ネットワーク',
        description: '日常運営・プロセス・コンプライアンスを表示',
        edgeTypes: ['financial_control', 'process_optimization', 'compliance_oversight', 'team_formation'],
        color: '#9E9E9E',
        visible: true,
        created: new Date().toISOString()
      },
      {
        id: 'knowledge_collaboration',
        name: '知識協働ネットワーク',
        description: 'チーム連携・知識共有・顧客とのやり取りを表示',
        edgeTypes: ['collaboration', 'talent_supply', 'knowledge_sharing', 'customer_interaction', 'research_collaboration'],
        color: '#8BC34A',
        visible: true,
        created: new Date().toISOString()
      },
      {
        id: 'gftd_neural_system',
        name: 'gftd.ai神経系ネットワーク',
        description: 'gftd.aiの中枢制御と神経伝達システムを表示',
        edgeTypes: ['neural_control', 'neural_coordination', 'endocrine_regulation', 'hormone_secretion'],
        color: '#3F51B5',
        visible: true,
        created: new Date().toISOString()
      },
      {
        id: 'gftd_service_platform',
        name: 'gftd.aiサービスプラットフォーム',
        description: 'サービス発見・デプロイ・API統合を表示',
        edgeTypes: ['service_discovery', 'api_integration', 'client_integration', 'optimized_deployment'],
        color: '#673AB7',
        visible: true,
        created: new Date().toISOString()
      },
      {
        id: 'gftd_data_pipeline',
        name: 'gftd.aiデータパイプライン',
        description: 'データパイプライン・解析・監視システムを表示',
        edgeTypes: ['data_pipeline', 'real_time_analytics', 'infrastructure_monitoring', 'performance_monitoring'],
        color: '#795548',
        visible: true,
        created: new Date().toISOString()
      },
      {
        id: 'security_compliance',
        name: 'セキュリティ・コンプライアンス',
        description: '認証・暗号化・監査証跡を表示',
        edgeTypes: ['authentication', 'authorization', 'data_encryption', 'audit_trail', 'security_integration'],
        color: '#F44336',
        visible: true,
        created: new Date().toISOString()
      },
      {
        id: 'devops_cicd',
        name: 'DevOps・CI/CDネットワーク',
        description: 'DevOpsパイプライン・コード管理・デプロイを表示',
        edgeTypes: ['devops_pipeline', 'ci_cd_integration', 'code_synchronization', 'source_management', 'deployment_trigger'],
        color: '#607D8B',
        visible: true,
        created: new Date().toISOString()
      }
    ];
  }, []);

  /**
   * プリセットサブネットワークを適用
   */
  const applyPresetSubNetworks = useCallback(() => {
    const presets = getPresetSubNetworks();
    setSubNetworks(presets);
    // デフォルトでは何も表示しない（ユーザーが選択する）
    setActiveSubNetworks([]);
  }, [getPresetSubNetworks]);

  /**
   * おすすめサブネットワーク組み合わせを適用
   */
  const applyRecommendedView = useCallback((viewType: 'foundation' | 'development' | 'integration' | 'governance') => {
    const recommendations = {
      foundation: ['math_foundation', 'tech_infrastructure', 'organizational_governance'],
      development: ['math_development', 'tech_development', 'ai_communication'],
      integration: ['data_integration', 'workflow_automation', 'gftd_service_platform'],
      governance: ['organizational_governance', 'organizational_operations', 'security_compliance']
    };
    
    setActiveSubNetworks(recommendations[viewType]);
  }, []);

  /**
   * 左ペインでのアイテム選択処理
   */
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

  /**
   * 左ペインでのアイテム編集
   */
  const editItemInLeftPanel = useCallback((type: 'node' | 'edge', id: string) => {
    if (cyRef.current) {
      const element = cyRef.current.getElementById(id);
      if (element.length > 0) {
        const data = element.data();
        setEditingItem({ type, id, data: { ...data } });
      }
    }
  }, []);

  /**
   * 左ペインでの編集保存
   */
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

  // Mac trackpad用ジェスチャーサポート
  const setupTrackpadGestures = useCallback((cy: Core) => {
    const container = cy.container();
    if (!container) return;

    // デバウンス用の変数
    let isAnimating = false;
    let wheelTimeout: NodeJS.Timeout | null = null;
    let lastWheelTime = 0;
    let accumulatedDelta = { x: 0, y: 0 };
    
    // スムーズなパン・ズーム処理
    const smoothUpdate = () => {
      if (isAnimating) return;
      isAnimating = true;
      
      requestAnimationFrame(() => {
        isAnimating = false;
      });
    };
    
    container.addEventListener('wheel', (e: WheelEvent) => {
      e.preventDefault();
      e.stopPropagation();
      
      const now = Date.now();
      const timeDelta = now - lastWheelTime;
      lastWheelTime = now;
      
      // 高頻度イベントの間引き
      if (timeDelta < 16 && isAnimating) {
        return;
      }
      
      const zoom = cy.zoom();
      const deltaY = e.deltaY;
      const deltaX = e.deltaX;
      
      // trackpadスワイプ方向を逆に設定
      const isMac = navigator.platform.toUpperCase().indexOf('MAC') >= 0;
      const scrollDirectionY = isMac ? deltaY : -deltaY;
      const scrollDirectionX = isMac ? deltaX : -deltaX;
      
      // ピンチズームの検出（ctrlキーまたはmetaキーが押されている場合）
      if (e.ctrlKey || e.metaKey) {
        const zoomFactor = scrollDirectionY > 0 ? 0.95 : 1.05;
        const newZoom = Math.max(0.1, Math.min(3, zoom * zoomFactor));
        
        // アニメーションなしで即座にズーム
        cy.zoom({
          level: newZoom,
          position: { x: e.clientX, y: e.clientY }
        });
      } else {
        // パンスクロール - アニメーションなしで即座に移動
        const pan = cy.pan();
        const panSpeed = 1.5;
        
        // デルタを蓄積して滑らかに
        accumulatedDelta.x += scrollDirectionX * panSpeed;
        accumulatedDelta.y += scrollDirectionY * panSpeed;
        
        // 小さな移動は無視してちらつきを防止
        if (Math.abs(accumulatedDelta.x) > 2 || Math.abs(accumulatedDelta.y) > 2) {
          cy.pan({
            x: pan.x - accumulatedDelta.x,
            y: pan.y - accumulatedDelta.y
          });
          accumulatedDelta = { x: 0, y: 0 };
        }
      }
      
      // デバウンス処理
      if (wheelTimeout) clearTimeout(wheelTimeout);
      wheelTimeout = setTimeout(() => {
        accumulatedDelta = { x: 0, y: 0 };
      }, 100);
      
      smoothUpdate();
    }, { passive: false });

    // タッチジェスチャー（ピンチ、パン）
    const hammer = new Hammer.Manager(container, {
      recognizers: [
        [Hammer.Pinch, { enable: true }],
        [Hammer.Pan, { direction: Hammer.DIRECTION_ALL, threshold: 5 }],
        [Hammer.Tap, { taps: 1 }],
        [Hammer.Tap, { taps: 2 }, ['tap']]
      ]
    });
    
    let initialZoom = 1;
    let initialPan = { x: 0, y: 0 };
    let gestureActive = false;
    
    // ピンチズーム - 最適化
    hammer.on('pinchstart', (e) => {
      gestureActive = true;
      initialZoom = cy.zoom();
      initialPan = cy.pan();
      e.preventDefault();
    });
    
    hammer.on('pinchmove', (e) => {
      if (!gestureActive) return;
      e.preventDefault();
      
      const newZoom = Math.max(0.1, Math.min(3, initialZoom * e.scale));
      cy.zoom({
        level: newZoom,
        position: { x: e.center.x, y: e.center.y }
      });
    });
    
    hammer.on('pinchend', () => {
      gestureActive = false;
    });
    
    // パンジェスチャー - 最適化
    hammer.on('panstart', (e) => {
      if (gestureActive) return;
      gestureActive = true;
      initialPan = cy.pan();
      e.preventDefault();
    });
    
    hammer.on('panmove', (e) => {
      if (!gestureActive) return;
      e.preventDefault();
      
      // スムーズなパン処理
      cy.pan({
        x: initialPan.x + e.deltaX,
        y: initialPan.y + e.deltaY
      });
    });
    
    hammer.on('panend', () => {
      gestureActive = false;
    });
    
    // ダブルタップでフィット - アニメーション削除
    hammer.on('doubletap', (e) => {
      e.preventDefault();
      cy.fit(cy.elements(), 50);
    });

    // スムーズなアニメーション用のeasingオプション
    cy.style()
      .selector('node')
      .style({
        'transition-property': 'background-color, border-color, opacity',
        'transition-duration': 300
      })
      .selector('edge')
      .style({
        'transition-property': 'line-color, opacity',
        'transition-duration': 300
      });
  }, []);

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
      
      // エッジ選択モードの場合
      if (edgeSelectionMode) {
        toggleEdgeSelection(edge.id());
        
        // 選択状態の視覚的フィードバック
        if (selectedEdges.includes(edge.id())) {
          edge.style({
            'line-color': '#00BCD4',
            'target-arrow-color': '#00BCD4',
            'width': 4,
            'opacity': 1
          });
        } else {
          // デフォルトスタイルに戻す
          edge.style({
            'line-color': '#607D8B',
            'target-arrow-color': '#607D8B',
            'width': 1.5,
            'opacity': 0.7
          });
        }
        return;
      }
      
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

    // Mac trackpad用ジェスチャーサポート
    setupTrackpadGestures(cy);
  }, [editMode, edgeSelectionMode, selectedEdges, toggleEdgeSelection]);

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
        // 全データを並行して読み込み
        const [mathTheoryData, techTheoryData, organizationData, gftdTheoryData] = await Promise.all([
          loadMathData(),
          loadTechData(),
          loadOrgData(),
          loadGftdData()
        ]);
        
        setMathData(mathTheoryData);
        setTechData(techTheoryData);
        setOrgData(organizationData);
        setGftdData(gftdTheoryData);
        
        // ローカルストレージからユーザー編集データを読み込み
        loadFromLocalStorage();
        
        // プリセットサブネットワークを初期化
        const presets = getPresetSubNetworks();
        setSubNetworks(presets);
        
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

            // 会社組織系ノードスタイル
            {
              selector: 'node[type="energy_source"]',
              style: {
                'background-color': '#FFD700',
                'shape': 'star',
                'width': 110,
                'height': 110,
                'border-color': '#FFF8DC',
                'color': '#8B4513',
                'text-shadow': '0 0 4px rgba(255, 255, 255, 0.9)',
                'box-shadow': '0 0 35px rgba(255, 215, 0, 1.0)',
                'font-size': '12px'
              }
            },
            {
              selector: 'node[type="protective_layer"]',
              style: {
                'background-color': '#4169E1',
                'shape': 'round-octagon',
                'width': 100,
                'height': 80,
                'border-color': '#87CEEB',
                'box-shadow': '0 0 25px rgba(65, 105, 225, 0.8)',
                'font-size': '11px'
              }
            },
            {
              selector: 'node[type="canopy_layer"]',
              style: {
                'background-color': '#228B22',
                'shape': 'round-hexagon',
                'width': 95,
                'height': 95,
                'border-color': '#90EE90',
                'box-shadow': '0 0 25px rgba(34, 139, 34, 0.8)',
                'font-size': '11px'
              }
            },
            {
              selector: 'node[type="management_trunk"]',
              style: {
                'background-color': '#8B4513',
                'shape': 'round-rectangle',
                'width': 90,
                'height': 75,
                'border-color': '#DEB887',
                'box-shadow': '0 0 22px rgba(139, 69, 19, 0.8)',
                'font-size': '10px'
              }
            },
            {
              selector: 'node[type="photosynthetic_organ"]',
              style: {
                'background-color': '#32CD32',
                'shape': 'round-diamond',
                'width': 85,
                'height': 85,
                'border-color': '#98FB98',
                'box-shadow': '0 0 20px rgba(50, 205, 50, 0.8)',
                'font-size': '10px'
              }
            },
            {
              selector: 'node[type="reproductive_organ"]',
              style: {
                'background-color': '#FF69B4',
                'shape': 'round-pentagon',
                'width': 80,
                'height': 80,
                'border-color': '#FFB6C1',
                'box-shadow': '0 0 20px rgba(255, 105, 180, 0.8)',
                'font-size': '10px'
              }
            },
            {
              selector: 'node[type="seed_dispersal_organ"]',
              style: {
                'background-color': '#FF8C00',
                'shape': 'round-triangle',
                'width': 75,
                'height': 75,
                'border-color': '#FFE4B5',
                'box-shadow': '0 0 18px rgba(255, 140, 0, 0.8)',
                'font-size': '9px'
              }
            },
            {
              selector: 'node[type="pollination_organ"]',
              style: {
                'background-color': '#DA70D6',
                'shape': 'round-diamond',
                'width': 80,
                'height': 65,
                'border-color': '#DDA0DD',
                'box-shadow': '0 0 20px rgba(218, 112, 214, 0.8)',
                'font-size': '9px'
              }
            },
            {
              selector: 'node[type="root_system"]',
              style: {
                'background-color': '#A0522D',
                'shape': 'round-octagon',
                'width': 85,
                'height': 65,
                'border-color': '#D2691E',
                'box-shadow': '0 0 18px rgba(160, 82, 45, 0.8)',
                'font-size': '9px'
              }
            },
            {
              selector: 'node[type="vascular_system"]',
              style: {
                'background-color': '#DC143C',
                'shape': 'round-rectangle',
                'width': 85,
                'height': 60,
                'border-color': '#F08080',
                'box-shadow': '0 0 18px rgba(220, 20, 60, 0.8)',
                'font-size': '9px'
              }
            },
            {
              selector: 'node[type="metabolic_system"]',
              style: {
                'background-color': '#4682B4',
                'shape': 'ellipse',
                'width': 80,
                'height': 60,
                'border-color': '#87CEEB',
                'box-shadow': '0 0 18px rgba(70, 130, 180, 0.8)',
                'font-size': '9px'
              }
            },
            {
              selector: 'node[type="growth_apex"]',
              style: {
                'background-color': '#9370DB',
                'shape': 'round-triangle',
                'width': 70,
                'height': 80,
                'border-color': '#DDA0DD',
                'box-shadow': '0 0 20px rgba(147, 112, 219, 0.8)',
                'font-size': '9px'
              }
            },
            {
              selector: 'node[type="antibody_system"]',
              style: {
                'background-color': '#B22222',
                'shape': 'round-hexagon',
                'width': 75,
                'height': 75,
                'border-color': '#FF6347',
                'box-shadow': '0 0 20px rgba(178, 34, 34, 0.8)',
                'font-size': '9px'
              }
            },
            {
              selector: 'node[type="cell_division"]',
              style: {
                'background-color': '#20B2AA',
                'shape': 'round-diamond',
                'width': 70,
                'height': 70,
                'border-color': '#AFEEEE',
                'box-shadow': '0 0 18px rgba(32, 178, 170, 0.8)',
                'font-size': '9px'
              }
            },
            {
              selector: 'node[type="metabolic_pathway"]',
              style: {
                'background-color': '#8A2BE2',
                'shape': 'ellipse',
                'width': 85,
                'height': 50,
                'border-color': '#DDA0DD',
                'box-shadow': '0 0 18px rgba(138, 43, 226, 0.7)',
                'font-size': '9px'
              }
            },
            {
              selector: 'node[type="symbiotic_environment"]',
              style: {
                'background-color': '#2E8B57',
                'shape': 'round-octagon',
                'width': 100,
                'height': 100,
                'border-color': '#98FB98',
                'box-shadow': '0 0 30px rgba(46, 139, 87, 0.9)',
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

            // gftd.ai エコシステム専用ノードスタイル
            {
              selector: 'node[type="central_nervous_system"]',
              style: {
                'background-color': '#1A237E',
                'shape': 'star',
                'width': 120,
                'height': 120,
                'border-color': '#3F51B5',
                'color': '#E8EAF6',
                'text-shadow': '0 0 4px rgba(0, 0, 0, 0.9)',
                'box-shadow': '0 0 40px rgba(26, 35, 126, 1.0)',
                'font-size': '12px'
              }
            },
            {
              selector: 'node[type="neurotransmitter"]',
              style: {
                'background-color': '#0D47A1',
                'shape': 'round-hexagon',
                'width': 90,
                'height': 90,
                'border-color': '#2196F3',
                'box-shadow': '0 0 25px rgba(13, 71, 161, 0.9)',
                'font-size': '10px'
              }
            },
            {
              selector: 'node[type="endocrine_system"]',
              style: {
                'background-color': '#4A148C',
                'shape': 'round-octagon',
                'width': 100,
                'height': 85,
                'border-color': '#7B1FA2',
                'box-shadow': '0 0 30px rgba(74, 20, 140, 0.9)',
                'font-size': '11px'
              }
            },
            {
              selector: 'node[type="neural_network_platform"]',
              style: {
                'background-color': '#880E4F',
                'shape': 'round-diamond',
                'width': 105,
                'height': 105,
                'border-color': '#E91E63',
                'box-shadow': '0 0 35px rgba(136, 14, 79, 1.0)',
                'font-size': '11px'
              }
            },
            {
              selector: 'node[type="circulatory_system"]',
              style: {
                'background-color': '#B71C1C',
                'shape': 'round-rectangle',
                'width': 95,
                'height': 80,
                'border-color': '#F44336',
                'box-shadow': '0 0 28px rgba(183, 28, 28, 0.9)',
                'font-size': '10px'
              }
            },
            {
              selector: 'node[type="integumentary_system"]',
              style: {
                'background-color': '#E65100',
                'shape': 'round-triangle',
                'width': 90,
                'height': 85,
                'border-color': '#FF9800',
                'box-shadow': '0 0 25px rgba(230, 81, 0, 0.8)',
                'font-size': '10px'
              }
            },
            {
              selector: 'node[type="skeletal_system"]',
              style: {
                'background-color': '#5D4037',
                'shape': 'round-rectangle',
                'width': 100,
                'height': 75,
                'border-color': '#8D6E63',
                'box-shadow': '0 0 22px rgba(93, 64, 55, 0.8)',
                'font-size': '10px'
              }
            },
            {
              selector: 'node[type="synaptic_connection"]',
              style: {
                'background-color': '#00695C',
                'shape': 'ellipse',
                'width': 85,
                'height': 60,
                'border-color': '#009688',
                'box-shadow': '0 0 20px rgba(0, 105, 92, 0.8)',
                'font-size': '9px'
              }
            },
            {
              selector: 'node[type="lymphatic_system"]',
              style: {
                'background-color': '#1B5E20',
                'shape': 'round-pentagon',
                'width': 85,
                'height': 85,
                'border-color': '#4CAF50',
                'box-shadow': '0 0 22px rgba(27, 94, 32, 0.8)',
                'font-size': '10px'
              }
            },
            {
              selector: 'node[type="metabolic_enzyme"]',
              style: {
                'background-color': '#F57F17',
                'shape': 'round-triangle',
                'width': 75,
                'height': 75,
                'border-color': '#FFEB3B',
                'color': '#1A1A1A',
                'text-shadow': '0 0 3px rgba(255, 255, 255, 0.8)',
                'box-shadow': '0 0 20px rgba(245, 127, 23, 0.8)',
                'font-size': '9px'
              }
            },
            {
              selector: 'node[type="dna_repository"]',
              style: {
                'background-color': '#37474F',
                'shape': 'round-hexagon',
                'width': 90,
                'height': 90,
                'border-color': '#78909C',
                'box-shadow': '0 0 25px rgba(55, 71, 79, 0.8)',
                'font-size': '10px'
              }
            },
            {
              selector: 'node[type="rna_synthesis_factory"]',
              style: {
                'background-color': '#FF6F00',
                'shape': 'round-octagon',
                'width': 85,
                'height': 85,
                'border-color': '#FFC107',
                'color': '#1A1A1A',
                'text-shadow': '0 0 3px rgba(255, 255, 255, 0.8)',
                'box-shadow': '0 0 22px rgba(255, 111, 0, 0.8)',
                'font-size': '10px'
              }
            },
            {
              selector: 'node[type="cerebral_cortex"]',
              style: {
                'background-color': '#6A1B9A',
                'shape': 'star',
                'width': 110,
                'height': 110,
                'border-color': '#9C27B0',
                'box-shadow': '0 0 35px rgba(106, 27, 154, 1.0)',
                'font-size': '11px'
              }
            },
            {
              selector: 'node[type="sensory_organs"]',
              style: {
                'background-color': '#827717',
                'shape': 'ellipse',
                'width': 85,
                'height': 65,
                'border-color': '#CDDC39',
                'color': '#1A1A1A',
                'text-shadow': '0 0 3px rgba(255, 255, 255, 0.8)',
                'box-shadow': '0 0 20px rgba(130, 119, 23, 0.8)',
                'font-size': '9px'
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

            // gftd.ai エコシステム専用エッジスタイル
            {
              selector: 'edge[type="neural_control"], edge[type="neural_coordination"]',
              style: {
                'line-color': '#3F51B5',
                'target-arrow-color': '#3F51B5',
                'width': 3,
                'line-style': 'solid',
                'opacity': 0.9,
                'curve-style': 'bezier'
              }
            },
            {
              selector: 'edge[type="endocrine_regulation"], edge[type="hormone_secretion"]',
              style: {
                'line-color': '#9C27B0',
                'target-arrow-color': '#9C27B0',
                'width': 2.5,
                'line-style': 'dotted',
                'opacity': 0.8,
                'curve-style': 'bezier'
              }
            },
            {
              selector: 'edge[type="service_discovery"]',
              style: {
                'line-color': '#2196F3',
                'target-arrow-color': '#2196F3',
                'width': 2,
                'line-style': 'dashed',
                'opacity': 0.8
              }
            },
            {
              selector: 'edge[type="deployment_platform"], edge[type="runtime_environment"]',
              style: {
                'line-color': '#F44336',
                'target-arrow-color': '#F44336',
                'width': 2.5,
                'line-style': 'solid',
                'opacity': 0.8
              }
            },
            {
              selector: 'edge[type="optimized_deployment"]',
              style: {
                'line-color': '#FF9800',
                'target-arrow-color': '#FF9800',
                'width': 2.5,
                'line-style': 'solid',
                'opacity': 0.8
              }
            },
            {
              selector: 'edge[type="api_integration"], edge[type="client_integration"]',
              style: {
                'line-color': '#7B1FA2',
                'target-arrow-color': '#7B1FA2',
                'width': 2,
                'line-style': 'solid',
                'opacity': 0.8
              }
            },
            {
              selector: 'edge[type="protocol_implementation"]',
              style: {
                'line-color': '#009688',
                'target-arrow-color': '#009688',
                'width': 2.5,
                'line-style': 'dotted',
                'opacity': 0.8
              }
            },
            {
              selector: 'edge[type="message_passing"]',
              style: {
                'line-color': '#E91E63',
                'target-arrow-color': '#E91E63',
                'width': 2.5,
                'line-style': 'dashed',
                'opacity': 0.8
              }
            },
            {
              selector: 'edge[type="stream_processing"]',
              style: {
                'line-color': '#4CAF50',
                'target-arrow-color': '#4CAF50',
                'width': 2.5,
                'line-style': 'solid',
                'opacity': 0.8
              }
            },
            {
              selector: 'edge[type="data_pipeline"]',
              style: {
                'line-color': '#4CAF50',
                'target-arrow-color': '#4CAF50',
                'width': 3,
                'line-style': 'solid',
                'opacity': 0.9
              }
            },
            {
              selector: 'edge[type="real_time_analytics"]',
              style: {
                'line-color': '#FFEB3B',
                'target-arrow-color': '#FFEB3B',
                'width': 2,
                'line-style': 'dotted',
                'opacity': 0.8
              }
            },
            {
              selector: 'edge[type="code_synchronization"], edge[type="source_management"]',
              style: {
                'line-color': '#78909C',
                'target-arrow-color': '#78909C',
                'width': 2,
                'line-style': 'solid',
                'opacity': 0.7
              }
            },
            {
              selector: 'edge[type="ci_cd_integration"], edge[type="deployment_trigger"]',
              style: {
                'line-color': '#78909C',
                'target-arrow-color': '#78909C',
                'width': 2.5,
                'line-style': 'dashed',
                'opacity': 0.8
              }
            },
            {
              selector: 'edge[type="devops_pipeline"]',
              style: {
                'line-color': '#FFC107',
                'target-arrow-color': '#FFC107',
                'width': 3,
                'line-style': 'solid',
                'opacity': 0.9
              }
            },
            {
              selector: 'edge[type="intelligent_service"]',
              style: {
                'line-color': '#9C27B0',
                'target-arrow-color': '#9C27B0',
                'width': 3,
                'line-style': 'solid',
                'opacity': 0.9
              }
            },
            {
              selector: 'edge[type="authentication"], edge[type="authorization"]',
              style: {
                'line-color': '#B71C1C',
                'target-arrow-color': '#B71C1C',
                'width': 2,
                'line-style': 'dashed',
                'opacity': 0.8
              }
            },
            {
              selector: 'edge[type="data_encryption"]',
              style: {
                'line-color': '#B71C1C',
                'target-arrow-color': '#B71C1C',
                'width': 2.5,
                'line-style': 'dotted',
                'opacity': 0.8
              }
            },
            {
              selector: 'edge[type="infrastructure_monitoring"], edge[type="performance_monitoring"], edge[type="ai_monitoring"]',
              style: {
                'line-color': '#CDDC39',
                'target-arrow-color': '#CDDC39',
                'width': 2,
                'line-style': 'dotted',
                'opacity': 0.7
              }
            },
            {
              selector: 'edge[type="api_consumption"]',
              style: {
                'line-color': '#009688',
                'target-arrow-color': '#009688',
                'width': 2.5,
                'line-style': 'solid',
                'opacity': 0.8
              }
            },

            // 組織系エッジスタイル
            {
              selector: 'edge[type="energy_governance"], edge[type="strategic_direction"]',
              style: {
                'line-color': '#FFD700',
                'target-arrow-color': '#FFD700',
                'width': 3,
                'line-style': 'solid',
                'opacity': 0.9
              }
            },
            {
              selector: 'edge[type="execution_flow"], edge[type="resource_allocation"]',
              style: {
                'line-color': '#8B4513',
                'target-arrow-color': '#8B4513',
                'width': 2.5,
                'line-style': 'solid',
                'opacity': 0.8
              }
            },
            {
              selector: 'edge[type="collaboration"], edge[type="talent_supply"]',
              style: {
                'line-color': '#32CD32',
                'target-arrow-color': '#32CD32',
                'width': 2,
                'line-style': 'solid',
                'opacity': 0.8
              }
            },
            {
              selector: 'edge[type="financial_control"], edge[type="process_optimization"]',
              style: {
                'line-color': '#DC143C',
                'target-arrow-color': '#DC143C',
                'width': 2,
                'line-style': 'solid',
                'opacity': 0.7
              }
            },
            {
              selector: 'edge[type="compliance_oversight"], edge[type="security_integration"]',
              style: {
                'line-color': '#B22222',
                'target-arrow-color': '#B22222',
                'width': 2,
                'line-style': 'dashed',
                'opacity': 0.7
              }
            },
            {
              selector: 'edge[type="research_collaboration"], edge[type="insights_delivery"]',
              style: {
                'line-color': '#9370DB',
                'target-arrow-color': '#9370DB',
                'width': 2,
                'line-style': 'dotted',
                'opacity': 0.8
              }
            },
            {
              selector: 'edge[type="team_formation"], edge[type="project_execution"]',
              style: {
                'line-color': '#20B2AA',
                'target-arrow-color': '#20B2AA',
                'width': 2.5,
                'line-style': 'solid',
                'opacity': 0.8
              }
            },
            {
              selector: 'edge[type="knowledge_capture"], edge[type="knowledge_transfer"], edge[type="knowledge_sharing"]',
              style: {
                'line-color': '#4682B4',
                'target-arrow-color': '#4682B4',
                'width': 2,
                'line-style': 'dotted',
                'opacity': 0.7
              }
            },
            {
              selector: 'edge[type="customer_interaction"], edge[type="feedback_loop"], edge[type="market_insights"]',
              style: {
                'line-color': '#2E8B57',
                'target-arrow-color': '#2E8B57',
                'width': 2.5,
                'line-style': 'solid',
                'curve-style': 'unbundled-bezier',
                'opacity': 0.8
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
      }, [loadMathData, loadTechData, loadOrgData, loadGftdData, loadFromLocalStorage, viewMode, setupEventHandlers, updateAvailableNodes]);

  // ビューモード変更時のデータ更新
  useEffect(() => {
    if (cyRef.current && (mathData || techData || orgData || gftdData)) {
      const currentData = getCurrentData();
      cyRef.current.elements().remove();
      cyRef.current.add([...currentData.nodes, ...currentData.edges]);
      cyRef.current.layout({ name: currentLayout }).run();
      updateAvailableNodes(cyRef.current);
    }
  }, [viewMode, currentLayout, mathData, techData, orgData, gftdData, userEdits]);

  // サブネットワークフィルター適用
  useEffect(() => {
    applySubNetworkFilters();
  }, [activeSubNetworks, subNetworks, applySubNetworkFilters]);

  // エッジ選択の視覚的更新
  useEffect(() => {
    if (!cyRef.current) return;
    
    // すべてのエッジのスタイルをリセット
    cyRef.current.edges().style({
      'line-color': '#607D8B',
      'target-arrow-color': '#607D8B',
      'width': 1.5,
      'opacity': 0.7
    });

    // 選択されたエッジをハイライト
    selectedEdges.forEach(edgeId => {
      const edge = cyRef.current!.getElementById(edgeId);
      if (edge.length > 0) {
        edge.style({
          'line-color': '#00BCD4',
          'target-arrow-color': '#00BCD4',
          'width': 4,
          'opacity': 1
        });
      }
    });
  }, [selectedEdges]);

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
      trackUserEdit('node', 'update', nodeFormData);
    } else {
      cyRef.current.add({ data: nodeFormData });
      trackUserEdit('node', 'add', nodeFormData);
    }
    
    setShowNodeModal(false);
    updateAvailableNodes(cyRef.current);
    cyRef.current.layout({ name: currentLayout }).run();
  }, [nodeFormData, currentLayout, updateAvailableNodes, trackUserEdit]);

  const saveEdge = useCallback(() => {
    if (!cyRef.current || !edgeFormData.source || !edgeFormData.target) return;
    
    cyRef.current.add({ data: edgeFormData });
    trackUserEdit('edge', 'add', edgeFormData);
    setShowEdgeModal(false);
    cyRef.current.layout({ name: currentLayout }).run();
  }, [edgeFormData, currentLayout, trackUserEdit]);

  const deleteSelected = useCallback(() => {
    if (selectedElement && cyRef.current) {
      if (confirm('選択された要素を削除しますか？')) {
        const data = selectedElement.data();
        if (selectedElement.isNode && selectedElement.isNode()) {
          trackUserEdit('node', 'delete', data);
        } else if (selectedElement.isEdge && selectedElement.isEdge()) {
          trackUserEdit('edge', 'delete', data);
        }
        selectedElement.remove();
        setSelectedElement(null);
        updateAvailableNodes(cyRef.current);
        setShowInfoPanel(false);
      }
    } else {
      alert('削除する要素を選択してください。');
    }
  }, [selectedElement, updateAvailableNodes, trackUserEdit]);

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
      {/* ヘッダー（シンプル化） */}
      <div className="bg-black/30 backdrop-blur-lg border-b border-white/10 p-4">
        <div className="flex items-center justify-between">
          <div>
            <h1 className="text-2xl font-bold text-cyan-400 drop-shadow-lg">
              数学理論×IT技術 統合生態系
            </h1>
            <p className="text-sm text-cyan-300 mt-1">
              {viewMode === 'math' && '数学理論の生物的表現'}
              {viewMode === 'tech' && 'IT技術の生態系'}
              {viewMode === 'org' && '会社組織の生態系'}
              {viewMode === 'gftd' && 'gftd.ai技術スタックの生命体構図'}
              {viewMode === 'integrated' && '数学理論×IT技術×組織×gftd.aiの統合生態系'}
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
                onClick={() => setViewMode('org')}
                className={`px-3 py-1 rounded-full text-xs transition-all duration-300 ${
                  viewMode === 'org'
                    ? 'bg-orange-500 text-white'
                    : 'text-orange-400 hover:bg-orange-500/20'
                }`}
              >
                組織
              </button>
              <button
                onClick={() => setViewMode('gftd')}
                className={`px-3 py-1 rounded-full text-xs transition-all duration-300 ${
                  viewMode === 'gftd'
                    ? 'bg-cyan-500 text-white'
                    : 'text-cyan-400 hover:bg-cyan-500/20'
                }`}
              >
                gftd.ai
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
          </div>
        </div>
      </div>

                    {/* 3ペイン構成のメインコンテンツ */}
       <div className="flex flex-1 overflow-hidden">
         {/* 左ペイン: データエディタ */}
         <div className="w-1/4 bg-black/20 backdrop-blur-lg border-r border-white/10 flex flex-col">
           {/* タブ切り替え */}
           <div className="flex border-b border-white/10">
             <button
               onClick={() => setLeftPanelTab('nodes')}
               className={`flex-1 px-4 py-3 text-sm font-medium transition-all duration-300 ${
                 leftPanelTab === 'nodes'
                   ? 'bg-cyan-500/20 text-cyan-400 border-b-2 border-cyan-400'
                   : 'text-gray-400 hover:text-white hover:bg-white/5'
               }`}
             >
               ノード ({cyRef.current?.nodes().length || 0})
             </button>
             <button
               onClick={() => setLeftPanelTab('edges')}
               className={`flex-1 px-4 py-3 text-sm font-medium transition-all duration-300 ${
                 leftPanelTab === 'edges'
                   ? 'bg-cyan-500/20 text-cyan-400 border-b-2 border-cyan-400'
                   : 'text-gray-400 hover:text-white hover:bg-white/5'
               }`}
             >
               エッジ ({cyRef.current?.edges().length || 0})
             </button>
             <button
               onClick={() => setLeftPanelTab('subnets')}
               className={`flex-1 px-4 py-3 text-sm font-medium transition-all duration-300 ${
                 leftPanelTab === 'subnets'
                   ? 'bg-cyan-500/20 text-cyan-400 border-b-2 border-cyan-400'
                   : 'text-gray-400 hover:text-white hover:bg-white/5'
               }`}
             >
               サブネット ({subNetworks.length})
             </button>
           </div>
           
           {/* コンテンツエリア */}
           <div className="flex-1 overflow-hidden flex flex-col p-4">
             {leftPanelTab === 'nodes' && (
               <div className="flex flex-col h-full">
                 <h3 className="text-cyan-400 font-semibold text-sm mb-3">ノード一覧</h3>
                 <div className="flex-1 overflow-y-auto space-y-2">
                   {cyRef.current?.nodes().map((node: any) => {
                     const data = node.data();
                     const isSelected = selectedNodeIds.includes(data.id);
                     return (
                       <div
                         key={data.id}
                         className={`p-3 rounded-lg border cursor-pointer transition-all duration-200 ${
                           isSelected
                             ? 'bg-cyan-500/20 border-cyan-400'
                             : 'bg-white/5 border-white/10 hover:bg-white/10'
                         }`}
                         onClick={() => selectItemInLeftPanel('node', data.id)}
                       >
                         <div className="flex justify-between items-start">
                           <div className="flex-1 min-w-0">
                             <div className="text-sm font-medium text-white truncate">
                               {data.label || data.id}
                             </div>
                             <div className="text-xs text-gray-400 truncate">
                               {data.type} • {data.category}
                             </div>
                           </div>
                           <button
                             onClick={(e) => {
                               e.stopPropagation();
                               editItemInLeftPanel('node', data.id);
                             }}
                             className="ml-2 text-gray-400 hover:text-cyan-400 text-xs"
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
                 <h3 className="text-cyan-400 font-semibold text-sm mb-3">エッジ一覧</h3>
                 <div className="flex-1 overflow-y-auto space-y-2">
                   {cyRef.current?.edges().map((edge: any) => {
                     const data = edge.data();
                     const isSelected = selectedEdgeIds.includes(data.id);
                     return (
                       <div
                         key={data.id}
                         className={`p-3 rounded-lg border cursor-pointer transition-all duration-200 ${
                           isSelected
                             ? 'bg-cyan-500/20 border-cyan-400'
                             : 'bg-white/5 border-white/10 hover:bg-white/10'
                         }`}
                         onClick={() => selectItemInLeftPanel('edge', data.id)}
                       >
                         <div className="flex justify-between items-start">
                           <div className="flex-1 min-w-0">
                             <div className="text-sm font-medium text-white truncate">
                               {data.source} → {data.target}
                             </div>
                             <div className="text-xs text-gray-400 truncate">
                               {data.type} • {data.label || 'エッジ'}
                             </div>
                           </div>
                           <button
                             onClick={(e) => {
                               e.stopPropagation();
                               editItemInLeftPanel('edge', data.id);
                             }}
                             className="ml-2 text-gray-400 hover:text-cyan-400 text-xs"
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
                 <h3 className="text-cyan-400 font-semibold text-sm mb-3">サブネットワーク</h3>
                 <div className="flex-1 overflow-y-auto space-y-2">
                   {subNetworks.map(subnet => (
                     <div
                       key={subnet.id}
                       className="p-3 rounded-lg bg-white/5 border border-white/10"
                     >
                       <div className="flex items-center justify-between mb-2">
                         <div className="flex items-center gap-2">
                           <div
                             className="w-3 h-3 rounded-full"
                             style={{ backgroundColor: subnet.color }}
                           />
                           <span className="text-sm font-medium text-white">
                             {subnet.name}
                           </span>
                         </div>
                         <label className="flex items-center">
                           <input
                             type="checkbox"
                             checked={activeSubNetworks.includes(subnet.id)}
                             onChange={() => toggleSubNetwork(subnet.id)}
                             className="sr-only"
                           />
                           <div className={`w-4 h-4 rounded border-2 transition-all duration-200 ${
                             activeSubNetworks.includes(subnet.id)
                               ? 'bg-cyan-500 border-cyan-500'
                               : 'border-gray-400'
                           }`}>
                             {activeSubNetworks.includes(subnet.id) && (
                               <svg className="w-3 h-3 text-white" fill="currentColor" viewBox="0 0 20 20">
                                 <path fillRule="evenodd" d="M16.707 5.293a1 1 0 010 1.414l-8 8a1 1 0 01-1.414 0l-4-4a1 1 0 011.414-1.414L8 12.586l7.293-7.293a1 1 0 011.414 0z" clipRule="evenodd" />
                               </svg>
                             )}
                           </div>
                         </label>
                       </div>
                       <div className="text-xs text-gray-400 mb-2">
                         {subnet.description}
                       </div>
                       <div className="text-xs text-gray-500">
                         {subnet.edgeIds.length}個のエッジ
                       </div>
                     </div>
                   ))}
                 </div>
               </div>
             )}
           </div>
         </div>
         
         {/* 中央ペイン: Cytoscapeビューアー */}
         <div className="flex-1 relative">
           <div 
             ref={containerRef} 
             className="w-full h-full bg-gradient-to-br from-black/20 to-transparent"
           />
           
           {/* オーバーレイコントロール */}
           <div className="absolute top-4 left-4 flex gap-2">
             <button
               onClick={resetView}
               className="px-3 py-2 bg-black/70 backdrop-blur-sm border border-white/20 rounded-lg text-cyan-400 hover:bg-black/80 transition-all duration-300 text-sm"
             >
               リセット
             </button>
             <button
               onClick={fitToView}
               className="px-3 py-2 bg-black/70 backdrop-blur-sm border border-white/20 rounded-lg text-cyan-400 hover:bg-black/80 transition-all duration-300 text-sm"
             >
               全体表示
             </button>
             <button
               onClick={toggleLayout}
               className="px-3 py-2 bg-black/70 backdrop-blur-sm border border-white/20 rounded-lg text-cyan-400 hover:bg-black/80 transition-all duration-300 text-sm"
             >
               レイアウト: {currentLayout}
             </button>
           </div>
         </div>
         
         {/* 右ペイン: コンテクストメニュー・情報 */}
         <div className="w-1/4 bg-black/20 backdrop-blur-lg border-l border-white/10 flex flex-col overflow-hidden">
           {/* 編集・操作コントロール */}
           <div className="p-4 border-b border-white/10">
             <h3 className="text-cyan-400 font-semibold text-sm mb-3">操作・編集</h3>
             <div className="space-y-2">
               <button
                 onClick={() => setEditMode(!editMode)}
                 className={`w-full px-4 py-2 border rounded-lg transition-all duration-300 text-sm ${
                   editMode
                     ? 'bg-yellow-500/60 border-yellow-400 text-yellow-100'
                     : 'bg-yellow-500/20 border-yellow-400 text-yellow-400 hover:bg-yellow-500/40'
                 }`}
               >
                 {editMode ? '編集中' : '編集モード'}
               </button>
               
               <div className="flex gap-2">
                 <button
                   onClick={addNode}
                   className="flex-1 px-3 py-2 bg-green-500/20 border border-green-400 rounded-lg text-green-400 hover:bg-green-500/40 transition-all duration-300 text-xs"
                 >
                   ノード追加
                 </button>
                 <button
                   onClick={addEdge}
                   className="flex-1 px-3 py-2 bg-blue-500/20 border border-blue-400 rounded-lg text-blue-400 hover:bg-blue-500/40 transition-all duration-300 text-xs"
                 >
                   エッジ追加
                 </button>
               </div>
               
               <button
                 onClick={() => setEdgeSelectionMode(!edgeSelectionMode)}
                 className={`w-full px-4 py-2 border rounded-lg transition-all duration-300 text-sm ${
                   edgeSelectionMode
                     ? 'bg-cyan-500/60 border-cyan-400 text-cyan-100'
                     : 'bg-cyan-500/20 border-cyan-400 text-cyan-400 hover:bg-cyan-500/40'
                 }`}
               >
                 {edgeSelectionMode ? 'エッジ選択中' : 'エッジ選択'}
               </button>
               
               <button
                 onClick={() => setShowSubNetworkModal(true)}
                 className="w-full px-4 py-2 bg-purple-500/20 border border-purple-400 rounded-lg text-purple-400 hover:bg-purple-500/40 transition-all duration-300 text-sm"
                 disabled={selectedEdges.length === 0}
               >
                 サブネット作成 {selectedEdges.length > 0 && `(${selectedEdges.length})`}
               </button>
             </div>
             
             {/* エクスポート・インポート */}
             <div className="mt-4 pt-4 border-t border-white/10">
               <h4 className="text-cyan-400 font-semibold text-xs mb-2">データ管理</h4>
               <div className="flex gap-2">
                 <button
                   onClick={exportData}
                   className="flex-1 px-3 py-2 bg-orange-500/20 border border-orange-400 rounded-lg text-orange-400 hover:bg-orange-500/40 transition-all duration-300 text-xs"
                 >
                   エクスポート
                 </button>
                 <label className="flex-1">
                   <input
                     type="file"
                     accept=".json"
                     onChange={importData}
                     className="hidden"
                   />
                   <span className="block px-3 py-2 bg-teal-500/20 border border-teal-400 rounded-lg text-teal-400 hover:bg-teal-500/40 transition-all duration-300 text-xs text-center cursor-pointer">
                     インポート
                   </span>
                 </label>
               </div>
             </div>
           </div>
           
           {/* 情報パネル */}
           <div className="flex-1 overflow-hidden flex flex-col">
             {showInfoPanel && infoData ? (
               <div className="p-4 border-b border-white/10">
                 <div className="flex justify-between items-start mb-3">
                   <h3 className="text-cyan-400 font-bold text-lg">{infoData.title}</h3>
                   <button
                     onClick={() => setShowInfoPanel(false)}
                     className="text-gray-400 hover:text-white text-xl"
                   >
                     ×
                   </button>
                 </div>
                 <p className="text-gray-300 text-sm leading-relaxed mb-3">{infoData.description}</p>
                 {infoData.details && (
                   <div className="text-xs text-gray-400 space-y-1">
                     <div>カテゴリ: {infoData.details.category}</div>
                     <div>タイプ: {infoData.details.type}</div>
                     {infoData.details.level && <div>レベル: {infoData.details.level}</div>}
                   </div>
                 )}
               </div>
             ) : (
               <div className="p-4 text-center text-gray-500">
                 <p className="text-sm">ノードまたはエッジを選択してください</p>
               </div>
             )}
             
             {/* アイテム編集フォーム */}
             {editingItem && (
               <div className="p-4 border-b border-white/10 bg-black/30">
                 <h3 className="text-purple-400 font-semibold text-sm mb-3">
                   {editingItem.type === 'node' ? 'ノード編集' : 'エッジ編集'}
                 </h3>
                 <div className="space-y-3">
                   <div>
                     <label className="block text-gray-400 text-xs mb-1">ラベル:</label>
                     <input
                       type="text"
                       value={editingItem.data.label || ''}
                       onChange={(e) => setEditingItem(prev => 
                         prev ? {...prev, data: {...prev.data, label: e.target.value}} : null
                       )}
                       className="w-full px-3 py-2 bg-white/10 border border-white/20 rounded text-white text-sm"
                     />
                   </div>
                   
                   {editingItem.type === 'node' && (
                     <>
                       <div>
                         <label className="block text-gray-400 text-xs mb-1">説明:</label>
                         <textarea
                           value={editingItem.data.description || ''}
                           onChange={(e) => setEditingItem(prev => 
                             prev ? {...prev, data: {...prev.data, description: e.target.value}} : null
                           )}
                           className="w-full px-3 py-2 bg-white/10 border border-white/20 rounded text-white text-sm"
                           rows={3}
                         />
                       </div>
                       
                       <div>
                         <label className="block text-gray-400 text-xs mb-1">カテゴリ:</label>
                         <input
                           type="text"
                           value={editingItem.data.category || ''}
                           onChange={(e) => setEditingItem(prev => 
                             prev ? {...prev, data: {...prev.data, category: e.target.value}} : null
                           )}
                           className="w-full px-3 py-2 bg-white/10 border border-white/20 rounded text-white text-sm"
                         />
                       </div>
                     </>
                   )}
                   
                   {editingItem.type === 'edge' && (
                     <div>
                       <label className="block text-gray-400 text-xs mb-1">タイプ:</label>
                       <input
                         type="text"
                         value={editingItem.data.type || ''}
                         onChange={(e) => setEditingItem(prev => 
                           prev ? {...prev, data: {...prev.data, type: e.target.value}} : null
                         )}
                         className="w-full px-3 py-2 bg-white/10 border border-white/20 rounded text-white text-sm"
                       />
                     </div>
                   )}
                   
                   <div className="flex gap-2">
                     <button
                       onClick={saveItemEdit}
                       className="flex-1 px-3 py-2 bg-green-500/30 text-green-300 rounded text-xs hover:bg-green-500/50"
                     >
                       保存
                     </button>
                     <button
                       onClick={() => setEditingItem(null)}
                       className="flex-1 px-3 py-2 bg-gray-500/30 text-gray-300 rounded text-xs hover:bg-gray-500/50"
                     >
                       キャンセル
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
                onClick={() => setViewMode('org')}
                className={`px-3 py-1 rounded-full text-xs transition-all duration-300 ${
                  viewMode === 'org'
                    ? 'bg-orange-500 text-white'
                    : 'text-orange-400 hover:bg-orange-500/20'
                }`}
              >
                組織
              </button>
              <button
                onClick={() => setViewMode('gftd')}
                className={`px-3 py-1 rounded-full text-xs transition-all duration-300 ${
                  viewMode === 'gftd'
                    ? 'bg-cyan-500 text-white'
                    : 'text-cyan-400 hover:bg-cyan-500/20'
                }`}
              >
                gftd.ai
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
              <button
                onClick={() => setShowTrackpadHelp(!showTrackpadHelp)}
                className={`px-4 py-2 border rounded-full transition-all duration-300 text-sm ${
                  showTrackpadHelp
                    ? 'bg-purple-500/60 border-purple-400 text-purple-100'
                    : 'bg-purple-500/20 border-purple-400 text-purple-400 hover:bg-purple-500/40'
                }`}
              >
                🖱️ Trackpad操作
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

            {/* サブネットワーク コントロール */}
            <div className="flex gap-2">
              <button
                onClick={() => setShowSubNetworkPanel(!showSubNetworkPanel)}
                className={`px-4 py-2 border rounded-full transition-all duration-300 text-sm ${
                  showSubNetworkPanel
                    ? 'bg-purple-500/60 border-purple-400 text-purple-100'
                    : 'bg-purple-500/20 border-purple-400 text-purple-400 hover:bg-purple-500/40'
                }`}
              >
                🕸️ サブネット
              </button>
              <button
                onClick={() => setEdgeSelectionMode(!edgeSelectionMode)}
                className={`px-3 py-2 border rounded-full transition-all duration-300 text-xs ${
                  edgeSelectionMode
                    ? 'bg-cyan-500/60 border-cyan-400 text-cyan-100'
                    : 'bg-cyan-500/20 border-cyan-400 text-cyan-400 hover:bg-cyan-500/40'
                }`}
              >
                {edgeSelectionMode ? 'エッジ選択中' : 'エッジ選択'}
              </button>
              {selectedEdges.length > 0 && (
                <>
                  <button
                    onClick={createSubNetwork}
                    className="px-3 py-2 bg-green-500/20 border border-green-400 rounded-full text-green-400 hover:bg-green-500/40 transition-all duration-300 text-xs"
                  >
                    作成 ({selectedEdges.length})
                  </button>
                  <button
                    onClick={clearEdgeSelection}
                    className="px-3 py-2 bg-gray-500/20 border border-gray-400 rounded-full text-gray-400 hover:bg-gray-500/40 transition-all duration-300 text-xs"
                  >
                    クリア
                  </button>
                </>
              )}
              {activeSubNetworks.length > 0 && (
                <button
                  onClick={clearAllSubNetworks}
                  className="px-3 py-2 bg-orange-500/20 border border-orange-400 rounded-full text-orange-400 hover:bg-orange-500/40 transition-all duration-300 text-xs"
                >
                  全クリア
                </button>
              )}
            </div>
            
            {/* ローカル保存機能 */}
            <div className="flex gap-2">
              <button
                onClick={saveToLocalStorage}
                className={`px-3 py-2 border rounded-full transition-all duration-300 text-xs ${
                  hasUnsavedChanges
                    ? 'bg-red-500/20 border-red-400 text-red-400 hover:bg-red-500/40'
                    : 'bg-green-500/20 border-green-400 text-green-400 hover:bg-green-500/40'
                }`}
              >
                {hasUnsavedChanges ? '保存' : '保存済み'}
              </button>
              <button
                onClick={loadFromLocalStorage}
                className="px-3 py-2 bg-blue-500/20 border border-blue-400 rounded-full text-blue-400 hover:bg-blue-500/40 transition-all duration-300 text-xs"
              >
                復元
              </button>
              <button
                onClick={clearLocalStorage}
                className="px-3 py-2 bg-orange-500/20 border border-orange-400 rounded-full text-orange-400 hover:bg-orange-500/40 transition-all duration-300 text-xs"
              >
                クリア
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
        
        {/* エッジ選択モードインジケーター */}
        {edgeSelectionMode && (
          <div className="mt-2 ml-4 inline-block bg-cyan-500/90 text-black px-4 py-2 rounded-full font-bold text-sm">
            🔗 エッジ選択モード: エッジをクリックしてサブネットワークを作成
            {selectedEdges.length > 0 && ` (${selectedEdges.length}個選択中)`}
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
            
            {(viewMode === 'org' || viewMode === 'integrated') && (
              <>
                <div className="text-orange-400 font-semibold mb-1 mt-3">会社組織</div>
                <div className="flex items-center gap-2">
                  <div className="w-3 h-3 rounded-full bg-yellow-500 border border-white/30" />
                  <span className="text-white">経営陣（太陽）</span>
                </div>
                <div className="flex items-center gap-2">
                  <div className="w-3 h-3 rounded-full bg-blue-600 border border-white/30" />
                  <span className="text-white">取締役会（大気圏）</span>
                </div>
                <div className="flex items-center gap-2">
                  <div className="w-3 h-3 rounded-full bg-green-600 border border-white/30" />
                  <span className="text-white">執行役員（樹冠層）</span>
                </div>
                <div className="flex items-center gap-2">
                  <div className="w-3 h-3 rounded-full bg-amber-800 border border-white/30" />
                  <span className="text-white">管理職（幹）</span>
                </div>
                <div className="flex items-center gap-2">
                  <div className="w-3 h-3 rounded-full bg-lime-500 border border-white/30" />
                  <span className="text-white">各部門（器官）</span>
                </div>
                <div className="flex items-center gap-2">
                  <div className="w-3 h-3 rounded-full bg-teal-600 border border-white/30" />
                  <span className="text-white">チーム・プロジェクト</span>
                </div>
              </>
            )}

            {(viewMode === 'gftd' || viewMode === 'integrated') && (
              <>
                <div className="text-cyan-400 font-semibold mb-1 mt-3">gftd.ai エコシステム</div>
                <div className="flex items-center gap-2">
                  <div className="w-3 h-3 rounded-full bg-indigo-800 border border-white/30" />
                  <span className="text-white">gftd.ai（中枢神経系）</span>
                </div>
                <div className="flex items-center gap-2">
                  <div className="w-3 h-3 rounded-full bg-blue-700 border border-white/30" />
                  <span className="text-white">Hickory DNS（神経伝達物質）</span>
                </div>
                <div className="flex items-center gap-2">
                  <div className="w-3 h-3 rounded-full bg-purple-700 border border-white/30" />
                  <span className="text-white">api.gftd.ai（内分泌系）</span>
                </div>
                <div className="flex items-center gap-2">
                  <div className="w-3 h-3 rounded-full bg-pink-700 border border-white/30" />
                  <span className="text-white">actor.gftd.ai（神経ネットワーク）</span>
                </div>
                <div className="flex items-center gap-2">
                  <div className="w-3 h-3 rounded-full bg-red-700 border border-white/30" />
                  <span className="text-white">Fly.io（循環系）</span>
                </div>
                <div className="flex items-center gap-2">
                  <div className="w-3 h-3 rounded-full bg-orange-600 border border-white/30" />
                  <span className="text-white">Vercel（表皮系）</span>
                </div>
                <div className="flex items-center gap-2">
                  <div className="w-3 h-3 rounded-full bg-amber-800 border border-white/30" />
                  <span className="text-white">Next.js（骨格系）</span>
                </div>
                <div className="flex items-center gap-2">
                  <div className="w-3 h-3 rounded-full bg-teal-700 border border-white/30" />
                  <span className="text-white">MCP（シナプス）</span>
                </div>
                <div className="flex items-center gap-2">
                  <div className="w-3 h-3 rounded-full bg-green-700 border border-white/30" />
                  <span className="text-white">Confluent（リンパ系）</span>
                </div>
                <div className="flex items-center gap-2">
                  <div className="w-3 h-3 rounded-full bg-yellow-600 border border-white/30" />
                  <span className="text-white">ksqlDB（代謝酵素）</span>
                </div>
                <div className="flex items-center gap-2">
                  <div className="w-3 h-3 rounded-full bg-gray-600 border border-white/30" />
                  <span className="text-white">GitHub/GitLab（DNA/RNA）</span>
                </div>
                <div className="flex items-center gap-2">
                  <div className="w-3 h-3 rounded-full bg-purple-600 border border-white/30" />
                  <span className="text-white">AI統合層（大脳皮質）</span>
                </div>
              </>
            )}
            
            {viewMode === 'integrated' && (
              <>
                <div className="text-purple-400 font-semibold mb-1 mt-3">統合連携</div>
                <div className="flex items-center gap-2">
                  <div className="w-8 h-1 bg-purple-300 border border-white/30 opacity-80" style={{borderStyle: 'dotted'}} />
                  <span className="text-white">理論-実装-組織橋渡し</span>
                </div>
              </>
            )}
          </div>
        </div>
        
        {/* サブネットワーク管理パネル */}
        {showSubNetworkPanel && (
          <div className="absolute top-4 left-4 w-80 bg-black/90 backdrop-blur-lg rounded-lg p-4 border border-white/10 max-h-96 overflow-y-auto">
            <div className="flex justify-between items-start mb-3">
              <h3 className="text-purple-400 font-bold text-lg">🕸️ サブネットワーク</h3>
              <button
                onClick={() => setShowSubNetworkPanel(false)}
                className="text-gray-400 hover:text-white text-xl"
              >
                ×
              </button>
            </div>

            {/* プリセットとおすすめビュー */}
            <div className="mb-4">
              <h4 className="text-cyan-400 font-semibold text-sm mb-2">プリセット & おすすめビュー:</h4>
              <div className="space-y-2">
                <button
                  onClick={applyPresetSubNetworks}
                  className="w-full px-3 py-2 bg-purple-500/30 border border-purple-400/50 rounded text-purple-300 hover:bg-purple-500/50 transition-all text-xs"
                >
                  🎯 プリセットサブネット適用
                </button>
                <div className="grid grid-cols-2 gap-1">
                  <button
                    onClick={() => applyRecommendedView('foundation')}
                    className="px-2 py-1 bg-green-500/20 border border-green-400/30 rounded text-green-300 hover:bg-green-500/40 transition-all text-xs"
                  >
                    🏗️ 基盤
                  </button>
                  <button
                    onClick={() => applyRecommendedView('development')}
                    className="px-2 py-1 bg-blue-500/20 border border-blue-400/30 rounded text-blue-300 hover:bg-blue-500/40 transition-all text-xs"
                  >
                    🚀 開発
                  </button>
                  <button
                    onClick={() => applyRecommendedView('integration')}
                    className="px-2 py-1 bg-yellow-500/20 border border-yellow-400/30 rounded text-yellow-300 hover:bg-yellow-500/40 transition-all text-xs"
                  >
                    🔗 統合
                  </button>
                  <button
                    onClick={() => applyRecommendedView('governance')}
                    className="px-2 py-1 bg-red-500/20 border border-red-400/30 rounded text-red-300 hover:bg-red-500/40 transition-all text-xs"
                  >
                    🏛️ 統治
                  </button>
                </div>
              </div>
            </div>

            {/* エッジタイプ選択 */}
            <div className="mb-4">
              <h4 className="text-cyan-400 font-semibold text-sm mb-2">エッジタイプで選択:</h4>
              <div className="max-h-24 overflow-y-auto">
                {getAvailableEdgeTypes().map(type => (
                  <button
                    key={type}
                    onClick={() => selectEdgesByType(type)}
                    className="block w-full text-left px-2 py-1 text-xs text-gray-300 hover:bg-white/10 rounded mb-1"
                  >
                    {type}
                  </button>
                ))}
              </div>
            </div>

            {/* 既存のサブネットワーク */}
            <div className="mb-4">
              <div className="flex items-center justify-between mb-2">
                <h4 className="text-cyan-400 font-semibold text-sm">
                  サブネットワーク一覧 ({subNetworks.length}):
                </h4>
                {subNetworks.length > 0 && (
                  <div className="flex gap-1">
                    <button
                      onClick={() => setActiveSubNetworks(subNetworks.map(sn => sn.id))}
                      className="text-xs px-2 py-1 bg-green-500/20 border border-green-400/30 rounded text-green-300 hover:bg-green-500/40"
                    >
                      全表示
                    </button>
                    <button
                      onClick={() => setActiveSubNetworks([])}
                      className="text-xs px-2 py-1 bg-gray-500/20 border border-gray-400/30 rounded text-gray-300 hover:bg-gray-500/40"
                    >
                      全非表示
                    </button>
                  </div>
                )}
              </div>
              {subNetworks.length === 0 ? (
                <p className="text-gray-400 text-xs">サブネットワークがありません</p>
              ) : (
                <div className="space-y-2 max-h-48 overflow-y-auto">
                  {subNetworks.map(subnet => (
                    <div
                      key={subnet.id}
                      className={`bg-white/5 rounded p-2 border transition-all ${
                        activeSubNetworks.includes(subnet.id)
                          ? 'border-white/20 bg-white/10'
                          : 'border-white/10'
                      }`}
                    >
                      <div className="flex items-center justify-between mb-1">
                        <div className="flex items-center gap-2">
                          <div
                            className="w-3 h-3 rounded-full border border-white/30"
                            style={{ backgroundColor: subnet.color }}
                          />
                          <span className="text-white text-sm font-medium">
                            {subnet.name}
                          </span>
                        </div>
                        <div className="flex gap-1">
                          <button
                            onClick={() => toggleSubNetworkVisibility(subnet.id)}
                            className={`text-xs px-2 py-1 rounded transition-all ${
                              activeSubNetworks.includes(subnet.id)
                                ? 'bg-green-500/40 text-green-200 border border-green-400/50'
                                : 'bg-gray-500/30 text-gray-300 border border-gray-400/30 hover:bg-gray-500/50'
                            }`}
                          >
                            {activeSubNetworks.includes(subnet.id) ? '表示中' : '非表示'}
                          </button>
                          <button
                            onClick={() => deleteSubNetwork(subnet.id)}
                            className="text-xs px-2 py-1 rounded bg-red-500/30 text-red-300 hover:bg-red-500/50 border border-red-400/30"
                          >
                            削除
                          </button>
                        </div>
                      </div>
                      <p className="text-gray-400 text-xs leading-relaxed mb-1">{subnet.description}</p>
                      <div className="text-xs text-gray-500">
                        <span className="text-gray-400">エッジタイプ ({subnet.edgeTypes.length}):</span>
                        <div className="mt-1 flex flex-wrap gap-1">
                          {subnet.edgeTypes.slice(0, 3).map((type, index) => (
                            <span key={index} className="bg-gray-700/50 px-1 py-0.5 rounded text-xs">
                              {type}
                            </span>
                          ))}
                          {subnet.edgeTypes.length > 3 && (
                            <span className="text-gray-500 text-xs">
                              +{subnet.edgeTypes.length - 3}
                            </span>
                          )}
                        </div>
                      </div>
                    </div>
                  ))}
                </div>
              )}
            </div>

            {/* 選択状態 */}
            {selectedEdges.length > 0 && (
              <div className="bg-cyan-500/20 rounded p-2 border border-cyan-400/30 mb-4">
                <p className="text-cyan-300 text-sm font-medium">
                  {selectedEdges.length}個のエッジを選択中
                </p>
                <div className="flex gap-2 mt-2">
                  <button
                    onClick={createSubNetwork}
                    className="px-3 py-1 bg-green-500/30 text-green-300 rounded text-xs hover:bg-green-500/50"
                  >
                    サブネット作成
                  </button>
                  <button
                    onClick={clearEdgeSelection}
                    className="px-3 py-1 bg-gray-500/30 text-gray-300 rounded text-xs hover:bg-gray-500/50"
                  >
                    選択クリア
                  </button>
                </div>
              </div>
            )}

            {/* クイック情報 */}
            <div className="bg-indigo-500/10 rounded p-2 border border-indigo-400/20">
              <h5 className="text-indigo-300 font-medium text-xs mb-1">💡 クイックガイド</h5>
              <div className="text-xs text-gray-400 space-y-1">
                <p>• <span className="text-purple-300">プリセット適用</span>でサブネット一覧を作成</p>
                <p>• <span className="text-green-300">おすすめビュー</span>で目的別表示</p>
                <p>• <span className="text-cyan-300">エッジ選択モード</span>で手動選択</p>
                <p>• 複数サブネット同時表示可能</p>
              </div>
              {activeSubNetworks.length > 0 && (
                <div className="mt-2 pt-2 border-t border-indigo-400/20">
                  <p className="text-indigo-300 text-xs">
                    現在 <span className="font-bold">{activeSubNetworks.length}</span> 個のサブネットを表示中
                  </p>
                </div>
              )}
            </div>
          </div>
        )}

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
                  <optgroup label="会社組織">
                    <option value="energy_source">経営陣（太陽）</option>
                    <option value="protective_layer">取締役会（大気圏）</option>
                    <option value="canopy_layer">執行役員（樹冠層）</option>
                    <option value="management_trunk">管理職（幹）</option>
                    <option value="photosynthetic_organ">エンジニアリング部（光合成器官）</option>
                    <option value="reproductive_organ">プロダクト部（花器官）</option>
                    <option value="seed_dispersal_organ">営業部（種子散布器官）</option>
                    <option value="pollination_organ">マーケティング部（受粉器官）</option>
                    <option value="root_system">人事部（根系）</option>
                    <option value="vascular_system">財務部（維管束）</option>
                    <option value="metabolic_system">オペレーション部（代謝系）</option>
                    <option value="growth_apex">イノベーション研究所（成長点）</option>
                    <option value="antibody_system">セキュリティチーム（抗体システム）</option>
                    <option value="cell_division">アジャイルチーム（細胞分裂）</option>
                    <option value="metabolic_pathway">プロジェクト（代謝経路）</option>
                    <option value="symbiotic_environment">顧客エコシステム（共生環境）</option>
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
                  <optgroup label="会社組織">
                    <option value="energy_governance">統治エネルギー</option>
                    <option value="strategic_direction">戦略方向</option>
                    <option value="execution_flow">実行フロー</option>
                    <option value="resource_allocation">リソース配分</option>
                    <option value="collaboration">連携</option>
                    <option value="talent_supply">人材供給</option>
                    <option value="financial_control">財務管理</option>
                    <option value="process_optimization">プロセス最適化</option>
                    <option value="compliance_oversight">法務審査</option>
                    <option value="research_collaboration">研究連携</option>
                    <option value="insights_delivery">インサイト提供</option>
                    <option value="security_integration">セキュリティ統合</option>
                    <option value="team_formation">チーム編成</option>
                    <option value="project_execution">プロジェクト実行</option>
                    <option value="knowledge_capture">ナレッジ蓄積</option>
                    <option value="knowledge_transfer">知識移転</option>
                    <option value="knowledge_sharing">知識共有</option>
                    <option value="customer_interaction">顧客接点</option>
                    <option value="feedback_loop">フィードバックループ</option>
                    <option value="market_insights">市場インサイト</option>
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
      
      {/* サブネットワーク作成モーダル */}
      {showSubNetworkModal && (
        <div className="fixed inset-0 bg-black/80 backdrop-blur-sm flex items-center justify-center z-50">
          <div className="bg-gradient-to-br from-slate-800 to-purple-900 rounded-lg p-6 w-96 max-w-full border border-white/20">
            <div className="flex justify-between items-center mb-4">
              <h3 className="text-purple-400 font-bold text-lg">🕸️ サブネットワーク作成</h3>
              <button
                onClick={() => setShowSubNetworkModal(false)}
                className="text-gray-400 hover:text-white text-xl"
              >
                ×
              </button>
            </div>
            
            <div className="space-y-4">
              <div>
                <label className="block text-purple-400 text-sm font-medium mb-1">名前:</label>
                <input
                  type="text"
                  value={subNetworkFormData.name || ''}
                  onChange={(e) => setSubNetworkFormData(prev => ({ ...prev, name: e.target.value }))}
                  className="w-full p-2 bg-white/10 border border-white/30 rounded text-white placeholder-gray-400"
                  placeholder="サブネットワーク名を入力"
                />
              </div>
              
              <div>
                <label className="block text-purple-400 text-sm font-medium mb-1">説明:</label>
                <textarea
                  value={subNetworkFormData.description || ''}
                  onChange={(e) => setSubNetworkFormData(prev => ({ ...prev, description: e.target.value }))}
                  className="w-full p-2 bg-white/10 border border-white/30 rounded text-white placeholder-gray-400 h-20 resize-none"
                  placeholder="サブネットワークの説明を入力してください"
                />
              </div>

              <div>
                <label className="block text-purple-400 text-sm font-medium mb-1">色:</label>
                <div className="flex items-center gap-2">
                  <input
                    type="color"
                    value={subNetworkFormData.color || '#666666'}
                    onChange={(e) => setSubNetworkFormData(prev => ({ ...prev, color: e.target.value }))}
                    className="w-12 h-8 border border-white/30 rounded cursor-pointer"
                    aria-label="サブネットワークの色を選択"
                  />
                  <input
                    type="text"
                    value={subNetworkFormData.color || '#666666'}
                    onChange={(e) => setSubNetworkFormData(prev => ({ ...prev, color: e.target.value }))}
                    className="flex-1 p-2 bg-white/10 border border-white/30 rounded text-white placeholder-gray-400"
                    placeholder="#666666"
                  />
                </div>
              </div>

              <div>
                <label className="block text-purple-400 text-sm font-medium mb-1">含まれるエッジタイプ:</label>
                <div className="bg-white/5 p-2 rounded border border-white/10 max-h-24 overflow-y-auto">
                  {subNetworkFormData.edgeTypes?.map((type, index) => (
                    <div key={index} className="text-gray-300 text-xs py-1">
                      • {type}
                    </div>
                  )) || <div className="text-gray-400 text-xs">エッジタイプがありません</div>}
                </div>
              </div>
              
              <div className="flex gap-2 justify-end">
                <button
                  onClick={() => setShowSubNetworkModal(false)}
                  className="px-4 py-2 bg-gray-500/20 border border-gray-400 rounded text-gray-400 hover:bg-gray-500/40 transition-all duration-300"
                >
                  キャンセル
                </button>
                <button
                  onClick={saveSubNetwork}
                  className="px-4 py-2 bg-purple-500 border border-purple-400 rounded text-white hover:bg-purple-600 transition-all duration-300"
                >
                  作成
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