//! # Broker Server
//!
//! Kafka互換プロトコルを処理するTCPサーバー。
//! 高性能な非同期処理でクライアント接続を管理。

use crate::{BrokerConfig, BrokerError, BrokerResult, protocol::KafkaProtocolHandler, ClientId, SessionId};
use std::sync::Arc;
use tokio::{
    net::{TcpListener, TcpStream},
    io::{AsyncReadExt, AsyncWriteExt},
    sync::RwLock,
};

/// Kafkaブローカーサーバー
/// 
/// 高性能なTCPサーバーでKafka互換プロトコルを処理。
/// 複数のクライアント接続を並行して管理。
/// 
/// # 機能
/// - TCP/TLS接続受け入れ
/// - Kafkaプロトコル処理
/// - セッション管理
/// - 非同期リクエスト処理
/// 
/// # @todo
/// - [ ] TLS サポート
/// - [ ] SASL認証
/// - [ ] 接続プール管理
/// - [ ] メトリクス収集
#[derive(Debug)]
pub struct BrokerServer {
    /// サーバー設定
    config: BrokerConfig,
    /// ストレージエンジン
    storage: Arc<kawa_storage::StorageEngine>,
    /// アクティブな接続セッション
    sessions: Arc<RwLock<std::collections::HashMap<SessionId, ClientSession>>>,
    /// プロトコルハンドラー
    protocol_handler: Arc<KafkaProtocolHandler>,
}

/// クライアント接続セッション
#[derive(Debug, Clone)]
pub struct ClientSession {
    /// セッションID
    pub session_id: SessionId,
    /// クライアントID
    pub client_id: ClientId,
    /// リモートアドレス
    pub remote_addr: std::net::SocketAddr,
    /// 認証状態
    pub authenticated: bool,
    /// 接続時刻
    pub connected_at: std::time::SystemTime,
    /// 最終アクティブ時刻
    pub last_activity: std::time::SystemTime,
}

impl BrokerServer {
    /// 新しいブローカーサーバーを作成
    /// 
    /// # Arguments
    /// * `config` - サーバー設定
    /// * `storage` - ストレージエンジン
    /// 
    /// # Returns
    /// * `BrokerResult<Self>` - サーバーインスタンス
    pub async fn new(
        config: BrokerConfig,
        storage: Arc<kawa_storage::StorageEngine>,
    ) -> BrokerResult<Self> {
        tracing::info!("Initializing broker server");
        
        let protocol_handler = Arc::new(KafkaProtocolHandler::new());
        let sessions = Arc::new(RwLock::new(std::collections::HashMap::new()));
        
        Ok(Self {
            config,
            storage,
            sessions,
            protocol_handler,
        })
    }
    
    /// サーバーを開始
    /// 
    /// # Returns
    /// * `BrokerResult<()>` - 開始結果
    pub async fn start(&self) -> BrokerResult<()> {
        let bind_addr = self.config.bind_address();
        tracing::info!("Starting Kafka broker server on {}", bind_addr);
        
        // TCPリスナーをバインド
        let listener = TcpListener::bind(bind_addr).await
            .map_err(|_e| BrokerError::BindFailed { address: bind_addr })?;
        
        let actual_addr = listener.local_addr()
            .map_err(|_e| BrokerError::BindFailed { address: bind_addr })?;
        
        tracing::info!("Broker server listening on {}", actual_addr);
        
        // 接続受け入れループを開始
        let storage = Arc::clone(&self.storage);
        let sessions = Arc::clone(&self.sessions);
        let protocol_handler = Arc::clone(&self.protocol_handler);
        let config = self.config.clone();
        
        Self::accept_connections(listener, storage, sessions, protocol_handler, config).await;
        Ok(())
    }
    
    /// セッションを登録
    #[allow(dead_code)]
    async fn register_session(&self, session_id: SessionId, client_id: ClientId, remote_addr: std::net::SocketAddr) {
        let session = ClientSession {
            session_id,
            client_id,
            remote_addr,
            authenticated: false,
            connected_at: std::time::SystemTime::now(),
            last_activity: std::time::SystemTime::now(),
        };
        
        let mut sessions = self.sessions.write().await;
        sessions.insert(session_id, session);
        
        tracing::debug!("Session registered: {} for client {}", session_id, remote_addr);
    }
    
    /// セッションを削除
    #[allow(dead_code)]
    async fn unregister_session(&self, session_id: SessionId) {
        let mut sessions = self.sessions.write().await;
        if sessions.remove(&session_id).is_some() {
            tracing::debug!("Session unregistered: {}", session_id);
        }
    }
    
