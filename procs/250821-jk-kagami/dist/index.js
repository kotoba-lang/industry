"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.KagamiValidationError = exports.KagamiConnectionError = exports.KagamiError = exports.DEFAULT_EDITOR_CONFIG = exports.DEFAULT_KAFKA_CONFIG = exports.KAGAMI_NAME = exports.KAGAMI_VERSION = exports.ProjectionFactory = exports.SpreadsheetProjection = exports.EditorProjection = exports.ProjectionSystem = exports.InMemoryEventStore = exports.KafkaEventStore = exports.BaseCommand = exports.TransactionManager = exports.EditorCore = exports.isLightMode = exports.isDarkMode = exports.toggleTheme = exports.setTheme = exports.getCurrentTheme = exports.getThemeManager = exports.ThemeManager = exports.createDevKagamiEditor = exports.createKagamiEditor = exports.KagamiEditor = void 0;
exports.createDefaultConfig = createDefaultConfig;
exports.createBrowserKagamiEditor = createBrowserKagamiEditor;
exports.useKagamiEditor = useKagamiEditor;
exports.enableDebugMode = enableDebugMode;
exports.disableDebugMode = disableDebugMode;
exports.getVersion = getVersion;
exports.getInfo = getInfo;
// メインAPI
var KagamiEditor_1 = require("./KagamiEditor");
Object.defineProperty(exports, "KagamiEditor", { enumerable: true, get: function () { return KagamiEditor_1.KagamiEditor; } });
Object.defineProperty(exports, "createKagamiEditor", { enumerable: true, get: function () { return KagamiEditor_1.createKagamiEditor; } });
Object.defineProperty(exports, "createDevKagamiEditor", { enumerable: true, get: function () { return KagamiEditor_1.createDevKagamiEditor; } });
// テーマ管理
var ThemeManager_1 = require("./theme/ThemeManager");
Object.defineProperty(exports, "ThemeManager", { enumerable: true, get: function () { return ThemeManager_1.ThemeManager; } });
Object.defineProperty(exports, "getThemeManager", { enumerable: true, get: function () { return ThemeManager_1.getThemeManager; } });
Object.defineProperty(exports, "getCurrentTheme", { enumerable: true, get: function () { return ThemeManager_1.getCurrentTheme; } });
Object.defineProperty(exports, "setTheme", { enumerable: true, get: function () { return ThemeManager_1.setTheme; } });
Object.defineProperty(exports, "toggleTheme", { enumerable: true, get: function () { return ThemeManager_1.toggleTheme; } });
Object.defineProperty(exports, "isDarkMode", { enumerable: true, get: function () { return ThemeManager_1.isDarkMode; } });
Object.defineProperty(exports, "isLightMode", { enumerable: true, get: function () { return ThemeManager_1.isLightMode; } });
const KagamiEditor_2 = require("./KagamiEditor");
// エディタコア
var EditorCore_1 = require("./editor/EditorCore");
Object.defineProperty(exports, "EditorCore", { enumerable: true, get: function () { return EditorCore_1.EditorCore; } });
// トランザクション管理
var TransactionManager_1 = require("./transaction/TransactionManager");
Object.defineProperty(exports, "TransactionManager", { enumerable: true, get: function () { return TransactionManager_1.TransactionManager; } });
Object.defineProperty(exports, "BaseCommand", { enumerable: true, get: function () { return TransactionManager_1.BaseCommand; } });
// イベントストア
var KafkaEventStore_1 = require("./event-sourcing/KafkaEventStore");
Object.defineProperty(exports, "KafkaEventStore", { enumerable: true, get: function () { return KafkaEventStore_1.KafkaEventStore; } });
Object.defineProperty(exports, "InMemoryEventStore", { enumerable: true, get: function () { return KafkaEventStore_1.InMemoryEventStore; } });
// プロジェクションシステム
var ProjectionSystem_1 = require("./projection/ProjectionSystem");
Object.defineProperty(exports, "ProjectionSystem", { enumerable: true, get: function () { return ProjectionSystem_1.ProjectionSystem; } });
Object.defineProperty(exports, "EditorProjection", { enumerable: true, get: function () { return ProjectionSystem_1.EditorProjection; } });
Object.defineProperty(exports, "SpreadsheetProjection", { enumerable: true, get: function () { return ProjectionSystem_1.SpreadsheetProjection; } });
Object.defineProperty(exports, "ProjectionFactory", { enumerable: true, get: function () { return ProjectionSystem_1.ProjectionFactory; } });
// 定数
exports.KAGAMI_VERSION = '1.0.0';
exports.KAGAMI_NAME = 'Kagami Editor';
// デフォルト設定
exports.DEFAULT_KAFKA_CONFIG = {
    brokers: ['localhost:9092'],
    clientId: 'kagami-client',
    topic: 'kagami-events'
};
exports.DEFAULT_EDITOR_CONFIG = {
    initialContent: '',
    language: 'javascript',
    readOnly: false
};
// ユーティリティ関数
function createDefaultConfig(overrides = {}) {
    return {
        kafka: { ...exports.DEFAULT_KAFKA_CONFIG, ...overrides.kafka },
        editor: { ...exports.DEFAULT_EDITOR_CONFIG, ...overrides.editor },
        hyperformula: overrides.hyperformula || {}
    };
}
// ブラウザ環境での簡易初期化
function createBrowserKagamiEditor(containerId, options = {}) {
    const container = document.getElementById(containerId);
    if (!container) {
        throw new Error(`Container element with id "${containerId}" not found`);
    }
    const config = createDefaultConfig(options.config);
    return (0, KagamiEditor_2.createKagamiEditor)(container, {
        config,
        developmentMode: true,
        autoConnect: true,
        ...options
    });
}
// React用のhook（オプション）
function useKagamiEditor(container, options) {
    if (!container)
        return null;
    // 実際のReactプロジェクトでは、useEffectとuseRefを使用
    return (0, KagamiEditor_2.createKagamiEditor)(container, options);
}
// デバッグ用ヘルパー
function enableDebugMode() {
    window.KAGAMI_DEBUG = true;
    console.log('Kagami Debug Mode Enabled');
}
function disableDebugMode() {
    window.KAGAMI_DEBUG = false;
    console.log('Kagami Debug Mode Disabled');
}
// エラークラス
class KagamiError extends Error {
    constructor(message, code) {
        super(message);
        this.code = code;
        this.name = 'KagamiError';
    }
}
exports.KagamiError = KagamiError;
class KagamiConnectionError extends KagamiError {
    constructor(message) {
        super(message, 'CONNECTION_ERROR');
        this.name = 'KagamiConnectionError';
    }
}
exports.KagamiConnectionError = KagamiConnectionError;
class KagamiValidationError extends KagamiError {
    constructor(message) {
        super(message, 'VALIDATION_ERROR');
        this.name = 'KagamiValidationError';
    }
}
exports.KagamiValidationError = KagamiValidationError;
// バージョン情報
function getVersion() {
    return exports.KAGAMI_VERSION;
}
function getInfo() {
    return {
        name: exports.KAGAMI_NAME,
        version: exports.KAGAMI_VERSION,
        description: 'CodeMirror-based web editor with event sourcing and spreadsheet capabilities'
    };
}
//# sourceMappingURL=index.js.map