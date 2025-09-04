import { Schema } from 'prosemirror-model';
import { EditorView } from 'prosemirror-view';
import { ExcelImportResult } from '../importer/ExcelImporter';
/**
 * セルの値と表示情報
 */
export interface CellDisplayInfo {
    value: any;
    displayValue: string;
    formula?: string | undefined;
    isFormula: boolean;
    isCalculated: boolean;
    type: 'number' | 'string' | 'boolean' | 'date' | 'error' | 'empty';
    row: number;
    col: number;
}
/**
 * 表の表示情報
 */
export interface TableDisplayInfo {
    tableId: string;
    name: string;
    rows: number;
    cols: number;
    cells: CellDisplayInfo[];
    position: number;
}
/**
 * 表計算機能のレンダリングを担当するクラス
 * ProseMirrorの表ノードと表計算データを同期
 */
export declare class SpreadsheetRenderer {
    private schema;
    private view;
    private tableCounter;
    constructor(schema: Schema, view: EditorView);
    /**
     * Excelデータから表をレンダリング
     * @param excelData Excelインポート結果
     * @param insertPosition 挿入位置
     * @returns 表の表示情報
     */
    renderExcelTable(excelData: ExcelImportResult, insertPosition?: number): TableDisplayInfo;
    /**
     * ExcelデータからProseMirror表ノードを作成
     * @param excelData Excelインポート結果
     * @param tableId 表ID
     * @returns 表ノード
     */
    private createTableFromExcel;
    /**
     * セルの内容を作成
     * @param value セル値
     * @param row 行番号
     * @param col 列番号
     * @returns セルの内容ノード
     */
    private createCellContent;
    /**
     * 段落内容を作成
     * @param text テキスト
     * @returns 段落ノード
     */
    private createParagraphContent;
    /**
     * 表をエディタに挿入
     * @param tableNode 表ノード
     * @param position 挿入位置
     */
    private insertTable;
    /**
     * セル表示情報を抽出
     * @param excelData Excelデータ
     * @param tableId 表ID
     * @returns セル表示情報配列
     */
    private extractCellDisplayInfo;
    /**
     * セル値をフォーマット
     * @param value セル値
     * @returns フォーマットされた値
     */
    private formatCellValue;
    /**
     * セル型を判定
     * @param value セル値
     * @returns セル型
     */
    private determineCellType;
    /**
     * 数式を評価（簡易版）
     * @param formula 数式
     * @returns 評価結果
     */
    private evaluateFormula;
    /**
     * 表の位置を取得
     * @param tableId 表ID
     * @returns 表の位置
     */
    getTablePosition(tableId: string): number | null;
    /**
     * 表を更新
     * @param tableId 表ID
     * @param newData 新しいデータ
     */
    updateTable(tableId: string, newData: any[][]): void;
}
//# sourceMappingURL=SpreadsheetRenderer.d.ts.map