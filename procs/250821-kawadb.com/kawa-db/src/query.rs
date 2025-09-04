//! SQL Query Engine for KawaDB

use crate::{
    error::{KawaDbError, KawaDbResult},
    storage_adapter::KawaTableProvider,
    table::TableManager,
};
use datafusion::{
    execution::context::SessionContext,
    prelude::*,
    arrow::record_batch::RecordBatch,
    logical_expr::LogicalPlan,
};
use kawa_storage::StorageEngine;
use serde_json::Value;
use std::sync::Arc;

/// クエリエンジン
pub struct QueryEngine {
    session_ctx: Arc<SessionContext>,
    storage: Arc<StorageEngine>,
    table_manager: TableManager,
}

impl QueryEngine {
    /// 新しいクエリエンジンを作成
    pub fn new(session_ctx: Arc<SessionContext>, storage: Arc<StorageEngine>) -> KawaDbResult<Self> {
        let table_manager = TableManager::new(storage.clone())?;
        
        Ok(Self {
            session_ctx,
            storage,
            table_manager,
        })
    }

    /// SQLクエリを実行
    pub async fn execute(&self, sql: &str) -> KawaDbResult<QueryResult> {
        tracing::info!("Executing SQL: {}", sql);

        // SQLを解析
        let plan = self.parse_and_plan(sql).await?;
        
        // プランを実行
        match plan {
            QueryPlan::Select(logical_plan) => {
                self.execute_select(logical_plan).await
            }
            QueryPlan::Insert { table_name, values } => {
                self.execute_insert(&table_name, values).await
            }
            QueryPlan::CreateTable { schema } => {
                self.execute_create_table(schema).await
            }
            QueryPlan::DropTable { table_name } => {
                self.execute_drop_table(&table_name).await
            }
            QueryPlan::ShowTables => {
                self.execute_show_tables().await
            }
        }
    }

    /// SQLを解析してプランを作成
    async fn parse_and_plan(&self, sql: &str) -> KawaDbResult<QueryPlan> {
        // 簡易SQL解析（実際にはより高度なパーサーが必要）
        let sql_trimmed = sql.trim().to_lowercase();
        
        if sql_trimmed.starts_with("select") {
            // DataFusionでSELECTクエリを解析
            let logical_plan = self.session_ctx.sql(sql).await?.into_optimized_plan()?;
            Ok(QueryPlan::Select(logical_plan))
        } else if sql_trimmed.starts_with("insert") {
            // INSERT文を解析
            self.parse_insert(sql).await
        } else if sql_trimmed.starts_with("create table") {
            // CREATE TABLE文を解析
            self.parse_create_table(sql).await
        } else if sql_trimmed.starts_with("drop table") {
            // DROP TABLE文を解析
            self.parse_drop_table(sql).await
        } else if sql_trimmed == "show tables" {
            Ok(QueryPlan::ShowTables)
        } else {
            Err(KawaDbError::SqlParseError(
                format!("Unsupported SQL statement: {}", sql)
            ))
        }
    }

    /// INSERT文を解析
    async fn parse_insert(&self, sql: &str) -> KawaDbResult<QueryPlan> {
        // 簡易パーサー（実際にはsqlparserクレートを使用すべき）
        // INSERT INTO table_name VALUES (val1, val2, ...)
        
        // テーブル名を抽出
        let parts: Vec<&str> = sql.split_whitespace().collect();
        if parts.len() < 4 || parts[0].to_lowercase() != "insert" || 
           parts[1].to_lowercase() != "into" {
            return Err(KawaDbError::SqlParseError(
                "Invalid INSERT syntax".to_string()
            ));
        }
        
        let table_name = parts[2].to_string();
        
        // VALUES部分を抽出（簡略化）
        let values_part = if let Some(values_idx) = sql.to_lowercase().find("values") {
            &sql[values_idx + 6..].trim()
        } else {
            return Err(KawaDbError::SqlParseError(
                "VALUES clause not found".to_string()
            ));
        };
        
        // 値を解析（簡略化 - JSONとして解析）
        let values = if values_part.starts_with('(') && values_part.ends_with(')') {
            let json_str = &values_part[1..values_part.len()-1];
            // 簡単な例: (1, 'Alice') -> {"col1": 1, "col2": "Alice"}
            // 実際にはより高度なパーサーが必要
            vec![serde_json::json!({"data": json_str})]
        } else {
            return Err(KawaDbError::SqlParseError(
                "Invalid VALUES format".to_string()
            ));
        };
        
        Ok(QueryPlan::Insert { table_name, values })
    }

