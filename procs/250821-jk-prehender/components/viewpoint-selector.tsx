"use client"

import { Button } from "@/components/ui/button"
import { Waves, Grid3X3, Calendar, BarChart3, Map, Eye } from "lucide-react"

interface ViewpointSelectorProps {
  selectedViewpoint: string
  onViewpointChange: (viewpoint: string) => void
}

export function ViewpointSelector({ selectedViewpoint, onViewpointChange }: ViewpointSelectorProps) {
  const viewpoints = [
    { id: "stream", name: "Stream Flow", icon: Waves, description: "Events flowing like a river" },
    { id: "projection", name: "Projections", icon: Eye, description: "Different perspectives of data" },
    { id: "grid", name: "Grid View", icon: Grid3X3, description: "Organized grid layout" },
    { id: "timeline", name: "Timeline", icon: Calendar, description: "Chronological view" },
    { id: "analytics", name: "Analytics", icon: BarChart3, description: "Data insights" },
    { id: "mindmap", name: "Mind Map", icon: Map, description: "Conceptual connections" },
  ]

  return (
    <div className="h-16 border-b border-slate-200 bg-white/30 backdrop-blur-sm flex items-center px-6">
      <div className="flex items-center gap-2">
        <span className="text-sm font-medium text-slate-600 mr-4">Viewpoints:</span>
        {viewpoints.map((viewpoint) => {
          const Icon = viewpoint.icon
          return (
            <Button
              key={viewpoint.id}
              variant={selectedViewpoint === viewpoint.id ? "default" : "ghost"}
              size="sm"
              onClick={() => onViewpointChange(viewpoint.id)}
              className="flex items-center gap-2"
              title={viewpoint.description}
            >
              <Icon className="w-4 h-4" />
              {viewpoint.name}
            </Button>
          )
        })}
      </div>
    </div>
  )
}
