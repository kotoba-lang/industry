"use client"

import React, { useRef, useMemo } from 'react'
import { Canvas, useFrame } from '@react-three/fiber'
import { OrbitControls, Environment, Text } from '@react-three/drei'
import * as THREE from 'three'

function Terrain() {
  const terrainRef = useRef<THREE.Mesh>(null!)

  const geometry = useMemo(() => {
    const geo = new THREE.PlaneGeometry(100, 100, 50, 50)
    const pos = geo.attributes.position
    for (let i = 0; i < pos.count; i++) {
      const x = pos.getX(i)
      const y = pos.getY(i)
      const z = Math.sin(x / 10) * Math.sin(y / 10) * 2
      pos.setZ(i, z)
    }
    geo.computeVertexNormals()
    return geo
  }, [])

  return (
    <mesh ref={terrainRef} rotation={[-Math.PI / 2, 0, 0]} position={[0, -2, 0]}>
      <primitive object={geometry} />
      <meshStandardMaterial color="#90EE90" wireframe />
    </mesh>
  )
}

function FloatingSymbols() {
  const symbolsRef = useRef<THREE.Group>(null!)

  const symbols = useMemo(() => {
    return ['自', '他', '時', '間', '無']
  }, [])

  useFrame((state) => {
    const t = state.clock.getElapsedTime()
    symbolsRef.current.children.forEach((child, i) => {
      child.position.y = Math.sin(t + i * Math.PI / 5) + 5
      child.rotation.y = t * 0.2
    })
  })

  return (
    <group ref={symbolsRef}>
      {symbols.map((symbol, i) => (
        <Text
          key={i}
          color="white"
          fontSize={2}
          maxWidth={200}
          lineHeight={1}
          letterSpacing={0.02}
          textAlign="center"
          font="/fonts/Geist-Regular.ttf"
          anchorX="center"
          anchorY="middle"
          position={[Math.cos(i * Math.PI * 0.4) * 10, 5, Math.sin(i * Math.PI * 0.4) * 10]}
        >
          {symbol}
        </Text>
      ))}
    </group>
  )
}

function ConnectingLines() {
  const linesRef = useRef<THREE.Group>(null!)

  useFrame((state) => {
    const t = state.clock.getElapsedTime()
    linesRef.current.rotation.y = t * 0.1
  })

  return (
    <group ref={linesRef}>
      {[...Array(20)].map((_, i) => (
        <mesh key={i} position={[0, 5, 0]}>
          <boxGeometry args={[0.1, 0.1, 20]} />
          <meshStandardMaterial color="#FFA500" transparent opacity={0.5} />
        </mesh>
      ))}
    </group>
  )
}

function TimelessSphere() {
  const sphereRef = useRef<THREE.Mesh>(null!)

  useFrame((state) => {
    const t = state.clock.getElapsedTime()
    sphereRef.current.rotation.y = t * 0.1
    sphereRef.current.rotation.z = t * 0.1
  })

  return (
    <mesh ref={sphereRef} position={[0, 10, 0]}>
      <sphereGeometry args={[5, 32, 32]} />
      <meshStandardMaterial color="#4B0082" wireframe />
    </mesh>
  )
}

export default function JapaneseSerenity() {
  return (
    <div className="w-full h-screen">
      <Canvas camera={{ position: [0, 20, 40], fov: 75 }}>
        <ambientLight intensity={0.5} />
        <directionalLight position={[10, 10, 5]} intensity={1} />
        <Terrain />
        <FloatingSymbols />
        <ConnectingLines />
        <TimelessSphere />
        <OrbitControls enablePan={false} maxPolarAngle={Math.PI / 2 - 0.1} />
        <Environment preset="sunset" />
      </Canvas>
    </div>
  )
}

