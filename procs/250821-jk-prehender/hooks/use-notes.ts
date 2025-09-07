"use client"

import { useState, useCallback, useEffect, useRef } from "react"
import type { Note, StreamReference, ReferenceType, StreamType, StreamState, StreamNetwork, StreamQueryResult } from "@/types/note"
import { KafkaNotesManager, type KafkaNote } from "@/lib/kafka-rest-client"

// Simulated Event Sourcing - これが裏側でOccasion Topicとして管理される
interface NoteEvent {
  id: string
  type: "note.created" | "note.updated" | "note.deleted" | "note.tagged" | "stream.referenced" | "stream.unreferenced"
  noteId: string
  timestamp: Date
  data: any
}

// KafkaNote型をNote型に変換（ストリーム機能付き）
function kafkaNoteToNote(kafkaNote: KafkaNote): Note {
  return {
    id: kafkaNote.id,
    title: kafkaNote.title,
    content: kafkaNote.content || '',
    tags: kafkaNote.tags || [],
    createdAt: new Date(kafkaNote.createdAt),
    updatedAt: new Date(kafkaNote.updatedAt),
    // ストリーム拡張プロパティ
    streamId: kafkaNote.id, // KafkaではIDをstreamIdとして使用
    references: [], // TODO: Kafkaから取得
    subscribers: [], // TODO: Kafkaから取得
    streamType: 'note', // TODO: Kafkaのメタデータから取得
    streamState: 'active', // TODO: Kafkaのメタデータから取得
    // レガシープロパティ
    userId: kafkaNote.userId,
    deviceId: kafkaNote.deviceId,
    version: kafkaNote.version,
    isDeleted: kafkaNote.isDeleted,
    syncStatus: kafkaNote.syncStatus
  }
}

// Note型をKafkaNote型に変換（ストリーム機能付き）
function noteToKafkaNote(note: Note): Omit<KafkaNote, 'id' | 'createdAt' | 'updatedAt' | 'version'> {
  return {
    title: note.title,
    content: note.content,
    userId: note.userId,
    deviceId: note.deviceId,
    tags: note.tags,
    isDeleted: note.isDeleted,
    syncStatus: note.syncStatus
  }
}

// デバウンス用のフック
function useDebounce<T extends any[]>(callback: (...args: T) => void, delay: number) {
  const timeoutRef = useRef<NodeJS.Timeout | null>(null)
  
  return useCallback((...args: T) => {
    if (timeoutRef.current) {
      clearTimeout(timeoutRef.current)
    }
    
    timeoutRef.current = setTimeout(() => {
      callback(...args)
    }, delay)
  }, [callback, delay])
}

