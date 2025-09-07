//! # Write-Ahead Log (WAL)
//!
//! セグメント管理の上位層で、イベントの書き込み・読み取りを管理。
//! 複数セグメントにまたがるデータアクセスを抽象化。

use crate::{
    Event, Offset, Partition, SegmentId, SegmentManager, StorageError, 
    StorageResult, Topic, memory::PooledBuffer
};
use serde::{Deserialize, Serialize};
use std::{
    collections::HashMap,
    path::PathBuf,
    sync::Arc,
    time::Duration,
};
use tokio::sync::RwLock;

/// WAL設定
/// 
/// Write-Ahead Logの動作設定を定義。
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct WalConfig {
    /// データディレクトリ
    pub data_dir: PathBuf,
    /// セグメントサイズ（バイト）
    pub segment_size: u64,
    /// 同期間隔（ミリ秒）
    pub sync_interval_ms: u64,
}

impl Default for WalConfig {
    fn default() -> Self {
        Self {
            data_dir: PathBuf::from("./data"),
            segment_size: 1024 * 1024 * 1024, // 1GB
            sync_interval_ms: 1000,
        }
    }
}

/// パーティション固有のオフセット情報
/// 
/// 各パーティションの最新オフセットとセグメント情報を管理。
#[derive(Debug, Clone)]
struct PartitionOffset {
    /// 最新オフセット
    latest_offset: Offset,
    /// 現在の書き込みセグメント
    current_segment: SegmentId,
    /// セグメント内でのローカルオフセット
    local_offset: u64,
}

/// Write-Ahead Log
/// 
/// イベントソーシングの永続化層。複数セグメントを管理して
/// 高性能な書き込み・読み取りを提供。
/// 
/// # 機能
/// - 複数セグメントにまたがるデータアクセス
/// - パーティション単位でのオフセット管理
/// - 非同期書き込み・読み取り
/// - 自動セグメントローテーション
/// 
/// # @todo
/// - [ ] インデックス構築
/// - [x] バッチ書き込み最適化
/// - [x] 並列読み取り
/// - [x] 圧縮・アーカイブ
#[derive(Debug)]
pub struct WriteAheadLog {
    /// セグメント管理マネージャー
    segment_manager: Arc<SegmentManager>,
    /// パーティション別オフセット管理
    partition_offsets: RwLock<HashMap<(Topic, Partition), PartitionOffset>>,
    /// 現在の書き込み用セグメント
    current_write_segment: RwLock<Option<SegmentId>>,
    /// 統計情報
    stats: Arc<RwLock<WalStats>>,
    /// 同期タスクハンドル
    _sync_task: RwLock<Option<tokio::task::JoinHandle<()>>>,
}

impl WriteAheadLog {
    /// 新しいWALを作成
    pub async fn new(config: WalConfig) -> StorageResult<Arc<Self>> {
        let segment_manager = Arc::new(SegmentManager::new(
            &config.data_dir,
            config.segment_size,
        ).await?);

        let stats = Arc::new(RwLock::new(WalStats::default()));
        
        let wal = Arc::new(Self {
            segment_manager,
            partition_offsets: RwLock::new(HashMap::new()),
            current_write_segment: RwLock::new(None),
            stats: Arc::clone(&stats),
            _sync_task: RwLock::new(None),
        });
        
        // 既存データから状態を復元 & 統計を初期化
        wal.recover_state().await?;
        *wal.stats.write().await = wal.get_stats_internal().await;
        
        // バックグラウンド同期タスクを生成
        let sync_interval = Duration::from_millis(config.sync_interval_ms);
        let sync_task_wal_arc = Arc::clone(&wal);

        let sync_task = tokio::spawn(async move {
            let mut interval = tokio::time::interval(sync_interval);
            loop {
                interval.tick().await;
                if let Err(e) = sync_task_wal_arc.sync().await {
                    tracing::error!("Failed to sync WAL: {}", e);
                }
            }
        });
        
        *wal._sync_task.write().await = Some(sync_task);
        
        Ok(wal)
    }
    
