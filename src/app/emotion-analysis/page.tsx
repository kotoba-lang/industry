'use client';

import { useState, useRef, useEffect } from 'react';
import { analyzeFace, analyzeVoice, HumeFaceResponse, HumeVoiceResponse } from '@/lib/services/hume-service';
import { HumeRealtimeEmotionService } from '@/lib/client/hume-realtime';

export default function EmotionAnalysisPage() {
  const [apiKey, setApiKey] = useState('your-api-key');
  const [faceResult, setFaceResult] = useState<HumeFaceResponse | null>(null);
  const [voiceResult, setVoiceResult] = useState<HumeVoiceResponse | null>(null);
  const [isLoading, setIsLoading] = useState(false);
  
  const realtimeServiceRef = useRef<HumeRealtimeEmotionService | null>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);

  // 初期化
  useEffect(() => {
    return () => {
      // コンポーネントのクリーンアップ時にWebSocket接続を閉じる
      if (realtimeServiceRef.current) {
        realtimeServiceRef.current.closeConnection();
      }
    };
  }, []);

  // リアルタイム分析の初期化
  const initRealtime = () => {
    realtimeServiceRef.current = new HumeRealtimeEmotionService(
      apiKey,
      (data) => {
        setFaceResult(data);
      },
      (data) => {
        setVoiceResult(data);
      },
      (error) => {
        console.error('WebSocket error:', error);
      }
    );

    realtimeServiceRef.current.initWebSocket()
      .catch(error => {
        console.error('Failed to initialize WebSocket:', error);
      });
  };

  // バッチ処理で画像分析
  const handleImageUpload = async (e: React.ChangeEvent<HTMLInputElement>) => {
    if (!e.target.files || e.target.files.length === 0) return;
    
    setIsLoading(true);
    const file = e.target.files[0];
    
    try {
      // Server Actionを呼び出し
      const result = await analyzeFace(file, apiKey);
      setFaceResult(result);
    } catch (error) {
      console.error('Error analyzing image:', error);
    } finally {
      setIsLoading(false);
    }
  };

  // バッチ処理で音声分析
  const handleAudioUpload = async (e: React.ChangeEvent<HTMLInputElement>) => {
    if (!e.target.files || e.target.files.length === 0) return;
    
    setIsLoading(true);
    const file = e.target.files[0];
    
    try {
      // Server Actionを呼び出し
      const result = await analyzeVoice(file, apiKey);
      setVoiceResult(result);
    } catch (error) {
      console.error('Error analyzing audio:', error);
    } finally {
      setIsLoading(false);
    }
  };

  return (
    <div className="container mx-auto p-4">
      <h1 className="text-2xl font-bold mb-4">Emotion Analysis</h1>
      
      <div className="mb-4">
        <label className="block mb-2">API Key:</label>
        <input
          type="text"
          value={apiKey}
          onChange={(e) => setApiKey(e.target.value)}
          className="border p-2 w-full"
        />
      </div>
      
      <div className="mb-4">
        <h2 className="text-xl mb-2">Batch Analysis</h2>
        <div className="flex gap-4">
          <div>
            <label className="block mb-2">Upload Image:</label>
            <input 
              type="file" 
              accept="image/*" 
              onChange={handleImageUpload}
              className="mb-2"
            />
          </div>
          <div>
            <label className="block mb-2">Upload Audio:</label>
            <input 
              type="file" 
              accept="audio/*" 
              onChange={handleAudioUpload}
              className="mb-2"
            />
          </div>
        </div>
      </div>
      
      <div className="mb-4">
        <h2 className="text-xl mb-2">Realtime Analysis</h2>
        <button 
          onClick={initRealtime}
          className="bg-blue-500 text-white p-2 rounded"
        >
          Start Realtime Analysis
        </button>
      </div>
      
      {isLoading && <p>Loading...</p>}
      
      <div className="grid grid-cols-2 gap-4">
        <div>
          <h3 className="text-lg font-semibold mb-2">Face Emotions:</h3>
          {faceResult && (
            <pre className="bg-gray-100 p-2 rounded">
              {JSON.stringify(faceResult, null, 2)}
            </pre>
          )}
        </div>
        <div>
          <h3 className="text-lg font-semibold mb-2">Voice Emotions:</h3>
          {voiceResult && (
            <pre className="bg-gray-100 p-2 rounded">
              {JSON.stringify(voiceResult, null, 2)}
            </pre>
          )}
        </div>
      </div>
    </div>
  );
} 