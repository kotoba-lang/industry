'use client';

import { useState, useEffect, useRef } from 'react';
import { HumeRealtimeEmotionService, ConnectionState } from '@/lib/client/hume-realtime';
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
  const [connectionStatus, setConnectionStatus] = useState<string>(ConnectionState.CLOSED);
  const [debugInfo, setDebugInfo] = useState<string>('');
  
  // Humeサービスの初期化
  useEffect(() => {
    const apiKey = process.env.NEXT_PUBLIC_HUME_API_KEY;
    
    if (!apiKey) {
      setError('Hume API キーが設定されていません。.env.local ファイルに NEXT_PUBLIC_HUME_API_KEY を追加してください。');
      console.warn('Missing Hume API key in environment variables');
      setDebugInfo('エラー: Hume API キーが環境変数に設定されていません');
      return;
    }
    
    // Add more detailed API key validation
    if (apiKey.length < 20) {
      setError(`Hume API キーが短すぎます (${apiKey.length} 文字)。正しいAPIキーを設定してください。`);
      console.warn(`API key is too short: ${apiKey.length} characters`);
      setDebugInfo(`エラー: API キーの長さが不十分です (${apiKey.length} 文字)`);
      return;
    }
    
    if (!apiKey.match(/^[a-zA-Z0-9_\-]+$/)) {
      setError('Hume API キーの形式が正しくありません。正しいAPIキーを設定してください。');
      console.warn('API key has invalid format');
      setDebugInfo('エラー: API キーの形式が無効です');
      return;
    }
    
    // Log the environment variables status (without revealing the actual key)
    console.log('Environment variables check:', { 
      apiKeyPresent: !!apiKey,
      apiKeyLength: apiKey.length, 
      apiKeyFormatValid: !!apiKey.match(/^[a-zA-Z0-9_\-]+$/),
      nextPublicVars: Object.keys(process.env).filter(key => key.startsWith('NEXT_PUBLIC_')).length
    });
    
    // セキュリティチェック: APIキーが一般公開されていないことを確認
    if (typeof window !== 'undefined') {
      // プロダクション環境での追加チェック
      if (window.location.hostname !== 'localhost' && window.location.hostname !== '127.0.0.1') {
        // apiKeyの値は直接チェックすべきではない（環境変数の仕組み上）
        console.log('プロダクション環境では環境変数が正しく設定されていることを確認してください');
      }
    }
    
    // WebSocketサポートチェック
    if (typeof WebSocket === 'undefined') {
      setError('このブラウザはWebSocketをサポートしていません。別のブラウザを使用するか、ブラウザを更新してください。');
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
    
    const handleError = (error: Event | Error | unknown) => {
      // Enhance error logging with more information
      if (error instanceof Error) {
        console.error('Hume API error:', { message: error.message, name: error.name, stack: error.stack });
        setError(`Hume API エラー: ${error.message || '詳細不明'}`);
      } else if (error instanceof Event) {
        console.error('Hume API WebSocket event error:', { type: error.type, target: error.target });
        setError('Hume API WebSocketとの接続中にエラーが発生しました');
      } else if (error && typeof error === 'object' && 'type' in error && error.type === 'WebSocketError') {
        // Handle custom WebSocket error object
        console.error('Hume API WebSocket custom error:', error);
        const errorObj = error as any;
        const networkStatus = errorObj.networkStatus || {};
        
        let errorMessage = 'WebSocket接続エラー';
        if (errorObj.message) {
          errorMessage += `: ${errorObj.message}`;
        }
        
        // Add network information to help debugging
        if (networkStatus.online === false) {
          errorMessage += ' - インターネット接続がオフラインです';
        }
        
        setError(errorMessage);
      } else {
        // Handle empty or unknown error object
        console.error('Hume API unknown error:', error || 'Empty error object');
        setError('Hume API との接続中に不明なエラーが発生しました。環境変数とネットワーク接続を確認してください。');
      }

      // Try to verify API key validity by checking its format
      if (apiKey && (apiKey.length < 20 || !apiKey.match(/^[a-zA-Z0-9_-]+$/))) {
        console.warn('Hume API key appears to be invalid (incorrect format)');
        setError(prevError => `${prevError || ''} API キーの形式が正しくない可能性があります。`);
      }
      
      // Update debug info
      if (humeService) {
        updateDebugInfo();
      }
    };
    
    // サービスの初期化
    try {
      const service = new HumeRealtimeEmotionService(
        apiKey,
        handleFaceData,
        handleVoiceData,
        handleError
      );
      
      setHumeService(service);
      
      // Check API key format in browser console
      if (apiKey) {
        console.log(`API key length: ${apiKey.length}, Format valid: ${apiKey.match(/^[a-zA-Z0-9_-]+$/) ? 'Yes' : 'No'}`);
      }
    } catch (err) {
      // Handle initialization errors
      if (err instanceof Error) {
        console.error('Failed to initialize Hume service:', err.message);
        setError(`Hume サービスの初期化に失敗: ${err.message}`);
      } else {
        console.error('Unknown error during Hume service initialization:', err);
        setError('Hume サービスの初期化中に不明なエラーが発生しました');
      }
    }
    
    // クリーンアップ
    return () => {
      if (humeService) {
        humeService.closeConnection();
      }
    };
  }, []);
  
  // Debug info updater
  const updateDebugInfo = () => {
    if (!humeService) return;
    
    const state = humeService.getConnectionState();
    setConnectionStatus(state);
    
    // Get more detailed debugging information
    const lastError = humeService.getLastError();
    let errorDetails = 'なし';
    
    if (lastError) {
      if (lastError instanceof Error) {
        errorDetails = lastError.message;
      } else if (lastError instanceof Event) {
        errorDetails = `Event type: ${lastError.type}`;
      } else {
        errorDetails = JSON.stringify(lastError);
      }
    }
    
    const info = `
      接続状態: ${state}
      WebSocket: ${humeService.isConnected() ? '接続済み' : '未接続'}
      認証: ${humeService.isAuthenticated() ? '完了' : '未完了'}
      直近のエラー: ${errorDetails}
    `;
    
    setDebugInfo(info);
  };
  
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
        // Clear previous errors
        setError(null);
        
        console.log('Starting WebSocket connection to Hume API...');
        try {
          await humeService.initWebSocket(['face']);
          console.log('WebSocket connection initialized successfully');
        } catch (wsError) {
          console.error('WebSocket initialization error:', wsError);
          setError(`WebSocket接続エラー: ${wsError instanceof Error ? wsError.message : '詳細不明'}`);
        }
        
        // Update connection status after init
        updateDebugInfo();
        
        // Setup a periodic status check
        const checkInterval = setInterval(() => {
          if (humeService) {
            updateDebugInfo();
          } else {
            clearInterval(checkInterval);
          }
        }, 5000); // Check every 5 seconds
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
      updateDebugInfo();
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
          try {
            await humeService.sendImageData(blob);
          } catch (err) {
            console.error('Hume API へのデータ送信中のエラー:', err);
          }
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
        
        {/* API接続を手動で更新するためのボタンを追加 */}
        {humeService && (
          <>
            <button
              onClick={() => {
                if (humeService) {
                  humeService.closeConnection();
                  humeService.initWebSocket(['face'])
                    .then(() => {
                      console.log('WebSocket reconnection successful');
                      updateDebugInfo();
                    })
                    .catch(err => {
                      console.error('WebSocket reconnection failed:', err);
                      setError(`WebSocket再接続エラー: ${err instanceof Error ? err.message : '詳細不明'}`);
                      updateDebugInfo();
                    });
                }
              }}
              className="px-4 py-2 bg-gray-600 text-white rounded-md hover:bg-gray-700"
            >
              API接続を再試行
            </button>
            <button
              onClick={() => {
                // 詳細な接続診断情報を表示
                if (humeService) {
                  try {
                    const websocketURL = 'wss://api.hume.ai/v0/stream/models';
                    const testSocket = new WebSocket(websocketURL);
                    
                    testSocket.onopen = () => {
                      setDebugInfo(prev => prev + '\n\n接続テスト: WebSocketへの接続に成功しました');
                      testSocket.close();
                    };
                    
                    testSocket.onerror = (e) => {
                      setDebugInfo(prev => prev + '\n\n接続テスト: WebSocketへの接続に失敗しました');
                    };
                    
                    updateDebugInfo();
                  } catch (err) {
                    console.error('WebSocket test failed:', err);
                    setDebugInfo(prev => prev + `\n\n接続テスト: エラー ${err instanceof Error ? err.message : 'Unknown'}`);
                  }
                }
              }}
              className="px-4 py-2 bg-yellow-600 text-white rounded-md hover:bg-yellow-700"
            >
              接続診断
            </button>
          </>
        )}
      </div>
      
      {error && (
        <div className="mt-4 p-3 bg-red-100 text-red-700 rounded-md">
          {error}
        </div>
      )}
      
      {/* 接続ステータスを表示 */}
      <div className="mt-4 w-full max-w-2xl px-2 sm:px-4">
        <div className="p-3 bg-gray-100 text-gray-800 rounded-md">
          <h3 className="font-medium mb-2">接続状態: {connectionStatus}</h3>
          {debugInfo && (
            <pre className="whitespace-pre-wrap text-xs overflow-auto max-h-32">
              {debugInfo}
            </pre>
          )}
        </div>
      </div>
      
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