/**
 * 数学理論生物体系図 - Cytoscapeアプリケーション
 * Mathematical Theory Organism Visualization
 */

// グローバル変数
let cy;
let currentLayout = 'grid';

/**
 * 数学理論の階層とノードデータ定義
 * 生物学的メタファーによる表現：
 * - 基礎論：根系（Root System）
 * - 純粋数学：幹・枝（Trunk & Branches）
 * - 応用数学：葉・花（Leaves & Flowers）
 * - 学際領域：果実・種（Fruits & Seeds）
 */
const mathTheoryData = {
  nodes: [
    // 基礎論（根系）- Root System
    {
      data: { 
        id: 'foundations', 
        label: '基礎論\n(根系)', 
        type: 'foundation',
        category: '基礎論',
        description: '数学全体の土台となる理論。他のすべての数学分野の正当性と定義を支える根系のような存在。',
        details: '数理論理学、集合論、圏論から構成される数学の基盤理論'
      }
    },
    {
      data: { 
        id: 'logic', 
        label: '数理論理学', 
        type: 'foundation',
        category: '基礎論',
        description: '命題論理、述語論理、モデル理論、証明論を包含。他分野の正当性証明や形式化の基盤となる主根。',
        details: '数学的推論の形式化と証明の構造を研究する分野'
      }
    },
    {
      data: { 
        id: 'set_theory', 
        label: '集合論', 
        type: 'foundation',
        category: '基礎論',
        description: '公理的集合論、順序数・基数、連続体仮説。「数」「関数」「空間」を定義する栄養豊富な土壌。',
        details: 'ZF公理系に基づく現代数学の基本概念の定義'
      }
    },
    {
      data: { 
        id: 'category_theory', 
        label: '圏論', 
        type: 'foundation',
        category: '基礎論',
        description: 'オブジェクトと射、極限・余極限、自然変換。代数・位相・論理を統一的に記述する根の結合組織。',
        details: '数学の諸分野を統一的に記述する抽象的言語'
      }
    },

    // 純粋数学（幹・枝）- Trunk & Branches
    {
      data: { 
        id: 'pure_math', 
        label: '純粋数学\n(主幹)', 
        type: 'pure_math',
        category: '純粋数学',
        description: '基礎論から栄養を得て成長する主幹。理論的探究と美的追求を目的とする数学の中核部分。',
        details: '代数学、幾何学、解析学、数論、組合せ論の5つの主要分野から構成'
      }
    },
    {
      data: { 
        id: 'algebra', 
        label: '代数学\n(太枝)', 
        type: 'pure_math',
        category: '純粋数学',
        description: '群論、環論、体論、線型代数学、表現論、ホモロジー代数。構造と対称性を研究する力強い枝。',
        details: '数学的構造の抽象化と一般化を追究する分野'
      }
    },
    {
      data: { 
        id: 'geometry_topology', 
        label: '幾何学・位相学\n(美しい枝)', 
        type: 'pure_math',
        category: '純粋数学',
        description: 'ユークリッド幾何、微分幾何、位相空間論、代数幾何。形と空間の本質を探究する優雅な枝。',
        details: '空間と形の性質を研究する視覚的・直感的な数学分野'
      }
    },
    {
      data: { 
        id: 'analysis', 
        label: '解析学\n(しなやかな枝)', 
        type: 'pure_math',
        category: '純粋数学',
        description: '微分積分、実解析、複素解析、フーリエ解析、数値解析。連続性と変化を捉えるしなやかな枝。',
        details: '連続性、極限、微分積分を中心とする数学の基幹分野'
      }
    },
    {
      data: { 
        id: 'number_theory', 
        label: '数論\n(神秘的な枝)', 
        type: 'pure_math',
        category: '純粋数学',
        description: '初等数論、解析的数論、代数的数論、モジュラー形式。整数の深遠な性質を探究する神秘的な枝。',
        details: '整数とその性質を研究する数学の女王と呼ばれる分野'
      }
    },
    {
      data: { 
        id: 'combinatorics', 
        label: '組合せ論・離散数学\n(複雑な枝)', 
        type: 'pure_math',
        category: '純粋数学',
        description: '組合せ論、グラフ理論、計算理論、プロセス代数、暗号理論。離散構造を扱う複雑に分岐した枝。',
        details: '有限構造と離散的対象を研究する現代的な数学分野'
      }
    },

    // 応用数学（葉・花）- Leaves & Flowers
    {
      data: { 
        id: 'applied_math', 
        label: '応用数学\n(葉群)', 
        type: 'applied_math',
        category: '応用数学',
        description: '純粋数学の成果を実際の問題に適用。理論と現実を結ぶ光合成を行う葉群。',
        details: '数学理論を実世界の問題解決に応用する実践的分野'
      }
    },
    {
      data: { 
        id: 'probability_statistics', 
        label: '確率論・統計学\n(黄金の葉)', 
        type: 'applied_math',
        category: '応用数学',
        description: '不確実性と データの科学。現代社会の意思決定を支える黄金に輝く葉。',
        details: 'ランダム現象の数学的記述とデータ分析の理論'
      }
    },
    {
      data: { 
        id: 'optimization', 
        label: '最適化理論\n(効率の葉)', 
        type: 'applied_math',
        category: '応用数学',
        description: '線型・非線型計画法、整数計画法。最良解を求める効率性を追求する機能的な葉。',
        details: '制約条件下での最適解を求める数学的手法'
      }
    },
    {
      data: { 
        id: 'pde', 
        label: '偏微分方程式\n(動的な葉)', 
        type: 'applied_math',
        category: '応用数学',
        description: '物理現象の数学的記述。自然界の動的変化を表現する生命力溢れる葉。',
        details: '多変数関数の微分方程式による自然現象のモデル化'
      }
    },
    {
      data: { 
        id: 'computational', 
        label: '数値計算・計算科学\n(技術の葉)', 
        type: 'applied_math',
        category: '応用数学',
        description: '数値シミュレーション、アルゴリズム。計算技術と融合した現代的な葉。',
        details: 'コンピュータを用いた数値的手法による問題解決'
      }
    },

    // 学際領域（果実・種）- Fruits & Seeds
    {
      data: { 
        id: 'interdisciplinary', 
        label: '学際領域\n(果実群)', 
        type: 'interdisciplinary',
        category: '学際領域',
        description: '複数分野を融合し新たな理論・手法を創出。次世代の数学を孕む豊かな果実群。',
        details: '数学と他分野の境界で生まれる新しい研究領域'
      }
    },
    {
      data: { 
        id: 'math_physics', 
        label: '数理物理学\n(理論の果実)', 
        type: 'interdisciplinary',
        category: '学際領域',
        description: '量子場理論、弦理論の数学的定式化。物理と数学の深い結合から生まれる理論の果実。',
        details: '物理学の問題を数学的に厳密に定式化する分野'
      }
    },
    {
      data: { 
        id: 'math_biology', 
        label: '数理生物学\n(生命の果実)', 
        type: 'interdisciplinary',
        category: '学際領域',
        description: 'ダイナミカルシステム、ネットワークモデル。生命現象を数理的に解明する生命の果実。',
        details: '生物学的現象の数学的モデル化と解析'
      }
    },
    {
      data: { 
        id: 'data_science', 
        label: 'データサイエンス・AI\n(知識の種)', 
        type: 'interdisciplinary',
        category: '学際領域',
        description: '統計学、最適化、線型代数の融合。未来の知識を育む新しい種。',
        details: 'ビッグデータと機械学習による知識発見の科学'
      }
    }
  ],

  edges: [
    // 基礎論からの栄養供給（根からの栄養吸収）
    { data: { source: 'foundations', target: 'pure_math', type: 'foundation_to_pure' } },
    { data: { source: 'logic', target: 'foundations', type: 'nutrient_flow' } },
    { data: { source: 'set_theory', target: 'foundations', type: 'nutrient_flow' } },
    { data: { source: 'category_theory', target: 'foundations', type: 'nutrient_flow' } },

    // 純粋数学内部の成長（幹から枝への栄養供給）
    { data: { source: 'pure_math', target: 'algebra', type: 'growth_flow' } },
    { data: { source: 'pure_math', target: 'geometry_topology', type: 'growth_flow' } },
    { data: { source: 'pure_math', target: 'analysis', type: 'growth_flow' } },
    { data: { source: 'pure_math', target: 'number_theory', type: 'growth_flow' } },
    { data: { source: 'pure_math', target: 'combinatorics', type: 'growth_flow' } },

    // 純粋数学間の相互作用（枝間の栄養交換）
    { data: { source: 'algebra', target: 'geometry_topology', type: 'cross_pollination' } },
    { data: { source: 'algebra', target: 'number_theory', type: 'cross_pollination' } },
    { data: { source: 'analysis', target: 'geometry_topology', type: 'cross_pollination' } },
    { data: { source: 'analysis', target: 'number_theory', type: 'cross_pollination' } },

    // 応用数学への展開（枝から葉への栄養供給）
    { data: { source: 'algebra', target: 'applied_math', type: 'application_flow' } },
    { data: { source: 'analysis', target: 'applied_math', type: 'application_flow' } },
    { data: { source: 'applied_math', target: 'probability_statistics', type: 'specialization' } },
    { data: { source: 'applied_math', target: 'optimization', type: 'specialization' } },
    { data: { source: 'applied_math', target: 'pde', type: 'specialization' } },
    { data: { source: 'applied_math', target: 'computational', type: 'specialization' } },

    // 学際領域への結実（葉から果実への栄養集積）
    { data: { source: 'probability_statistics', target: 'interdisciplinary', type: 'synthesis_flow' } },
    { data: { source: 'optimization', target: 'interdisciplinary', type: 'synthesis_flow' } },
    { data: { source: 'pde', target: 'interdisciplinary', type: 'synthesis_flow' } },
    { data: { source: 'interdisciplinary', target: 'math_physics', type: 'fruition' } },
    { data: { source: 'interdisciplinary', target: 'math_biology', type: 'fruition' } },
    { data: { source: 'interdisciplinary', target: 'data_science', type: 'fruition' } },

    // 基礎論への直接的依存（深根からの直接栄養供給）
    { data: { source: 'logic', target: 'combinatorics', type: 'deep_root_support' } },
    { data: { source: 'set_theory', target: 'analysis', type: 'deep_root_support' } },
    { data: { source: 'category_theory', target: 'geometry_topology', type: 'deep_root_support' } }
  ]
};

