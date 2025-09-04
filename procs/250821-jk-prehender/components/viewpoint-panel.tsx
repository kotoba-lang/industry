"use client"

import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import { Badge } from "@/components/ui/badge"
import { ScrollArea } from "@/components/ui/scroll-area"
import { Calendar, BarChart3, Map, Grid3X3, Share, Clock, Tag, FileText, GitBranch, ArrowRight, ExternalLink } from "lucide-react"
import { formatDistanceToNow, format } from "date-fns"
import { extractTitleFromContent } from "@/lib/utils"
import type { Note, StreamNetwork, StreamQueryResult, ReferenceType } from "@/types/note"

interface ViewpointPanelProps {
  note: Note
  viewpoint: string
  allNotes: Note[]
  streamNetwork?: StreamNetwork
  onAddStreamReference?: (fromStreamId: string, toStreamId: string, referenceType: ReferenceType, context?: string) => void
  onRemoveStreamReference?: (fromStreamId: string, referenceId: string) => void
  findRelatedStreams?: (streamId: string, depth?: number) => StreamQueryResult[]
}

const REFERENCE_TYPE_LABELS: Record<ReferenceType, { label: string; color: string }> = {
  'relates_to': { label: '関連', color: 'bg-blue-100 text-blue-800' },
  'depends_on': { label: '依存', color: 'bg-red-100 text-red-800' },
  'follows_from': { label: '続き', color: 'bg-green-100 text-green-800' },
  'contradicts': { label: '矛盾', color: 'bg-orange-100 text-orange-800' },
  'supports': { label: '支持', color: 'bg-teal-100 text-teal-800' },
  'questions': { label: '疑問', color: 'bg-yellow-100 text-yellow-800' },
  'answers': { label: '回答', color: 'bg-purple-100 text-purple-800' },
  'quotes': { label: '引用', color: 'bg-gray-100 text-gray-800' },
  'extends': { label: '拡張', color: 'bg-indigo-100 text-indigo-800' }
}

