/**
 * KSQL エンジン
 */

import { Connection } from '../core/Connection';
import { Logger } from '../utils/Logger';
import { 
  KsqlResult, 
  KsqlQueryType, 
  ContinuousQuery, 
  QueryStatus, 
  StreamingResult,
  KsqlStream,
  KsqlTable,
  KsqlColumn,
  KsqlDataType,
  KsqlDataFormat,
  KsqlWindow
} from '../types/KsqlTypes';

export class KsqlEngine {
  private connection: Connection;
  private logger: Logger;
  private wasmKsqlEngine: any = null;
  private continuousQueries: Map<string, ContinuousQuery> = new Map();
  private streamingResults: Map<string, StreamingResult[]> = new Map();

  constructor(connection: Connection, logger: Logger) {
    this.connection = connection;
    this.logger = logger;
  }

  /**
   * KSQL エンジンを初期化
   */
  async initialize(): Promise<void> {
    try {
      const rawConnection = this.connection.getRawConnection();
      const wasmModule = this.connection['wasmModule'];
      
      if (wasmModule && wasmModule.WasmKsqlEngine) {
        this.wasmKsqlEngine = new wasmModule.WasmKsqlEngine();
        this.logger.info('KSQL engine initialized');
      } else {
        this.logger.warn('WASM KSQL engine not available, using mock implementation');
      }
    } catch (error) {
      this.logger.error('Failed to initialize KSQL engine:', error);
      throw error;
    }
  }

  /**
   * KSQL クエリを実行
   */
  async executeKsql(ksql: string): Promise<KsqlResult> {
    try {
      this.logger.debug('Executing KSQL:', ksql);

      if (this.wasmKsqlEngine) {
        // WASM エンジンを使用
        const result = this.wasmKsqlEngine.execute_ksql(ksql);
        return this.convertWasmResult(result);
      } else {
        // モック実装
        return this.executeKsqlMock(ksql);
      }
    } catch (error) {
      this.logger.error('KSQL execution failed:', error);
      return {
        success: false,
        error: error instanceof Error ? error.message : String(error),
        queryType: KsqlQueryType.SELECT
      };
    }
  }

  /**
   * ストリームを作成
   */
  async createStream(stream: KsqlStream): Promise<KsqlResult> {
    const ksql = this.buildCreateStreamKsql(stream);
    return this.executeKsql(ksql);
  }

  /**
   * テーブルを作成
   */
  async createTable(table: KsqlTable): Promise<KsqlResult> {
    const ksql = this.buildCreateTableKsql(table);
    return this.executeKsql(ksql);
  }

  /**
   * ストリームからストリームを作成（クエリベース）
   */
  async createStreamAsSelect(streamName: string, selectQuery: string, partitionBy?: string): Promise<KsqlResult> {
    let ksql = `CREATE STREAM ${streamName} AS ${selectQuery}`;
    if (partitionBy) {
      ksql += ` PARTITION BY ${partitionBy}`;
    }
    return this.executeKsql(ksql);
  }

  /**
   * 継続的クエリを作成
   */
  async createContinuousQuery(name: string, ksql: string): Promise<string> {
    const queryId = `query_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`;
    
    const continuousQuery: ContinuousQuery = {
      id: queryId,
      name,
      sql: ksql,
      status: QueryStatus.RUNNING,
      statistics: {
        messagesProcessed: 0,
        resultsProduced: 0,
        averageProcessingTime: 0,
        throughput: 0,
        memoryUsage: 0
      },
      inputSources: this.extractInputSources(ksql),
      outputTarget: this.extractOutputTarget(ksql),
      createdAt: new Date()
    };

    this.continuousQueries.set(queryId, continuousQuery);
    
    // バックグラウンドで継続的に実行
    this.startContinuousQueryExecution(queryId);
    
    this.logger.info(`Created continuous query: ${name} (${queryId})`);
    return queryId;
  }

