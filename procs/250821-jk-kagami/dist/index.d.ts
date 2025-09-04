export { KagamiEditor, createKagamiEditor, createDevKagamiEditor } from './KagamiEditor';
export type { KagamiEditorOptions } from './KagamiEditor';
export { ThemeManager, getThemeManager, getCurrentTheme, setTheme, toggleTheme, isDarkMode, isLightMode } from './theme/ThemeManager';
export type { Theme, ThemeChangeEvent, ThemeChangeCallback } from './theme/ThemeManager';
export type { KagamiConfig, KagamiState, KagamiEvent, EditorState, SpreadsheetState, EditorChangeEvent, SpreadsheetChangeEvent, EventStore, Projection, TransactionResult, Command, KafkaConfig, BaseEvent } from './types';
import type { KagamiConfig, KafkaConfig } from './types';
import type { KagamiEditorOptions } from './KagamiEditor';
import { KagamiEditor } from './KagamiEditor';
export { EditorCore } from './editor/EditorCore';
export type { CellPosition, CellValue } from './editor/EditorCore';
export { TransactionManager, BaseCommand } from './transaction/TransactionManager';
export type { CommandExecutionResult } from './transaction/TransactionManager';
export { KafkaEventStore, InMemoryEventStore } from './event-sourcing/KafkaEventStore';
export { ProjectionSystem, EditorProjection, SpreadsheetProjection, ProjectionFactory } from './projection/ProjectionSystem';
export declare const KAGAMI_VERSION = "1.0.0";
export declare const KAGAMI_NAME = "Kagami Editor";
export declare const DEFAULT_KAFKA_CONFIG: KafkaConfig;
export declare const DEFAULT_EDITOR_CONFIG: {
    initialContent: string;
    language: "javascript";
    readOnly: boolean;
};
export declare function createDefaultConfig(overrides?: Partial<KagamiConfig>): KagamiConfig;
export declare function createBrowserKagamiEditor(containerId: string, options?: Partial<KagamiEditorOptions>): KagamiEditor;
export declare function useKagamiEditor(container: HTMLElement | null, options: KagamiEditorOptions): KagamiEditor | null;
export declare function enableDebugMode(): void;
export declare function disableDebugMode(): void;
export declare class KagamiError extends Error {
    code?: string | undefined;
    constructor(message: string, code?: string | undefined);
}
export declare class KagamiConnectionError extends KagamiError {
    constructor(message: string);
}
export declare class KagamiValidationError extends KagamiError {
    constructor(message: string);
}
export declare function getVersion(): string;
export declare function getInfo(): {
    name: string;
    version: string;
    description: string;
};
//# sourceMappingURL=index.d.ts.map