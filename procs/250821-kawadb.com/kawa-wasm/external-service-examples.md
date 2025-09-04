# 外部サービスからのKawaDB-WASM使用例

## 📋 概要

このドキュメントでは、外部サービスやWebアプリケーションからKawaDB-WASMライブラリを使用する具体的な方法を説明します。

## 🚀 使用方法

### 1. 直接HTMLからの使用

```html
<!DOCTYPE html>
<html>
<head>
    <title>KawaDB External Service</title>
    <script type="module">
        import init, { KawaBrowserDB, BrowserDBConfig, StorageType } from './pkg/kawa_wasm.js';
        
        let db = null;
        
        async function initKawaDB() {
            try {
                // WASMモジュールの初期化
                await init();
                
                // 設定の作成
                const config = new BrowserDBConfig();
                config.set_storage_type(StorageType.LocalStorage);
                config.set_max_events(10000);
                config.set_debug_mode(true);
                
                // データベースの作成
                db = new KawaBrowserDB(config);
                
                console.log('KawaDB initialized successfully');
                
                // テストイベントの追加
                await addTestEvent();
                
            } catch (error) {
                console.error('Failed to initialize KawaDB:', error);
            }
        }
        
        async function addTestEvent() {
            if (!db) return;
            
            const eventData = {
                user_id: 'test_user',
                action: 'page_view',
                page: '/home',
                timestamp: Date.now()
            };
            
            const eventId = await db.add_event('page_view', JSON.stringify(eventData));
            console.log('Event added:', eventId);
            
            // イベントの取得
            const events = await db.get_events(10);
            console.log('Recent events:', JSON.parse(events));
        }
        
        // ページロード時に初期化
        window.addEventListener('load', initKawaDB);
    </script>
</head>
<body>
    <h1>KawaDB Test Page</h1>
    <button onclick="addTestEvent()">Add Event</button>
</body>
</html>
```

### 2. React/Next.jsアプリケーションでの使用

```typescript
// hooks/useKawaDB.ts
import { useState, useEffect } from 'react';

interface KawaDBInstance {
  add_event: (eventType: string, data: string) => Promise<string>;
  get_events: (limit: number) => Promise<string>;
  get_current_state: () => Promise<string>;
  get_stats: () => Promise<string>;
  clear_data: () => Promise<boolean>;
}

export function useKawaDB() {
  const [db, setDb] = useState<KawaDBInstance | null>(null);
  const [isInitialized, setIsInitialized] = useState(false);
  const [error, setError] = useState<Error | null>(null);

  useEffect(() => {
    let mounted = true;

    const initDB = async () => {
      try {
        // 動的インポート
        const wasmModule = await import('/pkg/kawa_wasm.js');
        
        // WASMモジュールの初期化
        await wasmModule.default();
        
        // 設定の作成
        const config = new wasmModule.BrowserDBConfig();
        config.set_storage_type(wasmModule.StorageType.LocalStorage);
        config.set_max_events(10000);
        config.set_debug_mode(process.env.NODE_ENV === 'development');
        
        // データベースの作成
        const kawaDB = new wasmModule.KawaBrowserDB(config);
        
        if (mounted) {
          setDb(kawaDB);
          setIsInitialized(true);
          setError(null);
        }
      } catch (err) {
        if (mounted) {
          setError(err instanceof Error ? err : new Error('Unknown error'));
        }
      }
    };

    initDB();

    return () => {
      mounted = false;
    };
  }, []);

  const addEvent = async (eventType: string, data: any) => {
    if (!db) throw new Error('KawaDB not initialized');
    
    const eventData = typeof data === 'string' ? data : JSON.stringify(data);
    return await db.add_event(eventType, eventData);
  };

  const getEvents = async (limit: number = 100) => {
    if (!db) throw new Error('KawaDB not initialized');
    
    const eventsJson = await db.get_events(limit);
    return JSON.parse(eventsJson);
  };

  const getStats = async () => {
    if (!db) throw new Error('KawaDB not initialized');
    
    const statsJson = await db.get_stats();
    return JSON.parse(statsJson);
  };

  const clearData = async () => {
    if (!db) throw new Error('KawaDB not initialized');
    
    return await db.clear_data();
  };

  return {
    db,
    isInitialized,
    error,
    addEvent,
    getEvents,
    getStats,
    clearData
  };
}
```

