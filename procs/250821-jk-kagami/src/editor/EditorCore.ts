import { EditorView } from 'prosemirror-view';
import { EditorState, Transaction } from 'prosemirror-state';
import { Schema, DOMParser, Node } from 'prosemirror-model';
import { schema } from 'prosemirror-schema-basic';
import { addListNodes } from 'prosemirror-schema-list';
import { exampleSetup } from 'prosemirror-example-setup';
import { columnResizing, tableEditing, goToNextCell, tableNodes } from 'prosemirror-tables';
import { gapCursor } from 'prosemirror-gapcursor';
import { HyperFormula } from 'hyperformula';
import { Subject, Observable } from 'rxjs';
import { v4 as uuidv4 } from 'uuid';

import { 
  EditorState as KagamiEditorState, 
  EditorChangeEvent,
  SpreadsheetChangeEvent,
  KagamiConfig 
} from '../types';
import { SpreadsheetRenderer } from '../spreadsheet/SpreadsheetRenderer';
import { ExcelImporter } from '../importer/ExcelImporter';
import { createExcelImportPlugin } from './plugins/ExcelImportPlugin';

/**
 * セル位置を表す型
 */
export interface CellPosition {
  row: number;
  col: number;
}

/**
 * セル値を表す型
 */
export interface CellValue {
  value: any;
  formula?: string | undefined;
  type: 'number' | 'string' | 'boolean' | 'error' | 'empty';
}

/**
 * 表機能を統合したProseMirrorスキーマ
 */
function createTableSchema() {
  // 基本スキーマにリストノードを追加
  const nodes = addListNodes(schema.spec.nodes, "paragraph block*", "block");
  
  // 表ノードを追加（カスタム属性付き）
  const tableNodesWithSpreadsheet = tableNodes({
    tableGroup: "block",
    cellContent: "block+",
    cellAttributes: {
      formula: { default: undefined },
      calculated: { default: false },
      cellType: { default: "data" }
    }
  });
  
  return new Schema({
    nodes: nodes.append(tableNodesWithSpreadsheet),
    marks: schema.spec.marks
  });
}

/**
 * HyperFormulaを統合したProseMirrorベースのエディタコア
 * 表計算機能を直接内包し、セル内で数式を評価
 */
export class EditorCore {
  private view: EditorView;
  private changeSubject = new Subject<EditorChangeEvent>();
  private spreadsheetChangeSubject = new Subject<SpreadsheetChangeEvent>();
  private sequenceNumber = 0;
  private currentState: KagamiEditorState;
  private schema: Schema;
  private hyperFormula: HyperFormula;
  private cellFormulaMap = new Map<string, string>(); // セル位置 → 数式のマップ
  private sheetId: string = ''; // HyperFormulaのシートID
  private spreadsheetRenderer: SpreadsheetRenderer;
  private excelImporter!: ExcelImporter;

  /**
   * エディタコアを初期化
   * @param parent HTMLエレメント
   * @param config 設定オブジェクト
   */
  constructor(
    parent: HTMLElement,
    private config: KagamiConfig
  ) {
    // HyperFormulaの初期化
    this.hyperFormula = HyperFormula.buildEmpty({
      licenseKey: 'gpl-v3',
      ...config.hyperformula
    });
    
    // デフォルトシートを作成
    this.sheetId = this.hyperFormula.addSheet('Sheet1');

    // 初期状態を設定
    this.currentState = {
      content: config.editor?.initialContent || '',
      cursor: 0,
      selection: undefined,
      lastSequenceNumber: 0
    };

    // 表機能を含むスキーマを作成
    this.schema = createTableSchema();

    // 初期ドキュメントを作成
    const initialDoc = this.createDocumentFromText(this.currentState.content);

    // Excel インポーターを初期化
    this.excelImporter = new ExcelImporter();

    // Excel インポートプラグインを作成
    const excelImportPlugin = createExcelImportPlugin({
      excelImporter: this.excelImporter,
      // SpreadsheetRenderer は後で設定されるため省略
      defaultImportOptions: {
        hasHeader: true,
        maxRows: 1000,
        maxColumns: 50
      }
    });

    // ProseMirrorエディタを初期化（表機能 + Excel インポート機能を含む）
    this.view = new EditorView(parent, {
      state: EditorState.create({
        doc: initialDoc,
        plugins: [
          ...exampleSetup({ schema: this.schema }),
          columnResizing(),
          tableEditing(),
          gapCursor(),
          excelImportPlugin
        ]
      }),
      dispatchTransaction: (transaction: Transaction) => {
        this.handleTransaction(transaction);
      }
    });

    // SpreadsheetRendererを実際に初期化
    this.spreadsheetRenderer = new SpreadsheetRenderer(this.schema, this.view);

    // 表のセル更新時のイベントをハンドル
    this.setupTableEventHandlers();
  }