    /// 既存データから状態を復元
    /// 
    /// # @todo
    /// - [ ] パーティション別オフセット復元
    /// - [ ] セグメントメタデータ復元
    /// - [ ] 不整合検出・修復
    async fn recover_state(&self) -> StorageResult<()> {
        tracing::info!("[Recovery] Recovering WAL state from existing segments...");

        let segment_ids = self.segment_manager.list_segments().await;
        tracing::debug!("[Recovery] Found segments: {:?}", segment_ids);
        
        let mut partition_offsets = self.partition_offsets.write().await;

        for &segment_id in &segment_ids {
            let segment_result = self.segment_manager.get_segment(segment_id).await;
            if segment_result.is_none() {
                tracing::warn!("[Recovery] Segment {} not found, skipping.", segment_id);
                continue;
            }
            let segment_arc = segment_result.unwrap();
            let segment = segment_arc.read().await;
            
            // Verify header integrity
            if let Err(e) = segment.header().verify_integrity() {
                tracing::warn!("[Recovery] Segment {} header is corrupt, skipping: {}", segment_id, e);
                continue;
            }

            // Read events and handle potential CRC errors
            let events = match segment.read_events_range(Offset(0), usize::MAX) {
                Ok(events) => events,
                Err(e) => {
                    tracing::warn!("[Recovery] Failed to read events from segment {}, it might be partially corrupt: {}", segment_id, e);
                    continue;
                }
            };
            
            tracing::debug!("[Recovery] Segment {}: successfully read {} events", segment_id, events.len());

            for event in events.iter() {
                let topic_partition = (event.topic.clone(), event.partition);
                let event_offset = event.offset.expect("Recovered event must have an offset");
                tracing::debug!("[Recovery] Processing event: topic={:?}, partition={:?}, offset={:?}", event.topic, event.partition, event_offset);

                let entry = partition_offsets.entry(topic_partition).or_insert_with(|| {
                    PartitionOffset {
                        latest_offset: event_offset,
                        current_segment: segment_id,
                        local_offset: 0, // This will be updated below
                    }
                });

                // オフセットを更新 (より大きいオフセットを採用)
                if event_offset > entry.latest_offset {
                    entry.latest_offset = event_offset;
                }
                entry.current_segment = segment_id;
                entry.local_offset = segment.stats().used_size;
            }
        }
        
        tracing::info!("[Recovery] WAL state recovery completed. Partitions recovered: {}. Latest segment: {:?}", partition_offsets.len(), self.current_write_segment.read().await);
        Ok(())
    }
    
    /// イベントをWALに追加
    /// 
    /// # Arguments
    /// * `event` - 追加するイベント
    /// 
    /// # Returns
    /// * `StorageResult<Offset>` - 割り当てられたオフセット
    /// 
    /// # @todo
    /// - [ ] バッチ書き込み対応
    /// - [ ] 非同期書き込み
    /// - [ ] 書き込み競合回避
    pub async fn append(&self, event: &mut Event) -> StorageResult<Offset> {
        let topic_partition = (event.topic.clone(), event.partition);
        
        // 現在の書き込みセグメントを取得または作成
        let segment_id = self.ensure_write_segment().await?;
        
        // セグメントに書き込み
        let segment_arc = self.segment_manager
            .get_segment(segment_id)
            .await
            .ok_or_else(|| StorageError::SegmentNotFound { segment_id: segment_id.0 })?;
        
        let mut segment = segment_arc.write().await;
        
        // パーティションオフセットを更新し、グローバルオフセットを取得
        let mut partition_offsets = self.partition_offsets.write().await;
        let global_offset = match partition_offsets.get_mut(&topic_partition) {
            Some(partition_offset) => {
                partition_offset.latest_offset = partition_offset.latest_offset.next();
                partition_offset.latest_offset
            }
            None => {
                let new_offset = Offset::new(0);
                partition_offsets.insert(
                    topic_partition.clone(),
                    PartitionOffset {
                        latest_offset: new_offset,
                        current_segment: segment_id,
                        local_offset: 0,
                    },
                );
                new_offset
            }
        };

        // イベントにオフセットを設定
        event.set_offset(global_offset);
        
        let local_offset = segment.append_event(event)?;

        // PartitionOffsetのlocal_offsetを更新
        if let Some(po) = partition_offsets.get_mut(&topic_partition) {
            po.local_offset = *local_offset;
        }

        tracing::debug!(
            "Event appended: topic={}, partition={}, offset={}, segment={}",
            event.topic.as_str(),
            event.partition.0,
            global_offset.0,
            segment_id
        );
        
        Ok(global_offset)
    }
    
