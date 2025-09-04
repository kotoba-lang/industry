import { Observable, Subject, combineLatest } from 'rxjs';
import { map, takeUntil } from 'rxjs/operators';

import { EditorCore } from './editor/EditorCore';
import { TransactionManager } from './transaction/TransactionManager';
import { KafkaEventStore, InMemoryEventStore } from './event-sourcing/KafkaEventStore';
import { ProjectionSystem } from './projection/ProjectionSystem';
import { TableManager, TableMetadata } from './editor/TableManager';
import { ExcelImporter, ExcelImportResult, ExcelImportOptions, ExcelImportEvent } from './importer/ExcelImporter';
import { SpreadsheetRenderer, TableDisplayInfo } from './spreadsheet/SpreadsheetRenderer';
import { 
  KagamiConfig, 
  KagamiState, 
  EditorState, 
  SpreadsheetState, 
  EventStore,
  KagamiEvent,
  TransactionResult
} from './types';

/**
 * Kagamiエディタのオプション
 */
export interface KagamiEditorOptions {
  /** 設定 */
  config: KagamiConfig;
  /** 開発モード（インメモリイベントストアを使用） */
  developmentMode?: boolean;
  /** 自動接続 */
  autoConnect?: boolean;
}

/**
 * Kagamiエディタのメインクラス
 * ProseMirror + イベントソーシング + 表計算機能を統合
 */
export class KagamiEditor {
  private editorCore: EditorCore;
  private transactionManager: TransactionManager;
  private eventStore: EventStore;
  private projectionSystem: ProjectionSystem;
  private tableManager: TableManager;
  private excelImporter: ExcelImporter;
  private spreadsheetRenderer: SpreadsheetRenderer;
  private destroySubject = new Subject<void>();
  private initialized = false;

  constructor(
    private container: HTMLElement,
    private options: KagamiEditorOptions
  ) {
    this.eventStore = options.developmentMode 
      ? new InMemoryEventStore()
      : new KafkaEventStore(options.config.kafka);

    this.transactionManager = new TransactionManager(this.eventStore);
    this.projectionSystem = new ProjectionSystem(this.eventStore);
    this.editorCore = new EditorCore(container, options.config);
    this.excelImporter = new ExcelImporter();
    
    // TableManagerはEditorCoreの初期化後に作成
    this.tableManager = new TableManager(this.editorCore.getSchema(), this.editorCore.getView());
    
    // SpreadsheetRendererを初期化
    this.spreadsheetRenderer = new SpreadsheetRenderer(this.editorCore.getSchema(), this.editorCore.getView());

    this.setupEventSubscriptions();

    if (options.autoConnect !== false) {
      this.initialize();
    }
  }

  /**
   * イベント購読を設定
   */
  private setupEventSubscriptions(): void {
    // エディタの変更を監視
    this.editorCore.changes$
      .pipe(takeUntil(this.destroySubject))
      .subscribe(async (event) => {
        await this.eventStore.save(event);
      });

    // 表計算の変更を監視
    this.editorCore.spreadsheetChanges$
      .pipe(takeUntil(this.destroySubject))
      .subscribe(async (event) => {
        await this.eventStore.save(event);
      });

    // プロジェクションシステムの状態変更を監視
    this.projectionSystem.editorState$
      .pipe(takeUntil(this.destroySubject))
      .subscribe((state) => {
        // エディタの状態を同期
        if (state.content !== this.editorCore.getState().content) {
          this.editorCore.setContent(state.content);
        }
      });
  }

  /**
   * 初期化
   */
  public async initialize(): Promise<void> {
    if (this.initialized) {
      return;
    }

    try {
      // Kafkaイベントストアの場合は接続
      if (this.eventStore instanceof KafkaEventStore) {
        await this.eventStore.connect();
      }

      // 状態を再構築
      await this.projectionSystem.rebuild();

      this.initialized = true;
      console.log('Kagami Editor initialized successfully');
    } catch (error) {
      console.error('Failed to initialize Kagami Editor:', error);
      throw error;
    }
  }

  /**
   * 現在の状態を取得
   */
  public getState(): KagamiState {
    return this.projectionSystem.getCurrentState();
  }

  /**
   * 状態の変更を監視
   */
  public get state$(): Observable<KagamiState> {
    return this.projectionSystem.state$;
  }

  /**
   * エディタの状態を取得
   */
  public getEditorState(): EditorState {
    return this.projectionSystem.getEditorState();
  }

  /**
   * エディタの状態変更を監視
   */
  public get editorState$(): Observable<EditorState> {
    return this.projectionSystem.editorState$;
  }

  /**
   * 表計算の状態を取得
   */
  public getSpreadsheetState(): SpreadsheetState {
    return this.projectionSystem.getSpreadsheetState();
  }

  /**
   * 表計算の状態変更を監視
   */
  public get spreadsheetState$(): Observable<SpreadsheetState> {
    return this.projectionSystem.spreadsheetState$;
  }

