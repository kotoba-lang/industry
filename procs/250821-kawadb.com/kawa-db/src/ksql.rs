//! # KSQL Query Engine for KawaDB
//! 
//! KSQLDBライクなストリーミングクエリエンジンの実装
//! 継続的なクエリ処理、窓関数、ストリーミング変換を提供

use crate::{
    error::{KawaDbError, KawaDbResult},
    query::{QueryResult, QueryEngine},
    table::TableManager,
};
use datafusion::{
    execution::context::SessionContext,
};
use kawa_storage::StorageEngine;
use serde::{Deserialize, Serialize};
use serde_json::Value;
use std::{
    collections::HashMap,
    sync::Arc,
    time::{Duration, SystemTime},
};
use tokio::time::interval;

/// KSQLクエリエンジン
pub struct KsqlEngine {
    /// 基底SQLエンジン
    base_engine: QueryEngine,
    /// 継続的クエリ管理
    continuous_queries: Arc<tokio::sync::RwLock<HashMap<String, ContinuousQuery>>>,
    /// ストリーミング処理コンテキスト
    streaming_context: Arc<StreamingContext>,
    /// 結果配信チャネル
    result_publisher: tokio::sync::broadcast::Sender<StreamingResult>,
}

/// 継続的クエリ定義
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ContinuousQuery {
    /// クエリID
    pub id: String,
    /// クエリ名
    pub name: String,
    /// KSQLクエリ文
    pub ksql: String,
    /// 入力ストリーム/テーブル
    pub input_sources: Vec<String>,
    /// 出力ストリーム/テーブル
    pub output_target: String,
    /// 処理間隔（ミリ秒）
    pub processing_interval_ms: u64,
    /// 最終実行時刻
    pub last_execution: Option<SystemTime>,
    /// 状態
    pub status: QueryStatus,
    /// 統計情報
    pub statistics: QueryStatistics,
}

/// クエリ状態
#[derive(Debug, Clone, Serialize, Deserialize)]
pub enum QueryStatus {
    /// 実行中
    Running,
    /// 停止中
    Stopped,
    /// エラー
    Error(String),
    /// 一時停止
    Paused,
}

/// クエリ統計情報
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct QueryStatistics {
    /// 処理したメッセージ数
    pub messages_processed: u64,
    /// 生成した結果数
    pub results_produced: u64,
    /// 平均処理時間（ミリ秒）
    pub avg_processing_time_ms: f64,
    /// 最後のエラー
    pub last_error: Option<String>,
    /// 開始時刻
    pub started_at: SystemTime,
}

/// ストリーミング結果
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct StreamingResult {
    /// クエリID
    pub query_id: String,
    /// 結果データ
    pub data: Vec<Value>,
    /// タイムスタンプ
    pub timestamp: SystemTime,
    /// メタデータ
    pub metadata: HashMap<String, Value>,
}

/// ストリーミングコンテキスト
pub struct StreamingContext {
    /// セッションコンテキスト
    #[allow(dead_code)]
    session_ctx: Arc<SessionContext>,
    /// ストレージエンジン
    #[allow(dead_code)]
    storage: Arc<StorageEngine>,
    /// テーブル管理
    #[allow(dead_code)]
    table_manager: TableManager,
}

/// KSQLクエリタイプ
#[derive(Debug, Clone, Serialize, Deserialize)]
pub enum KsqlQueryType {
    /// ストリーム作成
    CreateStream {
        stream_name: String,
        columns: Vec<KsqlColumn>,
        source_topic: String,
        format: DataFormat,
    },
    /// テーブル作成
    CreateTable {
        table_name: String,
        columns: Vec<KsqlColumn>,
        source_topic: String,
        primary_key: Vec<String>,
        format: DataFormat,
    },
    /// ストリーム作成（クエリから）
    CreateStreamAsSelect {
        stream_name: String,
        select_query: String,
        partition_by: Option<String>,
    },
    /// テーブル作成（クエリから）
    CreateTableAsSelect {
        table_name: String,
        select_query: String,
        partition_by: Option<String>,
    },
    /// 選択クエリ
    Select {
        query: String,
        limit: Option<u64>,
    },
    /// ストリーム削除
    DropStream {
        stream_name: String,
    },
    /// テーブル削除
    DropTable {
        table_name: String,
    },
    /// 継続的クエリ実行
    SelectContinuous {
        query: String,
        emit_changes: bool,
    },
}

