"use client";

import cytoscape, { Core } from "cytoscape";
import { useEffect, useRef, useState } from "react";
// @ts-ignore
import dagre from "cytoscape-dagre";

// Cytoscapeの拡張を登録
if (typeof window !== "undefined") {
  cytoscape.use(dagre);
}

interface ProcessNode {
  id: string;
  label: string;
  layer: string;
  description?: string;
  type?: string;
  category?: string;
  details?: {
    responsibilities?: string[];
    technologies?: string[];
  };
}

interface ProcessEdge {
  id: string;
  source: string;
  target: string;
  label?: string;
  type?: string;
  description?: string;
  details?: {
    process?: string;
    artifacts?: string[];
  };
}

interface ProcessLayoutPatternProps {
  data?: {
    nodes: ProcessNode[];
    edges: ProcessEdge[];
  };
  width?: number;
  height?: number;
  dataUrl?: string;
}

/**
 * プロセスレイアウトパターンコンポーネント
 * 画像の項目を縦軸とした階層的なプロセスフローを表示
 */
export default function ProcessLayoutPattern({
  data,
  width = 1200,
  height = 800,
  dataUrl = "/data/process-layout-data.json",
}: ProcessLayoutPatternProps) {
  const containerRef = useRef<HTMLDivElement>(null);
  const cyRef = useRef<Core | null>(null);
  const [selectedNode, setSelectedNode] = useState<ProcessNode | null>(null);
  const [selectedEdge, setSelectedEdge] = useState<ProcessEdge | null>(null);
  const [processData, setProcessData] = useState<{
    nodes: ProcessNode[];
    edges: ProcessEdge[];
  } | null>(null);
  const [loading, setLoading] = useState(true);

  // デフォルトのプロセスデータ（画像の項目に基づく）
  const defaultData = {
    nodes: [
      // Layer (層) - 最上位
      {
        id: "layer",
        label: "Layer (層)",
        layer: "layer",
        description: "システム全体の層構造を定義",
        type: "layer",
        category: "Architecture",
        details: {
          responsibilities: [
            "システム全体の層構造を定義",
            "アーキテクチャパターンの決定",
            "技術スタックの選択",
          ],
          technologies: [
            "アーキテクチャフレームワーク",
            "設計パターン",
            "技術標準",
          ],
        },
      },

      // 既存モデル (Existing Model)
      {
        id: "existing_model",
        label: "既存モデル",
        layer: "existing_model",
        description: "既存のビジネスモデルとデータモデル",
        type: "model",
        category: "Business",
        details: {
          responsibilities: [
            "ビジネスモデルの定義",
            "データモデルの設計",
            "ドメインロジックの実装",
          ],
          technologies: ["UML", "ER図", "ドメイン駆動設計"],
        },
      },

      // 既存機能 (Existing Function)
      {
        id: "existing_function",
        label: "既存機能",
        layer: "existing_function",
        description: "現在実装されている機能とサービス",
        type: "function",
        category: "Service",
        details: {
          responsibilities: [
            "ビジネス機能の実装",
            "サービスの提供",
            "ユーザーインターフェース",
          ],
          technologies: ["React", "Node.js", "REST API"],
        },
      },

      // ISM: LTSから (ISM: From LTS)
      {
        id: "ism_lts",
        label: "ISM: LTSから",
        layer: "ism_lts",
        description: "Long Term Supportからの統合サービス管理",
        type: "management",
        category: "Integration",
        details: {
          responsibilities: [
            "統合サービス管理",
            "長期サポート",
            "システム統合",
          ],
          technologies: ["ITIL", "サービス管理", "統合プラットフォーム"],
        },
      },

      // Web Server
      {
        id: "web_server",
        label: "Web Server",
        layer: "web_server",
        description: "Webアプリケーションサーバーとフロントエンド",
        type: "server",
        category: "Infrastructure",
        details: {
          responsibilities: [
            "Webアプリケーションの配信",
            "フロントエンドの提供",
            "サーバーサイド処理",
          ],
          technologies: ["Next.js", "Express.js", "Nginx"],
        },
      },

      // API Gateway/LB/Auth
      {
        id: "api_gateway",
        label: "API Gateway\nLB\nAuth",
        layer: "api_gateway",
        description: "API Gateway、Load Balancer、認証システム",
        type: "gateway",
        category: "Security",
        details: {
          responsibilities: ["API Gateway機能", "負荷分散", "認証・認可"],
          technologies: ["Kong", "OAuth2", "JWT"],
        },
      },
    ],
    edges: [
      // 層から既存モデルへの接続
      {
        id: "layer_to_model",
        source: "layer",
        target: "existing_model",
        label: "定義",
        type: "definition",
        description: "層から既存モデルへの定義プロセス",
        details: {
          process: "アーキテクチャ定義からモデル設計への変換",
          artifacts: ["アーキテクチャ文書", "モデル設計書", "技術仕様書"],
        },
      },

      // 既存モデルから既存機能への接続
      {
        id: "model_to_function",
        source: "existing_model",
        target: "existing_function",
        label: "実装",
        type: "implementation",
        description: "既存モデルから既存機能への実装プロセス",
        details: {
          process: "モデル設計から機能実装への変換",
          artifacts: ["実装コード", "テストケース", "ドキュメント"],
        },
      },

      // 既存機能からISMへの接続
      {
        id: "function_to_ism",
        source: "existing_function",
        target: "ism_lts",
        label: "統合",
        type: "integration",
        description: "既存機能からISMへの統合プロセス",
        details: {
          process: "機能から統合サービスへの変換",
          artifacts: ["統合仕様書", "API仕様", "サービスカタログ"],
        },
      },

      // ISMからWeb Serverへの接続
      {
        id: "ism_to_webserver",
        source: "ism_lts",
        target: "web_server",
        label: "配信",
        type: "delivery",
        description: "ISMからWeb Serverへの配信プロセス",
        details: {
          process: "統合サービスからWeb配信への変換",
          artifacts: ["デプロイメント設定", "配信パイプライン", "監視設定"],
        },
      },

      // Web ServerからAPI Gatewayへの接続
      {
        id: "webserver_to_gateway",
        source: "web_server",
        target: "api_gateway",
        label: "保護",
        type: "security",
        description: "Web ServerからAPI Gatewayへの保護プロセス",
        details: {
          process: "Webサービスからセキュアゲートウェイへの変換",
          artifacts: ["セキュリティ設定", "認証設定", "アクセス制御"],
        },
      },
    ],
  };

  // データファイルの読み込み
  useEffect(() => {
    const loadData = async () => {
      try {
        setLoading(true);
        if (data) {
          setProcessData(data);
        } else {
          const response = await fetch(dataUrl);
          if (response.ok) {
            const jsonData = await response.json();
            setProcessData({
              nodes: jsonData.nodes || defaultData.nodes,
              edges: jsonData.edges || defaultData.edges,
            });
          } else {
            setProcessData(defaultData);
          }
        }
      } catch (error) {
        console.error("データの読み込みに失敗しました:", error);
        setProcessData(defaultData);
      } finally {
        setLoading(false);
      }
    };

    loadData();
  }, [data, dataUrl]);

  const finalData = processData || defaultData;

  /**
   * プロセスレイアウトの設定を取得
   */
  const getProcessLayout = () => {
    return {
      name: "dagre",
      rankDir: "TB", // Top to Bottom
      nodeDimensionsIncludeLabels: true,
      rankSep: 100, // 層間の距離
      nodeSep: 50, // ノード間の距離
      edgeSep: 20, // エッジ間の距離
      ranker: "network-simplex",
      padding: 50,
    };
  };

  /**
   * ノードのスタイル設定
   */
  const getNodeStyle = (node: ProcessNode) => {
    const baseStyle = {
      "background-color": "#2c3e50",
      "border-color": "#34495e",
      "border-width": "2px",
      color: "#ecf0f1",
      "text-valign": "center",
      "text-halign": "center",
      "text-wrap": "wrap",
      "text-max-width": "150px",
      "font-size": "12px",
      "font-weight": "bold",
      shape: "rectangle",
      width: "120px",
      height: "80px",
      padding: "10px",
    };

    // 層に応じた色分け
    const layerColors: { [key: string]: string } = {
      layer: "#e74c3c", // 赤 - 最上位層
      existing_model: "#f39c12", // オレンジ - モデル層
      existing_function: "#f1c40f", // 黄 - 機能層
      ism_lts: "#27ae60", // 緑 - 統合層
      web_server: "#3498db", // 青 - サーバー層
      api_gateway: "#9b59b6", // 紫 - ゲートウェイ層
    };

    return {
      ...baseStyle,
      "background-color": layerColors[node.layer] || "#2c3e50",
    };
  };

  /**
   * エッジのスタイル設定
   */
  const getEdgeStyle = () => {
    return {
      width: "3px",
      "line-color": "#7f8c8d",
      "target-arrow-color": "#7f8c8d",
      "target-arrow-shape": "triangle",
      "curve-style": "bezier",
      label: "data(label)",
      "text-rotation": "autorotate",
      "text-margin-y": "-10px",
      "font-size": "10px",
      color: "#2c3e50",
    };
  };

  /**
   * Cytoscapeインスタンスの初期化
   */
  useEffect(() => {
    if (!containerRef.current || loading) return;

    // 既存のインスタンスをクリーンアップ
    if (cyRef.current) {
      cyRef.current.destroy();
    }

    // 新しいCytoscapeインスタンスを作成
    const cy = cytoscape({
      container: containerRef.current,
      elements: {
        nodes: finalData.nodes.map((node) => ({
          group: "nodes",
          data: {
            id: node.id,
            label: node.label,
            layer: node.layer,
            description: node.description,
            type: node.type,
            category: node.category,
            details: node.details,
          },
        })),
        edges: finalData.edges.map((edge) => ({
          group: "edges",
          data: {
            id: edge.id,
            source: edge.source,
            target: edge.target,
            label: edge.label,
            type: edge.type,
            description: edge.description,
            details: edge.details,
          },
        })),
      },
      style: [
        {
          selector: "node",
          style: {
            "background-color": "data(backgroundColor)",
            "border-color": "data(borderColor)",
            "border-width": "2px",
            color: "#ecf0f1",
            "text-valign": "center",
            "text-halign": "center",
            "text-wrap": "wrap",
            "text-max-width": "150px",
            "font-size": "12px",
            "font-weight": "bold",
            shape: "rectangle",
            width: "120px",
            height: "80px",
            padding: "10px",
          },
        },
        {
          selector: "edge",
          style: {
            width: 3,
            "line-color": "#7f8c8d",
            "target-arrow-color": "#7f8c8d",
            "target-arrow-shape": "triangle",
            "curve-style": "bezier",
            label: "data(label)",
            "text-rotation": "autorotate",
            "text-margin-y": "-10px",
            "font-size": "10px",
            color: "#2c3e50",
          },
        },
        {
          selector: "node:selected",
          style: {
            "border-color": "#e74c3c",
            "border-width": "4px",
            "background-color": "data(backgroundColor)",
          },
        },
        {
          selector: "edge:selected",
          style: {
            "line-color": "#e74c3c",
            "target-arrow-color": "#e74c3c",
            width: 5,
          },
        },
      ],
      layout: getProcessLayout(),
    });

    // ノードクリックイベント
    cy.on("tap", "node", (evt: any) => {
      const node = evt.target;
      const nodeData = node.data();
      setSelectedNode({
        id: nodeData.id,
        label: nodeData.label,
        layer: nodeData.layer,
        description: nodeData.description,
        type: nodeData.type,
        category: nodeData.category,
        details: nodeData.details,
      });
      setSelectedEdge(null);
    });

    // エッジクリックイベント
    cy.on("tap", "edge", (evt: any) => {
      const edge = evt.target;
      const edgeData = edge.data();
      setSelectedEdge({
        id: edgeData.id,
        source: edgeData.source,
        target: edgeData.target,
        label: edgeData.label,
        type: edgeData.type,
        description: edgeData.description,
        details: edgeData.details,
      });
      setSelectedNode(null);
    });

    // 背景クリックで選択解除
    cy.on("tap", (evt: any) => {
      if (evt.target === cy) {
        setSelectedNode(null);
        setSelectedEdge(null);
      }
    });

    // ズームとパン機能
    cy.on("mouseover", "node", (evt: any) => {
      evt.target.style("border-color", "#f39c12");
      evt.target.style("border-width", "3px");
    });

    cy.on("mouseout", "node", (evt: any) => {
      evt.target.style("border-color", "#34495e");
      evt.target.style("border-width", "2px");
    });

    cyRef.current = cy;

    // クリーンアップ関数
    return () => {
      if (cyRef.current) {
        cyRef.current.destroy();
        cyRef.current = null;
      }
    };
  }, [finalData, loading]);

  /**
   * レイアウトの再適用
   */
  const applyLayout = () => {
    if (cyRef.current) {
      cyRef.current.layout(getProcessLayout()).run();
    }
  };

  /**
   * ズームリセット
   */
  const resetZoom = () => {
    if (cyRef.current) {
      cyRef.current.fit();
      cyRef.current.center();
    }
  };

  if (loading) {
    return (
      <div className="flex items-center justify-center h-96">
        <div className="text-lg text-gray-600">データを読み込み中...</div>
      </div>
    );
  }

  return (
    <div className="process-layout-pattern">
      <div className="controls mb-4 flex gap-2">
        <button
          onClick={applyLayout}
          className="px-4 py-2 bg-blue-500 text-white rounded hover:bg-blue-600"
        >
          レイアウト再適用
        </button>
        <button
          onClick={resetZoom}
          className="px-4 py-2 bg-green-500 text-white rounded hover:bg-green-600"
        >
          ズームリセット
        </button>
      </div>

      <div className="visualization-container relative">
        <div
          ref={containerRef}
          style={{
            width: `${width}px`,
            height: `${height}px`,
            border: "2px solid #34495e",
            borderRadius: "8px",
            backgroundColor: "#ecf0f1",
          }}
        />

        {/* 選択されたノードの情報パネル */}
        {selectedNode && (
          <div className="info-panel absolute top-4 right-4 bg-white p-4 rounded-lg shadow-lg border border-gray-200 max-w-sm">
            <h3 className="text-lg font-bold mb-2 text-gray-800">
              {selectedNode.label}
            </h3>
            <div className="space-y-2 text-sm text-gray-600">
              <p>
                <strong>層:</strong> {selectedNode.layer}
              </p>
              {selectedNode.type && (
                <p>
                  <strong>タイプ:</strong> {selectedNode.type}
                </p>
              )}
              {selectedNode.category && (
                <p>
                  <strong>カテゴリ:</strong> {selectedNode.category}
                </p>
              )}
              {selectedNode.description && (
                <p>
                  <strong>説明:</strong> {selectedNode.description}
                </p>
              )}
              {selectedNode.details?.responsibilities && (
                <div>
                  <p>
                    <strong>責任:</strong>
                  </p>
                  <ul className="list-disc list-inside ml-2">
                    {selectedNode.details.responsibilities.map(
                      (resp, index) => (
                        <li key={index}>{resp}</li>
                      )
                    )}
                  </ul>
                </div>
              )}
              {selectedNode.details?.technologies && (
                <div>
                  <p>
                    <strong>技術:</strong>
                  </p>
                  <ul className="list-disc list-inside ml-2">
                    {selectedNode.details.technologies.map((tech, index) => (
                      <li key={index}>{tech}</li>
                    ))}
                  </ul>
                </div>
              )}
            </div>
            <button
              onClick={() => setSelectedNode(null)}
              className="mt-3 px-3 py-1 bg-gray-500 text-white rounded text-xs hover:bg-gray-600"
            >
              閉じる
            </button>
          </div>
        )}

        {/* 選択されたエッジの情報パネル */}
        {selectedEdge && (
          <div className="info-panel absolute top-4 right-4 bg-white p-4 rounded-lg shadow-lg border border-gray-200 max-w-sm">
            <h3 className="text-lg font-bold mb-2 text-gray-800">
              {selectedEdge.label}
            </h3>
            <div className="space-y-2 text-sm text-gray-600">
              <p>
                <strong>タイプ:</strong> {selectedEdge.type}
              </p>
              {selectedEdge.description && (
                <p>
                  <strong>説明:</strong> {selectedEdge.description}
                </p>
              )}
              {selectedEdge.details?.process && (
                <p>
                  <strong>プロセス:</strong> {selectedEdge.details.process}
                </p>
              )}
              {selectedEdge.details?.artifacts && (
                <div>
                  <p>
                    <strong>成果物:</strong>
                  </p>
                  <ul className="list-disc list-inside ml-2">
                    {selectedEdge.details.artifacts.map((artifact, index) => (
                      <li key={index}>{artifact}</li>
                    ))}
                  </ul>
                </div>
              )}
            </div>
            <button
              onClick={() => setSelectedEdge(null)}
              className="mt-3 px-3 py-1 bg-gray-500 text-white rounded text-xs hover:bg-gray-600"
            >
              閉じる
            </button>
          </div>
        )}
      </div>

      {/* レイヤー説明 */}
      <div className="layer-legend mt-4 p-4 bg-gray-50 rounded-lg">
        <h4 className="text-lg font-bold mb-3 text-gray-800">
          プロセスレイヤー説明
        </h4>
        <div className="grid grid-cols-2 md:grid-cols-3 gap-4 text-sm">
          <div className="flex items-center">
            <div className="w-4 h-4 bg-red-500 rounded mr-2"></div>
            <span>Layer (層) - システム全体の層構造</span>
          </div>
          <div className="flex items-center">
            <div className="w-4 h-4 bg-orange-500 rounded mr-2"></div>
            <span>既存モデル - ビジネス・データモデル</span>
          </div>
          <div className="flex items-center">
            <div className="w-4 h-4 bg-yellow-500 rounded mr-2"></div>
            <span>既存機能 - 実装済みサービス</span>
          </div>
          <div className="flex items-center">
            <div className="w-4 h-4 bg-green-500 rounded mr-2"></div>
            <span>ISM: LTS - 統合サービス管理</span>
          </div>
          <div className="flex items-center">
            <div className="w-4 h-4 bg-blue-500 rounded mr-2"></div>
            <span>Web Server - アプリケーションサーバー</span>
          </div>
          <div className="flex items-center">
            <div className="w-4 h-4 bg-purple-500 rounded mr-2"></div>
            <span>API Gateway - ゲートウェイ・認証</span>
          </div>
        </div>
      </div>
    </div>
  );
}