    /// クライアント接続を処理
    async fn handle_client_connection(
        mut stream: TcpStream,
        session_id: SessionId,
        client_id: ClientId,
        storage: Arc<kawa_storage::StorageEngine>,
        sessions: Arc<RwLock<std::collections::HashMap<SessionId, ClientSession>>>,
        protocol_handler: Arc<KafkaProtocolHandler>,
        _config: BrokerConfig,
    ) -> BrokerResult<()> {
        tracing::info!("Handling client connection: session={}, client={}", session_id, client_id);
        
        let mut buffer = vec![0; 4096];
        
        loop {
            // リクエストデータを読み取り
            match stream.read(&mut buffer).await {
                Ok(0) => {
                    // 接続が閉じられた
                    tracing::debug!("Client disconnected: session={}", session_id);
                    break;
                }
                Ok(bytes_read) => {
                    tracing::debug!("Received {} bytes from client {}", bytes_read, session_id);
                    
                    // プロトコル処理（現在は簡易応答）
                    let request_slice = &buffer[..bytes_read];
                    match protocol_handler.handle_request(request_slice, &storage, session_id).await {
                        Ok(response) => {
                            if let Err(e) = stream.write_all(&response).await {
                                tracing::error!("Failed to send response to client {}: {}", session_id, e);
                                break;
                            }
                        }
                        Err(e) => {
                            tracing::error!("Protocol handling error for client {}: {}", session_id, e);
                            // エラー応答を送信（簡易実装）
                            let error_response = b"ERROR: Protocol handling failed\n";
                            let _ = stream.write_all(error_response).await;
                            break;
                        }
                    }
                }
                Err(e) => {
                    tracing::error!("Error reading from client {}: {}", session_id, e);
                    break;
                }
            }
        }
        
        // セッションをクリーンアップ
        let mut sessions_guard = sessions.write().await;
        sessions_guard.remove(&session_id);
        
        tracing::info!("Client connection closed: session={}", session_id);
        Ok(())
    }
    
    /// テスト用にサーバーを開始してアドレスを返す
    /// 
    /// # Returns
    /// * `BrokerResult<std::net::SocketAddr>` - 実際にバインドされたアドレス
    pub async fn start_and_get_addr(&self) -> BrokerResult<std::net::SocketAddr> {
        let bind_addr = self.config.bind_address();
        tracing::info!("Starting Kafka broker server on {}", bind_addr);
        
        // TCPリスナーをバインド
        let listener = TcpListener::bind(bind_addr).await
            .map_err(|_e| BrokerError::BindFailed { address: bind_addr })?;
        
        let actual_addr = listener.local_addr()
            .map_err(|_e| BrokerError::BindFailed { address: bind_addr })?;
        
        tracing::info!("Broker server listening on {}", actual_addr);
        
        // バックグラウンドで接続処理を開始
        let storage = Arc::clone(&self.storage);
        let sessions = Arc::clone(&self.sessions);
        let protocol_handler = Arc::clone(&self.protocol_handler);
        let config = self.config.clone();
        
        tokio::spawn(async move {
            Self::accept_connections(listener, storage, sessions, protocol_handler, config).await
        });
        
        Ok(actual_addr)
    }
    
    /// 接続受け入れループ（分離されたメソッド）
    async fn accept_connections(
        listener: TcpListener,
        storage: Arc<kawa_storage::StorageEngine>,
        sessions: Arc<RwLock<std::collections::HashMap<SessionId, ClientSession>>>,
        protocol_handler: Arc<KafkaProtocolHandler>,
        config: BrokerConfig,
    ) {
        loop {
            match listener.accept().await {
                Ok((stream, addr)) => {
                    tracing::debug!("New client connection from {}", addr);
                    
                    // 各接続を並行して処理
                    let session_id = SessionId::new();
                    let client_id = ClientId::new();
                    
                    // セッションを登録
                    let session = ClientSession {
                        session_id,
                        client_id: client_id.clone(),
                        remote_addr: addr,
                        authenticated: false,
                        connected_at: std::time::SystemTime::now(),
                        last_activity: std::time::SystemTime::now(),
                    };
                    
                    {
                        let mut sessions_guard = sessions.write().await;
                        sessions_guard.insert(session_id, session);
                    }
                    
                    // 接続処理を非同期タスクとして実行
                    let storage_clone = Arc::clone(&storage);
                    let sessions_clone = Arc::clone(&sessions);
                    let protocol_handler_clone = Arc::clone(&protocol_handler);
                    let config_clone = config.clone();
                    
                    tokio::spawn(async move {
                        if let Err(e) = Self::handle_client_connection(
                            stream, 
                            session_id, 
                            client_id, 
                            storage_clone, 
                            sessions_clone, 
                            protocol_handler_clone,
                            config_clone
                        ).await {
                            tracing::error!("Client connection error: {}", e);
                        }
                    });
                }
                Err(e) => {
                    tracing::error!("Failed to accept connection: {}", e);
                    // 接続受け入れエラーは続行
                }
            }
        }
    }
    
    /// サーバーを停止
    /// 
    /// # Returns
    /// * `BrokerResult<()>` - 停止結果
    pub async fn stop(&mut self) -> BrokerResult<()> {
        tracing::info!("Stopping broker server");
        
        // アクティブなセッションを全てクローズ
        let mut sessions = self.sessions.write().await;
        let session_count = sessions.len();
        sessions.clear();
        
        tracing::info!("Broker server stopped (closed {} sessions)", session_count);
        Ok(())
    }
} 