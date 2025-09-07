/**
 * KawaDB ORM 基本使用例
 */

import { KawaORM, Entity, Column, Table, DataType } from '../src';

// ユーザーエンティティを定義
@Table('users')
class User extends Entity {
  @Column({ type: DataType.STRING, nullable: false })
  name: string;

  @Column({ type: DataType.STRING, nullable: false, unique: true })
  email: string;

  @Column({ type: DataType.NUMBER, nullable: false })
  age: number;

  @Column({ type: DataType.BOOLEAN, defaultValue: true })
  active: boolean = true;

  constructor(name: string, email: string, age: number) {
    super();
    this.name = name;
    this.email = email;
    this.age = age;
  }
}

// 商品エンティティを定義
@Table('products')
class Product extends Entity {
  @Column({ type: DataType.STRING, nullable: false })
  name: string;

  @Column({ type: DataType.NUMBER, nullable: false })
  price: number;

  @Column({ type: DataType.STRING })
  description?: string;

  @Column({ type: DataType.JSON })
  metadata?: any;

  constructor(name: string, price: number, description?: string) {
    super();
    this.name = name;
    this.price = price;
    this.description = description;
  }
}

async function basicExample() {
  console.log('🚀 KawaDB ORM 基本使用例');

  // ORM を初期化
  const orm = new KawaORM({
    storage: 'localStorage',
    enableKSQL: true,
    debugMode: true
  });

  try {
    // データベースに接続
    console.log('📊 データベースに接続中...');
    await orm.connect();
    console.log('✅ 接続完了');

    // リポジトリを取得
    const userRepo = orm.getRepository(User);
    const productRepo = orm.getRepository(Product);

    // === ユーザー操作 ===
    console.log('\n👤 ユーザー操作');

    // ユーザーを作成
    const user1 = new User('Alice', 'alice@example.com', 25);
    const user2 = new User('Bob', 'bob@example.com', 30);
    const user3 = new User('Charlie', 'charlie@example.com', 35);

    await userRepo.save(user1);
    await userRepo.save(user2);
    await userRepo.save(user3);
    console.log('✅ ユーザーを作成しました');

    // 全ユーザーを取得
    const allUsers = await userRepo.find();
    console.log(`📋 全ユーザー数: ${allUsers.length}`);
    allUsers.forEach(user => {
      console.log(`  - ${user.name} (${user.email}) - ${user.age}歳`);
    });

    // 条件検索
    const adults = await userRepo.find({
      where: { age: 30 },
      order: { name: 'ASC' }
    });
    console.log(`🎯 30歳のユーザー: ${adults.length}人`);

    // ID検索
    if (user1.id) {
      const foundUser = await userRepo.findById(user1.id);
      console.log(`🔍 ID検索結果: ${foundUser?.name}`);
    }

    // === 商品操作 ===
    console.log('\n🛍️ 商品操作');

    // 商品を作成
    const product1 = new Product('ラップトップ', 999.99, '高性能ラップトップ');
    product1.metadata = { brand: 'TechCorp', warranty: '2年' };

    const product2 = new Product('マウス', 29.99, 'ワイヤレスマウス');
    const product3 = new Product('キーボード', 89.99);

    await productRepo.save(product1);
    await productRepo.save(product2);
    await productRepo.save(product3);
    console.log('✅ 商品を作成しました');

    // 商品一覧
    const allProducts = await productRepo.find({
      order: { price: 'DESC' }
    });
    console.log(`📦 商品一覧:`);
    allProducts.forEach(product => {
      console.log(`  - ${product.name}: $${product.price}`);
    });

    // === KSQL ストリーミング ===
    console.log('\n📈 KSQL ストリーミング');

    const ksql = orm.getKsqlEngine();

    // ユーザーイベントストリームを作成
    await ksql.executeKsql(`
      CREATE STREAM user_events (
        user_id STRING,
        event_type STRING,
        timestamp TIMESTAMP
      ) WITH (
        KAFKA_TOPIC='user_events',
        VALUE_FORMAT='JSON'
      )
    `);
    console.log('✅ ユーザーイベントストリームを作成');

    // イベントデータを追加
    await ksql.addStreamingData('user_events', {
      user_id: user1.id,
      event_type: 'login',
      timestamp: new Date().toISOString()
    });

    await ksql.addStreamingData('user_events', {
      user_id: user2.id,
      event_type: 'purchase',
      timestamp: new Date().toISOString()
    });

    console.log('✅ ストリーミングデータを追加');

    // 継続的クエリを作成
    const queryId = await ksql.createContinuousQuery(
      'user_activity_summary',
      `SELECT user_id, COUNT(*) as event_count
       FROM user_events
       WINDOW TUMBLING (SIZE 1 MINUTE)
       GROUP BY user_id
       EMIT CHANGES`
    );
    console.log(`✅ 継続的クエリを作成: ${queryId}`);

    // 少し待ってからストリーミング結果を取得
    await new Promise(resolve => setTimeout(resolve, 2000));
    
    const streamingResults = await ksql.getStreamingResults(queryId, 5);
    console.log(`📊 ストリーミング結果: ${streamingResults.length}件`);

    // === 統計情報 ===
    console.log('\n📊 統計情報');
    const stats = await orm.getStats();
    console.log('データベース統計:', {
      totalEvents: stats.totalEvents,
      totalTables: stats.totalTables,
      memoryUsage: `${Math.round(stats.memoryUsage / 1024)}KB`,
      uptime: `${Math.round(stats.uptime / 1000)}秒`
    });

    // === 環境情報 ===
    console.log('\n🌐 環境情報');
    const envInfo = orm.getEnvironmentInfo();
    console.log('実行環境:', {
      isWeb: envInfo.isWeb,
      isElectron: envInfo.isElectron,
      isNode: envInfo.isNode,
      wasmLoaded: envInfo.wasmLoaded,
      connected: envInfo.connected
    });

  } catch (error) {
    console.error('❌ エラーが発生しました:', error);
  } finally {
    // 切断
    await orm.disconnect();
    console.log('\n🔌 データベースから切断しました');
  }
}

// メイン関数を実行
if (require.main === module) {
  basicExample().catch(console.error);
}

export { basicExample, User, Product }; 