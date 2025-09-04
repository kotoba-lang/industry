import { Transaction as PMTransaction } from 'prosemirror-state';
/**
 * イベントソーシングのためのベースイベント型
 */
export interface BaseEvent {
    /** イベントの一意識別子 */
    id: string;
    /** イベントタイプ */
    type: string;
    /** イベント発生時刻 */
    timestamp: number;
    /** イベントデータ */
    data: Record<string, any>;
    /** イベントを発生させたユーザー */
    userId?: string;
    /** イベントのシーケンス番号 */
    sequenceNumber: number;
}
/**
 * エディタの変更イベント
 */
export interface EditorChangeEvent extends BaseEvent {
    type: 'editor.change';
    data: {
        /** ProseMirrorのトランザクション */
        transaction: PMTransaction;
        /** 変更前のコンテンツ */
        oldContent: string;
        /** 変更後のコンテンツ */
        newContent: string;
        /** 変更された範囲 */
        changes: Array<{
            from: number;
            to: number;
            insert: string;
        }>;
    };
}
/**
 * 表計算の変更イベント
 */
export interface SpreadsheetChangeEvent extends BaseEvent {
    type: 'spreadsheet.change';
    data: {
        /** 変更されたセル */
        cellAddress: string;
        /** 変更前の値 */
        oldValue: any;
        /** 変更後の値 */
        newValue: any;
        /** 数式かどうか */
        isFormula: boolean;
    };
}
/**
 * 全てのイベントの統合型
 */
export type KagamiEvent = EditorChangeEvent | SpreadsheetChangeEvent;
/**
 * イベントストアのインターフェース
 */
export interface EventStore {
    /** イベントを保存する */
    save(event: KagamiEvent): Promise<void>;
    /** イベントを取得する */
    getEvents(fromSequence?: number): Promise<KagamiEvent[]>;
    /** イベントストリームを購読する */
    subscribe(callback: (event: KagamiEvent) => void): () => void;
}
/**
 * エディタの状態
 */
export interface EditorState {
    /** ドキュメントの内容 */
    content: string;
    /** カーソル位置 */
    cursor: number;
    /** 選択範囲 */
    selection?: {
        from: number;
        to: number;
    } | undefined;
    /** 最後に適用されたイベントのシーケンス番号 */
    lastSequenceNumber: number;
}
/**
 * 表計算の状態
 */
export interface SpreadsheetState {
    /** HyperFormulaの設定 */
    config: Record<string, any>;
    /** セルの値 */
    cells: Record<string, any>;
    /** 最後に適用されたイベントのシーケンス番号 */
    lastSequenceNumber: number;
}
/**
 * Kagamiエディタの全体状態
 */
export interface KagamiState {
    /** エディタ状態 */
    editor: EditorState;
    /** 表計算状態 */
    spreadsheet: SpreadsheetState;
    /** メタデータ */
    metadata: {
        /** 作成日時 */
        created: number;
        /** 最終更新日時 */
        updated: number;
        /** バージョン */
        version: string;
    };
}
/**
 * プロジェクション（状態再構築）のインターフェース
 */
export interface Projection<T> {
    /** 初期状態 */
    initialState: T;
    /** イベントを適用して状態を更新する */
    apply(state: T, event: KagamiEvent): T;
}
/**
 * Kafkaの設定
 */
export interface KafkaConfig {
    /** Kafkaクラスタのブローカー */
    brokers: string[];
    /** クライアントID */
    clientId: string;
    /** トピック名 */
    topic: string;
    /** グループID */
    groupId?: string;
    /** SSL設定 */
    ssl?: boolean;
    /** 認証設定 */
    sasl?: {
        mechanism: 'plain' | 'scram-sha-256' | 'scram-sha-512';
        username: string;
        password: string;
    } | undefined;
}
/**
 * Kagamiエディタの設定
 */
export interface KagamiConfig {
    /** Kafkaの設定 */
    kafka: KafkaConfig;
    /** HyperFormulaの設定 */
    hyperformula?: Record<string, any>;
    /** エディタの初期設定 */
    editor?: {
        /** 初期コンテンツ */
        initialContent?: string;
        /** 言語モード */
        language?: 'javascript' | 'typescript' | 'text';
        /** 読み取り専用モード */
        readOnly?: boolean;
    };
}
/**
 * トランザクションの結果
 */
export interface TransactionResult {
    /** 成功したかどうか */
    success: boolean;
    /** 適用されたイベント */
    events: KagamiEvent[];
    /** エラーメッセージ（失敗時） */
    error?: string;
}
/**
 * コマンドのインターフェース
 */
export interface Command {
    /** コマンドの実行 */
    execute(): Promise<TransactionResult>;
}
//# sourceMappingURL=index.d.ts.map