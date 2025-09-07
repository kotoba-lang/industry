import { EditorView } from 'prosemirror-view';
import { Schema } from 'prosemirror-model';
import { ExcelImportResult } from '../importer/ExcelImporter';
/**
 * 表のメタデータ
 */
export interface TableMetadata {
    id: string;
    name: string;
    rowCount: number;
    columnCount: number;
    position: number;
    headers: string[];
    createdAt: number;
    updatedAt: number;
}
/**
 * 表のセル情報
 */
export interface TableCellInfo {
    row: number;
    col: number;
    value: string;
    isHeader: boolean;
    tableId: string;
}
/**
 * ProseMirrorでの表作成・管理を行うクラス
 */
export declare class TableManager {
    private schema;
    private view;
    private tables;
    private tableIdCounter;
    constructor(schema: Schema, view: EditorView);
    /**
     * スキーマに表ノードを追加
     * @param baseSchema ベーススキーマ
     * @returns 表ノードが追加されたスキーマ
     * @deprecated EditorCoreのcreateTableSchema()を使用してください
     */
    static createSchemaWithTables(baseSchema: Schema): Schema;
    /**
     * Excelデータから表を作成
     * @param excelData Excel インポート結果
     * @param position 挿入位置
     * @returns 作成された表のメタデータ
     */
    createTableFromExcel(excelData: ExcelImportResult, position?: number): TableMetadata;
    /**
     * 表ノードを作成
     * @param headers ヘッダー配列
     * @param data データ配列
     * @param tableId 表ID
     * @returns 表ノード
     */
    private createTableNode;
    /**
     * 表をエディタに挿入
     * @param tableNode 表ノード
     * @param position 挿入位置
     */
    private insertTable;
    /**
     * 表の位置を検索
     * @param tableId 表ID
     * @returns 表の位置（見つからない場合は -1）
     */
    private findTablePosition;
    /**
     * 表の一覧を取得
     * @returns 表のメタデータ一覧
     */
    getTables(): TableMetadata[];
    /**
     * 表のメタデータを取得
     * @param tableId 表ID
     * @returns 表のメタデータ
     */
    getTableMetadata(tableId: string): TableMetadata | undefined;
    /**
     * 表を削除
     * @param tableId 表ID
     */
    deleteTable(tableId: string): void;
    /**
     * 基本的な表を作成（テスト用）
     * @param rows 行数
     * @param cols 列数
     * @param position 挿入位置
     * @returns 表のメタデータ
     */
    createBasicTable(rows: number, cols: number, position?: number): TableMetadata;
}
//# sourceMappingURL=TableManager.d.ts.map