export function ViewpointPanel({ 
  note, 
  viewpoint, 
  allNotes, 
  streamNetwork,
  onAddStreamReference,
  onRemoveStreamReference,
  findRelatedStreams 
}: ViewpointPanelProps) {
  const renderTimelineView = () => {
    const events = [
      { type: "created", timestamp: note.createdAt, description: "Stream created" },
      { type: "updated", timestamp: note.updatedAt, description: "Last updated" },
      ...note.tags.map((tag) => ({
        type: "tagged",
        timestamp: note.updatedAt,
        description: `Tagged with #${tag}`,
      })),
      ...note.references.map((ref) => ({
        type: "referenced",
        timestamp: ref.createdAt,
        description: `Referenced ${REFERENCE_TYPE_LABELS[ref.referenceType]?.label} stream`,
      })),
    ].sort((a, b) => b.timestamp.getTime() - a.timestamp.getTime())

    return (
      <div className="space-y-4">
        <div className="flex items-center gap-2 mb-6">
          <Calendar className="w-5 h-5 text-blue-600" />
          <h2 className="text-xl font-semibold">Timeline View</h2>
          <Badge variant="outline" className="text-xs">
            {note.streamType} • {note.streamState}
          </Badge>
        </div>

        <Card>
          <CardHeader>
            <CardTitle className="text-lg">{extractTitleFromContent(note.content)}</CardTitle>
          </CardHeader>
          <CardContent>
            <div className="space-y-4">
              {events.map((event, index) => (
                <div key={index} className="flex items-start gap-3 pb-4 border-b border-gray-100 last:border-b-0">
                  <div className="w-2 h-2 bg-blue-500 rounded-full mt-2" />
                  <div className="flex-1">
                    <p className="font-medium text-sm">{event.description}</p>
                    <p className="text-xs text-gray-500">
                      {format(event.timestamp, "MMM d, yyyy HH:mm")} (
                      {formatDistanceToNow(event.timestamp, { addSuffix: true })})
                    </p>
                  </div>
                </div>
              ))}
            </div>
          </CardContent>
        </Card>
      </div>
    )
  }

  const renderAnalyticsView = () => {
    const wordCount = note.content.split(/\s+/).filter((word) => word.length > 0).length
    const charCount = note.content.length
    const paragraphs = note.content.split("\n\n").filter((p) => p.trim().length > 0).length
    const readingTime = Math.ceil(wordCount / 200) // Average reading speed

    return (
      <div className="space-y-4">
        <div className="flex items-center gap-2 mb-6">
          <BarChart3 className="w-5 h-5 text-green-600" />
          <h2 className="text-xl font-semibold">Analytics View</h2>
        </div>

        <div className="grid grid-cols-2 lg:grid-cols-4 gap-4 mb-6">
          <Card>
            <CardContent className="p-4">
              <div className="text-2xl font-bold text-blue-600">{wordCount}</div>
              <div className="text-sm text-gray-600">Words</div>
            </CardContent>
          </Card>
          <Card>
            <CardContent className="p-4">
              <div className="text-2xl font-bold text-green-600">{charCount}</div>
              <div className="text-sm text-gray-600">Characters</div>
            </CardContent>
          </Card>
          <Card>
            <CardContent className="p-4">
              <div className="text-2xl font-bold text-purple-600">{note.references.length}</div>
              <div className="text-sm text-gray-600">References</div>
            </CardContent>
          </Card>
          <Card>
            <CardContent className="p-4">
              <div className="text-2xl font-bold text-orange-600">{note.subscribers.length}</div>
              <div className="text-sm text-gray-600">Subscribers</div>
            </CardContent>
          </Card>
        </div>

        <Card>
          <CardHeader>
            <CardTitle className="text-lg">Stream Analysis</CardTitle>
          </CardHeader>
          <CardContent>
            <div className="space-y-3">
              <div className="flex justify-between">
                <span className="text-sm text-gray-600">Stream Type</span>
                <Badge variant="outline">{note.streamType}</Badge>
              </div>
              <div className="flex justify-between">
                <span className="text-sm text-gray-600">Stream State</span>
                <Badge variant="secondary">{note.streamState}</Badge>
              </div>
              <div className="flex justify-between">
                <span className="text-sm text-gray-600">Created</span>
                <span className="text-sm font-medium">{format(note.createdAt, "MMM d, yyyy")}</span>
              </div>
              <div className="flex justify-between">
                <span className="text-sm text-gray-600">Last updated</span>
                <span className="text-sm font-medium">{formatDistanceToNow(note.updatedAt, { addSuffix: true })}</span>
              </div>
              <div className="flex justify-between">
                <span className="text-sm text-gray-600">Tags</span>
                <span className="text-sm font-medium">{note.tags.length}</span>
              </div>
            </div>
          </CardContent>
        </Card>
      </div>
    )
  }

  const renderConnectionsView = () => {
    const relatedStreams = findRelatedStreams ? findRelatedStreams(note.streamId, 2) : []
    
    // Fallback to simple tag-based relations if stream network is not available
    const tagBasedRelations = allNotes
      .filter(
        (n) =>
          n.id !== note.id &&
          (n.tags.some((tag) => note.tags.includes(tag)) ||
            n.content.toLowerCase().includes(extractTitleFromContent(note.content).toLowerCase()) ||
            note.content.toLowerCase().includes(extractTitleFromContent(n.content).toLowerCase())),
      )
      .slice(0, 5)

    const displayStreams = relatedStreams.length > 0 ? relatedStreams : tagBasedRelations.map(n => ({ stream: n, depth: 1, referencePath: [], relatedStreams: [] }))

    return (
      <div className="space-y-4">
        <div className="flex items-center gap-2 mb-6">
          <Share className="w-5 h-5 text-purple-600" />
          <h2 className="text-xl font-semibold">Stream Connections</h2>
        </div>

        <Card>
          <CardHeader>
            <CardTitle className="text-lg flex items-center gap-2">
              <GitBranch className="w-4 h-4" />
              Current Stream
            </CardTitle>
          </CardHeader>
          <CardContent>
            <h3 className="font-semibold mb-2">{extractTitleFromContent(note.content)}</h3>
            <div className="flex items-center gap-2 mb-3">
              <Badge variant="outline">{note.streamType}</Badge>
              <Badge variant="secondary">{note.streamState}</Badge>
            </div>
            <div className="flex flex-wrap gap-1 mb-3">
              {note.tags.map((tag) => (
                <Badge key={tag} variant="secondary">
                  #{tag}
                </Badge>
              ))}
            </div>
            <p className="text-sm text-gray-600 line-clamp-3">{note.content}</p>
          </CardContent>
        </Card>

        {/* Direct References */}
        {note.references.length > 0 && (
          <Card>
            <CardHeader>
              <CardTitle className="text-lg">Direct References</CardTitle>
            </CardHeader>
            <CardContent>
              <div className="space-y-3">
                {note.references.map((ref) => {
                  const targetStream = allNotes.find(n => n.streamId === ref.targetStreamId)
                  const refInfo = REFERENCE_TYPE_LABELS[ref.referenceType]
                  
                  return (
                    <div key={ref.id} className="flex items-center gap-3 p-3 border border-gray-200 rounded-lg">
                      <Badge className={`text-xs ${refInfo.color}`}>
                        {refInfo.label}
                      </Badge>
                      <ArrowRight className="w-3 h-3 text-gray-400" />
                      <div className="flex-1">
                        <h4 className="font-medium text-sm">
                          {targetStream ? extractTitleFromContent(targetStream.content) : 'Unknown Stream'}
                        </h4>
                        {ref.context && (
                          <p className="text-xs text-gray-500">{ref.context}</p>
                        )}
                      </div>
                      <ExternalLink className="w-3 h-3 text-gray-400" />
                    </div>
                  )
                })}
              </div>
            </CardContent>
          </Card>
        )}

        {/* Related Streams */}
        <Card>
          <CardHeader>
            <CardTitle className="text-lg">Related Streams</CardTitle>
          </CardHeader>
          <CardContent>
            {displayStreams.length === 0 ? (
              <p className="text-sm text-gray-500">No related streams found</p>
            ) : (
              <div className="space-y-3">
                {displayStreams.map((result, index) => {
                  const stream = 'stream' in result ? result.stream : result
                  return (
                    <div key={stream.id} className="p-3 border border-gray-200 rounded-lg">
                      <div className="flex items-center gap-2 mb-1">
                        <h4 className="font-medium text-sm">{extractTitleFromContent(stream.content)}</h4>
                        <Badge variant="outline" className="text-xs">{stream.streamType}</Badge>
                        {'depth' in result && result.depth > 1 && (
                          <Badge variant="secondary" className="text-xs">Depth {result.depth}</Badge>
                        )}
                      </div>
                      <div className="flex flex-wrap gap-1 mb-2">
                        {stream.tags
                          .filter((tag) => note.tags.includes(tag))
                          .map((tag) => (
                            <Badge key={tag} variant="outline" className="text-xs">
                              #{tag}
                            </Badge>
                          ))}
                      </div>
                      <p className="text-xs text-gray-600 line-clamp-2">{stream.content}</p>
                    </div>
                  )
                })}
              </div>
            )}
          </CardContent>
        </Card>
      </div>
    )
  }

  const renderGridView = () => {
    const sections = note.content.split("\n\n").filter((section) => section.trim().length > 0)

    return (
      <div className="space-y-4">
        <div className="flex items-center gap-2 mb-6">
          <Grid3X3 className="w-5 h-5 text-indigo-600" />
          <h2 className="text-xl font-semibold">Grid View</h2>
        </div>

        <Card className="mb-4">
          <CardHeader>
            <CardTitle className="flex items-center gap-2">
              <FileText className="w-4 h-4" />
              {extractTitleFromContent(note.content)}
            </CardTitle>
            <div className="flex items-center gap-4 text-sm text-gray-500">
              <span className="flex items-center gap-1">
                <Clock className="w-3 h-3" />
                {formatDistanceToNow(note.updatedAt, { addSuffix: true })}
              </span>
              <span className="flex items-center gap-1">
                <Tag className="w-3 h-3" />
                {note.tags.length} tags
              </span>
              <span className="flex items-center gap-1">
                <GitBranch className="w-3 h-3" />
                {note.references.length} refs
              </span>
            </div>
          </CardHeader>
        </Card>

        <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
          {sections.map((section, index) => (
            <Card key={index}>
              <CardContent className="p-4">
                <div className="text-sm font-medium text-gray-700 mb-2">Section {index + 1}</div>
                <p className="text-sm text-gray-600 whitespace-pre-wrap">{section}</p>
              </CardContent>
            </Card>
          ))}
        </div>

        {note.tags.length > 0 && (
          <Card>
            <CardHeader>
              <CardTitle className="text-lg">Tags</CardTitle>
            </CardHeader>
            <CardContent>
              <div className="flex flex-wrap gap-2">
                {note.tags.map((tag) => (
                  <Badge key={tag} variant="secondary">
                    #{tag}
                  </Badge>
                ))}
              </div>
            </CardContent>
          </Card>
        )}
      </div>
    )
  }

  const renderMindMapView = () => {
    const keywords = note.content
      .toLowerCase()
      .split(/\s+/)
      .filter((word) => word.length > 3)
      .reduce(
        (acc, word) => {
          acc[word] = (acc[word] || 0) + 1
          return acc
        },
        {} as Record<string, number>,
      )

    const topKeywords = Object.entries(keywords)
      .sort(([, a], [, b]) => b - a)
      .slice(0, 10)

    return (
      <div className="space-y-4">
        <div className="flex items-center gap-2 mb-6">
          <Map className="w-5 h-5 text-red-600" />
          <h2 className="text-xl font-semibold">Stream Mind Map</h2>
        </div>

        <div className="relative bg-gray-50 rounded-lg p-8 min-h-96">
          {/* Central Node */}
          <div className="absolute top-1/2 left-1/2 transform -translate-x-1/2 -translate-y-1/2">
            <div className="bg-blue-500 text-white px-4 py-2 rounded-full font-semibold text-center min-w-32">
              {extractTitleFromContent(note.content)}
            </div>
          </div>

          {/* Tag Nodes */}
          {note.tags.map((tag, index) => {
            const angle = (index * 360) / note.tags.length
            const radius = 120
            const x = Math.cos((angle * Math.PI) / 180) * radius
            const y = Math.sin((angle * Math.PI) / 180) * radius

            return (
              <div
                key={tag}
                className="absolute transform -translate-x-1/2 -translate-y-1/2"
                style={{
                  left: `calc(50% + ${x}px)`,
                  top: `calc(50% + ${y}px)`,
                }}
              >
                <div className="bg-green-500 text-white px-3 py-1 rounded-full text-sm">#{tag}</div>
                {/* Connection Line */}
                <svg className="absolute top-1/2 left-1/2 transform -translate-x-1/2 -translate-y-1/2 pointer-events-none">
                  <line x1="0" y1="0" x2={-x} y2={-y} stroke="#e5e7eb" strokeWidth="2" />
                </svg>
              </div>
            )
          })}

          {/* Reference Nodes */}
          {note.references.slice(0, 6).map((ref, index) => {
            const targetStream = allNotes.find(n => n.streamId === ref.targetStreamId)
            const angle = (index * 360) / 6 + 30 // Offset from tags
            const radius = 200
            const x = Math.cos((angle * Math.PI) / 180) * radius
            const y = Math.sin((angle * Math.PI) / 180) * radius

            return (
              <div
                key={ref.id}
                className="absolute transform -translate-x-1/2 -translate-y-1/2"
                style={{
                  left: `calc(50% + ${x}px)`,
                  top: `calc(50% + ${y}px)`,
                }}
              >
                <div className="bg-purple-500 text-white px-2 py-1 rounded text-xs">
                  {targetStream ? extractTitleFromContent(targetStream.content).substring(0, 20) + '...' : 'Unknown'}
                </div>
              </div>
            )
          })}
        </div>
      </div>
    )
  }

  return (
    <div className="flex-1 bg-gray-50">
      <ScrollArea className="h-full">
        <div className="p-6">
          {viewpoint === "timeline" && renderTimelineView()}
          {viewpoint === "analytics" && renderAnalyticsView()}
          {viewpoint === "connections" && renderConnectionsView()}
          {viewpoint === "grid" && renderGridView()}
          {viewpoint === "mindmap" && renderMindMapView()}
        </div>
      </ScrollArea>
    </div>
  )
}
