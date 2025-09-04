'use client'

import { useEffect, useRef, useState, useCallback } from 'react'
import { createKagamiEditorMock } from '../lib/kagamiMock'

interface LogEntry {
  message: string
  type: 'info' | 'state' | 'event' | 'error'
  timestamp: string
}

export default function KagamiEditorComponent() {
  const editorRef = useRef<HTMLDivElement>(null)
  const [kagamiEditor, setKagamiEditor] = useState<any>(null)
  const [logs, setLogs] = useState<LogEntry[]>([])
  const [status, setStatus] = useState<string>('初期化中...')
  const [selectedFile, setSelectedFile] = useState<File | null>(null)
  const [cellAddr, setCellAddr] = useState('A1')
  const [cellValue, setCellValue] = useState('')
  const [getCellAddr, setGetCellAddr] = useState('A1')
  const [formula, setFormula] = useState('=A1+B1')
  const [hasHeader, setHasHeader] = useState(true)
  const [maxRows, setMaxRows] = useState(1000)
  const [maxCols, setMaxCols] = useState(100)
  const [excelProgress, setExcelProgress] = useState('')
  const [excelResults, setExcelResults] = useState('')
  const [cellDisplay, setCellDisplay] = useState('セルの値がここに表示されます')

  // ログ関数
  const log = useCallback((message: string, type: LogEntry['type'] = 'info') => {
    const timestamp = new Date().toLocaleTimeString()
    const newLog: LogEntry = { message, type, timestamp }
    setLogs(prev => [...prev, newLog])
    console.log(`[Kagami] ${message}`)
  }, [])

  // ステータス更新
  const updateStatus = useCallback((message: string) => {
    setStatus(`ステータス: ${message}`)
  }, [])

  // 初期化
  useEffect(() => {
    const initializeKagamiEditor = async () => {
      try {
        log('Kagami Editorを初期化中...', 'info')
        updateStatus('初期化中...')

        if (!editorRef.current) {
          throw new Error('Editor container not found')
        }

        // モック版のKagami Editorを作成
        const editor = createKagamiEditorMock(editorRef.current, `
<h1>Kagami Editor へようこそ！</h1>
<p>このエディタは<strong>ProseMirror</strong> + イベントソーシング + 表計算機能を統合したリッチテキストエディタです。</p>

<h2>主な機能</h2>
<ul>
<li><strong>リッチテキスト編集</strong>: 太字、斜体、見出しなど</li>
<li><em>構造化文書</em>: スキーマベースの文書モデル</li>
<li><code>イベントソーシング</code>: 全ての変更をイベントとして記録</li>
<li>表計算機能: HyperFormulaによる計算エンジン</li>
</ul>

<h3>使い方</h3>
<p>上部のボタンを使って、テキストの装飾や見出しの設定ができます。</p>
<p>表計算機能も試してみてください！A1セルに数値を入力し、B1セルに =A1*2 と入力してみましょう。</p>
        `)

        console.log('📝 Editor creation completed')
        console.log('📋 Editor container:', editorRef.current)
        console.log('🎯 Editor instance:', editor)
        
        // エディタコンテナの内容を確認
        if (editorRef.current) {
          console.log('🔍 Container innerHTML:', editorRef.current.innerHTML)
          console.log('🎨 Container children:', editorRef.current.children)
        }

        setKagamiEditor(editor)

        // 確実にコンテンツが設定されるように、少し待ってから再確認
        setTimeout(() => {
          if (editorRef.current) {
            const contentDiv = editorRef.current.querySelector('div[contenteditable="true"]')
            if (contentDiv && (!contentDiv.innerHTML || contentDiv.innerHTML.trim() === '')) {
              console.log('⚠️ コンテンツが空のため、再設定します')
              contentDiv.innerHTML = `
<h1>Kagami Editor へようこそ！</h1>
<p>このエディタは<strong>ProseMirror</strong> + イベントソーシング + 表計算機能を統合したリッチテキストエディタです。</p>

<h2>主な機能</h2>
<ul>
<li><strong>リッチテキスト編集</strong>: 太字、斜体、見出しなど</li>
<li><em>構造化文書</em>: スキーマベースの文書モデル</li>
<li><code>イベントソーシング</code>: 全ての変更をイベントとして記録</li>
<li>表計算機能: HyperFormulaによる計算エンジン</li>
</ul>

<h3>使い方</h3>
<p>上部のボタンを使って、テキストの装飾や見出しの設定ができます。</p>
<p>表計算機能も試してみてください！A1セルに数値を入力し、B1セルに =A1*2 と入力してみましょう。</p>
              `
            }
            console.log('✅ 最終コンテンツ確認:', contentDiv?.innerHTML?.substring(0, 100) + '...')
          }
        }, 100)

        // イベントリスナーを設定
        if (editor.editorState$) {
          editor.editorState$.subscribe((state: any) => {
            log(`エディタ状態変更: 文字数 ${state.content?.length || 0}`, 'state')
          })
        }

        if (editor.spreadsheetState$) {
          editor.spreadsheetState$.subscribe((state: any) => {
            log(`表計算状態変更: セル数 ${Object.keys(state.cells || {}).length}`, 'state')
            // セル表示を更新
            if (editor) {
              try {
                let html = '<h4>セル値:</h4>'
                html += '<table border="1" style="border-collapse: collapse; width: 100%;">'
                html += '<tr><th></th><th>A</th><th>B</th><th>C</th></tr>'

                for (let row = 0; row < 3; row++) {
                  html += `<tr><th>${row + 1}</th>`
                  for (let col = 0; col < 3; col++) {
                    try {
                      const value = editor.getCellValue(row, col)
                      html += `<td>${value?.value || ''}</td>`
                    } catch (e) {
                      html += '<td></td>'
                    }
                  }
                  html += '</tr>'
                }

                html += '</table>'
                setCellDisplay(html)
              } catch (error) {
                setCellDisplay(`エラー: ${error}`)
              }
            }
          })
        }

        if (editor.events$) {
          editor.events$.subscribe((event: any) => {
            log(`イベント: ${event.type} (ID: ${event.id?.substring(0, 8) || 'unknown'}...)`, 'event')
          })
        }

        // 表計算のデモデータを設定
        editor.setCellValue(0, 0, 100)  // A1 = 100
        editor.setCellValue(0, 1, 200)  // B1 = 200
        editor.setCellValue(0, 2, '=A1+B1')  // C1 = A1+B1
        editor.setCellValue(1, 0, '=A1*2')   // A2 = A1*2

        // 初期のセル表示を更新
        try {
          let html = '<h4>セル値:</h4>'
          html += '<table border="1" style="border-collapse: collapse; width: 100%;">'
          html += '<tr><th></th><th>A</th><th>B</th><th>C</th></tr>'

          for (let row = 0; row < 3; row++) {
            html += `<tr><th>${row + 1}</th>`
            for (let col = 0; col < 3; col++) {
              try {
                const value = editor.getCellValue(row, col)
                html += `<td>${value?.value || ''}</td>`
              } catch (e) {
                html += '<td></td>'
              }
            }
            html += '</tr>'
          }

          html += '</table>'
          setCellDisplay(html)
        } catch (error) {
          setCellDisplay(`エラー: ${error}`)
        }

        log('Kagami Editorの初期化が完了しました', 'state')
        updateStatus('初期化完了 - 準備完了')

      } catch (error) {
        const errorMessage = error instanceof Error ? error.message : 'Unknown error'
        log(`初期化エラー: ${errorMessage}`, 'error')
        updateStatus(`初期化失敗: ${errorMessage}`)
        console.error('Initialization error:', error)
      }
    }

    initializeKagamiEditor()
  }, [log, updateStatus])

  // ハンドラー関数群
  const handleSetContent = () => {
    if (!kagamiEditor) return

    const content = `<h1>サンプルドキュメント</h1>
<p>これは<strong>ProseMirror</strong>で作成された<em>リッチテキスト</em>ドキュメントです。</p>

<h2>機能紹介</h2>
<ul>
<li><strong>太字テキスト</strong></li>
<li><em>イタリックテキスト</em></li>
<li><code>インラインコード</code></li>
</ul>

<h3>使い方</h3>
<p>テキストを選択してから、上部のフォーマットボタンを使用してください。</p>
<p>見出しボタンを使って、段落を見出しに変換することもできます。</p>`

    kagamiEditor.setHTML(content)
    log('サンプルコンテンツを設定しました', 'info')
  }

  const handleGetContent = () => {
    if (!kagamiEditor) return

    const content = kagamiEditor.getContent()
    log(`コンテンツを取得: ${content.length}文字`, 'info')
    alert(`コンテンツ:\n${content.substring(0, 200)}${content.length > 200 ? '...' : ''}`)
  }

  const handleInsertText = () => {
    if (!kagamiEditor) return

    const text = prompt('挿入するテキストを入力してください:', '\n// 挿入されたテキスト\nconsole.log("Hello!");')
    if (text !== null) {
      kagamiEditor.insertText(text)
      log(`テキストを挿入しました: "${text.substring(0, 20)}..."`, 'info')
    }
  }

  const handleFocusEditor = () => {
    if (!kagamiEditor) return

    kagamiEditor.focus()
    log('エディタにフォーカスしました', 'info')
  }

  const handleSetCellValue = () => {
    if (!kagamiEditor) return

    if (!cellValue) {
      alert('値を入力してください')
      return
    }

    try {
      const match = cellAddr.match(/^([A-Z]+)(\d+)$/)
      if (!match) {
        throw new Error('無効なセルアドレス')
      }

      const col = match[1].charCodeAt(0) - 65
      const row = parseInt(match[2]) - 1

      kagamiEditor.setCellValue(row, col, cellValue)
      log(`セル ${cellAddr} に値を設定: ${cellValue}`, 'info')

      setCellValue('')

      // セル表示を更新
      try {
        let html = '<h4>セル値:</h4>'
        html += '<table border="1" style="border-collapse: collapse; width: 100%;">'
        html += '<tr><th></th><th>A</th><th>B</th><th>C</th></tr>'

        for (let row = 0; row < 3; row++) {
          html += `<tr><th>${row + 1}</th>`
          for (let col = 0; col < 3; col++) {
            try {
              const value = kagamiEditor.getCellValue(row, col)
              html += `<td>${value?.value || ''}</td>`
            } catch (e) {
              html += '<td></td>'
            }
          }
          html += '</tr>'
        }

        html += '</table>'
        setCellDisplay(html)
      } catch (error) {
        setCellDisplay(`エラー: ${error}`)
      }

    } catch (error) {
      const errorMessage = error instanceof Error ? error.message : 'Unknown error'
      log(`セル設定エラー: ${errorMessage}`, 'error')
      alert(`エラー: ${errorMessage}`)
    }
  }

  const handleGetCellValue = () => {
    if (!kagamiEditor) return

    try {
      const match = getCellAddr.match(/^([A-Z]+)(\d+)$/)
      if (!match) {
        throw new Error('無効なセルアドレス')
      }

      const col = match[1].charCodeAt(0) - 65
      const row = parseInt(match[2]) - 1

      const cellValue = kagamiEditor.getCellValue(row, col)
      log(`セル ${getCellAddr} の値: ${JSON.stringify(cellValue)}`, 'info')
      alert(`セル ${getCellAddr}:\n値: ${cellValue.value}\n型: ${cellValue.type}${cellValue.formula ? `\n数式: ${cellValue.formula}` : ''}`)

    } catch (error) {
      const errorMessage = error instanceof Error ? error.message : 'Unknown error'
      log(`セル取得エラー: ${errorMessage}`, 'error')
      alert(`エラー: ${errorMessage}`)
    }
  }

  const handleFileSelect = (event: React.ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0]
    if (file) {
      setSelectedFile(file)
      log(`ファイルが選択されました: ${file.name} (${file.size} bytes)`, 'info')

      setExcelResults(`
        <h4>選択されたファイル</h4>
        <p><strong>名前:</strong> ${file.name}</p>
        <p><strong>サイズ:</strong> ${(file.size / 1024).toFixed(2)} KB</p>
        <p><strong>タイプ:</strong> ${file.type}</p>
        <p><strong>最終更新:</strong> ${new Date(file.lastModified).toLocaleString()}</p>
      `)
    }
  }

  const handleImportExcel = async () => {
    if (!selectedFile) {
      alert('まずExcelファイルを選択してください')
      return
    }

    if (!kagamiEditor) {
      alert('Kagami Editorが初期化されていません')
      return
    }

    try {
      setExcelProgress('Excelファイルを読み込んでいます...')

      const options = {
        hasHeader,
        maxRows,
        maxColumns: maxCols
      }

      log(`Excelファイルをインポート中: ${selectedFile.name}`, 'info')

      if (typeof kagamiEditor.importExcelFile === 'function') {
        const result = await kagamiEditor.importExcelFile(selectedFile, options)

        log(`インポート完了: ${result.tables.length}個の表を作成`, 'info')

        // インポートしたデータをエディタ内にテーブルとして挿入
        if (result.excelResults && result.excelResults.length > 0) {
          let tableHTML = '\n<h3>📊 インポートされたテーブル</h3>\n'
          
          result.excelResults.forEach((sheet: any, sheetIndex: number) => {
            tableHTML += `<h4>${sheet.sheetName || `Sheet${sheetIndex + 1}`}</h4>\n`
            tableHTML += '<table border="1" style="border-collapse: collapse; width: 100%; margin: 10px 0;">\n'
            
            // ヘッダー行
            if (hasHeader && sheet.headers) {
              tableHTML += '<thead>\n<tr style="background-color: #f0f8ff;">\n'
              sheet.headers.forEach((header: any) => {
                tableHTML += `  <th style="padding: 8px; border: 1px solid #ddd; text-align: left;">${header}</th>\n`
              })
              tableHTML += '</tr>\n</thead>\n'
            }
            
            // データ行
            tableHTML += '<tbody>\n'
            if (sheet.data && sheet.data.length > 0) {
              sheet.data.forEach((row: any[], rowIndex: number) => {
                tableHTML += '<tr>\n'
                row.forEach((cell: any) => {
                  const cellValue = cell !== null && cell !== undefined ? String(cell) : ''
                  tableHTML += `  <td style="padding: 8px; border: 1px solid #ddd;">${cellValue}</td>\n`
                })
                tableHTML += '</tr>\n'
              })
            }
            tableHTML += '</tbody>\n'
            tableHTML += '</table>\n'
            
            tableHTML += `<p style="color: #666; font-size: 0.9em; margin: 5px 0;">行数: ${sheet.rowCount}, 列数: ${sheet.columnCount}</p>\n`
          })
          
          // エディタにテーブルHTMLを挿入
          kagamiEditor.insertText(tableHTML)
          
          // 同時にインポートしたデータを表計算エンジンにも設定
          if (result.excelResults[0] && result.excelResults[0].data) {
            const firstSheet = result.excelResults[0]
            
            // ヘッダーを設定
            if (hasHeader && firstSheet.headers) {
              firstSheet.headers.forEach((header: any, colIndex: number) => {
                kagamiEditor.setCellValue(0, colIndex, header)
              })
            }
            
            // データを設定
            firstSheet.data.forEach((row: any[], rowIndex: number) => {
              row.forEach((cell: any, colIndex: number) => {
                const dataRowIndex = hasHeader ? rowIndex + 1 : rowIndex
                kagamiEditor.setCellValue(dataRowIndex, colIndex, cell)
              })
            })
            
            log(`表計算エンジンにデータを設定: ${firstSheet.data.length}行`, 'info')
          }
        }

        setExcelResults(`
          <h4>インポート結果</h4>
          <p><strong>作成された表:</strong> ${result.tables.length}個</p>
          <p><strong>作成されたシート:</strong> ${result.sheets.length}個</p>
          <p><strong>エディタに挿入:</strong> ✅ 完了</p>
          <div style="margin-top: 10px;">
            <h5>表の詳細:</h5>
            ${result.tables.map((table: any) => `
              <div style="margin: 5px 0; padding: 5px; border-left: 3px solid #007acc;">
                <strong>${table.name}</strong> (${table.rows}行 × ${table.cols}列)
              </div>
            `).join('')}
          </div>
        `)
      }

      setExcelProgress('')

    } catch (error) {
      const errorMessage = error instanceof Error ? error.message : 'Unknown error'
      log(`Excelインポートエラー: ${errorMessage}`, 'error')
      setExcelProgress(`エラー: ${errorMessage}`)

      setTimeout(() => {
        setExcelProgress('')
      }, 3000)
    }
  }

  const clearLogs = () => {
    setLogs([])
    log('ログをクリアしました', 'info')
  }

  return (
    <>
      <div className="controls">
        <button onClick={handleSetContent}>サンプルコンテンツを設定</button>
        <button onClick={handleGetContent}>コンテンツを取得</button>
        <button onClick={handleInsertText}>テキストを挿入</button>
        <button onClick={handleFocusEditor}>フォーカス</button>
      </div>
      
      <div className="controls">
        <button onClick={() => kagamiEditor?.applyFormat('bold')}>太字</button>
        <button onClick={() => kagamiEditor?.applyFormat('italic')}>斜体</button>
        <button onClick={() => kagamiEditor?.applyFormat('code')}>コード</button>
        <button onClick={() => kagamiEditor?.setHeading(1)}>見出し1</button>
        <button onClick={() => kagamiEditor?.setHeading(2)}>見出し2</button>
        <button onClick={() => kagamiEditor?.setHeading(3)}>見出し3</button>
      </div>
      
      <div ref={editorRef} className="editor-container"></div>

      <div className="demo-section">
        <h2>表計算機能</h2>
        <div className="spreadsheet-demo">
          <div>
            <h3>セル操作</h3>
            <div className="cell-input">
              <input
                type="text"
                placeholder="A1"
                value={cellAddr}
                onChange={(e) => setCellAddr(e.target.value)}
              />
              <input
                type="text"
                placeholder="値または数式"
                value={cellValue}
                onChange={(e) => setCellValue(e.target.value)}
              />
              <button onClick={handleSetCellValue}>設定</button>
            </div>
            <div className="cell-input">
              <input
                type="text"
                placeholder="A1"
                value={getCellAddr}
                onChange={(e) => setGetCellAddr(e.target.value)}
              />
              <button onClick={handleGetCellValue}>取得</button>
            </div>
          </div>
          <div>
            <h3>セル表示</h3>
            <div 
              className="cell-display"
              dangerouslySetInnerHTML={{ __html: cellDisplay }}
            />
          </div>
        </div>
      </div>

      <div className="demo-section">
        <h2>📊 Excel インポート機能</h2>
        <div className="controls">
          <input
            type="file"
            accept=".xlsx,.xls"
            onChange={handleFileSelect}
          />
          <button onClick={handleImportExcel}>Excelファイルをインポート</button>
        </div>
        <div className="controls">
          <label>
            <input
              type="checkbox"
              checked={hasHeader}
              onChange={(e) => setHasHeader(e.target.checked)}
            />
            最初の行をヘッダーとして扱う
          </label>
          <label>
            最大行数: 
            <input
              type="number"
              value={maxRows}
              min="1"
              max="10000"
              onChange={(e) => setMaxRows(parseInt(e.target.value))}
            />
          </label>
          <label>
            最大列数: 
            <input
              type="number"
              value={maxCols}
              min="1"
              max="1000"
              onChange={(e) => setMaxCols(parseInt(e.target.value))}
            />
          </label>
        </div>
        {excelProgress && (
          <div className="status">
            {excelProgress}
          </div>
        )}
        {excelResults && (
          <div 
            className="status"
            dangerouslySetInnerHTML={{ __html: excelResults }}
          />
        )}
      </div>

      <div className="demo-section">
        <h2>状態監視</h2>
        <div className="controls">
          <button onClick={() => {
            if (kagamiEditor) {
              const state = kagamiEditor.getState()
              log('現在の状態を取得しました', 'state')
              console.log('Kagami Editor State:', state)
            }
          }}>現在の状態を取得</button>
          <button onClick={clearLogs}>ログをクリア</button>
        </div>
        <div className="logs">
          {logs.map((logEntry, index) => (
            <div key={index} className={`log-entry log-${logEntry.type}`}>
              [{logEntry.timestamp}] {logEntry.message}
            </div>
          ))}
        </div>
      </div>

      <div className="status">
        {status}
      </div>
    </>
  )
} 