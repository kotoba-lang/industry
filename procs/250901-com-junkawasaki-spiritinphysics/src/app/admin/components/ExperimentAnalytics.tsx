'use client';

import React, { useState, useEffect } from 'react';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { Badge } from '@/components/ui/badge';
import { Progress } from '@/components/ui/progress';
import {
  BarChart,
  Bar,
  XAxis,
  YAxis,
  CartesianGrid,
  Tooltip,
  Legend,
  ResponsiveContainer,
  PieChart,
  Pie,
  Cell,
  LineChart,
  Line
} from 'recharts';
import {
  Brain,
  Activity,
  Target,
  Zap
} from 'lucide-react';

interface AnalyticsData {
  totalParticipants: number;
  completedSessions: number;
  completionRate: number;
  averageSessionDuration: number;
  averageReactionTime: number;
  emotionDistribution: Record<string, number>;
  totalSessions: number;
}

interface ReactionTimeData {
  participantId: string;
  sessionType: string;
  stimulusWord: string;
  responseWord: string;
  reactionTimeMs: number;
  isDelayed: boolean;
  timestamp: string;
}

export function ExperimentAnalytics() {
  const [analyticsData, setAnalyticsData] = useState<AnalyticsData | null>(null);
  const [reactionTimeData, setReactionTimeData] = useState<ReactionTimeData[]>([]);
  const [isLoading, setIsLoading] = useState(true);

  const fetchAnalytics = async () => {
    try {
      const [analyticsResponse, reactionTimeResponse] = await Promise.all([
        fetch('/api/admin/experimental-data?type=analytics'),
        fetch('/api/admin/experimental-data?type=reaction-times')
      ]);

      const analyticsResult = await analyticsResponse.json();
      const reactionTimeResult = await reactionTimeResponse.json();

      if (analyticsResult.success) {
        setAnalyticsData(analyticsResult.data);
      }
      if (reactionTimeResult.success) {
        setReactionTimeData(reactionTimeResult.data);
      }
    } catch (error) {
      console.error('Error fetching analytics:', error);
    } finally {
      setIsLoading(false);
    }
  };

  useEffect(() => {
    fetchAnalytics();
  }, []);

  // Prepare data for charts
  const emotionChartData = analyticsData ? Object.entries(analyticsData.emotionDistribution).map(([emotion, count]) => ({
    name: emotion.charAt(0).toUpperCase() + emotion.slice(1),
    value: count,
    percentage: ((count / Object.values(analyticsData.emotionDistribution).reduce((a, b) => a + b, 0)) * 100).toFixed(1)
  })) : [];

  const reactionTimeChartData = reactionTimeData.reduce((acc, item) => {
    const existing = acc.find(d => d.stimulusWord === item.stimulusWord);
    if (existing) {
      existing.times.push(item.reactionTimeMs);
      existing.average = existing.times.reduce((a, b) => a + b, 0) / existing.times.length;
    } else {
      acc.push({
        stimulusWord: item.stimulusWord,
        times: [item.reactionTimeMs],
        average: item.reactionTimeMs
      });
    }
    return acc;
  }, [] as { stimulusWord: string; times: number[]; average: number }[]).map(item => ({
    word: item.stimulusWord,
    average: Math.round(item.average),
    min: Math.min(...item.times),
    max: Math.max(...item.times)
  }));

  const sessionTypeData = reactionTimeData.reduce((acc, item) => {
    const key = item.sessionType;
    if (!acc[key]) {
      acc[key] = { sessionType: key, count: 0, totalTime: 0 };
    }
    acc[key].count += 1;
    acc[key].totalTime += item.reactionTimeMs;
    return acc;
  }, {} as Record<string, { sessionType: string; count: number; totalTime: number }>);

  const sessionChartData = Object.values(sessionTypeData).map(item => ({
    session: item.sessionType,
    averageTime: Math.round(item.totalTime / item.count),
    totalResponses: item.count
  }));

  const COLORS = ['#0088FE', '#00C49F', '#FFBB28', '#FF8042', '#8884D8'];

  if (isLoading) {
    return (
      <Card>
        <CardContent className="p-6">
          <div className="flex items-center justify-center">
            <div className="animate-spin rounded-full h-8 w-8 border-b-2 border-gray-900"></div>
            <span className="ml-2">分析データを読み込み中...</span>
          </div>
        </CardContent>
      </Card>
    );
  }

  if (!analyticsData) {
    return (
      <Card>
        <CardContent className="p-6">
          <div className="text-center text-gray-500">
            分析データの読み込みに失敗しました
          </div>
        </CardContent>
      </Card>
    );
  }

  return (
    <div className="space-y-6">
      {/* Key Metrics */}
      <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-4">
        <Card>
          <CardHeader className="flex flex-row items-center justify-between space-y-0 pb-2">
            <CardTitle className="text-sm font-medium">平均反応時間</CardTitle>
            <Zap className="h-4 w-4 text-muted-foreground" />
          </CardHeader>
          <CardContent>
            <div className="text-2xl font-bold">{analyticsData.averageReactionTime.toFixed(0)}ms</div>
            <p className="text-xs text-muted-foreground">
              全参加者の平均
            </p>
          </CardContent>
        </Card>

        <Card>
          <CardHeader className="flex flex-row items-center justify-between space-y-0 pb-2">
            <CardTitle className="text-sm font-medium">完了率</CardTitle>
            <Target className="h-4 w-4 text-muted-foreground" />
          </CardHeader>
          <CardContent>
            <div className="text-2xl font-bold">{analyticsData.completionRate.toFixed(1)}%</div>
            <Progress value={analyticsData.completionRate} className="mt-2" />
          </CardContent>
        </Card>

        <Card>
          <CardHeader className="flex flex-row items-center justify-between space-y-0 pb-2">
            <CardTitle className="text-sm font-medium">感情タイプ数</CardTitle>
            <Brain className="h-4 w-4 text-muted-foreground" />
          </CardHeader>
          <CardContent>
            <div className="text-2xl font-bold">{Object.keys(analyticsData.emotionDistribution).length}</div>
            <p className="text-xs text-muted-foreground">
              検出された感情の種類
            </p>
          </CardContent>
        </Card>

        <Card>
          <CardHeader className="flex flex-row items-center justify-between space-y-0 pb-2">
            <CardTitle className="text-sm font-medium">総応答数</CardTitle>
            <Activity className="h-4 w-4 text-muted-foreground" />
          </CardHeader>
          <CardContent>
            <div className="text-2xl font-bold">{reactionTimeData.length}</div>
            <p className="text-xs text-muted-foreground">
              記録された応答
            </p>
          </CardContent>
        </Card>
      </div>

      {/* Charts Row 1 */}
      <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
        {/* Reaction Time by Stimulus Word */}
        <Card>
          <CardHeader>
            <CardTitle>刺激語ごとの平均反応時間</CardTitle>
            <CardDescription>
              各刺激語に対する平均反応時間を比較
            </CardDescription>
          </CardHeader>
          <CardContent>
            <ResponsiveContainer width="100%" height={300}>
              <BarChart data={reactionTimeChartData}>
                <CartesianGrid strokeDasharray="3 3" />
                <XAxis
                  dataKey="word"
                  fontSize={12}
                  angle={-45}
                  textAnchor="end"
                  height={80}
                />
                <YAxis />
                <Tooltip
                  formatter={(value: number) => [`${value}ms`, '平均反応時間']}
                  labelFormatter={(label) => `刺激語: ${label}`}
                />
                <Bar dataKey="average" fill="#8884d8" />
              </BarChart>
            </ResponsiveContainer>
          </CardContent>
        </Card>

        {/* Emotion Distribution */}
        <Card>
          <CardHeader>
            <CardTitle>感情分布</CardTitle>
            <CardDescription>
              実験中に検出された感情の分布
            </CardDescription>
          </CardHeader>
          <CardContent>
            <ResponsiveContainer width="100%" height={300}>
              <PieChart>
                <Pie
                  data={emotionChartData}
                  cx="50%"
                  cy="50%"
                  labelLine={false}
                  label={({ name, percentage }) => `${name}: ${percentage}%`}
                  outerRadius={80}
                  fill="#8884d8"
                  dataKey="value"
                >
                  {emotionChartData.map((entry, index) => (
                    <Cell key={`cell-${index}`} fill={COLORS[index % COLORS.length]} />
                  ))}
                </Pie>
                <Tooltip />
              </PieChart>
            </ResponsiveContainer>
          </CardContent>
        </Card>
      </div>

      {/* Charts Row 2 */}
      <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
        {/* Session Performance */}
        <Card>
          <CardHeader>
            <CardTitle>セッションごとのパフォーマンス</CardTitle>
            <CardDescription>
              各セッションタイプの平均反応時間と応答数
            </CardDescription>
          </CardHeader>
          <CardContent>
            <ResponsiveContainer width="100%" height={300}>
              <LineChart data={sessionChartData}>
                <CartesianGrid strokeDasharray="3 3" />
                <XAxis dataKey="session" />
                <YAxis yAxisId="left" />
                <YAxis yAxisId="right" orientation="right" />
                <Tooltip />
                <Legend />
                <Bar yAxisId="left" dataKey="averageTime" fill="#8884d8" name="平均反応時間 (ms)" />
                <Line
                  yAxisId="right"
                  type="monotone"
                  dataKey="totalResponses"
                  stroke="#82ca9d"
                  strokeWidth={2}
                  name="総応答数"
                />
              </LineChart>
            </ResponsiveContainer>
          </CardContent>
        </Card>

        {/* Performance Metrics Summary */}
        <Card>
          <CardHeader>
            <CardTitle>パフォーマンス指標</CardTitle>
            <CardDescription>
              実験の主要な統計指標
            </CardDescription>
          </CardHeader>
          <CardContent className="space-y-4">
            <div className="flex items-center justify-between">
              <span className="text-sm font-medium">完了率</span>
              <div className="flex items-center space-x-2">
                <Progress value={analyticsData.completionRate} className="w-20" />
                <span className="text-sm font-medium">{analyticsData.completionRate.toFixed(1)}%</span>
              </div>
            </div>

            <div className="flex items-center justify-between">
              <span className="text-sm font-medium">平均セッション時間</span>
              <Badge variant="secondary">
                {Math.round(analyticsData.averageSessionDuration / 60)}分
              </Badge>
            </div>

            <div className="flex items-center justify-between">
              <span className="text-sm font-medium">総参加者数</span>
              <Badge variant="outline">
                {analyticsData.totalParticipants}人
              </Badge>
            </div>

            <div className="flex items-center justify-between">
              <span className="text-sm font-medium">総セッション数</span>
              <Badge variant="outline">
                {analyticsData.totalSessions}回
              </Badge>
            </div>

            <div className="pt-4 border-t">
              <h4 className="text-sm font-medium mb-2">感情分布トップ3</h4>
              <div className="space-y-2">
              {emotionChartData.slice(0, 3).map((emotion) => (
                <div key={emotion.name} className="flex items-center justify-between">
                  <span className="text-sm">{emotion.name}</span>
                  <Badge variant="secondary" className="text-xs">
                    {emotion.percentage}%
                  </Badge>
                </div>
              ))}
              </div>
            </div>
          </CardContent>
        </Card>
      </div>
    </div>
  );
}
