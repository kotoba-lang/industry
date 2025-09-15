"use client"

import { useState } from "react"
import { Button } from "@/components/ui/button"
import { ScrollArea } from "@/components/ui/scroll-area"
import { Input } from "@/components/ui/input"
import { Badge } from "@/components/ui/badge"
import { DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuTrigger } from "@/components/ui/dropdown-menu"
import { Search, MoreHorizontal, Trash2, FileText, Lightbulb, CheckSquare, Users, FolderOpen, BookOpen, Copy, GitBranch, ArrowRight } from "lucide-react"
import { formatDistanceToNow } from "date-fns"
import { extractTitleFromContent } from "@/lib/utils"
import type { Note, StreamType } from "@/types/note"

interface NoteSidebarProps {
  notes: Note[]
  selectedNoteId: string | null
  onNoteSelect: (id: string) => void
  onDeleteNote: (id: string) => void
}

const STREAM_TYPE_CONFIG = {
  note: { label: 'Note', icon: FileText, color: 'text-blue-600', bgColor: 'bg-blue-100' },
  idea: { label: 'Idea', icon: Lightbulb, color: 'text-yellow-600', bgColor: 'bg-yellow-100' },
  task: { label: 'Task', icon: CheckSquare, color: 'text-green-600', bgColor: 'bg-green-100' },
  meeting: { label: 'Meeting', icon: Users, color: 'text-purple-600', bgColor: 'bg-purple-100' },
  project: { label: 'Project', icon: FolderOpen, color: 'text-orange-600', bgColor: 'bg-orange-100' },
  reference: { label: 'Reference', icon: BookOpen, color: 'text-teal-600', bgColor: 'bg-teal-100' },
  template: { label: 'Template', icon: Copy, color: 'text-gray-600', bgColor: 'bg-gray-100' }
}