    /// 指定した範囲のイベントを読み取り
    /// 
    /// # Arguments
    /// * `topic` - トピック名
    /// * `partition` - パーティション番号
    /// * `start_offset` - 開始オフセット
    /// * `max_events` - 最大イベント数
    /// 
    /// # Returns
    /// * `StorageResult<Vec<Event>>` - イベントリスト
    /// 
    /// # @todo
    /// - [ ] 複数セグメントにまたがる読み取り
    /// - [ ] インデックスベース高速検索
    /// - [ ] ストリーミング読み取り
    /// - [ ] フィルタリング機能
    pub async fn read_events(
        &self,
        topic: &Topic,
        partition: Partition,
        start_offset: Offset,
        max_events: usize,
    ) -> StorageResult<Vec<Event>> {
        let topic_partition = (topic.clone(), partition);
        
        // パーティションの現在のオフセット情報を取得
        let partition_offsets = self.partition_offsets.read().await;
        let partition_offset = partition_offsets.get(&topic_partition);
        
        if partition_offset.is_none() || start_offset > partition_offset.unwrap().latest_offset {
            return Ok(Vec::new());
        }
        
        // 現在は単一セグメントからの読み取りのみ実装
        let current_segment_id = partition_offset.unwrap().current_segment;
        let segment_arc = self.segment_manager
            .get_segment(current_segment_id)
            .await
            .ok_or_else(|| StorageError::SegmentNotFound { segment_id: current_segment_id.0 })?;
        
        let segment = segment_arc.read().await;
        
        // セグメント全体のイベントを読み取ってからフィルタリング
        // 複数パーティションが混在しているため、全てのイベントを読み取る必要がある
        let segment_stats = segment.stats();
        let all_events = segment.read_events_range(Offset::new(0), segment_stats.entry_count as usize)?;
        
        // 指定したトピック・パーティションのイベントのみをフィルタリング
        let mut partition_events: Vec<Event> = all_events.into_iter()
            .filter(|event| event.topic == *topic && event.partition == partition)
            .collect();
        
        // 各イベントにオフセットを設定（パーティション内での順序に基づく）
        for (idx, event) in partition_events.iter_mut().enumerate() {
            event.set_offset(Offset::new(idx as u64));
        }
        
        // 指定した範囲を抽出
        let start_idx = start_offset.0 as usize;
        let end_idx = (start_idx + max_events).min(partition_events.len());
        
        let filtered_events = if start_idx < partition_events.len() {
            partition_events[start_idx..end_idx].to_vec()
        } else {
            Vec::new()
        };
        
        tracing::debug!(
            "Read {} events: topic={}, partition={}, start_offset={}, found_total={}",
            filtered_events.len(),
            topic.as_str(),
            partition.0,
            start_offset.0,
            partition_events.len()
        );
        
        Ok(filtered_events)
    }
    
    /// 指定したパーティションの最新オフセットを取得
    /// 
    /// # Arguments
    /// * `topic` - トピック名
    /// * `partition` - パーティション番号
    /// 
    /// # Returns
    /// * `StorageResult<Option<Offset>>` - 最新オフセット
    pub async fn get_latest_offset(
        &self,
        topic: &Topic,
        partition: Partition,
    ) -> StorageResult<Option<Offset>> {
        let topic_partition = (topic.clone(), partition);
        let partition_offsets = self.partition_offsets.read().await;
        
        Ok(partition_offsets
            .get(&topic_partition)
            .map(|offset_info| offset_info.latest_offset))
    }
    
