"use client";

import { useState } from 'react';
import JungVoiceTest from './JungVoiceTest';
import ConsentForm from '../consent/ConsentForm';
import { JungVoiceAssessmentProps, TestResults } from './types';
import { JungVoiceAssessmentPropsSchema } from './schema';

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
    <div className={`py-8 ${className}`}>
      {!hasConsented ? (
        <ConsentForm onConsent={handleConsent} />
      ) : (
        <JungVoiceTest 
          numberOfWords={validatedProps.numberOfWords} 
          apiKey={validatedProps.apiKey}
          generationId={validatedProps.generationId}
          voiceName={validatedProps.voiceName}
          speechRecognitionLang={validatedProps.speechRecognitionLang}
          onTestComplete={handleTestComplete}
          className={className}
        />
      )}
    </div>
  );
} 