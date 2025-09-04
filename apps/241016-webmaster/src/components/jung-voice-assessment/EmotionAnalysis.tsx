"use client";

import { useState, useEffect } from 'react';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '../ui/card';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '../ui/tabs';
import {
  LineChart,
  Line,
  XAxis,
  YAxis,
  CartesianGrid,
  Tooltip,
  Legend,
  ResponsiveContainer,
} from 'recharts';
import { Button } from '../ui/button';

interface EmotionDataPoint {
  stimulus: string;
  response: string;
  reactionTimeMs: number;
  timestamp: string;
  emotions: {
    name: string;
    score: number;
  }[];
}

interface EmotionAnalysisProps {
  userId: string;
  assessmentId: string;
  className?: string;
}

export default function EmotionAnalysis({
  userId,
  assessmentId,
  className = '',
}: EmotionAnalysisProps) {
  const [facialData, setFacialData] = useState<EmotionDataPoint[]>([]);
  const [voiceData, setVoiceData] = useState<EmotionDataPoint[]>([]);
  const [isLoading, setIsLoading] = useState<boolean>(true);
  const [error, setError] = useState<string | null>(null);
  const [activeTab, setActiveTab] = useState<string>('facial');
  const [selectedEmotion, setSelectedEmotion] = useState<string>('Joy');
  const [emotionOptions, setEmotionOptions] = useState<string[]>([]);

  useEffect(() => {
    const fetchEmotionData = async () => {
      setIsLoading(true);
      setError(null);

      try {
        // IndexedDBからデータを取得
        const dbName = 'jungVoiceAssessment';
        const dbVersion = 1;
        const request = indexedDB.open(dbName, dbVersion);

        request.onerror = (event) => {
          console.error('IndexedDB error:', event);
          setError('感情データの取得に失敗しました。');
          setIsLoading(false);
        };

        request.onsuccess = (event) => {
          const db = request.result;
          const transaction = db.transaction(['emotionData'], 'readonly');
          const store = transaction.objectStore('emotionData');
          
          // assessmentIdまたはuserIdでデータを検索
          const query = assessmentId 
            ? store.index('assessmentId').getAll(assessmentId)
            : store.index('userId').getAll(userId);

          query.onsuccess = (event) => {
            const records = query.result;
            if (records && records.length > 0) {
              const processedData = processEmotionData(records);
              setFacialData(processedData.facialData);
              setVoiceData(processedData.voiceData);
              
              // 感情オプションを設定
              if (processedData.facialData.length > 0 && processedData.facialData[0].emotions.length > 0) {
                const emotionNames = processedData.facialData[0].emotions.map(e => e.name);
                setEmotionOptions(emotionNames);
                setSelectedEmotion(emotionNames[0]);
              }
            } else {
              // データが見つからない場合
              console.log('No emotion data found in IndexedDB');
            }
            setIsLoading(false);
          };

          query.onerror = (event) => {
            console.error('Error fetching data from IndexedDB:', event);
            setError('感情データの取得に失敗しました。');
            setIsLoading(false);
          };
        };

        request.onupgradeneeded = (event) => {
          // データベースが存在しない場合は作成
          const db = request.result;
          if (!db.objectStoreNames.contains('emotionData')) {
            const store = db.createObjectStore('emotionData', { keyPath: 'id', autoIncrement: true });
            store.createIndex('userId', 'userId', { unique: false });
            store.createIndex('assessmentId', 'assessmentId', { unique: false });
          }
        };
      } catch (err) {
        console.error('Error fetching emotion data:', err);
        setError('感情データの取得に失敗しました。');
        setIsLoading(false);
      }
    };

    fetchEmotionData();
  }, [userId, assessmentId]);

  // データを処理する関数
  const processEmotionData = (records: any[]) => {
    const facialData: EmotionDataPoint[] = [];
    const voiceData: EmotionDataPoint[] = [];

    records.forEach(record => {
      if (record.facialEmotions && record.facialEmotions.length > 0) {
        facialData.push({
          stimulus: record.stimulusWord || '',
          response: record.responseWord || '',
          reactionTimeMs: record.reactionTimeMs || 0,
          timestamp: new Date(record.recordedAt).toLocaleTimeString(),
          emotions: record.facialEmotions.map((e: any) => ({
            name: e.emotionName,
            score: e.score,
          })),
        });
      }

      if (record.voiceEmotions && record.voiceEmotions.length > 0) {
        voiceData.push({
          stimulus: record.stimulusWord || '',
          response: record.responseWord || '',
          reactionTimeMs: record.reactionTimeMs || 0,
          timestamp: new Date(record.recordedAt).toLocaleTimeString(),
          emotions: record.voiceEmotions.map((e: any) => ({
            name: e.emotionName,
            score: e.score,
          })),
        });
      }
    });

    return { facialData, voiceData };
  };

  // Chart用のデータを準備
  const prepareChartData = (data: EmotionDataPoint[], emotionName: string) => {
    return data.map((point, index) => {
      const emotion = point.emotions.find(e => e.name === emotionName);
      return {
        name: `${index + 1}: ${point.stimulus}`,
        score: emotion ? emotion.score : 0,
        reactionTime: point.reactionTimeMs,
      };
    });
  };

  // 反応時間と感情スコアの相関を計算
  const calculateCorrelation = (data: EmotionDataPoint[], emotionName: string) => {
    if (data.length < 3) return '十分なデータがありません';

    const pairs = data.map(point => {
      const emotion = point.emotions.find(e => e.name === emotionName);
      return [point.reactionTimeMs, emotion ? emotion.score : 0];
    });

    const n = pairs.length;
    const sumX = pairs.reduce((sum, pair) => sum + pair[0], 0);
    const sumY = pairs.reduce((sum, pair) => sum + pair[1], 0);
    const sumXY = pairs.reduce((sum, pair) => sum + pair[0] * pair[1], 0);
    const sumX2 = pairs.reduce((sum, pair) => sum + pair[0] * pair[0], 0);
    const sumY2 = pairs.reduce((sum, pair) => sum + pair[1] * pair[1], 0);

    const numerator = n * sumXY - sumX * sumY;
    const denominator = Math.sqrt((n * sumX2 - sumX * sumX) * (n * sumY2 - sumY * sumY));

    if (denominator === 0) return '計算できません';

    const correlation = numerator / denominator;
    return correlation.toFixed(4);
  };

  // 最も強い感情を見つける
  const findStrongestEmotions = (data: EmotionDataPoint[]) => {
    if (data.length === 0) return [];

    // 各感情のスコア平均を計算
    const emotionScores: Record<string, { total: number; count: number }> = {};

    data.forEach(point => {
      point.emotions.forEach(emotion => {
        if (!emotionScores[emotion.name]) {
          emotionScores[emotion.name] = { total: 0, count: 0 };
        }
        emotionScores[emotion.name].total += emotion.score;
        emotionScores[emotion.name].count += 1;
      });
    });

    // 平均を計算してソート
    const averages = Object.entries(emotionScores).map(([name, { total, count }]) => ({
      name,
      average: total / count,
    }));

    return averages.sort((a, b) => b.average - a.average).slice(0, 5);
  };

  const activeData = activeTab === 'facial' ? facialData : voiceData;
  const chartData = prepareChartData(activeData, selectedEmotion);
  const correlation = calculateCorrelation(activeData, selectedEmotion);
  const strongestEmotions = findStrongestEmotions(activeData);

  return (
    <div className={`p-4 ${className}`}>
      <Card>
        <CardHeader>
          <CardTitle>感情分析結果</CardTitle>
          <CardDescription>
            ユングの言語連想テスト中に記録された感情の分析
          </CardDescription>
        </CardHeader>
        <CardContent>
          {isLoading ? (
            <div className="flex justify-center items-center h-40">
              <div className="animate-spin rounded-full h-8 w-8 border-b-2 border-gray-900"></div>
            </div>
          ) : error ? (
            <div className="text-red-500 p-4 text-center">
              <p>{error}</p>
              <Button
                onClick={() => window.location.reload()}
                className="mt-4"
                variant="outline"
              >
                再試行
              </Button>
            </div>
          ) : (
            <Tabs defaultValue="facial" onValueChange={setActiveTab}>
              <TabsList className="mb-4">
                <TabsTrigger value="facial">顔の感情分析</TabsTrigger>
                <TabsTrigger value="voice">声の感情分析</TabsTrigger>
              </TabsList>

              <TabsContent value="facial">
                {facialData.length === 0 ? (
                  <p className="text-center text-gray-500 py-4">
                    顔の感情データがありません
                  </p>
                ) : (
                  <div>
                    <div className="mb-4">
                      <label htmlFor="facial-emotion-select" className="block text-sm font-medium mb-2">感情を選択:</label>
                      <select
                        id="facial-emotion-select"
                        value={selectedEmotion}
                        onChange={(e) => setSelectedEmotion(e.target.value)}
                        className="w-full p-2 border rounded"
                        aria-label="顔の感情を選択"
                      >
                        {emotionOptions.map((emotion) => (
                          <option key={emotion} value={emotion}>
                            {emotion}
                          </option>
                        ))}
                      </select>
                    </div>

                    <div className="mb-8">
                      <h3 className="text-lg font-semibold mb-2">感情スコアの推移</h3>
                      <div className="h-64">
                        <ResponsiveContainer width="100%" height="100%">
                          <LineChart data={chartData}>
                            <CartesianGrid strokeDasharray="3 3" />
                            <XAxis dataKey="name" />
                            <YAxis domain={[0, 1]} />
                            <Tooltip />
                            <Legend />
                            <Line
                              type="monotone"
                              dataKey="score"
                              stroke="#8884d8"
                              name={`${selectedEmotion}スコア`}
                            />
                            <Line
                              type="monotone"
                              dataKey="reactionTime"
                              stroke="#82ca9d"
                              name="反応時間 (ms)"
                              yAxisId={1}
                            />
                          </LineChart>
                        </ResponsiveContainer>
                      </div>
                    </div>

                    <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                      <div>
                        <h3 className="text-lg font-semibold mb-2">最も強い感情</h3>
                        <ul className="space-y-2">
                          {strongestEmotions.map((emotion) => (
                            <li key={emotion.name} className="flex justify-between">
                              <span>{emotion.name}</span>
                              <span>{(emotion.average * 100).toFixed(2)}%</span>
                            </li>
                          ))}
                        </ul>
                      </div>
                      <div>
                        <h3 className="text-lg font-semibold mb-2">反応時間と感情の相関</h3>
                        <p>
                          {selectedEmotion}と反応時間の相関係数: <strong>{correlation}</strong>
                        </p>
                        <p className="text-sm text-gray-500 mt-2">
                          1に近いほど強い正の相関、-1に近いほど強い負の相関を示します。
                        </p>
                      </div>
                    </div>
                  </div>
                )}
              </TabsContent>

              <TabsContent value="voice">
                {voiceData.length === 0 ? (
                  <p className="text-center text-gray-500 py-4">
                    音声の感情データがありません
                  </p>
                ) : (
                  <div>
                    <div className="mb-4">
                      <label htmlFor="voice-emotion-select" className="block text-sm font-medium mb-2">感情を選択:</label>
                      <select
                        id="voice-emotion-select"
                        value={selectedEmotion}
                        onChange={(e) => setSelectedEmotion(e.target.value)}
                        className="w-full p-2 border rounded"
                        aria-label="声の感情を選択"
                      >
                        {emotionOptions.map((emotion) => (
                          <option key={emotion} value={emotion}>
                            {emotion}
                          </option>
                        ))}
                      </select>
                    </div>

                    <div className="mb-8">
                      <h3 className="text-lg font-semibold mb-2">感情スコアの推移</h3>
                      <div className="h-64">
                        <ResponsiveContainer width="100%" height="100%">
                          <LineChart data={chartData}>
                            <CartesianGrid strokeDasharray="3 3" />
                            <XAxis dataKey="name" />
                            <YAxis domain={[0, 1]} />
                            <Tooltip />
                            <Legend />
                            <Line
                              type="monotone"
                              dataKey="score"
                              stroke="#8884d8"
                              name={`${selectedEmotion}スコア`}
                            />
                            <Line
                              type="monotone"
                              dataKey="reactionTime"
                              stroke="#82ca9d"
                              name="反応時間 (ms)"
                              yAxisId={1}
                            />
                          </LineChart>
                        </ResponsiveContainer>
                      </div>
                    </div>

                    <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                      <div>
                        <h3 className="text-lg font-semibold mb-2">最も強い感情</h3>
                        <ul className="space-y-2">
                          {strongestEmotions.map((emotion) => (
                            <li key={emotion.name} className="flex justify-between">
                              <span>{emotion.name}</span>
                              <span>{(emotion.average * 100).toFixed(2)}%</span>
                            </li>
                          ))}
                        </ul>
                      </div>
                      <div>
                        <h3 className="text-lg font-semibold mb-2">反応時間と感情の相関</h3>
                        <p>
                          {selectedEmotion}と反応時間の相関係数: <strong>{correlation}</strong>
                        </p>
                        <p className="text-sm text-gray-500 mt-2">
                          1に近いほど強い正の相関、-1に近いほど強い負の相関を示します。
                        </p>
                      </div>
                    </div>
                  </div>
                )}
              </TabsContent>
            </Tabs>
          )}
        </CardContent>
      </Card>
    </div>
  );
} 