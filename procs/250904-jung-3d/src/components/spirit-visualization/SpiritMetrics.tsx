"use client";

import React from "react";
import { SpiritMetrics, ConsentData } from "@/types/session";

interface SpiritMetricsProps {
  metrics?: SpiritMetrics;
  consent?: ConsentData;
}

export default function SpiritMetrics({ metrics, consent }: SpiritMetricsProps) {
  if (!metrics || !consent) {
    return (
      <div className="bg-gray-50 p-6 rounded-lg border border-gray-200">
        <div className="text-center">
          <div className="text-4xl mb-4">📊</div>
          <h3 className="text-lg font-semibold text-gray-700 mb-2">No Metrics Available</h3>
          <p className="text-gray-500">Waiting for spirit analysis data...</p>
        </div>
      </div>
    );
  }

  const reactionTimeScore = metrics.averageReactionTime > 0
    ? Math.max(0, 100 - (metrics.averageReactionTime / 20)) // Lower reaction time = higher score
    : 0;

  const delayRatio = metrics.totalResponses > 0
    ? (metrics.delayedResponses / metrics.totalResponses) * 100
    : 0;

  const delayScore = Math.max(0, 100 - delayRatio);

  const overallSpiritScore = (reactionTimeScore + delayScore) / 2;

  return (
    <div className="space-y-6">
      {/* Participant Information */}
      <div className="bg-gradient-to-r from-blue-50 to-indigo-50 p-6 rounded-lg border border-blue-200">
        <h3 className="text-xl font-semibold text-blue-900 mb-4">
          Participant Information
        </h3>
        <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
          <div>
            <p className="text-sm text-blue-700">Participant ID</p>
            <p className="font-mono text-blue-900">{metrics.participantId}</p>
          </div>
          <div>
            <p className="text-sm text-blue-700">Signature</p>
            <p className="font-medium text-blue-900">{consent.signature}</p>
          </div>
          <div>
            <p className="text-sm text-blue-700">Consent Date</p>
            <p className="text-blue-900">
              {new Date(consent.agreedAt).toLocaleDateString('ja-JP')}
            </p>
          </div>
          <div>
            <p className="text-sm text-blue-700">Session Duration</p>
            <p className="text-blue-900">
              {Math.round((metrics.sessionEndTime - metrics.sessionStartTime) / 1000)}s
            </p>
          </div>
        </div>
      </div>

      {/* Spirit Metrics */}
      <div className="bg-gradient-to-r from-green-50 to-emerald-50 p-6 rounded-lg border border-green-200">
        <h3 className="text-xl font-semibold text-green-900 mb-4">
          Spirit Analysis Metrics
        </h3>

        <div className="grid grid-cols-1 md:grid-cols-3 gap-6">
          {/* Overall Spirit Score */}
          <div className="text-center">
            <div className="relative w-24 h-24 mx-auto mb-2">
              <svg className="w-24 h-24 transform -rotate-90" viewBox="0 0 24 24">
                <circle
                  cx="12"
                  cy="12"
                  r="10"
                  stroke="#e5e7eb"
                  strokeWidth="2"
                  fill="none"
                />
                <circle
                  cx="12"
                  cy="12"
                  r="10"
                  stroke="#10b981"
                  strokeWidth="2"
                  fill="none"
                  strokeDasharray={`${overallSpiritScore * 0.628} 6.28`}
                  strokeLinecap="round"
                />
              </svg>
              <div className="absolute inset-0 flex items-center justify-center">
                <span className="text-lg font-bold text-green-800">
                  {overallSpiritScore.toFixed(0)}
                </span>
              </div>
            </div>
            <p className="text-sm text-green-700">Overall Spirit Score</p>
          </div>

          {/* Reaction Time */}
          <div className="text-center">
            <div className="text-2xl font-bold text-green-800 mb-1">
              {metrics.averageReactionTime.toFixed(0)}ms
            </div>
            <p className="text-sm text-green-700">Avg Reaction Time</p>
            <div className="mt-2 bg-green-200 rounded-full h-2">
              <div
                className="bg-green-600 h-2 rounded-full"
                style={{ width: `${Math.min(100, (2000 - metrics.averageReactionTime) / 20)}%` }}
              />
            </div>
          </div>

          {/* Delayed Responses */}
          <div className="text-center">
            <div className="text-2xl font-bold text-green-800 mb-1">
              {delayRatio.toFixed(1)}%
            </div>
            <p className="text-sm text-green-700">Delayed Responses</p>
            <div className="mt-2 bg-green-200 rounded-full h-2">
              <div
                className="bg-green-600 h-2 rounded-full"
                style={{ width: `${100 - delayRatio}%` }}
              />
            </div>
          </div>
        </div>

        {/* Detailed Stats */}
        <div className="mt-6 grid grid-cols-2 md:grid-cols-4 gap-4 text-sm">
          <div className="bg-white p-3 rounded border">
            <div className="font-medium text-green-800">Total Responses</div>
            <div className="text-green-600">{metrics.totalResponses}</div>
          </div>
          <div className="bg-white p-3 rounded border">
            <div className="font-medium text-green-800">Delayed Count</div>
            <div className="text-green-600">{metrics.delayedResponses}</div>
          </div>
          <div className="font-medium text-green-800">Reaction Score</div>
          <div className="text-green-600">{reactionTimeScore.toFixed(1)}/100</div>
          <div className="bg-white p-3 rounded border">
            <div className="font-medium text-green-800">Delay Score</div>
            <div className="text-green-600">{delayScore.toFixed(1)}/100</div>
          </div>
        </div>
      </div>

      {/* Consent Status */}
      <div className="bg-gradient-to-r from-purple-50 to-pink-50 p-6 rounded-lg border border-purple-200">
        <h3 className="text-xl font-semibold text-purple-900 mb-4">
          Consent Status
        </h3>
        <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
          {Object.entries(consent.agreements).map(([key, value]) => (
            <div key={key} className="flex items-center space-x-2">
              <div className={`w-3 h-3 rounded-full ${value ? 'bg-green-500' : 'bg-red-500'}`} />
              <span className="text-sm text-purple-700 capitalize">
                {key === 'understand' ? '理解' :
                 key === 'voluntary' ? '自発性' :
                 key === 'withdraw' ? '撤回権' : '記録同意'}
              </span>
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}
