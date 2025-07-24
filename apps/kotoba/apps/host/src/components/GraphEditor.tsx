import React, { useState, useEffect, useRef } from 'react'
import cytoscape from 'cytoscape'
import dagre from 'cytoscape-dagre'

// cytoscapeにdagreプラグインを登録
cytoscape.use(dagre as any)

/**
 * ASTノードの型定義
 */
interface ASTNode {
  id: string
  label: string
  type: string
  group: string
  level: number
}

/**
 * ASTエッジの型定義
 */
interface ASTEdge {
  source: string
  target: string
  weight: number
}

export type GraphEditorProps = {
  /**
   * ASTデータ
   */
  astData?: { nodes: ASTNode[], edges: ASTEdge[] }
  /**
   * 初期グラフタイプ
   */
  initialGraphType?: 'ast' | 'network' | 'hierarchy' | 'circular'
  /**
   * グラフの高さ
   */
  height?: string
  /**
   * グラフデータ更新コールバック
   */
  onGraphUpdate?: (graphData: { nodes: ASTNode[], edges: ASTEdge[] }) => void
};

export function GraphEditor({ 
  astData: externalASTData,
  initialGraphType = 'ast',
  height = '600px',
  onGraphUpdate
}: GraphEditorProps) {
  const [graphType, setGraphType] = useState<'ast' | 'network' | 'hierarchy' | 'circular'>(initialGraphType)
  const [astData, setAstData] = useState<{ nodes: ASTNode[], edges: ASTEdge[] }>({ nodes: [], edges: [] })
  const [isModified, setIsModified] = useState(false)
  const [selectedNode, setSelectedNode] = useState<ASTNode | null>(null)
  const [editingNode, setEditingNode] = useState<ASTNode | null>(null)
  const [showNodeEditor, setShowNodeEditor] = useState(false)
  const [newNodeData, setNewNodeData] = useState<Partial<ASTNode>>({
    label: '',
    type: 'node',
    group: 'A',
    level: 0
  })
  
  const sampleData = {
    nodes: [
      { id: '1', label: 'Node 1', type: 'node', group: 'A', level: 0 },
      { id: '2', label: 'Node 2', type: 'node', group: 'A', level: 1 },
      { id: '3', label: 'Node 3', type: 'node', group: 'B', level: 1 },
      { id: '4', label: 'Node 4', type: 'node', group: 'B', level: 2 },
      { id: '5', label: 'Node 5', type: 'node', group: 'C', level: 2 }
    ],
    edges: [
      { source: '1', target: '2', weight: 1 },
      { source: '2', target: '3', weight: 2 },
      { source: '3', target: '4', weight: 1 },
      { source: '4', target: '5', weight: 3 },
      { source: '1', target: '5', weight: 2 }
    ]
  }
  const cyRef = useRef<cytoscape.Core | null>(null)
  const containerRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    console.log('Graph Editor Component: Component mounted.')
    
    // サンプルASTデータを設定
    const sampleASTData = {
      nodes: [
        { id: 'doc', label: 'Document', type: 'doc', group: 'A', level: 0 },
        { id: 'h1', label: 'Kotoba Editor', type: 'heading_1', group: 'A', level: 1 },
        { id: 'p1', label: 'This is a ProseMirror-based...', type: 'paragraph', group: 'B', level: 1 },
        { id: 'h2', label: 'Features', type: 'heading_2', group: 'A', level: 1 },
        { id: 'ul1', label: 'List', type: 'bullet_list', group: 'C', level: 1 },
        { id: 'li1', label: 'Rich text editing', type: 'list_item', group: 'C', level: 2 },
        { id: 'li2', label: 'Markdown support', type: 'list_item', group: 'C', level: 2 },
        { id: 'li3', label: 'AST generation', type: 'list_item', group: 'C', level: 2 },
        { id: 'h3', label: 'Lists', type: 'heading_3', group: 'A', level: 1 },
        { id: 'ul2', label: 'Nested List', type: 'bullet_list', group: 'C', level: 1 },
        { id: 'li4', label: 'Item 1', type: 'list_item', group: 'C', level: 2 },
        { id: 'li5', label: 'Item 2', type: 'list_item', group: 'C', level: 2 },
        { id: 'ul3', label: 'Sub List', type: 'bullet_list', group: 'C', level: 2 },
        { id: 'li6', label: 'Sub-item 2.1', type: 'list_item', group: 'C', level: 3 },
        { id: 'li7', label: 'Sub-item 2.2', type: 'list_item', group: 'C', level: 3 }
      ],
      edges: [
        { source: 'doc', target: 'h1', weight: 1 },
        { source: 'doc', target: 'p1', weight: 1 },
        { source: 'doc', target: 'h2', weight: 1 },
        { source: 'doc', target: 'ul1', weight: 1 },
        { source: 'ul1', target: 'li1', weight: 1 },
        { source: 'ul1', target: 'li2', weight: 1 },
        { source: 'ul1', target: 'li3', weight: 1 },
        { source: 'doc', target: 'h3', weight: 1 },
        { source: 'doc', target: 'ul2', weight: 1 },
        { source: 'ul2', target: 'li4', weight: 1 },
        { source: 'ul2', target: 'li5', weight: 1 },
        { source: 'li5', target: 'ul3', weight: 1 },
        { source: 'ul3', target: 'li6', weight: 1 },
        { source: 'ul3', target: 'li7', weight: 1 }
      ]
    }
    
    setAstData(sampleASTData)
    initializeGraph(sampleASTData)
  }, [])

  // 外部からASTデータが渡された場合の処理
  useEffect(() => {
    if (externalASTData && externalASTData.nodes.length > 0) {
      console.log('Graph Editor: Received external AST data', externalASTData)
      setAstData(externalASTData)
      initializeGraph(externalASTData)
    }
  }, [externalASTData])

  /**
   * cytoscapeグラフを初期化
   */
  const initializeGraph = (data: { nodes: ASTNode[], edges: ASTEdge[] }) => {
    if (!containerRef.current) return

    // 既存のグラフを破棄
    if (cyRef.current) {
      cyRef.current.destroy()
    }

    // cytoscapeの要素を作成
    const elements = {
      nodes: data.nodes.map(node => ({
        group: 'nodes' as const,
        data: {
          id: node.id,
          label: node.label,
          type: node.type,
          group: node.group,
          level: node.level
        }
      })),
      edges: data.edges.map(edge => ({
        group: 'edges' as const,
        data: {
          id: `${edge.source}-${edge.target}`,
          source: edge.source,
          target: edge.target,
          weight: edge.weight
        }
      }))
    }

    // cytoscapeインスタンスを作成
    cyRef.current = cytoscape({
      container: containerRef.current,
      elements: elements,
      style: [
        {
          selector: 'node',
          style: {
            'background-color': (ele: any) => {
              const group = ele.data('group')
              switch (group) {
                case 'A': return '#10b981' // 緑 - 見出し
                case 'B': return '#3b82f6' // 青 - 段落
                case 'C': return '#f59e0b' // オレンジ - リスト
                case 'D': return '#8b5cf6' // 紫 - コードブロック
                case 'E': return '#6b7280' // グレー - テキスト
                default: return '#374151'
              }
            },
            'label': 'data(label)',
            'color': '#ffffff',
            'font-size': '12px',
            'font-weight': 'bold',
            'text-wrap': 'wrap',
            'text-max-width': '120px',
            'text-valign': 'center',
            'text-halign': 'center',
            'width': (ele: any) => {
              const level = ele.data('level')
              return Math.max(60, 80 - level * 5)
            },
            'height': (ele: any) => {
              const level = ele.data('level')
              return Math.max(40, 50 - level * 3)
            },
            'border-width': 2,
            'border-color': '#1f2937',
            'border-opacity': 0.8
          }
        },
        {
          selector: 'edge',
          style: {
            'width': 'data(weight)',
            'line-color': '#6b7280',
            'target-arrow-color': '#6b7280',
            'target-arrow-shape': 'triangle',
            'curve-style': 'bezier',
            'opacity': 0.8
          }
        },
        {
          selector: 'node:selected',
          style: {
            'border-width': 4,
            'border-color': '#ef4444',
            'border-opacity': 1
          }
        },
        {
          selector: 'edge:selected',
          style: {
            'width': 'data(weight)',
            'line-color': '#ef4444',
            'target-arrow-color': '#ef4444',
            'opacity': 1
          }
        }
      ],
      layout: getLayoutConfig(graphType)
    })

    // イベントリスナーを追加
    cyRef.current.on('tap', 'node', function(evt) {
      const node = evt.target
      const nodeData = node.data()
      const selectedNodeData = {
        id: nodeData.id,
        label: nodeData.label,
        type: nodeData.type,
        group: nodeData.group,
        level: nodeData.level
      }
      setSelectedNode(selectedNodeData)
      console.log('Node clicked:', nodeData)
    })

    cyRef.current.on('tap', 'edge', function(evt) {
      const edge = evt.target
      console.log('Edge clicked:', edge.data())
    })

    // グラフをフィット
    cyRef.current.fit()
  }

  /**
   * レイアウト設定を取得
   */
  const getLayoutConfig = (type: string) => {
    switch (type) {
      case 'ast':
        return {
          name: 'dagre',
          rankDir: 'TB',
          nodeDimensionsIncludeLabels: true,
          padding: 50,
          animate: true,
          animationDuration: 1000
        }
      case 'hierarchy':
        return {
          name: 'dagre',
          rankDir: 'LR',
          nodeDimensionsIncludeLabels: true,
          padding: 50,
          animate: true,
          animationDuration: 1000
        }
      case 'circular':
        return {
          name: 'circle',
          padding: 50,
          animate: true,
          animationDuration: 1000
        }
      case 'network':
      default:
        return {
          name: 'cose',
          padding: 50,
          animate: true,
          animationDuration: 1000,
          nodeDimensionsIncludeLabels: true
        }
    }
  }

  /**
   * グラフタイプを変更
   */
  const handleGraphTypeChange = (newType: 'ast' | 'network' | 'hierarchy' | 'circular') => {
    setGraphType(newType)
    if (cyRef.current) {
      const layout = cyRef.current.layout(getLayoutConfig(newType))
      layout.run()
    }
  }

  /**
   * ノードを追加
   */
  const addNode = () => {
    if (!newNodeData.label.trim()) return

    const newNode: ASTNode = {
      id: `node_${Date.now()}`,
      label: newNodeData.label,
      type: newNodeData.type || 'node',
      group: newNodeData.group || 'A',
      level: newNodeData.level || 0
    }

    const updatedData = {
      nodes: [...astData.nodes, newNode],
      edges: [...astData.edges]
    }

    setAstData(updatedData)
    setIsModified(true)
    setNewNodeData({ label: '', type: 'node', group: 'A', level: 0 })
    setShowNodeEditor(false)

    // グラフを更新
    initializeGraph(updatedData)

    // コールバックを呼び出し
    if (onGraphUpdate) {
      onGraphUpdate(updatedData)
    }
  }

  /**
   * ノードを削除
   */
  const deleteNode = (nodeId: string) => {
    const updatedData = {
      nodes: astData.nodes.filter(node => node.id !== nodeId),
      edges: astData.edges.filter(edge => edge.source !== nodeId && edge.target !== nodeId)
    }

    setAstData(updatedData)
    setIsModified(true)
    setSelectedNode(null)

    // グラフを更新
    initializeGraph(updatedData)

    // コールバックを呼び出し
    if (onGraphUpdate) {
      onGraphUpdate(updatedData)
    }
  }

  /**
   * ノードを編集
   */
  const editNode = (nodeId: string, updates: Partial<ASTNode>) => {
    const updatedData = {
      nodes: astData.nodes.map(node => 
        node.id === nodeId ? { ...node, ...updates } : node
      ),
      edges: astData.edges
    }

    setAstData(updatedData)
    setIsModified(true)
    setEditingNode(null)

    // グラフを更新
    initializeGraph(updatedData)

    // コールバックを呼び出し
    if (onGraphUpdate) {
      onGraphUpdate(updatedData)
    }
  }

  /**
   * エッジを追加
   */
  const addEdge = (sourceId: string, targetId: string) => {
    const newEdge: ASTEdge = {
      source: sourceId,
      target: targetId,
      weight: 1
    }

    const updatedData = {
      nodes: astData.nodes,
      edges: [...astData.edges, newEdge]
    }

    setAstData(updatedData)
    setIsModified(true)

    // グラフを更新
    initializeGraph(updatedData)

    // コールバックを呼び出し
    if (onGraphUpdate) {
      onGraphUpdate(updatedData)
    }
  }

  /**
   * グラフを保存
   */
  const saveGraph = () => {
    if (onGraphUpdate) {
      onGraphUpdate(astData)
    }
    setIsModified(false)
  }

  /**
   * グラフをエクスポート
   */
  const exportGraph = () => {
    if (cyRef.current) {
      const png = cyRef.current.png({
        full: true,
        output: 'blob'
      })
      
      const url = URL.createObjectURL(png)
      const a = document.createElement('a')
      a.href = url
      a.download = `graph_${graphType}_${new Date().toISOString().slice(0, 10)}.png`
      a.click()
      URL.revokeObjectURL(url)
    }
  }

  /**
   * グラフデータをJSONとしてエクスポート
   */
  const exportGraphData = () => {
    const blob = new Blob([JSON.stringify(astData, null, 2)], { type: 'application/json' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `graph_data_${new Date().toISOString().slice(0, 10)}.json`
    a.click()
    URL.revokeObjectURL(url)
  }

  return (
    <div className="min-h-screen bg-gray-50 dark:bg-gray-900" data-testid="graph-editor">
      {/* Header */}
      <div className="bg-white dark:bg-gray-800 shadow-sm border-b border-gray-200 dark:border-gray-700">
        <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8">
          <div className="flex justify-between items-center py-4">
            <div className="flex items-center space-x-4">
              <h1 className="text-xl font-semibold text-gray-900 dark:text-gray-100">Kotoba Graph Editor</h1>
              <div className="flex items-center space-x-2">
                <select 
                  value={graphType} 
                  onChange={(e) => handleGraphTypeChange(e.target.value as 'ast' | 'network' | 'hierarchy' | 'circular')} 
                  className="block w-48 rounded-md border-gray-300 dark:border-gray-600 shadow-sm focus:border-indigo-500 focus:ring-indigo-500 dark:focus:border-indigo-400 dark:focus:ring-indigo-400 sm:text-sm bg-white dark:bg-gray-700 text-gray-900 dark:text-gray-100"
                >
                  <option value="ast">AST Tree</option>
                  <option value="hierarchy">Hierarchy</option>
                  <option value="circular">Circular</option>
                  <option value="network">Network</option>
                </select>
                {isModified && (
                  <span className="inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium bg-yellow-100 dark:bg-yellow-900 text-yellow-800 dark:text-yellow-200">
                    Modified
                  </span>
                )}
              </div>
            </div>
            
            <div className="flex items-center space-x-3">
              <button
                onClick={() => setShowNodeEditor(true)}
                className="inline-flex items-center px-3 py-2 border border-transparent text-sm leading-4 font-medium rounded-md text-white bg-indigo-600 hover:bg-indigo-700 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-indigo-500 dark:focus:ring-offset-gray-800 transition-colors duration-200"
              >
                <svg className="-ml-0.5 mr-2 h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 4v16m8-8H4" />
                </svg>
                Add Node
              </button>
              
              <button
                onClick={saveGraph}
                disabled={!isModified}
                className="inline-flex items-center px-3 py-2 border border-transparent text-sm leading-4 font-medium rounded-md text-white bg-green-600 hover:bg-green-700 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-green-500 dark:focus:ring-offset-gray-800 disabled:opacity-50 disabled:cursor-not-allowed transition-colors duration-200"
              >
                <svg className="-ml-0.5 mr-2 h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M8 7H5a2 2 0 00-2 2v9a2 2 0 002 2h14a2 2 0 002-2V9a2 2 0 00-2-2h-3m-1 4l-3 3m0 0l-3-3m3 3V4" />
                </svg>
                {isModified ? 'Save*' : 'Saved'}
              </button>
              
              <button
                onClick={exportGraph}
                className="inline-flex items-center px-3 py-2 border border-gray-300 dark:border-gray-600 shadow-sm text-sm leading-4 font-medium rounded-md text-gray-700 dark:text-gray-300 bg-white dark:bg-gray-700 hover:bg-gray-50 dark:hover:bg-gray-600 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-indigo-500 dark:focus:ring-offset-gray-800 transition-colors duration-200"
              >
                <svg className="-ml-0.5 mr-2 h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 10v6m0 0l-3-3m3 3l3-3m2 8H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z" />
                </svg>
                Export PNG
              </button>
              
              <button
                onClick={exportGraphData}
                className="inline-flex items-center px-3 py-2 border border-gray-300 dark:border-gray-600 shadow-sm text-sm leading-4 font-medium rounded-md text-gray-700 dark:text-gray-300 bg-white dark:bg-gray-700 hover:bg-gray-50 dark:hover:bg-gray-600 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-indigo-500 dark:focus:ring-offset-gray-800 transition-colors duration-200"
              >
                <svg className="-ml-0.5 mr-2 h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 19v-6a2 2 0 00-2-2H5a2 2 0 00-2 2v6a2 2 0 002 2h2a2 2 0 002-2zm0 0V9a2 2 0 012-2h2a2 2 0 012 2v10m-6 0a2 2 0 002 2h2a2 2 0 002-2m0 0V5a2 2 0 012-2h2a2 2 0 012 2v14a2 2 0 01-2 2h-2a2 2 0 01-2-2z" />
                </svg>
                Export JSON
              </button>
            </div>
          </div>
        </div>
      </div>

      {/* Main Content */}
      <div className="flex">
        {/* Graph Area */}
        <div className="flex-1">
          <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-6">
            <div className="bg-white dark:bg-gray-800 shadow-sm rounded-lg border border-gray-200 dark:border-gray-700">
              <div 
                ref={containerRef} 
                className="w-full rounded-lg"
                style={{ height }}
              />
            </div>
          </div>
        </div>

        {/* Sidebar */}
        <div className="w-80 bg-white dark:bg-gray-800 border-l border-gray-200 dark:border-gray-700">
          <div className="p-4">
            <h3 className="text-lg font-medium text-gray-900 dark:text-gray-100 mb-4">Graph Editor</h3>
            
            {/* Node Editor */}
            {showNodeEditor && (
              <div className="mb-6 p-4 bg-gray-50 dark:bg-gray-700 rounded-lg">
                <h4 className="text-sm font-medium text-gray-900 dark:text-gray-100 mb-3">Add New Node</h4>
                <div className="space-y-3">
                  <input
                    type="text"
                    placeholder="Node label"
                    value={newNodeData.label}
                    onChange={(e) => setNewNodeData({ ...newNodeData, label: e.target.value })}
                    className="block w-full rounded-md border-gray-300 dark:border-gray-600 shadow-sm focus:border-indigo-500 focus:ring-indigo-500 dark:focus:border-indigo-400 dark:focus:ring-indigo-400 sm:text-sm bg-white dark:bg-gray-600 text-gray-900 dark:text-gray-100"
                  />
                  <select
                    value={newNodeData.group}
                    onChange={(e) => setNewNodeData({ ...newNodeData, group: e.target.value })}
                    className="block w-full rounded-md border-gray-300 dark:border-gray-600 shadow-sm focus:border-indigo-500 focus:ring-indigo-500 dark:focus:border-indigo-400 dark:focus:ring-indigo-400 sm:text-sm bg-white dark:bg-gray-600 text-gray-900 dark:text-gray-100"
                  >
                    <option value="A">Group A (Green)</option>
                    <option value="B">Group B (Blue)</option>
                    <option value="C">Group C (Orange)</option>
                    <option value="D">Group D (Purple)</option>
                    <option value="E">Group E (Gray)</option>
                  </select>
                  <input
                    type="number"
                    placeholder="Level"
                    value={newNodeData.level}
                    onChange={(e) => setNewNodeData({ ...newNodeData, level: parseInt(e.target.value) || 0 })}
                    className="block w-full rounded-md border-gray-300 dark:border-gray-600 shadow-sm focus:border-indigo-500 focus:ring-indigo-500 dark:focus:border-indigo-400 dark:focus:ring-indigo-400 sm:text-sm bg-white dark:bg-gray-600 text-gray-900 dark:text-gray-100"
                  />
                  <div className="flex space-x-2">
                    <button
                      onClick={addNode}
                      className="flex-1 inline-flex justify-center items-center px-3 py-2 border border-transparent text-sm leading-4 font-medium rounded-md text-white bg-indigo-600 hover:bg-indigo-700 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-indigo-500 dark:focus:ring-offset-gray-800 transition-colors duration-200"
                    >
                      Add
                    </button>
                    <button
                      onClick={() => setShowNodeEditor(false)}
                      className="flex-1 inline-flex justify-center items-center px-3 py-2 border border-gray-300 dark:border-gray-600 shadow-sm text-sm leading-4 font-medium rounded-md text-gray-700 dark:text-gray-300 bg-white dark:bg-gray-700 hover:bg-gray-50 dark:hover:bg-gray-600 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-indigo-500 dark:focus:ring-offset-gray-800 transition-colors duration-200"
                    >
                      Cancel
                    </button>
                  </div>
                </div>
              </div>
            )}

            {/* Selected Node Info */}
            {selectedNode && (
              <div className="mb-6 p-4 bg-blue-50 dark:bg-blue-900/20 rounded-lg">
                <h4 className="text-sm font-medium text-gray-900 dark:text-gray-100 mb-3">Selected Node</h4>
                <div className="space-y-2 text-sm">
                  <div><span className="font-medium">ID:</span> {selectedNode.id}</div>
                  <div><span className="font-medium">Label:</span> {selectedNode.label}</div>
                  <div><span className="font-medium">Type:</span> {selectedNode.type}</div>
                  <div><span className="font-medium">Group:</span> {selectedNode.group}</div>
                  <div><span className="font-medium">Level:</span> {selectedNode.level}</div>
                </div>
                <div className="mt-3 flex space-x-2">
                  <button
                    onClick={() => setEditingNode(selectedNode)}
                    className="flex-1 inline-flex justify-center items-center px-2 py-1 border border-transparent text-xs leading-4 font-medium rounded-md text-white bg-blue-600 hover:bg-blue-700 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500 dark:focus:ring-offset-gray-800 transition-colors duration-200"
                  >
                    Edit
                  </button>
                  <button
                    onClick={() => deleteNode(selectedNode.id)}
                    className="flex-1 inline-flex justify-center items-center px-2 py-1 border border-transparent text-xs leading-4 font-medium rounded-md text-white bg-red-600 hover:bg-red-700 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-red-500 dark:focus:ring-offset-gray-800 transition-colors duration-200"
                  >
                    Delete
                  </button>
                </div>
              </div>
            )}

            {/* Node List */}
            <div className="mb-6">
              <h4 className="text-sm font-medium text-gray-900 dark:text-gray-100 mb-3">Nodes ({astData.nodes.length})</h4>
              <div className="space-y-2 max-h-60 overflow-y-auto">
                {astData.nodes.map((node) => (
                  <div
                    key={node.id}
                    className={`p-2 rounded border cursor-pointer transition-colors duration-200 ${
                      selectedNode?.id === node.id
                        ? 'border-indigo-500 bg-indigo-50 dark:bg-indigo-900/20'
                        : 'border-gray-200 dark:border-gray-600 hover:border-gray-300 dark:hover:border-gray-500'
                    }`}
                    onClick={() => setSelectedNode(node)}
                  >
                    <div className="text-sm font-medium text-gray-900 dark:text-gray-100">{node.label}</div>
                    <div className="text-xs text-gray-500 dark:text-gray-400">{node.type} • Group {node.group}</div>
                  </div>
                ))}
              </div>
            </div>

            {/* Graph Stats */}
            <div className="p-4 bg-gray-50 dark:bg-gray-700 rounded-lg">
              <h4 className="text-sm font-medium text-gray-900 dark:text-gray-100 mb-3">Graph Statistics</h4>
              <div className="space-y-2 text-sm">
                <div><span className="font-medium">Nodes:</span> {astData.nodes.length}</div>
                <div><span className="font-medium">Edges:</span> {astData.edges.length}</div>
                <div><span className="font-medium">Layout:</span> {graphType}</div>
                <div><span className="font-medium">Status:</span> {isModified ? 'Modified' : 'Saved'}</div>
              </div>
            </div>
          </div>
        </div>
      </div>

      {/* Footer */}
      <div className="bg-white dark:bg-gray-800 border-t border-gray-200 dark:border-gray-700">
        <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-3">
          <div className="flex items-center justify-between text-sm text-gray-500 dark:text-gray-400">
            <span>Graph Editor • Cytoscape.js</span>
            <span>{graphType} layout • {astData.nodes.length} nodes • {astData.edges.length} edges</span>
          </div>
        </div>
      </div>
    </div>
  )
} 