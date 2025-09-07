// Kafka REST API Client with Enhanced LocalStorage Mock
// 将来的にはTauriバックエンドで @confluentinc/kafka-javascript を使用予定

// データ型の定義
export interface KafkaNote {
  id: string;
  title: string;
  content?: string;
  userId: string;
  tags?: string[];
  createdAt: string;
  updatedAt: string;
  deviceId: string;
  version: number;
  isDeleted: boolean;
  syncStatus: 'pending' | 'synced' | 'failed';
}

export interface KafkaUser {
  id: string;
  name: string;
  email?: string;
  iCloudId?: string;
  createdAt: string;
  isActive: boolean;
}

export interface KafkaEvent {
  id: string;
  type: 'note.created' | 'note.updated' | 'note.deleted' | 'user.created' | 'sync.completed';
  entityId: string;
  userId: string;
  deviceId: string;
  timestamp: string;
  data?: any;
}

// トピック名の定義
export const TOPICS = {
  NOTES: 'prehender-notes',
  USERS: 'prehender-users',
  EVENTS: 'prehender-events',
  SYNC: 'prehender-sync'
} as const;

// Enhanced LocalStorage Kafka Mock Client
export class KafkaRestClient {
  private deviceId: string;
  
  constructor() {
    this.deviceId = this.getOrCreateDeviceId();
  }

  private getOrCreateDeviceId(): string {
    // SSR対応: ブラウザ環境でのみlocalStorageを使用
    if (typeof window === 'undefined') {
      return 'server-device-' + Date.now();
    }
    
    let deviceId = localStorage.getItem('kafka-device-id');
    if (!deviceId) {
      deviceId = crypto.randomUUID();
      localStorage.setItem('kafka-device-id', deviceId);
    }
    return deviceId;
  }

  private getStorageKey(topic: string): string {
    return `kafka-mock-${topic}`;
  }

  private getMetadataKey(topic: string): string {
    return `kafka-metadata-${topic}`;
  }

  // トピックメタデータを管理
  private updateTopicMetadata(topic: string, recordCount: number): void {
    // SSR対応: ブラウザ環境でのみlocalStorageを使用
    if (typeof window === 'undefined') return;
    
    const metadata = {
      topic_name: topic,
      partition_count: 1,
      replication_factor: 1,
      record_count: recordCount,
      last_updated: new Date().toISOString(),
      device_id: this.deviceId
    };
    localStorage.setItem(this.getMetadataKey(topic), JSON.stringify(metadata));
  }

  // メッセージを送信（LocalStorageに保存）
  async produceRecord(topic: string, key: string, value: any): Promise<boolean> {
    try {
      // SSR対応: ブラウザ環境でのみlocalStorageを使用
      if (typeof window === 'undefined') {
        console.log(`⚠️ [KAFKA MOCK] Server-side produce ignored for topic ${topic}`);
        return true;
      }
      
      const storageKey = this.getStorageKey(topic);
      const existingData = localStorage.getItem(storageKey);
      const records = existingData ? JSON.parse(existingData) : [];
      
      // 新しいレコードを作成
      const record = {
        key,
        value,
        timestamp: new Date().toISOString(),
        offset: records.length,
        partition: 0,
        deviceId: this.deviceId,
        headers: {
          'x-device-id': this.deviceId,
          'x-timestamp': Date.now().toString()
        }
      };
      
      records.push(record);
      
      // 最新1000件のみ保持（パフォーマンス対策）
      if (records.length > 1000) {
        records.splice(0, records.length - 1000);
        // オフセットを再計算
        records.forEach((r, index) => r.offset = index);
      }
      
      localStorage.setItem(storageKey, JSON.stringify(records));
      this.updateTopicMetadata(topic, records.length);
      
      console.log(`✅ [KAFKA MOCK] Record produced to topic ${topic}:`, { 
        key, 
        offset: record.offset,
        partition: record.partition,
        deviceId: this.deviceId
      });
      
      // イベント配信をシミュレート
      this.broadcastTopicEvent(topic, 'record.produced', record);
      
      return true;
    } catch (error) {
      console.error('Error producing record:', error);
      return false;
    }
  }

