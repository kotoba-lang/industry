import { Observable, Subject } from 'rxjs';
import { CellDisplayInfo, TableDisplayInfo } from './SpreadsheetRenderer';

/**
 * セルの編集イベント
 */
export interface CellEditEvent {
  tableId: string;
  row: number;
  col: number;
  oldValue: any;
  newValue: any;
  isFormula: boolean;
}

/**
 * 表計算UIのイベント
 */
export interface SpreadsheetUIEvent {
  type: 'cell.edit' | 'cell.select' | 'table.select' | 'formula.edit';
  data: any;
  timestamp: number;
}

/**
 * 表計算機能のUI表示を担当するクラス
 */
export class SpreadsheetUI {
  private container: HTMLElement;
  private eventSubject = new Subject<SpreadsheetUIEvent>();
  private cellEditSubject = new Subject<CellEditEvent>();
  private selectedCell: { tableId: string; row: number; col: number } | null = null;
  private tables: Map<string, TableDisplayInfo> = new Map();

  constructor(container: HTMLElement) {
    this.container = container;
    this.setupEventHandlers();
  }

  /**
   * UIイベントを監視
   */
  public get events$(): Observable<SpreadsheetUIEvent> {
    return this.eventSubject.asObservable();
  }

  /**
   * セル編集イベントを監視
   */
  public get cellEditEvents$(): Observable<CellEditEvent> {
    return this.cellEditSubject.asObservable();
  }

  /**
   * 表をUI上に表示
   * @param tableInfo 表の表示情報
   */
  public displayTable(tableInfo: TableDisplayInfo): void {
    this.tables.set(tableInfo.tableId, tableInfo);
    this.renderTable(tableInfo);
  }

  /**
   * 表を更新
   * @param tableId 表ID
   * @param updatedInfo 更新された表情報
   */
  public updateTable(tableId: string, updatedInfo: Partial<TableDisplayInfo>): void {
    const existing = this.tables.get(tableId);
    if (existing) {
      const updated = { ...existing, ...updatedInfo };
      this.tables.set(tableId, updated);
      this.renderTable(updated);
    }
  }

  /**
   * 表をレンダリング
   * @param tableInfo 表の表示情報
   */
  private renderTable(tableInfo: TableDisplayInfo): void {
    const tableElement = this.createTableElement(tableInfo);
    
    // 既存の表を削除
    const existingTable = this.container.querySelector(`[data-table-id="${tableInfo.tableId}"]`);
    if (existingTable) {
      existingTable.remove();
    }
    
    // 新しい表を追加
    this.container.appendChild(tableElement);
  }

  /**
   * 表のHTML要素を作成
   * @param tableInfo 表の表示情報
   * @returns 表のHTML要素
   */
  private createTableElement(tableInfo: TableDisplayInfo): HTMLElement {
    const wrapper = document.createElement('div');
    wrapper.className = 'kagami-spreadsheet-table';
    wrapper.setAttribute('data-table-id', tableInfo.tableId);
    
    // 表のヘッダー
    const header = document.createElement('div');
    header.className = 'kagami-table-header';
    header.innerHTML = `
      <h3>${tableInfo.name}</h3>
      <div class="kagami-table-info">
        ${tableInfo.rows}行 × ${tableInfo.cols}列
      </div>
    `;
    wrapper.appendChild(header);
    
    // 表の本体
    const table = document.createElement('table');
    table.className = 'kagami-spreadsheet-grid';
    table.setAttribute('data-table-id', tableInfo.tableId);
    
    // 表を行×列の形で構築
    const grid = this.buildTableGrid(tableInfo);
    
    // ヘッダー行
    if (grid.length > 0) {
      const thead = document.createElement('thead');
      const headerRow = document.createElement('tr');
      
      // 行番号列
      const cornerCell = document.createElement('th');
      cornerCell.className = 'kagami-cell-corner';
      cornerCell.textContent = '';
      headerRow.appendChild(cornerCell);
      
      // 列ヘッダー
      for (let col = 0; col < tableInfo.cols; col++) {
        const th = document.createElement('th');
        th.className = 'kagami-column-header';
        th.textContent = this.getColumnLabel(col);
        headerRow.appendChild(th);
      }
      
      thead.appendChild(headerRow);
      table.appendChild(thead);
    }
    
    // データ行
    const tbody = document.createElement('tbody');
    grid.forEach((row, rowIndex) => {
      const tr = document.createElement('tr');
      
      // 行番号
      const rowHeader = document.createElement('th');
      rowHeader.className = 'kagami-row-header';
      rowHeader.textContent = (rowIndex + 1).toString();
      tr.appendChild(rowHeader);
      
      // セル
      row.forEach((cell, colIndex) => {
        const td = this.createCellElement(cell, tableInfo.tableId, rowIndex, colIndex);
        tr.appendChild(td);
      });
      
      tbody.appendChild(tr);
    });
    
    table.appendChild(tbody);
    wrapper.appendChild(table);
    
    return wrapper;
  }

