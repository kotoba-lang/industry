//! # Kafka Protocol Types
//!
//! Kafkaプロトコルの基本型定義。
//! API キー、バージョン、リクエスト/レスポンス構造体。

use crate::{BrokerError, BrokerResult};
use serde::{Deserialize, Serialize};

/// Kafka API キー
/// 
/// 各リクエストタイプを識別する数値。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum ApiKey {
    /// Produce API (0) - メッセージ送信
    Produce = 0,
    /// Fetch API (1) - メッセージ取得  
    Fetch = 1,
    /// ListOffsets API (2) - オフセット一覧
    ListOffsets = 2,
    /// Metadata API (3) - クラスターメタデータ
    Metadata = 3,
    /// LeaderAndIsr API (4) - リーダー情報
    LeaderAndIsr = 4,
    /// StopReplica API (5) - レプリケーション停止
    StopReplica = 5,
    /// UpdateMetadata API (6) - メタデータ更新
    UpdateMetadata = 6,
    /// ControlledShutdown API (7) - 制御されたシャットダウン
    ControlledShutdown = 7,
    /// OffsetCommit API (8) - オフセットコミット
    OffsetCommit = 8,
    /// OffsetFetch API (9) - オフセット取得
    OffsetFetch = 9,
    /// FindCoordinator API (10) - コーディネーター検索
    FindCoordinator = 10,
    /// JoinGroup API (11) - グループ参加
    JoinGroup = 11,
    /// Heartbeat API (12) - ハートビート
    Heartbeat = 12,
    /// LeaveGroup API (13) - グループ離脱
    LeaveGroup = 13,
    /// SyncGroup API (14) - グループ同期
    SyncGroup = 14,
    /// DescribeGroups API (15) - グループ説明
    DescribeGroups = 15,
    /// ListGroups API (16) - グループ一覧
    ListGroups = 16,
    /// SaslHandshake API (17) - SASL ハンドシェイク
    SaslHandshake = 17,
    /// ApiVersions API (18) - API バージョン情報
    ApiVersions = 18,
    /// CreateTopics API (19) - トピック作成
    CreateTopics = 19,
    /// DeleteTopics API (20) - トピック削除
    DeleteTopics = 20,
}

impl ApiKey {
    /// API コードから ApiKey を作成
    pub fn from_code(code: i16) -> BrokerResult<Self> {
        match code {
            0 => Ok(ApiKey::Produce),
            1 => Ok(ApiKey::Fetch),
            2 => Ok(ApiKey::ListOffsets),
            3 => Ok(ApiKey::Metadata),
            4 => Ok(ApiKey::LeaderAndIsr),
            5 => Ok(ApiKey::StopReplica),
            6 => Ok(ApiKey::UpdateMetadata),
            7 => Ok(ApiKey::ControlledShutdown),
            8 => Ok(ApiKey::OffsetCommit),
            9 => Ok(ApiKey::OffsetFetch),
            10 => Ok(ApiKey::FindCoordinator),
            11 => Ok(ApiKey::JoinGroup),
            12 => Ok(ApiKey::Heartbeat),
            13 => Ok(ApiKey::LeaveGroup),
            14 => Ok(ApiKey::SyncGroup),
            15 => Ok(ApiKey::DescribeGroups),
            16 => Ok(ApiKey::ListGroups),
            17 => Ok(ApiKey::SaslHandshake),
            18 => Ok(ApiKey::ApiVersions),
            19 => Ok(ApiKey::CreateTopics),
            20 => Ok(ApiKey::DeleteTopics),
            _ => Err(BrokerError::UnsupportedVersion { version: code }),
        }
    }
    
    /// ApiKey を API コードに変換
    pub fn to_code(self) -> i16 {
        self as i16
    }
    
    /// API 名を取得
    pub fn name(&self) -> &'static str {
        match self {
            ApiKey::Produce => "Produce",
            ApiKey::Fetch => "Fetch",
            ApiKey::ListOffsets => "ListOffsets",
            ApiKey::Metadata => "Metadata",
            ApiKey::LeaderAndIsr => "LeaderAndIsr",
            ApiKey::StopReplica => "StopReplica",
            ApiKey::UpdateMetadata => "UpdateMetadata",
            ApiKey::ControlledShutdown => "ControlledShutdown",
            ApiKey::OffsetCommit => "OffsetCommit",
            ApiKey::OffsetFetch => "OffsetFetch",
            ApiKey::FindCoordinator => "FindCoordinator",
            ApiKey::JoinGroup => "JoinGroup",
            ApiKey::Heartbeat => "Heartbeat",
            ApiKey::LeaveGroup => "LeaveGroup",
            ApiKey::SyncGroup => "SyncGroup",
            ApiKey::DescribeGroups => "DescribeGroups",
            ApiKey::ListGroups => "ListGroups",
            ApiKey::SaslHandshake => "SaslHandshake",
            ApiKey::ApiVersions => "ApiVersions",
            ApiKey::CreateTopics => "CreateTopics",
            ApiKey::DeleteTopics => "DeleteTopics",
        }
    }
}

