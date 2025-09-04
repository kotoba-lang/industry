import { Observable } from 'rxjs';
/**
 * Excel インポートの結果を表現する型
 */
export interface ExcelImportResult {
    /** シート名 */
    sheetName: string;
    /** 表のデータ */
    data: any[][];
    /** 列名配列 */
    headers: string[];
    /** 行数 */
    rowCount: number;
    /** 列数 */
    columnCount: number;
}
/**
 * インポートオプション
 */
export interface ExcelImportOptions {
    /** 最初の行をヘッダーとして扱うか */
    hasHeader?: boolean;
    /** インポートするシート名（指定なしで全シート） */
    sheetName?: string;
    /** 空のセルをどう扱うか */
    emptyValue?: string | null;
    /** 数値を文字列として扱うか */
    forceString?: boolean;
    /** 最大行数制限 */
    maxRows?: number;
    /** 最大列数制限 */
    maxColumns?: number;
}
/**
 * Excel インポートイベント
 */
export interface ExcelImportEvent {
    id: string;
    type: 'excel.import.start' | 'excel.import.progress' | 'excel.import.complete' | 'excel.import.error';
    timestamp: number;
    data: {
        fileName?: string;
        sheetName?: string;
        progress?: number;
        error?: Error;
        result?: ExcelImportResult | undefined;
    };
}
/**
 * Excel ファイルのインポート機能を提供するクラス
 */
export declare class ExcelImporter {
    private eventSubject;
    private importId;
    /**
     * Excel インポートイベントを監視
     */
    get events$(): Observable<ExcelImportEvent>;
    /**
     * ファイルからExcelデータをインポート
     * @param file Excel ファイル
     * @param options インポートオプション
     * @returns インポート結果
     */
    importFromFile(file: File, options?: ExcelImportOptions): Promise<ExcelImportResult[]>;
    /**
     * ArrayBufferからExcelデータをインポート
     * @param buffer ArrayBuffer
     * @param options インポートオプション
     * @returns インポート結果
     */
    importFromArrayBuffer(buffer: ArrayBuffer, options?: ExcelImportOptions): Promise<ExcelImportResult[]>;
    /**
     * ファイルを ArrayBuffer として読み込み
     * @param file ファイル
     * @returns ArrayBuffer
     */
    private readFileAsArrayBuffer;
    /**
     * Excel シートを表形式データに変換
     * @param sheet Excel シート
     * @param sheetName シート名
     * @param options インポートオプション
     * @returns 変換結果
     */
    private convertSheetToData;
    /**
     * インポートイベントを発行
     * @param event イベント
     */
    private emitEvent;
}
//# sourceMappingURL=ExcelImporter.d.ts.map