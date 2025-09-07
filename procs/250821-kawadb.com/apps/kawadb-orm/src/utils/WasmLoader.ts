/**
 * WASM ローダー
 */

import { Logger } from './Logger';
import { Environment } from './Environment';

export interface WasmModule {
  KawaBrowserDB: any;
  BrowserDBConfig: any;
  StorageType: any;
  WasmKsqlEngine: any;
  WasmKsqlResult: any;
  WasmContinuousQuery: any;
  WasmStreamingResult: any;
  init: () => Promise<void>;
  version: () => string;
  memory_usage: () => number;
}

export class WasmLoader {
  private wasmModule: WasmModule | null = null;
  private loaded: boolean = false;
  private loadPromise: Promise<void> | null = null;
  private logger: Logger;

  constructor(logger: Logger) {
    this.logger = logger;
  }

  /**
   * WASMモジュールをロード
   */
  async load(wasmPath?: string): Promise<void> {
    if (this.loaded) {
      return;
    }

    if (this.loadPromise) {
      return this.loadPromise;
    }

    this.loadPromise = this.doLoad(wasmPath);
    return this.loadPromise;
  }

  private async doLoad(wasmPath?: string): Promise<void> {
    this.logger.info('Loading WASM module...');
    
    if (!Environment.isWebAssemblySupported()) {
      throw new Error('WebAssembly is not supported in this environment');
    }

    try {
      let wasmModule: any;

      if (Environment.isWeb() || Environment.isElectronRenderer()) {
        // Web/Electronレンダラープロセス
        wasmModule = await this.loadWebWasm(wasmPath);
      } else if (Environment.isNode() || Environment.isElectronMain()) {
        // Node.js/Electronメインプロセス
        wasmModule = await this.loadNodeWasm(wasmPath);
      } else {
        throw new Error('Unsupported environment for WASM loading');
      }

      // 初期化
      await wasmModule.init();

      this.wasmModule = wasmModule;
      this.loaded = true;
      
      this.logger.info('WASM module loaded successfully', {
        version: wasmModule.version(),
        memoryUsage: wasmModule.memory_usage()
      });

    } catch (error) {
      this.logger.error('Failed to load WASM module:', error);
      throw error;
    }
  }

  /**
   * Web環境でWASMをロード
   */
  private async loadWebWasm(wasmPath?: string): Promise<any> {
    const path = wasmPath || '/kawa-wasm/kawa_wasm.js';
    
    try {
      // 動的インポート
      const wasmModule = await import(path);
      return wasmModule;
    } catch (error) {
      // フォールバック: スクリプトタグで読み込み
      return this.loadWebWasmViaScript(path);
    }
  }

  /**
   * スクリプトタグ経由でWASMをロード
   */
  private async loadWebWasmViaScript(path: string): Promise<any> {
    return new Promise((resolve, reject) => {
      const script = document.createElement('script');
      script.src = path;
      script.type = 'module';
      
      script.onload = () => {
        // グローバルオブジェクトから取得
        const wasmModule = (window as any).kawa_wasm;
        if (wasmModule) {
          resolve(wasmModule);
        } else {
          reject(new Error('WASM module not found in global scope'));
        }
      };

      script.onerror = (error) => {
        reject(new Error(`Failed to load WASM script: ${error}`));
      };

      document.head.appendChild(script);
    });
  }

  /**
   * Node.js環境でWASMをロード
   */
  private async loadNodeWasm(wasmPath?: string): Promise<any> {
    const path = wasmPath || '../kawa-wasm/pkg/kawa_wasm.js';
    
    try {
      // Node.js require - dynamic to avoid bundler issues
      const wasmModule = eval('require')(path);
      return wasmModule;
    } catch (error) {
      // フォールバック: 動的インポート
      const wasmModule = await import(path);
      return wasmModule;
    }
  }

  /**
   * WASMモジュールを取得
   */
  getModule(): WasmModule {
    if (!this.loaded || !this.wasmModule) {
      throw new Error('WASM module not loaded. Call load() first.');
    }
    return this.wasmModule;
  }

  /**
   * ロード済みかどうか
   */
  isLoaded(): boolean {
    return this.loaded;
  }

  /**
   * WASMモジュールをアンロード
   */
  unload(): void {
    this.wasmModule = null;
    this.loaded = false;
    this.loadPromise = null;
    this.logger.info('WASM module unloaded');
  }

  /**
   * メモリ使用量を取得
   */
  getMemoryUsage(): number {
    if (!this.loaded || !this.wasmModule) {
      return 0;
    }
    return this.wasmModule.memory_usage();
  }

  /**
   * バージョン情報を取得
   */
  getVersion(): string {
    if (!this.loaded || !this.wasmModule) {
      return 'unknown';
    }
    return this.wasmModule.version();
  }
} 