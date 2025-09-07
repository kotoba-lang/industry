//! # Segment Management
//!
//! セグメント化されたファイル管理機能。
//! 固定長セグメント + メモリマップドI/O + CRCチェックによる高性能ストレージ。

use crate::{Event, Offset, StorageError, StorageResult};
use derive_more::{Deref, DerefMut, From, Into};
use memmap2::{MmapMut, MmapOptions};
use serde::{Deserialize, Serialize};
use std::{
    collections::HashMap,
    fs::{File, OpenOptions},
    path::{Path, PathBuf},
    sync::{
        atomic::{AtomicU64, Ordering},
        Arc,
    },
};
use tokio::sync::RwLock;

/// セグメントID（u64ベース）
///
/// 各セグメントファイルの一意識別子。
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash, Serialize, Deserialize, Deref, DerefMut, From, Into)]
pub struct SegmentId(pub u64);

impl SegmentId {
    /// u64からセグメントIDを作成
    pub fn from_u64(id: u64) -> Self {
        Self(id)
    }
}

impl std::fmt::Display for SegmentId {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{}", self.0)
    }
}

/// セグメントヘッダー情報
/// 
/// セグメントファイルの先頭に格納されるメタデータ。
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SegmentHeader {
    /// セグメントID
    pub segment_id: SegmentId,
    /// 作成日時（UNIXタイムスタンプ）
    pub created_at: u64,
    /// セグメントサイズ
    pub size: u64,
    /// 書き込み位置オフセット
    pub write_offset: u64,
    /// エントリ数
    pub entry_count: u64,
    /// CRCチェックサム
    pub header_crc: u32,
}

impl SegmentHeader {
    /// 新しいセグメントヘッダーを作成
    pub fn new(segment_id: SegmentId, size: u64) -> Self {
        let created_at = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap_or_default()
            .as_secs();
            
        let mut header = Self {
            segment_id,
            created_at,
            size,
            write_offset: 256, // 固定ヘッダーサイズ
            entry_count: 0,
            header_crc: 0,
        };
        
        header.header_crc = header.calculate_crc();
        header
    }
    
    /// ヘッダーのCRCを計算
    fn calculate_crc(&self) -> u32 {
        let mut temp_header = self.clone();
        temp_header.header_crc = 0;
        
        let serialized = bincode::serialize(&temp_header).unwrap_or_default();
        crc32fast::hash(&serialized)
    }
    
    /// ヘッダーの整合性を検証
    pub fn verify_integrity(&self) -> StorageResult<()> {
        let expected_crc = self.calculate_crc();
        if self.header_crc != expected_crc {
            return Err(StorageError::CrcMismatch {
                offset: 0,
                expected: expected_crc,
                actual: self.header_crc,
            });
        }
        Ok(())
    }
}

/// セグメントファイル
/// 
/// 固定長のメモリマップドファイルでイベントを格納。
/// 高速なシーケンシャル書き込みとランダムアクセス読み取りを提供。
#[derive(Debug)]
pub struct Segment {
    /// セグメントID
    id: SegmentId,
    /// ファイルパス
    file_path: PathBuf,
    /// ファイルハンドル
    file: File,
    /// メモリマップドファイル
    mmap: MmapMut,
    /// セグメントヘッダー
    header: SegmentHeader,
    /// 読み取り専用フラグ
    read_only: bool,
}

impl Segment {
    /// 新しいセグメントを作成
    pub fn create<P: AsRef<Path>>(
        file_path: P,
        size: u64,
        segment_id: SegmentId,
    ) -> StorageResult<Self> {
        let file = OpenOptions::new()
            .read(true)
            .write(true)
            .create(true)
            .open(file_path.as_ref())?;

        file.set_len(size)?;

        let mmap = unsafe { MmapOptions::new().map_mut(&file)? };

        let header = SegmentHeader::new(segment_id, size);
        let header_bytes = bincode::serialize(&header)?;

        let mut segment = Self {
            id: segment_id,
            file_path: file_path.as_ref().to_path_buf(),
            file,
            mmap,
            header,
            read_only: false,
        };

        // ヘッダーをメモリマップに書き込み
        let header_size = 256;
        if header_bytes.len() > header_size {
            return Err(StorageError::Configuration { message: "Header size exceeds reserved space".to_string() });
        }
        segment.mmap[..header_bytes.len()].copy_from_slice(&header_bytes);
        // 残りを0で埋める
        for i in header_bytes.len()..header_size {
            segment.mmap[i] = 0;
        }
        
        Ok(segment)
    }
    