  // レコードを消費（LocalStorageから読み取り）
  async consumeRecords(topic: string, fromOffset: number = 0): Promise<any[]> {
    try {
      // SSR対応: ブラウザ環境でのみlocalStorageを使用
      if (typeof window === 'undefined') {
        return [];
      }
      
      const storageKey = this.getStorageKey(topic);
      const existingData = localStorage.getItem(storageKey);
      const allRecords = existingData ? JSON.parse(existingData) : [];
      
      // 指定されたオフセット以降のレコードを返す
      const records = allRecords.filter(record => record.offset >= fromOffset);
      
      console.log(`📖 [KAFKA MOCK] Consuming ${records.length} records from topic ${topic} (from offset ${fromOffset})`);
      return records;
    } catch (error) {
      console.error('Error consuming records:', error);
      return [];
    }
  }

  // 最新のレコードのみを取得（重複排除付き）
  async getLatestRecords(topic: string): Promise<any[]> {
    try {
      const allRecords = await this.consumeRecords(topic);
      const latestByKey = new Map();
      
      // キーごとに最新のレコードのみを保持
      allRecords.forEach(record => {
        const existing = latestByKey.get(record.key);
        if (!existing || record.offset > existing.offset) {
          latestByKey.set(record.key, record);
        }
      });
      
      return Array.from(latestByKey.values());
    } catch (error) {
      console.error('Error getting latest records:', error);
      return [];
    }
  }

  // イベントブロードキャスト（将来的にWebSocketで置き換え可能）
  private broadcastTopicEvent(topic: string, eventType: string, data: any): void {
    // SSR対応: ブラウザ環境でのみイベント配信
    if (typeof window === 'undefined') return;
    
    const event = new CustomEvent('kafka-topic-event', {
      detail: { topic, eventType, data, timestamp: new Date().toISOString() }
    });
    window.dispatchEvent(event);
  }

  // イベントリスナーを追加
  addEventListener(callback: (event: CustomEvent) => void): () => void {
    // SSR対応: ブラウザ環境でのみリスナー追加
    if (typeof window === 'undefined') {
      return () => {}; // 空の関数を返す
    }
    
    window.addEventListener('kafka-topic-event', callback);
    return () => window.removeEventListener('kafka-topic-event', callback);
  }

  // 接続テスト（改良版）
  async testConnection(): Promise<{ success: boolean; error?: string; deviceId?: string }> {
    try {
      // SSR対応: サーバーサイドでは常に成功を返す
      if (typeof window === 'undefined') {
        return { 
          success: true, 
          deviceId: this.deviceId,
          error: 'Server-side environment - localStorage not available'
        };
      }
      
      // LocalStorageの可用性をテスト
      const testKey = 'kafka-connection-test';
      const testValue = Date.now().toString();
      localStorage.setItem(testKey, testValue);
      const retrieved = localStorage.getItem(testKey);
      localStorage.removeItem(testKey);
      
      if (retrieved === testValue) {
        console.log('✅ [KAFKA MOCK] Connection successful - LocalStorage available');
        console.log(`🔗 [KAFKA MOCK] Device ID: ${this.deviceId}`);
        return { 
          success: true, 
          deviceId: this.deviceId 
        };
      } else {
        return { success: false, error: 'LocalStorage not available' };
      }
    } catch (error) {
      console.error('Kafka connection test failed:', error);
      return { 
        success: false, 
        error: error instanceof Error ? error.message : 'Unknown error' 
      };
    }
  }

  // トピック情報を取得
  async getTopicInfo(topic: string): Promise<any> {
    // SSR対応: ブラウザ環境でのみlocalStorageを使用
    if (typeof window === 'undefined') {
      return {
        topic_name: topic,
        partition_count: 1,
        replication_factor: 1,
        record_count: 0,
        last_updated: new Date().toISOString(),
        device_id: this.deviceId,
        mock: true,
        ssr: true
      };
    }
    
    const metadataKey = this.getMetadataKey(topic);
    const existingMetadata = localStorage.getItem(metadataKey);
    
    if (existingMetadata) {
      return JSON.parse(existingMetadata);
    }
    
    // デフォルトメタデータ
    const defaultMetadata = {
      topic_name: topic,
      partition_count: 1,
      replication_factor: 1,
      record_count: 0,
      last_updated: new Date().toISOString(),
      device_id: this.deviceId,
      mock: true
    };
    
    localStorage.setItem(metadataKey, JSON.stringify(defaultMetadata));
    return defaultMetadata;
  }

