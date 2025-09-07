"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.KagamiEditor = void 0;
exports.createKagamiEditor = createKagamiEditor;
exports.createDevKagamiEditor = createDevKagamiEditor;
const rxjs_1 = require("rxjs");
const operators_1 = require("rxjs/operators");
const EditorCore_1 = require("./editor/EditorCore");
const TransactionManager_1 = require("./transaction/TransactionManager");
const KafkaEventStore_1 = require("./event-sourcing/KafkaEventStore");
const ProjectionSystem_1 = require("./projection/ProjectionSystem");
const TableManager_1 = require("./editor/TableManager");
const ExcelImporter_1 = require("./importer/ExcelImporter");
const SpreadsheetRenderer_1 = require("./spreadsheet/SpreadsheetRenderer");
/**
 * Kagamiエディタのメインクラス
 * ProseMirror + イベントソーシング + 表計算機能を統合
 */
class KagamiEditor {
    constructor(container, options) {
        this.container = container;
        this.options = options;
        this.destroySubject = new rxjs_1.Subject();
        this.initialized = false;
        this.eventStore = options.developmentMode
            ? new KafkaEventStore_1.InMemoryEventStore()
            : new KafkaEventStore_1.KafkaEventStore(options.config.kafka);
        this.transactionManager = new TransactionManager_1.TransactionManager(this.eventStore);
        this.projectionSystem = new ProjectionSystem_1.ProjectionSystem(this.eventStore);
        this.editorCore = new EditorCore_1.EditorCore(container, options.config);
        this.excelImporter = new ExcelImporter_1.ExcelImporter();
        // TableManagerはEditorCoreの初期化後に作成
        this.tableManager = new TableManager_1.TableManager(this.editorCore.getSchema(), this.editorCore.getView());
        // SpreadsheetRendererを初期化
        this.spreadsheetRenderer = new SpreadsheetRenderer_1.SpreadsheetRenderer(this.editorCore.getSchema(), this.editorCore.getView());
        this.setupEventSubscriptions();
        if (options.autoConnect !== false) {
            this.initialize();
        }
    }
    /**
     * イベント購読を設定
     */
    setupEventSubscriptions() {
        // エディタの変更を監視
        this.editorCore.changes$
            .pipe((0, operators_1.takeUntil)(this.destroySubject))
            .subscribe(async (event) => {
            await this.eventStore.save(event);
        });
        // 表計算の変更を監視
        this.editorCore.spreadsheetChanges$
            .pipe((0, operators_1.takeUntil)(this.destroySubject))
            .subscribe(async (event) => {
            await this.eventStore.save(event);
        });
        // プロジェクションシステムの状態変更を監視
        this.projectionSystem.editorState$
            .pipe((0, operators_1.takeUntil)(this.destroySubject))
            .subscribe((state) => {
            // エディタの状態を同期
            if (state.content !== this.editorCore.getState().content) {
                this.editorCore.setContent(state.content);
            }
        });
    }
    /**
     * 初期化
     */
    async initialize() {
        if (this.initialized) {
            return;
        }
        try {
            // Kafkaイベントストアの場合は接続
            if (this.eventStore instanceof KafkaEventStore_1.KafkaEventStore) {
                await this.eventStore.connect();
            }
            // 状態を再構築
            await this.projectionSystem.rebuild();
            this.initialized = true;
            console.log('Kagami Editor initialized successfully');
        }
        catch (error) {
            console.error('Failed to initialize Kagami Editor:', error);
            throw error;
        }
    }
    /**
     * 現在の状態を取得
     */
    getState() {
        return this.projectionSystem.getCurrentState();
    }
    /**
     * 状態の変更を監視
     */
    get state$() {
        return this.projectionSystem.state$;
    }
    /**
     * エディタの状態を取得
     */
    getEditorState() {
        return this.projectionSystem.getEditorState();
    }
    /**
     * エディタの状態変更を監視
     */
    get editorState$() {
        return this.projectionSystem.editorState$;
    }
    /**
     * 表計算の状態を取得
     */
    getSpreadsheetState() {
        return this.projectionSystem.getSpreadsheetState();
    }
    /**
     * 表計算の状態変更を監視
     */
    get spreadsheetState$() {
        return this.projectionSystem.spreadsheetState$;
    }
    /**
     * 統合された状態を取得（エディタ + 表計算）
     */
    get combinedState$() {
        return (0, rxjs_1.combineLatest)([
            this.editorState$,
            this.spreadsheetState$
        ]).pipe((0, operators_1.map)(([editor, spreadsheet]) => ({ editor, spreadsheet })));
    }
    /**
     * イベントストリームを取得
     */
    get events$() {
        return this.transactionManager.events$;
    }
    /**
     * エディタのコンテンツを設定
     */
    setContent(content) {
        this.editorCore.setContent(content);
    }
    /**
     * エディタのコンテンツを取得
     */
    getContent() {
        return this.editorCore.getState().content;
    }
    /**
     * エディタにフォーカスを設定
     */
    focus() {
        this.editorCore.focus();
    }
    /**
     * カーソル位置を設定
     */
    setCursor(position) {
        this.editorCore.setCursor(position);
    }
    /**
     * 選択範囲を設定
     */
    setSelection(from, to) {
        this.editorCore.setSelection(from, to);
    }
    /**
     * テキストを挿入
     */
    insertText(text, position) {
        this.editorCore.insertText(text, position);
    }
    /**
     * テキストを置換
     */
    replaceText(from, to, text) {
        this.editorCore.replaceText(from, to, text);
    }
    /**
     * 表を挿入
     */
    insertTable(rows, cols) {
        this.editorCore.insertTable(rows, cols);
    }
    /**
     * 表計算のセル値を設定
     */
    setCellValue(row, col, value) {
        this.editorCore.setCellValue(row, col, value);
    }
    /**
     * 表計算のセル値を取得
     */
    getCellValue(row, col) {
        return this.editorCore.getCellValue(row, col);
    }
    /**
     * 表計算のシートを作成
     */
    createSheet(name) {
        return this.editorCore.createSheet(name);
    }
    /**
     * 表計算のシートを削除
     */
    removeSheet(sheetId) {
        // HyperFormulaのAPIではsheetIdは文字列だが、EditorCoreのremoveSheetは数値を期待
        // 文字列から数値への変換が必要
        const numericSheetId = parseInt(sheetId, 10);
        if (!isNaN(numericSheetId)) {
            this.editorCore.removeSheet(numericSheetId);
        }
    }
    /**
     * 表を作成
     */
    createTable(rows, cols, position) {
        return this.tableManager.createBasicTable(rows, cols, position);
    }
    /**
     * 表の一覧を取得
     */
    getTables() {
        return this.tableManager.getTables();
    }
    /**
     * 表を削除
     */
    deleteTable(tableId) {
        this.tableManager.deleteTable(tableId);
    }
    /**
     * Excelファイルをインポートして表として表示
     * @param file Excelファイル
     * @param options インポートオプション
     * @returns インポート結果
     */
    async importExcelFile(file, options) {
        try {
            // Excelファイルを読み込み
            const excelResults = await this.excelImporter.importFromFile(file, options);
            // ProseMirrorエディタに表を作成
            const tables = [];
            const sheets = [];
            for (const excelResult of excelResults) {
                // 新しいSpreadsheetRendererを使用して表を作成
                const tableDisplayInfo = this.spreadsheetRenderer.renderExcelTable(excelResult);
                tables.push(tableDisplayInfo);
                // HyperFormulaにシートを作成
                const sheetId = this.editorCore.createSheet(excelResult.sheetName);
                // データをセルに設定
                for (let row = 0; row < excelResult.data.length; row++) {
                    const rowData = excelResult.data[row];
                    if (rowData) {
                        for (let col = 0; col < rowData.length; col++) {
                            const cellValue = rowData[col];
                            if (cellValue !== undefined && cellValue !== null) {
                                this.editorCore.setCellValue(row, col, cellValue);
                            }
                        }
                    }
                }
                sheets.push({
                    id: sheetId,
                    name: excelResult.sheetName,
                    rows: excelResult.data.length,
                    cols: excelResult.data[0]?.length || 0
                });
            }
            return {
                tables,
                sheets,
                excelResults
            };
        }
        catch (error) {
            console.error('Excel import failed:', error);
            throw error;
        }
    }
    /**
     * ArrayBufferからExcelデータをインポート
     * @param buffer ArrayBuffer
     * @param options インポートオプション
     * @returns インポート結果
     */
    async importExcelFromArrayBuffer(buffer, options) {
        try {
            // ArrayBufferから読み込み
            const excelResults = await this.excelImporter.importFromArrayBuffer(buffer, options);
            // ProseMirrorエディタに表を作成
            const tables = [];
            const sheets = [];
            for (const excelResult of excelResults) {
                // 新しいSpreadsheetRendererを使用して表を作成
                const tableDisplayInfo = this.spreadsheetRenderer.renderExcelTable(excelResult);
                tables.push(tableDisplayInfo);
                // HyperFormulaにシートを作成
                const sheetId = this.editorCore.createSheet(excelResult.sheetName);
                // データをセルに設定
                for (let row = 0; row < excelResult.data.length; row++) {
                    const rowData = excelResult.data[row];
                    if (rowData) {
                        for (let col = 0; col < rowData.length; col++) {
                            const cellValue = rowData[col];
                            if (cellValue !== undefined && cellValue !== null) {
                                this.editorCore.setCellValue(row, col, cellValue);
                            }
                        }
                    }
                }
                sheets.push({
                    id: sheetId,
                    name: excelResult.sheetName,
                    rows: excelResult.data.length,
                    cols: excelResult.data[0]?.length || 0
                });
            }
            return {
                tables,
                sheets,
                excelResults
            };
        }
        catch (error) {
            console.error('Excel import failed:', error);
            throw error;
        }
    }
    /**
     * Excel インポートイベントを監視
     */
    get excelImportEvents$() {
        return this.excelImporter.events$;
    }
    /**
     * 状態を再構築
     */
    async rebuild(fromSequence) {
        await this.projectionSystem.rebuild(fromSequence);
    }
    /**
     * 状態のスナップショットを作成
     */
    createSnapshot() {
        return this.projectionSystem.createSnapshot();
    }
    /**
     * スナップショットから状態を復元
     */
    restoreFromSnapshot(snapshot) {
        this.projectionSystem.restoreFromSnapshot(snapshot);
    }
    /**
     * 状態をリセット
     */
    reset() {
        this.projectionSystem.reset();
    }
    /**
     * 統計情報を取得
     */
    getStats() {
        return this.projectionSystem.getStats();
    }
    /**
     * 開発者向けデバッグ情報を取得
     */
    getDebugInfo() {
        return {
            initialized: this.initialized,
            eventStoreType: this.eventStore instanceof KafkaEventStore_1.KafkaEventStore ? 'Kafka' : 'InMemory',
            eventStoreConnected: this.eventStore instanceof KafkaEventStore_1.KafkaEventStore
                ? this.eventStore.isConnected()
                : true,
            stats: this.getStats()
        };
    }
    /**
     * 設定を更新
     */
    updateConfig(config) {
        this.options.config = { ...this.options.config, ...config };
        // 必要に応じて各コンポーネントに設定変更を通知
    }
    /**
     * エディタを破棄
     */
    async destroy() {
        this.destroySubject.next();
        this.destroySubject.complete();
        // 各コンポーネントのクリーンアップ
        this.editorCore.destroy();
        this.transactionManager.destroy();
        this.projectionSystem.destroy();
        // イベントストアのクリーンアップ
        if (this.eventStore instanceof KafkaEventStore_1.KafkaEventStore) {
            await this.eventStore.destroy();
        }
        else if (this.eventStore instanceof KafkaEventStore_1.InMemoryEventStore) {
            this.eventStore.destroy();
        }
        this.initialized = false;
        console.log('Kagami Editor destroyed');
    }
}
exports.KagamiEditor = KagamiEditor;
/**
 * Kagamiエディタのファクトリー関数
 */
function createKagamiEditor(container, options) {
    return new KagamiEditor(container, options);
}
/**
 * 開発用の簡易設定でKagamiエディタを作成
 */
function createDevKagamiEditor(container, initialContent = '') {
    const config = {
        kafka: {
            brokers: ['localhost:9092'],
            clientId: 'kagami-dev',
            topic: 'kagami-events'
        },
        editor: {
            initialContent,
            language: 'javascript'
        }
    };
    return new KagamiEditor(container, {
        config,
        developmentMode: true,
        autoConnect: true
    });
}
//# sourceMappingURL=KagamiEditor.js.map