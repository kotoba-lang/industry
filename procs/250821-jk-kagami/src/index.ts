// メインAPI
export { KagamiEditor, createKagamiEditor, createDevKagamiEditor } from './KagamiEditor';
export type { KagamiEditorOptions } from './KagamiEditor';

// テーマ管理
export { 
  ThemeManager, 
  getThemeManager, 
  getCurrentTheme, 
  setTheme, 
  toggleTheme, 
  isDarkMode, 
  isLightMode 
} from './theme/ThemeManager';
export type { 
  Theme, 
  ThemeChangeEvent, 
  ThemeChangeCallback 
} from './theme/ThemeManager';

// 型定義
export type {
  KagamiConfig,
  KagamiState,
  KagamiEvent,
  EditorState,
  SpreadsheetState,
  EditorChangeEvent,
  SpreadsheetChangeEvent,
  EventStore,
  Projection,
  TransactionResult,
  Command,
  KafkaConfig,
  BaseEvent
} from './types';

// 型の再インポート（内部使用）
import type { KagamiConfig, KafkaConfig } from './types';
import type { KagamiEditorOptions } from './KagamiEditor';
import { KagamiEditor, createKagamiEditor } from './KagamiEditor';

// エディタコア
export { EditorCore } from './editor/EditorCore';
export type { CellPosition, CellValue } from './editor/EditorCore';

// トランザクション管理
export { TransactionManager, BaseCommand } from './transaction/TransactionManager';
export type { CommandExecutionResult } from './transaction/TransactionManager';

// イベントストア
export { KafkaEventStore, InMemoryEventStore } from './event-sourcing/KafkaEventStore';

// プロジェクションシステム
export { 
  ProjectionSystem, 
  EditorProjection, 
  SpreadsheetProjection, 
  ProjectionFactory 
} from './projection/ProjectionSystem';

// 定数
export const KAGAMI_VERSION = '1.0.0';
export const KAGAMI_NAME = 'Kagami Editor';

// デフォルト設定
export const DEFAULT_KAFKA_CONFIG: KafkaConfig = {
  brokers: ['localhost:9092'],
  clientId: 'kagami-client',
  topic: 'kagami-events'
};

export const DEFAULT_EDITOR_CONFIG = {
  initialContent: '',
  language: 'javascript' as const,
  readOnly: false
};

// ユーティリティ関数
export function createDefaultConfig(overrides: Partial<KagamiConfig> = {}): KagamiConfig {
  return {
    kafka: { ...DEFAULT_KAFKA_CONFIG, ...overrides.kafka },
    editor: { ...DEFAULT_EDITOR_CONFIG, ...overrides.editor },
    hyperformula: overrides.hyperformula || {}
  };
}

// ブラウザ環境での簡易初期化
export function createBrowserKagamiEditor(
  containerId: string,
  options: Partial<KagamiEditorOptions> = {}
): KagamiEditor {
  const container = document.getElementById(containerId);
  if (!container) {
    throw new Error(`Container element with id "${containerId}" not found`);
  }

  const config = createDefaultConfig(options.config);
  
  return createKagamiEditor(container, {
    config,
    developmentMode: true,
    autoConnect: true,
    ...options
  });
}

// React用のhook（オプション）
export function useKagamiEditor(
  container: HTMLElement | null,
  options: KagamiEditorOptions
): KagamiEditor | null {
  if (!container) return null;
  
  // 実際のReactプロジェクトでは、useEffectとuseRefを使用
  return createKagamiEditor(container, options);
}

// デバッグ用ヘルパー
export function enableDebugMode(): void {
  (window as any).KAGAMI_DEBUG = true;
  console.log('Kagami Debug Mode Enabled');
}

export function disableDebugMode(): void {
  (window as any).KAGAMI_DEBUG = false;
  console.log('Kagami Debug Mode Disabled');
}

// エラークラス
export class KagamiError extends Error {
  constructor(message: string, public code?: string) {
    super(message);
    this.name = 'KagamiError';
  }
}

export class KagamiConnectionError extends KagamiError {
  constructor(message: string) {
    super(message, 'CONNECTION_ERROR');
    this.name = 'KagamiConnectionError';
  }
}

export class KagamiValidationError extends KagamiError {
  constructor(message: string) {
    super(message, 'VALIDATION_ERROR');
    this.name = 'KagamiValidationError';
  }
}

// バージョン情報
export function getVersion(): string {
  return KAGAMI_VERSION;
}

export function getInfo(): {
  name: string;
  version: string;
  description: string;
} {
  return {
    name: KAGAMI_NAME,
    version: KAGAMI_VERSION,
    description: 'CodeMirror-based web editor with event sourcing and spreadsheet capabilities'
  };
} 