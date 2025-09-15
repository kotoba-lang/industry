'use client';

import React, { useState, useEffect } from 'react';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import {
  ScatterChart,
  Scatter,
  XAxis,
  YAxis,
  CartesianGrid,
  Tooltip,
  ResponsiveContainer,
  BarChart,
  Bar,
  LineChart,
  Line,
  ComposedChart,
  Area
} from 'recharts';
import {
  BarChart3,
  Activity,
  Clock,
  Brain,
  Download
} from 'lucide-react';

interface ReactionTimeData {
  participantId: string;
  sessionType: string;
  stimulusWord: string;
  responseWord: string;
  reactionTimeMs: number;
  isDelayed: boolean;
  timestamp: string;
}

interface SessionData {
  participantId: string;
  sessionId: string;
  sessionType: string;
  startTime: string;
  endTime: string;
  wordResponses: Array<{
    stimulusWord: { word: string; key: string };
    responseWord: string;
    reactionTimeMs: number;
    isDelayed: boolean;
  }>;
  averageReactionTime: number;
  emotionData: Array<{
    emotion: string;
    confidence: number;
    timestamp: string;
  }>;
}

export function DataVisualization() {
  const [reactionTimeData, setReactionTimeData] = useState<ReactionTimeData[]>([]);
  const [sessionData, setSessionData] = useState<SessionData[]>([]);
  const [selectedVisualization, setSelectedVisualization] = useState('scatter');
  const [selectedMetric, setSelectedMetric] = useState('reaction-time');
  const [isLoading, setIsLoading] = useState(true);

  const fetchData = async () => {
    try {
      const [reactionTimeResponse, sessionsResponse] = await Promise.all([
        fetch('/api/admin/experimental-data?type=reaction-times'),
        fetch('/api/admin/experimental-data?type=sessions')
      ]);

      const reactionTimeResult = await reactionTimeResponse.json();
      const sessionsResult = await sessionsResponse.json();

      if (reactionTimeResult.success) {
        setReactionTimeData(reactionTimeResult.data);
      }
      if (sessionsResult.success) {
        setSessionData(sessionsResult.data);
      }
    } catch (error) {
      console.error('Error fetching visualization data:', error);
    } finally {
      setIsLoading(false);
    }
  };

  useEffect(() => {
    fetchData();
  }, []);

  // Prepare data for different visualizations
  const scatterData = reactionTimeData.map((item, index) => ({
    x: index,
    y: item.reactionTimeMs,
    stimulusWord: item.stimulusWord,
    sessionType: item.sessionType,
    isDelayed: item.isDelayed
  }));

  const heatMapData = reactionTimeData.reduce((acc, item) => {
    const key = `${item.stimulusWord}-${item.sessionType}`;
    const existing = acc.find(d => d.key === key);
    if (existing) {
      existing.values.push(item.reactionTimeMs);
      existing.average = existing.values.reduce((a, b) => a + b, 0) / existing.values.length;
    } else {
      acc.push({
        key,
        stimulusWord: item.stimulusWord,
        sessionType: item.sessionType,
        values: [item.reactionTimeMs],
        average: item.reactionTimeMs
      });
    }
    return acc;
  }, [] as Array<{ key: string; stimulusWord: string; sessionType: string; values: number[]; average: number }>).map(item => ({
    stimulusWord: item.stimulusWord,
    sessionType: item.sessionType,
    average: Math.round(item.average),
    count: item.values.length
  }));

  const timeSeriesData = reactionTimeData
    .sort((a, b) => new Date(a.timestamp).getTime() - new Date(b.timestamp).getTime())
    .map((item, index) => ({
      time: index,
      reactionTime: item.reactionTimeMs,
      stimulusWord: item.stimulusWord,
      sessionType: item.sessionType
    }));


  const renderVisualization = () => {
    switch (selectedVisualization) {
      case 'scatter':
        return (
          <ResponsiveContainer width="100%" height={400}>
            <ScatterChart data={scatterData}>
              <CartesianGrid strokeDasharray="3 3" />
              <XAxis
                type="number"
                dataKey="x"
                name="応答順序"
                label={{ value: '応答順序', position: 'insideBottom', offset: -5 }}
              />
              <YAxis
                type="number"
                dataKey="y"
                name="反応時間 (ms)"
                label={{ value: '反応時間 (ms)', angle: -90, position: 'insideLeft' }}
              />
              <Tooltip
                formatter={(value: number) => [
                  `${value}ms`,
                  '反応時間'
                ]}
                labelFormatter={(label) => `応答 ${label}`}
              />
              <Scatter
                dataKey="y"
                fill="#8884d8"
                shape={(props: { cx: number; cy: number; payload: { isDelayed: boolean } }) => {
                  const { cx, cy, payload } = props;
                  return (
                    <circle
                      cx={cx}
                      cy={cy}
                      r={4}
                      fill={payload.isDelayed ? '#ff6b6b' : '#8884d8'}
                      stroke="#fff"
                      strokeWidth={1}
                    />
                  );
                }}
              />
            </ScatterChart>
          </ResponsiveContainer>
        );

      case 'heatmap':
        return (
          <ResponsiveContainer width="100%" height={400}>
            <BarChart data={heatMapData}>
              <CartesianGrid strokeDasharray="3 3" />
              <XAxis
                dataKey="stimulusWord"
                angle={-45}
                textAnchor="end"
                height={80}
                fontSize={12}
              />
              <YAxis />
              <Tooltip
                formatter={(value: number) => [`${value}ms`, '平均反応時間']}
                labelFormatter={(label) => `刺激語: ${label}`}
              />
              <Bar dataKey="average" fill="#8884d8" />
            </BarChart>
          </ResponsiveContainer>
        );

      case 'timeseries':
        return (
          <ResponsiveContainer width="100%" height={400}>
            <LineChart data={timeSeriesData}>
              <CartesianGrid strokeDasharray="3 3" />
              <XAxis
                dataKey="time"
                type="number"
                label={{ value: '時間経過', position: 'insideBottom', offset: -5 }}
              />
              <YAxis
                label={{ value: '反応時間 (ms)', angle: -90, position: 'insideLeft' }}
              />
              <Tooltip
                formatter={(value: number) => [`${value}ms`, '反応時間']}
                labelFormatter={(label) => `時間: ${label}`}
              />
              <Line
                type="monotone"
                dataKey="reactionTime"
                stroke="#8884d8"
                strokeWidth={2}
                dot={false}
              />
            </LineChart>
          </ResponsiveContainer>
        );

      case 'composed':
        return (
          <ResponsiveContainer width="100%" height={400}>
            <ComposedChart data={timeSeriesData}>
              <CartesianGrid strokeDasharray="3 3" />
              <XAxis
                dataKey="time"
                label={{ value: '時間経過', position: 'insideBottom', offset: -5 }}
              />
              <YAxis
                label={{ value: '反応時間 (ms)', angle: -90, position: 'insideLeft' }}
              />
              <Tooltip />
              <Area
                type="monotone"
                dataKey="reactionTime"
                fill="#8884d8"
                fillOpacity={0.3}
                stroke="#8884d8"
              />
              <Line
                type="monotone"
                dataKey="reactionTime"
                stroke="#ff7300"
                strokeWidth={2}
                dot={false}
              />
            </ComposedChart>
          </ResponsiveContainer>
        );

      default:
        return <div>視覚化タイプを選択してください</div>;
    }
  };

  if (isLoading) {
    return (
      <Card>
        <CardContent className="p-6">
          <div className="flex items-center justify-center">
            <div className="animate-spin rounded-full h-8 w-8 border-b-2 border-gray-900"></div>
            <span className="ml-2">視覚化データを読み込み中...</span>
          </div>
        </CardContent>
      </Card>
    );
  }

  return (
    <div className="space-y-6">
      {/* Controls */}
      <Card>
        <CardHeader>
          <CardTitle className="flex items-center space-x-2">
            <BarChart3 className="h-5 w-5" />
            <span>データ視覚化</span>
          </CardTitle>
          <CardDescription>
            実験データの様々な視覚化ビュー
          </CardDescription>
        </CardHeader>
        <CardContent>
          <div className="flex flex-col md:flex-row gap-4">
            <div className="flex-1">
              <label className="block text-sm font-medium mb-2">視覚化タイプ</label>
              <Select value={selectedVisualization} onValueChange={setSelectedVisualization}>
                <SelectTrigger>
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="scatter">散布図 - 反応時間の分布</SelectItem>
                  <SelectItem value="heatmap">ヒートマップ - 刺激語ごとの反応時間</SelectItem>
                  <SelectItem value="timeseries">時系列 - 時間経過による反応時間</SelectItem>
                  <SelectItem value="composed">複合チャート - 面積 + 線</SelectItem>
                </SelectContent>
              </Select>
            </div>

            <div className="flex-1">
              <label className="block text-sm font-medium mb-2">指標</label>
              <Select value={selectedMetric} onValueChange={setSelectedMetric}>
                <SelectTrigger>
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="reaction-time">反応時間</SelectItem>
                  <SelectItem value="emotion">感情データ</SelectItem>
                  <SelectItem value="accuracy">正確性</SelectItem>
                  <SelectItem value="response-pattern">応答パターン</SelectItem>
                </SelectContent>
              </Select>
            </div>

            <div className="flex items-end">
              <Button variant="outline" className="flex items-center space-x-2">
                <Download className="h-4 w-4" />
                <span>エクスポート</span>
              </Button>
            </div>
          </div>
        </CardContent>
      </Card>

      {/* Visualization */}
      <Card>
        <CardHeader>
          <CardTitle>
            {selectedVisualization === 'scatter' && '反応時間の散布図'}
            {selectedVisualization === 'heatmap' && '刺激語ごとの平均反応時間'}
            {selectedVisualization === 'timeseries' && '時系列反応時間推移'}
            {selectedVisualization === 'composed' && '複合反応時間分析'}
          </CardTitle>
          <CardDescription>
            {selectedVisualization === 'scatter' && '各応答の反応時間をプロット。赤い点は遅延応答を示します。'}
            {selectedVisualization === 'heatmap' && '各刺激語に対する平均反応時間を棒グラフで表示。'}
            {selectedVisualization === 'timeseries' && '実験中の時間経過による反応時間の変化。'}
            {selectedVisualization === 'composed' && '面積グラフと線グラフの組み合わせによる詳細分析。'}
          </CardDescription>
        </CardHeader>
        <CardContent>
          {renderVisualization()}
        </CardContent>
      </Card>

      {/* Summary Statistics */}
      <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
        <Card>
          <CardHeader className="flex flex-row items-center justify-between space-y-0 pb-2">
            <CardTitle className="text-sm font-medium">総データポイント</CardTitle>
            <Activity className="h-4 w-4 text-muted-foreground" />
          </CardHeader>
          <CardContent>
            <div className="text-2xl font-bold">{reactionTimeData.length}</div>
            <p className="text-xs text-muted-foreground">
              記録された反応時間データ
            </p>
          </CardContent>
        </Card>

        <Card>
          <CardHeader className="flex flex-row items-center justify-between space-y-0 pb-2">
            <CardTitle className="text-sm font-medium">平均反応時間</CardTitle>
            <Clock className="h-4 w-4 text-muted-foreground" />
          </CardHeader>
          <CardContent>
            <div className="text-2xl font-bold">
              {reactionTimeData.length > 0
                ? Math.round(reactionTimeData.reduce((acc, item) => acc + item.reactionTimeMs, 0) / reactionTimeData.length)
                : 0}ms
            </div>
            <p className="text-xs text-muted-foreground">
              全応答の平均
            </p>
          </CardContent>
        </Card>

        <Card>
          <CardHeader className="flex flex-row items-center justify-between space-y-0 pb-2">
            <CardTitle className="text-sm font-medium">遅延応答率</CardTitle>
            <Brain className="h-4 w-4 text-muted-foreground" />
          </CardHeader>
          <CardContent>
            <div className="text-2xl font-bold">
              {reactionTimeData.length > 0
                ? ((reactionTimeData.filter(item => item.isDelayed).length / reactionTimeData.length) * 100).toFixed(1)
                : 0}%
            </div>
            <p className="text-xs text-muted-foreground">
              遅延と判定された応答の割合
            </p>
          </CardContent>
        </Card>
      </div>
    </div>
  );
}