/**
 * Cytoscapeインスタンスの初期化
 */
function initializeCytoscape() {
  console.log('Initializing Cytoscape...');
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
      // 基本スタイル
      {
        selector: 'node',
        style: {
          'background-color': '#555555',
          'label': 'data(label)',
          'width': 60,
          'height': 60,
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
          'text-max-width': '120px'
        }
      },
      
      // 基礎論（根系）のスタイル
      {
        selector: 'node[type="foundation"]',
        style: {
          'background-color': '#8B4513',
          'shape': 'round-hexagon',
          'width': 80,
          'height': 80,
          'background-gradient-direction': 'to-bottom',
          'background-gradient-stop-colors': '#8B4513 #A0522D #8B4513',
          'box-shadow': '0 0 20px rgba(139, 69, 19, 0.8)',
          'border-color': '#CD853F'
        }
      },
      
      // 純粋数学（幹・枝）のスタイル
      {
        selector: 'node[type="pure_math"]',
        style: {
          'background-color': '#228B22',
          'shape': 'round-rectangle',
          'width': 90,
          'height': 70,
          'background-gradient-direction': 'to-bottom',
          'background-gradient-stop-colors': '#228B22 #32CD32 #228B22',
          'box-shadow': '0 0 20px rgba(34, 139, 34, 0.8)',
          'border-color': '#90EE90'
        }
      },
      
      // 応用数学（葉・花）のスタイル
      {
        selector: 'node[type="applied_math"]',
        style: {
          'background-color': '#FFD700',
          'shape': 'round-diamond',
          'width': 70,
          'height': 70,
          'background-gradient-direction': 'to-bottom',
          'background-gradient-stop-colors': '#FFD700 #FFA500 #FFD700',
          'box-shadow': '0 0 20px rgba(255, 215, 0, 0.8)',
          'border-color': '#FFFF00',
          'color': '#000000',
          'text-shadow': '0 0 2px rgba(255, 255, 255, 0.8)'
        }
      },
      
      // 学際領域（果実・種）のスタイル
      {
        selector: 'node[type="interdisciplinary"]',
        style: {
          'background-color': '#FF6347',
          'shape': 'round-octagon',
          'width': 85,
          'height': 85,
          'background-gradient-direction': 'to-bottom',
          'background-gradient-stop-colors': '#FF6347 #FF4500 #FF6347',
          'box-shadow': '0 0 25px rgba(255, 99, 71, 0.9)',
          'border-color': '#FF7F50'
        }
      },
      
      // エッジの基本スタイル
      {
        selector: 'edge',
        style: {
          'width': 3,
          'line-color': '#00d4aa',
          'target-arrow-color': '#00d4aa',
          'target-arrow-shape': 'triangle',
          'curve-style': 'bezier',
          'opacity': 0.7,
          'arrow-scale': 1.5
        }
      },
      
      // 栄養供給フロー（根からの栄養）
      {
        selector: 'edge[type="nutrient_flow"], edge[type="foundation_to_pure"]',
        style: {
          'line-color': '#8B4513',
          'target-arrow-color': '#8B4513',
          'width': 4,
          'line-style': 'solid',
          'source-arrow-shape': 'circle',
          'source-arrow-color': '#CD853F'
        }
      },
      
      // 成長フロー（幹から枝）
      {
        selector: 'edge[type="growth_flow"]',
        style: {
          'line-color': '#228B22',
          'target-arrow-color': '#228B22',
          'width': 4,
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
          'width': 3,
          'line-style': 'solid'
        }
      },
      
      // 統合フロー（葉から果実）
      {
        selector: 'edge[type="synthesis_flow"], edge[type="fruition"]',
        style: {
          'line-color': '#FF6347',
          'target-arrow-color': '#FF6347',
          'width': 3,
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
      
      // ホバー・選択時のスタイル
      {
        selector: 'node:selected',
        style: {
          'border-width': 4,
          'border-color': '#00d4aa',
          'box-shadow': '0 0 30px rgba(0, 212, 170, 1)'
        }
      },
      
      {
        selector: 'node:active',
        style: {
          'overlay-color': '#00d4aa',
          'overlay-padding': 10,
          'overlay-opacity': 0.25
        }
      }
    ],
    
    layout: {
      name: 'grid',
      rows: 4,
      cols: 5,
      animate: true,
      animationDuration: 1000,
      fit: true,
      padding: 50
    }
  });
  
  console.log('Cytoscape initialized successfully');
  console.log('Number of nodes:', cy.nodes().length);
  console.log('Number of edges:', cy.edges().length);
  
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
    
    infoPanel.classList.add('visible');
    
    // 関連ノードのハイライト
    highlightConnectedNodes(node);
  });
  
  // 背景クリックで情報パネルを閉じる
  cy.on('tap', function(evt) {
    if (evt.target === cy) {
      infoPanel.classList.remove('visible');
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
  cy.fit();
  cy.center();
  cy.elements().removeClass('highlighted dimmed');
  document.getElementById('infoPanel').classList.remove('visible');
}

/**
 * 全体表示
 */
function fitToView() {
  cy.fit(cy.elements(), 50);
}

/**
 * レイアウト変更
 */
function toggleLayout() {
  const layouts = ['dagre', 'cose-bilkent', 'circle', 'grid'];
  const currentIndex = layouts.indexOf(currentLayout);
  const nextIndex = (currentIndex + 1) % layouts.length;
  currentLayout = layouts[nextIndex];
  
  let layoutOptions;
  
  switch(currentLayout) {
    case 'dagre':
      layoutOptions = {
        name: 'dagre',
        rankDir: 'TB',
        spacingFactor: 1.5,
        animate: true,
        animationDuration: 1000
      };
      break;
    case 'cose-bilkent':
      layoutOptions = {
        name: 'cose-bilkent',
        animate: true,
        animationDuration: 1000,
        nodeRepulsion: 4500,
        idealEdgeLength: 100,
        edgeElasticity: 0.45
      };
      break;
    case 'circle':
      layoutOptions = {
        name: 'circle',
        animate: true,
        animationDuration: 1000,
        radius: 200
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
  }
  
  cy.layout(layoutOptions).run();
}

/**
 * ページ読み込み時の初期化
 */
document.addEventListener('DOMContentLoaded', function() {
  console.log('DOM Content Loaded');
  console.log('Cytoscape available:', typeof cytoscape !== 'undefined');
  console.log('Dagre extension available:', typeof cytoscape('core', 'layout').dagre !== 'undefined');
  console.log('Cose-bilkent extension available:', typeof cytoscape('core', 'layout').coseBilkent !== 'undefined');
  
  // レイアウト拡張の登録
  if (typeof cytoscape !== 'undefined' && typeof dagre !== 'undefined') {
    console.log('Registering dagre extension...');
    try {
      cytoscape.use(dagre);
      console.log('Dagre extension registered successfully');
    } catch (e) {
      console.error('Error registering dagre:', e);
    }
  }
  
  if (typeof cytoscape !== 'undefined' && typeof coseBilkent !== 'undefined') {
    console.log('Registering cose-bilkent extension...');
    try {
      cytoscape.use(coseBilkent);
      console.log('Cose-bilkent extension registered successfully');
    } catch (e) {
      console.error('Error registering cose-bilkent:', e);
    }
  }
  
  try {
    initializeCytoscape();
  } catch (error) {
    console.error('Error initializing Cytoscape:', error);
  }
  
  // アニメーション効果の追加
  setTimeout(() => {
    cy.elements().forEach((ele, index) => {
      setTimeout(() => {
        ele.style('opacity', 1);
      }, index * 50);
    });
  }, 500);
});

// グローバル関数として公開
window.resetView = resetView;
window.fitToView = fitToView;
window.toggleLayout = toggleLayout; 