  /**
   * 表のイベントハンドラーを設定
   */
  private setupTableEventHandlers(): void {
    // セル内容の変更を監視
    this.view.dom.addEventListener('input', (event: Event) => {
      if (this.isInTableCell()) {
        this.handleCellContentChange();
      }
    });

    // セルフォーカス時の処理
    this.view.dom.addEventListener('click', (event: Event) => {
      if (this.isInTableCell()) {
        this.handleCellFocus();
      }
    });
  }

  /**
   * 現在のカーソル位置が表のセル内かどうかを判定
   */
  private isInTableCell(): boolean {
    const { $from } = this.view.state.selection;
    return $from.depth > 0 && $from.node().type.name === 'table_cell';
  }

  /**
   * セル内容の変更を処理
   */
  private handleCellContentChange(): void {
    const { $from } = this.view.state.selection;
    if ($from.depth < 1) return;

    const cell = $from.node();
    const cellContent = cell.textContent;
    
    // 数式の場合（=で始まる）
    if (cellContent.startsWith('=')) {
      this.processCellFormula(cellContent, $from);
    }
  }

  /**
   * セルフォーカス時の処理
   */
  private handleCellFocus(): void {
    const { $from } = this.view.state.selection;
    if ($from.depth < 1) return;

    const cellPos = this.getCellPosition($from);
    const formula = this.cellFormulaMap.get(`${cellPos.row}-${cellPos.col}`);
    
    // 数式がある場合は表示
    if (formula) {
      this.showFormulaInCell(formula, $from);
    }
  }

  /**
   * セル内の数式を処理
   */
  private processCellFormula(formula: string, $cellPos: any): void {
    const cellPos = this.getCellPosition($cellPos);
    const cellKey = `${cellPos.row}-${cellPos.col}`;
    
    try {
      // HyperFormulaで数式を評価
      this.hyperFormula.setCellContents(
        { sheet: this.sheetId as any, row: cellPos.row, col: cellPos.col },
        formula
      );
      
      const result = this.hyperFormula.getCellValue({
        sheet: this.sheetId as any,
        row: cellPos.row,
        col: cellPos.col
      });
      
      // 数式をマップに保存
      this.cellFormulaMap.set(cellKey, formula);
      
      // セルの表示を計算結果に更新
      this.updateCellDisplay(result, $cellPos, formula);
      
      // 表計算変更イベントを発行
      const event: SpreadsheetChangeEvent = {
        id: uuidv4(),
        type: 'spreadsheet.change',
        timestamp: Date.now(),
        sequenceNumber: ++this.sequenceNumber,
        data: {
          cellAddress: `${cellPos.row}-${cellPos.col}`,
          oldValue: formula,
          newValue: result,
          isFormula: true
        }
      };
      
      this.spreadsheetChangeSubject.next(event);
      
    } catch (error) {
      console.error('Formula processing error:', error);
      // エラーの場合は#ERROR!を表示
      this.updateCellDisplay('#ERROR!', $cellPos, formula);
    }
  }

  /**
   * セル位置を取得
   */
  private getCellPosition($cellPos: any): CellPosition {
    // 表内での位置を計算
    let row = 0;
    let col = 0;
    
    // 簡易的な位置計算（実際のテーブル構造に応じて調整が必要）
    const table = $cellPos.node(-2); // table_cell -> table_row -> table
    if (table && table.type.name === 'table') {
      // 行の計算
      for (let i = 0; i < $cellPos.index(-1); i++) {
        row++;
      }
      
      // 列の計算
      const currentRow = $cellPos.node(-1);
      for (let i = 0; i < $cellPos.index(); i++) {
        col++;
      }
    }
    
    return { row, col };
  }

