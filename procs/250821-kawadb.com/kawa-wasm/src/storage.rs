//! WASM Storage Interface

use wasm_bindgen::prelude::*;
use std::collections::HashMap;
use std::sync::Mutex;
use once_cell::sync::Lazy;

/// メモリストレージの実体
static MEMORY_STORAGE: Lazy<Mutex<HashMap<String, String>>> = Lazy::new(|| Mutex::new(HashMap::new()));

/// WASM環境でのストレージ抽象化
#[wasm_bindgen]
pub struct WasmStorage {
    storage_type: StorageType,
}

/// ストレージタイプ
#[wasm_bindgen]
#[derive(Debug, Clone)]
pub enum StorageType {
    LocalStorage,
    IndexedDB,
    Memory,
}

/// ストレージ統計
#[wasm_bindgen]
pub struct StorageStats {
    total_size: u64,
    used_size: u64,
    table_count: u32,
}

#[wasm_bindgen]
impl WasmStorage {
    /// 新しいストレージインスタンスを作成
    #[wasm_bindgen(constructor)]
    pub fn new(storage_type: StorageType) -> Result<WasmStorage, JsValue> {
        Ok(WasmStorage { storage_type })
    }

    /// データを保存
    pub fn set_item(&self, key: &str, value: &str) -> Result<bool, JsValue> {
        match self.storage_type {
            StorageType::LocalStorage => self.set_local_storage_item(key, value),
            StorageType::Memory => {
                let mut storage = MEMORY_STORAGE.lock().unwrap();
                storage.insert(key.to_string(), value.to_string());
                Ok(true)
            }
            StorageType::IndexedDB => {
                // IndexedDBは将来実装
                Err(JsValue::from_str("IndexedDB not yet implemented"))
            }
        }
    }

    /// データを取得
    pub fn get_item(&self, key: &str) -> Result<Option<String>, JsValue> {
        match self.storage_type {
            StorageType::LocalStorage => self.get_local_storage_item(key),
            StorageType::Memory => {
                let storage = MEMORY_STORAGE.lock().unwrap();
                Ok(storage.get(key).cloned())
            }
            StorageType::IndexedDB => {
                // IndexedDBは将来実装
                Err(JsValue::from_str("IndexedDB not yet implemented"))
            }
        }
    }

    /// データを削除
    pub fn remove_item(&self, key: &str) -> Result<bool, JsValue> {
        match self.storage_type {
            StorageType::LocalStorage => self.remove_local_storage_item(key),
            StorageType::Memory => {
                let mut storage = MEMORY_STORAGE.lock().unwrap();
                Ok(storage.remove(key).is_some())
            }
            StorageType::IndexedDB => {
                // IndexedDBは将来実装
                Err(JsValue::from_str("IndexedDB not yet implemented"))
            }
        }
    }

    /// 全てのキーを取得
    pub fn keys(&self) -> Result<Vec<String>, JsValue> {
        match self.storage_type {
            StorageType::LocalStorage => self.get_local_storage_keys(),
            StorageType::Memory => {
                let storage = MEMORY_STORAGE.lock().unwrap();
                Ok(storage.keys().cloned().collect())
            }
            StorageType::IndexedDB => {
                // IndexedDBは将来実装
                Err(JsValue::from_str("IndexedDB not yet implemented"))
            }
        }
    }

    /// ストレージをクリア
    pub fn clear(&self) -> Result<bool, JsValue> {
        match self.storage_type {
            StorageType::LocalStorage => {
                if let Some(window) = web_sys::window() {
                    if let Ok(Some(storage)) = window.local_storage() {
                        storage.clear().map_err(|_| JsValue::from_str("Failed to clear storage"))?;
                        return Ok(true);
                    }
                }
                Ok(false)
            }
            StorageType::Memory => {
                let mut storage = MEMORY_STORAGE.lock().unwrap();
                storage.clear();
                Ok(true)
            }
            StorageType::IndexedDB => {
                // IndexedDBは将来実装
                Err(JsValue::from_str("IndexedDB not yet implemented"))
            }
        }
    }

