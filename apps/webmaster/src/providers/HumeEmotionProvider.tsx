"use client";

import React, { createContext, useContext, useState, useRef, useCallback, useEffect } from 'react';
import axios from 'axios';

// Types
export interface EmotionData {
  emotions?: Record<string, number>;
  dominantEmotion?: string;
  timestamp: number;
}

interface HumeEmotionContextType {
  isTracking: boolean;
  isInitialized: boolean;
  currentEmotion: EmotionData | null;
  emotionHistory: EmotionData[];
  error: string | null;
  enableTracking: () => Promise<void>;
  disableTracking: () => void;
  captureEmotion: () => Promise<EmotionData | null>;
  attachVideoElement: (videoElement: HTMLVideoElement) => void;
  detachVideoElement: () => void;
  clearEmotionHistory: () => void;
}

// Default context value
const defaultContextValue: HumeEmotionContextType = {
  isTracking: false,
  isInitialized: false,
  currentEmotion: null,
  emotionHistory: [],
  error: null,
  enableTracking: async () => {},
  disableTracking: () => {},
  captureEmotion: async () => null,
  attachVideoElement: () => {},
  detachVideoElement: () => {},
  clearEmotionHistory: () => {},
};

// Create context
const HumeEmotionContext = createContext<HumeEmotionContextType>(defaultContextValue);

// Provider props
interface HumeEmotionProviderProps {
  children: React.ReactNode;
  apiKey?: string;
  captureInterval?: number;
}

