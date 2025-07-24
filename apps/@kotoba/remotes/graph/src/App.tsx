import { useState, useEffect, useRef } from 'react'
import cytoscape from 'cytoscape'
import dagre from 'cytoscape-dagre'
import './App.css'

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

/**
 * GraphコンポーネントのProps
 */
interface GraphProps {
  astData?: { nodes: ASTNode[], edges: ASTEdge[] }
}

/**
 * グラフ表示コンポーネント（cytoscape使用）
 * Module Federationでホストアプリケーションから利用される
 */
function App({ astData: externalASTData }: GraphProps) {
  const [graphType, setGraphType] = useState<'ast' | 'network' | 'hierarchy' | 'circular'>('ast')
  const [astData, setAstData] = useState<{ nodes: ASTNode[], edges: ASTEdge[] }>({ nodes: [], edges: [] })
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
    console.log('Remote Graph: App component mounted.')
    
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
      console.log('Graph: Received external AST data', externalASTData)
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
                case 'A': return '#2ecc71' // 緑 - 見出し
                case 'B': return '#3498db' // 青 - 段落
                case 'C': return '#e67e22' // オレンジ - リスト
                case 'D': return '#9b59b6' // 紫 - コードブロック
                case 'E': return '#95a5a6' // グレー - テキスト
                default: return '#34495e'
              }
            },
            'label': 'data(label)',
            'color': '#fff',
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
            'border-color': '#2c3e50',
            'border-opacity': 0.8
          }
        },
        {
          selector: 'edge',
          style: {
            'width': 'data(weight)',
            'line-color': '#7f8c8d',
            'target-arrow-color': '#7f8c8d',
            'target-arrow-shape': 'triangle',
            'curve-style': 'bezier',
            'opacity': 0.8
          }
        },
        {
          selector: 'node:selected',
          style: {
            'border-width': 4,
            'border-color': '#e74c3c',
            'border-opacity': 1
          }
        },
        {
          selector: 'edge:selected',
          style: {
            'width': 'data(weight)',
            'line-color': '#e74c3c',
            'target-arrow-color': '#e74c3c',
            'opacity': 1
          }
        }
      ],
      layout: getLayoutConfig(graphType)
    })

    // イベントリスナーを追加
    cyRef.current.on('tap', 'node', function(evt) {
      const node = evt.target
      console.log('Node clicked:', node.data())
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
   * サンプルデータを表示
   */
  const showSampleData = () => {
    setAstData(sampleData)
    initializeGraph(sampleData)
  }

  /**
   * ASTデータを表示
   */
  const showASTData = () => {
    // 現在のASTデータを再表示
    initializeGraph(astData)
  }

  /**
   * グラフをリセット
   */
  const resetGraph = () => {
    if (cyRef.current) {
      cyRef.current.fit()
    }
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

  return (
    <div className="graph-container">
      <div className="graph-header">
        <h3>Kotoba Graph Viewer (AST)</h3>
        <div className="graph-controls">
          <select 
            value={graphType} 
            onChange={(e) => handleGraphTypeChange(e.target.value as 'ast' | 'network' | 'hierarchy' | 'circular')} 
            className="graph-type-select"
          >
            <option value="ast">AST Tree</option>
            <option value="hierarchy">Hierarchy</option>
            <option value="circular">Circular</option>
            <option value="network">Network</option>
          </select>
          <button onClick={showASTData} className="btn btn-primary">Show AST</button>
          <button onClick={showSampleData} className="btn btn-secondary">Sample Data</button>
          <button onClick={resetGraph} className="btn btn-reset">Reset View</button>
          <button onClick={exportGraph} className="btn btn-export">Export PNG</button>
        </div>
      </div>
      
      <div className="graph-content">
        <div ref={containerRef} className="graph-canvas" />
      </div>
      
      <div className="graph-footer">
        <span className="status-text">
          {graphType} layout • {astData.nodes.length} nodes • {astData.edges.length} edges
        </span>
      </div>
    </div>
  )
}

export default App
