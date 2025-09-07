/**
 * KawaORM - メインORM クラス
 * 
 * KawaDB-WASMとのインターフェースを提供し、
 * 高レベルなORM機能とKSQL機能を統合します。
 */

import { EventEmitter } from 'eventemitter3';
import { Entity } from './core/Entity';
import { Connection } from './core/Connection';
import { Repository } from './core/Repository';
import { KsqlEngine } from './ksql/KsqlEngine';
import { WasmLoader } from './utils/WasmLoader';
import { Environment } from './utils/Environment';
import { Logger } from './utils/Logger';
import { DatabaseConfig, ConnectionOptions } from './types/DatabaseTypes';
import { EntityConstructor } from './types/EntityTypes';

/**
 * KawaORM メインクラス
 */
export class KawaORM extends EventEmitter {
  private connection: Connection | null = null;
  private repositories: Map<string, Repository<any>> = new Map();
  private ksqlEngine: KsqlEngine | null = null;
  private wasmLoader: WasmLoader;
  private logger: Logger;
  private config: DatabaseConfig;

  constructor(config: DatabaseConfig = {}) {
    super();
    
    this.config = {
      storage: 'localStorage',
      maxEvents: 10000,
      enableSync: false,
      enableKSQL: true,
      debugMode: false,
      ...config
    };

    this.logger = new Logger(this.config.debugMode);
    this.wasmLoader = new WasmLoader(this.logger);
    
    this.logger.info('KawaORM initialized', { config: this.config });
  }

  /**
   * データベースに接続
   */
  async connect(options: ConnectionOptions = {}): Promise<void> {
    this.logger.info('Connecting to KawaDB...');
    
    try {
      // WASMモジュールをロード
      await this.wasmLoader.load();
      
      // 接続を確立
      this.connection = new Connection(this.wasmLoader, this.config, this.logger);
      await this.connection.connect(options);
      
      // KSQL エンジンを初期化
      if (this.config.enableKSQL) {
        this.ksqlEngine = new KsqlEngine(this.connection, this.logger);
        await this.ksqlEngine.initialize();
      }
      
      this.emit('connected');
      this.logger.info('Connected to KawaDB successfully');
      
    } catch (error) {
      this.logger.error('Failed to connect to KawaDB:', error);
      this.emit('error', error);
      throw error;
    }
  }

  /**
   * データベースから切断
   */
  async disconnect(): Promise<void> {
    if (this.connection) {
      await this.connection.disconnect();
      this.connection = null;
    }
    
    if (this.ksqlEngine) {
      await this.ksqlEngine.cleanup();
      this.ksqlEngine = null;
    }
    
    this.repositories.clear();
    this.emit('disconnected');
    this.logger.info('Disconnected from KawaDB');
  }

  /**
   * エンティティのリポジトリを取得
   */
  getRepository<T extends Entity>(entityConstructor: EntityConstructor<T>): Repository<T> {
    if (!this.connection) {
      throw new Error('Not connected to database. Call connect() first.');
    }

    const entityName = entityConstructor.name;
    
    if (!this.repositories.has(entityName)) {
      const repository = new Repository(entityConstructor, this.connection, this.logger);
      this.repositories.set(entityName, repository);
    }
    
    return this.repositories.get(entityName)!;
  }

  /**
   * KSQLエンジンを取得
   */
  getKsqlEngine(): KsqlEngine {
    if (!this.ksqlEngine) {
      throw new Error('KSQL engine not initialized. Make sure enableKSQL is true in config.');
    }
    
    return this.ksqlEngine;
  }

  /**
   * 生のSQLクエリを実行
   */
  async query(sql: string, params?: any[]): Promise<any> {
    if (!this.connection) {
      throw new Error('Not connected to database. Call connect() first.');
    }
    
    return this.connection.query(sql, params);
  }

  /**
   * トランザクションを開始
   */
  async transaction<T>(callback: (orm: KawaORM) => Promise<T>): Promise<T> {
    if (!this.connection) {
      throw new Error('Not connected to database. Call connect() first.');
    }
    
    await this.connection.beginTransaction();
    
    try {
      const result = await callback(this);
      await this.connection.commitTransaction();
      return result;
    } catch (error) {
      await this.connection.rollbackTransaction();
      throw error;
    }
  }

  /**
   * 接続状態を取得
   */
  isConnected(): boolean {
    return this.connection !== null && this.connection.isConnected();
  }

  /**
   * 現在の環境情報を取得
   */
  getEnvironmentInfo(): any {
    return {
      isWeb: Environment.isWeb(),
      isElectron: Environment.isElectron(),
      isNode: Environment.isNode(),
      supportedFeatures: Environment.getSupportedFeatures(),
      wasmLoaded: this.wasmLoader.isLoaded(),
      connected: this.isConnected()
    };
  }

  /**
   * 統計情報を取得
   */
  async getStats(): Promise<any> {
    if (!this.connection) {
      throw new Error('Not connected to database. Call connect() first.');
    }
    
    return this.connection.getStats();
  }

  /**
   * データベースを最適化
   */
  async optimize(): Promise<void> {
    if (!this.connection) {
      throw new Error('Not connected to database. Call connect() first.');
    }
    
    await this.connection.optimize();
    this.logger.info('Database optimized');
  }

  /**
   * データを同期
   */
  async sync(): Promise<void> {
    if (!this.connection) {
      throw new Error('Not connected to database. Call connect() first.');
    }
    
    if (!this.config.enableSync) {
      throw new Error('Sync is not enabled. Set enableSync to true in config.');
    }
    
    await this.connection.sync();
    this.logger.info('Data synchronized');
  }

  /**
   * 全データをクリア
   */
  async clearAll(): Promise<void> {
    if (!this.connection) {
      throw new Error('Not connected to database. Call connect() first.');
    }
    
    await this.connection.clearAll();
    this.repositories.clear();
    this.logger.info('All data cleared');
  }
} 