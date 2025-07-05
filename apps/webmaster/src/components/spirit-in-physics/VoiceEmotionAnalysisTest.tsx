'use client';

import { useState, useRef, useEffect } from 'react';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { analyzeVoice } from '@/lib/actions/hume-service';
import { HumeVoiceEmotion } from '@/lib/actions/hume-service';
import { AlertCircle, Clock } from 'lucide-react';

interface VoiceEmotionAnalysisTestProps {
  apiKey?: string;
}

export default function VoiceEmotionAnalysisTest({ 
  apiKey = process.env.NEXT_PUBLIC_HUME_API_KEY || '',
}: VoiceEmotionAnalysisTestProps) {
  const [isRecording, setIsRecording] = useState(false);
  const [emotions, setEmotions] = useState<HumeVoiceEmotion[]>([]);
  const [isProcessing, setIsProcessing] = useState(false);
  const [processingTime, setProcessingTime] = useState(0);
  const [error, setError] = useState<string | null>(null);
  const [recordingTime, setRecordingTime] = useState(0);
  
  const mediaRecorderRef = useRef<MediaRecorder | null>(null);
  const audioChunksRef = useRef<Blob[]>([]);
  const timerIdRef = useRef<NodeJS.Timeout | null>(null);
  const processingTimerRef = useRef<NodeJS.Timeout | null>(null);
  
  // Cleanup function
  useEffect(() => {
    return () => {
      if (timerIdRef.current) {
        clearInterval(timerIdRef.current);
      }
      if (processingTimerRef.current) {
        clearInterval(processingTimerRef.current);
      }
      if (mediaRecorderRef.current) {
        mediaRecorderRef.current.stream.getTracks().forEach(track => track.stop());
      }
    };
  }, []);
  
  // Function to start recording
  const startRecording = async () => {
    try {
      setError(null);
      setEmotions([]);
      audioChunksRef.current = [];
      
      // Request microphone access
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
      
      // Create media recorder
      const mediaRecorder = new MediaRecorder(stream);
      mediaRecorderRef.current = mediaRecorder;
      
      // Set up event handlers
      mediaRecorder.ondataavailable = (event) => {
        if (event.data.size > 0) {
          audioChunksRef.current.push(event.data);
        }
      };
      
      mediaRecorder.onstop = async () => {
        // Process the recorded audio
        await processRecording();
      };
      
      // Start recording
      mediaRecorder.start(1000); // Capture data every second
      setIsRecording(true);
      
      // Set up timer
      setRecordingTime(0);
      timerIdRef.current = setInterval(() => {
        setRecordingTime(prev => prev + 1);
      }, 1000);
      
    } catch (err) {
      console.error('Error starting recording:', err);
      setError('マイクへのアクセスが許可されていません。');
    }
  };
  
  // Function to stop recording
  const stopRecording = () => {
    if (mediaRecorderRef.current && isRecording) {
      mediaRecorderRef.current.stop();
      mediaRecorderRef.current.stream.getTracks().forEach(track => track.stop());
      setIsRecording(false);
      
      if (timerIdRef.current) {
        clearInterval(timerIdRef.current);
        timerIdRef.current = null;
      }
    }
  };
  
  // Function to process the recording
  const processRecording = async () => {
    if (audioChunksRef.current.length === 0) return;
    
    setIsProcessing(true);
    setProcessingTime(0);
    
    // Start a timer to show processing time
    processingTimerRef.current = setInterval(() => {
      setProcessingTime(prev => prev + 1);
    }, 1000);
    
    try {
      // Create audio blob
      const audioBlob = new Blob(audioChunksRef.current, { type: 'audio/wav' });
      
      // Send to Hume AI for analysis
      const response = await analyzeVoice(audioBlob, apiKey);
      
      // Update state with emotions
      setEmotions(response.emotions);
    } catch (err: any) {
      console.error('Error processing voice recording:', err);
      const errorMessage = err.message || '音声分析中にエラーが発生しました。';
      
      // Handle timeout specifically
      if (errorMessage.includes('did not complete within')) {
        setError('音声分析がタイムアウトしました。もう一度試すか、より短い録音で試してください。');
      } else {
        setError(`音声分析中にエラーが発生しました: ${errorMessage}`);
      }
    } finally {
      setIsProcessing(false);
      if (processingTimerRef.current) {
        clearInterval(processingTimerRef.current);
        processingTimerRef.current = null;
      }
    }
  };
  
  // Format time as MM:SS
  const formatTime = (seconds: number): string => {
    const mins = Math.floor(seconds / 60);
    const secs = seconds % 60;
    return `${mins.toString().padStart(2, '0')}:${secs.toString().padStart(2, '0')}`;
  };
  
  // Get the top 5 emotions
  const topEmotions = emotions.slice().sort((a, b) => b.score - a.score).slice(0, 5);
  
  return (
    <div className="space-y-6">
      <Card>
        <CardHeader>
          <CardTitle>音声感情分析テスト</CardTitle>
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
                  disabled={isProcessing}
                  className="w-32"
                >
                  録音開始
                </Button>
              ) : (
                <Button 
                  onClick={stopRecording} 
                  variant="destructive"
                  className="w-32"
                >
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
            <CardTitle>検出された感情</CardTitle>
          </CardHeader>
          <CardContent>
            <div className="space-y-4">
              {topEmotions.map(emotion => (
                <div key={emotion.name} className="flex items-center">
                  <div className="w-32">{emotion.name}</div>
                  <div className="flex-1">
                    <div className="bg-gray-200 rounded-full h-4">
                      <div
                        className="bg-blue-600 h-4 rounded-full"
                        style={{ width: `${emotion.score * 100}%` }}
                      ></div>
                    </div>
                  </div>
                  <div className="w-16 text-right">{(emotion.score * 100).toFixed(1)}%</div>
                </div>
              ))}
            </div>
          </CardContent>
        </Card>
      )}
      
      {isProcessing && (
        <Card>
          <CardContent className="p-6">
            <div className="flex flex-col items-center">
              <div className="animate-spin rounded-full h-8 w-8 border-b-2 border-gray-900 mb-4"></div>
              <p className="text-lg font-medium mb-2">音声を分析中...</p>
              <div className="flex items-center text-gray-500">
                <Clock className="w-4 h-4 mr-2" />
                <span>処理時間: {formatTime(processingTime)}</span>
              </div>
              {processingTime > 30 && (
                <p className="mt-4 text-amber-600 text-sm text-center">
                  音声の分析には時間がかかることがあります。<br />
                  しばらくお待ちください（最大2分程度）。
                </p>
              )}
            </div>
          </CardContent>
        </Card>
      )}
    </div>
  );
} 