/// KSQLカラム定義
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct KsqlColumn {
    /// カラム名
    pub name: String,
    /// データタイプ
    pub data_type: KsqlDataType,
    /// NULL許可
    pub nullable: bool,
}

/// KSQLデータタイプ
#[derive(Debug, Clone, Serialize, Deserialize)]
pub enum KsqlDataType {
    /// 整数
    Integer,
    /// 長整数
    Bigint,
    /// 倍精度浮動小数点
    Double,
    /// 文字列
    String,
    /// ブール値
    Boolean,
    /// 配列
    Array(Box<KsqlDataType>),
    /// マップ
    Map(Box<KsqlDataType>, Box<KsqlDataType>),
    /// 構造体
    Struct(Vec<KsqlColumn>),
    /// 日時
    Timestamp,
    /// 日付
    Date,
    /// 時間
    Time,
}

/// データフォーマット
#[derive(Debug, Clone, Serialize, Deserialize)]
pub enum DataFormat {
    /// JSON
    Json,
    /// Avro
    Avro,
    /// Protobuf
    Protobuf,
    /// デリミタ区切り
    Delimited,
}

impl KsqlEngine {
    /// 新しいKSQLエンジンを作成
    pub fn new(
        session_ctx: Arc<SessionContext>,
        storage: Arc<StorageEngine>,
    ) -> KawaDbResult<Self> {
        let base_engine = QueryEngine::new(session_ctx.clone(), storage.clone())?;
        let table_manager = TableManager::new(storage.clone())?;
        
        let streaming_context = Arc::new(StreamingContext {
            session_ctx: session_ctx.clone(),
            storage: storage.clone(),
            table_manager,
        });
        
        let (result_publisher, _) = tokio::sync::broadcast::channel(1000);
        
        Ok(Self {
            base_engine,
            continuous_queries: Arc::new(tokio::sync::RwLock::new(HashMap::new())),
            streaming_context,
            result_publisher,
        })
    }

    /// KSQLクエリを実行
    pub async fn execute_ksql(&self, ksql: &str) -> KawaDbResult<QueryResult> {
        tracing::info!("Executing KSQL: {}", ksql);

        // KSQLクエリを解析
        let query_type = self.parse_ksql(ksql)?;
        
        // クエリタイプに応じて処理
        match query_type {
            KsqlQueryType::CreateStream { stream_name, columns, source_topic, format } => {
                self.create_stream(&stream_name, columns, &source_topic, format).await
            }
            KsqlQueryType::CreateTable { table_name, columns, source_topic, primary_key, format } => {
                self.create_table(&table_name, columns, &source_topic, primary_key, format).await
            }
            KsqlQueryType::CreateStreamAsSelect { stream_name, select_query, partition_by } => {
                self.create_stream_as_select(&stream_name, &select_query, partition_by).await
            }
            KsqlQueryType::CreateTableAsSelect { table_name, select_query, partition_by } => {
                self.create_table_as_select(&table_name, &select_query, partition_by).await
            }
            KsqlQueryType::Select { query, limit } => {
                self.execute_select(&query, limit).await
            }
            KsqlQueryType::SelectContinuous { query, emit_changes } => {
                self.execute_continuous_query(&query, emit_changes).await
            }
            KsqlQueryType::DropStream { stream_name } => {
                self.drop_stream(&stream_name).await
            }
            KsqlQueryType::DropTable { table_name } => {
                self.drop_table(&table_name).await
            }
        }
    }

    /// KSQLクエリを解析
    fn parse_ksql(&self, ksql: &str) -> KawaDbResult<KsqlQueryType> {
        let ksql_lower = ksql.trim().to_lowercase();
        
        if ksql_lower.starts_with("create stream") {
            self.parse_create_stream(ksql)
        } else if ksql_lower.starts_with("create table") {
            self.parse_create_table(ksql)
        } else if ksql_lower.starts_with("select") {
            // 継続的クエリかどうかを判定
            if ksql_lower.contains("emit changes") {
                self.parse_continuous_select(ksql)
            } else {
                self.parse_select(ksql)
            }
        } else if ksql_lower.starts_with("drop stream") {
            self.parse_drop_stream(ksql)
        } else if ksql_lower.starts_with("drop table") {
            self.parse_drop_table(ksql)
        } else {
            Err(KawaDbError::SqlParseError(
                format!("Unsupported KSQL statement: {}", ksql)
            ))
        }
    }