    /// ストレージサイズを取得
    pub fn size(&self) -> Result<u64, JsValue> {
        match self.storage_type {
            StorageType::LocalStorage => {
                let mut total_size = 0u64;
                let keys = self.keys()?;
                
                for key in keys {
                    if let Ok(Some(value)) = self.get_item(&key) {
                        total_size += key.len() as u64 + value.len() as u64;
                    }
                }
                
                Ok(total_size)
            }
            StorageType::Memory => {
                let storage = MEMORY_STORAGE.lock().unwrap();
                let total_size = storage.iter().map(|(k, v)| k.len() + v.len()).sum::<usize>() as u64;
                Ok(total_size)
            }
            StorageType::IndexedDB => {
                // IndexedDBは将来実装
                Err(JsValue::from_str("IndexedDB not yet implemented"))
            }
        }
    }

    /// ストレージ統計を取得
    pub fn get_stats(&self) -> Result<StorageStats, JsValue> {
        let used_size = self.size()?;
        let keys = self.keys()?;
        
        Ok(StorageStats {
            total_size: 10 * 1024 * 1024, // 10MB仮想容量
            used_size,
            table_count: keys.iter().filter(|k| k.starts_with("table_")).count() as u32,
        })
    }

    // LocalStorage専用メソッド
    fn set_local_storage_item(&self, key: &str, value: &str) -> Result<bool, JsValue> {
        if let Some(window) = web_sys::window() {
            if let Ok(Some(storage)) = window.local_storage() {
                storage.set_item(key, value)
                    .map_err(|_| JsValue::from_str("Failed to set item"))?;
                return Ok(true);
            }
        }
        Ok(false)
    }

    fn get_local_storage_item(&self, key: &str) -> Result<Option<String>, JsValue> {
        if let Some(window) = web_sys::window() {
            if let Ok(Some(storage)) = window.local_storage() {
                return storage.get_item(key)
                    .map_err(|_| JsValue::from_str("Failed to get item"));
            }
        }
        Ok(None)
    }

    fn remove_local_storage_item(&self, key: &str) -> Result<bool, JsValue> {
        if let Some(window) = web_sys::window() {
            if let Ok(Some(storage)) = window.local_storage() {
                storage.remove_item(key)
                    .map_err(|_| JsValue::from_str("Failed to remove item"))?;
                return Ok(true);
            }
        }
        Ok(false)
    }

    fn get_local_storage_keys(&self) -> Result<Vec<String>, JsValue> {
        let mut keys = Vec::new();
        
        if let Some(window) = web_sys::window() {
            if let Ok(Some(storage)) = window.local_storage() {
                let length = storage.length()
                    .map_err(|_| JsValue::from_str("Failed to get storage length"))?;
                
                for i in 0..length {
                    if let Ok(Some(key)) = storage.key(i) {
                        keys.push(key);
                    }
                }
            }
        }
        
        Ok(keys)
    }
}

#[wasm_bindgen]
impl StorageStats {
    /// 総容量を取得
    #[wasm_bindgen(getter)]
    pub fn total_size(&self) -> u64 {
        self.total_size
    }

    /// 使用容量を取得
    #[wasm_bindgen(getter)]
    pub fn used_size(&self) -> u64 {
        self.used_size
    }

    /// テーブル数を取得
    #[wasm_bindgen(getter)]
    pub fn table_count(&self) -> u32 {
        self.table_count
    }

    /// 使用率を取得
    pub fn usage_percentage(&self) -> f64 {
        if self.total_size > 0 {
            (self.used_size as f64 / self.total_size as f64) * 100.0
        } else {
            0.0
        }
    }

    /// 空き容量を取得
    pub fn free_size(&self) -> u64 {
        if self.total_size > self.used_size {
            self.total_size - self.used_size
        } else {
            0
        }
    }

    /// 統計をJSON文字列として取得
    pub fn to_json(&self) -> String {
        serde_json::json!({
            "total_size": self.total_size,
            "used_size": self.used_size,
            "table_count": self.table_count,
            "usage_percentage": self.usage_percentage(),
            "free_size": self.free_size()
        }).to_string()
    }
}

/// デフォルトストレージを作成
#[wasm_bindgen]
pub fn create_default_storage() -> Result<WasmStorage, JsValue> {
    WasmStorage::new(StorageType::LocalStorage)
}

/// ストレージタイプを文字列から解析
#[wasm_bindgen]
pub fn parse_storage_type(type_str: &str) -> StorageType {
    match type_str.to_lowercase().as_str() {
        "localstorage" | "local" => StorageType::LocalStorage,
        "indexeddb" | "idb" => StorageType::IndexedDB,
        "memory" | "mem" => StorageType::Memory,
        _ => StorageType::LocalStorage, // デフォルト
    }
} 