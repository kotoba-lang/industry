"use client";

import { useState, useRef, useEffect } from 'react';
import { Button } from '../ui/button';
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

type WordResponse = z.infer<typeof WordResponseSchema>;
type TestResults = z.infer<typeof TestResultsSchema>;

// Jung's original 100 stimulus words from his 1910 paper
export const JUNG_STIMULUS_WORDS = [
  'head', 'green', 'water', 'to sing', 'dead', 'long', 'ship', 'to pay', 'window', 'friendly',
  'to cook', 'to ask', 'cold', 'stem', 'to dance', 'village', 'lake', 'sick', 'pride', 'to cook',
  'ink', 'angry', 'needle', 'to swim', 'voyage', 'blue', 'lamp', 'to sin', 'bread', 'rich',
  'tree', 'to prick', 'pity', 'yellow', 'mountain', 'to die', 'salt', 'new', 'custom', 'to pray',
  'money', 'foolish', 'pamphlet', 'despise', 'finger', 'expensive', 'bird', 'to fall', 'book', 'unjust',
  'frog', 'to part', 'hunger', 'white', 'child', 'to take care', 'pencil', 'sad', 'plum', 'to marry',
  'house', 'dear', 'glass', 'to quarrel', 'fur', 'great', 'turnip', 'to hold', 'triangle', 'to fear',
  'anxious', 'to kiss', 'burn', 'clean', 'door', 'to choose', 'hay', 'contented', 'ridicule', 'to sleep',
  'month', 'nice', 'woman', 'to abuse', 'yellow', 'to come', 'stove', 'sad', 'stem', 'to dance',
  'sea', 'lovely', 'year', 'black', 'bread', 'family', 'to wash', 'cow', 'friend', 'happiness'
];

// Jung considered reaction times > 2 seconds as "delayed" and potentially significant
const DELAYED_REACTION_THRESHOLD_MS = 2000;

interface JungWordTestProps {
  numberOfWords?: number; // Allow customizing the number of words to test
  onTestComplete?: (results: TestResults) => void;
}