  /**
   * 統合された状態を取得（エディタ + 表計算）
   */
  public get combinedState$(): Observable<{ editor: EditorState; spreadsheet: SpreadsheetState }> {
    return combineLatest([
      this.editorState$,
      this.spreadsheetState$
    ]).pipe(
      map(([editor, spreadsheet]) => ({ editor, spreadsheet }))
    );
  }

  /**
   * イベントストリームを取得
   */
  public get events$(): Observable<KagamiEvent> {
    return this.transactionManager.events$;
  }

  /**
   * エディタのコンテンツを設定
   */
  public setContent(content: string): void {
    this.editorCore.setContent(content);
  }

  /**
   * エディタのコンテンツを取得
   */
  public getContent(): string {
    return this.editorCore.getState().content;
  }

  /**
   * エディタにフォーカスを設定
   */
  public focus(): void {
    this.editorCore.focus();
  }

  /**
   * カーソル位置を設定
   */
  public setCursor(position: number): void {
    this.editorCore.setCursor(position);
  }

  /**
   * 選択範囲を設定
   */
  public setSelection(from: number, to: number): void {
    this.editorCore.setSelection(from, to);
  }

  /**
   * テキストを挿入
   */
  public insertText(text: string, position?: number): void {
    this.editorCore.insertText(text, position);
  }

  /**
   * テキストを置換
   */
  public replaceText(from: number, to: number, text: string): void {
    this.editorCore.replaceText(from, to, text);
  }

  /**
   * 表を挿入
   */
  public insertTable(rows: number, cols: number): void {
    this.editorCore.insertTable(rows, cols);
  }

  /**
   * 表計算のセル値を設定
   */
  public setCellValue(row: number, col: number, value: any): void {
    this.editorCore.setCellValue(row, col, value);
  }

  /**
   * 表計算のセル値を取得
   */
  public getCellValue(row: number, col: number): any {
    return this.editorCore.getCellValue(row, col);
  }

  /**
   * 表計算のシートを作成
   */
  public createSheet(name: string): string {
    return this.editorCore.createSheet(name);
  }

  /**
   * 表計算のシートを削除
   */
  public removeSheet(sheetId: string): void {
    // HyperFormulaのAPIではsheetIdは文字列だが、EditorCoreのremoveSheetは数値を期待
    // 文字列から数値への変換が必要
    const numericSheetId = parseInt(sheetId, 10);
    if (!isNaN(numericSheetId)) {
      this.editorCore.removeSheet(numericSheetId);
    }
  }

  /**
   * 表を作成
   */
  public createTable(rows: number, cols: number, position?: number): TableMetadata {
    return this.tableManager.createBasicTable(rows, cols, position);
  }

  /**
   * 表の一覧を取得
   */
  public getTables(): TableMetadata[] {
    return this.tableManager.getTables();
  }

  /**
   * 表を削除
   */
  public deleteTable(tableId: string): void {
    this.tableManager.deleteTable(tableId);
  }

  /**
   * Excelファイルをインポートして表として表示
   * @param file Excelファイル
   * @param options インポートオプション
   * @returns インポート結果
   */
  public async importExcelFile(file: File, options?: ExcelImportOptions): Promise<{
    tables: TableDisplayInfo[];
    sheets: Array<{ id: string; name: string; rows: number; cols: number }>;
    excelResults: ExcelImportResult[];
  }> {
    try {
      // Excelファイルを読み込み
      const excelResults = await this.excelImporter.importFromFile(file, options);
      
      // ProseMirrorエディタに表を作成
      const tables: TableDisplayInfo[] = [];
      const sheets: Array<{ id: string; name: string; rows: number; cols: number }> = [];
      
      for (const excelResult of excelResults) {
        // 新しいSpreadsheetRendererを使用して表を作成
        const tableDisplayInfo = this.spreadsheetRenderer.renderExcelTable(excelResult);
        tables.push(tableDisplayInfo);
        
        // HyperFormulaにシートを作成
        const sheetId = this.editorCore.createSheet(excelResult.sheetName);
        
        // データをセルに設定
        for (let row = 0; row < excelResult.data.length; row++) {
          const rowData = excelResult.data[row];
          if (rowData) {
            for (let col = 0; col < rowData.length; col++) {
              const cellValue = rowData[col];
              if (cellValue !== undefined && cellValue !== null) {
                this.editorCore.setCellValue(row, col, cellValue);
              }
            }
          }
        }
        
        sheets.push({
          id: sheetId,
          name: excelResult.sheetName,
          rows: excelResult.data.length,
          cols: excelResult.data[0]?.length || 0
        });
      }
      
      return {
        tables,
        sheets,
        excelResults
      };
    } catch (error) {
      console.error('Excel import failed:', error);
      throw error;
    }
  }

