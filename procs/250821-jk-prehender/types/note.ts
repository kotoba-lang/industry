export interface Note {
  id: string
  userId: string // iCloud同期のためのユーザーID
  title: string
  content: string
  tags: string[]
  createdAt: Date
  updatedAt: Date
  syncedAt?: Date // 最後の同期時刻
  deviceId: string // 作成/更新されたデバイスID
  version: number // 競合解決のためのバージョン
  isDeleted: boolean // 論理削除フラグ
  syncStatus: 'synced' | 'pending' | 'conflict' | 'error' // 同期状態
  // ストリーム拡張
  streamId: string // ストリームとしての識別子
  references: StreamReference[] // 他のストリームへの参照
  subscribers: string[] // このストリームを参照している他のストリーム
  streamType: StreamType // ストリームの種類
  streamState: StreamState // ストリームの現在の状態
}

// ストリーム型の定義
export type StreamType = 
  | 'note'        // 通常のノート
  | 'idea'        // アイデア
  | 'task'        // タスク
  | 'meeting'     // 会議記録
  | 'project'     // プロジェクト
  | 'reference'   // 参考資料
  | 'template'    // テンプレート

// ストリーム状態
export type StreamState = 
  | 'draft'       // 下書き
  | 'active'      // アクティブ
  | 'archived'    // アーカイブ済み
  | 'linked'      // 他のストリームにリンク済み
  | 'merged'      // 他のストリームとマージ済み

// ストリーム間の参照
export interface StreamReference {
  id: string
  targetStreamId: string
  referenceType: ReferenceType
  context?: string // 参照の文脈・理由
  createdAt: Date
  isActive: boolean
}

// 参照の種類
export type ReferenceType = 
  | 'relates_to'    // 関連している
  | 'depends_on'    // 依存している
  | 'follows_from'  // 続きである
  | 'contradicts'   // 矛盾している
  | 'supports'      // 支持している
  | 'questions'     // 疑問を投げかけている
  | 'answers'       // 回答している
  | 'quotes'        // 引用している
  | 'extends'       // 拡張している

// ストリームイベント（既存のNoteEventを拡張）
export interface StreamEvent {
  id: string
  type: StreamEventType
  streamId: string
  userId: string
  deviceId: string
  timestamp: Date
  data: any
  version: number
  referenceChanges?: StreamReference[] // 参照の変更
}

export type StreamEventType = 
  | "stream.created" 
  | "stream.updated" 
  | "stream.deleted" 
  | "stream.tagged"
  | "stream.referenced" // 他のストリームを参照
  | "stream.unreferenced" // 参照を削除
  | "stream.state_changed" // 状態変更
  | "stream.merged" // 他のストリームとマージ
  | "stream.synced"

// ストリームネットワーク（ストリーム間の関係を表現）
export interface StreamNetwork {
  streams: Map<string, Note>
  references: Map<string, StreamReference[]>
  reverseReferences: Map<string, string[]> // 逆引き参照
}

// ストリームクエリ結果
export interface StreamQueryResult {
  stream: Note
  relatedStreams: Note[]
  referencePath: StreamReference[]
  depth: number
}

export interface NoteEvent {
  id: string
  type: "note.created" | "note.updated" | "note.deleted" | "note.tagged" | "note.synced"
  noteId: string
  userId: string
  deviceId: string
  timestamp: Date
  data: any
  version: number
}

export interface SyncMetadata {
  lastSyncAt: Date
  deviceId: string
  userId: string
  pendingChanges: number
  conflictCount: number
}

export interface DeviceInfo {
  id: string
  name: string
  type: 'desktop' | 'mobile' | 'web'
  lastSeenAt: Date
  isCurrentDevice: boolean
}
