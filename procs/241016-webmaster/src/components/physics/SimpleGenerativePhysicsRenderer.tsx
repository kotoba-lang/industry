'use client'

import React, { useRef, useEffect, useState } from 'react'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Slider } from '@/components/ui/slider'
import { Badge } from '@/components/ui/badge'

/**
 * 生成物理学の数学的基盤を使った簡潔な3Dレンダリングシステム
 * physics_restructuredプロジェクトの数式に基づく可視化
 */

// 物理定数
const PHYSICS_CONSTANTS = {
  k_B: 1.381e-23,     // J/K - ボルツマン定数
  hbar: 1.055e-34,    // J·s - 換算プランク定数
  c: 2.998e8,         // m/s - 光速
  T_CMB: 2.725,       // K - CMB温度
  // 情報物理学パラメータ
  beta_info: 0.075,   // 情報補正係数
  alpha_quantum: 0.02, // 量子情報効果係数
  // 宇宙論パラメータ
  Omega_m: 0.3153,
  sigma_8: 0.8111,
  H_0: 67.66,
}

interface PhysicsState {
  redshift: number
  informationDensity: number
  quantumCoherence: number
  consciousnessLevel: number
  landauerEnergy: number
  cosmicTemperature: number
  structureGrowth: number
}

/**
 * 情報密度進化の計算
 * rho_info = rho_info_base × structure_growth × decoherence_factor
 */
function calculateInformationDensity(z: number): number {
  const rho_info_base = 1e80 // bits/Mpc³
  const structure_growth = 1 - Math.exp(-z / 100)
  const decoherence_factor = Math.exp(-PHYSICS_CONSTANTS.alpha_quantum * z)
  return rho_info_base * structure_growth * decoherence_factor
}

/**
 * ランダウアーの原理に基づくエネルギー計算
 * E_info = n_bits × k_B × T × ln(2) [J]
 */
function calculateLandauerEnergy(nBits: number, temperature: number): number {
  return nBits * PHYSICS_CONSTANTS.k_B * temperature * Math.log(2)
}

/**
 * 宇宙の温度進化
 * T(z) = T_CMB × (1 + z)
 */
function calculateCosmicTemperature(z: number): number {
  return PHYSICS_CONSTANTS.T_CMB * (1 + z)
}

/**
 * 意識レベルの計算
 * Φ_consciousness = |⟨Ψ_cosmic|Ψ_neural⟩|²
 */
function calculateConsciousnessLevel(informationDensity: number, quantumCoherence: number): number {
  const cosmic_state = Math.sqrt(informationDensity / 1e80)
  const neural_state = quantumCoherence
  return Math.abs(cosmic_state * neural_state) ** 2
}

/**
 * 構造形成進化の計算
 */
function calculateStructureGrowth(z: number): number {
  const growth_factor = 1 / (1 + z)
  const info_correction = 1 + PHYSICS_CONSTANTS.beta_info * Math.exp(-z / 50)
  return growth_factor * info_correction
}

/**
 * 物理状態の更新
 */
function usePhysicsState(redshift: number): PhysicsState {
  const [state, setState] = useState<PhysicsState>({
    redshift: 0,
    informationDensity: 0,
    quantumCoherence: 0,
    consciousnessLevel: 0,
    landauerEnergy: 0,
    cosmicTemperature: 0,
    structureGrowth: 0,
  })

  useEffect(() => {
    const informationDensity = calculateInformationDensity(redshift)
    const cosmicTemperature = calculateCosmicTemperature(redshift)
    const landauerEnergy = calculateLandauerEnergy(informationDensity, cosmicTemperature)
    const quantumCoherence = Math.exp(-redshift / 10)
    const consciousnessLevel = calculateConsciousnessLevel(informationDensity, quantumCoherence)
    const structureGrowth = calculateStructureGrowth(redshift)

    setState({
      redshift,
      informationDensity,
      quantumCoherence,
      consciousnessLevel,
      landauerEnergy,
      cosmicTemperature,
      structureGrowth,
    })
  }, [redshift])

  return state
}

/**
 * CSS3Dによる生成物理学可視化
 */
