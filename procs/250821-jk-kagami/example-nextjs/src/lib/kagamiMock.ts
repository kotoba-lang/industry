// Kagami Editor Mock Implementation for Next.js

export interface MockKagamiEditor {
  setContent: (content: string) => void
  setHTML: (html: string) => void
  getContent: () => string
  getHTML: () => string
  focus: () => void
  setCursor: (pos: number) => void
  insertText: (text: string) => void
  applyFormat: (format: string) => void
  setHeading: (level: number) => void
  setCellValue: (row: number, col: number, value: any) => void
  getCellValue: (row: number, col: number) => { value: any; type: string; formula?: string }
  getSheets: () => Array<{ id: number; name: string; rows: number; cols: number }>
  evaluateFormula: (formula: string) => any
  getState: () => any
  getStats: () => any
  getDebugInfo: () => any
  importExcelFile?: (file: File, options: any) => Promise<any>
  editorState$?: { subscribe: (callback: (state: any) => void) => void }
  spreadsheetState$?: { subscribe: (callback: (state: any) => void) => void }
  events$?: { subscribe: (callback: (event: any) => void) => void }
}

export function createKagamiEditorMock(container: HTMLElement, initialContent: string = ''): MockKagamiEditor {
  // セル値のストレージ
  const cellStorage = new Map<string, any>()
  
  // 初期コンテンツを設定
  container.innerHTML = `
    <div contenteditable="true" style="
      padding: 20px; 
      margin: 0; 
      height: 100%; 
      min-height: 280px;
      overflow: auto; 
      border: 1px solid #ddd; 
      background: white;
      border-radius: 4px;
      font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
      line-height: 1.6;
      box-sizing: border-box;
      outline: none;
    ">
      ${initialContent}
    </div>
  `

  return {
    setContent: (content: string) => {
      const contentDiv = container.querySelector('div')
      if (contentDiv) {
        contentDiv.textContent = content
      }
    },
    
    setHTML: (html: string) => {
      const contentDiv = container.querySelector('div')
      if (contentDiv) {
        contentDiv.innerHTML = html
      }
    },
    
    getContent: () => {
      const contentDiv = container.querySelector('div')
      return contentDiv?.textContent || initialContent
    },
    
    getHTML: () => {
      const contentDiv = container.querySelector('div')
      return contentDiv?.innerHTML || initialContent
    },
    
    focus: () => {
      container.focus()
    },
    
    setCursor: (pos: number) => {
      console.log(`Setting cursor to position ${pos}`)
    },
    
    insertText: (text: string) => {
      const contentDiv = container.querySelector('div')
      if (contentDiv) {
        const current = contentDiv.innerHTML || ''
        
        // HTMLテーブルや構造化されたHTMLの場合は直接挿入
        if (text.includes('<table') || text.includes('<h3>') || text.includes('<h4>')) {
          contentDiv.innerHTML = `${current}${text}`
        } else {
          // 通常のテキストの場合はハイライト付きで挿入
          contentDiv.innerHTML = `${current}<span style="background: #ffffcc;">${text}</span>`
        }
        
        // テーブルのスタイルを適用
        const tables = contentDiv.querySelectorAll('table')
        tables.forEach(table => {
          table.style.borderCollapse = 'collapse'
          table.style.width = '100%'
          table.style.margin = '10px 0'
          table.style.boxShadow = '0 2px 4px rgba(0,0,0,0.1)'
          
          // セルのスタイル調整
          const cells = table.querySelectorAll('td, th')
          cells.forEach(cell => {
            if (cell instanceof HTMLElement) {
              cell.style.padding = '8px'
              cell.style.border = '1px solid #ddd'
              cell.style.textAlign = 'left'
            }
          })
          
          // ヘッダーのスタイル調整
          const headers = table.querySelectorAll('th')
          headers.forEach(header => {
            if (header instanceof HTMLElement) {
              header.style.backgroundColor = '#f0f8ff'
              header.style.fontWeight = 'bold'
            }
          })
          
          // 偶数行の背景色
          const rows = table.querySelectorAll('tbody tr:nth-child(even)')
          rows.forEach(row => {
            if (row instanceof HTMLElement) {
              row.style.backgroundColor = '#f9f9f9'
            }
          })
        })
      }
    },
    
    applyFormat: (format: string) => {
      console.log(`Applying ${format} format`)
      // 実際のフォーマット適用をシミュレート
      const selection = window.getSelection()
      if (selection && selection.rangeCount > 0) {
        const range = selection.getRangeAt(0)
        const selectedText = range.toString()
        if (selectedText) {
          let formattedText = selectedText
          switch (format) {
            case 'bold':
              formattedText = `<strong>${selectedText}</strong>`
              break
            case 'italic':
              formattedText = `<em>${selectedText}</em>`
              break
            case 'code':
              formattedText = `<code>${selectedText}</code>`
              break
          }
          
          const span = document.createElement('span')
          span.innerHTML = formattedText
          range.deleteContents()
          range.insertNode(span)
        }
      }
    },
    
    setHeading: (level: number) => {
      console.log(`Setting heading level ${level}`)
      const selection = window.getSelection()
      if (selection && selection.rangeCount > 0) {
        const range = selection.getRangeAt(0)
        const selectedText = range.toString()
        if (selectedText) {
          const heading = document.createElement(`h${level}`)
          heading.textContent = selectedText
          range.deleteContents()
          range.insertNode(heading)
        }
      }
    },
    
    setCellValue: (row: number, col: number, value: any) => {
      const cellKey = `${row}-${col}`
      cellStorage.set(cellKey, value)
      console.log(`Setting cell [${row},${col}] = ${value}`)
    },
    
    getCellValue: (row: number, col: number) => {
      const cellKey = `${row}-${col}`
      const value = cellStorage.get(cellKey)
      
      // デフォルト値またはランダム値を返す
      if (value !== undefined) {
        return {
          value,
          type: typeof value === 'number' ? 'number' : 
                typeof value === 'string' && value.startsWith('=') ? 'formula' : 'string',
          formula: typeof value === 'string' && value.startsWith('=') ? value : undefined
        }
      }
      
      return { 
        value: Math.random() * 100, 
        type: 'number' 
      }
    },
    
    getSheets: () => [
      { id: 0, name: 'Sheet1', rows: 10, cols: 10 }
    ],
    
    evaluateFormula: (formula: string) => {
      try {
        // 簡単な数式評価
        const expression = formula.replace(/^=/, '')
        if (/^[\d+\-*/().\s]+$/.test(expression)) {
          return eval(expression)
        }
        return `評価結果: ${Math.random() * 100}`
      } catch {
        return '#ERROR!'
      }
    },
    
    getState: () => ({
      editor: { 
        content: container.textContent || '', 
        cursor: 0, 
        lastSequenceNumber: 1 
      },
      spreadsheet: { 
        cells: Object.fromEntries(cellStorage), 
        lastSequenceNumber: 1 
      },
      metadata: { 
        created: Date.now(), 
        updated: Date.now(), 
        version: '1.0.0' 
      }
    }),
    
    getStats: () => ({
      totalEvents: 5,
      editorLastSequence: 3,
      spreadsheetLastSequence: 2,
      lastUpdated: Date.now()
    }),
    
    getDebugInfo: () => ({
      initialized: true,
      eventStoreType: 'InMemory',
      eventStoreConnected: true,
      stats: { 
        totalEvents: 5, 
        editorLastSequence: 3, 
        spreadsheetLastSequence: 2, 
        lastUpdated: Date.now() 
      }
    }),
    
    // Excel インポート機能 - 実際のファイルを読み込み
    importExcelFile: async (file: File, options: any) => {
      return new Promise((resolve, reject) => {
        const reader = new FileReader()
        
        reader.onload = async (e) => {
          try {
            console.log('🔍 Excelファイル読み込み開始:', file.name, 'サイズ:', file.size)
            // xlsxライブラリを動的インポート
            const XLSX = await import('xlsx')
            console.log('📚 xlsxライブラリ読み込み完了')
            
            const data = new Uint8Array(e.target?.result as ArrayBuffer)
            const workbook = XLSX.read(data, { type: 'array' })
            console.log('📊 ワークブック読み込み完了. シート数:', workbook.SheetNames.length, 'シート名:', workbook.SheetNames)
            
            const excelResults: any[] = []
            const mockTables: any[] = []
            const mockSheets: any[] = []
            
            // 各シートを処理
            workbook.SheetNames.forEach((sheetName, sheetIndex) => {
              console.log(`🔄 シート処理中: ${sheetName} (${sheetIndex + 1}/${workbook.SheetNames.length})`)
              const worksheet = workbook.Sheets[sheetName]
              
              // シートをJSONに変換
              const jsonData = XLSX.utils.sheet_to_json(worksheet, { 
                header: 1, 
                raw: false,
                defval: ''
              }) as any[][]
              
              if (jsonData.length === 0) return
              
              // 最大行数・列数の制限を適用
              const limitedData = jsonData.slice(0, options.maxRows || 1000)
              const maxCols = options.maxColumns || 100
              
              // 各行の列数を制限
              const processedData = limitedData.map(row => 
                row.slice(0, maxCols)
              )
              
              let headers: string[] = []
              let dataRows: any[][] = []
              
              if (options.hasHeader && processedData.length > 0) {
                headers = processedData[0].map((cell: any) => String(cell || ''))
                dataRows = processedData.slice(1)
              } else {
                // ヘッダーなしの場合、A, B, C...でヘッダーを生成
                const maxColCount = Math.max(...processedData.map(row => row.length))
                headers = Array.from({ length: maxColCount }, (_, i) => 
                  String.fromCharCode(65 + i)
                )
                dataRows = processedData
              }
              
              // 空の行を除去
              const filteredDataRows = dataRows.filter(row => 
                row.some(cell => cell !== null && cell !== undefined && cell !== '')
              )
              
                             if (filteredDataRows.length > 0) {
                 console.log(`✅ シート "${sheetName}" 処理完了:`, {
                   データ行数: filteredDataRows.length,
                   列数: headers.length,
                   ヘッダー: headers.slice(0, 3), // 最初の3列だけ表示
                   サンプルデータ: filteredDataRows.slice(0, 2) // 最初の2行だけ表示
                 })
                 
                 excelResults.push({
                   sheetName: sheetName,
                   data: filteredDataRows,
                   headers: headers,
                   rowCount: filteredDataRows.length,
                   columnCount: headers.length
                 })
                
                // テーブル情報を作成
                mockTables.push({
                  tableId: `table-${sheetIndex}`,
                  name: sheetName,
                  rows: filteredDataRows.length + (options.hasHeader ? 1 : 0),
                  cols: headers.length,
                  cells: [], // 必要に応じて詳細なセル情報を作成
                  position: sheetIndex
                })
                
                // シート情報を作成
                mockSheets.push({
                  id: `sheet-${sheetIndex}`,
                  name: sheetName,
                  rows: filteredDataRows.length + (options.hasHeader ? 1 : 0),
                  cols: headers.length
                })
              }
            })
            
            console.log('🎉 Excel処理完了:', {
              処理したシート数: excelResults.length,
              総データ行数: excelResults.reduce((sum, sheet) => sum + sheet.rowCount, 0),
              結果: excelResults.map(sheet => `${sheet.sheetName}: ${sheet.rowCount}行`)
            })
            
            resolve({
              tables: mockTables,
              sheets: mockSheets,
              excelResults: excelResults
            })
            
          } catch (error) {
            console.error('Excel読み込みエラー:', error)
            reject(new Error(`Excelファイルの読み込みに失敗しました: ${error instanceof Error ? error.message : 'Unknown error'}`))
          }
        }
        
        reader.onerror = () => {
          reject(new Error('ファイルの読み込みに失敗しました'))
        }
        
        reader.readAsArrayBuffer(file)
      })
    },
    
    // モック Observable
    editorState$: { 
      subscribe: (callback: (state: any) => void) => {
        console.log('Editor state subscription created')
        // 初期状態を即座に通知
        setTimeout(() => {
          callback({ content: container.textContent || '', cursor: 0 })
        }, 100)
      }
    },
    
    spreadsheetState$: { 
      subscribe: (callback: (state: any) => void) => {
        console.log('Spreadsheet state subscription created')
        // 初期状態を即座に通知
        setTimeout(() => {
          callback({ cells: Object.fromEntries(cellStorage) })
        }, 100)
      }
    },
    
    events$: { 
      subscribe: (callback: (event: any) => void) => {
        console.log('Events subscription created')
        // 定期的にモックイベントを送信
        setTimeout(() => {
          callback({ 
            type: 'editor.init', 
            id: 'mock-event-1', 
            timestamp: Date.now() 
          })
        }, 500)
      }
    }
  }
} 