    /// CREATE STREAMを解析
    fn parse_create_stream(&self, ksql: &str) -> KawaDbResult<KsqlQueryType> {
        // 簡易パーサー: CREATE STREAM stream_name (columns) WITH (...)
        // 実際にはより高度なパーサーが必要
        
        // AS SELECTが含まれているかチェック
        if ksql.to_lowercase().contains("as select") {
            return self.parse_create_stream_as_select(ksql);
        }
        
        // ストリーム名を抽出
        let stream_name = self.extract_identifier_after_keyword(ksql, "CREATE STREAM")?;
        
        // カラム定義を抽出（簡略化）
        let columns = self.extract_columns_from_ddl(ksql)?;
        
        // WITH句からメタデータを抽出
        let (source_topic, format) = self.extract_with_clause(ksql)?;
        
        Ok(KsqlQueryType::CreateStream {
            stream_name,
            columns,
            source_topic,
            format,
        })
    }

    /// CREATE TABLE AS SELECTを解析
    fn parse_create_stream_as_select(&self, ksql: &str) -> KawaDbResult<KsqlQueryType> {
        let stream_name = self.extract_identifier_after_keyword(ksql, "CREATE STREAM")?;
        
        // AS SELECTの後の部分を抽出
        let select_start = ksql.to_lowercase().find("as select")
            .ok_or_else(|| KawaDbError::SqlParseError("AS SELECT not found".to_string()))?;
        let select_query = ksql[select_start + 9..].trim().to_string();
        
        // PARTITION BYを抽出（オプション）
        let partition_by = self.extract_partition_by(&select_query);
        
        Ok(KsqlQueryType::CreateStreamAsSelect {
            stream_name,
            select_query,
            partition_by,
        })
    }

    /// CREATE TABLEを解析
    fn parse_create_table(&self, ksql: &str) -> KawaDbResult<KsqlQueryType> {
        // AS SELECTが含まれているかチェック
        if ksql.to_lowercase().contains("as select") {
            return self.parse_create_table_as_select(ksql);
        }
        
        let table_name = self.extract_identifier_after_keyword(ksql, "CREATE TABLE")?;
        let columns = self.extract_columns_from_ddl(ksql)?;
        let (source_topic, format) = self.extract_with_clause(ksql)?;
        
        // PRIMARY KEYを抽出
        let primary_key = self.extract_primary_key(ksql)?;
        
        Ok(KsqlQueryType::CreateTable {
            table_name,
            columns,
            source_topic,
            primary_key,
            format,
        })
    }

    /// CREATE TABLE AS SELECTを解析
    fn parse_create_table_as_select(&self, ksql: &str) -> KawaDbResult<KsqlQueryType> {
        let table_name = self.extract_identifier_after_keyword(ksql, "CREATE TABLE")?;
        
        let select_start = ksql.to_lowercase().find("as select")
            .ok_or_else(|| KawaDbError::SqlParseError("AS SELECT not found".to_string()))?;
        let select_query = ksql[select_start + 9..].trim().to_string();
        
        let partition_by = self.extract_partition_by(&select_query);
        
        Ok(KsqlQueryType::CreateTableAsSelect {
            table_name,
            select_query,
            partition_by,
        })
    }

    /// SELECTクエリを解析
    fn parse_select(&self, ksql: &str) -> KawaDbResult<KsqlQueryType> {
        // LIMITを抽出
        let limit = self.extract_limit(ksql);
        
        Ok(KsqlQueryType::Select {
            query: ksql.to_string(),
            limit,
        })
    }

    /// 継続的SELECTクエリを解析
    fn parse_continuous_select(&self, ksql: &str) -> KawaDbResult<KsqlQueryType> {
        let emit_changes = ksql.to_lowercase().contains("emit changes");
        
        Ok(KsqlQueryType::SelectContinuous {
            query: ksql.to_string(),
            emit_changes,
        })
    }

