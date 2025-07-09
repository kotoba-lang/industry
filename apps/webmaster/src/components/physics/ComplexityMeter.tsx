'use client'

import { motion } from 'framer-motion'
import { PhysicsPhase } from './GenerativeInformationPhysicsVisualization'
import { Cpu, Zap, Brain, Atom, Sparkles } from 'lucide-react'

interface ComplexityMeterProps {
  phase: PhysicsPhase
  totalPhases: number
}

export function ComplexityMeter({ phase, totalPhases }: ComplexityMeterProps) {
  const complexityPercentage = (Math.log(phase.complexity) / Math.log(1e100)) * 100
  const infoPercentage = (Math.log(phase.infoDensity) / Math.log(1e120)) * 100
  const consciousnessPercentage = phase.consciousness * 100
  const entanglementPercentage = phase.entanglement * 100

  const circumference = 2 * Math.PI * 90 // radius = 90

  const meters = [
    {
      label: 'Computational Complexity',
      value: complexityPercentage,
      color: '#8b5cf6',
      glowColor: 'rgba(139, 92, 246, 0.5)',
      icon: Cpu,
      strokeDasharray: circumference,
      strokeDashoffset: circumference - (complexityPercentage / 100) * circumference,
      radius: 90
    },
    {
      label: 'Information Density',
      value: infoPercentage,
      color: '#06b6d4',
      glowColor: 'rgba(6, 182, 212, 0.5)',
      icon: Sparkles,
      strokeDasharray: circumference * 0.8,
      strokeDashoffset: circumference * 0.8 - (infoPercentage / 100) * circumference * 0.8,
      radius: 70
    },
    {
      label: 'Consciousness Index',
      value: consciousnessPercentage,
      color: '#f59e0b',
      glowColor: 'rgba(245, 158, 11, 0.5)',
      icon: Brain,
      strokeDasharray: circumference * 0.6,
      strokeDashoffset: circumference * 0.6 - (consciousnessPercentage / 100) * circumference * 0.6,
      radius: 50
    },
    {
      label: 'Quantum Entanglement',
      value: entanglementPercentage,
      color: '#10b981',
      glowColor: 'rgba(16, 185, 129, 0.5)',
      icon: Atom,
      strokeDasharray: circumference * 0.4,
      strokeDashoffset: circumference * 0.4 - (entanglementPercentage / 100) * circumference * 0.4,
      radius: 30
    }
  ]

  return (
    <motion.div 
      className="bg-black/20 backdrop-blur-sm rounded-2xl border border-white/10 p-6"
      initial={{ opacity: 0, y: 20 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: 0.6, delay: 0.8 }}
    >
      <div className="grid grid-cols-1 lg:grid-cols-2 gap-8">
        {/* Complexity meter */}
        <div className="relative flex items-center justify-center">
          <div className="relative">
            <svg className="w-64 h-64 transform -rotate-90" viewBox="0 0 200 200">
              {/* 背景円 */}
              {meters.map((meter, index) => (
                <circle
                  key={`bg-${index}`}
                  cx="100"
                  cy="100"
                  r={meter.radius}
                  fill="none"
                  stroke="rgba(255, 255, 255, 0.1)"
                  strokeWidth="8"
                />
              ))}
              
              {/* 進行円 */}
              {meters.map((meter, index) => (
                <motion.circle
                  key={`progress-${index}`}
                  cx="100"
                  cy="100"
                  r={meter.radius}
                  fill="none"
                  stroke={meter.color}
                  strokeWidth="8"
                  strokeLinecap="round"
                  strokeDasharray={meter.strokeDasharray}
                  strokeDashoffset={meter.strokeDashoffset}
                  initial={{ strokeDashoffset: meter.strokeDasharray }}
                  animate={{ strokeDashoffset: meter.strokeDashoffset }}
                  transition={{ duration: 2, delay: index * 0.2, ease: 'easeInOut' }}
                  style={{
                    filter: `drop-shadow(0 0 6px ${meter.glowColor})`,
                  }}
                />
              ))}
              
              {/* 中央の値表示 */}
              <text
                x="100"
                y="95"
                textAnchor="middle"
                className="fill-white text-2xl font-bold transform rotate-90"
                style={{ transformOrigin: '100px 95px' }}
              >
                {complexityPercentage.toFixed(0)}%
              </text>
              <text
                x="100"
                y="115"
                textAnchor="middle"
                className="fill-gray-400 text-sm transform rotate-90"
                style={{ transformOrigin: '100px 115px' }}
              >
                Complexity
              </text>
            </svg>
            
            {/* 中央アイコン */}
            <div className="absolute top-1/2 left-1/2 transform -translate-x-1/2 -translate-y-1/2">
              <motion.div
                className="w-16 h-16 rounded-full bg-gradient-to-r from-purple-500 to-pink-500 flex items-center justify-center"
                animate={{
                  rotate: [0, 360],
                  scale: [1, 1.1, 1],
                }}
                transition={{
                  rotate: { duration: 20, repeat: Infinity, ease: 'linear' },
                  scale: { duration: 2, repeat: Infinity, ease: 'easeInOut' }
                }}
              >
                <Zap className="text-white" size={32} />
              </motion.div>
            </div>
          </div>
        </div>

        {/* Detailed information */}
        <div className="space-y-4">
          <div className="mb-6">
            <h3 className="text-2xl font-bold text-white mb-2">Universal Information Processing Capacity</h3>
            <p className="text-gray-400 text-sm">
              Integrated indicators of computational complexity and information processing in {phase.name}
            </p>
          </div>

          {/* Individual meters */}
          {meters.map((meter, index) => {
            const Icon = meter.icon
            return (
              <motion.div
                key={meter.label}
                className="bg-black/30 rounded-xl p-4 border border-white/5"
                initial={{ opacity: 0, x: 20 }}
                animate={{ opacity: 1, x: 0 }}
                transition={{ duration: 0.5, delay: index * 0.1 }}
              >
                <div className="flex items-center gap-3 mb-2">
                  <div 
                    className="p-2 rounded-lg flex items-center justify-center"
                    style={{ backgroundColor: meter.color }}
                  >
                    <Icon size={20} className="text-white" />
                  </div>
                  <div className="flex-1">
                    <div className="flex items-center justify-between">
                      <span className="text-white font-semibold">{meter.label}</span>
                      <span className="text-gray-300 font-mono">
                        {meter.value.toFixed(1)}%
                      </span>
                    </div>
                  </div>
                </div>
                
                <div className="relative h-2 bg-black/40 rounded-full overflow-hidden">
                  <motion.div
                    className="h-full rounded-full"
                    style={{ 
                      backgroundColor: meter.color,
                      boxShadow: `0 0 10px ${meter.glowColor}`
                    }}
                    initial={{ width: 0 }}
                    animate={{ width: `${meter.value}%` }}
                    transition={{ duration: 1, delay: index * 0.1 }}
                  />
                </div>
              </motion.div>
            )
          })}

          {/* Overall score */}
          <motion.div 
            className="bg-gradient-to-r from-purple-500/10 to-pink-500/10 rounded-xl p-4 border border-purple-500/20 mt-6"
            initial={{ opacity: 0, y: 10 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ duration: 0.5, delay: 1 }}
          >
            <div className="text-center">
              <div className="text-3xl font-bold text-purple-300 mb-1">
                {((complexityPercentage + infoPercentage + consciousnessPercentage + entanglementPercentage) / 4).toFixed(1)}%
              </div>
              <div className="text-sm text-gray-400">Integrated Information Processing Index</div>
            </div>
          </motion.div>

          {/* Phase progression */}
          <motion.div 
            className="bg-black/30 rounded-xl p-4 border border-white/5"
            initial={{ opacity: 0, y: 10 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ duration: 0.5, delay: 1.2 }}
          >
            <div className="flex items-center justify-between mb-2">
              <span className="text-white font-semibold">Cosmic Evolution Progress</span>
              <span className="text-gray-300 font-mono">
                {phase.id + 1}/{totalPhases}
              </span>
            </div>
            <div className="relative h-2 bg-black/40 rounded-full overflow-hidden">
              <motion.div
                className="h-full bg-gradient-to-r from-red-500 via-cyan-500 via-blue-500 to-purple-500 rounded-full"
                initial={{ width: 0 }}
                animate={{ width: `${((phase.id + 1) / totalPhases) * 100}%` }}
                transition={{ duration: 1, delay: 1.2 }}
              />
            </div>
          </motion.div>
        </div>
      </div>
    </motion.div>
  )
} 