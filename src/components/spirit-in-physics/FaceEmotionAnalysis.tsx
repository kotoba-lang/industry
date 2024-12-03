'use client'

import React, { useRef, useEffect, useState } from 'react'
import Webcam from 'react-webcam'
import * as faceapi from 'face-api.js'
import { motion } from 'framer-motion'

const FaceEmotionAnalysis: React.FC = () => {
  const webcamRef = useRef<Webcam>(null)
  const canvasRef = useRef<HTMLCanvasElement>(null)
  const [emotion, setEmotion] = useState<string>('')

  useEffect(() => {
    const loadModels = async () => {
      const MODEL_URL = '/models'
      await Promise.all([
        faceapi.nets.tinyFaceDetector.loadFromUri(MODEL_URL),
        faceapi.nets.faceLandmark68Net.loadFromUri(MODEL_URL),
        faceapi.nets.faceRecognitionNet.loadFromUri(MODEL_URL),
        faceapi.nets.faceExpressionNet.loadFromUri(MODEL_URL)
      ])
    }

    loadModels()
  }, [])

  useEffect(() => {
    const runFaceDetection = async () => {
      if (webcamRef.current && canvasRef.current) {
        const video = webcamRef.current.video as HTMLVideoElement
        const canvas = canvasRef.current

        const displaySize = { width: video.width, height: video.height }
        faceapi.matchDimensions(canvas, displaySize)

        setInterval(async () => {
          const detections = await faceapi.detectAllFaces(video, new faceapi.TinyFaceDetectorOptions())
            .withFaceLandmarks()
            .withFaceExpressions()

          const resizedDetections = faceapi.resizeResults(detections, displaySize)
          canvas.getContext('2d')?.clearRect(0, 0, canvas.width, canvas.height)
          faceapi.draw.drawDetections(canvas, resizedDetections)
          faceapi.draw.drawFaceLandmarks(canvas, resizedDetections)
          faceapi.draw.drawFaceExpressions(canvas, resizedDetections)

          if (resizedDetections.length > 0) {
            const expressions = resizedDetections[0].expressions
            const dominantEmotion = Object.entries(expressions).reduce((a, b) => a[1] > b[1] ? a : b)[0]
            setEmotion(dominantEmotion)
          }
        }, 100)
      }
    }

    runFaceDetection()
  }, [])

  return (
    <div className="relative w-full max-w-md mx-auto">
      <Webcam
        audio={false}
        ref={webcamRef}
        screenshotFormat="image/jpeg"
        className="w-full rounded-lg shadow-lg"
      />
      <canvas
        ref={canvasRef}
        className="absolute top-0 left-0 w-full h-full"
      />
      <motion.div
        initial={{ opacity: 0, y: 20 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: 0.5 }}
        className="mt-4 p-4 bg-white rounded-lg shadow-md"
      >
        <h2 className="text-2xl font-bold mb-2">検出された感情:</h2>
        <motion.p
          key={emotion}
          initial={{ opacity: 0 }}
          animate={{ opacity: 1 }}
          className="text-xl"
        >
          {emotion}
        </motion.p>
      </motion.div>
    </div>
  )
}

export default FaceEmotionAnalysis

