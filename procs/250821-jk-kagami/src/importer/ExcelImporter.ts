import * as XLSX from 'xlsx';
import { Observable, Subject } from 'rxjs';

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
export class ExcelImporter {
  private eventSubject = new Subject<ExcelImportEvent>();
  private importId = 0;

  /**
   * Excel インポートイベントを監視
   */
  public get events$(): Observable<ExcelImportEvent> {
    return this.eventSubject.asObservable();
  }

  /**
   * ファイルからExcelデータをインポート
   * @param file Excel ファイル
   * @param options インポートオプション
   * @returns インポート結果
   */
  public async importFromFile(
    file: File,
    options: ExcelImportOptions = {}
  ): Promise<ExcelImportResult[]> {
    const importId = `excel-import-${++this.importId}`;
    
    try {
      // インポート開始イベント
      this.emitEvent({
        id: importId,
        type: 'excel.import.start',
        timestamp: Date.now(),
        data: { fileName: file.name }
      });

      // ファイル読み込み
      const arrayBuffer = await this.readFileAsArrayBuffer(file);
      
      // Excel ワークブックを読み込み
      const workbook = XLSX.read(arrayBuffer, {
        type: 'array',
        cellDates: true,
        cellNF: false,
        cellText: false
      });

      // シートを処理
      const results: ExcelImportResult[] = [];
      const sheetNames = options.sheetName 
        ? [options.sheetName]
        : workbook.SheetNames;

      for (let i = 0; i < sheetNames.length; i++) {
        const sheetName = sheetNames[i];
        if (!sheetName) {
          console.warn(`Sheet name at index ${i} is undefined`);
          continue;
        }
        
        const sheet = workbook.Sheets[sheetName];
        
        if (!sheet) {
          console.warn(`Sheet "${sheetName}" not found`);
          continue;
        }

        // 進捗イベント
        this.emitEvent({
          id: importId,
          type: 'excel.import.progress',
          timestamp: Date.now(),
          data: { 
            fileName: file.name,
            sheetName,
            progress: (i + 1) / sheetNames.length * 100
          }
        });

        // シートデータを変換
        const result = this.convertSheetToData(sheet, sheetName, options);
        results.push(result);
      }

      // インポート完了イベント
      this.emitEvent({
        id: importId,
        type: 'excel.import.complete',
        timestamp: Date.now(),
        data: { fileName: file.name, result: results.length > 0 ? results[0] : undefined }
      });

      return results;

    } catch (error) {
      // エラーイベント
      this.emitEvent({
        id: importId,
        type: 'excel.import.error',
        timestamp: Date.now(),
        data: { fileName: file.name, error: error as Error }
      });
      
      throw error;
    }
  }

  /**
   * ArrayBufferからExcelデータをインポート
   * @param buffer ArrayBuffer
   * @param options インポートオプション
   * @returns インポート結果
   */
  public async importFromArrayBuffer(
    buffer: ArrayBuffer,
    options: ExcelImportOptions = {}
  ): Promise<ExcelImportResult[]> {
    const importId = `excel-import-${++this.importId}`;
    
    try {
      // インポート開始イベント
      this.emitEvent({
        id: importId,
        type: 'excel.import.start',
        timestamp: Date.now(),
        data: { fileName: 'buffer' }
      });

      // Excel ワークブックを読み込み
      const workbook = XLSX.read(buffer, {
        type: 'array',
        cellDates: true,
        cellNF: false,
        cellText: false
      });

      // シートを処理
      const results: ExcelImportResult[] = [];
      const sheetNames = options.sheetName 
        ? [options.sheetName]
        : workbook.SheetNames;

      for (let i = 0; i < sheetNames.length; i++) {
        const sheetName = sheetNames[i];
        if (!sheetName) {
          console.warn(`Sheet name at index ${i} is undefined`);
          continue;
        }
        
        const sheet = workbook.Sheets[sheetName];
        
        if (!sheet) {
          console.warn(`Sheet "${sheetName}" not found`);
          continue;
        }

        // 進捗イベント
        this.emitEvent({
          id: importId,
          type: 'excel.import.progress',
          timestamp: Date.now(),
          data: { 
            fileName: 'buffer',
            sheetName,
            progress: (i + 1) / sheetNames.length * 100
          }
        });

        // シートデータを変換
        const result = this.convertSheetToData(sheet, sheetName, options);
        results.push(result);
      }

      // インポート完了イベント
      this.emitEvent({
        id: importId,
        type: 'excel.import.complete',
        timestamp: Date.now(),
        data: { fileName: 'buffer', result: results[0] }
      });

      return results;

    } catch (error) {
      // エラーイベント
      this.emitEvent({
        id: importId,
        type: 'excel.import.error',
        timestamp: Date.now(),
        data: { fileName: 'buffer', error: error as Error }
      });
      
      throw error;
    }
  }

