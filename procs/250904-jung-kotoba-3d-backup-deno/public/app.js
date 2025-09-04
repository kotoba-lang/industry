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
  const pointsRef = useRef(null);
  const linesRef = useRef([]);

  const [data, setData] = useState(null);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState(null);
  const [parameters, setParameters] = useState({
    alpha: 1.0,
    gamma: 1.0,
    eta: 1.0,
    lambda: 1.0
  });

  // Load data from API
  useEffect(() => {
    console.log('Starting data load...');
    setIsLoading(true);
    setError(null);

    fetch('/api/spirit-data')
      .then(res => {
        console.log('API response received:', res.status, res.statusText);
        if (!res.ok) {
          throw new Error(`HTTP error! status: ${res.status} ${res.statusText}`);
        }
        return res.json();
      })
      .then(data => {
        console.log('Data loaded successfully:', data);
        console.log('Vectors count:', data.vectors?.length || 0);
        console.log('Associations count:', data.associations?.length || 0);
        setData(data);
        setIsLoading(false);
      })
      .catch(error => {
        console.error('Failed to load data:', error);
        setError(error.message);

        // フォールバックデータを使用
        const fallbackData = {
          vectors: [
            {
              word: "テスト",
              vector: [1, 0, 0],
              reactionTime: 1000,
              associationCount: 1
            },
            {
              word: "サンプル",
              vector: [0, 1, 0],
              reactionTime: 1200,
              associationCount: 2
            },
            {
              word: "データ",
              vector: [0, 0, 1],
              reactionTime: 800,
              associationCount: 1
            }
          ],
          associations: [
            ["テスト", "サンプル"],
            ["サンプル", "データ"]
          ]
        };
        console.log('Using fallback data:', fallbackData);
        setData(fallbackData);
        setIsLoading(false);
      });
  }, []);

  // Initialize Three.js scene
  useEffect(() => {
    if (!mountRef.current) {
      console.log('Mount ref not available, skipping Three.js initialization');
      return;
    }

    console.log('Initializing Three.js scene...');
    console.log('Mount element:', mountRef.current);

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

    console.log('Three.js scene initialized successfully');

    return () => {
      console.log('Cleaning up Three.js scene...');
      if (mountRef.current && renderer.domElement) {
        mountRef.current.removeChild(renderer.domElement);
      }
      renderer.dispose();
    };
  }, []);

  // Update visualization when data or parameters change
  useEffect(() => {
    console.log('Updating visualization with data:', data);
    console.log('Scene ref:', sceneRef.current);

    if (!sceneRef.current || !data) {
      console.log('Scene or data not available, skipping visualization update');
      return;
    }

    if (!data.vectors || data.vectors.length === 0) {
      console.log('No vectors in data, skipping visualization update');
      return;
    }

    console.log('Clearing existing visualization...');
    // Clear existing points and lines
    sceneRef.current.children = sceneRef.current.children.filter(child => {
      const shouldKeep = !(child instanceof THREE.Points || child instanceof THREE.Line);
      if (!shouldKeep) {
        console.log('Removing object:', child.type);
      }
      return shouldKeep;
    });

    // Add grid and axes back
    console.log('Adding grid and axes...');
    const gridHelper = new THREE.GridHelper(20, 20, 0x444444, 0x222222);
    sceneRef.current.add(gridHelper);
    const axesHelper = new THREE.AxesHelper(5);
    sceneRef.current.add(axesHelper);

    // Create geometry for points
    console.log('Creating geometry for points...');
    const positions = [];
    const colors = [];
    const sizes = [];

    console.log('Processing vectors:', data.vectors.length);
    data.vectors.forEach((vector, index) => {
      console.log(`Vector ${index}:`, vector.word, vector.vector);

      if (!vector.vector || vector.vector.length !== 3) {
        console.warn(`Invalid vector for ${vector.word}:`, vector.vector);
        return;
      }

      positions.push(...vector.vector);

      // Color based on energy (y component)
      const energy = vector.vector[1];
      const normalizedEnergy = Math.max(0, Math.min(1, (energy + 5) / 10));
      colors.push(normalizedEnergy, 0.5, 1 - normalizedEnergy);

      // Size based on association count
      const size = Math.max(5, (vector.associationCount || 1) * 2);
      sizes.push(size);
    });

    console.log('Positions length:', positions.length);
    console.log('Colors length:', colors.length);
    console.log('Sizes length:', sizes.length);

    // Only create points if we have valid data
    if (positions.length === 0) {
      console.log('No valid positions, skipping point creation');
      return;
    }

    console.log('Creating Three.js geometry and material...');

    // Create points geometry
    const geometry = new THREE.BufferGeometry();
    geometry.setAttribute('position', new THREE.Float32BufferAttribute(positions, 3));
    geometry.setAttribute('color', new THREE.Float32BufferAttribute(colors, 3));
    geometry.setAttribute('size', new THREE.Float32BufferAttribute(sizes, 1));

    console.log('Geometry created with', positions.length / 3, 'points');

    // Create material with simpler shader for better compatibility
    const material = new THREE.ShaderMaterial({
      uniforms: {
        size: { value: 15 },
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

    console.log('Material created');

    const points = new THREE.Points(geometry, material);
    pointsRef.current = points;
    sceneRef.current.add(points);

    console.log('Points added to scene:', points);

    // Create association lines
    console.log('Creating association lines...');
    const lineMaterial = new THREE.LineBasicMaterial({
      color: 0x888888,
      transparent: true,
      opacity: 0.3
    });

    let linesCreated = 0;
    if (data.associations && Array.isArray(data.associations)) {
      data.associations.forEach((association, index) => {
        console.log(`Processing association ${index}:`, association);

        let word1, word2;
        if (Array.isArray(association)) {
          [word1, word2] = association;
        } else if (typeof association === 'string') {
          [word1, word2] = association.split('->');
        } else {
          console.warn('Invalid association format:', association);
          return;
        }

        const vector1 = data.vectors.find(v => v.word === word1);
        const vector2 = data.vectors.find(v => v.word === word2);

        if (vector1 && vector2 && vector1.vector && vector2.vector) {
          try {
            const lineGeometry = new THREE.BufferGeometry().setFromPoints([
              new THREE.Vector3(...vector1.vector),
              new THREE.Vector3(...vector2.vector)
            ]);

            const line = new THREE.Line(lineGeometry, lineMaterial);
            linesRef.current.push(line);
            sceneRef.current.add(line);
            linesCreated++;
          } catch (error) {
            console.error('Error creating line:', error);
          }
        } else {
          console.warn('Missing vectors for association:', word1, word2);
        }
      });
    }

    console.log(`Created ${linesCreated} association lines`);
    console.log('Visualization update completed successfully');

    // Force render
    if (rendererRef.current) {
      rendererRef.current.render(sceneRef.current, cameraRef.current);
    }

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

  return React.createElement('div', { ref: mountRef, id: 'canvas-container' });
};

// Initialize React app
console.log('Initializing React app...');

const container = document.getElementById('canvas-container');
if (!container) {
  console.error('Canvas container not found!');
} else {
  console.log('Canvas container found, creating React root...');
  try {
    const root = createRoot(container);
    root.render(React.createElement(SpiritVisualizer));
    console.log('React app initialized successfully');
  } catch (error) {
    console.error('Failed to initialize React app:', error);
  }
}
