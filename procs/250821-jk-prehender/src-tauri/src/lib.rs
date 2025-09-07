mod icloud;

use tauri::State;
use std::sync::Mutex;
use icloud::{iCloudSyncService, iCloudData, SyncResult};
use tauri_plugin_sql::{Migration, MigrationKind};

// アプリケーション状態
pub struct AppState {
    pub icloud_service: Mutex<Option<iCloudSyncService>>,
}

// Tauriコマンド：iCloudサービスの初期化
#[tauri::command]
async fn init_icloud_service(state: State<'_, AppState>) -> Result<bool, String> {
    let service = iCloudSyncService::new()?;
    let mut service_state = state.icloud_service.lock().unwrap();
    *service_state = Some(service);
    log::info!("iCloud service initialized successfully");
    Ok(true)
}

// Tauriコマンド：ユーザーデータをiCloudに保存
#[tauri::command]
async fn save_to_icloud(
    user_id: String,
    notes: Vec<serde_json::Value>,
    state: State<'_, AppState>
) -> Result<(), String> {
    let service_guard = state.icloud_service.lock().unwrap();
    let service = service_guard.as_ref()
        .ok_or("iCloud service not initialized")?;
    
    let data = iCloudData {
        notes,
        last_sync_at: chrono::Utc::now().to_rfc3339(),
        device_id: uuid::Uuid::new_v4().to_string(),
    };
    
    service.save_user_data(&user_id, &data)?;
    Ok(())
}

// Tauriコマンド：iCloudからユーザーデータを読み込み
#[tauri::command]
async fn load_from_icloud(
    user_id: String,
    state: State<'_, AppState>
) -> Result<Option<iCloudData>, String> {
    let service_guard = state.icloud_service.lock().unwrap();
    let service = service_guard.as_ref()
        .ok_or("iCloud service not initialized")?;
    
    service.load_user_data(&user_id)
}

// Tauriコマンド：ノートの同期
#[tauri::command]
async fn sync_notes_with_icloud(
    user_id: String,
    local_notes: Vec<serde_json::Value>,
    state: State<'_, AppState>
) -> Result<SyncResult, String> {
    let service_guard = state.icloud_service.lock().unwrap();
    let service = service_guard.as_ref()
        .ok_or("iCloud service not initialized")?;
    
    service.sync_notes(&user_id, local_notes)
}

// Tauriコマンド：全ユーザーIDの取得
#[tauri::command]
async fn get_all_icloud_users(state: State<'_, AppState>) -> Result<Vec<String>, String> {
    let service_guard = state.icloud_service.lock().unwrap();
    let service = service_guard.as_ref()
        .ok_or("iCloud service not initialized")?;
    
    Ok(service.get_all_user_ids())
}

// Tauriコマンド：ユーザーデータの削除
#[tauri::command]
async fn delete_icloud_user_data(
    user_id: String,
    state: State<'_, AppState>
) -> Result<(), String> {
    let service_guard = state.icloud_service.lock().unwrap();
    let service = service_guard.as_ref()
        .ok_or("iCloud service not initialized")?;
    
    service.delete_user_data(&user_id)
}

// Tauriコマンド：iCloud同期状態の確認
#[tauri::command]
async fn check_icloud_availability() -> Result<bool, String> {
    #[cfg(target_os = "macos")]
    {
        // macOSでiCloudの可用性をチェック
        match iCloudSyncService::new() {
            Ok(_) => Ok(true),
            Err(e) => {
                log::warn!("iCloud not available: {}", e);
                Ok(false)
            }
        }
    }
    
    #[cfg(not(target_os = "macos"))]
    {
        // macOS以外では常にfalse
        Ok(false)
    }
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
  let app_state = AppState {
      icloud_service: Mutex::new(None),
  };

  // データベースマイグレーションの定義
  let migrations = vec![
      Migration {
          version: 1,
          description: "create notes table",
          sql: "CREATE TABLE IF NOT EXISTS notes (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              title TEXT NOT NULL,
              content TEXT,
              created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
              updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
              user_id TEXT,
              topic TEXT,
              tags TEXT,
              metadata TEXT
          )",
          kind: MigrationKind::Up,
      },
      Migration {
          version: 2,
          description: "create users table",
          sql: "CREATE TABLE IF NOT EXISTS users (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              name TEXT NOT NULL,
              email TEXT,
              created_at DATETIME DEFAULT CURRENT_TIMESTAMP
          )",
          kind: MigrationKind::Up,
      },
      Migration {
          version: 3,
          description: "create sync_status table",
          sql: "CREATE TABLE IF NOT EXISTS sync_status (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              last_sync_at DATETIME,
              device_id TEXT,
              sync_type TEXT
          )",
          kind: MigrationKind::Up,
      }
  ];

  tauri::Builder::default()
    .manage(app_state)
    .plugin(
        tauri_plugin_sql::Builder::default()
            .add_migrations("sqlite:prehender_local.db", migrations)
            .build()
    )
    .invoke_handler(tauri::generate_handler![
        init_icloud_service,
        save_to_icloud,
        load_from_icloud,
        sync_notes_with_icloud,
        get_all_icloud_users,
        delete_icloud_user_data,
        check_icloud_availability
    ])
    .setup(|app| {
      if cfg!(debug_assertions) {
        app.handle().plugin(
          tauri_plugin_log::Builder::default()
            .level(log::LevelFilter::Info)
            .build(),
        )?;
      }
      
      // アプリケーション起動ログ
      log::info!("Prehender app started successfully");
      
      Ok(())
    })
    .run(tauri::generate_context!())
    .expect("error while running tauri application");
}
