"use client"

import { useState } from "react"
import { Button } from "@/components/ui/button"
import { ScrollArea } from "@/components/ui/scroll-area"
import { Badge } from "@/components/ui/badge"
import { Plus, ChevronRight, ChevronDown, GitBranch } from "lucide-react"

interface SidebarProps {
  selectedTopic: string | null
  onTopicSelect: (topic: string | null) => void
}

export function Sidebar({ selectedTopic, onTopicSelect }: SidebarProps) {
  const [expandedStreams, setExpandedStreams] = useState<Set<string>>(new Set(["main"]))

  const streams = [
    {
      id: "main",
      name: "Main Stream",
      topics: [
        { id: "notes", name: "Notes", count: 24, color: "bg-blue-500" },
        { id: "ideas", name: "Ideas", count: 12, color: "bg-green-500" },
        { id: "tasks", name: "Tasks", count: 8, color: "bg-orange-500" },
        { id: "meetings", name: "Meetings", count: 6, color: "bg-purple-500" },
      ],
    },
    {
      id: "projects",
      name: "Project Streams",
      topics: [
        { id: "project-alpha", name: "Project Alpha", count: 15, color: "bg-red-500" },
        { id: "project-beta", name: "Project Beta", count: 9, color: "bg-indigo-500" },
      ],
    },
  ]

  const toggleStream = (streamId: string) => {
    const newExpanded = new Set(expandedStreams)
    if (newExpanded.has(streamId)) {
      newExpanded.delete(streamId)
    } else {
      newExpanded.add(streamId)
    }
    setExpandedStreams(newExpanded)
  }

  return (
    <div className="w-80 border-r border-slate-200 bg-white/50 backdrop-blur-sm flex flex-col">
      <div className="p-4 border-b border-slate-200">
        <div className="flex items-center justify-between mb-4">
          <h2 className="font-semibold text-slate-700">Event Streams</h2>
          <Button variant="ghost" size="sm">
            <Plus className="w-4 h-4" />
          </Button>
        </div>

        <Button
          variant={selectedTopic === null ? "default" : "ghost"}
          className="w-full justify-start"
          onClick={() => onTopicSelect(null)}
        >
          <GitBranch className="w-4 h-4 mr-2" />
          All Streams
        </Button>
      </div>

      <ScrollArea className="flex-1">
        <div className="p-2">
          {streams.map((stream) => (
            <div key={stream.id} className="mb-2">
              <Button
                variant="ghost"
                className="w-full justify-start p-2 h-auto"
                onClick={() => toggleStream(stream.id)}
              >
                {expandedStreams.has(stream.id) ? (
                  <ChevronDown className="w-4 h-4 mr-2" />
                ) : (
                  <ChevronRight className="w-4 h-4 mr-2" />
                )}
                <span className="font-medium">{stream.name}</span>
              </Button>

              {expandedStreams.has(stream.id) && (
                <div className="ml-6 space-y-1">
                  {stream.topics.map((topic) => (
                    <Button
                      key={topic.id}
                      variant={selectedTopic === topic.id ? "secondary" : "ghost"}
                      className="w-full justify-start p-2 h-auto"
                      onClick={() => onTopicSelect(topic.id)}
                    >
                      <div className={`w-3 h-3 rounded-full ${topic.color} mr-3`} />
                      <span className="flex-1 text-left">{topic.name}</span>
                      <Badge variant="secondary" className="ml-2">
                        {topic.count}
                      </Badge>
                    </Button>
                  ))}
                </div>
              )}
            </div>
          ))}
        </div>
      </ScrollArea>
    </div>
  )
}