function PhysicsVisualization({ state }: { state: PhysicsState }) {
  // 物理状態に基づく動的スタイル
  const energyFactor = state.landauerEnergy / 1e-10
  const hue = (energyFactor * 360) % 360
  const saturation = Math.min(state.quantumCoherence * 100, 100)
  const lightness = 20 + state.consciousnessLevel * 30
  
  const backgroundStyle = {
    background: `radial-gradient(circle at center, 
      hsl(${hue}, ${saturation}%, ${lightness}%) 0%,
      hsl(${(hue + 60) % 360}, ${saturation * 0.8}%, ${lightness * 0.5}%) 50%,
      black 100%
    )`,
  }
  
  // 情報密度パーティクルの生成
  const particles = Array.from({ length: Math.floor(state.informationDensity / 1e78) }, (_, i) => ({
    id: i,
    style: {
      position: 'absolute' as const,
      width: '3px',
      height: '3px',
      borderRadius: '50%',
      background: `hsl(${(hue + i * 30) % 360}, 90%, 70%)`,
      left: `${Math.random() * 100}%`,
      top: `${Math.random() * 100}%`,
      opacity: state.quantumCoherence,
      animation: `float ${3 + Math.random() * 2}s ease-in-out infinite`,
      animationDelay: `${Math.random() * 2}s`,
    },
  }))
  
  // 量子もつれ効果のスタイル
  const entangleStyle = {
    position: 'absolute' as const,
    top: '50%',
    left: '50%',
    width: `${state.consciousnessLevel * 300}px`,
    height: `${state.consciousnessLevel * 300}px`,
    border: `2px solid hsl(${hue}, 80%, 60%)`,
    borderRadius: '50%',
    transform: 'translate(-50%, -50%)',
    opacity: state.quantumCoherence * 0.7,
    animation: `pulse ${2 + state.consciousnessLevel * 2}s ease-in-out infinite`,
  }

  return (
    <div 
      className="w-full h-full relative overflow-hidden"
      style={{ 
        perspective: '1000px',
        transformStyle: 'preserve-3d',
        ...backgroundStyle
      }}
    >
      {/* 情報密度パーティクル */}
      {particles.map((particle) => (
        <div
          key={particle.id}
          className="physics-particle"
          style={particle.style}
        />
      ))}
      
      {/* 量子もつれ効果 */}
      <div className="entangle-effect" style={entangleStyle} />
      
      {/* 構造形成の進化表示 */}
      <div className="absolute bottom-10 left-1/2 transform -translate-x-1/2 flex space-x-4">
        {Array.from({ length: 8 }, (_, i) => {
          const z = i * 0.5
          const growth = calculateStructureGrowth(z)
          const size = growth * 40
          const opacity = growth
          
          return (
            <div
              key={i}
              className="rounded-full border-2 border-blue-400"
              style={{
                width: `${size}px`,
                height: `${size}px`,
                opacity,
                backgroundColor: `hsl(${growth * 200}, 70%, 50%)`,
                transform: `translateZ(${growth * 20}px)`,
                animation: `rotate ${5 + growth * 2}s linear infinite`,
              }}
            />
          )
        })}
      </div>
      
      {/* 意識レベル表示 */}
      <div 
        className="absolute top-1/2 left-1/2 transform -translate-x-1/2 -translate-y-1/2"
        style={{
          transform: `translate(-50%, -50%) scale(${1 + state.consciousnessLevel})`,
          opacity: state.consciousnessLevel,
        }}
      >
        <div className="w-8 h-8 bg-yellow-400 rounded-full animate-pulse" />
      </div>
      
      {/* 情報流れエフェクト */}
      <div className="absolute inset-0 pointer-events-none">
        {Array.from({ length: 5 }, (_, i) => (
          <div
            key={i}
            className="absolute w-full h-0.5 bg-gradient-to-r from-transparent via-cyan-400 to-transparent opacity-60"
            style={{
              top: `${20 + i * 15}%`,
              animation: `flow ${2 + i * 0.5}s linear infinite`,
              animationDelay: `${i * 0.3}s`,
            }}
          />
        ))}
      </div>
      
      <style jsx>{`
        @keyframes float {
          0%, 100% { transform: translateY(0px); }
          50% { transform: translateY(-20px); }
        }
        
        @keyframes pulse {
          0%, 100% { transform: translate(-50%, -50%) scale(1); }
          50% { transform: translate(-50%, -50%) scale(1.1); }
        }
        
        @keyframes rotate {
          0% { transform: rotate(0deg) translateZ(var(--z, 0)); }
          100% { transform: rotate(360deg) translateZ(var(--z, 0)); }
        }
        
        @keyframes flow {
          0% { transform: translateX(-100%); }
          100% { transform: translateX(100%); }
        }
      `}</style>
    </div>
  )
}

/**
 * メインコンポーネント
 */
