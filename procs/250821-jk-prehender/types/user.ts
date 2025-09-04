export interface User {
  id: string
  email: string
  name: string
  iCloudId?: string // iCloud統合用ID
  createdAt: Date
  updatedAt: Date
  isActive: boolean
  preferences: UserPreferences
}

export interface UserPreferences {
  theme: 'light' | 'dark' | 'system'
  autoSync: boolean
  syncInterval: number // 分単位
  conflictResolution: 'manual' | 'latest' | 'merge'
  defaultTags: string[]
  noteViewMode: 'list' | 'grid' | 'timeline'
}

export interface AuthState {
  user: User | null
  isAuthenticated: boolean
  isLoading: boolean
  error: string | null
}

export interface SyncConflict {
  id: string
  noteId: string
  type: 'content' | 'metadata' | 'deletion'
  localVersion: any
  remoteVersion: any
  timestamp: Date
  resolved: boolean
} 