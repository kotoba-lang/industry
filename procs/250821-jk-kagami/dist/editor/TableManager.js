"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.TableManager = void 0;
const prosemirror_model_1 = require("prosemirror-model");
const prosemirror_tables_1 = require("prosemirror-tables");
/**
 * ProseMirrorでの表作成・管理を行うクラス
 */
class TableManager {
    constructor(schema, view) {
        this.tables = new Map();
        this.tableIdCounter = 0;
        this.schema = schema;
        this.view = view;
    }
    /**
     * スキーマに表ノードを追加
     * @param baseSchema ベーススキーマ
     * @returns 表ノードが追加されたスキーマ
     * @deprecated EditorCoreのcreateTableSchema()を使用してください
     */
    static createSchemaWithTables(baseSchema) {
        const nodes = baseSchema.spec.nodes.append((0, prosemirror_tables_1.tableNodes)({
            tableGroup: "block",
            cellContent: "block+",
            cellAttributes: {
                formula: { default: undefined },
                calculated: { default: false },
                cellType: { default: "data" }
            }
        }));
        return new prosemirror_model_1.Schema({
            nodes,
            marks: baseSchema.spec.marks
        });
    }
    /**
     * Excelデータから表を作成
     * @param excelData Excel インポート結果
     * @param position 挿入位置
     * @returns 作成された表のメタデータ
     */
    createTableFromExcel(excelData, position) {
        const tableId = `table-${++this.tableIdCounter}`;
        const { data, headers, sheetName } = excelData;
        // 表のメタデータを作成
        const metadata = {
            id: tableId,
            name: sheetName || `Table ${this.tableIdCounter}`,
            rowCount: data.length + (headers.length > 0 ? 1 : 0),
            columnCount: headers.length || (data[0]?.length || 0),
            position: position || this.view.state.selection.from,
            headers,
            createdAt: Date.now(),
            updatedAt: Date.now()
        };
        // ProseMirror表ノードを作成
        const tableNode = this.createTableNode(headers, data, tableId);
        // エディタに表を挿入
        this.insertTable(tableNode, metadata.position);
        // メタデータを保存
        this.tables.set(tableId, metadata);
        return metadata;
    }
    /**
     * 表ノードを作成
     * @param headers ヘッダー配列
     * @param data データ配列
     * @param tableId 表ID
     * @returns 表ノード
     */
    createTableNode(headers, data, tableId) {
        const { table, table_row, table_cell, table_header } = this.schema.nodes;
        if (!table || !table_row || !table_cell) {
            throw new Error('Table nodes not found in schema');
        }
        const rows = [];
        const colCount = headers.length || (data[0]?.length || 0);
        // ヘッダー行を作成
        if (headers.length > 0) {
            const headerCells = headers.map(header => {
                const cellNode = table_header || table_cell;
                const content = header ? [this.schema.text(header)] : [];
                return cellNode.create({
                    colspan: 1,
                    rowspan: 1
                }, content);
            });
            rows.push(table_row.create({}, headerCells));
        }
        // データ行を作成
        data.forEach((rowData, rowIndex) => {
            const cells = [];
            for (let colIndex = 0; colIndex < colCount; colIndex++) {
                const value = rowData[colIndex];
                const cellContent = value != null ? [this.schema.text(String(value))] : [];
                cells.push(table_cell.create({
                    colspan: 1,
                    rowspan: 1
                }, cellContent));
            }
            rows.push(table_row.create({}, cells));
        });
        return table.create({}, rows);
    }
    /**
     * 表をエディタに挿入
     * @param tableNode 表ノード
     * @param position 挿入位置
     */
    insertTable(tableNode, position) {
        const tr = this.view.state.tr;
        tr.insert(position, tableNode);
        this.view.dispatch(tr);
    }
    /**
     * 表の位置を検索
     * @param tableId 表ID
     * @returns 表の位置（見つからない場合は -1）
     */
    findTablePosition(tableId) {
        const { doc } = this.view.state;
        let position = -1;
        doc.descendants((node, pos) => {
            if (node.type.name === 'table' && node.attrs.tableId === tableId) {
                position = pos;
                return false; // 検索を停止
            }
            return true;
        });
        return position;
    }
    /**
     * 表の一覧を取得
     * @returns 表のメタデータ一覧
     */
    getTables() {
        return Array.from(this.tables.values());
    }
    /**
     * 表のメタデータを取得
     * @param tableId 表ID
     * @returns 表のメタデータ
     */
    getTableMetadata(tableId) {
        return this.tables.get(tableId);
    }
    /**
     * 表を削除
     * @param tableId 表ID
     */
    deleteTable(tableId) {
        const tablePos = this.findTablePosition(tableId);
        if (tablePos === -1) {
            throw new Error(`Table position not found for ${tableId}`);
        }
        const tableNode = this.view.state.doc.nodeAt(tablePos);
        if (!tableNode) {
            throw new Error(`Table node not found at position ${tablePos}`);
        }
        const tr = this.view.state.tr;
        tr.delete(tablePos, tablePos + tableNode.nodeSize);
        this.view.dispatch(tr);
        // メタデータを削除
        this.tables.delete(tableId);
    }
    /**
     * 基本的な表を作成（テスト用）
     * @param rows 行数
     * @param cols 列数
     * @param position 挿入位置
     * @returns 表のメタデータ
     */
    createBasicTable(rows, cols, position) {
        const tableId = `table-${++this.tableIdCounter}`;
        const headers = Array.from({ length: cols }, (_, i) => `Column ${i + 1}`);
        const data = Array.from({ length: rows }, (_, rowIndex) => Array.from({ length: cols }, (_, colIndex) => `Cell ${rowIndex + 1},${colIndex + 1}`));
        const metadata = {
            id: tableId,
            name: `Table ${this.tableIdCounter}`,
            rowCount: rows + 1, // ヘッダー行を含む
            columnCount: cols,
            position: position || this.view.state.selection.from,
            headers,
            createdAt: Date.now(),
            updatedAt: Date.now()
        };
        const tableNode = this.createTableNode(headers, data, tableId);
        this.insertTable(tableNode, metadata.position);
        this.tables.set(tableId, metadata);
        return metadata;
    }
}
exports.TableManager = TableManager;
//# sourceMappingURL=TableManager.js.map