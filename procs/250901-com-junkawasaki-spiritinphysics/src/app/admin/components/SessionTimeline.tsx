'use client';

import React, { useState, useEffect } from 'react';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { Badge } from '@/components/ui/badge';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import {
  Clock,
  Play,
  Activity,
  Brain,
  MessageSquare
} from 'lucide-react';

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

interface Participant {
  id: string;
  age: number;
  gender: string;
  handedness: string;
  createdAt: Date;
  sessionCount: number;
  lastActivity: Date;
  status: string;
}

export function SessionTimeline() {
  const [sessions, setSessions] = useState<SessionData[]>([]);
  const [participants, setParticipants] = useState<Participant[]>([]);
  const [selectedParticipant, setSelectedParticipant] = useState('all');
  const [selectedSessionType, setSelectedSessionType] = useState('all');
  const [isLoading, setIsLoading] = useState(true);

  const fetchData = async () => {
    try {
      const [sessionsResponse, participantsResponse] = await Promise.all([
        fetch('/api/admin/experimental-data?type=sessions'),
        fetch('/api/admin/experimental-data?type=participants')
      ]);

      const sessionsResult = await sessionsResponse.json();
      const participantsResult = await participantsResponse.json();

      if (sessionsResult.success) {
        setSessions(sessionsResult.data);
      }
      if (participantsResult.success) {
        setParticipants(participantsResult.data);
      }
    } catch (error) {
      console.error('Error fetching timeline data:', error);
    } finally {
      setIsLoading(false);
    }
  };

  useEffect(() => {
    fetchData();
  }, []);

  // Filter sessions based on selected criteria
  const filteredSessions = sessions.filter(session => {
    const participantMatch = selectedParticipant === 'all' || session.participantId === selectedParticipant;
    const sessionTypeMatch = selectedSessionType === 'all' || session.sessionType === selectedSessionType;
    return participantMatch && sessionTypeMatch;
  });

  // Sort sessions by start time
  const sortedSessions = filteredSessions.sort((a, b) =>
    new Date(a.startTime).getTime() - new Date(b.startTime).getTime()
  );

  const getParticipantInfo = (participantId: string) => {
    return participants.find(p => p.id === participantId);
  };

  const getSessionDuration = (startTime: string, endTime: string) => {
    const start = new Date(startTime);
    const end = new Date(endTime);
    const durationMs = end.getTime() - start.getTime();
    const minutes = Math.floor(durationMs / 60000);
    const seconds = Math.floor((durationMs % 60000) / 1000);
    return `${minutes}:${seconds.toString().padStart(2, '0')}`;
  };


  const getEmotionIcon = (emotion: string) => {
    // Simple emotion to icon mapping
    const emotionIcons: Record<string, string> = {
      joy: '😊',
      sadness: '😢',
      anger: '😠',
      fear: '😨',
      surprise: '😲',
      calm: '😌',
      wonder: '🤔',
      peace: '🕊️'
    };
    return emotionIcons[emotion.toLowerCase()] || '😐';
  };

  if (isLoading) {
    return (
      <Card>
        <CardContent className="p-6">
          <div className="flex items-center justify-center">
            <div className="animate-spin rounded-full h-8 w-8 border-b-2 border-gray-900"></div>
            <span className="ml-2">タイムラインデータを読み込み中...</span>
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
            <Clock className="h-5 w-5" />
            <span>セッションタイムライン</span>
          </CardTitle>
          <CardDescription>
            実験セッションの時間的推移と詳細な活動記録
          </CardDescription>
        </CardHeader>
        <CardContent>
          <div className="flex flex-col md:flex-row gap-4">
            <div className="flex-1">
              <label className="block text-sm font-medium mb-2">参加者フィルター</label>
              <Select value={selectedParticipant} onValueChange={setSelectedParticipant}>
                <SelectTrigger>
                  <SelectValue placeholder="全ての参加者" />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="all">全ての参加者</SelectItem>
                  {participants.map((participant) => (
                    <SelectItem key={participant.id} value={participant.id}>
                      {participant.id.slice(0, 8)}... (年齢: {participant.age}, {participant.gender})
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>

            <div className="flex-1">
              <label className="block text-sm font-medium mb-2">セッションタイプ</label>
              <Select value={selectedSessionType} onValueChange={setSelectedSessionType}>
                <SelectTrigger>
                  <SelectValue placeholder="全てのタイプ" />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="all">全てのタイプ</SelectItem>
                  <SelectItem value="session-1">セッション 1</SelectItem>
                  <SelectItem value="session-2">セッション 2</SelectItem>
                </SelectContent>
              </Select>
            </div>
          </div>
        </CardContent>
      </Card>

      {/* Timeline */}
      <Card>
        <CardHeader>
          <CardTitle>セッションタイムライン</CardTitle>
          <CardDescription>
            {sortedSessions.length} 件のセッションが表示されています
          </CardDescription>
        </CardHeader>
        <CardContent>
          {sortedSessions.length === 0 ? (
            <div className="text-center py-8 text-gray-500">
              条件に一致するセッションが見つかりません
            </div>
          ) : (
            <div className="space-y-6">
              {sortedSessions.map((session, index) => {
                const participant = getParticipantInfo(session.participantId);
                return (
                  <div key={`${session.participantId}-${session.sessionId}`} className="relative">
                    {/* Timeline connector */}
                    {index < sortedSessions.length - 1 && (
                      <div className="absolute left-6 top-12 w-0.5 h-16 bg-gray-200"></div>
                    )}

                    <div className="flex items-start space-x-4">
                      {/* Timeline dot */}
                      <div className="flex-shrink-0">
                        <div className="w-12 h-12 bg-blue-100 rounded-full flex items-center justify-center">
                          <Play className="w-6 h-6 text-blue-600" />
                        </div>
                      </div>

                      {/* Session details */}
                      <div className="flex-1 bg-gray-50 rounded-lg p-4">
                        <div className="flex items-center justify-between mb-3">
                          <div className="flex items-center space-x-3">
                            <h3 className="font-semibold text-lg">
                              {session.sessionType === 'session-1' ? 'セッション 1' : 'セッション 2'}
                            </h3>
                            <Badge variant="outline">
                              {participant ? `${participant.age}歳 ${participant.gender}` : '不明'}
                            </Badge>
                          </div>
                          <div className="text-sm text-gray-500">
                            {new Date(session.startTime).toLocaleString('ja-JP')}
                          </div>
                        </div>

                        <div className="grid grid-cols-1 md:grid-cols-3 gap-4 mb-4">
                          <div className="flex items-center space-x-2">
                            <Clock className="w-4 h-4 text-gray-400" />
                            <span className="text-sm">
                              所要時間: {getSessionDuration(session.startTime, session.endTime)}
                            </span>
                          </div>
                          <div className="flex items-center space-x-2">
                            <Activity className="w-4 h-4 text-gray-400" />
                            <span className="text-sm">
                              応答数: {session.wordResponses.length}
                            </span>
                          </div>
                          <div className="flex items-center space-x-2">
                            <Brain className="w-4 h-4 text-gray-400" />
                            <span className="text-sm">
                              平均反応時間: {Math.round(session.averageReactionTime)}ms
                            </span>
                          </div>
                        </div>

                        {/* Word Responses */}
                        <div className="mb-4">
                          <h4 className="text-sm font-medium mb-2 flex items-center space-x-2">
                            <MessageSquare className="w-4 h-4" />
                            <span>単語応答 ({session.wordResponses.length}件)</span>
                          </h4>
                          <div className="grid grid-cols-1 md:grid-cols-2 gap-2">
                            {session.wordResponses.slice(0, 4).map((response, idx) => (
                              <div key={idx} className="bg-white rounded p-2 text-sm">
                                <div className="flex justify-between items-center">
                                  <span className="font-medium">{response.stimulusWord.word}</span>
                                  <Badge
                                    variant={response.isDelayed ? "destructive" : "secondary"}
                                    className="text-xs"
                                  >
                                    {response.reactionTimeMs}ms
                                  </Badge>
                                </div>
                                <div className="text-gray-600 mt-1">
                                  → {response.responseWord}
                                </div>
                              </div>
                            ))}
                            {session.wordResponses.length > 4 && (
                              <div className="bg-white rounded p-2 text-sm text-center text-gray-500">
                                他{session.wordResponses.length - 4}件...
                              </div>
                            )}
                          </div>
                        </div>

                        {/* Video Files */}
                        {participant?.hasVideoFiles && (
                          <div className="mb-4">
                            <h4 className="text-sm font-medium mb-2 flex items-center space-x-2">
                              <Activity className="w-4 h-4" />
                              <span>ビデオファイル ({participant.videoFiles.length}個)</span>
                            </h4>
                            <div className="flex flex-wrap gap-2">
                              {participant.videoFiles.map((file, idx) => (
                                <Badge key={idx} variant="outline" className="text-xs">
                                  📹 {file}
                                </Badge>
                              ))}
                            </div>
                          </div>
                        )}

                        {/* Emotion Data */}
                        {session.emotionData.length > 0 && (
                          <div>
                            <h4 className="text-sm font-medium mb-2 flex items-center space-x-2">
                              <Brain className="w-4 h-4" />
                              <span>感情データ ({session.emotionData.length}件)</span>
                            </h4>
                            <div className="flex flex-wrap gap-2">
                              {session.emotionData.slice(0, 3).map((emotion, idx) => (
                                <Badge key={idx} variant="outline" className="text-xs">
                                  <span className="mr-1">{getEmotionIcon(emotion.emotion)}</span>
                                  {emotion.emotion} ({Math.round(emotion.confidence * 100)}%)
                                </Badge>
                              ))}
                              {session.emotionData.length > 3 && (
                                <Badge variant="outline" className="text-xs">
                                  +{session.emotionData.length - 3}件
                                </Badge>
                              )}
                            </div>
                          </div>
                        )}
                      </div>
                    </div>
                  </div>
                );
              })}
            </div>
          )}
        </CardContent>
      </Card>

      {/* Summary Stats */}
      <div className="grid grid-cols-1 md:grid-cols-4 gap-4">
        <Card>
          <CardHeader className="flex flex-row items-center justify-between space-y-0 pb-2">
            <CardTitle className="text-sm font-medium">総セッション数</CardTitle>
            <Activity className="h-4 w-4 text-muted-foreground" />
          </CardHeader>
          <CardContent>
            <div className="text-2xl font-bold">{sortedSessions.length}</div>
            <p className="text-xs text-muted-foreground">
              表示中のセッション
            </p>
          </CardContent>
        </Card>

        <Card>
          <CardHeader className="flex flex-row items-center justify-between space-y-0 pb-2">
            <CardTitle className="text-sm font-medium">平均所要時間</CardTitle>
            <Clock className="h-4 w-4 text-muted-foreground" />
          </CardHeader>
          <CardContent>
            <div className="text-2xl font-bold">
              {sortedSessions.length > 0
                ? Math.round(
                    sortedSessions.reduce((acc, session) => {
                      const duration = new Date(session.endTime).getTime() - new Date(session.startTime).getTime();
                      return acc + duration;
                    }, 0) / sortedSessions.length / 60000
                  )
                : 0}分
            </div>
            <p className="text-xs text-muted-foreground">
              セッションあたり
            </p>
          </CardContent>
        </Card>

        <Card>
          <CardHeader className="flex flex-row items-center justify-between space-y-0 pb-2">
            <CardTitle className="text-sm font-medium">総応答数</CardTitle>
            <MessageSquare className="h-4 w-4 text-muted-foreground" />
          </CardHeader>
          <CardContent>
            <div className="text-2xl font-bold">
              {sortedSessions.reduce((acc, session) => acc + session.wordResponses.length, 0)}
            </div>
            <p className="text-xs text-muted-foreground">
              全セッション合計
            </p>
          </CardContent>
        </Card>

        <Card>
          <CardHeader className="flex flex-row items-center justify-between space-y-0 pb-2">
            <CardTitle className="text-sm font-medium">感情データ数</CardTitle>
            <Brain className="h-4 w-4 text-muted-foreground" />
          </CardHeader>
          <CardContent>
            <div className="text-2xl font-bold">
              {sortedSessions.reduce((acc, session) => acc + session.emotionData.length, 0)}
            </div>
            <p className="text-xs text-muted-foreground">
              記録された感情データ
            </p>
          </CardContent>
        </Card>
      </div>
    </div>
  );
}
