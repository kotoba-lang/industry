'use client';

import { useState, useEffect, useRef } from 'react';
import { HumeRealtimeEmotionService } from '@/lib/client/hume-realtime';
import { HumeFaceResponse, HumeVoiceResponse } from '@/lib/actions/hume-service';

interface EmotionScore {
  name: string;
  score: number;
}

export default function FaceEmotionAnalysis() {
  const videoRef = useRef<HTMLVideoElement>(null);
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const [emotions, setEmotions] = useState<EmotionScore[]>([]);
  const [cameraActive, setCameraActive] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [humeService, setHumeService] = useState<HumeRealtimeEmotionService | null>(null);
  
  // Humeサービスの初期化
  useEffect(() => {
    const apiKey = process.env.NEXT_PUBLIC_HUME_API_KEY;
    
    if (!apiKey) {
      setError('Hume API キーが設定されていません');
      return;
    }
    
    // 感情データを受け取るコールバック
    const handleFaceData = (data: HumeFaceResponse) => {
      if (data.emotions) {
        setEmotions(data.emotions.sort((a, b) => b.score - a.score));
      }
    };
    
    const handleVoiceData = (data: HumeVoiceResponse) => {
      // 音声感情データの処理（必要に応じて）
      console.log('Voice emotion data:', data);
    };
    
    const handleError = (error: Event) => {
      console.error('Hume API error:', error);
      setError('Hume API との接続中にエラーが発生しました');
    };
    
    // サービスの初期化
    const service = new HumeRealtimeEmotionService(
      apiKey,
      handleFaceData,
      handleVoiceData,
      handleError
    );
    
    setHumeService(service);
    
    // クリーンアップ
    return () => {
      if (service) {
        service.closeConnection();
      }
    };
  }, []);
  
  // カメラのセットアップ
  const setupCamera = async () => {
    try {
      const stream = await navigator.mediaDevices.getUserMedia({
        video: { width: 640, height: 480 },
        audio: false
      });
      
      if (videoRef.current) {
        videoRef.current.srcObject = stream;
      }
      
      setCameraActive(true);
      
      // Hume WebSocketの初期化
      if (humeService) {
        await humeService.initWebSocket(['face']);
      }
      
      // フレームの処理開始
      requestAnimationFrame(processFrame);
    } catch (err) {
      console.error('カメラへのアクセスに失敗しました:', err);
      setError('カメラへのアクセスが許可されていません');
    }
  };
  
  // カメラを停止
  const stopCamera = () => {
    if (videoRef.current && videoRef.current.srcObject) {
      const tracks = (videoRef.current.srcObject as MediaStream).getTracks();
      tracks.forEach(track => track.stop());
      videoRef.current.srcObject = null;
    }
    
    setCameraActive(false);
    
    // Humeサービスの接続を閉じる
    if (humeService) {
      humeService.closeConnection();
    }
  };
  
  // ビデオフレームの処理
  const processFrame = async () => {
    if (!cameraActive || !videoRef.current || !canvasRef.current || !humeService) {
      return;
    }
    
    const video = videoRef.current;
    const canvas = canvasRef.current;
    const context = canvas.getContext('2d');
    
    if (!context) return;
    
    // ビデオフレームをキャンバスに描画
    canvas.width = video.videoWidth;
    canvas.height = video.videoHeight;
    context.drawImage(video, 0, 0, canvas.width, canvas.height);
    
    // キャンバスから画像データを取得
    try {
      canvas.toBlob(async (blob) => {
        if (blob && humeService) {
          // 画像データをHumeサービスに送信
          await humeService.sendImageData(blob);
        }
        
        // 次のフレームを処理
        if (cameraActive) {
          setTimeout(() => requestAnimationFrame(processFrame), 200); // 200msごとに処理（5FPS）
        }
      }, 'image/jpeg', 0.8);
    } catch (err) {
      console.error('画像処理エラー:', err);
    }
  };
  
  // 色をスコアに基づいて生成
  const getColorFromScore = (score: number) => {
    const hue = 120 * (1 - score); // 0 (赤) から 120 (緑)
    return `hsl(${hue}, 100%, 40%)`;
  };
  
  return (
    <div className="flex flex-col items-center">
      <div className="relative w-full max-w-2xl px-2 sm:px-4">
        <video
          ref={videoRef}
          autoPlay
          playsInline
          muted
          className="w-full rounded-lg shadow-lg"
          style={{ display: cameraActive ? 'block' : 'none' }}
        />
        
        <canvas
          ref={canvasRef}
          className="hidden" // 非表示
        />
        
        {!cameraActive && (
          <div className="w-full h-64 flex items-center justify-center bg-gray-100 rounded-lg">
            <p className="text-gray-500">カメラが停止しています</p>
          </div>
        )}
      </div>
      
      <div className="mt-6 flex space-x-4">
        {!cameraActive ? (
          <button
            onClick={setupCamera}
            className="px-4 py-2 bg-blue-600 text-white rounded-md hover:bg-blue-700"
          >
            カメラを開始
          </button>
        ) : (
          <button
            onClick={stopCamera}
            className="px-4 py-2 bg-red-600 text-white rounded-md hover:bg-red-700"
          >
            カメラを停止
          </button>
        )}
      </div>
      
      {error && (
        <div className="mt-4 p-3 bg-red-100 text-red-700 rounded-md">
          {error}
        </div>
      )}
      
      <div className="mt-6 w-full max-w-2xl px-2 sm:px-4">
        <h2 className="text-xl font-semibold mb-4">リアルタイム感情分析</h2>
        
        {emotions.length > 0 ? (
          <div className="space-y-3">
            {emotions.slice(0, 5).map((emotion) => (
              <div key={emotion.name} className="flex flex-col">
                <div className="flex justify-between mb-1">
                  <span className="font-medium">{emotion.name}</span>
                  <span>{Math.round(emotion.score * 100)}%</span>
                </div>
                <div className="w-full bg-gray-200 rounded-full h-2.5">
                  <div
                    className="h-2.5 rounded-full"
                    style={{
                      width: `${emotion.score * 100}%`,
                      backgroundColor: getColorFromScore(emotion.score)
                    }}
                  />
                </div>
              </div>
            ))}
          </div>
        ) : (
          <p className="text-gray-500">カメラを開始すると感情分析が表示されます</p>
        )}
      </div>
    </div>
  );
} 