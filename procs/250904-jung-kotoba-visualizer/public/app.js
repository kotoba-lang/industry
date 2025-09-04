import * as THREE from 'three';
import { OrbitControls } from 'three/addons/controls/OrbitControls.js';
import { CSS2DRenderer, CSS2DObject } from 'three/addons/renderers/CSS2DRenderer.js';

let scene, camera, renderer, labelRenderer;
let controls;
const nodes = [];
const edges = [];
const raycaster = new THREE.Raycaster();
const mouse = new THREE.Vector2();
let INTERSECTED;
let currentTreeData;
let wordMap = new Map();
let selectedNode = null;
let highlightedPath = [];

// Global functions for HTML buttons
window.generateNewTree = async function() {
  const promptInput = document.getElementById('prompt-input');
  const prompt = promptInput.value.trim();
  if (prompt) {
    await generateTreeWithPrompt(prompt);
    document.getElementById('highlight-controls').style.display = 'block';
  }
};

window.generateLanguageSpace = async function() {
  await generateLanguageSpaceMap();
  document.getElementById('highlight-controls').style.display = 'block';
};

window.highlightSubtree = async function() {
  const wordInput = document.getElementById('highlight-word-input');
  const word = wordInput.value.trim().toLowerCase();
  if (word) {
    highlightSubtreeFromWord(word);
  }
};

window.clearHighlights = function() {
  clearAllHighlights();
};

async function generateTreeWithPrompt(prompt) {
  try {
    // Clear existing scene
    clearScene();

    // Fetch new data
    const response = await fetch(`/api/completions?prompt=${encodeURIComponent(prompt)}`);
    const data = await response.json();

    currentTreeData = data.tree;
    visualizeTree(data.tree);
    buildWordMap(data.tree);
    updatePathDisplay([]);

  } catch (error) {
    console.error('Error generating tree:', error);
  }
}

async function generateLanguageSpaceMap() {
  try {
    // Clear existing scene
    clearScene();

    // Define multiple seed prompts for broader language space
    const seedPrompts = [
      "The", "A", "I", "You", "We", "They", "It", "This", "That", "Here", "There",
      "What", "How", "Why", "When", "Where", "Who", "Which", "All", "Some", "One",
      "Time", "Life", "World", "Day", "Night", "Light", "Dark", "Good", "Bad",
      "Love", "Hate", "Think", "Know", "Feel", "See", "Hear", "Say", "Do", "Make"
    ];

    console.log('Generating language space from', seedPrompts.length, 'seed prompts...');

    // Generate trees from multiple prompts
    const allTrees = [];
    const batchSize = 5; // Process in batches to avoid overwhelming the server

    for (let i = 0; i < seedPrompts.length; i += batchSize) {
      const batch = seedPrompts.slice(i, i + batchSize);
      const batchPromises = batch.map(async (prompt) => {
        try {
          const response = await fetch(`/api/completions?prompt=${encodeURIComponent(prompt)}`);
          const data = await response.json();
          return data.tree;
        } catch (error) {
          console.error(`Error generating tree for "${prompt}":`, error);
          return null;
        }
      });

      const batchResults = await Promise.all(batchPromises);
      allTrees.push(...batchResults.filter(tree => tree !== null));

      // Small delay between batches
      if (i + batchSize < seedPrompts.length) {
        await new Promise(resolve => setTimeout(resolve, 100));
      }
    }

    console.log('Generated', allTrees.length, 'trees for language space');

    // Merge all trees into a comprehensive language space
    const mergedTree = mergeTreesIntoSpace(allTrees);
    currentTreeData = mergedTree;

    visualizeLanguageSpace(mergedTree);
    buildWordMap(mergedTree);
    updatePathDisplay([]);

    console.log('Language space visualization complete');

  } catch (error) {
    console.error('Error generating language space:', error);
  }
}

function mergeTreesIntoSpace(trees) {
  // Create a root node for the language space
  const spaceRoot = {
    word: "Language",
    x: 0,
    y: 0,
    z: 0,
    children: []
  };

  // Collect all unique words and their relationships
  const wordConnections = new Map();

  trees.forEach(tree => {
    collectWordConnections(tree, wordConnections);
  });

  // Build the merged tree structure
  buildMergedTree(spaceRoot, wordConnections, new Set());

  return spaceRoot;
}