  /**
   * 継続的クエリを停止
   */
  async stopContinuousQuery(queryId: string): Promise<boolean> {
    const query = this.continuousQueries.get(queryId);
    if (!query) {
      return false;
    }

    query.status = QueryStatus.STOPPED;
    this.continuousQueries.set(queryId, query);
    
    this.logger.info(`Stopped continuous query: ${queryId}`);
    return true;
  }

  /**
   * 継続的クエリ一覧を取得
   */
  async listContinuousQueries(): Promise<ContinuousQuery[]> {
    return Array.from(this.continuousQueries.values());
  }

  /**
   * ストリーミング結果を取得
   */
  async getStreamingResults(queryId: string, limit?: number): Promise<StreamingResult[]> {
    const results = this.streamingResults.get(queryId) || [];
    return limit ? results.slice(-limit) : results;
  }

  /**
   * ストリームを削除
   */
  async dropStream(streamName: string): Promise<KsqlResult> {
    const ksql = `DROP STREAM ${streamName}`;
    return this.executeKsql(ksql);
  }

  /**
   * テーブルを削除
   */
  async dropTable(tableName: string): Promise<KsqlResult> {
    const ksql = `DROP TABLE ${tableName}`;
    return this.executeKsql(ksql);
  }

  /**
   * ストリーム一覧を表示
   */
  async showStreams(): Promise<KsqlResult> {
    return this.executeKsql('SHOW STREAMS');
  }

  /**
   * テーブル一覧を表示
   */
  async showTables(): Promise<KsqlResult> {
    return this.executeKsql('SHOW TABLES');
  }

  /**
   * ストリーミングデータを追加
   */
  async addStreamingData(topic: string, data: any): Promise<void> {
    if (this.wasmKsqlEngine && this.wasmKsqlEngine.add_streaming_data) {
      const dataString = typeof data === 'string' ? data : JSON.stringify(data);
      this.wasmKsqlEngine.add_streaming_data(topic, dataString);
    }
    
    // イベントとしても保存
    await this.connection.addEvent(`stream_${topic}`, data);
  }

  /**
   * クリーンアップ
   */
  async cleanup(): Promise<void> {
    // 全ての継続的クエリを停止
    for (const [queryId] of this.continuousQueries) {
      await this.stopContinuousQuery(queryId);
    }
    
    this.continuousQueries.clear();
    this.streamingResults.clear();
    this.wasmKsqlEngine = null;
    
    this.logger.info('KSQL engine cleaned up');
  }

  /**
   * CREATE STREAM KSQLを構築
   */
  private buildCreateStreamKsql(stream: KsqlStream): string {
    const columns = stream.columns.map(col => 
      `${col.name} ${col.type}${col.nullable ? '' : ' NOT NULL'}`
    ).join(', ');
    
    let ksql = `CREATE STREAM ${stream.name} (${columns}) WITH (`;
    ksql += `KAFKA_TOPIC='${stream.topic}', VALUE_FORMAT='${stream.format}'`;
    
    if (stream.partitionBy) {
      ksql += `, PARTITIONS=${stream.partitionBy}`;
    }
    
    ksql += ')';
    
    return ksql;
  }

  /**
   * CREATE TABLE KSQLを構築
   */
  private buildCreateTableKsql(table: KsqlTable): string {
    const columns = table.columns.map(col => 
      `${col.name} ${col.type}${col.nullable ? '' : ' NOT NULL'}`
    ).join(', ');
    
    let ksql = `CREATE TABLE ${table.name} (${columns}) WITH (`;
    ksql += `KAFKA_TOPIC='${table.topic}', VALUE_FORMAT='${table.format}'`;
    ksql += `, KEY='${table.primaryKey.join(',')}'`;
    ksql += ')';
    
    return ksql;
  }

  /**
   * WASM結果を変換
   */
  private convertWasmResult(wasmResult: any): KsqlResult {
    return {
      success: wasmResult.success(),
      queryId: wasmResult.query_id(),
      data: wasmResult.data() ? JSON.parse(wasmResult.data()) : undefined,
      error: wasmResult.error_message(),
      queryType: this.parseQueryType(wasmResult.query_type())
    };
  }

