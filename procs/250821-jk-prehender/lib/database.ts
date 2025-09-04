import Dexie, { Table } from 'dexie'
import { Note, NoteEvent, SyncMetadata, DeviceInfo } from '@/types/note'
import { User, SyncConflict } from '@/types/user'

export class PrehenderDatabase extends Dexie {
  // テーブル定義
  notes!: Table<Note>
  noteEvents!: Table<NoteEvent>
  users!: Table<User>
  syncMetadata!: Table<SyncMetadata>
  syncConflicts!: Table<SyncConflict>
  deviceInfo!: Table<DeviceInfo>

  constructor() {
    super('PrehenderDatabase')
    
    this.version(1).stores({
      notes: '++id, userId, title, createdAt, updatedAt, syncedAt, deviceId, version, isDeleted, syncStatus, *tags',
      noteEvents: '++id, noteId, userId, deviceId, timestamp, type, version',
      users: '++id, email, iCloudId, isActive',
      syncMetadata: '++userId, deviceId, lastSyncAt',
      syncConflicts: '++id, noteId, timestamp, resolved',
      deviceInfo: '++id, type, lastSeenAt, isCurrentDevice'
    })

    // インデックス最適化
    this.notes.hook('creating', function (primKey, obj, trans) {
      obj.createdAt = new Date()
      obj.updatedAt = new Date()
      obj.version = 1
      obj.isDeleted = false
      obj.syncStatus = 'pending'
    })

    this.notes.hook('updating', function (modifications, primKey, obj, trans) {
      ;(modifications as any).updatedAt = new Date()
      if (obj.version !== undefined) {
        ;(modifications as any).version = obj.version + 1
      }
      ;(modifications as any).syncStatus = 'pending'
    })
  }
}

// データベースインスタンス
export const db = new PrehenderDatabase()

// デバイス情報の初期化
export async function initializeDevice(): Promise<string> {
  const deviceId = localStorage.getItem('prehender-device-id') || crypto.randomUUID()
  localStorage.setItem('prehender-device-id', deviceId)

  const existingDevice = await db.deviceInfo.where('id').equals(deviceId).first()
  
  if (!existingDevice) {
    await db.deviceInfo.add({
      id: deviceId,
      name: navigator.userAgent.includes('Mac') ? 'Mac Desktop' : 'Desktop',
      type: 'desktop',
      lastSeenAt: new Date(),
      isCurrentDevice: true
    })
  } else {
    await db.deviceInfo.update(deviceId, {
      lastSeenAt: new Date(),
      isCurrentDevice: true
    })
  }

  // 他のデバイスをcurrentDevice=falseに設定
  await db.deviceInfo.where('id').notEqual(deviceId).modify({ isCurrentDevice: false })

  return deviceId
}

// データベースの初期化とマイグレーション
export async function initializeDatabase(): Promise<void> {
  try {
    await db.open()
    console.log('Database initialized successfully')
  } catch (error) {
    console.error('Failed to initialize database:', error)
    throw error
  }
}

// iCloud同期関連の機能
export class iCloudSyncManager {
  private syncInProgress = false
  private deviceId: string

  constructor(deviceId: string) {
    this.deviceId = deviceId
  }

  // iCloudから最新データを取得
  async fetchFromiCloud(userId: string): Promise<Note[]> {
    try {
      // Safari/WebKitのiCloudキーバリューストアを利用
      // Note: これは実際の実装では、ブラウザのiCloud統合APIを使用
      const iCloudData = await this.getiCloudKeyValueStore(userId)
      return iCloudData.notes || []
    } catch (error) {
      console.error('Failed to fetch from iCloud:', error)
      throw error
    }
  }