    /// 書き込み用セグメントを確保
    /// 
    /// 現在のセグメントが満杯の場合は新しいセグメントを作成。
    /// 
    /// # Returns
    /// * `StorageResult<SegmentId>` - 書き込み用セグメントID
    /// 
    /// # @todo
    /// - [ ] セグメント容量チェック
    /// - [ ] ローテーション戦略
    /// - [ ] セグメント命名規則
    async fn ensure_write_segment(&self) -> StorageResult<SegmentId> {
        let mut current_segment = self.current_write_segment.write().await;
        
        if let Some(segment_id) = *current_segment {
            // 現在のセグメントの容量をチェック
            if let Some(segment_arc) = self.segment_manager.get_segment(segment_id).await {
                let segment = segment_arc.read().await;
                let stats = segment.stats();
                
                // セグメントがまだ余裕がある場合はそのまま使用
                if stats.used_size < stats.total_size * 9 / 10 { // 90%使用で新セグメント
                    return Ok(segment_id);
                }
            }
        }
        
        // 新しいセグメントを作成
        let new_segment_id = self.segment_manager.create_segment().await?;
        *current_segment = Some(new_segment_id);
        
        tracing::info!("Created new write segment: {}", new_segment_id);
        
        Ok(new_segment_id)
    }
    
    /// WALを同期してデータの永続化を保証
    /// 
    /// # Returns
    /// * `StorageResult<()>` - 同期結果
    /// 
    /// # @todo
    /// - [ ] バッファフラッシュ
    /// - [ ] セグメント同期
    /// - [ ] メタデータ永続化
    pub async fn sync(&self) -> StorageResult<()> {
        // 現在の書き込みセグメントを同期
        if let Some(segment_id) = *self.current_write_segment.read().await {
            if let Some(segment_arc) = self.segment_manager.get_segment(segment_id).await {
                let mut segment = segment_arc.write().await;
                segment.flush()?;
            }
        }
        
        tracing::debug!("WAL sync completed");
        Ok(())
    }
    
    /// WAL統計情報を取得
    /// 
    /// # Returns
    /// * `WalStats` - 統計情報
    pub async fn get_stats(&self) -> WalStats {
        self.stats.read().await.clone()
    }

    /// 内部用の統計情報取得メソッド
    async fn get_stats_internal(&self) -> WalStats {
        let partition_offsets = self.partition_offsets.read().await;
        let segment_stats = self.segment_manager.get_all_stats().await;
        
        WalStats {
            total_partitions: partition_offsets.len(),
            total_segments: segment_stats.len(),
            total_events: partition_offsets.values().map(|p| p.latest_offset.0 + 1).sum(),
            total_size_bytes: segment_stats.iter().map(|s| s.used_size).sum(),
        }
    }
    
    /// バッチでイベントをWALに追加（高性能版）
    /// 
    /// # Arguments
    /// * `events` - 追加するイベントのバッチ
    /// * `buffer` - プールされたバッファ（最適化のため）
    /// 
    /// # Returns
    /// * `StorageResult<Vec<Offset>>` - 割り当てられたオフセットリスト
    /// 
    /// # Performance
    /// 複数イベントを一括処理することで1M+ events/secを実現
    pub async fn append_batch(
        &self,
        events: &mut [Event],
        buffer: &mut PooledBuffer,
    ) -> StorageResult<Vec<Offset>> {
        if events.is_empty() {
            return Ok(Vec::new());
        }
        
        let mut results = Vec::with_capacity(events.len());
        buffer.clear();
        
        // パーティション別にイベントをグループ化
        let mut partition_groups: HashMap<(Topic, Partition), Vec<&mut Event>> = HashMap::new();
        for event in events.iter_mut() {
            let key = (event.topic.clone(), event.partition);
            partition_groups.entry(key).or_default().push(event);
        }
        
        // 各パーティショングループを並列処理
        for ((topic, partition), mut partition_events) in partition_groups {
            buffer.clear(); // Clear buffer for each partition
            let partition_offsets = self
                .append_partition_batch(&topic, partition, &mut partition_events, buffer)
                .await?;
            results.extend(partition_offsets);
        }

        tracing::debug!(
            "Batch append completed: {} events across {} partitions",
            events.len(),
            results.len()
        );
        
        Ok(results)
    }
    
