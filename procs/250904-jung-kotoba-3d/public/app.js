import React, { useEffect, useRef, useState } from 'react';
import { createRoot } from 'react-dom/client';
import * as THREE from 'three';
import { OrbitControls } from 'three/addons/controls/OrbitControls.js';

const SpiritVisualizer = () => {
  const mountRef = useRef(null);
  const sceneRef = useRef(null);
  const rendererRef = useRef(null);
  const cameraRef = useRef(null);
  const controlsRef = useRef(null);

  const [data, setData] = useState(null);
  const [parameters, setParameters] = useState({
    alpha: 1.0,
    gamma: 1.0,
    eta: 1.0,
    lambda: 1.0
  });

  // Load data from API
  useEffect(() => {
    fetch('/api/spirit-data')
      .then(res => res.json())
      .then(setData)
      .catch(console.error);
  }, []);

  // Initialize Three.js scene
  useEffect(() => {
    if (!mountRef.current) return;

    const scene = new THREE.Scene();
    scene.background = new THREE.Color(0x0a0a0a);

    const camera = new THREE.PerspectiveCamera(
      75,
      window.innerWidth / window.innerHeight,
      0.1,
      1000
    );
    camera.position.set(5, 5, 5);

    const renderer = new THREE.WebGLRenderer({ antialias: true });
    renderer.setSize(window.innerWidth, window.innerHeight);
    mountRef.current.appendChild(renderer.domElement);

    const controls = new OrbitControls(camera, renderer.domElement);
    controls.enableDamping = true;
    controls.dampingFactor = 0.05;

    // Lighting
    const ambientLight = new THREE.AmbientLight(0x404040, 0.6);
    scene.add(ambientLight);

    const directionalLight = new THREE.DirectionalLight(0xffffff, 0.8);
    directionalLight.position.set(1, 1, 1);
    scene.add(directionalLight);

    // Grid helper
    const gridHelper = new THREE.GridHelper(20, 20, 0x444444, 0x222222);
    scene.add(gridHelper);

    // Axes helper
    const axesHelper = new THREE.AxesHelper(5);
    scene.add(axesHelper);

    sceneRef.current = scene;
    rendererRef.current = renderer;
    cameraRef.current = camera;
    controlsRef.current = controls;

    const animate = () => {
      requestAnimationFrame(animate);
      controls.update();
      renderer.render(scene, camera);
    };
    animate();

    return () => {
      if (mountRef.current && renderer.domElement) {
        mountRef.current.removeChild(renderer.domElement);
      }
      renderer.dispose();
    };
  }, []);

  // Update visualization when data or parameters change
  useEffect(() => {
    if (!sceneRef.current || !data) return;

    // Clear existing points
    sceneRef.current.children = sceneRef.current.children.filter(child =>
      !(child instanceof THREE.Points || child instanceof THREE.Line)
    );

    // Add grid and axes back
    const gridHelper = new THREE.GridHelper(20, 20, 0x444444, 0x222222);
    sceneRef.current.add(gridHelper);
    const axesHelper = new THREE.AxesHelper(5);
    sceneRef.current.add(axesHelper);

    // Create geometry for points
    const positions = [];
    const colors = [];
    const sizes = [];

    data.vectors.forEach(vector => {
      positions.push(...vector.vector);

      // Color based on energy (y component)
      const energy = vector.vector[1];
      const normalizedEnergy = Math.max(0, Math.min(1, (energy + 5) / 10));
      colors.push(normalizedEnergy, 0.5, 1 - normalizedEnergy);

      // Size based on association count
      sizes.push(Math.max(5, vector.associationCount * 2));
    });

    // Create points geometry
    const geometry = new THREE.BufferGeometry();
    geometry.setAttribute('position', new THREE.Float32BufferAttribute(positions, 3));
    geometry.setAttribute('color', new THREE.Float32BufferAttribute(colors, 3));
    geometry.setAttribute('size', new THREE.Float32BufferAttribute(sizes, 1));

    // Create material
    const material = new THREE.ShaderMaterial({
      uniforms: {
        size: { value: 10 },
      },
      vertexShader: `
        attribute float size;
        attribute vec3 color;
        varying vec3 vColor;

        void main() {
          vColor = color;
          vec4 mvPosition = modelViewMatrix * vec4(position, 1.0);
          gl_PointSize = size * (300.0 / -mvPosition.z);
          gl_Position = projectionMatrix * mvPosition;
        }
      `,
      fragmentShader: `
        varying vec3 vColor;

        void main() {
          float r = distance(gl_PointCoord, vec2(0.5, 0.5));
          if (r > 0.5) discard;

          gl_FragColor = vec4(vColor, 0.8);
        }
      `,
      transparent: true,
      vertexColors: true,
    });

    const points = new THREE.Points(geometry, material);
    sceneRef.current.add(points);

    // Create association lines
    const lineMaterial = new THREE.LineBasicMaterial({
      color: 0x888888,
      transparent: true,
      opacity: 0.3
    });

    data.associations.forEach(([key, count]) => {
      const [word1, word2] = key.split('->');
      const vector1 = data.vectors.find(v => v.word === word1);
      const vector2 = data.vectors.find(v => v.word === word2);

      if (vector1 && vector2) {
        const lineGeometry = new THREE.BufferGeometry().setFromPoints([
          new THREE.Vector3(...vector1.vector),
          new THREE.Vector3(...vector2.vector)
        ]);

        const line = new THREE.Line(lineGeometry, lineMaterial);
        sceneRef.current.add(line);
      }
    });

  }, [data, parameters]);

  // Handle parameter changes
  const handleParameterChange = (param, value) => {
    setParameters(prev => ({ ...prev, [param]: parseFloat(value) }));

    // Update display value
    const element = document.getElementById(`${param}-value`);
    if (element) element.textContent = value;
  };

  // Setup parameter listeners
  useEffect(() => {
    ['alpha', 'gamma', 'eta', 'lambda'].forEach(param => {
      const element = document.getElementById(param);
      if (element) {
        element.addEventListener('input', (e) => {
          handleParameterChange(param, e.target.value);
        });
      }
    });
  }, []);

  // Update stats display
  useEffect(() => {
    if (!data) return;

    const statsContent = document.getElementById('stats-content');
    if (statsContent) {
      statsContent.innerHTML = `
        <div>総単語数: ${data.vectors.length}</div>
        <div>関連ペア数: ${data.associations.length}</div>
        <div>平均反応時間: ${Math.round(data.vectors.reduce((sum, v) => sum + v.reactionTime, 0) / data.vectors.length)}ms</div>
      `;
    }

    // Update word list
    const wordListContent = document.getElementById('word-list-content');
    if (wordListContent) {
      wordListContent.innerHTML = data.vectors
        .sort((a, b) => b.associationCount - a.associationCount)
        .slice(0, 20)
        .map(v => `<div class="word-item">${v.word} (${v.associationCount})</div>`)
        .join('');
    }
  }, [data]);

  return <div ref={mountRef} id="canvas-container" />;
};

// Initialize React app
const container = document.createElement('div');
document.body.appendChild(container);
const root = createRoot(container);
root.render(<SpiritVisualizer />);
