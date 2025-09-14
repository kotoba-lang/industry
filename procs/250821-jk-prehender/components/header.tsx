"use client"

import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Badge } from "@/components/ui/badge"
import { Separator } from "@/components/ui/separator"
import { DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuSeparator, DropdownMenuTrigger, DropdownMenuLabel } from "@/components/ui/dropdown-menu"
import { Tooltip, TooltipContent, TooltipProvider, TooltipTrigger } from "@/components/ui/tooltip"
import { Command, CommandEmpty, CommandGroup, CommandInput, CommandItem, CommandList } from "@/components/ui/command"
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover"
import { Search, Plus, Eye, FileText, Calendar, BarChart3, Map, Grid3X3, Share, Lightbulb, CheckSquare, Users, FolderOpen, BookOpen, Copy, Zap, Sparkles, RefreshCw, Settings } from "lucide-react"
import { Waves } from "lucide-react"
import type { StreamType } from "@/types/note"
import { useState } from "react"

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
  const [searchOpen, setSearchOpen] = useState(false)

  const viewpoints = [
    { id: "timeline", name: "Timeline View", icon: Calendar, description: "View streams chronologically" },
    { id: "mindmap", name: "Mind Map", icon: Map, description: "Visual mind map of connections" },
    { id: "analytics", name: "Analytics", icon: BarChart3, description: "Data insights and metrics" },
    { id: "grid", name: "Grid View", icon: Grid3X3, description: "Compact grid layout" },
    { id: "connections", name: "Connections", icon: Share, description: "Network of relationships" },
  ]

  return (
    <TooltipProvider>
      <header className="h-16 border-b border-border bg-background/95 backdrop-blur supports-[backdrop-filter]:bg-background/60 flex items-center justify-between px-6">
        {/* Logo and Brand */}
        <div className="flex items-center gap-6">
          <div className="flex items-center gap-3">
            <div className="relative">
              <Waves className="w-8 h-8 text-primary animate-pulse" />
              <div className="absolute -top-1 -right-1 w-3 h-3 bg-green-500 rounded-full border-2 border-background"></div>
            </div>
            <div>
              <h1 className="text-xl font-bold bg-gradient-to-r from-primary to-primary/70 bg-clip-text text-transparent">
                Prehender
              </h1>
              <p className="text-xs text-muted-foreground hidden sm:block">Stream Management</p>
            </div>
          </div>

          {/* Search */}
          <div className="hidden md:block">
            <Popover open={searchOpen} onOpenChange={setSearchOpen}>
              <PopoverTrigger asChild>
                <Button variant="outline" size="sm" className="w-64 justify-start text-sm text-muted-foreground">
                  <Search className="mr-2 h-4 w-4" />
                  Search streams...
                  <kbd className="pointer-events-none inline-flex h-5 select-none items-center gap-1 rounded border bg-muted px-1.5 font-mono text-[10px] font-medium text-muted-foreground opacity-100 ml-auto">
                    <span className="text-xs">⌘</span>K
                  </kbd>
                </Button>
              </PopoverTrigger>
              <PopoverContent className="w-80 p-0" align="start">
                <Command>
                  <CommandInput placeholder="Search streams, tags, content..." />
                  <CommandList>
                    <CommandEmpty>No results found.</CommandEmpty>
                    <CommandGroup heading="Recent streams">
                      <CommandItem>
                        <FileText className="mr-2 h-4 w-4" />
                        <span>Welcome to Prehender</span>
                      </CommandItem>
                    </CommandGroup>
                    <CommandGroup heading="Stream types">
                      <CommandItem>
                        <Lightbulb className="mr-2 h-4 w-4" />
                        <span>Ideas</span>
                      </CommandItem>
                      <CommandItem>
                        <CheckSquare className="mr-2 h-4 w-4" />
                        <span>Tasks</span>
                      </CommandItem>
                    </CommandGroup>
                  </CommandList>
                </Command>
              </PopoverContent>
            </Popover>
          </div>
        </div>

        {/* Actions */}
        <div className="flex items-center gap-3">
          {/* Mobile Search */}
          <Tooltip>
            <TooltipTrigger asChild>
              <Button variant="ghost" size="sm" className="md:hidden">
                <Search className="h-4 w-4" />
              </Button>
            </TooltipTrigger>
            <TooltipContent>
              Search (⌘K)
            </TooltipContent>
          </Tooltip>

          {/* Viewpoints */}
          {hasSelectedNote && (
            <DropdownMenu>
              <Tooltip>
                <TooltipTrigger asChild>
                  <DropdownMenuTrigger asChild>
                    <Button variant="outline" size="sm">
                      <Eye className="w-4 h-4 mr-2" />
                      <span className="hidden sm:inline">
                        {selectedViewpoint ? viewpoints.find((v) => v.id === selectedViewpoint)?.name : "Viewpoints"}
                      </span>
                      <span className="sm:hidden">View</span>
                    </Button>
                  </DropdownMenuTrigger>
                </TooltipTrigger>
                <TooltipContent>
                  Change viewpoint
                </TooltipContent>
              </Tooltip>
              <DropdownMenuContent align="end" className="w-56">
                <DropdownMenuLabel>Viewpoints</DropdownMenuLabel>
                <DropdownMenuSeparator />
                <DropdownMenuItem onClick={() => onViewpointChange(null)}>
                  <FileText className="w-4 h-4 mr-2" />
                  <div className="flex flex-col">
                    <span>Normal View</span>
                    <span className="text-xs text-muted-foreground">Standard editor view</span>
                  </div>
                </DropdownMenuItem>
                {viewpoints.map((viewpoint) => {
                  const Icon = viewpoint.icon
                  const isSelected = selectedViewpoint === viewpoint.id
                  return (
                    <DropdownMenuItem key={viewpoint.id} onClick={() => onViewpointChange(viewpoint.id)}>
                      <Icon className="w-4 h-4 mr-2" />
                      <div className="flex flex-col">
                        <span className={isSelected ? "font-medium" : ""}>{viewpoint.name}</span>
                        <span className="text-xs text-muted-foreground">{viewpoint.description}</span>
                      </div>
                      {isSelected && <Badge variant="secondary" className="ml-auto">Active</Badge>}
                    </DropdownMenuItem>
                  )
                })}
              </DropdownMenuContent>
            </DropdownMenu>
          )}

          {/* Quick Actions */}
          <Tooltip>
            <TooltipTrigger asChild>
              <Button variant="ghost" size="sm">
                <RefreshCw className="h-4 w-4" />
              </Button>
            </TooltipTrigger>
            <TooltipContent>
              Refresh data
            </TooltipContent>
          </Tooltip>

          {/* Stream Creation */}
          <DropdownMenu>
            <Tooltip>
              <TooltipTrigger asChild>
                <DropdownMenuTrigger asChild>
                  <Button size="sm" className="prehender-gradient hover:opacity-90 transition-opacity">
                    <Plus className="w-4 h-4 mr-2" />
                    <span className="hidden sm:inline">New Stream</span>
                    <span className="sm:hidden">New</span>
                  </Button>
                </DropdownMenuTrigger>
              </TooltipTrigger>
              <TooltipContent>
                Create new stream
              </TooltipContent>
            </Tooltip>
            <DropdownMenuContent align="end" className="w-72">
              <DropdownMenuLabel className="flex items-center gap-2">
                <Sparkles className="w-4 h-4" />
                Create New Stream
              </DropdownMenuLabel>
              <DropdownMenuSeparator />
              <div className="grid grid-cols-1 gap-1 p-1">
                {Object.entries(STREAM_TYPE_CONFIG).map(([type, config]) => {
                  const Icon = config.icon
                  return (
                    <DropdownMenuItem
                      key={type}
                      onClick={() => onCreateStreamType ? onCreateStreamType(type as StreamType) : onNewNote()}
                      className="flex items-start gap-3 p-3 cursor-pointer hover:bg-accent focus:bg-accent"
                    >
                      <div className={`p-1.5 rounded-md bg-muted ${config.color.replace('text-', 'bg-').replace('-600', '-100')}`}>
                        <Icon className={`w-4 h-4 ${config.color}`} />
                      </div>
                      <div className="flex-1 min-w-0">
                        <div className="font-medium text-sm">{config.label}</div>
                        <div className="text-xs text-muted-foreground mt-0.5">{config.description}</div>
                      </div>
                      <div className="flex items-center gap-1">
                        <kbd className="pointer-events-none inline-flex h-5 select-none items-center gap-1 rounded border bg-muted px-1.5 font-mono text-[10px] font-medium text-muted-foreground opacity-100">
                          {type.charAt(0).toUpperCase()}
                        </kbd>
                      </div>
                    </DropdownMenuItem>
                  )
                })}
              </div>
            </DropdownMenuContent>
          </DropdownMenu>

          {/* Settings */}
          <Tooltip>
            <TooltipTrigger asChild>
              <Button variant="ghost" size="sm">
                <Settings className="h-4 w-4" />
              </Button>
            </TooltipTrigger>
            <TooltipContent>
              Settings
            </TooltipContent>
          </Tooltip>
        </div>
      </header>
    </TooltipProvider>
  )
}
    </header>
  )
}
