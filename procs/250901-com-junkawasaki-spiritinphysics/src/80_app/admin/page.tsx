'use client';

import React, { useState, useEffect } from 'react';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { Card, CardContent } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import {
  Users,
  BarChart3,
  Clock,
  TrendingUp,
  Brain,
  Download,
  RefreshCw,
  Activity,
  ExternalLink,
  Cloud,
  Database,
  FileText
} from 'lucide-react';
import Link from 'next/link';
import { ParticipantOverview } from './components/ParticipantOverview';
import { ExperimentAnalytics } from './components/ExperimentAnalytics';
import { DataVisualization } from './components/DataVisualization';
import { SessionTimeline } from './components/SessionTimeline';
import { ExportTools } from './components/ExportTools';

interface AnalyticsData {
  totalParticipants: number;
  completedSessions: number;
  completionRate: number;
  averageSessionDuration: number;
  averageReactionTime: number;
  emotionDistribution: Record<string, number>;
  totalSessions: number;
}

export default function AdminPage() {
  const [analyticsData, setAnalyticsData] = useState<AnalyticsData | null>(null);
  const [isLoading, setIsLoading] = useState(true);
  const [activeTab, setActiveTab] = useState('overview');

  const fetchAnalytics = async () => {
    try {
      const response = await fetch('/api/admin/experimental-data?type=analytics');
      const result = await response.json();
      if (result.success) {
        setAnalyticsData(result.data);
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

  const handleRefresh = () => {
    setIsLoading(true);
    fetchAnalytics();
  };

  if (isLoading) {
    return (
      <main className="flex min-h-screen flex-col items-center justify-center p-24">
        <div className="flex items-center space-x-2">
          <RefreshCw className="h-6 w-6 animate-spin" />
          <span>データを読み込み中...</span>
        </div>
      </main>
    );
  }

  return (
    <main className="min-h-screen bg-gray-50">
      {/* Header */}
      <div className="bg-white border-b border-gray-200 px-6 py-4">
        <div className="flex items-center justify-between">
          <div>
            <h1 className="text-2xl font-bold text-gray-900">
              クラウド統合実験管理ダッシュボード
            </h1>
            <p className="text-sm text-gray-600 mt-1">
              Vercel Blob + Kuzu + ファイルシステム統合のSpirit-in-Physics実験データ管理
            </p>
          </div>
          <div className="flex items-center space-x-4">
            <Link href="/admin/analytics">
              <Button
                variant="default"
                size="sm"
                className="flex items-center space-x-2 bg-gradient-to-r from-blue-600 to-purple-600 hover:from-blue-700 hover:to-purple-700"
              >
                <BarChart3 className="h-4 w-4" />
                <span>統合分析</span>
                <ExternalLink className="h-3 w-3" />
              </Button>
            </Link>
            <Button
              variant="outline"
              size="sm"
              onClick={handleRefresh}
              className="flex items-center space-x-2"
            >
              <RefreshCw className="h-4 w-4" />
              <span>更新</span>
            </Button>
            <div className="text-sm text-gray-500">
              最終更新: {new Date().toLocaleString('ja-JP')}
            </div>
          </div>
        </div>

        {/* Data Source Info */}
        <div className="bg-gradient-to-r from-blue-50 to-green-50 border border-blue-200 px-4 py-3">
          <div className="flex items-center justify-between">
            <div className="flex items-center space-x-4">
              <div className="flex items-center space-x-2">
                <span className="text-sm font-medium text-blue-800">🔗 統合データソース</span>
              </div>
              <div className="flex items-center space-x-4">
                <div className="flex items-center space-x-1">
                  <Cloud className="h-3 w-3 text-blue-600" />
                  <span className="text-xs text-blue-700">Vercel Blob</span>
                </div>
                <div className="flex items-center space-x-1">
                  <Database className="h-3 w-3 text-green-600" />
                  <span className="text-xs text-green-700">Kuzu DB</span>
                </div>
                <div className="flex items-center space-x-1">
                  <FileText className="h-3 w-3 text-gray-600" />
                  <span className="text-xs text-gray-700">File System</span>
                </div>
              </div>
            </div>
            <div className="flex items-center space-x-2">
              <span className="text-xs text-blue-700 bg-blue-100 px-2 py-1 rounded">
                リアルタイム同期
              </span>
            </div>
          </div>
        </div>
      </div>

      {/* Quick Stats */}
      {analyticsData && (
        <div className="bg-white px-6 py-4 border-b border-gray-200">
          <div className="grid grid-cols-1 md:grid-cols-4 gap-4">
            <Card className="border-0 shadow-none">
              <CardContent className="p-4">
                <div className="flex items-center space-x-3">
                  <div className="p-2 bg-blue-100 rounded-lg">
                    <Users className="h-5 w-5 text-blue-600" />
                  </div>
                  <div>
                    <p className="text-sm font-medium text-gray-600">参加者数</p>
                    <p className="text-2xl font-bold text-gray-900">
                      {analyticsData.totalParticipants}
                    </p>
                  </div>
                </div>
              </CardContent>
            </Card>

            <Card className="border-0 shadow-none">
              <CardContent className="p-4">
                <div className="flex items-center space-x-3">
                  <div className="p-2 bg-green-100 rounded-lg">
                    <Activity className="h-5 w-5 text-green-600" />
                  </div>
                  <div>
                    <p className="text-sm font-medium text-gray-600">完了率</p>
                    <p className="text-2xl font-bold text-gray-900">
                      {analyticsData.completionRate.toFixed(1)}%
                    </p>
                  </div>
                </div>
              </CardContent>
            </Card>

            <Card className="border-0 shadow-none">
              <CardContent className="p-4">
                <div className="flex items-center space-x-3">
                  <div className="p-2 bg-purple-100 rounded-lg">
                    <Clock className="h-5 w-5 text-purple-600" />
                  </div>
                  <div>
                    <p className="text-sm font-medium text-gray-600">平均反応時間</p>
                    <p className="text-2xl font-bold text-gray-900">
                      {analyticsData.averageReactionTime.toFixed(0)}ms
                    </p>
                  </div>
                </div>
              </CardContent>
            </Card>

            <Card className="border-0 shadow-none">
              <CardContent className="p-4">
                <div className="flex items-center space-x-3">
                  <div className="p-2 bg-orange-100 rounded-lg">
                    <Brain className="h-5 w-5 text-orange-600" />
                  </div>
                  <div>
                    <p className="text-sm font-medium text-gray-600">感情データ数</p>
                    <p className="text-2xl font-bold text-gray-900">
                      {Object.keys(analyticsData.emotionDistribution).length}
                    </p>
                  </div>
                </div>
              </CardContent>
            </Card>
          </div>
        </div>
      )}

      {/* Main Content */}
      <div className="px-6 py-6">
        <Tabs value={activeTab} onValueChange={setActiveTab} className="w-full">
          <TabsList className="grid w-full grid-cols-6">
            <TabsTrigger value="overview" className="flex items-center space-x-2">
              <Users className="h-4 w-4" />
              <span>参加者一覧</span>
            </TabsTrigger>
            <TabsTrigger value="analytics" className="flex items-center space-x-2">
              <BarChart3 className="h-4 w-4" />
              <span>分析</span>
            </TabsTrigger>
            <TabsTrigger value="visualization" className="flex items-center space-x-2">
              <TrendingUp className="h-4 w-4" />
              <span>可視化</span>
            </TabsTrigger>
            <TabsTrigger value="timeline" className="flex items-center space-x-2">
              <Clock className="h-4 w-4" />
              <span>タイムライン</span>
            </TabsTrigger>
            <TabsTrigger value="import" className="flex items-center space-x-2">
              <Database className="h-4 w-4" />
              <span>インポート</span>
            </TabsTrigger>
            <TabsTrigger value="export" className="flex items-center space-x-2">
              <Download className="h-4 w-4" />
              <span>エクスポート</span>
            </TabsTrigger>
          </TabsList>

          <TabsContent value="overview" className="mt-6">
            <ParticipantOverview />
          </TabsContent>

          <TabsContent value="analytics" className="mt-6">
            <ExperimentAnalytics />
          </TabsContent>

          <TabsContent value="visualization" className="mt-6">
            <DataVisualization />
          </TabsContent>

          <TabsContent value="timeline" className="mt-6">
            <SessionTimeline />
          </TabsContent>

          <TabsContent value="import" className="mt-6">
            <div className="text-center py-12">
              <Database className="h-12 w-12 mx-auto text-gray-400 mb-4" />
              <h3 className="text-lg font-semibold text-gray-900 mb-2">
                データインポート
              </h3>
              <p className="text-gray-600 mb-6">
                ファイルシステムからデータを手動でインポートします
              </p>
              <Link href="/admin/import">
                <Button className="bg-blue-600 hover:bg-blue-700">
                  <Database className="h-4 w-4 mr-2" />
                  インポートページへ
                </Button>
              </Link>
            </div>
          </TabsContent>

          <TabsContent value="export" className="mt-6">
            <ExportTools />
          </TabsContent>
        </Tabs>
      </div>
    </main>
  );
}