/**
 * KSQL関連の型定義
 */

// KSQL クエリのタイプ
export enum KsqlQueryType {
  CREATE_STREAM = 'CREATE_STREAM',
  CREATE_TABLE = 'CREATE_TABLE',
  DROP_STREAM = 'DROP_STREAM',
  DROP_TABLE = 'DROP_TABLE',
  SELECT = 'SELECT',
  INSERT = 'INSERT',
  SHOW_STREAMS = 'SHOW_STREAMS',
  SHOW_TABLES = 'SHOW_TABLES',
  DESCRIBE = 'DESCRIBE'
}

// KSQL データ型
export enum KsqlDataType {
  STRING = 'STRING',
  INTEGER = 'INTEGER',
  BIGINT = 'BIGINT',
  DOUBLE = 'DOUBLE',
  BOOLEAN = 'BOOLEAN',
  ARRAY = 'ARRAY',
  MAP = 'MAP',
  STRUCT = 'STRUCT',
  TIMESTAMP = 'TIMESTAMP',
  DATE = 'DATE',
  TIME = 'TIME'
}

// KSQL カラム定義
export interface KsqlColumn {
  name: string;
  type: KsqlDataType;
  nullable: boolean;
}

// KSQL ストリーム定義
export interface KsqlStream {
  name: string;
  columns: KsqlColumn[];
  topic: string;
  format: KsqlDataFormat;
  partitionBy?: string;
  window?: KsqlWindow;
}

// KSQL テーブル定義
export interface KsqlTable {
  name: string;
  columns: KsqlColumn[];
  topic: string;
  format: KsqlDataFormat;
  primaryKey: string[];
  window?: KsqlWindow;
}

// KSQL データフォーマット
export enum KsqlDataFormat {
  JSON = 'JSON',
  AVRO = 'AVRO',
  PROTOBUF = 'PROTOBUF',
  DELIMITED = 'DELIMITED'
}

// KSQL ウィンドウ定義
export interface KsqlWindow {
  type: 'tumbling' | 'hopping' | 'session';
  size: number;
  unit: 'seconds' | 'minutes' | 'hours' | 'days';
  advance?: number; // hopping window only
  grace?: number; // grace period
}

// KSQL クエリ結果
export interface KsqlResult {
  success: boolean;
  queryId?: string;
  data?: any[];
  error?: string;
  schema?: KsqlColumn[];
  queryType: KsqlQueryType;
}

// 継続的クエリ
export interface ContinuousQuery {
  id: string;
  name: string;
  sql: string;
  status: QueryStatus;
  statistics: QueryStatistics;
  inputSources: string[];
  outputTarget: string;
  createdAt: Date;
  lastExecution?: Date;
}

// クエリステータス
export enum QueryStatus {
  RUNNING = 'RUNNING',
  STOPPED = 'STOPPED',
  ERROR = 'ERROR',
  PAUSED = 'PAUSED'
}

// クエリ統計
export interface QueryStatistics {
  messagesProcessed: number;
  resultsProduced: number;
  averageProcessingTime: number;
  lastError?: string;
  throughput: number;
  memoryUsage: number;
}

// ストリーミング結果
export interface StreamingResult {
  queryId: string;
  data: any[];
  timestamp: Date;
  metadata: Record<string, any>;
}

// KSQL 設定
export interface KsqlConfig {
  processingGuarantee?: 'at_least_once' | 'exactly_once';
  cacheMaxBytesBuffering?: number;
  commitIntervalMs?: number;
  numStreamThreads?: number;
  defaultTimestampExtractor?: string;
  defaultKeySerdeClass?: string;
  defaultValueSerdeClass?: string;
}

// KSQL エンジン統計
export interface KsqlEngineStats {
  totalStreams: number;
  totalTables: number;
  totalQueries: number;
  runningQueries: number;
  messagesPerSecond: number;
  memoryUsage: number;
  cpuUsage: number;
} 