function collectWordConnections(node, connections, path = []) {
  const currentPath = [...path, node.word];

  if (!connections.has(node.word)) {
    connections.set(node.word, {
      node: node,
      parents: new Set(),
      children: new Map()
    });
  }

  // Add parent relationships
  if (path.length > 0) {
    const parentWord = path[path.length - 1];
    connections.get(node.word).parents.add(parentWord);

    // Add child relationships to parent
    if (!connections.get(parentWord).children.has(node.word)) {
      connections.get(parentWord).children.set(node.word, []);
    }
    connections.get(parentWord).children.get(node.word).push(node);
  }

  // Process children
  node.children.forEach(child => {
    collectWordConnections(child, connections, currentPath);
  });
}

function buildMergedTree(root, connections, visited, maxDepth = 8) {
  const queue = [{ node: root, depth: 0 }];
  const processedNodes = new Map();

  while (queue.length > 0) {
    const { node, depth } = queue.shift();
    const word = node.word;

    if (depth >= maxDepth || visited.has(word)) continue;
    visited.add(word);

    if (connections.has(word)) {
      const connectionData = connections.get(word);

      // Add children from the merged connections
      for (const [childWord, childNodes] of connectionData.children) {
        if (!visited.has(childWord)) {
          // Use the first occurrence of this child word
          const childNode = { ...childNodes[0] };

          // Position children in a more spread out manner for better visualization
          const angle = Math.random() * Math.PI * 2;
          const radius = 3 + depth * 2;
          childNode.x = node.x + Math.cos(angle) * radius;
          childNode.y = node.y + Math.sin(angle) * radius;
          childNode.z = node.z - 1;

          node.children.push(childNode);
          queue.push({ node: childNode, depth: depth + 1 });
        }
      }
    }
  }
}

function clearScene() {
  // Remove all meshes and lines
  nodes.forEach(node => scene.remove(node));
  edges.forEach(edge => scene.remove(edge));
  nodes.length = 0;
  edges.length = 0;
  wordMap.clear();
  selectedNode = null;
  highlightedPath = [];

  // Clear word map UI
  const wordmapContent = document.getElementById('wordmap-content');
  wordmapContent.innerHTML = '';
}

init();
animate();

async function init() {
  // Scene
  scene = new THREE.Scene();
  scene.background = new THREE.Color(0x111111);

  // Camera - adjust for left panel
  camera = new THREE.PerspectiveCamera(75, (window.innerWidth - 400) / window.innerHeight, 0.1, 1000);
  camera.position.set(10, 5, 10);
  camera.lookAt(0, 0, 0);

  // Renderer - adjust for left panel
  renderer = new THREE.WebGLRenderer({ antialias: true });
  renderer.setSize(window.innerWidth - 400, window.innerHeight);
  const treeContainer = document.querySelector('.tree-container');
  treeContainer.appendChild(renderer.domElement);

  // Label Renderer
  labelRenderer = new CSS2DRenderer();
  labelRenderer.setSize(window.innerWidth - 400, window.innerHeight);
  labelRenderer.domElement.style.position = 'absolute';
  labelRenderer.domElement.style.top = '0px';
  labelRenderer.domElement.style.left = '0px';
  labelRenderer.domElement.style.pointerEvents = 'none';
  treeContainer.appendChild(labelRenderer.domElement);

  // Controls
  controls = new OrbitControls(camera, labelRenderer.domElement);
  controls.enableDamping = true;
  controls.dampingFactor = 0.05;
  controls.screenSpacePanning = false;
  controls.minDistance = 1;
  controls.maxDistance = 500;
  controls.target.set(0, 0, 0);

  // Lights
  const ambientLight = new THREE.AmbientLight(0xffffff, 0.5);
  scene.add(ambientLight);
  const pointLight = new THREE.PointLight(0xffffff, 0.5);
  camera.add(pointLight);
  scene.add(camera);

  // Initial language space generation
  console.log('Initializing language space...');
  await generateLanguageSpaceMap();

  window.addEventListener('resize', onWindowResize, false);
  document.addEventListener('mousemove', onMouseMove, false);

  // Word map click handler
  document.getElementById('wordmap-content').addEventListener('click', onWordMapClick);
}

