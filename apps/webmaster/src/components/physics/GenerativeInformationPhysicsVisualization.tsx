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
    name: "BigBang",
    time: "t = 0",
    infoDensity: 1e120,
    complexity: 1e100,
    genRate: 1e80,
    decayRate: 0,
    entanglement: 1.0,
    consciousness: 0.0,
    equation: "H = √(8πGρ/3) → ∞",
    description: "特異点における無限の情報密度と計算複雑性。宇宙の初期条件として全ての情報が凝縮。",
    color: "#ff6b6b"
  },
  {
    id: 1,
    name: "インフレーション",
    time: "t = 10⁻³²s",
    infoDensity: 1e80,
    complexity: 1e60,
    genRate: 1e50,
    decayRate: 1e45,
    entanglement: 0.99,
    consciousness: 0.01,
    equation: "a(t) = a₀ e^(Ht)",
    description: "指数的膨張により情報が希釈されるが、量子揺らぎが新しい情報を生成。計算複雑性は急速に増大。",
    color: "#4ecdc4"
  },
  {
    id: 2,
    name: "クォーク閉じ込め",
    time: "t = 10⁻⁶s",
    infoDensity: 1e70,
    complexity: 1e50,
    genRate: 1e40,
    decayRate: 1e35,
    entanglement: 0.95,
    consciousness: 0.05,
    equation: "QCD: g²/(12π) → 0",
    description: "強い相互作用により陽子・中性子が形成。量子色力学による情報処理の新しい階層が出現。",
    color: "#45b7d1"
  },
  {
    id: 3,
    name: "原始核合成",
    time: "t = 10²s",
    infoDensity: 1e65,
    complexity: 1e45,
    genRate: 1e35,
    decayRate: 1e30,
    entanglement: 0.90,
    consciousness: 0.10,
    equation: "⁴He + ²H → ⁶Li + γ",
    description: "軽元素合成により核構造の情報が固定化。元素存在比が情報処理の基盤を提供。",
    color: "#96ceb4"
  },
  {
    id: 4,
    name: "再結合",
    time: "t = 3.8×10⁵年",
    infoDensity: 1e60,
    complexity: 1e40,
    genRate: 1e30,
    decayRate: 1e25,
    entanglement: 0.80,
    consciousness: 0.20,
    equation: "p + e⁻ → H + γ",
    description: "水素原子の形成により光子が自由に伝播。宇宙背景放射として情報が保存される。",
    color: "#ffeaa7"
  },
  {
    id: 5,
    name: "最初の星",
    time: "t = 10⁸年",
    infoDensity: 1e55,
    complexity: 1e35,
    genRate: 1e25,
    decayRate: 1e20,
    entanglement: 0.70,
    consciousness: 0.30,
    equation: "M_Jeans = (kT/Gm)^(3/2) ρ^(-1/2)",
    description: "重力収縮により最初の星が誕生。核融合により重元素が生成され、化学的複雑性が出現。",
    color: "#fd79a8"
  },
  {
    id: 6,
    name: "銀河形成",
    time: "t = 10⁹年",
    infoDensity: 1e50,
    complexity: 1e30,
    genRate: 1e20,
    decayRate: 1e15,
    entanglement: 0.60,
    consciousness: 0.50,
    equation: "t_ff = √(3π/32Gρ)",
    description: "銀河スケールの構造形成。恒星系の形成により惑星環境が整備され、複雑な化学進化が可能に。",
    color: "#a29bfe"
  },
  {
    id: 7,
    name: "現在",
    time: "t = 13.8×10⁹年",
    infoDensity: 1e45,
    complexity: 1e25,
    genRate: 1e15,
    decayRate: 1e10,
    entanglement: 0.50,
    consciousness: 0.80,
    equation: "Φ = ∫ φ(x) log φ(x) dx",
    description: "生命と意識の出現。情報処理能力が生物学的進化を通じて飛躍的に向上。意識による宇宙の自己認識。",
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

  // 自動進行アニメーション
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


      {/* 制御パネル */}
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
      
      {/* メインビジュアライゼーション */}
      <motion.div 
        className="relative bg-black/30 backdrop-blur-sm rounded-3xl border border-white/10 overflow-hidden"
        initial={{ opacity: 0, y: 20 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: 0.8 }}
      >
        {/* タイムライン */}
        <TimelineSelector
          phases={physicsPhases}
          currentPhase={currentPhase}
          onPhaseSelect={handlePhaseChange}
        />
        
        {/* キャンバス */}
        <div className="relative">
          <PhysicsCanvas
            phase={currentPhaseData}
            quantumMode={quantumMode}
            isPlaying={isPlaying}
          />
          
          {/* フェーズインジケーター */}
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


      {/* 方程式表示 */}
      <EquationDisplay
        equation={currentPhaseData.equation}
        description={currentPhaseData.description}
        phase={currentPhaseData}
      />

      {/* パラメータ表示 */}
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

      {/* 複雑性メーター */}
      <ComplexityMeter 
        phase={currentPhaseData}
        totalPhases={physicsPhases.length}
      />
    </div>
  )
} 