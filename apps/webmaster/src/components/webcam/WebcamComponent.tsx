"use client";

import React, { useRef, useEffect, useState } from 'react';
import { Card } from "@/components/ui/card";
import { useHumeEmotion } from '@/providers/HumeEmotionProvider';

type WebcamProps = {
  width?: number;
  height?: number;
  isActive?: boolean;
  showLabels?: boolean;
  children?: React.ReactNode;
};

export default function WebcamComponent({
  width = 320,
  height = 240,
  isActive = true,
  showLabels = true,
  children
}: WebcamProps) {
  const videoRef = useRef<HTMLVideoElement | null>(null);
  const [isInitialized, setIsInitialized] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const { attachVideoElement, detachVideoElement, currentEmotion } = useHumeEmotion();

  useEffect(() => {
    if (!isActive) {
      if (videoRef.current && isInitialized) {
        detachVideoElement();
        
        const tracks = videoRef.current.srcObject instanceof MediaStream 
          ? videoRef.current.srcObject.getTracks() 
          : [];
          
        tracks.forEach(track => track.stop());
        videoRef.current.srcObject = null;
        setIsInitialized(false);
      }
      return;
    }

    async function setupCamera() {
      try {
        if (!videoRef.current) return;

        const stream = await navigator.mediaDevices.getUserMedia({
          video: {
            width: { ideal: width },
            height: { ideal: height },
            facingMode: "user"
          },
          audio: false
        });

        videoRef.current.srcObject = stream;
        videoRef.current.onloadedmetadata = () => {
          if (videoRef.current) {
            videoRef.current.play();
            attachVideoElement(videoRef.current);
            setIsInitialized(true);
          }
        };
      } catch (err) {
        setError(`カメラへのアクセスができませんでした: ${err instanceof Error ? err.message : String(err)}`);
        console.error("カメラアクセスエラー:", err);
      }
    }

    if (isActive && !isInitialized) {
      setupCamera();
    }

    return () => {
      if (videoRef.current && isInitialized) {
        detachVideoElement();
        
        const tracks = videoRef.current.srcObject instanceof MediaStream 
          ? videoRef.current.srcObject.getTracks() 
          : [];
          
        tracks.forEach(track => track.stop());
        videoRef.current.srcObject = null;
      }
    };
  }, [isActive, isInitialized, attachVideoElement, detachVideoElement, width, height]);

  return (
    <Card className="webcam-container p-2">
      <div className="relative">
        <video
          ref={videoRef}
          width={width}
          height={height}
          muted
          playsInline
          className={`rounded-md ${error ? 'hidden' : 'block'}`}
          style={{ transform: 'scaleX(-1)' }} // Mirror effect
        />
        
        {error && (
          <div className="flex items-center justify-center bg-gray-200 rounded-md" 
               style={{ width, height }}>
            <p className="text-red-500 text-sm p-4 text-center">{error}</p>
          </div>
        )}

        {showLabels && currentEmotion && !error && (
          <div className="absolute bottom-2 left-2 right-2 bg-black/60 text-white text-xs p-1 rounded">
            {currentEmotion.emotions && 
              Object.entries(currentEmotion.emotions)
                .sort(([, a], [, b]) => b - a)
                .slice(0, 3)
                .map(([emotion, score]) => (
                  <div key={emotion} className="flex justify-between">
                    <span>{emotion}</span>
                    <span>{(score * 100).toFixed(1)}%</span>
                  </div>
                ))
            }
          </div>
        )}

        {children}
      </div>
    </Card>
  );
} 