export default function JungWordTest({ 
  numberOfWords = 100, 
  onTestComplete 
}: JungWordTestProps) {
  // Use only the specified number of words
  const stimulusWords = JUNG_STIMULUS_WORDS.slice(0, numberOfWords);
  
  const [currentWordIndex, setCurrentWordIndex] = useState<number>(-1); // -1 means test not started
  const [userResponse, setUserResponse] = useState<string>('');
  const [responses, setResponses] = useState<WordResponse[]>([]);
  const [startTime, setStartTime] = useState<number | null>(null);
  const [testComplete, setTestComplete] = useState<boolean>(false);
  const [averageReactionTime, setAverageReactionTime] = useState<number>(0);
  const [delayedResponses, setDelayedResponses] = useState<number>(0);
  
  const inputRef = useRef<HTMLInputElement>(null);

  // Start the test
  const startTest = () => {
    setCurrentWordIndex(0);
    setResponses([]);
    setTestComplete(false);
    setStartTime(Date.now());
  };

  // Reset the test
  const resetTest = () => {
    setCurrentWordIndex(-1);
    setUserResponse('');
    setResponses([]);
    setTestComplete(false);
    setStartTime(null);
    setAverageReactionTime(0);
    setDelayedResponses(0);
  };

  // Record response and move to next word
  const recordResponse = () => {
    if (startTime === null || currentWordIndex < 0 || currentWordIndex >= stimulusWords.length) {
      return;
    }

    const endTime = Date.now();
    const reactionTimeMs = endTime - startTime;
    const isDelayed = reactionTimeMs > DELAYED_REACTION_THRESHOLD_MS;

    const response: WordResponse = {
      stimulus: stimulusWords[currentWordIndex],
      response: userResponse.trim(),
      reactionTimeMs,
      isDelayed,
    };

    const updatedResponses = [...responses, response];
    setResponses(updatedResponses);
    setUserResponse('');

    // Move to next word or complete test
    if (currentWordIndex < stimulusWords.length - 1) {
      setCurrentWordIndex(currentWordIndex + 1);
      setStartTime(Date.now());
    } else {
      completeTest(updatedResponses);
    }
  };

  // Complete the test and calculate results
  const completeTest = (finalResponses: WordResponse[]) => {
    const totalReactionTime = finalResponses.reduce((sum, r) => sum + r.reactionTimeMs, 0);
    const avgReactionTime = Math.round(totalReactionTime / finalResponses.length);
    const delayedCount = finalResponses.filter(r => r.isDelayed).length;

    setAverageReactionTime(avgReactionTime);
    setDelayedResponses(delayedCount);
    setTestComplete(true);
    setCurrentWordIndex(-1);

    const results: TestResults = {
      responses: finalResponses,
      averageReactionTimeMs: avgReactionTime,
      delayedResponseCount: delayedCount,
      completedAt: new Date(),
    };

    if (onTestComplete) {
      onTestComplete(results);
    }
  };

  // Handle key press for submitting response
  const handleKeyPress = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter' && userResponse.trim() !== '') {
      recordResponse();
    }
  };

  // Focus input when moving to a new word
  useEffect(() => {
    if (currentWordIndex >= 0 && inputRef.current) {
      inputRef.current.focus();
    }
  }, [currentWordIndex]);

  return (
    <div className="max-w-2xl mx-auto p-6 bg-white dark:bg-gray-800 rounded-lg shadow-md">
      <h2 className="text-2xl font-bold mb-6 text-center text-gray-800 dark:text-white"> Spirit in Physics (Jung's Word Association Test Embedding Model)</h2>
      
      {currentWordIndex === -1 && !testComplete && (
        <div className="text-center">
          <p className="mb-4 text-gray-800 dark:text-white">
            This test presents {numberOfWords} words one at a time. For each word, type the first word that
            comes to your mind as quickly as possible.
          </p>
          <p className="mb-6 text-gray-800 dark:text-white">
            The test measures your reaction time and looks for patterns in your responses.
          </p>
          <Button onClick={startTest} className="px-6 py-2">
            Begin Test
          </Button>
        </div>
      )}

      {currentWordIndex >= 0 && currentWordIndex < stimulusWords.length && (
        <div className="text-center">
          <div className="mb-8">
            <p className="text-sm text-gray-500 dark:text-gray-300 mb-1">Word {currentWordIndex + 1} of {stimulusWords.length}</p>
            <h3 className="text-3xl font-bold text-gray-800 dark:text-white">{stimulusWords[currentWordIndex]}</h3>
          </div>
          
          <div className="flex items-center justify-center mb-4">
            <input
              ref={inputRef}
              type="text"
              value={userResponse}
              onChange={(e) => setUserResponse(e.target.value)}
              onKeyDown={handleKeyPress}
              placeholder="Type your response..."
              className="px-4 py-2 border rounded-l-md w-64 focus:outline-none focus:ring-2 focus:ring-blue-500 text-gray-800"
              autoFocus
            />
            <Button 
              onClick={recordResponse}
              disabled={!userResponse.trim()}
              className="rounded-l-none"
            >
              Next
            </Button>
          </div>
          
          <p className="text-sm text-gray-500 dark:text-gray-300">
            Press Enter after typing your response
          </p>
        </div>
      )}

      {testComplete && (
        <div className="text-center">
          <h3 className="text-xl font-semibold mb-4 text-gray-800 dark:text-white">Test Complete</h3>
          
          <div className="bg-gray-100 dark:bg-gray-700 p-4 rounded-md mb-6">
            <p className="mb-2 dark:text-white">
              <span className="font-medium text-gray-800 dark:text-white">Average reaction time:</span> {averageReactionTime} ms
            </p>
            <p className="dark:text-white">
              <span className="font-medium text-gray-800 dark:text-white">Delayed responses:</span> {delayedResponses} out of {responses.length}
            </p>
          </div>
          
          <h4 className="text-lg font-medium mb-3 text-gray-800 dark:text-white">Your Responses</h4>
          <div className="max-h-80 overflow-y-auto mb-6">
            <table className="w-full border-collapse">
              <thead className="bg-gray-50 dark:bg-gray-700">
                <tr>
                  <th className="px-4 py-2 text-left text-sm font-medium text-gray-500 dark:text-gray-300">Stimulus</th>
                  <th className="px-4 py-2 text-left text-sm font-medium text-gray-500 dark:text-gray-300">Response</th>
                  <th className="px-4 py-2 text-left text-sm font-medium text-gray-500 dark:text-gray-300">Time (ms)</th>
                </tr>
              </thead>
              <tbody>
                {responses.map((resp, index) => (
                  <tr key={index} className={resp.isDelayed 
                    ? "bg-yellow-50 dark:bg-yellow-900" 
                    : (index % 2 === 0 
                      ? "bg-white dark:bg-gray-800" 
                      : "bg-gray-50 dark:bg-gray-700")}>
                    <td className="px-4 py-2 text-sm text-gray-800 dark:text-white">{resp.stimulus}</td>
                    <td className="px-4 py-2 text-sm text-gray-800 dark:text-white">{resp.response}</td>
                    <td className={`px-4 py-2 text-sm ${resp.isDelayed ? "text-red-600 dark:text-red-400 font-medium" : ""} text-gray-800 dark:text-white`}>
                      {resp.reactionTimeMs}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          
          <p className="mb-6 text-sm text-gray-600 dark:text-gray-300">
            Note: Highlighted rows indicate delayed responses (&gt; 2 seconds), which Jung considered
            potentially significant and might indicate emotional complexes.
          </p>
          
          <Button onClick={resetTest} className="px-6 py-2">
            Take Test Again
          </Button>
        </div>
      )}
    </div>
  );
} 