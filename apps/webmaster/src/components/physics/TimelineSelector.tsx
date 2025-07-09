'use client'

import { motion } from 'framer-motion'
import { PhysicsPhase } from './GenerativeInformationPhysicsVisualization'

interface TimelineSelectorProps {
  phases: PhysicsPhase[]
  currentPhase: number
  onPhaseSelect: (phaseId: number) => void
}

export function TimelineSelector({ phases, currentPhase, onPhaseSelect }: TimelineSelectorProps) {
  const timelinePositions = [5, 15, 25, 35, 50, 65, 80, 95] // Percentage positions

  return (
    <div className="relative h-24 bg-gradient-to-r from-black/40 to-black/60 border-b border-white/10">
      {/* タイムライン背景 */}
      <div className="absolute top-1/2 left-12 right-12 h-1 bg-gradient-to-r from-red-500 via-cyan-500 via-blue-500 via-green-500 to-purple-500 rounded-full transform -translate-y-1/2" />
      
      {/* 進行インジケーター */}
      <motion.div
        className="absolute top-1/2 left-12 h-1 bg-white/80 rounded-full transform -translate-y-1/2 shadow-lg"
        initial={{ width: '0%' }}
        animate={{ 
          width: `${(currentPhase / (phases.length - 1)) * 100}%`,
        }}
        transition={{ duration: 0.8, ease: 'easeInOut' }}
      />

      {/* タイムライマーカー */}
      {phases.map((phase, index) => {
        const isActive = index === currentPhase
        const isPast = index < currentPhase
        const leftPosition = `${timelinePositions[index]}%`

        return (
          <motion.div
            key={phase.id}
            className="absolute top-1/2 transform -translate-y-1/2 -translate-x-1/2 cursor-pointer group"
            style={{ left: leftPosition }}
            onClick={() => onPhaseSelect(index)}
            whileHover={{ scale: 1.2 }}
            whileTap={{ scale: 0.95 }}
          >
            {/* マーカー本体 */}
            <motion.div
              className={`w-6 h-6 rounded-full border-4 transition-all duration-300 ${
                isActive
                  ? 'bg-white border-cyan-400 shadow-lg'
                  : isPast
                  ? 'bg-cyan-400 border-cyan-600'
                  : 'bg-gray-600 border-gray-400'
              }`}
              animate={isActive ? { 
                scale: [1, 1.2, 1],
                boxShadow: [
                  '0 0 10px rgba(34, 211, 238, 0.5)',
                  '0 0 20px rgba(34, 211, 238, 0.8)',
                  '0 0 10px rgba(34, 211, 238, 0.5)'
                ]
              } : {}}
              transition={isActive ? { 
                duration: 2, 
                repeat: Infinity,
                ease: 'easeInOut'
              } : {}}
            />

            {/* ホバー時のツールチップ */}
            <motion.div
              className="absolute bottom-full mb-2 left-1/2 transform -translate-x-1/2 opacity-0 group-hover:opacity-100 transition-opacity duration-200"
              initial={{ y: 10, opacity: 0 }}
              whileHover={{ y: 0, opacity: 1 }}
            >
              <div className="bg-black/90 backdrop-blur-sm text-white px-3 py-2 rounded-lg text-sm whitespace-nowrap border border-white/20">
                <div className="font-semibold">{phase.name}</div>
                <div className="text-xs text-gray-300">{phase.time}</div>
                {/* 小さな矢印 */}
                <div className="absolute top-full left-1/2 transform -translate-x-1/2 w-0 h-0 border-l-4 border-r-4 border-t-4 border-transparent border-t-black/90" />
              </div>
            </motion.div>

            {/* フェーズラベル */}
            <div className="absolute top-full mt-2 left-1/2 transform -translate-x-1/2 text-center">
              <div 
                className={`text-xs font-medium transition-all duration-300 ${
                  isActive 
                    ? 'text-cyan-300 scale-110' 
                    : isPast 
                    ? 'text-cyan-400' 
                    : 'text-gray-400'
                }`}
              >
                {phase.name}
              </div>
              <div 
                className={`text-xs transition-all duration-300 mt-1 ${
                  isActive 
                    ? 'text-white' 
                    : 'text-gray-500'
                }`}
              >
                {phase.time}
              </div>
            </div>
          </motion.div>
        )
      })}

      {/* 時間軸ラベル */}
      <div className="absolute bottom-2 left-12 right-12 flex justify-between text-xs text-gray-400">
        <span>BigBang</span>
        <span>現在</span>
      </div>

      {/* 現在時刻の表示 */}
      <motion.div
        className="absolute top-2 left-1/2 transform -translate-x-1/2 bg-black/60 backdrop-blur-sm rounded-lg px-3 py-1 text-sm text-white"
        key={currentPhase}
        initial={{ opacity: 0, y: -10 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: 0.3 }}
      >
        <span className="text-cyan-300">現在:</span> {phases[currentPhase].name}
      </motion.div>
    </div>
  )
} 