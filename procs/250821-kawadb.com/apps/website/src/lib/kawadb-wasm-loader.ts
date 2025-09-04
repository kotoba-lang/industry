/**
 * KawaDB WASM ローダー
 * 外部サービスから使用可能なWASMライブラリローダー
 */

// WASMモジュールの型定義
export interface KawaDBWasmModule {
  init(): Promise<void>;
  KawaBrowserDB: any;
  BrowserDBConfig: any;
  WasmKsqlEngine: any;
  WasmStorage: any;
  StorageType: {
    LocalStorage: number;
    IndexedDB: number;
    Memory: number;
  };
}

// グローバルな変数として保存
let wasmModule: KawaDBWasmModule | null = null;

/**
 * WASMモジュールを動的にロード
 */
export async function loadKawaDBWasm(): Promise<KawaDBWasmModule> {
  if (wasmModule) {
    return wasmModule;
  }

  try {
    // 動的インポートでWASMモジュールをロード
    const module = await import('/pkg/kawa_wasm.js');
    
    // WASMモジュールの初期化
    await module.default();
    
    wasmModule = {
      init: module.default,
      KawaBrowserDB: module.KawaBrowserDB,
      BrowserDBConfig: module.BrowserDBConfig,
      WasmKsqlEngine: module.WasmKsqlEngine,
      WasmStorage: module.WasmStorage,
      StorageType: module.StorageType,
    };

    return wasmModule;
  } catch (error) {
    console.error('Failed to load KawaDB WASM:', error);
    throw error;
  }
}

/**
 * KawaDB設定インターフェース
 */
export interface KawaDBConfig {
  storageType?: 'LocalStorage' | 'IndexedDB' | 'Memory';
  maxEvents?: number;
  debugMode?: boolean;
  syncEnabled?: boolean;
  syncEndpoint?: string;
}

/**
 * KawaDBクライアントクラス
 */
export class KawaDBClient {
  private db: any = null;
  private ksqlEngine: any = null;
  private storage: any = null;
  private wasm: KawaDBWasmModule | null = null;
  private config: KawaDBConfig;

  constructor(config: KawaDBConfig = {}) {
    this.config = {
      storageType: 'LocalStorage',
      maxEvents: 10000,
      debugMode: false,
      syncEnabled: false,
      ...config
    };
  }

  /**
   * 初期化
   */
  async initialize(): Promise<void> {
    // WASMモジュールをロード
    this.wasm = await loadKawaDBWasm();

    // データベース設定の作成
    const dbConfig = new this.wasm.BrowserDBConfig();
    
    // ストレージタイプの設定
    switch (this.config.storageType) {
      case 'LocalStorage':
        dbConfig.set_storage_type(this.wasm.StorageType.LocalStorage);
        break;
      case 'IndexedDB':
        dbConfig.set_storage_type(this.wasm.StorageType.IndexedDB);
        break;
      case 'Memory':
        dbConfig.set_storage_type(this.wasm.StorageType.Memory);
        break;
    }

    // その他の設定
    if (this.config.maxEvents) {
      dbConfig.set_max_events(this.config.maxEvents);
    }

    if (this.config.debugMode) {
      dbConfig.set_debug_mode(this.config.debugMode);
    }

    if (this.config.syncEnabled) {
      dbConfig.enable_sync(this.config.syncEnabled);
      if (this.config.syncEndpoint) {
        dbConfig.set_sync_endpoint(this.config.syncEndpoint);
      }
    }

    // データベースの作成
    this.db = new this.wasm.KawaBrowserDB(dbConfig);

    // KSQLエンジンの作成
    this.ksqlEngine = new this.wasm.WasmKsqlEngine();

    // ストレージの作成
    const storageType = this.config.storageType === 'LocalStorage' ? this.wasm.StorageType.LocalStorage :
                       this.config.storageType === 'IndexedDB' ? this.wasm.StorageType.IndexedDB :
                       this.wasm.StorageType.Memory;
    this.storage = new this.wasm.WasmStorage(storageType);
  }

  /**
   * イベントを追加
   */
  async addEvent(eventType: string, data: any): Promise<string> {
    if (!this.db) {
      throw new Error('KawaDB is not initialized');
    }
    
    const eventData = typeof data === 'string' ? data : JSON.stringify(data);
    return await this.db.add_event(eventType, eventData);
  }

