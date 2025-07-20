/**
 * 数学理論生物体系図 - シンプル版
 */

// シンプルなデータセット
const simpleData = {
  nodes: [
    { data: { id: 'foundations', label: '基礎論' } },
    { data: { id: 'pure_math', label: '純粋数学' } },
    { data: { id: 'applied_math', label: '応用数学' } },
    { data: { id: 'interdisciplinary', label: '学際領域' } }
  ],
  edges: [
    { data: { source: 'foundations', target: 'pure_math' } },
    { data: { source: 'pure_math', target: 'applied_math' } },
    { data: { source: 'applied_math', target: 'interdisciplinary' } }
  ]
};

let cy;

function initializeCytoscape() {
  console.log('Initializing simple cytoscape...');
  
  const container = document.getElementById('cy');
  if (!container) {
    console.error('Container #cy not found!');
    return;
  }
  
  console.log('Container found, creating cytoscape instance...');
  
  try {
    cy = cytoscape({
      container: container,
      elements: [...simpleData.nodes, ...simpleData.edges],
      style: [
        {
          selector: 'node',
          style: {
            'background-color': '#666',
            'label': 'data(label)',
            'color': 'white',
            'text-valign': 'center',
            'text-halign': 'center',
            'width': 60,
            'height': 60
          }
        },
        {
          selector: 'edge',
          style: {
            'width': 3,
            'line-color': '#ccc',
            'target-arrow-color': '#ccc',
            'target-arrow-shape': 'triangle'
          }
        }
      ],
      layout: {
        name: 'grid',
        rows: 2,
        cols: 2
      }
    });
    
    console.log('Cytoscape created successfully!');
    console.log('Nodes:', cy.nodes().length);
    console.log('Edges:', cy.edges().length);
    
    // 簡単なイベントハンドラ
    cy.on('tap', 'node', function(evt) {
      const node = evt.target;
      console.log('Node clicked:', node.data('label'));
      alert('Clicked: ' + node.data('label'));
    });
    
  } catch (error) {
    console.error('Error creating cytoscape:', error);
  }
}

// ページ読み込み時の初期化
document.addEventListener('DOMContentLoaded', function() {
  console.log('DOM Content Loaded');
  console.log('Cytoscape available:', typeof cytoscape !== 'undefined');
  
  setTimeout(() => {
    initializeCytoscape();
  }, 100); // 少し遅らせて確実にライブラリが読み込まれるのを待つ
});

// グローバル関数
function resetView() {
  if (cy) {
    cy.fit();
    cy.center();
  }
}

function fitToView() {
  if (cy) {
    cy.fit(cy.elements(), 50);
  }
}

function toggleLayout() {
  if (!cy) return;
  
  const layouts = ['grid', 'circle', 'random'];
  const currentLayout = cy.layout().options.name;
  const currentIndex = layouts.indexOf(currentLayout);
  const nextIndex = (currentIndex + 1) % layouts.length;
  const nextLayout = layouts[nextIndex];
  
  console.log('Switching to layout:', nextLayout);
  
  cy.layout({ name: nextLayout }).run();
}

// グローバル関数として公開
window.resetView = resetView;
window.fitToView = fitToView;
window.toggleLayout = toggleLayout; 