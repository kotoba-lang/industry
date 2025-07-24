import { useState, useEffect, useRef } from 'react'
import cytoscape from 'cytoscape'
import './App.css'

/**
 * グラフ表示コンポーネント（cytoscape使用）
 * Module Federationでホストアプリケーションから利用される
 */
function App() {
  const [graphType, setGraphType] = useState<'network' | 'hierarchy' | 'circular'>('network')
  const [nodes, setNodes] = useState([
    { id: '1', label: 'Node 1', group: 'A' },
    { id: '2', label: 'Node 2', group: 'A' },
    { id: '3', label: 'Node 3', group: 'B' },
    { id: '4', label: 'Node 4', group: 'B' },
    { id: '5', label: 'Node 5', group: 'C' }
  ])
  const [edges, setEdges] = useState([
    { source: '1', target: '2', weight: 1 },
    { source: '2', target: '3', weight: 2 },
    { source: '3', target: '4', weight: 1 },
    { source: '4', target: '5', weight: 3 },
    { source: '1', target: '5', weight: 2 }
  ])
  const containerRef = useRef<HTMLDivElement>(null)
  const cyRef = useRef<cytoscape.Core | null>(null)

  useEffect(() => {
    console.log('Remote Graph: App component mounted.');
    initializeGraph()
  }, [])

  useEffect(() => {
    if (cyRef.current) {
      updateGraphLayout()
    }
  }, [graphType])

  useEffect(() => {
    if (cyRef.current) {
      updateGraphData()
    }
  }, [nodes, edges])

  /**
   * cytoscapeグラフを初期化
   */
  const initializeGraph = () => {
    if (!containerRef.current) return

    // 既存のグラフがあれば削除
    if (cyRef.current) {
      cyRef.current.destroy()
    }

    // cytoscapeインスタンスを作成
    cyRef.current = cytoscape({
      container: containerRef.current,
      elements: {
        nodes: nodes.map(node => ({
          group: 'nodes' as const,
          data: {
            id: node.id,
            label: node.label,
            group: node.group
          }
        })),
        edges: edges.map(edge => ({
          group: 'edges' as const,
          data: {
            id: `${edge.source}-${edge.target}`,
            source: edge.source,
            target: edge.target,
            weight: edge.weight
          }
        }))
      },
      style: [
        {
          selector: 'node',
          style: {
            'background-color': '#666',
            'label': 'data(label)',
            'color': '#fff',
            'text-valign': 'center',
            'text-halign': 'center',
            'width': 30,
            'height': 30,
            'font-size': '10px',
            'border-width': 2,
            'border-color': '#333'
          }
        },
        {
          selector: 'node[group="A"]',
          style: {
            'background-color': '#4CAF50'
          }
        },
        {
          selector: 'node[group="B"]',
          style: {
            'background-color': '#2196F3'
          }
        },
        {
          selector: 'node[group="C"]',
          style: {
            'background-color': '#FF9800'
          }
        },
        {
          selector: 'edge',
          style: {
            'width': 'data(weight)',
            'line-color': '#ccc',
            'target-arrow-color': '#ccc',
            'target-arrow-shape': 'triangle',
            'curve-style': 'bezier'
          }
        }
      ],
      layout: getLayoutConfig()
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
  }

  /**
   * レイアウト設定を取得
   */
  const getLayoutConfig = () => {
    switch (graphType) {
      case 'hierarchy':
        return {
          name: 'dagre',
          rankDir: 'TB',
          padding: 50
        }
      case 'circular':
        return {
          name: 'circle',
          padding: 50
        }
      default:
        return {
          name: 'cose',
          padding: 50,
          animate: true,
          animationDuration: 1000
        }
    }
  }

  /**
   * グラフレイアウトを更新
   */
  const updateGraphLayout = () => {
    if (!cyRef.current) return

    const layout = cyRef.current.layout(getLayoutConfig())
    layout.run()
  }

  /**
   * グラフデータを更新
   */
  const updateGraphData = () => {
    if (!cyRef.current) return

    // 既存の要素を削除
    cyRef.current.elements().remove()

    // 新しい要素を追加
    const newElements = [
      ...nodes.map(node => ({
        group: 'nodes' as const,
        data: {
          id: node.id,
          label: node.label,
          group: node.group
        }
      })),
      ...edges.map(edge => ({
        group: 'edges' as const,
        data: {
          id: `${edge.source}-${edge.target}`,
          source: edge.source,
          target: edge.target,
          weight: edge.weight
        }
      }))
    ]

    cyRef.current.add(newElements)
    updateGraphLayout()
  }

  /**
   * 新しいノードを追加
   */
  const addNode = () => {
    const newNodeId = (nodes.length + 1).toString()
    const groups = ['A', 'B', 'C']
    const randomGroup = groups[Math.floor(Math.random() * groups.length)]
    
    const newNode = {
      id: newNodeId,
      label: `Node ${newNodeId}`,
      group: randomGroup
    }

    setNodes(prev => [...prev, newNode])

    // 既存のノードの1つに接続
    if (nodes.length > 0) {
      const randomNode = nodes[Math.floor(Math.random() * nodes.length)]
      const newEdge = {
        source: randomNode.id,
        target: newNodeId,
        weight: Math.floor(Math.random() * 3) + 1
      }
      setEdges(prev => [...prev, newEdge])
    }
  }

  /**
   * ランダムなエッジを追加
   */
  const addEdge = () => {
    if (nodes.length < 2) return

    const sourceNode = nodes[Math.floor(Math.random() * nodes.length)]
    const targetNode = nodes[Math.floor(Math.random() * nodes.length)]
    
    if (sourceNode.id === targetNode.id) return

    const newEdge = {
      source: sourceNode.id,
      target: targetNode.id,
      weight: Math.floor(Math.random() * 3) + 1
    }

    setEdges(prev => [...prev, newEdge])
  }

  /**
   * 最後のノードを削除
   */
  const removeNode = () => {
    if (nodes.length <= 1) return

    const lastNodeId = nodes[nodes.length - 1].id
    
    setNodes(prev => prev.slice(0, -1))
    setEdges(prev => prev.filter(edge => edge.source !== lastNodeId && edge.target !== lastNodeId))
  }

  /**
   * グラフをリセット
   */
  const resetGraph = () => {
    setNodes([
      { id: '1', label: 'Node 1', group: 'A' },
      { id: '2', label: 'Node 2', group: 'A' },
      { id: '3', label: 'Node 3', group: 'B' },
      { id: '4', label: 'Node 4', group: 'B' },
      { id: '5', label: 'Node 5', group: 'C' }
    ])
    setEdges([
      { source: '1', target: '2', weight: 1 },
      { source: '2', target: '3', weight: 2 },
      { source: '3', target: '4', weight: 1 },
      { source: '4', target: '5', weight: 3 },
      { source: '1', target: '5', weight: 2 }
    ])
  }

  return (
    <div className="graph-container">
      <div className="graph-header">
        <h3>Kotoba Graph Viewer (Cytoscape)</h3>
        <div className="graph-controls">
          <select 
            value={graphType} 
            onChange={(e) => setGraphType(e.target.value as 'network' | 'hierarchy' | 'circular')} 
            className="graph-type-select"
          >
            <option value="network">Network Layout</option>
            <option value="hierarchy">Hierarchy Layout</option>
            <option value="circular">Circular Layout</option>
          </select>
          <button onClick={addNode} className="btn btn-primary">Add Node</button>
          <button onClick={addEdge} className="btn btn-secondary">Add Edge</button>
          <button onClick={removeNode} className="btn btn-danger" disabled={nodes.length <= 1}>Remove Node</button>
          <button onClick={resetGraph} className="btn btn-warning">Reset</button>
        </div>
      </div>
      <div className="graph-content">
        <div ref={containerRef} className="graph-canvas" />
      </div>
      <div className="graph-footer">
        <span className="status-text">
          {graphType} layout • {nodes.length} nodes • {edges.length} edges
        </span>
      </div>
    </div>
  )
}

export default App
