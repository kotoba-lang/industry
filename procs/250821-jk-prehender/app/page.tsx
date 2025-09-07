"use client"

import { useState } from "react"
import { NoteEditor } from "@/components/note-editor"
import { NoteSidebar } from "@/components/note-sidebar"
import { ViewpointPanel } from "@/components/viewpoint-panel"
import { Header } from "@/components/header"

import { useNotes } from "@/hooks/use-notes"

export default function PrehenderApp() {
  const [selectedNoteId, setSelectedNoteId] = useState<string | null>(null)
  const [selectedViewpoint, setSelectedViewpoint] = useState<string | null>(null)
  
  const { 
    notes, 
    createNote, 
    updateNote, 
    deleteNote, 
    isLoading, 
    error, 
    refreshNotes,
    streamNetwork,
    addStreamReference,
    removeStreamReference,
    findRelatedStreams,
  } = useNotes()

  const selectedNote = selectedNoteId ? notes.find((note) => note.id === selectedNoteId) : null

  const handleNewNote = async () => {
    const newNote = await createNote('note')
    setSelectedNoteId(newNote.id)
    setSelectedViewpoint(null)
  }

  const handleCreateStreamType = async (streamType: 'note' | 'idea' | 'task' | 'meeting' | 'project' | 'reference' | 'template') => {
    const newNote = await createNote(streamType)
    setSelectedNoteId(newNote.id)
    setSelectedViewpoint(null)
  }

  if (isLoading) {
    return (
      <div className="h-screen bg-white flex items-center justify-center">
        <div className="text-center">
          <div className="animate-spin rounded-full h-8 w-8 border-b-2 border-blue-600 mx-auto mb-4"></div>
          <p className="text-sm text-gray-600">Loading streams...</p>
        </div>
      </div>
    )
  }

  return (
    <div className="h-screen bg-white flex flex-col overflow-hidden">
      {error && (
        <div className="bg-red-100 border-l-4 border-red-500 text-red-700 p-4 mb-2">
          <div className="flex">
            <div className="flex-1">
              <p className="text-sm">Database Error: {error}</p>
            </div>
            <button
              onClick={refreshNotes}
              className="text-red-500 hover:text-red-700 text-sm underline ml-4"
            >
              Retry
            </button>
          </div>
        </div>
      )}

      <Header
        onNewNote={handleNewNote}
        selectedViewpoint={selectedViewpoint}
        onViewpointChange={setSelectedViewpoint}
        hasSelectedNote={!!selectedNote}
        onCreateStreamType={handleCreateStreamType}
      />

      <div className="flex-1 flex overflow-hidden">
        <NoteSidebar
          notes={notes}
          selectedNoteId={selectedNoteId}
          onNoteSelect={(id) => {
            setSelectedNoteId(id)
            setSelectedViewpoint(null)
          }}
          onDeleteNote={deleteNote}
        />

        <main className="flex-1 flex flex-col overflow-hidden">
          {/* メインコンテンツエリア */}
          <div className="flex-1 overflow-hidden">
            {selectedViewpoint && selectedNote ? (
              <ViewpointPanel 
                note={selectedNote} 
                viewpoint={selectedViewpoint} 
                allNotes={notes}
                streamNetwork={streamNetwork}
                onAddStreamReference={addStreamReference}
                onRemoveStreamReference={removeStreamReference}
                findRelatedStreams={findRelatedStreams}
              />
            ) : (
              <NoteEditor 
                note={selectedNote} 
                onUpdateNote={updateNote} 
                onNewNote={handleNewNote}
                allNotes={notes}
                onAddStreamReference={addStreamReference}
                onRemoveStreamReference={removeStreamReference}
              />
            )}
          </div>

          {/* ストリーム統計とコントロール */}
          <div className="border-t bg-gray-50 p-2 flex justify-between items-center">
            <div className="flex items-center space-x-4">
              <span className="text-sm text-gray-600">
                {notes.length} streams • {streamNetwork.streams.size} in network
              </span>
              {selectedNote && (
                <span className="text-sm text-blue-600">
                  {selectedNote.references.length} references • {selectedNote.subscribers.length} subscribers
                </span>
              )}
              <button
                onClick={refreshNotes}
                className="text-sm text-blue-600 hover:text-blue-800 underline"
              >
                Refresh
              </button>
            </div>
          </div>
        </main>
      </div>
    </div>
  )
}
