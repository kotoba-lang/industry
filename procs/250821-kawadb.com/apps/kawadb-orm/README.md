# KawaDB ORM

**TypeScript ORM for KawaDB with KSQL support - works in Web and Electron environments**

KawaDB ORM は、KawaDB の KSQL 操作を抽象化し、TypeScript でタイプセーフなデータベース操作を提供するORMライブラリです。WebブラウザとElectronの両方の環境で動作します。

## ✨ 特徴

- 🔗 **完全TypeScript対応** - タイプセーフなAPI
- 🌐 **クロスプラットフォーム** - Web、Electron、Node.js対応
- 📊 **KSQL統合** - ストリーミングSQL処理
- 🎯 **デコレータベース** - シンプルなエンティティ定義
- ⚡ **高性能** - WebAssembly バックエンド
- 🔄 **イベントソーシング** - 自動的なイベント追跡
- 💾 **複数ストレージ** - localStorage、IndexedDB、メモリ対応

## 📦 インストール

```bash
npm install kawadb-orm
```

## 🚀 クイックスタート

### 基本的な使用例

```typescript
import { KawaORM, Entity, Column, Table } from 'kawadb-orm';

// エンティティを定義
@Table('users')
class User extends Entity {
  @Column({ type: 'string' })
  name: string;

  @Column({ type: 'string' })
  email: string;

  @Column({ type: 'number' })
  age: number;

  constructor(name: string, email: string, age: number) {
    super();
    this.name = name;
    this.email = email;
    this.age = age;
  }
}

// ORM を初期化
const orm = new KawaORM({
  storage: 'localStorage',
  enableKSQL: true,
  debugMode: true
});

async function main() {
  // データベースに接続
  await orm.connect();

  // リポジトリを取得
  const userRepo = orm.getRepository(User);

  // ユーザーを作成
  const user = new User('Alice', 'alice@example.com', 25);
  await userRepo.save(user);

  // ユーザーを検索
  const users = await userRepo.find({
    where: { age: 25 },
    order: { name: 'ASC' }
  });

  console.log('Found users:', users);
}

main().catch(console.error);
```

### KSQL ストリーミング処理

```typescript
import { KawaORM, KsqlDataType, KsqlDataFormat } from 'kawadb-orm';

const orm = new KawaORM({ enableKSQL: true });
await orm.connect();

const ksql = orm.getKsqlEngine();

// ストリームを作成
await ksql.createStream({
  name: 'user_events',
  columns: [
    { name: 'user_id', type: KsqlDataType.STRING, nullable: false },
    { name: 'event_type', type: KsqlDataType.STRING, nullable: false },
    { name: 'timestamp', type: KsqlDataType.TIMESTAMP, nullable: false }
  ],
  topic: 'events',
  format: KsqlDataFormat.JSON
});

// 継続的クエリを作成
const queryId = await ksql.createContinuousQuery(
  'user_activity_summary',
  `SELECT user_id, COUNT(*) as event_count
   FROM user_events 
   WINDOW TUMBLING (SIZE 1 MINUTE)
   GROUP BY user_id
   EMIT CHANGES`
);

// ストリーミングデータを追加
await ksql.addStreamingData('events', {
  user_id: 'user123',
  event_type: 'login',
  timestamp: new Date().toISOString()
});

// 結果を取得
const results = await ksql.getStreamingResults(queryId, 10);
console.log('Streaming results:', results);
```

## 🏗️ API リファレンス

### KawaORM クラス

```typescript
class KawaORM {
  constructor(config?: DatabaseConfig)
  
  // 接続管理
  async connect(options?: ConnectionOptions): Promise<void>
  async disconnect(): Promise<void>
  isConnected(): boolean
  
  // リポジトリ
  getRepository<T>(entityConstructor: EntityConstructor<T>): Repository<T>
  
  // KSQL
  getKsqlEngine(): KsqlEngine
  
  // ユーティリティ
  async query(sql: string, params?: any[]): Promise<any>
  async transaction<T>(callback: (orm: KawaORM) => Promise<T>): Promise<T>
  getEnvironmentInfo(): any
  async getStats(): Promise<any>
}
```

### Repository クラス

```typescript
class Repository<T extends Entity> {
  // CRUD操作
  async save(entity: T): Promise<T>
  async create(entity: T): Promise<T>
  async update(entity: T): Promise<T>
  
  // 検索
  async findById(id: EntityId): Promise<T | null>
  async find(options?: FindOptions<T>): Promise<T[]>
  async findOne(options?: FindOptions<T>): Promise<T | null>
  async count(options?: FindOptions<T>): Promise<number>
  async exists(id: EntityId): Promise<boolean>
  
  // 削除
  async delete(id: EntityId): Promise<boolean>
  async deleteMany(options: DeleteOptions<T>): Promise<number>
  async clear(): Promise<void>
}
```

### KsqlEngine クラス

