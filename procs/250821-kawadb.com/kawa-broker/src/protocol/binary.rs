//! # Kafka Binary Protocol
//!
//! Kafkaプロトコルのバイナリ形式処理。
//! リクエスト/レスポンスのシリアライゼーション・デシリアライゼーション。

use crate::{BrokerError, BrokerResult};
use super::types::{ApiKey, ApiVersion};
use bytes::{BufMut, Bytes, BytesMut};

/// Kafkaプロトコルのリクエストヘッダー
#[derive(Debug, Clone)]
pub struct RequestHeader {
    /// リクエスト長（ヘッダー含む）
    pub length: u32,
    /// API キー
    pub api_key: ApiKey,
    /// API バージョン
    pub api_version: ApiVersion,
    /// 相関ID（リクエスト・レスポンス対応）
    pub correlation_id: u32,
    /// クライアントID
    pub client_id: Option<String>,
}

/// Kafkaプロトコルのレスポンスヘッダー
#[derive(Debug, Clone)]
pub struct ResponseHeader {
    /// レスポンス長
    pub length: u32,
    /// 相関ID（リクエストと同じ）
    pub correlation_id: u32,
}

/// Kafkaリクエスト
#[derive(Debug)]
pub struct KafkaRequest {
    /// リクエストヘッダー
    pub header: RequestHeader,
    /// リクエストボディ（生データ）
    pub body: Bytes,
}

/// Kafkaレスポンス
#[derive(Debug)]
pub struct KafkaResponse {
    /// レスポンスヘッダー
    pub header: ResponseHeader,
    /// レスポンスボディ（生データ）
    pub body: Bytes,
}

/// プロトコルリーダー - バイナリデータからKafka構造体への変換
pub struct ProtocolReader<'a> {
    buf: &'a [u8],
    pos: usize,
}

impl<'a> ProtocolReader<'a> {
    /// 新しいリーダーを作成
    pub fn new(buf: &'a [u8]) -> Self {
        Self { buf, pos: 0 }
    }
    
    /// バッファに残りデータがあるかチェック
    pub fn has_remaining(&self) -> bool {
        self.pos < self.buf.len()
    }
    
    /// 残りバイト数を取得
    pub fn remaining(&self) -> usize {
        self.buf.len() - self.pos
    }
    
    /// u8を読み取り
    pub fn read_u8(&mut self) -> BrokerResult<u8> {
        if self.pos >= self.buf.len() {
            return Err(BrokerError::invalid_protocol("Unexpected end of buffer"));
        }
        let val = self.buf[self.pos];
        self.pos += 1;
        Ok(val)
    }
    
    /// u16 (big-endian) を読み取り
    pub fn read_u16(&mut self) -> BrokerResult<u16> {
        if self.pos + 2 > self.buf.len() {
            return Err(BrokerError::invalid_protocol("Unexpected end of buffer"));
        }
        let val = u16::from_be_bytes([self.buf[self.pos], self.buf[self.pos + 1]]);
        self.pos += 2;
        Ok(val)
    }
    
    /// u32 (big-endian) を読み取り
    pub fn read_u32(&mut self) -> BrokerResult<u32> {
        if self.pos + 4 > self.buf.len() {
            return Err(BrokerError::invalid_protocol("Unexpected end of buffer"));
        }
        let val = u32::from_be_bytes([
            self.buf[self.pos],
            self.buf[self.pos + 1], 
            self.buf[self.pos + 2],
            self.buf[self.pos + 3]
        ]);
        self.pos += 4;
        Ok(val)
    }
    
    /// i16 (big-endian) を読み取り
    pub fn read_i16(&mut self) -> BrokerResult<i16> {
        Ok(self.read_u16()? as i16)
    }
    
    /// i32 (big-endian) を読み取り
    pub fn read_i32(&mut self) -> BrokerResult<i32> {
        Ok(self.read_u32()? as i32)
    }
    
    /// Kafkaスタイルの文字列を読み取り (length-prefixed)
    pub fn read_string(&mut self) -> BrokerResult<Option<String>> {
        let len = self.read_i16()?;
        
        if len == -1 {
            // Null string
            return Ok(None);
        }
        
        if len < 0 {
            return Err(BrokerError::invalid_protocol("Invalid string length"));
        }
        
        let len = len as usize;
        if self.pos + len > self.buf.len() {
            return Err(BrokerError::invalid_protocol("String length exceeds buffer"));
        }
        
        let string_data = &self.buf[self.pos..self.pos + len];
        let string = String::from_utf8(string_data.to_vec())
            .map_err(|_| BrokerError::invalid_protocol("Invalid UTF-8 string"))?;
        
        self.pos += len;
        Ok(Some(string))
    }
    
    /// バイト配列を読み取り
    pub fn read_bytes(&mut self, len: usize) -> BrokerResult<&[u8]> {
        if self.pos + len > self.buf.len() {
            return Err(BrokerError::invalid_protocol("Bytes length exceeds buffer"));
        }
        
        let bytes = &self.buf[self.pos..self.pos + len];
        self.pos += len;
        Ok(bytes)
    }
    
    /// 残りの全バイトを読み取り
    pub fn read_remaining(&mut self) -> &[u8] {
        let remaining = &self.buf[self.pos..];
        self.pos = self.buf.len();
        remaining
    }
}

/// プロトコルライター - Kafka構造体からバイナリデータへの変換
pub struct ProtocolWriter {
    buf: BytesMut,
}