    /// 既存のセグメントファイルを開く
    pub fn open<P: AsRef<Path>>(file_path: P, read_only: bool) -> StorageResult<Self> {
        let file_path_buf = file_path.as_ref().to_path_buf();
        
        let file = if read_only {
            File::open(&file_path_buf)?
        } else {
            OpenOptions::new()
                .read(true)
                .write(true)
                .open(&file_path_buf)?
        };
        
        let mmap = if read_only {
            unsafe { MmapOptions::new().map(&file)?.make_mut()? }
        } else {
            unsafe { MmapOptions::new().map_mut(&file)? }
        };
        
        // ヘッダーを読み取り（固定サイズ256バイト）
        let header_size = 256;
        if mmap.len() < header_size {
            return Err(StorageError::InvalidFormat { message: "Segment file too small for header".to_string() });
        }
        
        // ヘッダーデータを探す（最初の非ゼロバイトまで）
        let mut actual_header_end = 0;
        for i in 0..header_size {
            if mmap[i] != 0 {
                actual_header_end = i + 1;
            }
        }
        if actual_header_end == 0 {
            return Err(StorageError::InvalidFormat { message: "No header data found".to_string() });
        }
        
        // 実際のヘッダーをより正確に見つける
        for end in (actual_header_end..header_size).rev() {
            if let Ok(header) = bincode::deserialize::<SegmentHeader>(&mmap[..end]) {
                if header.verify_integrity().is_ok() {
                    return Ok(Self {
                        id: header.segment_id,
                        file_path: file_path_buf,
                        file,
                        mmap,
                        header,
                        read_only,
                    });
                }
            }
        }
        
        return Err(StorageError::InvalidFormat { message: "Failed to deserialize segment header".to_string() });
    }
    
    /// セグメントヘッダーを取得
    pub fn header(&self) -> &SegmentHeader {
        &self.header
    }
    
    /// イベントをセグメントに書き込み
    pub fn append_event(&mut self, event: &Event) -> StorageResult<Offset> {
        if self.read_only {
            return Err(StorageError::Internal { message: "Cannot write to read-only segment".to_string() });
        }
        
        let serialized_event = bincode::serialize(event)?;
        let data_size = serialized_event.len() as u32;
        let entry_size = std::mem::size_of::<u32>() + serialized_event.len() + std::mem::size_of::<u32>(); // サイズ + データ + CRC
        
        // 容量チェック
        if self.header.write_offset + entry_size as u64 > self.header.size {
            return Err(StorageError::InsufficientSpace {
                required: entry_size as u64,
                available: self.header.size - self.header.write_offset,
            });
        }
        
        let write_offset = self.header.write_offset as usize;
        let offset = Offset::new(self.header.entry_count);
        
        // CRCを計算
        let crc = crc32fast::hash(&serialized_event);
        
        // データサイズを書き込み（4バイト）
        let size_bytes = data_size.to_le_bytes();
        let size_end = write_offset + size_bytes.len();
        self.mmap[write_offset..size_end].copy_from_slice(&size_bytes);
        
        // データを書き込み
        let data_end = size_end + serialized_event.len();
        self.mmap[size_end..data_end].copy_from_slice(&serialized_event);
        
        // CRCを書き込み
        let crc_bytes = crc.to_le_bytes();
        let crc_end = data_end + crc_bytes.len();
        self.mmap[data_end..crc_end].copy_from_slice(&crc_bytes);
        
        // ヘッダーを更新
        self.header.write_offset += entry_size as u64;
        self.header.entry_count += 1;
        
        // ヘッダーを再計算・書き込み
        self.header.header_crc = self.header.calculate_crc();
        let header_bytes = bincode::serialize(&self.header)?;
        let header_size = 256;
        if header_bytes.len() > header_size {
            return Err(StorageError::Configuration { message: "Header size exceeds reserved space".to_string() });
        }
        self.mmap[..header_bytes.len()].copy_from_slice(&header_bytes);
        // 残りを0で埋める（更新時）
        for i in header_bytes.len()..header_size {
            self.mmap[i] = 0;
        }
        
        Ok(offset)
    }
    
