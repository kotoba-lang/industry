/**
 * 数学理論生物体系図 - 完全版
 * Mathematical Theory Organism Visualization - Complete Edition
 * 
 * 数学の主要理論を完全な生物学的メタファーで表現
 */

let cy;
let currentLayout = 'cose';
let completeTheoryData = null;

/**
 * JSONデータの読み込み
 */
async function loadTheoryData() {
  try {
    console.log('Loading complete theory data...');
    const response = await fetch('data/complete-theory-data.json');
    
    if (!response.ok) {
      throw new Error(`HTTP error! status: ${response.status}`);
    }
    
    completeTheoryData = await response.json();
    console.log('Complete theory data loaded successfully');
    console.log('Nodes:', completeTheoryData.nodes.length);
    console.log('Edges:', completeTheoryData.edges.length);
    
    return completeTheoryData;
  } catch (error) {
    console.error('Error loading theory data:', error);
    throw error;
  }
}

/**
 * Cytoscapeインスタンスの初期化
 */
async function initializeCytoscape() {
  console.log('Initializing complete mathematical theory organism...');
  
  // データが読み込まれていない場合は読み込む
  if (!completeTheoryData) {
    try {
      await loadTheoryData();
    } catch (error) {
      console.error('Failed to load theory data:', error);
      return;
    }
  }
  
  console.log('Total nodes:', completeTheoryData.nodes.length);
  console.log('Total edges:', completeTheoryData.edges.length);
  
  const container = document.getElementById('cy');
  if (!container) {
    console.error('Container element not found!');
    return;
  }
  
  cy = cytoscape({
    container: container,
    elements: [...completeTheoryData.nodes, ...completeTheoryData.edges],
    
    style: [
      // === 基本ノードスタイル ===
      {
        selector: 'node',
        style: {
          'label': 'data(label)',
          'text-valign': 'center',
          'text-halign': 'center',
          'color': '#ffffff',
          'text-shadow': '0 0 4px rgba(0, 0, 0, 0.8)',
          'font-size': '9px',
          'font-weight': 'bold',
          'border-width': 1.5,
          'border-color': '#ffffff',
          'border-opacity': 0.4,
          'text-wrap': 'wrap',
          'text-max-width': '80px'
        }
      },
      
      // === 基礎論（根系）===
      {
        selector: 'node[type="foundation_root"]',
        style: {
          'background-color': '#654321',
          'shape': 'round-hexagon',
          'width': 100,
          'height': 100,
          'border-color': '#8B7D6B',
          'box-shadow': '0 0 25px rgba(101, 67, 33, 0.8)',
          'font-size': '11px'
        }
      },
      {
        selector: 'node[type="foundation"]',
        style: {
          'background-color': '#8B4513',
          'shape': 'round-hexagon',
          'width': 75,
          'height': 75,
          'border-color': '#CD853F',
          'box-shadow': '0 0 18px rgba(139, 69, 19, 0.7)',
          'font-size': '10px'
        }
      },
      
      // === 純粋数学（幹・枝）===
      {
        selector: 'node[type="trunk"]',
        style: {
          'background-color': '#2E7D32',
          'shape': 'round-rectangle',
          'width': 95,
          'height': 75,
          'border-color': '#66BB6A',
          'box-shadow': '0 0 20px rgba(46, 125, 50, 0.8)',
          'font-size': '11px'
        }
      },
      {
        selector: 'node[type^="branch_"]',
        style: {
          'background-color': '#388E3C',
          'shape': 'round-rectangle',
          'width': 85,
          'height': 65,
          'border-color': '#81C784',
          'box-shadow': '0 0 15px rgba(56, 142, 60, 0.7)',
          'font-size': '10px'
        }
      },
      {
        selector: 'node[type="sub_branch"]',
        style: {
          'background-color': '#4CAF50',
          'shape': 'round-rectangle',
          'width': 70,
          'height': 55,
          'border-color': '#A5D6A7',
          'box-shadow': '0 0 12px rgba(76, 175, 80, 0.6)',
          'font-size': '9px'
        }
      },
      
      // === 応用数学（葉・花）===
      {
        selector: 'node[type="leaf_cluster"]',
        style: {
          'background-color': '#F57F17',
          'shape': 'round-diamond',
          'width': 90,
          'height': 90,
          'border-color': '#FFF176',
          'color': '#1A1A1A',
          'text-shadow': '0 0 3px rgba(255, 255, 255, 0.8)',
          'box-shadow': '0 0 20px rgba(245, 127, 23, 0.8)',
          'font-size': '11px'
        }
      },
      {
        selector: 'node[type="leaf"]',
        style: {
          'background-color': '#FBC02D',
          'shape': 'ellipse',
          'width': 75,
          'height': 60,
          'border-color': '#FFEB3B',
          'color': '#1A1A1A',
          'text-shadow': '0 0 2px rgba(255, 255, 255, 0.9)',
          'box-shadow': '0 0 15px rgba(251, 192, 45, 0.7)',
          'font-size': '9px'
        }
      },
      
      // === 学際領域（果実・種）===
      {
        selector: 'node[type="fruit_cluster"]',
        style: {
          'background-color': '#D32F2F',
          'shape': 'round-octagon',
          'width': 95,
          'height': 95,
          'border-color': '#EF5350',
          'box-shadow': '0 0 22px rgba(211, 47, 47, 0.8)',
          'font-size': '11px'
        }
      },
      {
        selector: 'node[type="fruit"]',
        style: {
          'background-color': '#F44336',
          'shape': 'round-octagon',
          'width': 80,
          'height': 80,
          'border-color': '#FFAB91',
          'box-shadow': '0 0 18px rgba(244, 67, 54, 0.7)',
          'font-size': '10px'
        }
      },
      {
        selector: 'node[type="seed"]',
        style: {
          'background-color': '#FF7043',
          'shape': 'round-triangle',
          'width': 65,
          'height': 65,
          'border-color': '#FFCCBC',
          'box-shadow': '0 0 15px rgba(255, 112, 67, 0.6)',
          'font-size': '9px'
        }
      },
      
      // === エッジスタイル ===
      {
        selector: 'edge',
        style: {
          'width': 1.5,
          'line-color': '#607D8B',
          'target-arrow-color': '#607D8B',
          'target-arrow-shape': 'triangle',
          'curve-style': 'bezier',
          'opacity': 0.7,
          'arrow-scale': 1.0
        }
      },
      
      // 栄養供給フロー（根からの栄養）
      {
        selector: 'edge[type="nutrient_flow"], edge[type="foundation_to_pure"]',
        style: {
          'line-color': '#8B4513',
          'target-arrow-color': '#8B4513',
          'width': 3,
          'line-style': 'solid',
          'opacity': 0.9
        }
      },
      
      // 成長フロー（幹から枝）
      {
        selector: 'edge[type="growth_flow"], edge[type="sub_branch_growth"]',
        style: {
          'line-color': '#388E3C',
          'target-arrow-color': '#388E3C',
          'width': 2.5,
          'line-style': 'solid',
          'opacity': 0.8
        }
      },
      
      // 相互作用（交差受粉）
      {
        selector: 'edge[type="cross_pollination"]',
        style: {
          'line-color': '#4CAF50',
          'target-arrow-color': '#4CAF50',
          'width': 2,
          'line-style': 'dashed',
          'curve-style': 'unbundled-bezier',
          'opacity': 0.6
        }
      },
      
      // 応用フロー
      {
        selector: 'edge[type="application_flow"], edge[type="specialization"]',
        style: {
          'line-color': '#FF9800',
          'target-arrow-color': '#FF9800',
          'width': 2.5,
          'line-style': 'solid',
          'opacity': 0.8
        }
      },
      
      // 統合・結実フロー
      {
        selector: 'edge[type="synthesis_flow"], edge[type="fruition"], edge[type="seed_formation"]',
        style: {
          'line-color': '#E91E63',
          'target-arrow-color': '#E91E63',
          'width': 2.5,
          'line-style': 'solid',
          'opacity': 0.8
        }
      },
      
      // 直接応用
      {
        selector: 'edge[type="direct_application"]',
        style: {
          'line-color': '#9C27B0',
          'target-arrow-color': '#9C27B0',
          'width': 2,
          'line-style': 'dotted',
          'opacity': 0.7
        }
      },
      
      // 深層サポート
      {
        selector: 'edge[type="deep_root_support"]',
        style: {
          'line-color': '#5D4037',
          'target-arrow-color': '#5D4037',
          'width': 2,
          'line-style': 'dotted',
          'curve-style': 'segments',
          'opacity': 0.6
        }
      },
      
      // 選択時のスタイル
      {
        selector: 'node:selected',
        style: {
          'border-width': 4,
          'border-color': '#00BCD4',
          'box-shadow': '0 0 30px rgba(0, 188, 212, 1)'
        }
      },
      
      // ハイライト状態
      {
        selector: '.highlighted',
        style: {
          'opacity': 1,
          'z-index': 10
        }
      },
      
      {
        selector: '.dimmed',
        style: {
          'opacity': 0.2,
          'z-index': 1
        }
      }
    ],
    
    layout: {
      name: 'cose',
      animate: true,
      animationDuration: 2000,
      nodeRepulsion: 800000,
      nodeOverlap: 40,
      idealEdgeLength: 120,
      edgeElasticity: 200,
      nestingFactor: 10,
      gravity: 100,
      numIter: 1500,
      initialTemp: 300,
      coolingFactor: 0.95,
      minTemp: 1.0,
      fit: true,
      padding: 60
    }
  });
  
  console.log('Complete mathematical theory organism initialized!');
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
    
    if (infoTitle && infoDescription) {
      infoTitle.textContent = data.label.replace(/\n/g, ' ');
      infoDescription.textContent = data.description || 'このノードの詳細情報が利用可能です。';
    }
    
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
        'border-width': 1.5,
        'border-opacity': 0.4
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
    cy.fit(cy.elements(), 60);
  }
}

