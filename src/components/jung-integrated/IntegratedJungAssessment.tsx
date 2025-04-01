"use client";

import { useState, useEffect } from 'react';
import dynamic from 'next/dynamic';
import JungWordTest from '@/components/jung-word-assessment/JungWordTest';
import ResultAnalysis from '@/components/jung-word-assessment/ResultAnalysis';
import JungVoiceTest from '@/components/jung-voice-assessment/JungVoiceTest';
import ModelParamsControl from '@/components/kawasaki-model/ModelParamsControl';
import PhysicsStateMachine from '@/components/kawasaki-model/PhysicsStateMachine';
import { useAnimation } from '@/components/kawasaki-model/hooks/useAnimation';
import { defaultModelParams, type IntegratedModelParams } from '@/components/kawasaki-model/utils/integratedModel';
import { type TestResults as WordTestResults } from '@/components/jung-word-assessment/types';
import { type TestResults as VoiceTestResults } from '@/components/jung-voice-assessment/types';
import { generateGraphDataFromWordAssessment } from './utils/generateGraphDataFromAssessment';
import { generateGraphDataFromVoiceAssessment } from './utils/generateGraphDataFromVoiceAssessment';
import { mergeGraphData } from './utils/mergeGraphData';

// Using dynamic import for the physics graph component (client-side only)
const PhysicsGraph = dynamic(() => import('@/components/kawasaki-model/PhysicsGraph'), { ssr: false });

type AssessmentType = 'word' | 'voice' | 'both';

interface IntegratedJungAssessmentProps {
  numberOfWords?: number;
  apiKey?: string;
  voiceName?: string;
  speechRecognitionLang?: string;
  wordTestResults?: WordTestResults | null;
  voiceTestResults?: VoiceTestResults | null;
  setWordTestResults?: (results: WordTestResults | null) => void;
  setVoiceTestResults?: (results: VoiceTestResults | null) => void;
}

// Adapts voice test results to a common format
const adaptVoiceTestResults = (results: any): any => {
  return {
    responses: results.responses.map((r: any) => ({
      stimulus: r.stimulusWord,
      response: r.responseWord,
      reactionTimeMs: r.reactionTimeMs,
      isDelayed: r.reactionTimeMs > 2000 // Assuming 2s threshold
    })),
    averageReactionTimeMs: results.averageReactionTimeMs,
    delayedResponseCount: results.delayedResponsesCount,
    completedAt: results.completedAt || new Date()
  };
};

