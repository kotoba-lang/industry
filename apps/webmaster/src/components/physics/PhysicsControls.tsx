'use client'

import { Play, Pause, RotateCcw, Zap, Settings, Eye, EyeOff } from 'lucide-react'
import { motion } from 'framer-motion'

interface PhysicsControlsProps {
  isPlaying: boolean
  quantumMode: boolean
  speed: number
  showParameters: boolean
  onPlayPause: () => void
  onReset: () => void
  onQuantumModeToggle: () => void
  onSpeedChange: (speed: number) => void
  onParametersToggle: () => void
}

export function PhysicsControls({
  isPlaying,
  quantumMode,
  speed,
  showParameters,
  onPlayPause,
  onReset,
  onQuantumModeToggle,
  onSpeedChange,
  onParametersToggle,
}: PhysicsControlsProps) {
  const speedOptions = [0.5, 1, 1.5, 2, 3]

  return (
    <motion.div 
      className="bg-black/20 backdrop-blur-sm rounded-2xl border border-white/10 p-6"
      initial={{ opacity: 0, y: 20 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: 0.6, delay: 0.2 }}
    >
      <div className="flex flex-wrap items-center justify-center gap-4">
        {/* Play Controls */}
        <div className="flex items-center gap-2">
          <motion.button
            onClick={onPlayPause}
            className={`flex items-center gap-2 px-6 py-3 rounded-xl font-semibold transition-all duration-300 ${
              isPlaying 
                ? 'bg-red-500 hover:bg-red-600 text-white' 
                : 'bg-green-500 hover:bg-green-600 text-white'
            }`}
            whileHover={{ scale: 1.05 }}
            whileTap={{ scale: 0.95 }}
          >
            {isPlaying ? <Pause size={20} /> : <Play size={20} />}
            {isPlaying ? 'Stop' : 'Start'}
          </motion.button>

          <motion.button
            onClick={onReset}
            className="flex items-center gap-2 px-4 py-3 rounded-xl bg-gray-600 hover:bg-gray-700 text-white transition-all duration-300"
            whileHover={{ scale: 1.05 }}
            whileTap={{ scale: 0.95 }}
          >
            <RotateCcw size={20} />
            Reset
          </motion.button>
        </div>

        {/* Speed Control */}
        <div className="flex items-center gap-2">
          <span className="text-white text-sm font-medium">Speed:</span>
          <div className="flex gap-1">
            {speedOptions.map((speedOption) => (
              <motion.button
                key={speedOption}
                onClick={() => onSpeedChange(speedOption)}
                className={`px-3 py-2 rounded-lg text-sm font-medium transition-all duration-300 ${
                  speed === speedOption
                    ? 'bg-blue-500 text-white'
                    : 'bg-white/10 text-gray-300 hover:bg-white/20'
                }`}
                whileHover={{ scale: 1.05 }}
                whileTap={{ scale: 0.95 }}
              >
                {speedOption}×
              </motion.button>
            ))}
          </div>
        </div>

        {/* Quantum Mode */}
        <motion.button
          onClick={onQuantumModeToggle}
          className={`flex items-center gap-2 px-4 py-3 rounded-xl font-semibold transition-all duration-300 ${
            quantumMode
              ? 'bg-purple-500 hover:bg-purple-600 text-white'
              : 'bg-white/10 hover:bg-white/20 text-gray-300'
          }`}
          whileHover={{ scale: 1.05 }}
          whileTap={{ scale: 0.95 }}
        >
          <Zap size={20} />
          Quantum Mode
        </motion.button>

        {/* Parameters Toggle */}
        <motion.button
          onClick={onParametersToggle}
          className={`flex items-center gap-2 px-4 py-3 rounded-xl font-semibold transition-all duration-300 ${
            showParameters
              ? 'bg-cyan-500 hover:bg-cyan-600 text-white'
              : 'bg-white/10 hover:bg-white/20 text-gray-300'
          }`}
          whileHover={{ scale: 1.05 }}
          whileTap={{ scale: 0.95 }}
        >
          {showParameters ? <Eye size={20} /> : <EyeOff size={20} />}
          Parameters
        </motion.button>

        {/* Settings */}
        <motion.button
          className="flex items-center gap-2 px-4 py-3 rounded-xl bg-white/10 hover:bg-white/20 text-gray-300 transition-all duration-300"
          whileHover={{ scale: 1.05 }}
          whileTap={{ scale: 0.95 }}
        >
          <Settings size={20} />
          Settings
        </motion.button>
      </div>

      {/* Help Text */}
      <div className="mt-4 text-center text-sm text-gray-400">
        <p>Click on each stage or use the play button to observe the evolution of the universe</p>
      </div>
    </motion.div>
  )
} 