    /// CREATE TABLE文を解析
    async fn parse_create_table(&self, _sql: &str) -> KawaDbResult<QueryPlan> {
        // 簡易パーサー - 実際にはsqlparserを使用
        // CREATE TABLE table_name (col1 TYPE, col2 TYPE, ...)
        
        use crate::schema::{TableSchema, ColumnDefinition, DataType};
        
        let table_name = "parsed_table".to_string(); // 簡略化
        let columns = vec![
            ColumnDefinition::new("id".to_string(), DataType::Int64, false),
            ColumnDefinition::new("data".to_string(), DataType::String, true),
        ];
        
        let schema = TableSchema::new(table_name, columns);
        Ok(QueryPlan::CreateTable { schema })
    }

    /// DROP TABLE文を解析
    async fn parse_drop_table(&self, sql: &str) -> KawaDbResult<QueryPlan> {
        let parts: Vec<&str> = sql.split_whitespace().collect();
        if parts.len() < 3 || parts[0].to_lowercase() != "drop" || 
           parts[1].to_lowercase() != "table" {
            return Err(KawaDbError::SqlParseError(
                "Invalid DROP TABLE syntax".to_string()
            ));
        }
        
        let table_name = parts[2].to_string();
        Ok(QueryPlan::DropTable { table_name })
    }

    /// SELECTクエリを実行
    async fn execute_select(&self, logical_plan: LogicalPlan) -> KawaDbResult<QueryResult> {
        // テーブルプロバイダーを登録
        self.register_table_providers().await?;
        
        // クエリを実行
        let df = DataFrame::new(self.session_ctx.state(), logical_plan);
        let batches = df.collect().await?;
        
        // RecordBatchをJSONに変換
        let mut rows = Vec::new();
        for batch in batches {
            let json_rows = self.record_batch_to_json(batch)?;
            rows.extend(json_rows);
        }
        
        let row_count = rows.len();
        
        Ok(QueryResult::Select {
            columns: Vec::new(), // TODO: カラム情報を取得
            rows,
            row_count,
        })
    }

    /// INSERT文を実行
    async fn execute_insert(&self, table_name: &str, values: Vec<Value>) -> KawaDbResult<QueryResult> {
        let affected_rows = self.table_manager.insert_data(table_name, values).await?;
        
        Ok(QueryResult::Insert { affected_rows })
    }

    /// CREATE TABLE文を実行
    async fn execute_create_table(&self, schema: crate::schema::TableSchema) -> KawaDbResult<QueryResult> {
        self.table_manager.create_table(schema).await?;
        
        Ok(QueryResult::CreateTable { 
            table_name: "created".to_string() // TODO: 実際のテーブル名
        })
    }

    /// DROP TABLE文を実行
    async fn execute_drop_table(&self, table_name: &str) -> KawaDbResult<QueryResult> {
        self.table_manager.drop_table(table_name).await?;
        
        Ok(QueryResult::DropTable { 
            table_name: table_name.to_string() 
        })
    }

    /// SHOW TABLES文を実行
    async fn execute_show_tables(&self) -> KawaDbResult<QueryResult> {
        let tables = self.table_manager.list_tables().await?;
        let rows: Vec<Value> = tables.into_iter()
            .map(|table| serde_json::json!({"table_name": table}))
            .collect();
        
        let row_count = rows.len();
        
        Ok(QueryResult::Select {
            columns: vec!["table_name".to_string()],
            rows,
            row_count,
        })
    }

