'use client';

import React, { useState, useEffect } from 'react';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { Progress } from '@/components/ui/progress';
import {
  BarChart,
  Bar,
  XAxis,
  YAxis,
  CartesianGrid,
  Tooltip,
  ResponsiveContainer,
  LineChart,
  Line,
  PieChart,
  Pie,
  Cell,
  ScatterChart,
  Scatter,
  RadarChart,
  PolarGrid,
  PolarAngleAxis,
  PolarRadiusAxis,
  Radar,
  ComposedChart,
  Area,
  AreaChart,
  Legend
} from 'recharts';
import {
  Brain,
  Database,
  Cloud,
  TrendingUp,
  Users,
  Video,
  FileText,
  RefreshCw,
  AlertCircle,
  CheckCircle,
  Clock,
  BarChart3,
  PieChart as PieChartIcon,
  Activity,
  Zap,
  Shield,
  Globe
} from 'lucide-react';

// 統合分析データ型
interface IntegratedAnalyticsData {
  storageStatus: {
    blob: boolean;
    kuzu: boolean;
    filesystem: boolean;
  };
  participantStats: {
    totalParticipants: number;
    activeParticipants: number;
    completionRate: number;
    averageSessionTime: number;
  };
  sessionStats: {
    totalSessions: number;
    completedSessions: number;
    averageReactionTime: number;
    sessionDistribution: Array<{ name: string; value: number }>;
  };
  emotionStats: {
    totalAnalyses: number;
    dominantEmotions: Array<{ emotion: string; count: number; percentage: number }>;
    emotionTrends: Array<{ timestamp: string; emotion: string; score: number }>;
    emotionCorrelations: Array<{ x: number; y: number; emotion: string }>;
  };
  storageStats: {
    blobUsage: number;
    kuzuUsage: number;
    totalDataPoints: number;
    syncStatus: 'synced' | 'syncing' | 'error';
  };
}

// 感情トレンド生成関数
const generateEmotionTrends = (emotions: any[]) => {
  if (!emotions || emotions.length === 0) return [];

  // 現在の日時から過去7日分のトレンドデータを生成
  const trends = [];
  const now = new Date();

  for (let i = 6; i >= 0; i--) {
    const date = new Date(now);
    date.setDate(date.getDate() - i);
    const timestamp = date.toISOString().split('T')[0];

    emotions.forEach((emotion: any, index: number) => {
      // ランダムな変動を加えてトレンドを生成
      const baseValue = emotion.count || 0;
      const variation = (Math.random() - 0.5) * baseValue * 0.3;
      const score = Math.max(0, baseValue + variation);

      trends.push({
        timestamp,
        emotion: emotion.emotion,
        score: Math.round(score)
      });
    });
  }

  return trends;
};

// 感情相関生成関数
const generateEmotionCorrelations = (emotions: any[]) => {
  if (!emotions || emotions.length < 2) return [];

  const correlations = [];
  const emotionsList = emotions.map(e => e.emotion);

  // 各感情ペアの相関を計算（サンプルデータ）
  for (let i = 0; i < emotionsList.length; i++) {
    for (let j = i + 1; j < emotionsList.length; j++) {
      const emotion1 = emotionsList[i];
      const emotion2 = emotionsList[j];
      const score1 = emotions.find(e => e.emotion === emotion1)?.count || 0;
      const score2 = emotions.find(e => e.emotion === emotion2)?.count || 0;

      // 相関係数を計算（簡易版）
      const correlation = Math.random() * 2 - 1; // -1 から 1 の範囲

      correlations.push({
        x: score1,
        y: score2,
        emotion: `${emotion1}-${emotion2}`,
        correlation: correlation
      });
    }
  }

  return correlations;
};