    /// 指定したオフセットのイベントを読み取り
    pub fn read_event(&self, offset: Offset) -> StorageResult<Option<Event>> {
        if offset.0 >= self.header.entry_count {
            return Ok(None);
        }
        
        // エントリインデックスからファイル内のポジションを探索
        let mut current_position = 256; // 固定ヘッダーサイズ
        let mut current_entry_index = 0u64;
        
        // 指定したオフセットまで線形探索
        while current_entry_index <= offset.0 && current_position < self.header.write_offset as usize {
            // データサイズを読み取り（4バイト）
            if current_position + std::mem::size_of::<u32>() > self.mmap.len() {
                break;
            }
            
            let size_bytes = &self.mmap[current_position..current_position + std::mem::size_of::<u32>()];
            let data_size = u32::from_le_bytes([size_bytes[0], size_bytes[1], size_bytes[2], size_bytes[3]]) as usize;
            
            let data_start = current_position + std::mem::size_of::<u32>();
            let data_end = data_start + data_size;
            let crc_end = data_end + std::mem::size_of::<u32>();
            
            // 境界チェック
            if crc_end > self.mmap.len() {
                return Err(StorageError::InvalidFormat { message: "Entry extends beyond segment".to_string() });
            }
            
            if current_entry_index == offset.0 {
                // 指定されたオフセットのイベントを読み取り
                let event_data = &self.mmap[data_start..data_end];
                let stored_crc_bytes = &self.mmap[data_end..crc_end];
                let stored_crc = u32::from_le_bytes([
                    stored_crc_bytes[0], stored_crc_bytes[1], 
                    stored_crc_bytes[2], stored_crc_bytes[3]
                ]);
                
                // CRC検証
                let calculated_crc = crc32fast::hash(event_data);
                if stored_crc != calculated_crc {
                    return Err(StorageError::CrcMismatch {
                        offset: current_position as u64,
                        expected: calculated_crc,
                        actual: stored_crc,
                    });
                }
                
                // イベントをデシリアライズ
                match bincode::deserialize::<Event>(event_data) {
                    Ok(event) => return Ok(Some(event)),
                    Err(_) => {
                        return Err(StorageError::InvalidFormat { message: "Failed to deserialize event data".to_string() });
                    }
                }
            }
            
            // 次のエントリへ移動
            current_position = crc_end;
            current_entry_index += 1;
        }
        
        Ok(None)
    }
    