    /// DROP STREAMを解析
    fn parse_drop_stream(&self, ksql: &str) -> KawaDbResult<KsqlQueryType> {
        let stream_name = self.extract_identifier_after_keyword(ksql, "DROP STREAM")?;
        
        Ok(KsqlQueryType::DropStream { stream_name })
    }

    /// DROP TABLEを解析
    fn parse_drop_table(&self, ksql: &str) -> KawaDbResult<KsqlQueryType> {
        let table_name = self.extract_identifier_after_keyword(ksql, "DROP TABLE")?;
        
        Ok(KsqlQueryType::DropTable { table_name })
    }

    /// ストリームを作成
    async fn create_stream(
        &self,
        stream_name: &str,
        columns: Vec<KsqlColumn>,
        source_topic: &str,
        format: DataFormat,
    ) -> KawaDbResult<QueryResult> {
        // ストリーム定義をメタデータとして保存
        let _stream_metadata = StreamMetadata {
            name: stream_name.to_string(),
            columns,
            source_topic: source_topic.to_string(),
            format,
            created_at: SystemTime::now(),
        };
        
        // TODO: メタデータをストレージに保存
        
        Ok(QueryResult::CreateTable {
            table_name: stream_name.to_string(),
        })
    }

    /// テーブルを作成
    async fn create_table(
        &self,
        table_name: &str,
        columns: Vec<KsqlColumn>,
        source_topic: &str,
        primary_key: Vec<String>,
        format: DataFormat,
    ) -> KawaDbResult<QueryResult> {
        // テーブル定義をメタデータとして保存
        let _table_metadata = TableMetadata {
            name: table_name.to_string(),
            columns,
            source_topic: source_topic.to_string(),
            primary_key,
            format,
            created_at: SystemTime::now(),
        };
        
        // TODO: メタデータをストレージに保存
        
        Ok(QueryResult::CreateTable {
            table_name: table_name.to_string(),
        })
    }

    /// ストリーム作成（クエリから）
    async fn create_stream_as_select(
        &self,
        stream_name: &str,
        select_query: &str,
        _partition_by: Option<String>,
    ) -> KawaDbResult<QueryResult> {
        // 継続的クエリとして登録
        let query_id = format!("stream_{}", stream_name);
        let continuous_query = ContinuousQuery {
            id: query_id.clone(),
            name: stream_name.to_string(),
            ksql: select_query.to_string(),
            input_sources: self.extract_input_sources(select_query)?,
            output_target: stream_name.to_string(),
            processing_interval_ms: 1000, // 1秒間隔
            last_execution: None,
            status: QueryStatus::Running,
            statistics: QueryStatistics {
                messages_processed: 0,
                results_produced: 0,
                avg_processing_time_ms: 0.0,
                last_error: None,
                started_at: SystemTime::now(),
            },
        };
        
        // 継続的クエリを登録
        self.continuous_queries.write().await.insert(query_id.clone(), continuous_query);
        
        // バックグラウンドタスクを開始
        self.start_continuous_query_task(query_id).await?;
        
        Ok(QueryResult::CreateTable {
            table_name: stream_name.to_string(),
        })
    }

    /// テーブル作成（クエリから）
    async fn create_table_as_select(
        &self,
        table_name: &str,
        select_query: &str,
        _partition_by: Option<String>,
    ) -> KawaDbResult<QueryResult> {
        // 継続的クエリとして登録（テーブル用）
        let query_id = format!("table_{}", table_name);
        let continuous_query = ContinuousQuery {
            id: query_id.clone(),
            name: table_name.to_string(),
            ksql: select_query.to_string(),
            input_sources: self.extract_input_sources(select_query)?,
            output_target: table_name.to_string(),
            processing_interval_ms: 5000, // 5秒間隔（テーブルは低頻度）
            last_execution: None,
            status: QueryStatus::Running,
            statistics: QueryStatistics {
                messages_processed: 0,
                results_produced: 0,
                avg_processing_time_ms: 0.0,
                last_error: None,
                started_at: SystemTime::now(),
            },
        };
        
        self.continuous_queries.write().await.insert(query_id.clone(), continuous_query);
        self.start_continuous_query_task(query_id).await?;
        
        Ok(QueryResult::CreateTable {
            table_name: table_name.to_string(),
        })
    }