  // 全トピックの統計情報を取得
  async getAllTopicsInfo(): Promise<Record<string, any>> {
    const info: Record<string, any> = {};
    for (const topic of Object.values(TOPICS)) {
      info[topic] = await this.getTopicInfo(topic);
    }
    return info;
  }

  // デバッグ用：全データをクリア
  async clearAllTopics(): Promise<void> {
    // SSR対応: ブラウザ環境でのみlocalStorageを使用
    if (typeof window === 'undefined') {
      console.log('⚠️ [KAFKA MOCK] Cannot clear topics in server-side environment');
      return;
    }
    
    Object.values(TOPICS).forEach(topic => {
      localStorage.removeItem(this.getStorageKey(topic));
      localStorage.removeItem(this.getMetadataKey(topic));
    });
    console.log('🗑️ [KAFKA MOCK] All topics cleared');
  }

  // デバッグ用：トピック内容を表示
  async debugTopic(topic: string): Promise<void> {
    const records = await this.consumeRecords(topic);
    const metadata = await this.getTopicInfo(topic);
    console.log(`📊 [KAFKA MOCK] Topic ${topic}:`, { metadata, records });
  }

  // データエクスポート（バックアップ用）
  async exportData(): Promise<string> {
    const data: Record<string, any> = {
      deviceId: this.deviceId,
      exportedAt: new Date().toISOString(),
      topics: {}
    };
    
    for (const topic of Object.values(TOPICS)) {
      const records = await this.consumeRecords(topic);
      const metadata = await this.getTopicInfo(topic);
      data.topics[topic] = { records, metadata };
    }
    
    return JSON.stringify(data, null, 2);
  }

  // データインポート（復元用）
  async importData(jsonData: string): Promise<boolean> {
    try {
      // SSR対応: ブラウザ環境でのみlocalStorageを使用
      if (typeof window === 'undefined') {
        console.log('⚠️ [KAFKA MOCK] Cannot import data in server-side environment');
        return false;
      }
      
      const data = JSON.parse(jsonData);
      
      for (const [topic, topicData] of Object.entries(data.topics as Record<string, any>)) {
        if (Object.values(TOPICS).includes(topic as any)) {
          localStorage.setItem(this.getStorageKey(topic), JSON.stringify(topicData.records));
          localStorage.setItem(this.getMetadataKey(topic), JSON.stringify(topicData.metadata));
        }
      }
      
      console.log('📥 [KAFKA MOCK] Data imported successfully');
      return true;
    } catch (error) {
      console.error('Error importing data:', error);
      return false;
    }
  }
}

// シングルトンインスタンス
let kafkaClient: KafkaRestClient | null = null;

export function getKafkaRestClient(): KafkaRestClient {
  if (!kafkaClient) {
    kafkaClient = new KafkaRestClient();
  }
  return kafkaClient;
}

// ノート管理クラス（改良版）
export class KafkaNotesManager {
  private static client = getKafkaRestClient();

  // ノートを作成
  static async createNote(note: Omit<KafkaNote, 'id' | 'createdAt' | 'updatedAt' | 'version'>): Promise<KafkaNote> {
    const newNote: KafkaNote = {
      ...note,
      id: crypto.randomUUID(),
      createdAt: new Date().toISOString(),
      updatedAt: new Date().toISOString(),
      version: 1,
    };

    const success = await this.client.produceRecord(TOPICS.NOTES, newNote.id, newNote);
    
    if (success) {
      // イベントも送信
      const event: KafkaEvent = {
        id: crypto.randomUUID(),
        type: 'note.created',
        entityId: newNote.id,
        userId: newNote.userId,
        deviceId: newNote.deviceId,
        timestamp: new Date().toISOString(),
        data: { title: newNote.title, tags: newNote.tags },
      };
      await this.client.produceRecord(TOPICS.EVENTS, event.id, event);
    }

    return newNote;
  }