export function NoteSidebar({ notes, selectedNoteId, onNoteSelect, onDeleteNote }: NoteSidebarProps) {
  const [searchQuery, setSearchQuery] = useState("")
  const [filterStreamType, setFilterStreamType] = useState<StreamType | 'all'>('all')

  const filteredNotes = notes.filter((note) => {
    const title = extractTitleFromContent(note.content)
    const matchesSearch = title.toLowerCase().includes(searchQuery.toLowerCase()) ||
      note.content.toLowerCase().includes(searchQuery.toLowerCase())
    
    const matchesType = filterStreamType === 'all' || note.streamType === filterStreamType
    
    return matchesSearch && matchesType
  })

  const getPreviewText = (content: string) => {
    // 最初の1行目（タイトル）を除いた本文を取得
    const lines = content.split('\n')
    const bodyContent = lines.slice(1).join('\n').trim()
    
    if (!bodyContent) return "本文なし"
    
    return bodyContent.replace(/[#*`]/g, "").substring(0, 100) + (bodyContent.length > 100 ? "..." : "")
  }

  const getStreamIcon = (streamType: StreamType) => {
    const config = STREAM_TYPE_CONFIG[streamType]
    const Icon = config.icon
    return <Icon className={`w-3 h-3 ${config.color}`} />
  }

  const streamTypeCounts = notes.reduce((acc, note) => {
    acc[note.streamType] = (acc[note.streamType] || 0) + 1
    return acc
  }, {} as Record<StreamType, number>)

  return (
    <div className="w-80 border-r border-gray-200 bg-gray-50 flex flex-col">
      <div className="p-4 border-b border-gray-200 bg-white">
        <div className="space-y-3">
          {/* Search */}
          <div className="relative">
            <Search className="absolute left-3 top-1/2 transform -translate-y-1/2 text-gray-400 w-4 h-4" />
            <Input
              placeholder="Search streams..."
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
              className="pl-10 h-8 text-sm"
            />
          </div>

          {/* Stream Type Filter */}
          <DropdownMenu>
            <DropdownMenuTrigger asChild>
              <Button variant="outline" size="sm" className="w-full justify-between h-8 text-sm">
                {filterStreamType === 'all' ? (
                  <span>All Types</span>
                ) : (
                  <div className="flex items-center gap-2">
                    {getStreamIcon(filterStreamType)}
                    <span>{STREAM_TYPE_CONFIG[filterStreamType].label}</span>
                  </div>
                )}
                <span className="text-xs text-gray-500">({filteredNotes.length})</span>
              </Button>
            </DropdownMenuTrigger>
            <DropdownMenuContent className="w-56">
              <DropdownMenuItem onClick={() => setFilterStreamType('all')}>
                <GitBranch className="w-4 h-4 mr-2 text-gray-500" />
                All Types
                <span className="ml-auto text-xs text-gray-500">{notes.length}</span>
              </DropdownMenuItem>
              {Object.entries(STREAM_TYPE_CONFIG).map(([type, config]) => {
                const Icon = config.icon
                const count = streamTypeCounts[type as StreamType] || 0
                return (
                  <DropdownMenuItem 
                    key={type} 
                    onClick={() => setFilterStreamType(type as StreamType)}
                    disabled={count === 0}
                  >
                    <Icon className={`w-4 h-4 mr-2 ${config.color}`} />
                    {config.label}
                    <span className="ml-auto text-xs text-gray-500">{count}</span>
                  </DropdownMenuItem>
                )
              })}
            </DropdownMenuContent>
          </DropdownMenu>
        </div>
      </div>

      <ScrollArea className="flex-1">
        <div className="p-2">
          {filteredNotes.length === 0 ? (
            <div className="text-center py-8 text-gray-500">
              <FileText className="w-8 h-8 mx-auto mb-2 opacity-50" />
              <p className="text-sm">No streams found</p>
              {searchQuery && (
                <p className="text-xs mt-1">Try a different search term</p>
              )}
            </div>
          ) : (
            filteredNotes.map((note) => {
              const config = STREAM_TYPE_CONFIG[note.streamType]
              const Icon = config.icon
              
              return (
                <div
                  key={note.id}
                  className={`group p-3 rounded-lg cursor-pointer transition-colors mb-1 ${
                    selectedNoteId === note.id
                      ? "bg-blue-100 border border-blue-200"
                      : "hover:bg-white border border-transparent"
                  }`}
                  onClick={() => onNoteSelect(note.id)}
                >
                  <div className="flex items-start justify-between mb-2">
                    <div className="flex items-center gap-2 flex-1">
                      <div className={`p-1 rounded ${config.bgColor}`}>
                        <Icon className={`w-3 h-3 ${config.color}`} />
                      </div>
                      <h3 className="font-medium text-sm text-gray-900 truncate flex-1">
                        {extractTitleFromContent(note.content)}
                      </h3>
                    </div>
                    <DropdownMenu>
                      <DropdownMenuTrigger asChild>
                        <Button
                          variant="ghost"
                          size="sm"
                          className="opacity-0 group-hover:opacity-100 h-6 w-6 p-0"
                          onClick={(e) => e.stopPropagation()}
                        >
                          <MoreHorizontal className="w-3 h-3" />
                        </Button>
                      </DropdownMenuTrigger>
                      <DropdownMenuContent align="end">
                        <DropdownMenuItem
                          onClick={(e) => {
                            e.stopPropagation()
                            onDeleteNote(note.id)
                          }}
                          className="text-red-600"
                        >
                          <Trash2 className="w-4 h-4 mr-2" />
                          Delete
                        </DropdownMenuItem>
                      </DropdownMenuContent>
                    </DropdownMenu>
                  </div>

                  <p className="text-xs text-gray-600 mb-2 line-clamp-2">{getPreviewText(note.content)}</p>

                  <div className="flex items-center justify-between">
                    <div className="flex items-center gap-2">
                      <span className="text-xs text-gray-500">
                        {formatDistanceToNow(note.updatedAt, { addSuffix: true })}
                      </span>
                      <Badge variant="outline" className="text-xs">
                        {note.streamState}
                      </Badge>
                    </div>
                    
                    <div className="flex items-center gap-2">
                      {/* Stream References */}
                      {note.references.length > 0 && (
                        <div className="flex items-center gap-1 text-xs text-blue-600">
                          <ArrowRight className="w-3 h-3" />
                          <span>{note.references.length}</span>
                        </div>
                      )}
                      
                      {/* Tags */}
                      {note.tags.length > 0 && (
                        <div className="flex gap-1">
                          {note.tags.slice(0, 2).map((tag) => (
                            <span key={tag} className="text-xs bg-gray-200 text-gray-700 px-1 rounded">
                              #{tag}
                            </span>
                          ))}
                          {note.tags.length > 2 && (
                            <span className="text-xs text-gray-500">+{note.tags.length - 2}</span>
                          )}
                        </div>
                      )}
                    </div>
                  </div>
                </div>
              )
            })
          )}
        </div>
      </ScrollArea>
    </div>
  )
}
