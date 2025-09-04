import { Plugin, PluginKey, PluginView } from 'prosemirror-state';
import { EditorView } from 'prosemirror-view';
import { Node } from 'prosemirror-model';
import { ExcelImporter, ExcelImportOptions } from '../../importer/ExcelImporter';
import { SpreadsheetRenderer } from '../../spreadsheet/SpreadsheetRenderer';

/**
 * Excel インポートプラグインのキー
 */
export const excelImportPluginKey = new PluginKey('excel-import');

/**
 * Excel インポートプラグインの設定
 */
export interface ExcelImportPluginOptions {
  /** ExcelImporter インスタンス */
  excelImporter: ExcelImporter;
  /** SpreadsheetRenderer インスタンス（optional） */
  spreadsheetRenderer?: SpreadsheetRenderer;
  /** インポート時のデフォルトオプション */
  defaultImportOptions?: ExcelImportOptions;
  /** インポートボタンのラベル */
  buttonLabel?: string;
  /** インポートボタンのアイコン */
  buttonIcon?: string;
  /** アップロードファイルタイプ */
  acceptedFileTypes?: string;
}

/**
 * Excel インポートプラグインの状態
 */
export interface ExcelImportPluginState {
  /** インポート中かどうか */
  isImporting: boolean;
  /** エラーメッセージ */
  errorMessage?: string;
  /** インポート進捗 */
  progress?: number;
}

/**
 * Excel インポートプラグインのビュー
 */
class ExcelImportPluginView implements PluginView {
  private toolbarElement!: HTMLElement;
  private fileInput!: HTMLInputElement;
  private importButton!: HTMLButtonElement;
  private progressElement!: HTMLElement;
  private errorElement!: HTMLElement;

  constructor(
    private view: EditorView,
    private options: ExcelImportPluginOptions
  ) {
    this.createToolbarElements();
    this.setupEventListeners();
  }

  /**
   * ツールバー要素を作成
   */
  private createToolbarElements(): void {
    // メインコンテナ
    this.toolbarElement = document.createElement('div');
    this.toolbarElement.className = 'excel-import-toolbar';
    this.toolbarElement.style.cssText = `
      display: flex;
      align-items: center;
      gap: 8px;
      padding: 4px 8px;
      border-bottom: 1px solid #e0e0e0;
      background: #f5f5f5;
    `;

    // ファイル入力
    this.fileInput = document.createElement('input');
    this.fileInput.type = 'file';
    this.fileInput.accept = this.options.acceptedFileTypes || '.xlsx,.xls,.csv';
    this.fileInput.style.display = 'none';
    this.fileInput.id = 'excel-import-file-input';

    // インポートボタン
    this.importButton = document.createElement('button');
    this.importButton.type = 'button';
    this.importButton.className = 'excel-import-button';
    this.importButton.innerHTML = `
      ${this.options.buttonIcon || '📊'}
      <span>${this.options.buttonLabel || 'Excel インポート'}</span>
    `;
    this.importButton.style.cssText = `
      display: flex;
      align-items: center;
      gap: 4px;
      padding: 6px 12px;
      border: 1px solid #ccc;
      border-radius: 4px;
      background: white;
      cursor: pointer;
      font-size: 14px;
    `;

    // 進捗表示
    this.progressElement = document.createElement('div');
    this.progressElement.className = 'excel-import-progress';
    this.progressElement.style.cssText = `
      display: none;
      font-size: 12px;
      color: #666;
    `;

    // エラー表示
    this.errorElement = document.createElement('div');
    this.errorElement.className = 'excel-import-error';
    this.errorElement.style.cssText = `
      display: none;
      font-size: 12px;
      color: #e74c3c;
      max-width: 200px;
      word-wrap: break-word;
    `;

    // 要素を追加
    this.toolbarElement.appendChild(this.fileInput);
    this.toolbarElement.appendChild(this.importButton);
    this.toolbarElement.appendChild(this.progressElement);
    this.toolbarElement.appendChild(this.errorElement);

    // エディタの前に挿入
    const editorDom = this.view.dom;
    if (editorDom.parentNode) {
      editorDom.parentNode.insertBefore(this.toolbarElement, editorDom);
    }
  }

  /**
   * イベントリスナーを設定
   */
  private setupEventListeners(): void {
    // インポートボタンクリック
    this.importButton.addEventListener('click', () => {
      this.fileInput.click();
    });

    // ファイル選択
    this.fileInput.addEventListener('change', (event) => {
      const target = event.target as HTMLInputElement;
      if (target.files && target.files[0]) {
        this.handleFileImport(target.files[0]);
      }
    });

    // ドラッグ＆ドロップ
    this.setupDragAndDrop();
  }

  /**
   * ドラッグ＆ドロップ機能を設定
   */
  private setupDragAndDrop(): void {
    const editorDom = this.view.dom;

    editorDom.addEventListener('dragover', (event) => {
      event.preventDefault();
      event.dataTransfer!.dropEffect = 'copy';
      editorDom.style.border = '2px dashed #007bff';
    });

    editorDom.addEventListener('dragleave', (event) => {
      event.preventDefault();
      editorDom.style.border = '';
    });

    editorDom.addEventListener('drop', (event) => {
      event.preventDefault();
      editorDom.style.border = '';
      
      const files = event.dataTransfer?.files;
      if (files && files[0]) {
        this.handleFileImport(files[0]);
      }
    });
  }

