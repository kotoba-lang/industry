"use client";

import { useState, useEffect } from "react";
import { JungVoiceAssessment } from "@/components/jung-voice-assessment";
import { JungEmbeddingVisualization } from "@/components/jung-visualization";
import { useKawasakiStore } from "@/store/kawasakiStore";

/**
 * Interactive component for Spirit in Physics that manages state for voice assessments
 * and embedding visualizations
 */
export default function SpiritInPhysicsInteractive() {
  // Get voice assessment data from store
  const voiceAssessments = useKawasakiStore((state) => state.voiceAssessments);
  const [hasVoiceData, setHasVoiceData] = useState(false);
  const [showEmbeddingVisualization, setShowEmbeddingVisualization] = useState(false);

  // Convert store data to Jung test data format
  const jungTestData = voiceAssessments.map(assessment => ({
    responses: assessment.results.responses.map(response => ({
      stimulus: response.stimulusWord,
      response: response.responseWord,
      reactionTime: response.reactionTimeMs,
      isDelayed: response.reactionTimeMs > 2000
    })),
    averageReactionTime: assessment.results.averageReactionTimeMs,
    delayedResponseCount: assessment.results.responses.filter(r => r.reactionTimeMs > 2000).length,
    testType: 'voice' as const,
    timestamp: parseInt(assessment.timestamp)
  }));

  useEffect(() => {
    // Check if there's voice assessment data available
    setHasVoiceData(voiceAssessments.length > 0);
  }, [voiceAssessments]);

  const handleVoiceTestComplete = (results: any) => {
    console.log("Voice test completed with results:", results);
    // The results are automatically saved to the Zustand store by the JungVoiceAssessment component
  };

  return (
    <div className="space-y-8">
      {/* Interactive Jung Voice Assessment */}
      <div className="p-6 bg-gradient-to-r from-blue-100 to-purple-100 rounded-lg">
        <JungVoiceAssessment 
          numberOfWords={10} 
          onTestComplete={handleVoiceTestComplete}
        />
        
        {hasVoiceData && (
          <div className="mt-6 p-4 bg-green-50 border border-green-200 rounded-lg shadow-sm">
            <p className="text-green-800 font-semibold">
              Voice assessment data has been collected and integrated into the Kawasaki Model
            </p>
            <p className="text-green-600 text-sm mt-1">
              The model visualization above has been updated with your voice assessment data
            </p>
            <button
              onClick={() => setShowEmbeddingVisualization(!showEmbeddingVisualization)}
              className="mt-3 px-4 py-2 bg-blue-600 text-white rounded-md hover:bg-blue-700 transition-colors"
            >
              {showEmbeddingVisualization ? 'Hide' : 'Show'} Jung Embedding Analysis
            </button>
          </div>
        )}
      </div>

      {/* Jung Embedding Visualization */}
      {hasVoiceData && showEmbeddingVisualization && (
        <div className="mt-8">
          <h3 className="text-xl font-semibold mb-4">RAG-style Jung Embedding Analysis</h3>
          <p className="text-gray-600 mb-4">
            Advanced visualization of word association embeddings using retrieval-augmented analysis techniques.
            This visualization shows semantic relationships, clustering patterns, and evaluation metrics
            inspired by RAG system evaluation methodologies.
          </p>
          <JungEmbeddingVisualization 
            testData={jungTestData}
            width={800}
            height={600}
            className="w-full"
          />
        </div>
      )}
    </div>
  );
} 