  /**
   * ファイルを ArrayBuffer として読み込み
   * @param file ファイル
   * @returns ArrayBuffer
   */
  private readFileAsArrayBuffer(file: File): Promise<ArrayBuffer> {
    return new Promise((resolve, reject) => {
      const reader = new FileReader();
      
      reader.onload = (event) => {
        if (event.target?.result instanceof ArrayBuffer) {
          resolve(event.target.result);
        } else {
          reject(new Error('Failed to read file as ArrayBuffer'));
        }
      };
      
      reader.onerror = () => {
        reject(new Error('File reading error'));
      };
      
      reader.readAsArrayBuffer(file);
    });
  }

  /**
   * Excel シートを表形式データに変換
   * @param sheet Excel シート
   * @param sheetName シート名
   * @param options インポートオプション
   * @returns 変換結果
   */
  private convertSheetToData(
    sheet: XLSX.WorkSheet,
    sheetName: string,
    options: ExcelImportOptions
  ): ExcelImportResult {
    const {
      hasHeader = true,
      emptyValue = null,
      forceString = false,
      maxRows = 10000,
      maxColumns = 1000
    } = options;

    // シートの範囲を取得
    const range = XLSX.utils.decode_range(sheet['!ref'] || 'A1:A1');
    
    // 制限を適用
    const endRow = Math.min(range?.e?.r || 0, maxRows - 1);
    const endCol = Math.min(range?.e?.c || 0, maxColumns - 1);
    
    // データを配列に変換
    const data: any[][] = [];
    let headers: string[] = [];
    
    for (let row = range?.s?.r || 0; row <= endRow; row++) {
      const rowData: any[] = [];
      
      for (let col = range?.s?.c || 0; col <= endCol; col++) {
        const cellAddress = XLSX.utils.encode_cell({ r: row, c: col });
        const cell = sheet[cellAddress];
        
        let value = emptyValue;
        
        if (cell) {
          if (forceString) {
            value = cell.w || cell.v?.toString() || emptyValue;
          } else {
            // 型に応じて値を処理
            switch (cell.t) {
              case 'n': // 数値
                value = cell.v;
                break;
              case 's': // 文字列
                value = cell.v;
                break;
              case 'b': // 真偽値
                value = cell.v;
                break;
              case 'd': // 日付
                value = cell.v;
                break;
              case 'e': // エラー
                value = `#ERROR: ${cell.v}`;
                break;
              default:
                value = cell.v || emptyValue;
            }
          }
        }
        
        rowData.push(value);
      }
      
      // ヘッダー行の処理
      if (row === (range?.s?.r || 0) && hasHeader) {
        headers = rowData.map((val, idx) => 
          val?.toString() || `Column ${idx + 1}`
        );
      } else {
        data.push(rowData);
      }
    }
    
    // ヘッダーがない場合は自動生成
    if (!hasHeader && data.length > 0 && data[0]) {
      headers = data[0].map((_, idx) => `Column ${idx + 1}`);
    }

    return {
      sheetName,
      data,
      headers,
      rowCount: data.length,
      columnCount: headers.length
    };
  }

  /**
   * インポートイベントを発行
   * @param event イベント
   */
  private emitEvent(event: ExcelImportEvent): void {
    this.eventSubject.next(event);
  }
} 