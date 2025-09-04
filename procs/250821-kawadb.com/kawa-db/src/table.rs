//! Table Management for KawaDB

use crate::{
    error::{KawaDbError, KawaDbResult},
    schema::{TableSchema, ColumnDefinition, DataType},
    DbStats,
};
use kawa_storage::{StorageEngine, Topic, Partition, EventData};
use kawa_serialization::{MessageSerializer, SerializationFormat, SerializationPurpose, SerializerFactory};
use serde_json::Value;
use std::sync::Arc;
use std::collections::HashMap;

/// テーブル管理マネージャー
#[derive(Debug, Clone)]
pub struct TableManager {
    storage: Arc<StorageEngine>,
    /// メタデータキャッシュ
    schema_cache: Arc<tokio::sync::RwLock<HashMap<String, TableSchema>>>,
    /// データシリアライザー
    serializer: SerializationFormat,
}

impl TableManager {
    /// 新しいテーブルマネージャーを作成
    pub fn new(storage: Arc<StorageEngine>) -> KawaDbResult<Self> {
        // Database用途のシリアライザーを使用
        let serializer = SerializerFactory::create_for_purpose(SerializationPurpose::Storage)
            .map_err(|e| KawaDbError::InternalError(format!("Serializer initialization failed: {}", e)))?;
        
        Ok(Self {
            storage,
            schema_cache: Arc::new(tokio::sync::RwLock::new(HashMap::new())),
            serializer,
        })
    }
    
    /// カスタムシリアライザーでテーブルマネージャーを作成
    pub fn with_serializer(storage: Arc<StorageEngine>, serializer: SerializationFormat) -> Self {
        Self {
            storage,
            schema_cache: Arc::new(tokio::sync::RwLock::new(HashMap::new())),
            serializer,
        }
    }

    /// テーブルを作成
    pub async fn create_table(&self, schema: TableSchema) -> KawaDbResult<()> {
        // スキーマを検証
        schema.validate()?;

        // 既存テーブルの確認
        if self.table_exists(&schema.name).await? {
            return Err(KawaDbError::SchemaValidationError(
                format!("Table '{}' already exists", schema.name)
            ));
        }

        // メタデータトピックに保存
        let metadata_topic = Topic::new("_kawa_table_metadata");
        let partition = Partition::new(0);
        
        let metadata = TableMetadata::new(schema.clone());
        let event_data = EventData::from_json(&serde_json::to_string(&metadata)?);
        
        self.storage.append_event(&metadata_topic, partition, event_data).await
            .map_err(|e| KawaDbError::StorageError(e.to_string()))?;

        // データ用トピックを作成（テーブル名をトピック名として使用）
        let data_topic = Topic::new(&format!("table_{}", schema.name));
        
        // 初期化イベントを挿入（テーブル作成記録）
        let init_event = TableEvent::TableCreated {
            table_name: schema.name.clone(),
            schema: schema.clone(),
            timestamp: chrono::Utc::now(),
        };
        let init_data = EventData::from_json(&serde_json::to_string(&init_event)?);
        
        self.storage.append_event(&data_topic, partition, init_data).await
            .map_err(|e| KawaDbError::StorageError(e.to_string()))?;

        // キャッシュに追加
        let mut cache = self.schema_cache.write().await;
        cache.insert(schema.name.clone(), schema);

        Ok(())
    }

    /// テーブルが存在するかチェック
    pub async fn table_exists(&self, table_name: &str) -> KawaDbResult<bool> {
        // まずキャッシュをチェック
        {
            let cache = self.schema_cache.read().await;
            if cache.contains_key(table_name) {
                return Ok(true);
            }
        }

        // ストレージから確認
        self.load_table_metadata(table_name).await.map(|schema| schema.is_some())
    }

