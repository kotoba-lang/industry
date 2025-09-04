"use client";

import React, { useEffect, useRef, useState } from 'react';
import { EmbeddingPoint, AssociationConnection } from './types';

interface AssociationHeatmapProps {
  points: EmbeddingPoint[];
  connections: AssociationConnection[];
  width?: number;
  height?: number;
  className?: string;
}

interface HeatmapCell {
  x: number;
  y: number;
  value: number;
  sourceWord: string;
  targetWord: string;
  reactionTime?: number;
  connectionType: 'semantic' | 'temporal' | 'emotional';
}

const AssociationHeatmap: React.FC<AssociationHeatmapProps> = ({
  points,
  connections,
  width = 600,
  height = 600,
  className = ''
}) => {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const [hoveredCell, setHoveredCell] = useState<HeatmapCell | null>(null);
  const [selectedFilter, setSelectedFilter] = useState<'all' | 'semantic' | 'temporal' | 'emotional'>('all');

  useEffect(() => {
    drawHeatmap();
  }, [points, connections, selectedFilter, width, height]);

  const createHeatmapData = (): HeatmapCell[] => {
    const heatmapData: HeatmapCell[] = [];
    const wordIndices = new Map<string, number>();
    
    // Create word index mapping
    points.forEach((point, index) => {
      wordIndices.set(point.word, index);
    });

    // Initialize matrix
    const matrixSize = points.length;
    const matrix: number[][] = Array(matrixSize).fill(null).map(() => Array(matrixSize).fill(0));
    const connectionMatrix: Map<string, AssociationConnection> = new Map();

    // Fill matrix with connection strengths
    connections.forEach(connection => {
      const sourceIndex = wordIndices.get(
        points.find(p => p.id === connection.source)?.word || ''
      );
      const targetIndex = wordIndices.get(
        points.find(p => p.id === connection.target)?.word || ''
      );

      if (sourceIndex !== undefined && targetIndex !== undefined) {
        matrix[sourceIndex][targetIndex] = connection.strength;
        connectionMatrix.set(`${sourceIndex}-${targetIndex}`, connection);
      }
    });

    // Convert matrix to heatmap cells
    for (let i = 0; i < matrixSize; i++) {
      for (let j = 0; j < matrixSize; j++) {
        const connection = connectionMatrix.get(`${i}-${j}`);
        if (matrix[i][j] > 0 || i === j) {
          heatmapData.push({
            x: j,
            y: i,
            value: matrix[i][j],
            sourceWord: points[i]?.word || '',
            targetWord: points[j]?.word || '',
            reactionTime: connection?.reactionTime,
            connectionType: connection?.type || 'semantic'
          });
        }
      }
    }

    return heatmapData;
  };

  const drawHeatmap = () => {
    const canvas = canvasRef.current;
    if (!canvas) return;

    const ctx = canvas.getContext('2d');
    if (!ctx) return;

    // Clear canvas
    ctx.clearRect(0, 0, canvas.width, canvas.height);

    const heatmapData = createHeatmapData();
    const matrixSize = points.length;
    
    if (matrixSize === 0) return;

    const cellSize = Math.min(width, height) / (matrixSize + 2); // +2 for labels
    const startX = cellSize;
    const startY = cellSize;

    // Filter data based on selected filter
    const filteredData = selectedFilter === 'all' 
      ? heatmapData 
      : heatmapData.filter(cell => cell.connectionType === selectedFilter);

    // Draw cells
    filteredData.forEach(cell => {
      const x = startX + cell.x * cellSize;
      const y = startY + cell.y * cellSize;
      
      // Get color based on value and connection type
      const color = getCellColor(cell.value, cell.connectionType);
      
      ctx.fillStyle = color;
      ctx.fillRect(x, y, cellSize - 1, cellSize - 1);

      // Add border for diagonal (self-association)
      if (cell.x === cell.y) {
        ctx.strokeStyle = '#2c3e50';
        ctx.lineWidth = 2;
        ctx.strokeRect(x, y, cellSize - 1, cellSize - 1);
      }

      // Highlight hovered cell
      if (hoveredCell && hoveredCell.x === cell.x && hoveredCell.y === cell.y) {
        ctx.strokeStyle = '#ff6b6b';
        ctx.lineWidth = 3;
        ctx.strokeRect(x - 1, y - 1, cellSize + 1, cellSize + 1);
      }
    });

    // Draw labels
    drawLabels(ctx, cellSize, startX, startY, matrixSize);
    
    // Draw legend
    drawLegend(ctx, width - 150, 20);
  };

  const drawLabels = (ctx: CanvasRenderingContext2D, cellSize: number, startX: number, startY: number, matrixSize: number) => {
    ctx.font = `${Math.max(8, cellSize * 0.3)}px Arial`;
    ctx.fillStyle = '#2c3e50';
    ctx.textAlign = 'center';
    ctx.textBaseline = 'middle';

    // Draw column labels (top)
    for (let i = 0; i < matrixSize && i < points.length; i++) {
      const x = startX + i * cellSize + cellSize / 2;
      const y = cellSize / 2;
      const word = points[i]?.word || '';
      
      // Truncate long words
      const displayWord = word.length > 8 ? word.substring(0, 6) + '...' : word;
      
      ctx.save();
      ctx.translate(x, y);
      ctx.rotate(-Math.PI / 4);
      ctx.fillText(displayWord, 0, 0);
      ctx.restore();
    }

    // Draw row labels (left)
    ctx.textAlign = 'right';
    for (let i = 0; i < matrixSize && i < points.length; i++) {
      const x = cellSize * 0.9;
      const y = startY + i * cellSize + cellSize / 2;
      const word = points[i]?.word || '';
      
      // Truncate long words
      const displayWord = word.length > 8 ? word.substring(0, 6) + '...' : word;
      
      ctx.fillText(displayWord, x, y);
    }
  };

  const drawLegend = (ctx: CanvasRenderingContext2D, x: number, y: number) => {
    const legendWidth = 120;
    const legendHeight = 80;
    
    // Background
    ctx.fillStyle = 'rgba(255, 255, 255, 0.9)';
    ctx.fillRect(x, y, legendWidth, legendHeight);
    ctx.strokeStyle = '#ddd';
    ctx.strokeRect(x, y, legendWidth, legendHeight);

    // Title
    ctx.font = '12px Arial';
    ctx.fillStyle = '#2c3e50';
    ctx.textAlign = 'center';
    ctx.fillText('Association Strength', x + legendWidth / 2, y + 15);

    // Color gradient
    const gradientHeight = 20;
    const gradientY = y + 25;
    
    for (let i = 0; i < legendWidth - 20; i++) {
      const value = i / (legendWidth - 20);
      ctx.fillStyle = getCellColor(value, 'semantic');
      ctx.fillRect(x + 10 + i, gradientY, 1, gradientHeight);
    }

    // Labels
    ctx.font = '10px Arial';
    ctx.textAlign = 'left';
    ctx.fillStyle = '#666';
    ctx.fillText('0', x + 10, gradientY + gradientHeight + 15);
    ctx.textAlign = 'right';
    ctx.fillText('1', x + legendWidth - 10, gradientY + gradientHeight + 15);
  };

  const getCellColor = (value: number, type: 'semantic' | 'temporal' | 'emotional'): string => {
    if (value === 0) return '#f8f9fa';
    
    const intensity = Math.min(1, Math.max(0, value));
    const alpha = 0.3 + intensity * 0.7;
    
    switch (type) {
      case 'semantic':
        return `rgba(76, 175, 80, ${alpha})`; // Green
      case 'temporal':
        return `rgba(255, 152, 0, ${alpha})`; // Orange
      case 'emotional':
        return `rgba(156, 39, 176, ${alpha})`; // Purple
      default:
        return `rgba(33, 150, 243, ${alpha})`; // Blue
    }
  };

  const handleMouseMove = (event: React.MouseEvent<HTMLCanvasElement>) => {
    const canvas = canvasRef.current;
    if (!canvas) return;

    const rect = canvas.getBoundingClientRect();
    const x = event.clientX - rect.left;
    const y = event.clientY - rect.top;

    const matrixSize = points.length;
    const cellSize = Math.min(width, height) / (matrixSize + 2);
    const startX = cellSize;
    const startY = cellSize;

    const cellX = Math.floor((x - startX) / cellSize);
    const cellY = Math.floor((y - startY) / cellSize);

    if (cellX >= 0 && cellX < matrixSize && cellY >= 0 && cellY < matrixSize) {
      const heatmapData = createHeatmapData();
      const cell = heatmapData.find(c => c.x === cellX && c.y === cellY);
      setHoveredCell(cell || null);
    } else {
      setHoveredCell(null);
    }
  };

  const handleMouseLeave = () => {
    setHoveredCell(null);
  };

  return (
    <div className={`relative ${className}`}>
      {/* Filter Controls */}
      <div className="mb-4 flex gap-2">
        {['all', 'semantic', 'temporal', 'emotional'].map(filter => (
          <button
            key={filter}
            onClick={() => setSelectedFilter(filter as any)}
            className={`px-3 py-1 rounded text-sm font-medium transition-colors ${
              selectedFilter === filter
                ? 'bg-blue-500 text-white'
                : 'bg-gray-200 text-gray-700 hover:bg-gray-300'
            }`}
          >
            {filter.charAt(0).toUpperCase() + filter.slice(1)}
          </button>
        ))}
      </div>

      {/* Canvas */}
      <div className="relative">
        <canvas
          ref={canvasRef}
          width={width}
          height={height}
          className="border border-gray-300 rounded-lg shadow cursor-crosshair"
          onMouseMove={handleMouseMove}
          onMouseLeave={handleMouseLeave}
        />

        {/* Tooltip */}
        {hoveredCell && (
          <div className="absolute top-2 left-2 bg-white p-3 rounded shadow-lg border text-sm max-w-xs z-10">
            <div className="font-bold text-gray-800">
              {hoveredCell.sourceWord} → {hoveredCell.targetWord}
            </div>
            <div className="mt-1">
              <span className="text-gray-600">Strength:</span> 
              <span className="font-medium ml-1">{(hoveredCell.value * 100).toFixed(1)}%</span>
            </div>
            <div>
              <span className="text-gray-600">Type:</span> 
              <span className="font-medium ml-1 capitalize">{hoveredCell.connectionType}</span>
            </div>
            {hoveredCell.reactionTime && (
              <div>
                <span className="text-gray-600">Reaction Time:</span> 
                <span className="font-medium ml-1">{hoveredCell.reactionTime}ms</span>
              </div>
            )}
          </div>
        )}
      </div>

      {/* Description */}
      <div className="mt-4 text-sm text-gray-600">
        <p>
          This heatmap shows the strength of associations between words. 
          Darker colors indicate stronger associations. 
          Use the filter buttons to view different types of connections.
        </p>
      </div>
    </div>
  );
};

export default AssociationHeatmap; 