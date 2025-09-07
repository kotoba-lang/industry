"use client";

import React, { useMemo } from "react";
import { TimeSeriesPoint } from "@/types/vector";

interface SpiritTimeSeriesProps {
  timeSeries: TimeSeriesPoint[];
  width?: number;
  height?: number;
}

export default function SpiritTimeSeries({
  timeSeries = [],
  width = 800,
  height = 300
}: SpiritTimeSeriesProps) {
  const safeTimeSeries = timeSeries || [];

  const { energyPath, entropyPath, timeLabels } = useMemo(() => {
    if (safeTimeSeries.length === 0) return { energyPath: "", entropyPath: "", timeLabels: [] };

    const startTime = safeTimeSeries[0].timestamp;
    const endTime = safeTimeSeries[safeTimeSeries.length - 1].timestamp;
    const timeRange = endTime - startTime;

    const maxEnergy = Math.max(...safeTimeSeries.map(p => p.energy));
    const maxEntropy = Math.max(...safeTimeSeries.map(p => p.entropy));

    const energyPoints: string[] = [];
    const entropyPoints: string[] = [];
    const labels: string[] = [];

    safeTimeSeries.forEach((point, index) => {
      const x = (point.timestamp - startTime) / timeRange * width;
      const energyY = height - (point.energy / maxEnergy) * (height * 0.8);
      const entropyY = height - (point.entropy / maxEntropy) * (height * 0.8);

      energyPoints.push(`${index === 0 ? 'M' : 'L'} ${x} ${energyY}`);
      entropyPoints.push(`${index === 0 ? 'M' : 'L'} ${x} ${entropyY}`);

      // Add time labels every 10 points
      if (index % 10 === 0) {
        const time = new Date(point.timestamp).toLocaleTimeString();
        labels.push({
          x,
          label: time,
          energyY,
          entropyY
        });
      }
    });

    return {
      energyPath: energyPoints.join(' '),
      entropyPath: entropyPoints.join(' '),
      timeLabels: labels
    };
  }, [safeTimeSeries, width, height]);

  if (safeTimeSeries.length === 0) {
    return (
      <div className="w-full h-64 bg-gray-100 rounded-lg flex items-center justify-center">
        <p className="text-gray-500">No time series data available</p>
      </div>
    );
  }

  return (
    <div className="w-full bg-white rounded-lg p-4 shadow-lg">
      <h3 className="text-lg font-semibold mb-4 text-gray-800">
        Spirit Energy & Entropy Over Time
      </h3>

      <svg width={width} height={height} className="border border-gray-200 rounded">
        {/* Grid lines */}
        <defs>
          <pattern id="grid" width="40" height="40" patternUnits="userSpaceOnUse">
            <path d="M 40 0 L 0 0 0 40" fill="none" stroke="#f0f0f0" strokeWidth="1"/>
          </pattern>
        </defs>
        <rect width="100%" height="100%" fill="url(#grid)" />

        {/* Energy line */}
        <path
          d={energyPath}
          fill="none"
          stroke="#3b82f6"
          strokeWidth="2"
          strokeLinecap="round"
          strokeLinejoin="round"
        />

        {/* Entropy line */}
        <path
          d={entropyPath}
          fill="none"
          stroke="#ef4444"
          strokeWidth="2"
          strokeLinecap="round"
          strokeLinejoin="round"
          strokeDasharray="5,5"
        />

        {/* Time labels */}
        {timeLabels.map((label, index) => (
          <g key={index}>
            <text
              x={label.x}
              y={height - 10}
              textAnchor="middle"
              className="text-xs fill-gray-600"
              transform={`rotate(-45, ${label.x}, ${height - 10})`}
            >
              {label.label}
            </text>
          </g>
        ))}
      </svg>

      {/* Legend */}
      <div className="flex justify-center mt-4 space-x-6">
        <div className="flex items-center">
          <div className="w-4 h-1 bg-blue-500 rounded mr-2"></div>
          <span className="text-sm text-gray-600">Energy</span>
        </div>
        <div className="flex items-center">
          <div className="w-4 h-1 bg-red-500 rounded mr-2 border-dashed border-red-500"></div>
          <span className="text-sm text-gray-600">Entropy</span>
        </div>
      </div>

      {/* Statistics */}
      <div className="grid grid-cols-2 gap-4 mt-4 text-sm">
        <div className="bg-blue-50 p-3 rounded">
          <div className="font-medium text-blue-800">Average Energy</div>
          <div className="text-blue-600">
            {(timeSeries.reduce((sum, p) => sum + p.energy, 0) / timeSeries.length).toFixed(4)}
          </div>
        </div>
        <div className="bg-red-50 p-3 rounded">
          <div className="font-medium text-red-800">Average Entropy</div>
          <div className="text-red-600">
            {(timeSeries.reduce((sum, p) => sum + p.entropy, 0) / timeSeries.length).toFixed(4)}
          </div>
        </div>
      </div>
    </div>
  );
}