  /**
   * 表のグリッドを構築
   * @param tableInfo 表の表示情報
   * @returns グリッド（行×列の2次元配列）
   */
  private buildTableGrid(tableInfo: TableDisplayInfo): CellDisplayInfo[][] {
    const grid: CellDisplayInfo[][] = [];
    
    for (let row = 0; row < tableInfo.rows; row++) {
      const rowData: CellDisplayInfo[] = [];
      for (let col = 0; col < tableInfo.cols; col++) {
        // セル情報を検索
        const cellInfo = tableInfo.cells.find(c => c.row === row && c.col === col);
        if (cellInfo) {
          rowData.push(cellInfo);
        } else {
          // 空のセル情報を作成
          rowData.push({
            value: '',
            displayValue: '',
            isFormula: false,
            isCalculated: false,
            type: 'empty',
            row,
            col
          });
        }
      }
      grid.push(rowData);
    }
    
    return grid;
  }

  /**
   * セルのHTML要素を作成
   * @param cellInfo セル情報
   * @param tableId 表ID
   * @param row 行番号
   * @param col 列番号
   * @returns セルのHTML要素
   */
  private createCellElement(
    cellInfo: CellDisplayInfo, 
    tableId: string, 
    row: number, 
    col: number
  ): HTMLElement {
    const td = document.createElement('td');
    td.className = 'kagami-cell';
    td.setAttribute('data-table-id', tableId);
    td.setAttribute('data-row', row.toString());
    td.setAttribute('data-col', col.toString());
    
    // セルの型に応じてクラスを追加
    td.classList.add(`kagami-cell-${cellInfo.type}`);
    
    if (cellInfo.isFormula) {
      td.classList.add('kagami-cell-formula');
    }
    
    if (cellInfo.isCalculated) {
      td.classList.add('kagami-cell-calculated');
    }
    
    // セル内容を設定
    const content = document.createElement('div');
    content.className = 'kagami-cell-content';
    content.textContent = cellInfo.displayValue;
    
    // 編集可能にする
    content.setAttribute('contenteditable', 'true');
    content.setAttribute('data-original-value', cellInfo.value?.toString() || '');
    
    // 数式の場合は元の数式を保持
    if (cellInfo.formula) {
      content.setAttribute('data-formula', cellInfo.formula);
    }
    
    td.appendChild(content);
    
    return td;
  }

  /**
   * 列ラベルを取得（A, B, C, ...）
   * @param col 列番号
   * @returns 列ラベル
   */
  private getColumnLabel(col: number): string {
    let label = '';
    let num = col;
    
    do {
      label = String.fromCharCode(65 + (num % 26)) + label;
      num = Math.floor(num / 26) - 1;
    } while (num >= 0);
    
    return label;
  }

  /**
   * イベントハンドラーを設定
   */
  private setupEventHandlers(): void {
    // セルクリック
    this.container.addEventListener('click', (event) => {
      const target = event.target as HTMLElement;
      const cell = target.closest('.kagami-cell');
      
      if (cell) {
        this.handleCellClick(cell as HTMLElement);
      }
    });
    
    // セル編集
    this.container.addEventListener('input', (event) => {
      const target = event.target as HTMLElement;
      
      if (target.classList.contains('kagami-cell-content')) {
        this.handleCellEdit(target);
      }
    });
    
    // セルのフォーカス処理
    this.container.addEventListener('focus', (event) => {
      const target = event.target as HTMLElement;
      
      if (target.classList.contains('kagami-cell-content')) {
        this.handleCellFocus(target);
      }
    }, true);
    
    // セルのブラー処理
    this.container.addEventListener('blur', (event) => {
      const target = event.target as HTMLElement;
      
      if (target.classList.contains('kagami-cell-content')) {
        this.handleCellBlur(target);
      }
    }, true);
  }

