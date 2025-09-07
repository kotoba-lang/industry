"use client";

import React, { useEffect, useRef } from 'react';

interface AudioVisualizerProps {
  stream: MediaStream | null;
}

/**
 * A component that visualizes audio activity from a MediaStream.
 * It uses the Web Audio API to analyze frequency data and draws bars on a canvas.
 */
const AudioVisualizer: React.FC<AudioVisualizerProps> = ({ stream }) => {
  const canvasRef = useRef<HTMLCanvasElement>(null);

  useEffect(() => {
    if (!stream || !canvasRef.current) return;

    // Ensure there's an audio track before proceeding
    if (stream.getAudioTracks().length === 0) {
        console.warn("AudioVisualizer: The provided stream has no audio tracks.");
        return;
    }

    const audioContext = new (window.AudioContext || (window as any).webkitAudioContext)();
    const source = audioContext.createMediaStreamSource(stream);
    const analyser = audioContext.createAnalyser();
    
    analyser.fftSize = 256;
    source.connect(analyser);

    const bufferLength = analyser.frequencyBinCount;
    const dataArray = new Uint8Array(bufferLength);
    const canvas = canvasRef.current;
    const canvasCtx = canvas.getContext('2d');
    let animationFrameId: number;

    const draw = () => {
      animationFrameId = requestAnimationFrame(draw);
      analyser.getByteFrequencyData(dataArray);

      if (!canvasCtx) return;
      
      // Clear canvas with a transparent background
      canvasCtx.clearRect(0, 0, canvas.width, canvas.height);
      
      const average = dataArray.reduce((acc, val) => acc + val, 0) / bufferLength;
      const normalizedAverage = average / 128.0; // Normalize to 0-1 range (data is 0-255)
      
      const barWidth = canvas.width * normalizedAverage;

      // Draw a single bar representing the average volume
      canvasCtx.fillStyle = '#22c55e'; // Green 500
      canvasCtx.fillRect(0, 0, barWidth, canvas.height);
    };

    draw();

    return () => {
      cancelAnimationFrame(animationFrameId);
      if (audioContext.state !== 'closed') {
        audioContext.close();
      }
    };
  }, [stream]);

  return <canvas ref={canvasRef} width="200" height="10" style={{ borderRadius: '5px' }} />;
};

export default AudioVisualizer; 