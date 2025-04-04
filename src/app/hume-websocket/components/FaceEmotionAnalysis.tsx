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

  const connectWebSocket = () => {
    setIsLoading(true);
    setError(null);

    // デバッグ用にAPIキーを確認
    const apiKey = process.env.NEXT_PUBLIC_HUME_API_KEY;
    console.log("APIキー存在チェック:", apiKey ? "キーあり" : "キーなし");
    
    // 接続URLを作成して確認
    const wsUrl = `wss://api.hume.ai/v0/expression-measurement/ws?apiKey=${apiKey}`;
    console.log("顔分析 - 接続URL:", wsUrl);

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

    // JSONメッセージの作成
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
            <Button onClick={connectWebSocket} disabled={isLoading}>
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
        {error && <p className="text-red-500 text-sm">{error}</p>}
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