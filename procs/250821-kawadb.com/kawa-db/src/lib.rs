//! # 🗄️ Kawa DB - DuckDB-like Local Database Engine
//! 
//! KawaDBは、DuckDBのようにローカル + WASM対応の高性能データベースエンジンです。
//! kawa-storageのイベントストレージ機能を拡張し、SQL処理とリアルタイム分析を提供します。

pub mod config;
pub mod engine;
pub mod error;
pub mod ksql;
pub mod query;
pub mod schema;
pub mod storage_adapter;
pub mod table;
pub mod graph_traversal;

#[cfg(feature = "wasm")]
pub mod wasm;

// Re-exports
pub use config::*;
pub use engine::*;
pub use error::*;
pub use ksql::*;
pub use query::*;
pub use schema::*;
pub use table::*;
pub use graph_traversal::*;

use std::sync::Arc;
use std::collections::HashMap;
use serde_json::Value;
use datafusion::execution::context::SessionContext;
use kawa_storage::{StorageEngine, Topic, Partition, Offset};

/// KawaDBのメインエンジン
pub struct KawaDb {
    /// ストレージエンジン
    pub storage: Arc<StorageEngine>,
    /// セッションコンテキスト
    pub session_ctx: Arc<SessionContext>,
    /// テーブル管理
    pub table_manager: TableManager,
    /// 設定
    #[allow(dead_code)]
    config: DbConfig,
}

impl Clone for KawaDb {
    fn clone(&self) -> Self {
        Self {
            storage: self.storage.clone(),
            session_ctx: self.session_ctx.clone(),
            table_manager: self.table_manager.clone(),
            config: self.config.clone(),
        }
    }
}

impl KawaDb {
    /// 新しいKawaDBインスタンスを作成
    pub async fn new(config: DbConfig) -> Result<Self, KawaDbError> {
        // kawa-storageエンジンを初期化
        let storage = Arc::new(
            StorageEngine::new(config.storage.clone()).await
                .map_err(|e| KawaDbError::StorageError(e.to_string()))?
        );

        // DataFusionセッションコンテキストを作成
        let session_ctx = Arc::new(SessionContext::new());

        // kawa-storageとDataFusionを統合するカスタムTableProviderを登録
        let table_manager = TableManager::new(storage.clone())
            .map_err(|e| KawaDbError::InternalError(e.to_string()))?;
        
        let db = Self {
            session_ctx,
            storage: storage.clone(),
            config,
            table_manager,
        };

        db.initialize().await?;
        Ok(db)
    }

    /// データベースを初期化
    async fn initialize(&self) -> Result<(), KawaDbError> {
        // システムテーブルを登録
        self.register_system_tables().await?;
        
        // 既存のテーブルを復元
        self.restore_tables().await?;
        
        Ok(())
    }

    /// SQLクエリを実行
    pub async fn execute_sql(&self, sql: &str) -> Result<QueryResult, KawaDbError> {
        let query_engine = QueryEngine::new(self.session_ctx.clone(), self.storage.clone())?;
        query_engine.execute(sql).await
    }

    /// テーブルを作成
    pub async fn create_table(&self, schema: TableSchema) -> Result<(), KawaDbError> {
        self.table_manager.create_table(schema).await
    }

    /// テーブル一覧を取得
    pub async fn list_tables(&self) -> Result<Vec<String>, KawaDbError> {
        self.table_manager.list_tables().await
    }

    /// テーブルにデータを挿入
    pub async fn insert_data(
        &self, 
        table_name: &str, 
        data: Vec<serde_json::Value>
    ) -> Result<usize, KawaDbError> {
        self.table_manager.insert_data(table_name, data).await
    }

    /// ストリーム処理を実行
    pub async fn process_stream(
        &self,
        _topic: &str,
        _sql_transform: &str,
        _output_table: &str,
    ) -> KawaDbResult<StreamProcessor> {
        // TODO: ストリーム処理を実装
        let stream_processor = StreamProcessor::new(self.storage.clone(), self.session_ctx.clone());
        
        // 実際の処理は別途実装
        // stream_processor.process(topic, sql_transform, output_table).await
        Ok(stream_processor)
    }

    /// ストリーム処理を作成
    pub async fn create_stream(
        &self,
        _topic: &str,
        _sql_transform: &str,
        _output_table: &str,
    ) -> KawaDbResult<StreamProcessor> {
        Ok(StreamProcessor::new(self.storage.clone(), self.session_ctx.clone()))
    }

    /// データベース統計を取得
    pub async fn get_stats(&self) -> Result<DbStats, KawaDbError> {
        self.table_manager.get_stats().await
    }