    /// SELECT文を実行
    async fn execute_select(&self, query: &str, _limit: Option<u64>) -> KawaDbResult<QueryResult> {
        // 通常のSQL実行に委譲
        self.base_engine.execute(query).await
    }

    /// 継続的クエリを実行
    async fn execute_continuous_query(
        &self,
        query: &str,
        _emit_changes: bool,
    ) -> KawaDbResult<QueryResult> {
        // 一時的な継続的クエリを作成
        let query_id = format!("temp_{}", SystemTime::now().duration_since(SystemTime::UNIX_EPOCH).unwrap().as_millis());
        
        let continuous_query = ContinuousQuery {
            id: query_id.clone(),
            name: "temporary".to_string(),
            ksql: query.to_string(),
            input_sources: self.extract_input_sources(query)?,
            output_target: "stdout".to_string(),
            processing_interval_ms: 1000,
            last_execution: None,
            status: QueryStatus::Running,
            statistics: QueryStatistics {
                messages_processed: 0,
                results_produced: 0,
                avg_processing_time_ms: 0.0,
                last_error: None,
                started_at: SystemTime::now(),
            },
        };
        
        self.continuous_queries.write().await.insert(query_id.clone(), continuous_query);
        self.start_continuous_query_task(query_id).await?;
        
        Ok(QueryResult::Select {
            columns: vec!["status".to_string()],
            rows: vec![serde_json::json!({"status": "Continuous query started"})],
            row_count: 1,
        })
    }

    /// ストリームを削除
    async fn drop_stream(&self, stream_name: &str) -> KawaDbResult<QueryResult> {
        // 関連する継続的クエリを停止
        self.stop_queries_for_stream(stream_name).await?;
        
        // TODO: メタデータを削除
        
        Ok(QueryResult::DropTable {
            table_name: stream_name.to_string(),
        })
    }

    /// テーブルを削除
    async fn drop_table(&self, table_name: &str) -> KawaDbResult<QueryResult> {
        // 関連する継続的クエリを停止
        self.stop_queries_for_table(table_name).await?;
        
        // TODO: メタデータを削除
        
        Ok(QueryResult::DropTable {
            table_name: table_name.to_string(),
        })
    }

    /// 継続的クエリのタスクを開始
    async fn start_continuous_query_task(&self, query_id: String) -> KawaDbResult<()> {
        let queries = self.continuous_queries.clone();
        let streaming_context = self.streaming_context.clone();
        let result_publisher = self.result_publisher.clone();
        
        tokio::spawn(async move {
            let mut interval = interval(Duration::from_millis(1000));
            
            loop {
                interval.tick().await;
                
                // クエリを取得
                let query = {
                    let queries_read = queries.read().await;
                    match queries_read.get(&query_id) {
                        Some(q) if matches!(q.status, QueryStatus::Running) => q.clone(),
                        _ => break, // クエリが停止されたか削除された
                    }
                };
                
                // クエリを実行
                match Self::execute_continuous_query_iteration(&streaming_context, &query).await {
                    Ok(result) => {
                        // 結果を配信
                        if let Err(e) = result_publisher.send(result) {
                            tracing::warn!("Failed to publish streaming result: {}", e);
                        }
                        
                        // 統計を更新
                        if let Some(q) = queries.write().await.get_mut(&query_id) {
                            q.statistics.messages_processed += 1;
                            q.last_execution = Some(SystemTime::now());
                        }
                    }
                    Err(e) => {
                        tracing::error!("Continuous query execution failed: {}", e);
                        
                        // エラー状態に更新
                        if let Some(q) = queries.write().await.get_mut(&query_id) {
                            q.status = QueryStatus::Error(e.to_string());
                            q.statistics.last_error = Some(e.to_string());
                        }
                    }
                }
            }
        });
        
        Ok(())
    }

    /// 継続的クエリの1回の実行
    async fn execute_continuous_query_iteration(
        _streaming_context: &StreamingContext,
        query: &ContinuousQuery,
    ) -> KawaDbResult<StreamingResult> {
        // TODO: 実際のストリーミング処理を実装
        // 1. 入力ソースから新しいデータを読み取り
        // 2. SQLクエリを実行
        // 3. 結果を出力ターゲットに書き込み
        
        Ok(StreamingResult {
            query_id: query.id.clone(),
            data: vec![serde_json::json!({"message": "Mock streaming result"})],
            timestamp: SystemTime::now(),
            metadata: HashMap::new(),
        })
    }