  // iCloudにデータをアップロード
  async synciCloud(userId: string): Promise<void> {
    if (this.syncInProgress) {
      console.log('Sync already in progress')
      return
    }

    this.syncInProgress = true
    
    try {
      // iCloud可用性をチェック
      const iCloudAvailable = await this.checkiCloudAvailability()
      if (!iCloudAvailable) {
        console.log('iCloud not available, using fallback sync')
      }

      // ローカルの未同期データを取得
      const pendingNotes = await db.notes
        .where(['userId', 'syncStatus'])
        .equals([userId, 'pending'])
        .toArray()

      // Tauriが利用可能な場合は直接同期
      if (typeof window !== 'undefined' && '__TAURI__' in window && iCloudAvailable) {
        try {
          const syncResult = await this.syncWithTauri(userId, pendingNotes)
          
          // 同期結果に基づいてローカルステータスを更新
          if (syncResult.success) {
            for (const note of pendingNotes) {
              await db.notes.update(note.id, {
                syncStatus: 'synced',
                syncedAt: new Date()
              })
            }
            
            // 競合がある場合は記録
            if (syncResult.conflicts > 0) {
              console.warn(`${syncResult.conflicts} conflicts detected during sync`)
              // TODO: 競合をUIに表示
            }
          } else {
            throw new Error(syncResult.error || 'Sync failed')
          }
        } catch (tauriError) {
          console.warn('Tauri sync failed, falling back to manual sync:', tauriError)
          // フォールバック処理を続行
        }
      }
      
      // フォールバック同期処理（従来の方法）
      if (!iCloudAvailable || typeof window === 'undefined' || !('__TAURI__' in window)) {
        // iCloudから最新データを取得
        const iCloudNotes = await this.fetchFromiCloud(userId)
        
        // 競合解決
        const conflicts = await this.resolveConflicts(pendingNotes, iCloudNotes, userId)
        
        // 競合がある場合はユーザーに通知
        if (conflicts.length > 0) {
          await this.saveConflicts(conflicts)
        }

        // iCloudに変更をアップロード
        await this.uploadToiCloud(userId, pendingNotes)
        
        // 同期完了したノートのステータスを更新
        for (const note of pendingNotes) {
          await db.notes.update(note.id, {
            syncStatus: 'synced',
            syncedAt: new Date()
          })
        }
      }

      // 同期メタデータを更新
      await this.updateSyncMetadata(userId)

    } finally {
      this.syncInProgress = false
    }
  }

  // 競合解決ロジック
  private async resolveConflicts(
    localNotes: Note[], 
    iCloudNotes: Note[], 
    userId: string
  ): Promise<SyncConflict[]> {
    const conflicts: SyncConflict[] = []
    
    for (const localNote of localNotes) {
      const iCloudNote = iCloudNotes.find(n => n.id === localNote.id)
      
      if (iCloudNote) {
        // 同じノートが両方に存在する場合
        if (localNote.version !== iCloudNote.version) {
          // バージョンが異なる場合は競合
          const conflict: SyncConflict = {
            id: crypto.randomUUID(),
            noteId: localNote.id,
            type: this.getConflictType(localNote, iCloudNote),
            localVersion: localNote,
            remoteVersion: iCloudNote,
            timestamp: new Date(),
            resolved: false
          }
          conflicts.push(conflict)
        }
      }
    }
    
    return conflicts
  }

  private getConflictType(local: Note, remote: Note): 'content' | 'metadata' | 'deletion' {
    if (local.isDeleted !== remote.isDeleted) {
      return 'deletion'
    }
    if (local.content !== remote.content || local.title !== remote.title) {
      return 'content'
    }
    return 'metadata'
  }

  // 競合を保存
  private async saveConflicts(conflicts: SyncConflict[]): Promise<void> {
    for (const conflict of conflicts) {
      await db.syncConflicts.add(conflict)
    }
  }

  // iCloudキーバリューストアから読み取り
  private async getiCloudKeyValueStore(userId: string): Promise<any> {
    try {
      // Tauriアプリケーションの場合
      if (typeof window !== 'undefined' && '__TAURI__' in window) {
        const { invoke } = await import('@tauri-apps/api/core')
        const result = await invoke('load_from_icloud', { userId })
        return result || {}
      }
      
      // フォールバック: ローカルストレージをiCloudとして扱う（開発用）
      const data = localStorage.getItem(`icloud-${userId}`)
      return data ? JSON.parse(data) : {}
    } catch (error) {
      console.error('Error reading from iCloud:', error)
      return {}
    }
  }