  /**
   * セルの表示を更新
   */
  private updateCellDisplay(value: any, $cellPos: any, formula?: string): void {
    const tr = this.view.state.tr;
    const cellStart = $cellPos.start();
    const cellEnd = $cellPos.end();
    
    // セル内容を計算結果で置換
    const displayValue = this.formatCellValue(value);
    tr.insertText(displayValue, cellStart, cellEnd);
    
    // 数式属性を設定
    if (formula) {
      tr.setNodeMarkup($cellPos.pos, undefined, {
        formula: formula,
        calculated: true
      });
    }
    
    this.view.dispatch(tr);
  }

  /**
   * セルに数式を表示
   */
  private showFormulaInCell(formula: string, $cellPos: any): void {
    const tr = this.view.state.tr;
    const cellStart = $cellPos.start();
    const cellEnd = $cellPos.end();
    
    // セル内容を数式で置換
    tr.insertText(formula, cellStart, cellEnd);
    this.view.dispatch(tr);
  }

  /**
   * セル値をフォーマット
   */
  private formatCellValue(value: any): string {
    if (value === null || value === undefined) return '';
    if (typeof value === 'number') {
      return value.toString();
    }
    if (typeof value === 'boolean') {
      return value ? 'TRUE' : 'FALSE';
    }
    if (value instanceof Error) {
      return '#ERROR!';
    }
    return String(value);
  }

  /**
   * 表を挿入
   * @param rows 行数
   * @param cols 列数
   */
  public insertTable(rows: number, cols: number): void {
    const { state } = this.view;
    const { $from } = state.selection;
    
    // ノードタイプの存在確認
    const tableCellType = this.schema.nodes.table_cell;
    const tableRowType = this.schema.nodes.table_row;
    const tableType = this.schema.nodes.table;
    
    if (!tableCellType || !tableRowType || !tableType) {
      console.error('Table node types not found in schema');
      return;
    }
    
    // 表のノードを作成
    const tableRows = [];
    for (let i = 0; i < rows; i++) {
      const cells = [];
      for (let j = 0; j < cols; j++) {
        const cell = tableCellType.createAndFill();
        if (cell) {
          cells.push(cell);
        }
      }
      if (cells.length > 0) {
        tableRows.push(tableRowType.create(null, cells));
      }
    }
    
    if (tableRows.length > 0) {
      const table = tableType.create(null, tableRows);
      
      // 表を挿入
      const tr = state.tr.replaceSelectionWith(table);
      this.view.dispatch(tr);
    }
  }

  /**
   * セルの値を設定
   * @param row 行
   * @param col 列
   * @param value 値
   */
  public setCellValue(row: number, col: number, value: any): void {
    const cellKey = `${row}-${col}`;
    
    // HyperFormulaで値を設定
    this.hyperFormula.setCellContents(
      { sheet: this.sheetId as any, row, col },
      value
    );
    
    // 数式の場合はマップに保存
    if (typeof value === 'string' && value.startsWith('=')) {
      this.cellFormulaMap.set(cellKey, value);
    }
    
    // 表計算変更イベントを発行
    const event: SpreadsheetChangeEvent = {
      id: uuidv4(),
      type: 'spreadsheet.change',
      timestamp: Date.now(),
      sequenceNumber: ++this.sequenceNumber,
      data: {
        cellAddress: cellKey,
        oldValue: undefined,
        newValue: value,
        isFormula: typeof value === 'string' && value.startsWith('=')
      }
    };
    
    this.spreadsheetChangeSubject.next(event);
  }

  /**
   * セルの値を取得
   * @param row 行
   * @param col 列
   */
  public getCellValue(row: number, col: number): CellValue {
    try {
      const value = this.hyperFormula.getCellValue({
        sheet: this.sheetId as any,
        row,
        col
      });
      
      const formula = this.cellFormulaMap.get(`${row}-${col}`);
      
      let type: CellValue['type'] = 'empty';
      if (value !== null && value !== undefined) {
        if (typeof value === 'number') {
          type = 'number';
        } else if (typeof value === 'string') {
          type = 'string';
        } else if (typeof value === 'boolean') {
          type = 'boolean';
        } else if (value instanceof Error) {
          type = 'error';
        }
      }
      
      return {
        value,
        formula,
        type
      };
    } catch (error) {
      return {
        value: null,
        type: 'error'
      };
    }
  }

