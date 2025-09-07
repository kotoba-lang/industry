//! Schema Definitions for KawaDB

use crate::error::{KawaDbError, KawaDbResult};
use arrow::datatypes::{DataType as ArrowDataType, Field, Schema};
use serde::{Deserialize, Serialize};

/// テーブルスキーマ
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct TableSchema {
    /// テーブル名
    pub name: String,
    
    /// カラム定義
    pub columns: Vec<ColumnDefinition>,
    
    /// プライマリキー（オプション）
    pub primary_key: Option<Vec<String>>,
    
    /// インデックス定義
    pub indexes: Vec<IndexDefinition>,
    
    /// パーティション設定
    pub partitioning: Option<PartitioningConfig>,
    
    /// テーブル作成時刻
    pub created_at: chrono::DateTime<chrono::Utc>,
    
    /// テーブル更新時刻
    pub updated_at: chrono::DateTime<chrono::Utc>,
}

impl TableSchema {
    /// 新しいテーブルスキーマを作成
    pub fn new(name: String, columns: Vec<ColumnDefinition>) -> Self {
        let now = chrono::Utc::now();
        Self {
            name,
            columns,
            primary_key: None,
            indexes: Vec::new(),
            partitioning: None,
            created_at: now,
            updated_at: now,
        }
    }

    /// プライマリキーを設定
    pub fn with_primary_key(mut self, primary_key: Vec<String>) -> KawaDbResult<Self> {
        // プライマリキーのカラムが存在するかチェック
        for key_col in &primary_key {
            if !self.columns.iter().any(|col| &col.name == key_col) {
                return Err(KawaDbError::ColumnNotFound(key_col.clone()));
            }
        }
        self.primary_key = Some(primary_key);
        Ok(self)
    }

    /// インデックスを追加
    pub fn add_index(mut self, index: IndexDefinition) -> KawaDbResult<Self> {
        // インデックスのカラムが存在するかチェック
        for col in &index.columns {
            if !self.columns.iter().any(|table_col| &table_col.name == col) {
                return Err(KawaDbError::ColumnNotFound(col.clone()));
            }
        }
        self.indexes.push(index);
        Ok(self)
    }

    /// Arrow Schemaに変換
    pub fn to_arrow_schema(&self) -> KawaDbResult<Schema> {
        let fields: Result<Vec<Field>, _> = self.columns
            .iter()
            .map(|col| col.to_arrow_field())
            .collect();
        
        let fields = fields?;
        Ok(Schema::new(fields))
    }

    /// カラム名でカラム定義を取得
    pub fn get_column(&self, name: &str) -> Option<&ColumnDefinition> {
        self.columns.iter().find(|col| col.name == name)
    }

    /// スキーマを検証
    pub fn validate(&self) -> KawaDbResult<()> {
        if self.name.is_empty() {
            return Err(KawaDbError::SchemaValidationError(
                "Table name cannot be empty".to_string()
            ));
        }

        if self.columns.is_empty() {
            return Err(KawaDbError::SchemaValidationError(
                "Table must have at least one column".to_string()
            ));
        }

        // カラム名の重複チェック
        let mut column_names = std::collections::HashSet::new();
        for column in &self.columns {
            if !column_names.insert(&column.name) {
                return Err(KawaDbError::SchemaValidationError(
                    format!("Duplicate column name: {}", column.name)
                ));
            }
        }

        // プライマリキーの検証
        if let Some(pk) = &self.primary_key {
            for key_col in pk {
                if !self.columns.iter().any(|col| &col.name == key_col) {
                    return Err(KawaDbError::SchemaValidationError(
                        format!("Primary key column '{}' not found", key_col)
                    ));
                }
            }
        }

        Ok(())
    }
}

/// カラム定義
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct ColumnDefinition {
    /// カラム名
    pub name: String,
    
    /// データ型
    pub data_type: DataType,
    
    /// NULL許可
    pub nullable: bool,
    
    /// デフォルト値
    pub default_value: Option<DefaultValue>,
    
    /// カラムコメント
    pub comment: Option<String>,
}

impl ColumnDefinition {
    /// 新しいカラム定義を作成
    pub fn new(name: String, data_type: DataType, nullable: bool) -> Self {
        Self {
            name,
            data_type,
            nullable,
            default_value: None,
            comment: None,
        }
    }

    /// デフォルト値を設定
    pub fn with_default(mut self, default: DefaultValue) -> Self {
        self.default_value = Some(default);
        self
    }

    /// コメントを設定
    pub fn with_comment(mut self, comment: String) -> Self {
        self.comment = Some(comment);
        self
    }

