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
  Line,
  RadarChart,
  PolarGrid,
  PolarAngleAxis,
  PolarRadiusAxis,
  Radar
} from 'recharts';
import {
  Brain,
  Activity,
  Target,
  Zap,
  Smile,
  Frown,
  Meh,
  Play
} from 'lucide-react';
import { EmotionAnalysisControls } from './EmotionAnalysisControls';

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

interface EmotionData {
  participantId: string;
  videoFile: string;
  sessionType: string;
  emotions: Array<{
    name: string;
    score: number;
    confidence: number;
  }>;
  timestamp: string;
  processingTime: number;
}

interface EmotionStatistics {
  totalAnalyses: number;
  averageEmotions: Record<string, number>;
  dominantEmotions: Array<{ emotion: string; count: number }>;
  processingStats: {
    averageTime: number;
    totalTime: number;
  };
}

export function ExperimentAnalytics() {
  const [analyticsData, setAnalyticsData] = useState<AnalyticsData | null>(null);
  const [reactionTimeData, setReactionTimeData] = useState<ReactionTimeData[]>([]);
  const [emotionData, setEmotionData] = useState<EmotionData[]>([]);
  const [emotionStats, setEmotionStats] = useState<EmotionStatistics | null>(null);
  const [isLoading, setIsLoading] = useState(true);

  const fetchAnalytics = async () => {
    try {
      const [analyticsResponse, reactionTimeResponse, emotionResponse] = await Promise.all([
        fetch('/api/admin/experimental-data?type=analytics'),
        fetch('/api/admin/experimental-data?type=reaction-times'),
        fetch('/api/admin/emotion-analysis?action=get-statistics')
      ]);

      const analyticsResult = await analyticsResponse.json();
      const reactionTimeResult = await reactionTimeResponse.json();
      const emotionResult = await emotionResponse.json();

      if (analyticsResult.success) {
        setAnalyticsData(analyticsResult.data);
      }
      if (reactionTimeResult.success) {
        setReactionTimeData(reactionTimeResult.data);
      }
      if (emotionResult.success) {
        // emotionResult.data.data contains the array of emotion analysis results
        setEmotionData(emotionResult.data.data || []);
        setEmotionStats(emotionResult.data.statistics);
      }
    } catch (error) {
      console.error('Error fetching analytics:', error);
    } finally {
      setIsLoading(false);
    }
  };

  const handleAnalysisComplete = () => {
    // 感情分析完了後にデータを再取得
    fetchAnalytics();
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

  // Prepare Hume emotion data for charts
  const humeEmotionChartData = emotionStats ? Object.entries(emotionStats.averageEmotions).map(([emotion, score]) => ({
    emotion: emotion.charAt(0).toUpperCase() + emotion.slice(1),
    score: Math.round(score * 100) / 100,
    fullMark: 1
  })) : [];

  const dominantEmotionChartData = emotionStats ? emotionStats.dominantEmotions.slice(0, 8).map(item => ({
    name: item.emotion.charAt(0).toUpperCase() + item.emotion.slice(1),
    value: item.count,
    percentage: emotionStats.totalAnalyses > 0 ? ((item.count / emotionStats.totalAnalyses) * 100).toFixed(1) : '0'
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
            <CardTitle className="text-sm font-medium">感情分析数</CardTitle>
            <Brain className="h-4 w-4 text-muted-foreground" />
          </CardHeader>
          <CardContent>
            <div className="text-2xl font-bold">{emotionStats?.totalAnalyses || 0}</div>
            <p className="text-xs text-muted-foreground">
              Hume AIによる分析完了数
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
        {/* Hume Emotion Radar Chart */}
        <Card>
          <CardHeader>
            <CardTitle>Hume AI 感情分析結果</CardTitle>
            <CardDescription>
              ビデオ分析による平均感情スコア（レーダー図）
            </CardDescription>
          </CardHeader>
          <CardContent>
            {humeEmotionChartData.length > 0 ? (
              <ResponsiveContainer width="100%" height={300}>
                <RadarChart data={humeEmotionChartData}>
                  <PolarGrid />
                  <PolarAngleAxis dataKey="emotion" />
                  <PolarRadiusAxis angle={90} domain={[0, 1]} />
                  <Radar
                    name="感情スコア"
                    dataKey="score"
                    stroke="#8884d8"
                    fill="#8884d8"
                    fillOpacity={0.3}
                  />
                  <Tooltip formatter={(value: number) => [`${value}`, 'スコア']} />
                </RadarChart>
              </ResponsiveContainer>
            ) : (
              <div className="flex items-center justify-center h-[300px] text-gray-500">
                <div className="text-center">
                  <Brain className="h-12 w-12 mx-auto mb-2 text-gray-400" />
                  <p>感情分析データがありません</p>
                  <p className="text-sm">ビデオファイルの分析を実行してください</p>
                </div>
              </div>
            )}
          </CardContent>
        </Card>

        {/* Dominant Emotions Pie Chart */}
        <Card>
          <CardHeader>
            <CardTitle>主要感情分布</CardTitle>
            <CardDescription>
              最も頻出する感情の割合
            </CardDescription>
          </CardHeader>
          <CardContent>
            {dominantEmotionChartData.length > 0 ? (
              <ResponsiveContainer width="100%" height={300}>
                <PieChart>
                  <Pie
                    data={dominantEmotionChartData}
                    cx="50%"
                    cy="50%"
                    labelLine={false}
                    label={({ name, percentage }) => `${name}: ${percentage}%`}
                    outerRadius={80}
                    fill="#8884d8"
                    dataKey="value"
                  >
                    {dominantEmotionChartData.map((entry, index) => (
                      <Cell key={`cell-${index}`} fill={COLORS[index % COLORS.length]} />
                    ))}
                  </Pie>
                  <Tooltip formatter={(value: number) => [`${value}件`, '出現回数']} />
                </PieChart>
              </ResponsiveContainer>
            ) : (
              <div className="flex items-center justify-center h-[300px] text-gray-500">
                <div className="text-center">
                  <Meh className="h-12 w-12 mx-auto mb-2 text-gray-400" />
                  <p>主要感情データがありません</p>
                  <p className="text-sm">感情分析を実行してください</p>
                </div>
              </div>
            )}
          </CardContent>
        </Card>
      </div>

      {/* Emotion Analysis Summary */}
      {emotionStats && emotionStats.totalAnalyses > 0 && (
        <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
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
                {emotionChartData && emotionChartData.length > 0 ? (
                  emotionChartData.slice(0, 3).map((emotion) => (
                    <div key={emotion.name} className="flex items-center justify-between">
                      <span className="text-sm">{emotion.name}</span>
                      <Badge variant="secondary" className="text-xs">
                        {emotion.percentage}%
                      </Badge>
                    </div>
                  ))
                ) : (
                  <p className="text-sm text-gray-500">感情データがありません</p>
                )}
              </div>
            </div>
          </CardContent>
        </Card>
      </div>
    )}

    {/* Emotion Analysis Controls - Always visible */}
    <div className="grid grid-cols-1 gap-6">
      <EmotionAnalysisControls onAnalysisComplete={handleAnalysisComplete} />
    </div>
  </div>
  );
}