function visualizeTree(tree) {
  const geometry = new THREE.SphereGeometry(0.15, 16, 16);
  const material = new THREE.MeshPhongMaterial({ color: 0x00ff00 });

  function traverse(node, parentMesh, path = []) {
    const currentPath = [...path, node.word];

    const nodeMesh = new THREE.Mesh(geometry, material.clone());
    nodeMesh.position.set(node.x, node.y, node.z);
    nodeMesh.userData = {
      word: node.word,
      path: currentPath,
      nodeData: node,
      originalColor: 0x00ff00
    };
    scene.add(nodeMesh);
    nodes.push(nodeMesh);

    const nodeLabel = new CSS2DObject(createLabel(node.word));
    nodeLabel.position.copy(nodeMesh.position);
    nodeMesh.add(nodeLabel);

    if (parentMesh) {
      const points = [parentMesh.position, nodeMesh.position];
      const lineGeometry = new THREE.BufferGeometry().setFromPoints(points);
      const lineMaterial = new THREE.LineBasicMaterial({
        color: 0xcccccc,
        transparent: true,
        opacity: 0.3
      });
      const line = new THREE.Line(lineGeometry, lineMaterial);
      scene.add(line);
      edges.push(line);
    }

    node.children.forEach(child => traverse(child, nodeMesh, currentPath));
  }

  traverse(tree, null, []);
}

function visualizeLanguageSpace(tree) {
  const geometry = new THREE.SphereGeometry(0.1, 12, 12);
  const baseMaterial = new THREE.MeshPhongMaterial({ color: 0x888888 }); // Gray for base language space

  function traverse(node, parentMesh, path = []) {
    const currentPath = [...path, node.word];

    const nodeMesh = new THREE.Mesh(geometry, baseMaterial.clone());
    nodeMesh.position.set(node.x, node.y, node.z);
    nodeMesh.userData = {
      word: node.word,
      path: currentPath,
      nodeData: node,
      originalColor: 0x888888,
      isHighlighted: false
    };
    scene.add(nodeMesh);
    nodes.push(nodeMesh);

    const nodeLabel = new CSS2DObject(createLabel(node.word));
    nodeLabel.position.copy(nodeMesh.position);
    nodeMesh.add(nodeLabel);

    if (parentMesh) {
      const points = [parentMesh.position, nodeMesh.position];
      const lineGeometry = new THREE.BufferGeometry().setFromPoints(points);
      const lineMaterial = new THREE.LineBasicMaterial({
        color: 0x666666,
        transparent: true,
        opacity: 0.2
      });
      const line = new THREE.Line(lineGeometry, lineMaterial);
      scene.add(line);
      edges.push(line);
    }

    node.children.forEach(child => traverse(child, nodeMesh, currentPath));
  }

  traverse(tree, null, []);
}

function buildWordMap(tree) {
  wordMap.clear();
  const wordmapContent = document.getElementById('wordmap-content');
  wordmapContent.innerHTML = '';

  function collectWords(node, path = []) {
    const currentPath = [...path, node.word];
    const wordKey = node.word.toLowerCase();

    if (!wordMap.has(wordKey)) {
      wordMap.set(wordKey, {
        word: node.word,
        occurrences: [],
        paths: []
      });
    }

    const wordData = wordMap.get(wordKey);
    wordData.occurrences.push(node);
    wordData.paths.push(currentPath);

    node.children.forEach(child => collectWords(child, currentPath));
  }

  collectWords(tree, []);

  // Sort by frequency and create UI elements
  const sortedWords = Array.from(wordMap.entries())
    .sort((a, b) => b[1].occurrences.length - a[1].occurrences.length);

  sortedWords.forEach(([key, data]) => {
    const wordElement = document.createElement('div');
    wordElement.className = 'word-item';
    wordElement.textContent = `${data.word} (${data.occurrences.length})`;
    wordElement.dataset.word = key;

    wordElement.addEventListener('mouseenter', () => {
      highlightWordInTree(data.occurrences);
    });

    wordElement.addEventListener('mouseleave', () => {
      clearWordHighlight();
    });

    wordmapContent.appendChild(wordElement);
  });
}

