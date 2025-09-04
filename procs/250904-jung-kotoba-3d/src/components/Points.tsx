import React, { useMemo, useRef } from 'react';
import { Vector } from './types';
import { Text } from '@react-three/drei';
import * as THREE from 'three';

interface PointsProps {
  vectors: Vector[];
}

const Points: React.FC<PointsProps> = ({ vectors }) => {
  const pointsRef = useRef<THREE.Points>(null);

  const [positions, colors] = useMemo(() => {
    const pos = new Float32Array(vectors.length * 3);
    const col = new Float32Array(vectors.length * 3);

    vectors.forEach((v, i) => {
      pos[i * 3] = v.vector[0];
      pos[i * 3 + 1] = v.vector[1];
      pos[i * 3 + 2] = v.vector[2];

      const energy = v.vector[1];
      const normalizedEnergy = Math.max(0, Math.min(1, (energy + 5) / 10));
      const color = new THREE.Color();
      color.setHSL(0.6 - normalizedEnergy * 0.4, 1.0, 0.5);
      col[i * 3] = color.r;
      col[i * 3 + 1] = color.g;
      col[i * 3 + 2] = color.b;
    });

    return [pos, col];
  }, [vectors]);

  return (
    <>
      <points ref={pointsRef}>
        <bufferGeometry>
          <bufferAttribute
            attach="attributes-position"
            count={positions.length / 3}
            array={positions}
            itemSize={3}
          />
          <bufferAttribute
            attach="attributes-color"
            count={colors.length / 3}
            array={colors}
            itemSize={3}
          />
        </bufferGeometry>
        <pointsMaterial
          size={0.2}
          vertexColors
          sizeAttenuation
          depthWrite={false}
        />
      </points>
      {vectors.map((v, i) => (
        <Text
          key={i}
          position={v.vector}
          fontSize={0.2}
          color="white"
          anchorX="center"
          anchorY="middle"
        >
          {v.word}
        </Text>
      ))}
    </>
  );
};

export default Points;
