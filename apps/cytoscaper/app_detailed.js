/**
 * 数学理論生物体系図 - 詳細版
 * Mathematical Theory Organism Visualization - Detailed Version
 */

let cy;
let currentLayout = 'cose';
let mathTheoryData = null;

/**
 * JSONデータの読み込み
 */
async function loadDetailedTheoryData() {
  try {
    console.log('Loading detailed theory data...');
    const response = await fetch('data/detailed-theory-data.json');
    
    if (!response.ok) {
      throw new Error(`HTTP error! status: ${response.status}`);
    }
    
    mathTheoryData = await response.json();
    console.log('Detailed theory data loaded successfully');
    console.log('Nodes:', mathTheoryData.nodes.length);
    console.log('Edges:', mathTheoryData.edges.length);
    
    return mathTheoryData;
  } catch (error) {
    console.error('Error loading detailed theory data:', error);
    throw error;
  }
}

/**
 * Cytoscapeインスタンスの初期化
 */
async function initializeCytoscape() {
  console.log('Initializing detailed cytoscape...');
  
  // データが読み込まれていない場合は読み込む
  if (!mathTheoryData) {
    try {
      await loadDetailedTheoryData();
    } catch (error) {
      console.error('Failed to load detailed theory data:', error);
      return;
    }
  }
  
  console.log('Node count:', mathTheoryData.nodes.length);
  console.log('Edge count:', mathTheoryData.edges.length);
  
  const container = document.getElementById('cy');
  if (!container) {
    console.error('Container element not found!');
    return;
  }
  
  cy = cytoscape({
    container: container,
    elements: [...mathTheoryData.nodes, ...mathTheoryData.edges],
    
    style: [
      // 基本ノードスタイル
      {
        selector: 'node',
        style: {
          'label': 'data(label)',
          'text-valign': 'center',
          'text-halign': 'center',
          'color': '#ffffff',
          'text-shadow': '0 0 4px rgba(0, 0, 0, 0.8)',
          'font-size': '10px',
          'font-weight': 'bold',
          'border-width': 2,
          'border-color': '#ffffff',
          'border-opacity': 0.3,
          'text-wrap': 'wrap',
          'text-max-width': '80px'
        }
      },
      
      // 基礎論（根系）のスタイル
      {
        selector: 'node[type="foundation"]',
        style: {
          'background-color': '#8B4513',
          'shape': 'round-hexagon',
          'width': 70,
          'height': 70,
          'border-color': '#CD853F',
          'box-shadow': '0 0 15px rgba(139, 69, 19, 0.6)'
        }
      },
      
      // 純粋数学（幹・枝）のスタイル
      {
        selector: 'node[type="pure_math"]',
        style: {
          'background-color': '#228B22',
          'shape': 'round-rectangle',
          'width': 80,
          'height': 60,
          'border-color': '#90EE90',
          'box-shadow': '0 0 15px rgba(34, 139, 34, 0.6)'
        }
      },
      
      // 応用数学（葉・花）のスタイル
      {
        selector: 'node[type="applied_math"]',
        style: {
          'background-color': '#FFD700',
          'shape': 'round-diamond',
          'width': 65,
          'height': 65,
          'border-color': '#FFFF00',
          'color': '#000000',
          'text-shadow': '0 0 2px rgba(255, 255, 255, 0.8)',
          'box-shadow': '0 0 15px rgba(255, 215, 0, 0.6)'
        }
      },
      
      // 学際領域（果実・種）のスタイル
      {
        selector: 'node[type="interdisciplinary"]',
        style: {
          'background-color': '#FF6347',
          'shape': 'round-octagon',
          'width': 75,
          'height': 75,
          'border-color': '#FF7F50',
          'box-shadow': '0 0 18px rgba(255, 99, 71, 0.7)'
        }
      },
      
      // エッジの基本スタイル
      {
        selector: 'edge',
        style: {
          'width': 2,
          'line-color': '#00d4aa',
          'target-arrow-color': '#00d4aa',
          'target-arrow-shape': 'triangle',
          'curve-style': 'bezier',
          'opacity': 0.8,
          'arrow-scale': 1.2
        }
      },
      
      // 栄養供給フロー（根からの栄養）
      {
        selector: 'edge[type="nutrient_flow"], edge[type="foundation_to_pure"]',
        style: {
          'line-color': '#8B4513',
          'target-arrow-color': '#8B4513',
          'width': 3,
          'line-style': 'solid'
        }
      },
      
      // 成長フロー（幹から枝）
      {
        selector: 'edge[type="growth_flow"]',
        style: {
          'line-color': '#228B22',
          'target-arrow-color': '#228B22',
          'width': 3,
          'line-style': 'solid'
        }
      },
      
      // 相互作用（枝間の交流）
      {
        selector: 'edge[type="cross_pollination"]',
        style: {
          'line-color': '#32CD32',
          'target-arrow-color': '#32CD32',
          'width': 2,
          'line-style': 'dashed',
          'curve-style': 'unbundled-bezier'
        }
      },
      
      // 応用フロー（枝から葉）
      {
        selector: 'edge[type="application_flow"], edge[type="specialization"]',
        style: {
          'line-color': '#FFD700',
          'target-arrow-color': '#FFD700',
          'width': 2.5,
          'line-style': 'solid'
        }
      },
      
      // 統合フロー（葉から果実）
      {
        selector: 'edge[type="synthesis_flow"], edge[type="fruition"]',
        style: {
          'line-color': '#FF6347',
          'target-arrow-color': '#FF6347',
          'width': 2.5,
          'line-style': 'solid'
        }
      },
      
      // 深層サポート（深根からの直接支援）
      {
        selector: 'edge[type="deep_root_support"]',
        style: {
          'line-color': '#A0522D',
          'target-arrow-color': '#A0522D',
          'width': 2,
          'line-style': 'dotted',
          'curve-style': 'segments'
        }
      },
      
      // 選択時のスタイル
      {
        selector: 'node:selected',
        style: {
          'border-width': 4,
          'border-color': '#00d4aa',
          'box-shadow': '0 0 25px rgba(0, 212, 170, 1)'
        }
      }
    ],
    
    layout: {
      name: 'cose',
      animate: true,
      animationDuration: 1500,
      nodeRepulsion: 400000,
      nodeOverlap: 20,
      idealEdgeLength: 100,
      edgeElasticity: 100,
      nestingFactor: 5,
      gravity: 80,
      numIter: 1000,
      initialTemp: 200,
      coolingFactor: 0.95,
      minTemp: 1.0,
      fit: true,
      padding: 50
    }
  });
  
  console.log('Detailed Cytoscape initialized successfully!');
  console.log('Nodes:', cy.nodes().length);
  console.log('Edges:', cy.edges().length);
  
  setupEventHandlers();
}

