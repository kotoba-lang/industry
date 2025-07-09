'use client';

import { useState, useEffect, useRef } from 'react';
import { HumeFaceResponse } from '@/lib/actions/hume-service';
import { analyzeFaceEmotion } from '@/lib/actions/hume-rest-service';

interface EmotionScore {
  name: string;
  score: number;
}

export default function FaceEmotionAnalysisRest() {
  const videoRef = useRef<HTMLVideoElement>(null);
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const [emotions, setEmotions] = useState<EmotionScore[]>([]);
  const [cameraActive, setCameraActive] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [isAnalyzing, setIsAnalyzing] = useState(false);
  const [apiKey, setApiKey] = useState<string>('');
  const [debugInfo, setDebugInfo] = useState<string>('');
  const [lastApiCallTime, setLastApiCallTime] = useState<number>(0);
  const [captureInterval, setCaptureInterval] = useState<number>(1000); // 1 second default

  // API キーの取得・検証
  useEffect(() => {
    const key = process.env.NEXT_PUBLIC_HUME_API_KEY;
    
    if (!key) {
      setError('Hume API キーが設定されていません。.env.local ファイルに NEXT_PUBLIC_HUME_API_KEY を追加してください。');
      console.warn('Missing Hume API key in environment variables');
      setDebugInfo('エラー: Hume API キーが環境変数に設定されていません');
      return;
    }
    
    // Add more detailed API key validation
    if (key.length < 20) {
      setError(`Hume API キーが短すぎます (${key.length} 文字)。正しいAPIキーを設定してください。`);
      console.warn(`API key is too short: ${key.length} characters`);
      setDebugInfo(`エラー: API キーの長さが不十分です (${key.length} 文字)`);
      return;
    }
    
    if (!key.match(/^[a-zA-Z0-9_\-]+$/)) {
      setError('Hume API キーの形式が正しくありません。正しいAPIキーを設定してください。');
      console.warn('API key has invalid format');
      setDebugInfo('エラー: API キーの形式が無効です');
      return;
    }
    
    // Log the environment variables status (without revealing the actual key)
    console.log('Environment variables check:', { 
      apiKeyPresent: !!key,
      apiKeyLength: key.length, 
      apiKeyFormatValid: !!key.match(/^[a-zA-Z0-9_\-]+$/),
      nextPublicVars: Object.keys(process.env).filter(key => key.startsWith('NEXT_PUBLIC_')).length
    });
    
    setApiKey(key);
    setDebugInfo('API キーの検証が完了しました');
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
      setError(null);
      setDebugInfo('カメラが起動しました。画像分析を開始します。');
      
      // Start capturing frames after camera is ready
      setTimeout(() => {
        captureAndAnalyze();
      }, 1000);
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
    setEmotions([]);
    setDebugInfo('カメラを停止しました');
  };
  
  // ビデオフレームの取得と分析
  const captureAndAnalyze = async () => {
    if (!cameraActive || !videoRef.current || !canvasRef.current || isAnalyzing || !apiKey) {
      return;
    }
    
    const now = Date.now();
    const timeSinceLastCall = now - lastApiCallTime;
    
    // Rate limiting to avoid too many API calls
    if (timeSinceLastCall < captureInterval) {
      setTimeout(captureAndAnalyze, captureInterval - timeSinceLastCall);
      return;
    }
    
    const video = videoRef.current;
    const canvas = canvasRef.current;
    const context = canvas.getContext('2d');
    
    if (!context || !video.videoWidth) {
      // Video might not be ready yet
      setTimeout(captureAndAnalyze, 500);
      return;
    }
    
    try {
      // Mark that we're currently analyzing to prevent overlapping calls
      setIsAnalyzing(true);
      setLastApiCallTime(now);
      
      // ビデオフレームをキャンバスに描画
      canvas.width = video.videoWidth;
      canvas.height = video.videoHeight;
      context.drawImage(video, 0, 0, canvas.width, canvas.height);
      
      // キャンバスから画像データを取得
      const imageDataUrl = canvas.toDataURL('image/jpeg', 0.8);
      
      // Update debug info
      setDebugInfo(`画像を取得しました。Hume APIに送信中... (${new Date().toLocaleTimeString()})`);
      
      // Send to Hume API
      const result = await analyzeFaceEmotion(imageDataUrl, apiKey);
      
      // Handle response
      if ('error' in result) {
        console.error('Hume API error:', result.error);
        setError(`Hume API エラー: ${result.error}`);
        setDebugInfo(prev => `${prev}\nAPIエラー: ${result.error}`);
      } else {
        // Clear any previous errors
        setError(null);
        
        // Update emotions if they exist in the response
        if (result.emotions) {
          setEmotions(result.emotions.sort((a, b) => b.score - a.score));
          setDebugInfo(`最終更新: ${new Date().toLocaleTimeString()}\n感情分析が完了しました`);
        } else if ((result as any).predictions && (result as any).predictions.emotions) {
          // Handle alternative response structure
          setEmotions((result as any).predictions.emotions.sort((a: any, b: any) => b.score - a.score));
          setDebugInfo(`最終更新: ${new Date().toLocaleTimeString()}\n感情分析が完了しました (新フォーマット)`);
        } else {
          setDebugInfo(prev => `${prev}\n感情データがレスポンスに含まれていません\n受信データ: ${JSON.stringify(result).substring(0, 100)}...`);
        }
      }
    } catch (err) {
      console.error('画像処理エラー:', err);
      setError(`画像処理エラー: ${err instanceof Error ? err.message : '不明なエラー'}`);
    } finally {
      setIsAnalyzing(false);
      
      // Schedule next capture if camera is still active
      if (cameraActive) {
        setTimeout(captureAndAnalyze, captureInterval);
      }
    }
  };
  
  // 色をスコアに基づいて生成
  const getColorFromScore = (score: number) => {
    const hue = 120 * (1 - score); // 0 (赤) から 120 (緑)
    return `hsl(${hue}, 100%, 40%)`;
  };
  
  // キャプチャ間隔の変更
  const handleIntervalChange = (e: React.ChangeEvent<HTMLSelectElement>) => {
    const newInterval = parseInt(e.target.value, 10);
    setCaptureInterval(newInterval);
    setDebugInfo(`キャプチャ間隔を ${newInterval}ms に変更しました`);
  };
  
  return (
    <div className="flex flex-col items-center">
      {/* API Information Alert */}
      <div className="mb-6 p-3 bg-blue-100 text-blue-800 rounded-md w-full max-w-2xl">
        <h3 className="font-bold mb-1">Hume API 情報</h3>
        <p className="text-sm">
          Hume の REST API はバッチ処理方式で、結果を取得するには複数回のリクエストが必要です。
          このデモでは、画像の送信と処理リクエストの開始のみを行います。
          実際のアプリケーションでは、ジョブの状態をポーリングして結果を取得する必要があります。
        </p>
      </div>
      
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
        
        {cameraActive && (
          <button
            onClick={captureAndAnalyze}
            disabled={isAnalyzing}
            className={`px-4 py-2 bg-green-600 text-white rounded-md hover:bg-green-700 ${
              isAnalyzing ? 'opacity-50 cursor-not-allowed' : ''
            }`}
          >
            {isAnalyzing ? '分析中...' : '今すぐ分析'}
          </button>
        )}
      </div>
      
      {/* キャプチャ間隔の選択 */}
      <div className="mt-4 flex items-center space-x-2">
        <label htmlFor="captureInterval" className="text-sm">キャプチャ間隔:</label>
        <select
          id="captureInterval"
          value={captureInterval}
          onChange={handleIntervalChange}
          className="p-2 border rounded-md"
          disabled={!cameraActive}
        >
          <option value="500">0.5秒 (高負荷)</option>
          <option value="1000">1秒</option>
          <option value="2000">2秒</option>
          <option value="5000">5秒</option>
          <option value="10000">10秒 (低負荷)</option>
        </select>
      </div>
      
      {error && (
        <div className="mt-4 p-3 bg-red-100 text-red-700 rounded-md w-full max-w-2xl">
          <h3 className="font-bold mb-1">エラー</h3>
          <p>{error}</p>
          {error.includes && error.includes('batch API') && (
            <p className="mt-2 text-sm">
              これはエラーではなく、Hume API の仕様によるものです。
              詳細はサーバーログを確認してください。
            </p>
          )}
        </div>
      )}
      
      {/* デバッグ情報 */}
      <div className="mt-4 w-full max-w-2xl px-2 sm:px-4">
        <div className="p-3 bg-gray-100 text-gray-800 rounded-md">
          <h3 className="font-medium mb-2">ステータス</h3>
          <pre className="whitespace-pre-wrap text-xs overflow-auto max-h-32">
            {debugInfo}
          </pre>
        </div>
      </div>
      
      <div className="mt-6 w-full max-w-2xl px-2 sm:px-4">
        <h2 className="text-xl font-semibold mb-4">感情分析結果</h2>
        
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
          <p className="text-gray-500">
            {cameraActive 
              ? '感情分析を実行中...' 
              : 'カメラを開始すると感情分析が表示されます'}
          </p>
        )}
      </div>
    </div>
  );
} 