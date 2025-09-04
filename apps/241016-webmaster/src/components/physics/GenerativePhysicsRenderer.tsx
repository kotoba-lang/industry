'use client'

import React, { useRef, useEffect, useState, useMemo } from 'react'
import * as THREE from 'three'
import { Canvas, useFrame } from '@react-three/fiber'
import { OrbitControls } from '@react-three/drei'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Slider } from '@/components/ui/slider'
import { Badge } from '@/components/ui/badge'

/**
 * 生成物理学の数学的基盤
 * physics_restructuredプロジェクトの数式に基づく3Dレンダリングシステム
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
  gamma_thermal: 0.001, // 熱力学補正係数
  // 宇宙論パラメータ
  Omega_m: 0.3153,
  Omega_Lambda: 0.6847,
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
 * 情報密度可視化用のパーティクルシステム
 */
function InformationDensityField({ state }: { state: PhysicsState }) {
  const meshRef = useRef<THREE.Mesh>(null)
  
  const particleCount = Math.floor(state.informationDensity / 1e77) // スケール調整
  
  const particles = useMemo(() => {
    const positions = new Float32Array(particleCount * 3)
    
    for (let i = 0; i < particleCount; i++) {
      const phi = Math.random() * 2 * Math.PI
      const theta = Math.random() * Math.PI
      const radius = 20 + Math.random() * 30
      
      positions[i * 3] = radius * Math.sin(theta) * Math.cos(phi)
      positions[i * 3 + 1] = radius * Math.sin(theta) * Math.sin(phi)
      positions[i * 3 + 2] = radius * Math.cos(theta)
    }
    
    return positions
  }, [particleCount])

  useFrame(() => {
    if (meshRef.current) {
      meshRef.current.rotation.y += 0.01
      meshRef.current.rotation.x += 0.005
    }
  })

  const energyFactor = state.landauerEnergy / 1e-10
  const hue = (energyFactor * 0.7) % 1
  const color = new THREE.Color().setHSL(hue, 0.8, 0.6)

  return (
    <mesh ref={meshRef}>
      <bufferGeometry>
        <bufferAttribute
          attach="attributes-position"
          count={particles.length / 3}
          array={particles}
          itemSize={3}
        />
      </bufferGeometry>
      <pointsMaterial size={0.5} color={color} transparent opacity={0.7} />
    </mesh>
  )
}

/**
 * 量子情報状態の可視化
 */
function QuantumInformationVisualization({ state }: { state: PhysicsState }) {
  const meshRef = useRef<THREE.Group>(null)

  useFrame(() => {
    if (meshRef.current) {
      meshRef.current.rotation.y += state.quantumCoherence * 0.02
      meshRef.current.rotation.z += state.quantumCoherence * 0.01
    }
  })

  const quantumColor = new THREE.Color().setHSL(state.quantumCoherence, 0.7, 0.5)
  const entanglementColor = new THREE.Color().setHSL((state.quantumCoherence + 0.3) % 1, 0.7, 0.5)

  return (
    <group ref={meshRef}>
      {/* 量子状態の重ね合わせ */}
      <mesh>
        <sphereGeometry args={[2, 32, 32]} />
        <meshPhongMaterial 
          color={quantumColor}
          opacity={state.quantumCoherence}
          transparent
        />
      </mesh>
      
      {/* 量子もつれ表現 */}
      <mesh position={[5, 0, 0]}>
        <sphereGeometry args={[1, 16, 16]} />
        <meshPhongMaterial 
          color={entanglementColor}
          opacity={state.quantumCoherence * 0.8}
          transparent
        />
      </mesh>
      
      {/* 量子情報の流れ */}
      <mesh>
        <cylinderGeometry args={[0.1, 0.1, 5, 8]} />
        <meshBasicMaterial 
          color={new THREE.Color().setHSL(state.quantumCoherence * 0.5, 1, 0.5)}
          opacity={state.quantumCoherence}
          transparent
        />
      </mesh>
    </group>
  )
}

/**
 * 意識と宇宙の相互作用可視化
 */
