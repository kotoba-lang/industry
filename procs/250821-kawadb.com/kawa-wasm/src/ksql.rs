//! KSQL streaming query engine for WebAssembly
use wasm_bindgen::prelude::*;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use js_sys::Date;

/// KSQL結果
#[wasm_bindgen(getter_with_clone)]
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct WasmKsqlResult {
    success: bool,
    data: String,
    query_id: Option<String>,
    error_message: Option<String>,
    query_type: String,
}

#[wasm_bindgen]
impl WasmKsqlResult {
    /// 成功フラグを取得
    #[wasm_bindgen(getter)]
    pub fn success(&self) -> bool {
        self.success
    }

    /// データを取得
    #[wasm_bindgen(getter)]
    pub fn data(&self) -> String {
        self.data.clone()
    }

    /// クエリIDを取得
    #[wasm_bindgen(getter)]
    pub fn query_id(&self) -> Option<String> {
        self.query_id.clone()
    }

    /// エラーメッセージを取得
    #[wasm_bindgen(getter)]
    pub fn error_message(&self) -> Option<String> {
        self.error_message.clone()
    }

    /// クエリタイプを取得
    #[wasm_bindgen(getter)]
    pub fn query_type(&self) -> String {
        self.query_type.clone()
    }
}

/// 継続的クエリ
#[wasm_bindgen(getter_with_clone)]
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct WasmContinuousQuery {
    id: String,
    name: String,
    ksql: String,
    status: String,
    messages_processed: u64,
    results_produced: u64,
    created_at: u64,
    last_error: Option<String>,
    input_sources: Vec<String>,
    output_target: String,
    processing_interval_ms: u64,
}

#[wasm_bindgen]
impl WasmContinuousQuery {
    /// クエリIDを取得
    #[wasm_bindgen(getter)]
    pub fn id(&self) -> String {
        self.id.clone()
    }

    /// クエリ名を取得
    #[wasm_bindgen(getter)]
    pub fn name(&self) -> String {
        self.name.clone()
    }

    /// KSQL文を取得
    #[wasm_bindgen(getter)]
    pub fn ksql(&self) -> String {
        self.ksql.clone()
    }

    /// 状態を取得
    #[wasm_bindgen(getter)]
    pub fn status(&self) -> String {
        self.status.clone()
    }

    /// 処理したメッセージ数を取得
    #[wasm_bindgen(getter)]
    pub fn messages_processed(&self) -> u64 {
        self.messages_processed
    }

    /// 生成した結果数を取得
    #[wasm_bindgen(getter)]
    pub fn results_produced(&self) -> u64 {
        self.results_produced
    }

    /// 作成日時を取得
    #[wasm_bindgen(getter)]
    pub fn created_at(&self) -> u64 {
        self.created_at
    }

    /// 最後のエラーを取得
    #[wasm_bindgen(getter)]
    pub fn last_error(&self) -> Option<String> {
        self.last_error.clone()
    }

    /// 入力ソース一覧を取得
    pub fn get_input_sources(&self) -> String {
        serde_json::to_string(&self.input_sources).unwrap_or_else(|_| "[]".to_string())
    }

    /// 出力ターゲットを取得
    #[wasm_bindgen(getter)]
    pub fn output_target(&self) -> String {
        self.output_target.clone()
    }

    /// 処理間隔を取得
    #[wasm_bindgen(getter)]
    pub fn processing_interval_ms(&self) -> u64 {
        self.processing_interval_ms
    }
}

/// ストリーミング結果
#[wasm_bindgen(getter_with_clone)]
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct WasmStreamingResult {
    query_id: String,
    data: String,
    timestamp: u64,
    metadata: String,
}

#[wasm_bindgen]
impl WasmStreamingResult {
    /// クエリIDを取得
    #[wasm_bindgen(getter)]
    pub fn query_id(&self) -> String {
        self.query_id.clone()
    }

    /// データを取得
    #[wasm_bindgen(getter)]
    pub fn data(&self) -> String {
        self.data.clone()
    }

    /// タイムスタンプを取得
    #[wasm_bindgen(getter)]
    pub fn timestamp(&self) -> u64 {
        self.timestamp
    }

    /// メタデータを取得
    #[wasm_bindgen(getter)]
    pub fn metadata(&self) -> String {
        self.metadata.clone()
    }
}

/// KSQLエンジン
#[wasm_bindgen]
pub struct WasmKsqlEngine {
    continuous_queries: HashMap<String, WasmContinuousQuery>,
    streaming_data: HashMap<String, Vec<String>>,
    result_streams: HashMap<String, Vec<WasmStreamingResult>>,
}

