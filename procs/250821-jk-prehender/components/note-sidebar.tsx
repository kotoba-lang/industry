"use client"

import { useState, useMemo } from "react"
import { Button } from "@/components/ui/button"
import { ScrollArea } from "@/components/ui/scroll-area"
import { Input } from "@/components/ui/input"
import { Badge } from "@/components/ui/badge"
import { Separator } from "@/components/ui/separator"
import { Tooltip, TooltipContent, TooltipProvider, TooltipTrigger } from "@/components/ui/tooltip"
import { DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuSeparator, DropdownMenuTrigger, DropdownMenuLabel } from "@/components/ui/dropdown-menu"
import { Command, CommandEmpty, CommandGroup, CommandInput, CommandItem, CommandList } from "@/components/ui/command"
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover"
import {
  Search, MoreHorizontal, Trash2, FileText, Lightbulb, CheckSquare, Users, FolderOpen, BookOpen, Copy,
  GitBranch, ArrowRight, Plus, Filter, SortAsc, SortDesc, Clock, Star, Eye, EyeOff, Pin, PinOff
} from "lucide-react"
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
  const [sortBy, setSortBy] = useState<'updated' | 'created' | 'title'>('updated')
  const [sortOrder, setSortOrder] = useState<'asc' | 'desc'>('desc')
  const [showFilters, setShowFilters] = useState(false)

  const filteredAndSortedNotes = useMemo(() => {
    // Filter notes
    const filtered = notes.filter((note) => {
      const title = extractTitleFromContent(note.content)
      const matchesSearch = title.toLowerCase().includes(searchQuery.toLowerCase()) ||
        note.content.toLowerCase().includes(searchQuery.toLowerCase()) ||
        note.tags.some(tag => tag.toLowerCase().includes(searchQuery.toLowerCase()))

      const matchesType = filterStreamType === 'all' || note.streamType === filterStreamType

      return matchesSearch && matchesType
    })

    // Sort notes
    return filtered.sort((a, b) => {
      let comparison = 0

      switch (sortBy) {
        case 'title':
          comparison = extractTitleFromContent(a.content).localeCompare(extractTitleFromContent(b.content))
          break
        case 'created':
          comparison = new Date(a.createdAt).getTime() - new Date(b.createdAt).getTime()
          break
        case 'updated':
          comparison = new Date(a.updatedAt).getTime() - new Date(b.updatedAt).getTime()
          break
      }

      return sortOrder === 'asc' ? comparison : -comparison
    })
  }, [notes, searchQuery, filterStreamType, sortBy, sortOrder])

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
    <TooltipProvider>
      <div className="w-80 border-r border-border bg-background/50 flex flex-col">
        {/* Enhanced Header */}
        <div className="p-4 border-b border-border bg-background/95 backdrop-blur supports-[backdrop-filter]:bg-background/60">
          <div className="space-y-3">
            {/* Search with Command Palette */}
            <Popover open={showFilters} onOpenChange={setShowFilters}>
              <PopoverTrigger asChild>
                <div className="relative">
                  <Search className="absolute left-3 top-1/2 transform -translate-y-1/2 text-muted-foreground w-4 h-4" />
                  <Input
                    placeholder="Search streams, tags, content..."
                    value={searchQuery}
                    onChange={(e) => setSearchQuery(e.target.value)}
                    className="pl-10 h-9"
                  />
                  <kbd className="pointer-events-none absolute right-2 top-1/2 -translate-y-1/2 inline-flex h-5 select-none items-center gap-1 rounded border bg-muted px-1.5 font-mono text-[10px] font-medium text-muted-foreground opacity-100">
                    ⌘K
                  </kbd>
                </div>
              </PopoverTrigger>
              <PopoverContent className="w-80 p-0" align="start">
                <Command>
                  <CommandInput placeholder="Type to search..." />
                  <CommandList>
                    <CommandEmpty>No results found.</CommandEmpty>
                    <CommandGroup heading="Stream Types">
                      <CommandItem onSelect={() => setFilterStreamType('all')}>
                        <GitBranch className="mr-2 h-4 w-4" />
                        <span>All Streams</span>
                        <span className="ml-auto text-xs text-muted-foreground">{notes.length}</span>
                      </CommandItem>
                      {Object.entries(STREAM_TYPE_CONFIG).map(([type, config]) => (
                        <CommandItem key={type} onSelect={() => setFilterStreamType(type as StreamType)}>
                          <config.icon className={`mr-2 h-4 w-4 ${config.color}`} />
                          <span>{config.label}</span>
                          <span className="ml-auto text-xs text-muted-foreground">
                            {streamTypeCounts[type as StreamType] || 0}
                          </span>
                        </CommandItem>
                      ))}
                    </CommandGroup>
                  </CommandList>
                </Command>
              </PopoverContent>
            </Popover>

            {/* Enhanced Filters and Sort */}
            <div className="flex items-center gap-2">
              {/* Stream Type Filter */}
              <DropdownMenu>
                <Tooltip>
                  <TooltipTrigger asChild>
                    <DropdownMenuTrigger asChild>
                      <Button variant="outline" size="sm" className="flex-1 justify-start h-8 text-sm">
                        {filterStreamType === 'all' ? (
                          <>
                            <Filter className="w-3 h-3 mr-2" />
                            <span>All Types</span>
                          </>
                        ) : (
                          <div className="flex items-center gap-2">
                            {getStreamIcon(filterStreamType)}
                            <span>{STREAM_TYPE_CONFIG[filterStreamType].label}</span>
                          </div>
                        )}
                      </Button>
                    </DropdownMenuTrigger>
                  </TooltipTrigger>
                  <TooltipContent>Filter by stream type</TooltipContent>
                </Tooltip>
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
          {filteredAndSortedNotes.length === 0 ? (
            <div className="text-center py-8 text-gray-500">
              <FileText className="w-8 h-8 mx-auto mb-2 opacity-50" />
              <p className="text-sm">No streams found</p>
              {searchQuery && (
                <p className="text-xs mt-1">Try a different search term</p>
              )}
            </div>
          ) : (
            filteredAndSortedNotes.map((note) => {
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
    </TooltipProvider>
  )
}