  // iCloudキーバリューストアに書き込み
  private async setiCloudKeyValueStore(userId: string, data: any): Promise<void> {
    try {
      // Tauriアプリケーションの場合
      if (typeof window !== 'undefined' && '__TAURI__' in window) {
        const { invoke } = await import('@tauri-apps/api/core')
        await invoke('save_to_icloud', { 
          userId, 
          notes: data.notes || [] 
        })
        return
      }
      
      // フォールバック: ローカルストレージ
      localStorage.setItem(`icloud-${userId}`, JSON.stringify(data))
    } catch (error) {
      console.error('Error writing to iCloud:', error)
      throw error
    }
  }

  // iCloud同期の可用性をチェック
  private async checkiCloudAvailability(): Promise<boolean> {
    try {
      if (typeof window !== 'undefined' && '__TAURI__' in window) {
        const { invoke } = await import('@tauri-apps/api/core')
        return await invoke('check_icloud_availability')
      }
      return false
    } catch (error) {
      console.error('Error checking iCloud availability:', error)
      return false
    }
  }

  // Tauriを使用した直接同期
  private async syncWithTauri(userId: string, localNotes: Note[]): Promise<any> {
    try {
      if (typeof window !== 'undefined' && '__TAURI__' in window) {
        const { invoke } = await import('@tauri-apps/api/core')
        
        // ノートをJSONに変換
        const notesJson = localNotes.map(note => ({
          id: note.id,
          userId: note.userId,
          title: note.title,
          content: note.content,
          tags: note.tags,
          createdAt: note.createdAt.toISOString(),
          updatedAt: note.updatedAt.toISOString(),
          syncedAt: note.syncedAt?.toISOString(),
          deviceId: note.deviceId,
          version: note.version,
          isDeleted: note.isDeleted,
          syncStatus: note.syncStatus
        }))
        
        return await invoke('sync_notes_with_icloud', { 
          userId, 
          localNotes: notesJson 
        })
      }
      throw new Error('Tauri not available')
    } catch (error) {
      console.error('Error syncing with Tauri:', error)
      throw error
    }
  }

  // iCloudにデータをアップロード
  private async uploadToiCloud(userId: string, notes: Note[]): Promise<void> {
    const currentData = await this.getiCloudKeyValueStore(userId)
    const updatedData = {
      ...currentData,
      notes: [...(currentData.notes || []), ...notes],
      lastSyncAt: new Date().toISOString(),
      deviceId: this.deviceId
    }
    
    await this.setiCloudKeyValueStore(userId, updatedData)
  }

  // 同期メタデータを更新
  private async updateSyncMetadata(userId: string): Promise<void> {
    const existingMetadata = await db.syncMetadata.where('userId').equals(userId).first()
    
    if (existingMetadata) {
      await db.syncMetadata.update(existingMetadata.userId, {
        lastSyncAt: new Date(),
        deviceId: this.deviceId,
        pendingChanges: 0
      })
    } else {
      await db.syncMetadata.add({
        userId,
        deviceId: this.deviceId,
        lastSyncAt: new Date(),
        pendingChanges: 0,
        conflictCount: 0
      })
    }
  }

  // 自動同期の開始
  async startAutoSync(userId: string, intervalMinutes: number = 5): Promise<void> {
    const intervalMs = intervalMinutes * 60 * 1000
    
    setInterval(async () => {
      try {
        await this.synciCloud(userId)
        console.log('Auto sync completed')
      } catch (error) {
        console.error('Auto sync failed:', error)
      }
    }, intervalMs)
  }

