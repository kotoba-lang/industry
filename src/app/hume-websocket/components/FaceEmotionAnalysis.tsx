"use client";

import { useState, useEffect, useRef } from "react";
import { Button } from "@/components/ui/button";
import { Card } from "@/components/ui/card";
import { Separator } from "@/components/ui/separator";
import Webcam from "react-webcam";

type Emotion = {
  name: string;
  score: number;
};

type FaceResult = {
  bbox: {
    x: number;
    y: number;
    width: number;
    height: number;
  };
  emotions: Emotion[];
  face_id: string;
};

export default function FaceEmotionAnalysis() {
  const [isConnected, setIsConnected] = useState<boolean>(false);
  const [isLoading, setIsLoading] = useState<boolean>(false);
  const [error, setError] = useState<string | null>(null);
  const [isCameraActive, setIsCameraActive] = useState<boolean>(false);
  const [emotionResults, setEmotionResults] = useState<FaceResult[]>([]);
  const [isCapturing, setIsCapturing] = useState<boolean>(false);
  const [captureInterval, setCaptureInterval] = useState<NodeJS.Timeout | null>(null);
  const [connectionAttempts, setConnectionAttempts] = useState<number>(0);
  
  const socketRef = useRef<WebSocket | null>(null);
  const webcamRef = useRef<Webcam | null>(null);

  useEffect(() => {
    return () => {
      // コンポーネントがアンマウントされたときにソケット接続とインターバルを閉じる
      if (socketRef.current) {
        socketRef.current.close();
      }
      if (captureInterval) {
        clearInterval(captureInterval);
      }
    };
  }, [captureInterval]);

  const connectWebSocket = (retry = false) => {
    if (retry) {
      // 最大3回まで再試行
      if (connectionAttempts >= 3) {
        setError("WebSocket接続の再試行回数が上限に達しました。後ほど再度お試しください。");
        setIsLoading(false);
        return;
      }
      setConnectionAttempts(prev => prev + 1);
    } else {
      setConnectionAttempts(1);
    }
    
    setIsLoading(true);
    setError(null);

    // APIキーの確認
    const apiKey = process.env.NEXT_PUBLIC_HUME_API_KEY;
    if (!apiKey) {
      setError("APIキーが設定されていません。.env.localファイルにNEXT_PUBLIC_HUME_API_KEYを正しく設定してください。");
      setIsLoading(false);
      return;
    }
    
    console.log("APIキー存在チェック:", apiKey ? "キーあり" : "キーなし");
    
    // 接続URLを修正 - Hume APIの最新の仕様に合わせる
    const wsUrl = `wss://api.hume.ai/v0/stream/models?apiKey=${encodeURIComponent(apiKey)}`;
    console.log("顔分析 - 接続URL構築:", wsUrl.substring(0, wsUrl.indexOf('?') + 8) + "***");

    try {
      // WebSocket接続を作成する前に既存の接続をクリア
      if (socketRef.current) {
        socketRef.current.close();
        socketRef.current = null;
      }
      
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
          console.log("顔分析 - 受信データ:", data);
          if (data.face && data.face.predictions) {
            setEmotionResults(data.face.predictions);
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
        // WebSocket エラーオブジェクトには詳細が含まれていないことがあるためエラーハンドリングを改善
        console.error("WebSocket error:", err);
        setError("WebSocket接続エラーが発生しました。APIキーが正しいか確認してください。");
        setIsLoading(false);
        setIsConnected(false);
        
        // 再接続を試みるかユーザーに通知
        if (socketRef.current) {
          socketRef.current.close();
          socketRef.current = null;
        }
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
    stopCapturing();
  };

  const startCamera = () => {
    setIsCameraActive(true);
  };

  const stopCamera = () => {
    setIsCameraActive(false);
    stopCapturing();
  };

  const captureAndAnalyze = () => {
    if (!webcamRef.current || !socketRef.current) return;

    const imageSrc = webcamRef.current.getScreenshot();
    if (!imageSrc) return;

    // Base64形式の画像データを抽出 (data:image/jpeg;base64,を削除)
    const base64Image = imageSrc.split(',')[1];

    // JSONメッセージの作成 - シンプルな形式に変更（type, configフィールドを削除）
    const message = {
      models: {
        face: {}
      },
      data: base64Image
    };

    try {
      // デバッグ情報
      console.log("顔分析 - 送信メッセージ構造:", {
        ...message,
        data: base64Image ? `${base64Image.substring(0, 20)}...（省略）` : "なし"
      });
      
      // WebSocketでメッセージを送信
      socketRef.current.send(JSON.stringify(message));
    } catch (err) {
      console.error("顔分析 - メッセージ送信エラー:", err);
      setError("メッセージ送信エラー: " + (err instanceof Error ? err.message : String(err)));
    }
  };

  const startCapturing = () => {
    if (!isConnected) {
      connectWebSocket();
    }

    setIsCapturing(true);
    // 1.5秒ごとに画像をキャプチャして分析
    const interval = setInterval(captureAndAnalyze, 1500);
    setCaptureInterval(interval);
  };

  const stopCapturing = () => {
    if (captureInterval) {
      clearInterval(captureInterval);
      setCaptureInterval(null);
    }
    setIsCapturing(false);
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
      Neutral: "bg-gray-500",
      Contempt: "bg-orange-700",
      Confusion: "bg-purple-300"
    };
    
    return emotionColors[emotion] || "bg-gray-500";
  };

  // リトライボタン用のハンドラー
  const handleRetryConnection = () => {
    connectWebSocket(true);
  };

  return (
    <div className="space-y-6">
      <div className="space-y-4">
        {isCameraActive ? (
          <div className="relative overflow-hidden rounded-lg">
            <Webcam
              audio={false}
              ref={webcamRef}
              screenshotFormat="image/jpeg"
              className="w-full h-auto"
              videoConstraints={{
                width: 640,
                height: 480,
                facingMode: "user"
              }}
            />
          </div>
        ) : (
          <div className="bg-gray-200 aspect-video rounded-lg flex items-center justify-center">
            <p className="text-gray-500">カメラが未起動です</p>
          </div>
        )}

        <div className="flex flex-wrap gap-2">
          {!isCameraActive ? (
            <Button onClick={startCamera}>
              カメラを起動
            </Button>
          ) : (
            <Button onClick={stopCamera} variant="destructive">
              カメラを停止
            </Button>
          )}

          {isCameraActive && !isConnected && (
            <Button onClick={() => connectWebSocket()} disabled={isLoading}>
              {isLoading ? "接続中..." : "WebSocket接続"}
            </Button>
          )}

          {isCameraActive && isConnected && !isCapturing && (
            <Button onClick={startCapturing}>
              感情分析を開始
            </Button>
          )}

          {isCapturing && (
            <Button onClick={stopCapturing} variant="secondary">
              感情分析を停止
            </Button>
          )}

          {isConnected && (
            <Button onClick={disconnectWebSocket} variant="destructive">
              WebSocket切断
            </Button>
          )}
        </div>
        {error && (
          <div className="bg-red-50 border border-red-200 rounded-md p-4 my-4">
            <div className="flex">
              <div className="flex-shrink-0">
                <svg className="h-5 w-5 text-red-400" viewBox="0 0 20 20" fill="currentColor">
                  <path fillRule="evenodd" d="M10 18a8 8 0 100-16 8 8 0 000 16zM8.707 7.293a1 1 0 00-1.414 1.414L8.586 10l-1.293 1.293a1 1 0 101.414 1.414L10 11.414l1.293 1.293a1 1 0 001.414-1.414L11.414 10l1.293-1.293a1 1 0 00-1.414-1.414L10 8.586 8.707 7.293z" clipRule="evenodd" />
                </svg>
              </div>
              <div className="ml-3">
                <p className="text-sm text-red-700">{error}</p>
                {connectionAttempts > 0 && connectionAttempts < 3 && (
                  <div className="mt-2">
                    <Button 
                      onClick={() => handleRetryConnection()} 
                      size="sm" 
                      variant="outline" 
                      className="text-red-700 bg-red-50 hover:bg-red-100"
                    >
                      接続を再試行
                    </Button>
                  </div>
                )}
              </div>
            </div>
          </div>
        )}
      </div>

      <Separator />

      <div className="space-y-4">
        <h3 className="text-lg font-medium">表情分析結果</h3>
        {emotionResults.length > 0 ? (
          <div className="space-y-4">
            {emotionResults.map((result, index) => (
              <Card key={index} className="p-4">
                <p className="font-medium mb-2">顔 ID: {result.face_id}</p>
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
            {isCapturing ? "分析結果を待っています..." : "カメラを起動して感情分析を開始してください。"}
          </p>
        )}
      </div>
    </div>
  );
} 