// Provider component
export function HumeEmotionProvider({
  children,
  apiKey = process.env.NEXT_PUBLIC_HUME_API_KEY || '',
  captureInterval = 1000, // Default is 1 second
}: HumeEmotionProviderProps) {
  const [isTracking, setIsTracking] = useState(false);
  const [isInitialized, setIsInitialized] = useState(false);
  const [currentEmotion, setCurrentEmotion] = useState<EmotionData | null>(null);
  const [emotionHistory, setEmotionHistory] = useState<EmotionData[]>([]);
  const [error, setError] = useState<string | null>(null);

  const videoRef = useRef<HTMLVideoElement | null>(null);
  const canvasRef = useRef<HTMLCanvasElement | null>(null);
  const trackingIntervalRef = useRef<NodeJS.Timeout | null>(null);

  // Create canvas when component mounts
  useEffect(() => {
    const canvas = document.createElement('canvas');
    canvasRef.current = canvas;

    return () => {
      canvasRef.current = null;
    };
  }, []);

  // Attach video element
  const attachVideoElement = useCallback((videoElement: HTMLVideoElement) => {
    videoRef.current = videoElement;
    setIsInitialized(true);
  }, []);

  // Detach video element
  const detachVideoElement = useCallback(() => {
    videoRef.current = null;
    setIsInitialized(false);
  }, []);

  // Clear emotion history
  const clearEmotionHistory = useCallback(() => {
    setEmotionHistory([]);
  }, []);

  // Capture frame from video and return as blob
  const captureFrame = useCallback(async (): Promise<Blob | null> => {
    if (!videoRef.current || !canvasRef.current) return null;

    const video = videoRef.current;
    const canvas = canvasRef.current;
    const context = canvas.getContext('2d');

    if (!context) return null;

    // Set canvas dimensions to match video
    canvas.width = video.videoWidth;
    canvas.height = video.videoHeight;

    // Draw video frame to canvas (mirror horizontally)
    context.translate(canvas.width, 0);
    context.scale(-1, 1);
    context.drawImage(video, 0, 0, canvas.width, canvas.height);
    context.setTransform(1, 0, 0, 1, 0, 0); // Reset transform

    // Get blob from canvas
    return new Promise((resolve) => {
      canvas.toBlob((blob) => {
        resolve(blob);
      }, 'image/jpeg', 0.85);
    });
  }, []);

  // Send frame to Hume API for analysis
  const analyzeFrame = useCallback(async (blob: Blob): Promise<EmotionData | null> => {
    if (!blob || !apiKey) return null;

    try {
      // Create form data with the image
      const formData = new FormData();
      formData.append('file', blob, 'face.jpg');
      formData.append('models', 'face');

      // Send to Hume API
      const response = await axios.post('https://api.hume.ai/v0/batch/jobs', formData, {
        headers: {
          'X-Hume-Api-Key': apiKey,
          'Accept': 'application/json',
        },
      });

      if (!response.data || !response.data.job_id) {
        throw new Error('Invalid response from Hume API');
      }

      // Poll for results
      const jobId = response.data.job_id;
      let maxRetries = 10;
      let retryDelay = 500; // ms

      while (maxRetries > 0) {
        await new Promise(resolve => setTimeout(resolve, retryDelay));
        
        const resultsResponse = await axios.get(`https://api.hume.ai/v0/batch/jobs/${jobId}/predictions`, {
          headers: {
            'X-Hume-Api-Key': apiKey,
            'Accept': 'application/json',
          },
        });

        if (resultsResponse.data && 
            resultsResponse.data.predictions && 
            resultsResponse.data.predictions.length > 0 &&
            resultsResponse.data.predictions[0].models &&
            resultsResponse.data.predictions[0].models.face) {
          
          const faceData = resultsResponse.data.predictions[0].models.face;
          
          if (faceData.emotions) {
            // Format emotion data
            const emotions: Record<string, number> = {};
            let dominantEmotion = '';
            let maxScore = 0;

            faceData.emotions.forEach((emotion: { name: string; score: number }) => {
              emotions[emotion.name] = emotion.score;
              if (emotion.score > maxScore) {
                maxScore = emotion.score;
                dominantEmotion = emotion.name;
              }
            });

            const result: EmotionData = {
              emotions,
              dominantEmotion,
              timestamp: Date.now(),
            };

            return result;
          }
        }

        maxRetries--;
        retryDelay *= 1.5; // Exponential backoff
      }

      throw new Error('Failed to get results after multiple retries');
    } catch (err) {
      console.error('Error analyzing facial emotions:', err);
      setError(`感情分析に失敗しました: ${err instanceof Error ? err.message : String(err)}`);
      return null;
    }
  }, [apiKey]);

  // Capture emotion data
  const captureEmotion = useCallback(async (): Promise<EmotionData | null> => {
    try {
      if (!videoRef.current || !canvasRef.current) {
        throw new Error('Video or canvas not initialized');
      }

      const frameBlob = await captureFrame();
      if (!frameBlob) {
        throw new Error('Failed to capture video frame');
      }

      const emotionData = await analyzeFrame(frameBlob);
      if (emotionData) {
        setCurrentEmotion(emotionData);
        setEmotionHistory(prev => [...prev, emotionData]);
        return emotionData;
      }
      
      return null;
    } catch (err) {
      console.error('Error capturing emotion:', err);
      setError(`感情キャプチャに失敗しました: ${err instanceof Error ? err.message : String(err)}`);
      return null;
    }
  }, [captureFrame, analyzeFrame]);

  // Enable emotion tracking
  const enableTracking = useCallback(async () => {
    if (!isInitialized) {
      setError('WebcamComponentが初期化されていません');
      return;
    }

    if (isTracking) {
      return; // Already tracking
    }

    try {
      // Capture initial emotion
      await captureEmotion();
      
      // Start tracking interval
      trackingIntervalRef.current = setInterval(async () => {
        await captureEmotion();
      }, captureInterval);
      
      setIsTracking(true);
      setError(null);
    } catch (err) {
      console.error('Error enabling emotion tracking:', err);
      setError(`感情トラッキングの有効化に失敗しました: ${err instanceof Error ? err.message : String(err)}`);
    }
  }, [isInitialized, isTracking, captureEmotion, captureInterval]);

  // Disable emotion tracking
  const disableTracking = useCallback(() => {
    if (trackingIntervalRef.current) {
      clearInterval(trackingIntervalRef.current);
      trackingIntervalRef.current = null;
    }
    setIsTracking(false);
  }, []);

  // Clean up on unmount
  useEffect(() => {
    return () => {
      if (trackingIntervalRef.current) {
        clearInterval(trackingIntervalRef.current);
      }
    };
  }, []);

  // Create context value
  const contextValue: HumeEmotionContextType = {
    isTracking,
    isInitialized,
    currentEmotion,
    emotionHistory,
    error,
    enableTracking,
    disableTracking,
    captureEmotion,
    attachVideoElement,
    detachVideoElement,
    clearEmotionHistory,
  };

  return (
    <HumeEmotionContext.Provider value={contextValue}>
      {children}
    </HumeEmotionContext.Provider>
  );
}

// Custom hook for using the context
export function useHumeEmotion() {
  const context = useContext(HumeEmotionContext);
  
  if (context === undefined) {
    throw new Error('useHumeEmotion must be used within a HumeEmotionProvider');
  }
  
  return context;
} 