function ConsciousnessCosmosInteraction({ state }: { state: PhysicsState }) {
  const meshRef = useRef<THREE.Group>(null)

  useFrame(() => {
    if (meshRef.current) {
      const scale = 1 + state.consciousnessLevel * 0.5
      meshRef.current.scale.setScalar(scale)
      meshRef.current.rotation.y += state.consciousnessLevel * 0.01
    }
  })

  const consciousnessColor = new THREE.Color().setHSL(state.consciousnessLevel * 0.8, 0.9, 0.6)
  const cosmicColor = new THREE.Color().setHSL((state.consciousnessLevel * 0.3) % 1, 0.8, 0.4)

  return (
    <group ref={meshRef} position={[0, 10, 0]}>
      {/* 個体意識の表現 */}
      <mesh>
        <boxGeometry args={[2, 2, 2]} />
        <meshPhongMaterial 
          color={consciousnessColor}
          opacity={0.7}
          transparent
        />
      </mesh>
      
      {/* 宇宙意識への拡張 */}
      <mesh>
        <octahedronGeometry args={[4, 2]} />
        <meshPhongMaterial 
          color={cosmicColor}
          opacity={state.consciousnessLevel * 0.5}
          transparent
          wireframe
        />
      </mesh>
    </group>
  )
}

/**
 * 構造形成の時間進化可視化
 */
function StructureFormationEvolution({ state }: { state: PhysicsState }) {
  return (
    <group position={[0, -10, 0]}>
      {Array.from({ length: 8 }, (_, i) => {
        const z = i * 0.5
        const growth = calculateStructureGrowth(z)
        const size = growth * 2
        const color = new THREE.Color().setHSL(growth * 0.6, 0.8, 0.5)
        
        return (
          <mesh key={i} position={[i * 3 - 10, 0, 0]}>
            <sphereGeometry args={[size, 16, 16]} />
            <meshPhongMaterial 
              color={color}
              opacity={growth}
              transparent
            />
          </mesh>
        )
      })}
    </group>
  )
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
 * 3Dシーンコンポーネント
 */
function PhysicsScene({ state }: { state: PhysicsState }) {
  return (
    <>
      <ambientLight intensity={0.5} />
      <pointLight position={[10, 10, 10]} />
      <directionalLight position={[0, 10, 5]} intensity={1} />
      
      <InformationDensityField state={state} />
      <QuantumInformationVisualization state={state} />
      <ConsciousnessCosmosInteraction state={state} />
      <StructureFormationEvolution state={state} />
      
      <OrbitControls />
    </>
  )
}

/**
 * メインコンポーネント
 */
export default function GenerativePhysicsRenderer() {
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
    <div className="w-full h-screen bg-black text-white">
      <div className="absolute inset-0 z-0">
        <Canvas camera={{ position: [0, 0, 50], fov: 60 }}>
          <PhysicsScene state={state} />
        </Canvas>
      </div>
      
      {/* 制御UI */}
      <div className="absolute top-4 left-4 z-10 space-y-4">
        <Card className="bg-black/80 border-white/20">
          <CardHeader>
            <CardTitle className="text-white">🌌 生成物理学 3Dレンダリング</CardTitle>
            <CardDescription className="text-gray-300">
              情報密度進化・量子情報・意識と宇宙の相互作用
            </CardDescription>
          </CardHeader>
          <CardContent className="space-y-4">
            <div>
              <label className="text-sm font-medium text-white">赤方偏移 (z): {redshift.toFixed(2)}</label>
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
              <Badge variant="secondary">{state.informationDensity.toExponential(2)} bits/Mpc³</Badge>
            </div>
            <div className="flex justify-between">
              <span className="text-sm text-gray-300">ランダウアーエネルギー:</span>
              <Badge variant="secondary">{state.landauerEnergy.toExponential(2)} J</Badge>
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
      
      {/* 理論解説 */}
      <div className="absolute bottom-4 right-4 z-10">
        <Card className="bg-black/80 border-white/20 max-w-md">
          <CardHeader>
            <CardTitle className="text-white">🔬 理論的基盤</CardTitle>
          </CardHeader>
          <CardContent className="text-sm text-gray-300">
            <p>
              この3Dレンダリングは、physics_restructuredの生成物理学理論に基づいています：
            </p>
            <ul className="mt-2 space-y-1">
              <li>• 情報密度進化の可視化</li>
              <li>• ランダウアーの原理による色彩変換</li>
              <li>• 量子情報処理の3D表現</li>
              <li>• 意識と宇宙の相互作用</li>
              <li>• 構造形成の時間進化</li>
            </ul>
          </CardContent>
        </Card>
      </div>
    </div>
  )
} 