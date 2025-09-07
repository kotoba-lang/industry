"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.SpreadsheetRenderer = void 0;
/**
 * 表計算機能のレンダリングを担当するクラス
 * ProseMirrorの表ノードと表計算データを同期
 */
class SpreadsheetRenderer {
    constructor(schema, view) {
        this.tableCounter = 0;
        this.schema = schema;
        this.view = view;
    }
    /**
     * Excelデータから表をレンダリング
     * @param excelData Excelインポート結果
     * @param insertPosition 挿入位置
     * @returns 表の表示情報
     */
    renderExcelTable(excelData, insertPosition) {
        const tableId = `excel-table-${++this.tableCounter}`;
        const position = insertPosition ?? this.view.state.selection.from;
        // 表ノードを作成
        const tableNode = this.createTableFromExcel(excelData, tableId);
        // 表をエディタに挿入
        this.insertTable(tableNode, position);
        // 表示情報を構築
        const displayInfo = {
            tableId,
            name: excelData.sheetName || `Sheet ${this.tableCounter}`,
            rows: excelData.rowCount + (excelData.headers.length > 0 ? 1 : 0),
            cols: excelData.columnCount,
            cells: this.extractCellDisplayInfo(excelData, tableId),
            position
        };
        return displayInfo;
    }
    /**
     * ExcelデータからProseMirror表ノードを作成
     * @param excelData Excelインポート結果
     * @param tableId 表ID
     * @returns 表ノード
     */
    createTableFromExcel(excelData, tableId) {
        const { table, table_row, table_cell, table_header } = this.schema.nodes;
        if (!table || !table_row || !table_cell) {
            throw new Error('Table node types not found in schema');
        }
        const rows = [];
        const { headers, data } = excelData;
        // ヘッダー行を作成
        if (headers.length > 0) {
            const headerCells = headers.map(header => {
                const content = header ? this.createParagraphContent(String(header)) : [];
                return (table_header || table_cell).create({
                    colspan: 1,
                    rowspan: 1,
                    cellType: 'header'
                }, content);
            });
            rows.push(table_row.create({}, headerCells));
        }
        // データ行を作成
        data.forEach((rowData, rowIndex) => {
            const cells = headers.map((_, colIndex) => {
                const cellValue = rowData[colIndex];
                const content = this.createCellContent(cellValue, rowIndex, colIndex);
                const attrs = {
                    colspan: 1,
                    rowspan: 1,
                    cellType: 'data'
                };
                // 数式の場合は属性を追加
                if (typeof cellValue === 'string' && cellValue.startsWith('=')) {
                    attrs.formula = cellValue;
                    attrs.calculated = true;
                }
                return table_cell.create(attrs, content);
            });
            rows.push(table_row.create({}, cells));
        });
        return table.create({ tableId }, rows);
    }
    /**
     * セルの内容を作成
     * @param value セル値
     * @param row 行番号
     * @param col 列番号
     * @returns セルの内容ノード
     */
    createCellContent(value, row, col) {
        if (value == null || value === '') {
            return [this.createParagraphContent('')];
        }
        // 数式の場合は計算結果を表示
        if (typeof value === 'string' && value.startsWith('=')) {
            try {
                // 実際の計算結果を取得（TODO: HyperFormulaとの連携）
                const calculatedValue = this.evaluateFormula(value);
                return [this.createParagraphContent(String(calculatedValue))];
            }
            catch (error) {
                return [this.createParagraphContent('#ERROR!')];
            }
        }
        // 通常の値
        const displayValue = this.formatCellValue(value);
        return [this.createParagraphContent(displayValue)];
    }
    /**
     * 段落内容を作成
     * @param text テキスト
     * @returns 段落ノード
     */
    createParagraphContent(text) {
        const paragraph = this.schema.nodes.paragraph;
        if (!paragraph) {
            throw new Error('Paragraph node not found in schema');
        }
        const content = text ? [this.schema.text(text)] : [];
        return paragraph.create({}, content);
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
     * セル表示情報を抽出
     * @param excelData Excelデータ
     * @param tableId 表ID
     * @returns セル表示情報配列
     */
    extractCellDisplayInfo(excelData, tableId) {
        const cells = [];
        const { headers, data } = excelData;
        // ヘッダー行の処理
        if (headers.length > 0) {
            headers.forEach((header, col) => {
                cells.push({
                    value: header,
                    displayValue: String(header),
                    isFormula: false,
                    isCalculated: false,
                    type: 'string',
                    row: 0,
                    col
                });
            });
        }
        // データ行の処理
        data.forEach((rowData, rowIndex) => {
            const actualRow = headers.length > 0 ? rowIndex + 1 : rowIndex;
            rowData.forEach((cellValue, col) => {
                const isFormula = typeof cellValue === 'string' && cellValue.startsWith('=');
                cells.push({
                    value: cellValue,
                    displayValue: this.formatCellValue(cellValue),
                    formula: isFormula ? cellValue : undefined,
                    isFormula,
                    isCalculated: isFormula,
                    type: this.determineCellType(cellValue),
                    row: actualRow,
                    col
                });
            });
        });
        return cells;
    }
    /**
     * セル値をフォーマット
     * @param value セル値
     * @returns フォーマットされた値
     */
    formatCellValue(value) {
        if (value == null || value === '') {
            return '';
        }
        if (typeof value === 'number') {
            return value.toLocaleString();
        }
        if (typeof value === 'boolean') {
            return value ? 'TRUE' : 'FALSE';
        }
        if (value instanceof Date) {
            return value.toLocaleDateString();
        }
        if (typeof value === 'string' && value.startsWith('=')) {
            // 数式の場合は計算結果を表示
            try {
                const result = this.evaluateFormula(value);
                return this.formatCellValue(result);
            }
            catch (error) {
                return '#ERROR!';
            }
        }
        return String(value);
    }
    /**
     * セル型を判定
     * @param value セル値
     * @returns セル型
     */
    determineCellType(value) {
        if (value == null || value === '') {
            return 'empty';
        }
        if (typeof value === 'number') {
            return 'number';
        }
        if (typeof value === 'boolean') {
            return 'boolean';
        }
        if (value instanceof Date) {
            return 'date';
        }
        if (typeof value === 'string' && value.startsWith('=')) {
            return 'number'; // 数式は通常数値を返す
        }
        return 'string';
    }
    /**
     * 数式を評価（簡易版）
     * @param formula 数式
     * @returns 評価結果
     */
    evaluateFormula(formula) {
        // 簡易的な数式評価（実際はHyperFormulaを使用）
        try {
            // =の除去
            const expression = formula.substring(1);
            // 簡単な四則演算のみサポート
            if (/^[\d+\-*/().\s]+$/.test(expression)) {
                return eval(expression);
            }
            // セル参照の場合（A1+B1など）
            if (/[A-Z]+\d+/.test(expression)) {
                // TODO: 実際のセル値を取得して計算
                return 'N/A';
            }
            return 'N/A';
        }
        catch (error) {
            return '#ERROR!';
        }
    }
    /**
     * 表の位置を取得
     * @param tableId 表ID
     * @returns 表の位置
     */
    getTablePosition(tableId) {
        const { doc } = this.view.state;
        let position = null;
        doc.descendants((node, pos) => {
            if (node.type.name === 'table' && node.attrs.tableId === tableId) {
                position = pos;
                return false;
            }
            return true;
        });
        return position;
    }
    /**
     * 表を更新
     * @param tableId 表ID
     * @param newData 新しいデータ
     */
    updateTable(tableId, newData) {
        const position = this.getTablePosition(tableId);
        if (position === null) {
            throw new Error(`Table ${tableId} not found`);
        }
        // TODO: 表の更新実装
        console.log(`Updating table ${tableId} at position ${position}`);
    }
}
exports.SpreadsheetRenderer = SpreadsheetRenderer;
//# sourceMappingURL=SpreadsheetRenderer.js.map