    /// 指定されたトピックからグラフをプロジェクションする
    pub async fn project_graph_from_topic(&self, topic_name: &str) -> Result<ProjectedGraph, KawaDbError> {
        let topic = Topic::new(topic_name);
        let mut graph = ProjectedGraph::new();

        // @todo: パーティションを動的に取得する
        let partition = Partition::new(0);
        
        let events = self.storage.read_events(&topic, partition, Offset::new(0), usize::MAX).await
            .map_err(|e| KawaDbError::StorageError(e.to_string()))?;

        for event in events {
            // イベントタイプに基づいてVertexまたはEdgeに変換
            // このロジックはアプリケーション固有になる可能性がある
            let data: HashMap<String, Value> = serde_json::from_slice(&event.data)
                .unwrap_or_default();
            
            let vertex = GraphVertex {
                id: event.id.to_string(), // イベントIDを頂点IDとして使用
                label: event.topic.to_string(), // topicをlabelとして暫定使用
                properties: data,
            };
            graph.vertices.insert(vertex.id.clone(), vertex);
        }

        Ok(graph)
    }

    /// システムテーブルを登録
    async fn register_system_tables(&self) -> Result<(), KawaDbError> {
        // TODO: システムテーブル実装
        // - information_schema.tables
        // - information_schema.columns
        // - kawa_system.topics
        // - kawa_system.partitions
        Ok(())
    }

    /// 既存テーブルを復元
    async fn restore_tables(&self) -> Result<(), KawaDbError> {
        // TODO: 永続化されたテーブル定義を復元
        Ok(())
    }
}

/// ストリーミングデータ処理エンジン
pub struct StreamProcessor {
    #[allow(dead_code)]
    storage: Arc<StorageEngine>,
    #[allow(dead_code)]
    session_ctx: Arc<SessionContext>,
}

impl StreamProcessor {
    pub fn new(storage: Arc<StorageEngine>, session_ctx: Arc<SessionContext>) -> Self {
        Self { storage, session_ctx }
    }

    /// ストリーミングデータを処理
    pub async fn process(
        &self,
        _topic: &str,
        _sql_transform: &str,
        _output_table: &str,
    ) -> Result<(), KawaDbError> {
        // TODO: リアルタイムストリーム処理実装
        // 1. topicからメッセージを読み取り
        // 2. SQL変換を適用
        // 3. 結果をoutput_tableに保存
        Ok(())
    }
}

/// データベース統計情報
#[derive(Debug, serde::Serialize, serde::Deserialize)]
pub struct DbStats {
    pub total_tables: usize,
    pub total_rows: u64,
    pub total_size_bytes: u64,
    pub active_queries: usize,
    pub uptime_seconds: u64,
}

#[cfg(test)]
mod tests {
    use super::*;
    use tempfile::TempDir;
    use std::collections::HashMap;

    #[tokio::test]
    async fn test_kawa_db_creation() {
        let temp_dir = TempDir::new().unwrap();
        let config = DbConfig::new(temp_dir.path().to_path_buf());
        
        let db = KawaDb::new(config).await.unwrap();
        
        // 基本的な動作確認
        let stats = db.get_stats().await.unwrap();
        assert_eq!(stats.total_tables, 0);
    }

    #[tokio::test]
    async fn test_table_operations() {
        let temp_dir = TempDir::new().unwrap();
        let config = DbConfig::new(temp_dir.path().to_path_buf());
        let db = KawaDb::new(config).await.unwrap();

        // テーブル作成
        let schema = TableSchema::new(
            "users".to_string(),
            vec![
                ColumnDefinition::new("id".to_string(), DataType::Int64, false),
                ColumnDefinition::new("name".to_string(), DataType::String, false),
            ],
        );

        db.create_table(schema).await.unwrap();
        
        let tables = db.list_tables().await.unwrap();
        assert!(tables.contains(&"users".to_string()));
    }

    #[test]
    fn test_graph_traversal() {
        let mut graph = ProjectedGraph::new();

        // Create vertices
        let v1 = GraphVertex { id: "1".to_string(), label: "person".to_string(), properties: [("name".to_string(), serde_json::json!("marko"))].iter().cloned().collect() };
        let v2 = GraphVertex { id: "2".to_string(), label: "person".to_string(), properties: [("name".to_string(), serde_json::json!("vadas"))].iter().cloned().collect() };
        let v3 = GraphVertex { id: "3".to_string(), label: "software".to_string(), properties: [("name".to_string(), serde_json::json!("lop"))].iter().cloned().collect() };
        graph.vertices.insert("1".to_string(), v1);
        graph.vertices.insert("2".to_string(), v2);
        graph.vertices.insert("3".to_string(), v3);

        // Create edges
        let e1 = GraphEdge { id: "7".to_string(), label: "knows".to_string(), out_v: "1".to_string(), in_v: "2".to_string(), properties: HashMap::new() };
        let e2 = GraphEdge { id: "9".to_string(), label: "created".to_string(), out_v: "1".to_string(), in_v: "3".to_string(), properties: HashMap::new() };
        graph.edges.insert("7".to_string(), e1);
        graph.edges.insert("9".to_string(), e2);

        let mut traverser = Traverser::new(&graph);
        
        let names = traverser.V()
            .has("name", &serde_json::json!("marko"))
            .out("created")
            .values("name");

        assert_eq!(names.len(), 1);
        assert_eq!(names[0], "lop");
    }
} 