```typescript
// components/EventLogger.tsx
import { useKawaDB } from '../hooks/useKawaDB';

export default function EventLogger() {
  const { isInitialized, addEvent, getEvents, getStats, clearData, error } = useKawaDB();
  const [events, setEvents] = useState([]);
  const [stats, setStats] = useState(null);

  const handleAddEvent = async () => {
    try {
      await addEvent('button_click', {
        button: 'add_event',
        timestamp: Date.now(),
        user_agent: navigator.userAgent
      });
      
      // イベントリストを更新
      const recentEvents = await getEvents(10);
      setEvents(recentEvents);
      
      // 統計情報を更新
      const currentStats = await getStats();
      setStats(currentStats);
      
    } catch (error) {
      console.error('Failed to add event:', error);
    }
  };

  const handleClearData = async () => {
    try {
      await clearData();
      setEvents([]);
      setStats(null);
    } catch (error) {
      console.error('Failed to clear data:', error);
    }
  };

  if (error) {
    return <div>Error: {error.message}</div>;
  }

  if (!isInitialized) {
    return <div>Loading KawaDB...</div>;
  }

  return (
    <div>
      <h2>KawaDB Event Logger</h2>
      
      <div>
        <button onClick={handleAddEvent}>Add Event</button>
        <button onClick={handleClearData}>Clear Data</button>
      </div>
      
      <div>
        <h3>Recent Events</h3>
        <pre>{JSON.stringify(events, null, 2)}</pre>
      </div>
      
      <div>
        <h3>Statistics</h3>
        <pre>{JSON.stringify(stats, null, 2)}</pre>
      </div>
    </div>
  );
}
```

### 3. Vue.jsアプリケーションでの使用

```typescript
// composables/useKawaDB.ts
import { ref, onMounted } from 'vue';

export function useKawaDB() {
  const db = ref(null);
  const isInitialized = ref(false);
  const error = ref(null);

  onMounted(async () => {
    try {
      const wasmModule = await import('/pkg/kawa_wasm.js');
      
      await wasmModule.default();
      
      const config = new wasmModule.BrowserDBConfig();
      config.set_storage_type(wasmModule.StorageType.LocalStorage);
      config.set_max_events(10000);
      
      db.value = new wasmModule.KawaBrowserDB(config);
      isInitialized.value = true;
      
    } catch (err) {
      error.value = err;
    }
  });

  const addEvent = async (eventType: string, data: any) => {
    if (!db.value) throw new Error('KawaDB not initialized');
    
    const eventData = typeof data === 'string' ? data : JSON.stringify(data);
    return await db.value.add_event(eventType, eventData);
  };

  const getEvents = async (limit: number = 100) => {
    if (!db.value) throw new Error('KawaDB not initialized');
    
    const eventsJson = await db.value.get_events(limit);
    return JSON.parse(eventsJson);
  };

  return {
    db,
    isInitialized,
    error,
    addEvent,
    getEvents
  };
}
```

### 4. Angular アプリケーションでの使用

```typescript
// services/kawadb.service.ts
import { Injectable } from '@angular/core';

@Injectable({
  providedIn: 'root'
})
export class KawaDBService {
  private db: any = null;
  private isInitialized = false;

  async initialize(): Promise<void> {
    if (this.isInitialized) return;

    try {
      const wasmModule = await import('/pkg/kawa_wasm.js');
      
      await wasmModule.default();
      
      const config = new wasmModule.BrowserDBConfig();
      config.set_storage_type(wasmModule.StorageType.LocalStorage);
      config.set_max_events(10000);
      
      this.db = new wasmModule.KawaBrowserDB(config);
      this.isInitialized = true;
      
    } catch (error) {
      console.error('Failed to initialize KawaDB:', error);
      throw error;
    }
  }

  async addEvent(eventType: string, data: any): Promise<string> {
    if (!this.db) throw new Error('KawaDB not initialized');
    
    const eventData = typeof data === 'string' ? data : JSON.stringify(data);
    return await this.db.add_event(eventType, eventData);
  }

  async getEvents(limit: number = 100): Promise<any[]> {
    if (!this.db) throw new Error('KawaDB not initialized');
    
    const eventsJson = await this.db.get_events(limit);
    return JSON.parse(eventsJson);
  }

  async getStats(): Promise<any> {
    if (!this.db) throw new Error('KawaDB not initialized');
    
    const statsJson = await this.db.get_stats();
    return JSON.parse(statsJson);
  }

  async clearData(): Promise<boolean> {
    if (!this.db) throw new Error('KawaDB not initialized');
    
    return await this.db.clear_data();
  }
}
```

### 5. KSQL機能の使用

