use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::sync::{Arc, Mutex};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct iCloudData {
    pub notes: Vec<serde_json::Value>,
    pub last_sync_at: String,
    pub device_id: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SyncResult {
    pub success: bool,
    pub error: Option<String>,
    pub synced_count: usize,
    pub conflicts: usize,
}

#[cfg(target_os = "macos")]
mod macos_icloud {
    use super::*;
    use cocoa::base::{id, nil};
    use cocoa::foundation::{NSString, NSDefaultRunLoopMode};
    use objc::runtime::{Class, Object};
    use objc::{msg_send, sel, sel_impl};
    use core_foundation::string::{CFString, CFStringRef};
    use core_foundation::base::{CFType, TCFType};
    use std::ffi::CStr;
    use std::os::raw::c_char;

    // Thread-safe wrapper for iCloud operations
    pub struct iCloudManager {
        // We'll perform operations synchronously on the main thread
        _marker: std::marker::PhantomData<()>,
    }

    unsafe impl Send for iCloudManager {}
    unsafe impl Sync for iCloudManager {}

    impl iCloudManager {
        pub fn new() -> Result<Self, String> {
            // Test if we can access NSUbiquitousKeyValueStore
            unsafe {
                let class = Class::get("NSUbiquitousKeyValueStore")
                    .ok_or("NSUbiquitousKeyValueStore class not found")?;
                
                let _store: id = msg_send![class, defaultStore];
            }

            Ok(iCloudManager {
                _marker: std::marker::PhantomData,
            })
        }

        pub fn get_string(&self, key: &str) -> Option<String> {
            unsafe {
                let class = Class::get("NSUbiquitousKeyValueStore")?;
                let store: id = msg_send![class, defaultStore];
                
                if store == nil {
                    return None;
                }

                let ns_key = NSString::alloc(nil).init_str(key);
                let value: id = msg_send![store, stringForKey: ns_key];
                
                if value == nil {
                    return None;
                }

                let c_str: *const c_char = msg_send![value, UTF8String];
                if c_str.is_null() {
                    return None;
                }

                let rust_str = CStr::from_ptr(c_str).to_string_lossy().into_owned();
                Some(rust_str)
            }
        }

        pub fn set_string(&self, key: &str, value: &str) -> Result<(), String> {
            unsafe {
                let class = Class::get("NSUbiquitousKeyValueStore")
                    .ok_or("NSUbiquitousKeyValueStore class not found")?;
                let store: id = msg_send![class, defaultStore];
                
                if store == nil {
                    return Err("Failed to get NSUbiquitousKeyValueStore defaultStore".to_string());
                }

                let ns_key = NSString::alloc(nil).init_str(key);
                let ns_value = NSString::alloc(nil).init_str(value);
                
                let _: () = msg_send![store, setString: ns_value forKey: ns_key];
                let _: () = msg_send![store, synchronize];
                
                Ok(())
            }
        }

        pub fn get_data(&self, key: &str) -> Option<Vec<u8>> {
            unsafe {
                let class = Class::get("NSUbiquitousKeyValueStore")?;
                let store: id = msg_send![class, defaultStore];
                
                if store == nil {
                    return None;
                }

                let ns_key = NSString::alloc(nil).init_str(key);
                let data: id = msg_send![store, dataForKey: ns_key];
                
                if data == nil {
                    return None;
                }

                let length: usize = msg_send![data, length];
                let bytes: *const u8 = msg_send![data, bytes];
                
                if bytes.is_null() || length == 0 {
                    return None;
                }

                let slice = std::slice::from_raw_parts(bytes, length);
                Some(slice.to_vec())
            }
        }

        pub fn set_data(&self, key: &str, data: &[u8]) -> Result<(), String> {
            unsafe {
                let class = Class::get("NSUbiquitousKeyValueStore")
                    .ok_or("NSUbiquitousKeyValueStore class not found")?;
                let store: id = msg_send![class, defaultStore];
                
                if store == nil {
                    return Err("Failed to get NSUbiquitousKeyValueStore defaultStore".to_string());
                }

                let ns_key = NSString::alloc(nil).init_str(key);
                
                // NSDataを作成
                let ns_data_class = Class::get("NSData").ok_or("NSData class not found")?;
                let ns_data: id = msg_send![ns_data_class, dataWithBytes: data.as_ptr() length: data.len()];
                
                if ns_data == nil {
                    return Err("Failed to create NSData".to_string());
                }

                let _: () = msg_send![store, setData: ns_data forKey: ns_key];
                let _: () = msg_send![store, synchronize];
                
                Ok(())
            }
        }

        pub fn remove_object(&self, key: &str) -> Result<(), String> {
            unsafe {
                let class = Class::get("NSUbiquitousKeyValueStore")
                    .ok_or("NSUbiquitousKeyValueStore class not found")?;
                let store: id = msg_send![class, defaultStore];
                
                if store == nil {
                    return Err("Failed to get NSUbiquitousKeyValueStore defaultStore".to_string());
                }

                let ns_key = NSString::alloc(nil).init_str(key);
                let _: () = msg_send![store, removeObjectForKey: ns_key];
                let _: () = msg_send![store, synchronize];
                
                Ok(())
            }
        }

        pub fn get_all_keys(&self) -> Vec<String> {
            unsafe {
                let class = Class::get("NSUbiquitousKeyValueStore");
                if class.is_none() {
                    return Vec::new();
                }
                let class = class.unwrap();
                let store: id = msg_send![class, defaultStore];
                
                if store == nil {
                    return Vec::new();
                }

                let dictionary: id = msg_send![store, dictionaryRepresentation];
                let all_keys: id = msg_send![dictionary, allKeys];
                let count: usize = msg_send![all_keys, count];
                
                let mut keys = Vec::new();
                for i in 0..count {
                    let key: id = msg_send![all_keys, objectAtIndex: i];
                    let c_str: *const c_char = msg_send![key, UTF8String];
                    if !c_str.is_null() {
                        let rust_str = CStr::from_ptr(c_str).to_string_lossy().into_owned();
                        keys.push(rust_str);
                    }
                }
                
                keys
            }
        }
    }
}

#[cfg(not(target_os = "macos"))]
mod fallback_icloud {
    use super::*;
    use std::collections::HashMap;
    use std::sync::RwLock;
    
    // フォールバック実装（macOS以外の環境用）
    pub struct iCloudManager {
        storage: RwLock<HashMap<String, String>>,
    }

    impl iCloudManager {
        pub fn new() -> Result<Self, String> {
            Ok(iCloudManager {
                storage: RwLock::new(HashMap::new()),
            })
        }

        pub fn get_string(&self, key: &str) -> Option<String> {
            self.storage.read().unwrap().get(key).cloned()
        }

        pub fn set_string(&self, key: &str, value: &str) -> Result<(), String> {
            self.storage.write().unwrap().insert(key.to_string(), value.to_string());
            Ok(())
        }

        pub fn get_data(&self, key: &str) -> Option<Vec<u8>> {
            self.get_string(key).map(|s| s.into_bytes())
        }

        pub fn set_data(&self, key: &str, data: &[u8]) -> Result<(), String> {
            match String::from_utf8(data.to_vec()) {
                Ok(s) => self.set_string(key, &s),
                Err(_) => Err("Invalid UTF-8 data".to_string()),
            }
        }

        pub fn remove_object(&self, key: &str) -> Result<(), String> {
            self.storage.write().unwrap().remove(key);
            Ok(())
        }

        pub fn get_all_keys(&self) -> Vec<String> {
            self.storage.read().unwrap().keys().cloned().collect()
        }
    }
}

#[cfg(target_os = "macos")]
use macos_icloud::iCloudManager;

#[cfg(not(target_os = "macos"))]  
use fallback_icloud::iCloudManager;

// Thread-safe wrapper for the iCloud service
pub struct iCloudSyncService {
    manager: Arc<Mutex<iCloudManager>>,
}

// Explicitly implement Send and Sync for the service
unsafe impl Send for iCloudSyncService {}
unsafe impl Sync for iCloudSyncService {}

impl iCloudSyncService {
    pub fn new() -> Result<Self, String> {
        let manager = iCloudManager::new()?;
        Ok(iCloudSyncService { 
            manager: Arc::new(Mutex::new(manager))
        })
    }

    pub fn save_user_data(&self, user_id: &str, data: &iCloudData) -> Result<(), String> {
        let key = format!("prehender_user_{}", user_id);
        let json_data = serde_json::to_string(data)
            .map_err(|e| format!("Failed to serialize data: {}", e))?;
        
        let manager = self.manager.lock().unwrap();
        manager.set_string(&key, &json_data)?;
        log::info!("Saved data to iCloud for user: {}", user_id);
        Ok(())
    }

    pub fn load_user_data(&self, user_id: &str) -> Result<Option<iCloudData>, String> {
        let key = format!("prehender_user_{}", user_id);
        
        let manager = self.manager.lock().unwrap();
        match manager.get_string(&key) {
            Some(json_data) => {
                let data = serde_json::from_str::<iCloudData>(&json_data)
                    .map_err(|e| format!("Failed to deserialize data: {}", e))?;
                Ok(Some(data))
            }
            None => Ok(None),
        }
    }

    pub fn sync_notes(&self, user_id: &str, local_notes: Vec<serde_json::Value>) -> Result<SyncResult, String> {
        // 既存のiCloudデータを取得
        let existing_data = self.load_user_data(user_id)?;
        
        let mut synced_count = 0;
        let mut conflicts = 0;
        
        // 新しいデータを作成
        let new_data = iCloudData {
            notes: local_notes.clone(),
            last_sync_at: chrono::Utc::now().to_rfc3339(),
            device_id: uuid::Uuid::new_v4().to_string(),
        };

        // 競合検出ロジック（簡略化）
        if let Some(existing) = existing_data {
            // 既存のノートと比較
            for local_note in &local_notes {
                let local_id = local_note.get("id").and_then(|v| v.as_str());
                if let Some(id) = local_id {
                    let existing_note = existing.notes.iter()
                        .find(|n| n.get("id").and_then(|v| v.as_str()) == Some(id));
                    
                    if existing_note.is_some() {
                        // 簡単な競合検出（バージョン比較）
                        let local_version = local_note.get("version").and_then(|v| v.as_i64()).unwrap_or(0);
                        let existing_version = existing_note.unwrap().get("version").and_then(|v| v.as_i64()).unwrap_or(0);
                        
                        if local_version != existing_version {
                            conflicts += 1;
                        }
                    }
                }
                synced_count += 1;
            }
        } else {
            synced_count = local_notes.len();
        }

        // データを保存
        self.save_user_data(user_id, &new_data)?;

        Ok(SyncResult {
            success: true,
            error: None,
            synced_count,
            conflicts,
        })
    }

    pub fn get_all_user_ids(&self) -> Vec<String> {
        let manager = self.manager.lock().unwrap();
        let all_keys = manager.get_all_keys();
        all_keys.into_iter()
            .filter(|key| key.starts_with("prehender_user_"))
            .map(|key| key.strip_prefix("prehender_user_").unwrap_or("").to_string())
            .filter(|id| !id.is_empty())
            .collect()
    }

    pub fn delete_user_data(&self, user_id: &str) -> Result<(), String> {
        let key = format!("prehender_user_{}", user_id);
        let manager = self.manager.lock().unwrap();
        manager.remove_object(&key)?;
        log::info!("Deleted iCloud data for user: {}", user_id);
        Ok(())
    }
} 