    /// ストリーム用のクエリを停止
    async fn stop_queries_for_stream(&self, stream_name: &str) -> KawaDbResult<()> {
        let mut queries = self.continuous_queries.write().await;
        let query_ids: Vec<String> = queries.iter()
            .filter(|(_, q)| q.output_target == stream_name)
            .map(|(id, _)| id.clone())
            .collect();
        
        for query_id in query_ids {
            if let Some(query) = queries.get_mut(&query_id) {
                query.status = QueryStatus::Stopped;
            }
        }
        
        Ok(())
    }

    /// テーブル用のクエリを停止
    async fn stop_queries_for_table(&self, table_name: &str) -> KawaDbResult<()> {
        // ストリーム用と同じ処理
        self.stop_queries_for_stream(table_name).await
    }

    /// 継続的クエリ一覧を取得
    pub async fn list_continuous_queries(&self) -> Vec<ContinuousQuery> {
        self.continuous_queries.read().await.values().cloned().collect()
    }

    /// 結果ストリームを購読
    pub fn subscribe_results(&self) -> tokio::sync::broadcast::Receiver<StreamingResult> {
        self.result_publisher.subscribe()
    }

    // === パーサーヘルパー関数 ===

    /// キーワードの後の識別子を抽出
    fn extract_identifier_after_keyword(&self, sql: &str, keyword: &str) -> KawaDbResult<String> {
        let keyword_lower = keyword.to_lowercase();
        let sql_lower = sql.to_lowercase();
        
        let start = sql_lower.find(&keyword_lower)
            .ok_or_else(|| KawaDbError::SqlParseError(format!("Keyword '{}' not found", keyword)))?;
        
        let after_keyword = &sql[start + keyword.len()..].trim();
        let identifier = after_keyword.split_whitespace().next()
            .ok_or_else(|| KawaDbError::SqlParseError("Identifier not found".to_string()))?;
        
        Ok(identifier.to_string())
    }

    /// DDLからカラム定義を抽出
    fn extract_columns_from_ddl(&self, ddl: &str) -> KawaDbResult<Vec<KsqlColumn>> {
        // 簡易実装: (col1 TYPE, col2 TYPE, ...)
        // TODO: より高度なパーサーを実装
        
        let start = ddl.find('(')
            .ok_or_else(|| KawaDbError::SqlParseError("Column definition not found".to_string()))?;
        let end = ddl.find(')')
            .ok_or_else(|| KawaDbError::SqlParseError("Column definition not closed".to_string()))?;
        
        let columns_str = &ddl[start + 1..end];
        let mut columns = Vec::new();
        
        for column_def in columns_str.split(',') {
            let parts: Vec<&str> = column_def.trim().split_whitespace().collect();
            if parts.len() >= 2 {
                let name = parts[0].to_string();
                let type_str = parts[1].to_uppercase();
                let data_type = self.parse_ksql_data_type(&type_str)?;
                
                columns.push(KsqlColumn {
                    name,
                    data_type,
                    nullable: true, // TODO: NULL制約の解析
                });
            }
        }
        
        Ok(columns)
    }

    /// KSQLデータタイプを解析
    fn parse_ksql_data_type(&self, type_str: &str) -> KawaDbResult<KsqlDataType> {
        match type_str {
            "INTEGER" | "INT" => Ok(KsqlDataType::Integer),
            "BIGINT" => Ok(KsqlDataType::Bigint),
            "DOUBLE" => Ok(KsqlDataType::Double),
            "STRING" | "VARCHAR" => Ok(KsqlDataType::String),
            "BOOLEAN" => Ok(KsqlDataType::Boolean),
            "TIMESTAMP" => Ok(KsqlDataType::Timestamp),
            "DATE" => Ok(KsqlDataType::Date),
            "TIME" => Ok(KsqlDataType::Time),
            _ => Err(KawaDbError::SqlParseError(
                format!("Unsupported data type: {}", type_str)
            ))
        }
    }