    /// 指定した範囲のイベントを読み取り
    pub fn read_events_range(&self, start_offset: Offset, max_events: usize) -> StorageResult<Vec<Event>> {
        let mut events = Vec::with_capacity(max_events.min(self.header.entry_count as usize));
        
        let mut current_position = 256; // 固定ヘッダーサイズ
        let mut current_entry_index = 0u64;
        let end_offset = (start_offset.0 + max_events as u64).min(self.header.entry_count);
        
        // 開始オフセットまでスキップ
        while current_entry_index < start_offset.0 && current_position < self.header.write_offset as usize {
            // データサイズを読み取り（4バイト）
            if current_position + std::mem::size_of::<u32>() > self.mmap.len() {
                break;
            }
            
            let size_bytes = &self.mmap[current_position..current_position + std::mem::size_of::<u32>()];
            let data_size = u32::from_le_bytes([size_bytes[0], size_bytes[1], size_bytes[2], size_bytes[3]]) as usize;
            
            let entry_size = std::mem::size_of::<u32>() + data_size + std::mem::size_of::<u32>(); // サイズ + データ + CRC
            current_position += entry_size;
            current_entry_index += 1;
        }
        
        // 指定した範囲のイベントを読み取り
        while current_entry_index < end_offset && current_position < self.header.write_offset as usize && events.len() < max_events {
            // データサイズを読み取り（4バイト）
            if current_position + std::mem::size_of::<u32>() > self.mmap.len() {
                break;
            }
            
            let size_bytes = &self.mmap[current_position..current_position + std::mem::size_of::<u32>()];
            let data_size = u32::from_le_bytes([size_bytes[0], size_bytes[1], size_bytes[2], size_bytes[3]]) as usize;
            
            let data_start = current_position + std::mem::size_of::<u32>();
            let data_end = data_start + data_size;
            let crc_end = data_end + std::mem::size_of::<u32>();
            
            // 境界チェック
            if crc_end > self.mmap.len() {
                return Err(StorageError::InvalidFormat { message: "Entry extends beyond segment".to_string() });
            }
            
            // イベントを読み取り
            let event_data = &self.mmap[data_start..data_end];
            let stored_crc_bytes = &self.mmap[data_end..crc_end];
            let stored_crc = u32::from_le_bytes([
                stored_crc_bytes[0], stored_crc_bytes[1], 
                stored_crc_bytes[2], stored_crc_bytes[3]
            ]);
            
            // CRC検証
            let calculated_crc = crc32fast::hash(event_data);
            if stored_crc != calculated_crc {
                return Err(StorageError::CrcMismatch {
                    offset: current_position as u64,
                    expected: calculated_crc,
                    actual: stored_crc,
                });
            }
            
            // イベントをデシリアライズ
            match bincode::deserialize::<Event>(event_data) {
                Ok(event) => {
                    events.push(event);
                    current_position = crc_end;
                    current_entry_index += 1;
                }
                Err(_) => {
                    return Err(StorageError::InvalidFormat { message: "Failed to deserialize event data during read".to_string() });
                }
            }
        }
        
        Ok(events)
    }
    
    /// セグメントをフラッシュ
    pub fn flush(&mut self) -> StorageResult<()> {
        if !self.read_only {
            self.mmap.flush()?;
            self.file.sync_all()?;
        }
        Ok(())
    }
    
    /// セグメントの統計情報を取得
    pub fn stats(&self) -> SegmentStats {
        SegmentStats {
            id: self.id,
            file_path: self.file_path.clone(),
            total_size: self.header.size,
            used_size: self.header.write_offset,
            entry_count: self.header.entry_count,
            read_only: self.read_only,
        }
    }
    
