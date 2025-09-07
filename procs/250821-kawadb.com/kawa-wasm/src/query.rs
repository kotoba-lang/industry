//! WASM Query Processing

use wasm_bindgen::prelude::*;

/// SQLクエリの種類
#[wasm_bindgen]
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum QueryType {
    Select,
    Insert,
    Update,
    Delete,
    CreateTable,
    DropTable,
    ShowTables,
    Unknown,
}

/// クエリ解析器
#[wasm_bindgen]
pub struct QueryParser;

#[wasm_bindgen]
impl QueryParser {
    /// 新しいクエリ解析器を作成
    #[wasm_bindgen(constructor)]
    pub fn new() -> QueryParser {
        QueryParser
    }

    /// SQLクエリのタイプを判定
    pub fn parse_query_type(&self, sql: &str) -> QueryType {
        let sql_lower = sql.trim().to_lowercase();
        
        if sql_lower.starts_with("select") {
            QueryType::Select
        } else if sql_lower.starts_with("insert") {
            QueryType::Insert
        } else if sql_lower.starts_with("update") {
            QueryType::Update
        } else if sql_lower.starts_with("delete") {
            QueryType::Delete
        } else if sql_lower.starts_with("create table") {
            QueryType::CreateTable
        } else if sql_lower.starts_with("drop table") {
            QueryType::DropTable
        } else if sql_lower == "show tables" {
            QueryType::ShowTables
        } else {
            QueryType::Unknown
        }
    }

    /// SQLクエリを検証
    pub fn validate_sql(&self, sql: &str) -> bool {
        if sql.trim().is_empty() {
            return false;
        }

        // 基本的な検証
        let sql_lower = sql.trim().to_lowercase();
        
        // 危険なキーワードをチェック
        let dangerous_keywords = ["drop database", "drop schema", "truncate"];
        for keyword in dangerous_keywords {
            if sql_lower.contains(keyword) {
                return false;
            }
        }

        // 基本的なSQL構文チェック
        matches!(self.parse_query_type(sql), 
                QueryType::Select | QueryType::Insert | QueryType::Update | 
                QueryType::Delete | QueryType::CreateTable | QueryType::DropTable | 
                QueryType::ShowTables)
    }

    /// テーブル名を抽出（簡易版）
    pub fn extract_table_name(&self, sql: &str) -> Option<String> {
        let sql_lower = sql.trim().to_lowercase();
        let words: Vec<&str> = sql_lower.split_whitespace().collect();

        match self.parse_query_type(sql) {
            QueryType::Select => {
                // SELECT ... FROM table_name
                if let Some(from_idx) = words.iter().position(|&w| w == "from") {
                    if from_idx + 1 < words.len() {
                        return Some(words[from_idx + 1].to_string());
                    }
                }
            }
            QueryType::Insert => {
                // INSERT INTO table_name
                if words.len() >= 3 && words[0] == "insert" && words[1] == "into" {
                    return Some(words[2].to_string());
                }
            }
            QueryType::Update => {
                // UPDATE table_name
                if words.len() >= 2 && words[0] == "update" {
                    return Some(words[1].to_string());
                }
            }
            QueryType::Delete => {
                // DELETE FROM table_name
                if let Some(from_idx) = words.iter().position(|&w| w == "from") {
                    if from_idx + 1 < words.len() {
                        return Some(words[from_idx + 1].to_string());
                    }
                }
            }
            QueryType::CreateTable => {
                // CREATE TABLE table_name
                if words.len() >= 3 && words[0] == "create" && words[1] == "table" {
                    return Some(words[2].to_string());
                }
            }
            QueryType::DropTable => {
                // DROP TABLE table_name
                if words.len() >= 3 && words[0] == "drop" && words[1] == "table" {
                    return Some(words[2].to_string());
                }
            }
            _ => {}
        }

        None
    }
}

impl Default for QueryParser {
    fn default() -> Self {
        Self::new()
    }
}

/// クエリ統計
#[wasm_bindgen]
pub struct QueryStats {
    total_queries: u32,
    successful_queries: u32,
    failed_queries: u32,
    total_execution_time: f64,
}

