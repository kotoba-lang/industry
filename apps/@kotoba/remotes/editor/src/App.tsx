import { useState, useEffect, useRef } from 'react'
import './App.css'

/**
 * テキストエディターコンポーネント
 * Module Federationでホストアプリケーションから利用される
 */
function App() {
  const [content, setContent] = useState('')
  const [fileName, setFileName] = useState('untitled.txt')
  const [isModified, setIsModified] = useState(false)
  const textareaRef = useRef<HTMLTextAreaElement>(null)

  useEffect(() => {
    console.log('Remote Editor: App component mounted.');
    
    // 初期コンテンツを設定
    setContent('# Welcome to Kotoba Editor\n\nThis is a simple text editor component.\n\n## Features:\n- Real-time editing\n- File management\n- Syntax highlighting (coming soon)\n\nStart typing to begin editing...')
  }, []);

  const handleContentChange = (e: React.ChangeEvent<HTMLTextAreaElement>) => {
    setContent(e.target.value)
    setIsModified(true)
  }

  const handleSave = () => {
    // 実際のアプリケーションではファイルシステムに保存
    console.log('Saving file:', fileName)
    console.log('Content:', content)
    setIsModified(false)
    alert(`File "${fileName}" saved successfully!`)
  }

  const handleNewFile = () => {
    if (isModified) {
      if (confirm('Unsaved changes will be lost. Continue?')) {
        setContent('')
        setFileName('untitled.txt')
        setIsModified(false)
      }
    } else {
      setContent('')
      setFileName('untitled.txt')
      setIsModified(false)
    }
  }

  const handleFileNameChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    setFileName(e.target.value)
    setIsModified(true)
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
          <button onClick={handleNewFile} className="btn btn-secondary">
            New
          </button>
          <button onClick={handleSave} className="btn btn-primary" disabled={!isModified}>
            {isModified ? 'Save*' : 'Saved'}
          </button>
        </div>
      </div>
      
      <div className="editor-content">
        <textarea
          ref={textareaRef}
          value={content}
          onChange={handleContentChange}
          className="editor-textarea"
          placeholder="Start typing your content here..."
          spellCheck={false}
        />
      </div>
      
      <div className="editor-footer">
        <span className="status-text">
          {isModified ? 'Modified' : 'Saved'} • {content.length} characters
        </span>
      </div>
    </div>
  )
}

export default App