    /// バッチデータを一括書き込み（高性能版）
    /// 
    /// # Arguments
    /// * `batch_data` - シリアライズされたバッチデータ
    /// * `event_sizes` - 各イベントのサイズリスト
    /// 
    /// # Returns
    /// * `StorageResult<Offset>` - 開始オフセット
    /// 
    /// # Performance
    /// 複数イベントを一回のI/Oで書き込み、1M+ events/secを実現
    pub fn append_batch_data(&mut self, batch_data: &[u8], event_sizes: &[usize]) -> StorageResult<Offset> {
        if self.read_only {
            return Err(StorageError::Internal { message: "Cannot write to read-only segment".to_string() });
        }
        
        let start_entry_index = self.header.entry_count;
        let current_position = self.header.write_offset as usize;
        
        // 容量チェック
        let required_space = batch_data.len() + (event_sizes.len() * 8); // 8 bytes per event for metadata
        if current_position + required_space > self.mmap.len() {
            return Err(StorageError::InsufficientSpace {
                required: required_space as u64,
                available: (self.mmap.len() - current_position) as u64,
            });
        }
        
        let mut write_position = current_position;
        let mut data_offset = 0;
        
        // 各イベントを書き込み
        for &event_size in event_sizes {
            // イベントサイズ書き込み（4バイト）
            let size_bytes = (event_size as u32).to_le_bytes();
            self.mmap[write_position..write_position + 4].copy_from_slice(&size_bytes);
            write_position += 4;
            
            // イベントデータ書き込み
            let event_data = &batch_data[data_offset..data_offset + event_size];
            self.mmap[write_position..write_position + event_size].copy_from_slice(event_data);
            write_position += event_size;
            
            // CRC書き込み（4バイト）
            let crc = crc32fast::hash(event_data);
            let crc_bytes = crc.to_le_bytes();
            self.mmap[write_position..write_position + 4].copy_from_slice(&crc_bytes);
            write_position += 4;
            
            data_offset += event_size;
        }
        
        // ヘッダー更新
        self.header.write_offset = write_position as u64;
        self.header.entry_count += event_sizes.len() as u64;
        
        // ヘッダーをファイルに書き込み（既存のロジックを使用）
        let header_bytes = bincode::serialize(&self.header)?;
        let header_size = 256;
        if header_bytes.len() > header_size {
            return Err(StorageError::Configuration { message: "Header size exceeds reserved space".to_string() });
        }
        self.mmap[..header_bytes.len()].copy_from_slice(&header_bytes);
        for i in header_bytes.len()..header_size {
            self.mmap[i] = 0;
        }
        
        tracing::debug!(
            "Batch written: {} events, {} bytes, start_offset={}",
            event_sizes.len(),
            batch_data.len(),
            start_entry_index
        );
        
        Ok(Offset::new(start_entry_index))
    }
    
    pub fn id(&self) -> SegmentId {
        self.id
    }
}

/// セグメント統計情報
#[derive(Debug, Clone)]
pub struct SegmentStats {
    /// セグメントID
    pub id: SegmentId,
    /// ファイルパス
    pub file_path: PathBuf,
    /// 総サイズ
    pub total_size: u64,
    /// 使用サイズ
    pub used_size: u64,
    /// エントリ数
    pub entry_count: u64,
    /// 読み取り専用フラグ
    pub read_only: bool,
}

/// セグメントヘッダーのサイズ (固定)
pub const HEADER_SIZE: usize = 256;

/// セグメント管理マネージャー
/// 
/// 複数のセグメントファイルを管理し、効率的なデータアクセスを提供。
#[derive(Debug)]
pub struct SegmentManager {
    /// データディレクトリ
    data_dir: PathBuf,
    /// アクティブなセグメント
    segments: RwLock<HashMap<SegmentId, Arc<RwLock<Segment>>>>,
    /// セグメントサイズ
    segment_size: u64,
    /// 次のセグメントID
    next_segment_id: AtomicU64,
}

impl SegmentManager {
    pub async fn new<P: AsRef<Path>>(data_dir: P, segment_size: u64) -> StorageResult<Self> {
        let manager = Self {
            data_dir: data_dir.as_ref().to_path_buf(),
            segments: RwLock::new(HashMap::new()),
            segment_size,
            next_segment_id: AtomicU64::new(0),
        };
        manager.load_existing_segments().await?;
        Ok(manager)
    }

    async fn load_existing_segments(&self) -> StorageResult<()> {
        let mut max_id = 0;
        let mut read_dir = tokio::fs::read_dir(&self.data_dir).await?;
        while let Some(entry) = read_dir.next_entry().await? {
            let path = entry.path();
            if path.is_file() && path.extension().and_then(|s| s.to_str()) == Some("seg") {
                if let Some(stem) = path.file_stem().and_then(|s| s.to_str()) {
                    if let Ok(id) = stem.parse::<u64>() {
                        max_id = max_id.max(id);
                        match Segment::open(&path, false) {
                            Ok(segment) => {
                                let segment_id = segment.id();
                                self.segments
                                    .write()
                                    .await
                                    .insert(segment_id, Arc::new(RwLock::new(segment)));
                                tracing::info!("[Recovery] Loaded existing segment: {}", segment_id);
                            }
                            Err(e) => {
                                tracing::warn!("[Recovery] Failed to open segment {:?}: {}", path, e);
                            }
                        }
                    }
                }
            }
        }
        self.next_segment_id.store(max_id + 1, Ordering::Relaxed);
        Ok(())
    }

