/**
 * 統合オーディオキャッシュユーティリティ
 * 
 * クライアントサイド（IndexedDB）とサーバーサイドの両方のキャッシュを使用して
 * 音声ファイルを効率的に管理します。
 */

import { 
  getAudioFromCache, 
  saveAudioToCache, 
  clearAudioCache, 
  getAudioCacheSize 
} from './audioCache';

import {
  getAudioFromServerCache,
  saveAudioToServerCache,
  clearServerAudioCache,
  getServerAudioCacheSize
} from './serverAudioCache';

/**
 * キャッシュの種類を示す定数
 */
export enum CacheType {
  CLIENT = 'client',
  SERVER = 'server',
  BOTH = 'both'
}

/**
 * キャッシュ設定のインターフェース
 */
export interface CacheSettings {
  clientEnabled: boolean;
  serverEnabled: boolean;
  preferServer: boolean; // サーバーキャッシュを優先するかどうか
}

// デフォルトキャッシュ設定
const defaultSettings: CacheSettings = {
  clientEnabled: true,
  serverEnabled: true,
  preferServer: true // サーバーキャッシュを優先
};

/**
 * キャッシュの統計情報のインターフェース
 */
export interface CacheStats {
  client: { count: number, sizeBytes: number };
  server: { count: number, sizeBytes: number };
  total: { count: number, sizeBytes: number };
}

/**
 * 統合キャッシュから音声を取得
 * 
 * 設定に基づいて、クライアントかサーバー、または両方から音声を検索します。
 */
export async function getAudioFromCombinedCache(
  text: string, 
  voice: string, 
  settings: CacheSettings = defaultSettings
): Promise<{ blob: Blob | null; source: CacheType | null }> {
  try {
    // どのキャッシュからも取得しない場合は早期リターン
    if (!settings.clientEnabled && !settings.serverEnabled) {
      return { blob: null, source: null };
    }

    // サーバーキャッシュを優先する場合
    if (settings.preferServer && settings.serverEnabled) {
      const serverBlob = await getAudioFromServerCache(text, voice);
      if (serverBlob) {
        return { blob: serverBlob, source: CacheType.SERVER };
      }
      
      // サーバーに見つからない場合はクライアントを確認
      if (settings.clientEnabled) {
        const clientBlob = await getAudioFromCache(text, voice);
        if (clientBlob) {
          // 将来のリクエストのためにサーバーにもキャッシュ
          saveAudioToServerCache(text, voice, clientBlob).catch(err => {
            console.error('Failed to save client cache to server:', err);
          });
          return { blob: clientBlob, source: CacheType.CLIENT };
        }
      }
    } 
    // クライアントキャッシュを優先する場合
    else if (settings.clientEnabled) {
      const clientBlob = await getAudioFromCache(text, voice);
      if (clientBlob) {
        return { blob: clientBlob, source: CacheType.CLIENT };
      }
      
      // クライアントに見つからない場合はサーバーを確認
      if (settings.serverEnabled) {
        const serverBlob = await getAudioFromServerCache(text, voice);
        if (serverBlob) {
          // 将来のリクエストのためにクライアントにもキャッシュ
          saveAudioToCache(text, voice, serverBlob).catch(err => {
            console.error('Failed to save server cache to client:', err);
          });
          return { blob: serverBlob, source: CacheType.SERVER };
        }
      }
    }
    
    // どちらのキャッシュにも見つからない場合
    return { blob: null, source: null };
  } catch (err) {
    console.error('Failed to get audio from combined cache:', err);
    return { blob: null, source: null };
  }
}

/**
 * 音声を統合キャッシュに保存
 * 
 * 設定に基づいて、クライアントやサーバー、または両方に音声を保存します。
 */
export async function saveAudioToCombinedCache(
  text: string, 
  voice: string, 
  audioData: Blob,
  settings: CacheSettings = defaultSettings
): Promise<{ success: boolean; savedTo: CacheType }> {
  const results = { 
    clientSuccess: false, 
    serverSuccess: false 
  };
  
  try {
    // クライアントキャッシュに保存
    if (settings.clientEnabled) {
      results.clientSuccess = await saveAudioToCache(text, voice, audioData);
    }
    
    // サーバーキャッシュに保存
    if (settings.serverEnabled) {
      results.serverSuccess = await saveAudioToServerCache(text, voice, audioData);
    }
    
    // 保存先の決定
    let savedTo: CacheType;
    if (results.clientSuccess && results.serverSuccess) {
      savedTo = CacheType.BOTH;
    } else if (results.clientSuccess) {
      savedTo = CacheType.CLIENT;
    } else if (results.serverSuccess) {
      savedTo = CacheType.SERVER;
    } else {
      // どちらにも保存できなかった場合
      return { success: false, savedTo: CacheType.BOTH };
    }
    
    return { success: true, savedTo };
  } catch (err) {
    console.error('Failed to save audio to combined cache:', err);
    return { 
      success: results.clientSuccess || results.serverSuccess, 
      savedTo: results.clientSuccess && results.serverSuccess 
        ? CacheType.BOTH
        : results.clientSuccess 
          ? CacheType.CLIENT 
          : results.serverSuccess 
            ? CacheType.SERVER 
            : CacheType.BOTH
    };
  }
}

/**
 * 統合キャッシュを消去
 * 
 * 設定に基づいて、クライアントやサーバー、または両方のキャッシュを消去します。
 */
export async function clearCombinedCache(
  cacheType: CacheType = CacheType.BOTH,
  olderThanDays?: number
): Promise<boolean> {
  try {
    let clientSuccess = true;
    let serverSuccess = true;
    
    if (cacheType === CacheType.CLIENT || cacheType === CacheType.BOTH) {
      clientSuccess = await clearAudioCache(olderThanDays);
    }
    
    if (cacheType === CacheType.SERVER || cacheType === CacheType.BOTH) {
      serverSuccess = await clearServerAudioCache(olderThanDays);
    }
    
    return clientSuccess && serverSuccess;
  } catch (err) {
    console.error('Failed to clear combined cache:', err);
    return false;
  }
}

/**
 * 統合キャッシュの統計情報を取得
 */
export async function getCombinedCacheStats(): Promise<CacheStats> {
  try {
    // 並行して両方のキャッシュの統計を取得
    const [clientStats, serverStats] = await Promise.all([
      getAudioCacheSize(),
      getServerAudioCacheSize()
    ]);
    
    return {
      client: clientStats,
      server: serverStats,
      total: {
        count: clientStats.count + serverStats.count,
        sizeBytes: clientStats.sizeBytes + serverStats.sizeBytes
      }
    };
  } catch (err) {
    console.error('Failed to get combined cache stats:', err);
    return {
      client: { count: 0, sizeBytes: 0 },
      server: { count: 0, sizeBytes: 0 },
      total: { count: 0, sizeBytes: 0 }
    };
  }
} 