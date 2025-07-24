import { useState, useEffect, useRef } from 'react'
import { EditorState } from 'prosemirror-state'
import { EditorView } from 'prosemirror-view'
import { Schema, DOMParser } from 'prosemirror-model'
import { schema } from 'prosemirror-schema-basic'
import { addListNodes } from 'prosemirror-schema-list'
import { exampleSetup } from 'prosemirror-example-setup'
import './App.css'

// 拡張されたスキーマ（リスト機能付き）
const mySchema = new Schema({
  nodes: addListNodes(schema.spec.nodes, 'paragraph block*', 'block'),
  marks: schema.spec.marks
})

/**
 * ASTノードの型定義
 */
interface ASTNode {
  id: string
  type: string
  content: string
  children: ASTNode[]
  level: number
}

/**
 * プロセミラーエディターコンポーネント
 * Module Federationでホストアプリケーションから利用される
 */
function App() {
  const [fileName, setFileName] = useState('untitled.md')
  const [isModified, setIsModified] = useState(false)
  const [astData, setAstData] = useState<{ nodes: any[], edges: any[] }>({ nodes: [], edges: [] })
  const editorRef = useRef<HTMLDivElement>(null)
  const viewRef = useRef<EditorView | null>(null)

  useEffect(() => {
    console.log('Remote Editor: App component mounted.')
    
    if (editorRef.current && !viewRef.current) {
      // 初期コンテンツ
      const initialContent = `
# Kotoba Editor

This is a **ProseMirror**-based text editor with AST visualization.

## Features
- Rich text editing
- Markdown support
- AST generation
- Graph visualization

### Lists
- Item 1
- Item 2
  - Sub-item 2.1
  - Sub-item 2.2

### Code Blocks
\`\`\`javascript
function hello() {
  console.log("Hello, World!");
}
\`\`\`

Start editing to see the AST structure!
      `.trim()

      // DOMパーサーを作成
      const parser = DOMParser.fromSchema(mySchema)
      
      // 初期HTMLを作成
      const tempDiv = document.createElement('div')
      tempDiv.innerHTML = initialContent
      
      // EditorStateを作成
      const state = EditorState.create({
        doc: parser.parse(tempDiv),
        plugins: exampleSetup({ schema: mySchema })
      })

      // EditorViewを作成
      const view = new EditorView(editorRef.current, {
        state,
        dispatchTransaction(transaction) {
          const newState = view.state.apply(transaction)
          view.updateState(newState)
          
          if (transaction.docChanged) {
            setIsModified(true)
            // ASTを生成
            generateAST(newState.doc)
          }
        }
      })

      viewRef.current = view
      
      // 初期ASTを生成
      generateAST(state.doc)
    }

    return () => {
      if (viewRef.current) {
        viewRef.current.destroy()
        viewRef.current = null
      }
    }
  }, [])

  /**
   * ProseMirrorドキュメントからASTを生成
   */
  const generateAST = (doc: any) => {
    const nodes: any[] = []
    const edges: any[] = []
    let nodeId = 0

    const processNode = (node: any, parentId: string | null = null, level: number = 0) => {
      const currentNodeId = `node_${nodeId++}`
      
      // ノード情報を抽出
      let content = ''
      let nodeType = node.type.name
      
      if (node.isText) {
        content = node.text || ''
        nodeType = 'text'
      } else if (node.type.name === 'heading') {
        content = node.textContent || ''
        nodeType = `heading_${node.attrs.level}`
      } else if (node.type.name === 'paragraph') {
        content = node.textContent || ''
      } else if (node.type.name === 'list_item') {
        content = node.textContent || ''
      } else if (node.type.name === 'code_block') {
        content = node.textContent || ''
      }

      // ノードを追加
      nodes.push({
        id: currentNodeId,
        label: content.length > 20 ? content.substring(0, 20) + '...' : content || nodeType,
        type: nodeType,
        group: getNodeGroup(nodeType),
        level: level
      })

      // 親子関係のエッジを追加
      if (parentId) {
        edges.push({
          source: parentId,
          target: currentNodeId,
          weight: 1
        })
      }

      // 子ノードを処理
      node.forEach((child: any, offset: number) => {
        processNode(child, currentNodeId, level + 1)
      })
    }

    // ドキュメントのルートノードから処理開始
    processNode(doc)

    setAstData({ nodes, edges })
  }

  /**
   * ノードタイプに基づいてグループを決定
   */
  const getNodeGroup = (nodeType: string): string => {
    if (nodeType.startsWith('heading_')) return 'A'
    if (nodeType === 'paragraph') return 'B'
    if (nodeType === 'list_item') return 'C'
    if (nodeType === 'code_block') return 'D'
    if (nodeType === 'text') return 'E'
    return 'F'
  }

  /**
   * ファイルを保存
   */
  const handleSave = () => {
    if (viewRef.current) {
      const content = viewRef.current.state.doc.textContent
      console.log('Saving content:', content)
      setIsModified(false)
    }
  }

  /**
   * 新しいファイルを作成
   */
  const handleNewFile = () => {
    if (viewRef.current) {
      const parser = DOMParser.fromSchema(mySchema)
      const tempDiv = document.createElement('div')
      tempDiv.innerHTML = '<p>New document</p>'
      
      const newState = EditorState.create({
        doc: parser.parse(tempDiv),
        plugins: exampleSetup({ schema: mySchema })
      })
      
      viewRef.current.updateState(newState)
      setIsModified(false)
      generateAST(newState.doc)
    }
  }

  /**
   * ファイル名を変更
   */
  const handleFileNameChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    setFileName(e.target.value)
  }

  /**
   * ASTデータをエクスポート
   */
  const exportAST = () => {
    const astDataStr = JSON.stringify(astData, null, 2)
    const blob = new Blob([astDataStr], { type: 'application/json' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `${fileName.replace('.md', '')}_ast.json`
    a.click()
    URL.revokeObjectURL(url)
  }

  return (
    <div className="editor-container">
      <div className="editor-header">
        <input 
          type="text" 
          value={fileName} 
          onChange={handleFileNameChange} 
          className="file-name-input" 
          placeholder="Enter file name..." 
        />
        <div className="editor-actions">
          <button onClick={handleNewFile} className="btn btn-secondary">New</button>
          <button onClick={handleSave} className="btn btn-primary" disabled={!isModified}>
            {isModified ? 'Save*' : 'Saved'}
          </button>
          <button onClick={exportAST} className="btn btn-export">Export AST</button>
        </div>
      </div>
      
      <div className="editor-content">
        <div ref={editorRef} className="prosemirror-editor" />
      </div>
      
      <div className="editor-footer">
        <span className="status-text">
          {isModified ? 'Modified' : 'Saved'} • {astData.nodes.length} AST nodes • {astData.edges.length} connections
        </span>
      </div>
    </div>
  )
}

export default App
