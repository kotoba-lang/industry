import { Observable } from 'rxjs';
import { TableDisplayInfo } from './SpreadsheetRenderer';
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
export declare class SpreadsheetUI {
    private container;
    private eventSubject;
    private cellEditSubject;
    private selectedCell;
    private tables;
    constructor(container: HTMLElement);
    /**
     * UIイベントを監視
     */
    get events$(): Observable<SpreadsheetUIEvent>;
    /**
     * セル編集イベントを監視
     */
    get cellEditEvents$(): Observable<CellEditEvent>;
    /**
     * 表をUI上に表示
     * @param tableInfo 表の表示情報
     */
    displayTable(tableInfo: TableDisplayInfo): void;
    /**
     * 表を更新
     * @param tableId 表ID
     * @param updatedInfo 更新された表情報
     */
    updateTable(tableId: string, updatedInfo: Partial<TableDisplayInfo>): void;
    /**
     * 表をレンダリング
     * @param tableInfo 表の表示情報
     */
    private renderTable;
    /**
     * 表のHTML要素を作成
     * @param tableInfo 表の表示情報
     * @returns 表のHTML要素
     */
    private createTableElement;
    /**
     * 表のグリッドを構築
     * @param tableInfo 表の表示情報
     * @returns グリッド（行×列の2次元配列）
     */
    private buildTableGrid;
    /**
     * セルのHTML要素を作成
     * @param cellInfo セル情報
     * @param tableId 表ID
     * @param row 行番号
     * @param col 列番号
     * @returns セルのHTML要素
     */
    private createCellElement;
    /**
     * 列ラベルを取得（A, B, C, ...）
     * @param col 列番号
     * @returns 列ラベル
     */
    private getColumnLabel;
    /**
     * イベントハンドラーを設定
     */
    private setupEventHandlers;
    /**
     * セルクリック処理
     * @param cellElement セル要素
     */
    private handleCellClick;
    /**
     * セル編集処理
     * @param contentElement セル内容要素
     */
    private handleCellEdit;
    /**
     * セルフォーカス処理
     * @param contentElement セル内容要素
     */
    private handleCellFocus;
    /**
     * セルブラー処理
     * @param contentElement セル内容要素
     */
    private handleCellBlur;
    /**
     * 数式を評価（簡易版）
     * @param formula 数式
     * @returns 評価結果
     */
    private evaluateFormula;
    /**
     * 現在選択されているセルの情報を取得
     */
    getSelectedCell(): {
        tableId: string;
        row: number;
        col: number;
    } | null;
    /**
     * 表一覧を取得
     */
    getTables(): TableDisplayInfo[];
    /**
     * UIを破棄
     */
    destroy(): void;
}
//# sourceMappingURL=SpreadsheetUI.d.ts.map