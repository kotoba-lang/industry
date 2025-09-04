//! # Event Data Structures
//!
//! イベントソーシングで使用するイベントデータ構造の定義。
//! 型安全性とシリアライゼーション効率を重視した設計。

use bytes::Bytes;
use derive_more::{Deref, DerefMut, From, Into};
use serde::{Deserialize, Serialize};
use std::{collections::HashMap, time::{SystemTime, UNIX_EPOCH}};
use uuid::Uuid;
use crate::{Offset, Partition, Topic};

/// 圧縮タイプ
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum CompressionType {
    None,
    Snappy,
}

impl Default for CompressionType {
    fn default() -> Self {
        CompressionType::None
    }
}

/// イベントID（UUIDv4）
///
/// 各イベントの一意識別子。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize, Deref, DerefMut, From, Into)]
pub struct EventId(pub Uuid);

impl EventId {
    /// 新しいイベントIDを生成
    pub fn new() -> Self {
        Self(Uuid::new_v4())
    }
    
    /// UUIDからイベントIDを作成
    pub fn from_uuid(uuid: Uuid) -> Self {
        Self(uuid)
    }
}

impl Default for EventId {
    fn default() -> Self {
        Self::new()
    }
}

impl std::fmt::Display for EventId {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{}", self.0)
    }
}

/// イベントデータのペイロード
/// 
/// 実際のイベント内容を格納。バイナリデータとして扱い、
/// 上位層でのデシリアライゼーションに対応。
/// 
/// # @todo
/// - [ ] 圧縮サポート
/// - [ ] スキーマバージョニング
/// - [ ] 暗号化サポート
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, Deref, DerefMut, From, Into)]
pub struct EventData(pub Vec<u8>);

impl EventData {
    /// 新しいイベントデータを作成
    pub fn new(data: Vec<u8>) -> Self {
        Self(data)
    }
    
    /// バイト配列からイベントデータを作成
    pub fn from_bytes(bytes: impl Into<Vec<u8>>) -> Self {
        Self(bytes.into())
    }
    
    /// JSON文字列からイベントデータを作成
    pub fn from_json(json: &str) -> Self {
        Self(json.as_bytes().to_vec())
    }
    
    /// イベントデータのサイズを取得
    pub fn len(&self) -> usize {
        self.0.len()
    }
    
    /// イベントデータが空かどうかを確認
    pub fn is_empty(&self) -> bool {
        self.0.is_empty()
    }
    
    /// イベントデータをバイト配列として取得
    pub fn as_bytes(&self) -> &[u8] {
        &self.0
    }
    
    /// UTF-8文字列として解釈を試行
    pub fn as_str(&self) -> Option<&str> {
        std::str::from_utf8(&self.0).ok()
    }
    
    /// イベントデータをJSON文字列として取得
    /// 
    /// # Returns
    /// * `String` - UTF-8文字列として解釈されたイベントデータ
    /// 
    /// # Note
    /// データが有効なUTF-8でない場合、base64エンコードされた文字列を返す
    pub fn to_json(&self) -> String {
        match std::str::from_utf8(&self.0) {
            Ok(s) => s.to_string(),
            Err(_) => {
                // バイナリデータの場合はbase64エンコード
                use base64::Engine;
                base64::engine::general_purpose::STANDARD.encode(&self.0)
            }
        }
    }
    
    /// イベントデータをJSONオブジェクトとして解析
    /// 
    /// # Returns
    /// * `Result<serde_json::Value, serde_json::Error>` - 解析されたJSONオブジェクト
    pub fn to_json_value(&self) -> Result<serde_json::Value, serde_json::Error> {
        match std::str::from_utf8(&self.0) {
            Ok(s) => serde_json::from_str(s),
            Err(_) => {
                // バイナリデータの場合はbase64文字列としてJSONにラップ
                use base64::Engine;
                let base64_str = base64::engine::general_purpose::STANDARD.encode(&self.0);
                Ok(serde_json::Value::String(base64_str))
            }
        }
    }
    
    /// JSON文字列からイベントデータを作成（バリデーション付き）
    /// 
    /// # Arguments
    /// * `json` - JSON文字列
    /// 
    /// # Returns
    /// * `Result<EventData, serde_json::Error>` - 作成されたイベントデータ
    pub fn from_json_validated(json: &str) -> Result<Self, serde_json::Error> {
        // JSONの構文チェック
        let _: serde_json::Value = serde_json::from_str(json)?;
        Ok(Self::from_json(json))
    }
    
