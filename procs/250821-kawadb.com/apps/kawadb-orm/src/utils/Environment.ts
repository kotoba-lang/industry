/**
 * 環境検出ユーティリティ
 */

export interface EnvironmentFeatures {
  localStorage: boolean;
  indexedDB: boolean;
  webWorker: boolean;
  serviceWorker: boolean;
  webAssembly: boolean;
  nodeFs: boolean;
  electronRenderer: boolean;
  electronMain: boolean;
}

export class Environment {
  private static _features: EnvironmentFeatures | null = null;

  /**
   * Webブラウザ環境かどうか
   */
  static isWeb(): boolean {
    return typeof window !== 'undefined' && typeof document !== 'undefined';
  }

  /**
   * Node.js環境かどうか
   */
  static isNode(): boolean {
    return typeof process !== 'undefined' && process.versions && process.versions.node !== undefined;
  }

  /**
   * Electron環境かどうか
   */
  static isElectron(): boolean {
    return typeof window !== 'undefined' && 
           typeof window.process !== 'undefined' &&
           (window.process as any).type === 'renderer';
  }

  /**
   * Electronのメインプロセスかどうか
   */
  static isElectronMain(): boolean {
    return this.isNode() && 
           typeof process !== 'undefined' && 
           (process as any).type === 'browser';
  }

  /**
   * Electronのレンダラープロセスかどうか
   */
  static isElectronRenderer(): boolean {
    return this.isElectron();
  }

  /**
   * サポートされている機能を取得
   */
  static getSupportedFeatures(): EnvironmentFeatures {
    if (!this._features) {
      this._features = this.detectFeatures();
    }
    return this._features;
  }

  /**
   * 機能を検出
   */
  private static detectFeatures(): EnvironmentFeatures {
    const features: EnvironmentFeatures = {
      localStorage: false,
      indexedDB: false,
      webWorker: false,
      serviceWorker: false,
      webAssembly: false,
      nodeFs: false,
      electronRenderer: false,
      electronMain: false
    };

    // Web環境での機能検出
    if (this.isWeb()) {
      try {
        features.localStorage = typeof localStorage !== 'undefined' && localStorage !== null;
      } catch (e) {
        features.localStorage = false;
      }

      try {
        features.indexedDB = typeof indexedDB !== 'undefined' && indexedDB !== null;
      } catch (e) {
        features.indexedDB = false;
      }

      features.webWorker = typeof Worker !== 'undefined';
      features.serviceWorker = 'serviceWorker' in navigator;
      features.webAssembly = typeof WebAssembly !== 'undefined';
    }

    // Node.js環境での機能検出
    if (this.isNode()) {
      try {
        // Dynamic require to avoid bundler issues
        const fs = eval('require')('fs');
        features.nodeFs = !!fs;
      } catch (e) {
        features.nodeFs = false;
      }

      features.webAssembly = typeof WebAssembly !== 'undefined';
    }

    // Electron環境での機能検出
    features.electronRenderer = this.isElectronRenderer();
    features.electronMain = this.isElectronMain();

    return features;
  }

  /**
   * 最適なストレージタイプを取得
   */
  static getOptimalStorageType(): 'localStorage' | 'indexedDB' | 'memory' {
    const features = this.getSupportedFeatures();
    
    if (features.indexedDB) {
      return 'indexedDB';
    } else if (features.localStorage) {
      return 'localStorage';
    } else {
      return 'memory';
    }
  }

  /**
   * WebAssemblyが利用可能かどうか
   */
  static isWebAssemblySupported(): boolean {
    return this.getSupportedFeatures().webAssembly;
  }

  /**
   * 永続化ストレージが利用可能かどうか
   */
  static isPersistentStorageSupported(): boolean {
    const features = this.getSupportedFeatures();
    return features.localStorage || features.indexedDB || features.nodeFs;
  }

  /**
   * 非同期ストレージが利用可能かどうか
   */
  static isAsyncStorageSupported(): boolean {
    const features = this.getSupportedFeatures();
    return features.indexedDB || features.nodeFs;
  }

  /**
   * 環境情報を取得
   */
  static getEnvironmentInfo(): any {
    return {
      isWeb: this.isWeb(),
      isNode: this.isNode(),
      isElectron: this.isElectron(),
      isElectronMain: this.isElectronMain(),
      isElectronRenderer: this.isElectronRenderer(),
      features: this.getSupportedFeatures(),
      userAgent: this.isWeb() ? navigator.userAgent : (typeof process !== 'undefined' ? (process as any).version : 'unknown'),
      platform: this.isNode() ? (process as any).platform : 'web',
      timestamp: new Date().toISOString()
    };
  }
} 