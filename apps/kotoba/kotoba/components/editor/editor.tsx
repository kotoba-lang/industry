import React, { useState, useEffect, useRef } from 'react'
import { useEditor, EditorContent } from '@tiptap/react'
import StarterKit from '@tiptap/starter-kit'
import Placeholder from '@tiptap/extension-placeholder'
import Highlight from '@tiptap/extension-highlight'
import Link from '@tiptap/extension-link'
import Image from '@tiptap/extension-image'
import { Table } from '@tiptap/extension-table'
import TaskList from '@tiptap/extension-task-list'
import Underline from '@tiptap/extension-underline'
import TextAlign from '@tiptap/extension-text-align'
import Color from '@tiptap/extension-color'
import { TextStyle } from '@tiptap/extension-text-style'
import FontFamily from '@tiptap/extension-font-family'
import FontSize from '@tiptap/extension-font-size'
import Subscript from '@tiptap/extension-subscript'
import Superscript from '@tiptap/extension-superscript'
import Strike from '@tiptap/extension-strike'
import Code from '@tiptap/extension-code'
import CodeBlock from '@tiptap/extension-code-block'
import Paragraph from '@tiptap/extension-paragraph'
import Heading from '@tiptap/extension-heading'
import Bold from '@tiptap/extension-bold'
import Italic from '@tiptap/extension-italic'
import BulletList from '@tiptap/extension-bullet-list'
import OrderedList from '@tiptap/extension-ordered-list'
import ListItem from '@tiptap/extension-list-item'
import Blockquote from '@tiptap/extension-blockquote'
import HorizontalRule from '@tiptap/extension-horizontal-rule'
import HardBreak from '@tiptap/extension-hard-break'
import Dropcursor from '@tiptap/extension-dropcursor'
import Gapcursor from '@tiptap/extension-gapcursor'
import History from '@tiptap/extension-history'
import Collaboration from '@tiptap/extension-collaboration'
import CollaborationCursor from '@tiptap/extension-collaboration-cursor'
import Mention from '@tiptap/extension-mention'
import Emoji from '@tiptap/extension-emoji'
import Typography from '@tiptap/extension-typography'

export type EditorProps = {
  /**
   * 初期ファイル名
   */
  initialFileName?: string;
  /**
   * 初期コンテンツ
   */
  initialContent?: string;
  /**
   * ASTデータ更新コールバック
   */
  onASTUpdate?: (astData: any) => void;
};