  // ノートを更新
  static async updateNote(id: string, updates: Partial<KafkaNote>): Promise<boolean> {
    try {
      // 既存のノートを検索
      const latestRecords = await this.client.getLatestRecords(TOPICS.NOTES);
      let existingNote: KafkaNote | null = null;
      
      for (const record of latestRecords) {
        if (record.key === id) {
          existingNote = record.value;
          break;
        }
      }
      
      if (!existingNote) {
        console.warn(`Note ${id} not found for update`);
        return false;
      }

      const updatedNote: KafkaNote = {
        ...existingNote,
        ...updates,
        id,
        updatedAt: new Date().toISOString(),
        version: (existingNote.version || 0) + 1,
        syncStatus: 'pending' as const,
      };

      const success = await this.client.produceRecord(TOPICS.NOTES, id, updatedNote);

      if (success && updates.userId && updates.deviceId) {
        // イベントも送信
        const event: KafkaEvent = {
          id: crypto.randomUUID(),
          type: 'note.updated',
          entityId: id,
          userId: updates.userId,
          deviceId: updates.deviceId,
          timestamp: new Date().toISOString(),
          data: updates,
        };
        await this.client.produceRecord(TOPICS.EVENTS, event.id, event);
      }

      return success;
    } catch (error) {
      console.error('Error updating note:', error);
      return false;
    }
  }

  // ノートを削除（論理削除）
  static async deleteNote(id: string, userId: string, deviceId: string): Promise<boolean> {
    try {
      // 既存のノートを検索
      const latestRecords = await this.client.getLatestRecords(TOPICS.NOTES);
      let existingNote: KafkaNote | null = null;
      
      for (const record of latestRecords) {
        if (record.key === id) {
          existingNote = record.value;
          break;
        }
      }
      
      if (!existingNote) {
        console.warn(`Note ${id} not found for deletion`);
        return false;
      }

      const deletedNote: KafkaNote = {
        ...existingNote,
        isDeleted: true,
        updatedAt: new Date().toISOString(),
        syncStatus: 'pending' as const,
      };

      const success = await this.client.produceRecord(TOPICS.NOTES, id, deletedNote);

      if (success) {
        // イベントも送信
        const event: KafkaEvent = {
          id: crypto.randomUUID(),
          type: 'note.deleted',
          entityId: id,
          userId,
          deviceId,
          timestamp: new Date().toISOString(),
        };
        await this.client.produceRecord(TOPICS.EVENTS, event.id, event);
      }

      return success;
    } catch (error) {
      console.error('Error deleting note:', error);
      return false;
    }
  }

  // ノートを取得（改良版）
  static async fetchNotes(userId?: string): Promise<KafkaNote[]> {
    try {
      const latestRecords = await this.client.getLatestRecords(TOPICS.NOTES);
      const notes: KafkaNote[] = [];

      for (const record of latestRecords) {
        const note: KafkaNote = record.value;
        if (!userId || note.userId === userId) {
          notes.push(note);
        }
      }

      const filteredNotes = notes
        .filter(note => !note.isDeleted)
        .sort((a, b) => new Date(b.updatedAt).getTime() - new Date(a.updatedAt).getTime());

      console.log(`📝 [KAFKA MOCK] Fetched ${filteredNotes.length} notes for user: ${userId || 'all'}`);
      return filteredNotes;
    } catch (error) {
      console.error('Error fetching notes:', error);
      return [];
    }
  }
}

// ユーザー管理クラス（改良版）
export class KafkaUsersManager {
  private static client = getKafkaRestClient();

