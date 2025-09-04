/**
 * オーディオキャッシュユーティリティ
 * 
 * HumeのTTS APIから生成された音声ファイルをIndexedDBに保存し、
 * 同じテキストに対する音声を再度リクエストする際に再利用します。
 */

// IndexedDBのデータベース名とストア名
const DB_NAME = 'jung-voice-cache';
const STORE_NAME = 'audio-files';
const DB_VERSION = 1;

interface AudioCacheItem {
  text: string;
  voice: string;
  audioData: Blob;
  timestamp: number;
}

/**
 * IndexedDBを開く
 */
async function openDatabase(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open(DB_NAME, DB_VERSION);
    
    request.onerror = (event) => {
      console.error('IndexedDB error:', event);
      reject('Failed to open database');
    };
    
    request.onsuccess = (event) => {
      const db = (event.target as IDBOpenDBRequest).result;
      resolve(db);
    };
    
    request.onupgradeneeded = (event) => {
      const db = (event.target as IDBOpenDBRequest).result;
      
      // 音声ファイルを保存するオブジェクトストアを作成
      if (!db.objectStoreNames.contains(STORE_NAME)) {
        const store = db.createObjectStore(STORE_NAME, { keyPath: ['text', 'voice'] });
        store.createIndex('timestamp', 'timestamp', { unique: false });
      }
    };
  });
}

/**
 * キャッシュからオーディオを取得
 */
export async function getAudioFromCache(text: string, voice: string): Promise<Blob | null> {
  try {
    const db = await openDatabase();
    
    return new Promise((resolve, reject) => {
      const transaction = db.transaction(STORE_NAME, 'readonly');
      const store = transaction.objectStore(STORE_NAME);
      const request = store.get([text, voice]);
      
      request.onsuccess = () => {
        const result = request.result as AudioCacheItem | undefined;
        if (result) {
          console.log(`Retrieved audio from cache for: "${text}"`);
          resolve(result.audioData);
        } else {
          resolve(null);
        }
      };
      
      request.onerror = (event) => {
        console.error('Error retrieving audio from cache:', event);
        resolve(null);
      };
      
      transaction.oncomplete = () => {
        db.close();
      };
    });
  } catch (err) {
    console.error('Failed to access audio cache:', err);
    return null;
  }
}

/**
 * オーディオをキャッシュに保存
 */
export async function saveAudioToCache(text: string, voice: string, audioData: Blob): Promise<boolean> {
  try {
    const db = await openDatabase();
    
    return new Promise((resolve, reject) => {
      const transaction = db.transaction(STORE_NAME, 'readwrite');
      const store = transaction.objectStore(STORE_NAME);
      
      const item: AudioCacheItem = {
        text,
        voice,
        audioData,
        timestamp: Date.now()
      };
      
      const request = store.put(item);
      
      request.onsuccess = () => {
        console.log(`Saved audio to cache for: "${text}"`);
        resolve(true);
      };
      
      request.onerror = (event) => {
        console.error('Error saving audio to cache:', event);
        resolve(false);
      };
      
      transaction.oncomplete = () => {
        db.close();
      };
    });
  } catch (err) {
    console.error('Failed to save audio to cache:', err);
    return false;
  }
}

/**
 * キャッシュを消去（古いエントリのみ、または全て）
 */
export async function clearAudioCache(olderThanDays?: number): Promise<boolean> {
  try {
    const db = await openDatabase();
    
    return new Promise((resolve, reject) => {
      const transaction = db.transaction(STORE_NAME, 'readwrite');
      const store = transaction.objectStore(STORE_NAME);
      
      // 特定の日数より古いエントリのみを削除
      if (olderThanDays) {
        const cutoffTime = Date.now() - (olderThanDays * 24 * 60 * 60 * 1000);
        const index = store.index('timestamp');
        const range = IDBKeyRange.upperBound(cutoffTime);
        
        const cursorRequest = index.openCursor(range);
        
        cursorRequest.onsuccess = (event) => {
          const cursor = (event.target as IDBRequest).result as IDBCursorWithValue;
          if (cursor) {
            cursor.delete();
            cursor.continue();
          }
        };
      } else {
        // 全てのエントリを削除
        store.clear();
      }
      
      transaction.oncomplete = () => {
        db.close();
        resolve(true);
      };
      
      transaction.onerror = (event) => {
        console.error('Error clearing audio cache:', event);
        resolve(false);
      };
    });
  } catch (err) {
    console.error('Failed to clear audio cache:', err);
    return false;
  }
}

/**
 * キャッシュのサイズを取得
 */
export async function getAudioCacheSize(): Promise<{ count: number, sizeBytes: number }> {
  try {
    const db = await openDatabase();
    
    return new Promise((resolve, reject) => {
      const transaction = db.transaction(STORE_NAME, 'readonly');
      const store = transaction.objectStore(STORE_NAME);
      const countRequest = store.count();
      let count = 0;
      let sizeBytes = 0;
      
      countRequest.onsuccess = () => {
        count = countRequest.result;
        
        if (count === 0) {
          resolve({ count: 0, sizeBytes: 0 });
          return;
        }
        
        const cursorRequest = store.openCursor();
        
        cursorRequest.onsuccess = (event) => {
          const cursor = (event.target as IDBRequest).result as IDBCursorWithValue;
          if (cursor) {
            const item = cursor.value as AudioCacheItem;
            sizeBytes += item.audioData.size;
            cursor.continue();
          } else {
            resolve({ count, sizeBytes });
          }
        };
      };
      
      transaction.oncomplete = () => {
        db.close();
      };
      
      transaction.onerror = (event) => {
        console.error('Error getting audio cache size:', event);
        resolve({ count: 0, sizeBytes: 0 });
      };
    });
  } catch (err) {
    console.error('Failed to get audio cache size:', err);
    return { count: 0, sizeBytes: 0 };
  }
} 