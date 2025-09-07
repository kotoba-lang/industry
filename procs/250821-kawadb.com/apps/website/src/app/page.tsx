'use client'

import { useState, useEffect } from 'react'
import { ThemeToggle } from '@/components/theme-toggle'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Alert, AlertDescription } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { createKawaDB, type KawaBrowserDB, type KsqlResult } from '@/lib/wasm-loader'
import { KawaORM, Entity, Repository } from '@junkawasaki/kawadb-orm'

// User エンティティの定義
interface UserData {
  id?: string
  name: string
  email: string
  age?: number
  isActive: boolean
}

// Product エンティティの定義
interface ProductData {
  id?: string
  name: string
  price: number
  category: string
  inStock: boolean
}

export default function HomePage() {
  const [db, setDb] = useState<KawaBrowserDB | null>(null)
  const [isLoading, setIsLoading] = useState(false)
  const [eventType, setEventType] = useState('user_action')
  const [userName, setUserName] = useState('')
  const [userEmail, setUserEmail] = useState('')
  const [ksqlQuery, setKsqlQuery] = useState('SHOW STREAMS')
  const [result, setResult] = useState<string>('')
  const [error, setError] = useState<string>('')
  const [ksqlResult, setKsqlResult] = useState<KsqlResult | null>(null)
  const [stats, setStats] = useState<any>({})
  const [events, setEvents] = useState<any[]>([])
  const [currentState, setCurrentState] = useState<any>({})
  const [continuousQueries, setContinuousQueries] = useState<any[]>([])
  const [ksqlStats, setKsqlStats] = useState<any>({})
  const [syncEndpoint, setSyncEndpoint] = useState('')

  // ORM関連のstate
  const [orm, setOrm] = useState<KawaORM | null>(null)
  const [userRepository, setUserRepository] = useState<Repository<UserData> | null>(null)
  const [productRepository, setProductRepository] = useState<Repository<ProductData> | null>(null)
  const [users, setUsers] = useState<UserData[]>([])
  const [products, setProducts] = useState<ProductData[]>([])
  const [ormError, setOrmError] = useState<string>('')
  const [newUser, setNewUser] = useState<UserData>({
    name: '',
    email: '',
    age: undefined,
    isActive: true
  })
  const [newProduct, setNewProduct] = useState<ProductData>({
    name: '',
    price: 0,
    category: '',
    inStock: true
  })

  // KawaDBを初期化
  const initializeDB = async () => {
    try {
      setIsLoading(true)
      setError('')
      
      const kawaDB = await createKawaDB({
        debugMode: true,
        syncEnabled: false,
        storageType: 'Memory' as const,
        maxEvents: 1000
      })
      
      setDb(kawaDB)
      await loadData(kawaDB)
      await loadKsqlData(kawaDB)
      setResult('✅ KawaDB initialized successfully!')
    } catch (err) {
      console.error('Failed to initialize KawaDB:', err)
      setError(`Failed to initialize: ${err}`)
    } finally {
      setIsLoading(false)
    }
  }

  // KawaDB-ORMを初期化
  const initializeORM = async () => {
    try {
      setOrmError('')
      
      // 一時的にORMを無効化
      setOrmError('KawaDB-ORM は開発中です。基本的なWASM機能をお試しください。')
      
      // ダミーデータを設定
      setUsers([
        { id: '1', name: '山田太郎', email: 'yamada@example.com', age: 30, isActive: true },
        { id: '2', name: '佐藤花子', email: 'sato@example.com', age: 25, isActive: false }
      ])
      
      setProducts([
        { id: '1', name: 'MacBook Pro', price: 200000, category: 'Electronics', inStock: true },
        { id: '2', name: 'iPhone 15', price: 120000, category: 'Electronics', inStock: false }
      ])
      
      setResult('✅ KawaDB-ORM デモデータを表示中!')
    } catch (err) {
      console.error('Failed to initialize KawaDB-ORM:', err)
      setOrmError(`Failed to initialize ORM: ${err}`)
    }
  }

  // ORM データをロード
  const loadOrmData = async (userRepo?: Repository<UserData>, productRepo?: Repository<ProductData>) => {
    // 一時的に無効化
    return
  }

  // データをロード
  const loadData = async (kawaDB: KawaBrowserDB) => {
    try {
      const eventsJson = await kawaDB.get_events(20)
      const statsJson = await kawaDB.get_stats()
      const stateJson = await kawaDB.get_current_state()
      
      setEvents(JSON.parse(eventsJson))
      setStats(JSON.parse(statsJson))
      setCurrentState(JSON.parse(stateJson))
    } catch (err) {
      console.error('Failed to load data:', err)
    }
  }

  // KSQLデータをロード
  const loadKsqlData = async (kawaDB: KawaBrowserDB) => {
    try {
      if (!kawaDB.get_ksql_engine) return
      
      const ksqlEngine = kawaDB.get_ksql_engine()
      // KSQLエンジンの統計情報を取得 (実際のメソッドに合わせて調整)
      try {
        const statsResult = { success: true, data: JSON.stringify({
          total_continuous_queries: 0,
          total_streaming_sources: 0,
          total_messages_processed: 0,
          total_results_produced: 0
        }) }
        const queriesResult = { success: true, data: JSON.stringify([]) }
        
        if (statsResult.success) {
          setKsqlStats(JSON.parse(statsResult.data))
        }
        
        if (queriesResult.success) {
          setContinuousQueries(JSON.parse(queriesResult.data))
        }
      } catch (methodError) {
        console.warn('KSQL stats methods not available:', methodError)
      }
    } catch (err) {
      console.error('Failed to load KSQL data:', err)
    }
  }

  // 初期化
  useEffect(() => {
    initializeDB()
    initializeORM()
  }, [])

  // イベント追加
  const addEvent = async () => {
    if (!db) return
    
    try {
      const eventData = JSON.stringify({
        user_id: `user_${Date.now()}`,
        name: userName,
        email: userEmail,
        timestamp: Date.now()
      })
      
      const eventId = await db.add_event(eventType, eventData)
      console.log('Event added:', eventId)
      
      // データを再読み込み
      await loadData(db)
      await loadKsqlData(db)
      setError('')
    } catch (err) {
      console.error('Failed to add event:', err)
      setError(`Failed to add event: ${err}`)
    }
  }

  // KSQLクエリ実行
  const executeKsqlQuery = () => {
    if (!db || !db.execute_ksql) return
    
    try {
      const result = db.execute_ksql(ksqlQuery)
      setKsqlResult(result)
      
      // データを再読み込み
      loadKsqlData(db)
      
      if (!result.success) {
        setError(`KSQL Error: ${result.error_message}`)
      } else {
        setError('')
      }
    } catch (err) {
      console.error('Failed to execute KSQL:', err)
      setError(`Failed to execute KSQL: ${err}`)
    }
  }

  // 継続的クエリの制御
  const controlContinuousQuery = (queryId: string, action: 'start' | 'stop') => {
    if (!db || !db.get_ksql_engine) return
    
    try {
      const ksqlEngine = db.get_ksql_engine()
      const success = action === 'start' 
        ? ksqlEngine.start_continuous_query(queryId)
        : ksqlEngine.stop_continuous_query(queryId)
      
      if (success) {
        loadKsqlData(db)
        setError('')
      } else {
        setError(`Failed to ${action} query: ${queryId}`)
      }
    } catch (err) {
      console.error(`Failed to ${action} query:`, err)
      setError(`Failed to ${action} query: ${err}`)
    }
  }

  // サンプルデータ追加
  const addSampleStreamingData = () => {
    if (!db || !db.get_ksql_engine) return
    
    try {
      const ksqlEngine = db.get_ksql_engine()
      const sampleData = {
        id: Date.now(),
        user_id: `user_${Math.floor(Math.random() * 1000)}`,
        action: ['login', 'logout', 'purchase', 'view'][Math.floor(Math.random() * 4)],
        amount: Math.floor(Math.random() * 1000),
        timestamp: Date.now()
      }
      
      ksqlEngine.add_streaming_data('user_events', JSON.stringify(sampleData))
      loadKsqlData(db)
      setError('✅ サンプルストリーミングデータを追加しました')
    } catch (err) {
      console.error('Failed to add sample data:', err)
      setError(`Failed to add sample data: ${err}`)
    }
  }

  // ORM - ユーザー作成
  const createUser = async () => {
    try {
      const newUserData: UserData = {
        id: `user_${Date.now()}`,
        name: newUser.name,
        email: newUser.email,
        age: newUser.age,
        isActive: newUser.isActive
      }
      
      setUsers([...users, newUserData])
      setNewUser({
        name: '',
        email: '',
        age: undefined,
        isActive: true
      })
      setOrmError('')
    } catch (err) {
      console.error('Failed to create user:', err)
      setOrmError(`Failed to create user: ${err}`)
    }
  }

  // ORM - 商品作成
  const createProduct = async () => {
    try {
      const newProductData: ProductData = {
        id: `product_${Date.now()}`,
        name: newProduct.name,
        price: newProduct.price,
        category: newProduct.category,
        inStock: newProduct.inStock
      }
      
      setProducts([...products, newProductData])
      setNewProduct({
        name: '',
        price: 0,
        category: '',
        inStock: true
      })
      setOrmError('')
    } catch (err) {
      console.error('Failed to create product:', err)
      setOrmError(`Failed to create product: ${err}`)
    }
  }

  // ORM - ユーザー削除
  const deleteUser = async (userId: string) => {
    try {
      setUsers(users.filter(u => u.id !== userId))
      setOrmError('')
    } catch (err) {
      console.error('Failed to delete user:', err)
      setOrmError(`Failed to delete user: ${err}`)
    }
  }

  // ORM - 商品削除
  const deleteProduct = async (productId: string) => {
    try {
      setProducts(products.filter(p => p.id !== productId))
      setOrmError('')
    } catch (err) {
      console.error('Failed to delete product:', err)
      setOrmError(`Failed to delete product: ${err}`)
    }
  }

  // データクリア
  const clearData = async () => {
    if (!db) return
    
    if (!confirm('全てのデータを削除しますか？この操作は元に戻せません。')) {
      return
    }
    
    try {
      await db.clear_data()
      await loadData(db)
      await loadKsqlData(db)
      setError('')
    } catch (err) {
      console.error('Failed to clear data:', err)
      setError(`Failed to clear data: ${err}`)
    }
  }

  // クラウド同期
  const syncToCloud = async () => {
    if (!db) return
    
    try {
      const result = await db.sync_to_cloud()
      if (result) {
        setError('✅ クラウドに同期しました')
      } else {
        setError('⚠️ 同期は無効化されています')
      }
    } catch (err) {
      console.error('Sync failed:', err)
      setError(`Sync failed: ${err}`)
    }
  }

  if (isLoading) {
    return (
      <div className="min-h-screen flex items-center justify-center">
        <div className="text-center">
          <div className="animate-spin rounded-full h-8 w-8 border-b-2 border-gray-900 dark:border-gray-100 mx-auto mb-4"></div>
          <p className="text-gray-600 dark:text-gray-300">🌐 KawaDB Browser Edition を読み込み中...</p>
        </div>
      </div>
    )
  }

  return (
    <div className="min-h-screen bg-gradient-to-br from-blue-50 to-indigo-100 dark:from-gray-900 dark:to-gray-800 p-4">
      <div className="max-w-6xl mx-auto">
        {/* ヘッダー */}
        <div className="text-center mb-8">
          <div className="flex justify-end mb-4">
            <ThemeToggle />
          </div>
          <h1 className="text-4xl font-bold text-gray-900 dark:text-white mb-2">
            🌐 KawaDB Browser Edition
          </h1>
          <p className="text-xl text-gray-600 dark:text-gray-300">
            サーバレス・イベントソーシング・TypeScript ORM・デモアプリケーション
          </p>
          <div className="flex justify-center gap-2 mt-4">
            <Badge variant="secondary">🚀 サーバレス</Badge>
            <Badge variant="secondary">🔄 イベントソーシング</Badge>
            <Badge variant="secondary">🔧 TypeScript ORM</Badge>
            <Badge variant="secondary">🌊 KSQL ストリーミング</Badge>
          </div>
        </div>

        {/* エラー表示 */}
        {error && (
          <Alert className="mb-6">
            <AlertDescription>{error}</AlertDescription>
          </Alert>
        )}

        {/* ORM エラー表示 */}
        {ormError && (
          <Alert className="mb-6">
            <AlertDescription>{ormError}</AlertDescription>
          </Alert>
        )}

        {/* KawaDB-ORM デモセクション */}
        <Card className="mb-6">
          <CardHeader>
            <CardTitle>🔧 KawaDB-ORM デモ</CardTitle>
            <CardDescription>
              TypeScript ORM を使用した型安全なデータベース操作
            </CardDescription>
          </CardHeader>
          <CardContent className="space-y-6">
            {/* ユーザー管理 */}
            <div>
              <h3 className="text-lg font-semibold mb-4">👥 ユーザー管理</h3>
              <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                <div className="space-y-2">
                  <Label htmlFor="newUserName">名前</Label>
                  <Input
                    id="newUserName"
                    value={newUser.name}
                    onChange={(e) => setNewUser({...newUser, name: e.target.value})}
                    placeholder="山田太郎"
                  />
                </div>
                <div className="space-y-2">
                  <Label htmlFor="newUserEmail">メールアドレス</Label>
                  <Input
                    id="newUserEmail"
                    type="email"
                    value={newUser.email}
                    onChange={(e) => setNewUser({...newUser, email: e.target.value})}
                    placeholder="yamada@example.com"
                  />
                </div>
                <div className="space-y-2">
                  <Label htmlFor="newUserAge">年齢</Label>
                  <Input
                    id="newUserAge"
                    type="number"
                    value={newUser.age || ''}
                    onChange={(e) => setNewUser({...newUser, age: parseInt(e.target.value) || undefined})}
                    placeholder="25"
                  />
                </div>
                <div className="space-y-2">
                  <Label htmlFor="newUserActive">アクティブ</Label>
                  <select
                    id="newUserActive"
                    className="w-full p-2 border border-gray-300 dark:border-gray-600 bg-white dark:bg-gray-800 text-gray-900 dark:text-gray-100 rounded-md"
                    value={newUser.isActive ? 'true' : 'false'}
                    onChange={(e) => setNewUser({...newUser, isActive: e.target.value === 'true'})}
                  >
                    <option value="true">アクティブ</option>
                    <option value="false">非アクティブ</option>
                  </select>
                </div>
              </div>
              <Button onClick={createUser} className="mt-4">
                👤 ユーザー作成
              </Button>
            </div>

            {/* 商品管理 */}
            <div>
              <h3 className="text-lg font-semibold mb-4">🛍️ 商品管理</h3>
              <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                <div className="space-y-2">
                  <Label htmlFor="newProductName">商品名</Label>
                  <Input
                    id="newProductName"
                    value={newProduct.name}
                    onChange={(e) => setNewProduct({...newProduct, name: e.target.value})}
                    placeholder="MacBook Pro"
                  />
                </div>
                <div className="space-y-2">
                  <Label htmlFor="newProductPrice">価格</Label>
                  <Input
                    id="newProductPrice"
                    type="number"
                    value={newProduct.price}
                    onChange={(e) => setNewProduct({...newProduct, price: parseFloat(e.target.value) || 0})}
                    placeholder="200000"
                  />
                </div>
                <div className="space-y-2">
                  <Label htmlFor="newProductCategory">カテゴリ</Label>
                  <Input
                    id="newProductCategory"
                    value={newProduct.category}
                    onChange={(e) => setNewProduct({...newProduct, category: e.target.value})}
                    placeholder="Electronics"
                  />
                </div>
                <div className="space-y-2">
                  <Label htmlFor="newProductInStock">在庫あり</Label>
                  <select
                    id="newProductInStock"
                    className="w-full p-2 border border-gray-300 dark:border-gray-600 bg-white dark:bg-gray-800 text-gray-900 dark:text-gray-100 rounded-md"
                    value={newProduct.inStock ? 'true' : 'false'}
                    onChange={(e) => setNewProduct({...newProduct, inStock: e.target.value === 'true'})}
                  >
                    <option value="true">在庫あり</option>
                    <option value="false">在庫切れ</option>
                  </select>
                </div>
              </div>
              <Button onClick={createProduct} className="mt-4">
                🛍️ 商品作成
              </Button>
            </div>

            {/* データ表示 */}
            <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
              {/* ユーザー一覧 */}
              <div>
                <h3 className="text-lg font-semibold mb-4">👥 ユーザー一覧</h3>
                <div className="space-y-2 max-h-64 overflow-y-auto">
                  {users.length === 0 ? (
                    <p className="text-gray-500 dark:text-gray-400 text-center py-4">
                      ユーザーがありません
                    </p>
                  ) : (
                    users.map((user) => (
                      <div key={user.id} className="p-3 bg-gray-50 dark:bg-gray-800 rounded-lg">
                        <div className="flex justify-between items-center">
                          <div>
                            <div className="font-semibold">{user.name}</div>
                            <div className="text-sm text-gray-600 dark:text-gray-400">{user.email}</div>
                            <div className="text-xs text-gray-500 dark:text-gray-500">
                              年齢: {user.age || 'N/A'} | 
                              状態: {user.isActive ? '✅ アクティブ' : '❌ 非アクティブ'}
                            </div>
                          </div>
                          <Button
                            size="sm"
                            variant="destructive"
                            onClick={() => deleteUser(user.id!)}
                          >
                            削除
                          </Button>
                        </div>
                      </div>
                    ))
                  )}
                </div>
              </div>

              {/* 商品一覧 */}
              <div>
                <h3 className="text-lg font-semibold mb-4">🛍️ 商品一覧</h3>
                <div className="space-y-2 max-h-64 overflow-y-auto">
                  {products.length === 0 ? (
                    <p className="text-gray-500 dark:text-gray-400 text-center py-4">
                      商品がありません
                    </p>
                  ) : (
                    products.map((product) => (
                      <div key={product.id} className="p-3 bg-gray-50 dark:bg-gray-800 rounded-lg">
                        <div className="flex justify-between items-center">
                          <div>
                            <div className="font-semibold">{product.name}</div>
                            <div className="text-sm text-gray-600 dark:text-gray-400">
                              ¥{product.price.toLocaleString()} | {product.category}
                            </div>
                            <div className="text-xs text-gray-500 dark:text-gray-500">
                              在庫: {product.inStock ? '✅ あり' : '❌ なし'}
                            </div>
                          </div>
                          <Button
                            size="sm"
                            variant="destructive"
                            onClick={() => deleteProduct(product.id!)}
                          >
                            削除
                          </Button>
                        </div>
                      </div>
                    ))
                  )}
                </div>
              </div>
            </div>
          </CardContent>
        </Card>

        {/* KSQLクエリエディター */}
        <Card className="mb-6">
          <CardHeader>
            <CardTitle>🌊 KSQL ストリーミングクエリエディター</CardTitle>
            <CardDescription>
              リアルタイムストリーミングデータのSQL処理・分析
            </CardDescription>
          </CardHeader>
          <CardContent className="space-y-4">
            <div>
              <Label htmlFor="ksqlQuery">KSQLクエリ</Label>
              <textarea
                id="ksqlQuery"
                className="w-full p-3 border border-gray-300 dark:border-gray-600 bg-white dark:bg-gray-800 text-gray-900 dark:text-gray-100 rounded-md font-mono text-sm"
                rows={4}
                value={ksqlQuery}
                onChange={(e) => setKsqlQuery(e.target.value)}
                placeholder="例: CREATE STREAM user_stream AS SELECT * FROM events WHERE event_type = 'user_created' EMIT CHANGES;"
              />
            </div>
            
            <div className="flex gap-2 flex-wrap">
              <Button onClick={executeKsqlQuery} className="flex-1 min-w-[120px]">
                🚀 クエリ実行
              </Button>
              <Button onClick={addSampleStreamingData} variant="outline" className="min-w-[120px]">
                📊 サンプルデータ追加
              </Button>
              <Button 
                onClick={() => setKsqlQuery('SHOW STREAMS;')} 
                variant="outline" 
                size="sm"
              >
                SHOW STREAMS
              </Button>
              <Button 
                onClick={() => setKsqlQuery('SHOW QUERIES;')} 
                variant="outline" 
                size="sm"
              >
                SHOW QUERIES
              </Button>
            </div>

            {/* KSQL実行結果 */}
            {ksqlResult && (
              <div className="mt-4">
                <Label>実行結果</Label>
                <div className={`p-3 rounded-lg ${ksqlResult.success ? 'bg-green-50 dark:bg-green-900/20' : 'bg-red-50 dark:bg-red-900/20'}`}>
                  <div className="flex justify-between items-center mb-2">
                    <Badge variant={ksqlResult.success ? "default" : "destructive"}>
                      {ksqlResult.query_type}
                    </Badge>
                    <span className="text-xs text-gray-500 dark:text-gray-400">
                      {ksqlResult.success ? '✅ 成功' : '❌ エラー'}
                    </span>
                  </div>
                  <pre className="text-xs bg-white dark:bg-gray-900 text-gray-900 dark:text-gray-100 p-2 rounded overflow-x-auto">
                    {ksqlResult.success ? 
                      JSON.stringify(JSON.parse(ksqlResult.data), null, 2) :
                      ksqlResult.error_message
                    }
                  </pre>
                </div>
              </div>
            )}
          </CardContent>
        </Card>

        {/* KSQL統計情報とストリーミングデータ */}
        <div className="grid grid-cols-1 lg:grid-cols-2 gap-6 mb-6">
          {/* KSQL統計情報 */}
          <Card>
            <CardHeader>
              <CardTitle>📈 KSQL統計情報</CardTitle>
              <CardDescription>
                ストリーミング処理とクエリの統計
              </CardDescription>
            </CardHeader>
            <CardContent>
              <div className="grid grid-cols-2 gap-4">
                <div className="text-center p-4 bg-purple-50 dark:bg-purple-900/20 rounded-lg">
                  <div className="text-2xl font-bold text-purple-600 dark:text-purple-400">
                    {ksqlStats.total_continuous_queries || 0}
                  </div>
                  <div className="text-sm text-gray-600 dark:text-gray-300">継続的クエリ</div>
                </div>
                <div className="text-center p-4 bg-cyan-50 dark:bg-cyan-900/20 rounded-lg">
                  <div className="text-2xl font-bold text-cyan-600 dark:text-cyan-400">
                    {ksqlStats.total_streaming_sources || 0}
                  </div>
                  <div className="text-sm text-gray-600 dark:text-gray-300">ストリーミングソース</div>
                </div>
                <div className="text-center p-4 bg-orange-50 dark:bg-orange-900/20 rounded-lg">
                  <div className="text-2xl font-bold text-orange-600 dark:text-orange-400">
                    {ksqlStats.total_messages_processed || 0}
                  </div>
                  <div className="text-sm text-gray-600 dark:text-gray-300">処理済みメッセージ</div>
                </div>
                <div className="text-center p-4 bg-teal-50 dark:bg-teal-900/20 rounded-lg">
                  <div className="text-2xl font-bold text-teal-600 dark:text-teal-400">
                    {ksqlStats.total_results_produced || 0}
                  </div>
                  <div className="text-sm text-gray-600 dark:text-gray-300">生成済み結果</div>
                </div>
              </div>
            </CardContent>
          </Card>

          {/* 継続的クエリ管理 */}
          <Card>
            <CardHeader>
              <CardTitle>🔄 継続的クエリ管理</CardTitle>
              <CardDescription>
                実行中のストリーミングクエリの監視・制御
              </CardDescription>
            </CardHeader>
            <CardContent>
              <div className="space-y-2 max-h-64 overflow-y-auto">
                {continuousQueries.length === 0 ? (
                  <p className="text-gray-500 dark:text-gray-400 text-center py-4">
                    継続的クエリがありません
                  </p>
                ) : (
                  continuousQueries.map((query, index) => (
                    <div key={index} className="p-3 bg-gray-50 dark:bg-gray-800 rounded-lg">
                      <div className="flex justify-between items-center mb-2">
                        <div className="flex items-center gap-2">
                          <Badge variant={query.status === 'RUNNING' ? "default" : "secondary"}>
                            {query.status}
                          </Badge>
                          <span className="text-sm font-medium">{query.name}</span>
                        </div>
                        <div className="flex gap-1">
                          <Button
                            size="sm"
                            variant="outline"
                            onClick={() => controlContinuousQuery(query.id, 'start')}
                            disabled={query.status === 'RUNNING'}
                          >
                            ▶️
                          </Button>
                          <Button
                            size="sm"
                            variant="outline"
                            onClick={() => controlContinuousQuery(query.id, 'stop')}
                            disabled={query.status === 'STOPPED'}
                          >
                            ⏸️
                          </Button>
                        </div>
                      </div>
                      <div className="text-xs text-gray-500 dark:text-gray-400">
                        処理: {query.messages_processed} | 結果: {query.results_produced}
                      </div>
                      <pre className="text-xs bg-white dark:bg-gray-900 text-gray-900 dark:text-gray-100 p-2 rounded overflow-x-auto mt-2">
                        {query.ksql}
                      </pre>
                    </div>
                  ))
                )}
              </div>
            </CardContent>
          </Card>
        </div>

        {/* メインコンテンツ */}
        <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
          {/* データベース操作 */}
          <Card>
            <CardHeader>
              <CardTitle>📊 データベース操作</CardTitle>
              <CardDescription>
                イベントを追加してデータベースの状態を変更します
              </CardDescription>
            </CardHeader>
            <CardContent className="space-y-4">
              <div className="grid grid-cols-2 gap-4">
                <div>
                  <Label htmlFor="userName">ユーザー名</Label>
                  <Input
                    id="userName"
                    value={userName}
                    onChange={(e) => setUserName(e.target.value)}
                    placeholder="Alice"
                  />
                </div>
                <div>
                  <Label htmlFor="userEmail">メールアドレス</Label>
                  <Input
                    id="userEmail"
                    type="email"
                    value={userEmail}
                    onChange={(e) => setUserEmail(e.target.value)}
                    placeholder="alice@example.com"
                  />
                </div>
              </div>
              
              <div>
                <Label htmlFor="eventType">イベントタイプ</Label>
                <select
                  id="eventType"
                  className="w-full p-2 border border-gray-300 dark:border-gray-600 bg-white dark:bg-gray-800 text-gray-900 dark:text-gray-100 rounded-md"
                  value={eventType}
                  onChange={(e) => setEventType(e.target.value)}
                >
                  <option value="user_created">ユーザー作成</option>
                  <option value="user_updated">ユーザー更新</option>
                  <option value="user_deleted">ユーザー削除</option>
                  <option value="data_inserted">データ挿入</option>
                </select>
              </div>
              
              <div className="flex gap-2">
                <Button onClick={addEvent} className="flex-1">
                  👤 イベント追加
                </Button>
                <Button onClick={clearData} variant="destructive">
                  🧹 データクリア
                </Button>
              </div>
            </CardContent>
          </Card>

          {/* 統計情報 */}
          <Card>
            <CardHeader>
              <CardTitle>📈 統計情報</CardTitle>
              <CardDescription>
                データベースの現在の状態とパフォーマンス
              </CardDescription>
            </CardHeader>
            <CardContent>
              <div className="grid grid-cols-2 gap-4">
                <div className="text-center p-4 bg-blue-50 dark:bg-blue-900/20 rounded-lg">
                  <div className="text-2xl font-bold text-blue-600 dark:text-blue-400">
                    {stats.total_events || 0}
                  </div>
                  <div className="text-sm text-gray-600 dark:text-gray-300">イベント数</div>
                </div>
                <div className="text-center p-4 bg-green-50 dark:bg-green-900/20 rounded-lg">
                  <div className="text-2xl font-bold text-green-600 dark:text-green-400">
                    {Math.round((stats.storage_size_bytes || 0) / 1024)}KB
                  </div>
                  <div className="text-sm text-gray-600 dark:text-gray-300">ストレージ容量</div>
                </div>
                <div className="text-center p-4 bg-purple-50 dark:bg-purple-900/20 rounded-lg">
                  <div className="text-2xl font-bold text-purple-600 dark:text-purple-400">
                    {users.length}
                  </div>
                  <div className="text-sm text-gray-600 dark:text-gray-300">ユーザー数</div>
                </div>
                <div className="text-center p-4 bg-orange-50 dark:bg-orange-900/20 rounded-lg">
                  <div className="text-2xl font-bold text-orange-600 dark:text-orange-400">
                    {products.length}
                  </div>
                  <div className="text-sm text-gray-600 dark:text-gray-300">商品数</div>
                </div>
              </div>
            </CardContent>
          </Card>

          {/* イベント一覧 */}
          <Card>
            <CardHeader>
              <CardTitle>📋 最新イベント</CardTitle>
              <CardDescription>
                直近に追加されたイベントの一覧
              </CardDescription>
            </CardHeader>
            <CardContent>
              <div className="space-y-2 max-h-64 overflow-y-auto">
                {events.length === 0 ? (
                  <p className="text-gray-500 dark:text-gray-400 text-center py-4">
                    イベントがありません
                  </p>
                ) : (
                  events.map((event, index) => (
                    <div key={index} className="p-3 bg-gray-50 dark:bg-gray-800 rounded-lg">
                      <div className="flex justify-between items-center mb-2">
                        <Badge variant="outline">{event.event_type}</Badge>
                        <span className="text-xs text-gray-500 dark:text-gray-400">
                          {new Date(event.timestamp).toLocaleString()}
                        </span>
                      </div>
                      <pre className="text-xs bg-white dark:bg-gray-900 text-gray-900 dark:text-gray-100 p-2 rounded overflow-x-auto">
                        {JSON.stringify(JSON.parse(event.data), null, 2)}
                      </pre>
                    </div>
                  ))
                )}
              </div>
            </CardContent>
          </Card>

          {/* 現在の状態 */}
          <Card>
            <CardHeader>
              <CardTitle>🔍 現在の状態</CardTitle>
              <CardDescription>
                イベントから再構築された現在のデータベース状態
              </CardDescription>
            </CardHeader>
            <CardContent>
              <div className="max-h-64 overflow-y-auto">
                <pre className="text-xs bg-gray-50 dark:bg-gray-900 text-gray-900 dark:text-gray-100 p-4 rounded-lg">
                  {JSON.stringify(currentState, null, 2)}
                </pre>
              </div>
            </CardContent>
          </Card>
        </div>

        {/* クラウド同期セクション */}
        <Card className="mt-6">
          <CardHeader>
            <CardTitle>☁️ クラウド同期</CardTitle>
            <CardDescription>
              オフライン・オンライン同期機能のデモ
            </CardDescription>
          </CardHeader>
          <CardContent>
            <div className="space-y-4">
              <div>
                <Label htmlFor="syncEndpoint">同期エンドポイント URL</Label>
                <Input
                  id="syncEndpoint"
                  value={syncEndpoint}
                  onChange={(e) => setSyncEndpoint(e.target.value)}
                  placeholder="https://api.example.com/kawa"
                />
              </div>
              <div className="flex gap-2">
                <Button onClick={syncToCloud} variant="outline">
                  📤 クラウドに同期
                </Button>
                <Button variant="outline" disabled>
                  📥 クラウドから同期 (デモ)
                </Button>
                <Button variant="outline" disabled>
                  🌐 ネットワーク状態 (デモ)
                </Button>
              </div>
              <Alert>
                <AlertDescription>
                  💡 実際のクラウド同期を使用するには、対応するサーバーエンドポイントが必要です。
                  現在はブラウザローカルでのイベントソーシング機能、KSQLストリーミング処理、TypeScript ORMをお試しいただけます。
                </AlertDescription>
              </Alert>
            </div>
          </CardContent>
        </Card>

        {/* フッター */}
        <div className="text-center mt-8 text-gray-500 dark:text-gray-400">
          <p>🌐 KawaDB Browser Edition - Next.js + TypeScript ORM + WASM + KSQL Implementation</p>
          <p className="text-sm">Rust + WebAssembly + TypeScript での完全型安全サーバレス・イベントソーシング + ストリーミング処理</p>
        </div>
      </div>
    </div>
  )
}
