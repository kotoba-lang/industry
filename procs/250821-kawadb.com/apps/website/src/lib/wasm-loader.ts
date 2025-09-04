/**
 * KawaDB WASM モジュールローダー
 * WebAssembly モジュールのロードと初期化を行う
 */

/**
 * KawaDB ブラウザ設定
 */
interface KawaDBConfig {
  debugMode?: boolean;
  syncEnabled?: boolean;
  storageType?: 'LocalStorage' | 'IndexedDB' | 'Memory';
  maxEvents?: number;
}

/**
 * KSQL結果インターフェース
 */
export interface KsqlResult {
  success: boolean;
  data: string;
  query_id?: string;
  error_message?: string;
  query_type: string;
}

/**
 * 継続的クエリインターフェース
 */
export interface ContinuousQuery {
  id: string;
  name: string;
  ksql: string;
  status: string;
  messages_processed: number;
  results_produced: number;
  created_at: number;
  last_error?: string;
  input_sources: string[];
  output_target: string;
  processing_interval_ms: number;
}

/**
 * ストリーミング結果インターフェース
 */
export interface StreamingResult {
  query_id: string;
  data: string;
  timestamp: number;
  metadata: string;
}

/**
 * KSQLエンジンインターフェース
 */
export interface KsqlEngine {
  execute_ksql(ksql: string): KsqlResult;
  add_streaming_data(source_name: string, data: string): boolean;
  list_continuous_queries(): string;
  stop_continuous_query(query_id: string): boolean;
  start_continuous_query(query_id: string): boolean;
  get_result_stream(query_id: string): string;
  clear_result_stream(query_id: string): boolean;
  get_all_streaming_data(): string;
  clear_streaming_data(source_name: string): boolean;
  get_statistics(): string;
}

/**
 * 拡張されたKawaBrowserDBインターフェース
 */
export interface KawaBrowserDB {
  add_event(event_type: string, data: string): Promise<string>;
  get_events(limit: number): Promise<string>;
  get_current_state(): Promise<string>;
  get_stats(): Promise<string>;
  clear_data(): Promise<void>;
  sync_to_cloud(): Promise<boolean>;
  
  // KSQL機能
  execute_ksql?: (ksql: string) => KsqlResult;
  get_ksql_engine?: () => KsqlEngine;
}

/**
 * WASM情報
 */
export interface WasmInfo {
  version: string;
  build_date: string;
  features: string[];
}

/**
 * モックKSQLエンジン実装
 */
class MockKsqlEngine implements KsqlEngine {
  private continuousQueries: Map<string, ContinuousQuery> = new Map();
  private streamingData: Map<string, any[]> = new Map();
  private resultStreams: Map<string, any[]> = new Map();

  execute_ksql(ksql: string): KsqlResult {
    console.log('🔍 Executing KSQL:', ksql);
    
    const ksqlLower = ksql.trim().toLowerCase();
    
    try {
      if (ksqlLower.startsWith('create stream')) {
        return this.handleCreateStream(ksql);
      } else if (ksqlLower.startsWith('create table')) {
        return this.handleCreateTable(ksql);
      } else if (ksqlLower.startsWith('select')) {
        return this.handleSelect(ksql);
      } else if (ksqlLower.startsWith('show')) {
        return this.handleShow(ksql);
      } else if (ksqlLower.startsWith('drop')) {
        return this.handleDrop(ksql);
      } else {
        return {
          success: false,
          data: '{}',
          error_message: `Unsupported KSQL statement: ${ksql}`,
          query_type: 'ERROR'
        };
      }
    } catch (error) {
      return {
        success: false,
        data: '{}',
        error_message: `KSQL execution error: ${error}`,
        query_type: 'ERROR'
      };
    }
  }

  private handleCreateStream(ksql: string): KsqlResult {
    // CREATE STREAM の簡易処理
    const streamName = this.extractIdentifierAfterKeyword(ksql, 'CREATE STREAM');
    
    if (ksql.toLowerCase().includes('as select')) {
      // CREATE STREAM AS SELECT
      const queryId = `stream_${streamName}`;
      const query: ContinuousQuery = {
        id: queryId,
        name: streamName,
        ksql: ksql,
        status: 'RUNNING',
        messages_processed: 0,
        results_produced: 0,
        created_at: Date.now(),
        input_sources: this.extractInputSources(ksql),
        output_target: streamName,
        processing_interval_ms: 1000
      };
      
      this.continuousQueries.set(queryId, query);
      this.resultStreams.set(queryId, []);
      
      return {
        success: true,
        data: JSON.stringify({
          message: `Continuous stream '${streamName}' created successfully`,
          stream_name: streamName,
          query_id: queryId
        }),
        query_id: queryId,
        query_type: 'CREATE_STREAM_AS_SELECT'
      };
    } else {
      // 通常の CREATE STREAM
      this.streamingData.set(streamName, []);
      
      return {
        success: true,
        data: JSON.stringify({
          message: `Stream '${streamName}' created successfully`,
          stream_name: streamName
        }),
        query_type: 'CREATE_STREAM'
      };
    }
  }