function highlightWordInTree(occurrences) {
  // Clear previous highlights
  nodes.forEach(node => {
    if (node.material.color.getHex() !== 0x00ff00) {
      node.material.color.setHex(0x00ff00);
    }
  });

  // Highlight matching nodes
  occurrences.forEach(node => {
    const nodeMesh = nodes.find(mesh => mesh.userData.nodeData === node);
    if (nodeMesh) {
      nodeMesh.material.color.setHex(0xffa500);
    }
  });
}

function clearWordHighlight() {
  nodes.forEach(node => {
    if (node.material.color.getHex() !== 0x00ff00) {
      node.material.color.setHex(0x00ff00);
    }
  });
}

function onWordMapClick(event) {
  if (event.target.classList.contains('word-item')) {
    const wordKey = event.target.dataset.word;
    const wordData = wordMap.get(wordKey);

    if (wordData) {
      // Clear previous selection
      document.querySelectorAll('.word-item.selected').forEach(el => {
        el.classList.remove('selected');
      });

      // Select current word
      event.target.classList.add('selected');

      // Highlight in tree
      highlightWordInTree(wordData.occurrences);

      // Show first path
      if (wordData.paths.length > 0) {
        updatePathDisplay(wordData.paths[0]);
      }
    }
  }
}

function createLabel(text) {
    const div = document.createElement('div');
    div.className = 'label';
    div.textContent = text;
    div.style.marginTop = '-1em';
    div.style.padding = '4px 8px';
    div.style.color = '#fff';
    div.style.background = 'rgba(0, 0, 0, 0.6)';
    div.style.borderRadius = '4px';
    div.style.fontSize = '12px';
    div.style.fontFamily = 'sans-serif';
    div.style.pointerEvents = 'none'; // to allow orbit controls to work through the label
    div.style.visibility = 'hidden'; // Initially hidden
    return div;
}

function updatePathDisplay(path) {
  const pathText = document.getElementById('path-text');
  if (path.length === 0) {
    pathText.textContent = 'Hover over nodes to see paths';
  } else {
    pathText.textContent = path.join(' → ');
  }
}

function highlightPath(path) {
  // Clear previous path highlights
  clearPathHighlight();

  if (path.length === 0) return;

  // Highlight path nodes and edges
  for (let i = 0; i < path.length; i++) {
    const word = path[i];
    const nodeMesh = nodes.find(mesh => mesh.userData.word === word);

    if (nodeMesh) {
      // Highlight node
      nodeMesh.material.emissive.setHex(0x444444);

      // Highlight edge to parent (except root)
      if (i > 0) {
        const parentWord = path[i - 1];
        const parentNode = nodes.find(mesh => mesh.userData.word === parentWord);

        if (parentNode) {
          // Find the edge between parent and current node
          const edge = edges.find(edge => {
            const positions = edge.geometry.attributes.position.array;
            const startPos = new THREE.Vector3(positions[0], positions[1], positions[2]);
            const endPos = new THREE.Vector3(positions[3], positions[4], positions[5]);

            const startMatch = startPos.distanceTo(parentNode.position) < 0.1;
            const endMatch = endPos.distanceTo(nodeMesh.position) < 0.1;

            return startMatch && endMatch;
          });

          if (edge) {
            highlightedPath.push(edge);
            edge.material.color.setHex(0xffaa00);
            edge.material.opacity = 0.8;
          }
        }
      }
    }
  }
}

function clearPathHighlight() {
  // Clear node highlights
  nodes.forEach(node => {
    node.material.emissive.setHex(0x000000);
  });

  // Clear edge highlights
  highlightedPath.forEach(edge => {
    edge.material.color.setHex(0xcccccc);
    edge.material.opacity = 0.3;
  });
  highlightedPath = [];
}

function highlightSubtreeFromWord(targetWord) {
  console.log('Highlighting subtree from word:', targetWord);

  // Clear any existing subtree highlights
  clearAllHighlights();

  // Find all nodes with the target word
  const targetNodes = nodes.filter(node =>
    node.userData.word.toLowerCase() === targetWord.toLowerCase()
  );

  if (targetNodes.length === 0) {
    console.log('Word not found in the tree');
    return;
  }

  console.log('Found', targetNodes.length, 'nodes with the target word');

  // For each target node, highlight its subtree
  targetNodes.forEach(targetNode => {
    highlightSubtree(targetNode, 0xff4444); // Red for highlighted subtrees
  });
}