    /// テーブルプロバイダーを登録
    async fn register_table_providers(&self) -> KawaDbResult<()> {
        let tables = self.table_manager.list_tables().await?;
        
        for table_name in tables {
            if let Some(schema) = self.table_manager.get_table_schema(&table_name).await? {
                // スキーマをArrowスキーマに変換
                let arrow_schema = Arc::new(schema.to_arrow_schema()?);
                
                let provider = KawaTableProvider::new(
                    self.storage.clone(),
                    table_name.clone(),
                    arrow_schema,
                );
                
                self.session_ctx.register_table(&table_name, Arc::new(provider))?;
            }
        }
        
        Ok(())
    }

    /// RecordBatchをJSONに変換
    fn record_batch_to_json(&self, batch: RecordBatch) -> KawaDbResult<Vec<Value>> {
        // record_batches_to_json_rowsの代替実装
        let mut json_rows = Vec::new();
        let schema = batch.schema();
        
        for row_idx in 0..batch.num_rows() {
            let mut row_data = serde_json::Map::new();
            
            for col_idx in 0..batch.num_columns() {
                let column = batch.column(col_idx);
                let field = schema.field(col_idx);
                
                // 値を文字列として取得（簡略化）
                let value = format!("{:?}", column.slice(row_idx, 1));
                row_data.insert(field.name().clone(), serde_json::Value::String(value));
            }
            
            json_rows.push(serde_json::Value::Object(row_data));
        }
        
        Ok(json_rows)
    }
}

/// クエリプラン
#[derive(Debug)]
pub enum QueryPlan {
    Select(LogicalPlan),
    Insert {
        table_name: String,
        values: Vec<Value>,
    },
    CreateTable {
        schema: crate::schema::TableSchema,
    },
    DropTable {
        table_name: String,
    },
    ShowTables,
}

/// クエリ結果
#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
pub enum QueryResult {
    Select {
        columns: Vec<String>,
        rows: Vec<Value>,
        row_count: usize,
    },
    Insert {
        affected_rows: usize,
    },
    CreateTable {
        table_name: String,
    },
    DropTable {
        table_name: String,
    },
    Error {
        message: String,
    },
}

impl QueryResult {
    /// 成功結果かどうか
    pub fn is_success(&self) -> bool {
        !matches!(self, QueryResult::Error { .. })
    }

    /// 影響を受けた行数を取得
    pub fn affected_rows(&self) -> usize {
        match self {
            QueryResult::Select { row_count, .. } => *row_count,
            QueryResult::Insert { affected_rows } => *affected_rows,
            QueryResult::CreateTable { .. } => 1,
            QueryResult::DropTable { .. } => 1,
            QueryResult::Error { .. } => 0,
        }
    }

    /// JSONとして取得
    pub fn to_json(&self) -> KawaDbResult<String> {
        serde_json::to_string(self).map_err(Into::into)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use tempfile::TempDir;
    use kawa_storage::StorageConfig;

    async fn create_test_query_engine() -> QueryEngine {
        let temp_dir = TempDir::new().unwrap();
        let config = StorageConfig {
            data_dir: temp_dir.path().to_path_buf(),
            compression_type: None,
            segment_size: 1024 * 1024,
            sync_interval_ms: 100,
            enable_compression: false,
            memory_pool_size: 1024 * 1024,
            batch_size: 100,
            worker_count: None,
        };
        
        let storage = Arc::new(StorageEngine::new(config).await.unwrap());
        let session_ctx = Arc::new(SessionContext::new());
        
        QueryEngine::new(session_ctx, storage).unwrap()
    }

    #[tokio::test]
    async fn test_show_tables() {
        let engine = create_test_query_engine().await;
        
        let result = engine.execute("SHOW TABLES").await.unwrap();
        
        match result {
            QueryResult::Select { rows, .. } => {
                assert_eq!(rows.len(), 0); // 初期状態では空
            }
            _ => panic!("Expected Select result"),
        }
    }

    #[tokio::test]
    async fn test_query_result_serialization() {
        let result = QueryResult::Insert { affected_rows: 5 };
        let json = result.to_json().unwrap();
        
        assert!(json.contains("affected_rows"));
        assert!(json.contains("5"));
    }
} 