    /// イベントデータをプレーンテキストとして取得
    /// 
    /// # Returns
    /// * `String` - プレーンテキストとして解釈されたデータ
    pub fn to_text(&self) -> String {
        match std::str::from_utf8(&self.0) {
            Ok(s) => s.to_string(),
            Err(_) => format!("<binary data: {} bytes>", self.0.len()),
        }
    }
}

/// タイムスタンプ型（Newtypeパターン）
/// 
/// UNIXタイムスタンプ（ミリ秒精度）を表現。
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Serialize, Deserialize, Deref, DerefMut, From, Into)]
pub struct Timestamp(pub u64);

impl Timestamp {
    /// 現在時刻のタイムスタンプを作成
    pub fn now() -> Self {
        let duration = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap_or_default();
        Self(duration.as_millis() as u64)
    }
    
    /// ミリ秒値からタイムスタンプを作成
    pub fn from_millis(millis: u64) -> Self {
        Self(millis)
    }
    
    /// タイムスタンプをミリ秒値として取得
    pub fn as_millis(&self) -> u64 {
        self.0
    }
}

impl Default for Timestamp {
    fn default() -> Self {
        Self::now()
    }
}

/// イベント構造体
/// 
/// ストレージに永続化される完全なイベント情報。
/// Kafka互換のメッセージ構造を参考にした設計。
/// 
/// # Fields
/// - `id`: イベントの一意識別子
/// - `topic`: イベントが属するトピック
/// - `partition`: パーティション番号
/// - `offset`: パーティション内での順序位置
/// - `timestamp`: イベント作成時刻
/// - `data`: イベントのペイロード
/// - `metadata`: 追加メタデータ
/// 
/// # Example
/// ```rust
/// use kawa_storage::{Event, EventId, EventData, Topic, Partition};
/// 
/// let event = Event::new(
///     EventId::new(),
///     Topic::new("user-events"),
///     Partition::new(0),
///     EventData::from_json(r#"{"user_id": 123, "action": "login"}"#)
/// );
/// ```
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Event {
    /// イベントID
    pub id: EventId,
    /// オフセット
    pub offset: Option<Offset>,
    /// トピック
    pub topic: Topic,
    /// パーティション
    pub partition: Partition,
    /// タイムスタンプ
    pub timestamp: u64,
    /// キー
    pub key: Option<Bytes>,
    /// データ
    pub data: EventData,
    /// 圧縮タイプ
    #[serde(default)]
    pub compression: CompressionType,
    /// ヘッダー
    pub headers: HashMap<String, String>,
}

impl Event {
    /// 新しいイベントを作成
    /// 
    /// # Arguments
    /// * `id` - イベントID
    /// * `topic` - トピック名
    /// * `partition` - パーティション番号
    /// * `data` - イベントデータ
    /// 
    /// # Returns
    /// * `Event` - 作成されたイベント
    pub fn new(
        id: EventId,
        topic: Topic,
        partition: Partition,
        data: EventData,
    ) -> Self {
        Self {
            id,
            offset: None,
            topic,
            partition,
            timestamp: SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .unwrap_or_default()
                .as_millis() as u64,
            key: None,
            data,
            compression: CompressionType::None,
            headers: HashMap::new(),
        }
    }
    
    /// イベントにオフセットを設定
    pub fn set_offset(&mut self, offset: Offset) {
        self.offset = Some(offset);
    }

    /// ヘッダーを追加
    pub fn add_header(&mut self, key: String, value: String) {
        self.headers.insert(key, value);
    }
    
    /// イベントのおおよそのサイズを計算
    /// 
    /// # @todo
    /// - [ ] より正確なサイズ計算
    pub fn size_of(&self) -> usize {
        let mut size = std::mem::size_of::<Self>();
        size += self.topic.len();
        if let Some(key) = &self.key {
            size += key.len();
        }
        size += self.data.len();
        size += self.headers.iter().map(|(k, v)| k.len() + v.len()).sum::<usize>();
        size
    }
    
    /// イベントをキーでソート用の比較キーを取得
    pub fn sort_key(&self) -> (Timestamp, EventId) {
        (Timestamp::from_millis(self.timestamp), self.id)
    }
} 