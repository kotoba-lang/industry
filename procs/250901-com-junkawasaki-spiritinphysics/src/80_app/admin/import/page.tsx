// LLM-BOUNDARY: 80_app - app/(segments)/...（RSC & Client）

'use client';

import React, { useState } from 'react';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { Progress } from '@/components/ui/progress';
import { AlertCircle, CheckCircle, Database, Cloud, FileText } from 'lucide-react';
import { Alert, AlertDescription } from '@/components/ui/alert';

type ImportStatus = 'idle' | 'running' | 'completed' | 'error';

interface ImportResult {
  participantId: string;
  status: 'success' | 'error';
  message: string;
  details?: any;
}

export default function ImportPage() {
  const [participantImportStatus, setParticipantImportStatus] = useState<ImportStatus>('idle');
  const [sessionImportStatus, setSessionImportStatus] = useState<ImportStatus>('idle');
  const [emotionImportStatus, setEmotionImportStatus] = useState<ImportStatus>('idle');

  const [participantResults, setParticipantResults] = useState<ImportResult[]>([]);
  const [sessionResults, setSessionResults] = useState<ImportResult[]>([]);
  const [emotionResults, setEmotionResults] = useState<ImportResult[]>([]);

  const [progress, setProgress] = useState(0);

  // 参加者データをインポート
  const importParticipants = async () => {
    setParticipantImportStatus('running');
    setParticipantResults([]);
    setProgress(0);

    try {
      const response = await fetch('/api/admin/import/participants', {
        method: 'POST',
      });

      if (!response.ok) {
        throw new Error('Failed to import participants');
      }

      const data = await response.json();
      setParticipantResults(data.results || []);
      setParticipantImportStatus('completed');
    } catch (error) {
      console.error('Import error:', error);
      setParticipantImportStatus('error');
      setParticipantResults([{
        participantId: 'all',
        status: 'error',
        message: error instanceof Error ? error.message : 'Unknown error'
      }]);
    }
  };

  // セッションデータをインポート
  const importSessions = async () => {
    setSessionImportStatus('running');
    setSessionResults([]);
    setProgress(0);

    try {
      const response = await fetch('/api/admin/import/sessions', {
        method: 'POST',
      });

      if (!response.ok) {
        throw new Error('Failed to import sessions');
      }

      const data = await response.json();
      setSessionResults(data.results || []);
      setSessionImportStatus('completed');
    } catch (error) {
      console.error('Import error:', error);
      setSessionImportStatus('error');
      setSessionResults([{
        participantId: 'all',
        status: 'error',
        message: error instanceof Error ? error.message : 'Unknown error'
      }]);
    }
  };

  // 感情分析データをインポート
  const importEmotions = async () => {
    setEmotionImportStatus('running');
    setEmotionResults([]);
    setProgress(0);

    try {
      const response = await fetch('/api/admin/import/emotions', {
        method: 'POST',
      });

      if (!response.ok) {
        throw new Error('Failed to import emotion data');
      }

      const data = await response.json();
      setEmotionResults(data.results || []);
      setEmotionImportStatus('completed');
    } catch (error) {
      console.error('Import error:', error);
      setEmotionImportStatus('error');
      setEmotionResults([{
        participantId: 'all',
        status: 'error',
        message: error instanceof Error ? error.message : 'Unknown error'
      }]);
    }
  };

  const getStatusIcon = (status: ImportStatus) => {
    switch (status) {
      case 'running':
        return <Database className="h-4 w-4 animate-spin" />;
      case 'completed':
        return <CheckCircle className="h-4 w-4 text-green-500" />;
      case 'error':
        return <AlertCircle className="h-4 w-4 text-red-500" />;
      default:
        return <FileText className="h-4 w-4" />;
    }
  };

  const getStatusColor = (status: ImportStatus) => {
    switch (status) {
      case 'running':
        return 'bg-blue-500';
      case 'completed':
        return 'bg-green-500';
      case 'error':
        return 'bg-red-500';
      default:
        return 'bg-gray-500';
    }
  };

  return (
    <div className="container mx-auto py-8">
      <div className="mb-8">
        <h1 className="text-3xl font-bold">データインポート</h1>
        <p className="text-gray-600 mt-2">
          ファイルシステムからデータを手動でインポートします
        </p>
      </div>

      <Tabs defaultValue="participants" className="space-y-6">
        <TabsList className="grid w-full grid-cols-3">
          <TabsTrigger value="participants">参加者データ</TabsTrigger>
          <TabsTrigger value="sessions">セッションデータ</TabsTrigger>
          <TabsTrigger value="emotions">感情分析データ</TabsTrigger>
        </TabsList>

        <TabsContent value="participants" className="space-y-6">
          <Card>
            <CardHeader>
              <CardTitle className="flex items-center gap-2">
                {getStatusIcon(participantImportStatus)}
                参加者データインポート
              </CardTitle>
              <CardDescription>
                ファイルシステムから参加者データをKuzuとBlob Storageにインポートします
              </CardDescription>
            </CardHeader>
            <CardContent className="space-y-4">
              <div className="flex items-center gap-4">
                <Button
                  onClick={importParticipants}
                  disabled={participantImportStatus === 'running'}
                  className={`${getStatusColor(participantImportStatus)} text-white`}
                >
                  {participantImportStatus === 'running' ? 'インポート中...' : '参加者データをインポート'}
                </Button>
                <Badge variant="outline">
                  ステータス: {participantImportStatus === 'idle' ? '待機中' :
                               participantImportStatus === 'running' ? '実行中' :
                               participantImportStatus === 'completed' ? '完了' : 'エラー'}
                </Badge>
              </div>

              {participantImportStatus === 'running' && (
                <Progress value={progress} className="w-full" />
              )}

              {participantResults.length > 0 && (
                <div className="space-y-2">
                  <h4 className="font-semibold">結果:</h4>
                  {participantResults.map((result, index) => (
                    <Alert key={index} className={result.status === 'success' ? 'border-green-200' : 'border-red-200'}>
                      <AlertCircle className={`h-4 w-4 ${result.status === 'success' ? 'text-green-500' : 'text-red-500'}`} />
                      <AlertDescription>
                        <strong>{result.participantId}:</strong> {result.message}
                      </AlertDescription>
                    </Alert>
                  ))}
                </div>
              )}
            </CardContent>
          </Card>
        </TabsContent>

        <TabsContent value="sessions" className="space-y-6">
          <Card>
            <CardHeader>
              <CardTitle className="flex items-center gap-2">
                {getStatusIcon(sessionImportStatus)}
                セッションデータインポート
              </CardTitle>
              <CardDescription>
                ファイルシステムからセッションデータをKuzuとBlob Storageにインポートします
              </CardDescription>
            </CardHeader>
            <CardContent className="space-y-4">
              <div className="flex items-center gap-4">
                <Button
                  onClick={importSessions}
                  disabled={sessionImportStatus === 'running'}
                  className={`${getStatusColor(sessionImportStatus)} text-white`}
                >
                  {sessionImportStatus === 'running' ? 'インポート中...' : 'セッションデータをインポート'}
                </Button>
                <Badge variant="outline">
                  ステータス: {sessionImportStatus === 'idle' ? '待機中' :
                               sessionImportStatus === 'running' ? '実行中' :
                               sessionImportStatus === 'completed' ? '完了' : 'エラー'}
                </Badge>
              </div>

              {sessionImportStatus === 'running' && (
                <Progress value={progress} className="w-full" />
              )}

              {sessionResults.length > 0 && (
                <div className="space-y-2">
                  <h4 className="font-semibold">結果:</h4>
                  {sessionResults.map((result, index) => (
                    <Alert key={index} className={result.status === 'success' ? 'border-green-200' : 'border-red-200'}>
                      <AlertCircle className={`h-4 w-4 ${result.status === 'success' ? 'text-green-500' : 'text-red-500'}`} />
                      <AlertDescription>
                        <strong>{result.participantId}:</strong> {result.message}
                      </AlertDescription>
                    </Alert>
                  ))}
                </div>
              )}
            </CardContent>
          </Card>
        </TabsContent>

        <TabsContent value="emotions" className="space-y-6">
          <Card>
            <CardHeader>
              <CardTitle className="flex items-center gap-2">
                {getStatusIcon(emotionImportStatus)}
                感情分析データインポート
              </CardTitle>
              <CardDescription>
                ファイルシステムから感情分析データをKuzuとBlob Storageにインポートします
              </CardDescription>
            </CardHeader>
            <CardContent className="space-y-4">
              <div className="flex items-center gap-4">
                <Button
                  onClick={importEmotions}
                  disabled={emotionImportStatus === 'running'}
                  className={`${getStatusColor(emotionImportStatus)} text-white`}
                >
                  {emotionImportStatus === 'running' ? 'インポート中...' : '感情分析データをインポート'}
                </Button>
                <Badge variant="outline">
                  ステータス: {emotionImportStatus === 'idle' ? '待機中' :
                               emotionImportStatus === 'running' ? '実行中' :
                               emotionImportStatus === 'completed' ? '完了' : 'エラー'}
                </Badge>
              </div>

              {emotionImportStatus === 'running' && (
                <Progress value={progress} className="w-full" />
              )}

              {emotionResults.length > 0 && (
                <div className="space-y-2">
                  <h4 className="font-semibold">結果:</h4>
                  {emotionResults.map((result, index) => (
                    <Alert key={index} className={result.status === 'success' ? 'border-green-200' : 'border-red-200'}>
                      <AlertCircle className={`h-4 w-4 ${result.status === 'success' ? 'text-green-500' : 'text-red-500'}`} />
                      <AlertDescription>
                        <strong>{result.participantId}:</strong> {result.message}
                      </AlertDescription>
                    </Alert>
                  ))}
                </div>
              )}
            </CardContent>
          </Card>
        </TabsContent>
      </Tabs>
    </div>
  );
}
