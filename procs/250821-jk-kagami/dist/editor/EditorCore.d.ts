import { EditorView } from 'prosemirror-view';
import { Schema } from 'prosemirror-model';
import { Observable } from 'rxjs';
import { EditorState as KagamiEditorState, EditorChangeEvent, SpreadsheetChangeEvent, KagamiConfig } from '../types';
import { ExcelImporter } from '../importer/ExcelImporter';
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
 * HyperFormulaを統合したProseMirrorベースのエディタコア
 * 表計算機能を直接内包し、セル内で数式を評価
 */
export declare class EditorCore {
    private config;
    private view;
    private changeSubject;
    private spreadsheetChangeSubject;
    private sequenceNumber;
    private currentState;
    private schema;
    private hyperFormula;
    private cellFormulaMap;
    private sheetId;
    private spreadsheetRenderer;
    private excelImporter;
    /**
     * エディタコアを初期化
     * @param parent HTMLエレメント
     * @param config 設定オブジェクト
     */
    constructor(parent: HTMLElement, config: KagamiConfig);
    /**
     * 表のイベントハンドラーを設定
     */
    private setupTableEventHandlers;
    /**
     * 現在のカーソル位置が表のセル内かどうかを判定
     */
    private isInTableCell;
    /**
     * セル内容の変更を処理
     */
    private handleCellContentChange;
    /**
     * セルフォーカス時の処理
     */
    private handleCellFocus;
    /**
     * セル内の数式を処理
     */
    private processCellFormula;
    /**
     * セル位置を取得
     */
    private getCellPosition;
    /**
     * セルの表示を更新
     */
    private updateCellDisplay;
    /**
     * セルに数式を表示
     */
    private showFormulaInCell;
    /**
     * セル値をフォーマット
     */
    private formatCellValue;
    /**
     * 表を挿入
     * @param rows 行数
     * @param cols 列数
     */
    insertTable(rows: number, cols: number): void;
    /**
     * セルの値を設定
     * @param row 行
     * @param col 列
     * @param value 値
     */
    setCellValue(row: number, col: number, value: any): void;
    /**
     * セルの値を取得
     * @param row 行
     * @param col 列
     */
    getCellValue(row: number, col: number): CellValue;
    /**
     * シートを作成
     * @param name シート名
     */
    createSheet(name: string): string;
    /**
     * シートを削除
     * @param sheetId シートID
     */
    removeSheet(sheetId: number): void;
    /**
     * 表計算の変更イベントを観測
     */
    get spreadsheetChanges$(): Observable<SpreadsheetChangeEvent>;
    /**
     * テキストからProseMirrorドキュメントを作成
     */
    private createDocumentFromText;
    /**
     * トランザクションを処理
     */
    private handleTransaction;
    /**
     * ドキュメントからテキストコンテンツを取得
     */
    private getTextContent;
    /**
     * 変更イベントを観測
     */
    get changes$(): Observable<EditorChangeEvent>;
    /**
     * 現在の状態を取得
     */
    getState(): KagamiEditorState;
    /**
     * プログラムからコンテンツを設定
     * @param content 新しいコンテンツ
     */
    setContent(content: string): void;
    /**
     * 読み取り専用モードを切り替え
     * @param readOnly 読み取り専用かどうか
     */
    setReadOnly(readOnly: boolean): void;
    /**
     * フォーカスを設定
     */
    focus(): void;
    /**
     * カーソル位置を設定
     * @param position 位置
     */
    setCursor(position: number): void;
    /**
     * 選択範囲を設定
     * @param from 開始位置
     * @param to 終了位置
     */
    setSelection(from: number, to: number): void;
    /**
     * テキストを挿入
     * @param text 挿入するテキスト
     * @param position 挿入位置（未指定の場合は現在のカーソル位置）
     */
    insertText(text: string, position?: number): void;
    /**
     * テキストを置換
     * @param from 開始位置
     * @param to 終了位置
     * @param text 置換するテキスト
     */
    replaceText(from: number, to: number, text: string): void;
    /**
     * HTMLコンテンツを取得
     */
    getHTML(): string;
    /**
     * HTMLコンテンツを設定
     * @param html HTMLコンテンツ
     */
    setHTML(html: string): void;
    /**
     * マークダウンライクなフォーマットを適用
     * @param format フォーマットタイプ
     */
    applyFormat(format: 'bold' | 'italic' | 'code'): void;
    /**
     * 段落を見出しに変換
     * @param level 見出しレベル（1-6）
     */
    setHeading(level: number): void;
    /**
     * スキーマを取得
     */
    getSchema(): Schema;
    /**
     * ビューを取得
     */
    getView(): EditorView;
    /**
     * Excel インポーターを取得
     */
    getExcelImporter(): ExcelImporter;
    /**
     * Excel インポートイベントを観測
     */
    get excelImportEvents$(): Observable<any>;
    /**
     * エディタを破棄
     */
    destroy(): void;
}
//# sourceMappingURL=EditorCore.d.ts.map