  /**
   * セルクリック処理
   * @param cellElement セル要素
   */
  private handleCellClick(cellElement: HTMLElement): void {
    const tableId = cellElement.getAttribute('data-table-id');
    const row = parseInt(cellElement.getAttribute('data-row') || '0');
    const col = parseInt(cellElement.getAttribute('data-col') || '0');
    
    if (tableId) {
      this.selectedCell = { tableId, row, col };
      
      // 既存の選択を解除
      this.container.querySelectorAll('.kagami-cell-selected').forEach(el => {
        el.classList.remove('kagami-cell-selected');
      });
      
      // 新しい選択を設定
      cellElement.classList.add('kagami-cell-selected');
      
      // イベントを発行
      this.eventSubject.next({
        type: 'cell.select',
        data: { tableId, row, col },
        timestamp: Date.now()
      });
    }
  }

  /**
   * セル編集処理
   * @param contentElement セル内容要素
   */
  private handleCellEdit(contentElement: HTMLElement): void {
    const cellElement = contentElement.closest('.kagami-cell') as HTMLElement;
    if (!cellElement) return;
    
    const tableId = cellElement.getAttribute('data-table-id');
    const row = parseInt(cellElement.getAttribute('data-row') || '0');
    const col = parseInt(cellElement.getAttribute('data-col') || '0');
    const oldValue = contentElement.getAttribute('data-original-value');
    const newValue = contentElement.textContent || '';
    
    if (tableId && oldValue !== newValue) {
      const isFormula = newValue.startsWith('=');
      
      // セル編集イベントを発行
      this.cellEditSubject.next({
        tableId,
        row,
        col,
        oldValue,
        newValue,
        isFormula
      });
      
      // 元の値を更新
      contentElement.setAttribute('data-original-value', newValue);
      
      // 数式の場合は属性を更新
      if (isFormula) {
        contentElement.setAttribute('data-formula', newValue);
        cellElement.classList.add('kagami-cell-formula');
      } else {
        contentElement.removeAttribute('data-formula');
        cellElement.classList.remove('kagami-cell-formula');
      }
    }
  }

  /**
   * セルフォーカス処理
   * @param contentElement セル内容要素
   */
  private handleCellFocus(contentElement: HTMLElement): void {
    const formula = contentElement.getAttribute('data-formula');
    
    // 数式の場合は数式を表示
    if (formula) {
      contentElement.textContent = formula;
    }
  }

  /**
   * セルブラー処理
   * @param contentElement セル内容要素
   */
  private handleCellBlur(contentElement: HTMLElement): void {
    const formula = contentElement.getAttribute('data-formula');
    const newValue = contentElement.textContent || '';
    
    // 数式が変更された場合は計算結果を表示
    if (formula && newValue.startsWith('=')) {
      // TODO: 実際の計算結果を表示
      const result = this.evaluateFormula(newValue);
      contentElement.textContent = result.toString();
    }
  }

  /**
   * 数式を評価（簡易版）
   * @param formula 数式
   * @returns 評価結果
   */
  private evaluateFormula(formula: string): any {
    try {
      // =を除去
      const expression = formula.substring(1);
      
      // 簡単な四則演算
      if (/^[\d+\-*/().\s]+$/.test(expression)) {
        return eval(expression);
      }
      
      return 'N/A';
    } catch (error) {
      return '#ERROR!';
    }
  }

  /**
   * 現在選択されているセルの情報を取得
   */
  public getSelectedCell(): { tableId: string; row: number; col: number } | null {
    return this.selectedCell;
  }

  /**
   * 表一覧を取得
   */
  public getTables(): TableDisplayInfo[] {
    return Array.from(this.tables.values());
  }

  /**
   * UIを破棄
   */
  public destroy(): void {
    this.eventSubject.complete();
    this.cellEditSubject.complete();
    this.container.innerHTML = '';
  }
} 