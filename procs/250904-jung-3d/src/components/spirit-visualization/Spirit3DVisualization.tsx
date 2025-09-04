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

  // Validate props
  if (!Array.isArray(position) || position.length !== 3) {
    console.warn('SpiritPoint: Invalid position array', position);
    return null;
  }

  const [x, y, z] = position;
  if (typeof x !== 'number' || typeof y !== 'number' || typeof z !== 'number' ||
      isNaN(x) || isNaN(y) || isNaN(z)) {
    console.warn('SpiritPoint: Invalid position values', position);
    return null;
  }

  if (typeof energy !== 'number' || isNaN(energy)) {
    energy = 0.5;
  }

  // Ensure energy is within reasonable bounds
  energy = Math.max(0.1, Math.min(1.0, energy));

  // Validate label
  const safeLabel = typeof label === 'string' ? label : 'Unknown';

  useFrame((state) => {
    if (meshRef.current) {
      try {
        // Subtle pulsing animation based on energy
        const scale = 0.1 + Math.sin(state.clock.elapsedTime * 2) * 0.02 * energy;
        meshRef.current.scale.setScalar(scale);
      } catch (error) {
        console.warn('Error animating spirit point:', error);
      }
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
          {safeLabel}
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

  // Ensure vectors is always an array
  const inputVectors = Array.isArray(vectors) ? vectors : [];

  // Filter and validate vectors more strictly
  const safeVectors = inputVectors.filter((v): v is SpiritVector => {
    if (!v || typeof v !== 'object') return false;
    if (!v.vector || !Array.isArray(v.vector) || v.vector.length !== 3) return false;
    if (typeof v.energy !== 'number' || isNaN(v.energy)) return false;
    if (!v.stimulus || typeof v.stimulus !== 'string') return false;
    if (!v.response || typeof v.response !== 'string') return false;

    // Validate vector coordinates
    const [x, y, z] = v.vector;
    return typeof x === 'number' && typeof y === 'number' && typeof z === 'number' &&
           !isNaN(x) && !isNaN(y) && !isNaN(z);
  });

  console.log('SpiritScene received:', {
    inputVectorsLength: inputVectors.length,
    safeVectorsLength: safeVectors.length,
    firstVector: safeVectors[0],
  });

  // Early return if no valid vectors
  if (safeVectors.length === 0) {
    console.log('SpiritScene: No valid vectors, returning empty group');
    return <group ref={groupRef} />;
  }

  useEffect(() => {
    if (safeVectors.length > 0 && camera) {
      try {
        const positions = safeVectors.map(v => {
          if (!v || !v.vector || !Array.isArray(v.vector) || v.vector.length !== 3) {
            return new THREE.Vector3(0, 0, 0);
          }
          const [x, y, z] = v.vector;
          if (typeof x !== 'number' || typeof y !== 'number' || typeof z !== 'number' ||
              isNaN(x) || isNaN(y) || isNaN(z)) {
            return new THREE.Vector3(0, 0, 0);
          }
          return new THREE.Vector3(x, y, z);
        });

        if (positions.length > 0) {
          const box = new THREE.Box3().setFromPoints(positions);
          const center = box.getCenter(new THREE.Vector3());
          const size = box.getSize(new THREE.Vector3());

          const maxDim = Math.max(size.x || 1, size.y || 1, size.z || 1);
          const fov = camera.fov * (Math.PI / 180);
          let cameraZ = Math.abs(maxDim / 2 / Math.tan(fov / 2));

          cameraZ *= 2; // Zoom out to see all points

          camera.position.set(center.x, center.y, center.z + cameraZ);
          camera.updateProjectionMatrix();
          camera.lookAt(center);
        }
      } catch (error) {
        console.warn('Error setting up camera:', error);
      }
    }
  }, [safeVectors, camera]);

  useFrame((state) => {
    if (animate && groupRef.current) {
      groupRef.current.rotation.y += 0.002;
    }
  });

  // Render spirit points
  const renderSpiritPoints = () => {
    if (!safeVectors || safeVectors.length === 0) return null;

    return safeVectors.map((spiritVector, index) => {
      try {
        return (
          <SpiritPoint
            key={`${spiritVector.stimulus}-${spiritVector.response}-${index}`}
            position={spiritVector.vector as [number, number, number]}
            label={`${spiritVector.stimulus} → ${spiritVector.response}`}
            energy={spiritVector.energy}
            showLabel={showLabels}
          />
        );
      } catch (error) {
        console.warn('Error rendering spirit point at index', index, error);
        return null;
      }
    });
  };

  // Render connections between related points
  const renderConnections = () => {
    if (!safeVectors || safeVectors.length < 2) return null;

    try {
      return safeVectors.flatMap((v1, i) => {
        return safeVectors.slice(i + 1).map((v2, j) => {
          try {
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
          } catch (error) {
            console.warn('Error creating connection line:', error);
            return null;
          }
        }).filter(Boolean); // Remove null values
      });
    } catch (error) {
      console.warn('Error rendering connections:', error);
      return null;
    }
  };

  return (
    <group ref={groupRef}>
      {renderSpiritPoints()}
      {renderConnections()}
    </group>
  );
}

export default function Spirit3DVisualization({
  vectors = [],
  showLabels = true,
  animate = true
}: Spirit3DVisualizationProps) {
  // Ensure vectors is always an array and validate each vector
  const safeVectors = Array.isArray(vectors)
    ? vectors.filter(v => v && typeof v === 'object' && v.vector && Array.isArray(v.vector) && v.vector.length === 3)
    : [];

  console.log('Spirit3DVisualization received:', {
    vectorsType: typeof vectors,
    vectorsLength: vectors?.length,
    safeVectorsLength: safeVectors.length,
    firstVector: safeVectors[0],
  });

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

  // Safe camera configuration
  const cameraConfig = {
    position: [0, 0, 5] as [number, number, number],
    fov: 75
  };

  return (
    <div className="w-full h-[600px] bg-black rounded-lg overflow-hidden">
      <Canvas camera={cameraConfig}>
        <ambientLight intensity={0.4} />
        <pointLight position={[10, 10, 10]} intensity={0.8} />
        <pointLight position={[-10, -10, -10]} intensity={0.4} />
        <SpiritScene vectors={safeVectors} showLabels={showLabels} animate={animate} />
        <OrbitControls enablePan={true} enableZoom={true} enableRotate={true} />
        <Environment preset="night" />
      </Canvas>

      <div className="absolute bottom-4 left-4 text-white text-sm bg-black bg-opacity-50 p-2 rounded">
        <div>Points: {safeVectors.length}</div>
        <div>Average Energy: {safeVectors.length > 0 ? (safeVectors.reduce((sum, v) => sum + (v.energy || 0), 0) / safeVectors.length).toFixed(3) : '0.000'}</div>
      </div>
    </div>
  );
}