    /// テーブル一覧を取得
    pub async fn list_tables(&self) -> KawaDbResult<Vec<String>> {
        self.refresh_schema_cache().await?;
        
        let cache = self.schema_cache.read().await;
        Ok(cache.keys().cloned().collect())
    }

    /// テーブルスキーマを取得
    pub async fn get_table_schema(&self, table_name: &str) -> KawaDbResult<Option<TableSchema>> {
        // キャッシュから取得を試行
        {
            let cache = self.schema_cache.read().await;
            if let Some(schema) = cache.get(table_name) {
                return Ok(Some(schema.clone()));
            }
        }

        // ストレージから読み取り
        if let Some(schema) = self.load_table_metadata(table_name).await? {
            // キャッシュに追加
            let mut cache = self.schema_cache.write().await;
            cache.insert(table_name.to_string(), schema.clone());
            Ok(Some(schema))
        } else {
            Ok(None)
        }
    }

    /// データを挿入
    pub async fn insert_data(
        &self,
        table_name: &str,
        data: Vec<Value>,
    ) -> KawaDbResult<usize> {
        // テーブルスキーマを取得
        let schema = self.get_table_schema(table_name).await?
            .ok_or_else(|| KawaDbError::TableNotFound(table_name.to_string()))?;

        // データを検証
        for row in &data {
            self.validate_row_data(&schema, row)?;
        }

        // データをイベントとして保存
        let data_topic = Topic::new(&format!("table_{}", table_name));
        let partition = Partition::new(0);
        
        let mut inserted_count = 0;
        for row in data {
            let insert_event = TableEvent::DataInserted {
                table_name: table_name.to_string(),
                data: row,
                timestamp: chrono::Utc::now(),
            };
            
            // データベース用に最適化されたシリアライゼーション
            let json_data = serde_json::to_string(&insert_event)?;
            let serialized_data = self.serializer.serialize(json_data.as_bytes())
                .map_err(|e| KawaDbError::InternalError(format!("Data serialization failed: {}", e)))?;
            
            let event_data = EventData::from_bytes(serialized_data);
            
            self.storage.append_event(&data_topic, partition, event_data).await
                .map_err(|e| KawaDbError::StorageError(e.to_string()))?;
            
            inserted_count += 1;
        }

        Ok(inserted_count)
    }

    /// テーブルデータを読み取り
    pub async fn read_table_data(
        &self,
        table_name: &str,
        limit: Option<usize>,
        offset: Option<u64>,
    ) -> KawaDbResult<Vec<Value>> {
        // テーブル存在確認
        if !self.table_exists(table_name).await? {
            return Err(KawaDbError::TableNotFound(table_name.to_string()));
        }

        let data_topic = Topic::new(&format!("table_{}", table_name));
        let partition = Partition::new(0);
        let start_offset = kawa_storage::Offset::new(offset.unwrap_or(0));
        let max_events = limit.unwrap_or(1000);

        let events = self.storage
            .read_events(&data_topic, partition, start_offset, max_events)
            .await
            .map_err(|e| KawaDbError::StorageError(e.to_string()))?;

        let mut result = Vec::new();
        for event in events {
            // データをデシリアライズ
            let deserialized_data = match self.serializer.deserialize(event.data.as_bytes()) {
                Ok(data) => data,
                Err(e) => {
                    tracing::warn!("Failed to deserialize table data: {}. Trying fallback to JSON.", e);
                    // フォールバック: 直接JSONとして解析
                    event.data.to_json().into_bytes()
                }
            };
            
            let json_str = String::from_utf8(deserialized_data)
                .map_err(|e| KawaDbError::InternalError(format!("Invalid UTF-8 data: {}", e)))?;
            
            if let Ok(table_event) = serde_json::from_str::<TableEvent>(&json_str) {
                match table_event {
                    TableEvent::DataInserted { data, .. } => {
                        result.push(data);
                    }
                    _ => {} // 他のイベントタイプはスキップ
                }
            }
        }

        Ok(result)
    }

