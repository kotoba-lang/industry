"use client";

import { useState, useEffect, useRef } from "react";
import { Textarea } from "@/components/ui/textarea";
import { Button } from "@/components/ui/button";
import { Card } from "@/components/ui/card";
import { Separator } from "@/components/ui/separator";
import { getHumeWebSocketUrl, formatHumeError } from "../utils/humeUtils";

type Emotion = {
  name: string;
  score: number;
};

type EmotionResult = {
  text: string;
  position: { begin: number; end: number };
  emotions: Emotion[];
};

export default function TextEmotionAnalysis() {
  const [text, setText] = useState<string>("");
  const [emotionResults, setEmotionResults] = useState<EmotionResult[]>([]);
  const [isConnected, setIsConnected] = useState<boolean>(false);
  const [isLoading, setIsLoading] = useState<boolean>(false);
  const [error, setError] = useState<string | null>(null);
  const socketRef = useRef<WebSocket | null>(null);

  useEffect(() => {
    return () => {
      // コンポーネントがアンマウントされたときにソケット接続を閉じる
      if (socketRef.current) {
        socketRef.current.close();
      }
    };
  }, []);

  const connectWebSocket = () => {
    setIsLoading(true);
    setError(null);

    try {
      // デバッグ用にAPIキーを確認
      const wsUrl = getHumeWebSocketUrl();
      console.log("接続URL:", wsUrl);

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
          console.log("受信データ:", data);
          if (data.language && data.language.predictions) {
            setEmotionResults(data.language.predictions);
          } else if (data.error) {
            // エラーレスポンスの処理
            console.error("API Error:", data.error);
            setError(`API エラー: ${formatHumeError(data.error)}`);
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
      setError("WebSocket作成エラー: " + formatHumeError(err));
      setIsLoading(false);
    }
  };

  const disconnectWebSocket = () => {
    if (socketRef.current) {
      socketRef.current.close();
      setIsConnected(false);
    }
  };

  const analyzeSentiment = () => {
    if (!isConnected || !socketRef.current) {
      connectWebSocket();
      return;
    }

    if (text.trim() === "") {
      setError("テキストを入力してください。");
      return;
    }

    setError(null);
    setIsLoading(true);

    // JSONメッセージの作成
    const message = {
      models: {
        language: {}
      },
      raw_text: true,
      data: text
    };

    try {
      // デバッグのためメッセージを表示
      console.log("送信メッセージ:", message);
      
      // WebSocketでメッセージを送信
      socketRef.current.send(JSON.stringify(message));
      setIsLoading(false);
    } catch (err) {
      console.error("メッセージ送信エラー:", err);
      setError("メッセージ送信エラー: " + (err instanceof Error ? err.message : String(err)));
      setIsLoading(false);
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
      Love: "bg-pink-500",
      Admiration: "bg-indigo-500",
      "Aesthetic Appreciation": "bg-teal-500",
      Amusement: "bg-lime-500"
    };
    
    return emotionColors[emotion] || "bg-gray-500";
  };

  return (
    <div className="space-y-6">
      <div className="space-y-2">
        <Textarea
          placeholder="ここにテキストを入力してください..."
          value={text}
          onChange={(e) => setText(e.target.value)}
          className="min-h-32"
        />
        <div className="flex space-x-2">
          {!isConnected ? (
            <Button onClick={connectWebSocket} disabled={isLoading}>
              {isLoading ? "接続中..." : "WebSocket接続"}
            </Button>
          ) : (
            <Button onClick={disconnectWebSocket} variant="destructive">
              WebSocket切断
            </Button>
          )}
          <Button onClick={analyzeSentiment} disabled={isLoading || (!isConnected && text.trim() === "")}>
            感情分析
          </Button>
        </div>
        {error && <p className="text-red-500 text-sm">{error}</p>}
      </div>

      <Separator />

      <div className="space-y-4">
        <h3 className="text-lg font-medium">分析結果</h3>
        {emotionResults.length > 0 ? (
          <div className="space-y-4">
            {emotionResults.map((result, index) => (
              <Card key={index} className="p-4">
                <p className="font-medium mb-2">テキスト: {result.text}</p>
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
            {isConnected ? "テキストを入力して「感情分析」ボタンをクリックしてください。" : "WebSocketに接続してください。"}
          </p>
        )}
      </div>
    </div>
  );
} 