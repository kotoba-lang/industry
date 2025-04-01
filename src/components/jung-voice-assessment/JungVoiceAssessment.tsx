"use client";

import { useState } from 'react';
import JungVoiceTest from './JungVoiceTest';
import ConsentForm from '../consent/ConsentForm';
import { JungVoiceAssessmentProps, TestResults } from './types';
import { JungVoiceAssessmentPropsSchema } from './schema';
import { HumeEmotionProvider } from '@/providers/HumeEmotionProvider';
import EmotionAnalysis from './EmotionAnalysis';
import { v4 as uuidv4 } from 'uuid';

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

  // ユーザーIDの初期化
  useState(() => {
    // ユーザーIDをローカルストレージから取得または生成
    const existingUserId = localStorage.getItem('jung_test_user_id');
    const newUserId = existingUserId || uuidv4();
    
    if (!existingUserId) {
      localStorage.setItem('jung_test_user_id', newUserId);
    }
    
    setUserId(newUserId);
  });

  const handleTestComplete = (results: TestResults) => {
    setTestResults(results);
    setShowAnalysis(true);
    
    if (onTestComplete) {
      onTestComplete(results);
    }
  };

  const handleRetakeTest = () => {
    setTestResults(null);
    setShowAnalysis(false);
  };

  const handleConsent = () => {
    setHasConsented(true);
  };

  return (
    <HumeEmotionProvider apiKey={validatedProps.apiKey}>
      <div className={`py-8 ${className}`}>
        {!hasConsented ? (
          <ConsentForm onConsent={handleConsent} />
        ) : (
          <>
            <JungVoiceTest 
              numberOfWords={validatedProps.numberOfWords} 
              apiKey={validatedProps.apiKey}
              generationId={validatedProps.generationId}
              voiceName={validatedProps.voiceName}
              speechRecognitionLang={validatedProps.speechRecognitionLang}
              onTestComplete={handleTestComplete}
              className={className}
            />
            
            {showAnalysis && testResults && (
              <div className="mt-10">
                <h2 className="text-2xl font-bold mb-4 text-center">感情分析</h2>
                <p className="text-center mb-6 text-gray-600">
                  テスト中の顔の表情と声のトーンから感情を分析しました
                </p>
                <EmotionAnalysis 
                  userId={userId}
                  assessmentId={assessmentId}
                  className="mt-4"
                />
              </div>
            )}
          </>
        )}
      </div>
    </HumeEmotionProvider>
  );
} 