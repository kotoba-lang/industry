'use client'

import { useState } from 'react'
import dynamic from 'next/dynamic'
import { motion } from 'framer-motion'
import CorrelationForm from './correlation-form'
import { CorrelationInput, Vector, correlationLevelToNumber } from '@/types/correlation'

const VectorVisualization = dynamic(() => import('./vector-visualization'), { ssr: false })

export default function CorrelationFormWrapper() {
  const [vectors, setVectors] = useState<Vector[]>([])

  const handleCorrelationSubmit = (correlations: CorrelationInput[]) => {
    const elementVectors: { [key: string]: Vector } = { りんご: [0, 0, 0], みかん: [0, 0, 0], バナナ: [0, 0, 0], '河崎純真': [0, 0, 0] }

    correlations.forEach(({ pair, level }) => {
      const value = correlationLevelToNumber(level)
      const { element1, element2 } = pair

      // 簡単な例として、各要素を3次元ベクトルとして表現
      for (let i = 0; i < 3; i++) {
        elementVectors[element1][i] += value
        elementVectors[element2][i] -= value
      }
    })

    setVectors(Object.values(elementVectors))
  }

  return (
    <>
      <CorrelationForm onSubmit={handleCorrelationSubmit} />
      {vectors.length > 0 && (
        <motion.div
          initial={{ opacity: 0, y: 20 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ duration: 0.5, delay: 0.2 }}
        >
          <div className="mt-8 p-6 bg-white rounded-lg shadow-md">
          <h2 className="text-2xl font-bold mb-4">ベクトル化結果</h2>
          <ul className="space-y-2 mb-4">
            {['りんご', 'みかん', 'バナナ', '河崎純真'].map((element, index) => (
              <li key={element} className="font-mono">
                {element}: [{vectors[index].map(v => v.toFixed(2)).join(', ')}]
                </li>
              ))}
            </ul>
            <div className="w-full h-[400px]">
            <VectorVisualization vectors={vectors} />
          </div>
          </div>
        </motion.div>
      )}
    </>
  )
}

