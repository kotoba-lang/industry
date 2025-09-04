# KawaDB-WASM デプロイメント・外部利用ガイド

## 概要

KawaDB-WASMは、ブラウザ環境で動作するイベントソーシングデータベースです。
このガイドでは、外部サービスから利用するための方法を説明します。

## 📦 利用方法

### 1. NPMパッケージとしての配布

#### パッケージの準備
```bash
# WASMライブラリのビルド
cd kawa-wasm
wasm-pack build --target web --out-dir pkg

# NPMパッケージの公開準備
cd pkg
npm publish
```

#### 外部プロジェクトでの利用
```bash
# 外部プロジェクトでのインストール
npm install kawa-wasm
```

```typescript
// 外部サービスでの利用例
import init, { KawaBrowserDB, BrowserDBConfig, StorageType } from 'kawa-wasm';

// 初期化
await init();

// 設定の作成
const config = new BrowserDBConfig();
config.set_storage_type(StorageType.LocalStorage);
config.set_max_events(10000);
config.enable_sync(true);

// データベースの作成
const db = new KawaBrowserDB(config);

// イベントの追加
const eventId = await db.add_event('user_signup', JSON.stringify({
  user_id: '123',
  email: 'user@example.com',
  timestamp: Date.now()
}));

// イベントの取得
const events = await db.get_events(10);
console.log(JSON.parse(events));
```

### 2. CDN経由での配布

#### Static Hosting設定
```html
<!DOCTYPE html>
<html>
<head>
    <title>KawaDB External Service</title>
</head>
<body>
    <script type="module">
        import init, { KawaBrowserDB, BrowserDBConfig, StorageType } from 'https://cdn.jsdelivr.net/npm/kawa-wasm@latest/kawa_wasm.js';
        
        async function initKawaDB() {
            await init();
            
            const config = new BrowserDBConfig();
            config.set_storage_type(StorageType.IndexedDB);
            
            const db = new KawaBrowserDB(config);
            
            // 使用例
            await db.add_event('page_view', JSON.stringify({
                page: window.location.pathname,
                timestamp: Date.now()
            }));
        }
        
        initKawaDB();
    </script>
</body>
</html>
```

### 3. 独自サーバーでのホスティング

#### Nginx設定例
```nginx
server {
    listen 80;
    server_name your-domain.com;
    
    location /kawadb-wasm/ {
        root /var/www/html;
        
        # WASM用ヘッダー
        add_header Cross-Origin-Embedder-Policy require-corp;
        add_header Cross-Origin-Opener-Policy same-origin;
        add_header Access-Control-Allow-Origin *;
        
        # WASM MIMEタイプ
        location ~* \.wasm$ {
            add_header Content-Type application/wasm;
        }
    }
}
```

### 4. React/Vue.jsアプリケーションでの利用

#### React例
```typescript
// hooks/useKawaDB.ts
import { useEffect, useState } from 'react';
import init, { KawaBrowserDB, BrowserDBConfig, StorageType } from 'kawa-wasm';

export const useKawaDB = () => {
  const [db, setDb] = useState<KawaBrowserDB | null>(null);
  const [isInitialized, setIsInitialized] = useState(false);

  useEffect(() => {
    const initDB = async () => {
      try {
        await init();
        
        const config = new BrowserDBConfig();
        config.set_storage_type(StorageType.IndexedDB);
        config.enable_sync(true);
        config.set_debug_mode(process.env.NODE_ENV === 'development');
        
        const kawaDB = new KawaBrowserDB(config);
        setDb(kawaDB);
        setIsInitialized(true);
      } catch (error) {
        console.error('Failed to initialize KawaDB:', error);
      }
    };

    initDB();
  }, []);

  return { db, isInitialized };
};

// コンポーネントでの使用
const EventLogger = () => {
  const { db, isInitialized } = useKawaDB();
  
  const logEvent = async (eventType: string, data: object) => {
    if (db && isInitialized) {
      await db.add_event(eventType, JSON.stringify(data));
    }
  };

  return (
    <div>
      <button onClick={() => logEvent('button_click', { button: 'log' })}>
        Log Event
      </button>
    </div>
  );
};
```