export default function IntegratedJungAssessment({ 
  numberOfWords = 30,
  apiKey = process.env.NEXT_PUBLIC_HUME_API_KEY || '',
  voiceName = 'David Hume',
  speechRecognitionLang = 'en-US',
  wordTestResults: externalWordResults = null,
  voiceTestResults: externalVoiceResults = null,
  setWordTestResults,
  setVoiceTestResults
}: IntegratedJungAssessmentProps) {
  // Create internal state if external state handlers aren't provided
  const [internalWordResults, setInternalWordResults] = useState<WordTestResults | null>(null);
  const [internalVoiceResults, setInternalVoiceResults] = useState<VoiceTestResults | null>(null);
  
  // Use external state if provided, otherwise use internal state
  const wordTestResults = externalWordResults !== undefined ? externalWordResults : internalWordResults;
  const voiceTestResults = externalVoiceResults !== undefined ? externalVoiceResults : internalVoiceResults;
  
  // Create safe setter functions that use external setters if provided, otherwise use internal
  const safeSetWordResults = (results: WordTestResults | null) => {
    if (setWordTestResults) {
      setWordTestResults(results);
    } else {
      setInternalWordResults(results);
    }
  };
  
  const safeSetVoiceResults = (results: VoiceTestResults | null) => {
    if (setVoiceTestResults) {
      setVoiceTestResults(results);
    } else {
      setInternalVoiceResults(results);
    }
  };

  // Assessment selection and display states
  const [assessmentType, setAssessmentType] = useState<AssessmentType>('word');
  const [showTestSelection, setShowTestSelection] = useState<boolean>(true);
  const [showWordTest, setShowWordTest] = useState<boolean>(false);
  const [showVoiceTest, setShowVoiceTest] = useState<boolean>(false);
  const [showAnalysis, setShowAnalysis] = useState<boolean>(false);
  const [showModel, setShowModel] = useState<boolean>(false);
  
  // Model parameters and animation state
  const { 
    transitionState, 
    time, 
    isPlaying, 
    speed, 
    handleStateChange, 
    togglePlay, 
    handleSpeedChange 
  } = useAnimation({
    initialSpeed: 1,
    initialPlaying: false,
    autoTransitionProbability: {
      stable: 0.1,
      excited: 0.3,
      decaying: 0.5,
    },
  });

  const [frameRate, setFrameRate] = useState(30);
  const [selectedElement, setSelectedElement] = useState<string | null>(null);
  const [modelParams, setModelParams] = useState<IntegratedModelParams>(defaultModelParams);

  // Generate graph data from test results
  const wordGraphData = wordTestResults 
    ? generateGraphDataFromWordAssessment(wordTestResults, transitionState, time, modelParams)
    : { nodes: [], links: [] };
    
  const voiceGraphData = voiceTestResults
    ? generateGraphDataFromVoiceAssessment(voiceTestResults, transitionState, time, modelParams)
    : { nodes: [], links: [] };
  
  // Merge graph data when both tests are available
  const graphData = (wordTestResults && voiceTestResults) 
    ? mergeGraphData(wordGraphData, voiceGraphData)
    : wordTestResults 
      ? wordGraphData 
      : voiceGraphData;

  // Test selection handlers
  const handleSelectWordTest = () => {
    setAssessmentType('word');
    setShowTestSelection(false);
    setShowWordTest(true);
    setShowVoiceTest(false);
  };
  
  const handleSelectVoiceTest = () => {
    setAssessmentType('voice');
    setShowTestSelection(false);
    setShowWordTest(false);
    setShowVoiceTest(true);
  };
  
  const handleSelectBothTests = () => {
    setAssessmentType('both');
    setShowTestSelection(false);
    setShowWordTest(true);
    setShowVoiceTest(false);
  };

  // Test completion handlers
  const handleWordTestComplete = (results: WordTestResults) => {
    safeSetWordResults(results);
    
    if (assessmentType === 'both' && !voiceTestResults) {
      // Switch to voice test if doing both
      setShowWordTest(false);
      
      setTimeout(() => {
        setShowVoiceTest(true);
      }, 500);
    } else {
      // Just completed word test only, or already have voice results
      setShowWordTest(false);
      setShowAnalysis(true);
    }
  };
  
  const handleVoiceTestComplete = (results: VoiceTestResults) => {
    safeSetVoiceResults(results);
    setShowVoiceTest(false);
    
    setTimeout(() => {
      setShowAnalysis(true);
    }, 500);
  };

  // Navigation handlers
  const handleShowModel = () => {
    setShowAnalysis(false);
    setShowModel(true);
  };

  const handleRetakeTest = () => {
    safeSetWordResults(null);
    safeSetVoiceResults(null);
    
    setShowWordTest(false);
    setShowVoiceTest(false);
    setShowAnalysis(false);
    setShowModel(false);
    
    setTimeout(() => {
      setShowTestSelection(true);
    }, 500);
  };

  // Configuration handlers
  const handleFrameRateChange = (event: React.ChangeEvent<HTMLInputElement>) => {
    setFrameRate(Number(event.target.value));
  };

  const handleSpeedChangeEvent = (event: React.ChangeEvent<HTMLInputElement>) => {
    handleSpeedChange(Number(event.target.value));
  };

  return (
    <div className="py-8">
      {showTestSelection && (
        <div className="p-4 max-w-2xl mx-auto">
          <h2 className="text-2xl font-bold mb-6 text-center">Jung's Association Tests</h2>
          <p className="mb-6 text-center text-gray-700 dark:text-gray-200">
            Select the type of assessment you'd like to take:
          </p>
          
          <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
            <button
              onClick={handleSelectWordTest}
              className="p-4 bg-white dark:bg-gray-800 border border-gray-200 dark:border-gray-700 rounded-lg shadow-sm hover:bg-blue-50 transition-colors"
            >
              <h3 className="font-bold mb-2">Word Association Test</h3>
              <p className="text-sm text-gray-700 dark:text-gray-200">Type responses to stimulus words</p>
            </button>
            
            <button
              onClick={handleSelectVoiceTest}
              className="p-4 bg-white dark:bg-gray-800 border border-gray-200 dark:border-gray-700 rounded-lg shadow-sm hover:bg-blue-50 transition-colors"
            >
              <h3 className="font-bold mb-2">Voice Association Test</h3>
              <p className="text-sm text-gray-700 dark:text-gray-200">Speak responses to spoken stimulus words</p>
            </button>
            
            <button
              onClick={handleSelectBothTests}
              className="p-4 bg-white dark:bg-gray-800 border border-gray-200 dark:border-gray-700 rounded-lg shadow-sm hover:bg-blue-50 transition-colors"
            >
              <h3 className="font-bold mb-2">Complete Assessment</h3>
              <p className="text-sm text-gray-700 dark:text-gray-200">Take both tests for a comprehensive analysis</p>
            </button>
          </div>
        </div>
      )}
      
      {showWordTest && (
        <JungWordTest 
          numberOfWords={numberOfWords} 
          onTestComplete={handleWordTestComplete} 
        />
      )}
      
      {showVoiceTest && (
        <JungVoiceTest 
          numberOfWords={numberOfWords}
          apiKey={apiKey}
          voiceName={voiceName}
          speechRecognitionLang={speechRecognitionLang}
          onTestComplete={handleVoiceTestComplete}
        />
      )}
      
      {showAnalysis && (wordTestResults || voiceTestResults) && (
        <div className="p-4">
          {wordTestResults && (
            <div className="mb-8">
              <h3 className="text-xl font-bold mb-4">Word Association Results</h3>
              <ResultAnalysis results={wordTestResults} />
            </div>
          )}
          
          {voiceTestResults && (
            <div className="mb-8">
              <h3 className="text-xl font-bold mb-4">Voice Association Results</h3>
              <ResultAnalysis results={adaptVoiceTestResults(voiceTestResults)} />
            </div>
          )}
          
          <div className="mt-8 flex justify-center space-x-4">
            <button
              onClick={handleShowModel}
              className="px-4 py-2 bg-blue-600 text-white rounded-md hover:bg-blue-700 transition-colors"
            >
              View Vector Visualization
            </button>
            <button
              onClick={handleRetakeTest}
              className="px-4 py-2 bg-gray-600 text-white rounded-md hover:bg-gray-700 transition-colors"
            >
              Take Test Again
            </button>
          </div>
        </div>
      )}
      
      {showModel && (wordTestResults || voiceTestResults) && (
        <div className="p-4">
          <div className="mb-4 flex justify-between items-center">
            <h2 className="text-2xl font-bold">
              {assessmentType === 'both' 
                ? "Integrated Vector Visualization" 
                : assessmentType === 'voice' 
                  ? "Voice Association Visualization" 
                  : "Word Association Visualization"}
            </h2>
            <button
              onClick={handleRetakeTest}
              className="px-4 py-2 bg-gray-600 text-white rounded-md hover:bg-gray-700 transition-colors"
            >
              Take Test Again
            </button>
          </div>
          
          <div className="h-[80vh] flex">
            {/* Left sidebar with controls */}
            <div className="w-1/4 pr-4 flex flex-col space-y-4">
              <PhysicsStateMachine transitionState={transitionState} onStateChange={handleStateChange} />
              <ModelParamsControl params={modelParams} onChange={setModelParams} />
              
              <div className="bg-white/80 backdrop-blur-sm p-3 rounded-md shadow-sm border border-gray-200 dark:border-gray-700 text-xs overflow-auto flex-grow">
                <h3 className="font-bold mb-2 text-gray-800">About Your Results Visualization</h3>
                <p className="mb-2 leading-relaxed">
                  This visualization shows the relationships between your word associations:
                </p>
                <ul className="space-y-1 pl-4">
                  <li className="flex items-start">
                    <span className="inline-block w-1 h-1 rounded-full bg-gray-800 mt-1.5 mr-2"></span>
                    <span>Each node represents a stimulus or response word</span>
                  </li>
                  <li className="flex items-start">
                    <span className="inline-block w-1 h-1 rounded-full bg-gray-800 mt-1.5 mr-2"></span>
                    <span>Connections represent associations with delayed responses highlighted</span>
                  </li>
                  {assessmentType === 'both' && (
                    <li className="flex items-start">
                      <span className="inline-block w-1 h-1 rounded-full bg-gray-800 mt-1.5 mr-2"></span>
                      <span>Cross-connections show words that appeared in both tests</span>
                    </li>
                  )}
                  <li className="flex items-start">
                    <span className="inline-block w-1 h-1 rounded-full bg-gray-800 mt-1.5 mr-2"></span>
                    <span>Adjust parameters to see how different factors influence the model</span>
                  </li>
                </ul>
                <p className="mt-2 leading-relaxed">
                  According to Jung, delayed responses may indicate emotional complexes or areas of psychological tension.
                </p>
                {assessmentType === 'both' && (
                  <p className="mt-2 leading-relaxed">
                    Comparing written and spoken responses can reveal differences in your conscious and unconscious associations.
                  </p>
                )}
              </div>
              
              <div className="bg-white/80 backdrop-blur-sm p-3 rounded-md shadow-sm border border-gray-200 dark:border-gray-700 overflow-auto">
                <h3 className="font-bold mb-2 text-gray-800 text-xs">Test Summary</h3>
                {wordTestResults && (
                  <div className="mb-2">
                    <p className="text-xs font-medium">Word Test:</p>
                    <p className="text-xs">Average response time: <span className="font-semibold">{wordTestResults.averageReactionTimeMs} ms</span></p>
                    <p className="text-xs">Delayed responses: <span className="font-semibold">{wordTestResults.delayedResponseCount} / {wordTestResults.responses.length}</span></p>
                  </div>
                )}
                {voiceTestResults && (
                  <div>
                    <p className="text-xs font-medium">Voice Test:</p>
                    <p className="text-xs">Average response time: <span className="font-semibold">{voiceTestResults.averageReactionTimeMs} ms</span></p>
                    <p className="text-xs">Delayed responses: <span className="font-semibold">{voiceTestResults.delayedResponsesCount} / {voiceTestResults.responses.length}</span></p>
                  </div>
                )}
              </div>
            </div>
            
            {/* Main visualization area */}
            <div className="w-3/4 border border-gray-200 dark:border-gray-700 rounded-md shadow-sm overflow-hidden bg-white/50 h-full">
              <PhysicsGraph
                data={graphData}
                frameRate={frameRate}
                time={time}
                isPlaying={isPlaying}
                speed={speed}
                selectedElement={selectedElement}
                setSelectedElement={setSelectedElement}
              />
            </div>
          </div>
        </div>
      )}
    </div>
  );
} 