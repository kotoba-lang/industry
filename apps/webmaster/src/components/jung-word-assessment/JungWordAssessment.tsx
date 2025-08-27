"use client";

import { useState } from 'react';
import JungWordTest from './JungWordTest';
import ResultAnalysis from './ResultAnalysis';
import { z } from 'zod';

// Schema for a single word response
const WordResponseSchema = z.object({
  stimulus: z.string(),
  response: z.string(),
  reactionTimeMs: z.number(),
  isDelayed: z.boolean(),
});

// Schema for the complete test results
const TestResultsSchema = z.object({
  responses: z.array(WordResponseSchema),
  averageReactionTimeMs: z.number(),
  delayedResponseCount: z.number(),
  completedAt: z.date(),
});

type TestResults = z.infer<typeof TestResultsSchema>;

interface JungWordAssessmentProps {
  numberOfWords?: number;
}

export default function JungWordAssessment({ numberOfWords = 100 }: JungWordAssessmentProps) {
  const [testResults, setTestResults] = useState<TestResults | null>(null);
  const [showAnalysis, setShowAnalysis] = useState<boolean>(false);

  const handleTestComplete = (results: TestResults) => {
    setTestResults(results);
    setShowAnalysis(true);
  };

  const handleRetakeTest = () => {
    setTestResults(null);
    setShowAnalysis(false);
  };

  return (
    <div className="py-8">
      {!showAnalysis ? (
        <JungWordTest 
          numberOfWords={numberOfWords} 
          onTestComplete={handleTestComplete} 
        />
      ) : testResults ? (
        <div>
          <ResultAnalysis results={testResults} />
          <div className="mt-8 text-center">
            <button
              onClick={handleRetakeTest}
              className="px-4 py-2 bg-blue-600 text-white rounded-md hover:bg-blue-700 transition-colors"
            >
              Take Test Again
            </button>
          </div>
        </div>
      ) : (
        <div className="text-center p-8">
          <p className="text-red-500">Error: No test results available.</p>
          <button
            onClick={handleRetakeTest}
            className="mt-4 px-4 py-2 bg-blue-600 text-white rounded-md hover:bg-blue-700 transition-colors"
          >
            Take Test Again
          </button>
        </div>
      )}
    </div>
  );
} 