```typescript
class KsqlEngine {
  // KSQL実行
  async executeKsql(ksql: string): Promise<KsqlResult>
  
  // ストリーム/テーブル管理
  async createStream(stream: KsqlStream): Promise<KsqlResult>
  async createTable(table: KsqlTable): Promise<KsqlResult>
  async dropStream(streamName: string): Promise<KsqlResult>
  async dropTable(tableName: string): Promise<KsqlResult>
  
  // 継続的クエリ
  async createContinuousQuery(name: string, ksql: string): Promise<string>
  async stopContinuousQuery(queryId: string): Promise<boolean>
  async listContinuousQueries(): Promise<ContinuousQuery[]>
  
  // ストリーミングデータ
  async addStreamingData(topic: string, data: any): Promise<void>
  async getStreamingResults(queryId: string, limit?: number): Promise<StreamingResult[]>
}
```

## 🔧 設定オプション

```typescript
interface DatabaseConfig {
  storage?: 'localStorage' | 'indexedDB' | 'memory';
  maxEvents?: number;
  enableSync?: boolean;
  enableKSQL?: boolean;
  debugMode?: boolean;
  syncEndpoint?: string;
  wasmPath?: string;
}
```

## 🌐 環境別設定

### Web ブラウザ

```typescript
// WASMファイルのパスを指定
const orm = new KawaORM({
  storage: 'indexedDB',
  wasmPath: '/path/to/kawa-wasm/kawa_wasm.js'
});
```

### Electron

```typescript
// Electronではデフォルトパスが使用される
const orm = new KawaORM({
  storage: 'localStorage',
  debugMode: process.env.NODE_ENV === 'development'
});
```

### Node.js

```typescript
// Node.js環境では相対パスを使用
const orm = new KawaORM({
  storage: 'memory',
  wasmPath: '../kawa-wasm/pkg/kawa_wasm.js'
});
```

## 📊 デコレータ使用例

```typescript
import { Entity, Table, Column, DataType } from 'kawadb-orm';

@Table('products')
class Product extends Entity {
  @Column({ 
    type: DataType.STRING, 
    nullable: false,
    unique: true 
  })
  name: string;

  @Column({ 
    type: DataType.NUMBER,
    defaultValue: 0 
  })
  price: number;

  @Column({ 
    type: DataType.JSON,
    nullable: true 
  })
  metadata?: any;

  @Column({ 
    type: DataType.BOOLEAN,
    defaultValue: true 
  })
  active: boolean = true;
}
```

## 🔄 リアルタイム分析例

```typescript
// Eコマースのリアルタイム分析
const ksql = orm.getKsqlEngine();

// 購入イベントストリーム
await ksql.createStream({
  name: 'purchases',
  columns: [
    { name: 'user_id', type: KsqlDataType.STRING, nullable: false },
    { name: 'product_id', type: KsqlDataType.STRING, nullable: false },
    { name: 'amount', type: KsqlDataType.DOUBLE, nullable: false },
    { name: 'timestamp', type: KsqlDataType.TIMESTAMP, nullable: false }
  ],
  topic: 'purchase_events',
  format: KsqlDataFormat.JSON
});

// 売上集計クエリ
await ksql.createContinuousQuery(
  'sales_summary',
  `SELECT 
     user_id,
     COUNT(*) as purchase_count,
     SUM(amount) as total_amount,
     AVG(amount) as avg_amount
   FROM purchases
   WINDOW TUMBLING (SIZE 1 HOUR)
   GROUP BY user_id
   HAVING COUNT(*) > 5
   EMIT CHANGES`
);

// 商品別売上ランキング
await ksql.createContinuousQuery(
  'product_ranking',
  `SELECT 
     product_id,
     COUNT(*) as purchase_count,
     SUM(amount) as revenue
   FROM purchases
   WINDOW TUMBLING (SIZE 1 DAY)
   GROUP BY product_id
   ORDER BY revenue DESC
   LIMIT 10
   EMIT CHANGES`
);
```

## 🛠️ 開発

```bash
# 依存関係をインストール
npm install

# 開発モード
npm run dev

# ビルド
npm run build

# テスト
npm test

# リント
npm run lint

# フォーマット
npm run format
```

## 📖 ドキュメント

- [API リファレンス](./docs/api.md)
- [KSQL ガイド](./docs/ksql.md)
- [デプロイメント](./docs/deployment.md)
- [サンプル集](./examples/)

## 🤝 コントリビューション

1. フォークしてください
2. フィーチャーブランチを作成してください (`git checkout -b feature/amazing-feature`)
3. 変更をコミットしてください (`git commit -m 'Add amazing feature'`)
4. ブランチにプッシュしてください (`git push origin feature/amazing-feature`)
5. プルリクエストを作成してください

## 📄 ライセンス

MIT License - 詳細は [LICENSE](LICENSE) ファイルを参照してください。

## 🔗 関連リンク

- [KawaDB](https://github.com/kawasaki/kawadb.com)
- [KawaDB WASM](../kawa-wasm/)
- [WebAssembly](https://webassembly.org/)
- [TypeScript](https://www.typescriptlang.org/)

---

**KawaDB ORM** - TypeScript でタイプセーフなストリーミングデータベース操作を提供 🚀 