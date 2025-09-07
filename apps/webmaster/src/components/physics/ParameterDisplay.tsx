'use client'

import { motion } from 'framer-motion'
import { PhysicsPhase } from './GenerativeInformationPhysicsVisualization'
import { TrendingUp, Zap, Brain, Atom, Database, Activity } from 'lucide-react'

interface ParameterDisplayProps {
  phase: PhysicsPhase
}

function getChangeRate(paramKey: string, phaseId: number): string {
  // 位相IDとパラメータキーに基づいて決定的な変化率を計算
  const seed = (paramKey.charCodeAt(0) + phaseId) % 100
  const isPositive = seed % 2 === 0
  const rate = ((seed % 50) / 5).toFixed(1)
  return `${isPositive ? '+' : '-'}${rate}%`
}

export function ParameterDisplay({ phase }: ParameterDisplayProps) {
  const parameters = [
    {
      key: 'infoDensity',
      label: 'Information Density',
      value: phase.infoDensity,
      unit: 'bits/m³',
      icon: Database,
      color: 'from-cyan-500 to-blue-500',
      textColor: 'text-cyan-300'
    },
    {
      key: 'complexity',
      label: 'Computational Complexity',
      value: phase.complexity,
      unit: 'ops/s',
      icon: TrendingUp,
      color: 'from-purple-500 to-pink-500',
      textColor: 'text-purple-300'
    },
    {
      key: 'genRate',
      label: 'Information Generation Rate',
      value: phase.genRate,
      unit: 'bits/s',
      icon: Activity,
      color: 'from-green-500 to-emerald-500',
      textColor: 'text-green-300'
    },
    {
      key: 'decayRate',
      label: 'Information Decay Rate',
      value: phase.decayRate,
      unit: 'bits/s',
      icon: TrendingUp,
      color: 'from-orange-500 to-red-500',
      textColor: 'text-orange-300'
    },
    {
      key: 'entanglement',
      label: 'Quantum Entanglement',
      value: phase.entanglement,
      unit: '',
      icon: Atom,
      color: 'from-indigo-500 to-purple-500',
      textColor: 'text-indigo-300'
    },
    {
      key: 'consciousness',
      label: 'Consciousness Index',
      value: phase.consciousness,
      unit: '',
      icon: Brain,
      color: 'from-pink-500 to-rose-500',
      textColor: 'text-pink-300'
    }
  ]

  const formatValue = (value: number, isRatio: boolean = false): string => {
    if (isRatio) {
      return value.toFixed(2)
    }
    
    if (value >= 1e100) {
      return `${(value / 1e100).toFixed(1)}×10¹⁰⁰`
    } else if (value >= 1e80) {
      return `${(value / 1e80).toFixed(1)}×10⁸⁰`
    } else if (value >= 1e60) {
      return `${(value / 1e60).toFixed(1)}×10⁶⁰`
    } else if (value >= 1e40) {
      return `${(value / 1e40).toFixed(1)}×10⁴⁰`
    } else if (value >= 1e20) {
      return `${(value / 1e20).toFixed(1)}×10²⁰`
    } else if (value >= 1e15) {
      return `${(value / 1e15).toFixed(1)}×10¹⁵`
    } else if (value >= 1e10) {
      return `${(value / 1e10).toFixed(1)}×10¹⁰`
    } else if (value >= 1e6) {
      return `${(value / 1e6).toFixed(1)}M`
    } else if (value >= 1e3) {
      return `${(value / 1e3).toFixed(1)}K`
    } else {
      return value.toFixed(1)
    }
  }

  const getProgressBarWidth = (value: number, key: string): number => {
    switch (key) {
      case 'infoDensity':
        return Math.min((Math.log(value) / Math.log(1e120)) * 100, 100)
      case 'complexity':
        return Math.min((Math.log(value) / Math.log(1e100)) * 100, 100)
      case 'genRate':
        return Math.min((Math.log(value) / Math.log(1e80)) * 100, 100)
      case 'decayRate':
        return value > 0 ? Math.min((Math.log(value) / Math.log(1e80)) * 100, 100) : 0
      case 'entanglement':
      case 'consciousness':
        return value * 100
      default:
        return 0
    }
  }

  return (
    <motion.div 
      className="bg-black/20 backdrop-blur-sm rounded-2xl border border-white/10 p-6"
      initial={{ opacity: 0, y: 20 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: 0.6, delay: 0.6 }}
    >
      <div className="mb-6">
        <h3 className="text-2xl font-bold text-white mb-2">Physics Parameters</h3>
        <p className="text-gray-400 text-sm">
          Information processing and quantum state characteristics in {phase.name}
        </p>
      </div>

      <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
        {parameters.map((param, index) => {
          const Icon = param.icon
          const isRatio = param.key === 'entanglement' || param.key === 'consciousness'
          const progressWidth = getProgressBarWidth(param.value, param.key)

          return (
            <motion.div
              key={param.key}
              className="bg-black/30 rounded-xl p-4 border border-white/5 hover:border-white/10 transition-all duration-300"
              initial={{ opacity: 0, scale: 0.9 }}
              animate={{ opacity: 1, scale: 1 }}
              transition={{ duration: 0.5, delay: index * 0.1 }}
              whileHover={{ scale: 1.02 }}
            >
              {/* アイコンとラベル */}
              <div className="flex items-center gap-3 mb-3">
                <div className={`p-2 rounded-lg bg-gradient-to-r ${param.color}`}>
                  <Icon size={20} className="text-white" />
                </div>
                <div>
                  <h4 className="text-sm font-semibold text-white">{param.label}</h4>
                  <div className="text-xs text-gray-400">
                    {param.key === 'entanglement' ? 'E' : 
                     param.key === 'consciousness' ? 'Φ' :
                     param.key === 'infoDensity' ? 'ρ_I' :
                     param.key === 'complexity' ? 'C(t)' :
                     param.key === 'genRate' ? 'Γ_gen' : 'Γ_decay'}
                  </div>
                </div>
              </div>

              {/* 値表示 */}
              <div className="mb-3">
                <motion.div 
                  className={`text-xl font-bold ${param.textColor} font-mono`}
                  key={`${param.key}-${phase.id}`}
                  initial={{ scale: 1.2, opacity: 0 }}
                  animate={{ scale: 1, opacity: 1 }}
                  transition={{ duration: 0.3 }}
                >
                  {formatValue(param.value, isRatio)}
                  {param.unit && (
                    <span className="text-sm text-gray-400 ml-1">{param.unit}</span>
                  )}
                </motion.div>
              </div>

              {/* プログレスバー */}
              <div className="relative h-2 bg-black/40 rounded-full overflow-hidden">
                <motion.div
                  className={`h-full bg-gradient-to-r ${param.color} rounded-full`}
                  initial={{ width: 0 }}
                  animate={{ width: `${progressWidth}%` }}
                  transition={{ duration: 0.8, delay: index * 0.1 }}
                />
                
                {/* グロー効果 */}
                <motion.div
                  className={`absolute top-0 h-full bg-gradient-to-r ${param.color} rounded-full opacity-60 blur-sm`}
                  initial={{ width: 0 }}
                  animate={{ width: `${progressWidth}%` }}
                  transition={{ duration: 0.8, delay: index * 0.1 }}
                />
              </div>

              {/* Change rate indicator */}
              {index < parameters.length - 1 && (
                <div className="mt-2 flex items-center gap-1 text-xs">
                  <Zap size={12} className="text-yellow-400" />
                  <span className="text-gray-400">
                    Change rate: {getChangeRate(param.key, phase.id)}
                  </span>
                </div>
              )}
            </motion.div>
          )
        })}
      </div>

      {/* Phase-specific metrics */}
      <motion.div 
        className="mt-6 bg-gradient-to-r from-indigo-500/10 to-purple-500/10 rounded-xl p-4 border border-indigo-500/20"
        initial={{ opacity: 0, y: 10 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: 0.5, delay: 0.8 }}
      >
        <h4 className="text-lg font-semibold text-indigo-300 mb-2">
          📊 {phase.name} Characteristic Metrics
        </h4>
        <div className="grid grid-cols-2 md:grid-cols-4 gap-4 text-sm">
          <div className="text-center">
            <div className="text-cyan-300 font-bold text-lg">
              {((phase.genRate - phase.decayRate) / phase.genRate * 100).toFixed(1)}%
            </div>
            <div className="text-gray-400">Net Information Rate</div>
          </div>
          <div className="text-center">
            <div className="text-purple-300 font-bold text-lg">
              {(Math.log(phase.complexity) / Math.log(1e100) * 100).toFixed(1)}%
            </div>
            <div className="text-gray-400">Complexity Ratio</div>
          </div>
          <div className="text-center">
            <div className="text-green-300 font-bold text-lg">
              {(phase.entanglement * phase.consciousness * 100).toFixed(1)}%
            </div>
            <div className="text-gray-400">Integration Level</div>
          </div>
          <div className="text-center">
            <div className="text-orange-300 font-bold text-lg">
              {(Math.log(phase.infoDensity) / 120).toFixed(2)}
            </div>
            <div className="text-gray-400">Information Efficiency</div>
          </div>
        </div>
      </motion.div>
    </motion.div>
  )
} 