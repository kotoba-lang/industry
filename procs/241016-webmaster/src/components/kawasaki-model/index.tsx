"use client"

import type React from "react"
import { useState, useEffect, useMemo } from "react"
import { generateGraphData } from "@/components/kawasaki-model/utils/generateGraphData"
import dynamic from "next/dynamic"
import PhysicsStateMachine from "@/components/kawasaki-model/PhysicsStateMachine"
import ModelParamsControl from "@/components/kawasaki-model/ModelParamsControl"
import { useAnimation } from "@/components/kawasaki-model/hooks/useAnimation"
import { defaultModelParams, type IntegratedModelParams } from "@/components/kawasaki-model/utils/integratedModel"
import { useKawasakiStore } from "@/store/kawasakiStore"
import { generateGraphDataFromVoiceAssessment } from "@/components/jung-integrated/utils/generateGraphDataFromVoiceAssessment"

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
  
  // State to track parameter impacts on words
  const [parameterImpacts, setParameterImpacts] = useState<Record<string, string[]>>({
    alpha: [],  // Words affected by reaction speed parameter
    gamma: [],  // Words affected by skin potential parameter
    lambda: [], // Words affected by emotional response parameter
    eta: []     // Words affected by facial emotion parameter
  })
  
  // State to track which parameter was last adjusted
  const [lastAdjustedParam, setLastAdjustedParam] = useState<string | null>(null)

  // Get voice assessment data from Zustand store
  const voiceAssessments = useKawasakiStore((state) => state.voiceAssessments)

  // Set particle count to 50 (half of Jung's 100 words for performance)
  const particleCount = 50

  // Generate graph data (pass integrated model parameters)
  const baseGraphData = useMemo(() => 
    generateGraphData(particleCount, transitionState, time, modelParams),
    [particleCount, transitionState, time, modelParams]
  )
  
  // Generate voice assessment graph data
  const voiceGraphData = useMemo(() => {
    if (voiceAssessments && voiceAssessments.length > 0) {
      // Use the most recent voice assessment
      const latestAssessment = voiceAssessments[voiceAssessments.length - 1]
      
      if (latestAssessment && latestAssessment.results) {
        return generateGraphDataFromVoiceAssessment(
          latestAssessment.results,
          transitionState,
          time,
          modelParams
        )
      }
    }
    return { nodes: [], links: [] }
  }, [voiceAssessments, transitionState, time, modelParams])
  
  // Calculate parameter impacts whenever model params change
  useEffect(() => {
    if (voiceGraphData.nodes.length === 0) return
    
    const newImpacts: Record<string, string[]> = {
      alpha: [],
      gamma: [],
      lambda: [],
      eta: []
    }
    
    // Identify words most affected by each parameter
    // This is simplified logic - in a real implementation, you would use actual formulas
    voiceGraphData.nodes.forEach(node => {
      if (!node.id || typeof node.id !== 'string' || node.id === 'center') return
      
      // Parse node properties to determine parameter impacts
      // Use reaction time for alpha parameter (reaction speed)
      if (node.reactionTime && node.reactionTime > 2000) {
        newImpacts.alpha.push(node.id)
      }
      
      // Use hasEmotionalComplex flag for gamma and lambda parameters
      // This is based on reaction time thresholds from Jung's theory
      if (node.hasEmotionalComplex) {
        newImpacts.gamma.push(node.id)
        newImpacts.lambda.push(node.id)
      }
      
      // Use emotion intensity for eta parameter (facial emotion)
      if (node.emotionIntensity && node.emotionIntensity > 0.7) {
        newImpacts.eta.push(node.id)
      }
    })
    
    setParameterImpacts(newImpacts)
  }, [voiceGraphData.nodes, modelParams])
  
  // Combine graph data with parameter impact information
  const combinedGraphData = useMemo(() => {
    const nodes = [...baseGraphData.nodes]
    
    // Add parameter impact information to voice nodes
    const voiceNodes = voiceGraphData.nodes.map(node => {
      if (!node.id || typeof node.id !== 'string' || node.id === 'center') return node
      
      // Create a copy of the node to add parameter impact information
      const enhancedNode = { ...node }
      
      // Add flags for which parameters affect this node
      enhancedNode.affectedByAlpha = parameterImpacts.alpha.includes(node.id)
      enhancedNode.affectedByGamma = parameterImpacts.gamma.includes(node.id)
      enhancedNode.affectedByLambda = parameterImpacts.lambda.includes(node.id)
      enhancedNode.affectedByEta = parameterImpacts.eta.includes(node.id)
      
      // Add highlighting if this node is affected by the last adjusted parameter
      if (lastAdjustedParam) {
        enhancedNode.highlighted = parameterImpacts[lastAdjustedParam]?.includes(node.id)
      }
      
      return enhancedNode
    })
    
    return {
      nodes: [...nodes, ...voiceNodes],
      links: [...baseGraphData.links, ...voiceGraphData.links]
    }
  }, [baseGraphData, voiceGraphData, parameterImpacts, lastAdjustedParam])

  const handleFrameRateChange = (event: React.ChangeEvent<HTMLInputElement>) => {
    setFrameRate(Number(event.target.value))
  }

  const handleSpeedChangeEvent = (event: React.ChangeEvent<HTMLInputElement>) => {
    handleSpeedChange(Number(event.target.value))
  }
  
  // Enhanced parameter change handler to track which parameter was adjusted
  const handleModelParamsChange = (newParams: IntegratedModelParams) => {
    // Determine which parameter changed
    const changedParam = Object.keys(newParams).find(
      key => newParams[key as keyof IntegratedModelParams] !== modelParams[key as keyof IntegratedModelParams]
    )
    
    if (changedParam) {
      setLastAdjustedParam(changedParam)
    }
    
    setModelParams(newParams)
  }

  return (
    <main className="flex flex-col h-[calc(100vh-2rem)] w-full p-0 bg-gradient-to-b from-gray-50 to-gray-100 text-gray-800 overflow-hidden">
      <div className="px-4 py-3 bg-white/80 backdrop-blur-sm">
        <h1 className="text-xl font-bold text-gray-800 tracking-wide">
          Spirit in Physics ( Jung's Word Association Test Embedding Model )
        </h1>
        {voiceAssessments.length > 0 && (
          <p className="text-sm text-gray-600">
            Voice assessment data included ({voiceAssessments.length} test{voiceAssessments.length !== 1 ? 's' : ''})
            {voiceGraphData.nodes.length > 0 && 
              ` - ${voiceGraphData.nodes.length - 1} words with latency reflected in distance`}
          </p>
        )}
      </div>

      <div className="grid grid-cols-12 gap-0 h-full">
        {/* Left sidebar with controls */}
        <div className="col-span-12 md:col-span-3 lg:col-span-2 flex flex-col space-y-2 p-3 bg-white/70 backdrop-blur-sm z-10">
          <PhysicsStateMachine transitionState={transitionState} onStateChange={handleStateChange} />
          <ModelParamsControl params={modelParams} onChange={handleModelParamsChange} />

          {/* Parameter impact legend */}
          {voiceGraphData.nodes.length > 0 && (
            <div className="bg-white/80 backdrop-blur-sm p-3 rounded-md shadow-sm border border-gray-200 text-xs">
              <h3 className="font-bold mb-2 text-gray-800">Parameter Impacts</h3>
              <ul className="space-y-1">
                <li className="flex justify-between">
                  <span>α (Reaction speed):</span>
                  <span className="font-medium">{parameterImpacts.alpha.length} words</span>
                </li>
                <li className="flex justify-between">
                  <span>γ (Skin potential):</span>
                  <span className="font-medium">{parameterImpacts.gamma.length} words</span>
                </li>
                <li className="flex justify-between">
                  <span>λ (Emotional response):</span>
                  <span className="font-medium">{parameterImpacts.lambda.length} words</span>
                </li>
                <li className="flex justify-between">
                  <span>η (Facial emotion):</span>
                  <span className="font-medium">{parameterImpacts.eta.length} words</span>
                </li>
              </ul>
              {lastAdjustedParam && (
                <div className="mt-2 pt-2 border-t border-gray-200">
                  <p className="font-medium">Last adjusted: {lastAdjustedParam} parameter</p>
                  <p className="text-xs text-gray-600 mt-1">
                    {parameterImpacts[lastAdjustedParam]?.length > 0 
                      ? `Affected words: ${parameterImpacts[lastAdjustedParam].slice(0, 3).join(', ')}${parameterImpacts[lastAdjustedParam].length > 3 ? '...' : ''}`
                      : 'No words affected'}
                  </p>
                </div>
              )}
            </div>
          )}

          {/* Model explanation */}
          <div className="bg-white/80 backdrop-blur-sm p-3 rounded-md shadow-sm border border-gray-200 text-xs overflow-auto flex-grow">
            <h3 className="font-bold mb-2 text-gray-800">About the Integrated Model</h3>
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
            {voiceGraphData.nodes.length > 0 && (
              <>
                <h4 className="font-semibold mt-3 mb-1 text-gray-800">Voice Assessment Data</h4>
                <p className="leading-relaxed">
                  Words with longer reaction times appear closer to the center, following Jung's theory of complexes.
                </p>
                <p className="mt-1 text-xs text-gray-600">
                  <strong>Parameter adjustments:</strong> Words affected by specific parameters will be highlighted when you adjust the controls.
                </p>
              </>
            )}
            <p className="mt-2 leading-relaxed">
              Adjust parameters to visualize how different psychological factors influence word associations in the
              Zen-inspired space.
            </p>
          </div>
        </div>

        {/* Main visualization area */}
        <div className="col-span-12 md:col-span-9 lg:col-span-10 border-0 md:border-l border-gray-200 overflow-hidden bg-gradient-to-br from-white/80 to-gray-100/80 backdrop-blur-sm relative">
          <PhysicsGraph
            data={combinedGraphData}
            frameRate={frameRate}
            time={time}
            isPlaying={isPlaying}
            speed={speed}
            selectedElement={selectedElement}
            setSelectedElement={setSelectedElement}
            lastAdjustedParam={lastAdjustedParam}
          />
          
          {/* Parameter impact tooltip */}
          {selectedElement && (
            <div className="absolute bottom-4 right-4 bg-white/90 backdrop-blur-sm shadow-lg rounded-md p-3 max-w-xs text-sm border border-gray-200">
              <h4 className="font-semibold text-gray-800 mb-1">{selectedElement.split(' ')[0]}</h4>
              
              {/* Find the node that matches this element */}
              {(() => {
                const node = combinedGraphData.nodes.find(n => n.name === selectedElement);
                if (!node) return null;
                
                const impacts = [];
                if (node.affectedByAlpha) impacts.push("α: reaction speed");
                if (node.affectedByGamma) impacts.push("γ: skin potential");
                if (node.affectedByLambda) impacts.push("λ: emotional response");
                if (node.affectedByEta) impacts.push("η: facial emotion");
                
                return (
                  <div>
                    {node.reactionTime && (
                      <p className="text-xs mb-1">Reaction time: {Math.round(node.reactionTime)}ms</p>
                    )}
                    {impacts.length > 0 ? (
                      <div>
                        <p className="text-xs font-medium mb-1">Affected by parameters:</p>
                        <ul className="text-xs text-gray-700">
                          {impacts.map((impact, i) => (
                            <li key={i} className="flex items-center">
                              <span className="inline-block w-1 h-1 rounded-full bg-gray-600 mr-1"></span>
                              {impact}
                            </li>
                          ))}
                        </ul>
                      </div>
                    ) : (
                      <p className="text-xs text-gray-600">No parameter impacts detected</p>
                    )}
                  </div>
                );
              })()}
            </div>
          )}
        </div>
      </div>
    </main>
  )
}

