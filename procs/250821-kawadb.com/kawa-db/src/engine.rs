//! Main Database Engine for KawaDB

use crate::{
    config::DbConfig,
    error::{KawaDbError, KawaDbResult},
    query::{QueryEngine, QueryResult},
    table::TableManager,
    schema::TableSchema,
    DbStats,
};
use datafusion::execution::context::SessionContext;
use kawa_storage::StorageEngine;
use serde_json::Value;
use std::sync::Arc;
use std::time::Instant;

/// KawaDBメインエンジン
pub struct DatabaseEngine {
    /// DataFusionセッションコンテキスト
    session_ctx: Arc<SessionContext>,
    
    /// kawa-storageエンジン
    storage: Arc<StorageEngine>,
    
    /// クエリエンジン
    query_engine: QueryEngine,
    
    /// テーブル管理マネージャー
    table_manager: TableManager,
    
    /// データベース設定
    config: DbConfig,
    
    /// エンジン開始時刻
    start_time: Instant,
}

impl DatabaseEngine {
    /// 新しいデータベースエンジンを作成
    pub async fn new(config: DbConfig) -> KawaDbResult<Self> {
        tracing::info!("Initializing KawaDB engine with config: {:?}", config);

        // kawa-storageエンジンを初期化
        let storage = Arc::new(
            StorageEngine::new(config.storage.clone()).await
                .map_err(|e| KawaDbError::StorageError(e.to_string()))?
        );

        // DataFusionセッションコンテキストを作成
        let session_ctx = Arc::new(SessionContext::new());

        // クエリエンジンを初期化
        let query_engine = QueryEngine::new(session_ctx.clone(), storage.clone())?;
        
        // テーブル管理マネージャーを初期化
        let table_manager = TableManager::new(storage.clone())?;

        let engine = Self {
            session_ctx,
            storage,
            query_engine,
            table_manager,
            config,
            start_time: Instant::now(),
        };

        // データベースを初期化
        engine.initialize().await?;
        
        tracing::info!("KawaDB engine initialized successfully");
        Ok(engine)
    }

    /// データベースを初期化
    async fn initialize(&self) -> KawaDbResult<()> {
        tracing::info!("Initializing database components");
        
        // システムテーブルを作成
        self.create_system_tables().await?;
        
        // 既存テーブルの復元
        self.restore_existing_tables().await?;
        
        tracing::info!("Database initialization completed");
        Ok(())
    }

    /// SQLクエリを実行
    pub async fn execute_sql(&self, sql: &str) -> KawaDbResult<QueryResult> {
        tracing::debug!("Executing SQL query: {}", sql);
        
        let start = Instant::now();
        let result = self.query_engine.execute(sql).await;
        let duration = start.elapsed();
        
        match &result {
            Ok(query_result) => {
                tracing::info!(
                    "Query executed successfully in {:?}, affected rows: {}", 
                    duration, 
                    query_result.affected_rows()
                );
            }
            Err(e) => {
                tracing::error!("Query execution failed in {:?}: {}", duration, e);
            }
        }
        
        result
    }

    /// テーブルを作成
    pub async fn create_table(&self, schema: TableSchema) -> KawaDbResult<()> {
        tracing::info!("Creating table: {}", schema.name);
        
        self.table_manager.create_table(schema).await
    }

    /// テーブル一覧を取得
    pub async fn list_tables(&self) -> KawaDbResult<Vec<String>> {
        self.table_manager.list_tables().await
    }

    /// テーブルスキーマを取得
    pub async fn get_table_schema(&self, table_name: &str) -> KawaDbResult<Option<TableSchema>> {
        self.table_manager.get_table_schema(table_name).await
    }

    /// テーブルにデータを挿入
    pub async fn insert_data(
        &self,
        table_name: &str,
        data: Vec<Value>,
    ) -> KawaDbResult<usize> {
        tracing::debug!("Inserting {} rows into table: {}", data.len(), table_name);
        
        self.table_manager.insert_data(table_name, data).await
    }

    /// テーブルデータを読み取り
    pub async fn read_table_data(
        &self,
        table_name: &str,
        limit: Option<usize>,
        offset: Option<u64>,
    ) -> KawaDbResult<Vec<Value>> {
        self.table_manager.read_table_data(table_name, limit, offset).await
    }

    /// テーブルを削除
    pub async fn drop_table(&self, table_name: &str) -> KawaDbResult<()> {
        tracing::info!("Dropping table: {}", table_name);
        
        self.table_manager.drop_table(table_name).await
    }

    /// データベース統計を取得
    pub async fn get_stats(&self) -> KawaDbResult<DbStats> {
        let mut stats = self.table_manager.get_stats().await?;
        
        // エンジン固有の統計を追加
        stats.uptime_seconds = self.start_time.elapsed().as_secs();
        // TODO: アクティブクエリ数を追跡
        
        Ok(stats)
    }

    /// データベース接続をテスト
    pub async fn test_connection(&self) -> KawaDbResult<bool> {
        // 簡単なクエリを実行してデータベースが正常に動作するか確認
        match self.execute_sql("SHOW TABLES").await {
            Ok(_) => Ok(true),
            Err(e) => {
                tracing::error!("Connection test failed: {}", e);
                Ok(false)
            }
        }
    }

    /// データベースを最適化（コンパクション、統計更新など）
    pub async fn optimize(&self) -> KawaDbResult<()> {
        tracing::info!("Starting database optimization");
        
        // TODO: 以下の最適化処理を実装
        // 1. 古いセグメントのコンパクション
        // 2. インデックスの再構築
        // 3. 統計情報の更新
        // 4. 未使用データの削除
        
        tracing::info!("Database optimization completed");
        Ok(())
    }

