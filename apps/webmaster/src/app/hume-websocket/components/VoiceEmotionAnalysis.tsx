"use client";

import { useState, useEffect, useRef } from "react";
import { Button } from "@/components/ui/button";
import { Card } from "@/components/ui/card";
import { Separator } from "@/components/ui/separator";
import { Progress } from "@/components/ui/progress";

type Emotion = {
  name: string;
  score: number;
};

type ProsodyResult = {
  emotions: Emotion[];
  timestamp_ms: number;
};

export default function VoiceEmotionAnalysis() {
  const [isConnected, setIsConnected] = useState<boolean>(false);
  const [isLoading, setIsLoading] = useState<boolean>(false);
  const [error, setError] = useState<string | null>(null);
  const [isRecording, setIsRecording] = useState<boolean>(false);
  const [emotionResults, setEmotionResults] = useState<ProsodyResult[]>([]);
  const [mediaRecorder, setMediaRecorder] = useState<MediaRecorder | null>(null);
  const [audioChunks, setAudioChunks] = useState<Blob[]>([]);
  const [recordingTime, setRecordingTime] = useState<number>(0);
  const [timer, setTimer] = useState<NodeJS.Timeout | null>(null);

  const socketRef = useRef<WebSocket | null>(null);
  const streamRef = useRef<MediaStream | null>(null);

  useEffect(() => {
    return () => {
      // コンポーネントがアンマウントされたときにリソースを解放
      stopRecording();
      disconnectWebSocket();
      if (timer) clearInterval(timer);
    };
  }, [timer]);

  const connectWebSocket = () => {
    setIsLoading(true);
    setError(null);

    // デバッグ用にAPIキーを確認
    const apiKey = process.env.NEXT_PUBLIC_HUME_API_KEY;
    console.log("APIキー存在チェック:", apiKey ? "キーあり" : "キーなし");
    
    // 接続URLを作成して確認
    const wsUrl = `wss://api.hume.ai/v0/expression-measurement/ws?apiKey=${apiKey}`;
    console.log("音声分析 - 接続URL:", wsUrl);

    try {
      // WebSocket接続
      const socket = new WebSocket(wsUrl);

      socket.onopen = () => {
        console.log("WebSocket connected");
        setIsConnected(true);
        setIsLoading(false);
      };

      socket.onmessage = (event) => {
        try {
          const data = JSON.parse(event.data);
          console.log("音声分析 - 受信データ:", data);
          if (data.prosody && data.prosody.predictions) {
            // 新しい結果を最大5件まで保存する
            setEmotionResults(prev => {
              const newResults = [...prev, ...data.prosody.predictions];
              return newResults.slice(-5); // 最新の5件だけを保持
            });
          } else if (data.error) {
            // エラーレスポンスの処理
            console.error("API Error:", data.error);
            setError(`API エラー: ${data.error.message || data.error}`);
          }
        } catch (err) {
          console.error("Error parsing WebSocket message:", err);
        }
      };

      socket.onerror = (err) => {
        console.error("WebSocket error:", err);
        setError("WebSocket接続エラーが発生しました。詳細はコンソールを確認してください。");
        setIsLoading(false);
        setIsConnected(false);
      };

      socket.onclose = (event) => {
        console.log("WebSocket connection closed", event.code, event.reason);
        setIsConnected(false);
        if (event.code !== 1000) {
          setError(`WebSocket切断: コード ${event.code} ${event.reason ? "- " + event.reason : ""}`);
        }
      };

      socketRef.current = socket;
    } catch (err) {
      console.error("WebSocket creation error:", err);
      setError("WebSocket作成エラー: " + (err instanceof Error ? err.message : String(err)));
      setIsLoading(false);
    }
  };

  const disconnectWebSocket = () => {
    if (socketRef.current) {
      socketRef.current.close();
      setIsConnected(false);
    }
  };

  const startRecording = async () => {
    setError(null);
    
    if (!isConnected) {
      connectWebSocket();
    }

    try {
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
      streamRef.current = stream;
      
      const recorder = new MediaRecorder(stream);
      setMediaRecorder(recorder);
      
      setAudioChunks([]);
      
      recorder.ondataavailable = (e) => {
        if (e.data.size > 0) {
          setAudioChunks(prev => [...prev, e.data]);
        }
      };
      
      recorder.onstop = () => {
        processAudioChunks();
      };
      
      // 3秒ごとにデータを処理する
      recorder.start(3000);
      setIsRecording(true);
      
      // 録音時間の追跡
      const interval = setInterval(() => {
        setRecordingTime(prev => prev + 1);
      }, 1000);
      setTimer(interval);
      
    } catch (err) {
      console.error("Error starting recording:", err);
      setError("マイクへのアクセスに失敗しました。");
    }
  };

  const stopRecording = () => {
    if (mediaRecorder && mediaRecorder.state !== "inactive") {
      mediaRecorder.stop();
    }
    
    if (streamRef.current) {
      streamRef.current.getTracks().forEach(track => track.stop());
      streamRef.current = null;
    }
    
    if (timer) {
      clearInterval(timer);
      setTimer(null);
    }
    
    setIsRecording(false);
    setRecordingTime(0);
  };

  const processAudioChunks = async () => {
    if (audioChunks.length === 0 || !socketRef.current) return;
    
    try {
      const audioBlob = new Blob(audioChunks, { type: 'audio/wav' });
      console.log("音声分析 - Blobサイズ:", audioBlob.size, "bytes");
      
      // Blobをbase64に変換
      const reader = new FileReader();
      reader.readAsDataURL(audioBlob);
      reader.onloadend = () => {
        const base64data = reader.result as string;
        // Base64形式の音声データを抽出 (data:audio/wav;base64,を削除)
        const base64Audio = base64data.split(',')[1];
        
        // JSONメッセージの作成
        const message = {
          models: {
            prosody: {}
          },
          data: base64Audio
        };
        
        try {
          // デバッグ情報
          console.log("音声分析 - 送信メッセージ構造:", {
            ...message,
            data: base64Audio ? `${base64Audio.substring(0, 20)}...（省略）` : "なし"
          });
          
          // WebSocketでメッセージを送信
          socketRef.current?.send(JSON.stringify(message));
        } catch (sendErr) {
          console.error("音声分析 - メッセージ送信エラー:", sendErr);
          setError("メッセージ送信エラー: " + (sendErr instanceof Error ? sendErr.message : String(sendErr)));
        }
      };
      
      reader.onerror = (fileErr) => {
        console.error("音声分析 - ファイル読み込みエラー:", fileErr);
        setError("ファイル読み込みエラー");
      };
      
      // 新しい録音チャンクのために配列をクリア
      setAudioChunks([]);
      
    } catch (err) {
      console.error("Error processing audio chunks:", err);
      setError("音声処理中にエラーが発生しました。");
    }
  };

  // 感情のカラーマッピング
  const getEmotionColor = (emotion: string): string => {
    const emotionColors: { [key: string]: string } = {
      Joy: "bg-yellow-500",
      Sadness: "bg-blue-500",
      Anger: "bg-red-500",
      Fear: "bg-purple-500",
      Surprise: "bg-green-500",
      Disgust: "bg-orange-500",
      Happiness: "bg-yellow-500",
      "Low Energy": "bg-blue-300",
      "High Energy": "bg-orange-500",
      Calm: "bg-teal-500",
      Excited: "bg-pink-500"
    };
    
    return emotionColors[emotion] || "bg-gray-500";
  };

  return (
    <div className="space-y-6">
      <div className="space-y-4">
        <div className="bg-gray-100 p-6 rounded-lg flex flex-col items-center justify-center">
          <div className="w-32 h-32 rounded-full bg-gray-200 flex items-center justify-center mb-4">
            <div className={`w-24 h-24 rounded-full ${isRecording ? 'bg-red-500 animate-pulse' : 'bg-gray-300'} flex items-center justify-center`}>
              <span className="text-white text-3xl">{isRecording ? '録音中' : '停止'}</span>
            </div>
          </div>
          
          {isRecording && (
            <div className="w-full mb-4">
              <p className="text-center mb-2">録音時間: {recordingTime}秒</p>
              <Progress value={(recordingTime % 3) * 33.3} className="h-2" />
              <p className="text-xs text-center mt-1">3秒ごとに音声が分析されます</p>
            </div>
          )}
          
          <div className="flex space-x-2 mt-2">
            {!isConnected ? (
              <Button onClick={connectWebSocket} disabled={isLoading}>
                {isLoading ? "接続中..." : "WebSocket接続"}
              </Button>
            ) : (
              <>
                {!isRecording ? (
                  <Button onClick={startRecording}>録音開始</Button>
                ) : (
                  <Button onClick={stopRecording} variant="destructive">録音停止</Button>
                )}
                <Button onClick={disconnectWebSocket} variant="outline">WebSocket切断</Button>
              </>
            )}
          </div>
        </div>
        {error && <p className="text-red-500 text-sm">{error}</p>}
      </div>

      <Separator />

      <div className="space-y-4">
        <h3 className="text-lg font-medium">音声感情分析結果</h3>
        {emotionResults.length > 0 ? (
          <div className="space-y-4">
            {emotionResults.map((result, index) => (
              <Card key={index} className="p-4">
                <p className="font-medium mb-2">タイムスタンプ: {result.timestamp_ms}ms</p>
                <div className="grid grid-cols-2 gap-2 sm:grid-cols-3 md:grid-cols-4">
                  {result.emotions
                    .sort((a, b) => b.score - a.score)
                    .slice(0, 8)
                    .map((emotion, emoIndex) => (
                      <div 
                        key={emoIndex} 
                        className={`${getEmotionColor(emotion.name)} p-2 rounded-md text-white flex flex-col`}
                      >
                        <span className="text-sm font-medium">{emotion.name}</span>
                        <span className="text-xs">{(emotion.score * 100).toFixed(1)}%</span>
                      </div>
                    ))}
                </div>
              </Card>
            ))}
          </div>
        ) : (
          <p className="text-gray-500">
            {isRecording ? "音声を分析中..." : "WebSocketに接続して録音を開始してください。"}
          </p>
        )}
      </div>
    </div>
  );
} 