    /// テーブルを削除
    pub async fn drop_table(&self, table_name: &str) -> KawaDbResult<()> {
        // テーブル存在確認
        if !self.table_exists(table_name).await? {
            return Err(KawaDbError::TableNotFound(table_name.to_string()));
        }

        // 削除イベントを記録
        let data_topic = Topic::new(&format!("table_{}", table_name));
        let partition = Partition::new(0);
        
        let drop_event = TableEvent::TableDropped {
            table_name: table_name.to_string(),
            timestamp: chrono::Utc::now(),
        };
        
        let event_data = EventData::from_json(&serde_json::to_string(&drop_event)?);
        
        self.storage.append_event(&data_topic, partition, event_data).await
            .map_err(|e| KawaDbError::StorageError(e.to_string()))?;

        // キャッシュから削除
        let mut cache = self.schema_cache.write().await;
        cache.remove(table_name);

        Ok(())
    }

    /// データベース統計を取得
    pub async fn get_stats(&self) -> KawaDbResult<DbStats> {
        self.refresh_schema_cache().await?;
        
        let cache = self.schema_cache.read().await;
        let total_tables = cache.len();
        
        // 各テーブルの行数を計算（簡易実装）
        let mut total_rows = 0u64;
        let mut total_size_bytes = 0u64;
        
        for table_name in cache.keys() {
            let data = self.read_table_data(table_name, None, None).await?;
            total_rows += data.len() as u64;
            
            // 簡易サイズ計算
            for row in data {
                total_size_bytes += serde_json::to_string(&row)?.len() as u64;
            }
        }

        Ok(DbStats {
            total_tables,
            total_rows,
            total_size_bytes,
            active_queries: 0, // TODO: クエリ実行中カウンターを実装
            uptime_seconds: 0, // TODO: 起動時間を追跡
        })
    }

    /// スキーマキャッシュを更新
    async fn refresh_schema_cache(&self) -> KawaDbResult<()> {
        let metadata_topic = Topic::new("_kawa_table_metadata");
        let partition = Partition::new(0);
        let start_offset = kawa_storage::Offset::new(0);
        
        match self.storage.read_events(&metadata_topic, partition, start_offset, 10000).await {
            Ok(events) => {
                let mut cache = self.schema_cache.write().await;
                cache.clear();
                
                for event in events {
                    if let Ok(metadata) = serde_json::from_str::<TableMetadata>(&event.data.to_json()) {
                        cache.insert(metadata.schema.name.clone(), metadata.schema);
                    }
                }
                
                Ok(())
            }
            Err(_) => {
                // メタデータトピックが存在しない場合は空のキャッシュのまま
                Ok(())
            }
        }
    }

    /// ストレージからテーブルメタデータを読み取り
    async fn load_table_metadata(&self, table_name: &str) -> KawaDbResult<Option<TableSchema>> {
        let metadata_topic = Topic::new("_kawa_table_metadata");
        let partition = Partition::new(0);
        let start_offset = kawa_storage::Offset::new(0);
        
        match self.storage.read_events(&metadata_topic, partition, start_offset, 10000).await {
            Ok(events) => {
                for event in events {
                    if let Ok(metadata) = serde_json::from_str::<TableMetadata>(&event.data.to_json()) {
                        if metadata.schema.name == table_name {
                            return Ok(Some(metadata.schema));
                        }
                    }
                }
                Ok(None)
            }
            Err(_) => Ok(None),
        }
    }

    /// 行データを検証
    fn validate_row_data(&self, schema: &TableSchema, row: &Value) -> KawaDbResult<()> {
        let obj = row.as_object()
            .ok_or_else(|| KawaDbError::DataTypeMismatch {
                expected: "object".to_string(),
                actual: format!("{:?}", row),
            })?;

        for column in &schema.columns {
            if let Some(value) = obj.get(&column.name) {
                self.validate_column_value(column, value)?;
            } else if !column.nullable && column.default_value.is_none() {
                return Err(KawaDbError::SchemaValidationError(
                    format!("Non-nullable column '{}' is missing", column.name)
                ));
            }
        }

        Ok(())
    }