  // 手動同期の実行
  async manualSync(userId: string): Promise<{ success: boolean; conflicts: number }> {
    try {
      await this.synciCloud(userId)
      const allConflicts = await db.syncConflicts.toArray()
      const unresolvedConflicts = allConflicts.filter(c => !c.resolved).length
      return { success: true, conflicts: unresolvedConflicts }
    } catch (error) {
      console.error('Manual sync failed:', error)
      return { success: false, conflicts: 0 }
    }
  }
}

// 同期マネージャーのインスタンス化
let syncManager: iCloudSyncManager | null = null

export async function getSyncManager(): Promise<iCloudSyncManager> {
  if (!syncManager) {
    const deviceId = await initializeDevice()
    syncManager = new iCloudSyncManager(deviceId)
  }
  return syncManager
}

// 競合解決のヘルパー関数
export class ConflictResolver {
  // 競合を手動で解決
  static async resolveConflict(
    conflictId: string, 
    resolution: 'local' | 'remote' | 'merge', 
    mergedData?: Partial<Note>
  ): Promise<void> {
    const conflict = await db.syncConflicts.get(conflictId)
    if (!conflict) throw new Error('Conflict not found')

    let resolvedNote: Note

    switch (resolution) {
      case 'local':
        resolvedNote = conflict.localVersion as Note
        break
      case 'remote':
        resolvedNote = conflict.remoteVersion as Note
        break
      case 'merge':
        if (!mergedData) throw new Error('Merged data required for merge resolution')
        resolvedNote = {
          ...(conflict.localVersion as Note),
          ...mergedData,
          version: Math.max(
            (conflict.localVersion as Note).version,
            (conflict.remoteVersion as Note).version
          ) + 1,
          updatedAt: new Date(),
          syncStatus: 'pending'
        }
        break
      default:
        throw new Error('Invalid resolution type')
    }

    // ノートを更新
    await db.notes.put(resolvedNote)
    
    // 競合を解決済みに設定
    await db.syncConflicts.update(conflictId, { resolved: true })
    
    // イベントを記録
    await db.noteEvents.add({
      id: crypto.randomUUID(),
      type: 'note.synced',
      noteId: resolvedNote.id,
      userId: resolvedNote.userId,
      deviceId: resolvedNote.deviceId,
      timestamp: new Date(),
      data: { resolution, conflictId },
      version: resolvedNote.version
    })
  }

  // 未解決の競合一覧を取得
  static async getUnresolvedConflicts(userId?: string): Promise<SyncConflict[]> {
    let conflicts = await db.syncConflicts.toArray()
    conflicts = conflicts.filter(c => !c.resolved)
    
    if (userId) {
      const userNotes = await db.notes.where('userId').equals(userId).toArray()
      const userNoteIds = new Set(userNotes.map(n => n.id))
      conflicts = conflicts.filter(c => userNoteIds.has(c.noteId))
    }
    
    return conflicts
  }

  // 自動競合解決（最新のタイムスタンプを優先）
  static async autoResolveByTimestamp(conflictId: string): Promise<void> {
    const conflict = await db.syncConflicts.get(conflictId)
    if (!conflict) throw new Error('Conflict not found')

    const local = conflict.localVersion as Note
    const remote = conflict.remoteVersion as Note
    
    const resolution = local.updatedAt > remote.updatedAt ? 'local' : 'remote'
    await this.resolveConflict(conflictId, resolution)
  }
}

// オフライン対応のユーティリティ
export class OfflineManager {
  // オフライン状態を監視
  static startOfflineMonitoring(): void {
    window.addEventListener('online', async () => {
      console.log('Back online, attempting sync...')
      try {
        const syncManager = await getSyncManager()
        // 全ユーザーの同期を試行（実際の実装では現在のユーザーのみ）
        const users = await db.users.toArray()
        for (const user of users) {
          await syncManager.manualSync(user.id)
        }
      } catch (error) {
        console.error('Failed to sync after coming online:', error)
      }
    })

    window.addEventListener('offline', () => {
      console.log('Gone offline, sync will be queued')
    })
  }

  // オフライン状態の確認
  static isOnline(): boolean {
    return navigator.onLine
  }

