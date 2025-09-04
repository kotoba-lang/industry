//! Database operations for WebAssembly
use wasm_bindgen::prelude::*;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;

// ローカルに必要な型を定義
#[wasm_bindgen]
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DbConfig {
    pub max_connections: usize,
    pub timeout_ms: u64,
    pub debug_mode: bool,
}

#[wasm_bindgen]
impl DbConfig {
    #[wasm_bindgen(constructor)]
    pub fn new() -> Self {
        Self {
            max_connections: 10,
            timeout_ms: 5000,
            debug_mode: false,
        }
    }
}

#[wasm_bindgen(getter_with_clone)]
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TableSchema {
    pub name: String,
    pub columns: Vec<ColumnDefinition>,
}

#[wasm_bindgen(getter_with_clone)]
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ColumnDefinition {
    pub name: String,
    pub data_type: DataType,
    pub nullable: bool,
}

#[wasm_bindgen]
#[derive(Debug, Clone, Copy, Serialize, Deserialize)]
pub enum DataType {
    String,
    Integer,
    Float,
    Boolean,
    Timestamp,
    Json,
}

#[wasm_bindgen(getter_with_clone)]
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DBQueryResult {
    pub success: bool,
    pub data: String,
    pub rows_affected: usize,
    pub error_message: Option<String>,
}

/// WebAssembly用データベースエンジン
#[wasm_bindgen]
#[derive(Debug)]
#[allow(dead_code)]
pub struct DatabaseEngine {
    config: DbConfig,
    tables: HashMap<String, TableSchema>,
}

#[wasm_bindgen]
impl DatabaseEngine {
    #[wasm_bindgen(constructor)]
    pub fn new(config: DbConfig) -> Self {
        Self {
            config,
            tables: HashMap::new(),
        }
    }

    #[wasm_bindgen(js_name = executeQuery)]
    pub fn execute_query(&self, query: &str) -> DBQueryResult {
        DBQueryResult {
            success: true,
            data: format!("Query executed: {}", query),
            rows_affected: 0,
            error_message: None,
        }
    }

    pub fn create_table(&mut self, schema: TableSchema) -> Result<(), String> {
        self.tables.insert(schema.name.clone(), schema);
        Ok(())
    }
} 