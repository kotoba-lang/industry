"use client"

import { useState } from "react"
import dynamic from "next/dynamic"
import { TestResults } from "@/components/jung-word-assessment/types"
import IntegratedJungAssessment from "@/components/jung-integrated/IntegratedJungAssessment"


export default function JungIntegratedPage() {
  const [testResults, setTestResults] = useState<TestResults | null>(null)
  
  return (
    <main className="container mx-auto px-4 py-12">
      <div className="mb-8 text-center">
        <h1 className="text-4xl font-bold mb-4">Jung's Integrated Word Association Model</h1>
        <p className="text-xl text-gray-600">
          Explore your subconscious through word associations and vector visualization
        </p>
      </div>
      
      <div className="prose max-w-none mb-12">
        <h2>About This Integration</h2>
        <p>
          This tool combines Carl Jung's word association test with a sophisticated vector embedding model
          to visualize your responses in a dynamic physics-based environment.
        </p>
        <p>
          First, you'll complete the word association test, then your responses will be processed and
          visualized in the model. The visualization represents:
        </p>
        <ul>
          <li>The semantic relationships between your responses</li>
          <li>Reaction time patterns that may indicate emotional complexes</li>
          <li>Associations that deviate from typical patterns</li>
        </ul>
        <p>
          Watch how your mental associations form clusters and interact in the model's environment.
        </p>
      </div>
      
      <div className="bg-white rounded-lg shadow-lg">
        <IntegratedJungAssessment 
          numberOfWords={3} 
          wordTestResults={testResults}
          setWordTestResults={setTestResults}
        />
      </div>
      
      <div className="mt-12 text-center text-sm text-gray-500">
        <p>
          Based on Carl Jung's word association theories and modern vector embedding techniques.
          For educational and exploration purposes only.
        </p>
      </div>
    </main>
  )
} 