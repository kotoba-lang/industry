"use client";

import React, { useState, useEffect, useMemo } from 'react';
import EmbeddingScatterPlot from './EmbeddingScatterPlot';
import AssociationHeatmap from './AssociationHeatmap';
import EvaluationMetrics from './EvaluationMetrics';
import {
  EmbeddingPoint,
  AssociationConnection,
  Cluster,
  EvaluationMetrics as IEvaluationMetrics,
  AnimationState,
  VisualizationConfig,
  JungTestData,
  UMAPConfig
} from './types';
import {
  generateEmbeddingPoints,
  generateAssociationConnections,
  performClustering,
  calculateEmbeddingAnalysis
} from './utils/embeddingUtils';
import {
  applyDimensionReduction,
  createAnimatedReduction,
  calculatePreservationMetrics
} from './utils/dimensionReduction';

interface JungEmbeddingVisualizationProps {
  testData: JungTestData[];
  width?: number;
  height?: number;
  className?: string;
}

const JungEmbeddingVisualization: React.FC<JungEmbeddingVisualizationProps> = ({
  testData,
  width = 800,
  height = 600,
  className = ''
}) => {
  // State management
  const [activeTab, setActiveTab] = useState<'scatter' | 'heatmap' | 'metrics'>('scatter');
  const [animationState, setAnimationState] = useState<AnimationState>({
    frame: 0,
    totalFrames: 100,
    isPlaying: false,
    speed: 1,
    currentPhase: 'initialization'
  });
  
  const [config, setConfig] = useState<VisualizationConfig>({
    width,
    height,
    pointSize: 6,
    connectionOpacity: 0.6,
    clusterOpacity: 0.3,
    animationDuration: 3000,
    showLabels: true,
    showConnections: true,
    showClusters: true,
    colorScheme: 'jung',
    interactionMode: 'hover'
  });

  const [umapConfig, setUmapConfig] = useState<UMAPConfig>({
    nNeighbors: 15,
    minDist: 0.1,
    metric: 'euclidean',
    nComponents: 2,
    randomState: 42
  });

  const [selectedPoint, setSelectedPoint] = useState<EmbeddingPoint | null>(null);
  const [animationSequence, setAnimationSequence] = useState<EmbeddingPoint[][]>([]);

  // Combine multiple test data into single dataset
  const combinedTestData = useMemo((): JungTestData | null => {
    if (testData.length === 0) return null;
    
    const allResponses = testData.flatMap(test => test.responses);
    const totalReactionTime = allResponses.reduce((sum, r) => sum + r.reactionTime, 0);
    const delayedCount = allResponses.filter(r => r.isDelayed).length;
    
    return {
      responses: allResponses,
      averageReactionTime: allResponses.length > 0 ? totalReactionTime / allResponses.length : 0,
      delayedResponseCount: delayedCount,
      testType: 'integrated',
      timestamp: Date.now()
    };
  }, [testData]);

  // Generate visualization data
  const visualizationData = useMemo(() => {
    if (!combinedTestData) {
      return {
        points: [],
        connections: [],
        clusters: [],
        metrics: {
          semanticCoherence: 0,
          temporalConsistency: 0,
          emotionalResonance: 0,
          clusterQuality: 0,
          retrievalAccuracy: 0,
          generationFluency: 0,
          overallScore: 0
        }
      };
    }

    // Generate base embedding points
    const basePoints = generateEmbeddingPoints(combinedTestData);
    
    // Apply dimension reduction
    const reducedPoints = applyDimensionReduction(basePoints, umapConfig);
    
    // Generate connections and clusters
    const connections = generateAssociationConnections(combinedTestData, reducedPoints);
    const clusters = performClustering(reducedPoints, 5);
    
    // Calculate evaluation metrics
    const embeddingAnalysis = calculateEmbeddingAnalysis(reducedPoints, clusters);
    const preservationMetrics = calculatePreservationMetrics(basePoints, reducedPoints);
    
    const metrics: IEvaluationMetrics = {
      semanticCoherence: Math.random() * 0.4 + 0.5, // Simulated
      temporalConsistency: 1 - (combinedTestData.delayedResponseCount / combinedTestData.responses.length),
      emotionalResonance: combinedTestData.delayedResponseCount / combinedTestData.responses.length,
      clusterQuality: embeddingAnalysis.silhouetteScore,
      retrievalAccuracy: preservationMetrics.correlationCoefficient,
      generationFluency: preservationMetrics.neighborhoodPreservation,
      overallScore: 0
    };
    
    // Calculate overall score
    metrics.overallScore = (
      metrics.semanticCoherence +
      metrics.temporalConsistency +
      metrics.emotionalResonance +
      metrics.clusterQuality +
      metrics.retrievalAccuracy +
      metrics.generationFluency
    ) / 6;

    return {
      points: reducedPoints,
      connections,
      clusters,
      metrics
    };
  }, [combinedTestData, umapConfig]);

  // Animation control
  useEffect(() => {
    if (!animationState.isPlaying) return;

    const interval = setInterval(() => {
      setAnimationState(prev => {
        const nextFrame = (prev.frame + prev.speed) % prev.totalFrames;
        let nextPhase = prev.currentPhase;
        
        // Update phase based on frame
        if (nextFrame < 20) nextPhase = 'initialization';
        else if (nextFrame < 40) nextPhase = 'clustering';
        else if (nextFrame < 60) nextPhase = 'association';
        else if (nextFrame < 80) nextPhase = 'evaluation';
        else nextPhase = 'complete';

        return {
          ...prev,
          frame: nextFrame,
          currentPhase: nextPhase
        };
      });
    }, 50);

    return () => clearInterval(interval);
  }, [animationState.isPlaying, animationState.speed]);

  // Generate animation sequence when points change
  useEffect(() => {
    if (visualizationData.points.length > 0) {
      const sequence = createAnimatedReduction(
        visualizationData.points,
        umapConfig,
        animationState.totalFrames
      );
      setAnimationSequence(sequence);
    }
  }, [visualizationData.points, umapConfig, animationState.totalFrames]);

  // Get current frame points for animation
  const currentPoints = useMemo(() => {
    if (!animationState.isPlaying || animationSequence.length === 0) {
      return visualizationData.points;
    }
    
    const frameIndex = Math.floor(animationState.frame) % animationSequence.length;
    return animationSequence[frameIndex] || visualizationData.points;
  }, [animationState.frame, animationState.isPlaying, animationSequence, visualizationData.points]);

  const handlePlayPause = () => {
    setAnimationState(prev => ({ ...prev, isPlaying: !prev.isPlaying }));
  };

  const handleSpeedChange = (speed: number) => {
    setAnimationState(prev => ({ ...prev, speed }));
  };

  const handleConfigChange = (newConfig: Partial<VisualizationConfig>) => {
    setConfig(prev => ({ ...prev, ...newConfig }));
  };

  const handleUmapConfigChange = (newConfig: Partial<UMAPConfig>) => {
    setUmapConfig(prev => ({ ...prev, ...newConfig }));
  };

  if (!combinedTestData || testData.length === 0) {
    return (
      <div className={`p-8 text-center bg-gray-50 rounded-lg ${className}`}>
        <div className="text-gray-500 text-lg mb-2">No test data available</div>
        <div className="text-gray-400 text-sm">
          Please complete a Jung word association test to see the visualization
        </div>
      </div>
    );
  }

  return (
    <div className={`bg-white rounded-lg shadow-lg ${className}`}>
      {/* Header */}
      <div className="p-6 border-b border-gray-200">
        <h2 className="text-2xl font-bold text-gray-800 mb-2">
          Jung Embedding Visualization
        </h2>
        <p className="text-gray-600 text-sm">
          Interactive analysis of word association embeddings with {combinedTestData.responses.length} associations
          from {testData.length} test{testData.length !== 1 ? 's' : ''}
        </p>
      </div>

      {/* Controls */}
      <div className="p-4 bg-gray-50 border-b border-gray-200">
        <div className="flex flex-wrap items-center gap-4">
          {/* Tab Selection */}
          <div className="flex bg-white rounded-lg border">
            {[
              { key: 'scatter', label: '📊 Scatter Plot', icon: '📊' },
              { key: 'heatmap', label: '🔥 Heatmap', icon: '🔥' },
              { key: 'metrics', label: '📈 Metrics', icon: '📈' }
            ].map(tab => (
              <button
                key={tab.key}
                onClick={() => setActiveTab(tab.key as any)}
                className={`px-4 py-2 text-sm font-medium transition-colors ${
                  activeTab === tab.key
                    ? 'bg-blue-500 text-white'
                    : 'text-gray-600 hover:text-gray-800'
                }`}
              >
                {tab.label}
              </button>
            ))}
          </div>

          {/* Animation Controls */}
          {activeTab === 'scatter' && (
            <>
              <button
                onClick={handlePlayPause}
                className="px-4 py-2 bg-green-500 text-white rounded hover:bg-green-600 transition-colors"
              >
                {animationState.isPlaying ? '⏸️ Pause' : '▶️ Play'}
              </button>
              
              <div className="flex items-center gap-2">
                <label className="text-sm text-gray-600">Speed:</label>
                <select
                  value={animationState.speed}
                  onChange={(e) => handleSpeedChange(Number(e.target.value))}
                  className="px-2 py-1 border rounded text-sm"
                >
                  <option value={0.5}>0.5x</option>
                  <option value={1}>1x</option>
                  <option value={2}>2x</option>
                  <option value={4}>4x</option>
                </select>
              </div>
            </>
          )}

          {/* Color Scheme */}
          <div className="flex items-center gap-2">
            <label className="text-sm text-gray-600">Color:</label>
            <select
              value={config.colorScheme}
              onChange={(e) => handleConfigChange({ colorScheme: e.target.value as any })}
              className="px-2 py-1 border rounded text-sm"
            >
              <option value="jung">Jung (Stimulus/Response)</option>
              <option value="emotion">Emotion Intensity</option>
              <option value="temporal">Reaction Time</option>
              <option value="default">Default</option>
            </select>
          </div>
        </div>
      </div>

      {/* Main Content */}
      <div className="p-6">
        {activeTab === 'scatter' && (
          <div className="h-[600px]">
            <EmbeddingScatterPlot
              points={currentPoints}
              connections={visualizationData.connections}
              clusters={visualizationData.clusters}
              animationState={animationState}
              config={config}
              onPointClick={setSelectedPoint}
              onPointHover={() => {}}
            />
          </div>
        )}

        {activeTab === 'heatmap' && (
          <div className="flex justify-center">
            <AssociationHeatmap
              points={visualizationData.points}
              connections={visualizationData.connections}
              width={Math.min(600, width - 100)}
              height={Math.min(600, height - 200)}
            />
          </div>
        )}

        {activeTab === 'metrics' && (
          <EvaluationMetrics
            metrics={visualizationData.metrics}
          />
        )}
      </div>

      {/* Selected Point Details */}
      {selectedPoint && activeTab === 'scatter' && (
        <div className="p-4 bg-blue-50 border-t border-blue-200">
          <h4 className="font-semibold text-blue-800 mb-2">Selected Word: {selectedPoint.word}</h4>
          <div className="grid grid-cols-2 md:grid-cols-4 gap-4 text-sm">
            <div>
              <span className="text-gray-600">Type:</span>
              <div className="font-medium">{selectedPoint.type}</div>
            </div>
            <div>
              <span className="text-gray-600">Category:</span>
              <div className="font-medium">{selectedPoint.category}</div>
            </div>
            {selectedPoint.reactionTime && (
              <div>
                <span className="text-gray-600">Avg Reaction:</span>
                <div className="font-medium">{selectedPoint.reactionTime.toFixed(0)}ms</div>
              </div>
            )}
            {selectedPoint.cluster !== undefined && (
              <div>
                <span className="text-gray-600">Cluster:</span>
                <div className="font-medium">Cluster {selectedPoint.cluster + 1}</div>
              </div>
            )}
          </div>
        </div>
      )}

      {/* Footer with Jung Quote */}
      <div className="p-4 bg-gray-50 rounded-b-lg border-t border-gray-200">
        <blockquote className="text-sm italic text-gray-600 text-center">
          "The word-association experiment... reveals the hidden thoughts which influence consciousness."
        </blockquote>
        <cite className="text-xs text-gray-500 block text-center mt-1">— Carl Gustav Jung</cite>
      </div>
    </div>
  );
};

export default JungEmbeddingVisualization; 