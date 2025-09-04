"use client";

import React, { useRef, useEffect } from "react";
import { Canvas, useFrame, useThree } from "@react-three/fiber";
import { OrbitControls, Text, Environment, Line } from "@react-three/drei";
import { SpiritVector } from "@/types/vector";
import * as THREE from "three";

interface Spirit3DVisualizationProps {
  vectors?: SpiritVector[];
  showLabels?: boolean;
  animate?: boolean;
}

function SpiritPoint({
  position,
  label,
  energy,
  showLabel
}: {
  position: [number, number, number];
  label: string;
  energy: number;
  showLabel: boolean;
}) {
  const meshRef = useRef<THREE.Mesh>(null);

  useFrame((state) => {
    if (meshRef.current) {
      // Subtle pulsing animation based on energy
      const scale = 0.1 + Math.sin(state.clock.elapsedTime * 2) * 0.02 * energy;
      meshRef.current.scale.setScalar(scale);
    }
  });

  return (
    <>
      <mesh ref={meshRef} position={position}>
        <sphereGeometry args={[0.1, 32, 32]} />
        <meshStandardMaterial
          color={`hsl(${energy * 360}, 70%, 50%)`}
          emissive={`hsl(${energy * 360}, 70%, 20%)`}
          emissiveIntensity={0.2}
        />
      </mesh>
      {showLabel && (
        <Text
          position={[position[0], position[1] + 0.2, position[2]]}
          fontSize={0.15}
          color="white"
          anchorX="center"
          anchorY="middle"
        >
          {label}
        </Text>
      )}
    </>
  );
}

function SpiritScene({
  vectors = [],
  showLabels,
  animate
}: Spirit3DVisualizationProps) {
  const { camera } = useThree();
  const groupRef = useRef<THREE.Group>(null);
  const safeVectors = vectors || [];

  useEffect(() => {
    if (safeVectors.length > 0) {
      const positions = safeVectors.map(v => new THREE.Vector3(...v.vector));
      const box = new THREE.Box3().setFromPoints(positions);
      const center = box.getCenter(new THREE.Vector3());
      const size = box.getSize(new THREE.Vector3());

      const maxDim = Math.max(size.x, size.y, size.z);
      const fov = camera.fov * (Math.PI / 180);
      let cameraZ = Math.abs(maxDim / 2 / Math.tan(fov / 2));

      cameraZ *= 2; // Zoom out to see all points

      camera.position.set(center.x, center.y, center.z + cameraZ);
      camera.updateProjectionMatrix();
      camera.lookAt(center);
    }
  }, [safeVectors, camera]);

  useFrame((state) => {
    if (animate && groupRef.current) {
      groupRef.current.rotation.y += 0.002;
    }
  });

  return (
    <group ref={groupRef}>
      {/* Render spirit points */}
      {safeVectors.map((spiritVector, index) => (
        <SpiritPoint
          key={`${spiritVector.stimulus}-${spiritVector.response}-${index}`}
          position={spiritVector.vector}
          label={`${spiritVector.stimulus} → ${spiritVector.response}`}
          energy={spiritVector.energy}
          showLabel={showLabels}
        />
      ))}

      {/* Render connections between related points */}
      {safeVectors.flatMap((v1, i) =>
        safeVectors.slice(i + 1).map((v2, j) => {
          // Only connect points with similar energy levels
          if (Math.abs(v1.energy - v2.energy) > 0.3) return null;

          return (
            <Line key={`connection-${i}-${j}`}>
              <bufferGeometry>
                <bufferAttribute
                  attach="attributes-position"
                  count={2}
                  array={new Float32Array([...v1.vector, ...v2.vector])}
                  itemSize={3}
                />
              </bufferGeometry>
              <lineBasicMaterial
                color="white"
                opacity={0.3}
                transparent
              />
            </Line>
          );
        })
      )}
    </group>
  );
}

export default function Spirit3DVisualization({
  vectors = [],
  showLabels = true,
  animate = true
}: Spirit3DVisualizationProps) {
  const safeVectors = vectors || [];

  if (safeVectors.length === 0) {
    return (
      <div className="w-full h-[600px] bg-black rounded-lg overflow-hidden flex items-center justify-center">
        <div className="text-white text-center">
          <div className="text-6xl mb-4">🌌</div>
          <h3 className="text-xl font-semibold mb-2">No Spirit Data Available</h3>
          <p className="text-gray-300">Waiting for word association data to visualize...</p>
        </div>
      </div>
    );
  }

  return (
    <div className="w-full h-[600px] bg-black rounded-lg overflow-hidden">
      <Canvas camera={{ position: [0, 0, 5], fov: 75 }}>
        <ambientLight intensity={0.4} />
        <pointLight position={[10, 10, 10]} intensity={0.8} />
        <pointLight position={[-10, -10, -10]} intensity={0.4} />
        <SpiritScene vectors={safeVectors} showLabels={showLabels} animate={animate} />
        <OrbitControls enablePan={true} enableZoom={true} enableRotate={true} />
        <Environment preset="night" />
      </Canvas>

      <div className="absolute bottom-4 left-4 text-white text-sm bg-black bg-opacity-50 p-2 rounded">
        <div>Points: {safeVectors.length}</div>
        <div>Average Energy: {(safeVectors.reduce((sum, v) => sum + v.energy, 0) / safeVectors.length).toFixed(3)}</div>
      </div>
    </div>
  );
}