#[wasm_bindgen]
impl QueryStats {
    /// 新しい統計インスタンスを作成
    #[wasm_bindgen(constructor)]
    pub fn new() -> QueryStats {
        QueryStats {
            total_queries: 0,
            successful_queries: 0,
            failed_queries: 0,
            total_execution_time: 0.0,
        }
    }

    /// クエリ成功を記録
    pub fn record_success(&mut self, execution_time: f64) {
        self.total_queries += 1;
        self.successful_queries += 1;
        self.total_execution_time += execution_time;
    }

    /// クエリ失敗を記録
    pub fn record_failure(&mut self, execution_time: f64) {
        self.total_queries += 1;
        self.failed_queries += 1;
        self.total_execution_time += execution_time;
    }

    /// 総クエリ数を取得
    #[wasm_bindgen(getter)]
    pub fn total_queries(&self) -> u32 {
        self.total_queries
    }

    /// 成功クエリ数を取得
    #[wasm_bindgen(getter)]
    pub fn successful_queries(&self) -> u32 {
        self.successful_queries
    }

    /// 失敗クエリ数を取得
    #[wasm_bindgen(getter)]
    pub fn failed_queries(&self) -> u32 {
        self.failed_queries
    }

    /// 平均実行時間を取得
    pub fn average_execution_time(&self) -> f64 {
        if self.total_queries > 0 {
            self.total_execution_time / self.total_queries as f64
        } else {
            0.0
        }
    }

    /// 成功率を取得
    pub fn success_rate(&self) -> f64 {
        if self.total_queries > 0 {
            (self.successful_queries as f64 / self.total_queries as f64) * 100.0
        } else {
            0.0
        }
    }

    /// 統計をリセット
    pub fn reset(&mut self) {
        self.total_queries = 0;
        self.successful_queries = 0;
        self.failed_queries = 0;
        self.total_execution_time = 0.0;
    }

    /// 統計をJSON文字列として取得
    pub fn to_json(&self) -> String {
        serde_json::json!({
            "total_queries": self.total_queries,
            "successful_queries": self.successful_queries,
            "failed_queries": self.failed_queries,
            "total_execution_time": self.total_execution_time,
            "average_execution_time": self.average_execution_time(),
            "success_rate": self.success_rate()
        }).to_string()
    }
}

impl Default for QueryStats {
    fn default() -> Self {
        Self::new()
    }
}

#[cfg(test)]
#[allow(dead_code, unused_variables)]
mod tests {
    use super::*;
    use wasm_bindgen_test::*;

    // wasm_bindgen_test_configure!(run_in_browser);

    #[wasm_bindgen_test]
    fn test_query_parser() {
        let parser = QueryParser::new();
        
        assert!(matches!(parser.parse_query_type("SELECT * FROM users"), QueryType::Select));
        assert!(matches!(parser.parse_query_type("INSERT INTO users VALUES (1, 'test')"), QueryType::Insert));
        assert!(matches!(parser.parse_query_type("SHOW TABLES"), QueryType::ShowTables));
    }

    #[wasm_bindgen_test]
    fn test_table_extraction() {
        let parser = QueryParser::new();
        
        assert_eq!(parser.extract_table_name("SELECT * FROM users"), Some("users".to_string()));
        assert_eq!(parser.extract_table_name("INSERT INTO products VALUES (1, 'test')"), Some("products".to_string()));
        assert_eq!(parser.extract_table_name("DROP TABLE old_data"), Some("old_data".to_string()));
    }

    #[wasm_bindgen_test]
    fn test_sql_validation() {
        let parser = QueryParser::new();
        
        assert!(parser.validate_sql("SELECT * FROM users"));
        assert!(parser.validate_sql("SHOW TABLES"));
        assert!(!parser.validate_sql(""));
        assert!(!parser.validate_sql("DROP DATABASE test"));
    }

    #[wasm_bindgen_test]
    fn test_query_stats() {
        let mut stats = QueryStats::new();
        
        stats.record_success(100.0);
        stats.record_failure(50.0);
        
        assert_eq!(stats.total_queries(), 2);
        assert_eq!(stats.successful_queries(), 1);
        assert_eq!(stats.failed_queries(), 1);
        assert_eq!(stats.success_rate(), 50.0);
    }
} 