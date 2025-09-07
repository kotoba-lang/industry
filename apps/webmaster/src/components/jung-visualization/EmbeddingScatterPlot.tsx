"use client";

import React, { useEffect, useRef, useState } from 'react';
import { EmbeddingPoint, AssociationConnection, Cluster, AnimationState, VisualizationConfig } from './types';

interface EmbeddingScatterPlotProps {
  points: EmbeddingPoint[];
  connections: AssociationConnection[];
  clusters: Cluster[];
  animationState: AnimationState;
  config: VisualizationConfig;
  onPointClick?: (point: EmbeddingPoint) => void;
  onPointHover?: (point: EmbeddingPoint | null) => void;
}

const EmbeddingScatterPlot: React.FC<EmbeddingScatterPlotProps> = ({
  points,
  connections,
  clusters,
  animationState,
  config,
  onPointClick,
  onPointHover
}) => {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const [hoveredPoint, setHoveredPoint] = useState<EmbeddingPoint | null>(null);
  const [selectedPoint, setSelectedPoint] = useState<EmbeddingPoint | null>(null);
  const animationFrameRef = useRef<number>();

  // Animation frame handler
  useEffect(() => {
    const animate = () => {
      if (canvasRef.current) {
        drawVisualization();
      }
      if (animationState.isPlaying) {
        animationFrameRef.current = requestAnimationFrame(animate);
      }
    };

    if (animationState.isPlaying) {
      animationFrameRef.current = requestAnimationFrame(animate);
    } else {
      drawVisualization();
    }

    return () => {
      if (animationFrameRef.current) {
        cancelAnimationFrame(animationFrameRef.current);
      }
    };
  }, [points, connections, clusters, animationState, config, hoveredPoint, selectedPoint]);

  // Handle mouse events
  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;

    const handleMouseMove = (event: MouseEvent) => {
      const rect = canvas.getBoundingClientRect();
      const x = event.clientX - rect.left;
      const y = event.clientY - rect.top;
      
      const point = findPointAt(x, y);
      setHoveredPoint(point);
      onPointHover?.(point);
    };

    const handleMouseClick = (event: MouseEvent) => {
      const rect = canvas.getBoundingClientRect();
      const x = event.clientX - rect.left;
      const y = event.clientY - rect.top;
      
      const point = findPointAt(x, y);
      setSelectedPoint(point);
      if (point) {
        onPointClick?.(point);
      }
    };

    canvas.addEventListener('mousemove', handleMouseMove);
    canvas.addEventListener('click', handleMouseClick);

    return () => {
      canvas.removeEventListener('mousemove', handleMouseMove);
      canvas.removeEventListener('click', handleMouseClick);
    };
  }, [points, onPointClick, onPointHover]);

  const findPointAt = (x: number, y: number): EmbeddingPoint | null => {
    const canvas = canvasRef.current;
    if (!canvas) return null;

    const centerX = canvas.width / 2;
    const centerY = canvas.height / 2;
    const scale = Math.min(canvas.width, canvas.height) / 400;

    for (const point of points) {
      const pointX = centerX + point.x * scale;
      const pointY = centerY + point.y * scale;
      const distance = Math.sqrt(Math.pow(x - pointX, 2) + Math.pow(y - pointY, 2));
      
      if (distance <= config.pointSize + 2) {
        return point;
      }
    }

    return null;
  };

  const drawVisualization = () => {
    const canvas = canvasRef.current;
    if (!canvas) return;

    const ctx = canvas.getContext('2d');
    if (!ctx) return;

    // Clear canvas
    ctx.clearRect(0, 0, canvas.width, canvas.height);

    const centerX = canvas.width / 2;
    const centerY = canvas.height / 2;
    const scale = Math.min(canvas.width, canvas.height) / 400;

    // Draw clusters first (background)
    if (config.showClusters) {
      drawClusters(ctx, centerX, centerY, scale);
    }

    // Draw connections
    if (config.showConnections) {
      drawConnections(ctx, centerX, centerY, scale);
    }

    // Draw points
    drawPoints(ctx, centerX, centerY, scale);

    // Draw labels
    if (config.showLabels) {
      drawLabels(ctx, centerX, centerY, scale);
    }

    // Draw animation phase indicator
    drawPhaseIndicator(ctx);
  };

  const drawClusters = (ctx: CanvasRenderingContext2D, centerX: number, centerY: number, scale: number) => {
    clusters.forEach(cluster => {
      const clusterPoints = points.filter(p => p.cluster === parseInt(cluster.id.split('_')[1]));
      if (clusterPoints.length === 0) return;

      // Calculate cluster bounds
      const minX = Math.min(...clusterPoints.map(p => p.x));
      const maxX = Math.max(...clusterPoints.map(p => p.x));
      const minY = Math.min(...clusterPoints.map(p => p.y));
      const maxY = Math.max(...clusterPoints.map(p => p.y));

      const x = centerX + minX * scale - 10;
      const y = centerY + minY * scale - 10;
      const width = (maxX - minX) * scale + 20;
      const height = (maxY - minY) * scale + 20;

      // Draw cluster background
      ctx.fillStyle = cluster.color + Math.floor(config.clusterOpacity * 255).toString(16).padStart(2, '0');
      ctx.fillRect(x, y, width, height);

      // Draw cluster border
      ctx.strokeStyle = cluster.color;
      ctx.lineWidth = 1;
      ctx.strokeRect(x, y, width, height);
    });
  };

  const drawConnections = (ctx: CanvasRenderingContext2D, centerX: number, centerY: number, scale: number) => {
    connections.forEach(connection => {
      const sourcePoint = points.find(p => p.id === connection.source);
      const targetPoint = points.find(p => p.id === connection.target);
      
      if (!sourcePoint || !targetPoint) return;

      const x1 = centerX + sourcePoint.x * scale;
      const y1 = centerY + sourcePoint.y * scale;
      const x2 = centerX + targetPoint.x * scale;
      const y2 = centerY + targetPoint.y * scale;

      // Set connection style based on type
      ctx.strokeStyle = getConnectionColor(connection);
      ctx.lineWidth = Math.max(1, connection.strength * 3);
      ctx.globalAlpha = config.connectionOpacity;

      // Draw connection line
      ctx.beginPath();
      ctx.moveTo(x1, y1);
      ctx.lineTo(x2, y2);
      ctx.stroke();

      // Draw arrow for directionality
      if (connection.strength > 0.5) {
        drawArrow(ctx, x1, y1, x2, y2);
      }

      ctx.globalAlpha = 1.0;
    });
  };

  const drawPoints = (ctx: CanvasRenderingContext2D, centerX: number, centerY: number, scale: number) => {
    points.forEach(point => {
      const x = centerX + point.x * scale;
      const y = centerY + point.y * scale;
      
      // Determine point color based on color scheme
      const color = getPointColor(point);
      const size = getPointSize(point);
      
      // Draw point shadow for depth
      ctx.fillStyle = 'rgba(0, 0, 0, 0.2)';
      ctx.beginPath();
      ctx.arc(x + 2, y + 2, size, 0, 2 * Math.PI);
      ctx.fill();

      // Draw point
      ctx.fillStyle = color;
      ctx.beginPath();
      ctx.arc(x, y, size, 0, 2 * Math.PI);
      ctx.fill();

      // Draw point border
      ctx.strokeStyle = getPointBorderColor(point);
      ctx.lineWidth = 2;
      ctx.stroke();

      // Highlight hovered point
      if (hoveredPoint === point) {
        ctx.strokeStyle = '#ff6b6b';
        ctx.lineWidth = 3;
        ctx.stroke();
      }

      // Highlight selected point
      if (selectedPoint === point) {
        ctx.strokeStyle = '#4ecdc4';
        ctx.lineWidth = 4;
        ctx.stroke();
      }
    });
  };

  const drawLabels = (ctx: CanvasRenderingContext2D, centerX: number, centerY: number, scale: number) => {
    ctx.font = '12px Arial';
    ctx.textAlign = 'center';
    ctx.textBaseline = 'middle';

    points.forEach(point => {
      const x = centerX + point.x * scale;
      const y = centerY + point.y * scale;
      
      // Draw label background
      const textWidth = ctx.measureText(point.word).width;
      ctx.fillStyle = 'rgba(255, 255, 255, 0.8)';
      ctx.fillRect(x - textWidth / 2 - 2, y - 20, textWidth + 4, 16);

      // Draw label text
      ctx.fillStyle = '#333';
      ctx.fillText(point.word, x, y - 12);
    });
  };

  const drawPhaseIndicator = (ctx: CanvasRenderingContext2D) => {
    const phaseColors = {
      'initialization': '#ff9999',
      'clustering': '#99ccff',
      'association': '#99ff99',
      'evaluation': '#ffcc99',
      'complete': '#cc99ff'
    };

    ctx.fillStyle = phaseColors[animationState.currentPhase];
    ctx.fillRect(10, 10, 20, 20);
    
    ctx.fillStyle = '#333';
    ctx.font = '14px Arial';
    ctx.textAlign = 'left';
    ctx.fillText(animationState.currentPhase.toUpperCase(), 40, 25);

    // Progress bar
    const progress = animationState.frame / animationState.totalFrames;
    ctx.fillStyle = '#ddd';
    ctx.fillRect(10, 40, 200, 10);
    ctx.fillStyle = phaseColors[animationState.currentPhase];
    ctx.fillRect(10, 40, 200 * progress, 10);
  };

  const getConnectionColor = (connection: AssociationConnection): string => {
    switch (connection.type) {
      case 'semantic': return '#4ecdc4';
      case 'temporal': return '#ff6b6b';
      case 'emotional': return '#ffd93d';
      default: return '#95a5a6';
    }
  };

  const getPointColor = (point: EmbeddingPoint): string => {
    switch (config.colorScheme) {
      case 'jung':
        return point.type === 'stimulus' ? '#e74c3c' : '#3498db';
      case 'emotion':
        const intensity = point.emotionalIntensity || 0;
        return `hsl(${60 - intensity * 60}, 70%, 60%)`;
      case 'temporal':
        const delay = point.isDelayed ? 1 : 0;
        return `hsl(${240 - delay * 60}, 70%, 60%)`;
      default:
        return point.type === 'stimulus' ? '#e74c3c' : '#3498db';
    }
  };

  const getPointSize = (point: EmbeddingPoint): number => {
    const baseSize = config.pointSize;
    const associationFactor = (point.associationStrength || 1) * 0.5;
    return baseSize + associationFactor * 5;
  };

  const getPointBorderColor = (point: EmbeddingPoint): string => {
    return point.isDelayed ? '#e74c3c' : '#2c3e50';
  };

  const drawArrow = (ctx: CanvasRenderingContext2D, x1: number, y1: number, x2: number, y2: number) => {
    const angle = Math.atan2(y2 - y1, x2 - x1);
    const arrowLength = 10;
    const arrowWidth = 5;

    ctx.beginPath();
    ctx.moveTo(x2, y2);
    ctx.lineTo(
      x2 - arrowLength * Math.cos(angle - arrowWidth),
      y2 - arrowLength * Math.sin(angle - arrowWidth)
    );
    ctx.moveTo(x2, y2);
    ctx.lineTo(
      x2 - arrowLength * Math.cos(angle + arrowWidth),
      y2 - arrowLength * Math.sin(angle + arrowWidth)
    );
    ctx.stroke();
  };

  return (
    <div className="relative w-full h-full">
      <canvas
        ref={canvasRef}
        width={config.width}
        height={config.height}
        className="border border-gray-300 rounded-lg shadow-lg cursor-crosshair"
        style={{ width: '100%', height: '100%' }}
      />
      
      {/* Tooltip */}
      {hoveredPoint && (
        <div className="absolute top-2 left-2 bg-white p-2 rounded shadow-lg border text-sm">
          <div className="font-bold">{hoveredPoint.word}</div>
          <div>Type: {hoveredPoint.type}</div>
          <div>Category: {hoveredPoint.category}</div>
          {hoveredPoint.reactionTime && (
            <div>Reaction: {hoveredPoint.reactionTime}ms</div>
          )}
          {hoveredPoint.isDelayed && (
            <div className="text-red-600">⚠ Delayed Response</div>
          )}
        </div>
      )}
      
      {/* Selected point details */}
      {selectedPoint && (
        <div className="absolute top-2 right-2 bg-blue-50 p-3 rounded shadow-lg border text-sm max-w-xs">
          <div className="font-bold text-blue-800">{selectedPoint.word}</div>
          <div>Type: {selectedPoint.type}</div>
          <div>Category: {selectedPoint.category}</div>
          {selectedPoint.reactionTime && (
            <div>Reaction Time: {selectedPoint.reactionTime}ms</div>
          )}
          {selectedPoint.associationStrength && (
            <div>Association Strength: {(selectedPoint.associationStrength * 100).toFixed(1)}%</div>
          )}
          {selectedPoint.emotionalIntensity && (
            <div>Emotional Intensity: {(selectedPoint.emotionalIntensity * 100).toFixed(1)}%</div>
          )}
          {selectedPoint.isDelayed && (
            <div className="text-red-600 font-semibold">⚠ Delayed Response (Potential Complex)</div>
          )}
        </div>
      )}
    </div>
  );
};

export default EmbeddingScatterPlot; 