export default function SimpleGenerativePhysicsRenderer() {
  const [redshift, setRedshift] = useState(0)
  const [isPlaying, setIsPlaying] = useState(false)
  const state = usePhysicsState(redshift)

  useEffect(() => {
    let interval: NodeJS.Timeout
    if (isPlaying) {
      interval = setInterval(() => {
        setRedshift(prev => (prev + 0.1) % 10)
      }, 100)
    }
    return () => clearInterval(interval)
  }, [isPlaying])

  return (
    <div className="w-full h-screen bg-black text-white relative">
      {/* 3D可視化エリア */}
      <div className="absolute inset-0 z-0">
        <PhysicsVisualization state={state} />
      </div>
      
      {/* 制御UI */}
      <div className="absolute top-4 left-4 z-10 space-y-4">
        <Card className="bg-black/80 border-white/20">
          <CardHeader>
            <CardTitle className="text-white">🌌 生成物理学レンダリング</CardTitle>
            <CardDescription className="text-gray-300">
              情報密度進化・量子効果・意識と宇宙の相互作用
            </CardDescription>
          </CardHeader>
          <CardContent className="space-y-4">
            <div>
              <label className="text-sm font-medium text-white">
                赤方偏移 (z): {redshift.toFixed(2)}
              </label>
              <Slider
                value={[redshift]}
                onValueChange={(value) => setRedshift(value[0])}
                min={0}
                max={10}
                step={0.1}
                className="w-full"
              />
            </div>
            
            <Button 
              onClick={() => setIsPlaying(!isPlaying)}
              className="w-full"
              variant={isPlaying ? "destructive" : "default"}
            >
              {isPlaying ? "停止" : "宇宙進化再生"}
            </Button>
          </CardContent>
        </Card>
        
        {/* 物理状態表示 */}
        <Card className="bg-black/80 border-white/20">
          <CardHeader>
            <CardTitle className="text-white">📊 物理状態</CardTitle>
          </CardHeader>
          <CardContent className="space-y-2">
            <div className="flex justify-between">
              <span className="text-sm text-gray-300">情報密度:</span>
              <Badge variant="secondary">
                {state.informationDensity.toExponential(2)} bits/Mpc³
              </Badge>
            </div>
            <div className="flex justify-between">
              <span className="text-sm text-gray-300">ランダウアーエネルギー:</span>
              <Badge variant="secondary">
                {state.landauerEnergy.toExponential(2)} J
              </Badge>
            </div>
            <div className="flex justify-between">
              <span className="text-sm text-gray-300">量子コヒーレンス:</span>
              <Badge variant="secondary">{state.quantumCoherence.toFixed(3)}</Badge>
            </div>
            <div className="flex justify-between">
              <span className="text-sm text-gray-300">意識レベル:</span>
              <Badge variant="secondary">{state.consciousnessLevel.toFixed(3)}</Badge>
            </div>
            <div className="flex justify-between">
              <span className="text-sm text-gray-300">宇宙温度:</span>
              <Badge variant="secondary">{state.cosmicTemperature.toFixed(2)} K</Badge>
            </div>
            <div className="flex justify-between">
              <span className="text-sm text-gray-300">構造形成:</span>
              <Badge variant="secondary">{state.structureGrowth.toFixed(3)}</Badge>
            </div>
          </CardContent>
        </Card>
      </div>
      
      {/* 数式表示 */}
      <div className="absolute bottom-4 right-4 z-10">
        <Card className="bg-black/80 border-white/20 max-w-md">
          <CardHeader>
            <CardTitle className="text-white">🔬 生成物理学の数式</CardTitle>
          </CardHeader>
          <CardContent className="text-sm text-gray-300">
            <div className="space-y-2">
              <div>
                <strong>情報密度進化:</strong>
                <br />
                <code className="text-cyan-400">
                  ρ_info = ρ_base × (1-e^(-z/100)) × e^(-α_q×z)
                </code>
              </div>
              <div>
                <strong>ランダウアーエネルギー:</strong>
                <br />
                <code className="text-cyan-400">
                  E_info = n_bits × k_B × T × ln(2)
                </code>
              </div>
              <div>
                <strong>意識レベル:</strong>
                <br />
                <code className="text-cyan-400">
                  Φ = |⟨Ψ_cosmic|Ψ_neural⟩|²
                </code>
              </div>
              <div>
                <strong>構造形成:</strong>
                <br />
                <code className="text-cyan-400">
                  G(z) = 1/(1+z) × (1+β×e^(-z/50))
                </code>
              </div>
            </div>
          </CardContent>
        </Card>
      </div>
    </div>
  )
} 