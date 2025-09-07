"use client";

import { useState, useEffect } from "react";
import { SpiritAnalysisResult } from "@/types/session";
import { analyzeSessionData } from "@/lib/sessionParser";
import Spirit3DVisualization from "@/components/spirit-visualization/Spirit3DVisualization";
import SpiritTimeSeries from "@/components/spirit-visualization/SpiritTimeSeries";
import SpiritMetrics from "@/components/spirit-visualization/SpiritMetrics";
import KawasakiModelMath from "@/components/spirit-visualization/KawasakiModelMath";

export default function SpiritInPhysicsVisualization() {
  const [analysisResult, setAnalysisResult] = useState<SpiritAnalysisResult | null>({
    metrics: {
      participantId: '',
      totalResponses: 0,
      averageReactionTime: 0,
      delayedResponses: 0,
      wordAssociations: [],
      sessionStartTime: 0,
      sessionEndTime: 0
    },
    emotions: [],
    consent: {
      participantId: '',
      signature: '',
      agreements: {
        understand: false,
        voluntary: false,
        withdraw: false,
        recording: false
      },
      agreedAt: ''
    },
    kawasakiModelData: {
      energy: 0,
      vectors: [],
      timeSeries: []
    }
  });
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [activeTab, setActiveTab] = useState<'3d' | 'metrics' | 'timeseries' | 'math'>('math');

  useEffect(() => {
    async function loadData() {
      try {
        setLoading(true);

        // Load session and consent data
        const [sessionResponse, consentResponse] = await Promise.all([
          fetch('/api/session-data'),
          fetch('/api/consent-data')
        ]);

        if (!sessionResponse.ok) {
          const errorData = await sessionResponse.json().catch(() => ({ error: 'Unknown error' }));
          throw new Error(`Session data error: ${errorData.error}`);
        }

        if (!consentResponse.ok) {
          const errorData = await consentResponse.json().catch(() => ({ error: 'Unknown error' }));
          throw new Error(`Consent data error: ${errorData.error}`);
        }

        const sessionData = await sessionResponse.json();
        const consentData = await consentResponse.json();

        // Validate data structure
        if (!sessionData.events || !Array.isArray(sessionData.events)) {
          throw new Error('Invalid session data structure');
        }

        if (!consentData.agreements) {
          throw new Error('Invalid consent data structure');
        }

        // Analyze the data
        const result = analyzeSessionData(sessionData, consentData);
        console.log('Analysis result:', {
          hasKawasakiModelData: !!result.kawasakiModelData,
          vectorsCount: Array.isArray(result.kawasakiModelData?.vectors) ? result.kawasakiModelData.vectors.length : 'not array',
          timeSeriesCount: Array.isArray(result.kawasakiModelData?.timeSeries) ? result.kawasakiModelData.timeSeries.length : 'not array',
          metrics: result.metrics,
        });
        setAnalysisResult(result);
      } catch (err) {
        console.error('Error loading data:', err);
        setError(err instanceof Error ? err.message : 'Unknown error occurred');
      } finally {
        setLoading(false);
      }
    }

    loadData();
  }, []);

  // Switch to 3D tab when data is loaded
  useEffect(() => {
    if (analysisResult?.kawasakiModelData && Array.isArray(analysisResult.kawasakiModelData.vectors) && activeTab === 'math') {
      setActiveTab('3d');
    }
  }, [analysisResult, activeTab]);

  if (loading) {
    return (
      <div className="min-h-screen bg-gradient-to-br from-slate-50 to-blue-50 flex items-center justify-center">
        <div className="text-center">
          <div className="animate-spin rounded-full h-12 w-12 border-b-2 border-blue-600 mx-auto mb-4"></div>
          <p className="text-gray-600">Loading Spirit in Physics data...</p>
        </div>
      </div>
    );
  }

  if (error) {
    return (
      <div className="min-h-screen bg-gradient-to-br from-red-50 to-pink-50 flex items-center justify-center">
        <div className="text-center max-w-md">
          <div className="text-red-500 text-6xl mb-4">⚠️</div>
          <h1 className="text-2xl font-bold text-red-800 mb-2">Error Loading Data</h1>
          <p className="text-red-600">{error}</p>
        </div>
      </div>
    );
  }

  if (!analysisResult) {
    return (
      <div className="min-h-screen bg-gradient-to-br from-gray-50 to-slate-50 flex items-center justify-center">
        <div className="text-center">
          <p className="text-gray-600">No data available</p>
        </div>
      </div>
    );
  }

  return (
    <div className="min-h-screen bg-gradient-to-br from-slate-50 to-blue-50">
      {/* Header */}
      <header className="bg-white shadow-lg border-b border-blue-100">
        <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-6">
          <div className="flex items-center justify-between">
            <div>
              <h1 className="text-3xl font-bold text-gray-900">
                Spirit in Physics
              </h1>
              <p className="text-gray-600 mt-1">
                Kawasaki Model - 3D Spirit Visualization
              </p>
            </div>
            <div className="text-right">
              <p className="text-sm text-gray-500">Participant</p>
              <p className="font-mono text-gray-900">
                {analysisResult?.metrics.participantId || 'Loading...'}
              </p>
            </div>
          </div>
        </div>
      </header>

      {/* Navigation Tabs */}
      <nav className="bg-white border-b border-gray-200">
        <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8">
          <div className="flex space-x-8">
            {[
              ...(analysisResult?.kawasakiModelData && Array.isArray(analysisResult.kawasakiModelData.vectors) ? [{ id: '3d', label: '3D Visualization', icon: '🌌' }] : []),
              ...(analysisResult ? [{ id: 'metrics', label: 'Spirit Metrics', icon: '📊' }] : []),
              ...(analysisResult?.kawasakiModelData && Array.isArray(analysisResult.kawasakiModelData.timeSeries) ? [{ id: 'timeseries', label: 'Time Series', icon: '📈' }] : []),
              { id: 'math', label: 'Kawasaki Model', icon: '⚛️' }
            ].map((tab) => (
              <button
                key={tab.id}
                onClick={() => setActiveTab(tab.id as any)}
                disabled={
                  (tab.id === '3d') && (!analysisResult?.kawasakiModelData || !Array.isArray(analysisResult.kawasakiModelData.vectors)) ||
                  (tab.id === 'metrics') && !analysisResult ||
                  (tab.id === 'timeseries') && (!analysisResult?.kawasakiModelData || !Array.isArray(analysisResult.kawasakiModelData.timeSeries)) ||
                  (tab.id === 'math') ? false : false
                }
                className={`flex items-center py-4 px-1 border-b-2 font-medium text-sm ${
                  activeTab === tab.id
                    ? 'border-blue-500 text-blue-600'
                    : (tab.id === '3d') && (!analysisResult?.kawasakiModelData || !Array.isArray(analysisResult.kawasakiModelData.vectors)) ||
                      (tab.id === 'metrics') && !analysisResult ||
                      (tab.id === 'timeseries') && (!analysisResult?.kawasakiModelData || !Array.isArray(analysisResult.kawasakiModelData.timeSeries))
                    ? 'border-transparent text-gray-300 cursor-not-allowed'
                    : 'border-transparent text-gray-500 hover:text-gray-700 hover:border-gray-300'
                }`}
              >
                <span className="mr-2">{tab.icon}</span>
                {tab.label}
              </button>
            ))}
          </div>
        </div>
      </nav>

      {/* Main Content */}
      <main className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-8">
        {activeTab === '3d' && analysisResult?.kawasakiModelData && Array.isArray(analysisResult.kawasakiModelData.vectors) && (
          <div className="space-y-6">
            <div className="bg-white rounded-lg shadow-lg p-6">
              <h2 className="text-2xl font-bold text-gray-900 mb-4">
                3D Spirit Vector Space
              </h2>
              <p className="text-gray-600 mb-6">
                Interactive 3D visualization of spirit vectors based on the Kawasaki Model.
                Each point represents a word association with energy-based coloring and positioning.
              </p>
              <Spirit3DVisualization
                vectors={analysisResult.kawasakiModelData.vectors}
                showLabels={true}
                animate={true}
              />
            </div>
          </div>
        )}

        {activeTab === '3d' && analysisResult && !analysisResult.kawasakiModelData && !loading && (
          <div className="space-y-6">
            <div className="bg-white rounded-lg shadow-lg p-6">
              <div className="text-center py-12">
                <div className="text-6xl mb-4">⚠️</div>
                <h3 className="text-2xl font-bold text-gray-900 mb-4">
                  No Spirit Data Available
                </h3>
                <p className="text-gray-600 mb-6">
                  The session data was loaded successfully, but no valid word associations
                  were found for 3D visualization. This might be due to missing reaction time data.
                </p>
                <p className="text-sm text-gray-500">
                  Try checking the session data or contact support if this persists.
                </p>
              </div>
            </div>
          </div>
        )}

        {activeTab === 'metrics' && analysisResult && (
          <div className="space-y-6">
            <SpiritMetrics
              metrics={analysisResult.metrics}
              consent={analysisResult.consent}
            />
          </div>
        )}

        {activeTab === 'timeseries' && analysisResult?.kawasakiModelData && Array.isArray(analysisResult.kawasakiModelData.timeSeries) && (
          <div className="space-y-6">
            <div className="bg-white rounded-lg shadow-lg">
              <SpiritTimeSeries
                timeSeries={analysisResult.kawasakiModelData.timeSeries}
                width={800}
                height={400}
              />
            </div>
          </div>
        )}

        {activeTab === 'math' && (
          <div className="space-y-6">
            <KawasakiModelMath />
          </div>
        )}

      </main>

      {/* Footer */}
      <footer className="bg-white border-t border-gray-200 mt-16">
        <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-8">
          <div className="text-center">
            <h3 className="text-lg font-semibold text-gray-900 mb-2">
              Spirit in Physics Research
            </h3>
            <p className="text-gray-600 text-sm mb-4">
              Kawasaki Model implementation for quantifying human spirit through physical measurements
            </p>
            <div className="text-xs text-gray-500">
              <p>© 2024 Jun Kawasaki - Graduate School of Medical and Dental Sciences, Niigata University</p>
              <p>Based on Jung's Word Association Test and Information Physics principles</p>
            </div>
          </div>
        </div>
      </footer>
    </div>
  );
}
