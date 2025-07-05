// Type definitions for Three.js JSX elements used in vector-visualization.tsx
import * as THREE from 'three';

declare global {
  namespace JSX {
    interface IntrinsicElements {
      mesh: any;
      sphereGeometry: any;
      meshStandardMaterial: any;
      bufferGeometry: any;
      bufferAttribute: any;
      lineBasicMaterial: any;
      line: any;
      group: any;
    }
  }
}

// Extend THREE.Object3D to include proper rotation types
declare module 'three' {
  interface Object3D {
    rotation: THREE.Euler;
  }
} 