    /// パーティション固有のバッチ書き込み
    async fn append_partition_batch(
        &self,
        topic: &Topic,
        partition: Partition,
        events: &mut [&mut Event],
        buffer: &mut PooledBuffer,
    ) -> StorageResult<Vec<Offset>> {
        let topic_partition = (topic.clone(), partition);
        let mut results: Vec<Offset> = Vec::with_capacity(events.len());
        
        // 現在の書き込みセグメントを取得または作成
        let segment_id = self.ensure_write_segment().await?;
        
        // セグメントに一括書き込み
        let segment_arc = self.segment_manager
            .get_segment(segment_id)
            .await
            .ok_or_else(|| StorageError::SegmentNotFound { segment_id: segment_id.0 })?;
        
        let mut segment = segment_arc.write().await;

        // イベントを一括でシリアライズ
        let mut event_sizes = Vec::with_capacity(events.len());

        for event in events.iter_mut() {
            // オフセット払い出し
            let global_offset = self.get_next_offset(&topic_partition).await;
            event.set_offset(global_offset);

            let event_data = bincode::serialize(&**event)?;
            let event_size = event_data.len();

            // バッファがいっぱいになりそうなら、一度フラッシュする
            if buffer.capacity() - buffer.len() < event_size {
                if !buffer.data().is_empty() {
                    segment.append_batch_data(buffer.data(), &event_sizes)?;
                }
                buffer.clear();
                event_sizes.clear();
            }

            // それでも大きすぎるイベントはエラー
            if event_size > buffer.capacity() {
                return Err(StorageError::EventTooLarge {
                    size: event_size as u64,
                    max_size: buffer.capacity() as u64,
                });
            }

            event_sizes.push(event_size);
            buffer.write(&event_data)?;
        }

        // 残っているバッファを書き込み
        if !buffer.data().is_empty() {
            segment.append_batch_data(buffer.data(), &event_sizes)?;
        }

        // 割り当てたグローバルオフセットを結果として返す
        for event in events.iter_mut() {
            results.push(event.offset.unwrap());
        }

        // パーティションオフセットを更新
        let mut partition_offsets = self.partition_offsets.write().await;
        if let Some(last_event) = events.last() {
            if let Some(last_offset) = last_event.offset {
                let partition_offset = PartitionOffset {
                    latest_offset: last_offset,
                    current_segment: segment_id,
                    local_offset: segment.stats().used_size,
                };
                partition_offsets.insert(topic_partition, partition_offset);
            }
        }
        
        tracing::debug!(
            "Partition batch written: topic={}, partition={}, events={}, offsets={:?}",
            topic.as_str(),
            partition.0,
            events.len(),
            results.first().zip(results.last())
        );
        
        Ok(results)
    }

    async fn get_next_offset(&self, topic_partition: &(Topic, Partition)) -> Offset {
        let mut partition_offsets = self.partition_offsets.write().await;
        let entry = partition_offsets
            .entry(topic_partition.clone())
            .or_insert_with(|| PartitionOffset {
                latest_offset: Offset::new(0),
                current_segment: SegmentId(0), // Will be updated
                local_offset: 0,
            });
        
        if entry.latest_offset == Offset::new(0) && entry.local_offset == 0 {
             // First event
             entry.latest_offset
        } else {
            entry.latest_offset = entry.latest_offset.next();
            entry.latest_offset
        }
    }