  /**
   * ArrayBufferからExcelデータをインポート
   * @param buffer ArrayBuffer
   * @param options インポートオプション
   * @returns インポート結果
   */
  public async importExcelFromArrayBuffer(buffer: ArrayBuffer, options?: ExcelImportOptions): Promise<{
    tables: TableDisplayInfo[];
    sheets: Array<{ id: string; name: string; rows: number; cols: number }>;
    excelResults: ExcelImportResult[];
  }> {
    try {
      // ArrayBufferから読み込み
      const excelResults = await this.excelImporter.importFromArrayBuffer(buffer, options);
      
      // ProseMirrorエディタに表を作成
      const tables: TableDisplayInfo[] = [];
      const sheets: Array<{ id: string; name: string; rows: number; cols: number }> = [];
      
      for (const excelResult of excelResults) {
        // 新しいSpreadsheetRendererを使用して表を作成
        const tableDisplayInfo = this.spreadsheetRenderer.renderExcelTable(excelResult);
        tables.push(tableDisplayInfo);
        
        // HyperFormulaにシートを作成
        const sheetId = this.editorCore.createSheet(excelResult.sheetName);
        
        // データをセルに設定
        for (let row = 0; row < excelResult.data.length; row++) {
          const rowData = excelResult.data[row];
          if (rowData) {
            for (let col = 0; col < rowData.length; col++) {
              const cellValue = rowData[col];
              if (cellValue !== undefined && cellValue !== null) {
                this.editorCore.setCellValue(row, col, cellValue);
              }
            }
          }
        }
        
        sheets.push({
          id: sheetId,
          name: excelResult.sheetName,
          rows: excelResult.data.length,
          cols: excelResult.data[0]?.length || 0
        });
      }
      
      return {
        tables,
        sheets,
        excelResults
      };
    } catch (error) {
      console.error('Excel import failed:', error);
      throw error;
    }
  }

  /**
   * Excel インポートイベントを監視
   */
  public get excelImportEvents$(): Observable<ExcelImportEvent> {
    return this.excelImporter.events$;
  }

  /**
   * 状態を再構築
   */
  public async rebuild(fromSequence?: number): Promise<void> {
    await this.projectionSystem.rebuild(fromSequence);
  }

  /**
   * 状態のスナップショットを作成
   */
  public createSnapshot(): KagamiState {
    return this.projectionSystem.createSnapshot();
  }

  /**
   * スナップショットから状態を復元
   */
  public restoreFromSnapshot(snapshot: KagamiState): void {
    this.projectionSystem.restoreFromSnapshot(snapshot);
  }

  /**
   * 状態をリセット
   */
  public reset(): void {
    this.projectionSystem.reset();
  }

  /**
   * 統計情報を取得
   */
  public getStats(): {
    totalEvents: number;
    editorLastSequence: number;
    spreadsheetLastSequence: number;
    lastUpdated: number;
  } {
    return this.projectionSystem.getStats();
  }

  /**
   * 開発者向けデバッグ情報を取得
   */
  public getDebugInfo(): {
    initialized: boolean;
    eventStoreType: string;
    eventStoreConnected: boolean;
    stats: {
      totalEvents: number;
      editorLastSequence: number;
      spreadsheetLastSequence: number;
      lastUpdated: number;
    };
  } {
    return {
      initialized: this.initialized,
      eventStoreType: this.eventStore instanceof KafkaEventStore ? 'Kafka' : 'InMemory',
      eventStoreConnected: this.eventStore instanceof KafkaEventStore 
        ? this.eventStore.isConnected() 
        : true,
      stats: this.getStats()
    };
  }

  /**
   * 設定を更新
   */
  public updateConfig(config: Partial<KagamiConfig>): void {
    this.options.config = { ...this.options.config, ...config };
    // 必要に応じて各コンポーネントに設定変更を通知
  }

  /**
   * エディタを破棄
   */
  public async destroy(): Promise<void> {
    this.destroySubject.next();
    this.destroySubject.complete();

    // 各コンポーネントのクリーンアップ
    this.editorCore.destroy();
    this.transactionManager.destroy();
    this.projectionSystem.destroy();

    // イベントストアのクリーンアップ
    if (this.eventStore instanceof KafkaEventStore) {
      await this.eventStore.destroy();
    } else if (this.eventStore instanceof InMemoryEventStore) {
      this.eventStore.destroy();
    }

    this.initialized = false;
    console.log('Kagami Editor destroyed');
  }
}

/**
 * Kagamiエディタのファクトリー関数
 */
export function createKagamiEditor(
  container: HTMLElement,
  options: KagamiEditorOptions
): KagamiEditor {
  return new KagamiEditor(container, options);
}

/**
 * 開発用の簡易設定でKagamiエディタを作成
 */
export function createDevKagamiEditor(
  container: HTMLElement,
  initialContent: string = ''
): KagamiEditor {
  const config: KagamiConfig = {
    kafka: {
      brokers: ['localhost:9092'],
      clientId: 'kagami-dev',
      topic: 'kagami-events'
    },
    editor: {
      initialContent,
      language: 'javascript'
    }
  };

  return new KagamiEditor(container, {
    config,
    developmentMode: true,
    autoConnect: true
  });
} 