  /**
   * シートを作成
   * @param name シート名
   */
  public createSheet(name: string): string {
    return this.hyperFormula.addSheet(name);
  }

  /**
   * シートを削除
   * @param sheetId シートID
   */
  public removeSheet(sheetId: number): void {
    this.hyperFormula.removeSheet(sheetId);
  }

  /**
   * 表計算の変更イベントを観測
   */
  public get spreadsheetChanges$(): Observable<SpreadsheetChangeEvent> {
    return this.spreadsheetChangeSubject.asObservable();
  }

  /**
   * テキストからProseMirrorドキュメントを作成
   */
  private createDocumentFromText(text: string): Node {
    if (!text.trim()) {
      // 空の場合は空の段落を作成
      const docNode = this.schema.nodes['doc'];
      const paragraphNode = this.schema.nodes['paragraph'];
      if (docNode && paragraphNode) {
        const emptyDoc = docNode.createAndFill();
        return emptyDoc || docNode.create(null, [paragraphNode.create()]);
      }
    }

    try {
      // HTMLとして解釈してパース
      const div = document.createElement('div');
      div.innerHTML = text.split('\n').map(line => `<p>${line || '<br>'}</p>`).join('');
      return DOMParser.fromSchema(this.schema).parse(div);
    } catch (error) {
      // パースに失敗した場合は単純なテキストノードとして扱う
      const docNode = this.schema.nodes['doc'];
      const paragraphNode = this.schema.nodes['paragraph'];
      
      if (docNode && paragraphNode) {
        const paragraphs = text.split('\n').map(line => 
          paragraphNode.create(null, 
            line ? [this.schema.text(line)] : []
          )
        );
        return docNode.create(null, paragraphs);
      }
    }
    
    // フォールバック: 空のドキュメント
    const docNode = this.schema.nodes['doc'];
    const paragraphNode = this.schema.nodes['paragraph'];
    if (docNode && paragraphNode) {
      return docNode.create(null, [paragraphNode.create()]);
    }
    
    throw new Error('Could not create document: missing required nodes');
  }

  /**
   * トランザクションを処理
   */
  private handleTransaction(transaction: Transaction): void {
    const newState = this.view.state.apply(transaction);
    this.view.updateState(newState);

    if (transaction.docChanged) {
      const oldContent = this.currentState.content;
      const newContent = this.getTextContent();
      
      // 変更を抽出（ProseMirror形式）
      const changes: Array<{from: number; to: number; insert: string}> = [];
      transaction.steps.forEach(step => {
        if ('from' in step && 'to' in step) {
          changes.push({
            from: (step as any).from,
            to: (step as any).to,
            insert: (step as any).slice?.content?.textBetween(0, (step as any).slice?.content?.size) || ''
          });
        }
      });

      // イベントを作成
      const event: EditorChangeEvent = {
        id: uuidv4(),
        type: 'editor.change',
        timestamp: Date.now(),
        sequenceNumber: ++this.sequenceNumber,
        data: {
          transaction: transaction as any,
          oldContent,
          newContent,
          changes
        }
      };

      // 状態を更新
      const selection = newState.selection;
      this.currentState = {
        ...this.currentState,
        content: newContent,
        cursor: selection.anchor,
        selection: selection.empty ? undefined : {
          from: selection.from,
          to: selection.to
        },
        lastSequenceNumber: this.sequenceNumber
      };

      // イベントを発行
      this.changeSubject.next(event);
    }
  }

  /**
   * ドキュメントからテキストコンテンツを取得
   */
  private getTextContent(): string {
    return this.view.state.doc.textBetween(0, this.view.state.doc.content.size, '\n');
  }

  /**
   * 変更イベントを観測
   */
  public get changes$(): Observable<EditorChangeEvent> {
    return this.changeSubject.asObservable();
  }

  /**
   * 現在の状態を取得
   */
  public getState(): KagamiEditorState {
    return { ...this.currentState };
  }

  /**
   * プログラムからコンテンツを設定
   * @param content 新しいコンテンツ
   */
  public setContent(content: string): void {
    const newDoc = this.createDocumentFromText(content);
    const transaction = this.view.state.tr.replaceWith(
      0, 
      this.view.state.doc.content.size, 
      newDoc.content
    );
    
    this.view.dispatch(transaction);
  }

