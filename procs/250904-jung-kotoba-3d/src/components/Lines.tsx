import React from 'react';
import { Vector, Association } from './types';
import * as THREE from 'three';

interface LinesProps {
  associations: Association[];
  vectors: Vector[];
}

const Lines: React.FC<LinesProps> = ({ associations, vectors }) => {
  const vectorMap = new Map(vectors.map((v) => [v.word, v.vector]));

  return (
    <group>
      {associations.map((assoc, i) => {
        const start = vectorMap.get(assoc.source);
        const end = vectorMap.get(assoc.target);

        if (!start || !end) {
          return null;
        }

        const points = [new THREE.Vector3(...start), new THREE.Vector3(...end)];
        const geometry = new THREE.BufferGeometry().setFromPoints(points);

        return (
          <line key={i} geometry={geometry}>
            <lineBasicMaterial
              color="gray"
              transparent
              opacity={0.3}
              linewidth={assoc.strength}
            />
          </line>
        );
      })}
    </group>
  );
};

export default Lines;
