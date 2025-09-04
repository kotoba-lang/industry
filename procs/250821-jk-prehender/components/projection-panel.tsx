import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import { Badge } from "@/components/ui/badge"
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs"
import { BarChart, Bar, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer } from "recharts"

interface ProjectionPanelProps {
  selectedTopic: string | null
  events: any[]
}

export function ProjectionPanel({ selectedTopic, events }: ProjectionPanelProps) {
  const projections = [
    {
      id: "summary",
      name: "Summary View",
      data: {
        totalEvents: 47,
        activeTopics: 6,
        todayEvents: 12,
        weeklyGrowth: "+23%",
      },
    },
    {
      id: "analytics",
      name: "Analytics",
      data: [
        { name: "Notes", count: 24, color: "#3b82f6" },
        { name: "Ideas", count: 12, color: "#10b981" },
        { name: "Tasks", count: 8, color: "#f59e0b" },
        { name: "Meetings", count: 6, color: "#8b5cf6" },
      ],
    },
    {
      id: "timeline",
      name: "Timeline",
      data: {
        recent: [
          { time: "2 min ago", event: "New idea captured", type: "idea" },
          { time: "15 min ago", event: "Task completed", type: "task" },
          { time: "1 hour ago", event: "Meeting notes added", type: "note" },
        ],
      },
    },
  ]

  return (
    <div className="h-full p-6 bg-gradient-to-br from-slate-50 to-blue-50">
      <div className="mb-6">
        <h2 className="text-2xl font-bold text-slate-800 mb-2">Projections</h2>
        <p className="text-slate-600">Different perspectives of your event data</p>
      </div>

      <Tabs defaultValue="summary" className="h-full">
        <TabsList className="grid w-full grid-cols-3">
          <TabsTrigger value="summary">Summary</TabsTrigger>
          <TabsTrigger value="analytics">Analytics</TabsTrigger>
          <TabsTrigger value="timeline">Timeline</TabsTrigger>
        </TabsList>

        <TabsContent value="summary" className="mt-6">
          <div className="grid grid-cols-2 lg:grid-cols-4 gap-4 mb-6">
            <Card>
              <CardHeader className="pb-2">
                <CardTitle className="text-sm font-medium text-slate-600">Total Events</CardTitle>
              </CardHeader>
              <CardContent>
                <div className="text-2xl font-bold text-slate-800">47</div>
              </CardContent>
            </Card>

            <Card>
              <CardHeader className="pb-2">
                <CardTitle className="text-sm font-medium text-slate-600">Active Topics</CardTitle>
              </CardHeader>
              <CardContent>
                <div className="text-2xl font-bold text-slate-800">6</div>
              </CardContent>
            </Card>

            <Card>
              <CardHeader className="pb-2">
                <CardTitle className="text-sm font-medium text-slate-600">Today's Events</CardTitle>
              </CardHeader>
              <CardContent>
                <div className="text-2xl font-bold text-slate-800">12</div>
              </CardContent>
            </Card>

            <Card>
              <CardHeader className="pb-2">
                <CardTitle className="text-sm font-medium text-slate-600">Weekly Growth</CardTitle>
              </CardHeader>
              <CardContent>
                <div className="text-2xl font-bold text-green-600">+23%</div>
              </CardContent>
            </Card>
          </div>

          <Card>
            <CardHeader>
              <CardTitle>Event Distribution</CardTitle>
            </CardHeader>
            <CardContent>
              <div className="space-y-3">
                {projections[1].data.map((item) => (
                  <div key={item.name} className="flex items-center justify-between">
                    <div className="flex items-center gap-3">
                      <div className="w-4 h-4 rounded-full" style={{ backgroundColor: item.color }} />
                      <span className="font-medium">{item.name}</span>
                    </div>
                    <Badge variant="secondary">{item.count}</Badge>
                  </div>
                ))}
              </div>
            </CardContent>
          </Card>
        </TabsContent>

        <TabsContent value="analytics" className="mt-6">
          <Card>
            <CardHeader>
              <CardTitle>Event Analytics</CardTitle>
            </CardHeader>
            <CardContent>
              <ResponsiveContainer width="100%" height={300}>
                <BarChart data={projections[1].data}>
                  <CartesianGrid strokeDasharray="3 3" />
                  <XAxis dataKey="name" />
                  <YAxis />
                  <Tooltip />
                  <Bar dataKey="count" fill="#3b82f6" />
                </BarChart>
              </ResponsiveContainer>
            </CardContent>
          </Card>
        </TabsContent>

        <TabsContent value="timeline" className="mt-6">
          <Card>
            <CardHeader>
              <CardTitle>Recent Activity</CardTitle>
            </CardHeader>
            <CardContent>
              <div className="space-y-4">
                {projections[2].data.recent.map((item, index) => (
                  <div key={index} className="flex items-center gap-4 p-3 rounded-lg bg-slate-50">
                    <div className="w-2 h-2 rounded-full bg-blue-500" />
                    <div className="flex-1">
                      <p className="font-medium text-slate-800">{item.event}</p>
                      <p className="text-sm text-slate-500">{item.time}</p>
                    </div>
                    <Badge variant="outline">{item.type}</Badge>
                  </div>
                ))}
              </div>
            </CardContent>
          </Card>
        </TabsContent>
      </Tabs>
    </div>
  )
}