  /**
   * イベントを取得
   */
  async getEvents(limit: number = 100): Promise<any[]> {
    if (!this.db) {
      throw new Error('KawaDB is not initialized');
    }
    
    const eventsJson = await this.db.get_events(limit);
    return JSON.parse(eventsJson);
  }

  /**
   * 現在の状態を取得
   */
  async getCurrentState(): Promise<any> {
    if (!this.db) {
      throw new Error('KawaDB is not initialized');
    }
    
    const stateJson = await this.db.get_current_state();
    return JSON.parse(stateJson);
  }

  /**
   * 統計情報を取得
   */
  async getStats(): Promise<any> {
    if (!this.db) {
      throw new Error('KawaDB is not initialized');
    }
    
    const statsJson = await this.db.get_stats();
    return JSON.parse(statsJson);
  }

  /**
   * データをクリア
   */
  async clearData(): Promise<void> {
    if (!this.db) {
      throw new Error('KawaDB is not initialized');
    }
    
    await this.db.clear_data();
  }

  /**
   * KSQLクエリを実行
   */
  executeKsql(ksql: string): any {
    if (!this.ksqlEngine) {
      throw new Error('KSQL Engine is not initialized');
    }
    
    return this.ksqlEngine.execute_ksql(ksql);
  }

  /**
   * ストリーミングデータを追加
   */
  addStreamingData(sourceName: string, data: any): boolean {
    if (!this.ksqlEngine) {
      throw new Error('KSQL Engine is not initialized');
    }
    
    const dataJson = typeof data === 'string' ? data : JSON.stringify(data);
    return this.ksqlEngine.add_streaming_data(sourceName, dataJson);
  }

  /**
   * リソースをクリーンアップ
   */
  destroy(): void {
    if (this.db) {
      this.db.free();
      this.db = null;
    }
    
    if (this.ksqlEngine) {
      this.ksqlEngine.free();
      this.ksqlEngine = null;
    }
    
    if (this.storage) {
      this.storage.free();
      this.storage = null;
    }
  }
}

/**
 * KawaDBクライアントファクトリー
 */
export async function createKawaDBClient(config: KawaDBConfig = {}): Promise<KawaDBClient> {
  const client = new KawaDBClient(config);
  await client.initialize();
  return client;
}

/**
 * 外部サービス用のシンプルなAPI
 */
export class KawaDBSimpleAPI {
  private client: KawaDBClient | null = null;

  /**
   * 初期化
   */
  async init(config: KawaDBConfig = {}): Promise<void> {
    this.client = await createKawaDBClient(config);
  }

  /**
   * イベントをログ
   */
  async logEvent(eventType: string, data: any): Promise<void> {
    if (!this.client) {
      throw new Error('KawaDB is not initialized');
    }
    
    await this.client.addEvent(eventType, data);
  }

  /**
   * イベント履歴を取得
   */
  async getEventHistory(limit: number = 100): Promise<any[]> {
    if (!this.client) {
      throw new Error('KawaDB is not initialized');
    }
    
    return await this.client.getEvents(limit);
  }

  /**
   * 統計情報を取得
   */
  async getStatistics(): Promise<any> {
    if (!this.client) {
      throw new Error('KawaDB is not initialized');
    }
    
    return await this.client.getStats();
  }

  /**
   * データをクリア
   */
  async clearAllData(): Promise<void> {
    if (!this.client) {
      throw new Error('KawaDB is not initialized');
    }
    
    await this.client.clearData();
  }

  /**
   * リソースをクリーンアップ
   */
  destroy(): void {
    if (this.client) {
      this.client.destroy();
      this.client = null;
    }
  }
}

/**
 * CDN用のグローバル関数
 */
declare global {
  interface Window {
    KawaDB: {
      loadKawaDBWasm: typeof loadKawaDBWasm;
      createKawaDBClient: typeof createKawaDBClient;
      KawaDBClient: typeof KawaDBClient;
      KawaDBSimpleAPI: typeof KawaDBSimpleAPI;
    };
  }
}

// ブラウザ環境でのグローバル関数の設定
if (typeof window !== 'undefined') {
  window.KawaDB = {
    loadKawaDBWasm,
    createKawaDBClient,
    KawaDBClient,
    KawaDBSimpleAPI,
  };
} 