```typescript
// KSQL機能を使用した例
async function initKSQLExample() {
  const wasmModule = await import('/pkg/kawa_wasm.js');
  await wasmModule.default();
  
  const ksqlEngine = new wasmModule.WasmKsqlEngine();
  
  // ストリーミングデータの追加
  ksqlEngine.add_streaming_data('user_events', JSON.stringify({
    user_id: 'user123',
    event_type: 'click',
    page: '/home',
    timestamp: Date.now()
  }));
  
  // 継続的クエリの作成
  const result = ksqlEngine.execute_ksql(`
    CREATE STREAM user_clicks AS
    SELECT user_id, event_type, page, timestamp
    FROM user_events
    WHERE event_type = 'click'
    EMIT CHANGES;
  `);
  
  console.log('KSQL Result:', result);
  
  // 継続的クエリの一覧表示
  const queries = ksqlEngine.list_continuous_queries();
  console.log('Continuous Queries:', JSON.parse(queries));
}
```

## 🏗️ 設定方法

### 1. ファイルの配置

```bash
# WASMファイルをプロジェクトに配置
cp -r kawa-wasm/pkg/* your-project/public/pkg/

# または、NPMパッケージとして配布
npm install kawa-wasm
```

### 2. Webpack設定

```javascript
// webpack.config.js
module.exports = {
  experiments: {
    asyncWebAssembly: true,
  },
  module: {
    rules: [
      {
        test: /\.wasm$/,
        type: 'webassembly/async',
      },
    ],
  },
};
```

### 3. Vite設定

```typescript
// vite.config.ts
import { defineConfig } from 'vite';

export default defineConfig({
  optimizeDeps: {
    exclude: ['kawa-wasm']
  },
  build: {
    target: 'es2020'
  }
});
```

## 📊 実用的な使用例

### 1. Analyticsサービス

```typescript
class AnalyticsService {
  private kawaDB: any;
  
  async init() {
    const wasmModule = await import('/pkg/kawa_wasm.js');
    await wasmModule.default();
    
    const config = new wasmModule.BrowserDBConfig();
    config.set_storage_type(wasmModule.StorageType.IndexedDB);
    config.set_max_events(50000);
    
    this.kawaDB = new wasmModule.KawaBrowserDB(config);
  }
  
  async trackPageView(page: string, userId?: string) {
    await this.kawaDB.add_event('page_view', JSON.stringify({
      page,
      user_id: userId,
      timestamp: Date.now(),
      user_agent: navigator.userAgent,
      referrer: document.referrer
    }));
  }
  
  async trackEvent(eventName: string, properties: any) {
    await this.kawaDB.add_event(eventName, JSON.stringify({
      ...properties,
      timestamp: Date.now()
    }));
  }
  
  async getAnalytics() {
    const events = await this.kawaDB.get_events(1000);
    const stats = await this.kawaDB.get_stats();
    
    return {
      events: JSON.parse(events),
      stats: JSON.parse(stats)
    };
  }
}
```

### 2. ログ収集サービス

```typescript
class LoggingService {
  private kawaDB: any;
  
  async init() {
    const wasmModule = await import('/pkg/kawa_wasm.js');
    await wasmModule.default();
    
    const config = new wasmModule.BrowserDBConfig();
    config.set_storage_type(wasmModule.StorageType.LocalStorage);
    config.set_max_events(10000);
    
    this.kawaDB = new wasmModule.KawaBrowserDB(config);
  }
  
  async logError(error: Error, context?: any) {
    await this.kawaDB.add_event('error', JSON.stringify({
      message: error.message,
      stack: error.stack,
      context,
      timestamp: Date.now(),
      url: window.location.href
    }));
  }
  
  async logInfo(message: string, data?: any) {
    await this.kawaDB.add_event('info', JSON.stringify({
      message,
      data,
      timestamp: Date.now()
    }));
  }
  
  async getLogs(level?: string) {
    const events = await this.kawaDB.get_events(1000);
    const allEvents = JSON.parse(events);
    
    return level ? 
      allEvents.filter(event => event.event_type === level) : 
      allEvents;
  }
}
```

## 🔧 トラブルシューティング

### 1. WASMファイルが見つからない

```javascript
// 解決方法: 正しいパスを確認
console.log('Current origin:', window.location.origin);
const wasmModule = await import(`${window.location.origin}/pkg/kawa_wasm.js`);
```

### 2. CORS エラー

```nginx
# Nginx設定
server {
    location /pkg/ {
        add_header Access-Control-Allow-Origin *;
        add_header Cross-Origin-Embedder-Policy require-corp;
        add_header Cross-Origin-Opener-Policy same-origin;
    }
}
```

### 3. メモリ不足エラー

```typescript
// メモリ使用量を監視
const stats = await db.get_stats();
if (stats.memory_usage > 50000000) { // 50MB
  await db.clear_data();
}
```

## 📚 その他の情報

- [KawaDB メインドキュメント](../README.md)
- [WebAssembly ガイド](https://webassembly.org/getting-started/developers-guide/)
- [MDN WebAssembly](https://developer.mozilla.org/en-US/docs/WebAssembly) 