  // オフライン中に作成されたノートを取得
  static async getOfflineNotes(userId: string): Promise<Note[]> {
    return await db.notes
      .where('userId')
      .equals(userId)
      .and(note => note.syncStatus === 'pending')
      .toArray()
  }

  // オフライン作業のサマリーを取得
  static async getOfflineSummary(userId: string): Promise<{
    pendingNotes: number
    conflicts: number
    lastSyncAt: Date | null
  }> {
    const pendingNotes = await this.getOfflineNotes(userId)
    const conflicts = await ConflictResolver.getUnresolvedConflicts(userId)
    const syncMetadata = await db.syncMetadata.where('userId').equals(userId).first()
    
    return {
      pendingNotes: pendingNotes.length,
      conflicts: conflicts.length,
      lastSyncAt: syncMetadata?.lastSyncAt || null
    }
  }
}

// ノート操作のヘルパー関数
export class NoteManager {
  // ノートを作成（自動的に同期対象に）
  static async createNote(
    userId: string, 
    title: string, 
    content: string, 
    tags: string[] = []
  ): Promise<Note> {
    const deviceId = await initializeDevice()
    const note: Note = {
      id: crypto.randomUUID(),
      userId,
      title,
      content,
      tags,
      createdAt: new Date(),
      updatedAt: new Date(),
      deviceId,
      version: 1,
      isDeleted: false,
      syncStatus: 'pending'
    }

    await db.notes.add(note)
    
    // イベントを記録
    await db.noteEvents.add({
      id: crypto.randomUUID(),
      type: 'note.created',
      noteId: note.id,
      userId,
      deviceId,
      timestamp: new Date(),
      data: { title, tags },
      version: 1
    })

    return note
  }

  // ノートを更新
  static async updateNote(
    noteId: string, 
    updates: Partial<Pick<Note, 'title' | 'content' | 'tags'>>
  ): Promise<Note> {
    const existingNote = await db.notes.get(noteId)
    if (!existingNote) throw new Error('Note not found')

    const updatedNote: Note = {
      ...existingNote,
      ...updates,
      updatedAt: new Date(),
      version: existingNote.version + 1,
      syncStatus: 'pending'
    }

    await db.notes.put(updatedNote)
    
    // イベントを記録
    await db.noteEvents.add({
      id: crypto.randomUUID(),
      type: 'note.updated',
      noteId,
      userId: existingNote.userId,
      deviceId: existingNote.deviceId,
      timestamp: new Date(),
      data: updates,
      version: updatedNote.version
    })

    return updatedNote
  }

  // ノートを論理削除
  static async deleteNote(noteId: string): Promise<void> {
    const note = await db.notes.get(noteId)
    if (!note) throw new Error('Note not found')

    const deletedNote: Note = {
      ...note,
      isDeleted: true,
      updatedAt: new Date(),
      version: note.version + 1,
      syncStatus: 'pending'
    }

    await db.notes.put(deletedNote)
    
    // イベントを記録
    await db.noteEvents.add({
      id: crypto.randomUUID(),
      type: 'note.deleted',
      noteId,
      userId: note.userId,
      deviceId: note.deviceId,
      timestamp: new Date(),
      data: { deleted: true },
      version: deletedNote.version
    })
  }

  // ユーザーのノート一覧を取得（削除済みを除く）
  static async getUserNotes(userId: string): Promise<Note[]> {
    return await db.notes
      .where('userId')
      .equals(userId)
      .and(note => !note.isDeleted)
      .reverse()
      .sortBy('updatedAt')
  }

  // ノート検索
  static async searchNotes(userId: string, query: string): Promise<Note[]> {
    const allNotes = await this.getUserNotes(userId)
    const lowercaseQuery = query.toLowerCase()
    
    return allNotes.filter(note => 
      note.title.toLowerCase().includes(lowercaseQuery) ||
      note.content.toLowerCase().includes(lowercaseQuery) ||
      note.tags.some(tag => tag.toLowerCase().includes(lowercaseQuery))
    )
  }
} 