  /**
   * ファイルインポートを処理
   */
  private async handleFileImport(file: File): Promise<void> {
    this.updateState({ isImporting: true });

    try {
      // インポート実行
      const results = await this.options.excelImporter.importFromFile(
        file,
        this.options.defaultImportOptions
      );

      // インポート結果を ProseMirror に挿入
      for (const result of results) {
        await this.insertExcelTable(result);
      }

      this.updateState({ isImporting: false });
      
      // ファイル入力をクリア
      this.fileInput.value = '';

    } catch (error) {
      console.error('Excel import error:', error);
      this.updateState({ 
        isImporting: false, 
        errorMessage: `インポートエラー: ${error instanceof Error ? error.message : 'Unknown error'}`
      });
    }
  }

  /**
   * Excel テーブルを ProseMirror に挿入
   */
  private async insertExcelTable(result: any): Promise<void> {
    const { state, dispatch } = this.view;
    const { schema } = state;

    // 表ノードタイプを取得
    const tableType = schema.nodes.table;
    const tableRowType = schema.nodes.table_row;
    const tableCellType = schema.nodes.table_cell;

    if (!tableType || !tableRowType || !tableCellType) {
      throw new Error('Table node types not found in schema');
    }

    // 表のノードを作成
    const tableRows = [];
    
    // ヘッダー行を作成
    if (result.headers && result.headers.length > 0) {
      const headerCells = result.headers.map((header: string) => {
        const paragraphNode = schema.nodes.paragraph?.create(null, 
          header ? [schema.text(header)] : []
        );
        return tableCellType.create(
          { cellType: 'header' },
          paragraphNode ? [paragraphNode] : []
        );
      });
      tableRows.push(tableRowType.create(null, headerCells));
    }

    // データ行を作成
    for (const row of result.data) {
      const cells = [];
      for (let i = 0; i < Math.max(row.length, result.headers?.length || 0); i++) {
        const cellValue = row[i] || '';
        const paragraphNode = schema.nodes.paragraph?.create(null, 
          cellValue ? [schema.text(String(cellValue))] : []
        );
        cells.push(tableCellType.create(
          { cellType: 'data' },
          paragraphNode ? [paragraphNode] : []
        ));
      }
      tableRows.push(tableRowType.create(null, cells));
    }

    // 表全体を作成
    const table = tableType.create(
      { 
        excelSheetName: result.sheetName,
        imported: true,
        importedAt: new Date().toISOString()
      },
      tableRows
    );

    // 現在の選択位置に挿入
    const tr = state.tr;
    const { from } = state.selection;
    
    // 表の前後に段落を追加
    const paragraphBefore = schema.nodes.paragraph?.create();
    const paragraphAfter = schema.nodes.paragraph?.create();
    
    if (paragraphBefore && paragraphAfter) {
      tr.insert(from, [paragraphBefore, table, paragraphAfter]);
    } else {
      tr.insert(from, table);
    }

    dispatch(tr);
  }

  /**
   * 状態を更新
   */
  private updateState(state: Partial<ExcelImportPluginState>): void {
    // インポート中の UI 更新
    if (state.isImporting !== undefined) {
      this.importButton.disabled = state.isImporting;
      this.progressElement.style.display = state.isImporting ? 'block' : 'none';
      this.progressElement.textContent = state.isImporting ? 'インポート中...' : '';
    }

    // エラーメッセージの更新
    if (state.errorMessage !== undefined) {
      this.errorElement.style.display = state.errorMessage ? 'block' : 'none';
      this.errorElement.textContent = state.errorMessage || '';
    }

    // 進捗の更新
    if (state.progress !== undefined) {
      this.progressElement.textContent = `インポート中... ${Math.round(state.progress)}%`;
    }
  }

  /**
   * ビューを更新
   */
  update(view: EditorView): void {
    this.view = view;
  }

  /**
   * ビューを破棄
   */
  destroy(): void {
    if (this.toolbarElement && this.toolbarElement.parentNode) {
      this.toolbarElement.parentNode.removeChild(this.toolbarElement);
    }
  }
}

/**
 * Excel インポートプラグインを作成
 */
export function createExcelImportPlugin(options: ExcelImportPluginOptions): Plugin {
  return new Plugin({
    key: excelImportPluginKey,
    
    state: {
      init(): ExcelImportPluginState {
        return {
          isImporting: false
        };
      },
      
      apply(tr, pluginState): ExcelImportPluginState {
        // トランザクションからメタデータを取得
        const meta = tr.getMeta(excelImportPluginKey);
        if (meta) {
          return { ...pluginState, ...meta };
        }
        return pluginState;
      }
    },
    
    view: (view) => new ExcelImportPluginView(view, options),
    
    props: {
      // キーボードショートカット
      handleKeyDown(view, event) {
        // Ctrl+Shift+I で Excel インポート
        if (event.ctrlKey && event.shiftKey && event.key === 'I') {
          const pluginView = excelImportPluginKey.getState(view.state);
          if (pluginView && !pluginView.isImporting) {
            // ファイル選択を開く
            const fileInput = document.getElementById('excel-import-file-input') as HTMLInputElement;
            if (fileInput) {
              fileInput.click();
            }
          }
          return true;
        }
        return false;
      }
    }
  });
} 