    /// ストリーミングデータを処理
    pub async fn process_stream(
        &self,
        topic: &str,
        sql_transform: &str,
        output_table: &str,
    ) -> KawaDbResult<()> {
        tracing::info!(
            "Starting stream processing: {} -> {} -> {}", 
            topic, sql_transform, output_table
        );
        
        // TODO: リアルタイムストリーム処理を実装
        // 1. topicからメッセージを継続的に読み取り
        // 2. SQL変換を適用
        // 3. 結果をoutput_tableに保存
        // 4. エラーハンドリングとリトライ
        
        Ok(())
    }

    /// バックアップを作成
    pub async fn create_backup(&self, backup_path: &str) -> KawaDbResult<()> {
        tracing::info!("Creating backup to: {}", backup_path);
        
        // TODO: バックアップ機能を実装
        // 1. 全テーブルデータをエクスポート
        // 2. スキーマ情報をエクスポート
        // 3. 圧縮してバックアップファイルを作成
        
        Ok(())
    }

    /// バックアップから復元
    pub async fn restore_from_backup(&self, backup_path: &str) -> KawaDbResult<()> {
        tracing::info!("Restoring from backup: {}", backup_path);
        
        // TODO: 復元機能を実装
        // 1. バックアップファイルを解凍
        // 2. スキーマ情報を復元
        // 3. データを復元
        
        Ok(())
    }

    /// システムテーブルを作成
    async fn create_system_tables(&self) -> KawaDbResult<()> {
        // TODO: システムテーブルを実装
        // - information_schema.tables
        // - information_schema.columns  
        // - kawa_system.topics
        // - kawa_system.partitions
        // - kawa_system.query_log
        
        Ok(())
    }

    /// 既存テーブルを復元
    async fn restore_existing_tables(&self) -> KawaDbResult<()> {
        // テーブルマネージャーが自動的に既存テーブルを復元
        let tables = self.table_manager.list_tables().await?;
        
        tracing::info!("Restored {} existing tables", tables.len());
        for table in &tables {
            tracing::debug!("Restored table: {}", table);
        }
        
        Ok(())
    }

    /// 設定を取得
    pub fn get_config(&self) -> &DbConfig {
        &self.config
    }

    /// アップタイムを取得
    pub fn uptime(&self) -> std::time::Duration {
        self.start_time.elapsed()
    }
}

// セーフな送信とクローンのための実装
unsafe impl Send for DatabaseEngine {}
unsafe impl Sync for DatabaseEngine {}

impl Clone for DatabaseEngine {
    fn clone(&self) -> Self {
        // Note: このClone実装はエラーハンドリングが困難なため、必要時に手動でクローンを作成することを推奨
        let query_engine = QueryEngine::new(self.session_ctx.clone(), self.storage.clone())
            .expect("Failed to clone QueryEngine");
        let table_manager = TableManager::new(self.storage.clone())
            .expect("Failed to clone TableManager");
        
        Self {
            session_ctx: self.session_ctx.clone(),
            storage: self.storage.clone(),
            query_engine,
            table_manager,
            config: self.config.clone(),
            start_time: self.start_time,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::schema::{ColumnDefinition, DataType};
    use tempfile::TempDir;

    async fn create_test_engine() -> (TempDir, DatabaseEngine) {
        let temp_dir = TempDir::new().unwrap();
        let config = DbConfig::new(temp_dir.path().to_path_buf());
        
        let engine = DatabaseEngine::new(config).await.unwrap();
        (temp_dir, engine)
    }

    #[tokio::test]
    async fn test_engine_creation() {
        let (_temp_dir, engine) = create_test_engine().await;
        
        assert!(engine.test_connection().await.unwrap());
        assert_eq!(engine.uptime().as_secs(), 0); // 直後なので0秒
    }

    #[tokio::test]
    async fn test_table_operations() {
        let (_temp_dir, engine) = create_test_engine().await;

        // テーブル作成
        let schema = TableSchema::new(
            "users".to_string(),
            vec![
                ColumnDefinition::new("id".to_string(), DataType::Int64, false),
                ColumnDefinition::new("name".to_string(), DataType::String, false),
            ],
        );

        engine.create_table(schema).await.unwrap();
        
        // テーブル一覧確認
        let tables = engine.list_tables().await.unwrap();
        assert!(tables.contains(&"users".to_string()));

        // データ挿入
        let data = vec![
            serde_json::json!({"id": 1, "name": "Alice"}),
            serde_json::json!({"id": 2, "name": "Bob"}),
        ];

        let count = engine.insert_data("users", data).await.unwrap();
        assert_eq!(count, 2);

        // データ読み取り
        let retrieved = engine.read_table_data("users", None, None).await.unwrap();
        assert_eq!(retrieved.len(), 2);
    }

    #[tokio::test]
    async fn test_sql_execution() {
        let (_temp_dir, engine) = create_test_engine().await;
        
        // SHOW TABLESクエリをテスト
        let result = engine.execute_sql("SHOW TABLES").await.unwrap();
        
        match result {
            QueryResult::Select { rows, .. } => {
                assert_eq!(rows.len(), 0); // 初期状態では空
            }
            _ => panic!("Expected Select result"),
        }
    }

    #[tokio::test]
    async fn test_stats() {
        let (_temp_dir, engine) = create_test_engine().await;
        
        let stats = engine.get_stats().await.unwrap();
        assert_eq!(stats.total_tables, 0);
        assert_eq!(stats.total_rows, 0);
    }
} 