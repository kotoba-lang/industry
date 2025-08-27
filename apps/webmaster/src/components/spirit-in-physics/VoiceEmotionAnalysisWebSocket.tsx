'use client';

import { useState, useEffect, useRef } from 'react';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Progress } from '@/components/ui/progress';
import { AlertCircle, Clock, Mic, MicOff } from 'lucide-react';
import { HumeRealtimeEmotionService, ConnectionState } from '@/lib/client/hume-realtime';
import { HumeVoiceEmotion } from '@/lib/actions/hume-service';

interface VoiceEmotionAnalysisWebSocketProps {
  apiKey?: string;
}

export default function VoiceEmotionAnalysisWebSocket({ 
  apiKey = process.env.NEXT_PUBLIC_HUME_API_KEY || '',
}: VoiceEmotionAnalysisWebSocketProps) {
  const [isConnected, setIsConnected] = useState<boolean>(false);
  const [isRecording, setIsRecording] = useState<boolean>(false);
  const [emotions, setEmotions] = useState<HumeVoiceEmotion[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [recordingTime, setRecordingTime] = useState<number>(0);
  const [connectionState, setConnectionState] = useState<ConnectionState>(ConnectionState.CLOSED);
  
  const mediaRecorderRef = useRef<MediaRecorder | null>(null);
  const audioChunksRef = useRef<Blob[]>([]);
  const timerIdRef = useRef<NodeJS.Timeout | null>(null);
  const humeServiceRef = useRef<HumeRealtimeEmotionService | null>(null);
  const streamRef = useRef<MediaStream | null>(null);
  
  // エモーションサービスの初期化
  useEffect(() => {
    if (!apiKey) {
      setError('APIキーが設定されていません。環境変数を確認してください。');
      return;
    }
    
    humeServiceRef.current = new HumeRealtimeEmotionService(
      apiKey,
      // Face data handler (not used)
      () => {},
      // Voice data handler
      (voiceData) => {
        if (voiceData.emotions && voiceData.emotions.length > 0) {
          console.log('Voice emotion data received:', voiceData);
          setEmotions(voiceData.emotions);
        }
      },
      // Error handler
      (error) => {
        console.error('Hume WebSocket error:', error);
        const errorMessage = error instanceof Error 
          ? error.message 
          : typeof error === 'object' && error !== null && 'message' in error
            ? String(error.message)
            : 'WebSocket接続エラーが発生しました';
        setError(errorMessage);
        setIsConnected(false);
        setConnectionState(ConnectionState.ERROR);
      }
    );
    
    return () => {
      stopRecording();
      if (humeServiceRef.current) {
        humeServiceRef.current.closeConnection();
      }
    };
  }, [apiKey]);
  
  // 接続状態の監視
  useEffect(() => {
    if (!humeServiceRef.current) return;
    
    const intervalId = setInterval(() => {
      if (humeServiceRef.current) {
        const state = humeServiceRef.current.getConnectionState();
        setConnectionState(state);
        setIsConnected(humeServiceRef.current.isAuthenticated());
      }
    }, 1000);
    
    return () => clearInterval(intervalId);
  }, []);
  
  // 録音開始
  const startRecording = async () => {
    try {
      setError(null);
      audioChunksRef.current = [];
      
      // WebSocketが接続されていない場合は接続
      if (!isConnected && humeServiceRef.current) {
        try {
          await humeServiceRef.current.initWebSocket(['prosody']);
          // 接続が成功するまで少し待機
          await new Promise(resolve => setTimeout(resolve, 1000));
        } catch (err) {
          console.error('Failed to initialize WebSocket:', err);
          throw new Error('WebSocketの初期化に失敗しました');
        }
      }
      
      // マイクへのアクセス要求
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
      streamRef.current = stream;
      
      // MediaRecorderの設定
      const recorder = new MediaRecorder(stream);
      mediaRecorderRef.current = recorder;
      
      // データの処理
      recorder.ondataavailable = async (event) => {
        if (event.data.size > 0) {
          audioChunksRef.current.push(event.data);
          
          // 取得したオーディオチャンクを処理
          const audioBlob = new Blob([event.data], { type: 'audio/webm' });
          
          if (humeServiceRef.current && humeServiceRef.current.isAuthenticated()) {
            try {
              await humeServiceRef.current.sendAudioData(audioBlob);
            } catch (err) {
              console.error('Failed to send audio data:', err);
            }
          }
        }
      };
      
      // 録音開始（2秒ごとにデータを取得）
      recorder.start(2000);
      setIsRecording(true);
      
      // タイマーの設定
      setRecordingTime(0);
      timerIdRef.current = setInterval(() => {
        setRecordingTime(prev => prev + 1);
      }, 1000);
      
    } catch (err) {
      console.error('Error starting recording:', err);
      setError(err instanceof Error ? err.message : 'マイクの起動に失敗しました');
    }
  };
  
  // 録音停止
  const stopRecording = () => {
    if (mediaRecorderRef.current && isRecording) {
      mediaRecorderRef.current.stop();
      
      // トラックの停止
      if (streamRef.current) {
        streamRef.current.getTracks().forEach(track => track.stop());
        streamRef.current = null;
      }
      
      setIsRecording(false);
      
      // タイマーの停止
      if (timerIdRef.current) {
        clearInterval(timerIdRef.current);
        timerIdRef.current = null;
      }
    }
  };
  
  // 接続状態のテキスト表示
  const getConnectionStateText = (): string => {
    switch (connectionState) {
      case ConnectionState.CLOSED:
        return '切断';
      case ConnectionState.CONNECTING:
        return '接続中...';
      case ConnectionState.OPEN:
        return '接続済み（認証中...）';
      case ConnectionState.AUTHENTICATED:
        return '接続済み';
      case ConnectionState.ERROR:
        return 'エラー';
      default:
        return '不明';
    }
  };
  
  // 時間のフォーマット MM:SS
  const formatTime = (seconds: number): string => {
    const mins = Math.floor(seconds / 60);
    const secs = seconds % 60;
    return `${mins.toString().padStart(2, '0')}:${secs.toString().padStart(2, '0')}`;
  };
  
  // 上位5つの感情を取得
  const topEmotions = emotions.slice().sort((a, b) => b.score - a.score).slice(0, 5);
  
  return (
    <div className="space-y-6">
      <Card>
        <CardHeader>
          <CardTitle className="flex items-center justify-between">
            <span>リアルタイム音声感情分析</span>
            <span className={`text-sm px-2 py-1 rounded-full ${
              isConnected 
                ? 'bg-green-100 text-green-800' 
                : connectionState === ConnectionState.CONNECTING 
                  ? 'bg-yellow-100 text-yellow-800'
                  : 'bg-gray-100 text-gray-800'
            }`}>
              {getConnectionStateText()}
            </span>
          </CardTitle>
        </CardHeader>
        <CardContent>
          <div className="flex flex-col items-center space-y-4">
            {error && (
              <div className="p-4 bg-red-100 text-red-700 rounded-md w-full flex items-start">
                <AlertCircle className="w-5 h-5 mr-2 mt-0.5 flex-shrink-0" />
                <div>{error}</div>
              </div>
            )}
            
            <div className="text-center mb-4">
              {isRecording ? (
                <div className="text-xl font-bold">{formatTime(recordingTime)}</div>
              ) : (
                <div className="text-gray-500">録音ボタンを押して開始</div>
              )}
            </div>
            
            <div className="flex space-x-4">
              {!isRecording ? (
                <Button 
                  onClick={startRecording} 
                  disabled={connectionState === ConnectionState.ERROR}
                  className="w-32 flex items-center"
                >
                  <Mic className="w-4 h-4 mr-2" />
                  録音開始
                </Button>
              ) : (
                <Button 
                  onClick={stopRecording} 
                  variant="destructive"
                  className="w-32 flex items-center"
                >
                  <MicOff className="w-4 h-4 mr-2" />
                  録音停止
                </Button>
              )}
            </div>
          </div>
        </CardContent>
      </Card>
      
      {topEmotions.length > 0 && (
        <Card>
          <CardHeader>
            <CardTitle>リアルタイム検出感情</CardTitle>
          </CardHeader>
          <CardContent>
            <div className="space-y-4">
              {topEmotions.map(emotion => (
                <div key={emotion.name} className="space-y-1">
                  <div className="flex justify-between">
                    <span>{emotion.name}</span>
                    <span className="font-medium">{(emotion.score * 100).toFixed(1)}%</span>
                  </div>
                  <Progress value={emotion.score * 100} className="h-2" />
                </div>
              ))}
            </div>
          </CardContent>
        </Card>
      )}
      
      {!isConnected && !error && (
        <div className="text-center p-4 bg-blue-50 rounded-md">
          <p className="text-blue-700">
            WebSocket接続は録音開始時に自動的に確立されます。
          </p>
        </div>
      )}
    </div>
  );
} 