    /// WITH句を抽出
    fn extract_with_clause(&self, sql: &str) -> KawaDbResult<(String, DataFormat)> {
        // 簡易実装: WITH (KAFKA_TOPIC='topic', VALUE_FORMAT='JSON')
        let with_start = sql.to_lowercase().find("with")
            .ok_or_else(|| KawaDbError::SqlParseError("WITH clause not found".to_string()))?;
        
        let with_clause = &sql[with_start..];
        
        // TOPICを抽出
        let topic = if let Some(start) = with_clause.find("KAFKA_TOPIC=") {
            let topic_part = &with_clause[start + 13..];
            let end = topic_part.find(',').unwrap_or(topic_part.len());
            topic_part[..end].trim_matches('\'').trim_matches('"').to_string()
        } else {
            "default_topic".to_string()
        };
        
        // フォーマットを抽出
        let format = if let Some(start) = with_clause.find("VALUE_FORMAT=") {
            let format_part = &with_clause[start + 13..];
            let end = format_part.find(',').unwrap_or(format_part.len());
            let format_str = format_part[..end].trim_matches('\'').trim_matches('"');
            match format_str.to_uppercase().as_str() {
                "JSON" => DataFormat::Json,
                "AVRO" => DataFormat::Avro,
                "PROTOBUF" => DataFormat::Protobuf,
                "DELIMITED" => DataFormat::Delimited,
                _ => DataFormat::Json,
            }
        } else {
            DataFormat::Json
        };
        
        Ok((topic, format))
    }

    /// PRIMARY KEYを抽出
    fn extract_primary_key(&self, sql: &str) -> KawaDbResult<Vec<String>> {
        // 簡易実装: PRIMARY KEY (col1, col2)
        if let Some(start) = sql.to_lowercase().find("primary key") {
            let pk_part = &sql[start + 11..];
            if let Some(start_paren) = pk_part.find('(') {
                if let Some(end_paren) = pk_part.find(')') {
                    let cols_str = &pk_part[start_paren + 1..end_paren];
                    return Ok(cols_str.split(',').map(|s| s.trim().to_string()).collect());
                }
            }
        }
        
        Ok(vec![])
    }

    /// PARTITION BYを抽出
    fn extract_partition_by(&self, sql: &str) -> Option<String> {
        sql.to_lowercase().find("partition by")
            .map(|start| {
                let partition_part = &sql[start + 12..];
                partition_part.split_whitespace().next().unwrap_or("").to_string()
            })
    }

    /// LIMITを抽出
    fn extract_limit(&self, sql: &str) -> Option<u64> {
        sql.to_lowercase().find("limit")
            .and_then(|start| {
                let limit_part = &sql[start + 5..];
                limit_part.split_whitespace().next()
                    .and_then(|s| s.parse().ok())
            })
    }

    /// 入力ソースを抽出
    fn extract_input_sources(&self, sql: &str) -> KawaDbResult<Vec<String>> {
        // 簡易実装: FROMとJOINからテーブル名を抽出
        let mut sources = Vec::new();
        
        // FROM句を検索
        if let Some(from_start) = sql.to_lowercase().find("from") {
            let from_part = &sql[from_start + 4..];
            if let Some(table_name) = from_part.split_whitespace().next() {
                sources.push(table_name.to_string());
            }
        }
        
        // JOIN句を検索
        let sql_lower = sql.to_lowercase();
        for join_keyword in &["join", "inner join", "left join", "right join", "full join"] {
            if let Some(join_start) = sql_lower.find(join_keyword) {
                let join_part = &sql[join_start + join_keyword.len()..];
                if let Some(table_name) = join_part.split_whitespace().next() {
                    sources.push(table_name.to_string());
                }
            }
        }
        
        Ok(sources)
    }
}

/// ストリームメタデータ
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct StreamMetadata {
    pub name: String,
    pub columns: Vec<KsqlColumn>,
    pub source_topic: String,
    pub format: DataFormat,
    pub created_at: SystemTime,
}

/// テーブルメタデータ
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TableMetadata {
    pub name: String,
    pub columns: Vec<KsqlColumn>,
    pub source_topic: String,
    pub primary_key: Vec<String>,
    pub format: DataFormat,
    pub created_at: SystemTime,
} 