export function useNotes() {
  const [notes, setNotes] = useState<Note[]>([])
  const [isLoading, setIsLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [streamNetwork, setStreamNetwork] = useState<StreamNetwork>({
    streams: new Map(),
    references: new Map(),
    reverseReferences: new Map()
  })

  // Event Sourcing simulation - 実際のアプリではKafkaに送信される
  const emitEvent = useCallback((event: Omit<NoteEvent, "id" | "timestamp">) => {
    const fullEvent: NoteEvent = {
      ...event,
      id: Date.now().toString(),
      timestamp: new Date(),
    }

    // ここで実際にはKafkaのOccasion Topicに送信される
    console.log("Stream Event emitted to Kafka:", fullEvent)

    // Projection更新のシミュレーション
    return fullEvent
  }, [])

  // ストリームネットワークを更新
  const updateStreamNetwork = useCallback((updatedNotes: Note[]) => {
    const streams = new Map<string, Note>()
    const references = new Map<string, StreamReference[]>()
    const reverseReferences = new Map<string, string[]>()

    updatedNotes.forEach(note => {
      streams.set(note.streamId, note)
      references.set(note.streamId, note.references)

      // 逆引き参照を構築
      note.references.forEach(ref => {
        if (!reverseReferences.has(ref.targetStreamId)) {
          reverseReferences.set(ref.targetStreamId, [])
        }
        reverseReferences.get(ref.targetStreamId)?.push(note.streamId)
      })
    })

    setStreamNetwork({ streams, references, reverseReferences })
  }, [])

  // Kafkaからノートを読み込む
  const loadNotes = useCallback(async () => {
    try {
      setIsLoading(true)
      setError(null)
      const kafkaNotes = await KafkaNotesManager.fetchNotes()
      const convertedNotes = kafkaNotes.filter(note => !note.isDeleted).map(kafkaNoteToNote)
      
      // デフォルトのWelcomeノートがない場合は追加
      if (convertedNotes.length === 0) {
        const welcomeNote = await createInitialWelcomeNote()
        if (welcomeNote) {
          convertedNotes.unshift(welcomeNote)
        }
      }
      
      setNotes(convertedNotes)
      updateStreamNetwork(convertedNotes)
    } catch (err) {
      console.error('Error loading notes from Kafka:', err)
      setError(err instanceof Error ? err.message : 'Failed to load notes from Kafka')
    } finally {
      setIsLoading(false)
    }
  }, [updateStreamNetwork])

  // 初期Welcomeノートを作成
  const createInitialWelcomeNote = useCallback(async (): Promise<Note | null> => {
    try {
      const welcomeKafkaNote = await KafkaNotesManager.createNote({
        title: "Welcome to Prehender with Kafka",
        content: "This is your first note using Kafka streaming. You can write anything here, add tags, and view it from different perspectives using the viewpoints feature.\n\nThis note is now stored in Kafka topics and synchronized in real-time across all your devices.",
        userId: 'local-user',
        deviceId: 'local-device',
        tags: ["welcome", "kafka", "tutorial"],
        isDeleted: false,
        syncStatus: 'pending'
      })
      
      return kafkaNoteToNote(welcomeKafkaNote)
    } catch (err) {
      console.error('Error creating welcome note:', err)
      return null
    }
  }, [])

  // 実際のKafka更新処理
  const performKafkaUpdate = useCallback(async (id: string, updates: Partial<Note>) => {
    try {
      await KafkaNotesManager.updateNote(id, {
        title: updates.title,
        content: updates.content,
        tags: updates.tags,
        userId: updates.userId || 'local-user',
        deviceId: updates.deviceId || 'local-device',
        version: updates.version,
        syncStatus: 'pending'
      })
      console.log(`✅ Stream ${id} updated in Kafka`)
    } catch (err) {
      console.error('Error updating stream in Kafka:', err)
    }
  }, [])

  // デバウンスされたKafka更新関数（500ms遅延）
  const debouncedKafkaUpdate = useDebounce(performKafkaUpdate, 500)

  // 初回読み込み
  useEffect(() => {
    loadNotes()
  }, [loadNotes])

  const createNote = useCallback(async (streamType: StreamType = 'note') => {
    const newNote: Note = {
      id: `note-${Date.now()}`,
      streamId: `stream-${Date.now()}`,
      title: "",
      content: "",
      tags: [],
      createdAt: new Date(),
      updatedAt: new Date(),
      references: [],
      subscribers: [],
      streamType,
      streamState: 'draft',
      userId: 'local-user',
      deviceId: 'local-device',
      version: 1,
      isDeleted: false,
      syncStatus: 'pending'
    }

    try {
      // Kafkaに保存
      const kafkaNote = await KafkaNotesManager.createNote(noteToKafkaNote(newNote))
      const createdNote = kafkaNoteToNote(kafkaNote)
      console.log(`✅ New stream ${createdNote.streamId} created in Kafka`)
      
      // Event Sourcing: stream.created イベント
      emitEvent({
        type: "note.created",
        noteId: createdNote.id,
        data: createdNote,
      })

      const updatedNotes = [createdNote, ...notes]
      setNotes(updatedNotes)
      updateStreamNetwork(updatedNotes)
      return createdNote
    } catch (err) {
      console.error('Error creating stream:', err)
      setError(err instanceof Error ? err.message : 'Failed to create stream')
      return newNote
    }
  }, [emitEvent, notes, updateStreamNetwork])

  const updateNote = useCallback(
    async (id: string, updates: Partial<Note>) => {
      try {
        // 即座にUIを更新
        const updatedNotes = notes.map((note) => {
          if (note.id === id) {
            const updatedNote = { ...note, ...updates, updatedAt: new Date() }

            // Kafkaへの保存はデバウンスして実行
            debouncedKafkaUpdate(id, updatedNote)

            // Event Sourcing: stream.updated イベント
            emitEvent({
              type: "note.updated",
              noteId: id,
              data: { updates, previousState: note },
            })

            return updatedNote
          }
          return note
        })
        
        setNotes(updatedNotes)
        updateStreamNetwork(updatedNotes)
      } catch (err) {
        console.error('Error updating stream:', err)
        setError(err instanceof Error ? err.message : 'Failed to update stream')
      }
    },
    [notes, emitEvent, debouncedKafkaUpdate, updateStreamNetwork],
  )

  const deleteNote = useCallback(
    async (id: string) => {
      const noteToDelete = notes.find((note) => note.id === id)

      if (noteToDelete) {
        try {
          // Kafkaで論理削除
          await KafkaNotesManager.deleteNote(id, noteToDelete.userId, noteToDelete.deviceId)
          console.log(`✅ Stream ${id} deleted from Kafka`)

          // Event Sourcing: stream.deleted イベント
          emitEvent({
            type: "note.deleted",
            noteId: id,
            data: noteToDelete,
          })

          const updatedNotes = notes.filter((note) => note.id !== id)
          setNotes(updatedNotes)
          updateStreamNetwork(updatedNotes)
        } catch (err) {
          console.error('Error deleting stream:', err)
          setError(err instanceof Error ? err.message : 'Failed to delete stream')
        }
      }
    },
    [notes, emitEvent, updateStreamNetwork],
  )

  // ストリーム参照を追加
  const addStreamReference = useCallback(async (fromStreamId: string, toStreamId: string, referenceType: ReferenceType, context?: string) => {
    const newReference: StreamReference = {
      id: `ref-${Date.now()}`,
      targetStreamId: toStreamId,
      referenceType,
      context,
      createdAt: new Date(),
      isActive: true
    }

    const fromNote = notes.find(n => n.streamId === fromStreamId)
    if (!fromNote) return

    const updatedReferences = [...fromNote.references, newReference]
    await updateNote(fromNote.id, { references: updatedReferences })

    // 参照先のストリームのsubscribersも更新
    const toNote = notes.find(n => n.streamId === toStreamId)
    if (toNote) {
      const updatedSubscribers = [...toNote.subscribers, fromStreamId]
      await updateNote(toNote.id, { subscribers: updatedSubscribers })
    }

    emitEvent({
      type: "stream.referenced",
      noteId: fromNote.id,
      data: { reference: newReference }
    })
  }, [notes, updateNote, emitEvent])

  // ストリーム参照を削除
  const removeStreamReference = useCallback(async (fromStreamId: string, referenceId: string) => {
    const fromNote = notes.find(n => n.streamId === fromStreamId)
    if (!fromNote) return

    const referenceToRemove = fromNote.references.find(ref => ref.id === referenceId)
    if (!referenceToRemove) return

    const updatedReferences = fromNote.references.filter(ref => ref.id !== referenceId)
    await updateNote(fromNote.id, { references: updatedReferences })

    // 参照先のストリームのsubscribersも更新
    const toNote = notes.find(n => n.streamId === referenceToRemove.targetStreamId)
    if (toNote) {
      const updatedSubscribers = toNote.subscribers.filter(sub => sub !== fromStreamId)
      await updateNote(toNote.id, { subscribers: updatedSubscribers })
    }

    emitEvent({
      type: "stream.unreferenced",
      noteId: fromNote.id,
      data: { removedReference: referenceToRemove }
    })
  }, [notes, updateNote, emitEvent])

  // 関連ストリームを検索
  const findRelatedStreams = useCallback((streamId: string, depth: number = 2): StreamQueryResult[] => {
    const visited = new Set<string>()
    const results: StreamQueryResult[] = []

    const searchRecursive = (currentStreamId: string, currentDepth: number, path: StreamReference[]) => {
      if (currentDepth > depth || visited.has(currentStreamId)) return

      visited.add(currentStreamId)
      const currentStream = streamNetwork.streams.get(currentStreamId)
      if (!currentStream) return

      if (currentStreamId !== streamId) {
        results.push({
          stream: currentStream,
          relatedStreams: [],
          referencePath: [...path],
          depth: currentDepth
        })
      }

      // 参照先のストリームを探索
      const references = streamNetwork.references.get(currentStreamId) || []
      references.forEach(ref => {
        if (ref.isActive) {
          searchRecursive(ref.targetStreamId, currentDepth + 1, [...path, ref])
        }
      })

      // 逆参照（このストリームを参照しているストリーム）も探索
      const reverseRefs = streamNetwork.reverseReferences.get(currentStreamId) || []
      reverseRefs.forEach(refStreamId => {
        const refStream = streamNetwork.streams.get(refStreamId)
        if (refStream) {
          const refToThis = refStream.references.find(r => r.targetStreamId === currentStreamId)
          if (refToThis) {
            searchRecursive(refStreamId, currentDepth + 1, [...path, refToThis])
          }
        }
      })
    }

    searchRecursive(streamId, 0, [])
    return results
  }, [streamNetwork])

  return {
    notes,
    createNote,
    updateNote,
    deleteNote,
    isLoading,
    error,
    refreshNotes: loadNotes,
    // ストリーム機能
    streamNetwork,
    addStreamReference,
    removeStreamReference,
    findRelatedStreams,
  }
}