  private handleCreateTable(ksql: string): KsqlResult {
    // CREATE TABLE の簡易処理
    const tableName = this.extractIdentifierAfterKeyword(ksql, 'CREATE TABLE');
    
    if (ksql.toLowerCase().includes('as select')) {
      // CREATE TABLE AS SELECT
      const queryId = `table_${tableName}`;
      const query: ContinuousQuery = {
        id: queryId,
        name: tableName,
        ksql: ksql,
        status: 'RUNNING',
        messages_processed: 0,
        results_produced: 0,
        created_at: Date.now(),
        input_sources: this.extractInputSources(ksql),
        output_target: tableName,
        processing_interval_ms: 5000
      };
      
      this.continuousQueries.set(queryId, query);
      this.resultStreams.set(queryId, []);
      
      return {
        success: true,
        data: JSON.stringify({
          message: `Continuous table '${tableName}' created successfully`,
          table_name: tableName,
          query_id: queryId
        }),
        query_id: queryId,
        query_type: 'CREATE_TABLE_AS_SELECT'
      };
    } else {
      // 通常の CREATE TABLE
      this.streamingData.set(tableName, []);
      
      return {
        success: true,
        data: JSON.stringify({
          message: `Table '${tableName}' created successfully`,
          table_name: tableName
        }),
        query_type: 'CREATE_TABLE'
      };
    }
  }

  private handleSelect(ksql: string): KsqlResult {
    // SELECT クエリの簡易処理
    if (ksql.toLowerCase().includes('emit changes')) {
      // 継続的クエリ
      const queryId = `temp_${Date.now()}`;
      const query: ContinuousQuery = {
        id: queryId,
        name: 'temporary',
        ksql: ksql,
        status: 'RUNNING',
        messages_processed: 0,
        results_produced: 0,
        created_at: Date.now(),
        input_sources: this.extractInputSources(ksql),
        output_target: 'stdout',
        processing_interval_ms: 1000
      };
      
      this.continuousQueries.set(queryId, query);
      this.resultStreams.set(queryId, []);
      
      return {
        success: true,
        data: JSON.stringify({
          message: 'Continuous query started',
          query_id: queryId
        }),
        query_id: queryId,
        query_type: 'SELECT_CONTINUOUS'
      };
    } else {
      // 通常の SELECT
      const inputSources = this.extractInputSources(ksql);
      const results: any[] = [];
      
      for (const source of inputSources) {
        const data = this.streamingData.get(source) || [];
        results.push(...data.slice(-10)); // 最新10件
      }
      
      return {
        success: true,
        data: JSON.stringify(results),
        query_type: 'SELECT'
      };
    }
  }

  private handleShow(ksql: string): KsqlResult {
    const ksqlLower = ksql.toLowerCase();
    
    if (ksqlLower.includes('streams')) {
      const streams = Array.from(this.streamingData.keys());
      return {
        success: true,
        data: JSON.stringify({ streams }),
        query_type: 'SHOW_STREAMS'
      };
    } else if (ksqlLower.includes('tables')) {
      const tables = Array.from(this.streamingData.keys());
      return {
        success: true,
        data: JSON.stringify({ tables }),
        query_type: 'SHOW_TABLES'
      };
    } else if (ksqlLower.includes('queries')) {
      const queries = Array.from(this.continuousQueries.values());
      return {
        success: true,
        data: JSON.stringify(queries),
        query_type: 'SHOW_QUERIES'
      };
    } else {
      return {
        success: false,
        data: '{}',
        error_message: 'Unsupported SHOW statement',
        query_type: 'ERROR'
      };
    }
  }

