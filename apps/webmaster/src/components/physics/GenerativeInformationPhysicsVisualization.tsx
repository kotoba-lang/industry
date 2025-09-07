'use client'

import { useState, useEffect, useRef } from 'react'
import { motion, AnimatePresence } from 'framer-motion'
import { PhysicsControls } from './PhysicsControls'
import { EquationDisplay } from './EquationDisplay'
import { PhysicsCanvas } from './PhysicsCanvas'
import { TimelineSelector } from './TimelineSelector'
import { ParameterDisplay } from './ParameterDisplay'
import { ComplexityMeter } from './ComplexityMeter'

export interface PhysicsPhase {
  id: number
  name: string
  time: string
  infoDensity: number
  complexity: number
  genRate: number
  decayRate: number
  entanglement: number
  consciousness: number
  equation: string
  description: string
  color: string
}

const physicsPhases: PhysicsPhase[] = [
  {
    id: 0,
    name: "Big Bang",
    time: "t = 0",
    infoDensity: 1e120,
    complexity: 1e100,
    genRate: 1e80,
    decayRate: 0,
    entanglement: 1.0,
    consciousness: 0.0,
    equation: "H = √(8πGρ/3) → ∞",
    description: "Infinite information density and computational complexity at the singularity. All information condensed as the universe's initial condition.",
    color: "#ff6b6b"
  },
  {
    id: 1,
    name: "Inflation",
    time: "t = 10⁻³²s",
    infoDensity: 1e80,
    complexity: 1e60,
    genRate: 1e50,
    decayRate: 1e45,
    entanglement: 0.99,
    consciousness: 0.01,
    equation: "a(t) = a₀ e^(Ht)",
    description: "Exponential expansion dilutes information, but quantum fluctuations generate new information. Computational complexity increases rapidly.",
    color: "#4ecdc4"
  },
  {
    id: 2,
    name: "Quark Confinement",
    time: "t = 10⁻⁶s",
    infoDensity: 1e70,
    complexity: 1e50,
    genRate: 1e40,
    decayRate: 1e35,
    entanglement: 0.95,
    consciousness: 0.05,
    equation: "QCD: g²/(12π) → 0",
    description: "Strong interactions form protons and neutrons. New hierarchy of information processing emerges through quantum chromodynamics.",
    color: "#45b7d1"
  },
  {
    id: 3,
    name: "Primordial Nucleosynthesis",
    time: "t = 10²s",
    infoDensity: 1e65,
    complexity: 1e45,
    genRate: 1e35,
    decayRate: 1e30,
    entanglement: 0.90,
    consciousness: 0.10,
    equation: "⁴He + ²H → ⁶Li + γ",
    description: "Light element synthesis fixes nuclear structure information. Element abundance ratios provide foundation for information processing.",
    color: "#96ceb4"
  },
  {
    id: 4,
    name: "Recombination",
    time: "t = 3.8×10⁵ years",
    infoDensity: 1e60,
    complexity: 1e40,
    genRate: 1e30,
    decayRate: 1e25,
    entanglement: 0.80,
    consciousness: 0.20,
    equation: "p + e⁻ → H + γ",
    description: "Hydrogen atom formation allows photons to travel freely. Information preserved as cosmic microwave background radiation.",
    color: "#ffeaa7"
  },
  {
    id: 5,
    name: "First Stars",
    time: "t = 10⁸ years",
    infoDensity: 1e55,
    complexity: 1e35,
    genRate: 1e25,
    decayRate: 1e20,
    entanglement: 0.70,
    consciousness: 0.30,
    equation: "M_Jeans = (kT/Gm)^(3/2) ρ^(-1/2)",
    description: "Gravitational collapse births first stars. Nuclear fusion generates heavy elements, enabling chemical complexity.",
    color: "#fd79a8"
  },
  {
    id: 6,
    name: "Galaxy Formation",
    time: "t = 10⁹ years",
    infoDensity: 1e50,
    complexity: 1e30,
    genRate: 1e20,
    decayRate: 1e15,
    entanglement: 0.60,
    consciousness: 0.50,
    equation: "t_ff = √(3π/32Gρ)",
    description: "Galaxy-scale structure formation. Stellar system formation establishes planetary environments, enabling complex chemical evolution.",
    color: "#a29bfe"
  },
  {
    id: 7,
    name: "Present",
    time: "t = 13.8×10⁹ years",
    infoDensity: 1e45,
    complexity: 1e25,
    genRate: 1e15,
    decayRate: 1e10,
    entanglement: 0.50,
    consciousness: 0.80,
    equation: "Φ = ∫ φ(x) log φ(x) dx",
    description: "Life and consciousness emerge. Information processing capabilities improve dramatically through biological evolution. Conscious self-recognition of the universe.",
    color: "#6c5ce7"
  }
]