    /// Arrow Fieldに変換
    pub fn to_arrow_field(&self) -> KawaDbResult<Field> {
        let arrow_type = self.data_type.to_arrow_type()?;
        Ok(Field::new(&self.name, arrow_type, self.nullable))
    }
}

/// データ型
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub enum DataType {
    Boolean,
    Int8,
    Int16,
    Int32,
    Int64,
    UInt8,
    UInt16,
    UInt32,
    UInt64,
    Float32,
    Float64,
    String,
    Binary,
    Date,
    Timestamp,
    Json,
    Uuid,
}

impl DataType {
    /// Arrow DataTypeに変換
    pub fn to_arrow_type(&self) -> KawaDbResult<ArrowDataType> {
        let arrow_type = match self {
            DataType::Boolean => ArrowDataType::Boolean,
            DataType::Int8 => ArrowDataType::Int8,
            DataType::Int16 => ArrowDataType::Int16,
            DataType::Int32 => ArrowDataType::Int32,
            DataType::Int64 => ArrowDataType::Int64,
            DataType::UInt8 => ArrowDataType::UInt8,
            DataType::UInt16 => ArrowDataType::UInt16,
            DataType::UInt32 => ArrowDataType::UInt32,
            DataType::UInt64 => ArrowDataType::UInt64,
            DataType::Float32 => ArrowDataType::Float32,
            DataType::Float64 => ArrowDataType::Float64,
            DataType::String => ArrowDataType::Utf8,
            DataType::Binary => ArrowDataType::Binary,
            DataType::Date => ArrowDataType::Date32,
            DataType::Timestamp => ArrowDataType::Timestamp(
                arrow::datatypes::TimeUnit::Microsecond, None
            ),
            DataType::Json => ArrowDataType::Utf8, // JSONは文字列として保存
            DataType::Uuid => ArrowDataType::Utf8, // UUIDは文字列として保存
        };
        Ok(arrow_type)
    }
}

/// デフォルト値
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub enum DefaultValue {
    Null,
    Boolean(bool),
    Integer(i64),
    Float(f64),
    String(String),
    CurrentTimestamp,
    NewUuid,
}

/// インデックス定義
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct IndexDefinition {
    /// インデックス名
    pub name: String,
    
    /// インデックス対象カラム
    pub columns: Vec<String>,
    
    /// インデックス種別
    pub index_type: IndexType,
    
    /// ユニーク制約
    pub unique: bool,
}

/// インデックス種別
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub enum IndexType {
    /// B-Treeインデックス
    BTree,
    
    /// ハッシュインデックス
    Hash,
    
    /// 全文検索インデックス
    FullText,
}

/// パーティション設定
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct PartitioningConfig {
    /// パーティション種別
    pub partition_type: PartitionType,
    
    /// パーティション数
    pub partition_count: usize,
    
    /// パーティションキー
    pub partition_key: Vec<String>,
}

/// パーティション種別
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub enum PartitionType {
    /// ハッシュパーティション
    Hash,
    
    /// レンジパーティション
    Range,
    
    /// リストパーティション
    List,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_table_schema_creation() {
        let columns = vec![
            ColumnDefinition::new("id".to_string(), DataType::Int64, false),
            ColumnDefinition::new("name".to_string(), DataType::String, false),
            ColumnDefinition::new("age".to_string(), DataType::Int32, true),
        ];

        let schema = TableSchema::new("users".to_string(), columns);
        assert_eq!(schema.name, "users");
        assert_eq!(schema.columns.len(), 3);
        assert!(schema.primary_key.is_none());
    }

    #[test]
    fn test_schema_with_primary_key() {
        let columns = vec![
            ColumnDefinition::new("id".to_string(), DataType::Int64, false),
            ColumnDefinition::new("name".to_string(), DataType::String, false),
        ];

        let schema = TableSchema::new("users".to_string(), columns)
            .with_primary_key(vec!["id".to_string()])
            .unwrap();

        assert_eq!(schema.primary_key, Some(vec!["id".to_string()]));
    }

    #[test]
    fn test_schema_validation() {
        let columns = vec![
            ColumnDefinition::new("id".to_string(), DataType::Int64, false),
            ColumnDefinition::new("name".to_string(), DataType::String, false),
        ];

        let schema = TableSchema::new("users".to_string(), columns);
        assert!(schema.validate().is_ok());

        // 空のテーブル名
        let schema = TableSchema::new("".to_string(), vec![]);
        assert!(schema.validate().is_err());
    }

    #[test]
    fn test_arrow_conversion() {
        let column = ColumnDefinition::new("test".to_string(), DataType::String, true);
        let field = column.to_arrow_field().unwrap();
        
        assert_eq!(field.name(), "test");
        assert_eq!(field.data_type(), &ArrowDataType::Utf8);
        assert!(field.is_nullable());
    }
} 