impl ProtocolWriter {
    /// 新しいライターを作成
    pub fn new() -> Self {
        Self {
            buf: BytesMut::new(),
        }
    }
    
    /// 指定容量でライターを作成
    pub fn with_capacity(capacity: usize) -> Self {
        Self {
            buf: BytesMut::with_capacity(capacity),
        }
    }
    
    /// u8を書き込み
    pub fn write_u8(&mut self, val: u8) {
        self.buf.put_u8(val);
    }
    
    /// u16 (big-endian) を書き込み
    pub fn write_u16(&mut self, val: u16) {
        self.buf.put_u16(val);
    }
    
    /// u32 (big-endian) を書き込み
    pub fn write_u32(&mut self, val: u32) {
        self.buf.put_u32(val);
    }
    
    /// i16 (big-endian) を書き込み
    pub fn write_i16(&mut self, val: i16) {
        self.buf.put_i16(val);
    }
    
    /// i32 (big-endian) を書き込み
    pub fn write_i32(&mut self, val: i32) {
        self.buf.put_i32(val);
    }
    
    /// Kafkaスタイルの文字列を書き込み
    pub fn write_string(&mut self, val: Option<&str>) {
        match val {
            None => {
                // Null string
                self.write_i16(-1);
            }
            Some(s) => {
                let bytes = s.as_bytes();
                self.write_i16(bytes.len() as i16);
                self.buf.put_slice(bytes);
            }
        }
    }
    
    /// バイト配列を書き込み
    pub fn write_bytes(&mut self, bytes: &[u8]) {
        self.buf.put_slice(bytes);
    }
    
    /// バッファをBytesに変換
    pub fn into_bytes(self) -> Bytes {
        self.buf.freeze()
    }
    
    /// 現在のバッファサイズを取得
    pub fn len(&self) -> usize {
        self.buf.len()
    }
    
    /// バッファが空かどうか
    pub fn is_empty(&self) -> bool {
        self.buf.is_empty()
    }
}

impl Default for ProtocolWriter {
    fn default() -> Self {
        Self::new()
    }
}

impl KafkaRequest {
    /// バイナリデータからKafkaリクエストを解析
    /// 
    /// # Arguments
    /// * `data` - バイナリデータ
    /// 
    /// # Returns
    /// * `BrokerResult<KafkaRequest>` - 解析されたリクエスト
    pub fn parse(data: &[u8]) -> BrokerResult<KafkaRequest> {
        let mut reader = ProtocolReader::new(data);
        
        // リクエスト長を読み取り（最初の4バイト）
        let length = reader.read_u32()?;
        
        if length as usize != data.len() - 4 {
            return Err(BrokerError::invalid_protocol("Invalid request length"));
        }
        
        // ヘッダーを解析
        let api_key = ApiKey::from_code(reader.read_i16()?)?;
        let api_version = ApiVersion(reader.read_i16()?);
        let correlation_id = reader.read_u32()?;
        let client_id = reader.read_string()?;
        
        let header = RequestHeader {
            length,
            api_key,
            api_version,
            correlation_id,
            client_id,
        };
        
        // 残りのデータをボディとして取得
        let body = Bytes::copy_from_slice(reader.read_remaining());
        
        Ok(KafkaRequest { header, body })
    }
}

impl KafkaResponse {
    /// Kafkaレスポンスを作成
    /// 
    /// # Arguments
    /// * `correlation_id` - 相関ID
    /// * `body` - レスポンスボディ
    /// 
    /// # Returns
    /// * `KafkaResponse` - レスポンス
    pub fn new(correlation_id: u32, body: Bytes) -> Self {
        let header = ResponseHeader {
            length: 4 + body.len() as u32, // correlation_id(4) + body
            correlation_id,
        };
        
        Self { header, body }
    }
    
    /// レスポンスをバイナリデータにシリアライズ
    /// 
    /// # Returns
    /// * `Bytes` - シリアライズされたバイナリデータ
    pub fn serialize(&self) -> Bytes {
        let mut writer = ProtocolWriter::with_capacity(8 + self.body.len());
        
        // レスポンス長を書き込み
        writer.write_u32(self.header.length);
        // 相関IDを書き込み
        writer.write_u32(self.header.correlation_id);
        // ボディを書き込み
        writer.write_bytes(&self.body);
        
        writer.into_bytes()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    
    #[test]
    fn test_protocol_reader_writer() {
        let mut writer = ProtocolWriter::new();
        
        // テストデータを書き込み
        writer.write_u32(12345);
        writer.write_i16(-1);
        writer.write_string(Some("test"));
        writer.write_u8(255);
        
        let data = writer.into_bytes();
        let mut reader = ProtocolReader::new(&data);
        
        // データを読み取りテスト
        assert_eq!(reader.read_u32().unwrap(), 12345);
        assert_eq!(reader.read_i16().unwrap(), -1);
        assert_eq!(reader.read_string().unwrap(), Some("test".to_string()));
        assert_eq!(reader.read_u8().unwrap(), 255);
        assert!(!reader.has_remaining());
    }
    
    #[test]
    fn test_null_string() {
        let mut writer = ProtocolWriter::new();
        writer.write_string(None);
        
        let data = writer.into_bytes();
        let mut reader = ProtocolReader::new(&data);
        
        assert_eq!(reader.read_string().unwrap(), None);
    }
} 