//! Storage Adapter for integrating kawa-storage with DataFusion

use crate::{
    error::{KawaDbError, KawaDbResult},
};
use datafusion::{
    arrow::{
        array::{ArrayRef, StringArray, Int64Array},
        datatypes::{SchemaRef, DataType as ArrowDataType},
        record_batch::RecordBatch,
    },
    common::DataFusionError,
    physical_plan::{
        empty::EmptyExec,
        ExecutionPlan,
    },
    catalog::TableProvider,
    logical_expr::{Expr, TableType},
};
use kawa_storage::{Topic, Partition};
use async_trait::async_trait;
use std::sync::Arc;

/// KawaDB用のDataFusionテーブルプロバイダー
#[derive(Debug)]
pub struct KawaTableProvider {
    #[allow(dead_code)]
    storage: Arc<kawa_storage::StorageEngine>,
    #[allow(dead_code)]
    topic: String,
    schema: SchemaRef,
}

impl KawaTableProvider {
    /// 新しいテーブルプロバイダーを作成
    pub fn new(
        storage: Arc<kawa_storage::StorageEngine>,
        topic: String,
        schema: SchemaRef,
    ) -> Self {
        Self {
            storage,
            topic,
            schema,
        }
    }

    /// テーブルデータを読み取りRecordBatchに変換
    #[allow(dead_code)]
    async fn load_data(&self) -> KawaDbResult<Vec<RecordBatch>> {
        let data_topic = Topic::new(&format!("table_{}", self.topic));
        let partition = Partition::new(0);
        let start_offset = kawa_storage::Offset::new(0);
        let limit = 1000; // 制限を設定
        
        let events = self.storage.read_events(&data_topic, partition, start_offset, limit).await
            .map_err(|e| KawaDbError::StorageError(e.to_string()))?;
        
        // イベントをJSONとして解析
        let mut rows: Vec<serde_json::Value> = Vec::new();
        for event in events {
            let json_data = serde_json::from_slice(event.data.as_bytes())
                .unwrap_or_else(|_| serde_json::json!({"raw_data": String::from_utf8_lossy(event.data.as_bytes())}));
            rows.push(json_data);
        }
        
        // RecordBatchに変換
        if rows.is_empty() {
            return Ok(vec![]);
        }
        
        let mut columns: Vec<ArrayRef> = Vec::new();
        
        // 各カラムのデータを抽出
        for field in self.schema.fields() {
            let column_data = self.extract_column_data(&rows, field.name())?;
            let array = self.create_arrow_array(column_data, field.data_type())?;
            columns.push(array);
        }
        
        let batch = RecordBatch::try_new(self.schema.clone(), columns)
            .map_err(|e| KawaDbError::DataFusionError(e.to_string()))?;
        
        Ok(vec![batch])
    }
    
    /// 指定されたカラムのデータを抽出
    #[allow(dead_code)]
    fn extract_column_data(&self, rows: &[serde_json::Value], column_name: &str) -> KawaDbResult<Vec<String>> {
        let mut column_data = Vec::new();
        
        for row in rows {
            let value = if let Some(val) = row.get(column_name) {
                val.to_string()
            } else {
                "null".to_string()
            };
            column_data.push(value);
        }
        
        Ok(column_data)
    }
    
    /// ArrowのArrayRefを作成
    #[allow(dead_code)]
    fn create_arrow_array(&self, data: Vec<String>, data_type: &ArrowDataType) -> KawaDbResult<ArrayRef> {
        match data_type {
            ArrowDataType::Utf8 => {
                let array = StringArray::from(data);
                Ok(Arc::new(array))
            }
            ArrowDataType::Int64 => {
                let int_data: Result<Vec<i64>, _> = data.iter()
                    .map(|s| s.parse::<i64>())
                    .collect();
                
                match int_data {
                    Ok(values) => {
                        let array = Int64Array::from(values);
                        Ok(Arc::new(array))
                    }
                    Err(_) => {
                        // パースに失敗した場合は文字列として扱う
                        let array = StringArray::from(data);
                        Ok(Arc::new(array))
                    }
                }
            }
            _ => {
                // その他の型は文字列として扱う
                let array = StringArray::from(data);
                Ok(Arc::new(array))
            }
        }
    }
}

#[async_trait]
impl TableProvider for KawaTableProvider {
    /// テーブルのスキーマを返す
    fn as_any(&self) -> &dyn std::any::Any {
        self
    }
    
    /// テーブルスキーマを取得
    fn schema(&self) -> SchemaRef {
        self.schema.clone()
    }
    
    /// テーブルタイプを取得
    fn table_type(&self) -> TableType {
        TableType::Base
    }
    
    /// テーブルスキャンを実行
    async fn scan(
        &self,
        _state: &dyn datafusion::catalog::Session,
        _projection: Option<&Vec<usize>>,
        _filters: &[Expr],
        _limit: Option<usize>,
    ) -> Result<Arc<dyn ExecutionPlan>, DataFusionError> {
        // TODO: 実際のスキャン処理を実装
        // 現在は空のテーブルを返す
        Ok(Arc::new(EmptyExec::new(self.schema.clone())))
    }
} 