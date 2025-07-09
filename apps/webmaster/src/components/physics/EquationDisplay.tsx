'use client'

import { motion } from 'framer-motion'
import { PhysicsPhase } from './GenerativeInformationPhysicsVisualization'
import { BookOpen, Calculator, Atom } from 'lucide-react'

interface EquationDisplayProps {
  equation: string
  description: string
  phase: PhysicsPhase
}

export function EquationDisplay({ equation, description, phase }: EquationDisplayProps) {
  return (
    <motion.div 
      className="bg-gradient-to-r from-black/20 to-black/30 backdrop-blur-sm rounded-2xl border border-white/10 p-6"
      initial={{ opacity: 0, y: 20 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: 0.6, delay: 0.4 }}
    >
      <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
        {/* Equation section */}
        <motion.div 
          className="bg-black/30 rounded-xl p-6 border border-white/5"
          key={phase.id}
          initial={{ opacity: 0, scale: 0.9 }}
          animate={{ opacity: 1, scale: 1 }}
          transition={{ duration: 0.5 }}
        >
          <div className="flex items-center gap-3 mb-4">
            <Calculator className="text-cyan-400" size={24} />
            <h3 className="text-xl font-bold text-white">Governing Equation</h3>
          </div>
          
          <div className="bg-black/40 rounded-lg p-4 border border-cyan-500/20">
            <motion.div
              className="font-mono text-lg md:text-xl text-cyan-300 text-center"
              style={{ 
                textShadow: '0 0 10px rgba(103, 232, 249, 0.5)',
                fontFamily: 'Fira Code, Monaco, Consolas, monospace'
              }}
              initial={{ opacity: 0 }}
              animate={{ opacity: 1 }}
              transition={{ duration: 0.8, delay: 0.2 }}
            >
              {equation}
            </motion.div>
          </div>
          
          {/* Equation meaning */}
          <div className="mt-4 text-sm text-gray-300">
            <div className="flex items-start gap-2">
              <Atom className="text-purple-400 mt-1 flex-shrink-0" size={16} />
              <div>
                <p className="font-semibold text-purple-400 mb-1">Physical Meaning:</p>
                <p>{getEquationMeaning(phase.name)}</p>
              </div>
            </div>
          </div>
        </motion.div>

        {/* Description section */}
        <motion.div 
          className="bg-black/30 rounded-xl p-6 border border-white/5"
          key={`desc-${phase.id}`}
          initial={{ opacity: 0, x: 20 }}
          animate={{ opacity: 1, x: 0 }}
          transition={{ duration: 0.5, delay: 0.1 }}
        >
          <div className="flex items-center gap-3 mb-4">
            <BookOpen className="text-green-400" size={24} />
            <h3 className="text-xl font-bold text-white">Physical Process</h3>
          </div>
          
          <motion.p 
            className="text-gray-300 leading-relaxed"
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            transition={{ duration: 0.8, delay: 0.3 }}
          >
            {description}
          </motion.p>
          
          {/* Important physical quantities */}
          <div className="mt-4 space-y-2">
            <h4 className="text-sm font-semibold text-yellow-400">Key Physical Quantities:</h4>
            <div className="grid grid-cols-2 gap-2 text-xs">
              <div className="bg-black/40 rounded p-2">
                <div className="text-cyan-300 font-mono">ρ_I</div>
                <div className="text-gray-400">Information Density</div>
              </div>
              <div className="bg-black/40 rounded p-2">
                <div className="text-purple-300 font-mono">C(t)</div>
                <div className="text-gray-400">Computational Complexity</div>
              </div>
              <div className="bg-black/40 rounded p-2">
                <div className="text-green-300 font-mono">E</div>
                <div className="text-gray-400">Quantum Entanglement</div>
              </div>
              <div className="bg-black/40 rounded p-2">
                <div className="text-orange-300 font-mono">Φ</div>
                <div className="text-gray-400">Consciousness Index</div>
              </div>
            </div>
          </div>
        </motion.div>
      </div>
      
      {/* Phase-specific insights */}
      <motion.div 
        className="mt-6 bg-gradient-to-r from-indigo-500/10 to-purple-500/10 rounded-xl p-4 border border-indigo-500/20"
        initial={{ opacity: 0, y: 10 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: 0.5, delay: 0.6 }}
      >
        <h4 className="text-lg font-semibold text-indigo-300 mb-2">
          🔬 {phase.name} Key Insights
        </h4>
        <p className="text-gray-300 text-sm">
          {getPhaseInsight(phase.name)}
        </p>
      </motion.div>
    </motion.div>
  )
}

function getEquationMeaning(phaseName: string): string {
  const meanings: Record<string, string> = {
    "Big Bang": "Singularity where the Hubble parameter diverges and spacetime curvature reaches infinity",
    "Inflation": "Rapid expansion period where the scale factor increases exponentially",
    "Quark Confinement": "QCD coupling constant decreases and quarks form bound states",
    "Primordial Nucleosynthesis": "Light element synthesis through helium and deuterium fusion reactions",
    "Recombination": "Process where protons and electrons combine to form neutral hydrogen atoms",
    "First Stars": "Conditions for star formation initiated by gravitational instability via Jeans mass",
    "Galaxy Formation": "Gravitational collapse of large-scale structures on free-fall timescales",
    "Present": "Integrated Information Theory of consciousness defined by information integration"
  }
  return meanings[phaseName] || "Fundamental physical laws governing cosmic evolution"
}

function getPhaseInsight(phaseName: string): string {
  const insights: Record<string, string> = {
    "Big Bang": "At the singularity where information density becomes infinite, all physical laws exist in a unified state. From an information processing perspective, this can be interpreted as the universe's 'initial program' in condensed form.",
    "Inflation": "The moment when quantum fluctuations transition to classical density fluctuations. Information-theoretically, this can be understood as the process where quantum information 'decoheres' into classical information.",
    "Quark Confinement": "Phase transition through strong interactions changes the hierarchy from quark-level to nucleon-level information processing.",
    "Primordial Nucleosynthesis": "Nucleon binding creates more complex information structures (atomic nuclei). This becomes the foundation of the universe's 'chemical memory'.",
    "Recombination": "Electron-nucleus binding shields electromagnetic interactions, allowing photons to propagate freely. This makes the universe 'transparent' and enables long-distance information transmission.",
    "First Stars": "Gravitational collapse births the first 'information processing devices' - stars - as dissipative structures. Nuclear fusion is the universe's first sustained energy conversion process.",
    "Galaxy Formation": "Multiple stellar systems gravitationally bind to form larger, more complex information processing systems. Galaxies serve as cosmic 'cities'.",
    "Present": "Consciousness emerges through biological evolution, granting the universe the ability to recognize and understand itself. This can be interpreted as the universe's 'self-awakening'."
  }
  return insights[phaseName] || "A critical turning point in cosmic evolution."
} 