"use client";

import { useState, useEffect } from 'react';
import JungVoiceTest from './JungVoiceTest';
import ConsentForm from '../consent/ConsentForm';
import { JungVoiceAssessmentProps, TestResults } from './types';
import { JungVoiceAssessmentPropsSchema } from './schema';
import { HumeEmotionProvider } from '@/providers/HumeEmotionProvider';
import FaceEmotionAnalysis from '@/app/hume-websocket/components/FaceEmotionAnalysis';
import { v4 as uuidv4 } from 'uuid';
import { useKawasakiStore } from '@/store/kawasakiStore';

export default function JungVoiceAssessment({ 
  numberOfWords = 100,
  apiKey = process.env.NEXT_PUBLIC_HUME_API_KEY || '',
  generationId = '795c949a-1510-4a80-9646-7d0863b023ab',
  voiceName = 'David Hume',
  speechRecognitionLang = 'en-US',
  onTestComplete,
  className = '',
}: JungVoiceAssessmentProps) {
  // Props validation
  const validatedProps = JungVoiceAssessmentPropsSchema.parse({
    numberOfWords,
    apiKey,
    generationId,
    voiceName,
    speechRecognitionLang,
    onTestComplete,
    className
  });

  const [testResults, setTestResults] = useState<TestResults | null>(null);
  const [showAnalysis, setShowAnalysis] = useState<boolean>(false);
  const [hasConsented, setHasConsented] = useState<boolean>(false);
  const [assessmentId] = useState<string>(uuidv4());
  const [userId, setUserId] = useState<string>('');
  const [isAnalyzing, setIsAnalyzing] = useState<boolean>(false);

  // Kawasaki Model ストアから更新関数を取得
  const updateVoiceAssessment = useKawasakiStore(state => state.updateVoiceAssessment);

  // ユーザーIDの初期化 - useEffect で実行してSSRに対応
  useEffect(() => {
    // Only run in browser environment
    if (typeof window !== 'undefined') {
      // ユーザーIDをローカルストレージから取得または生成
      const existingUserId = localStorage.getItem('jung_test_user_id');
      const newUserId = existingUserId || uuidv4();
      
      if (!existingUserId) {
        localStorage.setItem('jung_test_user_id', newUserId);
      }
      
      setUserId(newUserId);
    }
  }, []);

  const handleTestComplete = (results: TestResults) => {
    setTestResults(results);
    setShowAnalysis(true);
    setIsAnalyzing(false);
    
    // Kawasaki Model ストアに結果を反映
    try {
      updateVoiceAssessment({
        userId,
        assessmentId,
        results,
        timestamp: new Date().toISOString(),
      });
    } catch (error) {
      console.error('Failed to update Kawasaki Model with voice assessment results:', error);
      // エラーハンドリングを追加（必要に応じて）
    }
    
    if (onTestComplete) {
      onTestComplete(results);
    }
  };

  const handleRetakeTest = () => {
    setTestResults(null);
    setShowAnalysis(false);
    setIsAnalyzing(false);
  };

  const handleConsent = () => {
    setHasConsented(true);
    setIsAnalyzing(true);
  };

  return (
    <HumeEmotionProvider apiKey={validatedProps.apiKey}>
      <div className={`px-4 sm:px-6 md:px-8 pt-4 pb-6 ${className}`}>
        {!hasConsented ? (
          <div className="max-w-4xl mx-auto">
            <ConsentForm onConsent={handleConsent} />
          </div>
        ) : (
          <div className="max-w-4xl mx-auto space-y-6">
            
            <JungVoiceTest 
              numberOfWords={validatedProps.numberOfWords} 
              apiKey={validatedProps.apiKey}
              generationId={validatedProps.generationId}
              voiceName={validatedProps.voiceName}
              speechRecognitionLang={validatedProps.speechRecognitionLang}
              onTestComplete={handleTestComplete}
              className={className}
            />
            
            {/* {(isAnalyzing || showAnalysis) && (
              <div className="mt-6">
                <h2 className="text-2xl font-bold mb-3 text-center">Emotion Analysis</h2>
                <p className="text-center mb-4 text-gray-600 dark:text-gray-300">
                  {isAnalyzing && !showAnalysis ? "Analyzing emotions in real-time based on facial expressions and voice tone" : "Analysis of emotions based on facial expressions and voice tone during the test"}
                </p>
                <FaceEmotionAnalysis />
              </div>
            )} */}
          </div>
        )}
      </div>
    </HumeEmotionProvider>
  );
} 