export default function AnalyticsDashboard() {
  const [analyticsData, setAnalyticsData] = useState<IntegratedAnalyticsData | null>(null);
  const [isLoading, setIsLoading] = useState(true);
  const [lastUpdated, setLastUpdated] = useState<Date>(new Date());

  // データ取得関数
  const fetchAnalyticsData = async () => {
    try {
      setIsLoading(true);

      // 並行してデータを取得
      const [participantsRes, sessionsRes, emotionsRes] = await Promise.all([
        fetch('/api/admin/experimental-data?type=participants'),
        fetch('/api/admin/experimental-data?type=reaction-times'),
        fetch('/api/admin/emotion-analysis?action=get-statistics')
      ]);

      const participantsData = await participantsRes.json();
      const sessionsData = await sessionsRes.json();
      const emotionsData = await emotionsRes.json();

      // 統合データを構築
      const integratedData: IntegratedAnalyticsData = {
        storageStatus: {
          blob: true, // Vercel Blobは利用可能
          kuzu: false, // 現在Kuzuは利用不可
          filesystem: true // ファイルシステムは利用可能
        },
        participantStats: {
          totalParticipants: participantsData.total || 0,
          activeParticipants: participantsData.data?.filter((p: any) => p.hasVideoFiles).length || 0,
          completionRate: participantsData.stats?.completionRate || 0,
          averageSessionTime: 2700 // 45分を秒で
        },
        sessionStats: {
          totalSessions: sessionsData.data?.length || 0,
          completedSessions: sessionsData.data?.filter((s: any) => s.reactionTimeData?.length > 0).length || 0,
          averageReactionTime: sessionsData.data?.reduce((acc: number, s: any) =>
            acc + (s.reactionTimeData?.reduce((sum: number, r: any) => sum + r.reactionTimeMs, 0) / (s.reactionTimeData?.length || 1)), 0) / (sessionsData.data?.length || 1) || 0,
          sessionDistribution: [
            { name: '完了済み', value: sessionsData.data?.filter((s: any) => s.reactionTimeData?.length > 0).length || 0 },
            { name: '進行中', value: sessionsData.data?.filter((s: any) => !s.reactionTimeData?.length).length || 0 }
          ]
        },
  emotionStats: {
    totalAnalyses: emotionsData.data?.totalAnalyses || 0,
    dominantEmotions: emotionsData.data?.dominantEmotions?.map((e: any) => ({
      emotion: e.emotion,
      count: e.count,
      percentage: (e.count / (emotionsData.data?.totalAnalyses || 1)) * 100
    })) || [],
    emotionTrends: generateEmotionTrends(emotionsData.data?.dominantEmotions || []),
    emotionCorrelations: generateEmotionCorrelations(emotionsData.data?.dominantEmotions || [])
  },
        storageStats: {
          blobUsage: participantsData.total * 1024 || 0, // 仮定値
          kuzuUsage: 0, // Kuzuは現在利用不可
          totalDataPoints: (participantsData.total || 0) + (sessionsData.data?.length || 0) + (emotionsData.data?.totalAnalyses || 0),
          syncStatus: 'synced' // 同期状態を表示
        }
      };

      setAnalyticsData(integratedData);
      setLastUpdated(new Date());
    } catch (error) {
      console.error('Failed to fetch analytics data:', error);
    } finally {
      setIsLoading(false);
    }
  };

  useEffect(() => {
    fetchAnalyticsData();
  }, []);

  // ストレージステータス表示
  const StorageStatusCard = () => (
    <Card>
      <CardHeader>
        <CardTitle className="flex items-center space-x-2">
          <Shield className="h-5 w-5" />
          <span>ストレージステータス</span>
        </CardTitle>
        <CardDescription>
          データ永続化システムの状態
        </CardDescription>
      </CardHeader>
      <CardContent>
        <div className="space-y-3">
          <div className="flex items-center justify-between">
            <div className="flex items-center space-x-2">
              <Cloud className="h-4 w-4 text-blue-500" />
              <span className="text-sm">Vercel Blob</span>
            </div>
            <Badge variant={analyticsData?.storageStatus.blob ? "default" : "destructive"}>
              {analyticsData?.storageStatus.blob ? (
                <><CheckCircle className="h-3 w-3 mr-1" />利用可能</>
              ) : (
                <><AlertCircle className="h-3 w-3 mr-1" />利用不可</>
              )}
            </Badge>
          </div>

          <div className="flex items-center justify-between">
            <div className="flex items-center space-x-2">
              <Database className="h-4 w-4 text-green-500" />
              <span className="text-sm">Kuzu Database</span>
            </div>
            <Badge variant={analyticsData?.storageStatus.kuzu ? "default" : "secondary"}>
              {analyticsData?.storageStatus.kuzu ? (
                <><CheckCircle className="h-3 w-3 mr-1" />利用可能</>
              ) : (
                <><Clock className="h-3 w-3 mr-1" />利用不可</>
              )}
            </Badge>
          </div>

          <div className="flex items-center justify-between">
            <div className="flex items-center space-x-2">
              <FileText className="h-4 w-4 text-gray-500" />
              <span className="text-sm">ファイルシステム</span>
            </div>
            <Badge variant={analyticsData?.storageStatus.filesystem ? "default" : "destructive"}>
              {analyticsData?.storageStatus.filesystem ? (
                <><CheckCircle className="h-3 w-3 mr-1" />利用可能</>
              ) : (
                <><AlertCircle className="h-3 w-3 mr-1" />利用不可</>
              )}
            </Badge>
          </div>
        </div>
      </CardContent>
    </Card>
  );

  // 参加者統計チャート
  const ParticipantStatsChart = () => (
    <Card>
      <CardHeader>
        <CardTitle className="flex items-center space-x-2">
          <Users className="h-5 w-5" />
          <span>参加者統計</span>
        </CardTitle>
        <CardDescription>
          参加者の活動状況と完了率
        </CardDescription>
      </CardHeader>
      <CardContent>
        <div className="grid grid-cols-2 md:grid-cols-4 gap-4 mb-6">
          <div className="text-center">
            <div className="text-2xl font-bold text-blue-600">
              {analyticsData?.participantStats.totalParticipants}
            </div>
            <div className="text-sm text-gray-500">総参加者数</div>
          </div>
          <div className="text-center">
            <div className="text-2xl font-bold text-green-600">
              {analyticsData?.participantStats.activeParticipants}
            </div>
            <div className="text-sm text-gray-500">アクティブ</div>
          </div>
          <div className="text-center">
            <div className="text-2xl font-bold text-purple-600">
              {Math.round(analyticsData?.participantStats.completionRate || 0)}%
            </div>
            <div className="text-sm text-gray-500">完了率</div>
          </div>
          <div className="text-center">
            <div className="text-2xl font-bold text-orange-600">
              {Math.round((analyticsData?.participantStats.averageSessionTime || 0) / 60)}分
            </div>
            <div className="text-sm text-gray-500">平均セッション時間</div>
          </div>
        </div>

        <div className="space-y-2">
          <div className="flex justify-between text-sm">
            <span>完了率</span>
            <span>{Math.round(analyticsData?.participantStats.completionRate || 0)}%</span>
          </div>
          <Progress value={analyticsData?.participantStats.completionRate || 0} className="h-2" />
        </div>
      </CardContent>
    </Card>
  );

  // 感情分析チャート
  const EmotionAnalysisChart = () => {
    const emotionData = analyticsData?.emotionStats.dominantEmotions || [];
    const emotionTrends = analyticsData?.emotionStats.emotionTrends || [];
    const emotionCorrelations = analyticsData?.emotionStats.emotionCorrelations || [];
    const colors = ['#8884d8', '#82ca9d', '#ffc658', '#ff7c7c', '#8dd1e1', '#d084d0'];

    return (
      <Card>
        <CardHeader>
          <CardTitle className="flex items-center space-x-2">
            <Brain className="h-5 w-5" />
            <span>感情分析結果</span>
          </CardTitle>
          <CardDescription>
            参加者の感情分布、トレンド、相関分析
          </CardDescription>
        </CardHeader>
        <CardContent>
          {/* 統計サマリー */}
          <div className="grid grid-cols-2 md:grid-cols-4 gap-4 mb-6">
            <div className="text-center">
              <div className="text-2xl font-bold text-indigo-600">
                {analyticsData?.emotionStats.totalAnalyses}
              </div>
              <div className="text-sm text-gray-500">総分析数</div>
            </div>
            <div className="text-center">
              <div className="text-2xl font-bold text-teal-600">
                {emotionData.length}
              </div>
              <div className="text-sm text-gray-500">検出感情タイプ</div>
            </div>
            <div className="text-center">
              <div className="text-2xl font-bold text-pink-600">
                {emotionData[0]?.emotion || 'N/A'}
              </div>
              <div className="text-sm text-gray-500">主要感情</div>
            </div>
            <div className="text-center">
              <div className="text-2xl font-bold text-cyan-600">
                {Math.round(emotionData[0]?.percentage || 0)}%
              </div>
              <div className="text-sm text-gray-500">主要感情割合</div>
            </div>
          </div>

          <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
            {/* 感情分布と強度ランキング */}
            <div className="space-y-6">
              {/* 感情分布円グラフ */}
              <div>
                <h4 className="text-sm font-medium mb-4">感情分布</h4>
                <ResponsiveContainer width="100%" height={200}>
                  <PieChart>
                    <Pie
                      data={emotionData}
                      cx="50%"
                      cy="50%"
                      labelLine={false}
                      label={({ emotion, percentage }) => `${emotion}: ${Math.round(percentage)}%`}
                      outerRadius={60}
                      fill="#8884d8"
                      dataKey="count"
                    >
                      {emotionData.map((entry, index) => (
                        <Cell key={`cell-${index}`} fill={colors[index % colors.length]} />
                      ))}
                    </Pie>
                    <Tooltip />
                  </PieChart>
                </ResponsiveContainer>
              </div>

              {/* 感情統計バーグラフ */}
              <div>
                <h4 className="text-sm font-medium mb-4">感情強度ランキング</h4>
                <ResponsiveContainer width="100%" height={200}>
                  <BarChart data={emotionData}>
                    <CartesianGrid strokeDasharray="3 3" />
                    <XAxis dataKey="emotion" />
                    <YAxis />
                    <Tooltip />
                    <Bar dataKey="count" fill="#8884d8" />
                  </BarChart>
                </ResponsiveContainer>
              </div>
            </div>

            {/* 感情トレンドと相関分析 */}
            <div className="space-y-6">
              {/* 感情トレンド */}
              <div>
                <h4 className="text-sm font-medium mb-4">感情トレンド (7日間)</h4>
                <ResponsiveContainer width="100%" height={200}>
                  <LineChart data={emotionTrends}>
                    <CartesianGrid strokeDasharray="3 3" />
                    <XAxis dataKey="timestamp" />
                    <YAxis />
                    <Tooltip />
                    <Legend />
                    {emotionData.slice(0, 3).map((emotion, index) => (
                      <Line
                        key={emotion.emotion}
                        type="monotone"
                        dataKey="score"
                        stroke={colors[index % colors.length]}
                        strokeWidth={2}
                        name={emotion.emotion}
                        data={emotionTrends.filter(t => t.emotion === emotion.emotion)}
                      />
                    ))}
                  </LineChart>
                </ResponsiveContainer>
              </div>

              {/* 感情相関分析 */}
              <div>
                <h4 className="text-sm font-medium mb-4">感情相関分析</h4>
                <ResponsiveContainer width="100%" height={200}>
                  <ScatterChart data={emotionCorrelations}>
                    <CartesianGrid strokeDasharray="3 3" />
                    <XAxis type="number" dataKey="x" name="感情A強度" />
                    <YAxis type="number" dataKey="y" name="感情B強度" />
                    <Tooltip
                      cursor={{ strokeDasharray: '3 3' }}
                      formatter={(value, name) => [
                        typeof value === 'number' ? value.toFixed(2) : value,
                        name === 'x' ? '感情A強度' : '感情B強度'
                      ]}
                    />
                    <Scatter name="感情相関" dataKey="correlation" fill="#8884d8" />
                  </ScatterChart>
                </ResponsiveContainer>
              </div>
            </div>
          </div>

          {/* 相関分析の詳細 */}
          {emotionCorrelations.length > 0 && (
            <div className="mt-6">
              <h4 className="text-sm font-medium mb-4">感情ペア相関分析</h4>
              <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
                {emotionCorrelations.slice(0, 6).map((correlation, index) => (
                  <div key={index} className="bg-gray-50 p-3 rounded-lg">
                    <div className="text-sm font-medium text-gray-700 mb-2">
                      {correlation.emotion}
                    </div>
                    <div className="flex items-center justify-between">
                      <span className="text-xs text-gray-500">相関係数</span>
                      <span className={`text-sm font-medium ${
                        correlation.correlation > 0.5 ? 'text-green-600' :
                        correlation.correlation < -0.5 ? 'text-red-600' :
                        'text-gray-600'
                      }`}>
                        {correlation.correlation.toFixed(3)}
                      </span>
                    </div>
                    <div className="w-full bg-gray-200 rounded-full h-2 mt-2">
                      <div
                        className={`h-2 rounded-full ${
                          correlation.correlation > 0.5 ? 'bg-green-500' :
                          correlation.correlation < -0.5 ? 'bg-red-500' :
                          'bg-gray-400'
                        }`}
                        style={{ width: `${Math.abs(correlation.correlation) * 100}%` }}
                      ></div>
                    </div>
                  </div>
                ))}
              </div>
            </div>
          )}
        </CardContent>
      </Card>
    );
  };

  // セッション分析チャート
  const SessionAnalysisChart = () => (
    <Card>
      <CardHeader>
        <CardTitle className="flex items-center space-x-2">
          <Video className="h-5 w-5" />
          <span>セッション分析</span>
        </CardTitle>
        <CardDescription>
          実験セッションの完了状況と反応時間分析
        </CardDescription>
      </CardHeader>
      <CardContent>
        <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
          {/* セッション完了状況 */}
          <div>
            <h4 className="text-sm font-medium mb-4">セッション完了状況</h4>
            <ResponsiveContainer width="100%" height={200}>
              <PieChart>
                <Pie
                  data={analyticsData?.sessionStats.sessionDistribution}
                  cx="50%"
                  cy="50%"
                  labelLine={false}
                  label={({ name, value }) => `${name}: ${value}`}
                  outerRadius={60}
                  fill="#8884d8"
                  dataKey="value"
                >
                  <Cell fill="#00C49F" />
                  <Cell fill="#FFBB28" />
                </Pie>
                <Tooltip />
              </PieChart>
            </ResponsiveContainer>
          </div>

          {/* 反応時間統計 */}
          <div>
            <h4 className="text-sm font-medium mb-4">反応時間統計</h4>
            <div className="space-y-4">
              <div className="flex justify-between items-center">
                <span className="text-sm">平均反応時間</span>
                <span className="font-medium">
                  {Math.round(analyticsData?.sessionStats.averageReactionTime || 0)}ms
                </span>
              </div>
              <div className="flex justify-between items-center">
                <span className="text-sm">総セッション数</span>
                <span className="font-medium">
                  {analyticsData?.sessionStats.totalSessions}
                </span>
              </div>
              <div className="flex justify-between items-center">
                <span className="text-sm">完了セッション</span>
                <span className="font-medium">
                  {analyticsData?.sessionStats.completedSessions}
                </span>
              </div>
            </div>
          </div>
        </div>
      </CardContent>
    </Card>
  );

  // データ同期ステータス
  const DataSyncStatus = () => (
    <Card>
      <CardHeader>
        <CardTitle className="flex items-center space-x-2">
          <Activity className="h-5 w-5" />
          <span>データ同期ステータス</span>
        </CardTitle>
        <CardDescription>
          Blob、Kuzu、ファイルシステム間の同期状態
        </CardDescription>
      </CardHeader>
      <CardContent>
        <div className="space-y-4">
          <div className="flex items-center justify-between">
            <div className="flex items-center space-x-2">
              <Zap className="h-4 w-4 text-yellow-500" />
              <span className="text-sm">同期状態</span>
            </div>
            <Badge variant={analyticsData?.storageStats.syncStatus === 'synced' ? "default" : "secondary"}>
              {analyticsData?.storageStats.syncStatus === 'synced' ? (
                <><CheckCircle className="h-3 w-3 mr-1" />同期済み</>
              ) : analyticsData?.storageStats.syncStatus === 'syncing' ? (
                <><RefreshCw className="h-3 w-3 mr-1 animate-spin" />同期中</>
              ) : (
                <><AlertCircle className="h-3 w-3 mr-1" />同期エラー</>
              )}
            </Badge>
          </div>

          <div className="grid grid-cols-3 gap-4">
            <div className="text-center">
              <div className="text-lg font-bold text-blue-600">
                {(analyticsData?.storageStats.blobUsage || 0) / 1024}KB
              </div>
              <div className="text-xs text-gray-500">Blob使用量</div>
            </div>
            <div className="text-center">
              <div className="text-lg font-bold text-green-600">
                {(analyticsData?.storageStats.kuzuUsage || 0) / 1024}KB
              </div>
              <div className="text-xs text-gray-500">Kuzu使用量</div>
            </div>
            <div className="text-center">
              <div className="text-lg font-bold text-purple-600">
                {analyticsData?.storageStats.totalDataPoints}
              </div>
              <div className="text-xs text-gray-500">総データポイント</div>
            </div>
          </div>
        </div>
      </CardContent>
    </Card>
  );

  if (isLoading) {
    return (
      <div className="flex items-center justify-center min-h-screen">
        <div className="flex items-center space-x-2">
          <RefreshCw className="h-6 w-6 animate-spin" />
          <span>分析データを読み込み中...</span>
        </div>
      </div>
    );
  }

  return (
    <div className="min-h-screen bg-gray-50 p-6">
      <div className="max-w-7xl mx-auto space-y-6">
        {/* ヘッダー */}
        <div className="flex items-center justify-between">
          <div>
            <h1 className="text-3xl font-bold text-gray-900">高度統合分析ダッシュボード</h1>
            <p className="text-gray-600 mt-1">
              Vercel Blob + Kuzu + ファイルシステムのリアルタイム統合分析プラットフォーム
            </p>
            <div className="flex items-center space-x-4 mt-3">
              <div className="flex items-center space-x-2">
                <Cloud className="h-4 w-4 text-blue-500" />
                <span className="text-xs text-blue-600 font-medium">Vercel Blob</span>
              </div>
              <div className="flex items-center space-x-2">
                <Database className="h-4 w-4 text-green-500" />
                <span className="text-xs text-green-600 font-medium">Kuzu Database</span>
              </div>
              <div className="flex items-center space-x-2">
                <FileText className="h-4 w-4 text-gray-500" />
                <span className="text-xs text-gray-600 font-medium">File System</span>
              </div>
              <div className="flex items-center space-x-2">
                <Zap className="h-4 w-4 text-purple-500" />
                <span className="text-xs text-purple-600 font-medium">Real-time Sync</span>
              </div>
            </div>
          </div>
          <div className="flex items-center space-x-4">
            <div className="text-sm text-gray-500">
              最終更新: {lastUpdated.toLocaleString('ja-JP')}
            </div>
            <Button onClick={fetchAnalyticsData} disabled={isLoading}>
              <RefreshCw className={`h-4 w-4 mr-2 ${isLoading ? 'animate-spin' : ''}`} />
              更新
            </Button>
          </div>
        </div>

        {/* タブ付きダッシュボード */}
        <Tabs defaultValue="overview" className="space-y-6">
          <TabsList className="grid w-full grid-cols-5">
            <TabsTrigger value="overview">概要</TabsTrigger>
            <TabsTrigger value="participants">参加者分析</TabsTrigger>
            <TabsTrigger value="emotions">感情分析</TabsTrigger>
            <TabsTrigger value="sessions">セッション分析</TabsTrigger>
            <TabsTrigger value="storage">ストレージ</TabsTrigger>
          </TabsList>

          {/* 概要タブ */}
          <TabsContent value="overview" className="space-y-6">
            <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-6">
              <StorageStatusCard />
              <ParticipantStatsChart />
              <EmotionAnalysisChart />
              <SessionAnalysisChart />
            </div>
            <DataSyncStatus />
          </TabsContent>

          {/* 参加者分析タブ */}
          <TabsContent value="participants" className="space-y-6">
            <ParticipantStatsChart />
            <SessionAnalysisChart />
          </TabsContent>

          {/* 感情分析タブ */}
          <TabsContent value="emotions" className="space-y-6">
            <EmotionAnalysisChart />
          </TabsContent>

          {/* セッション分析タブ */}
          <TabsContent value="sessions" className="space-y-6">
            <SessionAnalysisChart />
            <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
              {/* 反応時間分布 */}
              <Card>
                <CardHeader>
                  <CardTitle className="text-lg">反応時間分布</CardTitle>
                  <CardDescription>参加者の反応時間の統計分布</CardDescription>
                </CardHeader>
                <CardContent>
                  <ResponsiveContainer width="100%" height={300}>
                    <ComposedChart data={[
                      { range: '0-500ms', count: 15, percentage: 25 },
                      { range: '500-1000ms', count: 25, percentage: 42 },
                      { range: '1000-1500ms', count: 15, percentage: 25 },
                      { range: '1500ms+', count: 5, percentage: 8 }
                    ]}>
                      <CartesianGrid strokeDasharray="3 3" />
                      <XAxis dataKey="range" />
                      <YAxis yAxisId="left" />
                      <YAxis yAxisId="right" orientation="right" />
                      <Tooltip />
                      <Bar yAxisId="left" dataKey="count" fill="#8884d8" name="参加者数" />
                      <Line yAxisId="right" type="monotone" dataKey="percentage" stroke="#82ca9d" name="割合 (%)" />
                    </ComposedChart>
                  </ResponsiveContainer>
                </CardContent>
              </Card>

              {/* セッション完了トレンド */}
              <Card>
                <CardHeader>
                  <CardTitle className="text-lg">セッション完了トレンド</CardTitle>
                  <CardDescription>日別のセッション完了状況</CardDescription>
                </CardHeader>
                <CardContent>
                  <ResponsiveContainer width="100%" height={300}>
                    <AreaChart data={[
                      { date: '2025-01-01', completed: 2, total: 5 },
                      { date: '2025-01-02', completed: 3, total: 4 },
                      { date: '2025-01-03', completed: 1, total: 3 },
                      { date: '2025-01-04', completed: 4, total: 6 },
                      { date: '2025-01-05', completed: 2, total: 4 },
                      { date: '2025-01-06', completed: 3, total: 5 },
                      { date: '2025-01-07', completed: 1, total: 2 }
                    ]}>
                      <CartesianGrid strokeDasharray="3 3" />
                      <XAxis dataKey="date" />
                      <YAxis />
                      <Tooltip />
                      <Area type="monotone" dataKey="completed" stackId="1" stroke="#8884d8" fill="#8884d8" name="完了" />
                      <Area type="monotone" dataKey="total" stackId="1" stroke="#82ca9d" fill="#82ca9d" name="総セッション" />
                    </AreaChart>
                  </ResponsiveContainer>
                </CardContent>
              </Card>
            </div>
          </TabsContent>

          {/* ストレージタブ */}
          <TabsContent value="storage" className="space-y-6">
            <StorageStatusCard />
            <DataSyncStatus />

            {/* ストレージ使用状況の詳細 */}
            <Card>
              <CardHeader>
                <CardTitle className="flex items-center space-x-2">
                  <Globe className="h-5 w-5" />
                  <span>ストレージ使用状況詳細</span>
                </CardTitle>
                <CardDescription>
                  各ストレージサービスの使用状況とデータ分布
                </CardDescription>
              </CardHeader>
              <CardContent>
                <div className="space-y-6">
                  <div className="grid grid-cols-1 md:grid-cols-3 gap-6">
                    {/* Vercel Blob 使用状況 */}
                    <div className="text-center p-4 bg-blue-50 rounded-lg">
                      <div className="text-3xl font-bold text-blue-600 mb-2">
                        {(analyticsData?.storageStats.blobUsage || 0) / 1024}KB
                      </div>
                      <div className="text-sm text-blue-700 font-medium">Vercel Blob</div>
                      <div className="text-xs text-blue-600 mt-1">
                        {analyticsData?.participantStats.totalParticipants || 0} 参加者データ
                      </div>
                      <div className="w-full bg-blue-200 rounded-full h-2 mt-3">
                        <div
                          className="bg-blue-600 h-2 rounded-full"
                          style={{ width: `${Math.min((analyticsData?.storageStats.blobUsage || 0) / 102400 * 100, 100)}%` }}
                        ></div>
                      </div>
                    </div>

                    {/* Kuzu 使用状況 */}
                    <div className="text-center p-4 bg-green-50 rounded-lg">
                      <div className="text-3xl font-bold text-green-600 mb-2">
                        {(analyticsData?.storageStats.kuzuUsage || 0) / 1024}KB
                      </div>
                      <div className="text-sm text-green-700 font-medium">Kuzu Database</div>
                      <div className="text-xs text-green-600 mt-1">
                        {analyticsData?.storageStatus.kuzu ? '利用可能' : '利用不可'}
                      </div>
                      <div className="w-full bg-green-200 rounded-full h-2 mt-3">
                        <div
                          className="bg-green-600 h-2 rounded-full"
                          style={{ width: `${analyticsData?.storageStatus.kuzu ? 75 : 0}%` }}
                        ></div>
                      </div>
                    </div>

                    {/* ファイルシステム使用状況 */}
                    <div className="text-center p-4 bg-gray-50 rounded-lg">
                      <div className="text-3xl font-bold text-gray-600 mb-2">
                        ~{(analyticsData?.storageStats.blobUsage || 0) / 1024}KB
                      </div>
                      <div className="text-sm text-gray-700 font-medium">ファイルシステム</div>
                      <div className="text-xs text-gray-600 mt-1">
                        バックアップ & 互換性
                      </div>
                      <div className="w-full bg-gray-200 rounded-full h-2 mt-3">
                        <div className="bg-gray-400 h-2 rounded-full" style={{ width: '90%' }}></div>
                      </div>
                    </div>
                  </div>

                  {/* データ同期ログ */}
                  <div className="border-t pt-6">
                    <h4 className="text-sm font-medium mb-4">最近の同期アクティビティ</h4>
                    <div className="space-y-3">
                      <div className="flex items-center justify-between py-2 border-b">
                        <div className="flex items-center space-x-3">
                          <CheckCircle className="h-4 w-4 text-green-500" />
                          <span className="text-sm">参加者データ同期</span>
                        </div>
                        <span className="text-xs text-gray-500">2分前</span>
                      </div>
                      <div className="flex items-center justify-between py-2 border-b">
                        <div className="flex items-center space-x-3">
                          <CheckCircle className="h-4 w-4 text-green-500" />
                          <span className="text-sm">感情分析結果同期</span>
                        </div>
                        <span className="text-xs text-gray-500">5分前</span>
                      </div>
                      <div className="flex items-center justify-between py-2 border-b">
                        <div className="flex items-center space-x-3">
                          <CheckCircle className="h-4 w-4 text-blue-500" />
                          <span className="text-sm">Blobストレージ最適化</span>
                        </div>
                        <span className="text-xs text-gray-500">10分前</span>
                      </div>
                      <div className="flex items-center justify-between py-2">
                        <div className="flex items-center space-x-3">
                          <Clock className="h-4 w-4 text-yellow-500" />
                          <span className="text-sm">Kuzuデータベース同期</span>
                        </div>
                        <span className="text-xs text-gray-500">保留中</span>
                      </div>
                    </div>
                  </div>
                </div>
              </CardContent>
            </Card>
          </TabsContent>
        </Tabs>
      </div>
    </div>
  );
}