  private handleDrop(ksql: string): KsqlResult {
    const ksqlLower = ksql.toLowerCase();
    
    if (ksqlLower.includes('stream')) {
      const streamName = this.extractIdentifierAfterKeyword(ksql, 'DROP STREAM');
      this.streamingData.delete(streamName);
      this.stopQueriesForTarget(streamName);
      
      return {
        success: true,
        data: JSON.stringify({
          message: `Stream '${streamName}' dropped successfully`,
          stream_name: streamName
        }),
        query_type: 'DROP_STREAM'
      };
    } else if (ksqlLower.includes('table')) {
      const tableName = this.extractIdentifierAfterKeyword(ksql, 'DROP TABLE');
      this.streamingData.delete(tableName);
      this.stopQueriesForTarget(tableName);
      
      return {
        success: true,
        data: JSON.stringify({
          message: `Table '${tableName}' dropped successfully`,
          table_name: tableName
        }),
        query_type: 'DROP_TABLE'
      };
    } else {
      return {
        success: false,
        data: '{}',
        error_message: 'Unsupported DROP statement',
        query_type: 'ERROR'
      };
    }
  }

  add_streaming_data(source_name: string, data: string): boolean {
    try {
      const jsonData = JSON.parse(data);
      if (!this.streamingData.has(source_name)) {
        this.streamingData.set(source_name, []);
      }
      this.streamingData.get(source_name)!.push(jsonData);
      
      // 関連する継続的クエリをトリガー
      this.triggerContinuousQueries(source_name);
      
      return true;
    } catch (error) {
      console.error('Failed to parse streaming data:', error);
      return false;
    }
  }

  list_continuous_queries(): string {
    const queries = Array.from(this.continuousQueries.values());
    return JSON.stringify(queries);
  }

  stop_continuous_query(query_id: string): boolean {
    const query = this.continuousQueries.get(query_id);
    if (query) {
      query.status = 'STOPPED';
      return true;
    }
    return false;
  }

  start_continuous_query(query_id: string): boolean {
    const query = this.continuousQueries.get(query_id);
    if (query) {
      query.status = 'RUNNING';
      return true;
    }
    return false;
  }

  get_result_stream(query_id: string): string {
    const results = this.resultStreams.get(query_id) || [];
    return JSON.stringify(results);
  }

  clear_result_stream(query_id: string): boolean {
    if (this.resultStreams.has(query_id)) {
      this.resultStreams.set(query_id, []);
      return true;
    }
    return false;
  }

  get_all_streaming_data(): string {
    const data: Record<string, any[]> = {};
    this.streamingData.forEach((value, key) => {
      data[key] = value;
    });
    return JSON.stringify(data);
  }

  clear_streaming_data(source_name: string): boolean {
    if (this.streamingData.has(source_name)) {
      this.streamingData.set(source_name, []);
      return true;
    }
    return false;
  }

  get_statistics(): string {
    const stats = {
      total_continuous_queries: this.continuousQueries.size,
      total_streaming_sources: this.streamingData.size,
      total_result_streams: this.resultStreams.size,
      queries_by_status: this.getQueriesByStatus(),
      total_messages_processed: this.getTotalMessagesProcessed(),
      total_results_produced: this.getTotalResultsProduced()
    };
    return JSON.stringify(stats);
  }

  private extractIdentifierAfterKeyword(sql: string, keyword: string): string {
    const keywordLower = keyword.toLowerCase();
    const sqlLower = sql.toLowerCase();
    
    const start = sqlLower.indexOf(keywordLower);
    if (start === -1) {
      throw new Error(`Keyword '${keyword}' not found`);
    }
    
    const afterKeyword = sql.substring(start + keyword.length).trim();
    const identifier = afterKeyword.split(/\s+/)[0];
    
    if (!identifier) {
      throw new Error('Identifier not found');
    }
    
    return identifier;
  }

  private extractInputSources(sql: string): string[] {
    const sources: string[] = [];
    
    // FROM句を検索
    const fromMatch = sql.toLowerCase().match(/from\s+(\w+)/);
    if (fromMatch) {
      sources.push(fromMatch[1]);
    }
    
    // JOIN句を検索
    const joinMatches = sql.toLowerCase().matchAll(/(?:inner\s+|left\s+|right\s+|full\s+)?join\s+(\w+)/g);
    for (const match of joinMatches) {
      sources.push(match[1]);
    }
    
    return sources;
  }

  private triggerContinuousQueries(sourceName: string): void {
    for (const [queryId, query] of this.continuousQueries) {
      if (query.status === 'RUNNING' && query.input_sources.includes(sourceName)) {
        // 簡易的なクエリ処理
        const result = {
          query_id: queryId,
          timestamp: Date.now(),
          data: { message: 'Mock streaming result', source: sourceName }
        };
        
        const results = this.resultStreams.get(queryId) || [];
        results.push(result);
        
        // 結果数が多すぎる場合は古いものを削除
        if (results.length > 1000) {
          results.splice(0, 100);
        }
        
        this.resultStreams.set(queryId, results);
        
        // 統計を更新
        query.messages_processed += 1;
        query.results_produced += 1;
      }
    }
  }