  /**
   * 読み取り専用モードを切り替え
   * @param readOnly 読み取り専用かどうか
   */
  public setReadOnly(readOnly: boolean): void {
    // ProseMirrorでは editable プロパティで制御
    this.view.setProps({
      editable: () => !readOnly
    });
  }

  /**
   * フォーカスを設定
   */
  public focus(): void {
    this.view.focus();
  }

  /**
   * カーソル位置を設定
   * @param position 位置
   */
  public setCursor(position: number): void {
    const tr = this.view.state.tr;
    const resolvedPos = tr.doc.resolve(Math.min(position, tr.doc.content.size));
    tr.setSelection(new (require('prosemirror-state').TextSelection)(resolvedPos));
    this.view.dispatch(tr);
  }

  /**
   * 選択範囲を設定
   * @param from 開始位置
   * @param to 終了位置
   */
  public setSelection(from: number, to: number): void {
    const tr = this.view.state.tr;
    const fromPos = tr.doc.resolve(Math.min(from, tr.doc.content.size));
    const toPos = tr.doc.resolve(Math.min(to, tr.doc.content.size));
    tr.setSelection(new (require('prosemirror-state').TextSelection)(fromPos, toPos));
    this.view.dispatch(tr);
  }

  /**
   * テキストを挿入
   * @param text 挿入するテキスト
   * @param position 挿入位置（未指定の場合は現在のカーソル位置）
   */
  public insertText(text: string, position?: number): void {
    const pos = position !== undefined ? position : this.view.state.selection.anchor;
    const tr = this.view.state.tr;
    tr.insertText(text, pos);
    this.view.dispatch(tr);
  }

  /**
   * テキストを置換
   * @param from 開始位置
   * @param to 終了位置
   * @param text 置換するテキスト
   */
  public replaceText(from: number, to: number, text: string): void {
    const tr = this.view.state.tr;
    tr.insertText(text, from, to);
    this.view.dispatch(tr);
  }

  /**
   * HTMLコンテンツを取得
   */
  public getHTML(): string {
    const div = document.createElement('div');
    const fragment = this.view.state.doc.content;
    const serializer = require('prosemirror-model').DOMSerializer.fromSchema(this.schema);
    div.appendChild(serializer.serializeFragment(fragment));
    return div.innerHTML;
  }

  /**
   * HTMLコンテンツを設定
   * @param html HTMLコンテンツ
   */
  public setHTML(html: string): void {
    const div = document.createElement('div');
    div.innerHTML = html;
    const doc = DOMParser.fromSchema(this.schema).parse(div);
    const tr = this.view.state.tr.replaceWith(0, this.view.state.doc.content.size, doc.content);
    this.view.dispatch(tr);
  }

  /**
   * マークダウンライクなフォーマットを適用
   * @param format フォーマットタイプ
   */
  public applyFormat(format: 'bold' | 'italic' | 'code'): void {
    const { state } = this.view;
    const { from, to } = state.selection;
    
    if (from === to) return;

    const tr = state.tr;
    const mark = this.schema.marks[format];
    
    if (mark) {
      tr.addMark(from, to, mark.create());
      this.view.dispatch(tr);
    }
  }

  /**
   * 段落を見出しに変換
   * @param level 見出しレベル（1-6）
   */
  public setHeading(level: number): void {
    const { state } = this.view;
    const { $from, $to } = state.selection;
    
    const tr = state.tr;
    const headingType = this.schema.nodes[`heading`];
    
    if (headingType) {
      tr.setBlockType($from.start(), $to.end(), headingType, { level });
      this.view.dispatch(tr);
    }
  }

  /**
   * スキーマを取得
   */
  public getSchema(): Schema {
    return this.schema;
  }

  /**
   * ビューを取得
   */
  public getView(): EditorView {
    return this.view;
  }

  /**
   * Excel インポーターを取得
   */
  public getExcelImporter(): ExcelImporter {
    return this.excelImporter;
  }

  /**
   * Excel インポートイベントを観測
   */
  public get excelImportEvents$(): Observable<any> {
    return this.excelImporter.events$;
  }

  /**
   * エディタを破棄
   */
  public destroy(): void {
    if (this.view) {
      this.view.destroy();
    }
    this.changeSubject.complete();
    this.spreadsheetChangeSubject.complete();
    this.hyperFormula.destroy();
  }
} 