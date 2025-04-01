"use client";

import { useCallback, useEffect, useRef, useState } from 'react';
import { HumeFaceResponse, HumeRealtimeEmotionService } from '@/lib/services/hume-service';

export interface WebcamComponentProps {
  apiKey: string;
  onFaceData?: (data: HumeFaceResponse) => void;
  isActive?: boolean;
  width?: number;
  height?: number;
  captureInterval?: number; // ミリ秒単位のキャプチャ間隔
  className?: string;
}

export default function WebcamComponent({
  apiKey,
  onFaceData,
  isActive = true,
  width = 320,
  height = 240,
  captureInterval = 1000, // デフォルトは1秒ごと
  className = '',
}: WebcamComponentProps) {
  const videoRef = useRef<HTMLVideoElement>(null);
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const [isWebcamReady, setIsWebcamReady] = useState(false);
  const [dominantEmotion, setDominantEmotion] = useState<string | null>(null);
  const [emotionScores, setEmotionScores] = useState<{ name: string; score: number }[]>([]);
  const [error, setError] = useState<string | null>(null);

  // Humeサービスの参照
  const humeServiceRef = useRef<HumeRealtimeEmotionService | null>(null);
  const captureTimerRef = useRef<NodeJS.Timeout | null>(null);

  // 顔データを処理するコールバック
  const handleFaceData = useCallback((data: HumeFaceResponse) => {
    if (data.emotions && data.emotions.length > 0) {
      // 感情スコアをソートして最も高いものを取得
      const sortedEmotions = [...data.emotions].sort((a, b) => b.score - a.score);
      const topEmotion = sortedEmotions[0];
      
      setDominantEmotion(`${topEmotion.name}: ${(topEmotion.score * 100).toFixed(2)}%`);
      setEmotionScores(sortedEmotions.slice(0, 5)); // 上位5つの感情を保存
      
      // 親コンポーネントにデータを渡す
      if (onFaceData) {
        onFaceData(data);
      }
    }
  }, [onFaceData]);

  // 音声データを処理するコールバック（必要ない場合は空関数）
  const handleVoiceData = useCallback(() => {
    // 音声データは直接処理しないため、空関数
  }, []);

  // エラーハンドラ
  const handleError = useCallback((error: Event) => {
    console.error('WebSocket Error:', error);
    setError('感情認識サービスに接続できませんでした。');
  }, []);

  // Webカメラを初期化
  useEffect(() => {
    if (!isActive) return;

    let stream: MediaStream | null = null;

    const initWebcam = async () => {
      try {
        // カメラアクセスのリクエスト
        stream = await navigator.mediaDevices.getUserMedia({
          video: {
            width: { ideal: width },
            height: { ideal: height },
            facingMode: 'user'
          },
          audio: false
        });

        // videoタグにストリームを設定
        if (videoRef.current) {
          videoRef.current.srcObject = stream;
          setIsWebcamReady(true);
          setError(null);
        }

      } catch (err) {
        console.error('Error accessing webcam:', err);
        setError('カメラへのアクセスに失敗しました。');
      }
    };

    initWebcam();

    // クリーンアップ関数
    return () => {
      if (stream) {
        stream.getTracks().forEach(track => track.stop());
      }
      setIsWebcamReady(false);
    };
  }, [isActive, width, height]);

  // Hume感情認識サービスを初期化
  useEffect(() => {
    if (!isActive || !isWebcamReady || !apiKey) return;

    // Humeサービスを初期化
    humeServiceRef.current = new HumeRealtimeEmotionService(
      apiKey,
      handleFaceData,
      handleVoiceData,
      handleError
    );

    // WebSocketを初期化（顔認識のみ使用）
    humeServiceRef.current.initWebSocket(['face'])
      .then(() => {
        console.log('WebSocket connection established');
      })
      .catch(err => {
        console.error('Failed to initialize WebSocket:', err);
        setError('感情認識サービスの初期化に失敗しました。');
      });

    // クリーンアップ関数
    return () => {
      if (humeServiceRef.current) {
        humeServiceRef.current.closeConnection();
        humeServiceRef.current = null;
      }
      
      if (captureTimerRef.current) {
        clearInterval(captureTimerRef.current);
        captureTimerRef.current = null;
      }
    };
  }, [isActive, isWebcamReady, apiKey, handleFaceData, handleVoiceData, handleError]);

  // 定期的に画像をキャプチャして送信
  useEffect(() => {
    if (!isActive || !isWebcamReady || !humeServiceRef.current) return;

    const captureAndSend = () => {
      if (!videoRef.current || !canvasRef.current || !humeServiceRef.current) return;

      const video = videoRef.current;
      const canvas = canvasRef.current;
      const context = canvas.getContext('2d');

      if (!context) return;

      // ビデオフレームをキャンバスに描画
      canvas.width = video.videoWidth;
      canvas.height = video.videoHeight;
      context.drawImage(video, 0, 0, canvas.width, canvas.height);

      // キャンバスから画像データを取得
      canvas.toBlob(async (blob) => {
        if (blob && humeServiceRef.current) {
          try {
            // 画像データをHumeに送信
            await humeServiceRef.current.sendImageData(blob);
          } catch (err) {
            console.error('Error sending image data:', err);
          }
        }
      }, 'image/jpeg', 0.8); // JPEG品質80%
    };

    // 定期的にキャプチャと送信を実行
    captureTimerRef.current = setInterval(captureAndSend, captureInterval);

    // クリーンアップ関数
    return () => {
      if (captureTimerRef.current) {
        clearInterval(captureTimerRef.current);
        captureTimerRef.current = null;
      }
    };
  }, [isActive, isWebcamReady, captureInterval]);

  return (
    <div className={`relative ${className}`}>
      {error && (
        <div className="absolute inset-0 flex items-center justify-center bg-red-100 bg-opacity-80 rounded-md z-10">
          <p className="text-red-600 p-2">{error}</p>
        </div>
      )}
      
      <div className="relative">
        {/* ビデオ表示用 */}
        <video
          ref={videoRef}
          autoPlay
          playsInline
          muted
          className="rounded-md w-full max-w-full"
          style={{ maxHeight: height + 'px' }}
        />
        
        {/* キャンバス（画像キャプチャ用、非表示） */}
        <canvas
          ref={canvasRef}
          className="hidden"
          width={width}
          height={height}
        />
        
        {/* 感情表示オーバーレイ */}
        {dominantEmotion && (
          <div className="absolute top-0 left-0 right-0 bg-black bg-opacity-50 text-white p-2 rounded-t-md">
            <p className="text-sm font-medium">{dominantEmotion}</p>
          </div>
        )}
      </div>
      
      {/* 感情スコア表示 */}
      {emotionScores.length > 0 && (
        <div className="mt-2 p-2 bg-gray-100 dark:bg-gray-800 rounded-md">
          <h4 className="text-sm font-medium mb-1">感情分析:</h4>
          <ul className="text-xs space-y-1">
            {emotionScores.map((emotion, index) => (
              <li key={index} className="flex justify-between">
                <span>{emotion.name}</span>
                <span>{(emotion.score * 100).toFixed(2)}%</span>
              </li>
            ))}
          </ul>
        </div>
      )}
    </div>
  );
} 