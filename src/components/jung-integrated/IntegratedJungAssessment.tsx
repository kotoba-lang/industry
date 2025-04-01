"use client";

import { useState, useEffect } from 'react';
import dynamic from 'next/dynamic';
import JungWordTest from '@/components/jung-word-assessment/JungWordTest';
import ResultAnalysis from '@/components/jung-word-assessment/ResultAnalysis';
import ModelParamsControl from '@/components/kawasaki-model/ModelParamsControl';
import PhysicsStateMachine from '@/components/kawasaki-model/PhysicsStateMachine';
import { useAnimation } from '@/components/kawasaki-model/hooks/useAnimation';
import { defaultModelParams, type IntegratedModelParams } from '@/components/kawasaki-model/utils/integratedModel';
import { type TestResults } from '@/components/jung-word-assessment/types';
import { generateGraphDataFromWordAssessment } from './utils/generateGraphDataFromAssessment';

// Using dynamic import for the physics graph component (client-side only)
const PhysicsGraph = dynamic(() => import('@/components/kawasaki-model/PhysicsGraph'), { ssr: false });

interface IntegratedJungAssessmentProps {
  numberOfWords?: number;
  testResults: TestResults | null;
  setTestResults: (results: TestResults | null) => void;
}

export default function IntegratedJungAssessment({ 
  numberOfWords = 30,
  testResults,
  setTestResults
}: IntegratedJungAssessmentProps) {
  const [showTest, setShowTest] = useState<boolean>(true);
  const [showAnalysis, setShowAnalysis] = useState<boolean>(false);
  const [showModel, setShowModel] = useState<boolean>(false);
  
  // Model parameters and state
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
  const graphData = testResults 
    ? generateGraphDataFromWordAssessment(testResults, transitionState, time, modelParams)
    : { nodes: [], links: [] };

  // Handlers
  const handleTestComplete = (results: TestResults) => {
    setTestResults(results);
    setShowTest(false);
    setShowAnalysis(true);
  };

  const handleShowModel = () => {
    setShowAnalysis(false);
    setShowModel(true);
  };

  const handleRetakeTest = () => {
    setTestResults(null);
    setShowTest(true);
    setShowAnalysis(false);
    setShowModel(false);
  };

  const handleFrameRateChange = (event: React.ChangeEvent<HTMLInputElement>) => {
    setFrameRate(Number(event.target.value));
  };

  const handleSpeedChangeEvent = (event: React.ChangeEvent<HTMLInputElement>) => {
    handleSpeedChange(Number(event.target.value));
  };

  return (
    <div className="py-8">
      {showTest && (
        <JungWordTest 
          numberOfWords={numberOfWords} 
          onTestComplete={handleTestComplete} 
        />
      )}
      
      {showAnalysis && testResults && (
        <div className="p-4">
          <ResultAnalysis results={testResults} />
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
      
      {showModel && testResults && (
        <div className="p-4">
          <div className="mb-4 flex justify-between items-center">
            <h2 className="text-2xl font-bold">Vector Visualization of Your Word Associations</h2>
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
              
              <div className="bg-white/80 backdrop-blur-sm p-3 rounded-md shadow-sm border border-gray-200 text-xs overflow-auto flex-grow">
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
                  <li className="flex items-start">
                    <span className="inline-block w-1 h-1 rounded-full bg-gray-800 mt-1.5 mr-2"></span>
                    <span>Adjust parameters to see how different factors influence the model</span>
                  </li>
                </ul>
                <p className="mt-2 leading-relaxed">
                  According to Jung, delayed responses may indicate emotional complexes or areas of psychological tension.
                </p>
              </div>
              
              <div className="bg-white/80 backdrop-blur-sm p-3 rounded-md shadow-sm border border-gray-200 overflow-auto">
                <h3 className="font-bold mb-2 text-gray-800 text-xs">Test Summary</h3>
                <p className="text-xs">Average response time: <span className="font-semibold">{testResults.averageReactionTimeMs} ms</span></p>
                <p className="text-xs">Delayed responses: <span className="font-semibold">{testResults.delayedResponseCount} / {testResults.responses.length}</span></p>
              </div>
            </div>
            
            {/* Main visualization area */}
            <div className="w-3/4 border border-gray-200 rounded-md shadow-sm overflow-hidden bg-white/50 h-full">
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