//! # Kafka Protocol Handler
//!
//! Kafkaプロトコルのリクエスト処理とレスポンス生成。
//! APIキー別のルーティングとバイナリプロトコル対応。

use crate::{BrokerResult, SessionId};
use super::{
    binary::{KafkaRequest, KafkaResponse, ProtocolWriter},
    types::{ApiKey, ErrorCode},
};
use bytes::Bytes;
use std::sync::Arc;

/// Kafka プロトコルハンドラー
/// 
/// バイナリKafkaプロトコルを処理し、適切なAPIハンドラーにルーティング。
/// 高性能なリクエスト処理とレスポンス生成を提供。
/// 
/// # 対応API
/// - Produce API (0) - メッセージ送信
/// - Fetch API (1) - メッセージ取得
/// - Metadata API (3) - クラスターメタデータ
/// - ApiVersions API (18) - サポート済みAPI情報
/// 
/// # @todo
/// - [ ] 完全なAPI対応
/// - [ ] レプリケーション対応
/// - [ ] コンシューマーグループ管理
/// - [ ] 管理API実装
#[derive(Debug)]
pub struct KafkaProtocolHandler {
    /// サポート済みAPI一覧
    supported_apis: Vec<(ApiKey, i16, i16)>, // (api_key, min_version, max_version)
}

impl KafkaProtocolHandler {
    /// 新しいプロトコルハンドラーを作成
    pub fn new() -> Self {
        // サポート済みAPIバージョンを定義
        let supported_apis = vec![
            (ApiKey::Produce, 0, 7),          // Produce API v0-v7
            (ApiKey::Fetch, 0, 11),           // Fetch API v0-v11
            (ApiKey::Metadata, 0, 9),         // Metadata API v0-v9
            (ApiKey::ApiVersions, 0, 3),      // ApiVersions API v0-v3
        ];
        
        Self { supported_apis }
    }
    
    /// クライアントリクエストを処理
    /// 
    /// # Arguments
    /// * `request_data` - バイナリリクエストデータ
    /// * `storage` - ストレージエンジン  
    /// * `session_id` - セッションID
    /// 
    /// # Returns
    /// * `BrokerResult<Vec<u8>>` - バイナリレスポンスデータ
    pub async fn handle_request(
        &self,
        request_data: &[u8],
        storage: &Arc<kawa_storage::StorageEngine>,
        session_id: SessionId,
    ) -> BrokerResult<Vec<u8>> {
        tracing::debug!(
            "Processing Kafka protocol request: {} bytes from session {}",
            request_data.len(), 
            session_id
        );
        
        // リクエストがKafkaプロトコル形式かチェック
        if request_data.len() < 14 {
            // 最小ヘッダーサイズ: length(4) + api_key(2) + api_version(2) + correlation_id(4) + client_id_len(2)
            return self.handle_text_request(request_data, storage, session_id).await;
        }
        
        // Kafkaバイナリプロトコルとして解析を試行
        match KafkaRequest::parse(request_data) {
            Ok(kafka_request) => {
                self.handle_kafka_request(kafka_request, storage, session_id).await
            }
            Err(_) => {
                // バイナリ解析に失敗した場合、テキストリクエストとして処理
                self.handle_text_request(request_data, storage, session_id).await
            }
        }
    }
    
    /// Kafkaバイナリリクエストを処理
    async fn handle_kafka_request(
        &self,
        request: KafkaRequest,
        storage: &Arc<kawa_storage::StorageEngine>,
        session_id: SessionId,
    ) -> BrokerResult<Vec<u8>> {
        let api_key = request.header.api_key;
        let api_version = request.header.api_version;
        let correlation_id = request.header.correlation_id;
        
        tracing::info!(
            "Kafka API request: {} v{} (correlation_id: {}, session: {})",
            api_key.name(),
            api_version.version(),
            correlation_id,
            session_id
        );
        
        // APIバージョンをチェック
        if !self.is_api_version_supported(api_key, api_version.version()) {
            return self.create_error_response(
                correlation_id,
                ErrorCode::UnsupportedVersion
            );
        }
        
        // APIキー別の処理
        let response_body = match api_key {
            ApiKey::Produce => {
                self.handle_produce_api(request.body, storage, session_id).await?
            }
            ApiKey::Fetch => {
                self.handle_fetch_api(request.body, storage, session_id).await?
            }
            ApiKey::Metadata => {
                self.handle_metadata_api(request.body, storage, session_id).await?
            }
            ApiKey::ApiVersions => {
                self.handle_api_versions_api(request.body, session_id).await?
            }
            _ => {
                return self.create_error_response(
                    correlation_id,
                    ErrorCode::InvalidRequest
                );
            }
        };
        
        // レスポンスを作成
        let response = KafkaResponse::new(correlation_id, response_body);
        Ok(response.serialize().to_vec())
    }
    
