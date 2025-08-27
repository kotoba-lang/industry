"use client";

import React from 'react';
import { EvaluationMetrics as IEvaluationMetrics } from './types';

interface EvaluationMetricsProps {
  metrics: IEvaluationMetrics;
  className?: string;
}

const EvaluationMetrics: React.FC<EvaluationMetricsProps> = ({ metrics, className = '' }) => {
  const metricItems = [
    {
      key: 'semanticCoherence',
      label: 'Semantic Coherence',
      value: metrics.semanticCoherence,
      description: 'How well word associations maintain semantic relationships',
      color: 'text-blue-600',
      bgColor: 'bg-blue-50',
      icon: '🔗'
    },
    {
      key: 'temporalConsistency',
      label: 'Temporal Consistency',
      value: metrics.temporalConsistency,
      description: 'Consistency of reaction times across similar word types',
      color: 'text-green-600',
      bgColor: 'bg-green-50',
      icon: '⏱️'
    },
    {
      key: 'emotionalResonance',
      label: 'Emotional Resonance',
      value: metrics.emotionalResonance,
      description: 'Strength of emotional responses in associations',
      color: 'text-purple-600',
      bgColor: 'bg-purple-50',
      icon: '💭'
    },
    {
      key: 'clusterQuality',
      label: 'Cluster Quality',
      value: metrics.clusterQuality,
      description: 'How well words group into meaningful clusters',
      color: 'text-orange-600',
      bgColor: 'bg-orange-50',
      icon: '🔍'
    },
    {
      key: 'retrievalAccuracy',
      label: 'Retrieval Accuracy',
      value: metrics.retrievalAccuracy,
      description: 'Accuracy of semantic association retrieval',
      color: 'text-indigo-600',
      bgColor: 'bg-indigo-50',
      icon: '🎯'
    },
    {
      key: 'generationFluency',
      label: 'Generation Fluency',
      value: metrics.generationFluency,
      description: 'Fluency and naturalness of word associations',
      color: 'text-pink-600',
      bgColor: 'bg-pink-50',
      icon: '✨'
    }
  ];

  const getScoreColor = (score: number): string => {
    if (score >= 0.8) return 'text-green-600';
    if (score >= 0.6) return 'text-yellow-600';
    return 'text-red-600';
  };

  const getScoreBgColor = (score: number): string => {
    if (score >= 0.8) return 'bg-green-100';
    if (score >= 0.6) return 'bg-yellow-100';
    return 'bg-red-100';
  };

  const formatScore = (score: number): string => {
    return (score * 100).toFixed(1);
  };

  const getPerformanceLevel = (score: number): string => {
    if (score >= 0.8) return 'Excellent';
    if (score >= 0.6) return 'Good';
    if (score >= 0.4) return 'Fair';
    return 'Needs Improvement';
  };

  return (
    <div className={`bg-white p-6 rounded-lg shadow-lg border ${className}`}>
      <div className="mb-6">
        <h3 className="text-xl font-bold text-gray-800 mb-2">Jung Embedding Evaluation Metrics</h3>
        <p className="text-gray-600 text-sm">
          Comprehensive analysis of word association embedding quality and performance
        </p>
      </div>

      {/* Overall Score */}
      <div className="mb-6 p-4 bg-gradient-to-r from-blue-50 to-purple-50 rounded-lg border">
        <div className="flex items-center justify-between">
          <div>
            <h4 className="text-lg font-semibold text-gray-800">Overall Score</h4>
            <p className="text-sm text-gray-600">Comprehensive evaluation metric</p>
          </div>
          <div className="text-right">
            <div className={`text-3xl font-bold ${getScoreColor(metrics.overallScore)}`}>
              {formatScore(metrics.overallScore)}%
            </div>
            <div className={`text-sm font-medium ${getScoreColor(metrics.overallScore)}`}>
              {getPerformanceLevel(metrics.overallScore)}
            </div>
          </div>
        </div>
        
        {/* Progress Bar */}
        <div className="mt-3 w-full bg-gray-200 rounded-full h-3">
          <div
            className={`h-3 rounded-full transition-all duration-500 ${
              metrics.overallScore >= 0.8 ? 'bg-green-500' :
              metrics.overallScore >= 0.6 ? 'bg-yellow-500' : 'bg-red-500'
            }`}
            style={{ width: `${metrics.overallScore * 100}%` }}
          />
        </div>
      </div>

      {/* Individual Metrics */}
      <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
        {metricItems.map((item) => (
          <div
            key={item.key}
            className={`p-4 rounded-lg border transition-all duration-200 hover:shadow-md ${item.bgColor}`}
          >
            <div className="flex items-start justify-between mb-2">
              <div className="flex items-center">
                <span className="text-xl mr-2">{item.icon}</span>
                <div>
                  <h5 className={`font-semibold ${item.color}`}>{item.label}</h5>
                  <p className="text-xs text-gray-600 mt-1">{item.description}</p>
                </div>
              </div>
              <div className="text-right">
                <div className={`text-xl font-bold ${getScoreColor(item.value)}`}>
                  {formatScore(item.value)}%
                </div>
              </div>
            </div>
            
            {/* Mini Progress Bar */}
            <div className="w-full bg-white rounded-full h-2 mt-2">
              <div
                className={`h-2 rounded-full transition-all duration-300 ${
                  item.value >= 0.8 ? 'bg-green-400' :
                  item.value >= 0.6 ? 'bg-yellow-400' : 'bg-red-400'
                }`}
                style={{ width: `${item.value * 100}%` }}
              />
            </div>
          </div>
        ))}
      </div>

      {/* Jung-specific Insights */}
      <div className="mt-6 p-4 bg-gray-50 rounded-lg border">
        <h4 className="font-semibold text-gray-800 mb-3">🧠 Jung Analysis Insights</h4>
        <div className="space-y-2 text-sm">
          {metrics.semanticCoherence > 0.7 && (
            <div className="flex items-center text-green-700">
              <span className="mr-2">✓</span>
              Strong semantic relationships suggest well-integrated unconscious associations
            </div>
          )}
          {metrics.temporalConsistency < 0.5 && (
            <div className="flex items-center text-orange-700">
              <span className="mr-2">⚠</span>
              Inconsistent reaction times may indicate emotional complexes or conflicts
            </div>
          )}
          {metrics.emotionalResonance > 0.6 && (
            <div className="flex items-center text-purple-700">
              <span className="mr-2">💫</span>
              High emotional resonance suggests active unconscious material
            </div>
          )}
          {metrics.clusterQuality > 0.7 && (
            <div className="flex items-center text-blue-700">
              <span className="mr-2">🔍</span>
              Clear clustering patterns indicate organized psychological structures
            </div>
          )}
        </div>
      </div>

      {/* Performance Summary */}
      <div className="mt-4 p-3 bg-blue-50 rounded-lg border border-blue-200">
        <h4 className="font-semibold text-blue-800 mb-2">📊 Performance Summary</h4>
        <div className="grid grid-cols-2 gap-4 text-sm">
          <div>
            <span className="text-gray-600">Strongest Aspect:</span>
            <div className="font-medium text-blue-800">
              {metricItems.reduce((max, item) => 
                item.value > max.value ? item : max
              ).label}
            </div>
          </div>
          <div>
            <span className="text-gray-600">Improvement Area:</span>
            <div className="font-medium text-blue-800">
              {metricItems.reduce((min, item) => 
                item.value < min.value ? item : min
              ).label}
            </div>
          </div>
        </div>
      </div>

      {/* Jung Quote */}
      <div className="mt-4 p-3 bg-gradient-to-r from-gray-50 to-gray-100 rounded-lg border-l-4 border-gray-400">
        <blockquote className="text-sm italic text-gray-700">
          "The association experiment reveals to us the psychological constellation of the unconscious mind."
        </blockquote>
        <cite className="text-xs text-gray-500 mt-1 block">— Carl Gustav Jung</cite>
      </div>
    </div>
  );
};

export default EvaluationMetrics; 