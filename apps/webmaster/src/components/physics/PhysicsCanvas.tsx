'use client'

import { useEffect, useRef, useState } from 'react'
import { PhysicsPhase } from './GenerativeInformationPhysicsVisualization'

interface PhysicsCanvasProps {
  phase: PhysicsPhase
  quantumMode: boolean
  isPlaying: boolean
}

interface InformationField {
  x: number
  y: number
  density: number
  velocity: { x: number; y: number }
  phase: number
  frequency: number
}

interface QuantumState {
  x: number
  y: number
  amplitude: number
  phase: number
  frequency: number
}

interface SpaceTimeMetric {
  x: number
  y: number
  curvature: number
}

export function PhysicsCanvas({ phase, quantumMode, isPlaying }: PhysicsCanvasProps) {
  const canvasRef = useRef<HTMLCanvasElement>(null)
  const animationRef = useRef<number>()
  const [informationField, setInformationField] = useState<InformationField[]>([])
  const [quantumStates, setQuantumStates] = useState<QuantumState[]>([])
  const [spaceTimeMetric, setSpaceTimeMetric] = useState<SpaceTimeMetric[]>([])
  const [time, setTime] = useState(0)

  // 初期化
  useEffect(() => {
    initializeSimulation()
  }, [])

  // アニメーションループ
  useEffect(() => {
    if (isPlaying) {
      animate()
    } else {
      if (animationRef.current) {
        cancelAnimationFrame(animationRef.current)
      }
    }

    return () => {
      if (animationRef.current) {
        cancelAnimationFrame(animationRef.current)
      }
    }
  }, [isPlaying, quantumMode, phase])

  const initializeSimulation = () => {
    const width = 800
    const height = 600

    // Information field initialization
    const newInformationField: InformationField[] = []
    for (let i = 0; i < 50; i++) {
      for (let j = 0; j < 40; j++) {
        newInformationField.push({
          x: (i / 50) * width,
          y: (j / 40) * height,
          density: Math.random() * 0.5 + 0.5,
          velocity: {
            x: (Math.random() - 0.5) * 2,
            y: (Math.random() - 0.5) * 2
          },
          phase: Math.random() * Math.PI * 2,
          frequency: Math.random() * 0.05 + 0.02
        })
      }
    }

    // 量子状態の初期化
    const newQuantumStates: QuantumState[] = []
    for (let i = 0; i < 100; i++) {
      newQuantumStates.push({
        x: Math.random() * width,
        y: Math.random() * height,
        amplitude: Math.random(),
        phase: Math.random() * Math.PI * 2,
        frequency: Math.random() * 0.1 + 0.05
      })
    }

    // 時空メトリックの初期化
    const newSpaceTimeMetric: SpaceTimeMetric[] = []
    for (let i = 0; i < width; i += 40) {
      for (let j = 0; j < height; j += 40) {
        newSpaceTimeMetric.push({
          x: i,
          y: j,
          curvature: 0
        })
      }
    }

    setInformationField(newInformationField)
    setQuantumStates(newQuantumStates)
    setSpaceTimeMetric(newSpaceTimeMetric)
  }

  const animate = () => {
    const canvas = canvasRef.current
    if (!canvas) return

    const ctx = canvas.getContext('2d')
    if (!ctx) return

    // キャンバスクリア
    ctx.clearRect(0, 0, canvas.width, canvas.height)

    // 背景グラデーション
    drawBackground(ctx, canvas.width, canvas.height)

            // Information field rendering
    drawInformationField(ctx)

    // 量子状態の描画（量子モード時）
    if (quantumMode) {
      drawQuantumStates(ctx)
    }

    // 時空メトリックの描画
    drawSpaceTimeMetric(ctx)

    // 宇宙論的進化の描画
    drawCosmologicalEvolution(ctx, canvas.width, canvas.height)

            // Consciousness emergence (late stages)
    if (phase.consciousness > 0.1) {
      drawConsciousnessEmergence(ctx, canvas.width, canvas.height)
    }

    // 時間更新
    setTime(prev => prev + 0.1)

    // 物理状態更新
    updatePhysicsStates()

    if (isPlaying) {
      animationRef.current = requestAnimationFrame(animate)
    }
  }

  const drawBackground = (ctx: CanvasRenderingContext2D, width: number, height: number) => {
    const gradient = ctx.createRadialGradient(width / 2, height / 2, 0, width / 2, height / 2, Math.max(width, height) / 2)
    gradient.addColorStop(0, 'rgba(30, 30, 60, 0.8)')
    gradient.addColorStop(1, 'rgba(10, 10, 20, 0.9)')
    
    ctx.fillStyle = gradient
    ctx.fillRect(0, 0, width, height)

    // グリッド描画
    ctx.strokeStyle = 'rgba(255, 255, 255, 0.03)'
    ctx.lineWidth = 1
    for (let x = 0; x < width; x += 40) {
      ctx.beginPath()
      ctx.moveTo(x, 0)
      ctx.lineTo(x, height)
      ctx.stroke()
    }
    for (let y = 0; y < height; y += 40) {
      ctx.beginPath()
      ctx.moveTo(0, y)
      ctx.lineTo(width, y)
      ctx.stroke()
    }
  }

  const drawInformationField = (ctx: CanvasRenderingContext2D) => {
    informationField.forEach((field) => {
      const intensity = field.density * (1 + 0.3 * Math.sin(field.phase + time * 0.1))
      const adjustedIntensity = intensity * (Math.log(phase.infoDensity) / Math.log(1e120))

      const gradient = ctx.createRadialGradient(field.x, field.y, 0, field.x, field.y, 15)
      gradient.addColorStop(0, `rgba(46, 204, 113, ${adjustedIntensity})`)
      gradient.addColorStop(1, 'rgba(46, 204, 113, 0)')

      ctx.fillStyle = gradient
      ctx.beginPath()
      ctx.arc(field.x, field.y, 8 * adjustedIntensity, 0, Math.PI * 2)
      ctx.fill()
    })
  }

  const drawQuantumStates = (ctx: CanvasRenderingContext2D) => {
    ctx.globalAlpha = 0.7

    quantumStates.forEach((state) => {
      const waveFunction = state.amplitude * Math.sin(state.phase + time * state.frequency)
      const entanglementEffect = phase.entanglement

      if (waveFunction > 0) {
        const alpha = waveFunction * entanglementEffect * 0.8
        ctx.fillStyle = `rgba(52, 152, 219, ${alpha})`
        ctx.beginPath()
        ctx.arc(state.x, state.y, 3 + waveFunction * 8, 0, Math.PI * 2)
        ctx.fill()

        // Quantum entanglement lines
        if (Math.random() < 0.1 && entanglementEffect > 0.8) {
          const nearbyState = quantumStates[Math.floor(Math.random() * quantumStates.length)]
          ctx.strokeStyle = `rgba(147, 51, 234, ${alpha * 0.3})`
          ctx.lineWidth = 1
          ctx.beginPath()
          ctx.moveTo(state.x, state.y)
          ctx.lineTo(nearbyState.x, nearbyState.y)
          ctx.stroke()
        }
      }
    })

    ctx.globalAlpha = 1.0
  }

  const drawSpaceTimeMetric = (ctx: CanvasRenderingContext2D) => {
    ctx.globalAlpha = 0.4
    ctx.strokeStyle = `rgba(231, 76, 60, 0.6)`
    ctx.lineWidth = 1

    spaceTimeMetric.forEach((metric) => {
      const curvatureEffect = metric.curvature * 15 * (Math.log(phase.complexity) / Math.log(1e100))
      
      ctx.beginPath()
      ctx.moveTo(metric.x - curvatureEffect, metric.y)
      ctx.lineTo(metric.x + curvatureEffect, metric.y)
      ctx.moveTo(metric.x, metric.y - curvatureEffect)
      ctx.lineTo(metric.x, metric.y + curvatureEffect)
      ctx.stroke()
    })

    ctx.globalAlpha = 1.0
  }

  const drawCosmologicalEvolution = (ctx: CanvasRenderingContext2D, width: number, height: number) => {
    // ハッブルパラメータの進化
    ctx.strokeStyle = 'rgba(155, 89, 182, 0.8)'
    ctx.lineWidth = 2
    ctx.beginPath()

    for (let x = 0; x < width; x += 5) {
      const t = (x / width) * 13.8
      const H = 70 * Math.sqrt(phase.complexity / 1e25)
      const y = height - (H / 100) * height * 0.3

      if (x === 0) {
        ctx.moveTo(x, y)
      } else {
        ctx.lineTo(x, y)
      }
    }
    ctx.stroke()

    // σ₈進化
    ctx.strokeStyle = 'rgba(241, 196, 15, 0.8)'
    ctx.beginPath()

    for (let x = 0; x < width; x += 5) {
      const sigma8 = 0.834 * (phase.infoDensity / 1e120)
      const y = height - sigma8 * height * 0.4

      if (x === 0) {
        ctx.moveTo(x, y)
      } else {
        ctx.lineTo(x, y)
      }
    }
    ctx.stroke()
  }

  const drawConsciousnessEmergence = (ctx: CanvasRenderingContext2D, width: number, height: number) => {
    ctx.globalAlpha = phase.consciousness * 0.6

    const centerX = width / 2
    const centerY = height / 2
    const radius = phase.consciousness * 120

    const gradient = ctx.createRadialGradient(centerX, centerY, 0, centerX, centerY, radius)
    gradient.addColorStop(0, 'rgba(255, 255, 255, 0.8)')
    gradient.addColorStop(0.5, 'rgba(147, 51, 234, 0.4)')
    gradient.addColorStop(1, 'rgba(255, 255, 255, 0)')

    ctx.fillStyle = gradient
    ctx.beginPath()
    ctx.arc(centerX, centerY, radius, 0, Math.PI * 2)
    ctx.fill()

          // Consciousness network
    if (phase.consciousness > 0.5) {
      for (let i = 0; i < 10; i++) {
        const angle = (i / 10) * Math.PI * 2 + time * 0.1
        const x = centerX + Math.cos(angle) * radius * 0.7
        const y = centerY + Math.sin(angle) * radius * 0.7

        ctx.fillStyle = 'rgba(255, 255, 255, 0.8)'
        ctx.beginPath()
        ctx.arc(x, y, 3, 0, Math.PI * 2)
        ctx.fill()

        // 接続線
        ctx.strokeStyle = 'rgba(255, 255, 255, 0.3)'
        ctx.lineWidth = 1
        ctx.beginPath()
        ctx.moveTo(centerX, centerY)
        ctx.lineTo(x, y)
        ctx.stroke()
      }
    }

    ctx.globalAlpha = 1.0
  }

  const updatePhysicsStates = () => {
          // Information field update
    setInformationField(prev => prev.map(field => ({
      ...field,
      phase: field.phase + field.frequency * 0.1,
      x: field.x + field.velocity.x * 0.5,
      y: field.y + field.velocity.y * 0.5,
      velocity: {
        x: field.x < 0 || field.x > 800 ? -field.velocity.x : field.velocity.x,
        y: field.y < 0 || field.y > 600 ? -field.velocity.y : field.velocity.y
      }
    })))

    // 量子状態の更新
    setQuantumStates(prev => prev.map(state => ({
      ...state,
      phase: state.phase + state.frequency,
      x: (state.x + Math.cos(state.phase) * 0.5 + 800) % 800,
      y: (state.y + Math.sin(state.phase) * 0.5 + 600) % 600
    })))

    // 時空メトリックの更新
    setSpaceTimeMetric(prev => prev.map(metric => {
              // Calculate curvature based on nearby information field density
      let totalDensity = 0
      let count = 0

      informationField.forEach(field => {
        const distance = Math.sqrt((metric.x - field.x) ** 2 + (metric.y - field.y) ** 2)
        if (distance < 50) {
          totalDensity += field.density / (distance + 1)
          count++
        }
      })

      return {
        ...metric,
        curvature: count > 0 ? totalDensity / count : 0
      }
    }))
  }

  return (
    <div className="relative w-full h-[600px] rounded-xl overflow-hidden bg-black/50">
      <canvas
        ref={canvasRef}
        width={800}
        height={600}
        className="w-full h-full object-contain"
      />
      
              {/* Phase information overlay */}
      <div className="absolute bottom-4 left-4 bg-black/60 backdrop-blur-sm rounded-lg p-3 text-white text-sm">
        <div className="flex items-center gap-2 mb-1">
          <div 
            className="w-3 h-3 rounded-full"
            style={{ backgroundColor: phase.color }}
          />
          <span className="font-semibold">{phase.name}</span>
        </div>
        <div className="text-xs text-gray-300">
          {quantumMode ? '量子モード有効' : '古典モード'}
        </div>
      </div>
    </div>
  )
} 