#### Vue.js例
```typescript
// composables/useKawaDB.ts
import { ref, onMounted } from 'vue';
import init, { KawaBrowserDB, BrowserDBConfig, StorageType } from 'kawa-wasm';

export const useKawaDB = () => {
  const db = ref<KawaBrowserDB | null>(null);
  const isInitialized = ref(false);

  onMounted(async () => {
    try {
      await init();
      
      const config = new BrowserDBConfig();
      config.set_storage_type(StorageType.LocalStorage);
      
      db.value = new KawaBrowserDB(config);
      isInitialized.value = true;
    } catch (error) {
      console.error('Failed to initialize KawaDB:', error);
    }
  });

  return { db, isInitialized };
};
```

## 🔧 高度な利用方法

### 1. KSQL統合
```typescript
import { WasmKsqlEngine } from 'kawa-wasm';

const ksqlEngine = new WasmKsqlEngine();

// ストリーミングデータの追加
ksqlEngine.add_streaming_data('user_events', JSON.stringify({
  user_id: '123',
  event_type: 'click',
  timestamp: Date.now()
}));

// 継続的クエリの実行
const result = ksqlEngine.execute_ksql(`
  CREATE STREAM user_clicks AS
  SELECT user_id, event_type, timestamp
  FROM user_events
  WHERE event_type = 'click'
  EMIT CHANGES;
`);

console.log(result);
```

### 2. 同期機能の設定
```typescript
// 同期エンドポイントの設定
config.set_sync_endpoint('https://your-sync-service.com/api/sync');

// 手動同期
await db.sync_to_cloud();
await db.sync_from_cloud();
```

### 3. 統計情報の取得
```typescript
const stats = await db.get_stats();
const statsData = JSON.parse(stats);
console.log('Database Statistics:', statsData);
```

## 🛠️ 開発・テスト環境

### Webpack設定
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

### Vite設定
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

## 🚀 本番環境での考慮事項

### 1. セキュリティ
- CSP (Content Security Policy) の適切な設定
- CORS設定の確認
- 機密データの適切な暗号化

### 2. パフォーマンス
- WASMファイルの適切なキャッシュ設定
- 大量データ処理時のメモリ管理
- 適切なイベント数の上限設定

### 3. 監視・ログ
```typescript
// エラーハンドリング
try {
  await db.add_event('user_action', eventData);
} catch (error) {
  console.error('KawaDB Error:', error);
  // 外部監視システムへの通知
}
```

## 📊 利用例

### 1. Analytics サービス
```typescript
// ページビューの記録
await db.add_event('page_view', JSON.stringify({
  page: window.location.pathname,
  user_agent: navigator.userAgent,
  timestamp: Date.now()
}));

// ユーザー行動の分析
const result = ksqlEngine.execute_ksql(`
  SELECT page, COUNT(*) as views
  FROM page_views
  GROUP BY page
  EMIT CHANGES;
`);
```

### 2. リアルタイム通知システム
```typescript
// リアルタイムイベントの処理
ksqlEngine.execute_ksql(`
  CREATE STREAM notifications AS
  SELECT user_id, message, timestamp
  FROM user_events
  WHERE event_type = 'notification'
  EMIT CHANGES;
`);
```

### 3. A/Bテストプラットフォーム
```typescript
// テスト結果の記録
await db.add_event('ab_test_result', JSON.stringify({
  test_id: 'button_color_test',
  variant: 'blue',
  user_id: '123',
  conversion: true,
  timestamp: Date.now()
}));
```

## 📚 参考情報

- [KawaDB メインドキュメント](../README.md)
- [WebAssembly ガイド](https://webassembly.org/getting-started/developers-guide/)
- [wasm-pack ドキュメント](https://rustwasm.github.io/wasm-pack/) 