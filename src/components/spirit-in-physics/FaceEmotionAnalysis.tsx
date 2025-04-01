"use client";

import { useRef, useState, useEffect } from "react";
import Webcam from "react-webcam";
import * as faceapi from "face-api.js";

export default function FaceEmotionAnalysis() {
  const webcamRef = useRef<Webcam>(null);
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const [isModelLoaded, setIsModelLoaded] = useState(false);
  const [emotionData, setEmotionData] = useState<Record<string, number>>({});

  // Load face-api models
  useEffect(() => {
    const loadModels = async () => {
      try {
        // Load models from public directory
        await Promise.all([
          faceapi.nets.tinyFaceDetector.loadFromUri("/face-api-models"),
          faceapi.nets.faceExpressionNet.loadFromUri("/face-api-models"),
        ]);
        setIsModelLoaded(true);
        console.log("Face detection models loaded");
      } catch (error) {
        console.error("Error loading face detection models:", error);
      }
    };
    
    loadModels();
  }, []);

  // Setup face detection when models are loaded
  useEffect(() => {
    if (!isModelLoaded) return;

    let animationFrameId: number;
    
    const detectFace = async () => {
      if (
        webcamRef.current?.video &&
        webcamRef.current.video.readyState === 4 &&
        canvasRef.current
      ) {
        const video = webcamRef.current.video;
        const canvas = canvasRef.current;
        
        // Match canvas dimensions to video
        const { videoWidth, videoHeight } = video;
        canvas.width = videoWidth;
        canvas.height = videoHeight;
        
        // Detect faces and expressions
        const detections = await faceapi
          .detectAllFaces(video, new faceapi.TinyFaceDetectorOptions())
          .withFaceExpressions();
        
        // Draw results on canvas
        const ctx = canvas.getContext("2d");
        if (ctx) {
          ctx.clearRect(0, 0, videoWidth, videoHeight);
          
          if (detections.length > 0) {
            // Get expressions from first detected face
            const expressions = detections[0].expressions;
            // Convert FaceExpressions to Record<string, number>
            const expressionsAsRecord: Record<string, number> = {};
            Object.entries(expressions).forEach(([key, value]) => {
              expressionsAsRecord[key] = value as number;
            });
            setEmotionData(expressionsAsRecord);
            
            // Draw face detection results
            faceapi.draw.drawDetections(canvas, detections);
          }
        }
      }
      
      // Continue detection loop
      animationFrameId = requestAnimationFrame(detectFace);
    };
    
    detectFace();
    
    // Cleanup
    return () => {
      if (animationFrameId) {
        cancelAnimationFrame(animationFrameId);
      }
    };
  }, [isModelLoaded]);

  return (
    <div className="flex flex-col items-center">
      <div className="relative">
        <Webcam
          ref={webcamRef}
          audio={false}
          className="rounded-lg"
          width={640}
          height={480}
          mirrored
        />
        <canvas
          ref={canvasRef}
          className="absolute top-0 left-0 z-10"
        />
      </div>
      
      {!isModelLoaded && (
        <div className="mt-4 text-yellow-600">
          顔分析モデルを読み込み中...
        </div>
      )}
      
      <div className="mt-6 bg-white p-4 rounded-lg shadow w-full max-w-lg">
        <h2 className="text-xl font-semibold mb-3">感情分析結果</h2>
        <div className="space-y-2">
          {Object.entries(emotionData).map(([emotion, value]) => (
            <div key={emotion} className="flex items-center">
              <span className="w-32">{translateEmotion(emotion)}:</span>
              <div className="w-full bg-gray-200 rounded-full h-4">
                <div
                  className="bg-blue-600 h-4 rounded-full"
                  style={{ width: `${Math.round(value * 100)}%` }}
                ></div>
              </div>
              <span className="ml-2 w-12 text-right">
                {Math.round(value * 100)}%
              </span>
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}

// Helper function to translate emotion names to Japanese
function translateEmotion(emotion: string): string {
  const translations: Record<string, string> = {
    neutral: "無表情",
    happy: "幸せ",
    sad: "悲しみ",
    angry: "怒り",
    fearful: "恐れ",
    disgusted: "嫌悪",
    surprised: "驚き",
  };
  
  return translations[emotion] || emotion;
} 