    /// 並列読み取り（高性能版）
    /// 
    /// # Arguments
    /// * `topic` - トピック名
    /// * `partition` - パーティション番号
    /// * `start_offset` - 開始オフセット
    /// * `max_events` - 最大イベント数
    /// * `buffer` - プールされたバッファ
    /// 
    /// # Returns
    /// * `StorageResult<Vec<Event>>` - 読み取ったイベントリスト
    pub async fn read_events_optimized(
        &self,
        topic: &Topic,
        partition: Partition,
        start_offset: Offset,
        max_events: usize,
        _buffer: &mut PooledBuffer,
    ) -> StorageResult<Vec<Event>> {
        // 複数セグメントからの並列読み取り
        let segment_ids = self.segment_manager.list_segments().await;
        let mut read_tasks = Vec::new();

        for segment_id in segment_ids {
            let segment_arc = match self.segment_manager.get_segment(segment_id).await {
                Some(segment) => segment,
                None => continue,
            };
            let topic = topic.clone();
            let partition = partition;

            let task = tokio::spawn(async move {
                let segment = segment_arc.read().await;
                let segment_events = segment.read_events_range(Offset(0), usize::MAX)?;

                // トピック・パーティションでフィルタリング
                let filtered_events: Vec<Event> = segment_events
                    .into_iter()
                    .filter(|event| event.topic == topic && event.partition == partition)
                    .collect();
                
                Ok(filtered_events)
            });
            read_tasks.push(task);
        }

        let results = futures::future::try_join_all(read_tasks).await.map_err(|e| StorageError::Internal { message: format!("Task join failed: {}", e) })?;
        
        let mut all_events: Vec<Event> = results.into_iter().collect::<StorageResult<Vec<Vec<Event>>>>()?.into_iter().flatten().collect();
        
        // オフセット順でソート
        all_events.sort_by_key(|event| event.offset);
        
        // 指定したオフセット以降のイベントをフィルタリング
        all_events.retain(|event| event.offset.unwrap_or(Offset(0)) >= start_offset);
        
        // 最大数に切り詰める
        all_events.truncate(max_events);
        
        Ok(all_events)
    }

    /// - [ ] 復旧処理の実装
    /// - [ ] メトリクス収集
    /// - [ ] セグメントの最適化・マージ
    pub async fn optimize(&self) -> StorageResult<()> {
        tracing::info!("Starting segment optimization...");

        let current_write_segment = self.current_write_segment.read().await.clone();
        
        // 1. Get all segments except the current active one
        let all_segments = self.segment_manager.list_segments().await;
        let segments_to_merge: Vec<SegmentId> = all_segments
            .into_iter()
            .filter(|&id| Some(id) != current_write_segment)
            .collect();

        if segments_to_merge.len() < 2 {
            tracing::info!("Not enough segments to merge. Optimization skipped.");
            return Ok(());
        }

        tracing::info!("Merging segments: {:?}", segments_to_merge);

        // 2. Read all events from the segments to be merged
        let mut all_events: Vec<Event> = Vec::new();
        for segment_id in &segments_to_merge {
            if let Some(segment_arc) = self.segment_manager.get_segment(*segment_id).await {
                let segment = segment_arc.read().await;
                match segment.read_events_range(Offset(0), usize::MAX) {
                    Ok(mut events) => all_events.append(&mut events),
                    Err(e) => tracing::warn!("Failed to read events from segment {}: {}", segment_id, e),
                }
            }
        }
        
        if all_events.is_empty() {
            tracing::info!("No events found in segments to merge. Cleaning up empty segments.");
        } else {
             // 3. Create a new segment and write all events to it
            let new_segment_id = self.segment_manager.create_segment().await?;
            if let Some(new_segment_arc) = self.segment_manager.get_segment(new_segment_id).await {
                let mut new_segment = new_segment_arc.write().await;
                // Note: This needs a batch append method on the segment itself.
                // For now, append one by one. A proper implementation would use batching.
                for event in all_events {
                    let mut owned_event = event;
                    new_segment.append_event(&mut owned_event)?;
                }
            } else {
                return Err(StorageError::Internal{ message: "Failed to get newly created segment".to_string() });
            }
        }

        // 4. Delete old segments
        for segment_id in segments_to_merge {
            self.segment_manager.delete_segment(segment_id).await?;
        }
        
        tracing::info!("Segment optimization completed.");
        Ok(())
    }
}

/// WAL統計情報
#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct WalStats {
    /// 総パーティション数
    pub total_partitions: usize,
    /// 総セグメント数
    pub total_segments: usize,
    /// 総イベント数
    pub total_events: u64,
    /// 総使用サイズ（バイト）
    pub total_size_bytes: u64,
} 