/**
 * イベントハンドラーの設定
 */
function setupEventHandlers() {
  const infoPanel = document.getElementById('infoPanel');
  const infoTitle = document.getElementById('infoTitle');
  const infoDescription = document.getElementById('infoDescription');
  
  // ノードクリックイベント
  cy.on('tap', 'node', function(evt) {
    const node = evt.target;
    const data = node.data();
    
    infoTitle.textContent = data.label.replace(/\n/g, ' ');
    infoDescription.textContent = data.description || 'このノードの詳細情報が利用可能です。';
    
    if (infoPanel) {
      infoPanel.classList.add('visible');
    }
    
    // 関連ノードのハイライト
    highlightConnectedNodes(node);
  });
  
  // 背景クリックで情報パネルを閉じる
  cy.on('tap', function(evt) {
    if (evt.target === cy) {
      if (infoPanel) {
        infoPanel.classList.remove('visible');
      }
      cy.elements().removeClass('highlighted dimmed');
    }
  });
  
  // ノードホバーエフェクト
  cy.on('mouseover', 'node', function(evt) {
    const node = evt.target;
    node.style('cursor', 'pointer');
    
    // 軽いハイライト効果
    node.style({
      'border-width': 3,
      'border-opacity': 0.8
    });
  });
  
  cy.on('mouseout', 'node', function(evt) {
    const node = evt.target;
    if (!node.selected()) {
      node.style({
        'border-width': 2,
        'border-opacity': 0.3
      });
    }
  });
}

/**
 * 関連ノードのハイライト
 */
function highlightConnectedNodes(selectedNode) {
  cy.elements().addClass('dimmed');
  
  const connectedNodes = selectedNode.neighborhood().add(selectedNode);
  connectedNodes.removeClass('dimmed').addClass('highlighted');
  
  // ハイライトスタイルの適用
  cy.style()
    .selector('.highlighted')
    .style({
      'opacity': 1,
      'z-index': 10
    })
    .selector('.dimmed')
    .style({
      'opacity': 0.3,
      'z-index': 1
    })
    .update();
}

/**
 * ビューのリセット
 */
function resetView() {
  if (cy) {
    cy.fit();
    cy.center();
    cy.elements().removeClass('highlighted dimmed');
    const infoPanel = document.getElementById('infoPanel');
    if (infoPanel) {
      infoPanel.classList.remove('visible');
    }
  }
}

/**
 * 全体表示
 */
function fitToView() {
  if (cy) {
    cy.fit(cy.elements(), 50);
  }
}

/**
 * レイアウト変更
 */
function toggleLayout() {
  if (!cy) return;
  
  const layouts = ['cose', 'circle', 'grid', 'breadthfirst'];
  const currentIndex = layouts.indexOf(currentLayout);
  const nextIndex = (currentIndex + 1) % layouts.length;
  currentLayout = layouts[nextIndex];
  
  let layoutOptions;
  
  switch(currentLayout) {
    case 'cose':
      layoutOptions = {
        name: 'cose',
        animate: true,
        animationDuration: 1000,
        nodeRepulsion: 400000,
        idealEdgeLength: 100,
        edgeElasticity: 100
      };
      break;
    case 'circle':
      layoutOptions = {
        name: 'circle',
        animate: true,
        animationDuration: 1000,
        radius: 250
      };
      break;
    case 'grid':
      layoutOptions = {
        name: 'grid',
        animate: true,
        animationDuration: 1000,
        rows: 4,
        cols: 5
      };
      break;
    case 'breadthfirst':
      layoutOptions = {
        name: 'breadthfirst',
        animate: true,
        animationDuration: 1000,
        directed: true,
        roots: '#foundations',
        spacingFactor: 1.75
      };
      break;
  }
  
  console.log('Switching to layout:', currentLayout);
  cy.layout(layoutOptions).run();
}

/**
 * ページ読み込み時の初期化
 */
document.addEventListener('DOMContentLoaded', async function() {
  console.log('DOM Content Loaded - Detailed Version');
  console.log('Cytoscape available:', typeof cytoscape !== 'undefined');
  
  setTimeout(async () => {
    try {
      await initializeCytoscape();
      
      // アニメーション効果の追加
      setTimeout(() => {
        if (cy) {
          cy.elements().forEach((ele, index) => {
            setTimeout(() => {
              ele.style('opacity', 1);
            }, index * 30);
          });
        }
      }, 500);
      
    } catch (error) {
      console.error('Error initializing detailed Cytoscape:', error);
    }
  }, 200);
});

// グローバル関数として公開
window.resetView = resetView;
window.fitToView = fitToView;
window.toggleLayout = toggleLayout; 