/// API バージョン
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct ApiVersion(pub i16);

impl ApiVersion {
    /// 新しいバージョンを作成
    pub fn new(version: i16) -> Self {
        Self(version)
    }
    
    /// バージョン番号を取得
    pub fn version(&self) -> i16 {
        self.0
    }
}

/// Kafka エラーコード
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ErrorCode {
    /// 成功
    None = 0,
    /// 不明なサーバーエラー
    UnknownServerError = -1,
    /// オフセット範囲外
    OffsetOutOfRange = 1,
    /// 破損したメッセージ
    CorruptMessage = 2,
    /// 不明なトピックまたはパーティション
    UnknownTopicOrPartition = 3,
    /// 無効なフェッチサイズ
    InvalidFetchSize = 4,
    /// リーダーが利用不可
    LeaderNotAvailable = 5,
    /// フォロワーではない
    NotLeaderForPartition = 6,
    /// リクエストタイムアウト
    RequestTimedOut = 7,
    /// ブローカーが利用不可
    BrokerNotAvailable = 8,
    /// レプリカが利用不可
    ReplicaNotAvailable = 9,
    /// メッセージが大きすぎる
    MessageTooLarge = 10,
    /// コントローラーエポックが古い
    StaleControllerEpoch = 11,
    /// オフセットメタデータが大きすぎる
    OffsetMetadataTooLarge = 12,
    /// ネットワーク例外
    NetworkException = 13,
    /// コーディネーターロード中
    CoordinatorLoadInProgress = 14,
    /// コーディネーター利用不可
    CoordinatorNotAvailable = 15,
    /// コーディネーターではない
    NotCoordinator = 16,
    /// 無効なトピック例外
    InvalidTopicException = 17,
    /// レコードリストが大きすぎる
    RecordListTooLarge = 18,
    /// フォロワーが少なすぎる
    NotEnoughReplicas = 19,
    /// フォロワーの後続が少なすぎる
    NotEnoughReplicasAfterAppend = 20,
    /// 無効な必要アック数
    InvalidRequiredAcks = 21,
    /// 無効なプロデューサーエポック
    InvalidProducerEpoch = 23,
    /// 無効なTxnState
    InvalidTxnState = 24,
    /// 無効なプロデューサーIDマッピング
    InvalidProducerIdMapping = 25,
    /// 無効なトランザクションタイムアウト
    InvalidTransactionTimeout = 26,
    /// 進行中のトランザクション
    ConcurrentTransactions = 27,
    /// トランザクションコーディネーターフェンシング
    TransactionCoordinatorFenced = 28,
    /// トピック認証失敗
    TopicAuthorizationFailed = 29,
    /// グループ認証失敗
    GroupAuthorizationFailed = 30,
    /// クラスター認証失敗
    ClusterAuthorizationFailed = 31,
    /// 無効なタイムスタンプ
    InvalidTimestamp = 32,
    /// サポートされていないSASLメカニズム
    UnsupportedSaslMechanism = 33,
    /// 無効なリクエスト
    InvalidRequest = 43,
    /// サポートされていないバージョン
    UnsupportedVersion = 35,
}

impl ErrorCode {
    /// エラーコードから ErrorCode を作成
    pub fn from_code(code: i16) -> Self {
        match code {
            0 => ErrorCode::None,
            -1 => ErrorCode::UnknownServerError,
            1 => ErrorCode::OffsetOutOfRange,
            2 => ErrorCode::CorruptMessage,
            3 => ErrorCode::UnknownTopicOrPartition,
            4 => ErrorCode::InvalidFetchSize,
            5 => ErrorCode::LeaderNotAvailable,
            6 => ErrorCode::NotLeaderForPartition,
            7 => ErrorCode::RequestTimedOut,
            8 => ErrorCode::BrokerNotAvailable,
            9 => ErrorCode::ReplicaNotAvailable,
            10 => ErrorCode::MessageTooLarge,
            11 => ErrorCode::StaleControllerEpoch,
            12 => ErrorCode::OffsetMetadataTooLarge,
            13 => ErrorCode::NetworkException,
            14 => ErrorCode::CoordinatorLoadInProgress,
            15 => ErrorCode::CoordinatorNotAvailable,
            16 => ErrorCode::NotCoordinator,
            17 => ErrorCode::InvalidTopicException,
            18 => ErrorCode::RecordListTooLarge,
            19 => ErrorCode::NotEnoughReplicas,
            20 => ErrorCode::NotEnoughReplicasAfterAppend,
            21 => ErrorCode::InvalidRequiredAcks,
            23 => ErrorCode::InvalidProducerEpoch,
            24 => ErrorCode::InvalidTxnState,
            25 => ErrorCode::InvalidProducerIdMapping,
            26 => ErrorCode::InvalidTransactionTimeout,
            27 => ErrorCode::ConcurrentTransactions,
            28 => ErrorCode::TransactionCoordinatorFenced,
            29 => ErrorCode::TopicAuthorizationFailed,
            30 => ErrorCode::GroupAuthorizationFailed,
            31 => ErrorCode::ClusterAuthorizationFailed,
            32 => ErrorCode::InvalidTimestamp,
            33 => ErrorCode::UnsupportedSaslMechanism,
            43 => ErrorCode::InvalidRequest,
            35 => ErrorCode::UnsupportedVersion,
            _ => ErrorCode::UnknownServerError,
        }
    }
    