export function Editor({ 
  initialFileName = 'untitled.md',
  initialContent = `# Kotoba Editor

This is a Tiptap-based rich text editor with AST generation capabilities.

## Features

- Rich text editing
- Markdown support
- AST generation
- Real-time updates

## Lists

- Item 1
- Item 2
  - Sub-item 2.1
  - Sub-item 2.2

## Code Blocks

\`\`\`javascript
function hello() {
  console.log("Hello, World!");
}
\`\`\`

Start editing to see the AST in the graph viewer!`,
  onASTUpdate
}: EditorProps) {
  const [fileName, setFileName] = useState(initialFileName)
  const [isModified, setIsModified] = useState(false)
  const editorRef = useRef<HTMLDivElement>(null)

  const editor = useEditor({
    extensions: [
      StarterKit,
      Placeholder.configure({
        placeholder: 'Start writing...',
      }),
      Highlight,
      Link.configure({
        openOnClick: false,
      }),
      Image,
      Table.configure({
        resizable: true,
      }),
      TaskList,
      Underline,
      TextAlign.configure({
        types: ['heading', 'paragraph'],
      }),
      Color,
      TextStyle,
      FontFamily,
      FontSize,
      Subscript,
      Superscript,
      Strike,
      Code,
      CodeBlock,
      Paragraph,
      Heading,
      Bold,
      Italic,
      BulletList,
      OrderedList,
      ListItem,
      Blockquote,
      HorizontalRule,
      HardBreak,
      Dropcursor,
      Gapcursor,
      History,
      Collaboration,
      CollaborationCursor,
      Mention,
      Emoji,
      Typography,
    ],
    content: initialContent,
    onUpdate: ({ editor }) => {
      setIsModified(true)
      // AST生成とコールバック呼び出し
      const astData = generateAST(editor)
      if (onASTUpdate) {
        onASTUpdate(astData)
      }
    },
    editorProps: {
      attributes: {
        class: 'prose prose-sm sm:prose lg:prose-lg focus:outline-none p-4 border border-gray-300 rounded-lg bg-white dark:bg-gray-800 dark:border-gray-600 dark:prose-invert min-h-[600px] transition-colors duration-200 text-gray-900 dark:text-gray-100',
      },
    },
  })

  // ダークモード変更を監視
  useEffect(() => {
    const observer = new MutationObserver((mutations) => {
      mutations.forEach((mutation) => {
        if (mutation.type === 'attributes' && mutation.attributeName === 'class') {
          const isDarkMode = document.documentElement.classList.contains('dark')
          if (editor && editorRef.current) {
            const editorElement = editorRef.current.querySelector('.ProseMirror')
            if (editorElement) {
              if (isDarkMode) {
                editorElement.classList.add('dark')
              } else {
                editorElement.classList.remove('dark')
              }
            }
          }
        }
      })
    })

    observer.observe(document.documentElement, {
      attributes: true,
      attributeFilter: ['class']
    })

    return () => observer.disconnect()
  }, [editor])

  /**
   * ファイル名を変更
   */
  const handleFileNameChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    setFileName(e.target.value)
  }

  /**
   * ファイルを保存
   */
  const handleSave = () => {
    if (editor) {
      const content = editor.getHTML()
      const blob = new Blob([content], { type: 'text/html' })
      const url = URL.createObjectURL(blob)
      const a = document.createElement('a')
      a.href = url
      a.download = fileName
      a.click()
      URL.revokeObjectURL(url)
      setIsModified(false)
    }
  }

  /**
   * 新しいファイルを作成
   */
  const handleNewFile = () => {
    if (editor) {
      editor.commands.setContent('<p></p>')
      setFileName('untitled.md')
      setIsModified(true)
    }
  }

  /**
   * TiptapエディターからASTを生成
   */
  const generateAST = (editorInstance: any): { nodes: any[], edges: any[] } => {
    if (!editorInstance) return { nodes: [], edges: [] }

    const doc = editorInstance.state.doc
    const nodes: any[] = []
    const edges: any[] = []
    let nodeId = 0

    // ルートノードを追加
    const rootNode = {
      id: 'doc',
      label: 'Document',
      type: 'doc',
      group: 'A',
      level: 0
    }
    nodes.push(rootNode)

    /**
     * ノードを再帰的に処理してASTを構築
     */
    const processNode = (node: any, parentId: string, level: number) => {
      const currentNodeId = `node_${nodeId++}`
      
      // ノードタイプに基づいてグループを決定
      let group = 'E' // デフォルト
      let label = node.type.name
      
      switch (node.type.name) {
        case 'heading':
          group = 'A'
          label = `Heading ${node.attrs.level}`
          break
        case 'paragraph':
          group = 'B'
          label = node.textContent.slice(0, 30) + (node.textContent.length > 30 ? '...' : '')
          break
        case 'bullet_list':
        case 'ordered_list':
          group = 'C'
          label = node.type.name === 'bullet_list' ? 'Bullet List' : 'Ordered List'
          break
        case 'list_item':
          group = 'C'
          label = node.textContent.slice(0, 20) + (node.textContent.length > 20 ? '...' : '')
          break
        case 'code_block':
          group = 'D'
          label = 'Code Block'
          break
        case 'text':
          group = 'E'
          label = node.text.slice(0, 15) + (node.text.length > 15 ? '...' : '')
          break
      }

      const astNode = {
        id: currentNodeId,
        label,
        type: node.type.name,
        group,
        level
      }
      nodes.push(astNode)

      // 親ノードとのエッジを作成
      if (parentId !== 'doc') {
        edges.push({
          source: parentId,
          target: currentNodeId,
          weight: 1
        })
      } else {
        edges.push({
          source: 'doc',
          target: currentNodeId,
          weight: 1
        })
      }

      // 子ノードを処理
      node.forEach((child: any) => {
        processNode(child, currentNodeId, level + 1)
      })
    }

    // ドキュメントの子ノードを処理
    doc.forEach((child: any) => {
      processNode(child, 'doc', 1)
    })

    return { nodes, edges }
  }

  /**
   * ASTデータをエクスポート
   */
  const exportAST = () => {
    if (!editor) return
    
    const astData = generateAST(editor)
    console.log('Generated AST:', astData)

    // コールバック関数を呼び出し
    if (onASTUpdate) {
      onASTUpdate(astData)
      console.log('AST data sent via callback')
    }

    // グローバルウィンドウオブジェクトにも送信（後方互換性）
    if ((window as any).updateASTData) {
      (window as any).updateASTData(astData)
      console.log('AST data sent to global window object')
    }

    // ASTデータをJSONファイルとしてダウンロード
    const blob = new Blob([JSON.stringify(astData, null, 2)], { type: 'application/json' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `${fileName.replace(/\.[^/.]+$/, '')}_ast.json`
    a.click()
    URL.revokeObjectURL(url)
  }

  return (
    <div className="tiptap-editor" data-testid="editor">
      {/* Header */}
      <div className="bg-white dark:bg-gray-800 shadow-sm border-b border-gray-200 dark:border-gray-700">
        <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8">
          <div className="flex justify-between items-center py-4">
            <div className="flex items-center space-x-4">
              <h1 className="text-xl font-semibold text-gray-900 dark:text-gray-100">Kotoba Editor</h1>
              <div className="flex items-center space-x-2">
                <input
                  type="text"
                  value={fileName}
                  onChange={handleFileNameChange}
                  className="block w-64 rounded-md border-gray-300 dark:border-gray-600 shadow-sm focus:border-indigo-500 focus:ring-indigo-500 dark:focus:border-indigo-400 dark:focus:ring-indigo-400 sm:text-sm bg-white dark:bg-gray-700 text-gray-900 dark:text-gray-100"
                  placeholder="Enter file name..."
                />
                {isModified && (
                  <span className="inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium bg-yellow-100 dark:bg-yellow-900 text-yellow-800 dark:text-yellow-200">
                    Modified
                  </span>
                )}
              </div>
            </div>
            
            <div className="flex items-center space-x-3">
              <button
                onClick={handleNewFile}
                className="inline-flex items-center px-3 py-2 border border-gray-300 dark:border-gray-600 shadow-sm text-sm leading-4 font-medium rounded-md text-gray-700 dark:text-gray-300 bg-white dark:bg-gray-700 hover:bg-gray-50 dark:hover:bg-gray-600 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-indigo-500 dark:focus:ring-offset-gray-800 transition-colors duration-200"
              >
                <svg className="-ml-0.5 mr-2 h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 4v16m8-8H4" />
                </svg>
                New
              </button>
              
              <button
                onClick={handleSave}
                disabled={!isModified}
                className="inline-flex items-center px-3 py-2 border border-transparent text-sm leading-4 font-medium rounded-md text-white bg-indigo-600 hover:bg-indigo-700 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-indigo-500 disabled:opacity-50 disabled:cursor-not-allowed transition-colors duration-200"
              >
                <svg className="-ml-0.5 mr-2 h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M8 7H5a2 2 0 00-2 2v9a2 2 0 002 2h14a2 2 0 002-2V9a2 2 0 00-2-2h-3m-1 4l-3 3m0 0l-3-3m3 3V4" />
                </svg>
                {isModified ? 'Save*' : 'Saved'}
              </button>
              
              <button
                onClick={exportAST}
                className="inline-flex items-center px-3 py-2 border border-transparent text-sm leading-4 font-medium rounded-md text-white bg-green-600 hover:bg-green-700 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-green-500 transition-colors duration-200"
              >
                <svg className="-ml-0.5 mr-2 h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 19v-6a2 2 0 00-2-2H5a2 2 0 00-2 2v6a2 2 0 002 2h2a2 2 0 002-2zm0 0V9a2 2 0 012-2h2a2 2 0 012 2v10m-6 0a2 2 0 002 2h2a2 2 0 002-2m0 0V5a2 2 0 012-2h2a2 2 0 012 2v14a2 2 0 01-2 2h-2a2 2 0 01-2-2z" />
                </svg>
                Export AST
              </button>
            </div>
          </div>
        </div>
      </div>

      {/* Editor Content */}
      <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-6">
        <div className="bg-white dark:bg-gray-800 shadow-sm rounded-lg border border-gray-200 dark:border-gray-700">
          <div 
            ref={editorRef} 
            className="tiptap-editor-container"
          >
            {/* Tiptap Toolbar */}
            {editor && (
              <div className="tiptap-toolbar">
                <button
                  onClick={() => editor.chain().focus().toggleBold().run()}
                  className={`tiptap-button ${editor.isActive('bold') ? 'is-active' : ''}`}
                >
                  Bold
                </button>
                <button
                  onClick={() => editor.chain().focus().toggleItalic().run()}
                  className={`tiptap-button ${editor.isActive('italic') ? 'is-active' : ''}`}
                >
                  Italic
                </button>
                <button
                  onClick={() => editor.chain().focus().toggleStrike().run()}
                  className={`tiptap-button ${editor.isActive('strike') ? 'is-active' : ''}`}
                >
                  Strike
                </button>
                <button
                  onClick={() => editor.chain().focus().toggleCode().run()}
                  className={`tiptap-button ${editor.isActive('code') ? 'is-active' : ''}`}
                >
                  Code
                </button>
                <button
                  onClick={() => editor.chain().focus().toggleHeading({ level: 1 }).run()}
                  className={`tiptap-button ${editor.isActive('heading', { level: 1 }) ? 'is-active' : ''}`}
                >
                  H1
                </button>
                <button
                  onClick={() => editor.chain().focus().toggleHeading({ level: 2 }).run()}
                  className={`tiptap-button ${editor.isActive('heading', { level: 2 }) ? 'is-active' : ''}`}
                >
                  H2
                </button>
                <button
                  onClick={() => editor.chain().focus().toggleBulletList().run()}
                  className={`tiptap-button ${editor.isActive('bulletList') ? 'is-active' : ''}`}
                >
                  Bullet List
                </button>
                <button
                  onClick={() => editor.chain().focus().toggleOrderedList().run()}
                  className={`tiptap-button ${editor.isActive('orderedList') ? 'is-active' : ''}`}
                >
                  Ordered List
                </button>
                <button
                  onClick={() => editor.chain().focus().toggleBlockquote().run()}
                  className={`tiptap-button ${editor.isActive('blockquote') ? 'is-active' : ''}`}
                >
                  Quote
                </button>
                <button
                  onClick={() => editor.chain().focus().setHorizontalRule().run()}
                  className="tiptap-button"
                >
                  Horizontal Rule
                </button>
              </div>
            )}
            <EditorContent editor={editor} />
          </div>
        </div>
      </div>

      {/* Footer */}
      <div className="bg-white dark:bg-gray-800 border-t border-gray-200 dark:border-gray-700">
        <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-3">
          <div className="flex items-center justify-between text-sm text-gray-500 dark:text-gray-400">
            <span>Tiptap Editor • AST Export Ready</span>
            <span>{isModified ? 'Modified' : 'Saved'}</span>
          </div>
        </div>
      </div>
    </div>
  )
}
