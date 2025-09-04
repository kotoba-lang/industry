import * as THREE from 'three';
import { OrbitControls } from 'three/addons/controls/OrbitControls.js';
import { CSS2DRenderer, CSS2DObject } from 'three/addons/renderers/CSS2DRenderer.js';

let scene, camera, renderer, labelRenderer;
let controls;
const nodes = [];
const raycaster = new THREE.Raycaster();
const mouse = new THREE.Vector2();
let INTERSECTED;

init();
animate();

async function init() {
  // Scene
  scene = new THREE.Scene();
  scene.background = new THREE.Color(0x111111);

  // Camera
  camera = new THREE.PerspectiveCamera(75, window.innerWidth / window.innerHeight, 0.1, 1000);
  camera.position.z = 10;

  // Renderer
  renderer = new THREE.WebGLRenderer({ antialias: true });
  renderer.setSize(window.innerWidth, window.innerHeight);
  document.body.appendChild(renderer.domElement);
  
  // Label Renderer
  labelRenderer = new CSS2DRenderer();
  labelRenderer.setSize(window.innerWidth, window.innerHeight);
  labelRenderer.domElement.style.position = 'absolute';
  labelRenderer.domElement.style.top = '0px';
  document.body.appendChild(labelRenderer.domElement);

  // Controls
  controls = new OrbitControls(camera, labelRenderer.domElement);
  controls.enableDamping = true;
  controls.dampingFactor = 0.05;
  controls.screenSpacePanning = false;
  controls.minDistance = 1;
  controls.maxDistance = 500;

  // Lights
  const ambientLight = new THREE.AmbientLight(0xffffff, 0.5);
  scene.add(ambientLight);
  const pointLight = new THREE.PointLight(0xffffff, 0.5);
  camera.add(pointLight);
  scene.add(camera);

  // Data fetching and visualization
  const response = await fetch('/api/completions?prompt=Intelligence');
  const data = await response.json();
  visualizeTree(data.tree);

  window.addEventListener('resize', onWindowResize, false);
  document.addEventListener('mousemove', onMouseMove, false);
}

function visualizeTree(tree) {
  const geometry = new THREE.SphereGeometry(0.2, 16, 16);
  const material = new THREE.MeshPhongMaterial({ color: 0x00ff00 });

  function traverse(node, parentMesh) {
    const nodeMesh = new THREE.Mesh(geometry, material.clone());
    nodeMesh.position.set(node.x, node.y, node.z);
    nodeMesh.userData = { word: node.word };
    scene.add(nodeMesh);
    nodes.push(nodeMesh);
    
    const nodeLabel = new CSS2DObject(createLabel(node.word));
    nodeLabel.position.copy(nodeMesh.position);
    nodeMesh.add(nodeLabel);


    if (parentMesh) {
      const points = [parentMesh.position, nodeMesh.position];
      const lineGeometry = new THREE.BufferGeometry().setFromPoints(points);
      const lineMaterial = new THREE.LineBasicMaterial({ color: 0xcccccc, transparent: true, opacity: 0.5 });
      const line = new THREE.Line(lineGeometry, lineMaterial);
      scene.add(line);
    }

    node.children.forEach(child => traverse(child, nodeMesh));
  }
  
  traverse(tree, null);
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


function onWindowResize() {
  camera.aspect = window.innerWidth / window.innerHeight;
  camera.updateProjectionMatrix();
  renderer.setSize(window.innerWidth, window.innerHeight);
  labelRenderer.setSize(window.innerWidth, window.innerHeight);
}

function onMouseMove(event) {
    event.preventDefault();
    mouse.x = (event.clientX / window.innerWidth) * 2 - 1;
    mouse.y = -(event.clientY / window.innerHeight) * 2 + 1;
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
      }
      INTERSECTED = intersects[0].object;
      INTERSECTED.currentHex = INTERSECTED.material.emissive.getHex();
      INTERSECTED.material.emissive.setHex(0xff0000);
      INTERSECTED.children[0].element.style.visibility = 'visible';

    }
  } else {
    if (INTERSECTED) {
        INTERSECTED.material.emissive.setHex(INTERSECTED.currentHex);
        INTERSECTED.children[0].element.style.visibility = 'hidden';
    }
    INTERSECTED = null;
  }
  
  renderer.render(scene, camera);
  labelRenderer.render(scene, camera);
}