    /// ErrorCode をエラーコードに変換
    pub fn to_code(self) -> i16 {
        self as i16
    }
}

/// Produce リクエスト
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ProduceRequest {
    /// トランザクション ID
    pub transactional_id: Option<String>,
    /// 必要な ACK 数
    pub acks: i16,
    /// タイムアウト（ミリ秒）
    pub timeout_ms: u32,
    /// トピックデータ
    pub topic_data: Vec<ProduceTopicData>,
}

/// Produce リクエストのトピックデータ
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ProduceTopicData {
    /// トピック名
    pub topic: String,
    /// パーティションデータ
    pub partition_data: Vec<ProducePartitionData>,
}

/// Produce リクエストのパーティションデータ
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ProducePartitionData {
    /// パーティション番号
    pub partition: u32,
    /// レコード（生バイト）
    pub records: Vec<u8>,
}

/// Produce レスポンス
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ProduceResponse {
    /// レスポンス
    pub responses: Vec<ProduceTopicResponse>,
    /// スロットルタイム（ミリ秒）
    pub throttle_time_ms: u32,
}

/// Produce レスポンスのトピックレスポンス
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ProduceTopicResponse {
    /// トピック名
    pub topic: String,
    /// パーティションレスポンス
    pub partition_responses: Vec<ProducePartitionResponse>,
}

/// Produce レスポンスのパーティションレスポンス
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ProducePartitionResponse {
    /// パーティション番号
    pub partition: u32,
    /// エラーコード
    pub error_code: i16,
    /// ベースオフセット
    pub base_offset: i64,
    /// ログ追加タイム
    pub log_append_time_ms: i64,
    /// ログ開始オフセット
    pub log_start_offset: i64,
}

/// Fetch リクエスト (旧ConsumeRequest)
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FetchRequest {
    /// レプリカID
    pub replica_id: i32,
    /// 最大待機時間（ミリ秒）
    pub max_wait_ms: u32,
    /// 最小バイト数
    pub min_bytes: u32,
    /// 最大バイト数
    pub max_bytes: u32,
    /// 分離レベル
    pub isolation_level: i8,
    /// セッションID
    pub session_id: u32,
    /// セッションエポック
    pub session_epoch: i32,
    /// トピック
    pub topics: Vec<FetchTopicData>,
    /// 忘れられたトピック
    pub forgotten_topics_data: Vec<ForgottenTopic>,
}

/// Fetch リクエストのトピックデータ
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FetchTopicData {
    /// トピック名
    pub topic: String,
    /// パーティション
    pub partitions: Vec<FetchPartitionData>,
}

/// Fetch リクエストのパーティションデータ
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FetchPartitionData {
    /// パーティション番号
    pub partition: u32,
    /// 現在のリーダーエポック
    pub current_leader_epoch: i32,
    /// フェッチオフセット
    pub fetch_offset: i64,
    /// ログ開始オフセット
    pub log_start_offset: i64,
    /// 最大バイト数
    pub max_bytes: u32,
}

/// 忘れられたトピック
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ForgottenTopic {
    /// トピック名
    pub topic: String,
    /// パーティション
    pub partitions: Vec<u32>,
}

/// 過去のCousumerRequestを保持（後方互換性）
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ConsumeRequest {
    /// トピック名
    pub topic: String,
    /// パーティション番号
    pub partition: u32,
    /// 開始オフセット
    pub offset: u64,
    /// 最大メッセージ数
    pub max_messages: usize,
}

impl From<ConsumeRequest> for FetchRequest {
    fn from(req: ConsumeRequest) -> Self {
        FetchRequest {
            replica_id: -1,
            max_wait_ms: 500,
            min_bytes: 1,
            max_bytes: 1024 * 1024, // 1MB
            isolation_level: 0,
            session_id: 0,
            session_epoch: -1,
            topics: vec![FetchTopicData {
                topic: req.topic,
                partitions: vec![FetchPartitionData {
                    partition: req.partition,
                    current_leader_epoch: -1,
                    fetch_offset: req.offset as i64,
                    log_start_offset: -1,
                    max_bytes: 1024 * 1024, // 1MB
                }],
            }],
            forgotten_topics_data: Vec::new(),
        }
    }
} 