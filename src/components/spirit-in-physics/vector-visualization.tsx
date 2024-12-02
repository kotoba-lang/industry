"use client";

import { useRef, useEffect } from "react";
import { Canvas, useFrame, useThree } from "@react-three/fiber";

import { OrbitControls, Text, Environment, Line } from "@react-three/drei";

import { Vector } from "@/types/correlation";

import * as THREE from "three";

import {
  Group,
  Mesh,
  SphereGeometry,
  MeshStandardMaterial,
  BufferGeometry,
  BufferAttribute,
  LineBasicMaterial,
  AmbientLight,
  PointLight,
} from "three";

type VectorVisualizationProps = {
  vectors: Vector[];
};

const elements = ["りんご", "みかん", "バナナ", "河崎純真"];

function Element({
  position,
  label,
}: {
  position: [number, number, number];
  label: string;
}) {
  return (
    <Group position={position}>
      <Mesh>
        <SphereGeometry args={[0.1, 32, 32]} />
        <MeshStandardMaterial color="blue" />
      </Mesh>
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
    </Group>
  );
}

function Scene({ vectors }: VectorVisualizationProps) {
  const { camera } = useThree();
  const groupRef = useRef<any>(null);

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
    <Group ref={groupRef}>
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
          <Line key={`${i}-${j}`}>
            <BufferGeometry attach="geometry">
              <BufferAttribute
                attachObject={["attributes", "position"]}
                count={2}
                array={new Float32Array([...v1, ...v2])}
                itemSize={3}
              />
            </BufferGeometry>
            <LineBasicMaterial
              attach="material"
              color="white"
              opacity={0.5}
              transparent
            />
          </Line>
        ))
      )}
    </Group>
  );
}

export default function VectorVisualization({
  vectors,
}: VectorVisualizationProps) {
  return (
    <Canvas>
      <AmbientLight intensity={0.5} />
      <PointLight position={[10, 10, 10]} />
      <Scene vectors={vectors} />
      <OrbitControls />
      <Environment preset="studio" />
    </Canvas>
  );
}
