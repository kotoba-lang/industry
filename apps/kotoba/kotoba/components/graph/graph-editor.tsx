import React, { useState, useEffect, useRef } from 'react'
import cytoscape from 'cytoscape'
import dagre from 'cytoscape-dagre'
import edgehandles from 'cytoscape-edgehandles'
import { FloatingToolbar } from './components/FloatingToolbar'
import { ContextMenu } from './components/ContextMenu'

// cytoscapeにプラグインを登録
cytoscape.use(dagre as any)
cytoscape.use(edgehandles as any)

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
  const [edgeMode, setEdgeMode] = useState<'view' | 'create' | 'edit'>('view')
  const [selectedEdge, setSelectedEdge] = useState<any>(null)
  const [showEdgeEditor, setShowEdgeEditor] = useState(false)
  const [edgeWeight, setEdgeWeight] = useState(1)
  
  // フローティングツールバーとコンテキストメニューの状態
  const [showFloatingToolbar, setShowFloatingToolbar] = useState(false)
  const [toolbarPosition, setToolbarPosition] = useState({ x: 0, y: 0 })
  const [showContextMenu, setShowContextMenu] = useState(false)
  const [contextMenuPosition, setContextMenuPosition] = useState({ x: 0, y: 0 })
  const [selectedElement, setSelectedElement] = useState<any>(null)
  
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
  const edgeHandlesRef = useRef<any>(null)

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
              return Math.max(60, 80 - level * 5)
            },
            'border-width': 2,
            'border-color': '#ffffff',
            'border-opacity': 0.8
          }
        },
        {
          selector: 'edge',
          style: {
            'width': (ele: any) => Math.max(1, ele.data('weight')),
            'line-color': '#6b7280',
            'curve-style': 'bezier',
            'target-arrow-color': '#6b7280',
            'target-arrow-shape': 'triangle',
            'arrow-scale': 0.8,
            'label': 'data(weight)',
            'font-size': '10px',
            'text-rotation': 'autorotate',
            'text-margin-y': '-10px'
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
            'width': (ele: any) => Math.max(3, ele.data('weight') + 2),
            'line-color': '#ef4444',
            'target-arrow-color': '#ef4444'
          }
        }
      ],
      layout: {
        name: 'dagre',
        rankDir: 'TB',
        nodeSep: 50,
        edgeSep: 20,
        rankSep: 80
      }
    })

    // ノードクリックイベント
    cyRef.current.on('tap', 'node', (evt: any) => {
      const node = evt.target
      const nodeData = node.data()
      const position = node.renderedPosition()
      
      setSelectedNode({
        id: nodeData.id,
        label: nodeData.label,
        type: nodeData.type,
        group: nodeData.group,
        level: nodeData.level
      })
      setSelectedEdge(null)
      setShowEdgeEditor(false)
      
      // フローティングツールバーを表示
      setSelectedElement(node)
      setToolbarPosition({ x: position.x, y: position.y })
      setShowFloatingToolbar(true)
      setShowContextMenu(false)
    })

    // エッジクリックイベント
    cyRef.current.on('tap', 'edge', (evt: any) => {
      const edge = evt.target
      const position = edge.renderedMidpoint()
      
      setSelectedEdge(edge)
      setSelectedNode(null)
      setShowEdgeEditor(true)
      setEdgeWeight(edge.data('weight'))
      
      // フローティングツールバーを表示
      setSelectedElement(edge)
      setToolbarPosition({ x: position.x, y: position.y })
      setShowFloatingToolbar(true)
      setShowContextMenu(false)
    })

    // 背景クリックイベント
    cyRef.current.on('tap', (evt: any) => {
      if (evt.target === cyRef.current) {
        setSelectedNode(null)
        setSelectedEdge(null)
        setShowEdgeEditor(false)
        setShowFloatingToolbar(false)
        setShowContextMenu(false)
      }
    })

    // 右クリックイベント（コンテキストメニュー）
    cyRef.current.on('cxttap', (evt: any) => {
      const position = evt.renderedPosition || evt.cyRenderedPosition
      setContextMenuPosition({ x: position.x, y: position.y })
      setShowContextMenu(true)
      setShowFloatingToolbar(false)
    })

    // EdgeHandlesプラグインを初期化
    edgeHandlesRef.current = cyRef.current.edgehandles({
      snap: true,
      noEdgeEventsInDraw: true,
      disableBrowserGestures: true,
      handleNodes: 'node',
      handlePosition: 'middle middle',
      handleInDrawMode: false,
      edgeType: function() {
        return 'flat'
      },
      complete: function(sourceNode: any, targetNode: any, addedEles: any) {
        // 新しいエッジが作成された時の処理
        const newEdge = addedEles[0]
        const sourceId = sourceNode.id()
        const targetId = targetNode.id()
        
        // 重複エッジをチェック
        const existingEdge = cyRef.current?.getElementById(`${sourceId}-${targetId}`)
        if (existingEdge && existingEdge.length > 0) {
          addedEles.remove()
          return
        }

        // 新しいエッジデータを作成
        const newEdgeData = {
          source: sourceId,
          target: targetId,
          weight: 1
        }

        // ASTデータを更新
        const updatedEdges = [...astData.edges, newEdgeData]
        const updatedAstData = { ...astData, edges: updatedEdges }
        setAstData(updatedAstData)
        setIsModified(true)

        // コールバックを呼び出し
        if (onGraphUpdate) {
          onGraphUpdate(updatedAstData)
        }

        console.log('New edge created:', newEdgeData)
      }
    })

    // レイアウトを適用
    applyLayout()
  }

  /**
   * レイアウトを適用
   */
  const applyLayout = () => {
    if (!cyRef.current) return

    const layoutOptions: any = {
      name: 'dagre',
      rankDir: 'TB',
      nodeSep: 50,
      edgeSep: 20,
      rankSep: 80
    }

    switch (graphType) {
      case 'ast':
        layoutOptions.name = 'dagre'
        layoutOptions.rankDir = 'TB'
        break
      case 'hierarchy':
        layoutOptions.name = 'dagre'
        layoutOptions.rankDir = 'LR'
        break
      case 'circular':
        layoutOptions.name = 'circle'
        layoutOptions.fit = true
        layoutOptions.padding = 50
        break
      case 'network':
        layoutOptions.name = 'cose'
        layoutOptions.nodeRepulsion = 4500
        layoutOptions.idealEdgeLength = 50
        layoutOptions.edgeElasticity = 0.45
        layoutOptions.nestingFactor = 0.1
        layoutOptions.gravity = 80
        layoutOptions.numIter = 2500
        layoutOptions.initialTemp = 200
        layoutOptions.coolingFactor = 0.95
        layoutOptions.minTemp = 1.0
        break
    }

    const layout = cyRef.current.layout(layoutOptions)
    layout.run()
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

    const updatedNodes = [...astData.nodes, newNode]
    const updatedAstData = { ...astData, nodes: updatedNodes }
    setAstData(updatedAstData)
    setIsModified(true)

    // グラフを更新
    if (cyRef.current) {
      cyRef.current.add({
        group: 'nodes',
        data: {
          id: newNode.id,
          label: newNode.label,
          type: newNode.type,
          group: newNode.group,
          level: newNode.level
        }
      })
      applyLayout()
    }

    // フォームをリセット
    setNewNodeData({
      label: '',
      type: 'node',
      group: 'A',
      level: 0
    })

    // コールバックを呼び出し
    if (onGraphUpdate) {
      onGraphUpdate(updatedAstData)
    }
  }

  /**
   * ノードを削除
   */
  const deleteNode = () => {
    if (!selectedNode) return

    // 関連するエッジも削除
    const updatedEdges = astData.edges.filter(
      edge => edge.source !== selectedNode.id && edge.target !== selectedNode.id
    )
    const updatedNodes = astData.nodes.filter(node => node.id !== selectedNode.id)
    const updatedAstData = { nodes: updatedNodes, edges: updatedEdges }
    setAstData(updatedAstData)
    setIsModified(true)

    // グラフから削除
    if (cyRef.current) {
      cyRef.current.remove(`#${selectedNode.id}`)
      applyLayout()
    }

    setSelectedNode(null)
    setShowNodeEditor(false)

    // コールバックを呼び出し
    if (onGraphUpdate) {
      onGraphUpdate(updatedAstData)
    }
  }

  /**
   * ノードを編集
   */
  const editNode = () => {
    if (!editingNode) return

    const updatedNodes = astData.nodes.map(node =>
      node.id === editingNode.id ? editingNode : node
    )
    const updatedAstData = { ...astData, nodes: updatedNodes }
    setAstData(updatedAstData)
    setIsModified(true)

    // グラフを更新
    if (cyRef.current) {
      const cyNode = cyRef.current.getElementById(editingNode.id)
      cyNode.data({
        label: editingNode.label,
        type: editingNode.type,
        group: editingNode.group,
        level: editingNode.level
      })
    }

    setEditingNode(null)
    setShowNodeEditor(false)

    // コールバックを呼び出し
    if (onGraphUpdate) {
      onGraphUpdate(updatedAstData)
    }
  }

  /**
   * エッジの重みを更新
   */
  const updateEdgeWeight = () => {
    if (!selectedEdge) return

    const sourceId = selectedEdge.data('source')
    const targetId = selectedEdge.data('target')
    
    // ASTデータを更新
    const updatedEdges = astData.edges.map(edge =>
      edge.source === sourceId && edge.target === targetId
        ? { ...edge, weight: edgeWeight }
        : edge
    )
    const updatedAstData = { ...astData, edges: updatedEdges }
    setAstData(updatedAstData)
    setIsModified(true)

    // グラフを更新
    selectedEdge.data('weight', edgeWeight)

    setShowEdgeEditor(false)
    setSelectedEdge(null)

    // コールバックを呼び出し
    if (onGraphUpdate) {
      onGraphUpdate(updatedAstData)
    }
  }

  /**
   * エッジを削除
   */
  const deleteEdge = () => {
    if (!selectedEdge) return

    const sourceId = selectedEdge.data('source')
    const targetId = selectedEdge.data('target')
    
    // ASTデータを更新
    const updatedEdges = astData.edges.filter(
      edge => !(edge.source === sourceId && edge.target === targetId)
    )
    const updatedAstData = { ...astData, edges: updatedEdges }
    setAstData(updatedAstData)
    setIsModified(true)

    // グラフから削除
    selectedEdge.remove()

    setShowEdgeEditor(false)
    setSelectedEdge(null)

    // コールバックを呼び出し
    if (onGraphUpdate) {
      onGraphUpdate(updatedAstData)
    }
  }

  /**
   * グラフを保存
   */
  const saveGraph = () => {
    setIsModified(false)
    console.log('Graph saved:', astData)
  }

  /**
   * グラフをエクスポート（PNG）
   */
  const exportGraph = () => {
    if (!cyRef.current) return

    const png = cyRef.current.png({
      full: true,
      quality: 1,
      output: 'blob'
    })

    const url = URL.createObjectURL(png)
    const a = document.createElement('a')
    a.href = url
    a.download = 'graph.png'
    a.click()
    URL.revokeObjectURL(url)
  }

  /**
   * グラフデータをエクスポート（JSON）
   */
  const exportGraphData = () => {
    const blob = new Blob([JSON.stringify(astData, null, 2)], { type: 'application/json' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = 'graph_data.json'
    a.click()
    URL.revokeObjectURL(url)
  }

  /**
   * エッジモードを切り替え
   */
  const toggleEdgeMode = () => {
    if (!edgeHandlesRef.current) return

    if (edgeMode === 'view') {
      setEdgeMode('create')
      edgeHandlesRef.current.enable()
    } else {
      setEdgeMode('view')
      edgeHandlesRef.current.disable()
    }
  }

  // グラフタイプが変更された時の処理
  useEffect(() => {
    applyLayout()
  }, [graphType])

  return (
    <div className="graph-editor" data-testid="graph-editor">
      {/* Header */}
      <div className="bg-white dark:bg-gray-800 shadow-sm border-b border-gray-200 dark:border-gray-700">
        <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8">
          <div className="flex justify-between items-center py-4">
            <div className="flex items-center space-x-4">
              <h1 className="text-xl font-semibold text-gray-900 dark:text-gray-100">Graph Editor</h1>
              {isModified && (
                <span className="inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium bg-yellow-100 dark:bg-yellow-900 text-yellow-800 dark:text-yellow-200">
                  Modified
                </span>
              )}
            </div>
            
            <div className="flex items-center space-x-3">
              {/* レイアウト選択 */}
              <select
                value={graphType}
                onChange={(e) => setGraphType(e.target.value as any)}
                className="block rounded-md border-gray-300 dark:border-gray-600 shadow-sm focus:border-indigo-500 focus:ring-indigo-500 dark:focus:border-indigo-400 dark:focus:ring-indigo-400 sm:text-sm bg-white dark:bg-gray-700 text-gray-900 dark:text-gray-100"
              >
                <option value="ast">AST Tree</option>
                <option value="hierarchy">Hierarchy</option>
                <option value="circular">Circular</option>
                <option value="network">Network</option>
              </select>

              {/* エッジ作成モード */}
              <button
                onClick={toggleEdgeMode}
                className={`inline-flex items-center px-3 py-2 border text-sm leading-4 font-medium rounded-md transition-colors duration-200 ${
                  edgeMode === 'create'
                    ? 'border-red-300 text-red-700 bg-red-50 dark:border-red-600 dark:text-red-300 dark:bg-red-900'
                    : 'border-gray-300 text-gray-700 bg-white dark:border-gray-600 dark:text-gray-300 dark:bg-gray-700 hover:bg-gray-50 dark:hover:bg-gray-600'
                }`}
              >
                <svg className="-ml-0.5 mr-2 h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M13 7h8m0 0v8m0-8l-8 8-4-4-6 6" />
                </svg>
                {edgeMode === 'create' ? 'Cancel Edge' : 'Create Edge'}
              </button>

              <button
                onClick={saveGraph}
                disabled={!isModified}
                className="inline-flex items-center px-3 py-2 border border-transparent text-sm leading-4 font-medium rounded-md text-white bg-indigo-600 hover:bg-indigo-700 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-indigo-500 disabled:opacity-50 disabled:cursor-not-allowed transition-colors duration-200"
              >
                <svg className="-ml-0.5 mr-2 h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M8 7H5a2 2 0 00-2 2v9a2 2 0 002 2h14a2 2 0 002-2V9a2 2 0 00-2-2h-3m-1 4l-3 3m0 0l-3-3m3 3V4" />
                </svg>
                {isModified ? 'Save*' : 'Saved'}
              </button>
              
              <button
                onClick={exportGraph}
                className="inline-flex items-center px-3 py-2 border border-transparent text-sm leading-4 font-medium rounded-md text-white bg-green-600 hover:bg-green-700 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-green-500 transition-colors duration-200"
              >
                <svg className="-ml-0.5 mr-2 h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M4 16l4.586-4.586a2 2 0 012.828 0L16 16m-2-2l1.586-1.586a2 2 0 012.828 0L20 14m-6-6h.01M6 20h12a2 2 0 002-2V6a2 2 0 00-2-2H6a2 2 0 00-2 2v12a2 2 0 002 2z" />
                </svg>
                Export PNG
              </button>

              <button
                onClick={exportGraphData}
                className="inline-flex items-center px-3 py-2 border border-transparent text-sm leading-4 font-medium rounded-md text-white bg-purple-600 hover:bg-purple-700 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-purple-500 transition-colors duration-200"
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
      <div className="flex h-screen">
        {/* Graph Container */}
        <div className="flex-1 relative">
          <div 
            ref={containerRef} 
            style={{ 
              height, 
              width: '100%'
            }}
            className="bg-gray-50 dark:bg-gray-900 graph-grid-pattern"
          />
          
          {/* エッジ作成モードのインジケーター */}
          {edgeMode === 'create' && (
            <div className="absolute top-4 left-4 bg-red-100 dark:bg-red-900 border border-red-300 dark:border-red-600 rounded-lg px-4 py-2">
              <div className="flex items-center text-red-800 dark:text-red-200">
                <svg className="w-4 h-4 mr-2" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M13 7h8m0 0v8m0-8l-8 8-4-4-6 6" />
                </svg>
                <span className="text-sm font-medium">Edge Creation Mode - Click and drag from one node to another</span>
              </div>
            </div>
          )}

          {/* フローティングツールバー */}
          {showFloatingToolbar && selectedElement && (
            <FloatingToolbar
              x={toolbarPosition.x}
              y={toolbarPosition.y}
              type={selectedElement.isNode() ? 'node' : 'edge'}
              onDelete={() => {
                if (selectedElement.isNode()) {
                  deleteNode()
                } else {
                  deleteEdge()
                }
                setShowFloatingToolbar(false)
              }}
              onEdit={() => {
                if (selectedElement.isNode()) {
                  setEditingNode(selectedNode)
                  setShowNodeEditor(true)
                }
                setShowFloatingToolbar(false)
              }}
              onDuplicate={() => {
                if (selectedElement.isNode()) {
                  // ノード複製機能を実装
                  console.log('Duplicate node:', selectedElement.id())
                }
                setShowFloatingToolbar(false)
              }}
              onColorChange={() => {
                // 色変更機能を実装
                console.log('Change color for:', selectedElement.id())
                setShowFloatingToolbar(false)
              }}
              onZoomTo={() => {
                cyRef.current?.fit(selectedElement)
                setShowFloatingToolbar(false)
              }}
              onExpand={() => {
                // 展開機能を実装
                console.log('Expand:', selectedElement.id())
                setShowFloatingToolbar(false)
              }}
            />
          )}

          {/* コンテキストメニュー */}
          {showContextMenu && (
            <ContextMenu
              x={contextMenuPosition.x}
              y={contextMenuPosition.y}
              onAddNode={() => {
                // 指定位置にノードを追加
                console.log('Add node at:', contextMenuPosition)
                setShowContextMenu(false)
              }}
              onAddEdge={() => {
                setEdgeMode('create')
                setShowContextMenu(false)
              }}
              onPaste={() => {
                // ペースト機能を実装
                console.log('Paste')
                setShowContextMenu(false)
              }}
              onSelectAll={() => {
                cyRef.current?.elements().select()
                setShowContextMenu(false)
              }}
              onClearSelection={() => {
                cyRef.current?.elements().unselect()
                setShowContextMenu(false)
              }}
              onZoomIn={() => {
                cyRef.current?.zoom({ level: cyRef.current.zoom() * 1.2 })
                setShowContextMenu(false)
              }}
              onZoomOut={() => {
                cyRef.current?.zoom({ level: cyRef.current.zoom() * 0.8 })
                setShowContextMenu(false)
              }}
              onFitToView={() => {
                cyRef.current?.fit()
                setShowContextMenu(false)
              }}
              onResetView={() => {
                cyRef.current?.reset()
                setShowContextMenu(false)
              }}
              onClose={() => setShowContextMenu(false)}
            />
          )}
        </div>

        {/* Sidebar */}
        <div className="w-80 bg-white dark:bg-gray-800 border-l border-gray-200 dark:border-gray-700 overflow-y-auto">
          <div className="p-4">
            {/* ノード追加セクション */}
            <div className="mb-6">
              <h3 className="text-lg font-medium text-gray-900 dark:text-gray-100 mb-3">Add Node</h3>
              <div className="space-y-3">
                <input
                  type="text"
                  value={newNodeData.label}
                  onChange={(e) => setNewNodeData({ ...newNodeData, label: e.target.value })}
                  placeholder="Node label"
                  className="block w-full rounded-md border-gray-300 dark:border-gray-600 shadow-sm focus:border-indigo-500 focus:ring-indigo-500 dark:focus:border-indigo-400 dark:focus:ring-indigo-400 sm:text-sm bg-white dark:bg-gray-700 text-gray-900 dark:text-gray-100"
                />
                <select
                  value={newNodeData.group}
                  onChange={(e) => setNewNodeData({ ...newNodeData, group: e.target.value })}
                  className="block w-full rounded-md border-gray-300 dark:border-gray-600 shadow-sm focus:border-indigo-500 focus:ring-indigo-500 dark:focus:border-indigo-400 dark:focus:ring-indigo-400 sm:text-sm bg-white dark:bg-gray-700 text-gray-900 dark:text-gray-100"
                >
                  <option value="A">Group A (Green)</option>
                  <option value="B">Group B (Blue)</option>
                  <option value="C">Group C (Orange)</option>
                  <option value="D">Group D (Purple)</option>
                  <option value="E">Group E (Gray)</option>
                </select>
                <input
                  type="number"
                  value={newNodeData.level}
                  onChange={(e) => setNewNodeData({ ...newNodeData, level: parseInt(e.target.value) })}
                  placeholder="Level"
                  className="block w-full rounded-md border-gray-300 dark:border-gray-600 shadow-sm focus:border-indigo-500 focus:ring-indigo-500 dark:focus:border-indigo-400 dark:focus:ring-indigo-400 sm:text-sm bg-white dark:bg-gray-700 text-gray-900 dark:text-gray-100"
                />
                <button
                  onClick={addNode}
                  disabled={!newNodeData.label.trim()}
                  className="w-full inline-flex justify-center items-center px-3 py-2 border border-transparent text-sm leading-4 font-medium rounded-md text-white bg-indigo-600 hover:bg-indigo-700 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-indigo-500 disabled:opacity-50 disabled:cursor-not-allowed transition-colors duration-200"
                >
                  Add Node
                </button>
              </div>
            </div>

            {/* 選択されたノードの詳細 */}
            {selectedNode && (
              <div className="mb-6 p-4 bg-gray-50 dark:bg-gray-700 rounded-lg">
                <h3 className="text-lg font-medium text-gray-900 dark:text-gray-100 mb-3">Selected Node</h3>
                <div className="space-y-2 text-sm">
                  <div><span className="font-medium">ID:</span> {selectedNode.id}</div>
                  <div><span className="font-medium">Label:</span> {selectedNode.label}</div>
                  <div><span className="font-medium">Type:</span> {selectedNode.type}</div>
                  <div><span className="font-medium">Group:</span> {selectedNode.group}</div>
                  <div><span className="font-medium">Level:</span> {selectedNode.level}</div>
                </div>
                <div className="mt-4 space-y-2">
                  <button
                    onClick={() => {
                      setEditingNode(selectedNode)
                      setShowNodeEditor(true)
                    }}
                    className="w-full inline-flex justify-center items-center px-3 py-2 border border-gray-300 dark:border-gray-600 shadow-sm text-sm leading-4 font-medium rounded-md text-gray-700 dark:text-gray-300 bg-white dark:bg-gray-700 hover:bg-gray-50 dark:hover:bg-gray-600 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-indigo-500 dark:focus:ring-offset-gray-800 transition-colors duration-200"
                  >
                    Edit Node
                  </button>
                  <button
                    onClick={deleteNode}
                    className="w-full inline-flex justify-center items-center px-3 py-2 border border-transparent text-sm leading-4 font-medium rounded-md text-white bg-red-600 hover:bg-red-700 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-red-500 transition-colors duration-200"
                  >
                    Delete Node
                  </button>
                </div>
              </div>
            )}

            {/* ノード編集フォーム */}
            {showNodeEditor && editingNode && (
              <div className="mb-6 p-4 bg-blue-50 dark:bg-blue-900 rounded-lg">
                <h3 className="text-lg font-medium text-gray-900 dark:text-gray-100 mb-3">Edit Node</h3>
                <div className="space-y-3">
                  <input
                    type="text"
                    value={editingNode.label}
                    onChange={(e) => setEditingNode({ ...editingNode, label: e.target.value })}
                    className="block w-full rounded-md border-gray-300 dark:border-gray-600 shadow-sm focus:border-indigo-500 focus:ring-indigo-500 dark:focus:border-indigo-400 dark:focus:ring-indigo-400 sm:text-sm bg-white dark:bg-gray-700 text-gray-900 dark:text-gray-100"
                  />
                  <select
                    value={editingNode.group}
                    onChange={(e) => setEditingNode({ ...editingNode, group: e.target.value })}
                    className="block w-full rounded-md border-gray-300 dark:border-gray-600 shadow-sm focus:border-indigo-500 focus:ring-indigo-500 dark:focus:border-indigo-400 dark:focus:ring-indigo-400 sm:text-sm bg-white dark:bg-gray-700 text-gray-900 dark:text-gray-100"
                  >
                    <option value="A">Group A (Green)</option>
                    <option value="B">Group B (Blue)</option>
                    <option value="C">Group C (Orange)</option>
                    <option value="D">Group D (Purple)</option>
                    <option value="E">Group E (Gray)</option>
                  </select>
                  <input
                    type="number"
                    value={editingNode.level}
                    onChange={(e) => setEditingNode({ ...editingNode, level: parseInt(e.target.value) })}
                    className="block w-full rounded-md border-gray-300 dark:border-gray-600 shadow-sm focus:border-indigo-500 focus:ring-indigo-500 dark:focus:border-indigo-400 dark:focus:ring-indigo-400 sm:text-sm bg-white dark:bg-gray-700 text-gray-900 dark:text-gray-100"
                  />
                  <div className="flex space-x-2">
                    <button
                      onClick={editNode}
                      className="flex-1 inline-flex justify-center items-center px-3 py-2 border border-transparent text-sm leading-4 font-medium rounded-md text-white bg-indigo-600 hover:bg-indigo-700 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-indigo-500 transition-colors duration-200"
                    >
                      Save
                    </button>
                    <button
                      onClick={() => {
                        setShowNodeEditor(false)
                        setEditingNode(null)
                      }}
                      className="flex-1 inline-flex justify-center items-center px-3 py-2 border border-gray-300 dark:border-gray-600 shadow-sm text-sm leading-4 font-medium rounded-md text-gray-700 dark:text-gray-300 bg-white dark:bg-gray-700 hover:bg-gray-50 dark:hover:bg-gray-600 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-indigo-500 dark:focus:ring-offset-gray-800 transition-colors duration-200"
                    >
                      Cancel
                    </button>
                  </div>
                </div>
              </div>
            )}

            {/* エッジ編集フォーム */}
            {showEdgeEditor && selectedEdge && (
              <div className="mb-6 p-4 bg-green-50 dark:bg-green-900 rounded-lg">
                <h3 className="text-lg font-medium text-gray-900 dark:text-gray-100 mb-3">Edit Edge</h3>
                <div className="space-y-2 text-sm mb-3">
                  <div><span className="font-medium">From:</span> {selectedEdge.data('source')}</div>
                  <div><span className="font-medium">To:</span> {selectedEdge.data('target')}</div>
                </div>
                <div className="space-y-3">
                  <label className="block text-sm font-medium text-gray-700 dark:text-gray-300">
                    Weight
                  </label>
                  <input
                    type="number"
                    value={edgeWeight}
                    onChange={(e) => setEdgeWeight(parseInt(e.target.value))}
                    min="1"
                    max="10"
                    className="block w-full rounded-md border-gray-300 dark:border-gray-600 shadow-sm focus:border-indigo-500 focus:ring-indigo-500 dark:focus:border-indigo-400 dark:focus:ring-indigo-400 sm:text-sm bg-white dark:bg-gray-700 text-gray-900 dark:text-gray-100"
                  />
                  <div className="flex space-x-2">
                    <button
                      onClick={updateEdgeWeight}
                      className="flex-1 inline-flex justify-center items-center px-3 py-2 border border-transparent text-sm leading-4 font-medium rounded-md text-white bg-green-600 hover:bg-green-700 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-green-500 transition-colors duration-200"
                    >
                      Update
                    </button>
                    <button
                      onClick={deleteEdge}
                      className="flex-1 inline-flex justify-center items-center px-3 py-2 border border-transparent text-sm leading-4 font-medium rounded-md text-white bg-red-600 hover:bg-red-700 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-red-500 transition-colors duration-200"
                    >
                      Delete
                    </button>
                  </div>
                </div>
              </div>
            )}

            {/* グラフ統計 */}
            <div className="mb-6">
              <h3 className="text-lg font-medium text-gray-900 dark:text-gray-100 mb-3">Graph Statistics</h3>
              <div className="space-y-2 text-sm">
                <div><span className="font-medium">Nodes:</span> {astData.nodes.length}</div>
                <div><span className="font-medium">Edges:</span> {astData.edges.length}</div>
                <div><span className="font-medium">Groups:</span> {new Set(astData.nodes.map(n => n.group)).size}</div>
                <div><span className="font-medium">Max Level:</span> {Math.max(...astData.nodes.map(n => n.level), 0)}</div>
              </div>
            </div>

            {/* ノードリスト */}
            <div>
              <h3 className="text-lg font-medium text-gray-900 dark:text-gray-100 mb-3">Nodes</h3>
              <div className="space-y-2 max-h-60 overflow-y-auto">
                {astData.nodes.map(node => (
                  <div
                    key={node.id}
                    className={`p-2 rounded border cursor-pointer transition-colors duration-200 ${
                      selectedNode?.id === node.id
                        ? 'border-indigo-500 bg-indigo-50 dark:bg-indigo-900'
                        : 'border-gray-200 dark:border-gray-600 hover:border-gray-300 dark:hover:border-gray-500'
                    }`}
                    onClick={() => {
                      setSelectedNode(node)
                      setSelectedEdge(null)
                      setShowEdgeEditor(false)
                    }}
                  >
                    <div className="text-sm font-medium text-gray-900 dark:text-gray-100">{node.label}</div>
                    <div className="text-xs text-gray-500 dark:text-gray-400">
                      {node.type} • Group {node.group} • Level {node.level}
                    </div>
                  </div>
                ))}
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
  )
} 