    /// カラム値を検証
    fn validate_column_value(&self, column: &ColumnDefinition, value: &Value) -> KawaDbResult<()> {
        if value.is_null() {
            if !column.nullable {
                return Err(KawaDbError::SchemaValidationError(
                    format!("NULL value for non-nullable column '{}'", column.name)
                ));
            }
            return Ok(());
        }

        let valid = match &column.data_type {
            DataType::Boolean => value.is_boolean(),
            DataType::Int8 | DataType::Int16 | DataType::Int32 | DataType::Int64 => {
                value.is_i64()
            }
            DataType::UInt8 | DataType::UInt16 | DataType::UInt32 | DataType::UInt64 => {
                value.is_u64()
            }
            DataType::Float32 | DataType::Float64 => value.is_f64(),
            DataType::String | DataType::Json | DataType::Uuid => value.is_string(),
            DataType::Binary => value.is_string(), // Base64エンコードされた文字列として
            DataType::Date | DataType::Timestamp => value.is_string(), // ISO 8601文字列として
        };

        if !valid {
            return Err(KawaDbError::DataTypeMismatch {
                expected: format!("{:?}", column.data_type),
                actual: format!("{:?}", value),
            });
        }

        Ok(())
    }
}

/// テーブルメタデータ
#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
struct TableMetadata {
    schema: TableSchema,
    created_at: chrono::DateTime<chrono::Utc>,
}

impl TableMetadata {
    fn new(schema: TableSchema) -> Self {
        Self {
            schema,
            created_at: chrono::Utc::now(),
        }
    }
}

/// テーブルイベント
#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
enum TableEvent {
    TableCreated {
        table_name: String,
        schema: TableSchema,
        timestamp: chrono::DateTime<chrono::Utc>,
    },
    DataInserted {
        table_name: String,
        data: Value,
        timestamp: chrono::DateTime<chrono::Utc>,
    },
    TableDropped {
        table_name: String,
        timestamp: chrono::DateTime<chrono::Utc>,
    },
}

#[cfg(test)]
mod tests {
    use super::*;
    use tempfile::TempDir;
    use kawa_storage::StorageConfig;

    async fn create_test_storage() -> (TempDir, Arc<StorageEngine>) {
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
        (temp_dir, storage)
    }

    #[tokio::test]
    async fn test_table_creation() {
        let (_temp_dir, storage) = create_test_storage().await;
        let manager = TableManager::new(storage).unwrap();

        let schema = TableSchema::new(
            "test_table".to_string(),
            vec![
                ColumnDefinition::new("id".to_string(), DataType::Int64, false),
                ColumnDefinition::new("name".to_string(), DataType::String, false),
            ],
        );

        manager.create_table(schema).await.unwrap();
        assert!(manager.table_exists("test_table").await.unwrap());
    }

    #[tokio::test]
    async fn test_data_insertion() {
        let (_temp_dir, storage) = create_test_storage().await;
        let manager = TableManager::new(storage).unwrap();

        let schema = TableSchema::new(
            "users".to_string(),
            vec![
                ColumnDefinition::new("id".to_string(), DataType::Int64, false),
                ColumnDefinition::new("name".to_string(), DataType::String, false),
            ],
        );

        manager.create_table(schema).await.unwrap();

        let data = vec![
            serde_json::json!({"id": 1, "name": "Alice"}),
            serde_json::json!({"id": 2, "name": "Bob"}),
        ];

        let count = manager.insert_data("users", data).await.unwrap();
        assert_eq!(count, 2);

        let retrieved = manager.read_table_data("users", None, None).await.unwrap();
        assert_eq!(retrieved.len(), 2);
    }
} 