  /**
   * モック KSQL 実行
   */
  private executeKsqlMock(ksql: string): KsqlResult {
    const ksqlLower = ksql.trim().toLowerCase();
    
    if (ksqlLower.startsWith('create stream')) {
      return {
        success: true,
        queryType: KsqlQueryType.CREATE_STREAM,
        data: [{ message: 'Stream created successfully' }]
      };
    } else if (ksqlLower.startsWith('create table')) {
      return {
        success: true,
        queryType: KsqlQueryType.CREATE_TABLE,
        data: [{ message: 'Table created successfully' }]
      };
    } else if (ksqlLower.startsWith('show streams')) {
      return {
        success: true,
        queryType: KsqlQueryType.SHOW_STREAMS,
        data: [{ streams: [] }]
      };
    } else if (ksqlLower.startsWith('show tables')) {
      return {
        success: true,
        queryType: KsqlQueryType.SHOW_TABLES,
        data: [{ tables: [] }]
      };
    } else if (ksqlLower.startsWith('select')) {
      return {
        success: true,
        queryType: KsqlQueryType.SELECT,
        data: [{ message: 'Query executed (mock)' }]
      };
    } else {
      return {
        success: false,
        error: `Unsupported KSQL statement: ${ksql}`,
        queryType: KsqlQueryType.SELECT
      };
    }
  }

  /**
   * クエリタイプを解析
   */
  private parseQueryType(queryTypeString: string): KsqlQueryType {
    switch (queryTypeString.toUpperCase()) {
      case 'CREATE_STREAM':
        return KsqlQueryType.CREATE_STREAM;
      case 'CREATE_TABLE':
        return KsqlQueryType.CREATE_TABLE;
      case 'DROP_STREAM':
        return KsqlQueryType.DROP_STREAM;
      case 'DROP_TABLE':
        return KsqlQueryType.DROP_TABLE;
      case 'SELECT':
        return KsqlQueryType.SELECT;
      case 'SHOW_STREAMS':
        return KsqlQueryType.SHOW_STREAMS;
      case 'SHOW_TABLES':
        return KsqlQueryType.SHOW_TABLES;
      default:
        return KsqlQueryType.SELECT;
    }
  }

  /**
   * 継続的クエリの実行を開始
   */
  private async startContinuousQueryExecution(queryId: string): Promise<void> {
    // 簡易実装: 定期的にクエリを実行
    setInterval(async () => {
      const query = this.continuousQueries.get(queryId);
      if (query && query.status === QueryStatus.RUNNING) {
        try {
          // クエリを実行してストリーミング結果を生成
          const result: StreamingResult = {
            queryId,
            data: [{ timestamp: new Date(), message: 'Mock streaming result' }],
            timestamp: new Date(),
            metadata: {}
          };
          
          // 結果を保存
          const results = this.streamingResults.get(queryId) || [];
          results.push(result);
          
          // 結果数を制限
          if (results.length > 1000) {
            results.shift();
          }
          
          this.streamingResults.set(queryId, results);
          
          // 統計を更新
          query.statistics.messagesProcessed++;
          query.statistics.resultsProduced++;
          query.lastExecution = new Date();
          
          this.continuousQueries.set(queryId, query);
          
        } catch (error) {
          this.logger.error(`Continuous query execution failed: ${queryId}`, error);
          query.status = QueryStatus.ERROR;
          query.statistics.lastError = error instanceof Error ? error.message : String(error);
          this.continuousQueries.set(queryId, query);
        }
      }
    }, 5000); // 5秒間隔
  }

  /**
   * 入力ソースを抽出
   */
  private extractInputSources(ksql: string): string[] {
    // 簡易パーサー: FROM句からテーブル名を抽出
    const fromMatch = ksql.match(/FROM\s+(\w+)/i);
    return fromMatch ? [fromMatch[1]] : [];
  }

  /**
   * 出力ターゲットを抽出
   */
  private extractOutputTarget(ksql: string): string {
    // 簡易パーサー: CREATE ... AS SELECT の場合
    const createMatch = ksql.match(/CREATE\s+(?:STREAM|TABLE)\s+(\w+)/i);
    return createMatch ? createMatch[1] : '';
  }
} 