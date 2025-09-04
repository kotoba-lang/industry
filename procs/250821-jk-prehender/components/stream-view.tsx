"use client"

import { useState } from "react"
import { Card } from "@/components/ui/card"
import { Badge } from "@/components/ui/badge"
import { ScrollArea } from "@/components/ui/scroll-area"
import { formatDistanceToNow } from "date-fns"

interface StreamViewProps {
  selectedTopic: string | null
  events: any[]
}

export function StreamView({ selectedTopic, events }: StreamViewProps) {
  const [streamEvents, setStreamEvents] = useState([
    {
      id: "1",
      type: "note.created",
      topic: "notes",
      title: "Meeting Notes - Q4 Planning",
      content: "Discussed quarterly objectives and key milestones...",
      timestamp: new Date(Date.now() - 1000 * 60 * 30),
      metadata: { author: "John Doe", tags: ["meeting", "planning"] },
    },
    {
      id: "2",
      type: "idea.captured",
      topic: "ideas",
      title: "New Feature Concept",
      content: "What if we could visualize data streams as flowing rivers?",
      timestamp: new Date(Date.now() - 1000 * 60 * 45),
      metadata: { author: "Jane Smith", tags: ["feature", "ui"] },
    },
    {
      id: "3",
      type: "task.created",
      topic: "tasks",
      title: "Implement stream visualization",
      content: "Create the flowing river UI component for event streams",
      timestamp: new Date(Date.now() - 1000 * 60 * 60),
      metadata: { author: "Mike Johnson", tags: ["development", "ui"] },
    },
  ])

  const getEventColor = (topic: string) => {
    const colors = {
      notes: "border-l-blue-500 bg-blue-50",
      ideas: "border-l-green-500 bg-green-50",
      tasks: "border-l-orange-500 bg-orange-50",
      meetings: "border-l-purple-500 bg-purple-50",
    }
    return colors[topic] || "border-l-gray-500 bg-gray-50"
  }

  const filteredEvents = selectedTopic ? streamEvents.filter((event) => event.topic === selectedTopic) : streamEvents

  return (
    <div className="h-full relative overflow-hidden">
      {/* Flowing River Background */}
      <div className="absolute inset-0 opacity-10">
        <svg className="w-full h-full" viewBox="0 0 1000 800">
          <defs>
            <linearGradient id="riverGradient" x1="0%" y1="0%" x2="100%" y2="100%">
              <stop offset="0%" stopColor="#3b82f6" />
              <stop offset="100%" stopColor="#8b5cf6" />
            </linearGradient>
          </defs>
          <path
            d="M0,400 Q250,200 500,400 T1000,400"
            stroke="url(#riverGradient)"
            strokeWidth="100"
            fill="none"
            opacity="0.3"
          />
          <path
            d="M0,450 Q300,250 600,450 T1000,450"
            stroke="url(#riverGradient)"
            strokeWidth="60"
            fill="none"
            opacity="0.2"
          />
        </svg>
      </div>

      <ScrollArea className="h-full">
        <div className="p-6 space-y-4 relative z-10">
          <div className="flex items-center justify-between mb-6">
            <h2 className="text-2xl font-bold text-slate-800">
              {selectedTopic
                ? `${selectedTopic.charAt(0).toUpperCase() + selectedTopic.slice(1)} Stream`
                : "All Streams"}
            </h2>
            <Badge variant="secondary">{filteredEvents.length} events</Badge>
          </div>

          <div className="space-y-4">
            {filteredEvents.map((event, index) => (
              <Card
                key={event.id}
                className={`p-4 border-l-4 ${getEventColor(event.topic)} hover:shadow-md transition-all duration-200 animate-in slide-in-from-right-5`}
                style={{ animationDelay: `${index * 100}ms` }}
              >
                <div className="flex items-start justify-between mb-2">
                  <div className="flex items-center gap-2">
                    <Badge variant="outline" className="text-xs">
                      {event.type}
                    </Badge>
                    <Badge variant="secondary" className="text-xs">
                      {event.topic}
                    </Badge>
                  </div>
                  <span className="text-xs text-slate-500">
                    {formatDistanceToNow(event.timestamp, { addSuffix: true })}
                  </span>
                </div>

                <h3 className="font-semibold text-slate-800 mb-2">{event.title}</h3>

                <p className="text-slate-600 text-sm mb-3">{event.content}</p>

                <div className="flex items-center justify-between">
                  <div className="flex gap-1">
                    {event.metadata.tags.map((tag) => (
                      <Badge key={tag} variant="outline" className="text-xs">
                        #{tag}
                      </Badge>
                    ))}
                  </div>
                  <span className="text-xs text-slate-500">by {event.metadata.author}</span>
                </div>
              </Card>
            ))}
          </div>
        </div>
      </ScrollArea>
    </div>
  )
}