  // ユーザーを作成
  static async createUser(user: Omit<KafkaUser, 'id' | 'createdAt'>): Promise<KafkaUser> {
    const newUser: KafkaUser = {
      ...user,
      id: crypto.randomUUID(),
      createdAt: new Date().toISOString(),
    };

    const success = await this.client.produceRecord(TOPICS.USERS, newUser.id, newUser);

    if (success) {
      // イベントも送信
      const event: KafkaEvent = {
        id: crypto.randomUUID(),
        type: 'user.created',
        entityId: newUser.id,
        userId: newUser.id,
        deviceId: '',
        timestamp: new Date().toISOString(),
        data: { name: newUser.name, email: newUser.email },
      };
      await this.client.produceRecord(TOPICS.EVENTS, event.id, event);
    }

    return newUser;
  }

  // ユーザーを取得
  static async fetchUsers(): Promise<KafkaUser[]> {
    try {
      const latestRecords = await this.client.getLatestRecords(TOPICS.USERS);
      const users: KafkaUser[] = [];

      for (const record of latestRecords) {
        const user: KafkaUser = record.value;
        users.push(user);
      }

      const activeUsers = users
        .filter(user => user.isActive)
        .sort((a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime());

      console.log(`👥 [KAFKA MOCK] Fetched ${activeUsers.length} users`);
      return activeUsers;
    } catch (error) {
      console.error('Error fetching users:', error);
      return [];
    }
  }
}

// 同期管理クラス（改良版）
export class KafkaSyncManager {
  private static client = getKafkaRestClient();

  // 同期完了を通知
  static async markSyncCompleted(userId: string, deviceId: string, syncType: string = 'kafka'): Promise<boolean> {
    const syncData = {
      id: crypto.randomUUID(),
      userId,
      deviceId,
      syncType,
      timestamp: new Date().toISOString(),
      status: 'completed',
    };

    const success = await this.client.produceRecord(TOPICS.SYNC, syncData.id, syncData);

    if (success) {
      // イベントも送信
      const event: KafkaEvent = {
        id: crypto.randomUUID(),
        type: 'sync.completed',
        entityId: syncData.id,
        userId,
        deviceId,
        timestamp: new Date().toISOString(),
        data: { syncType },
      };
      await this.client.produceRecord(TOPICS.EVENTS, event.id, event);
    }

    return success;
  }

  // イベントを取得
  static async fetchEvents(userId?: string): Promise<KafkaEvent[]> {
    try {
      const latestRecords = await this.client.getLatestRecords(TOPICS.EVENTS);
      const events: KafkaEvent[] = [];

      for (const record of latestRecords) {
        const event: KafkaEvent = record.value;
        if (!userId || event.userId === userId) {
          events.push(event);
        }
      }

      const sortedEvents = events.sort((a, b) => 
        new Date(b.timestamp).getTime() - new Date(a.timestamp).getTime()
      );

      console.log(`📅 [KAFKA MOCK] Fetched ${sortedEvents.length} events for user: ${userId || 'all'}`);
      return sortedEvents;
    } catch (error) {
      console.error('Error fetching events:', error);
      return [];
    }
  }
}

// 接続テスト関数
export async function testKafkaConnection(): Promise<{ success: boolean; error?: string; deviceId?: string }> {
  const client = getKafkaRestClient();
  return await client.testConnection();
}

// デバッグ用ユーティリティ（改良版）
export const KafkaDebugUtils = {
  // 全トピックをクリア
  clearAll: async () => {
    const client = getKafkaRestClient();
    await client.clearAllTopics();
  },
  
  // トピック内容を表示
  debugTopic: async (topic: string) => {
    const client = getKafkaRestClient();
    await client.debugTopic(topic);
  },
  
  // 全トピックの統計を表示
  showStats: async () => {
    const client = getKafkaRestClient();
    const stats = await client.getAllTopicsInfo();
    console.log('📊 [KAFKA MOCK] All Topics Stats:', stats);
    return stats;
  },

  // データをエクスポート
  exportData: async () => {
    const client = getKafkaRestClient();
    return await client.exportData();
  },

  // データをインポート
  importData: async (jsonData: string) => {
    const client = getKafkaRestClient();
    return await client.importData(jsonData);
  },

  // リアルタイムイベントリスナー
  addEventListenter: (callback: (event: CustomEvent) => void) => {
    const client = getKafkaRestClient();
    return client.addEventListener(callback);
  }
}; 