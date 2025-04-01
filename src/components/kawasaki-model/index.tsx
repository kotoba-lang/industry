"use client"

import type React from "react"
import { useState } from "react"
import { generateGraphData } from "@/components/kawasaki-model/utils/generateGraphData"
import dynamic from "next/dynamic"
import PhysicsStateMachine from "@/components/kawasaki-model/PhysicsStateMachine"
import ModelParamsControl from "@/components/kawasaki-model/ModelParamsControl"
import { useAnimation } from "@/components/kawasaki-model/hooks/useAnimation"
import { defaultModelParams, type IntegratedModelParams } from "@/components/kawasaki-model/utils/integratedModel"

const PhysicsGraph = dynamic(() => import("@/components/kawasaki-model/PhysicsGraph"), { ssr: false })

export default function Home() {
  // Use animation hook
  const { transitionState, time, isPlaying, speed, handleStateChange, togglePlay, handleSpeedChange } = useAnimation({
    initialSpeed: 1,
    initialPlaying: false,
    autoTransitionProbability: {
      stable: 0.1,
      excited: 0.3,
      decaying: 0.5,
    },
  })

  const [frameRate, setFrameRate] = useState(30)
  const [selectedElement, setSelectedElement] = useState<string | null>(null)
  const [modelParams, setModelParams] = useState<IntegratedModelParams>(defaultModelParams)

  // Set particle count to 50 (half of Jung's 100 words for performance)
  const particleCount = 50

  // Generate graph data (pass integrated model parameters)
  const graphData = generateGraphData(particleCount, transitionState, time, modelParams)

  const handleFrameRateChange = (event: React.ChangeEvent<HTMLInputElement>) => {
    setFrameRate(Number(event.target.value))
  }

  const handleSpeedChangeEvent = (event: React.ChangeEvent<HTMLInputElement>) => {
    handleSpeedChange(Number(event.target.value))
  }

  return (
    <main className="flex flex-col h-[calc(100vh-2rem)] w-full p-0 bg-gradient-to-b from-gray-50 to-gray-100 dark:from-gray-900 dark:to-gray-800 text-gray-800 dark:text-gray-200 overflow-hidden">
      <div className="px-4 py-3 bg-white/80 dark:bg-black/40 backdrop-blur-sm">
        <h1 className="text-xl font-bold text-gray-800 dark:text-gray-200 tracking-wide">
          Spirit in Physics ( Jung's Word Association Test Embedding Model )
        </h1>
      </div>

      <div className="grid grid-cols-12 gap-0 h-full">
        {/* Left sidebar with controls */}
        <div className="col-span-12 md:col-span-3 lg:col-span-2 flex flex-col space-y-2 p-3 bg-white/70 dark:bg-gray-900/70 backdrop-blur-sm z-10">
          <PhysicsStateMachine transitionState={transitionState} onStateChange={handleStateChange} />
          <ModelParamsControl params={modelParams} onChange={setModelParams} />

          {/* Model explanation */}
          <div className="bg-white/80 dark:bg-gray-800/80 backdrop-blur-sm p-3 rounded-md shadow-sm border border-gray-200 dark:border-gray-700 text-xs overflow-auto flex-grow">
            <h3 className="font-bold mb-2 text-gray-800 dark:text-gray-200">About the Integrated Model</h3>
            <p className="mb-2 leading-relaxed">
              This model integrates multiple factors from Jung's word association test:
            </p>
            <ul className="space-y-1 pl-4">
              <li className="flex items-start">
                <span className="inline-block w-1 h-1 rounded-full bg-gray-800 mt-1.5 mr-2"></span>
                <span>Semantic similarity (vector dot product)</span>
              </li>
              <li className="flex items-start">
                <span className="inline-block w-1 h-1 rounded-full bg-gray-800 mt-1.5 mr-2"></span>
                <span>Association reaction speed (α parameter)</span>
              </li>
              <li className="flex items-start">
                <span className="inline-block w-1 h-1 rounded-full bg-gray-800 mt-1.5 mr-2"></span>
                <span>Skin potential for emotional response (γ, λ parameters)</span>
              </li>
              <li className="flex items-start">
                <span className="inline-block w-1 h-1 rounded-full bg-gray-800 mt-1.5 mr-2"></span>
                <span>Facial emotion analysis (η parameter)</span>
              </li>
            </ul>
            <p className="mt-2 leading-relaxed">
              Adjust parameters to visualize how different psychological factors influence word associations in the
              Zen-inspired space.
            </p>
          </div>
        </div>

        {/* Main visualization area */}
        <div className="col-span-12 md:col-span-9 lg:col-span-10 border-0 md:border-l border-gray-200 dark:border-gray-700 overflow-hidden bg-gradient-to-br from-white/80 to-gray-100/80 dark:from-gray-800/80 dark:to-gray-900/80 backdrop-blur-sm">
          <PhysicsGraph
            data={graphData}
            frameRate={frameRate}
            time={time}
            isPlaying={isPlaying}
            speed={speed}
            selectedElement={selectedElement}
            setSelectedElement={setSelectedElement}
          />
        </div>
      </div>
    </main>
  )
}

