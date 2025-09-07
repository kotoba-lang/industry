"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.BaseCommand = exports.TransactionManager = void 0;
const rxjs_1 = require("rxjs");
const uuid_1 = require("uuid");
/**
 * トランザクション管理システム
 * イベントソーシングパターンでコマンドを管理し、
 * 全ての操作をイベントとして記録する
 */
class TransactionManager {
    constructor(eventStore) {
        this.eventStore = eventStore;
        this.eventSubject = new rxjs_1.Subject();
        this.executionSubject = new rxjs_1.Subject();
        this.executionHistory = new Map();
        // イベントストアのサブスクリプション
        this.eventStore.subscribe((event) => {
            this.eventSubject.next(event);
        });
    }
    /**
     * コマンドを実行する
     * @param command 実行するコマンド
     * @returns 実行結果
     */
    async executeCommand(command) {
        const executionId = (0, uuid_1.v4)();
        const startTime = Date.now();
        // 実行を記録
        const execution = {
            executionId,
            startTime,
            status: 'pending'
        };
        this.executionHistory.set(executionId, execution);
        this.executionSubject.next(execution);
        try {
            // コマンドを実行
            const result = await command.execute();
            // 成功時の処理
            execution.endTime = Date.now();
            execution.status = 'completed';
            execution.result = result;
            // イベントをイベントストアに保存
            if (result.success) {
                for (const event of result.events) {
                    await this.eventStore.save(event);
                }
            }
            this.executionSubject.next(execution);
            return execution;
        }
        catch (error) {
            // エラー時の処理
            execution.endTime = Date.now();
            execution.status = 'failed';
            execution.error = error;
            this.executionSubject.next(execution);
            return execution;
        }
    }
    /**
     * 複数のコマンドを順次実行する
     * @param commands 実行するコマンドの配列
     * @returns 実行結果の配列
     */
    async executeCommands(commands) {
        const results = [];
        for (const command of commands) {
            const result = await this.executeCommand(command);
            results.push(result);
            // 一つでも失敗したら残りをスキップ
            if (result.status === 'failed') {
                break;
            }
        }
        return results;
    }
    /**
     * トランザクションを開始する（複数コマンドを一括実行）
     * @param commands 実行するコマンドの配列
     * @returns 全体の実行結果
     */
    async transaction(commands) {
        const allEvents = [];
        const executions = [];
        try {
            // 全てのコマンドを実行
            for (const command of commands) {
                const execution = await this.executeCommand(command);
                executions.push(execution);
                if (execution.status === 'failed') {
                    throw execution.error || new Error('Command execution failed');
                }
                if (execution.result?.success) {
                    allEvents.push(...execution.result.events);
                }
            }
            return {
                success: true,
                events: allEvents
            };
        }
        catch (error) {
            return {
                success: false,
                events: allEvents,
                error: error instanceof Error ? error.message : 'Unknown error'
            };
        }
    }
    /**
     * イベントストリームを取得
     */
    get events$() {
        return this.eventSubject.asObservable();
    }
    /**
     * コマンド実行状況を取得
     */
    get executions$() {
        return this.executionSubject.asObservable();
    }
    /**
     * 実行履歴を取得
     * @param executionId 実行ID（省略時は全て）
     */
    getExecutionHistory(executionId) {
        if (executionId) {
            const execution = this.executionHistory.get(executionId);
            return execution ? [execution] : [];
        }
        return Array.from(this.executionHistory.values());
    }
    /**
     * 実行履歴をクリア
     */
    clearExecutionHistory() {
        this.executionHistory.clear();
    }
    /**
     * リソースを解放
     */
    destroy() {
        this.eventSubject.complete();
        this.executionSubject.complete();
        this.executionHistory.clear();
    }
}
exports.TransactionManager = TransactionManager;
/**
 * 基本的なコマンドクラス
 */
class BaseCommand {
    constructor(type, userId) {
        this.type = type;
        this.userId = userId;
        this.id = (0, uuid_1.v4)();
        this.timestamp = Date.now();
    }
    /**
     * コマンドの基本情報を取得
     */
    getInfo() {
        return {
            id: this.id,
            type: this.type,
            timestamp: this.timestamp,
            userId: this.userId
        };
    }
}
exports.BaseCommand = BaseCommand;
//# sourceMappingURL=TransactionManager.js.map