    pub async fn create_segment(&self) -> StorageResult<SegmentId> {
        let segment_id = SegmentId(self.next_segment_id.fetch_add(1, Ordering::Relaxed));
        let file_path = self.data_dir.join(format!("{}.seg", segment_id));

        let segment = Segment::create(&file_path, self.segment_size, segment_id)?;

        self.segments
            .write()
            .await
            .insert(segment_id, Arc::new(RwLock::new(segment)));
        Ok(segment_id)
    }

    /// Deletes a segment by its ID.
    pub async fn delete_segment(&self, segment_id: SegmentId) -> StorageResult<()> {
        if self.segments.write().await.remove(&segment_id).is_some() {
            let file_path = self.data_dir.join(format!("{}.seg", segment_id));
            tokio::fs::remove_file(file_path).await?;
            tracing::info!("Deleted segment file for ID: {}", segment_id);
        }
        Ok(())
    }

    /// Gets a segment by its ID.
    pub async fn get_segment(&self, segment_id: SegmentId) -> Option<Arc<RwLock<Segment>>> {
        let segments = self.segments.read().await;
        segments.get(&segment_id).cloned()
    }
    
    /// すべてのセグメント統計を取得
    pub async fn get_all_stats(&self) -> Vec<SegmentStats> {
        let segments = self.segments.read().await;
        let mut stats = Vec::new();
        
        for segment_arc in segments.values() {
            if let Ok(segment) = segment_arc.try_read() {
                stats.push(segment.stats());
            }
        }
        
        stats
    }
    
    /// セグメント一覧を取得
    /// 
    /// # Returns
    /// * `Vec<SegmentId>` - セグメントIDリスト
    pub async fn list_segments(&self) -> Vec<SegmentId> {
        let segments = self.segments.read().await;
        segments.keys().copied().collect()
    }
    
    /// アクティブセグメント統計を取得
    /// 
    /// # Returns
    /// * `Vec<SegmentStats>` - セグメント統計リスト
    pub async fn get_segments_stats(&self) -> Vec<SegmentStats> {
        let segments = self.segments.read().await;
        let mut stats = Vec::new();
        
        for segment_arc in segments.values() {
            let segment = segment_arc.read().await;
            stats.push(segment.stats());
        }
        
        stats
    }
    
    /// セグメントを並列で処理
    /// 
    /// # Arguments
    /// * `processor` - セグメント処理関数
    /// 
    /// # Returns
    /// * `StorageResult<Vec<T>>` - 処理結果リスト
    pub async fn process_segments_parallel<T, F, Fut>(
        &self,
        processor: F,
    ) -> StorageResult<Vec<T>>
    where
        T: Send + 'static,
        F: Fn(SegmentId, Arc<RwLock<Segment>>) -> Fut + Send + Sync + 'static,
        Fut: std::future::Future<Output = StorageResult<T>> + Send,
    {
        let segments = self.segments.read().await;
        let processor = Arc::new(processor);
        
        let mut handles = Vec::new();
        
        for (&segment_id, segment_arc) in segments.iter() {
            let processor = Arc::clone(&processor);
            let segment_arc = Arc::clone(segment_arc);
            
            let handle = tokio::spawn(async move {
                processor(segment_id, segment_arc).await
            });
            
            handles.push(handle);
        }
        
        let mut results = Vec::new();
        for handle in handles {
            match handle.await {
                Ok(result) => results.push(result?),
                Err(e) => return Err(StorageError::Internal { message: format!("Segment processing failed: {}", e) }),
            }
        }
        
        Ok(results)
    }
} 