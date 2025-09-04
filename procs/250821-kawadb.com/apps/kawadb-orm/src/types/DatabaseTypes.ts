/**
 * データベース関連の型定義
 */

export type StorageType = 'localStorage' | 'indexedDB' | 'memory';

export interface DatabaseConfig {
  storage?: StorageType;
  maxEvents?: number;
  enableSync?: boolean;
  enableKSQL?: boolean;
  debugMode?: boolean;
  syncEndpoint?: string;
  wasmPath?: string;
}

export interface ConnectionOptions {
  timeout?: number;
  retryAttempts?: number;
  retryDelay?: number;
}

export interface QueryOptions {
  limit?: number;
  offset?: number;
  orderBy?: string;
  orderDirection?: 'ASC' | 'DESC';
}

export interface TransactionOptions {
  isolationLevel?: 'READ_UNCOMMITTED' | 'READ_COMMITTED' | 'REPEATABLE_READ' | 'SERIALIZABLE';
  timeout?: number;
}

export interface DatabaseStats {
  totalEvents: number;
  totalTables: number;
  memoryUsage: number;
  storageSize: number;
  lastSync?: Date;
  uptime: number;
}

export interface SyncResult {
  success: boolean;
  eventsUploaded: number;
  eventsDownloaded: number;
  conflictsResolved: number;
  errors: string[];
} 