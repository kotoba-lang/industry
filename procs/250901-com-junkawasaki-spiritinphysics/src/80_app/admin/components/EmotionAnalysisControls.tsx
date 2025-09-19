'use client';

import React, { useState } from 'react';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import {
  Brain,
  Play,
  Loader2,
  CheckCircle,
  AlertCircle,
  Video,
  Users,
  Workflow
} from 'lucide-react';
// ワークフローサービスはAPI経由で使用するため、直接インポートしない

interface EmotionAnalysisControlsProps {
  onAnalysisComplete?: () => void;
}

export function EmotionAnalysisControls({ onAnalysisComplete }: EmotionAnalysisControlsProps) {
  const [selectedParticipant, setSelectedParticipant] = useState<string>('');
  const [isAnalyzing, setIsAnalyzing] = useState(false);
  const [analysisStatus, setAnalysisStatus] = useState<'idle' | 'success' | 'error'>('idle');
  const [statusMessage, setStatusMessage] = useState('');
  const [useWorkflow, setUseWorkflow] = useState(true); // デフォルトでワークフロー使用

  // 参加者リスト（実際のデータから取得する）
  const participants = [
    "144b325f-5966-4d59-a629-f2ca421388cc",
    "15592cdb-86cf-4baf-86f5-66184169ee39",
    "25111604-c7db-4bfd-8662-e55060e332d6",
    "2a0d7a69-f953-4c29-87a5-8a8e4e8bd413",
    "4512513e-9132-4556-9858-bac08f28037f",
    "5346d514-e501-457a-aff1-55c92074a6f2",
    "5ac869a3-b8db-49c3-9362-3e149a5415e9",
    "7dda0261-a6f4-4208-bd61-4244380d277f",
    "a4e1b8f4-e267-41a7-acfc-07fe1b7c06fb",
    "a5d58eb8-a8c0-4b19-b4e3-67c391b530db",
    "ad96101f-a7a8-4d71-8d82-c0478975c40b",
    "e41a9cd2-d803-49a8-9020-0260e55cd03e"
  ];

  const handleSingleAnalysis = async () => {
    if (!selectedParticipant) {
      setAnalysisStatus('error');
      setStatusMessage('参加者を選択してください');
      return;
    }

    setIsAnalyzing(true);
    setAnalysisStatus('idle');
    setStatusMessage('');

    try {
      if (useWorkflow) {
        // ワークフローAPIを使用
        setStatusMessage('ワークフロー分析を開始します...');

        const response = await fetch('/api/admin/emotion-analysis', {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
          },
          body: JSON.stringify({
            action: 'analyze-batch-workflow',
            participantId: selectedParticipant,
            priority: 'normal',
          }),
        });

        const result = await response.json();

        if (!result.success) {
          throw new Error(result.error || 'ワークフロー開始に失敗しました');
        }

        setAnalysisStatus('success');
        setStatusMessage(`ワークフロー分析を開始しました (Batch ID: ${result.data?.batchId?.slice(0, 8)}...)`);

        // 定期的にステータスを確認
        setTimeout(() => {
          if (onAnalysisComplete) {
            onAnalysisComplete();
          }
        }, 2000);

      } else {
        // 従来の同期分析（後方互換性のため維持）
        const response = await fetch(`/api/admin/experimental-data?type=participant&participantId=${selectedParticipant}`);
        const result = await response.json();

        if (!result.success || !result.data.hasVideoFiles) {
          throw new Error('この参加者にはビデオファイルがありません');
        }

        const videoFiles = result.data.videoFiles;
        const analysisPromises = videoFiles.map(async (videoFile: string) => {
          const sessionType = videoFile.includes('session-1') ? 'session-1' : 'session-2';
          const analysisResponse = await fetch(
            `/api/admin/emotion-analysis?action=analyze-single&participantId=${selectedParticipant}&videoFile=${videoFile}&sessionType=${sessionType}`
          );
          return analysisResponse.json();
        });

        const results = await Promise.all(analysisPromises);
        const successCount = results.filter(r => r.success).length;

        setAnalysisStatus('success');
        setStatusMessage(`${successCount}/${videoFiles.length}件のビデオ分析が完了しました`);

        if (onAnalysisComplete) {
          onAnalysisComplete();
        }
      }

    } catch (error) {
      console.error('Analysis error:', error);
      setAnalysisStatus('error');
      setStatusMessage(error instanceof Error ? error.message : '分析中にエラーが発生しました');
    } finally {
      setIsAnalyzing(false);
    }
  };

  const handleBatchAnalysis = async () => {
    setIsAnalyzing(true);
    setAnalysisStatus('idle');
    setStatusMessage('バッチ分析を開始します...');

    try {
      if (useWorkflow) {
        // ワークフローAPIを使用した全参加者分析
        const response = await fetch('/api/admin/emotion-analysis', {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
          },
          body: JSON.stringify({
            action: 'analyze-all-workflow',
            priority: 'normal',
          }),
        });

        const result = await response.json();

        if (!result.success) {
          throw new Error(result.error || 'バッチワークフロー開始に失敗しました');
        }

        setAnalysisStatus('success');
        setStatusMessage(`全参加者ワークフロー分析を開始しました (Batch ID: ${result.data?.batchId?.slice(0, 8)}...)`);

        // ワークフロー開始後にステータスを確認
        setTimeout(() => {
          if (onAnalysisComplete) {
            onAnalysisComplete();
          }
        }, 3000);

      } else {
        // 従来の同期バッチ分析
        const results = [];

        for (let i = 0; i < participants.length; i++) {
          const participantId = participants[i];

          try {
            const response = await fetch('/api/admin/emotion-analysis', {
              method: 'POST',
              headers: {
                'Content-Type': 'application/json',
              },
              body: JSON.stringify({
                action: 'analyze-batch',
                participantId
              })
            });

            const result = await response.json();
            results.push(result);

            // 進捗を表示
            setStatusMessage(`${results.length}/${participants.length}人の分析が完了しました`);

            // APIレート制限を考慮して少し待つ
            await new Promise(resolve => setTimeout(resolve, 2000));

          } catch (error) {
            console.error(`Error analyzing participant ${participantId}:`, error);
          }
        }

        const successCount = results.filter(r => r.success).length;
        setAnalysisStatus('success');
        setStatusMessage(`同期バッチ分析完了: ${successCount}/${participants.length}人の分析に成功しました`);

        if (onAnalysisComplete) {
          onAnalysisComplete();
        }
      }

    } catch (error) {
      console.error('Batch analysis error:', error);
      setAnalysisStatus('error');
      setStatusMessage(error instanceof Error ? error.message : 'バッチ分析中にエラーが発生しました');
    } finally {
      setIsAnalyzing(false);
    }
  };

  return (
    <div className="space-y-6">
      {/* Single Participant Analysis */}
      <Card>
        <CardHeader>
          <CardTitle className="flex items-center space-x-2">
            <Video className="h-5 w-5" />
            <span>個別参加者分析</span>
          </CardTitle>
          <CardDescription>
            特定の参加者のビデオファイルを感情分析
          </CardDescription>
        </CardHeader>
        <CardContent>
          <div className="flex items-center space-x-3 mb-4">
            <label className="flex items-center space-x-2 cursor-pointer">
              <input
                type="checkbox"
                checked={useWorkflow}
                onChange={(e) => setUseWorkflow(e.target.checked)}
                className="rounded"
              />
              <span className="text-sm">
                <Workflow className="h-4 w-4 inline mr-1" />
                ワークフロー使用
              </span>
            </label>
            <Badge variant={useWorkflow ? "default" : "secondary"} className="text-xs">
              {useWorkflow ? "非同期" : "同期"}
            </Badge>
          </div>
        </CardContent>
        <CardContent className="space-y-4">
          <div className="flex flex-col md:flex-row gap-4">
            <div className="flex-1">
              <Select value={selectedParticipant} onValueChange={setSelectedParticipant}>
                <SelectTrigger>
                  <SelectValue placeholder="参加者を選択してください" />
                </SelectTrigger>
                <SelectContent>
                  {participants.map((participantId) => (
                    <SelectItem key={participantId} value={participantId}>
                      {participantId.slice(0, 8)}...
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>
            <Button
              onClick={handleSingleAnalysis}
              disabled={isAnalyzing || !selectedParticipant}
              className="flex items-center space-x-2"
            >
              {isAnalyzing ? (
                <Loader2 className="h-4 w-4 animate-spin" />
              ) : (
                <Play className="h-4 w-4" />
              )}
              <span>{isAnalyzing ? '分析中...' : '分析実行'}</span>
            </Button>
          </div>

          {/* Status Message */}
          {statusMessage && (
            <div className={`flex items-center space-x-2 p-3 rounded-lg ${
              analysisStatus === 'success'
                ? 'bg-green-50 border border-green-200'
                : analysisStatus === 'error'
                ? 'bg-red-50 border border-red-200'
                : 'bg-blue-50 border border-blue-200'
            }`}>
              {analysisStatus === 'success' && <CheckCircle className="h-5 w-5 text-green-600" />}
              {analysisStatus === 'error' && <AlertCircle className="h-5 w-5 text-red-600" />}
              {analysisStatus === 'idle' && isAnalyzing && <Loader2 className="h-5 w-5 animate-spin text-blue-600" />}
              <span className={`text-sm ${
                analysisStatus === 'success'
                  ? 'text-green-800'
                  : analysisStatus === 'error'
                  ? 'text-red-800'
                  : 'text-blue-800'
              }`}>
                {statusMessage}
              </span>
            </div>
          )}
        </CardContent>
      </Card>

      {/* Batch Analysis */}
      <Card>
        <CardHeader>
          <CardTitle className="flex items-center space-x-2">
            <Users className="h-5 w-5" />
            <span>全参加者バッチ分析</span>
          </CardTitle>
          <CardDescription>
            全ての参加者のビデオファイルを一括で感情分析
          </CardDescription>
        </CardHeader>
        <CardContent className="space-y-4">
          <div className="bg-yellow-50 border border-yellow-200 p-4 rounded-lg">
            <div className="flex items-start space-x-3">
              <AlertCircle className="h-5 w-5 text-yellow-600 mt-0.5" />
              <div>
                <h4 className="text-sm font-medium text-yellow-800">注意事項</h4>
                <ul className="text-sm text-yellow-700 mt-1 space-y-1">
                  <li>• 全{participants.length}人の参加者を分析します</li>
                  <li>• 各分析に数秒～数分かかります</li>
                  <li>• Hume APIのレート制限を考慮して順次実行します</li>
                  <li>• ビデオファイルが存在しない参加者はスキップされます</li>
                </ul>
              </div>
            </div>
          </div>

          <div className="flex items-center justify-between">
            <div className="flex items-center space-x-4">
              <Badge variant="outline">
                対象参加者: {participants.length}人
              </Badge>
              <Badge variant="outline">
                推定時間: {Math.ceil(participants.length * 0.5)}分
              </Badge>
            </div>

            <Button
              onClick={handleBatchAnalysis}
              disabled={isAnalyzing}
              variant="outline"
              className="flex items-center space-x-2"
            >
              {isAnalyzing ? (
                <Loader2 className="h-4 w-4 animate-spin" />
              ) : (
                <Play className="h-4 w-4" />
              )}
              <span>{isAnalyzing ? '分析実行中...' : 'バッチ分析開始'}</span>
            </Button>
          </div>
        </CardContent>
      </Card>

      {/* Analysis Info */}
      <Card>
        <CardHeader>
          <CardTitle className="flex items-center space-x-2">
            <Brain className="h-5 w-5" />
            <span>感情分析について</span>
          </CardTitle>
        </CardHeader>
        <CardContent>
          <div className="space-y-3 text-sm text-gray-600">
            <p>
              <strong>Hume AI</strong> を使用してビデオファイルから以下の感情を分析します：
            </p>
            <ul className="list-disc list-inside space-y-1 ml-4">
              <li>顔表情の分析（FACS）</li>
              <li>感情の強度スコアリング</li>
              <li>信頼度評価</li>
              <li>リアルタイム処理</li>
            </ul>
            <p>
              分析結果は各参加者のディレクトリに <code className="bg-gray-100 px-1 rounded">emotion_analysis.json</code> として保存されます。
            </p>
          </div>
        </CardContent>
      </Card>
    </div>
  );
}
