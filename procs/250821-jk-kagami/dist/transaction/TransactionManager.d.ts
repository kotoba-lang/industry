import { Observable } from 'rxjs';
import { KagamiEvent, EventStore, Command, TransactionResult } from '@/types';
/**
 * コマンドの実行結果
 */
export interface CommandExecutionResult {
    /** 実行ID */
    executionId: string;
    /** 開始時刻 */
    startTime: number;
    /** 終了時刻 */
    endTime?: number;
    /** 実行状態 */
    status: 'pending' | 'completed' | 'failed';
    /** 結果 */
    result?: TransactionResult;
    /** エラー */
    error?: Error;
}
/**
 * トランザクション管理システム
 * イベントソーシングパターンでコマンドを管理し、
 * 全ての操作をイベントとして記録する
 */
export declare class TransactionManager {
    private eventStore;
    private eventSubject;
    private executionSubject;
    private executionHistory;
    constructor(eventStore: EventStore);
    /**
     * コマンドを実行する
     * @param command 実行するコマンド
     * @returns 実行結果
     */
    executeCommand(command: Command): Promise<CommandExecutionResult>;
    /**
     * 複数のコマンドを順次実行する
     * @param commands 実行するコマンドの配列
     * @returns 実行結果の配列
     */
    executeCommands(commands: Command[]): Promise<CommandExecutionResult[]>;
    /**
     * トランザクションを開始する（複数コマンドを一括実行）
     * @param commands 実行するコマンドの配列
     * @returns 全体の実行結果
     */
    transaction(commands: Command[]): Promise<TransactionResult>;
    /**
     * イベントストリームを取得
     */
    get events$(): Observable<KagamiEvent>;
    /**
     * コマンド実行状況を取得
     */
    get executions$(): Observable<CommandExecutionResult>;
    /**
     * 実行履歴を取得
     * @param executionId 実行ID（省略時は全て）
     */
    getExecutionHistory(executionId?: string): CommandExecutionResult[];
    /**
     * 実行履歴をクリア
     */
    clearExecutionHistory(): void;
    /**
     * リソースを解放
     */
    destroy(): void;
}
/**
 * 基本的なコマンドクラス
 */
export declare abstract class BaseCommand implements Command {
    protected readonly type: string;
    protected readonly userId?: string | undefined;
    protected readonly id: string;
    protected readonly timestamp: number;
    constructor(type: string, userId?: string | undefined);
    /**
     * コマンドを実行する（サブクラスで実装）
     */
    abstract execute(): Promise<TransactionResult>;
    /**
     * コマンドの基本情報を取得
     */
    getInfo(): {
        id: string;
        type: string;
        timestamp: number;
        userId: string | undefined;
    };
}
//# sourceMappingURL=TransactionManager.d.ts.map