import { Plugin, PluginKey } from 'prosemirror-state';
import { ExcelImporter, ExcelImportOptions } from '../../importer/ExcelImporter';
import { SpreadsheetRenderer } from '../../spreadsheet/SpreadsheetRenderer';
/**
 * Excel インポートプラグインのキー
 */
export declare const excelImportPluginKey: PluginKey<any>;
/**
 * Excel インポートプラグインの設定
 */
export interface ExcelImportPluginOptions {
    /** ExcelImporter インスタンス */
    excelImporter: ExcelImporter;
    /** SpreadsheetRenderer インスタンス（optional） */
    spreadsheetRenderer?: SpreadsheetRenderer;
    /** インポート時のデフォルトオプション */
    defaultImportOptions?: ExcelImportOptions;
    /** インポートボタンのラベル */
    buttonLabel?: string;
    /** インポートボタンのアイコン */
    buttonIcon?: string;
    /** アップロードファイルタイプ */
    acceptedFileTypes?: string;
}
/**
 * Excel インポートプラグインの状態
 */
export interface ExcelImportPluginState {
    /** インポート中かどうか */
    isImporting: boolean;
    /** エラーメッセージ */
    errorMessage?: string;
    /** インポート進捗 */
    progress?: number;
}
/**
 * Excel インポートプラグインを作成
 */
export declare function createExcelImportPlugin(options: ExcelImportPluginOptions): Plugin;
//# sourceMappingURL=ExcelImportPlugin.d.ts.map