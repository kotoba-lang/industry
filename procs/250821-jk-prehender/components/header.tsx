"use client"

import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuTrigger } from "@/components/ui/dropdown-menu"
import { Search, Plus, Eye, FileText, Calendar, BarChart3, Map, Grid3X3, Share, Lightbulb, CheckSquare, Users, FolderOpen, BookOpen, Copy } from "lucide-react"
import { Waves } from "lucide-react"
import type { StreamType } from "@/types/note"

interface HeaderProps {
  onNewNote: () => void
  selectedViewpoint: string | null
  onViewpointChange: (viewpoint: string | null) => void
  hasSelectedNote: boolean
  onCreateStreamType?: (streamType: StreamType) => void
}

const STREAM_TYPE_CONFIG = {
  note: { label: 'Note', icon: FileText, color: 'text-blue-600', description: 'Regular note or documentation' },
  idea: { label: 'Idea', icon: Lightbulb, color: 'text-yellow-600', description: 'Creative ideas and concepts' },
  task: { label: 'Task', icon: CheckSquare, color: 'text-green-600', description: 'Action items and todos' },
  meeting: { label: 'Meeting', icon: Users, color: 'text-purple-600', description: 'Meeting notes and discussions' },
  project: { label: 'Project', icon: FolderOpen, color: 'text-orange-600', description: 'Project documentation' },
  reference: { label: 'Reference', icon: BookOpen, color: 'text-teal-600', description: 'Reference materials and resources' },
  template: { label: 'Template', icon: Copy, color: 'text-gray-600', description: 'Reusable templates' }
}

export function Header({ 
  onNewNote, 
  selectedViewpoint, 
  onViewpointChange, 
  hasSelectedNote,
  onCreateStreamType 
}: HeaderProps) {
  const viewpoints = [
    { id: "timeline", name: "Timeline View", icon: Calendar },
    { id: "mindmap", name: "Mind Map", icon: Map },
    { id: "analytics", name: "Analytics", icon: BarChart3 },
    { id: "grid", name: "Grid View", icon: Grid3X3 },
    { id: "connections", name: "Connections", icon: Share },
  ]

  return (
    <header className="h-14 border-b border-gray-200 flex items-center justify-between px-4 bg-white">
      <div className="flex items-center gap-4">
        <div className="flex items-center gap-2">
          {/* <FileText className="w-6 h-6 text-gray-700" /> */}
          {/* <h1 className="text-lg font-medium text-gray-900">Prehender</h1> */}

          <Waves className="w-8 h-8 text-blue-600" />
          <h1 className="text-2xl font-bold bg-gradient-to-r from-blue-600 to-purple-600 bg-clip-text text-transparent">
            Prehender
          </h1>
        </div>

        <div className="relative">
          <Search className="absolute left-3 top-1/2 transform -translate-y-1/2 text-gray-400 w-4 h-4" />
          <Input placeholder="Search streams..." className="pl-10 w-64 h-8 text-sm border-gray-300" />
        </div>
      </div>

      <div className="flex items-center gap-2">
        {hasSelectedNote && (
          <DropdownMenu>
            <DropdownMenuTrigger asChild>
              <Button variant="ghost" size="sm" className="text-gray-600">
                <Eye className="w-4 h-4 mr-2" />
                {selectedViewpoint ? viewpoints.find((v) => v.id === selectedViewpoint)?.name : "Viewpoints"}
              </Button>
            </DropdownMenuTrigger>
            <DropdownMenuContent align="end">
              <DropdownMenuItem onClick={() => onViewpointChange(null)}>
                <FileText className="w-4 h-4 mr-2" />
                Normal View
              </DropdownMenuItem>
              {viewpoints.map((viewpoint) => {
                const Icon = viewpoint.icon
                return (
                  <DropdownMenuItem key={viewpoint.id} onClick={() => onViewpointChange(viewpoint.id)}>
                    <Icon className="w-4 h-4 mr-2" />
                    {viewpoint.name}
                  </DropdownMenuItem>
                )
              })}
            </DropdownMenuContent>
          </DropdownMenu>
        )}

        {/* Stream Creation Dropdown */}
        <DropdownMenu>
          <DropdownMenuTrigger asChild>
            <Button size="sm" className="bg-blue-600 hover:bg-blue-700 text-white">
              <Plus className="w-4 h-4 mr-1" />
              New Stream
            </Button>
          </DropdownMenuTrigger>
          <DropdownMenuContent align="end" className="w-56">
            <div className="px-2 py-1.5 text-sm font-semibold text-gray-700 border-b">
              Create New Stream
            </div>
            {Object.entries(STREAM_TYPE_CONFIG).map(([type, config]) => {
              const Icon = config.icon
              return (
                <DropdownMenuItem 
                  key={type}
                  onClick={() => onCreateStreamType ? onCreateStreamType(type as StreamType) : onNewNote()}
                  className="flex flex-col items-start py-2"
                >
                  <div className="flex items-center gap-2 w-full">
                    <Icon className={`w-4 h-4 ${config.color}`} />
                    <span className="font-medium">{config.label}</span>
                  </div>
                  <span className="text-xs text-gray-500 ml-6">{config.description}</span>
                </DropdownMenuItem>
              )
            })}
          </DropdownMenuContent>
        </DropdownMenu>
      </div>
    </header>
  )
}