  private stopQueriesForTarget(target: string): void {
    for (const query of this.continuousQueries.values()) {
      if (query.output_target === target) {
        query.status = 'STOPPED';
      }
    }
  }

  private getQueriesByStatus(): Record<string, number> {
    const statusCounts: Record<string, number> = {};
    
    for (const query of this.continuousQueries.values()) {
      statusCounts[query.status] = (statusCounts[query.status] || 0) + 1;
    }
    
    return statusCounts;
  }

  private getTotalMessagesProcessed(): number {
    return Array.from(this.continuousQueries.values())
      .reduce((total, query) => total + query.messages_processed, 0);
  }

  private getTotalResultsProduced(): number {
    return Array.from(this.continuousQueries.values())
      .reduce((total, query) => total + query.results_produced, 0);
  }
}

/**
 * モックデータベース実装
 * WASMファイルのロードに失敗した場合に使用
 */
class MockKawaBrowserDB implements KawaBrowserDB {
  private events: Array<{ event_type: string; data: string; timestamp: number }> = [];
  private config: KawaDBConfig;
  private ksqlEngine: MockKsqlEngine;

  constructor(config: KawaDBConfig) {
    this.config = config;
    this.ksqlEngine = new MockKsqlEngine();
    console.log('🔧 MockKawaBrowserDB initialized with config:', config);
  }

  async add_event(event_type: string, data: string): Promise<string> {
    const event = {
      event_type,
      data,
      timestamp: Date.now(),
    };
    this.events.push(event);
    
    // LocalStorageに保存
    if (this.config.storageType === 'LocalStorage') {
      localStorage.setItem('kawa_events', JSON.stringify(this.events));
    }
    
    // KSQLエンジンにストリーミングデータとして追加
    this.ksqlEngine.add_streaming_data('events', JSON.stringify(event));
    
    console.log('📝 Event added:', event);
    return `event_${event.timestamp}`;
  }

  async get_events(limit: number): Promise<string> {
    const recentEvents = this.events.slice(-limit);
    return JSON.stringify(recentEvents);
  }

  async get_current_state(): Promise<string> {
    // 簡単な状態を再構築
    const state = {
      total_events: this.events.length,
      last_event_timestamp: this.events.length > 0 ? this.events[this.events.length - 1].timestamp : null,
      event_types: [...new Set(this.events.map(e => e.event_type))],
    };
    return JSON.stringify(state);
  }

  async get_stats(): Promise<string> {
    const stats = {
      total_events: this.events.length,
      storage_size_bytes: JSON.stringify(this.events).length,
      last_updated: Date.now(),
    };
    return JSON.stringify(stats);
  }

  async clear_data(): Promise<void> {
    this.events = [];
    if (this.config.storageType === 'LocalStorage') {
      localStorage.removeItem('kawa_events');
    }
    console.log('🧹 Data cleared');
  }

  async sync_to_cloud(): Promise<boolean> {
    // モック実装：実際の同期は行わない
    console.log('☁️ Mock sync to cloud (not implemented)');
    return false;
  }

  // KSQL機能
  execute_ksql(ksql: string): KsqlResult {
    return this.ksqlEngine.execute_ksql(ksql);
  }

  get_ksql_engine(): KsqlEngine {
    return this.ksqlEngine;
  }
}

/**
 * KawaDB ブラウザ版データベースを作成
 */
export async function createKawaDB(config: KawaDBConfig = {}): Promise<KawaBrowserDB> {
  console.log('🌐 KawaDB Browser Edition を初期化しています...');
  
  // 既存のデータをLocalStorageから読み込み
  const mockDB = new MockKawaBrowserDB(config);
  if (config.storageType === 'LocalStorage') {
    const stored = localStorage.getItem('kawa_events');
    if (stored) {
      try {
        const events = JSON.parse(stored);
        console.log('📂 Loaded', events.length, 'events from localStorage');
        // プライベートフィールドに直接アクセスできないため、イベントを再追加
        for (const event of events) {
          await mockDB.add_event(event.event_type, event.data);
        }
      } catch (error) {
        console.warn('⚠️ Failed to load events from localStorage:', error);
      }
    }
  }
  
  console.log('✅ KawaDB Browser Edition initialized (Mock mode)');
  return mockDB;
}

/**
 * WASM情報を取得
 */
export async function getWasmInfo(): Promise<WasmInfo> {
  return {
    version: '0.1.0',
    build_date: new Date().toISOString(),
    features: ['Mock Mode', 'LocalStorage', 'Event Sourcing'],
  };
} 