/**
 * レイアウト変更
 */
function toggleLayout() {
  if (!cy) return;
  
  const layouts = ['cose', 'circle', 'breadthfirst', 'grid', 'concentric'];
  const currentIndex = layouts.indexOf(currentLayout);
  const nextIndex = (currentIndex + 1) % layouts.length;
  currentLayout = layouts[nextIndex];
  
  let layoutOptions;
  
  switch(currentLayout) {
    case 'cose':
      layoutOptions = {
        name: 'cose',
        animate: true,
        animationDuration: 1500,
        nodeRepulsion: 800000,
        idealEdgeLength: 120,
        edgeElasticity: 200
      };
      break;
    case 'circle':
      layoutOptions = {
        name: 'circle',
        animate: true,
        animationDuration: 1500,
        radius: 300
      };
      break;
    case 'breadthfirst':
      layoutOptions = {
        name: 'breadthfirst',
        animate: true,
        animationDuration: 1500,
        directed: true,
        roots: '#foundations',
        spacingFactor: 2
      };
      break;
    case 'grid':
      layoutOptions = {
        name: 'grid',
        animate: true,
        animationDuration: 1500,
        rows: 6,
        cols: 6
      };
      break;
    case 'concentric':
      layoutOptions = {
        name: 'concentric',
        animate: true,
        animationDuration: 1500,
        concentric: function(node) {
          return 10 - node.data('level');
        },
        levelWidth: function(nodes) {
          return 2;
        }
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
  console.log('DOM Content Loaded - Complete Mathematical Theory Organism');
  console.log('Cytoscape available:', typeof cytoscape !== 'undefined');
  
  setTimeout(async () => {
    try {
      await initializeCytoscape();
      
      // 段階的アニメーション効果
      setTimeout(() => {
        if (cy) {
          cy.elements().forEach((ele, index) => {
            setTimeout(() => {
              ele.style('opacity', 1);
            }, index * 20);
          });
        }
      }, 500);
      
    } catch (error) {
      console.error('Error initializing complete mathematical theory organism:', error);
    }
  }, 300);
});

// グローバル関数として公開
window.resetView = resetView;
window.fitToView = fitToView;
window.toggleLayout = toggleLayout; 