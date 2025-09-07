import { Observable } from 'rxjs';
import { TableMetadata } from './editor/TableManager';
import { ExcelImportResult, ExcelImportOptions, ExcelImportEvent } from './importer/ExcelImporter';
import { TableDisplayInfo } from './spreadsheet/SpreadsheetRenderer';
import { KagamiConfig, KagamiState, EditorState, SpreadsheetState, KagamiEvent } from './types';
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
export declare class KagamiEditor {
    private container;
    private options;
    private editorCore;
    private transactionManager;
    private eventStore;
    private projectionSystem;
    private tableManager;
    private excelImporter;
    private spreadsheetRenderer;
    private destroySubject;
    private initialized;
    constructor(container: HTMLElement, options: KagamiEditorOptions);
    /**
     * イベント購読を設定
     */
    private setupEventSubscriptions;
    /**
     * 初期化
     */
    initialize(): Promise<void>;
    /**
     * 現在の状態を取得
     */
    getState(): KagamiState;
    /**
     * 状態の変更を監視
     */
    get state$(): Observable<KagamiState>;
    /**
     * エディタの状態を取得
     */
    getEditorState(): EditorState;
    /**
     * エディタの状態変更を監視
     */
    get editorState$(): Observable<EditorState>;
    /**
     * 表計算の状態を取得
     */
    getSpreadsheetState(): SpreadsheetState;
    /**
     * 表計算の状態変更を監視
     */
    get spreadsheetState$(): Observable<SpreadsheetState>;
    /**
     * 統合された状態を取得（エディタ + 表計算）
     */
    get combinedState$(): Observable<{
        editor: EditorState;
        spreadsheet: SpreadsheetState;
    }>;
    /**
     * イベントストリームを取得
     */
    get events$(): Observable<KagamiEvent>;
    /**
     * エディタのコンテンツを設定
     */
    setContent(content: string): void;
    /**
     * エディタのコンテンツを取得
     */
    getContent(): string;
    /**
     * エディタにフォーカスを設定
     */
    focus(): void;
    /**
     * カーソル位置を設定
     */
    setCursor(position: number): void;
    /**
     * 選択範囲を設定
     */
    setSelection(from: number, to: number): void;
    /**
     * テキストを挿入
     */
    insertText(text: string, position?: number): void;
    /**
     * テキストを置換
     */
    replaceText(from: number, to: number, text: string): void;
    /**
     * 表を挿入
     */
    insertTable(rows: number, cols: number): void;
    /**
     * 表計算のセル値を設定
     */
    setCellValue(row: number, col: number, value: any): void;
    /**
     * 表計算のセル値を取得
     */
    getCellValue(row: number, col: number): any;
    /**
     * 表計算のシートを作成
     */
    createSheet(name: string): string;
    /**
     * 表計算のシートを削除
     */
    removeSheet(sheetId: string): void;
    /**
     * 表を作成
     */
    createTable(rows: number, cols: number, position?: number): TableMetadata;
    /**
     * 表の一覧を取得
     */
    getTables(): TableMetadata[];
    /**
     * 表を削除
     */
    deleteTable(tableId: string): void;
    /**
     * Excelファイルをインポートして表として表示
     * @param file Excelファイル
     * @param options インポートオプション
     * @returns インポート結果
     */
    importExcelFile(file: File, options?: ExcelImportOptions): Promise<{
        tables: TableDisplayInfo[];
        sheets: Array<{
            id: string;
            name: string;
            rows: number;
            cols: number;
        }>;
        excelResults: ExcelImportResult[];
    }>;
    /**
     * ArrayBufferからExcelデータをインポート
     * @param buffer ArrayBuffer
     * @param options インポートオプション
     * @returns インポート結果
     */
    importExcelFromArrayBuffer(buffer: ArrayBuffer, options?: ExcelImportOptions): Promise<{
        tables: TableDisplayInfo[];
        sheets: Array<{
            id: string;
            name: string;
            rows: number;
            cols: number;
        }>;
        excelResults: ExcelImportResult[];
    }>;
    /**
     * Excel インポートイベントを監視
     */
    get excelImportEvents$(): Observable<ExcelImportEvent>;
    /**
     * 状態を再構築
     */
    rebuild(fromSequence?: number): Promise<void>;
    /**
     * 状態のスナップショットを作成
     */
    createSnapshot(): KagamiState;
    /**
     * スナップショットから状態を復元
     */
    restoreFromSnapshot(snapshot: KagamiState): void;
    /**
     * 状態をリセット
     */
    reset(): void;
    /**
     * 統計情報を取得
     */
    getStats(): {
        totalEvents: number;
        editorLastSequence: number;
        spreadsheetLastSequence: number;
        lastUpdated: number;
    };
    /**
     * 開発者向けデバッグ情報を取得
     */
    getDebugInfo(): {
        initialized: boolean;
        eventStoreType: string;
        eventStoreConnected: boolean;
        stats: {
            totalEvents: number;
            editorLastSequence: number;
            spreadsheetLastSequence: number;
            lastUpdated: number;
        };
    };
    /**
     * 設定を更新
     */
    updateConfig(config: Partial<KagamiConfig>): void;
    /**
     * エディタを破棄
     */
    destroy(): Promise<void>;
}
/**
 * Kagamiエディタのファクトリー関数
 */
export declare function createKagamiEditor(container: HTMLElement, options: KagamiEditorOptions): KagamiEditor;
/**
 * 開発用の簡易設定でKagamiエディタを作成
 */
export declare function createDevKagamiEditor(container: HTMLElement, initialContent?: string): KagamiEditor;
//# sourceMappingURL=KagamiEditor.d.ts.map