export function GenerativeInformationPhysicsVisualization() {
  const [currentPhase, setCurrentPhase] = useState(0)
  const [isPlaying, setIsPlaying] = useState(false)
  const [quantumMode, setQuantumMode] = useState(false)
  const [showParameters, setShowParameters] = useState(true)
  const [speed, setSpeed] = useState(1)
  const animationRef = useRef<NodeJS.Timeout>()

  const currentPhaseData = physicsPhases[currentPhase]

  // Auto-progression animation
  useEffect(() => {
    if (isPlaying) {
      animationRef.current = setInterval(() => {
        setCurrentPhase(prev => (prev + 1) % physicsPhases.length)
      }, 4000 / speed)
    } else {
      if (animationRef.current) {
        clearInterval(animationRef.current)
      }
    }

    return () => {
      if (animationRef.current) {
        clearInterval(animationRef.current)
      }
    }
  }, [isPlaying, speed])

  const handlePhaseChange = (phaseId: number) => {
    setCurrentPhase(phaseId)
  }

  const handlePlayPause = () => {
    setIsPlaying(!isPlaying)
  }

  const handleReset = () => {
    setIsPlaying(false)
    setCurrentPhase(0)
  }

  const handleQuantumModeToggle = () => {
    setQuantumMode(!quantumMode)
  }

  const handleSpeedChange = (newSpeed: number) => {
    setSpeed(newSpeed)
  }

  return (
    <div className="w-full max-w-7xl mx-auto space-y-6">


      {/* Control panel */}
      <PhysicsControls
        isPlaying={isPlaying}
        quantumMode={quantumMode}
        speed={speed}
        showParameters={showParameters}
        onPlayPause={handlePlayPause}
        onReset={handleReset}
        onQuantumModeToggle={handleQuantumModeToggle}
        onSpeedChange={handleSpeedChange}
        onParametersToggle={() => setShowParameters(!showParameters)}
      />
      
      {/* Main visualization */}
      <motion.div 
        className="relative bg-black/30 backdrop-blur-sm rounded-3xl border border-white/10 overflow-hidden"
        initial={{ opacity: 0, y: 20 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: 0.8 }}
      >
        {/* Timeline */}
        <TimelineSelector
          phases={physicsPhases}
          currentPhase={currentPhase}
          onPhaseSelect={handlePhaseChange}
        />
        
        {/* Canvas */}
        <div className="relative">
          <PhysicsCanvas
            phase={currentPhaseData}
            quantumMode={quantumMode}
            isPlaying={isPlaying}
          />
          
          {/* Phase indicator */}
          <motion.div 
            className="absolute top-4 right-4 bg-black/50 backdrop-blur-sm rounded-xl px-4 py-2 text-white"
            key={currentPhase}
            initial={{ opacity: 0, scale: 0.8 }}
            animate={{ opacity: 1, scale: 1 }}
            transition={{ duration: 0.3 }}
          >
            <div className="font-bold text-lg">{currentPhaseData.name}</div>
            <div className="text-sm text-gray-300">{currentPhaseData.time}</div>
          </motion.div>
        </div>
      </motion.div>


      {/* Equation display */}
      <EquationDisplay
        equation={currentPhaseData.equation}
        description={currentPhaseData.description}
        phase={currentPhaseData}
      />

      {/* Parameter display */}
      <AnimatePresence>
        {showParameters && (
          <motion.div
            initial={{ opacity: 0, height: 0 }}
            animate={{ opacity: 1, height: 'auto' }}
            exit={{ opacity: 0, height: 0 }}
            transition={{ duration: 0.3 }}
          >
            <ParameterDisplay phase={currentPhaseData} />
          </motion.div>
        )}
      </AnimatePresence>

      {/* Complexity meter */}
      <ComplexityMeter 
        phase={currentPhaseData}
        totalPhases={physicsPhases.length}
      />
    </div>
  )
} 