function highlightSubtree(rootNode, highlightColor) {
  const visited = new Set();
  const queue = [rootNode];

  while (queue.length > 0) {
    const currentNode = queue.shift();
    const nodeId = currentNode.id || currentNode.uuid;

    if (visited.has(nodeId)) continue;
    visited.add(nodeId);

    // Highlight the node
    currentNode.material.color.setHex(highlightColor);
    currentNode.userData.isHighlighted = true;

    // Find and highlight edges connected to this node
    currentNode.userData.nodeData.children.forEach(childData => {
      const childNode = nodes.find(node =>
        node.userData.nodeData === childData
      );

      if (childNode) {
        // Find the edge between current node and child
        const edge = edges.find(edge => {
          if (!edge.geometry.attributes.position) return false;
          const positions = edge.geometry.attributes.position.array;
          if (positions.length < 6) return false;

          const startPos = new THREE.Vector3(positions[0], positions[1], positions[2]);
          const endPos = new THREE.Vector3(positions[3], positions[4], positions[5]);

          const startMatch = startPos.distanceTo(currentNode.position) < 0.1;
          const endMatch = endPos.distanceTo(childNode.position) < 0.1;

          return startMatch && endMatch;
        });

        if (edge) {
          edge.material.color.setHex(highlightColor);
          edge.material.opacity = 0.8;
          highlightedPath.push(edge);
        }

        // Add child to queue for further processing
        queue.push(childNode);
      }
    });
  }
}

function clearAllHighlights() {
  console.log('Clearing all highlights');

  // Reset all node colors to their original colors
  nodes.forEach(node => {
    const originalColor = node.userData.originalColor || 0x888888;
    node.material.color.setHex(originalColor);
    node.userData.isHighlighted = false;
  });

  // Reset all edge colors
  edges.forEach(edge => {
    edge.material.color.setHex(0x666666);
    edge.material.opacity = 0.2;
  });

  // Clear highlighted path array
  highlightedPath = [];

  // Clear path display
  updatePathDisplay([]);
}

function onWindowResize() {
  const treeContainer = document.querySelector('.tree-container');
  const rect = treeContainer.getBoundingClientRect();

  camera.aspect = rect.width / rect.height;
  camera.updateProjectionMatrix();
  renderer.setSize(rect.width, rect.height);
  labelRenderer.setSize(rect.width, rect.height);
}

function onMouseMove(event) {
    const treeContainer = document.querySelector('.tree-container');
    const rect = treeContainer.getBoundingClientRect();

    // Only process mouse events within the tree container
    if (event.clientX < rect.left || event.clientX > rect.right ||
        event.clientY < rect.top || event.clientY > rect.bottom) {
      return;
    }

    event.preventDefault();
    mouse.x = ((event.clientX - rect.left) / rect.width) * 2 - 1;
    mouse.y = -((event.clientY - rect.top) / rect.height) * 2 + 1;
}

function animate() {
  requestAnimationFrame(animate);
  controls.update();

  raycaster.setFromCamera(mouse, camera);
  const intersects = raycaster.intersectObjects(nodes);

  if (intersects.length > 0) {
    if (INTERSECTED != intersects[0].object) {
      if (INTERSECTED) {
          INTERSECTED.material.emissive.setHex(INTERSECTED.currentHex);
          INTERSECTED.children[0].element.style.visibility = 'hidden';
          clearPathHighlight();
      }
      INTERSECTED = intersects[0].object;
      INTERSECTED.currentHex = INTERSECTED.material.emissive.getHex();
      INTERSECTED.material.emissive.setHex(0xff0000);
      INTERSECTED.children[0].element.style.visibility = 'visible';

      // Show path for hovered node
      if (INTERSECTED.userData.path) {
        updatePathDisplay(INTERSECTED.userData.path);
        highlightPath(INTERSECTED.userData.path);
      }

    }
  } else {
    if (INTERSECTED) {
        INTERSECTED.material.emissive.setHex(INTERSECTED.currentHex);
        INTERSECTED.children[0].element.style.visibility = 'hidden';
        clearPathHighlight();
        updatePathDisplay([]);
    }
    INTERSECTED = null;
  }

  renderer.render(scene, camera);
  labelRenderer.render(scene, camera);
}
