"use strict";
var __createBinding = (this && this.__createBinding) || (Object.create ? (function(o, m, k, k2) {
    if (k2 === undefined) k2 = k;
    var desc = Object.getOwnPropertyDescriptor(m, k);
    if (!desc || ("get" in desc ? !m.__esModule : desc.writable || desc.configurable)) {
      desc = { enumerable: true, get: function() { return m[k]; } };
    }
    Object.defineProperty(o, k2, desc);
}) : (function(o, m, k, k2) {
    if (k2 === undefined) k2 = k;
    o[k2] = m[k];
}));
var __setModuleDefault = (this && this.__setModuleDefault) || (Object.create ? (function(o, v) {
    Object.defineProperty(o, "default", { enumerable: true, value: v });
}) : function(o, v) {
    o["default"] = v;
});
var __importStar = (this && this.__importStar) || (function () {
    var ownKeys = function(o) {
        ownKeys = Object.getOwnPropertyNames || function (o) {
            var ar = [];
            for (var k in o) if (Object.prototype.hasOwnProperty.call(o, k)) ar[ar.length] = k;
            return ar;
        };
        return ownKeys(o);
    };
    return function (mod) {
        if (mod && mod.__esModule) return mod;
        var result = {};
        if (mod != null) for (var k = ownKeys(mod), i = 0; i < k.length; i++) if (k[i] !== "default") __createBinding(result, mod, k[i]);
        __setModuleDefault(result, mod);
        return result;
    };
})();
Object.defineProperty(exports, "__esModule", { value: true });
exports.ExcelImporter = void 0;
const XLSX = __importStar(require("xlsx"));
const rxjs_1 = require("rxjs");
/**
 * Excel ファイルのインポート機能を提供するクラス
 */
class ExcelImporter {
    constructor() {
        this.eventSubject = new rxjs_1.Subject();
        this.importId = 0;
    }
    /**
     * Excel インポートイベントを監視
     */
    get events$() {
        return this.eventSubject.asObservable();
    }
    /**
     * ファイルからExcelデータをインポート
     * @param file Excel ファイル
     * @param options インポートオプション
     * @returns インポート結果
     */
    async importFromFile(file, options = {}) {
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
            const results = [];
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
        }
        catch (error) {
            // エラーイベント
            this.emitEvent({
                id: importId,
                type: 'excel.import.error',
                timestamp: Date.now(),
                data: { fileName: file.name, error: error }
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
    async importFromArrayBuffer(buffer, options = {}) {
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
            const results = [];
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
        }
        catch (error) {
            // エラーイベント
            this.emitEvent({
                id: importId,
                type: 'excel.import.error',
                timestamp: Date.now(),
                data: { fileName: 'buffer', error: error }
            });
            throw error;
        }
    }
    /**
     * ファイルを ArrayBuffer として読み込み
     * @param file ファイル
     * @returns ArrayBuffer
     */
    readFileAsArrayBuffer(file) {
        return new Promise((resolve, reject) => {
            const reader = new FileReader();
            reader.onload = (event) => {
                if (event.target?.result instanceof ArrayBuffer) {
                    resolve(event.target.result);
                }
                else {
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
    convertSheetToData(sheet, sheetName, options) {
        const { hasHeader = true, emptyValue = null, forceString = false, maxRows = 10000, maxColumns = 1000 } = options;
        // シートの範囲を取得
        const range = XLSX.utils.decode_range(sheet['!ref'] || 'A1:A1');
        // 制限を適用
        const endRow = Math.min(range?.e?.r || 0, maxRows - 1);
        const endCol = Math.min(range?.e?.c || 0, maxColumns - 1);
        // データを配列に変換
        const data = [];
        let headers = [];
        for (let row = range?.s?.r || 0; row <= endRow; row++) {
            const rowData = [];
            for (let col = range?.s?.c || 0; col <= endCol; col++) {
                const cellAddress = XLSX.utils.encode_cell({ r: row, c: col });
                const cell = sheet[cellAddress];
                let value = emptyValue;
                if (cell) {
                    if (forceString) {
                        value = cell.w || cell.v?.toString() || emptyValue;
                    }
                    else {
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
                headers = rowData.map((val, idx) => val?.toString() || `Column ${idx + 1}`);
            }
            else {
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
    emitEvent(event) {
        this.eventSubject.next(event);
    }
}
exports.ExcelImporter = ExcelImporter;
//# sourceMappingURL=ExcelImporter.js.map