import { Subject, Observable } from 'rxjs';
import { v4 as uuidv4 } from 'uuid';
import { 
  KagamiEvent, 
  EventStore, 
  Command, 
  TransactionResult 
} from '@/types';

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
export class TransactionManager {
  private eventSubject = new Subject<KagamiEvent>();
  private executionSubject = new Subject<CommandExecutionResult>();
  private executionHistory = new Map<string, CommandExecutionResult>();

  constructor(private eventStore: EventStore) {
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
  public async executeCommand(command: Command): Promise<CommandExecutionResult> {
    const executionId = uuidv4();
    const startTime = Date.now();
    
    // 実行を記録
    const execution: CommandExecutionResult = {
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

    } catch (error) {
      // エラー時の処理
      execution.endTime = Date.now();
      execution.status = 'failed';
      execution.error = error as Error;
      
      this.executionSubject.next(execution);
      return execution;
    }
  }

  /**
   * 複数のコマンドを順次実行する
   * @param commands 実行するコマンドの配列
   * @returns 実行結果の配列
   */
  public async executeCommands(commands: Command[]): Promise<CommandExecutionResult[]> {
    const results: CommandExecutionResult[] = [];
    
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
  public async transaction(commands: Command[]): Promise<TransactionResult> {
    const allEvents: KagamiEvent[] = [];
    const executions: CommandExecutionResult[] = [];
    
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
      
    } catch (error) {
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
  public get events$(): Observable<KagamiEvent> {
    return this.eventSubject.asObservable();
  }

  /**
   * コマンド実行状況を取得
   */
  public get executions$(): Observable<CommandExecutionResult> {
    return this.executionSubject.asObservable();
  }

  /**
   * 実行履歴を取得
   * @param executionId 実行ID（省略時は全て）
   */
  public getExecutionHistory(executionId?: string): CommandExecutionResult[] {
    if (executionId) {
      const execution = this.executionHistory.get(executionId);
      return execution ? [execution] : [];
    }
    
    return Array.from(this.executionHistory.values());
  }

  /**
   * 実行履歴をクリア
   */
  public clearExecutionHistory(): void {
    this.executionHistory.clear();
  }

  /**
   * リソースを解放
   */
  public destroy(): void {
    this.eventSubject.complete();
    this.executionSubject.complete();
    this.executionHistory.clear();
  }
}

/**
 * 基本的なコマンドクラス
 */
export abstract class BaseCommand implements Command {
  protected readonly id: string;
  protected readonly timestamp: number;

  constructor(
    protected readonly type: string,
    protected readonly userId?: string
  ) {
    this.id = uuidv4();
    this.timestamp = Date.now();
  }

  /**
   * コマンドを実行する（サブクラスで実装）
   */
  abstract execute(): Promise<TransactionResult>;

  /**
   * コマンドの基本情報を取得
   */
  public getInfo() {
    return {
      id: this.id,
      type: this.type,
      timestamp: this.timestamp,
      userId: this.userId
    };
  }
} 