#[wasm_bindgen]
impl WasmKsqlEngine {
    /// 新しいKSQLエンジンを作成
    #[wasm_bindgen(constructor)]
    pub fn new() -> WasmKsqlEngine {
        WasmKsqlEngine {
            continuous_queries: HashMap::new(),
            streaming_data: HashMap::new(),
            result_streams: HashMap::new(),
        }
    }

    /// KSQLクエリを実行
    pub fn execute_ksql(&mut self, ksql: &str) -> WasmKsqlResult {
        let ksql_lower = ksql.trim().to_lowercase();
        
        if ksql_lower.starts_with("create stream") {
            self.handle_create_stream(ksql)
        } else if ksql_lower.starts_with("show streams") {
            self.handle_show_streams()
        } else if ksql_lower.starts_with("select") {
            self.handle_select(ksql)
        } else {
            WasmKsqlResult {
                success: false,
                data: "{}".to_string(),
                query_id: None,
                error_message: Some(format!("Unsupported KSQL: {}", ksql)),
                query_type: "ERROR".to_string(),
            }
        }
    }

    fn handle_create_stream(&mut self, ksql: &str) -> WasmKsqlResult {
        let query_id = format!("query_{}", Date::now() as u64);
        let query = WasmContinuousQuery {
            id: query_id.clone(),
            name: "CREATE_STREAM".to_string(),
            ksql: ksql.to_string(),
            status: "RUNNING".to_string(),
            messages_processed: 0,
            results_produced: 0,
            created_at: Date::now() as u64,
            last_error: None,
            input_sources: vec!["default".to_string()],
            output_target: "output".to_string(),
            processing_interval_ms: 1000,
        };

        self.continuous_queries.insert(query_id.clone(), query);

        WasmKsqlResult {
            success: true,
            data: format!("{{\"query_id\": \"{}\"}}", query_id),
            query_id: Some(query_id),
            error_message: None,
            query_type: "CREATE_STREAM".to_string(),
        }
    }

    fn handle_show_streams(&self) -> WasmKsqlResult {
        let queries: Vec<_> = self.continuous_queries.values().collect();
        let data = serde_json::to_string(&queries).unwrap_or_else(|_| "[]".to_string());
        
        WasmKsqlResult {
            success: true,
            data,
            query_id: None,
            error_message: None,
            query_type: "SHOW_STREAMS".to_string(),
        }
    }

    fn handle_select(&self, ksql: &str) -> WasmKsqlResult {
        WasmKsqlResult {
            success: true,
            data: format!("{{\"query\": \"{}\", \"results\": []}}", ksql),
            query_id: None,
            error_message: None,
            query_type: "SELECT".to_string(),
        }
    }

    /// ストリーミングデータを追加
    pub fn add_streaming_data(&mut self, source_name: &str, data: &str) -> bool {
        self.streaming_data
            .entry(source_name.to_string())
            .or_insert_with(Vec::new)
            .push(data.to_string());
        true
    }

    /// 継続的クエリのリストを取得
    pub fn list_continuous_queries(&self) -> String {
        let queries: Vec<_> = self.continuous_queries.values().collect();
        serde_json::to_string(&queries).unwrap_or_else(|_| "[]".to_string())
    }

    /// 継続的クエリを停止
    pub fn stop_continuous_query(&mut self, query_id: &str) -> bool {
        if let Some(query) = self.continuous_queries.get_mut(query_id) {
            query.status = "STOPPED".to_string();
            true
        } else {
            false
        }
    }

    /// 継続的クエリを開始
    pub fn start_continuous_query(&mut self, query_id: &str) -> bool {
        if let Some(query) = self.continuous_queries.get_mut(query_id) {
            query.status = "RUNNING".to_string();
            true
        } else {
            false
        }
    }

    /// 結果ストリームを取得
    pub fn get_result_stream(&self, query_id: &str) -> String {
        if let Some(results) = self.result_streams.get(query_id) {
            serde_json::to_string(results).unwrap_or_else(|_| "[]".to_string())
        } else {
            "[]".to_string()
        }
    }

    /// 結果ストリームをクリア
    pub fn clear_result_stream(&mut self, query_id: &str) -> bool {
        self.result_streams.remove(query_id).is_some()
    }

    /// 全てのストリーミングデータを取得
    pub fn get_all_streaming_data(&self) -> String {
        serde_json::to_string(&self.streaming_data).unwrap_or_else(|_| "{}".to_string())
    }

    /// ストリーミングデータをクリア
    pub fn clear_streaming_data(&mut self, source_name: &str) -> bool {
        self.streaming_data.remove(source_name).is_some()
    }

    /// 統計情報を取得
    pub fn get_statistics(&self) -> String {
        let stats = serde_json::json!({
            "total_queries": self.continuous_queries.len(),
            "total_data_sources": self.streaming_data.len(),
            "total_results": self.result_streams.len()
        });
        stats.to_string()
    }
} 