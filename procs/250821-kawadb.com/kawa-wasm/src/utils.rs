//! Utility functions for WebAssembly
use wasm_bindgen::prelude::*;
use web_sys::console;
use js_sys::{Date, Math};

/// ログ出力関数
pub fn log_message(message: &str) {
    console::log_1(&message.into());
}

/// タイムスタンプを取得
pub fn get_timestamp() -> u64 {
    Date::now() as u64
}

/// ランダムなIDを生成
pub fn generate_id() -> String {
    let timestamp = Date::now() as u64;
    let random = (Math::random() * 1000000.0) as u64;
    format!("{}_{}", timestamp, random)
}

/// JSONデータをバリデート
pub fn validate_json(json_str: &str) -> bool {
    serde_json::from_str::<serde_json::Value>(json_str).is_ok()
}

/// コンソールログマクロ
#[macro_export]
macro_rules! console_log {
    ($($t:tt)*) => {
        web_sys::console::log_1(&format!($($t)*).into());
    }
}

/// エラーログマクロ
#[macro_export]
macro_rules! console_error {
    ($($t:tt)*) => {
        web_sys::console::error_1(&format!($($t)*).into());
    }
}

/// 警告ログマクロ
#[macro_export]
macro_rules! console_warn {
    ($($t:tt)*) => {
        web_sys::console::warn_1(&format!($($t)*).into());
    }
}

/// ランダムな16進数文字列を生成
pub fn generate_hex_string(length: usize) -> String {
    let chars = "0123456789abcdef".chars().collect::<Vec<char>>();
    (0..length)
        .map(|_| chars[(Math::random() * chars.len() as f64) as usize])
        .collect()
}

/// エラーをJavaScriptのエラーに変換
pub fn to_js_error(error: &str) -> JsValue {
    JsValue::from_str(error)
}

/// 現在の時刻を ISO 8601 形式で取得
pub fn get_iso_timestamp() -> String {
    let date = Date::new_0();
    date.to_iso_string().as_string().unwrap_or_else(|| "1970-01-01T00:00:00.000Z".to_string())
}

/// バイト配列を16進数文字列に変換
pub fn bytes_to_hex(bytes: &[u8]) -> String {
    bytes.iter()
        .map(|b| format!("{:02x}", b))
        .collect()
}

/// 16進数文字列をバイト配列に変換
pub fn hex_to_bytes(hex: &str) -> Result<Vec<u8>, String> {
    if hex.len() % 2 != 0 {
        return Err("Hex string must have even length".to_string());
    }
    
    (0..hex.len())
        .step_by(2)
        .map(|i| {
            u8::from_str_radix(&hex[i..i+2], 16)
                .map_err(|_| "Invalid hex character".to_string())
        })
        .collect()
} 