    /// テキストリクエストを処理（後方互換性）
    async fn handle_text_request(
        &self,
        request_data: &[u8],
        _storage: &Arc<kawa_storage::StorageEngine>,
        session_id: SessionId,
    ) -> BrokerResult<Vec<u8>> {
        tracing::debug!("Processing text request from session {}", session_id);
        
        let request_text = String::from_utf8_lossy(request_data);
        
        // 簡易テキストコマンド処理
        let response = if request_text.starts_with("PING") {
            b"PONG\n".to_vec()
        } else if request_text.starts_with("PRODUCE") {
            b"PRODUCE_OK\n".to_vec()
        } else if request_text.starts_with("FETCH") {
            b"FETCH_OK\n".to_vec()
        } else {
            format!("UNKNOWN REQUEST: {} bytes\n", request_data.len()).into_bytes()
        };
        
        Ok(response)
    }
    
    /// Produce API を処理
    async fn handle_produce_api(
        &self,
        _body: Bytes,
        _storage: &Arc<kawa_storage::StorageEngine>,
        session_id: SessionId,
    ) -> BrokerResult<Bytes> {
        tracing::debug!("Handling Produce API for session {}", session_id);
        
        // 簡易実装: 成功レスポンスを返す
        let mut writer = ProtocolWriter::new();
        
        // スロットルタイム（0ms）
        writer.write_u32(0);
        
        // レスポンス配列のサイズ（0個）
        writer.write_u32(0);
        
        Ok(writer.into_bytes())
    }
    
    /// Fetch API を処理
    async fn handle_fetch_api(
        &self,
        _body: Bytes,
        _storage: &Arc<kawa_storage::StorageEngine>,
        session_id: SessionId,
    ) -> BrokerResult<Bytes> {
        tracing::debug!("Handling Fetch API for session {}", session_id);
        
        // 簡易実装: 空のレスポンスを返す
        let mut writer = ProtocolWriter::new();
        
        // スロットルタイム（0ms）
        writer.write_u32(0);
        
        // エラーコード（0 = 成功）
        writer.write_i16(0);
        
        // セッションID
        writer.write_u32(0);
        
        // レスポンス配列のサイズ（0個）
        writer.write_u32(0);
        
        Ok(writer.into_bytes())
    }
    
    /// Metadata API を処理
    async fn handle_metadata_api(
        &self,
        _body: Bytes,
        _storage: &Arc<kawa_storage::StorageEngine>,
        session_id: SessionId,
    ) -> BrokerResult<Bytes> {
        tracing::debug!("Handling Metadata API for session {}", session_id);
        
        // 簡易実装: 基本的なクラスターメタデータを返す
        let mut writer = ProtocolWriter::new();
        
        // スロットルタイム（0ms）
        writer.write_u32(0);
        
        // ブローカー配列のサイズ（1個）
        writer.write_u32(1);
        
        // ブローカー情報
        writer.write_i32(0);  // ブローカーID
        writer.write_string(Some("localhost"));  // ホスト
        writer.write_i32(9092);  // ポート
        writer.write_string(None);  // ラック情報
        
        // クラスターID
        writer.write_string(Some("kawa-cluster"));
        
        // コントローラーID
        writer.write_i32(0);
        
        // トピック配列のサイズ（0個）
        writer.write_u32(0);
        
        Ok(writer.into_bytes())
    }
    
    /// ApiVersions API を処理
    async fn handle_api_versions_api(
        &self,
        _body: Bytes,
        session_id: SessionId,
    ) -> BrokerResult<Bytes> {
        tracing::debug!("Handling ApiVersions API for session {}", session_id);
        
        let mut writer = ProtocolWriter::new();
        
        // エラーコード（0 = 成功）
        writer.write_i16(0);
        
        // API キー配列のサイズ
        writer.write_u32(self.supported_apis.len() as u32);
        
        // 各サポート済みAPIを書き込み
        for (api_key, min_version, max_version) in &self.supported_apis {
            writer.write_i16(api_key.to_code());  // API キー
            writer.write_i16(*min_version);       // 最小バージョン
            writer.write_i16(*max_version);       // 最大バージョン
        }
        
        // スロットルタイム（0ms）
        writer.write_u32(0);
        
        Ok(writer.into_bytes())
    }
    
    /// APIバージョンがサポートされているかチェック
    fn is_api_version_supported(&self, api_key: ApiKey, version: i16) -> bool {
        self.supported_apis
            .iter()
            .find(|(key, _, _)| *key == api_key)
            .map(|(_, min_ver, max_ver)| version >= *min_ver && version <= *max_ver)
            .unwrap_or(false)
    }
    
    /// エラーレスポンスを作成
    fn create_error_response(
        &self,
        correlation_id: u32,
        error_code: ErrorCode,
    ) -> BrokerResult<Vec<u8>> {
        let mut writer = ProtocolWriter::new();
        
        // エラーコード
        writer.write_i16(error_code.to_code());
        
        // エラーメッセージ
        writer.write_string(Some("Error occurred"));
        
        let response = KafkaResponse::new(correlation_id, writer.into_bytes());
        Ok(response.serialize().to_vec())
    }
}

impl Default for KafkaProtocolHandler {
    fn default() -> Self {
        Self::new()
    }
} 