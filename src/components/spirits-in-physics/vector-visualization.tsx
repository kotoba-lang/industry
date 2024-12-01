import { useRef, useEffect } from 'react';
import { Canvas, useFrame, useThree } from '@react-three/fiber';
import { OrbitControls, Text, Environment } from '@react-three/drei';
import * as THREE from 'three';
import { Vector } from '../types/correlation';

type VectorVisualizationProps = {
  vectors: Vector[];
};

const elements = ['りんご', 'みかん', 'バナナ', '河崎純真'];

function Element({ position, label }: { position: [number, number, number]; label: string }) {
  return (
    <group position={position}>
      <mesh>
        <sphereGeometry args={[0.1, 32, 32]} />
        <meshStandardMaterial color="blue" />
      </mesh>
      <Text
        position={[0, 0.2, 0]}
        fontSize={0.2}
        color="white"
        anchorX="center"
        anchorY="middle"
        font="/fonts/Geist-Regular.ttf"
      >
        {label}
      </Text>
    </group>
  );
}

function Scene({ vectors }: VectorVisualizationProps) {
  const { camera } = useThree();
  const groupRef = useRef<THREE.Group>(null);

  useEffect(() => {
    if (groupRef.current) {
      const box = new THREE.Box3().setFromObject(groupRef.current);
      const center = box.getCenter(new THREE.Vector3());
      const size = box.getSize(new THREE.Vector3());

      const maxDim = Math.max(size.x, size.y, size.z);
      const fov = camera.fov * (Math.PI / 180);
      let cameraZ = Math.abs(maxDim / 2 / Math.tan(fov / 2));

      cameraZ *= 1.5; // Zoom out a little more so all lines are visible

      camera.position.set(center.x, center.y, center.z + cameraZ);
      camera.updateProjectionMatrix();

      // Look at center of all elements
      camera.lookAt(center);
    }
  }, [vectors, camera]);

  useFrame(() => {
    if (groupRef.current) {
      groupRef.current.rotation.y += 0.005;
    }
  });

  return (
    <group ref={groupRef}>
      {vectors.map((vector, index) => (
        <Element
          key={index}
          position={vector as [number, number, number]}
          label={elements[index]}
        />
      ))}
      {/* Add lines between elements */}
      {vectors.flatMap((v1, i) =>
        vectors.slice(i + 1).map((v2, j) => (
          <line key={`${i}-${j}`}>
            <bufferGeometry attach="geometry">
              <bufferAttribute
                attachObject={['attributes', 'position']}
                count={2}
                array={new Float32Array([...v1, ...v2])}
                itemSize={3}
              />
            </bufferGeometry>
            <lineBasicMaterial attach="material" color="white" opacity={0.5} transparent />
          </line>
        ))
      )}
    </group>
  );
}

export default function VectorVisualization({ vectors }: VectorVisualizationProps) {
  return (
    <div style={{ width: '100%', height: '400px' }}>
      <Canvas>
        <ambientLight intensity={0.5} />
        <pointLight position={[10, 10, 10]} />
        <Scene vectors={vectors} />
        <OrbitControls />
        <Environment preset="studio" />
      </Canvas>
    </div>
  );
}

